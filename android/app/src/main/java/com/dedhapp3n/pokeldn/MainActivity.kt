package com.dedhapp3n.pokeldn

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dedhapp3n.pokeldn.esp32.Esp32Client
import com.dedhapp3n.pokeldn.esp32.Esp32HandshakePhase
import com.dedhapp3n.pokeldn.esp32.Esp32HandshakeState
import com.dedhapp3n.pokeldn.esp32.Esp32IncompatibleProtocolException
import com.dedhapp3n.pokeldn.esp32.RawCapturePhase
import com.dedhapp3n.pokeldn.esp32.RawCaptureState
import com.dedhapp3n.pokeldn.esp32.RawSerialCapture
import com.dedhapp3n.pokeldn.esp32.formatMac
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
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private val usbManager by lazy { getSystemService(UsbManager::class.java) }
    private val scanner by lazy { UsbDeviceScanner(this) }
    private val transport by lazy { UsbSerialTransport(usbManager) }
    private val serialWorker = Executors.newSingleThreadExecutor()
    private val helloWorker = Executors.newCachedThreadPool()
    private val helloWatchdog = Executors.newSingleThreadScheduledExecutor()
    private var scanResult by mutableStateOf<UsbScanResult?>(null)
    private var connectionState by mutableStateOf(SerialConnectionState())
    private var esp32State by mutableStateOf(Esp32HandshakeState())
    private var rawCaptureState by mutableStateOf(RawCaptureState())
    private var connectedBaudRate by mutableIntStateOf(UsbSerialTransport.BAUD_RATE)
    private var selectedDiagnosticBaud by mutableIntStateOf(UsbSerialTransport.BAUD_RATE)
    private var pendingDeviceId: Int? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var permissionTimeout: Runnable? = null
    private var operationId = 0
    private var helloOperationId = 0
    private var helloFuture: Future<*>? = null
    private var helloTimeout: Future<*>? = null
    private var rawCaptureOperationId = 0
    private var rawCaptureFuture: Future<*>? = null
    private var rawCaptureTimeout: Future<*>? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) handlePermissionResult(intent)
        }
    }

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                if (connectionState.deviceName == device.deviceName) {
                    clearPendingPermission()
                    cancelHello()
                    cancelRawCapture()
                    operationId++
                    esp32State = Esp32HandshakeState()
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
                        esp32State = esp32State,
                        rawCaptureState = rawCaptureState,
                        connectedBaudRate = connectedBaudRate,
                        selectedDiagnosticBaud = selectedDiagnosticBaud,
                        onScan = ::scanDevices,
                        onConnect = ::connectSerial,
                        onDisconnect = ::disconnectSerial,
                        onTestEsp32 = ::testEsp32,
                        onDiagnosticBaudSelected = { selectedDiagnosticBaud = it },
                        onCaptureRaw = ::captureRawSerial,
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
        clearPendingPermission()
        cancelHello()
        cancelRawCapture()
        operationId++
        serialWorker.execute { transport.disconnectSafely() }
        serialWorker.shutdown()
        helloWorker.shutdownNow()
        helloWatchdog.shutdownNow()
        super.onDestroy()
    }

    private fun scanDevices() {
        scanResult = scanner.scan()
        val name = connectionState.deviceName ?: return
        val device = (scanResult as? UsbScanResult.Devices)?.items?.firstOrNull { it.deviceName == name }
        if (device == null && connectionState.phase != SerialConnectionPhase.DISCONNECTED) {
            clearPendingPermission()
            cancelHello()
            cancelRawCapture()
            operationId++
            esp32State = Esp32HandshakeState()
            connectionState = SerialConnectionState(name, SerialConnectionPhase.DISCONNECTED)
            serialWorker.execute { transport.disconnectSafely() }
        } else if (device != null && connectionState.phase in setOf(
                SerialConnectionPhase.READY, SerialConnectionPhase.PERMISSION_REQUIRED,
                SerialConnectionPhase.DEVICE_DETECTED,
            )) {
            connectionState = SerialConnectionState(name, devicePhase(device))
        } else if (device != null && connectionState.phase == SerialConnectionPhase.CONNECTION_FAILED &&
            device.hasPermission) {
            connectionState = SerialConnectionState(name, SerialConnectionPhase.READY)
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
            try {
                // UsbManager adds EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED to the result.
                val permissionIntent = PendingIntent.getBroadcast(
                    this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                usbManager.requestPermission(device, permissionIntent)
                schedulePermissionTimeout(device)
            } catch (error: Exception) {
                clearPendingPermission()
                connectionState = SerialConnectionState(name, SerialConnectionPhase.CONNECTION_FAILED, error.message)
            }
        }
    }

    private fun handlePermissionResult(intent: Intent) {
        val name = connectionState.deviceName ?: return
        val id = pendingDeviceId ?: return
        val reportedDevice = intent.usbDevice()
        if (reportedDevice != null && (reportedDevice.deviceId != id || reportedDevice.deviceName != name)) return
        clearPendingPermission()
        val device = usbManager.deviceList[name]
        if (device == null || device.deviceId != id) {
            connectionState = SerialConnectionState(name, SerialConnectionPhase.DISCONNECTED)
        } else if (usbManager.hasPermission(device)) {
            connectionState = SerialConnectionState(name, SerialConnectionPhase.READY)
            openSerial(device)
        } else if (intent.hasExtra(UsbManager.EXTRA_PERMISSION_GRANTED) &&
            !intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            connectionState = SerialConnectionState(name, SerialConnectionPhase.PERMISSION_DENIED)
        } else {
            connectionState = SerialConnectionState(name, SerialConnectionPhase.CONNECTION_FAILED,
                "USB permission response was incomplete. Tap Connect Serial to retry.")
        }
        scanResult = scanner.scan()
    }

    private fun schedulePermissionTimeout(device: UsbDevice) {
        val timeout = Runnable {
            if (pendingDeviceId != device.deviceId || connectionState.deviceName != device.deviceName) return@Runnable
            clearPendingPermission()
            val current = usbManager.deviceList[device.deviceName]
            if (current == null || current.deviceId != device.deviceId) {
                connectionState = SerialConnectionState(device.deviceName, SerialConnectionPhase.DISCONNECTED)
            } else if (usbManager.hasPermission(current)) {
                connectionState = SerialConnectionState(device.deviceName, SerialConnectionPhase.READY)
                openSerial(current)
            } else {
                connectionState = SerialConnectionState(device.deviceName, SerialConnectionPhase.CONNECTION_FAILED,
                    "USB permission response timed out. Tap Connect Serial to retry.")
            }
            scanResult = scanner.scan()
        }
        permissionTimeout = timeout
        mainHandler.postDelayed(timeout, PERMISSION_TIMEOUT_MS)
    }

    private fun clearPendingPermission() {
        permissionTimeout?.let(mainHandler::removeCallbacks)
        permissionTimeout = null
        pendingDeviceId = null
    }

    private fun openSerial(device: UsbDevice) {
        cancelHello()
        cancelRawCapture()
        rawCaptureState = RawCaptureState()
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
                    esp32State = Esp32HandshakeState()
                    connectedBaudRate = transport.configuredBaudRate ?: UsbSerialTransport.BAUD_RATE
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
        cancelHello()
        cancelRawCapture()
        val thisOperation = ++operationId
        esp32State = Esp32HandshakeState()
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

    private fun testEsp32() {
        if (connectionState.phase != SerialConnectionPhase.CONNECTED ||
            esp32State.phase == Esp32HandshakePhase.TESTING ||
            rawCaptureState.phase == RawCapturePhase.CAPTURING) return
        val thisOperation = operationId
        val thisHello = ++helloOperationId
        val started = System.nanoTime()
        val finished = AtomicBoolean(false)
        esp32State = Esp32HandshakeState(Esp32HandshakePhase.TESTING)
        val task = helloWorker.submit {
            val nextState = try {
                val client = Esp32Client(transport, diagnostics = { Log.d(ESP32_LOG_TAG, it) })
                Esp32HandshakeState(Esp32HandshakePhase.VERIFIED, info = client.hello())
            } catch (error: Exception) {
                Esp32HandshakeState(
                    Esp32HandshakePhase.FAILED,
                    info = (error as? Esp32IncompatibleProtocolException)?.info,
                    detail = error.message ?: "ESP32 HELLO failed",
                )
            }
            if (!finished.compareAndSet(false, true)) return@submit
            runOnUiThread {
                if (thisHello != helloOperationId || thisOperation != operationId ||
                    connectionState.phase != SerialConnectionPhase.CONNECTED) {
                    return@runOnUiThread
                }
                helloTimeout?.cancel(false)
                helloTimeout = null
                helloFuture = null
                esp32State = nextState
            }
        }
        helloFuture = task
        helloTimeout = helloWatchdog.schedule({
            if (!finished.compareAndSet(false, true)) return@schedule
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            Log.e(ESP32_LOG_TAG, "HELLO hard timeout after $elapsedMs ms; closing serial transport")
            task.cancel(true)
            serialWorker.execute { transport.disconnectSafely() }
            runOnUiThread {
                if (thisHello != helloOperationId || thisOperation != operationId) return@runOnUiThread
                helloOperationId++
                operationId++
                helloFuture = null
                helloTimeout = null
                esp32State = Esp32HandshakeState(
                    Esp32HandshakePhase.FAILED,
                    detail = "ESP32 HELLO exceeded ${HELLO_OPERATION_TIMEOUT_MS / 1_000} seconds.",
                )
                connectionState = SerialConnectionState(
                    connectionState.deviceName,
                    SerialConnectionPhase.CONNECTION_FAILED,
                    "ESP32 HELLO timed out. The serial connection was closed; reconnect to retry.",
                )
            }
        }, HELLO_OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun cancelHello() {
        helloOperationId++
        helloTimeout?.cancel(false)
        helloTimeout = null
        helloFuture?.cancel(true)
        helloFuture = null
    }

    private fun captureRawSerial(baudRate: Int) {
        if (connectionState.phase != SerialConnectionPhase.CONNECTED ||
            esp32State.phase == Esp32HandshakePhase.TESTING ||
            rawCaptureState.phase == RawCapturePhase.CAPTURING) return
        val name = connectionState.deviceName ?: return
        val device = usbManager.deviceList[name] ?: run {
            scanDevices()
            return
        }
        val thisOperation = operationId
        val thisCapture = ++rawCaptureOperationId
        val finished = AtomicBoolean(false)
        rawCaptureState = RawCaptureState(RawCapturePhase.CAPTURING, detail = "Reopening at $baudRate baud")
        val task = helloWorker.submit {
            var reopened = false
            val nextState = try {
                transport.disconnectSafely()
                transport.connect(device, baudRate)
                reopened = true
                val configuredBaud = transport.configuredBaudRate
                    ?: throw IllegalStateException("Serial driver did not report a configured baud rate")
                val capture = RawSerialCapture(transport, diagnostics = { Log.d(ESP32_LOG_TAG, it) })
                RawCaptureState(RawCapturePhase.COMPLETE, result = capture.capture(configuredBaud))
            } catch (error: Exception) {
                RawCaptureState(
                    RawCapturePhase.FAILED,
                    detail = error.message ?: "Raw serial capture failed",
                )
            }
            if (!finished.compareAndSet(false, true)) {
                transport.disconnectSafely()
                return@submit
            }
            runOnUiThread {
                if (thisCapture != rawCaptureOperationId || thisOperation != operationId ||
                    connectionState.phase != SerialConnectionPhase.CONNECTED) return@runOnUiThread
                rawCaptureTimeout?.cancel(false)
                rawCaptureTimeout = null
                rawCaptureFuture = null
                rawCaptureState = nextState
                if (nextState.phase == RawCapturePhase.COMPLETE) {
                    connectedBaudRate = nextState.result?.baudRate ?: baudRate
                } else if (!reopened) {
                    operationId++
                    connectionState = SerialConnectionState(
                        name,
                        SerialConnectionPhase.CONNECTION_FAILED,
                        "Could not reopen serial at $baudRate baud. Reconnect to retry.",
                    )
                }
            }
        }
        rawCaptureFuture = task
        rawCaptureTimeout = helloWatchdog.schedule({
            if (!finished.compareAndSet(false, true)) return@schedule
            Log.e(ESP32_LOG_TAG, "Raw capture hard timeout; closing serial transport")
            task.cancel(true)
            serialWorker.execute { transport.disconnectSafely() }
            runOnUiThread {
                if (thisCapture != rawCaptureOperationId || thisOperation != operationId) return@runOnUiThread
                rawCaptureOperationId++
                operationId++
                rawCaptureFuture = null
                rawCaptureTimeout = null
                rawCaptureState = RawCaptureState(
                    RawCapturePhase.FAILED,
                    detail = "Raw capture exceeded ${RAW_CAPTURE_OPERATION_TIMEOUT_MS / 1_000} seconds.",
                )
                connectionState = SerialConnectionState(
                    connectionState.deviceName,
                    SerialConnectionPhase.CONNECTION_FAILED,
                    "Raw serial capture timed out. The serial connection was closed; reconnect to retry.",
                )
            }
        }, RAW_CAPTURE_OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun cancelRawCapture() {
        rawCaptureOperationId++
        rawCaptureTimeout?.cancel(false)
        rawCaptureTimeout = null
        rawCaptureFuture?.cancel(true)
        rawCaptureFuture = null
        if (rawCaptureState.phase == RawCapturePhase.CAPTURING) {
            rawCaptureState = RawCaptureState(RawCapturePhase.FAILED, detail = "Raw capture canceled.")
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else getParcelableExtra(UsbManager.EXTRA_DEVICE)

    companion object {
        private const val ACTION_USB_PERMISSION = "com.dedhapp3n.pokeldn.USB_PERMISSION"
        private const val PERMISSION_TIMEOUT_MS = 30_000L
        private const val HELLO_OPERATION_TIMEOUT_MS = 10_000L
        private const val RAW_CAPTURE_OPERATION_TIMEOUT_MS = 5_000L
        private const val ESP32_LOG_TAG = "PokeLDN-ESP32"
    }
}

private fun UsbSerialTransport.disconnectSafely() {
    try { disconnect() } catch (_: Exception) { /* A detached device may already be closed. */ }
}

private val DIAGNOSTIC_BAUD_RATES = listOf(115200, 230400, 460800, 921600)

@Composable
private fun UsbDevicesScreen(
    result: UsbScanResult?,
    connectionState: SerialConnectionState,
    esp32State: Esp32HandshakeState,
    rawCaptureState: RawCaptureState,
    connectedBaudRate: Int,
    selectedDiagnosticBaud: Int,
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onTestEsp32: () -> Unit,
    onDiagnosticBaudSelected: (Int) -> Unit,
    onCaptureRaw: (Int) -> Unit,
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
                        esp32State = if (connectionState.deviceName == device.deviceName) esp32State
                            else Esp32HandshakeState(),
                        rawCaptureState = if (connectionState.deviceName == device.deviceName) rawCaptureState
                            else RawCaptureState(),
                        connectedBaudRate = if (connectionState.deviceName == device.deviceName &&
                            connectionState.phase == SerialConnectionPhase.CONNECTED) connectedBaudRate
                            else UsbSerialTransport.BAUD_RATE,
                        selectedDiagnosticBaud = selectedDiagnosticBaud,
                        onConnect = { onConnect(device.deviceName) },
                        onDisconnect = onDisconnect,
                        onTestEsp32 = onTestEsp32,
                        onDiagnosticBaudSelected = onDiagnosticBaudSelected,
                        onCaptureRaw = onCaptureRaw,
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
    esp32State: Esp32HandshakeState,
    rawCaptureState: RawCaptureState,
    connectedBaudRate: Int,
    selectedDiagnosticBaud: Int,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onTestEsp32: () -> Unit,
    onDiagnosticBaudSelected: (Int) -> Unit,
    onCaptureRaw: (Int) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
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
                Text("Serial settings: $connectedBaudRate baud, 8N1, no flow control")
                if (state.phase == SerialConnectionPhase.CONNECTED) {
                    Button(
                        onClick = onDisconnect,
                        enabled = esp32State.phase != Esp32HandshakePhase.TESTING &&
                            rawCaptureState.phase != RawCapturePhase.CAPTURING,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Disconnect") }
                    Text("ESP32 protocol", style = MaterialTheme.typography.titleMedium)
                    Text("Handshake: ${esp32State.phase.label}")
                    esp32State.detail?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    esp32State.info?.let { info ->
                        Text("Protocol version: ${info.protocolVersion}")
                        Text("Firmware: ${info.firmwareText}")
                        if (info.firmwareVersion.isNotEmpty()) Text("Firmware version: ${info.firmwareVersion}")
                        Text("Chip revision: ${info.chipRevision}")
                        Text("Station MAC: ${info.stationMac.formatMac()}")
                        Text("Access point MAC: ${info.accessPointMac.formatMac()}")
                    }
                    Button(
                        onClick = onTestEsp32,
                        enabled = esp32State.phase != Esp32HandshakePhase.TESTING &&
                            rawCaptureState.phase != RawCapturePhase.CAPTURING,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("ESP32 HELLO") }
                    Text("Raw Serial Diagnostics", style = MaterialTheme.typography.titleMedium)
                    Text("Passive capture baud (no protocol data is sent)")
                    DIAGNOSTIC_BAUD_RATES.forEach { baud ->
                        val selectBaud = { onDiagnosticBaudSelected(baud) }
                        if (baud == selectedDiagnosticBaud) {
                            Button(
                                onClick = selectBaud,
                                enabled = rawCaptureState.phase != RawCapturePhase.CAPTURING,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("$baud baud (selected)") }
                        } else {
                            OutlinedButton(
                                onClick = selectBaud,
                                enabled = rawCaptureState.phase != RawCapturePhase.CAPTURING,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("$baud baud") }
                        }
                    }
                    Button(
                        onClick = { onCaptureRaw(selectedDiagnosticBaud) },
                        enabled = esp32State.phase != Esp32HandshakePhase.TESTING &&
                            rawCaptureState.phase != RawCapturePhase.CAPTURING,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Capture Passive") }
                } else {
                    if (esp32State.phase == Esp32HandshakePhase.FAILED) {
                        Text("Handshake: ${esp32State.phase.label}")
                        esp32State.detail?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    Button(
                        onClick = onConnect,
                        enabled = canConnect && state.phase != SerialConnectionPhase.REQUESTING_PERMISSION &&
                            state.phase != SerialConnectionPhase.CONNECTING,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Connect Serial") }
                }
                when (rawCaptureState.phase) {
                    RawCapturePhase.IDLE -> Unit
                    RawCapturePhase.CAPTURING -> Text("Raw capture: ${rawCaptureState.detail ?: "capturing"}")
                    RawCapturePhase.FAILED -> Text(
                        "Raw capture failed: ${rawCaptureState.detail ?: "unknown error"}",
                        color = MaterialTheme.colorScheme.error,
                    )
                    RawCapturePhase.COMPLETE -> rawCaptureState.result?.let { capture ->
                        Text("Raw capture result (long press to select and copy)")
                        Button(
                            onClick = { clipboard.setText(AnnotatedString(capture.displayText())) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Copy Raw Capture") }
                        SelectionContainer {
                            Text(capture.displayText(), fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            } else {
                Text("No supported serial driver for this device")
            }
        }
    }
}
