package dev.ticbox.companion.platform.pairing

import android.content.Context
import android.util.AtomicFile
import dev.ticbox.companion.api.AssociationMapping
import dev.ticbox.companion.engine.pairing.AssociationMappingDocumentCodec
import dev.ticbox.companion.engine.pairing.AssociationMappingStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Kept outside cloud backup because Android association IDs belong to this local app/user. */
class AtomicFileAssociationMappingStore(context: Context, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) : AssociationMappingStore {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "companion/associations-v1.bin"))
    override suspend fun all(): List<AssociationMapping> = withContext(dispatcher) { processLock.withLock { read() } }
    override suspend fun put(mapping: AssociationMapping) = withContext(dispatcher) {
        processLock.withLock { write(read().filterNot { it.associationId == mapping.associationId } + mapping) }
    }
    override suspend fun remove(associationId: Int) = withContext(dispatcher) {
        processLock.withLock {
            val previous = read()
            val selected = previous.filterNot { it.associationId == associationId }
            if (previous.size != selected.size) write(selected)
        }
    }
    private fun read(): List<AssociationMapping> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val bytes = file.openRead().use { it.readNBytes(AssociationMappingDocumentCodec.MAX_DOCUMENT_BYTES + 1) }
        return AssociationMappingDocumentCodec.decode(bytes)
    }
    private fun write(mappings: List<AssociationMapping>) {
        if (!file.baseFile.parentFile!!.isDirectory && !file.baseFile.parentFile!!.mkdirs()) throw IOException("Cannot create association mapping directory")
        val bytes = AssociationMappingDocumentCodec.encode(mappings)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch (problem: Throwable) { file.failWrite(output); throw problem }
        if (!file.openRead().use { it.readNBytes(AssociationMappingDocumentCodec.MAX_DOCUMENT_BYTES + 1) }.contentEquals(bytes)) throw IOException("Association mapping commit verification failed")
    }
    private companion object { val processLock = Mutex() }
}
