package dev.ticbox.companion

import android.app.Application
import android.content.res.Configuration
import dev.ticbox.companion.api.DataMigration
import dev.ticbox.companion.platform.health.LocalHealthProfile
import dev.ticbox.companion.ui.CompanionDependencies
import dev.ticbox.companion.ui.R
import dev.ticbox.companion.ui.common.UserMessages
import dev.ticbox.companion.ui.common.uiText
import dev.ticbox.companion.ui.settings.migrationMessage
import dev.ticbox.companion.ui.toolbox.ShellHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide dependencies. They outlive Activities, so binding and commands survive rotation. */
class CompanionApplication : Application(), CompanionDependencies {
    override val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override val messages = UserMessages()
    override val phoneApps by lazy { PackageManagerPhoneApps(this) }
    override val preferences by lazy { AndroidAppPreferences(this) }
    override val sessions by lazy {
        ServiceSessionController(this, applicationScope) { LocalHealthProfile.id(this) }
    }
    override val shell by lazy { ShellHost(terminalDisplays, applicationScope) }
    private val migration by lazy { HostDataMigration(this, sessions) }
    override val dataMigration: DataMigration
        get() = migration

    override fun onCreate() {
        super.onCreate()
        preferences // Applies the saved theme before the first Activity draws.
        sessions.bind()
        migration.takeOutcome()?.let { outcome ->
            messages.show(
                if (outcome == HostDataMigration.IMPORTED) uiText(R.string.migration_imported)
                else migrationMessage(outcome)
            )
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        preferences.refreshLanguage()
        sessions.onLocaleChanged()
    }
}
