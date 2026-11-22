package dev.ticbox.companion.toolboxagent

import dev.ticbox.companion.protocol.*
import java.io.IOException
import kotlinx.serialization.json.*

internal class AgentInstaller(private val wire: AgentWire, private val environment: AgentEnvironment) {
    fun install(args: JsonObject) {
        val parts = (args["parts"] as? JsonArray)?.map { it as? JsonObject ?: throw IllegalArgumentException("invalid_apk_part") }.orEmpty()
        require(parts.isNotEmpty() && parts.size <= 1024)
        val sizes = parts.map { it.requiredLong("size").also { size -> require(size > 0) } }
        val total = sizes.fold(0L, Math::addExact)
        val user = args.number("userId")?.toInt() ?: 0; require(user >= 0)
        val flags = mutableListOf("cmd", "package", "install-create", "--user", user.toString(), "-S", total.toString())
        if (args.flag("replace") != false) flags += "-r"
        if (args.flag("allowDowngrade") == true) flags += "-d"
        if (args.flag("grantRuntimePermissions") == true) flags += "-g"
        val created = environment.commands.run(flags)
        val id = Regex("\\[(\\d+)]").find(created.output)?.groupValues?.get(1)
        if (created.code != 0 || id == null) { result(false, created.output); return }
        var abandonAllowed = true
        try {
            var completed = 0L
            for (index in parts.indices) {
                var copied = 0L
                val write = environment.commands.run(listOf("cmd", "package", "install-write", "-S", sizes[index].toString(), id, "part$index.apk", "-"), input = { output ->
                    while (true) {
                        val packet = wire.read()
                        if (packet.type == ToolboxProtocol.END) break
                        require(packet.type == ToolboxProtocol.DATA && packet.bytes.isNotEmpty())
                        copied = Math.addExact(copied, packet.bytes.size.toLong()); require(copied <= sizes[index]) { "apk_size_mismatch" }
                        output.write(packet.bytes); completed += packet.bytes.size
                        wire.json(ToolboxProtocol.PROGRESS, buildJsonObject { put("completed", completed); put("total", total); put("item", index) })
                    }
                    require(copied == sizes[index]) { "apk_size_mismatch" }
                }, timeoutMillis = 300_000)
                if (write.code != 0) { result(false, write.output); return }
            }
            // After dispatching commit, losing its reply cannot establish failure. A disconnect
            // must not kill this agent or abandon a possibly completed/in-progress installation.
            abandonAllowed = false
            val commit = try { environment.commands.run(listOf("cmd", "package", "install-commit", id), timeoutMillis = 300_000) }
            catch (error: Exception) { throw InstallResultUnknownException(error) }
            if (!commit.completed) throw InstallResultUnknownException()
            val committed = commit.code == 0 && commit.output.trimStart().startsWith("Success")
            if (!committed && !commit.output.trimStart().startsWith("Failure")) throw InstallResultUnknownException()
            abandonAllowed = !committed
            result(committed, commit.output)
        } finally {
            if (abandonAllowed) runCatching { environment.commands.run(listOf("cmd", "package", "install-abandon", id)) }
        }
    }
    private fun result(success: Boolean, output: String) = wire.json(ToolboxProtocol.RESULT, buildJsonObject { put("success", success); put("output", output) })
}

internal class InstallResultUnknownException(cause: Exception? = null) : IOException("install_result_unknown", cause)
