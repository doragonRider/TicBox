package dev.ticbox.companion

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.Signature
import android.content.pm.SigningInfo
import dev.ticbox.companion.api.ToolboxPhase
import dev.ticbox.companion.api.WatchToolboxClient
import dev.ticbox.companion.platform.service.BackendSessionService
import dev.ticbox.companion.ui.ThemeMode
import dev.ticbox.companion.ui.session.SessionState
import dev.ticbox.companion.ui.session.readySession
import dev.ticbox.companion.ui.toolbox.RemoteScreenSettings
import dev.ticbox.companion.ui.toolbox.ScreenQuality
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ApplicationStartupTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun constructingTheApplicationDoesNotTouchItsContext() {
        // Android constructs Application before attaching a Context; everything is lazy.
        CompanionApplication()
    }

    @Test
    fun remoteScreenSettingsSurviveARestart() {
        val chosen =
            RemoteScreenSettings(
                preset = ScreenQuality.SHARP,
                advanced = true,
                audio = true,
                maxSize = 2_560,
                maxFps = 120,
                bitRate = 1_000_000_000,
                h265 = true,
                limitToEncoder = false,
                codecOptions = "video-qp-max=35,bitrate-mode=1",
                frameStats = true,
                smoothPlayback = true,
                udp = true,
                udpEncryption = false,
            )
        AndroidAppPreferences(context).setRemoteScreen(chosen)
        assertEquals(chosen, AndroidAppPreferences(context).remoteScreen.value)
    }

    @Test
    fun mediaEncryptionDefaultsOnForExistingPreferences() {
        assertTrue(AndroidAppPreferences(context).remoteScreen.value.udpEncryption)
        assertTrue(AndroidAppPreferences(context).remoteScreen.value.limitToEncoder)
    }

    @Test
    fun appearanceSurvivesARestart() {
        AndroidAppPreferences(context).apply {
            setThemeMode(ThemeMode.DARK)
            setDynamicColor(false)
        }
        val restored = AndroidAppPreferences(context).appearance.value
        assertEquals(ThemeMode.DARK, restored.themeMode)
        assertFalse(restored.dynamicColor)
    }

    @Test
    fun hidingEmptyHealthMetricsSurvivesARestart() {
        assertEquals(false, AndroidAppPreferences(context).hideEmptyHealthMetrics.value)
        AndroidAppPreferences(context).setHideEmptyHealthMetrics(true)
        assertEquals(true, AndroidAppPreferences(context).hideEmptyHealthMetrics.value)
    }

    @Test
    fun theStepGoalSurvivesARestartUntilCleared() {
        AndroidAppPreferences(context).setStepGoal(12_000)
        assertEquals(12_000, AndroidAppPreferences(context).stepGoal.value)
        AndroidAppPreferences(context).setStepGoal(null)
        assertEquals(null, AndroidAppPreferences(context).stepGoal.value)
    }

    @Test
    fun theDemoReplacesTheServiceSessionUntilExited() {
        val controller =
            ServiceSessionController(context, CoroutineScope(SupervisorJob() + Dispatchers.Main)) {
                "profile"
            }
        assertEquals(SessionState.Connecting, controller.state.value)

        controller.enterDemo()
        ShadowLooper.idleMainLooper()
        val demo = controller.state.value.readySession
        assertTrue(demo?.isDemo == true)
        assertEquals("profile", demo?.localProfileId)

        controller.exitDemo()
        ShadowLooper.idleMainLooper()
        assertEquals(SessionState.Connecting, controller.state.value)
    }

    @Test
    fun demoBorrowsRealAdbAcrossServiceRebindingWithoutExposingTheWatchBridge() {
        shadowOf(context.packageManager)
            .getInternalMutablePackageInfo(context.packageName)
            .signingInfo =
            SigningInfo().also { shadowOf(it).setSignatures(arrayOf(Signature(ByteArray(8)))) }
        val bindingContext = BindingContext(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = ServiceSessionController(bindingContext, scope) { "profile" }
        val first = Robolectric.buildService(BackendSessionService::class.java).create()
        var firstDestroyed = false
        val second = Robolectric.buildService(BackendSessionService::class.java)
        var secondCreated = false
        try {
            controller.enterDemo()
            ShadowLooper.idleMainLooper()
            val initial = checkNotNull(controller.state.value.readySession)
            assertSame(WatchToolboxClient.UNAVAILABLE, initial.services.toolbox)

            controller.bind()
            val component = ComponentName(context, BackendSessionService::class.java)
            val firstBinder = first.get().LocalBinder()
            bindingContext.connection.onServiceConnected(component, firstBinder)
            ShadowLooper.idleMainLooper()
            val connected = checkNotNull(controller.state.value.readySession)
            assertTrue(connected.isDemo)
            assertEquals(initial.key, connected.key)
            assertSame(initial.client, connected.client)
            assertSame(firstBinder.services.toolbox.adb, connected.services.toolbox.adb)
            assertNotSame(WatchToolboxClient.UNAVAILABLE.adb, connected.services.toolbox.adb)
            assertSame(
                WatchToolboxClient.UNAVAILABLE.localBridge,
                connected.services.toolbox.localBridge,
            )

            bindingContext.connection.onServiceDisconnected(component)
            ShadowLooper.idleMainLooper()
            val disconnected = checkNotNull(controller.state.value.readySession)
            assertSame(initial.client, disconnected.client)
            assertSame(WatchToolboxClient.UNAVAILABLE, disconnected.services.toolbox)
            first.destroy()
            firstDestroyed = true

            second.create()
            secondCreated = true
            val secondBinder = second.get().LocalBinder()
            bindingContext.connection.onServiceConnected(component, secondBinder)
            ShadowLooper.idleMainLooper()
            val rebound = checkNotNull(controller.state.value.readySession)
            assertSame(initial.client, rebound.client)
            assertSame(secondBinder.services.toolbox.adb, rebound.services.toolbox.adb)
            assertNotSame(connected.services.toolbox.adb, rebound.services.toolbox.adb)

            // This view does not own the service clients, including when a caller closes it.
            rebound.services.toolbox.close()
            assertEquals(
                ToolboxPhase.DISCONNECTED,
                secondBinder.services.toolbox.adb.state.value.phase,
            )
            controller.exitDemo()
            ShadowLooper.idleMainLooper()
            val real = checkNotNull(controller.state.value.readySession)
            assertFalse(real.isDemo)
            assertSame(secondBinder.services.toolbox, real.services.toolbox)

            controller.enterDemo()
            ShadowLooper.idleMainLooper()
            val next = checkNotNull(controller.state.value.readySession)
            assertSame(rebound.services.toolbox, next.services.toolbox)
            assertSame(secondBinder.services.toolbox.adb, next.services.toolbox.adb)
        } finally {
            if (!firstDestroyed) first.destroy()
            if (secondCreated) second.destroy()
            scope.cancel()
            ShadowLooper.idleMainLooper()
        }
    }

    private class BindingContext(context: Context) : ContextWrapper(context) {
        lateinit var connection: ServiceConnection

        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
            connection = conn
            return true
        }
    }
}
