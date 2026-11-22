package dev.ticbox.companion.protocol

import java.io.*
import kotlin.test.*

class ToolboxProtocolTest {
    @Test fun fragmentedBinaryFramesPreserveEveryByteAndBoundary() {
        val content = ByteArray(200_000) { (it * 31).toByte() }
        val encoded = ByteArrayOutputStream().also {
            ToolboxProtocol.write(it, ToolboxProtocol.DATA, content)
            ToolboxProtocol.write(it, ToolboxProtocol.END)
        }.toByteArray()
        val input = object : ByteArrayInputStream(encoded) {
            override fun read(bytes: ByteArray, offset: Int, length: Int) = super.read(bytes, offset, minOf(length, 3))
        }
        assertContentEquals(content, ToolboxProtocol.required(input).bytes)
        assertEquals(ToolboxProtocol.END, ToolboxProtocol.required(input).type)
        assertNull(ToolboxProtocol.read(input))
    }
    @Test fun incompleteHeadersBodiesAndOversizedPacketsFailClosed() {
        val encoded = ByteArrayOutputStream().also { ToolboxProtocol.write(it, ToolboxProtocol.DATA, byteArrayOf(0, -1, 10, 13)) }.toByteArray()
        for (size in 1 until encoded.size) assertFailsWith<EOFException>("prefix=$size") {
            ToolboxProtocol.required(ByteArrayInputStream(encoded.copyOf(size)))
        }
        for (length in listOf(-1, Int.MAX_VALUE, ToolboxProtocol.MAX_PACKET + 1)) {
            val bytes = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeByte(ToolboxProtocol.DATA); writeInt(length) } }.toByteArray()
            assertFailsWith<IOException> { ToolboxProtocol.required(ByteArrayInputStream(bytes)) }
        }
        assertFailsWith<IOException> { ToolboxProtocol.required(ByteArrayInputStream(byteArrayOf(99, 0, 0, 0, 0))) }
    }
    @Test fun interruptAndForceStopHaveDistinctFrames() {
        val encoded = ByteArrayOutputStream().also {
            ToolboxProtocol.write(it, ToolboxProtocol.CANCEL)
            ToolboxProtocol.write(it, ToolboxProtocol.FORCE_STOP)
        }.toByteArray()
        val input = ByteArrayInputStream(encoded)
        assertEquals(ToolboxProtocol.CANCEL, ToolboxProtocol.required(input).type)
        assertEquals(ToolboxProtocol.FORCE_STOP, ToolboxProtocol.required(input).type)
        assertFailsWith<IllegalArgumentException> { ToolboxProtocol.write(ByteArrayOutputStream(), ToolboxProtocol.SCREEN_UDP_READY + 1) }
        assertFailsWith<IOException> { ToolboxProtocol.required(ByteArrayInputStream(byteArrayOf((ToolboxProtocol.SCREEN_UDP_READY + 1).toByte(), 0, 0, 0, 0))) }
    }
    @Test fun whatAnOlderBridgeNeedsToBeUpdatedStaysAsItIs() {
        assertTrue(ToolboxProtocol.VERSION >= ToolboxProtocol.FIRST_UPDATABLE_VERSION)
        assertEquals(
            listOf(1, 2, 3, 4, 5, 6, 12),
            with(ToolboxProtocol) { listOf(REQUEST, READY, RESULT, ERROR, DATA, END, PROGRESS) },
        )
        val frame = ByteArrayOutputStream().also { ToolboxProtocol.write(it, ToolboxProtocol.DATA, byteArrayOf(7, 8)) }
        assertContentEquals(byteArrayOf(5, 0, 0, 0, 2, 7, 8), frame.toByteArray())
    }
    @Test fun quotingPreservesUnicodeAndShellMetacharactersAsOneArgument() {
        val text = "手表 ' ; \" `literal` \$(literal)\nfile name.apk"
        val process = ProcessBuilder("/bin/sh", "-c", "printf '%s' ${ToolboxProtocol.quote(text)}").start()
        assertEquals(text, process.inputStream.reader().readText())
        assertEquals(0, process.waitFor())
        assertFailsWith<IllegalArgumentException> { ToolboxProtocol.quote("bad\u0000argument") }
    }
}
