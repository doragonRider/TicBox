package dev.ticbox.companion.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class HealthModuleBundle(val versionName: String, val versionCode: Long, val buildId: String, val sha256: String, val bytes: Long)
data class HealthModuleImage(
    val buildId: String?, val versionName: String?, val versionCode: Long?,
    val matchesBundled: Boolean, val disabled: Boolean, val removalPending: Boolean,
)
/** Disk state and a response from the injected TicHealth process are independent observations. */
data class HealthModuleSnapshot(
    val target: ToolboxTarget, val magiskVersionCode: Int?, val sdk: Int, val abi: String,
    val installed: HealthModuleImage?, val staged: HealthModuleImage?,
    val healthRuntimeBuildId: String?, val runtimeIssue: String?,
    val lastInstallBuildId: String? = null, val lastInstallOutcome: HealthModuleOutcome? = null,
) {
    val rebootRequired: Boolean get() = staged != null
}
enum class HealthModulePhase { DISCONNECTED, READY, READING, INSTALLING, UNAVAILABLE, CLOSED }
enum class HealthModuleOutcome { READ, UP_TO_DATE, STAGED_REBOOT_REQUIRED, REJECTED, UNCONFIRMED }
data class HealthModuleResult(val outcome: HealthModuleOutcome, val reason: String? = null)
data class HealthModuleState(
    val phase: HealthModulePhase = HealthModulePhase.DISCONNECTED,
    val bundle: HealthModuleBundle? = null, val snapshot: HealthModuleSnapshot? = null,
    val current: Boolean = false, val lastOperation: HealthModuleResult? = null,
)
/**
 * Explicitly installs only the phone's bundled module on its currently paired watch through root
 * Local Bridge. No automatic install, reboot, Zygisk/DenyList changes or notification-patch opt-in.
 * Cancellation/transport loss after submission leaves UNCONFIRMED: refresh before deciding to retry.
 * A staged install is not proof the module has loaded; inspect healthRuntimeBuildId after reboot.
 */
interface HealthModuleClient : AutoCloseable {
    val state: StateFlow<HealthModuleState>
    /** Reads the phone asset without contacting a watch or requesting root. */
    suspend fun bundled(): HealthModuleBundle
    suspend fun refresh(): HealthModuleResult
    suspend fun installBundled(progress: (ToolboxProgress) -> Unit = {}): HealthModuleResult
    override fun close()
    companion object {
        val UNAVAILABLE = object : HealthModuleClient {
            override val state = MutableStateFlow(HealthModuleState(phase = HealthModulePhase.UNAVAILABLE))
            override suspend fun bundled(): HealthModuleBundle = throw ToolboxException("module_unavailable")
            override suspend fun refresh() = HealthModuleResult(HealthModuleOutcome.REJECTED, "module_unavailable")
            override suspend fun installBundled(progress: (ToolboxProgress) -> Unit) = refresh()
            override fun close() = Unit
        }
    }
}
