package dev.ticbox.companion.demo

import dev.ticbox.companion.api.ApplicationRoute
import dev.ticbox.companion.api.CallAudioEndpoint
import dev.ticbox.companion.api.CallCommand
import dev.ticbox.companion.api.CallCommandResult
import dev.ticbox.companion.api.CallCommandStatus
import dev.ticbox.companion.api.CallingAccess
import dev.ticbox.companion.api.CallingPhase
import dev.ticbox.companion.api.CloudCapability
import dev.ticbox.companion.api.CloudPrivacySettings
import dev.ticbox.companion.api.CompanionCall
import dev.ticbox.companion.api.CompanionCallingClient
import dev.ticbox.companion.api.CompanionCallingState
import dev.ticbox.companion.api.DataAssetRecord
import dev.ticbox.companion.api.DataItemDraft
import dev.ticbox.companion.api.DataItemPage
import dev.ticbox.companion.api.DataItemQuery
import dev.ticbox.companion.api.DataItemRecord
import dev.ticbox.companion.api.DataScope
import dev.ticbox.companion.api.DataSyncBarrierResult
import dev.ticbox.companion.api.DataSyncBarrierStatus
import dev.ticbox.companion.api.DataSyncClient
import dev.ticbox.companion.api.DataSyncPhase
import dev.ticbox.companion.api.DataSyncState
import dev.ticbox.companion.api.GoogleAccountTransferPhase
import dev.ticbox.companion.api.GoogleAccountTransferRequest
import dev.ticbox.companion.api.GoogleServiceResult
import dev.ticbox.companion.api.GoogleServiceStatus
import dev.ticbox.companion.api.GoogleServices
import dev.ticbox.companion.api.GoogleServicesState
import dev.ticbox.companion.api.GoogleUserAction
import dev.ticbox.companion.api.MobvoiAccountClient
import dev.ticbox.companion.api.MobvoiAccountPhase
import dev.ticbox.companion.api.MobvoiAccountProfile
import dev.ticbox.companion.api.MobvoiAccountState
import dev.ticbox.companion.api.MobvoiCloudException
import dev.ticbox.companion.api.MobvoiCloudHealthClient
import dev.ticbox.companion.api.MobvoiCloudIssue
import dev.ticbox.companion.api.MobvoiCloudIssueKind
import dev.ticbox.companion.api.MobvoiCloudOwner
import dev.ticbox.companion.api.MobvoiCloudPage
import dev.ticbox.companion.api.MobvoiCloudRegion
import dev.ticbox.companion.api.MobvoiHealthCacheQuery
import dev.ticbox.companion.api.MobvoiHealthDataSource
import dev.ticbox.companion.api.MobvoiHealthPoint
import dev.ticbox.companion.api.MobvoiHealthSession
import dev.ticbox.companion.api.MobvoiHealthSetting
import dev.ticbox.companion.api.MobvoiHealthSyncPhase
import dev.ticbox.companion.api.MobvoiHealthSyncReport
import dev.ticbox.companion.api.MobvoiHealthSyncRequest
import dev.ticbox.companion.api.MobvoiHealthSyncState
import dev.ticbox.companion.api.MobvoiHealthUserProfile
import dev.ticbox.companion.api.MobvoiIdentifierKind
import dev.ticbox.companion.api.MobvoiLoginIdentifier
import dev.ticbox.companion.api.MobvoiProfileUpdate
import dev.ticbox.companion.api.MobvoiSportRecord
import dev.ticbox.companion.api.MobvoiSportTrackPoint
import dev.ticbox.companion.api.NetworkProxyState
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.api.SetupConsent
import java.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val ShortDelay = 300L
private const val StateDialing = 1
private const val StateHolding = 3
private const val StateActive = 4

/** In-memory call handling: dialing starts a call that answers itself. */
internal class DemoCalling(earpieceName: String, speakerName: String) : CompanionCallingClient {
    private val current =
        MutableStateFlow(
            CompanionCallingState(
                enabled = true,
                settingsLoaded = true,
                phase = CallingPhase.READY,
                access =
                    CallingAccess(
                        companionAssociation = true,
                        watchProfileAssociation = true,
                        manageOngoingCalls = true,
                        readPhoneState = true,
                        callPhone = true,
                    ),
                serviceConnected = true,
                sessionId = 1,
                muted = false,
                audioEndpoints =
                    listOf(
                        CallAudioEndpoint("earpiece", 1, earpieceName),
                        CallAudioEndpoint("speaker", 8, speakerName),
                    ),
                currentEndpointId = "earpiece",
            )
        )
    private var nextCall = 1

    override val state = current.asStateFlow()

    override suspend fun setEnabled(enabled: Boolean) {
        delay(ShortDelay)
        current.update {
            it.copy(
                enabled = enabled,
                phase = if (enabled) CallingPhase.READY else CallingPhase.DISABLED,
                calls = if (enabled) it.calls else emptyList(),
            )
        }
    }

    override suspend fun refreshAccess() = Unit

    override suspend fun resync() {
        delay(ShortDelay)
    }

    override suspend fun execute(command: CallCommand): CallCommandResult {
        if (!current.value.enabled) return CallCommandResult(CallCommandStatus.DISABLED)
        when (command) {
            is CallCommand.Dial -> dial(command.number)
            is CallCommand.Reject -> removeCall(command.callId)
            is CallCommand.Action -> perform(command)
            is CallCommand.SelectAudioEndpoint ->
                current.update { it.copy(currentEndpointId = command.endpointId) }
            is CallCommand.Dtmf,
            CallCommand.RouteAudioToWatch -> Unit
        }
        return CallCommandResult(CallCommandStatus.DISPATCHED)
    }

    private suspend fun dial(number: String) {
        val id = "demo-call-${nextCall++}"
        current.update {
            it.copy(
                phase = CallingPhase.ACTIVE,
                calls = it.calls + CompanionCall(id, StateDialing, handle = number),
            )
        }
        delay(ShortDelay)
        setCallState(id, StateActive)
    }

    private fun perform(command: CallCommand.Action) {
        when (command.actionValue) {
            1,
            4 -> setCallState(command.callId, StateActive)
            2 -> removeCall(command.callId)
            3 -> setCallState(command.callId, StateHolding)
            5 -> current.update { it.copy(muted = true) }
            6 -> current.update { it.copy(muted = false) }
        }
    }

    private fun setCallState(id: String, value: Int) {
        current.update { state ->
            state.copy(
                calls = state.calls.map { if (it.id == id) it.copy(stateValue = value) else it }
            )
        }
    }

    private fun removeCall(id: String) {
        current.update { state ->
            val calls = state.calls.filterNot { it.id == id }
            state.copy(
                calls = calls,
                phase = if (calls.isEmpty()) CallingPhase.READY else CallingPhase.ACTIVE,
            )
        }
    }

    override fun close() = Unit
}

/** Nothing leaves the phone: any password or code signs in, as long as the cloud is switched on. */
internal class DemoAccount(
    private val privacy: StateFlow<CloudPrivacySettings>,
    private val nickname: String,
) : MobvoiAccountClient {
    private val current = MutableStateFlow(MobvoiAccountState())

    override val state = current.asStateFlow()

    private fun requireCloud(operation: String) {
        if (!privacy.value.allows(CloudCapability.MOBVOI_CLOUD)) {
            throw MobvoiCloudException(
                MobvoiCloudIssue(MobvoiCloudIssueKind.CONSENT_REQUIRED, operation)
            )
        }
    }

    private suspend fun signIn(
        email: String?,
        phone: String?,
        region: MobvoiCloudRegion,
    ): MobvoiAccountProfile {
        requireCloud("login")
        current.update { it.copy(phase = MobvoiAccountPhase.AUTHENTICATING) }
        delay(ShortDelay)
        val profile =
            MobvoiAccountProfile(
                MobvoiCloudOwner(region, "demo-wwid", "demo-account"),
                email = email,
                phone = phone,
                nickname = nickname,
            )
        current.value = MobvoiAccountState(MobvoiAccountPhase.SIGNED_IN, profile)
        return profile
    }

    override suspend fun restore() = current.value.profile

    override suspend fun loginPassword(
        identifier: MobvoiLoginIdentifier,
        password: CharArray,
        region: MobvoiCloudRegion,
    ): MobvoiAccountProfile {
        val email = identifier.value.takeIf { identifier.kind == MobvoiIdentifierKind.EMAIL }
        val phone = identifier.value.takeIf { identifier.kind == MobvoiIdentifierKind.PHONE }
        return signIn(email, phone, region)
    }

    override suspend fun requestCaptcha(identifier: MobvoiLoginIdentifier, language: String) {
        requireCloud("captcha")
        delay(ShortDelay)
    }

    override suspend fun loginCaptcha(phone: String, code: String, region: MobvoiCloudRegion) =
        signIn(null, phone, region)

    private fun profile() = checkNotNull(current.value.profile) { "Not signed in" }

    override suspend fun refreshProfile(): MobvoiAccountProfile {
        requireCloud("profile")
        delay(ShortDelay)
        return profile()
    }

    override suspend fun updateProfile(update: MobvoiProfileUpdate): MobvoiAccountProfile {
        requireCloud("profile")
        delay(ShortDelay)
        val next = profile().copy(nickname = update.nickname ?: profile().nickname)
        current.update { it.copy(profile = next) }
        return next
    }

    override suspend fun saveTimeZone(timeZoneId: String) {
        requireCloud("time_zone")
        delay(ShortDelay)
    }

    override suspend fun logout(revokeRemote: Boolean, clearCachedHealth: Boolean) {
        delay(ShortDelay)
        current.value = MobvoiAccountState()
    }

    override fun close() = Unit
}

/** A cloud cache that only fills up after a demo sync. */
internal class DemoCloudHealth(
    private val account: StateFlow<MobvoiAccountState>,
    private val clock: Clock,
) : MobvoiCloudHealthClient {
    private val current = MutableStateFlow(MobvoiHealthSyncState())
    private var cachedOwner: MobvoiCloudOwner? = null
    private var sports = emptyList<MobvoiSportRecord>()

    override val state = current.asStateFlow()

    override suspend fun sync(request: MobvoiHealthSyncRequest): MobvoiHealthSyncReport {
        val owner = checkNotNull(account.value.profile?.owner) { "Not signed in" }
        current.value = MobvoiHealthSyncState(MobvoiHealthSyncPhase.SYNCING, owner)
        delay(ShortDelay * 2)
        cachedOwner = owner
        sports = sampleSports()
        current.value =
            MobvoiHealthSyncState(
                MobvoiHealthSyncPhase.COMPLETED,
                owner,
                sourceCount = 1,
                pointCount = 240,
                sessionCount = 6,
                sportCount = sports.size,
            )
        return MobvoiHealthSyncReport(owner, 1, 240, 6, sports.size, false, emptyList())
    }

    private fun sampleSports(): List<MobvoiSportRecord> {
        val now = clock.millis()
        return listOf(2, 1).map { daysAgo ->
            val start = now - daysAgo * 86_400_000L
            MobvoiSportRecord(
                key = "demo-cloud-$daysAgo",
                recordId = "demo-cloud-$daysAgo",
                deviceId = "demo-watch",
                type = "0",
                startMillis = start,
                endMillis = start + 1_800_000L,
                totalMotionTime = null,
                totalDistance = null,
                totalCalorie = null,
                totalSteps = null,
                averageHeartRate = null,
                gpsType = null,
                tracks = emptyList(),
            )
        }
    }

    override fun cancelSync() {
        current.update { it.copy(phase = MobvoiHealthSyncPhase.CANCELLED) }
    }

    override suspend fun cachedOwners() = listOfNotNull(cachedOwner)

    override suspend fun sources(owner: MobvoiCloudOwner) =
        if (owner == cachedOwner) listOf(MobvoiHealthDataSource("derived:com.mobvoi.fitness"))
        else emptyList()

    override suspend fun refreshUserProfile(): MobvoiHealthUserProfile {
        val owner = checkNotNull(account.value.profile?.owner) { "Not signed in" }
        delay(ShortDelay)
        return profile(owner)
    }

    private fun profile(owner: MobvoiCloudOwner) =
        MobvoiHealthUserProfile(
            owner,
            birthday = null,
            gender = null,
            height = "172",
            weight = "64",
            unit = null,
            goalTodayActive = 12,
            goalTodayExercise = 1_800,
            goalTodayStep = 8_000,
            expectedSleepTime = null,
            expectedGetUpTime = null,
            heartRateWarningFitnessUpperLimit = 170,
            heartRateWarningRestLowerLimit = 45,
            heartRateWarningRestUpperLimit = 110,
        )

    override suspend fun cachedUserProfile(owner: MobvoiCloudOwner) =
        if (owner == cachedOwner) profile(owner) else null

    override suspend fun readUserSettings(type: String) = emptyList<MobvoiHealthSetting>()

    override suspend fun writeUserSetting(setting: MobvoiHealthSetting) = Unit

    override suspend fun points(query: MobvoiHealthCacheQuery) =
        MobvoiCloudPage(emptyList<MobvoiHealthPoint>(), null)

    override suspend fun sessions(query: MobvoiHealthCacheQuery) =
        MobvoiCloudPage(emptyList<MobvoiHealthSession>(), null)

    override suspend fun sports(query: MobvoiHealthCacheQuery) =
        MobvoiCloudPage(if (query.owner == cachedOwner) sports else emptyList(), null)

    override suspend fun downloadSportRecord(recordId: String): MobvoiSportRecord? {
        delay(ShortDelay)
        val track =
            (0L..59L).map { tick ->
                MobvoiSportTrackPoint(tick, null, null, null, 120, null, null, null, null, null)
            }
        sports = sports.map { if (it.recordId == recordId) it.copy(tracks = track) else it }
        return sports.firstOrNull { it.recordId == recordId }
    }

    override suspend fun deleteSportRecord(recordId: String) {
        delay(ShortDelay)
        sports = sports.filterNot { it.recordId == recordId }
    }

    override suspend fun clearCache(owner: MobvoiCloudOwner) {
        delay(ShortDelay)
        cachedOwner = null
        sports = emptyList()
        current.value = MobvoiHealthSyncState()
    }

    override fun close() = Unit
}

/** Reports what real Google services would, honoring the privacy switches. */
internal class DemoGoogle(private val privacy: StateFlow<CloudPrivacySettings>) : GoogleServices {
    private val current = MutableStateFlow(GoogleServicesState())

    override val state = current.asStateFlow()
    override val actions = MutableStateFlow(emptyList<GoogleUserAction>()).asStateFlow()

    override suspend fun transferAccount(
        peer: PeerAddress,
        sessionId: Long,
        request: GoogleAccountTransferRequest,
    ): GoogleServiceResult {
        if (!privacy.value.allows(CloudCapability.GOOGLE_ACCOUNT_TRANSFER)) {
            return GoogleServiceResult(GoogleServiceStatus.UNAVAILABLE, "account_transfer")
        }
        current.update { it.copy(accountTransfer = GoogleAccountTransferPhase.TRANSFERRING) }
        delay(ShortDelay)
        val result =
            GoogleServiceResult(
                GoogleServiceStatus.COMPLETE,
                "account_transfer",
                transferredAccounts = 1,
            )
        current.update {
            it.copy(
                accountTransfer = GoogleAccountTransferPhase.COMPLETE,
                lastAccountResult = result,
            )
        }
        return result
    }

    override suspend fun applyConsents(
        peer: PeerAddress,
        sessionId: Long,
        consents: List<SetupConsent>,
    ) = emptyList<GoogleServiceResult>()

    override suspend fun setCloudSyncSetting(
        peer: PeerAddress,
        sessionId: Long,
        enabled: Boolean,
    ): GoogleServiceResult {
        delay(ShortDelay)
        val allowed = privacy.value.allows(CloudCapability.GOOGLE_CLOUD_SYNC)
        return GoogleServiceResult(
            if (allowed) GoogleServiceStatus.COMPLETE else GoogleServiceStatus.UNAVAILABLE,
            "cloud_sync",
        )
    }

    override fun abortAccountTransfer() {
        current.update { it.copy(accountTransfer = GoogleAccountTransferPhase.CANCELLED) }
    }

    override fun close() = Unit
}

/** Fixed counters; the demo has no data layer behind it. */
internal class DemoDataSync : DataSyncClient {
    private val current =
        MutableStateFlow(
            DataSyncState(phase = DataSyncPhase.ACTIVE, sessionId = 1, receivedItems = 128)
        )

    override val state = current.asStateFlow()

    override suspend fun scopes(peer: PeerAddress?) = emptyList<DataScope>()

    override suspend fun items(scope: DataScope, query: DataItemQuery) =
        DataItemPage(emptyList(), null)

    override suspend fun publish(
        scope: DataScope,
        route: ApplicationRoute,
        draft: DataItemDraft,
    ): DataItemRecord = throw UnsupportedOperationException("No data layer in the demo")

    override suspend fun delete(
        scope: DataScope,
        route: ApplicationRoute,
        uri: String,
    ): DataItemRecord = throw UnsupportedOperationException("No data layer in the demo")

    override suspend fun putAsset(
        scope: DataScope,
        route: ApplicationRoute,
        bytes: ByteArray,
    ): DataAssetRecord = throw UnsupportedOperationException("No data layer in the demo")

    override suspend fun asset(scope: DataScope, route: ApplicationRoute, digest: String) = null

    override suspend fun requestAsset(
        scope: DataScope,
        route: ApplicationRoute,
        digest: String,
        permissionOnly: Boolean,
    ) = Unit

    override suspend fun awaitProgressBarrier(timeoutMillis: Long) =
        DataSyncBarrierResult(DataSyncBarrierStatus.UNAVAILABLE, sessionId = 1)

    override suspend fun requestSync() {
        delay(ShortDelay)
        current.update { it.copy(receivedItems = it.receivedItems + 1) }
    }

    override fun close() = Unit
}

internal fun demoNetworkState(): StateFlow<NetworkProxyState> =
    MutableStateFlow(NetworkProxyState()).asStateFlow()
