package dev.ticbox.companion.ui.toolbox

import dev.ticbox.companion.api.AdbEndpoint
import dev.ticbox.companion.api.DeviceSettingsState
import dev.ticbox.companion.api.LocalHealthSyncPhase
import dev.ticbox.companion.api.LocalHealthSyncState
import dev.ticbox.companion.api.ToolboxBackendKind
import dev.ticbox.companion.api.ToolboxBackendState
import dev.ticbox.companion.api.ToolboxCapabilities
import dev.ticbox.companion.api.ToolboxCapability
import dev.ticbox.companion.api.ToolboxFile
import dev.ticbox.companion.api.ToolboxFileKind
import dev.ticbox.companion.api.ToolboxPhase
import dev.ticbox.companion.api.ToolboxScreenEncoder
import dev.ticbox.companion.api.ToolboxScreenOptions
import dev.ticbox.companion.api.ToolboxVideoCodec
import dev.ticbox.companion.api.WatchInstalledApp
import dev.ticbox.companion.ui.bridge.LOCAL_BRIDGE_PACKAGE
import dev.ticbox.companion.ui.bridge.LocalBridgeStatus
import dev.ticbox.companion.ui.bridge.localBridgeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolboxLogicTest {
    private fun connected(kind: ToolboxBackendKind, vararg features: ToolboxCapability) =
        ToolboxBackendState(
            kind,
            ToolboxPhase.CONNECTED,
            capabilities = ToolboxCapabilities(uid = 0, sdk = 33, features = features.toSet()),
        )

    @Test
    fun theOnlyConnectedBackendIsTheOneUsedAndRootWinsWhenBothAre() {
        val adb = connected(ToolboxBackendKind.ADB)
        val root = connected(ToolboxBackendKind.ROOT_BRIDGE)
        assertNull(ToolboxAccess().selected)
        assertFalse(ToolboxAccess().connected)
        assertEquals(ToolboxBackendKind.ADB, ToolboxAccess(adb = adb).selected)
        assertEquals(ToolboxBackendKind.ROOT_BRIDGE, ToolboxAccess(rootBridge = root).selected)
        assertEquals(ToolboxBackendKind.ROOT_BRIDGE, ToolboxAccess(adb, root).selected)
        val chosen = ToolboxAccess(adb, root, preferred = ToolboxBackendKind.ADB)
        assertEquals(ToolboxBackendKind.ADB, chosen.selected)
    }

    @Test
    fun aChoiceForABackendThatDroppedOutDoesNotHideTheOtherOne() {
        val root = connected(ToolboxBackendKind.ROOT_BRIDGE)
        val access = ToolboxAccess(rootBridge = root, preferred = ToolboxBackendKind.ADB)
        assertEquals(ToolboxBackendKind.ROOT_BRIDGE, access.selected)
    }

    @Test
    fun aCapabilityBelongsToTheBackendInUse() {
        val adb = connected(ToolboxBackendKind.ADB, ToolboxCapability.FILES)
        val root = connected(ToolboxBackendKind.ROOT_BRIDGE, ToolboxCapability.FILE_OWNERSHIP)
        assertFalse(ToolboxAccess().has(ToolboxCapability.FILES))
        assertTrue(ToolboxAccess(adb = adb).has(ToolboxCapability.FILES))
        assertFalse(ToolboxAccess(adb = adb).has(ToolboxCapability.FILE_OWNERSHIP))
        assertTrue(ToolboxAccess(adb, root).has(ToolboxCapability.FILE_OWNERSHIP))
        val overAdb = ToolboxAccess(adb, root, preferred = ToolboxBackendKind.ADB)
        assertFalse(overAdb.has(ToolboxCapability.FILE_OWNERSHIP))
    }

    @Test
    fun aFingerOnTheVideoLandsInsideTheFrame() {
        val middle = screenTouch(TOUCH_DOWN, 7, 100f, 50f, 200, 100, 454, 454)
        assertEquals(227, middle.x)
        assertEquals(227, middle.y)
        assertEquals(1f, middle.pressure)
        val offEdge = screenTouch(TOUCH_MOVE, 7, 500f, -3f, 200, 100, 454, 454)
        assertEquals(453, offEdge.x)
        assertEquals(0, offEdge.y)
        assertEquals(0f, screenTouch(TOUCH_UP, 7, 1f, 1f, 200, 100, 454, 454).pressure)
    }

    @Test
    fun pathsClimbToTheRootAndStop() {
        assertEquals("/sdcard", parentPath("/sdcard/Download"))
        assertEquals("/sdcard", parentPath("/sdcard/Download/"))
        assertEquals("/", parentPath("/sdcard"))
        assertNull(parentPath("/"))
        assertEquals("/sdcard/a", childPath("/sdcard", "a"))
        assertEquals("/a", childPath("/", "a"))
        assertEquals(listOf("/", "/sdcard", "/sdcard/x"), pathCrumbs("/sdcard/x"))
        assertEquals(listOf("/"), pathCrumbs("/"))
    }

    private fun file(name: String, kind: ToolboxFileKind, size: Long = 0, modified: Long = 0) =
        ToolboxFile("/$name", name, kind, size, modified, mode = 0, uid = 0, gid = 0)

    @Test
    fun foldersStayFirstWhicheverWayTheRestAreSorted() {
        val files =
            listOf(
                file("b.txt", ToolboxFileKind.FILE, size = 1, modified = 5),
                file("Z", ToolboxFileKind.DIRECTORY),
                file("a.txt", ToolboxFileKind.FILE, size = 9, modified = 1),
                file("c", ToolboxFileKind.DIRECTORY),
            )
        fun order(sort: FileSort, ascending: Boolean) =
            files.sortedWith(fileOrder(sort, ascending)).map { it.name }
        assertEquals(listOf("c", "Z", "a.txt", "b.txt"), order(FileSort.NAME, true))
        assertEquals(listOf("Z", "c", "b.txt", "a.txt"), order(FileSort.NAME, false))
        assertEquals(listOf("c", "Z", "b.txt", "a.txt"), order(FileSort.SIZE, true))
        assertEquals(listOf("Z", "c", "b.txt", "a.txt"), order(FileSort.DATE, false))
    }

    @Test
    fun hiddenFilesAndSearchesNarrowTheList() {
        val state =
            FileManagerUiState(
                files =
                    listOf(
                        file(".nomedia", ToolboxFileKind.FILE),
                        file("Music", ToolboxFileKind.DIRECTORY),
                    )
            )
        assertEquals(listOf("Music"), state.visible.map { it.name })
        assertEquals(2, state.copy(showHidden = true).visible.size)
        assertEquals(0, state.copy(query = "photos").visible.size)
        assertEquals(1, state.copy(query = " mus ").visible.size)
    }

    @Test
    fun onlyRealModesOwnersAndNamesAreAccepted() {
        assertEquals(0b110_100_100, parseFileMode("644"))
        assertEquals(0b111_101_101, parseFileMode("0755"))
        assertNull(parseFileMode("888"))
        assertNull(parseFileMode("12345"))
        assertEquals("0644", fileModeText(0b1000_000_110_100_100))
        assertEquals(1000 to 2000, parseOwner(" 1000:2000 "))
        assertNull(parseOwner("shell:shell"))
        assertNull(parseOwner("1000"))
        assertNull(parseOwner("-1:0"))
        assertTrue(isFileName("notes.txt"))
        assertFalse(isFileName("a/b"))
        assertFalse(isFileName(".."))
        assertFalse(isFileName("  "))
    }

    @Test
    fun onlySixDigitCodesAndUsableAddressesAreAccepted() {
        assertTrue(isPairingCode("123456"))
        assertFalse(isPairingCode("12345"))
        assertFalse(isPairingCode("12345a"))
        assertEquals(AdbEndpoint("192.168.1.5", 5555), adbEndpointOf("192.168.1.5", "5555"))
        assertNull(adbEndpointOf("192.168.1.5", "99999"))
        assertNull(adbEndpointOf("192.168.1.5", "port"))
        assertNull(adbEndpointOf("", "5555"))
    }

    @Test
    fun bluetoothStartsOnTheLightestStream() {
        assertEquals(ScreenQuality.LIGHT, defaultScreenQuality(ToolboxBackendKind.ROOT_BRIDGE))
        assertEquals(ScreenQuality.BALANCED, defaultScreenQuality(ToolboxBackendKind.ADB))
    }

    @Test
    fun aPresetAsksForItsOwnStreamAndAdvancedForWhatWasEntered() {
        val settings = RemoteScreenSettings(preset = ScreenQuality.SHARP, audio = true, h265 = true)
        assertEquals(
            ScreenQuality.SHARP.options.copy(limitToEncoder = true),
            settings.options(ToolboxBackendKind.ADB),
        )
        assertEquals(
            ScreenQuality.LIGHT.options,
            RemoteScreenSettings(limitToEncoder = false).options(ToolboxBackendKind.ROOT_BRIDGE),
        )
        assertEquals(
            ToolboxScreenOptions(
                maxSize = 450,
                maxFps = 60,
                bitRate = 8_000_000,
                codec = ToolboxVideoCodec.H265,
                audio = true,
                limitToEncoder = true,
            ),
            settings.copy(advanced = true).options(null),
        )
        // A stored value from another version can't get a stream refused.
        val stored = RemoteScreenSettings(advanced = true, maxSize = 9_000, maxFps = 0, bitRate = 5)
        assertEquals(
            ToolboxScreenOptions(
                maxSize = 2_560,
                maxFps = 1,
                bitRate = 100_000,
                limitToEncoder = true,
            ),
            stored.options(null),
        )
    }

    @Test
    fun encoderOptionsGoOnlyWithAdvancedAndOnlyWhenValid() {
        val settings =
            RemoteScreenSettings(advanced = true, codecOptions = "video-qp-max=35, bitrate-mode=1")
        assertEquals(
            "video-qp-max=35,bitrate-mode=1",
            settings.options(ToolboxBackendKind.ADB).videoCodecOptions,
        )
        assertEquals(
            "",
            settings.copy(advanced = false).options(ToolboxBackendKind.ADB).videoCodecOptions,
        )
        assertEquals(
            "",
            settings
                .copy(codecOptions = "a=1 b=2")
                .options(ToolboxBackendKind.ADB)
                .videoCodecOptions,
        )
    }

    @Test
    fun theEncoderLimitComesToWhatTheDeviceWillDo() {
        val avc =
            ToolboxScreenEncoder(
                ToolboxVideoCodec.H264,
                true,
                "c2.mtk.avc",
                true,
                1152,
                2560,
                85,
                40_000_000,
            )
        val hevc =
            ToolboxScreenEncoder(
                ToolboxVideoCodec.H265,
                true,
                "c2.mtk.hevc",
                true,
                1152,
                2560,
                120,
                40_000_000,
            )
        val settings = RemoteScreenSettings(maxFps = 120, bitRate = 60_000_000)
        val limited = settings.encoderFit(listOf(avc, hevc))
        assertEquals(avc, limited.encoder)
        assertEquals(85, limited.fps(settings.maxFps))
        assertEquals(40_000_000, limited.bitRate(settings.bitRate))
        assertEquals(30, limited.fps(30))
        assertEquals(120, settings.copy(h265 = true).encoderFit(listOf(avc, hevc)).fps(120))
        // Without an H.265 encoder the device sends H.264, and so says the page.
        val noHevc =
            settings
                .copy(h265 = true)
                .encoderFit(listOf(avc, ToolboxScreenEncoder(ToolboxVideoCodec.H265, false)))
        assertFalse(noHevc.h265Available)
        assertEquals(avc, noHevc.encoder)
        // Off, not read yet, or nothing declared: the values stand as entered.
        listOf(
                settings.copy(limitToEncoder = false).encoderFit(listOf(avc)),
                settings.encoderFit(null),
                settings.encoderFit(emptyList()),
            )
            .forEach {
                assertTrue(it.h265Available)
                assertNull(it.encoder)
                assertEquals(120, it.fps(120))
                assertEquals(60_000_000, it.bitRate(60_000_000))
            }
    }

    @Test
    fun udpIsOptInAndOnlyAppliesToAdvancedWirelessAdb() {
        val stored = RemoteScreenSettings(advanced = true, udp = true)
        assertTrue(stored.options(ToolboxBackendKind.ADB).udp)
        assertFalse(stored.options(ToolboxBackendKind.ROOT_BRIDGE).udp)
        assertFalse(stored.options(null).udp)
        assertFalse(stored.copy(advanced = false).options(ToolboxBackendKind.ADB).udp)
        assertTrue("changing backend preserves the user's wireless preference", stored.udp)
        assertFalse(RemoteScreenSettings().options(ToolboxBackendKind.ADB).udp)
    }

    @Test
    fun aBitRateIsReadAndWrittenInMbps() {
        assertEquals(350_000, parseBitRate("0.35"))
        assertEquals(350_000, parseBitRate("0,35"))
        assertEquals(1_000_000_000, parseBitRate("1000"))
        listOf("", ".", "0.09", "1000.1", "1.2.3").forEach {
            assertEquals(null, parseBitRate(it))
        }
        assertEquals("8", bitRateInMbps(8_000_000))
        assertEquals("0.35", bitRateInMbps(350_000))
        assertEquals("1000", bitRateInMbps(1_000_000_000))
    }

    private fun bridgeStatus(
        reason: String? = null,
        offered: Set<String>? = null,
        phase: LocalHealthSyncPhase = LocalHealthSyncPhase.COMPLETED,
        vararg apps: String,
    ) =
        localBridgeStatus(
            LocalHealthSyncState(phase = phase, reason = reason, advertisedTables = offered),
            DeviceSettingsState(apps = apps.map { WatchInstalledApp(it, null, null, null) }),
        )

    @Test
    fun theBridgeStatusNamesWhatIsLeftToDo() {
        val tables = setOf("data_session")
        assertEquals(
            LocalBridgeStatus.NOT_INSTALLED,
            bridgeStatus(apps = arrayOf("com.other")),
        )
        assertEquals(
            LocalBridgeStatus.NOT_ALLOWED,
            bridgeStatus("data_session:bridge_disabled", apps = arrayOf(LOCAL_BRIDGE_PACKAGE)),
        )
        assertEquals(
            LocalBridgeStatus.UNREACHABLE,
            bridgeStatus("data_session:bridge_timeout", apps = arrayOf(LOCAL_BRIDGE_PACKAGE)),
        )
        assertEquals(LocalBridgeStatus.READY, bridgeStatus(offered = tables))
        assertEquals(LocalBridgeStatus.UNKNOWN, bridgeStatus())
    }

    @Test
    fun aLostLinkSaysNothingAboutTheBridge() {
        assertEquals(
            LocalBridgeStatus.UNKNOWN,
            bridgeStatus(
                "data_session:bridge_timeout",
                phase = LocalHealthSyncPhase.DISCONNECTED,
                apps = arrayOf(LOCAL_BRIDGE_PACKAGE),
            ),
        )
        assertFalse(LocalBridgeStatus.UNKNOWN.needsSetup)
        assertFalse(LocalBridgeStatus.READY.needsSetup)
        assertTrue(LocalBridgeStatus.NOT_INSTALLED.needsSetup)
    }
}
