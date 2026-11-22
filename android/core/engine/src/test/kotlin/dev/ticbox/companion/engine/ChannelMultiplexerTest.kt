@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.ticbox.companion.engine

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import kotlin.random.Random
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class ChannelMultiplexerTest {
    private class Rig(val test: TestScope, config: ChannelConfig = ChannelConfig(), random: Random = Random(7), unconfined: Boolean = false) {
        val writes = mutableListOf<ChannelRequest>()
        val diagnostics = mutableListOf<ProtocolDiagnostic>()
        var diagnosticCallback: (ProtocolDiagnostic) -> Unit = {}
        var beforeSend: suspend (ChannelRequest) -> Unit = {}
        val mux = ChannelMultiplexer(
            if (unconfined) CoroutineScope(test.backgroundScope.coroutineContext + UnconfinedTestDispatcher(test.testScheduler)) else test.backgroundScope,
            PEER, 42, ROUTE, config, random,
            diagnostics = DiagnosticSink { diagnostics.add(it); diagnosticCallback(it) },
        ) { request, guard -> if (guard()) { beforeSend(request); writes.add(request) } }
        suspend fun open(path: String = "/setup/channel"): WearChannel {
            val opening = test.async { mux.open(path, true) }
            test.runCurrent()
            val control = writes.last { it.control?.type == ChannelCodec.OPEN }.control!!
            mux.receive(ChannelRequest(control = ChannelControl(ChannelCodec.OPEN_ACK, control.channelId, false)))
            test.runCurrent()
            return opening.await()
        }
        fun incoming(id: ULong, path: String = "/incoming", senderRole: Boolean? = true) = mux.receive(ChannelRequest(control = ChannelControl(
            ChannelCodec.OPEN, id, senderRole, ROUTE.packageName, ROUTE.packageDigest, path, allowOverMetered = true,
        )))
        fun data(channel: WearChannel, sequence: Long, bytes: ByteArray, final: Boolean = false) = mux.receive(ChannelRequest(data = ChannelData(
            ChannelHeader(channel.info.id, !channel.info.localIsOpener, sequence), bytes, final,
        )))
        fun ack(channel: WearChannel, sequence: Long, final: Boolean = false) = mux.receive(ChannelRequest(ack = ChannelAck(
            ChannelHeader(channel.info.id, !channel.info.localIsOpener, sequence), final,
        )))
        fun closeRemote(channel: WearChannel, error: Int = 0, digest: String = ROUTE.packageDigest) = mux.receive(ChannelRequest(control = ChannelControl(
            ChannelCodec.CLOSE, channel.info.id, !channel.info.localIsOpener, ROUTE.packageName, digest, closeErrorCode = error,
        )))
        fun dataWrites() = writes.mapNotNull { it.data }
        fun acks() = writes.mapNotNull { it.ack }
    }

    @Test fun `OPEN waits for matching role and ID and emits stock request fields`() = runTest {
        val rig = Rig(this)
        val result = async { rig.mux.open("/setup/channel", true) }
        runCurrent()
        val request = rig.writes.single()
        val control = request.control!!
        assertEquals(0, request.origin)
        assertEquals(0, request.minimumVersion)
        assertEquals(ChannelCodec.OPEN, control.type)
        assertEquals(true, control.fromChannelOpener)
        assertEquals(true, control.allowOverMetered)
        assertEquals(ROUTE.packageName, control.packageName)
        assertEquals(ROUTE.packageDigest, control.packageDigest)
        assertEquals("/setup/channel", control.path)
        assertTrue(control.channelId!! <= Long.MAX_VALUE.toULong())
        assertFalse(result.isCompleted)
        rig.mux.receive(ChannelRequest(control = ChannelControl(ChannelCodec.OPEN_ACK, control.channelId, true)))
        rig.mux.receive(ChannelRequest(control = ChannelControl(ChannelCodec.OPEN_ACK, control.channelId!! + 1u, false)))
        runCurrent()
        assertFalse(result.isCompleted)
        rig.mux.receive(ChannelRequest(control = ChannelControl(ChannelCodec.OPEN_ACK, control.channelId, false)))
        runCurrent()
        val channel = result.await()
        assertEquals(ChannelPhase.OPEN, channel.state.value.phase)
        assertEquals(42L, channel.info.sessionId)
        rig.mux.close()
    }

    @Test fun `empty and blank OPEN paths retain stock defaults and diagnostic context`() = runTest {
        val rig = Rig(this)
        rig.mux.receive(ChannelRequest(control = ChannelControl(
            type = ChannelCodec.OPEN, channelId = 1uL,
            packageName = ROUTE.packageName, packageDigest = ROUTE.packageDigest,
        )))
        rig.incoming(2uL, "  ")
        assertEquals("", rig.mux.accept().info.path)
        assertEquals("  ", rig.mux.accept().info.path)
        val local = rig.open("")
        assertEquals("", local.info.path)
        runCurrent()
        assertEquals(2, rig.diagnostics.count { it.event == "unusual_open_path" })
        assertTrue(rig.diagnostics.any { it.event == "unusual_open_path" && it.details["pathPresent"] == "false" })
        assertEquals(2, rig.writes.count { it.control?.type == ChannelCodec.OPEN_ACK })
        rig.mux.close()
    }

    @Test fun `OPEN retries twice at fifteen seconds then late ACK cannot resurrect it`() = runTest {
        val rig = Rig(this)
        val result = async { runCatching { rig.mux.open("/setup/channel", true) } }
        runCurrent()
        val id = rig.writes.single().control!!.channelId
        advanceTimeBy(14_999); runCurrent(); assertEquals(1, rig.writes.size)
        advanceTimeBy(1); runCurrent(); assertEquals(2, rig.writes.count { it.control?.type == ChannelCodec.OPEN })
        advanceTimeBy(15_000); runCurrent()
        val error = assertIs<ChannelClosedException>(result.await().exceptionOrNull())
        assertEquals(ChannelEndReason.TIMEOUT, error.reason)
        rig.mux.receive(ChannelRequest(control = ChannelControl(ChannelCodec.OPEN_ACK, id, false)))
        runCurrent()
        assertEquals(2, rig.writes.count { it.control?.type == ChannelCodec.OPEN })
        rig.mux.close()
    }

    @Test fun `local IDs are never reused even after an abandoned OPEN`() = runTest {
        val constant = object : Random() { override fun nextBits(bitCount: Int) = 0 }
        val rig = Rig(this, random = constant)
        val first = async { rig.mux.open("/one", true) }
        runCurrent()
        first.cancelAndJoin(); runCurrent()
        val error = assertFailsWith<ChannelClosedException> { rig.mux.open("/two", true) }
        assertEquals(ChannelEndReason.RESOURCE_LIMIT, error.reason)
        rig.mux.close()
    }

    @Test fun `split writes stop for each ACK and retry the same DATA sequence`() = runTest {
        val rig = Rig(this, ChannelConfig(sendChunkBytes = 128))
        val channel = rig.open()
        val body = ByteArray(300) { it.toByte() }
        val result = async { channel.write(body) }
        runCurrent()
        body.fill(0)
        assertEquals(1, rig.dataWrites().size)
        assertEquals(0L, rig.dataWrites()[0].header!!.sequence)
        assertEquals(128, rig.dataWrites()[0].data!!.size)
        advanceTimeBy(18_000); runCurrent()
        assertEquals(2, rig.dataWrites().size)
        assertContentEquals(rig.dataWrites()[0].data, rig.dataWrites()[1].data)
        assertEquals(0L, rig.dataWrites()[1].header!!.sequence)
        rig.ack(channel, 0); runCurrent()
        assertEquals(1L, rig.dataWrites().last().header!!.sequence)
        rig.ack(channel, 1); runCurrent()
        assertEquals(2L, rig.dataWrites().last().header!!.sequence)
        assertEquals(44, rig.dataWrites().last().data!!.size)
        rig.ack(channel, 2); runCurrent()
        result.await()
        assertFalse(channel.state.value.outputEnded)
        rig.mux.close()
    }

    @Test fun `partial application reads delay ACK and preserve defensive payload copies`() = runTest {
        val rig = Rig(this)
        val channel = rig.open()
        val body = "abcdef".toByteArray()
        rig.data(channel, 0, body)
        body.fill(0)
        rig.data(channel, 0, "duplicate".toByteArray())
        runCurrent(); assertTrue(rig.acks().isEmpty())
        assertContentEquals("ab".toByteArray(), channel.read(2))
        runCurrent(); assertTrue(rig.acks().isEmpty())
        assertContentEquals("cdef".toByteArray(), channel.read(20))
        runCurrent()
        assertEquals(1, rig.acks().size)
        assertEquals(0L, rig.acks().single().header!!.sequence)
        assertEquals(true, rig.acks().single().header!!.fromChannelOpener)
        assertEquals(false, rig.acks().single().final)
        rig.data(channel, 0, "old".toByteArray())
        runCurrent(); assertEquals(1, rig.acks().size) // Conflicting old payload must not match the ACK cache.
        rig.mux.close()
    }

    @Test fun `same ID in simultaneous opposite OPEN roles is independent`() = runTest {
        val rig = Rig(this)
        val local = rig.open("/outgoing")
        rig.incoming(local.info.id, "/incoming")
        val remote = rig.mux.accept()
        assertEquals(local.info.id, remote.info.id)
        assertTrue(local.info.localIsOpener)
        assertFalse(remote.info.localIsOpener)
        rig.data(local, 0, "L".toByteArray())
        rig.data(remote, 0, "R".toByteArray())
        assertContentEquals("L".toByteArray(), local.read())
        assertContentEquals("R".toByteArray(), remote.read())
        val left = async { local.write(byteArrayOf(1)) }
        val right = async { remote.write(byteArrayOf(2)) }
        runCurrent()
        rig.ack(local, 0); runCurrent()
        assertTrue(left.isCompleted); assertFalse(right.isCompleted)
        rig.ack(remote, 0); runCurrent(); right.await()
        assertEquals(setOf(true, false), rig.dataWrites().map { it.header!!.fromChannelOpener }.toSet())
        rig.mux.close()
    }

    @Test fun `two waiting accepts both wake for coalesced incoming OPENs`() = runTest {
        val rig = Rig(this)
        val first = async { rig.mux.accept() }
        val second = async { rig.mux.accept() }
        runCurrent()
        rig.incoming(1uL, "/one")
        rig.incoming(2uL, "/two")
        runCurrent()
        assertEquals(setOf("/one", "/two"), setOf(first.await().info.path, second.await().info.path))
        rig.mux.close()
    }

    @Test fun `duplicate OPEN repeats ACK without a second accept and mismatched path closes it`() = runTest {
        val rig = Rig(this)
        rig.incoming(7uL, "/one")
        val channel = rig.mux.accept()
        rig.incoming(7uL, "/one")
        runCurrent()
        assertEquals(2, rig.writes.count { it.control?.type == ChannelCodec.OPEN_ACK })
        val next = async { runCatching { rig.mux.accept() } }
        runCurrent(); assertFalse(next.isCompleted)
        rig.incoming(7uL, "/different")
        assertEquals(ChannelEndReason.PROTOCOL, channel.state.value.endReason)
        assertFalse(next.isCompleted)
        rig.mux.close(); runCurrent()
        assertIs<ChannelClosedException>(next.await().exceptionOrNull())
    }

    @Test fun `input EOF and output EOF are independent and do not automatically CLOSE`() = runTest {
        val rig = Rig(this)
        val channel = rig.open()
        rig.data(channel, 0, byteArrayOf(), final = true)
        assertNull(channel.read())
        runCurrent()
        assertEquals(true, rig.acks().last().final)
        assertTrue(channel.state.value.inputEnded)
        assertFalse(channel.state.value.outputEnded)
        val output = async { channel.finishOutput() }
        runCurrent()
        assertEquals(true, rig.dataWrites().last().final)
        assertTrue(rig.dataWrites().last().data!!.isEmpty())
        assertFalse(output.isCompleted)
        rig.ack(channel, 0, final = true); runCurrent(); output.await()
        assertTrue(channel.state.value.outputEnded)
        assertEquals(ChannelPhase.OPEN, channel.state.value.phase)
        assertTrue(rig.writes.none { it.control?.type == ChannelCodec.CLOSE })
        rig.mux.close()
    }

    @Test fun `peer early final ACK fails remaining write but leaves input usable`() = runTest {
        val rig = Rig(this, ChannelConfig(sendChunkBytes = 128))
        val channel = rig.open()
        val writing = async { runCatching { channel.write(ByteArray(300)) } }
        runCurrent()
        rig.ack(channel, 0, final = true); runCurrent()
        assertIs<ChannelClosedException>(writing.await().exceptionOrNull())
        assertEquals(1, rig.dataWrites().size)
        assertTrue(channel.state.value.outputEnded)
        assertEquals(ChannelPhase.OPEN, channel.state.value.phase)
        rig.data(channel, 0, byteArrayOf(9))
        assertContentEquals(byteArrayOf(9), channel.read())
        rig.mux.close()
    }

    @Test fun `matching false final ACK adopts EOF from sent final DATA and logs interpretation`() = runTest {
        val rig = Rig(this)
        val channel = rig.open()
        val ending = async { channel.finishOutput() }; runCurrent()
        rig.ack(channel, 0, final = false); runCurrent(); ending.await()
        assertTrue(channel.state.value.outputEnded)
        assertEquals(ChannelPhase.OPEN, channel.state.value.phase)
        val warning = rig.diagnostics.single { it.event == "final_ack_interpreted" }
        assertEquals(DiagnosticSeverity.WARNING, warning.severity)
        assertEquals("0", warning.details["observedSequence"])
        assertEquals("true", warning.details["sentFinal"])
        assertContains(warning.details.getValue("decision"), "infer")
        rig.data(channel, 0, byteArrayOf(7)); assertContentEquals(byteArrayOf(7), channel.read())
        rig.mux.close()
    }

    @Test fun `closed local input promptly wakes read and marks the next data ACK final`() = runTest {
        val rig = Rig(this)
        val channel = rig.open()
        val reading = async { channel.read() }
        runCurrent(); channel.closeInput(); runCurrent()
        assertNull(reading.await())
        rig.data(channel, 0, byteArrayOf(1, 2, 3)); runCurrent()
        assertEquals(true, rig.acks().single().final)
        assertEquals(ChannelPhase.OPEN, channel.state.value.phase)
        assertFalse(channel.state.value.outputEnded)
        rig.mux.close()
    }

    @Test fun `future DATA never overwrites a pending unconsumed block or closes its channel`() = runTest {
        val rig = Rig(this)
        val one = rig.open("/one")
        val two = rig.open("/two")
        rig.data(one, 8, byteArrayOf(1))
        rig.data(one, 0, byteArrayOf(2))
        rig.data(one, 1, byteArrayOf(3))
        assertEquals(ChannelPhase.OPEN, one.state.value.phase)
        assertContentEquals(byteArrayOf(2), one.read())
        val warning = rig.diagnostics.single { it.event == "unexpected_pending_data" }
        assertEquals("1", warning.details["observedSequence"])
        assertEquals("0", warning.details["rxNext"])
        assertContains(warning.details.getValue("decision"), "preserve")
        rig.data(one, 1, byteArrayOf(3)); assertContentEquals(byteArrayOf(3), one.read())
        assertEquals(ChannelPhase.OPEN, two.state.value.phase)
        rig.mux.close()
    }

    @Test fun `future ACK is ignored with diagnostic and cannot confirm a pending DATA`() = runTest {
        val rig = Rig(this)
        val channel = rig.open()
        rig.ack(channel, 1)
        assertEquals(ChannelPhase.OPEN, channel.state.value.phase)
        assertEquals("false", rig.diagnostics.last { it.event == "unmatched_ack" }.details["pending"])
        val writing = async { channel.write(byteArrayOf(1)) }; runCurrent()
        rig.ack(channel, 5); runCurrent()
        assertFalse(writing.isCompleted)
        val warning = rig.diagnostics.last { it.event == "unmatched_ack" }
        assertEquals("5", warning.details["observedSequence"])
        assertEquals("0", warning.details["txNext"])
        assertEquals("true", warning.details["pending"])
        rig.ack(channel, 0); runCurrent(); writing.await()
        rig.mux.close()
    }

    @Test fun `DATA makes three attempts then sends CLOSE zero with timeout reason kept separate`() = runTest {
        val rig = Rig(this)
        val channel = rig.open()
        val writing = async { runCatching { channel.write(byteArrayOf(1)) } }
        runCurrent()
        advanceTimeBy(18_000); runCurrent()
        advanceTimeBy(18_000); runCurrent()
        assertEquals(3, rig.dataWrites().size)
        advanceTimeBy(18_000); runCurrent()
        val error = assertIs<ChannelClosedException>(writing.await().exceptionOrNull())
        assertEquals(ChannelEndReason.TIMEOUT, error.reason)
        assertNull(error.applicationErrorCode)
        assertEquals(0, rig.writes.last().control!!.closeErrorCode)
        assertEquals(ChannelPhase.OPEN, rig.open("/survivor").state.value.phase)
        rig.mux.close()
    }

    @Test fun `graceful close drains started write rejects queued writes and sends one CLOSE`() = runTest {
        val rig = Rig(this)
        val channel = rig.open()
        val writing = async { channel.write(byteArrayOf(1)) }
        runCurrent()
        val queued = async { runCatching { channel.write(byteArrayOf(2)) } }
        runCurrent()
        val closing = async { channel.closeGracefully(17) }
        runCurrent()
        assertEquals(ChannelPhase.CLOSING, channel.state.value.phase)
        assertTrue(rig.writes.none { it.control?.type == ChannelCodec.CLOSE })
        rig.ack(channel, 0); runCurrent()
        writing.await(); closing.await()
        assertIs<ChannelClosedException>(queued.await().exceptionOrNull())
        assertEquals(1, rig.dataWrites().size)
        assertEquals(1, rig.writes.count { it.control?.type == ChannelCodec.CLOSE })
        assertEquals(17, rig.writes.last().control!!.closeErrorCode)
        assertEquals(ChannelEndReason.LOCAL_CLOSE, channel.state.value.endReason)
        assertEquals(17, channel.state.value.applicationErrorCode)
        rig.mux.close()
    }

    @Test fun `canceling a second graceful-close await does not cancel its owner`() = runTest {
        val rig = Rig(this)
        val channel = rig.open()
        val writing = async { channel.write(byteArrayOf(1)) }; runCurrent()
        val closing = async { channel.closeGracefully() }; runCurrent()
        val another = async { channel.closeGracefully() }; runCurrent()
        another.cancelAndJoin()
        assertEquals(ChannelPhase.CLOSING, channel.state.value.phase)
        rig.ack(channel, 0); runCurrent()
        writing.await(); closing.await()
        rig.mux.close()
    }

    @Test fun `remote CLOSE authenticates control identity and keeps application error separate`() = runTest {
        val rig = Rig(this)
        val channel = rig.open()
        rig.closeRemote(channel, 22, digest = "wrong")
        assertEquals(ChannelPhase.OPEN, channel.state.value.phase)
        rig.closeRemote(channel, 22)
        assertEquals(ChannelEndReason.REMOTE_CLOSE, channel.state.value.endReason)
        assertEquals(22, channel.state.value.applicationErrorCode)
        runCurrent()
        assertTrue(rig.writes.none { it.control?.type == ChannelCodec.CLOSE })
        rig.mux.close()
    }

    @Test fun `unsupported version and other origin never dispatch into ChannelApi`() = runTest {
        val rig = Rig(this)
        for (request in listOf(
            ChannelRequest(control = ChannelControl(ChannelCodec.OPEN, 1uL, true, ROUTE.packageName, ROUTE.packageDigest, "/unsupported"), minimumVersion = 2),
            ChannelRequest(control = ChannelControl(ChannelCodec.OPEN, 2uL, true, ROUTE.packageName, ROUTE.packageDigest, "/asset"), origin = 1),
            ChannelRequest(control = ChannelControl(ChannelCodec.OPEN, 3uL, true, ROUTE.packageName, ROUTE.packageDigest, "/unknown"), origin = 9),
        )) rig.mux.receive(request)
        runCurrent(); assertTrue(rig.writes.isEmpty())
        val accepted = async { runCatching { rig.mux.accept() } }; runCurrent(); assertFalse(accepted.isCompleted)
        rig.mux.close(); runCurrent(); assertIs<ChannelClosedException>(accepted.await().exceptionOrNull())
    }

    @Test fun `capacity and write-call budgets reject locally without corrupting existing streams`() = runTest {
        val rig = Rig(this, ChannelConfig(maxActiveChannels = 1))
        val channel = rig.open()
        assertEquals(ChannelEndReason.RESOURCE_LIMIT, assertFailsWith<ChannelClosedException> { rig.mux.open("/extra", true) }.reason)
        assertFailsWith<IllegalArgumentException> { channel.write(ByteArray(512 * 1024 + 1)) }
        assertEquals(ChannelPhase.OPEN, channel.state.value.phase)
        rig.mux.close()
    }

    @Test fun `closing manager immediately releases pending reads writes opens and accepts`() = runTest {
        val rig = Rig(this)
        val channel = rig.open()
        val read = async { runCatching { channel.read() } }
        val write = async { runCatching { channel.write(byteArrayOf(1)) } }
        val open = async { runCatching { rig.mux.open("/pending", true) } }
        val accept = async { runCatching { rig.mux.accept() } }
        runCurrent()
        rig.mux.close(); runCurrent()
        for (result in listOf(read.await().exceptionOrNull(), write.await().exceptionOrNull(), open.await().exceptionOrNull(), accept.await().exceptionOrNull())) {
            assertEquals(ChannelEndReason.LINK_LOST, assertIs<ChannelClosedException>(result).reason)
        }
    }

    @Test fun `channel close releases an operation even while independent wire writer is blocked`() = runTest {
        val rig = Rig(this)
        val channel = rig.open()
        val blocker = CompletableDeferred<Unit>()
        rig.beforeSend = { if (it.data != null) blocker.await() }
        val writing = async { runCatching { channel.write(byteArrayOf(1)) } }; runCurrent()
        channel.close(); runCurrent()
        assertEquals(ChannelEndReason.LOCAL_CLOSE, assertIs<ChannelClosedException>(writing.await().exceptionOrNull()).reason)
        blocker.complete(Unit); runCurrent()
        assertEquals(ChannelPhase.OPEN, rig.open("/next").state.value.phase)
        rig.mux.close()
    }

    @Test fun `canceling idle reader preserves channel but cancellation during consumption aborts it`() = runTest {
        val rig = Rig(this, unconfined = true)
        val channel = rig.open()
        val idle = async { channel.read() }; runCurrent(); idle.cancelAndJoin()
        assertEquals(ChannelPhase.OPEN, channel.state.value.phase)
        rig.data(channel, 0, byteArrayOf(1))
        lateinit var reading: Deferred<ByteArray?>
        rig.beforeSend = { if (it.ack != null) reading.cancel() }
        reading = async(start = CoroutineStart.LAZY) { channel.read() }
        reading.start(); runCurrent()
        assertTrue(reading.isCancelled)
        assertEquals(ChannelEndReason.CANCELLED, channel.state.value.endReason)
        rig.mux.close()
    }

    @Test fun `optional missing headers use zero defaults without changing sender role`() = runTest {
        val constant = object : Random() { override fun nextBits(bitCount: Int) = 0 }
        val rig = Rig(this, random = constant)
        val opening = async { rig.mux.open("/zero", true) }; runCurrent()
        rig.mux.receive(ChannelRequest(control = ChannelControl(type = ChannelCodec.OPEN_ACK)))
        runCurrent()
        val channel = opening.await()
        assertEquals(0uL, channel.info.id)
        rig.mux.receive(ChannelRequest(data = ChannelData(data = byteArrayOf(7))))
        assertContentEquals(byteArrayOf(7), channel.read()); runCurrent()
        assertEquals(true, rig.acks().last().header!!.fromChannelOpener)
        val writing = async { channel.write(byteArrayOf(1)) }; runCurrent()
        rig.mux.receive(ChannelRequest(ack = ChannelAck()))
        runCurrent(); writing.await()
        rig.mux.close()
    }

    @Test fun `control precedes DATA and absent type is OPEN even for a false sender role`() = runTest {
        val rig = Rig(this)
        rig.mux.receive(ChannelRequest(
            control = ChannelControl(channelId = 5uL, fromChannelOpener = false, packageName = ROUTE.packageName, packageDigest = ROUTE.packageDigest, path = "/default"),
            data = ChannelData(ChannelHeader(5uL, true, 9), ByteArray(65_537)),
            minimumVersion = -1,
        ))
        val channel = rig.mux.accept(); runCurrent()
        assertFalse(channel.info.localIsOpener)
        assertEquals(ChannelPhase.OPEN, channel.state.value.phase)
        assertEquals(ChannelCodec.OPEN_ACK, rig.writes.single().control!!.type)
        rig.mux.close()
    }

    @Test fun `cancelled accept hands the coalesced notification to the remaining waiter`() = runTest {
        val rig = Rig(this)
        val canceled = async { rig.mux.accept() }
        val remaining = async { rig.mux.accept() }; runCurrent()
        rig.incoming(1uL, "/one"); rig.incoming(2uL, "/two")
        canceled.cancel(); runCurrent()
        val first = remaining.await()
        val second = rig.mux.accept()
        assertEquals(setOf("/one", "/two"), setOf(first.info.path, second.info.path))
        rig.mux.close()
    }

    @Test fun `last consumed DATA can replay its ACK without redelivery or sequence increment`() = runTest {
        val rig = Rig(this)
        val channel = rig.open()
        val body = byteArrayOf(4, 5, 6)
        rig.data(channel, 0, body); assertContentEquals(body, channel.read()); runCurrent()
        rig.data(channel, 0, body); runCurrent()
        assertEquals(listOf(0L, 0L), rig.acks().map { it.header!!.sequence })
        val next = async { channel.read() }; runCurrent(); assertFalse(next.isCompleted)
        rig.data(channel, 1, byteArrayOf(7)); runCurrent(); assertContentEquals(byteArrayOf(7), next.await())
        assertEquals(1L, rig.acks().last().header!!.sequence)
        assertEquals(1, rig.diagnostics.count { it.event == "duplicate_data_ack_replayed" })
        rig.mux.close()
    }

    @Test fun `EOF ACK recovery requires exact payload final and role tuple match`() = runTest {
        val rig = Rig(this)
        val channel = rig.open()
        rig.data(channel, 0, byteArrayOf(8), final = true)
        assertContentEquals(byteArrayOf(8), channel.read()); runCurrent()
        rig.data(channel, 0, byteArrayOf(8), final = true); runCurrent()
        assertEquals(listOf(true, true), rig.acks().map { it.final })
        rig.data(channel, 0, byteArrayOf(9), final = true)
        rig.data(channel, 0, byteArrayOf(8), final = false)
        rig.mux.receive(ChannelRequest(data = ChannelData(ChannelHeader(channel.info.id, channel.info.localIsOpener, 0), byteArrayOf(8), true)))
        runCurrent()
        assertEquals(2, rig.acks().size)
        assertEquals(2, rig.diagnostics.count { it.event == "old_data_ignored" })
        assertEquals(1, rig.diagnostics.count { it.event == "unknown_data" })
        assertNull(channel.read())
        assertEquals(ChannelPhase.OPEN, channel.state.value.phase)
        val writing = async { channel.write(byteArrayOf(1)) }; runCurrent(); rig.ack(channel, 0); runCurrent(); writing.await()
        rig.mux.close()
    }

    @Test fun `unknown control uses the stock OPEN fallback and records raw enum`() = runTest {
        val rig = Rig(this)
        rig.mux.receive(ChannelRequest(control = ChannelControl(99, 7uL, true, ROUTE.packageName, ROUTE.packageDigest, "/future")))
        val channel = rig.mux.accept(); runCurrent()
        assertEquals("/future", channel.info.path)
        assertEquals(ChannelCodec.OPEN_ACK, rig.writes.single().control!!.type)
        val warning = rig.diagnostics.single { it.event == "control_default_adopted" }
        assertEquals("99", warning.details["rawType"])
        assertEquals("1", warning.details["dispatchType"])
        assertEquals(DiagnosticSeverity.WARNING, warning.severity)
        rig.mux.close()
    }

    @Test fun `diagnostic sink failure is isolated and metadata never contains payload body`() = runTest {
        val rig = Rig(this)
        rig.diagnosticCallback = { throw IllegalStateException("logger failed") }
        val channel = rig.open()
        val secret = "sensitive-health-body"
        rig.data(channel, 0, secret.toByteArray()); assertContentEquals(secret.toByteArray(), channel.read()); runCurrent()
        val writing = async { channel.write(secret.toByteArray()) }; runCurrent(); rig.ack(channel, 0); runCurrent(); writing.await()
        assertTrue(rig.diagnostics.isNotEmpty())
        assertTrue(rig.diagnostics.none { it.details.values.any { value -> secret in value } })
        assertEquals(ChannelPhase.OPEN, channel.state.value.phase)
        rig.mux.close()
    }

    companion object {
        val PEER = PeerAddress("AA:BB:CC:DD:EE:FF")
        val ROUTE = ApplicationRoute("example.companion", "digest")
    }
}
