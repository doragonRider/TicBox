package com.flyfishxu.kadb.transport

import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.KadbOptions
import com.flyfishxu.kadb.cert.AdbKeyPair
import com.flyfishxu.kadb.cert.HostKeySet
import com.flyfishxu.kadb.cert.CertUtils
import com.flyfishxu.kadb.cert.InMemoryPrivateKeyStore
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.core.AdbConnection
import com.flyfishxu.kadb.pair.SslUtils
import com.flyfishxu.kadb.shell.AdbShellPacket
import com.flyfishxu.kadb.shell.AdbShellStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.conscrypt.Conscrypt

/** Real sockets and TLS records. All keys and payloads are synthetic fixtures. */
class AndroidTlsTransportTest {
    private class Peer(action: (Socket) -> Unit) : AutoCloseable {
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port get() = listener.localPort
        val workers = Executors.newCachedThreadPool()
        @Volatile private var socket: Socket? = null
        val finished = workers.submit {
            listener.accept().use { raw ->
                socket = raw
                raw.tcpNoDelay = true
                raw.soTimeout = 10_000
                action(raw)
            }
        }
        override fun close() {
            socket?.close()
            listener.close()
            workers.shutdownNow()
        }
    }

    private class Identity {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cert: X509Certificate = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(
                X500Name("CN=localhost"), BigInteger.ONE, Date(0), Date(4_102_444_800_000L),
                X500Name("CN=localhost"), key.public,
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(key.private)),
        )
        val adb = AdbKeyPair(key.private, key.public, cert)
        private val keys = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("fixture", key.private, "fixture".toCharArray(), arrayOf(cert))
        }
        private val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keys, "fixture".toCharArray())
        }
        val verifiedClients = AtomicInteger()
        private val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
                check(chain.first().publicKey == key.public) { "Client must retain the paired ADB key" }
                verifiedClients.incrementAndGet()
            }
        }
        // A separate TLS provider on the peer verifies interoperability and the negotiated protocol.
        // The client uses Conscrypt (SslUtils selects it exactly as on the published Kadb JVM path).
        val serverContext: SSLContext = SSLContext.getInstance("TLS").apply {
            init(managers.keyManagers, arrayOf(trust), SecureRandom())
        }
        val clientContext: SSLContext get() = SslUtils.getSslContext(HostKeySet(adb, listOf(adb)))
        fun server(raw: Socket, protocol: String = "TLSv1.3"): SSLSocket =
            (serverContext.socketFactory.createSocket(raw, "localhost", raw.port, false) as SSLSocket).apply {
                useClientMode = false
                enabledProtocols = arrayOf(protocol)
                needClientAuth = true
                startHandshake()
                assertEquals(protocol, session.protocol)
                assertContentEquals(cert.publicKey.encoded, session.peerCertificates.first().publicKey.encoded)
            }
    }

    private data class Frame(val command: Int, val local: Int, val remote: Int, val payload: ByteArray)
    private fun frame(input: InputStream): Frame {
        val bytes = input.readNBytes(24)
        if (bytes.size != 24) throw EOFException()
        val header = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN)
        val command = header.int
        val local = header.int
        val remote = header.int
        val length = header.int
        header.int
        check(header.int == command.inv() && length in 0..1_048_576)
        val payload = input.readNBytes(length)
        check(payload.size == length)
        return Frame(command, local, remote, payload)
    }
    private fun send(output: OutputStream, command: Int, local: Int, remote: Int, payload: ByteArray = byteArrayOf()) {
        output.write(ByteBuffer.allocate(24 + payload.size).order(LITTLE_ENDIAN).putInt(command)
            .putInt(local).putInt(remote).putInt(payload.size).putInt(0).putInt(command.inv()).put(payload).array())
        output.flush()
    }

    private fun exerciseAdb(tls: Boolean, concurrency: Int, rounds: Int) {
        val identity = Identity()
        val opens = AtomicInteger()
        val handshakes = AtomicInteger()
        Peer { raw ->
            assertEquals(CNXN, frame(raw.getInputStream()).command)
            val socket = if (tls) {
                send(raw.getOutputStream(), STLS, 0x01000000, 0)
                assertEquals(STLS, frame(raw.getInputStream()).command)
                identity.server(raw).also { handshakes.incrementAndGet() }
            } else raw
            send(socket.getOutputStream(), CNXN, 0x01000001, 1_048_576, "device::features=shell_v2;\u0000".toByteArray())
            try {
                while (true) {
                    val packet = frame(socket.getInputStream())
                    when (packet.command) {
                        OPEN -> {
                            val id = opens.incrementAndGet()
                            send(socket.getOutputStream(), OKAY, id, packet.local)
                            val body = ByteArray(129) { 17 }
                            val stdout = ByteBuffer.allocate(body.size + 5).order(LITTLE_ENDIAN).put(1).putInt(body.size).put(body).array()
                            send(socket.getOutputStream(), WRTE, id, packet.local, stdout)
                        }
                        CLSE -> send(socket.getOutputStream(), CLSE, packet.remote, packet.local)
                        OKAY -> Unit
                        else -> error("Unexpected fixture packet")
                    }
                }
            } catch (_: EOFException) { }
        }.use { peer ->
            val connection = runBlocking {
                AdbConnection.connect("127.0.0.1", peer.port, HostKeySet(identity.adb, listOf(identity.adb)),
                    KadbOptions(), connectTimeoutMs = 5_000, ioTimeoutMs = 10_000).first
            }
            connection.use { client ->
                assertTrue(client.supportsFeature("shell_v2"))
                assertEquals(tls, client.encrypted)
                val operations = List(concurrency) {
                    peer.workers.submit {
                        repeat(rounds) { index ->
                            AdbShellStream(client.open("shell,v2,raw:fixture"), encrypted = client.encrypted).use { stream ->
                                assertEquals(tls, stream.encrypted)
                                assertContentEquals(ByteArray(129) { 17 }, (stream.read() as AdbShellPacket.StdOut).payload)
                            }
                            if (index % 25 == 0) Thread.sleep(10)
                        }
                    }
                }
                operations.forEach { it.get(30, TimeUnit.SECONDS) }
                assertTrue(client.isOpen)
                assertEquals(concurrency * rounds, opens.get())
                assertEquals(if (tls) 1 else 0, handshakes.get())
                assertEquals(if (tls) 1 else 0, identity.verifiedClients.get())
            }
            peer.finished.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun sequentialAgentStreamsRetainOneMutuallyAuthenticatedTlsConnection() = exerciseAdb(true, 1, 200)
    @Test fun parallelAgentStreamsRetainOneTlsConnectionAndIndependentStreamCloses() = exerciseAdb(true, 4, 100)
    @Test fun legacyPlainAdbStillUsesItsOriginalFraming() = exerciseAdb(false, 1, 30)

    @Test fun publicShellCarriesItsPhysicalConnectionEncryptionForTlsAndPlainAdb() {
        try {
            for (encrypted in listOf(true, false)) {
                val identity = Identity()
                KadbCert.configure(InMemoryPrivateKeyStore().apply {
                    writePrivateKeyPemAtomic(CertUtils.encodePrivateKeyPem(identity.key.private))
                })
                Peer { raw ->
                    assertEquals(CNXN, frame(raw.getInputStream()).command)
                    val socket = if (encrypted) {
                        send(raw.getOutputStream(), STLS, 0x01000000, 0)
                        assertEquals(STLS, frame(raw.getInputStream()).command)
                        identity.server(raw)
                    } else raw
                    send(socket.getOutputStream(), CNXN, 0x01000001, 1_048_576, "device::features=shell_v2;\u0000".toByteArray())
                    val open = frame(socket.getInputStream())
                    assertEquals(OPEN, open.command)
                    send(socket.getOutputStream(), OKAY, 1, open.local)
                    assertEquals(CLSE, frame(socket.getInputStream()).command)
                    send(socket.getOutputStream(), CLSE, 1, open.local)
                }.use { peer ->
                    Kadb.create("127.0.0.1", peer.port, connectTimeout = 5_000, socketTimeout = 5_000).use { client ->
                        val shell = client.openShell("fixture")
                        assertEquals(encrypted, shell.encrypted)
                        shell.close()
                        peer.finished.get(3, TimeUnit.SECONDS)
                        client.resetConnection()
                        // Retiring/reconnecting the client cannot change the shell's captured state.
                        assertEquals(encrypted, shell.encrypted)
                    }
                    peer.finished.get(3, TimeUnit.SECONDS)
                }
            }
        } finally { KadbCert.configure(InMemoryPrivateKeyStore()) }
    }

    @Test fun largeBinaryTransferAndPeerKeyUpdatesUseTheTlsStreamsInBothDirections() {
        val identity = Identity()
        val bytes = ByteArray(350_001) { (it * 31).toByte() }
        Peer { raw ->
            identity.server(raw).use { tls ->
                assertContentEquals(bytes, tls.inputStream.readNBytes(bytes.size))
                bytes.asList().chunked(4093).forEachIndexed { index, chunk ->
                    // SunJSSE startHandshake on an established TLS 1.3 socket initiates KeyUpdate.
                    if (index % 7 == 0) tls.startHandshake()
                    tls.outputStream.write(chunk.toByteArray())
                    tls.outputStream.flush()
                }
            }
        }.use { peer ->
            val raw = runBlocking { PlainBlockingChannel.connect("127.0.0.1", peer.port, 5_000) }
            raw.startTls(identity.clientContext, "localhost", peer.port, 10_000).use { client ->
                runBlocking {
                    client.writeExactly(ByteBuffer.wrap(bytes), 10, TimeUnit.SECONDS)
                    val incoming = ByteBuffer.allocate(bytes.size)
                    client.readExactly(incoming, 10, TimeUnit.SECONDS)
                    assertContentEquals(bytes, incoming.array())
                }
            }
            peer.finished.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun tls12OnlyPeerIsRejectedWithoutDowngrading() {
        val identity = Identity()
        Peer { raw -> assertFailsWith<SSLException> { identity.server(raw, "TLSv1.2") } }.use { peer ->
            val raw = runBlocking { PlainBlockingChannel.connect("127.0.0.1", peer.port, 5_000) }
            assertFailsWith<SSLException> { raw.startTls(identity.clientContext, "localhost", peer.port, 5_000) }
            assertFalse(raw.isOpen)
            peer.finished.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun anUnpairedKeyCannotOpenAnAdbStream() {
        val paired = Identity()
        val different = Identity()
        Peer { raw ->
            assertEquals(CNXN, frame(raw.getInputStream()).command)
            send(raw.getOutputStream(), STLS, 0x01000000, 0)
            assertEquals(STLS, frame(raw.getInputStream()).command)
            assertFailsWith<SSLException> { paired.server(raw) }
        }.use { peer ->
            assertFailsWith<IOException> {
                runBlocking {
                    AdbConnection.connect("127.0.0.1", peer.port,
                        HostKeySet(different.adb, listOf(different.adb)), KadbOptions(),
                        connectTimeoutMs = 5_000, ioTimeoutMs = 5_000)
                }
            }
            assertEquals(0, paired.verifiedClients.get())
            peer.finished.get(3, TimeUnit.SECONDS)
        }
    }

    @Test fun stalledHandshakeHonorsTheConfiguredReadDeadlineAndClosesTheSocket() {
        val identity = Identity()
        val finish = CountDownLatch(1)
        Peer { _ -> finish.await(3, TimeUnit.SECONDS) }.use { peer ->
            val raw = runBlocking { PlainBlockingChannel.connect("127.0.0.1", peer.port, 5_000) }
            try {
                assertFailsWith<SocketTimeoutException> {
                    raw.startTls(identity.clientContext, "localhost", peer.port, 150)
                }
                assertFalse(raw.isOpen)
            } finally { finish.countDown() }
        }
    }

    @Test fun cancellationOfATlsHandshakeUnblocksTheRawTransport() {
        val identity = Identity()
        val accepted = CountDownLatch(1)
        val finish = CountDownLatch(1)
        Peer { _ -> accepted.countDown(); finish.await(3, TimeUnit.SECONDS) }.use { peer ->
            val raw = runBlocking { PlainBlockingChannel.connect("127.0.0.1", peer.port, 5_000) }
            val upgrading = peer.workers.submit { raw.startTls(identity.clientContext, "localhost", peer.port, 0) }
            try {
                assertTrue(accepted.await(2, TimeUnit.SECONDS))
                raw.close()
                assertTrue(assertFailsWith<ExecutionException> { upgrading.get(2, TimeUnit.SECONDS) }.cause is IOException)
                assertFalse(raw.isOpen)
            } finally { finish.countDown() }
        }
    }

    @Test fun closingATlsChannelUnblocksAReadWithoutWaitingForThePeer() {
        val identity = Identity()
        val finish = CountDownLatch(1)
        val started = CountDownLatch(1)
        val handshakeReady = CountDownLatch(1)
        Peer { raw -> identity.server(raw).use {
            handshakeReady.countDown()
            finish.await(4, TimeUnit.SECONDS)
        } }.use { peer ->
            val raw = runBlocking { PlainBlockingChannel.connect("127.0.0.1", peer.port, 5_000) }
            val client = raw.startTls(identity.clientContext, "localhost", peer.port, 0)
            val reading = peer.workers.submit {
                started.countDown()
                runBlocking { client.read(ByteBuffer.allocate(1), 0, TimeUnit.SECONDS) }
            }
            try {
                assertTrue(handshakeReady.await(2, TimeUnit.SECONDS))
                assertTrue(started.await(2, TimeUnit.SECONDS))
                client.close()
                assertTrue(assertFailsWith<ExecutionException> { reading.get(2, TimeUnit.SECONDS) }.cause is IOException)
                assertFalse(client.isOpen)
            } finally { finish.countDown() }
        }
    }

    private companion object {
        // Also exercise the SSLEngine-backed socket implementation in the host fixture.
        init { Conscrypt.setUseEngineSocketByDefault(true) }
        const val CNXN = 0x4e584e43
        const val STLS = 0x534c5453
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val WRTE = 0x45545257
        const val CLSE = 0x45534c43
    }
}
