package dev.ticbox.companion.healthmodule;

import android.net.Credentials;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import org.json.JSONObject;

/** Fixed root CLI usable from the existing root Local Bridge command line or adb shell su.
 * Usage: CLASSPATH=<module.dex> app_process / <this class> status|OFF|OBSERVE|PROBE|ALL. */
public final class NotificationAlertsControl {
    private NotificationAlertsControl() { }
    public static void main(String[] arguments) {
        String stage = "request";
        try {
            if (Process.myUid() != 0) throw new IllegalStateException("root_required");
            if (arguments.length != 1) throw new IllegalArgumentException("usage_status_OFF_OBSERVE_PROBE_ALL");
            JSONObject request = new JSONObject().put("version", 1);
            if (arguments[0].equals("status")) request.put("op", "status");
            else request.put("op", "mode").put("mode", AlertPolicy.Mode.valueOf(arguments[0]).name());
            stage = "uid";
            int expectedUid;
            try (BufferedReader packages = new BufferedReader(new FileReader("/data/system/packages.list"))) {
                expectedUid = packageUid(packages, AlertProfile.PACKAGE);
            }
            if (expectedUid < 10000 || expectedUid >= 99000) throw new IllegalStateException("target_uid_invalid");
            stage = "connect";
            try (LocalSocket connection = new LocalSocket()) {
                connection.connect(new LocalSocketAddress(AlertModuleServer.SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
                connection.setSoTimeout(15_000);
                stage = "identity";
                Credentials peer = connection.getPeerCredentials();
                if (peer == null || peer.getUid() != expectedUid) throw new IllegalStateException("target_uid_mismatch");
                HealthModuleServer.writeFrame(connection.getOutputStream(), request);
                JSONObject reply = HealthModuleServer.readFrame(connection.getInputStream());
                if (reply.optInt("version") != 1 || reply.optInt("moduleVersionCode") != 1 ||
                        !AlertProfile.matches(reply.optString("package"), reply.optLong("versionCode"), reply.optString("versionName"), reply.optString("sha256")))
                    throw new IllegalStateException("target_identity_mismatch");
                System.out.println(reply);
                if (reply.has("error")) System.exit(1);
            }
        } catch (Exception | LinkageError failure) {
            // No exception text, private paths or content from the host process.
            System.err.println("notification_alert_control_unavailable stage=" + stage); System.exit(1);
        }
    }

    /**
     * From the system's own list, because a root process started by an app shares that app's mount
     * namespace, where data isolation hides other apps' directories under /data/user/0.
     */
    static int packageUid(BufferedReader packages, String name) throws IOException {
        for (String line; (line = packages.readLine()) != null; ) {
            String[] fields = line.split(" ");
            if (fields.length >= 2 && name.equals(fields[0])) return Integer.parseInt(fields[1]);
        }
        throw new IllegalStateException("target_uid_invalid");
    }
}
