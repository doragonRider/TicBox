package dev.ticbox.companion.ui.health

import java.time.Duration
import java.time.Instant
import java.util.TreeMap

/**
 * Thirty-minute totals within one local calendar day. The caller supplies its actual start/end, so
 * a daylight-saving day may contain 46 or 50 buckets. Empty buckets remain absent.
 *
 * An interval's value is spread by actual elapsed time; overlapping intervals use the highest
 * contribution rate, matching the observed vendor aggregation without counting duplicates twice.
 * Zero-duration readings (and climbing heights) are assigned to their timestamp's bucket.
 */
internal fun activityBuckets(
    readings: List<HealthPoint>,
    start: Instant,
    end: Instant,
): List<HealthPoint> {
    require(end > start)
    val bounds = mutableListOf(start)
    while (bounds.last() < end) bounds += minOf(bounds.last().plusSeconds(30 * 60), end)
    val totals = DoubleArray(bounds.size - 1)
    val observed = BooleanArray(totals.size)
    val events = TreeMap<Instant, MutableList<Pair<Double, Int>>>()
    bounds.forEach { events[it] = mutableListOf() }
    fun bucket(time: Instant): Int =
        bounds.binarySearch(time).let { found -> if (found >= 0) found else -found - 2 }
    readings.forEach { reading ->
        val finish = reading.endExclusive
        if (finish == null || finish == reading.time) {
            if (reading.time >= start && reading.time < end) {
                val index = bucket(reading.time)
                totals[index] += reading.value
                observed[index] = true
            }
        } else if (finish > reading.time && finish > start && reading.time < end) {
            val a = maxOf(reading.time, start)
            val b = minOf(finish, end)
            val rate = reading.value / elapsedMillis(reading.time, finish)
            events.getOrPut(a) { mutableListOf() } += rate to 1
            events.getOrPut(b) { mutableListOf() } += rate to -1
        }
    }
    val rates = TreeMap<Double, Int>()
    var previous = start
    events.forEach { (time, changes) ->
        if (time > previous && rates.isNotEmpty()) {
            val index = bucket(previous)
            totals[index] += rates.lastKey() * elapsedMillis(previous, time)
            observed[index] = true
        }
        changes.forEach { (rate, change) ->
            val count = (rates[rate] ?: 0) + change
            if (count == 0) rates.remove(rate) else rates[rate] = count
        }
        previous = time
    }
    return totals.indices
        .filter { observed[it] }
        .map { index ->
            HealthPoint(bounds[index], totals[index], endExclusive = bounds[index + 1])
        }
}

/**
 * [buckets] added up into hours counted from [start], the day's own start, so a daylight-saving day
 * has 23 or 25 of them. Hours with nothing recorded stay absent.
 */
internal fun hourlyTotals(
    buckets: List<HealthPoint>,
    start: Instant,
    end: Instant,
): List<HealthPoint> =
    buckets
        .filter { it.time >= start && it.time < end }
        .groupBy { Duration.between(start, it.time).toHours() }
        .toSortedMap()
        .map { (hour, points) ->
            val from = start.plus(Duration.ofHours(hour))
            HealthPoint(
                from,
                points.sumOf { it.value },
                endExclusive = minOf(from.plus(Duration.ofHours(1)), end),
            )
        }

private fun elapsedMillis(start: Instant, end: Instant): Double =
    Duration.between(start, end).let { it.seconds.toDouble() * 1000 + it.nano / 1_000_000.0 }
