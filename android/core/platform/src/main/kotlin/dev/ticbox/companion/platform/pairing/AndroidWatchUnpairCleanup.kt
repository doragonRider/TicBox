package dev.ticbox.companion.platform.pairing

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import dev.ticbox.companion.api.DiagnosticSeverity
import dev.ticbox.companion.api.DiagnosticSink
import dev.ticbox.companion.api.PairingActionKind
import dev.ticbox.companion.api.PairingAssociation
import dev.ticbox.companion.api.PairingFailure
import dev.ticbox.companion.api.PairingFailureKind
import dev.ticbox.companion.api.PairingWorkflowException
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.api.ProtocolDiagnostic
import dev.ticbox.companion.engine.pairing.AssociationMappingStore
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Local cleanup after the caller stops this watch's pairing/reconnect work and closes its link.
 * Confirms Bluetooth unpairing before removing this app's associations and address mappings.
 * Health records and the local node identity are deliberately outside this class's capabilities.
 */
class AndroidWatchUnpairCleanup internal constructor(
    private val platform: WatchUnpairPlatform,
    private val mappings: AssociationMappingStore,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
) {
    constructor(
        context: Context,
        hostActions: AndroidPairingActions,
        mappings: AssociationMappingStore,
        diagnostics: DiagnosticSink,
    ) : this(AndroidUnpairPlatform(context.applicationContext, hostActions, diagnostics), mappings, diagnostics)

    private val commands = Mutex()

    /** The caller supplies its deadline. Cancellation releases this operation's observer/UI token. */
    suspend fun clear(peer: PeerAddress) {
        try {
            commands.withLock {
                currentCoroutineContext().ensureActive()
                val operationId = operationIds.getAndIncrement()
                check(operationId > 0) { "Unpair operation ID exhausted" }
                platform.ready()
                val bonds = platform.openBonds()
                try {
                    log(peer, operationId, "watch_unpair_cleanup_started")
                    bonds.capture(operationId)
                    // System addresses take priority. For address-less AssociationInfo, only a
                    // previously stored mapping for this app-owned ID can bind it to this peer.
                    val saved = mappings.all().associateBy { it.associationId }
                    fun matching(associations: List<PairingAssociation>): List<PairingAssociation> =
                        associations.filter { (it.peer ?: saved[it.id]?.peer) == peer }
                            .map { it.copy(peer = peer) }

                    val owned = matching(platform.associations())
                    if (readState(peer) != BondState.NONE) {
                        if (owned.isNotEmpty()) {
                            val cleared = bonds.clear(operationId, owned.first())
                            if (!cleared && readState(peer) != BondState.NONE) {
                                throw unpairFailure(PairingFailureKind.BOND, "watch_bond_changed_forget_in_settings_required")
                            }
                        } else {
                            // A Bluetooth bond alone does not authorize CDM.removeBond. The
                            // ordinary settings Activity is the supported fallback on every OS.
                            log(peer, operationId, "watch_unpair_settings_required")
                            platform.forgetInSettings(operationId, peer)
                        }
                    }
                    requireNone(peer)
                    log(peer, operationId, "watch_unpair_bond_none")

                    // Settings may already have removed an association. Refresh ownership, and
                    // check the bond again before every destructive association operation.
                    for (association in matching(platform.associations())) {
                        currentCoroutineContext().ensureActive()
                        requireNone(peer)
                        platform.disassociate(association)
                        mappings.remove(association.id)
                    }
                    if (matching(platform.associations()).isNotEmpty()) {
                        throw unpairFailure(PairingFailureKind.ASSOCIATION, "watch_association_cleanup_incomplete")
                    }
                    // Remove only this peer's stale local rows, including the no-association case.
                    for (mapping in mappings.all().filter { it.peer == peer }) {
                        currentCoroutineContext().ensureActive()
                        requireNone(peer)
                        mappings.remove(mapping.associationId)
                    }
                    requireNone(peer)
                    log(peer, operationId, "watch_unpair_cleanup_completed")
                } finally {
                    runCatching { bonds.cancel(operationId) }
                    runCatching { bonds.close() }
                    runCatching { platform.cancelUi(operationId) }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (known: PairingWorkflowException) {
            throw known
        } catch (denied: SecurityException) {
            throw unpairFailure(PairingFailureKind.PERMISSION_DENIED, "bluetooth_permission_denied")
        } catch (problem: Exception) {
            throw unpairFailure(PairingFailureKind.ASSOCIATION, "watch_unpair_cleanup_${problem.javaClass.simpleName}")
        }
    }

    private fun requireNone(peer: PeerAddress) {
        if (readState(peer) != BondState.NONE) {
            throw unpairFailure(PairingFailureKind.BOND, "bluetooth_device_not_forgotten")
        }
    }

    private fun readState(peer: PeerAddress): BondState {
        platform.ready()
        val state = platform.state(peer)
        platform.ready() // Bluetooth going off can make getBondState return NONE.
        return state
    }

    private fun log(peer: PeerAddress, operationId: Long, event: String) {
        runCatching { diagnostics.record(ProtocolDiagnostic("pairing_android", event, DiagnosticSeverity.INFO,
            peer = peer, details = mapOf("operationId" to operationId.toString()))) }
    }

    private companion object {
        // Shared with neither PairingCoordinator's low-numbered IDs nor another cleanup instance.
        val operationIds = AtomicLong(1L shl 50)
    }
}

/** Android access and the existing bond controller are injected, without duplicating its waiter. */
internal interface WatchUnpairPlatform {
    fun ready()
    fun associations(): List<PairingAssociation>
    fun state(peer: PeerAddress): BondState
    fun openBonds(): AndroidPairingBonds
    suspend fun forgetInSettings(operationId: Long, peer: PeerAddress)
    fun disassociate(association: PairingAssociation)
    fun cancelUi(operationId: Long)
}

private class AndroidUnpairPlatform(
    private val context: Context,
    private val hostActions: AndroidPairingActions,
    private val diagnostics: DiagnosticSink,
) : WatchUnpairPlatform {
    private fun adapter(): BluetoothAdapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        ?: throw unpairFailure(PairingFailureKind.PLATFORM_UNAVAILABLE, "bluetooth_adapter_unavailable")

    private fun manager(): CompanionDeviceManager = context.getSystemService(CompanionDeviceManager::class.java)
        ?: throw unpairFailure(PairingFailureKind.PLATFORM_UNAVAILABLE, "companion_device_manager_unavailable")

    @SuppressLint("MissingPermission")
    override fun ready() {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("BLUETOOTH_CONNECT not granted")
        }
        if (!adapter().isEnabled) throw unpairFailure(PairingFailureKind.BOND, "bluetooth_disabled_during_bond")
    }

    @SuppressLint("MissingPermission")
    override fun associations(): List<PairingAssociation> = manager().myAssociations.map(::describe)

    @SuppressLint("MissingPermission")
    override fun state(peer: PeerAddress): BondState = when (adapter().getRemoteDevice(peer.value).bondState) {
        BluetoothDevice.BOND_NONE -> BondState.NONE
        BluetoothDevice.BOND_BONDING -> BondState.BONDING
        BluetoothDevice.BOND_BONDED -> BondState.BONDED
        else -> throw unpairFailure(PairingFailureKind.BOND, "bluetooth_bond_state_unavailable")
    }

    override fun openBonds(): AndroidPairingBonds = AndroidPairingBonds(context, hostActions, diagnostics)

    override suspend fun forgetInSettings(operationId: Long, peer: PeerAddress) {
        hostActions.activity(operationId, PairingActionKind.FORGET_BLUETOOTH_DEVICE,
            "forget_selected_watch_in_bluetooth_settings", Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    @SuppressLint("MissingPermission")
    override fun disassociate(association: PairingAssociation) {
        ready()
        val current = manager().myAssociations.firstOrNull { it.id == association.id } ?: return
        val actualPeer = describe(current).peer
        if (actualPeer != null && actualPeer != association.peer) {
            throw unpairFailure(PairingFailureKind.ASSOCIATION, "association_device_changed")
        }
        // Do not turn a new bond appearing during cleanup into a disconnected CDM association.
        val peer = association.peer
        if (peer == null || state(peer) != BondState.NONE) {
            throw unpairFailure(PairingFailureKind.BOND, "bluetooth_device_not_forgotten")
        }
        ready()
        manager().disassociate(current.id)
    }

    override fun cancelUi(operationId: Long) = hostActions.cancel(operationId)

    @SuppressLint("MissingPermission")
    private fun describe(info: AssociationInfo): PairingAssociation {
        val address = info.deviceMacAddress?.toString() ?: info.associatedDevice?.bluetoothDevice?.address
        return PairingAssociation(info.id, address?.let(::PeerAddress), info.displayName?.toString(),
            info.deviceProfile == AssociationRequest.DEVICE_PROFILE_WATCH)
    }
}

private fun unpairFailure(kind: PairingFailureKind, reason: String) = PairingWorkflowException(PairingFailure(kind, reason))
