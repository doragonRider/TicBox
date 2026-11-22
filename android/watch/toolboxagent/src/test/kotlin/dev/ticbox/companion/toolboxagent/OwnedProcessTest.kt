package dev.ticbox.companion.toolboxagent

import dev.ticbox.companion.protocol.ToolboxProtocol
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

class OwnedProcessTest {
    private fun waitForFile(path: java.nio.file.Path) {
        val deadline = System.nanoTime() + 3_000_000_000
        while (!Files.exists(path) && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(Files.exists(path))
    }
    @Test fun hangupAllowsACaughtSignalToFinishCleanupBeyondTheOldKillDeadline() {
        val root = Files.createTempDirectory("toolbox-hup-")
        val ready = root.resolve("ready"); val cleaned = root.resolve("cleaned")
        val cleanup = "sleep 2; printf complete > ${ToolboxProtocol.quote(cleaned.toString())}; exit 0"
        val process = startOwnedCommand("trap ${ToolboxProtocol.quote(cleanup)} HUP; printf ready > '${ready}'; while :; do sleep 0.05; done", "/bin/sh")
        try {
            waitForFile(ready)
            hangupOwnedCommand(process)
            assertFalse(process.waitFor(1600, TimeUnit.MILLISECONDS))
            assertTrue(process.waitFor(4, TimeUnit.SECONDS))
            assertEquals("complete", Files.readString(cleaned))
        } finally { signalOwnedCommand(process, CommandSignal.FORCE); root.toFile().deleteRecursively() }
    }
    @Test fun ignoredHangupIsNotUpgradedAndOnlyExplicitForceStopsTheCommand() {
        val root = Files.createTempDirectory("toolbox-nohup-")
        val ready = root.resolve("ready")
        val process = startOwnedCommand("trap '' HUP INT TERM; printf ready > '${ready}'; while :; do sleep 0.05; done", "/bin/sh")
        try {
            waitForFile(ready)
            hangupOwnedCommand(process)
            assertFalse(process.waitFor(1800, TimeUnit.MILLISECONDS))
            assertTrue(signalOwnedCommand(process, CommandSignal.FORCE))
            assertTrue(process.waitFor(3, TimeUnit.SECONDS))
        } finally { signalOwnedCommand(process, CommandSignal.FORCE); root.toFile().deleteRecursively() }
    }
    @Test fun anUnregisteredProcessCannotBeSignalledEvenWithAnExplicitForceRequest() {
        val process = ProcessBuilder("sleep", "20").start()
        try {
            assertFalse(signalOwnedCommand(process, CommandSignal.FORCE))
            assertTrue(process.isAlive)
        } finally { process.destroyForcibly() }
    }
}
