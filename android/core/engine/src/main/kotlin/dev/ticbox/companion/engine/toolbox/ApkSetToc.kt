package dev.ticbox.companion.engine.toolbox

import dev.ticbox.companion.api.ToolboxException

/**
 * The APKs to install from a bundletool .apks, going by its toc.pb. The archive also holds other
 * builds of the same app for older Android versions and modules that download later, which can't
 * be told apart by looking at the APKs. Field numbers are from bundletool's commands.proto.
 */
internal fun installTimePaths(toc: ByteArray, profile: WatchProfile): Set<String> = try {
    val result = fieldsOf(toc)
    val usable = result.messages(BUILD_VARIANT).filter { variant ->
        val abis = abisOf(variant)
        minSdkOf(variant) <= profile.sdk && (abis.isEmpty() || abis.any { it in profile.abis })
    }
    val variant = usable.maxWithOrNull(compareBy({ minSdkOf(it) }, { it.number(VARIANT_NUMBER) }))
        ?: throw ToolboxException("package_incompatible")
    val paths = LinkedHashSet<String>()
    variant.messages(VARIANT_APK_SET).forEach { set ->
        val module = set.messages(APK_SET_MODULE).firstOrNull()
        if (module == null || installsWithApp(module)) paths += pathsOf(set, APP_APK_KINDS)
    }
    result.messages(BUILD_ASSET_SLICE_SET).forEach { set ->
        val pack = set.messages(APK_SET_MODULE).firstOrNull()
        if (pack?.number(ASSET_DELIVERY) == DELIVERY_INSTALL_TIME) paths += pathsOf(set, setOf(APK_ASSET_SLICE))
    }
    paths
} catch (_: IndexOutOfBoundsException) {
    throw ToolboxException("package_unreadable")
} catch (_: IllegalArgumentException) {
    throw ToolboxException("package_unreadable")
}

private fun installsWithApp(module: List<ProtoField>): Boolean {
    val delivery = module.number(MODULE_DELIVERY)
    return delivery == DELIVERY_INSTALL_TIME || (delivery == 0L && module.number(MODULE_ON_DEMAND_OLD) == 0L)
}

private fun pathsOf(set: List<ProtoField>, kinds: Set<Int>): List<String> =
    set.messages(APK_SET_DESCRIPTION)
        .filter { description -> description.any { it.number in kinds } }
        .mapNotNull { description -> description.firstOrNull { it.number == APK_PATH }?.bytes?.decodeToString() }

private fun minSdkOf(variant: List<ProtoField>): Long =
    variant.messages(VARIANT_TARGETING).firstOrNull()
        ?.messages(TARGETING_SDK)?.firstOrNull()
        ?.messages(TARGETING_VALUE)?.firstOrNull()
        ?.messages(SDK_MIN)?.firstOrNull()
        ?.number(WRAPPED_VALUE) ?: 0

private fun abisOf(variant: List<ProtoField>): List<String> {
    val targeting = variant.messages(VARIANT_TARGETING).firstOrNull() ?: return emptyList()
    val single = targeting.messages(TARGETING_ABI).flatMap { it.messages(TARGETING_VALUE) }
    val multiple = targeting.messages(TARGETING_MULTI_ABI).flatMap { it.messages(TARGETING_VALUE) }.flatMap { it.messages(MULTI_ABI_ABI) }
    return (single + multiple).mapNotNull { ABI_ALIASES[it.number(ABI_ALIAS).toInt()] }
}

private class ProtoField(val number: Int, val value: Long, val bytes: ByteArray?)

private fun List<ProtoField>.messages(number: Int): List<List<ProtoField>> =
    filter { it.number == number }.mapNotNull { it.bytes }.map(::fieldsOf)

private fun List<ProtoField>.number(number: Int): Long = firstOrNull { it.number == number }?.value ?: 0

private fun fieldsOf(message: ByteArray): List<ProtoField> {
    val fields = ArrayList<ProtoField>()
    var at = 0
    fun varint(): Long {
        var value = 0L
        var shift = 0
        while (true) {
            val byte = message[at++].toInt()
            value = value or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return value
            shift += 7
            require(shift < 64)
        }
    }
    while (at < message.size) {
        val key = varint()
        val number = (key ushr 3).toInt()
        when ((key and 7).toInt()) {
            WIRE_VARINT -> fields += ProtoField(number, varint(), null)
            WIRE_FIXED64 -> at += 8
            WIRE_LENGTH -> {
                val length = varint()
                require(length in 0..(message.size - at).toLong())
                fields += ProtoField(number, 0, message.copyOfRange(at, at + length.toInt()))
                at += length.toInt()
            }
            WIRE_FIXED32 -> at += 4
            else -> throw IllegalArgumentException()
        }
    }
    return fields
}

private const val WIRE_VARINT = 0
private const val WIRE_FIXED64 = 1
private const val WIRE_LENGTH = 2
private const val WIRE_FIXED32 = 5
private const val BUILD_VARIANT = 1
private const val BUILD_ASSET_SLICE_SET = 3
private const val VARIANT_TARGETING = 1
private const val VARIANT_APK_SET = 2
private const val VARIANT_NUMBER = 3
private const val APK_SET_MODULE = 1
private const val APK_SET_DESCRIPTION = 2
private const val MODULE_ON_DEMAND_OLD = 2
private const val MODULE_DELIVERY = 6
private const val ASSET_DELIVERY = 4
private const val DELIVERY_INSTALL_TIME = 1L
private const val APK_PATH = 2
private const val APK_SPLIT = 3
private const val APK_STANDALONE = 4
private const val APK_SYSTEM = 6
private const val APK_ASSET_SLICE = 7
private val APP_APK_KINDS = setOf(APK_SPLIT, APK_STANDALONE, APK_SYSTEM)
private const val TARGETING_SDK = 1
private const val TARGETING_ABI = 2
private const val TARGETING_MULTI_ABI = 4
private const val TARGETING_VALUE = 1
private const val SDK_MIN = 1
private const val WRAPPED_VALUE = 1
private const val MULTI_ABI_ABI = 1
private const val ABI_ALIAS = 1
private val ABI_ALIASES = mapOf(
    1 to "armeabi", 2 to "armeabi_v7a", 3 to "arm64_v8a", 4 to "x86", 5 to "x86_64", 6 to "mips", 7 to "mips64", 8 to "riscv64",
)
