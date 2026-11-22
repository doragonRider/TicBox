package dev.ticbox.companion.ui.common

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources

/** Text that is resolved against resources when shown, so view models stay locale-free. */
sealed interface UiText {
    data class Resource(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    data class Plural(
        @param:PluralsRes val id: Int,
        val count: Int,
        val args: List<Any> = listOf(count),
    ) : UiText

    fun resolve(resources: Resources): String {
        fun arguments(values: List<Any>) =
            values.map { if (it is UiText) it.resolve(resources) else it }.toTypedArray()
        return when (this) {
            is Resource -> resources.getString(id, *arguments(args))
            is Plural -> resources.getQuantityString(id, count, *arguments(args))
        }
    }
}

fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Resource(id, args.toList())

@Composable fun UiText.asString(): String = resolve(LocalResources.current)
