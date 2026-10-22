package com.usbarsenal.app

import android.os.Build
import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.usbarsenal.app.lcd.LcdTheme
import com.usbarsenal.app.lcd.Switches
import com.usbarsenal.app.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The splash leaves in one cut, as an LCD would, not the system's fade
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) splashScreen.setOnExitAnimationListener { it.remove() }
        // The LCD is lit in both system themes, so the bar icons are always dark
        val bars = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(bars, bars)
        val switches = Switches(applicationContext)
        setContent { LcdTheme(switches) { App() } }
    }
}
