package dev.ticbox.companion.ui.toolbox

import android.os.Looper
import dev.ticbox.companion.api.AdbToolboxClient
import dev.ticbox.companion.api.ToolboxApk
import dev.ticbox.companion.api.ToolboxApp
import dev.ticbox.companion.api.ToolboxAppAction
import dev.ticbox.companion.api.ToolboxAppRequest
import dev.ticbox.companion.api.ToolboxBackendKind
import dev.ticbox.companion.api.ToolboxBackendState
import dev.ticbox.companion.api.ToolboxCapabilities
import dev.ticbox.companion.api.ToolboxCapability
import dev.ticbox.companion.api.ToolboxCommandResult
import dev.ticbox.companion.api.ToolboxException
import dev.ticbox.companion.api.ToolboxFile
import dev.ticbox.companion.api.ToolboxFileKind
import dev.ticbox.companion.api.ToolboxFileRequest
import dev.ticbox.companion.api.ToolboxInstallOptions
import dev.ticbox.companion.api.ToolboxInstallResult
import dev.ticbox.companion.api.ToolboxPhase
import dev.ticbox.companion.api.ToolboxProgress
import dev.ticbox.companion.api.ToolboxTarget
import dev.ticbox.companion.api.WatchToolboxBackend
import dev.ticbox.companion.api.WatchToolboxClient
import dev.ticbox.companion.ui.FakeSessions
import dev.ticbox.companion.ui.TestDependencies
import dev.ticbox.companion.ui.bridge.BridgeInstallState
import dev.ticbox.companion.ui.bridge.LOCAL_BRIDGE_PACKAGE
import dev.ticbox.companion.ui.bridge.LocalBridgeViewModel
import dev.ticbox.companion.ui.demoWatch
import dev.ticbox.companion.ui.session
import dev.ticbox.companion.ui.session.SessionState
import java.io.IOException
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Asking the watch takes seconds, so lists are kept, and mustn't outlast what they describe. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ToolboxMemoryTest {
    private class Watch(kind: ToolboxBackendKind, connected: Boolean = true) :
        AdbToolboxClient by WatchToolboxClient.UNAVAILABLE.adb {
        override fun bind(target: ToolboxTarget): WatchToolboxBackend =
            super<AdbToolboxClient>.bind(target)

        override val state =
            MutableStateFlow(
                ToolboxBackendState(
                    kind,
                    if (connected) ToolboxPhase.CONNECTED else ToolboxPhase.DISCONNECTED,
                    kind.name,
                    ToolboxCapabilities(0, 33, ToolboxCapability.entries.toSet()),
                    target = if (connected) ToolboxTarget("watch", 1, kind) else null,
                )
            )

        var apps = listOf(app("com.sample"))
        var appLists = 0
        var actionExitCode = 0
        val folders = HashMap<String, List<ToolboxFile>>()
        val listed = mutableListOf<String>()
        var fileFailure: ToolboxException? = null
        val fileCalls = mutableListOf<ToolboxFileRequest>()

        override suspend fun apps(userId: Int): List<ToolboxApp> {
            appLists++
            return apps
        }

        override suspend fun app(request: ToolboxAppRequest) =
            ToolboxCommandResult(actionExitCode, byteArrayOf(), byteArrayOf())

        override suspend fun files(path: String): List<ToolboxFile> {
            listed += path
            return folders[path] ?: throw ToolboxException("not_found")
        }

        override suspend fun file(request: ToolboxFileRequest) {
            fileCalls += request
            fileFailure?.let { throw it }
        }

        var update: suspend () -> ToolboxInstallResult = { ToolboxInstallResult(true, "Success") }
        var updates = 0
        var connects = 0
        var onConnect: () -> Unit = {}
        var installs = 0

        override suspend fun updateHost(
            apk: ToolboxApk,
            progress: (ToolboxProgress) -> Unit,
        ): ToolboxInstallResult {
            updates++
            return update()
        }

        override suspend fun connect() {
            connects++
            onConnect()
        }

        override suspend fun install(
            apks: List<ToolboxApk>,
            options: ToolboxInstallOptions,
            progress: (ToolboxProgress) -> Unit,
        ): ToolboxInstallResult {
            installs++
            return ToolboxInstallResult(true, "Success")
        }

        fun reconnects(id: Long = 2) {
            state.value =
                state.value.copy(
                    phase = ToolboxPhase.CONNECTED,
                    error = null,
                    target = ToolboxTarget("watch", id, state.value.kind),
                )
        }
    }

    private class Fixture(adbConnected: Boolean = false) : AutoCloseable {
        val context = RuntimeEnvironment.getApplication()
        val adb = Watch(ToolboxBackendKind.ADB, adbConnected)
        val root = Watch(ToolboxBackendKind.ROOT_BRIDGE)
        val client =
            object : WatchToolboxClient {
                override val adb = this@Fixture.adb
                override val localBridge = root
            }
        val dependencies =
            TestDependencies(
                FakeSessions(SessionState.Ready(demoWatch(context).session(toolbox = client)))
            )
        val toolbox = ToolboxViewModel(dependencies)
        val documents =
            ToolboxDocuments(
                context.contentResolver,
                java.nio.file.Files.createTempDirectory("transfer-test-").toFile(),
            )
        val memory = toolboxMemory(client)

        fun appManager() = AppManagerViewModel(dependencies, toolbox, documents)

        fun fileManager() = FileManagerViewModel(dependencies, toolbox, documents)

        fun bridge() =
            LocalBridgeViewModel(dependencies, PHONE_VERSION, updateCheckPauseMillis = 1) {
                ToolboxApk("bridge.apk", 1) { byteArrayOf(1).inputStream() }
            }

        override fun close() = dependencies.applicationScope.cancel()
    }

    private fun awaitUi(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(5)
        }
        assertTrue(condition(), "The page didn't get there")
    }

    private fun Watch.outdated() {
        state.value =
            ToolboxBackendState(
                ToolboxBackendKind.ROOT_BRIDGE,
                ToolboxPhase.FAILED,
                error = "agent_version_mismatch",
            )
    }

    @Test
    fun theAppListIsAskedForOncePerConnection() {
        Fixture().use { f ->
            val first = f.appManager()
            first.show()
            awaitUi { first.state.value.apps != null }
            assertEquals(1, f.root.appLists)

            val reopened = f.appManager()
            reopened.show()
            assertEquals("com.sample", reopened.state.value.apps?.single()?.packageName)
            assertEquals(1, f.root.appLists)

            f.root.apps = listOf(app("com.newer"))
            reopened.refresh()
            awaitUi { reopened.state.value.apps?.single()?.packageName == "com.newer" }
            assertEquals(2, f.root.appLists)

            f.root.reconnects()
            val afterReconnect = f.appManager()
            afterReconnect.show()
            awaitUi { afterReconnect.state.value.apps != null }
            assertEquals(3, f.root.appLists)
            assertEquals(
                f.root.state.value.target,
                afterReconnect.state.value.apps?.single()?.target,
            )
        }
    }

    @Test
    fun aRefreshThatFailsKeepsTheListOnThePage() {
        Fixture().use { f ->
            val page = f.appManager()
            page.show()
            awaitUi { page.state.value.apps != null }
            f.root.state.value = f.root.state.value.copy(phase = ToolboxPhase.DISCONNECTED)
            page.refresh()
            awaitUi { !page.state.value.loading }
            assertNotNull(page.state.value.apps)
        }
    }

    @Test
    fun changingAnAppReadsTheListAgainWhileStartingOneDoesnt() {
        Fixture().use { f ->
            val page = f.appManager()
            page.show()
            awaitUi { page.state.value.apps != null }
            val app = page.state.value.apps!!.single()

            page.act(app, ToolboxAppAction.START)
            awaitUi { page.pending.value.isEmpty() && !page.state.value.loading }
            assertEquals(1, f.root.appLists)

            page.act(app, ToolboxAppAction.FORCE_STOP)
            awaitUi { f.root.appLists == 2 && page.pending.value.isEmpty() }

            // It may have failed because the list was out of date.
            f.root.actionExitCode = 1
            page.act(page.state.value.apps!!.single(), ToolboxAppAction.START)
            awaitUi { f.root.appLists == 3 && page.pending.value.isEmpty() }
        }
    }

    @Test
    fun goingBackToAFolderDoesntAskTheWatchUntilRefreshed() {
        Fixture().use { f ->
            f.root.folders["/a"] = listOf(folder("/a/b"))
            f.root.folders["/a/b"] = listOf(file("/a/b/c"))
            val page = f.fileManager()
            page.open("/a")
            awaitUi { page.state.value.files != null }
            page.open("/a/b")
            awaitUi { page.state.value.files?.singleOrNull()?.name == "c" }

            assertTrue(page.goUp())
            assertEquals("/a", page.state.value.path)
            assertEquals("b", page.state.value.files?.single()?.name)
            assertEquals(listOf("/a", "/a/b"), f.root.listed)

            page.refresh()
            awaitUi { f.root.listed.size == 3 && !page.state.value.loading }
            assertEquals("/a", f.root.listed.last())
        }
    }

    @Test
    fun actingOnAFileThatsAlreadyGoneShowsTheFolderAsItIsNow() {
        Fixture().use { f ->
            f.root.folders["/a"] = listOf(file("/a/gone"), file("/a/here"))
            val page = f.fileManager()
            page.open("/a")
            awaitUi { page.state.value.files?.size == 2 }
            val gone = page.state.value.files!!.first { it.name == "gone" }

            f.root.folders["/a"] = listOf(file("/a/here"))
            f.root.fileFailure = ToolboxException("not_found")
            page.delete(gone)
            awaitUi { page.state.value.files?.singleOrNull()?.name == "here" }
            assertEquals(listOf("/a", "/a"), f.root.listed)
            assertEquals("here", f.memory.files(f.root.state.value.target!!, "/a")?.single()?.name)
        }
    }

    @Test
    fun aFolderThatVanishedIsForgottenWithTheListThatNamedIt() {
        Fixture().use { f ->
            f.root.folders["/a"] = listOf(folder("/a/gone"))
            val page = f.fileManager()
            page.open("/a")
            awaitUi { page.state.value.files != null }

            page.open("/a/gone")
            awaitUi { page.state.value.problem != null }
            assertNull(page.state.value.files)
            assertNull(f.memory.files(f.root.state.value.target!!, "/a"))

            f.root.folders["/a"] = listOf(file("/a/here"))
            page.open("/a")
            awaitUi { page.state.value.files?.singleOrNull()?.name == "here" }
        }
    }

    @Test
    fun forgettingAPathDropsItsFolderItsContentsAndTheFolderListingItOnly() {
        Fixture().use { f ->
            val target = f.root.state.value.target!!
            listOf("/a", "/a/b", "/a/b/c", "/a/bb", "/z").forEach {
                f.memory.rememberFiles(target, it, emptyList())
            }
            f.memory.forget("/a/b")
            assertNull(f.memory.files(target, "/a"))
            assertNull(f.memory.files(target, "/a/b"))
            assertNull(f.memory.files(target, "/a/b/c"))
            assertNotNull(f.memory.files(target, "/a/bb"))
            assertNotNull(f.memory.files(target, "/z"))
        }
    }

    @Test
    fun anOutdatedRootBridgeUpdatesAndIsConfirmedOnceItAnswersAtTheNewVersion() {
        Fixture().use { f ->
            f.root.apps = listOf(bridgeApp(PHONE_VERSION))
            f.root.outdated()
            // The bridge is replaced underneath the transfer, so its answer never arrives.
            f.root.update = { throw ToolboxException("install_result_unknown") }
            var failures = 1
            f.root.onConnect = {
                if (failures-- > 0) throw IOException("still restarting")
                f.root.reconnects()
            }
            val page = f.bridge()
            awaitUi { page.state.value?.installsOverRoot == true }

            page.install()
            awaitUi { page.install.value == BridgeInstallState.Updated }
            assertEquals(1, f.root.updates)
            assertEquals(2, f.root.connects)
            assertEquals(0, f.adb.installs)
            assertEquals(PHONE_VERSION, f.memory.bridgeOnWatch.value?.versionCode)
            assertNotNull(f.memory.apps(f.root.state.value.target!!))
        }
    }

    @Test
    fun aBridgeAnsweringAtAnotherVersionIsNotCalledUpdated() {
        Fixture().use { f ->
            f.root.apps = listOf(bridgeApp(PHONE_VERSION - 1))
            f.root.outdated()
            f.root.onConnect = { f.root.reconnects() }
            val page = f.bridge()
            awaitUi { page.state.value?.installsOverRoot == true }
            page.install()
            awaitUi { page.install.value == BridgeInstallState.Unknown }
            assertTrue(f.root.connects > 1)
        }
    }

    @Test
    fun reinstallingTheSameVersionOverRootNeedsANewInstallPathToCount() {
        Fixture().use { f ->
            f.root.apps = listOf(bridgeApp(PHONE_VERSION, "/data/app/old/base.apk"))
            f.root.onConnect = { f.root.reconnects(f.root.connects + 1L) }
            val page = f.bridge()
            awaitUi { page.state.value?.rootConnected == true }
            page.readInstalled()
            awaitUi { page.installed.value != null }

            page.install()
            awaitUi { page.install.value == BridgeInstallState.Unknown }

            f.root.apps = listOf(bridgeApp(PHONE_VERSION, "/data/app/new/base.apk"))
            page.install()
            awaitUi { page.install.value == BridgeInstallState.Updated }
        }
    }

    @Test
    fun aRefusedUpdateIsShownWithoutWaitingForARestart() {
        Fixture().use { f ->
            f.root.outdated()
            f.root.update = { ToolboxInstallResult(false, "INSTALL_FAILED_UPDATE_INCOMPATIBLE") }
            val page = f.bridge()
            awaitUi { page.state.value?.installsOverRoot == true }
            page.install()
            awaitUi { page.install.value is BridgeInstallState.Refused }
            assertEquals(
                "INSTALL_FAILED_UPDATE_INCOMPATIBLE",
                (page.install.value as BridgeInstallState.Refused).output,
            )
            assertEquals(0, f.root.connects)
        }
    }

    @Test
    fun anUpdateCutOffPartWayIsAFailureNotARestart() {
        Fixture().use { f ->
            f.root.outdated()
            f.root.update = { throw ToolboxException("bridge_update_interrupted") }
            val page = f.bridge()
            awaitUi { page.state.value?.installsOverRoot == true }
            page.install()
            awaitUi { page.install.value is BridgeInstallState.Failed }
            assertEquals(0, f.root.connects)
        }
    }

    @Test
    fun anUpdateRunningIsStillThereOnReturnAndCantBeStartedTwice() {
        Fixture().use { f ->
            f.root.outdated()
            val answer = CompletableDeferred<ToolboxInstallResult>()
            f.root.update = { answer.await() }
            val page = f.bridge()
            awaitUi { page.state.value?.installsOverRoot == true }
            page.install()
            awaitUi { f.root.updates == 1 }

            val returned = f.bridge()
            assertTrue(returned.install.value is BridgeInstallState.Running)
            returned.install()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, f.root.updates)
            answer.complete(ToolboxInstallResult(false, "no"))
            awaitUi { returned.install.value is BridgeInstallState.Refused }

            assertEquals(BridgeInstallState.Idle, f.bridge().install.value)
        }
    }

    @Test
    fun wirelessDebuggingInstallsWhenBothAreUpAndTheListsAreReadAgain() {
        Fixture(adbConnected = true).use { f ->
            val target = f.adb.state.value.target!!
            f.memory.rememberApps(target, listOf(bridgeApp(1)))
            f.adb.apps = listOf(bridgeApp(PHONE_VERSION))
            val page = f.bridge()
            assertFalse(page.state.value!!.installsOverRoot)
            page.install()
            awaitUi { page.pending.value.isEmpty() && page.installed.value != null }
            assertEquals(BridgeInstallState.Installed, page.install.value)
            assertEquals(1, f.adb.installs)
            assertEquals(0, f.root.updates)
            assertEquals(PHONE_VERSION, f.memory.apps(target)?.single()?.versionCode)
        }
    }

    private companion object {
        const val PHONE_VERSION = 7L

        fun app(name: String, version: Long = 1, apk: String = "/data/app/$name/base.apk") =
            ToolboxApp(
                name,
                name,
                null,
                version,
                uid = 10_000,
                enabled = true,
                system = false,
                apkPaths = listOf(apk),
                dataDirectory = null,
            )

        fun bridgeApp(version: Long, apk: String = "/data/app/bridge/base.apk") =
            app(LOCAL_BRIDGE_PACKAGE, version, apk)

        fun entry(path: String, kind: ToolboxFileKind) =
            ToolboxFile(path, path.substringAfterLast('/'), kind, 0, 0, 0, 0, 0)

        fun file(path: String) = entry(path, ToolboxFileKind.FILE)

        fun folder(path: String) = entry(path, ToolboxFileKind.DIRECTORY)
    }
}
