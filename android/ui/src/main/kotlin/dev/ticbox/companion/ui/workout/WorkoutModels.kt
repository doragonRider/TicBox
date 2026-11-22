package dev.ticbox.companion.ui.workout

import androidx.annotation.DrawableRes
import dev.ticbox.companion.api.HealthFitnessRecord
import dev.ticbox.companion.ui.designsystem.AppIcons

/** How a sport reads: which headline numbers suit it. Actual data still decides what is shown. */
internal enum class SportKind(
    @param:DrawableRes val icon: Int,
    val showsPace: Boolean = false,
    val showsSpeed: Boolean = false,
    val showsDistance: Boolean = false,
    val showsSplits: Boolean = false,
) {
    RUN(AppIcons.Running, showsPace = true, showsDistance = true, showsSplits = true),
    WALK(AppIcons.Walking, showsSpeed = true, showsDistance = true, showsSplits = true),
    RIDE(AppIcons.Cycling, showsSpeed = true, showsDistance = true),
    POOL(AppIcons.Swimming, showsDistance = true),
    OPEN_WATER(AppIcons.Swimming, showsDistance = true),
    HIIT(AppIcons.Strength),
    INTERVALS(AppIcons.Strength),
    COUNTED(AppIcons.Strength),
    FIELD(AppIcons.Sports, showsDistance = true),
    OTHER(AppIcons.Sports),
}

internal fun sportKind(code: Long?): SportKind =
    when (code) {
        0L,
        2L,
        6L,
        7L,
        18L,
        132L -> SportKind.RUN
        1L,
        5L,
        8L,
        17L,
        135L -> SportKind.WALK
        3L,
        10L,
        12L,
        13L,
        14L,
        23L,
        77L,
        94L,
        101L,
        102L,
        110L,
        112L,
        113L,
        117L,
        133L,
        134L,
        136L,
        154L -> SportKind.RIDE
        9L,
        11L -> SportKind.POOL
        55L -> SportKind.OPEN_WATER
        24L -> SportKind.HIIT
        4L,
        140L -> SportKind.INTERVALS
        19L,
        25L,
        28L,
        30L,
        63L -> SportKind.COUNTED
        21L,
        22L,
        56L,
        60L,
        96L,
        103L,
        156L,
        157L -> SportKind.FIELD
        else -> SportKind.OTHER
    }

/** One workout in the list. Vendor placeholders are already missing values here. */
data class WorkoutSummary(
    /** The stored record's key, to read it again. */
    val key: String,
    /** Set when the full record exists; a summary-only row has no samples to open. */
    val recordId: Long?,
    val typeCode: Long?,
    val startMillis: Long?,
    val endMillis: Long?,
    val durationMillis: Long?,
    val distanceMeters: Long?,
    val calories: Double?,
    val steps: Long?,
    val averageHeartRate: Long?,
    val autoDetected: Boolean?,
)

internal fun HealthFitnessRecord.toSummary() =
    WorkoutSummary(
        key = raw.key,
        recordId = id,
        typeCode = typeCode,
        startMillis = startMillis?.takeIf { it > 0 },
        endMillis = endMillis?.takeIf { it > 0 },
        durationMillis = durationMillis?.takeIf { it >= 0 },
        distanceMeters = distanceMeters?.takeIf { it >= 0 },
        calories = calorieKcalDecimal?.toDoubleOrNull()?.takeIf { it >= 0 },
        steps = steps?.takeIf { it >= 0 },
        averageHeartRate = averageHeartRateBpm?.takeIf { it > 0 },
        autoDetected = autoDetected,
    )
