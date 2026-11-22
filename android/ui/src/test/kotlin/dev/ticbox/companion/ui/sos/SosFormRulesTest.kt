package dev.ticbox.companion.ui.sos

import dev.ticbox.companion.api.SosEmergencyContact
import dev.ticbox.companion.api.SosLimits
import dev.ticbox.companion.api.SosWatchEditKind
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class SosFormRulesTest {
    private val ana = SosEmergencyContact("Ana", "1")
    private val bo = SosEmergencyContact("Bo", "2")
    private val cy = SosEmergencyContact("Cy", "3")

    @Test
    fun numbersKeepOnlyWhatTheWatchAcceptsUpToItsLimit() {
        assertEquals("+86 (138) 0-0.*#", sosNumberChars("+86 (138) 0-0.*#abc号\n"))
        val tooLong = "1".repeat(SosLimits.MAX_NUMBER_CHARS + 5)
        assertEquals(SosLimits.MAX_NUMBER_CHARS, sosNumberChars(tooLong).length)
    }

    @Test
    fun aNumberNeedsADigitButTheEmergencyNumberCanBeCleared() {
        assertFalse(validSosNumber(""))
        assertFalse(validSosNumber("+-"))
        assertTrue(validSosNumber("*1"))

        assertTrue(validEmergencyNumber(""))
        assertFalse(validEmergencyNumber("+-"))
        assertTrue(validEmergencyNumber("110"))
    }

    @Test
    fun onlyPlainNumbersCanReceiveSms() {
        assertTrue(canReceiveSms("+86 (138) 0000-0000"))
        assertFalse(canReceiveSms("*123#"))
        assertFalse(canReceiveSms("1+2"))
    }

    @Test
    fun aContactChangeKeepsTheOthersInPlace() {
        assertEquals(listOf(ana, bo), listOf(ana).withContact(null, bo))
        assertEquals(listOf(ana, cy), listOf(ana, bo).withContact(1, cy))
        assertEquals(listOf(bo), listOf(ana, bo).withoutContact(0))
    }

    @Test
    fun whatTheWatchAsksForPicksTheEditor() {
        assertEquals(SosDialog.Contact(null), sosEditorFor(SosWatchEditKind.CONTACTS.name))
        assertEquals(SosDialog.Number, sosEditorFor(SosWatchEditKind.EMERGENCY_NUMBER.name))
    }
}
