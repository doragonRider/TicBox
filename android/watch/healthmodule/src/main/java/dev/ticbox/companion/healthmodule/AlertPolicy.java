package dev.ticbox.companion.healthmodule;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.provider.Settings;
import android.util.Log;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/** Modes affect only verified Android phone stream items, never local alarms/calls. */
final class AlertPolicy {
    enum Mode { OFF, OBSERVE, PROBE, ALL }
    static final String PROBE_PACKAGE = "dev.ticbox.alertprobe";
    private final Context context;
    private final SharedPreferences preferences;
    private final AtomicLong observed = new AtomicLong(), restricted = new AtomicLong(), failures = new AtomicLong();
    private volatile Mode mode;
    private volatile String issue;
    private volatile boolean installed;

    AlertPolicy(Context context) {
        this.context = context;
        preferences = context.getSharedPreferences("ticwatch_notification_alerts_v1", Context.MODE_PRIVATE);
        // On until turned off: a SystemUI build with no saved mode follows the phone's choices from its first start.
        mode = parse(preferences.getString(AlertProfile.SHA256, Mode.ALL.name()));
    }

    /** A saved mode this version doesn't know only observes. */
    static Mode parse(String value) {
        try { return Mode.valueOf(value); } catch (RuntimeException invalid) { return Mode.OBSERVE; }
    }
    void installed() { installed = true; }
    void failed() {
        installed = false; issue = "notification_alert_contract_unavailable";
        if (failures.incrementAndGet() == 1) Log.w("TicNotifyModule", issue);
    }
    Mode mode() { return mode; }
    boolean active() { return installed; }

    boolean patch(String packageName) {
        Mode selected = mode;
        return installed && (selected == Mode.ALL || selected == Mode.PROBE && PROBE_PACKAGE.equals(packageName));
    }
    boolean soundAllowed(boolean requested) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        return requested && audio != null && audio.getRingerMode() == AudioManager.RINGER_MODE_NORMAL;
    }
    int vibrationIntensity() {
        if (Settings.System.getInt(context.getContentResolver(), "vibrate_on", 1) == 0) return 0;
        return Math.max(0, Math.min(3, Settings.System.getInt(context.getContentResolver(), "notification_vibration_intensity", 1)));
    }
    void observe(String packageName, boolean sound, boolean vibrate, boolean patternPresent, boolean plugged, boolean ringtone, boolean patched) {
        // Count loader calls, not notifications or measured audible/tactile effects. No item IDs/content.
        if (!PROBE_PACKAGE.equals(packageName)) return;
        long count = observed.incrementAndGet();
        if (patched) restricted.incrementAndGet();
        if (count <= 32 || count % 100 == 0) Log.i("TicNotifyModule", "alert_intent role=" + (ringtone ? "ringtone" : "vibration") +
                " sound=" + sound + " vibrate=" + vibrate + " patternPresent=" + patternPresent + " plugged=" + plugged + " patched=" + patched);
    }

    synchronized JSONObject request(JSONObject request) {
        try {
            if (request.length() < 2 || request.length() > 3 || request.optInt("version", -1) != 1 ||
                    !(request.opt("version") instanceof Integer) || !(request.opt("op") instanceof String)) return result("invalid_request");
            String op = request.getString("op");
            if ("status".equals(op) && request.length() == 2) return result(null);
            if (!"mode".equals(op) || request.length() != 3 || !(request.opt("mode") instanceof String)) return result("invalid_request");
            Mode selected;
            try { selected = Mode.valueOf(request.getString("mode")); }
            catch (IllegalArgumentException invalid) { return result("invalid_mode"); }
            if (!installed && selected != Mode.OFF && selected != Mode.OBSERVE) return result("notification_alert_contract_unavailable");
            if (!preferences.edit().putString(AlertProfile.SHA256, selected.name()).commit()) return result("mode_write_unconfirmed");
            mode = selected;
            return result(null);
        } catch (Exception invalid) { return result("invalid_request"); }
    }
    JSONObject result(String error) {
        JSONObject value = new JSONObject();
        try {
            value.put("version", 1).put("moduleVersionCode", 1).put("package", AlertProfile.PACKAGE)
                    .put("versionCode", AlertProfile.VERSION_CODE).put("versionName", AlertProfile.VERSION_NAME).put("sha256", AlertProfile.SHA256)
                    .put("installed", installed).put("mode", mode.name()).put("probeLoaderCalls", observed.get())
                    .put("probePatchedLoaderCalls", restricted.get()).put("failures", failures.get());
            if (issue != null) value.put("issue", issue);
            if (error != null) value.put("error", error);
        } catch (Exception ignored) { }
        return value;
    }
}
