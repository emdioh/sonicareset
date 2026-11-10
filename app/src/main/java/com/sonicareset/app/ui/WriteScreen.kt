package com.sonicareset.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sonicareset.app.WriteController
import com.sonicareset.app.nfc.NfcWriter
import com.sonicareset.app.nfc.SonicarePassword

/**
 * Experimental write screen. The user supplies the page, the 4 bytes and
 * either their own password or the head's UID + MFG code, from which the app
 * derives the Sonicare password (ATC1441 algorithm, see [SonicarePassword]).
 */
@Composable
fun WriteScreen(
    controller: WriteController,
    hasNfc: Boolean,
    nfcEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!hasNfc) {
        CenteredInfo("Questo dispositivo non ha NFC.", modifier); return
    }
    if (!nfcEnabled) {
        CenteredInfo("NFC disattivato. Attivalo nelle impostazioni.", modifier); return
    }

    val armed by controller.armed
    val lastResult by controller.lastResult

    var pageText by remember { mutableStateOf("4") }
    var dataHex by remember { mutableStateOf("") }
    var pwdHex by remember { mutableStateOf("") }
    var deriveMode by remember { mutableStateOf(true) }
    var uidText by remember { mutableStateOf("") }
    var mfgText by remember { mutableStateOf("") }
    var derivedPreview by remember { mutableStateOf<SonicarePassword.Derived?>(null) }
    var validationError by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Modalità sperimentale",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "Scrive 4 byte su una pagina del tag che presenti. " +
                            "Scrivere valori errati può rendere la testina inutilizzabile in modo " +
                            "permanente. Le pagine 0-3 (UID/lock/OTP) sono bloccate. " +
                            "Per le pagine protette puoi far ricavare la password all'app da UID + " +
                            "codice MFG (algoritmo ATC1441), oppure inserirla a mano.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "ATTENZIONE: le testine NTAG213 hanno CFG1=0x43. Dopo 3 password " +
                            "sbagliate la scrittura viene disattivata PER SEMPRE. L'app non riprova " +
                            "mai da sola: controlla che la password derivata sia corretta prima di scrivere.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                value = pageText,
                onValueChange = { s -> pageText = s.filter { it.isDigit() }.take(3) },
                label = { Text("Pagina (>= ${NfcWriter.MIN_WRITABLE_PAGE})") },
                singleLine = true,
                enabled = !armed,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            OutlinedTextField(
                value = dataHex,
                onValueChange = { s -> dataHex = s.filterHex().take(8) },
                label = { Text("Dati: 8 cifre hex (4 byte)") },
                placeholder = { Text("es. 00000000") },
                singleLine = true,
                enabled = !armed,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = deriveMode,
                    onClick = { if (!armed) { deriveMode = true; validationError = null } },
                    label = { Text("Ricava da UID + MFG") },
                    enabled = !armed,
                )
                FilterChip(
                    selected = !deriveMode,
                    onClick = { if (!armed) { deriveMode = false; validationError = null } },
                    label = { Text("Password manuale") },
                    enabled = !armed,
                )
            }
        }

        if (deriveMode) {
            item {
                OutlinedTextField(
                    value = uidText,
                    onValueChange = { s ->
                        uidText = s.filterHexColons().take(23)
                        derivedPreview = null
                    },
                    label = { Text("UID del tag (7 byte) o ultimi 4 byte") },
                    placeholder = { Text("es. 04:EC:FC:A2:94:10:90") },
                    singleLine = true,
                    enabled = !armed,
                    isError = validationError != null &&
                        SonicarePassword.normaliseUid(uidText) == null,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = mfgText,
                    onValueChange = { s ->
                        mfgText = s.filterMfg().take(11)
                        derivedPreview = null
                    },
                    label = { Text("Codice MFG (stampato sotto la testina)") },
                    placeholder = { Text("es. 221214 12K") },
                    singleLine = true,
                    enabled = !armed,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedButton(
                    onClick = {
                        when (val out = SonicarePassword.derive(uidText, mfgText)) {
                            is SonicarePassword.Outcome.Ok -> {
                                derivedPreview = out.derived
                                validationError = null
                            }
                            is SonicarePassword.Outcome.Invalid -> {
                                derivedPreview = null
                                validationError = out.message
                            }
                        }
                    },
                    enabled = !armed,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Calcola password") }
            }
            derivedPreview?.let { d ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("Password derivata", style = MaterialTheme.typography.titleSmall)
                            Text(
                                d.password.joinToString(":") { "%02X".format(it) },
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                            )
                            Text(
                                "UID ${d.uid.joinToString(":") { "%02X".format(it) }} · MFG \"${d.mfg}\"",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                "Comando PWD_AUTH: 1B:" + d.hex.chunked(2).joinToString(":"),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                            )
                        }
                    }
                }
            }
        } else {
            item {
                OutlinedTextField(
                    value = pwdHex,
                    onValueChange = { s -> pwdHex = s.filterHex().take(8) },
                    label = { Text("Password tag (opzionale): 8 cifre hex") },
                    placeholder = { Text("vuota = nessun PWD_AUTH") },
                    singleLine = true,
                    enabled = !armed,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        validationError?.let { err ->
            item {
                Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        item {
            if (!armed) {
                Button(
                    onClick = {
                        val password = if (deriveMode) derivedPreview?.password else
                            if (pwdHex.isEmpty()) null else hexToBytes(pwdHex)
                        val err = when {
                            deriveMode && derivedPreview == null ->
                                "Prima calcola la password da UID + MFG."
                            else -> validate(pageText, dataHex, pwdHex, deriveMode)
                        }
                        validationError = err
                        if (err == null) {
                            controller.arm(
                                NfcWriter.WriteRequest(
                                    page = pageText.toInt(),
                                    data = hexToBytes(dataHex),
                                    password = password,
                                )
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Arma scrittura") }
            } else {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Armato. Avvicina ORA il tag al retro del telefono.",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        OutlinedButton(
                            onClick = { controller.disarm() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Annulla") }
                    }
                }
            }
        }

        lastResult?.let { res ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Esito", style = MaterialTheme.typography.titleSmall)
                        Text(res, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredInfo(text: String, modifier: Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

private fun String.filterHex(): String =
    filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.uppercase()

/** Keep hex digits plus ':'. */
private fun String.filterHexColons(): String =
    filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }.uppercase()

/** Keep hex digits (UPPER), digits, space and '-' for the MFG field. */
private fun String.filterMfg(): String =
    filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.uppercase()

private fun validate(
    pageText: String,
    dataHex: String,
    pwdHex: String,
    deriveMode: Boolean,
): String? {
    val page = pageText.toIntOrNull()
        ?: return "Pagina non valida."
    if (page < NfcWriter.MIN_WRITABLE_PAGE) {
        return "Le pagine 0-${NfcWriter.MIN_WRITABLE_PAGE - 1} sono bloccate (UID/lock/OTP)."
    }
    if (dataHex.length != 8) return "I dati devono essere 8 cifre hex (4 byte)."
    if (deriveMode) return null   // password derivation already validated by preview
    if (pwdHex.isNotEmpty() && pwdHex.length != 8) {
        return "La password deve essere 8 cifre hex (4 byte) oppure vuota."
    }
    return null
}

private fun hexToBytes(hex: String): ByteArray {
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        val hi = Character.digit(hex[i * 2], 16)
        val lo = Character.digit(hex[i * 2 + 1], 16)
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}
