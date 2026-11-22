package dev.ticbox.companion.api

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Pins a confirmed multi-step module operation to one paired watch. Module engines check this
 * context before contacting their selected peer, so a UI check cannot race into another watch.
 * Reconnecting the same peer after its requested reboot is allowed; each operation still uses
 * its own physical-session and root device-identity checks. This is not an authorization grant.
 */
class WatchModuleTarget(val peer: PeerAddress) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WatchModuleTarget>
    override fun toString(): String = "WatchModuleTarget(peer=<redacted>)"
}
