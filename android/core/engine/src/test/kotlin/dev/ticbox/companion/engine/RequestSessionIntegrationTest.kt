@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.ticbox.companion.engine

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.data.*
import dev.ticbox.companion.engine.pairing.WatchUnpairEngine
import dev.ticbox.companion.protocol.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*

/** Exercises requests on the real framed reader alongside Channels and DataApi extensions. */
class RequestSessionIntegrationTest {
    private class Wire : DuplexTransport {
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val outgoing = mutableListOf<ByteArray>()
        override suspend fun read() = incoming.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) { outgoing += bytes.copyOf() }
        override fun close() { incoming.close() }
        fun envelope(bytes: ByteArray) { for (piece in MessagePieces.encode(bytes)) check(incoming.trySend(GmsRecord.encode(piece)).isSuccess) }
        fun envelopes(): List<ByteArray> {
            val decoder = GmsRecordDecoder(); val assembler = MessageAssembler()
            return outgoing.flatMap { decoder.push(it) }.mapNotNull { assembler.accept(it) }
        }
        fun requests(): List<RpcMessage> = envelopes().flatMap(WearMessages::decodeRpcs)
    }
    private class Rig(val test: TestScope, extensions: List<SessionExtension> = emptyList()) {
        val wires = mutableListOf<Wire>()
        val notes = mutableListOf<ProtocolDiagnostic>()
        val engine = SessionEngine(test.backgroundScope, TransportFactory { Wire().also(wires::add) }, IdentityStore { LOCAL }, APP,
            config = SessionConfig(initialReconnectDelayMillis = 100, maxReconnectDelayMillis = 100, reconnectJitterRatio = 0.0),
            diagnostics = DiagnosticSink { notes += it }, extensions = extensions, rpcDispatcher = StandardTestDispatcher(test.testScheduler))
        val wire get() = wires.last()
        suspend fun ready(peerWireVersion: Int = 1) {
            engine.connect(PEER); test.runCurrent()
            wire.envelope(WearMessages.encodeConnect(ConnectMessage("watch", "Watch", wireVersion = peerWireVersion))); test.runCurrent()
            assertEquals(ConnectionPhase.LINK_READY, engine.state.value.phase)
        }
        fun response(to: RpcMessage, body: ByteArray, route: ApplicationRoute = APP, source: String? = "watch") = wire.envelope(WearMessages.encodeRpc(
            RpcMessage(99, route.packageName, route.packageDigest, LOCAL.nodeId, "/different-response-path", body,
                sourceNodeId = source, responseCorrelationToken = token(to.generation ?: 0, to.requestId), kind = RpcKind.SERVICE),
        ))
    }

    @Test fun `framed CONNECT negotiation selects the asset format independently of SyncStart version`() = runTest {
        for ((advertisedVersion, negotiatedVersion) in listOf(0 to 0, 2 to 1)) {
            val data = DataSyncEngine(backgroundScope, MemoryDataSyncStore())
            val rig = Rig(this, listOf(object : SessionExtension {
                override fun onReady(session: EstablishedSession) {
                    assertEquals(negotiatedVersion, session.negotiatedWireVersion)
                    data.attach(DataSyncSession(session.peer, session.sessionId, session.localIdentity.nodeId, session.remoteNodeId,
                        session.remoteNetworkId ?: session.localIdentity.networkId, session.route,
                        sendEnvelope = { session.sendEnvelope(it) }, onResyncRequired = session::requestReconnect,
                        negotiatedWireVersion = session.negotiatedWireVersion))
                }
                override suspend fun onEnvelope(session: EstablishedSession, envelope: ByteArray) { data.acceptInbound(session.sessionId, envelope) }
                override fun onClosed(session: EstablishedSession) { data.detach(session.sessionId) }
            }))
            try {
                rig.ready(advertisedVersion)
                // DataApi version 2 is used on both physical wire versions.
                rig.wire.envelope(DataProtocol.syncStart(emptyMap(), "watch")); runCurrent()
                val scope = DataScope(PEER, LOCAL.nodeId, "watch", LOCAL.networkId)
                val bytes = byteArrayOf(42)
                val asset = data.putAsset(scope, APP, bytes)
                data.publish(scope, APP, DataItemDraft("wear://${LOCAL.nodeId}/asset", assets = listOf(DataAssetReference("0", asset.digest))))
                runCurrent()
                val ownerBytes = rig.wire.envelopes().single { GmsStructs.decode("WearMessage", it)["setAsset"] != null }
                val ownerEnvelope = GmsStructs.decode("WearMessage", ownerBytes)
                val owner = ownerEnvelope["setAsset"] as DecodedStruct
                val pieces = rig.wire.envelopes().mapNotNull { GmsStructs.decode("WearMessage", it)["filePiece"] as? DecodedStruct }
                if (negotiatedVersion == 0) {
                    assertContentEquals(bytes, owner["inlineData"] as ByteArray)
                    assertNotEquals(true, ownerEnvelope["hasFile"])
                    assertTrue(pieces.isEmpty())
                } else {
                    assertNull(owner["inlineData"])
                    assertEquals(true, ownerEnvelope["hasFile"])
                    val piece = pieces.single()
                    assertEquals(DataProtocol.digest(ownerBytes), piece["ownerMessageDigest"])
                    assertContentEquals(bytes, piece["data"] as ByteArray)
                    assertEquals(true, piece["finalPiece"])
                    assertEquals(asset.digest, piece["fileDigest"])
                }
                rig.wire.envelope(DataProtocol.ackAsset(asset.digest)); runCurrent()
                assertEquals(0, data.state.value.pendingAssetSends)
            } finally { rig.engine.close(); data.close() }
        }
    }

    @Test fun `response uses session token and namespace without requiring equal response path`() = runTest {
        val rig = Rig(this); rig.ready()
        val id = rig.engine.state.value.sessionId
        val reply = async { rig.engine.request(PEER, id, "/query", byteArrayOf(1), route = GMS) }; runCurrent()
        val request = rig.wire.requests().single()
        assertEquals(RpcKind.SERVICE, request.kind); assertEquals(true, request.expectsResponse)
        assertEquals(GMS.packageName, request.packageName)
        rig.response(request, byteArrayOf(3), APP); runCurrent(); assertFalse(reply.isCompleted)
        rig.response(request, byteArrayOf(3), GMS, "other-node"); runCurrent(); assertFalse(reply.isCompleted)
        // Missing source is interpreted as the peer bound to this physical connection.
        rig.response(request, byteArrayOf(7), GMS, ""); runCurrent()
        assertContentEquals(byteArrayOf(7), reply.await())
        rig.engine.close()
    }

    @Test fun `timeout and link replacement release pending requests and old leases cannot send`() = runTest {
        val sessions = mutableListOf<EstablishedSession>()
        val rig = Rig(this, listOf(object : SessionExtension { override fun onReady(session: EstablishedSession) { sessions += session } }))
        rig.ready(); val id = rig.engine.state.value.sessionId
        val timeout = async { runCatching { rig.engine.request(PEER, id, "/query", byteArrayOf(), 50) } }
        runCurrent(); advanceTimeBy(51); runCurrent(); assertTrue(timeout.await().isFailure)
        val pending = async { runCatching { rig.engine.request(PEER, id, "/query", byteArrayOf()) } }
        runCurrent(); rig.wire.incoming.close(); runCurrent()
        assertIs<NotConnectedException>(pending.await().exceptionOrNull())
        advanceTimeBy(100); runCurrent()
        rig.wire.envelope(WearMessages.encodeConnect(ConnectMessage("watch", "Watch"))); runCurrent()
        assertEquals(2, sessions.size)
        assertFailsWith<NotConnectedException> { sessions.first().sendEnvelope(WearMessages.encodeHeartbeat()) }
        sessions.first().requestReconnect("late callback")
        assertEquals(ConnectionPhase.LINK_READY, rig.engine.state.value.phase)
        rig.engine.close()
    }

    @Test fun `inbound handler is asynchronous duplicate requests replay actual reply and foreign app cannot invoke`() = runTest {
        val rig = Rig(this); var calls = 0; val gate = CompletableDeferred<Unit>()
        val registration = rig.engine.registerRequestHandler("DATA_SYNC_PROGRESS", GMS) { input ->
            calls++; gate.await(); assertEquals(GMS, input.route); byteArrayOf(8)
        }
        rig.ready()
        fun incoming(route: ApplicationRoute = GMS) = WearMessages.encodeRpc(RpcMessage(31, route.packageName, route.packageDigest,
            LOCAL.nodeId, "DATA_SYNC_PROGRESS", byteArrayOf(2), "watch", expectsResponse = true, generation = 7, kind = RpcKind.SERVICE))
        rig.wire.envelope(incoming(APP)); rig.wire.envelope(incoming()); rig.wire.envelope(incoming()); runCurrent()
        assertEquals(1, calls); assertTrue(rig.wire.requests().isEmpty())
        // Handler waiting for data must not occupy the physical reader/writer.
        rig.engine.sendMessage("/ordinary", byteArrayOf(4)); runCurrent()
        gate.complete(Unit); runCurrent()
        val response = rig.wire.requests().last()
        assertEquals(token(7, 31), response.responseCorrelationToken)
        assertContentEquals(byteArrayOf(8), response.payload)
        rig.wire.envelope(incoming()); runCurrent()
        assertEquals(1, calls); assertEquals(2, rig.wire.requests().count { it.responseCorrelationToken != null })
        registration.close(); rig.engine.close()
    }

    @Test fun `session bound account messages and permission channel cannot cross a reconnect`() = runTest {
        val rig = Rig(this); rig.ready()
        val old = rig.engine.state.value.sessionId
        rig.wire.incoming.close(); runCurrent(); advanceTimeBy(100); runCurrent()
        rig.wire.envelope(WearMessages.encodeConnect(ConnectMessage("watch", "Watch"))); runCurrent()
        val fresh = rig.engine.state.value.sessionId
        assertNotEquals(old, fresh)
        assertFailsWith<NotConnectedException> { rig.engine.sendMessage(PEER, old, "/companion/send_companion_info", byteArrayOf(1)) }
        assertFailsWith<NotConnectedException> { rig.engine.openChannel(PEER, old, "/cdm/channel/permission_sync") }
        assertTrue(rig.wire.requests().isEmpty())
        rig.engine.sendMessage(PEER, fresh, "/companion/send_companion_info", byteArrayOf(2))
        assertContentEquals(byteArrayOf(2), rig.wire.requests().single().payload)
        rig.engine.close()
    }

    @Test fun `framed malformed protobuf is logged while next valid response and extensions survive`() = runTest {
        var closed = 0; var observed = 0
        val rig = Rig(this, listOf(object : SessionExtension {
            override suspend fun onEnvelope(session: EstablishedSession, envelope: ByteArray) { observed++ }
            override fun onClosed(session: EstablishedSession) { closed++ }
        }))
        rig.ready()
        val pending = async { rig.engine.request(PEER, rig.engine.state.value.sessionId, "/query", byteArrayOf()) }; runCurrent()
        rig.wire.envelope(byteArrayOf(0))
        rig.response(rig.wire.requests().last(), byteArrayOf(5)); runCurrent()
        assertContentEquals(byteArrayOf(5), pending.await())
        assertTrue(rig.notes.any { it.event == "malformed_envelope" })
        assertTrue(observed >= 2)
        rig.engine.disconnect(); runCurrent(); rig.engine.close()
        assertEquals(1, closed)
    }

    @Test fun `malformed RPC sibling does not hide DataApi payload from durable recovery handler`() = runTest {
        val dataEnvelopes = mutableListOf<ByteArray>()
        val rig = Rig(this, listOf(object : SessionExtension {
            override suspend fun onEnvelope(session: EstablishedSession, envelope: ByteArray) {
                if (DataProtocol.hasData(envelope)) dataEnvelopes += envelope
            }
        }))
        rig.ready()
        val item = DataProtocol.wrapDataItem(DataProtocol.encodeItem(DataWireItem(DataAppKey(APP.packageName, APP.packageDigest),
            "wear://watch/data", 1, "watch", false, byteArrayOf(3))))
        // Valid root framing with an empty field18 RpcRequest that lacks required fields.
        rig.wire.envelope(item + byteArrayOf(0x92.toByte(), 1, 0)); runCurrent()
        assertEquals(1, dataEnvelopes.size)
        assertTrue(rig.notes.any { it.event == "malformed_envelope" })
        assertEquals(ConnectionPhase.LINK_READY, rig.engine.state.value.phase)
        rig.engine.close()
    }

    @Test fun `unassignable AppKey is diagnosed without breaking pending valid requests`() = runTest {
        val rig = Rig(this); rig.ready()
        val pending = async { rig.engine.request(PEER, rig.engine.state.value.sessionId, "/query", byteArrayOf()) }; runCurrent()
        for (packageName in listOf("", " ", "x".repeat(257))) {
            rig.wire.envelope(WearMessages.encodeRpc(RpcMessage(4, packageName, "", LOCAL.nodeId, "/query", byteArrayOf(),
                "watch", kind = RpcKind.SERVICE)))
        }
        rig.response(rig.wire.requests().single(), byteArrayOf(9)); runCurrent()
        assertContentEquals(byteArrayOf(9), pending.await())
        assertEquals(ConnectionPhase.LINK_READY, rig.engine.state.value.phase)
        assertEquals(3, rig.notes.count { it.event == "rpc_route_unassigned" })
        rig.engine.close()
    }

    @Test fun `support probe survives a framed replay burst larger than the data queue with slow storage`() = runTest {
        val memory = MemoryDataSyncStore()
        val slow = object : DataSyncStore by memory {
            override suspend fun applyRemote(record: StoredDataItem): RemoteApplyResult {
                delay(10); return memory.applyRemote(record)
            }
        }
        val data = DataSyncEngine(backgroundScope, slow, DataSyncConfig(inboundQueueCapacity = 2))
        val unpair = WatchUnpairEngine(backgroundScope,
            requestSnapshot = { data.requestRemoteSnapshot(it.scope, it.sessionId) },
            cleanup = { error("A support check must not unpair") }, identityTimeoutMillis = 5_000)
        val observer = data.observeInboundItems(unpair::offerRemoteItem)
        val dataScope = DataScope(PEER, LOCAL.nodeId, "watch", LOCAL.networkId)
        val rig = Rig(this, listOf(object : SessionExtension {
            override fun onReady(session: EstablishedSession) {
                data.attach(DataSyncSession(session.peer, session.sessionId, LOCAL.nodeId, "watch", LOCAL.networkId, APP,
                    sendEnvelope = { session.sendEnvelope(it) }, onResyncRequired = session::requestReconnect,
                    negotiatedWireVersion = session.negotiatedWireVersion))
                unpair.attach(CompanionFeatureSession(session.sessionId, dataScope, APP,
                    send = { _, _, _ -> error("A support check must not send an unpair command") },
                    request = { _, _, _, _ -> error("No RequestApi in this probe") },
                    open = { _, _ -> error("No Channel in this probe") }))
            }
            override suspend fun onEnvelope(session: EstablishedSession, envelope: ByteArray) {
                data.acceptInbound(session.sessionId, envelope)
            }
            override fun onClosed(session: EstablishedSession) {
                data.detach(session.sessionId); unpair.detach(session.sessionId)
            }
        }))
        try {
            rig.ready()
            val pending = async { unpair.probe() }; runCurrent()
            for (sequence in 1L..100L) {
                rig.wire.envelope(DataProtocol.wrapDataItem(DataProtocol.encodeItem(DataWireItem(
                    DataAppKey(APP.packageName, APP.packageDigest), "wear://watch/item/$sequence", sequence, "watch", data = byteArrayOf(1)))))
            }
            val homeInfo = DataMapCodec.encode(mapOf(
                "HOME_VERSION_CODE" to DataMapValue.IntNumber(790_427_547),
                "HOME_VERSION_NAME" to DataMapValue.Text("1.15.81.626317553")))
            rig.wire.envelope(DataProtocol.wrapDataItem(DataProtocol.encodeItem(DataWireItem(
                DataAppKey(APP.packageName, APP.packageDigest), "wear://watch/settings/home_info", 101, "watch", data = homeInfo))))
            runCurrent()
            assertFalse(pending.isCompleted)
            assertEquals(ConnectionPhase.LINK_READY, rig.engine.state.value.phase)
            advanceTimeBy(2_000); runCurrent()
            assertEquals(WatchUnpairSupport.SUPPORTED, pending.await().support)
            assertEquals(1L, rig.engine.state.value.sessionId)
            assertEquals(1, rig.wires.size)
            assertEquals(101L, memory.cursors(dataScope)["watch"])
            assertEquals(101, memory.items(dataScope, DataItemQuery(limit = 200)).items.size)
            assertEquals(0L, data.state.value.droppedEnvelopes)
            assertFalse(rig.notes.any { it.event == "resync_reconnect_requested" })
            rig.engine.sendMessage("/after-probe", byteArrayOf(1))
            assertEquals("/after-probe", rig.wire.requests().single().path)
        } finally {
            rig.engine.close(); observer.close(); unpair.close(); data.close()
        }
    }

    @Test fun `disconnect cancels an envelope waiting for capacity without holding the lifecycle lock`() = runTest {
        val entered = CompletableDeferred<Unit>(); var closed = 0
        val rig = Rig(this, listOf(object : SessionExtension {
            override suspend fun onEnvelope(session: EstablishedSession, envelope: ByteArray) {
                if (DataProtocol.hasData(envelope)) { entered.complete(Unit); awaitCancellation() }
            }
            override fun onClosed(session: EstablishedSession) { closed++ }
        }))
        rig.ready()
        rig.wire.envelope(DataProtocol.syncStart(emptyMap(), "phone")); runCurrent()
        assertTrue(entered.isCompleted)
        rig.engine.disconnect(); runCurrent()
        assertEquals(ConnectionPhase.IDLE, rig.engine.state.value.phase)
        assertEquals(1, closed)
        rig.engine.close()
    }

    companion object {
        private val PEER = PeerAddress("10:20:30:40:50:60")
        private val LOCAL = NodeIdentity(nodeId = "phone", nodeName = "Phone", networkId = "network", androidId = 3L)
        private val APP = ApplicationRoute("app", "digest")
        private val GMS = ApplicationRoute("com.google.android.gms", "gms-digest")
        private fun token(generation: Int, requestId: Int) = ((generation + 527) * 31) + requestId
    }
}
