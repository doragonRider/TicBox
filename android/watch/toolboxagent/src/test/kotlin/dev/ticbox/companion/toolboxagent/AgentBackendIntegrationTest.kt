package dev.ticbox.companion.toolboxagent

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.toolbox.*
import dev.ticbox.companion.protocol.ToolboxProtocol
import java.io.*
import java.net.*
import java.nio.file.Files
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Production client and agent on actual full-duplex streams; only Android PackageInstaller is fake. */
class AgentBackendIntegrationTest {
    internal open class Packages : PackageCommands {
        val operations = CopyOnWriteArrayList<List<String>>()
        val writes = CopyOnWriteArrayList<ByteArray>()
        var failure: String? = null
        @Volatile var beforeCommit: (() -> Unit)? = null
        @Volatile var commitReply: CapturedCommand? = null
        val finishedCommits = AtomicInteger()
        private val ids = AtomicInteger(20)
        override fun run(command: List<String>, input: ((OutputStream) -> Unit)?, timeoutMillis: Long): CapturedCommand {
            operations += command
            val action = command.getOrNull(2)
            if (action == "install-write") ByteArrayOutputStream().also { input?.invoke(it); writes += it.toByteArray() }
            if (action == "install-commit") {
                beforeCommit?.invoke()
                finishedCommits.incrementAndGet()
                commitReply?.let { return it }
            }
            if (failure == action) return CapturedCommand(1, "Failure [test rejection]")
            return CapturedCommand(0, if (action == "install-create") "Success: created install session [${ids.incrementAndGet()}]" else "Success")
        }
    }
    internal class Fixture(kind: ToolboxBackendKind, scope: CoroutineScope, val packages: Packages = Packages(), suppliedContext: android.content.Context? = null, environment: (AgentEnvironment) -> AgentEnvironment = { it }) : AutoCloseable {
        private val listener = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        private val executor = Executors.newCachedThreadPool()
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val backend = AgentToolboxBackend(kind, scope, object : ToolboxAgentConnector {
            override val device = "loopback-watch"
            override suspend fun prepare() = Unit
            override suspend fun open(): ToolboxChannel = toolboxIo(onDiscard = ToolboxChannel::close) {
                val client = Socket(listener.inetAddress, listener.localPort); client.tcpNoDelay = true
                val server = listener.accept(); server.tcpNoDelay = true; sockets += client; sockets += server
                executor.execute {
                    server.use { runCatching { ToolboxAgentRuntime(AgentWire(it.getInputStream(), it.getOutputStream()),
                        environment(AgentEnvironment(if (kind == ToolboxBackendKind.ROOT_BRIDGE) 0 else 2000, 33, "", "/bin/sh", packages, suppliedContext, deviceId = "loopback-watch"))).run() } }
                    sockets.remove(server)
                }
                object : ToolboxChannel {
                    override val input = client.getInputStream()
                    override val output = client.getOutputStream()
                    override fun close() { sockets.remove(client); client.close() }
                }
            }
            override fun close() {
                // The agent worker may remove its last socket concurrently. Kotlin's toList()
                // singleton fast path reads size before iterator.next(), which can then be empty.
                // Concurrent removals have their own close path; traverse the set directly.
                sockets.forEach { runCatching(it::close) }
                sockets.clear()
            }
        })
        override fun close() { backend.close(); listener.close(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }
    private suspend fun both(block: suspend (Fixture, java.nio.file.Path) -> Unit) = coroutineScope {
        for (kind in ToolboxBackendKind.entries) {
            val root = Files.createTempDirectory("toolbox-$kind-")
            try { Fixture(kind, this).use { fixture -> fixture.backend.connect(); block(fixture, root) } }
            finally { root.toFile().deleteRecursively() }
        }
    }
    @Test fun binaryFilesMetadataSymlinksAndQuotedPathsWorkOnBothBackends() = runBlocking { withTimeout(20_000) { both { f, root ->
        val content = ByteArray(400_321) { (it * 23).toByte() }; val path = root.resolve("手表 ' \$ notes.bin").toString()
        val progress = mutableListOf<ToolboxProgress>()
        f.backend.push(path, content.inputStream(), content.size.toLong(), ToolboxWriteOptions(mode = 384), progress::add)
        assertEquals(content.size.toLong(), progress.last().completedBytes)
        assertEquals(384, f.backend.stat(path).mode and 0xfff)
        val output = ByteArrayOutputStream(); f.backend.pull(path, output, offset = 29)
        assertContentEquals(content.copyOfRange(29, content.size), output.toByteArray())
        f.backend.file(ToolboxFileRequest(ToolboxFileAction.SYMLINK, path, root.resolve("alias").toString()))
        assertEquals(ToolboxFileKind.SYMLINK, f.backend.stat(root.resolve("alias").toString()).kind)
        assertEquals(setOf("alias", "手表 ' \$ notes.bin"), f.backend.files(root.toString()).map { it.name }.toSet())
        f.backend.file(ToolboxFileRequest(ToolboxFileAction.DELETE, root.resolve("alias").toString()))
        assertContentEquals(content, Files.readAllBytes(java.nio.file.Path.of(path)))
        val expected = if (f.backend.state.value.kind == ToolboxBackendKind.ROOT_BRIDGE) 0 else 2000
        assertEquals(expected, f.backend.state.value.capabilities!!.uid)
        assertEquals(expected == 0, ToolboxCapability.PRIVATE_APP_DATA in f.backend.state.value.capabilities!!.features)
    } } }
    @Test fun incompleteOversizedAndCancelledUploadsKeepOriginalAndRemoveTemporaryFiles() = runBlocking { withTimeout(20_000) { both { f, root ->
        val destination = root.resolve("existing"); Files.writeString(destination, "original")
        for ((bytes, declared) in listOf(byteArrayOf(1) to 2L, byteArrayOf(1, 2) to 1L)) {
            assertFailsWith<IOException> { f.backend.push(destination.toString(), bytes.inputStream(), declared, ToolboxWriteOptions(overwrite = true)) }
        }
        waitUntil { Files.list(root).use { it.count() == 1L } }
        assertEquals("original", Files.readString(destination))
        val source = object : InputStream() {
            val waiting = CountDownLatch(1); val released = CountDownLatch(1)
            @Volatile var closed = false
            override fun read(): Int { waiting.countDown(); released.await(); return -1 }
            override fun close() { closed = true; released.countDown() }
        }
        val upload = launch { f.backend.push(destination.toString(), source, 100, ToolboxWriteOptions(overwrite = true)) }
        withContext(Dispatchers.IO) { assertTrue(source.waiting.await(2, TimeUnit.SECONDS)) }
        upload.cancelAndJoin(); assertTrue(source.closed)
        waitUntil { Files.list(root).use { it.count() == 1L } }
        assertEquals("original", Files.readString(destination))
        assertFailsWith<ToolboxException> { f.backend.push(destination.toString(), byteArrayOf().inputStream(), 0) }
    } } }
    @Test fun recursiveCopyRejectsAliasesIntoItsOwnTreeAndDeletesLinksWithoutFollowingThem() = runBlocking { withTimeout(20_000) { both { f, root ->
        val src = Files.createDirectory(root.resolve("src")); Files.writeString(src.resolve("keep"), "keep")
        Files.createSymbolicLink(root.resolve("alias"), src)
        assertFailsWith<ToolboxException> { f.backend.file(ToolboxFileRequest(ToolboxFileAction.COPY, src.toString(), root.resolve("alias/nested").toString(), recursive = true)) }
        val dst = root.resolve("copied")
        f.backend.file(ToolboxFileRequest(ToolboxFileAction.COPY, src.toString(), dst.toString(), recursive = true))
        assertEquals("keep", Files.readString(dst.resolve("keep")))
        f.backend.file(ToolboxFileRequest(ToolboxFileAction.DELETE, root.resolve("alias").toString(), recursive = true))
        assertTrue(Files.exists(src.resolve("keep")))
    } } }
    @Test fun splitInstallCommitsOnceAfterAllPartsAndAbandonsWriteAndCommitFailures() = runBlocking { withTimeout(20_000) { both { f, _ ->
        val parts = listOf(ByteArray(220_000) { it.toByte() }, ByteArray(100_017) { (it * 7).toByte() })
        fun apks() = parts.mapIndexed { i, bytes -> ToolboxApk("untrusted ' split$i.apk", bytes.size.toLong(), bytes::inputStream) }
        assertTrue(f.backend.install(apks()).success)
        assertEquals(2, f.packages.writes.size)
        parts.forEachIndexed { index, bytes -> assertContentEquals(bytes, f.packages.writes[index]) }
        assertEquals(1, f.packages.operations.count { it.getOrNull(2) == "install-commit" })
        assertEquals(0, f.packages.operations.count { it.getOrNull(2) == "install-abandon" })
        for (failure in listOf("install-write", "install-commit")) {
            f.packages.failure = failure
            assertFalse(f.backend.install(apks()).success)
        }
        waitUntil { f.packages.operations.count { it.getOrNull(2) == "install-abandon" } == 2 }
    } } }
    @Test fun packageDataArrivesInNewFoldersAndAgainOnRetryWhileDamageKeepsItsReason() = runBlocking { withTimeout(20_000) { both { f, root ->
        val folder = root.resolve("Android/obb/dev.example").toString()
        val path = "$folder/main.1.dev.example.obb"
        val options = ToolboxWriteOptions(overwrite = true, atomic = true)
        repeat(2) { attempt ->
            val content = ByteArray(300_000 + attempt) { (it * (attempt + 3)).toByte() }
            f.backend.file(ToolboxFileRequest(ToolboxFileAction.MAKE_DIRECTORY, folder, recursive = true))
            f.backend.push(path, content.inputStream(), content.size.toLong(), options)
            assertEquals(content.size.toLong(), f.backend.stat(path).size)
            assertContentEquals(content, Files.readAllBytes(java.nio.file.Path.of(path)))
        }
        assertEquals(listOf("main.1.dev.example.obb"), f.backend.files(folder).map { it.name })
        fun damaged() = object : InputStream() {
            private var left = 150_000
            override fun read() = if (left-- > 0) 1 else throw ToolboxException("package_unreadable")
        }
        val push = assertFailsWith<ToolboxException> { f.backend.push(path, damaged(), 300_001, options) }
        assertEquals("package_unreadable", push.code)
        assertEquals(300_001, f.backend.stat(path).size)
        assertEquals(listOf("main.1.dev.example.obb"), f.backend.files(folder).map { it.name })
        val install = assertFailsWith<ToolboxException> { f.backend.install(listOf(ToolboxApk("base.apk", 300_000) { damaged() })) }
        assertEquals("package_unreadable", install.code)
        waitUntil { f.packages.operations.any { it.getOrNull(2) == "install-abandon" } }
        assertEquals(0, f.packages.operations.count { it.getOrNull(2) == "install-commit" })
    } } }
    @Test fun shortSplitAndDisconnectedInstallAlwaysAbandonTheCreatedPackageSession() = runBlocking { withTimeout(20_000) { both { f, _ ->
        assertFailsWith<IOException> { f.backend.install(listOf(ToolboxApk("bad.apk", 2) { byteArrayOf(1).inputStream() })) }
        waitUntil { f.packages.operations.any { it.getOrNull(2) == "install-abandon" } }
        val source = object : InputStream() {
            val entered = CountDownLatch(1); val released = CountDownLatch(1)
            override fun read(): Int { entered.countDown(); released.await(); return -1 }
            override fun close() { released.countDown() }
        }
        val job = launch { f.backend.install(listOf(ToolboxApk("cancel.apk", 100) { source })) }
        withContext(Dispatchers.IO) { assertTrue(source.entered.await(2, TimeUnit.SECONDS)) }
        job.cancelAndJoin()
        waitUntil { f.packages.operations.count { it.getOrNull(2) == "install-abandon" } == 2 }
    } } }
    @Test fun missingCommitReplyStaysUnknownWithoutAbandoningAPossiblyCommittedSession() = runBlocking { withTimeout(20_000) { both { f, _ ->
        for (reply in listOf(CapturedCommand(124, "", completed = false), CapturedCommand(1, ""), CapturedCommand(0, ""))) {
            f.packages.commitReply = reply
            val error = assertFailsWith<ToolboxException> {
                f.backend.install(listOf(ToolboxApk("base.apk", 1) { byteArrayOf(1).inputStream() }))
            }
            assertEquals("install_result_unknown", error.code)
            assertEquals(0, f.packages.operations.count { it.getOrNull(2) == "install-abandon" })
        }
        assertEquals(3, f.packages.finishedCommits.get())
        f.packages.beforeCommit = { throw IOException("commit reply unavailable") }
        val exception = assertFailsWith<ToolboxException> {
            f.backend.install(listOf(ToolboxApk("base.apk", 1) { byteArrayOf(1).inputStream() }))
        }
        assertEquals("install_result_unknown", exception.code)
        assertEquals(0, f.packages.operations.count { it.getOrNull(2) == "install-abandon" })
    } } }
    @Test fun aCommitAlreadyDispatchedContinuesAfterTheTransportCloses() = runBlocking { withTimeout(20_000) { both { f, _ ->
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        f.packages.beforeCommit = { started.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        val install = async { runCatching { f.backend.install(listOf(ToolboxApk("base.apk", 1) { byteArrayOf(1).inputStream() })) } }
        try {
            withContext(Dispatchers.IO) { assertTrue(started.await(3, TimeUnit.SECONDS)) }
            f.backend.disconnect()
            assertTrue(install.await().isFailure)
            // A commit is allowed to exceed the old blanket kill deadline after disconnection.
            delay(1800)
            assertEquals(0, f.packages.finishedCommits.get())
            assertEquals(0, f.packages.operations.count { it.getOrNull(2) == "install-abandon" })
            release.countDown()
            waitUntil { f.packages.finishedCommits.get() == 1 }
            assertEquals(0, f.packages.operations.count { it.getOrNull(2) == "install-abandon" })
        } finally { release.countDown(); install.cancelAndJoin() }
    } } }
    @Test fun commandStreamsKeepBinaryStdoutSeparateFromStderrAndBoundCapturedBytes() = runBlocking { withTimeout(20_000) { both { f, _ ->
        repeat(12) {
            val result = f.backend.execute("printf '\\000\\377\\012'; printf 'error' >&2; exit 7")
            assertContentEquals(byteArrayOf(0, -1, 10), result.stdout); assertEquals("error", result.stderr.toString(Charsets.UTF_8)); assertEquals(7, result.exitCode)
        }
        val small = f.backend.execute("printf 123456789; printf abcdef >&2", ToolboxCommandOptions(maxCaptureBytes = 5))
        assertEquals(5, small.stdout.size + small.stderr.size); assertTrue(small.truncated)
        f.backend.openCommand("cat").use { session ->
            val bytes = ByteArray(260_000) { (it * 11).toByte() }; val received = ByteArrayOutputStream()
            val reading = launch { session.events.collect { event -> if (event is ToolboxCommandEvent.Stdout) received.write(event.bytes) } }
            session.write(bytes); session.closeInput(); reading.join()
            assertEquals(0, session.awaitExit().code); assertContentEquals(bytes, received.toByteArray())
        }
    } } }
    @Test fun timeoutInterruptsAndSessionCloseHangsUpOwnedCommandsWhileReconnectRetiresPreviousStreams() = runBlocking { withTimeout(20_000) { both { f, root ->
        val timed = f.backend.execute("exec sleep 30", ToolboxCommandOptions(timeoutMillis = 100))
        assertTrue(timed.timedOut); assertEquals(124, timed.exitCode)
        val pidFile = root.resolve("pid")
        val session = f.backend.openCommand("sleep 30 & echo \$! > '${pidFile}'; wait")
        waitUntil { Files.exists(pidFile) && Files.size(pidFile) > 0 }
        val pid = Files.readString(pidFile).trim().toLong()
        session.close()
        waitUntil {
            val state = runCatching { java.io.File("/proc/$pid/stat").readText().substringAfter(") ") }.getOrNull()
            state == null || state.startsWith("Z")
        }
        val stale = f.backend.openCommand("exec sleep 30")
        f.backend.disconnect(); assertEquals(ToolboxPhase.DISCONNECTED, f.backend.state.value.phase)
        assertFailsWith<Exception> { stale.awaitExit() }; stale.close()
        f.backend.connect(); assertTrue(f.backend.execute("printf ready").successful)
    } } }
    @Test fun cancellingIsACatchableInterruptAndForceRequiresItsOwnRequest() = runBlocking { withTimeout(20_000) { both { f, root ->
        val ready = root.resolve("ready"); val interrupted = root.resolve("interrupted")
        val trap = "printf interrupted > '${interrupted}'"
        val session = f.backend.openCommand("trap ${ToolboxProtocol.quote(trap)} INT; trap '' HUP TERM; printf ready > '${ready}'; while :; do sleep 0.05; done")
        val reading = launch { session.events.collect { } }
        val exit = async { session.awaitExit() }
        try {
            waitUntil { Files.exists(ready) }
            session.cancel()
            waitUntil { Files.exists(interrupted) }
            assertFalse(exit.isCompleted, "SIGINT must not become force-stop when the command handles it")
            assertEquals(ToolboxPhase.CONNECTED, f.backend.state.value.phase)
            session.forceStop()
            assertTrue(exit.await().code != 0)
            reading.join()
        } finally { runCatching { session.forceStop() }; session.close(); reading.cancelAndJoin(); exit.cancelAndJoin() }
    } } }
    @Test fun shellRejectsRootOnlyAppManagementInsteadOfClaimingSuccess() = runBlocking { withTimeout(10_000) {
        Fixture(ToolboxBackendKind.ADB, this).use { f ->
            f.backend.connect()
            assertFailsWith<ToolboxException> { f.backend.app(ToolboxAppRequest("test.app", ToolboxAppAction.CLEAR_CACHE)) }
            assertFailsWith<ToolboxException> { f.backend.backupAppData("test.app", ByteArrayOutputStream()) }
        }
    } }
    private suspend fun waitUntil(predicate: () -> Boolean) = withTimeout(5_000) { while (!predicate()) delay(10) }
}
