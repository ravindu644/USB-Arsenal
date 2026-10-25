package com.usbarsenal.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.usbarsenal.app.lcd.Lcd
import com.usbarsenal.app.lcd.LcdType
import com.usbarsenal.app.lcd.Sprites

/*
 * HID: the way into Keys + mouse and Quacky, as cards, and the setup a start brings up: the
 * keyboard's poll interval. A changed interval while HID runs is applied, or discarded, on
 * the way back.
 */
@Composable
fun HidScreen(back: () -> Unit, openKeys: () -> Unit, openQuacky: () -> Unit) {
    BackHandler(onBack = back)
    val nodes = Gadget.status?.hid.orEmpty().map { it.node.substringAfterLast('/') }.filter { it.isNotEmpty() }
    val interval = HidSetup.interval
    Box(Modifier.fillMaxSize()) {
        Screen {
            Top { BackKey() }
            Title("HID")
            Card("Keyboard and mouse", nodes.joinToString(" · ").ifEmpty { "a trackpad and keys" }, Sprites.Hid, openKeys, Modifier.focusRequester(focusOn()).opener("hid/keys"))
            Card("Quacky", "a script that types", Sprites.Duck, openQuacky, Modifier.opener("hid/quacky"))
            Row(Modifier.fillMaxWidth().heightIn(min = Lcd.touch), horizontalArrangement = Arrangement.spacedBy(Lcd.tight), verticalAlignment = Alignment.CenterVertically) {
                // On a kernel without the f_hid control the host gets the stock period whatever is
                // set: 1 ms at high speed, 10 ms on a USB 1.1 host. One line either way, so the row
                // never re-lays itself when that is learned
                val fixed = HidSetup.fixed
                val fullSpeed = Gadget.status?.speed == "full-speed"
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Lcd.lines)) {
                    Text("Interval", LcdType.labelStrong)
                    Marquee(if (fixed) "${if (fullSpeed) 10 else 1} ms, fixed by this kernel" else periodText(interval, fullSpeed), LcdType.data, Modifier.fillMaxWidth())
                }
                // Lower is faster; the exponent stops at 16, where a USB interrupt endpoint does
                Key("-", Modifier.width(44.dp).semantics { contentDescription = "Shorter interval" }, padding = 0.dp, enabled = !fixed && interval > 1) { HidSetup.set(interval - 1) }
                Text("%02d".format(java.util.Locale.ROOT, if (fixed) DEFAULT_INTERVAL else interval), LcdType.data)
                Key("+", Modifier.width(44.dp).semantics { contentDescription = "Longer interval" }, padding = 0.dp, enabled = !fixed && interval < 16) { HidSetup.set(interval + 1) }
            }
        }
        UnappliedSheet(Fn.HID, back)
    }
}
