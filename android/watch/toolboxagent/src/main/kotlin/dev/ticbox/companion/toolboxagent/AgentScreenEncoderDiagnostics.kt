package dev.ticbox.companion.toolboxagent

import dev.ticbox.companion.protocol.ScreenEncoderDiagnostics
import java.io.InputStream
import kotlinx.serialization.json.JsonObject

/** Uses the already-owned stderr drainer, with bounded lines and an exact metadata prefix. */
internal fun drainScreenEncoderDiagnostics(input: InputStream, publish: (JsonObject) -> Unit) {
    val buffer = ByteArray(8_192)
    val line = ByteArray(ScreenEncoderDiagnostics.MAX_BYTES + ScreenEncoderDiagnostics.PREFIX.length)
    var length = 0
    var overflow = false
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return // An unterminated record is incomplete, not a diagnostic.
        for (i in 0 until read) {
            val byte = buffer[i]
            if (byte == '\n'.code.toByte()) {
                if (!overflow && length > ScreenEncoderDiagnostics.PREFIX.length &&
                    ScreenEncoderDiagnostics.PREFIX.indices.all { line[it] == ScreenEncoderDiagnostics.PREFIX[it].code.toByte() }) {
                    ScreenEncoderDiagnostics.parse(line.copyOfRange(ScreenEncoderDiagnostics.PREFIX.length, length))?.let { runCatching { publish(it) } }
                }
                length = 0; overflow = false
            } else if (!overflow) {
                if (length == line.size) overflow = true else line[length++] = byte
            }
        }
    }
}
