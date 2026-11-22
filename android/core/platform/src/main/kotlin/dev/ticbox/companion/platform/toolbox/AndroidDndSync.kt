package dev.ticbox.companion.platform.toolbox

import android.content.Context
import android.util.AtomicFile
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.toolbox.*
import dev.ticbox.companion.protocol.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

internal class AtomicDndSettings(context: Context) : DndSettingsStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "companion/dnd-v1.json").apply { parentFile!!.mkdirs() })
    @Synchronized override fun load(): DndSettings {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return DndSettings()
        return file.openRead().use { stream -> ToolboxProtocol.json.parseToJsonElement(stream.readBytes().toString(Charsets.UTF_8)).jsonObject }.let { value ->
            DndSettings(DndSyncMode.valueOf(value.text("mode") ?: error("dnd_settings_corrupt")),
                (value.number("revision") ?: error("dnd_settings_corrupt")).also { require(it >= 0) },
                (value["ownedPeers"] as? JsonArray).orEmpty().map { (it as JsonPrimitive).content }.toSet())
        }
    }
    @Synchronized override fun save(settings: DndSettings) {
        val bytes = buildJsonObject { put("mode", settings.mode.name); put("revision", settings.revision); putJsonArray("ownedPeers") { settings.ownedPeers.sorted().forEach { add(it) } } }.toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (error: Throwable) { file.failWrite(stream); throw error }
    }
}

internal class AndroidDndRemote(private val send: suspend (DndLink, ByteArray) -> Unit) : DndRemote, AutoCloseable {
    private data class Pending(val link: DndLink, val revision: Long, val result: CompletableDeferred<Boolean>)
    private val pending = ConcurrentHashMap<String, Pending>()
    override suspend fun apply(link: DndLink, revision: Long, active: Boolean): Boolean {
        val id = UUID.randomUUID().toString(); val request = Pending(link, revision, CompletableDeferred()); pending[id] = request
        try {
            send(link, buildJsonObject { put("requestId", id); put("revision", revision); put("active", active) }.toString().toByteArray(Charsets.UTF_8))
            return try { withTimeout(75_000) { request.result.await() } } catch (timeout: TimeoutCancellationException) { throw ToolboxException("dnd_ack_timeout", timeout) }
        } finally { pending.remove(id); request.result.cancel() }
    }
    fun accept(peer: PeerAddress, sessionId: Long, bytes: ByteArray) {
        val value = runCatching { ToolboxProtocol.json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }.getOrNull() ?: return
        val item = pending[value.text("requestId")] ?: return
        if (item.link.peer != peer || item.link.sessionId != sessionId || item.revision != value.number("revision")) return
        val error = value.text("error")
        if (error != null) item.result.completeExceptionally(ToolboxException(error))
        else value.flag("active")?.let { item.result.complete(it) }
    }
    override fun close() { pending.values.forEach { it.result.cancel() }; pending.clear() }
}
