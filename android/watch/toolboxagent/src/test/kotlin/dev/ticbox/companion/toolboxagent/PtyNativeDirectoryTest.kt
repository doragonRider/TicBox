package dev.ticbox.companion.toolboxagent

import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.*

/** Android ownership fixtures: no root privilege, chown, or platform directory mutation on host. */
class PtyNativeDirectoryTest {
    private fun directory(uid: Int, gid: Int, permissions: Int) = PtyDirectoryMetadata(uid, gid, 0x4000 or permissions)
    private fun fixture() = mutableMapOf(
        Paths.get("/") to directory(0, 0, 0x1ed), // 0755
        Paths.get("/data") to directory(1000, 1000, 0x1f9), // 0771
        Paths.get("/data/local") to directory(0, 0, 0x1e9), // 0751
        Paths.get("/data/local/tmp") to directory(2000, 2000, 0x1f9), // 0771
    )
    private class Probe(val facts: Map<Path, PtyDirectoryMetadata>) {
        val reads = mutableListOf<Path>()
        var creationStarted = false
        // Mirrors the creation boundary in NativeLibrary: policy completes before a child exists.
        fun parentBeforeCreation(uid: Int): Path = ptyNativeParent(uid) { path ->
            reads.add(path)
            facts[path] ?: throw NoSuchFileException(path.toString())
        }.also { creationStarted = true }
    }

    @Test fun rootUsesAProtectedParentAndNeverLooksAtTheShellTempDirectory() {
        val probe = Probe(fixture())
        assertEquals(Paths.get("/data/local"), probe.parentBeforeCreation(0))
        assertEquals(listOf(Paths.get("/"), Paths.get("/data"), Paths.get("/data/local")), probe.reads)
        assertTrue(probe.creationStarted)
    }

    @Test fun shellKeepsItsOwnTempParent() {
        val probe = Probe(fixture())
        assertEquals(Paths.get("/data/local/tmp"), probe.parentBeforeCreation(2000))
        assertEquals(Paths.get("/data/local/tmp"), probe.reads.last())
    }

    @Test fun missingRootParentCannotFallBackToAnExistingShellParent() {
        val probe = Probe(fixture().apply { remove(Paths.get("/data/local")) })
        assertFailsWith<NoSuchFileException> { probe.parentBeforeCreation(0) }
        assertFalse(probe.creationStarted)
        assertFalse(Paths.get("/data/local/tmp") in probe.reads)
    }

    @Test fun rootExtractionRefusesAShellWritableAncestorOrParent() {
        val unsafeAncestors = listOf(
            Paths.get("/") to directory(2000, 2000, 0x1ed),
            Paths.get("/data") to directory(2000, 2000, 0x1c0),
            Paths.get("/data") to directory(1000, 2000, 0x1f9),
            Paths.get("/data") to directory(0, 0, 0x1ff), // world-writable, even with root owner
            Paths.get("/data/local") to directory(2000, 2000, 0x1c0),
            Paths.get("/data/local") to directory(1000, 1000, 0x1c0),
            Paths.get("/data/local") to directory(0, 2000, 0x1f8), // 0770
            Paths.get("/data/local") to directory(0, 0, 0x1c2), // 0702
        )
        for ((path, facts) in unsafeAncestors) {
            val probe = Probe(fixture().apply { put(path, facts) })
            assertEquals("terminal_native_directory_untrusted", assertFailsWith<IOException>(path.toString()) { probe.parentBeforeCreation(0) }.message)
            assertFalse(probe.creationStarted, path.toString())
        }
    }

    @Test fun symlinksAndFilesAtEveryRootAncestorAreRefusedBeforeCreation() {
        for (path in listOf("/", "/data", "/data/local").map(Paths::get)) {
            for (mode in listOf(0xa1ff, 0x81c0)) { // symlink, regular file
                val probe = Probe(fixture().apply { put(path, PtyDirectoryMetadata(0, 0, mode)) })
                assertFailsWith<IOException> { probe.parentBeforeCreation(0) }
                assertFalse(probe.creationStarted)
            }
        }
    }

    @Test fun systemGroupWritableDataIsAcceptedButRootParentGroupWriteIsNot() {
        assertEquals(Paths.get("/data/local"), Probe(fixture()).parentBeforeCreation(0))
        val unsafe = Probe(fixture().apply { put(Paths.get("/data/local"), directory(0, 1000, 0x1f8)) })
        assertFailsWith<IOException> { unsafe.parentBeforeCreation(0) }
        assertFalse(unsafe.creationStarted)
    }

    @Test fun unexpectedUidAndUnsafeShellParentFailBeforeCreatingAnything() {
        for (uid in listOf(-1, 1000, 10000)) {
            val probe = Probe(fixture())
            assertEquals("terminal_native_uid_unavailable", assertFailsWith<IOException> { probe.parentBeforeCreation(uid) }.message)
            assertTrue(probe.reads.isEmpty())
            assertFalse(probe.creationStarted)
        }
        val unsafe = Probe(fixture().apply { put(Paths.get("/data/local/tmp"), directory(2000, 2000, 0x1ff)) })
        assertFailsWith<IOException> { unsafe.parentBeforeCreation(2000) }
        assertFalse(unsafe.creationStarted)
    }
}
