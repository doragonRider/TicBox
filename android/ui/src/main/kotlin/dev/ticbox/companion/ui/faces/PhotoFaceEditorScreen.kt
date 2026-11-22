package dev.ticbox.companion.ui.faces

import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.designsystem.BannerTone
import dev.ticbox.companion.ui.designsystem.CaptionText
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.PageColumn
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.RevealValue
import dev.ticbox.companion.ui.designsystem.SectionTitle
import dev.ticbox.companion.ui.designsystem.StatusBanner
import dev.ticbox.companion.ui.designsystem.SwitchRow
import kotlin.math.roundToInt

/** Moving, zooming and scaling a photo for the photo face, and the face's own layout. */
@Composable
fun PhotoFaceEditorScreen(viewModel: PhotoFaceViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val linkReady by viewModel.linkReady.collectAsStateWithLifecycle()
    val battery by viewModel.battery.collectAsStateWithLifecycle()
    val delivered by viewModel.delivered.collectAsStateWithLifecycle()
    val ready = linkReady == true
    val sending = PhotoFaceViewModel.SET in pending
    // Back to the photo face page once the watch has the photo, or when there is none to edit,
    // as after the photo couldn't be read or the app was restarted here. [onDone] leaves only this
    // page, even if it is already on its way out.
    val opened = remember { viewModel.delivered.value }
    LaunchedEffect(delivered) { if (delivered > opened) onDone() }
    val empty = !editor.loading && editor.source == null
    LaunchedEffect(empty) { if (empty) onDone() }
    // The watch's layout until it's changed here; none when the watch hasn't said what it is.
    val layout = editor.edit?.layout ?: state.photo?.settings?.let(PhotoLayout::of)
    PageScaffold(stringResource(R.string.faces_photo_edit_title), onBack = onBack) { padding ->
        PageColumn(padding) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CropView(editor, layout, battery, viewModel::move, viewModel::fillScreen)
                val side = editor.edit?.crop?.side?.roundToInt()
                CaptionText(
                    if (side == null) ""
                    else stringResource(R.string.faces_photo_area, side, WatchScreenPixels)
                )
            }
            RevealValue(editor.edit, spacing = 20.dp) { edit ->
                ScalingChoice(edit, viewModel::edit)
                SharpeningChoice(edit, viewModel::edit)
                layout?.let { LayoutChoice(it, viewModel::edit) }
            }
            Button(
                onClick = viewModel::send,
                enabled = ready && editor.edit != null && !sending,
                modifier = Modifier.fillMaxWidth().testTag("photo_send"),
            ) {
                Text(
                    stringResource(
                        if (sending) R.string.faces_photo_sending else R.string.faces_photo_send
                    )
                )
            }
            FootnoteText(
                stringResource(
                    if (ready) R.string.faces_photo_edit_hint else R.string.faces_photo_disconnected
                )
            )
        }
    }
}

/**
 * The selected square with the watch's round screen marked out and the face's layout on top. While
 * the photo rests, the scaled photo that will be sent fades in over the live one.
 */
@Composable
private fun CropView(
    editor: PhotoEditorState,
    layout: PhotoLayout?,
    battery: Int?,
    onMove: (Float, Float, Float, Float, Float, Float) -> Unit,
    onFill: () -> Unit,
) {
    val source = editor.source
    val edit = editor.edit
    val output = editor.output
    val fresh = output != null && edit != null && output.key == edit.render
    val shown = remember { Animatable(0f) }
    LaunchedEffect(fresh) { if (fresh) shown.animateTo(1f, tween(150)) else shown.snapTo(0f) }
    val outline = MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier.fillMaxWidth()
            .widthIn(max = 360.dp)
            .aspectRatio(1f)
            .clipToBounds()
            .background(Color.Black)
            .testTag("photo_crop")
    ) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(source) {
                    detectTransformGestures { focus, pan, zoom, _ ->
                        onMove(size.width.toFloat(), focus.x, focus.y, pan.x, pan.y, zoom)
                    }
                }
                .pointerInput(source) { detectTapGestures(onDoubleTap = { onFill() }) }
        ) {
            if (source != null && edit != null) {
                val crop = edit.crop
                val perPixel = (size.width / crop.side).toFloat()
                translate((-crop.left * perPixel).toFloat(), (-crop.top * perPixel).toFloat()) {
                    scale(perPixel, perPixel, pivot = Offset.Zero) {
                        drawImage(
                            source.image,
                            dstSize = IntSize(source.bitmap.width, source.bitmap.height),
                            filterQuality = FilterQuality.High,
                        )
                    }
                }
                if (output != null && shown.value > 0f) {
                    drawImage(
                        output.image,
                        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                        alpha = shown.value,
                        filterQuality = FilterQuality.Medium,
                    )
                }
            }
            val corners =
                Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    addOval(Rect(Offset.Zero, size))
                }
            drawPath(corners, Color.Black.copy(alpha = 0.6f))
            val line = 1.dp.toPx()
            drawCircle(outline, radius = size.minDimension / 2 - line / 2, style = Stroke(line))
        }
        if (edit != null && layout != null) {
            PhotoFaceOverlay(layout, battery, Modifier.fillMaxSize())
        }
        if (editor.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
}

@Composable
private fun ScalingChoice(edit: PhotoEdit, onEdit: ((PhotoEdit) -> PhotoEdit) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.faces_photo_scaling))
        val scaling = edit.scaling
        StatusBanner(
            text =
                stringResource(
                    when (scaling) {
                        Scaling.DOWN -> R.string.faces_photo_scaling_down
                        Scaling.EXACT -> R.string.faces_photo_scaling_exact
                        Scaling.UP -> R.string.faces_photo_scaling_up
                    },
                    WatchScreenPixels,
                ),
            tone = if (scaling == Scaling.UP) BannerTone.Warning else BannerTone.Info,
            title =
                if (scaling == Scaling.UP) stringResource(R.string.faces_photo_scaling_up_title)
                else null,
        )
        Choices(ResampleFilter.entries, edit.filter, { it.title }, { "filter_" + it.name }) { filter
            ->
            onEdit {
                if (it.scaling == Scaling.UP) it.copy(up = filter) else it.copy(down = filter)
            }
        }
    }
}

@Composable
private fun SharpeningChoice(edit: PhotoEdit, onEdit: ((PhotoEdit) -> PhotoEdit) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.faces_photo_sharpening))
        Choices(Sharpening.entries, edit.sharpening, { it.title }, { "sharpen_" + it.name }) {
            sharpening ->
            onEdit { it.copy(sharpening = sharpening) }
        }
        FootnoteText(stringResource(R.string.faces_photo_sharpening_note))
    }
}

@Composable
private fun LayoutChoice(layout: PhotoLayout, onEdit: ((PhotoEdit) -> PhotoEdit) -> Unit) {
    fun change(next: PhotoLayout) = onEdit { it.copy(layout = next) }
    ListGroup(title = stringResource(R.string.faces_photo_layout)) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.faces_photo_time_place),
                style = MaterialTheme.typography.bodyLarge,
            )
            Choices(TimePlace.entries, layout.time, { it.title }, { "time_" + it.name }) {
                change(layout.copy(time = it))
            }
        }
        SwitchRow(
            stringResource(R.string.faces_photo_show_date),
            layout.date,
            { change(layout.copy(date = it)) },
            summary = stringResource(R.string.faces_photo_show_date_summary),
        )
        SwitchRow(
            stringResource(R.string.faces_photo_show_battery),
            layout.battery,
            { change(layout.copy(battery = it)) },
            summary = stringResource(R.string.faces_photo_show_battery_summary),
        )
    }
}

/** One choice out of a few, without check marks so that longer names still fit on a phone. */
@Composable
private fun <T> Choices(
    options: List<T>,
    selected: T,
    title: (T) -> Int,
    tag: (T) -> String,
    onSelect: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
                modifier = Modifier.testTag(tag(option)),
            ) {
                Text(stringResource(title(option)), maxLines = 1)
            }
        }
    }
}

private val ResampleFilter.title: Int
    @StringRes
    get() =
        when (this) {
            ResampleFilter.BILINEAR -> R.string.faces_photo_filter_bilinear
            ResampleFilter.BICUBIC -> R.string.faces_photo_filter_bicubic
            ResampleFilter.LANCZOS3 -> R.string.faces_photo_filter_lanczos
        }

private val Sharpening.title: Int
    @StringRes
    get() =
        when (this) {
            Sharpening.OFF -> R.string.faces_photo_sharpening_off
            Sharpening.LIGHT -> R.string.faces_photo_sharpening_light
            Sharpening.MEDIUM -> R.string.faces_photo_sharpening_medium
            Sharpening.STRONG -> R.string.faces_photo_sharpening_strong
        }
