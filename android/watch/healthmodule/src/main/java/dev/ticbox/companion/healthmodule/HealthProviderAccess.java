package dev.ticbox.companion.healthmodule;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONObject;

/** Fixed provider operations executed as the verified TicHealth Application, never as a forged UID. */
final class HealthProviderAccess {
    private static final String AUTHORITY = "com.mobvoi.health.provider.settings";
    private static final String STEPS = "com.mobvoi.ticwear.steps";
    private static final String DATA = "com.mobvoi.health.db.provider";
    private static final String INFO = "com.mobvoi.wear.provider.info";
    private static final String ACCOUNT = "com.mobvoi.wear.provider.accountInfo";
    private static final Uri URI = Uri.parse("content://" + AUTHORITY);
    private static final Uri DATA_URI = Uri.parse("content://" + DATA + "/data_point");
    private static final Set<String> TOGGLES = Set.of("background_hrm", "auto_detect_blood_oxygen", "auto_detect_pressure",
            "auto_detect_body_mind_state", "auto_detect_heart_health", "auto_detect_sleep", "auto_detect_fitness",
            "detect_respiration_rate", "detect_temperature", "rest_hrm_warning", "fitness_hrm_warning", "auto_motion");
    private static final Set<String> NUMBERS = Set.of("background_hrm_mode", "rest_hrm_warning_lower", "rest_hrm_warning_upper",
            "sleep_detect_blood_oxygen_frequency", "detect_sleep_temperature_unit");
    private final Context context;
    private final ContentResolver resolver;
    private final HealthProfile profile;

    HealthProviderAccess(Context context, HealthProfile profile) {
        this.context = context; this.resolver = context.getContentResolver(); this.profile = profile;
    }

    JSONObject execute(JSONObject request) {
        JSONObject reply = identity();
        try {
            validate(request);
            String method = request.getString("method");
            String key = request.optString("key", null), value = request.optString("value", null);
            if ("get".equals(method) || "put".equals(method) || "notify".equals(method) || "auto_motion_feature".equals(method)) {
                owner(AUTHORITY, HealthProfile.PACKAGE, true);
                if ("notify".equals(method)) resolver.notifyChange(URI.buildUpon().appendPath("app_ctrl").appendPath(key).build(), null);
                else {
                    Bundle extras = new Bundle();
                    extras.putString("table", "auto_motion_feature".equals(method) ? "flags_feature" : "app_ctrl");
                    extras.putString("name", "auto_motion_feature".equals(method) ? "auto_motion" : key);
                    if ("put".equals(method)) extras.putString("value", value);
                    Bundle result = resolver.call(URI, "put".equals(method) ? "put" : "get", null, extras);
                    if (result == null) throw new Failure("monitoring_provider_no_reply");
                    Object old = result.get("value");
                    if (old != null && (!(old instanceof String) || ((String) old).length() > 256)) throw new Failure("monitoring_value_invalid");
                    reply.put("value", old == null ? JSONObject.NULL : old);
                    if ("put".equals(method)) reply.put("status", result.getBoolean("status", false));
                }
            } else if ("step".equals(method) || "notify_steps".equals(method)) {
                owner(STEPS, HealthProfile.PACKAGE, true);
                Uri uri = Uri.parse("content://" + STEPS);
                if ("step".equals(method)) {
                    ContentValues values = new ContentValues(); values.put("enable", Boolean.valueOf(value));
                    resolver.update(uri, values, null, null);
                } else resolver.notifyChange(uri, null);
            } else if ("fitness_get".equals(method)) reply.put("value", nullable(readFitness(identityForFitness(), System.currentTimeMillis())));
            else if ("fitness_put".equals(method)) { writeFitness(Long.parseLong(value)); reply.put("status", true); }
            reply.put("success", true);
        } catch (Exception error) {
            HealthController.put(reply, "success", false);
            HealthController.put(reply, "reason", error instanceof Failure ? ((Failure) error).reason :
                    error instanceof SecurityException ? "monitoring_module_provider_permission_denied" : "monitoring_module_provider_failed");
        }
        return reply;
    }

    /** The wire never supplies a URI, SQL, package, preference table or arbitrary provider method. */
    static void validate(JSONObject request) throws Exception {
        Iterator<String> keys = request.keys();
        while (keys.hasNext()) if (!Set.of("version", "op", "method", "key", "value").contains(keys.next())) throw new Failure("monitoring_module_invalid_provider_request");
        Object version = request.opt("version");
        if (!(version instanceof Number) || ((Number) version).doubleValue() != 1 || !"provider".equals(request.opt("op")) || !(request.opt("method") instanceof String))
            throw new Failure("monitoring_module_invalid_provider_request");
        String method = request.getString("method");
        boolean hasKey = Set.of("get", "put", "notify").contains(method);
        boolean hasValue = Set.of("put", "step", "fitness_put").contains(method);
        if (!hasKey && !Set.of("step", "notify_steps", "auto_motion_feature", "fitness_get", "fitness_put").contains(method)) throw new Failure("monitoring_module_invalid_provider_request");
        if (request.has("key") != hasKey || request.has("value") != hasValue ||
                hasKey && !(request.opt("key") instanceof String) || hasValue && !(request.opt("value") instanceof String))
            throw new Failure("monitoring_module_invalid_provider_request");
        String key = hasKey ? request.getString("key") : null;
        if (hasKey && !TOGGLES.contains(key) && !NUMBERS.contains(key) && !"step_pedometer".equals(key)) throw new Failure("monitoring_module_invalid_provider_request");
        if (!hasValue) return;
        String value = request.getString("value");
        boolean valid = "step".equals(method) || key != null && TOGGLES.contains(key) ? Set.of("true", "false").contains(value) :
                "fitness_put".equals(method) ? integer(value, 100, 220, 1) :
                "background_hrm_mode".equals(key) || "detect_sleep_temperature_unit".equals(key) ? Set.of("0", "1").contains(value) :
                "rest_hrm_warning_lower".equals(key) ? integer(value, 40, 60, 5) :
                "rest_hrm_warning_upper".equals(key) ? integer(value, 100, 150, 10) :
                "sleep_detect_blood_oxygen_frequency".equals(key) && Set.of("60", "1800", "3600").contains(value);
        // Step changes must use the dedicated provider, including its reset/observer side effects.
        if (!valid || "step_pedometer".equals(key)) throw new Failure("invalid_monitoring_value");
    }

    private static boolean integer(String value, long min, long max, long step) {
        try { long n = Long.parseLong(value); return Long.toString(n).equals(value) && n >= min && n <= max && (n - min) % step == 0; }
        catch (NumberFormatException invalid) { return false; }
    }
    private JSONObject identity() {
        JSONObject reply = new JSONObject();
        HealthController.put(reply, "version", 1); HealthController.put(reply, "moduleVersionCode", 1); HealthController.put(reply, "providerVersion", 1);
        HealthController.put(reply, "packageName", HealthProfile.PACKAGE); HealthController.put(reply, "versionCode", profile.versionCode); HealthController.put(reply, "apkSha256", profile.sha256);
        return reply;
    }
    @SuppressWarnings("deprecation")
    private boolean owner(String authority, String expected, boolean required) throws Failure {
        ProviderInfo provider = context.getPackageManager().resolveContentProvider(authority, 0);
        if (provider == null) { if (required) throw new Failure("monitoring_provider_missing"); return false; }
        if (!expected.equals(provider.packageName)) throw new Failure("monitoring_provider_identity_mismatch");
        return true;
    }
    private static final class FitnessIdentity {
        final String device, account;
        FitnessIdentity(String device, String account) { this.device = device; this.account = account; }
        boolean sameAs(FitnessIdentity other) { return device.equals(other.device) && account.equals(other.account); }
    }
    private FitnessIdentity identityForFitness() throws Exception {
        owner(DATA, HealthProfile.PACKAGE, true); owner(INFO, "com.mobvoi.wear.appsservice", true);
        String device = identityString(INFO, "wear", "wear_device_id");
        if (device.trim().isEmpty() || "illegal_device_id".equals(device)) throw new Failure("monitoring_fitness_device_identity_missing");
        String account = owner(ACCOUNT, "com.mobvoi.wear.account.aw", false) ? identityString(ACCOUNT, "account", "wwid") : identityString(INFO, "phone", "wwid");
        return new FitnessIdentity(device, account);
    }
    private String identityString(String authority, String table, String key) throws Exception {
        Bundle args = new Bundle(); args.putString("table", table); args.putString("name", key);
        Bundle reply = resolver.call(Uri.parse("content://" + authority + "/" + table + "/" + key), "get", null, args);
        Object value = reply == null ? null : reply.get("value");
        if (!(value instanceof String) || ((String) value).length() > 256) throw new Failure("monitoring_fitness_identity_unavailable");
        return (String) value;
    }
    private String readFitness(FitnessIdentity identity, long now) throws Exception {
        if (now <= 0 || now == Long.MAX_VALUE) throw new Failure("monitoring_fitness_clock_invalid");
        String selection = "(time_to >= ? AND time_to < ?) AND type = ?";
        String[] args = new String[]{"0", Long.toString(now + 1), "16"};
        if (!identity.account.isEmpty()) { selection += " AND wwid = ?"; args = new String[]{"0", Long.toString(now + 1), "16", identity.account}; }
        Cursor cursor = resolver.query(DATA_URI, new String[]{"values"}, selection, args, "time_to DESC, _id DESC LIMIT 1");
        if (cursor == null) throw new Failure("monitoring_fitness_query_no_reply");
        try (Cursor owned = cursor) {
            if (!owned.moveToFirst()) return null;
            String value = owned.getString(owned.getColumnIndexOrThrow("values"));
            if (value == null || value.length() > 256) throw new Failure("monitoring_fitness_value_invalid");
            return Long.toString(new BigDecimal(value).toBigIntegerExact().longValueExact());
        }
    }
    private void writeFitness(long value) throws Exception {
        FitnessIdentity identity = identityForFitness();
        long now = System.currentTimeMillis();
        if (now <= 0 || now == Long.MAX_VALUE) throw new Failure("monitoring_fitness_clock_invalid");
        ContentValues values = new ContentValues();
        values.put("device_id", identity.device); values.put("wwid", identity.account); values.put("type", 16);
        values.put("time_from", now); values.put("time_to", now); values.put("values", Long.toString(value)); values.put("synced", 0); values.put("deleted", 0);
        Uri inserted = resolver.insert(DATA_URI, values);
        if (inserted == null || !DATA.equals(inserted.getAuthority()) || inserted.getPathSegments().size() != 2 ||
                !"data_point".equals(inserted.getPathSegments().get(0)) || !inserted.getPathSegments().get(1).matches("[0-9]+"))
            throw new Failure("monitoring_fitness_commit_unverified");
        if (!identity.sameAs(identityForFitness())) throw new Failure("monitoring_fitness_identity_changed");
        if (!Long.toString(value).equals(readFitness(identity, Math.max(now, System.currentTimeMillis())))) throw new Failure("monitoring_fitness_readback_mismatch");
    }
    private static Object nullable(String value) { return value == null ? JSONObject.NULL : value; }
    private static final class Failure extends Exception {
        final String reason;
        Failure(String reason) { super(reason); this.reason = reason; }
    }
}
