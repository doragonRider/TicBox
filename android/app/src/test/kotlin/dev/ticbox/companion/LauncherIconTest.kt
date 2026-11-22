package dev.ticbox.companion

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.drawable.AdaptiveIconDrawable
import dev.ticbox.companion.ui.AppIconStyle
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LauncherIconTest {
    @Test
    fun adaptiveIconLayersAndAppNameArePackaged() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals("TicBox", context.getString(R.string.app_name))
        for (id in
            listOf(
                R.mipmap.ic_launcher,
                R.mipmap.ic_launcher_slate,
                R.mipmap.ic_launcher_white_dial,
            )) {
            val icon = context.getDrawable(id) as AdaptiveIconDrawable
            assertNotNull(icon.foreground)
            assertNotNull(icon.background)
            assertNotNull(icon.monochrome)
        }
    }

    @Test
    fun choosingAnIconTurnsOnItsLauncherEntryAndTheOthersOff() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = AndroidAppPreferences(context)
        assertEquals(AppIconStyle.DARK_DIAL, preferences.appearance.value.appIcon)
        preferences.setAppIcon(AppIconStyle.WHITE_DIAL)
        val packages = context.packageManager
        fun state(name: String) =
            packages.getComponentEnabledSetting(
                ComponentName(context, "dev.ticbox.companion.$name")
            )
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state("IconWhiteDial"))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state("MainActivity"))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state("IconSlate"))
        assertEquals(AppIconStyle.WHITE_DIAL, preferences.appearance.value.appIcon)
        // Read back as a new start would, from the launcher entries rather than memory.
        assertEquals(
            AppIconStyle.WHITE_DIAL,
            AndroidAppPreferences(context).appearance.value.appIcon,
        )
    }
}
