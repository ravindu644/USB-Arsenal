package com.usbarsenal.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.usbarsenal.app.lcd.LocalLcd
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.usbarsenal.app.BuildConfig
import com.usbarsenal.app.backend.Backend
import com.usbarsenal.app.backend.BackendState
import com.usbarsenal.app.backend.backendState
import com.usbarsenal.app.lcd.Lcd
import com.usbarsenal.app.lcd.LcdType
import com.usbarsenal.app.lcd.Pin
import com.usbarsenal.app.lcd.Sprites
import kotlinx.coroutines.delay

// Status is re-read this often while a gadget screen is in front (and after every command)
const val POLL_MS = 2_000L

// Re-reads status while the screen is started; the read lane never waits on a command
@Composable
fun PollStatus() {
    val owner = LocalLifecycleOwner.current
    // Apart from the polling, so the first status never waits on a slow check
    LaunchedEffect(Unit) { Gadget.loadOnce() }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                Gadget.refresh()
                delay(POLL_MS)
            }
        }
    }
}

// The attention key's words for a backend that needs something: what is wrong, and the fix
private fun attention(b: Backend): Pair<String, String>? = when (b.state) {
    BackendState.READY -> null
    // The same version rebuilt is still an update, but "1.2.0 → 1.2.0" would read as nothing
    BackendState.UPDATE_AVAILABLE ->
        "Backend update" to (b.installed?.takeIf { it != BuildConfig.VERSION_NAME }?.let { "$it → ${BuildConfig.VERSION_NAME}" } ?: "update")
    BackendState.NOT_INSTALLED -> "Not installed" to "install"
    BackendState.MODULE_MISSING -> "Module missing" to "reinstall"
    BackendState.CORRUPTED -> "Backend broken" to "reinstall"
    BackendState.NO_ROOT -> "No root" to "grant"
}

/*
 * Home is the gadget: each function a row with its live facts and its switch, which starts it
 * with what its screen set up, or stops it; the bottom bar says who holds the port. A backend
 * that needs something says so in one key above Log and Settings, and holds the switches here
 * and on every function's screen until it is fixed (an update does not hold them).
 */
@Composable
fun HomeScreen(open: (Fn) -> (() -> Unit)?, openLog: (() -> Unit)?, openSettings: (() -> Unit)?, fix: (Backend) -> Unit) {
    PollStatus()
    val context = LocalContext.current
    LaunchedEffect(Unit) { Gadget.backend = backendState(context) }
    val backend = Gadget.backend
    LaunchedEffect(Unit) {
        Picks.load(context)
        TetherSetup.load(context)
        HidSetup.load(context)
    }
    val picks = Picks.all
    val status = Gadget.status
    val caps = Gadget.caps
    val busy = Gadget.busy
    val needs = backend?.takeIf { it.state != BackendState.READY }
    // Held until the backend is known to be there, and while it needs fixing (an update does not)
    val held = busy != null || backend.holds()
    val up = Fn.entries.count { status.isOn(it) }
    val shown = rememberTyped(Fn.entries.size, 1)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val port = when {
        busy != null -> Port.Replugging("usb-arsenal ${busy.typed}")
        status?.bound == true -> Port.Bound(status.udc)
        else -> Port.Android
    }

    // Stop all fails on the whole gadget: the bar shakes and the fault sits above it
    val barShake = rememberShake()
    val wholeFault = Gadget.fault?.takeIf { it.where == null }
    LaunchedEffect(wholeFault) { if (wholeFault != null) barShake.fire() }
    Screen(bottomBar = { BottomBar(port, { Gadget.run(null, "stop") { if (it) Gadget.forgetStart() } }, Modifier.shake(barShake)) }) {
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
                    onOpen = open(fn),
                    modifier = Modifier
                        .drawnFrom(i, shown)
                        // A rule over every row but the first; inside drawnFrom, so it comes in with its row
                        .then(if (i > 0) Modifier.drawWithContent { drawContent(); drawRect(ink, size = Size(size.width, Lcd.pixel.toPx())) } else Modifier)
                        .shake(shake)
                        .then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                    available = caps.has(fn),
                    // Storage starts from its picks; with none there is nothing to serve
                    enabled = !held && (on || startable(fn)),
                )
                // On the gutter, under the row that bleeds past it
                if (fault != null) Fault(fault.message, onLog = openLog, modifier = Modifier.padding(horizontal = 10.dp))
            }
        }
        needs?.let { b -> attention(b)?.let { (label, fixLabel) -> Alert(label, fixLabel, onClick = { fix(b) }) } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Lcd.tight)) {
            Key("Log", Modifier.weight(1f), enabled = openLog != null) { openLog?.invoke() }
            Key("Settings", Modifier.weight(1f), enabled = openSettings != null) { openSettings?.invoke() }
        }
        if (wholeFault != null) Fault(wholeFault.message, onLog = openLog)
    }
}

