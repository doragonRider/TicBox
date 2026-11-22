package dev.ticbox.companion.engine.module

import dev.ticbox.companion.api.*
import java.io.IOException
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class WatchModuleHostEngineTest {
    private val link = HealthModuleLink(PeerAddress("00:11:22:33:44:55"), 1)
    private val running = "zygisk value=1\ndeny 0\nhealth_pid 1234\nhealth_version versionCode=50603234\nsysui_version versionCode=150\nsdk 33\n"
    private fun result(stdout: String = "", exit: Int = 0) = ToolboxCommandResult(exit, stdout.toByteArray(), ByteArray(0))

    @Test fun theStatusSaysWhatMagiskAndThePackagesSay() {
        val on = parseModuleHostStatus(running)
        assertEquals(WatchModuleHostSnapshot(zygiskOn = true, healthOnDenyList = false, healthSupported = true, healthRunning = true, systemUiSupported = true), on)
        assertEquals(false, parseModuleHostStatus("zygisk value=0\n").zygiskOn)
        assertEquals(null, parseModuleHostStatus("zygisk unknown\n").zygiskOn)
        assertTrue(parseModuleHostStatus("zygisk value=1\ndeny 1\n").healthOnDenyList)
        assertFalse(parseModuleHostStatus("zygisk value=1\nhealth_pid\n").healthRunning)
        assertEquals(false, parseModuleHostStatus("zygisk value=1\nhealth_version versionCode=1\n").healthSupported)
        assertEquals(null, parseModuleHostStatus("zygisk value=1\nhealth_version\n").healthSupported)
        assertEquals(false, parseModuleHostStatus("zygisk value=1\nsysui_version versionCode=151\nsdk 33\n").systemUiSupported)
        assertEquals(false, parseModuleHostStatus("zygisk value=1\nsysui_version versionCode=150\nsdk 35\n").systemUiSupported)
        assertFailsWith<ToolboxException> { parseModuleHostStatus("") }
    }

    @Test fun turningOnZygiskWritesOnceAndThenReads() = runTest {
        val commands = mutableListOf<String>()
        val engine = WatchModuleHostEngine { _, command, _ -> commands += command; result(running) }
        engine.attach(link)
        assertEquals(0, commands.size)
        engine.turnOnZygisk()
        assertEquals(2, commands.size)
        assertTrue(commands[0].contains("REPLACE INTO settings") && commands[0].contains("'zygisk',1"))
        assertTrue(commands[1].contains("SELECT value FROM settings"))
        assertEquals(true, engine.state.value.snapshot?.zygiskOn)
        assertNull(engine.state.value.reason)
    }

    @Test fun aLostWriteIsReportedAndNeverRepeated() = runTest {
        val commands = mutableListOf<String>()
        val engine = WatchModuleHostEngine { _, command, _ ->
            commands += command
            if (command.contains("REPLACE INTO")) throw IOException("gone")
            result("zygisk value=0\n")
        }
        engine.attach(link)
        engine.turnOnZygisk()
        assertEquals(1, commands.count { it.contains("REPLACE INTO") })
        assertEquals("module_zygisk_failed", engine.state.value.reason)
        assertEquals(false, engine.state.value.snapshot?.zygiskOn)
    }

    @Test fun aRestartAnswersBeforeTheWatchGoesDown() = runTest {
        val commands = mutableListOf<String>()
        val engine = WatchModuleHostEngine { _, command, _ -> commands += command; result() }
        engine.attach(link)
        engine.restart()
        assertTrue(commands.single().contains("svc power reboot") && commands.single().trimEnd().endsWith("&"))
        assertNull(engine.state.value.reason)
    }

    @Test fun aSystemUiRestartLightsTheScreenAndAnswersAtOnce() = runTest {
        val commands = mutableListOf<String>()
        val engine = WatchModuleHostEngine { _, command, _ -> commands += command; result() }
        engine.attach(link)
        engine.restartSystemUi()
        val command = commands.single()
        assertTrue("kill " in command && "com.mobvoi.wear.refsysui" in command)
        assertTrue("KEYCODE_WAKEUP" in command && command.trimEnd().endsWith("&"))
        assertFalse("reboot" in command)
        assertNull(engine.state.value.reason)
    }

    @Test fun aSystemUiRestartThatLostItsReplyIsNoFailureButARefusalIs() = runTest {
        val lost = WatchModuleHostEngine { _, _, _ -> throw IOException("gone") }
        lost.attach(link)
        lost.restartSystemUi()
        assertNull(lost.state.value.reason)
        val refused = WatchModuleHostEngine { _, _, _ -> result(exit = 4) }
        refused.attach(link)
        refused.restartSystemUi()
        assertEquals("module_ui_restart_failed", refused.state.value.reason)
    }

    @Test fun aRestartThatLostTheLinkIsNoFailureButARefusalIs() = runTest {
        val lost = WatchModuleHostEngine { _, _, _ -> throw IOException("gone") }
        lost.attach(link)
        lost.restart()
        assertNull(lost.state.value.reason)
        val refused = WatchModuleHostEngine { _, _, _ -> result(exit = 1) }
        refused.attach(link)
        refused.restart()
        assertEquals("module_restart_failed", refused.state.value.reason)
    }

    @Test fun anExplicitRestartRetryDoesNotRetainThePreviousRefusal() = runTest {
        var refuse = true
        val host = WatchModuleHostEngine { _, _, _ -> result(exit = if (refuse) 1 else 0) }
        host.attach(link)
        host.restart()
        assertEquals("module_restart_failed", host.state.value.reason)
        refuse = false
        host.restart()
        assertNull(host.state.value.reason)
    }

    @Test fun stayingAwakeSetsAnExpiringLockAndAFailureDoesntMatter() = runTest {
        val commands = mutableListOf<String>()
        val engine = WatchModuleHostEngine { _, command, _ -> commands += command; throw IOException("gone") }
        engine.attach(link)
        engine.stayAwake()
        assertTrue(commands.single().contains("/sys/power/wake_lock") && commands.single().contains("300000000000"))
        assertNull(engine.state.value.reason)
    }

    @Test fun aReplyFromBeforeALinkChangeIsDropped() = runTest {
        lateinit var engine: WatchModuleHostEngine
        engine = WatchModuleHostEngine { _, _, _ -> engine.attach(HealthModuleLink(link.peer, 2)); result(running) }
        engine.attach(link)
        engine.refresh()
        assertNull(engine.state.value.snapshot)
        assertEquals(WatchModuleHostPhase.READY, engine.state.value.phase)
    }
}
