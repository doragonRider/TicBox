package dev.ticbox.companion.healthbridge.ui

import android.os.Bundle
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.ticbox.companion.healthbridge.HealthExportSelection
import dev.ticbox.companion.healthbridge.R
import dev.ticbox.companion.healthbridge.parseExportTime
import dev.ticbox.companion.protocol.LocalHealthTable

@Stable
internal class ExportSource(val table: LocalHealthTable, saved: Bundle?) {
    var selected by
        mutableStateOf(saved?.getBoolean("enabled_${table.name}") ?: isSelectedByDefault(table))
    var types by mutableStateOf(saved?.getString("types_${table.name}").orEmpty())
}

private fun isSelectedByDefault(table: LocalHealthTable) =
    table == LocalHealthTable.POINT || table == LocalHealthTable.SESSION

/** What the user has entered so far. It is only read when they start an export. */
@Stable
internal class ExportForm(saved: Bundle?) {
    val sources = LocalHealthTable.entries.map { ExportSource(it, saved) }
    var from by mutableStateOf(saved?.getString("from").orEmpty())
    var to by mutableStateOf(saved?.getString("to").orEmpty())
    var maximum by mutableStateOf(saved?.getString("maximum") ?: "200")
    var redaction by mutableStateOf(saved?.getBoolean("redaction") ?: true)

    fun useSleepAndOxygenPreset() {
        sources.forEach { source ->
            source.selected = isSelectedByDefault(source.table)
            source.types =
                when (source.table) {
                    LocalHealthTable.POINT -> "2,28"
                    LocalHealthTable.SESSION -> "100"
                    else -> ""
                }
        }
    }

    /** Null if something entered can't be used. */
    fun selection(): HealthExportSelection? =
        try {
            val tables =
                sources
                    .filter { it.selected }
                    .associate { source ->
                        source.table to
                            source.types
                                .trim()
                                .split(TYPE_SEPARATORS)
                                .filter { it.isNotEmpty() }
                                .map { it.toLong() }
                                .toSet()
                    }
            val fromMillis = parseExportTime(from, false)
            val toMillis = parseExportTime(to, true)
            val rows = maximum.trim().toInt()
            val backwards = fromMillis != null && toMillis != null && fromMillis > toMillis
            if (tables.isEmpty() || rows !in 1..5000 || backwards) {
                null
            } else {
                HealthExportSelection(tables, fromMillis, toMillis, rows, redaction)
            }
        } catch (_: Exception) {
            null
        }

    fun save(outState: Bundle) {
        sources.forEach { source ->
            outState.putBoolean("enabled_${source.table.name}", source.selected)
            outState.putString("types_${source.table.name}", source.types)
        }
        outState.putString("from", from)
        outState.putString("to", to)
        outState.putString("maximum", maximum)
        outState.putBoolean("redaction", redaction)
    }

    private companion object {
        val TYPE_SEPARATORS = Regex("[,，;；\\s]+")
    }
}

@get:StringRes
private val LocalHealthTable.label
    get() =
        when (this) {
            LocalHealthTable.POINT -> R.string.export_table_point
            LocalHealthTable.SESSION -> R.string.export_table_session
            LocalHealthTable.SPORT -> R.string.export_table_sport
            LocalHealthTable.FITNESS_RECORD -> R.string.export_table_fitness_record
            LocalHealthTable.FITNESS_POINT -> R.string.export_table_fitness_point
        }

/** Null where a source has no types to filter by, as with a workout's points. */
@get:StringRes
private val LocalHealthTable.typesHint
    get() =
        when (this) {
            LocalHealthTable.POINT -> R.string.export_point_types
            LocalHealthTable.SESSION -> R.string.export_session_types
            LocalHealthTable.SPORT,
            LocalHealthTable.FITNESS_RECORD -> R.string.export_sport_types
            LocalHealthTable.FITNESS_POINT -> null
        }

@Composable
internal fun HealthExportScreen(
    form: ExportForm,
    running: Boolean,
    cancelling: Boolean,
    status: String?,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val list = rememberTransformingLazyColumnState()
    // The result appears at the end of the list, right above the button that was just tapped.
    LaunchedEffect(status != null) {
        if (status != null) {
            withFrameNanos {}
            list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1)
        }
    }
    BridgeScreen(
        state = list,
        edgeButton = {
            if (running) {
                EdgeButton(
                    onClick = onCancel,
                    enabled = !cancelling,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                ) {
                    Text(stringResource(R.string.export_cancel))
                }
            } else {
                EdgeButton(onClick = onStart) { Text(stringResource(R.string.export_start)) }
            }
        },
    ) {
        row { Title(stringResource(R.string.export_open)) }
        row { Note(stringResource(R.string.export_intro)) }
        row { Section(stringResource(R.string.export_sources)) }
        form.sources.forEach { source ->
            row(key = source.table) {
                Check(
                    label = stringResource(source.table.label),
                    checked = source.selected,
                    onCheckedChange = { source.selected = it },
                    enabled = !running,
                )
            }
            val typesHint = source.table.typesHint
            if (source.selected && typesHint != null) {
                row(key = "${source.table}-types") {
                    Field(
                        label = stringResource(R.string.export_types),
                        value = source.types,
                        onValueChange = { source.types = it },
                        placeholder = stringResource(R.string.export_types_all),
                        hint = stringResource(typesHint),
                        enabled = !running,
                    )
                }
            }
        }
        row {
            Action(
                stringResource(R.string.export_sleep_oxygen_preset),
                form::useSleepAndOxygenPreset,
                enabled = !running,
            )
        }
        row { Section(stringResource(R.string.export_range)) }
        row {
            Field(
                label = stringResource(R.string.export_from),
                value = form.from,
                onValueChange = { form.from = it },
                placeholder = stringResource(R.string.export_range_any),
                enabled = !running,
            )
        }
        row {
            Field(
                label = stringResource(R.string.export_to),
                value = form.to,
                onValueChange = { form.to = it },
                placeholder = stringResource(R.string.export_range_any),
                enabled = !running,
            )
        }
        row { Note(stringResource(R.string.export_range_note)) }
        row { Section(stringResource(R.string.export_options)) }
        row {
            Field(
                label = stringResource(R.string.export_maximum),
                value = form.maximum,
                onValueChange = { form.maximum = it },
                hint = stringResource(R.string.export_maximum_range),
                enabled = !running,
                keyboardType = KeyboardType.Number,
            )
        }
        row {
            Toggle(
                label = stringResource(R.string.export_redact),
                summary = stringResource(R.string.export_redact_summary),
                checked = form.redaction,
                onCheckedChange = { form.redaction = it },
                enabled = !running,
            )
        }
        row { Note(stringResource(R.string.export_notes)) }
        status?.let { text ->
            row(key = "status") { Note(text, color = MaterialTheme.colorScheme.onSurface) }
        }
    }
}
