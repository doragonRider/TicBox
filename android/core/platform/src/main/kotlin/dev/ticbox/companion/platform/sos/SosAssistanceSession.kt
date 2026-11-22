package dev.ticbox.companion.platform.sos

import dev.ticbox.companion.api.*
import java.time.Clock
import kotlin.math.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class SosSmsKind { INITIAL, LOCATION, FINISHED }
internal object SosAssistanceOwnership { val commands = Mutex() }
internal interface SosAssistanceDevice {
    fun capabilities(config: SosPhoneAssistanceConfig): SosPhoneCapabilities
    fun subscription(config: SosPhoneAssistanceConfig): Int?
    /** Return submission count only. A successful API call is not delivery confirmation. */
    fun send(subscriptionId: Int, destination: String, kind: SosSmsKind, location: SosLocationSample?): Int
    /** The returned subscription must be closed even after location permission is revoked. */
    fun locations(onLocation: (SosLocationSample) -> Unit): AutoCloseable
    fun foreground(active: Boolean, location: Boolean = false)
}

/** Independent service lifetime: losing the watch link does not terminate an accepted SOS. */
internal class SosAssistanceSession(
    private val scope: CoroutineScope,
    private val store: SosAssistanceStore,
    private val device: SosAssistanceDevice,
    private val state: MutableStateFlow<SosAssistanceState>,
    private val clock: Clock = Clock.systemUTC(),
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
) {
    private val lock = SosAssistanceOwnership.commands
    private var active: SosActiveAlert? = null
    private var latestLocation: SosLocationSample? = null
    private var locationSubscription: AutoCloseable? = null
    private var ticker: Job? = null
    @Volatile private var closed = false

    suspend fun start() = lock.withLock {
        if (closed) return@withLock
        val config = store.config()
        if (!config.enabled) { finish(SosAssistancePhase.DISABLED); return@withLock }
        val saved = store.active() ?: run { device.foreground(false); return@withLock }
        if (saved.failureReason != null) {
            active = null; latestLocation = null; releaseTracking()
            state.value = state.value.copy(phase = SosAssistancePhase.FAILED, eventId = saved.request.eventId,
                issue = SosIssue("phone_assistance", saved.failureReason))
            device.foreground(false); return@withLock
        }
        if (active?.request?.eventId == saved.request.eventId) return@withLock
        releaseTracking()
        active = saved
        val now = clock.millis()
        if (saved.startedAtMillis > now || saved.expiresAtMillis - saved.startedAtMillis != MAX_DURATION_MILLIS) {
            fail("sos_journal_time_invalid"); return@withLock
        }
        if (!validTime(saved)) return@withLock
        if (now >= saved.expiresAtMillis) {
            if (saved.request.shareLocation) expire() else finish(SosAssistancePhase.STOPPED)
            return@withLock
        }
        device.foreground(true)
        state.value = state.value.copy(config = config, phase = SosAssistancePhase.ACTIVE, eventId = saved.request.eventId,
            recipientCount = saved.request.contacts.size, startedAtMillis = saved.startedAtMillis,
            expiresAtMillis = saved.expiresAtMillis, smsPartsSubmitted = saved.partsSubmitted, issue = null,
            capabilities = device.capabilities(config), skippedRecipientCount = saved.skippedRecipientCount)
        if (saved.skippedRecipientCount > 0) issue("sos_contacts_partially_skipped")
        if (saved.submissionPending) issue("sms_previous_submission_unconfirmed")
        // Reserve and submit the first message without waiting for a GPS fix.
        if (canSend(config, saved) && saved.lastAttemptMillis == 0L) submit(SosSmsKind.INITIAL, null)
        if (active == null) return@withLock
        // Stock sends one initial SMS and immediately stops when location sharing is off.
        if (stopAfterInitialIfComplete()) return@withLock
        ensureLocationSubscription(config)
        ticker = scope.launch {
            while (isActive) {
                val remaining = active?.let { it.expiresAtMillis - clock.millis() } ?: break
                delay(minOf(CHECK_INTERVAL_MILLIS, remaining.coerceAtLeast(1)))
                checkNow()
            }
        }
    }

    suspend fun checkNow() = lock.withLock {
        if (closed) return@withLock
        val alert = active ?: return@withLock
        if (!stillOwned(alert)) return@withLock
        val config = store.config()
        if (!config.enabled) { finish(SosAssistancePhase.DISABLED); return@withLock }
        if (!validTime(alert)) return@withLock
        if (clock.millis() >= alert.expiresAtMillis) { expire(); return@withLock }
        if (!canSend(config, alert)) return@withLock
        ensureLocationSubscription(config)
        val sample = latestLocation
        val moved = sample != null && fresh(sample) &&
            (alert.locationAtLastAttempt == null || distanceMeters(alert.locationAtLastAttempt, sample) >= MOVEMENT_METERS)
        when {
            alert.lastAttemptMillis == 0L -> submit(SosSmsKind.INITIAL, null)
            moved -> submit(SosSmsKind.LOCATION, sample)
            clock.millis() - alert.lastAttemptMillis >= REMINDER_INTERVAL_MILLIS -> submit(SosSmsKind.INITIAL, sample?.takeIf(::fresh))
        }
        stopAfterInitialIfComplete()
    }

    private suspend fun onLocation(sample: SosLocationSample) = lock.withLock {
        if (closed) return@withLock
        val alert = active ?: return@withLock
        if (!stillOwned(alert)) return@withLock
        val config = store.config()
        if (!config.enabled) { finish(SosAssistancePhase.DISABLED); return@withLock }
        if (!validTime(alert)) return@withLock
        if (clock.millis() >= alert.expiresAtMillis) { expire(); return@withLock }
        if (!canSend(config, alert)) return@withLock
        if (!locationAllowed(config)) return@withLock
        if (!alert.request.shareLocation || !valid(sample) || !fresh(sample)) return@withLock
        if (latestLocation != null && sample.atMillis <= latestLocation!!.atMillis) return@withLock
        latestLocation = sample
        // As in the stock service, send the first acquired fix immediately; later movement is
        // checked on the 15-minute tick, instead of turning frequent GPS callbacks into SMS spam.
        if (alert.locationAtLastAttempt == null) submit(SosSmsKind.LOCATION, sample)
    }

    suspend fun stop(disabled: Boolean = false) = lock.withLock {
        finish(if (disabled) SosAssistancePhase.DISABLED else SosAssistancePhase.STOPPED)
    }
    suspend fun unavailable(reason: String) = lock.withLock { fail(reason, SosAssistancePhase.UNAVAILABLE) }

    /** Service teardown releases location even when the OS/permissions can no longer answer. */
    fun release() {
        closed = true; active = null; latestLocation = null; releaseTracking()
    }

    private fun canSend(config: SosPhoneAssistanceConfig, alert: SosActiveAlert): Boolean {
        val capability = device.capabilities(config)
        state.value = state.value.copy(capabilities = capability)
        if (!capability.smsSupported || !capability.smsPermissionGranted) {
            fail(if (!capability.smsSupported) "sms_not_supported" else "sms_permission_required", SosAssistancePhase.UNAVAILABLE)
            return false
        }
        val selected = device.subscription(config)
        if (selected == null) {
            issue("sms_subscription_temporarily_unavailable")
            return false
        }
        if (selected != alert.subscriptionId) {
            fail("sms_subscription_changed", SosAssistancePhase.UNAVAILABLE)
            return false
        }
        if (state.value.issue?.reason == "sms_subscription_temporarily_unavailable") state.value = state.value.copy(issue = null)
        return true
    }

    /** A failed attempt is reserved and is not immediately replayed. Only a later periodic
     * round may try again, bounded by the original expiry, even without location sharing. */
    private fun stopAfterInitialIfComplete(): Boolean {
        val alert = active ?: return true
        if (!alert.request.shareLocation && alert.partsSubmitted > 0) { finish(SosAssistancePhase.STOPPED); return true }
        return false
    }

    private fun ensureLocationSubscription(config: SosPhoneAssistanceConfig) {
        if (!locationAllowed(config) || locationSubscription != null) return
        try {
            device.foreground(true, location = true)
            locationSubscription = device.locations { sample -> scope.launch { onLocation(sample) } }
            if (state.value.issue?.reason in setOf("location_unavailable", "location_disabled", "location_provider_unavailable",
                    "foreground_location_permission_required", "background_location_permission_required"))
                state.value = state.value.copy(issue = null)
        } catch (problem: Exception) {
            if (problem is CancellationException) throw problem
            issue((problem as? SosPlatformFailure)?.reason ?: "location_unavailable")
            runCatching { device.foreground(true) }
        }
    }
    private fun validTime(alert: SosActiveAlert): Boolean {
        if (clock.millis() >= maxOf(alert.startedAtMillis, alert.lastAttemptMillis)) return true
        // A clock rollback must not extend the lifetime or cause an old position to be reused.
        fail("sos_clock_moved_backwards", SosAssistancePhase.UNAVAILABLE)
        return false
    }
    private fun locationAllowed(config: SosPhoneAssistanceConfig): Boolean {
        if (active?.request?.shareLocation != true) return false
        val capability = device.capabilities(config)
        state.value = state.value.copy(capabilities = capability)
        // The watch event can arrive in the background. A foreground-only grant does not
        // authorize creating a location FGS there. Recheck on every callback/tick, too: a
        // revocation must stop updates and discard even a previously fresh buffered position.
        val reason = when {
            !capability.foregroundLocationPermissionGranted -> "foreground_location_permission_required"
            !capability.backgroundLocationPermissionGranted -> "background_location_permission_required"
            else -> return true
        }
        latestLocation = null
        if (locationSubscription != null) {
            runCatching { locationSubscription?.close() }; locationSubscription = null
            runCatching { device.foreground(true) }
        }
        if (state.value.issue?.reason != reason) issue(reason)
        return false
    }
    private fun submit(kind: SosSmsKind, sample: SosLocationSample?) {
        val alert = active ?: return
        if (closed || !stillOwned(alert)) return
        val reserved = alert.copy(lastAttemptMillis = clock.millis(), locationAtLastAttempt = sample ?: alert.locationAtLastAttempt,
            submissionPending = true)
        // A crash after this durable reservation leaves an unknown submission. Never replay it
        // automatically on service/process reconstruction; the next periodic update is independent.
        store.saveActive(reserved); active = reserved
        var submitted = 0; var failures = 0
        for (contact in alert.request.contacts) {
            scope.ensureActive()
            try { submitted += device.send(alert.subscriptionId, contact.number, kind, sample) }
            catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                failures++
            }
        }
        val completed = reserved.copy(partsSubmitted = reserved.partsSubmitted + submitted, submissionPending = false)
        store.saveActive(completed); active = completed
        state.value = state.value.copy(smsPartsSubmitted = completed.partsSubmitted)
        log("sms_submission", mapOf("kind" to kind.name, "recipients" to alert.request.contacts.size.toString(),
            "partsSubmitted" to submitted.toString(), "failures" to failures.toString()))
        if (failures > 0) {
            issue(if (submitted > 0) "sms_submission_partial" else "sms_submission_failed")
        }
    }
    private fun expire() {
        val alert = active ?: return
        // Clear the durable session first. An ambiguous final modem submission is never retried.
        store.saveActive(null); active = null
        var submitted = 0; var failures = 0
        val config = store.config()
        if (config.enabled && device.capabilities(config).smsPermissionGranted && device.subscription(config) == alert.subscriptionId) {
            alert.request.contacts.forEach { contact ->
                scope.ensureActive()
                try { submitted += device.send(alert.subscriptionId, contact.number, SosSmsKind.FINISHED, null) }
                catch (problem: Exception) {
                    if (problem is CancellationException) throw problem
                    failures++
                }
            }
        }
        state.value = state.value.copy(smsPartsSubmitted = alert.partsSubmitted + submitted)
        if (failures > 0) issue(if (submitted > 0) "sms_submission_partial" else "sms_submission_failed")
        finish(SosAssistancePhase.STOPPED)
    }
    private fun finish(phase: SosAssistancePhase) {
        store.saveActive(null); active = null; latestLocation = null
        releaseTracking()
        state.value = state.value.copy(phase = phase)
        device.foreground(false)
    }
    private fun fail(reason: String, phase: SosAssistancePhase = SosAssistancePhase.FAILED) { issue(reason); finish(phase) }
    private fun issue(reason: String) {
        state.value = state.value.copy(issue = SosIssue("phone_assistance", reason))
        log("assistance_issue", mapOf("reason" to reason))
    }
    private fun log(event: String, details: Map<String, String>) {
        runCatching { diagnostics.record(ProtocolDiagnostic("sos", event, details = details)) }
    }
    private fun fresh(sample: SosLocationSample): Boolean = clock.millis() - sample.atMillis in 0..LOCATION_MAX_AGE_MILLIS
    private fun stillOwned(alert: SosActiveAlert): Boolean {
        if (store.active()?.let { it.request.eventId == alert.request.eventId && it.failureReason == null } == true) return true
        active = null; latestLocation = null; releaseTracking()
        return false
    }
    private fun releaseTracking() {
        ticker?.cancel(); ticker = null
        runCatching { locationSubscription?.close() }; locationSubscription = null
    }
    companion object {
        const val MAX_DURATION_MILLIS = 24 * 60 * 60 * 1000L
        const val CHECK_INTERVAL_MILLIS = 15 * 60 * 1000L
        const val REMINDER_INTERVAL_MILLIS = 4 * 60 * 60 * 1000L
        const val LOCATION_MAX_AGE_MILLIS = 10 * 60 * 1000L
        const val MOVEMENT_METERS = 100.0
        private fun valid(p: SosLocationSample) = p.latitude.isFinite() && p.longitude.isFinite() && p.latitude in -90.0..90.0 && p.longitude in -180.0..180.0
        internal fun distanceMeters(a: SosLocationSample, b: SosLocationSample): Double {
            val lat1 = Math.toRadians(a.latitude); val lat2 = Math.toRadians(b.latitude)
            val dlat = lat2 - lat1; val dlon = Math.toRadians(b.longitude - a.longitude)
            val h = sin(dlat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dlon / 2).pow(2)
            return 6_371_000 * 2 * atan2(sqrt(h.coerceIn(0.0, 1.0)), sqrt((1 - h).coerceIn(0.0, 1.0)))
        }
    }
}

internal class SosPlatformFailure(val reason: String) : IllegalStateException(reason)
