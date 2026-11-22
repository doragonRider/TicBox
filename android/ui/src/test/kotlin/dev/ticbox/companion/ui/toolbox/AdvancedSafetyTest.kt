package dev.ticbox.companion.ui.toolbox

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import dev.ticbox.companion.api.*
import dev.ticbox.companion.ui.*
import dev.ticbox.companion.ui.session.SessionState
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Regression coverage for target binding, document names, and cancelled transfer ownership. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AdvancedSafetyTest {
    class Backend(kind: ToolboxBackendKind) :
        AdbToolboxClient by WatchToolboxClient.UNAVAILABLE.adb {
        override fun bind(target: ToolboxTarget): WatchToolboxBackend =
            super<AdbToolboxClient>.bind(target)

        override fun confirmDevice(target: ToolboxTarget) {
            super<AdbToolboxClient>.confirmDevice(target)
            state.value = state.value.copy(previousDeviceId = null)
        }

        override val state =
            MutableStateFlow(
                ToolboxBackendState(
                    kind,
                    ToolboxPhase.CONNECTED,
                    kind.name,
                    ToolboxCapabilities(
                        if (kind == ToolboxBackendKind.ROOT_BRIDGE) 0 else 2000,
                        33,
                        ToolboxCapability.entries.toSet(),
                    ),
                    target = ToolboxTarget("watch", 1, kind),
                )
            )
        val fileCalls = mutableListOf<ToolboxFileRequest>()
        val appCalls = mutableListOf<ToolboxAppRequest>()
        val pushes = mutableListOf<String>()
        val commands = mutableListOf<String>()
        val clipboardWrites = mutableListOf<String>()
        var beforePush: (suspend (String) -> Unit)? = null
        var beforeInstall: (() -> Unit)? = null
        var shell: ToolboxCommandSession? = null

        override suspend fun files(path: String) = emptyList<ToolboxFile>()

        override suspend fun file(request: ToolboxFileRequest) {
            fileCalls += request
        }

        override suspend fun app(request: ToolboxAppRequest): ToolboxCommandResult {
            appCalls += request
            return ToolboxCommandResult(0, byteArrayOf(), byteArrayOf())
        }

        override suspend fun apps(userId: Int) = emptyList<ToolboxApp>()

        override suspend fun setClipboard(text: String) {
            clipboardWrites += text
        }

        override suspend fun execute(
            command: String,
            options: ToolboxCommandOptions,
        ): ToolboxCommandResult {
            commands += command
            return ToolboxCommandResult(0, byteArrayOf(), byteArrayOf())
        }

        override suspend fun install(
            apks: List<ToolboxApk>,
            options: ToolboxInstallOptions,
            progress: (ToolboxProgress) -> Unit,
        ): ToolboxInstallResult {
            beforeInstall?.invoke()
            return ToolboxInstallResult(true, "Success")
        }

        override suspend fun push(
            path: String,
            input: InputStream,
            size: Long,
            options: ToolboxWriteOptions,
            progress: (ToolboxProgress) -> Unit,
        ) {
            pushes += path
            beforePush?.invoke(path)
            input.use { it.readBytes() }
        }

        override suspend fun pushResumable(
            request: ToolboxUploadRequest,
            open: (Long) -> InputStream,
            progress: (ToolboxProgress) -> Unit,
        ): ToolboxTransferReceipt {
            push(request.path, open(0), request.size, request.options, progress)
            return ToolboxTransferReceipt(request.size, request.sha256, 0)
        }

        override suspend fun discardUpload(request: ToolboxUploadRequest) = Unit

        override suspend fun openCommand(command: String, options: ToolboxCommandOptions) =
            checkNotNull(shell)
    }

    private class Fixture : AutoCloseable {
        val context = RuntimeEnvironment.getApplication()
        val adb = Backend(ToolboxBackendKind.ADB)
        val root = Backend(ToolboxBackendKind.ROOT_BRIDGE)
        val services =
            object : WatchToolboxClient {
                override val adb = this@Fixture.adb
                override val localBridge = root
            }
        val dependencies =
            TestDependencies(
                FakeSessions(SessionState.Ready(demoWatch(context).session(toolbox = services)))
            )
        val toolbox = ToolboxViewModel(dependencies)
        val documents =
            ToolboxDocuments(
                context.contentResolver,
                java.nio.file.Files.createTempDirectory("transfer-test-").toFile(),
            )

        override fun close() {
            dependencies.applicationScope.cancel()
        }
    }

    private fun awaitUi(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
            Thread.sleep(5)
        }
        assertTrue(
            condition(),
            "UI command did not finish: " +
                org.robolectric.shadows.ShadowLog.getLogsForTag("CompanionCommand").map {
                    it.throwable
                },
        )
    }

    @Test
    fun aReopenedAdvancedPageStillWarnsUntilTheBackendDeviceIsConfirmed() {
        Fixture().use { f ->
            f.root.state.value = f.root.state.value.copy(previousDeviceId = "previous-watch")
            val reopened = ToolboxViewModel(f.dependencies)
            assertTrue(reopened.access.value!!.deviceChanged)
            assertFalse(reopened.access.value!!.connected)
            assertEquals(
                "device_changed",
                assertFailsWith<ToolboxException> { reopened.backend() }.code,
            )
            reopened.confirmDevice()
            awaitUi { reopened.access.value?.connected == true }
            assertNull(f.root.state.value.previousDeviceId)
        }
    }

    @Test
    fun aReopenedPageCannotSilentlyFollowFallbackToAnotherBackendsDevice() {
        Fixture().use { f ->
            assertEquals("watch", f.toolbox.backend().state.value.target?.deviceId)
            f.adb.state.value =
                f.adb.state.value.copy(
                    target = ToolboxTarget("other-watch", 2, ToolboxBackendKind.ADB)
                )
            f.root.state.value =
                f.root.state.value.copy(phase = ToolboxPhase.DISCONNECTED, target = null)
            val reopened = ToolboxViewModel(f.dependencies)
            assertTrue(reopened.access.value!!.deviceChanged)
            assertEquals(
                "device_changed",
                assertFailsWith<ToolboxException> { reopened.backend() }.code,
            )
            val bridge =
                dev.ticbox.companion.ui.bridge.LocalBridgeViewModel(f.dependencies) { null }
            assertTrue(bridge.state.value!!.deviceChanged)
            bridge.confirmDevice()
            awaitUi { reopened.access.value?.connected == true }
            assertFalse(bridge.state.value!!.deviceChanged)
        }
    }

    @Test
    fun bridgeInstallFollowupCannotGrantOrLaunchOnAReplacementConnection() {
        Fixture().use { f ->
            val original = f.adb.state.value.target
            f.adb.beforeInstall = {
                f.adb.state.value =
                    f.adb.state.value.copy(
                        target = ToolboxTarget("replacement", 2, ToolboxBackendKind.ADB)
                    )
            }
            val bridge =
                dev.ticbox.companion.ui.bridge.LocalBridgeViewModel(f.dependencies) {
                    ToolboxApk("bridge.apk", 1) { byteArrayOf(1).inputStream() }
                }
            assertEquals(original, bridge.state.value?.adb?.target)
            bridge.install()
            awaitUi {
                bridge.install.value != dev.ticbox.companion.ui.bridge.BridgeInstallState.Idle
            }
            awaitUi { bridge.pending.value.isEmpty() }
            assertEquals(
                dev.ticbox.companion.ui.bridge.BridgeInstallState.Installed,
                bridge.install.value,
            )
            assertEquals(original, bridge.installationTarget.value)
            assertTrue(
                f.adb.commands.isEmpty(),
                "DND permission must not be granted on the replacement",
            )
            assertTrue(f.adb.appCalls.isEmpty(), "The replacement must not receive the launch")
        }
    }

    @Test
    fun queuedClipboardAndPasteActionsCannotFollowAReplacementConnection() {
        Fixture().use { f ->
            val clipboard = ClipboardViewModel(f.dependencies, f.toolbox)
            clipboard.setDraft("stale")
            clipboard.setOnWatch()
            clipboard.pasteOnWatch()
            assertEquals(
                setOf(ClipboardViewModel.SET, ClipboardViewModel.PASTE),
                clipboard.pending.value,
            )
            f.root.state.value =
                f.root.state.value.copy(
                    target = ToolboxTarget("watch", 2, ToolboxBackendKind.ROOT_BRIDGE)
                )
            awaitUi { clipboard.pending.value.isEmpty() }
            assertTrue(f.root.clipboardWrites.isEmpty())
            assertTrue(f.root.commands.isEmpty())
            assertTrue(f.adb.clipboardWrites.isEmpty() && f.adb.commands.isEmpty())

            clipboard.setDraft("accepted")
            clipboard.setOnWatch()
            clipboard.setDraft("edited after sending")
            clipboard.pasteOnWatch()
            awaitUi { clipboard.pending.value.isEmpty() }
            assertEquals(listOf("accepted"), f.root.clipboardWrites)
            assertEquals(listOf("input keyevent KEYCODE_PASTE"), f.root.commands)
        }
    }

    @Test
    fun changedDeviceRequiresConfirmationAndOldObjectsRemainRejectedAfterConfirmation() {
        Fixture().use { f ->
            f.toolbox.select(ToolboxBackendKind.ADB)
            val files = FileManagerViewModel(f.dependencies, f.toolbox, f.documents)
            val apps = AppManagerViewModel(f.dependencies, f.toolbox, f.documents)
            val oldFile =
                ToolboxFile(
                    "/sdcard/important.txt",
                    "important.txt",
                    ToolboxFileKind.FILE,
                    1,
                    0,
                    384,
                    2000,
                    2000,
                    target = f.adb.state.value.target,
                )
            val oldApp =
                ToolboxApp(
                    "com.example.app",
                    "Old watch app",
                    null,
                    1,
                    10001,
                    true,
                    false,
                    emptyList(),
                    null,
                    f.adb.state.value.target,
                )
            val errors = mutableListOf<dev.ticbox.companion.ui.common.UiText>()
            f.dependencies.applicationScope.launch {
                f.dependencies.messages.messages.collect { errors += it }
            }
            f.root.state.value =
                f.root.state.value.copy(
                    target = ToolboxTarget("different-watch", 2, ToolboxBackendKind.ROOT_BRIDGE)
                )
            f.adb.state.value = f.adb.state.value.copy(phase = ToolboxPhase.DISCONNECTED)
            awaitUi { f.toolbox.access.value?.deviceChanged == true }
            assertFalse(f.toolbox.access.value!!.connected)
            files.delete(oldFile)
            apps.act(oldApp, ToolboxAppAction.CLEAR_DATA)
            awaitUi { errors.size == 2 }
            assertTrue(f.root.fileCalls.isEmpty() && f.root.appCalls.isEmpty())
            f.toolbox.confirmDevice()
            awaitUi { f.toolbox.access.value?.connected == true }
            files.delete(oldFile)
            apps.act(oldApp, ToolboxAppAction.CLEAR_DATA)
            awaitUi { errors.size == 4 }
            assertTrue(f.root.fileCalls.isEmpty() && f.root.appCalls.isEmpty())
            runBlocking {
                f.toolbox
                    .backend()
                    .file(ToolboxFileRequest(ToolboxFileAction.MAKE_DIRECTORY, "/sdcard/new"))
            }
            assertEquals(1, f.root.fileCalls.size)
        }
    }

    @Test
    fun pickedDisplayNameCannotEscapeTheShownUploadDirectory() {
        Fixture().use { f ->
            val source =
                File.createTempFile("review-picked-", ".bin").apply {
                    writeBytes(byteArrayOf(1, 2, 3))
                }
            try {
                MaliciousNameProvider.source = source
                val provider =
                    MaliciousNameProvider().apply {
                        attachInfo(
                            f.context,
                            ProviderInfo().apply {
                                authority = "advanced.review"
                                exported = true
                            },
                        )
                    }
                ShadowContentResolver.registerProviderInternal("advanced.review", provider)
                val uri = Uri.parse("content://advanced.review/selected")
                assertNotNull(runBlocking { f.documents.describe(uri) })
                f.documents.open(uri).use { assertEquals(3, it.readBytes().size) }
                val files = FileManagerViewModel(f.dependencies, f.toolbox, f.documents)
                files.open("/sdcard/Download")
                awaitUi { files.state.value.files != null }
                val error = CompletableDeferred<dev.ticbox.companion.ui.common.UiText>()
                f.dependencies.applicationScope.launch {
                    error.complete(f.dependencies.messages.messages.first())
                }
                files.prepareUpload()
                files.upload(uri)
                awaitUi { error.isCompleted }
                assertEquals(toolboxMessage("invalid_file_name"), runBlocking { error.await() })
                assertTrue(f.root.pushes.isEmpty())
            } finally {
                source.delete()
            }
        }
    }

    @Test
    fun cancelledSlowOpenCannotHideTheReplacementTransfer() {
        Fixture().use { f ->
            val source =
                File.createTempFile("review-open-", ".bin").apply {
                    writeBytes(byteArrayOf(1, 2, 3))
                }
            val secondStarted = CompletableDeferred<Unit>()
            val releaseSecond = CompletableDeferred<Unit>()
            var secondFinished = false
            BlockingOpenProvider.source = source
            BlockingOpenProvider.entered = CountDownLatch(1)
            BlockingOpenProvider.release = CountDownLatch(1)
            BlockingOpenProvider.completed = CountDownLatch(1)
            val provider =
                BlockingOpenProvider().apply {
                    attachInfo(
                        f.context,
                        ProviderInfo().apply {
                            authority = "advanced.review.blocking"
                            exported = true
                        },
                    )
                }
            ShadowContentResolver.registerProviderInternal("advanced.review.blocking", provider)
            f.root.beforePush = { path ->
                if (path.endsWith("/second.bin")) {
                    secondStarted.complete(Unit)
                    releaseSecond.await()
                    secondFinished = true
                }
            }
            val files = FileManagerViewModel(f.dependencies, f.toolbox, f.documents)
            files.open("/sdcard/Download")
            awaitUi { files.state.value.target != null }
            try {
                files.prepareUpload()
                files.upload(Uri.parse("content://advanced.review.blocking/first.bin"))
                awaitUi { BlockingOpenProvider.entered.count == 0L }
                files.cancelTransfer()
                files.prepareUpload()
                files.upload(Uri.parse("content://advanced.review.blocking/second.bin"))
                awaitUi { secondStarted.isCompleted }
                assertEquals("second.bin", files.state.value.transfer?.name)
                BlockingOpenProvider.release.countDown()
                awaitUi { BlockingOpenProvider.completed.count == 0L }
                repeat(10) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(10)
                }
                assertEquals("second.bin", files.state.value.transfer?.name)
                assertFalse(secondFinished)
                releaseSecond.complete(Unit)
                awaitUi { secondFinished }
            } finally {
                BlockingOpenProvider.release.countDown()
                releaseSecond.complete(Unit)
                source.delete()
            }
        }
    }
}

class MaliciousNameProvider : ContentProvider() {
    companion object {
        lateinit var source: File
    }

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ) =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any>("../../escape.bin", 3L))
        }

    override fun openFile(uri: Uri, mode: String) =
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)

    override fun getType(uri: Uri) = "application/octet-stream"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0
}

class BlockingOpenProvider : ContentProvider() {
    companion object {
        lateinit var source: File
        lateinit var entered: CountDownLatch
        lateinit var release: CountDownLatch
        lateinit var completed: CountDownLatch
    }

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ) =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any>(uri.lastPathSegment!!, 3L))
        }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (uri.lastPathSegment == "first.bin") {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).also {
            if (uri.lastPathSegment == "first.bin") completed.countDown()
        }
    }

    override fun getType(uri: Uri) = "application/octet-stream"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0
}
