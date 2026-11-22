package dev.ticbox.companion.toolboxagent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.os.Build
import androidx.annotation.RequiresApi
import dev.ticbox.companion.protocol.*
import kotlinx.serialization.json.*

/** Uses the real shell UID/package attribution, including an explicit empty clipboard result. */
internal object AgentClipboard {
    @Suppress("DEPRECATION") // Independent agent process deliberately adopts shell's clipboard identity.
    fun handle(wire: AgentWire, environment: AgentEnvironment, args: JsonObject, write: Boolean) {
        // root is useful for acquiring privilege, but UID 0 does not own com.android.shell.
        if (android.os.Process.myUid() == 0) android.system.Os.setuid(2000)
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val context = if (Build.VERSION.SDK_INT >= 31) AttributedShellContext(environment.context) else ShellContext(environment.context)
        // ClipboardManager retains the constructor context, rather than the system context's
        // cached manager (whose attribution would still be package android).
        val constructor = ClipboardManager::class.java.getDeclaredConstructor(Context::class.java, Handler::class.java).apply { isAccessible = true }
        val clipboard = constructor.newInstance(context, Handler(Looper.getMainLooper()))
        if (write) {
            val text = args.requiredText("text"); require(text.toByteArray(Charsets.UTF_8).size <= 1_048_576)
            clipboard.setPrimaryClip(ClipData.newPlainText(null, text))
            wire.json(ToolboxProtocol.RESULT, buildJsonObject { put("success", true) })
        } else {
            val clip = clipboard.primaryClip
            val text = if (clip == null || clip.itemCount == 0) "" else clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
            wire.json(ToolboxProtocol.RESULT, buildJsonObject { put("text", text) })
        }
    }
    private open class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = "com.android.shell"
        override fun getOpPackageName() = "com.android.shell"
        override fun getApplicationContext(): Context = this
        override fun getDeviceId() = 0
    }
    @RequiresApi(31)
    private class AttributedShellContext(base: Context) : ShellContext(base) {
        override fun getAttributionSource(): android.content.AttributionSource = android.content.AttributionSource.Builder(2000).setPackageName("com.android.shell").build()
    }
}
