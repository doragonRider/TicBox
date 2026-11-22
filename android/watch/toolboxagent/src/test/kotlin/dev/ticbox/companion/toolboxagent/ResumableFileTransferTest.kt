package dev.ticbox.companion.toolboxagent

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Real production client/agent streams and actual files; both transport policies use the same protocol. */
class ResumableFileTransferTest {
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun request(f: AgentBackendIntegrationTest.Fixture, path: Path, bytes: ByteArray, overwrite: Boolean = false) =
        ToolboxUploadRequest(f.backend.state.value.target!!, UUID.randomUUID().toString().replace("-", ""), path.toString(), bytes.size.toLong(), sha(bytes), ToolboxWriteOptions(overwrite = overwrite))
    private suspend fun both(block: suspend CoroutineScope.(AgentBackendIntegrationTest.Fixture, Path) -> Unit) = coroutineScope {
        for (kind in ToolboxBackendKind.entries) {
            val directory = Files.createTempDirectory("resumable-$kind-")
            try { AgentBackendIntegrationTest.Fixture(kind, this).use { it.backend.connect(); block(it, directory) } }
            finally { directory.toFile().deleteRecursively() }
        }
    }
    private suspend fun settled(block: () -> Boolean) = withTimeout(5_000) { while (!block()) delay(10) }
    private fun broken(bytes: ByteArray, after: Int = ToolboxProtocol.CHUNK * 2) = object : InputStream() {
        private var cursor = 0
        override fun read(): Int = error("bulk reads required")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (cursor >= after) throw IOException("test source interrupted")
            val count = minOf(length, after - cursor); bytes.copyInto(buffer, offset, cursor, cursor + count); cursor += count; return count
        }
    }
    @Test fun downloadsResumeAfterInterruptionAndValidateTheEntireContentOnBothBackends() = runBlocking { withTimeout(30_000) { both { f, directory ->
        val bytes = ByteArray(400_333) { (it * 37).toByte() }
        val path = Files.write(directory.resolve("export.json"), bytes)
        val snapshot = f.backend.snapshotFile(path.toString())
        val retained = ByteArrayOutputStream()
        val failing = object : OutputStream() {
            override fun write(value: Int) = error("bulk only")
            override fun write(value: ByteArray, offset: Int, length: Int) {
                if (retained.size() >= 2 * ToolboxProtocol.CHUNK) throw IOException("phone storage interrupted")
                retained.write(value, offset, length)
            }
        }
        assertFailsWith<IOException> { f.backend.pullResumable(snapshot, failing) }
        val offset = retained.size().toLong(); assertTrue(offset in 1 until bytes.size.toLong())
        f.backend.disconnect(); f.backend.connect()
        val progress = mutableListOf<ToolboxProgress>()
        val receipt = f.backend.bind().pullResumable(snapshot, retained, offset, sha(retained.toByteArray()), progress::add)
        assertEquals(offset, receipt.resumedBytes); assertEquals(snapshot.sha256, receipt.sha256)
        assertEquals(offset, progress.first().completedBytes); assertEquals(bytes.size.toLong(), progress.last().completedBytes)
        assertContentEquals(bytes, retained.toByteArray())
        // A missing completion reply can be confirmed using the already complete retained prefix.
        val confirmation = f.backend.pullResumable(snapshot, ByteArrayOutputStream(), snapshot.size, snapshot.sha256)
        assertEquals(snapshot.size, confirmation.resumedBytes)
    } } }
    @Test fun changedSourcesAndMismatchedDownloadPrefixesNeverReportSuccess() = runBlocking { withTimeout(30_000) { both { f, directory ->
        val bytes = ByteArray(200_000) { it.toByte() }; val path = Files.write(directory.resolve("source"), bytes)
        val snapshot = f.backend.snapshotFile(path.toString())
        val output = ByteArrayOutputStream()
        assertEquals("file_resume_mismatch", assertFailsWith<ToolboxException> { f.backend.pullResumable(snapshot, output, 100, sha(byteArrayOf(1))) }.code)
        assertEquals(0, output.size())
        bytes[bytes.lastIndex] = (bytes.last() + 1).toByte(); Files.write(path, bytes)
        assertEquals("file_changed", assertFailsWith<ToolboxException> { f.backend.pullResumable(snapshot, output) }.code)
    } } }
    @Test fun interruptedUploadsRetainBytesAndOriginalDestinationAcrossReconnectThenCommitOnce() = runBlocking { withTimeout(30_000) { both { f, directory ->
        val bytes = ByteArray(350_123) { (it * 17).toByte() }; val destination = Files.writeString(directory.resolve("destination"), "original")
        val request = request(f, destination, bytes, overwrite = true)
        assertFailsWith<IOException> { f.backend.pushResumable(request, { broken(bytes) }) }
        val stage = directory.resolve(".ticbox-transfer-${request.transferId}/data")
        settled { Files.exists(stage) && Files.size(stage) > 0 }
        assertEquals("original", Files.readString(destination))
        f.backend.disconnect(); f.backend.connect()
        // Wait for the disconnected worker to release its file lock.
        var receipt: ToolboxTransferReceipt? = null
        withTimeout(5_000) {
            while (receipt == null) {
                try { receipt = f.backend.bind().pushResumable(request, { bytes.copyOfRange(it.toInt(), bytes.size).inputStream() }) }
                catch (e: ToolboxException) { if (e.code != "file_transfer_busy") throw e; delay(20) }
            }
        }
        assertTrue(receipt!!.resumedBytes > 0); assertContentEquals(bytes, Files.readAllBytes(destination))
        var sourceOpened = false
        assertEquals(bytes.size.toLong(), f.backend.pushResumable(request, { sourceOpened = true; error("must not reopen committed input") }).resumedBytes)
        assertFalse(sourceOpened)
        f.backend.discardUpload(request)
        assertFalse(Files.exists(stage.parent)); assertContentEquals(bytes, Files.readAllBytes(destination))
    } } }
    @Test fun invalidUploadContentAndChangedDestinationsRemainUncommitted() = runBlocking { withTimeout(30_000) { both { f, directory ->
        val bytes = ByteArray(200_000) { (it * 13).toByte() }; val destination = Files.writeString(directory.resolve("destination"), "original")
        val wrong = request(f, destination, bytes, overwrite = true)
        assertEquals("file_checksum_mismatch", assertFailsWith<ToolboxException> { f.backend.pushResumable(wrong, { ByteArray(bytes.size) { 1 }.inputStream() }) }.code)
        assertEquals("original", Files.readString(destination)); f.backend.discardUpload(wrong)
        val partial = request(f, destination, bytes, overwrite = true)
        assertFailsWith<IOException> { f.backend.pushResumable(partial, { broken(bytes) }) }
        Files.writeString(destination, "external edit")
        var opened = false
        withTimeout(5_000) {
            while (true) {
                val error = assertFailsWith<ToolboxException> { f.backend.pushResumable(partial, { opened = true; bytes.inputStream() }) }
                if (error.code == "file_transfer_busy") { delay(20); continue }
                assertEquals("file_changed", error.code); break
            }
        }
        assertFalse(opened); assertEquals("external edit", Files.readString(destination)); f.backend.discardUpload(partial)
    } } }
    @Test fun cancellationKeepsAResumableStageAndExplicitDiscardNeverDeletesTheDestination() = runBlocking { withTimeout(30_000) { both { f, directory ->
        val bytes = ByteArray(300_000) { it.toByte() }; val destination = directory.resolve("destination"); val request = request(f, destination, bytes)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val source = object : InputStream() {
            var prefix = true
            override fun read(): Int { entered.countDown(); release.await(); return -1 }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (prefix) { prefix = false; bytes.copyInto(buffer, offset, 0, length); return length }
                return read()
            }
            override fun close() { release.countDown() }
        }
        val uploading = launch { f.backend.pushResumable(request, { source }) }
        withContext(Dispatchers.IO) { assertTrue(entered.await(3, TimeUnit.SECONDS)) }
        assertEquals("file_transfer_busy", assertFailsWith<ToolboxException> { f.backend.pushResumable(request, { error("must not open") }) }.code)
        uploading.cancelAndJoin()
        assertFalse(Files.exists(destination))
        withTimeout(5_000) { while (true) { try { f.backend.discardUpload(request); break } catch(e: ToolboxException) { if(e.code != "file_transfer_busy") throw e; delay(20) } } }
        assertFalse(Files.exists(directory.resolve(".ticbox-transfer-${request.transferId}")))
    } } }
    @Test fun emptyFilesAndChangedDeviceIdentityAreHandledBeforeOpeningAnySource() = runBlocking { withTimeout(30_000) { both { f, directory ->
        val file = Files.createFile(directory.resolve("empty")); val snapshot = f.backend.snapshotFile(file.toString())
        assertEquals(0L, f.backend.pullResumable(snapshot, ByteArrayOutputStream()).bytes)
        val request = request(f, directory.resolve("new-empty"), byteArrayOf())
        assertEquals(0L, f.backend.pushResumable(request, { byteArrayOf().inputStream() }).bytes)
        f.backend.discardUpload(request)
        val changed = request.target.copy(deviceId = "another-device")
        assertEquals("device_changed", assertFailsWith<ToolboxException> { f.backend.pushResumable(request.copy(target = changed), { error("must not open") }) }.code)
        assertEquals("device_changed", assertFailsWith<ToolboxException> { f.backend.pullResumable(snapshot.copy(target = changed), ByteArrayOutputStream()) }.code)
    } } }
    @Test fun lostCommitReplyIsRecoveredWithoutSendingTheFileAgain() = runBlocking { withTimeout(30_000) { both { f, directory ->
        val bytes = ByteArray(100_999) { (it * 41).toByte() }; val destination = directory.resolve("committed")
        val request = request(f, destination, bytes)
        val frames = ByteArrayOutputStream().apply {
            bytes.asList().chunked(ToolboxProtocol.CHUNK).forEach { ToolboxProtocol.write(this, ToolboxProtocol.DATA, it.toByteArray()) }
            ToolboxProtocol.write(this, ToolboxProtocol.END)
        }
        val loseResult = object : OutputStream() {
            override fun write(value: Int) = error("bulk only")
            override fun write(value: ByteArray, offset: Int, length: Int) {
                if (value[offset].toInt() == ToolboxProtocol.RESULT) throw EOFException("lost commit reply")
            }
        }
        val args = buildJsonObject {
            put("transferId", request.transferId); put("path", request.path); put("size", request.size); put("sha256", request.sha256); put("overwrite", false)
        }
        assertFailsWith<EOFException> { AgentFileTransfers(AgentWire(frames.toByteArray().inputStream(), loseResult)).push(args) }
        assertContentEquals(bytes, Files.readAllBytes(destination))
        val receipt = f.backend.pushResumable(request, { error("committed source must not be read again") })
        assertEquals(bytes.size.toLong(), receipt.resumedBytes); f.backend.discardUpload(request)
    } } }

}
