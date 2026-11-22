package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class DndLink(val peer: PeerAddress, val sessionId: Long)
data class DndSettings(val mode: DndSyncMode = DndSyncMode.OFF, val revision: Long = 0, val ownedPeers: Set<String> = emptySet()) {
    val releasePending: Boolean get() = mode == DndSyncMode.OFF && ownedPeers.isNotEmpty()
}
interface DndSettingsStore { fun load(): DndSettings; fun save(settings: DndSettings) }
fun interface DndRemote { suspend fun apply(link: DndLink, revision: Long, active: Boolean): Boolean }

/** Latest-state convergence. Stale acknowledgements cannot change the selected watch's state. */
class DndSyncEngine(scope: CoroutineScope, private val source: StateFlow<Boolean?>, private val store: DndSettingsStore, private val remote: DndRemote,
    private val retryMillis: Long = 3_000) : DndSyncClient {
    private val lifetime = SupervisorJob(scope.coroutineContext[Job])
    private val worker = CoroutineScope(scope.coroutineContext + lifetime)
    private val settings = MutableStateFlow(store.load())
    private val link = MutableStateFlow<DndLink?>(null)
    private val refreshes = MutableStateFlow(0L)
    private val mutableState = MutableStateFlow(DndSyncState(mode = settings.value.mode))
    override val state: StateFlow<DndSyncState> = mutableState.asStateFlow()
    init {
        worker.launch {
            combine(settings.map { it.mode }.distinctUntilChanged(), source, link, refreshes) { mode, phone, target, retry ->
                Desired(mode, phone, target, retry)
            }.collectLatest { desired ->
                val target = desired.target
                mutableState.value = mutableState.value.copy(mode = desired.mode, phoneDnd = desired.phone, connected = target != null,
                    pending = (target == null && desired.mode == DndSyncMode.FOLLOW_PHONE) || settings.value.releasePending, error = null)
                if (target == null || (desired.mode == DndSyncMode.OFF && target.peer.value !in settings.value.ownedPeers)) return@collectLatest
                if (desired.mode == DndSyncMode.FOLLOW_PHONE && desired.phone == null) {
                    mutableState.value = mutableState.value.copy(pending = true, error = "notification_listener_unavailable"); return@collectLatest
                }
                val active = desired.mode == DndSyncMode.FOLLOW_PHONE && desired.phone == true
                var attempt = 0
                while (currentCoroutineContext().isActive) try {
                    val revision = synchronized(settings) {
                    // Persist ownership before sending: a lost acknowledgement may still have
                    // activated the rule, and switching to OFF must later release that same watch.
                    val next = settings.value.copy(revision = Math.addExact(settings.value.revision, 1),
                        ownedPeers = if (active) settings.value.ownedPeers + target.peer.value else settings.value.ownedPeers)
                    store.save(next); settings.value = next; next.revision
                }
                    mutableState.value = mutableState.value.copy(pending = true)
                    val applied = remote.apply(target, revision, active)
                    if (link.value != target) return@collectLatest
                    if (!applied) synchronized(settings) {
                        val next = settings.value.copy(ownedPeers = settings.value.ownedPeers - target.peer.value); store.save(next); settings.value = next
                    }
                    mutableState.value = mutableState.value.copy(watchRuleActive = applied,
                        pending = applied != active || settings.value.releasePending, error = if (applied == active) null else "watch_dnd_mismatch")
                    break
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    val code = (error as? ToolboxException)?.code ?: "dnd_sync_failed"
                    mutableState.value = mutableState.value.copy(pending = true, error = code)
                    if (code in setOf("notification_policy_access_required", "dnd_superseded") || error !is java.io.IOException) break
                    delay((retryMillis * (1L shl attempt.coerceAtMost(4))).coerceAtMost(60_000)); attempt++
                }
            }
        }
    }
    fun attach(value: DndLink?) {
        if (link.value != value) mutableState.value = mutableState.value.copy(connected = value != null, watchRuleActive = null)
        link.value = value
    }
    override suspend fun setMode(mode: DndSyncMode) = synchronized(settings) {
        val previous = settings.value
        val next = previous.copy(mode = mode)
        store.save(next); settings.value = next
    }
    override suspend fun refresh() { refreshes.value++ }
    override fun close() { lifetime.cancel() }
    private data class Desired(val mode: DndSyncMode, val phone: Boolean?, val target: DndLink?, val retry: Long)
}
