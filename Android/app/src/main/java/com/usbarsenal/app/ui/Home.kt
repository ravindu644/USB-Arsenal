package com.usbarsenal.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.usbarsenal.app.lcd.LocalLcd
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.usbarsenal.app.BuildConfig
import com.usbarsenal.app.backend.Backend
import com.usbarsenal.app.backend.BackendState
import com.usbarsenal.app.lcd.Lcd
import com.usbarsenal.app.lcd.LcdType
import com.usbarsenal.app.lcd.Pin
import com.usbarsenal.app.lcd.Sprites

// The attention key's words for a backend that needs something: what is wrong, and the fix
private fun attention(b: Backend): Pair<String, String>? = when (b.state) {
    BackendState.READY -> null
    // The same version rebuilt is still an update, but "1.2.0 → 1.2.0" would read as nothing
    BackendState.UPDATE_AVAILABLE ->
        "Backend update" to (b.installed?.takeIf { it != BuildConfig.VERSION_NAME }?.let { "$it → ${BuildConfig.VERSION_NAME}" } ?: "update")
    BackendState.NOT_INSTALLED -> "Not installed" to "install"
    BackendState.MODULE_MISSING -> "Module missing" to "reinstall"
    BackendState.CORRUPTED -> "Backend broken" to "reinstall"
    // The shade over every screen says it
    BackendState.NO_ROOT -> null
}

// The bar's detail once status has not answered for a while; the rows keep the last answer
const val NO_ANSWER = "no answer from usb-arsenal"

/*
 * Home is the gadget: each function a row with its live facts and its switch, which starts it
 * with what its screen set up, or stops it; the bottom bar says who holds the port. A backend
 * that needs something says so in one key above Log and Settings, and holds the switches here
 * and on every function's screen until it is fixed (an update does not hold them).
 */
@Composable
fun HomeScreen(open: (Fn) -> Unit, openLog: () -> Unit, openSettings: () -> Unit, fix: (Backend) -> Unit) {
    val backend = Gadget.backend
    val status = Gadget.status
    val caps = Gadget.caps
    val busy = Gadget.busy
    val needs = backend?.takeIf { it.state != BackendState.READY }
    // Held until the backend is known to be there, and while it needs fixing (an update does not)
    val held = busy != null || backend.holds()
    val up = Fn.entries.count { status.isOn(it) }
    val shown = rememberTyped(Fn.entries.size, 1)
    val first = remember { FocusRequester() }
    // A row Back is returning to asks after this, so it wins; one that is gone leaves this
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    // Three failed reads in a row: what is shown is the last answer, and the bar says so
    val silent = Gadget.misses >= 3
    val port = when {
        busy?.stopping == true -> Port.Stopping(busy.says)
        busy != null -> Port.Replugging(busy.says)
        status?.bound == true -> Port.Bound(if (silent) NO_ANSWER else status.udc)
        else -> Port.Android(if (silent) NO_ANSWER else "")
    }

    // Stop all fails on the whole gadget: the bar shakes and the fault sits above it
    val barShake = rememberShake()
    val wholeFault = Gadget.fault?.takeIf { it.where == null }
    LaunchedEffect(wholeFault) { if (wholeFault != null) barShake.fire() }
    val stopAll = { Gadget.run(null, "stop") }
    Screen(bottomBar = { BottomBar(port, { stopAll() }, Modifier.shake(barShake), Gadget.binds, Gadget.fails) }) {
        Top {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Pin(2.dp)
                Text("USB Arsenal", LcdType.labelStrong)
            }
            Text("v${BuildConfig.VERSION_NAME}", LcdType.data)
        }
        Text(if (up == 0) "idle" else "$up up", LcdType.hero)
        val ink = LocalLcd.current.ink
        Column(Modifier.bleed(10.dp)) {
            Fn.entries.forEachIndexed { i, fn ->
                val shake = rememberShake()
                val fault = Gadget.fault?.takeIf { it.where == fn }
                LaunchedEffect(fault) { if (fault != null) shake.fire() }
                val (l1, l2) = rowLines(fn, status, caps, busy)
                val on = status.isOn(fn)
                FunctionRow(
                    icon = when (fn) {
                        Fn.STORAGE -> Sprites.Storage
                        Fn.TETHER -> Sprites.Tether
                        Fn.HID -> Sprites.Hid
                    },
                    label = fn.label,
                    line1 = l1,
                    line2 = l2,
                    on = on,
                    onToggle = { flip(fn, it) },
                    onOpen = { open(fn) },
                    modifier = Modifier
                        .drawnFrom(i, shown)
                        // A rule over every row but the first; inside drawnFrom, so it comes in with its row
                        .then(if (i > 0) Modifier.drawWithContent { drawContent(); drawRect(ink, size = Size(size.width, Lcd.pixel.toPx())) } else Modifier)
                        .shake(shake)
                        .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                        .opener("home/${fn.name}"),
                    available = caps.has(fn),
                    // Storage starts from its picks; with none there is nothing to serve
                    enabled = !held && (on || startable(fn)),
                )
                // On the gutter, under the row that bleeds past it
                if (fault != null) Fault(fault.message, onLog = openLog, modifier = Modifier.padding(horizontal = 10.dp).opener("home/fault/${fn.name}"))
            }
        }
        // Under the rows, as the board has them; a short window scrolls to them
        needs?.let { b -> attention(b)?.let { (label, fixLabel) -> Alert(label, fixLabel, onClick = { fix(b) }, Modifier.opener("home/fix")) } }
        KeyRow {
            Key("Log", Modifier.weight(1f).opener("home/log"), onClick = openLog)
            Key("Settings", Modifier.weight(1f).opener("home/settings"), onClick = openSettings)
        }
        if (wholeFault != null) Fault(wholeFault.message, onLog = openLog, modifier = Modifier.opener("home/fault"))
    }
}

