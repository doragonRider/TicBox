package dev.ticbox.companion.ui.home

import dev.ticbox.companion.api.ConnectionPhase
import dev.ticbox.companion.api.ConnectionState
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.api.WatchClient
import dev.ticbox.companion.api.WatchObservation
import dev.ticbox.companion.api.WatchSnapshot
import dev.ticbox.companion.ui.FakeSessions
import dev.ticbox.companion.ui.TestDependencies
import dev.ticbox.companion.ui.UnpairedClient
import dev.ticbox.companion.ui.common.MINUTE_MILLIS
import dev.ticbox.companion.ui.demoWatch
import dev.ticbox.companion.ui.session
import dev.ticbox.companion.ui.session.SessionState
import java.io.IOException
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeBatteryRefreshTest {
    private class CountingClient : WatchClient by UnpairedClient() {
        override val state = MutableStateFlow(WatchSnapshot())
        var requests = 0
        var failure: Exception? = null

        override suspend fun refreshBattery() {
            requests++
            failure?.let { throw it }
        }
    }

    private val client = CountingClient()
    private val now = 1_800_000_000_000L

    @Before fun useTestMain() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun restoreMain() = Dispatchers.resetMain()

    private fun viewModel(): HomeViewModel {
        val demo = demoWatch(RuntimeEnvironment.getApplication())
        val session = demo.session(key = "service", client = client)
        return HomeViewModel(TestDependencies(FakeSessions(SessionState.Ready(session))))
    }

    private fun link(session: Long, battery: WatchObservation<Int>? = null) =
        WatchSnapshot(
            ConnectionState(ConnectionPhase.LINK_READY, PeerAddress("AA:BB:CC:DD:EE:FF"), session),
            battery,
        )

    /** Also runs what falls due at the very end, which advanceTimeBy alone leaves waiting. */
    private fun TestScope.pass(minutes: Long) {
        advanceTimeBy(minutes * MINUTE_MILLIS)
        runCurrent()
    }

    @Test
    fun aLinkThatHasNotAskedAsksEvenWithARecentValue() {
        assertEquals(0, batteryRefreshDelay(WatchObservation(74, now, false), null, now))
    }

    @Test
    fun theWaitRunsFromTheLatestRequestOrValue() {
        val asked = now - 4 * MINUTE_MILLIS
        val value = WatchObservation(74, now - MINUTE_MILLIS, false)
        assertEquals(6 * MINUTE_MILLIS, batteryRefreshDelay(null, asked, now))
        assertEquals(9 * MINUTE_MILLIS, batteryRefreshDelay(value, asked, now))
        assertEquals(0, batteryRefreshDelay(null, now - 11 * MINUTE_MILLIS, now))
    }

    @Test
    fun aClockMovedBackDoesNotStretchTheWait() {
        assertEquals(
            BATTERY_REFRESH_MILLIS,
            batteryRefreshDelay(null, now + 60 * MINUTE_MILLIS, now),
        )
    }

    @Test
    fun asksOnlyOnceTheLinkIsReadyAndThenEveryTenMinutes() = runTest {
        val home = viewModel()
        backgroundScope.launch { home.keepBatteryCurrent() }
        client.state.value =
            WatchSnapshot(ConnectionState(ConnectionPhase.CONNECTING, sessionId = 1))
        pass(0)
        assertEquals(0, client.requests)
        client.state.value = link(1)
        pass(0)
        assertEquals(1, client.requests)
        pass(9)
        assertEquals(1, client.requests)
        pass(2)
        assertEquals(2, client.requests)
    }

    @Test
    fun aValueRestartsTheWaitAndANewLinkAsksAgain() = runTest {
        val home = viewModel()
        backgroundScope.launch { home.keepBatteryCurrent() }
        client.state.value = link(1)
        pass(6)
        client.state.value = link(1, WatchObservation(74, System.currentTimeMillis(), false))
        pass(9)
        assertEquals(1, client.requests)
        client.state.value = link(2, WatchObservation(74, System.currentTimeMillis(), true))
        pass(0)
        assertEquals(2, client.requests)
    }

    @Test
    fun aHiddenPageDoesNotAskAndReturningWaitsItsTurn() = runTest {
        val home = viewModel()
        val showing = backgroundScope.launch { home.keepBatteryCurrent() }
        client.state.value = link(1)
        pass(0)
        showing.cancel()
        pass(30)
        assertEquals(1, client.requests)
        backgroundScope.launch { home.keepBatteryCurrent() }
        pass(0)
        assertEquals(1, client.requests)
        pass(11)
        assertEquals(2, client.requests)
    }

    @Test
    fun aFailedRequestWaitsLikeAnyOther() = runTest {
        client.failure = IOException("synthetic")
        val home = viewModel()
        backgroundScope.launch { home.keepBatteryCurrent() }
        client.state.value = link(1)
        pass(9)
        assertEquals(1, client.requests)
        pass(2)
        assertEquals(2, client.requests)
    }
}
