@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.ticbox.companion.engine

import dev.ticbox.companion.api.*
import dev.ticbox.companion.engine.data.*
import dev.ticbox.companion.protocol.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*
import kotlinx.coroutines.test.*

class CompanionDataInitializerTest {
    private val peer = PeerAddress("AA:BB:CC:DD:EE:FF")
    private val route = ApplicationRoute("app", "digest")
    private val scope = DataScope(peer, "phone", "watch", "net")
    private val clock = Clock.fixed(Instant.ofEpochMilli(1_234), ZoneOffset.UTC)

    @Test fun `settings and static capability persist in their own namespace and identical values are not republished`() = runTest {
        val store = MemoryDataSyncStore()
        val engine = DataSyncEngine(backgroundScope, store)
        engine.attach(DataSyncSession(peer, 1, "phone", "watch", "net", route, {}, negotiatedWireVersion = 1)); runCurrent()
        engine.acceptInbound(1, DataProtocol.syncStart(emptyMap(), "phone")); runCurrent()
        val initializer = CompanionDataInitializer(engine, route, { "zh-CN,en-US" }, { true }, 37, clock = clock)
        initializer.prepare(1)
        val all = engine.items(scope).items
        assertEquals(4, all.size)
        val capability = all.single { it.uri.contains("/capabilities/") }
        assertEquals(CompanionDataInitializer.GMS_ROUTE, capability.route)
        assertContentEquals(byteArrayOf(0x73), capability.data)
        val locales = all.single { it.uri.endsWith(CompanionSettingsCodec.LOCALES_PATH) }
        assertEquals(route, locales.route)
        val wrapper = ProtoWire.decodeFields(locales.data!!)
        val value = ProtoWire.decodeFields(wrapper.first { it.number == 1 }.value as ByteArray)
        val localeProto = ProtoWire.decodeFields(value.single().value as ByteArray)
        assertEquals("zh-CN,en-US", (localeProto.single().value as ByteArray).toString(Charsets.UTF_8))
        initializer.prepare(1)
        assertEquals(all.map { it.sequenceId }, engine.items(scope).items.map { it.sequenceId })
        assertFalse(engine.state.value.initialSyncCompletionObserved)
        engine.close()
    }

    @Test fun `timestamp beats later peer clock while consent and TOS require explicit submission`() = runTest {
        val store = MemoryDataSyncStore(); val engine = DataSyncEngine(backgroundScope, store)
        engine.attach(DataSyncSession(peer, 1, "phone", "watch", "net", route, {}, negotiatedWireVersion = 1)); runCurrent()
        engine.acceptInbound(1, DataProtocol.syncStart(emptyMap(), "phone")); runCurrent()
        engine.publish(scope, route, DataItemDraft("wear://phone${CompanionSettingsCodec.TIME_FORMAT_PATH}",
            CompanionSettingsCodec.setting(CompanionSettingsCodec.booleanValue(false), 9_999)))
        val initializer = CompanionDataInitializer(engine, route, { "en-US" }, { true }, 37, clock = clock)
        initializer.prepare(1)
        var all = engine.items(scope).items
        assertEquals(10_000L, CompanionSettingsCodec.changedAt(all.single { it.uri.endsWith(CompanionSettingsCodec.TIME_FORMAT_PATH) }.data!!))
        assertFalse(all.any { it.uri.endsWith(CompanionSettingsCodec.TERMS_AUDIT_PATH) || it.uri.contains("/location/") })
        initializer.prepare(1, termsAccepted = true, consents = listOf(SetupConsent(SetupOptinType.LOCATION, SetupOptinChoice.DECLINED, 1_200)))
        all = engine.items(scope).items
        val location = all.filter { it.uri.contains("/location/") }
        assertEquals(2, location.size)
        assertTrue(location.all { it.uri.endsWith("/watch") })
        assertTrue(location.all { CompanionSettingsCodec.value(it.data!!)!!.contentEquals(byteArrayOf(0x10, 0)) })
        val terms = all.single { it.uri.endsWith(CompanionSettingsCodec.TERMS_AUDIT_PATH) }
        assertContentEquals(hex("0a04080110011207080110809dca6f18012001"), terms.data)
        engine.detach(1)
        assertFailsWith<NotConnectedException> { initializer.prepare(1, termsAccepted = true) }
        engine.close()
    }
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
