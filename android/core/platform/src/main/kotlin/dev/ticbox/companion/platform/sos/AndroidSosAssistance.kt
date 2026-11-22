package dev.ticbox.companion.platform.sos

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.telephony.SubscriptionManager
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.sos.SosEmergencyHandler
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock

/** Host Activities request these permissions; an incoming SOS never opens a permission dialog. */
enum class SosAuthorizationAction { SMS, SIM_SELECTION, LOCATION, BACKGROUND_LOCATION, NOTIFICATIONS, APP_SETTINGS, LOCATION_SETTINGS }
sealed interface AndroidSosAuthorizationLaunch {
    data class Permissions(val permissions: List<String>) : AndroidSosAuthorizationLaunch
    data class Activity(val intent: Intent) : AndroidSosAuthorizationLaunch
    data class Unavailable(val reason: String) : AndroidSosAuthorizationLaunch
}
interface AndroidSosAuthorizationActions {
    fun prepareAuthorization(action: SosAuthorizationAction): AndroidSosAuthorizationLaunch
    /** Subscription IDs only; returns empty without READ_PHONE_STATE. No phone numbers are read. */
    fun availableSmsSubscriptions(): List<Int>
}

internal interface SosAssistanceLauncher {
    fun capabilities(config: SosPhoneAssistanceConfig): SosPhoneCapabilities
    fun subscription(config: SosPhoneAssistanceConfig): Int?
    fun launch()
    fun stop()
}
internal object SosAssistanceRegistry {
    val state = MutableStateFlow(SosAssistanceState())
}

/** Runtime facade; the independent foreground service owns an accepted emergency until stop/expiry. */
class AndroidSosAssistance internal constructor(
    private val store: SosAssistanceStore,
    private val launcher: SosAssistanceLauncher,
    parentScope: CoroutineScope,
    private val sharedState: MutableStateFlow<SosAssistanceState>,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
    private val clock: Clock = Clock.systemUTC(),
    private val authorization: AndroidSosAuthorizationActions? = null,
) : SosEmergencyHandler, AndroidSosAuthorizationActions {
    constructor(context: Context, scope: CoroutineScope, diagnostics: DiagnosticSink = DiagnosticSink.NONE) : this(
        AtomicFileSosAssistanceStore(context), AndroidSosServiceLauncher(context.applicationContext), scope,
        SosAssistanceRegistry.state, diagnostics, authorization = AndroidSosActions(context.applicationContext),
    )
    private val closed = AtomicBoolean()
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val worker = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val initialized = CompletableDeferred<Unit>()
    override val state: StateFlow<SosAssistanceState> = sharedState.asStateFlow()

    init {
        worker.launch(Dispatchers.IO) {
            try {
                SosAssistanceOwnership.commands.withLock {
                    val config = store.config()
                    val capabilities = launcher.capabilities(config)
                    val failed = store.active()?.takeIf { it.failureReason != null }
                    sharedState.value = sharedState.value.copy(config = config, capabilities = capabilities,
                        phase = if (failed != null) SosAssistancePhase.FAILED
                            else if (sharedState.value.phase in setOf(SosAssistancePhase.ACTIVE, SosAssistancePhase.STARTING)) sharedState.value.phase
                            else phase(config, capabilities),
                        issue = failed?.failureReason?.let { SosIssue("phone_assistance", it) } ?: sharedState.value.issue,
                        eventId = failed?.request?.eventId ?: sharedState.value.eventId,
                        recipientCount = failed?.request?.contacts?.size ?: sharedState.value.recipientCount,
                        startedAtMillis = failed?.startedAtMillis ?: sharedState.value.startedAtMillis,
                        expiresAtMillis = failed?.expiresAtMillis ?: sharedState.value.expiresAtMillis,
                        smsPartsSubmitted = failed?.partsSubmitted ?: sharedState.value.smsPartsSubmitted,
                        skippedRecipientCount = failed?.skippedRecipientCount ?: sharedState.value.skippedRecipientCount)
                }
            } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                sharedState.value = SosAssistanceState(issue = SosIssue("restore", "sos_storage_unavailable"))
                log("restore_failed", "sos_storage_unavailable")
            } finally { initialized.complete(Unit) }
        }
    }

    override suspend fun configure(config: SosPhoneAssistanceConfig) = withContext(Dispatchers.IO) {
        awaitReady(); config.subscriptionId?.let { require(SubscriptionManager.isValidSubscriptionId(it)) }
        SosAssistanceOwnership.commands.withLock {
            val previous = store.config()
            store.saveConfig(config)
            // Changing SIM/authorization never changes the recipient/SIM of an already-running SOS.
            if (!config.enabled || config.subscriptionId != previous.subscriptionId) {
                val wasActive = store.active() != null
                store.saveActive(null)
                if (wasActive) launcher.stop()
            }
            val capabilities = launcher.capabilities(config)
            val active = store.active()
            sharedState.value = sharedState.value.copy(config = config, capabilities = capabilities,
                phase = if (active != null) sharedState.value.phase else phase(config, capabilities), issue = null)
        }
    }
    override suspend fun refreshCapabilities() = withContext(Dispatchers.IO) {
        awaitReady()
        SosAssistanceOwnership.commands.withLock {
            val config = store.config(); val capabilities = launcher.capabilities(config)
            sharedState.value = sharedState.value.copy(config = config, capabilities = capabilities,
                phase = if (store.active() != null) sharedState.value.phase else phase(config, capabilities))
        }
    }
    override suspend fun handle(request: SosEmergencyRequest): SosEmergencyHandling = withContext(Dispatchers.IO) {
        awaitReady()
        SosAssistanceOwnership.commands.withLock {
            val config = store.config()
            if (!config.enabled) return@withLock rejected("phone_assistance_disabled")
            if (request.contacts.isEmpty() || request.contacts.size > SosLimits.MAX_CONTACTS)
                return@withLock rejected("sos_contacts_invalid")
            val eligible = request.contacts.filter { smsNumber(it.number) }
            if (eligible.isEmpty()) return@withLock rejected("sos_contacts_invalid")
            val skipped = request.contacts.size - eligible.size
            val capabilities = launcher.capabilities(config)
            sharedState.value = sharedState.value.copy(capabilities = capabilities)
            val subscription = launcher.subscription(config)
            if (!capabilities.smsSupported || !capabilities.smsPermissionGranted || subscription == null)
                return@withLock rejected(capabilities.reason ?: "sms_unavailable")
            val now = clock.millis()
            if (request.receivedAtMillis > now || now - request.receivedAtMillis > 60_000)
                return@withLock rejected("sos_event_expired")
            val existing = store.active()
            if (existing != null && existing.failureReason == null && existing.expiresAtMillis > now) {
                // Repeated watch triggers cannot reset the 24-hour limit or resend the initial SMS.
                if (existing.request.peer != request.peer) return@withLock rejected("another_watch_assistance_active")
                return@withLock SosEmergencyHandling(true, "sos_assistance_already_active")
            }
            val uniqueContacts = eligible.distinctBy { normalizedSmsNumber(it.number) }
            val alert = SosActiveAlert(request.copy(contacts = uniqueContacts), subscription, now,
                now + SosAssistanceSession.MAX_DURATION_MILLIS, skippedRecipientCount = skipped)
            store.saveActive(alert)
            sharedState.value = sharedState.value.copy(config = config, phase = SosAssistancePhase.STARTING,
                eventId = request.eventId, recipientCount = uniqueContacts.size, startedAtMillis = now,
                expiresAtMillis = alert.expiresAtMillis, smsPartsSubmitted = 0,
                skippedRecipientCount = skipped,
                issue = if (skipped > 0) SosIssue("phone_assistance", "sos_contacts_partially_skipped") else null)
            try { launcher.launch(); SosEmergencyHandling(true) }
            catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                store.saveActive(null)
                val reason = if (problem is SecurityException) "sos_foreground_permission_required" else "sos_foreground_start_failed"
                rejected(reason)
            }
        }
    }
    override suspend fun stop() = withContext(Dispatchers.IO) {
        awaitReady()
        SosAssistanceOwnership.commands.withLock {
            store.saveActive(null); launcher.stop()
            sharedState.value = sharedState.value.copy(phase = if (store.config().enabled) SosAssistancePhase.STOPPED else SosAssistancePhase.DISABLED)
        }
    }
    /** Releasing a UI/runtime handle does not silently stop a service-owned emergency. */
    override fun close() { if (closed.compareAndSet(false, true)) lifetime.cancel() }
    override fun prepareAuthorization(action: SosAuthorizationAction): AndroidSosAuthorizationLaunch =
        authorization?.prepareAuthorization(action) ?: AndroidSosAuthorizationLaunch.Unavailable("sos_authorization_unavailable")
    override fun availableSmsSubscriptions(): List<Int> = authorization?.availableSmsSubscriptions().orEmpty()
    private suspend fun awaitReady() {
        check(!closed.get()) { "sos_platform_closed" }; initialized.await()
        check(!closed.get()) { "sos_platform_closed" }
    }
    private fun rejected(reason: String): SosEmergencyHandling {
        sharedState.value = sharedState.value.copy(issue = SosIssue("phone_assistance", reason),
            phase = if (store.active() != null && sharedState.value.phase in setOf(SosAssistancePhase.ACTIVE, SosAssistancePhase.STARTING)) sharedState.value.phase
                else if (sharedState.value.config.enabled) SosAssistancePhase.UNAVAILABLE else SosAssistancePhase.DISABLED)
        log("assistance_rejected", reason)
        return SosEmergencyHandling(false, reason)
    }
    private fun log(event: String, reason: String) {
        runCatching { diagnostics.record(ProtocolDiagnostic("sos", event, details = mapOf("reason" to reason))) }
    }
    private fun phase(config: SosPhoneAssistanceConfig, capabilities: SosPhoneCapabilities) = when {
        !config.enabled -> SosAssistancePhase.DISABLED
        !capabilities.smsSupported || !capabilities.smsPermissionGranted || !capabilities.usableSmsSubscription -> SosAssistancePhase.UNAVAILABLE
        else -> SosAssistancePhase.READY
    }
}

internal class AndroidSosActions(private val context: Context) : AndroidSosAuthorizationActions {
    override fun prepareAuthorization(action: SosAuthorizationAction): AndroidSosAuthorizationLaunch = when (action) {
        SosAuthorizationAction.SMS -> AndroidSosAuthorizationLaunch.Permissions(listOf(Manifest.permission.SEND_SMS))
        SosAuthorizationAction.SIM_SELECTION -> AndroidSosAuthorizationLaunch.Permissions(listOf(Manifest.permission.READ_PHONE_STATE))
        SosAuthorizationAction.LOCATION -> AndroidSosAuthorizationLaunch.Permissions(listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
        SosAuthorizationAction.BACKGROUND_LOCATION -> if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            AndroidSosAuthorizationLaunch.Activity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            else AndroidSosAuthorizationLaunch.Unavailable("foreground_location_permission_required_first")
        SosAuthorizationAction.NOTIFICATIONS -> AndroidSosAuthorizationLaunch.Permissions(listOf(Manifest.permission.POST_NOTIFICATIONS))
        SosAuthorizationAction.APP_SETTINGS -> AndroidSosAuthorizationLaunch.Activity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        SosAuthorizationAction.LOCATION_SETTINGS -> AndroidSosAuthorizationLaunch.Activity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
    }
    override fun availableSmsSubscriptions(): List<Int> {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
        return try { context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty().map { it.subscriptionId }.filter(SubscriptionManager::isValidSubscriptionId) }
            catch (_: SecurityException) { emptyList() }
    }
}

internal class AndroidSosServiceLauncher(private val context: Context) : SosAssistanceLauncher {
    override fun capabilities(config: SosPhoneAssistanceConfig) = sosCapabilities(context, config)
    override fun subscription(config: SosPhoneAssistanceConfig) = selectedSmsSubscription(context, config)
    override fun launch() { context.startForegroundService(Intent(context, SosAssistanceService::class.java)) }
    override fun stop() { context.stopService(Intent(context, SosAssistanceService::class.java)) }
}

internal fun sosCapabilities(context: Context, config: SosPhoneAssistanceConfig): SosPhoneCapabilities {
    fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    val supported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)
    val permission = granted(Manifest.permission.SEND_SMS)
    val subscription = selectedSmsSubscription(context, config) != null
    return SosPhoneCapabilities(supported, permission,
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION),
        granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION), granted(Manifest.permission.POST_NOTIFICATIONS), subscription,
        when { !supported -> "sms_not_supported"; !permission -> "sms_permission_required"; !subscription -> "sms_subscription_required"; else -> null })
}
internal fun selectedSmsSubscription(context: Context, config: SosPhoneAssistanceConfig): Int? {
    val selected = config.subscriptionId ?: SubscriptionManager.getDefaultSmsSubscriptionId()
    if (!SubscriptionManager.isValidSubscriptionId(selected)) return null
    // A user-selected SIM must still be active. The default API alone does not validate an
    // explicitly selected subscription; without phone-state permission report unavailable.
    if (config.subscriptionId != null) {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return null
        try { if (context.getSystemService(SubscriptionManager::class.java)?.isActiveSubscriptionId(selected) != true) return null }
        catch (_: SecurityException) { return null }
    }
    return selected
}
internal fun smsNumber(value: String): Boolean = Regex("\\+?[0-9]{1,32}").matches(normalizedSmsNumber(value))
internal fun normalizedSmsNumber(value: String) = value.filterNot { it == ' ' || it == '-' || it == '(' || it == ')' || it == '.' }
