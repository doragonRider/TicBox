package dev.ticbox.companion.toolboxagent

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import dev.ticbox.companion.protocol.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.serialization.json.*

@Suppress("DEPRECATION")
internal class AgentApps(private val wire: AgentWire, private val environment: AgentEnvironment) {
    private fun pm(user: Int): PackageManager {
        if (user == 0) return environment.context.packageManager
        val handle = android.os.UserHandle.getUserHandleForUid(Math.multiplyExact(user, 100_000))
        return (android.content.Context::class.java.getMethod("createContextAsUser", android.os.UserHandle::class.java, Int::class.javaPrimitiveType)
            .invoke(environment.context, handle, 0) as android.content.Context).packageManager
    }
    @SuppressLint("QueryPermissionsNeeded") // Runs under actual shell/root UID, exempt from ordinary app visibility filtering.
    fun list(user: Int) {
        require(user >= 0)
        // PackageManager under the actual shell/root process, not the ordinary Bridge app UID.
        val pm = pm(user)
        pm.getInstalledPackages(PackageManager.GET_DISABLED_COMPONENTS).forEach { info ->
            val name = info.packageName
            val app = info.applicationInfo ?: return@forEach
            wire.json(ToolboxProtocol.RESULT, buildJsonObject {
                put("packageName", name); put("label", pm.getApplicationLabel(app).toString())
                info.versionName?.let { put("versionName", it) }; put("versionCode", info.longVersionCode)
                put("uid", app.uid); put("enabled", app.enabled); put("system", app.flags and ApplicationInfo.FLAG_SYSTEM != 0)
                put("dataDirectory", app.dataDir)
                putJsonArray("apkPaths") { add(app.sourceDir); app.splitSourceDirs.orEmpty().forEach { add(it) } }
            })
        }
        wire.send(ToolboxProtocol.END)
    }
    fun action(args: JsonObject) {
        val name = packageName(args)
        val userId = (args.number("userId") ?: 0).also { require(it in 0..Int.MAX_VALUE) }.toInt()
        val user = userId.toString(); val pm = pm(userId)
        val action = args.requiredText("action")
        protectHost(name, action != "START" && action != "ENABLE" && action != "UNSUSPEND")
        if (action == "CLEAR_CACHE") {
            if (environment.uid != 0) throw SecurityException("root_required")
            val app = pm.getApplicationInfo(name, 0)
            val stopped = environment.commands.run(listOf("am", "force-stop", "--user", user, name)); check(stopped.code == 0)
            listOfNotNull(app.dataDir, app.deviceProtectedDataDir).distinct().forEach { root ->
                listOf("cache", "code_cache").forEach { cache ->
                    val directory = File(root, cache).toPath()
                    if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) Files.newDirectoryStream(directory).use { entries -> entries.forEach { AgentFiles(wire).remove(it) } }
                }
            }
            wire.json(ToolboxProtocol.EXIT, buildJsonObject { put("code", 0); put("timedOut", false) }); return
        }
        val argv = when (action) {
            "START" -> {
                val intent = pm.getLaunchIntentForPackage(name) ?: throw IllegalArgumentException("no_launcher_activity")
                listOf("am", "start", "--user", user, "-n", intent.component!!.flattenToString())
            }
            "FORCE_STOP" -> listOf("am", "force-stop", "--user", user, name)
            "ENABLE" -> listOf("cmd", "package", "enable", "--user", user, name)
            "DISABLE" -> listOf("cmd", "package", "disable-user", "--user", user, name)
            "UNINSTALL" -> listOf("cmd", "package", "uninstall") + (if (args.flag("keepData") == true) listOf("-k") else emptyList()) + listOf("--user", user, name)
            "CLEAR_DATA" -> listOf("cmd", "package", "clear", "--user", user, name)
            "SUSPEND" -> listOf("cmd", "package", "suspend", "--user", user, name)
            "UNSUSPEND" -> listOf("cmd", "package", "unsuspend", "--user", user, name)
            else -> throw IllegalArgumentException("unknown_app_action")
        }
        AgentCommand(wire, environment).run(argv.joinToString(" ", transform = ToolboxProtocol::quote), 120_000)
    }
    fun backup(args: JsonObject) {
        if (environment.uid != 0) throw SecurityException("root_required")
        val name = packageName(args); val user = (args.number("userId") ?: 0).also { require(it in 0..Int.MAX_VALUE) }.toInt()
        protectHost(name, true)
        val info = pm(user).getApplicationInfo(name, 0)
        val stop = environment.commands.run(listOf("am", "force-stop", "--user", user.toString(), name)); check(stop.code == 0)
        val paths = listOfNotNull(info.dataDir, info.deviceProtectedDataDir).distinct().filter { File(it).isDirectory }
        require(paths.isNotEmpty()) { "app_data_missing" }
        // Root is deliberately allowed to read the real private data, including device-protected data.
        val command = listOf("tar", "-C", "/", "-cf", "-") + paths.map { it.removePrefix("/") }
        AgentCommand(wire, environment).run(command.joinToString(" ", transform = ToolboxProtocol::quote), 1_800_000)
    }
    private fun packageName(args: JsonObject): String = args.requiredText("packageName").also {
        require(it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*"))) { "invalid_package_name" }
    }
    private fun protectHost(name: String, destructive: Boolean) {
        if (destructive && name == environment.transportHostPackage) throw TransportHostProtectedException()
    }
}
internal class TransportHostProtectedException : SecurityException("transport_host_protected")
