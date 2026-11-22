package dev.ticbox.companion.platform.toolbox

import android.content.Context
import dev.ticbox.companion.api.ToolboxException
import dev.ticbox.companion.engine.module.HealthModulePayload
import dev.ticbox.companion.engine.toolbox.toolboxIo
import dev.ticbox.companion.protocol.MagiskModuleProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Assets belong to :app; no dependency on a Magisk binary or native library on the phone. */
internal class EmbeddedHealthModule(private val context: Context) {
    suspend fun load(): HealthModulePayload = toolboxIo {
        try {
            val catalog = context.assets.open(MagiskModuleProtocol.CATALOG_ASSET).use { input ->
                val bytes = input.readBytes(); require(bytes.size <= 16 * 1024)
                MagiskModuleProtocol.decode(Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject)
            }
            require(context.assets.open(MagiskModuleProtocol.ASSET).use { MagiskModuleProtocol.sha256(it, MagiskModuleProtocol.MAX_ZIP_BYTES.toLong()) } == catalog.sha256)
            HealthModulePayload(catalog) { context.assets.open(MagiskModuleProtocol.ASSET) }
        } catch (error: Exception) { throw ToolboxException("module_payload_unavailable", error) }
    }
}
