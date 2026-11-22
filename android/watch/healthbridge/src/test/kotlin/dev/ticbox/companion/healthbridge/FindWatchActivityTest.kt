package dev.ticbox.companion.healthbridge

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.android.gms.wearable.MessageEvent
import dev.ticbox.companion.protocol.WatchFindingProtocol
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Visible Activity lifecycle and real listener dispatch; all request IDs/nodes are synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class FindWatchActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    private class Ringer(private val initial: FindWatchOutput) : FindWatchRinger {
        var starts = 0
        var stops = 0
        var changed: ((FindWatchOutput) -> Unit)? = null
        override fun start(changed: (FindWatchOutput) -> Unit) { starts++; this.changed = changed; changed(initial) }
        override fun stop() { stops++; changed = null }
    }
    private data class Report(val node: String, val fields: JsonObject) {
        fun text(key: String) = WatchFindingProtocol.text(fields, key)
        fun flag(key: String) = WatchFindingProtocol.flag(fields, key)
    }
    private val activities = mutableListOf<ActivityController<FindWatchActivity>>()
    private val ringers = mutableListOf<Ringer>()
    private val reports = mutableListOf<Report>()
    private var initialOutput = FindWatchOutput(vibrationRequested = true, preparingAudio = true)
    private lateinit var listener: ServiceController<FindWatchListenerService>
    private val requestId = UUID.randomUUID().toString()

    @Before fun configure() {
        FindWatchActivity.ringerFactory = { Ringer(initialOutput).also(ringers::add) }
        FindWatchActivity.reporterFactory = { FindWatchReporter { node, bytes -> reports += Report(node, WatchFindingProtocol.parse(bytes)) } }
        listener = Robolectric.buildService(FindWatchListenerService::class.java).create()
        // Finding must work even when health-provider reads have never been enabled.
        BridgeSettings.setEnabled(RuntimeEnvironment.getApplication(), false)
    }

    @After fun cleanup() {
        activities.asReversed().forEach { it.close() }
        listener.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        FindWatchActivity.ringerFactory = ::AndroidFindWatchRinger
        FindWatchActivity.reporterFactory = ::WearableFindWatchReporter
    }

    private fun intent(id: String = requestId, node: String = "phone-fixture", durationMillis: Long = 60_000) =
        Intent(RuntimeEnvironment.getApplication(), FindWatchActivity::class.java).apply {
            action = WatchFindingProtocol.ACTION
            putExtra(WatchFindingProtocol.REQUEST_ID, id)
            putExtra(WatchFindingProtocol.SOURCE_NODE, node)
            putExtra(WatchFindingProtocol.DURATION_MILLIS, durationMillis)
        }

    private fun controller(intent: Intent = intent()) =
        Robolectric.buildActivity(FindWatchActivity::class.java, intent).also(activities::add)

    private fun stop(id: String = requestId, node: String = "phone-fixture", path: String = WatchFindingProtocol.STOP_PATH,
        drain: Boolean = true) {
        listener.get().onMessageReceived(object : MessageEvent {
            override fun getRequestId() = 1
            override fun getPath() = path
            override fun getData() = WatchFindingProtocol.stop(id)
            override fun getSourceNodeId() = node
        })
        if (drain) shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun onlyResumedActivityStartsEffectsAndLeavingStopsThem() {
        val activity = controller().create().start()
        assertTrue(ringers.isEmpty())
        activity.resume().visible()
        assertEquals(1, ringers.single().starts)
        assertEquals("ringing", reports.single().text("state"))
        assertFalse(reports.single().flag("audioPlaybackStarted")!!)
        assertTrue(reports.single().flag("vibrationRequested")!!)
        activity.pause().stop()
        assertEquals(1, ringers.single().stops)
        assertTrue(activity.get().isFinishing)
        assertEquals("activity_left", reports.last().text("reason"))
        assertEquals("stopped", reports.last().text("state"))
    }

    @Test fun stopButtonWorksWhileOnlyVibrationIsRunning() {
        val activity = controller().setup()
        compose.onNodeWithText("Stop").performClick()
        assertTrue(activity.get().isFinishing)
        assertEquals(1, ringers.single().stops)
        assertEquals("user_stop", reports.last().text("reason"))
    }

    @Test fun remoteStopRequiresMatchingSourceRequestAndPath() {
        val activity = controller().setup()
        stop(node = "other-phone")
        stop(id = "other-request")
        stop(path = "/ticbox_local_health/wake")
        assertEquals(0, ringers.single().stops)
        assertFalse(activity.get().isFinishing)
        stop()
        assertEquals(1, ringers.single().stops)
        assertEquals("remote_stop", reports.last().text("reason"))
        assertTrue(activity.get().isFinishing)
    }

    @Test fun stopBeforeRemoteIntentPreventsAnyOutputStarting() {
        stop()
        val activity = controller().create()
        assertTrue(ringers.isEmpty())
        assertTrue(activity.get().isFinishing)
        assertEquals("phone-fixture", reports.single().node)
        assertEquals(requestId, reports.single().text("requestId"))
        assertEquals("stopped", reports.single().text("state"))
        assertEquals("cancelled_before_start", reports.single().text("reason"))
    }

    @Test fun stopBetweenCreationAndResumeCannotStartLateAudio() {
        val activity = controller().create().start()
        stop(drain = false)
        activity.resume().visible()
        assertTrue(ringers.isEmpty())
        assertTrue(activity.get().isFinishing)
        assertEquals("cancelled_before_start", reports.last().text("reason"))
    }

    @Test fun deadlineIsCappedAtFiveMinutesAndDuplicateIntentCannotExtendIt() {
        val activity = controller(intent(durationMillis = Long.MAX_VALUE)).create()
        val deadline = SystemClock.uptimeMillis() + WatchFindingProtocol.MAX_DURATION_MILLIS
        activity.start().resume().visible()
        shadowOf(Looper.getMainLooper()).idleFor(200_000, TimeUnit.MILLISECONDS)
        activity.newIntent(intent(durationMillis = Long.MAX_VALUE))
        assertEquals(1, ringers.single().starts)
        // ActivityController's visibility traversal can itself advance the virtual clock.
        shadowOf(Looper.getMainLooper()).idleFor(deadline - 1 - SystemClock.uptimeMillis(), TimeUnit.MILLISECONDS)
        assertEquals(0, ringers.single().stops)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
        assertEquals(1, ringers.single().stops)
        assertEquals("timeout", reports.last().text("reason"))
    }

    @Test fun replacementStopsPriorRequestAndItsLateStopCannotStopNewRequest() {
        val activity = controller().setup()
        val nextId = UUID.randomUUID().toString()
        activity.newIntent(intent(id = nextId))
        assertEquals(2, ringers.size)
        assertEquals(1, ringers.first().stops)
        assertEquals("replaced", reports.first { it.text("state") == "stopped" }.text("reason"))
        stop()
        assertEquals(0, ringers.last().stops)
        assertFalse(activity.get().isFinishing)
        stop(id = nextId)
        assertEquals(1, ringers.last().stops)
        assertEquals(nextId, reports.last().text("requestId"))
    }

    @Test fun asynchronousAudioConfirmationUpdatesFlagsOnlyAfterPlaybackAndStopsRemainUsableOnFailure() {
        initialOutput = FindWatchOutput(preparingAudio = true)
        val activity = controller().setup()
        assertTrue(reports.isEmpty())
        val update = ringers.single().changed!!
        update(FindWatchOutput(audioPlaybackStarted = true))
        assertEquals("ringing", reports.single().text("state"))
        assertTrue(reports.single().flag("audioPlaybackStarted")!!)
        update(FindWatchOutput(reason = "playback_error"))
        assertEquals("failed", reports.last().text("state"))
        assertFalse(activity.get().isFinishing)
        compose.onNodeWithText("Stop").assertIsDisplayed()
        compose.onNodeWithText("Stop").performClick()
        assertTrue(activity.get().isFinishing)
        assertEquals("stopped", reports.last().text("state"))
        update(FindWatchOutput(audioPlaybackStarted = true))
        assertEquals("stopped", reports.last().text("state"))
    }
}
