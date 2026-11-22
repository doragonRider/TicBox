package dev.ticbox.companion.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.api.CloudCapability
import dev.ticbox.companion.api.CloudPrivacySettings
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.EmptyState
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.PageColumn
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.SwitchRow
import kotlinx.coroutines.flow.StateFlow

class PrivacyViewModel(dependencies: CompanionDependencies) : SessionViewModel(dependencies) {
    val state: StateFlow<CloudPrivacySettings?> =
        sessionState(
            current = { it.services.privacy.state.value },
            updates = { it.services.privacy.state },
        )

    fun setEnabled(capability: CloudCapability, enabled: Boolean) =
        command(capability) { session ->
            session.services.privacy.setEnabled(capability, enabled)
            null
        }
}

private val CloudCapability.title: Int
    get() =
        when (this) {
            CloudCapability.MOBVOI_CLOUD -> R.string.privacy_mobvoi_cloud
            CloudCapability.GOOGLE_ACCOUNT_TRANSFER -> R.string.privacy_google_account
            CloudCapability.GOOGLE_CLOUD_SYNC -> R.string.privacy_google_sync
            CloudCapability.GOOGLE_LOCATION_SERVICES -> R.string.privacy_google_location
            CloudCapability.VENDOR_DIAGNOSTICS -> R.string.privacy_vendor_diagnostics
            CloudCapability.WATCH_INTERNET_PROXY -> R.string.privacy_internet_proxy
        }

private val CloudCapability.summary: Int
    get() =
        when (this) {
            CloudCapability.MOBVOI_CLOUD -> R.string.privacy_mobvoi_cloud_summary
            CloudCapability.GOOGLE_ACCOUNT_TRANSFER -> R.string.privacy_google_account_summary
            CloudCapability.GOOGLE_CLOUD_SYNC -> R.string.privacy_google_sync_summary
            CloudCapability.GOOGLE_LOCATION_SERVICES -> R.string.privacy_google_location_summary
            CloudCapability.VENDOR_DIAGNOSTICS -> R.string.privacy_vendor_diagnostics_summary
            CloudCapability.WATCH_INTERNET_PROXY -> R.string.privacy_internet_proxy_summary
        }

@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val viewModel = companionViewModel { PrivacyViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    var confirming by rememberSaveable { mutableStateOf<CloudCapability?>(null) }
    PageScaffold(stringResource(R.string.privacy_title), onBack = onBack) { padding ->
        val settings = state
        if (settings == null) {
            EmptyState(
                AppIcons.Privacy,
                stringResource(R.string.no_watch_title),
                stringResource(R.string.no_watch_body),
                Modifier.padding(padding),
            )
            return@PageScaffold
        }
        PageColumn(padding) {
            FootnoteText(stringResource(R.string.privacy_intro))
            ListGroup(title = stringResource(R.string.privacy_services)) {
                CloudCapability.entries.forEach { capability ->
                    SwitchRow(
                        stringResource(capability.title),
                        checked = settings.allows(capability),
                        onCheckedChange = { enabled ->
                            if (enabled) confirming = capability
                            else viewModel.setEnabled(capability, false)
                        },
                        summary = stringResource(capability.summary),
                        enabled = capability !in pending,
                    )
                }
            }
            FootnoteText(stringResource(R.string.privacy_footer))
        }
    }
    confirming?.let { capability ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = {
                Text(
                    stringResource(R.string.privacy_confirm_title, stringResource(capability.title))
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(capability.summary))
                    Text(stringResource(R.string.privacy_confirm_body))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = null
                        viewModel.setEnabled(capability, true)
                    }
                ) {
                    Text(stringResource(R.string.action_turn_on))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
