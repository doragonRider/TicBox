package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.ToolboxScreenInput
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import kotlin.test.*

class ScrcpyControlTest {
    @Test fun lightweightKeyFrameRequestIsSeparateFromCaptureReset() {
        assertContentEquals(byteArrayOf(127), ScrcpyControl.requestKeyFrame())
        assertContentEquals(byteArrayOf(17), ScrcpyControl.resetVideo())
    }

    @Test fun utf8ClipboardAndTextLengthsUseBytesNotCharacters() {
        val text = "剪贴板 🕒\n"; val bytes = text.toByteArray()
        val input = DataInputStream(ByteArrayInputStream(ScrcpyControl.clipboard(text, true, 123)))
        assertEquals(9, input.readUnsignedByte()); assertEquals(123, input.readLong()); assertTrue(input.readBoolean())
        assertEquals(bytes.size, input.readInt()); assertContentEquals(bytes, input.readAllBytes())
        val typed = ScrcpyControl.encode(ToolboxScreenInput.Text(text))
        assertEquals(1, typed[0].toInt()); assertContentEquals(bytes, typed.copyOfRange(5, typed.size))
    }
    @Test fun touchUsesFrameGeometryAndUnsignedPressure() {
        val input = DataInputStream(ByteArrayInputStream(ScrcpyControl.encode(ToolboxScreenInput.Touch(0, -1, 160, 240, 320, 480))))
        assertEquals(2, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte()); assertEquals(-1, input.readLong())
        assertEquals(160, input.readInt()); assertEquals(240, input.readInt()); assertEquals(320, input.readUnsignedShort()); assertEquals(480, input.readUnsignedShort())
        assertEquals(65535, input.readUnsignedShort()); assertEquals(0, input.readInt()); assertEquals(1, input.readInt()); assertEquals(-1, input.read())
        assertFailsWith<IllegalArgumentException> { ScrcpyControl.encode(ToolboxScreenInput.Touch(0, 0, 320, 0, 320, 480)) }
    }
    @Test fun scrollClampsSignedFixedPointAndRejectsNonFiniteValues() {
        val data = ScrcpyControl.encode(ToolboxScreenInput.Scroll(0, 0, 320, 480, -2f, 2f))
        val input = DataInputStream(ByteArrayInputStream(data)).apply { skipBytes(13) }
        assertEquals(-32768, input.readShort().toInt()); assertEquals(32767, input.readShort().toInt())
        assertFailsWith<IllegalArgumentException> { ScrcpyControl.encode(ToolboxScreenInput.Scroll(0, 0, 320, 480, Float.NaN, 0f)) }
    }
    @Test fun longTextIsSplitAtUtf8BoundariesWithinTheServersActual300ByteLimit() {
        val text = "🕒测试 text ".repeat(100)
        val parts = ScrcpyControl.textFrames(text).map { frame ->
            val input = DataInputStream(ByteArrayInputStream(frame)); assertEquals(1, input.readUnsignedByte())
            val size = input.readInt(); assertTrue(size in 1..300)
            input.readAllBytes().toString(Charsets.UTF_8).also { assertFalse(it.contains('\uFFFD')) }
        }.toList()
        assertTrue(parts.size > 1); assertEquals(text, parts.joinToString(""))
        assertFailsWith<IllegalArgumentException> { ScrcpyControl.clipboard("x".repeat((1 shl 18) - 13), false, 1) }
    }
}
