package dev.ticbox.companion.platform.settings

import android.content.Context
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.settings.DevicePreferencesStore
import dev.ticbox.companion.engine.finding.FindDeviceSettingsStore
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.Properties
import kotlinx.coroutines.CancellationException

class AtomicFileDevicePreferences(context: Context, private val diagnostics: DiagnosticSink = DiagnosticSink.NONE) : DevicePreferencesStore {
    private val file = AtomicPreferencesFile(File(context.noBackupFilesDir, "device-display.settings"), 64 * 1024)
    override suspend fun load(): CompanionDevicePreferences = try {
        val values = read(file)
        CompanionDevicePreferences(values.getProperty("locales"), values.getProperty("24hour")?.toBooleanStrictOrNull())
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (problem: Exception) {
        report(diagnostics, problem); CompanionDevicePreferences()
    }
    override suspend fun save(preferences: CompanionDevicePreferences) = write(file, Properties().apply {
        preferences.languageTags?.let { setProperty("locales", it) }
        preferences.is24HourFormat?.let { setProperty("24hour", it.toString()) }
    })
}
class AtomicFileFindDeviceSettings(context: Context, private val diagnostics: DiagnosticSink = DiagnosticSink.NONE) : FindDeviceSettingsStore {
    private val file = AtomicPreferencesFile(File(context.noBackupFilesDir, "find-devices.settings"), 64 * 1024)
    override suspend fun load(): FindDeviceSettings = try { FindDeviceSettings(read(file).getProperty("enabled")?.toBooleanStrictOrNull() ?: true) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (problem: Exception) { report(diagnostics, problem); FindDeviceSettings() }
    override suspend fun save(settings: FindDeviceSettings) = write(file, Properties().apply { setProperty("enabled", settings.enabled.toString()) })
}
private suspend fun read(file: AtomicPreferencesFile): Properties = Properties().apply {
    file.read()?.let { load(it.inputStream()) }
}
private suspend fun write(file: AtomicPreferencesFile, properties: Properties) {
    val bytes = ByteArrayOutputStream().use { output -> properties.store(output, null); output.toByteArray() }
    file.write(bytes)
}
private fun report(diagnostics: DiagnosticSink, problem: Throwable) {
    runCatching { diagnostics.record(ProtocolDiagnostic("device_preferences", "load_failed", details = mapOf("exception" to problem.javaClass.simpleName))) }
}
