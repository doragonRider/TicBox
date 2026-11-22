package dev.ticbox.companion.ui.toolbox

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.format.Formatter
import dev.ticbox.companion.api.ToolboxApk
import dev.ticbox.companion.api.ToolboxException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A file the user picked on the phone. */
data class PickedFile(val uri: Uri, val name: String, val size: Long)

/** The phone's side of a transfer: files the user picked, and documents the user created. */
class ToolboxDocuments(
    private val resolver: ContentResolver,
    private val stagingDirectory: File? = null,
) {
    internal fun transferDirectory(): File =
        stagingDirectory ?: throw ToolboxException("transfer_storage_unavailable")

    /** Null when the provider won't say what the file is or how big, which a transfer needs. */
    suspend fun describe(uri: Uri): PickedFile? =
        withContext(Dispatchers.IO) {
            runCatching {
                var name: String? = null
                var size = -1L
                resolver.query(uri, null, null, null, null)?.use { row ->
                    if (row.moveToFirst()) {
                        val nameAt = row.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeAt = row.getColumnIndex(OpenableColumns.SIZE)
                        if (nameAt >= 0 && !row.isNull(nameAt)) name = row.getString(nameAt)
                        if (sizeAt >= 0 && !row.isNull(sizeAt)) size = row.getLong(sizeAt)
                    }
                }
                if (size < 0) {
                    size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1
                }
                val shown = name ?: uri.lastPathSegment
                if (size < 0 || shown.isNullOrBlank()) null else PickedFile(uri, shown, size)
            }
                .getOrNull()
        }

    fun open(uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw ToolboxException("source_short_read")

    fun apk(file: PickedFile): ToolboxApk = ToolboxApk(file.name, file.size) { open(file.uri) }

    internal fun retainWritePermission(uri: Uri) {
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) {
            /* Some providers only grant access for this process. */
        }
    }

    internal suspend fun deleteCreated(uri: Uri) =
        withContext(Dispatchers.IO) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            Unit
        }

    /**
     * Local staging survives a provider write/close failure; a retry reopens only this document.
     */
    internal suspend fun copyCompletedTo(uri: Uri, staged: File) =
        withContext(Dispatchers.IO) {
            try {
                val output =
                    resolver.openOutputStream(uri, "wt")
                        ?: throw ToolboxException("file_export_failed")
                output.use { destination ->
                    staged.inputStream().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            destination.write(buffer, 0, count)
                        }
                        destination.flush()
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (problem: Exception) {
                throw ToolboxException("file_export_failed", problem)
            }
        }

    /** Fills a document the user just created, and removes it again if that doesn't finish. */
    suspend fun writeTo(uri: Uri, write: suspend (OutputStream) -> Unit) {
        try {
            withContext(Dispatchers.IO) {
                val output =
                    resolver.openOutputStream(uri, "wt")
                        ?: throw ToolboxException("operation_failed")
                output.use { write(it) }
            }
        } catch (problem: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            }
            throw problem
        }
    }
}

internal fun Context.fileSizeText(bytes: Long): String = Formatter.formatShortFileSize(this, bytes)

internal fun fractionOf(done: Long, total: Long?): Float? =
    total?.takeIf { it > 0 }?.let { (done.toFloat() / it).coerceIn(0f, 1f) }
