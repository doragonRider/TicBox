package dev.ticbox.companion.ui.toolbox

import android.annotation.SuppressLint
import android.net.Uri
import androidx.lifecycle.viewModelScope
import dev.ticbox.companion.api.ToolboxException
import dev.ticbox.companion.api.ToolboxFile
import dev.ticbox.companion.api.ToolboxFileAction
import dev.ticbox.companion.api.ToolboxFileKind
import dev.ticbox.companion.api.ToolboxFileRequest
import dev.ticbox.companion.api.ToolboxTarget
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.common.UiText
import dev.ticbox.companion.ui.common.toUiText
import dev.ticbox.companion.ui.common.uiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class FileSort {
    NAME,
    SIZE,
    DATE,
}

/** A file waiting to be pasted into another folder. */
data class FileClip(val file: ToolboxFile, val move: Boolean)

data class FileTransfer(
    val name: String,
    val upload: Boolean,
    val fraction: Float?,
    val paused: Boolean = false,
    val problem: UiText? = null,
    val canChooseDestination: Boolean = false,
    val recoveryFailed: Boolean = false,
)

data class FileManagerUiState(
    val target: ToolboxTarget? = null,
    val path: String = INTERNAL_STORAGE,
    /** Null until the first answer for this folder. */
    val files: List<ToolboxFile>? = null,
    val loading: Boolean = false,
    val problem: UiText? = null,
    val sort: FileSort = FileSort.NAME,
    val ascending: Boolean = true,
    val showHidden: Boolean = false,
    val query: String = "",
    val clip: FileClip? = null,
    val transfer: FileTransfer? = null,
    /** An upload that would replace a file already there, until the user decides. */
    val replacing: PickedFile? = null,
) {
    val visible: List<ToolboxFile>
        get() =
            files
                .orEmpty()
                .filter { showHidden || !it.name.startsWith('.') }
                .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
                .sortedWith(fileOrder(sort, ascending))
}

// This is resolved on the remote watch; the phone's Environment path belongs to another device.
@SuppressLint("SdCardPath") internal const val INTERNAL_STORAGE = "/sdcard"
internal const val ROOT_PATH = "/"

/** Folders first, as file managers do, whichever way the rest are sorted. */
internal fun fileOrder(sort: FileSort, ascending: Boolean): Comparator<ToolboxFile> {
    val byName = compareBy<ToolboxFile> { it.name.lowercase() }
    val chosen =
        when (sort) {
            FileSort.NAME -> byName
            FileSort.SIZE -> compareBy<ToolboxFile> { it.size }.then(byName)
            FileSort.DATE -> compareBy<ToolboxFile> { it.modifiedMillis }.then(byName)
        }
    return compareBy<ToolboxFile> { it.kind != ToolboxFileKind.DIRECTORY }
        .then(if (ascending) chosen else chosen.reversed())
}

internal fun parentPath(path: String): String? {
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty()) return null
    return trimmed.substringBeforeLast('/', "").ifEmpty { ROOT_PATH }
}

internal fun childPath(directory: String, name: String): String =
    directory.trimEnd('/') + "/" + name

/** Each folder on the way to [path], for the row of places to jump back to. */
internal fun pathCrumbs(path: String): List<String> =
    generateSequence(path) { parentPath(it) }.toList().reversed()

internal fun fileModeText(mode: Int): String = (mode and 0xfff).toString(8).padStart(4, '0')

internal fun isFileName(name: String): Boolean =
    name.isNotBlank() && '/' !in name && '\u0000' !in name && name != "." && name != ".."

/**
 * Kept by the toolbox graph, so a transfer and the folder are still here after a look at another
 * tool. A transfer runs in the application's scope, because leaving the toolbox isn't a reason to
 * lose half a file; Cancel is.
 */
class FileManagerViewModel(
    dependencies: CompanionDependencies,
    private val toolbox: ToolboxViewModel,
    private val documents: ToolboxDocuments,
) : SessionViewModel(dependencies) {
    private val scope: CoroutineScope = dependencies.applicationScope
    private val current = MutableStateFlow(FileManagerUiState())
    val state: StateFlow<FileManagerUiState> = current.asStateFlow()

    private var loading: Job? = null
    private val transfers = fileTransfers(scope, documents, messages)

    init {
        viewModelScope.launch {
            transfers.state.collect { transfer -> current.update { it.copy(transfer = transfer) } }
        }
    }

    private var downloadTarget: ToolboxFile? = null

    private data class UploadTarget(val directory: String, val target: ToolboxTarget)

    private var uploadTarget: UploadTarget? = null
    private var pickerPrepared = false
    private var replacementTarget: UploadTarget? = null

    /**
     * Back to a folder this connection has seen, it comes from memory and the watch isn't asked.
     */
    fun open(path: String) {
        val target = toolbox.currentTarget()
        val known = target?.let { toolbox.memory()?.files(it, path) }
        if (known == null) {
            fetch(path)
            return
        }
        loading?.cancel()
        current.update {
            it.copy(
                target = target,
                path = path,
                files = known,
                loading = false,
                problem = null,
                query = if (path == it.path) it.query else "",
            )
        }
    }

    /** The folder on the page, as it's known or by asking. */
    fun show() = open(current.value.path)

    /** Asks the watch again. */
    fun refresh() = fetch(current.value.path)

    /**
     * What's on the page stays while it answers, and through a failure that says nothing about the
     * folder. [quiet] is for an action that has already reported how it went.
     */
    private fun fetch(path: String, quiet: Boolean = false) {
        loading?.cancel()
        val target = toolbox.currentTarget()
        current.update {
            val same = path == it.path
            it.copy(
                path = path,
                files = if (same && target != null && it.target == target) it.files else null,
                loading = true,
                problem = null,
                query = if (same) it.query else "",
            )
        }
        loading = viewModelScope.launch {
            try {
                val backend = toolbox.backend()
                val asked = backend.state.value.target
                val files = reportingTimeouts { backend.files(path) }
                toolbox.backend(asked)
                asked?.let { toolbox.memory()?.rememberFiles(it, path, files) }
                current.update { it.copy(files = files, target = asked, loading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: Exception) {
                // Gone, so the folder above it is out of date as well.
                val gone = (problem as? ToolboxException)?.code == "not_found"
                if (gone) toolbox.memory()?.forget(path)
                val keep = !gone && current.value.files != null
                if (keep && !quiet) messages.show(problem.toUiText())
                current.update {
                    if (keep) it.copy(loading = false)
                    else it.copy(files = null, loading = false, problem = problem.toUiText())
                }
            }
        }
    }

    /**
     * A change can fail halfway, or because the list was out of date and the file already gone, so
     * either way what it touched is read again.
     */
    private suspend fun afterChange(vararg paths: String?) {
        withContext(NonCancellable + Dispatchers.Main) {
            paths.filterNotNull().forEach { toolbox.memory()?.forget(it) }
            fetch(current.value.path, quiet = true)
        }
    }

    fun goUp(): Boolean {
        val parent = parentPath(current.value.path) ?: return false
        open(parent)
        return true
    }

    fun setSort(sort: FileSort) = current.update {
        if (it.sort == sort) it.copy(ascending = !it.ascending) else it.copy(sort = sort)
    }

    fun setShowHidden(show: Boolean) = current.update { it.copy(showHidden = show) }

    fun setQuery(query: String) = current.update { it.copy(query = query) }

    fun makeDirectory(name: String) =
        act(
            ToolboxFileRequest(
                ToolboxFileAction.MAKE_DIRECTORY,
                childPath(current.value.path, name),
            )
        )

    fun rename(file: ToolboxFile, name: String) =
        act(
            ToolboxFileRequest(
                ToolboxFileAction.MOVE,
                file.path,
                destination = childPath(parentPath(file.path) ?: ROOT_PATH, name),
            ),
            file.target,
        )

    fun delete(file: ToolboxFile) =
        act(
            ToolboxFileRequest(
                ToolboxFileAction.DELETE,
                file.path,
                recursive = file.kind == ToolboxFileKind.DIRECTORY,
            ),
            file.target,
        )

    fun chmod(file: ToolboxFile, mode: Int) =
        act(ToolboxFileRequest(ToolboxFileAction.CHMOD, file.path, mode = mode), file.target)

    fun chown(file: ToolboxFile, uid: Int, gid: Int) =
        act(
            ToolboxFileRequest(ToolboxFileAction.CHOWN, file.path, uid = uid, gid = gid),
            file.target,
        )

    fun clip(file: ToolboxFile, move: Boolean) = current.update {
        it.copy(clip = FileClip(file, move))
    }

    fun cancelClip() = current.update { it.copy(clip = null) }

    fun paste() {
        val state = current.value
        val clip = state.clip ?: return
        current.update { it.copy(clip = null) }
        act(
            ToolboxFileRequest(
                if (clip.move) ToolboxFileAction.MOVE else ToolboxFileAction.COPY,
                clip.file.path,
                destination = childPath(state.path, clip.file.name),
                recursive = clip.file.kind == ToolboxFileKind.DIRECTORY,
            ),
            clip.file.target,
        )
    }

    private fun act(request: ToolboxFileRequest, expected: ToolboxTarget? = current.value.target) =
        command(ACTION) {
            try {
                reportingTimeouts {
                    toolbox
                        .backend(expected ?: throw ToolboxException("connection_replaced"))
                        .file(request)
                }
            } finally {
                afterChange(request.path, request.destination)
            }
            null
        }

    /** Only asks about what's known to be there; the backend still refuses a late arrival. */
    fun prepareUpload() {
        pickerPrepared = true
        uploadTarget = current.value.target?.let { UploadTarget(current.value.path, it) }
    }

    fun upload(uri: Uri) {
        val pickedTarget = if (pickerPrepared) uploadTarget else null
        pickerPrepared = false
        uploadTarget = null
        viewModelScope.launch {
            val file = documents.describe(uri)
            when {
                file == null -> messages.show(uiText(R.string.installer_unreadable))
                !isFileName(file.name) -> messages.show(toolboxMessage("invalid_file_name"))
                pickedTarget == null -> messages.show(toolboxMessage("connection_replaced"))
                current.value.files.orEmpty().any { it.name == file.name } -> {
                    replacementTarget = pickedTarget
                    current.update { it.copy(replacing = file) }
                }
                else -> push(file, pickedTarget, overwrite = false)
            }
        }
    }

    fun confirmReplace() {
        val file = current.value.replacing ?: return
        val target = replacementTarget ?: return
        replacementTarget = null
        current.update { it.copy(replacing = null) }
        push(file, target, overwrite = true)
    }

    fun cancelReplace() {
        replacementTarget = null
        current.update { it.copy(replacing = null) }
    }

    private fun push(file: PickedFile, target: UploadTarget, overwrite: Boolean) {
        val destination = childPath(target.directory, file.name)
        transfers.upload(file, target.target, destination, overwrite, { toolbox.backend() }) {
            afterChange(destination)
        }
    }

    /** Which file the system's save dialog is for. Survives the activity going away meanwhile. */
    fun prepareDownload(file: ToolboxFile) {
        downloadTarget = file
    }

    fun download(uri: Uri) {
        val file = downloadTarget ?: return
        downloadTarget = null
        transfers.download(file, uri) { toolbox.backend() }
    }

    fun pauseTransfer() = transfers.pause()

    fun resumeTransfer() =
        transfers.resume({ toolbox.backend() }) { afterChange(current.value.path) }

    fun saveTransferTo(uri: Uri) = transfers.saveTo(uri, { toolbox.backend() })

    fun cancelTransfer() = transfers.discard { toolbox.backend() }

    companion object {
        const val ACTION = "toolbox_file_action"
    }
}
