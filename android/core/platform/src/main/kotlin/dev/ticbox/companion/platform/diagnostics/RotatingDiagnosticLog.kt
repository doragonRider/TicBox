package dev.ticbox.companion.platform.diagnostics

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Private append-only JSONL journal with a fixed disk budget. Call from the diagnostic IO worker. */
internal class RotatingDiagnosticLog(
    private val directory: File,
    private val maxFileBytes: Long = 1_048_576,
    private val fileCount: Int = 4,
) {
    init { require(maxFileBytes in 4_096..16_777_216 && fileCount in 1..16) }
    private val directoryLock = locks.computeIfAbsent(directory.toPath().toAbsolutePath().normalize().toString()) { Any() }

    fun append(line: String) = synchronized(directoryLock) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size > maxFileBytes) throw IOException("Diagnostic entry exceeds the journal budget")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create diagnostic directory")
        val current = file(0)
        if (current.length() > maxFileBytes - bytes.size) rotate()
        // Never use the diagnostic entry as a filename or executable text.
        java.io.FileOutputStream(current, true).use { it.write(bytes) }
    }

    private fun file(index: Int) = File(directory, "protocol-$index.jsonl")

    private fun rotate() {
        val oldest = file(fileCount - 1)
        if (oldest.exists() && !oldest.delete()) throw IOException("Cannot rotate diagnostic archive")
        for (index in fileCount - 2 downTo 0) {
            val source = file(index)
            if (source.exists() && !source.renameTo(file(index + 1))) throw IOException("Cannot rotate diagnostic file")
        }
    }

    private companion object { val locks = ConcurrentHashMap<String, Any>() }
}
