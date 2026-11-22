@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.ticbox.companion.platform.toolbox

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.toolbox.*
import dev.ticbox.companion.protocol.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AndroidDndSyncTest {
    private val link = DndLink(PeerAddress("AA:BB:CC:DD:EE:01"), 7)
    @Test fun acknowledgementsMustMatchRequestRevisionPeerAndPhysicalSession() = runTest {
        lateinit var request: JsonObject
        val remote = AndroidDndRemote { _, bytes -> request = ToolboxProtocol.json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }
        val result = async { remote.apply(link, 99, true) }; runCurrent()
        fun response(revision: Long = 99) = buildJsonObject { put("requestId", request.text("requestId")); put("revision", revision); put("active", true) }.toString().toByteArray()
        remote.accept(link.peer, 6, response()); remote.accept(PeerAddress("AA:BB:CC:DD:EE:02"), 7, response()); remote.accept(link.peer, 7, response(98))
        runCurrent(); assertFalse(result.isCompleted)
        remote.accept(link.peer, 7, response()); runCurrent(); assertTrue(result.await()); remote.close()
    }
    @Test fun timeoutIsRetryableAndLateReceiptCannotSatisfyAReplacementRequest() = runTest {
        val requests = mutableListOf<JsonObject>()
        val remote = AndroidDndRemote { _, bytes -> requests += ToolboxProtocol.json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }
        val first = async { runCatching { remote.apply(link, 1, true) } }; runCurrent(); advanceTimeBy(75_001); runCurrent()
        assertEquals("dnd_ack_timeout", (first.await().exceptionOrNull() as ToolboxException).code)
        val next = async { remote.apply(link, 2, false) }; runCurrent()
        remote.accept(link.peer, 7, buildJsonObject { put("requestId", requests[0].text("requestId")); put("revision", 1); put("active", true) }.toString().toByteArray())
        runCurrent(); assertFalse(next.isCompleted); next.cancelAndJoin(); remote.close()
    }
    @Test fun durableModeOwnershipAndBridgeKeySurviveNewServiceInstances() {
        val context = RuntimeEnvironment.getApplication()
        val expected = DndSettings(DndSyncMode.OFF, 29, setOf(link.peer.value))
        AtomicDndSettings(context).save(expected); assertEquals(expected, AtomicDndSettings(context).load())
        assertEquals(bridgeKey(context), bridgeKey(context)); assertEquals(64, bridgeKey(context).length)
        File(context.noBackupFilesDir, "companion/toolbox-bridge-key.bin").writeBytes(byteArrayOf(1))
        assertEquals("bridge_identity_corrupt", assertFailsWith<ToolboxException> { bridgeKey(context) }.code)
    }
    @Test fun corruptSettingsAreNotSilentlyReplacedWithOffAndForgottenOwnership() {
        val context = RuntimeEnvironment.getApplication()
        File(context.noBackupFilesDir, "companion/dnd-v1.json").apply { parentFile!!.mkdirs(); writeText("{broken") }
        assertFails { AtomicDndSettings(context).load() }
    }
}
