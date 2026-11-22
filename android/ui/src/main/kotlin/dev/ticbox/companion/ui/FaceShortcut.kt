package dev.ticbox.companion.ui

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A home screen shortcut whose icon is the watch's current face. A launcher icon can't be a picture
 * the app chooses while it runs, but a pinned shortcut's can, and it follows the face as it
 * changes.
 */
interface FaceShortcut {
    /** The latest current face a real watch showed, or null until one has. */
    val face: StateFlow<ImageBitmap?>

    /** Hands over a real watch's current face. A pinned shortcut takes it as its icon. */
    fun offer(face: ImageBitmap)

    /** Asks the launcher to pin the shortcut with the latest face. False when it can't. */
    fun pin(): Boolean

    companion object {
        val UNAVAILABLE =
            object : FaceShortcut {
                override val face = MutableStateFlow<ImageBitmap?>(null)

                override fun offer(face: ImageBitmap) = Unit

                override fun pin() = false
            }
    }
}
