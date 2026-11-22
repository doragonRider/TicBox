package dev.ticbox.companion.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.ui.AppIconStyle
import dev.ticbox.companion.ui.AppLanguage
import dev.ticbox.companion.ui.AppPreferences
import dev.ticbox.companion.ui.FaceShortcut
import dev.ticbox.companion.ui.LocalSystemActions
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.ThemeMode
import dev.ticbox.companion.ui.common.UserMessages
import dev.ticbox.companion.ui.common.uiText
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.AppIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.FadeSwitch
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
class AppSettingsViewModel(
    private val preferences: AppPreferences,
    private val faceShortcut: FaceShortcut = FaceShortcut.UNAVAILABLE,
    private val messages: UserMessages? = null,
) : ViewModel() {
    val appearance = preferences.appearance
    val face = faceShortcut.face
    val language = preferences.language
    val hideEmptyHealthMetrics = preferences.hideEmptyHealthMetrics

    fun setHideEmptyHealthMetrics(hide: Boolean) = preferences.setHideEmptyHealthMetrics(hide)

    fun setThemeMode(mode: ThemeMode) = preferences.setThemeMode(mode)

    fun setDynamicColor(enabled: Boolean) = preferences.setDynamicColor(enabled)

    fun setAppIcon(icon: AppIconStyle) = preferences.setAppIcon(icon)

    fun pinFaceShortcut() {
        if (!faceShortcut.pin()) messages?.show(uiText(R.string.app_icon_face_unsupported))
    }

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

private val AppIconStyle.label: Int
    get() =
        when (this) {
            AppIconStyle.WARM_WHITE -> R.string.app_icon_warm_white
            AppIconStyle.NAVY -> R.string.app_icon_navy
            AppIconStyle.MIST -> R.string.app_icon_mist
        }

private val AppIconStyle.foreground: Int
    get() =
        when (this) {
            AppIconStyle.WARM_WHITE -> R.drawable.ic_launcher_foreground
            AppIconStyle.NAVY -> R.drawable.ic_launcher_foreground_navy
            AppIconStyle.MIST -> R.drawable.ic_launcher_foreground_mist
        }

private val AppIconStyle.background: Int
    get() =
        when (this) {
            AppIconStyle.WARM_WHITE -> R.color.ic_launcher_background
            AppIconStyle.NAVY -> R.color.ic_launcher_background_navy
            AppIconStyle.MIST -> R.color.ic_launcher_background_mist
        }

/** A launcher icon the way a round launcher shows it: the middle two thirds of its layers. */
@Composable
private fun LauncherIconPreview(style: AppIconStyle, size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(colorResource(style.background)),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(style.foreground), null, Modifier.requiredSize(size * 1.5f))
    }
}

@Composable
private fun AppIconChoices(selected: AppIconStyle, onSelect: (AppIconStyle) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 8.dp, vertical = 4.dp)) {
        AppIconStyle.entries.forEach { style ->
            val chosen = style == selected
            val ring by
                animateColorAsState(
                    if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent,
                    label = "app icon ring",
                )
            Column(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .selectable(chosen, role = Role.RadioButton, onClick = { onSelect(style) })
                    .padding(vertical = 8.dp)
                    .testTag("app_icon_${style.name}"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.border(3.dp, ring, CircleShape).padding(5.dp)) {
                    LauncherIconPreview(style, 56.dp)
                }
                Text(
                    stringResource(style.label),
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Pins a shortcut with the watch's current face; waits until a real watch has shown one. */
@Composable
private fun FaceShortcutRow(face: ImageBitmap?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val enabled = face != null
    val text = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.38f)
    val secondary = if (enabled) colors.onSurfaceVariant else colors.onSurface.copy(alpha = 0.38f)
    ListItem(
        headlineContent = { Text(stringResource(R.string.app_icon_face), color = text) },
        supportingContent = {
            FadeSwitch(
                stringResource(
                    if (enabled) R.string.app_icon_face_summary else R.string.app_icon_face_waiting
                )
            ) {
                Text(it, color = secondary)
            }
        },
        leadingContent = {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(colors.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (face != null) {
                    Image(face, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    AppIcon(AppIcons.WatchFaces, contentDescription = null, tint = secondary)
                }
            }
        },
        trailingContent = { AppIcon(AppIcons.Add, contentDescription = null, tint = secondary) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier =
            Modifier.clickable(enabled = enabled, onClick = onClick).testTag("face_shortcut"),
    )
}

@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val viewModel = companionViewModel {
        AppSettingsViewModel(it.preferences, it.faceShortcut, it.messages)
    }
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()
    val face by viewModel.face.collectAsStateWithLifecycle()
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
            ListGroup(title = stringResource(R.string.appearance_app_icon)) {
                AppIconChoices(appearance.appIcon, viewModel::setAppIcon)
                FaceShortcutRow(face, viewModel::pinFaceShortcut)
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
