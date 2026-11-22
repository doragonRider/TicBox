package dev.ticbox.companion.api

import kotlinx.coroutines.flow.StateFlow

/** Phone-side setup workflow state. Wire status values stay separate from these UI phases. */
enum class SetupPhase { IDLE, RUNNING, WAITING_FOR_USER, PROBED, COMPLETED, CANCELLED, FAILED }
enum class SetupStep { CHANNEL, PROBE, WATCH_READY, TIME, TETHER, TERMS, ACCOUNT, RESTORE, CHECKIN, INITIAL_DATA_PREPARATION, DATA_SYNC_COMPLETION, OPTIONAL, COMPLETE, OOBE }
enum class SetupStepState { RUNNING, WAITING, SUCCEEDED, SKIPPED, FAILED }
data class SetupStepProgress(val step: SetupStep, val state: SetupStepState)
enum class SetupTetherMode(val wireValue: Int) { STANDALONE(1), TETHERED(2), RESTRICTED(3) }
enum class SetupAccountChoice { ASK, SKIP, TRANSFER }
/** Device check-in may use the watch's Internet connection; local setup skips it by default. */
enum class SetupCheckinChoice { SKIP, ATTEMPT }
enum class SetupOptionalStep { RESTORE, DAY_ZERO_UPDATE, GOOGLE_ASSISTANT, LOCK_SCREEN, APP_INSTALL, CDM_PERMISSION_SYNC }
enum class SetupOptinType(val wireValue: Int) { ACCOUNTS(1), CLOUDSYNC(2), COMMUNICATION(3), CALENDAR(4), NOTIFICATIONS(5), LOGGING(6), LOCATION(7) }
enum class SetupOptinChoice(val wireValue: Int) { ACCEPTED(1), DECLINED(2), SKIPPED(3) }

/** A caller-supplied decision, never automatically accepted by the setup engine. */
data class SetupConsent(val type: SetupOptinType, val choice: SetupOptinChoice, val decidedAtEpochMillis: Long)
data class SetupOptinObservation(val typeValue: Int, val stateValue: Int, val decidedAtEpochMillis: Long?)

data class SetupSystemInfo(
    val versionValue: Int? = null,
    val capabilities: List<Int> = emptyList(),
    val editionValue: Int? = null,
    val platformVersion: Int? = null,
    val maintenanceReleaseVersion: Int? = null,
    val setupCapabilities: List<Int> = emptyList(),
    val setupCapabilityVersionValue: Int? = null,
) {
    /** Stock ignores setup capability declarations when their version is UNKNOWN/absent. */
    val effectiveSetupCapabilities: List<Int>
        get() = if ((setupCapabilityVersionValue ?: 0) == 0) emptyList() else setupCapabilities
}

data class SetupCellularInfo(val mnc: Int? = null, val mcc: Int? = null, val carrierName: String? = null)
data class SetupCheckinStatus(val statusValue: Int, val factoryReset: Boolean? = null)

data class SetupProbe(
    val peer: PeerAddress,
    val sessionId: Long,
    /** Raw Setup Status enum, not GMS CONNECT version or local Android provisioning flags. */
    val watchStatusValue: Int?,
    val systemInfo: SetupSystemInfo?,
    val cellularInfo: SetupCellularInfo? = null,
    val connectionInfoReceived: Boolean = false,
)

data class SetupOptions(
    val tetherMode: SetupTetherMode = SetupTetherMode.TETHERED,
    /** null asks the host; false is an explicit decline and cancels this local workflow. */
    val termsAccepted: Boolean? = null,
    val accountChoice: SetupAccountChoice = SetupAccountChoice.ASK,
    /** ATTEMPT explicitly opts into check-in queries; the active CHECKIN action can still be skipped. */
    val checkinChoice: SetupCheckinChoice = SetupCheckinChoice.SKIP,
    val consents: List<SetupConsent> = emptyList(),
    val optionalSteps: List<SetupOptionalStep> = emptyList(),
    /** Require the host's concrete locale/settings preparation; no empty-queue sync inference. */
    val requireInitialDataPreparation: Boolean = false,
    /** Opt into a separately supplied GMS DATA_SYNC_PROGRESS completion implementation. */
    val awaitDataSyncCompletion: Boolean = false,
    /** Only applies when the watch advertises SIGNALS_OOBE_DONE. */
    val waitForOobeDone: Boolean = true,
)

enum class SetupActionKind { REVIEW_TERMS, CHOOSE_ACCOUNT, ACCOUNT_TRANSFER, CHECKIN, INITIAL_DATA_PREPARATION, DATA_SYNC_COMPLETION, OPTIONAL_STEP }
enum class SetupDecision { CONTINUE, RETRY, SKIP_OPTIONAL, CANCEL }

data class SetupUserAction(
    val id: Long,
    val kind: SetupActionKind,
    val allowedDecisions: Set<SetupDecision>,
    /** Stable local reason, suitable for mapping to localized UI copy. */
    val reason: String,
    val optionalStep: SetupOptionalStep? = null,
)

data class SetupUserResponse(
    val decision: SetupDecision,
    val termsAccepted: Boolean? = null,
    val accountChoice: SetupAccountChoice = SetupAccountChoice.ASK,
    val consents: List<SetupConsent> = emptyList(),
)

enum class SetupFailureKind { LINK_LOST, CHANNEL_CLOSED, TIMEOUT, REMOTE_REJECTED, DEPENDENCY, PROTOCOL, UNSUPPORTED_MODE, UNEXPECTED }
data class SetupFailure(val kind: SetupFailureKind, val reason: String)

data class SetupSnapshot(
    val runId: Long = 0,
    val peer: PeerAddress? = null,
    val sessionId: Long = 0,
    val phase: SetupPhase = SetupPhase.IDLE,
    val currentStep: SetupStep? = null,
    val progress: List<SetupStepProgress> = emptyList(),
    val probe: SetupProbe? = null,
    val checkin: SetupCheckinStatus? = null,
    val observedOptins: List<SetupOptinObservation> = emptyList(),
    /** CHECKIN may offer skip/cancel while phase remains RUNNING and the status query continues. */
    val action: SetupUserAction? = null,
    val failure: SetupFailure? = null,
    /** Exact observations, not a claim that phone-side return completed watch provisioning. */
    val watchCompleteObserved: Boolean = false,
    val watchOobeDoneObserved: Boolean = false,
    /** Local locale/settings preparation, independent from wire data synchronization. */
    val dataPreparationCompleted: Boolean = false,
    val dataSyncCompletionObserved: Boolean = false,
)

class SetupBusyException : IllegalStateException("A setup operation is already running")
class SetupWorkflowException(val failure: SetupFailure) : java.io.IOException(failure.reason)

/** Owned by the backend host. UI drives explicit decisions and observes progress. */
interface SetupClient : AutoCloseable {
    val state: StateFlow<SetupSnapshot>
    /** Query the current link using a temporary Channel; sends no TIME, consent, tether or COMPLETE. */
    suspend fun probe(): SetupProbe
    /** Start a service-owned workflow and return after acceptance, before remote completion. */
    suspend fun start(options: SetupOptions = SetupOptions())
    /** Accept only the active action's allowed decision. Stale or mismatched actions return false. */
    fun resume(actionId: Long, response: SetupUserResponse): Boolean
    /** Stop the local operation and close its Channel; does not reset/unpair the watch. */
    fun cancel()
    override fun close()
}
