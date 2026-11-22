package dev.ticbox.companion.protocol

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ScreenUdpTransportTest {
    private val key = ByteArray(32) { (it * 11).toByte() }
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val shortTiming = ScreenUdpTransport.Timing(handshakeMillis = 1_500, heartbeatMillis = 40, idleMillis = 500)

    @Test fun authenticatedIndependentSocketsDeliverFragmentedVideoAndAudio() {
        val receivedVideo = LinkedBlockingQueue<ScreenUdpTransport.Message>()
        val receivedAudio = LinkedBlockingQueue<ScreenUdpTransport.Message>()
        val failures = LinkedBlockingQueue<IOException>()
        ScreenUdpTransport.listen(key, ScreenUdpTransport.VIDEO, onFailure = { failures.add(it) }).use { video ->
            ScreenUdpTransport.listen(key, ScreenUdpTransport.AUDIO, onFailure = { failures.add(it) }).use { audio ->
                assertNotEquals(video.port, audio.port)
                ScreenUdpTransport.connect(loopback, video.port, key, ScreenUdpTransport.VIDEO, { receivedVideo.add(it) }, { failures.add(it) }).use { picture ->
                    ScreenUdpTransport.connect(loopback, audio.port, key, ScreenUdpTransport.AUDIO, { receivedAudio.add(it) }, { failures.add(it) }).use { sound ->
                        picture.awaitConnected(); sound.awaitConnected(); video.awaitConnected(); audio.awaitConnected()
                        val frame = ByteArray(100_000) { (it * 31).toByte() }
                        val sent = video.sendMeasured(ToolboxProtocol.VIDEO_FRAME, frame)
                        assertEquals(0L, sent.sequence)
                        assertEquals(frame.size, sent.payloadBytes)
                        assertEquals(87, sent.datagrams)
                        assertEquals(104_176L, sent.wireBytes)
                        assertEquals(0L, sent.pacingWaitNanos)
                        assertTrue(sent.sendNanos > 0)
                        audio.send(ToolboxProtocol.AUDIO_FRAME, byteArrayOf(3, 4, 5))
                        val observed = assertNotNull(receivedVideo.poll(3, TimeUnit.SECONDS))
                        assertEquals(ToolboxProtocol.VIDEO_FRAME, observed.type)
                        assertContentEquals(frame, observed.bytes)
                        assertFalse(observed.discontinuity)
                        assertNull(observed.gap)
                        assertContentEquals(byteArrayOf(3, 4, 5), assertNotNull(receivedAudio.poll(3, TimeUnit.SECONDS)).bytes)
                        assertNull(failures.poll())
                        assertFailsWith<IOException> { picture.send(ToolboxProtocol.VIDEO_FRAME, frame) }
                    }
                }
            }
        }
    }

    @Test fun wrongKeyAndUnauthenticatedPeerCannotClaimDestinationOrDeliverMedia() {
        val failures = LinkedBlockingQueue<IOException>()
        val observed = LinkedBlockingQueue<ScreenUdpTransport.Message>()
        ScreenUdpTransport.listen(key, ScreenUdpTransport.VIDEO, onFailure = { failures.add(it) }).use { server ->
            DatagramSocket().use { intruder ->
                val cipher = ScreenUdpCipher(ByteArray(32) { 99 }, ScreenUdpTransport.VIDEO, fromServer = false)
                val hello = cipher.encode(control(ScreenUdpCipher.HELLO, 0))
                intruder.send(DatagramPacket(hello, hello.size, InetSocketAddress(loopback, server.port)))
                // The real key can still claim this listening socket after a bogus HELLO.
                ScreenUdpTransport.connect(loopback, server.port, key, ScreenUdpTransport.VIDEO, { observed.add(it) }, { failures.add(it) }).use { client ->
                    client.awaitConnected(); server.awaitConnected()
                    server.send(ToolboxProtocol.VIDEO_CONFIG, byteArrayOf(42))
                    assertContentEquals(byteArrayOf(42), assertNotNull(observed.poll(2, TimeUnit.SECONDS)).bytes)
                    assertNull(failures.poll())
                }
            }
        }
    }

    @Test fun silentOrWrongKeyEndpointFailsWithoutFallbackAndCloseUnblocksHandshake() {
        val timing = shortTiming.copy(handshakeMillis = 150)
        val failures = LinkedBlockingQueue<IOException>()
        DatagramSocket().use { unreachable ->
            ScreenUdpTransport.connect(loopback, unreachable.localPort, key, ScreenUdpTransport.VIDEO, {}, { failures.add(it) }, timing).use { client ->
                val failure = assertFailsWith<IOException> { client.awaitConnected(1_000) }
                assertEquals("screen_udp_unreachable", failure.message)
                assertEquals("screen_udp_unreachable", assertNotNull(failures.poll(1, TimeUnit.SECONDS)).message)
            }
        }
        ScreenUdpTransport.listen(key, ScreenUdpTransport.VIDEO, onFailure = {}).use { server ->
            val entered = CountDownLatch(1)
            val result = LinkedBlockingQueue<Throwable>()
            val waiter = Thread({ entered.countDown(); runCatching { server.awaitConnected(30_000) }.exceptionOrNull()?.let(result::add) })
            waiter.start()
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            server.close()
            assertEquals("screen_udp_closed", assertNotNull(result.poll(1, TimeUnit.SECONDS)).message)
            waiter.join(1_000)
            assertFalse(waiter.isAlive)
        }
    }

    @Test fun idleStaticPictureKeepsHeartbeatButDisappearingPeerReportsFailure() {
        val failures = LinkedBlockingQueue<IOException>()
        val server = ScreenUdpTransport.listen(key, ScreenUdpTransport.VIDEO, onFailure = {}, timing = shortTiming)
        try {
            ScreenUdpTransport.connect(loopback, server.port, key, ScreenUdpTransport.VIDEO, {}, { failures.add(it) }, shortTiming).use { client ->
                client.awaitConnected(); server.awaitConnected()
                assertNull(failures.poll(750, TimeUnit.MILLISECONDS)) // Longer than the 500 ms idle bound.
                server.close()
                val failure = assertNotNull(failures.poll(2, TimeUnit.SECONDS))
                // Connected UDP may report the closed port immediately, or time out the heartbeat.
                assertTrue(failure.message in setOf("screen_udp_connection_lost", "screen_udp_io_failed"))
            }
        } finally { server.close() }
    }

    @Test fun answeredHeartbeatsMeasureTheRoundTripAndDeliveredFramesCarryTheirReceipt() {
        val received = LinkedBlockingQueue<ScreenUdpTransport.Message>()
        ScreenUdpTransport.listen(key, ScreenUdpTransport.VIDEO, onFailure = {}, timing = shortTiming).use { server ->
            ScreenUdpTransport.connect(loopback, server.port, key, ScreenUdpTransport.VIDEO, { received.add(it) }, {}, shortTiming, collectDiagnostics = true).use { client ->
                client.awaitConnected(); server.awaitConnected()
                val deadline = System.nanoTime() + 2_000_000_000L
                while (client.roundTripNanos() == null && System.nanoTime() < deadline) Thread.sleep(10)
                assertTrue(assertNotNull(client.roundTripNanos()) in 0..1_000_000_000L)
                assertNull(server.roundTripNanos()) // Only the phone pairs its probes.
                assertNull(client.roundTripNanos(System.nanoTime() + 4_000_000_000L)) // Stale reads as unmeasured.
                val before = System.nanoTime()
                server.send(ToolboxProtocol.VIDEO_FRAME, ByteArray(5_000) { it.toByte() })
                val frame = assertNotNull(received.poll(3, TimeUnit.SECONDS))
                assertTrue(assertNotNull(frame.completedNanos) >= before)
                assertTrue(frame.receiveWorkNanos > 0)
            }
        }
    }

    @Test fun encryptionBindsHeaderStreamDirectionKeyAndEveryPayloadByte() {
        val fragment = fragments(0, ByteArray(2_700) { it.toByte() }).first()
        val sender = ScreenUdpCipher(key, ScreenUdpTransport.VIDEO, fromServer = true)
        val encoded = sender.encode(fragment)
        val receiver = ScreenUdpCipher(key, ScreenUdpTransport.VIDEO, fromServer = true)
        assertTrue(encoded.size <= 1_200)
        assertContentEquals(fragment.payload, assertNotNull(receiver.decode(encoded)).payload)
        for (index in listOf(0, 7, 8, 20, 27, 31, 32, encoded.lastIndex)) {
            val corrupt = encoded.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertNull(receiver.decode(corrupt), "tampered byte $index")
        }
        assertNull(ScreenUdpCipher(key, ScreenUdpTransport.AUDIO, true).decode(encoded))
        assertNull(ScreenUdpCipher(key, ScreenUdpTransport.VIDEO, false).decode(encoded))
        assertNull(ScreenUdpCipher(ByteArray(32), ScreenUdpTransport.VIDEO, true).decode(encoded))
        assertNull(receiver.decode(encoded.copyOf(20)))
        assertNull(receiver.decode(ByteArray(1_201)))
        assertFailsWith<IllegalArgumentException> { ScreenUdpCipher(ByteArray(31), ScreenUdpTransport.VIDEO, true) }
    }

    @Test fun packetReplayWindowHandlesWholeLargeFrameReverseOrderWithoutQuadraticShifting() {
        val replay = ScreenUdpReplayWindow()
        // Maximum 8 MiB frame is over 7,000 datagrams; fully reversed arrival remains admissible.
        for (sequence in 8_191L downTo 0) assertTrue(replay.accept(sequence))
        for (sequence in 0L..8_191) assertFalse(replay.accept(sequence))
        for (sequence in 8_192L..40_000) assertTrue(replay.accept(sequence))
        assertFalse(replay.accept(0))
        assertFalse(replay.accept(40_000))
        assertFalse(replay.accept(-1))
    }

    @Test fun fragmentedFramesReorderDeduplicateAndWaitForMissingPredecessors() {
        val assembler = ScreenUdpAssembler(100)
        val content = ByteArray(5_000) { (it * 17).toByte() }
        val future = fragments(1, byteArrayOf(99)).single()
        assertTrue(assembler.accept(future, 0).isEmpty())
        val pieces = fragments(0, content)
        for (piece in pieces.drop(1).reversed()) {
            assertTrue(assembler.accept(piece, 10).isEmpty())
            assertTrue(assembler.accept(piece, 11).isEmpty())
        }
        val result = assembler.accept(pieces.first(), 12)
        assertEquals(listOf(0L, 1L), result.map { it.sequence })
        assertContentEquals(content, result[0].bytes)
        assertContentEquals(byteArrayOf(99), result[1].bytes)
        assertTrue(result.none { it.discontinuity })
        assertEquals(0, assembler.retainedBytes)
        assertTrue(assembler.accept(pieces.first(), 20).isEmpty())
    }

    @Test fun expectedMessageUnlocksAFullQueueWithoutEvictingItsSuccessor() {
        val assembler = ScreenUdpAssembler(150)
        // Reordered within the time a gap may wait once a later message is whole.
        for (sequence in 1L..4) {
            assertTrue(assembler.accept(fragments(sequence, byteArrayOf(sequence.toByte())).single(), sequence * 5).isEmpty())
        }
        assertEquals(4, assembler.retainedMessages)
        val ready = assembler.accept(fragments(0, byteArrayOf(0)).single(), 25)
        assertEquals((0L..4).toList(), ready.map { it.sequence })
        assertTrue(ready.none { it.discontinuity })
        assertEquals(0, assembler.retainedMessages)
        assertEquals(0, assembler.retainedBytes)
    }

    @Test fun missingFragmentedMessageReplacesFurthestFutureEntryAndRetainsContiguousFrames() {
        val assembler = ScreenUdpAssembler(150)
        for (sequence in 1L..4) assembler.accept(fragments(sequence, byteArrayOf(sequence.toByte())).single(), sequence * 2)
        val keyframe = ByteArray(3_000) { (it * 7).toByte() }
        val pieces = fragments(0, keyframe)
        assertTrue(assembler.accept(pieces[0], 10).isEmpty())
        assertEquals(4, assembler.retainedMessages) // Current 0 plus future 1, 2 and 3; only 4 was evicted.
        assertEquals(3_003, assembler.retainedBytes)
        assertTrue(assembler.accept(pieces[1], 15).isEmpty())
        val ready = assembler.accept(pieces[2], 20)
        assertEquals(listOf(0L, 1L, 2L, 3L), ready.map { it.sequence })
        assertContentEquals(keyframe, ready.first().bytes)
        assertTrue(ready.none { it.discontinuity })
        assertEquals(0, assembler.retainedMessages)
        // A genuinely missing evicted frame marks the next delivered frame, not the earlier key.
        // Message 5 is whole, so the evicted 4 is given up on after the short wait.
        assertTrue(assembler.accept(fragments(5, byteArrayOf(5)).single(), 80).isEmpty())
        assertTrue(assembler.expire(80 + ScreenUdpTransport.LATER_COMPLETE_MILLIS - 1).isEmpty())
        val afterEviction = assembler.expire(80 + ScreenUdpTransport.LATER_COMPLETE_MILLIS).single()
        assertTrue(afterEviction.discontinuity)
        val gap = assertNotNull(afterEviction.gap)
        assertEquals(1L, gap.skippedMessages)
        assertFalse(gap.missingMessageTimeout)
        assertTrue(gap.laterComplete)
        assertEquals(1L, gap.capacityEvictions)
        assertEquals(0L, gap.capacityRejectedFragments)
    }

    @Test fun farFuturePressureCannotEvictTheIncompleteExpectedKeyframe() {
        val assembler = ScreenUdpAssembler(150)
        val pieces = fragments(0, ByteArray(3_000) { it.toByte() })
        assembler.accept(pieces[0], 0)
        for (sequence in 1L..3) assembler.accept(fragments(sequence, byteArrayOf(sequence.toByte())).single(), sequence)
        for (sequence in 4L..20) {
            assertTrue(assembler.accept(fragments(sequence, byteArrayOf(sequence.toByte())).single(), sequence).isEmpty())
            assertEquals(4, assembler.retainedMessages)
            assertEquals(3_003, assembler.retainedBytes)
        }
        assertTrue(assembler.accept(pieces[1], 25).isEmpty())
        val ready = assembler.accept(pieces[2], 28)
        assertEquals(listOf(0L, 1L, 2L, 3L), ready.map { it.sequence })
        assertTrue(ready.none { it.discontinuity })
        assertEquals(0, assembler.retainedBytes)
    }

    @Test fun byteBudgetPrioritizesMissingPredecessorWithoutAdvancingTheExpectedSequence() {
        val assembler = ScreenUdpAssembler(150)
        fun first(sequence: Long) = ScreenUdpFragment(ScreenUdpCipher.MEDIA, sequence, sequence, ToolboxProtocol.VIDEO_FRAME,
            ToolboxProtocol.MAX_PACKET, 0, (ToolboxProtocol.MAX_PACKET + ScreenUdpCipher.PAYLOAD - 1) / ScreenUdpCipher.PAYLOAD,
            ByteArray(ScreenUdpCipher.PAYLOAD))
        assembler.accept(first(1), 0)
        assembler.accept(first(2), 1)
        assertEquals(ScreenUdpAssembler.MAX_BYTES, assembler.retainedBytes)
        assertTrue(assembler.accept(first(0), 2).isEmpty())
        assertEquals(2, assembler.retainedMessages)
        assertEquals(ScreenUdpAssembler.MAX_BYTES, assembler.retainedBytes)
        // 0 remains in progress; a small new future message cannot advance past it.
        assertTrue(assembler.accept(fragments(3, byteArrayOf(3)).single(), 3).isEmpty())
        assertEquals(2, assembler.retainedMessages)
        assertEquals(ScreenUdpAssembler.MAX_BYTES, assembler.retainedBytes)
        assertTrue(assembler.expire(149).isEmpty())
    }

    @Test fun rejectedLargeFutureMessageDoesNotPartiallyEvictTheExistingQueue() {
        val assembler = ScreenUdpAssembler(150)
        fun first(sequence: Long, size: Int) = ScreenUdpFragment(ScreenUdpCipher.MEDIA, sequence, sequence, ToolboxProtocol.VIDEO_FRAME,
            size, 0, (size + ScreenUdpCipher.PAYLOAD - 1) / ScreenUdpCipher.PAYLOAD, ByteArray(ScreenUdpCipher.PAYLOAD))
        val mib = 1024 * 1024
        assembler.accept(first(0, 8 * mib), 0)
        assembler.accept(first(1, 7 * mib), 1)
        assembler.accept(first(5, mib), 2)
        assertEquals(3, assembler.retainedMessages)
        assertEquals(ScreenUdpAssembler.MAX_BYTES, assembler.retainedBytes)
        // Evicting 5 would still leave 15 MiB in earlier 0/1. Message 3 cannot fit, so
        // reject it without first losing 5 to a failed admission attempt.
        assertTrue(assembler.accept(first(3, 8 * mib), 3).isEmpty())
        assertEquals(3, assembler.retainedMessages)
        assertEquals(ScreenUdpAssembler.MAX_BYTES, assembler.retainedBytes)
        assertTrue(assembler.expire(149).isEmpty())
    }

    @Test fun partialAndEntireMissingFramesExpireWithoutWaitingForAnotherDatagram() {
        val assembler = ScreenUdpAssembler(100)
        assertTrue(assembler.accept(fragments(0, ByteArray(3_000)).first(), 0).isEmpty())
        assertTrue(assembler.accept(fragments(1, byteArrayOf(1)).single(), 10).isEmpty())
        val afterPartial = assembler.expire(101).single()
        assertEquals(1L, afterPartial.sequence)
        assertTrue(afterPartial.discontinuity)
        val partialGap = assertNotNull(afterPartial.gap)
        assertEquals(1L, partialGap.skippedMessages)
        assertEquals(1L, partialGap.incompleteMessages)
        assertEquals(2L, partialGap.missingFragments)
        assertTrue(partialGap.fragmentTimeout)
        assertFalse(partialGap.frameDeadline)
        assertFalse(partialGap.missingMessageTimeout)
        assertEquals(101L, partialGap.maxAgeMillis)
        assertEquals(101L, partialGap.maxNoProgressMillis)
        // Message 3 is whole, so the wholly missing 2 waits only the short time.
        assertTrue(assembler.accept(fragments(3, byteArrayOf(3)).single(), 110).isEmpty())
        assertTrue(assembler.expire(110 + ScreenUdpTransport.LATER_COMPLETE_MILLIS - 1).isEmpty())
        val afterMissing = assembler.expire(110 + ScreenUdpTransport.LATER_COMPLETE_MILLIS).single()
        assertEquals(3L, afterMissing.sequence)
        assertTrue(afterMissing.discontinuity)
        val missingGap = assertNotNull(afterMissing.gap)
        assertEquals(1L, missingGap.skippedMessages)
        assertEquals(0L, missingGap.incompleteMessages)
        assertEquals(0L, missingGap.missingFragments) // No fragment count was observed for message 2.
        assertFalse(missingGap.missingMessageTimeout)
        assertTrue(missingGap.laterComplete)
        assertFalse(missingGap.fragmentTimeout)
        assertEquals(ScreenUdpTransport.LATER_COMPLETE_MILLIS, missingGap.maxAgeMillis)
        assertEquals(0L, missingGap.maxNoProgressMillis)
        val next = assembler.accept(fragments(4, byteArrayOf(4)).single(), 211).single()
        assertFalse(next.discontinuity)
        assertNull(next.gap)
        assertEquals(0, assembler.retainedBytes)
    }

    @Test fun gapMetadataAccumulatesKnownPartialsAndMissingMessagesThenResets() {
        val assembler = ScreenUdpAssembler(100)
        assembler.accept(fragments(0, ByteArray(3_000)).first(), 0)
        assembler.accept(fragments(2, ByteArray(3_000)).first(), 10)
        assembler.accept(fragments(4, byteArrayOf(4)).single(), 20)
        val result = assembler.expire(120).single()
        assertEquals(4L, result.sequence)
        val gap = assertNotNull(result.gap)
        assertEquals(4L, gap.skippedMessages)
        assertEquals(2L, gap.incompleteMessages)
        assertEquals(4L, gap.missingFragments)
        assertTrue(gap.missingMessageTimeout)
        assertTrue(gap.fragmentTimeout)
        assertFalse(gap.frameDeadline)
        assertEquals(120L, gap.maxAgeMillis)
        assertEquals(120L, gap.maxNoProgressMillis)
        assembler.accept(fragments(6, byteArrayOf(6)).single(), 130)
        val following = assertNotNull(assembler.expire(230).single().gap)
        assertEquals(1L, following.skippedMessages)
        assertEquals(0L, following.incompleteMessages)
        assertEquals(0L, following.missingFragments)
        assertFalse(following.fragmentTimeout)
        assertEquals(4L, gap.skippedMessages) // Earlier snapshots are immutable.
    }

    @Test fun gapCapacityObservationsDoNotMarkEarlierContinuousMessagesAndAreCountedOnce() {
        val assembler = ScreenUdpAssembler(100)
        val pieces = fragments(0, ByteArray(3_000))
        assembler.accept(pieces[0], 0)
        for (sequence in 1L..3) assembler.accept(fragments(sequence, byteArrayOf(sequence.toByte())).single(), sequence)
        for (sequence in 4L..20) assembler.accept(fragments(sequence, byteArrayOf(sequence.toByte())).single(), sequence)
        assembler.accept(pieces[1], 25)
        val continuous = assembler.accept(pieces[2], 28)
        assertEquals(listOf(0L, 1L, 2L, 3L), continuous.map { it.sequence })
        assertTrue(continuous.all { !it.discontinuity && it.gap == null })
        assembler.accept(fragments(21, byteArrayOf(21)).single(), 50)
        val pressureGap = assertNotNull(assembler.expire(150).single().gap)
        assertEquals(17L, pressureGap.skippedMessages)
        assertEquals(17L, pressureGap.capacityRejectedFragments)
        assertEquals(0L, pressureGap.capacityEvictions)
        assembler.accept(fragments(23, byteArrayOf(23)).single(), 160)
        val nextGap = assertNotNull(assembler.expire(260).single().gap)
        assertEquals(1L, nextGap.skippedMessages)
        assertEquals(0L, nextGap.capacityRejectedFragments)
        assertEquals(0L, nextGap.capacityEvictions)
    }

    @Test fun gapMetadataHandlesLargeSequenceJumpsWithoutAllocatingMissingEntries() {
        val assembler = ScreenUdpAssembler(100)
        val sequence = Long.MAX_VALUE - 2
        assembler.accept(fragments(0, byteArrayOf(1)).single().copy(messageSequence = sequence), 0)
        assertEquals(1, assembler.retainedMessages)
        val message = assembler.expire(100).single()
        assertEquals(sequence, assertNotNull(message.gap).skippedMessages)
        assertEquals(0, assembler.retainedMessages)
        assertEquals(0, assembler.retainedBytes)
        // Existing callers/tests can still construct a message without optional local observations.
        assertNull(ScreenUdpTransport.Message(0, ToolboxProtocol.VIDEO_FRAME, byteArrayOf(), true).gap)
    }

    @Test fun reassemblyMemoryIsBoundedDuringAnAuthenticatedBurstOfIncompleteFrames() {
        val assembler = ScreenUdpAssembler(100)
        for (sequence in 0L..20) {
            val size = ToolboxProtocol.MAX_PACKET
            val first = ScreenUdpFragment(ScreenUdpCipher.MEDIA, sequence, sequence, ToolboxProtocol.VIDEO_FRAME,
                size, 0, (size + ScreenUdpCipher.PAYLOAD - 1) / ScreenUdpCipher.PAYLOAD, ByteArray(ScreenUdpCipher.PAYLOAD))
            assembler.accept(first, sequence)
            assertTrue(assembler.retainedBytes <= ScreenUdpAssembler.MAX_BYTES)
            assertTrue(assembler.retainedMessages <= ScreenUdpAssembler.MAX_MESSAGES)
        }
        assertTrue(assembler.expire(500).isEmpty())
        assertEquals(0, assembler.retainedBytes)
        assertTrue(assembler.accept(fragments(21, byteArrayOf(21)).single(), 501).isEmpty())
        assertTrue(assembler.expire(601).single().discontinuity)
    }

    @Test fun malformedAuthenticatedFragmentLayoutCannotReachReassembly() {
        val cipher = ScreenUdpCipher(key, ScreenUdpTransport.VIDEO, true)
        val valid = fragments(0, byteArrayOf(1)).single()
        for (bad in listOf(
            valid.copy(total = -1), valid.copy(total = ToolboxProtocol.MAX_PACKET + 1),
            valid.copy(index = 2), valid.copy(count = 0), valid.copy(type = 0),
            valid.copy(packetSequence = -1), valid.copy(messageSequence = Long.MAX_VALUE),
            valid.copy(payload = byteArrayOf()), valid.copy(kind = ScreenUdpCipher.HELLO),
        )) assertFailsWith<IllegalArgumentException> { cipher.encode(bad) }
    }

    @Test fun audioRejectsOversizedMessagesBeforeReassemblyWhileVideoAllowsBoundedKeyframes() {
        val audio = ScreenUdpCipher(key, ScreenUdpTransport.AUDIO, true)
        val video = ScreenUdpCipher(key, ScreenUdpTransport.VIDEO, true)
        fun first(total: Int) = ScreenUdpFragment(ScreenUdpCipher.MEDIA, 0, 0, ToolboxProtocol.AUDIO_FRAME,
            total, 0, (total + ScreenUdpCipher.PAYLOAD - 1) / ScreenUdpCipher.PAYLOAD, ByteArray(ScreenUdpCipher.PAYLOAD))
        assertFailsWith<IllegalArgumentException> { audio.encode(first(ScreenUdpTransport.MAX_AUDIO_MESSAGE + 1)) }
        val acceptedAudio = audio.encode(first(ScreenUdpTransport.MAX_AUDIO_MESSAGE))
        assertNotNull(ScreenUdpCipher(key, ScreenUdpTransport.AUDIO, true).decode(acceptedAudio))
        val acceptedVideo = video.encode(first(ToolboxProtocol.MAX_PACKET).copy(type = ToolboxProtocol.VIDEO_FRAME))
        assertNotNull(ScreenUdpCipher(key, ScreenUdpTransport.VIDEO, true).decode(acceptedVideo))
    }

    @Test fun mediaPacerLimitsBurstsWithoutReservingFutureBudgetOnDeniedAttempts() {
        val pacer = ScreenUdpPacer(1_200, 2_400)
        assertEquals(0L, pacer.take(1_200, 0))
        assertEquals(0L, pacer.take(1_200, 0))
        assertEquals(1_000_000_000L, pacer.take(1_200, 0))
        assertEquals(500_000_000L, pacer.take(1_200, 500_000_000))
        assertEquals(0L, pacer.take(600, 500_000_000))
        assertEquals(0L, pacer.take(1_200, 1_500_000_000))
        assertEquals(0L, pacer.take(1_200, 10_000_000_000))
        assertEquals(0L, pacer.take(1_200, 10_000_000_000))
        assertEquals(1_000_000_000L, pacer.take(1_200, 10_000_000_000))
    }

    @Test fun aWholeLaterMessageEndsTheWaitForAMissingFragmentEarly() {
        val assembler = ScreenUdpAssembler(150)
        val pieces = fragments(0, ByteArray(3_000) { it.toByte() })
        assertTrue(assembler.accept(pieces[0], 0).isEmpty())
        assertTrue(assembler.accept(pieces[2], 1).isEmpty())
        assertTrue(fragments(1, byteArrayOf(5)).flatMap { assembler.accept(it, 2) }.isEmpty())
        // The missing fragment still has its window 29 ms after the last progress, and not after 30.
        assertTrue(assembler.expire(ScreenUdpTransport.LATER_COMPLETE_MILLIS).isEmpty())
        val next = assembler.expire(1 + ScreenUdpTransport.LATER_COMPLETE_MILLIS).single()
        assertEquals(1L, next.sequence)
        val gap = assertNotNull(next.gap)
        assertTrue(gap.laterComplete); assertFalse(gap.fragmentTimeout)
        assertEquals(1L, gap.missingFragments)
        // A fragment reordered within the window completes its message without a gap.
        val late = fragments(2, ByteArray(3_000) { it.toByte() })
        assertTrue(assembler.accept(late[0], 100).isEmpty())
        assertTrue(fragments(3, byteArrayOf(6)).flatMap { assembler.accept(it, 101) }.isEmpty())
        val delivered = assembler.accept(late[1], 110) + assembler.accept(late[2], 111)
        assertEquals(listOf(2L, 3L), delivered.map { it.sequence })
        assertTrue(delivered.none { it.discontinuity })
    }

    @Test fun pacedFragmentProgressHasIdleDeadlineAndHardTotalLifetime() {
        val assembler = ScreenUdpAssembler(100)
        val pieces = fragments(0, ByteArray(3_000) { it.toByte() })
        assertTrue(assembler.accept(pieces[0], 0).isEmpty())
        assertTrue(assembler.accept(pieces[1], 90).isEmpty())
        assertTrue(assembler.expire(150).isEmpty())
        assertFalse(assembler.accept(pieces[2], 180).single().discontinuity)
        val endless = fragments(1, ByteArray(50_000))
        for (index in 0..22) assertTrue(assembler.accept(endless[index], 200L + index * 90).isEmpty())
        assembler.expire(2_201)
        assertEquals(0, assembler.retainedBytes)
        val next = assembler.accept(fragments(2, byteArrayOf(2)).single(), 2_202).single()
        assertTrue(next.discontinuity)
        val gap = assertNotNull(next.gap)
        assertTrue(gap.frameDeadline)
        assertFalse(gap.fragmentTimeout) // Progress was still arriving 21 ms before the hard expiry.
        assertFalse(gap.missingMessageTimeout)
        assertEquals(1L, gap.incompleteMessages)
        assertEquals(21L, gap.missingFragments)
        assertEquals(2_001L, gap.maxAgeMillis)
        assertEquals(21L, gap.maxNoProgressMillis)
    }

    @Test fun pacedKeyframeOutlivesInitialReorderWindowWhileHeartbeatsRemainIndependent() {
        val received = LinkedBlockingQueue<ScreenUdpTransport.Message>()
        val failures = LinkedBlockingQueue<IOException>()
        ScreenUdpTransport.listen(key, ScreenUdpTransport.VIDEO, onFailure = { failures.add(it) }, timing = shortTiming).use { server ->
            ScreenUdpTransport.connect(loopback, server.port, key, ScreenUdpTransport.VIDEO, { received.add(it) }, { failures.add(it) }, shortTiming).use { client ->
                client.awaitConnected(); server.awaitConnected()
                server.setSendRate(20_000, 1_200)
                val payload = ByteArray(20_000) { (it * 19).toByte() }
                val started = System.nanoTime()
                val sent = server.sendMeasured(ToolboxProtocol.VIDEO_FRAME, payload)
                assertTrue(System.nanoTime() - started >= 150_000_000L)
                assertEquals(0L, sent.sequence)
                assertEquals(payload.size, sent.payloadBytes)
                assertEquals(18, sent.datagrams)
                assertEquals(20_864L, sent.wireBytes)
                assertTrue(sent.pacingWaitNanos >= 150_000_000L)
                assertTrue(sent.sendNanos >= sent.pacingWaitNanos)
                val frame = assertNotNull(received.poll(2, TimeUnit.SECONDS))
                assertContentEquals(payload, frame.bytes)
                assertFalse(frame.discontinuity)
                assertNull(failures.poll())
                assertTrue(frame.toString().contains("bytes=20000"))
                assertFalse(frame.toString().contains(payload.joinToString()))
            }
        }
    }

    @Test fun closeInterruptsPacedMediaWithoutWaitingForItsRateBudget() {
        val server = ScreenUdpTransport.listen(key, ScreenUdpTransport.VIDEO, onFailure = {})
        try {
            ScreenUdpTransport.connect(loopback, server.port, key, ScreenUdpTransport.VIDEO, {}, {}).use { client ->
                client.awaitConnected(); server.awaitConnected()
                server.setSendRate(1, 1_200)
                val entered = CountDownLatch(1)
                val stopped = CountDownLatch(1)
                val sender = Thread({
                    entered.countDown()
                    try { server.send(ToolboxProtocol.VIDEO_FRAME, ByteArray(20_000)) } catch (_: IOException) { } finally { stopped.countDown() }
                })
                sender.start()
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                server.close()
                assertTrue(stopped.await(1, TimeUnit.SECONDS))
                sender.join(1_000)
                assertFalse(sender.isAlive)
            }
        } finally { server.close() }
    }

    private fun control(kind: Int, sequence: Long) = ScreenUdpFragment(kind, sequence, 0, 0, 0, 0, 0, byteArrayOf())
    private fun fragments(sequence: Long, bytes: ByteArray): List<ScreenUdpFragment> {
        val count = maxOf(1, (bytes.size + ScreenUdpCipher.PAYLOAD - 1) / ScreenUdpCipher.PAYLOAD)
        return (0 until count).map { index ->
            val start = index * ScreenUdpCipher.PAYLOAD
            ScreenUdpFragment(ScreenUdpCipher.MEDIA, sequence * 10_000 + index, sequence, ToolboxProtocol.VIDEO_FRAME,
                bytes.size, index, count, bytes.copyOfRange(start, minOf(bytes.size, start + ScreenUdpCipher.PAYLOAD)))
        }
    }
}
