package dev.ticbox.companion.platform.privacy

import android.content.Context
import android.util.AtomicFile
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.privacy.CloudPrivacyCodec
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Never backed up: an install on another device cannot silently inherit cloud authorization. */
class AtomicFileCloudPrivacyStore(context: Context, private val diagnostics: DiagnosticSink = DiagnosticSink.NONE) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "cloud-privacy.settings"))

    fun load(): CloudPrivacySettings = try {
        file.openRead().use { input -> CloudPrivacyCodec.decode(input.readNBytes(CloudPrivacyCodec.MAX_BYTES + 1)) }
    } catch (_: FileNotFoundException) { CloudPrivacySettings() }
    catch (problem: Exception) {
        runCatching { diagnostics.record(ProtocolDiagnostic("privacy", "settings_restore_disabled", details =
            mapOf("exception" to problem.javaClass.simpleName, "decision" to "disable all cloud capabilities"))) }
        CloudPrivacySettings()
    }

    suspend fun save(settings: CloudPrivacySettings) = withContext(Dispatchers.IO) {
        val stream = file.startWrite()
        try { stream.write(CloudPrivacyCodec.encode(settings)); file.finishWrite(stream) }
        catch (problem: Throwable) { file.failWrite(stream); throw problem }
    }
}
