package dev.ticbox.companion.platform.proxy

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.content.Context
import dev.ticbox.companion.api.DiagnosticSink
import dev.ticbox.companion.api.DuplexTransport
import dev.ticbox.companion.api.NetworkProxy
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.engine.proxy.AcceptedProxyTransport
import dev.ticbox.companion.engine.proxy.Flow15ProxyPolicy
import dev.ticbox.companion.engine.proxy.Flow15ProxyServer
import dev.ticbox.companion.engine.proxy.ProxyListener
import dev.ticbox.companion.engine.proxy.ProxyListenerFactory
import dev.ticbox.companion.platform.transport.BlockingStreamTransport
import dev.ticbox.companion.platform.transport.BluetoothRfcommTransportFactory
import dev.ticbox.companion.platform.transport.cancellableIo
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** Secure phone RFCOMM server, separate from the outgoing main Data Layer client socket. */
class AndroidNetworkProxy(
    context: Context,
    parentScope: CoroutineScope,
    diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    policy: Flow15ProxyPolicy = Flow15ProxyPolicy(),
    dispatcher: CoroutineDispatcher? = null,
) : NetworkProxy by createProxy(context, parentScope, diagnostics, policy, dispatcher)

private fun createProxy(
    context: Context,
    parentScope: CoroutineScope,
    diagnostics: DiagnosticSink,
    policy: Flow15ProxyPolicy,
    dispatcher: CoroutineDispatcher?,
): NetworkProxy {
    // Dispatchers.IO's default parallelism can otherwise be consumed entirely by blocking reads.
    // Its elastic view has a finite policy-derived ceiling with room for write/connect/accept work.
    val parallelism = ((policy.maxTcpConnections.toLong() + policy.maxUdpConnections) * 2 + 8)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val io = dispatcher ?: Dispatchers.IO.limitedParallelism(parallelism)
    return Flow15ProxyServer(parentScope, AndroidFlow15ListenerFactory(context, io),
        JavaNetProxyNetwork(io, policy.connectTimeoutMillis.toInt()), AndroidProxyNetworkMonitor(context),
        diagnostics, policy)
}

class AndroidFlow15ListenerFactory(
    context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProxyListenerFactory {
    private val context = context.applicationContext

    @SuppressLint("MissingPermission")
    override suspend fun listen(peer: PeerAddress): ProxyListener {
        val adapter = BluetoothRfcommTransportFactory.requireBluetoothReady(context)
        if (adapter.getRemoteDevice(peer.value).bondState != BluetoothDevice.BOND_BONDED) {
            throw SecurityException("Selected proxy peer must already be bonded")
        }
        // listenUsingRfcommWithServiceRecord is the secure API the official client uses.
        return cancellableIo(dispatcher, onCancel = {}, onDiscard = ProxyListener::close) {
            val server = adapter.listenUsingRfcommWithServiceRecord("Flow15", FLOW15_UUID)
            Listener(server, dispatcher)
        }
    }

    private class Listener(
        private val server: BluetoothServerSocket,
        private val dispatcher: CoroutineDispatcher,
    ) : ProxyListener {
        private val closed = AtomicBoolean()

        @SuppressLint("MissingPermission")
        override suspend fun accept(): AcceptedProxyTransport = cancellableIo(
            dispatcher, onCancel = ::close, onDiscard = { it.transport.close() },
        ) {
            val socket = server.accept()
            try {
                if (closed.get()) throw java.io.IOException("Flow15 listener closed")
                val remote = socket.remoteDevice
                val transport: DuplexTransport = BlockingStreamTransport(
                    socket.inputStream, socket.outputStream, socket::close, dispatcher,
                )
                AcceptedProxyTransport(PeerAddress(remote.address), remote.bondState == BluetoothDevice.BOND_BONDED, transport)
            } catch (error: Throwable) { runCatching { socket.close() }; throw error }
        }

        override fun close() { if (closed.compareAndSet(false, true)) runCatching { server.close() } }
    }

    companion object {
        val FLOW15_UUID: UUID = UUID.fromString("c3cfde9a-7300-11ec-90d6-0242ac120003")
    }
}
