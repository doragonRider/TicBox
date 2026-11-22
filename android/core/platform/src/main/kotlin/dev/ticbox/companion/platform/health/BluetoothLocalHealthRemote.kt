package dev.ticbox.companion.platform.health

import android.content.Context
import android.os.SystemClock
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.health.*
import dev.ticbox.companion.platform.transport.BluetoothRfcommTransportFactory
import dev.ticbox.companion.platform.identity.AndroidApplicationRoute
import dev.ticbox.companion.protocol.*
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Wake over the existing direct link; all health payloads use a separate secure Bluetooth socket. */
class BluetoothLocalHealthRemote(context: Context,
    private val wake: suspend (LocalHealthLink, ApplicationRoute, ByteArray) -> Unit,
) : LocalHealthRemote {
    private val context = context.applicationContext
    private val random = SecureRandom()
    private val transport = BluetoothRfcommTransportFactory(context, serviceUuid = UUID.fromString(LocalHealthProtocol.RFCOMM_UUID))
    private val route: ApplicationRoute by lazy { AndroidApplicationRoute.read(this.context) }
    override suspend fun open(link: LocalHealthLink): LocalHealthConnection {
        val token = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val setup = SetupTimer()
        setup.measure("wake") {
            wake(link, route, LocalHealthProtocol.wake(token).toString().toByteArray(Charsets.UTF_8))
        }
        // Listener cold startup and SDP registration are asynchronous. Retry this same wake window.
        var socket: DuplexTransport? = null
        CONNECT_WAITS_MILLIS.forEach { wait ->
            if (socket == null) {
                setup.measure("wait") { delay(wait) }
                setup.measure("connect") {
                    try { withTimeout(2_000) { socket = transport.open(link.source.peer) } }
                    catch (timeout: TimeoutCancellationException) { currentCoroutineContext().ensureActive() }
                    catch (_: IOException) { }
                }
            }
        }
        val connected = socket ?: throw IOException("Local health bridge not reachable")
        try {
            val frames = LocalHealthFrames(connected, Dispatchers.Default)
            val reply = setup.measure("handshake") {
                frames.write(LocalHealthProtocol.hello(token))
                frames.read()
            }
            if (reply.text("op") != "ready") throw IOException("Local health bridge handshake failed")
            val version = reply.number("version") ?: 1
            if (version < LocalHealthProtocol.INCREMENTAL_VERSION) throw IOException("health_bridge_update_required")
            return object : LocalHealthConnection {
                override val setupStages = setup.stages()
                override val servesAllTables = true
                override val supportedTables = LocalHealthProtocol.supportedTables(reply)
                override suspend fun page(table: LocalHealthTable, checkpoint: LocalHealthCheckpoint): LocalHealthPage {
                    frames.write(LocalHealthProtocol.changes(table, checkpoint.receiver ?: link.source.localUserId,
                        checkpoint.run ?: error("Missing acquisition run"), checkpoint.full))
                    val reply = frames.read()
                    return LocalHealthProtocol.page(reply, frames.lastFrameBytes.toLong())
                }
                override suspend fun acknowledge(table: LocalHealthTable, page: LocalHealthPage) {
                    val batch = page.batchId ?: return
                    frames.write(LocalHealthProtocol.acknowledge(link.source.localUserId, table, batch))
                    val result = frames.read()
                    if (result.text("batch") != batch || result.flag("committed") != true)
                        throw IOException("Local health acknowledgement was not persisted")
                }
                override suspend fun journal(source: LocalHealthJournalSource, enabled: Boolean?): LocalHealthJournalStatus {
                    if (reply.number("journalVersion") != 1L) throw LocalHealthJournalException("journal_bridge_update_required")
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
                override fun close() = connected.close()
            }
        } catch (problem: Throwable) { connected.close(); throw problem }
    }

    private companion object {
        /** Quick at first, as a failed try has already taken its own time. Spans the old 3.8 seconds. */
        val CONNECT_WAITS_MILLIS = listOf(300L, 150, 150, 300, 500, 500, 500, 500, 500, 500)
    }
}

/** Sums the time spent in each step of opening a connection, for sync diagnostics. */
private class SetupTimer {
    private val stages = linkedMapOf<String, LocalHealthSyncStage>()

    inline fun <T> measure(name: String, block: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        try { return block() } finally {
            val previous = stages[name]
            stages[name] = LocalHealthSyncStage(null, name,
                millis = (previous?.millis ?: 0) + SystemClock.elapsedRealtime() - start,
                count = (previous?.count ?: 0) + 1)
        }
    }

    fun stages() = stages.values.toList()
}
