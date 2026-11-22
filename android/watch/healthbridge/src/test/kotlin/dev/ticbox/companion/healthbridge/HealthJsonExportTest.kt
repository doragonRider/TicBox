package dev.ticbox.companion.healthbridge

import android.os.CancellationSignal
import dev.ticbox.companion.protocol.*
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class HealthJsonExportTest {
    private fun row(id: Long, type: Long = 28, start: Long = 100, end: Long = start) = buildJsonObject {
        put("id", id.toString())
        put("columns", buildJsonObject {
            put("_id", id); put("id", "sleep-$id"); put("type", type); put("values", "97.5,unknown")
            put("device_id", "watch-A"); put("wwid", "account-A"); put("time_from", start); put("time_to", end)
            put("deleted", 1); put("future_field", buildJsonObject { put("x", 3) }); put("binary", buildJsonObject { put("base64", "AP8=") })
        })
        put("storageTypes", buildJsonObject { put("id", "string"); put("values", "string"); put("binary", "blob") })
    }
    private fun page(vararg rows: JsonObject, complete: Boolean = true, next: Long? = rows.lastOrNull()?.number("id"), upper: Long = 99) =
        localHealthPage(rows.toList(), next, upper, complete, "5.6.0-3234.2686")
    private fun export(selection: HealthExportSelection = HealthExportSelection(mapOf(LocalHealthTable.POINT to emptySet())),
        limits: HealthExportLimits = HealthExportLimits(), elapsed: () -> Long = { 0 },
        cancellation: CancellationSignal = CancellationSignal(), cancelReason: () -> String? = { if (cancellation.isCanceled) "cancelled" else null },
        progress: (LocalHealthTable, Int, Int) -> Unit = { _, _, _ -> },
        reader: (JsonObject, CancellationSignal) -> JsonObject): JsonObject {
        val output = ByteArrayOutputStream()
        val summary = HealthJsonExporter(reader, { buildJsonObject { put("providerVersion", "fixture") } }, limits, elapsed)
            .write(output, selection, cancellation, cancelReason, progress)
        val result = Json.parseToJsonElement(output.toString(Charsets.UTF_8.name())).jsonObject
        assertEquals(summary.runId, result.text("runId"))
        assertEquals(summary.status, result["summary"]!!.jsonObject.text("status"))
        return result
    }
    private fun JsonObject.table(index: Int = 0) = get("tables")!!.jsonArray[index].jsonObject
    private fun JsonObject.rows() = get("rows")!!.jsonArray
    private fun JsonObject.result() = get("result")!!.jsonObject

    @Test fun rawSessionAndFutureFieldsSurviveWithConsistentPerExportIdentityRedaction() {
        val first = row(1, 100); val second = row(2, 2)
        val selection = HealthExportSelection(linkedMapOf(LocalHealthTable.SESSION to emptySet(), LocalHealthTable.POINT to emptySet()))
        val report = export(selection) { request, _ -> if (request.text("table") == "data_session") page(first) else page(second) }
        val session = report.table().rows().single().jsonObject
        val point = report.table(1).rows().single().jsonObject
        val sessionCols = session["columns"]!!.jsonObject
        assertEquals("sleep-1", sessionCols.text("id"))
        assertEquals("97.5,unknown", sessionCols.text("values"))
        assertEquals(first["storageTypes"], session["storageTypes"])
        assertEquals(first["columns"]!!.jsonObject["future_field"], sessionCols["future_field"])
        assertEquals(first["columns"]!!.jsonObject["binary"], sessionCols["binary"])
        assertNotEquals("watch-A", sessionCols.text("device_id"))
        assertEquals(sessionCols["device_id"], point["columns"]!!.jsonObject["device_id"])
        assertNotEquals(sessionCols["device_id"], sessionCols["wwid"])
        assertEquals("watch-A", first["columns"]!!.jsonObject.text("device_id"))
        assertEquals(HealthProviderReader.URI.toString(), report.table().text("queryUri"))
        val again = export(selection) { _, _ -> page(first) }
        assertNotEquals(sessionCols["device_id"], again.table().rows().first().jsonObject["columns"]!!.jsonObject["device_id"])
    }

    @Test fun explicitUnredactedExportPreservesOriginalIdentities() {
        val raw = row(1)
        val report = export(HealthExportSelection(mapOf(LocalHealthTable.POINT to emptySet()), redactIdentities = false)) { _, _ -> page(raw) }
        assertEquals(raw, report.table().rows().single())
        assertTrue(report["redactedColumns"]!!.jsonArray.isEmpty())
    }

    @Test fun absentNullAndEmptyIdentitiesDoNotBecomeInventedAnonymousAccounts() {
        val raw = buildJsonObject {
            put("id", "1"); put("columns", buildJsonObject { put("_id", 1); put("wwid", ""); put("device_id", JsonNull) })
        }
        val report = export { _, _ -> page(raw) }
        assertEquals(raw, report.table().rows().single())
    }

    @Test fun typeAndInclusiveTimeFiltersUseAllPagesAndPreserveDeletedRows() {
        var calls = 0
        val report = export(HealthExportSelection(mapOf(LocalHealthTable.POINT to setOf(28)), 100, 200)) { request, _ ->
            calls++
            if (calls == 1) page(row(1, 1), row(2, 28, 90, 100), complete = false, next = 2)
            else {
                assertEquals(2L, request.number("afterId")); assertEquals(99L, request.number("upperId"))
                page(row(3, 28, 200, 300), row(4, 28, 201), row(5, 28, 0, 99))
            }
        }
        assertEquals(2, calls); assertEquals(listOf("2", "3"), report.table().rows().map { it.jsonObject.text("id") })
        assertEquals(3L, report.table().result().number("rowsFiltered"))
        assertEquals("complete", report.table().result().text("status"))
        assertEquals(1L, report.table().rows()[0].jsonObject["columns"]!!.jsonObject.number("deleted"))
    }

    @Test fun unknownFilterMetadataAndMalformedTimesRemainAvailableWithoutInventedValues() {
        val raw = JsonObject(row(1).toMutableMap().apply { put("columns", buildJsonObject {
            put("type", "not-a-number"); put("time_from", 300); put("time_to", 100); put("values", "NaN,-1,,future")
        }) })
        val report = export(HealthExportSelection(mapOf(LocalHealthTable.POINT to setOf(28)), 100, 200)) { _, _ -> page(raw) }
        assertEquals(raw, report.table().rows().single())
        assertEquals(1L, report.table().result().number("unresolvedFilterRows"))
    }

    @Test fun sportPermissionFailureIsIsolatedFromSuccessfulNonemptySession() {
        val report = export(HealthExportSelection(linkedMapOf(LocalHealthTable.SPORT to emptySet(), LocalHealthTable.SESSION to setOf(100)))) { request, _ ->
            if (request.text("table") == "fitness_summary") queryError("provider_denied") else page(row(1, 100))
        }
        assertEquals("failed", report.table().result().text("status"))
        assertEquals("provider_denied", report.table().result().text("reason"))
        assertEquals(1, report.table(1).rows().size)
        assertEquals("partial", report["summary"]!!.jsonObject.text("status"))
        assertEquals("complete", report.table(1).result().text("status"))
    }

    @Test fun sourceFailureDoesNotExportExceptionMessagesOrPretendToBeEmptySuccess() {
        val report = export { _, _ -> throw IllegalStateException("sensitive-health-value") }
        assertEquals("failed", report["summary"]!!.jsonObject.text("status"))
        assertEquals("reader_failed", report.table().result().text("reason"))
        assertEquals("IllegalStateException", report.table().result().text("exceptionType"))
        assertFalse(report.toString().contains("sensitive-health-value"))
    }

    @Test fun rowLimitAndSuccessfulEmptySourceHaveDifferentCompletionStates() {
        val limited = export(HealthExportSelection(mapOf(LocalHealthTable.POINT to emptySet()), maxRowsPerTable = 1)) { _, _ -> page(row(1), row(2)) }
        assertEquals(1, limited.table().rows().size); assertEquals("row_limit", limited.table().result().text("reason"))
        assertEquals(false, limited.table().result().flag("complete"))
        val empty = export { _, _ -> page() }
        assertEquals("complete", empty["summary"]!!.jsonObject.text("status")); assertTrue(empty.table().rows().isEmpty())
    }

    @Test fun byteBudgetLeavesAValidDocumentAndStopsLaterQueries() {
        val raw = row(1)
        var calls = 0
        val report = export(HealthExportSelection(linkedMapOf(LocalHealthTable.POINT to emptySet(), LocalHealthTable.SESSION to emptySet()), redactIdentities = false),
            HealthExportLimits(rowBytes = raw.toString().toByteArray().size.toLong())) { _, _ -> calls++; page(raw, row(2)) }
        assertEquals(1, calls); assertEquals(1, report.table().rows().size)
        assertEquals("byte_limit", report.table().result().text("reason"))
        assertEquals("byte_limit", report.table(1).result().text("reason"))
    }

    @Test fun scanBudgetTerminatesSparseFilteredHistory() {
        val report = export(HealthExportSelection(mapOf(LocalHealthTable.POINT to setOf(28))),
            HealthExportLimits(scannedRowsPerTable = 2)) { _, _ -> page(row(1, 2), row(2, 2), row(3, 2)) }
        assertTrue(report.table().rows().isEmpty()); assertEquals(2L, report.table().result().number("rowsExamined"))
        assertEquals("scan_limit", report.table().result().text("reason"))
    }

    @Test fun cancellationRetainsReadRowsAndSkipsRemainingSources() {
        val signal = CancellationSignal(); var calls = 0
        val report = export(HealthExportSelection(linkedMapOf(LocalHealthTable.POINT to emptySet(), LocalHealthTable.SESSION to emptySet())),
            cancellation = signal, progress = { _, _, _ -> signal.cancel() }) { _, _ -> calls++; page(row(1), complete = false) }
        assertEquals(1, calls); assertEquals(1, report.table().rows().size)
        assertEquals("cancelled", report["summary"]!!.jsonObject.text("status"))
        assertEquals("cancelled", report.table(1).result().text("reason"))
    }

    @Test fun deadlineAndStalledCursorAreRecordedWithoutAnUnboundedLoop() {
        var now = 0L; var calls = 0
        val expired = export(elapsed = { now }) { _, _ -> calls++; now = 60_001; page(row(1), complete = false) }
        assertEquals(1, calls); assertEquals("deadline", expired.table().result().text("reason"))
        calls = 0
        val stalled = export { _, _ -> calls++; page(complete = false, next = null) }
        assertEquals(1, calls); assertEquals("cursor_not_advanced", stalled.table().result().text("reason"))
    }

    @Test fun dateAndOffsetInputsPreserveTheRequestedWatchLocalDay() {
        val zone = ZoneId.of("Asia/Shanghai")
        val from = parseExportTime("2026-11-03", false, zone)!!
        val end = parseExportTime("2026-11-03", true, zone)!!
        assertEquals(86_400_000L - 1, end - from)
        assertEquals(from, parseExportTime("2026-11-03T00:00:00+08:00", false, ZoneId.of("UTC")))
        assertEquals(from + 30 * 60_000L, parseExportTime("2026-11-03 00:30", false, zone))
        assertEquals(-1000L, parseExportTime("-1000", false, zone))
        assertNull(parseExportTime("", true, zone))
    }
}
