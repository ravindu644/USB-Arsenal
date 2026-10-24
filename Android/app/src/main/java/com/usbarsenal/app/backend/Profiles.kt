package com.usbarsenal.app.backend

import org.json.JSONArray
import org.json.JSONObject

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

// The folder by its canonical path, the form `identity show` names the active profile in
suspend fun profilesDir(): String = Root.read("mkdir -p $PROFILES && readlink -f $PROFILES").out.firstOrNull() ?: PROFILES

// The folder's profiles, each read by config.c's own loader (`identity show --json FILE...`), so
// the app reads a file exactly as `identity set` will; the JSON as printed, which the cache keeps
suspend fun listProfilesJson(dir: String): String? = Root.read(
    "set -- ${shellQuote(dir)}/*.conf; [ -e \"\$1\" ] || { echo '[]'; exit 0; }; $UA identity show --json \"\$@\"",
).out.joinToString("\n").takeIf { it.startsWith("[") }

// "0x18d1" as config.c prints an id: four lower-case hex digits, as the screen shows it
fun hex4(v: String) = v.trim().removePrefix("0x").removePrefix("0X").lowercase().padStart(4, '0')

// A profile from the keys config.c printed for it; a key it lacks is the built-in value
fun profileOf(path: String, keys: Map<String, String>) = Profile(
    path,
    keys["product"]?.ifEmpty { null } ?: BUILT_IN.product,
    keys["manufacturer"]?.ifEmpty { null } ?: BUILT_IN.maker,
    keys["id_vendor"]?.ifEmpty { null }?.let(::hex4) ?: BUILT_IN.vid,
    keys["id_product"]?.ifEmpty { null }?.let(::hex4) ?: BUILT_IN.pid,
    keys["max_power"]?.toIntOrNull() ?: BUILT_IN.power,
    keys["serial"].orEmpty(),
)

// `identity show --json FILE...`: one object per file, its path and its keys
fun parseProfiles(json: String): List<Profile> = runCatching {
    val a = JSONArray(json)
    (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        profileOf(o.getString("path"), o.getJSONObject("keys").strings())
    }.sortedBy { it.name.lowercase() }
}.getOrDefault(emptyList())

internal fun JSONObject.strings() = keys().asSequence().associateWith { getString(it) }

// The template with this profile's keys put in, each as a plain `key=value` line; a key a
// hand-made file lacks is added at the end, so none of the six is ever left out
fun withKeys(template: List<String>, p: Profile): String {
    val set = mapOf(
        "product" to p.product,
        "manufacturer" to p.maker,
        "id_vendor" to "0x${p.vid}",
        "id_product" to "0x${p.pid}",
        "max_power" to "${p.power}",
        "serial" to p.serial,
    )
    val there = template.map { it.substringBefore('=', "").trim() }.toSet()
    val lines = template.map { line ->
        val key = line.substringBefore('=', "").trim()
        set[key]?.let { "$key=$it" } ?: line
    } + set.filterKeys { it !in there }.map { (k, v) -> "$k=$v" }
    return lines.joinToString("\n", postfix = "\n")
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
