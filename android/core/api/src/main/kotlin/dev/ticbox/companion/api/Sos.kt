package dev.ticbox.companion.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Values recovered from both the phone settings screen and the watch's SOS preferences. */
enum class SosFallDetectionMode(val wireValue: Int) { OFF(-1), ALWAYS(0), EXERCISE_ONLY(1) }

object SosLimits {
    const val MAX_CONTACTS = 4
    const val MAX_NUMBER_CHARS = 128
    const val MAX_NAME_CHARS = 200
}

data class SosEmergencyContact(val name: String, val number: String) {
    override fun toString() = "SosEmergencyContact(<private>)"
}

/** A watch preference snapshot, not proof that a sensor, telephone call, or SMS has succeeded. */
data class SosWatchSettings(
    val updatedAtMillis: Long,
    val buttonTriggerEnabled: Boolean,
    val emergencyNumber: String,
    /** Keep unknown future values readable; setters accept only [SosFallDetectionMode]. */
    val fallDetectionValue: Int,
    val shareLocation: Boolean,
    /** null means the reported contacts payload could not be interpreted safely. */
    val contacts: List<SosEmergencyContact>?,
    /** Exact original opaque text is retained for display/export, never diagnostics. */
    val contactsPayload: String,
) {
    val fallDetectionMode: SosFallDetectionMode? get() = SosFallDetectionMode.entries.firstOrNull { it.wireValue == fallDetectionValue }
    override fun toString() = "SosWatchSettings(updatedAtMillis=$updatedAtMillis, buttonTriggerEnabled=$buttonTriggerEnabled, fallDetectionValue=$fallDetectionValue, shareLocation=$shareLocation, contactsReadable=${contacts != null})"
}

enum class SosPhase { DISCONNECTED, CONNECTED, READING, WRITING, CLOSED }
enum class SosCommandStatus { CONFIRMED, TIMED_OUT, READ_BACK_MISMATCH, UNAVAILABLE, FAILED, INVALID_ARGUMENT }
enum class SosSettingField { EMERGENCY_NUMBER, CONTACTS, BUTTON_TRIGGER, SHARE_LOCATION, FALL_DETECTION }
data class SosCommandResult(val status: SosCommandStatus, val field: SosSettingField? = null, val reason: String? = null)
data class SosIssue(val operation: String, val reason: String)
enum class SosWatchEditKind { EMERGENCY_NUMBER, CONTACTS }
data class SosWatchEditRequest(val sequence: Long, val kind: SosWatchEditKind, val sessionId: Long)
data class SosState(
    val sessionId: Long = 0,
    val peer: PeerAddress? = null,
    val phase: SosPhase = SosPhase.DISCONNECTED,
    val settings: SosWatchSettings? = null,
    /** False after disconnect/reconnect until a snapshot arrives from that physical session. */
    val settingsCurrent: Boolean = false,
    val lastOperation: SosCommandResult? = null,
    val lastIssue: SosIssue? = null,
    val lastWatchEditRequest: SosWatchEditRequest? = null,
    val emergencyEventsReceived: Long = 0,
)

data class SosPhoneAssistanceConfig(
    /** Explicit opt-in to phone-side emergency-contact SMS; does not enable watch sensors. */
    val enabled: Boolean = false,
    /** null selects the system's default SMS subscription; multi-SIM resolution is platform-owned. */
    val subscriptionId: Int? = null,
)
data class SosPhoneCapabilities(
    val smsSupported: Boolean = false,
    val smsPermissionGranted: Boolean = false,
    val foregroundLocationPermissionGranted: Boolean = false,
    val backgroundLocationPermissionGranted: Boolean = false,
    val notificationPermissionGranted: Boolean = false,
    val usableSmsSubscription: Boolean = false,
    /** Stable non-sensitive platform reason, such as sms_permission_missing. */
    val reason: String? = null,
)
enum class SosAssistancePhase { DISABLED, READY, STARTING, ACTIVE, STOPPED, UNAVAILABLE, FAILED }
data class SosAssistanceState(
    val config: SosPhoneAssistanceConfig = SosPhoneAssistanceConfig(),
    val capabilities: SosPhoneCapabilities = SosPhoneCapabilities(),
    val phase: SosAssistancePhase = SosAssistancePhase.DISABLED,
    val eventId: String? = null,
    val recipientCount: Int = 0,
    val startedAtMillis: Long? = null,
    val expiresAtMillis: Long? = null,
    /** Counts submitted SMS parts, not network delivery or a recipient's acknowledgement. */
    val smsPartsSubmitted: Int = 0,
    val issue: SosIssue? = null,
    /** Configured contacts skipped because their number cannot safely be used for SMS. */
    val skippedRecipientCount: Int = 0,
)
data class SosEmergencyRequest(
    val eventId: String,
    val peer: PeerAddress,
    val sessionId: Long,
    val receivedAtMillis: Long,
    val contacts: List<SosEmergencyContact>,
    val shareLocation: Boolean,
) {
    override fun toString() = "SosEmergencyRequest(eventId=$eventId, sessionId=$sessionId, receivedAtMillis=$receivedAtMillis, recipientCount=${contacts.size}, shareLocation=$shareLocation)"
}
data class SosEmergencyHandling(val accepted: Boolean, val reason: String? = null)

/** Android owns permission requests; this backend only reports permission facts and execution state. */
interface SosPhoneAssistanceClient : AutoCloseable {
    val state: StateFlow<SosAssistanceState>
    suspend fun refreshCapabilities()
    suspend fun configure(config: SosPhoneAssistanceConfig)
    suspend fun stop()
    override fun close()

    companion object {
        val UNAVAILABLE = object : SosPhoneAssistanceClient {
            override val state = MutableStateFlow(SosAssistanceState(phase = SosAssistancePhase.UNAVAILABLE))
            override suspend fun refreshCapabilities() = Unit
            override suspend fun configure(config: SosPhoneAssistanceConfig) { throw IllegalStateException("sos_phone_assistance_unavailable") }
            override suspend fun stop() = Unit
            override fun close() = Unit
        }
    }
}

interface SosClient : AutoCloseable {
    val state: StateFlow<SosState>
    val phoneAssistance: SosPhoneAssistanceClient
    suspend fun refresh(): SosCommandResult
    suspend fun setEmergencyNumber(number: String): SosCommandResult
    suspend fun setContacts(contacts: List<SosEmergencyContact>): SosCommandResult
    suspend fun setButtonTrigger(enabled: Boolean): SosCommandResult
    suspend fun setShareLocation(enabled: Boolean): SosCommandResult
    suspend fun setFallDetection(mode: SosFallDetectionMode): SosCommandResult
    override fun close()

    companion object {
        val UNAVAILABLE = object : SosClient {
            override val state = MutableStateFlow(SosState())
            override val phoneAssistance = SosPhoneAssistanceClient.UNAVAILABLE
            private fun unavailable() = SosCommandResult(SosCommandStatus.UNAVAILABLE, reason = "sos_unavailable")
            override suspend fun refresh() = unavailable()
            override suspend fun setEmergencyNumber(number: String) = unavailable()
            override suspend fun setContacts(contacts: List<SosEmergencyContact>) = unavailable()
            override suspend fun setButtonTrigger(enabled: Boolean) = unavailable()
            override suspend fun setShareLocation(enabled: Boolean) = unavailable()
            override suspend fun setFallDetection(mode: SosFallDetectionMode) = unavailable()
            override fun close() = Unit
        }
    }
}
