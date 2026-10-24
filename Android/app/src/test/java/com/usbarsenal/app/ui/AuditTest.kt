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
    // A generated profile leaves `serial=` empty with a comment after it; the app must read it
    // as empty (the phone's own serial), as config.c does, not as the comment's text
    @Test
    fun generatedProfileSerialIsEmpty() {
        val lines = listOf("== /p/x.conf", "serial=                          # empty: ro.serialno, or /etc/machine-id", "product=AB#12 # not a comment")
        val p = parseProfiles(lines).single()
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
}
