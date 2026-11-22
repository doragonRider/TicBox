package dev.ticbox.companion.healthmodule;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.Map;

/** Reflection is limited to one exact profile and the fixed 16-setting catalog. */
final class OemHealthStore implements HealthController.Store {
    private final Application application;
    private final HealthProfile profile;
    private final ClassLoader loader;
    private final Handler main;
    OemHealthStore(Application application, HealthProfile profile, Handler main) {
        this.application = application; this.profile = profile; this.loader = application.getClassLoader(); this.main = main;
    }

    private Object healthy() throws Exception { return call(null, profile.healthyClass, profile.healthyFactory, new Class<?>[]{Context.class}, application); }
    private Object sleep() throws Exception { return call(null, profile.sleepConstants, "a", new Class<?>[]{Context.class}, application); }

    @Override public String read(HealthProfile.Setting setting) throws Exception {
        assertMain();
        Object value;
        if (setting.healthyIndex >= 0) value = call(healthy(), profile.healthyClass, profile.healthyGetters[setting.healthyIndex], new Class<?>[0]);
        else {
            Object pref = sleep();
            value = invoke(pref.getClass().getMethod("b", String.class, Object.class), pref, setting.key, setting.defaultObject(profile));
        }
        if ((setting.kind == HealthProfile.Kind.BOOLEAN && !(value instanceof Boolean)) ||
                (setting.kind != HealthProfile.Kind.BOOLEAN && !(value instanceof String)))
            throw new HealthController.Failure("health_module_oem_value_type_mismatch");
        return HealthProfile.canonical(setting, value.toString());
    }

    @Override public boolean periodsCompatible(String firstStart, String firstEnd, String secondStart, String secondEnd) throws Exception {
        assertMain();
        Object value = call(null, profile.timeUtils, profile.periodValidator,
                new Class<?>[]{String.class, String.class, String.class, String.class}, firstStart, firstEnd, secondStart, secondEnd);
        if (!(value instanceof Boolean)) throw new HealthController.Failure("health_module_oem_contract_changed");
        return (Boolean) value;
    }

    @Override public void apply(HealthProfile.Setting setting, String value, Map<String, String> before, HealthController.Completion completion) {
        try {
            assertMain();
            if (setting.healthyIndex >= 0) {
                Class<?> parameter = setting.kind == HealthProfile.Kind.BOOLEAN ? boolean.class : String.class;
                call(healthy(), profile.healthyClass, profile.healthySetters[setting.healthyIndex], new Class<?>[]{parameter}, HealthProfile.typed(setting, value));
                if ("alert_sit_long".equals(setting.key) && "true".equals(value))
                    call(null, "com.mobvoi.ticwear.health.bg.TimerTaskService", "a", new Class<?>[]{Context.class}, application);
            } else if ("sleep_time".equals(setting.key) || "getup_time".equals(setting.key)) {
                applySleepCycle(setting, value, before, completion); return;
            } else if ("exclusion_start_time".equals(setting.key) || "exclusion_end_time".equals(setting.key)) {
                String start = "exclusion_start_time".equals(setting.key) ? value : HealthController.required(before, "SLEEP_EXCLUSION_START");
                String end = "exclusion_end_time".equals(setting.key) ? value : HealthController.required(before, "SLEEP_EXCLUSION_END");
                Object pref = sleep();
                writePref(pref, "exclusion_start_time", start); writePref(pref, "exclusion_end_time", end);
                writePref(pref, "exclusion_time_target", duration(start, end));
                verifyPref(pref, "exclusion_start_time", start); verifyPref(pref, "exclusion_end_time", end);
                verifyPref(pref, "exclusion_time_target", duration(start, end));
            } else {
                writePref(sleep(), setting.key, HealthProfile.typed(setting, value));
                if ("sleep_alarm".equals(setting.key) || "smart_awake".equals(setting.key)) alarm("c");
                else if ("sleep_notification".equals(setting.key)) alarm("e");
            }
            completion.complete(null);
        } catch (Exception error) { completion.complete(error); }
        catch (LinkageError error) { completion.complete(new HealthController.Failure("health_module_oem_contract_changed")); }
    }

    private void applySleepCycle(HealthProfile.Setting setting, String value, Map<String, String> before, HealthController.Completion completion) throws Exception {
        String start = "sleep_time".equals(setting.key) ? value : HealthController.required(before, "SLEEP_EXPECTED_BEDTIME");
        String end = "getup_time".equals(setting.key) ? value : HealthController.required(before, "SLEEP_EXPECTED_WAKE_TIME");
        long now = System.currentTimeMillis();
        long startMillis = timeMillis(start), endMillis = timeMillis(end);
        Object storage = call(null, profile.sleepStorage, "x", new Class<?>[0]);
        Object rawWorker = call(storage, profile.sleepStorage, "N", new Class<?>[0]);
        if (!(rawWorker instanceof Handler)) throw new HealthController.Failure("health_module_oem_contract_changed");
        Handler worker = (Handler) rawWorker;
        if (value.equals(before.get(setting.id))) {
            if (!worker.post(() -> {
                boolean pointsMatch;
                try { verifySleepPoints(storage, now, startMillis, endMillis); pointsMatch = true; }
                catch (Exception mismatch) {
                    if (!(mismatch instanceof HealthController.Failure) ||
                            !"health_module_sleep_db_readback_mismatch".equals(((HealthController.Failure) mismatch).reason)) {
                        main.post(() -> completion.complete(mismatch)); return;
                    }
                    pointsMatch = false;
                }
                final boolean needsDbWrite = !pointsMatch;
                main.post(() -> {
                    try {
                        for (String id : new String[]{"SLEEP_EXPECTED_BEDTIME", "SLEEP_EXPECTED_WAKE_TIME", "SLEEP_EXCLUSION_ENABLED", "SLEEP_EXCLUSION_START", "SLEEP_EXCLUSION_END"}) {
                            if (!HealthController.required(before, id).equals(read(HealthProfile.find(id))))
                                throw new HealthController.Failure("health_module_settings_changed");
                        }
                        finishSleepCycle(storage, worker, now, start, end, startMillis, endMillis, needsDbWrite, completion);
                    } catch (Exception error) { completion.complete(error); }
                });
            })) throw new HealthController.Failure("health_module_oem_worker_unavailable");
        } else finishSleepCycle(storage, worker, now, start, end, startMillis, endMillis, true, completion);
    }

    private void finishSleepCycle(Object storage, Handler worker, long now, String start, String end,
            long startMillis, long endMillis, boolean writeDatabase, HealthController.Completion completion) throws Exception {
        if (writeDatabase) call(storage, profile.sleepStorage, "R", new Class<?>[]{long.class, long.class, long.class}, now, startMillis, endMillis);
        Object pref = sleep();
        int week = Calendar.getInstance().get(Calendar.WEEK_OF_YEAR), target = duration(start, end);
        writePref(pref, "week_of_year", week); writePref(pref, "sleep_target", target);
        writePref(pref, "sleep_time", start); writePref(pref, "getup_time", end);
        alarm("c");
        verifyPref(pref, "week_of_year", week); verifyPref(pref, "sleep_target", target);
        // OEM R posts two writes to SleepWorker. Queue a barrier on that same
        // worker; never block the application's main thread waiting for SQLite.
        if (!worker.post(() -> {
            Exception failure = null;
            try {
                verifySleepPoints(storage, now, startMillis, endMillis);
            } catch (Exception error) { failure = error; }
            catch (LinkageError error) { failure = new HealthController.Failure("health_module_oem_contract_changed"); }
            final Exception result = failure;
            main.post(() -> {
                try {
                    if (result != null) { completion.complete(result); return; }
                    verifyPref(pref, "sleep_time", start); verifyPref(pref, "getup_time", end);
                    verifyPref(pref, "sleep_target", target); verifyPref(pref, "week_of_year", week);
                    completion.complete(null);
                } catch (Exception error) { completion.complete(error); }
            });
        })) throw new HealthController.Failure("health_module_oem_worker_unavailable");
    }

    private void verifySleepPoints(Object storage, long now, long startMillis, long endMillis) throws Exception {
        long endExclusive = now + 1;
        Object sleepPoint = call(storage, profile.sleepStorage, "A", new Class<?>[]{long.class, long.class}, 0L, endExclusive);
        Object wakePoint = call(storage, profile.sleepStorage, "z", new Class<?>[]{long.class, long.class}, 0L, endExclusive);
        verifyPoint(sleepPoint, startMillis); verifyPoint(wakePoint, endMillis);
    }

    private void verifyPoint(Object point, long expected) throws Exception {
        if (point == null) throw new HealthController.Failure("health_module_sleep_db_readback_mismatch");
        Object value = invoke(point.getClass().getMethod("g"), point);
        if (!(value instanceof Number) || ((Number) value).longValue() != expected)
            throw new HealthController.Failure("health_module_sleep_db_readback_mismatch");
    }
    private void alarm(String method) throws Exception { call(null, profile.alarmUtils, method, new Class<?>[]{Context.class}, application); }
    private void writePref(Object pref, String key, Object value) throws Exception { invoke(pref.getClass().getMethod("c", String.class, Object.class), pref, key, value); }
    private void verifyPref(Object pref, String key, Object expected) throws Exception {
        Object raw = invoke(pref.getClass().getMethod("d"), pref);
        if (!(raw instanceof SharedPreferences)) throw new HealthController.Failure("health_module_oem_contract_changed");
        SharedPreferences preferences = (SharedPreferences) raw;
        if (!preferences.contains(key) || !expected.equals(preferences.getAll().get(key)))
            throw new HealthController.Failure("health_module_readback_mismatch");
    }
    private int duration(String start, String end) throws Exception {
        Object value = call(null, profile.timeUtils, "g", new Class<?>[]{String.class, String.class}, start, end);
        if (!(value instanceof Integer)) throw new HealthController.Failure("health_module_oem_contract_changed");
        return (Integer) value;
    }
    private long timeMillis(String value) throws Exception {
        Object result = call(null, profile.timeUtils, "h", new Class<?>[]{String.class}, value);
        if (!(result instanceof Long)) throw new HealthController.Failure("health_module_oem_contract_changed");
        return (Long) result;
    }
    private Object call(Object receiver, String owner, String name, Class<?>[] signature, Object... args) throws Exception {
        try {
            Class<?> type = Class.forName(owner, false, loader);
            return invoke(type.getMethod(name, signature), receiver, args);
        } catch (LinkageError error) { throw new HealthController.Failure("health_module_oem_contract_changed"); }
    }
    private static Object invoke(Method method, Object receiver, Object... args) throws Exception {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof SecurityException) throw new HealthController.Failure("health_module_oem_permission_denied");
            throw new HealthController.Failure("health_module_oem_call_failed");
        }
    }
    private static void assertMain() throws HealthController.Failure {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new HealthController.Failure("health_module_main_thread_required");
    }
}
