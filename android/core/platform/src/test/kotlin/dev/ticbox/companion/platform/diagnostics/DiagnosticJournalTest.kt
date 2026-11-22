@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.ticbox.companion.platform.diagnostics

import dev.ticbox.companion.api.*
import java.io.IOException
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class DiagnosticJournalTest {
    @Test fun journalAppendsInsteadOfTruncatingPreviousEntries() {
        val directory = Files.createTempDirectory("ticbox-diagnostics").toFile()
        try {
            val first = RotatingDiagnosticLog(directory)
            val second = RotatingDiagnosticLog(directory)
            first.append("{\"event\":\"first\"}")
            second.append("{\"event\":\"second\"}")
            assertEquals(listOf("{\"event\":\"first\"}", "{\"event\":\"second\"}"), directory.resolve("protocol-0.jsonl").readLines())
        } finally { directory.deleteRecursively() }
    }

    @Test fun rotationRetainsNewestCompleteEntriesWithinTheDiskBudget() {
        val directory = Files.createTempDirectory("ticbox-diagnostics").toFile()
        try {
            val journal = RotatingDiagnosticLog(directory, maxFileBytes = 4096, fileCount = 3)
            for (index in 0..9) journal.append("$index:" + "x".repeat(1_898))
            val files = directory.listFiles()!!.sortedByDescending { it.name }
            assertEquals(3, files.size)
            assertTrue(files.all { it.length() <= 4096 })
            assertEquals((4..9).toList(), files.flatMap { it.readLines() }.map { it.substringBefore(':').toInt() })
        } finally { directory.deleteRecursively() }
    }

    @Test fun queueOverloadDoesNotBlockProtocolAndLeavesALossMarker() = runTest {
        val lines = mutableListOf<String>()
        val sink = BufferedDiagnosticSink(backgroundScope, lines::add, capacity = 2, now = { 1234 }, dispatcher = StandardTestDispatcher(testScheduler))
        repeat(100) { sink.record(ProtocolDiagnostic("channel", "unexpected_ack", details = mapOf("sequence" to it.toString()))) }
        assertTrue(sink.droppedEntries > 0)
        sink.close()
        runCurrent()
        sink.awaitClosed()
        assertTrue(lines.any { it.contains("log_queue_overflow") && it.contains("droppedEntries") })
        assertTrue(lines.any { it.contains("unexpected_ack") })
    }

    @Test fun diskAndMirrorFailuresDoNotEscapeIntoTheProtocolCaller() = runTest {
        var reports = 0
        val sink = BufferedDiagnosticSink(backgroundScope,
            append = { throw IOException("storage unavailable") },
            mirror = { throw IllegalStateException("mirror unavailable") },
            onFailure = { reports++; throw IllegalStateException("reporter unavailable") },
            dispatcher = StandardTestDispatcher(testScheduler))
        sink.record(ProtocolDiagnostic("channel", "unexpected_ack"))
        sink.close()
        runCurrent()
        sink.awaitClosed()
        assertEquals(2, reports)
    }

    @Test fun parentCancellationDrainsTheFiniteQueueAndAccountsForUndeliveredEntries() = runTest {
        val lines = mutableListOf<String>()
        val lifetime = SupervisorJob(backgroundScope.coroutineContext[Job])
        val scope = CoroutineScope(backgroundScope.coroutineContext + lifetime)
        val sink = BufferedDiagnosticSink(scope, lines::add, dispatcher = StandardTestDispatcher(testScheduler))
        repeat(4) { sink.record(ProtocolDiagnostic("channel", "queued_$it")) }
        lifetime.cancel()
        runCurrent()
        sink.awaitClosed()
        val stored = lines.count { it.contains("queued_") }
        val reportedLoss = lines.filter { it.contains("log_queue_overflow") }.sumOf {
            Regex("\\\"droppedEntries\\\":\\\"(\\d+)\\\"").find(it)?.groupValues?.get(1)?.toInt() ?: 0
        }
        assertEquals(4, stored + reportedLoss)
    }
}
