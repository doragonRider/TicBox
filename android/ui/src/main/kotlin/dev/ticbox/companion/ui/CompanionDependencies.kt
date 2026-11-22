package dev.ticbox.companion.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.ticbox.companion.api.CallAuthorizationAction
import dev.ticbox.companion.api.DataMigration
import dev.ticbox.companion.ui.common.UserMessages
import dev.ticbox.companion.ui.session.SessionController
import dev.ticbox.companion.ui.toolbox.EmulatorTerminalDisplays
import dev.ticbox.companion.ui.toolbox.ShellHost
import dev.ticbox.companion.ui.toolbox.TerminalDisplayFactory
import kotlinx.coroutines.CoroutineScope

/** Everything the UI needs from the app host. Screens get these through their view models. */
interface CompanionDependencies {
    val sessions: SessionController
    val phoneApps: PhoneAppsSource
    val preferences: AppPreferences
    val messages: UserMessages

    /** Outlives screens; commands run here so leaving a page does not cancel them. */
    val applicationScope: CoroutineScope

    /** The terminal emulator's native library only loads on Android, so tests bring their own. */
    val terminalDisplays: TerminalDisplayFactory
        get() = EmulatorTerminalDisplays

    /** The shell, which outlives its page and any Activity for as long as it runs. */
    val shell: ShellHost

    /** Export and import of the app's data; the app restarts after an import. */
    val dataMigration: DataMigration
        get() = DataMigration.UNAVAILABLE
}

/** A permission or system page that SMS assistance can need. */
enum class SosAccess {
    SMS,
    SIM_SELECTION,
    LOCATION,
    BACKGROUND_LOCATION,
    NOTIFICATIONS,
    APP_SETTINGS,
    LOCATION_SETTINGS,
}

/** Activity-bound system screens. */
interface SystemActions {
    fun openNotificationAccess()

    fun openAppNotificationSettings()

    /** Shows the system prompt or settings page for one call permission. */
    fun requestCallingAccess(action: CallAuthorizationAction)

    /** Shows the system prompt or settings page for one thing SMS assistance needs. */
    fun requestSosAccess(access: SosAccess)

    /** Which SIMs can be chosen for SMS assistance. Empty until phone state may be read. */
    fun sosSmsSubscriptions(): List<Int>

    /** Leaves the app with the shell running and a notification to come back to it from. */
    fun keepShellRunning() = Unit
}

val LocalCompanionDependencies =
    staticCompositionLocalOf<CompanionDependencies> {
        error("CompanionDependencies are provided by CompanionApp")
    }

val LocalSystemActions =
    staticCompositionLocalOf<SystemActions> {
        object : SystemActions {
            override fun openNotificationAccess() = Unit

            override fun openAppNotificationSettings() = Unit

            override fun requestCallingAccess(action: CallAuthorizationAction) = Unit

            override fun requestSosAccess(access: SosAccess) = Unit

            override fun sosSmsSubscriptions() = emptyList<Int>()
        }
    }

/** Creates (or returns) a view model wired to the app's dependencies. */
@Composable
inline fun <reified VM : ViewModel> companionViewModel(
    owner: ViewModelStoreOwner =
        checkNotNull(LocalViewModelStoreOwner.current) { "No ViewModelStoreOwner" },
    crossinline create: CreationExtras.(CompanionDependencies) -> VM,
): VM {
    val dependencies = LocalCompanionDependencies.current
    return viewModel(owner) { create(dependencies) }
}
