package dev.ticbox.companion.ui.toolbox

import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import dev.ticbox.companion.api.ToolboxApp
import dev.ticbox.companion.api.ToolboxBackendKind
import dev.ticbox.companion.api.ToolboxFile
import dev.ticbox.companion.api.ToolboxScreenEncoder
import dev.ticbox.companion.api.ToolboxTarget
import dev.ticbox.companion.api.WatchToolboxClient
import dev.ticbox.companion.ui.bridge.BridgeInstallState
import dev.ticbox.companion.ui.bridge.LOCAL_BRIDGE_PACKAGE
import java.util.EnumMap
import java.util.WeakHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private val memories = WeakHashMap<WatchToolboxClient, ToolboxMemory>()

/** One for the watch's pair of connections, however often the toolbox's pages come and go. */
internal fun toolboxMemory(client: WatchToolboxClient): ToolboxMemory =
    synchronized(memories) { memories.getOrPut(client) { ToolboxMemory() } }

/**
 * What the watch already said, since asking again takes it a second or two each time. It's kept in
 * memory only, and for as long as the process lives.
 *
 * A list is only good on the connection it came from: each row carries that connection's target,
 * and operations refuse any other. So a reconnect starts over, while a connection that stays up
 * never asks twice. Anything that changes the watch has to [forget] what it touched.
 */
internal class ToolboxMemory {
    private class Lists(val target: ToolboxTarget) {
        var apps: List<ToolboxApp>? = null
        val folders = HashMap<String, List<ToolboxFile>>()
    }

    private val lists = EnumMap<ToolboxBackendKind, Lists>(ToolboxBackendKind::class.java)
    private val bridge = MutableStateFlow<ToolboxApp?>(null)

    /** The Local Bridge on the watch, as last listed. */
    val bridgeOnWatch: StateFlow<ToolboxApp?> = bridge.asStateFlow()

    private val encoders =
        MutableStateFlow<Map<ToolboxTarget, Map<Int, List<ToolboxScreenEncoder>>>>(emptyMap())

    /**
     * What the device's screen encoders declared on a connection, by the long side asked for. They
     * don't change while it stays up; a new connection of the same kind replaces the old one's.
     */
    val screenEncoders: StateFlow<Map<ToolboxTarget, Map<Int, List<ToolboxScreenEncoder>>>> =
        encoders.asStateFlow()

    fun rememberScreenEncoders(
        target: ToolboxTarget,
        maxSize: Int,
        declared: List<ToolboxScreenEncoder>,
    ) = encoders.update { all ->
        all.filterKeys { it.kind != target.kind } +
            (target to (all[target].orEmpty() + (maxSize to declared)))
    }

    /** Watch apps' icons come over the companion link, so they last across tool connections. */
    val icons = LruCache<String, ImageBitmap>(MAX_ICONS)

    /** An update outlasts its page, and a second one mustn't start while it runs. */
    val bridgeInstall = MutableStateFlow<BridgeInstallState>(BridgeInstallState.Idle)
    val bridgeInstallTarget = MutableStateFlow<ToolboxTarget?>(null)

    private fun of(target: ToolboxTarget): Lists =
        lists[target.kind]?.takeIf { it.target == target }
            ?: Lists(target).also { lists[target.kind] = it }

    @Synchronized fun apps(target: ToolboxTarget): List<ToolboxApp>? = of(target).apps

    @Synchronized
    fun rememberApps(target: ToolboxTarget, apps: List<ToolboxApp>) {
        of(target).apps = apps
        bridge.value = apps.firstOrNull { it.packageName == LOCAL_BRIDGE_PACKAGE }
    }

    @Synchronized
    fun files(target: ToolboxTarget, path: String): List<ToolboxFile>? = of(target).folders[path]

    @Synchronized
    fun rememberFiles(target: ToolboxTarget, path: String, files: List<ToolboxFile>) {
        of(target).folders[path] = files
    }

    @Synchronized
    fun forgetApps() {
        lists.values.forEach { it.apps = null }
    }

    /**
     * Whatever [path] was, the folder holding it now lists it wrongly, and what was under it is
     * gone or elsewhere.
     */
    @Synchronized
    fun forget(path: String) {
        val inside = path.trimEnd('/') + "/"
        val parent = parentPath(path)
        lists.values.forEach { list ->
            list.folders.keys.removeAll { it == path || it == parent || it.startsWith(inside) }
        }
    }

    /** For what could have changed anything, such as a shell or an install. */
    @Synchronized
    fun forgetLists() {
        lists.values.forEach {
            it.apps = null
            it.folders.clear()
        }
    }

    private companion object {
        const val MAX_ICONS = 256
    }
}
