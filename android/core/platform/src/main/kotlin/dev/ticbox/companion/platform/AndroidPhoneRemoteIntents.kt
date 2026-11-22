package dev.ticbox.companion.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.settings.PhoneRemoteIntentExecutor
import dev.ticbox.companion.protocol.RemoteIntentRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Uses ordinary Android component permissions and background-launch policy for the paired watch. */
class AndroidPhoneRemoteIntents(context: Context, private val diagnostics: DiagnosticSink = DiagnosticSink.NONE) : PhoneRemoteIntentExecutor {
    private val context = context.applicationContext

    override suspend fun execute(request: RemoteIntentRequest): Boolean = withContext(Dispatchers.Main.immediate) {
        if (request.effectiveStartMode == 3) {
            log("webview_unavailable") // The official phone handler also returns false for WEBVIEW.
            return@withContext false
        }
        var unavailable = 0
        val intent = Intent().apply {
            request.action?.let(::setAction)
            request.packageName?.let(::setPackage)
            if (request.uri != null || request.mimeType != null)
                setDataAndType(request.uri?.let(Uri::parse), request.mimeType)
            request.categories.forEach(::addCategory)
            putExtras(DataMapBundles.fromMap(request.extras) { unavailable++ })
        }
        val options = request.activityOptions.takeIf { it.isNotEmpty() }?.let {
            // This SDK hint is not a public ActivityOptions key. Android owns lockscreen policy.
            if (it["activity_options_wake_phone"] != null) log("wake_phone_hint_received")
            DataMapBundles.fromMap(it - "activity_options_wake_phone") { unavailable++ }
        }
        if (unavailable > 0) log("optional_bundle_values_unavailable", mapOf("count" to unavailable.toString()))
        when (request.effectiveStartMode) {
            1 -> { context.sendBroadcast(intent); true }
            2 -> context.startService(intent) != null
            else -> { // ACTIVITY and ACTIVITY_THIRD_PARTY use the same native dispatch boundary.
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent, options)
                true
            }
        }
    }

    private fun log(event: String, details: Map<String, String> = emptyMap()) {
        runCatching { diagnostics.record(ProtocolDiagnostic("remote_intent_android", event, details = details)) }
    }
}
