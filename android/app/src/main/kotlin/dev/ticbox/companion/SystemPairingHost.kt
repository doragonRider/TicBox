package dev.ticbox.companion

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.ticbox.companion.api.PairingActionKind
import dev.ticbox.companion.platform.pairing.AndroidPairingActions
import dev.ticbox.companion.platform.pairing.AndroidPairingLaunch
import kotlinx.coroutines.*

/**
 * Activity-owned system UI bridge. Every native launch has its own saved registry key and captures
 * its opaque token: a late result can never be attributed to a later launch after rotation/cancel.
 * Construct in onCreate, attach LocalBinder.pairingActions when bound, and detach on unbind.
 */
class SystemPairingHost(
    private val activity: ComponentActivity,
    private val key: String = "backend.pairing",
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var actions: AndroidPairingActions? = null
    private var collection: Job? = null
    private var activeId: String? = null
    private var cancellationId: String? = null
    private var closed = false
    private val launches = linkedMapOf<String, NativeLaunch>()
    private val pending = ArrayDeque<Completion>()

    private class NativeLaunch(val id: String, val owner: String, val kind: PairingActionKind) {
        var unregister: (() -> Unit)? = null
        var launch: ((AndroidPairingLaunch) -> Unit)? = null
    }

    private sealed interface Completion {
        val id: String
        val owner: String

        data class Permissions(override val id: String, override val owner: String) : Completion

        data class Result(
            override val id: String,
            override val owner: String,
            val code: Int,
            val intent: Intent?,
        ) : Completion
    }

    private val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> observe()
            Lifecycle.Event.ON_PAUSE -> {
                collection?.cancel()
                collection = null
            }
            Lifecycle.Event.ON_DESTROY -> close()
            else -> Unit
        }
    }

    init {
        activity.savedStateRegistry.consumeRestoredStateForKey(key)?.let { saved ->
            activeId = saved.getString("action")
            cancellationId = saved.getString("cancellation")
            @Suppress("DEPRECATION")
            val slots = saved.getParcelableArrayList<Bundle>("launches") ?: arrayListOf()
            for (slot in slots.take(MAX_RETAINED)) {
                val id = slot.getString("id") ?: continue
                val owner = slot.getString("owner") ?: continue
                val kind =
                    runCatching { PairingActionKind.valueOf(slot.getString("kind") ?: "") }
                        .getOrNull() ?: continue
                register(NativeLaunch(id, owner, kind))
            }
            @Suppress("DEPRECATION")
            val completions = saved.getParcelableArrayList<Bundle>("results") ?: arrayListOf()
            for (result in completions.take(MAX_RETAINED)) {
                val id = result.getString("id") ?: continue
                val owner = result.getString("owner") ?: continue
                enqueue(
                    if (result.getBoolean("permissions")) Completion.Permissions(id, owner)
                    else
                        Completion.Result(
                            id,
                            owner,
                            result.getInt("code"),
                            result.getParcelable("intent", Intent::class.java),
                        )
                )
            }
        }
        activity.savedStateRegistry.registerSavedStateProvider(key) {
            Bundle().apply {
                putString("action", activeId)
                putString("cancellation", cancellationId)
                putParcelableArrayList(
                    "launches",
                    ArrayList(
                        launches.values.map { slot ->
                            Bundle().apply {
                                putString("id", slot.id)
                                putString("owner", slot.owner)
                                putString("kind", slot.kind.name)
                            }
                        }
                    ),
                )
                putParcelableArrayList(
                    "results",
                    ArrayList(
                        pending.map { completion ->
                            Bundle().apply {
                                putString("id", completion.id)
                                putString("owner", completion.owner)
                                putBoolean("permissions", completion is Completion.Permissions)
                                if (completion is Completion.Result) {
                                    putInt("code", completion.code)
                                    putParcelable("intent", completion.intent)
                                }
                            }
                        }
                    ),
                )
            }
        }
        activity.lifecycle.addObserver(observer)
    }

    /** Main thread. Registry identity distinguishes rotation from process/service replacement. */
    fun attach(registry: AndroidPairingActions) {
        check(!closed)
        collection?.cancel()
        collection = null
        actions = registry
        while (pending.isNotEmpty()) deliver(registry, pending.removeFirst())
        // A cancelled launch remains registered under its own key for its eventual native callback.
        // It does not need to occupy the current operation's token slot.
        activeId?.let { id ->
            if (
                launches[id]?.owner != registry.instanceId ||
                    registry.state.value.none { it.id == id }
            )
                activeId = null
        }
        cancellationId?.let { id ->
            if (
                launches[id]?.owner != registry.instanceId ||
                    registry.state.value.none { it.id == id }
            )
                cancellationId = null
        }
        observe()
    }

    fun detach() {
        collection?.cancel()
        collection = null
        actions = null
    }

    private fun observe() {
        if (
            closed ||
                collection != null ||
                !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        )
            return
        val registry = actions ?: return
        collection = scope.launch { registry.state.collect { launchNext(registry) } }
    }

    private fun launchNext(registry: AndroidPairingActions) {
        if (
            closed ||
                actions !== registry ||
                !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        )
            return
        val cancelAction =
            registry.state.value.firstOrNull { it.kind == PairingActionKind.CANCEL_ASSOCIATION }
        if (cancelAction != null && cancellationId == null) {
            val descriptor = registry.prepare(cancelAction.id) ?: return
            cancellationId = descriptor.actionId
            start(registry, cancelAction.kind, descriptor)
            return
        }
        activeId?.let { id -> if (registry.state.value.none { it.id == id }) activeId = null }
        if (activeId != null || cancellationId != null) return
        val action =
            registry.state.value.firstOrNull {
                it.kind != PairingActionKind.CANCEL_ASSOCIATION && launches[it.id] == null
            } ?: return
        val descriptor = registry.prepare(action.id) ?: return
        activeId = descriptor.actionId
        start(registry, action.kind, descriptor)
    }

    private fun start(
        registry: AndroidPairingActions,
        kind: PairingActionKind,
        descriptor: AndroidPairingLaunch,
    ) {
        val slot = NativeLaunch(descriptor.actionId, registry.instanceId, kind)
        try {
            register(slot)
            slot.launch?.invoke(descriptor)
        } catch (problem: Exception) {
            forget(slot.id)
            if (activeId == slot.id) activeId = null
            if (cancellationId == slot.id) cancellationId = null
            registry.failLaunch(slot.id, problem)
        }
    }

    /** Lifecycle-free registration permits rebuilding saved per-action keys during onCreate. */
    private fun register(slot: NativeLaunch) {
        // Unregistering an in-flight key can make ActivityResultRegistry retain its late result
        // without a consumer. Bound native requests too: surface a retryable launch failure
        // instead of continually allocating abandoned registry keys.
        check(launches.size < MAX_RETAINED) { "Too many outstanding system pairing results" }
        launches[slot.id] = slot
        val registryKey = "$key.${slot.kind.name}.${slot.id}"
        when (slot.kind) {
            PairingActionKind.BLUETOOTH_PERMISSIONS -> {
                val launcher =
                    activity.activityResultRegistry.register(
                        registryKey,
                        ActivityResultContracts.RequestMultiplePermissions(),
                    ) {
                        finish(slot, Completion.Permissions(slot.id, slot.owner))
                    }
                bind(slot, launcher) { descriptor ->
                    launcher.launch(
                        (descriptor as AndroidPairingLaunch.Permissions).permissions.toTypedArray()
                    )
                }
            }
            PairingActionKind.ENABLE_BLUETOOTH,
            PairingActionKind.FORGET_BLUETOOTH_DEVICE -> {
                val launcher =
                    activity.activityResultRegistry.register(
                        registryKey,
                        ActivityResultContracts.StartActivityForResult(),
                    ) {
                        finish(slot, Completion.Result(slot.id, slot.owner, it.resultCode, it.data))
                    }
                bind(slot, launcher) { descriptor ->
                    launcher.launch((descriptor as AndroidPairingLaunch.ActivityIntent).intent)
                }
            }
            else -> {
                val launcher =
                    activity.activityResultRegistry.register(
                        registryKey,
                        ActivityResultContracts.StartIntentSenderForResult(),
                    ) {
                        finish(slot, Completion.Result(slot.id, slot.owner, it.resultCode, it.data))
                    }
                bind(slot, launcher) { descriptor ->
                    launcher.launch(
                        IntentSenderRequest.Builder(
                                (descriptor as AndroidPairingLaunch.Sender).sender
                            )
                            .build()
                    )
                }
            }
        }
    }

    private fun <T> bind(
        slot: NativeLaunch,
        launcher: ActivityResultLauncher<T>,
        launch: (AndroidPairingLaunch) -> Unit,
    ) {
        slot.unregister = launcher::unregister
        slot.launch = launch
        // register() can synchronously deliver a saved pending result before it returns.
        if (launches[slot.id] !== slot) launcher.unregister()
    }

    private fun finish(slot: NativeLaunch, completion: Completion) {
        if (closed || launches[slot.id] !== slot) return
        forget(slot.id)
        if (activeId == slot.id) activeId = null
        if (cancellationId == slot.id) cancellationId = null
        val registry = actions
        if (registry == null) {
            enqueue(completion)
        } else deliver(registry, completion)
        if (registry != null) launchNext(registry)
    }

    private fun enqueue(completion: Completion) {
        if (pending.size >= MAX_RETAINED) pending.removeFirst()
        pending += completion
    }

    private fun deliver(registry: AndroidPairingActions, completion: Completion) {
        if (registry.instanceId != completion.owner) return
        when (completion) {
            is Completion.Permissions -> registry.completePermissionsResult(completion.id)
            is Completion.Result ->
                registry.completeActivityResult(completion.id, completion.code, completion.intent)
        }
    }

    private fun forget(id: String) {
        launches.remove(id)?.unregister?.invoke()
    }

    override fun close() {
        if (closed) return
        closed = true
        detach()
        scope.cancel()
        activity.lifecycle.removeObserver(observer)
        activity.savedStateRegistry.unregisterSavedStateProvider(key)
        launches.values.toList().forEach { it.unregister?.invoke() }
        launches.clear()
        pending.clear()
    }

    private companion object {
        const val MAX_RETAINED = 8
    }
}
