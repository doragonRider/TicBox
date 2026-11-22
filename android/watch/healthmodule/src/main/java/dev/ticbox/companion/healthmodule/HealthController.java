package dev.ticbox.companion.healthmodule;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Called exclusively by the main-Handler dispatcher, including async completions. */
final class HealthController {
    interface Completion { void complete(Exception failure); }
    interface Reply { void complete(JSONObject result); }
    interface Store {
        String read(HealthProfile.Setting setting) throws Exception;
        boolean periodsCompatible(String firstStart, String firstEnd, String secondStart, String secondEnd) throws Exception;
        void apply(HealthProfile.Setting setting, String value, Map<String, String> before, Completion completion);
    }
    static final class Failure extends Exception {
        final String reason;
        Failure(String reason) { super(reason); this.reason = reason; }
    }

    private final HealthProfile profile;
    private final Store store;
    HealthController(HealthProfile profile, Store store) { this.profile = profile; this.store = store; }

    void execute(JSONObject request, Reply reply) {
        try {
            Iterator<String> keys = request.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!"version".equals(key) && !"op".equals(key) && !"id".equals(key) && !"value".equals(key))
                    throw new Failure("health_module_invalid_request");
            }
            Object version = request.opt("version");
            if (!(version instanceof Number) || ((Number) version).doubleValue() != 1.0 || !"settings".equals(request.opt("op")))
                throw new Failure("health_module_invalid_request");
            final HealthProfile.Setting setting;
            if (request.has("id")) {
                Object id = request.opt("id");
                setting = id instanceof String ? HealthProfile.find((String) id) : null;
                if (setting == null) throw new Failure("health_module_unknown_setting");
            } else {
                setting = null;
            }
            if (!request.has("value")) { reply.complete(snapshot("READ", null)); return; }
            if (setting == null) throw new Failure("health_module_invalid_request");
            final String value;
            try { value = HealthProfile.canonical(setting, request.opt("value")); }
            catch (IllegalArgumentException error) { throw new Failure(error.getMessage()); }
            final Map<String, String> before = readValues();
            if (!before.containsKey(setting.id)) throw new Failure("health_module_setting_unavailable");
            validate(setting, value, before);
            final boolean unchanged = value.equals(before.get(setting.id));
            // An explicit retry may follow a successful preference write whose
            // alarm/DB side effect failed. Reapply safe OEM refreshes; never
            // silently equate same stored value with completed side effects.
            store.apply(setting, value, before, failure -> {
                try {
                    if (failure != null) { reply.complete(snapshot("STORED_UNVERIFIED", reason(failure, "health_module_apply_failed"))); return; }
                    String observed = store.read(setting);
                    if (!value.equals(observed)) {
                        reply.complete(snapshot("STORED_UNVERIFIED", "health_module_readback_mismatch")); return;
                    }
                    reply.complete(snapshot(unchanged ? "UNCHANGED" : "STORED_AND_NOTIFIED", null));
                } catch (Exception error) { reply.complete(unavailable(profile, "STORED_UNVERIFIED", reason(error, "health_module_readback_failed"))); }
            });
        } catch (Exception error) {
            reply.complete(snapshot("FAILED", reason(error, "health_module_request_failed")));
        }
    }

    private void validate(HealthProfile.Setting setting, String value, Map<String, String> before) throws Exception {
        if ("sleep_time".equals(setting.key) || "getup_time".equals(setting.key)) {
            String start = "sleep_time".equals(setting.key) ? value : required(before, "SLEEP_EXPECTED_BEDTIME");
            String end = "getup_time".equals(setting.key) ? value : required(before, "SLEEP_EXPECTED_WAKE_TIME");
            if (start.equals(end)) throw new Failure("health_module_equal_sleep_times");
            if ("true".equals(required(before, "SLEEP_EXCLUSION_ENABLED")) &&
                    !store.periodsCompatible(start, end, required(before, "SLEEP_EXCLUSION_START"), required(before, "SLEEP_EXCLUSION_END")))
                throw new Failure("health_module_period_conflict");
        } else if ("exclusion_time_open_status".equals(setting.key)) {
            if ("true".equals(value) && !store.periodsCompatible(required(before, "SLEEP_EXPECTED_BEDTIME"), required(before, "SLEEP_EXPECTED_WAKE_TIME"),
                    required(before, "SLEEP_EXCLUSION_START"), required(before, "SLEEP_EXCLUSION_END")))
                throw new Failure("health_module_period_conflict");
        } else if ("exclusion_start_time".equals(setting.key) || "exclusion_end_time".equals(setting.key)) {
            String start = "exclusion_start_time".equals(setting.key) ? value : required(before, "SLEEP_EXCLUSION_START");
            String end = "exclusion_end_time".equals(setting.key) ? value : required(before, "SLEEP_EXCLUSION_END");
            if ("true".equals(required(before, "SLEEP_EXCLUSION_ENABLED")) &&
                    !store.periodsCompatible(start, end, required(before, "SLEEP_EXPECTED_BEDTIME"), required(before, "SLEEP_EXPECTED_WAKE_TIME")))
                throw new Failure("health_module_period_conflict");
        }
    }

    static String required(Map<String, String> values, String id) throws Failure {
        String value = values.get(id);
        if (value == null) throw new Failure("health_module_dependency_unavailable");
        return value;
    }

    private Map<String, String> readValues() {
        Map<String, String> values = new LinkedHashMap<>();
        for (HealthProfile.Setting setting : HealthProfile.SETTINGS) {
            try { values.put(setting.id, HealthProfile.canonical(setting, store.read(setting))); }
            catch (Exception ignored) { /* An unavailable sibling must not hide all other settings. */ }
        }
        return values;
    }

    private JSONObject snapshot(String outcome, String failureReason) {
        JSONArray settings = new JSONArray();
        for (HealthProfile.Setting setting : HealthProfile.SETTINGS) {
            JSONObject entry = new JSONObject();
            put(entry, "id", setting.id); put(entry, "default", setting.defaultText(profile));
            try {
                put(entry, "value", HealthProfile.canonical(setting, store.read(setting))); put(entry, "available", true);
            } catch (Exception error) {
                put(entry, "value", JSONObject.NULL); put(entry, "available", false);
                put(entry, "unavailableReason", reason(error, "health_module_oem_read_failed"));
            }
            settings.put(entry);
        }
        return reply(profile, outcome, failureReason, settings);
    }

    static JSONObject unavailable(HealthProfile profile, String outcome, String reason) {
        JSONArray settings = new JSONArray();
        for (HealthProfile.Setting setting : HealthProfile.SETTINGS) {
            JSONObject entry = new JSONObject();
            put(entry, "id", setting.id); put(entry, "value", JSONObject.NULL); put(entry, "available", false);
            put(entry, "unavailableReason", reason); put(entry, "default", setting.defaultText(profile)); settings.put(entry);
        }
        return reply(profile, outcome, reason, settings);
    }

    private static JSONObject reply(HealthProfile profile, String outcome, String reason, JSONArray settings) {
        JSONObject result = new JSONObject();
        put(result, "version", 1); put(result, "moduleVersionCode", 1); put(result, "packageName", HealthProfile.PACKAGE);
        put(result, "versionCode", profile.versionCode); put(result, "apkSha256", profile.sha256); put(result, "outcome", outcome);
        if (reason != null) put(result, "reason", reason);
        put(result, "settings", settings); return result;
    }
    static String reason(Exception error, String fallback) { return error instanceof Failure ? ((Failure) error).reason : fallback; }
    static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
}
