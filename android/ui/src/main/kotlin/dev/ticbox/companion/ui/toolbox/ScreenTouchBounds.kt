package dev.ticbox.companion.ui.toolbox

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.PathHitTester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection

/** Uses the video's clipping outline, including circular faces and rounded rectangular corners. */
internal class ScreenTouchBounds(
    private val shape: Shape,
    private val density: Density,
    private val layoutDirection: LayoutDirection,
) {
    private var measuredSize = IntSize.Zero
    private var outline: Outline? = null
    private var pathHitTester: PathHitTester? = null

    fun contains(position: Offset, size: IntSize): Boolean {
        if (
            !position.x.isFinite() ||
                !position.y.isFinite() ||
                position.x < 0f ||
                position.y < 0f ||
                position.x >= size.width ||
                position.y >= size.height
        )
            return false
        if (measuredSize != size) {
            val next =
                shape.createOutline(
                    Size(size.width.toFloat(), size.height.toFloat()),
                    layoutDirection,
                    density,
                )
            outline = next
            measuredSize = size
            pathHitTester =
                if (next is Outline.Generic) PathHitTester().apply { updatePath(next.path) }
                else null
        }
        return when (val current = checkNotNull(outline)) {
            is Outline.Rectangle -> current.rect.contains(position)
            is Outline.Rounded -> current.roundRect.contains(position)
            is Outline.Generic -> checkNotNull(pathHitTester).contains(position)
        }
    }
}
