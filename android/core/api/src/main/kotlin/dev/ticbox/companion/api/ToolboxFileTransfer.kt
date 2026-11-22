package dev.ticbox.companion.api

/** A content snapshot of one regular remote file, bound to a physical device and backend kind. */
data class ToolboxFileSnapshot(
    val target: ToolboxTarget,
    val path: String,
    val size: Long,
    val sha256: String,
    val modifiedMillis: Long,
)

/**
 * Persist this together with the immutable phone-side source. Reuse it only for an explicit retry
 * on the same device/kind, bound to that device's newly reviewed connection. [transferId] is a
 * random lowercase 32-digit hexadecimal identifier; it is never a remote path.
 */
data class ToolboxUploadRequest(
    val target: ToolboxTarget,
    val transferId: String,
    val path: String,
    val size: Long,
    val sha256: String,
    val options: ToolboxWriteOptions = ToolboxWriteOptions(),
)

/** Returned after byte counts and hashes were checked, not merely after the last DATA frame. */
data class ToolboxTransferReceipt(val bytes: Long, val sha256: String, val resumedBytes: Long)
