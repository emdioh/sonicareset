package com.sonicareset.app.nfc

/**
 * A single read-only page (4 bytes) dumped from the tag.
 *
 * @property index zero-based page index on the tag.
 * @property hex uppercase hex representation of the 4 bytes, no spaces.
 */
data class PageDump(val index: Int, val hex: String)

/**
 * Immutable result of a strictly READ-ONLY inspection of an NFC tag.
 *
 * Nothing in this model implies any mutation of the tag: it only carries
 * data that could be read without authenticating-to-bypass or writing.
 */
data class ScanResult(
    val uid: String,                 // hex, uppercase, no spaces
    val techList: List<String>,      // short class names, e.g. "MifareUltralight","NfcA","Ndef"
    val tagType: String,             // human label e.g. "MIFARE Ultralight" or "NFC-A"
    val atqa: String?,               // hex or null
    val sak: String?,                // hex or null
    val pages: List<PageDump>,       // read-only dump of pages that could be read WITHOUT authentication
    val ndefRecords: List<String>,   // decoded NDEF text/URI records, empty if none
    val notes: List<String>          // human notes, e.g. "Alcune pagine non leggibili (protette)."
)
