package dev.ticbox.companion.platform.cloud

import dev.ticbox.companion.engine.cloud.UrlConnectionCloudHttpTransport

/** Host-owned HTTP client, independent of Bluetooth and UI activities. */
class AndroidCloudHttpTransport(
    maxResponseBytes: Int = 16 * 1024 * 1024,
    connectTimeoutMillis: Int = 30_000,
    readTimeoutMillis: Int = 60_000,
) : UrlConnectionCloudHttpTransport(maxResponseBytes, connectTimeoutMillis, readTimeoutMillis)
