package com.usbarsenal.app

import android.annotation.SuppressLint
import androidx.core.graphics.drawable.toDrawable
import android.os.Build
import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import com.usbarsenal.app.backend.Cache
import com.usbarsenal.app.ui.HidSetup
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
        // The last reads and the trackpad's settings, so the first frame draws from them
        Cache.init(applicationContext)
        HidSetup.init(applicationContext)
        setContent { LcdTheme(switches) { App() } }
        // The launch window's Pin (Android 8 to 11's splash) has done its job once Compose draws;
        // left in place it would show wherever Compose does not paint, a resize or the keyboard
        window.setBackgroundDrawable(getColor(R.color.lcd).toDrawable())
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
    // goes on, so Android turns it into the D-pad keys that move focus. It goes on with the stick
    // centred, or a deflected stick would start a repeating D-pad key of its own
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        val sink = Stick.sink
        if (sink != null && ev.isFromSource(InputDevice.SOURCE_JOYSTICK) && ev.action == MotionEvent.ACTION_MOVE) {
            sink(ev.getAxisValue(MotionEvent.AXIS_X), ev.getAxisValue(MotionEvent.AXIS_Y))
            val now = ev.getAxisValue(MotionEvent.AXIS_HAT_X) to ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val hatMoved = now != hat
            hat = now
            if (!hatMoved) return true
            val hatOnly = ev.withoutStick()
            return super.dispatchGenericMotionEvent(hatOnly).also { hatOnly.recycle() }
        }
        return super.dispatchGenericMotionEvent(ev)
    }
}

// The same event with the left stick's axes at rest
private fun MotionEvent.withoutStick(): MotionEvent {
    val props = Array(pointerCount) { i -> MotionEvent.PointerProperties().also { getPointerProperties(i, it) } }
    val coords = Array(pointerCount) { i ->
        MotionEvent.PointerCoords().also {
            getPointerCoords(i, it)
            it.setAxisValue(MotionEvent.AXIS_X, 0f)
            it.setAxisValue(MotionEvent.AXIS_Y, 0f)
        }
    }
    return MotionEvent.obtain(downTime, eventTime, action, pointerCount, props, coords, metaState, buttonState, xPrecision, yPrecision, deviceId, edgeFlags, source, flags)
}
