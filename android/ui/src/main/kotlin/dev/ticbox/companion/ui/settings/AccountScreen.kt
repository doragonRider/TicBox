package dev.ticbox.companion.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.api.CloudCapability
import dev.ticbox.companion.api.MobvoiAccountPhase
import dev.ticbox.companion.api.MobvoiAccountProfile
import dev.ticbox.companion.api.MobvoiAccountState
import dev.ticbox.companion.api.MobvoiCloudRegion
import dev.ticbox.companion.api.MobvoiIdentifierKind
import dev.ticbox.companion.api.MobvoiLoginIdentifier
import dev.ticbox.companion.api.MobvoiProfileUpdate
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.common.currentLocale
import dev.ticbox.companion.ui.common.uiText
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.BannerTone
import dev.ticbox.companion.ui.designsystem.BusyBar
import dev.ticbox.companion.ui.designsystem.EmptyState
import dev.ticbox.companion.ui.designsystem.FadeSwitch
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.GrowingGaps
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.NavigationRow
import dev.ticbox.companion.ui.designsystem.PageColumn
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.RadioRow
import dev.ticbox.companion.ui.designsystem.Reveal
import dev.ticbox.companion.ui.designsystem.StatusBanner
import dev.ticbox.companion.ui.designsystem.ValueRow
import dev.ticbox.companion.ui.navigation.CloudHealthRoute
import dev.ticbox.companion.ui.navigation.PrivacyRoute
import java.time.ZoneId
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

data class AccountUiState(val cloudAllowed: Boolean, val account: MobvoiAccountState)

class AccountViewModel(dependencies: CompanionDependencies) : SessionViewModel(dependencies) {
    val state: StateFlow<AccountUiState?> =
        sessionState(
            current = {
                AccountUiState(
                    it.services.privacy.state.value.allows(CloudCapability.MOBVOI_CLOUD),
                    it.services.account.state.value,
                )
            },
            updates = { session ->
                combine(session.services.privacy.state, session.services.account.state) {
                    privacy,
                    account ->
                    AccountUiState(privacy.allows(CloudCapability.MOBVOI_CLOUD), account)
                }
            },
        )

    /** The password stays out of saved state, logs and messages, and is wiped after use. */
    fun loginWithPassword(identifier: String, password: CharArray, region: MobvoiCloudRegion) =
        command(LOGIN) {
            try {
                it.services.account.loginPassword(login(identifier), password, region)
            } finally {
                password.fill(' ')
            }
            null
        }

    fun requestCode(phone: String, language: String) =
        command(CODE) {
            it.services.account.requestCaptcha(
                MobvoiLoginIdentifier(MobvoiIdentifierKind.PHONE, phone),
                language,
            )
            uiText(R.string.account_code_sent)
        }

    fun loginWithCode(phone: String, code: String, region: MobvoiCloudRegion) =
        command(LOGIN) {
            it.services.account.loginCaptcha(phone, code, region)
            null
        }

    fun refreshProfile() =
        command(REFRESH) {
            it.services.account.refreshProfile()
            null
        }

    fun saveNickname(nickname: String) =
        command(NICKNAME) {
            it.services.account.updateProfile(MobvoiProfileUpdate(nickname = nickname))
            null
        }

    fun saveTimeZone() =
        command(TIME_ZONE) {
            it.services.account.saveTimeZone(ZoneId.systemDefault().id)
            uiText(R.string.account_time_zone_saved)
        }

    fun logout(clearCachedHealth: Boolean) =
        command(LOGOUT) {
            it.services.account.logout(clearCachedHealth = clearCachedHealth)
            null
        }

    private fun login(identifier: String) =
        MobvoiLoginIdentifier(
            if ('@' in identifier) MobvoiIdentifierKind.EMAIL else MobvoiIdentifierKind.PHONE,
            identifier,
        )

    companion object {
        const val LOGIN = "account_login"
        const val CODE = "account_code"
        const val REFRESH = "account_refresh"
        const val NICKNAME = "account_nickname"
        const val TIME_ZONE = "account_time_zone"
        const val LOGOUT = "account_logout"
    }
}

@Composable
fun AccountScreen(onBack: () -> Unit, navigate: (Any) -> Unit) {
    val viewModel = companionViewModel { AccountViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    PageScaffold(stringResource(R.string.account_title), onBack = onBack) { padding ->
        val current = state
        if (current == null) {
            EmptyState(
                AppIcons.Account,
                stringResource(R.string.no_watch_title),
                stringResource(R.string.no_watch_body),
                Modifier.padding(padding),
            )
            return@PageScaffold
        }
        val account = current.account
        PageColumn(padding) {
            Reveal(!current.cloudAllowed) {
                StatusBanner(
                    stringResource(R.string.account_cloud_disabled),
                    tone = BannerTone.Warning,
                    actionLabel = stringResource(R.string.account_open_privacy),
                    onAction = { navigate(PrivacyRoute) },
                )
            }
            Reveal(account.watchLoginRequested && account.phase != MobvoiAccountPhase.SIGNED_IN) {
                StatusBanner(stringResource(R.string.account_watch_requested))
            }
            Reveal(account.phase == MobvoiAccountPhase.EXPIRED) {
                StatusBanner(stringResource(R.string.account_expired), tone = BannerTone.Warning)
            }
            BusyBar(
                account.phase == MobvoiAccountPhase.RESTORING ||
                    account.phase == MobvoiAccountPhase.AUTHENTICATING
            )
            FadeSwitch(
                account.profile.takeIf { account.phase == MobvoiAccountPhase.SIGNED_IN },
                key = { it != null },
            ) { profile ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = GrowingGaps(20.dp)) {
                    if (profile != null) {
                        Profile(profile, viewModel, pending, navigate)
                    } else {
                        LoginForm(
                            enabled =
                                current.cloudAllowed &&
                                    account.phase != MobvoiAccountPhase.CLOSED &&
                                    AccountViewModel.LOGIN !in pending,
                            codeEnabled = current.cloudAllowed && AccountViewModel.CODE !in pending,
                            viewModel = viewModel,
                        )
                        // Data cached by an earlier sign-in stays readable and removable without
                        // one.
                        CloudHealthEntry(navigate)
                    }
                }
            }
            FootnoteText(stringResource(R.string.account_note))
        }
    }
}

@Composable
private fun CloudHealthEntry(navigate: (Any) -> Unit) {
    ListGroup {
        NavigationRow(
            stringResource(R.string.cloud_title),
            onClick = { navigate(CloudHealthRoute) },
            icon = AppIcons.Database,
            summary = stringResource(R.string.account_cloud_summary),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginForm(enabled: Boolean, codeEnabled: Boolean, viewModel: AccountViewModel) {
    var region by rememberSaveable { mutableStateOf(MobvoiCloudRegion.GLOBAL) }
    var useCode by rememberSaveable { mutableStateOf(false) }
    var identifier by rememberSaveable { mutableStateOf("") }
    // Deliberately not saveable: a password must not reach the saved instance state.
    var password by remember { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    val locale = currentLocale()
    val login = {
        val name = identifier.trim()
        when {
            !enabled || name.isEmpty() -> Unit
            useCode -> if (code.isNotBlank()) viewModel.loginWithCode(name, code.trim(), region)
            password.isNotEmpty() -> {
                viewModel.loginWithPassword(name, password.toCharArray(), region)
                password = ""
            }
        }
    }
    ListGroup(Modifier.selectableGroup(), title = stringResource(R.string.account_region)) {
        RadioRow(
            stringResource(R.string.account_region_global),
            selected = region == MobvoiCloudRegion.GLOBAL,
            onClick = { region = MobvoiCloudRegion.GLOBAL },
        )
        RadioRow(
            stringResource(R.string.account_region_china),
            selected = region == MobvoiCloudRegion.CHINA,
            onClick = { region = MobvoiCloudRegion.CHINA },
        )
    }
    ListGroup(title = stringResource(R.string.account_sign_in)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(false, true).forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = useCode == option,
                        onClick = { useCode = option },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                    ) {
                        Text(
                            stringResource(
                                if (option) R.string.account_method_code
                                else R.string.account_method_password
                            )
                        )
                    }
                }
            }
            OutlinedTextField(
                identifier,
                { identifier = it },
                Modifier.fillMaxWidth(),
                label = {
                    Text(
                        stringResource(
                            if (useCode) R.string.account_phone else R.string.account_identifier
                        )
                    )
                },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = if (useCode) KeyboardType.Phone else KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    ),
            )
            FadeSwitch(useCode) { usingCode ->
                if (usingCode) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            code,
                            { code = it },
                            Modifier.weight(1f),
                            label = { Text(stringResource(R.string.account_code)) },
                            singleLine = true,
                            keyboardOptions =
                                KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done,
                                ),
                            keyboardActions = KeyboardActions(onDone = { login() }),
                        )
                        TextButton(
                            onClick = {
                                viewModel.requestCode(identifier.trim(), locale.toLanguageTag())
                            },
                            enabled = codeEnabled && identifier.isNotBlank(),
                        ) {
                            Text(stringResource(R.string.account_send_code))
                        }
                    }
                } else {
                    OutlinedTextField(
                        password,
                        { password = it },
                        Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.account_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                        keyboardActions = KeyboardActions(onDone = { login() }),
                    )
                }
            }
            Button(
                onClick = login,
                enabled =
                    enabled &&
                        identifier.isNotBlank() &&
                        (if (useCode) code.isNotBlank() else password.isNotEmpty()),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.account_sign_in))
            }
        }
    }
}

@Composable
private fun Profile(
    profile: MobvoiAccountProfile,
    viewModel: AccountViewModel,
    pending: Set<Any>,
    navigate: (Any) -> Unit,
) {
    var editingNickname by rememberSaveable { mutableStateOf(false) }
    var loggingOut by rememberSaveable { mutableStateOf(false) }
    ListGroup(title = stringResource(R.string.account_profile)) {
        NavigationRow(
            stringResource(R.string.account_nickname),
            onClick = { editingNickname = true },
            summary = profile.nickname ?: stringResource(R.string.account_not_set),
            enabled = AccountViewModel.NICKNAME !in pending,
        )
        profile.email?.let { ValueRow(stringResource(R.string.account_email), it) }
        profile.phone?.let { ValueRow(stringResource(R.string.account_phone), it) }
        ValueRow(
            stringResource(R.string.account_region),
            stringResource(
                if (profile.owner.region == MobvoiCloudRegion.CHINA) {
                    R.string.account_region_china
                } else {
                    R.string.account_region_global
                }
            ),
        )
    }
    CloudHealthEntry(navigate)
    ListGroup {
        NavigationRow(
            stringResource(R.string.account_refresh),
            onClick = viewModel::refreshProfile,
            icon = AppIcons.Refresh,
            enabled = AccountViewModel.REFRESH !in pending,
            trailingIcon = null,
        )
        NavigationRow(
            stringResource(R.string.account_save_time_zone),
            onClick = viewModel::saveTimeZone,
            icon = AppIcons.Language,
            enabled = AccountViewModel.TIME_ZONE !in pending,
            trailingIcon = null,
        )
        NavigationRow(
            stringResource(R.string.account_sign_out),
            onClick = { loggingOut = true },
            icon = AppIcons.Logout,
            enabled = AccountViewModel.LOGOUT !in pending,
        )
    }
    if (editingNickname) {
        NicknameDialog(
            profile.nickname.orEmpty(),
            onDismiss = { editingNickname = false },
            onSave = {
                editingNickname = false
                viewModel.saveNickname(it)
            },
        )
    }
    if (loggingOut) {
        LogoutDialog(
            onDismiss = { loggingOut = false },
            onConfirm = { clearCache ->
                loggingOut = false
                viewModel.logout(clearCache)
            },
        )
    }
}

@Composable
private fun NicknameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var nickname by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_nickname)) },
        text = {
            OutlinedTextField(
                nickname,
                { nickname = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(nickname.trim()) }, enabled = nickname.isNotBlank()) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun LogoutDialog(onDismiss: () -> Unit, onConfirm: (clearCache: Boolean) -> Unit) {
    var clearCache by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_sign_out)) },
        text = {
            Row(
                Modifier.fillMaxWidth().toggleable(clearCache, role = Role.Checkbox) {
                    clearCache = it
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(clearCache, onCheckedChange = null, Modifier.padding(end = 12.dp))
                Text(stringResource(R.string.account_clear_cache))
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(clearCache) }) {
                Text(stringResource(R.string.account_sign_out))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
