package dev.ticbox.companion.ui.faces

import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.ticbox.companion.api.WatchFaceFavorite
import dev.ticbox.companion.api.WatchFaceImage
import dev.ticbox.companion.api.WatchFaceManagement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fetches and decodes watch-face previews off the main thread. A preview is either inline in the
 * favorite or a DataApi asset that may arrive shortly after the catalog, so assets get a few
 * bounded retries.
 */
class FacePreviewLoader(private val faces: WatchFaceManagement) {
    private val cache = LruCache<String, ImageBitmap>(24)

    fun cached(favorite: WatchFaceFavorite): ImageBitmap? = favorite.previewKey()?.let(cache::get)

    suspend fun load(favorite: WatchFaceFavorite): ImageBitmap? {
        val key = favorite.previewKey() ?: return null
        cache.get(key)?.let {
            return it
        }
        return try {
            val image = favorite.preview ?: favorite.previewDigest?.let { fetch(it) } ?: return null
            decode(image)?.also { cache.put(key, it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            Log.w(TAG, "Watch face preview unavailable", problem)
            null
        }
    }

    private suspend fun fetch(digest: String): WatchFaceImage? =
        withTimeoutOrNull(FETCH_TIMEOUT_MILLIS) {
            for (wait in RETRY_DELAYS) {
                if (wait > 0) delay(wait)
                faces.previewAsset(digest)?.let {
                    return@withTimeoutOrNull it
                }
            }
            null
        }

    private suspend fun decode(image: WatchFaceImage): ImageBitmap? =
        withContext(Dispatchers.Default) {
            val bytes = image.bytes
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (
                bounds.outWidth / sample > MAX_EDGE || bounds.outHeight / sample > MAX_EDGE
            ) sample *= 2
            BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
                ?.asImageBitmap()
        }

    private companion object {
        const val TAG = "FacePreviewLoader"
        const val MAX_EDGE = 512
        const val FETCH_TIMEOUT_MILLIS = 5_000L
        val RETRY_DELAYS = listOf(0L, 300L, 1_000L)
    }
}

/** Identifies one preview image; inline previews are keyed by the image instance. */
fun WatchFaceFavorite.previewKey(): String? =
    previewDigest ?: preview?.let { "inline:$id:$componentName:${System.identityHashCode(it)}" }
