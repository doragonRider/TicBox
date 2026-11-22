package dev.ticbox.companion.healthbridge

import android.app.Application
import android.os.CancellationSignal
import androidx.test.core.app.ApplicationProvider
import dev.ticbox.companion.protocol.*
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FitnessHelperSessionTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    /** A helper process whose other end runs [helper] on a thread of its own. */
    private class FakeHelper(helper: (InputStream, OutputStream) -> Unit) : Process() {
        private val toHelper = PipedOutputStream(); private val helperIn = PipedInputStream(toHelper, 1 shl 20)
        private val fromHelper = PipedOutputStream(); private val ours = PipedInputStream(fromHelper, 1 shl 20)
        private val worker = thread(isDaemon = true) { try { helper(helperIn, fromHelper) } catch (_: Exception) { } finally { fromHelper.close() } }
        @Volatile var destroyed = false
        override fun getOutputStream(): OutputStream = toHelper
        override fun getInputStream(): InputStream = ours
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int { worker.join(); return 0 }
        override fun exitValue() = 0
        override fun destroy() { destroyed = true; runCatching { toHelper.close() }; runCatching { fromHelper.close() } }
    }

    private fun page(table: String) = buildJsonObject { put("table", table) }

    @Test fun oneHelperAnswersEveryReadOfTheWindow() {
        val launches = mutableListOf<String>()
        val session = FitnessHelperSession(context) { command ->
            launches += command
            FakeHelper { input, output ->
                LocalHealthProtocol.write(output, buildJsonObject { put("ready", true) })
                FitnessHelperMain.serve(input, output) { request ->
                    when (request.text("kind")) {
                        "page" -> buildJsonObject { put("rows", JsonArray(emptyList())); put("table", request.getValue("page").jsonObject.text("table")!!) }
                        else -> buildJsonObject { put("head", "7"); put("action", request.getValue("journal").jsonObject.text("action")!!) }
                    }
                }
            }
        }
        session.use {
            assertEquals("POINT", it.page(page("POINT"), CancellationSignal())!!.text("table"))
            assertEquals("RECORD", it.page(page("RECORD"), CancellationSignal())!!.text("table"))
            assertEquals("read", it.journal(buildJsonObject { put("source", "fitness"); put("action", "read") }, CancellationSignal())!!.text("action"))
        }
        assertEquals(1, launches.size)
        assertTrue(launches.single().endsWith("dev.ticbox.companion.healthbridge.FitnessHelperMain"))
    }

    @Test fun aFailedReadIsTheHelpersReasonNotAFallback() {
        val session = FitnessHelperSession(context) {
            FakeHelper { input, output ->
                LocalHealthProtocol.write(output, buildJsonObject { put("ready", true) })
                FitnessHelperMain.serve(input, output) { buildJsonObject { put("helperError", "snapshot_failed") } }
            }
        }
        session.use { assertEquals("snapshot_failed", assertFailsWith<FitnessSourceException> { it.page(page("POINT"), CancellationSignal()) }.code) }
    }

    @Test fun rootRefusedIsLeftToTheOneShotHelperAndTriedAgainNextTime() {
        var launches = 0
        repeat(2) {
            FitnessHelperSession(context) { launches++; FakeHelper { _, _ -> } }.use { session ->
                assertNull(session.page(page("POINT"), CancellationSignal()))
                // Once is enough for this window.
                assertNull(session.page(page("POINT"), CancellationSignal()))
            }
        }
        assertEquals(2, launches)
    }

    @Test fun aChannelThatDoesntCarryFramesIsNotTriedAgainOnThisVersion() {
        var launches = 0
        repeat(2) {
            FitnessHelperSession(context) {
                launches++
                FakeHelper { _, output -> output.write("FITNESS_ROOT_READY\n".toByteArray()); output.flush(); Thread.sleep(200) }
            }.use { session -> assertNull(session.page(page("POINT"), CancellationSignal())) }
        }
        assertEquals(1, launches)
    }

    @Test fun aHelperThatEndsBetweenReadsLeavesTheRestToTheOneShotHelper() {
        var launches = 0
        val session = FitnessHelperSession(context) {
            launches++
            FakeHelper { input, output ->
                LocalHealthProtocol.write(output, buildJsonObject { put("ready", true) })
                LocalHealthProtocol.read(input)
                LocalHealthProtocol.write(output, buildJsonObject { put("rows", JsonArray(emptyList())) })
            }
        }
        session.use {
            assertNotNull(it.page(page("POINT"), CancellationSignal()))
            assertNull(it.page(page("POINT"), CancellationSignal()))
            assertNull(it.page(page("POINT"), CancellationSignal()))
        }
        assertEquals(1, launches)
    }
}
