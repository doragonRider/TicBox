package dev.ticbox.companion.engine.module

import dev.ticbox.companion.api.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
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

    @Test fun onUnlessTurnedOffAndTheAgeTellsWhetherThisBootHasSeenIt() {
        val off = parseNotificationAlertStatus("alerts off\n")
        assertFalse(off.optedIn); assertFalse(off.running); assertFalse(off.applied)
        val sameBoot = parseNotificationAlertStatus("alerts on 60 3600\n")
        assertTrue(sameBoot.optedIn); assertFalse(sameBoot.optInSurvivedReboot)
        val laterBoot = parseNotificationAlertStatus("alerts on 7200 3600\n")
        assertTrue(laterBoot.optInSurvivedReboot); assertFalse(laterBoot.running)
        // No copy on the watch yet: nothing turned it off, and no boot has seen it.
        val nothing = parseNotificationAlertStatus("alerts on\n")
        assertTrue(nothing.optedIn); assertFalse(nothing.optInSurvivedReboot)
        // The 1.0.7 line meant the opposite, so it is never read as either.
        assertFailsWith<ToolboxException> { parseNotificationAlertStatus("optin none\n") }
        assertFailsWith<ToolboxException> { parseNotificationAlertStatus("alerts maybe\n") }
    }

    @Test fun onlyAllModeOnAWrittenHookCountsAsApplied() {
        assertTrue(parseNotificationAlertStatus("alerts on 7200 3600\n${status("ALL")}\n").applied)
        assertFalse(parseNotificationAlertStatus("alerts on 7200 3600\n${status("PROBE")}\n").applied)
        assertFalse(parseNotificationAlertStatus("alerts on 7200 3600\n${status("ALL", installed = false)}\n").applied)
        assertFailsWith<ToolboxException> { parseNotificationAlertStatus("") }
    }

    @Test fun attachingAsksNothingAndALinkChangeForgets() = runTest {
        var calls = 0
        val engine = NotificationAlertPatchEngine { _, _, _ -> calls++; result("alerts off\n") }
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
            if (command.contains("status")) result("alerts on 60 3600\n") else result("")
        }
        engine.attach(link)
        engine.optIn()
        assertEquals(2, commands.size)
        assertTrue(commands[0].contains("rm -f") && !commands[0].contains("touch"))
        assertTrue(commands[1].contains("status"))
        assertTrue(engine.state.value.snapshot!!.optedIn)
    }

    @Test fun aStagedCopyIsWhatTheNextBootKeeps() = runTest {
        val commands = mutableListOf<String>()
        val engine = NotificationAlertPatchEngine { _, command, _ -> commands += command; result("alerts on 60 3600\n") }
        engine.attach(link)
        engine.optIn()
        // Magisk replaces the active directory with the staged one at boot.
        assertTrue(commands[0].contains("rm -f /data/adb/modules/ticwatch_health_settings/notification-alerts.disabled " +
            "/data/adb/modules_update/ticwatch_health_settings/notification-alerts.disabled"))
        assertTrue(commands[0].contains("exit 3"))
        assertTrue(commands[1].contains("if [ -d /data/adb/modules_update/ticwatch_health_settings ]; then d=/data/adb/modules_update/"))
        val snapshot = engine.state.value.snapshot!!
        assertTrue(snapshot.optedIn); assertFalse(snapshot.optInSurvivedReboot)
    }

    @Test fun aRefusedModeIsReportedBesideWhatTheWatchThenSays() = runTest {
        val engine = NotificationAlertPatchEngine { _, command, _ ->
            if (command.contains("status")) result("alerts on 7200 3600\n${status("OBSERVE", installed = false)}\n")
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
        engine = NotificationAlertPatchEngine { _, _, _ -> engine.attach(HealthModuleLink(link.peer, 2)); result("alerts off\n") }
        engine.attach(link)
        engine.refresh()
        assertNull(engine.state.value.snapshot); assertEquals(NotificationAlertPatchPhase.READY, engine.state.value.phase)
    }

    @Test fun failedOffRemainsVisibleWhenTheRecordWasWrittenButAllIsStillRunning() = runTest {
        val commands = mutableListOf<String>()
        val engine = NotificationAlertPatchEngine { _, command, _ ->
            commands += command
            if (commands.size == 1) result(status("ALL", error = "mode_write_unconfirmed") + "\noptout 1 0\n", exit = 1)
            else result("alerts off\n" + status("ALL") + "\n")
        }
        engine.attach(link); engine.optOut()
        assertEquals(2, commands.size)
        assertEquals("mode_write_unconfirmed", engine.state.value.reason)
        assertEquals(NotificationAlertPatchPhase.READY, engine.state.value.phase)
        assertTrue(engine.state.value.current)
        assertFalse(engine.state.value.snapshot!!.optedIn)
        assertTrue(engine.state.value.snapshot!!.applied)
    }

    @Test fun aFailedRecordIsNotHiddenByASuccessfulOff() = runTest {
        var calls = 0
        val engine = NotificationAlertPatchEngine { _, _, _ ->
            if (++calls == 1) result(status("OFF") + "\noptout 0 1\n", exit = 1)
            else result("alerts on 7200 3600\n" + status("OFF") + "\n")
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
                else result("alerts off\n")
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
            else result("alerts off\n" + status("OFF") + "\n")
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
                if (++calls == 1) failure else result("alerts off\n")
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
            else result("alerts off\n" + status("ALL") + "\n")
        }
        engine.attach(link); engine.optOut()
        assertEquals(2, calls)
        assertEquals("notification_alert_unconfirmed", engine.state.value.reason)
        assertTrue(engine.state.value.snapshot!!.applied)
    }

    @Test fun generatedCommandsKeepTheRecordInEveryCopyAndOnlyTurningOffWritesOne() = runTest {
        val shell = listOf(Path.of("/bin/sh"), Path.of("/usr/bin/sh")).firstOrNull(Files::isExecutable)
        assumeTrue(shell != null && Files.isReadable(Path.of("/proc/uptime")), "This shell contract test requires a POSIX sh host")
        val directory = Files.createTempDirectory("notification-alert-marker-")
        try {
            val module = Files.createDirectory(directory.resolve("module directory"))
            val staged = directory.resolve("staged directory")
            Files.writeString(module.resolve("module.dex"), "fixture")
            fun quoted(value: Path) = "'" + value.toString().replace("'", "'\\''") + "'"
            val engine = NotificationAlertPatchEngine { _, command, _ ->
                // Bind only the fixed device paths to local fixtures; the control answers nothing, as with no extension.
                val local = command.replace("/data/adb/modules_update/ticwatch_health_settings", quoted(staged))
                    .replace("/data/adb/modules/ticwatch_health_settings", quoted(module)).replace("/system/bin/app_process", "false")
                val process = ProcessBuilder(checkNotNull(shell).toString(), "-c", local).start()
                try {
                    assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Generated shell must terminate")
                    ToolboxCommandResult(process.exitValue(), process.inputStream.use { it.readBytes() }, process.errorStream.use { it.readBytes() })
                } finally { if (process.isAlive) process.destroyForcibly() }
            }
            engine.attach(link)
            engine.refresh()
            assertTrue(engine.state.value.snapshot!!.optedIn, "On with no record")
            assertFalse(Files.list(module).use { it.anyMatch { path -> path.fileName.toString().startsWith("notification-alerts") } })
            // An update waits for its restart: the record goes into both copies, and the older opt-in goes.
            Files.createDirectory(staged); Files.writeString(staged.resolve("module.dex"), "fixture")
            Files.writeString(module.resolve("notification-alerts.enabled"), "")
            engine.keepOff()
            assertNull(engine.state.value.reason)
            assertFalse(engine.state.value.snapshot!!.optedIn)
            for (copy in listOf(module, staged)) {
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(copy.resolve("notification-alerts.disabled")))
            }
            assertFalse(Files.exists(module.resolve("notification-alerts.enabled")))
            // The staged copy is the one the next boot keeps, so it alone decides.
            Files.delete(staged.resolve("notification-alerts.disabled")); engine.refresh()
            assertTrue(engine.state.value.snapshot!!.optedIn)
            Files.delete(module.resolve("notification-alerts.disabled")); Files.createSymbolicLink(staged.resolve("notification-alerts.disabled"), directory.resolve("gone"))
            engine.refresh()
            assertFalse(engine.state.value.snapshot!!.optedIn, "Anything under the name counts, as the loader has it")
            Files.delete(staged.resolve("notification-alerts.disabled"))
            engine.keepOff(); engine.optIn()
            assertNull(engine.state.value.reason)
            assertTrue(engine.state.value.snapshot!!.optedIn)
            assertFalse(Files.exists(module.resolve("notification-alerts.disabled")) || Files.exists(staged.resolve("notification-alerts.disabled")))
            // Nowhere to keep it, so it isn't kept.
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).filter { it != directory }.forEach(Files::delete) }
            engine.keepOff()
            assertEquals("notification_alert_write_failed", engine.state.value.reason)
            assertTrue(engine.state.value.snapshot!!.optedIn)
            engine.close()
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    @Test fun generatedOptOutShellPreservesControlFailureWhileKeepingTheRecord() = runTest {
        val shell = listOf(Path.of("/bin/sh"), Path.of("/usr/bin/sh")).firstOrNull(Files::isExecutable)
        assumeTrue(shell != null, "This shell contract test requires a POSIX sh host")
        val directory = Files.createTempDirectory("notification-alert-optout-")
        try {
            val module = Files.createDirectory(directory.resolve("module directory"))
            Files.writeString(module.resolve("module.dex"), "fixture")
            val marker = module.resolve("notification-alerts.disabled")
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
            assertTrue(Files.isRegularFile(marker), "Failure of OFF must not block cancelling the next load")
            assertEquals("mode_write_unconfirmed", engine.state.value.reason)
            assertFalse(engine.state.value.snapshot!!.optedIn)
            assertTrue(engine.state.value.snapshot!!.applied)
            engine.close()
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

}
