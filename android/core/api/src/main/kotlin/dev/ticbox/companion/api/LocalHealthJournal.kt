package dev.ticbox.companion.api

/** Two fixed OEM database files. This API never accepts a path, table name or SQL. */
enum class LocalHealthJournalSource(val wireName: String) { HEALTH("health"), FITNESS("fitness") }

/** Source metadata only. An installed journal persists independently of the Zygisk module. */
data class LocalHealthJournalStatus(
    val source: LocalHealthJournalSource,
    val enabledForSync: Boolean,
    /** Null when root access or source validation prevented an observation. */
    val installed: Boolean?,
    val formatVersion: Int? = null,
    val retainedFromSequence: Long? = null,
    val headSequence: Long? = null,
    val reason: String? = null,
)

class LocalHealthJournalException(val code: String) : java.io.IOException(code)
