package dev.continuum.fx.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EqImportTest {

    private val squigExport = """
        Preamp: -6.4 dB
        Filter 1: ON LSQ Fc 105 Hz Gain 5.5 dB Q 0.70
        Filter 2: ON PK Fc 2500 Hz Gain -3.1 dB Q 1.41
        Filter 3: ON HSQ Fc 10000 Hz Gain -2.0 dB Q 0.70
        Filter 4: OFF PK Fc 50 Hz Gain 1.0 dB Q 1.0
        Filter 5: ON PK Fc 1000 Hz Gain 3 dB BW Oct 1.0
    """.trimIndent()

    @Test
    fun parsesSquigLinkExport() {
        val result = EqImport.parse(squigExport, "Blessing 3.txt")
        val profile = result.profile
        assertNotNull(profile)
        profile!!
        assertEquals("Blessing 3", profile.name)
        assertEquals(-6.4, profile.preampDb, 1e-9)
        assertEquals(5, profile.bands.size)
        assertEquals(FilterType.LOW_SHELF, profile.bands[0].type)
        assertEquals(105.0, profile.bands[0].frequency, 1e-9)
        assertEquals(5.5, profile.bands[0].gainDb, 1e-9)
        assertEquals(0.70, profile.bands[0].q, 1e-9)
        assertEquals(FilterType.HIGH_SHELF, profile.bands[2].type)
        assertFalse(profile.bands[3].enabled)
        assertEquals(1.4142, profile.bands[4].q, 1e-3)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun parsesAutoEqStyleWithCommentsAndCommas() {
        val text = """
            # AutoEQ ParametricEQ
            Preamp: -7,2 dB
            Filter 1: ON LSC Fc 105 Hz Gain 6,8 dB Q 0,70
            Filter 2: ON PK Fc 1.2 kHz Gain -2 dB Q 2
            Filter 3: ON XX Fc 300 Hz Gain 1 dB Q 1
        """.trimIndent()
        val result = EqImport.parse(text)
        val profile = result.profile!!
        assertEquals(-7.2, profile.preampDb, 1e-9)
        assertEquals(2, profile.bands.size)
        assertEquals(1200.0, profile.bands[1].frequency, 1e-9)
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun rejectsGraphicEq() {
        val result = EqImport.parse("GraphicEQ: 20 -6.1; 21 -6.0; 22 -5.9")
        assertNull(result.profile)
        assertTrue(result.error!!.contains("graphic", ignoreCase = true))
    }

    @Test
    fun exportRoundTrips() {
        val original = EqImport.parse(squigExport).profile!!
        val again = EqImport.parse(EqImport.export(original)).profile!!
        assertEquals(original.bands.size, again.bands.size)
        assertEquals(original.preampDb, again.preampDb, 0.05)
        original.bands.zip(again.bands).forEach { (a, b) ->
            assertEquals(a.type, b.type)
            assertEquals(a.frequency, b.frequency, 0.6)
            assertEquals(a.gainDb, b.gainDb, 0.05)
            assertEquals(a.q, b.q, 0.006)
            assertEquals(a.enabled, b.enabled)
        }
    }
}
