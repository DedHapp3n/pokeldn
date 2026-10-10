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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.dedhapp3n.pokeldn.esp32.Esp32HandshakePhase
import com.dedhapp3n.pokeldn.esp32.Esp32HandshakeState
import com.dedhapp3n.pokeldn.esp32.Esp32IncompatibleProtocolException
import com.dedhapp3n.pokeldn.esp32.Esp32RadioOperation
import com.dedhapp3n.pokeldn.esp32.Esp32RadioPhase
import com.dedhapp3n.pokeldn.esp32.Esp32RadioSession
import com.dedhapp3n.pokeldn.esp32.RawCapturePhase
import com.dedhapp3n.pokeldn.esp32.RawCaptureState
import com.dedhapp3n.pokeldn.esp32.RawReadBufferMode
import com.dedhapp3n.pokeldn.esp32.RawSerialCapture
import com.dedhapp3n.pokeldn.frlg.ProdKeysState
import com.dedhapp3n.pokeldn.frlg.ProdKeysStore
import com.dedhapp3n.pokeldn.frlg.ProdKeysPhase
import com.dedhapp3n.pokeldn.ldn.LdnAdvertisementBuilder
import com.dedhapp3n.pokeldn.ldn.LdnApTestPhase
import com.dedhapp3n.pokeldn.ldn.LdnApTestState
import com.dedhapp3n.pokeldn.ldn.LdnDiscoveryController
import com.dedhapp3n.pokeldn.ldn.LdnDiscoverySnapshot
import com.dedhapp3n.pokeldn.ldn.LdnPiaStage
import com.dedhapp3n.pokeldn.ldn.toEsp32AccessPointConfig
import com.dedhapp3n.pokeldn.ui.PokeLdnApp
import com.dedhapp3n.pokeldn.ui.theme.PokeLDNTheme
import com.dedhapp3n.pokeldn.usb.UsbDeviceScanner
import com.dedhapp3n.pokeldn.usb.UsbScanResult
import com.dedhapp3n.pokeldn.usb.SerialConnectionPhase
import com.dedhapp3n.pokeldn.usb.SerialConnectionState
import com.dedhapp3n.pokeldn.usb.UsbSerialTransport
import com.dedhapp3n.pokeldn.usb.devicePhase
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private val usbManager by lazy { getSystemService(UsbManager::class.java) }
    private val scanner by lazy { UsbDeviceScanner(this) }
    private val transport by lazy { UsbSerialTransport(usbManager) }
    private val prodKeysStore by lazy { ProdKeysStore(this) }
    private val serialWorker = Executors.newSingleThreadExecutor()
    private val helloWorker = Executors.newCachedThreadPool()
    private val ldnOperationWorker = Executors.newSingleThreadExecutor()
    private val helloWatchdog = Executors.newSingleThreadScheduledExecutor()
    private var scanResult by mutableStateOf<UsbScanResult?>(null)
    private var connectionState by mutableStateOf(SerialConnectionState())
    private var esp32State by mutableStateOf(Esp32HandshakeState())
    private var rawCaptureState by mutableStateOf(RawCaptureState())
    private var connectedBaudRate by mutableIntStateOf(UsbSerialTransport.BAUD_RATE)
    private var selectedDiagnosticBaud by mutableIntStateOf(UsbSerialTransport.BAUD_RATE)
    private var selectedReadBufferMode by mutableStateOf(RawReadBufferMode.REUSED)
    private var prodKeysState by mutableStateOf(ProdKeysState())
    private var ldnApTestState by mutableStateOf(LdnApTestState())
    private var pendingDeviceId: Int? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var permissionTimeout: Runnable? = null
    private var verifyAfterSerialConnect = false
    private var operationId = 0
    private var helloOperationId = 0
    private var helloFuture: Future<*>? = null
    private var helloTimeout: Future<*>? = null
    private var rawCaptureOperationId = 0
    private var rawCaptureFuture: Future<*>? = null
    private var rawCaptureTimeout: Future<*>? = null
    private val radioSessionLock = Any()
    @Volatile private var radioSession: Esp32RadioSession? = null
    private val ldnTestLock = Any()
    @Volatile private var activeLdnTest: ActiveLdnTest? = null
    @Volatile private var ldnTestId = 0

    private data class ActiveLdnTest(
        val session: Esp32RadioSession,
        val operation: Esp32RadioOperation,
        val discovery: LdnDiscoveryController,
    )

    private val prodKeysPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        helloWorker.submit {
            val next = try {
                contentResolver.openInputStream(uri)?.let(prodKeysStore::importKeys)
                    ?: ProdKeysState(ProdKeysPhase.INVALID, "Could not open the selected file")
            } catch (error: Exception) {
                ProdKeysState(ProdKeysPhase.INVALID, error.message ?: "Could not import prod.keys")
            }
            runOnUiThread { prodKeysState = next }
        }
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) handlePermissionResult(intent)
        }
    }

    private val usbDeviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                if (connectionState.deviceName == device.deviceName) {
                    verifyAfterSerialConnect = false
                    clearPendingPermission()
                    cancelHello()
                    cancelRawCapture()
                    operationId++
                    esp32State = Esp32HandshakeState()
                    invalidateLdnApTest()
                    connectionState = SerialConnectionState(device.deviceName, SerialConnectionPhase.DISCONNECTED)
                    serialWorker.execute { closeRadioAndTransport() }
                }
            }
            scanDevices()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prodKeysState = prodKeysStore.state()
        enableEdgeToEdge()
        ContextCompat.registerReceiver(
            this, permissionReceiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this, usbDeviceReceiver, IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        setContent {
            PokeLDNTheme {
                PokeLdnApp(
                    result = scanResult,
                    connectionState = connectionState,
                    esp32State = esp32State,
                    rawCaptureState = rawCaptureState,
                    connectedBaudRate = connectedBaudRate,
                    selectedDiagnosticBaud = selectedDiagnosticBaud,
                    selectedReadBufferMode = selectedReadBufferMode,
                    onScan = ::scanDevices,
                    onConnectEsp32 = ::connectAndVerifyEsp32,
                    onConnect = ::connectSerial,
                    onDisconnect = ::disconnectSerial,
                    onTestEsp32 = ::testEsp32,
                    onDiagnosticBaudSelected = { selectedDiagnosticBaud = it },
                    onReadBufferModeSelected = { selectedReadBufferMode = it },
                    onCaptureRaw = ::captureRawSerial,
                    prodKeysState = prodKeysState,
                    onImportProdKeys = { prodKeysPicker.launch(arrayOf("text/plain", "application/octet-stream")) },
                    ldnApTestState = ldnApTestState,
                    onStartLdnApTest = ::startLdnApTest,
                    onStopLdnApTest = ::stopLdnApTest,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        scanDevices()
    }

    override fun onDestroy() {
        unregisterReceiver(permissionReceiver)
        unregisterReceiver(usbDeviceReceiver)
        clearPendingPermission()
        cancelHello()
        cancelRawCapture()
        invalidateLdnApTest()
        operationId++
        serialWorker.execute { closeRadioAndTransport() }
        serialWorker.shutdown()
        helloWorker.shutdownNow()
        ldnOperationWorker.shutdownNow()
        helloWatchdog.shutdownNow()
        super.onDestroy()
    }

    private fun scanDevices() {
        scanResult = scanner.scan()
        val name = connectionState.deviceName ?: return
        val device = (scanResult as? UsbScanResult.Devices)?.items?.firstOrNull { it.deviceName == name }
        if (device == null && connectionState.phase != SerialConnectionPhase.DISCONNECTED) {
            verifyAfterSerialConnect = false
            clearPendingPermission()
            cancelHello()
            cancelRawCapture()
            operationId++
            esp32State = Esp32HandshakeState()
            invalidateLdnApTest()
            connectionState = SerialConnectionState(name, SerialConnectionPhase.DISCONNECTED)
            serialWorker.execute { closeRadioAndTransport() }
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

    private fun connectAndVerifyEsp32(name: String) {
        if (connectionState.deviceName == name &&
            connectionState.phase == SerialConnectionPhase.CONNECTED) {
            testEsp32()
            return
        }
        val visible = (scanResult as? UsbScanResult.Devices)?.items
            ?.firstOrNull { it.deviceName == name && it.serialSupported }
            ?: return
        if (connectionState.phase in setOf(
                SerialConnectionPhase.REQUESTING_PERMISSION,
                SerialConnectionPhase.CONNECTING,
                SerialConnectionPhase.DISCONNECTING,
            )) return
        verifyAfterSerialConnect = true
        connectSerial(visible.deviceName)
    }

    private fun connectSerial(name: String) {
        val visible = (scanResult as? UsbScanResult.Devices)?.items?.firstOrNull { it.deviceName == name }
            ?: run {
                verifyAfterSerialConnect = false
                return
            }
        if (!visible.serialSupported || connectionState.phase in setOf(
                SerialConnectionPhase.REQUESTING_PERMISSION, SerialConnectionPhase.CONNECTING,
                SerialConnectionPhase.CONNECTED, SerialConnectionPhase.DISCONNECTING,
            )) {
            verifyAfterSerialConnect = false
            return
        }

        val device = usbManager.deviceList[name] ?: run {
            verifyAfterSerialConnect = false
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
                verifyAfterSerialConnect = false
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
            verifyAfterSerialConnect = false
            connectionState = SerialConnectionState(name, SerialConnectionPhase.DISCONNECTED)
        } else if (usbManager.hasPermission(device)) {
            connectionState = SerialConnectionState(name, SerialConnectionPhase.READY)
            openSerial(device)
        } else if (intent.hasExtra(UsbManager.EXTRA_PERMISSION_GRANTED) &&
            !intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            verifyAfterSerialConnect = false
            connectionState = SerialConnectionState(name, SerialConnectionPhase.PERMISSION_DENIED)
        } else {
            verifyAfterSerialConnect = false
            connectionState = SerialConnectionState(name, SerialConnectionPhase.CONNECTION_FAILED,
                "USB permission response was incomplete. Try connecting again.")
        }
        scanResult = scanner.scan()
    }

    private fun schedulePermissionTimeout(device: UsbDevice) {
        val timeout = Runnable {
            if (pendingDeviceId != device.deviceId || connectionState.deviceName != device.deviceName) return@Runnable
            clearPendingPermission()
            val current = usbManager.deviceList[device.deviceName]
            if (current == null || current.deviceId != device.deviceId) {
                verifyAfterSerialConnect = false
                connectionState = SerialConnectionState(device.deviceName, SerialConnectionPhase.DISCONNECTED)
            } else if (usbManager.hasPermission(current)) {
                connectionState = SerialConnectionState(device.deviceName, SerialConnectionPhase.READY)
                openSerial(current)
            } else {
                verifyAfterSerialConnect = false
                connectionState = SerialConnectionState(device.deviceName, SerialConnectionPhase.CONNECTION_FAILED,
                    "USB permission response timed out. Try connecting again.")
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
                if (error == null && usbManager.deviceList.containsKey(device.deviceName)) {
                    esp32State = Esp32HandshakeState()
                    invalidateLdnApTest()
                    connectedBaudRate = transport.configuredBaudRate ?: UsbSerialTransport.BAUD_RATE
                    connectionState = SerialConnectionState(device.deviceName, SerialConnectionPhase.CONNECTED)
                    val shouldVerify = verifyAfterSerialConnect
                    verifyAfterSerialConnect = false
                    if (shouldVerify) testEsp32()
                } else if (!usbManager.deviceList.containsKey(device.deviceName)) {
                    verifyAfterSerialConnect = false
                    serialWorker.execute { closeRadioAndTransport() }
                    connectionState = SerialConnectionState(device.deviceName, SerialConnectionPhase.DISCONNECTED)
                } else {
                    verifyAfterSerialConnect = false
                    connectionState = SerialConnectionState(
                        device.deviceName, SerialConnectionPhase.CONNECTION_FAILED, error?.message,
                    )
                }
            }
        }
    }

    private fun disconnectSerial() {
        val name = connectionState.deviceName ?: return
        verifyAfterSerialConnect = false
        cancelHello()
        cancelRawCapture()
        val thisOperation = ++operationId
        esp32State = Esp32HandshakeState()
        invalidateLdnApTest()
        connectionState = SerialConnectionState(name, SerialConnectionPhase.DISCONNECTING)
        serialWorker.execute {
            closeRadioAndTransport()
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
            (esp32State.phase == Esp32HandshakePhase.VERIFIED && radioSession != null) ||
            rawCaptureState.phase == RawCapturePhase.CAPTURING) return
        val thisOperation = operationId
        val thisHello = ++helloOperationId
        val started = System.nanoTime()
        val finished = AtomicBoolean(false)
        esp32State = Esp32HandshakeState(Esp32HandshakePhase.TESTING)
        val task = helloWorker.submit {
            closeRadioSession()
            val session = Esp32RadioSession(transport, diagnostics = { Log.d(ESP32_LOG_TAG, it) })
            installRadioSession(session)
            val nextState = try {
                val info = session.verifyAndPrepare()
                Esp32HandshakeState(Esp32HandshakePhase.VERIFIED, info = info)
            } catch (error: Exception) {
                closeRadioSession(session)
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
                if (nextState.phase == Esp32HandshakePhase.VERIFIED) {
                    connectedBaudRate = transport.configuredBaudRate ?: UsbSerialTransport.BAUD_RATE
                    ldnApTestState = LdnApTestState(LdnApTestPhase.READY)
                } else {
                    ldnApTestState = LdnApTestState(LdnApTestPhase.DISCONNECTED)
                }
                esp32State = nextState
            }
        }
        helloFuture = task
        helloTimeout = helloWatchdog.schedule({
            if (!finished.compareAndSet(false, true)) return@schedule
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            Log.e(ESP32_LOG_TAG, "HELLO hard timeout after $elapsedMs ms; closing serial transport")
            task.cancel(true)
            serialWorker.execute { closeRadioAndTransport() }
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
                invalidateLdnApTest()
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

    private fun captureRawSerial(baudRate: Int, bufferMode: RawReadBufferMode) {
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
        rawCaptureState = RawCaptureState(
            RawCapturePhase.CAPTURING,
            detail = "Reopening at $baudRate baud; ${bufferMode.label}",
        )
        esp32State = Esp32HandshakeState()
        invalidateLdnApTest()
        val task = helloWorker.submit {
            var reopened = false
            val nextState = try {
                closeRadioAndTransport()
                transport.connect(device, baudRate)
                reopened = true
                val configuredBaud = transport.configuredBaudRate
                    ?: throw IllegalStateException("Serial driver did not report a configured baud rate")
                val usbDiagnostic = transport.openDiagnostic
                val capture = RawSerialCapture(transport, diagnostics = { Log.d(ESP32_LOG_TAG, it) })
                RawCaptureState(
                    RawCapturePhase.COMPLETE,
                    result = capture.capture(configuredBaud, bufferMode, usbDiagnostic),
                )
            } catch (error: Exception) {
                RawCaptureState(
                    RawCapturePhase.FAILED,
                    detail = error.message ?: "Raw serial capture failed",
                )
            }
            if (!finished.compareAndSet(false, true)) {
                closeRadioAndTransport()
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
            serialWorker.execute { closeRadioAndTransport() }
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

    private fun startLdnApTest() {
        if (prodKeysState.phase != ProdKeysPhase.AVAILABLE) {
            ldnApTestState = LdnApTestState(LdnApTestPhase.FAILED, "Valid prod.keys are required")
            return
        }
        val session = radioSession
        if (esp32State.phase != Esp32HandshakePhase.VERIFIED ||
            session?.state?.phase != Esp32RadioPhase.READY) {
            ldnApTestState = LdnApTestState(LdnApTestPhase.FAILED, "ESP32 radio is not Ready")
            return
        }
        if (ldnApTestState.phase in setOf(
                LdnApTestPhase.PREPARING,
                LdnApTestPhase.STARTING_AP,
                LdnApTestPhase.ADVERTISING,
                LdnApTestPhase.CONSOLE_ACTIVITY,
                LdnApTestPhase.AUTH_REQUEST,
                LdnApTestPhase.AUTH_RESPONSE_SENT,
                LdnApTestPhase.AUTHENTICATION_REJECTED,
                LdnApTestPhase.PARTICIPANT_REGISTERED,
                LdnApTestPhase.STATION_ASSOCIATED,
                LdnApTestPhase.STOPPING,
            )) return
        val testId = synchronized(ldnTestLock) {
            activeLdnTest = null
            ++ldnTestId
        }
        ldnApTestState = LdnApTestState(LdnApTestPhase.PREPARING)
        ldnOperationWorker.execute {
            var operation: Esp32RadioOperation? = null
            var discovery: LdnDiscoveryController? = null
            try {
                val keys = prodKeysStore.loadLdnKeys()
                val network = LdnAdvertisementBuilder().buildDiscoveryNetwork(keys)
                val config = network.accessPoint.toEsp32AccessPointConfig()
                runOnUiThread {
                    if (testId == ldnTestId) {
                        ldnApTestState = LdnApTestState(
                            LdnApTestPhase.STARTING_AP,
                            channel = config.channel,
                        )
                    }
                }
                val activeOperation = session.startAccessPoint(config)
                operation = activeOperation
                lateinit var controller: LdnDiscoveryController
                controller = LdnDiscoveryController(
                    session = session,
                    operation = activeOperation,
                    network = network,
                    onSnapshot = { snapshot ->
                        runOnUiThread {
                            val current = synchronized(ldnTestLock) {
                                testId == ldnTestId && activeLdnTest?.discovery === controller
                            }
                            if (current) {
                                ldnApTestState = discoveryState(config.channel, snapshot)
                            }
                        }
                    },
                    onFailure = { error ->
                        handleLdnDiscoveryFailure(testId, session, activeOperation, controller, error)
                    },
                )
                discovery = controller
                val accepted = synchronized(ldnTestLock) {
                    if (testId == ldnTestId && radioSession === session) {
                        activeLdnTest = ActiveLdnTest(session, activeOperation, controller)
                        true
                    } else false
                }
                if (!accepted) {
                    controller.stop()
                    try { session.stopAccessPoint(activeOperation) } catch (_: Exception) { /* Session is already closing. */ }
                    return@execute
                }
                val initial = controller.start()
                runOnUiThread {
                    if (testId == ldnTestId) ldnApTestState = discoveryState(config.channel, initial)
                }
            } catch (error: Exception) {
                discovery?.stop()
                operation?.let {
                    if (session.state.phase in setOf(Esp32RadioPhase.STARTING_RADIO, Esp32RadioPhase.RADIO_ACTIVE)) {
                        try { session.stopAccessPoint(it) } catch (_: Exception) { /* Reported by session state below. */ }
                    }
                }
                synchronized(ldnTestLock) {
                    if (activeLdnTest?.discovery === discovery) activeLdnTest = null
                }
                runOnUiThread {
                    if (testId != ldnTestId) return@runOnUiThread
                    val cleanupRequired = session.state.phase == Esp32RadioPhase.CLEANUP_REQUIRED
                    ldnApTestState = LdnApTestState(
                        if (cleanupRequired) LdnApTestPhase.CLEANUP_REQUIRED else LdnApTestPhase.FAILED,
                        error.message ?: "LDN AP start failed",
                    )
                }
            }
        }
    }

    private fun stopLdnApTest() {
        val active: ActiveLdnTest
        val testId = synchronized(ldnTestLock) {
            active = activeLdnTest ?: return
            activeLdnTest = null
            ++ldnTestId
        }
        ldnApTestState = LdnApTestState(LdnApTestPhase.STOPPING)
        ldnOperationWorker.execute {
            val error = try {
                active.discovery.stop()
                active.session.stopAccessPoint(active.operation)
                null
            } catch (failure: Exception) {
                failure
            }
            runOnUiThread {
                if (testId != ldnTestId) return@runOnUiThread
                if (error == null) {
                    ldnApTestState = LdnApTestState(
                        LdnApTestPhase.READY,
                        "STOP succeeded and STATUS confirmed mode=0",
                    )
                } else {
                    val cleanupRequired = active.session.state.phase == Esp32RadioPhase.CLEANUP_REQUIRED
                    ldnApTestState = LdnApTestState(
                        if (cleanupRequired) LdnApTestPhase.CLEANUP_REQUIRED else LdnApTestPhase.FAILED,
                        error.message ?: "LDN AP stop failed",
                    )
                }
            }
        }
    }

    private fun invalidateLdnApTest() {
        val active = synchronized(ldnTestLock) {
            ldnTestId++
            activeLdnTest.also { activeLdnTest = null }
        }
        active?.discovery?.stop()
        ldnApTestState = LdnApTestState(LdnApTestPhase.DISCONNECTED)
    }

    private fun discoveryState(channel: Int, snapshot: LdnDiscoverySnapshot): LdnApTestState =
        LdnApTestState(
            phase = when {
                snapshot.piaStage == LdnPiaStage.ESTABLISHED -> LdnApTestPhase.PIA_ESTABLISHED
                snapshot.piaStage == LdnPiaStage.FAILED -> LdnApTestPhase.PIA_FAILED
                snapshot.piaStage == LdnPiaStage.SESSION_RESPONSE_SENT -> LdnApTestPhase.PIA_RESPONSE_SENT
                snapshot.piaStage == LdnPiaStage.SESSION_JOIN_RECEIVED -> LdnApTestPhase.PIA_REQUEST
                snapshot.stationDetected -> LdnApTestPhase.STATION_ASSOCIATED
                snapshot.participantRegistered -> LdnApTestPhase.PARTICIPANT_REGISTERED
                snapshot.authenticationFailures > 0 -> LdnApTestPhase.AUTHENTICATION_REJECTED
                snapshot.authenticationResponses > 0 -> LdnApTestPhase.AUTH_RESPONSE_SENT
                snapshot.authenticationRequests > 0 -> LdnApTestPhase.AUTH_REQUEST
                snapshot.discoveryActivityCount > 0 -> LdnApTestPhase.CONSOLE_ACTIVITY
                else -> LdnApTestPhase.ADVERTISING
            },
            detail = snapshot.latestActivity ?: "Waiting for console discovery activity",
            channel = channel,
            advertisementsSent = snapshot.advertisementsSent,
            discoveryActivityCount = snapshot.discoveryActivityCount,
            stationDetected = snapshot.stationDetected,
            authenticationRequests = snapshot.authenticationRequests,
            authenticationResponses = snapshot.authenticationResponses,
            authenticationFailures = snapshot.authenticationFailures,
            participantRegistered = snapshot.participantRegistered,
            piaNetRequests = snapshot.piaNetRequests,
            piaSessionRequests = snapshot.piaSessionRequests,
            piaSessionResponses = snapshot.piaSessionResponses,
            piaEstablished = snapshot.piaStage == LdnPiaStage.ESTABLISHED,
            latestActivity = snapshot.latestActivity,
        )

    private fun handleLdnDiscoveryFailure(
        testId: Int,
        session: Esp32RadioSession,
        operation: Esp32RadioOperation,
        discovery: LdnDiscoveryController,
        failure: Throwable,
    ) {
        val accepted = synchronized(ldnTestLock) {
            val active = activeLdnTest
            if (testId == ldnTestId && active?.discovery === discovery) {
                activeLdnTest = null
                true
            } else false
        }
        if (!accepted) return
        runOnUiThread {
            if (testId == ldnTestId) {
                ldnApTestState = LdnApTestState(
                    LdnApTestPhase.STOPPING,
                    "Advertisement failed; returning the adapter to Ready",
                )
            }
        }
        ldnOperationWorker.execute {
            discovery.stop()
            val cleanupError = try {
                session.stopAccessPoint(operation)
                null
            } catch (error: Exception) {
                error
            }
            runOnUiThread {
                if (testId != ldnTestId) return@runOnUiThread
                val cleanupRequired = cleanupError != null ||
                    session.state.phase == Esp32RadioPhase.CLEANUP_REQUIRED
                ldnApTestState = LdnApTestState(
                    if (cleanupRequired) LdnApTestPhase.CLEANUP_REQUIRED else LdnApTestPhase.FAILED,
                    buildString {
                        append(failure.message ?: "LDN advertisement failed")
                        if (cleanupError == null) append("; STOP and STATUS mode=0 succeeded")
                        else append("; cleanup failed: ${cleanupError.message}")
                    },
                )
            }
        }
    }

    private fun installRadioSession(session: Esp32RadioSession) {
        val previous = synchronized(radioSessionLock) {
            val current = radioSession
            radioSession = session
            current
        }
        if (previous !== session) {
            try { previous?.close() } catch (_: Exception) { /* The USB device may already be detached. */ }
        }
    }

    private fun closeRadioSession(expected: Esp32RadioSession? = null) {
        val session = synchronized(radioSessionLock) {
            val current = radioSession
            if (expected != null && current !== expected) null
            else current.also { radioSession = null }
        }
        try { session?.close() } catch (_: Exception) { /* The USB device may already be detached. */ }
    }

    private fun closeRadioAndTransport() {
        closeRadioSession()
        transport.disconnectSafely()
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (android.os.Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else getParcelableExtra(UsbManager.EXTRA_DEVICE)

    companion object {
        private const val ACTION_USB_PERMISSION = "com.dedhapp3n.pokeldn.USB_PERMISSION"
        private const val PERMISSION_TIMEOUT_MS = 30_000L
        private const val HELLO_OPERATION_TIMEOUT_MS = 20_000L
        private const val RAW_CAPTURE_OPERATION_TIMEOUT_MS = 5_000L
        private const val ESP32_LOG_TAG = "PokeLDN-ESP32"
    }
}

private fun UsbSerialTransport.disconnectSafely() {
    try { disconnect() } catch (_: Exception) { /* A detached device may already be closed. */ }
}
