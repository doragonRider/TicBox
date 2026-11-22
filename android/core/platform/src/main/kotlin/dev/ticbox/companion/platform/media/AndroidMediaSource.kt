@file:Suppress("DEPRECATION")
package dev.ticbox.companion.platform.media

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.media.*
import dev.ticbox.companion.platform.DataMapBundles
import dev.ticbox.companion.platform.notification.NotificationListenerHub
import dev.ticbox.companion.platform.notification.WatchNotificationListener
import dev.ticbox.companion.protocol.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Active third-party sessions are authorized by our existing enabled notification listener. */
@SuppressLint("MissingPermission") // Enabled notification listener is the documented alternative.
class AndroidMediaSource(context: Context, scope: CoroutineScope, private val diagnostics: DiagnosticSink = DiagnosticSink.NONE) : MediaSource, AutoCloseable {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(this.context, WatchNotificationListener::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val lifetime = SupervisorJob(scope.coroutineContext[Job])
    private val worker = CoroutineScope(scope.coroutineContext + lifetime)
    private val closed = AtomicBoolean()
    private val snapshotLock = Mutex()
    private val mapper = AndroidMediaMapper(this.context, diagnostics)
    private val dispatcher = MediaCommandDispatcher(diagnostics)
    private val mutableState = MutableStateFlow(MediaSourceState())
    override val state: StateFlow<MediaSourceState> = mutableState.asStateFlow()
    private val changes = MutableStateFlow(0L)
    override val events: Flow<Unit> = changes.map { delay(100) }
    // Only the main dispatcher accesses native controller registration and this map.
    private val controllers = linkedMapOf<MediaSession.Token, Entry>()
    private var registered = false
    private data class Entry(val controller: MediaController, val id: String, val callback: MediaController.Callback)
    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { values ->
        if (!closed.get() && registered && NotificationListenerHub.availability.value.accessGranted) try { replace(values.orEmpty()); mutableState.value = MediaSourceState(true, true) }
        catch (problem: Exception) { metadata("active_sessions_update_failed", problem) }
    }

    init {
        worker.launch {
            NotificationListenerHub.refreshAccess(this@AndroidMediaSource.context)
            NotificationListenerHub.availability.collect { availability -> withContext(Dispatchers.Main.immediate) {
                if (closed.get()) return@withContext
                if (availability.accessGranted) {
                    try {
                        if (!registered) { manager.addOnActiveSessionsChangedListener(listener, component, main); registered = true }
                        replace(manager.getActiveSessions(component))
                        mutableState.value = MediaSourceState(true, true)
                    } catch (problem: Exception) { mutableState.value = MediaSourceState(availability.accessGranted, false); metadata("active_sessions_unavailable", problem) }
                } else {
                    releaseNative(); mutableState.value = MediaSourceState(false, false)
                }
                changed()
            } }
        }
        worker.launch { NotificationListenerHub.changes.collect { changed() } }
    }

    private fun replace(values: List<MediaController>) {
        val before = controllers.keys.toList()
        val tokens = values.mapTo(mutableSetOf()) { it.sessionToken }
        controllers.keys.filter { it !in tokens }.forEach { token -> controllers.remove(token)?.let { runCatching { it.controller.unregisterCallback(it.callback) } } }
        val ordered = linkedMapOf<MediaSession.Token, Entry>()
        values.forEach { controller ->
            val token = controller.sessionToken
            val existing = controllers[token]
            val entry = existing ?: run {
                val callback = object : MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) { changed() }
                    override fun onMetadataChanged(metadata: MediaMetadata?) { changed() }
                    override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) { changed() }
                    override fun onQueueTitleChanged(title: CharSequence?) { changed() }
                    override fun onExtrasChanged(extras: Bundle?) { changed() }
                    override fun onAudioInfoChanged(info: MediaController.PlaybackInfo) { changed() }
                    override fun onSessionDestroyed() {
                        controllers.remove(token)?.let { runCatching { it.controller.unregisterCallback(it.callback) } }; changed()
                    }
                }
                controller.registerCallback(callback, main)
                Entry(controller, UUID.randomUUID().toString(), callback)
            }
            ordered[token] = entry
        }
        controllers.clear(); controllers.putAll(ordered)
        if (before != ordered.keys.toList()) changed()
    }

    override suspend fun snapshot(): List<PhoneMediaSession> = snapshotLock.withLock {
        check(!closed.get())
        if (!state.value.available) throw IllegalStateException("Media sessions unavailable")
        val values = withContext(Dispatchers.Main.immediate) {
            replace(manager.getActiveSessions(component)); controllers.values.toList()
        }
        val notifications = if (NotificationListenerHub.availability.value.connected) try { NotificationListenerHub.active().toList() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (problem: Exception) { metadata("notification_metadata_unavailable", problem); null } else null
        withContext(Dispatchers.Default) {
            val images = AndroidMediaMapper.ImageBudget()
            values.map { entry ->
                ensureActive()
                // One unreadable native session makes this snapshot incomplete. Preserve the last
                // published controls instead of selecting/removing from an invented empty list.
                mapper.map(entry.controller, entry.id, notifications, images)
            }
        }
    }

    override suspend fun execute(sessionId: String, command: MediaCommand): MediaCommandOutcome = withContext(Dispatchers.Main.immediate) {
        val count = MediaProtocol.operationCount(command)
        if (closed.get() || !state.value.available) return@withContext MediaCommandOutcome(count, 0)
        val controller = controllers.values.firstOrNull { it.id == sessionId }?.controller ?: return@withContext MediaCommandOutcome(count, 0)
        dispatcher.dispatch(command, object : MediaActionPort {
            override suspend fun transport(command: String): Boolean {
                when (command) {
                    "seek_forward" -> controller.transportControls.fastForward()
                    "seek_back" -> controller.transportControls.rewind()
                    else -> {
                        val code = when (command) { "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; "togglepause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "play" -> KeyEvent.KEYCODE_MEDIA_PLAY; "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
                            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE; else -> return false }
                        val down = controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                        val up = controller.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                        return down && up
                    }
                }
                return true
            }
            override suspend fun seekTo(positionMillis: Long): Boolean { controller.transportControls.seekTo(positionMillis); return true }
            override suspend fun setThumbRating(value: Int): Boolean {
                val rating = if (value == 0) Rating.newUnratedRating(Rating.RATING_THUMB_UP_DOWN) else Rating.newThumbRating(value > 0)
                controller.transportControls.setRating(rating); return true
            }
            override suspend fun adjustVolume(direction: Int): Boolean { controller.adjustVolume(direction, AudioManager.FLAG_SHOW_UI); return true }
            override suspend fun setAbsoluteVolume(value: Float): Boolean {
                if (!value.isFinite()) return false
                val max = controller.playbackInfo.maxVolume
                if (max <= 0) return false
                controller.setVolumeTo((value * max).toInt(), AudioManager.FLAG_SHOW_UI); return true
            }
            override suspend fun customAction(action: String, extras: Map<String, DataMapValue>): Boolean {
                controller.transportControls.sendCustomAction(action, DataMapBundles.fromMap(extras)); return true
            }
            override suspend fun skipToQueueItem(id: Long): Boolean { controller.transportControls.skipToQueueItem(id); return true }
        }).also { changed() }
    }

    private fun releaseNative() {
        if (registered) { runCatching { manager.removeOnActiveSessionsChangedListener(listener) }; registered = false }
        controllers.values.forEach { runCatching { it.controller.unregisterCallback(it.callback) } }; controllers.clear()
    }
    private fun changed() { if (!closed.get()) changes.update { it + 1 } }
    private fun metadata(event: String, failure: Exception) { runCatching { diagnostics.record(ProtocolDiagnostic("media", event, details = mapOf("exception" to failure.javaClass.simpleName))) } }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        lifetime.cancel(); mutableState.value = MediaSourceState()
        main.post { releaseNative() }
    }
}
