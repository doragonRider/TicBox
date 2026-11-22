package dev.ticbox.companion.protocol

/** MessageApi RPC payloads have no Setup Channel length prefix. */
object SetupRpcCodec {
    fun encodeTetherConfig(config: Int): ByteArray = GmsStructs.encode("SetTetherConfig", mapOf("config" to config))
    fun encodeRestoreSkipped(): ByteArray = GmsStructs.encode("WatchRestoreStatus", mapOf("status" to 1))
    fun decodeCheckin(bytes: ByteArray): DecodedStruct = GmsStructs.decode("WatchGmsCheckinStatus", bytes)
    /** Empty correlated responses are valid; extra future fields remain inspectable metadata. */
    fun decodeEmptyResponse(bytes: ByteArray): List<ProtoField> = ProtoWire.decodeFields(bytes)
}
