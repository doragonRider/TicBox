package dev.ticbox.companion.engine.media

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import kotlinx.coroutines.CancellationException

/** Native calls have no delivery ACK; true means the system accepted the corresponding call. */
interface MediaActionPort {
    suspend fun transport(command: String): Boolean
    suspend fun seekTo(positionMillis: Long): Boolean
    suspend fun setThumbRating(value: Int): Boolean
    suspend fun adjustVolume(direction: Int): Boolean
    suspend fun setAbsoluteVolume(value: Float): Boolean
    suspend fun customAction(action: String, extras: Map<String, DataMapValue>): Boolean
    suspend fun skipToQueueItem(id: Long): Boolean
}

/** Follows the official independent-operation order; one failed operation does not hide the others. */
class MediaCommandDispatcher(private val diagnostics: DiagnosticSink = DiagnosticSink.NONE) {
    suspend fun dispatch(command: MediaCommand, port: MediaActionPort): MediaCommandOutcome {
        var attempted = 0; var accepted = 0
        suspend fun submit(action: suspend () -> Boolean) {
            attempted++
            try { if (action()) accepted++ }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) { runCatching { diagnostics.record(ProtocolDiagnostic("media", "native_operation_failed",
                details = mapOf("exception" to problem.javaClass.simpleName))) } }
        }
        command.command?.takeIf { it in MediaProtocol.transportCommands }?.let { submit { port.transport(it) } }
        command.positionMillis?.let { submit { port.seekTo(it) } }
        command.rating?.takeIf { it in -1..1 }?.let { submit { port.setThumbRating(it) } }
        command.volumeDelta?.takeIf { it == -1 || it == 1 }?.let { submit { port.adjustVolume(it) } }
        command.absoluteVolume?.let { submit { port.setAbsoluteVolume(it) } }
        command.customAction?.takeIf { it.isNotEmpty() }?.let { submit { port.customAction(it, command.customActionExtras) } }
        command.playQueueId?.let { submit { port.skipToQueueItem(it) } }
        return MediaCommandOutcome(attempted, accepted)
    }
}
