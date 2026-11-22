package dev.ticbox.companion.toolboxagent

import android.system.Os
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths

/** lstat facts, kept separate so host tests can exercise Android's UID/permission policy. */
internal data class PtyDirectoryMetadata(val uid: Int, val gid: Int, val mode: Int)

/**
 * A private child is insufficient under a parent another UID can rename entries in. Root uses
 * the root-owned /data/local parent, never the shell-owned /data/local/tmp. Validate its entire fixed
 * ancestry before creating anything; no directory is created or chmod-ed to "repair" a refusal.
 */
internal fun ptyNativeParent(
    uid: Int,
    metadata: (Path) -> PtyDirectoryMetadata = { path ->
        Os.lstat(path.toString()).let { PtyDirectoryMetadata(it.st_uid, it.st_gid, it.st_mode) }
    },
): Path {
    val parent = when (uid) {
        0 -> Paths.get("/data/local")
        2000 -> Paths.get("/data/local/tmp")
        else -> throw IOException("terminal_native_uid_unavailable")
    }
    val trusted = if (uid == 0) setOf(0, 1000) else setOf(0, 1000, 2000)
    fun validate(path: Path) {
        val facts = metadata(path)
        // /data commonly belongs to system:system with mode 0771. Its group write is trusted,
        // unlike write access granted to shell or other non-system identities in a root path.
        val directory = facts.mode and 0xf000 == 0x4000
        val unsafeWrite = facts.mode and 0x2 != 0 || facts.mode and 0x10 != 0 && facts.gid !in trusted
        if (!directory || facts.uid !in trusted || unsafeWrite ||
            uid == 0 && path == parent && (facts.uid != 0 || facts.mode and 0x12 != 0)) {
            throw IOException("terminal_native_directory_untrusted")
        }
    }
    var ancestor = parent.root
    validate(ancestor)
    for (name in parent) {
        ancestor = ancestor.resolve(name)
        validate(ancestor)
    }
    return parent
}
