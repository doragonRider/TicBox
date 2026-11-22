package dev.ticbox.companion.ui.faces

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.io.InputStream
import java.util.Properties

/** The app's photo face store, in no-backup storage. */
@Composable
fun rememberPhotoFaceStore(): PhotoFaceStore {
    val context = LocalContext.current.applicationContext
    return remember(context) { PhotoFaceStore(File(context.noBackupFilesDir, "photo-face")) }
}

/** How a kept photo was framed, so editing it again starts where it was left. */
data class PhotoFraming(
    val crop: PhotoCrop,
    val down: ResampleFilter,
    val up: ResampleFilter,
    val sharpening: Sharpening,
)

/**
 * Keeps the last photo the watch accepted from here, as the file the user chose, and how it was
 * framed. Editing again then starts from that photo, not from the watch's small preview. Also keeps
 * which watch face is the photo face, so other pages can tell when it is the current one. [dir]
 * should be in no-backup storage: it holds a copy of a personal photo.
 */
class PhotoFaceStore(private val dir: File) {
    private val photo = File(dir, "photo")
    private val framingFile = File(dir, "framing.properties")
    private val componentFile = File(dir, "component")

    /**
     * Copies a chosen photo into a file of its own for this edit, replacing earlier copies that
     * were never sent. Null when it is larger than [MAX_BYTES], which is then not kept.
     */
    fun stage(open: () -> InputStream): File? =
        synchronized(lock) {
            dir.mkdirs()
            dir.listFiles { file -> file.name.startsWith(STAGED) }?.forEach { it.delete() }
            val file = File(dir, STAGED + System.nanoTime())
            var size = 0L
            open().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        size += read
                        if (size > MAX_BYTES) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (size <= MAX_BYTES) return file
            file.delete()
            null
        }

    /** After the watch accepted [file] framed as [framing]. Returns where the photo is now kept. */
    fun keep(file: File, framing: PhotoFraming): File =
        synchronized(lock) {
            dir.mkdirs()
            if (file != photo && file.exists())
                check(file.renameTo(photo)) { "Couldn't keep the photo" }
            val values = Properties()
            values["centerX"] = framing.crop.centerX.toString()
            values["centerY"] = framing.crop.centerY.toString()
            values["side"] = framing.crop.side.toString()
            values["down"] = framing.down.name
            values["up"] = framing.up.name
            values["sharpening"] = framing.sharpening.name
            val next = File(dir, "framing.next")
            next.outputStream().use { values.store(it, null) }
            check(next.renameTo(framingFile)) { "Couldn't keep the framing" }
            photo
        }

    /** The kept photo and its framing; the framing is null when it is missing or unreadable. */
    fun kept(): Pair<File, PhotoFraming?>? =
        synchronized(lock) {
            if (!photo.isFile) return null
            val framing =
                try {
                    val values = Properties().apply { framingFile.inputStream().use { load(it) } }
                    fun number(key: String) = values.getProperty(key).toDouble()
                    val crop = PhotoCrop(number("centerX"), number("centerY"), number("side"))
                    check(
                        crop.centerX.isFinite() && crop.centerY.isFinite() && crop.side.isFinite()
                    )
                    check(crop.side > 0)
                    PhotoFraming(
                        crop,
                        ResampleFilter.valueOf(values.getProperty("down")),
                        ResampleFilter.valueOf(values.getProperty("up")),
                        Sharpening.valueOf(values.getProperty("sharpening")),
                    )
                } catch (_: Exception) {
                    null
                }
            photo to framing
        }

    /** The photo face's component name, once a page has found it on the watch. */
    fun component(): String? =
        synchronized(lock) {
            if (!componentFile.isFile) return null
            componentFile.readText().trim().ifEmpty { null }
        }

    fun rememberComponent(name: String): Unit =
        synchronized(lock) {
            if (component() == name) return
            dir.mkdirs()
            val next = File(dir, "component.next")
            next.writeText(name)
            check(next.renameTo(componentFile)) { "Couldn't keep the component" }
        }

    companion object {
        /** Larger than any phone camera's JPEG or HEIC. */
        const val MAX_BYTES = 64L * 1024 * 1024
        private const val STAGED = "staged-"

        /** One lock for every store, as pages make their own over the same folder. */
        private val lock = Any()
    }
}
