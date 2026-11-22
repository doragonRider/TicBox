package dev.ticbox.companion.platform.toolbox

import dev.ticbox.companion.api.ToolboxException
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class BundledApkEntryTest {
    private val jar = byteArrayOf(0, 1, -1, 0, 42)
    private fun apk() = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
        for ((name, bytes) in listOf("classes.dex" to ByteArray(128), "assets/toolbox/scrcpy-server-v4.1.jar" to jar, "assets/unrelated" to byteArrayOf(9))) {
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        }
    } }.toByteArray()
    @Test fun streamsExactlyTheEmbeddedServerAndClosesTheOwnedApkStream() {
        var closed = false
        val input = object : FilterInputStream(apk().inputStream()) { override fun close() { closed = true; super.close() } }
        val result = bundledApkEntry(input, "assets/toolbox/scrcpy-server-v4.1.jar").use { it.readBytes() }
        assertContentEquals(jar, result); assertTrue(closed)
    }
    @Test fun missingServerFailsExplicitlyAndClosesTheSource() {
        var closed = false
        val input = object : FilterInputStream(apk().inputStream()) { override fun close() { closed = true; super.close() } }
        assertEquals("bundled_apk_entry_missing", assertFailsWith<ToolboxException> { bundledApkEntry(input, "assets/missing.jar") }.code)
        assertTrue(closed)
    }
}
