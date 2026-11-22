package dev.ticbox.companion.ui

import dev.ticbox.companion.ui.toolbox.RemoteScreenSettings
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * The launcher icon. Android only lets an app choose between icons it ships, so each one is a
 * launcher entry of its own, of which one is enabled.
 */
enum class AppIconStyle {
    WARM_WHITE,
    NAVY,
    MIST,
}

data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Wallpaper-based Material You colors; off uses the app's own palette. */
    val dynamicColor: Boolean = true,
    val appIcon: AppIconStyle = AppIconStyle.WARM_WHITE,
)

enum class AppLanguage(val languageTag: String) {
    SYSTEM(""),
    ENGLISH("en"),
    SIMPLIFIED_CHINESE("zh-Hans"),
    TRADITIONAL_CHINESE("zh-Hant");

    companion object {
        fun fromTag(tag: String?): AppLanguage {
            if (tag.isNullOrBlank()) return SYSTEM
            val locale = Locale.forLanguageTag(tag)
            return when {
                locale.language == "en" -> ENGLISH
                locale.language != "zh" -> SYSTEM
                locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO") ->
                    TRADITIONAL_CHINESE
                else -> SIMPLIFIED_CHINESE
            }
        }
    }
}

/** App-level preferences. Language changes go through Android's per-app language setting. */
interface AppPreferences {
    val remoteScreen: StateFlow<RemoteScreenSettings>

    fun setRemoteScreen(settings: RemoteScreenSettings)

    val appearance: StateFlow<AppearanceSettings>
    val language: StateFlow<AppLanguage>

    /** The daily step goal chosen in this app. Null to use the watch's own goal. */
    val stepGoal: StateFlow<Int?>

    fun setThemeMode(mode: ThemeMode)

    fun setDynamicColor(enabled: Boolean)

    fun setAppIcon(icon: AppIconStyle)

    fun setLanguage(language: AppLanguage)

    fun setStepGoal(goal: Int?)

    /** Whether Health leaves out metrics this watch has never recorded. */
    val hideEmptyHealthMetrics: StateFlow<Boolean>

    fun setHideEmptyHealthMetrics(hide: Boolean)

    /**
     * Whether the watch last said it applies per-app sound and vibration, so Notifications can look
     * right offline, and never asks the watch for root on behalf of someone who didn't set this up.
     */
    val notificationAlertsApplied: StateFlow<Boolean>

    fun setNotificationAlertsApplied(applied: Boolean)
}
