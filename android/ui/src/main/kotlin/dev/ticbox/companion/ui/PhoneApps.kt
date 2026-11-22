package dev.ticbox.companion.ui

import androidx.compose.ui.graphics.ImageBitmap

/** An app installed on this phone, for choosing which notifications reach the watch. */
data class PhoneApp(
    val packageName: String,
    val label: String,
    val system: Boolean = false,
    val internet: Boolean = true,
    val installedAt: Long = 0,
)

/** Reads installed apps from the phone. Loading and caching belong to the app host. */
interface PhoneAppsSource {
    suspend fun installedApps(): List<PhoneApp>

    suspend fun icon(packageName: String): ImageBitmap?
}

enum class AppSort {
    SELECTED_FIRST,
    NAME,
    INSTALL_TIME,
}

data class PackageListImport(
    val packages: Set<String>,
    val ignored: Int = 0,
    val tooLarge: Boolean = false,
)

private val packageName = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

/** Parses one package name per line, as produced by "copy list". */
fun parsePackageList(input: String): PackageListImport {
    if (input.length > 1_048_576) return PackageListImport(emptySet(), tooLarge = true)
    val lines = input.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    val valid = lines.filter { it == "android" || it.matches(packageName) }
    return PackageListImport(valid.toSet(), lines.size - valid.size)
}

fun filterPhoneApps(
    apps: List<PhoneApp>,
    query: String,
    showSystem: Boolean,
    showOffline: Boolean,
    sort: AppSort,
    selected: Set<String>,
    /** Listed by hand, so hiding a category doesn't hide them. A search still narrows them. */
    alwaysShown: Set<String> = selected,
): List<PhoneApp> {
    val matches = apps.filter {
        (it.packageName in alwaysShown ||
            (showSystem || !it.system) && (showOffline || it.internet)) &&
            (it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true))
    }
    val comparator =
        when (sort) {
            AppSort.SELECTED_FIRST ->
                compareByDescending<PhoneApp> { it.packageName in selected }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
            AppSort.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { app: PhoneApp -> app.label }
            AppSort.INSTALL_TIME -> compareByDescending { it.installedAt }
        }
    return matches.sortedWith(comparator.thenBy { it.packageName })
}
