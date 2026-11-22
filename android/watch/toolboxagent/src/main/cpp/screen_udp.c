#define _GNU_SOURCE
#include "screen_udp.h"
#include "screen_fec.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <netinet/in.h>
#include <linux/udp.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <sys/eventfd.h>
#include <sys/socket.h>
#include <time.h>
#include <unistd.h>
#ifndef TW_SCREEN_NO_JNI
#include <jni.h>
#endif
#ifdef TW_SCREEN_TEST
extern int tw_test_sendmmsg(int, struct mmsghdr *, unsigned int, int);
extern ssize_t tw_test_sendmsg(int, const struct msghdr *, int);
#define SEND_BATCH tw_test_sendmmsg
#define SEND_GSO tw_test_sendmsg
#else
#define SEND_BATCH sendmmsg
#define SEND_GSO sendmsg
#endif
#ifndef UDP_SEGMENT
#define UDP_SEGMENT 103
#endif

/* IDs, never caller-controlled pointers. Leases prevent FD reuse during a racing close. */
typedef struct Sender {
    int64_t id;
    int fd, wake, family, refs;
    atomic_int closed;
    atomic_int gso_disabled;
    struct Sender *next;
} Sender;
static pthread_mutex_t guard = PTHREAD_MUTEX_INITIALIZER;
static Sender *senders;
static int64_t next_id = 1;
static void destroy(Sender *s) { close(s->fd); close(s->wake); free(s); }
static Sender *acquire(int64_t id) {
    pthread_mutex_lock(&guard);
    Sender *s = senders;
    while (s && s->id != id) s = s->next;
    if (s) ++s->refs;
    pthread_mutex_unlock(&guard);
    if (!s) errno = ECANCELED;
    return s;
}
static void release(Sender *s) {
    pthread_mutex_lock(&guard);
    int dispose = --s->refs == 0 && atomic_load(&s->closed);
    pthread_mutex_unlock(&guard);
    if (dispose) destroy(s);
}
int64_t tw_screen_open(int fd) {
    int type = 0; socklen_t size = sizeof(type);
    struct sockaddr_storage local; socklen_t local_size = sizeof(local);
    if (getsockopt(fd, SOL_SOCKET, SO_TYPE, &type, &size) < 0 || getsockname(fd, (struct sockaddr *)&local, &local_size) < 0) return 0;
    if (type != SOCK_DGRAM || (local.ss_family != AF_INET && local.ss_family != AF_INET6)) { errno = EINVAL; return 0; }
    Sender *s = calloc(1, sizeof(*s)); if (!s) return 0;
    s->fd = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    if (s->fd < 0) { free(s); return 0; }
    s->wake = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    if (s->wake < 0) { int saved = errno; close(s->fd); free(s); errno = saved; return 0; }
    s->family = local.ss_family; atomic_init(&s->closed, 0); atomic_init(&s->gso_disabled, 0);
    pthread_mutex_lock(&guard);
    if (next_id == INT64_MAX) { pthread_mutex_unlock(&guard); destroy(s); errno = EOVERFLOW; return 0; }
    s->id = next_id++; s->next = senders; senders = s;
    pthread_mutex_unlock(&guard);
    return s->id;
}
void tw_screen_close(int64_t id) {
    pthread_mutex_lock(&guard);
    Sender **entry = &senders;
    while (*entry && (*entry)->id != id) entry = &(*entry)->next;
    Sender *s = *entry;
    if (s) {
        *entry = s->next; atomic_store(&s->closed, 1);
        uint64_t one = 1; ssize_t written;
        do { written = write(s->wake, &one, sizeof(one)); } while (written < 0 && errno == EINTR);
    }
    int dispose = s && s->refs == 0;
    pthread_mutex_unlock(&guard);
    if (dispose) destroy(s);
}
static int64_t millis(void) {
    struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t);
    return (int64_t)t.tv_sec * 1000 + t.tv_nsec / 1000000;
}
int tw_screen_send(int64_t id, const uint8_t *bytes, const int *lengths, int count,
        const uint8_t *address, int address_length, int scope, int port) {
    return tw_screen_send_measured(id, bytes, lengths, count, address, address_length, scope, port, NULL);
}
int tw_screen_send_measured(int64_t id, const uint8_t *bytes, const int *lengths, int count,
        const uint8_t *address, int address_length, int scope, int port, TwScreenSendStats *stats) {
    if (stats) *stats = (TwScreenSendStats){0};
    if (count < 1 || count > TW_SCREEN_MAX_BATCH || (address_length != 4 && address_length != 16) || scope < 0 || port < 1 || port > 65535) { errno = EINVAL; return -1; }
    for (int i = 0; i < count; ++i) if (lengths[i] < 48 || lengths[i] > 1200) { errno = EINVAL; return -1; }
    Sender *s = acquire(id); if (!s) return -1;
    struct sockaddr_storage destination = {0}; socklen_t addr_size;
    if (s->family == AF_INET6) {
        struct sockaddr_in6 *v6 = (struct sockaddr_in6 *)&destination;
        v6->sin6_family = AF_INET6; v6->sin6_port = htons((uint16_t)port); v6->sin6_scope_id = (uint32_t)scope;
        if (address_length == 16) memcpy(&v6->sin6_addr, address, 16);
        else { v6->sin6_addr.s6_addr[10] = 255; v6->sin6_addr.s6_addr[11] = 255; memcpy(v6->sin6_addr.s6_addr + 12, address, 4); }
        addr_size = sizeof(*v6);
    } else {
        if (address_length != 4) { release(s); errno = EAFNOSUPPORT; return -1; }
        struct sockaddr_in *v4 = (struct sockaddr_in *)&destination;
        v4->sin_family = AF_INET; v4->sin_port = htons((uint16_t)port); memcpy(&v4->sin_addr, address, 4); addr_size = sizeof(*v4);
    }
    struct iovec vectors[TW_SCREEN_MAX_BATCH]; struct mmsghdr messages[TW_SCREEN_MAX_BATCH] = {0};
    for (int i = 0; i < count; ++i) {
        vectors[i].iov_base = (void *)(bytes + i * 1200); vectors[i].iov_len = (size_t)lengths[i];
        messages[i].msg_hdr.msg_name = &destination; messages[i].msg_hdr.msg_namelen = addr_size;
        messages[i].msg_hdr.msg_iov = &vectors[i]; messages[i].msg_hdr.msg_iovlen = 1;
    }
    /* One iovec per original datagram avoids repacking short, equal-sized shards. UDP_SEGMENT
     * partitions the concatenation, so all but the last datagram MUST have the same length.
     * At most one FEC block (20 datagrams, 24 KB) goes in one call; the wire format is unchanged. */
    int use_gso = count > 1 && !atomic_load(&s->gso_disabled);
    size_t total = 0;
    for (int i = 0; i < count; ++i) {
        total += (size_t)lengths[i];
        if (i < count - 1 && lengths[i] != lengths[0]) use_gso = 0;
    }
    if (lengths[count - 1] > lengths[0]) use_gso = 0;
    union { struct cmsghdr align; uint8_t bytes[CMSG_SPACE(sizeof(uint16_t))]; } ancillary = {0};
    struct msghdr grouped = {0};
    grouped.msg_name = &destination; grouped.msg_namelen = addr_size;
    grouped.msg_iov = vectors; grouped.msg_iovlen = (size_t)count;
    grouped.msg_control = ancillary.bytes; grouped.msg_controllen = sizeof(ancillary.bytes);
    struct cmsghdr *segment = CMSG_FIRSTHDR(&grouped);
    segment->cmsg_level = IPPROTO_UDP; segment->cmsg_type = UDP_SEGMENT; segment->cmsg_len = CMSG_LEN(sizeof(uint16_t));
    uint16_t segment_size = (uint16_t)lengths[0]; memcpy(CMSG_DATA(segment), &segment_size, sizeof(segment_size));
    // Nothing paces video any more, so a full socket buffer is the normal brake: allow a Wi-Fi stall.
    int sent = 0, calls = 0, error = 0; int64_t deadline = millis() + 1000;
    while (sent < count) {
        if (atomic_load(&s->closed)) { error = ECANCELED; break; }
        if (millis() >= deadline) { error = ETIMEDOUT; break; }
        if (use_gso) {
            ssize_t n = SEND_GSO(s->fd, &grouped, MSG_DONTWAIT | MSG_NOSIGNAL); ++calls; if (stats) ++stats->gso_calls;
            if (n == (ssize_t)total) { error = 0; if (stats) stats->gso_datagrams += count; sent = count; break; }
            // A UDP error sent no datagram. Only explicit unsupported/offload failures switch
            // to sendmmsg, once per socket. EIO also covers Linux's missing checksum offload.
            // Never replay a positive short result, or hide unreachable/permission/MTU errors.
            if (n >= 0) { error = EIO; break; }
            error = errno;
            if (error == EINVAL || error == ENOPROTOOPT || error == EOPNOTSUPP || error == ENOSYS || error == EIO) {
                atomic_store(&s->gso_disabled, 1); use_gso = 0; error = 0; continue;
            }
            if (error == EINTR) continue;
            if (error != EAGAIN && error != EWOULDBLOCK && error != ENOBUFS) break;
        } else {
            int n = SEND_BATCH(s->fd, messages + sent, (unsigned)(count - sent), MSG_DONTWAIT | MSG_NOSIGNAL); ++calls;
            if (n > 0) { error = 0; sent += n; continue; } // Retry ONLY the unsent suffix.
            if (n < 0 && errno == EINTR) continue;
            if (n == 0) { error = EIO; break; }
            error = errno;
            if (error != EAGAIN && error != EWOULDBLOCK && error != ENOBUFS) break;
        }
        // ENOBUFS can report POLLOUT while still full; use only the wake fd for a bounded pause.
        struct pollfd fds[2] = {{s->wake, POLLIN, 0}, {s->fd, error == ENOBUFS ? 0 : POLLOUT, 0}};
        if (poll(fds, error == ENOBUFS ? 1 : 2, 10) < 0 && errno != EINTR) { error = errno; break; }
        error = 0;
    }
    if (stats) stats->gso_disabled = atomic_load(&s->gso_disabled);
    release(s);
    if (error) { errno = error; return -1; }
    return calls;
}
#ifndef TW_SCREEN_NO_JNI
static int64_t nanos(clockid_t clock) {
    struct timespec t; if (clock_gettime(clock, &t) != 0) return -1;
    return (int64_t)t.tv_sec * 1000000000LL + t.tv_nsec;
}
static int64_t elapsed(int64_t end, int64_t start) { return start < 0 || end < start ? -1 : end - start; }
static void exception(JNIEnv *env, const char *type, const char *text) {
    jclass cls = (*env)->FindClass(env, type); if (cls) (*env)->ThrowNew(env, cls, text);
}
JNIEXPORT jlong JNICALL Java_dev_ticbox_companion_toolboxagent_ScreenNative_open(JNIEnv *env, jclass cls, jint fd) {
    (void)cls; int64_t id = tw_screen_open(fd);
    if (!id) exception(env, "java/io/IOException", "screen_udp_native_open_failed");
    return (jlong)id;
}
JNIEXPORT void JNICALL Java_dev_ticbox_companion_toolboxagent_ScreenNative_close(JNIEnv *env, jclass cls, jlong id) { (void)env; (void)cls; tw_screen_close(id); }
JNIEXPORT jint JNICALL Java_dev_ticbox_companion_toolboxagent_ScreenNative_sendBatch(JNIEnv *env, jclass cls, jlong id,
    jbyteArray bytes, jintArray lengths, jint count, jbyteArray address, jint scope, jint port, jlongArray statistics) {
    (void)cls;
    int addr_length = address ? (*env)->GetArrayLength(env, address) : 0;
    if (!bytes || !lengths || (statistics && (*env)->GetArrayLength(env, statistics) < 7) || count < 1 || count > TW_SCREEN_MAX_BATCH || (*env)->GetArrayLength(env, bytes) < count * 1200 ||
        (*env)->GetArrayLength(env, lengths) < count || (addr_length != 4 && addr_length != 16)) {
        exception(env, "java/lang/IllegalArgumentException", "screen_udp_native_batch_invalid"); return 0;
    }
    // NULL is the normal playback path: no diagnostic clock reads or statistics copy-out.
    int64_t copy_start = statistics ? nanos(CLOCK_MONOTONIC) : -1, copy_cpu = statistics ? nanos(CLOCK_THREAD_CPUTIME_ID) : -1;
    uint8_t packets[TW_SCREEN_MAX_BATCH * 1200], addr[16]; jint sizes[TW_SCREEN_MAX_BATCH];
    (*env)->GetByteArrayRegion(env, bytes, 0, count * 1200, (jbyte *)packets);
    (*env)->GetByteArrayRegion(env, address, 0, addr_length, (jbyte *)addr);
    (*env)->GetIntArrayRegion(env, lengths, 0, count, sizes);
    if ((*env)->ExceptionCheck(env)) return 0;
    int64_t copy_cpu_end = statistics ? nanos(CLOCK_THREAD_CPUTIME_ID) : -1, native_start = statistics ? nanos(CLOCK_MONOTONIC) : -1;
    int64_t native_cpu = statistics ? nanos(CLOCK_THREAD_CPUTIME_ID) : -1;
    TwScreenSendStats stats;
    int calls = tw_screen_send_measured(id, packets, sizes, count, addr, addr_length, scope, port, statistics ? &stats : NULL);
    int saved_errno = errno;
    if (statistics) {
        int64_t native_cpu_end = nanos(CLOCK_THREAD_CPUTIME_ID), native_end = nanos(CLOCK_MONOTONIC);
        jlong values[7] = {elapsed(native_start, copy_start), elapsed(copy_cpu_end, copy_cpu),
            elapsed(native_end, native_start), elapsed(native_cpu_end, native_cpu), stats.gso_calls, stats.gso_datagrams, stats.gso_disabled};
        if (calls >= 0) (*env)->SetLongArrayRegion(env, statistics, 0, 7, values);
    }
    errno = saved_errno;
    if (calls < 0) exception(env, "java/io/IOException", errno == ECANCELED ? "screen_udp_closed" :
        errno == ETIMEDOUT ? "screen_udp_send_timeout" : "screen_udp_native_send_failed");
    return calls;
}
JNIEXPORT jlong JNICALL Java_dev_ticbox_companion_toolboxagent_ScreenNative_threadCpuNanos(JNIEnv *env, jclass cls) {
    (void)env; (void)cls; struct timespec time;
    if (clock_gettime(CLOCK_THREAD_CPUTIME_ID, &time) != 0) return -1;
    return (jlong)time.tv_sec * 1000000000LL + time.tv_nsec;
}
JNIEXPORT jint JNICALL Java_dev_ticbox_companion_toolboxagent_ScreenNative_fecMode(JNIEnv *env, jclass cls) { (void)env; (void)cls; return tw_fec_mode(); }
JNIEXPORT void JNICALL Java_dev_ticbox_companion_toolboxagent_ScreenNative_encodeParity(JNIEnv *env, jclass cls,
    jbyteArray data, jint count, jint length, jbyteArray parity, jboolean simd) {
    (void)cls;
    if (!data || !parity || count < 1 || count > 16 || length < 0 || length > 1152 ||
        (*env)->GetArrayLength(env, data) < count * length || (*env)->GetArrayLength(env, parity) < ((count + 3) / 4) * length) {
        exception(env, "java/lang/IllegalArgumentException", "screen_fec_native_block_invalid"); return;
    }
    uint8_t input[16 * 1152], output[4 * 1152];
    (*env)->GetByteArrayRegion(env, data, 0, count * length, (jbyte *)input);
    if ((*env)->ExceptionCheck(env)) return;
    tw_fec_encode(input, count, length, output, simd);
    (*env)->SetByteArrayRegion(env, parity, 0, ((count + 3) / 4) * length, (jbyte *)output);
}
#endif
