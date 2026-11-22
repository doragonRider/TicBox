package dev.ticbox.companion.ui.toolbox

import dev.ticbox.companion.api.ToolboxScreenEvent
import dev.ticbox.companion.api.ToolboxScreenSession
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Media consumers never wait for one another or for control events on separated sessions. */
internal suspend fun collectScreenEvents(
    session: ToolboxScreenSession,
    video: suspend (ToolboxScreenEvent) -> Unit,
    audio: suspend (ToolboxScreenEvent) -> Unit,
    control: suspend (ToolboxScreenEvent) -> Unit = {},
) {
    val media = session.media
    if (media == null) {
        session.events.collect { event ->
            when (event) {
                is ToolboxScreenEvent.Configuration,
                is ToolboxScreenEvent.Frame -> video(event)
                is ToolboxScreenEvent.AudioConfiguration,
                is ToolboxScreenEvent.AudioFrame -> audio(event)
                else -> control(event)
            }
        }
        return
    }
    coroutineScope {
        val picture = launch { media.video.collect(video) }
        val sound = launch { media.audio.collect(audio) }
        try {
            session.events.collect(control)
        } finally {
            picture.cancel()
            sound.cancel()
        }
    }
}
