package dev.ticbox.companion.toolboxagent

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import dev.ticbox.companion.protocol.*
import dev.ticbox.companion.api.ToolboxBackendKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.*
import java.nio.file.Files
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlinx.serialization.json.*
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class RootAppManagementTest {
    @Test fun currentRootTransportHostCannotBeStoppedClearedDisabledUninstalledOrBackedUp() {
        val operations = mutableListOf<List<String>>()
        val runner = object : PackageCommands {
            override fun run(command: List<String>, input: ((OutputStream) -> Unit)?, timeoutMillis: Long): CapturedCommand {
                operations += command; error("Protected host must be rejected before any command")
            }
        }
        val environment = AgentEnvironment(0, 33, "", "/bin/sh", runner, RuntimeEnvironment.getApplication())
        val apps = AgentApps(AgentWire(byteArrayOf().inputStream(), ByteArrayOutputStream()), environment)
        for (action in listOf("FORCE_STOP", "CLEAR_DATA", "CLEAR_CACHE", "DISABLE", "UNINSTALL", "SUSPEND")) {
            assertFailsWith<TransportHostProtectedException> { apps.action(buildJsonObject { put("packageName", ToolboxProtocol.ROOT_HOST_PACKAGE); put("action", action) }) }
        }
        assertFailsWith<TransportHostProtectedException> { apps.backup(buildJsonObject { put("packageName", ToolboxProtocol.ROOT_HOST_PACKAGE) }) }
        assertTrue(operations.isEmpty())
    }
    @Test fun independentRootAdbCanClearBridgeCacheAndPreservesRealPrivateData() {
        val root = Files.createTempDirectory("private-app-")
        try {
            val credential = Files.createDirectory(root.resolve("ce")); val device = Files.createDirectory(root.resolve("de"))
            for (dir in listOf(credential, device)) {
                Files.createDirectory(dir.resolve("cache")); Files.writeString(dir.resolve("cache/discard"), "cache")
                Files.createDirectory(dir.resolve("code_cache")); Files.writeString(dir.resolve("code_cache/discard"), "code")
                Files.createDirectory(dir.resolve("databases")); Files.writeString(dir.resolve("databases/keep"), "private data")
            }
            val context = RuntimeEnvironment.getApplication()
            val app = ApplicationInfo().apply { packageName = ToolboxProtocol.ROOT_HOST_PACKAGE; dataDir = credential.toString(); deviceProtectedDataDir = device.toString(); sourceDir = "/data/app/test/base.apk" }
            shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = app.packageName; applicationInfo = app })
            val commands = mutableListOf<List<String>>()
            val runner = object : PackageCommands {
                override fun run(command: List<String>, input: ((OutputStream) -> Unit)?, timeoutMillis: Long): CapturedCommand {
                    commands += command; return CapturedCommand(0, "")
                }
            }
            val output = ByteArrayOutputStream()
            AgentApps(AgentWire(byteArrayOf().inputStream(), output), AgentEnvironment(0, 33, "", "/bin/sh", runner, context, transportHostPackage = null)).action(buildJsonObject {
                put("packageName", app.packageName); put("action", "CLEAR_CACHE")
            })
            assertEquals(listOf("am", "force-stop", "--user", "0", app.packageName), commands.single())
            for (dir in listOf(credential, device)) {
                assertEquals("private data", Files.readString(dir.resolve("databases/keep")))
                assertEquals(0, dir.resolve("cache").toFile().list()!!.size)
                assertEquals(0, dir.resolve("code_cache").toFile().list()!!.size)
            }
            assertEquals(0, ToolboxProtocol.required(output.toByteArray().inputStream()).objectValue().number("code"))
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun packageListUsesActualPackageManagerIncludingDisabledAndSplitApps() {
        val context = RuntimeEnvironment.getApplication()
        val app = ApplicationInfo().apply { packageName = "test.splits"; sourceDir = "/data/app/base.apk"; splitSourceDirs = arrayOf("/data/app/split.apk"); enabled = false; uid = 10088; dataDir = "/data/user/0/test.splits" }
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = app.packageName; applicationInfo = app; versionName = "2.3" })
        val output = ByteArrayOutputStream()
        AgentApps(AgentWire(byteArrayOf().inputStream(), output), AgentEnvironment(0, 33, "", "/bin/sh", suppliedContext = context)).list(0)
        val input = output.toByteArray().inputStream(); val entries = mutableListOf<JsonObject>()
        while (true) { val packet = ToolboxProtocol.required(input); if (packet.type == ToolboxProtocol.END) break; entries += packet.objectValue() }
        val entry = entries.single { it.text("packageName") == "test.splits" }
        assertEquals(10088, entry.number("uid")); assertEquals(false, entry.flag("enabled"))
        assertEquals(2, entry["apkPaths"]!!.jsonArray.size)
    }
    @Test fun rootBackupStreamsCredentialAndDeviceProtectedDataAsAnActualTarArchive() = runBlocking { withTimeout(10_000) {
        val root = Files.createTempDirectory("private-backup-")
        try {
            val ce = Files.createDirectory(root.resolve("ce")); val de = Files.createDirectory(root.resolve("de"))
            Files.writeString(ce.resolve("private.db"), "credential protected"); Files.writeString(de.resolve("direct.boot"), "device protected")
            val context = RuntimeEnvironment.getApplication()
            val info = ApplicationInfo().apply { packageName = "test.backup"; dataDir = ce.toString(); deviceProtectedDataDir = de.toString(); sourceDir = "/data/app/base.apk" }
            shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = info.packageName; applicationInfo = info })
            AgentBackendIntegrationTest.Fixture(ToolboxBackendKind.ROOT_BRIDGE, this, suppliedContext = context).use { f ->
                f.backend.connect(); val archive = ByteArrayOutputStream()
                f.backend.backupAppData(info.packageName, archive)
                val file = root.resolve("backup.tar"); Files.write(file, archive.toByteArray())
                val listing = ProcessBuilder("tar", "-tf", file.toString()).start()
                val names = listing.inputStream.reader().readText()
                assertEquals(0, listing.waitFor()); assertTrue(names.contains("ce/private.db")); assertTrue(names.contains("de/direct.boot"))
                assertEquals(listOf("am", "force-stop", "--user", "0", "test.backup"), f.packages.operations.single())
            }
        } finally { root.toFile().deleteRecursively() }
    } }
}
