package com.usbarsenal.app

import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.usbarsenal.app.lcd.LcdTheme
import com.usbarsenal.app.lcd.Switches
import com.usbarsenal.app.ui.Kit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The LCD is lit in both system themes, so the bar icons are always dark
        val bars = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(bars, bars)
        val switches = Switches(applicationContext)
        setContent { LcdTheme(switches) { Kit() } }
    }
}
