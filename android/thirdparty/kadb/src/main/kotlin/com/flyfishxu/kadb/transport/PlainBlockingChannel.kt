// Modified by TicBox contributors, 2026: Android source binding / TLS transport.
/*
 * Copyright (c) 2024 Flyfish-Xu
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.flyfishxu.kadb.transport

import com.flyfishxu.kadb.TcpKeepAlive
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.math.min

internal class PlainBlockingChannel private constructor(
    private val socket: Socket,
    private val rawSocket: Socket? = null,
) : TransportChannel {

    /** Android owns TLS record ordering, buffering and post-handshake messages. */
    fun startTls(context: SSLContext, host: String, port: Int, timeoutMillis: Int): PlainBlockingChannel {
        val tls = context.socketFactory.createSocket(socket, host, port, true) as SSLSocket
        val channel = PlainBlockingChannel(tls, socket)
        try {
            tls.useClientMode = true
            tls.enabledProtocols = arrayOf("TLSv1.3")
            tls.soTimeout = timeoutMillis
            tls.startHandshake()
            return channel
        } catch (error: Throwable) {
            runCatching { channel.close() }
            throw error
        }
    }

    companion object {
        fun connect(host: String, port: Int, connectTimeoutMs: Long, tcpKeepAlive: TcpKeepAlive? = null): PlainBlockingChannel {
            require(connectTimeoutMs >= 0) { "Connect timeout must not be negative" }
            val socket = Socket()
            try {
                socket.keepAlive = true
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                tcpKeepAlive?.let { configureTcpKeepAlive(socket, it) }
                return PlainBlockingChannel(socket)
            } catch (error: Throwable) {
                runCatching { socket.close() }
                throw error
            }
        }
    }

    private val input get() = socket.getInputStream()
    private val output get() = socket.getOutputStream()

    override val localAddress: InetSocketAddress
        get() = socket.localSocketAddress as InetSocketAddress
    override val remoteAddress: InetSocketAddress
        get() = socket.remoteSocketAddress as InetSocketAddress

    override suspend fun read(dst: ByteBuffer, timeout: Long, unit: TimeUnit): Int {
        if (!dst.hasRemaining()) return 0
        val oldTimeout = socket.soTimeout
        val readTimeout = if (timeout > 0) unit.toMillis(timeout).coerceIn(1, Int.MAX_VALUE.toLong()).toInt() else 0
        val timeoutChanged = oldTimeout != readTimeout
        try {
            if (timeoutChanged) socket.soTimeout = readTimeout
            val max = min(dst.remaining(), 64 * 1024)
            val read = if (dst.hasArray()) {
                input.read(dst.array(), dst.arrayOffset() + dst.position(), max).also {
                    if (it > 0) dst.position(dst.position() + it)
                }
            } else {
                val buffer = ByteArray(max)
                input.read(buffer).also { if (it > 0) dst.put(buffer, 0, it) }
            }
            return read
        } finally {
            if (timeoutChanged && !socket.isClosed) runCatching { socket.soTimeout = oldTimeout }
        }
    }

    override suspend fun write(src: ByteBuffer, timeout: Long, unit: TimeUnit): Int {
        val max = min(src.remaining(), 64 * 1024)
        if (max == 0) return 0
        if (src.hasArray()) {
            output.write(src.array(), src.arrayOffset() + src.position(), max)
            src.position(src.position() + max)
        } else {
            val bytes = ByteArray(max)
            src.get(bytes)
            output.write(bytes)
        }
        return max
    }

    override suspend fun readExactly(dst: ByteBuffer, timeout: Long, unit: TimeUnit) {
        while (dst.hasRemaining()) {
            val read = read(dst, timeout, unit)
            if (read < 0) throw java.io.EOFException("EOF while readExactly")
        }
    }

    override suspend fun writeExactly(src: ByteBuffer, timeout: Long, unit: TimeUnit) {
        while (src.hasRemaining()) {
            val written = write(src, timeout, unit)
            if (written < 0) throw java.io.IOException("write returned $written")
        }
    }

    override suspend fun shutdownInput() {
        socket.shutdownInput()
    }

    override suspend fun shutdownOutput() {
        socket.shutdownOutput()
    }

    override val isOpen: Boolean
        get() = socket.isConnected && !socket.isClosed

    override fun close() {
        // Closing the raw socket first unblocks an outstanding TLS read/write/handshake.
        // Never wait for close_notify on a peer which has stopped receiving.
        runCatching { rawSocket?.close() }
        socket.close()
    }
}
