package dev.ticbox.companion.platform.migration

import dev.ticbox.companion.api.MigrationException
import java.io.DataInputStream
import java.io.EOFException
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * A password-sealed stream: PBKDF2-HMAC-SHA256 derives an AES-256 key, and the plaintext is cut into
 * 64 KiB chunks, each sealed with AES-GCM. A chunk's nonce holds its index and a last-chunk flag, and
 * every chunk authenticates the header, so reordered, dropped or truncated chunks fail to open.
 * Layout: magic(8) version(1) iterations(4) salt(16) noncePrefix(7), then [length(4) ciphertext]*.
 */
internal object MigrationCrypto {
    private val MAGIC = "TWMIGENC".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1
    const val ITERATIONS = 600_000
    private const val MIN_ITERATIONS = 10_000
    private const val MAX_ITERATIONS = 10_000_000
    private const val SALT_BYTES = 16
    private const val PREFIX_BYTES = 7
    private const val HEADER_BYTES = 8 + 1 + 4 + SALT_BYTES + PREFIX_BYTES
    const val CHUNK_BYTES = 64 * 1024
    private const val TAG_BYTES = 16

    fun isEncrypted(head: ByteArray) = head.size >= MAGIC.size && head.copyOf(MAGIC.size).contentEquals(MAGIC)
    val magicSize get() = MAGIC.size

    fun seal(output: OutputStream, password: CharArray, iterations: Int = ITERATIONS, random: SecureRandom = SecureRandom()): OutputStream {
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS)
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val prefix = ByteArray(PREFIX_BYTES).also(random::nextBytes)
        val header = ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).put(VERSION.toByte()).putInt(iterations).put(salt).put(prefix).array()
        output.write(header)
        return SealingStream(output, key(password, salt, iterations), prefix, header)
    }

    /** [input] starts at the magic. A bad first chunk means a wrong password; a later one, damage. */
    fun open(input: InputStream, password: CharArray): InputStream {
        val data = DataInputStream(input)
        val header = ByteArray(HEADER_BYTES)
        try { data.readFully(header) } catch (problem: EOFException) { throw MigrationException("migration_damaged", problem) }
        val fields = ByteBuffer.wrap(header)
        val magic = ByteArray(MAGIC.size).also(fields::get)
        if (!magic.contentEquals(MAGIC)) throw MigrationException("migration_not_a_backup")
        if (fields.get().toInt() != VERSION) throw MigrationException("migration_newer")
        val iterations = fields.getInt()
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw MigrationException("migration_damaged")
        val salt = ByteArray(SALT_BYTES).also(fields::get)
        val prefix = ByteArray(PREFIX_BYTES).also(fields::get)
        return OpeningStream(PushbackInputStream(input, 1), key(password, salt, iterations), prefix, header)
    }

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try { return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") }
        finally { spec.clearPassword() }
    }

    private fun nonce(prefix: ByteArray, index: Int, last: Boolean) =
        ByteBuffer.allocate(12).put(prefix).putInt(index).put(if (last) 1 else 0).array()

    private fun cipher(mode: Int, key: SecretKeySpec, prefix: ByteArray, index: Int, last: Boolean, header: ByteArray) =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BYTES * 8, nonce(prefix, index, last))); updateAAD(header)
        }

    private class SealingStream(out: OutputStream, private val key: SecretKeySpec, private val prefix: ByteArray,
        private val header: ByteArray) : FilterOutputStream(out) {
        private val buffer = ByteArray(CHUNK_BYTES)
        private var size = 0
        private var index = 0
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed)
            var at = off; val end = off + len
            while (at < end) {
                // A full chunk is only sealed once more data follows, so the last chunk is never empty unless all is.
                if (size == CHUNK_BYTES) seal(false)
                val count = minOf(CHUNK_BYTES - size, end - at)
                System.arraycopy(b, at, buffer, size, count); size += count; at += count
            }
        }
        override fun flush() = out.flush()
        override fun close() {
            if (closed) return
            try { seal(true); out.flush() } finally { closed = true; out.close() }
        }
        private fun seal(last: Boolean) {
            check(index != Int.MAX_VALUE) { "Migration file too large" }
            val sealed = cipher(Cipher.ENCRYPT_MODE, key, prefix, index++, last, header).doFinal(buffer, 0, size)
            out.write(ByteBuffer.allocate(4).putInt(sealed.size).array()); out.write(sealed)
            size = 0
        }
    }

    private class OpeningStream(private val source: PushbackInputStream, private val key: SecretKeySpec,
        private val prefix: ByteArray, private val header: ByteArray) : InputStream() {
        private var chunk = ByteArray(0)
        private var at = 0
        private var index = 0
        private var finished = false

        override fun read(): Int = if (!fill()) -1 else chunk[at++].toInt() and 0xff
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val count = minOf(len, chunk.size - at)
            System.arraycopy(chunk, at, b, off, count); at += count
            return count
        }
        override fun close() = source.close()

        private fun fill(): Boolean {
            while (at == chunk.size) {
                if (finished) return false
                val data = DataInputStream(source)
                val length = try { data.readInt() } catch (problem: EOFException) { throw damaged(problem) }
                if (length !in TAG_BYTES..CHUNK_BYTES + TAG_BYTES) throw damaged(null)
                val sealed = ByteArray(length)
                try { data.readFully(sealed) } catch (problem: EOFException) { throw damaged(problem) }
                val next = source.read()
                val last = next < 0
                if (!last) source.unread(next)
                chunk = try { cipher(Cipher.DECRYPT_MODE, key, prefix, index, last, header).doFinal(sealed) }
                catch (problem: GeneralSecurityException) {
                    // A first chunk that opens as a middle one means the right password and a cut-off file.
                    val cut = index == 0 && last && runCatching {
                        cipher(Cipher.DECRYPT_MODE, key, prefix, 0, false, header).doFinal(sealed)
                    }.isSuccess
                    throw MigrationException(if (index == 0 && !cut) "migration_wrong_password" else "migration_damaged", problem)
                }
                at = 0; index++; finished = last
            }
            return true
        }
        private fun damaged(cause: Throwable?) = MigrationException("migration_damaged", cause)
    }
}
