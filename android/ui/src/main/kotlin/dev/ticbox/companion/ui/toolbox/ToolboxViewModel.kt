package dev.ticbox.companion.ui.toolbox

import androidx.lifecycle.viewModelScope
import dev.ticbox.companion.api.ToolboxApp
import dev.ticbox.companion.api.ToolboxBackendKind
import dev.ticbox.companion.api.ToolboxBackendState
import dev.ticbox.companion.api.ToolboxCapability
import dev.ticbox.companion.api.ToolboxException
import dev.ticbox.companion.api.ToolboxPhase
import dev.ticbox.companion.api.ToolboxScreenEncoder
import dev.ticbox.companion.api.ToolboxTarget
import dev.ticbox.companion.api.WatchToolboxBackend
import dev.ticbox.companion.api.WatchToolboxClient
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.common.SessionViewModel
import java.util.WeakHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

// Keep acceptance with the live pair of transports, including while its navigation graph is gone.
private val deviceSelections = WeakHashMap<WatchToolboxClient, MutableStateFlow<String?>>()

internal fun toolboxDeviceSelection(client: WatchToolboxClient): MutableStateFlow<String?> =
    synchronized(deviceSelections) { deviceSelections.getOrPut(client) { MutableStateFlow(null) } }

/** Which backends are connected, and the one the tools use. */
data class ToolboxAccess(
    val adb: ToolboxBackendState = ToolboxBackendState(ToolboxBackendKind.ADB),
    val rootBridge: ToolboxBackendState = ToolboxBackendState(ToolboxBackendKind.ROOT_BRIDGE),
    val preferred: ToolboxBackendKind? = null,
    val deviceChanged: Boolean = false,
) {
    val bothConnected: Boolean
        get() = adb.phase == ToolboxPhase.CONNECTED && rootBridge.phase == ToolboxPhase.CONNECTED

    /** With both connected, root by default: it can do everything ADB can and more. */
    val selected: ToolboxBackendKind?
        get() =
            when {
                bothConnected -> preferred ?: ToolboxBackendKind.ROOT_BRIDGE
                adb.phase == ToolboxPhase.CONNECTED -> ToolboxBackendKind.ADB
                rootBridge.phase == ToolboxPhase.CONNECTED -> ToolboxBackendKind.ROOT_BRIDGE
                else -> null
            }

    val connected: Boolean
        get() = selected != null && !deviceChanged

    val target: ToolboxTarget?
        get() = (if (selected == ToolboxBackendKind.ADB) adb else rootBridge).target

    /** Some operations exist only with root, whichever backend claims to be connected. */
    fun has(capability: ToolboxCapability): Boolean {
        val state = if (selected == ToolboxBackendKind.ADB) adb else rootBridge
        return connected && state.capabilities?.features?.contains(capability) == true
    }
}

/** Shared by the toolbox pages, so a backend chosen on the first is the one the others use. */
class ToolboxViewModel(dependencies: CompanionDependencies) : SessionViewModel(dependencies) {
    private val preferred = MutableStateFlow<ToolboxBackendKind?>(null)

    private fun checked(client: WatchToolboxClient, value: ToolboxAccess): ToolboxAccess {
        val acceptedDevice = toolboxDeviceSelection(client)
        val id = value.target?.deviceId
        val previous =
            (if (value.selected == ToolboxBackendKind.ADB) value.adb else value.rootBridge)
                .previousDeviceId
        if (id != null && acceptedDevice.value == null) acceptedDevice.value = previous ?: id
        return value.copy(
            deviceChanged = previous != null || id != null && acceptedDevice.value != id
        )
    }

    val access: StateFlow<ToolboxAccess?> =
        sessionState(
            current = {
                checked(
                    it.services.toolbox,
                    ToolboxAccess(
                        it.services.toolbox.adb.state.value,
                        it.services.toolbox.localBridge.state.value,
                        preferred.value,
                    ),
                )
            },
            updates = { session ->
                combine(
                    session.services.toolbox.adb.state,
                    session.services.toolbox.localBridge.state,
                    preferred,
                    toolboxDeviceSelection(session.services.toolbox),
                ) { adb, rootBridge, chosen, _ ->
                    checked(session.services.toolbox, ToolboxAccess(adb, rootBridge, chosen))
                }
            },
        )

    /** What the watch already said. Null only without a session, when there's no watch to ask. */
    internal fun memory(): ToolboxMemory? = session.value?.services?.toolbox?.let(::toolboxMemory)

    /** What the device's screen encoders declared, as Remote Screen last read them. */
    val screenEncoders: StateFlow<Map<ToolboxTarget, Map<Int, List<ToolboxScreenEncoder>>>?> =
        sessionState(
            current = { toolboxMemory(it.services.toolbox).screenEncoders.value },
            updates = { toolboxMemory(it.services.toolbox).screenEncoders },
        )

    /** Asks the device once per connection and size; it runs a short process there each time. */
    suspend fun readScreenEncoders(
        target: ToolboxTarget,
        maxSize: Int,
    ): List<ToolboxScreenEncoder> {
        val memory = memory() ?: throw ToolboxException("not_connected")
        memory.screenEncoders.value[target]?.get(maxSize)?.let {
            return it
        }
        return backend(target).screenEncoders(maxSize).also {
            memory.rememberScreenEncoders(target, maxSize, it)
        }
    }

    /** The connection tools would use right now, if they may. */
    fun currentTarget(): ToolboxTarget? = runCatching {
        backend()
    }
        .getOrNull()
        ?.state
        ?.value
        ?.target

    /** The Local Bridge on the watch, once something has listed the watch's apps. */
    val bridgeOnWatch: StateFlow<ToolboxApp?> =
        sessionState(
            current = { toolboxMemory(it.services.toolbox).bridgeOnWatch.value },
            updates = { toolboxMemory(it.services.toolbox).bridgeOnWatch },
        )

    /** Lists the watch's apps once per connection, which also has App Manager's list ready. */
    fun checkBridge() {
        val backend = runCatching { backend() }.getOrNull() ?: return
        val target = backend.state.value.target ?: return
        val memory = memory() ?: return
        if (memory.apps(target) != null) return
        viewModelScope.launch {
            runCatching { memory.rememberApps(target, backend.apps()) }
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    fun selectedBackend(): ToolboxBackendKind? {
        val toolbox = session.value?.services?.toolbox ?: return null
        return ToolboxAccess(
                toolbox.adb.state.value,
                toolbox.localBridge.state.value,
                preferred.value,
            )
            .selected
    }

    /** The backend to run a tool on. A dropped connection surfaces as a message, not a crash. */
    fun backend(expected: ToolboxTarget? = null): WatchToolboxBackend {
        val toolbox = session.value?.services?.toolbox
        val backend =
            when (selectedBackend()) {
                ToolboxBackendKind.ADB -> toolbox?.adb
                ToolboxBackendKind.ROOT_BRIDGE -> toolbox?.localBridge
                null -> null
            } ?: throw ToolboxException("not_connected")
        val target =
            backend.state.value.target ?: throw ToolboxException("device_identity_required")
        val acceptedDevice = toolboxDeviceSelection(checkNotNull(toolbox))
        if (backend.state.value.previousDeviceId != null) throw ToolboxException("device_changed")
        if (acceptedDevice.value == null) acceptedDevice.value = target.deviceId
        if (acceptedDevice.value != target.deviceId) throw ToolboxException("device_changed")
        if (expected != null && target != expected) throw ToolboxException("connection_replaced")
        return backend.bind(target)
    }

    fun confirmDevice(target: ToolboxTarget? = access.value?.target) {
        val reviewed = target ?: return
        command(CONFIRM_DEVICE) { session ->
            val toolbox = session.services.toolbox
            val backend =
                if (reviewed.kind == ToolboxBackendKind.ADB) toolbox.adb else toolbox.localBridge
            backend.confirmDevice(reviewed)
            toolboxDeviceSelection(toolbox).value = reviewed.deviceId
            null
        }
    }

    fun select(kind: ToolboxBackendKind) {
        preferred.value = kind
    }

    fun connectLocalBridge() =
        command(CONNECT_BRIDGE) {
            reportingTimeouts { it.services.toolbox.localBridge.connect() }
            null
        }

    fun disconnectLocalBridge() =
        command(CONNECT_BRIDGE) {
            it.services.toolbox.localBridge.disconnect()
            null
        }

    companion object {
        const val CONNECT_BRIDGE = "toolbox_connect_bridge"
        const val CONFIRM_DEVICE = "toolbox_confirm_device"
    }
}
