package com.sonicareset.app.nfc

/**
 * Philips Sonicare brush-head NFC password derivation.
 *
 * The NTAG213 inside a Sonicare brush head protects its pages with a 4-byte
 * password. That password is not secret-random: it is a deterministic function
 * of the tag UID and the MFG batch code printed on the head.
 *
 * Algorithm reverse-engineered by @ATC1441 and published as a gist:
 *   https://gist.github.com/atc1441/41af75048e4c22af1f5f0d4c1d94bb56
 *
 * Independent re-implementations that agree with it (checked against the
 * published test vectors, see [selfTest]):
 *   - nicjes/SonicareGenerator  (JavaScript)
 *   - filipsworks/SoniKraker    (Python)
 *   - flohzirkus666/sonicare-rs (Rust)
 *   - Vilda007/sonicare         (Flipper Zero, C)
 *
 * Steps:
 *  1. crc = CRC16-CCITT (poly 0x1021, MSB-first, no reflection) of the UID,
 *     initialised with the magic value 0x49A3.
 *  2. crc |= CRC16(crc, MFG_BYTES) << 16   -- MFG CRC seeded with the UID CRC.
 *  3. Byte-rotate the 32-bit value: swap the two 16-bit halves.
 *  4. The 4 bytes, sent big-endian, are the password used in PWD_AUTH (0x1B).
 *
 * Nothing here touches a tag: it is a pure function of two strings the user
 * supplies (or that the read-only scanner already captured).
 *
 * SAFETY: NTAG213 heads in the field use CFG1=0x43, which permanently disables
 * writing after 3 wrong PWD_AUTH attempts. Do not retry on auth failure.
 */
object SonicarePassword {

    /** CRC16 initial value the Sonicare derivation uses as a seed. */
    private const val CRC_SEED = 0x49A3

    /** CRC16-CCITT polynomial. */
    private const val CRC_POLY = 0x1021

    /** Length of the 7-byte NTAG UID the password is derived from. */
    const val UID_LENGTH = 7

    /** Length of the MFG string, including the space: "221214 12K". */
    const val MFG_LENGTH = 10

    /**
     * Page holding the MFG code on a Sonicare head: the 10 ASCII bytes live at
     * page 0x21 offset 2 .. page 0x23 offset 3 (a 2-byte prefix precedes them).
     */
    const val MFG_PAGE = 0x21
    const val MFG_OFFSET_IN_PAGE = 2

    /** Page holding the used-time counter; bytes 0..1 are the usage counter. */
    const val USAGE_PAGE = 0x24

    /** Result of a derivation, with the checks that produced it. */
    data class Derived(
        /** 4 password bytes in wire (big-endian) order. */
        val password: ByteArray,
        /** The password as 8 uppercase hex digits, e.g. "61F0A50F". */
        val hex: String,
        /** The 7-byte UID actually used. */
        val uid: ByteArray,
        /** The 10-byte MFG string actually used. */
        val mfg: String,
        /** The intermediate 32-bit value before the byte rotation. */
        val crc: Int,
    ) {
        // data class with a ByteArray needs structural equals/hashCode by hand.
        override fun equals(other: Any?): Boolean = when {
            this === other -> true
            other !is Derived -> false
            else -> password.contentEquals(other.password) &&
                uid.contentEquals(other.uid) &&
                hex == other.hex && mfg == other.mfg && crc == other.crc
        }

        override fun hashCode(): Int {
            var h = password.contentHashCode()
            h = 31 * h + uid.contentHashCode()
            h = 31 * h + hex.hashCode()
            h = 31 * h + mfg.hashCode()
            h = 31 * h + crc
            return h
        }
    }

    sealed interface Outcome {
        data class Ok(val derived: Derived) : Outcome
        data class Invalid(val message: String) : Outcome
    }

    // ---------------------------------------------------------------- entry

    /**
     * Derive the password from a UID string and an MFG string.
     *
     * [uidHex] accepts "04:EC:FC:A2:94:10:90", "04ECFCA2941090" or the 4-byte
     * UID tail "A2:94:10:90" (the last 4 UID bytes are what NTAG needs to be
     * prefixed with 0x88 0x04 for the full 7-byte form). See [normaliseUid].
     *
     * [mfg] accepts "221214 12K" or "22121412K"; the separator is always
     * re-inserted because it is part of the hashed bytes.
     */
    fun derive(uidHex: String, mfg: String): Outcome {
        val uidBytes = normaliseUid(uidHex)
            ?: return Outcome.Invalid(
                "UID non valido: servono 7 byte esadecimali (es. 04:EC:FC:A2:94:10:90)."
            )
        val mfgBytes = normaliseMfg(mfg)
            ?: return Outcome.Invalid(
                "MFG non valido: servono 6 cifre + 2 cifre + 1 lettera (es. 221214 12K)."
            )

        val crc = rawPassword(uidBytes, mfgBytes)
        val password = ByteArray(4) { i ->
            ((crc ushr (8 * (3 - i))) and 0xFF).toByte()   // big-endian wire order
        }
        return Outcome.Ok(
            Derived(
                password = password,
                hex = password.toHex(),
                uid = uidBytes,
                mfg = String(mfgBytes, Charsets.US_ASCII),
                crc = crc,
            )
        )
    }

    /**
     * Derive straight from a read-only page dump: the UID comes from the tag
     * and the MFG is read out of page [MFG_PAGE] (offset [MFG_OFFSET_IN_PAGE]).
     */
    fun deriveFromDump(uidHex: String, pages: List<PageDump>): Outcome {
        val mfg = mfgFromPages(pages)
            ?: return Outcome.Invalid(
                "Il dump non contiene il codice MFG (pagina 0x21, offset 2). " +
                    "Rileggi la testina: page 0x21-0x23 devono essere leggibili."
            )
        return derive(uidHex, mfg)
    }

    /** Extract the 10-character MFG string from a page dump, or null if missing. */
    fun mfgFromPages(pages: List<PageDump>): String? {
        val byIndex = pages.associateBy { it.index }
        val out = StringBuilder(MFG_LENGTH)
        for (i in 0 until MFG_LENGTH) {
            val abs = MFG_OFFSET_IN_PAGE + i            // absolute byte offset from page 0
            val pageIndex = MFG_PAGE + abs / 4
            val offsetInPage = abs % 4
            val page = byIndex[pageIndex] ?: return null
            val h = page.hex
            if (h.length != 8) return null
            val b = h.substring(offsetInPage * 2, offsetInPage * 2 + 2).toIntOrNull(16) ?: return null
            out.append(b.toChar())
        }
        return out.toString()
    }

    /** The full PWD_AUTH command body for a derived password: 0x1B + 4 bytes. */
    fun authCommand(derived: Derived): ByteArray = byteArrayOf(0x1B) + derived.password

    // ------------------------------------------------------------- internals

    /**
     * 32-bit Sonicare value: UID CRC seeded with 0x49A3, MFG CRC folded into the
     * high half, then the two halves swapped. Int == uint32 bit pattern.
     */
    private fun rawPassword(uid: ByteArray, mfg: ByteArray): Int {
        var crc = crc16(CRC_SEED, uid, 0, uid.size)
        crc = crc or (crc16(crc, mfg, 0, mfg.size) shl 16)
        return ((crc ushr 8) and 0x00FF00FF) or ((crc shl 8) and -0x00FF0100)
    }

    /**
     * CRC16-CCITT, MSB-first, polynomial 0x1021, no input/output reflection,
     * no final XOR — exactly the loop from the ATC1441 gist:
     *
     *     crc ^= *buffer++ << 8;
     *     if (crc & 0x8000) crc = (crc << 1) ^ 0x1021; else crc <<= 1;   // x8
     */
    internal fun crc16(seed: Int, data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = seed and 0xFFFF
        for (i in from until to) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor CRC_POLY else crc shl 1
            }
            crc = crc and 0xFFFF
        }
        return crc
    }

    /**
     * Parse a UID: 7 bytes (full NTAG UID), or 4 bytes (NTAG "UID" as returned
     * by some readers, which is the UID tail). A 4-byte UID is expanded to the
     * 7-byte form 0x88 0x04 BCC0 + tail, matching what NTAG213 reports.
     */
    fun normaliseUid(uidHex: String): ByteArray? {
        val clean = uidHex.filterNot { it == ':' || it == ' ' || it == '-' || it == '.' }
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        if (clean.any { Character.digit(it, 16) < 0 }) return null
        val raw = ByteArray(clean.length / 2) { i ->
            ((Character.digit(clean[i * 2], 16) shl 4) or
                Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
        return when (raw.size) {
            UID_LENGTH -> raw
            4 -> {
                val bcc0 = (0x88.toByte().toInt() xor 0x04 xor (raw[0].toInt() and 0xFF)).toByte()
                byteArrayOf(0x88.toByte(), 0x04, bcc0) + raw
            }
            else -> null
        }
    }

    /**
     * Accept "221214 12K" or "22121412K" and return the 10 ASCII bytes with the
     * space always inserted at index 6 (the space is hashed, so it matters).
     */
    fun normaliseMfg(mfg: String): ByteArray? {
        val s = mfg.trim().uppercase()
        val compact = if (s.length == MFG_LENGTH && s[6] == ' ') {
            s
        } else {
            s.filterNot { it == ' ' || it == '-' || it == '_' }
                .let { if (it.length == MFG_LENGTH - 1) it.substring(0, 6) + " " + it.substring(6) else it }
        }
        if (compact.length != MFG_LENGTH) return null
        if (compact[6] != ' ') return null
        if (!compact.substring(0, 6).all { it in '0'..'9' }) return null
        if (!compact.substring(7, 9).all { it in '0'..'9' }) return null
        if (!(compact[9] in 'A'..'Z')) return null
        return compact.toByteArray(Charsets.US_ASCII)
    }

    private fun ByteArray.toHex(): String {
        val hex = "0123456789ABCDEF"
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append(hex[v ushr 4]).append(hex[v and 0x0F])
        }
        return out.toString()
    }

    // ------------------------------------------------------------- self test

    /** Published test vectors, cross-checked against every implementation above. */
    private val VECTORS = listOf(
        // ATC1441 gist: expects 0x61F0A50F
        Triple("04:EC:FC:A2:94:10:90", "221214 12K", "61F0A50F"),
        // flohzirkus666/sonicare-rs: expects 0x1D7E4BDF
        Triple("04:D2:E9:B2:11:68:80", "210607 43M", "1D7E4BDF"),
    )

    /** Runs the known-answer vectors; used by unit tests and a debug check. */
    fun selfTest(): Boolean = VECTORS.all { (uid, mfg, expected) ->
        (derive(uid, mfg) as? Outcome.Ok)?.derived?.hex == expected
    }

    fun vectorDescription(): String = VECTORS.joinToString("\n") { (uid, mfg, expected) ->
        val got = (derive(uid, mfg) as? Outcome.Ok)?.derived?.hex ?: "ERRORE"
        "$uid / $mfg -> $got (atteso $expected)"
    }
}
