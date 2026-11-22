package dev.ticbox.companion.healthmodule;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class HealthModuleTest {
    private Application app;
    private Handler main;
    @Before public void setup() { app = RuntimeEnvironment.getApplication(); TestOem.reset(app); main = new Handler(Looper.getMainLooper()); }

    @Test public void runtimeStatusIsBoundToCompiledBuildAndExcludesSettings() throws Exception {
        JSONObject result = HealthModuleServer.runtimeStatus(HealthProfile.V56);
        assertEquals(ModuleBuildInfo.ID, result.getString("buildId"));
        assertTrue(result.getString("buildId").matches("[0-9a-f]{64}"));
        assertEquals(HealthProfile.V56.sha256, result.getString("apkSha256"));
        assertFalse(result.has("settings"));
    }

    private HealthController controller(HealthProfile profile) { return new HealthController(profile, new OemHealthStore(app, profile, main)); }
    private static JSONObject request(String id, Object value) throws Exception {
        JSONObject request = new JSONObject().put("version", 1).put("op", "settings");
        if (id != null) request.put("id", id);
        if (value != null) request.put("value", value);
        return request;
    }
    private JSONObject execute(HealthController controller, JSONObject request) {
        AtomicReference<JSONObject> result = new AtomicReference<>();
        controller.execute(request, result::set); shadowOf(Looper.getMainLooper()).idle();
        assertNotNull("The worker barrier must eventually complete the response", result.get()); return result.get();
    }
    private static JSONObject entry(JSONObject reply, String id) throws Exception {
        JSONArray settings = reply.getJSONArray("settings");
        for (int i = 0; i < settings.length(); i++) if (id.equals(settings.getJSONObject(i).getString("id"))) return settings.getJSONObject(i);
        throw new AssertionError("Missing catalog setting " + id);
    }

    @Test public void exactPackageVersionNameCodeAndHashAreAllRequired() {
        for (HealthProfile profile : new HealthProfile[]{HealthProfile.V55, HealthProfile.V56}) {
            assertSame(profile, HealthProfile.match(HealthProfile.PACKAGE, profile.versionCode, profile.versionName, profile.sha256));
            assertNull(HealthProfile.match("other.package", profile.versionCode, profile.versionName, profile.sha256));
            assertNull(HealthProfile.match(HealthProfile.PACKAGE, profile.versionCode + 1, profile.versionName, profile.sha256));
            assertNull(HealthProfile.match(HealthProfile.PACKAGE, profile.versionCode, "other version", profile.sha256));
            assertNull(HealthProfile.match(HealthProfile.PACKAGE, profile.versionCode, profile.versionName, "0" + profile.sha256.substring(1)));
        }
    }

    @Test public void allSixteenOriginalReadsHaveTheCorrectVersionSpecificDefaults() throws Exception {
        for (HealthProfile profile : new HealthProfile[]{HealthProfile.V55, HealthProfile.V56}) {
            JSONObject reply = execute(controller(profile), request(null, null));
            assertEquals("READ", reply.getString("outcome")); assertEquals(1, reply.getInt("moduleVersionCode"));
            assertEquals(16, reply.getJSONArray("settings").length()); assertEquals(profile.sha256, reply.getString("apkSha256"));
            for (HealthProfile.Setting setting : HealthProfile.SETTINGS) {
                JSONObject value = entry(reply, setting.id);
                assertTrue(value.getBoolean("available")); assertEquals(setting.defaultText(profile), value.getString("value"));
                assertEquals(setting.defaultText(profile), value.getString("default"));
            }
        }
        assertTrue("Reads never alter a preference or schedule an alarm", TestOem.calls.isEmpty());
    }

    @Test public void allSixteenWritesUseBothAnalyzedOriginalMappingsAndReadBack() throws Exception {
        for (HealthProfile profile : new HealthProfile[]{HealthProfile.V55, HealthProfile.V56}) {
            TestOem.reset(app); HealthController controller = controller(profile);
            Map<String, String> changes = new LinkedHashMap<>();
            changes.put("SEDENTARY_REMINDER", "false"); changes.put("STEP_GOAL_APPROACH_REMINDER", "false"); changes.put("DAILY_GOALS_REMINDER", "false");
            changes.put("HEALTH_QUIET_START", "21:00"); changes.put("HEALTH_QUIET_END", "09:00");
            changes.put("SLEEP_BEDTIME_REMINDER", "true"); changes.put("SLEEP_COMPLETE_REMINDER", "true"); changes.put("SLEEP_WAKE_ALARM", "true");
            changes.put("SLEEP_SMART_AWAKE_MINUTES", "15"); changes.put("SLEEP_ALARM_VIBRATION", "never"); changes.put("SLEEP_ALARM_SOUND", "false");
            changes.put("SLEEP_EXPECTED_BEDTIME", "22:00"); changes.put("SLEEP_EXPECTED_WAKE_TIME", "07:00");
            changes.put("SLEEP_EXCLUSION_START", "11:00"); changes.put("SLEEP_EXCLUSION_END", "18:00"); changes.put("SLEEP_EXCLUSION_ENABLED", "true");
            for (Map.Entry<String, String> change : changes.entrySet()) {
                JSONObject reply = execute(controller, request(change.getKey(), change.getValue()));
                assertEquals(change.getKey(), "STORED_AND_NOTIFIED", reply.getString("outcome"));
                assertEquals(change.getValue(), entry(reply, change.getKey()).getString("value"));
            }
            for (String setter : profile.healthySetters) assertTrue(TestOem.calls.contains(profile.healthyClass + "." + setter));
            assertTrue(TestOem.calls.contains("bedtime_alarm")); assertTrue(TestOem.calls.contains("wake_alarm"));
            assertEquals(2, TestOem.dbWrites);
            assertEquals(22 * 3_600_000L, (long) TestOem.sleepPoint.g()); assertEquals(7 * 3_600_000L, (long) TestOem.wakePoint.g());
            assertEquals(540, app.getSharedPreferences("sleep_settings", 0).getInt("sleep_target", -1));
            assertEquals(420, app.getSharedPreferences("sleep_settings", 0).getInt("exclusion_time_target", -1));
        }
    }

    @Test public void explicitEqualValueRequestRepairsAnAlarmFailureWithoutAutomaticReplay() throws Exception {
        HealthController controller = controller(HealthProfile.V56); TestOem.failAlarm = true;
        JSONObject first = execute(controller, request("SLEEP_WAKE_ALARM", "true"));
        assertEquals("STORED_UNVERIFIED", first.getString("outcome")); assertEquals("true", entry(first, "SLEEP_WAKE_ALARM").getString("value"));
        assertEquals(1, count("wake_alarm"));
        TestOem.failAlarm = false;
        JSONObject retry = execute(controller, request("SLEEP_WAKE_ALARM", "true"));
        assertEquals("UNCHANGED", retry.getString("outcome")); assertEquals(2, count("wake_alarm"));
    }

    @Test public void equalSleepCycleRequestRepairsMissingDatabasePointsButDoesNotAppendThemAgain() throws Exception {
        HealthController controller = controller(HealthProfile.V56); TestOem.dropDbWrites = true;
        JSONObject first = execute(controller, request("SLEEP_EXPECTED_BEDTIME", "22:00"));
        assertEquals("STORED_UNVERIFIED", first.getString("outcome")); assertEquals("22:00", entry(first, "SLEEP_EXPECTED_BEDTIME").getString("value"));
        assertEquals(1, TestOem.dbWrites);
        TestOem.dropDbWrites = false;
        assertEquals("UNCHANGED", execute(controller, request("SLEEP_EXPECTED_BEDTIME", "22:00")).getString("outcome"));
        assertEquals(2, TestOem.dbWrites); assertEquals(22 * 3_600_000L, (long) TestOem.sleepPoint.g());
        assertEquals("UNCHANGED", execute(controller, request("SLEEP_EXPECTED_BEDTIME", "22:00")).getString("outcome"));
        assertEquals("Already correct DB points are not appended on an explicit equal-value refresh", 2, TestOem.dbWrites);
        assertEquals(3, count("wake_alarm"));
    }

    @Test public void invalidValuesUnknownFieldsAndConflictingPeriodsCannotMutateTheOemStore() throws Exception {
        HealthController controller = controller(HealthProfile.V56);
        JSONObject[] bad = {
            request("SEDENTARY_REMINDER", "1"), request("SLEEP_WAKE_ALARM", true), request("HEALTH_QUIET_START", "24:00"),
            request("SLEEP_SMART_AWAKE_MINUTES", "10"), request("SLEEP_ALARM_VIBRATION", "unknown"),
            request("SLEEP_EXPECTED_BEDTIME", "08:00"), request("not_a_setting", "true"),
            request(null, null).put("table", "arbitrary"), request(null, null).put("version", 2), request(null, null).put("op", "shell")
        };
        for (JSONObject request : bad) assertEquals("FAILED", execute(controller, request).getString("outcome"));
        assertTrue(TestOem.calls.isEmpty());
        execute(controller, request("SLEEP_EXPECTED_BEDTIME", "12:00")); TestOem.calls.clear();
        JSONObject conflict = execute(controller, request("SLEEP_EXCLUSION_ENABLED", "true"));
        assertEquals("FAILED", conflict.getString("outcome")); assertEquals("health_module_period_conflict", conflict.getString("reason"));
        assertTrue(TestOem.calls.isEmpty());
    }

    @Test public void corruptOriginalValueIsUnavailableAndDoesNotHideOtherSettingsOrInventADefault() throws Exception {
        app.getSharedPreferences("sleep_settings", 0).edit().putString("smart_awake", "17").commit();
        JSONObject reply = execute(controller(HealthProfile.V56), request(null, null));
        JSONObject corrupt = entry(reply, "SLEEP_SMART_AWAKE_MINUTES");
        assertFalse(corrupt.getBoolean("available")); assertTrue(corrupt.isNull("value")); assertEquals("0", corrupt.getString("default"));
        assertTrue(entry(reply, "SEDENTARY_REMINDER").getBoolean("available"));
        assertEquals("FAILED", execute(controller(HealthProfile.V56), request("SLEEP_SMART_AWAKE_MINUTES", "15")).getString("outcome"));
        assertTrue(TestOem.calls.isEmpty());
    }

    @Test public void sedentaryEnableRefreshesTheOriginalTimerIncludingAnExplicitEqualValueRequest() throws Exception {
        HealthController controller = controller(HealthProfile.V56);
        assertEquals("UNCHANGED", execute(controller, request("SEDENTARY_REMINDER", "true")).getString("outcome"));
        assertEquals(1, count("timer_refresh"));
        execute(controller, request("SEDENTARY_REMINDER", "false")); assertEquals(1, count("timer_refresh"));
        execute(controller, request("SEDENTARY_REMINDER", "true")); assertEquals(2, count("timer_refresh"));
    }

    @Test public void onlyRootPeersAreAcceptedAndFrameCodecUsesBoundedBigEndianUtf8() throws Exception {
        assertTrue(HealthModuleServer.authorized(0));
        for (int uid : new int[]{-1, 1000, 2000, 10067}) assertFalse(HealthModuleServer.authorized(uid));
        JSONObject request = request("HEALTH_QUIET_START", "21:00");
        byte[] encoded = frame(request.toString().getBytes(StandardCharsets.UTF_8));
        assertEquals(request.toString(), HealthModuleServer.readFrame(new ByteArrayInputStream(encoded)).toString());
        ByteArrayOutputStream output = new ByteArrayOutputStream(); HealthModuleServer.writeFrame(output, execute(controller(HealthProfile.V56), request(null, null)));
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(output.toByteArray()));
        int size = input.readInt(); assertEquals(output.size() - 4, size); assertTrue(size < HealthModuleServer.MAX_FRAME);
        byte[] body = new byte[size]; input.readFully(body); assertEquals(16, new JSONObject(new String(body, StandardCharsets.UTF_8)).getJSONArray("settings").length());
        for (byte[] bad : new byte[][]{new byte[]{-1, -1, -1, -1}, new byte[]{0, 1, 0, 1}, frame(new byte[]{(byte) 0xc3, 0x28}), frame("{\"nested\":{}}".getBytes(StandardCharsets.UTF_8)), frame("{} trailing".getBytes(StandardCharsets.UTF_8))}) {
            try { HealthModuleServer.readFrame(new ByteArrayInputStream(bad)); fail("Invalid frame must be rejected"); }
            catch (IOException expected) { }
        }
    }

    private static final class DeferredStore implements HealthController.Store {
        final Map<String, String> values = new LinkedHashMap<>();
        HealthController.Completion pending;
        int writes, reads;
        DeferredStore() { for (HealthProfile.Setting setting : HealthProfile.SETTINGS) values.put(setting.id, setting.defaultText(HealthProfile.V56)); }
        @Override public String read(HealthProfile.Setting setting) { reads++; return values.get(setting.id); }
        @Override public boolean periodsCompatible(String a, String b, String c, String d) { return true; }
        @Override public void apply(HealthProfile.Setting setting, String value, Map<String, String> before, HealthController.Completion completion) {
            writes++; values.put(setting.id, value); pending = completion;
        }
    }

    @Test public void mainDispatcherSerializesCompleteAsyncOperationsWithoutBlockingTheOemMainThread() throws Exception {
        DeferredStore store = new DeferredStore();
        HealthMainDispatcher dispatcher = new HealthMainDispatcher(main, new HealthController(HealthProfile.V56, store), HealthProfile.V56, 2_000);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<JSONObject> first = pool.submit(() -> dispatcher.request(request("SEDENTARY_REMINDER", "false")));
            awaitStart(store); assertNotNull(store.pending); assertFalse(first.isDone());
            int initialReads = store.reads;
            Future<JSONObject> second = pool.submit(() -> dispatcher.request(request(null, null)));
            Thread.sleep(10); shadowOf(Looper.getMainLooper()).idle();
            assertFalse(second.isDone()); assertEquals(initialReads, store.reads);
            store.pending.complete(null); shadowOf(Looper.getMainLooper()).idle();
            assertEquals("STORED_AND_NOTIFIED", first.get(1, TimeUnit.SECONDS).getString("outcome"));
            assertEquals("READ", second.get(1, TimeUnit.SECONDS).getString("outcome")); assertEquals(1, store.writes);
        } finally { pool.shutdownNow(); }
    }

    @Test public void timeoutBeforeMainExecutionCancelsTheQueuedMutation() throws Exception {
        DeferredStore store = new DeferredStore();
        HealthMainDispatcher dispatcher = new HealthMainDispatcher(main, new HealthController(HealthProfile.V56, store), HealthProfile.V56, 25);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            JSONObject result = pool.submit(() -> dispatcher.request(request("SEDENTARY_REMINDER", "false"))).get(1, TimeUnit.SECONDS);
            assertEquals("health_module_main_timeout_before_start", result.getString("reason"));
            shadowOf(Looper.getMainLooper()).idle(); assertEquals(0, store.writes);
        } finally { pool.shutdownNow(); }
    }

    @Test public void timeoutAfterStartNeverReplaysOrClaimsTheMutationWasUndone() throws Exception {
        DeferredStore store = new DeferredStore();
        HealthMainDispatcher dispatcher = new HealthMainDispatcher(main, new HealthController(HealthProfile.V56, store), HealthProfile.V56, 100);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<JSONObject> call = pool.submit(() -> dispatcher.request(request("SEDENTARY_REMINDER", "false")));
            awaitStart(store);
            JSONObject result = call.get(1, TimeUnit.SECONDS);
            assertEquals("STORED_UNVERIFIED", result.getString("outcome")); assertEquals("health_module_operation_in_progress", result.getString("reason"));
            assertEquals("false", store.values.get("SEDENTARY_REMINDER")); assertEquals(1, store.writes);
            store.pending.complete(null); shadowOf(Looper.getMainLooper()).idle(); assertEquals(1, store.writes);
        } finally { pool.shutdownNow(); }
    }

    private void awaitStart(DeferredStore store) throws Exception {
        for (int i = 0; i < 200 && store.pending == null; i++) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(1); }
        assertNotNull(store.pending);
    }
    private static int count(String event) { int count = 0; for (String value : TestOem.calls) if (event.equals(value)) count++; return count; }
    private static byte[] frame(byte[] bytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); DataOutputStream data = new DataOutputStream(output);
        data.writeInt(bytes.length); data.write(bytes); return output.toByteArray();
    }
}
