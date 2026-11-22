package dev.ticbox.companion.platform.google

import dev.ticbox.companion.api.GoogleServiceStatus
import kotlin.test.*

class GoogleAccountResultsTest {
    @Test fun `any actual successful account matches stock completion while retaining partial failure counts`() {
        val partial = GoogleAccountResults.completed(1, 2)
        assertEquals(GoogleServiceStatus.PARTIAL, partial.status)
        assertEquals(1, partial.transferredAccounts); assertEquals(2, partial.failedAccounts)
        assertEquals(GoogleServiceStatus.COMPLETE, GoogleAccountResults.completed(2, 0).status)
    }
    @Test fun `empty results are skipped and zero successful results cannot become success`() {
        assertEquals(GoogleServiceStatus.SKIPPED, GoogleAccountResults.completed(0, 0).status)
        assertEquals(10800, GoogleAccountResults.completed(0, 0).serviceStatusCode)
        assertEquals(GoogleServiceStatus.FAILED, GoogleAccountResults.completed(0, 2).status)
    }
    @Test fun `raw Google error status remains available beside observed result mapping`() {
        assertEquals(GoogleServiceStatus.CANCELLED, GoogleAccountResults.error(10598).status)
        assertEquals(GoogleServiceStatus.TIMED_OUT, GoogleAccountResults.error(10758).status)
        assertEquals("account_blocked_by_target", GoogleAccountResults.error(10703).reason)
        val future = GoogleAccountResults.error(45000)
        assertEquals(GoogleServiceStatus.FAILED, future.status); assertEquals(45000, future.serviceStatusCode)
    }
}
