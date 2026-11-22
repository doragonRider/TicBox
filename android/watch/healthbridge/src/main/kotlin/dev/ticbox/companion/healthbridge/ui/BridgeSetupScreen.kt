package dev.ticbox.companion.healthbridge.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ticbox.companion.healthbridge.R

/** Where asking for root currently stands, while the switch itself is off. */
internal enum class RootRequest {
    NONE,
    PENDING,
    DENIED,
}

@Composable
internal fun BridgeSetupScreen(
    readingEnabled: Boolean,
    hasBluetoothPermission: Boolean,
    rootEnabled: Boolean,
    rootRequest: RootRequest,
    onReadingChange: (Boolean) -> Unit,
    onRequestBluetooth: () -> Unit,
    onRootChange: (Boolean) -> Unit,
    onExport: () -> Unit,
) {
    BridgeScreen {
        row { Title(stringResource(R.string.app_name)) }
        row {
            Toggle(
                label = stringResource(R.string.bridge_enable),
                summary = stringResource(R.string.bridge_enable_summary),
                checked = readingEnabled,
                onCheckedChange = onReadingChange,
            )
        }
        if (!hasBluetoothPermission) {
            row { Action(stringResource(R.string.bridge_permission), onRequestBluetooth) }
        }
        row { Note(stringResource(R.string.bridge_explanation)) }
        row {
            Toggle(
                label = stringResource(R.string.bridge_root_enable),
                summary =
                    stringResource(
                        when {
                            rootRequest == RootRequest.PENDING -> R.string.bridge_root_requesting
                            rootEnabled -> R.string.bridge_root_granted
                            rootRequest == RootRequest.DENIED -> R.string.bridge_root_denied
                            else -> R.string.bridge_root_off
                        }
                    ),
                checked = rootEnabled,
                onCheckedChange = onRootChange,
                enabled = rootRequest != RootRequest.PENDING,
            )
        }
        row { Note(stringResource(R.string.bridge_root_explanation)) }
        row { Action(stringResource(R.string.export_open), onExport) }
    }
}
