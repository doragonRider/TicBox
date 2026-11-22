package dev.ticbox.companion.toolboxagent

import android.content.Context
import android.annotation.SuppressLint
import android.os.Build
import android.os.Looper
import dev.ticbox.companion.protocol.*
import java.io.*
import java.nio.file.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*
import kotlin.system.exitProcess

/** Same bytecode runs as shell over ADB or root over the authenticated local bridge. */
object ToolboxAgentMain {
    @Suppress("DEPRECATION") // app_process has no Application to prepare the framework's main looper.
    @JvmStatic fun main(args: Array<String>) {
        val output = FileOutputStream(FileDescriptor.out)
        // Keep framework/library println from corrupting the framed stream.
        System.setOut(PrintStream(FileOutputStream(FileDescriptor.err)))
        val uid = android.os.Process.myUid()
        if (uid != 0 && uid != 2000) exitProcess(77)
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val environment = AgentEnvironment(uid, Build.VERSION.SDK_INT, args.firstOrNull() ?: "", "/system/bin/sh",
            transportHostPackage = if (args.getOrNull(1) == "--bridge-host") ToolboxProtocol.ROOT_HOST_PACKAGE else null)
        ToolboxAgentRuntime(AgentWire(FileInputStream(FileDescriptor.`in`), output), environment).run()
        exitProcess(0)
    }
}
internal class AgentWire(val input: InputStream, private val output: OutputStream) {
    @Synchronized fun send(type: Int, bytes: ByteArray = byteArrayOf()) = ToolboxProtocol.write(output, type, bytes)
    @Synchronized fun json(type: Int, value: JsonObject) = ToolboxProtocol.write(output, type, value)
    fun read() = ToolboxProtocol.required(input)
}
internal data class AgentEnvironment(val uid: Int, val sdk: Int, val scrcpy: String, val shell: String,
    val commands: PackageCommands = SystemPackageCommands, private val suppliedContext: Context? = null,
    val deviceId: String? = null, val transportHostPackage: String? = if (uid == 0) ToolboxProtocol.ROOT_HOST_PACKAGE else null,
    val moduleRoot: Path = Paths.get("/data/adb"), val moduleAbi: String? = null, val moduleRuntime: (() -> JsonObject)? = null) {
    val context: Context by lazy { suppliedContext ?: systemContext() }
    @SuppressLint("PrivateApi") // Only the standalone root/shell app_process calls this; there is no Application.
    private fun systemContext(): Context {
        val type = Class.forName("android.app.ActivityThread")
        val thread = type.getMethod("systemMain").invoke(null)
        return type.getMethod("getSystemContext").invoke(thread) as Context
    }
}
internal class ToolboxAgentRuntime(private val wire: AgentWire, private val environment: AgentEnvironment,
    private val installReplyTimeoutMillis: Long = 5_000) {
    fun run() {
        var installArgs: JsonObject? = null
        var installReplySent = false
        try {
            wire.json(ToolboxProtocol.READY, buildJsonObject {
                put("version", ToolboxProtocol.VERSION); put("uid", environment.uid); put("sdk", environment.sdk)
                put("deviceId", environment.deviceId ?: agentDeviceIdentity())
                putJsonArray("protectedPackages") { environment.transportHostPackage?.let { add(it) } }
                putJsonArray("features") {
                    listOf("INSTALL_APK", "INSTALL_SPLITS", "APPS", "CLIPBOARD", "COMMAND", "SCREEN", "FILES", "FILE_RESUME").forEach { add(it) }
                    if (PtyProcess.available()) add("TERMINAL")
                    add("SCREEN_KEYFRAME"); add("SCREEN_ENCODER_LIMITS"); add("SCREEN_CODEC_OPTIONS")
                    if (environment.uid == 2000) { add("SCREEN_UDP"); add("SCREEN_UDP_FEC"); add("SCREEN_UDP_AUTH_ONLY"); add("SCREEN_UDP_STARTUP") }
                    if (environment.uid == 0) listOf("PRIVATE_APP_DATA", "CLEAR_APP_CACHE", "FILE_OWNERSHIP", MagiskModuleProtocol.CAPABILITY, HealthMonitoringProtocol.CAPABILITY).forEach { add(it) }
                }
            })
            val request = wire.read().also { require(it.type == ToolboxProtocol.REQUEST) }.objectValue()
            val args = request["args"] as? JsonObject ?: buildJsonObject { }
            val files = AgentFiles(wire)
            when (request.requiredText("op")) {
                "probe" -> wire.json(ToolboxProtocol.RESULT, buildJsonObject { put("success", true) })
                "files" -> files.list(args.requiredText("path"))
                "stat" -> wire.json(ToolboxProtocol.RESULT, files.stat(args.requiredText("path")))
                "file" -> {
                    val cancelled = AtomicBoolean()
                    Thread({
                        try { wire.read() } catch (_: Exception) { }
                        finally { cancelled.set(true) }
                    }, "toolbox-file-cancellation").apply { isDaemon = true; start() }
                    AgentFiles(wire) { if (cancelled.get()) throw InterruptedIOException("operation_cancelled") }.change(args)
                }
                "pull" -> files.get(args.requiredText("path"), args.number("offset") ?: 0)
                "push" -> files.put(args)
                "file_snapshot" -> AgentFileTransfers(wire).snapshot(args)
                "pull_resume" -> AgentFileTransfers(wire).pull(args)
                "push_resume" -> AgentFileTransfers(wire).push(args)
                "discard_upload" -> AgentFileTransfers(wire).discard(args)
                "exec" -> AgentCommand(wire, environment).run(args.requiredText("command"), args.number("timeoutMillis") ?: 60_000)
                "terminal" -> AgentTerminal(wire, environment).run(args)
                "install" -> {
                    installArgs = args
                    AgentInstaller(wire, environment).install(args)
                    installReplySent = true
                }
                "apps" -> AgentApps(wire, environment).list(args.number("userId")?.toInt() ?: 0)
                "app" -> AgentApps(wire, environment).action(args)
                "backup" -> AgentApps(wire, environment).backup(args)
                "clipboard_get" -> AgentClipboard.handle(wire, environment, args, false)
                "clipboard_set" -> AgentClipboard.handle(wire, environment, args, true)
                "screen" -> AgentScreen(wire, environment).run(args)
                "screen_encoders" -> wire.json(ToolboxProtocol.RESULT,
                    AgentScreenEncoders.describe(environment, (args.number("maxSize") ?: 960).toInt().also { require(it in 64..4096) }))
                MagiskModuleProtocol.OPERATION -> AgentMagiskModule(wire, environment).handle(args)
                HealthMonitoringProtocol.OPERATION -> AgentMonitoring.handle(wire, environment, args)
                else -> throw IllegalArgumentException("unknown_operation")
            }
        } catch (error: Exception) {
            val code = when (error) {
                is ScreenRelayException -> error.code
                is FileTransferProblem -> error.reason
                is TerminalUnavailableException -> "terminal_unavailable"
                is TerminalStartException -> "terminal_start_failed"
                is ModuleProblem -> error.reason
                is MonitoringUnavailable -> error.reason
                is InstallResultUnknownException -> "install_result_unknown"
                is DeviceIdentityUnavailableException -> "device_identity_required"
                is TransportHostProtectedException -> "transport_host_protected"
                is InterruptedIOException -> "operation_cancelled"
                is AccessDeniedException, is SecurityException -> "permission_denied"
                is NoSuchFileException, is FileNotFoundException -> "not_found"
                is FileAlreadyExistsException -> "already_exists"
                is EOFException -> "connection_closed"
                is IllegalArgumentException -> "invalid_request"
                else -> "operation_failed"
            }
            runCatching { wire.json(ToolboxProtocol.ERROR, buildJsonObject { put("code", code); put("type", error.javaClass.simpleName) }) }
                .onSuccess { if (installArgs != null) installReplySent = true }
        } finally {
            // Installer cleanup/abandon has finished before waiting for the client receipt.
            if (installReplySent) installArgs?.let { wire.awaitInstallReceipt(it, installReplyTimeoutMillis) }
        }
    }
}
