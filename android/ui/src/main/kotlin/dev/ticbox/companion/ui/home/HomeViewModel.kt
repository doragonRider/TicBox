package dev.ticbox.companion.ui.home

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.viewModelScope
import dev.ticbox.companion.api.ConnectionPhase
import dev.ticbox.companion.api.ConnectionRecoveryPhase
import dev.ticbox.companion.api.ConnectionRecoveryState
import dev.ticbox.companion.api.DeviceSettingsState
import dev.ticbox.companion.api.PeerAddress
import dev.ticbox.companion.api.StepSnapshot
import dev.ticbox.companion.api.WatchFaceFavorite
import dev.ticbox.companion.api.WatchFaceManagementState
import dev.ticbox.companion.api.WatchFacePhoto
import dev.ticbox.companion.api.WatchObservation
import dev.ticbox.companion.api.WatchSnapshot
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.common.MINUTE_MILLIS
import dev.ticbox.companion.ui.faces.FacePreviewLoader
import dev.ticbox.companion.ui.faces.PhotoFacePictures
import dev.ticbox.companion.ui.health.HealthRecordsLoader
import dev.ticbox.companion.ui.health.LocalHealthViewModel
import dev.ticbox.companion.ui.health.stepGoalFor
import dev.ticbox.companion.ui.session.SessionController
import dev.ticbox.companion.ui.session.SessionState
import dev.ticbox.companion.ui.session.WatchSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The step count on the home page. [lastKnown] when the watch may have counted more since. */
data class HomeSteps(val count: Int, val lastKnown: Boolean)

/**
 * The records stored on this phone come first, so this page agrees with the health pages. What the
 * watch last reported only fills in while nothing is stored for today, and only if it came today:
 * the report isn't sent regularly and can stay behind, or be from an earlier day.
 */
internal fun homeSteps(
    stored: Int?,
    reported: WatchObservation<StepSnapshot>?,
    linkReady: Boolean,
    today: LocalDate,
    zone: ZoneId,
): HomeSteps? =
    when {
        stored != null -> HomeSteps(stored, lastKnown = !linkReady)
        reported == null -> null
        Instant.ofEpochMilli(reported.receivedAtEpochMillis).atZone(zone).toLocalDate() != today ->
            null
        else -> HomeSteps(reported.value.currentSteps, reported.stale)
    }

data class HomeState(
    val watch: WatchSnapshot,
    val device: DeviceSettingsState,
    val recovery: ConnectionRecoveryState,
    val faces: WatchFaceManagementState,
    /** Chosen in this app; otherwise the watch's own goal applies. */
    val chosenStepGoal: Int?,
) {
    val stepGoal: Int?
        get() = chosenStepGoal ?: watch.steps?.value?.goal

    val paired: Boolean
        get() = watch.connection.peer != null || recovery.desiredPeer != null

    val linkReady: Boolean
        get() = watch.connection.phase == ConnectionPhase.LINK_READY

    /** The service has not read the saved watch yet, so [paired] may still be false. */
    val loadingSavedWatch: Boolean
        get() =
            recovery.phase == ConnectionRecoveryPhase.IDLE &&
                !recovery.durable &&
                watch.connection.peer == null

    val name: String?
        get() = device.info?.model ?: watch.connection.peerName

    val currentFace: WatchFaceFavorite?
        get() = currentFaceOf(watch, faces)

    val currentFaceName: String?
        get() = currentFace?.let { face ->
            faces.installed.firstOrNull { it.componentName == face.componentName }?.displayedName
        }
}

/**
 * The current favorite, only when the catalog belongs to this exact link: a preview from an older
 * session or another watch must not be shown as the current face.
 */
private data class CurrentFace(
    val face: WatchFaceFavorite?,
    val photo: WatchFacePhoto?,
    val battery: Int?,
)

internal fun currentFaceOf(
    watch: WatchSnapshot,
    faces: WatchFaceManagementState,
): WatchFaceFavorite? {
    val connection = watch.connection
    if (connection.phase != ConnectionPhase.LINK_READY || connection.sessionId == 0L) return null
    if (faces.sessionId != connection.sessionId || faces.scope?.peer != connection.peer) return null
    val current = faces.current ?: return null
    val id = current.favoriteId ?: return null
    return faces.favorites.singleOrNull {
        it.id == id && (current.componentName == null || current.componentName == it.componentName)
    }
}

internal const val BATTERY_REFRESH_MILLIS = 10 * MINUTE_MILLIS

/**
 * How long to wait before asking the watch for its battery. A link that hasn't asked yet asks right
 * away, because connecting brings no new value by itself. After that, a value the watch sent counts
 * as a refresh too, however it was asked for. The limit stops a phone clock moved back from
 * stretching the wait.
 */
internal fun batteryRefreshDelay(
    battery: WatchObservation<Int>?,
    askedAtMillis: Long?,
    nowMillis: Long,
): Long {
    if (askedAtMillis == null) return 0
    val last = maxOf(askedAtMillis, battery?.receivedAtEpochMillis ?: askedAtMillis)
    return (last + BATTERY_REFRESH_MILLIS - nowMillis).coerceIn(0, BATTERY_REFRESH_MILLIS)
}

/** [photoFace] draws the photo face, whose own preview on the watch is a stock picture. */
class HomeViewModel(
    dependencies: CompanionDependencies,
    private val photoFace: PhotoFacePictures? = null,
) : LocalHealthViewModel(dependencies) {
    private val sessions: SessionController = dependencies.sessions
    val sessionState: StateFlow<SessionState> = sessions.state

    val state: StateFlow<HomeState?> =
        sessionState(
            current = { session ->
                HomeState(
                    session.client.state.value,
                    session.services.deviceSettings.state.value,
                    session.services.recovery.state.value,
                    session.services.watchFaces.state.value,
                    dependencies.preferences.stepGoalFor(session).value,
                )
            },
            updates = { session ->
                combine(
                    session.client.state,
                    session.services.deviceSettings.state,
                    session.services.recovery.state,
                    session.services.watchFaces.state,
                    dependencies.preferences.stepGoalFor(session),
                    ::HomeState,
                )
            },
        )

    /**
     * Null until today's records are read. They are read again after each sync and when the page
     * comes back, so a new day shows up without any special handling.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val steps: StateFlow<HomeSteps?> =
        combine(
                state.filterNotNull(),
                recordAccess.mapLatest { access ->
                    access?.let {
                        HealthRecordsLoader(it.client, it.source, ZoneId.systemDefault())
                            .loadSteps(LocalDate.now())
                    }
                },
            ) { home, stored ->
                homeSteps(
                    stored,
                    home.watch.steps,
                    home.linkReady,
                    LocalDate.now(),
                    ZoneId.systemDefault(),
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var loader: Pair<WatchSession, FacePreviewLoader>? = null
    private val refreshedSessions = mutableSetOf<Long>()
    private val photoAsked = mutableSetOf<Long>()

    /**
     * The current face's preview with the session it came from, read from that session's own state,
     * so switching between the demo and a real watch can't pair one's face with the other.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val currentPreview: StateFlow<Pair<WatchSession, ImageBitmap>?> =
        session
            .flatMapLatest { current ->
                if (current == null) flowOf(null)
                else
                    combine(current.client.state, current.services.watchFaces.state) { watch, faces
                            ->
                            CurrentFace(
                                currentFaceOf(watch, faces),
                                faces.photo,
                                watch.battery?.value,
                            )
                        }
                        .distinctUntilChanged()
                        .mapLatest { (face, photo, battery) ->
                            face ?: return@mapLatest null
                            val image =
                                photoFacePicture(current, face, photo, battery)
                                    ?: loaderFor(current).load(face)
                            image?.let { current to it }
                        }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val facePreview: StateFlow<ImageBitmap?> =
        currentPreview
            .map { it?.second }
            .stateIn(viewModelScope, SharingStarted.Eagerly, currentPreview.value?.second)

    init {
        // A real watch's face becomes the face shortcut's icon; the demo's never does.
        viewModelScope.launch {
            currentPreview
                .filterNotNull()
                .filter { (session, _) -> !session.isDemo }
                .map { it.second }
                .distinctUntilChanged()
                .collect(dependencies.faceShortcut::offer)
        }
        // The face catalog is needed for the preview; ask once per link session.
        viewModelScope.launch {
            state
                .filterNotNull()
                .map { it.linkReady to it.watch.connection.sessionId }
                .distinctUntilChanged()
                .collect { (ready, id) ->
                    val current = session.value ?: return@collect
                    if (!ready || !refreshedSessions.add(id)) return@collect
                    try {
                        withTimeoutOrNull(5_000) { current.services.watchFaces.refresh() }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The home page falls back to the illustration; the faces page can retry.
                    }
                }
        }
    }

    /** Link session and time of the last battery request. */
    private var batteryAsked: Pair<Long, Long>? = null

    /** Refreshes the battery for as long as this runs, which should be while the page shows. */
    suspend fun keepBatteryCurrent() {
        state
            .map { home ->
                home?.takeIf { it.linkReady }?.watch?.let { it.connection.sessionId to it.battery }
            }
            .distinctUntilChanged()
            .collectLatest { link ->
                val (id, battery) = link ?: return@collectLatest
                while (true) {
                    val askedAt = batteryAsked?.takeIf { it.first == id }?.second
                    delay(batteryRefreshDelay(battery, askedAt, System.currentTimeMillis()))
                    try {
                        session.value?.client?.refreshBattery()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The page keeps its last value, and a failed request waits like any other.
                    }
                    // Set only now: a request cancelled on its way out must be sent again.
                    batteryAsked = id to System.currentTimeMillis()
                }
            }
    }

    fun retry() = sessions.retry()

    fun enterDemo() = sessions.enterDemo()

    fun reconnect(peer: PeerAddress) =
        command(RECONNECT) { session ->
            session.client.connect(peer)
            null
        }

    companion object {
        const val RECONNECT = "home_reconnect"
    }

    /**
     * The photo face as it looks, when it is the current face: the watch's preview of it is the
     * stock picture, not the user's photo. Asks the watch for the photo once per link if needed.
     */
    private suspend fun photoFacePicture(
        session: WatchSession,
        face: WatchFaceFavorite,
        photo: WatchFacePhoto?,
        battery: Int?,
    ): ImageBitmap? {
        val pictures = photoFace ?: return null
        val component = face.componentName ?: return null
        return try {
            if (!withContext(Dispatchers.IO) { pictures.isPhotoFace(component) }) return null
            val link = session.client.state.value.connection.sessionId
            val known =
                photo?.takeIf { it.image != null }
                    ?: if (photoAsked.add(link)) session.services.watchFaces.queryPhoto() else null
            known
                ?.let { withContext(Dispatchers.Default) { pictures.draw(it, battery) } }
                ?.asImageBitmap()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun loaderFor(session: WatchSession): FacePreviewLoader {
        loader
            ?.takeIf { it.first == session }
            ?.let {
                return it.second
            }
        return FacePreviewLoader(session.services.watchFaces).also { loader = session to it }
    }
}
