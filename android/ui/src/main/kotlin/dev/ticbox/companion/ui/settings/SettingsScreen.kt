package dev.ticbox.companion.ui.settings

import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.ui.AppLanguage
import dev.ticbox.companion.ui.AppPreferences
import dev.ticbox.companion.ui.LocalSystemActions
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.ThemeMode
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.NavigationRow
import dev.ticbox.companion.ui.designsystem.PageColumn
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.RadioRow
import dev.ticbox.companion.ui.designsystem.SwitchRow
import dev.ticbox.companion.ui.navigation.AccountRoute
import dev.ticbox.companion.ui.navigation.AppearanceRoute
import dev.ticbox.companion.ui.navigation.DataMigrationRoute
import dev.ticbox.companion.ui.navigation.DiagnosticsRoute
import dev.ticbox.companion.ui.navigation.GoogleServicesRoute
import dev.ticbox.companion.ui.navigation.LanguageRoute
import dev.ticbox.companion.ui.navigation.PrivacyRoute

/** App-level preferences; independent of the watch session. */
class AppSettingsViewModel(private val preferences: AppPreferences) : ViewModel() {
    val appearance = preferences.appearance
    val language = preferences.language
    val hideEmptyHealthMetrics = preferences.hideEmptyHealthMetrics

    fun setHideEmptyHealthMetrics(hide: Boolean) = preferences.setHideEmptyHealthMetrics(hide)

    fun setThemeMode(mode: ThemeMode) = preferences.setThemeMode(mode)

    fun setDynamicColor(enabled: Boolean) = preferences.setDynamicColor(enabled)

    fun setLanguage(language: AppLanguage) = preferences.setLanguage(language)
}

val AppLanguage.label: Int
    get() =
        when (this) {
            AppLanguage.SYSTEM -> R.string.language_system
            AppLanguage.ENGLISH -> R.string.language_english
            AppLanguage.SIMPLIFIED_CHINESE -> R.string.language_simplified_chinese
            AppLanguage.TRADITIONAL_CHINESE -> R.string.language_traditional_chinese
        }

private val ThemeMode.label: Int
    get() =
        when (this) {
            ThemeMode.SYSTEM -> R.string.appearance_theme_system
            ThemeMode.LIGHT -> R.string.appearance_theme_light
            ThemeMode.DARK -> R.string.appearance_theme_dark
        }

@Composable
fun SettingsScreen(navigate: (Any) -> Unit) {
    val viewModel = companionViewModel { AppSettingsViewModel(it.preferences) }
    val language by viewModel.language.collectAsStateWithLifecycle()
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    val hideEmptyMetrics by viewModel.hideEmptyHealthMetrics.collectAsStateWithLifecycle()
    val actions = LocalSystemActions.current
    PageScaffold(stringResource(R.string.tab_settings)) { padding ->
        PageColumn(padding) {
            ListGroup(title = stringResource(R.string.settings_privacy_group)) {
                NavigationRow(
                    stringResource(R.string.privacy_title),
                    onClick = { navigate(PrivacyRoute) },
                    icon = AppIcons.Privacy,
                    summary = stringResource(R.string.settings_privacy_summary),
                )
            }
            ListGroup(title = stringResource(R.string.settings_cloud_group)) {
                NavigationRow(
                    stringResource(R.string.account_title),
                    onClick = { navigate(AccountRoute) },
                    icon = AppIcons.Account,
                    summary = stringResource(R.string.settings_account_summary),
                )
                NavigationRow(
                    stringResource(R.string.google_title),
                    onClick = { navigate(GoogleServicesRoute) },
                    icon = AppIcons.Login,
                    summary = stringResource(R.string.settings_google_summary),
                )
            }
            ListGroup(title = stringResource(R.string.settings_app_group)) {
                NavigationRow(
                    stringResource(R.string.language_title),
                    onClick = { navigate(LanguageRoute) },
                    icon = AppIcons.Language,
                    summary = stringResource(language.label),
                )
                NavigationRow(
                    stringResource(R.string.appearance_title),
                    onClick = { navigate(AppearanceRoute) },
                    icon = AppIcons.Palette,
                    summary = stringResource(appearance.themeMode.label),
                )
                SwitchRow(
                    stringResource(R.string.settings_hide_empty_metrics),
                    hideEmptyMetrics,
                    viewModel::setHideEmptyHealthMetrics,
                    summary = stringResource(R.string.settings_hide_empty_metrics_summary),
                    icon = AppIcons.Hidden,
                )
                NavigationRow(
                    stringResource(R.string.settings_connection_notification),
                    onClick = actions::openAppNotificationSettings,
                    icon = AppIcons.Notifications,
                    summary = stringResource(R.string.settings_connection_notification_summary),
                    trailingIcon = AppIcons.OpenExternal,
                )
            }
            ListGroup(title = stringResource(R.string.settings_data_group)) {
                NavigationRow(
                    stringResource(R.string.migration_title),
                    onClick = { navigate(DataMigrationRoute) },
                    icon = AppIcons.Database,
                    summary = stringResource(R.string.settings_migration_summary),
                )
            }
            ListGroup(title = stringResource(R.string.settings_advanced_group)) {
                NavigationRow(
                    stringResource(R.string.diagnostics_title),
                    onClick = { navigate(DiagnosticsRoute) },
                    icon = AppIcons.Diagnostics,
                    summary = stringResource(R.string.settings_diagnostics_summary),
                )
            }
            val context = LocalContext.current
            val version =
                remember(context) {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }
            version?.let { FootnoteText(stringResource(R.string.settings_version, it)) }
        }
    }
}

@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val viewModel = companionViewModel { AppSettingsViewModel(it.preferences) }
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    PageScaffold(stringResource(R.string.appearance_title), onBack = onBack) { padding ->
        PageColumn(padding) {
            ListGroup(
                Modifier.selectableGroup(),
                title = stringResource(R.string.appearance_theme),
            ) {
                ThemeMode.entries.forEach { mode ->
                    RadioRow(
                        stringResource(mode.label),
                        selected = appearance.themeMode == mode,
                        onClick = { viewModel.setThemeMode(mode) },
                    )
                }
            }
            ListGroup {
                SwitchRow(
                    stringResource(R.string.appearance_dynamic_color),
                    checked = appearance.dynamicColor,
                    onCheckedChange = viewModel::setDynamicColor,
                    summary = stringResource(R.string.appearance_dynamic_color_summary),
                    icon = AppIcons.Palette,
                )
            }
        }
    }
}

@Composable
fun LanguageScreen(onBack: () -> Unit) {
    val viewModel = companionViewModel { AppSettingsViewModel(it.preferences) }
    val language by viewModel.language.collectAsStateWithLifecycle()
    PageScaffold(stringResource(R.string.language_title), onBack = onBack) { padding ->
        PageColumn(padding) {
            ListGroup(Modifier.selectableGroup()) {
                AppLanguage.entries.forEach { option ->
                    RadioRow(
                        stringResource(option.label),
                        selected = language == option,
                        onClick = { viewModel.setLanguage(option) },
                    )
                }
            }
            FootnoteText(stringResource(R.string.language_note))
        }
    }
}
