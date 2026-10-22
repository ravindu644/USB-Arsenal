package com.usbarsenal.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.usbarsenal.app.lcd.LcdType
import com.usbarsenal.app.lcd.Pin
import com.usbarsenal.app.lcd.SpriteIcon
import com.usbarsenal.app.lcd.Sprites

// Quacky: not built yet. The duck and Pin, and a line that says so; never shaded, nothing to press
@Composable
fun QuackyScreen(back: () -> Unit) {
    BackHandler(onBack = back)
    Screen {
        Top { BackKey() }
        Title("Quacky")
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
                    SpriteIcon(Sprites.Duck, 8.dp)
                    Pin(6.dp)
                }
                Text("Under construction", LcdType.labelStrong)
            }
        }
    }
}
