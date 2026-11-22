package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ToolboxConnectionTest {
    private class Connector(var uid: Int = 0) : ToolboxAgentConnector {
        override var device = "first"
        var identity: String? = null
        var protectedPackages: Set<String> = emptySet()
        var features: Set<ToolboxCapability> = emptySet()
        val opened = mutableListOf<String>()
        val written = mutableListOf<ByteArrayOutputStream>()
        override suspend fun prepare() = Unit
        override suspend fun open(): ToolboxChannel {
            opened += device
            val bytes = ByteArrayOutputStream().also { out ->
                ToolboxProtocol.write(out, ToolboxProtocol.READY, buildJsonObject {
                    put("version", ToolboxProtocol.VERSION); put("deviceId", identity ?: device); put("uid", uid); put("sdk", 33)
                    putJsonArray("protectedPackages") { protectedPackages.forEach { add(it) } }
                    putJsonArray("features") { features.forEach { add(it.name) } }
                })
                ToolboxProtocol.write(out, ToolboxProtocol.RESULT, buildJsonObject { put("success", true) })
            }.toByteArray()
            return object : ToolboxChannel {
                override val input = bytes.inputStream()
                override val output = ByteArrayOutputStream().also { written += it }
                override fun close() = Unit
            }
        }
        override fun close() = Unit
    }
    private class StalledProbe : ToolboxAgentConnector {
        override val device = "watch"
        val waiting = CountDownLatch(1)
        val closed = AtomicBoolean()
        @Volatile private var activeReply: InputStream? = null
        override suspend fun prepare() = Unit
        override suspend fun open(): ToolboxChannel {
            val ready = ByteArrayOutputStream().also { out ->
                ToolboxProtocol.write(out, ToolboxProtocol.READY, buildJsonObject { put("version", ToolboxProtocol.VERSION); put("deviceId", device); put("uid", 0); put("sdk", 33) })
            }.toByteArray().inputStream()
            val release = CountDownLatch(1)
            val reply = object : InputStream() {
                override fun read(): Int { waiting.countDown(); release.await(); return -1 }
                override fun close() { closed.set(true); release.countDown() }
            }
            activeReply = reply
            return object : ToolboxChannel {
                override val input = SequenceInputStream(ready, reply)
                override val output = ByteArrayOutputStream()
                override fun close() { reply.close() }
            }
        }
        override fun close() { activeReply?.close() }
    }
    /** A bridge that answers with [reply], then goes away the way a replaced app does. */
    private class UpdatingBridge(
        private val version: Int = ToolboxProtocol.VERSION, private val uid: Int = 0,
        private val reply: OutputStream.() -> Unit = {},
    ) : ToolboxAgentConnector {
        override val device = "watch"
        val sent = ByteArrayOutputStream()
        private val gone = CountDownLatch(1)
        override suspend fun prepare() = Unit
        override suspend fun open(): ToolboxChannel {
            val ready = ByteArrayOutputStream().also { out ->
                ToolboxProtocol.write(out, ToolboxProtocol.READY, buildJsonObject { put("version", version); put("deviceId", device); put("uid", uid); put("sdk", 33) })
            }.toByteArray().inputStream()
            val after = object : InputStream() {
                private var answer: InputStream? = null
                override fun read(): Int {
                    gone.await()
                    val bytes = answer ?: ByteArrayOutputStream().apply(reply).toByteArray().inputStream().also { answer = it }
                    return bytes.read()
                }
            }
            return object : ToolboxChannel {
                override val input = SequenceInputStream(ready, after)
                override val output = object : OutputStream() {
                    private val frames = ByteArrayOutputStream()
                    override fun write(b: Int) { sent.write(b); frames.write(b); sentEnd() }
                    override fun write(b: ByteArray, off: Int, len: Int) { sent.write(b, off, len); frames.write(b, off, len); sentEnd() }
                    private fun sentEnd() {
                        val all = frames.toByteArray()
                        if (all.size >= 5 && all[all.size - 5] == ToolboxProtocol.END.toByte() && all.takeLast(4).all { it == 0.toByte() }) gone.countDown()
                    }
                }
                override fun close() = gone.countDown()
            }
        }
        override fun close() = gone.countDown()
    }
    private fun apk(size: Int = 3) = ToolboxApk("bridge.apk", size.toLong()) { ByteArray(size).inputStream() }
    @Test fun updatingCarriesTheApkAndTakesThePackageManagersAnswer() = runBlocking { withTimeout(5_000) {
        val bridge = UpdatingBridge(reply = { ToolboxProtocol.write(this, ToolboxProtocol.RESULT, buildJsonObject { put("success", true); put("output", "Success") }) })
        val backend = AgentToolboxBackend(ToolboxBackendKind.ROOT_BRIDGE, this, bridge)
        try {
            val result = backend.updateHost(apk())
            assertTrue(result.success)
            val frames = bridge.sent.toByteArray().inputStream()
            val request = ToolboxProtocol.required(frames)
            assertEquals(ToolboxProtocol.REQUEST, request.type)
            assertEquals("install", request.objectValue().text("op"))
            assertEquals(ToolboxProtocol.DATA, ToolboxProtocol.required(frames).type)
            assertEquals(ToolboxProtocol.END, ToolboxProtocol.required(frames).type)
        } finally { backend.close() }
    } }
    @Test fun aBridgeStoppingAfterEverythingArrivedLeavesTheResultUnknown() = runBlocking { withTimeout(5_000) {
        val backend = AgentToolboxBackend(ToolboxBackendKind.ROOT_BRIDGE, this, UpdatingBridge())
        try { assertEquals("install_result_unknown", assertFailsWith<ToolboxException> { backend.updateHost(apk()) }.code) }
        finally { backend.close() }
    } }
    @Test fun anApkThatCantBeReadIsNotMistakenForAnUpdateInProgress() = runBlocking { withTimeout(5_000) {
        val backend = AgentToolboxBackend(ToolboxBackendKind.ROOT_BRIDGE, this, UpdatingBridge())
        val unreadable = ToolboxApk("bridge.apk", 3) { throw IOException("no") }
        try { assertEquals("bridge_update_interrupted", assertFailsWith<ToolboxException> { backend.updateHost(unreadable) }.code) }
        finally { backend.close() }
    } }
    @Test fun onlyAnOlderUpdatableRootBridgeOnThisDeviceIsUpdated() = runBlocking { withTimeout(5_000) {
        suspend fun refusal(bridge: UpdatingBridge, kind: ToolboxBackendKind = ToolboxBackendKind.ROOT_BRIDGE): String {
            val backend = AgentToolboxBackend(kind, this, bridge)
            try { return assertFailsWith<ToolboxException> { backend.updateHost(apk()) }.code.also { assertEquals(0, bridge.sent.size()) } }
            finally { backend.close() }
        }
        assertEquals("phone_app_outdated", refusal(UpdatingBridge(version = ToolboxProtocol.VERSION + 1)))
        assertEquals("bridge_update_needs_adb", refusal(UpdatingBridge(version = ToolboxProtocol.FIRST_UPDATABLE_VERSION - 1)))
        assertEquals("root_required", refusal(UpdatingBridge(uid = 2000)))
        assertEquals("toolbox_unavailable", refusal(UpdatingBridge(), ToolboxBackendKind.ADB))
        val connector = Connector(); val backend = AgentToolboxBackend(ToolboxBackendKind.ROOT_BRIDGE, this, connector)
        try {
            backend.connect(); connector.device = "other watch"
            assertEquals("device_changed", assertFailsWith<ToolboxException> { backend.updateHost(apk()) }.code)
            assertEquals(0, connector.written.last().size())
        } finally { backend.close() }
    } }
    @Test fun anOlderAgentIsRefusedForToolsWithTheProblemItIsFixedBy() {
        assertNull(agentVersionProblem(4, current = 4, firstUpdatable = 3))
        assertEquals("agent_version_mismatch", agentVersionProblem(3, current = 4, firstUpdatable = 3))
        assertEquals("bridge_update_needs_adb", agentVersionProblem(2, current = 4, firstUpdatable = 3))
        assertEquals("bridge_update_needs_adb", agentVersionProblem(null, current = 4, firstUpdatable = 3))
        assertEquals("phone_app_outdated", agentVersionProblem(5, current = 4, firstUpdatable = 3))
    }
    @Test fun connectionDeadlineIncludesTheProbeReplyAndReportsTimeoutInsteadOfStayingConnecting() = runBlocking { withTimeout(7_000) {
        val connector = StalledProbe()
        val backend = AgentToolboxBackend(ToolboxBackendKind.ROOT_BRIDGE, this, connector, connectTimeoutMillis = 2_000)
        try {
            val error = assertFailsWith<ToolboxException> { backend.connect() }
            assertEquals(0, connector.waiting.count, "The agent sent READY, but never answered the probe")
            assertEquals("timeout", error.code)
            assertEquals(ToolboxPhase.FAILED, backend.state.value.phase)
            assertEquals("timeout", backend.state.value.error)
            assertTrue(connector.closed.get())
        } finally { backend.close() }
    } }
    @Test fun callerCancellationClosesTheProbeWithoutReportingAConnectionFailure() = runBlocking { withTimeout(7_000) {
        val connector = StalledProbe()
        val backend = AgentToolboxBackend(ToolboxBackendKind.ROOT_BRIDGE, this, connector)
        try {
            val attempt = launch { backend.connect() }
            withContext(Dispatchers.IO) { assertTrue(connector.waiting.await(3, TimeUnit.SECONDS)) }
            attempt.cancelAndJoin()
            assertEquals(ToolboxPhase.DISCONNECTED, backend.state.value.phase)
            assertNull(backend.state.value.error)
            assertTrue(connector.closed.get())
        } finally { backend.close() }
    } }
    @Test fun consecutiveConnectRequestsCannotSwitchEndpointsDuringThePreviousProbe() = runBlocking { withTimeout(5_000) {
        val connector = Connector(); val backend = AgentToolboxBackend(ToolboxBackendKind.ROOT_BRIDGE, this, connector)
        try {
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var secondConfigured = false
            val first = launch { backend.connectConfigured { connector.device = "first"; started.complete(Unit); release.await() } }
            started.await()
            val second = launch { backend.connectConfigured { connector.device = "second"; secondConfigured = true } }
            yield(); assertFalse(secondConfigured)
            release.complete(Unit); first.join(); second.join()
            assertEquals(listOf("first", "second"), connector.opened)
            assertEquals("second", backend.state.value.device)
        } finally { backend.close() }
    } }
    @Test fun everyRootOperationChecksUidAndNeverSilentlyFallsBackToShell() = runBlocking { withTimeout(5_000) {
        val connector = Connector(); val backend = AgentToolboxBackend(ToolboxBackendKind.ROOT_BRIDGE, this, connector)
        try {
            backend.connect(); connector.uid = 2000
            assertEquals("root_required", assertFailsWith<ToolboxException> { backend.execute("privileged command") }.code)
            assertEquals(0, connector.written.last().size())
        } finally { backend.close() }
    } }
    @Test fun monitoringReusesTheAdvancedConnectionWithoutInvalidatingItsCurrentLease(): Unit = runBlocking {
        val connector = Connector().apply { features = setOf(ToolboxCapability.HEALTH_MONITORING) }
        val backend = AgentToolboxBackend(ToolboxBackendKind.ROOT_BRIDGE, this, connector)
        try {
            backend.connect()
            val target = backend.state.value.target
            val lease = backend.bind()
            val opened = connector.opened.size
            backend.ensureConnected()
            assertEquals(opened, connector.opened.size)
            assertEquals(target, backend.state.value.target)
            val request = buildJsonObject { put("id", "PRESSURE"); put("value", "true") }
            backend.monitoringSettings(request)
            val sent = ToolboxProtocol.required(connector.written.last().toByteArray().inputStream()).objectValue()
            assertEquals(HealthMonitoringProtocol.OPERATION, sent.text("op"))
            assertEquals(request, sent["args"])
            // This lease would fail if a monitoring read had performed another connect().
            lease.setClipboard("still-current")
        } finally { backend.close() }
    }
    @Test fun monitoringChecksAgentRootCapabilityAndDeviceBeforeWriting(): Unit = runBlocking {
        suspend fun refused(connector: Connector, expected: String) {
            val backend = AgentToolboxBackend(ToolboxBackendKind.ADB, this, connector)
            try {
                backend.connect()
                assertEquals(expected, assertFailsWith<ToolboxException> { backend.monitoringSettings(buildJsonObject { }) }.code)
                assertEquals(0, connector.written.last().size())
            } finally { backend.close() }
        }
        refused(Connector(2000).apply { features = setOf(ToolboxCapability.HEALTH_MONITORING) }, "monitoring_root_required")
        refused(Connector(), "monitoring_bridge_update_required")
        val connector = Connector().apply { features = setOf(ToolboxCapability.HEALTH_MONITORING) }
        val backend = AgentToolboxBackend(ToolboxBackendKind.ROOT_BRIDGE, this, connector)
        try {
            backend.connect(); connector.identity = "another-watch"
            assertEquals("device_changed", assertFailsWith<ToolboxException> { backend.monitoringSettings(buildJsonObject { }) }.code)
            assertEquals(0, connector.written.last().size())
        } finally { backend.close() }
    }
    @Test fun implicitMonitoringConnectCannotAcknowledgeAnAdvancedDeviceChange(): Unit = runBlocking {
        val connector = Connector(); val backend = AgentToolboxBackend(ToolboxBackendKind.ROOT_BRIDGE, this, connector)
        try {
            backend.connect(); backend.disconnect(); connector.identity = "another-watch"; backend.connect()
            val before = backend.state.value; val opened = connector.opened.size
            assertEquals("device_changed", assertFailsWith<ToolboxException> { backend.ensureConnected() }.code)
            assertEquals(before, backend.state.value); assertEquals(opened, connector.opened.size)
        } finally { backend.close() }
    }
    @Test fun closeCannotBeUndoneByDelayedDisconnectOrConnect(): Unit = runBlocking {
        val backend = AgentToolboxBackend(ToolboxBackendKind.ADB, this, Connector(2000))
        backend.close(); backend.disconnect(); assertEquals(ToolboxPhase.CLOSED, backend.state.value.phase)
        assertFailsWith<IllegalStateException> { backend.connect() }
    }
    @Test fun everyOperationChecksTheAgentIdentityBeforeWritingAndOldLeasesCannotFollowReconnects(): Unit = runBlocking {
        val connector = Connector()
        val backend = AgentToolboxBackend(ToolboxBackendKind.ROOT_BRIDGE, this, connector)
        try {
            backend.connect()
            val first = backend.bind()
            connector.device = "different-watch"
            assertEquals("device_changed", assertFailsWith<ToolboxException> { first.stat("/private") }.code)
            assertEquals(0, connector.written.last().size())
            assertEquals(ToolboxPhase.FAILED, backend.state.value.phase)
            backend.connect()
            val channels = connector.opened.size
            assertEquals("connection_replaced", assertFailsWith<ToolboxException> { first.file(ToolboxFileRequest(ToolboxFileAction.DELETE, "/private")) }.code)
            assertEquals(channels, connector.opened.size, "A stale lease must fail before opening another channel")
            assertEquals("different-watch", backend.state.value.target?.deviceId)
        } finally { backend.close() }
    }
    @Test fun sameHardwareAtANewEndpointKeepsItsIdentityButInvalidatesOldOperations(): Unit = runBlocking {
        val connector = Connector().apply { identity = "same-hardware" }
        val backend = AgentToolboxBackend(ToolboxBackendKind.ROOT_BRIDGE, this, connector)
        try {
            backend.connect()
            val previous = backend.bind()
            val identity = backend.state.value.target!!.deviceId
            backend.disconnect(); connector.device = "new-endpoint"; backend.connect()
            assertEquals(identity, backend.state.value.target!!.deviceId)
            assertNull(backend.state.value.previousDeviceId)
            assertNotEquals(previous.state.value.target, backend.state.value.target)
            assertEquals("connection_replaced", assertFailsWith<ToolboxException> { previous.stat("/private") }.code)
        } finally { backend.close() }
    }
    @Test fun reconnectingToAnotherDeviceRequiresAcknowledgementEvenForANewCaller(): Unit = runBlocking {
        val connector = Connector()
        val backend = AgentToolboxBackend(ToolboxBackendKind.ADB, this, connector)
        try {
            backend.connect()
            val original = backend.state.value.target!!
            backend.disconnect(); connector.device = "different-watch"; backend.connect()
            val replacement = backend.state.value.target!!
            assertEquals(original.deviceId, backend.state.value.previousDeviceId)
            val opened = connector.opened.size
            assertEquals("device_changed", assertFailsWith<ToolboxException> { backend.setClipboard("private") }.code)
            assertEquals("device_changed", assertFailsWith<ToolboxException> { backend.bind().setClipboard("private") }.code)
            assertEquals(opened, connector.opened.size)
            assertEquals("connection_replaced", assertFailsWith<ToolboxException> { backend.confirmDevice(original) }.code)
            backend.confirmDevice(replacement)
            assertNull(backend.state.value.previousDeviceId)
            backend.bind().setClipboard("reviewed")
            assertEquals(opened + 1, connector.opened.size)
        } finally { backend.close() }
    }
    @Test fun currentTransportHostIsProtectedBeforeOpeningAnyDestructiveOperation(): Unit = runBlocking {
        val connector = Connector().apply { protectedPackages = setOf(ToolboxProtocol.ROOT_HOST_PACKAGE) }
        val backend = AgentToolboxBackend(ToolboxBackendKind.ROOT_BRIDGE, this, connector)
        try {
            backend.connect()
            val opened = connector.opened.size
            for (action in listOf(ToolboxAppAction.UNINSTALL, ToolboxAppAction.FORCE_STOP, ToolboxAppAction.CLEAR_DATA, ToolboxAppAction.CLEAR_CACHE, ToolboxAppAction.DISABLE, ToolboxAppAction.SUSPEND)) {
                assertEquals("transport_host_protected", assertFailsWith<ToolboxException> { backend.bind().app(ToolboxAppRequest(ToolboxProtocol.ROOT_HOST_PACKAGE, action)) }.code)
            }
            assertEquals("transport_host_protected", assertFailsWith<ToolboxException> { backend.permission(ToolboxProtocol.ROOT_HOST_PACKAGE, "permission", false) }.code)
            assertEquals("transport_host_protected", assertFailsWith<ToolboxException> { backend.appOp(ToolboxProtocol.ROOT_HOST_PACKAGE, "operation", "ignore") }.code)
            assertEquals("transport_host_protected", assertFailsWith<ToolboxException> { backend.backupAppData(ToolboxProtocol.ROOT_HOST_PACKAGE, ByteArrayOutputStream()) }.code)
            assertEquals(opened, connector.opened.size)
        } finally { backend.close() }
    }
}
