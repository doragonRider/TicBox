package dev.ticbox.companion.engine.media

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class MediaCommandDispatcherTest {
    private class Port : MediaActionPort {
        val operations = mutableListOf<String>()
        var fail: String? = null
        private fun record(value: String): Boolean { operations += value; if (fail == value) error("private native details"); return true }
        override suspend fun transport(command: String) = record(command)
        override suspend fun seekTo(positionMillis: Long) = record("seek:$positionMillis")
        override suspend fun setThumbRating(value: Int) = record("rating:$value")
        override suspend fun adjustVolume(direction: Int) = record("volume:$direction")
        override suspend fun setAbsoluteVolume(value: Float) = record("absolute:$value")
        override suspend fun customAction(action: String, extras: Map<String, DataMapValue>) = record("custom")
        override suspend fun skipToQueueItem(id: Long) = record("queue:$id")
    }
    @Test fun `all independent native controls retain source dispatch order including zero seek queue and volume`(): Unit = runTest {
        val port = Port()
        val result = MediaCommandDispatcher().dispatch(MediaCommand("pause", 1, 0f, 0, 0, 0, "star"), port)
        assertEquals(listOf("pause", "seek:0", "rating:0", "volume:1", "absolute:0.0", "custom", "queue:0"), port.operations)
        assertEquals(MediaCommandOutcome(7, 7), result)
    }
    @Test fun `failure in one native operation does not suppress other usable controls or expose body logs`(): Unit = runTest {
        val port = Port().apply { fail = "pause" }; val logs = mutableListOf<ProtocolDiagnostic>()
        val result = MediaCommandDispatcher(DiagnosticSink { logs += it }).dispatch(MediaCommand("pause", volumeDelta = -1), port)
        assertEquals(listOf("pause", "volume:-1"), port.operations); assertEquals(MediaCommandOutcome(2, 1), result)
        assertTrue(logs.flatMap { it.details.values }.none { it.contains("private") })
    }
    @Test fun `future command and null rating do not block a usable absolute position operation`(): Unit = runTest {
        val port = Port()
        val result = MediaCommandDispatcher().dispatch(MediaCommand("future", 7, positionMillis = 23, rating = -2), port)
        assertEquals(listOf("seek:23"), port.operations); assertEquals(MediaCommandOutcome(1, 1), result)
    }
    @Test fun `caller cancellation stops compound dispatch instead of pretending later operations ran`(): Unit = runTest {
        val port = object : MediaActionPort by Port() { override suspend fun transport(command: String): Boolean { throw CancellationException("fixture") } }
        assertFailsWith<CancellationException> { MediaCommandDispatcher().dispatch(MediaCommand("play", volumeDelta = 1), port) }
    }
}
