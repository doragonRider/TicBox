package dev.ticbox.companion.ui.health

import androidx.annotation.DrawableRes
import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.ticbox.companion.api.HealthPointRecord
import dev.ticbox.companion.api.HealthPointType
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.currentLocale
import dev.ticbox.companion.ui.common.formatDecimal
import dev.ticbox.companion.ui.common.formatNumber
import dev.ticbox.companion.ui.common.formatTime
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.BusyBar
import dev.ticbox.companion.ui.designsystem.EmptyState
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.PageList
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.Reveal
import dev.ticbox.companion.ui.designsystem.ValueRow
import dev.ticbox.companion.ui.workout.sportName
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Events the watch records, shown as a dated list. Also a navigation argument. */
@Keep
enum class HealthLog(
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int,
    @param:StringRes val about: Int,
    @param:DrawableRes val icon: Int,
    val types: List<HealthPointType>,
) {
    BODY_MIND(
        R.string.log_body_mind,
        R.string.log_body_mind_summary,
        R.string.log_body_mind_about,
        AppIcons.Energy,
        listOf(HealthPointType.BODY_MIND_STATE),
    ),
    HEART_HEALTH(
        R.string.log_heart_health,
        R.string.log_heart_health_summary,
        R.string.log_heart_health_about,
        AppIcons.HeartMonitor,
        listOf(HealthPointType.HEART_HEALTH),
    ),
    HEART_WARNINGS(
        R.string.log_heart_warnings,
        R.string.log_heart_warnings_summary,
        R.string.log_heart_warnings_about,
        AppIcons.Warning,
        listOf(HealthPointType.HEART_RATE_WARNING),
    ),
    HIGH_STRENGTH(
        R.string.log_high_strength,
        R.string.log_high_strength_summary,
        R.string.log_high_strength_about,
        AppIcons.Strength,
        listOf(HealthPointType.HIGH_STRENGTH_TRAINING),
    ),
    MOOD(
        R.string.log_mood,
        R.string.log_mood_summary,
        R.string.log_mood_about,
        AppIcons.Mood,
        listOf(HealthPointType.TODAY_MOOD),
    ),
    ARTY(
        R.string.log_arty,
        R.string.log_arty_summary,
        R.string.log_arty_about,
        AppIcons.Suggest,
        listOf(HealthPointType.ARTY_DATA),
    ),
    CYCLE(
        R.string.log_cycle,
        R.string.log_cycle_summary,
        R.string.log_cycle_about,
        AppIcons.Drop,
        listOf(
            HealthPointType.PERIOD_EVENT,
            HealthPointType.PERIOD_CYCLE_LENGTH,
            HealthPointType.PERIOD_MENSTRUATION_LENGTH,
        ),
    );

    companion object {
        /** How far back a log's page lists events. */
        const val WINDOW_DAYS = 90L
    }
}

data class HealthLogUiState(
    val hasSource: Boolean,
    val loading: Boolean = true,
    /** Newest first. */
    val rows: List<HealthPointRecord> = emptyList(),
    val state: HealthReadState? = null,
)

class HealthLogViewModel(dependencies: CompanionDependencies, private val log: HealthLog) :
    LocalHealthViewModel(dependencies) {
    private val current = MutableStateFlow(HealthLogUiState(hasSource = source.value != null))
    val state = current.asStateFlow()

    init {
        viewModelScope.launch {
            recordAccess.collectLatest { access ->
                if (access == null) {
                    current.value = HealthLogUiState(hasSource = false, loading = false)
                } else {
                    current.update { it.copy(hasSource = true, loading = true) }
                    current.value = read(access)
                }
            }
        }
    }

    private suspend fun read(access: RecordAccess): HealthLogUiState =
        try {
            val from = System.currentTimeMillis() - HealthLog.WINDOW_DAYS * 86_400_000
            val results =
                log.types.map {
                    access.client.pointRows(access.source, it, from, null, maxPages = 4)
                }
            val rows =
                results
                    .flatMap { it.items }
                    .filter { it.deleted != true && it.endMillis != null }
                    .sortedByDescending { it.endMillis }
            HealthLogUiState(
                hasSource = true,
                loading = false,
                rows = rows,
                state =
                    when {
                        results.any { !it.available } && rows.isEmpty() ->
                            HealthReadState.UNAVAILABLE
                        results.any { !it.available || it.limited } ||
                            rows.any { it.issues.isNotEmpty() } -> HealthReadState.PARTIAL
                        rows.isEmpty() -> HealthReadState.EMPTY
                        else -> HealthReadState.AVAILABLE
                    },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            current.value.copy(loading = false, state = HealthReadState.FAILED)
        }
}

@Composable
fun HealthLogScreen(log: HealthLog, onBack: () -> Unit) {
    val viewModel = companionViewModel { HealthLogViewModel(it, log) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    PageScaffold(stringResource(log.title), onBack = onBack) { padding ->
        if (!state.hasSource) {
            EmptyState(
                log.icon,
                stringResource(R.string.health_no_watch_title),
                stringResource(R.string.health_no_watch_body),
                Modifier.padding(padding),
            )
            return@PageScaffold
        }
        val days =
            state.rows.groupBy { row ->
                Instant.ofEpochMilli(checkNotNull(row.endMillis)).atZone(zone).toLocalDate()
            }
        PageList(padding) {
            item(key = "status") {
                Column {
                    BusyBar(state.loading)
                    Reveal(!state.loading) { ReadStateBanner(state.state, loading = false) }
                }
            }
            items(days.entries.toList(), key = { it.key.toEpochDay() }) { (date, rows) ->
                ListGroup(title = dayLabel(date, today)) {
                    rows.forEach { row -> LogRow(log, row) }
                }
            }
            item(key = "about") { FootnoteText(stringResource(log.about)) }
        }
    }
}

@Composable
private fun LogRow(log: HealthLog, row: HealthPointRecord) {
    val time = row.endMillis?.let { formatTime(it, currentLocale()) }.orEmpty()
    ValueRow(logText(log, row) ?: stringResource(R.string.log_unreadable), time)
}

private fun HealthPointRecord.slot(semantic: String): String? =
    values.firstOrNull { it.semantic == semantic }?.decimal

private fun HealthPointRecord.slotLong(semantic: String): Long? =
    slot(semantic)?.toDoubleOrNull()?.toLong()

/** One event in words; null when the row does not carry the confirmed fields. */
@Composable
private fun logText(log: HealthLog, row: HealthPointRecord): String? {
    val locale = currentLocale()
    val formatter = metricFormatter()
    return when (log) {
        HealthLog.BODY_MIND -> {
            val level = row.slot("level") ?: return null
            val label =
                when (row.slotLong("body_mind_type")) {
                    1L -> R.string.log_body_mind_fatigue
                    2L -> R.string.log_body_mind_energy
                    else -> R.string.log_body_mind_other
                }
            stringResource(R.string.log_body_mind_row, stringResource(label), level)
        }
        HealthLog.HEART_HEALTH -> {
            val code = row.slot("heart_health_type") ?: return null
            // A heart rate of 0 may be a placeholder, so it is not shown as a measurement.
            val bpm = row.slot("heart_rate")?.toDoubleOrNull()?.takeIf { it > 0 }
            val heart = bpm?.let { formatter.value(HealthMetric.HEART, it) }
            if (heart == null) stringResource(R.string.log_heart_health_code, code)
            else stringResource(R.string.log_heart_health_row, code, heart)
        }
        HealthLog.HEART_WARNINGS -> {
            val code = row.slotLong("heart_rate_warning") ?: return null
            val label =
                when (code) {
                    16L -> R.string.log_heart_warning_fitness_upper
                    17L -> R.string.log_heart_warning_rest_upper
                    18L -> R.string.log_heart_warning_rest_lower
                    else -> null
                }
            if (label == null) stringResource(R.string.log_heart_warning_other, code)
            else stringResource(label)
        }
        HealthLog.HIGH_STRENGTH -> {
            val millis = row.slotLong("high_intensity_duration") ?: return null
            stringResource(
                R.string.log_high_strength_row,
                sportName(row.slotLong("sport_type")),
                formatter.durationMillis(millis),
            )
        }
        HealthLog.MOOD -> {
            val code = row.slotLong("today_mood") ?: return null
            stringResource(
                when (code) {
                    0L -> R.string.log_mood_excited
                    1L -> R.string.log_mood_happy
                    2L -> R.string.log_mood_calm
                    3L -> R.string.log_mood_sad
                    4L -> R.string.log_mood_angry
                    else -> R.string.log_mood_unknown
                }
            )
        }
        HealthLog.ARTY -> {
            val parts = buildList {
                row.slot("arty_score")?.let {
                    add(
                        stringResource(
                            R.string.log_arty_score,
                            formatDecimal(it.toDouble(), locale),
                        )
                    )
                }
                row.slot("tru_hr")?.toDoubleOrNull()?.let {
                    add(
                        stringResource(
                            R.string.log_arty_true_heart,
                            formatNumber(it.toInt(), locale),
                        )
                    )
                }
                row.slot("ecap")?.let {
                    add(
                        stringResource(R.string.log_arty_ecap, formatDecimal(it.toDouble(), locale))
                    )
                }
                row.slot("hsx")?.let {
                    add(stringResource(R.string.log_arty_hsx, formatDecimal(it.toDouble(), locale)))
                }
                val low = row.slot("arty_age_lower")
                val high = row.slot("arty_age_upper")
                if (low != null && high != null) {
                    add(stringResource(R.string.log_arty_age, low, high))
                }
            }
            parts.joinToString(" · ").ifEmpty { null }
        }
        HealthLog.CYCLE ->
            when (row.type) {
                HealthPointType.PERIOD_EVENT ->
                    stringResource(
                        when (row.slotLong("period_event")) {
                            0L -> R.string.log_cycle_start
                            1L -> R.string.log_cycle_end
                            else -> R.string.log_cycle_unknown
                        }
                    )
                HealthPointType.PERIOD_CYCLE_LENGTH ->
                    row.slot("period_cycle_length")?.let {
                        stringResource(R.string.log_cycle_length, it)
                    }
                else ->
                    row.slot("period_menstruation_length")?.let {
                        stringResource(R.string.log_cycle_menstruation, it)
                    }
            }
    }
}
