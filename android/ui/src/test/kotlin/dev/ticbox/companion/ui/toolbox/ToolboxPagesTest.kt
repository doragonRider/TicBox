package dev.ticbox.companion.ui.toolbox

import android.content.ContentProvider
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsEqualTo
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.ticbox.companion.api.AdbDiscoveredEndpoint
import dev.ticbox.companion.api.AdbEndpoint
import dev.ticbox.companion.api.AdbPairingStatus
import dev.ticbox.companion.api.AdbToolboxClient
import dev.ticbox.companion.api.DndSyncClient
import dev.ticbox.companion.api.DndSyncMode
import dev.ticbox.companion.api.DndSyncState
import dev.ticbox.companion.api.ToolboxApk
import dev.ticbox.companion.api.ToolboxApp
import dev.ticbox.companion.api.ToolboxBackendKind
import dev.ticbox.companion.api.ToolboxBackendState
import dev.ticbox.companion.api.ToolboxBundle
import dev.ticbox.companion.api.ToolboxBundleData
import dev.ticbox.companion.api.ToolboxBundleResult
import dev.ticbox.companion.api.ToolboxCapabilities
import dev.ticbox.companion.api.ToolboxCapability
import dev.ticbox.companion.api.ToolboxCommandOptions
import dev.ticbox.companion.api.ToolboxCommandResult
import dev.ticbox.companion.api.ToolboxException
import dev.ticbox.companion.api.ToolboxFile
import dev.ticbox.companion.api.ToolboxFileKind
import dev.ticbox.companion.api.ToolboxInstallOptions
import dev.ticbox.companion.api.ToolboxInstallResult
import dev.ticbox.companion.api.ToolboxPhase
import dev.ticbox.companion.api.ToolboxProgress
import dev.ticbox.companion.api.ToolboxTarget
import dev.ticbox.companion.api.WatchToolboxBackend
import dev.ticbox.companion.api.WatchToolboxClient
import dev.ticbox.companion.ui.CompanionApp
import dev.ticbox.companion.ui.FakeSessions
import dev.ticbox.companion.ui.NoSystemActions
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.TestDependencies
import dev.ticbox.companion.ui.demoWatch
import dev.ticbox.companion.ui.designsystem.InlineValueRow
import dev.ticbox.companion.ui.navigation.AdbConnectRoute
import dev.ticbox.companion.ui.navigation.ApkInstallerRoute
import dev.ticbox.companion.ui.navigation.AppManagerRoute
import dev.ticbox.companion.ui.navigation.ClipboardRoute
import dev.ticbox.companion.ui.navigation.FileManagerRoute
import dev.ticbox.companion.ui.navigation.HealthOverviewRoute
import dev.ticbox.companion.ui.navigation.LocalBridgeRoute
import dev.ticbox.companion.ui.navigation.RemoteScreenRoute
import dev.ticbox.companion.ui.navigation.SplitInstallerRoute
import dev.ticbox.companion.ui.navigation.TerminalRoute
import dev.ticbox.companion.ui.navigation.ToolboxRoute
import dev.ticbox.companion.ui.navigation.WatchPreferencesRoute
import dev.ticbox.companion.ui.session
import dev.ticbox.companion.ui.session.SessionState
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h2000dp")
class ToolboxPagesTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()

    private fun show(
        toolbox: WatchToolboxClient = WatchToolboxClient.UNAVAILABLE,
        key: String = "demo",
        dnd: DndSyncClient = DndSyncClient.UNAVAILABLE,
        picked: List<Uri> = emptyList(),
    ): NavHostController {
        val session = demoWatch(context).session(key = key, toolbox = toolbox, dnd = dnd)
        lateinit var navController: NavHostController
        compose.setContent {
            navController = rememberNavController()
            val documents = remember { PickedDocuments(picked) }
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides documents) {
                CompanionApp(
                    TestDependencies(FakeSessions(SessionState.Ready(session))),
                    NoSystemActions,
                    navController,
                )
            }
        }
        compose.waitForIdle()
        return navController
    }

    private fun NavHostController.open(route: Any) {
        compose.runOnUiThread { navigate(route) }
        compose.waitForIdle()
    }

    private fun text(value: String) = compose.onNodeWithText(value)

    private fun connectedAdb(backend: AdbToolboxClient): WatchToolboxClient =
        object : WatchToolboxClient {
            override val adb = backend
            override val localBridge: WatchToolboxBackend =
                WatchToolboxClient.UNAVAILABLE.localBridge
        }

    /** Every tool's first control, to tell grey from usable without a screen of its own. */
    private fun NavHostController.assertEveryToolIs(enabled: Boolean) {
        fun check(route: Any, control: () -> SemanticsNodeInteraction) {
            open(route)
            if (enabled) control().assertIsEnabled() else control().assertIsNotEnabled()
            compose.runOnUiThread { popBackStack() }
            compose.waitForIdle()
        }
        check(ApkInstallerRoute) { text("Choose files") }
        check(SplitInstallerRoute) { text("Choose files") }
        check(ClipboardRoute) { text("Read Clipboard") }
        check(RemoteScreenRoute) { text("Start") }
        check(FileManagerRoute) { compose.onNodeWithContentDescription("Sort") }
        check(AppManagerRoute) { text("User apps") }
    }

    @Test
    fun wirelessDiscoveryLabelsAuthenticationRatherThanAssumingEveryEndpointIsPaired() {
        val endpoints =
            listOf(
                AdbDiscoveredEndpoint(
                    "Paired Pixel",
                    AdbEndpoint("192.0.2.1", 37001),
                    false,
                    AdbPairingStatus.PAIRED,
                ),
                AdbDiscoveredEndpoint(
                    "New Pixel",
                    AdbEndpoint("192.0.2.2", 37002),
                    false,
                    AdbPairingStatus.REQUIRED,
                ),
                AdbDiscoveredEndpoint("Offline Pixel", AdbEndpoint("192.0.2.3", 37003), false),
                AdbDiscoveredEndpoint("Pairing Pixel", AdbEndpoint("192.0.2.2", 37004), true),
            )
        val nav = show(toolbox = connectedAdb(DiscoveryAdb(endpoints, "adb_pairing_required")))
        nav.open(AdbConnectRoute)
        text("Ready to connect · 192.0.2.1:37001").performScrollTo().assertIsDisplayed()
        text("Ready to connect · Not paired with this app · 192.0.2.2:37002")
            .performScrollTo()
            .assertIsDisplayed()
        text("Found · Pairing status unknown · 192.0.2.3:37003")
            .performScrollTo()
            .assertIsDisplayed()
        text("Ready to pair · 192.0.2.2:37004").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun revokedPairingUpdatesPreviouslyReadyRowAndShowsThePairingInstruction() {
        val endpoint =
            AdbDiscoveredEndpoint(
                "Pixel",
                AdbEndpoint("192.0.2.1", 37001),
                false,
                AdbPairingStatus.PAIRED,
            )
        val nav =
            show(toolbox = connectedAdb(DiscoveryAdb(listOf(endpoint), "adb_pairing_required")))
        nav.open(AdbConnectRoute)
        text("Ready to connect · 192.0.2.1:37001").performScrollTo().performClick()
        text(context.getString(R.string.toolbox_error_adb_pairing_required)).assertIsDisplayed()
        text("Ready to connect · Not paired with this app · 192.0.2.1:37001").assertIsDisplayed()
    }

    @Test
    fun networkFailureDoesNotTurnUnknownPairingIntoNotPaired() {
        val endpoint = AdbDiscoveredEndpoint("Pixel", AdbEndpoint("192.0.2.1", 37001), false)
        val nav = show(toolbox = connectedAdb(DiscoveryAdb(listOf(endpoint), "connection_failed")))
        nav.open(AdbConnectRoute)
        text("Found · Pairing status unknown · 192.0.2.1:37001").performScrollTo().performClick()
        text(context.getString(R.string.toolbox_error_adb_connect)).assertIsDisplayed()
        text("Found · Pairing status unknown · 192.0.2.1:37001").assertIsDisplayed()
    }

    @Test
    fun arrivingTriesTheLastDeviceStraightAwayAndSaysNothingWhenItIsNotThere() {
        val adb = LastDeviceAdb(emptyList(), reconnectFailure = "connection_failed")
        val nav = show(toolbox = connectedAdb(adb))
        nav.open(AdbConnectRoute)
        waitFor("Nothing yet")
        compose.waitForIdle()
        assertEquals(1, adb.reconnects)
        text(context.getString(R.string.toolbox_error_adb_connect)).assertDoesNotExist()
    }

    @Test
    fun theLastDeviceFoundPairedConnectsByItselfButNotAgainAfterDisconnecting() {
        val last =
            AdbDiscoveredEndpoint(
                "adb-watch",
                AdbEndpoint("192.0.2.9", 41001),
                false,
                AdbPairingStatus.PAIRED,
                autoConnect = true,
            )
        val other =
            AdbDiscoveredEndpoint(
                "adb-phone",
                AdbEndpoint("192.0.2.1", 37001),
                false,
                AdbPairingStatus.PAIRED,
            )
        val adb = LastDeviceAdb(listOf(other, last))
        val nav = show(toolbox = connectedAdb(adb))
        nav.open(AdbConnectRoute)
        waitFor("Connected to 192.0.2.9:41001")
        assertEquals(listOf(last.endpoint), adb.connects)
        text("Disconnect").performClick()
        compose.waitForIdle()
        assertTrue(adb.stayDisconnected)
        assertEquals(2, adb.searches)
        assertEquals(listOf(last.endpoint), adb.connects)
        // Searching again still leaves it to the person.
        compose.onNodeWithContentDescription("Search again").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(3, adb.searches)
        assertEquals(listOf(last.endpoint), adb.connects)
    }

    @Test
    fun withoutAConnectionToolsAreGreyButOpenAndTheirControlsAreToo() {
        val nav = show()
        nav.open(ToolboxRoute)
        text("These tools need a wireless debugging connection, or Local Bridge with root.")
            .performScrollTo()
        val card = text("Clipboard").performScrollTo()
        card.assertIsEnabled()
        card.performClick()
        compose.waitForIdle()
        assertTrue(nav.currentDestination!!.hasRoute(ClipboardRoute::class))
        text("Read Clipboard").assertIsNotEnabled()
        text("Set Clipboard").assertIsNotEnabled()
        text("Paste").assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.runOnUiThread { nav.popBackStack() }
        nav.assertEveryToolIs(enabled = false)
        nav.open(TerminalRoute)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        text("ESC").assertIsNotEnabled()
    }

    @Test
    fun aLongPathShowsItsEnd() {
        // Eight crumbs at the 48 dp of a touch target are too wide for the page, whatever the font.
        val folders = listOf("Android", "data", "com.google.android.dialer", "files", "a", "b")
        val adb = FakeAdb()
        var parent = INTERNAL_STORAGE
        folders.forEach { name ->
            adb.folders[parent] =
                ToolboxFile("$parent/$name", name, ToolboxFileKind.DIRECTORY, 0, 0, 0, 0, 0)
            parent += "/$name"
        }
        val nav = show(connectedAdb(adb))
        nav.open(ToolboxRoute)
        nav.open(FileManagerRoute)
        folders.forEach { name ->
            compose.onNodeWithText(name).performClick()
            compose.waitForIdle()
        }
        val end = compose.onNodeWithText(folders.last()).getUnclippedBoundsInRoot()
        val page = compose.onRoot().getBoundsInRoot()
        assertTrue(end.right <= page.right, "the last folder is cut off at ${end.right}")
        compose.onNodeWithText(folders.last()).assertIsDisplayed()
        compose.onNodeWithText("/").assertIsNotDisplayed()
    }

    @Test
    fun pullingAFolderDownReadsItAgain() {
        val adb = FakeAdb()
        val nav = show(connectedAdb(adb))
        nav.open(ToolboxRoute)
        nav.open(FileManagerRoute)
        compose.waitForIdle()
        assertEquals(1, adb.listings)
        compose.onRoot().performTouchInput { swipeDown(startY = height / 3f, endY = bottom) }
        compose.waitForIdle()
        assertEquals(2, adb.listings)
    }

    @Test
    fun aRootBridgeTooOldForToolsLeadsToWhereItUpdates() {
        val root =
            object : WatchToolboxBackend by WatchToolboxClient.UNAVAILABLE.localBridge {
                override val state =
                    MutableStateFlow(
                        ToolboxBackendState(
                            ToolboxBackendKind.ROOT_BRIDGE,
                            ToolboxPhase.FAILED,
                            error = "agent_version_mismatch",
                        )
                    )
            }
        val nav =
            show(
                object : WatchToolboxClient {
                    override val adb = WatchToolboxClient.UNAVAILABLE.adb
                    override val localBridge = root
                }
            )
        nav.open(ToolboxRoute)
        text("Local Bridge with root").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(nav.currentDestination!!.hasRoute(LocalBridgeRoute::class))
        text("Update").performScrollTo().assertIsEnabled()
    }

    @Test
    fun aLongValueLeavesItsTitleOnOneLine() {
        val long = "com.example.".repeat(40)
        compose.setContent {
            Column(Modifier.width(280.dp)) {
                InlineValueRow("Data folder", long)
                InlineValueRow("APK", "short")
            }
        }
        compose.waitForIdle()
        fun bounds(value: String) =
            compose.onNodeWithText(value, useUnmergedTree = true).getBoundsInRoot()
        val title = bounds("Data folder")
        val oneLine = bounds("APK")
        assertEquals(oneLine.bottom - oneLine.top, title.bottom - title.top)
        assertTrue(bounds(long).top >= title.bottom)
    }

    @Test
    fun theNoticeOnAToolLeadsBackToWhereBackendsAreSetUp() {
        val nav = show()
        nav.open(ToolboxRoute)
        nav.open(ClipboardRoute)
        val notice = text("This tool needs a backend").assertIsDisplayed().getBoundsInRoot()
        val demo = compose.onNodeWithTag("demo_banner").assertIsDisplayed().getBoundsInRoot()
        assertTrue(notice.bottom <= demo.top)
        text("Set up").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertTrue(nav.currentDestination!!.hasRoute(ToolboxRoute::class))
    }

    @Test
    fun aDifferentDeviceStillShowsAWarningInEveryToolsBottomNotice() {
        val adb = FakeAdb()
        val nav = show(connectedAdb(adb))
        nav.open(ToolboxRoute)
        compose.runOnUiThread {
            adb.state.value =
                adb.state.value.copy(
                    target = ToolboxTarget("another-watch", 2, ToolboxBackendKind.ADB)
                )
        }
        compose.waitForIdle()
        val warning = context.getString(R.string.toolbox_device_changed)
        text(warning).assertIsDisplayed()
        for (route in
            listOf(
                ApkInstallerRoute,
                SplitInstallerRoute,
                ClipboardRoute,
                TerminalRoute,
                RemoteScreenRoute,
                FileManagerRoute,
                AppManagerRoute,
            )) {
            nav.open(route)
            text(warning).assertIsDisplayed()
            text("Set up").assertIsEnabled().performClick()
            compose.waitForIdle()
            assertTrue(nav.currentDestination!!.hasRoute(ToolboxRoute::class))
        }
        nav.open(ClipboardRoute)
        text("Set Clipboard").assertIsNotEnabled()
        text("Paste").assertIsNotEnabled()
    }

    @Test
    fun clipboardSetsWhatIsTypedAndPastePressesTheWatchsPasteKey() {
        val adb = FakeAdb()
        val nav = show(connectedAdb(adb))
        nav.open(ClipboardRoute)
        compose.onNode(hasSetTextAction()).performTextInput("hello")
        text("Set Clipboard").performClick()
        compose.waitForIdle()
        assertEquals(listOf("hello"), adb.clipboardWrites)
        assertTrue(adb.commands.isEmpty())
        text("Paste").performClick()
        compose.waitForIdle()
        assertEquals(listOf("input keyevent KEYCODE_PASTE"), adb.commands)
        assertEquals(listOf("hello"), adb.clipboardWrites)
    }

    @Test
    @Config(qualifiers = "en-rUS-w411dp-h640dp")
    fun theMirrorStaysPutWhileTheControlsBelowItScroll() {
        val nav = show(connectedAdb(FakeAdb()))
        nav.open(RemoteScreenRoute)
        val mirrorBefore = text("Start").getBoundsInRoot()
        val keyBefore = text("Back").getBoundsInRoot()
        text("Presets").performScrollTo()
        compose.waitForIdle()
        assertEquals(mirrorBefore, text("Start").getBoundsInRoot())
        assertTrue(text("Back").getBoundsInRoot().top < keyBefore.top)
    }

    @Test
    fun aConnectedBackendWakesTheToolsAndReadsItsAnswer() {
        val nav = show(connectedAdb(FakeAdb()))
        nav.open(ToolboxRoute)
        compose.onAllNodes(hasText("Not connected")).let {
            assertEquals(1, it.fetchSemanticsNodes().size) // Local Bridge's own row, nothing else.
        }
        nav.assertEveryToolIs(enabled = true)
        nav.open(ClipboardRoute)
        text("Read Clipboard").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("copied on the watch")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun rootOnlyActionsStayGreyOverWirelessDebugging() {
        val nav = show(connectedAdb(FakeAdb()))
        nav.open(AppManagerRoute)
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Sample")).fetchSemanticsNodes().isNotEmpty()
        }
        text("Sample").performClick()
        compose.waitForIdle()
        text("Force stop").assertIsEnabled()
        text("Clear cache").assertIsNotEnabled()
        text("Back up private data").assertIsNotEnabled()
    }

    @Test
    fun followingThePhoneIsAskedOfTheService() {
        val dnd = FakeDnd()
        val nav = show(dnd = dnd)
        nav.open(WatchPreferencesRoute)
        text("Follow the phone").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(DndSyncMode.FOLLOW_PHONE), dnd.modes)
        text("Follow the phone").assertIsSelected()
    }

    @Test
    fun aStepsMarkSitsInTheMiddleOfItsBadge() {
        var done by mutableStateOf(false)
        compose.setContent { StepCard(3, "Pair", Modifier.testTag("card"), done = done) }
        compose.waitForIdle()
        val card = compose.onNodeWithTag("card").getBoundsInRoot()
        fun assertCentred(mark: SemanticsNodeInteraction) {
            val bounds = mark.getBoundsInRoot()
            assertEquals(30f, ((bounds.left + bounds.right) / 2 - card.left).value, 0.5f)
            assertEquals(30f, ((bounds.top + bounds.bottom) / 2 - card.top).value, 0.5f)
        }
        assertCentred(text("3"))
        compose.runOnIdle { done = true }
        compose.waitForIdle()
        assertCentred(compose.onNodeWithContentDescription("Done"))
    }

    @Test
    fun remoteControlsScrollBelowTheGapUnderTheMirror() {
        val nav = show(connectedAdb(FakeAdb()))
        nav.open(RemoteScreenRoute)
        val pane =
            compose
                .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                .getBoundsInRoot()
        text("Back").getBoundsInRoot().top.assertIsEqualTo(pane.top, "first control")
    }

    @Test
    fun theWatchCanPickFromAllItsLanguagesItself() {
        val nav = show()
        nav.open(WatchPreferencesRoute)
        text("More languages on watch").performScrollTo().assertIsEnabled().performClick()
        waitFor("Opened on your watch")
    }

    @Test
    fun wirelessDebuggingOnlySaysNothingWasFoundOnceASearchHasAnswered() {
        val adb = FakeAdb()
        val search = CompletableDeferred<List<AdbDiscoveredEndpoint>>()
        adb.search = search
        adb.state.value = adb.state.value.copy(phase = ToolboxPhase.DISCONNECTED)
        val nav = show(connectedAdb(adb))
        nav.open(AdbConnectRoute)
        compose.onNode(hasText("Nothing yet", substring = true)).assertDoesNotExist()

        search.complete(emptyList())
        waitFor("Nothing yet")

        adb.search =
            CompletableDeferred(
                listOf(AdbDiscoveredEndpoint("My watch", AdbEndpoint("192.0.2.1", 5555), false))
            )
        compose.onNodeWithContentDescription("Search again").performClick()
        waitFor("My watch")
        compose.waitForIdle()
        compose.onNode(hasText("Nothing yet", substring = true)).assertDoesNotExist()
    }

    private fun pick(adb: FakeAdb, vararg names: String): NavHostController {
        Robolectric.setupContentProvider(PickableFiles::class.java, FILES_AUTHORITY)
        val uris = names.map { Uri.parse("content://$FILES_AUTHORITY/$it") }
        val nav = show(connectedAdb(adb), picked = uris)
        nav.open(ApkInstallerRoute)
        text("Choose files").performClick()
        return nav
    }

    private fun waitFor(value: String) =
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(value, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }

    @Test
    fun aPackageFileShowsWhatItHoldsAndIsInstalledWholeWithoutItsRefusedNeighbour() {
        val adb = FakeAdb()
        val game = gameBundle()
        adb.bundles["game.xapk"] = game
        adb.refused["old.apkm"] = "package_encrypted"
        pick(adb, "game.xapk", "old.apkm")
        waitFor("A Game")
        waitFor("old.apkm wasn't installed")
        text(
            "This file is encrypted, as APKM files from before 2021 are. Try downloading it again."
        )
        compose.onNode(hasText("2 of 5 APKs for this watch", substring = true)).assertExists()
        compose.onNode(hasText("of data to copy", substring = true)).assertExists()
        text("Install").performScrollTo().performClick()
        waitFor("A Game is installed.")
        assertEquals(listOf(game), adb.installedBundles)
        text("Choose files").assertIsEnabled()
    }

    @Test
    fun anAppWhoseDataDidntArriveSaysSoAndCanBeSentAgain() {
        val adb = FakeAdb()
        adb.bundles["game.xapk"] = gameBundle()
        adb.bundleResult =
            ToolboxBundleResult(ToolboxInstallResult(true, "Success"), "connection_closed")
        pick(adb, "game.xapk")
        waitFor("A Game")
        text("Install").performScrollTo().performClick()
        waitFor("A Game is installed without all its data")
        text(
            "The connection to the watch was lost. Install again to send the data. " +
                "Until then the app may not run."
        )
        text("Install").performScrollTo().assertIsEnabled()
        text("A Game").assertExists()
    }

    @Test
    fun whatInstalledLeavesTheListWhileWhatFailedStaysToTryAgain() {
        val adb = FakeAdb()
        adb.bundles["game.xapk"] = gameBundle()
        adb.bundleResult = ToolboxBundleResult(ToolboxInstallResult(false, "Failure [X]"))
        pick(adb, "plain.apk", "game.xapk")
        waitFor("A Game")
        text("Install").performScrollTo().performClick()
        waitFor("A Game wasn't installed")
        waitFor("plain.apk is installed.")
        text("plain.apk").assertDoesNotExist()
        text("A Game").assertExists()
    }

    @Test
    fun aPackageFileIsAnInstallOfItsOwnOnBothPages() {
        fun file(name: String) = PickedFile(Uri.parse("content://x/$name"), name, 1)
        val base = file("base.apk")
        val config = file("config.apk")
        val game = file("game.xapk")
        val bundles = mapOf(game.uri to gameBundle())
        val separate = installBatches(listOf(base, game, config), bundles, split = false)
        assertEquals(
            listOf(listOf(base), listOf(config), listOf(game)),
            separate.map { it.files },
        )
        val together = installBatches(listOf(base, game, config), bundles, split = true)
        assertEquals(listOf(listOf(base, config), listOf(game)), together.map { it.files })
        assertEquals(listOf("base.apk", "A Game"), together.map { it.name })
        assertEquals(1, installBatches(listOf(game), bundles, split = true).size)
    }

    @Test
    fun aPageThatNeedsTheBridgeLeadsToItsInstallPage() {
        val nav = show(key = "paired")
        nav.open(HealthOverviewRoute)
        text("Local Bridge isn't on the watch")
        text("Set up").performClick()
        compose.waitForIdle()
        assertTrue(nav.currentDestination!!.hasRoute(LocalBridgeRoute::class))
        text("Install").assertIsNotEnabled()
    }

    @Test
    fun theDemoDoesNotAskForTheBridge() {
        val nav = show()
        nav.open(HealthOverviewRoute)
        val banners = compose.onAllNodes(hasText("Set up")).fetchSemanticsNodes()
        assertEquals(0, banners.size)
    }
}

private const val FILES_AUTHORITY = "dev.ticbox.companion.test.files"

private fun gameBundle() =
    ToolboxBundle(
        "dev.example.game",
        "A Game",
        "1.0",
        listOf(
            ToolboxApk("base.apk", 100) { ByteArray(100).inputStream() },
            ToolboxApk("config.apk", 100) { ByteArray(100).inputStream() },
        ),
        skippedApks = 3,
        data =
            listOf(
                ToolboxBundleData("Android/obb/dev.example.game/main.1.dev.example.game.obb", 900) {
                    ByteArray(900).inputStream()
                }
            ),
    )

/** Answers the document picker with these files, as if the user chose them. */
private class PickedDocuments(private val uris: List<Uri>) : ActivityResultRegistryOwner {
    override val activityResultRegistry =
        object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                dispatchResult(requestCode, uris)
            }
        }
}

class PickableFiles : ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ) =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any>(uri.lastPathSegment.orEmpty(), 1_000L))
        }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0
}

private class DiscoveryAdb(
    private val endpoints: List<AdbDiscoveredEndpoint>,
    private val failure: String,
) : AdbToolboxClient by WatchToolboxClient.UNAVAILABLE.adb {
    override val state = MutableStateFlow(ToolboxBackendState(ToolboxBackendKind.ADB))

    override suspend fun discover() = endpoints

    override suspend fun connect(endpoint: AdbEndpoint) {
        state.value =
            ToolboxBackendState(ToolboxBackendKind.ADB, ToolboxPhase.FAILED, error = failure)
        throw ToolboxException(failure)
    }
}

/**
 * The last address no longer answers; [endpoints] is what the search then finds. Disconnecting by
 * choice clears [AdbDiscoveredEndpoint.autoConnect], as the Android client does.
 */
private class LastDeviceAdb(
    private val endpoints: List<AdbDiscoveredEndpoint>,
    private val reconnectFailure: String? = null,
) : AdbToolboxClient by WatchToolboxClient.UNAVAILABLE.adb {
    override val state = MutableStateFlow(ToolboxBackendState(ToolboxBackendKind.ADB))
    val connects = mutableListOf<AdbEndpoint>()
    var reconnects = 0
    var searches = 0
    var stayDisconnected = false

    override suspend fun reconnectLast(): Boolean {
        reconnects++
        reconnectFailure?.let { throw ToolboxException(it) }
        return false
    }

    override suspend fun discover() =
        endpoints
            .map { if (stayDisconnected) it.copy(autoConnect = false) else it }
            .also {
                searches++
            }

    override suspend fun connect(endpoint: AdbEndpoint) {
        connects += endpoint
        stayDisconnected = false
        state.value =
            ToolboxBackendState(
                ToolboxBackendKind.ADB,
                ToolboxPhase.CONNECTED,
                device = "${endpoint.host}:${endpoint.port}",
            )
    }

    override suspend fun disconnectByChoice() {
        stayDisconnected = true
        state.value = ToolboxBackendState(ToolboxBackendKind.ADB)
    }
}

private class FakeAdb : AdbToolboxClient by WatchToolboxClient.UNAVAILABLE.adb {
    override fun confirmDevice(target: ToolboxTarget) =
        super<AdbToolboxClient>.confirmDevice(target)

    override fun bind(target: dev.ticbox.companion.api.ToolboxTarget): WatchToolboxBackend =
        super<AdbToolboxClient>.bind(target)

    val bundles = mutableMapOf<String, ToolboxBundle>()
    val refused = mutableMapOf<String, String>()
    val installedBundles = mutableListOf<ToolboxBundle>()
    var bundleResult = ToolboxBundleResult(ToolboxInstallResult(true, "Success"))

    override suspend fun readBundle(file: ToolboxApk): ToolboxBundle? {
        refused[file.name]?.let { throw ToolboxException(it) }
        return bundles[file.name]
    }

    override suspend fun install(
        apks: List<ToolboxApk>,
        options: ToolboxInstallOptions,
        progress: (ToolboxProgress) -> Unit,
    ) = ToolboxInstallResult(true, "Success")

    override suspend fun installBundle(
        bundle: ToolboxBundle,
        options: ToolboxInstallOptions,
        progress: (ToolboxProgress) -> Unit,
    ): ToolboxBundleResult {
        installedBundles += bundle
        return bundleResult
    }

    var search: CompletableDeferred<List<AdbDiscoveredEndpoint>>? = null

    override suspend fun discover() = search?.await().orEmpty()

    override val state =
        MutableStateFlow(
            ToolboxBackendState(
                ToolboxBackendKind.ADB,
                ToolboxPhase.CONNECTED,
                device = "watch",
                target = dev.ticbox.companion.api.ToolboxTarget("watch", 1, ToolboxBackendKind.ADB),
                capabilities =
                    ToolboxCapabilities(
                        uid = 2000,
                        sdk = 33,
                        features = setOf(ToolboxCapability.FILES, ToolboxCapability.APPS),
                    ),
            )
        )

    override suspend fun clipboard() = "copied on the watch"

    val clipboardWrites = mutableListOf<String>()
    val commands = mutableListOf<String>()

    override suspend fun setClipboard(text: String) {
        clipboardWrites += text
    }

    override suspend fun execute(command: String, options: ToolboxCommandOptions) =
        ToolboxCommandResult(0, ByteArray(0), ByteArray(0)).also { commands += command }

    var listings = 0
    val folders = mutableMapOf<String, ToolboxFile>()

    override suspend fun files(path: String) = listOfNotNull(folders[path]).also { listings++ }

    override suspend fun apps(userId: Int) =
        listOf(
            ToolboxApp(
                "com.sample",
                "Sample",
                "1.0",
                1,
                uid = 10_100,
                enabled = true,
                system = false,
                apkPaths = emptyList(),
                dataDirectory = null,
            )
        )
}

private class FakeDnd : DndSyncClient {
    val modes = mutableListOf<DndSyncMode>()
    override val state = MutableStateFlow(DndSyncState())

    override suspend fun setMode(mode: DndSyncMode) {
        modes += mode
        state.value = state.value.copy(mode = mode)
    }

    override suspend fun refresh() = Unit

    override fun close() = Unit
}
