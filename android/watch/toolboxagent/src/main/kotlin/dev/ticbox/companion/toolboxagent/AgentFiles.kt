package dev.ticbox.companion.toolboxagent

import dev.ticbox.companion.protocol.*
import java.io.*
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlinx.serialization.json.*

/** Files are evaluated by the OS under the real agent UID; there is no shell-sized root allowlist. */
internal class AgentFiles(private val wire: AgentWire, private val checkCancelled: () -> Unit = {}) {
    private fun path(value: String): Path {
        require(value.startsWith('/') && '\u0000' !in value) { "absolute_path_required" }
        return Paths.get(value)
    }
    fun stat(value: String): JsonObject {
        val p = path(value)
        val basic = Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val unix = Files.readAttributes(p, "unix:mode,uid,gid", LinkOption.NOFOLLOW_LINKS)
        return buildJsonObject {
            put("path", p.toString()); put("name", p.fileName?.toString() ?: "/")
            put("kind", when { basic.isSymbolicLink -> "SYMLINK"; basic.isDirectory -> "DIRECTORY"; basic.isRegularFile -> "FILE"; else -> "OTHER" })
            put("size", basic.size()); put("modifiedMillis", basic.lastModifiedTime().toMillis())
            put("mode", (unix["mode"] as Number).toInt()); put("uid", (unix["uid"] as Number).toInt()); put("gid", (unix["gid"] as Number).toInt())
            if (basic.isSymbolicLink) put("linkTarget", Files.readSymbolicLink(p).toString())
        }
    }
    fun list(value: String) {
        Files.newDirectoryStream(path(value)).use { entries -> entries.forEach { wire.json(ToolboxProtocol.RESULT, stat(it.toString())) } }
        wire.send(ToolboxProtocol.END)
    }
    fun get(value: String, offset: Long) {
        require(offset >= 0)
        FileInputStream(path(value).toFile()).use { stream ->
            val size = stream.channel.size(); require(offset <= size) { "offset_past_end" }; stream.channel.position(offset)
            wire.json(ToolboxProtocol.PROGRESS, buildJsonObject { if (size > 0) put("total", size - offset) })
            val buffer = ByteArray(ToolboxProtocol.CHUNK)
            while (true) { val n = stream.read(buffer); if (n < 0) break; wire.send(ToolboxProtocol.DATA, buffer.copyOf(n)) }
        }
        wire.send(ToolboxProtocol.END)
        wire.awaitFileReceipt()
    }
    fun put(args: JsonObject) {
        val destination = path(args.requiredText("path")); val size = args.requiredLong("size"); require(size >= 0)
        val overwrite = args.flag("overwrite") == true; val atomic = args.flag("atomic") != false
        val exists = Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
        if (exists && !overwrite) throw FileAlreadyExistsException(destination.toString())
        // Never follow an existing symlink into a different file when replacing it.
        if (exists && Files.isSymbolicLink(destination)) require(atomic) { "symlink_requires_atomic_replace" }
        val previous = if (exists && !Files.isSymbolicLink(destination)) Files.readAttributes(destination, "unix:mode,uid,gid", LinkOption.NOFOLLOW_LINKS) else null
        val target = if (atomic) Files.createTempFile(destination.parent, ".ticbox-", ".part") else destination
        var committed = false
        try {
            var copied = 0L
            val options = if (atomic || overwrite) arrayOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
                else arrayOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)
            Files.newOutputStream(target, *options).use { out ->
                while (true) {
                    val packet = wire.read()
                    if (packet.type == ToolboxProtocol.END) break
                    require(packet.type == ToolboxProtocol.DATA && packet.bytes.isNotEmpty()) { "file_chunk_required" }
                    copied = Math.addExact(copied, packet.bytes.size.toLong()); require(copied <= size) { "file_size_mismatch" }
                    out.write(packet.bytes)
                    wire.json(ToolboxProtocol.PROGRESS, buildJsonObject { put("completed", copied); put("total", size) })
                }
                require(copied == size) { "file_size_mismatch" }
            }
            val mode = args.number("mode")?.toInt() ?: (previous?.get("mode") as? Number)?.toInt()
            if (mode != null) Files.setAttribute(target, "unix:mode", mode and 0xfff)
            if (previous != null) {
                Files.setAttribute(target, "unix:uid", (previous["uid"] as Number).toInt())
                Files.setAttribute(target, "unix:gid", (previous["gid"] as Number).toInt())
            }
            if (atomic) {
                // Preserve create-only semantics. Hard links are unavailable on emulated storage;
                // the provider's same-directory no-replace move works there as well.
                if (!overwrite) Files.move(target, destination)
                else Files.move(target, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            committed = true
            wire.json(ToolboxProtocol.RESULT, buildJsonObject { put("bytes", copied) })
        } finally { if (atomic && !committed) Files.deleteIfExists(target) }
    }
    fun change(args: JsonObject) {
        checkCancelled()
        val p = path(args.requiredText("path")); val recursive = args.flag("recursive") == true
        val overwrite = args.flag("overwrite") == true
        fun destination() = path(args.requiredText("destination"))
        when (args.requiredText("action")) {
            "MAKE_DIRECTORY" -> if (recursive) Files.createDirectories(p) else Files.createDirectory(p)
            "DELETE" -> if (recursive) remove(p) else Files.delete(p)
            "MOVE" -> if (overwrite) Files.move(p, destination(), StandardCopyOption.REPLACE_EXISTING) else Files.move(p, destination())
            "COPY" -> copy(p, destination(), recursive, overwrite)
            "CHMOD" -> Files.setAttribute(p, "unix:mode", args.requiredLong("mode").toInt().also { require(it in 0..0xfff) }, LinkOption.NOFOLLOW_LINKS)
            "CHOWN" -> {
                require(args.number("uid") != null || args.number("gid") != null) { "owner_required" }
                args.number("uid")?.toInt()?.let { require(it >= 0); Files.setAttribute(p, "unix:uid", it, LinkOption.NOFOLLOW_LINKS) }
                args.number("gid")?.toInt()?.let { require(it >= 0); Files.setAttribute(p, "unix:gid", it, LinkOption.NOFOLLOW_LINKS) }
            }
            "SYMLINK" -> Files.createSymbolicLink(destination(), p)
            else -> throw IllegalArgumentException("unknown_file_action")
        }
        wire.json(ToolboxProtocol.RESULT, buildJsonObject { put("success", true) })
    }
    private fun copy(source: Path, destination: Path, recursive: Boolean, overwrite: Boolean) {
        val absolute = source.toAbsolutePath().normalize()
        val src = absolute.parent?.toRealPath()?.resolve(absolute.fileName) ?: absolute.toRealPath()
        fun resolvedDestination(p: Path): Path = if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) p.toRealPath()
            else resolvedDestination(p.parent).resolve(p.fileName)
        val dst = destination.toAbsolutePath().normalize()
        val resolved = resolvedDestination(dst)
        require(src != resolved && !resolved.startsWith(src)) { "copy_into_self" }
        val options: Array<CopyOption> = if (overwrite) arrayOf(LinkOption.NOFOLLOW_LINKS, StandardCopyOption.REPLACE_EXISTING) else arrayOf(LinkOption.NOFOLLOW_LINKS)
        if (!Files.isDirectory(src, LinkOption.NOFOLLOW_LINKS)) { Files.copy(src, dst, *options); return }
        require(recursive) { "recursive_required" }
        Files.walkFileTree(src, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCancelled()
                val target = dst.resolve(src.relativize(dir))
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(target)
                else require(overwrite && Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) { "destination_exists" }
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCancelled()
                Files.copy(file, dst.resolve(src.relativize(file)), *options); return FileVisitResult.CONTINUE
            }
        })
    }
    internal fun remove(path: Path) {
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { checkCancelled(); Files.delete(file); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult { checkCancelled(); if (exc != null) throw exc; Files.delete(dir); return FileVisitResult.CONTINUE }
        })
    }
}

internal fun JsonObject.requiredText(key: String): String = text(key)?.also { require('\u0000' !in it) } ?: throw IllegalArgumentException("missing_$key")
internal fun JsonObject.requiredLong(key: String): Long = number(key) ?: throw IllegalArgumentException("missing_$key")
