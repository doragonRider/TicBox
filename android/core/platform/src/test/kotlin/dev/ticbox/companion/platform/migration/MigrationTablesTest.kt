package dev.ticbox.companion.platform.migration

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import dev.ticbox.companion.platform.data.DataSyncSqlSchema
import kotlin.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Fails when the phone database changes and the migration file doesn't: every table and column has to
 * be carried (or left out on purpose), and the importer has to know the new version.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MigrationTablesTest {
    @Test fun theFileCarriesEveryTableAndColumnOfTheCurrentDatabase() {
        assertEquals(DataSyncSqlSchema.VERSION, MigrationTables.SCHEMA,
            "Update the data migration for the new database version; see docs/dev/data-migration.md")
        val db = SQLiteDatabase.create(null)
        try {
            DataSyncSqlSchema.statements.forEach(db::execSQL)
            // android_metadata is Android's own; sqlite_ tables are SQLite's.
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name <> 'android_metadata'", null)
                .use { buildSet { while (it.moveToNext()) add(it.getString(0)) } }
            assertEquals(tables, MigrationTables.health.keys + MigrationTables.pairing.keys + MigrationTables.notCarried.keys)
            (tables - MigrationTables.notCarried.keys).forEach { table ->
                val columns = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                    buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
                }
                assertEquals(columns - MigrationTables.omitted[table].orEmpty(), MigrationTables.columns(table)!!.toSet(), table)
            }
        } finally { db.close() }
    }
}
