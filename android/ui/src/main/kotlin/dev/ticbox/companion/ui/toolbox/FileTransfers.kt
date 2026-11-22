package dev.ticbox.companion.ui.toolbox

import android.net.Uri
import androidx.core.net.toUri
import dev.ticbox.companion.api.ToolboxBackendKind
import dev.ticbox.companion.api.ToolboxCapability
import dev.ticbox.companion.api.ToolboxException
import dev.ticbox.companion.api.ToolboxFile
import dev.ticbox.companion.api.ToolboxFileSnapshot
import dev.ticbox.companion.api.ToolboxProgress
import dev.ticbox.companion.api.ToolboxTarget
import dev.ticbox.companion.api.ToolboxUploadRequest
import dev.ticbox.companion.api.ToolboxWriteOptions
import dev.ticbox.companion.api.WatchToolboxBackend
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.UserMessages
import dev.ticbox.companion.ui.common.toUiText
import dev.ticbox.companion.ui.common.uiText
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.WeakHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One user-selected transfer, retained privately until it finishes or is explicitly discarded. */
@Serializable
internal data class FileTransferCheckpoint(
    val id: String,
    val name: String,
    val upload: Boolean,
    val deviceId: String,
    val backend: String,
    val connectionId: Long,
    val remotePath: String,
    val uri: String,
    val size: Long,
    val sha256: String,
    val modifiedMillis: Long = 0,
    val overwrite: Boolean = false,
    val completed: Boolean = false,
) {
    val target: ToolboxTarget
        get() = ToolboxTarget(deviceId, connectionId, ToolboxBackendKind.valueOf(backend))

    fun snapshot() = ToolboxFileSnapshot(target, remotePath, size, sha256, modifiedMillis)

    fun uploadRequest() =
        ToolboxUploadRequest(target, id, remotePath, size, sha256, ToolboxWriteOptions(overwrite))

    fun validate(): FileTransferCheckpoint = apply {
        require(id.matches(Regex("[a-f0-9]{32}")))
        require(name.isNotBlank() && name.length <= 1024)
        require(deviceId.isNotBlank() && deviceId.length <= 128)
        require(remotePath.startsWith('/') && '\u0000' !in remotePath)
        require(size >= 0 && sha256.matches(Regex("[a-f0-9]{64}")))
        require(uri.length <= 16384 && uri.toUri().scheme in setOf("content", "file"))
        ToolboxBackendKind.valueOf(backend)
    }
}

internal class FileTransferStore(private val directory: File) {
    private val manifest = File(directory, "transfer.json")

    fun data(id: String): File {
        require(id.matches(Regex("[a-f0-9]{32}")))
        check(directory.isDirectory || directory.mkdirs()) { "transfer_storage_unavailable" }
        return File(directory, "$id.part")
    }

    @Synchronized
    fun read(): FileTransferCheckpoint? {
        if (!manifest.exists()) return null
        if (manifest.length() > 32768) throw ToolboxException("transfer_checkpoint_invalid")
        return try {
            Json.decodeFromString<FileTransferCheckpoint>(manifest.readText()).validate()
        } catch (problem: Exception) {
            throw ToolboxException("transfer_checkpoint_invalid", problem)
        }
    }

    @Synchronized
    fun save(value: FileTransferCheckpoint) {
        value.validate()
        data(value.id)
        val temporary = File(directory, "transfer.json.tmp")
        FileOutputStream(temporary).use {
            it.write(Json.encodeToString(value).toByteArray())
            it.fd.sync()
        }
        Files.move(
            temporary.toPath(),
            manifest.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    @Synchronized
    fun clearInvalid() {
        Files.deleteIfExists(manifest.toPath())
        directory
            .listFiles()
            ?.filter {
                it.name.matches(Regex("[a-f0-9]{32}\\.part")) || it.name == "transfer.json.tmp"
            }
            ?.forEach { Files.deleteIfExists(it.toPath()) }
    }

    @Synchronized
    fun clear(id: String) {
        if (read()?.id == id) Files.deleteIfExists(manifest.toPath())
        Files.deleteIfExists(data(id).toPath())
    }
}

/** Shared across navigation graphs. Reopening a page never starts another copy of an active job. */
private val transferControllers = WeakHashMap<CoroutineScope, FileTransferController>()

internal fun fileTransfers(
    scope: CoroutineScope,
    documents: ToolboxDocuments,
    messages: UserMessages,
): FileTransferController =
    synchronized(transferControllers) {
        transferControllers.getOrPut(scope) {
            FileTransferController(scope, documents, messages).also { controller ->
                scope.coroutineContext[Job]?.invokeOnCompletion {
                    synchronized(transferControllers) {
                        if (transferControllers[scope] === controller)
                            transferControllers.remove(scope)
                    }
                }
            }
        }
    }

internal class FileTransferController(
    private val scope: CoroutineScope,
    private val documents: ToolboxDocuments,
    private val messages: UserMessages,
) {
    private val current = MutableStateFlow<FileTransfer?>(null)
    val state = current.asStateFlow()
    private val store by lazy { FileTransferStore(documents.transferDirectory()) }
    private var checkpoint: FileTransferCheckpoint? = null
    private var work: Job? = null
    @Volatile private var activeId: String? = null
    private var restorationFailed = false
    private val restored = scope.launch {
        try {
            checkpoint = withContext(Dispatchers.IO) { store.read() }
            checkpoint?.let { current.value = restoredState(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            restorationFailed = true
            current.value =
                FileTransfer(
                    "",
                    false,
                    null,
                    paused = true,
                    problem = problem.toUiText(),
                    recoveryFailed = true,
                )
            messages.show(problem.toUiText())
        }
    }

    private fun restoredState(saved: FileTransferCheckpoint): FileTransfer {
        val local = if (saved.upload) null else store.data(saved.id).length()
        return FileTransfer(
            saved.name,
            saved.upload,
            local?.let { fractionOf(it, saved.size) },
            paused = true,
            canChooseDestination = local == saved.size,
        )
    }

    fun upload(
        file: PickedFile,
        target: ToolboxTarget,
        path: String,
        overwrite: Boolean,
        backend: () -> WatchToolboxBackend,
        changed: suspend () -> Unit,
    ) =
        start(file.name, true) { id ->
            val bound = requireBackend(backend, target, exactConnection = true)
            requireResume(bound)
            val staged = store.data(id)
            val hash =
                withContext(Dispatchers.IO) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var copied = 0L
                    try {
                        documents.open(file.uri).use { input ->
                            FileOutputStream(staged).use { output ->
                                val buffer = ByteArray(65536)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (count == 0) throw ToolboxException("source_empty_read")
                                    copied += count
                                    if (copied > file.size)
                                        throw ToolboxException("source_size_mismatch")
                                    output.write(buffer, 0, count)
                                    digest.update(buffer, 0, count)
                                }
                                if (copied != file.size)
                                    throw ToolboxException("source_size_mismatch")
                                output.fd.sync()
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        digest.digest().hex()
                    } catch (problem: Throwable) {
                        staged.delete()
                        throw problem
                    }
                }
            val prepared =
                FileTransferCheckpoint(
                    id,
                    file.name,
                    true,
                    target.deviceId,
                    target.kind.name,
                    target.connectionId,
                    path,
                    file.uri.toString(),
                    file.size,
                    hash,
                    overwrite = overwrite,
                )
            persist(prepared)
            execute(prepared, backend, changed)
        }

    fun download(
        file: ToolboxFile,
        uri: Uri,
        backend: () -> WatchToolboxBackend,
    ) =
        start(file.name, false) { id ->
            val target = file.target ?: throw ToolboxException("connection_replaced")
            val bound = requireBackend(backend, target, exactConnection = true)
            requireResume(bound)
            val snapshot = bound.snapshotFile(file.path)
            documents.retainWritePermission(uri)
            val prepared =
                FileTransferCheckpoint(
                    id,
                    file.name,
                    false,
                    target.deviceId,
                    target.kind.name,
                    target.connectionId,
                    snapshot.path,
                    uri.toString(),
                    snapshot.size,
                    snapshot.sha256,
                    snapshot.modifiedMillis,
                )
            withContext(Dispatchers.IO) { FileOutputStream(store.data(id)).use { it.fd.sync() } }
            persist(prepared)
            execute(prepared, backend, {})
        }

    private fun start(name: String, upload: Boolean, block: suspend (String) -> Unit) {
        if (work?.isCompleted == false || checkpoint != null || restorationFailed) {
            messages.show(uiText(R.string.files_transfer_busy))
            return
        }
        val id = UUID.randomUUID().toString().replace("-", "")
        activeId = id
        current.value = FileTransfer(name, upload, null)
        work = scope.launch {
            run(id) {
                restored.join()
                if (checkpoint != null || restorationFailed) {
                    checkpoint?.let {
                        activeId = it.id
                        current.value = restoredState(it)
                    }
                    if (restorationFailed) activeId = null
                    messages.show(uiText(R.string.files_transfer_busy))
                } else block(id)
            }
        }
    }

    private suspend fun persist(value: FileTransferCheckpoint) {
        currentCoroutineContext().ensureActive()
        // This small atomic manifest write is serialized with main-thread pause/discard actions.
        store.save(value)
        checkpoint = value
    }

    fun resume(backend: () -> WatchToolboxBackend, changed: suspend () -> Unit) {
        val saved = checkpoint ?: return
        if (work?.isCompleted == false) return
        activeId = saved.id
        current.value = FileTransfer(saved.name, saved.upload, current.value?.fraction)
        work = scope.launch { run(saved.id) { execute(saved, backend, changed) } }
    }

    fun saveTo(uri: Uri, backend: () -> WatchToolboxBackend) {
        val saved = checkpoint?.takeIf { !it.upload } ?: return
        if (work?.isCompleted == false) return
        documents.retainWritePermission(uri)
        val replaced = saved.copy(uri = uri.toString())
        try {
            store.save(replaced)
            checkpoint = replaced
            resume(backend, {})
        } catch (problem: Exception) {
            messages.show(problem.toUiText())
        }
    }

    fun pause() {
        work?.cancel()
    }

    /**
     * Discards local progress; remote upload cleanup is bounded and any uncertainty is reported.
     */
    fun discard(backend: () -> WatchToolboxBackend) {
        if (restorationFailed) {
            if (work?.isCompleted == false) return
            work = scope.launch {
                try {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        withContext(Dispatchers.IO) { store.clearInvalid() }
                        restorationFailed = false
                        current.value = null
                    }
                } catch (problem: Exception) {
                    messages.show(problem.toUiText())
                }
            }
            return
        }
        if (work?.isCompleted == false && checkpoint != null) {
            pause()
            return
        }
        val saved = checkpoint
        val id = activeId
        activeId = null
        work?.cancel()
        work = null
        if (saved == null) {
            current.value = null
            if (id != null) scope.launch(Dispatchers.IO) { store.clear(id) }
            return
        }
        activeId = saved.id
        current.value = FileTransfer(saved.name, saved.upload, current.value?.fraction)
        work = scope.launch {
            try {
                if (saved.upload) {
                    try {
                        reportingTimeouts {
                            kotlinx.coroutines.withTimeout(5000) {
                                requireBackend(backend, saved.target)
                                    .discardUpload(saved.uploadRequest())
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        messages.show(uiText(R.string.files_remote_cleanup_pending))
                    }
                }
                withContext(kotlinx.coroutines.NonCancellable) {
                    withContext(Dispatchers.IO) { store.clear(saved.id) }
                    checkpoint = null
                    current.value = null
                    activeId = null
                    if (!saved.upload) messages.show(uiText(R.string.files_destination_retained))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: Exception) {
                messages.show(problem.toUiText())
            } finally {
                if (checkpoint != null)
                    current.update {
                        it?.copy(
                            paused = true,
                            canChooseDestination =
                                checkpoint?.let { saved ->
                                    !saved.upload && store.data(saved.id).length() == saved.size
                                } == true,
                        )
                    }
            }
        }
    }

    private suspend fun run(id: String, block: suspend () -> Unit) {
        try {
            reportingTimeouts { block() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            if (activeId == id) {
                android.util.Log.w("CompanionCommand", "File transfer failed", problem)
                messages.show(problem.toUiText())
                current.update { it?.copy(problem = problem.toUiText()) }
            }
        } finally {
            if (activeId == id) {
                if (checkpoint?.id == id)
                    current.update {
                        it?.copy(
                            paused = true,
                            canChooseDestination =
                                checkpoint?.let { saved ->
                                    !saved.upload && store.data(saved.id).length() == saved.size
                                } == true,
                        )
                    }
                else {
                    current.value = null
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                        store.clear(id)
                    }
                }
            }
        }
    }

    private suspend fun execute(
        saved: FileTransferCheckpoint,
        backend: () -> WatchToolboxBackend,
        changed: suspend () -> Unit,
    ) {
        val staged = store.data(saved.id)
        val progress: (ToolboxProgress) -> Unit = { value ->
            current.update {
                if (activeId == saved.id)
                    it?.copy(fraction = fractionOf(value.completedBytes, value.totalBytes))
                else it
            }
        }
        if (saved.upload) {
            verify(staged, saved.size, saved.sha256)
            if (!saved.completed) {
                val bound = requireBackend(backend, saved.target)
                requireResume(bound)
                bound.pushResumable(
                    saved.uploadRequest(),
                    { offset ->
                        FileInputStream(staged).apply { channel.position(offset) }
                    },
                    progress,
                )
                persist(saved.copy(completed = true))
            }
            // Persisted completion prevents retransmission if cancellation happens during cleanup.
            try {
                requireBackend(backend, saved.target).discardUpload(saved.uploadRequest())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                /* The completed remote manifest may be discarded later. */
            }
            changed()
        } else {
            val offset = withContext(Dispatchers.IO) { staged.length() }
            if (!staged.isFile || offset > saved.size)
                throw ToolboxException("file_resume_mismatch")
            if (offset < saved.size) {
                val bound = requireBackend(backend, saved.target)
                requireResume(bound)
                val prefix = if (offset == 0L) null else hash(staged)
                withContext(Dispatchers.IO) {
                    FileOutputStream(staged, true).use { output ->
                        bound.pullResumable(saved.snapshot(), output, offset, prefix, progress)
                        output.fd.sync()
                    }
                }
            }
            verify(staged, saved.size, saved.sha256)
            // A destination-provider failure preserves the complete local file for another save
            // attempt.
            documents.copyCompletedTo(saved.uri.toUri(), staged)
        }
        currentCoroutineContext().ensureActive()
        withContext(kotlinx.coroutines.NonCancellable) {
            withContext(Dispatchers.IO) { store.clear(saved.id) }
            checkpoint = null
            current.value = null
            activeId = null
            messages.show(
                uiText(
                    if (saved.upload) R.string.files_uploaded else R.string.files_downloaded,
                    saved.name,
                )
            )
        }
    }

    private fun requireBackend(
        backend: () -> WatchToolboxBackend,
        expected: ToolboxTarget,
        exactConnection: Boolean = false,
    ): WatchToolboxBackend {
        val value = backend()
        val actual = value.state.value.target ?: throw ToolboxException("device_identity_required")
        if (actual.deviceId != expected.deviceId || actual.kind != expected.kind)
            throw ToolboxException("device_changed")
        if (exactConnection && actual != expected) throw ToolboxException("connection_replaced")
        return value.bind(actual)
    }

    private fun requireResume(backend: WatchToolboxBackend) {
        if (ToolboxCapability.FILE_RESUME !in backend.state.value.capabilities?.features.orEmpty())
            throw ToolboxException("file_resume_unavailable")
    }

    private suspend fun verify(file: File, size: Long, expected: String) {
        if (!file.isFile || file.length() != size || hash(file) != expected)
            throw ToolboxException("file_resume_mismatch")
    }

    private suspend fun hash(file: File): String =
        withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().hex()
        }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
