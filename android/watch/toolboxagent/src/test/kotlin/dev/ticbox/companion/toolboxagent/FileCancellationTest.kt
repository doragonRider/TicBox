package dev.ticbox.companion.toolboxagent

import dev.ticbox.companion.protocol.*
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.nio.file.Files
import kotlin.test.*
import kotlinx.serialization.json.*

class FileCancellationTest {
    @Test fun recursiveDeletionStopsAtACheckpointAndDoesNotPublishSuccess() {
        val root = Files.createTempDirectory("toolbox-cancel-")
        try {
            repeat(10) { Files.writeString(root.resolve("file-$it"), "keep") }
            val output = ByteArrayOutputStream()
            var checks = 0
            val files = AgentFiles(AgentWire(byteArrayOf().inputStream(), output)) {
                if (++checks == 4) throw InterruptedIOException("operation_cancelled")
            }
            assertFailsWith<InterruptedIOException> { files.change(buildJsonObject {
                put("action", "DELETE"); put("path", root.toString()); put("recursive", true)
            }) }
            assertTrue(Files.list(root).use { it.count() } in 1..9, "Deletion may be partial, but must stop at cancellation")
            assertEquals(0, output.size(), "Cancelled changes must never return success")
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun cancellationBeforeCopyDoesNotCreateTheDestination() {
        val root = Files.createTempDirectory("toolbox-copy-cancel-")
        try {
            val source = Files.writeString(root.resolve("source"), "keep")
            val destination = root.resolve("destination")
            val files = AgentFiles(AgentWire(byteArrayOf().inputStream(), ByteArrayOutputStream())) { throw InterruptedIOException() }
            assertFailsWith<InterruptedIOException> { files.change(buildJsonObject {
                put("action", "COPY"); put("path", source.toString()); put("destination", destination.toString())
            }) }
            assertFalse(Files.exists(destination))
            assertEquals("keep", Files.readString(source))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun processIdentityParsingHandlesSpacesAndParentheses() {
        val stat = "42 (agent (worker)) " + (listOf("S") + List(18) { "0" } + "123456" + List(5) { "0" }).joinToString(" ")
        assertEquals(123456L, processStartTicks(stat))
        assertNull(processStartTicks("invalid"))
    }
}
