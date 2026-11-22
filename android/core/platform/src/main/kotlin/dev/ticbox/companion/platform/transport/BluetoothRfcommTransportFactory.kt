package dev.ticbox.companion.platform.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import dev.ticbox.companion.api.DuplexTransport
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.api.TransportFactory
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Secure classic RFCOMM only. System discovery, association and bond consent are separate steps. */
class BluetoothRfcommTransportFactory(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val serviceUuid: UUID = DATA_UUID,
) : TransportFactory {
    private val context = context.applicationContext

    @SuppressLint("MissingPermission")
    override suspend fun open(peer: PeerAddress): DuplexTransport {
        val adapter = requireBluetoothReady(context)
        val device = adapter.getRemoteDevice(peer.value)
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            throw SecurityException("System Bluetooth pairing is required before opening RFCOMM")
        }
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            adapter.cancelDiscovery()
        }
        val socket = device.createRfcommSocketToServiceRecord(serviceUuid)
        return cancellableIo(
            dispatcher = dispatcher,
            onCancel = { runCatching { socket.close() } },
            onDiscard = DuplexTransport::close,
        ) {
            try {
                socket.connect()
                BlockingStreamTransport(
                    input = socket.inputStream,
                    output = socket.outputStream,
                    release = socket::close,
                    dispatcher = dispatcher,
                )
            } catch (failure: Throwable) {
                runCatching { socket.close() }
                throw failure
            }
        }
    }

    companion object {
        /** Main data socket; Flow15 uses a different UUID and the opposite listening role. */
        val DATA_UUID: UUID = UUID.fromString("5e8945b0-9525-11e3-a5e2-0800200c9a66")

        @SuppressLint("MissingPermission")
        fun requireBluetoothReady(context: Context): BluetoothAdapter {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("Bluetooth connect permission is required")
            }
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw UnsupportedOperationException("This phone has no Bluetooth adapter")
            check(adapter.isEnabled) { "Bluetooth must be enabled before starting the session" }
            return adapter
        }
    }
}
