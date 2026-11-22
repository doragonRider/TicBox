package dev.ticbox.companion.toolboxagent

import android.os.Build
import dev.ticbox.companion.protocol.*
import java.io.*
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import java.util.zip.ZipFile
import kotlinx.serialization.json.*

internal class ModuleProblem(val reason: String) : IOException(reason)

/** Fixed root-only payload, private staging, cross-process lock, no shell interpolation or reboot. */
internal class AgentMagiskModule(private val wire: AgentWire, private val environment: AgentEnvironment) {
    private val root get() = environment.moduleRoot
    private val installed get() = root.resolve("modules/${MagiskModuleProtocol.ID}")
    private val staged get() = root.resolve("modules_update/${MagiskModuleProtocol.ID}")
    private val abi get() = environment.moduleAbi ?: Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
    private fun refusal(reason: String): Nothing = throw ModuleProblem(reason)
    private fun safe(path: Path) {
        var current: Path? = path
        while (current != null) {
            if (Files.isSymbolicLink(current)) refusal("module_unsafe_path")
            current = current.parent
        }
    }
    fun handle(args: JsonObject) {
        if (environment.uid != 0) refusal("module_root_required")
        val catalog = MagiskModuleProtocol.decode(args["catalog"] as? JsonObject ?: refusal("module_catalog_invalid"))
        val action = args.requiredText("action"); require(action in setOf("status", "install"))
        safe(root)
        val work = root.resolve("ticbox-module-installer")
        Files.createDirectories(work, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        safe(work)
        Files.setPosixFilePermissions(work, PosixFilePermissions.fromString("rwx------"))
        val lockPath = work.resolve("install.lock"); safe(lockPath)
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val lock = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
                ?: refusal("module_busy")
            lock.use {
                if (action == "status") { result("READ", snapshot(catalog, work)); return }
                if (environment.sdk < 30 || abi !in setOf("armeabi-v7a", "arm64-v8a")) refusal("module_device_unsupported")
                val magisk = magiskVersion() ?: refusal("magisk_unavailable")
                if (magisk < 26000) refusal("magisk_too_old")
                // Magisk's installer removes disable/remove markers. Never silently re-enable a module.
                for (path in listOf(installed, staged)) {
                    safe(path)
                    if (Files.exists(path)) {
                        if (Files.exists(path.resolve("disable")) || Files.exists(path.resolve("remove"))) refusal("module_disabled_or_removing")
                        // Fresh boot-mode installs also create an active metadata-only placeholder.
                        val placeholder = path == installed && ours(staged) && runCatching {
                            properties(path.resolve("module.prop")).getProperty("id") == MagiskModuleProtocol.ID &&
                                Files.list(path).use { rows -> rows.allMatch { it.fileName.toString() in setOf("module.prop", "update") } }
                        }.getOrDefault(false)
                        if (!ours(path) && !placeholder) refusal("module_id_conflict")
                    }
                }
                val directory = Files.createTempDirectory(work, "upload-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                try {
                    wire.json(ToolboxProtocol.PROGRESS, buildJsonObject {
                        put("readyForUpload", true); put("completed", 0); put("total", catalog.bytes)
                    })
                    val zip = directory.resolve("module.zip")
                    Files.newOutputStream(zip, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                        var received = 0L
                        while (true) {
                            val frame = wire.read()
                            if (frame.type == ToolboxProtocol.END) break
                            require(frame.type == ToolboxProtocol.DATA && frame.bytes.isNotEmpty())
                            received += frame.bytes.size; require(received <= catalog.bytes)
                            output.write(frame.bytes)
                            wire.json(ToolboxProtocol.PROGRESS, buildJsonObject { put("completed", received); put("total", catalog.bytes) })
                        }
                        require(received == catalog.bytes)
                    }
                    verifyZip(zip, catalog)
                if (image(staged, catalog)?.flag("matchesBundled") == true) { result("STAGED_REBOOT_REQUIRED", snapshot(catalog, work)); return }
                if (!Files.exists(staged) && image(installed, catalog)?.flag("matchesBundled") == true) {
                    result("UP_TO_DATE", snapshot(catalog, work)); return
                }

                    // No further read of the Bluetooth pipe after acceptance. A lost caller cannot
                    // abort a half-installed module; the bridge drains output until this agent exits.
                    receipt(work, catalog, "UNCONFIRMED")
                    val command = try { environment.commands.run(listOf("magisk", "--install-module", zip.toString()), timeoutMillis = 180_000) }
                        catch (_: Exception) { refusal("module_install_unconfirmed") }
                    if (!command.completed) refusal("module_install_unconfirmed")
                    if (command.code != 0) {
                        receipt(work, catalog, "REJECTED")
                        refusal("magisk_install_failed")
                    }
                    if (image(staged, catalog)?.flag("matchesBundled") != true) refusal("module_install_unconfirmed")
                    receipt(work, catalog, "STAGED_REBOOT_REQUIRED")
                    result("STAGED_REBOOT_REQUIRED", snapshot(catalog, work))
                } finally { directory.toFile().deleteRecursively() }
            }
        }
    }
    private fun result(outcome: String, state: JsonObject) = wire.json(ToolboxProtocol.RESULT, buildJsonObject {
        put("outcome", outcome); put("snapshot", state)
    })
    private fun magiskVersion(): Int? = runCatching {
        environment.commands.run(listOf("magisk", "-V"), timeoutMillis = 10_000).takeIf { it.completed && it.code == 0 }?.output?.trim()?.toIntOrNull()
    }.getOrNull()
    private fun properties(path: Path): Properties {
        safe(path)
        require(Files.size(path) in 1..8192)
        return Properties().apply { Files.newInputStream(path).use { load(it) } }
    }
    private fun ours(path: Path): Boolean = runCatching {
        val prop = properties(path.resolve("module.prop"))
        if (prop.getProperty("id") != MagiskModuleProtocol.ID) return@runCatching false
        // Also recognizes our previously shipped ZIPs, before build metadata was introduced.
        val dex = path.resolve("module.dex"); safe(dex)
        Files.size(dex) in 112..MagiskModuleProtocol.MAX_EXPANDED_BYTES &&
            Files.readAllBytes(dex).toString(Charsets.ISO_8859_1).contains("Ldev/ticbox/companion/healthmodule/HealthModule;")
    }.getOrDefault(false)
    private fun image(path: Path, catalog: MagiskModuleProtocol.Catalog): JsonObject? {
        safe(path)
        if (!Files.exists(path)) return null
        val build = runCatching { properties(path.resolve("companion-build.properties")) }.getOrNull()
        val matches = build?.getProperty("id") == MagiskModuleProtocol.ID && build.getProperty("buildId") == catalog.buildId &&
            MagiskModuleProtocol.PAYLOADS.all { name -> runCatching {
                val file = path.resolve(name); safe(file)
                Files.newInputStream(file).use { MagiskModuleProtocol.sha256(it, MagiskModuleProtocol.MAX_EXPANDED_BYTES.toLong()) } == catalog.entries[name]
            }.getOrDefault(false) }
        return buildJsonObject {
            put("buildId", build?.getProperty("buildId")?.takeIf(MagiskModuleProtocol::digest)?.let(::JsonPrimitive) ?: JsonNull)
            put("versionName", build?.getProperty("versionName")?.take(80)?.let(::JsonPrimitive) ?: JsonNull)
            put("versionCode", build?.getProperty("versionCode")?.toLongOrNull()?.let(::JsonPrimitive) ?: JsonNull)
            put("matchesBundled", matches); put("disabled", Files.exists(path.resolve("disable"))); put("removalPending", Files.exists(path.resolve("remove")))
        }
    }
    private fun snapshot(catalog: MagiskModuleProtocol.Catalog, work: Path): JsonObject {
        val runtime = runCatching { environment.moduleRuntime?.invoke() ?: AgentModuleRuntime.read(environment.context) }.getOrElse {
            buildJsonObject { put("issue", "module_runtime_not_observed") }
        }
        return buildJsonObject {
            put("magiskVersionCode", magiskVersion()?.let(::JsonPrimitive) ?: JsonNull)
            put("sdk", environment.sdk); put("abi", abi)
            put("installed", image(installed, catalog) ?: JsonNull); put("staged", image(staged, catalog) ?: JsonNull)
            put("healthRuntimeBuildId", runtime.text("buildId")?.let(::JsonPrimitive) ?: JsonNull)
            put("runtimeIssue", runtime.text("issue")?.let(::JsonPrimitive) ?: JsonNull)
            val file = work.resolve("last-install.json"); safe(file)
            put("lastInstall", runCatching {
                require(Files.size(file) <= 1024)
                Json.parseToJsonElement(Files.newInputStream(file).use { it.readBytes().toString(Charsets.UTF_8) })
            }.getOrNull() ?: JsonNull)
        }
    }
    private fun receipt(work: Path, catalog: MagiskModuleProtocol.Catalog, outcome: String) {
        val bytes = buildJsonObject { put("buildId", catalog.buildId); put("outcome", outcome) }.toString().toByteArray()
        val temporary = work.resolve("receipt.tmp"); safe(temporary)
        FileOutputStream(temporary.toFile()).use { it.write(bytes); it.fd.sync() }
        Files.move(temporary, work.resolve("last-install.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    companion object {
        internal fun verifyZip(path: Path, catalog: MagiskModuleProtocol.Catalog) {
            fun invalid(): Nothing = throw ModuleProblem("module_payload_invalid")
            if (Files.size(path) != catalog.bytes || Files.newInputStream(path).use { MagiskModuleProtocol.sha256(it, MagiskModuleProtocol.MAX_ZIP_BYTES.toLong()) } != catalog.sha256) invalid()
            ZipFile(path.toFile()).use { zip ->
                val seen = mutableSetOf<String>(); var total = 0L
                for (entry in zip.entries()) {
                    if (entry.isDirectory) {
                        val directory = entry.name.removeSuffix("/")
                        if (directory.startsWith('/') || directory.split('/').any { it in setOf("", ".", "..") } ||
                            catalog.entries.keys.none { it.startsWith("$directory/") }) invalid()
                        continue
                    }
                    if (!seen.add(entry.name) || entry.name !in catalog.entries) invalid()
                    val body = zip.getInputStream(entry).use { input ->
                        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) { val count = input.read(buffer); if (count < 0) break
                            if (count == 0 || output.size() + count > MagiskModuleProtocol.MAX_EXPANDED_BYTES - total) invalid()
                            output.write(buffer, 0, count)
                        }; output.toByteArray()
                    }
                    total += body.size; if (total > MagiskModuleProtocol.MAX_EXPANDED_BYTES) invalid()
                    if (MagiskModuleProtocol.sha256(body.inputStream(), MagiskModuleProtocol.MAX_EXPANDED_BYTES.toLong()) != catalog.entries[entry.name]) invalid()
                    if (entry.name == "module.prop") {
                        val properties = Properties().apply { load(body.inputStream()) }
                        if (properties.getProperty("id") != MagiskModuleProtocol.ID || properties.getProperty("versionCode") != catalog.versionCode.toString()) invalid()
                    }
                    if (entry.name == "companion-build.properties") {
                        val properties = Properties().apply { load(body.inputStream()) }
                        if (properties.getProperty("id") != MagiskModuleProtocol.ID || properties.getProperty("buildId") != catalog.buildId) invalid()
                    }
                }
                if (seen != catalog.entries.keys || !seen.containsAll(setOf("module.prop", "customize.sh"))) invalid()
            }
        }
    }
}
