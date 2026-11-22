package dev.ticbox.companion.engine.module

import dev.ticbox.companion.api.ToolboxException
import dev.ticbox.companion.api.WatchModuleTarget
import kotlin.coroutines.coroutineContext

/** Runs before a remote effect, against the operation's captured physical link. */
internal suspend fun requireModuleTarget(link: HealthModuleLink) {
    val expected = coroutineContext[WatchModuleTarget] ?: return
    if (expected.peer != link.peer) throw ToolboxException("device_changed")
}
