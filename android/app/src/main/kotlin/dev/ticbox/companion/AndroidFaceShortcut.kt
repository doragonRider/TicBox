package dev.ticbox.companion

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.ticbox.companion.ui.FaceShortcut
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The face shortcut through [ShortcutManager]. Its intent names the Activity itself rather than a
 * launcher entry, which Appearance may turn off, and is otherwise the one a launcher sends, so an
 * open app comes forward as it was left.
 */
class AndroidFaceShortcut(private val context: Context) : FaceShortcut {
    private val shortcuts = context.getSystemService(ShortcutManager::class.java)
    private val latest = MutableStateFlow<ImageBitmap?>(null)
    override val face = latest.asStateFlow()

    override fun offer(face: ImageBitmap) {
        latest.value = face
        try {
            if (shortcuts.pinnedShortcuts.any { it.id == ID })
                shortcuts.updateShortcuts(listOf(info(face)))
        } catch (problem: RuntimeException) {
            Log.w(TAG, "Updating the face shortcut failed: ${problem.javaClass.simpleName}")
        }
    }

    override fun pin(): Boolean {
        val face = latest.value ?: return false
        return try {
            shortcuts.isRequestPinShortcutSupported &&
                shortcuts.requestPinShortcut(info(face), null)
        } catch (problem: RuntimeException) {
            Log.w(TAG, "Pinning the face shortcut failed: ${problem.javaClass.simpleName}")
            false
        }
    }

    private fun info(face: ImageBitmap): ShortcutInfo =
        ShortcutInfo.Builder(context, ID)
            .setShortLabel(context.getString(R.string.app_name))
            .setIcon(Icon.createWithAdaptiveBitmap(adaptive(face.asAndroidBitmap())))
            .setIntent(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClass(context, CompanionActivity::class.java)
            )
            .build()

    /**
     * An adaptive icon's layer is half as wide again as what a launcher shows, so the face fills
     * the middle two thirds. Black around it suits a launcher that shows the corners too.
     */
    private fun adaptive(face: Bitmap): Bitmap {
        val icon = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(icon)
        canvas.drawColor(Color.BLACK)
        val shown = ICON_SIZE * 2f / 3f
        val inset = (ICON_SIZE - shown) / 2f
        canvas.drawBitmap(
            face,
            null,
            RectF(inset, inset, inset + shown, inset + shown),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        return icon
    }

    private companion object {
        const val TAG = "FaceShortcut"
        const val ID = "current-watch-face"
        const val ICON_SIZE = 432
    }
}
