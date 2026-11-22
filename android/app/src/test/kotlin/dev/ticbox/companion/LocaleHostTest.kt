package dev.ticbox.companion

import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Only English and Chinese resources are packaged (localeFilters), so an unsupported system
 * language falls back to English for both app text and library text such as Material dialogs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LocaleHostTest {
    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private val datePickerTitle = androidx.compose.material3.R.string.m3c_date_picker_title
    private val healthTab = dev.ticbox.companion.ui.R.string.tab_health

    @Test
    @Config(qualifiers = "fr-rFR")
    fun frenchSystemShowsEnglishEverywhere() {
        assertEquals("Health", text(healthTab))
        assertEquals("Select date", text(datePickerTitle))
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun simplifiedChineseIsUsedForAppAndLibraryText() {
        assertEquals("健康", text(healthTab))
        assertEquals("选择日期", text(datePickerTitle))
    }

    @Test
    @Config(qualifiers = "zh-rTW")
    fun traditionalChineseHasItsOwnTranslation() {
        assertEquals("健康", text(healthTab))
        assertEquals("設定", text(dev.ticbox.companion.ui.R.string.tab_settings))
    }
}
