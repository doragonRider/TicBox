package dev.ticbox.companion.engine.toolbox

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What an APK says about itself. Unlike its file name, this can't be a store's own naming. */
internal data class ApkManifest(val packageName: String, val split: String?, val versionName: String?)

/** Reads the root element of a compiled AndroidManifest.xml, which is all that's needed. Null if it's invalid. */
internal fun readApkManifest(bytes: ByteArray): ApkManifest? = try {
    val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (data.getShort(0).toInt() != CHUNK_XML) null else parse(data)
} catch (_: IndexOutOfBoundsException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

private fun parse(data: ByteBuffer): ApkManifest? {
    var strings: StringPool? = null
    var at = data.getShort(2).toInt() and 0xffff
    while (at + CHUNK_HEADER_SIZE <= data.capacity()) {
        val type = data.getShort(at).toInt() and 0xffff
        val headerSize = data.getShort(at + 2).toInt() and 0xffff
        val chunkSize = data.getInt(at + 4)
        if (chunkSize < CHUNK_HEADER_SIZE) return null
        when (type) {
            CHUNK_STRING_POOL -> strings = StringPool(data, at, headerSize)
            CHUNK_START_ELEMENT -> return manifestElement(data, at + headerSize, strings ?: return null)
        }
        at += chunkSize
    }
    return null
}

private fun manifestElement(data: ByteBuffer, element: Int, strings: StringPool): ApkManifest? {
    if (strings[data.getInt(element + 4)] != "manifest") return null
    val attributesAt = element + (data.getShort(element + 8).toInt() and 0xffff)
    val attributeSize = data.getShort(element + 10).toInt() and 0xffff
    val count = data.getShort(element + 12).toInt() and 0xffff
    val values = (0 until count).associate { index ->
        val at = attributesAt + index * attributeSize
        val raw = data.getInt(at + 8)
        val isString = (data.get(at + 15).toInt() and 0xff) == TYPE_STRING
        val value = if (raw != NO_STRING) strings[raw] else if (isString) strings[data.getInt(at + 16)] else null
        strings[data.getInt(at + 4)] to value
    }
    val packageName = values["package"]?.takeIf { it.isNotEmpty() } ?: return null
    return ApkManifest(packageName, values["split"]?.takeIf { it.isNotEmpty() }, values["versionName"])
}

private class StringPool(private val data: ByteBuffer, private val chunk: Int, headerSize: Int) {
    private val count = data.getInt(chunk + 8)
    private val utf8 = data.getInt(chunk + 16) and FLAG_UTF8 != 0
    private val stringsStart = chunk + data.getInt(chunk + 20)
    private val offsets = chunk + headerSize

    operator fun get(index: Int): String? {
        if (index !in 0 until count) return null
        val at = stringsStart + data.getInt(offsets + index * 4)
        return if (utf8) utf8At(at) else utf16At(at)
    }

    private fun utf8At(start: Int): String {
        // The character count comes first, then the byte count, each in one or two bytes.
        var at = start + if (data.get(start).toInt() and 0x80 != 0) 2 else 1
        val high = data.get(at).toInt() and 0xff
        val length = if (high and 0x80 != 0) {
            at += 2
            ((high and 0x7f) shl 8) or (data.get(at - 1).toInt() and 0xff)
        } else {
            at += 1
            high
        }
        return String(ByteArray(length) { data.get(at + it) }, Charsets.UTF_8)
    }

    private fun utf16At(start: Int): String {
        val high = data.getShort(start).toInt() and 0xffff
        val long = high and 0x8000 != 0
        val length = if (long) ((high and 0x7fff) shl 16) or (data.getShort(start + 2).toInt() and 0xffff) else high
        val at = start + if (long) 4 else 2
        require(at + length.toLong() * 2 <= data.capacity())
        return String(CharArray(length) { data.getShort(at + it * 2).toInt().toChar() })
    }
}

private const val CHUNK_XML = 0x0003
private const val CHUNK_STRING_POOL = 0x0001
private const val CHUNK_START_ELEMENT = 0x0102
private const val CHUNK_HEADER_SIZE = 8
private const val FLAG_UTF8 = 1 shl 8
private const val TYPE_STRING = 3
private const val NO_STRING = -1
