package dev.ticbox.companion.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

object WatchFacePhotoProtocol {
    fun combine(json: ByteArray, image: ByteArray, maxBytes: Int = ProtocolLimits.MAX_MESSAGE_BYTES): ByteArray {
        protocolCheck(json.size.toLong() + image.size + 4 <= maxBytes, "Watch face photo byte budget exceeded")
        return ByteBuffer.allocate(4 + json.size + image.size).order(ByteOrder.BIG_ENDIAN).putInt(json.size).put(json).put(image).array()
    }
    fun split(bytes: ByteArray, maxBytes: Int = ProtocolLimits.MAX_MESSAGE_BYTES): Pair<ByteArray, ByteArray> {
        protocolCheck(bytes.size in 4..maxBytes, "Watch face photo length budget exceeded")
        val length = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).int
        protocolCheck(length >= 0 && length <= bytes.size - 4, "Truncated watch face photo JSON")
        return bytes.copyOfRange(4, 4 + length) to bytes.copyOfRange(4 + length, bytes.size)
    }
}
