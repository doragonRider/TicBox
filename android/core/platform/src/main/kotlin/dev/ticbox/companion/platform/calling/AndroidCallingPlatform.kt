package dev.ticbox.companion.platform.calling

import android.Manifest
import android.annotation.SuppressLint
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.calling.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface AndroidCallAuthorizationLaunch {
    data class Permissions(val permissions: List<String>) : AndroidCallAuthorizationLaunch
    data class Activity(val intent: Intent) : AndroidCallAuthorizationLaunch
    data object PairWatch : AndroidCallAuthorizationLaunch
    data class Unavailable(val reason: String) : AndroidCallAuthorizationLaunch
}

/** What an Activity host needs to ask for call permissions, without the engine's types. */
fun interface AndroidCallAuthorizationActions {
    fun prepareAuthorization(action: CallAuthorizationAction): AndroidCallAuthorizationLaunch
}

/** Companion Telecom access is checked with the public API, independently of a claimed WATCH role. */
class AndroidCallingPlatform(
    context: Context,
    scope: CoroutineScope,
    private val diagnostics: DiagnosticSink = DiagnosticSink.NONE,
) : CallingPlatform, AndroidCallAuthorizationActions {
    private val context = context.applicationContext
    private val lifetime = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val mutable = MutableStateFlow(CallingPlatformState())
    override val state = mutable.asStateFlow()
    private val closed = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var telephony: TelephonyManager? = null
    private var phoneCallback: TelephonyCallback? = null
    private var telephonyState: Int? = null
    private var access = CallingAccess()

    init {
        lifetime.launch(Dispatchers.Main.immediate) {
            refreshAccess()
            CompanionCallRegistry.state.collect { publish() }
        }
    }

    override suspend fun refreshAccess() = withContext(Dispatchers.Main.immediate) {
        if (closed.get()) return@withContext
        val telecom = context.getSystemService(TelecomManager::class.java)
        val associations = try {
            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP))
                context.getSystemService(CompanionDeviceManager::class.java)?.myAssociations.orEmpty() else emptyList()
        } catch (problem: Exception) { log("association_inspection_failed", problem); emptyList() }
        val managed = try { telecom?.hasManageOngoingCallsPermission() == true }
        catch (problem: Exception) { log("manage_permission_inspection_failed", problem); false }
        access = CallingAccess(
            companionAssociation = associations.isNotEmpty(),
            watchProfileAssociation = associations.any { it.deviceProfile == AssociationRequest.DEVICE_PROFILE_WATCH },
            manageOngoingCalls = managed,
            defaultDialer = telecom?.defaultDialerPackage == context.packageName,
            readPhoneState = granted(Manifest.permission.READ_PHONE_STATE),
            callPhone = granted(Manifest.permission.CALL_PHONE), telecomAvailable = telecom != null,
        )
        updateTelephonyCallback()
        publish()
    }

    override fun prepareAuthorization(action: CallAuthorizationAction): AndroidCallAuthorizationLaunch = when (action) {
        CallAuthorizationAction.READ_PHONE_STATE -> AndroidCallAuthorizationLaunch.Permissions(listOf(Manifest.permission.READ_PHONE_STATE))
        CallAuthorizationAction.CALL_PHONE -> AndroidCallAuthorizationLaunch.Permissions(listOf(Manifest.permission.CALL_PHONE))
        CallAuthorizationAction.APP_SETTINGS -> AndroidCallAuthorizationLaunch.Activity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)))
        CallAuthorizationAction.BLUETOOTH_SETTINGS -> AndroidCallAuthorizationLaunch.Activity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        CallAuthorizationAction.ASSOCIATE_WATCH -> if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP))
            AndroidCallAuthorizationLaunch.PairWatch else AndroidCallAuthorizationLaunch.Unavailable("companion_device_setup_unavailable")
    }

    @SuppressLint("MissingPermission") // Each Telecom dial and telephony registration checks its actual runtime permission.
    override suspend fun execute(command: CallCommand, watch: PeerAddress?): CallCommandResult = withContext(Dispatchers.Main.immediate) {
        if (closed.get()) return@withContext unavailable("calling_platform_closed")
        if (command is CallCommand.Dial) {
            if (command.number.isEmpty()) return@withContext CallCommandResult(CallCommandStatus.MALFORMED, "empty_dial_number")
            if (!granted(Manifest.permission.CALL_PHONE)) return@withContext unavailable("call_phone_permission_required")
            val telecom = context.getSystemService(TelecomManager::class.java) ?: return@withContext unavailable("telecom_unavailable")
            try {
                telecom.placeCall(Uri.fromParts("tel", command.number, null), Bundle())
                return@withContext CallCommandResult(CallCommandStatus.DISPATCHED)
            } catch (problem: Exception) {
                if (problem is CancellationException) throw problem
                return@withContext failed(problem)
            }
        }
        // An ordinary companion never acquires a signature permission by naming a system package.
        val telecom = context.getSystemService(TelecomManager::class.java)
        val authorized = try { telecom?.hasManageOngoingCallsPermission() == true || telecom?.defaultDialerPackage == context.packageName }
            catch (_: Exception) { false }
        if (!authorized) return@withContext unavailable("manage_ongoing_calls_required")
        val service = CompanionCallRegistry.service ?: return@withContext unavailable("incall_service_not_connected")
        try { service.execute(command, watch) } catch (problem: Exception) {
            if (problem is CancellationException) throw problem
            failed(problem)
        }
    }

    @SuppressLint("MissingPermission") // Checked here and again by TelephonyManager.
    private fun updateTelephonyCallback() {
        if (!access.readPhoneState) {
            phoneCallback?.let { callback -> runCatching { telephony?.unregisterTelephonyCallback(callback) } }
            phoneCallback = null; telephony = null; telephonyState = null
            return
        }
        if (phoneCallback != null) return
        val manager = context.getSystemService(TelephonyManager::class.java) ?: return
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                if (closed.get()) return
                telephonyState = state; publish()
            }
        }
        try {
            manager.registerTelephonyCallback(context.mainExecutor, callback)
            telephony = manager; phoneCallback = callback
        } catch (problem: Exception) { log("telephony_callback_unavailable", problem) }
    }

    private fun publish() {
        if (closed.get()) return
        val native = CompanionCallRegistry.state.value
        mutable.value = native.copy(access = access, telephonyStateValue = telephonyState)
    }
    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun unavailable(reason: String) = CallCommandResult(CallCommandStatus.UNAVAILABLE, reason)
    private fun failed(problem: Exception): CallCommandResult {
        log("native_command_failed", problem)
        return CallCommandResult(if (problem is SecurityException) CallCommandStatus.UNAVAILABLE else CallCommandStatus.FAILED,
            problem.javaClass.simpleName)
    }
    private fun log(event: String, problem: Exception) {
        runCatching { diagnostics.record(ProtocolDiagnostic("calling_android", event,
            details = mapOf("exception" to problem.javaClass.simpleName))) }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        lifetime.cancel()
        mainHandler.post {
            phoneCallback?.let { callback -> runCatching { telephony?.unregisterTelephonyCallback(callback) } }
            phoneCallback = null; telephony = null
        }
    }
}
