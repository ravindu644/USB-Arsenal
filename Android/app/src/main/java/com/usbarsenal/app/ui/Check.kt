package com.usbarsenal.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.usbarsenal.app.backend.Capabilities
import com.usbarsenal.app.backend.Probe
import com.usbarsenal.app.lcd.Lcd
import com.usbarsenal.app.lcd.LcdType

enum class Verdict(val word: String) { OK("ok"), PARTIAL("partial"), MISSING("missing") }

// A probe in a panel: its [value] when it is ok, else its [tag]; [why] says what that costs and
// [option] what the kernel needs for it
class ProbeLine(val label: String, val value: String?, val tag: String? = null, val why: String? = null, val option: String? = null)

// [lines] is empty when the panel's own function is missing: [why] and [option] then say it all
class ProbePanel(val label: String, val verdict: Verdict, val lines: List<ProbeLine>, val why: String? = null, val option: String? = null)


/*
 * check --json as a panel per function. A probe that needs another sits in its panel and goes
 * with it: no CD-ROM without mass storage, no NAT without a tether function. The options are
 * check.c's own hints. [picked] is the controller Check ran with.
 */
fun checkPanels(c: Capabilities, picked: String?): List<ProbePanel> {
    fun line(label: String, p: Probe, why: String, option: String? = null, value: String = "ok") =
        if (p == Probe.OK) ProbeLine(label, value) else ProbeLine(label, null, if (p == Probe.CAVEAT) "caveat" else "missing", why, option)
    // A panel whose function works is ok, or partial when a line in it is not
    fun panel(label: String, lines: List<ProbeLine>) = ProbePanel(label, if (lines.all { it.tag == null }) Verdict.OK else Verdict.PARTIAL, lines)

    val core = c.root == Probe.OK && c.configfs == Probe.OK && c.udc == Probe.OK
    val must = listOf(
        line("Root", c.root, "nothing works"),
        line("configfs", c.configfs, "no configfs usb gadget; it needs a custom kernel", "CONFIG_USB_CONFIGFS"),
        // A 0 is no controller in /sys/class/udc, or the pick gone from it: a dual-role port in host mode lists none
        line(
            "Controller", c.udc,
            "${picked?.let { "$it is not" } ?: "no usb device controller is"} listed now; the port may be in host mode (otg)",
            value = c.udcName,
        ),
        line("Gadget", c.gadget, if (core) "could not create a gadget" else "needs the lines above first"),
    )
    val mustHave = ProbePanel("Must have", if (must.all { it.tag == null }) Verdict.OK else Verdict.MISSING, must)
    // check probes no function until every must have line is ok
    if (!c.requirementsMet) return listOf(mustHave)

    val storage = if (c.massStorage != Probe.OK) {
        ProbePanel("Storage", Verdict.MISSING, emptyList(), "no drives for the host", "CONFIG_USB_CONFIGFS_MASS_STORAGE")
    } else {
        panel("Storage", listOf(ProbeLine("Drives", "ok"), line("CD-ROM", c.cdrom, "plain isos such as windows cannot boot as a cd-rom")))
    }
    val tether = if (c.ncm != Probe.OK && c.rndis != Probe.OK) {
        // NCM built but unsafe on this Samsung kernel needs its fix, not its option
        val ncm = if (c.ncm == Probe.CAVEAT) "the f_ncm fix in kernel-patches/" else "CONFIG_USB_CONFIGFS_NCM"
        ProbePanel("Tether", Verdict.MISSING, emptyList(), "no network for the host", "$ncm or CONFIG_USB_CONFIGFS_RNDIS")
    } else {
        panel(
            "Tether",
            listOf(
                // -1 is the Samsung kernel NCM would crash; 0 is a kernel without f_ncm. Tether falls back either way
                if (c.ncm == Probe.CAVEAT) line("NCM", c.ncm, "unsafe on this samsung kernel; tether uses rndis", "the f_ncm fix in kernel-patches/")
                else line("NCM", c.ncm, "tether uses rndis", "CONFIG_USB_CONFIGFS_NCM"),
                line("RNDIS", c.rndis, "no tether on old windows hosts", "CONFIG_USB_CONFIGFS_RNDIS"),
                line("NAT", c.nat, "the host gets no internet through the phone", "CONFIG_IP_NF_NAT, CONFIG_IP_NF_TARGET_MASQUERADE"),
                line("NAT6", c.nat6, "the host gets ipv4 only", "CONFIG_IP6_NF_NAT, CONFIG_IP6_NF_TARGET_MASQUERADE"),
            ),
        )
    }
    // The interval is an extra of our f_hid patch, never upstream, so a fixed one is no fault: HID
    // stays ok, and the line says what the patch would give
    val hid = if (c.hid != Probe.OK) {
        ProbePanel("HID", Verdict.MISSING, emptyList(), "no keyboard and mouse", "CONFIG_USB_CONFIGFS_F_HID")
    } else {
        panel(
            "HID",
            listOf(
                ProbeLine("Keyboard and mouse", "ok"),
                // A backend older than the probe does not say; the HID screen then finds out once it runs
                when (c.hidInterval) {
                    Probe.OK -> ProbeLine("Interval", "settable")
                    null -> ProbeLine("Interval", "unknown")
                    else -> ProbeLine("Interval", "1 ms, fixed", why = "the f_hid patch in kernel-patches/ makes it settable")
                },
            ),
        )
    }
    return listOf(mustHave, storage, tether, hid)
}

// The panels and their rows while check runs: every one there is, as a kernel with everything has them
val CHECK_SKELETON = Probe.OK.let { ok -> checkPanels(Capabilities(ok, ok, ok, ok, ok, ok, ok, ok, ok, ok, ok, "", "", true, ok), null) }

// How many steps a panel resolves in: a row each, or one for a panel with only its note
val ProbePanel.steps get() = maxOf(1, lines.size)

// A panel with its name and verdict on the face, a cursor in the verdict's place until it has answered
@Composable
fun CheckPanel(label: String, verdict: Verdict?, content: @Composable ColumnScope.() -> Unit) = Panel({
    Text(label, LcdType.labelStrong, Modifier.weight(1f))
    if (verdict != null) Text(verdict.word, LcdType.data) else Cursor()
}) { Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), content = content) }

// A row: a cursor until it answers, then its value or its tag, with what it costs and needs under it
@Composable
fun CheckLine(line: ProbeLine, answered: Boolean) {
    Kv(line.label, fact = true) {
        when {
            !answered -> Cursor()
            line.tag != null -> Tag(line.tag)
            else -> Text(line.value.orEmpty(), LcdType.data)
        }
    }
    if (answered && line.why != null) CheckWhy(line.why, line.option)
}

@Composable
fun CheckWhy(why: String?, option: String?) = Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(Lcd.lines)) {
    why?.let { Text(it, LcdType.data) }
    option?.let { Text("needs $it", LcdType.data) }
}
