// Modified by TicBox contributors, 2026: Android source binding / TLS transport.
package com.flyfishxu.kadb.core

import com.flyfishxu.kadb.DelayedAckMode

internal fun shouldAdvertiseDelayedAck(mode: DelayedAckMode): Boolean = when (mode) {
    DelayedAckMode.AOSP_DEFAULT -> aospDefaultDelayedAckEnabled()
    DelayedAckMode.ENABLED -> true
    DelayedAckMode.DISABLED -> false
}
