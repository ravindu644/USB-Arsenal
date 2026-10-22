package com.usbarsenal.app.ui

import com.usbarsenal.app.backend.Capabilities
import com.usbarsenal.app.backend.GadgetStatus
import com.usbarsenal.app.backend.HidNode
import com.usbarsenal.app.backend.Lun
import com.usbarsenal.app.backend.Probe
import com.usbarsenal.app.backend.Tether
import org.junit.Assert.assertEquals
import org.junit.Test

// Home's rows say what status --json says, and Storage's switch starts every pick in one command
class GadgetTest {
    private val live = GadgetStatus(
        version = "1.2.0", udc = "11201000.usb0", udcState = "configured", bound = true,
        storage = listOf(Lun(0, "/sdcard/USB-Arsenal/Win11.iso", "dvd", "ro"), Lun(1, "", "", "")),
        hid = listOf(HidNode("keyboard", "/dev/hidg1"), HidNode("mouse", "/dev/hidg2")),
        tether = Tether("ncm", "usb0", true, "192.168.42.129/24", "fd75:7361::/64", "rmnet_data0", false, "", "", false),
    )
    private fun caps(hid: Probe = Probe.OK) = Capabilities(
        Probe.OK, Probe.OK, Probe.OK, Probe.OK, Probe.OK, Probe.OK, hid, Probe.CAVEAT, Probe.OK, Probe.OK, Probe.OK,
        "1.2.0", "11201000.usb0", true,
    )

    @Test
    fun liveLines() {
        assertEquals("LUN0 Win11.iso · dvd" to "", rowLines(Fn.STORAGE, live, caps(), null))
        assertEquals("ncm 192.168.42.129 → .130" to "via rmnet_data0", rowLines(Fn.TETHER, live, caps(), null))
        assertEquals("keyboard /dev/hidg1" to "mouse /dev/hidg2", rowLines(Fn.HID, live, caps(), null))
    }

    @Test
    fun offBusyAndMissing() {
        val idle = live.copy(bound = false, storage = emptyList(), hid = emptyList(), tether = null)
        assertEquals("off" to "pick an image to start", rowLines(Fn.STORAGE, idle, caps(), null))
        assertEquals("off" to "Win11.iso", rowLines(Fn.STORAGE, idle, caps(), null, "/sdcard/USB-Arsenal/Win11.iso"))
        // NCM is a caveat on this kernel, so a start would bring up RNDIS
        assertEquals("off" to "rndis · 192.168.42.129/24", rowLines(Fn.TETHER, idle, caps(), null))
        assertEquals("starting" to "hid start", rowLines(Fn.HID, idle, caps(), Busy(Fn.HID, "hid start", stopping = false)))
        // A path that ends in "stop" is still a start
        assertEquals("starting", rowLines(Fn.STORAGE, idle, caps(), Busy(Fn.STORAGE, "storage start /sdcard/backstop", stopping = false)).first)
        // The profile's function and address, where it sets them
        assertEquals("off" to "rndis · 10.0.0.1/24", rowLines(Fn.TETHER, idle, caps(), null, profile = mapOf("tether_function" to "rndis", "tether_address" to "10.0.0.1/24")))
        assertEquals("no hid in this kernel" to "CONFIG_USB_CONFIGFS_F_HID", rowLines(Fn.HID, idle, caps(Probe.MISSING), null))
    }

    // The host gets the next address, or the one before at the top of the subnet; none without one
    @Test
    fun hostAddress() {
        assertEquals("192.168.42.130", hostOf("192.168.42.129/24"))
        assertEquals("192.168.42.253", hostOf("192.168.42.254/24"))
        assertEquals(null, hostOf(""))
        val gateway = live.copy(tether = live.tether!!.copy(address = "", gateway = true, gatewayContainer = "OpenWrt"))
        assertEquals("ncm usb0" to "gateway OpenWrt", rowLines(Fn.TETHER, gateway, caps(), null))
    }

    // L1 and R1 skip a ghosted option, and stop at the ends
    @Test
    fun shoulderSkipsGhosted() {
        val noNcm = { i: Int -> i != 0 }
        assertEquals(null, nextEnabled(1, -1, 2, noNcm))
        assertEquals(2, nextEnabled(0, 1, 3) { it != 1 })
        assertEquals(null, nextEnabled(2, 1, 3) { true })
    }

    @Test
    fun picksStartInOneCommand() {
        val picks = listOf(Pick(1, "rw", "/sdcard/USB-Arsenal/pen drive.img"), Pick(0, "auto", "/sdcard/USB-Arsenal/a.iso"))
        assertEquals(
            listOf("storage", "start", "--lun", "0", "--mode", "auto", "/sdcard/USB-Arsenal/a.iso", "--lun", "1", "--mode", "rw", "/sdcard/USB-Arsenal/pen drive.img"),
            startArgs(picks),
        )
        assertEquals(picks, decodePicks(encodePicks(picks)))
    }
}
