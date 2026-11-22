package dev.ticbox.companion.platform.migration

import dev.ticbox.companion.api.MigrationException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.*
import org.junit.Test

class MigrationCryptoTest {
    private val plain = Random(7).nextBytes(MigrationCrypto.CHUNK_BYTES * 2 + 123)

    private fun seal(bytes: ByteArray, password: String = "correct horse") = ByteArrayOutputStream().also { out ->
        MigrationCrypto.seal(out, password.toCharArray(), iterations = 10_000).use { it.write(bytes) }
    }.toByteArray()
    private fun open(sealed: ByteArray, password: String = "correct horse") =
        MigrationCrypto.open(ByteArrayInputStream(sealed), password.toCharArray()).use { it.readBytes() }
    private fun code(block: () -> Unit) = assertFailsWith<MigrationException> { block() }.code

    @Test fun sealedChunksOpenBackToTheSameBytes() {
        val sealed = seal(plain)
        assertTrue(MigrationCrypto.isEncrypted(sealed))
        assertContentEquals(plain, open(sealed))
        assertContentEquals(ByteArray(0), open(seal(ByteArray(0))))
    }

    @Test fun aWrongPasswordIsToldApartFromACutOrAlteredFile() {
        val sealed = seal(plain)
        assertEquals("migration_wrong_password", code { open(sealed, "wrong") })
        // Cut right after a whole chunk: the right password, but no last chunk.
        val firstChunkEnd = 36 + 4 + MigrationCrypto.CHUNK_BYTES + 16
        assertEquals("migration_damaged", code { open(sealed.copyOf(firstChunkEnd)) })
        assertEquals("migration_damaged", code { open(sealed.copyOf(sealed.size - 1)) })
        val altered = sealed.copyOf().also { it[it.size - 20] = (it[it.size - 20] + 1).toByte() }
        assertEquals("migration_damaged", code { open(altered) })
    }
}
