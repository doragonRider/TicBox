package dev.ticbox.companion.ui.toolbox

import android.content.ClipData
import android.content.ClipboardManager
import android.view.Window
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.center
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.core.view.WindowCompat
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.ticbox.companion.api.AdbToolboxClient
import dev.ticbox.companion.api.ToolboxBackendKind
import dev.ticbox.companion.api.ToolboxBackendState
import dev.ticbox.companion.api.ToolboxCapabilities
import dev.ticbox.companion.api.ToolboxCapability
import dev.ticbox.companion.api.ToolboxCommandOptions
import dev.ticbox.companion.api.ToolboxCommandSession
import dev.ticbox.companion.api.ToolboxException
import dev.ticbox.companion.api.ToolboxPhase
import dev.ticbox.companion.api.ToolboxTarget
import dev.ticbox.companion.api.ToolboxTerminalEvent
import dev.ticbox.companion.api.ToolboxTerminalOptions
import dev.ticbox.companion.api.ToolboxTerminalSession
import dev.ticbox.companion.api.ToolboxTerminalSize
import dev.ticbox.companion.api.WatchToolboxBackend
import dev.ticbox.companion.api.WatchToolboxClient
import dev.ticbox.companion.ui.CompanionApp
import dev.ticbox.companion.ui.FakeSessions
import dev.ticbox.companion.ui.FakeTerminalDisplays
import dev.ticbox.companion.ui.NoSystemActions
import dev.ticbox.companion.ui.SystemActions
import dev.ticbox.companion.ui.TestDependencies
import dev.ticbox.companion.ui.demoWatch
import dev.ticbox.companion.ui.navigation.TerminalRoute
import dev.ticbox.companion.ui.session
import dev.ticbox.companion.ui.session.SessionState
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

private const val RESET = "\u001bc"
private const val STILL_CURSOR = "\u001b[?12l"

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h2000dp")
class TerminalScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()

    private val adb = ShellAdb()
    private val displays = FakeTerminalDisplays()
    private var kept = 0
    private lateinit var window: Window
    private val actions =
        object : SystemActions by NoSystemActions {
            override fun keepShellRunning() {
                kept++
            }
        }
    private lateinit var dependencies: TestDependencies
    private lateinit var back: OnBackPressedDispatcher
    private val display
        get() = displays.created.single()

    private val shell
        get() = adb.terminals.last()

    private fun open(): NavHostController {
        val toolbox =
            object : WatchToolboxClient {
                override val adb = this@TerminalScreenTest.adb
                override val localBridge: WatchToolboxBackend =
                    WatchToolboxClient.UNAVAILABLE.localBridge
            }
        val session = demoWatch(context).session(key = "demo", toolbox = toolbox)
        dependencies =
            TestDependencies(FakeSessions(SessionState.Ready(session)), terminalDisplays = displays)
        lateinit var navController: NavHostController
        compose.setContent {
            navController = rememberNavController()
            back = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            window = checkNotNull(LocalActivity.current).window
            CompanionApp(dependencies, actions, navController)
        }
        compose.waitForIdle()
        visit(navController)
        return navController
    }

    private fun visit(nav: NavHostController) {
        compose.runOnUiThread { nav.navigate(TerminalRoute) }
        compose.waitForIdle()
    }

    private fun leave(nav: NavHostController) {
        compose.runOnUiThread { nav.popBackStack() }
        compose.waitForIdle()
    }

    private fun openReady(): NavHostController {
        val nav = open()
        until { adb.terminals.isNotEmpty() }
        key("ESC").assertIsEnabled()
        return nav
    }

    /** Reading the semantics is what pumps the main looper while waiting. */
    private fun until(condition: () -> Boolean) {
        compose.waitUntil(5_000) {
            compose.onAllNodes(isRoot()).fetchSemanticsNodes()
            condition()
        }
        compose.waitForIdle()
    }

    private fun untilShown(text: String) = until {
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun key(label: String) = compose.onNodeWithText(label)

    private fun described(label: String) = compose.onNodeWithContentDescription(label)

    @Test
    fun theShellIsReadyWhenItHasStartedEvenIfItPrintsNothing() {
        val starting = CompletableDeferred<Unit>()
        adb.starting = starting
        open()
        until { adb.opened.isNotEmpty() }
        assertEquals(displays.panelSize, adb.opened.single().size)
        compose.onNodeWithText("Starting a shell on the watch…").assertIsDisplayed()
        key("ESC").assertIsNotEnabled()

        starting.complete(Unit)
        until { adb.terminals.isNotEmpty() }
        key("ESC").assertIsEnabled()
        assertEquals(
            0,
            compose
                .onAllNodes(hasText("Starting a shell on the watch…"))
                .fetchSemanticsNodes()
                .size,
        )
    }

    @Test
    fun outputReachesTheEmulatorUntouchedAcrossSplitCharacters() {
        openReady()
        val text = "\u001b[31m好\r\n".toByteArray()
        val split = text.size - 3 // Inside the three bytes of 好.
        shell.print(text.copyOfRange(0, split))
        shell.print(text.copyOfRange(split, text.size))
        until { display.shown.length >= RESET.length + 8 }
        assertEquals(RESET + "\u001b[31m好\r\n", display.shown)
    }

    @Test
    fun whatIsTypedReachesTheShellInOrderWithNothingAddedOrEchoed() {
        openReady()
        val typed = (1..50).map { "$it" } + "\r"
        typed.forEach(display::keyboardSends)
        until { shell.written.size == typed.size }
        assertEquals(typed, shell.written.map { it.decodeToString() })
        assertEquals(RESET, display.shown)
    }

    @Test
    fun theKeysGoThroughTheEmulatorSoThatItEncodesThem() {
        openReady()
        key("ESC").performClick()
        key("PGDN").performClick()
        described("Left").performClick()
        described("Tab").performClick()
        compose.waitForIdle()
        assertEquals(
            listOf(
                TerminalKey.ESCAPE,
                TerminalKey.PAGE_DOWN,
                TerminalKey.LEFT,
                TerminalKey.TAB,
            ),
            display.pressed,
        )
        key("/").performClick()
        key("―").performClick()
        compose.waitForIdle()
        assertEquals(listOf('/', '-'), display.characters)
    }

    @Test
    fun holdingAnArrowRepeatsItWhereATapIsOnePress() {
        openReady()
        described("Left").performClick()
        compose.waitForIdle()
        assertEquals(listOf(TerminalKey.LEFT), display.pressed)

        described("Up").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(2_000)
        described("Up").performTouchInput { up() }
        compose.waitForIdle()
        val held = display.pressed.count { it == TerminalKey.UP }
        assertTrue("Up was pressed $held times", held > 2)
    }

    @Test
    fun aTapPutsCtrlOnForTheNextKeyAndHoldingKeepsItOn() {
        openReady()
        key("CTRL").performClick()
        compose.waitForIdle()
        assertEquals(StickyKey.NEXT, display.ctrl)
        key("CTRL").assertIsSelected()

        key("CTRL").performClick()
        compose.waitForIdle()
        assertEquals(StickyKey.OFF, display.ctrl)
        key("CTRL").assertIsNotSelected()

        key("CTRL").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(StickyKey.LOCKED, display.ctrl)
        key("CTRL").performClick()
        compose.waitForIdle()
        assertEquals(StickyKey.OFF, display.ctrl)

        key("ALT").performClick()
        compose.waitForIdle()
        assertEquals(StickyKey.NEXT, display.alt)
    }

    @Test
    fun aNewShellStartsWithTheStickyKeysOff() {
        openReady()
        key("CTRL").performTouchInput { longClick() }
        key("ALT").performClick()
        compose.waitForIdle()
        shell.exit(0)
        untilShown("Exited with code 0.")
        compose.onNodeWithText("New shell").performClick()
        until { adb.terminals.size == 2 }
        assertEquals(StickyKey.OFF, display.ctrl)
        assertEquals(StickyKey.OFF, display.alt)
    }

    @Test
    fun theTextRowSendsWhatIsWrittenAndAnEmptyRowSendsEnter() {
        openReady()
        key("ESC").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        val row = compose.onNode(hasSetTextAction())
        row.performTextInput("中文")
        row.performImeAction()
        compose.waitForIdle()
        assertEquals(listOf("中文"), display.typed)
        assertEquals(emptyList<TerminalKey>(), display.pressed)

        row.performImeAction()
        compose.waitForIdle()
        assertEquals(listOf("中文"), display.typed)
        assertEquals(listOf(TerminalKey.ENTER), display.pressed)

        row.performTouchInput { swipeRight() }
        compose.waitForIdle()
        key("ESC").assertIsDisplayed()
    }

    @Test
    fun theTextRowWaitsForTheShellToo() {
        adb.starting = CompletableDeferred()
        open()
        until { adb.opened.isNotEmpty() }
        key("ESC").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        described("Text to send to the shell").assertExists()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test
    fun pasteReadsTheClipboardOnlyWhenAskedAndGivesItToTheEmulator() {
        openReady()
        context
            .getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(null, "echo hi"))
        compose.waitForIdle()
        assertEquals(emptyList<String>(), display.pasted)
        compose.runOnUiThread { display.requestPaste() }
        assertEquals(listOf("echo hi"), display.pasted)
    }

    @Test
    fun aNewSizeReachesTheShellButTheOneItOpenedAtDoesnt() {
        openReady()
        assertEquals(emptyList<ToolboxTerminalSize>(), shell.resized)

        val pixelsOnly = displays.panelSize.copy(heightPixels = 999)
        compose.runOnUiThread { display.reportSize(pixelsOnly) }
        compose.waitForIdle()
        assertEquals(emptyList<ToolboxTerminalSize>(), shell.resized)

        val smaller = ToolboxTerminalSize(rows = 12, columns = 50)
        compose.runOnUiThread { display.reportSize(smaller) }
        until { shell.resized.isNotEmpty() }
        assertEquals(listOf(smaller), shell.resized.toList())
    }

    @Test
    fun anExitIsReportedAndOnlyAskingOpensAnotherWithoutWhatWasTypedBefore() {
        openReady()
        display.keyboardSends("left over")
        until { shell.written.size == 1 }

        shell.exit(3)
        untilShown("Exited with code 3.")
        key("ESC").assertIsNotEnabled()
        assertEquals(1, adb.opened.size)

        compose.onNodeWithText("New shell").performClick()
        until { adb.terminals.size == 2 }
        assertEquals(emptyList<ByteArray>(), shell.written.toList())
        assertEquals(RESET + STILL_CURSOR + RESET, display.shown)
        assertEquals("Only the one emulator", 1, displays.created.size)
    }

    @Test
    fun lostOutputIsSaidEvenWhenTheShellExitedCleanly() {
        openReady()
        shell.exit(0, outputTruncated = true)
        untilShown("Exited with code 0. Some of its last output may be missing.")
    }

    @Test
    fun stoppingEndsTheShellWithoutCallingItAnExit() {
        openReady()
        // Slow enough for the main thread to be waiting on it, which is what can lose the ending.
        display.showMillis = 200
        compose.onNodeWithContentDescription("Stop shell").performClick()
        until { shell.closed }
        untilShown("Stopped.")
        assertEquals(RESET + STILL_CURSOR, display.shown)
    }

    @Test
    fun theSystemBarIconsStayLightOverThePageAndComeBackAfterIt() {
        val nav = open()
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        until { !bars.isAppearanceLightStatusBars }
        assertEquals(false, bars.isAppearanceLightNavigationBars)
        leave(nav)
        until { bars.isAppearanceLightStatusBars }
        assertEquals(true, bars.isAppearanceLightNavigationBars)
    }

    @Test
    fun aLostConnectionEndsTheShellAndComingBackDoesntOpenAnother() {
        openReady()
        val first = shell
        val connected = adb.state.value
        adb.state.value = ToolboxBackendState(ToolboxBackendKind.ADB)
        until { first.closed }
        untilShown("The connection ended, and this shell with it.")

        adb.state.value = connected
        untilShown("New shell")
        assertEquals(1, adb.opened.size)
    }

    @Test
    fun noTerminalOnTheDeviceSaysSoAndNeverBecomesAPipe() {
        adb.refusal = ToolboxException("terminal_unavailable")
        open()
        untilShown("The terminal component didn't load on this device, so no shell was started.")
        compose.onNodeWithText("New shell").assertIsDisplayed()
        assertEquals(0, adb.pipes)
    }

    @Test
    fun aBrokenConnectionIsNotAnExit() {
        openReady()
        shell.fail(ToolboxException("terminal_closed"))
        untilShown("The connection ended, and this shell with it.")
        assertEquals(
            0,
            compose.onAllNodes(hasText("Exited", substring = true)).fetchSemanticsNodes().size,
        )
    }

    @Test
    fun leavingThePageLeavesTheShellRunningAndComingBackFindsIt() {
        val nav = openReady()
        val first = shell
        leave(nav)
        first.print("while away".toByteArray())
        until { "while away" in display.shown }
        assertFalse(first.closed)
        assertFalse(display.closed)

        visit(nav)
        key("ESC").assertIsEnabled()
        assertEquals(1, adb.opened.size)
        display.keyboardSends("x")
        until { first.written.size == 1 }
    }

    @Test
    fun aShellThatEndedWhileAwayIsToldOnReturnAndTheVisitAfterThatStartsAnew() {
        val nav = openReady()
        val first = display
        leave(nav)
        shell.exit(5)
        until { dependencies.shell.state.value.phase == ShellPhase.ENDED }
        visit(nav)
        untilShown("Exited with code 5.")
        assertEquals(1, adb.opened.size)

        leave(nav)
        until { first.closed }
        visit(nav)
        until { adb.terminals.size == 2 }
        assertEquals(2, displays.created.size)
    }

    @Test
    fun endingItFromOutsideThePageLeavesNothingForTheNextVisit() {
        val nav = openReady()
        val first = shell
        val firstDisplay = display
        leave(nav)
        dependencies.shell.stop()
        until { first.closed && firstDisplay.closed }
        visit(nav)
        until { adb.terminals.size == 2 }
    }

    @Test
    fun backOnTheFirstPageAsksAndKeepingItLeavesTheShellRunning() {
        val nav = openReady()
        val first = shell
        leave(nav)
        compose.runOnUiThread { back.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("Keep in background").performClick()
        compose.waitForIdle()
        assertEquals(1, kept)
        assertFalse(first.closed)
    }

    @Test
    fun backOnTheFirstPageCanEndTheShell() {
        val nav = openReady()
        val first = shell
        leave(nav)
        compose.runOnUiThread { back.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("End shell").performClick()
        until { first.closed }
        assertEquals(0, kept)
    }

    @Test
    fun backIsLeftAloneWithoutARunningShell() {
        val nav = openReady()
        shell.exit(0)
        untilShown("Exited with code 0.")
        leave(nav)
        assertFalse(back.hasEnabledCallbacks())
    }

    @Test
    fun theNotificationCanAskForTheTerminalFromAnotherPage() {
        val nav = openReady()
        leave(nav)
        dependencies.shell.show()
        until { nav.currentDestination?.hasRoute<TerminalRoute>() == true }
    }
}

private class FakeTerminal(override val target: ToolboxTarget) : ToolboxTerminalSession {
    private val output = Channel<ToolboxTerminalEvent>(Channel.UNLIMITED)
    val written = CopyOnWriteArrayList<ByteArray>()
    val resized = CopyOnWriteArrayList<ToolboxTerminalSize>()
    @Volatile var closed = false

    fun print(bytes: ByteArray) {
        output.trySend(ToolboxTerminalEvent.Output(bytes))
    }

    fun exit(code: Int, outputTruncated: Boolean = false) {
        output.trySend(ToolboxTerminalEvent.Exited(code, outputTruncated))
        output.close()
    }

    fun fail(problem: Exception) {
        output.close(problem)
    }

    override val events: Flow<ToolboxTerminalEvent> = flow {
        for (event in output) emit(event)
    }

    override suspend fun write(bytes: ByteArray) {
        written += bytes
    }

    override suspend fun resize(size: ToolboxTerminalSize) {
        resized += size
    }

    override suspend fun awaitExit() = ToolboxTerminalEvent.Exited(0)

    override fun close() {
        closed = true
        output.close()
    }
}

private class ShellAdb : AdbToolboxClient by WatchToolboxClient.UNAVAILABLE.adb {
    override fun confirmDevice(target: ToolboxTarget) =
        super<AdbToolboxClient>.confirmDevice(target)

    override fun bind(target: ToolboxTarget): WatchToolboxBackend =
        super<AdbToolboxClient>.bind(target)

    private val watch = ToolboxTarget("watch", 1, ToolboxBackendKind.ADB)

    override val state =
        MutableStateFlow(
            ToolboxBackendState(
                ToolboxBackendKind.ADB,
                ToolboxPhase.CONNECTED,
                device = "watch",
                target = watch,
                capabilities =
                    ToolboxCapabilities(
                        uid = 2000,
                        sdk = 33,
                        features = setOf(ToolboxCapability.TERMINAL),
                    ),
            )
        )

    var starting: CompletableDeferred<Unit>? = null
    var refusal: ToolboxException? = null
    var pipes = 0
    val opened = CopyOnWriteArrayList<ToolboxTerminalOptions>()
    val terminals = CopyOnWriteArrayList<FakeTerminal>()

    override suspend fun openTerminal(options: ToolboxTerminalOptions): ToolboxTerminalSession {
        opened += options
        refusal?.let { throw it }
        starting?.await()
        return FakeTerminal(watch).also { terminals += it }
    }

    override suspend fun openCommand(
        command: String,
        options: ToolboxCommandOptions,
    ): ToolboxCommandSession {
        pipes++
        throw ToolboxException("unexpected_pipe")
    }
}
