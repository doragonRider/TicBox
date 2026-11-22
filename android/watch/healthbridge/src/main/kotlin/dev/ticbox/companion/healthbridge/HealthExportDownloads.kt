package dev.ticbox.companion.healthbridge

import android.content.ContentResolver
import android.content.ContentValues
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

internal data class DownloadExport<T>(val relativePath: String, val result: T)

/** Publishes only closed, complete JSON documents; a failed attempt never replaces an earlier export. */
internal class HealthExportDownloads(private val resolver: ContentResolver) {
    fun <T> write(export: (OutputStream) -> T): DownloadExport<T> {
        val time = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now())
        val name = "health-export-$time-${UUID.randomUUID().toString().take(8)}.json"
        val directory = "${Environment.DIRECTORY_DOWNLOADS}/TicBox/"
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, directory)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }) ?: throw IOException("Downloads could not create an export")
        try {
            val descriptor = resolver.openFileDescriptor(uri, "w")
                ?: throw IOException("Downloads could not open an export")
            val result = ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { file ->
                // The exporter writes token by token, which shared storage shouldn't see one by one.
                val output = file.buffered(WRITE_BUFFER_BYTES)
                export(output).also {
                    output.flush()
                    // A published file must survive losing power right after the export reports success.
                    file.fd.sync()
                }
            }
            // MediaStore may choose a different name if a file with that name already exists.
            val path = resolver.query(uri, arrayOf(MediaStore.Downloads.RELATIVE_PATH, MediaStore.Downloads.DISPLAY_NAME), null, null, null).use { cursor ->
                if (cursor == null || !cursor.moveToFirst()) throw IOException("Downloads export metadata unavailable")
                val relative = cursor.getString(0) ?: throw IOException("Downloads export directory unavailable")
                val actualName = cursor.getString(1) ?: throw IOException("Downloads export name unavailable")
                "${relative.trimEnd('/')}/$actualName"
            }
            if (resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) != 1) {
                throw IOException("Downloads did not publish the export")
            }
            return DownloadExport(path, result)
        } catch (problem: Exception) {
            try {
                if (resolver.delete(uri, null, null) != 1) problem.addSuppressed(IOException("Downloads did not remove the unfinished export"))
            } catch (cleanup: Exception) { problem.addSuppressed(cleanup) }
            throw problem
        }
    }

    private companion object {
        const val WRITE_BUFFER_BYTES = 64 * 1024
    }
}
