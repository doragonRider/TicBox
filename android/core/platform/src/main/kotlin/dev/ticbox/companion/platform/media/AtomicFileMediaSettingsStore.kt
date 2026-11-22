package dev.ticbox.companion.platform.media

import android.content.Context
import dev.ticbox.companion.platform.settings.AtomicPreferencesFile
import dev.ticbox.companion.api.MediaControlsSettings
import dev.ticbox.companion.engine.media.MediaSettingsStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties

/** Only user preferences are persisted here; media titles and native session tokens are absent. */
class AtomicFileMediaSettingsStore(context: Context) : MediaSettingsStore {
    private val file = AtomicPreferencesFile(File(context.noBackupFilesDir, "media-controls.settings"), MediaPreferencesCodec.MAX_BYTES)
    override suspend fun load(): MediaControlsSettings = file.read()?.let(MediaPreferencesCodec::decode) ?: MediaControlsSettings()
    override suspend fun save(settings: MediaControlsSettings) = file.write(MediaPreferencesCodec.encode(settings))
}

internal object MediaPreferencesCodec {
    const val MAX_BYTES = 65_536
    fun encode(settings: MediaControlsSettings): ByteArray = ByteArrayOutputStream().use { output ->
        Properties().apply {
            setProperty("enabled", settings.enabled.toString()); setProperty("autoLaunch", settings.autoLaunch.toString())
            settings.preferredPackage?.let { setProperty("preferredPackage", it) }
        }.store(output, null)
        output.toByteArray().also { require(it.size <= MAX_BYTES) { "Media settings size budget exceeded" } }
    }
    fun decode(bytes: ByteArray): MediaControlsSettings {
        require(bytes.size <= MAX_BYTES) { "Media settings size budget exceeded" }
        val values = Properties().apply { load(ByteArrayInputStream(bytes)) }
        return MediaControlsSettings(values.getProperty("enabled")?.toBooleanStrictOrNull() ?: true,
            values.getProperty("preferredPackage")?.takeIf(String::isNotBlank), values.getProperty("autoLaunch")?.toBooleanStrictOrNull() ?: false)
    }
}
