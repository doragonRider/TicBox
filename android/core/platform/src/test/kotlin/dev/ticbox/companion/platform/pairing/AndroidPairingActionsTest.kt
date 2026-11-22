package dev.ticbox.companion.platform.pairing

import dev.ticbox.companion.api.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidPairingActionsTest {
    @Test fun opaqueActionsClaimOnceAndActualPermissionsAreCheckedByPlatformAfterResult() = runTest {
        val actions = AndroidPairingActions()
        val result = async { actions.permissions(7, listOf("android.permission.BLUETOOTH_CONNECT")) }
        runCurrent()
        val token = actions.state.value.single()
        assertEquals(PairingActionKind.BLUETOOTH_PERMISSIONS, token.kind)
        assertFalse(actions.completePermissionsResult("old-process-token"))
        val launch = assertIs<AndroidPairingLaunch.Permissions>(actions.prepare(token.id))
        assertEquals(listOf("android.permission.BLUETOOTH_CONNECT"), launch.permissions)
        assertNull(actions.prepare(token.id))
        assertTrue(actions.releaseLaunch(token.id))
        assertNotNull(actions.prepare(token.id))
        assertTrue(actions.completePermissionsResult(token.id))
        assertFalse(actions.completePermissionsResult(token.id))
        assertEquals(PairingHostResult.PermissionsReturned, result.await())
        assertTrue(actions.state.value.isEmpty())
        actions.close()
    }

    @Test fun cancellationInvalidatesOnlyItsOwnTokensAndReleasesSuspendingWork() = runTest {
        val actions = AndroidPairingActions()
        val first = async { actions.permissions(1, listOf("p1")) }
        val second = async { actions.permissions(2, listOf("p2")) }
        runCurrent()
        val stale = actions.state.value.first { it.operationId == 1L }
        actions.cancel(1)
        runCurrent()
        assertTrue(first.isCancelled)
        assertFalse(actions.completePermissionsResult(stale.id))
        assertEquals(listOf(2L), actions.state.value.map { it.operationId })
        actions.completePermissionsResult(actions.state.value.single().id)
        second.await()
        actions.close()
    }
}
