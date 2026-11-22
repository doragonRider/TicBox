package dev.ticbox.companion.ui.toolbox

import android.net.Uri
import android.os.Looper
import dev.ticbox.companion.api.*
import dev.ticbox.companion.ui.common.UserMessages
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileTransfersTest {
    private fun ByteArray.sha() =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }

    private inner class Backend(val bytes: ByteArray) :
        WatchToolboxBackend by WatchToolboxClient.UNAVAILABLE.localBridge {
        override val state =
            MutableStateFlow(
                ToolboxBackendState(
                    ToolboxBackendKind.ROOT_BRIDGE,
                    ToolboxPhase.CONNECTED,
                    capabilities =
                        ToolboxCapabilities(
                            0,
                            33,
                            setOf(ToolboxCapability.FILES, ToolboxCapability.FILE_RESUME),
                        ),
                    target = ToolboxTarget("watch-a", 1, ToolboxBackendKind.ROOT_BRIDGE),
                )
            )

        override fun bind(target: ToolboxTarget): WatchToolboxBackend =
            super<WatchToolboxBackend>.bind(target)

        val offsets = mutableListOf<Long>()
        val received = java.io.ByteArrayOutputStream()
        var failPull = true
        var failPush = true
        var discarded = 0
        var cancellationCleanup: CompletableDeferred<Unit>? = null
        val enteredCancelableRead = CompletableDeferred<Unit>()

        override suspend fun snapshotFile(path: String) =
            ToolboxFileSnapshot(
                checkNotNull(state.value.target),
                path,
                bytes.size.toLong(),
                bytes.sha(),
                123,
            )

        override suspend fun pullResumable(
            snapshot: ToolboxFileSnapshot,
            output: OutputStream,
            offset: Long,
            prefixSha256: String?,
            progress: (ToolboxProgress) -> Unit,
        ): ToolboxTransferReceipt {
            offsets += offset
            if (offset > 0) assertEquals(bytes.copyOf(offset.toInt()).sha(), prefixSha256)
            if (cancellationCleanup != null && offset == 0L) {
                failPull = false
                output.write(bytes, 0, 137)
                progress(ToolboxProgress(137, bytes.size.toLong()))
                enteredCancelableRead.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { cancellationCleanup!!.await() }
                }
            }
            if (failPull) {
                failPull = false
                output.write(bytes, 0, 137)
                progress(ToolboxProgress(137, bytes.size.toLong()))
                throw ToolboxException("connection_closed")
            }
            output.write(bytes, offset.toInt(), bytes.size - offset.toInt())
            progress(ToolboxProgress(bytes.size.toLong(), bytes.size.toLong()))
            return ToolboxTransferReceipt(bytes.size.toLong(), bytes.sha(), offset)
        }

        override suspend fun pushResumable(
            request: ToolboxUploadRequest,
            open: (Long) -> InputStream,
            progress: (ToolboxProgress) -> Unit,
        ): ToolboxTransferReceipt {
            val offset = received.size().toLong()
            offsets += offset
            open(offset).use { input ->
                if (failPush) {
                    failPush = false
                    received.write(input.readNBytes(137))
                    progress(ToolboxProgress(137, request.size))
                    throw ToolboxException("connection_closed")
                }
                received.write(input.readBytes())
            }
            assertEquals(request.sha256, received.toByteArray().sha())
            return ToolboxTransferReceipt(request.size, request.sha256, offset)
        }

        override suspend fun discardUpload(request: ToolboxUploadRequest) {
            discarded++
        }

        fun reconnect(
            device: String = "watch-a",
            kind: ToolboxBackendKind = ToolboxBackendKind.ROOT_BRIDGE,
        ) {
            state.value = state.value.copy(target = ToolboxTarget(device, 2, kind), kind = kind)
        }
    }

    private class Fixture : AutoCloseable {
        val directory = Files.createTempDirectory("file-transfers-").toFile()
        val data = ByteArray(8193) { (it % 251).toByte() }
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val documents =
            ToolboxDocuments(
                RuntimeEnvironment.getApplication().contentResolver,
                File(directory, "staging"),
            )
        var controller = FileTransferController(scope, documents, UserMessages())

        fun recreate() {
            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            controller = FileTransferController(scope, documents, UserMessages())
        }

        override fun close() {
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue(condition(), "Transfer did not reach the expected state")
    }

    private fun Backend.file() =
        ToolboxFile(
            "/sdcard/example.bin",
            "example.bin",
            ToolboxFileKind.FILE,
            bytes.size.toLong(),
            123,
            384,
            0,
            0,
            target = state.value.target,
        )

    @Test
    fun downloadSurvivesRestartAndUsesOnlyTheMissingSuffixOnTheSameDevice() {
        Fixture().use { f ->
            val backend = Backend(f.data)
            val output = File(f.directory, "download.bin")
            f.controller.download(backend.file(), Uri.fromFile(output)) { backend }
            await { f.controller.state.value?.paused == true }
            assertFalse(output.exists())
            f.recreate()
            await { f.controller.state.value?.paused == true }
            backend.reconnect()
            f.controller.resume({ backend }, {})
            await { output.exists() && f.controller.state.value == null }
            assertContentEquals(f.data, output.readBytes())
            assertEquals(listOf(0L, 137L), backend.offsets)
            assertNull(FileTransferStore(File(f.directory, "staging")).read())
        }
    }

    @Test
    fun downloadDoesNotResumeOnAnotherDeviceOrBackend() {
        Fixture().use { f ->
            val backend = Backend(f.data)
            f.controller.download(backend.file(), Uri.fromFile(File(f.directory, "download.bin"))) {
                backend
            }
            await { f.controller.state.value?.paused == true }
            backend.reconnect("watch-b")
            f.controller.resume({ backend }, {})
            await { f.controller.state.value?.paused == true }
            assertEquals(listOf(0L), backend.offsets)
            backend.reconnect(kind = ToolboxBackendKind.ADB)
            f.controller.resume({ backend }, {})
            await { f.controller.state.value?.paused == true }
            assertEquals(listOf(0L), backend.offsets)
        }
    }

    @Test
    fun failedDocumentSaveCanChooseANewDestinationWithoutDownloadingAgain() {
        Fixture().use { f ->
            val backend = Backend(f.data).apply { failPull = false }
            val bad = File(f.directory, "absent/download.bin")
            f.controller.download(backend.file(), Uri.fromFile(bad)) { backend }
            await { f.controller.state.value?.paused == true }
            assertTrue(f.controller.state.value!!.canChooseDestination)
            val output = File(f.directory, "saved.bin")
            f.controller.saveTo(Uri.fromFile(output)) {
                error("Complete local file doesn't need the remote connection")
            }
            await { output.exists() && f.controller.state.value == null }
            assertContentEquals(f.data, output.readBytes())
            assertEquals(listOf(0L), backend.offsets)
        }
    }

    @Test
    fun uploadKeepsAnImmutableSourceAndResumesAfterRestart() {
        Fixture().use { f ->
            val backend = Backend(f.data)
            val source = File(f.directory, "upload.bin").apply { writeBytes(f.data) }
            val target = checkNotNull(backend.state.value.target)
            f.controller.upload(
                PickedFile(Uri.fromFile(source), source.name, source.length()),
                target,
                "/sdcard/upload.bin",
                false,
                { backend },
                {},
            )
            await { f.controller.state.value?.paused == true }
            source.writeText("changed after upload began")
            f.recreate()
            await { f.controller.state.value?.paused == true }
            backend.reconnect()
            f.controller.resume({ backend }, {})
            await { f.controller.state.value == null }
            assertContentEquals(f.data, backend.received.toByteArray())
            assertEquals(listOf(0L, 137L), backend.offsets)
            assertEquals(1, backend.discarded)
        }
    }

    @Test
    fun offlineDiscardRemovesLocalProgressWithoutTouchingAnotherDevice() {
        Fixture().use { f ->
            val backend = Backend(f.data)
            val source = File(f.directory, "upload.bin").apply { writeBytes(f.data) }
            f.controller.upload(
                PickedFile(Uri.fromFile(source), source.name, source.length()),
                backend.state.value.target!!,
                "/sdcard/upload.bin",
                false,
                { backend },
                {},
            )
            await { f.controller.state.value?.paused == true }
            f.controller.discard { throw ToolboxException("not_connected") }
            await { f.controller.state.value == null }
            assertEquals(0, backend.discarded)
            assertNull(FileTransferStore(File(f.directory, "staging")).read())
        }
    }

    @Test
    fun resumeAndDiscardWaitForCanceledReadersToFinish() {
        Fixture().use { f ->
            val gate = CompletableDeferred<Unit>()
            val backend = Backend(f.data).apply { cancellationCleanup = gate }
            val output = File(f.directory, "download.bin")
            f.controller.download(backend.file(), Uri.fromFile(output)) { backend }
            await { backend.enteredCancelableRead.isCompleted }
            f.controller.pause()
            f.controller.resume({ backend }, {})
            f.controller.discard { backend }
            assertEquals(listOf(0L), backend.offsets)
            assertNotNull(FileTransferStore(File(f.directory, "staging")).read())
            gate.complete(Unit)
            await { f.controller.state.value?.paused == true }
            f.controller.resume({ backend }, {})
            await { output.exists() && f.controller.state.value == null }
            assertContentEquals(f.data, output.readBytes())
            assertEquals(listOf(0L, 137L), backend.offsets)
        }
    }

    @Test
    fun aDamagedCheckpointHasAnExplicitDiscardPath() {
        Fixture().use { f ->
            val directory = File(f.directory, "staging").apply { mkdirs() }
            File(directory, "transfer.json").writeText("incomplete")
            f.recreate()
            await { f.controller.state.value?.recoveryFailed == true }
            f.controller.discard { error("Invalid local metadata cannot authorize remote actions") }
            await { f.controller.state.value == null }
            assertFalse(File(directory, "transfer.json").exists())
            val backend = Backend(f.data).apply { failPull = false }
            val output = File(f.directory, "download.bin")
            f.controller.download(backend.file(), Uri.fromFile(output)) { backend }
            await { output.exists() && f.controller.state.value == null }
            assertContentEquals(f.data, output.readBytes())
        }
    }
}
