package com.usbarsenal.app.ui

import com.usbarsenal.app.backend.Capabilities
import com.usbarsenal.app.backend.Probe
import com.usbarsenal.app.backend.Probe.CAVEAT
import com.usbarsenal.app.backend.Probe.MISSING
import com.usbarsenal.app.backend.Probe.OK
import org.junit.Assert.assertEquals
import org.junit.Test

class CheckTest {
    private fun caps(
        udc: Probe = OK, mass: Probe = OK, cdrom: Probe = OK, hid: Probe = OK, ncm: Probe = OK,
        rndis: Probe = OK, nat6: Probe = OK, interval: Probe? = OK,
    ) = Capabilities(OK, OK, udc, udc, mass, cdrom, hid, ncm, rndis, OK, nat6, "1.2.0", "a600000.dwc3", udc == OK, interval)

    private fun panel(c: Capabilities, label: String) = checkPanels(c, null).first { it.label == label }

    @Test
    fun allOk() = assertEquals(List(4) { Verdict.OK }, checkPanels(caps(), null).map { it.verdict })

    // CD-ROM is part of storage: without mass storage there is no row for it, only what it costs
    @Test
    fun storage() {
        assertEquals(Verdict.PARTIAL, panel(caps(cdrom = MISSING), "Storage").verdict)
        val none = panel(caps(mass = MISSING, cdrom = MISSING), "Storage")
        assertEquals(Verdict.MISSING, none.verdict)
        assertEquals(0, none.lines.size)
        assertEquals("CONFIG_USB_CONFIGFS_MASS_STORAGE", none.option)
    }

    @Test
    fun tether() {
        assertEquals(Verdict.PARTIAL, panel(caps(ncm = MISSING), "Tether").verdict)
        assertEquals(Verdict.PARTIAL, panel(caps(ncm = CAVEAT), "Tether").verdict)
        assertEquals(Verdict.PARTIAL, panel(caps(nat6 = MISSING), "Tether").verdict)
        assertEquals(Verdict.MISSING, panel(caps(ncm = MISSING, rndis = MISSING), "Tether").verdict)
    }

    // A fixed interval is the stock kernel, not a fault
    @Test
    fun hid() {
        assertEquals(Verdict.OK, panel(caps(interval = MISSING), "HID").verdict)
        assertEquals("1 ms, fixed", panel(caps(interval = MISSING), "HID").lines.last().value)
        assertEquals(Verdict.MISSING, panel(caps(hid = MISSING), "HID").verdict)
    }

    // A must have line missing: only that panel, the functions were never probed
    @Test
    fun blocked() {
        val panels = checkPanels(caps(udc = MISSING), "a600000.dwc3")
        assertEquals(listOf("Must have"), panels.map { it.label })
        assertEquals(Verdict.MISSING, panels[0].verdict)
        assertEquals(
            "a600000.dwc3 is not listed now; the port may be in host mode (otg)",
            panels[0].lines.first { it.label == "Controller" }.why,
        )
    }
}
