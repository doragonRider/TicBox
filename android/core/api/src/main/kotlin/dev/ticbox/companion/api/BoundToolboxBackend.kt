package dev.ticbox.companion.api

import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** Production transports also validate this lease after opening each agent channel. */
class ToolboxOperationTarget(val backend: WatchToolboxBackend, val target: ToolboxTarget) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolboxOperationTarget>
}

internal class BoundToolboxBackend(private val delegate: WatchToolboxBackend, private val target: ToolboxTarget) : WatchToolboxBackend {
    override val state = MutableStateFlow(delegate.state.value.copy(target = target))
    private suspend fun <T> run(block: suspend () -> T): T {
        val current = delegate.state.value
        if (current.phase != ToolboxPhase.CONNECTED || current.target != target) throw ToolboxException("connection_replaced")
        if (current.previousDeviceId != null) throw ToolboxException("device_changed")
        return withContext(ToolboxOperationTarget(delegate, target)) { block() }
    }
    override fun bind(target: ToolboxTarget): WatchToolboxBackend {
        if (target != this.target) throw ToolboxException("connection_replaced")
        return this
    }
    override fun confirmDevice(target: ToolboxTarget): Unit = throw ToolboxException("bound_backend_lifecycle")
    override suspend fun connect(): Unit = throw ToolboxException("bound_backend_lifecycle")
    override suspend fun disconnect(): Unit = throw ToolboxException("bound_backend_lifecycle")
    override fun close() = Unit
    override suspend fun updateHost(apk: ToolboxApk, progress: (ToolboxProgress) -> Unit): ToolboxInstallResult = throw ToolboxException("bound_backend_lifecycle")
    override suspend fun install(apks: List<ToolboxApk>, options: ToolboxInstallOptions, progress: (ToolboxProgress) -> Unit) = run { delegate.install(apks, options, progress) }
    override suspend fun readBundle(file: ToolboxApk) = run { delegate.readBundle(file) }
    override suspend fun installBundle(bundle: ToolboxBundle, options: ToolboxInstallOptions, progress: (ToolboxProgress) -> Unit) = run { delegate.installBundle(bundle, options, progress) }
    override suspend fun apps(userId: Int) = run { delegate.apps(userId).map { it.copy(target = target) } }
    override suspend fun app(request: ToolboxAppRequest) = run { delegate.app(request) }
    override suspend fun permission(packageName: String, permission: String, grant: Boolean, userId: Int) = run { delegate.permission(packageName, permission, grant, userId) }
    override suspend fun appOp(packageName: String, operation: String, mode: String, userId: Int) = run { delegate.appOp(packageName, operation, mode, userId) }
    override suspend fun backupAppData(packageName: String, output: OutputStream, userId: Int, progress: (ToolboxProgress) -> Unit) = run { delegate.backupAppData(packageName, output, userId, progress) }
    override suspend fun execute(command: String, options: ToolboxCommandOptions) = run { delegate.execute(command, options) }
    override suspend fun openCommand(command: String, options: ToolboxCommandOptions) = run { delegate.openCommand(command, options) }
    override suspend fun openTerminal(options: ToolboxTerminalOptions) = run { delegate.openTerminal(options) }
    override suspend fun clipboard() = run { delegate.clipboard() }
    override suspend fun setClipboard(text: String) = run { delegate.setClipboard(text) }
    override suspend fun openScreen(options: ToolboxScreenOptions) = run { delegate.openScreen(options) }
    override suspend fun screenEncoders(maxSize: Int) = run { delegate.screenEncoders(maxSize) }
    override suspend fun screenshot(output: OutputStream) = run { delegate.screenshot(output) }
    override suspend fun files(path: String) = run { delegate.files(path).map { it.copy(target = target) } }
    override suspend fun stat(path: String) = run { delegate.stat(path).copy(target = target) }
    override suspend fun file(request: ToolboxFileRequest) = run { delegate.file(request) }
    override suspend fun pull(path: String, output: OutputStream, offset: Long, progress: (ToolboxProgress) -> Unit) = run { delegate.pull(path, output, offset, progress) }
    override suspend fun push(path: String, input: InputStream, size: Long, options: ToolboxWriteOptions, progress: (ToolboxProgress) -> Unit) = input.use { run { delegate.push(path, it, size, options, progress) } }
    override suspend fun snapshotFile(path: String) = run { delegate.snapshotFile(path) }
    override suspend fun pullResumable(snapshot: ToolboxFileSnapshot, output: OutputStream, offset: Long, prefixSha256: String?, progress: (ToolboxProgress) -> Unit) = run { delegate.pullResumable(snapshot, output, offset, prefixSha256, progress) }
    override suspend fun pushResumable(request: ToolboxUploadRequest, open: (Long) -> InputStream, progress: (ToolboxProgress) -> Unit) = run { delegate.pushResumable(request, open, progress) }
    override suspend fun discardUpload(request: ToolboxUploadRequest) = run { delegate.discardUpload(request) }

}
