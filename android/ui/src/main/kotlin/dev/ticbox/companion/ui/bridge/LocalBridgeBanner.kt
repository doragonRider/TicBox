package dev.ticbox.companion.ui.bridge

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.designsystem.RevealValue
import dev.ticbox.companion.ui.designsystem.StatusBanner
import dev.ticbox.companion.ui.navigation.LocalBridgeRoute

/** Says why a page needs Local Bridge and leads to where it gets installed. */
@Composable
fun LocalBridgeBanner(
    status: LocalBridgeStatus,
    @StringRes needs: Int,
    navigate: (Any) -> Unit,
    modifier: Modifier = Modifier,
) {
    RevealValue(status.takeIf { it.needsSetup }, modifier) { shown ->
        StatusBanner(
            stringResource(needs),
            title =
                stringResource(
                    when (shown) {
                        LocalBridgeStatus.NOT_ALLOWED -> R.string.bridge_not_allowed_title
                        LocalBridgeStatus.UNREACHABLE -> R.string.workouts_unreachable_title
                        else -> R.string.workouts_no_bridge_title
                    }
                ),
            actionLabel = stringResource(R.string.bridge_set_up),
            onAction = { navigate(LocalBridgeRoute) },
        )
    }
}
