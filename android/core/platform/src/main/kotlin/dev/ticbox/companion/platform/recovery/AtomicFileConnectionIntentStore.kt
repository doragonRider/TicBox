package dev.ticbox.companion.platform.recovery

import android.content.Context
import android.util.AtomicFile
import dev.ticbox.companion.engine.recovery.ConnectionIntent
import dev.ticbox.companion.engine.recovery.ConnectionIntentCodec
import dev.ticbox.companion.engine.recovery.ConnectionIntentStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** No-backup storage: Bluetooth association IDs refer to this app installation and Android user. */
class AtomicFileConnectionIntentStore(context: Context, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : ConnectionIntentStore {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "companion/connection-intent-v1.bin"))
    override suspend fun read(): ConnectionIntent? = withContext(dispatcher) {
        processLock.withLock {
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withLock null
            ConnectionIntentCodec.decode(file.openRead().use { it.readNBytes(ConnectionIntentCodec.MAX_BYTES + 1) })
        }
    }
    override suspend fun write(intent: ConnectionIntent) = withContext(dispatcher) {
        processLock.withLock {
            if (!file.baseFile.parentFile!!.isDirectory && !file.baseFile.parentFile!!.mkdirs()) throw IOException("Cannot create connection intent directory")
            val bytes = ConnectionIntentCodec.encode(intent)
            val output = file.startWrite()
            try { output.write(bytes); file.finishWrite(output) }
            catch (problem: Throwable) { file.failWrite(output); throw problem }
            if (!file.openRead().use { it.readNBytes(ConnectionIntentCodec.MAX_BYTES + 1) }.contentEquals(bytes)) throw IOException("Connection intent commit verification failed")
        }
    }
    private companion object { val processLock = Mutex() }
}
