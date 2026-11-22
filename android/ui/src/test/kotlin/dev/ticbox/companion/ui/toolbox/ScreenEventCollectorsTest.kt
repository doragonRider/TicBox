package dev.ticbox.companion.ui.toolbox

import dev.ticbox.companion.api.ToolboxException
import dev.ticbox.companion.api.ToolboxScreenEvent
import dev.ticbox.companion.api.ToolboxScreenInput
import dev.ticbox.companion.api.ToolboxScreenMedia
import dev.ticbox.companion.api.ToolboxScreenSession
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

class ScreenEventCollectorsTest {
    @Test
    fun blockedVideoDecoderDoesNotBlockAudioOrControlAndClosingCancelsIt() = runBlocking {
        val decoding = CompletableDeferred<Unit>()
        val sound = CompletableDeferred<Unit>()
        val control = CompletableDeferred<Unit>()
        val ended = CompletableDeferred<Unit>()
        val cancelledDecoder = CompletableDeferred<Unit>()
        val session =
            TestScreenSession(
                events =
                    flow {
                        decoding.await()
                        emit(ToolboxScreenEvent.ClipboardAcknowledged(3))
                        ended.await()
                    },
                media =
                    ToolboxScreenMedia(
                        video =
                            flow { emit(ToolboxScreenEvent.Frame(0, true, false, byteArrayOf(1))) },
                        audio =
                            flow {
                                decoding.await()
                                emit(ToolboxScreenEvent.AudioFrame(0, false, byteArrayOf(2)))
                            },
                    ),
            )
        val collecting = async {
            collectScreenEvents(
                session,
                video = {
                    decoding.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelledDecoder.complete(Unit)
                    }
                },
                audio = { sound.complete(Unit) },
                control = { control.complete(Unit) },
            )
        }
        withTimeout(2_000) {
            sound.await()
            control.await()
        }
        assertTrue(collecting.isActive)
        ended.complete(Unit)
        withTimeout(2_000) {
            collecting.await()
            cancelledDecoder.await()
        }
    }

    @Test
    fun udpFailureCancelsBothMediaConsumersAndKeepsItsTypedReason() = runBlocking {
        val videoStarted = CompletableDeferred<Unit>()
        val audioStarted = CompletableDeferred<Unit>()
        val videoCancelled = CompletableDeferred<Unit>()
        val audioCancelled = CompletableDeferred<Unit>()
        val session =
            TestScreenSession(
                events =
                    flow {
                        videoStarted.await()
                        audioStarted.await()
                        throw ToolboxException("screen_udp_disconnected")
                    },
                media =
                    ToolboxScreenMedia(
                        video =
                            flow {
                                videoStarted.complete(Unit)
                                try {
                                    awaitCancellation()
                                } finally {
                                    videoCancelled.complete(Unit)
                                }
                            },
                        audio =
                            flow {
                                audioStarted.complete(Unit)
                                try {
                                    awaitCancellation()
                                } finally {
                                    audioCancelled.complete(Unit)
                                }
                            },
                    ),
            )
        val failure = runCatching {
            withTimeout(2_000) { collectScreenEvents(session, video = {}, audio = {}) }
        }
            .exceptionOrNull()
        assertEquals("screen_udp_disconnected", (failure as ToolboxException).code)
        assertTrue(videoCancelled.isCompleted)
        assertTrue(audioCancelled.isCompleted)
    }

    private class TestScreenSession(
        override val events: Flow<ToolboxScreenEvent>,
        override val media: ToolboxScreenMedia?,
    ) : ToolboxScreenSession {
        override suspend fun input(event: ToolboxScreenInput) = Unit

        override suspend fun getClipboard() = Unit

        override suspend fun setClipboard(text: String, paste: Boolean) = 0L

        override fun close() = Unit
    }
}
