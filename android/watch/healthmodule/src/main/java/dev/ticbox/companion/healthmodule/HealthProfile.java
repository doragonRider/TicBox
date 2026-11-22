package dev.ticbox.companion.healthmodule;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Exact shipped APK profiles. No caller-controlled class or method selectors. */
final class HealthProfile {
    static final String PACKAGE = "com.mobvoi.wear.health.at";
    static final String APPLICATION = "com.mobvoi.ticwear.health.HealthApplication";
    static final HealthProfile V55 = new HealthProfile(
            50503292L, "5.5.0-3292.2931", "39969a876dcd716bd9e868a446626f762f8c23c7c90d567a061df9e405666e25",
            "f9.h", "B", new String[]{"i", "o", "g", "p", "c"},
            new String[]{"S", "T", "U", "X", "W"}, "y9.l", "ja.a", "ja.d", "t", "ba.t", "always");
    static final HealthProfile V56 = new HealthProfile(
            50603234L, "5.6.0-3234.2686", "276264832680b5da9fc7bae260ff521e7df6d49c7e04fa40c00f64e3ef96c4db",
            "s8.h", "v", new String[]{"d", "l", "h", "m", "a"},
            new String[]{"M", "N", "O", "R", "Q"}, "l9.l", "w9.a", "w9.d", "u", "o9.t", "once");

    enum Kind { BOOLEAN, TIME, SMART_AWAKE, VIBRATION }
    static final class Setting {
        final String id, key;
        final Kind kind;
        final Object fallback;
        final int healthyIndex;
        Setting(String id, String key, Kind kind, Object fallback, int healthyIndex) {
            this.id = id; this.key = key; this.kind = kind; this.fallback = fallback; this.healthyIndex = healthyIndex;
        }
        String defaultText(HealthProfile profile) {
            return "vibration_mode".equals(key) ? profile.vibrationDefault : fallback.toString();
        }
        Object defaultObject(HealthProfile profile) {
            return "vibration_mode".equals(key) ? profile.vibrationDefault : fallback;
        }
    }

    static final List<Setting> SETTINGS = Collections.unmodifiableList(Arrays.asList(
            new Setting("SEDENTARY_REMINDER", "alert_sit_long", Kind.BOOLEAN, true, 0),
            new Setting("STEP_GOAL_APPROACH_REMINDER", "alert_step_enable_when_80", Kind.BOOLEAN, true, 1),
            new Setting("DAILY_GOALS_REMINDER", "alert_health_time_done", Kind.BOOLEAN, true, 2),
            new Setting("HEALTH_QUIET_START", "health_not_disturb_start_new", Kind.TIME, "22:00", 3),
            new Setting("HEALTH_QUIET_END", "health_not_disturb_end_new", Kind.TIME, "08:00", 4),
            new Setting("SLEEP_BEDTIME_REMINDER", "sleep_notification", Kind.BOOLEAN, false, -1),
            new Setting("SLEEP_COMPLETE_REMINDER", "sleep_complete_notification", Kind.BOOLEAN, false, -1),
            new Setting("SLEEP_WAKE_ALARM", "sleep_alarm", Kind.BOOLEAN, false, -1),
            new Setting("SLEEP_SMART_AWAKE_MINUTES", "smart_awake", Kind.SMART_AWAKE, "0", -1),
            new Setting("SLEEP_ALARM_VIBRATION", "vibration_mode", Kind.VIBRATION, "once", -1),
            new Setting("SLEEP_ALARM_SOUND", "sound_switch", Kind.BOOLEAN, true, -1),
            new Setting("SLEEP_EXPECTED_BEDTIME", "sleep_time", Kind.TIME, "23:00", -1),
            new Setting("SLEEP_EXPECTED_WAKE_TIME", "getup_time", Kind.TIME, "08:00", -1),
            new Setting("SLEEP_EXCLUSION_ENABLED", "exclusion_time_open_status", Kind.BOOLEAN, false, -1),
            new Setting("SLEEP_EXCLUSION_START", "exclusion_start_time", Kind.TIME, "10:00", -1),
            new Setting("SLEEP_EXCLUSION_END", "exclusion_end_time", Kind.TIME, "17:00", -1)
    ));

    final long versionCode;
    final String versionName, sha256, healthyClass, healthyFactory;
    final String[] healthyGetters, healthySetters;
    final String sleepConstants, alarmUtils, timeUtils, periodValidator, sleepStorage, vibrationDefault;

    private HealthProfile(long versionCode, String versionName, String sha256, String healthyClass,
            String healthyFactory, String[] getters, String[] setters, String sleepConstants,
            String alarmUtils, String timeUtils, String periodValidator, String sleepStorage, String vibrationDefault) {
        this.versionCode = versionCode; this.versionName = versionName; this.sha256 = sha256;
        this.healthyClass = healthyClass; this.healthyFactory = healthyFactory;
        this.healthyGetters = getters; this.healthySetters = setters;
        this.sleepConstants = sleepConstants; this.alarmUtils = alarmUtils; this.timeUtils = timeUtils;
        this.periodValidator = periodValidator; this.sleepStorage = sleepStorage;
        this.vibrationDefault = vibrationDefault;
    }

    static HealthProfile match(String packageName, long versionCode, String versionName, String sha256) {
        if (!PACKAGE.equals(packageName)) return null;
        for (HealthProfile profile : new HealthProfile[]{V55, V56}) {
            if (profile.versionCode == versionCode && profile.versionName.equals(versionName) && profile.sha256.equals(sha256)) return profile;
        }
        return null;
    }

    static Setting find(String id) {
        for (Setting setting : SETTINGS) if (setting.id.equals(id)) return setting;
        return null;
    }

    static String canonical(Setting setting, Object value) {
        if (!(value instanceof String) || ((String) value).length() > 16) throw new IllegalArgumentException("health_module_invalid_value");
        String text = (String) value;
        switch (setting.kind) {
            case BOOLEAN:
                if (!"true".equals(text) && !"false".equals(text)) throw new IllegalArgumentException("health_module_invalid_value");
                break;
            case TIME:
                if (text.length() != 5 || text.charAt(2) != ':' || !digit(text.charAt(0)) || !digit(text.charAt(1)) ||
                        !digit(text.charAt(3)) || !digit(text.charAt(4)) || Integer.parseInt(text.substring(0, 2)) > 23 ||
                        Integer.parseInt(text.substring(3)) > 59) throw new IllegalArgumentException("health_module_invalid_time");
                break;
            case SMART_AWAKE:
                if (!Arrays.asList("0", "15", "30", "45", "60").contains(text)) throw new IllegalArgumentException("health_module_invalid_value");
                break;
            case VIBRATION:
                if (!Arrays.asList("never", "once", "always").contains(text)) throw new IllegalArgumentException("health_module_invalid_value");
                break;
            default: throw new IllegalArgumentException("health_module_invalid_value");
        }
        return text;
    }

    static Object typed(Setting setting, String text) {
        return setting.kind == Kind.BOOLEAN ? Boolean.valueOf(text) : text;
    }

    private static boolean digit(char c) { return c >= '0' && c <= '9'; }
}
