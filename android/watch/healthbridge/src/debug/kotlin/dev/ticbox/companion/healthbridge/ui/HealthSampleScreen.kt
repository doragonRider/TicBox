package dev.ticbox.companion.healthbridge.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.MaterialTheme
import dev.ticbox.companion.healthbridge.R

@Composable
internal fun HealthSampleScreen(
    offlineConfirmed: Boolean,
    running: Boolean,
    status: String?,
    onOfflineChange: (Boolean) -> Unit,
    onSessionProbe: () -> Unit,
    onSportProbe: () -> Unit,
    onRawSample: () -> Unit,
    onExport: () -> Unit,
) {
    val canRead = offlineConfirmed && !running
    BridgeScreen {
        row { Title(stringResource(R.string.sample_title)) }
        row { Note(stringResource(R.string.sample_explanation)) }
        row {
            Check(
                label = stringResource(R.string.sample_offline),
                checked = offlineConfirmed,
                onCheckedChange = onOfflineChange,
                enabled = !running,
            )
        }
        row { Action(stringResource(R.string.session_probe_start), onSessionProbe, canRead) }
        row { Action(stringResource(R.string.sport_probe_start), onSportProbe, canRead) }
        row { Action(stringResource(R.string.sample_start), onRawSample, canRead) }
        status?.let { text ->
            row(key = "status") { Note(text, color = MaterialTheme.colorScheme.onSurface) }
        }
        row { Action(stringResource(R.string.export_open), onExport) }
    }
}
