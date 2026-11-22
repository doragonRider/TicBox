package dev.ticbox.companion.api

import java.io.InputStream
import java.io.OutputStream

/** What a migration file can carry. Each is chosen on export and again on import. */
enum class MigrationSection {
    /** Health records from the watch, the cloud health cache, and battery and step history. */
    HEALTH,
    /** App and watch-sync preferences, including the app language. */
    SETTINGS,
    /** SOS contacts and the phone's SMS assistance choice. */
    SOS,
    /** The key the watch authorized for wireless debugging, and the last device connected. */
    ADB,
    /** This phone's identity on the watch and the sync state that belongs to it. Replaces, never merges. */
    PAIRING,
}

/** What a migration file says about itself; readable before anything is imported. */
data class MigrationManifest(
    val format: Int,
    /** The phone database version the file was written from. */
    val dataSchema: Int,
    val appVersion: String,
    val createdAtMillis: Long,
    val sections: Set<MigrationSection>,
    val encrypted: Boolean,
)

/**
 * A stable reason key for the UI; never carries file contents or a password. [restartRequired] is
 * true once the backend has been stopped for an import, so the app has to start again either way.
 */
class MigrationException(val code: String, cause: Throwable? = null, val restartRequired: Boolean = false) :
    Exception(code, cause)

/**
 * Export and import of this app's local data. A blank password writes a plain ZIP; any other
 * password encrypts the whole file. Import merges: health rows keep the newer copy, preferences and
 * contacts take the file's values, and [MigrationSection.PAIRING] replaces this app's identity.
 */
interface DataMigration {
    suspend fun export(output: OutputStream, sections: Set<MigrationSection>, password: CharArray?)

    /** Reads only the manifest; throws `migration_password_required` or `migration_wrong_password`. */
    suspend fun inspect(open: () -> InputStream, password: CharArray?): MigrationManifest

    /** Checks the whole file, stops the backend, then merges. The app must restart afterwards. */
    suspend fun import(open: () -> InputStream, password: CharArray?, sections: Set<MigrationSection>)

    companion object {
        val UNAVAILABLE: DataMigration = object : DataMigration {
            override suspend fun export(output: OutputStream, sections: Set<MigrationSection>, password: CharArray?) =
                throw MigrationException("migration_unavailable")
            override suspend fun inspect(open: () -> InputStream, password: CharArray?): MigrationManifest =
                throw MigrationException("migration_unavailable")
            override suspend fun import(open: () -> InputStream, password: CharArray?, sections: Set<MigrationSection>) =
                throw MigrationException("migration_unavailable")
        }
    }
}
