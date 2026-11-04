package com.usbarsenal.app

import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/*
 * The JVM has no Resources, so the unit tests read the English strings straight from
 * res/values/strings.xml and plurals.xml, matching ids to names through R. Each test class that checks a string
 * calls XmlStrings.install() first. Only what the app's files use is handled: aapt's quoting and
 * escapes, and "one" and "other" for English plurals.
 */
object XmlStrings : StringSource {
    private val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
    private val pluralNames = R.plurals::class.java.fields.associate { it.getInt(null) to it.name }
    private val texts = HashMap<String, String>()
    private val plurals = HashMap<String, Map<String, String>>()

    init {
        // Gradle runs unit tests in the module directory
        listOf("strings.xml", "plurals.xml").map { File("src/main/res/values", it) }.forEach { file ->
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            root.children("string").forEach { texts[it.getAttribute("name")] = unescape(it.textContent) }
            root.children("plurals").forEach { p ->
                plurals[p.getAttribute("name")] = p.children("item").associate { it.getAttribute("quantity") to unescape(it.textContent) }
            }
        }
    }

    fun install() {
        Strings.source = this
    }

    override fun text(id: Int, args: Array<out Any>): String {
        val s = texts.getValue(names.getValue(id))
        return if (args.isEmpty()) s else String.format(Locale.US, s, *args)
    }

    override fun plural(id: Int, count: Int, args: Array<out Any>): String {
        val forms = plurals.getValue(pluralNames.getValue(id))
        return String.format(Locale.US, forms[if (count == 1) "one" else "other"] ?: forms.getValue("other"), *args)
    }

    private fun Element.children(tag: String): List<Element> {
        val list = getElementsByTagName(tag)
        return (0 until list.length).map { list.item(it) as Element }
    }

    // aapt's rules: a quoted run keeps its spaces, elsewhere whitespace collapses to one space;
    // a backslash takes the next character literally, n and t aside
    private fun unescape(raw: String): String {
        val out = StringBuilder()
        var quoted = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && i + 1 < raw.length -> {
                    val n = raw[++i]
                    out.append(if (n == 'n') '\n' else if (n == 't') '\t' else n)
                }
                c == '"' -> quoted = !quoted
                c.isWhitespace() && !quoted -> if (out.isEmpty() || !out.last().isWhitespace()) out.append(' ')
                else -> out.append(c)
            }
            i++
        }
        return if (raw.trimStart().startsWith('"')) out.toString() else out.toString().trim()
    }
}
