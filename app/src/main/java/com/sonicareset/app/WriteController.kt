package com.sonicareset.app

import androidx.compose.runtime.mutableStateOf
import com.sonicareset.app.nfc.NfcWriter

/**
 * Bridges the Compose "Write" screen and the NFC reader-mode callback in
 * [MainActivity]. The user composes and *arms* a write; the next tag tapped
 * while armed receives exactly that one write, then the controller disarms.
 */
class WriteController {
    /** True while a write is armed and waiting for the user to present a tag. */
    val armed = mutableStateOf(false)

    /** Human-readable outcome of the last write attempt, shown in the UI. */
    val lastResult = mutableStateOf<String?>(null)

    /** The single pending request; read by the NFC callback, then cleared. */
    @Volatile
    var pending: NfcWriter.WriteRequest? = null
        private set

    fun arm(request: NfcWriter.WriteRequest) {
        pending = request
        lastResult.value = null
        armed.value = true
    }

    fun disarm() {
        pending = null
        armed.value = false
    }

    fun reportResult(message: String) {
        lastResult.value = message
    }
}
