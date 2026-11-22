package dev.ticbox.companion.ui.connection

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.ticbox.companion.api.ConnectionFailure
import dev.ticbox.companion.api.ConnectionPhase
import dev.ticbox.companion.api.ConnectionState
import dev.ticbox.companion.api.FailureKind
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.api.WatchClient
import dev.ticbox.companion.api.WatchSnapshot
import dev.ticbox.companion.api.WatchUnpairClient
import dev.ticbox.companion.api.WatchUnpairOutcome
import dev.ticbox.companion.api.WatchUnpairPhase
import dev.ticbox.companion.api.WatchUnpairResult
import dev.ticbox.companion.api.WatchUnpairState
import dev.ticbox.companion.api.WatchUnpairSupport
import dev.ticbox.companion.ui.CompanionApp
import dev.ticbox.companion.ui.FakeSessions
import dev.ticbox.companion.ui.NoSystemActions
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.TestDependencies
import dev.ticbox.companion.ui.UnpairedClient
import dev.ticbox.companion.ui.demoWatch
import dev.ticbox.companion.ui.navigation.ConnectionRoute
import dev.ticbox.companion.ui.navigation.PhoneTransferRoute
import dev.ticbox.companion.ui.session
import dev.ticbox.companion.ui.session.SessionState
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhoneTransferScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val peer = PeerAddress("AA:BB:CC:DD:EE:FF")
    private val clearAction = "Clear this phone’s pairing"

    private class FakeUnpair(private val checked: WatchUnpairState? = null) : WatchUnpairClient {
        val cleared = mutableListOf<PeerAddress>()
        var remoteRequests = 0
        override val state =
            MutableStateFlow(
                WatchUnpairState(
                    phase = WatchUnpairPhase.IDLE,
                    support = WatchUnpairSupport.UNKNOWN,
                    reason = "fresh_home_info_required",
                )
            )

        override suspend fun probe(): WatchUnpairState {
            checked?.let { state.value = it }
            return state.value
        }

        override suspend fun unpairWithoutReset(): WatchUnpairResult {
            remoteRequests++
            error("This page must never ask the watch to unpair")
        }

        override suspend fun clearLocalPairing(peer: PeerAddress): WatchUnpairResult {
            cleared += peer
            val result =
                WatchUnpairResult(WatchUnpairOutcome.LOCAL_ONLY, peer, reason = "local_only")
            state.value = WatchUnpairState(lastResult = result)
            return result
        }

        override fun close() = Unit
    }

    private fun show(connection: ConnectionState, unpair: WatchUnpairClient): NavHostController {
        val client =
            object : WatchClient by UnpairedClient() {
                override val state = MutableStateFlow(WatchSnapshot(connection))
            }
        val session =
            demoWatch(context).session(key = "watch", client = client).let {
                it.copy(services = it.services.copy(unpair = unpair))
            }
        lateinit var navigation: NavHostController
        compose.setContent {
            navigation = rememberNavController()
            CompanionApp(
                TestDependencies(FakeSessions(SessionState.Ready(session))),
                NoSystemActions,
                navigation,
            )
        }
        compose.waitForIdle()
        return navigation
    }

    private fun openPage(connection: ConnectionState, unpair: WatchUnpairClient = FakeUnpair()) {
        val navigation = show(connection, unpair)
        compose.runOnUiThread { navigation.navigate(PhoneTransferRoute) }
        compose.waitForIdle()
    }

    private fun confirmClearing() {
        compose.onNodeWithText(clearAction).performScrollTo().assertIsEnabled().performClick()
        compose
            .onNode(hasText(clearAction).and(hasAnyAncestor(isDialog())).and(hasClickAction()))
            .performClick()
        compose.waitForIdle()
    }

    private val openTransfer = "Open transfer on watch"

    private fun runTheCheck() {
        compose.onNodeWithText("Check watch support").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun notVerified(sdk: Int?) =
        WatchUnpairState(
            phase = WatchUnpairPhase.IDLE,
            support = WatchUnpairSupport.UNSUPPORTED,
            watchSdk = sdk,
            reason = "home_version_not_verified",
        )

    private val linked = ConnectionState(ConnectionPhase.LINK_READY, peer, sessionId = 1)
    private val ended =
        ConnectionState(
            ConnectionPhase.FAILED,
            peer,
            sessionId = 1,
            failure = ConnectionFailure(FailureKind.ASSOCIATION_TERMINATED, "ended"),
        )

    @Test
    fun theConnectionPageOpensItAsItsOwnPage() {
        val navigation = show(linked, FakeUnpair())
        compose.runOnUiThread { navigation.navigate(ConnectionRoute) }
        compose.waitForIdle()
        compose.onNodeWithText("Check watch support").assertDoesNotExist()
        compose.onNodeWithText("Change paired phone").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(navigation.currentDestination!!.hasRoute<PhoneTransferRoute>())
    }

    @Test
    fun theButtonsRunFromCheckingToOpeningToClearing() {
        openPage(linked)
        val tops =
            listOf("Check watch support", "Open transfer on watch", clearAction).map {
                compose.onNodeWithText(it).getUnclippedBoundsInRoot().top
            }
        assertEquals(tops.sorted(), tops)
        compose.onNodeWithText(context.getString(R.string.phone_transfer_warning)).assertExists()
        compose
            .onAllNodes(hasText("keep", substring = true, ignoreCase = true))
            .assertCountEquals(0)
    }

    @Test
    fun whileTheWatchIsConnectedThisPhoneCannotBeCleared() {
        val unpair = FakeUnpair()
        openPage(linked, unpair)
        compose.onNodeWithText("Check watch support").performScrollTo().assertIsEnabled()
        compose.onNodeWithText(clearAction).performScrollTo().assertIsNotEnabled()
        compose
            .onNodeWithText(context.getString(R.string.phone_transfer_clear_linked))
            .performScrollTo()
            .assertIsDisplayed()
        assertEquals(0, unpair.remoteRequests)
        assertTrue(unpair.cleared.isEmpty())
    }

    @Test
    fun openingTheTransferWaitsForTheCheck() {
        openPage(linked, FakeUnpair(notVerified(sdk = 33)))
        compose.onNodeWithText(openTransfer).performScrollTo().assertIsNotEnabled()
        runTheCheck()
        compose
            .onNodeWithText(context.getString(R.string.unpair_unsupported))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(openTransfer).performScrollTo().assertIsEnabled()
    }

    @Test
    fun wearOs35CannotOpenTheTransfer() {
        openPage(linked, FakeUnpair(notVerified(sdk = 30)))
        runTheCheck()
        compose
            .onNodeWithText(context.getString(R.string.phone_transfer_unsupported))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.unpair_unsupported)).assertDoesNotExist()
        compose.onNodeWithText(openTransfer).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun aCheckThatCouldNotReadTheWatchDoesNotBlockTheTransfer() {
        val timedOut =
            WatchUnpairState(
                phase = WatchUnpairPhase.IDLE,
                support = WatchUnpairSupport.UNKNOWN,
                reason = "fresh_home_info_timeout",
            )
        openPage(linked, FakeUnpair(timedOut))
        runTheCheck()
        compose.onNodeWithText(openTransfer).performScrollTo().assertIsEnabled()
    }

    @Test
    fun aClientThatCannotCheckStillOpensTheTransfer() {
        openPage(linked, WatchUnpairClient.UNAVAILABLE)
        compose.onNodeWithText("Check watch support").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(openTransfer).performScrollTo().assertIsEnabled()
    }

    @Test
    fun afterTheWatchEndsTheAssociationOnlyThisPhoneIsCleared() {
        val unpair = FakeUnpair()
        openPage(ended, unpair)
        compose.onNodeWithText("Check watch support").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Open transfer on watch").performScrollTo().assertIsNotEnabled()
        compose
            .onNodeWithText(context.getString(R.string.phone_transfer_warning))
            .assertDoesNotExist()
        compose
            .onNodeWithText(context.getString(R.string.phone_transfer_association_ended))
            .performScrollTo()
            .assertIsDisplayed()
        confirmClearing()
        assertEquals(listOf(peer), unpair.cleared)
        assertEquals(0, unpair.remoteRequests)
        compose
            .onNodeWithText(context.getString(R.string.phone_transfer_cleared))
            .performScrollTo()
            .assertIsDisplayed()
        compose
            .onAllNodesWithContentDescription(context.getString(R.string.toolbox_step_done))
            .assertCountEquals(3)
    }

    /** An old phone that was away when the transfer finished never hears the watch end things. */
    @Test
    fun aWatchThatCannotBeReachedCanStillBeCleared() {
        val unpair = FakeUnpair()
        openPage(ConnectionState(ConnectionPhase.IDLE, peer), unpair)
        compose
            .onNodeWithText(context.getString(R.string.phone_transfer_disconnected))
            .performScrollTo()
            .assertIsDisplayed()
        confirmClearing()
        assertEquals(listOf(peer), unpair.cleared)
        assertEquals(0, unpair.remoteRequests)
    }
}
