package dev.ticbox.companion.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class DndSyncMode { OFF, FOLLOW_PHONE }
data class DndSyncState(
    val mode: DndSyncMode = DndSyncMode.OFF,
    val phoneDnd: Boolean? = null,
    val watchRuleActive: Boolean? = null,
    val connected: Boolean = false,
    val pending: Boolean = false,
    val error: String? = null,
)
interface DndSyncClient : AutoCloseable {
    val state: StateFlow<DndSyncState>
    suspend fun setMode(mode: DndSyncMode)
    suspend fun refresh()
    companion object {
        val UNAVAILABLE = object : DndSyncClient {
            override val state = MutableStateFlow(DndSyncState())
            override suspend fun setMode(mode: DndSyncMode) { throw ToolboxException("dnd_sync_unavailable") }
            override suspend fun refresh() = Unit
            override fun close() = Unit
        }
    }
}
