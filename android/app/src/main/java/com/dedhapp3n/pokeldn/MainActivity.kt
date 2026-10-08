package com.dedhapp3n.pokeldn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dedhapp3n.pokeldn.ui.theme.PokeLDNTheme
import com.dedhapp3n.pokeldn.usb.UsbDeviceInfo
import com.dedhapp3n.pokeldn.usb.UsbDeviceScanner
import com.dedhapp3n.pokeldn.usb.UsbScanResult
import com.dedhapp3n.pokeldn.usb.usbIdHex

class MainActivity : ComponentActivity() {
    private val scanner by lazy { UsbDeviceScanner(this) }
    private var scanResult by mutableStateOf<UsbScanResult?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PokeLDNTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    UsbDevicesScreen(
                        result = scanResult,
                        onScan = ::scanDevices,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        scanDevices()
    }

    private fun scanDevices() {
        scanResult = scanner.scan()
    }
}

@Composable
private fun UsbDevicesScreen(
    result: UsbScanResult?,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("USB devices", style = MaterialTheme.typography.headlineMedium)
        Text("Connect your ESP32 through USB OTG, then scan for devices visible to Android.")
        Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
            Text("Scan USB Devices")
        }

        val status = when (result) {
            null -> "Checking USB devices…"
            UsbScanResult.HostUnavailable -> "USB Host is unavailable on this device."
            UsbScanResult.ScanFailed -> "Could not scan USB devices. Try again."
            is UsbScanResult.Devices -> if (result.items.isEmpty()) {
                "No USB devices found. Check the cable and USB OTG connection."
            } else {
                "${result.items.size} USB device${if (result.items.size == 1) "" else "s"} found."
            }
        }
        Text(status, style = MaterialTheme.typography.titleMedium)

        if (result is UsbScanResult.Devices && result.items.isNotEmpty()) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(result.items, key = { it.deviceName }) { device ->
                    UsbDeviceCard(device)
                }
            }
        }
    }
}

@Composable
private fun UsbDeviceCard(device: UsbDeviceInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(device.productName ?: "USB device", style = MaterialTheme.typography.titleMedium)
            if (device.vendorId == 0x10C4 && device.productId == 0xEA60) {
                Text("Silicon Labs CP210x USB serial bridge")
            }
            Text("Device name: ${device.deviceName}")
            Text("VID: ${usbIdHex(device.vendorId)}  PID: ${usbIdHex(device.productId)}")
            device.manufacturer?.let { Text("Manufacturer: $it") }
            device.productName?.let { Text("Product: $it") }
        }
    }
}
