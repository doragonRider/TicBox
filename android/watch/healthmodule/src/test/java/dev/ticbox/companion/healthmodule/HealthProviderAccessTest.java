package dev.ticbox.companion.healthmodule;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class HealthProviderAccessTest {
    private static final String AUTHORITY = "com.mobvoi.health.provider.settings";
    private static final class Provider extends ContentProvider {
        final Map<String, String> values = new HashMap<>();
        int writes, steps;
        @Override public boolean onCreate() { return true; }
        @Override public Bundle call(String method, String arg, Bundle extras) {
            assertNull(arg); assertEquals("app_ctrl", extras.getString("table"));
            String key = extras.getString("name"), previous = values.get(key);
            if ("put".equals(method)) { writes++; values.put(key, extras.getString("value")); }
            Bundle result = new Bundle(); result.putString("value", previous); result.putBoolean("status", true); return result;
        }
        @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
        @Override public String getType(Uri u) { return null; }
        @Override public Uri insert(Uri u, ContentValues v) { return null; }
        @Override public int delete(Uri u, String s, String[] a) { return 0; }
        @Override public int update(Uri u, ContentValues v, String s, String[] a) {
            assertEquals("com.mobvoi.ticwear.steps", u.getAuthority()); assertEquals(1, v.size());
            values.put("step_pedometer", v.getAsBoolean("enable").toString()); steps++; return 0;
        }
    }
    private static JSONObject request(String method, String key, Object value) throws Exception {
        JSONObject result = new JSONObject().put("version", 1).put("op", "provider").put("method", method);
        if (key != null) result.put("key", key);
        if (value != null) result.put("value", value);
        return result;
    }
    private static void register(Context context, Provider provider, String authority, String owner) {
        ProviderInfo info = new ProviderInfo(); info.authority = authority; info.packageName = owner; info.name = Provider.class.getName(); info.exported = true;
        provider.attachInfo(context, info);
        shadowOf(context.getPackageManager()).addOrUpdateProvider(info);
        ShadowContentResolver.registerProviderInternal(authority, provider);
    }
    @Test public void usesTheLiveProviderAndReturnsOldValueWithoutClaimingItIsTheNewValue() throws Exception {
        Context context = RuntimeEnvironment.getApplication(); Provider provider = new Provider();
        register(context, provider, AUTHORITY, HealthProfile.PACKAGE);
        provider.values.put("auto_detect_pressure", "false");
        HealthProviderAccess access = new HealthProviderAccess(context, HealthProfile.V56);
        JSONObject put = access.execute(request("put", "auto_detect_pressure", "true"));
        assertTrue(put.getBoolean("success")); assertTrue(put.getBoolean("status")); assertEquals("false", put.getString("value"));
        JSONObject get = access.execute(request("get", "auto_detect_pressure", null));
        assertEquals("true", get.getString("value")); assertEquals(1, get.getInt("providerVersion"));
        assertEquals(HealthProfile.V56.sha256, get.getString("apkSha256")); assertEquals(1, provider.writes);
    }
    @Test public void stepChangesUseTheDedicatedProviderEvenWhenUpdateReturnsZero() throws Exception {
        Context context = RuntimeEnvironment.getApplication(); Provider provider = new Provider();
        register(context, provider, "com.mobvoi.ticwear.steps", HealthProfile.PACKAGE);
        HealthProviderAccess access = new HealthProviderAccess(context, HealthProfile.V56);
        assertTrue(access.execute(request("step", null, "false")).getBoolean("success"));
        assertEquals(1, provider.steps); assertEquals("false", provider.values.get("step_pedometer"));
        assertFalse(access.execute(request("put", "step_pedometer", "true")).getBoolean("success"));
        assertEquals(0, provider.writes);
    }
    @Test public void unrecognizedKeysMethodsTablesValuesAndOwnersCannotReachProviders() throws Exception {
        Context context = RuntimeEnvironment.getApplication(); Provider provider = new Provider();
        register(context, provider, AUTHORITY, HealthProfile.PACKAGE);
        HealthProviderAccess access = new HealthProviderAccess(context, HealthProfile.V56);
        JSONObject[] invalid = {
            request("put", "auto_detect_pressure", "1"), request("put", "auto_detect_pressure", true),
            request("put", "alert_sit_long", "false"), request("put", "background_hrm_mode", "2"),
            request("put", "rest_hrm_warning_upper", "111"), request("fitness_put", null, "500"),
            request("get", "auto_detect_pressure", null).put("table", "healthy"),
            request("get", "auto_detect_pressure", "ignored"), request("delete", "auto_detect_pressure", null),
            request("get", "auto_detect_pressure", null).put("version", "1"),
        };
        for (JSONObject request : invalid) assertFalse(request.toString(), access.execute(request).getBoolean("success"));
        assertEquals(0, provider.writes);

    }
    @Test public void anImpostorProviderIsRejectedBeforeCallingIt() throws Exception {
        Context context = RuntimeEnvironment.getApplication(); Provider provider = new Provider();
        register(context, provider, AUTHORITY, "wrong.package");
        HealthProviderAccess access = new HealthProviderAccess(context, HealthProfile.V56);
        assertEquals("monitoring_provider_identity_mismatch", access.execute(request("get", "auto_detect_pressure", null)).getString("reason"));
        assertEquals(0, provider.writes);
    }
    @Test public void providerCatalogAcceptsAllDocumentedCanonicalBoundariesOnly() throws Exception {
        for (String key : new String[]{"background_hrm","auto_detect_blood_oxygen","auto_detect_pressure","auto_detect_body_mind_state","auto_detect_heart_health","auto_detect_sleep","auto_detect_fitness","detect_respiration_rate","detect_temperature","rest_hrm_warning","fitness_hrm_warning","auto_motion"}) {
            HealthProviderAccess.validate(request("put", key, "false")); HealthProviderAccess.validate(request("put", key, "true"));
        }
        for (String value : new String[]{"60","1800","3600"}) HealthProviderAccess.validate(request("put", "sleep_detect_blood_oxygen_frequency", value));
        for (String value : new String[]{"100","220"}) HealthProviderAccess.validate(request("fitness_put", null, value));
    }
}
