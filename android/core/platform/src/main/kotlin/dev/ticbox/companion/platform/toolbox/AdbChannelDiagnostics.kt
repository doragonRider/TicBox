package dev.ticbox.companion.platform.toolbox

import dev.ticbox.companion.engine.toolbox.ToolboxChannel
import dev.ticbox.companion.engine.toolbox.toolboxIo
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException

/** A completed OPEN owns a stream; only a still-blocked OPEN requires a transport reset. */
internal suspend fun openAdbAgentChannel(
    resetTransport: () -> Unit,
    onCancelled: () -> Unit = {},
    open: () -> ToolboxChannel,
): ToolboxChannel {
    val pending = AtomicReference<ToolboxChannel?>()
    return toolboxIo(onCancel = {
        runCatching(onCancelled)
        val channel = pending.get()
        if (channel != null) channel.close() else resetTransport()
    }, onDiscard = ToolboxChannel::close) {
        open().also { pending.set(it) }
    }
}

/** Classify known failures with fixed labels, without copying exception messages into diagnostics. */
internal fun adbFailureKind(error: Throwable): String {
    val causes = generateSequence(error) { it.cause }.take(16).toList()
    return when {
        error is CancellationException -> "cancelled"
        adbRequiresPairing(error) -> "certificate_rejected"
        causes.any { it is SSLException } -> "tls_failure"
        causes.any { it is SocketTimeoutException } -> "socket_timeout"
        causes.any { it.message.orEmpty().startsWith("Invalid ADB magic:") } -> "invalid_adb_magic"
        causes.any { it.message.orEmpty().startsWith("Invalid ADB payload length:") } -> "invalid_adb_length"
        causes.any { it.message.orEmpty().contains("connection reset", ignoreCase = true) } -> "connection_reset"
        causes.any { it.message.orEmpty().contains("broken pipe", ignoreCase = true) } -> "broken_pipe"
        causes.any { it is EOFException } -> "eof"
        causes.any { it.message == "Kadb is closed" } -> "client_closed"
        causes.any { it.message == "ADB connection closed" } -> "connection_closed"
        else -> "other"
    }
}

/** TLS library reason identifiers only; exclude native addresses, peer names and message bodies. */
internal fun adbTlsFailureDetails(error: Throwable): Map<String, String> {
    val failures = generateSequence(error) { it.cause }.take(16).filterIsInstance<SSLException>().toList()
    if (failures.isEmpty()) return emptyMap()
    val messages = failures.map { it.message.orEmpty() }
    val reasons = messages.flatMap { message ->
        TLS_REASONS.filter { reason -> Regex("(?<![A-Z0-9_])${Regex.escape(reason)}(?![A-Z0-9_])").containsMatchIn(message) }
    }.distinct()
    val codes = messages.flatMap { message -> TLS_ERROR_CODE.findAll(message).map { it.groupValues[1].lowercase() }.toList() }.distinct().take(8)
    val operation = failures.asSequence().flatMap { it.stackTrace.asSequence() }.mapNotNull { frame ->
        if (!frame.className.startsWith("com.android.org.conscrypt.") && !frame.className.startsWith("org.conscrypt.")) null
        else when {
            frame.methodName.contains("unwrap", true) || frame.methodName.contains("read", true) -> "read"
            frame.methodName.contains("wrap", true) || frame.methodName.contains("write", true) -> "write"
            frame.methodName.contains("handshake", true) -> "handshake"
            else -> null
        }
    }.firstOrNull()
    return buildMap {
        put("tlsReasons", reasons.joinToString(",").ifEmpty { "unclassified" })
        if (codes.isNotEmpty()) put("tlsErrorCodes", codes.joinToString(","))
        if (operation != null) put("tlsOperation", operation)
    }
}

private val TLS_ERROR_CODE = Regex("error:([0-9a-fA-F]{8}):SSL routines:")
private val TLS_REASONS = listOf(
    "DECRYPTION_FAILED_OR_BAD_RECORD_MAC", "SSLV3_ALERT_BAD_RECORD_MAC", "BAD_RECORD_MAC",
    "SSLV3_ALERT_UNEXPECTED_MESSAGE", "UNEXPECTED_MESSAGE", "UNEXPECTED_RECORD",
    "WRONG_VERSION_NUMBER", "RECORD_TOO_LARGE", "PACKET_LENGTH_TOO_LONG",
    "TLSV1_ALERT_DECODE_ERROR", "DECODE_ERROR", "TLSV1_ALERT_DECRYPT_ERROR",
    "TLSV1_ALERT_INTERNAL_ERROR", "SSLV3_ALERT_HANDSHAKE_FAILURE", "HANDSHAKE_FAILURE",
    "SSLV3_ALERT_CERTIFICATE_UNKNOWN", "TLSV1_ALERT_UNKNOWN_CA", "SSLV3_ALERT_BAD_CERTIFICATE",
    "TLSV13_ALERT_CERTIFICATE_REQUIRED", "TLSV1_ALERT_PROTOCOL_VERSION", "UNSUPPORTED_PROTOCOL",
    "UNEXPECTED_EOF_WHILE_READING", "SSL_ERROR_SYSCALL", "SSL_ERROR_ZERO_RETURN",
)

/** Record only lifecycle/failure metadata; never stream bytes, clipboard text or file names. */
internal fun observeAdbChannel(
    channel: ToolboxChannel,
    observe: (event: String, error: Throwable?) -> Unit,
): ToolboxChannel = object : ToolboxChannel {
    override val secureScreenUdp = channel.secureScreenUdp
    private val closed = AtomicBoolean()
    private val failed = AtomicBoolean()
    private inline fun <T> io(stage: String, block: () -> T): T = try { block() }
    catch (error: Exception) {
        if (failed.compareAndSet(false, true)) runCatching { observe(stage, error) }
        throw error
    }
    override val input = object : InputStream() {
        override fun read(): Int = io("channel_read_failed") { channel.input.read() }
        override fun read(bytes: ByteArray, off: Int, len: Int): Int = io("channel_read_failed") { channel.input.read(bytes, off, len) }
    }
    override val output = object : OutputStream() {
        override fun write(byte: Int) = io("channel_write_failed") { channel.output.write(byte) }
        override fun write(bytes: ByteArray, off: Int, len: Int) = io("channel_write_failed") { channel.output.write(bytes, off, len) }
        override fun flush() = io("channel_write_failed") { channel.output.flush() }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { channel.close() } finally { runCatching { observe("channel_closed", null) } }
    }
}
