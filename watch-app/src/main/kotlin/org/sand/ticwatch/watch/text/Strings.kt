package org.sand.ticwatch.watch.text

import android.content.Context
import androidx.annotation.StringRes

/** String resources in the app language (set from the phone), for code outside Compose: payloads, notifications. */
fun interface Strings {
    fun get(@StringRes id: Int, vararg args: Any): String
}

/** [Strings] over the application context, whose resources follow the per-app language. */
class ContextStrings(context: Context) : Strings {
    private val appContext = context.applicationContext

    override fun get(@StringRes id: Int, vararg args: Any): String =
        if (args.isEmpty()) appContext.getString(id) else appContext.getString(id, *args)
}
