package dev.ticbox.companion.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WatchFaceWireComplication(val slotId: Int?, val providerComponent: String?, val dataType: Int?, val raw: ByteArray? = null)
data class WatchFaceWireFavorite(
    val id: Int?, val componentName: String?, val style: ByteArray? = null,
    val complications: List<WatchFaceWireComplication> = emptyList(), val previewAssetName: String? = null,
    val instanceId: String? = null, val preview: ByteArray? = null,
)
data class WatchFaceWireActive(val componentName: String?, val favoriteId: Int?)
data class WatchFaceWireOperation(val requestId: Int?, val status: Int?)
data class WatchFaceEditMessage(
    val kind: Int, val token: String? = null, val status: Int? = null, val requestId: Int? = null,
    val screenshot: ByteArray? = null, val styleSchema: ByteArray? = null, val complicationStates: ByteArray? = null,
    val unknownFields: List<Int> = emptyList(),
)

/** Source-bound Google companion paths; the transport supplies the configured AppKey. */
object WatchFaceProtocol {
    const val INSTALLED_PREFIX = "/watch_face/"
    const val ACTIVE_PATH = "/active/data/active_watch_face"
    const val FAVORITE_PREFIX = "/favorites/data/favorite/"
    const val ORDER_PATH = "/favorites/data/favorites_order"
    const val ADD_PATH = "/favorites/rpc/add_favorite"
    const val REMOVE_PATH = "/favorites/rpc/remove_favorite"
    const val REORDER_PATH = "/favorites/rpc/change_favorites_order"
    const val CHANGE_ACTIVE_PATH = "/favorites/rpc/change_active_watch_face"
    const val OPERATION_RESPONSE = "/favorites/rpc/favorites_operation_response"
    const val EDIT_CHANNEL = "/wf_editing_session/channel"
    const val MOBVOI_SET = "/tic_watchface_market/set"
    const val MOBVOI_IMAGE = "/tic_watchface_market/get_image"
    const val MOBVOI_PHOTO_INFO = "/tic_watchface_market/get_image_watchface_info"
    const val MOBVOI_BACKGROUND = "/tic_watchface_market/set_back_image"
    const val MOBVOI_PHOTO_COMBINED = "/tic_watchface_market/set_image_watchface"
    const val MOBVOI_PHOTO_CONFIRM = "/tic_watchface_market/set_back_image_confirm"
    const val MOBVOI_DOWNLOAD = "/tic_watchface_market/download_watchface"

    fun active(bytes: ByteArray): WatchFaceWireActive = fields(bytes).let { WatchFaceWireActive(it.wrapperText(1), it.wrapperInt(2)) }
    fun operation(bytes: ByteArray): WatchFaceWireOperation = fields(bytes).let { WatchFaceWireOperation(it.wrapperInt(1), it.int(2) ?: 0) }
    fun favorite(bytes: ByteArray): WatchFaceWireFavorite = fields(bytes).let {
        WatchFaceWireFavorite(it.wrapperInt(1), it.wrapperText(2), it.bytes(3), it.allBytes(4).map(::complication), it.wrapperText(5), it.wrapperText(6))
    }
    fun complication(bytes: ByteArray): WatchFaceWireComplication = fields(bytes).let { WatchFaceWireComplication(it.wrapperInt(1), it.wrapperText(2), it.wrapperInt(3), bytes.copyOf()) }
    fun complication(value: WatchFaceWireComplication): ByteArray = join(listOfNotNull(
        value.raw?.let { join(fields(it).filter { field -> field.number !in 1..3 }.map { field -> field.raw }) },
        value.slotId?.let { wi(1, it) }, value.providerComponent?.let { ws(2, it) }, value.dataType?.let { wi(3, it) }))
    fun styleOptions(bytes: ByteArray): Map<String, ByteArray> = fields(bytes).allBytes(2).mapNotNull { raw ->
        val entry = fields(raw); val key = entry.text(1) ?: return@mapNotNull null
        key to (entry.bytes(2) ?: byteArrayOf())
    }.toMap()
    fun style(options: Map<String, ByteArray>, raw: ByteArray? = null): ByteArray = join(
        (raw?.let { fields(it).filter { field -> field.number != 2 }.map { field -> field.raw } } ?: emptyList()) +
            options.toSortedMap().map { (key, value) -> b(2, b(1, key.toByteArray()) + b(2, value)) })
    fun order(bytes: ByteArray): List<Int> = fields(bytes).numbers(1)
    fun add(requestId: Int, component: String, flavorId: String? = null): ByteArray = wi(1, requestId) + ws(2, component) + (flavorId?.let { ws(3, it) } ?: byteArrayOf())
    fun remove(requestId: Int, id: Int, fallbackId: Int?): ByteArray = wi(1, requestId) + wi(2, id) + (fallbackId?.let { wi(3, it) } ?: byteArrayOf())
    fun change(requestId: Int, id: Int): ByteArray = wi(1, requestId) + wi(2, id)
    fun reorder(requestId: Int, ids: List<Int>): ByteArray = wi(1, requestId) + if (ids.isEmpty()) byteArrayOf() else b(2, join(ids.map { ProtoWire.varint(it.toLong().toULong()) }))

    fun startEditing(favoriteId: Int, width: Int, height: Int, abortCurrent: Boolean): ByteArray = request(1,
        wi(1, favoriteId) + wi(2, if (abortCurrent) 1 else 0) + wi(3, width) + wi(4, height))
    fun abortEditing(token: String): ByteArray = request(3, ws(1, token))
    fun endEditing(token: String, favorite: WatchFaceWireFavorite): ByteArray = request(2, ws(1, token) + b(2, join(listOfNotNull(
        favorite.id?.let { wi(1, it) }, favorite.componentName?.let { ws(2, it) }, favorite.style?.let { b(3, it) },
        join(favorite.complications.map { b(4, complication(it)) }), favorite.preview?.let { b(5, it) }))))
    fun screenshot(requestId: Int, token: String, style: ByteArray?, complications: List<WatchFaceWireComplication>, quality: Int?, compression: Int,
        renderParameters: ByteArray? = null): ByteArray = request(4, join(listOfNotNull(wi(1, requestId), ws(2, token),
        renderParameters?.let { b(3, it) }, quality?.let { wi(4, it) }, style?.let { b(6, it) },
        join(complications.map { b(7, complication(it)) }), n(8, compression))))
    fun complicationScreenshot(requestId: Int, token: String, info: WatchFaceWireComplication, style: ByteArray?, quality: Int?, compression: Int,
        calendarTimeMillis: Long?, renderParameters: ByteArray? = null): ByteArray = request(5, join(listOfNotNull(
        wi(1, requestId), ws(2, token), b(3, complication(info)), renderParameters?.let { b(4, it) }, quality?.let { wi(5, it) },
        calendarTimeMillis?.let { b(6, n(1, it)) }, style?.let { b(7, it) }, n(8, compression))))
    private fun request(tag: Int, bytes: ByteArray) = b(1, b(tag, bytes))

    /** Response kind 1=start, 2=end, 3=screenshot, 4=metadata; 0=other/unknown. */
    fun editingMessage(bytes: ByteArray): WatchFaceEditMessage {
        val root = fields(bytes)
        val selected = root.lastOrNull { it.wireType == 2 && it.number in 1..3 }
            ?: return WatchFaceEditMessage(0, unknownFields = root.map { it.number })
        if (selected.number == 3) {
            val meta = fields(selected.value as ByteArray)
            return WatchFaceEditMessage(4, styleSchema = meta.bytes(1), complicationStates = meta.bytes(2), unknownFields = meta.filter { it.number !in 1..2 }.map { it.number })
        }
        if (selected.number != 2) return WatchFaceEditMessage(0, unknownFields = root.map { it.number })
        val response = fields(selected.value as ByteArray)
        val message = response.lastOrNull { it.wireType == 2 && it.number in 1..3 }
            ?: return WatchFaceEditMessage(0, unknownFields = response.map { it.number })
        val body = fields(message.value as ByteArray)
        return if (message.number == 3) WatchFaceEditMessage(3, status = body.int(2), requestId = body.wrapperInt(1), screenshot = body.bytes(3), unknownFields = body.filter { it.number !in 1..3 }.map { it.number })
        else WatchFaceEditMessage(message.number, token = body.wrapperText(1), status = body.int(2), unknownFields = body.filter { it.number !in 1..2 }.map { it.number })
    }

    fun frame(bytes: ByteArray): ByteArray {
        protocolCheck(bytes.size <= ProtocolLimits.MAX_MESSAGE_BYTES, "Watch face frame budget exceeded")
        return ByteBuffer.allocate(4 + bytes.size).order(ByteOrder.BIG_ENDIAN).putInt(bytes.size).put(bytes).array()
    }
    internal fun fields(bytes: ByteArray) = ProtoWire.decodeFields(bytes)
    internal fun List<ProtoField>.bytes(n: Int) = lastOrNull { it.number == n && it.wireType == 2 }?.value as? ByteArray
    internal fun List<ProtoField>.allBytes(n: Int) = filter { it.number == n && it.wireType == 2 }.map { it.value as ByteArray }
    internal fun List<ProtoField>.text(n: Int) = bytes(n)?.toString(Charsets.UTF_8)
    internal fun List<ProtoField>.int(n: Int) = (lastOrNull { it.number == n && it.wireType == 0 }?.value as? ULong)?.toInt()
    internal fun List<ProtoField>.wrapperInt(n: Int) = bytes(n)?.let { fields(it).int(1) ?: 0 }
    internal fun List<ProtoField>.wrapperText(n: Int) = bytes(n)?.let { fields(it).text(1) ?: "" }
    internal fun List<ProtoField>.numbers(n: Int): List<Int> = buildList {
        for (field in this@numbers.filter { it.number == n }) when (field.wireType) {
            0 -> add((field.value as ULong).toInt())
            2 -> { val data = field.value as ByteArray; var offset = 0
                while (offset < data.size) { protocolCheck(size < ProtocolLimits.MAX_FIELDS, "Watch face list budget exceeded"); val v = ProtoWire.readVarint(data, offset); offset = v.next; add(v.value.toInt()) } }
        }
    }
    internal fun b(n: Int, bytes: ByteArray) = ProtoWire.encodedField(n, 2, bytes)
    internal fun n(n: Int, value: Long) = ProtoWire.encodedField(n, 0, ProtoWire.varint(value.toULong()))
    internal fun n(n: Int, value: Int) = n(n, value.toLong())
    internal fun wi(number: Int, value: Int) = b(number, if (value == 0) byteArrayOf() else n(1, value))
    internal fun ws(number: Int, value: String) = b(number, if (value.isEmpty()) byteArrayOf() else b(1, value.toByteArray(Charsets.UTF_8)))
    private fun join(values: List<ByteArray>): ByteArray = ByteArrayOutputStream().apply {
        values.forEach { protocolCheck(it.size <= ProtocolLimits.MAX_MESSAGE_BYTES - size(), "Watch face data budget exceeded"); write(it) }
    }.toByteArray()
}

class WatchFaceFrameDecoder(private val maxBytes: Int = ProtocolLimits.MAX_MESSAGE_BYTES) {
    private val header = ByteArray(4)
    private var headerUsed = 0
    private var body: ByteArray? = null
    private var bodyUsed = 0
    init { require(maxBytes > 0) }
    fun feed(input: ByteArray): List<ByteArray> = buildList {
        var offset = 0
        while (offset < input.size) {
            if (body == null) {
                val count = minOf(4 - headerUsed, input.size - offset)
                input.copyInto(header, headerUsed, offset, offset + count); offset += count; headerUsed += count
                if (headerUsed != 4) continue
                val length = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).int
                protocolCheck(length >= 0 && length <= maxBytes, "Watch face frame length budget exceeded")
                body = ByteArray(length); bodyUsed = 0
            }
            val target = body!!
            val count = minOf(target.size - bodyUsed, input.size - offset)
            input.copyInto(target, bodyUsed, offset, offset + count); bodyUsed += count; offset += count
            if (bodyUsed == target.size) { add(target); body = null; headerUsed = 0 }
        }
    }
    fun finish() { protocolCheck(headerUsed == 0 && body == null, "Truncated watch face frame") }
}
