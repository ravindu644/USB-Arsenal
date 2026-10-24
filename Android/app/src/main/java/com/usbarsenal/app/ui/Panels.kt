package com.usbarsenal.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.usbarsenal.app.lcd.dashes
import com.usbarsenal.app.lcd.Inverted
import com.usbarsenal.app.lcd.Lcd
import com.usbarsenal.app.lcd.LcdType
import com.usbarsenal.app.lcd.LocalHaptics
import com.usbarsenal.app.lcd.LocalLcd
import com.usbarsenal.app.lcd.SpriteIcon
import com.usbarsenal.app.lcd.Sprites
import com.usbarsenal.app.lcd.focusOutline
import com.usbarsenal.app.lcd.focusTick
import com.usbarsenal.app.lcd.dashedRect
import com.usbarsenal.app.lcd.ghost
import com.usbarsenal.app.lcd.lcdClickable
import com.usbarsenal.app.lcd.loop
import com.usbarsenal.app.lcd.stepSpec

// The panels of the function screens, each named after its class in lcd.css

// What a LUN serves: [kind] is the mode the host sees (dvd, cdrom, disk), [access] ro or rw
data class DriveImage(val name: String, val size: String, val kind: String, val access: String)

/*
 * .drive: a 150dp box with an inverted 40dp face (the LED, the LUN, eject) over the disc, the
 * file and `size · kind` with its access tag. Empty, the tray is out over the body:
 * a ghosted disc on the drive's ground, sliding in 3 steps of 2 frames. The body is the setup;
 * the LED is what runs: it blinks while the host is served, even with the pick ejected, is lit
 * for a pick not served yet, and hollow with neither.
 */
@Composable
fun Drive(lun: Int, image: DriveImage?, serving: Boolean, onEject: () -> Unit, modifier: Modifier = Modifier) {
    val ink = LocalLcd.current.ink
    val ground = LocalLcd.current.ground
    val haptics = LocalHaptics.current
    val out = image == null
    val tray by animateDpAsState(if (out) 0.dp else (-110).dp, stepSpec(6, steps = 3), label = "tray")
    Box(modifier.fillMaxWidth().height(150.dp).border(Lcd.pixel, ink).padding(Lcd.pixel).clipToBounds()) {
        if (image != null) {
            Row(
                Modifier.fillMaxSize().padding(top = 40.dp).padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SpriteIcon(Sprites.Storage, 4.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Lcd.lines)) {
                    Marquee(image.name, LcdType.data, Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        // A file outside the folder has no size to show here
                        Text(listOf(image.size, image.kind).filter { it.isNotEmpty() }.joinToString(" · "), LcdType.data)
                        Tag(image.access)
                    }
                }
            }
        }
        // The tray hides above the body, under the face, until it slides out
        Box(
            Modifier
                .padding(top = 40.dp, start = 14.dp, end = 14.dp)
                .fillMaxWidth()
                .height(106.dp)
                .offset { IntOffset(0, tray.roundToPx()) }
                // No frame of its own: inside the drive's box a second one read as a stray rectangle
                .background(ground),
            contentAlignment = Alignment.Center,
        ) { SpriteIcon(Sprites.Storage, 5.dp, Modifier.ghost(ground)) }
        Inverted {
            Row(
                Modifier.fillMaxWidth().height(40.dp).background(LocalLcd.current.ground).padding(start = 12.dp, end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LedDot(if (serving) Led.BUSY else if (out) Led.OFF else Led.ON)
                Text("lun$lun", LcdType.labelStrong)
                Spacer(Modifier.weight(1f))
                if (image != null) {
                    val interaction = remember { MutableInteractionSource() }
                    Box(
                        Modifier
                            .size(44.dp, 40.dp)
                            .lcdClickable(interaction, outline = true, role = Role.Button) {
                                haptics.gestureEnd()
                                onEject()
                            }
                            .semantics { contentDescription = "Eject" },
                        contentAlignment = Alignment.Center,
                    ) { SpriteIcon(Sprites.Eject, 2.dp) }
                }
            }
        }
    }
}

// FILES and the folder the list reads
@Composable
fun FilesHeader(path: String, modifier: Modifier = Modifier, label: String = "Files") = Row(
    modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(Lcd.tight),
) {
    Text(label, LcdType.labelStrong)
    // A path longer than the room left slides; it never wraps under the label
    Marquee(path, LcdType.data, Modifier.weight(1f), end = true)
}

/*
 * A file in the list: its name, the LUN that holds it as a tag, its size. The picked file is
 * inverted, a focused one too. A file another LUN holds cannot be picked.
 */
@Composable
fun FileRow(name: String, size: String, lun: String?, selected: Boolean, onPick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val haptics = LocalHaptics.current
    val body = @Composable {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).background(LocalLcd.current.ground).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(Lcd.tight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Marquee(name, LcdType.data, Modifier.weight(1f))
            if (lun != null) Tag(lun)
            Text(size, LcdType.data)
        }
    }
    Box(
        modifier
            .lcdClickable(interaction, outline = false, role = Role.RadioButton, enabled = lun == null) {
                haptics.tick()
                onPick()
            }
            .semantics { this.selected = selected },
    ) { Inverted(selected || focused, body) }
}

// .panel: a 2dp box over an inverted 40dp .face, as Identity's profile draws it. A lit card keeps
// the screen's [edge]: drawn in the card's own colours, it would vanish into the screen
@Composable
fun Panel(face: @Composable RowScope.() -> Unit, edge: Color = LocalLcd.current.ink, modifier: Modifier = Modifier, body: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().background(LocalLcd.current.ground).border(Lcd.pixel, edge).padding(Lcd.pixel)) {
        Inverted {
            Row(
                Modifier.fillMaxWidth().height(40.dp).background(LocalLcd.current.ground).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = face,
            )
        }
        body()
    }
}

// .node: one end of the tether chain, its name left and its value right; the phone is inverted
@Composable
fun Node(label: String, value: String? = null, inverted: Boolean = false) {
    val rule = LocalLcd.current.ink
    val body = @Composable {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Lcd.touch).background(LocalLcd.current.ground).border(Lcd.pixel, rule).padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, LcdType.labelStrong)
            if (value != null) Text(value, LcdType.data)
        }
    }
    Inverted(inverted, body)
}

/*
 * .wire: a dashed 6dp line joining two nodes, a packet stepping along it (4 steps a 1.2s
 * loop, against the line when [up]) and what carries it to the right. Reduced motion, or a link
 * that is not [moving], holds the packet at its first step.
 */
@Composable
fun Wire(label: String, up: Boolean = false, moving: Boolean = true) {
    val ink = LocalLcd.current.ink
    // A link that is down carries nothing: its packet rests
    val step = loop(1200, moving) { it * 4 / 1200 }
    Box(Modifier.fillMaxWidth().height(40.dp)) {
        Box(Modifier.padding(start = 44.dp).size(6.dp, 40.dp).dashes(ink))
        Box(
            Modifier
                .padding(start = 39.dp)
                .offset(y = (if (up) 32 - 8 * step else 8 * step).dp)
                .size(16.dp, 8.dp)
                .background(ink),
        )
        Text(label, LcdType.data, Modifier.padding(start = 66.dp, top = 10.dp))
    }
}

// .box: a value under its label, for counters such as Tx and Rx
@Composable
fun ValueBox(label: String, value: String, modifier: Modifier = Modifier) = Column(
    modifier.border(Lcd.pixel, LocalLcd.current.ink).padding(horizontal = 10.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(Lcd.lines),
) {
    Text(label, LcdType.labelStrong)
    Text(value, LcdType.data)
}

/*
 * .inp: one line of VT323 in a 2dp box, with an ink cursor and the dashed focus cursor. The
 * placeholder states the default.
 */
@Composable
fun Input(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    // false locks it, its border dashed as a disabled key's: what it sets cannot change now, and
    // its value stays readable (a ghost's dither would knock the VT323 strokes out)
    enabled: Boolean = true,
    singleLine: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val ink = LocalLcd.current.ink
    BasicTextField(
        value,
        onChange,
        modifier
            .focusTick()
            .then(if (focused) Modifier.focusOutline(ink) else Modifier)
            .heightIn(min = Lcd.touch)
            .then(if (enabled) Modifier.border(Lcd.pixel, ink) else Modifier.dashedRect(ink, -Lcd.pixel)),
        enabled = enabled,
        textStyle = LcdType.data.copy(color = ink),
        singleLine = singleLine,
        keyboardOptions = keyboard,
        interactionSource = interaction,
        cursorBrush = SolidColor(ink),
        decorationBox = { field ->
            Box(Modifier.padding(horizontal = 10.dp, vertical = if (singleLine) 0.dp else 8.dp), contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart) {
                if (value.isEmpty()) Text(placeholder, LcdType.data)
                field()
            }
        },
    )
}

// .field: a label over its input
@Composable
fun Field(label: String, modifier: Modifier = Modifier, input: @Composable () -> Unit) = Column(
    modifier,
    verticalArrangement = Arrangement.spacedBy(6.dp),
) {
    Text(label, LcdType.labelStrong)
    input()
}
