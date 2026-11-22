package dev.ticbox.companion.platform.health

import android.content.Context
import java.util.UUID

/** Preserve the UI's existing local profile identity and share it with service-owned ingestion. */
object LocalHealthProfile {
    @Synchronized fun id(context: Context): String {
        val preferences = context.getSharedPreferences("frontend-profile", Context.MODE_PRIVATE)
        return preferences.getString("id", null) ?: UUID.randomUUID().toString().also {
            check(preferences.edit().putString("id", it).commit()) { "Local profile could not be saved" }
        }
    }
}
