package com.usbarsenal.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.usbarsenal.app.backend.BUILT_IN
import com.usbarsenal.app.backend.PROFILES
import com.usbarsenal.app.backend.Profile
import com.usbarsenal.app.backend.listProfiles
import com.usbarsenal.app.backend.profilesDir
import com.usbarsenal.app.backend.writeProfile
import com.usbarsenal.app.backend.writeText
import com.usbarsenal.app.backend.profileText
import com.usbarsenal.app.lcd.Inverted
import com.usbarsenal.app.lcd.Lcd
import com.usbarsenal.app.lcd.LcdType
import com.usbarsenal.app.lcd.LocalHaptics
import com.usbarsenal.app.lcd.LocalLcd
import com.usbarsenal.app.lcd.SpriteIcon
import com.usbarsenal.app.lcd.Sprites
import kotlinx.coroutines.launch

// The power choices Identity offers, in mA; a profile written elsewhere may hold another
private val POWERS = listOf(100, 250, 500)

private val HEX4 = Regex("[0-9a-fA-F]{4}")

// What is wrong with a profile's fields, each by its field; nothing when it can be written
fun profileProblems(name: String, product: String, maker: String, vid: String, pid: String, taken: Boolean, serial: String = ""): Map<String, String> {
    val out = mutableMapOf<String, String>()
    val n = name.trim()
    if (n.isEmpty()) out["name"] = "a name for the file"
    else if (!Regex("[A-Za-z0-9][A-Za-z0-9 ._-]{0,63}").matches(n)) out["name"] = "letters, digits, space, . _ - only"
    else if (taken) out["name"] = "name taken"
    // As config.c's strings: printable ASCII only (USB strings are), 126 at most, and no '#',
    // which it reads as a comment after a space
    fun text(key: String, v: String, required: Boolean = true) {
        if (v.isBlank()) {
            if (required) out[key] = "cannot be empty"
        } else if (v.any { it !in ' '..'~' }) {
            out[key] = "plain ASCII letters, digits and symbols only"
        } else if ('#' in v) {
            out[key] = "no #"
        } else if (v.length > 126) {
            out[key] = "126 characters at most"
        }
    }
    text("product", product)
    text("maker", maker)
    // Empty is allowed: the phone's own serial
    text("serial", serial, required = false)
    if (!HEX4.matches(vid) || !HEX4.matches(pid)) out["ids"] = "four hex digits each"
    return out
}

/*
 * Identity: the profile the screen has picked, as a panel in the drive's look (its LED lit while
 * the host sees the gadget), and under a rule every profile in /sdcard/USB-Arsenal/Profiles,
 * Built-in first. A pick is set at once while nothing is bound; while the host sees the gadget it
 * waits, and Back asks to apply it (one replug) or discard it. Edit and Create share one sheet.
 */
@Composable
fun IdentityScreen(back: () -> Unit, openLog: () -> Unit) {
    PollStatus()
    // Until status and the profile in force are read, nothing is picked: an unknown gadget may be
    // live, and a pick set at once would replug it unasked
    val status = Gadget.status
    val known = status != null && Gadget.profile.isNotEmpty()
    val bound = status?.bound == true
    val active = activeProfile(Gadget.profile)
    val scope = rememberCoroutineScope()
    var dir by remember { mutableStateOf(PROFILES) }
    var profiles by remember { mutableStateOf<List<Profile>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        dir = profilesDir()
        profiles = listProfiles(dir)
    }
    // The pick starts at what is in force, each time the screen opens, once that is known
    LaunchedEffect(Gadget.profile.isNotEmpty()) {
        if (Gadget.profile.isEmpty()) return@LaunchedEffect
        IdentitySetup.chosen = activeProfile(Gadget.profile)
        IdentitySetup.edited = false
        IdentitySetup.undo = null
    }
    val chosen = IdentitySetup.chosen
    val shown = if (chosen == null) BUILT_IN else profiles?.firstOrNull { it.path == chosen }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    val fault = Gadget.fault?.takeIf { it.where == null }

    // Set what is chosen: identity set, or unset for Built-in. A live gadget replugs once. A set
    // refused or failed leaves the pick on what is really in force, so the screen never shows
    // a profile the host does not get
    fun applyChosen() {
        val c = IdentitySetup.chosen
        val args = if (c == null) arrayOf("identity", "unset") else arrayOf("identity", "set", c)
        val accepted = Gadget.run(null, *args) { ok ->
            if (ok) {
                IdentitySetup.edited = false
                IdentitySetup.undo = null
            } else {
                IdentitySetup.chosen = activeProfile(Gadget.profile)
            }
            Gadget.reloadProfile()
        }
        if (!accepted) IdentitySetup.chosen = active
    }
    fun pick(path: String?) {
        if (!known) return
        IdentitySetup.chosen = path
        if (!bound && path != active) applyChosen()
    }
    // Leaving with a pick made while bound, the gadget since gone: nothing to replug, so it is set
    BackHandler {
        if (known && !bound && IdentitySetup.chosen != active) applyChosen()
        back()
    }

    Box(Modifier.fillMaxSize()) {
        Screen(
            footer = {
                KeyRow {
                    // Built-in is no file: there is nothing to edit
                    Key("Edit", Modifier.weight(1f).opener("identity/edit"), enabled = shown != null && chosen != null) { sheet = chosen }
                    Key("Create", Modifier.weight(1f).opener("identity/create"), primary = true) { sheet = "" }
                }
            },
        ) {
            Top { BackKey() }
            Title("Identity")
            fault?.let { Fault(it.message, onLog = openLog, modifier = Modifier.opener("identity/fault")) }
            ProfilePanel(shown, live = bound)
            Column(Modifier.bleed(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FilesHeader(PROFILES, label = "Profiles")
                FileRow(BUILT_IN.name, "${BUILT_IN.vid}:${BUILT_IN.pid}", null, selected = chosen == null, onPick = { pick(null) })
                profiles.orEmpty().forEach { p ->
                    FileRow(p.name, "${p.vid}:${p.pid}", null, selected = p.path == chosen, onPick = { pick(p.path) })
                }
            }
        }
        NotAppliedSheet(
            identityPending(bound, chosen, active, IdentitySetup.edited),
            "The host still sees the old identity. Apply reconnects the gadget once; Discard keeps it.",
            canApply = Gadget.busy == null && !Gadget.backend.holds(),
            onDiscard = {
                IdentitySetup.chosen = active
                IdentitySetup.edited = false
                // An edit of the running profile is already in its file: put the old text back
                IdentitySetup.undo?.let { (path, text) -> scope.launch { writeText(path, text) } }
                IdentitySetup.undo = null
            },
            onApply = ::applyChosen,
            back = back,
        )
        ProfileSheet(
            open = sheet != null,
            profile = sheet?.takeIf { it.isNotEmpty() }?.let { path -> profiles?.firstOrNull { it.path == path } },
            dir = dir,
            // Null while the list is unread: a new name cannot be checked, so Create waits
            names = profiles?.map { it.name.lowercase() },
            onDismiss = { sheet = null },
        ) { path, created, before ->
            sheet = null
            reload++
            when {
                created -> pick(path)
                path != active -> {}
                // The running profile changed under the gadget: Back offers to apply it, and
                // Discard puts the old text back
                bound -> {
                    IdentitySetup.edited = true
                    if (IdentitySetup.undo == null && before != null) IdentitySetup.undo = path to before
                }
                // Off the bus, set it again: a daemon that keeps a copy (a path it cannot see)
                // takes the new one, and nothing replugs
                else -> applyChosen()
            }
        }
    }
}

// The drive's look for a profile: an inverted face with its LED and name, the facts in the body
@Composable
private fun ProfilePanel(p: Profile?, live: Boolean) {
    val ink = LocalLcd.current.ink
    Column(Modifier.fillMaxWidth().border(Lcd.pixel, ink).padding(Lcd.pixel)) {
        Inverted {
            Row(
                Modifier.fillMaxWidth().height(40.dp).background(LocalLcd.current.ground).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LedDot(if (live) Led.BUSY else Led.ON)
                Text(p?.name ?: "", LcdType.labelStrong)
            }
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            SpriteIcon(Sprites.Usb, 4.dp)
            if (p != null) {
                Column(verticalArrangement = Arrangement.spacedBy(Lcd.lines)) {
                    Marquee(p.product, LcdType.data, Modifier.fillMaxWidth())
                    Marquee(p.maker, LcdType.data, Modifier.fillMaxWidth())
                    Text("${p.vid}:${p.pid} · ${p.power} mA", LcdType.data)
                    Marquee(p.serial.ifEmpty { "serial from this phone" }, LcdType.data, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private class Opened {
    var open = false
    var count = 0
    var profile: Profile? = null
}

/*
 * Create, or Edit: the Create-disk sheet's look. Name (locked when editing), product, maker,
 * serial (empty is the phone's own), the USB IDs and the power; each field says what is wrong
 * with it under it, and the key waits until nothing is.
 */
@Composable
private fun ProfileSheet(open: Boolean, profile: Profile?, dir: String, names: List<String>?, onDismiss: () -> Unit, onSaved: (String, Boolean, String?) -> Unit) {
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    /*
     * What the sheet opened on, held until it opens again. Closing clears [profile] at once, while
     * the sheet still slides away: without this it would show a blank Create on the way down.
     * Plain fields, not state: they only change in a composition that [open] already triggers.
     */
    val opened = remember { Opened() }
    if (open && !opened.open) {
        opened.count++
        opened.profile = profile
    }
    opened.open = open
    val editing = opened.profile
    val start = editing ?: BUILT_IN.copy(product = "", maker = "")
    // Each time the sheet opens, from the profile it opens on
    var name by remember(opened.count) { mutableStateOf(editing?.name ?: "") }
    var product by remember(opened.count) { mutableStateOf(start.product) }
    var maker by remember(opened.count) { mutableStateOf(start.maker) }
    var vid by remember(opened.count) { mutableStateOf(start.vid) }
    var pid by remember(opened.count) { mutableStateOf(start.pid) }
    var power by remember(opened.count) { mutableIntStateOf(start.power) }
    var serial by remember(opened.count) { mutableStateOf(start.serial) }
    var writing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    val taken = editing == null && names != null && name.trim().lowercase() in names
    val problems = profileProblems(name, product, maker, vid, pid, taken, serial)
    val ids = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)

    fun save() {
        val path = editing?.path ?: "$dir/${name.trim()}.conf"
        writing = true
        failed = null
        scope.launch {
            val before = if (editing != null) profileText(path) else null
            val r = writeProfile(Profile(path, product.trim(), maker.trim(), vid.lowercase(), pid.lowercase(), power, serial.trim()), create = editing == null)
            writing = false
            if (r.ok) {
                haptics.confirm()
                onSaved(path, editing == null, before)
            } else {
                failed = r.err.lastOrNull() ?: "[-] Failed (exit ${r.code})"
                haptics.reject()
            }
        }
    }

    val cancelKey = remember { FocusRequester() }
    Sheet(
        open,
        onDismiss = { if (!writing) onDismiss() },
        entry = cancelKey,
        footer = {
            Key("Cancel", Modifier.weight(1f).focusRequester(cancelKey), enabled = !writing, onClick = onDismiss)
            Key(if (editing == null) "Create" else "Save", Modifier.weight(1f), primary = true, enabled = problems.isEmpty() && !writing && (editing != null || names != null)) { save() }
        },
    ) {
        Title(if (editing == null) "Create" else "Edit")
        Field("Name") { Input(name, { name = it }, Modifier.fillMaxWidth(), placeholder = "pixel-8", enabled = editing == null) }
        problems["name"]?.takeIf { name.isNotEmpty() }?.let { Warn(it) }
        Field("Product") { Input(product, { product = it }, Modifier.fillMaxWidth(), placeholder = "USB Arsenal") }
        problems["product"]?.takeIf { product.isNotEmpty() }?.let { Warn(it) }
        Field("Maker") { Input(maker, { maker = it }, Modifier.fillMaxWidth(), placeholder = "USB Arsenal") }
        problems["maker"]?.takeIf { maker.isNotEmpty() }?.let { Warn(it) }
        // Left empty, the host sees the phone's own serial
        Field("Serial") { Input(serial, { serial = it }, Modifier.fillMaxWidth(), placeholder = "from this phone") }
        problems["serial"]?.let { Warn(it) }
        Field("USB IDs") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Input(vid, { vid = it.take(4) }, Modifier.width(96.dp), placeholder = "1d6b", keyboard = ids)
                Text(":", LcdType.data)
                Input(pid, { pid = it.take(4) }, Modifier.width(96.dp), placeholder = "0104", keyboard = ids)
            }
        }
        problems["ids"]?.let { Warn(it) }
        Field("Power") { Segs(POWERS.map { "$it mA" }, POWERS.indexOf(power), { power = POWERS[it] }) }
        failed?.let { Fault(it, onLog = null) }
    }
}
