package com.usbarsenal.app.ui

import com.usbarsenal.app.backend.BUILT_IN
import com.usbarsenal.app.backend.Profile
import com.usbarsenal.app.backend.parseProfile
import com.usbarsenal.app.backend.parseProfiles
import com.usbarsenal.app.backend.withKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Profiles read as config.c reads them, write only their five keys, and a pick waits only while bound
class IdentityTest {
    // As `identity show --json FILE...` prints them: config.c read each file, so a key the file
    // lacks comes back at its default and ids are 0x%04x
    @Test
    fun parse() {
        val json = """[{"path":"/storage/emulated/0/USB-Arsenal/Profiles/pixel 8.conf","errors":0,"keys":{"product":"Pixel 8",
            "manufacturer":"Google","id_vendor":"0x18d1","id_product":"0x4ee7","max_power":"500","serial":"PX8-0001"}},
            {"path":"/storage/emulated/0/USB-Arsenal/Profiles/bare.conf","errors":0,"keys":{"product":"USB Arsenal","manufacturer":"USB Arsenal",
            "id_vendor":"0x1d6b","id_product":"0x0104","max_power":"250","serial":""}}]"""
        val (bare, pixel) = parseProfiles(json)
        assertEquals(Profile("/storage/emulated/0/USB-Arsenal/Profiles/pixel 8.conf", "Pixel 8", "Google", "18d1", "4ee7", 500, "PX8-0001"), pixel)
        assertEquals("pixel 8", pixel.name)
        // A profile missing a key reads as the built-in value for it
        assertEquals(BUILT_IN.product, bare.product)
        assertEquals(BUILT_IN.power, bare.power)
        // An empty serial is the phone's own
        assertEquals("", bare.serial)
    }

    @Test
    fun rewrite() {
        val template = listOf(
            "# What the host sees",
            "id_vendor=0x1d6b",
            "id_product=0x0104",
            "manufacturer=USB Arsenal",
            "product=USB Arsenal",
            "serial=                          # empty: ro.serialno",
            "max_power=250                    # mA, 0-500",
            "storage_mode=auto",
        )
        val out = withKeys(template, Profile("/p.conf", "Pixel 8", "Google", "18d1", "4ee7", 500)).lines()
        assertEquals("id_vendor=0x18d1", out[1])
        assertEquals("product=Pixel 8", out[4])
        assertEquals("max_power=500", out[6])
        // Everything else stays as generate wrote it
        assertEquals(template[0], out[0])
        // An empty serial is written empty, which config.c reads as the phone's own
        assertEquals("serial=", out[5])
        assertEquals(template[7], out[7])
        // A hand-made file without a key gets it added, so none of the six is lost
        val added = withKeys(listOf("product=Old"), Profile("/p.conf", "Pixel 8", "Google", "18d1", "4ee7", 500)).lines()
        assertTrue("id_vendor=0x18d1" in added && "max_power=500" in added && "serial=" in added)
    }

    @Test
    fun inForce() {
        val p = parseProfile("""{"profile":"","copy":false,"missing":"/p/gone.conf","keys":{"product":"USB Arsenal","serial":""}}""")
        assertEquals("Built-in", profileName(p))
        assertEquals("/p/gone.conf", p["missing"])
        // A derived value is empty in the JSON and left out
        assertTrue("serial" !in p)
    }

    @Test
    fun checks() {
        assertTrue(profileProblems("pixel-8", "Pixel 8", "Google", "18d1", "4ee7", taken = false).isEmpty())
        assertEquals(setOf("name"), profileProblems("pixel-8", "Pixel 8", "Google", "18d1", "4ee7", taken = true).keys)
        assertEquals(setOf("name"), profileProblems("../x", "Pixel 8", "Google", "18d1", "4ee7", taken = false).keys)
        assertEquals(setOf("product"), profileProblems("p", "Pixel #8", "Google", "18d1", "4ee7", taken = false).keys)
        assertEquals(setOf("ids"), profileProblems("p", "Pixel 8", "Google", "18d", "zz00", taken = false).keys)
        assertTrue(profileProblems("p", "Pixel 8", "Google", "18d1", "4ee7", taken = false, serial = "").isEmpty())
        assertEquals(setOf("serial"), profileProblems("p", "Pixel 8", "Google", "18d1", "4ee7", taken = false, serial = "AB #12").keys)
    }

    @Test
    fun names() {
        assertEquals("Built-in", profileName(mapOf("profile" to "")))
        assertEquals("pixel 8", profileName(mapOf("profile" to "/storage/emulated/0/USB-Arsenal/Profiles/pixel 8.conf")))
    }

    @Test
    fun pending() {
        val p = "/p.conf"
        // Off the bus a pick is set at once, so nothing waits
        assertEquals(false, identityPending(bound = false, chosen = p, active = null, edited = false))
        assertEquals(true, identityPending(bound = true, chosen = p, active = null, edited = false))
        assertEquals(false, identityPending(bound = true, chosen = p, active = p, edited = false))
        assertEquals(true, identityPending(bound = true, chosen = p, active = p, edited = true))
    }
}
