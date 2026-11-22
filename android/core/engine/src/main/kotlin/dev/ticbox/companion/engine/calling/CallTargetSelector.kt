package dev.ticbox.companion.engine.calling

import dev.ticbox.companion.api.CompanionCall

/** Matches stock foreground fallback: answer one ringing call; never guess between multiple calls. */
object CallTargetSelector {
    /** Original BridgingInCallService waits rather than bridging Android's explicitly suppressed ring. */
    fun isSuppressedRinging(stateValue: Int, suppressedByDnd: Boolean) = stateValue == 2 && suppressedByDnd

    fun select(calls: List<CompanionCall>, requestedId: String, answer: Boolean = false): CompanionCall? {
        if (requestedId.isNotEmpty()) return calls.firstOrNull { it.id == requestedId }
        val candidates = if (answer) calls.filter { it.stateValue == 2 } else calls
        return candidates.singleOrNull()
    }
}
