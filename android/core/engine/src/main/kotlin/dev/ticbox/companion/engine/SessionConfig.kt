package dev.ticbox.companion.engine

/**
 * Client resource/time budgets, not discovered or negotiated watch limits. The framing module has
 * its own 1 MiB record/message protection. Unknown peers must never dictate an unbounded allocation.
 */
data class SessionConfig(
    val connectTimeoutMillis: Long = 30_000,
    val handshakeTimeoutMillis: Long = 15_000,
    val writeTimeoutMillis: Long = 15_000,
    val initialReconnectDelayMillis: Long = 1_000,
    val maxReconnectDelayMillis: Long = 30_000,
    val reconnectJitterRatio: Double = 0.2,
    /** null means keep retrying transient transport failures until explicitly stopped. */
    val maxReconnectAttempts: Int? = null,
    val fragmentBytes: Int = 8_192,
    val maxBusinessPayloadBytes: Int = 512 * 1_024,
    val maxPathBytes: Int = 4_096,
    /** Pending envelopes until CONNECT and any accountless migration are both confirmed. */
    val maxPreConnectMessages: Int = 32,
    val maxPreConnectBytes: Int = 1_024 * 1_024,
    val maxReadChunkBytes: Int = 64 * 1_024,
    val eventBufferCapacity: Int = 32,
    /** Per-handler pending business work, independent of the public observation stream. */
    val businessMessageQueueCapacity: Int = 64,
    /** Includes queued and currently handled bodies plus routing metadata. */
    val businessMessageQueueBytes: Int = 2 * 1_024 * 1_024,
    val businessMessageTimeoutMillis: Long = 60_000,
    /** Local budgets for the accountless watch-transfer exchange, independent of CONNECT. */
    val migrationTimeoutMillis: Long = 20_000,
    val migrationQueryIntervalMillis: Long = 3_000,
    /** A CONNECT followed by immediate EOF must not reset exponential backoff. */
    val reconnectResetAfterMillis: Long = 30_000,
) {
    init {
        require(connectTimeoutMillis > 0 && handshakeTimeoutMillis > 0 && writeTimeoutMillis > 0)
        require(initialReconnectDelayMillis > 0 && maxReconnectDelayMillis >= initialReconnectDelayMillis)
        require(reconnectJitterRatio.isFinite() && reconnectJitterRatio in 0.0..1.0)
        require(maxReconnectAttempts == null || maxReconnectAttempts >= 0)
        require(fragmentBytes in 256..(512 * 1_024))
        require(maxBusinessPayloadBytes in 1..(512 * 1_024))
        require(maxPathBytes in 1..4_096)
        require(maxPreConnectMessages in 1..128 && maxPreConnectBytes in 1..(1_024 * 1_024))
        require(maxReadChunkBytes in 8_192..(1_024 * 1_024))
        require(eventBufferCapacity in 1..128)
        require(businessMessageQueueCapacity > 0 && businessMessageQueueBytes > 0 && businessMessageTimeoutMillis > 0)
        require(migrationTimeoutMillis > 0 && migrationQueryIntervalMillis > 0 && reconnectResetAfterMillis > 0)
    }
}
