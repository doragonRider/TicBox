package dev.ticbox.companion.ui.bridge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ticbox.companion.api.DeviceSettingsState
import dev.ticbox.companion.api.LocalHealthSyncPhase
import dev.ticbox.companion.api.LocalHealthSyncState
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.common.SessionViewModel
import dev.ticbox.companion.ui.companionViewModel
import dev.ticbox.companion.ui.session.WatchSession
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** The watch app shares the phone app's package name, which routes its messages to it. */
internal const val LOCAL_BRIDGE_PACKAGE = "dev.ticbox.companion"

/** Lets the watch app change Do Not Disturb, which otherwise takes a trip to system settings. */
internal const val ALLOW_DND_COMMAND = "cmd notification allow_dnd $LOCAL_BRIDGE_PACKAGE"

enum class LocalBridgeStatus {
    /** Nothing conclusive yet, such as before the first sync. */
    UNKNOWN,
    NOT_INSTALLED,

    /** Installed, but "Allow phone to read" is off on the watch. */
    NOT_ALLOWED,

    /**
     * It didn't answer, and the watch's app list can't say whether it's installed. The watch keeps
     * a new install switched off and ignores the phone until "Allow phone to read" is on, so a
     * bridge that was never turned on looks like this too.
     */
    UNREACHABLE,
    READY;

    /** Something the Local Bridge page can help with. */
    val needsSetup: Boolean
        get() = this == NOT_INSTALLED || this == NOT_ALLOWED || this == UNREACHABLE

    /** It may be installed but still switched off on the watch. */
    val mayBeSwitchedOff: Boolean
        get() = this == NOT_ALLOWED || this == UNREACHABLE
}

/** A reason reads "table:problem,table:problem". */
internal fun LocalHealthSyncState.problems(): Map<String, String> =
    reason.orEmpty().split(',').associate { it.substringBefore(':') to it.substringAfter(':') }

/** An empty list means it hasn't arrived, and a handshake outranks a list that may be stale. */
internal fun localBridgeMissing(sync: LocalHealthSyncState, device: DeviceSettingsState): Boolean =
    sync.advertisedTables == null &&
        device.apps.isNotEmpty() &&
        !device.appDirectoryLimited &&
        device.apps.none { it.packageName == LOCAL_BRIDGE_PACKAGE }

internal fun localBridgeStatus(
    sync: LocalHealthSyncState,
    device: DeviceSettingsState,
): LocalBridgeStatus {
    if (localBridgeMissing(sync, device)) return LocalBridgeStatus.NOT_INSTALLED
    val problems = sync.problems().values
    return when {
        // Without a link the last sync's problem says nothing about the bridge.
        sync.phase == LocalHealthSyncPhase.DISCONNECTED ->
            if (sync.advertisedTables != null) LocalBridgeStatus.READY
            else LocalBridgeStatus.UNKNOWN
        "bridge_disabled" in problems -> LocalBridgeStatus.NOT_ALLOWED
        problems.any { it in UNREACHABLE_PROBLEMS } -> LocalBridgeStatus.UNREACHABLE
        sync.advertisedTables != null -> LocalBridgeStatus.READY
        else -> LocalBridgeStatus.UNKNOWN
    }
}

private val UNREACHABLE_PROBLEMS = setOf("bridge_connection_failed", "bridge_timeout")

internal fun WatchSession.localBridgeStatus(): LocalBridgeStatus =
    if (isDemo) LocalBridgeStatus.READY
    else localBridgeStatus(services.localHealth.state.value, services.deviceSettings.state.value)

class LocalBridgeStatusViewModel(dependencies: CompanionDependencies) :
    SessionViewModel(dependencies) {
    val status: StateFlow<LocalBridgeStatus?> =
        sessionState(
            current = { it.localBridgeStatus() },
            updates = { session ->
                combine(
                        session.services.localHealth.state,
                        session.services.deviceSettings.state,
                    ) { _, _ ->
                        session.localBridgeStatus()
                    }
                    .distinctUntilChanged()
            },
        )
}

/** The bundled watch app is of the same build, so this is also the version it would install. */
@Composable
internal fun rememberPhoneVersion(): Long {
    val app = LocalContext.current.applicationContext
    return remember(app) { app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode }
}

/** For pages that decide whether to make room for a [LocalBridgeBanner]. */
@Composable
fun rememberLocalBridgeStatus(): LocalBridgeStatus {
    val viewModel = companionViewModel { LocalBridgeStatusViewModel(it) }
    val status by viewModel.status.collectAsStateWithLifecycle()
    return status ?: LocalBridgeStatus.UNKNOWN
}
