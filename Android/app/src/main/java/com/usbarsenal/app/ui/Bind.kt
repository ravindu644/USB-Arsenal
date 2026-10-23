package com.usbarsenal.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.usbarsenal.app.lcd.Inverted
import com.usbarsenal.app.lcd.Lcd
import com.usbarsenal.app.lcd.LcdType
import com.usbarsenal.app.lcd.LocalHaptics
import com.usbarsenal.app.lcd.LocalLcd
import com.usbarsenal.app.lcd.LocalReducedMotion
import com.usbarsenal.app.lcd.SpriteIcon
import com.usbarsenal.app.lcd.Sprites
import kotlinx.coroutines.delay

/*
 * The bind moment (the Bind board), once each time the port goes from Android to the gadget:
 * Pin climbs into the host's port in cuts, a tick each step, plugs in with a thud and sparks,
 * the screen inverts for one frame, then GADGET BOUND and what is bound. It holds a second and
 * cuts back. A touch or Back ends it early; with reduced motion it is the plugged frame only.
 */
@Composable
fun BindMoment(done: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val haptics = LocalHaptics.current
    val finish by rememberUpdatedState(done)
    // Pin's offset from plugged in, in dp, as the board's keyframes put it
    var y by remember { mutableIntStateOf(if (reduced) 0 else 250) }
    var dx by remember { mutableIntStateOf(0) }
    var plugged by remember { mutableStateOf(reduced) }
    var sparks by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(false) }
    var say by remember { mutableStateOf(reduced) }
    LaunchedEffect(Unit) {
        if (reduced) {
            haptics.bindPlug()
            delay(1000)
            finish()
            return@LaunchedEffect
        }
        // The board's 5s loop played once: 18%, 33%, 46%, 57%, then plugged at 66%
        delay(900); y = 180; dx = -6; haptics.bindClimb(0)
        delay(750); y = 120; dx = 6; haptics.bindClimb(1)
        delay(650); y = 64; dx = 0; haptics.bindClimb(2)
        delay(550); y = 28
        delay(450); y = 0; plugged = true; sparks = true; haptics.bindPlug()
        delay(50); flash = true
        delay(150); flash = false; say = true
        delay(300); sparks = false
        delay(700); finish()
    }
    BackHandler { finish() }
    // Nothing behind takes focus while it shows, as with a sheet, and what had it lets go, so a
    // key pressed now cannot flip a switch under it
    val focusManager = LocalFocusManager.current
    DisposableEffect(Unit) {
        SheetsOpen.count++
        focusManager.clearFocus()
        onDispose { SheetsOpen.count-- }
    }
    Inverted(flash) {
        val ink = LocalLcd.current.ink
        val ground = LocalLcd.current.ground
        Column(
            Modifier
                .fillMaxSize()
                .background(ground)
                .pointerInput(Unit) {
                    // Only a touch that starts on it ends it, not the lift of one already down
                    // when it came up
                    awaitPointerEventScope {
                        var touched = false
                        while (true) {
                            awaitPointerEvent().changes.forEach {
                                if (it.changedToDown()) touched = true
                                if (touched && it.changedToUp()) finish()
                                it.consume()
                            }
                        }
                    }
                }
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
            verticalArrangement = Arrangement.spacedBy(Lcd.section),
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth().height(330.dp).clipToBounds()) {
                val mid = maxWidth / 2
                // Under the host bar, so it climbs into it; its cable runs down and off to the right
                Box(Modifier.offset(mid - 48.dp + dx.dp, 66.dp + y.dp)) {
                    SpriteIcon(Sprites.Pin, 6.dp)
                    Box(Modifier.offset(60.dp, 108.dp).size(12.dp, 40.dp).background(ink))
                    Box(Modifier.offset(60.dp, 136.dp).size(400.dp, 12.dp).background(ink))
                }
                Inverted {
                    Box(Modifier.fillMaxWidth().height(96.dp).background(LocalLcd.current.ground).padding(start = Lcd.gutter, top = 22.dp)) {
                        Text("Host", LcdType.labelStrong)
                    }
                }
                // The port: an opening with its tongue, filled once Pin's plug is in it
                Box(
                    Modifier
                        .offset(mid - 34.dp, 66.dp)
                        .size(68.dp, 30.dp)
                        .background(if (plugged) ink else ground)
                        .border(Lcd.pixel, ground),
                ) {
                    if (!plugged) Box(Modifier.offset(4.dp, 4.dp).size(60.dp, 10.dp).background(ink))
                }
                if (sparks) {
                    SpriteIcon(Sprites.Spark, 4.dp, Modifier.offset(mid - 86.dp, 100.dp))
                    SpriteIcon(Sprites.Spark, 4.dp, Modifier.offset(mid + 58.dp, 100.dp))
                }
            }
            if (say) {
                Column(
                    Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(horizontal = Lcd.gutter),
                    verticalArrangement = Arrangement.spacedBy(Lcd.section),
                ) {
                    Text("Gadget\nbound", LcdType.hero, cursor = true)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        bound().chunked(2).forEach { Text(it.joinToString(" "), LcdType.data) }
                    }
                }
            }
        }
    }
}

// The functions bound, by their configfs names, as the C side makes them
private fun bound(): List<String> {
    val s = Gadget.status ?: return emptyList()
    return buildList {
        if (s.storage.isNotEmpty()) add("mass_storage.ua")
        s.tether?.let { add("${it.function}.ua") }
        if (s.hid.any { it.function == "keyboard" }) add("hid.ua_kbd")
        if (s.hid.any { it.function == "mouse" }) add("hid.ua_mouse")
    }
}
