package dev.ticbox.companion.engine.privacy

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.cloud.*

/** One optional service entry point; HTTP parsing, retries and sockets remain unaware of UI consent. */
class ConfiguredCloudHttpTransport(
    private val settings: () -> CloudPrivacySettings,
    private val create: () -> CloudHttpTransport,
) : CloudHttpTransport, AutoCloseable {
    private var transport: CloudHttpTransport? = null
    private var closed = false

    override suspend fun execute(request: CloudHttpRequest): CloudHttpResponse {
        if (!settings().allows(CloudCapability.MOBVOI_CLOUD)) throw MobvoiCloudException(
            MobvoiCloudIssue(MobvoiCloudIssueKind.CONSENT_REQUIRED, "mobvoi_cloud_disabled_in_settings"))
        val selected = synchronized(this) {
            check(!closed) { "Cloud transport is closed" }
            transport ?: create().also { transport = it }
        }
        return selected.execute(request)
    }

    /** Called by the settings workflow to release the previous optional service. Can be re-enabled. */
    fun stop() {
        val previous = synchronized(this) { transport.also { transport = null } }
        (previous as? AutoCloseable)?.close()
    }

    override fun close() { synchronized(this) { closed = true }; stop() }
}
