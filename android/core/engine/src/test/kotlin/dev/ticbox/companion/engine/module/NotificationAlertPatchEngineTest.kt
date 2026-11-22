package dev.ticbox.companion.engine.module

import dev.ticbox.companion.api.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue

class NotificationAlertPatchEngineTest {
    private val link = HealthModuleLink(PeerAddress("00:11:22:33:44:55"), 1)
    private fun status(mode: String = "OBSERVE", installed: Boolean = true, error: String? = null) =
        "{\"version\":1,\"package\":\"com.mobvoi.wear.refsysui\",\"installed\":$installed,\"mode\":\"$mode\"" +
            (error?.let { ",\"error\":\"$it\"" } ?: "") + "}"
    private fun result(stdout: String, exit: Int = 0) = ToolboxCommandResult(exit, stdout.toByteArray(), ByteArray(0))

    @Test fun markerAgeAgainstUptimeTellsWhetherThisBootHasSeenIt() {
        val none = parseNotificationAlertStatus("optin none\n")
        assertFalse(none.optedIn); assertFalse(none.running); assertFalse(none.applied)
        val sameBoot = parseNotificationAlertStatus("optin 60 3600\n")
        assertTrue(sameBoot.optedIn); assertFalse(sameBoot.optInSurvivedReboot)
        val laterBoot = parseNotificationAlertStatus("optin 7200 3600\n")
        assertTrue(laterBoot.optInSurvivedReboot); assertFalse(laterBoot.running)
    }

    @Test fun onlyAllModeOnAWrittenHookCountsAsApplied() {
        assertTrue(parseNotificationAlertStatus("optin 7200 3600\n${status("ALL")}\n").applied)
        assertFalse(parseNotificationAlertStatus("optin 7200 3600\n${status("PROBE")}\n").applied)
        assertFalse(parseNotificationAlertStatus("optin 7200 3600\n${status("ALL", installed = false)}\n").applied)
        assertFailsWith<ToolboxException> { parseNotificationAlertStatus("") }
    }

    @Test fun attachingAsksNothingAndALinkChangeForgets() = runTest {
        var calls = 0
        val engine = NotificationAlertPatchEngine { _, _, _ -> calls++; result("optin none\n") }
        engine.attach(link)
        assertEquals(0, calls)
        assertEquals(NotificationAlertPatchPhase.READY, engine.state.value.phase)
        assertNull(engine.state.value.snapshot)
        engine.refresh()
        assertEquals(1, calls); assertTrue(engine.state.value.current)
        engine.attach(null)
        assertNull(engine.state.value.snapshot); assertFalse(engine.state.value.current)
    }

    @Test fun aWriteIsFollowedByARead() = runTest {
        val commands = mutableListOf<String>()
        val engine = NotificationAlertPatchEngine { _, command, _ ->
            commands += command
            if (command.contains("status")) result("optin 60 3600\n") else result("")
        }
        engine.attach(link)
        engine.optIn()
        assertEquals(2, commands.size)
        assertTrue(commands[0].contains("touch") && commands[0].contains("chmod 600"))
        assertTrue(commands[1].contains("status"))
        assertTrue(engine.state.value.snapshot!!.optedIn)
    }

    @Test fun aStagedCopyTakesTheMarkerSoOneRestartFinishesBoth() = runTest {
        val commands = mutableListOf<String>()
        val engine = NotificationAlertPatchEngine { _, command, _ -> commands += command; result("optin 60 3600\n") }
        engine.attach(link)
        engine.optIn()
        // Magisk replaces the active directory with the staged one at boot.
        assertTrue(commands[0].contains("[ -d /data/adb/modules_update/ticwatch_health_settings ]"))
        assertTrue(commands[0].contains("m=/data/adb/modules_update/ticwatch_health_settings/notification-alerts.enabled"))
        assertTrue(commands[0].contains("exit 3"))
        assertTrue(commands[1].contains("[ -d /data/adb/modules_update/ticwatch_health_settings ]"))
        val snapshot = engine.state.value.snapshot!!
        assertTrue(snapshot.optedIn); assertFalse(snapshot.optInSurvivedReboot)
        engine.optOut()
        assertTrue(commands[2].contains("rm -f /data/adb/modules/ticwatch_health_settings/notification-alerts.enabled /data/adb/modules_update/"))
    }

    @Test fun aRefusedModeIsReportedBesideWhatTheWatchThenSays() = runTest {
        val engine = NotificationAlertPatchEngine { _, command, _ ->
            if (command.contains("status")) result("optin 7200 3600\n${status("OBSERVE", installed = false)}\n")
            else result(status("OBSERVE", false, "notification_alert_contract_unavailable"), exit = 1)
        }
        engine.attach(link)
        engine.applyToAll()
        val state = engine.state.value
        assertEquals("notification_alert_contract_unavailable", state.reason)
        assertTrue(state.current); assertFalse(state.snapshot!!.applied)
    }

    @Test fun aLostWriteIsNeverRepeated() = runTest {
        val commands = mutableListOf<String>()
        val engine = NotificationAlertPatchEngine { _, command, _ ->
            commands += command
            if (command.contains("status")) throw ToolboxException("bridge_not_connected")
            throw IOException("gone")
        }
        engine.attach(link)
        engine.applyToAll()
        assertEquals(2, commands.size)
        assertEquals(1, commands.count { !it.contains("status") })
        val state = engine.state.value
        assertEquals("notification_alert_unconfirmed", state.reason)
        assertFalse(state.current); assertEquals(NotificationAlertPatchPhase.UNAVAILABLE, state.phase)
    }

    @Test fun aReplyFromBeforeALinkChangeIsDropped() = runTest {
        lateinit var engine: NotificationAlertPatchEngine
        engine = NotificationAlertPatchEngine { _, _, _ -> engine.attach(HealthModuleLink(link.peer, 2)); result("optin none\n") }
        engine.attach(link)
        engine.refresh()
        assertNull(engine.state.value.snapshot); assertEquals(NotificationAlertPatchPhase.READY, engine.state.value.phase)
    }

    @Test fun failedOffRemainsVisibleWhenTheMarkerWasRemovedButAllIsStillRunning() = runTest {
        val commands = mutableListOf<String>()
        val engine = NotificationAlertPatchEngine { _, command, _ ->
            commands += command
            if (commands.size == 1) result(status("ALL", error = "mode_write_unconfirmed") + "\noptout 1 0\n", exit = 1)
            else result("optin none\n" + status("ALL") + "\n")
        }
        engine.attach(link); engine.optOut()
        assertEquals(2, commands.size)
        assertEquals("mode_write_unconfirmed", engine.state.value.reason)
        assertEquals(NotificationAlertPatchPhase.READY, engine.state.value.phase)
        assertTrue(engine.state.value.current)
        assertFalse(engine.state.value.snapshot!!.optedIn)
        assertTrue(engine.state.value.snapshot!!.applied)
    }

    @Test fun aMarkerRemovalFailureIsNotHiddenByASuccessfulOff() = runTest {
        var calls = 0
        val engine = NotificationAlertPatchEngine { _, _, _ ->
            if (++calls == 1) result(status("OFF") + "\noptout 0 1\n", exit = 1)
            else result("optin 7200 3600\n" + status("OFF") + "\n")
        }
        engine.attach(link); engine.optOut()
        assertEquals(2, calls)
        assertEquals("notification_alert_write_failed", engine.state.value.reason)
        assertTrue(engine.state.value.current)
        assertTrue(engine.state.value.snapshot!!.optedIn)
        assertFalse(engine.state.value.snapshot!!.applied)
    }

    @Test fun anAbsentControlSocketOrDexDoesNotPreventCancellingFutureLoading() = runTest {
        for (offExit in listOf(1, 3)) {
            var calls = 0
            val engine = NotificationAlertPatchEngine { _, _, _ ->
                if (++calls == 1) result("optout " + offExit + " 0\n", exit = offExit)
                else result("optin none\n")
            }
            engine.attach(link); engine.optOut()
            assertEquals(2, calls)
            assertEquals(NotificationAlertPatchPhase.READY, engine.state.value.phase)
            assertEquals("notification_alert_control_unavailable", engine.state.value.reason)
            assertTrue(engine.state.value.current)
            assertFalse(engine.state.value.snapshot!!.optedIn)
            assertFalse(engine.state.value.snapshot!!.running)
            engine.close()
        }
    }

    @Test fun successfulOffAndMarkerRemovalAreConfirmedWithoutRepeatingTheWrite() = runTest {
        val commands = mutableListOf<String>()
        val engine = NotificationAlertPatchEngine { _, command, _ ->
            commands += command
            if (commands.size == 1) result(status("OFF") + "\noptout 0 0\n")
            else result("optin none\n" + status("OFF") + "\n")
        }
        engine.attach(link); engine.optOut()
        assertEquals(2, commands.size)
        assertNull(engine.state.value.reason)
        assertTrue(engine.state.value.current)
        assertEquals(NotificationAlertPatchMode.OFF, engine.state.value.snapshot!!.mode)
        assertFalse(engine.state.value.snapshot!!.optedIn)
        assertFalse(engine.state.value.snapshot!!.applied)
    }

    @Test fun missingDamagedOrTimedOutOptOutReceiptsStayUnconfirmedAndOnlyReadOnce() = runTest {
        val failures = listOf(
            result(status("OFF")),
            result(status("OFF") + "\noptout 0 0\noptout 0 0\n"),
            result(status("OFF") + "\noptout bad 0\n"),
            result(status("OFF") + "\noptout 256 0\n", exit = 256),
            result(status("OFF") + "\noptout 1 0\n"),
            result(status("ALL") + "\noptout 0 0\n"),
            result("optout 0 0\n"),
            result(status("OFF") + "\noptout 0 0\n").copy(truncated = true),
            result("", exit = 124).copy(timedOut = true),
        )
        for (failure in failures) {
            var calls = 0
            val engine = NotificationAlertPatchEngine { _, _, _ ->
                if (++calls == 1) failure else result("optin none\n")
            }
            engine.attach(link); engine.optOut()
            assertEquals(2, calls)
            assertEquals("notification_alert_unconfirmed", engine.state.value.reason)
            assertTrue(engine.state.value.current)
            engine.close()
        }
    }

    @Test fun aThrownOptOutTimeoutStillOnlyPerformsOneReadAfterwards() = runTest {
        var calls = 0
        val engine = NotificationAlertPatchEngine { _, _, _ ->
            if (++calls == 1) withTimeout(1) { awaitCancellation() }
            else result("optin none\n" + status("ALL") + "\n")
        }
        engine.attach(link); engine.optOut()
        assertEquals(2, calls)
        assertEquals("notification_alert_unconfirmed", engine.state.value.reason)
        assertTrue(engine.state.value.snapshot!!.applied)
    }

    @Test fun generatedOptOutShellPreservesControlFailureWhileRemovingTheMarker() = runTest {
        val shell = listOf(Path.of("/bin/sh"), Path.of("/usr/bin/sh")).firstOrNull(Files::isExecutable)
        assumeTrue(shell != null, "This shell contract test requires a POSIX sh host")
        val directory = Files.createTempDirectory("notification-alert-optout-")
        try {
            val module = Files.createDirectory(directory.resolve("module directory"))
            Files.writeString(module.resolve("module.dex"), "fixture")
            val marker = module.resolve("notification-alerts.enabled")
            Files.writeString(marker, "")
            val control = directory.resolve("fake-app-process")
            Files.writeString(control, "#!/bin/sh\ncase \"\$3\" in\n" +
                "OFF) printf '%s\\n' '" + status("ALL", error = "mode_write_unconfirmed") + "'; exit 7 ;;\n" +
                "status) printf '%s\\n' '" + status("ALL") + "'; exit 0 ;;\n" +
                "*) exit 9 ;;\nesac\n")
            assertTrue(control.toFile().setExecutable(true))
            val results = mutableListOf<ToolboxCommandResult>()
            fun quoted(value: Path) = "'" + value.toString().replace("'", "'\\''") + "'"
            val engine = NotificationAlertPatchEngine { _, command, _ ->
                // Bind only the fixed device paths to local fixtures; execute the production shell.
                val local = command.replace("/data/adb/modules/ticwatch_health_settings", quoted(module))
                    .replace("/system/bin/app_process", quoted(control))
                val process = ProcessBuilder(checkNotNull(shell).toString(), "-c", local).start()
                try {
                    assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Generated opt-out shell must terminate")
                    ToolboxCommandResult(process.exitValue(), process.inputStream.use { it.readBytes() }, process.errorStream.use { it.readBytes() })
                        .also { results += it }
                } finally { if (process.isAlive) process.destroyForcibly() }
            }
            engine.attach(link); engine.optOut()
            assertEquals(2, results.size)
            assertEquals(7, results[0].exitCode, "A successful rm must not replace the control failure exit")
            assertContains(results[0].stdout.decodeToString(), "optout 7 0")
            assertFalse(Files.exists(marker), "Failure of OFF must not block cancelling the next load")
            assertEquals("mode_write_unconfirmed", engine.state.value.reason)
            assertFalse(engine.state.value.snapshot!!.optedIn)
            assertTrue(engine.state.value.snapshot!!.applied)
            engine.close()
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

}
