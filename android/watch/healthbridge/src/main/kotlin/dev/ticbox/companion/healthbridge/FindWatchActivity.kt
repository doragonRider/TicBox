package dev.ticbox.companion.healthbridge

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import dev.ticbox.companion.healthbridge.ui.BridgeTheme
import dev.ticbox.companion.healthbridge.ui.FindWatchScreen
import dev.ticbox.companion.protocol.WatchFindingProtocol
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal fun interface FindWatchReporter {
    fun send(sourceNode: String, payload: ByteArray)
}

internal class WearableFindWatchReporter(context: Context) : FindWatchReporter {
    private companion object {
        // One queue across Activity instances: late audio preparation cannot reorder a prior state.
        val delivery = Executors.newSingleThreadExecutor { task -> Thread(task, "watch-finding-state").apply { isDaemon = true } }
    }
    private val context = context.applicationContext
    override fun send(sourceNode: String, payload: ByteArray) {
        delivery.execute {
            try { Tasks.await(Wearable.getMessageClient(context).sendMessage(sourceNode, WatchFindingProtocol.STATE_PATH, payload),
                10, TimeUnit.SECONDS) }
            catch (problem: Exception) { findingLog("state_delivery_failed", problem) }
        }
    }
}

/** The existing WCS remote-intent RPC opens this package-scoped, visible Activity on the watch. */
class FindWatchActivity : ComponentActivity() {
    internal companion object {
        var ringerFactory: (Context) -> FindWatchRinger = ::AndroidFindWatchRinger
        var reporterFactory: (Context) -> FindWatchReporter = ::WearableFindWatchReporter
    }

    private class Run(val key: FindWatchKey, val durationMillis: Long) {
        var registration: FindWatchRequests.Registration? = null
        var ringer: FindWatchRinger? = null
        var timeout: Runnable? = null
        var started = false
        var failed = false
        var output = FindWatchOutput(preparingAudio = true)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var status by mutableIntStateOf(R.string.find_watch_preparing)
    private lateinit var reporter: FindWatchReporter
    private var current: Run? = null
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        reporter = reporterFactory(this)
        setContent { BridgeTheme { FindWatchScreen(status) { stopFinding("user_stop") } } }
        accept(intent)
    }

    override fun onPostResume() {
        super.onPostResume()
        resumed = true
        current?.let(::start)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        accept(intent)
        if (resumed) current?.let(::start)
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onStop() {
        resumed = false
        stopFinding("activity_left")
        super.onStop()
    }

    override fun onDestroy() {
        stopFinding("activity_left", finishActivity = false)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun accept(intent: Intent?) {
        val next = try {
            if (intent?.action != WatchFindingProtocol.ACTION) null else {
                val request = intent.getStringExtra(WatchFindingProtocol.REQUEST_ID)
                val source = intent.getStringExtra(WatchFindingProtocol.SOURCE_NODE)
                if (request.isNullOrBlank() || source.isNullOrBlank()) null else {
                    val duration = when (val value = intent.extras?.get(WatchFindingProtocol.DURATION_MILLIS)) {
                        is Number -> value.toLong()
                        is String -> value.toLongOrNull()
                        else -> null
                    }?.takeIf { it > 0 } ?: WatchFindingProtocol.MAX_DURATION_MILLIS
                    Run(FindWatchKey(source, request), duration.coerceAtMost(WatchFindingProtocol.MAX_DURATION_MILLIS))
                }
            }
        } catch (problem: Exception) { findingLog("launch_unreadable", problem); null }
        if (next == null) {
            stopFinding("replaced", finishActivity = false)
            status = R.string.find_watch_invalid_request
            return
        }
        current?.takeIf { it.key == next.key }?.let {
            // Duplicate remote-intent delivery neither restarts playback nor extends the deadline.
            if (it.output.active && !it.failed) report(it, "ringing", it.output)
            return
        }
        stopFinding("replaced", finishActivity = false)
        current = next
        next.registration = FindWatchRequests.register(next.key) { reason ->
            if (current === next) stopFinding(reason)
        }
        if (next.registration == null) {
            stopFinding("cancelled_before_start")
            return
        }
        status = R.string.find_watch_preparing
        next.timeout = Runnable { if (current === next) stopFinding("timeout") }.also {
            handler.postDelayed(it, next.durationMillis)
        }
    }

    private fun start(run: Run) {
        if (current !== run || run.started || run.failed) return
        // The Binder callback can record STOP before its posted main-thread cleanup runs.
        if (FindWatchRequests.isCancelled(run.key)) { stopFinding("cancelled_before_start"); return }
        run.started = true
        try {
            val ringer = ringerFactory(this)
            run.ringer = ringer
            ringer.start outputChanged@{ output ->
                if (current !== run || run.failed) return@outputChanged
                run.output = output
                when {
                    output.active -> {
                        status = if (output.audioPlaybackStarted) R.string.find_watch_ringing else R.string.find_watch_vibrating
                        report(run, "ringing", output)
                    }
                    output.preparingAudio -> status = R.string.find_watch_preparing
                    else -> fail(run, output.reason ?: "no_output")
                }
            }
        } catch (problem: Exception) {
            findingLog("ring_start_failed", problem)
            fail(run, "ring_start_failed")
        }
    }

    private fun fail(run: Run, reason: String) {
        if (current !== run || run.failed) return
        run.failed = true
        runCatching { run.ringer?.stop() }.onFailure { findingLog("ring_stop_failed", it) }
        run.output = FindWatchOutput(reason = reason)
        status = R.string.find_watch_failed
        report(run, "failed", run.output)
        // Keep the visible stop button and matching remote STOP usable after either output fails.
    }

    private fun stopFinding(reason: String, finishActivity: Boolean = true) {
        val run = current
        current = null
        if (run != null) {
            run.timeout?.let(handler::removeCallbacks)
            run.registration?.close()
            runCatching { run.ringer?.stop() }.onFailure { findingLog("ring_stop_failed", it) }
            report(run, "stopped", FindWatchOutput(reason = reason))
        }
        if (finishActivity && !isFinishing) finish()
    }

    private fun report(run: Run, state: String, output: FindWatchOutput) {
        try {
            reporter.send(run.key.sourceNode, WatchFindingProtocol.state(run.key.requestId, state,
                audioPlaybackStarted = output.audioPlaybackStarted, vibrationRequested = output.vibrationRequested,
                reason = output.reason))
        } catch (problem: Exception) { findingLog("state_delivery_failed", problem) }
    }
}
