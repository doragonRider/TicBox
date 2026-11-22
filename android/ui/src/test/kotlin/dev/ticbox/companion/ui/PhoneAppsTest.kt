package dev.ticbox.companion.ui

import dev.ticbox.companion.api.NotificationFilterMode
import dev.ticbox.companion.api.NotificationSyncSettings
import dev.ticbox.companion.ui.notifications.listFor
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class PhoneAppsTest {
    private val apps =
        listOf(
            PhoneApp("com.example.mail", "Mail", installedAt = 3),
            PhoneApp("com.example.chat", "chat", installedAt = 5),
            PhoneApp("android", "Android System", system = true, installedAt = 1),
            PhoneApp("com.example.offline", "Notes", internet = false, installedAt = 4),
        )

    @Test
    fun hiddenCategoriesAndSearchAreAppliedBeforeSorting() {
        val visible =
            filterPhoneApps(
                apps,
                "",
                showSystem = false,
                showOffline = false,
                AppSort.NAME,
                emptySet(),
            )
        assertEquals(listOf("chat", "Mail"), visible.map { it.label })

        val search =
            filterPhoneApps(
                apps,
                "EXAMPLE.OFF",
                showSystem = true,
                showOffline = true,
                AppSort.NAME,
                emptySet(),
            )
        assertEquals(listOf("com.example.offline"), search.map { it.packageName })
    }

    @Test
    fun listedAppsStayVisibleWhateverIsHiddenButASearchStillNarrowsThem() {
        val listed = setOf("android", "com.example.offline")
        fun visible(query: String) =
            filterPhoneApps(
                    apps,
                    query,
                    showSystem = false,
                    showOffline = false,
                    AppSort.NAME,
                    listed,
                )
                .map { it.label }
        assertEquals(listOf("Android System", "chat", "Mail", "Notes"), visible(""))
        assertEquals(listOf("Mail"), visible("mail"))
    }

    @Test
    fun listedAppsComeFirstThenNameOrInstallTime() {
        val listed =
            filterPhoneApps(
                apps,
                "",
                showSystem = true,
                showOffline = true,
                AppSort.SELECTED_FIRST,
                setOf("com.example.offline"),
            )
        assertEquals("com.example.offline", listed.first().packageName)

        val recent =
            filterPhoneApps(
                apps,
                "",
                showSystem = true,
                showOffline = true,
                AppSort.INSTALL_TIME,
                emptySet(),
            )
        assertEquals(listOf(5L, 4L, 3L, 1L), recent.map { it.installedAt })
    }

    @Test
    fun pastedListsKeepValidPackagesAndCountTheRest() {
        val parsed =
            parsePackageList(
                "com.example.mail\n\n  android  \nnot a package\n1bad.start\ncom.example.mail"
            )
        assertEquals(setOf("com.example.mail", "android"), parsed.packages)
        assertEquals(2, parsed.ignored)
        assertTrue(parsePackageList("x".repeat(1_048_577)).tooLarge)
    }

    @Test
    fun checkedMeansSendsInBothListModes() {
        val blocklist =
            NotificationSyncSettings(
                blockedPackages = setOf("a"),
                mode = NotificationFilterMode.BLOCKLIST,
            )
        assertEquals(setOf("b"), listFor(blocklist, mapOf("a" to true, "b" to false)))

        val allowlist =
            NotificationSyncSettings(
                allowedPackages = setOf("a"),
                mode = NotificationFilterMode.ALLOWLIST,
            )
        assertEquals(setOf("b"), listFor(allowlist, mapOf("a" to false, "b" to true)))
    }
}
