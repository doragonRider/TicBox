package dev.ticbox.companion.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Numeric Android/Wear values are retained; newer states are not coerced to an older state. */
data class CompanionCall(
    val id: String,
    val stateValue: Int = -1,
    /** Stock field 3 carries handle.schemeSpecificPart, despite its handle_uri name. */
    val handle: String? = null,
    val handlePresentation: Int? = null,
    val callerDisplayName: String? = null,
    val callerDisplayNamePresentation: Int? = null,
    val capabilities: Int? = null,
    val properties: Int? = null,
    val disconnectCause: CallDisconnectCause? = null,
    val connectTimeMillis: Long? = null,
    val videoState: Int? = null,
    val creationTimeMillis: Long? = null,
    val contactDisplayName: String? = null,
    val directionValue: Int = -1,
    val companionCanAnswer: Boolean = true,
)

data class CallDisconnectCause(val code: Int, val label: String? = null, val description: String? = null,
    val reason: String? = null, val tone: Int? = null)
data class CallAudioEndpoint(val id: String, val typeValue: Int, val name: String)

enum class CallAuthorizationAction { ASSOCIATE_WATCH, READ_PHONE_STATE, CALL_PHONE, APP_SETTINGS, BLUETOOTH_SETTINGS }
data class CallingAccess(
    val companionAssociation: Boolean = false,
    val watchProfileAssociation: Boolean = false,
    val manageOngoingCalls: Boolean = false,
    val defaultDialer: Boolean = false,
    val readPhoneState: Boolean = false,
    val callPhone: Boolean = false,
    val telecomAvailable: Boolean = true,
) {
    val canManage: Boolean get() = telecomAvailable && (manageOngoingCalls || defaultDialer)
    val suggestedActions: List<CallAuthorizationAction> get() = buildList {
        if (!canManage && telecomAvailable) add(CallAuthorizationAction.ASSOCIATE_WATCH)
        if (!readPhoneState) add(CallAuthorizationAction.READ_PHONE_STATE)
        if (!callPhone) add(CallAuthorizationAction.CALL_PHONE)
    }
}

sealed interface CallCommand {
    /** 1 answer,2 disconnect,3 hold,4 unhold,5 mute,6 unmute,7 silence; phone SDK also accepts 8/9. */
    data class Action(val callId: String = "", val actionValue: Int) : CallCommand
    data class Reject(val callId: String, val reasonValue: Int = 0, val textMessage: String = "") : CallCommand
    data class Dial(val number: String) : CallCommand
    data class Dtmf(val callId: String, val code: String) : CallCommand
    data class SelectAudioEndpoint(val endpointId: String) : CallCommand
    data object RouteAudioToWatch : CallCommand
}

enum class CallCommandStatus { DISPATCHED, DEFERRED, UNAVAILABLE, UNSUPPORTED, MALFORMED, FAILED, STALE_SESSION, DISABLED }
/** DISPATCHED is a platform invocation; actual call/audio changes are reported by system callbacks. */
data class CallCommandResult(val status: CallCommandStatus, val reason: String? = null, val callId: String? = null)
enum class CallingPhase { DISABLED, AUTHORIZATION_REQUIRED, DISCONNECTED, READY, ACTIVE, FAILED, CLOSED }
data class CompanionCallingState(
    val enabled: Boolean = true,
    val settingsLoaded: Boolean = false,
    val settingsDurable: Boolean = true,
    val phase: CallingPhase = CallingPhase.AUTHORIZATION_REQUIRED,
    val access: CallingAccess = CallingAccess(),
    val peer: PeerAddress? = null,
    val sessionId: Long = 0,
    val serviceConnected: Boolean = false,
    val calls: List<CompanionCall> = emptyList(),
    val muted: Boolean? = null,
    val audioEndpoints: List<CallAudioEndpoint> = emptyList(),
    val currentEndpointId: String? = null,
    val telephonyStateValue: Int? = null,
    val lastCommandResult: CallCommandResult? = null,
    val messagesPublished: Long = 0,
    val commandsDispatched: Long = 0,
    val commandsFailed: Long = 0,
    val lastIssue: String? = null,
)

interface CompanionCallingClient : AutoCloseable {
    val state: StateFlow<CompanionCallingState>
    suspend fun setEnabled(enabled: Boolean)
    suspend fun refreshAccess()
    suspend fun resync()
    suspend fun execute(command: CallCommand): CallCommandResult
    override fun close()

    companion object {
        val UNAVAILABLE: CompanionCallingClient = object : CompanionCallingClient {
            override val state = MutableStateFlow(CompanionCallingState(phase = CallingPhase.FAILED,
                lastIssue = "calling_platform_unavailable")).asStateFlow()
            override suspend fun setEnabled(enabled: Boolean) = Unit
            override suspend fun refreshAccess() = Unit
            override suspend fun resync() = Unit
            override suspend fun execute(command: CallCommand) = CallCommandResult(CallCommandStatus.UNAVAILABLE, "calling_platform_unavailable")
            override fun close() = Unit
        }
    }
}
