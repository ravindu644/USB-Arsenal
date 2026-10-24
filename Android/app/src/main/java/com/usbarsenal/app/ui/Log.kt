package com.usbarsenal.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.usbarsenal.app.backend.Root
import com.usbarsenal.app.backend.WORKSPACE
import com.usbarsenal.app.backend.plain
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// What the tether process (DHCP, the uplink, the gateway) writes; it appends, so Clear can empty it
private const val TETHER_LOG = "$WORKSPACE/Logs/tether.log"

/*
 * The Tether tab's lines and how far into the file they reach, kept for the process like the
 * CLI tab's: coming back to the tab carries on from there, so nothing repeats and a Clear
 * stays cleared.
 */
private object TetherLog {
    val lines = mutableStateListOf<String>()
    var offset: Long? = null
    // Clears done so far; the tail that starts after one empties the tab
    var cleared = 0
}

/*
 * Log: CLI is this session's command output, as each command printed it; Tether follows
 * Logs/tether.log on its own root shell, open only while that tab shows. Clear empties
 * the tab in front (on Tether, the log file itself), Copy logs copies it.
 */
@Composable
fun LogScreen(back: () -> Unit) {
    BackHandler(onBack = back)
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tether = TetherLog.lines
    // A clear of the Tether tab restarts its tail on the emptied file
    var clears by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(tab, clears) {
        if (tab != 1) return@LaunchedEffect
        // A Clear empties the tab here, once the tail before it is gone, so none of its lines
        // land after the clear
        val mine = clears
        if (mine != TetherLog.cleared) {
            tether.clear()
            TetherLog.offset = 0
            TetherLog.cleared = mine
        }
        // The last 200 lines the first time; after that, the bytes since the tab last showed.
        // A file now shorter than that was emptied elsewhere: its last 200 lines again, under a
        // line that says so
        val size = Root.read("stat -c %s $TETHER_LOG").out.firstOrNull()?.toLongOrNull()
        if (size == null) {
            tether += "# no tether log yet: a tether start writes it"
            return@LaunchedEffect
        }
        val at = TetherLog.offset
        if (at != null && at > size) tether += "# the log started over"
        val from = at?.takeIf { it <= size }?.let { "-c +${it + 1}" } ?: "-n 200"
        // -f, not -F: Android 8 to 10 ship a toybox without -F, and the file is never replaced
        val tail = Root.stream("tail $from -f $TETHER_LOG")
        if (tail == null) {
            tether += "[-] Cannot follow $TETHER_LOG: no root"
            return@LaunchedEffect
        }
        try {
            tail.lines.collect {
                tether += plain(it)
                tether.keepLast()
            }
        } finally {
            // Leaving the tab cancels this: the tail's shell goes with it, and where it got to is kept
            withContext(NonCancellable) {
                tail.close()
                // Not after a Clear: the tail that follows it starts from the emptied file
                if (clears == mine) TetherLog.offset = Root.read("stat -c %s $TETHER_LOG").out.firstOrNull()?.toLongOrNull()
            }
        }
    }
    // Opening the log is reading the fault, so the line that pointed here has done its job
    LaunchedEffect(Unit) { Gadget.fault = null }
    val lines = if (tab == 0) Gadget.cli else tether
    Screen(
        footer = {
            KeyRow {
                Key("Clear", Modifier.weight(1f)) {
                    if (tab == 0) {
                        lines.clear()
                    } else {
                        // The file itself, so a relaunch does not read it all back. The tether
                        // process appends (O_APPEND), so it writes on at the new end. Its own
                        // shell: this is not a gadget change
                        scope.launch {
                            if (Root.read(": > $TETHER_LOG").ok) clears++
                        }
                    }
                }
                Key("Copy logs", Modifier.weight(1f).focusRequester(focusOn()), primary = true) {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("usb-arsenal log", lines.joinToString("\n")))
                }
            }
        },
    ) {
        Top { BackKey() }
        Title("Log")
        Segs(listOf("CLI", "Tether"), tab, { tab = it })
        // A short window scrolls the screen to it, and it keeps a dozen lines. Each tab has its
        // own, so switching opens on that tab's newest line instead of the other's place
        key(tab) { Terminal(lines, Modifier.fill(min = 240.dp)) }
    }
}
