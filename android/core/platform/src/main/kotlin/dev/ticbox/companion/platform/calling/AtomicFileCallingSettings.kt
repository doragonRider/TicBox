package dev.ticbox.companion.platform.calling

import android.content.Context
import android.util.AtomicFile
import dev.ticbox.companion.engine.calling.CallingSettingsStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A local call_sync preference; no health/account content and no vendor DataItem are stored. */
class AtomicFileCallingSettings(context: Context, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : CallingSettingsStore {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "companion/calling-settings-v1.bin"))
    override suspend fun readEnabled(): Boolean? = withContext(dispatcher) {
        processLock.withLock {
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withLock null
            val bytes = file.openRead().use { it.readNBytes(6) }
            if (bytes.size != 5 || !bytes.copyOfRange(0, 4).contentEquals(MAGIC) || bytes[4].toInt() !in 0..1)
                throw IOException("Unsupported calling preference document")
            bytes[4].toInt() == 1
        }
    }
    override suspend fun writeEnabled(enabled: Boolean) = withContext(dispatcher) {
        processLock.withLock {
            if (!file.baseFile.parentFile!!.isDirectory && !file.baseFile.parentFile!!.mkdirs()) throw IOException("Cannot create calling preference directory")
            val bytes = MAGIC + byteArrayOf(if (enabled) 1 else 0)
            val out = file.startWrite()
            try { out.write(bytes); file.finishWrite(out) } catch (problem: Throwable) { file.failWrite(out); throw problem }
            if (!file.openRead().use { it.readNBytes(6) }.contentEquals(bytes)) throw IOException("Calling preference commit verification failed")
        }
    }
    private companion object {
        val processLock = Mutex()
        val MAGIC = byteArrayOf(0x54, 0x43, 0x43, 0x31)
    }
}
