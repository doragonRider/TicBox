package dev.ticbox.companion.healthbridge

import android.app.Application
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import dev.ticbox.companion.protocol.LocalHealthTable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], application = Application::class)
class HealthExportDownloadsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: DownloadsFixtureProvider
    private val downloads get() = HealthExportDownloads(context.contentResolver)

    @Before fun setup() { provider = DownloadsFixtureProvider.install(context) }
    @After fun cleanup() { provider.shutdown() }

    @Test fun publishesUtf8OnlyAfterWritingAndReportsTheNameChosenByDownloads() {
        provider.renameOnInsert = true
        val json = """{"synthetic":"睡眠🙂","summary":{"status":"complete"}}"""
        val saved = downloads.write { output ->
            assertTrue(provider.entries.values.all { it.values.getAsInteger(MediaStore.Downloads.IS_PENDING) == 1 })
            output.write(json.toByteArray(Charsets.UTF_8))
            "complete"
        }
        val entry = provider.entries.values.single()
        assertEquals("complete", saved.result)
        assertEquals("application/json", entry.values.getAsString(MediaStore.Downloads.MIME_TYPE))
        assertEquals(0, entry.values.getAsInteger(MediaStore.Downloads.IS_PENDING))
        assertEquals("Download/TicBox/${entry.values.getAsString(MediaStore.Downloads.DISPLAY_NAME)}", saved.relativePath)
        assertTrue(saved.relativePath.endsWith("-resolved.json"))
        assertEquals(json, entry.file.readText(Charsets.UTF_8))
    }

    @Test fun anotherExportGetsItsOwnFileAndDoesNotReplaceEarlierDownloads() {
        val first = downloads.write { it.write("first".toByteArray()) }
        val second = downloads.write { it.write("second".toByteArray()) }
        assertNotEquals(first.relativePath, second.relativePath)
        assertEquals(listOf("first", "second"), provider.entries.values.map { it.file.readText() })
        assertTrue(provider.entries.values.all { it.values.getAsInteger(MediaStore.Downloads.IS_PENDING) == 0 })
    }

    @Test fun aWriterFailureRemovesOnlyItsUnfinishedFile() {
        downloads.write { it.write("earlier".toByteArray()) }
        assertFailsWith<IOException> {
            downloads.write { it.write("unfinished".toByteArray()); throw IOException("synthetic write failure") }
        }
        assertEquals(listOf("earlier"), provider.entries.values.map { it.file.readText() })
        assertEquals(1, provider.directory.listFiles()!!.size)
    }

    @Test fun failureToOpenOrPublishDoesNotLeaveAnIncompleteDownload() {
        provider.denyOpen = true
        assertFailsWith<FileNotFoundException> { downloads.write { it.write(1) } }
        assertTrue(provider.entries.isEmpty())
        provider.denyOpen = false
        provider.rejectPublish = true
        assertFailsWith<IOException> { downloads.write { it.write(2) } }
        assertTrue(provider.entries.isEmpty())
        assertTrue(provider.directory.listFiles()!!.isEmpty())
    }

    @Test fun missingDownloadsFailsWithoutReadingHealthData() {
        provider.rejectInsert = true
        var readHealth = false
        assertFailsWith<IOException> { downloads.write { readHealth = true } }
        assertFalse(readHealth)
        assertTrue(provider.entries.isEmpty())
    }

    @Test fun cancellationStillPublishesAValidDocumentWithAnExplicitCancelledStatus() {
        val signal = CancellationSignal().apply { cancel() }
        val saved = downloads.write { output ->
            HealthJsonExporter({ _, _ -> error("Cancelled export must not read health rows") }, { buildJsonObject {} })
                .write(output, HealthExportSelection(mapOf(LocalHealthTable.POINT to emptySet())), signal)
        }
        val document = Json.parseToJsonElement(provider.entries.values.single().file.readText()).jsonObject
        assertEquals("cancelled", saved.result.status)
        assertEquals("cancelled", document["summary"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(0, provider.entries.values.single().values.getAsInteger(MediaStore.Downloads.IS_PENDING))
    }
}

/** Synthetic MediaStore contract with real file descriptors; no vendor health files or system MediaProvider. */
class DownloadsFixtureProvider : ContentProvider() {
    data class Entry(val values: ContentValues, val file: File)
    val entries = linkedMapOf<Long, Entry>()
    lateinit var directory: File
    var rejectInsert = false
    var denyOpen = false
    var rejectPublish = false
    var renameOnInsert = false
    /** Holds the writer at its first file access, as a slow export would. */
    var holdOpen: CountDownLatch? = null
    val removed = CountDownLatch(1)
    private var nextId = 1L

    override fun onCreate(): Boolean {
        directory = File(context!!.cacheDir, "fixture-downloads-${UUID.randomUUID()}").apply { mkdirs() }
        return true
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        check(uri == MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
        if (rejectInsert) return null
        val stored = ContentValues(values!!)
        if (renameOnInsert) stored.put(MediaStore.Downloads.DISPLAY_NAME,
            stored.getAsString(MediaStore.Downloads.DISPLAY_NAME).removeSuffix(".json") + "-resolved.json")
        val id = nextId++
        entries[id] = Entry(stored, File(directory, "$id.json"))
        return ContentUris.withAppendedId(uri, id)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        holdOpen?.await(10, TimeUnit.SECONDS)
        if (denyOpen) throw FileNotFoundException("Synthetic unavailable download")
        check(mode == "w")
        return ParcelFileDescriptor.open(entries.getValue(ContentUris.parseId(uri)).file,
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val entry = entries.getValue(ContentUris.parseId(uri))
        return MatrixCursor(projection!!).apply { addRow(projection.map { entry.values.getAsString(it) }) }
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        if (rejectPublish) return 0
        entries.getValue(ContentUris.parseId(uri)).values.putAll(values!!)
        return 1
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val entry = entries.remove(ContentUris.parseId(uri)) ?: return 0
        entry.file.delete()
        removed.countDown()
        return 1
    }

    override fun getType(uri: Uri) = "application/json"
    override fun shutdown() { directory.deleteRecursively(); super.shutdown() }

    companion object {
        fun install(context: Context): DownloadsFixtureProvider = DownloadsFixtureProvider().apply {
            attachInfo(context, ProviderInfo().apply { authority = MediaStore.AUTHORITY; exported = true })
            ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, this)
        }
    }
}
