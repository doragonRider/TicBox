package dev.ticbox.companion.ui.toolbox

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.currentLocale
import dev.ticbox.companion.ui.common.formatNumber
import dev.ticbox.companion.ui.designsystem.AppIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.FadingText
import dev.ticbox.companion.ui.designsystem.GrowingGaps

/** One numbered thing for the user to do in a setup guide. */
@Composable
internal fun StepCard(
    number: Int,
    title: String,
    modifier: Modifier = Modifier,
    done: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                Modifier.size(28.dp),
                shape = CircleShape,
                color =
                    animateColorAsState(
                            if (done) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.secondaryContainer,
                            label = "step_badge",
                        )
                        .value,
            ) {
                Crossfade(done, label = "step_mark") { finished ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (finished) {
                            AppIcon(
                                AppIcons.Check,
                                stringResource(R.string.toolbox_step_done),
                                Modifier.size(18.dp),
                            )
                        } else {
                            Text(
                                formatNumber(number, currentLocale()),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
            Column(
                Modifier.weight(1f).animateContentSize(),
                verticalArrangement = GrowingGaps(8.dp),
            ) {
                Text(
                    title,
                    Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                )
                content()
            }
        }
    }
}

@Composable
internal fun StepBody(text: String) {
    FadingText(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
