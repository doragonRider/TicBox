@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.ticbox.companion.engine.features

import dev.ticbox.companion.engine.CompanionCapabilities
import kotlin.test.*
import kotlinx.coroutines.test.*

class CompanionCapabilitiesTest {
    @Test fun `dynamic phone capability is deduplicated withdrawn on revocation and replayed for current scope`() = runTest {
        val data = FeatureTestData(); val registry = CompanionCapabilities(backgroundScope, data)
        registry.setPhoneCapability("full_companion_call_control_capability", true)
        registry.attach(data.session()); runCurrent()
        assertEquals(1, data.published.size)
        assertEquals(CompanionCapabilities.GMS_ROUTE, data.published.single().route)
        assertContentEquals(byteArrayOf(0x64), data.published.single().data)
        registry.setPhoneCapability("full_companion_call_control_capability", true); runCurrent()
        assertEquals(1, data.published.size)
        registry.setPhoneCapability("full_companion_call_control_capability", false); runCurrent()
        assertTrue(data.published.last().deleted)
        assertEquals(2, data.published.size)
        registry.close()
    }
    @Test fun `only watch source capability data items become observed support`() = runTest {
        val data = FeatureTestData(); val registry = CompanionCapabilities(backgroundScope, data)
        registry.attach(data.session()); runCurrent()
        data.remote("/capabilities/${data.route.packageName}/${data.route.packageDigest}/media_notification_message_watch",
            byteArrayOf(0x64), CompanionCapabilities.GMS_ROUTE); runCurrent()
        assertEquals(setOf("media_notification_message_watch"), registry.watch.value)
        registry.detach(1); runCurrent(); assertTrue(registry.watch.value.isEmpty())
        registry.close()
    }
    @Test fun `unknown markers remain dynamic and names preserve slash percent and literal plus`() = runTest {
        val data = FeatureTestData(); val registry = CompanionCapabilities(backgroundScope, data)
        val name = "mdm/Ringable+%x"
        registry.attach(data.session())
        data.remote("/capabilities/${data.route.packageName}/${data.route.packageDigest}/mdm%252FRingable%252B%2525x",
            byteArrayOf(), CompanionCapabilities.GMS_ROUTE); runCurrent()
        assertEquals(setOf(name), registry.watch.value)
        registry.setPhoneCapability(name, true); runCurrent()
        assertTrue(data.published.last().uri.endsWith("mdm%252FRingable%252B%2525x"))
        assertEquals(name, CompanionCapabilities.decodeName(CompanionCapabilities.encodeName(name)))
        registry.close()
    }
}
