package dev.ticbox.companion.engine

import dev.ticbox.companion.api.*
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Capability ownership is its inner URI AppKey; the outer DataApi namespace is GMS. */
class CompanionCapabilities(parentScope: CoroutineScope, private val data: DataSyncClient,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE) : AutoCloseable {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val worker = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val wakeup = MutableStateFlow(0L)
    private val mutex = Mutex()
    private val desired = MutableStateFlow<Set<String>>(emptySet())
    private val managed = mutableSetOf<String>()
    private val reconciled = mutableMapOf<String, Boolean>()
    private var reconciledSession = 0L
    @Volatile private var session: CompanionFeatureSession? = null
    private val mutableWatch = MutableStateFlow<Set<String>>(emptySet())
    val watch = mutableWatch.asStateFlow()
    init { worker.launch { combine(data.state, desired, wakeup) { sync, _, _ -> sync }.collect { sync ->
        val current = session ?: return@collect
        if (sync.sessionId == current.sessionId && sync.scope == current.scope && sync.phase == DataSyncPhase.ACTIVE)
            mutex.withLock { reconcile(current) }
    } } }
    fun attach(next: CompanionFeatureSession) { session = next; mutableWatch.value = emptySet(); wakeup.update { it + 1 } }
    fun detach(sessionId: Long) { if (session?.sessionId == sessionId) { session = null; mutableWatch.value = emptySet() } }
    fun setPhoneCapability(name: String, enabled: Boolean) {
        synchronized(managed) { managed += name }
        desired.update { if (enabled) it + name else it - name }; wakeup.update { it + 1 }
    }
    private suspend fun reconcile(current: CompanionFeatureSession) {
        try {
            if (reconciledSession != current.sessionId) { reconciled.clear(); reconciledSession = current.sessionId }
            val rows = ArrayList<DataItemRecord>(); var offset = 0
            for (node in setOf(current.scope.localNodeId, current.scope.remoteNodeId)) {
                offset = 0
                do {
                    val page = data.items(current.scope, DataItemQuery(route = GMS_ROUTE,
                        uriPrefix = "wear://$node/capabilities/", limit = 500, offset = offset))
                    rows += page.items
                    val next = page.nextOffset ?: break
                    if (next <= offset || rows.size >= 10_000) break
                    offset = next
                } while (true)
            }
            if (session !== current) return
            mutableWatch.value = rows.filter { !it.local && it.sourceNodeId == current.scope.remoteNodeId }
                .mapNotNull { row ->
                    val path = runCatching { URI(row.uri).rawPath }.getOrNull() ?: return@mapNotNull null
                    if (!path.startsWith("/capabilities/")) return@mapNotNull null
                    val parts = path.split('/').takeIf { it.size == 5 } ?: return@mapNotNull null
                    if (parts[2] != current.companionRoute.packageName || parts[3] != current.companionRoute.packageDigest) return@mapNotNull null
                    val name = decodeName(parts.last())
                    // A withdrawn DataItem is excluded by the query; capability names use URI double encoding.
                    if (row.data?.firstOrNull() !in listOf(0x64.toByte(), 0x73.toByte()))
                        runCatching { diagnostics.record(ProtocolDiagnostic("capabilities", "nonstandard_dynamic_marker",
                            peer = current.peer, sessionId = current.sessionId, details = mapOf("bytes" to (row.data?.size ?: 0).toString()))) }
                    name
                }.toSet()
            for (name in synchronized(managed) { managed.toList() }) {
                val wanted = name in desired.value
                if (reconciled[name] == wanted) continue
                val uri = "wear://${current.scope.localNodeId}/capabilities/${current.companionRoute.packageName}/${current.companionRoute.packageDigest}/${encodeName(name)}"
                val previous = rows.lastOrNull { it.uri == uri && it.local }
                if (session !== current) return
                var submitted = false
                if (wanted) {
                    if (previous?.data?.contentEquals(byteArrayOf(0x64)) != true) {
                        data.publish(current.scope, GMS_ROUTE, DataItemDraft(uri, byteArrayOf(0x64)))
                        submitted = true
                    }
                } else if (previous != null) { data.delete(current.scope, GMS_ROUTE, uri); submitted = true }
                reconciled[name] = wanted
                runCatching { diagnostics.record(ProtocolDiagnostic("capabilities", "phone_capability_reconciled",
                    peer = current.peer, sessionId = current.sessionId, details = mapOf("name" to name,
                        "enabled" to wanted.toString(), "submitted" to submitted.toString(),
                        "decision" to "local DataItem reconciled; watch connection status remains independently observed"))) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (problem: Exception) { runCatching { diagnostics.record(ProtocolDiagnostic("capabilities", "reconcile_failed",
            peer = current.peer, sessionId = current.sessionId, details = mapOf("exception" to problem.javaClass.simpleName))) } }
    }
    override fun close() { session = null; lifetime.cancel(); mutableWatch.value = emptySet() }
    companion object {
        val GMS_ROUTE = ApplicationRoute("com.google.android.gms", "38918a453d07199354f8b19af05ec6562ced5788")
        internal fun encodeName(name: String): String {
            fun encode(value: String) = buildString {
                value.toByteArray(Charsets.UTF_8).forEach { byte ->
                    val unsigned = byte.toInt() and 255; val character = unsigned.toChar()
                    if (character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character in "_-!.~'()*") append(character)
                    else append("%%%02X".format(unsigned))
                }
            }
            return encode(encode(name))
        }
        internal fun decodeName(segment: String): String = runCatching {
            // URLDecoder's form convention must not turn a literal plus into a space.
            fun decode(value: String) = URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)
            decode(decode(segment))
        }.getOrDefault(segment)
    }
}
