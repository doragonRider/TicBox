package dev.ticbox.companion.healthmodule;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/** Separate, opt-in SystemUI entry. The health-process entry never installs alert wrappers. */
public final class NotificationAlertsModule {
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static volatile AlertModuleServer server;
    private NotificationAlertsModule() { }

    public static void start(Context context) {
        if (!(context instanceof Application) || context.getApplicationContext() != context ||
                !AlertProfile.APPLICATION.equals(context.getClass().getName()) ||
                !AlertProfile.PACKAGE.equals(context.getPackageName()) ||
                !AlertProfile.PACKAGE.equals(Application.getProcessName()) ||
                context.getApplicationInfo().uid != Process.myUid() ||
                Build.VERSION.SDK_INT < 33 || Build.VERSION.SDK_INT > 34) return;
        if (!STARTED.compareAndSet(false, true)) return;
        Application application = (Application) context;
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            Thread thread = new Thread(() -> verify(application, main), "TicNotifyVerify");
            thread.setDaemon(true); thread.start();
        });
    }

    @SuppressWarnings("deprecation")
    private static void verify(Application application, Handler main) {
        try {
            PackageInfo info = application.getPackageManager().getPackageInfo(AlertProfile.PACKAGE, 0);
            ApplicationInfo source = info.applicationInfo;
            if (source == null || source.uid != Process.myUid() || source.sourceDir == null ||
                    !source.sourceDir.equals(application.getApplicationInfo().sourceDir) ||
                    info.splitNames != null && info.splitNames.length != 0 ||
                    !AlertProfile.matches(info.packageName, info.getLongVersionCode(), info.versionName,
                            HealthModule.sha256(new File(source.sourceDir)))) {
                Log.w("TicNotifyModule", "notification_alert_profile_not_supported"); return;
            }
            AlertPolicy policy = new AlertPolicy(application);
            OemAlertAdapter adapter = new OemAlertAdapter(application.getClassLoader(), policy);
            main.post(() -> {
                try { adapter.install(application); }
                catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { policy.failed(); }
                Thread thread = new Thread(() -> {
                    try { server = new AlertModuleServer(policy); server.start(); }
                    catch (Exception unavailable) { Log.w("TicNotifyModule", "notification_alert_socket_unavailable"); }
                }, "TicNotifyServerStart");
                thread.setDaemon(true); thread.start();
            });
        } catch (Exception | LinkageError unsupported) { Log.w("TicNotifyModule", "notification_alert_source_verification_failed"); }
    }
}
