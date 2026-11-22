package dev.ticbox.companion.engine

/** Stock phone retry defaults, plus explicitly local memory/concurrency limits. */
data class ChannelConfig(
    val openRetryIntervalMillis: Long = 15_000,
    val openSendAttempts: Int = 2,
    val dataRetryIntervalMillis: Long = 18_000,
    val dataSendAttempts: Int = 3,
    val sendChunkBytes: Int = 64_000,
    val maxActiveChannels: Int = 16,
    val maxLocalOpensPerSession: Int = 4_096,
    val maxWriteBytes: Int = 512 * 1_024,
    val maxQueuedWritesPerChannel: Int = 4,
    val maxPendingEmissions: Int = 64,
    val maxPathBytes: Int = 4_096,
) {
    init {
        require(openRetryIntervalMillis > 0 && dataRetryIntervalMillis > 0)
        require(openSendAttempts in 1..10 && dataSendAttempts in 1..10)
        require(sendChunkBytes in 128..65_536)
        require(maxActiveChannels in 1..64)
        require(maxLocalOpensPerSession in maxActiveChannels..65_536)
        require(maxWriteBytes in sendChunkBytes..(1_024 * 1_024))
        require(maxQueuedWritesPerChannel in 1..16)
        require(maxPendingEmissions in 1..256)
        require(maxPathBytes in 1..4_096)
    }
}
