package dev.ticbox.companion.healthbridge

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Transport loss delivers EOF. The operation owns its cleanup/commit and is never killed here. */
internal fun closeToolboxInput(process: Process) {
    runCatching { process.outputStream.close() }
}

/** A lost consumer must not block the agent's finally/PackageManager result behind a full pipe. */
internal fun relayToolboxOutput(input: InputStream, output: OutputStream, disconnected: () -> Unit) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var deliver = true
    while (true) {
        val count = input.read(buffer); if (count < 0) return
        if (count == 0) continue
        if (deliver) try { output.write(buffer, 0, count); output.flush() }
        catch (_: IOException) { deliver = false; disconnected() }
    }
}

/** The root process and Bluetooth connection exchange frames while both streams stay open. */
internal fun relayToolboxStream(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return
        if (count == 0) continue
        output.write(buffer, 0, count)
        // Process.outputStream buffers small requests. Flush before waiting for the next
        // Bluetooth read, since that read can depend on this request's agent response.
        output.flush()
    }
}
