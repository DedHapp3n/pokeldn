package com.dedhapp3n.pokeldn

import android.app.PendingIntent
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
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
import com.dedhapp3n.pokeldn.esp32.Esp32RadioState
import com.dedhapp3n.pokeldn.esp32.RawCapturePhase
import com.dedhapp3n.pokeldn.esp32.RawCaptureState
import com.dedhapp3n.pokeldn.esp32.RawReadBufferMode
import com.dedhapp3n.pokeldn.esp32.RawSerialCapture
import com.dedhapp3n.pokeldn.frlg.ProdKeysState
import com.dedhapp3n.pokeldn.frlg.ProdKeysStore
import com.dedhapp3n.pokeldn.frlg.ProdKeysPhase
import com.dedhapp3n.pokeldn.frlg.FrlgGiftStage
import com.dedhapp3n.pokeldn.frlg.FrlgMysteryGiftOperation
import com.dedhapp3n.pokeldn.frlg.FrlgOperationPhase
import com.dedhapp3n.pokeldn.frlg.FrlgOperationStartGate
import com.dedhapp3n.pokeldn.frlg.FrlgOperationState
import com.dedhapp3n.pokeldn.frlg.FrlgSaveBackupStage
import com.dedhapp3n.pokeldn.frlg.FrlgSaveBackupUiState
import com.dedhapp3n.pokeldn.frlg.FrlgSaveExport
import com.dedhapp3n.pokeldn.frlg.FrlgSaveExportPhase
import com.dedhapp3n.pokeldn.frlg.AndroidFrlgBackupFolder
import com.dedhapp3n.pokeldn.frlg.FrlgBackupStore
import com.dedhapp3n.pokeldn.frlg.FrlgBackupStorageUiState
import com.dedhapp3n.pokeldn.frlg.FrlgCompletedBackup
import com.dedhapp3n.pokeldn.frlg.FrlgSaveCloseResult
import com.dedhapp3n.pokeldn.frlg.FrlgSave
import com.dedhapp3n.pokeldn.frlg.FrlgSaveRestoreStage
import com.dedhapp3n.pokeldn.frlg.FrlgSaveRestoreUiState
import com.dedhapp3n.pokeldn.frlg.FrlgForegroundKind
import com.dedhapp3n.pokeldn.frlg.FrlgForegroundProgress
import com.dedhapp3n.pokeldn.frlg.FrlgOperationService
import com.dedhapp3n.pokeldn.frlg.FrlgRestoreTraceStore
import com.dedhapp3n.pokeldn.frlg.finishAfterPersistence
import com.dedhapp3n.pokeldn.frlg.frlgProtocolDiagnostics
import com.dedhapp3n.pokeldn.ldn.LdnAdvertisementBuilder
import com.dedhapp3n.pokeldn.ldn.LdnApTestPhase
import com.dedhapp3n.pokeldn.ldn.LdnApTestState
import com.dedhapp3n.pokeldn.ldn.LdnDiscoveryController
import com.dedhapp3n.pokeldn.ldn.LdnDiscoverySnapshot
import com.dedhapp3n.pokeldn.ldn.LdnPiaStage
import com.dedhapp3n.pokeldn.ldn.toEsp32AccessPointConfig
import com.dedhapp3n.pokeldn.profile.TrainerProfile
import com.dedhapp3n.pokeldn.profile.TrainerProfileStore
import com.dedhapp3n.pokeldn.ui.PokeLdnApp
import com.dedhapp3n.pokeldn.ui.theme.PokeLDNTheme
import com.dedhapp3n.pokeldn.usb.UsbDeviceScanner
import com.dedhapp3n.pokeldn.usb.UsbScanResult
import com.dedhapp3n.pokeldn.usb.SerialConnectionPhase
import com.dedhapp3n.pokeldn.usb.SerialConnectionState
import com.dedhapp3n.pokeldn.usb.UsbSerialTransport
import com.dedhapp3n.pokeldn.usb.devicePhase
import com.dedhapp3n.pokeldn.za.ZaSnapshot
import com.dedhapp3n.pokeldn.za.ZaSnapshotStore
import com.dedhapp3n.pokeldn.za.ZaReaderState
import com.dedhapp3n.pokeldn.za.ZaReaderService
import com.dedhapp3n.pokeldn.za.ZaReaderController
import com.dedhapp3n.pokeldn.za.ZaLdnNetworkBuilder
import com.dedhapp3n.pokeldn.za.ZaHostSession
import com.dedhapp3n.pokeldn.za.ZaReference
import com.dedhapp3n.pokeldn.za.ZaNames
import com.dedhapp3n.pokeldn.sprites.PokemonSpriteRepository
import com.dedhapp3n.pokeldn.profile.TrainerIdentityStore
import com.dedhapp3n.pokeldn.profile.TrainerIdentitySettings
import com.dedhapp3n.pokeldn.profile.TrainerSource
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.time.Instant
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    private val usbManager by lazy { getSystemService(UsbManager::class.java) }
    private val scanner by lazy { UsbDeviceScanner(this) }
    private lateinit var transport: UsbSerialTransport
    private val prodKeysStore by lazy { ProdKeysStore(this) }
    private val trainerProfileStore by lazy { TrainerProfileStore(this) }
    private val trainerIdentityStore by lazy { TrainerIdentityStore(this) }
    private val zaSnapshotStore by lazy { ZaSnapshotStore(filesDir.resolve("za-snapshots")) }
    private val zaNames by lazy { ZaNames.fromAssets(assets) }
    private val pokemonSprites by lazy { PokemonSpriteRepository(filesDir.resolve("pokemon-sprites")) }
    private val backupFolder by lazy { AndroidFrlgBackupFolder(this) }
    private val backupStore by lazy {
        FrlgBackupStore(filesDir.resolve("frlg-backups"), externalDirectory = backupFolder::externalDirectory)
    }
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
    private var trainerProfile by mutableStateOf(TrainerProfile())
    private var trainerIdentitySettings by mutableStateOf(TrainerIdentitySettings())
    private var ldnApTestState by mutableStateOf(LdnApTestState())
    private var frlgOperationState by mutableStateOf(FrlgOperationState())
    private var saveBackupState by mutableStateOf(FrlgSaveBackupUiState())
    private var saveRestoreState by mutableStateOf(FrlgSaveRestoreUiState())
    private var backupStorageState by mutableStateOf(FrlgBackupStorageUiState())
    private var zaSnapshots by mutableStateOf<List<ZaSnapshot>>(emptyList())
    private var zaReaderState by mutableStateOf<ZaReaderState?>(null)
    private var zaReaderSubscription: AutoCloseable? = null
    private var radioState by mutableStateOf(Esp32RadioState())
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
    private var radioStateSubscription: AutoCloseable? = null
    private var foregroundOperationSubscription: AutoCloseable? = null
    private val ldnTestLock = Any()
    @Volatile private var activeLdnTest: ActiveLdnTest? = null
    @Volatile private var ldnTestId = 0
    private val frlgOperationLock = Any()
    @Volatile private var activeFrlgOperation: ActiveLdnTest? = null
    @Volatile private var frlgOperationId = 0

    private data class ActiveLdnTest(
        val session: Esp32RadioSession,
        val operation: Esp32RadioOperation,
        val discovery: LdnDiscoveryController,
        val saveBackup: Boolean = false,
        val saveRestore: Boolean = false,
    )

    private val saveRestorePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        helloWorker.submit {
            val next = try {
                val original = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalArgumentException("Could not open the selected save")
                val normalized = FrlgSave.normalize(original)
                val summary = FrlgSave.describe(normalized)
                if (!summary.sound) throw FrlgSave.SaveException("The save has no whole valid slot")
                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "selected.sav"
                FrlgSaveRestoreUiState(filename=name,fileSize=original.size,save=normalized,hasWholeSlot=true,
                    validationDetail="Whole save slot verified (${summary.newest?.layouts?.joinToString { it.name.lowercase() }})")
            } catch (error: Exception) {
                FrlgSaveRestoreUiState(validationDetail=error.message ?: "Invalid FRLG save")
            }
            runOnUiThread {
                saveRestoreState = next
                FrlgOperationService.rememberRestoreSelection(next)
            }
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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

    private val saveBackupExporter = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val save = saveBackupState.save
        if (uri == null) {
            saveBackupState = saveBackupState.copy(
                exportPhase = FrlgSaveExportPhase.CANCELLED,
                exportDetail = "Export cancelled; the completed backup remains available",
            )
        } else if (save == null) {
            saveBackupState = saveBackupState.copy(
                exportPhase = FrlgSaveExportPhase.FAILED,
                exportDetail = "The completed backup is no longer available",
            )
        } else {
            saveBackupState = saveBackupState.copy(exportPhase = FrlgSaveExportPhase.EXPORTING, exportDetail = null)
            helloWorker.submit {
                val error = try {
                    contentResolver.openOutputStream(uri, "w")?.use { FrlgSaveExport.write(save, it) }
                        ?: throw IllegalStateException("Could not open the selected document")
                    null
                } catch (failure: Exception) { failure }
                runOnUiThread {
                    saveBackupState = saveBackupState.copy(
                        exportPhase = if (error == null) FrlgSaveExportPhase.EXPORTED else FrlgSaveExportPhase.FAILED,
                        exportDetail = error?.message ?: "Save exported successfully",
                    )
                }
            }
        }
    }

    private val backupFolderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val error = runCatching { backupFolder.remember(uri) }.exceptionOrNull()
            refreshBackupStorageState(error?.message)
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
                    if (FrlgOperationService.isActive()) {
                        FrlgOperationService.finish(FrlgOperationPhase.FAILED, "ESP32 disconnected during the operation")
                    } else invalidateFrlgOperation("ESP32 disconnected before the operation completed")
                    connectionState = SerialConnectionState(device.deviceName, SerialConnectionPhase.DISCONNECTED)
                    if (!FrlgOperationService.isActive()) serialWorker.execute { closeRadioAndTransport() }
                }
            }
            scanDevices()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        transport = ZaReaderService.ownedTransport() ?: FrlgOperationService.ownedTransport() ?: UsbSerialTransport(usbManager)
        FrlgOperationService.restoreSelection()?.let { saveRestoreState = it }
        if (!FrlgOperationService.isActive()) {
            FrlgRestoreTraceStore(filesDir.resolve(FrlgRestoreTraceStore.FILE_NAME)).load()?.let { trace ->
                saveRestoreState = saveRestoreState.copy(
                    operation = saveRestoreState.operation.copy(diagnostics = trace),
                )
            }
        }
        (ZaReaderService.ownedSession() ?: FrlgOperationService.ownedSession())?.let {
            installRadioSession(it)
            connectionState = SerialConnectionState(phase = SerialConnectionPhase.CONNECTED)
            esp32State = Esp32HandshakeState(Esp32HandshakePhase.VERIFIED, detail = "ESP32 operation active")
        }
        foregroundOperationSubscription = FrlgOperationService.subscribe(::onForegroundOperationProgress)
        trainerProfile = trainerProfileStore.load()
        trainerIdentitySettings = trainerIdentityStore.load()
        zaReaderSubscription = ZaReaderService.subscribe { state ->
            runOnUiThread {
                zaReaderState = state
                if (!state.terminal && state.host.participant && trainerIdentitySettings.source == TrainerSource.AUTOMATIC_FROM_GAME) {
                    state.snapshot.trainer?.asTrainerProfile()?.let { profile ->
                        trainerIdentitySettings = trainerIdentityStore.recordDetected("Pokémon Legends: Z-A", profile)
                    }
                }
                zaSnapshots = zaSnapshotStore.list()
            }
        }
        prodKeysState = prodKeysStore.state()
        zaSnapshots = zaSnapshotStore.list()
        restoreLatestBackup()
        refreshBackupStorageState()
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
                    trainerProfile = trainerProfile,
                    onSaveTrainerProfile = { trainerProfile = trainerProfileStore.save(it) },
                    trainerIdentitySettings = trainerIdentitySettings,
                    onTrainerSourceSelected = { trainerIdentitySettings = trainerIdentityStore.setSource(it) },
                    ldnApTestState = ldnApTestState,
                    onStartLdnApTest = ::startLdnApTest,
                    onStopLdnApTest = ::stopLdnApTest,
                    frlgOperationState = frlgOperationState,
                    saveBackupState = saveBackupState,
                    saveRestoreState = saveRestoreState,
                    radioState = radioState,
                    onStartWalkThroughWalls = ::startWalkThroughWalls,
                    onCancelWalkThroughWalls = ::cancelWalkThroughWalls,
                    onStartPokeHaxScreen = ::startPokeHaxScreen,
                    onCancelPokeHaxScreen = ::cancelPokeHaxScreen,
                    onStartSaveBackup = ::startSaveBackup,
                    onCancelSaveBackup = ::cancelSaveBackup,
                    onExportSaveBackup = ::exportSaveBackup,
                    onSelectSaveRestore = { saveRestorePicker.launch(arrayOf("application/octet-stream", "*/*")) },
                    onStartSaveRestore = ::startSaveRestore,
                    onCancelSaveRestore = ::cancelSaveRestore,
                    backupStorageState = backupStorageState,
                    onOpenBackupFolder = { backupFolderPicker.launch(backupFolder.uri()) },
                    onChangeBackupFolder = { backupFolderPicker.launch(null) },
                    zaSnapshots = zaSnapshots,
                    zaNames = zaNames,
                    pokemonSprites = pokemonSprites,
                    zaReaderState = zaReaderState,
                    onStartZaReader = ::startZaReader,
                    onFinishZaReader = ZaReaderService::finish,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        scanDevices()
        zaSnapshots = zaSnapshotStore.list()
    }

    override fun onDestroy() {
        unregisterReceiver(permissionReceiver)
        unregisterReceiver(usbDeviceReceiver)
        clearPendingPermission()
        cancelHello()
        cancelRawCapture()
        invalidateLdnApTest()
        foregroundOperationSubscription?.close()
        foregroundOperationSubscription = null
        zaReaderSubscription?.close()
        zaReaderSubscription = null
        if (!FrlgOperationService.isActive()) invalidateFrlgOperation()
        operationId++
        if (!FrlgOperationService.owns(radioSession) && !ZaReaderService.owns(radioSession)) serialWorker.execute { closeRadioAndTransport() }
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
            if (FrlgOperationService.isActive()) {
                FrlgOperationService.finish(FrlgOperationPhase.FAILED, "ESP32 disconnected during the operation")
            } else invalidateFrlgOperation()
            connectionState = SerialConnectionState(name, SerialConnectionPhase.DISCONNECTED)
            if (!FrlgOperationService.isActive()) serialWorker.execute { closeRadioAndTransport() }
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
                    invalidateFrlgOperation()
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
        invalidateFrlgOperation()
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
                invalidateFrlgOperation()
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
        invalidateFrlgOperation()
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

    private fun startZaReader() {
        val session = radioSession
        if (ZaReaderService.isActive() || FrlgOperationService.isActive() || activeLdnTest != null || activeFrlgOperation != null) return
        if (esp32State.phase != Esp32HandshakePhase.VERIFIED || session?.state?.phase != Esp32RadioPhase.READY) {
            zaReaderState = zaReaderState?.copy(phase = "FAILED", detail = "Connect and verify the ESP32 first", terminal = true)
            return
        }
        val code = SecureRandom().nextInt(100_000_000).toString().padStart(8, '0')
        val snapshot = zaSnapshotStore.start(code)
        val initial = ZaReaderState(code, "PREPARING NETWORK", snapshot)
        zaReaderState = initial
        requestOperationNotificationPermission()
        ZaReaderService.begin(this, transport, session, initial)
        ldnOperationWorker.execute {
            var operation: Esp32RadioOperation? = null
            var attached = false
            try {
                val keys = prodKeysStore.loadLdnKeys()
                val network = ZaLdnNetworkBuilder().build(keys, code)
                val hostName = trainerProfile.name.takeIf { it.isNotBlank() } ?: "POKELDN"
                val identity = ZaReference.namedIdentity(assets.open("za/identity10.bin").use { it.readBytes() }, hostName)
                val selection = assets.open("za/selection.bin").use { it.readBytes() }
                val activeOperation = session.startAccessPoint(network.accessPoint.toEsp32AccessPointConfig())
                operation = activeOperation
                lateinit var controller: ZaReaderController
                val host = ZaHostSession(network.accessPoint.ssid, network.accessPoint.bssid,
                    network.authentication.hostIp, code, identity, selection, { message -> controller.gameMessage(message) })
                controller = ZaReaderController(session, activeOperation, network, host, zaSnapshotStore, snapshot,
                    ZaReaderService::update, { error -> Log.e("PokeLDN-ZA", "Reader failed", error) })
                ZaReaderService.attach(transport, session, activeOperation, controller)
                attached = true
                controller.start()
            } catch (error: Throwable) {
                if (!attached) operation?.let { runCatching { session.stopAccessPoint(it) } }
                val failed = zaSnapshotStore.finish(snapshot)
                ZaReaderService.clearPreparation()
                ZaReaderService.update(initial.copy(phase = "FAILED", snapshot = failed, detail = error.message, terminal = true))
            }
        }
    }

    private fun startLdnApTest() {
        if (ZaReaderService.isActive()) return
        if (activeFrlgOperation != null) return
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

    private fun startWalkThroughWalls() = startFrlgOperation(
        mysteryGiftOperation = FrlgMysteryGiftOperation.WALK_THROUGH_WALLS,
    )

    private fun startPokeHaxScreen() = startFrlgOperation(
        mysteryGiftOperation = FrlgMysteryGiftOperation.POKEHAX_SCREEN,
    )

    private fun startSaveBackup() = startFrlgOperation(saveBackup = true)

    private fun startSaveRestore() {
        val save = saveRestoreState.save
        if (!saveRestoreState.readyFile || save == null) {
            saveRestoreState = saveRestoreState.copy(operation = FrlgOperationState(FrlgOperationPhase.FAILED, "Select a valid FRLG save first"))
            return
        }
        startFrlgOperation(saveBackup = false, restoreSave = save)
    }

    private fun startFrlgOperation(
        saveBackup: Boolean = false,
        restoreSave: ByteArray? = null,
        mysteryGiftOperation: FrlgMysteryGiftOperation? = null,
    ) {
        if (ZaReaderService.isActive()) return
        val saveRestore = restoreSave != null
        require(listOf(mysteryGiftOperation != null, saveBackup, saveRestore).count { it } == 1) {
            "Exactly one FRLG operation must be selected"
        }
        val session = radioSession
        if (session?.state?.phase == Esp32RadioPhase.READY) releaseStaleRadioOwnership(session)
        val availability = FrlgOperationStartGate.evaluate(
            keysAvailable = prodKeysState.phase == ProdKeysPhase.AVAILABLE,
            handshakeVerified = esp32State.phase == Esp32HandshakePhase.VERIFIED,
            radioReady = session?.state?.phase == Esp32RadioPhase.READY,
            frlgOperationOwned = activeFrlgOperation != null,
            otherRadioOperationOwned = activeLdnTest != null,
            radioDetail = session?.state?.let { "ESP32 radio is ${it.phase.name.lowercase().replace('_', ' ')}${it.detail?.let { detail -> ": $detail" } ?: ""}" },
        )
        if (!availability.enabled || session == null) {
            val failed = FrlgOperationState(
                FrlgOperationPhase.FAILED,
                availability.reason ?: "ESP32 radio session is unavailable",
                lastReached = activeOperationState(saveBackup, saveRestore).lastReached,
                diagnostics = activeOperationState(saveBackup, saveRestore).diagnostics,
            )
            setOperationState(saveBackup, saveRestore, failed)
            return
        }
        val jobId = synchronized(frlgOperationLock) { ++frlgOperationId }
        val foregroundKind = when {
            saveRestore -> FrlgForegroundKind.SAVE_RESTORE
            saveBackup -> FrlgForegroundKind.SAVE_BACKUP
            mysteryGiftOperation == FrlgMysteryGiftOperation.POKEHAX_SCREEN -> FrlgForegroundKind.POKEHAX_SCREEN
            else -> FrlgForegroundKind.WALK_THROUGH_WALLS
        }
        requestOperationNotificationPermission()
        FrlgOperationService.begin(
            this,
            FrlgForegroundProgress(foregroundKind, FrlgOperationPhase.PREPARING_ESP32, "Preparing ESP32"),
        )
        if (saveRestore) {
            saveRestoreState = saveRestoreState.copy(operation = FrlgOperationState(FrlgOperationPhase.PREPARING_ESP32), sectorsWritten=0,totalSectors=0,failureMask=null,loadResult=null,cartridge=null)
        } else if (saveBackup) {
            saveBackupState = FrlgSaveBackupUiState(
                operation = FrlgOperationState(FrlgOperationPhase.PREPARING_ESP32),
            )
        } else frlgOperationState = FrlgOperationState(FrlgOperationPhase.PREPARING_ESP32)
        ldnOperationWorker.execute {
            var operation: Esp32RadioOperation? = null
            var discovery: LdnDiscoveryController? = null
            try {
                val network = LdnAdvertisementBuilder().buildDiscoveryNetwork(prodKeysStore.loadLdnKeys())
                val config = network.accessPoint.toEsp32AccessPointConfig()
                runOnUiThread {
                    if (jobId == frlgOperationId) setOperationState(saveBackup, saveRestore,
                        FrlgOperationState(FrlgOperationPhase.STARTING_NETWORK, "Starting Nintendo LDN access point"))
                }
                val activeOperation = session.startAccessPoint(config)
                operation = activeOperation
                lateinit var controller: LdnDiscoveryController
                controller = LdnDiscoveryController(
                    session = session,
                    operation = activeOperation,
                    network = network,
                    mysteryGiftOperation = mysteryGiftOperation,
                    saveBackup = saveBackup,
                    restoreSave = restoreSave,
                    persistSaveBackup = if (saveBackup) ::persistCompletedBackup else { _ -> },
                    onSnapshot = FrlgOperationService::updateFromSnapshot,
                    onFailure = { error ->
                        Log.e(ESP32_LOG_TAG, "FRLG operation failed: ${error::class.java.name}", error)
                        FrlgOperationService.finish(FrlgOperationPhase.FAILED,
                            error.message ?: "FRLG wireless operation failed")
                    },
                )
                discovery = controller
                val accepted = synchronized(frlgOperationLock) {
                    if (jobId == frlgOperationId && radioSession === session) {
                        activeFrlgOperation = ActiveLdnTest(session, activeOperation, controller, saveBackup, saveRestore)
                        true
                    } else false
                }
                if (!accepted) {
                    controller.stop()
                    try { session.stopAccessPoint(activeOperation) } catch (_: Exception) { }
                    return@execute
                }
                FrlgOperationService.adopt(
                    this,
                    FrlgOperationService.OwnedOperation(
                        transport, session, activeOperation, controller, foregroundKind, connectionState.deviceName,
                    ),
                )
                controller.start()
                runOnUiThread {
                    if (jobId == frlgOperationId) setOperationState(saveBackup, saveRestore, FrlgOperationState(
                        FrlgOperationPhase.WAITING_FOR_CONSOLE,
                        "On the GBA, open Mystery Gift, choose Wonder Cards, then Friend.",
                    ))
                }
            } catch (error: Exception) {
                discovery?.stop()
                operation?.let {
                    if (session.state.phase in setOf(Esp32RadioPhase.STARTING_RADIO, Esp32RadioPhase.RADIO_ACTIVE)) {
                        try { session.stopAccessPoint(it) } catch (_: Exception) { }
                    }
                }
                synchronized(frlgOperationLock) {
                    if (activeFrlgOperation?.discovery === discovery) activeFrlgOperation = null
                }
                runOnUiThread { finishFrlgStartupFailure(jobId, error, saveBackup, saveRestore) }
                FrlgOperationService.finish(FrlgOperationPhase.FAILED, error.message ?: "Could not start FRLG operation")
            }
        }
    }

    private fun requestOperationNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun onForegroundOperationProgress(progress: FrlgForegroundProgress) {
        runOnUiThread {
            progress.snapshot?.let { snapshot ->
                when (progress.kind) {
                    FrlgForegroundKind.SAVE_RESTORE -> updateSaveRestoreState(snapshot)
                    FrlgForegroundKind.SAVE_BACKUP -> updateSaveBackupState(snapshot)
                    FrlgForegroundKind.WALK_THROUGH_WALLS,
                    FrlgForegroundKind.POKEHAX_SCREEN ->
                        frlgOperationState = frlgOperationState.recordProgress(frlgState(snapshot))
                }
            }
            if (progress.phase == FrlgOperationPhase.RETURNING_TO_READY) {
                setOperationState(
                    progress.kind == FrlgForegroundKind.SAVE_BACKUP,
                    progress.kind == FrlgForegroundKind.SAVE_RESTORE,
                    activeOperationState(
                        progress.kind == FrlgForegroundKind.SAVE_BACKUP,
                        progress.kind == FrlgForegroundKind.SAVE_RESTORE,
                    ).copy(phase = FrlgOperationPhase.RETURNING_TO_READY, detail = progress.detail),
                )
            }
            if (progress.terminal) {
                synchronized(frlgOperationLock) { activeFrlgOperation = null }
                val saveBackup = progress.kind == FrlgForegroundKind.SAVE_BACKUP
                val saveRestore = progress.kind == FrlgForegroundKind.SAVE_RESTORE
                val previous = activeOperationState(saveBackup, saveRestore)
                if (saveBackup && saveBackupState.dataAvailable) {
                    saveBackupState = saveBackupState.finishAfterPersistence(
                        progress.detail ?: "Save backup complete",
                        if (progress.phase == FrlgOperationPhase.COMPLETED) FrlgSaveCloseResult.NORMAL
                        else FrlgSaveCloseResult.TRANSPORT_FAILURE,
                    )
                    return@runOnUiThread
                }
                setOperationState(saveBackup, saveRestore, FrlgOperationState(
                    progress.phase,
                    progress.detail,
                    previous.lastReached,
                    progress.snapshot?.let(::frlgProtocolDiagnostics) ?: previous.diagnostics,
                ))
            }
        }
    }

    private fun updateSaveBackupState(snapshot: LdnDiscoverySnapshot) {
        val next = saveBackupOperationState(snapshot)
        val progress = saveBackupState.operation.recordProgress(next)
        val completed = snapshot.saveBackupData?.takeIf { it.size == 131072 }
        val cartridge = snapshot.detectedCartridge ?: saveBackupState.cartridge
        val filename = if (completed != null && saveBackupState.suggestedFilename == null) {
            val parts = cartridge?.split(" / ")
            FrlgSaveExport.suggestedFilename(
                parts?.getOrNull(0) ?: "FRLG",
                parts?.getOrNull(1) ?: "Unknown",
                Instant.now(),
                ZoneId.systemDefault(),
            )
        } else saveBackupState.suggestedFilename
        saveBackupState = saveBackupState.copy(
            operation = progress,
            bytesReceived = snapshot.saveBackupBytes,
            totalBytes = snapshot.saveBackupTotalBytes.takeIf { it > 0 } ?: 131072,
            cartridge = cartridge,
            hasWholeSlot = snapshot.saveBackupHasWholeSlot,
            save = completed ?: saveBackupState.save,
            suggestedFilename = filename,
            exportPhase = if (completed != null && saveBackupState.exportPhase == FrlgSaveExportPhase.NOT_AVAILABLE)
                FrlgSaveExportPhase.AVAILABLE else saveBackupState.exportPhase,
            closeResult = snapshot.saveBackupCloseResult,
        )
    }

    private fun saveBackupOperationState(snapshot: LdnDiscoverySnapshot): FrlgOperationState {
        val phase = when (snapshot.saveBackupStage) {
            FrlgSaveBackupStage.WAITING_CLIENT -> FrlgOperationPhase.ESTABLISHING_GAME_LINK
            FrlgSaveBackupStage.READING_CARTRIDGE -> FrlgOperationPhase.READING_CARTRIDGE
            FrlgSaveBackupStage.RECEIVING_SAVE -> FrlgOperationPhase.RECEIVING_SAVE
            FrlgSaveBackupStage.WAITING_READY_END -> FrlgOperationPhase.VALIDATING_SAVE
            FrlgSaveBackupStage.CLOSING -> FrlgOperationPhase.CLOSING_LINK
            FrlgSaveBackupStage.COMPLETED -> FrlgOperationPhase.COMPLETED
            FrlgSaveBackupStage.FAILED -> FrlgOperationPhase.FAILED
            null -> when {
                snapshot.linkPlayerExchanged -> FrlgOperationPhase.ESTABLISHING_GAME_LINK
                snapshot.rfuConnected -> FrlgOperationPhase.ESTABLISHING_RFU
                snapshot.reliableEstablished -> FrlgOperationPhase.ESTABLISHING_RELIABLE
                snapshot.piaStage == LdnPiaStage.ESTABLISHED -> FrlgOperationPhase.ESTABLISHING_PIA
                snapshot.participantRegistered -> FrlgOperationPhase.PARTICIPANT_REGISTERED
                snapshot.authenticationRequests > 0 -> FrlgOperationPhase.AUTHENTICATING
                snapshot.stationDetected -> FrlgOperationPhase.STATION_ASSOCIATED
                snapshot.discoveryActivityCount > 0 -> FrlgOperationPhase.CONSOLE_DETECTED
                else -> FrlgOperationPhase.WAITING_FOR_CONSOLE
            }
        }
        return FrlgOperationState(
            phase,
            userOperationDetail(phase, snapshot),
            phase,
            frlgProtocolDiagnostics(snapshot),
        )
    }

    private fun updateSaveRestoreState(snapshot: LdnDiscoverySnapshot) {
        val phase = when(snapshot.saveRestoreStage) {
            FrlgSaveRestoreStage.WAITING_CLIENT, FrlgSaveRestoreStage.VALIDATING -> FrlgOperationPhase.VALIDATING_SAVE
            FrlgSaveRestoreStage.READING_FOOTERS -> FrlgOperationPhase.READING_CARTRIDGE
            FrlgSaveRestoreStage.WRITING -> FrlgOperationPhase.RESTORING_SAVE
            FrlgSaveRestoreStage.LOADING -> FrlgOperationPhase.LOADING_RESTORED_SAVE
            FrlgSaveRestoreStage.CLOSING -> FrlgOperationPhase.CLOSING_LINK
            FrlgSaveRestoreStage.COMPLETED -> FrlgOperationPhase.COMPLETED
            FrlgSaveRestoreStage.REFUSED -> FrlgOperationPhase.REFUSED
            FrlgSaveRestoreStage.FAILED -> FrlgOperationPhase.FAILED
            null -> saveBackupOperationState(snapshot).phase
        }
        val next = FrlgOperationState(phase, userOperationDetail(phase, snapshot), phase, frlgProtocolDiagnostics(snapshot))
        saveRestoreState = saveRestoreState.copy(
            operation=saveRestoreState.operation.recordProgress(next),cartridge=snapshot.detectedCartridge ?: saveRestoreState.cartridge,
            sectorsWritten=snapshot.saveRestoreSectors,totalSectors=snapshot.saveRestoreTotalSectors,
            failureMask=snapshot.saveRestoreFailureMask,loadResult=snapshot.saveRestoreLoadResult,
        )
    }

    private fun frlgState(snapshot: LdnDiscoverySnapshot): FrlgOperationState {
        val phase = when (snapshot.giftStage) {
            FrlgGiftStage.READING_CARTRIDGE -> FrlgOperationPhase.READING_CARTRIDGE
            FrlgGiftStage.PREPARING_BOOST -> FrlgOperationPhase.PREPARING_BOOST
            FrlgGiftStage.SENDING_BOOST -> FrlgOperationPhase.SENDING_BOOST
            FrlgGiftStage.WAITING_RESULT -> FrlgOperationPhase.WAITING_FOR_CLOSE
            FrlgGiftStage.CLOSING -> FrlgOperationPhase.CLOSING_LINK
            FrlgGiftStage.COMPLETED -> FrlgOperationPhase.COMPLETED
            FrlgGiftStage.FAILED -> FrlgOperationPhase.FAILED
            FrlgGiftStage.WAITING_CLIENT -> FrlgOperationPhase.ESTABLISHING_GAME_LINK
            null -> when {
                snapshot.linkPlayerExchanged -> FrlgOperationPhase.ESTABLISHING_GAME_LINK
                snapshot.rfuConnected -> FrlgOperationPhase.ESTABLISHING_RFU
                snapshot.reliableEstablished -> FrlgOperationPhase.ESTABLISHING_RELIABLE
                snapshot.piaStage == LdnPiaStage.ESTABLISHED -> FrlgOperationPhase.ESTABLISHING_PIA
                snapshot.participantRegistered -> FrlgOperationPhase.PARTICIPANT_REGISTERED
                snapshot.authenticationRequests > 0 -> FrlgOperationPhase.AUTHENTICATING
                snapshot.stationDetected -> FrlgOperationPhase.STATION_ASSOCIATED
                snapshot.discoveryActivityCount > 0 -> FrlgOperationPhase.CONSOLE_DETECTED
                else -> FrlgOperationPhase.WAITING_FOR_CONSOLE
            }
        }
        val detected = snapshot.detectedCartridge?.let { "$it detected" }
        val diagnostics = frlgProtocolDiagnostics(snapshot)
        return FrlgOperationState(phase, detected ?: userOperationDetail(phase, snapshot), phase, diagnostics)
    }

    private fun userOperationDetail(phase: FrlgOperationPhase, snapshot: LdnDiscoverySnapshot): String? = when (phase) {
        FrlgOperationPhase.WAITING_FOR_CONSOLE -> "Open Mystery Gift on the console and choose Friend."
        FrlgOperationPhase.CONSOLE_DETECTED -> "Console activity detected"
        FrlgOperationPhase.STATION_ASSOCIATED -> "Console connected"
        FrlgOperationPhase.AUTHENTICATING -> "Checking console connection"
        FrlgOperationPhase.PARTICIPANT_REGISTERED,
        FrlgOperationPhase.ESTABLISHING_PIA,
        FrlgOperationPhase.ESTABLISHING_RELIABLE,
        FrlgOperationPhase.ESTABLISHING_RFU,
        FrlgOperationPhase.ESTABLISHING_GAME_LINK -> "Establishing game link"
        FrlgOperationPhase.FAILED -> snapshot.latestActivity?.takeUnless {
            it.contains("mask", ignoreCase = true) ||
                it.contains("PIA", ignoreCase = true) ||
                it.contains("Reliable", ignoreCase = true) ||
                it.contains("RFU", ignoreCase = true) ||
                it.contains("LinkPlayer", ignoreCase = true)
        } ?: "Operation failed. See Diagnostics for technical details."
        FrlgOperationPhase.REFUSED -> snapshot.latestActivity ?: "The cartridge refused this restore."
        else -> snapshot.latestActivity
    }

    private fun releaseStaleRadioOwnership(session: Esp32RadioSession) {
        val staleFrlg = synchronized(frlgOperationLock) {
            activeFrlgOperation?.takeIf { it.session === session }?.also { activeFrlgOperation = null }
        }
        staleFrlg?.discovery?.stop()
        synchronized(ldnTestLock) {
            activeLdnTest?.takeIf { it.session === session }?.also { activeLdnTest = null }
        }?.discovery?.stop()
    }

    private fun cancelWalkThroughWalls() {
        if (FrlgOperationService.progress()?.takeIf { !it.terminal }?.kind == FrlgForegroundKind.WALK_THROUGH_WALLS) {
            FrlgOperationService.cancel("Operation cancelled")
            return
        }
        val id = synchronized(frlgOperationLock) {
            if (activeFrlgOperation?.saveBackup != false || activeFrlgOperation?.saveRestore == true) return
            frlgOperationId
        }
        finishFrlgOperation(id, FrlgOperationPhase.CANCELLED, "Operation cancelled")
    }

    private fun cancelPokeHaxScreen() {
        if (FrlgOperationService.progress()?.takeIf { !it.terminal }?.kind == FrlgForegroundKind.POKEHAX_SCREEN) {
            FrlgOperationService.cancel("Operation cancelled")
            return
        }
        val id = synchronized(frlgOperationLock) {
            if (activeFrlgOperation?.saveBackup != false || activeFrlgOperation?.saveRestore == true) return
            frlgOperationId
        }
        finishFrlgOperation(id, FrlgOperationPhase.CANCELLED, "Operation cancelled")
    }

    private fun cancelSaveBackup() {
        if (FrlgOperationService.progress()?.takeIf { !it.terminal }?.kind == FrlgForegroundKind.SAVE_BACKUP) {
            FrlgOperationService.cancel("Save backup cancelled")
            return
        }
        val id = synchronized(frlgOperationLock) {
            if (activeFrlgOperation?.saveBackup != true) return
            frlgOperationId
        }
        finishFrlgOperation(id, FrlgOperationPhase.CANCELLED, "Save backup cancelled")
    }

    private fun cancelSaveRestore() {
        if (FrlgOperationService.progress()?.takeIf { !it.terminal }?.kind == FrlgForegroundKind.SAVE_RESTORE) {
            FrlgOperationService.cancel("Save restore cancelled")
            return
        }
        val id = synchronized(frlgOperationLock) {
            if (activeFrlgOperation?.saveRestore != true) return
            frlgOperationId
        }
        finishFrlgOperation(id, FrlgOperationPhase.CANCELLED, "Save restore cancelled")
    }

    private fun exportSaveBackup() {
        val filename = saveBackupState.suggestedFilename ?: return
        if (!saveBackupState.dataAvailable) return
        saveBackupExporter.launch(filename)
    }

    private fun persistCompletedBackup(backup: FrlgCompletedBackup) {
        val stored = backupStore.persist(backup)
        runOnUiThread {
            val warning = stored.externalWarning?.let { "Internal backup saved; external copy failed: $it" }
            saveBackupState = saveBackupState.copy(
                bytesReceived = stored.save.size,
                totalBytes = stored.save.size,
                cartridge = "${stored.game} / ${stored.language}",
                hasWholeSlot = stored.hasWholeSlot,
                save = stored.save.copyOf(),
                suggestedFilename = stored.filename,
                internalFilename = stored.filename,
                externalFilename = stored.externalFilename,
                exportPhase = FrlgSaveExportPhase.AVAILABLE,
                exportDetail = warning ?: "Backup saved to private app storage",
            )
            refreshBackupStorageState(warning)
        }
    }

    private fun restoreLatestBackup() {
        val stored = backupStore.latest() ?: return
        saveBackupState = FrlgSaveBackupUiState(
            operation = FrlgOperationState(FrlgOperationPhase.COMPLETED, "Backup available in storage"),
            bytesReceived = stored.save.size,
            totalBytes = stored.save.size,
            cartridge = "${stored.game} / ${stored.language}",
            hasWholeSlot = stored.hasWholeSlot,
            save = stored.save.copyOf(),
            suggestedFilename = stored.filename,
            internalFilename = stored.filename,
            exportPhase = FrlgSaveExportPhase.AVAILABLE,
            exportDetail = "Loaded from private app storage",
        )
    }

    private fun refreshBackupStorageState(detail: String? = null) {
        backupStorageState = FrlgBackupStorageUiState(
            configuredFolderName = runCatching { backupFolder.displayName() }.getOrNull(),
            latestBackupFilename = backupStore.latest()?.filename,
            detail = detail,
        )
    }

    private fun activeOperationState(saveBackup: Boolean, saveRestore: Boolean = false): FrlgOperationState =
        if (saveRestore) saveRestoreState.operation else if (saveBackup) saveBackupState.operation else frlgOperationState

    private fun setOperationState(saveBackup: Boolean, saveRestore: Boolean = false, state: FrlgOperationState) {
        if (saveRestore) saveRestoreState = saveRestoreState.copy(operation = state)
        else if (saveBackup) saveBackupState = saveBackupState.copy(operation = state)
        else frlgOperationState = state
    }

    private fun finishFrlgOperation(id: Int, terminal: FrlgOperationPhase, detail: String) {
        if (id != frlgOperationId || !FrlgOperationService.isActive()) return
        FrlgOperationService.finish(terminal, detail)
    }

    private fun finishFrlgStartupFailure(id: Int, error: Exception, saveBackup: Boolean, saveRestore: Boolean = false) {
        if (id != frlgOperationId) return
        val progress = activeOperationState(saveBackup, saveRestore)
        setOperationState(saveBackup, saveRestore, FrlgOperationState(
            FrlgOperationPhase.FAILED,
            error.message ?: "Could not start FRLG operation",
            progress.lastReached,
            progress.diagnostics,
        ))
    }

    private fun invalidateFrlgOperation(interruption: String? = null) {
        val active = synchronized(frlgOperationLock) {
            frlgOperationId++
            activeFrlgOperation.also { activeFrlgOperation = null }
        }
        active?.discovery?.stop()
        frlgOperationState = FrlgOperationState()
        if (active?.saveRestore == true) {
            val previous = saveRestoreState.operation
            saveRestoreState = saveRestoreState.copy(operation = FrlgOperationState(
                FrlgOperationPhase.FAILED,
                interruption ?: "Save restore connection closed",
                previous.lastReached,
                previous.diagnostics,
            ))
        }
        saveBackupState = if (saveBackupState.dataAvailable) {
            if (active?.saveBackup == true) {
                saveBackupState.finishAfterPersistence(
                    interruption?.let { "Backup saved; connection warning: $it" }
                        ?: "Backup saved; connection closed locally",
                    FrlgSaveCloseResult.TRANSPORT_FAILURE,
                )
            } else saveBackupState
        } else if (active?.saveBackup == true && interruption != null) {
            val previous = saveBackupState.operation
            saveBackupState.copy(
                operation = FrlgOperationState(
                    FrlgOperationPhase.FAILED,
                    interruption,
                    previous.lastReached,
                    previous.diagnostics,
                ),
            )
        } else if (active != null) {
            FrlgSaveBackupUiState()
        } else saveBackupState
    }

    private fun discoveryState(channel: Int, snapshot: LdnDiscoverySnapshot): LdnApTestState =
        LdnApTestState(
            phase = when {
                snapshot.linkPlayerExchanged -> LdnApTestPhase.LINK_PLAYER_EXCHANGED
                snapshot.rfuConnected -> LdnApTestPhase.RFU_CONNECTED
                snapshot.reliableEstablished -> LdnApTestPhase.RELIABLE_ESTABLISHED
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
            reliableEstablished = snapshot.reliableEstablished,
            reliableFramesReceived = snapshot.reliableFramesReceived,
            reliableFramesSent = snapshot.reliableFramesSent,
            rfuConnected = snapshot.rfuConnected,
            linkPlayerExchanged = snapshot.linkPlayerExchanged,
            detectedCartridge = snapshot.detectedCartridge,
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
        try { radioStateSubscription?.close() } catch (_: Exception) { }
        val previous = synchronized(radioSessionLock) {
            val current = radioSession
            radioSession = session
            current
        }
        radioStateSubscription = session.addStateListener { next ->
            runOnUiThread { if (radioSession === session) radioState = next }
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
        if (session != null) {
            FrlgOperationService.releaseRetained(session)
            try { radioStateSubscription?.close() } catch (_: Exception) { }
            radioStateSubscription = null
            runOnUiThread { radioState = Esp32RadioState() }
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
