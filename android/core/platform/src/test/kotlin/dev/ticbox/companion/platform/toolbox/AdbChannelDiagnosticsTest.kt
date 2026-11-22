package dev.ticbox.companion.platform.toolbox

import dev.ticbox.companion.engine.toolbox.ToolboxChannel
import java.io.*
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import javax.net.ssl.SSLProtocolException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AdbChannelDiagnosticsTest {
    @Test fun cancellationAfterOpenClosesOnlyItsStreamAndKeepsTheSharedTransport() = runTest {
        val started = CountDownLatch(1); val finish = CountDownLatch(1); val resumed = CountDownLatch(1)
        val reset = AtomicInteger(); val closed = AtomicInteger()
        val delegate = StandardTestDispatcher(testScheduler)
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                delegate.dispatch(context, block); resumed.countDown()
            }
        }
        val opening = async(dispatcher, start = CoroutineStart.UNDISPATCHED) {
            openAdbAgentChannel(resetTransport = { reset.incrementAndGet() }) {
                started.countDown(); check(finish.await(3, SECONDS))
                observeAdbChannel(testChannel { closed.incrementAndGet() }) { _, _ -> }
            }
        }
        try {
            assertTrue(started.await(3, SECONDS)); finish.countDown()
            assertTrue(resumed.await(3, SECONDS), "OPEN must have returned before cancellation")
            opening.cancel(); runCurrent(); opening.join()
            assertEquals(0, reset.get())
            assertEquals(1, closed.get())
        } finally { finish.countDown(); opening.cancel() }
    }

    @Test fun cancellationWhileOpenIsBlockedResetsTheCapturedTransportAndDiscardsALateStream() = runBlocking {
        val started = CountDownLatch(1); val finish = CountDownLatch(1); val closed = CountDownLatch(1)
        val resets = AtomicInteger()
        val opening = async(start = CoroutineStart.UNDISPATCHED) {
            openAdbAgentChannel(resetTransport = { resets.incrementAndGet(); finish.countDown() }) {
                started.countDown(); check(finish.await(3, SECONDS))
                observeAdbChannel(testChannel { closed.countDown() }) { _, _ -> }
            }
        }
        try {
            assertTrue(started.await(3, SECONDS)); opening.cancelAndJoin()
            assertTrue(closed.await(3, SECONDS))
            assertEquals(1, resets.get())
        } finally { finish.countDown(); opening.cancel() }
    }

    private fun testChannel(onClose: () -> Unit): ToolboxChannel = object : ToolboxChannel {
        override val input = byteArrayOf().inputStream()
        override val output = ByteArrayOutputStream()
        override fun close() = onClose()
    }

    @Test fun knownTransportFailuresUseFixedLabelsAndUnknownMessagesStayPrivate() {
        assertEquals("certificate_rejected", adbFailureKind(SSLProtocolException("SSLV3_ALERT_CERTIFICATE_UNKNOWN")))
        assertEquals("tls_failure", adbFailureKind(SSLProtocolException("private TLS diagnostic")))
        assertEquals("socket_timeout", adbFailureKind(IOException("wrapper", SocketTimeoutException())))
        assertEquals("connection_reset", adbFailureKind(SocketException("Connection reset by peer")))
        assertEquals("broken_pipe", adbFailureKind(IOException("Broken pipe")))
        assertEquals("invalid_adb_magic", adbFailureKind(IOException("Invalid ADB magic: private values")))
        assertEquals("invalid_adb_length", adbFailureKind(IOException("Invalid ADB payload length: private values")))
        assertEquals("eof", adbFailureKind(EOFException("private values")))
        assertEquals("client_closed", adbFailureKind(IOException("Kadb is closed")))
        assertEquals("connection_closed", adbFailureKind(IOException("ADB connection closed")))
        assertEquals("cancelled", adbFailureKind(CancellationException("private cancellation")))
        assertEquals("other", adbFailureKind(IOException("clipboard text or file path")))
    }

    @Test fun tlsReasonsRetainLibraryCodesWithoutNativeAddressesOrPrivateContext() {
        val error = SSLProtocolException("Read error: ssl=0xb400007575abec08: private context\n" +
            "error:1000008b:SSL routines:OPENSSL_internal:DECRYPTION_FAILED_OR_BAD_RECORD_MAC (private/source.cc:486 0xb4000074c7d11b40)")
        error.stackTrace = arrayOf(StackTraceElement("com.android.org.conscrypt.NativeCrypto", "ENGINE_SSL_read_direct", "private/source.cc", 486))
        assertEquals(mapOf("tlsReasons" to "DECRYPTION_FAILED_OR_BAD_RECORD_MAC", "tlsErrorCodes" to "1000008b", "tlsOperation" to "read"), adbTlsFailureDetails(error))
    }

    @Test fun tlsAlertRejectingOurWritesIsDistinctFromOurOwnDecryptionFailure() {
        val peerAlert = SSLProtocolException("error:10000419:SSL routines:OPENSSL_internal:SSLV3_ALERT_BAD_RECORD_MAC")
        val details = adbTlsFailureDetails(IOException("private wrapper", peerAlert))
        assertEquals("SSLV3_ALERT_BAD_RECORD_MAC", details["tlsReasons"])
        assertEquals("10000419", details["tlsErrorCodes"])
        assertTrue(details.values.none { it.contains("private") })
    }

    @Test fun unknownTlsTextAndOtherFailuresDoNotBecomeDiagnosticMessages() {
        assertEquals(mapOf("tlsReasons" to "unclassified"), adbTlsFailureDetails(SSLProtocolException("sensitive filename and clipboard text")))
        assertTrue(adbTlsFailureDetails(IOException("error:12345678:SSL routines:OPENSSL_internal:BAD_RECORD_MAC")).isEmpty())
    }

    @Test fun bytesAndTheOriginalFailureReachTheCallerWithOneFailureObservation() {
        val failure = IOException("test transport failure")
        val sent = ByteArrayOutputStream()
        var broken = false
        var closed = 0
        val channel = object : ToolboxChannel {
            override val input = byteArrayOf(0, -1, 7).inputStream()
            override val output = object : OutputStream() {
                override fun write(byte: Int) { if (broken) throw failure; sent.write(byte) }
                override fun write(bytes: ByteArray, off: Int, len: Int) { if (broken) throw failure; sent.write(bytes, off, len) }
            }
            override fun close() { closed++ }
        }
        val observations = mutableListOf<Pair<String, Throwable?>>()
        val observed = observeAdbChannel(channel) { event, error -> observations += event to error }
        assertContentEquals(byteArrayOf(0, -1, 7), observed.input.readBytes())
        observed.output.write(byteArrayOf(5, 9, -1, 8), 1, 2)
        assertContentEquals(byteArrayOf(9, -1), sent.toByteArray())
        broken = true
        repeat(2) { assertSame(failure, assertFailsWith<IOException> { observed.output.write(0) }) }
        observed.close(); observed.close()
        assertEquals(listOf("channel_write_failed", "channel_closed"), observations.map { it.first })
        assertSame(failure, observations.first().second)
        assertEquals(1, closed)
    }

    @Test fun diagnosticsFailingNeverReplaceTheTransportFailureOrPreventClose() {
        val failure = EOFException("test EOF")
        var closed = false
        val observed = observeAdbChannel(object : ToolboxChannel {
            override val input = object : InputStream() { override fun read(): Int = throw failure }
            override val output = ByteArrayOutputStream()
            override fun close() { closed = true }
        }) { _, _ -> throw IllegalStateException("logger failed") }
        assertSame(failure, assertFailsWith<EOFException> { observed.input.read() })
        observed.close()
        assertTrue(closed)
    }
}
