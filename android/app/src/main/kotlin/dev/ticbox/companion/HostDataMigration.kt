package dev.ticbox.companion

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dev.ticbox.companion.api.DataMigration
import dev.ticbox.companion.api.MigrationException
import dev.ticbox.companion.api.MigrationManifest
import dev.ticbox.companion.api.MigrationSection
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The service's data migration, reached through its binder. An import that got as far as stopping
 * the service restarts the app, and what it did is shown once the app is back.
 */
class HostDataMigration(
    private val application: Application,
    private val sessions: ServiceSessionController,
) : DataMigration {
    private val notes = application.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private suspend fun service(): DataMigration =
        withTimeoutOrNull(BIND_TIMEOUT_MILLIS) { sessions.binder.filterNotNull().first() }
            ?.migration ?: throw MigrationException("migration_unavailable")

    override suspend fun export(
        output: OutputStream,
        sections: Set<MigrationSection>,
        password: CharArray?,
    ) = service().export(output, sections, password)

    override suspend fun inspect(open: () -> InputStream, password: CharArray?): MigrationManifest =
        service().inspect(open, password)

    override suspend fun import(
        open: () -> InputStream,
        password: CharArray?,
        sections: Set<MigrationSection>,
    ) {
        val migration = service()
        val outcome =
            try {
                migration.import(open, password, sections)
                IMPORTED
            } catch (problem: MigrationException) {
                if (!problem.restartRequired) throw problem
                problem.code
            }
        withContext(NonCancellable) {
            check(notes.edit().putString(OUTCOME, outcome).commit()) { "Outcome not saved" }
            restart()
        }
    }

    /** What the import before this start did: [IMPORTED] or an error code. Read only once. */
    fun takeOutcome(): String? {
        val outcome = notes.getString(OUTCOME, null) ?: return null
        notes.edit().remove(OUTCOME).apply()
        return outcome
    }

    /** Files and the database were written underneath the running app, so it starts over. */
    private fun restart() {
        val launch =
            application.packageManager.getLaunchIntentForPackage(application.packageName)?.component
                ?: ComponentName(application, CompanionActivity::class.java)
        application.startActivity(Intent.makeRestartActivityTask(launch))
        Runtime.getRuntime().exit(0)
    }

    companion object {
        const val IMPORTED = "imported"
        private const val FILE = "frontend-migration"
        private const val OUTCOME = "outcome"
        private const val BIND_TIMEOUT_MILLIS = 10_000L
    }
}
