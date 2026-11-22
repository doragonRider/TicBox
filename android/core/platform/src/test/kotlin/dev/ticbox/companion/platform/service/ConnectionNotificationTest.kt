package dev.ticbox.companion.platform.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.Signature
import android.content.pm.SigningInfo
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN")
class ConnectionNotificationTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val launcherActivity get() = ComponentName(app, "example.LauncherActivity")

    private fun shownNotification(): Notification {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val packages = shadowOf(app.packageManager)
        packages.getInternalMutablePackageInfo(app.packageName).signingInfo = SigningInfo().also {
            shadowOf(it).setSignatures(arrayOf(Signature(ByteArray(8))))
        }
        packages.addActivityIfNotPresent(launcherActivity)
        packages.addIntentFilterForActivity(launcherActivity, IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        })
        // What the service sends itself to go foreground, without restoring a saved connection.
        val keepAlive = Intent(app, BackendSessionService::class.java)
            .setAction("dev.ticbox.companion.action.KEEP_ALIVE").putExtra("retain_generation", 0L)
        val service = Robolectric.buildService(BackendSessionService::class.java, keepAlive).create().startCommand(0, 1).get()
        return assertNotNull(shadowOf(service).lastForegroundNotification)
    }

    @Test
    fun theTitleIsTheSameNameInEveryLanguage() {
        assertEquals("TicBox", shownNotification().extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test
    fun tappingOpensTheAppTheWayItsLauncherIconDoes() {
        val opened = shadowOf(assertNotNull(shownNotification().contentIntent))
        assertTrue(opened.isActivity)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(launcherActivity)
        assertTrue(opened.savedIntent.filterEquals(launcher), "Not the launcher's intent: ${opened.savedIntent}")
        assertTrue(opened.savedIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun aConnectionHasNoActionToMisTap() {
        assertNull(shownNotification().actions)
    }
}
