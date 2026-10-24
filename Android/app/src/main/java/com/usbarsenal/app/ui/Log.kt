package com.usbarsenal.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext

/*
 * Log: this session's command output, as each command printed it. Clear empties it, Copy logs
 * copies it.
 */
@Composable
fun LogScreen(back: () -> Unit) {
    BackHandler(onBack = back)
    val context = LocalContext.current
    // Opening the log is reading the fault, so the line that pointed here has done its job
    LaunchedEffect(Unit) { Gadget.fault = null }
    val lines = Gadget.cli
    Screen(
        footer = {
            KeyRow {
                Key("Clear", Modifier.weight(1f)) { lines.clear() }
                Key("Copy logs", Modifier.weight(1f).focusRequester(focusOn()), primary = true) {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("usb-arsenal log", lines.joinToString("\n")))
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
