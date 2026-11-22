package dev.ticbox.companion.ui.faces

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.ticbox.companion.api.ConnectionPhase
import dev.ticbox.companion.api.WatchFaceOutcome
import dev.ticbox.companion.api.WatchFacePhoto
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.common.uiText
import dev.ticbox.companion.ui.designsystem.AppIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.FadeSwitch
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.GrowingGaps
import dev.ticbox.companion.ui.designsystem.PageColumn
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.Reveal
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The supported watches (TicWatch Pro 5, Pro 5 Enduro and Atlas) all have 466 × 466 screens, so
 * photos are sent at that size.
 */
const val WatchScreenPixels = 466

/** Larger photos are read at this length on their long side, which keeps one under 64 MiB. */
private const val MaxPhotoPixels = 4096

private const val PhotoQuality = 95

data class PhotoFaceUiState(
    val loading: Boolean = true,
    val photo: WatchFacePhoto? = null,
    val image: ImageBitmap? = null,
)

/** A photo being edited, read once and kept while the photo face pages are open. */
class PhotoSource(val bitmap: Bitmap) {
    val image: ImageBitmap = bitmap.asImageBitmap()
}

/** What the scaled pixels depend on. */
data class RenderKey(val crop: PhotoCrop, val filter: ResampleFilter, val sharpening: Sharpening)

/** [layout] stays null until it's changed here, so the watch's own settings are kept until then. */
data class PhotoEdit(
    val crop: PhotoCrop,
    val layout: PhotoLayout? = null,
    val down: ResampleFilter = ResampleFilter.LANCZOS3,
    val up: ResampleFilter = ResampleFilter.BICUBIC,
    val sharpening: Sharpening = Sharpening.OFF,
) {
    val scaling: Scaling
        get() = Scaling.of(crop.side, WatchScreenPixels)

    val filter: ResampleFilter
        get() = if (scaling == Scaling.UP) up else down

    val render: RenderKey
        get() = RenderKey(crop, filter, sharpening)
}

/** The photo as it will be sent, at the watch's size. */
class PhotoOutput(val key: RenderKey, val pixels: IntArray, val image: ImageBitmap)

data class PhotoEditorState(
    val loading: Boolean = false,
    val source: PhotoSource? = null,
    val edit: PhotoEdit? = null,
    val output: PhotoOutput? = null,
)

/** Shared by the photo face page and its editor. */
class PhotoFaceViewModel(dependencies: CompanionDependencies) : SessionViewModel(dependencies) {
    private val current = MutableStateFlow(PhotoFaceUiState())
    val state = current.asStateFlow()

    private val editing = MutableStateFlow(PhotoEditorState())
    val editor = editing.asStateFlow()

    private val sent = MutableStateFlow(0)

    /** Counts photos the watch confirmed, so the editor can close after one. */
    val delivered = sent.asStateFlow()

    val linkReady: StateFlow<Boolean?> =
        sessionState(
            current = { it.client.state.value.connection.phase == ConnectionPhase.LINK_READY },
            updates = { session ->
                session.client.state.map { it.connection.phase == ConnectionPhase.LINK_READY }
            },
        )

    val battery: StateFlow<Int?> =
        sessionState(
            current = { it.client.state.value.battery?.value },
            updates = { session -> session.client.state.map { it.battery?.value } },
        )

    init {
        // The scaled photo follows the edit once it rests for a moment; a newer edit cancels it.
        viewModelScope.launch {
            editing
                .map { it.source to it.edit?.render }
                .distinctUntilChanged()
                .collectLatest { (source, key) ->
                    if (source == null || key == null || editing.value.output?.key == key) {
                        return@collectLatest
                    }
                    delay(RenderDelayMillis)
                    val output = render(source, key)
                    editing.update { if (it.source === source) it.copy(output = output) else it }
                }
        }
    }

    /** Asks the watch for its photo face settings and image. */
    fun load() {
        val faces = session.value?.services?.watchFaces ?: return
        viewModelScope.launch {
            current.value = current.value.copy(loading = true)
            current.value =
                try {
                    val photo = faces.queryPhoto()
                    PhotoFaceUiState(false, photo, decode(photo.image?.bytes))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    PhotoFaceUiState(loading = false)
                }
        }
    }

    /** Starts editing a photo that [read] decodes. */
    fun open(read: suspend () -> Bitmap) {
        editing.value = PhotoEditorState(loading = true)
        viewModelScope.launch {
            val bitmap =
                try {
                    withContext(Dispatchers.IO) { read() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    messages.show(uiText(R.string.faces_photo_unreadable))
                    editing.value = PhotoEditorState()
                    return@launch
                }
            val crop = PhotoCrop.fill(bitmap.width, bitmap.height)
            editing.value = PhotoEditorState(source = PhotoSource(bitmap), edit = PhotoEdit(crop))
        }
    }

    /** Opens the photo edited last on this page, or else the one on the watch. */
    fun editCurrent() {
        if (editing.value.source != null || editing.value.loading) return
        val bytes = current.value.photo?.image?.bytes ?: return
        open { decodePhoto(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) }
    }

    fun edit(change: (PhotoEdit) -> PhotoEdit) {
        editing.update { state -> state.copy(edit = state.edit?.let(change)) }
    }

    /** A gesture on the crop view, [view] pixels wide. */
    fun move(view: Float, focusX: Float, focusY: Float, panX: Float, panY: Float, zoom: Float) {
        val bitmap = editing.value.source?.bitmap ?: return
        edit { edit ->
            val crop = edit.crop.moved(view, focusX, focusY, panX, panY, zoom)
            edit.copy(crop = crop.within(bitmap.width, bitmap.height, WatchScreenPixels))
        }
    }

    fun fillScreen() {
        val bitmap = editing.value.source?.bitmap ?: return
        edit { it.copy(crop = PhotoCrop.fill(bitmap.width, bitmap.height)) }
    }

    /** Sends the edited photo with its layout. Without the watch's settings, only the photo. */
    fun send() {
        val state = editing.value
        val source = state.source ?: return
        val edit = state.edit ?: return
        val settings = current.value.photo?.settings?.let { edit.layout?.applyTo(it) ?: it }
        command(SET) { session ->
            val output =
                state.output?.takeIf { it.key == edit.render } ?: render(source, edit.render)
            val bytes = withContext(Dispatchers.Default) { encode(output.pixels) }
            val result = session.services.watchFaces.setPhoto(settings, bytes)
            if (result.outcome == WatchFaceOutcome.ACKNOWLEDGED) {
                current.update { page ->
                    val photo = page.photo?.let { it.copy(settings = settings ?: it.settings) }
                    page.copy(photo = photo, image = output.image)
                }
                sent.update { it + 1 }
            }
            result.outcome.message()
        }
    }

    /** Makes the photo face the active one. The watch sends no reply, so this only says "sent". */
    fun usePhotoFace() {
        val settings = current.value.photo?.settings ?: return
        command(USE) { session ->
            session.services.watchFaces.selectMobvoiFace(settings).outcome.message()
        }
    }

    private suspend fun render(source: PhotoSource, key: RenderKey): PhotoOutput =
        withContext(Dispatchers.Default) {
            val job = coroutineContext
            val rows = BitmapRows(source.bitmap)
            val side = WatchScreenPixels
            val pixels =
                renderCrop(rows, key.crop, side, key.filter, key.sharpening) { job.ensureActive() }
            val bitmap = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
            PhotoOutput(key, pixels, bitmap.asImageBitmap())
        }

    private suspend fun decode(bytes: ByteArray?): ImageBitmap? = bytes?.let {
        withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
        }
    }

    companion object {
        const val SET = "photo_set"
        const val USE = "photo_use"
        private const val RenderDelayMillis = 150L
    }
}

private class BitmapRows(private val bitmap: Bitmap) : PixelRows {
    override val width = bitmap.width
    override val height = bitmap.height

    override fun read(y: Int, into: IntArray) = bitmap.getPixels(into, 0, width, 0, y, width, 1)
}

private fun encode(pixels: IntArray): ByteArray {
    val side = WatchScreenPixels
    val bitmap = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
    return ByteArrayOutputStream()
        .also { bitmap.compress(Bitmap.CompressFormat.JPEG, PhotoQuality, it) }
        .toByteArray()
}

/**
 * Reads a photo upright and in sRGB, into memory the resampler can read. One longer than
 * [MaxPhotoPixels] is read at that length.
 */
private fun decodePhoto(source: ImageDecoder.Source): Bitmap =
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val long = max(info.size.width, info.size.height)
            if (long > MaxPhotoPixels) {
                val factor = MaxPhotoPixels.toDouble() / long
                decoder.setTargetSize(
                    (info.size.width * factor).roundToInt().coerceAtLeast(1),
                    (info.size.height * factor).roundToInt().coerceAtLeast(1),
                )
            }
        }
        // Drawn much smaller than its size while zoomed out, so it needs mipmaps not to shimmer.
        .also { it.setHasMipMap(true) }

private fun decodePhoto(context: Context, uri: Uri): Bitmap =
    decodePhoto(ImageDecoder.createSource(context.contentResolver, uri))

@Composable
fun PhotoFaceScreen(viewModel: PhotoFaceViewModel, onBack: () -> Unit, onEdit: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val linkReady by viewModel.linkReady.collectAsStateWithLifecycle()
    val battery by viewModel.battery.collectAsStateWithLifecycle()
    val ready = linkReady == true
    val sending = PhotoFaceViewModel.SET in pending
    val context = LocalContext.current.applicationContext
    var requested by remember { mutableStateOf(false) }
    LaunchedEffect(ready) {
        if (ready && !requested) {
            requested = true
            viewModel.load()
        }
    }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
            if (uri != null) {
                viewModel.open { decodePhoto(context, uri) }
                onEdit()
            }
        }
    PageScaffold(stringResource(R.string.faces_photo_title), onBack = onBack) { padding ->
        PageColumn(padding) {
            Column(
                Modifier.fillMaxWidth().animateContentSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = GrowingGaps(16.dp),
            ) {
                PhotoPreview(state, battery)
                Button(
                    onClick = {
                        picker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    enabled = ready && !sending,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.faces_photo_choose))
                }
                Reveal(editor.source != null || state.photo?.image != null) {
                    FilledTonalButton(
                        onClick = {
                            viewModel.editCurrent()
                            onEdit()
                        },
                        enabled = ready && !sending,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.faces_photo_edit))
                    }
                }
                Reveal(state.photo?.settings != null) {
                    FilledTonalButton(
                        onClick = viewModel::usePhotoFace,
                        enabled = ready && PhotoFaceViewModel.USE !in pending,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.faces_photo_use))
                    }
                }
            }
            FootnoteText(
                stringResource(
                    if (ready) R.string.faces_photo_note else R.string.faces_photo_disconnected
                )
            )
        }
    }
}

@Composable
private fun PhotoPreview(state: PhotoFaceUiState, battery: Int?) {
    Box(
        Modifier.size(200.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        FadeSwitch(
            state.image to state.loading,
            key = { (image, loading) -> image ?: loading },
            contentAlignment = Alignment.Center,
        ) { (image, loading) ->
            when {
                image != null ->
                    Box(Modifier.size(200.dp).clip(CircleShape)) {
                        Image(
                            image,
                            contentDescription = stringResource(R.string.faces_photo_current),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(200.dp),
                        )
                        state.photo?.settings?.let { settings ->
                            PhotoFaceOverlay(
                                PhotoLayout.of(settings),
                                battery,
                                Modifier.size(200.dp),
                            )
                        }
                    }
                loading -> CircularProgressIndicator(Modifier.padding(16.dp))
                else ->
                    AppIcon(
                        AppIcons.WatchFaces,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                    )
            }
        }
    }
}
