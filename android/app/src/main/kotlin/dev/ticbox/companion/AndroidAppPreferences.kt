package dev.ticbox.companion

import android.app.LocaleManager
import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.LocaleList
import android.util.Log
import androidx.core.content.edit
import dev.ticbox.companion.ui.AppIconStyle
import dev.ticbox.companion.ui.AppLanguage
import dev.ticbox.companion.ui.AppPreferences
import dev.ticbox.companion.ui.AppearanceSettings
import dev.ticbox.companion.ui.ThemeMode
import dev.ticbox.companion.ui.toolbox.RemoteScreenSettings
import dev.ticbox.companion.ui.toolbox.ScreenQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Appearance lives in SharedPreferences; the theme is also handed to Android so system surfaces
 * match. The language is Android's per-app language, shared with system Settings.
 */
class AndroidAppPreferences(private val context: Context) : AppPreferences {
    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val localeManager = context.getSystemService(LocaleManager::class.java)
    private val uiModeManager = context.getSystemService(UiModeManager::class.java)
    private val packageManager = context.packageManager

    private val appearanceState = MutableStateFlow(readAppearance())
    private val languageState = MutableStateFlow(readLanguage())
    private val stepGoalState = MutableStateFlow(preferences.getInt(STEP_GOAL, 0).takeIf { it > 0 })
    private val remoteScreenState = MutableStateFlow(readRemoteScreen())
    private val hideEmptyMetricsState =
        MutableStateFlow(preferences.getBoolean(HIDE_EMPTY_METRICS, false))
    private val notificationAlertsAppliedState =
        MutableStateFlow(preferences.getBoolean(NOTIFICATION_ALERTS_APPLIED, false))

    override val appearance: StateFlow<AppearanceSettings> = appearanceState.asStateFlow()
    override val language: StateFlow<AppLanguage> = languageState.asStateFlow()
    override val stepGoal: StateFlow<Int?> = stepGoalState.asStateFlow()
    override val remoteScreen: StateFlow<RemoteScreenSettings> = remoteScreenState.asStateFlow()
    override val hideEmptyHealthMetrics: StateFlow<Boolean> = hideEmptyMetricsState.asStateFlow()
    override val notificationAlertsApplied: StateFlow<Boolean> =
        notificationAlertsAppliedState.asStateFlow()

    init {
        applyThemeMode(appearanceState.value.themeMode)
    }

    override fun setThemeMode(mode: ThemeMode) {
        appearanceState.update { it.copy(themeMode = mode) }
        preferences.edit { putString(THEME_MODE, mode.name) }
        applyThemeMode(mode)
    }

    override fun setDynamicColor(enabled: Boolean) {
        appearanceState.update { it.copy(dynamicColor = enabled) }
        preferences.edit { putBoolean(DYNAMIC_COLOR, enabled) }
    }

    /**
     * Turns on the chosen icon's launcher entry before turning off the others, so there is never a
     * moment without one. The setting is the entries themselves, which survive app updates.
     */
    override fun setAppIcon(icon: AppIconStyle) {
        try {
            setLauncher(icon, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            AppIconStyle.entries
                .filter { it != icon }
                .forEach { setLauncher(it, PackageManager.COMPONENT_ENABLED_STATE_DISABLED) }
        } catch (problem: RuntimeException) {
            Log.w(TAG, "Changing the launcher icon failed: ${problem.javaClass.simpleName}")
        }
        appearanceState.update { it.copy(appIcon = readAppIcon()) }
    }

    override fun setLanguage(language: AppLanguage) {
        // Android persists this and updates the configuration; the UI recomposes in place.
        localeManager.applicationLocales = LocaleList.forLanguageTags(language.languageTag)
        languageState.value = language
    }

    override fun setStepGoal(goal: Int?) {
        stepGoalState.value = goal
        preferences.edit { if (goal == null) remove(STEP_GOAL) else putInt(STEP_GOAL, goal) }
    }

    override fun setNotificationAlertsApplied(applied: Boolean) {
        if (notificationAlertsAppliedState.value == applied) return
        notificationAlertsAppliedState.value = applied
        preferences.edit { putBoolean(NOTIFICATION_ALERTS_APPLIED, applied) }
    }

    override fun setHideEmptyHealthMetrics(hide: Boolean) {
        hideEmptyMetricsState.value = hide
        preferences.edit { putBoolean(HIDE_EMPTY_METRICS, hide) }
    }

    override fun setRemoteScreen(settings: RemoteScreenSettings) {
        remoteScreenState.value = settings
        preferences.edit {
            putString(SCREEN_PRESET, settings.preset?.name)
            putBoolean(SCREEN_ADVANCED, settings.advanced)
            putBoolean(SCREEN_AUDIO, settings.audio)
            putInt(SCREEN_SIZE, settings.maxSize)
            putInt(SCREEN_FPS, settings.maxFps)
            putInt(SCREEN_BIT_RATE, settings.bitRate)
            putBoolean(SCREEN_H265, settings.h265)
            putBoolean(SCREEN_LIMIT_TO_ENCODER, settings.limitToEncoder)
            putString(SCREEN_CODEC_OPTIONS, settings.codecOptions)
            putBoolean(SCREEN_FRAME_STATS, settings.frameStats)
            putBoolean(SCREEN_SMOOTH_PLAYBACK, settings.smoothPlayback)
            putBoolean(SCREEN_UDP, settings.udp)
            putBoolean(SCREEN_UDP_ENCRYPTION, settings.udpEncryption)
        }
    }

    /** The language may also change in system Settings while the app runs. */
    fun refreshLanguage() {
        languageState.value = readLanguage()
    }

    private fun readAppearance() =
        AppearanceSettings(
            themeMode =
                preferences.getString(THEME_MODE, null)?.let { name ->
                    ThemeMode.entries.firstOrNull { it.name == name }
                } ?: ThemeMode.SYSTEM,
            dynamicColor = preferences.getBoolean(DYNAMIC_COLOR, true),
            appIcon = readAppIcon(),
        )

    private fun launcher(icon: AppIconStyle) = ComponentName(context, LAUNCHERS.getValue(icon))

    private fun setLauncher(icon: AppIconStyle, state: Int) =
        packageManager.setComponentEnabledSetting(
            launcher(icon),
            state,
            PackageManager.DONT_KILL_APP,
        )

    /** The other icons are off unless turned on, so the default is whichever isn't. */
    private fun readAppIcon(): AppIconStyle =
        AppIconStyle.entries.firstOrNull { icon ->
            icon != AppIconStyle.WARM_WHITE &&
                packageManager.getComponentEnabledSetting(launcher(icon)) ==
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } ?: AppIconStyle.WARM_WHITE

    private fun readRemoteScreen(): RemoteScreenSettings {
        val defaults = RemoteScreenSettings()
        return RemoteScreenSettings(
            preset =
                preferences.getString(SCREEN_PRESET, null)?.let { name ->
                    ScreenQuality.entries.firstOrNull { it.name == name }
                },
            advanced = preferences.getBoolean(SCREEN_ADVANCED, defaults.advanced),
            audio = preferences.getBoolean(SCREEN_AUDIO, defaults.audio),
            maxSize = preferences.getInt(SCREEN_SIZE, defaults.maxSize),
            maxFps = preferences.getInt(SCREEN_FPS, defaults.maxFps),
            bitRate = preferences.getInt(SCREEN_BIT_RATE, defaults.bitRate),
            h265 = preferences.getBoolean(SCREEN_H265, defaults.h265),
            limitToEncoder =
                preferences.getBoolean(SCREEN_LIMIT_TO_ENCODER, defaults.limitToEncoder),
            codecOptions =
                preferences.getString(SCREEN_CODEC_OPTIONS, null) ?: defaults.codecOptions,
            frameStats = preferences.getBoolean(SCREEN_FRAME_STATS, defaults.frameStats),
            smoothPlayback =
                preferences.getBoolean(SCREEN_SMOOTH_PLAYBACK, defaults.smoothPlayback),
            udp = preferences.getBoolean(SCREEN_UDP, defaults.udp),
            udpEncryption = preferences.getBoolean(SCREEN_UDP_ENCRYPTION, defaults.udpEncryption),
        )
    }

    private fun readLanguage(): AppLanguage {
        val locales = localeManager.applicationLocales
        return AppLanguage.fromTag(if (locales.isEmpty) null else locales[0].toLanguageTag())
    }

    private fun applyThemeMode(mode: ThemeMode) {
        try {
            uiModeManager.setApplicationNightMode(
                when (mode) {
                    ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                    ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                    ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
                }
            )
        } catch (problem: RuntimeException) {
            // The in-app theme still follows the setting; only system surfaces may differ.
            Log.w(TAG, "Applying the night mode failed", problem)
        }
    }

    private companion object {
        const val TAG = "AppPreferences"

        /**
         * The launcher entries in the manifest, one per icon. Their names don't describe the icons,
         * so redrawing one keeps the entry a user chose.
         */
        val LAUNCHERS =
            mapOf(
                AppIconStyle.WARM_WHITE to "dev.ticbox.companion.MainActivity",
                AppIconStyle.NAVY to "dev.ticbox.companion.LauncherIcon2",
                AppIconStyle.MIST to "dev.ticbox.companion.LauncherIcon3",
            )
        const val FILE = "frontend-appearance"
        const val THEME_MODE = "color-mode"
        const val DYNAMIC_COLOR = "dynamic-color"
        const val STEP_GOAL = "step-goal"
        const val HIDE_EMPTY_METRICS = "hide-empty-health-metrics"
        const val NOTIFICATION_ALERTS_APPLIED = "notification-alerts-applied"
        const val SCREEN_PRESET = "remote-screen-preset"
        const val SCREEN_ADVANCED = "remote-screen-advanced"
        const val SCREEN_AUDIO = "remote-screen-audio"
        const val SCREEN_SIZE = "remote-screen-max-size"
        const val SCREEN_FPS = "remote-screen-max-fps"
        const val SCREEN_BIT_RATE = "remote-screen-bit-rate"
        const val SCREEN_H265 = "remote-screen-h265"
        const val SCREEN_LIMIT_TO_ENCODER = "remote-screen-limit-to-encoder"
        const val SCREEN_CODEC_OPTIONS = "remote-screen-codec-options"
        const val SCREEN_FRAME_STATS = "remote-screen-frame-stats"
        const val SCREEN_SMOOTH_PLAYBACK = "remote-screen-smooth-playback"
        const val SCREEN_UDP = "remote-screen-udp"
        const val SCREEN_UDP_ENCRYPTION = "remote-screen-udp-encryption"
    }
}
