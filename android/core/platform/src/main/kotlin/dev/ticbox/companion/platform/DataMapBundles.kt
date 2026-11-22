package dev.ticbox.companion.platform

import android.os.Bundle
import dev.ticbox.companion.protocol.DataMapValue

/** Shared DataMap -> Android boundary for media controls and remote Intents. */
internal object DataMapBundles {
    fun fromMap(values: Map<String, DataMapValue>, unavailable: () -> Unit = {}): Bundle = Bundle().apply {
        values.forEach { (key, value) -> when (value) {
            DataMapValue.Null -> putString(key, null)
            is DataMapValue.Text -> putString(key, value.value)
            is DataMapValue.Bytes -> putByteArray(key, value.value)
            is DataMapValue.Bool -> putBoolean(key, value.value)
            is DataMapValue.IntNumber -> putInt(key, value.value)
            is DataMapValue.LongNumber -> putLong(key, value.value)
            is DataMapValue.FloatNumber -> putFloat(key, value.value)
            is DataMapValue.DoubleNumber -> putDouble(key, value.value)
            is DataMapValue.ByteNumber -> putByte(key, value.value)
            is DataMapValue.Strings -> putStringArray(key, value.value.toTypedArray())
            is DataMapValue.Longs -> putLongArray(key, value.value.toLongArray())
            is DataMapValue.Floats -> putFloatArray(key, value.value.toFloatArray())
            is DataMapValue.MapValue -> putBundle(key, fromMap(value.value, unavailable))
            is DataMapValue.ListValue -> when {
                value.value.all { it is DataMapValue.MapValue } -> putParcelableArrayList(key,
                    ArrayList(value.value.map { fromMap((it as DataMapValue.MapValue).value, unavailable) }))
                value.value.all { it is DataMapValue.Text } -> putStringArrayList(key,
                    ArrayList(value.value.map { (it as DataMapValue.Text).value }))
                value.value.all { it is DataMapValue.IntNumber } -> putIntegerArrayList(key,
                    ArrayList(value.value.map { (it as DataMapValue.IntNumber).value }))
                else -> unavailable()
            }
            is DataMapValue.AssetIndex, is DataMapValue.Unknown -> unavailable()
        } }
    }
}
