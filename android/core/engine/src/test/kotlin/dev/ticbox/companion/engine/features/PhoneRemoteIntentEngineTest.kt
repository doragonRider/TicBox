@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.ticbox.companion.engine.features

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.CompanionCapabilities
import dev.ticbox.companion.engine.settings.*
import dev.ticbox.companion.protocol.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

class PhoneRemoteIntentEngineTest {
    private fun request(vararg fields: Pair<String, DataMapValue>): ByteArray = DataMapCodec.encode(mapOf(
        "action" to DataMapValue.Text("android.intent.action.VIEW"),
        NotificationProtocol.INVOCATION_ID to DataMapValue.Text("watch-request-42"),
    ) + fields)

    @Test fun `phone intent is executed and result preserves the watch callback id`() = runTest {
        val data = FeatureTestData(); val executed = mutableListOf<RemoteIntentRequest>()
        val engine = PhoneRemoteIntentEngine(PhoneRemoteIntentExecutor { decoded -> executed += decoded; true })
        engine.attach(data.session())
        assertTrue(engine.handleMessage(data.scope.peer, 1, DeviceSettingsProtocol.REMOTE_INTENT,
            request("uri_data" to DataMapValue.Text("https://example.test/path")), data.route))
        assertEquals("https://example.test/path", executed.single().uri)
        val reply = data.sent.single()
        assertEquals(data.route, reply.first)
        assertEquals("/remote_intent/result", reply.second)
        val result = NotificationRpcCodec.decodeResult(reply.third)
        assertEquals("watch-request-42", result.invocationId)
        assertEquals(1, result.messageId)
        assertEquals(true, result.successful)
        engine.close()
    }

    @Test fun `executor refusal and exceptions reply false while a later request still works`() = runTest {
        val data = FeatureTestData(); val logs = mutableListOf<ProtocolDiagnostic>(); var calls = 0
        val engine = PhoneRemoteIntentEngine(PhoneRemoteIntentExecutor {
            calls++
            when(calls) { 1 -> false; 2 -> throw SecurityException("not exported"); else -> true }
        }, DiagnosticSink { logs += it })
        engine.attach(data.session())
        repeat(3) { engine.handleMessage(data.scope.peer, 1, DeviceSettingsProtocol.REMOTE_INTENT, request(), data.route) }
        assertEquals(listOf(false, false, true), data.sent.map { NotificationRpcCodec.decodeResult(it.third).successful })
        assertTrue(logs.any { it.event == "dispatch_failed" && it.details["exception"] == "SecurityException" })
        engine.close()
    }

    @Test fun `unfamiliar fields survive and unknown modes use the stock activity fallback`() = runTest {
        val data = FeatureTestData(); var received: RemoteIntentRequest? = null
        val logs = mutableListOf<ProtocolDiagnostic>()
        val engine = PhoneRemoteIntentEngine(PhoneRemoteIntentExecutor { received = it; true }, DiagnosticSink { logs += it })
        engine.attach(data.session())
        engine.handleMessage(data.scope.peer, 1, DeviceSettingsProtocol.REMOTE_INTENT, request(
            "start_mode" to DataMapValue.IntNumber(91), "new_vendor_field" to DataMapValue.LongNumber(7),
            "categories" to DataMapValue.Strings(listOf("android.intent.category.BROWSABLE")),
            "extras" to DataMapValue.Text("unusable optional field")), data.route)
        val decoded = assertNotNull(received)
        assertEquals(91, decoded.startMode)
        assertEquals(0, decoded.effectiveStartMode)
        assertEquals(DataMapValue.LongNumber(7), decoded.document.values["new_vendor_field"])
        assertEquals(listOf("android.intent.category.BROWSABLE"), decoded.categories)
        assertTrue(decoded.extras.isEmpty())
        assertTrue(logs.any { it.event == "request_notices" })
        assertEquals(true, NotificationRpcCodec.decodeResult(data.sent.single().third).successful)
        engine.close()
    }

    @Test fun `messages from a different session peer or application are never dispatched`() = runTest {
        val data = FeatureTestData(); var calls = 0
        val engine = PhoneRemoteIntentEngine(PhoneRemoteIntentExecutor { calls++; true })
        engine.attach(data.session())
        assertFalse(engine.handleMessage(data.scope.peer, 2, DeviceSettingsProtocol.REMOTE_INTENT, request(), data.route))
        assertFalse(engine.handleMessage(PeerAddress("AA:BB:CC:DD:EE:02"), 1, DeviceSettingsProtocol.REMOTE_INTENT, request(), data.route))
        assertFalse(engine.handleMessage(data.scope.peer, 1, DeviceSettingsProtocol.REMOTE_INTENT, request(), ApplicationRoute("other.app", "digest")))
        assertFalse(engine.handleMessage(data.scope.peer, 1, DeviceSettingsProtocol.REMOTE_INTENT_RESULT, request(), data.route))
        engine.detach(1)
        assertFalse(engine.handleMessage(data.scope.peer, 1, DeviceSettingsProtocol.REMOTE_INTENT, request(), data.route))
        assertEquals(0, calls)
        assertTrue(data.sent.isEmpty())
        engine.close()
    }

    @Test fun `completion from an old session cannot reply on the replacement link`() = runTest {
        val data = FeatureTestData(); val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Boolean>()
        val engine = PhoneRemoteIntentEngine(PhoneRemoteIntentExecutor { started.complete(Unit); finish.await() })
        engine.attach(data.session())
        val pending = async { engine.handleMessage(data.scope.peer, 1, DeviceSettingsProtocol.REMOTE_INTENT, request(), data.route) }
        started.await()
        engine.attach(data.session(2))
        finish.complete(true)
        assertTrue(pending.await())
        assertTrue(data.sent.isEmpty())
        engine.close()
    }

    @Test fun `unreadable request is isolated and does not fabricate a callback`() = runTest {
        val data = FeatureTestData(); var calls = 0
        val engine = PhoneRemoteIntentEngine(PhoneRemoteIntentExecutor { calls++; true })
        engine.attach(data.session())
        assertTrue(engine.handleMessage(data.scope.peer, 1, DeviceSettingsProtocol.REMOTE_INTENT, byteArrayOf(0x80.toByte()), data.route))
        assertEquals(0, calls)
        assertTrue(data.sent.isEmpty())
        assertTrue(engine.handleMessage(data.scope.peer, 1, DeviceSettingsProtocol.REMOTE_INTENT, request(), data.route))
        assertEquals(1, calls)
        assertEquals(true, NotificationRpcCodec.decodeResult(data.sent.single().third).successful)
        engine.close()
    }

    @Test fun `connection capability is a real dynamic publication under the companion key`() = runTest {
        val data = FeatureTestData(); val registry = CompanionCapabilities(backgroundScope, data)
        val engine = PhoneRemoteIntentEngine(PhoneRemoteIntentExecutor { true })
        engine.attach(data.session())
        registry.setPhoneCapability(RemoteIntentCodec.CAPABILITY, true)
        registry.attach(data.session()); runCurrent()
        val item = data.published.single()
        assertEquals(CompanionCapabilities.GMS_ROUTE, item.route)
        assertEquals("wear://phone/capabilities/companion.test/digest/handle_remote_intent", item.uri)
        assertContentEquals(byteArrayOf(0x64), item.data)
        engine.handleMessage(data.scope.peer, 1, DeviceSettingsProtocol.REMOTE_INTENT, request(), data.route)
        assertEquals(true, NotificationRpcCodec.decodeResult(data.sent.single().third).successful)
        registry.close(); engine.close()
    }
}
