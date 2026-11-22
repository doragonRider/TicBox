package dev.ticbox.companion.toolboxagent

import android.annotation.SuppressLint
import android.os.Build
import android.os.Process
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import java.util.zip.ZipFile

/** An owned controlling terminal. All methods block; callers keep them off the UI thread. */
internal class PtyProcess private constructor(handle: Long) : AutoCloseable {
    private val handle = AtomicLong(handle)
    private val inputLock = Any()
    private fun live() = handle.get().takeIf { it != 0L } ?: throw IOException("terminal_closed")

    /** Returns 0 on a quiet poll, -1 at terminal EOF. Bytes remain completely unmodified. */
    fun read(bytes: ByteArray, timeoutMillis: Int = 250): Int {
        require(bytes.size in 1..65_536 && timeoutMillis in 1..60_000)
        return PtyNative.read(live(), bytes, timeoutMillis)
    }
    fun write(bytes: ByteArray) = synchronized(inputLock) {
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(bytes.size, offset + 65_536)
            PtyNative.write(live(), bytes.copyOfRange(offset, end))
            offset = end
        }
        if (bytes.isEmpty()) live()
    }
    fun resize(rows: Int, columns: Int, widthPixels: Int = 0, heightPixels: Int = 0) {
        validateSize(rows, columns, widthPixels, heightPixels)
        PtyNative.resize(live(), rows, columns, widthPixels, heightPixels)
    }
    /** Actual shell exit status, or 128 + signal. Close interrupts this wait if still running. */
    fun awaitExit(): Int = PtyNative.awaitExit(live())
    /** Terminal hangup only. No forced kill of a shell or a detached/background command. */
    override fun close() { handle.getAndSet(0).takeIf { it != 0L }?.let(PtyNative::close) }

    companion object {
        fun available(): Boolean = AgentNativeLibrary.failure == null
        fun start(shell: String, term: String, rows: Int, columns: Int, widthPixels: Int = 0, heightPixels: Int = 0): PtyProcess {
            require(shell.startsWith('/') && '\u0000' !in shell)
            require(term.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,63}")))
            validateSize(rows, columns, widthPixels, heightPixels)
            AgentNativeLibrary.failure?.let { throw IOException("terminal_unavailable", it) }
            return PtyProcess(PtyNative.start(shell, term, rows, columns, widthPixels, heightPixels))
        }
        private fun validateSize(rows: Int, columns: Int, widthPixels: Int, heightPixels: Int) {
            require(rows in 1..1000 && columns in 1..1000 && widthPixels in 0..65_535 && heightPixels in 0..65_535)
        }
    }
}

/** app_process does not have an APK ClassLoader native path. Extract only this APK's fixed asset. */
internal object AgentNativeLibrary {
    val failure: Throwable? = try { load(); null } catch (error: Exception) { error } catch (error: LinkageError) { error }
    // Standalone app_process has no APK nativeLibraryDir. The Android path only loads the fixed
    // asset from its verified agent APK. Root uses a root-owned parent whose ancestry is not
    // writable by shell; its private 0700 child cannot be renamed/replaced by shell. Copies use
    // CREATE_NEW, length/CRC checks and read-only permissions. The other branch is the host fixture.
    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private fun load() {
        // Host JNI integration tests use this property. Android always uses the verified agent APK.
        if (System.getProperty("java.vm.name") != "Dalvik") {
            val library = System.getProperty("ticbox.pty.library") ?: throw IOException("terminal_native_host_unavailable")
            // Robolectric uses isolated classloaders; each owns a distinct mapped library.
            val directory = Files.createTempDirectory("ticbox-pty-host-")
            val copy = directory.resolve("libticbox_pty.so")
            try {
                Files.copy(Paths.get(library).toAbsolutePath(), copy)
                System.load(copy.toString())
            } finally {
                try { Files.deleteIfExists(copy) } finally { Files.deleteIfExists(directory) }
            }
            return
        }
        val supported = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        val bits64 = Process.is64Bit()
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in supported && (it in setOf("arm64-v8a", "x86_64")) == bits64 }
            ?: throw IOException("terminal_native_abi_unavailable")
        val classpath = System.getenv("CLASSPATH")?.split(':')?.singleOrNull()?.let(Paths::get)
            ?: throw IOException("terminal_native_classpath_unavailable")
        if (!Files.isRegularFile(classpath, LinkOption.NOFOLLOW_LINKS)) throw IOException("terminal_native_classpath_unavailable")
        ZipFile(classpath.toFile()).use { archive ->
            val asset = archive.getEntry("assets/toolbox/pty/$abi/libticbox_pty.so")
                ?: throw IOException("terminal_native_abi_unavailable")
            if (asset.isDirectory || asset.size !in 1..2_097_152) throw IOException("terminal_native_payload_invalid")
            val directory = Files.createTempDirectory(ptyNativeParent(Process.myUid()), "ticbox-pty-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            val library = directory.resolve("libticbox_pty.so")
            try {
                val crc = CRC32()
                var bytes = 0L
                Files.newOutputStream(library, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { output ->
                    archive.getInputStream(asset).use { input ->
                        val buffer = ByteArray(16_384)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            bytes += count
                            if (bytes > asset.size) throw IOException("terminal_native_payload_invalid")
                            crc.update(buffer, 0, count); output.write(buffer, 0, count)
                        }
                    }
                }
                if (bytes != asset.size || crc.value != asset.crc) throw IOException("terminal_native_payload_invalid")
                Files.setPosixFilePermissions(library, PosixFilePermissions.fromString("r-x------"))
                System.load(library.toString())
            } finally {
                try { Files.deleteIfExists(library) } finally { Files.deleteIfExists(directory) }
            }
        }
    }
}
