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
 *  - If the target page is protected, the user may supply THEIR OWN password;
 *    it is sent as a standard PWD_AUTH (0x1B) command. This object does NOT
 *    derive, guess or brute-force any password or key: no manufacturer
 *    key-derivation is implemented here. If the user does not know the
 *    password, protected pages simply remain unwritable.
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
    )

    sealed interface WriteResult {
        /** The page was written. [pack] is the PACK returned by PWD_AUTH, if a password was used. */
        data class Success(val page: Int, val pack: String?) : WriteResult
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
