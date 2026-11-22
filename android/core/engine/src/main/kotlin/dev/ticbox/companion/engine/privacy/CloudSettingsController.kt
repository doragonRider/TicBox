package dev.ticbox.companion.engine.privacy

import dev.ticbox.companion.api.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Persist frontend choices and notify the runtime. Protocol engines do not own consent. */
class CloudSettingsController(
    initial: CloudPrivacySettings = CloudPrivacySettings(),
    private val persist: suspend (CloudPrivacySettings) -> Unit = {},
    private val onChanged: suspend (CloudPrivacySettings) -> Unit = {},
) : CloudSettingsClient {
    private val updates = Mutex()
    private val mutable = MutableStateFlow(initial.copy(enabledCapabilities = initial.enabledCapabilities.toSet()))
    override val state: StateFlow<CloudPrivacySettings> = mutable.asStateFlow()

    override suspend fun setEnabled(capability: CloudCapability, enabled: Boolean): CloudPrivacySettings = updates.withLock {
        val previous = mutable.value
        if (previous.allows(capability) == enabled) return@withLock previous
        val next = previous.copy(enabledCapabilities =
            if (enabled) previous.enabledCapabilities + capability else previous.enabledCapabilities - capability)
        if (!enabled) mutable.value = next
        try { persist(next) }
        finally { if (!enabled) onChanged(next) }
        if (enabled) { mutable.value = next; onChanged(next) }
        next
    }
}
