package dev.ticbox.companion.ui.faces

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.core.graphics.scale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.ticbox.companion.api.ConnectionPhase
import dev.ticbox.companion.api.WatchFacePhoto
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.common.uiText
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.AppIcon
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.FadeSwitch
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.GrowingGaps
import dev.ticbox.companion.ui.designsystem.PageColumn
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.Reveal
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PhotoFaceUiState(
    val loading: Boolean = true,
    val photo: WatchFacePhoto? = null,
    val image: ImageBitmap? = null,
)

class PhotoFaceViewModel(dependencies: CompanionDependencies) : SessionViewModel(dependencies) {
    private val current = MutableStateFlow(PhotoFaceUiState())
    val state = current.asStateFlow()

    val linkReady: StateFlow<Boolean?> =
        sessionState(
            current = { it.client.state.value.connection.phase == ConnectionPhase.LINK_READY },
            updates = { session ->
                session.client.state.map { it.connection.phase == ConnectionPhase.LINK_READY }
            },
        )

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

    /** Sends the picked photo. [read] runs in the command so leaving the page does not stop it. */
    fun setPhoto(read: suspend () -> ByteArray) =
        command(SET) { session ->
            val bytes =
                try {
                    read()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@command uiText(R.string.faces_photo_unreadable)
                }
            val result = session.services.watchFaces.setPhoto(current.value.photo?.settings, bytes)
            current.value = current.value.copy(image = decode(bytes) ?: current.value.image)
            result.outcome.message()
        }

    /** Makes the photo face the active one. The watch sends no reply, so this only says "sent". */
    fun usePhotoFace() {
        val settings = current.value.photo?.settings ?: return
        command(USE) { session ->
            session.services.watchFaces.selectMobvoiFace(settings).outcome.message()
        }
    }

    private suspend fun decode(bytes: ByteArray?): ImageBitmap? = bytes?.let {
        withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap()
        }
    }

    companion object {
        const val SET = "photo_set"
        const val USE = "photo_use"
    }
}

/** Larger than any TicWatch screen, and far below the 1 MiB limit of a single watch message. */
private const val PhotoSide = 480

/** The picked photo as a center-cropped square JPEG, turned upright by the decoder. */
private suspend fun preparePhoto(context: Context, uri: Uri): ByteArray =
    withContext(Dispatchers.IO) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val decoded =
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val shortSide = minOf(info.size.width, info.size.height)
                decoder.setTargetSampleSize((shortSide / PhotoSide).coerceAtLeast(1))
            }
        val side = minOf(decoded.width, decoded.height)
        val square =
            Bitmap.createBitmap(
                decoded,
                (decoded.width - side) / 2,
                (decoded.height - side) / 2,
                side,
                side,
            )
        val scaled =
            if (side > PhotoSide) square.scale(PhotoSide, PhotoSide, filter = true) else square
        ByteArrayOutputStream()
            .also { scaled.compress(Bitmap.CompressFormat.JPEG, PhotoQuality, it) }
            .toByteArray()
    }

private const val PhotoQuality = 80

@Composable
fun PhotoFaceScreen(onBack: () -> Unit) {
    val viewModel = companionViewModel { PhotoFaceViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val linkReady by viewModel.linkReady.collectAsStateWithLifecycle()
    val ready = linkReady == true
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
            if (uri != null) viewModel.setPhoto { preparePhoto(context, uri) }
        }
    PageScaffold(stringResource(R.string.faces_photo_title), onBack = onBack) { padding ->
        PageColumn(padding) {
            Column(
                Modifier.fillMaxWidth().animateContentSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = GrowingGaps(16.dp),
            ) {
                PhotoPreview(state)
                Button(
                    onClick = {
                        picker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                    enabled = ready && PhotoFaceViewModel.SET !in pending,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.faces_photo_choose))
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
private fun PhotoPreview(state: PhotoFaceUiState) {
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
                    Image(
                        image,
                        contentDescription = stringResource(R.string.faces_photo_current),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(200.dp).clip(CircleShape),
                    )
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
