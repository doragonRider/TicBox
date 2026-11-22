package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.ToolboxException
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import kotlin.test.*

class ZipDirectoryTest {
    private val body = ByteArray(5_000) { (it % 251).toByte() }

    private fun directoryOf(bytes: ByteArray) = ZipDirectory.read(bytes.size.toLong()) { bytes.inputStream() }

    private fun assertBodyRead(archive: ByteArray) {
        val directory = assertNotNull(directoryOf(archive))
        val item = assertNotNull(directory.item("a/b.apk"))
        assertEquals(body.size.toLong(), item.size)
        assertContentEquals(body, directory.open(item).use { it.readBytes() })
    }

    @Test fun storedEntriesWithADataDescriptorAreRead() = assertBodyRead(archiveOf(listOf(ArchiveEntry("a/b.apk", body))))

    @Test fun deflatedEntriesAreRead() = assertBodyRead(archiveOf(listOf(ArchiveEntry("a/b.apk", body, deflated = true))))

    @Test fun zip64RecordsAreRead() {
        assertBodyRead(archiveOf(listOf(ArchiveEntry("a/b.apk", body)), zip64 = true))
        assertBodyRead(archiveOf(listOf(ArchiveEntry("a/b.apk", body, deflated = true)), zip64 = true))
    }

    @Test fun aCommentOrBytesInFrontDontMoveWhatIsFound() {
        assertBodyRead(archiveOf(listOf(ArchiveEntry("a/b.apk", body)), comment = "made by some tool"))
        assertBodyRead(archiveOf(listOf(ArchiveEntry("a/b.apk", body)), prefix = ByteArray(77) { 1 }))
    }

    @Test fun aSourceThatCantSkipIsReadThrough() {
        val archive = archiveOf(listOf(ArchiveEntry("first", ByteArray(100)), ArchiveEntry("a/b.apk", body)))
        val directory = assertNotNull(ZipDirectory.read(archive.size.toLong()) {
            object : FilterInputStream(ByteArrayInputStream(archive)) { override fun skip(count: Long) = 0L }
        })
        assertContentEquals(body, directory.open(directory.item("a/b.apk")!!).use { it.readBytes() })
    }

    @Test fun damageIsCaughtWhileReadingAndNotLater() {
        val archive = archiveOf(listOf(ArchiveEntry("a/b.apk", body)))
        archive[100] = (archive[100] + 1).toByte()
        val directory = assertNotNull(directoryOf(archive))
        val problem = assertFailsWith<ToolboxException> { directory.open(directory.item("a/b.apk")!!).use { it.readBytes() } }
        assertEquals("package_unreadable", problem.code)
    }

    @Test fun whatIsNoArchiveIsSaidToBeNone() {
        assertNull(directoryOf(ByteArray(5)))
        assertNull(directoryOf(ByteArray(70_000) { 7 }))
    }

    @Test fun aDirectoryOutOfPlaceIsDamageNotACrash() {
        val archive = archiveOf(listOf(ArchiveEntry("a/b.apk", body)))
        val pastTheEnd = archive.copyOf().also { it[it.size - 3] = 0x7f } // Top byte of the directory's offset.
        assertEquals("package_unreadable", assertFailsWith<ToolboxException> { directoryOf(pastTheEnd) }.code)
        val tooEarly = archive.copyOf().also { it[it.size - 6] = 0x7f } // Reads as bytes stuck on the front.
        val directory = assertNotNull(directoryOf(tooEarly))
        val problem = assertFailsWith<ToolboxException> { directory.open(directory.item("a/b.apk")!!) }
        assertEquals("package_unreadable", problem.code)
    }

    @Test fun manifestsAreReadInBothStringEncodings() {
        val expected = ApkManifest("dev.example", "config.xhdpi", "1.2")
        assertEquals(expected, readApkManifest(binaryManifest("dev.example", "config.xhdpi", "1.2", utf8 = true)))
        assertEquals(expected, readApkManifest(binaryManifest("dev.example", "config.xhdpi", "1.2", utf8 = false)))
        assertEquals(ApkManifest("dev.example", null, null), readApkManifest(binaryManifest("dev.example")))
    }

    @Test fun anInvalidManifestIsNotACrash() {
        assertNull(readApkManifest(ByteArray(3)))
        assertNull(readApkManifest(ByteArray(40) { 3 }))
        assertNull(readApkManifest(binaryManifest("dev.example").copyOf(50)))
    }
}
