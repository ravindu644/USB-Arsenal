package com.usbarsenal.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.usbarsenal.app.backend.logView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Log: the backend's log, every run from the app or a shell and tether's daemon, in time order,
 * kept across launches; the app's own faults go into the same file. The first frame draws the
 * last read; while the screen is open the file's tail is read once a second, in its own shell.
 * Clear empties the file, Copy logs copies the lines as the file has them.
 */
@Composable
fun LogScreen(back: () -> Unit) {
    BackHandler(onBack = back)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Opening the log is reading the fault, so the line that pointed here has done its job
    LaunchedEffect(Unit) {
        Gadget.fault = null
        while (true) {
            Gadget.readLogTail()
            delay(1000)
        }
    }
    val lines = Gadget.log.mapNotNull(::logView)
    Screen(
        footer = {
            KeyRow {
                Key("Clear", Modifier.weight(1f)) { scope.launch { Gadget.clearLog() } }
                Key("Copy logs", Modifier.weight(1f).focusRequester(focusOn()), primary = true) {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("usb-arsenal log", Gadget.log.joinToString("\n")))
                }
            }
        },
    ) {
        Top { BackKey() }
        Title("Log")
        // A short window scrolls the screen to it, and it keeps a dozen lines
        Terminal(lines, Modifier.fill(min = 240.dp))
    }
}
