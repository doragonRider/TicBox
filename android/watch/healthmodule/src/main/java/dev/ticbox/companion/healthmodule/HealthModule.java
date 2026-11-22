package dev.ticbox.companion.healthmodule;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

/** Entry invoked by the module loader in the original application's main process. */
public final class HealthModule {
    private static final String TAG = "TicHealthModule";
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static volatile HealthModuleServer server;
    private HealthModule() { }

    public static void start(Context context) {
        if (!(context instanceof Application) || context.getApplicationContext() != context ||
                !HealthProfile.APPLICATION.equals(context.getClass().getName()) ||
                !HealthProfile.PACKAGE.equals(context.getPackageName()) ||
                !HealthProfile.PACKAGE.equals(Application.getProcessName()) ||
                context.getApplicationInfo().uid != Process.myUid()) return;
        if (!STARTED.compareAndSet(false, true)) return;
        Application application = (Application) context;
        Handler main = new Handler(Looper.getMainLooper());
        // ActivityThread publishes the Application before onCreate completes.
        // The initial main post runs after handleBindApplication has returned.
        main.post(() -> {
            Thread verification = new Thread(() -> verifyAndStart(application, main), "TicHealthModuleVerify");
            verification.setDaemon(true); verification.start();
        });
    }

    @SuppressWarnings("deprecation")
    private static void verifyAndStart(Application application, Handler main) {
        try {
            PackageInfo info = application.getPackageManager().getPackageInfo(HealthProfile.PACKAGE, 0);
            ApplicationInfo source = info.applicationInfo;
            ApplicationInfo live = application.getApplicationInfo();
            if (source == null || source.uid != Process.myUid() || !HealthProfile.PACKAGE.equals(info.packageName) ||
                    source.sourceDir == null || !source.sourceDir.equals(live.sourceDir)) throw new IllegalStateException();
            String hash = sha256(new File(source.sourceDir));
            HealthProfile profile = HealthProfile.match(info.packageName, info.getLongVersionCode(), info.versionName, hash);
            if (profile == null) { Log.w(TAG, "health_module_profile_not_supported"); return; }
            main.post(() -> {
                try {
                    OemHealthStore store = new OemHealthStore(application, profile, main);
                    HealthController controller = new HealthController(profile, store);
                    server = new HealthModuleServer(new HealthMainDispatcher(main, controller, profile), profile, new HealthProviderAccess(application, profile));
                    server.start();
                    Log.i(TAG, "health_module_ready protocol=1 moduleVersionCode=1");
                } catch (Exception unavailable) { Log.w(TAG, "health_module_socket_unavailable"); }
                catch (LinkageError unavailable) { Log.w(TAG, "health_module_oem_contract_changed"); }
            });
        } catch (Exception unsupported) { Log.w(TAG, "health_module_source_verification_failed"); }
        catch (LinkageError unsupported) { Log.w(TAG, "health_module_source_verification_failed"); }
    }

    static String sha256(File file) throws Exception {
        if (!file.isFile() || file.length() <= 0 || file.length() > 256L * 1024L * 1024L) throw new IllegalArgumentException();
        long length = file.length(), modified = file.lastModified();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        if (length != file.length() || modified != file.lastModified()) throw new IllegalStateException();
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) { hex.append(Character.forDigit((value & 255) >>> 4, 16)); hex.append(Character.forDigit(value & 15, 16)); }
        return hex.toString();
    }
}
