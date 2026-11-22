package dev.ticbox.companion.protocol

import kotlin.test.*

class MigrationProtocolTest {
    @Test fun `accountless operations match independently written wire vectors`() {
        // WearMessage tag20 -> control(type5, matching(operationN)); no account records.
        for (operation in 1..4) {
            val bytes = hex("a2010608051202080$operation")
            assertContentEquals(bytes, MigrationProtocol.accountOperation(operation))
            val decoded = MigrationProtocol.decode(WearMessages.decode(bytes))!!
            assertEquals(5, decoded.type)
            assertEquals(operation, decoded.accountMatching!!.operation)
            assertTrue(decoded.accountMatching.accounts.isEmpty())
        }
    }

    @Test fun `unknown integer operations and child fields remain representable`() {
        val bytes = hex("a201080805120408634807")
        val decoded = MigrationProtocol.decode(WearMessages.decode(bytes))!!
        assertEquals(99, decoded.accountMatching!!.operation)
        assertEquals(9, decoded.accountMatching.unknownFields.single().number)
        assertContentEquals(bytes, MigrationProtocol.encode(decoded))
        assertNull(MigrationProtocol.decode(WearMessages.decode(hex("5a00"))))
    }

    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
