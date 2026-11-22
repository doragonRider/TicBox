package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.ToolboxException
import dev.ticbox.companion.api.WatchToolboxBackend
import java.io.IOException

/** What decides which parts of an app a watch needs. ABIs are as split names spell them. */
internal data class WatchProfile(val abis: List<String>, val density: Int, val languages: Set<String>, val sdk: Int)

internal suspend fun WatchToolboxBackend.readWatchProfile(): WatchProfile {
    val answer = try {
        execute(WATCH_PROFILE_COMMAND)
    } catch (_: IOException) {
        throw ToolboxException("connection_closed")
    }
    if (!answer.successful) throw ToolboxException("package_watch_unknown")
    return parseWatchProfile(answer.stdout.decodeToString(), state.value.capabilities?.sdk)
}

internal fun parseWatchProfile(output: String, knownSdk: Int?): WatchProfile {
    val values = output.lineSequence().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=').trim() }
    val abis = values["abi"].orEmpty().split(',').map { it.trim().replace('-', '_') }.filter { it.isNotEmpty() }
    val sdk = values["sdk"]?.toIntOrNull() ?: knownSdk
    if (abis.isEmpty() || sdk == null) throw ToolboxException("package_watch_unknown")
    // "wm density" ends on its override when there is one, which is the density in use.
    val density = DENSITY_NUMBER.find(values["density"].orEmpty())?.value?.toIntOrNull() ?: values["lcd"]?.toIntOrNull() ?: XHDPI
    val languages = listOf("locales", "locale", "product_locale").flatMap { values[it].orEmpty().split(',') }
        .filter { it.isNotBlank() && it.trim() != "null" }
        .map(::languageOf)
    return WatchProfile(abis, density, languages.toSet(), sdk)
}

/** Room left in shared storage, or null when the watch doesn't say, which is no reason to refuse. */
internal suspend fun WatchToolboxBackend.readFreeBytes(storage: String): Long? = try {
    val answer = execute("df -k $storage/ | tail -n 1")
    if (answer.successful) parseFreeBytes(answer.stdout.decodeToString()) else null
} catch (_: ToolboxException) {
    null
} catch (_: IOException) {
    null
}

/** The last row of "df -k": filesystem, size, used, available, percent, mount point. */
internal fun parseFreeBytes(row: String): Long? =
    row.trim().split(WHITESPACE).takeIf { it.size >= 6 }?.get(3)?.toLongOrNull()?.times(1024)

/** "zh-CN" and "zh_TW" are both Chinese, and old and new codes for a language are the same one. */
internal fun languageOf(tag: String): String {
    val language = tag.trim().removePrefix("b+").split('-', '_', '+').first().lowercase()
    return LEGACY_LANGUAGES[language] ?: language
}

private const val XHDPI = 320
private val DENSITY_NUMBER = Regex("""\d+$""")
private val WHITESPACE = Regex("""\s+""")
private val LEGACY_LANGUAGES = mapOf("in" to "id", "iw" to "he", "ji" to "yi")

private const val WATCH_PROFILE_COMMAND =
    "echo \"abi=$(getprop ro.product.cpu.abilist)\"; " +
        "echo \"sdk=$(getprop ro.build.version.sdk)\"; " +
        "echo \"density=$(wm density | tail -n 1)\"; " +
        "echo \"lcd=$(getprop ro.sf.lcd_density)\"; " +
        "echo \"locales=$(settings get system system_locales)\"; " +
        "echo \"locale=$(getprop persist.sys.locale)\"; " +
        "echo \"product_locale=$(getprop ro.product.locale)\""
