package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.math.roundToInt

internal class AgentScreenSession(
    private val link: AgentLink,
    worker: CoroutineScope,
    video: Boolean = true,
    startupTimeoutMillis: Long = 30_000,
    private val trace: ScreenDiagnostics? = null,
) : ToolboxScreenSession {
    init { require(startupTimeoutMillis > 0) }
    private val channel = Channel<ToolboxScreenEvent>(2)
    override val events: Flow<ToolboxScreenEvent> = channel.receiveAsFlow()
    private val sequence = AtomicLong()
    private val firstFrame = CompletableDeferred<Unit>()
    // Off the worker's dispatcher, which is the main thread in the app: every packet would wait there.
    private val reading: Job = worker.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
        try {
            var configured = false
            while (true) {
                val packet = link.read()
                when (packet.type) {
                    ToolboxProtocol.VIDEO_CONFIG -> packet.objectValue().let {
                        val width = it.number("width"); val height = it.number("height")
                        if (width == null || height == null || width !in 1..65_535L || height !in 1..65_535L) throw ToolboxException("screen_protocol_mismatch")
                        trace?.event("video_configuration", mapOf("codec" to it.required("codec"), "width" to width.toString(), "height" to height.toString()))
                        channel.send(ToolboxScreenEvent.Configuration(it.required("codec"), width.toInt(), height.toInt()))
                        configured = true
                    }
                    ToolboxProtocol.VIDEO_FRAME -> {
                        if (!configured || packet.bytes.size < 8) throw ToolboxException("invalid_video_frame")
                        val flags = ByteBuffer.wrap(packet.bytes).long
                        val frame = ToolboxScreenEvent.Frame(flags and 0x3fff_ffff_ffff_ffffL, flags and (1L shl 62) != 0L, flags and Long.MIN_VALUE != 0L, packet.bytes.copyOfRange(8, packet.bytes.size))
                        channel.send(frame)
                        if (!frame.codecConfig && frame.bytes.isNotEmpty()) firstFrame.complete(Unit)
                    }
                    ToolboxProtocol.AUDIO_CONFIG -> channel.send(ToolboxScreenEvent.AudioConfiguration(packet.objectValue().text("codec")))
                    ToolboxProtocol.AUDIO_FRAME -> {
                        if (packet.bytes.size < 8) throw ToolboxException("invalid_audio_frame")
                        val flags = ByteBuffer.wrap(packet.bytes).long
                        val bytes = packet.bytes.copyOfRange(8, packet.bytes.size)
                        channel.send(ToolboxScreenEvent.AudioFrame(flags and 0x3fff_ffff_ffff_ffffL, flags and Long.MIN_VALUE != 0L, bytes))
                    }
                    ToolboxProtocol.CLIPBOARD -> channel.send(ToolboxScreenEvent.Clipboard(packet.bytes.toString(Charsets.UTF_8)))
                    ToolboxProtocol.CLIPBOARD_ACK -> { if (packet.bytes.size != 8) throw ToolboxException("invalid_clipboard_ack"); channel.send(ToolboxScreenEvent.ClipboardAcknowledged(ByteBuffer.wrap(packet.bytes).long)) }
                    ToolboxProtocol.EXIT, ToolboxProtocol.END -> break
                    else -> link.unexpected(packet)
                }
            }
            channel.close()
        } catch (error: Throwable) { trace?.finish(error); channel.close(error) }
        finally { trace?.finish(); startup?.cancel(); link.close() }
    }
    // A connected agent/control socket is insufficient: require the first media frame too.
    // This deadline ends once video starts; a static screen may then remain idle indefinitely.
    private val startup: Job? = if (video) worker.launch(start = CoroutineStart.LAZY) {
        try { withTimeout(startupTimeoutMillis) { firstFrame.await() } }
        catch (timeout: TimeoutCancellationException) {
            if (!currentCoroutineContext().isActive) throw timeout
            trace?.finish(ToolboxException("timeout", timeout))
            channel.close(ToolboxException("timeout", timeout))
            link.close()
            reading.cancel()
        }
    } else null
    init { reading.start(); startup?.start() }
    override fun reportPlayback(sample: ToolboxScreenPlayback) { trace?.playback(sample) }
    override fun reportControlQueued(action: Int, waitMillis: Long) { trace?.controlQueued(action, waitMillis) }
    override suspend fun input(event: ToolboxScreenInput) {
        suspend fun send() {
            if (event is ToolboxScreenInput.Text) ScrcpyControl.textFrames(event.text).forEach { link.send(ToolboxProtocol.CONTROL, it) }
            else link.send(ToolboxProtocol.CONTROL, ScrcpyControl.encode(event))
        }
        if (trace != null) trace.control(event, ::send) else send()
    }
    override suspend fun getClipboard() { link.send(ToolboxProtocol.CONTROL, byteArrayOf(8, 0)) }
    override suspend fun setClipboard(text: String, paste: Boolean): Long {
        val next = sequence.incrementAndGet(); link.send(ToolboxProtocol.CONTROL, ScrcpyControl.clipboard(text, paste, next)); return next
    }
    override fun close() {
        trace?.finish()
        // Publish owner cancellation before closing the socket (including the close invoked by
        // read cancellation). Otherwise the awakened reader can close this channel with a
        // SocketException first, and a later cancel cannot replace that terminal cause.
        // A remote failure already published before this call remains the terminal cause.
        channel.cancel()
        startup?.cancel()
        firstFrame.cancel()
        reading.cancel()
        link.close()
    }
}

/** Pinned scrcpy 4.1 control protocol, including UTF-8 byte lengths and fixed-point fields. */
object ScrcpyControl {
    fun encode(event: ToolboxScreenInput): ByteArray = bytes { when (event) {
        is ToolboxScreenInput.Key -> { require(event.keyCode >= 0 && event.repeat >= 0); writeByte(0); writeByte(if (event.down) 0 else 1); writeInt(event.keyCode); writeInt(event.repeat); writeInt(event.metaState) }
        is ToolboxScreenInput.Text -> { writeByte(1); string(event.text, 300) }
        is ToolboxScreenInput.Touch -> {
            require(event.action in 0..6 && event.pressure.isFinite() && event.pressure in 0f..1f)
            writeByte(2); writeByte(event.action); writeLong(event.pointerId)
            position(event.x, event.y, event.width, event.height)
            writeShort((event.pressure * 65535).roundToInt()); writeInt(event.actionButton); writeInt(event.buttons)
        }
        is ToolboxScreenInput.Scroll -> {
            writeByte(3); position(event.x, event.y, event.width, event.height)
            fun fixed(value: Float): Int { require(value.isFinite()); return (value.coerceIn(-1f, 1f) * 32768).roundToInt().coerceIn(-32768, 32767) }
            writeShort(fixed(event.horizontal)); writeShort(fixed(event.vertical)); writeInt(event.buttons)
        }
        is ToolboxScreenInput.DisplayPower -> { writeByte(10); writeBoolean(event.on) }
    } }
    /** scrcpy 4.1 TYPE_RESET_VIDEO: an empty control message, which resets only capture/encoding. */
    fun resetVideo(): ByteArray = byteArrayOf(17)

    /** TicBox scrcpy extension; use only after SCREEN_KEYFRAME capability negotiation. */
    fun requestKeyFrame(): ByteArray = byteArrayOf(127)

    fun clipboard(text: String, paste: Boolean, sequence: Long): ByteArray = bytes { writeByte(9); writeLong(sequence); writeBoolean(paste); string(text, (1 shl 18) - 14) }
    fun textFrames(text: String): Sequence<ByteArray> = sequence {
        val encoded = text.toByteArray(Charsets.UTF_8); require(encoded.size <= 1_048_576)
        var start = 0
        while (start < encoded.size) {
            var end = minOf(start + 300, encoded.size)
            while (end < encoded.size && encoded[end].toInt() and 0xc0 == 0x80) end--
            yield(bytes { writeByte(1); writeInt(end - start); write(encoded, start, end - start) })
            start = end
        }
    }
    private fun DataOutputStream.position(x: Int, y: Int, width: Int, height: Int) {
        require(width in 1..65535 && height in 1..65535 && x in 0 until width && y in 0 until height)
        writeInt(x); writeInt(y); writeShort(width); writeShort(height)
    }
    private fun DataOutputStream.string(text: String, maximum: Int) { val value = text.toByteArray(Charsets.UTF_8); require(value.size <= maximum); writeInt(value.size); write(value) }
    private fun bytes(write: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).apply(write) }.toByteArray()
}
