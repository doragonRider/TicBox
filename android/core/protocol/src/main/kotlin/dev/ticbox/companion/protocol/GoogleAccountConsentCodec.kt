package dev.ticbox.companion.protocol

import java.io.ByteArrayOutputStream

object GoogleAccountConsentCodec {
    /** Mutates only the selected DataMap entry; unrelated entries retain their exact wire bytes. */
    fun diagnosticSetting(previous: ByteArray?, enabled: Boolean): ByteArray {
        val fields = if (previous == null) emptyList() else {
            // Match the stock read-modify-write failure on malformed DataMaps.
            DataMapCodec.decode(previous)
            ProtoWire.decodeFields(previous)
        }
        val output = ByteArrayOutputStream()
        for (field in fields) {
            val name = if (field.number == 1 && field.wireType == 2) {
                ProtoWire.decodeFields(field.contents as ByteArray).lastOrNull { it.number == 1 && it.wireType == 2 }
                    ?.let { (it.contents as ByteArray).toString(Charsets.UTF_8) }
            } else null
            if (name != "system_logging_enabled") field.appendTo(output)
        }
        output.write(DataMapCodec.encode(mapOf("system_logging_enabled" to DataMapValue.Bool(enabled))))
        protocolCheck(output.size() <= ProtocolLimits.MAX_MESSAGE_BYTES, "Diagnostic DataMap size budget exceeded")
        return output.toByteArray()
    }
}
