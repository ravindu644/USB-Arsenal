package com.usbarsenal.app.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.usbarsenal.app.lcd.Dither
import com.usbarsenal.app.lcd.Lcd
import com.usbarsenal.app.lcd.LcdType
import com.usbarsenal.app.lcd.LocalLcd
import com.usbarsenal.app.lcd.LocalReducedMotion
import com.usbarsenal.app.lcd.Pin
import com.usbarsenal.app.lcd.SpriteIcon
import com.usbarsenal.app.lcd.Sprites
import com.usbarsenal.app.lcd.dither
import com.usbarsenal.app.lcd.ghost

// The component kit: one section per box of section D, shown by Previews.kt. The strings are
// the reference boards' sample data

// Section A: sprites, dither, the three faces
@Composable
internal fun KitBasics() = Column(verticalArrangement = Arrangement.spacedBy(Lcd.section)) {
    val colors = LocalLcd.current
    Row(horizontalArrangement = Arrangement.spacedBy(Lcd.tight), verticalAlignment = Alignment.CenterVertically) {
        for (s in listOf(Sprites.Storage, Sprites.Tether, Sprites.Hid, Sprites.Usb, Sprites.Eject, Sprites.Spark, Sprites.Pointer)) {
            SpriteIcon(s, 2.dp)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Lcd.tight)) {
        Box(Modifier.size(64.dp).border(Lcd.pixel, colors.ink).dither(Dither.D25, colors.ink))
        Box(Modifier.size(64.dp).border(Lcd.pixel, colors.ink).dither(Dither.D50, colors.ink))
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) { SpriteIcon(Sprites.Storage, 4.dp, Modifier.ghost(colors.ground)) }
        Pin(4.dp)
    }
    Text("VT323 keeps its Case · motion ${if (LocalReducedMotion.current) "reduced" else "on"}", LcdType.data)
}

// D1: the key, the primary key, a disabled key
@Composable
internal fun KitKeys(focus: FocusRequester = remember { FocusRequester() }) = KeyRow {
    Key("Key", Modifier.weight(1f).focusRequester(focus)) {}
    Key("Primary", Modifier.weight(1f), primary = true) {}
    Key("Off", Modifier.weight(1f), enabled = false) {}
}

// D2: a mode, and the LUN strip growing to eight
@Composable
internal fun KitSegs() = Column(verticalArrangement = Arrangement.spacedBy(Lcd.tight)) {
    var mode by remember { mutableIntStateOf(0) }
    var luns by remember { mutableIntStateOf(2) }
    var lun by remember { mutableIntStateOf(0) }
    Segs(listOf("auto", "cd", "dvd", "disk", "rw"), mode, { mode = it })
    LunStrip(luns, lun, { lun = it }, {
        luns++
        lun = luns - 1
    })
}

// D3: on, off, and a function the kernel lacks
@Composable
internal fun KitDip() = Row(horizontalArrangement = Arrangement.spacedBy(Lcd.tight)) {
    var on by remember { mutableStateOf(true) }
    var off by remember { mutableStateOf(false) }
    Dip(on, { on = it }, "Storage")
    Dip(off, { off = it }, "Tether")
    Dip(false, {}, "HID", enabled = false)
}

// D4: Home's rows, one of them a function this kernel lacks
@Composable
internal fun KitRows() = Column(Modifier.bleed(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
    var storage by remember { mutableStateOf(true) }
    var tether by remember { mutableStateOf(false) }
    FunctionRow(Sprites.Storage, "Storage", "LUN0 Win11_24H2_x64.iso · dvd", "LUN1 pendrive.img · rw", storage, { storage = it }, {})
    FunctionRow(Sprites.Tether, "Tether", "off", "ncm · 192.168.42.129/24", tether, { tether = it }, {})
    FunctionRow(Sprites.Hid, "HID", "no hid in this kernel", "CONFIG_USB_CONFIGFS_F_HID", false, {}, {}, available = false)
}

// D5: the bottom bar's four states, switched from here
@Composable
internal fun KitPort(port: Port, onPort: (Port) -> Unit) {
    val states = listOf(Port.Bound("11201000.usb0"), Port.Replugging("starting storage"), Port.Stopping("stopping everything"), Port.Android)
    Segs(listOf("bound", "replug", "stop", "android"), states.indexOfFirst { it::class == port::class }, { onPort(states[it]) })
}

// D6: a drive serving a file read-write, the warning that goes with rw, and the files list. The folder is shown, never opened here
@SuppressLint("SdCardPath")
@Composable
internal fun KitDrive() = Column(verticalArrangement = Arrangement.spacedBy(Lcd.section)) {
    val files = listOf("Win11_24H2_x64.iso" to "5.8G", "archlinux-2026.10.01-x86_64.iso" to "1.2G", "drivers-2025.iso" to "412M", "pendrive.img" to "4.0G")
    var picked by remember { mutableIntStateOf(0) }
    var ejected by remember { mutableStateOf(false) }
    val (name, size) = files[picked]
    Drive(0, DriveImage(name, size, "disk", "rw"), serving = true, ejected = ejected, onEject = { ejected = true })
    Warn("host can write to this file")
    Column(Modifier.bleed(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FilesHeader("/sdcard/USB-Arsenal")
        files.forEachIndexed { i, (n, s) ->
            FileRow(n, s, if (i == 3) "lun1" else null, selected = i == picked && !ejected, onPick = {
                picked = i
                ejected = false
            })
        }
    }
}

// D7: the terminal, its newest line backlit
@Composable
internal fun KitTerminal() = Terminal(
    listOf(
        "$ ./install-backend.sh",
        "[+] Device architecture: aarch64",
        "[+] Installing the binary in /data/local/USB-Arsenal/bin",
        "cp: /data/local/USB-Arsenal/bin/usb-arsenal.new: No space left on device",
        "[-] Failed (exit 1)",
    ),
    Modifier.height(200.dp),
)

// D8: the tether chain, its counters, key-value rows and an input
@Composable
internal fun KitChain() = Column(verticalArrangement = Arrangement.spacedBy(Lcd.section)) {
    var gateway by remember { mutableStateOf(false) }
    var container by remember { mutableStateOf("") }
    Column {
        Node("Host")
        Wire("ncm")
        Node("Phone", "usb0", inverted = true)
        Wire("masquerade", up = true)
        Node("Upstream", "rmnet_data0")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Lcd.tight)) {
        ValueBox("Tx", "14.2 MB", Modifier.weight(1f))
        ValueBox("Rx", "3.1 MB", Modifier.weight(1f))
    }
    Column {
        Kv("Gateway") { Dip(gateway, { gateway = it }, "Gateway mode") }
        Kv("IPv4") { Text("192.168.42.130", LcdType.data) }
    }
    Field("Container") { Input(container, { container = it }, Modifier.fillMaxWidth(), placeholder = "OpenWrt") }
}

// D9: a command that fails: the shake, Reject, and the fault line under it
@Composable
internal fun KitFault() = Column(verticalArrangement = Arrangement.spacedBy(Lcd.tight)) {
    val shake = rememberShake()
    var fault by remember { mutableStateOf<String?>(null) }
    Key("Fail a command", Modifier.fillMaxWidth().shake(shake)) {
        shake.fire()
        fault = "[-] Daemon 16035 did not stop"
    }
    fault?.let { Fault(it, onLog = { fault = null }) }
}
