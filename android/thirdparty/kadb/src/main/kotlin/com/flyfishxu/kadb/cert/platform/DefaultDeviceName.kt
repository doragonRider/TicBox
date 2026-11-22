// Modified by TicBox contributors, 2026: Android source binding / TLS transport.
package com.flyfishxu.kadb.cert.platform


internal fun formatDefaultDeviceName(loginName: String?, hostName: String?): String {
    fun normalize(component: String?): String {
        return component
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "unknown"
    }

    return "${normalize(loginName)}@${normalize(hostName)}"
}
