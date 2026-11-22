package dev.ticbox.companion.healthbridge

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.net.Uri
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DndListenerServiceTest {
    @Test fun offDoesNotRequestRootWhenThisInstallationNeverOwnedARule() {
        val service = Robolectric.buildService(DndListenerService::class.java).create().get()
        assertFalse(service.apply("phone", 1, false))
        assertFalse(service.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted)
        assertNull(service.getSharedPreferences("dnd-follow-rule", 0).getString("ruleId", null))
    }
    @Test fun ownRuleCanBeReleasedWhileManualAndOtherAppsRulesStayEnabled() {
        val service = Robolectric.buildService(DndListenerService::class.java).create().get()
        val manager = service.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationPolicyAccessGranted(true)
        manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        val other = manager.addAutomaticZenRule(AutomaticZenRule("Existing schedule", null, ComponentName(service, BridgeSetupActivity::class.java),
            Uri.parse("condition://other/schedule"), null, NotificationManager.INTERRUPTION_FILTER_PRIORITY, true))
        assertTrue(service.apply("phone", 2, true))
        val own = manager.automaticZenRules.keys.single { it != other }
        assertFalse(service.apply("phone", 3, false))
        assertFalse(manager.getAutomaticZenRule(own)!!.isEnabled)
        assertTrue(manager.getAutomaticZenRule(other!!)!!.isEnabled)
        assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, manager.currentInterruptionFilter)
        assertFails { service.apply("phone", 2, true) }
        assertFalse(manager.getAutomaticZenRule(own)!!.isEnabled)
    }
}
