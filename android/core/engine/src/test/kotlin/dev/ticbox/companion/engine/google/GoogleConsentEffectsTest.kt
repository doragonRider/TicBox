@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.ticbox.companion.engine.google

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.CompanionDataInitializer
import dev.ticbox.companion.engine.data.*
import dev.ticbox.companion.protocol.*
import kotlin.test.*
import kotlinx.coroutines.test.*

class GoogleConsentEffectsTest {
    @Test fun `diagnostic consent is durable in GMS namespace and preserves unrelated original bytes`() = runTest {
        val data = activeData()
        val original = DataMapCodec.encode(mapOf("unrelated" to DataMapValue.Bytes(byteArrayOf(1, 2, 3)))) + byteArrayOf(0x98.toByte(), 0x06, 0x07)
        data.publish(SCOPE, CompanionDataInitializer.GMS_ROUTE, DataItemDraft("wear://watch/system_logging/settings", original))
        val effects = GoogleConsentEffects(GoogleAccountTransferEngineTest.FakeProvider(), data)
        val result = effects.apply(PEER, 7, listOf(SetupConsent(SetupOptinType.LOGGING, SetupOptinChoice.DECLINED, 1)))
        assertEquals(GoogleServiceStatus.COMPLETE, result.single().status)
        val published = data.items(SCOPE, DataItemQuery(CompanionDataInitializer.GMS_ROUTE, "wear://phone/system_logging/settings")).items.single()
        assertTrue(published.local)
        val body = published.data!!
        assertContentEquals(original, body.copyOfRange(0, original.size))
        val document = DataMapCodec.decode(body)
        assertEquals(DataMapValue.Bool(false), document.values["system_logging_enabled"])
        assertContentEquals(byteArrayOf(1, 2, 3), (document.values["unrelated"] as DataMapValue.Bytes).value)
        assertEquals(99, document.unknownFields.single().number)
        assertFalse(data.state.value.initialSyncCompletionObserved)
        data.close()
    }
    @Test fun `cloud refusal leaves explicit result while diagnostic consent still publishes and skip changes nothing`() = runTest {
        val data = activeData()
        val provider = GoogleAccountTransferEngineTest.FakeProvider().apply {
            failure = GoogleServiceException(GoogleServiceStatus.PERMISSION_DENIED, "gms_wearable_permission_denied")
        }
        val effects = GoogleConsentEffects(provider, data)
        val result = effects.apply(PEER, 7, listOf(
            SetupConsent(SetupOptinType.CLOUDSYNC, SetupOptinChoice.ACCEPTED, 1),
            SetupConsent(SetupOptinType.LOGGING, SetupOptinChoice.ACCEPTED, 1),
            SetupConsent(SetupOptinType.CLOUDSYNC, SetupOptinChoice.SKIPPED, 1),
        ))
        assertEquals(listOf(GoogleServiceStatus.PERMISSION_DENIED, GoogleServiceStatus.COMPLETE, GoogleServiceStatus.SKIPPED), result.map { it.status })
        assertEquals(listOf("opt_in" to true), provider.cloudCalls)
        assertEquals(1, data.items(SCOPE).items.size)
        data.close()
    }
    @Test fun `malformed old diagnostic map fails visibly and cannot erase the existing record`() = runTest {
        val data = activeData()
        data.publish(SCOPE, CompanionDataInitializer.GMS_ROUTE, DataItemDraft("wear://watch/system_logging/settings", byteArrayOf(0x0a, 0x7f)))
        val effects = GoogleConsentEffects(GoogleAccountTransferEngineTest.FakeProvider(), data)
        assertEquals(GoogleServiceStatus.FAILED, effects.apply(PEER, 7, listOf(SetupConsent(SetupOptinType.LOGGING, SetupOptinChoice.ACCEPTED, 1))).single().status)
        assertEquals(1, data.items(SCOPE).items.size)
        data.close()
    }
    private suspend fun TestScope.activeData(): DataSyncEngine {
        val data = DataSyncEngine(backgroundScope, MemoryDataSyncStore())
        data.attach(DataSyncSession(PEER, 7, "phone", "watch", "net", APP, {}, negotiatedWireVersion = 1))
        runCurrent()
        assertTrue(data.acceptInbound(7, GmsStructs.encode("WearMessage", mapOf("syncStart" to mapOf("version" to 2, "receivedSequenceId" to -1L, "syncTable" to emptyList<Any>())))))
        runCurrent()
        assertEquals(DataSyncPhase.ACTIVE, data.state.value.phase)
        return data
    }
    private companion object {
        val PEER = PeerAddress("AA:BB:CC:DD:EE:FF")
        val SCOPE = DataScope(PEER, "phone", "watch", "net")
        val APP = ApplicationRoute("companion.fixture", "digest")
    }
}
