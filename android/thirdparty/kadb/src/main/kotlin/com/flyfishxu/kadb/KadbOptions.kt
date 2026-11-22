// Modified by TicBox contributors, 2026: delayed ACK window and batching.
package com.flyfishxu.kadb

import com.flyfishxu.kadb.core.AdbProtocol

/**
 * Instance-scoped connection options that affect Kadb's host-side protocol behavior.
 */
data class KadbOptions(
    /**
     * Controls whether the host advertises the `delayed_ack` feature in CNXN.
     */
    val delayedAckMode: DelayedAckMode = DelayedAckMode.AOSP_DEFAULT,

    /**
     * With `delayed_ack`, how much a stream's peer may send ahead of what's been read. That much can
     * sit in memory, and in line ahead of newer data when the reader falls behind.
     */
    val delayedAckWindowBytes: Int = AdbProtocol.INITIAL_DELAYED_ACK_BYTES,

    /** Best effort platform TCP timings; unsupported platforms retain OS defaults. */
    val tcpKeepAlive: TcpKeepAlive? = null
) {
    init {
        require(delayedAckWindowBytes > 0) { "Delayed ACK window must be > 0: $delayedAckWindowBytes" }
    }
}

/**
 * Policy for host-side `delayed_ack` feature advertisement.
 */
enum class DelayedAckMode {
    /** Follow the platform default: JVM honors `ADB_BURST_MODE=1`, Android keeps it disabled. */
    AOSP_DEFAULT,

    /** Always advertise `delayed_ack` for this Kadb instance. */
    ENABLED,

    /** Never advertise `delayed_ack` for this Kadb instance. */
    DISABLED
}
