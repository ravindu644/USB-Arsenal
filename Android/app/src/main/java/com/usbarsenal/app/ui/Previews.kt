package com.usbarsenal.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.usbarsenal.app.lcd.Lcd
import com.usbarsenal.app.lcd.LcdTheme
import com.usbarsenal.app.lcd.Switches

// One preview per box of section D, at the phone size the reference boards use. They show the Kit's sections

@Composable
private fun Board(content: @Composable ColumnScope.() -> Unit) {
    val context = LocalContext.current
    LcdTheme(remember { Switches(context) }) {
        Column(Modifier.fillMaxSize().padding(Lcd.gutter), verticalArrangement = Arrangement.spacedBy(Lcd.section), content = content)
    }
}

@Preview(name = "D1 Top bar, title, keys", widthDp = 390, heightDp = 844)
@Composable
private fun KeysPreview() = Board {
    Top { BackKey() }
    Title("Storage")
    KitKeys()
}

@Preview(name = "D2 Segmented control, LUN strip", widthDp = 390, heightDp = 844)
@Composable
private fun SegsPreview() = Board { KitSegs() }

@Preview(name = "D3 DIP switch", widthDp = 390, heightDp = 844)
@Composable
private fun DipPreview() = Board { KitDip() }

@Preview(name = "D4 Function rows", widthDp = 390, heightDp = 844)
@Composable
private fun RowsPreview() = Board { KitRows() }

@Preview(name = "D5 Bottom bar", widthDp = 390, heightDp = 844)
@Composable
private fun BottomBarPreview() = Board {
    BottomBar(Port.Bound("11201000.usb0")) {}
    BottomBar(Port.Replugging("usb-arsenal storage start")) {}
    BottomBar(Port.Android) {}
}

@Preview(name = "D6 Drive, warning, files", widthDp = 390, heightDp = 844)
@Composable
private fun DrivePreview() = Board { KitDrive() }

@Preview(name = "D7 Terminal", widthDp = 390, heightDp = 844)
@Composable
private fun TerminalPreview() = Board { KitTerminal() }

@Preview(name = "D8 Tether chain, rows, input", widthDp = 390, heightDp = 844)
@Composable
private fun ChainPreview() = Board { KitChain() }

@Preview(name = "D9 Shake and fault", widthDp = 390, heightDp = 844)
@Composable
private fun FaultPreview() = Board {
    KitFault()
    Fault("[-] Daemon 16035 did not stop", onLog = {})
}
