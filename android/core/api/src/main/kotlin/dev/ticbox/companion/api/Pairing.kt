package dev.ticbox.companion.api

import kotlinx.coroutines.flow.StateFlow

enum class PairingPhase { IDLE, PREPARING, ASSOCIATING, CLEARING_OLD_BOND, BONDING, CONNECTING, REQUESTING_PERMISSION_CONSENT, TRANSFERRING_PERMISSIONS, COMPLETED, CANCELLED, FAILED }
enum class PairingActionKind { BLUETOOTH_PERMISSIONS, ENABLE_BLUETOOTH, ASSOCIATION_APPROVAL, CANCEL_ASSOCIATION, PERMISSION_TRANSFER_CONSENT, FORGET_BLUETOOTH_DEVICE }
enum class PairingPermissionTransfer { NOT_REQUESTED, WAITING_FOR_CONSENT, DECLINED, TRANSFERRING, COMPLETED, CANCELLED, FAILED }
enum class PairingConsent { NOT_REQUESTED, USER_APPROVED, SYSTEM_APPROVED, DECLINED, UNKNOWN_SYSTEM_STATE }

/** The platform host resolves [id] to an Android launch descriptor. No Android object crosses this API. */
data class PairingUserAction(
    val id: String,
    val operationId: Long,
    val kind: PairingActionKind,
    val permissions: List<String> = emptyList(),
    val reason: String,
)

data class PairingOptions(
    val peer: PeerAddress? = null,
    val namePattern: String? = "(?i).*ticwatch.*",
    val requestWatchProfile: Boolean = true,
    val reuseAssociation: Boolean = true,
    val syncPermissions: Boolean = true,
    /** New/re-pairing only: clear this selected peer's pre-existing bond before making a new one.
     * Background reconnects use WatchClient.connect(), and permission sync explicitly keeps bonds. */
    val replaceExistingBond: Boolean = true,
)

data class PairingAssociation(val id: Int, val peer: PeerAddress?, val displayName: String? = null, val watchProfile: Boolean = false)
data class AssociationMapping(val associationId: Int, val peer: PeerAddress, val watchProfile: Boolean, val observedAtMillis: Long)
enum class PairingFailureKind { PLATFORM_UNAVAILABLE, PERMISSION_DENIED, ASSOCIATION, BOND, LINK, PERMISSION_TRANSFER, TIMEOUT, UNEXPECTED }
data class PairingFailure(val kind: PairingFailureKind, val reason: String, val retryable: Boolean = true)

data class PairingSnapshot(
    val operationId: Long = 0,
    val phase: PairingPhase = PairingPhase.IDLE,
    val peer: PeerAddress? = null,
    val associationId: Int? = null,
    val associationObserved: Boolean = false,
    val bonded: Boolean = false,
    /** Observed before the system chooser; does not itself establish that keys are stale. */
    val preExistingBond: Boolean = false,
    val oldBondCleared: Boolean = false,
    val linkSessionId: Long? = null,
    val permissionConsent: PairingConsent = PairingConsent.NOT_REQUESTED,
    /** A platform transfer callback confirms the request, not every remote permission grant. */
    val permissionTransfer: PairingPermissionTransfer = PairingPermissionTransfer.NOT_REQUESTED,
    val failure: PairingFailure? = null,
)

interface PairingClient : AutoCloseable {
    val state: StateFlow<PairingSnapshot>
    /** Includes a cancellation dialog action if the OS needs the Activity host to close its chooser. */
    val actions: StateFlow<List<PairingUserAction>>
    /** Starts a service-owned operation. Device selection and authorizations stay in system UI. */
    suspend fun start(options: PairingOptions = PairingOptions()): Long
    /** Reuses or creates this app's association, establishes the link, then performs actual CDM transfer. */
    suspend fun syncPermissions(peer: PeerAddress): Long
    /**
     * Gives an already bonded watch this app's own association, as after its identity was imported
     * into a newly installed app. Keeps the bond and transfers no permissions.
     */
    suspend fun associate(peer: PeerAddress): Long =
        start(PairingOptions(peer = peer, syncPermissions = false, replaceExistingBond = false))
    /** Stops further work; cannot undo a bond removal already accepted by Android. Never resets. */
    fun cancel()
    /** Cancels only the matching operation; a late caller cannot cancel its replacement. */
    fun cancel(operationId: Long): Boolean
    override fun close()
}

class PairingBusyException : IllegalStateException("A pairing operation is already running")
class PairingWorkflowException(val failure: PairingFailure) : java.io.IOException(failure.reason)
