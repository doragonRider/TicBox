package dev.ticbox.companion.platform.settings

import android.annotation.SuppressLint
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.text.format.DateFormat

/** Phone preferences sent to the watch must not inherit this app's per-app language override. */
class PhoneSystemPreferences(private val context: Context) {
    private val locales
        get() = context.getSystemService(LocaleManager::class.java).systemLocales

    val languageTags: String
        get() = locales.toLanguageTags()

    // Lint fears a locale switch without App Bundle language splits. This library can't see that
    // :app turns the splits off, and this context only reads the system time format.
    @get:SuppressLint("AppBundleLocaleChanges")
    val is24Hour: Boolean
        get() {
            val primary = locales[0] ?: java.util.Locale.US
            val systemLocales = android.os.LocaleList(primary)
            val systemContext =
                context.createConfigurationContext(
                    Configuration(context.resources.configuration).apply {
                        setLocales(systemLocales)
                    }
                )
            return DateFormat.is24HourFormat(systemContext)
        }
}
