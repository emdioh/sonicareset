package com.sonicareset.app.nfc

import android.nfc.Tag
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import java.io.IOException

/**
 * Strictly READ-ONLY NFC inspector for Philips Sonicare brush-head tags
 * (MIFARE Ultralight / NFC-A).
 *
 * This object only READS data from a tag. It never writes, never
 * authenticates-to-bypass, never resets and never modifies tag memory.
 * No write or mutating transceive command is issued anywhere here:
 * there is no [MifareUltralight.writePage], no [Ndef.writeNdefMessage]
 * and no raw transceive with write opcodes. Every operation is a plain,
 * non-destructive read.
 */
object NfcReader {

    private val HEX = "0123456789ABCDEF".toCharArray()

    /** ByteArray -> uppercase hex string, no spaces. */
    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4])
            out.append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    /** Short class name from a fully-qualified tech name, e.g. "android.nfc.tech.NfcA" -> "NfcA". */
    private fun shortName(fqcn: String): String = fqcn.substringAfterLast('.')

    /**
     * Perform a strictly read-only inspection of [tag] and return a [ScanResult].
     * This method never mutates the tag in any way.
     */
    fun read(tag: Tag): ScanResult {
        val uid: String = (tag.id ?: ByteArray(0)).toHex()

        val rawTechs: Array<String> = tag.techList ?: emptyArray()
        val techList: List<String> = rawTechs.map { shortName(it) }

        val hasMifareUltralight = techList.contains("MifareUltralight")
        val hasNfcA = techList.contains("NfcA")
        val hasNdef = techList.contains("Ndef")

        val pages = mutableListOf<PageDump>()
        val ndefRecords = mutableListOf<String>()
        val notes = mutableListOf<String>()
        var atqa: String? = null
        var sak: String? = null

        // ---- MIFARE Ultralight: read-only page dump (no authentication) ----
        if (hasMifareUltralight) {
            try {
                readUltralightPages(tag, pages, notes)
            } catch (e: Exception) {
                notes.add("Lettura MIFARE Ultralight non riuscita (tag rimosso o errore).")
            }
        } else if (hasNfcA) {
            // ---- NFC-A only: capture ATQA / SAK, no raw transceive ----
            try {
                val nfcA = NfcA.get(tag)
                if (nfcA != null) {
                    nfcA.connect()
                    try {
                        atqa = nfcA.atqa?.toHex()
                        sak = ((nfcA.sak.toInt() and 0xFF)).let { v ->
                            val a = ByteArray(1); a[0] = v.toByte(); a.toHex()
                        }
                    } finally {
                        try { nfcA.close() } catch (_: Exception) { }
                    }
                }
            } catch (e: Exception) {
                notes.add("Lettura NFC-A non riuscita (tag rimosso o errore).")
            }
        }

        // ---- NDEF: read-only message decode ----
        if (hasNdef) {
            try {
                readNdef(tag, ndefRecords, notes)
            } catch (e: Exception) {
                notes.add("Lettura NDEF non riuscita (tag rimosso o errore).")
            }
        }

        val tagType = when {
            hasMifareUltralight -> "MIFARE Ultralight"
            hasNfcA -> "NFC-A"
            hasNdef -> "NDEF"
            else -> "Sconosciuto"
        }

        return ScanResult(
            uid = uid,
            techList = techList,
            tagType = tagType,
            atqa = atqa,
            sak = sak,
            pages = pages,
            ndefRecords = ndefRecords,
            notes = notes
        )
    }

    /**
     * Read pages in blocks of 4 via [MifareUltralight.readPages], starting at 0,
     * up to 64 pages (16 reads). On an [IOException] (protected page or out of
     * range) it STOPS reading and records an Italian note. Always closes in finally.
     * This is a pure read: no write/transceive command is used.
     */
    private fun readUltralightPages(
        tag: Tag,
        pages: MutableList<PageDump>,
        notes: MutableList<String>
    ) {
        val mul = MifareUltralight.get(tag) ?: return
        mul.connect()
        try {
            var startPage = 0
            val maxPages = 64
            while (startPage < maxPages) {
                val block: ByteArray
                try {
                    block = mul.readPages(startPage) // read-only: returns 4 pages (16 bytes)
                } catch (io: IOException) {
                    notes.add("Alcune pagine non leggibili (protette).")
                    break
                } catch (e: Exception) {
                    notes.add("Alcune pagine non leggibili (protette).")
                    break
                }
                if (block.isEmpty()) break
                val fullPages = block.size / 4
                for (p in 0 until fullPages) {
                    val idx = startPage + p
                    if (idx >= maxPages) break
                    val from = p * 4
                    val pageBytes = block.copyOfRange(from, from + 4)
                    pages.add(PageDump(index = idx, hex = pageBytes.toHex()))
                }
                startPage += 4
            }
        } finally {
            try { mul.close() } catch (_: Exception) { }
        }
    }

    /**
     * Read (only) the NDEF message from the tag and decode well-known Text and URI
     * records to strings. Prefers the cached message; otherwise connects and reads
     * the live message. Never writes.
     */
    private fun readNdef(
        tag: Tag,
        out: MutableList<String>,
        notes: MutableList<String>
    ) {
        val ndef = Ndef.get(tag) ?: return

        var message = ndef.cachedNdefMessage
        if (message == null) {
            ndef.connect()
            try {
                message = ndef.ndefMessage // read-only
            } finally {
                try { ndef.close() } catch (_: Exception) { }
            }
        }

        val records = message?.records ?: return
        for (record in records) {
            try {
                val decoded = decodeRecord(record)
                if (decoded != null) out.add(decoded)
            } catch (e: Exception) {
                notes.add("Un record NDEF non e' stato decodificato.")
            }
        }
    }

    /** Decode a single NDEF record (well-known Text or URI) to a string, or null. */
    private fun decodeRecord(record: android.nfc.NdefRecord): String? {
        val tnf = record.tnf
        val type = record.type ?: ByteArray(0)
        val payload = record.payload ?: ByteArray(0)

        if (tnf == android.nfc.NdefRecord.TNF_WELL_KNOWN) {
            // Text record: RTD_TEXT = "T"
            if (type.contentEquals(android.nfc.NdefRecord.RTD_TEXT)) {
                if (payload.isEmpty()) return ""
                val status = payload[0].toInt() and 0xFF
                val isUtf16 = (status and 0x80) != 0
                val langLen = status and 0x3F
                val textStart = 1 + langLen
                if (textStart > payload.size) return ""
                val charset = if (isUtf16) Charsets.UTF_16 else Charsets.UTF_8
                return String(payload, textStart, payload.size - textStart, charset)
            }
            // URI record: RTD_URI = "U"
            if (type.contentEquals(android.nfc.NdefRecord.RTD_URI)) {
                if (payload.isEmpty()) return ""
                val prefixCode = payload[0].toInt() and 0xFF
                val prefix = uriPrefix(prefixCode)
                val rest = String(payload, 1, payload.size - 1, Charsets.UTF_8)
                return prefix + rest
            }
        }
        return null
    }

    /** NFC Forum URI prefix abbreviation table. */
    private fun uriPrefix(code: Int): String = when (code) {
        0x00 -> ""
        0x01 -> "http://www."
        0x02 -> "https://www."
        0x03 -> "http://"
        0x04 -> "https://"
        0x05 -> "tel:"
        0x06 -> "mailto:"
        0x07 -> "ftp://anonymous:anonymous@"
        0x08 -> "ftp://ftp."
        0x09 -> "ftps://"
        0x0A -> "sftp://"
        0x0B -> "smb://"
        0x0C -> "nfs://"
        0x0D -> "ftp://"
        0x0E -> "dav://"
        0x0F -> "news:"
        0x10 -> "telnet://"
        0x11 -> "imap:"
        0x12 -> "rtsp://"
        0x13 -> "urn:"
        0x14 -> "pop:"
        0x15 -> "sip:"
        0x16 -> "sips:"
        0x17 -> "tftp:"
        0x18 -> "btspp://"
        0x19 -> "btl2cap://"
        0x1A -> "btgoep://"
        0x1B -> "tcpobex://"
        0x1C -> "irdaobex://"
        0x1D -> "file://"
        0x1E -> "urn:epc:id:"
        0x1F -> "urn:epc:tag:"
        0x20 -> "urn:epc:pat:"
        0x21 -> "urn:epc:raw:"
        0x22 -> "urn:epc:"
        0x23 -> "urn:nfc:"
        else -> ""
    }
}
