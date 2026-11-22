package dev.ticbox.companion.ui.bridge

import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.ticbox.companion.api.LocalHealthJournalException
import dev.ticbox.companion.api.LocalHealthJournalSource
import dev.ticbox.companion.api.LocalHealthJournalStatus
import dev.ticbox.companion.api.LocalHealthSyncClient
import dev.ticbox.companion.ui.CompanionApp
import dev.ticbox.companion.ui.FakeSessions
import dev.ticbox.companion.ui.NoSystemActions
import dev.ticbox.companion.ui.TestDependencies
import dev.ticbox.companion.ui.demoWatch
import dev.ticbox.companion.ui.navigation.HealthJournalRoute
import dev.ticbox.companion.ui.navigation.LocalBridgeRoute
import dev.ticbox.companion.ui.session
import dev.ticbox.companion.ui.session.SessionState
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h2000dp")
class HealthJournalScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()

    private fun show(
        journal: ((LocalHealthSyncClient) -> FakeJournal)? = null,
        route: Any = HealthJournalRoute,
    ): FakeJournal? {
        val watch = demoWatch(context)
        val fake = journal?.invoke(watch.localHealth)
        val session = watch.session(localHealth = fake ?: watch.localHealth)
        lateinit var navController: NavHostController
        compose.setContent {
            navController = rememberNavController()
            CompanionApp(
                TestDependencies(FakeSessions(SessionState.Ready(session))),
                NoSystemActions,
                navController,
            )
        }
        compose.waitForIdle()
        compose.runOnUiThread { navController.navigate(route) }
        compose.waitForIdle()
        return fake
    }

    private fun waitFor(value: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(value, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun switches() = compose.onAllNodes(hasText("Change log") and isToggleable())

    private fun waitForSwitches(count: Int) {
        compose.waitUntil(5_000) { switches().fetchSemanticsNodes().size == count }
        compose.waitForIdle()
    }

    @Test
    fun localBridgeLeadsHereOnceItsReady() {
        show(route = LocalBridgeRoute)
        compose.onNodeWithText("Health change log").performScrollTo().performClick()
        waitForSwitches(2)
    }

    @Test
    fun turningItOnAsksFirstAndThenChangesOnlyThatSource() {
        val journal = show({ FakeJournal(it) })!!
        waitForSwitches(2)

        switches()[1].performClick()
        waitFor("adds triggers")
        assertTrue(journal.writes.isEmpty())
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertTrue(journal.writes.isEmpty())

        switches()[1].performClick()
        waitFor("adds triggers")
        compose.onNodeWithText("Turn on").performClick()
        compose.waitForIdle()
        assertEquals(listOf(LocalHealthJournalSource.FITNESS to true), journal.writes)
        switches()[1].assertIsOn()
    }

    @Test
    fun turningItOffNeedsNoConfirmation() {
        val journal =
            show({ base ->
                FakeJournal(base).also {
                    it.status[LocalHealthJournalSource.HEALTH] =
                        LocalHealthJournalStatus(LocalHealthJournalSource.HEALTH, true, true)
                }
            })!!
        waitForSwitches(2)
        switches()[0].performClick()
        compose.waitForIdle()
        assertEquals(listOf(LocalHealthJournalSource.HEALTH to false), journal.writes)
    }

    @Test
    fun aLogLeftBehindCanBeRemoved() {
        val journal =
            show({ base ->
                FakeJournal(base).also {
                    it.status[LocalHealthJournalSource.HEALTH] =
                        LocalHealthJournalStatus(LocalHealthJournalSource.HEALTH, false, true)
                }
            })!!
        waitFor("still in the watch's database")
        compose.onNodeWithText("Remove from the watch").performClick()
        compose.waitForIdle()
        assertEquals(listOf(LocalHealthJournalSource.HEALTH to false), journal.writes)
    }

    @Test
    fun aLogThatWentMissingSaysHowToGetItBack() {
        show({ base ->
            FakeJournal(base).also {
                it.status[LocalHealthJournalSource.HEALTH] =
                    LocalHealthJournalStatus(LocalHealthJournalSource.HEALTH, true, false)
            }
        })
        waitFor("Turn it off, then on again")
    }

    @Test
    fun aWriteWithAnUnknownResultIsReadBackAndNeverRepeated() {
        val journal =
            show({ base -> FakeJournal(base, writeFailure = "journal_operation_unconfirmed") })!!
        waitForSwitches(2)
        val readsBefore = journal.reads

        switches()[0].performClick()
        waitFor("adds triggers")
        compose.onNodeWithText("Turn on").performClick()
        waitFor("Couldn't confirm the result")
        assertEquals(listOf(LocalHealthJournalSource.HEALTH to true), journal.writes)
        assertEquals(readsBefore + 1, journal.reads)
    }

    @Test
    fun aStatusThatCannotBeReadShowsNoSwitchAndSaysWhy() {
        show({ base -> FakeJournal(base, readFailure = "journal_sync_in_progress") })
        waitFor("The watch is syncing health data")
        waitForSwitches(0)
        compose.onAllNodesWithText("Read status").onFirst().performScrollTo()
    }
}

class FakeJournal(
    base: LocalHealthSyncClient,
    private val readFailure: String? = null,
    private val writeFailure: String? = null,
) : LocalHealthSyncClient by base {
    val status =
        LocalHealthJournalSource.entries
            .associateWith { LocalHealthJournalStatus(it, false, false) }
            .toMutableMap()
    val writes = mutableListOf<Pair<LocalHealthJournalSource, Boolean>>()
    var reads = 0

    override suspend fun journalStatus(source: LocalHealthJournalSource): LocalHealthJournalStatus {
        reads++
        readFailure?.let { throw LocalHealthJournalException(it) }
        return status.getValue(source)
    }

    override suspend fun setJournalEnabled(
        source: LocalHealthJournalSource,
        enabled: Boolean,
    ): LocalHealthJournalStatus {
        writes += source to enabled
        writeFailure?.let { throw LocalHealthJournalException(it) }
        val updated = LocalHealthJournalStatus(source, enabled, enabled)
        status[source] = updated
        return updated
    }
}
