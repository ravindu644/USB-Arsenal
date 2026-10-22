package com.usbarsenal.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.usbarsenal.app.lcd.Dither
import com.usbarsenal.app.lcd.FRAME_MS
import com.usbarsenal.app.lcd.Inverted
import com.usbarsenal.app.lcd.Lcd
import com.usbarsenal.app.lcd.LcdType
import com.usbarsenal.app.lcd.LocalHaptics
import com.usbarsenal.app.lcd.LocalLcd
import com.usbarsenal.app.lcd.LocalReducedMotion
import com.usbarsenal.app.lcd.Pin
import com.usbarsenal.app.lcd.SpriteIcon
import com.usbarsenal.app.lcd.Sprites
import com.usbarsenal.app.lcd.cursorOn
import com.usbarsenal.app.lcd.dither
import com.usbarsenal.app.lcd.ghost
import com.usbarsenal.app.lcd.lcdClickable
import com.usbarsenal.app.lcd.shoulderStep
import kotlinx.coroutines.delay

// ponytail: the groundwork on one screen, standing in for Home until E1 replaces it
@Composable
fun Kit() {
    val haptics = LocalHaptics.current
    var backs by remember { mutableIntStateOf(0) }
    var binds by remember { mutableIntStateOf(0) }
    var step by remember { mutableIntStateOf(0) }
    val first = remember { FocusRequester() }
    val colors = LocalLcd.current

    BackHandler { backs++ }
    LaunchedEffect(Unit) { first.requestFocus() }
    LaunchedEffect(binds) {
        if (binds == 0) return@LaunchedEffect
        for (i in 0..2) {
            haptics.bindClimb(i)
            delay(3L * FRAME_MS)
        }
        haptics.bindPlug()
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = Lcd.gutter).padding(top = Lcd.topInset),
        verticalArrangement = Arrangement.spacedBy(Lcd.section),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Lcd.tight)) {
            Pin(2.dp)
            Text("USB ARSENAL", LcdType.labelStrong)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text("KIT", LcdType.title)
            if (cursorOn()) Box(Modifier.padding(start = 4.dp, bottom = 8.dp).size(10.dp, 16.dp).background(colors.ink))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Lcd.tight), verticalAlignment = Alignment.CenterVertically) {
            for (s in listOf(Sprites.Storage, Sprites.Tether, Sprites.Hid, Sprites.Usb, Sprites.Eject, Sprites.Spark, Sprites.Pointer)) {
                SpriteIcon(s, 2.dp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Lcd.tight)) {
            Box(Modifier.size(64.dp).border(Lcd.pixel, colors.ink).dither(Dither.D25, colors.ink))
            Box(Modifier.size(64.dp).border(Lcd.pixel, colors.ink).dither(Dither.D50, colors.ink))
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                SpriteIcon(Sprites.Storage, 4.dp, Modifier.ghost(colors.ground))
            }
            Pin(4.dp)
        }
        Text("VT323 keeps its Case", LcdType.data)
        Text("motion ${if (LocalReducedMotion.current) "reduced" else "on"} · back $backs · step $step", LcdType.data)

        TestKey("BIND HAPTIC", Modifier.focusRequester(first)) { haptics.keyPress(); binds++ }
        TestRow("SEGMENT $step  L1 R1", Modifier.shoulderStep { step += it; haptics.tick() }) { haptics.confirm() }
        TestRow("REJECT HAPTIC") { haptics.reject() }
    }
}

@Composable
private fun Text(text: String, style: TextStyle) {
    // Silkscreen is a menu face and always uppercase; VT323 keeps its case
    val shown = if (style.fontFamily == LcdType.data.fontFamily) text else text.uppercase()
    BasicText(shown, style = style.copy(color = LocalLcd.current.ink))
}

@Composable
private fun TestKey(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .lcdClickable(interaction, outline = true, onClick = onClick)
            .heightIn(min = Lcd.touch)
            .border(Lcd.pixel, LocalLcd.current.ink)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, LcdType.labelStrong) }
}

@Composable
private fun TestRow(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val body = @Composable {
        Box(
            Modifier.fillMaxWidth().heightIn(min = Lcd.touch).background(LocalLcd.current.ground).padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) { Text(label, LcdType.labelStrong) }
    }
    Box(modifier.lcdClickable(interaction, outline = false, onClick = onClick)) {
        if (focused) Inverted(body) else body()
    }
}
