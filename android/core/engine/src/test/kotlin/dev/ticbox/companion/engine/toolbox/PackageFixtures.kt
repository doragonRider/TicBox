package dev.ticbox.companion.engine.toolbox

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** The ways real archives differ, which java.util.zip can't all be made to write. */
internal class ArchiveEntry(val name: String, val bytes: ByteArray, val deflated: Boolean = false)

private fun ByteArrayOutputStream.u16(value: Int) { write(value and 0xff); write(value ushr 8 and 0xff) }
private fun ByteArrayOutputStream.u32(value: Long) { repeat(4) { write((value ushr (it * 8)).toInt() and 0xff) } }
private fun ByteArrayOutputStream.u64(value: Long) { repeat(8) { write((value ushr (it * 8)).toInt() and 0xff) } }

/** Every entry has its sizes in a trailing data descriptor, as .xapk files do even when stored. */
internal fun archiveOf(entries: List<ArchiveEntry>, zip64: Boolean = false, comment: String = "", prefix: ByteArray = byteArrayOf()): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(prefix)
    class Written(val entry: ArchiveEntry, val name: ByteArray, val crc: Long, val compressed: Long, val offset: Long)
    val written = entries.map { entry ->
        val name = entry.name.toByteArray()
        val body = if (entry.deflated) deflate(entry.bytes) else entry.bytes
        val crc = CRC32().apply { update(entry.bytes) }.value
        val offset = out.size().toLong() - prefix.size
        out.u32(0x04034b50); out.u16(20); out.u16(FLAG_DATA_DESCRIPTOR); out.u16(if (entry.deflated) 8 else 0); out.u16(0); out.u16(0)
        out.u32(0); out.u32(0); out.u32(0); out.u16(name.size); out.u16(0); out.write(name)
        out.write(body)
        out.u32(0x08074b50); out.u32(crc); out.u32(body.size.toLong()); out.u32(entry.bytes.size.toLong())
        Written(entry, name, crc, body.size.toLong(), offset)
    }
    val directoryStart = out.size().toLong() - prefix.size
    written.forEach { item ->
        out.u32(0x02014b50); out.u16(20); out.u16(20); out.u16(FLAG_DATA_DESCRIPTOR); out.u16(if (item.entry.deflated) 8 else 0); out.u16(0); out.u16(0)
        out.u32(item.crc)
        out.u32(if (zip64) UNSIGNED_MAX else item.compressed)
        out.u32(if (zip64) UNSIGNED_MAX else item.entry.bytes.size.toLong())
        out.u16(item.name.size); out.u16(if (zip64) 28 else 0); out.u16(0); out.u16(0); out.u16(0); out.u32(0)
        out.u32(if (zip64) UNSIGNED_MAX else item.offset)
        out.write(item.name)
        if (zip64) {
            out.u16(1); out.u16(24); out.u64(item.entry.bytes.size.toLong()); out.u64(item.compressed); out.u64(item.offset)
        }
    }
    val directoryLength = out.size().toLong() - prefix.size - directoryStart
    if (zip64) {
        val record = out.size().toLong() - prefix.size
        out.u32(0x06064b50); out.u64(44); out.u16(45); out.u16(45); out.u32(0); out.u32(0)
        out.u64(entries.size.toLong()); out.u64(entries.size.toLong()); out.u64(directoryLength); out.u64(directoryStart)
        out.u32(0x07064b50); out.u32(0); out.u64(record); out.u32(1)
    }
    out.u32(0x06054b50); out.u16(0); out.u16(0)
    out.u16(if (zip64) 0xffff else entries.size); out.u16(if (zip64) 0xffff else entries.size)
    out.u32(if (zip64) UNSIGNED_MAX else directoryLength)
    out.u32(if (zip64) UNSIGNED_MAX else directoryStart)
    out.u16(comment.length); out.write(comment.toByteArray())
    return out.toByteArray()
}

private fun deflate(bytes: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.BEST_SPEED, true)
    deflater.setInput(bytes); deflater.finish()
    val out = ByteArrayOutputStream(); val buffer = ByteArray(4096)
    while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
    deflater.end()
    return out.toByteArray()
}

/** A compiled manifest holding only what a package file is read for. */
internal fun binaryManifest(packageName: String, split: String? = null, versionName: String? = null, utf8: Boolean = true): ByteArray {
    val strings = mutableListOf("manifest", "package", packageName)
    val attributes = mutableListOf(1 to 2)
    fun attribute(name: String, value: String) { strings += name; strings += value; attributes += strings.size - 2 to strings.size - 1 }
    split?.let { attribute("split", it) }
    versionName?.let { attribute("versionName", it) }
    val encoded = strings.map { value ->
        ByteArrayOutputStream().apply {
            if (utf8) {
                val bytes = value.toByteArray()
                write(value.length); write(bytes.size); write(bytes); write(0)
            } else {
                u16(value.length); value.forEach { u16(it.code) }; u16(0)
            }
        }.toByteArray()
    }
    val pool = ByteArrayOutputStream().apply {
        val header = 28 + strings.size * 4
        val data = encoded.sumOf { it.size }
        val padded = data + (4 - data % 4) % 4
        u16(1); u16(28); u32((header + padded).toLong())
        u32(strings.size.toLong()); u32(0); u32(if (utf8) 256 else 0); u32(header.toLong()); u32(0)
        var offset = 0L
        encoded.forEach { u32(offset); offset += it.size }
        encoded.forEach { write(it) }
        repeat(padded - data) { write(0) }
    }.toByteArray()
    val element = ByteArrayOutputStream().apply {
        u16(0x0102); u16(16); u32(36L + attributes.size * 20); u32(1); u32(UNSIGNED_MAX)
        u32(UNSIGNED_MAX); u32(0); u16(20); u16(20); u16(attributes.size); u16(0); u16(0); u16(0)
        attributes.forEach { (name, value) ->
            u32(UNSIGNED_MAX); u32(name.toLong()); u32(value.toLong()); u16(8); write(0); write(3); u32(value.toLong())
        }
    }.toByteArray()
    return ByteArrayOutputStream().apply {
        u16(3); u16(8); u32(8L + pool.size + element.size)
        write(pool); write(element)
    }.toByteArray()
}

internal fun apkOf(packageName: String, split: String? = null, versionName: String? = null): ByteArray = archiveOf(
    listOf(
        ArchiveEntry("res/raw/data", ByteArray(64) { it.toByte() }),
        ArchiveEntry("AndroidManifest.xml", binaryManifest(packageName, split, versionName), deflated = true),
    )
)

/** Writes protobuf the way toc.pb is laid out. */
internal class Proto {
    private val out = ByteArrayOutputStream()
    private fun varint(value: Long) {
        var rest = value
        while (rest and 0x7fL.inv() != 0L) { out.write((rest and 0x7f or 0x80).toInt()); rest = rest ushr 7 }
        out.write(rest.toInt())
    }
    fun number(field: Int, value: Long) = apply { varint(field.toLong() shl 3); varint(value) }
    fun bytes(field: Int, value: ByteArray) = apply { varint(field.toLong() shl 3 or 2); varint(value.size.toLong()); out.write(value) }
    fun text(field: Int, value: String) = bytes(field, value.toByteArray())
    fun message(field: Int, value: Proto) = bytes(field, value.bytes())
    fun bytes(): ByteArray = out.toByteArray()
}

private const val FLAG_DATA_DESCRIPTOR = 8
private const val UNSIGNED_MAX = 0xffffffffL
