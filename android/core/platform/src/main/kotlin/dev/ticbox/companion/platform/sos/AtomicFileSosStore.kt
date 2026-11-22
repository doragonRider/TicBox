package dev.ticbox.companion.platform.sos

import android.content.Context
import android.util.AtomicFile
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.sos.SosCodec
import dev.ticbox.companion.engine.sos.SosSettingsStore
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Contacts stay in app-private no-backup storage; neither filenames nor errors contain a number. */
class AtomicFileSosSettingsStore(context: Context) : SosSettingsStore {
    private val directory = File(context.applicationContext.noBackupFilesDir, "companion/sos-watch")
    override suspend fun load(peer: PeerAddress): SosWatchSettings? = withContext(Dispatchers.IO) {
        synchronized(LOCK) {
            val file = AtomicFile(File(directory, name(peer)))
            try { SosCodec.settings(file.openRead().use { it.readNBytes(MAX_BYTES + 1) }.also { require(it.size <= MAX_BYTES) }) }
            catch (_: FileNotFoundException) { null }
        }
    }
    override suspend fun save(peer: PeerAddress, settings: SosWatchSettings) = withContext(Dispatchers.IO) {
        synchronized(LOCK) { writeAtomic(AtomicFile(File(directory, name(peer))), SosCodec.encodeSettings(settings)) }
    }
    private fun name(peer: PeerAddress): String = MessageDigest.getInstance("SHA-256").digest(peer.value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) } + ".json"
    private companion object { val LOCK = Any(); const val MAX_BYTES = 64 * 1024 }
}

internal interface SosAssistanceStore {
    fun config(): SosPhoneAssistanceConfig
    fun saveConfig(config: SosPhoneAssistanceConfig)
    fun active(): SosActiveAlert?
    fun saveActive(alert: SosActiveAlert?)
}
internal data class SosLocationSample(val latitude: Double, val longitude: Double, val atMillis: Long) {
    override fun toString() = "SosLocationSample(<private>)"
}
internal data class SosActiveAlert(
    val request: SosEmergencyRequest,
    val subscriptionId: Int,
    val startedAtMillis: Long,
    val expiresAtMillis: Long,
    /** Commit before a modem submission: after a process death an ambiguous attempt is never replayed. */
    val lastAttemptMillis: Long = 0,
    val partsSubmitted: Int = 0,
    val locationAtLastAttempt: SosLocationSample? = null,
    /** True until the API-call round is journalled; process death leaves its outcome unknown. */
    val submissionPending: Boolean = false,
    val skippedRecipientCount: Int = 0,
    /** A failed service launch is retained for reporting, never automatically resumed. */
    val failureReason: String? = null,
) {
    override fun toString() = "SosActiveAlert(eventId=${request.eventId}, recipientCount=${request.contacts.size}, partsSubmitted=$partsSubmitted)"
}

internal class AtomicFileSosAssistanceStore(context: Context) : SosAssistanceStore {
    private val root = File(context.applicationContext.noBackupFilesDir, "companion")
    private val configuration = AtomicFile(File(root, "sos-assistance-v1.json"))
    private val active = AtomicFile(File(root, "sos-active-v1.json"))
    override fun config(): SosPhoneAssistanceConfig = synchronized(LOCK) {
        read(configuration)?.let { value ->
            require(value["version"]?.jsonPrimitive?.int == 1)
            SosPhoneAssistanceConfig(value.getValue("enabled").jsonPrimitive.boolean,
                value["subscriptionId"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.int)
        } ?: SosPhoneAssistanceConfig()
    }
    override fun saveConfig(config: SosPhoneAssistanceConfig) = synchronized(LOCK) {
        config.subscriptionId?.let { require(it >= 0) }
        write(configuration, buildJsonObject { put("version", 1); put("enabled", config.enabled); config.subscriptionId?.let { put("subscriptionId", it) } })
    }
    override fun active(): SosActiveAlert? = synchronized(LOCK) {
        read(active)?.let { value ->
            require(value["version"]?.jsonPrimitive?.int == 1)
            val contacts = value.getValue("contacts").jsonArray.map { c ->
                SosEmergencyContact(c.jsonObject.getValue("name").jsonPrimitive.content, c.jsonObject.getValue("number").jsonPrimitive.content)
            }
            require(contacts.size in 1..4)
            val request = SosEmergencyRequest(value.getValue("eventId").jsonPrimitive.content,
                PeerAddress(value.getValue("peer").jsonPrimitive.content), value.getValue("sessionId").jsonPrimitive.long,
                value.getValue("receivedAtMillis").jsonPrimitive.long, contacts, value.getValue("shareLocation").jsonPrimitive.boolean)
            val location = value["lastLocation"]?.jsonObject?.let {
                SosLocationSample(it.getValue("latitude").jsonPrimitive.double, it.getValue("longitude").jsonPrimitive.double, it.getValue("atMillis").jsonPrimitive.long)
            }
            SosActiveAlert(request, value.getValue("subscriptionId").jsonPrimitive.int,
                value.getValue("startedAtMillis").jsonPrimitive.long, value.getValue("expiresAtMillis").jsonPrimitive.long,
                value.getValue("lastAttemptMillis").jsonPrimitive.long, value.getValue("partsSubmitted").jsonPrimitive.int, location,
                value["submissionPending"]?.jsonPrimitive?.boolean ?: false,
                (value["skippedRecipientCount"]?.jsonPrimitive?.int ?: 0).also { require(it in 0..SosLimits.MAX_CONTACTS) },
                value["failureReason"]?.jsonPrimitive?.content?.also { require(it.matches(Regex("sos_[a-z_]{1,80}"))) })
        }
    }
    override fun saveActive(alert: SosActiveAlert?) = synchronized(LOCK) {
        if (alert == null) { active.delete(); return@synchronized }
        write(active, buildJsonObject {
            put("version", 1); put("eventId", alert.request.eventId); put("peer", alert.request.peer.value)
            put("sessionId", alert.request.sessionId); put("receivedAtMillis", alert.request.receivedAtMillis)
            put("shareLocation", alert.request.shareLocation); put("subscriptionId", alert.subscriptionId)
            put("startedAtMillis", alert.startedAtMillis); put("expiresAtMillis", alert.expiresAtMillis)
            put("lastAttemptMillis", alert.lastAttemptMillis); put("partsSubmitted", alert.partsSubmitted)
            put("submissionPending", alert.submissionPending)
            put("skippedRecipientCount", alert.skippedRecipientCount)
            alert.failureReason?.let { put("failureReason", it) }
            putJsonArray("contacts") { alert.request.contacts.forEach { c -> add(buildJsonObject { put("name", c.name); put("number", c.number) }) } }
            alert.locationAtLastAttempt?.let { sample -> putJsonObject("lastLocation") {
                put("latitude", sample.latitude); put("longitude", sample.longitude); put("atMillis", sample.atMillis)
            } }
        })
    }
    private fun read(file: AtomicFile): JsonObject? = try {
        val bytes = file.openRead().use { it.readNBytes(MAX_BYTES + 1) }; require(bytes.size <= MAX_BYTES)
        Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    } catch (_: FileNotFoundException) { null }
    private fun write(file: AtomicFile, json: JsonObject) = writeAtomic(file, json.toString().toByteArray(Charsets.UTF_8))
    private companion object { val LOCK = Any(); const val MAX_BYTES = 64 * 1024 }
}

private fun writeAtomic(file: AtomicFile, bytes: ByteArray) {
    require(bytes.size <= 64 * 1024)
    val parent = file.baseFile.parentFile!!
    if (!parent.isDirectory && !parent.mkdirs()) throw IOException("sos_storage_unavailable")
    val output = file.startWrite()
    try { output.write(bytes); file.finishWrite(output) }
    catch (problem: Throwable) { file.failWrite(output); throw problem }
}
