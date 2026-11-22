#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

extern char **environ;

/* Opaque monotonically allocated IDs, never native pointers or reusable file descriptors. */
typedef struct Pty {
    jlong id;
    int references; /* registry_lock */
    pthread_mutex_t lock;
    pthread_cond_t changed;
    int master;
    int cancel[2];
    pid_t pid;
    int child_published;
    int closed;
    int exited;
    int code;
    struct Pty *next;
} Pty;
static pthread_mutex_t registry_lock = PTHREAD_MUTEX_INITIALIZER;
static Pty *registry;
static jlong next_id = 1;

static void fail(JNIEnv *env, const char *code) {
    jclass type = (*env)->FindClass(env, "java/io/IOException");
    if (type) (*env)->ThrowNew(env, type, code);
}
static void release(Pty *pty) {
    pthread_mutex_lock(&registry_lock);
    int last = --pty->references == 0;
    pthread_mutex_unlock(&registry_lock);
    if (last) {
        if (pty->master >= 0) close(pty->master);
        close(pty->cancel[0]); close(pty->cancel[1]);
        pthread_cond_destroy(&pty->changed); pthread_mutex_destroy(&pty->lock);
        free(pty);
    }
}
static Pty *acquire(JNIEnv *env, jlong id) {
    pthread_mutex_lock(&registry_lock);
    Pty *pty = registry;
    while (pty && pty->id != id) pty = pty->next;
    if (pty) ++pty->references;
    pthread_mutex_unlock(&registry_lock);
    if (!pty) fail(env, "terminal_closed");
    return pty;
}
/* Each I/O operation owns a duplicate; close wakes poll before those duplicates are released.
 * This avoids closing a descriptor in one thread while another can use its recycled number. */
static int io_fd(JNIEnv *env, Pty *pty) {
    pthread_mutex_lock(&pty->lock);
    int fd = pty->closed ? -1 : fcntl(pty->master, F_DUPFD_CLOEXEC, 3);
    int closed = pty->closed;
    pthread_mutex_unlock(&pty->lock);
    if (fd < 0) fail(env, closed ? "terminal_closed" : "terminal_descriptor_failed");
    return fd;
}
static void hangup(Pty *pty) {
    pthread_mutex_lock(&pty->lock);
    if (!pty->closed) {
        pty->closed = 1;
        char byte = 1;
        ssize_t notified = write(pty->cancel[1], &byte, 1);
        (void) notified;
        close(pty->master); pty->master = -1;
        pthread_cond_broadcast(&pty->changed);
    }
    pthread_mutex_unlock(&pty->lock);
}
static void *reap(void *value) {
    Pty *pty = value;
    pthread_mutex_lock(&pty->lock);
    while (!pty->child_published) pthread_cond_wait(&pty->changed, &pty->lock);
    pid_t pid = pty->pid;
    pthread_mutex_unlock(&pty->lock);
    int status = 0;
    pid_t waited = -1;
    if (pid > 0) do { waited = waitpid(pid, &status, 0); } while (waited < 0 && errno == EINTR);
    pthread_mutex_lock(&pty->lock);
    pty->exited = 1;
    pty->code = waited < 0 ? -1 : WIFEXITED(status) ? WEXITSTATUS(status) : WIFSIGNALED(status) ? 128 + WTERMSIG(status) : -1;
    pthread_cond_broadcast(&pty->changed);
    pthread_mutex_unlock(&pty->lock);
    release(pty);
    return NULL;
}
static int dimensions(int rows, int columns, int x, int y) {
    return rows >= 1 && rows <= 1000 && columns >= 1 && columns <= 1000 && x >= 0 && x <= 65535 && y >= 0 && y <= 65535;
}
static int set_size(int fd, int rows, int columns, int x, int y) {
    struct winsize size = { .ws_row = (unsigned short) rows, .ws_col = (unsigned short) columns,
        .ws_xpixel = (unsigned short) x, .ws_ypixel = (unsigned short) y };
    return ioctl(fd, TIOCSWINSZ, &size);
}
static void free_environment(char **values) {
    if (!values) return;
    for (char **p = values; *p; ++p) free(*p);
    free(values);
}
static char **environment_with_term(const char *term) {
    size_t count = 0;
    for (char **p = environ; *p; ++p) ++count;
    char **values = calloc(count + 2, sizeof(char *));
    if (!values) return NULL;
    size_t index = 0;
    for (char **p = environ; *p; ++p) {
        if (strncmp(*p, "TERM=", 5) == 0) continue;
        values[index] = strdup(*p);
        if (!values[index++]) { free_environment(values); return NULL; }
    }
    values[index] = malloc(strlen(term) + 6);
    if (!values[index]) { free_environment(values); return NULL; }
    strcpy(values[index], "TERM="); strcat(values[index], term);
    return values;
}
static void child_failure(int fd, int stage) {
    int result[2] = {stage, errno};
    ssize_t reported = write(fd, result, sizeof(result));
    (void) reported;
    _exit(127);
}

JNIEXPORT jlong JNICALL Java_dev_ticbox_companion_toolboxagent_PtyNative_start(
        JNIEnv *env, jclass type, jstring shell_value, jstring term_value,
        jint rows, jint columns, jint x, jint y) {
    (void) type;
    if (!shell_value || !term_value || !dimensions(rows, columns, x, y)) { fail(env, "terminal_options_invalid"); return 0; }
    const char *shell = (*env)->GetStringUTFChars(env, shell_value, NULL);
    if (!shell) return 0;
    const char *term = (*env)->GetStringUTFChars(env, term_value, NULL);
    if (!term) { (*env)->ReleaseStringUTFChars(env, shell_value, shell); return 0; }
    char **child_env = environment_with_term(term);
    char *argv[] = {(char *) shell, "-i", NULL};
    long maximum_fd = sysconf(_SC_OPEN_MAX);
    if (maximum_fd < 0 || maximum_fd > INT_MAX) maximum_fd = 65536;
    int errors[2] = {-1, -1};
    int master = -1;
    char slave_name[128];
    Pty *pty = NULL;
    const char *problem = "terminal_start_failed";
    jlong answer = 0;
    if (!child_env) goto finish;
    master = open("/dev/ptmx", O_RDWR | O_NOCTTY | O_CLOEXEC | O_NONBLOCK);
    if (master < 0 || grantpt(master) || unlockpt(master) || ptsname_r(master, slave_name, sizeof(slave_name))) goto finish;
    struct termios settings;
    if (tcgetattr(master, &settings)) goto finish;
    settings.c_iflag |= IUTF8;
    settings.c_iflag &= ~(IXON | IXOFF);
    if (tcsetattr(master, TCSANOW, &settings) || set_size(master, rows, columns, x, y) || pipe2(errors, O_CLOEXEC)) goto finish;
    pty = calloc(1, sizeof(Pty));
    if (!pty) goto finish;
    pty->master = master; master = -1;
    if (pipe2(pty->cancel, O_CLOEXEC | O_NONBLOCK)) { close(pty->master); free(pty); pty = NULL; goto finish; }
    pthread_mutex_init(&pty->lock, NULL); pthread_cond_init(&pty->changed, NULL);
    pty->references = 2; /* unpublished owner plus background child reaper */
    pthread_t reaper;
    if (pthread_create(&reaper, NULL, reap, pty)) { pty->references = 1; release(pty); pty = NULL; goto finish; }
    pthread_detach(reaper);
    pid_t pid = fork();
    if (pid == 0) {
        /* Only async-signal-safe operations after fork in the multithreaded VM. */
        close(errors[0]);
        if (setsid() < 0) child_failure(errors[1], 1);
        int slave = open(slave_name, O_RDWR);
        if (slave < 0) child_failure(errors[1], 2);
        if (ioctl(slave, TIOCSCTTY, 0) < 0 || tcsetpgrp(slave, getpid()) < 0) child_failure(errors[1], 3);
        if (dup2(slave, STDIN_FILENO) < 0 || dup2(slave, STDOUT_FILENO) < 0 || dup2(slave, STDERR_FILENO) < 0) child_failure(errors[1], 4);
        /* Reserve FD 3 for exec failure. Everything else inherited from app_process closes. */
        if (errors[1] != 3 && dup2(errors[1], 3) < 0) child_failure(errors[1], 5);
        if (fcntl(3, F_SETFD, FD_CLOEXEC) < 0) child_failure(3, 5);
        for (int fd = 4; fd < maximum_fd; ++fd) close(fd);
        sigset_t empty; sigemptyset(&empty); sigprocmask(SIG_SETMASK, &empty, NULL);
        struct sigaction action; memset(&action, 0, sizeof(action)); action.sa_handler = SIG_DFL;
        sigemptyset(&action.sa_mask);
        for (int signal_number = 1; signal_number < NSIG; ++signal_number) {
            if (signal_number != SIGKILL && signal_number != SIGSTOP) sigaction(signal_number, &action, NULL);
        }
        execve(shell, argv, child_env);
        child_failure(3, 6);
    }
    pthread_mutex_lock(&pty->lock);
    pty->pid = pid; pty->child_published = 1; pthread_cond_broadcast(&pty->changed);
    pthread_mutex_unlock(&pty->lock);
    close(errors[1]); errors[1] = -1;
    if (pid < 0) goto finish;
    struct pollfd ready = {.fd = errors[0], .events = POLLIN};
    int polled;
    do { polled = poll(&ready, 1, 10000); } while (polled < 0 && errno == EINTR);
    if (polled <= 0) { problem = "terminal_start_timeout"; goto finish; }
    int detail[2]; ssize_t bytes;
    do { bytes = read(errors[0], detail, sizeof(detail)); } while (bytes < 0 && errno == EINTR);
    if (bytes != 0) { problem = "terminal_exec_failed"; goto finish; }
    pthread_mutex_lock(&registry_lock);
    if (next_id > 0 && next_id < INT64_MAX) {
        pty->id = next_id++; pty->next = registry; registry = pty; answer = pty->id;
    }
    pthread_mutex_unlock(&registry_lock);
finish:
    if (master >= 0) close(master);
    if (errors[0] >= 0) close(errors[0]);
    if (errors[1] >= 0) close(errors[1]);
    if (pty && answer == 0) { hangup(pty); release(pty); }
    free_environment(child_env);
    (*env)->ReleaseStringUTFChars(env, term_value, term);
    (*env)->ReleaseStringUTFChars(env, shell_value, shell);
    if (!answer && !(*env)->ExceptionCheck(env)) fail(env, problem);
    return answer;
}

JNIEXPORT jint JNICALL Java_dev_ticbox_companion_toolboxagent_PtyNative_read(
        JNIEnv *env, jclass type, jlong id, jbyteArray array, jint timeout) {
    (void) type;
    jsize length = array ? (*env)->GetArrayLength(env, array) : 0;
    if (length < 1 || length > 65536 || timeout < 1 || timeout > 60000) { fail(env, "terminal_read_invalid"); return -1; }
    Pty *pty = acquire(env, id); if (!pty) return -1;
    int fd = io_fd(env, pty); jint result = -1;
    if (fd >= 0) {
        struct pollfd waiting[2] = {{.fd = fd, .events = POLLIN}, {.fd = pty->cancel[0], .events = POLLIN}};
        int polled;
        do { polled = poll(waiting, 2, timeout); } while (polled < 0 && errno == EINTR);
        if (polled == 0) result = 0;
        else if (polled < 0) fail(env, "terminal_read_failed");
        else {
            unsigned char buffer[65536];
            pthread_mutex_lock(&pty->lock);
            int closed = pty->closed;
            ssize_t count = closed ? -1 : read(fd, buffer, (size_t) length);
            int error = errno;
            pthread_mutex_unlock(&pty->lock);
            if (closed) fail(env, "terminal_closed");
            else if (count > 0) { (*env)->SetByteArrayRegion(env, array, 0, (jsize) count, (jbyte *) buffer); result = (jint) count; }
            else if (count == 0 || error == EIO) result = -1;
            else if (error == EAGAIN || error == EWOULDBLOCK || error == EINTR) result = 0;
            else fail(env, "terminal_read_failed");
        }
        close(fd);
    }
    release(pty); return result;
}
JNIEXPORT void JNICALL Java_dev_ticbox_companion_toolboxagent_PtyNative_write(
        JNIEnv *env, jclass type, jlong id, jbyteArray array) {
    (void) type;
    jsize length = array ? (*env)->GetArrayLength(env, array) : -1;
    if (length < 0 || length > 65536) { fail(env, "terminal_write_invalid"); return; }
    unsigned char buffer[65536];
    if (length) (*env)->GetByteArrayRegion(env, array, 0, length, (jbyte *) buffer);
    if ((*env)->ExceptionCheck(env)) return;
    Pty *pty = acquire(env, id); if (!pty) return;
    int fd = io_fd(env, pty);
    if (fd >= 0) {
        size_t sent = 0;
        while (sent < (size_t) length) {
            struct pollfd waiting[2] = {{.fd = fd, .events = POLLOUT}, {.fd = pty->cancel[0], .events = POLLIN}};
            int polled;
            do { polled = poll(waiting, 2, -1); } while (polled < 0 && errno == EINTR);
            if (polled < 0) { fail(env, "terminal_write_failed"); break; }
            pthread_mutex_lock(&pty->lock);
            int closed = pty->closed;
            ssize_t count = closed ? -1 : write(fd, buffer + sent, (size_t) length - sent);
            int error = errno;
            pthread_mutex_unlock(&pty->lock);
            if (closed) { fail(env, "terminal_closed"); break; }
            if (count > 0) sent += (size_t) count;
            else if (count == 0 || (error != EAGAIN && error != EWOULDBLOCK && error != EINTR)) { fail(env, "terminal_write_failed"); break; }
        }
        close(fd);
    }
    release(pty);
}
JNIEXPORT void JNICALL Java_dev_ticbox_companion_toolboxagent_PtyNative_resize(
        JNIEnv *env, jclass type, jlong id, jint rows, jint columns, jint x, jint y) {
    (void) type;
    if (!dimensions(rows, columns, x, y)) { fail(env, "terminal_size_invalid"); return; }
    Pty *pty = acquire(env, id); if (!pty) return;
    pthread_mutex_lock(&pty->lock);
    int closed = pty->closed;
    int result = closed ? -1 : set_size(pty->master, rows, columns, x, y);
    pthread_mutex_unlock(&pty->lock);
    if (result < 0) fail(env, closed ? "terminal_closed" : "terminal_resize_failed");
    release(pty);
}
JNIEXPORT jint JNICALL Java_dev_ticbox_companion_toolboxagent_PtyNative_awaitExit(JNIEnv *env, jclass type, jlong id) {
    (void) type;
    Pty *pty = acquire(env, id); if (!pty) return -1;
    pthread_mutex_lock(&pty->lock);
    while (!pty->exited && !pty->closed) pthread_cond_wait(&pty->changed, &pty->lock);
    int exited = pty->exited, code = pty->code;
    pthread_mutex_unlock(&pty->lock);
    if (!exited) fail(env, "terminal_closed");
    else if (code < 0) fail(env, "terminal_exit_unavailable");
    release(pty); return code;
}
JNIEXPORT void JNICALL Java_dev_ticbox_companion_toolboxagent_PtyNative_close(JNIEnv *env, jclass type, jlong id) {
    (void) env; (void) type;
    pthread_mutex_lock(&registry_lock);
    Pty **cursor = &registry;
    while (*cursor && (*cursor)->id != id) cursor = &(*cursor)->next;
    Pty *pty = *cursor;
    if (pty) *cursor = pty->next;
    pthread_mutex_unlock(&registry_lock);
    if (pty) { hangup(pty); release(pty); }
}
