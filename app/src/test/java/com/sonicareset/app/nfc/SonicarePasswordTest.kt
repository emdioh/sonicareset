package com.sonicareset.app.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Known-answer tests for the Sonicare password derivation.
 *
 * The vectors come straight from the published implementations and are the
 * same ones those projects assert against:
 *
 *  - `61F0A50F` for UID 04:EC:FC:A2:94:10:90 + MFG "221214 12K"
 *    (ATC1441 gist, nicjes/SonicareGenerator, filipsworks/SoniKraker)
 *  - `1D7E4BDF` for UID 04:D2:E9:B2:11:68:80 + MFG "210607 43M"
 *    (flohzirkus666/sonicare-rs)
 */
class SonicarePasswordTest {

    @Test
    fun `matches the ATC1441 gist vector`() {
        val out = SonicarePassword.derive("04:EC:FC:A2:94:10:90", "221214 12K")
        assertTrue(out is SonicarePassword.Outcome.Ok)
        assertEquals("61F0A50F", (out as SonicarePassword.Outcome.Ok).derived.hex)
    }

    @Test
    fun `matches the sonicare-rs vector`() {
        val out = SonicarePassword.derive("04:D2:E9:B2:11:68:80", "210607 43M")
        assertEquals(
            "1D7E4BDF",
            (out as SonicarePassword.Outcome.Ok).derived.hex,
        )
    }

    @Test
    fun `self test vectors pass`() {
        assertTrue(SonicarePassword.selfTest())
    }

    @Test
    fun `uid without separators is accepted`() {
        val out = SonicarePassword.derive("04ECFCA2941090", "221214 12K")
        assertEquals("61F0A50F", (out as SonicarePassword.Outcome.Ok).derived.hex)
    }

    @Test
    fun `mfg without the space is normalised`() {
        val out = SonicarePassword.derive("04:EC:FC:A2:94:10:90", "22121412K")
        assertEquals("61F0A50F", (out as SonicarePassword.Outcome.Ok).derived.hex)
        assertEquals("221214 12K", (out as SonicarePassword.Outcome.Ok).derived.mfg)
    }

    @Test
    fun `mfg is case insensitive but hashed as upper case`() {
        val lower = SonicarePassword.derive("04:EC:FC:A2:94:10:90", "221214 12k")
        val upper = SonicarePassword.derive("04:EC:FC:A2:94:10:90", "221214 12K")
        assertEquals(
            (upper as SonicarePassword.Outcome.Ok).derived.hex,
            (lower as SonicarePassword.Outcome.Ok).derived.hex,
        )
    }

    @Test
    fun `password is the 32-bit value big-endian`() {
        val out = SonicarePassword.derive("04:EC:FC:A2:94:10:90", "221214 12K")
        val d = (out as SonicarePassword.Outcome.Ok).derived
        // 0x61F0A50F -> 61 F0 A5 0F on the wire
        assertEquals(listOf(0x61, 0xF0, 0xA5, 0x0F), d.password.map { it.toInt() and 0xFF })
        assertEquals(byteArrayOf(0x1B, 0x61, 0xF0.toByte(), 0xA5.toByte(), 0x0F).toList(),
            SonicarePassword.authCommand(d).toList())
    }

    @Test
    fun `four byte uid tail expands to the 88 04 form`() {
        val seven = SonicarePassword.normaliseUid("04:EC:FC:A2:94:10:90")
        assertNotNull(seven)
        // Reverse-engineered heads expose the 4-byte tail A2:94:10:90.
        val four = SonicarePassword.normaliseUid("A2:94:10:90")
        assertNotNull(four)
        assertEquals(7, four!!.size)
        assertEquals(0x88.toByte(), four[0])
        assertEquals(0x04.toByte(), four[1])
        // BCC0 = 0x88 XOR 0x04 XOR first tail byte
        assertEquals((0x88 xor 0x04 xor 0xA2).toByte(), four[2])
    }

    @Test
    fun `invalid uid is rejected`() {
        for (bad in listOf("", "04:EC", "ZZ:EC:FC:A2:94:10:90", "04:EC:FC:A2:94:10")) {
            assertTrue("expected reject for '$bad'", SonicarePassword.derive(bad, "221214 12K")
                is SonicarePassword.Outcome.Invalid)
        }
    }

    @Test
    fun `invalid mfg is rejected`() {
        for (bad in listOf("", "221214", "22121412", "221214 1K", "221214 129", "ABCDEF 12K")) {
            assertTrue("expected reject for '$bad'", SonicarePassword.derive("04:EC:FC:A2:94:10:90", bad)
                is SonicarePassword.Outcome.Invalid)
        }
    }

    @Test
    fun `mfg is read from pages 0x21 to 0x23`() {
        // Bytes for "221214 12K" starting at page 0x21 offset 2.
        val mfg = "221214 12K".toByteArray(Charsets.US_ASCII)
        val pages = mutableListOf<PageDump>()
        pages += PageDump(0x20, "00000000")
        pages += PageDump(0x21, "AA00%02X%02X".format(mfg[0].toInt(), mfg[1].toInt()))
        pages += PageDump(0x22, mfg.copyOfRange(2, 6).joinToString("") { "%02X".format(it) })
        pages += PageDump(0x23, mfg.copyOfRange(6, 10).joinToString("") { "%02X".format(it) })

        assertEquals("221214 12K", SonicarePassword.mfgFromPages(pages))

        val out = SonicarePassword.deriveFromDump("04:EC:FC:A2:94:10:90", pages)
        assertEquals("61F0A50F", (out as SonicarePassword.Outcome.Ok).derived.hex)
    }

    @Test
    fun `missing mfg pages yield null not a crash`() {
        assertNull(SonicarePassword.mfgFromPages(emptyList()))
        assertNull(SonicarePassword.mfgFromPages(listOf(PageDump(0x21, "AA00" + "3232"))))
    }

    @Test
    fun `difference in uid changes the password`() {
        val a = SonicarePassword.derive("04:EC:FC:A2:94:10:90", "221214 12K")
        val b = SonicarePassword.derive("04:EC:FC:A2:94:10:91", "221214 12K")
        assertTrue(
            (a as SonicarePassword.Outcome.Ok).derived.hex !=
                (b as SonicarePassword.Outcome.Ok).derived.hex,
        )
    }
}
