package com.sonicareset.app

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sonicareset.app.nfc.NfcReader
import com.sonicareset.app.nfc.NfcWriter
import com.sonicareset.app.nfc.ScanResult
import com.sonicareset.app.tracker.TrackerViewModel
import com.sonicareset.app.ui.ScanScreen
import com.sonicareset.app.ui.TrackerScreen
import com.sonicareset.app.ui.WriteScreen

/**
 * Host activity. Wires a strictly read-only NFC reader (reader mode) to the
 * Scan screen, and hosts the manual brush-head Tracker. The app never writes
 * to or modifies an NFC tag; it only reads data that is accessible.
 */
class MainActivity : ComponentActivity() {

    private var nfcAdapter: NfcAdapter? = null

    // Holds the most recent read result; observed by Compose.
    private val scanResultState = mutableStateOf<ScanResult?>(null)
    private val nfcEnabledState = mutableStateOf(false)

    // Mediates the experimental write flow.
    private val writeController = WriteController()

    private val readerCallback = NfcAdapter.ReaderCallback { tag: Tag ->
        val pending = writeController.pending
        if (pending != null) {
            // A write is armed: perform exactly that one write, then disarm.
            val res = NfcWriter.write(tag, pending)
            runOnUiThread {
                writeController.reportResult(renderWriteResult(res))
                writeController.disarm()
            }
        } else {
            val result = try {
                NfcReader.read(tag)
            } catch (t: Throwable) {
                null
            }
            if (result != null) {
                runOnUiThread { scanResultState.value = result }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        setContent {
            MaterialTheme {
                AppRoot(
                    hasNfc = nfcAdapter != null,
                    nfcEnabled = nfcEnabledState.value,
                    scanResult = scanResultState.value,
                    writeController = writeController,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter
        nfcEnabledState.value = adapter?.isEnabled == true
        adapter?.enableReaderMode(
            this,
            readerCallback,
            NfcAdapter.FLAG_READER_NFC_A or
                NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or
                NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null,
        )
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }
}

private enum class Tab { Scan, Write, Tracker }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(
    hasNfc: Boolean,
    nfcEnabled: Boolean,
    scanResult: ScanResult?,
    writeController: WriteController,
) {
    var tab by remember { mutableStateOf(Tab.Scan) }
    val trackerVm: TrackerViewModel = viewModel()

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text(
                    when (tab) {
                        Tab.Scan -> "Lettura NFC"
                        Tab.Write -> "Scrittura (sperimentale)"
                        Tab.Tracker -> "Testine"
                    }
                )
            })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.Scan,
                    onClick = { tab = Tab.Scan },
                    icon = { Icon(Icons.Filled.Nfc, contentDescription = null) },
                    label = { Text("Scansione") },
                )
                NavigationBarItem(
                    selected = tab == Tab.Write,
                    onClick = { tab = Tab.Write },
                    icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    label = { Text("Scrivi") },
                )
                NavigationBarItem(
                    selected = tab == Tab.Tracker,
                    onClick = { tab = Tab.Tracker },
                    icon = { Icon(Icons.Filled.Spa, contentDescription = null) },
                    label = { Text("Testine") },
                )
            }
        },
    ) { padding ->
        when (tab) {
            Tab.Scan -> ScanScreen(
                result = scanResult,
                hasNfc = hasNfc,
                nfcEnabled = nfcEnabled,
                modifier = Modifier.padding(padding),
            )
            Tab.Write -> WriteScreen(
                controller = writeController,
                hasNfc = hasNfc,
                nfcEnabled = nfcEnabled,
                modifier = Modifier.padding(padding),
            )
            Tab.Tracker -> TrackerScreen(
                vm = trackerVm,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

/** Render an [NfcWriter.WriteResult] as a short Italian message for the UI. */
private fun renderWriteResult(res: NfcWriter.WriteResult): String = when (res) {
    is NfcWriter.WriteResult.Success ->
        "Pagina ${res.page} scritta." + (res.pack?.let { " PACK: $it" } ?: "")
    is NfcWriter.WriteResult.Blocked -> res.message
    is NfcWriter.WriteResult.Failure -> res.message
}
