package dev.ticbox.companion.healthbridge

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/**
 * The read-only part of a database that the change-log checks and reads use. The root helper runs them on the file and
 * Local Bridge runs the same code on the module's answers from inside the official app, so both reach the same result.
 */
internal interface JournalSql {
    /** The database's `user_version`. */
    val version: Int
    fun rawQuery(sql: String, selectionArgs: Array<String>?): Cursor
}

internal class FileJournalSql(private val db: SQLiteDatabase) : JournalSql {
    override val version get() = db.version
    override fun rawQuery(sql: String, selectionArgs: Array<String>?): Cursor = db.rawQuery(sql, selectionArgs)
}
