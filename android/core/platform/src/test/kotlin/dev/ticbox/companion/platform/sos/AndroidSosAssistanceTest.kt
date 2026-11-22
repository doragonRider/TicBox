package dev.ticbox.companion.platform.sos

import android.Manifest
import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.IBinder
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import dev.ticbox.companion.api.*
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSubscriptionManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidSosAssistanceTest {
    private val now = 1_000_000L
    private val clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
    @Test fun optInAndPermissionsAreRequiredBeforeStartingService() = runTest {
        val store = Store(); val launcher = Launcher(); val state = MutableStateFlow(SosAssistanceState())
        val client = AndroidSosAssistance(store, launcher, backgroundScope, state, clock = clock)
        try {
            assertFalse(client.handle(request()).accepted); assertEquals(0, launcher.starts)
            client.configure(SosPhoneAssistanceConfig(true, 7))
            launcher.capability = launcher.capability.copy(smsPermissionGranted = false, reason = "sms_permission_required")
            assertEquals("sms_permission_required", client.handle(request()).reason)
            assertEquals(0, launcher.starts)
            launcher.capability = launcher.capability.copy(smsPermissionGranted = true, reason = null)
            assertTrue(client.handle(request()).accepted)
            assertEquals(1, launcher.starts); assertEquals("event", store.alert!!.request.eventId)
            assertEquals(SosAssistancePhase.STARTING, state.value.phase)
            assertEquals(0, state.value.smsPartsSubmitted)
        } finally { client.close() }
    }
    @Test fun duplicateTriggerDoesNotResetDeadlineAndClosingFacadePreservesServiceOwnedAlert() = runTest {
        val store = Store(); val launcher = Launcher(); val state = MutableStateFlow(SosAssistanceState())
        val client = AndroidSosAssistance(store, launcher, backgroundScope, state, clock = clock)
        client.configure(SosPhoneAssistanceConfig(true, 7)); assertTrue(client.handle(request()).accepted)
        val first = store.alert
        assertEquals("sos_assistance_already_active", client.handle(request().copy(eventId = "another")).reason)
        assertEquals(first, store.alert); assertEquals(1, launcher.starts)
        client.close(); assertNotNull(store.alert); assertEquals(0, launcher.stops)
    }
    @Test fun mixedSmsRecipientsAreFilteredWithoutDroppingValidContacts() = runTest {
        val store = Store(); val launcher = Launcher(); val state = MutableStateFlow(SosAssistanceState())
        val client = AndroidSosAssistance(store, launcher, backgroundScope, state, clock = clock)
        try {
            client.configure(SosPhoneAssistanceConfig(true, 7))
            val contacts = request().contacts + SosEmergencyContact("extension", "021-12345678#123")
            assertTrue(client.handle(request().copy(contacts = contacts)).accepted)
            assertEquals(request().contacts, store.alert!!.request.contacts)
            assertEquals(1, state.value.skippedRecipientCount)
            assertEquals(1, store.alert!!.skippedRecipientCount)
            assertEquals("sos_contacts_partially_skipped", state.value.issue?.reason)
            assertEquals(2, state.value.recipientCount)
        } finally { client.close() }
    }
    @Test fun failedAcceptedStartSurvivesStoreAndFacadeRecreationAsFailure() = runTest {
        val app = RuntimeEnvironment.getApplication()
        val store = AtomicFileSosAssistanceStore(app)
        store.saveConfig(SosPhoneAssistanceConfig(true, 7))
        store.saveActive(SosActiveAlert(request(), 7, now, now + SosAssistanceSession.MAX_DURATION_MILLIS,
            skippedRecipientCount = 1, failureReason = "sos_foreground_start_failed"))
        val state = MutableStateFlow(SosAssistanceState())
        val launcher = Launcher()
        val client = AndroidSosAssistance(AtomicFileSosAssistanceStore(app), launcher, backgroundScope, state, clock = clock)
        try {
            client.refreshCapabilities()
            assertEquals(SosAssistancePhase.FAILED, state.value.phase)
            assertEquals("sos_foreground_start_failed", state.value.issue?.reason)
            assertEquals("event", state.value.eventId)
            assertEquals(2, state.value.recipientCount)
            assertEquals(now + SosAssistanceSession.MAX_DURATION_MILLIS, state.value.expiresAtMillis)
            assertEquals(1, state.value.skippedRecipientCount)
            assertEquals("sos_foreground_start_failed", store.active()?.failureReason)
            assertEquals(1, store.active()?.skippedRecipientCount)
            assertEquals(0, launcher.starts)
        } finally { client.stop(); client.close() }
    }
    @Test fun changingSimStopsSessionAndExpiredOrInvalidRecipientsCannotTriggerSms() = runTest {
        val store = Store(); val launcher = Launcher(); val state = MutableStateFlow(SosAssistanceState())
        val client = AndroidSosAssistance(store, launcher, backgroundScope, state, clock = clock)
        try {
            client.configure(SosPhoneAssistanceConfig(true, 7)); assertTrue(client.handle(request()).accepted)
            client.configure(SosPhoneAssistanceConfig(true, 8)); assertNull(store.alert)
            assertTrue(launcher.stops > 0)
            assertEquals("sos_event_expired", client.handle(request().copy(receivedAtMillis = now - 60_001)).reason)
            assertEquals("sos_contacts_invalid", client.handle(request().copy(contacts = listOf(SosEmergencyContact("a", "*#123#")))).reason)
            client.configure(SosPhoneAssistanceConfig(false)); assertFalse(client.handle(request()).accepted)
            assertEquals(1, launcher.starts)
        } finally { client.close() }
    }
    @Test fun launchFailureClearsReservationAndRecipientsAreDeduplicated() = runTest {
        val store = Store(); val launcher = Launcher(); val state = MutableStateFlow(SosAssistanceState())
        val client = AndroidSosAssistance(store, launcher, backgroundScope, state, clock = clock)
        try {
            client.configure(SosPhoneAssistanceConfig(true, 7)); launcher.failure = SecurityException()
            assertEquals("sos_foreground_permission_required", client.handle(request()).reason); assertNull(store.alert)
            launcher.failure = null
            val contact = SosEmergencyContact("duplicate", "+1 (234)")
            assertTrue(client.handle(request().copy(contacts = listOf(contact, contact.copy(number = "+1234")))).accepted)
            assertEquals(1, store.alert!!.request.contacts.size)
            client.stop(); assertNull(store.alert)
        } finally { client.close() }
    }
    @Test fun atomicStoresSurviveRecreationAndKeepWatchesSeparate() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val first = AtomicFileSosSettingsStore(context)
        val peer = PeerAddress("01:23:45:67:89:AB"); val other = PeerAddress("11:23:45:67:89:AB")
        val contacts = listOf(SosEmergencyContact("private name", "1234"))
        val settings = SosWatchSettings(now, true, "112", 1, true, contacts, "[{\"name\":\"private name\",\"num\":\"1234\"}]")
        first.save(peer, settings)
        val restored = AtomicFileSosSettingsStore(context)
        assertEquals(settings, restored.load(peer)); assertNull(restored.load(other))
        assertTrue(File(context.noBackupFilesDir, "companion/sos-watch").listFiles()!!.none { it.name.contains("1234") || it.name.contains("private") })
        val store = AtomicFileSosAssistanceStore(context)
        assertEquals(SosPhoneAssistanceConfig(), store.config()); store.saveConfig(SosPhoneAssistanceConfig(true, 7))
        val alert = SosActiveAlert(request(), 7, now, now + SosAssistanceSession.MAX_DURATION_MILLIS,
            now + 10, 4, SosLocationSample(51.5, -0.1, now), submissionPending = true)
        store.saveActive(alert)
        val again = AtomicFileSosAssistanceStore(context)
        assertEquals(SosPhoneAssistanceConfig(true, 7), again.config()); assertEquals(alert, again.active())
        assertFalse(alert.toString().contains("1234")); assertFalse(alert.toString().contains("51.5"))
        again.saveActive(null); assertNull(store.active())
    }
    @Test fun explicitAuthorizationAndNoUnknownSimFallback() {
        val app = RuntimeEnvironment.getApplication(); val actions = AndroidSosActions(app)
        assertEquals(AndroidSosAuthorizationLaunch.Permissions(listOf(Manifest.permission.SEND_SMS)), actions.prepareAuthorization(SosAuthorizationAction.SMS))
        assertIs<AndroidSosAuthorizationLaunch.Unavailable>(actions.prepareAuthorization(SosAuthorizationAction.BACKGROUND_LOCATION))
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertIs<AndroidSosAuthorizationLaunch.Activity>(actions.prepareAuthorization(SosAuthorizationAction.BACKGROUND_LOCATION))
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        assertNull(selectedSmsSubscription(app, SosPhoneAssistanceConfig()))
        assertFalse(sosCapabilities(app, SosPhoneAssistanceConfig()).usableSmsSubscription)
    }
    @Test fun androidSmsAdapterUsesSelectedSimAndNeverStartsCall() {
        val app = RuntimeEnvironment.getApplication(); shadowOf(app).grantPermissions(Manifest.permission.SEND_SMS)
        val service = Robolectric.buildService(StubService::class.java).create().get()
        val device = AndroidSosDevice(service)
        assertTrue(device.send(7, "+1 (234)", SosSmsKind.INITIAL, null) >= 1)
        val sms = app.getSystemService(SmsManager::class.java).createForSubscriptionId(7)
        val shadow = shadowOf(sms)
        val text = shadow.lastSentTextMessageParams?.text ?: shadow.lastSentMultipartTextMessageParams.parts.joinToString("")
        val destination = shadow.lastSentTextMessageParams?.destinationAddress ?: shadow.lastSentMultipartTextMessageParams.destinationAddress
        assertEquals("+1234", destination); assertTrue(text.contains("TicWatch")); assertFalse(text.contains("maps.google"))
        assertNull(shadowOf(app).nextStartedActivity)
        shadowOf(app).denyPermissions(Manifest.permission.SEND_SMS)
        assertFailsWith<SosPlatformFailure> { device.send(7, "+1234", SosSmsKind.INITIAL, null) }
    }
    @Test fun foregroundNotificationOwnsOnlyItsAlertAndOffersExplicitStop() {
        val app = RuntimeEnvironment.getApplication()
        val notifications = app.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("existing", "Existing", NotificationManager.IMPORTANCE_LOW))
        notifications.notify(71, Notification.Builder(app, "existing").setContentTitle("Existing notification").build())
        val service = Robolectric.buildService(StubService::class.java).create().get()
        val device = AndroidSosDevice(service)
        device.foreground(true, location = true)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            service.foregroundServiceType)
        val notification = assertNotNull(shadowOf(service).lastForegroundNotification)
        val stop = shadowOf(notification.actions.single().actionIntent)
        assertTrue(stop.isService); assertTrue(stop.isImmutable)
        assertEquals(SosAssistanceService.ACTION_STOP, stop.savedIntent.action)
        assertEquals(SosAssistanceService::class.java.name, stop.savedIntent.component?.className)
        assertFalse(notification.extras.toString().contains("1234"))
        device.foreground(false)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertTrue(notifications.activeNotifications.any { it.id == 71 })
    }
    @Test fun androidLocationSubscriptionClosesAfterPermissionRevocation() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val service = Robolectric.buildService(StubService::class.java).create().get()
        val manager = service.getSystemService(LocationManager::class.java)
        val locations = shadowOf(manager)
        locations.setLocationEnabled(true)
        locations.setProviderEnabled(LocationManager.FUSED_PROVIDER, true)
        val subscription = AndroidSosDevice(service).locations { error("A test must not send a real location") }
        assertTrue(locations.getLocationRequests(LocationManager.FUSED_PROVIDER).isNotEmpty())
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        subscription.close()
        assertTrue(locations.getLocationRequests(LocationManager.FUSED_PROVIDER).isEmpty())
    }
    class StubService : Service() { override fun onBind(intent: Intent?): IBinder? = null }
    private fun request() = SosEmergencyRequest("event", PeerAddress("01:23:45:67:89:AB"), 1, now,
        listOf(SosEmergencyContact("first", "1234"), SosEmergencyContact("second", "5678")), false)
    private class Store : SosAssistanceStore {
        var config = SosPhoneAssistanceConfig(); var alert: SosActiveAlert? = null
        override fun config() = config
        override fun saveConfig(config: SosPhoneAssistanceConfig) { this.config = config }
        override fun active() = alert
        override fun saveActive(alert: SosActiveAlert?) { this.alert = alert }
    }
    private class Launcher : SosAssistanceLauncher {
        var capability = SosPhoneCapabilities(true, true, true, true, true, true)
        var starts = 0; var stops = 0; var failure: Exception? = null
        override fun capabilities(config: SosPhoneAssistanceConfig) = capability
        override fun subscription(config: SosPhoneAssistanceConfig) = config.subscriptionId ?: 7
        override fun launch() { failure?.let { throw it }; starts++ }
        override fun stop() { stops++ }
    }
}
