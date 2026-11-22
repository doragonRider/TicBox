package dev.ticbox.companion

import android.app.Application
import android.graphics.drawable.AdaptiveIconDrawable
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
        val icon = context.getDrawable(R.mipmap.ic_launcher) as AdaptiveIconDrawable
        assertNotNull(icon.foreground)
        assertNotNull(icon.background)
        assertNotNull(icon.monochrome)
    }
}
