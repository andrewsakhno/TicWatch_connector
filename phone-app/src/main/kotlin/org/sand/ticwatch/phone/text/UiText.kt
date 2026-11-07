package org.sand.ticwatch.phone.text

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * Text built outside the UI (analyzers, engines, stores) as string resources plus arguments, resolved in the
 * current app language only where it is shown. Arguments that are themselves [UiText] are resolved first.
 */
sealed interface UiText {
    fun resolve(resources: Resources): String
}

data class ResourceText(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText {
    override fun resolve(resources: Resources): String =
        if (args.isEmpty()) resources.getString(id) else resources.getString(id, *resolveArgs(args, resources))
}

data class PluralText(@PluralsRes val id: Int, val quantity: Int, val args: List<Any> = listOf(quantity)) : UiText {
    override fun resolve(resources: Resources): String = resources.getQuantityString(id, quantity, *resolveArgs(args, resources))
}

/** Text that needs no translation (numbers, units, names). */
data class LiteralText(val value: String) : UiText {
    override fun resolve(resources: Resources): String = value
}

data class JoinedText(val parts: List<UiText>, val separator: String) : UiText {
    override fun resolve(resources: Resources): String = parts.joinToString(separator) { it.resolve(resources) }
}

/** [inner] in lower case, for a label used mid-sentence. */
data class LowercaseText(val inner: UiText) : UiText {
    override fun resolve(resources: Resources): String = inner.resolve(resources).lowercase(resources.configuration.locales[0])
}

/** A moment as wall-clock time in the device zone (HH:mm). */
data class TimeOfDayText(val at: java.time.Instant) : UiText {
    override fun resolve(resources: Resources): String = TIME_OF_DAY.format(at.atZone(java.time.ZoneId.systemDefault()))

    private companion object {
        val TIME_OF_DAY: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    }
}

fun uiText(@StringRes id: Int, vararg args: Any): UiText = ResourceText(id, args.toList())

fun pluralText(@PluralsRes id: Int, quantity: Int, vararg args: Any): UiText =
    PluralText(id, quantity, if (args.isEmpty()) listOf(quantity) else args.toList())

/** Turns [UiText] into a string for non-Compose consumers (MCP tools, notifications, reports). */
fun interface TextResolver {
    fun resolve(text: UiText): String
}

/** [TextResolver] over a context's resources, which follow the app language. */
class ResourcesTextResolver(private val resources: () -> Resources) : TextResolver {
    override fun resolve(text: UiText): String = text.resolve(resources())
}

@Composable
@ReadOnlyComposable
fun UiText.asString(): String {
    LocalConfiguration.current // re-read on a language change
    return resolve(LocalContext.current.resources)
}

private fun resolveArgs(args: List<Any>, resources: Resources): Array<Any> =
    args.map { if (it is UiText) it.resolve(resources) else it }.toTypedArray()
