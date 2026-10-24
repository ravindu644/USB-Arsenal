package com.usbarsenal.app.backend

// Identity's profiles, files `identity set` takes, kept beside the images
const val PROFILES = "$IMAGES/Profiles"

/*
 * A profile as Identity shows and edits it: the keys the host sees by name. The file holds every
 * other key too, at the values `identity generate` gave it. [path] is empty for the built-in
 * defaults, which are no file.
 */
// An empty [serial] is the phone's own: config.c derives it from ro.serialno (or /etc/machine-id)
data class Profile(val path: String, val product: String, val maker: String, val vid: String, val pid: String, val power: Int, val serial: String = "") {
    val name get() = if (path.isEmpty()) "Built-in" else path.substringAfterLast('/').removeSuffix(".conf")
}

// config.c's defaults, which `identity unset` goes back to
val BUILT_IN = Profile("", "USB Arsenal", "USB Arsenal", "1d6b", "0104", 250)

// The keys Identity sets; the rest keep the template's values
private val KEYS = listOf("product", "manufacturer", "id_vendor", "id_product", "max_power", "serial")

// The folder by its canonical path, the form `identity show` names the active profile in
suspend fun profilesDir(): String = Root.read("mkdir -p $PROFILES && readlink -f $PROFILES").out.firstOrNull() ?: PROFILES

suspend fun listProfiles(dir: String): List<Profile> = parseProfiles(
    Root.read(
        "for f in ${shellQuote(dir)}/*.conf; do [ -f \"\$f\" ] && echo \"== \$f\" && " +
            "grep -E '^(${KEYS.joinToString("|")})=' \"\$f\"; done; true",
    ).out,
)

// A value as config.c reads it: cut at a '#' that starts it or follows whitespace (a comment),
// then trimmed. `generate` writes `serial=   # empty: ...`, which is an empty serial
private fun value(raw: String): String {
    val hash = Regex("(^|\\s)#").find(raw)?.range?.first ?: return raw.trim()
    return raw.substring(0, hash).trim()
}

// "0x18D1" or "18d1" as the screen shows it: four lower-case hex digits
fun hex4(v: String) = v.trim().removePrefix("0x").removePrefix("0X").lowercase().padStart(4, '0')

// `== path` starts a profile; its `key=value` lines follow. Missing keys keep the built-in value
fun parseProfiles(lines: List<String>): List<Profile> {
    val out = mutableListOf<Profile>()
    var path: String? = null
    var keys = mutableMapOf<String, String>()
    fun flush() {
        val p = path ?: return
        out += Profile(
            p,
            keys["product"] ?: BUILT_IN.product,
            keys["manufacturer"] ?: BUILT_IN.maker,
            keys["id_vendor"]?.let(::hex4) ?: BUILT_IN.vid,
            keys["id_product"]?.let(::hex4) ?: BUILT_IN.pid,
            keys["max_power"]?.toIntOrNull() ?: BUILT_IN.power,
            keys["serial"].orEmpty(),
        )
    }
    lines.forEach { line ->
        if (line.startsWith("== ")) {
            flush()
            path = line.removePrefix("== ")
            keys = mutableMapOf()
        } else if ('=' in line) {
            keys[line.substringBefore('=').trim()] = value(line.substringAfter('='))
        }
    }
    flush()
    return out.sortedBy { it.name.lowercase() }
}

// The template with this profile's keys put in, each as a plain `key=value` line
fun withKeys(template: List<String>, p: Profile): String {
    val set = mapOf(
        "product" to p.product,
        "manufacturer" to p.maker,
        "id_vendor" to "0x${p.vid}",
        "id_product" to "0x${p.pid}",
        "max_power" to "${p.power}",
        "serial" to p.serial,
    )
    return template.joinToString("\n", postfix = "\n") { line ->
        val key = line.substringBefore('=', "").trim()
        set[key]?.let { "$key=$it" } ?: line
    }
}

// What a profile's file holds now, to put back if an edit is discarded; null when unreadable
suspend fun profileText(path: String): String? =
    Root.read("cat ${shellQuote(path)}").takeIf { it.ok }?.out?.joinToString("\n", postfix = "\n")

/*
 * Writes [p] at its path. A new one starts from `identity generate`'s template, every key with
 * its comment; an edit starts from the file as it is, so the keys this screen does not show
 * (storage mode, the tether address, the HID group...) keep their values.
 */
suspend fun writeProfile(p: Profile, create: Boolean): RootResult {
    val template = if (create) {
        val tmp = shellQuote("${p.path}.new")
        Root.change("$UA identity generate $tmp --force >/dev/null && cat $tmp")
    } else {
        Root.read("cat ${shellQuote(p.path)}")
    }
    if (!template.ok) return template
    return writeText(p.path, withKeys(template.out, p), fresh = create)
}

/*
 * [text] into [path] through a file beside it and a rename, so a start that reads the profile
 * meanwhile sees the old file or the new one, never half of one. The text goes as one
 * shell-quoted word, so nothing in it can break the command. [fresh] refuses a file that is
 * already there: a new profile never overwrites another.
 */
suspend fun writeText(path: String, text: String, fresh: Boolean = false): RootResult {
    val f = shellQuote(path)
    val tmp = shellQuote("$path.new")
    val guard = if (fresh) "if [ -e $f ]; then rm -f $tmp; echo '[-] A profile by that name is already there' >&2; exit 1; fi; " else ""
    return Root.change("${guard}printf '%s' ${shellQuote(text)} > $tmp && mv -f $tmp $f")
}
