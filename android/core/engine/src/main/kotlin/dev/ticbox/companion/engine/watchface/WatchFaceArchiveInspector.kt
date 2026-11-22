package dev.ticbox.companion.engine.watchface

import dev.ticbox.companion.api.WatchFaceArchive
import java.io.FilterInputStream
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.*

data class WatchFaceArchiveInspection(val bytes: Long, val entries: Int, val expandedBytes: Long, val sha256: String)

/** Reads and verifies ZIP entries without extracting files or interpreting an installation schema. */
object WatchFaceArchiveInspector {
    suspend fun inspect(source: WatchFaceArchive, maxBytes: Long = 64L * 1024 * 1024,
        maxExpandedBytes: Long = 128L * 1024 * 1024, maxEntries: Int = 512): WatchFaceArchiveInspection {
        require(source.sizeBytes in 1..maxBytes) { "Watch face archive byte budget exceeded" }
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L; var expanded = 0L; var entries = 0
        source.open().use { original ->
            val bounded = object : FilterInputStream(original) {
                override fun read(): Int = super.read().also { if (it >= 0) { count++; require(count <= maxBytes) { "Watch face archive byte budget exceeded" } } }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = `in`.read(buffer, offset, length).also {
                    if (it > 0) { count += it; require(count <= maxBytes) { "Watch face archive byte budget exceeded" } }
                }
            }
            val raw = DigestInputStream(bounded, digest)
            val zip = ZipInputStream(raw)
            val buffer = ByteArray(32 * 1024)
            try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = localFileIo(original) { zip.nextEntry } ?: break
                require(++entries <= maxEntries) { "Watch face ZIP entry budget exceeded" }
                val name = entry.name.replace('\\', '/')
                require(!name.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(name) && name.split('/').none { it == ".." } && '\u0000' !in name) { "Unsafe watch face ZIP entry path" }
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = localFileIo(original) { zip.read(buffer) }
                    if (read < 0) break
                    expanded += read
                    require(expanded <= maxExpandedBytes) { "Watch face expanded ZIP byte budget exceeded" }
                }
                localFileIo(original) { zip.closeEntry() } // ZipInputStream validates each entry's CRC while consuming it.
            }
            // Hash bytes beyond the local entries too (central directory and optional trailing bytes).
            while (localFileIo(original) { raw.read(buffer) } >= 0) currentCoroutineContext().ensureActive()
            } finally { zip.close() }
        }
        require(entries > 0) { "No ZIP entries observed" }
        require(count == source.sizeBytes) { "Watch face archive size changed" }
        return WatchFaceArchiveInspection(count, entries, expanded, digest.digest().hex())
    }
    internal fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** Close the owned local stream as soon as its suspended caller is cancelled. */
internal suspend fun <T> localFileIo(input: InputStream, read: () -> T): T = coroutineScope {
    val operation = async(Dispatchers.IO) { read() }
    try { operation.await() } finally { if (!currentCoroutineContext().isActive) runCatching { input.close() } }
}
