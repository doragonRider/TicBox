package dev.ticbox.companion.ui.health

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
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
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.designsystem.AppIcons
import dev.ticbox.companion.ui.designsystem.EmptyState
import dev.ticbox.companion.ui.designsystem.FootnoteText
import dev.ticbox.companion.ui.designsystem.ListGroup
import dev.ticbox.companion.ui.designsystem.PageColumn
import dev.ticbox.companion.ui.designsystem.PageScaffold
import dev.ticbox.companion.ui.designsystem.ValueRow
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The newest stored value of [type], as confirmed by the backend. */
internal suspend fun RecordAccess.latestValue(type: HealthPointType): Double? =
    client
        .pointRows(source, type, from = null, to = null, maxPages = 10)
        .items
        .latest()
        ?.values
        ?.firstOrNull { it.index == 0 && it.semantic != null }
        ?.decimal
        ?.toDoubleOrNull()

private fun List<HealthPointRecord>.latest() = filter {
    it.deleted != true
}
    .maxByOrNull { it.endMillis ?: it.startMillis ?: Long.MIN_VALUE }

data class WatchProfileUiState(
    val hasSource: Boolean,
    val loading: Boolean = true,
    val values: Map<HealthPointType, Double> = emptyMap(),
    val failed: Boolean = false,
)

class WatchProfileViewModel(dependencies: CompanionDependencies) :
    LocalHealthViewModel(dependencies) {
    private val current = MutableStateFlow(WatchProfileUiState(hasSource = source.value != null))
    val state = current.asStateFlow()

    init {
        viewModelScope.launch {
            recordAccess.collectLatest { access ->
                if (access == null) {
                    current.value = WatchProfileUiState(hasSource = false, loading = false)
                    return@collectLatest
                }
                current.update { it.copy(hasSource = true, loading = true) }
                current.value =
                    try {
                        val values =
                            ProfileGroups.flatMap { it.types }
                                .mapNotNull { type ->
                                    access.latestValue(type)?.let { type to it }
                                }
                        WatchProfileUiState(true, loading = false, values = values.toMap())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        current.value.copy(loading = false, failed = true)
                    }
            }
        }
    }
}

private class ProfileGroup(@param:StringRes val title: Int, val types: List<HealthPointType>)

private val ProfileGroups =
    listOf(
        ProfileGroup(
            R.string.profile_personal,
            // Not gender: its code means different things depending on what wrote it.
            listOf(
                HealthPointType.INFO_BIRTHDAY,
                HealthPointType.INFO_HEIGHT,
                HealthPointType.INFO_WEIGHT,
            ),
        ),
        ProfileGroup(
            R.string.profile_goals,
            listOf(
                HealthPointType.GOAL_TODAY_STEP,
                HealthPointType.GOAL_TODAY_ACTIVE,
                HealthPointType.GOAL_TODAY_EXERCISE,
            ),
        ),
        ProfileGroup(
            R.string.profile_schedule,
            listOf(HealthPointType.EXPECTED_SLEEP_TIME, HealthPointType.EXPECTED_GET_UP_TIME),
        ),
        ProfileGroup(
            R.string.profile_heart_limits,
            listOf(
                HealthPointType.HEART_RATE_WARNING_FITNESS_UPPER_LIMIT,
                HealthPointType.HEART_RATE_WARNING_REST_UPPER_LIMIT,
                HealthPointType.HEART_RATE_WARNING_REST_LOWER_LIMIT,
            ),
        ),
    )

private val HealthPointType.profileLabel: Int
    get() =
        when (this) {
            HealthPointType.INFO_BIRTHDAY -> R.string.profile_birthday
            HealthPointType.INFO_HEIGHT -> R.string.profile_height
            HealthPointType.INFO_WEIGHT -> R.string.profile_weight
            HealthPointType.GOAL_TODAY_STEP -> R.string.profile_goal_steps
            HealthPointType.GOAL_TODAY_ACTIVE -> R.string.profile_goal_active
            HealthPointType.GOAL_TODAY_EXERCISE -> R.string.profile_goal_exercise
            HealthPointType.EXPECTED_SLEEP_TIME -> R.string.profile_expected_sleep
            HealthPointType.EXPECTED_GET_UP_TIME -> R.string.profile_expected_get_up
            HealthPointType.HEART_RATE_WARNING_FITNESS_UPPER_LIMIT ->
                R.string.profile_limit_fitness_upper
            HealthPointType.HEART_RATE_WARNING_REST_UPPER_LIMIT -> R.string.profile_limit_rest_upper
            else -> R.string.profile_limit_rest_lower
        }

@Composable
private fun profileValue(type: HealthPointType, value: Double): String {
    val locale = currentLocale()
    val formatter = metricFormatter()
    return when (type) {
        HealthPointType.INFO_BIRTHDAY ->
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(locale)
                .format(Instant.ofEpochSecond(value.toLong()).atZone(ZoneId.systemDefault()))
        HealthPointType.INFO_HEIGHT ->
            stringResource(R.string.value_centimeters, formatDecimal(value, locale))
        HealthPointType.INFO_WEIGHT ->
            stringResource(R.string.value_kilograms, formatDecimal(value, locale))
        HealthPointType.GOAL_TODAY_STEP ->
            pluralStringResource(
                R.plurals.value_steps,
                value.toInt(),
                formatNumber(value.toInt(), locale),
            )
        HealthPointType.GOAL_TODAY_ACTIVE ->
            stringResource(R.string.value_hours, formatDecimal(value, locale))
        HealthPointType.GOAL_TODAY_EXERCISE -> formatter.duration((value / 60).toLong())
        HealthPointType.EXPECTED_SLEEP_TIME,
        HealthPointType.EXPECTED_GET_UP_TIME ->
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                .withLocale(locale)
                .format(LocalTime.ofSecondOfDay((value.toLong() / 1000).mod(86_400L)))
        else -> formatter.value(HealthMetric.HEART, value)
    }
}

/** What the watch has stored about its wearer: read-only, and independent of any account. */
@Composable
fun WatchProfileScreen(onBack: () -> Unit) {
    val viewModel = companionViewModel { WatchProfileViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    PageScaffold(stringResource(R.string.profile_title), onBack = onBack) { padding ->
        if (!state.hasSource) {
            EmptyState(
                AppIcons.Person,
                stringResource(R.string.health_no_watch_title),
                stringResource(R.string.health_no_watch_body),
                Modifier.padding(padding),
            )
            return@PageScaffold
        }
        PageColumn(padding) {
            when {
                state.failed -> ReadStateBanner(HealthReadState.FAILED, loading = false)
                !state.loading && state.values.isEmpty() ->
                    ReadStateBanner(HealthReadState.EMPTY, loading = false)
            }
            ProfileGroups.forEach { group ->
                ListGroup(title = stringResource(group.title)) {
                    group.types.forEach { type ->
                        val value = state.values[type]
                        ValueRow(
                            stringResource(type.profileLabel),
                            if (value == null) "–" else profileValue(type, value),
                        )
                    }
                }
            }
            FootnoteText(stringResource(R.string.profile_note))
        }
    }
}
