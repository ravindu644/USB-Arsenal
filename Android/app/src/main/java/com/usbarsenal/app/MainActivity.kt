package com.usbarsenal.app

import android.annotation.SuppressLint
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import com.usbarsenal.app.backend.Controller
import androidx.activity.enableEdgeToEdge
import com.usbarsenal.app.lcd.LcdTheme
import com.usbarsenal.app.lcd.Switches
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.usbarsenal.app.ui.App
import com.usbarsenal.app.ui.Stick

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The splash leaves in one cut, as an LCD would, not the system's fade
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) splashScreen.setOnExitAnimationListener { it.remove() }
        // The LCD is lit in both system themes, so the bar icons are always dark
        val bars = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(bars, bars)
        val switches = Switches(applicationContext)
        // The controller every command names, before the first one runs
        Controller.load(applicationContext)
        setContent { LcdTheme(switches) { App() } }
        // The launch window's Pin (Android 8 to 11's splash) has done its job once Compose draws;
        // left in place it would show wherever Compose does not paint, a resize or the keyboard
        window.setBackgroundDrawable(ColorDrawable(getColor(R.color.lcd)))
    }

    /*
     * Back and Escape mean back, focused or not, so they go to the back dispatcher before any
     * view: Compose takes both as a focus exit while anything is focused, and Escape has no
     * fallback to BACK in every key map (the S10's has none). BUTTON_B falls back to BACK.
     * Activity.dispatchKeyEvent is public; lint flags it for androidx's own @RestrictTo override.
     * Only a press whose down came here counts: one let go after a dialog or su's prompt closed is not ours.
     */
    private var backDown = false

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_ESCAPE && event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) backDown = true
        if (event.action == KeyEvent.ACTION_UP) {
            if (backDown && !event.isCanceled) onBackPressedDispatcher.onBackPressed()
            backDown = false
        }
        return true
    }

    private var hat = 0f to 0f

    // While Keys + mouse holds the left stick, it moves the host pointer, not focus. Most pads
    // send their D-pad as a hat on these same events: a hat that moved, pressed or let go, still
    // goes on, so Android turns it into the D-pad keys that move focus
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        val sink = Stick.sink
        if (sink != null && ev.isFromSource(InputDevice.SOURCE_JOYSTICK) && ev.action == MotionEvent.ACTION_MOVE) {
            sink(ev.getAxisValue(MotionEvent.AXIS_X), ev.getAxisValue(MotionEvent.AXIS_Y))
            val now = ev.getAxisValue(MotionEvent.AXIS_HAT_X) to ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val hatMoved = now != hat
            hat = now
            if (!hatMoved) return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }
}
