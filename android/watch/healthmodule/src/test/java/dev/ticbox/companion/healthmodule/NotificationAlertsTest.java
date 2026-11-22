package dev.ticbox.companion.healthmodule;

import android.media.AudioManager;
import android.os.Looper;
import android.provider.Settings;
import com.google.android.clockwork.sysui.application.SysUiApplication;
import com.google.android.clockwork.sysui.application.c0;
import com.google.android.clockwork.sysui.backend.notification.sysuicontract.StreamItemIdAndRevision;
import java.io.BufferedReader;
import java.io.StringReader;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE, application = SysUiApplication.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class NotificationAlertsTest {
    private SysUiApplication app;
    private AlertPolicy policy;
    private c0.j component;
    private AudioManager audio;

    @Before public void setup() {
        app = (SysUiApplication) RuntimeEnvironment.getApplication();
        app.getSharedPreferences("ticwatch_notification_alerts_v1", 0).edit().clear().commit();
        component = new c0.j(false); app.component = component;
        policy = new AlertPolicy(app); TestAlerts.reset();
        audio = app.getSystemService(AudioManager.class); audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        Settings.System.putInt(app.getContentResolver(), "vibrate_on", 1);
        Settings.System.putInt(app.getContentResolver(), "notification_vibration_intensity", 1);
    }
    private void install() throws Exception { new OemAlertAdapter(app.getClassLoader(), policy).install(app); }
    @Test public void theControlFindsSystemUiInTheSystemsPackageList() throws Exception {
        String list = AlertProfile.PACKAGE + ".overlay 10009 0 /data/user/0/x default none 0 0 1\n" +
                AlertProfile.PACKAGE + " 10008 0 /data/user_de/0/x platform:privapp 3002 0 0 150\n";
        assertEquals(10008, NotificationAlertsControl.packageUid(new BufferedReader(new StringReader(list)), AlertProfile.PACKAGE));
        assertThrows(IllegalStateException.class,
                () -> NotificationAlertsControl.packageUid(new BufferedReader(new StringReader("other 10001\n")), AlertProfile.PACKAGE));
    }
    private JSONObject mode(String value) throws Exception {
        return policy.request(new JSONObject().put("version", 1).put("op", "mode").put("mode", value));
    }
    private void setMode(String value) throws Exception { assertFalse(mode(value).has("error")); }
    private static o6.p notification(String source, boolean sound, boolean vibrate, int kind, long[] pattern) {
        q4.l data = new q4.l() {
            public boolean A3() { return kind == 1; }
            public boolean w3() { return kind == 2; }
            public boolean o3() { return kind == 3; }
            public boolean w2() { return sound; }
            public boolean T2() { return vibrate; }
            public String M2() { return source; }
        };
        q4.k item = new q4.k() {
            public q4.l c() { return data; }
            public StreamItemIdAndRevision d() { return new StreamItemIdAndRevision(); }
        };
        return new o6.p() { public q4.k a() { return item; } public long[] b() { return pattern; } };
    }
    private static o6.p notification(String source, boolean sound, boolean vibrate) { return notification(source, sound, vibrate, 0, new long[]{-1}); }
    private void alert(o6.p item, boolean plugged) {
        TestAlerts.reset(); component.vibrator.alert(item, plugged); component.ringtone.alert(item, plugged);
    }
    private static void effects(int vibrations, int sounds) { assertEquals(vibrations, TestAlerts.vibrations); assertEquals(sounds, TestAlerts.sounds); }

    @Test public void profileRequiresExactIdentityIncludingVersionNameAndFullApkHash() {
        assertTrue(AlertProfile.matches(AlertProfile.PACKAGE, 150, "1.5.0", AlertProfile.SHA256));
        assertFalse(AlertProfile.matches("com.google.android.apps.wearable.systemui", 150, "1.5.0", AlertProfile.SHA256));
        assertFalse(AlertProfile.matches(AlertProfile.PACKAGE, 151, "1.5.0", AlertProfile.SHA256));
        assertFalse(AlertProfile.matches(AlertProfile.PACKAGE, 150, "1.5.1", AlertProfile.SHA256));
        assertFalse(AlertProfile.matches(AlertProfile.PACKAGE, 150, "1.5.0", "0".repeat(64)));
    }

    @Test public void observeAndOffLeaveOriginalEffectsAloneAndOnlyObserveTheProbe() throws Exception {
        install(); assertEquals(AlertPolicy.Mode.OBSERVE, policy.mode());
        alert(notification(AlertPolicy.PROBE_PACKAGE, false, false), false); effects(1, 1);
        assertEquals(2, policy.result(null).getLong("probeLoaderCalls"));
        alert(notification("com.tencent.mm", false, false), false); effects(1, 1);
        assertEquals(2, policy.result(null).getLong("probeLoaderCalls"));
        setMode("OFF"); alert(notification(AlertPolicy.PROBE_PACKAGE, false, false), false); effects(1, 1);
        assertEquals(2, policy.result(null).getLong("probeLoaderCalls"));
        assertEquals(0, policy.result(null).getLong("probePatchedLoaderCalls"));
    }

    @Test public void fourIntentCombinationsAreSeparatedEvenWithAnOriginalCombinedSoundHaptic() throws Exception {
        install(); setMode("PROBE");
        for (boolean sound : new boolean[]{false, true}) for (boolean vibrate : new boolean[]{false, true}) {
            alert(notification(AlertPolicy.PROBE_PACKAGE, sound, vibrate), false);
            effects(vibrate ? 1 : 0, sound ? 1 : 0);
        }
        assertEquals(8, policy.result(null).getLong("probePatchedLoaderCalls"));
        alert(notification("com.tencent.mm", false, false), false); effects(1, 1);
        setMode("ALL"); alert(notification("com.tencent.mm", false, false), false); effects(0, 0);
        alert(notification("com.tencent.mm", true, false), false); effects(0, 1);
    }

    @Test public void allModeLeavesLocalCallsAlarmsMcuAndAncsItemsOnTheirOriginalPath() throws Exception {
        install(); setMode("ALL");
        for (int kind : new int[]{1, 2, 3}) {
            alert(notification(AlertPolicy.PROBE_PACKAGE, false, false, kind, null), false); effects(1, 1);
        }
        assertEquals(0, policy.result(null).getLong("probeLoaderCalls"));
    }

    @Test public void watchMuteAndMotorSettingsRestrictRequestsAndCannotLeaveAudioInsideAHaptic() throws Exception {
        install(); setMode("ALL");
        audio.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
        alert(notification(AlertPolicy.PROBE_PACKAGE, true, true), false); effects(1, 0);
        audio.setRingerMode(AudioManager.RINGER_MODE_SILENT);
        // OEM motor preference is independent of ringtone mode on this watch.
        Settings.System.putInt(app.getContentResolver(), "vibrate_on", 0);
        alert(notification(AlertPolicy.PROBE_PACKAGE, true, true), false); effects(0, 0);
        audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        Settings.System.putInt(app.getContentResolver(), "vibrate_on", 1);
        Settings.System.putInt(app.getContentResolver(), "notification_vibration_intensity", 0);
        alert(notification(AlertPolicy.PROBE_PACKAGE, true, true), false); effects(0, 1);
        alert(notification(AlertPolicy.PROBE_PACKAGE, false, true), false); effects(0, 0);
    }

    @Test public void chargingIsNotMistakenForASoundRequestAndNeverForcesAPureWaveform() throws Exception {
        install(); setMode("ALL");
        for (boolean sound : new boolean[]{false, true}) for (boolean vibrate : new boolean[]{false, true}) {
            alert(notification(AlertPolicy.PROBE_PACKAGE, sound, vibrate), true);
            effects(0, sound ? 1 : 0); assertNull(TestAlerts.lastWaveform);
        }
    }

    @Test public void backgroundRingtoneDecisionDoesNotDependOnTheVibrationThreadOrCallOrder() throws Exception {
        install(); setMode("ALL"); ExecutorService background = Executors.newSingleThreadExecutor();
        try {
            o6.p silent = notification(AlertPolicy.PROBE_PACKAGE, false, false);
            background.submit(() -> component.ringtone.alert(silent, false)).get();
            component.vibrator.alert(silent, false); effects(0, 0);
            o6.p sound = notification(AlertPolicy.PROBE_PACKAGE, true, false);
            background.submit(() -> component.ringtone.alert(sound, false)).get();
            component.vibrator.alert(sound, false); effects(0, 1);
            o6.p vibrate = notification(AlertPolicy.PROBE_PACKAGE, false, true);
            background.submit(() -> component.ringtone.alert(vibrate, false)).get();
            component.vibrator.alert(vibrate, false); effects(1, 1);
        } finally { background.shutdownNow(); }
    }

    @Test public void originalFailurePropagatesOnceWithoutBeingRetriedOrDisablingTheWrapper() throws Exception {
        install(); component.factory.failOriginal = true;
        for (int kind : new int[]{0, 1}) {
            int before = component.factory.calls;
            try { component.vibrator.alert(notification(AlertPolicy.PROBE_PACKAGE, true, true, kind, null), false); fail(); }
            catch (IllegalStateException expected) { assertEquals("fixture original failure", expected.getMessage()); }
            assertEquals(before + 1, component.factory.calls); assertTrue(policy.active());
        }
    }

    @Test public void extensionFailureDisablesPolicyAndDelegatesOnlyOnceToKeepTheHostAvailable() throws Exception {
        install(); setMode("ALL"); component.factory.failPure = true;
        component.vibrator.alert(notification(AlertPolicy.PROBE_PACKAGE, false, true), false);
        assertFalse(policy.active()); assertEquals(1, component.factory.calls); effects(1, 1);
        assertEquals("notification_alert_contract_unavailable", policy.result(null).getString("issue"));
        assertEquals("notification_alert_contract_unavailable", mode("PROBE").getString("error"));
        setMode("OFF"); setMode("OBSERVE");
        alert(notification(AlertPolicy.PROBE_PACKAGE, false, false), false); effects(1, 1);
    }

    @Test public void changedProviderContractCannotPartiallyActivateThePatch() throws Exception {
        component = new c0.j(true); app.component = component;
        try { install(); fail(); } catch (ReflectiveOperationException expected) { }
        assertFalse(policy.active()); alert(notification(AlertPolicy.PROBE_PACKAGE, false, false), false); effects(1, 1);
        app.component = new Object();
        try { install(); fail(); } catch (ReflectiveOperationException expected) { }
        assertFalse(policy.active());
    }

    @Test public void installationRequiresTheRealMainLooper() throws Exception {
        ExecutorService background = Executors.newSingleThreadExecutor();
        try {
            assertTrue(background.submit(() -> {
                assertNotSame(Looper.myLooper(), Looper.getMainLooper());
                try { new OemAlertAdapter(app.getClassLoader(), policy).install(app); return false; }
                catch (IllegalStateException expected) { return true; }
            }).get());
            assertFalse(policy.active());
        } finally { background.shutdownNow(); }
    }

    @Test public void waveformBoundsProtectTheHostAndPreserveExplicitZeroMotorTime() throws Exception {
        assertArrayEquals(new long[]{0, 250}, OemAlertAdapter.vibration(null, 1));
        assertArrayEquals(new long[]{0, 350}, OemAlertAdapter.vibration(new long[]{-1}, 2));
        assertArrayEquals(new long[]{0, 200, 150, 500}, OemAlertAdapter.vibration(new long[]{0, 30_001}, 3));
        assertArrayEquals(new long[]{0, 250}, OemAlertAdapter.vibration(new long[]{0, Long.MAX_VALUE}, 1));
        assertArrayEquals(new long[]{0, 250}, OemAlertAdapter.vibration(new long[129], 1));
        assertArrayEquals(new long[]{0, 250}, OemAlertAdapter.vibration(new long[]{0, 20_000, 0, 20_000}, 1));
        long[] original = {0, 25, 10, 50};
        long[] copy = OemAlertAdapter.vibration(original, 1); assertArrayEquals(original, copy); assertNotSame(original, copy);
        install(); setMode("ALL");
        alert(notification(AlertPolicy.PROBE_PACKAGE, false, true, 0, new long[]{0, 0}), false); effects(0, 0);
        alert(notification(AlertPolicy.PROBE_PACKAGE, false, true, 0, original), false); effects(1, 0);
        assertArrayEquals(original, TestAlerts.lastWaveform);
    }

    @Test public void rootControlHasAFixedSchemaAndSavedModeIsScopedToTheVerifiedApk() throws Exception {
        assertEquals("notification_alert_contract_unavailable", mode("ALL").getString("error"));
        install();
        JSONObject valid = new JSONObject().put("version", 1).put("op", "mode").put("mode", "ALL");
        JSONObject[] invalid = {
            new JSONObject(valid.toString()).put("version", "1"), new JSONObject(valid.toString()).put("version", 2),
            new JSONObject(valid.toString()).put("file", "/other"), new JSONObject(valid.toString()).put("mode", true),
            new JSONObject(valid.toString()).put("op", "shell"), new JSONObject().put("version", 1).put("op", "status").put("extra", false)
        };
        for (JSONObject request : invalid) assertEquals("invalid_request", policy.request(request).getString("error"));
        assertEquals("invalid_mode", mode("arbitrary").getString("error"));
        assertEquals(AlertPolicy.Mode.OBSERVE, policy.mode());
        setMode("PROBE"); assertEquals(AlertPolicy.Mode.PROBE, new AlertPolicy(app).mode());
        assertEquals("PROBE", app.getSharedPreferences("ticwatch_notification_alerts_v1", 0).getString(AlertProfile.SHA256, null));
        assertEquals(AlertPolicy.Mode.OBSERVE, AlertPolicy.parse("future-mode"));
        assertEquals(AlertPolicy.Mode.OBSERVE, AlertPolicy.parse(null));
        JSONObject status = policy.request(new JSONObject().put("version", 1).put("op", "status"));
        assertFalse(status.has("error")); assertTrue(status.getBoolean("installed")); assertEquals(AlertProfile.SHA256, status.getString("sha256"));
    }
}
