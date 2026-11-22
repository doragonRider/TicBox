package dev.ticbox.companion.platform.recovery

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.ticbox.companion.api.DiagnosticSink
import dev.ticbox.companion.api.ProtocolDiagnostic
import dev.ticbox.companion.api.PairingAssociation
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.engine.recovery.*
import dev.ticbox.companion.platform.pairing.AtomicFileAssociationMappingStore
import kotlinx.coroutines.CancellationException

class AndroidRecoveryEnvironment(context: Context, private val diagnostics: DiagnosticSink = DiagnosticSink.NONE) : RecoveryEnvironment {
    private val context = context.applicationContext
    private val mappings = AtomicFileAssociationMappingStore(context)
    private fun manager(): CompanionDeviceManager? = if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP))
        context.getSystemService(CompanionDeviceManager::class.java) else null

    @SuppressLint("MissingPermission")
    override suspend fun pairedWatches(): List<PairingAssociation> {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        val owned = manager()?.myAssociations.orEmpty().map { info ->
            PairingAssociation(info.id, info.deviceMacAddress?.toString()?.let(::PeerAddress), info.displayName?.toString(),
                info.deviceProfile == android.companion.AssociationRequest.DEVICE_PROFILE_WATCH)
        }
        return knownWatchAssociations(owned, mappings.all()) { peer ->
            adapter.getRemoteDevice(peer.value).bondState == BluetoothDevice.BOND_BONDED
        }
    }

    @SuppressLint("MissingPermission") // Every Bluetooth access follows the actual runtime permission check.
    override suspend fun inspect(intent: ConnectionIntent): RecoveryEnvironmentState {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            return RecoveryEnvironmentState(bluetoothPermission = false)
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return RecoveryEnvironmentState(bluetoothAvailable = false)
        val associations = manager()?.myAssociations.orEmpty()
        val mapped = try { mappings.all() } catch (problem: Exception) {
            if (problem is CancellationException) throw problem
            runCatching { diagnostics.record(ProtocolDiagnostic("recovery", "association_mapping_read_failed",
                details = mapOf("exception" to problem.javaClass.simpleName))) }
            emptyList()
        }
        val association = associations.firstOrNull { info ->
            if (intent.associationId != null) info.id == intent.associationId
            else info.deviceMacAddress?.toString()?.equals(intent.peer?.value, ignoreCase = true) == true ||
                mapped.any { it.associationId == info.id && it.peer == intent.peer }
        }
        return RecoveryEnvironmentState(
            bluetoothEnabled = adapter.isEnabled,
            bonded = !adapter.isEnabled || (intent.peer?.let { adapter.getRemoteDevice(it.value).bondState == BluetoothDevice.BOND_BONDED } ?: true),
            associationId = association?.id,
            savedAssociationExists = intent.associationId == null || association != null,
        )
    }

    override suspend fun observe(intent: ConnectionIntent) {
        val id = intent.associationId ?: return
        val manager = manager() ?: return
        if (Build.VERSION.SDK_INT >= 36) {
            manager.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(id).build())
        } else {
            @Suppress("DEPRECATION") // Public API 34/35 fallback; association-ID requests were added in API 36.
            manager.startObservingDevicePresence(requireNotNull(intent.peer).value)
        }
    }

    override suspend fun stopObserving(intent: ConnectionIntent) {
        val id = intent.associationId ?: return
        val manager = manager() ?: return
        if (Build.VERSION.SDK_INT >= 36) {
            manager.stopObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(id).build())
        } else {
            @Suppress("DEPRECATION")
            manager.stopObservingDevicePresence(requireNotNull(intent.peer).value)
        }
    }
}
