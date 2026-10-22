package com.usbarsenal.app.ui

import com.usbarsenal.app.backend.BUILT_IN
import com.usbarsenal.app.backend.Profile
import com.usbarsenal.app.backend.parseProfiles
import com.usbarsenal.app.backend.withKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Profiles read as config.c reads them, write only their five keys, and a pick waits only while bound
class IdentityTest {
    @Test
    fun parse() {
        val lines = listOf(
            "== /storage/emulated/0/USB-Arsenal/Profiles/pixel 8.conf",
            "product=Pixel 8",
            "manufacturer=Google",
            "id_vendor=0x18D1",
            "id_product=0x4ee7",
            "max_power=500                    # mA, 0-500",
            "serial=PX8-0001",
            "== /storage/emulated/0/USB-Arsenal/Profiles/bare.conf",
        )
        val (bare, pixel) = parseProfiles(lines)
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
        assertEquals("Built-in", profileName(mapOf("profile" to "built-in defaults")))
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
