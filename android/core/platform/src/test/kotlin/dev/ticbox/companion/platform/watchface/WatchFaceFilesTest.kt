package dev.ticbox.companion.platform.watchface

import java.nio.file.Files
import kotlin.test.*

class WatchFaceFilesTest {
    @Test fun `staged snapshot is reopenable and close removes only its own local file`() {
        val file = Files.createTempFile("watch-face-fixture", ".zip").toFile()
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            val source = StagedWatchFaceArchive(file)
            assertEquals(3L, source.sizeBytes)
            repeat(2) { assertContentEquals(byteArrayOf(1, 2, 3), source.open().use { it.readBytes() }) }
            source.close(); source.close()
            assertFalse(file.exists())
            assertFailsWith<IllegalStateException> { source.open() }
        } finally { file.delete() }
    }
    @Test fun `URI input accepts local grants and rejects network schemes`() {
        listOf("content", "file", "android.resource").forEach(::requireLocalWatchFaceScheme)
        listOf("http", "https", "ftp", null).forEach { assertFailsWith<IllegalArgumentException> { requireLocalWatchFaceScheme(it) } }
    }
}
