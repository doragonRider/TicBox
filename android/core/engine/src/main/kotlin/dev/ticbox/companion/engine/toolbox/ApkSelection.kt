package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.ToolboxException

/**
 * Keeps what this watch needs from a store's full set of parts: its processor, the closest screen
 * density and its languages. Parts that aren't these kinds of config, such as feature modules and
 * asset packs, stay, because leaving out one the app requires makes the install fail.
 */
internal fun <T> pickForWatch(apks: List<T>, profile: WatchProfile, splitOf: (T) -> String?): List<T> {
    val configs = apks.mapNotNull { apk -> configOf(splitOf(apk))?.let { ConfigSplit(apk, it.first, it.second) } }
    val dropped = HashSet<T>()
    configs.groupBy { it.parent }.values.forEach { group ->
        dropped += abisToDrop(group, profile) + densitiesToDrop(group, profile) + languagesToDrop(group, profile)
    }
    return apks.filterNot { it in dropped }
}

private class ConfigSplit<T>(val apk: T, val parent: String, val token: String)

/** Splits read "config.xhdpi" for the app and "feature.config.xhdpi" for one of its modules. */
private fun configOf(split: String?): Pair<String, String>? = when {
    split == null -> null
    split.startsWith(CONFIG_PREFIX) -> "" to split.removePrefix(CONFIG_PREFIX)
    MODULE_CONFIG in split -> split.substringBefore(MODULE_CONFIG) to split.substringAfter(MODULE_CONFIG)
    else -> null
}

private fun <T> abisToDrop(group: List<ConfigSplit<T>>, profile: WatchProfile): List<T> {
    val offered = group.filter { it.token in ABIS }
    if (offered.isEmpty()) return emptyList()
    val chosen = profile.abis.firstOrNull { wanted -> offered.any { it.token == wanted } } ?: throw ToolboxException("package_wrong_abi")
    return offered.filter { it.token != chosen }.map { it.apk }
}

/** The smallest density that's still sharp on this screen, or else the sharpest there is. */
private fun <T> densitiesToDrop(group: List<ConfigSplit<T>>, profile: WatchProfile): List<T> {
    val offered = group.filter { it.token in DENSITIES }
    if (offered.isEmpty()) return emptyList()
    val sharp = offered.filter { DENSITIES.getValue(it.token) >= profile.density }
    val chosen = sharp.minByOrNull { DENSITIES.getValue(it.token) } ?: offered.maxBy { DENSITIES.getValue(it.token) }
    return offered.filter { it !== chosen }.map { it.apk }
}

/** English stays as a small fallback. An app with none of these still has its base's strings. */
private fun <T> languagesToDrop(group: List<ConfigSplit<T>>, profile: WatchProfile): List<T> =
    group.filter { isLanguage(it.token) && languageOf(it.token) != FALLBACK_LANGUAGE && languageOf(it.token) !in profile.languages }
        .map { it.apk }

private fun isLanguage(token: String) = token !in ABIS && token !in DENSITIES && token !in NOT_LANGUAGES && LANGUAGE.matches(token)

private const val CONFIG_PREFIX = "config."
private const val MODULE_CONFIG = ".config."
private const val FALLBACK_LANGUAGE = "en"
private val ABIS = setOf("armeabi", "armeabi_v7a", "arm64_v8a", "x86", "x86_64", "mips", "mips64", "riscv64")
private val DENSITIES = mapOf("ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240, "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640)
private val LANGUAGE = Regex("""(b\+)?[a-zA-Z]{2,3}([_+-][A-Za-z0-9]+)*""")

/** A texture format short enough to pass for a language code. */
private val NOT_LANGUAGES = setOf("atc")
