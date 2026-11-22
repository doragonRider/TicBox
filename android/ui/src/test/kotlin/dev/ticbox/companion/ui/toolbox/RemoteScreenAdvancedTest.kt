package dev.ticbox.companion.ui.toolbox

import android.app.Activity
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.net.wifi.WifiManager
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsEqualTo
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import dev.ticbox.companion.api.AdbToolboxClient
import dev.ticbox.companion.api.ToolboxBackendKind
import dev.ticbox.companion.api.ToolboxBackendState
import dev.ticbox.companion.api.ToolboxCapabilities
import dev.ticbox.companion.api.ToolboxCapability
import dev.ticbox.companion.api.ToolboxPhase
import dev.ticbox.companion.api.ToolboxScreenEncoder
import dev.ticbox.companion.api.ToolboxScreenEvent
import dev.ticbox.companion.api.ToolboxScreenInput
import dev.ticbox.companion.api.ToolboxScreenOptions
import dev.ticbox.companion.api.ToolboxScreenPlayback
import dev.ticbox.companion.api.ToolboxScreenSession
import dev.ticbox.companion.api.ToolboxScreenTiming
import dev.ticbox.companion.api.ToolboxTarget
import dev.ticbox.companion.api.ToolboxVideoCodec
import dev.ticbox.companion.api.WatchToolboxBackend
import dev.ticbox.companion.api.WatchToolboxClient
import dev.ticbox.companion.ui.CompanionApp
import dev.ticbox.companion.ui.FakeSessions
import dev.ticbox.companion.ui.NoSystemActions
import dev.ticbox.companion.ui.TestDependencies
import dev.ticbox.companion.ui.demoWatch
import dev.ticbox.companion.ui.navigation.RemoteScreenRoute
import dev.ticbox.companion.ui.navigation.ToolboxGraph
import dev.ticbox.companion.ui.session
import dev.ticbox.companion.ui.session.SessionState
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w411dp-h891dp")
class RemoteScreenAdvancedTest {
    @get:Rule val compose = createComposeRule()

    private val context = RuntimeEnvironment.getApplication()
    private val backend = RecordingAdb()
    private val rootBackend = RecordingRoot()
    private val client =
        object : WatchToolboxClient {
            override val adb: AdbToolboxClient = backend
            override val localBridge: WatchToolboxBackend = rootBackend
        }
    private val dependencies =
        TestDependencies(
            FakeSessions(
                SessionState.Ready(demoWatch(context).session(key = "watch", toolbox = client))
            )
        )
    private val settings
        get() = dependencies.preferences.remoteScreen.value

    private lateinit var root: View
    private lateinit var activity: Activity
    private lateinit var density: Density
    private lateinit var backPresses: OnBackPressedDispatcher
    private var orientation by mutableIntStateOf(Configuration.ORIENTATION_PORTRAIT)

    private lateinit var navigation: NavHostController

    private fun show(): NavHostController {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            navigation = nav
            root = LocalView.current
            activity = LocalActivity.current!!
            density = LocalDensity.current
            backPresses = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            val turned =
                Configuration(LocalConfiguration.current).apply {
                    orientation = this@RemoteScreenAdvancedTest.orientation
                }
            CompositionLocalProvider(LocalConfiguration provides turned) {
                CompanionApp(dependencies, NoSystemActions, nav)
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread { nav.navigate(RemoteScreenRoute) }
        compose.waitForIdle()
        return nav
    }

    private fun text(value: String) = compose.onNodeWithText(value)

    private fun button(description: String) = compose.onNodeWithContentDescription(description)

    private fun field(index: Int) = compose.onAllNodes(hasSetTextAction())[index]

    private fun surfaceView(view: View = root): SurfaceView? =
        when (view) {
            is SurfaceView -> view
            is ViewGroup ->
                (0 until view.childCount).firstNotNullOfOrNull {
                    surfaceView(view.getChildAt(it))
                }
            else -> null
        }

    /** The page's own, which outlives it while another page is on top. */
    private fun mirrorViewModel(): RemoteScreenViewModel =
        ViewModelProvider.create(
                navigation.getBackStackEntry<RemoteScreenRoute>(),
                object : ViewModelProvider.Factory {},
            )[RemoteScreenViewModel::class]

    private fun startMirror(): SurfaceView {
        text("Start").performClick()
        compose.waitForIdle()
        return giveSurface()
    }

    /**
     * A device's view system would do this once the view is drawn. Robolectric's has no surface.
     */
    private fun giveSurface(): SurfaceView {
        compose.waitUntil { surfaceView() != null }
        val mirror = assertNotNull(surfaceView())
        val before = backend.opened.size
        compose.runOnUiThread { mirrorViewModel().setSurface(Surface(SurfaceTexture(0))) }
        compose.waitUntil { backend.opened.size == before + 1 }
        compose.waitForIdle()
        return mirror
    }

    private fun heldWifiLocks() =
        Shadows.shadowOf(context.getSystemService(WifiManager::class.java)).activeLockCount

    private fun openAdvanced() {
        text("Presets").performScrollTo().performClick()
        compose.waitForIdle()
        text("Advanced").performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun advancedIsTheLastPresetAndOpensItsSettings() {
        show()
        text("Presets").performScrollTo().performClick()
        compose.waitForIdle()
        val advanced = text("Advanced").performScrollTo().getBoundsInRoot()
        assertTrue(advanced.top > text("Sharp").getBoundsInRoot().top)
        text("450 px · 8 Mbps · 60 FPS").assertExists()
        text("Advanced").performClick()
        compose.waitForIdle()
        listOf("Audio", "H265").forEach { text(it).performScrollTo().assertIsDisplayed() }
        field(0).assertTextContains("450")
        field(1).assertTextContains("60")
        field(2).assertTextContains("8")
        text("Pixels on the long side, 64 to 2560").assertExists()
        text("0.1 to 1000").assertExists()
        assertEquals(RemoteScreenSettings(advanced = true), settings)
    }

    @Test
    fun whatAdvancedSetsIsWhatTheStreamAsksFor() {
        show()
        openAdvanced()
        text("Audio").performScrollTo().performClick()
        text("H265").performScrollTo().performClick()
        text("UDP streaming").performScrollTo().performClick()
        field(0).performScrollTo()
        field(0).performTextReplacement("2560")
        field(1).performTextReplacement("120")
        field(2).performTextReplacement("1000")
        button("Back").performClick()
        compose.waitForIdle()
        startMirror()
        assertEquals(
            ToolboxScreenOptions(
                maxSize = 2_560,
                maxFps = 120,
                bitRate = 1_000_000_000,
                codec = ToolboxVideoCodec.H265,
                audio = true,
                udp = true,
                limitToEncoder = true,
            ),
            backend.opened.single(),
        )
    }

    @Test
    fun theEncoderLimitIsOnByDefaultAndShowsWhatTheDeviceWillSend() {
        backend.encoders =
            listOf(
                ToolboxScreenEncoder(
                    ToolboxVideoCodec.H264,
                    true,
                    "c2.mtk.avc",
                    true,
                    448,
                    992,
                    85,
                    40_000_000,
                ),
                ToolboxScreenEncoder(ToolboxVideoCodec.H265, false),
            )
        dependencies.preferences.setRemoteScreen(
            RemoteScreenSettings(maxFps = 120, bitRate = 60_000_000, h265 = true)
        )
        show()
        openAdvanced()
        text("c2.mtk.avc declares up to 85 FPS and 40 Mbps at 448 × 992 px").assertExists()
        text("The encoder goes up to 85, so the stream uses 85").assertExists()
        text("The encoder goes up to 40, so the stream uses 40").assertExists()
        text("H265").performScrollTo().assertIsOff().assertIsNotEnabled()
        text("The mirrored device has no H265 encoder").assertExists()
        field(1).performTextReplacement("60")
        compose.waitForIdle()
        text("1 to 85, the encoder's limit").assertExists()

        text("Stay within the encoder's limits").performScrollTo().assertIsOn().performClick()
        compose.waitForIdle()
        assertFalse(settings.limitToEncoder)
        text("H265").assertIsOn().assertIsEnabled()
        text("1 to 120").assertExists()
        text("0.1 to 1000").assertExists()
        text("Stay within the encoder's limits").performClick()
        compose.waitForIdle()
        assertEquals(listOf(450), backend.encoderReads, "read once per connection and size")

        button("Back").performClick()
        compose.waitForIdle()
        // Both the Presets row and the Advanced row under it say so.
        compose.onAllNodesWithText("450 px · 40 Mbps · 60 FPS").assertCountEquals(2)
        startMirror()
        val asked = backend.opened.single()
        assertTrue(asked.limitToEncoder)
        assertEquals(ToolboxVideoCodec.H265, asked.codec, "the device itself falls back to H264")
    }

    @Test
    fun experimentalEncoderOptionsAreSavedOnlyWhenValidAndReachTheStream() {
        show()
        openAdvanced()
        field(3).performScrollTo().performTextReplacement("video-qp-max=35, bitrate-mode=1")
        assertEquals("video-qp-max=35,bitrate-mode=1", settings.codecOptions)
        field(3).performTextReplacement("video-qp-max=35 power_on=false")
        field(3).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        assertEquals("video-qp-max=35,bitrate-mode=1", settings.codecOptions)
        field(3).performTextReplacement("video-qp-max=35,bitrate-mode=1")
        button("Back").performClick()
        compose.waitForIdle()
        startMirror()
        assertEquals("video-qp-max=35,bitrate-mode=1", backend.opened.single().videoCodecOptions)
    }

    @Test
    fun aConnectionThatCantReportEncodersSaysSo() {
        val older = ToolboxCapability.values().toSet() - ToolboxCapability.SCREEN_ENCODER_LIMITS
        backend.state.value =
            backend.state.value.copy(capabilities = ToolboxCapabilities(2000, 33, older))
        show()
        openAdvanced()
        text(
                "This connection can't report encoder limits, so the values below are used as entered."
            )
            .assertExists()
        text("H265").assertIsEnabled()
        assertTrue(backend.encoderReads.isEmpty())
    }

    @Test
    fun udpIsDisabledOnBluetoothAndReenabledWithItsSavedSettingOnAdb() {
        show()
        openAdvanced()
        text("UDP streaming").performScrollTo().assertIsEnabled().performClick()
        assertTrue(settings.udp)
        rootBackend.state.value =
            rootBackend.state.value.copy(
                phase = ToolboxPhase.CONNECTED,
                target = ToolboxTarget("watch", 1, ToolboxBackendKind.ROOT_BRIDGE),
            )
        compose.waitForIdle()
        text("UDP streaming").assertIsNotEnabled()
        assertTrue(settings.udp, "Bluetooth doesn't erase the wireless ADB preference")
        rootBackend.state.value = rootBackend.state.value.copy(phase = ToolboxPhase.DISCONNECTED)
        compose.waitForIdle()
        text("UDP streaming").assertIsEnabled()
        assertTrue(settings.options(ToolboxBackendKind.ADB).udp)
    }

    @Test
    fun decoderReadinessIsAcknowledgedBeforeTheUiClaimsLiveVideo() {
        dependencies.preferences.setRemoteScreen(RemoteScreenSettings(advanced = true, udp = true))
        backend.waitForDecoder = true
        val configuration = ToolboxScreenEvent.Configuration("h264", 450, 450)
        backend.nextEvents = listOf(configuration)
        show()
        startMirror()
        compose.waitUntil { backend.sessions.last().prepared.isNotEmpty() }
        assertEquals(configuration, backend.sessions.last().prepared.single().first)
        assertTrue(backend.sessions.last().prepared.single().second >= 0)
        assertEquals(ScreenPhase.STARTING, mirrorViewModel().state.value.phase)
        text("Stop").assertIsEnabled()
    }

    @Test
    fun encryptionIsExplicitSavedAndDisabledUntilWirelessUdpIsSelected() {
        show()
        openAdvanced()
        text("UDP media encryption").performScrollTo().assertIsNotEnabled()
        assertTrue(settings.udpEncryption)
        text("UDP streaming").performScrollTo().performClick()
        text("UDP media encryption").performScrollTo().assertIsEnabled().performClick()
        assertEquals(false, settings.udpEncryption)
        assertEquals(false, settings.options(ToolboxBackendKind.ADB).udpEncryption)
        rootBackend.state.value =
            rootBackend.state.value.copy(
                phase = ToolboxPhase.CONNECTED,
                target = ToolboxTarget("watch", 1, ToolboxBackendKind.ROOT_BRIDGE),
            )
        compose.waitForIdle()
        text("UDP media encryption").assertIsNotEnabled()
        assertTrue(settings.options(ToolboxBackendKind.ROOT_BRIDGE).udpEncryption)
        assertEquals(false, settings.udpEncryption)
        rootBackend.state.value = rootBackend.state.value.copy(phase = ToolboxPhase.DISCONNECTED)
        compose.waitForIdle()
        text("UDP media encryption").assertIsEnabled()
        button("Back").performClick()
        compose.waitForIdle()
        startMirror()
        assertEquals(false, backend.opened.single().udpEncryption)
    }

    @Test
    fun aDroppedUdpBackendStopsInsteadOfFallingBackToTheConnectedBluetoothBackend() {
        dependencies.preferences.setRemoteScreen(RemoteScreenSettings(advanced = true, udp = true))
        show()
        compose.runOnUiThread {
            ViewModelProvider.create(
                    navigation.getBackStackEntry<ToolboxGraph>(),
                    object : ViewModelProvider.Factory {},
                )[ToolboxViewModel::class]
                .select(ToolboxBackendKind.ADB)
        }
        rootBackend.state.value =
            rootBackend.state.value.copy(
                phase = ToolboxPhase.CONNECTED,
                target = ToolboxTarget("watch", 1, ToolboxBackendKind.ROOT_BRIDGE),
            )
        compose.waitForIdle()
        startMirror()
        assertTrue(backend.opened.single().udp)
        backend.state.value = backend.state.value.copy(phase = ToolboxPhase.DISCONNECTED)
        compose.waitUntil {
            compose
                .onAllNodes(hasText("The UDP media connection was lost. Mirroring stopped."))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertEquals(0, rootBackend.opened)
        text("Start").assertIsEnabled()
    }

    @Test
    fun enablingUdpWhilePausedInAdvancedDoesNotResumeOnBluetoothAfterAdbDrops() {
        show()
        compose.runOnUiThread {
            ViewModelProvider.create(
                    navigation.getBackStackEntry<ToolboxGraph>(),
                    object : ViewModelProvider.Factory {},
                )[ToolboxViewModel::class]
                .select(ToolboxBackendKind.ADB)
        }
        rootBackend.state.value =
            rootBackend.state.value.copy(
                phase = ToolboxPhase.CONNECTED,
                target = ToolboxTarget("watch", 1, ToolboxBackendKind.ROOT_BRIDGE),
            )
        compose.waitForIdle()
        startMirror()
        assertEquals(false, backend.opened.single().udp)
        openAdvanced()
        text("UDP streaming").performScrollTo().performClick()
        assertTrue(settings.udp)
        backend.state.value = backend.state.value.copy(phase = ToolboxPhase.DISCONNECTED)
        compose.waitForIdle()
        button("Back").performClick()
        compose.waitUntil {
            compose
                .onAllNodes(hasText("The UDP media connection was lost. Mirroring stopped."))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertEquals(0, rootBackend.opened)
        assertEquals(1, backend.opened.size)
        text("Start").assertIsEnabled()
    }

    @Test
    fun aValueOutOfRangeIsMarkedAndNotSaved() {
        show()
        openAdvanced()
        val inError = SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)
        fun assertRefused(index: Int, value: String) {
            field(index).performTextReplacement(value)
            field(index).assert(inError)
            assertEquals(RemoteScreenSettings(advanced = true), settings, value)
        }
        assertRefused(0, "2561")
        assertRefused(0, "")
        assertRefused(1, "121")
        assertRefused(1, "0")
        assertRefused(2, "1000.5")
        assertRefused(2, "1.2.3")
        field(0).performTextReplacement("1x0x8")
        field(0).assertTextContains("108")
        field(2).performTextReplacement("0,35")
        assertEquals(108, settings.maxSize)
        assertEquals(350_000, settings.bitRate)
    }

    @Test
    fun aPresetTakesOverFromAdvancedAndLeavesItsValues() {
        show()
        openAdvanced()
        field(0).performTextReplacement("720")
        button("Back").performClick()
        compose.waitForIdle()
        text("Sharp").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(
            RemoteScreenSettings(ScreenQuality.SHARP, advanced = false, maxSize = 720),
            settings,
        )
        startMirror()
        assertEquals(
            ScreenQuality.SHARP.options.copy(limitToEncoder = true),
            backend.opened.single(),
        )
    }

    @Test
    fun wifiIsHeldOnlyWhileTheMirrorRuns() {
        show()
        assertEquals(0, heldWifiLocks())
        startMirror()
        assertEquals(1, heldWifiLocks())
        text("Stop").performClick()
        compose.waitUntil { heldWifiLocks() == 0 }
    }

    @Test
    fun stoppingKeepsTheVideoUpUntilItIsCovered() {
        show()
        val mirror = startMirror()
        compose.mainClock.autoAdvance = false
        text("Stop").performClick()
        compose.mainClock.advanceTimeBy(48)
        assertTrue(mirror.isAttachedToWindow)
        compose.mainClock.autoAdvance = true
        compose.waitUntil { !mirror.isAttachedToWindow }
    }

    @Test
    fun frameTimingShowsOnlyWhenAskedFor() {
        show()
        startMirror()
        val timing = hasContentDescription("FPS", substring = true)
        val disabled = backend.sessions.single()
        assertFalse(backend.opened.single().collectDiagnostics)
        Thread.sleep(2_200) // Beyond the old always-running diagnostic report interval.
        compose.waitForIdle()
        compose.onAllNodes(timing).assertCountEquals(0)
        assertTrue(disabled.playback.isEmpty())
        assertEquals(0, disabled.timingCalls)
        compose.onAllNodes(timing).assertCountEquals(0)
        dependencies.preferences.setRemoteScreen(settings.copy(frameStats = true))
        compose.waitUntil(3_000) { compose.onAllNodes(timing).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, backend.opened.size, "capture is negotiated when opening the new session")
        assertTrue(disabled.closed)
        assertTrue(backend.opened.last().collectDiagnostics)
        compose.waitUntil(3_000) { backend.sessions.last().playback.isNotEmpty() }
        // The reliable stream measures neither the link nor the sending end.
        compose.onNode(hasContentDescription("Ping –")).assertExists()
        compose.onNode(hasContentDescription("Enc – Dec", substring = true)).assertExists()
        compose.onNode(hasContentDescription("1% Low", substring = true)).assertExists()
        val enabled = backend.sessions.last()
        dependencies.preferences.setRemoteScreen(settings.copy(frameStats = false))
        compose.waitUntil(3_000) { backend.opened.size == 3 }
        assertTrue(enabled.closed)
        assertFalse(backend.opened.last().collectDiagnostics)
        val stoppedReports = enabled.playback.size
        Thread.sleep(2_200)
        compose.waitForIdle()
        assertEquals(stoppedReports, enabled.playback.size)
        assertTrue(backend.sessions.last().playback.isEmpty())
        assertEquals(0, backend.sessions.last().timingCalls)
        compose.onAllNodes(timing).assertCountEquals(0)
    }

    @Test
    fun udpFrameTimingShowsTheLinkAndTheSendingEnd() {
        dependencies.preferences.setRemoteScreen(
            RemoteScreenSettings(advanced = true, udp = true, frameStats = true)
        )
        backend.nextTiming = ToolboxScreenTiming(roundTripMicros = 3_400, senderMicros = 12_000)
        show()
        startMirror()
        val ping = hasContentDescription("Ping 3.4 ms")
        compose.waitUntil(3_000) { compose.onAllNodes(ping).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(backend.opened.single().udp)
        compose.onNode(hasContentDescription("Enc 12 Dec", substring = true)).assertExists()
    }

    @Test
    fun comingBackFromAdvancedResumesTheMirrorWithTheNewSettings() {
        show()
        startMirror()
        openAdvanced()
        // What a device does as the view leaves the window.
        compose.runOnUiThread { mirrorViewModel().setSurface(null) }
        field(0).performTextReplacement("1080")
        button("Back").performClick()
        compose.waitForIdle()
        giveSurface()
        assertEquals(2, backend.opened.size)
        assertEquals(1080, backend.opened.last().maxSize)
        assertTrue(backend.sessions.first().closed)
    }

    @Test
    fun fullScreenFillsThePageWithoutRestartingTheStream() {
        show()
        button("Full screen").assertIsNotEnabled()
        val mirror = startMirror()
        val windowedWidth = mirror.width
        with(density) { assertEquals(280.dp.roundToPx(), windowedWidth) }
        button("Full screen").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(root.width, mirror.width)
        assertEquals(root.width, mirror.height)
        assertEquals((root.height - mirror.height) / 2, mirror.locationInRootY())
        text("Remote Screen").assertDoesNotExist()
        text("Stop").assertDoesNotExist()
        text("Back").assertIsNotDisplayed()

        button("Exit full screen").performClick()
        compose.waitForIdle()
        text("Remote Screen").assertIsDisplayed()
        text("Stop").assertIsDisplayed()
        text("Back").assertIsDisplayed()
        assertEquals(windowedWidth, mirror.width)

        button("Full screen").performClick()
        compose.waitForIdle()
        compose.runOnUiThread { backPresses.onBackPressed() }
        compose.waitForIdle()
        text("Remote Screen").assertIsDisplayed()
        button("Exit full screen").assertDoesNotExist()

        assertSame(mirror, surfaceView())
        assertEquals(1, backend.opened.size)
    }

    @Test
    fun theFullScreenToolbarPressesKeysFoldsAwayAndLeaves() {
        show()
        backend.nextEvents = listOf(ToolboxScreenEvent.Configuration("h264", 450, 450))
        startMirror()
        button("Hide controls").assertDoesNotExist()
        button("Full screen").performClick()
        compose.waitForIdle()

        button("Power").assertIsEnabled().performClick()
        val session = backend.sessions.last()
        compose.waitUntil { session.inputs.size == 2 }
        assertEquals(
            listOf(
                ToolboxScreenInput.Key(KeyEvent.KEYCODE_POWER, down = true),
                ToolboxScreenInput.Key(KeyEvent.KEYCODE_POWER, down = false),
            ),
            session.inputs.toList(),
        )

        button("Hide controls").performClick()
        compose.waitForIdle()
        button("Power").assertDoesNotExist()
        button("Exit full screen").assertDoesNotExist()

        button("Show controls").performClick()
        compose.waitForIdle()
        button("Exit full screen").performClick()
        compose.waitForIdle()
        button("Hide controls").assertDoesNotExist()
        text("Remote Screen").assertIsDisplayed()
    }

    /** The way ViewRootImpl hands a key to the window, without the input channel it reads from. */
    private fun sendPhoneKey(event: KeyEvent): Boolean = compose.runOnUiThread {
        val keys = ReflectionHelpers.getField<Any>(root.rootView.parent, "mUnhandledKeyManager")
        val captured =
            ReflectionHelpers.callInstanceMethod<Boolean>(
                keys,
                "preViewDispatch",
                ReflectionHelpers.ClassParameter.from(KeyEvent::class.java, event),
            )
        captured || activity.dispatchKeyEvent(event)
    }

    @Test
    fun inFullScreenThePhonesVolumeKeysPressTheMirroredDevices() {
        val volumeUp = KeyEvent.KEYCODE_VOLUME_UP
        show()
        backend.nextEvents = listOf(ToolboxScreenEvent.Configuration("h264", 450, 450))
        startMirror()
        button("Full screen").performClick()
        compose.waitForIdle()

        assertTrue(sendPhoneKey(KeyEvent(KeyEvent.ACTION_DOWN, volumeUp)))
        // Held: the key repeats, and each repeat is another press.
        assertTrue(sendPhoneKey(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, volumeUp, 1)))
        assertTrue(sendPhoneKey(KeyEvent(KeyEvent.ACTION_UP, volumeUp)))

        val session = backend.sessions.last()
        compose.waitUntil { session.inputs.size >= 4 }
        compose.waitForIdle()
        val press =
            listOf(
                ToolboxScreenInput.Key(volumeUp, down = true),
                ToolboxScreenInput.Key(volumeUp, down = false),
            )
        assertEquals(press + press, session.inputs.toList())
    }

    @Test
    fun outsideFullScreenThePhonesVolumeKeysStayThePhones() {
        show()
        backend.nextEvents = listOf(ToolboxScreenEvent.Configuration("h264", 450, 450))
        startMirror()

        val press = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)
        assertEquals(false, sendPhoneKey(press))

        compose.waitForIdle()
        assertEquals(emptyList(), backend.sessions.last().inputs.toList())
    }

    @Test
    fun theToolbarDragsWithinTheScreenAndKeepsItsTouchesFromTheMirror() {
        show()
        startMirror()
        button("Full screen").performClick()
        compose.waitForIdle()
        val toggle = button("Hide controls")
        val before = toggle.getBoundsInRoot()

        toggle.performTouchInput { swipeUp(startY = centerY, endY = -5_000f) }
        compose.waitForIdle()

        val after = toggle.getBoundsInRoot()
        // Its margin from the screen's edge, and the toolbar's own padding around the button.
        after.top.assertIsEqualTo(19.dp, "toggle top")
        after.left.assertIsEqualTo(before.left, "toggle left")
        assertEquals(emptyList(), backend.sessions.last().inputs.toList())
    }

    @Test
    fun aSessionThatEndsLeavesFullScreenAndSaysSo() {
        show()
        startMirror()
        button("Full screen").performClick()
        compose.waitForIdle()
        backend.sessions.single().end.complete(Unit)
        compose.waitUntil {
            compose.onAllNodes(hasText("Start")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        button("Exit full screen").assertDoesNotExist()
        text("Mirroring ended. The connection may have dropped.").assertIsDisplayed()
    }

    @Test
    fun turningThePhoneKeepsTheSameVideoSurface() {
        show()
        val mirror = startMirror()
        val upright = text("Back").getBoundsInRoot()
        orientation = Configuration.ORIENTATION_LANDSCAPE
        compose.waitForIdle()
        val sideways = text("Back").getBoundsInRoot()
        assertTrue(sideways.left > upright.left, "controls beside the mirror")
        assertTrue(sideways.top < upright.top, "controls at the top")
        assertSame(mirror, surfaceView())
        assertEquals(1, backend.opened.size)
    }

    @Test
    fun losingTheConnectionEndsTheMirrorUntilItIsStartedAgain() {
        show()
        startMirror()
        val connected = backend.state.value
        backend.state.value = connected.copy(phase = ToolboxPhase.DISCONNECTED)
        compose.waitUntil {
            compose.onAllNodes(hasText("Start")).fetchSemanticsNodes().isNotEmpty()
        }
        backend.state.value = connected
        compose.waitForIdle()
        text("Start").assertIsEnabled()
        assertEquals(1, backend.opened.size)
    }

    @Test
    fun aDeviceThatCantCaptureSoundStillShowsThePicture() {
        backend.nextEvents = listOf(ToolboxScreenEvent.AudioConfiguration(null))
        show()
        startMirror()
        compose.waitUntil {
            compose.onAllNodes(hasText(CANT_CAPTURE)).fetchSemanticsNodes().isNotEmpty()
        }
        text("Stop").assertIsDisplayed()
    }

    private fun View.locationInRootY(): Int {
        val here = IntArray(2).also(::getLocationInWindow)
        val top = IntArray(2).also(root::getLocationInWindow)
        return here[1] - top[1]
    }

    private companion object {
        const val CANT_CAPTURE = "Couldn't capture audio on this device. Showing the picture only."
    }
}

private class QuietSession(
    events: List<ToolboxScreenEvent>,
    override val requiresVideoDecoderReady: Boolean = false,
    private val timing: ToolboxScreenTiming? = null,
) : ToolboxScreenSession {
    val prepared = CopyOnWriteArrayList<Pair<ToolboxScreenEvent.Configuration, Long>>()

    override fun videoDecoderReady(
        configuration: ToolboxScreenEvent.Configuration,
        initializationMillis: Long,
    ) {
        prepared += configuration to initializationMillis
    }

    val end = CompletableDeferred<Unit>()
    @Volatile var closed = false
    val inputs = CopyOnWriteArrayList<ToolboxScreenInput>()
    val playback = CopyOnWriteArrayList<ToolboxScreenPlayback>()
    @Volatile var timingCalls = 0

    override val events: Flow<ToolboxScreenEvent> = flow {
        events.forEach { emit(it) }
        end.await()
    }

    override fun reportPlayback(sample: ToolboxScreenPlayback) {
        playback += sample
    }

    override fun timing(): ToolboxScreenTiming? {
        timingCalls++
        return timing
    }

    override suspend fun input(event: ToolboxScreenInput) {
        inputs += event
    }

    override suspend fun getClipboard() = Unit

    override suspend fun setClipboard(text: String, paste: Boolean) = 0L

    override fun close() {
        closed = true
    }
}

private class RecordingAdb : AdbToolboxClient by WatchToolboxClient.UNAVAILABLE.adb {
    override fun confirmDevice(target: ToolboxTarget) =
        super<AdbToolboxClient>.confirmDevice(target)

    override fun bind(target: ToolboxTarget): WatchToolboxBackend =
        super<AdbToolboxClient>.bind(target)

    val opened = CopyOnWriteArrayList<ToolboxScreenOptions>()
    val sessions = CopyOnWriteArrayList<QuietSession>()
    @Volatile var encoders: List<ToolboxScreenEncoder> = emptyList()
    val encoderReads = CopyOnWriteArrayList<Int>()

    override suspend fun screenEncoders(maxSize: Int): List<ToolboxScreenEncoder> {
        encoderReads += maxSize
        return encoders
    }

    @Volatile var waitForDecoder = false
    @Volatile var nextEvents: List<ToolboxScreenEvent> = emptyList()
    @Volatile var nextTiming: ToolboxScreenTiming? = null

    override suspend fun openScreen(options: ToolboxScreenOptions): ToolboxScreenSession {
        val session = QuietSession(nextEvents, waitForDecoder, nextTiming)
        sessions += session
        opened += options
        return session
    }

    override val state =
        MutableStateFlow(
            ToolboxBackendState(
                ToolboxBackendKind.ADB,
                ToolboxPhase.CONNECTED,
                device = "watch",
                target = ToolboxTarget("watch", 1, ToolboxBackendKind.ADB),
                capabilities =
                    ToolboxCapabilities(
                        uid = 2000,
                        sdk = 33,
                        features = ToolboxCapability.values().toSet(),
                    ),
            )
        )
}

private class RecordingRoot : WatchToolboxBackend by WatchToolboxClient.UNAVAILABLE.localBridge {
    @Volatile var opened = 0

    override suspend fun openScreen(options: ToolboxScreenOptions): ToolboxScreenSession {
        opened++
        return QuietSession(emptyList())
    }

    override val state = MutableStateFlow(ToolboxBackendState(ToolboxBackendKind.ROOT_BRIDGE))
}
