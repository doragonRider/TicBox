package dev.ticbox.companion.platform.pairing

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.pairing.PairingPlatform
import dev.ticbox.companion.platform.cdm.CdmPermissionSync
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import kotlinx.coroutines.*

/** Android 14+ public APIs. UI launches are delegated to a retained token registry owned by :app. */
class AndroidPairingPlatform(
    context: Context,
    val hostActions: AndroidPairingActions = AndroidPairingActions(),
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
) : PairingPlatform {
    private val context = context.applicationContext
    private val bonds = AndroidPairingBonds(this.context, hostActions, diagnostics)
    private val lock = Any()
    private var closed = false
    private var pendingAssociation: AssociationLease? = null
    private var permissionSync: CdmPermissionSync? = null
    override val actions get() = hostActions.state

    private class AssociationLease(val operationId: Long) {
        var completed = false
        var cancelled = false
    }

    private fun manager(): CompanionDeviceManager {
        synchronized(lock) { check(!closed) { "Pairing platform is closed" } }
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) {
            throw failure(PairingFailureKind.PLATFORM_UNAVAILABLE, "companion_device_setup_unavailable")
        }
        return context.getSystemService(CompanionDeviceManager::class.java)
            ?: throw failure(PairingFailureKind.PLATFORM_UNAVAILABLE, "companion_device_manager_unavailable")
    }

    @SuppressLint("MissingPermission")
    override suspend fun prepare(operationId: Long) {
        manager()
        hostActions.awaitCancellations()
        // CDM performs its own discovery; it does not require the app to run a Bluetooth scan.
        val missing = listOf(Manifest.permission.BLUETOOTH_CONNECT)
            .filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            hostActions.permissions(operationId, missing)
            val denied = missing.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (denied.isNotEmpty()) throw failure(PairingFailureKind.PERMISSION_DENIED, "bluetooth_permission_denied")
        }
        val adapter = adapter()
        if (!adapter.isEnabled) {
            hostActions.activity(operationId, PairingActionKind.ENABLE_BLUETOOTH, "bluetooth_enable_required", Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            if (!adapter.isEnabled) throw failure(PairingFailureKind.PERMISSION_DENIED, "bluetooth_enable_declined")
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun associations(): List<PairingAssociation> = manager().myAssociations.map(::describe)

    @SuppressLint("MissingPermission")
    override suspend fun associate(operationId: Long, options: PairingOptions): PairingAssociation = coroutineScope {
        hostActions.awaitCancellations()
        val manager = manager()
        val lease = AssociationLease(operationId)
        synchronized(lock) {
            check(pendingAssociation == null) { "An association request is already pending" }
            pendingAssociation = lease
        }
        val result = CompletableDeferred<PairingAssociation>()
        val ui = AtomicReference<Job?>()
        val callbackInfo = AtomicReference<AssociationInfo?>()
        val selectedDevice = AtomicReference<BluetoothDevice?>()
        fun finish(info: AssociationInfo) {
            try {
            val stale = synchronized(lock) { pendingAssociation !== lease || lease.cancelled }
            if (stale || result.isCompleted) { log(operationId, "late_association_callback"); return }
            val described = describe(info)
            val selected = selectedDevice.get()?.address?.let(::PeerAddress)
            val peer = described.peer ?: selected ?: options.peer?.also {
                // setAddress constrained this particular system chooser to the requested address.
                log(operationId, "association_address_from_request_filter", mapOf("associationId" to info.id.toString()))
            }
            if (peer == null) {
                callbackInfo.set(info)
                return
            }
            synchronized(lock) { lease.completed = true }
            result.complete(described.copy(peer = peer))
            } catch (problem: Exception) {
                result.completeExceptionally(failure(if (problem is SecurityException) PairingFailureKind.PERMISSION_DENIED else PairingFailureKind.ASSOCIATION,
                    "association_result_${problem.javaClass.simpleName}"))
            }
        }
        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                if (synchronized(lock) { pendingAssociation !== lease || lease.cancelled } || result.isCompleted) return
                log(operationId, "association_chooser_pending")
                ui.getAndSet(null)?.cancel()
                ui.set(launch {
                    try {
                        val returned = hostActions.sender(operationId, PairingActionKind.ASSOCIATION_APPROVAL, "select_and_approve_watch", intentSender)
                        if (returned is PairingHostResult.ActivityResult) {
                            log(operationId, "association_ui_result", mapOf("code" to returned.code.toString()))
                            synchronized(lock) { lease.completed = true }
                            if (returned.code != Activity.RESULT_OK) {
                                result.cancel(CancellationException("association_user_declined"))
                            } else {
                                @Suppress("DEPRECATION") // Some CDM implementations still return this classic-Bluetooth result alongside AssociationInfo.
                                val legacyDevice = returned.data?.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE, BluetoothDevice::class.java)
                                selectedDevice.set(legacyDevice)
                                val info = returned.data?.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java) ?: callbackInfo.get()
                                if (info != null) {
                                    finish(info)
                                    if (!result.isCompleted && describe(info).peer == null && selectedDevice.get() == null && options.peer == null) {
                                        result.completeExceptionally(failure(PairingFailureKind.ASSOCIATION, "association_bluetooth_address_unavailable"))
                                    }
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (problem: Exception) { result.completeExceptionally(problem) }
                })
            }

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                log(operationId, "association_created", mapOf("associationId" to associationInfo.id.toString()))
                callbackInfo.set(associationInfo); finish(associationInfo)
            }
            override fun onFailure(error: CharSequence?) {
                synchronized(lock) { lease.completed = true }
                result.completeExceptionally(failure(PairingFailureKind.ASSOCIATION, "system_association_failed"))
            }
            // API 36+ invokes both overloads. The deferred accepts one outcome; older OS uses the required overload above.
            @SuppressLint("NewApi")
            override fun onFailure(errorCode: Int, error: CharSequence?) {
                log(operationId, "association_error_code", mapOf("code" to errorCode.toString()))
                onFailure(error)
            }
        }
        try {
            val filter = BluetoothDeviceFilter.Builder().apply {
                options.peer?.let { setAddress(it.value) }
                options.namePattern?.let { pattern ->
                    try { setNamePattern(Pattern.compile(pattern)) }
                    catch (_: IllegalArgumentException) { log(operationId, "invalid_name_filter_ignored") }
                }
            }.build()
            val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(options.peer != null).apply {
                if (options.requestWatchProfile) setDeviceProfile(AssociationRequest.DEVICE_PROFILE_WATCH)
            }.build()
            val locationEnabled = runCatching { context.getSystemService(LocationManager::class.java)?.isLocationEnabled }.getOrNull()
            log(operationId, "association_requested", mapOf("locationEnabled" to (locationEnabled?.toString() ?: "unknown"),
                "addressFilter" to (options.peer != null).toString(), "nameFilter" to (options.namePattern != null).toString()))
            try { manager.associate(request, context.mainExecutor, callback) }
            catch (denied: SecurityException) {
                synchronized(lock) { lease.completed = true }
                throw failure(PairingFailureKind.PERMISSION_DENIED, if (options.requestWatchProfile) "watch_profile_request_denied" else "association_request_denied")
            }
            result.await()
        } catch (cancelled: CancellationException) {
            cancel(operationId)
            throw cancelled
        } catch (problem: Exception) {
            cancel(operationId)
            throw problem
        } finally {
            ui.getAndSet(null)?.cancel()
            result.cancel()
            synchronized(lock) { if (pendingAssociation === lease) pendingAssociation = null }
        }
    }

    override suspend fun captureBonds(operationId: Long): Set<PeerAddress> = bonds.capture(operationId)

    override suspend fun clearBond(operationId: Long, association: PairingAssociation): Boolean = bonds.clear(operationId, association)

    override suspend fun ensureBond(operationId: Long, peer: PeerAddress) {
        // Permission-only synchronization also waits for a bond, without replacing it.
        // capture is idempotent when the chooser workflow already registered its observer.
        bonds.capture(operationId)
        bonds.ensure(operationId, peer)
    }

    override suspend fun permissionConsent(operationId: Long, association: PairingAssociation): PairingConsent {
        val manager = manager()
        owned(association)
        if (Build.VERSION.SDK_INT >= 35 && manager.isPermissionTransferUserConsented(association.id)) return PairingConsent.SYSTEM_APPROVED
        val sender = sync().consentRequest(association.id)
        if (sender == null) {
            if (Build.VERSION.SDK_INT >= 35) return if (manager.isPermissionTransferUserConsented(association.id)) PairingConsent.SYSTEM_APPROVED else PairingConsent.DECLINED
            // Android 14 can return null for a previous accepted OR declined request. Let the actual
            // CDM transfer enforce the stored consent; null is never recorded as consent or success.
            log(operationId, "permission_consent_not_queryable_api34", mapOf("decision" to "let_system_transfer_check_stored_consent"))
            return PairingConsent.UNKNOWN_SYSTEM_STATE
        }
        val result = hostActions.sender(operationId, PairingActionKind.PERMISSION_TRANSFER_CONSENT, "permission_transfer_consent_required", sender)
        if (result !is PairingHostResult.ActivityResult || result.code != Activity.RESULT_OK) return PairingConsent.DECLINED
        return if (Build.VERSION.SDK_INT < 35 || manager.isPermissionTransferUserConsented(association.id)) PairingConsent.USER_APPROVED else PairingConsent.DECLINED
    }

    override suspend fun transferPermissions(association: PairingAssociation, channel: WearChannel, timeoutMillis: Long) {
        try {
            owned(association)
            if (context.checkSelfPermission(Manifest.permission.DELIVER_COMPANION_MESSAGES) != PackageManager.PERMISSION_GRANTED) {
                throw failure(PairingFailureKind.PERMISSION_DENIED, "permission_transport_privilege_not_granted")
            }
            val attached = sync().attach(association.id, channel)
            try { attached.transferPermissions(timeoutMillis) }
            finally { attached.close() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (known: PairingWorkflowException) { throw known }
        catch (problem: Exception) { throw failure(PairingFailureKind.PERMISSION_TRANSFER, "system_permission_transfer_${problem.javaClass.simpleName}") }
        finally { channel.close() }
    }

    override fun cancel(operationId: Long) {
        val cancelChooser = synchronized(lock) {
            val current = pendingAssociation
            if (current?.operationId == operationId && !current.completed && !current.cancelled) {
                current.cancelled = true
                pendingAssociation = null
                true
            } else false
        }
        if (cancelChooser) {
            runCatching { manager().buildAssociationCancellationIntent() }.onSuccess { sender ->
                if (sender != null) hostActions.cancellation(operationId, sender)
            }.onFailure { log(operationId, "association_cancel_ui_unavailable", mapOf("exception" to it.javaClass.simpleName)) }
        }
        bonds.cancel(operationId)
        hostActions.cancel(operationId)
    }

    override fun close() {
        val pending = synchronized(lock) { if (closed) return; pendingAssociation?.operationId }
        pending?.let(::cancel)
        synchronized(lock) { closed = true; permissionSync?.close(); permissionSync = null }
        bonds.close()
        hostActions.close()
    }

    @SuppressLint("MissingPermission")
    private fun describe(info: AssociationInfo): PairingAssociation {
        val address = info.deviceMacAddress?.toString() ?: info.associatedDevice?.bluetoothDevice?.address
        val peer = address?.let { runCatching { PeerAddress(it) }.getOrNull() }
        return PairingAssociation(info.id, peer, info.displayName?.toString(), info.deviceProfile == AssociationRequest.DEVICE_PROFILE_WATCH)
    }
    private fun owned(association: PairingAssociation): AssociationInfo {
        val info = manager().myAssociations.firstOrNull { it.id == association.id }
            ?: throw failure(PairingFailureKind.ASSOCIATION, "association_no_longer_owned")
        val currentPeer = describe(info).peer
        if (currentPeer != null && association.peer != null && currentPeer != association.peer) throw failure(PairingFailureKind.ASSOCIATION, "association_device_changed")
        return info
    }
    private fun adapter(): BluetoothAdapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        ?: throw failure(PairingFailureKind.PLATFORM_UNAVAILABLE, "bluetooth_adapter_unavailable")
    private fun sync(): CdmPermissionSync = synchronized(lock) {
        check(!closed)
        permissionSync ?: CdmPermissionSync(context, diagnostics).also { permissionSync = it }
    }
    private fun log(operationId: Long, event: String, details: Map<String, String> = emptyMap()) {
        runCatching { diagnostics.record(ProtocolDiagnostic("pairing_android", event, DiagnosticSeverity.INFO,
            details = details + ("operationId" to operationId.toString()))) }
    }
    private fun failure(kind: PairingFailureKind, reason: String) = PairingWorkflowException(PairingFailure(kind, reason))
}
