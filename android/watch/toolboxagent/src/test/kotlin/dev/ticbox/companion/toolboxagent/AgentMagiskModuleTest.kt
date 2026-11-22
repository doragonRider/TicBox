package dev.ticbox.companion.toolboxagent

import dev.ticbox.companion.api.*
import dev.ticbox.companion.protocol.*
import java.io.*
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class AgentMagiskModuleTest {
    private class Payload(val build: String = "a".repeat(64)) {
        val entries = linkedMapOf(
            "module.prop" to "id=${MagiskModuleProtocol.ID}\nversionCode=3\n".toByteArray(),
            "companion-build.properties" to "id=${MagiskModuleProtocol.ID}\nbuildId=$build\nversionName=0.3.0\nversionCode=3\n".toByteArray(),
            "module.dex" to ("dex\n" + "x".repeat(120) + "Ldev/ticbox/companion/healthmodule/HealthModule;").toByteArray(),
            "zygisk/armeabi-v7a.so" to byteArrayOf(1), "zygisk/arm64-v8a.so" to byteArrayOf(2),
            "customize.sh" to "exit 0".toByteArray(), "LICENSE" to "test fixture".toByteArray())
        val bytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip -> entries.forEach { (name, body) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(body); zip.closeEntry()
        } } }.toByteArray()
        val catalog = MagiskModuleProtocol.Catalog("0.3.0", 3, build, hash(bytes), bytes.size.toLong(), entries.mapValues { hash(it.value) })
        companion object { fun hash(bytes: ByteArray) = MagiskModuleProtocol.sha256(bytes.inputStream(), 8_000_000) }
    }
    private class Installer(val root: Path) : AgentBackendIntegrationTest.Packages() {
        val installs = AtomicInteger()
        var beforeInstall: (() -> Unit)? = null
        override fun run(command: List<String>, input: ((OutputStream) -> Unit)?, timeoutMillis: Long): CapturedCommand {
            if (command == listOf("magisk", "-V")) return CapturedCommand(0, "31001\n")
            if (command.take(2) == listOf("magisk", "--install-module")) {
                installs.incrementAndGet(); beforeInstall?.invoke()
                ZipFile(command[2]).use { zip -> for (entry in zip.entries()) {
                    val file = root.resolve("modules_update/${MagiskModuleProtocol.ID}/${entry.name}"); Files.createDirectories(file.parent)
                    zip.getInputStream(entry).use { Files.copy(it, file, StandardCopyOption.REPLACE_EXISTING) }
                } }
                val stub = root.resolve("modules/${MagiskModuleProtocol.ID}"); Files.createDirectories(stub)
                Files.copy(root.resolve("modules_update/${MagiskModuleProtocol.ID}/module.prop"), stub.resolve("module.prop"), StandardCopyOption.REPLACE_EXISTING)
                Files.writeString(stub.resolve("update"), "")
                return CapturedCommand(0, "Done")
            }
            error("unexpected command: $command")
        }
    }
    private suspend fun fixture(kind: ToolboxBackendKind = ToolboxBackendKind.ROOT_BRIDGE,
        block: suspend (AgentBackendIntegrationTest.Fixture, Installer, Path) -> Unit) = coroutineScope {
        val root = Files.createTempDirectory("owned-magisk-fixture")
        try {
            val commands = Installer(root)
            AgentBackendIntegrationTest.Fixture(kind, this, commands, environment = { it.copy(moduleRoot = root, moduleAbi = "arm64-v8a",
                moduleRuntime = { buildJsonObject { put("buildId", "b".repeat(64)) } }) }).use { f ->
                f.backend.connect(); block(f, commands, root)
            }
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun stagedPayloadIsVerifiedAndIsNotConfusedWithOlderRunningCode() = runBlocking { fixture { f, commands, root ->
        val p = Payload()
        val progress = mutableListOf<ToolboxProgress>()
        val result = f.backend.healthModule(p.catalog, p.bytes::inputStream, progress::add) {}
        assertEquals("STAGED_REBOOT_REQUIRED", result.text("outcome"))
        assertEquals(1, commands.installs.get()); assertEquals(p.bytes.size.toLong(), progress.last().completedBytes)
        val status = f.backend.healthModule(p.catalog, null, {}, {}).getValue("snapshot").jsonObject
        assertFalse(status["installed"]!!.jsonObject.flag("matchesBundled") == true); assertTrue(status["staged"]!!.jsonObject.flag("matchesBundled") == true)
        assertEquals("STAGED_REBOOT_REQUIRED", f.backend.healthModule(p.catalog, p.bytes::inputStream, {}, {}).text("outcome"))
        assertEquals(1, commands.installs.get())
        assertEquals("b".repeat(64), status.text("healthRuntimeBuildId"))
        // Simulate Magisk's boot activation; module.prop alone would be insufficient proof.
        val staged = root.resolve("modules_update/${MagiskModuleProtocol.ID}")
        val active = root.resolve("modules/${MagiskModuleProtocol.ID}"); active.toFile().deleteRecursively(); Files.move(staged, active)
        val afterBoot = f.backend.healthModule(p.catalog, null, {}, {}).getValue("snapshot").jsonObject
        assertEquals(JsonNull, afterBoot["staged"]); assertTrue(afterBoot["installed"]!!.jsonObject.flag("matchesBundled") == true)
        val noOp = f.backend.healthModule(p.catalog, p.bytes::inputStream, {}, {})
        assertEquals("UP_TO_DATE", noOp.text("outcome")); assertEquals(1, commands.installs.get())
        Files.writeString(active.resolve("disable"), "")
        assertEquals("module_disabled_or_removing", assertFailsWith<ToolboxException> { f.backend.healthModule(p.catalog, p.bytes::inputStream, {}, {}) }.code)
        assertEquals(1, commands.installs.get())
    } }
    @Test fun turningTheNotificationControlOffSurvivesEveryInstall() = runBlocking { fixture { f, commands, root ->
        val staged = root.resolve("modules_update/${MagiskModuleProtocol.ID}")
        val active = root.resolve("modules/${MagiskModuleProtocol.ID}")
        // As Magisk does, an install starts the staged copy afresh.
        commands.beforeInstall = { staged.toFile().deleteRecursively() }
        suspend fun install(build: Char) = Payload(build.toString().repeat(64)).let { p ->
            assertEquals("STAGED_REBOOT_REQUIRED", f.backend.healthModule(p.catalog, p.bytes::inputStream, {}, {}).text("outcome"))
        }
        install('c')
        assertFalse(Files.exists(staged.resolve(AgentMagiskModule.ALERTS_OFF), LinkOption.NOFOLLOW_LINKS)) // On unless turned off.
        // Turned off while that update waits for its restart, so only the staged copy says so.
        Files.writeString(staged.resolve(AgentMagiskModule.ALERTS_OFF), "")
        install('d')
        val kept = staged.resolve(AgentMagiskModule.ALERTS_OFF)
        assertTrue(Files.isRegularFile(kept, LinkOption.NOFOLLOW_LINKS))
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(kept))
        // After the restart it is in the active copy, and the next update keeps it as well.
        active.toFile().deleteRecursively(); Files.move(staged, active)
        install('e')
        assertTrue(Files.isRegularFile(staged.resolve(AgentMagiskModule.ALERTS_OFF), LinkOption.NOFOLLOW_LINKS))
        assertEquals(3, commands.installs.get())
    } }
    @Test fun corruptPayloadNeverReachesMagiskAndUploadIsCleaned() = runBlocking { fixture { f, commands, root ->
        val p = Payload(); val corrupt = p.bytes.clone().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertEquals("module_payload_invalid", assertFailsWith<ToolboxException> { f.backend.healthModule(p.catalog, corrupt::inputStream, {}, {}) }.code)
        assertEquals(0, commands.installs.get())
        assertTrue(Files.list(root.resolve("ticbox-module-installer")).use { it.noneMatch { path -> path.fileName.toString().startsWith("upload-") } })
    } }
    @Test fun rootCapabilityIsRequiredEvenWhenAnAdbAgentHasOtherTools() = runBlocking { fixture(ToolboxBackendKind.ADB) { f, commands, _ ->
        val p = Payload()
        assertEquals("module_root_required", assertFailsWith<ToolboxException> { f.backend.healthModule(p.catalog, null, {}, {}) }.code)
        assertEquals(0, commands.installs.get())
    } }
    @Test fun sourceFailureBeforeEndCannotSubmitAnInstallOrHangTheReader() = runBlocking { fixture { f, commands, root ->
        val p = Payload(); var submitted = false
        withTimeout(5000) {
            assertFailsWith<IOException> { f.backend.healthModule(p.catalog, { ByteArrayInputStream(byteArrayOf(1)) }, {}, { submitted = true }) }
        }
        assertFalse(submitted); assertEquals(0, commands.installs.get())
        withTimeout(5000) { while (Files.list(root.resolve("ticbox-module-installer")).use { rows -> rows.anyMatch { it.fileName.toString().startsWith("upload-") } }) delay(10) }
    } }
    @Test fun runtimeCannotClaimAnAnalyzedProfileAfterTheInstalledOemApkChanged() {
        val version = HealthMonitoringProtocol.ANALYZED_APK_SHA256.keys.first()
        val hash = HealthMonitoringProtocol.ANALYZED_APK_SHA256.getValue(version)
        val response = buildJsonObject {
            put("version", 1); put("packageName", HealthMonitoringProtocol.PACKAGE); put("versionCode", version)
            put("apkSha256", hash); put("buildId", "a".repeat(64))
        }
        assertEquals("a".repeat(64), AgentModuleRuntime.validate(response, version, hash).text("buildId"))
        assertFails { AgentModuleRuntime.validate(response, version, "b".repeat(64)) }
        assertFails { AgentModuleRuntime.validate(response, version + 1, hash) }
    }
    @Test fun acceptedInstallFinishesAfterCallerDisconnectAndLockRejectsConcurrentInstall() = runBlocking { fixture { f, commands, root ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
        commands.beforeInstall = { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
        val p = Payload()
        val operation = async(Dispatchers.IO) { try { f.backend.healthModule(p.catalog, p.bytes::inputStream, {}, {}) } finally { finished.countDown() } }
        assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
        assertEquals("module_busy", assertFailsWith<ToolboxException> { f.backend.healthModule(p.catalog, null, {}, {}) }.code)
        operation.cancelAndJoin(); release.countDown()
        withTimeout(10_000) { while (!Files.exists(root.resolve("modules_update/${MagiskModuleProtocol.ID}/module.dex"))) delay(10) }
        withTimeout(10_000) {
            while (Files.readString(root.resolve("ticbox-module-installer/last-install.json")).contains("UNCONFIRMED")) delay(10)
        }
        assertEquals(1, commands.installs.get()); assertEquals(0L, finished.count)
    } }
    @Test fun zipCatalogRejectsTraversalExtraEntriesAndTampering() {
        val p = Payload()
        assertFails { MagiskModuleProtocol.decode(p.catalog.copy(entries = p.catalog.entries + ("../escape" to "a".repeat(64))).json()) }
        val file = Files.createTempFile("owned-module", ".zip")
        try {
            Files.write(file, p.bytes); AgentMagiskModule.verifyZip(file, p.catalog)
            assertFails { AgentMagiskModule.verifyZip(file, p.catalog.copy(entries = p.catalog.entries + ("extra" to "b".repeat(64)))) }
            assertFails { AgentMagiskModule.verifyZip(file, p.catalog.copy(entries = p.catalog.entries + ("module.dex" to "c".repeat(64)))) }
        } finally { Files.delete(file) }
    }
}
