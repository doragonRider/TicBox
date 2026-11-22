package dev.ticbox.companion.platform.pairing

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bond operations for an explicit pairing workflow. Capture before opening the system chooser;
 * cancel releases that operation's observer. Normal RFCOMM reconnects never use [clear].
 *
 * A pre-existing bond is not evidence of stale keys. The caller decides whether a user-requested
 * replacement is appropriate. System pairing/encryption observed since capture is preserved.
 * Android does not expose an atomic compare-and-remove operation for Bluetooth keys.
 */
class AndroidPairingBonds internal constructor(
    private val platform: PairingBondPlatform,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
) : AutoCloseable {
    constructor(context: Context, hostActions: AndroidPairingActions, diagnostics: DiagnosticSink) :
        this(AndroidBondPlatform(context.applicationContext, hostActions), diagnostics)

    private class Operation(val id: Long) {
        val commands = Mutex()
        val updates = Channel<Unit>(Channel.CONFLATED)
        val changedPeers = mutableSetOf<PeerAddress>()
        val bondFailures = mutableMapOf<PeerAddress, Long>()
        val jobs = mutableSetOf<Job>()
        var revision = 0L
        var failure: PairingFailure? = null
        var observer: AutoCloseable? = null
        var snapshot: Set<PeerAddress>? = null
    }

    private val lock = Any()
    private val operations = mutableMapOf<Long, Operation>()
    private var closed = false

    /** Observer registration precedes the snapshot, including changes delivered during that read. */
    fun capture(operationId: Long): Set<PeerAddress> = translated {
        require(operationId > 0)
        synchronized(lock) {
            check(!closed) { "Pairing bonds are closed" }
            operations[operationId]?.snapshot?.let { return@translated it }
            platform.ready()
            val operation = Operation(operationId)
            operations[operationId] = operation
            try {
                operation.observer = platform.observe { observed(operation, it) }
                platform.ready()
                platform.bondedPeers().toSet().also {
                    platform.ready()
                    active(operation)
                    operation.snapshot = it
                    log(operationId, "bond_snapshot", mapOf("count" to it.size.toString()))
                }
            } catch (problem: Throwable) {
                operations.remove(operationId)
                release(operation)
                throw problem
            }
        }
    }

    /** True means NONE was read while Bluetooth remained enabled; false preserves a changed bond. */
    suspend fun clear(operationId: Long, association: PairingAssociation): Boolean =
        command(operationId) { operation ->
            val peer = association.peer ?: throw bondFailure("bond_device_address_unavailable")
            if (synchronized(lock) { peer !in requireNotNull(operation.snapshot) }) return@command false
            awaitState(operation, peer, BondState.NONE, failOnBondFailure = false) { state ->
                if (state == BondState.BONDING || changed(operation, peer)) {
                    log(operationId, "bond_replacement_preserved_system_change")
                    false
                } else {
                    currentCoroutineContext().ensureActive()
                    active(operation)
                    val requested = try {
                        synchronized(lock) {
                            active(operation)
                            if (peer in operation.changedPeers) return@awaitState false
                            platform.removeBond(association)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (problem: PairingWorkflowException) {
                        throw problem
                    } catch (problem: Exception) {
                        // Revoked CONNECT/disabled Bluetooth must fail instead of becoming a
                        // misleading settings fallback. An otherwise rejected public API can fall back.
                        platform.ready()
                        log(operationId, "bond_removal_api_unavailable", mapOf("exception" to problem.javaClass.simpleName))
                        false
                    }
                    if (requested == true) {
                        log(operationId, "bond_removal_requested")
                    } else {
                        if (readState(operation, peer) != BondState.NONE) {
                            if (changed(operation, peer)) return@awaitState false
                            log(operationId, "bond_removal_settings_required")
                            platform.forgetInSettings(operationId, peer)
                            active(operation)
                            // The settings Activity has no result contract. Neither RESULT_OK nor
                            // RESULT_CANCELED establishes that the user actually forgot this device.
                            if (readState(operation, peer) != BondState.NONE) {
                                throw bondFailure("bluetooth_device_not_forgotten")
                            }
                        }
                    }
                    true
                }
            }
        }

    suspend fun ensure(operationId: Long, peer: PeerAddress) {
        command(operationId) { operation ->
            awaitState(operation, peer, BondState.BONDED, failOnBondFailure = true) { state ->
                if (state == BondState.NONE) {
                    currentCoroutineContext().ensureActive()
                    active(operation)
                    val requested = synchronized(lock) { active(operation); platform.createBond(peer) }
                    if (!requested && readState(operation, peer) == BondState.NONE) {
                        throw bondFailure("system_bond_request_rejected")
                    }
                    log(operationId, "bond_requested", mapOf("accepted" to requested.toString()))
                }
                true
            }
        }
    }

    /** Does not undo a removal already accepted by Android or cancel a system pairing dialog. */
    fun cancel(operationId: Long) {
        val operation = synchronized(lock) { operations.remove(operationId) } ?: return
        release(operation)
        runCatching { platform.cancelUi(operationId) }
    }

    override fun close() {
        val pending = synchronized(lock) {
            if (closed) return
            closed = true
            operations.values.toList().also { operations.clear() }
        }
        pending.forEach { release(it); runCatching { platform.cancelUi(it.id) } }
    }

    private suspend fun <T> command(operationId: Long, block: suspend (Operation) -> T): T = translated {
        currentCoroutineContext().ensureActive()
        val operation = synchronized(lock) {
            operations[operationId] ?: throw CancellationException("bond_operation_not_active")
        }
        val job = currentCoroutineContext().job
        synchronized(lock) { active(operation); operation.jobs += job }
        try {
            operation.commands.withLock {
                active(operation)
                block(operation)
            }
        } catch (cancelled: CancellationException) {
            cancel(operationId)
            throw cancelled
        } finally {
            synchronized(lock) { operation.jobs -= job }
        }
    }

    /** Reuses the capture observer, so checking current state never creates a subscribe-after-read gap. */
    private suspend fun awaitState(
        operation: Operation,
        peer: PeerAddress,
        target: BondState,
        failOnBondFailure: Boolean,
        begin: suspend (BondState) -> Boolean,
    ): Boolean {
        active(operation)
        val failureRevision = synchronized(lock) { operation.bondFailures[peer] ?: 0L }
        val initial = readState(operation, peer)
        log(operation.id, "bond_observed", mapOf("state" to initial.name, "target" to target.name))
        if (initial == target) return true
        if (!begin(initial)) return false
        while (true) {
            currentCoroutineContext().ensureActive()
            active(operation)
            if (readState(operation, peer) == target) return true
            if (failOnBondFailure && synchronized(lock) { (operation.bondFailures[peer] ?: 0L) > failureRevision }) {
                throw bondFailure("system_bond_failed_or_declined")
            }
            operation.updates.receive()
        }
    }

    private fun readState(operation: Operation, peer: PeerAddress): BondState {
        platform.ready()
        val state = platform.state(peer)
        // getBondState can itself return NONE when Bluetooth is disabled. Check again after
        // reading rather than treating that fallback as a completed unpair operation.
        platform.ready()
        active(operation)
        return state
    }

    private fun active(operation: Operation) {
        synchronized(lock) {
            if (closed || operations[operation.id] !== operation) throw CancellationException("bond_operation_cancelled")
            operation.failure?.let { throw PairingWorkflowException(it) }
        }
    }

    private fun changed(operation: Operation, peer: PeerAddress): Boolean = synchronized(lock) {
        active(operation)
        peer in operation.changedPeers
    }

    private fun observed(operation: Operation, signal: BondSignal) {
        synchronized(lock) {
            if (operations[operation.id] !== operation) return
            operation.revision += 1
            when (signal) {
                is BondSignal.State -> {
                    if (signal.current != signal.previous || signal.repairing) operation.changedPeers += signal.peer
                    if (signal.current == BondState.NONE && signal.previous == BondState.BONDING) {
                        operation.bondFailures[signal.peer] = operation.revision
                    }
                }
                is BondSignal.Pairing -> operation.changedPeers += signal.peer
                is BondSignal.Encrypted -> operation.changedPeers += signal.peer
                is BondSignal.Error -> operation.failure = signal.failure
                is BondSignal.KeyMissing -> Unit // Diagnostic evidence, not proof of a factory reset.
            }
            operation.updates.trySend(Unit)
        }
        val details = when (signal) {
            is BondSignal.State -> mapOf("state" to signal.current.name, "repairing" to signal.repairing.toString())
            is BondSignal.Pairing -> mapOf("repairing" to signal.repairing.toString())
            is BondSignal.KeyMissing -> mapOf("reason" to (signal.reason?.toString() ?: "unknown"))
            else -> emptyMap()
        }
        log(operation.id, when (signal) {
            is BondSignal.State -> "bond_state"
            is BondSignal.Pairing -> "bond_system_pairing_observed"
            is BondSignal.Encrypted -> "bond_bredr_encrypted"
            is BondSignal.KeyMissing -> "bond_key_missing"
            is BondSignal.Error -> "bond_observer_unavailable"
        }, details)
    }

    private fun release(operation: Operation) {
        val jobs = synchronized(lock) { operation.jobs.toList().also { operation.jobs.clear() } }
        jobs.forEach { it.cancel(CancellationException("bond_operation_released")) }
        operation.updates.cancel(CancellationException("bond_operation_released"))
        runCatching { operation.observer?.close() }
        operation.observer = null
    }

    private fun log(operationId: Long, event: String, details: Map<String, String> = emptyMap()) {
        runCatching { diagnostics.record(ProtocolDiagnostic("pairing_android", event, DiagnosticSeverity.INFO,
            details = details + ("operationId" to operationId.toString()))) }
    }

    private inline fun <T> translated(block: () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (known: PairingWorkflowException) { throw known }
    catch (problem: SecurityException) { throw PairingWorkflowException(PairingFailure(PairingFailureKind.PERMISSION_DENIED, "bluetooth_permission_denied")) }
    catch (problem: Exception) { throw bondFailure("bond_platform_${problem.javaClass.simpleName}") }
}

internal enum class BondState { NONE, BONDING, BONDED }

internal sealed interface BondSignal {
    data class State(val peer: PeerAddress, val current: BondState, val previous: BondState?, val repairing: Boolean = false) : BondSignal
    data class Pairing(val peer: PeerAddress, val repairing: Boolean) : BondSignal
    data class Encrypted(val peer: PeerAddress) : BondSignal
    data class KeyMissing(val peer: PeerAddress, val reason: Int?) : BondSignal
    data class Error(val failure: PairingFailure) : BondSignal
}

/** Small public-API boundary; fake implementations test ordering/cancellation without an Android VM. */
internal interface PairingBondPlatform {
    fun ready()
    fun observe(listener: (BondSignal) -> Unit): AutoCloseable
    fun bondedPeers(): Set<PeerAddress>
    fun state(peer: PeerAddress): BondState
    fun createBond(peer: PeerAddress): Boolean
    /** null means no supported public API; true means requested, never completed. */
    fun removeBond(association: PairingAssociation): Boolean?
    suspend fun forgetInSettings(operationId: Long, peer: PeerAddress)
    fun cancelUi(operationId: Long)
}

private class AndroidBondPlatform(
    private val context: Context,
    private val hostActions: AndroidPairingActions,
) : PairingBondPlatform {
    private fun adapter(): BluetoothAdapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        ?: throw PairingWorkflowException(PairingFailure(PairingFailureKind.PLATFORM_UNAVAILABLE, "bluetooth_adapter_unavailable"))

    @SuppressLint("MissingPermission")
    override fun ready() {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("BLUETOOTH_CONNECT not granted")
        }
        if (!adapter().isEnabled) throw bondFailure("bluetooth_disabled_during_bond")
    }

    override fun observe(listener: (BondSignal) -> Unit): AutoCloseable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                try {
                    if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                        if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                            listener(BondSignal.Error(PairingFailure(PairingFailureKind.BOND, "bluetooth_disabled_during_bond")))
                        }
                        return
                    }
                    val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                    @SuppressLint("MissingPermission")
                    val peer = PeerAddress(device.address)
                    val repairing = Build.VERSION.SDK_INT >= 37 &&
                        intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_CONTEXT, -1) == BluetoothDevice.PAIRING_CONTEXT_REPAIRING
                    when (intent.action) {
                        BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                            val current = decodeState(intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) ?: return
                            val previous = decodeState(intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR))
                            listener(BondSignal.State(peer, current, previous, repairing))
                        }
                        BluetoothDevice.ACTION_PAIRING_REQUEST -> listener(BondSignal.Pairing(peer, repairing))
                        else -> if (Build.VERSION.SDK_INT >= 36) {
                            when (intent.action) {
                                BluetoothDevice.ACTION_KEY_MISSING -> {
                                    // Reasons were added in 36.1. They are optional; API 36.0 remains
                                    // supported and never calls the 36.1 getKeyMissingCount method.
                                    val reason = if (Build.VERSION.SDK_INT >= 37 ||
                                        Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
                                        intent.getIntExtra(BluetoothDevice.EXTRA_BOND_LOSS_REASON, BluetoothDevice.BOND_LOSS_REASON_UNKNOWN)
                                    } else null
                                    listener(BondSignal.KeyMissing(peer, reason))
                                }
                                BluetoothDevice.ACTION_ENCRYPTION_CHANGE -> if (
                                    intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, -1) == BluetoothDevice.TRANSPORT_BREDR &&
                                    intent.getIntExtra(BluetoothDevice.EXTRA_ENCRYPTION_STATUS, -1) == 0 &&
                                    intent.getBooleanExtra(BluetoothDevice.EXTRA_ENCRYPTION_ENABLED, false)
                                ) listener(BondSignal.Encrypted(peer))
                            }
                        }
                    }
                } catch (problem: Exception) {
                    listener(BondSignal.Error(PairingFailure(
                        if (problem is SecurityException) PairingFailureKind.PERMISSION_DENIED else PairingFailureKind.BOND,
                        "bond_observation_${problem.javaClass.simpleName}")))
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED).apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
            if (Build.VERSION.SDK_INT >= 36) {
                addAction(BluetoothDevice.ACTION_KEY_MISSING)
                addAction(BluetoothDevice.ACTION_ENCRYPTION_CHANGE)
            }
        }
        // Bluetooth broadcasts can originate from the privileged Bluetooth process, not system UID.
        context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        return AutoCloseable { context.unregisterReceiver(receiver) }
    }

    @SuppressLint("MissingPermission")
    override fun bondedPeers(): Set<PeerAddress> = adapter().bondedDevices.map { PeerAddress(it.address) }.toSet()

    @SuppressLint("MissingPermission")
    override fun state(peer: PeerAddress): BondState = decodeState(adapter().getRemoteDevice(peer.value).bondState)
        ?: throw bondFailure("bluetooth_bond_state_unavailable")

    @SuppressLint("MissingPermission")
    override fun createBond(peer: PeerAddress): Boolean = adapter().getRemoteDevice(peer.value).createBond()

    @SuppressLint("MissingPermission")
    override fun removeBond(association: PairingAssociation): Boolean? {
        if (Build.VERSION.SDK_INT < 36) return null
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return null
        val owned = manager.myAssociations.firstOrNull { it.id == association.id }
            ?: throw PairingWorkflowException(PairingFailure(PairingFailureKind.ASSOCIATION, "association_no_longer_owned"))
        val actual = owned.deviceMacAddress?.toString()?.let(::PeerAddress) ?: return null
        if (actual != association.peer) {
            throw PairingWorkflowException(PairingFailure(PairingFailureKind.ASSOCIATION, "association_device_changed"))
        }
        return manager.removeBond(owned.id)
    }

    override suspend fun forgetInSettings(operationId: Long, peer: PeerAddress) {
        hostActions.activity(operationId, PairingActionKind.FORGET_BLUETOOTH_DEVICE,
            "forget_selected_watch_in_bluetooth_settings", Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    override fun cancelUi(operationId: Long) = hostActions.cancel(operationId)

    private fun decodeState(value: Int): BondState? = when (value) {
        BluetoothDevice.BOND_NONE -> BondState.NONE
        BluetoothDevice.BOND_BONDING -> BondState.BONDING
        BluetoothDevice.BOND_BONDED -> BondState.BONDED
        else -> null
    }
}

private fun bondFailure(reason: String) = PairingWorkflowException(PairingFailure(PairingFailureKind.BOND, reason))
