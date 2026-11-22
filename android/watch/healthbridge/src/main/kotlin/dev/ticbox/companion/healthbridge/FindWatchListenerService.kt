package dev.ticbox.companion.healthbridge

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dev.ticbox.companion.protocol.WatchFindingProtocol

/** Finding is independent of health-provider consent. Only the paired message sender can stop its request. */
class FindWatchListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchFindingProtocol.STOP_PATH) return
        try {
            val request = WatchFindingProtocol.text(WatchFindingProtocol.parse(event.data), "requestId")
            if (!request.isNullOrBlank()) FindWatchRequests.cancel(event.sourceNodeId, request)
        } catch (problem: Exception) { findingLog("stop_message_unreadable", problem) }
    }
}

internal data class FindWatchKey(val sourceNode: String, val requestId: String)

/** Coordinates Activity and listener callbacks, including a STOP arriving before remote-intent delivery. */
internal object FindWatchRequests {
    private const val MAX_RECENT_CANCELLATIONS = 64
    private val lock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private val cancelled = LinkedHashMap<FindWatchKey, Long>()
    private var active: Registration? = null

    class Registration internal constructor(val key: FindWatchKey, internal val stop: (String) -> Unit) : AutoCloseable {
        override fun close() = synchronized(lock) { if (active === this) active = null }
    }

    fun register(key: FindWatchKey, stop: (String) -> Unit): Registration? {
        val (registration, previous) = synchronized(lock) {
            prune()
            if (cancelled.containsKey(key)) return null
            val previous = active
            val registration = Registration(key, stop)
            active = registration
            registration to previous
        }
        previous?.let {
            // Release old vibration before a new Activity starts using the same app vibrator.
            if (Looper.myLooper() == Looper.getMainLooper()) it.stop("replaced")
            else handler.post { it.stop("replaced") }
        }
        return registration
    }

    fun cancel(sourceNode: String, requestId: String) {
        val key = FindWatchKey(sourceNode, requestId)
        val selected = synchronized(lock) {
            prune()
            cancelled.remove(key)
            cancelled[key] = SystemClock.elapsedRealtime() + WatchFindingProtocol.MAX_DURATION_MILLIS
            while (cancelled.size > MAX_RECENT_CANCELLATIONS) cancelled.remove(cancelled.keys.first())
            active?.takeIf { it.key == key }
        }
        selected?.let { handler.post { it.stop("remote_stop") } }
    }

    fun isCancelled(key: FindWatchKey) = synchronized(lock) { prune(); cancelled.containsKey(key) }

    private fun prune() {
        val now = SystemClock.elapsedRealtime()
        cancelled.entries.removeAll { it.value <= now }
    }
}

internal fun findingLog(event: String, problem: Throwable? = null) {
    // No node identifiers or payloads enter logcat.
    Log.w("TicBoxFinding", if (problem == null) event else "$event: ${problem.javaClass.simpleName}")
}
