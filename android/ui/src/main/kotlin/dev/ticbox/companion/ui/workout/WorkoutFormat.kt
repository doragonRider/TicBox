package dev.ticbox.companion.ui.workout

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.currentLocale
import dev.ticbox.companion.ui.common.formatDecimal
import java.util.Locale
import kotlin.math.roundToLong

@Composable
@ReadOnlyComposable
internal fun sportName(code: Long?): String {
    val name = code?.let(::sportNameResource)
    return when {
        name != null -> stringResource(name)
        code == null || code == -1L -> stringResource(R.string.sport_unknown)
        else -> stringResource(R.string.sport_code, code)
    }
}

internal class WorkoutFormatter(private val resources: Resources, private val locale: Locale) {
    /** Effective training time as h:mm:ss. */
    fun elapsed(millis: Long): String {
        val seconds = millis / 1000
        return String.format(
            locale,
            "%d:%02d:%02d",
            seconds / 3600,
            seconds / 60 % 60,
            seconds % 60,
        )
    }

    /** Pace from seconds per kilometer. */
    fun pace(secondsPerKm: Double): String {
        val seconds = secondsPerKm.roundToLong()
        return resources.getString(R.string.value_pace, seconds / 60, seconds % 60)
    }

    /** Pace without its unit, short enough for a chart axis. */
    fun paceAxis(secondsPerKm: Double): String {
        val seconds = secondsPerKm.roundToLong()
        return resources.getString(R.string.value_pace_axis, seconds / 60, seconds % 60)
    }

    fun pace100(secondsPer100: Double): String {
        val seconds = secondsPer100.roundToLong()
        return resources.getString(R.string.value_pace_100m, seconds / 60, seconds % 60)
    }

    /** Speed from meters per second. */
    fun speed(metersPerSecond: Double): String =
        resources.getString(R.string.value_speed, formatDecimal(metersPerSecond * 3.6, locale))

    fun meters(meters: Double): String =
        resources.getString(R.string.value_meters, formatDecimal(meters, locale))

    fun number(value: Double, fractionDigits: Int = 1): String =
        formatDecimal(value, locale, fractionDigits)
}

@Composable
@ReadOnlyComposable
internal fun workoutFormatter() = WorkoutFormatter(LocalResources.current, currentLocale())
