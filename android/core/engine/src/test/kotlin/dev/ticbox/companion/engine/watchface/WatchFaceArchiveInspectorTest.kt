package dev.ticbox.companion.engine.watchface

import dev.ticbox.companion.api.WatchFaceArchive
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.*

class WatchFaceArchiveInspectorTest {
    @Test fun `ZIP validation hashes the complete encoded file and checks CRC without extraction`() = runBlocking {
        val bytes = WatchFaceManagementEngineTest.zip("face/config.json", "{}".toByteArray())
        val result = WatchFaceArchiveInspector.inspect(source(bytes))
        assertEquals(bytes.size.toLong(), result.bytes)
        assertEquals(1, result.entries); assertEquals(2L, result.expandedBytes)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }, result.sha256)
    }
    @Test fun `ZIP traversal malformed archives and local resource excess are rejected before transfer`(): Unit = runBlocking {
        for (name in listOf("../escape", "/absolute", "C:/escape", "dir/../../escape", "dir\\..\\escape")) {
            val bytes = WatchFaceManagementEngineTest.zip(name, byteArrayOf(1))
            assertFailsWith<IllegalArgumentException> { WatchFaceArchiveInspector.inspect(source(bytes)) }
        }
        val bytes = WatchFaceManagementEngineTest.zip("face/config.json", ByteArray(4096))
        assertFailsWith<IllegalArgumentException> { WatchFaceArchiveInspector.inspect(source(bytes), maxBytes = 1) }
        assertFailsWith<IllegalArgumentException> { WatchFaceArchiveInspector.inspect(source(bytes), maxExpandedBytes = 16) }
        assertFailsWith<IllegalArgumentException> { WatchFaceArchiveInspector.inspect(source(byteArrayOf(1, 2, 3))) }
    }
    @Test fun `local stream ownership closes an interrupted blocking read`() = runBlocking {
        val entered = CountDownLatch(1); val released = CountDownLatch(1)
        val input = object : InputStream() {
            override fun read(): Int { entered.countDown(); released.await(); return -1 }
            override fun close() { released.countDown() }
        }
        val job = launch { localFileIo(input) { input.read() } }
        withContext(Dispatchers.IO) { assertTrue(entered.await(2, TimeUnit.SECONDS)) }
        withTimeout(2_000) { job.cancelAndJoin() }
        assertEquals(0L, released.count)
    }
    @Test fun `Mobvoi image parser keeps signed bytes and ignores unrelated JSON fields`() {
        val (name, image) = WatchFaceMobvoiJson.image("{\"class_name\":\"Face\",\"image\":[-1,-128,0,127],\"future\":true}".toByteArray())
        assertEquals("Face", name); assertContentEquals(byteArrayOf(-1, -128, 0, 127), image!!.bytes)
        assertNull(WatchFaceMobvoiJson.image("{\"image\":[\"future\"]}".toByteArray()).second)
        assertEquals(1, WatchFaceMobvoiJson.settings("{\"category\":1,\"future\":[]}".toByteArray()).category)
        // WatchFaceBean's own no-argument constructor initializes category=2.
        assertEquals(2, WatchFaceMobvoiJson.settings("{}".toByteArray()).category)
        assertEquals("{\"category\":2}", WatchFaceMobvoiJson.encode(dev.ticbox.companion.api.WatchFacePhotoSettings()).toString(Charsets.UTF_8))
    }
    private fun source(bytes: ByteArray) = object : WatchFaceArchive {
        override val sizeBytes = bytes.size.toLong()
        override fun open() = ByteArrayInputStream(bytes)
    }
}
