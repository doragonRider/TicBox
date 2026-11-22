package dev.ticbox.companion.platform.toolbox

import dev.ticbox.companion.api.ToolboxException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Owns the source stream and exposes just one entry, without extracting the APK to disk. */
internal fun bundledApkEntry(source: InputStream, entry: String): InputStream {
    val zip = ZipInputStream(source)
    try {
        while (true) {
            val next = zip.nextEntry ?: throw ToolboxException("bundled_apk_entry_missing")
            if (!next.isDirectory && next.name == entry) return zip
        }
    } catch (error: Throwable) {
        zip.close()
        throw error
    }
}
