package com.usbarsenal.app.ui

import com.usbarsenal.app.backend.GadgetStatus
import com.usbarsenal.app.backend.Lun
import com.usbarsenal.app.backend.parseProfiles
import com.usbarsenal.app.backend.parseStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The bugs docs/developer/research/06-app-state.md confirmed, as the behaviour they should have.
 * Each failed on the code as audited.
 */
class AuditTest {
    // A generated profile leaves `serial=` empty with a comment after it. The app no longer reads
    // files itself: config.c does (`identity show --json FILE`), and its empty serial stays empty
    @Test
    fun generatedProfileSerialIsEmpty() {
        val p = parseProfiles("""[{"path":"/p/x.conf","errors":0,"keys":{"serial":"","product":"AB#12"}}]""").single()
        assertEquals("", p.serial)
        assertEquals("AB#12", p.product)
    }

    // A function whose drives are all empty is still on: the kernel presents the drives and
    // `storage stop` is what turns it off
    @Test
    fun emptyDrivesAreStillOn() {
        val s = GadgetStatus("1.2.0", "udc", "configured", bound = true, storage = listOf(Lun(0, "", "", "")), hid = emptyList(), tether = null)
        assertTrue(s.isOn(Fn.STORAGE))
        assertEquals("1 empty drive" to "no medium", rowLines(Fn.STORAGE, s, null, null))
    }

    // Pending comes from the backend's two files, through status, not from a record the app kept
    @Test
    fun pendingComesFromStatus() {
        val json = """{"version":"1.3.0","udc":"u","udc_state":"configured","bound":1,"storage":[{"lun":0,"file":"/a","mode":"disk","access":"ro"}],"hid":[],"tether":null,
            "boot_id":"b","setup":{"storage_lun0":"auto:/a","hid_interval":"0"},"running":{"storage_lun0":"auto:/b","hid_interval":"0"},"pending":{"storage":1,"tether":0,"hid":0}}"""
        val s = parseStatus(json)
        assertEquals(mapOf("storage" to true, "tether" to false, "hid" to false), s.pending)
        assertEquals("b", s.bootId)
        Gadget.status = s
        assertTrue(pending(Fn.STORAGE))
        assertFalse(pending(Fn.HID))
        // An older backend prints none of these; nothing is pending and nothing is set up
        val old = parseStatus("""{"version":"1.2.0","udc":"u","udc_state":"x","bound":0,"storage":[],"hid":[],"tether":null}""")
        assertEquals(emptyMap<String, String>(), old.setup)
        assertEquals("", old.bootId)
    }

    // Interface names as the kernel's dev_valid_name takes them; an empty gateway iface is usb0
    @Test
    fun ifaceNamesAsTheKernel() {
        assertFalse(validIface("."))
        assertFalse(validIface(".."))
        assertFalse(validIface("eth0:1"))
        assertTrue(validIface("rmnet_data0"))
        assertTrue(TetherPlan(container = "OpenWrt").ready(null))
        assertEquals("", TetherPlan(container = "OpenWrt").keys().toMap()["tether_gateway_iface"])
    }

    // A DNS list longer than the backend's 127-character field is refused before it is sent
    @Test
    fun dnsFitsTheBackendsField() {
        assertFalse(validDns(List(9) { "100.100.100.100" }.joinToString(",")))
        assertTrue(validDns("1.1.1.1,8.8.8.8"))
    }

    // A field keeps only what its value can hold, typed or pasted
    @Test
    fun inputsDropWhatTheyCannotHold() {
        assertEquals("wlan0rm", ifaceInput("wlan0 ;rm"))
        assertEquals("abcdefghijklmno", ifaceInput("abcdefghijklmnop"))
        assertEquals("ab", usbText("a#b\n"))
    }

    // A drive says its kind once: rw is a disk, its tag says the access
    @Test
    fun driveKind() {
        assertEquals("cd", kindOf("cdrom"))
        assertEquals("disk", kindOf("rw"))
        assertEquals("dvd", kindOf("dvd"))
    }

    // A USB 1.1 host polls in whole 1 ms frames
    @Test
    fun fullSpeedPeriod() {
        assertEquals("3 ms", periodText(3, fullSpeed = true))
        assertEquals("500 us", periodText(3))
    }
}
