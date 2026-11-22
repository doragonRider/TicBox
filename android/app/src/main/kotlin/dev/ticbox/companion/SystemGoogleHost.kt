package dev.ticbox.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.ticbox.companion.platform.google.AndroidGoogleActions
import kotlinx.coroutines.*

/** Presents genuine GMS authorization UI from a resumed Activity, with no credential access. */
class SystemGoogleHost(
    private val activity: ComponentActivity,
    private val key: String = "backend.google",
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val launchers = linkedMapOf<String, ActivityResultLauncher<IntentSenderRequest>>()
    private val pending = linkedMapOf<String, Int>()
    private var actions: AndroidGoogleActions? = null
    private var collection: Job? = null
    private var activeId: String? = null
    private var closed = false
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
            activeId = saved.getString("active")
            saved.getStringArrayList("launches")?.take(MAX_RETAINED)?.forEach(::register)
            saved.getBundle("results")?.let { results ->
                results.keySet().take(MAX_RETAINED).forEach { id ->
                    pending[id] = results.getInt(id)
                }
            }
        }
        activity.savedStateRegistry.registerSavedStateProvider(key) {
            Bundle().apply {
                putString("active", activeId)
                putStringArrayList("launches", ArrayList(launchers.keys))
                putBundle(
                    "results",
                    Bundle().apply { pending.forEach { (id, code) -> putInt(id, code) } },
                )
            }
        }
        activity.lifecycle.addObserver(observer)
    }

    /**
     * Attach LocalBinder.googleActions on the main thread; detach when its service binding ends.
     */
    fun attach(registry: AndroidGoogleActions) {
        check(!closed)
        collection?.cancel()
        collection = null
        actions = registry
        pending.toMap().forEach { (id, code) -> registry.activityResult(id, code) }
        pending.clear()
        if (registry.state.value.none { it.id == activeId }) activeId = null
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

    private fun launchNext(registry: AndroidGoogleActions) {
        if (
            closed ||
                actions !== registry ||
                !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        )
            return
        if (activeId != null && registry.state.value.any { it.id == activeId }) return
        activeId = null
        val action = registry.state.value.firstOrNull { it.id !in launchers } ?: return
        val launch = registry.takeLaunch(action.id) ?: return
        try {
            activeId = action.id
            register(action.id)
            launchers[action.id]?.launch(IntentSenderRequest.Builder(launch.sender).build())
        } catch (problem: Exception) {
            activeId = null
            launchers.remove(action.id)?.unregister()
            registry.launchFailed(action.id, problem)
        }
    }

    private fun register(id: String) {
        check(launchers.size < MAX_RETAINED) {
            "Outstanding Google authorization window limit reached"
        }
        // Results capture an immutable per-action token. Old dialogs cannot complete a newer one.
        var deliveredWhileRegistering = false
        val launcher =
            activity.activityResultRegistry.register(
                "$key.$id",
                ActivityResultContracts.StartIntentSenderForResult(),
            ) { result ->
                deliveredWhileRegistering = true
                launchers.remove(id)?.unregister()
                if (activeId == id) activeId = null
                val registry = actions
                if (registry == null) {
                    if (pending.size >= MAX_RETAINED) pending.remove(pending.keys.first())
                    pending[id] = result.resultCode
                } else registry.activityResult(id, result.resultCode)
                // UI return is only an acknowledgement; the backend waits for GMS's Binder result.
                if (registry != null) launchNext(registry)
            }
        if (deliveredWhileRegistering) launcher.unregister() else launchers[id] = launcher
    }

    override fun close() {
        if (closed) return
        closed = true
        detach()
        scope.cancel()
        activity.lifecycle.removeObserver(observer)
        activity.savedStateRegistry.unregisterSavedStateProvider(key)
        launchers.values.toList().forEach { it.unregister() }
        launchers.clear()
        pending.clear()
    }

    private companion object {
        const val MAX_RETAINED = 8
    }
}
