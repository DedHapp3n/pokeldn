package com.dedhapp3n.pokeldn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dedhapp3n.pokeldn.BuildConfig
import com.dedhapp3n.pokeldn.esp32.Esp32HandshakePhase
import com.dedhapp3n.pokeldn.esp32.Esp32HandshakeState
import com.dedhapp3n.pokeldn.esp32.RawCaptureState
import com.dedhapp3n.pokeldn.esp32.RawReadBufferMode
import com.dedhapp3n.pokeldn.esp32.Esp32RadioPhase
import com.dedhapp3n.pokeldn.esp32.Esp32RadioState
import com.dedhapp3n.pokeldn.frlg.FrlgOperationState
import com.dedhapp3n.pokeldn.frlg.FrlgOperationPhase
import com.dedhapp3n.pokeldn.frlg.FrlgOperationStartGate
import com.dedhapp3n.pokeldn.frlg.FrlgSaveBackupUiState
import com.dedhapp3n.pokeldn.frlg.FrlgBackupStorageUiState
import com.dedhapp3n.pokeldn.frlg.FrlgSaveExportPhase
import com.dedhapp3n.pokeldn.frlg.FrlgSaveCloseResult
import com.dedhapp3n.pokeldn.frlg.FrlgSaveRestoreUiState
import com.dedhapp3n.pokeldn.frlg.ProdKeysPhase
import com.dedhapp3n.pokeldn.frlg.ProdKeysState
import com.dedhapp3n.pokeldn.frlg.WalkThroughWallsPreset
import com.dedhapp3n.pokeldn.ldn.LdnApTestState
import com.dedhapp3n.pokeldn.profile.TrainerGender
import com.dedhapp3n.pokeldn.profile.TrainerLanguage
import com.dedhapp3n.pokeldn.profile.TrainerProfile
import com.dedhapp3n.pokeldn.profile.TrainerIdentitySettings
import com.dedhapp3n.pokeldn.profile.TrainerSource
import com.dedhapp3n.pokeldn.ui.theme.CreamPanel
import com.dedhapp3n.pokeldn.ui.theme.CreamPanelDark
import com.dedhapp3n.pokeldn.ui.theme.DeviceAmber
import com.dedhapp3n.pokeldn.ui.theme.DeviceBezel
import com.dedhapp3n.pokeldn.ui.theme.DeviceInk
import com.dedhapp3n.pokeldn.ui.theme.IndicatorCyan
import com.dedhapp3n.pokeldn.ui.theme.IndicatorGreen
import com.dedhapp3n.pokeldn.ui.theme.IndicatorRed
import com.dedhapp3n.pokeldn.ui.theme.ScreenMuted
import com.dedhapp3n.pokeldn.ui.theme.ScreenText
import com.dedhapp3n.pokeldn.ui.theme.ShellRed
import com.dedhapp3n.pokeldn.ui.theme.ShellRedBright
import com.dedhapp3n.pokeldn.ui.theme.ShellRedDark
import com.dedhapp3n.pokeldn.usb.SerialConnectionPhase
import com.dedhapp3n.pokeldn.usb.SerialConnectionState
import com.dedhapp3n.pokeldn.usb.UsbDeviceInfo
import com.dedhapp3n.pokeldn.usb.UsbScanResult
import com.dedhapp3n.pokeldn.za.ZaSnapshot
import com.dedhapp3n.pokeldn.za.ZaReaderState
import com.dedhapp3n.pokeldn.za.ZaNames
import com.dedhapp3n.pokeldn.za.ZaSaveDocument
import com.dedhapp3n.pokeldn.sprites.PokemonSpriteKey
import com.dedhapp3n.pokeldn.sprites.PokemonSpriteRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class AppDestination(val label: String) {
    HOME("Home"),
    GAMES("Games"),
    OPTIONS("Optionen"),
    DIAGNOSTICS("Diagnostics"),
}

private const val SAVE_BACKUP_ID = "frlg-save-backup"
private const val SAVE_RESTORE_ID = "frlg-save-restore"
private const val TRAINER_OPTION_ID = "trainer"

private data class GameModule(
    val title: String,
    val generation: String,
    val detail: String,
    val primary: Boolean = false,
    val researchItems: List<String> = emptyList(),
)

private val gameModules = listOf(
    GameModule("FireRed / LeafGreen", "GEN III", "Walk Through Walls, Save Backup, and Save Restore.", true),
    GameModule("Let's Go Pikachu / Eevee", "GEN VII", "Upstream-supported game; Android features are not available yet."),
    GameModule("Sword / Shield", "GEN VIII", "Upstream-supported game; Android features are not available yet."),
    GameModule("Brilliant Diamond / Shining Pearl", "GEN VIII", "Upstream-supported game; Android features are not available yet."),
    GameModule("Legends: Arceus", "GEN VIII", "Upstream-supported game; Android features are not available yet."),
    GameModule("Scarlet / Violet", "GEN IX", "Upstream-supported game; Android features are not available yet."),
    GameModule(
        "Legends: Z-A", "GEN IX", "Physically verified read-only Save Reader.",
    ),
)

@Composable
internal fun PokeLdnApp(
    result: UsbScanResult?,
    connectionState: SerialConnectionState,
    esp32State: Esp32HandshakeState,
    rawCaptureState: RawCaptureState,
    connectedBaudRate: Int,
    selectedDiagnosticBaud: Int,
    selectedReadBufferMode: RawReadBufferMode,
    onScan: () -> Unit,
    onConnectEsp32: (String) -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onTestEsp32: () -> Unit,
    onDiagnosticBaudSelected: (Int) -> Unit,
    onReadBufferModeSelected: (RawReadBufferMode) -> Unit,
    onCaptureRaw: (Int, RawReadBufferMode) -> Unit,
    prodKeysState: ProdKeysState,
    onImportProdKeys: () -> Unit,
    trainerProfile: TrainerProfile,
    onSaveTrainerProfile: (TrainerProfile) -> Unit,
    trainerIdentitySettings: TrainerIdentitySettings,
    onTrainerSourceSelected: (TrainerSource) -> Unit,
    ldnApTestState: LdnApTestState,
    onStartLdnApTest: () -> Unit,
    onStopLdnApTest: () -> Unit,
    frlgOperationState: FrlgOperationState,
    saveBackupState: FrlgSaveBackupUiState,
    saveRestoreState: FrlgSaveRestoreUiState,
    radioState: Esp32RadioState,
    onStartWalkThroughWalls: () -> Unit,
    onCancelWalkThroughWalls: () -> Unit,
    onStartSaveBackup: () -> Unit,
    onCancelSaveBackup: () -> Unit,
    onExportSaveBackup: () -> Unit,
    onSelectSaveRestore: () -> Unit,
    onStartSaveRestore: () -> Unit,
    onCancelSaveRestore: () -> Unit,
    backupStorageState: FrlgBackupStorageUiState,
    onOpenBackupFolder: () -> Unit,
    onChangeBackupFolder: () -> Unit,
    zaSnapshots: List<ZaSnapshot>,
    zaNames: ZaNames,
    pokemonSprites: PokemonSpriteRepository,
    zaReaderState: ZaReaderState?,
    onStartZaReader: () -> Unit,
    onFinishZaReader: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var destination by rememberSaveable { mutableStateOf(AppDestination.HOME) }
    var selectedGame by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedOperation by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedOption by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedZaSnapshot by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedZaPokemon by rememberSaveable { mutableStateOf<Int?>(null) }
    var selectedZaPage by rememberSaveable { mutableStateOf(0) }
    var zaOpenedFromHistory by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        containerColor = ShellRed,
        topBar = { DeviceHeader() },
        bottomBar = { DeviceModeBar(selected = destination, onSelected = { destination = it }) },
    ) { innerPadding ->
        when (destination) {
            AppDestination.HOME -> HomeScreen(
                result = result,
                connectionState = connectionState,
                esp32State = esp32State,
                radioState = radioState,
                keysState = prodKeysState,
                backupStorageState = backupStorageState,
                frlgOperationState = frlgOperationState,
                saveBackupState = saveBackupState,
                saveRestoreState = saveRestoreState,
                onConnectEsp32 = onConnectEsp32,
                modifier = Modifier.padding(innerPadding),
            )
            AppDestination.GAMES -> when {
                selectedOperation == WalkThroughWallsPreset.BOOST_ID -> WalkThroughWallsScreen(
                    connectionState = connectionState,
                    esp32State = esp32State,
                    keysState = prodKeysState,
                    operationState = frlgOperationState,
                    radioState = radioState,
                    onImportKeys = onImportProdKeys,
                    onStart = onStartWalkThroughWalls,
                    onCancel = onCancelWalkThroughWalls,
                    onBack = { selectedOperation = null },
                    modifier = Modifier.padding(innerPadding),
                )
                selectedOperation == SAVE_BACKUP_ID -> SaveBackupScreen(
                    connectionState = connectionState,
                    esp32State = esp32State,
                    keysState = prodKeysState,
                    backupState = saveBackupState,
                    radioState = radioState,
                    onImportKeys = onImportProdKeys,
                    onStart = onStartSaveBackup,
                    onCancel = onCancelSaveBackup,
                    onExport = onExportSaveBackup,
                    onBack = { selectedOperation = null },
                    modifier = Modifier.padding(innerPadding),
                )
                selectedOperation == SAVE_RESTORE_ID -> SaveRestoreScreen(
                    connectionState, esp32State, prodKeysState, saveRestoreState, radioState,
                    onImportProdKeys, onSelectSaveRestore, onStartSaveRestore, onCancelSaveRestore,
                    { selectedOperation = null }, Modifier.padding(innerPadding),
                )
                selectedGame == "frlg" -> FrlgOperationsScreen(
                    onOpenWalkThroughWalls = { selectedOperation = WalkThroughWallsPreset.BOOST_ID },
                    onOpenSaveBackup = { selectedOperation = SAVE_BACKUP_ID },
                    onOpenSaveRestore = { selectedOperation = SAVE_RESTORE_ID },
                    onBack = { selectedGame = null },
                    modifier = Modifier.padding(innerPadding),
                )
                selectedGame == "za" -> ZaReaderScreen(
                    zaSnapshots,
                    zaNames,
                    pokemonSprites,
                    zaReaderState,
                    radioState,
                    prodKeysState,
                    selectedZaSnapshot,
                    selectedZaPokemon,
                    selectedZaPage,
                    onSelectPage = { selectedZaPage = it },
                    onStart = onStartZaReader,
                    onFinish = onFinishZaReader,
                    onSelectSnapshot = {
                        if (selectedZaSnapshot == "history" && it != "history") zaOpenedFromHistory = true
                        selectedZaSnapshot = it; selectedZaPokemon = null; selectedZaPage = 0
                    },
                    onSelectPokemon = { selectedZaPokemon = it },
                    onBack = {
                        when {
                            selectedZaPokemon != null -> selectedZaPokemon = null
                            selectedZaSnapshot != null -> {
                                selectedZaSnapshot = if (selectedZaSnapshot != "history" && zaOpenedFromHistory) "history" else null
                                if (selectedZaSnapshot == null) zaOpenedFromHistory = false
                            }
                            else -> selectedGame = null
                        }
                    },
                    modifier = Modifier.padding(innerPadding),
                )
                else -> GamesScreen(
                    onOpenFrlg = { selectedGame = "frlg" },
                    onOpenZa = { selectedGame = "za" },
                    radioState = radioState,
                    modifier = Modifier.padding(innerPadding),
                )
            }
            AppDestination.OPTIONS -> if (selectedOption == TRAINER_OPTION_ID) {
                TrainerProfileScreen(
                    profile = trainerProfile,
                    identitySettings = trainerIdentitySettings,
                    onSourceSelected = onTrainerSourceSelected,
                    onSave = onSaveTrainerProfile,
                    onBack = { selectedOption = null },
                    modifier = Modifier.padding(innerPadding),
                )
            } else {
                OptionsScreen(
                    trainerProfile = trainerProfile,
                    identitySettings = trainerIdentitySettings,
                    onOpenTrainer = { selectedOption = TRAINER_OPTION_ID },
                    prodKeysState = prodKeysState,
                    onImportProdKeys = onImportProdKeys,
                    backupStorageState = backupStorageState,
                    onOpenBackupFolder = onOpenBackupFolder,
                    onChangeBackupFolder = onChangeBackupFolder,
                    modifier = Modifier.padding(innerPadding),
                )
            }
            AppDestination.DIAGNOSTICS -> DiagnosticsScreen(
                result = result,
                connectionState = connectionState,
                esp32State = esp32State,
                rawCaptureState = rawCaptureState,
                connectedBaudRate = connectedBaudRate,
                selectedDiagnosticBaud = selectedDiagnosticBaud,
                selectedReadBufferMode = selectedReadBufferMode,
                onScan = onScan,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onTestEsp32 = onTestEsp32,
                onDiagnosticBaudSelected = onDiagnosticBaudSelected,
                onReadBufferModeSelected = onReadBufferModeSelected,
                onCaptureRaw = onCaptureRaw,
                prodKeysState = prodKeysState,
                onImportProdKeys = onImportProdKeys,
                ldnApTestState = ldnApTestState,
                frlgOperationState = listOf(saveRestoreState.operation, saveBackupState.operation, frlgOperationState)
                    .firstOrNull { it.diagnostics != null } ?: frlgOperationState,
                zaReaderState = zaReaderState,
                onStartLdnApTest = onStartLdnApTest,
                onStopLdnApTest = onStopLdnApTest,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun DeviceHeader() {
    Surface(
        color = ShellRedBright,
        contentColor = CreamPanel,
        shadowElevation = 8.dp,
        modifier = Modifier.statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                color = CreamPanel,
                shape = CircleShape,
                border = BorderStroke(3.dp, DeviceBezel),
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Surface(color = IndicatorCyan, shape = CircleShape, modifier = Modifier.size(34.dp)) {
                        Box(contentAlignment = Alignment.TopStart) {
                            Surface(
                                color = CreamPanel.copy(alpha = 0.85f),
                                shape = CircleShape,
                                modifier = Modifier.padding(6.dp).size(7.dp),
                            ) {}
                        }
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text("PokeLDN", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                Text("MOBILE LINK UNIT", style = MaterialTheme.typography.labelMedium, color = CreamPanelDark)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                IndicatorLight(IndicatorRed, 11.dp)
                IndicatorLight(DeviceAmber, 11.dp)
                IndicatorLight(IndicatorGreen, 11.dp)
            }
        }
    }
}

@Composable
private fun DeviceModeBar(selected: AppDestination, onSelected: (AppDestination) -> Unit) {
    Surface(
        color = ShellRedDark,
        contentColor = CreamPanel,
        shadowElevation = 10.dp,
        modifier = Modifier.navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppDestination.entries.forEach { destination ->
                val isSelected = destination == selected
                Surface(
                    color = if (isSelected) CreamPanel else ShellRed,
                    contentColor = if (isSelected) DeviceInk else CreamPanel,
                    shape = RoundedCornerShape(9.dp),
                    border = BorderStroke(2.dp, DeviceBezel),
                    shadowElevation = if (isSelected) 1.dp else 4.dp,
                    modifier = Modifier.weight(1f).height(58.dp)
                        .semantics {
                            this.selected = isSelected
                            role = Role.Tab
                        }
                        .clickable { onSelected(destination) },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            destination.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Black,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    result: UsbScanResult?,
    connectionState: SerialConnectionState,
    esp32State: Esp32HandshakeState,
    radioState: Esp32RadioState,
    keysState: ProdKeysState,
    backupStorageState: FrlgBackupStorageUiState,
    frlgOperationState: FrlgOperationState,
    saveBackupState: FrlgSaveBackupUiState,
    saveRestoreState: FrlgSaveRestoreUiState,
    onConnectEsp32: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val devices = (result as? UsbScanResult.Devices)?.items.orEmpty()
    val serialDevice = devices.firstOrNull { it.deviceName == connectionState.deviceName }
        ?: devices.firstOrNull { it.serialSupported }
    val operations = listOf(
        "Save Restore" to saveRestoreState.operation,
        "Save Backup" to saveBackupState.operation,
        "Walk Through Walls" to frlgOperationState,
    )
    val activeOperation = operations.firstOrNull { it.second.running }
    val latestOperation = activeOperation ?: operations.firstOrNull { it.second.phase != FrlgOperationPhase.IDLE }
    CenteredDeviceList(modifier) {
        item { DeviceScreenTitle(null, "PokeLDN", "Connect, choose a game, and start an operation") }
        item {
            MainAdapterDisplay(
                device = serialDevice,
                connectionState = connectionState,
                esp32State = esp32State,
                radioState = radioState,
                onConnectEsp32 = onConnectEsp32,
            )
        }
        item {
            ShellPanel("Overview") {
                OverviewRow(
                    "Switch keys",
                    when (keysState.phase) {
                        ProdKeysPhase.AVAILABLE -> "Available"
                        ProdKeysPhase.INVALID -> "Invalid"
                        ProdKeysPhase.MISSING -> "Missing"
                    },
                    when (keysState.phase) {
                        ProdKeysPhase.AVAILABLE -> StatusTone.POSITIVE
                        ProdKeysPhase.INVALID -> StatusTone.ERROR
                        ProdKeysPhase.MISSING -> StatusTone.WARNING
                    },
                )
                OverviewRow(
                    "Latest backup",
                    backupStorageState.latestBackupFilename ?: "None yet",
                    if (backupStorageState.latestBackupFilename != null) StatusTone.POSITIVE else StatusTone.NEUTRAL,
                )
                latestOperation?.let { (name, state) ->
                    OverviewRow(
                        "Latest operation",
                        "$name: ${state.phase.label}",
                        when (state.phase) {
                            FrlgOperationPhase.COMPLETED -> StatusTone.POSITIVE
                            FrlgOperationPhase.FAILED, FrlgOperationPhase.REFUSED -> StatusTone.ERROR
                            FrlgOperationPhase.CANCELLED -> StatusTone.WARNING
                            else -> if (state.running) StatusTone.WARNING else StatusTone.NEUTRAL
                        },
                    )
                } ?: OverviewRow("Latest operation", "None yet", StatusTone.NEUTRAL)
                activeOperation?.let { (name, state) ->
                    OverviewRow("Current operation", "$name: ${state.phase.label}", StatusTone.WARNING)
                    state.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun OverviewRow(label: String, value: String, tone: StatusTone) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
        StatusBadge(value, tone)
    }
}

@Composable
private fun MainAdapterDisplay(
    device: UsbDeviceInfo?,
    connectionState: SerialConnectionState,
    esp32State: Esp32HandshakeState,
    radioState: Esp32RadioState,
    onConnectEsp32: (String) -> Unit,
) {
    val serialConnected = connectionState.phase == SerialConnectionPhase.CONNECTED
    val handshakeVerified = serialConnected && esp32State.phase == Esp32HandshakePhase.VERIFIED && esp32State.info != null
    val ready = handshakeVerified && radioState.phase == Esp32RadioPhase.READY
    val failed = connectionState.phase == SerialConnectionPhase.CONNECTION_FAILED ||
        esp32State.phase == Esp32HandshakePhase.FAILED
    val busy = connectionState.phase in setOf(
        SerialConnectionPhase.REQUESTING_PERMISSION,
        SerialConnectionPhase.CONNECTING,
        SerialConnectionPhase.DISCONNECTING,
    ) || esp32State.phase == Esp32HandshakePhase.TESTING
    BezelDisplay("Main adapter display") {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DisplayStatus(
                    when {
                        ready -> "ESP32 ready"
                        handshakeVerified -> "ESP32 ${radioState.phase.name.lowercase().replace('_', ' ')}"
                        esp32State.phase == Esp32HandshakePhase.TESTING -> "Verifying link"
                        connectionState.phase == SerialConnectionPhase.PERMISSION_DENIED -> "Permission denied"
                        failed -> "Connection failed"
                        serialConnected -> "Serial link online"
                        busy -> "Link in progress"
                        device != null -> "ESP32 detected"
                        else -> "ESP32 disconnected"
                    },
                    when {
                        ready -> StatusTone.POSITIVE
                        handshakeVerified -> StatusTone.WARNING
                        busy -> StatusTone.WARNING
                        failed || connectionState.phase == SerialConnectionPhase.PERMISSION_DENIED -> StatusTone.ERROR
                        else -> StatusTone.NEUTRAL
                    },
                )
                Text(
                    when {
                        ready -> "PokeLDN radio is operationally ready. Choose a game to continue."
                        handshakeVerified -> radioState.detail ?: "The ESP32 radio is not ready for a new operation."
                        connectionState.phase == SerialConnectionPhase.PERMISSION_DENIED ->
                            "USB permission is required. Tap Connect ESP32 to ask again."
                        esp32State.phase == Esp32HandshakePhase.FAILED ->
                            "The ESP32 did not verify. You can try the connection again."
                        serialConnected -> "Finishing ESP32 verification."
                        device != null -> "ESP32 USB adapter found. Connect it when you are ready."
                        else -> "Connect the ESP32 through USB OTG. It will appear here automatically."
                    },
                    color = ScreenMuted,
                )
            }
            if (busy) CircularProgressIndicator(Modifier.size(30.dp), color = IndicatorCyan, strokeWidth = 3.dp)
        }
        if (!handshakeVerified && serialConnected) {
            esp32State.detail?.let { Text(it, color = IndicatorRed) }
            DeviceButton(
                if (esp32State.phase == Esp32HandshakePhase.FAILED) "Retry connection" else "Connect ESP32",
                { device?.let { onConnectEsp32(it.deviceName) } },
                Modifier.fillMaxWidth(),
                enabled = !busy && device != null,
                style = DeviceButtonStyle.DISPLAY,
            )
        } else if (!handshakeVerified && device != null) {
            DeviceButton(
                "Connect ESP32",
                { onConnectEsp32(device.deviceName) },
                Modifier.fillMaxWidth(),
                enabled = !busy && device.serialSupported,
                style = DeviceButtonStyle.DISPLAY,
            )
        }
        connectionState.detail?.let {
            Text(
                it,
                color = if (connectionState.phase in setOf(
                        SerialConnectionPhase.CONNECTION_FAILED,
                        SerialConnectionPhase.PERMISSION_DENIED,
                    )) IndicatorRed else ScreenMuted,
            )
        }
    }
}

@Composable
private fun GamesScreen(
    onOpenFrlg: () -> Unit,
    onOpenZa: () -> Unit,
    radioState: Esp32RadioState,
    modifier: Modifier = Modifier,
) {
    CenteredDeviceList(modifier) {
        item {
            DeviceScreenTitle(null, "Games", "Choose a supported game")
        }
        item {
            ShellPanel("Status") {
                OverviewRow("ESP32", if (radioState.phase == Esp32RadioPhase.READY) "Ready" else "Not ready", if (radioState.phase == Esp32RadioPhase.READY) StatusTone.POSITIVE else StatusTone.NEUTRAL)
            }
        }
        item {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = if (maxWidth >= 600.dp) 2 else 1
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    gameModules.chunked(columns).forEach { rowModules ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            rowModules.forEach { module ->
                                GameModulePanel(module, when (module.title) {
                                    "FireRed / LeafGreen" -> onOpenFrlg
                                    "Legends: Z-A" -> onOpenZa
                                    else -> null
                                }, Modifier.weight(1f))
                            }
                            if (rowModules.size < columns) {
                                repeat(columns - rowModules.size) { Box(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZaReaderScreen(
    snapshots: List<ZaSnapshot>,
    names: ZaNames,
    sprites: PokemonSpriteRepository,
    reader: ZaReaderState?,
    radioState: Esp32RadioState,
    keysState: ProdKeysState,
    selectedId: String?,
    selectedPokemon: Int?,
    selectedPage: Int,
    onSelectPage: (Int) -> Unit,
    onStart: () -> Unit,
    onFinish: () -> Unit,
    onSelectSnapshot: (String?) -> Unit,
    onSelectPokemon: (Int?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = snapshots.firstOrNull { it.id == selectedId }
    val document = snapshot?.let(ZaSaveDocument::fromCapture)
    val pokemon = selectedPokemon?.let { snapshot?.pokemon?.getOrNull(it)?.summary }
    val active = reader != null && !reader.terminal
    CenteredDeviceList(modifier) {
        item {
            GameScreenTitle(GameSkins.ZA, "LEGENDS: Z-A", "Save Manager", "Local wireless capture of trainer and Pokémon previews")
            StatusBadge("PHYSICALLY VERIFIED READER", StatusTone.POSITIVE)
        }
        if (pokemon != null) {
            item {
                ShellPanel("POKÉMON") {
                    StatusBadge("PARTIAL CAPTURE", StatusTone.WARNING)
                    PokemonSprite(sprites, pokemon.species.takeIf { it > 0 }
                        ?.let { PokemonSpriteKey(it, pokemon.form.coerceAtLeast(0), pokemon.shiny) }, 92.dp)
                    Text(names.headline(pokemon), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Text(names.summary(pokemon))
                    if (pokemon.nickname.isNotBlank() && !pokemon.nickname.equals(names.species(pokemon.species), true))
                        DetailLine("Nickname", pokemon.nickname)
                    StatusBadge(if (pokemon.shiny) "Shiny" else "Not shiny",
                        if (pokemon.shiny) StatusTone.POSITIVE else StatusTone.NEUTRAL)
                    DetailLine("Nature", names.nature(pokemon.nature))
                    DetailLine("Ability", names.ability(pokemon.ability))
                    DetailLine("Held item", names.item(pokemon.heldItem))
                    DetailLine("Ball", names.ball(pokemon.ball))
                    DetailLine("OT", pokemon.ot.ifBlank { "Unknown" })
                    DetailLine("TID / SID", "${pokemon.tid} / ${pokemon.sid}")
                }
            }
            item {
                ShellPanel("MOVES") {
                    val moves = pokemon.moves.filter { it != 0 }
                    if (moves.isEmpty()) Text("No moves recorded")
                    moves.forEach { Text(names.move(it)) }
                }
            }
            item {
                ShellPanel("IVs") {
                    ZaNames.statRows(pokemon.ivs).forEach { (label, value) -> DetailLine(label, value.toString()) }
                }
            }
            item {
                ShellPanel("EVs") {
                    ZaNames.statRows(pokemon.evs).forEach { (label, value) -> DetailLine(label, value.toString()) }
                }
            }
            item {
                ShellPanel("DETAILS") {
                    DetailLine("Language", zaLanguage(pokemon.language))
                    DetailLine("Met location", names.location(pokemon.metLocation))
                    DetailLine("Met date", pokemon.metDate.joinToString("/"))
                    DetailLine("Height / weight / scale", "${pokemon.height} / ${pokemon.weight} / ${pokemon.scale}")
                    DetailLine("PID", pokemon.pid.toString(16).uppercase())
                    DetailLine("EC", pokemon.encryptionConstant.toString(16).uppercase())
                }
            }
        } else if (snapshot != null) {
            item {
                ShellPanel("PARTIAL LIVE CAPTURE") {
                    Text(document!!.description)
                    DetailLine("Captured", snapshot.pokemon.size.toString())
                    snapshot.unreadablePreviews.takeIf { it > 0 }?.let { DetailLine("Unreadable previews", it.toString()) }
                }
            }
            item {
                ShellPanel("TRAINER") {
                    val trainer = snapshot.trainer
                    if (trainer == null) Text("No trainer identity captured") else {
                        Text(trainer.name, style = MaterialTheme.typography.titleLarge)
                        DetailLine("TID", trainer.tid.toString())
                        DetailLine("SID", trainer.sid.toString())
                        DetailLine("Gender", trainer.genderLabel)
                        DetailLine("Language", trainer.languageLabel)
                    }
                }
            }
            item {
                val page = document!!.pages[selectedPage.coerceIn(document.pages.indices)]
                ZaCaptureGrid(page, names, sprites, onSelectPokemon)
            }
            if (document!!.pages.size > 1) item {
                ShellPanel("CAPTURED PAGES") {
                    DetailLine("Page", "${selectedPage.coerceIn(document.pages.indices) + 1} / ${document.pages.size}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DeviceButton("PREVIOUS", { onSelectPage((selectedPage - 1).coerceAtLeast(0)) }, Modifier.weight(1f),
                            enabled = selectedPage > 0, style = DeviceButtonStyle.SECONDARY)
                        DeviceButton("NEXT", { onSelectPage((selectedPage + 1).coerceAtMost(document.pages.lastIndex)) }, Modifier.weight(1f),
                            enabled = selectedPage < document.pages.lastIndex, style = DeviceButtonStyle.SECONDARY)
                    }
                }
            }
        } else if (selectedId == "history") {
            item {
                ShellPanel("SAVED LOCALLY") {
                    if (snapshots.isEmpty()) Text("No Z-A snapshots saved yet")
                }
            }
            snapshots.forEach { entry ->
                item {
                    ShellPanel(entry.trainer?.name ?: "Unknown trainer") {
                        Text("Partial capture · ${entry.pokemon.size} Pokémon read", fontWeight = FontWeight.Bold)
                        entry.unreadablePreviews.takeIf { it > 0 }?.let { Text("$it unreadable preview${if (it == 1) "" else "s"}") }
                        DetailLine("Last read", zaDate(entry.updated))
                        entry.trainer?.let { DetailLine("Language", it.languageLabel) }
                        StatusBadge(if (entry.finished) "Complete" else "In progress",
                            if (entry.finished) StatusTone.POSITIVE else StatusTone.WARNING)
                        DeviceButton("OPEN SNAPSHOT", { onSelectSnapshot(entry.id) }, Modifier.fillMaxWidth())
                    }
                }
            }
        } else if (active) {
            item {
                BezelDisplay("Reader status") {
                    Text("Z-A SAVE READER · PARTIAL CAPTURE", color = ScreenText, fontWeight = FontWeight.Black)
                    DisplayStatus(reader!!.phase, when (reader.phase) {
                        "FAILED" -> StatusTone.ERROR
                        "READING POKÉMON", "TRAINER DETECTED", "Z-A CONNECTED", "COMPLETE" -> StatusTone.POSITIVE
                        "WAITING FOR Z-A", "ESTABLISHING SESSION", "FINISHING" -> StatusTone.WARNING
                        else -> StatusTone.NEUTRAL
                    })
                    reader.snapshot.trainer?.let { Text("Trainer: ${it.name}", color = ScreenText) }
                    Text("Pokémon captured: ${reader.snapshot.pokemon.size}", color = ScreenText)
                    reader.current?.let { Text("Currently reading: ${names.headline(it)} · ${names.summary(it)}", color = ScreenText) }
                }
            }
            item {
                ShellPanel("SAVE READER") {
                    DetailLine("Link code", reader!!.code.chunked(4).joinToString(" "))
                    if (reader.snapshot.lastUnreadableAt?.let { System.currentTimeMillis() - it < 8_000 } == true)
                        Text("Could not read this Pokémon. Move to another Pokémon to continue.")
                    reader.snapshot.unreadablePreviews.takeIf { it > 0 }?.let { Text("$it unreadable preview${if (it == 1) "" else "s"}") }
                    DeviceButton("FINISH READING", onFinish, Modifier.fillMaxWidth())
                    DeviceButton("VIEW CAPTURED", { onSelectSnapshot(reader.snapshot.id) }, Modifier.fillMaxWidth(),
                        style = DeviceButtonStyle.SECONDARY)
                }
            }
        } else {
            item {
                ShellPanel("SAVE READER") {
                    Text("Read trainer and Pokémon previews over local wireless. This is a partial capture; full save import, export and writing are not available yet.")
                    snapshots.firstOrNull()?.let { latest ->
                        DetailLine("Latest trainer", latest.trainer?.name ?: "Unknown")
                        DetailLine("Pokémon", latest.pokemon.size.toString())
                        DetailLine("Last read", zaDate(latest.updated))
                    }
                    DeviceButton("READ FROM GAME", onStart, Modifier.fillMaxWidth(),
                        enabled = radioState.phase == Esp32RadioPhase.READY && keysState.phase == ProdKeysPhase.AVAILABLE)
                    if (keysState.phase != ProdKeysPhase.AVAILABLE) Text("Import valid prod.keys to start.")
                    else if (radioState.phase != Esp32RadioPhase.READY) Text("Connect and verify the ESP32 to start.")
                    reader?.takeIf { it.phase == "FAILED" }?.let { Text("Reader failed. See Diagnostics for details.") }
                    DeviceButton("SHOW SAVED DATA", { onSelectSnapshot("history") }, Modifier.fillMaxWidth(),
                        style = DeviceButtonStyle.SECONDARY)
                }
            }
        }
        if (selectedId != null) item {
            DeviceButton(if (selectedPokemon != null) "Back to snapshot" else "Back to Save Reader", onBack,
                Modifier.fillMaxWidth(), style = DeviceButtonStyle.SECONDARY)
        }
    }
}

private fun zaDate(epoch: Long): String = DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm")
    .format(Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()))

private fun zaLanguage(id: Int): String = when (id) {
    1 -> "Japanese"; 2 -> "English"; 3 -> "French"; 4 -> "Italian"; 5 -> "German"
    7 -> "Spanish"; 8 -> "Korean"; 9 -> "Chinese (Traditional)"; 10 -> "Chinese (Simplified)"
    11 -> "Spanish (Latin America)"; else -> "Unknown (#$id)"
}

@Composable
private fun OptionsScreen(
    trainerProfile: TrainerProfile,
    identitySettings: TrainerIdentitySettings,
    onOpenTrainer: () -> Unit,
    prodKeysState: ProdKeysState,
    onImportProdKeys: () -> Unit,
    backupStorageState: FrlgBackupStorageUiState,
    onOpenBackupFolder: () -> Unit,
    onChangeBackupFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredDeviceList(modifier) {
        item { DeviceScreenTitle(null, "Optionen", "App settings and local data") }
        item {
            ShellPanel("Trainer") {
                val activeTrainer = identitySettings.active(trainerProfile)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (activeTrainer.configured) activeTrainer.name else "Trainer profile",
                        modifier = Modifier.weight(1f),
                    )
                    StatusBadge(if (activeTrainer.configured) "Configured" else "Not configured", if (activeTrainer.configured) StatusTone.POSITIVE else StatusTone.WARNING)
                }
                Text(if (identitySettings.source == TrainerSource.AUTOMATIC_FROM_GAME)
                    "Automatic from game${identitySettings.detected?.let { ": ${it.sourceGame}" } ?: " — waiting for detection"}"
                    else "Manual trainer profile", style = MaterialTheme.typography.bodySmall)
                DeviceButton("Open Trainer", onOpenTrainer, Modifier.fillMaxWidth())
            }
        }
        item {
            ShellPanel("Switch Keys") {
                StatusBadge(
                    when (prodKeysState.phase) {
                        ProdKeysPhase.AVAILABLE -> "Keys available"
                        ProdKeysPhase.INVALID -> "Keys invalid"
                        ProdKeysPhase.MISSING -> "Keys missing"
                    },
                    when (prodKeysState.phase) {
                        ProdKeysPhase.AVAILABLE -> StatusTone.POSITIVE
                        ProdKeysPhase.INVALID -> StatusTone.ERROR
                        ProdKeysPhase.MISSING -> StatusTone.WARNING
                    },
                )
                Text("Your prod.keys file stays in app-private storage.")
                DeviceButton("Import prod.keys", onImportProdKeys, Modifier.fillMaxWidth())
            }
        }
        item {
            ShellPanel("Storage") {
                Text("Backup folder: ${backupStorageState.configuredFolderName ?: "Not selected"}")
                backupStorageState.latestBackupFilename?.let {
                    Text("Latest backup: $it", style = MaterialTheme.typography.bodySmall)
                }
                DeviceButton(
                    "Backups öffnen",
                    onOpenBackupFolder,
                    Modifier.fillMaxWidth(),
                    enabled = backupStorageState.configuredFolderName != null,
                    leading = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                )
                DeviceButton(
                    if (backupStorageState.configuredFolderName == null) "Backup-Ordner wählen" else "Ordner ändern",
                    onChangeBackupFolder,
                    Modifier.fillMaxWidth(),
                    style = DeviceButtonStyle.SECONDARY,
                )
                if (backupStorageState.configuredFolderName == null) {
                    Text(
                        "Backups are still kept safely inside the app. Choose a folder for copies you can browse.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                backupStorageState.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        item {
            ShellPanel("Device / Firmware") {
                DetailLine("App version", BuildConfig.VERSION_NAME)
                Text("ESP32 and firmware details are available in Diagnostics.")
            }
        }
    }
}

@Composable
private fun TrainerProfileScreen(
    profile: TrainerProfile,
    identitySettings: TrainerIdentitySettings,
    onSourceSelected: (TrainerSource) -> Unit,
    onSave: (TrainerProfile) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by rememberSaveable(profile.name) { mutableStateOf(profile.name) }
    var tid by rememberSaveable(profile.tid) { mutableStateOf(profile.tid?.toString().orEmpty()) }
    var sid by rememberSaveable(profile.sid) { mutableStateOf(profile.sid?.toString().orEmpty()) }
    var gender by rememberSaveable(profile.gender) { mutableStateOf(profile.gender) }
    var language by rememberSaveable(profile.language) { mutableStateOf(profile.language) }
    var genderMenu by rememberSaveable { mutableStateOf(false) }
    var languageMenu by rememberSaveable { mutableStateOf(false) }
    var feedback by rememberSaveable { mutableStateOf<String?>(null) }

    val draft = TrainerProfile(name, tid.toIntOrNull(), sid.toIntOrNull(), gender, language)
    val validationError = draft.normalized().validationError()
    CenteredDeviceList(modifier) {
        item { DeviceScreenTitle("Optionen / Trainer", "Trainer Profile", "App-wide identity for future game modules") }
        item { DeviceButton("Back to Optionen", onBack, Modifier.fillMaxWidth()) }
        item {
            ShellPanel("Trainer source") {
                DetailLine("Current source", if (identitySettings.source == TrainerSource.MANUAL) "Manual" else "Automatic from game")
                DeviceButton("MANUAL", { onSourceSelected(TrainerSource.MANUAL) }, Modifier.fillMaxWidth(),
                    style = if (identitySettings.source == TrainerSource.MANUAL) DeviceButtonStyle.PRIMARY else DeviceButtonStyle.SECONDARY)
                DeviceButton("AUTOMATIC FROM GAME", { onSourceSelected(TrainerSource.AUTOMATIC_FROM_GAME) }, Modifier.fillMaxWidth(),
                    style = if (identitySettings.source == TrainerSource.AUTOMATIC_FROM_GAME) DeviceButtonStyle.PRIMARY else DeviceButtonStyle.SECONDARY)
                identitySettings.detected?.let { detected ->
                    DetailLine("Detected from", detected.sourceGame)
                    Text(detected.profile.name, style = MaterialTheme.typography.titleLarge)
                    DetailLine("TID / SID", "${detected.profile.tid} / ${detected.profile.sid}")
                    Text("${detected.profile.gender.label} · ${detected.profile.language.label}")
                    DetailLine("Detected", zaDate(detected.detectedAt))
                } ?: if (identitySettings.source == TrainerSource.AUTOMATIC_FROM_GAME) {
                    Text("Waiting for a supported game's trainer identity.")
                } else Unit
            }
        }
        item {
            ShellPanel("Manual identity") {
                Text("Your manual profile is kept when automatic detection is selected.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; feedback = null },
                    label = { Text("Trainer name") },
                    supportingText = { Text("Game modules may apply shorter name limits later.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = tid,
                        onValueChange = { if (it.length <= 5 && it.all(Char::isDigit)) tid = it; feedback = null },
                        label = { Text("TID") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = sid,
                        onValueChange = { if (it.length <= 5 && it.all(Char::isDigit)) sid = it; feedback = null },
                        label = { Text("SID") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Box(Modifier.fillMaxWidth()) {
                    DeviceButton("Gender: ${gender.label}", { genderMenu = true }, Modifier.fillMaxWidth(), style = DeviceButtonStyle.SECONDARY)
                    DropdownMenu(expanded = genderMenu, onDismissRequest = { genderMenu = false }) {
                        TrainerGender.entries.forEach { choice ->
                            DropdownMenuItem(text = { Text(choice.label) }, onClick = {
                                gender = choice; genderMenu = false; feedback = null
                            })
                        }
                    }
                }
                Box(Modifier.fillMaxWidth()) {
                    DeviceButton("Language: ${language.label}", { languageMenu = true }, Modifier.fillMaxWidth(), style = DeviceButtonStyle.SECONDARY)
                    DropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
                        TrainerLanguage.entries.forEach { choice ->
                            DropdownMenuItem(text = { Text(choice.label) }, onClick = {
                                language = choice; languageMenu = false; feedback = null
                            })
                        }
                    }
                }
                validationError?.let { Text(it, color = ShellRedDark, style = MaterialTheme.typography.bodySmall) }
                feedback?.let { Text(it, color = IndicatorGreen, fontWeight = FontWeight.Bold) }
                DeviceButton(
                    "Save profile",
                    onClick = { onSave(draft.normalized()); feedback = "Trainer profile saved" },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = validationError == null,
                )
            }
        }
    }
}

@Composable
private fun GameModulePanel(module: GameModule, onOpen: (() -> Unit)?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = if (module.primary) DeviceAmber else CreamPanel,
        contentColor = DeviceInk,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(if (module.primary) 3.dp else 2.dp, DeviceBezel),
        shadowElevation = if (module.primary) 7.dp else 3.dp,
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val skin = when (module.title) {
                "FireRed / LeafGreen" -> GameSkins.FRLG
                "Legends: Z-A" -> GameSkins.ZA
                else -> null
            }
            skin?.let { Surface(color = it.accent, modifier = Modifier.fillMaxWidth().height(4.dp)) {} }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(module.generation, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                StatusBadge(if (onOpen != null) "Available" else "Planned", if (onOpen != null) StatusTone.POSITIVE else StatusTone.NEUTRAL)
            }
            Text(module.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text(module.detail, style = MaterialTheme.typography.bodyMedium)
            module.researchItems.forEach { item ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(item, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    StatusBadge("Research", StatusTone.NEUTRAL)
                }
            }
            if (onOpen != null) DeviceButton("Open module", onOpen, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun FrlgOperationsScreen(
    onOpenWalkThroughWalls: () -> Unit,
    onOpenSaveBackup: () -> Unit,
    onOpenSaveRestore: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredDeviceList(modifier) {
        item { GameScreenTitle(GameSkins.FRLG, "Games / FRLG", "FireRed / LeafGreen", "Choose an operation") }
        item { DeviceButton("Back to games", onBack, Modifier.fillMaxWidth()) }
        item {
            OperationCategory("Game Boosts") {
                OperationRow(
                    "Walk Through Walls",
                    "Install the R-button movement boost.",
                    "Verified",
                    StatusTone.POSITIVE,
                    onOpenWalkThroughWalls,
                )
                PlannedOperation("Speed Up", "Planned")
                PlannedOperation("No Wild Encounters", "Planned")
                PlannedOperation("Shiny Countdown", "Planned")
                PlannedOperation("Lead IV Display", "Planned")
                PlannedOperation("Pokémon Follower", "Planned")
            }
        }
        item {
            OperationCategory("Mystery Gift") {
                PlannedOperation("Wonder News", "Planned")
                PlannedOperation("Pokémon Gift", "Planned")
                PlannedOperation("Wonder Card", "Planned")
            }
        }
        item {
            OperationCategory("Link") {
                PlannedOperation("Direct Corner Trade", "Planned")
                PlannedOperation("Union Room", "Planned")
            }
        }
        item {
            OperationCategory("Save tools") {
                OperationRow(
                    "Save Backup",
                    "Copy and safely store the complete cartridge save.",
                    "Verified",
                    StatusTone.POSITIVE,
                    onOpenSaveBackup,
                )
                OperationRow(
                    "Save Restore",
                    "Validate and write a selected .sav file.",
                    "Verified",
                    StatusTone.POSITIVE,
                    onOpenSaveRestore,
                )
            }
        }
    }
}

@Composable
private fun SaveRestoreScreen(
    connectionState: SerialConnectionState,
    esp32State: Esp32HandshakeState,
    keysState: ProdKeysState,
    state: FrlgSaveRestoreUiState,
    radioState: Esp32RadioState,
    onImportKeys: () -> Unit,
    onSelect: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val availability = FrlgOperationStartGate.evaluate(
        keysAvailable = keysState.phase == ProdKeysPhase.AVAILABLE,
        handshakeVerified = esp32State.phase == Esp32HandshakePhase.VERIFIED,
        radioReady = radioState.phase == Esp32RadioPhase.READY,
        frlgOperationOwned = state.running,
        otherRadioOperationOwned = false,
        radioDetail = radioState.detail ?: "Connect and verify the ESP32 before restoring",
    )
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Restore cartridge save?") },
            text = { Text("This will write the selected save to the cartridge. Keep a verified backup of the current save.") },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
            confirmButton = { TextButton(onClick = { confirming = false; onStart() }) { Text("Restore") } },
        )
    }
    CenteredDeviceList(modifier) {
        item { GameScreenTitle(GameSkins.FRLG, "FRLG / Save tools", "Save Restore", "Select, validate, connect, and restore") }
        item { DeviceButton("Back to operations", onBack, Modifier.fillMaxWidth()) }
        item {
            ShellPanel("Selected save") {
                StatusBadge("Physically verified", StatusTone.POSITIVE)
                OverviewRow("1. Select save", state.filename ?: "No file selected", if (state.filename != null) StatusTone.POSITIVE else StatusTone.NEUTRAL)
                state.filename?.let {
                    Text("Size: ${state.fileSize} bytes (${state.save?.size?.div(1024) ?: 0} KB)", style = MaterialTheme.typography.bodySmall)
                }
                OverviewRow(
                    "2. Validate save",
                    if (state.readyFile) "Whole slot valid" else "Waiting",
                    if (state.readyFile) StatusTone.POSITIVE else StatusTone.NEUTRAL,
                )
                state.validationDetail?.let {
                    Text(it, color = if (state.readyFile) DeviceInk.copy(alpha = 0.72f) else IndicatorRed)
                }
                DeviceButton("Select .sav", onSelect, Modifier.fillMaxWidth(), enabled = !state.running)
            }
        }
        item {
            BezelDisplay("Restore status") {
                DisplayStatus(
                    when {
                        state.operation.phase == com.dedhapp3n.pokeldn.frlg.FrlgOperationPhase.COMPLETED -> "Restore complete"
                        state.operation.phase == com.dedhapp3n.pokeldn.frlg.FrlgOperationPhase.FAILED -> "Restore failed"
                        state.operation.phase == com.dedhapp3n.pokeldn.frlg.FrlgOperationPhase.REFUSED -> "Restore refused"
                        state.running -> state.operation.phase.label
                        else -> "Ready to connect"
                    },
                    when {
                        state.operation.phase == com.dedhapp3n.pokeldn.frlg.FrlgOperationPhase.COMPLETED -> StatusTone.POSITIVE
                        state.operation.phase == com.dedhapp3n.pokeldn.frlg.FrlgOperationPhase.FAILED -> StatusTone.ERROR
                        state.operation.phase == com.dedhapp3n.pokeldn.frlg.FrlgOperationPhase.REFUSED -> StatusTone.ERROR
                        state.running -> StatusTone.WARNING
                        else -> StatusTone.NEUTRAL
                    },
                )
                OverviewRow(
                    "3. Cartridge",
                    restoreCartridgeLabel(state.cartridge, state.operation.phase, state.running),
                    when {
                        state.totalSectors > 0 -> StatusTone.POSITIVE
                        state.operation.phase == com.dedhapp3n.pokeldn.frlg.FrlgOperationPhase.FAILED -> StatusTone.ERROR
                        else -> StatusTone.NEUTRAL
                    },
                )
                if (state.totalSectors > 0) DisplayStatus("Save and cartridge compatible", StatusTone.POSITIVE)
                state.operation.detail?.let { Text(it, color = ScreenMuted) }
                if (state.totalSectors > 0) Text("Progress: ${state.sectorsWritten} / ${state.totalSectors} sectors", color = ScreenMuted)
                if (state.operation.phase == com.dedhapp3n.pokeldn.frlg.FrlgOperationPhase.FAILED && state.totalSectors > 0) {
                    Text("Last completed: ${state.sectorsWritten} / ${state.totalSectors} sectors", color = ScreenMuted)
                }
            }
        }
        item {
            ShellPanel("Restore") {
                Text("Compatibility is checked before the cartridge is written.", style = MaterialTheme.typography.bodySmall)
                if (state.running) {
                    DeviceButton("Cancel", onCancel, Modifier.fillMaxWidth(), style = DeviceButtonStyle.SECONDARY)
                } else {
                    DeviceButton(
                        "Restore save",
                        { confirming = true },
                        Modifier.fillMaxWidth(),
                        enabled = state.readyFile && connectionState.phase == SerialConnectionPhase.CONNECTED && availability.enabled,
                    )
                    when {
                        !state.readyFile -> Text("Select a valid save to continue.", style = MaterialTheme.typography.bodySmall, color = ScreenMuted)
                        !availability.enabled -> Text(availability.reason.orEmpty(), style = MaterialTheme.typography.bodySmall, color = ScreenMuted)
                    }
                }
                if (keysState.phase != ProdKeysPhase.AVAILABLE) {
                    DeviceButton("Import prod.keys", onImportKeys, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

internal fun restoreCartridgeLabel(
    cartridge: String?,
    phase: com.dedhapp3n.pokeldn.frlg.FrlgOperationPhase,
    running: Boolean,
): String = cartridge ?: when {
    phase == com.dedhapp3n.pokeldn.frlg.FrlgOperationPhase.FAILED -> "Connection lost"
    running -> "Waiting for console"
    else -> "Not detected"
}

@Composable
private fun SaveBackupScreen(
    connectionState: SerialConnectionState,
    esp32State: Esp32HandshakeState,
    keysState: ProdKeysState,
    backupState: FrlgSaveBackupUiState,
    radioState: Esp32RadioState,
    onImportKeys: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val operation = backupState.operation
    val availability = FrlgOperationStartGate.evaluate(
        keysAvailable = keysState.phase == ProdKeysPhase.AVAILABLE,
        handshakeVerified = esp32State.phase == Esp32HandshakePhase.VERIFIED,
        radioReady = radioState.phase == Esp32RadioPhase.READY,
        frlgOperationOwned = operation.running,
        otherRadioOperationOwned = false,
        radioDetail = radioState.detail ?: "Connect and verify the ESP32 before starting a backup",
    )
    val adapterReady = connectionState.phase == SerialConnectionPhase.CONNECTED &&
        esp32State.phase == Esp32HandshakePhase.VERIFIED && radioState.phase == Esp32RadioPhase.READY
    val closeWarning = backupState.closeResult in setOf(
        FrlgSaveCloseResult.CLEANUP_WARNING,
        FrlgSaveCloseResult.TRANSPORT_FAILURE,
    )
    CenteredDeviceList(modifier) {
        item { GameScreenTitle(GameSkins.FRLG, "FRLG / Save tools", "Save Backup", "Read-only 128 KB cartridge backup") }
        item { DeviceButton("Back to operations", onBack, Modifier.fillMaxWidth()) }
        item {
            BezelDisplay("Backup status") {
                if (backupState.dataAvailable) {
                    DisplayStatus("Backup complete", StatusTone.POSITIVE)
                    backupState.cartridge?.let { Text(it, color = ScreenText, fontWeight = FontWeight.Bold) }
                    Text("128 KB", color = ScreenMuted)
                    DisplayStatus(
                        if (backupState.hasWholeSlot == true) "Whole save verified" else "Save copied with warning",
                        if (backupState.hasWholeSlot == true) StatusTone.POSITIVE else StatusTone.WARNING,
                    )
                    DisplayStatus(
                        if (backupState.internalFilename != null) "Saved successfully" else "Storage warning",
                        if (backupState.internalFilename != null) StatusTone.POSITIVE else StatusTone.WARNING,
                    )
                    backupState.internalFilename?.let { Text(it, color = ScreenMuted) }
                    if (closeWarning) {
                        StatusBadge("Connection ended unexpectedly", StatusTone.WARNING)
                        operation.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ScreenMuted) }
                    }
                } else {
                    DisplayStatus(
                        operation.phase.label,
                        when {
                            operation.phase == com.dedhapp3n.pokeldn.frlg.FrlgOperationPhase.FAILED -> StatusTone.ERROR
                            operation.running -> StatusTone.WARNING
                            else -> StatusTone.NEUTRAL
                        },
                    )
                    operation.detail?.let { Text(it, color = ScreenMuted) }
                    backupState.cartridge?.let { Text("Cartridge: $it", color = ScreenMuted) }
                    Text("Progress: ${backupState.bytesReceived / 1024} / ${backupState.totalBytes / 1024} KB", color = ScreenMuted)
                }
                DisplayStatus(
                    when { operation.running -> "ESP32 active"; adapterReady -> "ESP32 ready"; else -> "ESP32 disconnected" },
                    when { operation.running || adapterReady -> StatusTone.POSITIVE; else -> StatusTone.NEUTRAL },
                )
            }
        }
        item {
            ShellPanel("Backup file") {
                Text("Copies the cartridge save without writing to it.")
                if (backupState.running) {
                    DeviceButton("Cancel", onCancel, Modifier.fillMaxWidth(), style = DeviceButtonStyle.SECONDARY)
                } else {
                    DeviceButton(
                        "Start backup",
                        onStart,
                        Modifier.fillMaxWidth(),
                        enabled = connectionState.phase == SerialConnectionPhase.CONNECTED && availability.enabled,
                    )
                    if (!availability.enabled && !backupState.dataAvailable) {
                        Text(availability.reason.orEmpty(), style = MaterialTheme.typography.bodySmall, color = ScreenMuted)
                    }
                }
                if (backupState.dataAvailable) {
                    backupState.externalFilename?.let {
                        Text("Backup folder: $it", style = MaterialTheme.typography.bodySmall)
                    }
                    DeviceButton(
                        "Save a copy",
                        onExport,
                        Modifier.fillMaxWidth(),
                        enabled = backupState.exportPhase != FrlgSaveExportPhase.EXPORTING,
                    )
                }
                backupState.exportDetail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ScreenMuted) }
                if (keysState.phase != ProdKeysPhase.AVAILABLE) {
                    DeviceButton("Import prod.keys", onImportKeys, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun WalkThroughWallsScreen(
    connectionState: SerialConnectionState,
    esp32State: Esp32HandshakeState,
    keysState: ProdKeysState,
    operationState: FrlgOperationState,
    radioState: Esp32RadioState,
    onImportKeys: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val availability = FrlgOperationStartGate.evaluate(
        keysAvailable = keysState.phase == ProdKeysPhase.AVAILABLE,
        handshakeVerified = esp32State.phase == Esp32HandshakePhase.VERIFIED,
        radioReady = radioState.phase == Esp32RadioPhase.READY,
        frlgOperationOwned = operationState.running,
        otherRadioOperationOwned = false,
        radioDetail = radioState.detail ?: "ESP32 radio is ${radioState.phase.name.lowercase().replace('_', ' ')}",
    )
    val adapterReady = connectionState.phase == SerialConnectionPhase.CONNECTED &&
        esp32State.phase == Esp32HandshakePhase.VERIFIED && radioState.phase == Esp32RadioPhase.READY
    CenteredDeviceList(modifier) {
        item { GameScreenTitle(GameSkins.FRLG, "FRLG / Game Boost", "Walk Through Walls", "Hold R while moving") }
        item { DeviceButton("Back to operations", onBack, Modifier.fillMaxWidth()) }
        item {
            BezelDisplay("Operation status") {
                DisplayStatus(
                    operationState.phase.label,
                    when (operationState.phase) {
                        com.dedhapp3n.pokeldn.frlg.FrlgOperationPhase.COMPLETED -> StatusTone.POSITIVE
                        com.dedhapp3n.pokeldn.frlg.FrlgOperationPhase.FAILED -> StatusTone.ERROR
                        else -> if (operationState.running) StatusTone.WARNING else StatusTone.NEUTRAL
                    },
                )
                operationState.detail?.let { Text(it, color = ScreenMuted) }
                StatusBadge("Physically verified", StatusTone.POSITIVE)
                Text("Game: FireRed / LeafGreen", color = ScreenMuted)
                Text("Activation: hold ${WalkThroughWallsPreset.ACTIVATION_BUTTON}", color = ScreenMuted)
                DisplayStatus(
                    if (adapterReady) "ESP32 ready" else "ESP32 disconnected",
                    if (adapterReady) StatusTone.POSITIVE else StatusTone.NEUTRAL,
                )
                DisplayStatus(
                    when (keysState.phase) {
                        ProdKeysPhase.AVAILABLE -> "Keys available"
                        ProdKeysPhase.INVALID -> "Keys invalid"
                        ProdKeysPhase.MISSING -> "Keys missing"
                    },
                    when (keysState.phase) {
                        ProdKeysPhase.AVAILABLE -> StatusTone.POSITIVE
                        ProdKeysPhase.INVALID -> StatusTone.ERROR
                        ProdKeysPhase.MISSING -> StatusTone.WARNING
                    },
                )
            }
        }
        item {
            ShellPanel("Requirements") {
                Text("Import your own prod.keys. The app stores the validated file only in private app storage.")
                DeviceButton("Import prod.keys", onImportKeys, Modifier.fillMaxWidth())
                Text("On the GBA, enable Mystery Gift, open Wonder Cards, then choose Friend. Start here before selecting the host on the console.")
                Text("The boost lasts until the game restarts or the console is powered off.",
                    style = MaterialTheme.typography.bodySmall, color = DeviceInk.copy(alpha = 0.72f))
                if (operationState.running) {
                    DeviceButton("Cancel", onCancel, Modifier.fillMaxWidth(), style = DeviceButtonStyle.SECONDARY)
                } else {
                    DeviceButton(
                        "Start",
                        onStart,
                        Modifier.fillMaxWidth(),
                        enabled = connectionState.phase == SerialConnectionPhase.CONNECTED && availability.enabled,
                    )
                    if (!availability.enabled) {
                        Text(availability.reason.orEmpty(), style = MaterialTheme.typography.bodySmall, color = ScreenMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun OperationCategory(title: String, content: @Composable () -> Unit) {
    ShellPanel(title) { content() }
}

@Composable
private fun OperationRow(
    title: String,
    detail: String,
    badge: String,
    tone: StatusTone,
    onClick: () -> Unit,
) {
    Surface(
        color = CreamPanelDark,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(2.dp, DeviceBezel),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(title, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                StatusBadge(badge, tone)
            }
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PlannedOperation(title: String, status: String) {
    Surface(
        color = CreamPanelDark.copy(alpha = 0.72f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, DeviceBezel),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            StatusBadge(status, StatusTone.NEUTRAL)
        }
    }
}
