package dev.ticbox.companion.toolboxagent

import dev.ticbox.companion.protocol.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlinx.serialization.json.*

internal class FileTransferProblem(val reason: String) : IOException(reason)

/** Retained staging belongs to one immutable upload intent; no failed transfer replaces its destination. */
internal class AgentFileTransfers(private val wire: AgentWire) {
    private fun sendCompletion(value: JsonObject) {
        wire.json(ToolboxProtocol.RESULT, value)
        // Keep stdout/the Bluetooth relay open until the receiver has read the final reply.
        // EOF is also sufficient: the receiver may have closed after accepting the result.
        wire.awaitFileReceipt()
    }
    private fun path(value: String): Path {
        require(value.startsWith('/') && '\u0000' !in value) { "absolute_path_required" }
        return Paths.get(value).toAbsolutePath().normalize()
    }
    private fun digest() = MessageDigest.getInstance("SHA-256")
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun checkedHash(value: String): String = value.also { require(it.matches(Regex("[a-f0-9]{64}"))) }
    private fun fail(code: String): Nothing = throw FileTransferProblem(code)
    private data class Stamp(val key: Any?, val size: Long, val modified: java.nio.file.attribute.FileTime)
    private fun stamp(p: Path): Stamp {
        val attrs = Files.readAttributes(p, BasicFileAttributes::class.java)
        if (!attrs.isRegularFile) fail("file_not_regular")
        return Stamp(attrs.fileKey(), attrs.size(), attrs.lastModifiedTime())
    }
    private fun hash(p: Path): Pair<Long, String> {
        var count = 0L; val digest = digest(); val buffer = ByteArray(ToolboxProtocol.CHUNK)
        Files.newInputStream(p, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            while (true) { val n = input.read(buffer); if (n < 0) break; if (n == 0) continue; count = Math.addExact(count, n.toLong()); digest.update(buffer, 0, n) }
        }
        return count to digest.digest().hex()
    }
    fun snapshot(args: JsonObject) {
        val p = path(args.requiredText("path")).toRealPath()
        val before = stamp(p); val (count, sha) = hash(p)
        if (before != stamp(p) || count != before.size) fail("file_changed")
        sendCompletion(buildJsonObject {
            put("size", count); put("sha256", sha); put("modifiedMillis", before.modified.toMillis())
        })
    }
    fun pull(args: JsonObject) {
        val p = path(args.requiredText("path")).toRealPath()
        val size = args.requiredLong("size"); val offset = args.requiredLong("offset")
        require(size >= 0 && offset in 0..size)
        val expected = checkedHash(args.requiredText("sha256"))
        val prefixHash = if (offset == 0L) null else checkedHash(args.requiredText("prefixSha256"))
        val before = stamp(p)
        if (before.size != size) fail("file_changed")
        val complete = digest(); val suffix = digest(); val prefix = digest(); val buffer = ByteArray(ToolboxProtocol.CHUNK)
        FileInputStream(p.toFile()).use { input ->
            var remaining = offset
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (n <= 0) fail("file_changed")
                complete.update(buffer, 0, n); prefix.update(buffer, 0, n); remaining -= n
            }
            if (prefixHash != null && prefix.digest().hex() != prefixHash) fail("file_resume_mismatch")
            wire.json(ToolboxProtocol.PROGRESS, buildJsonObject { put("completed", offset); put("total", size) })
            var copied = offset
            while (copied < size) {
                val n = input.read(buffer, 0, minOf(size - copied, buffer.size.toLong()).toInt())
                if (n <= 0) fail("file_changed")
                complete.update(buffer, 0, n); suffix.update(buffer, 0, n)
                wire.send(ToolboxProtocol.DATA, buffer.copyOf(n)); copied += n
            }
            if (input.read() != -1 || before != stamp(p) || complete.digest().hex() != expected) fail("file_changed")
        }
        sendCompletion(buildJsonObject { put("bytes", size); put("sha256", expected); put("suffixSha256", suffix.digest().hex()) })
    }
    private data class Intent(val destination: Path, val id: String, val size: Long, val sha: String, val overwrite: Boolean, val mode: Int?) {
        fun json() = buildJsonObject {
            put("path", destination.toString()); put("id", id); put("size", size); put("sha256", sha); put("overwrite", overwrite)
            mode?.let { put("mode", it) }
        }
    }
    private fun intent(args: JsonObject): Intent {
        val raw = path(args.requiredText("path"))
        require(raw.fileName != null && raw.parent != null)
        val destination = raw.parent.toRealPath().resolve(raw.fileName)
        val id = args.requiredText("transferId"); require(id.matches(Regex("[a-f0-9]{32}")))
        val size = args.requiredLong("size"); require(size >= 0)
        val mode = args.number("mode")?.also { require(it in 0..0xfff) }?.toInt()
        return Intent(destination, id, size, checkedHash(args.requiredText("sha256")), args.flag("overwrite") == true, mode)
    }
    private fun directory(intent: Intent) = intent.destination.parent.resolve(".ticbox-transfer-${intent.id}")
    private fun regular(p: Path): Boolean = Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
    private fun metadata(p: Path): JsonObject {
        if (!regular(p) || Files.size(p) > 16 * 1024) fail("file_resume_mismatch")
        return try {
            val text = Files.newInputStream(p, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                val bytes = ByteArray(16 * 1024 + 1)
                var count = 0
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    if (read == 0) fail("file_resume_mismatch")
                    count += read
                }
                if (count > 16 * 1024) fail("file_resume_mismatch")
                String(bytes, 0, count, Charsets.UTF_8)
            }
            ToolboxProtocol.json.parseToJsonElement(text) as JsonObject
        }
        catch (_: Exception) { fail("file_resume_mismatch") }
    }
    private fun save(directory: Path, value: JsonObject) {
        val pending = directory.resolve("manifest.pending")
        if (Files.exists(pending, LinkOption.NOFOLLOW_LINKS)) {
            if (!regular(pending)) fail("file_resume_mismatch")
            Files.delete(pending)
        }
        FileChannel.open(pending, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val bytes = ByteBuffer.wrap(value.toString().toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        Files.move(pending, directory.resolve("manifest.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    private fun destinationToken(p: Path): String {
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return "absent"
        val attrs = Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val content = when {
            attrs.isSymbolicLink -> "link:${Files.readSymbolicLink(p)}"
            attrs.isRegularFile -> hash(p).second
            else -> fail("file_not_regular")
        }
        return digest().digest("${attrs.fileKey()}|${attrs.size()}|${attrs.lastModifiedTime()}|$content".toByteArray()).hex()
    }
    private fun initial(intent: Intent): JsonObject {
        val token = destinationToken(intent.destination)
        if (token != "absent" && !intent.overwrite) throw FileAlreadyExistsException(intent.destination.toString())
        return buildJsonObject {
            put("intent", intent.json()); put("phase", "uploading"); put("destinationToken", token)
        }
    }
    private fun phase(value: JsonObject, phase: String) = JsonObject(value + ("phase" to JsonPrimitive(phase)))
    private fun <T> locked(intent: Intent, create: Boolean, block: (Path, JsonObject?) -> T): T {
        val directory = directory(intent)
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (!create) return block(directory, null)
            try { Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))) } catch (_: FileAlreadyExistsException) { }
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) fail("file_resume_mismatch")
        val key = Files.readAttributes(directory, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()
        FileChannel.open(directory.resolve("lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (lock == null) fail("file_transfer_busy")
            lock.use {
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || key != Files.readAttributes(directory, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).fileKey()) fail("file_resume_mismatch")
                val manifest = directory.resolve("manifest.json")
                val value = if (Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) metadata(manifest) else null
                if (value != null && value["intent"] != intent.json()) fail("file_resume_mismatch")
                if (value == null && Files.exists(directory.resolve("data"), LinkOption.NOFOLLOW_LINKS)) fail("file_resume_mismatch")
                return block(directory, value)
            }
        }
    }
    fun push(args: JsonObject) {
        val intent = intent(args)
        locked(intent, create = true) { directory, saved ->
            var metadata = saved ?: initial(intent).also { save(directory, it) }
            val stage = directory.resolve("data")
            val currentPhase = metadata.requiredText("phase")
            if (currentPhase == "committed" || currentPhase == "committing" && !Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) {
                verifyDestination(intent)
                if (currentPhase != "committed") save(directory, phase(metadata, "committed"))
                return@locked
            }
            if (currentPhase !in setOf("uploading", "committing")) fail("file_resume_mismatch")
            if (destinationToken(intent.destination) != metadata.requiredText("destinationToken")) fail("file_changed")
            if (currentPhase == "uploading") {
                if (!Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) Files.createFile(stage, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                if (!regular(stage)) fail("file_resume_mismatch")
                val (offset, prefixSha) = hash(stage)
                if (offset > intent.size) fail("file_resume_mismatch")
                FileChannel.open(stage, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                    channel.position(offset)
                    wire.json(ToolboxProtocol.PROGRESS, buildJsonObject { put("completed", offset); put("total", intent.size); put("prefixSha256", prefixSha) })
                    var copied = offset
                    while (true) {
                        val packet = wire.read()
                        if (packet.type == ToolboxProtocol.END) break
                        if (packet.type != ToolboxProtocol.DATA || packet.bytes.isEmpty() || packet.bytes.size > ToolboxProtocol.CHUNK) fail("file_transfer_protocol")
                        if (packet.bytes.size > intent.size - copied) fail("file_size_mismatch")
                        val bytes = ByteBuffer.wrap(packet.bytes)
                        while (bytes.hasRemaining()) channel.write(bytes)
                        copied += packet.bytes.size
                        wire.json(ToolboxProtocol.PROGRESS, buildJsonObject { put("completed", copied); put("total", intent.size) })
                    }
                    if (copied != intent.size) fail("file_size_mismatch")
                    channel.force(true)
                }
                val complete = hash(stage)
                if (complete.first != intent.size || complete.second != intent.sha) fail("file_checksum_mismatch")
                if (destinationToken(intent.destination) != metadata.requiredText("destinationToken")) fail("file_changed")
                // Ownership comes from the live destination, never from the persisted sidecar.
                val previous = if (regular(intent.destination)) Files.readAttributes(intent.destination, "unix:mode,uid,gid", LinkOption.NOFOLLOW_LINKS) else null
                val mode = intent.mode ?: (previous?.get("mode") as? Number)?.toInt()
                val attributes = Files.readAttributes(stage, "unix:mode,uid,gid", LinkOption.NOFOLLOW_LINKS)
                if (mode != null && ((attributes["mode"] as Number).toInt() and 0xfff) != (mode and 0xfff)) Files.setAttribute(stage, "unix:mode", mode and 0xfff)
                (previous?.get("uid") as? Number)?.toInt()?.let { if ((attributes["uid"] as Number).toInt() != it) Files.setAttribute(stage, "unix:uid", it) }
                (previous?.get("gid") as? Number)?.toInt()?.let { if ((attributes["gid"] as Number).toInt() != it) Files.setAttribute(stage, "unix:gid", it) }
                metadata = phase(metadata, "committing"); save(directory, metadata)
            }
            val complete = hash(stage)
            if (complete.first != intent.size || complete.second != intent.sha) fail("file_checksum_mismatch")
            if (destinationToken(intent.destination) != metadata.requiredText("destinationToken")) fail("file_changed")
            if (intent.overwrite) Files.move(stage, intent.destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            else Files.move(stage, intent.destination)
            verifyDestination(intent)
            save(directory, phase(metadata, "committed"))
        }
        receipt(intent)
    }
    private fun verifyDestination(intent: Intent) {
        if (!regular(intent.destination)) fail("file_changed")
        val result = hash(intent.destination)
        if (result.first != intent.size || result.second != intent.sha) fail("file_changed")
    }
    private fun receipt(intent: Intent) = sendCompletion(buildJsonObject { put("bytes", intent.size); put("sha256", intent.sha) })
    fun discard(args: JsonObject) {
        val intent = intent(args)
        locked(intent, create = false) { directory, saved ->
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                val allowed = setOf("manifest.json", "manifest.pending", "lock", "data")
                val entries = Files.newDirectoryStream(directory).use { it.toList() }
                if (entries.any { it.fileName.toString() !in allowed || !regular(it) }) fail("file_resume_mismatch")
                // Missing metadata is only admissible for an interrupted empty initialization.
                if (saved == null && entries.any { it.fileName.toString() == "data" }) fail("file_resume_mismatch")
                entries.forEach(Files::delete)
                Files.delete(directory)
            }
        }
        sendCompletion(buildJsonObject { put("discarded", true) })
    }
}

internal fun AgentWire.awaitFileReceipt() {
    try { if (read().type != ToolboxProtocol.END) throw FileTransferProblem("file_transfer_protocol") }
    catch (_: EOFException) { }
}
