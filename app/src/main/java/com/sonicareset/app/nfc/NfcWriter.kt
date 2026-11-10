package com.sonicareset.app.nfc

import android.nfc.Tag
import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcA
import java.io.IOException

/**
 * Experimental, user-driven writer for MIFARE Ultralight / NTAG-family tags.
 *
 * Scope and limits (by design):
 *  - Writes exactly one 4-byte page per request, to a page chosen by the user.
 *  - If the target page is protected, the user may supply their OWN password,
 *    or ask the app to derive the Sonicare head password from the tag's UID and
 *    the MFG code printed on the head ([SonicarePassword]). Either way it is
 *    sent as a standard PWD_AUTH (0x1B) command.
 *  - There is NO brute force: a derivation produces exactly one candidate, and
 *    a failed PWD_AUTH is never retried automatically. NTAG213 heads ship with
 *    CFG1=0x43, meaning three wrong passwords permanently disable writing.
 *  - Pages 0..3 (UID / internal / lock bytes / OTP) are always refused, because
 *    writing them is either useless or permanently bricks the tag.
 *
 * Everything here acts only on a tag the user physically presents and only
 * when the user explicitly arms a write.
 */
object NfcWriter {

    /** Pages below this are never written (UID, internal, lock, OTP). */
    const val MIN_WRITABLE_PAGE = 4

    data class WriteRequest(
        val page: Int,
        val data: ByteArray,        // exactly 4 bytes
        val password: ByteArray?,   // exactly 4 bytes for PWD_AUTH, or null
    ) {
        // data class with a ByteArray needs structural equals/hashCode by hand.
        override fun equals(other: Any?): Boolean = when {
            this === other -> true
            other !is WriteRequest -> false
            else -> page == other.page && data.contentEquals(other.data) &&
                (password?.contentEquals(other.password) ?: (other.password == null))
        }

        override fun hashCode(): Int =
            31 * (31 * page + data.contentHashCode()) + (password?.contentHashCode() ?: 0)
    }

    sealed interface WriteResult {
        /**
         * The page was written. [pack] is the PACK returned by PWD_AUTH, if a
         * password was used.
         */
        data class Success(
            val page: Int,
            val pack: String?,
        ) : WriteResult
        /** The request was refused by the app's own guard-rails (not by the tag). */
        data class Blocked(val message: String) : WriteResult
        /** The tag or transport rejected or failed the operation. */
        data class Failure(val message: String) : WriteResult
    }

    fun write(tag: Tag, req: WriteRequest): WriteResult {
        if (req.data.size != 4) {
            return WriteResult.Failure("I dati devono essere esattamente 4 byte (8 cifre esadecimali).")
        }
        if (req.page < MIN_WRITABLE_PAGE) {
            return WriteResult.Blocked(
                "Pagina ${req.page} bloccata dall'app (UID/lock/OTP, pagine 0-3): " +
                    "scrittura rifiutata per non rendere il tag inutilizzabile."
            )
        }
        if (req.password != null && req.password.size != 4) {
            return WriteResult.Failure("La password deve essere di 4 byte (8 cifre esadecimali).")
        }

        val techs = tag.techList ?: emptyArray()
        return when {
            techs.any { it.endsWith("MifareUltralight") } -> writeViaUltralight(tag, req)
            techs.any { it.endsWith("NfcA") } -> writeViaNfcA(tag, req)
            else -> WriteResult.Failure("Tag non compatibile con la scrittura (né MifareUltralight né NfcA).")
        }
    }

    private fun writeViaUltralight(tag: Tag, req: WriteRequest): WriteResult {
        val mul = MifareUltralight.get(tag)
            ?: return WriteResult.Failure("Interfaccia MifareUltralight non disponibile su questo tag.")
        return try {
            mul.connect()
            var pack: String? = null
            if (req.password != null) {
                // PWD_AUTH: 0x1B + 4-byte password -> 2-byte PACK on success.
                val resp = mul.transceive(byteArrayOf(0x1B.toByte()) + req.password)
                pack = resp?.toHex()
            }
            mul.writePage(req.page, req.data)
            WriteResult.Success(req.page, pack)
        } catch (e: IOException) {
            WriteResult.Failure(authAwareMessage(e, req.password != null))
        } catch (e: Exception) {
            WriteResult.Failure("Errore: ${e.message ?: e.javaClass.simpleName}.")
        } finally {
            try { mul.close() } catch (_: Exception) {}
        }
    }

    private fun writeViaNfcA(tag: Tag, req: WriteRequest): WriteResult {
        val nfcA = NfcA.get(tag)
            ?: return WriteResult.Failure("Interfaccia NfcA non disponibile su questo tag.")
        return try {
            nfcA.connect()
            var pack: String? = null
            if (req.password != null) {
                val resp = nfcA.transceive(byteArrayOf(0x1B.toByte()) + req.password)
                pack = resp?.toHex()
            }
            // WRITE: 0xA2 + page + 4 data bytes.
            val cmd = byteArrayOf(0xA2.toByte(), (req.page and 0xFF).toByte()) + req.data
            nfcA.transceive(cmd)
            WriteResult.Success(req.page, pack)
        } catch (e: IOException) {
            WriteResult.Failure(authAwareMessage(e, req.password != null))
        } catch (e: Exception) {
            WriteResult.Failure("Errore: ${e.message ?: e.javaClass.simpleName}.")
        } finally {
            try { nfcA.close() } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------ derivation

    /**
     * Outcome of deriving a password.
     * [derived] is kept so the UI can show which UID/MFG produced the password.
     */
    sealed interface DerivedPassword {
        data class Ready(val derived: SonicarePassword.Derived) : DerivedPassword
        data class Err(val message: String) : DerivedPassword
    }

    /**
     * Derive the password from [uidHex] + [mfg] with the ATC1441 algorithm,
     * instead of taking a password from the user.
     */
    fun derivePassword(uidHex: String, mfg: String): DerivedPassword =
        when (val out = SonicarePassword.derive(uidHex, mfg)) {
            is SonicarePassword.Outcome.Invalid -> DerivedPassword.Err(out.message)
            is SonicarePassword.Outcome.Ok -> DerivedPassword.Ready(out.derived)
        }

    /**
     * Derive the password for a head that has just been read, taking the MFG
     * code from the tag's own page 0x21 offset 2, so nothing has to be typed.
     *
     * This only *derives*: the caller still builds the [WriteRequest] with the
     * page and data it actually wants, so no write is implied here.
     */
    fun derivePasswordFromScan(scan: ScanResult): DerivedPassword {
        val mfg = SonicarePassword.mfgFromPages(scan.pages)
            ?: return DerivedPassword.Err(
                "Il dump non contiene il codice MFG (pagina 0x21, offset 2)."
            )
        return derivePassword(scan.uid, mfg)
    }

    /**
     * Convenience: a [WriteRequest] for [page]/[data] using a password derived
     * from [uidHex] + [mfg] instead of one typed by the user.
     */
    fun withDerivedPassword(
        page: Int,
        data: ByteArray,
        uidHex: String,
        mfg: String,
    ): Pair<WriteRequest, SonicarePassword.Derived>? =
        (derivePassword(uidHex, mfg) as? DerivedPassword.Ready)?.let {
            WriteRequest(page, data, it.derived.password) to it.derived
        }

    /** Sonicare used-time counter page; bytes 0..1 hold the counter. */
    const val USAGE_PAGE = SonicarePassword.USAGE_PAGE

    private fun authAwareMessage(e: IOException, usedPassword: Boolean): String {
        val base = e.message?.takeIf { it.isNotBlank() }
        return if (usedPassword) {
            "Scrittura non riuscita: tag rimosso troppo presto, pagina protetta, o password errata" +
                (base?.let { " ($it)" } ?: "") + "."
        } else {
            "Scrittura non riuscita: tag rimosso troppo presto, oppure la pagina è protetta da password" +
                (base?.let { " ($it)" } ?: "") + "."
        }
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]); out.append(HEX[v and 0x0F])
        }
        return out.toString()
    }
}
