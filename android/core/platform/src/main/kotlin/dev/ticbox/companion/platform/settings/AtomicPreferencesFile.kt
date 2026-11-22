package dev.ticbox.companion.platform.settings

import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serialized atomic file IO shared by local preference stores; codecs/defaults belong to callers. */
internal class AtomicPreferencesFile(file: File, private val maxBytes: Int) {
    private val atomic = AtomicFile(file)
    private val mutex = Mutex()
    suspend fun read(): ByteArray? = withContext(Dispatchers.IO) { mutex.withLock {
        try { atomic.openRead().use { it.readNBytes(maxBytes + 1) }.also { checkSize(it) } }
        catch (_: FileNotFoundException) { null }
    } }
    suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) { mutex.withLock {
        checkSize(bytes)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (problem: Throwable) { atomic.failWrite(stream); throw problem }
    } }
    private fun checkSize(bytes: ByteArray) { require(bytes.size <= maxBytes) { "Preferences byte budget exceeded" } }
}
