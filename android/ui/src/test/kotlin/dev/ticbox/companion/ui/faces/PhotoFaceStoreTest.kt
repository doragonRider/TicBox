package dev.ticbox.companion.ui.faces

import dev.ticbox.companion.api.WatchFaceFavorite
import dev.ticbox.companion.api.WatchFaceInstalled
import dev.ticbox.companion.api.WatchFaceManagementState
import dev.ticbox.companion.api.WatchFacePhotoSettings
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PhotoFaceStoreTest {
    @TempDir lateinit var dir: File

    @Test
    fun aSentPhotoIsKeptWithItsFramingAndAnUnsentOneIsNot() {
        val store = PhotoFaceStore(File(dir, "photo-face"))
        assertNull(store.kept())
        val first = store.stage { byteArrayOf(1, 2, 3).inputStream() }!!
        val framing =
            PhotoFraming(
                PhotoCrop(1.5, 2.5, 300.0),
                ResampleFilter.BILINEAR,
                ResampleFilter.LANCZOS3,
                Sharpening.MEDIUM,
            )
        val kept = store.keep(first, framing)
        // A photo chosen afterwards but never sent leaves the kept one alone.
        store.stage { byteArrayOf(9).inputStream() }
        val (photo, restored) = store.kept()!!
        assertEquals(kept, photo)
        assertContentEquals(byteArrayOf(1, 2, 3), photo.readBytes())
        assertEquals(framing, restored)
        // Sending the kept photo again only updates its framing.
        store.keep(photo, framing.copy(sharpening = Sharpening.OFF))
        assertEquals(Sharpening.OFF, store.kept()!!.second!!.sharpening)
        File(dir, "photo-face/framing.properties").writeText("side=NaN")
        assertNull(store.kept()!!.second)
    }

    @Test
    fun thePhotoFaceIsFoundUnderTheNameTheWatchUses() {
        val faces =
            WatchFaceManagementState(
                installed =
                    listOf(
                        WatchFaceInstalled(
                            "com.example.faces/com.example.faces.image.ImageFace",
                            "Image",
                            sourceUri = "a",
                        ),
                        WatchFaceInstalled(
                            "com.example.faces/com.example.faces.Sport",
                            "Sport",
                            sourceUri = "b",
                        ),
                    ),
                favorites = listOf(WatchFaceFavorite(1, "other.pkg/.Face")),
            )
        fun find(cls: String?, name: String? = null) =
            photoFaceComponent(
                faces,
                WatchFacePhotoSettings(
                    packageName = "com.example.faces",
                    className = cls,
                    name = name,
                ),
            )
        val image = "com.example.faces/com.example.faces.image.ImageFace"
        assertEquals(image, find("com.example.faces.image.ImageFace"))
        assertEquals(image, find(".image.ImageFace"))
        assertEquals(image, find("ImageFace"))
        assertEquals(image, find("Unknown", name = "Image"))
        assertNull(find("Unknown"))
        assertEquals("other.pkg/other.pkg.Face", fullComponentName("other.pkg/.Face"))
    }
}
