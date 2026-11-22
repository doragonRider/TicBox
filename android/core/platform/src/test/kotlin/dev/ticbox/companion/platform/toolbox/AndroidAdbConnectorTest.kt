package dev.ticbox.companion.platform.toolbox

import dev.ticbox.companion.api.AdbDiscoveredEndpoint
import dev.ticbox.companion.api.AdbEndpoint
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AndroidAdbConnectorTest {
    @Test fun disconnectingByChoiceStopsConnectingByItselfAcrossRestartsUntilTheNextConnection() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val watch = AdbEndpoint("192.0.2.9", 41001)
        val found = listOf(AdbDiscoveredEndpoint("adb-watch", AdbEndpoint("192.0.2.10", 42002), false))
        val connector = AndroidAdbConnector(context)
        connector.select(watch, "adb-watch")
        assertEquals(watch, connector.reconnectable)
        assertTrue(connector.markLast(found).single().autoConnect)

        connector.stayDisconnected()
        assertNull(connector.reconnectable)
        assertFalse(connector.markLast(found).single().autoConnect)
        // The app started again: the choice and the address are both still there.
        val restarted = AndroidAdbConnector(context)
        assertNull(restarted.reconnectable)
        assertFalse(restarted.markLast(found).single().autoConnect)
        assertEquals("192.0.2.9:41001", restarted.device)

        restarted.select(watch, "adb-watch")
        assertEquals(watch, AndroidAdbConnector(context).reconnectable)
    }
}
