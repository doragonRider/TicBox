package dev.ticbox.companion.engine

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.CompanionSettingsCodec
import java.net.URI
import java.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Reviewed bootstrap DataItems. Values come from the host; no user consent is inferred. */
class CompanionDataInitializer(
    private val data: DataSyncClient,
    private val applicationRoute: ApplicationRoute,
    private val languageTags: () -> String,
    private val is24HourFormat: () -> Boolean,
    private val phoneSdkVersion: Int,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val clock: Clock = Clock.systemUTC(),
    private val settingsWriter: (suspend (DataScope, Long, String, ByteArray) -> Unit)? = null,
) {
    private val writer = Mutex()

    /** Safe on every established link. Changed settings receive monotonically increasing times. */
    suspend fun prepare(sessionId: Long, termsAccepted: Boolean = false, consents: List<SetupConsent> = emptyList()) = writer.withLock {
        val state = data.state.value
        val scope = state.scope ?: throw NotConnectedException()
        if (state.sessionId != sessionId || state.phase != DataSyncPhase.ACTIVE) throw NotConnectedException()
        fun ensureSession() {
            if (data.state.value.sessionId != sessionId || data.state.value.scope != scope || data.state.value.phase != DataSyncPhase.ACTIVE) throw NotConnectedException()
        }
        suspend fun publish(path: String, body: ByteArray, route: ApplicationRoute = applicationRoute) {
            ensureSession()
            data.publish(scope, route, DataItemDraft("wear://${scope.localNodeId}$path", body, lastModifiedMillis = clock.millis()))
        }
        suspend fun existing(path: String, route: ApplicationRoute = applicationRoute): List<DataItemRecord> {
            val records = ArrayList<DataItemRecord>(); var offset = 0
            do {
                val page = data.items(scope, DataItemQuery(route, includeDeleted = false, offset = offset, limit = 500))
                records += page.items.filter { runCatching { URI(it.uri).path == path }.getOrDefault(false) }
                val next = page.nextOffset ?: break
                offset = next
            } while (true)
            return records
        }
        suspend fun setting(path: String, value: ByteArray) {
            settingsWriter?.let { it(scope, sessionId, path, value); return }
            val previous = existing(path)
            var latest = Long.MIN_VALUE
            var same = false
            for (item in previous) {
                val body = item.data ?: continue
                try {
                    val changed = CompanionSettingsCodec.changedAt(body) ?: 0
                    if (changed >= latest) {
                        latest = changed
                        same = CompanionSettingsCodec.value(body)?.contentEquals(value) == true
                    }
                } catch (problem: Exception) {
                    log(scope, sessionId, "existing_setting_unreadable", mapOf("path" to path, "exception" to problem.javaClass.simpleName))
                }
            }
            if (same) return
            check(latest != Long.MAX_VALUE) { "Setting timestamp space exhausted" }
            publish(path, CompanionSettingsCodec.setting(value, maxOf(clock.millis(), latest + 1)))
        }
        val capabilityPath = "/capabilities/${applicationRoute.packageName}/${applicationRoute.packageDigest}/ticwatch_phone_connect"
        if (existing(capabilityPath, GMS_ROUTE).none { it.local && it.data?.contentEquals(byteArrayOf(0x73)) == true }) {
            publish(capabilityPath, byteArrayOf(0x73), GMS_ROUTE)
        }
        setting(CompanionSettingsCodec.LOCALES_PATH, CompanionSettingsCodec.localesValue(languageTags()))
        setting(CompanionSettingsCodec.TIME_FORMAT_PATH, CompanionSettingsCodec.booleanValue(is24HourFormat()))
        setting(CompanionSettingsCodec.PHONE_VERSION_PATH, CompanionSettingsCodec.integerValue(phoneSdkVersion))
        for (consent in consents) if (consent.type == SetupOptinType.LOCATION && consent.choice != SetupOptinChoice.SKIPPED) {
            val accepted = consent.choice == SetupOptinChoice.ACCEPTED
            setting("/protogensettings/location/combined_location/${scope.remoteNodeId}", CompanionSettingsCodec.booleanValue(accepted))
            setting("/protogensettings/location/network_location_opt_in/${scope.remoteNodeId}", CompanionSettingsCodec.booleanValue(accepted))
        }
        // CLOUDSYNC and LOGGING effects are owned by the host's GoogleServices integration,
        // which reports actual service/DataApi results separately from these bootstrap settings.
        if (termsAccepted) publish(CompanionSettingsCodec.TERMS_AUDIT_PATH, CompanionSettingsCodec.acceptedTermsAudit(clock.millis()))
        log(scope, sessionId, "initial_data_prepared", mapOf("termsRecorded" to termsAccepted.toString(), "settings" to "locale,time_format,phone_version", "decision" to "persist and submit; remote synchronization is observed separately"))
    }

    private fun log(scope: DataScope, sessionId: Long, event: String, details: Map<String, String>) {
        runCatching { diagnostics.record(ProtocolDiagnostic("initial_data", event, peer = scope.peer, sessionId = sessionId, details = details)) }
    }

    companion object {
        val GMS_ROUTE = ApplicationRoute("com.google.android.gms", "38918a453d07199354f8b19af05ec6562ced5788")
        const val DATA_SYNC_PROGRESS = "DATA_SYNC_PROGRESS"
    }
}
