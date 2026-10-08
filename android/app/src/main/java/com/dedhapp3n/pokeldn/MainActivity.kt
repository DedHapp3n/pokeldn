package com.dedhapp3n.pokeldn

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
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
import com.dedhapp3n.pokeldn.usb.SerialConnectionPhase
import com.dedhapp3n.pokeldn.usb.SerialConnectionState
import com.dedhapp3n.pokeldn.usb.UsbSerialTransport
import com.dedhapp3n.pokeldn.usb.devicePhase
import com.dedhapp3n.pokeldn.usb.isCp210xBridge
import com.dedhapp3n.pokeldn.usb.usbIdHex
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val usbManager by lazy { getSystemService(UsbManager::class.java) }
    private val scanner by lazy { UsbDeviceScanner(this) }
    private val transport by lazy { UsbSerialTransport(usbManager) }
    private val serialWorker = Executors.newSingleThreadExecutor()
    private var scanResult by mutableStateOf<UsbScanResult?>(null)
    private var connectionState by mutableStateOf(SerialConnectionState())
    private var pendingDeviceId: Int? = null
    private var operationId = 0

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            if (intent.action == ACTION_USB_PERMISSION) handlePermissionResult(device, intent)
        }
    }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                if (connectionState.deviceName == device.deviceName) {
                    pendingDeviceId = null
                    operationId++
                    connectionState = SerialConnectionState(device.deviceName, SerialConnectionPhase.DISCONNECTED)
                    serialWorker.execute { transport.disconnectSafely() }
                }
                scanDevices()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ContextCompat.registerReceiver(
            this, permissionReceiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this, detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_EXPORTED,
        )
        setContent {
            PokeLDNTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    UsbDevicesScreen(
                        result = scanResult,
                        connectionState = connectionState,
                        onScan = ::scanDevices,
                        onConnect = ::connectSerial,
                        onDisconnect = ::disconnectSerial,
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

    override fun onDestroy() {
        unregisterReceiver(permissionReceiver)
        unregisterReceiver(detachReceiver)
        pendingDeviceId = null
        operationId++
        serialWorker.execute { transport.disconnectSafely() }
        serialWorker.shutdown()
        super.onDestroy()
    }

    private fun scanDevices() {
        scanResult = scanner.scan()
        val name = connectionState.deviceName ?: return
        val device = (scanResult as? UsbScanResult.Devices)?.items?.firstOrNull { it.deviceName == name }
        if (device == null && connectionState.phase != SerialConnectionPhase.DISCONNECTED) {
            pendingDeviceId = null
            operationId++
            connectionState = SerialConnectionState(name, SerialConnectionPhase.DISCONNECTED)
            serialWorker.execute { transport.disconnectSafely() }
        } else if (device != null && connectionState.phase in setOf(
                SerialConnectionPhase.READY, SerialConnectionPhase.PERMISSION_REQUIRED,
                SerialConnectionPhase.DEVICE_DETECTED,
            )) {
            connectionState = SerialConnectionState(name, devicePhase(device))
        }
    }

    private fun connectSerial(name: String) {
        val visible = (scanResult as? UsbScanResult.Devices)?.items?.firstOrNull { it.deviceName == name }
            ?: return
        if (!visible.serialSupported || connectionState.phase in setOf(
                SerialConnectionPhase.REQUESTING_PERMISSION, SerialConnectionPhase.CONNECTING,
                SerialConnectionPhase.CONNECTED, SerialConnectionPhase.DISCONNECTING,
            )) return

        val device = usbManager.deviceList[name] ?: run {
            scanDevices()
            return
        }
        connectionState = SerialConnectionState(name, SerialConnectionPhase.DEVICE_DETECTED)
        if (usbManager.hasPermission(device)) {
            connectionState = SerialConnectionState(name, SerialConnectionPhase.READY)
            openSerial(device)
        } else {
            pendingDeviceId = device.deviceId
            connectionState = SerialConnectionState(name, SerialConnectionPhase.REQUESTING_PERMISSION)
            val permissionIntent = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            try {
                usbManager.requestPermission(device, permissionIntent)
            } catch (error: Exception) {
                pendingDeviceId = null
                connectionState = SerialConnectionState(name, SerialConnectionPhase.CONNECTION_FAILED, error.message)
            }
        }
    }

    private fun handlePermissionResult(device: UsbDevice, intent: Intent) {
        if (pendingDeviceId != device.deviceId || connectionState.deviceName != device.deviceName) return
        pendingDeviceId = null
        if (!usbManager.deviceList.containsKey(device.deviceName)) {
            connectionState = SerialConnectionState(device.deviceName, SerialConnectionPhase.DISCONNECTED)
        } else if (!intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) ||
            !usbManager.hasPermission(device)) {
            connectionState = SerialConnectionState(device.deviceName, SerialConnectionPhase.PERMISSION_DENIED)
        } else {
            connectionState = SerialConnectionState(device.deviceName, SerialConnectionPhase.READY)
            openSerial(device)
        }
        scanResult = scanner.scan()
    }

    private fun openSerial(device: UsbDevice) {
        val thisOperation = ++operationId
        connectionState = SerialConnectionState(device.deviceName, SerialConnectionPhase.CONNECTING)
        serialWorker.execute {
            val error = try {
                transport.connect(device)
                null
            } catch (failure: Exception) {
                failure
            }
            runOnUiThread {
                if (thisOperation != operationId) return@runOnUiThread
                connectionState = if (error == null && usbManager.deviceList.containsKey(device.deviceName)) {
                    SerialConnectionState(device.deviceName, SerialConnectionPhase.CONNECTED)
                } else if (!usbManager.deviceList.containsKey(device.deviceName)) {
                    serialWorker.execute { transport.disconnectSafely() }
                    SerialConnectionState(device.deviceName, SerialConnectionPhase.DISCONNECTED)
                } else {
                    SerialConnectionState(device.deviceName, SerialConnectionPhase.CONNECTION_FAILED, error?.message)
                }
            }
        }
    }

    private fun disconnectSerial() {
        val name = connectionState.deviceName ?: return
        val thisOperation = ++operationId
        connectionState = SerialConnectionState(name, SerialConnectionPhase.DISCONNECTING)
        serialWorker.execute {
            transport.disconnectSafely()
            runOnUiThread {
                if (thisOperation != operationId) return@runOnUiThread
                connectionState = SerialConnectionState(
                    name,
                    if (usbManager.deviceList.containsKey(name)) SerialConnectionPhase.READY
                    else SerialConnectionPhase.DISCONNECTED,
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else getParcelableExtra(UsbManager.EXTRA_DEVICE)

    companion object {
        private const val ACTION_USB_PERMISSION = "com.dedhapp3n.pokeldn.USB_PERMISSION"
    }
}

private fun UsbSerialTransport.disconnectSafely() {
    try { disconnect() } catch (_: Exception) { /* A detached device may already be closed. */ }
}

@Composable
private fun UsbDevicesScreen(
    result: UsbScanResult?,
    connectionState: SerialConnectionState,
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
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
        if (connectionState.deviceName != null && connectionState.phase == SerialConnectionPhase.DISCONNECTED) {
            Text(SerialConnectionPhase.DISCONNECTED.label, color = MaterialTheme.colorScheme.error)
        }

        if (result is UsbScanResult.Devices && result.items.isNotEmpty()) {
            val busy = connectionState.phase in setOf(
                SerialConnectionPhase.REQUESTING_PERMISSION, SerialConnectionPhase.CONNECTING,
                SerialConnectionPhase.CONNECTED, SerialConnectionPhase.DISCONNECTING,
            )
            LazyColumn(
                contentPadding = PaddingValues(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(result.items, key = { it.deviceName }) { device ->
                    UsbDeviceCard(
                        device = device,
                        state = if (connectionState.deviceName == device.deviceName) connectionState
                            else SerialConnectionState(device.deviceName, devicePhase(device)),
                        canConnect = !busy,
                        onConnect = { onConnect(device.deviceName) },
                        onDisconnect = onDisconnect,
                    )
                }
            }
        }
    }
}

@Composable
private fun UsbDeviceCard(
    device: UsbDeviceInfo,
    state: SerialConnectionState,
    canConnect: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(device.productName ?: "USB device", style = MaterialTheme.typography.titleMedium)
            if (isCp210xBridge(device.vendorId, device.productId)) {
                Text("Silicon Labs CP210x USB serial bridge")
            }
            Text("Device name: ${device.deviceName}")
            Text("VID: ${usbIdHex(device.vendorId)}  PID: ${usbIdHex(device.productId)}")
            device.manufacturer?.let { Text("Manufacturer: $it") }
            device.productName?.let { Text("Product: $it") }
            if (device.serialSupported) {
                Text("Status: ${state.phase.label}")
                state.detail?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Serial settings: ${UsbSerialTransport.BAUD_RATE} baud, 8N1, no flow control")
                if (state.phase == SerialConnectionPhase.CONNECTED) {
                    Button(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
                } else {
                    Button(
                        onClick = onConnect,
                        enabled = canConnect && state.phase != SerialConnectionPhase.REQUESTING_PERMISSION &&
                            state.phase != SerialConnectionPhase.CONNECTING,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Connect Serial") }
                }
            } else {
                Text("No supported serial driver for this device")
            }
        }
    }
}
