#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include "zygisk.hpp"
#include "bootstrap_policy.h"

namespace {
void report(const char *stage) { __android_log_write(ANDROID_LOG_WARN, "TicBoxHealthModule", stage); }
bool failed(JNIEnv *env) {
    if (!env->ExceptionCheck()) return false;
    // Exception text can contain vendor/private values; retain only a fixed bootstrap stage.
    env->ExceptionClear();
    return true;
}
struct Bootstrap { JavaVM *vm; unsigned char *dex; size_t size; const char *target; const char *entry; };

void *launch(void *opaque) {
    auto *work = static_cast<Bootstrap *>(opaque);
    JNIEnv *env = nullptr;
    if (work->vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        report("attach_failed"); free(work->dex); free(work); return nullptr;
    }
    bool success = false;
    do {
        if (env->PushLocalFrame(32) < 0) { failed(env); break; }
        jclass thread = env->FindClass("android/app/ActivityThread");
        if (failed(env) || !thread) break;
        jmethodID current = env->GetStaticMethodID(thread, "currentApplication", "()Landroid/app/Application;");
        if (failed(env) || !current) break;
        jobject application = nullptr;
        // No ActivityThread/ContextImpl fabrication and no blocking the app's main thread.
        for (int attempt = 0; attempt < 600 && !application; ++attempt) {
            application = env->CallStaticObjectMethod(thread, current);
            if (failed(env)) { application = nullptr; break; }
            if (!application) { timespec wait{0, 100000000}; nanosleep(&wait, nullptr); }
        }
        if (!application) { report("application_unavailable"); break; }
        jclass context = env->FindClass("android/content/Context");
        if (failed(env) || !context) break;
        jmethodID package_name = env->GetMethodID(context, "getPackageName", "()Ljava/lang/String;");
        if (failed(env) || !package_name) break;
        jmethodID class_loader = env->GetMethodID(context, "getClassLoader", "()Ljava/lang/ClassLoader;");
        if (failed(env) || !class_loader) break;
        auto name = static_cast<jstring>(env->CallObjectMethod(application, package_name));
        if (failed(env) || !name) break;
        const char *text = env->GetStringUTFChars(name, nullptr);
        if (failed(env) || !text) break;
        bool correct = !strcmp(text, work->target);
        env->ReleaseStringUTFChars(name, text);
        if (!correct) { report("application_identity_mismatch"); break; }
        jobject parent = env->CallObjectMethod(application, class_loader);
        if (failed(env) || !parent) break;
        jbyteArray bytes = env->NewByteArray(static_cast<jsize>(work->size));
        if (failed(env) || !bytes) break;
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(work->size), reinterpret_cast<const jbyte *>(work->dex));
        if (failed(env)) break;
        jclass buffers = env->FindClass("java/nio/ByteBuffer");
        if (failed(env) || !buffers) break;
        jmethodID wrap = env->GetStaticMethodID(buffers, "wrap", "([B)Ljava/nio/ByteBuffer;");
        if (failed(env) || !wrap) break;
        jobject buffer = env->CallStaticObjectMethod(buffers, wrap, bytes);
        if (failed(env) || !buffer) break;
        jclass loaders = env->FindClass("dalvik/system/InMemoryDexClassLoader");
        if (failed(env) || !loaders) break;
        jmethodID constructor = env->GetMethodID(loaders, "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
        if (failed(env) || !constructor) break;
        jobject loader = env->NewObject(loaders, constructor, buffer, parent);
        if (failed(env) || !loader) break;
        jmethodID load = env->GetMethodID(loaders, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        if (failed(env) || !load) break;
        jstring entry = env->NewStringUTF(work->entry);
        if (failed(env) || !entry) break;
        auto controller = static_cast<jclass>(env->CallObjectMethod(loader, load, entry));
        if (failed(env) || !controller) break;
        jmethodID start = env->GetStaticMethodID(controller, "start", "(Landroid/content/Context;)V");
        if (failed(env) || !start) break;
        env->CallStaticVoidMethod(controller, start, application);
        if (failed(env)) break;
        // Keep the managed DEX buffer and its loader alive for the lifetime of the controller.
        // Never retain a raw native buffer behind a DirectByteBuffer after returning this thread.
        env->NewGlobalRef(buffer);
        if (failed(env)) break;
        env->NewGlobalRef(loader);
        if (failed(env)) break;
        success = true;
    } while (false);
    if (!success) report("bootstrap_unavailable");
    // Detach also releases the local frame if bootstrap stopped before normal completion.
    failed(env); work->vm->DetachCurrentThread();
    free(work->dex); free(work);
    return nullptr;
}

class HealthModule final : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        api_ = api; env_ = env;
        if (env->GetJavaVM(&vm_) != JNI_OK) vm_ = nullptr;
    }
    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        if (!vm_) { api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY); return; }
        const char *process = args->nice_name ? env_->GetStringUTFChars(args->nice_name, nullptr) : nullptr;
        if (failed(env_) || !process) { api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY); return; }
        const char *directory = args->app_data_dir ? env_->GetStringUTFChars(args->app_data_dir, nullptr) : nullptr;
        if (failed(env_) || !directory) {
            env_->ReleaseStringUTFChars(args->nice_name, process);
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY); return;
        }
        bool matched = healthmodule::target_allowed(process, directory, args->uid,
            api_->getFlags() & zygisk::PROCESS_ON_DENYLIST,
            args->is_child_zygote && *args->is_child_zygote);
        alert_ = healthmodule::alert_target_allowed(process, directory, args->uid,
            api_->getFlags() & zygisk::PROCESS_ON_DENYLIST,
            args->is_child_zygote && *args->is_child_zygote);
        if (!matched && !alert_ && healthmodule::is_target_name(process)) report("target_policy_rejected");
        if (process) env_->ReleaseStringUTFChars(args->nice_name, process);
        if (directory) env_->ReleaseStringUTFChars(args->app_data_dir, directory);
        if (failed(env_) || (!matched && !alert_)) { api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY); return; }
        int directory_fd = api_->getModuleDir();
        if (directory_fd < 0) { report("module_directory_unavailable"); return; }
        if (alert_) {
            // No SystemUI injection for existing health-only installations. This root-owned
            // opt-in file must be explicitly created before a later process start/reboot.
            int opt_in = openat(directory_fd, "notification-alerts.enabled", O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
            struct stat flag{};
            bool enabled = opt_in >= 0 && !fstat(opt_in, &flag) && healthmodule::alert_opt_in_allowed(flag);
            if (opt_in >= 0) close(opt_in);
            if (!enabled) { report("alert_opt_in_absent"); close(directory_fd); api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY); return; }
        }
        int fd = openat(directory_fd, "module.dex", O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
        close(directory_fd);
        if (fd < 0) { report("dex_unavailable"); return; }
        struct stat info{};
        if (fstat(fd, &info) || !S_ISREG(info.st_mode) || info.st_size < 112 ||
            static_cast<size_t>(info.st_size) > healthmodule::kMaxDexBytes) { close(fd); report("dex_invalid"); return; }
        size_ = static_cast<size_t>(info.st_size);
        dex_ = static_cast<unsigned char *>(malloc(size_));
        size_t count = 0;
        while (dex_ && count < size_) {
            ssize_t got = read(fd, dex_ + count, size_ - count);
            if (got < 0 && errno == EINTR) continue;
            if (got <= 0) break;
            count += static_cast<size_t>(got);
        }
        close(fd);
        if (count != size_ || !healthmodule::dex_header_valid(dex_, size_)) {
            free(dex_); dex_ = nullptr; report("dex_invalid");
        }
    }
    void postAppSpecialize(const zygisk::AppSpecializeArgs *) override {
        if (!dex_) return;
        auto *work = static_cast<Bootstrap *>(malloc(sizeof(Bootstrap)));
        if (!work) { free(dex_); dex_ = nullptr; return; }
        *work = Bootstrap{vm_, dex_, size_, alert_ ? healthmodule::kAlertTarget : healthmodule::kTarget,
            alert_ ? "dev.ticbox.companion.healthmodule.NotificationAlertsModule" : "dev.ticbox.companion.healthmodule.HealthModule"}; dex_ = nullptr;
        pthread_t worker;
        if (pthread_create(&worker, nullptr, launch, work)) { free(work->dex); free(work); report("worker_unavailable"); }
        else pthread_detach(worker);
    }
    void preServerSpecialize(zygisk::ServerSpecializeArgs *) override { api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY); }
private:
    zygisk::Api *api_ = nullptr; JNIEnv *env_ = nullptr; JavaVM *vm_ = nullptr;
    unsigned char *dex_ = nullptr; size_t size_ = 0;
    bool alert_ = false;
};
} // namespace
REGISTER_ZYGISK_MODULE(HealthModule)
