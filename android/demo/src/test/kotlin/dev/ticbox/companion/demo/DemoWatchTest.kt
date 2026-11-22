package dev.ticbox.companion.demo

import android.graphics.BitmapFactory
import dev.ticbox.companion.api.ConnectionPhase
import dev.ticbox.companion.api.HealthPointQuery
import dev.ticbox.companion.api.HealthPointType
import dev.ticbox.companion.api.HealthRecordSource
import dev.ticbox.companion.api.HealthSleepQuery
import dev.ticbox.companion.api.NotificationFilterMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DemoWatchTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-03T15:00:00Z"), ZoneOffset.UTC)
    private val context = RuntimeEnvironment.getApplication()

    private fun TestScope.demo() = DemoWatch(context, this, clock)

    @Test
    fun healthRowsMatchWhatTheBackendConfirms() = runTest {
        val demo = demo()
        val source = HealthRecordSource.LocalDevice("any-profile", demo.peer)
        val yesterday = LocalDate.of(2026, 11, 2)
        val from = yesterday.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val to = from + 86_400_000 - 1
        val heart =
            demo.healthRecords.points(
                HealthPointQuery(source, from, to, HealthPointType.HEART_RATE.code, limit = 500)
            )
        assertEquals(96, heart.items.size)
        assertTrue(
            heart.items.all {
                it.raw.source == source &&
                    it.values.single().semantic == "heart_rate" &&
                    it.values.single().unit == "bpm"
            }
        )

        val sleep = demo.healthRecords.sleepRecords(HealthSleepQuery(source, from, to)).items
        val night = sleep.single {
            Instant.ofEpochMilli(it.endMillis!!).atZone(ZoneOffset.UTC).toLocalDate() == yesterday
        }
        assertTrue(night.motionIntervals.isNotEmpty())
        assertEquals(night.startMillis, night.motionIntervals.first().startMillis)
        assertEquals(night.endMillis, night.motionIntervals.last().endMillis)

        val otherWatch =
            HealthRecordSource.LocalDevice(
                "any-profile",
                dev.ticbox.companion.api.PeerAddress("AA:BB:CC:DD:EE:FF"),
            )
        assertTrue(
            demo.healthRecords.points(HealthPointQuery(otherWatch, from, to)).items.isEmpty()
        )
    }

    @Test
    fun noSamplesAreInventedAfterNow() = runTest {
        val demo = demo()
        val source = HealthRecordSource.LocalDevice("p", demo.peer)
        val today =
            LocalDate.of(2026, 11, 3).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val points =
            demo.healthRecords
                .points(HealthPointQuery(source, today, today + 86_400_000 - 1, limit = 500))
                .items
        assertTrue(points.isNotEmpty())
        assertTrue(points.all { it.endMillis!! <= clock.millis() })
    }

    @Test
    fun watchFacesCarryDecodablePreviews() = runTest {
        val demo = demo()
        val faces = demo.watchFaces.state.value
        assertEquals(3, faces.favorites.size)
        assertEquals(faces.favorites.first().id, faces.current?.favoriteId)
        faces.favorites.forEach { favorite ->
            val bytes = assertNotNull(favorite.preview).bytes
            assertNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        }
        // Two faces to add: one of the samples, and the photo face, which a real watch doesn't have
        // among its favorites either.
        assertEquals(2, faces.installed.size - faces.favorites.size)
        val photo = assertNotNull(demo.watchFaces.queryPhoto().settings)
        val component = "${photo.packageName}/${photo.className}"
        assertTrue(faces.installed.any { it.componentName == component })
        assertTrue(faces.favorites.none { it.componentName == component })
    }

    @Test
    fun commandsChangeTheSimulatedState() = runTest {
        val demo = demo()
        demo.client.setStepGoal(9_000)
        assertEquals(9_000, demo.client.state.value.steps?.value?.goal)

        demo.notifications.setPackageEnabled("com.example.chat", false)
        val settings = demo.notifications.state.value.settings
        assertEquals(NotificationFilterMode.BLOCKLIST, settings.mode)
        assertFalse(settings.packageAllowed("com.example.chat"))

        demo.client.disconnect()
        assertEquals(ConnectionPhase.IDLE, demo.client.state.value.connection.phase)
        assertTrue(demo.client.state.value.battery!!.stale)
        assertEquals(demo.client.state.value.connection.peer, demo.recovery.state.value.desiredPeer)
        assertTrue(demo.recovery.state.value.paused)
        demo.recovery.resume()
        assertEquals(ConnectionPhase.LINK_READY, demo.client.state.value.connection.phase)
        assertFalse(demo.recovery.state.value.paused)
    }

    @Test
    fun aRepeatedPairingRunsThroughItsPhases() =
        runTest(StandardTestDispatcher()) {
            val demo = demo()
            val id = demo.pairing.start(dev.ticbox.companion.api.PairingOptions())
            advanceUntilIdle()
            assertEquals(id, demo.pairing.state.value.operationId)
            assertEquals(
                dev.ticbox.companion.api.PairingPhase.COMPLETED,
                demo.pairing.state.value.phase,
            )
            assertEquals(ConnectionPhase.LINK_READY, demo.client.state.value.connection.phase)
        }
}
