package dev.ticbox.companion.platform.google

import dev.ticbox.companion.api.*

/** Result counts only. Original names and callback error text never enter retained metadata. */
internal object GoogleAccountResults {
    fun completed(successful: Int, failed: Int): GoogleServiceResult = when {
        successful > 0 -> GoogleServiceResult(if (failed == 0) GoogleServiceStatus.COMPLETE else GoogleServiceStatus.PARTIAL,
            "account_transfer", transferredAccounts = successful, failedAccounts = failed)
        failed == 0 -> GoogleServiceResult(GoogleServiceStatus.SKIPPED, "account_transfer", "bootstrap_skipped", 10800)
        else -> GoogleServiceResult(GoogleServiceStatus.FAILED, "account_transfer", "no_successful_accounts", failedAccounts = failed)
    }
    fun error(code: Int): GoogleServiceResult = GoogleServiceResult(when (code) {
        10598 -> GoogleServiceStatus.CANCELLED
        10800 -> GoogleServiceStatus.SKIPPED
        10757, 10758, 10759 -> GoogleServiceStatus.TIMED_OUT
        else -> GoogleServiceStatus.FAILED
    }, "account_transfer", when (code) {
        10562 -> "source_no_accounts"
        10563, 10576 -> "bluetooth_failure"
        10580 -> "network_failure"
        10703 -> "account_blocked_by_target"
        10801 -> "no_transferable_accounts"
        else -> "google_direct_transfer_error"
    }, code)
}
