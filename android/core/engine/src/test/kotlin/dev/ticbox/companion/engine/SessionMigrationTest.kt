@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.ticbox.companion.engine

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import java.io.IOException
import kotlin.test.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*

/** Synthetic framed peers exercise physical reconnects; these are not device observations. */
class SessionMigrationTest {
    @Test fun `migration resumes the same physical connection only after empty accounts confirmation`() = runTest {
        val f = Fixture(this)
        val first = f.start()
        first.receive(connect(migrating = true))
        runCurrent()
        assertTrue(first.closed)
        assertEquals(ConnectionPhase.RETRYING, f.engine.state.value.phase)
        assertTrue(f.ready.isEmpty())
        val transfer = f.retry()
        val local = WearMessages.connectOrNull(transfer.envelopes().single())!!
        assertEquals(LOCAL.nodeId, local.nodeId)
        assertEquals(LOCAL.networkId, local.networkId)
        assertEquals(true, local.isMigrating)
        transfer.receive(connect(migrating = true))
        runCurrent()
        assertEquals(ConnectionPhase.NEGOTIATING, f.engine.state.value.phase)
        assertEquals(listOf(MigrationProtocol.GET_ACCOUNTS), transfer.operations())
        // An unsolicited completion, including one before the inventory, is insufficient.
        transfer.receive(account(MigrationProtocol.REMOVE_ACCOUNTS_CONFIRM))
        runCurrent()
        assertEquals(ConnectionPhase.NEGOTIATING, f.engine.state.value.phase)
        transfer.receive(account(MigrationProtocol.ACCOUNTS_RESPONSE))
        runCurrent()
        assertEquals(listOf(1, 3), transfer.operations())
        assertTrue(transfer.controls().all { it.accountMatching!!.accounts.isEmpty() })
        assertTrue(f.ready.isEmpty())
        assertTrue(f.envelopes.isEmpty())
        transfer.receive(account(MigrationProtocol.REMOVE_ACCOUNTS_CONFIRM))
        runCurrent()
        assertEquals(ConnectionPhase.LINK_READY, f.engine.state.value.phase)
        assertEquals(listOf(2L), f.ready)
        assertFalse(transfer.closed)
        assertEquals(2, f.transports.size)
        assertEquals(1, transfer.envelopes().count { WearMessages.connectOrNull(it) != null })
        f.engine.sendMessage("/same-stream", byteArrayOf(1))
        assertEquals(2, f.transports.size)
        assertEquals(1, transfer.envelopes().sumOf { WearMessages.decodeRpcs(it).size })
        assertEquals(1, f.loads)

        transfer.end(); runCurrent()
        val normal = f.retry()
        val verified = WearMessages.connectOrNull(normal.envelopes().single())!!
        assertEquals(LOCAL.nodeId, verified.nodeId)
        assertEquals(LOCAL.networkId, verified.networkId)
        assertFalse(verified.isMigrating == true)
        normal.receive(connect()); runCurrent()
        assertEquals(listOf(2L, 3L), f.ready)
        f.engine.close()
    }

    @Test fun `existing watch accounts stop negotiation without a deletion request or private diagnostics`() = runTest {
        val f = Fixture(this)
        val transfer = f.enterTransfer()
        val inventory = GmsStructs.decode("AccountIdentity", GmsStructs.encode("AccountIdentity",
            mapOf("accountId" to "private-account-id", "accountName" to "private@example.invalid")))
        transfer.receive(MigrationProtocol.encode(MigrationControlMessage(5, MigrationAccountMessage(2, listOf(inventory)))))
        runCurrent()
        assertEquals(ConnectionPhase.FAILED, f.engine.state.value.phase)
        assertEquals(FailureKind.PROTOCOL, f.engine.state.value.failure?.kind)
        assertTrue(f.engine.state.value.failure!!.message.contains("requires account matching"))
        assertEquals(listOf(1), transfer.operations())
        assertTrue(transfer.closed)
        assertTrue(f.ready.isEmpty())
        assertFalse(f.notes.toString().contains("private-account-id"))
        assertFalse(f.notes.toString().contains("private@example.invalid"))
        f.engine.close()
    }

    @Test fun `unreadable inventory cannot be mistaken for an empty watch account list`() = runTest {
        val f = Fixture(this)
        val transfer = f.enterTransfer()
        // operation2, but the accounts member has varint wire type instead of a message.
        transfer.receive("a201080805120408021001".chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        runCurrent()
        assertEquals(ConnectionPhase.FAILED, f.engine.state.value.phase)
        assertEquals(listOf(1), transfer.operations())
        assertTrue(f.ready.isEmpty())
        f.engine.close()
    }

    @Test fun `lost early query is retried without cancelling the stream reader`() = runTest {
        val f = Fixture(this)
        val transfer = f.enterTransfer()
        advanceTimeBy(201); runCurrent()
        assertEquals(listOf(1, 1, 1), transfer.operations())
        assertFalse(transfer.closed)
        transfer.receive(account(MigrationProtocol.ACCOUNTS_RESPONSE))
        runCurrent()
        val queries = transfer.operations().count { it == 1 }
        advanceTimeBy(300); runCurrent()
        assertEquals(queries, transfer.operations().count { it == 1 })
        assertEquals(ConnectionPhase.NEGOTIATING, f.engine.state.value.phase)
        transfer.receive(account(MigrationProtocol.REMOVE_ACCOUNTS_CONFIRM))
        runCurrent()
        assertFalse(transfer.closed)
        assertEquals(ConnectionPhase.LINK_READY, f.engine.state.value.phase)
        assertTrue(f.notes.any { it.event == "empty_account_decision_confirmed" })
        f.engine.close()
    }

    @Test fun `confirmation already read with inventory cannot confirm the resulting empty decision`() = runTest {
        val f = Fixture(this)
        val transfer = f.enterTransfer()
        val early = data(1)
        transfer.receiveBatch(account(MigrationProtocol.ACCOUNTS_RESPONSE),
            account(MigrationProtocol.REMOVE_ACCOUNTS_CONFIRM), early)
        runCurrent()
        assertEquals(listOf(1, 3), transfer.operations())
        assertEquals(ConnectionPhase.NEGOTIATING, f.engine.state.value.phase)
        assertTrue(f.ready.isEmpty())
        assertTrue(f.envelopes.isEmpty())
        assertTrue(f.notes.any { it.event == "unsolicited_confirmation_ignored" })
        assertFalse(f.notes.any { it.event == "empty_account_decision_confirmed" })

        val confirmed = data(2)
        transfer.receiveBatch(account(MigrationProtocol.REMOVE_ACCOUNTS_CONFIRM), confirmed)
        runCurrent()
        assertEquals(ConnectionPhase.LINK_READY, f.engine.state.value.phase)
        assertFalse(transfer.closed)
        assertEquals(listOf(2L), f.ready)
        val delivered = f.envelopes.filter(DataProtocol::hasData)
        assertEquals(2, delivered.size)
        assertContentEquals(early, delivered[0])
        assertContentEquals(confirmed, delivered[1])
        f.engine.close()
    }

    @Test fun `missing confirmation never releases business handlers and cancellation stops retries`() = runTest {
        val f = Fixture(this)
        val transfer = f.enterTransfer()
        transfer.receive(account(MigrationProtocol.ACCOUNTS_RESPONSE))
        transfer.receive(WearMessages.encodeHeartbeat())
        runCurrent()
        assertEquals(ConnectionPhase.NEGOTIATING, f.engine.state.value.phase)
        advanceTimeBy(1_001); runCurrent()
        assertTrue(transfer.closed)
        assertEquals(ConnectionPhase.RETRYING, f.engine.state.value.phase)
        assertEquals(FailureKind.TIMEOUT, f.engine.state.value.failure?.kind)
        assertTrue(f.ready.isEmpty())
        assertTrue(f.envelopes.isEmpty())
        f.engine.disconnect()
        val attempts = f.transports.size
        advanceTimeBy(10_000); runCurrent()
        assertEquals(attempts, f.transports.size)
        assertEquals(ConnectionPhase.IDLE, f.engine.state.value.phase)
        f.engine.close()
    }

    @Test fun `watch cancellation and remote error cannot be interpreted as migration completion`() = runTest {
        for (message in listOf(
            MigrationControlMessage(MigrationProtocol.MIGRATION_FAILED),
            MigrationControlMessage(MigrationProtocol.MIGRATION_CANCELLED),
            MigrationControlMessage(5, MigrationAccountMessage(MigrationProtocol.CANCEL)),
            MigrationControlMessage(5, MigrationAccountMessage(MigrationProtocol.REMOTE_ERROR, errorMessage = "private remote text")),
        )) {
            val f = Fixture(this)
            val transfer = f.enterTransfer()
            transfer.receive(MigrationProtocol.encode(message))
            runCurrent()
            assertEquals(ConnectionPhase.FAILED, f.engine.state.value.phase)
            assertEquals(FailureKind.PROTOCOL, f.engine.state.value.failure?.kind)
            assertTrue(f.ready.isEmpty())
            assertFalse(f.notes.toString().contains("private remote text"))
            assertFalse(f.notes.any { it.event == "empty_account_decision_confirmed" })
            f.engine.close()
        }
    }

    @Test fun `ordinary CONNECT discovery after promotion avoids another account exchange`() = runTest {
        val f = Fixture(this)
        f.start().receive(connect(migrating = true)); runCurrent()
        val staleMode = f.retry()
        staleMode.receive(connect()); runCurrent()
        assertTrue(staleMode.closed)
        assertTrue(staleMode.controls().isEmpty())
        val normal = f.retry()
        normal.receive(connect()); runCurrent()
        assertEquals(ConnectionPhase.LINK_READY, f.engine.state.value.phase)
        assertFalse(WearMessages.connectOrNull(normal.envelopes().first())!!.isMigrating == true)
        f.engine.close()
    }

    @Test fun `data before confirmation in its batch and afterward is delivered once in stream order`() = runTest {
        val f = Fixture(this)
        val transfer = f.enterTransfer()
        val before = data(1)
        val coalesced = data(2)
        val after = data(3)
        transfer.receive(before)
        transfer.receive(account(MigrationProtocol.ACCOUNTS_RESPONSE))
        runCurrent()
        assertTrue(f.envelopes.isEmpty())
        transfer.receiveBatch(account(MigrationProtocol.REMOVE_ACCOUNTS_CONFIRM), coalesced)
        transfer.receive(after)
        runCurrent()
        assertEquals(ConnectionPhase.LINK_READY, f.engine.state.value.phase)
        assertFalse(transfer.closed)
        assertEquals(listOf(2L), f.ready)
        val delivered = f.envelopes.filter(DataProtocol::hasData)
        assertEquals(3, delivered.size)
        listOf(before, coalesced, after).zip(delivered).forEach { (expected, actual) -> assertContentEquals(expected, actual) }
        assertTrue(f.notes.any { it.event == "data_buffered" })
        f.engine.close()
    }

    @Test fun `business envelopes before migrating CONNECT remain pending until confirmation`() = runTest {
        val f = Fixture(this)
        f.start().receive(connect(migrating = true)); runCurrent()
        val transfer = f.retry()
        val early = data(1)
        transfer.receiveBatch(early, connect(migrating = true), account(MigrationProtocol.ACCOUNTS_RESPONSE))
        runCurrent()
        assertTrue(f.envelopes.isEmpty())
        transfer.receive(account(MigrationProtocol.REMOVE_ACCOUNTS_CONFIRM)); runCurrent()
        assertEquals(ConnectionPhase.LINK_READY, f.engine.state.value.phase)
        assertContentEquals(early, f.envelopes.first())
        assertEquals(1, f.envelopes.count(DataProtocol::hasData))
        f.engine.close()
    }

    @Test fun `record and message fragments survive the account confirmation handoff`() = runTest {
        val f = Fixture(this)
        val transfer = f.enterTransfer()
        transfer.receive(account(MigrationProtocol.ACCOUNTS_RESPONSE)); runCurrent()
        val payload = data(17)
        val pieces = MessagePieces.encode(payload, fragmentBytes = 2, queueId = 73).map(GmsRecord::encode)
        transfer.receiveRaw(pieces.first()); runCurrent()
        assertTrue(f.envelopes.isEmpty())
        // Complete an account confirmation, one more piece and half the following record in
        // the same Bluetooth read. Both decoding layers must retain their partial state.
        val split = pieces[2].size / 2
        transfer.receiveRaw(wire(account(MigrationProtocol.REMOVE_ACCOUNTS_CONFIRM)) + pieces[1] + pieces[2].copyOfRange(0, split))
        runCurrent()
        assertEquals(ConnectionPhase.LINK_READY, f.engine.state.value.phase)
        assertEquals(0, f.envelopes.count(DataProtocol::hasData))
        transfer.receiveRaw(pieces[2].copyOfRange(split, pieces[2].size) + pieces.drop(3).fold(byteArrayOf()) { bytes, record -> bytes + record })
        runCurrent()
        assertFalse(transfer.closed)
        assertContentEquals(payload, f.envelopes.single(DataProtocol::hasData))
        f.engine.close()
    }

    @Test fun `confirmation coalesced with cancellation cannot start business handlers`() = runTest {
        for (stop in stopControls()) {
            val f = Fixture(this)
            val transfer = f.enterTransfer()
            transfer.receive(account(MigrationProtocol.ACCOUNTS_RESPONSE)); runCurrent()
            transfer.receiveBatch(account(MigrationProtocol.REMOVE_ACCOUNTS_CONFIRM), stop, data(5))
            runCurrent()
            assertEquals(ConnectionPhase.FAILED, f.engine.state.value.phase)
            assertEquals(FailureKind.PROTOCOL, f.engine.state.value.failure?.kind)
            assertTrue(f.ready.isEmpty())
            assertTrue(f.envelopes.isEmpty())
            val attempts = f.transports.size
            advanceTimeBy(10_000); runCurrent()
            assertEquals(attempts, f.transports.size)
            f.engine.close()
        }
    }

    @Test fun `watch transfer cancellation and failure still stop a promoted stream`() = runTest {
        for (stop in stopControls()) {
            val f = Fixture(this)
            val transfer = f.enterTransfer()
            transfer.receive(account(MigrationProtocol.ACCOUNTS_RESPONSE))
            transfer.receive(account(MigrationProtocol.REMOVE_ACCOUNTS_CONFIRM)); runCurrent()
            assertEquals(ConnectionPhase.LINK_READY, f.engine.state.value.phase)
            f.envelopes.clear()
            transfer.receiveBatch(stop, data(5)); runCurrent()
            assertEquals(ConnectionPhase.FAILED, f.engine.state.value.phase)
            assertEquals(FailureKind.PROTOCOL, f.engine.state.value.failure?.kind)
            assertTrue(transfer.closed)
            assertTrue(f.envelopes.isEmpty())
            val interrupted = f.events.filterIsInstance<BackendEvent.ConnectionInterrupted>().last()
            assertFalse(interrupted.willRetry)
            assertEquals(2L, interrupted.sessionId)
            f.engine.close()
        }
    }

    @Test fun `association termination stops CONNECT migration and promoted sessions without retry`() = runTest {
        for (stage in 0..3) {
            val f = Fixture(this)
            val transport = if (stage == 0) f.start() else f.enterTransfer()
            if (stage >= 2) { transport.receive(account(MigrationProtocol.ACCOUNTS_RESPONSE)); runCurrent() }
            if (stage == 3) { transport.receive(account(MigrationProtocol.REMOVE_ACCOUNTS_CONFIRM)); runCurrent() }
            f.envelopes.clear()
            val termination = MigrationProtocol.encode(MigrationControlMessage(MigrationProtocol.TERMINATE_ASSOCIATION))
            if (stage == 2) transport.receiveBatch(account(MigrationProtocol.REMOVE_ACCOUNTS_CONFIRM), termination, data(9))
            else transport.receiveBatch(termination, data(9))
            runCurrent()
            val stopped = f.engine.state.value
            assertEquals(ConnectionPhase.FAILED, stopped.phase)
            assertEquals(FailureKind.ASSOCIATION_TERMINATED, stopped.failure?.kind)
            assertEquals(PEER, stopped.peer)
            assertEquals(if (stage == 0) 1L else 2L, stopped.sessionId)
            assertNull(stopped.retryDelayMillis)
            assertTrue(transport.closed)
            assertTrue(f.envelopes.isEmpty())
            if (stage != 3) assertTrue(f.ready.isEmpty())
            val interrupted = f.events.filterIsInstance<BackendEvent.ConnectionInterrupted>().last()
            assertEquals(PEER, interrupted.peer)
            assertEquals(stopped.sessionId, interrupted.sessionId)
            assertEquals(FailureKind.ASSOCIATION_TERMINATED, interrupted.failure.kind)
            assertFalse(interrupted.willRetry)
            val attempts = f.transports.size
            advanceTimeBy(10_000); runCurrent()
            assertEquals(attempts, f.transports.size)
            assertEquals(stopped, f.engine.state.value)
            assertFailsWith<NotConnectedException> { f.engine.sendMessage("/after-stop", byteArrayOf()) }
            f.engine.close()
        }
    }

    @Test fun `association termination is retained without a live event subscriber`() = runTest {
        val f = Fixture(this, collectEvents = false)
        val transport = f.start()
        transport.receive(connect()); runCurrent()
        transport.receive(MigrationProtocol.encode(MigrationControlMessage(MigrationProtocol.TERMINATE_ASSOCIATION))); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(FailureKind.ASSOCIATION_TERMINATED, f.engine.state.value.failure?.kind)
        assertEquals(ConnectionPhase.FAILED, f.engine.state.value.phase)
        assertEquals(PEER, f.engine.state.value.peer)
        assertEquals(1L, f.engine.state.value.sessionId)
        assertEquals(1, f.transports.size)
        f.engine.close()
    }

    @Test fun `termination in a confirmation batch takes precedence over its pending data budget`() = runTest {
        val f = Fixture(this, config = migrationConfig().copy(maxPreConnectMessages = 3))
        val transfer = f.enterTransfer()
        transfer.receive(account(MigrationProtocol.ACCOUNTS_RESPONSE)); runCurrent()
        transfer.receiveBatch(account(MigrationProtocol.REMOVE_ACCOUNTS_CONFIRM), data(1), data(2),
            MigrationProtocol.encode(MigrationControlMessage(MigrationProtocol.TERMINATE_ASSOCIATION)))
        runCurrent()
        assertEquals(ConnectionPhase.FAILED, f.engine.state.value.phase)
        assertEquals(FailureKind.ASSOCIATION_TERMINATED, f.engine.state.value.failure?.kind)
        assertTrue(f.ready.isEmpty())
        assertTrue(f.envelopes.isEmpty())
        assertFalse(f.events.filterIsInstance<BackendEvent.ConnectionInterrupted>().last().willRetry)
        f.engine.close()
    }

    @Test fun `queued termination from a replaced peer cannot fail or emit as the new session`() = runTest {
        val f = Fixture(this)
        val old = f.start()
        old.receive(connect()); runCurrent()
        old.receive(MigrationProtocol.encode(MigrationControlMessage(MigrationProtocol.TERMINATE_ASSOCIATION)))
        val other = PeerAddress("AA:BB:CC:DD:EE:FF")
        f.engine.connect(other); runCurrent()
        f.transports.last().receive(connect()); runCurrent()
        assertTrue(old.closed)
        assertEquals(ConnectionPhase.LINK_READY, f.engine.state.value.phase)
        assertEquals(other, f.engine.state.value.peer)
        assertEquals(2L, f.engine.state.value.sessionId)
        assertTrue(f.events.filterIsInstance<BackendEvent.ConnectionInterrupted>().isEmpty())
        f.engine.close()
    }

    @Test fun `negotiation data buffering has both envelope count and total byte limits`() = runTest {
        for (limited in listOf(
            migrationConfig().copy(maxPreConnectMessages = 4),
            migrationConfig().copy(maxPreConnectBytes = connect(migrating = true).size + account(2).size + data(1).size),
        )) {
            val f = Fixture(this, config = limited)
            val transfer = f.enterTransfer()
            transfer.receiveBatch(account(2), data(1), data(2), data(3)); runCurrent()
            assertEquals(ConnectionPhase.FAILED, f.engine.state.value.phase)
            assertEquals(FailureKind.PROTOCOL, f.engine.state.value.failure?.kind)
            assertTrue(transfer.closed)
            assertTrue(f.ready.isEmpty())
            assertTrue(f.envelopes.isEmpty())
            f.engine.close()
        }
    }

    @Test fun `previous node match diagnostics expose booleans without local identity values`() = runTest {
        for (previous in listOf(null, "old-phone-node", LOCAL.nodeId)) {
            val f = Fixture(this)
            f.start().receive(connect(migrating = true)); runCurrent()
            val transfer = f.retry()
            transfer.receive(connect(migrating = true, previousNodeId = previous)); runCurrent()
            val details = f.notes.single { it.event == "negotiation_started" }.details
            assertEquals((previous != null).toString(), details["previousNodePresent"])
            assertEquals((previous == LOCAL.nodeId).toString(), details["previousNodeMatchesLocal"])
            assertTrue(details.values.all { it == "true" || it == "false" })
            f.engine.close()
        }
    }

    @Test fun `immediate EOF after CONNECT keeps backoff while a stable connection resets it`() = runTest {
        val f = Fixture(this)
        var transport = f.start()
        for (expectedDelay in listOf(100L, 200L, 250L)) {
            transport.receive(connect()); runCurrent()
            transport.end(); runCurrent()
            assertEquals(expectedDelay, f.engine.state.value.retryDelayMillis)
            transport = f.retry()
        }
        transport.receive(connect()); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        transport.end(); runCurrent()
        assertEquals(100L, f.engine.state.value.retryDelayMillis)
        f.engine.close()
    }

    private class Fixture(val scope: TestScope, config: SessionConfig = migrationConfig(), collectEvents: Boolean = true) {
        val transports = mutableListOf<Transport>()
        val notes = mutableListOf<ProtocolDiagnostic>()
        val ready = mutableListOf<Long>()
        val envelopes = mutableListOf<ByteArray>()
        val events = mutableListOf<BackendEvent>()
        var loads = 0
        val engine = SessionEngine(
            scope.backgroundScope,
            TransportFactory { Transport().also(transports::add) },
            IdentityStore { loads++; LOCAL },
            ApplicationRoute("test.app", "test-digest"),
            config = config,
            diagnostics = DiagnosticSink(notes::add),
            extensions = listOf(object : SessionExtension {
                override fun onReady(session: EstablishedSession) { ready.add(session.sessionId) }
                override suspend fun onEnvelope(session: EstablishedSession, envelope: ByteArray) { envelopes.add(envelope) }
            }),
            monotonicMillis = { scope.testScheduler.currentTime },
        )
        init {
            if (collectEvents) scope.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                engine.events.collect { events.add(it) }
            }
        }
        suspend fun start(): Transport { engine.connect(PEER); scope.runCurrent(); return transports.last() }
        fun retry(): Transport {
            scope.advanceTimeBy(checkNotNull(engine.state.value.retryDelayMillis)); scope.runCurrent()
            return transports.last()
        }
        suspend fun enterTransfer(): Transport {
            start().receive(connect(migrating = true)); scope.runCurrent()
            return retry().also { it.receive(connect(migrating = true)); scope.runCurrent() }
        }
    }

    private class Transport : DuplexTransport {
        val input = Channel<ByteArray>(Channel.UNLIMITED)
        private val writes = mutableListOf<ByteArray>()
        var closed = false
        fun receive(envelope: ByteArray) { input.trySend(wire(envelope)).getOrThrow() }
        fun receiveRaw(bytes: ByteArray) { input.trySend(bytes).getOrThrow() }
        fun receiveBatch(vararg envelopes: ByteArray) { receiveRaw(envelopes.fold(byteArrayOf()) { bytes, envelope -> bytes + wire(envelope) }) }
        fun end() { input.close() }
        override suspend fun read(): ByteArray? = input.receiveCatching().getOrNull()
        override suspend fun write(bytes: ByteArray) {
            if (closed) throw IOException("Transport closed")
            writes.add(bytes.copyOf())
        }
        override fun close() { closed = true; input.close() }
        fun envelopes(): List<ByteArray> {
            val decoder = GmsRecordDecoder(); val assembler = MessageAssembler()
            return writes.flatMap { decoder.push(it) }.mapNotNull { assembler.accept(it) }
        }
        fun controls() = envelopes().mapNotNull { MigrationProtocol.decode(WearMessages.decode(it)) }
        fun operations() = controls().map { it.accountMatching!!.operation }
    }

    companion object {
        private val LOCAL = NodeIdentity("phone-node", "phone-network", "Phone", 123L)
        private val PEER = PeerAddress("11:22:33:44:55:66")
        private fun connect(migrating: Boolean = false, previousNodeId: String? = if (migrating) "old-phone-node" else null) = WearMessages.encodeConnect(
            ConnectMessage("watch-node", "Watch", isMigrating = migrating, previousNodeId = previousNodeId),
        )
        private fun data(sequence: Long) = GmsStructs.encode("WearMessage", mapOf("syncStart" to mapOf("version" to 2, "receivedSequenceId" to sequence)))
        private fun stopControls() = listOf(
            MigrationProtocol.encode(MigrationControlMessage(MigrationProtocol.MIGRATION_FAILED)),
            MigrationProtocol.encode(MigrationControlMessage(MigrationProtocol.MIGRATION_CANCELLED)),
            account(MigrationProtocol.CANCEL), account(MigrationProtocol.REMOTE_ERROR),
        )
        private fun migrationConfig() = SessionConfig(initialReconnectDelayMillis = 100, maxReconnectDelayMillis = 250,
            reconnectJitterRatio = 0.0, migrationTimeoutMillis = 1_000, migrationQueryIntervalMillis = 100)
        private fun account(operation: Int) = MigrationProtocol.accountOperation(operation)
        private fun wire(envelope: ByteArray) = GmsRecord.encode(MessagePieces.encode(envelope).single())
    }
}
