package dev.ticbox.companion.platform.watchface

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import dev.ticbox.companion.api.WatchFaceArchive
import dev.ticbox.companion.platform.transport.cancellableIo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers

/** A private staged snapshot; closing it removes only the copy owned by this adapter. */
class StagedWatchFaceArchive internal constructor(private val file: File) : WatchFaceArchive, AutoCloseable {
    private val closed = AtomicBoolean()
    override val sizeBytes: Long = file.length()
    override fun open(): InputStream {
        check(!closed.get()) { "Staged watch face archive is closed" }
        return FileInputStream(file)
    }
    override fun close() { if (closed.compareAndSet(false, true)) file.delete() }
}

/** Uses URI grants held by the caller. No Activity, network URL or permission request is retained. */
class AndroidWatchFaceFiles(context: Context, private val maxArchiveBytes: Long = 64L * 1024 * 1024) {
    private val app = context.applicationContext
    suspend fun stageArchive(uri: Uri): StagedWatchFaceArchive {
        val directory = File(app.cacheDir, "watch-face-imports").apply { check(isDirectory || mkdirs()) }
        val file = File.createTempFile("watch-face-", ".zip", directory)
        val input = try { open(uri) } catch (problem: Throwable) { file.delete(); throw problem }
        var delivered = false
        try {
            input.use { source -> file.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024); var total = 0L
                while (true) {
                    val count = cancellableIo(Dispatchers.IO, onCancel = { source.close() }) { source.read(buffer) }
                    if (count < 0) break
                    if (count == 0) continue
                    total += count
                    require(total <= maxArchiveBytes) { "Watch face archive byte budget exceeded" }
                    cancellableIo(Dispatchers.IO, onCancel = { output.close() }) { output.write(buffer, 0, count) }
                }
                cancellableIo(Dispatchers.IO, onCancel = { output.close() }) { output.fd.sync() }
            } }
            return StagedWatchFaceArchive(file).also { delivered = true }
        } finally { if (!delivered) { runCatching { input.close() }; file.delete() } }
    }

    suspend fun readPhoto(uri: Uri, maxBytes: Int = 1024 * 1024): ByteArray {
        require(maxBytes > 0)
        open(uri).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = cancellableIo(Dispatchers.IO, onCancel = { input.close() }) { input.read(buffer) }
                if (count < 0) break
                require(count <= maxBytes - output.size()) { "Watch face photo byte budget exceeded" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    private suspend fun open(uri: Uri): InputStream {
        requireLocalWatchFaceScheme(uri.scheme)
        val signal = CancellationSignal()
        return cancellableIo(Dispatchers.IO, onCancel = { signal.cancel() }, onDiscard = { input: InputStream -> input.close() }) {
            val descriptor = checkNotNull(app.contentResolver.openFileDescriptor(uri, "r", signal)) { "Local watch face file unavailable" }
            ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        }
    }
}

internal fun requireLocalWatchFaceScheme(scheme: String?) {
    require(scheme in setOf("content", "file", "android.resource")) { "Watch face imports require a local URI" }
}
