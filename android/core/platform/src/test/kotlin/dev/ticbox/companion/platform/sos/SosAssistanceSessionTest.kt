package dev.ticbox.companion.platform.sos

import dev.ticbox.companion.api.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SosAssistanceSessionTest {
    @Test fun firstSmsDoesNotWaitForLocationThenMovementAndFourHourRulesApply() = runTest {
        val f = fixture(this, shareLocation = true)
        f.session.start(); runCurrent()
        assertEquals(listOf(SosSmsKind.INITIAL, SosSmsKind.INITIAL), f.device.sent.map { it.kind })
        assertTrue(f.device.sent.all { it.location == null })
        f.device.fix(SosLocationSample(51.5, -0.1, f.clock.millis())); runCurrent()
        assertEquals(2, f.device.sent.count { it.kind == SosSmsKind.LOCATION })
        advanceTimeBy(10 * 60_000)
        f.device.fix(SosLocationSample(51.501, -0.1, f.clock.millis())); runCurrent()
        assertEquals(4, f.device.sent.size)
        advanceTimeBy(SosAssistanceSession.CHECK_INTERVAL_MILLIS - 10 * 60_000); runCurrent()
        assertEquals(6, f.device.sent.size)
        advanceTimeBy(SosAssistanceSession.REMINDER_INTERVAL_MILLIS); runCurrent()
        assertEquals(SosSmsKind.INITIAL, f.device.sent.last().kind)
        assertNull(f.device.sent.last().location)
        f.session.stop()
        assertEquals(1, f.device.locationCloses)
        assertEquals(SosAssistancePhase.STOPPED, f.state.value.phase)
        val count = f.device.sent.size
        advanceTimeBy(SosAssistanceSession.MAX_DURATION_MILLIS); runCurrent()
        assertEquals(count, f.device.sent.size)
    }
    @Test fun missingBackgroundLocationStillSendsInitialSmsAndReportsDegradation() = runTest {
        val f = fixture(this, true)
        f.device.capability = f.device.capability.copy(backgroundLocationPermissionGranted = false)
        f.session.start()
        assertEquals(2, f.device.sent.size)
        assertEquals("background_location_permission_required", f.state.value.issue?.reason)
        assertEquals(SosAssistancePhase.ACTIVE, f.state.value.phase)
        assertEquals(0, f.device.locationStarts)
        f.session.stop()
    }
    @Test fun subscriptionTemporarilyUnavailableRecoversWithoutChangingEventOrDeadline() = runTest {
        val f = fixture(this)
        val saved = f.store.alert!!
        f.device.selected = null
        f.session.start(); runCurrent()
        assertTrue(f.device.sent.isEmpty())
        assertEquals(saved, f.store.alert)
        assertEquals(SosAssistancePhase.ACTIVE, f.state.value.phase)
        advanceTimeBy(SosAssistanceSession.CHECK_INTERVAL_MILLIS)
        f.device.selected = 7
        runCurrent()
        assertEquals(2, f.device.sent.size)
        assertEquals(saved.request.eventId, f.store.alert?.request?.eventId)
        assertEquals(saved.expiresAtMillis, f.store.alert?.expiresAtMillis)
        f.device.selected = null
        f.device.fix(SosLocationSample(51.5, -0.1, f.clock.millis())); runCurrent()
        assertNotNull(f.store.alert)
        f.device.selected = 7
        f.session.checkNow()
        f.session.stop()
    }
    @Test fun temporarySubscriptionLossNeverExtendsExpiryOrStartsOnAnotherSim() = runTest {
        val f = fixture(this)
        f.device.selected = null
        f.session.start(); runCurrent()
        advanceTimeBy(SosAssistanceSession.MAX_DURATION_MILLIS); runCurrent()
        assertNull(f.store.alert)
        assertTrue(f.device.sent.isEmpty())
        assertEquals(SosAssistancePhase.STOPPED, f.state.value.phase)
    }
    @Test fun failedSmsRoundRemainsRecoverableButIsNotImmediatelyReplayed() = runTest {
        val f = fixture(this, false)
        f.device.beforeSend = { throw IllegalStateException("modem unavailable") }
        f.session.start(); runCurrent()
        val deadline = f.store.alert!!.expiresAtMillis
        assertEquals(SosAssistancePhase.ACTIVE, f.state.value.phase)
        assertEquals("sms_submission_failed", f.state.value.issue?.reason)
        f.device.beforeSend = null
        f.session.checkNow()
        assertTrue(f.device.sent.isEmpty())
        advanceTimeBy(SosAssistanceSession.REMINDER_INTERVAL_MILLIS); runCurrent()
        assertEquals(2, f.device.sent.size)
        assertEquals(deadline, f.state.value.expiresAtMillis)
        assertEquals(SosAssistancePhase.STOPPED, f.state.value.phase)
    }
    @Test fun locationRecoversAfterPermissionGrantAndProviderRecoveryWithoutDuplicateSubscriptions() = runTest {
        val f = fixture(this)
        f.device.capability = f.device.capability.copy(backgroundLocationPermissionGranted = false)
        f.session.start(); runCurrent()
        assertEquals(0, f.device.locationStarts)
        f.device.capability = f.device.capability.copy(backgroundLocationPermissionGranted = true)
        f.device.locationFailure = SosPlatformFailure("location_disabled")
        f.session.checkNow()
        assertEquals("location_disabled", f.state.value.issue?.reason)
        f.device.locationFailure = null
        f.session.checkNow(); f.session.checkNow()
        assertEquals(1, f.device.locationStarts)
        f.device.capability = f.device.capability.copy(foregroundLocationPermissionGranted = false)
        f.session.checkNow()
        assertEquals(1, f.device.locationCloses)
        f.device.capability = f.device.capability.copy(foregroundLocationPermissionGranted = true)
        f.session.checkNow()
        assertEquals(2, f.device.locationStarts)
        f.device.fix(SosLocationSample(51.5, -0.1, f.clock.millis())); runCurrent()
        assertEquals(2, f.device.sent.count { it.kind == SosSmsKind.LOCATION })
        f.session.stop()
    }
    @Test fun failedForegroundStartIsPersistedWithoutOverwritingAnotherEventOrReplayingSms() = runTest {
        val f = fixture(this)
        recordSosStartFailure(f.store, f.state, "old-event", "sos_foreground_start_failed")
        assertNull(f.store.alert?.failureReason)
        assertNotEquals(SosAssistancePhase.FAILED, f.state.value.phase)
        recordSosStartFailure(f.store, f.state, "event", "sos_foreground_start_failed")
        assertEquals("sos_foreground_start_failed", f.store.alert?.failureReason)
        f.session.start()
        assertEquals(SosAssistancePhase.FAILED, f.state.value.phase)
        assertTrue(f.device.sent.isEmpty())
        assertNotNull(f.store.alert)
        f.store.alert = null
        f.state.value = f.state.value.copy(phase = SosAssistancePhase.STOPPED)
        recordSosStartFailure(f.store, f.state, "event", "sos_foreground_start_failed")
        assertNull(f.store.alert)
        assertEquals(SosAssistancePhase.STOPPED, f.state.value.phase)
        f.session.release()
    }
    @Test fun revokedLocationPermissionClosesUpdatesAndNeverReusesBufferedPosition() = runTest {
        for (background in listOf(true, false)) {
            val f = fixture(this, true)
            f.session.start(); runCurrent()
            f.device.fix(SosLocationSample(51.5, -0.1, f.clock.millis())); runCurrent()
            val lateCallback = f.device.listener!!
            advanceTimeBy(10 * 60_000)
            f.device.fix(SosLocationSample(51.501, -0.1, f.clock.millis())); runCurrent()
            f.device.capability = if (background) f.device.capability.copy(backgroundLocationPermissionGranted = false)
                else f.device.capability.copy(foregroundLocationPermissionGranted = false)
            if (background) f.session.checkNow() else {
                lateCallback(SosLocationSample(51.502, -0.1, f.clock.millis())); runCurrent()
            }
            assertEquals(1, f.device.locationCloses)
            assertEquals(SosAssistancePhase.ACTIVE, f.state.value.phase)
            assertEquals(if (background) "background_location_permission_required" else "foreground_location_permission_required", f.state.value.issue?.reason)
            lateCallback(SosLocationSample(51.503, -0.1, f.clock.millis())); runCurrent()
            assertEquals(4, f.device.sent.size)
            advanceTimeBy(SosAssistanceSession.REMINDER_INTERVAL_MILLIS); runCurrent()
            assertEquals(SosSmsKind.INITIAL, f.device.sent.last().kind)
            assertNull(f.device.sent.last().location)
            f.session.stop()
        }
    }
    @Test fun withoutLocationSharingStockBehaviorIsOneInitialSmsThenStop() = runTest {
        val f = fixture(this, false)
        f.session.start(); runCurrent()
        assertEquals(2, f.device.sent.size)
        assertEquals(SosAssistancePhase.STOPPED, f.state.value.phase)
        assertNull(f.store.alert)
        assertEquals(0, f.device.locationStarts)
        advanceTimeBy(SosAssistanceSession.MAX_DURATION_MILLIS); runCurrent()
        assertEquals(2, f.device.sent.size)
    }
    @Test fun recreationDoesNotReplayInitialSmsOrExtendDeadline() = runTest {
        val f = fixture(this)
        f.session.start(); runCurrent()
        val deadline = f.store.alert!!.expiresAtMillis
        f.session.release()
        advanceTimeBy(5 * 60_000)
        val restored = SosAssistanceSession(backgroundScope, f.store, f.device, f.state, f.clock)
        restored.start(); runCurrent()
        assertEquals(2, f.device.sent.size)
        assertEquals(deadline, f.state.value.expiresAtMillis)
        restored.stop()
    }
    @Test fun expirySendsOneEndNoticeAndStopsOwnedLocation() = runTest {
        val f = fixture(this, true)
        f.session.start(); runCurrent()
        advanceTimeBy(SosAssistanceSession.MAX_DURATION_MILLIS); runCurrent()
        assertEquals(2, f.device.sent.count { it.kind == SosSmsKind.FINISHED })
        assertEquals(SosAssistancePhase.STOPPED, f.state.value.phase)
        assertNull(f.store.alert)
        assertEquals(1, f.device.locationCloses)
        val count = f.device.sent.size
        advanceTimeBy(SosAssistanceSession.REMINDER_INTERVAL_MILLIS); runCurrent()
        assertEquals(count, f.device.sent.size)
    }
    @Test fun permissionRevocationAndSimChangeStopWithoutRetargeting() = runTest {
        for (revoke in listOf(true, false)) {
            val f = fixture(this)
            f.session.start()
            if (revoke) f.device.capability = f.device.capability.copy(smsPermissionGranted = false, reason = "sms_permission_required")
            else f.device.selected = 8
            f.session.checkNow()
            assertEquals(SosAssistancePhase.UNAVAILABLE, f.state.value.phase)
            assertEquals(2, f.device.sent.size)
            assertNull(f.store.alert)
            f.session.release()
        }
    }
    @Test fun journalReservationPrecedesModemAndAmbiguousAttemptIsNeverReplayed() = runTest {
        val f = fixture(this)
        f.device.beforeSend = {
            assertTrue(f.store.alert!!.lastAttemptMillis > 0)
            assertEquals(0, f.store.alert!!.partsSubmitted)
            assertTrue(f.store.alert!!.submissionPending)
        }
        f.session.start()
        assertEquals(2, f.state.value.smsPartsSubmitted)
        f.session.release()
        f.store.alert = f.store.alert!!.copy(partsSubmitted = 0, submissionPending = true)
        val restored = SosAssistanceSession(backgroundScope, f.store, f.device, f.state, f.clock)
        restored.start()
        assertEquals(2, f.device.sent.size)
        assertEquals("sms_previous_submission_unconfirmed", f.state.value.issue?.reason)
        restored.stop()
    }
    @Test fun failingStoragePreventsSmsAndOneFailedRecipientDoesNotSkipOthers() = runTest {
        val f = fixture(this)
        f.store.writeFailure = true
        assertFailsWith<IllegalStateException> { f.session.start() }
        assertTrue(f.device.sent.isEmpty())
        f.session.release()
        val partial = fixture(this)
        partial.device.failureDestination = "1234"
        partial.session.start()
        assertEquals(1, partial.device.sent.size)
        assertEquals(1, partial.state.value.smsPartsSubmitted)
        assertEquals("sms_submission_partial", partial.state.value.issue?.reason)
        partial.session.stop()
    }
    @Test fun futureJournalFailsClosedInsteadOfExtendingTwentyFourHours() = runTest {
        val f = fixture(this)
        f.store.alert = f.store.alert!!.copy(startedAtMillis = f.clock.millis() + 1_000)
        f.session.start()
        assertTrue(f.device.sent.isEmpty())
        assertEquals("sos_journal_time_invalid", f.state.value.issue?.reason)
        assertNull(f.store.alert)
        f.session.release()
    }
    @Test fun aReleasedServiceCannotExecuteQueuedLocationCallbacksOrChecks() = runTest {
        val f = fixture(this)
        f.session.start(); runCurrent()
        val callback = f.device.listener!!
        val saved = f.store.alert
        f.session.release()
        callback(SosLocationSample(51.5, -0.1, f.clock.millis())); runCurrent()
        advanceTimeBy(SosAssistanceSession.REMINDER_INTERVAL_MILLIS); f.session.checkNow()
        assertEquals(2, f.device.sent.size)
        assertEquals(saved, f.store.alert)
        assertEquals(1, f.device.locationCloses)
    }
    @Test fun delayedRecreationOfLocationOffAlertDoesNotSendAnEndNotice() = runTest {
        val f = fixture(this, false)
        f.store.alert = f.store.alert!!.copy(lastAttemptMillis = f.clock.millis(), submissionPending = true)
        advanceTimeBy(SosAssistanceSession.MAX_DURATION_MILLIS + 1)
        f.session.start()
        assertTrue(f.device.sent.isEmpty())
        assertNull(f.store.alert)
        assertEquals(SosAssistancePhase.STOPPED, f.state.value.phase)
    }
    @Test fun foregroundTimeoutStopsAndReleasesLocationWithExplicitIssue() = runTest {
        val f = fixture(this)
        f.session.start(); runCurrent()
        f.session.unavailable("sos_foreground_timeout")
        assertNull(f.store.alert)
        assertEquals(1, f.device.locationCloses)
        assertEquals(SosAssistancePhase.UNAVAILABLE, f.state.value.phase)
        assertEquals("sos_foreground_timeout", f.state.value.issue?.reason)
        advanceTimeBy(SosAssistanceSession.MAX_DURATION_MILLIS); runCurrent()
        assertEquals(2, f.device.sent.size)
    }
    @Test fun oldTickerCannotOverwriteANewerDurableEvent() = runTest {
        val f = fixture(this)
        f.session.start()
        f.store.alert = f.store.alert!!.copy(request = request(f.clock.millis(), false).copy(eventId = "next"), lastAttemptMillis = 0, partsSubmitted = 0)
        f.session.checkNow()
        assertEquals("next", f.store.alert?.request?.eventId)
        assertEquals(2, f.device.sent.size)
        f.session.start()
        assertEquals(4, f.device.sent.size)
        assertEquals("next", f.state.value.eventId)
        f.session.stop()
    }
    private fun fixture(scope: TestScope, shareLocation: Boolean = true): Fixture {
        val clock = object : Clock() {
            override fun instant() = Instant.ofEpochMilli(millis())
            override fun millis() = 1_000_000L + scope.testScheduler.currentTime
            override fun getZone(): ZoneId = ZoneId.of("UTC")
            override fun withZone(zone: ZoneId): Clock = this
        }
        val request = request(clock.millis(), shareLocation)
        val store = Store(SosActiveAlert(request, 7, clock.millis(), clock.millis() + SosAssistanceSession.MAX_DURATION_MILLIS))
        val device = Device(); val state = MutableStateFlow(SosAssistanceState())
        return Fixture(store, device, state, clock, SosAssistanceSession(scope.backgroundScope, store, device, state, clock))
    }
    private fun request(now: Long, share: Boolean) = SosEmergencyRequest("event", PeerAddress("01:23:45:67:89:AB"), 1, now,
        listOf(SosEmergencyContact("first", "1234"), SosEmergencyContact("second", "5678")), share)
    private data class Fixture(val store: Store, val device: Device, val state: MutableStateFlow<SosAssistanceState>, val clock: Clock, val session: SosAssistanceSession)
    private class Store(var alert: SosActiveAlert?) : SosAssistanceStore {
        var settings = SosPhoneAssistanceConfig(true, 7); var writeFailure = false
        override fun config() = settings
        override fun saveConfig(config: SosPhoneAssistanceConfig) { settings = config }
        override fun active() = alert
        override fun saveActive(alert: SosActiveAlert?) { check(!writeFailure); this.alert = alert }
    }
    private data class Sent(val destination: String, val kind: SosSmsKind, val location: SosLocationSample?)
    private class Device : SosAssistanceDevice {
        var capability = SosPhoneCapabilities(true, true, true, true, true, true)
        var selected: Int? = 7; var locationStarts = 0; var locationCloses = 0
        var locationFailure: Exception? = null
        var listener: ((SosLocationSample) -> Unit)? = null; var beforeSend: (() -> Unit)? = null
        var failureDestination: String? = null; val sent = mutableListOf<Sent>()
        override fun capabilities(config: SosPhoneAssistanceConfig) = capability
        override fun subscription(config: SosPhoneAssistanceConfig) = selected
        override fun send(subscriptionId: Int, destination: String, kind: SosSmsKind, location: SosLocationSample?): Int {
            beforeSend?.invoke(); check(destination != failureDestination)
            sent += Sent(destination, kind, location); return 1
        }
        override fun locations(onLocation: (SosLocationSample) -> Unit): AutoCloseable {
            locationFailure?.let { throw it }
            locationStarts++; listener = onLocation
            return AutoCloseable { locationCloses++; listener = null }
        }
        override fun foreground(active: Boolean, location: Boolean) = Unit
        fun fix(sample: SosLocationSample) { listener?.invoke(sample) }
    }
}
