package dev.ticbox.companion.platform.toolbox

import dev.ticbox.companion.api.AdbEndpoint
import dev.ticbox.companion.engine.toolbox.toolboxIo
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.*
import kotlin.test.*

class AdbPairingRelayTest {
    @Test fun arbitraryHandshakeBytesAreRelayedBidirectionallyAndCloseReleasesBlockedReaders() = runBlocking { withTimeout(5_000) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { upstream ->
            AdbPairingRelay(AdbEndpoint(upstream.inetAddress.hostAddress, upstream.localPort)).use { relay ->
                Socket("127.0.0.1", relay.port).use { client ->
                    upstream.accept().use { server ->
                        val bytes = ByteArray(60_000) { (it * 13).toByte() }
                        client.getOutputStream().write(bytes)
                        assertContentEquals(bytes, ByteArray(bytes.size).also { DataInputStream(server.getInputStream()).readFully(it) })
                        server.getOutputStream().write(bytes.reversedArray())
                        assertContentEquals(bytes.reversedArray(), ByteArray(bytes.size).also { DataInputStream(client.getInputStream()).readFully(it) })
                        val blocked = launch { toolboxIo(onCancel = relay::close) { client.getInputStream().read() } }
                        delay(20); blocked.cancelAndJoin()
                        assertTrue(runCatching { server.getInputStream().read() == -1 }.getOrDefault(true))
                    }
                }
            }
        }
    } }
}
