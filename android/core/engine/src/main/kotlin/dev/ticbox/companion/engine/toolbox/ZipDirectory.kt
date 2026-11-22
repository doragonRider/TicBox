package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.ToolboxException
import java.io.EOFException
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipException
import kotlin.math.min

internal class ZipItem(
    val name: String,
    val method: Int,
    val encrypted: Boolean,
    val crc: Int,
    val compressedSize: Long,
    val size: Long,
    val headerOffset: Long,
) {
    val isDirectory: Boolean get() = name.endsWith('/')
}

/**
 * Reads an archive through its central directory, which is what the sizes can be trusted from.
 * Real .xapk files store APKs uncompressed with a trailing data descriptor, a combination
 * ZipInputStream refuses. All it needs is a stream that reopens: skipping seeks on a file, and
 * only reads through on a source that can't, such as a pipe from a cloud provider.
 */
internal class ZipDirectory private constructor(
    private val source: () -> InputStream,
    val items: List<ZipItem>,
    private val shift: Long,
) {
    fun item(name: String): ZipItem? = items.firstOrNull { it.name == name }

    /** Checks the content against the archive as it's read, unless [verified] is off to look inside. */
    fun open(item: ZipItem, verified: Boolean = true): InputStream {
        if (item.encrypted) throw ToolboxException("package_encrypted")
        val input = source()
        try {
            input.skipFully(item.headerOffset + shift)
            val header = input.readExactly(LOCAL_HEADER_SIZE).littleEndian()
            if (header.getInt(0) != LOCAL_SIGNATURE) throw corrupt()
            input.skipFully(header.unsignedShort(26) + header.unsignedShort(28).toLong())
            val body = BoundedStream(input, item.compressedSize)
            val content = when (item.method) {
                METHOD_STORED -> if (item.compressedSize == item.size) body else throw corrupt()
                METHOD_DEFLATED -> EntryInflater(body)
                else -> throw ToolboxException("package_unreadable")
            }
            return if (verified) VerifiedStream(content, item) else content
        } catch (problem: Throwable) {
            input.close()
            throw problem
        }
    }

    companion object {
        /** Null when this isn't an archive at all. */
        fun read(size: Long, source: () -> InputStream): ZipDirectory? {
            if (size < END_RECORD_SIZE) return null
            val tailSize = min(size, MAX_TAIL).toInt()
            val tailStart = size - tailSize
            val tail = source().use {
                it.skipFully(tailStart)
                it.readExactly(tailSize)
            }.littleEndian()
            val end = locateEndRecord(tail) ?: return null
            return try {
                readDirectory(source, tail, end, tailStart + end)
            } catch (_: IndexOutOfBoundsException) {
                throw corrupt()
            } catch (_: IllegalArgumentException) {
                throw corrupt()
            }
        }

        private fun locateEndRecord(tail: ByteBuffer): Int? {
            var at = tail.capacity() - END_RECORD_SIZE
            while (at >= 0) {
                val fits = at + END_RECORD_SIZE + tail.unsignedShort(at + 20) <= tail.capacity()
                if (tail.getInt(at) == END_SIGNATURE && fits) return at
                at--
            }
            return null
        }

        private fun readDirectory(source: () -> InputStream, tail: ByteBuffer, end: Int, endPosition: Long): ZipDirectory {
            var count = tail.unsignedShort(end + 10).toLong()
            var length = tail.unsignedInt(end + 12)
            var offset = tail.unsignedInt(end + 16)
            var directoryEnd = endPosition
            val locator = end - ZIP64_LOCATOR_SIZE
            if (locator >= 0 && tail.getInt(locator) == ZIP64_LOCATOR_SIGNATURE) {
                directoryEnd = tail.getLong(locator + 8)
                val record = source().use {
                    it.skipFully(directoryEnd)
                    it.readExactly(ZIP64_RECORD_SIZE)
                }.littleEndian()
                if (record.getInt(0) != ZIP64_RECORD_SIGNATURE) throw corrupt()
                count = record.getLong(32)
                length = record.getLong(40)
                offset = record.getLong(48)
            }
            if (count !in 0..MAX_ITEMS || length !in 0..MAX_DIRECTORY_BYTES) throw corrupt()
            // Anything stuck on the front of the archive moves every offset by the same amount.
            val shift = directoryEnd - length - offset
            if (shift < 0) throw corrupt()
            val directory = source().use {
                it.skipFully(offset + shift)
                it.readExactly(length.toInt())
            }.littleEndian()
            return ZipDirectory(source, parseItems(directory, count.toInt()), shift)
        }

        private fun parseItems(directory: ByteBuffer, count: Int): List<ZipItem> {
            val items = ArrayList<ZipItem>(count)
            var at = 0
            repeat(count) {
                if (directory.getInt(at) != DIRECTORY_SIGNATURE) throw corrupt()
                val nameLength = directory.unsignedShort(at + 28)
                val extraLength = directory.unsignedShort(at + 30)
                val commentLength = directory.unsignedShort(at + 32)
                val extraStart = at + DIRECTORY_HEADER_SIZE + nameLength
                val name = ByteArray(nameLength) { directory.get(at + DIRECTORY_HEADER_SIZE + it) }
                var size = directory.unsignedInt(at + 24)
                var compressed = directory.unsignedInt(at + 20)
                var header = directory.unsignedInt(at + 42)
                var extra = extraStart
                while (extra + 4 <= extraStart + extraLength) {
                    val tag = directory.unsignedShort(extra)
                    val tagLength = directory.unsignedShort(extra + 2)
                    if (tag == ZIP64_EXTRA_TAG) {
                        var field = extra + 4
                        if (size == UNSIGNED_INT_MAX) { size = directory.getLong(field); field += 8 }
                        if (compressed == UNSIGNED_INT_MAX) { compressed = directory.getLong(field); field += 8 }
                        if (header == UNSIGNED_INT_MAX) header = directory.getLong(field)
                    }
                    extra += 4 + tagLength
                }
                if (size < 0 || compressed < 0 || header < 0) throw corrupt()
                items += ZipItem(
                    String(name, Charsets.UTF_8).replace('\\', '/'),
                    directory.unsignedShort(at + 10),
                    directory.unsignedShort(at + 8) and FLAG_ENCRYPTED != 0,
                    directory.getInt(at + 16),
                    compressed,
                    size,
                    header,
                )
                at = extraStart + extraLength + commentLength
            }
            return items
        }
    }
}

private fun corrupt() = ToolboxException("package_unreadable")

private fun ByteArray.littleEndian(): ByteBuffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)

private fun ByteBuffer.unsignedShort(at: Int): Int = getShort(at).toInt() and 0xffff

private fun ByteBuffer.unsignedInt(at: Int): Long = getInt(at).toLong() and UNSIGNED_INT_MAX

private fun InputStream.skipFully(count: Long) {
    var remaining = count
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) remaining -= skipped
        else if (read() >= 0) remaining--
        else throw corrupt()
    }
}

internal fun InputStream.readExactly(count: Int): ByteArray {
    val bytes = ByteArray(count)
    var done = 0
    while (done < count) {
        val read = read(bytes, done, count - done)
        if (read < 0) throw corrupt()
        done += read
    }
    return bytes
}

/** Ends where the entry ends, and keeps skipping cheap by passing it down. */
private class BoundedStream(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
    override fun read(): Int {
        if (remaining <= 0) return -1
        return super.read().also { if (it >= 0) remaining-- }
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return -1
        val read = super.read(bytes, offset, min(length.toLong(), remaining).toInt())
        if (read > 0) remaining -= read
        return read
    }

    override fun skip(count: Long): Long {
        val skipped = super.skip(min(count, remaining))
        remaining -= skipped
        return skipped
    }
}

private class EntryInflater(body: InputStream) : InflaterInputStream(body, Inflater(true), BUFFER_SIZE) {
    private var ended = false

    /** A raw inflater can ask for one byte past the data before it notices the end. */
    override fun fill() {
        val read = `in`.read(buf, 0, buf.size)
        if (read >= 0) {
            len = read
        } else {
            if (ended) throw EOFException()
            ended = true
            buf[0] = 0
            len = 1
        }
        inf.setInput(buf, 0, len)
    }

    override fun close() {
        try {
            super.close()
        } finally {
            inf.end()
        }
    }
}

/** A damaged entry fails here instead of reaching the watch as a file nothing ever checks. */
private class VerifiedStream(private val content: InputStream, private val item: ZipItem) : InputStream() {
    private val crc = CRC32()
    private var remaining = item.size
    private val one = ByteArray(1)

    override fun read(): Int = if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) {
            if (content.read() >= 0 || crc.value.toInt() != item.crc) throw corrupt()
            return -1
        }
        val read = try {
            content.read(bytes, offset, min(length.toLong(), remaining).toInt())
        } catch (_: ZipException) {
            throw corrupt()
        } catch (_: EOFException) {
            throw corrupt()
        }
        if (read < 0) throw corrupt()
        crc.update(bytes, offset, read)
        remaining -= read
        return read
    }

    override fun close() = content.close()
}

private const val LOCAL_SIGNATURE = 0x04034b50
private const val DIRECTORY_SIGNATURE = 0x02014b50
private const val END_SIGNATURE = 0x06054b50
private const val ZIP64_RECORD_SIGNATURE = 0x06064b50
private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50
private const val ZIP64_EXTRA_TAG = 0x0001
private const val LOCAL_HEADER_SIZE = 30
private const val DIRECTORY_HEADER_SIZE = 46
private const val END_RECORD_SIZE = 22
private const val ZIP64_LOCATOR_SIZE = 20
private const val ZIP64_RECORD_SIZE = 56
private const val MAX_TAIL = END_RECORD_SIZE + 65_535L
private const val MAX_ITEMS = 65_535L * 4
private const val MAX_DIRECTORY_BYTES = 64L * 1024 * 1024
private const val UNSIGNED_INT_MAX = 0xffffffffL
private const val METHOD_STORED = 0
private const val METHOD_DEFLATED = 8
private const val FLAG_ENCRYPTED = 1
private const val BUFFER_SIZE = 64 * 1024
