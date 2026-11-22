@file:Suppress("OVERRIDE_DEPRECATION") // Legacy state/audio callbacks are retained where Android still delivers them.

package dev.ticbox.companion.platform.calling

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.OutcomeReceiver
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.util.Log
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.calling.CallTargetSelector
import dev.ticbox.companion.engine.calling.CallingPlatformState
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local registry of a genuine Telecom binding. No app service pretends to bind Telecom itself. */
internal object CompanionCallRegistry {
    private val mutable = MutableStateFlow(CallingPlatformState())
    val state = mutable.asStateFlow()
    var service: CompanionInCallService? = null
        private set
    fun attach(service: CompanionInCallService) { this.service = service; publish(service, CallingPlatformState(serviceConnected = true)) }
    fun publish(owner: CompanionInCallService, state: CallingPlatformState) { if (service === owner) mutable.value = state }
    fun detach(owner: CompanionInCallService) {
        if (service !== owner) return
        service = null
        mutable.value = CallingPlatformState(silenceRingerSequence = mutable.value.silenceRingerSequence)
    }
}

/** Non-UI wearable InCallService; the user's existing phone dialer keeps its UI and ringtone ownership. */
class CompanionInCallService : InCallService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val records = linkedMapOf<Call, Record>()
    private var endpoints = emptyList<CallEndpoint>()
    private var currentEndpoint: CallEndpoint? = null
    private var muted: Boolean? = null
    private var silenceSequence = 0L
    private var bound = false
    private val endpointRequests = mutableSetOf<CancellableContinuation<CallCommandResult>>()

    private class Record(val id: String, val callback: Call.Callback, var state: Int?) { var tone: Job? = null }

    override fun onBind(intent: Intent): IBinder? {
        val binder = super.onBind(intent)
        if (binder != null) {
            bound = true
            silenceSequence = CompanionCallRegistry.state.value.silenceRingerSequence
            CompanionCallRegistry.attach(this)
            @Suppress("DEPRECATION") // Only initializes mute when Telecom's new callback has not arrived.
            val initialAudio = callAudioState
            muted = initialAudio?.isMuted
            publish()
        }
        return binder
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        if (records.containsKey(call)) return
        val callback = object : Call.Callback() {
            @Suppress("DEPRECATION")
            override fun onStateChanged(call: Call, state: Int) { records[call]?.state = state; publish() }
            override fun onDetailsChanged(call: Call, details: Call.Details) { records[call]?.state = details.state; publish() }
            override fun onCallDestroyed(call: Call) { remove(call) }
            override fun onChildrenChanged(call: Call, children: MutableList<Call>) { publish() }
            override fun onParentChanged(call: Call, parent: Call?) { publish() }
        }
        // The original companion also uses a fresh UUID per Telecom Call, not a hidden Telecom ID.
        records[call] = Record(UUID.randomUUID().toString(), callback, call.details?.state)
        call.registerCallback(callback, Handler(Looper.getMainLooper()))
        publish()
    }

    override fun onCallRemoved(call: Call) { remove(call); super.onCallRemoved(call) }
    private fun remove(call: Call) {
        val record = records.remove(call) ?: return
        record.tone?.cancel(); runCatching { call.stopDtmfTone() }; call.unregisterCallback(record.callback)
        publish()
    }

    override fun onAvailableCallEndpointsChanged(availableEndpoints: MutableList<CallEndpoint>) {
        endpoints = availableEndpoints.toList(); publish()
    }
    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) { currentEndpoint = callEndpoint; publish() }
    override fun onMuteStateChanged(isMuted: Boolean) { muted = isMuted; publish() }
    @Suppress("DEPRECATION")
    override fun onCallAudioStateChanged(audioState: CallAudioState) { muted = audioState.isMuted; publish() }
    override fun onSilenceRinger() { silenceSequence += 1; publish() }

    private fun publish() {
        if (!bound) return
        val calls = records.mapNotNull { (call, record) ->
            try {
                val suppressed = call.details?.extras?.getBoolean(Call.EXTRA_IS_SUPPRESSED_BY_DO_NOT_DISTURB, false) == true
                if (CallTargetSelector.isSuppressedRinging(record.state ?: -1, suppressed)) null else snapshot(call, record)
            } catch (problem: Exception) {
                log("call_snapshot_partial", problem)
                CompanionCall(record.id, record.state ?: -1)
            }
        }
        CompanionCallRegistry.publish(this, CallingPlatformState(serviceConnected = true, calls = calls, muted = muted,
            endpoints = endpoints.map { CallAudioEndpoint(it.identifier.toString(), it.endpointType, it.endpointName.toString()) },
            currentEndpointId = currentEndpoint?.identifier?.toString(), silenceRingerSequence = silenceSequence))
    }

    private fun snapshot(call: Call, record: Record): CompanionCall {
        val details = call.details ?: return CompanionCall(record.id, record.state ?: -1)
        val disconnected = details.disconnectCause?.let { cause ->
            CallDisconnectCause(cause.code, cause.label?.toString(), cause.description?.toString(), cause.reason, cause.tone)
        }
        return CompanionCall(record.id, record.state ?: details.state, details.handle?.schemeSpecificPart,
            details.handlePresentation, details.callerDisplayName, details.callerDisplayNamePresentation,
            details.callCapabilities, details.callProperties, disconnected, details.connectTimeMillis,
            details.videoState, details.creationTimeMillis, details.contactDisplayName, details.callDirection, true)
    }

    @SuppressLint("MissingPermission") // Telecom enforces access; no privileged permission is pretended or requested.
    internal suspend fun execute(command: CallCommand, watch: PeerAddress?): CallCommandResult {
        if (!bound || CompanionCallRegistry.service !== this) return unavailable("incall_service_disconnected")
        if (command is CallCommand.SelectAudioEndpoint) {
            val selected = endpoints.firstOrNull { it.identifier.toString() == command.endpointId } ?: return unavailable("audio_endpoint_unavailable")
            return requestEndpoint(selected)
        }
        if (command == CallCommand.RouteAudioToWatch) return routeToWatch(watch)
        if (command is CallCommand.Action && command.actionValue in setOf(5, 6)) {
            if (command.callId.isNotEmpty() && records.values.none { it.id == command.callId }) return unavailable("call_not_found")
            setMuted(command.actionValue == 5)
            return dispatched(command.callId.takeIf { it.isNotEmpty() })
        }
        if (command is CallCommand.Action && command.actionValue == 7) {
            // Public API can reject an ordinary companion even though it may manage ongoing calls.
            getSystemService(TelecomManager::class.java).silenceRinger()
            return dispatched(command.callId.takeIf { it.isNotEmpty() })
        }
        if (command is CallCommand.Action && command.actionValue !in setOf(1, 2, 3, 4, 8, 9))
            return CallCommandResult(CallCommandStatus.UNSUPPORTED, "unknown_or_unimplemented_action_${command.actionValue}")
        val id = when (command) {
            is CallCommand.Action -> command.callId
            is CallCommand.Reject -> command.callId
            is CallCommand.Dtmf -> command.callId
            else -> return CallCommandResult(CallCommandStatus.UNSUPPORTED, "unsupported_native_command")
        }
        val selection = CallTargetSelector.select(CompanionCallRegistry.state.value.calls, id,
            answer = command is CallCommand.Action && command.actionValue in setOf(1, 8, 9))
            ?: return unavailable(if (id.isNotEmpty()) "call_not_found" else "foreground_call_missing_or_ambiguous")
        val entry = records.entries.firstOrNull { it.value.id == selection.id } ?: return unavailable("call_removed")
        val call = entry.key
        when (command) {
            is CallCommand.Action -> when (command.actionValue) {
                1, 8, 9 -> {
                    call.answer(call.details?.videoState ?: 0)
                    if (command.actionValue == 8) {
                        val routed = try { routeToWatch(watch) } catch (problem: Exception) {
                            if (problem is CancellationException) throw problem
                            log("answer_audio_route_failed", problem)
                            unavailable(problem.javaClass.simpleName)
                        }
                        if (routed.status != CallCommandStatus.DISPATCHED)
                            return CallCommandResult(CallCommandStatus.DISPATCHED, "answer_dispatched_audio_route_${routed.reason}", selection.id)
                    }
                }
                2 -> call.disconnect()
                3 -> call.hold()
                4 -> call.unhold()
            }
            is CallCommand.Reject -> {
                if (command.reasonValue !in 0..2) log("unknown_reject_reason", detail = command.reasonValue.toString())
                // The official client ignores the enum reason and uses text presence to select reject-with-SMS.
                @Suppress("DEPRECATION") // The newer reason-only overload cannot carry stock reject-with-SMS text.
                call.reject(command.textMessage.isNotEmpty(), command.textMessage)
            }
            is CallCommand.Dtmf -> {
                if (command.code.length != 1 || command.code[0] !in "0123456789ABCD*#")
                    return CallCommandResult(CallCommandStatus.MALFORMED, "invalid_dtmf")
                entry.value.tone?.cancelAndJoin()
                call.playDtmfTone(command.code[0])
                entry.value.tone = scope.launch {
                    try { delay(300) } finally { runCatching { call.stopDtmfTone() } }
                }
            }
        }
        return dispatched(selection.id)
    }

    private suspend fun requestEndpoint(endpoint: CallEndpoint): CallCommandResult = try {
        withTimeout(10_000) {
            suspendCancellableCoroutine { continuation ->
                endpointRequests += continuation
                continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { endpointRequests.remove(continuation) } }
                requestCallEndpointChange(endpoint, mainExecutor, object : OutcomeReceiver<Void, CallEndpointException> {
                    override fun onResult(result: Void?) {
                        endpointRequests.remove(continuation)
                        if (continuation.isActive) continuation.resumeWith(Result.success(dispatched()))
                    }
                    override fun onError(error: CallEndpointException) {
                        endpointRequests.remove(continuation)
                        if (continuation.isActive) continuation.resumeWith(Result.success(CallCommandResult(CallCommandStatus.FAILED,
                            "audio_endpoint_error_${error.code}")))
                    }
                })
            }
        }
    } catch (_: TimeoutCancellationException) { CallCommandResult(CallCommandStatus.FAILED, "audio_endpoint_timeout") }

    @SuppressLint("MissingPermission") // Access checked immediately before reading Bluetooth devices.
    private suspend fun routeToWatch(watch: PeerAddress?): CallCommandResult {
        if (watch == null) return unavailable("watch_peer_unavailable")
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            return unavailable("bluetooth_connect_permission_required")
        @Suppress("DEPRECATION") // CallEndpoint provides no Bluetooth MAC; this list proves an actual HFP candidate.
        val devices: Collection<BluetoothDevice> = callAudioState?.supportedBluetoothDevices.orEmpty()
        val target = devices.firstOrNull { it.address.equals(watch.value, ignoreCase = true) }
            ?: return unavailable("watch_hfp_audio_unavailable")
        val bluetooth = endpoints.filter { it.endpointType == CallEndpoint.TYPE_BLUETOOTH }
        val named = bluetooth.filter { it.endpointName.toString() == target.name }
        val endpoint = named.singleOrNull() ?: bluetooth.singleOrNull()?.takeIf { devices.size == 1 }
        if (endpoint != null) return requestEndpoint(endpoint)
        // Modern endpoints omit addresses; this public fallback preserves precise MAC selection.
        @Suppress("DEPRECATION")
        requestBluetoothAudio(target)
        log("legacy_precise_bluetooth_audio_requested")
        return dispatched()
    }

    override fun onUnbind(intent: Intent): Boolean {
        release(); return super.onUnbind(intent)
    }
    private fun release() {
        bound = false
        endpointRequests.toList().forEach { continuation ->
            if (continuation.isActive) continuation.resumeWith(Result.success(unavailable("incall_service_disconnected")))
        }
        endpointRequests.clear()
        records.toMap().forEach { (call, record) ->
            record.tone?.cancel(); runCatching { call.stopDtmfTone() }; runCatching { call.unregisterCallback(record.callback) }
        }
        records.clear(); endpoints = emptyList(); currentEndpoint = null; muted = null
        CompanionCallRegistry.detach(this)
    }
    override fun onDestroy() { release(); scope.cancel(); super.onDestroy() }
    private fun dispatched(id: String? = null) = CallCommandResult(CallCommandStatus.DISPATCHED, callId = id)
    private fun unavailable(reason: String) = CallCommandResult(CallCommandStatus.UNAVAILABLE, reason)
    private fun log(event: String, problem: Exception? = null, detail: String? = null) {
        Log.i("WatchCalling", "$event exception=${problem?.javaClass?.simpleName ?: "none"} detail=${detail ?: "none"}")
    }
}
