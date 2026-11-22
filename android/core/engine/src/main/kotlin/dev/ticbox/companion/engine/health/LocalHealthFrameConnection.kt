package dev.ticbox.companion.engine.health

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import java.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The phone's side of one connection to the watch's bridge, after its `ready` reply. A bridge whose `ready` says
 * `pipeline` 2 gets the table's next request as soon as a page arrives, naming that page as `after`, so the watch sends
 * the next one while this side saves. That request is used only if this side then committed the page it followed;
 * otherwise its reply is read and dropped, and the watch sends those rows again later.
 */
class LocalHealthFrameConnection(
    private val frames: LocalHealthFrames,
    private val ready: JsonObject,
    /** Receiver for checkpoints that don't name one. */
    private val receiver: String,
    override val setupStages: List<LocalHealthSyncStage> = emptyList(),
    private val onClose: () -> Unit = {},
) : LocalHealthConnection {
    override val servesAllTables = true
    override val supportedTables = LocalHealthProtocol.supportedTables(ready)
    private val pipelined = (ready.number("pipeline") ?: 0) >= LocalHealthProtocol.PIPELINE_VERSION

    /**
     * A batch already committed here whose ACK goes with the table's next request, saving a round trip per page.
     * If that request never goes out, the watch resends the batch and the phone's commit repeats harmlessly.
     */
    private var held: Pair<LocalHealthTable, String>? = null
    /** The last batch committed here, as the request sent ahead must follow it. */
    private var committed: Pair<LocalHealthTable, String>? = null

    private data class Scan(val table: LocalHealthTable, val receiver: String, val run: String, val full: Boolean, val window: Long?)
    private class Ahead(val scan: Scan, val after: String)
    private var ahead: Ahead? = null

    private suspend fun send(table: LocalHealthTable, batch: String) {
        frames.write(LocalHealthProtocol.acknowledge(receiver, table, batch))
        val result = frames.read()
        if (result.text("batch") != batch || result.flag("committed") != true)
            throw IOException("Local health acknowledgement was not persisted")
    }

    /** Reads and drops the reply to a request sent ahead that won't be used; its rows stay queued on the watch. */
    private suspend fun settle() {
        if (ahead == null) return
        ahead = null
        frames.read()
    }

    override suspend fun page(table: LocalHealthTable, checkpoint: LocalHealthCheckpoint): LocalHealthPage {
        val scan = Scan(table, checkpoint.receiver ?: receiver, checkpoint.run ?: error("Missing acquisition run"),
            checkpoint.full, checkpoint.window)
        val reply = ahead?.takeIf { it.scan == scan && committed == table to it.after }?.let { ahead = null; frames.read() } ?: run {
            settle()
            held?.takeIf { it.first != table }?.let { (other, batch) -> held = null; send(other, batch) }
            val ack = held?.second; held = null
            frames.write(LocalHealthProtocol.changes(table, scan.receiver, scan.run, scan.full, scan.window, ack, zlib = true))
            frames.read()
        }
        val page = LocalHealthProtocol.page(reply, frames.lastFrameBytes.toLong())
        val batch = page.batchId
        if (pipelined && page.error == null && !page.complete && page.inlineAck && batch != null) {
            // The held ACK is for the page before this one, which was committed before this was asked for.
            val ack = held?.takeIf { it.first == table }?.second?.also { held = null }
            frames.write(LocalHealthProtocol.changes(table, scan.receiver, scan.run, scan.full, scan.window, ack, zlib = true, after = batch))
            ahead = Ahead(scan, batch)
        }
        return page
    }

    override suspend fun acknowledge(table: LocalHealthTable, page: LocalHealthPage) {
        val batch = page.batchId ?: return
        committed = table to batch
        // The last page of a table has no next request to carry its ACK. On a pipelined bridge it also stands for the
        // page before it, whose ACK is still held.
        if (page.inlineAck && !page.complete) { held = table to batch; return }
        settle()
        if (pipelined && held?.first == table) held = null
        send(table, batch)
    }

    override suspend fun journal(source: LocalHealthJournalSource, enabled: Boolean?): LocalHealthJournalStatus {
        if (ready.number("journalVersion") != 1L) throw LocalHealthJournalException("journal_bridge_update_required")
        settle()
        frames.write(buildJsonObject {
            put("op", "journal"); put("source", source.wireName)
            put("action", when (enabled) { null -> "status"; true -> "enable"; false -> "disable" })
        })
        val result = frames.read()
        if (result.text("op") != "journal" || result.text("source") != source.wireName)
            throw LocalHealthJournalException("journal_invalid_reply")
        return LocalHealthJournalStatus(source, result.flag("enabledForSync") == true, result.flag("installed"),
            result.number("version")?.toInt(), result.number("floor"), result.number("head"),
            result.text("error")?.takeIf { it.matches(Regex("[a-z_]{1,80}")) })
    }

    override fun close() = onClose()
}
