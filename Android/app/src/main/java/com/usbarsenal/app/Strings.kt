package com.usbarsenal.app

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue

/*
 * Every string the app shows lives in res/values/strings.xml and plurals.xml, one Weblate
 * component a file. Composables and plain functions (the status lines, the busy text, the faults) read them
 * the same way, through str() and plural(). The source is snapshot state, so a language change,
 * which the activity takes itself rather than being recreated, recomposes whatever read a string.
 */
interface StringSource {
    fun text(@StringRes id: Int, args: Array<out Any>): String
    fun plural(@PluralsRes id: Int, count: Int, args: Array<out Any>): String
}

class ResourceStrings(private val res: Resources) : StringSource {
    // Without arguments the text is taken as it is, so a lone % needs no escaping
    override fun text(id: Int, args: Array<out Any>) = if (args.isEmpty()) res.getString(id) else res.getString(id, *args)
    override fun plural(id: Int, count: Int, args: Array<out Any>) = res.getQuantityString(id, count, *args)
}

object Strings {
    // MainActivity sets it before the first frame; a JVM test sets a reader of the XML
    var source: StringSource by mutableStateOf<StringSource>(Unset, neverEqualPolicy())

    private object Unset : StringSource {
        private fun unset(): Nothing = error("a string was read before Strings.source was set")
        override fun text(id: Int, args: Array<out Any>) = unset()
        override fun plural(id: Int, count: Int, args: Array<out Any>) = unset()
    }
}

fun str(@StringRes id: Int, vararg args: Any): String = Strings.source.text(id, args)

// The count picks the form and is the first argument, %1$d
fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String = Strings.source.plural(id, count, arrayOf<Any>(count, *args))
