package dev.ticbox.companion.ui.connection

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.rememberNavController
import dev.ticbox.companion.api.ConnectionRecoveryClient
import dev.ticbox.companion.api.ConnectionRecoveryPhase
import dev.ticbox.companion.api.ConnectionRecoveryState
import dev.ticbox.companion.api.PairingClient
import dev.ticbox.companion.api.PairingOptions
import dev.ticbox.companion.api.PairingPhase
import dev.ticbox.companion.api.PairingSnapshot
import dev.ticbox.companion.api.PairingUserAction
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.ui.CompanionApp
import dev.ticbox.companion.ui.FakeSessions
import dev.ticbox.companion.ui.NoSystemActions
import dev.ticbox.companion.ui.TestDependencies
import dev.ticbox.companion.ui.demoWatch
import dev.ticbox.companion.ui.session
import dev.ticbox.companion.ui.session.SessionState
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h2000dp")
class ConnectionAssociationTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val missing =
        "Android hasn’t linked this watch to the app, so the app won’t reconnect after Android " +
            "closes it, and the watch can’t answer or end calls."

    private class FakePairing : PairingClient {
        val started = mutableListOf<PairingOptions>()
        override val state = MutableStateFlow(PairingSnapshot())
        override val actions = MutableStateFlow(emptyList<PairingUserAction>())

        override suspend fun start(options: PairingOptions): Long {
            started += options
            state.value = PairingSnapshot(1, PairingPhase.ASSOCIATING, options.peer)
            return 1
        }

        override suspend fun syncPermissions(peer: PeerAddress) =
            error("Linking must not transfer permissions")

        override fun cancel() = Unit

        override fun cancel(operationId: Long) = false

        override fun close() = Unit
    }

    @Test
    fun aWatchKeptConnectedWithoutAnAssociationIsLinkedWithoutPairingAgain() {
        val demo = demoWatch(context)
        val peer = demo.client.state.value.connection.peer!!
        val recovered =
            MutableStateFlow(
                ConnectionRecoveryState(
                    ConnectionRecoveryPhase.RUNNING,
                    desiredPeer = peer,
                    durable = true,
                )
            )
        val recovery =
            object : ConnectionRecoveryClient {
                override val state = recovered

                override suspend fun resume() = Unit
            }
        val pairing = FakePairing()
        val session =
            demo.session(key = "service").let {
                it.copy(services = it.services.copy(recovery = recovery, pairing = pairing))
            }
        compose.setContent {
            CompanionApp(
                TestDependencies(FakeSessions(SessionState.Ready(session))),
                NoSystemActions,
                rememberNavController(),
            )
        }
        compose.waitForIdle()

        compose.onNodeWithText(missing).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Review").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Link watch").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(
            listOf(
                PairingOptions(peer = peer, syncPermissions = false, replaceExistingBond = false)
            ),
            pairing.started,
        )

        pairing.state.value = PairingSnapshot(1, PairingPhase.COMPLETED, peer, associationId = 7)
        recovered.value = recovered.value.copy(associationId = 7)
        compose.waitUntil(5_000) {
            compose
                .onAllNodes(hasText("The watch is linked to the app."))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.waitForIdle()
        compose.onNodeWithText("Link watch").assertDoesNotExist()
    }
}
