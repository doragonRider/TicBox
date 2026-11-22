package dev.ticbox.companion.platform.identity

import android.content.Context
import android.util.AtomicFile
import dev.ticbox.companion.api.IdentityStore
import dev.ticbox.companion.api.NodeIdentity
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Single-process owner for stable node/network identity. Stored outside Android cloud backup.
 * UUIDs are this client's identity generation choice; they do not claim to reproduce GMS check-in.
 */
class AtomicFileIdentityStore(
    context: Context,
    private val nodeName: String = "TicBox",
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : IdentityStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "companion/identity.bin"))

    override suspend fun loadOrCreate(): NodeIdentity = withContext(dispatcher) {
        processLock.withLock {
            val base = file.baseFile
            if (base.exists() || File(base.path + ".bak").exists()) {
                return@withLock IdentityDocumentCodec.decode(readDocument())
            }
            if (!base.parentFile!!.isDirectory && !base.parentFile!!.mkdirs()) {
                throw IOException("Cannot create identity storage directory")
            }
            val identity = NodeIdentity(UUID.randomUUID().toString(), UUID.randomUUID().toString(), nodeName)
            val encoded = IdentityDocumentCodec.encode(identity)
            val output = file.startWrite()
            try {
                output.write(encoded)
                file.finishWrite(output)
            } catch (failure: Throwable) {
                file.failWrite(output)
                throw failure
            }
            // AtomicFile finish failures can be logged by the framework. Verify the committed bytes
            // before this identity can leave the store or be placed on the wire.
            if (!encoded.contentEquals(readDocument())) throw IOException("Identity commit verification failed")
            identity
        }
    }

    private fun readDocument(): ByteArray = file.openRead().use { input ->
        val bytes = input.readNBytes(IdentityDocumentCodec.MAX_DOCUMENT_BYTES + 1)
        if (bytes.size > IdentityDocumentCodec.MAX_DOCUMENT_BYTES) {
            throw IOException("Persisted identity exceeds the local limit")
        }
        bytes
    }

    private companion object {
        // Prevent two instances in this app process from manufacturing competing identities.
        val processLock = Mutex()
    }
}
