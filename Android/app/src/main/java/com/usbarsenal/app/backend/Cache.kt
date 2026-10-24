package com.usbarsenal.app.backend

import android.content.Context
import androidx.core.content.edit
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/*
 * The last of every read, kept in prefs purely as a cache (Droidspaces' cachedContainers and
 * cachedHostCapabilitiesBootId): the first frame of every screen draws from it, a live read
 * replaces it, a failed read leaves it. The truth is the backend's; wiping this loses nothing.
 * Each entry remembers the boot it was read in, so a gadget from before a reboot is never shown
 * as live; the setup, caps and profile outlive a reboot.
 */
object Cache {
    // Null until init, as in a unit test: then nothing is cached and nothing is saved
    private var prefs: android.content.SharedPreferences? = null

    // This boot, read by the app itself (no root): the same id the backend prints
    val bootId: String by lazy { runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrDefault("") }

    fun init(context: Context) {
        prefs = context.getSharedPreferences("cache", Context.MODE_PRIVATE)
    }

    private fun text(key: String) = prefs?.getString(key, null)

    private fun put(key: String, value: String?) {
        prefs?.edit { if (value == null) remove(key) else putString(key, value) }
    }

    // The last status; its gadget part only while the boot it was read in is this one
    var status: GadgetStatus?
        get() = text("status")?.let { raw -> runCatching { parseStatus(raw) }.getOrNull() }?.let { s ->
            if (s.bootId.isNotEmpty() && s.bootId == bootId) s else s.copy(bound = false, storage = emptyList(), hid = emptyList(), tether = null, running = emptyMap(), pending = emptyMap())
        }
        set(v) = put("status", v?.raw)

    var caps: Capabilities?
        get() = text("caps")?.let { runCatching { parseCheck(it) }.getOrNull() }
        set(v) = put("caps", v?.raw)

    var profile: Map<String, String>
        get() = text("profile")?.let { runCatching { JSONObject(it).toMap() }.getOrNull() }.orEmpty()
        set(v) = put("profile", if (v.isEmpty()) null else JSONObject(v).toString())

    var backend: Backend?
        get() = text("backend")?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { Backend(BackendState.valueOf(it.getString("state")), it.optString("installed").ifEmpty { null }) }
        set(v) = put("backend", v?.let { JSONObject().put("state", it.state.name).put("installed", it.installed ?: "").toString() })

    // Edits of the setup not yet sent to the backend, so a kill in the beat before they go loses none
    var edits: Map<String, String>
        get() = text("edits")?.let { runCatching { JSONObject(it).toMap() }.getOrNull() }.orEmpty()
        set(v) = put("edits", if (v.isEmpty()) null else JSONObject(v).toString())

    var droidspaces: Droidspaces?
        get() = text("droidspaces")?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { o ->
            Droidspaces(o.getBoolean("installed"), o.optJSONArray("running")?.let { a -> (0 until a.length()).map(a::getString) })
        }
        set(v) = put("droidspaces", v?.let { JSONObject().put("installed", it.installed).put("running", it.running?.let(::JSONArray)).toString() })

    var udcs: List<Udc>?
        get() = text("udcs")?.let { parseUdcs(it.lines()) }
        set(v) = put("udcs", v?.joinToString("\n") { "${it.name} ${it.state}" })

    var daemonMode: Boolean?
        get() = text("daemon")?.toBooleanStrictOrNull()
        set(v) = put("daemon", v?.toString())

    var pathLinked: Boolean?
        get() = text("linked")?.toBooleanStrictOrNull()
        set(v) = put("linked", v?.toString())

    // Storage's folder: its canonical path, its files as `<bytes> <path>` lines, and the free bytes
    var imagesDir: String?
        get() = text("imagesDir")
        set(v) = put("imagesDir", v)

    var images: List<ImageFile>?
        get() = text("images")?.let { parseImages(it.lines()) }
        set(v) = put("images", v?.joinToString("\n") { "${it.bytes} ${it.path}" })

    var free: Long?
        get() = text("free")?.toLongOrNull()
        set(v) = put("free", v?.toString())

    private fun JSONObject.toMap() = keys().asSequence().associateWith { getString(it) }
}
