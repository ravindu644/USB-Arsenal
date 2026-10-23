package com.usbarsenal.app.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The JSON below is what the a166p printed with HID, an RW image and an NCM tether live, then idle
class ParsersTest {
    @Test
    fun liveStatus() {
        val s = parseStatus(
            """{"version":"1.2.0","udc":"11201000.usb0","udc_state":"not attached","bound":1,""" +
                """"storage":[{"lun":0,"file":"/data/local/tmp/ua-b2.img","mode":"rw","access":"rw"}],""" +
                """"hid":[{"function":"keyboard","node":"/dev/hidg1"},{"function":"mouse","node":"/dev/hidg2"}],""" +
                """"tether":{"function":"ncm","iface":"usb0","running":1,"address":"192.168.42.129/24",""" +
                """"ipv6":"fd75:7361::/64","upstream":"wlan0","gateway":0,"gateway_container":"",""" +
                """"gateway_iface":"","gateway_waiting":0}}""",
        )
        assertEquals(true, s.bound)
        assertEquals(listOf(Lun(0, "/data/local/tmp/ua-b2.img", "rw", "rw")), s.storage)
        assertEquals(listOf(HidNode("keyboard", "/dev/hidg1"), HidNode("mouse", "/dev/hidg2")), s.hid)
        assertEquals(
            Tether("ncm", "usb0", true, "192.168.42.129/24", "fd75:7361::/64", "wlan0", false, "", "", false),
            s.tether,
        )
    }

    @Test
    fun idleStatus() {
        val s = parseStatus(
            """{"version":"1.2.0","udc":"11201000.usb0","udc_state":"not attached","bound":0,"storage":[],"hid":[],"tether":null}""",
        )
        assertEquals(false, s.bound)
        assertEquals(emptyList<Lun>(), s.storage)
        assertNull(s.tether)
    }

    @Test
    fun check() {
        val c = parseCheck(
            """{"root":1,"configfs":1,"udc":1,"gadget":1,"mass_storage":1,"cdrom":1,"hid":1,"ncm":-1,""" +
                """"rndis":1,"nat":1,"nat6":0,"version":"1.2.0","udc_name":"11201000.usb0","requirements_met":1}""",
        )
        assertEquals(Probe.CAVEAT, c.ncm)
        assertEquals(Probe.MISSING, c.nat6)
        assertEquals(Probe.OK, c.massStorage)
        assertEquals("11201000.usb0", c.udcName)
        assertEquals(true, c.requirementsMet)
    }

    // /sys/class/udc, a controller a line with its state, which can be several words
    @Test
    fun controllers() {
        assertEquals(
            listOf(Udc("a600000.dwc3", "configured"), Udc("dummy_udc.0", "not attached"), Udc("bare", "")),
            parseUdcs(listOf("a600000.dwc3 configured", "dummy_udc.0 not attached", "", "  bare  ")),
        )
    }

    // The flag goes right after the binary, quoted, and is nothing without a pick
    @Test
    fun udcFlagOnlyWithAPick() {
        assertEquals("", udcFlag(null))
        assertEquals(" --udc 'a600000.dwc3'", udcFlag("a600000.dwc3"))
        assertEquals(" --udc 'it'\\''s'", udcFlag("it's"))
    }

    @Test(expected = org.json.JSONException::class)
    fun aMissingKeyIsAMismatchedBackend() {
        parseStatus("""{"version":"1.1.0","udc":"x","udc_state":"configured","bound":0}""")
    }
}
