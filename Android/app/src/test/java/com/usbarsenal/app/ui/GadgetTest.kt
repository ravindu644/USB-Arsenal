package com.usbarsenal.app.ui

import com.usbarsenal.app.backend.Capabilities
import com.usbarsenal.app.backend.GadgetStatus
import com.usbarsenal.app.backend.HidNode
import com.usbarsenal.app.backend.Lun
import com.usbarsenal.app.backend.Probe
import com.usbarsenal.app.backend.Tether
import com.usbarsenal.app.backend.parseContainers
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
        // Off, a row says what the function does: its switch already says off
        assertEquals("ISOs and disk images" to "as drives for the host", rowLines(Fn.STORAGE, idle, caps(), null))
        assertEquals("this phone's internet" to "for the host, over USB", rowLines(Fn.TETHER, idle, caps(), null))
        assertEquals("a keyboard and a mouse" to "for the host", rowLines(Fn.HID, idle, caps(), null))
        assertEquals("starting" to "hid start", rowLines(Fn.HID, idle, caps(), Busy(Fn.HID, "hid start", stopping = false)))
        // A path that ends in "stop" is still a start
        assertEquals("starting", rowLines(Fn.STORAGE, idle, caps(), Busy(Fn.STORAGE, "storage start /sdcard/backstop", stopping = false)).first)
        assertEquals("no hid in this kernel" to "CONFIG_USB_CONFIGFS_F_HID", rowLines(Fn.HID, idle, caps(Probe.MISSING), null))
    }

    // The host gets the next address, or the one before at the top of the subnet; none without one
    @Test
    fun hostAddress() {
        assertEquals("192.168.42.130", hostOf("192.168.42.129/24"))
        assertEquals("192.168.42.253", hostOf("192.168.42.254/24"))
        assertEquals(null, hostOf(""))
        // As parse_address: no prefix is a /24; the network, the broadcast and leading zeros are not hosts
        assertEquals("10.42.0.2", hostOf("10.42.0.1"))
        assertEquals(null, hostOf("10.42.0.0/24"))
        assertEquals(null, hostOf("10.42.0.255/24"))
        assertEquals(null, hostOf("10.42.0.01/24"))
        assertEquals(null, hostOf("10.42.0.1/31"))
        val gateway = live.copy(tether = live.tether!!.copy(address = "", gateway = true, gatewayContainer = "OpenWrt"))
        assertEquals("ncm usb0" to "gateway OpenWrt", rowLines(Fn.TETHER, gateway, caps(), null))
    }

    // Tether's setup starts as it says, and Home's off line says the same
    @Test
    fun tetherSetup() {
        assertEquals(listOf("tether", "start", "--ncm"), tetherArgs(TetherPlan(), "ncm"))
        assertEquals(listOf("tether", "start", "--rndis"), tetherArgs(TetherPlan(function = "rndis"), "rndis"))
        assertEquals(
            listOf("tether", "start", "--ncm", "--gateway", "OpenWrt", "--gateway-iface", "usb0"),
            tetherArgs(TetherPlan(gateway = true, container = " OpenWrt ", address = "10.0.0.1/24"), "ncm"),
        )
        assertEquals(
            listOf("tether", "start", "--ncm", "--address", "10.42.0.1/24", "--dns", "9.9.9.9", "--upstream", "wlan0"),
            tetherArgs(TetherPlan(address = "10.42.0.1/24", dns = "9.9.9.9", upstream = " wlan0"), "ncm"),
        )
        assertEquals(listOf("tether", "start", "--ncm", "--no-net"), tetherArgs(TetherPlan(net = false, address = "10.42.0.1/24"), "ncm"))
        // A setup the backend would refuse cannot be started
        assertEquals(false, TetherPlan(address = "10.42.0.1/33").ready())
        assertEquals(false, TetherPlan(dns = "1.1.1.1; reboot").ready())
        assertEquals(false, TetherPlan(gateway = true).ready())
        assertEquals(true, TetherPlan(dns = "1.1.1.1, 8.8.8.8").ready())
        // --no-net sends no address, so a half-typed one left behind cannot hold the start
        assertEquals(true, TetherPlan(net = false, address = "10.42.0.").ready())
        // NCM picked on a kernel that cannot run it still starts RNDIS
        assertEquals("rndis", tetherFunction(TetherPlan(function = "ncm"), emptyMap(), caps()))
        assertEquals("ncm", tetherFunction(TetherPlan(), emptyMap(), caps().copy(ncm = Probe.OK)))
        assertEquals("rndis", tetherFunction(TetherPlan(), mapOf("tether_function" to "rndis"), caps().copy(ncm = Probe.OK)))
        // A kernel with NCM alone (no RNDIS past GKI 5.10) starts NCM whatever was picked
        assertEquals("ncm", tetherFunction(TetherPlan(function = "rndis"), emptyMap(), caps().copy(ncm = Probe.OK, rndis = Probe.MISSING)))
    }

    @Test
    fun droidspacesContainers() {
        val json = """{"total":4,"running":[{"name":"Ubuntu 24.04","pid":10203},{"name":"OpenWrt","pid":4575}]}"""
        assertEquals(listOf("Ubuntu 24.04", "OpenWrt"), parseContainers(json))
        assertEquals(emptyList<String>(), parseContainers("""{"total":2,"running":[]}"""))
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
