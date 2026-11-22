package dev.ticbox.companion.platform.toolbox

import com.flyfishxu.kadb.shell.AdbShellPacket
import dev.ticbox.companion.api.ToolboxException
import dev.ticbox.companion.protocol.ToolboxProtocol
import java.io.ByteArrayInputStream
import kotlin.test.*

class AdbShellChannelTest {
    @Test fun encryptedShellObservationSurvivesPacketAndDiagnosticsWrappers() {
        val unknown = shellPacketChannel({ AdbShellPacket.Exit(byteArrayOf(0)) }, {}, {})
        assertFalse(unknown.secureScreenUdp)
        unknown.close()
        for (encrypted in listOf(false, true)) {
            var closed = false
            val shell = shellPacketChannel({ AdbShellPacket.Exit(byteArrayOf(0)) }, {}, { closed = true }, encrypted = encrypted)
            val observed = observeAdbChannel(shell) { _, _ -> }
            assertEquals(encrypted, shell.secureScreenUdp)
            assertEquals(encrypted, observed.secureScreenUdp)
            observed.close()
            assertTrue(closed)
            assertEquals(encrypted, observed.secureScreenUdp)
        }
    }

    @Test fun aTouchControlFrameUsesOneShellWriteInsteadOfSeparateAckedHeaderWrites() {
        val writes = mutableListOf<ByteArray>(); var closed = false
        val channel = shellPacketChannel({ error("not reading") }, { writes += it }, { closed = true })
        val touch = ByteArray(32) { it.toByte() }
        ToolboxProtocol.write(channel.output, ToolboxProtocol.CONTROL, touch)
        assertEquals(1, writes.size, "Each shell write can cost an ADB acknowledgement round trip")
        val packet = ToolboxProtocol.required(ByteArrayInputStream(writes.single()))
        assertEquals(ToolboxProtocol.CONTROL, packet.type)
        assertContentEquals(touch, packet.bytes)
        assertFalse(closed)
        ToolboxProtocol.write(channel.output, ToolboxProtocol.CANCEL)
        assertEquals(2, writes.size)
        assertEquals(ToolboxProtocol.CANCEL, ToolboxProtocol.required(ByteArrayInputStream(writes.last())).type)
        channel.close()
    }

    @Test fun adbShellV2SeparatesBinaryStdoutAndDiagnosticsAcrossArbitraryReads() {
        val packets = ArrayDeque(listOf(AdbShellPacket.StdError("private diagnostic".toByteArray()),
            AdbShellPacket.StdOut(byteArrayOf()), AdbShellPacket.StdOut(byteArrayOf(0, -1, 13)),
            AdbShellPacket.StdOut(byteArrayOf(10, 9)), AdbShellPacket.Exit(byteArrayOf(0))))
        val writes = mutableListOf<ByteArray>(); var closed = false
        val channel = shellPacketChannel({ packets.removeFirst() }, { writes += it }, { closed = true })
        val received = mutableListOf<Byte>()
        while (true) { val bytes = ByteArray(2); val n = channel.input.read(bytes); if (n == -1) break; received += bytes.take(n) }
        assertContentEquals(byteArrayOf(0, -1, 13, 10, 9), received.toByteArray())
        channel.output.write(byteArrayOf(99, 0, -1, 88), 1, 2); assertContentEquals(byteArrayOf(0, -1), writes.single())
        channel.close(); assertTrue(closed)
    }
    @Test fun nonzeroAndMalformedAgentExitsCannotLookLikeCleanEof() {
        for (payload in listOf(byteArrayOf(77), byteArrayOf())) {
            val channel = shellPacketChannel({ AdbShellPacket.Exit(payload) }, {}, {})
            assertEquals("agent_process_failed", assertFailsWith<ToolboxException> { channel.input.read() }.code)
        }
    }
}
