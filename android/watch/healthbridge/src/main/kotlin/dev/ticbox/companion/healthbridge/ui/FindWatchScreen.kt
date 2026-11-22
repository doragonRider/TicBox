package dev.ticbox.companion.healthbridge.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.ticbox.companion.healthbridge.R

/** Someone looking for a ringing watch needs one big, obvious way to stop it. */
@Composable
internal fun FindWatchScreen(@StringRes status: Int, onStop: () -> Unit) {
    BridgeScreen(
        edgeButton = {
            EdgeButton(onClick = onStop, buttonSize = EdgeButtonSize.Large) {
                Text(stringResource(R.string.find_watch_stop))
            }
        }
    ) {
        row { Title(stringResource(R.string.find_watch_title)) }
        row { Note(stringResource(status), color = MaterialTheme.colorScheme.onSurface) }
    }
}
