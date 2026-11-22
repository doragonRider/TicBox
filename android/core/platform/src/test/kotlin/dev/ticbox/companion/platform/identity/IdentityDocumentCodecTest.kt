package dev.ticbox.companion.platform.identity

import dev.ticbox.companion.api.NodeIdentity
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IdentityDocumentCodecTest {
    @Test
    fun optionalCheckinIdAndUnicodeNameArePreserved() {
        for (androidId in listOf(null, Long.MIN_VALUE, Long.MAX_VALUE)) {
            val identity = NodeIdentity("node", "network", "Phone 手表 🕒", androidId)
            assertEquals(identity, IdentityDocumentCodec.decode(IdentityDocumentCodec.encode(identity)))
        }
    }

    @Test
    fun everyTruncationAndAnyTrailingBytesAreRejected() {
        val bytes = IdentityDocumentCodec.encode(NodeIdentity("n", "w", "x"))
        for (length in bytes.indices) {
            assertFailsWith<IOException>("length=$length") { IdentityDocumentCodec.decode(bytes.copyOf(length)) }
        }
        assertFailsWith<IOException> { IdentityDocumentCodec.decode(bytes + 0) }
    }

    @Test
    fun corruptBodyChecksumAndUnsupportedVersionCannotProduceNewIdentity() {
        val bytes = IdentityDocumentCodec.encode(NodeIdentity("n", "w", "x"))
        for (offset in listOf(0, 7, 11, 14, bytes.lastIndex)) {
            val changed = bytes.copyOf()
            changed[offset] = (changed[offset].toInt() xor 1).toByte()
            assertFailsWith<IOException>("offset=$offset") { IdentityDocumentCodec.decode(changed) }
        }
    }

    @Test
    fun validChecksumCannotMakeAnInvalidDomainIdentityOrPresenceByteAcceptable() {
        // Modified-UTF strings are hand-encoded: empty node, network=w, name=x, absent Android ID.
        val blankNode = byteArrayOf(0, 0, 0, 1, 'w'.code.toByte(), 0, 1, 'x'.code.toByte(), 0)
        assertFailsWith<IOException> { IdentityDocumentCodec.decode(envelope(blankNode)) }
        // n/w/x with a presence marker 2; only 0 and 1 are meaningful in this app-owned format.
        val invalidPresence = byteArrayOf(0, 1, 110, 0, 1, 119, 0, 1, 120, 2)
        assertFailsWith<IOException> { IdentityDocumentCodec.decode(envelope(invalidPresence)) }
    }

    private fun envelope(body: ByteArray): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use {
            it.writeInt(0x54574944)
            it.writeInt(1)
            it.writeInt(body.size)
            it.write(body)
            it.write(MessageDigest.getInstance("SHA-256").digest(body))
        }
    }.toByteArray()
}
