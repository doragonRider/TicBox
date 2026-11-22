package dev.ticbox.companion.ui.common

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The locale used for numbers and dates. The app ships English and Chinese only; any other system
 * language falls back to US English so formatting matches the English text around it.
 */
@Composable
@ReadOnlyComposable
fun currentLocale(): Locale = contentLocale(LocalConfiguration.current.locales[0])

fun contentLocale(system: Locale?): Locale =
    system?.takeIf { it.language == "en" || it.language == "zh" } ?: Locale.US

fun formatNumber(value: Number, locale: Locale): String =
    NumberFormat.getIntegerInstance(locale).format(value)

/** Up to [maxFractionDigits] decimals, without trailing zeros. */
fun formatDecimal(value: Number, locale: Locale, maxFractionDigits: Int = 1): String =
    NumberFormat.getNumberInstance(locale)
        .apply { maximumFractionDigits = maxFractionDigits }
        .format(value)

fun formatTime(epochMillis: Long, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(locale)
        .format(Instant.ofEpochMilli(epochMillis).atZone(zone))

fun formatDateTime(
    epochMillis: Long,
    locale: Locale,
    zone: ZoneId = ZoneId.systemDefault(),
): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(locale)
        .format(Instant.ofEpochMilli(epochMillis).atZone(zone))

/** A short date for the locale, e.g. "Mon, Nov 3" or "11月3日周一". */
fun formatDayLabel(date: LocalDate, locale: Locale): String =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEMMMd"), locale)
        .format(date)

/** A compact date for chart axes, e.g. "11/3". */
fun formatShortDate(date: LocalDate, locale: Locale): String =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "Md"), locale)
        .format(date)

/** "5 minutes ago", following the app locale. */
fun formatRelative(epochMillis: Long, now: Long = System.currentTimeMillis()): String =
    DateUtils.getRelativeTimeSpanString(epochMillis, now, DateUtils.MINUTE_IN_MILLIS).toString()

const val MINUTE_MILLIS = 60_000L

/**
 * The current time for text relative to [anchorMillis]. It updates whenever the whole minutes since
 * the anchor change, and when the app returns to the foreground.
 */
@Composable
fun rememberMinuteClock(anchorMillis: Long): Long {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now by
        produceState(System.currentTimeMillis(), anchorMillis, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val current = System.currentTimeMillis()
                    value = current
                    delay(MINUTE_MILLIS - Math.floorMod(current - anchorMillis, MINUTE_MILLIS))
                }
            }
        }
    return now
}
