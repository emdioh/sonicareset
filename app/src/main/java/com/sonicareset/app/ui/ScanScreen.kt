package com.sonicareset.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sonicareset.app.nfc.PageDump
import com.sonicareset.app.nfc.ScanResult

@Composable
fun ScanScreen(
    result: ScanResult?,
    hasNfc: Boolean,
    nfcEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    when {
        !hasNfc -> CenteredMessage(
            text = "Questo dispositivo non ha NFC.",
            modifier = modifier
        )

        !nfcEnabled -> CenteredMessage(
            text = "NFC disattivato. Attivalo nelle impostazioni.",
            modifier = modifier
        )

        result == null -> ScanPrompt(modifier = modifier)

        else -> ScanResultContent(result = result, modifier = modifier)
    }
}

@Composable
private fun CenteredMessage(
    text: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ScanPrompt(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Nfc,
                contentDescription = "NFC",
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Avvicina il retro del telefono alla testina dello spazzolino per leggerne il tag NFC.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Text(
                text = "L'app legge soltanto il tag e non lo modifica in alcun modo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ScanResultContent(
    result: ScanResult,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Dati del tag",
                        style = MaterialTheme.typography.titleMedium
                    )
                    InfoRow(label = "Tipo", value = result.tagType)
                    InfoRow(label = "UID", value = result.uid, monospace = true)
                    InfoRow(
                        label = "Tech",
                        value = if (result.techList.isEmpty()) "-"
                        else result.techList.joinToString(", ")
                    )
                    result.atqa?.let { InfoRow(label = "ATQA", value = it, monospace = true) }
                    result.sak?.let { InfoRow(label = "SAK", value = it, monospace = true) }
                }
            }
        }

        if (result.pages.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Pagine (sola lettura)",
                            style = MaterialTheme.typography.titleMedium
                        )
                        result.pages.forEach { page -> PageRow(page = page) }
                    }
                }
            }
        }

        if (result.ndefRecords.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Record NDEF",
                            style = MaterialTheme.typography.titleMedium
                        )
                        result.ndefRecords.forEach { record ->
                            Text(
                                text = record,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        if (result.notes.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    result.notes.forEach { note ->
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Questa schermata legge il tag soltanto e non lo modifica.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    monospace: Boolean = false
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
        )
    }
}

@Composable
private fun PageRow(page: PageDump) {
    Text(
        text = "Pg ${page.index}: ${page.hex}",
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = FontFamily.Monospace
    )
}
