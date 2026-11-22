package dev.ticbox.companion.healthmodule;

/** This mapping is valid only for the complete APK whose byte identity was reviewed. */
final class AlertProfile {
    static final String PACKAGE = "com.mobvoi.wear.refsysui";
    static final String APPLICATION = "com.google.android.clockwork.sysui.application.SysUiApplication";
    static final String COMPONENT = "com.google.android.clockwork.sysui.application.c0$j";
    static final String SHA256 = "6738b7e13feba118437166f9dbedb2471add85de1492f2294b91e696442d54f0";
    static final String VERSION_NAME = "1.5.0";
    static final long VERSION_CODE = 150;
    private AlertProfile() { }

    static boolean matches(String name, long versionCode, String versionName, String sha256) {
        return PACKAGE.equals(name) && VERSION_CODE == versionCode && VERSION_NAME.equals(versionName) && SHA256.equals(sha256);
    }
}
