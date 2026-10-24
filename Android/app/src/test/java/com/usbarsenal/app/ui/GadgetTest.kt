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

    // The bottom bar's few words for a command, never read from a path
    @Test
    fun saysWhatItDoes() {
        assertEquals("starting HID", says(listOf("hid", "start", "--interval", "1")))
        assertEquals("starting storage", says(listOf("storage", "start", "--lun", "0", "--mode", "rw", "/sdcard/backstop")))
        assertEquals("stopping tether", says(listOf("tether", "stop")))
        assertEquals("stopping everything", says(listOf("stop")))
        assertEquals("ejecting a drive", says(listOf("storage", "eject", "--lun", "1")))
        assertEquals("switching identity", says(listOf("identity", "set", "/sdcard/USB-Arsenal/Profiles/a.conf")))
        assertEquals("back to built-in identity", says(listOf("identity", "unset")))
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

    // Tether's setup is the setup's keys and back, and a setup the backend would refuse cannot start
    @Test
    fun tetherSetup() {
        val plan = TetherPlan(function = "rndis", address = "10.42.0.1/24", dns = "9.9.9.9", upstream = " wlan0", net = true)
        assertEquals(plan.copy(upstream = "wlan0"), tetherPlanOf(plan.keys().toMap()))
        val gateway = TetherPlan(container = "OpenWrt", iface = "eth1")
        assertEquals(gateway, tetherPlanOf(gateway.keys().toMap()))
        assertEquals(TetherPlan(), tetherPlanOf(emptyMap()))
        assertEquals("", TetherPlan().keys().toMap()["tether_gateway_iface"])
        assertEquals(false, TetherPlan(address = "10.42.0.1/33").ready(null))
        assertEquals(false, TetherPlan(dns = "1.1.1.1; reboot").ready(null))
        assertEquals(true, TetherPlan(dns = "1.1.1.1, 8.8.8.8").ready(null))
        // --no-net sends no address, so a half-typed one left behind cannot hold the start
        assertEquals(true, TetherPlan(net = false, address = "10.42.0.").ready(null))
        // No NAT in the kernel: only a gateway or --no-net can start
        assertEquals(false, TetherPlan().ready(caps().copy(nat = Probe.MISSING)))
        assertEquals(true, TetherPlan(net = false).ready(caps().copy(nat = Probe.MISSING)))
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
    fun picksAreTheSetupsLuns() {
        val setup = mapOf("storage_lun1" to "rw:/sdcard/USB-Arsenal/pen drive.img", "storage_lun0" to "auto:/sdcard/USB-Arsenal/a.iso", "storage_lun2" to "", "udc" to "")
        val picks = picksOf(setup)
        assertEquals(listOf(Pick(0, "auto", "/sdcard/USB-Arsenal/a.iso"), Pick(1, "rw", "/sdcard/USB-Arsenal/pen drive.img")), picks)
        assertEquals("rw:/sdcard/USB-Arsenal/pen drive.img", picks[1].spec)
        // A path with a colon keeps it: the mode ends at the first one
        assertEquals(Pick(0, "dvd", "/a:b/c.iso"), picksOf(mapOf("storage_lun0" to "dvd:/a:b/c.iso")).single())
    }
}
