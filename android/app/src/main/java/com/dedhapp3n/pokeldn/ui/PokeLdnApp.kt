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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.dedhapp3n.pokeldn.esp32.Esp32HandshakePhase
import com.dedhapp3n.pokeldn.esp32.Esp32HandshakeState
import com.dedhapp3n.pokeldn.esp32.RawCaptureState
import com.dedhapp3n.pokeldn.esp32.RawReadBufferMode
import com.dedhapp3n.pokeldn.frlg.FrlgOperationState
import com.dedhapp3n.pokeldn.frlg.ProdKeysPhase
import com.dedhapp3n.pokeldn.frlg.ProdKeysState
import com.dedhapp3n.pokeldn.frlg.WalkThroughWallsPreset
import com.dedhapp3n.pokeldn.ldn.LdnApTestState
import com.dedhapp3n.pokeldn.ui.theme.CreamPanel
import com.dedhapp3n.pokeldn.ui.theme.CreamPanelDark
import com.dedhapp3n.pokeldn.ui.theme.DeviceAmber
import com.dedhapp3n.pokeldn.ui.theme.DeviceBezel
import com.dedhapp3n.pokeldn.ui.theme.DeviceInk
import com.dedhapp3n.pokeldn.ui.theme.IndicatorCyan
import com.dedhapp3n.pokeldn.ui.theme.IndicatorGreen
import com.dedhapp3n.pokeldn.ui.theme.IndicatorRed
import com.dedhapp3n.pokeldn.ui.theme.ScreenMuted
import com.dedhapp3n.pokeldn.ui.theme.ShellRed
import com.dedhapp3n.pokeldn.ui.theme.ShellRedBright
import com.dedhapp3n.pokeldn.ui.theme.ShellRedDark
import com.dedhapp3n.pokeldn.usb.SerialConnectionPhase
import com.dedhapp3n.pokeldn.usb.SerialConnectionState
import com.dedhapp3n.pokeldn.usb.UsbDeviceInfo
import com.dedhapp3n.pokeldn.usb.UsbScanResult

private enum class AppDestination(val label: String) {
    HOME("Home"),
    GAMES("Games"),
    OPTIONS("Optionen"),
    DIAGNOSTICS("Diagnostics"),
}

private data class GameModule(
    val title: String,
    val generation: String,
    val detail: String,
    val primary: Boolean = false,
)

private val gameModules = listOf(
    GameModule("FireRed / LeafGreen", "GEN III", "First Android focus: Mystery Gift and direct trade workflows.", true),
    GameModule("Ruby / Sapphire / Emerald", "GEN III", "Planned Game Boy Advance link workflows."),
    GameModule("Diamond / Pearl / Platinum", "GEN IV", "Planned module. Not available yet."),
    GameModule("HeartGold / SoulSilver", "GEN IV", "Planned module. Not available yet."),
    GameModule("Black / White", "GEN V", "Long-term planned module."),
    GameModule("Scarlet / Violet", "GEN IX", "Future modern-game workflow ideas."),
    GameModule("HOME-style tools", "UTILITY", "Future import, export, and collection workflows."),
)

@Composable
fun PokeLdnApp(
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
    ldnApTestState: LdnApTestState,
    onStartLdnApTest: () -> Unit,
    onStopLdnApTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var destination by rememberSaveable { mutableStateOf(AppDestination.HOME) }
    var selectedGame by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedOperation by rememberSaveable { mutableStateOf<String?>(null) }
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
                onConnectEsp32 = onConnectEsp32,
                onOpenGames = { destination = AppDestination.GAMES },
                onOpenDiagnostics = { destination = AppDestination.DIAGNOSTICS },
                modifier = Modifier.padding(innerPadding),
            )
            AppDestination.GAMES -> when {
                selectedOperation == WalkThroughWallsPreset.BOOST_ID -> WalkThroughWallsScreen(
                    connectionState = connectionState,
                    esp32State = esp32State,
                    keysState = prodKeysState,
                    operationState = FrlgOperationState(),
                    onImportKeys = onImportProdKeys,
                    onBack = { selectedOperation = null },
                    modifier = Modifier.padding(innerPadding),
                )
                selectedGame == "frlg" -> FrlgOperationsScreen(
                    onOpenWalkThroughWalls = { selectedOperation = WalkThroughWallsPreset.BOOST_ID },
                    onBack = { selectedGame = null },
                    modifier = Modifier.padding(innerPadding),
                )
                else -> GamesScreen(
                    onOpenFrlg = { selectedGame = "frlg" },
                    modifier = Modifier.padding(innerPadding),
                )
            }
            AppDestination.OPTIONS -> OptionsScreen(
                prodKeysState = prodKeysState,
                onImportProdKeys = onImportProdKeys,
                modifier = Modifier.padding(innerPadding),
            )
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
    onConnectEsp32: (String) -> Unit,
    onOpenGames: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val devices = (result as? UsbScanResult.Devices)?.items.orEmpty()
    val serialDevice = devices.firstOrNull { it.deviceName == connectionState.deviceName }
        ?: devices.firstOrNull { it.serialSupported }
    CenteredDeviceList(modifier) {
        item { DeviceScreenTitle(null, "Link console", null) }
        item {
            MainAdapterDisplay(
                device = serialDevice,
                connectionState = connectionState,
                esp32State = esp32State,
                onConnectEsp32 = onConnectEsp32,
            )
        }
        item {
            ShellPanel("Control deck") {
                DeviceButton("Open games", onOpenGames, Modifier.fillMaxWidth(), style = DeviceButtonStyle.SECONDARY)
                Surface(
                    color = CreamPanelDark,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(2.dp, DeviceBezel),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        IndicatorLight(IndicatorRed, 10.dp)
                        Column(Modifier.weight(1f)) {
                            Text("FIRERED / LEAFGREEN", fontWeight = FontWeight.Black)
                            Text("Current focus · workflows in development", style = MaterialTheme.typography.bodySmall)
                        }
                        StatusBadge("Soon", StatusTone.WARNING)
                    }
                }
                DeviceButton("Open diagnostics", onOpenDiagnostics, Modifier.fillMaxWidth())
                Text(
                    "Operations and saved-data tools will unlock as their Android workflows are completed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DeviceInk.copy(alpha = 0.72f),
                )
            }
        }
    }
}

@Composable
private fun MainAdapterDisplay(
    device: UsbDeviceInfo?,
    connectionState: SerialConnectionState,
    esp32State: Esp32HandshakeState,
    onConnectEsp32: (String) -> Unit,
) {
    val serialConnected = connectionState.phase == SerialConnectionPhase.CONNECTED
    val verified = serialConnected && esp32State.phase == Esp32HandshakePhase.VERIFIED && esp32State.info != null
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
                        verified -> "ESP32 ready"
                        esp32State.phase == Esp32HandshakePhase.TESTING -> "Verifying link"
                        connectionState.phase == SerialConnectionPhase.PERMISSION_DENIED -> "Permission denied"
                        failed -> "Connection failed"
                        serialConnected -> "Serial link online"
                        busy -> "Link in progress"
                        device != null -> "ESP32 detected"
                        else -> "No ESP32 detected"
                    },
                    when {
                        verified -> StatusTone.POSITIVE
                        busy -> StatusTone.WARNING
                        failed || connectionState.phase == SerialConnectionPhase.PERMISSION_DENIED -> StatusTone.ERROR
                        else -> StatusTone.NEUTRAL
                    },
                )
                Text(
                    when {
                        verified -> "PokeLDN radio verified. Choose a game to continue."
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
        if (!verified && serialConnected) {
            esp32State.detail?.let { Text(it, color = IndicatorRed) }
            DeviceButton(
                if (esp32State.phase == Esp32HandshakePhase.FAILED) "Retry connection" else "Connect ESP32",
                { device?.let { onConnectEsp32(it.deviceName) } },
                Modifier.fillMaxWidth(),
                enabled = !busy && device != null,
                style = DeviceButtonStyle.DISPLAY,
            )
        } else if (!verified && device != null) {
            DeviceButton(
                "Connect ESP32",
                { onConnectEsp32(device.deviceName) },
                Modifier.fillMaxWidth(),
                enabled = !busy && device.serialSupported,
                style = DeviceButtonStyle.DISPLAY,
            )
        }
        connectionState.detail?.let { Text(it, color = IndicatorRed) }
    }
}

@Composable
private fun GamesScreen(onOpenFrlg: () -> Unit, modifier: Modifier = Modifier) {
    CenteredDeviceList(modifier) {
        item {
            DeviceScreenTitle(null, "Game modules", "FireRed and LeafGreen are the first Android focus")
        }
        item {
            BezelDisplay("Selected development target") {
                DisplayStatus("FireRed / LeafGreen", StatusTone.WARNING)
                Text(
                    "This module is being prepared first. No Android game operation is available in this build yet.",
                    color = ScreenMuted,
                )
                StatusBadge("In development", StatusTone.WARNING)
            }
        }
        item {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = if (maxWidth >= 600.dp) 2 else 1
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    gameModules.chunked(columns).forEach { rowModules ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            rowModules.forEach { module ->
                                GameModulePanel(module, if (module.primary) onOpenFrlg else null, Modifier.weight(1f))
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
private fun OptionsScreen(
    prodKeysState: ProdKeysState,
    onImportProdKeys: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredDeviceList(modifier) {
        item { DeviceScreenTitle(null, "Optionen", "App settings and local data") }
        item {
            ShellPanel("Trainer") {
                Text("Trainer settings will be available in a later milestone.")
            }
        }
        item {
            ShellPanel("Switch Keys") {
                StatusBadge(
                    when (prodKeysState.phase) {
                        ProdKeysPhase.AVAILABLE -> "Keys valid"
                        ProdKeysPhase.INVALID -> "Keys invalid"
                        ProdKeysPhase.MISSING -> "Keys missing"
                    },
                    when (prodKeysState.phase) {
                        ProdKeysPhase.AVAILABLE -> StatusTone.POSITIVE
                        ProdKeysPhase.INVALID -> StatusTone.ERROR
                        ProdKeysPhase.MISSING -> StatusTone.WARNING
                    },
                )
                prodKeysState.detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                Text("Your imported prod.keys file is stored in private app storage.")
                DeviceButton("Import prod.keys", onImportProdKeys, Modifier.fillMaxWidth())
            }
        }
        item {
            ShellPanel("Storage") {
                Text("Local file and backup settings will be added as storage workflows become available.")
            }
        }
        item {
            ShellPanel("Device / Firmware") {
                Text("Device information is available in Diagnostics. Firmware updates are not available yet.")
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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(module.generation, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                StatusBadge(if (module.primary) "Focus" else "Planned", if (module.primary) StatusTone.WARNING else StatusTone.NEUTRAL)
            }
            Text(module.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text(module.detail, style = MaterialTheme.typography.bodyMedium)
            DeviceButton(
                if (module.primary) "Open module" else "Coming later",
                onClick = onOpen ?: {},
                modifier = Modifier.fillMaxWidth(),
                enabled = onOpen != null,
            )
        }
    }
}

@Composable
private fun FrlgOperationsScreen(
    onOpenWalkThroughWalls: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredDeviceList(modifier) {
        item { DeviceScreenTitle("Games / FRLG", "FireRed / LeafGreen", "Choose an operation") }
        item { DeviceButton("Back to games", onBack, Modifier.fillMaxWidth()) }
        item {
            OperationCategory("Game Boosts") {
                OperationRow(
                    "Walk Through Walls",
                    "Hold R to pass through walls, trees, and water.",
                    "First port target",
                    StatusTone.WARNING,
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
                PlannedOperation("Save Backup", "Planned")
                PlannedOperation("Save Restore", "Experimental")
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
    onImportKeys: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val adapterReady = connectionState.phase == SerialConnectionPhase.CONNECTED &&
        esp32State.phase == Esp32HandshakePhase.VERIFIED
    CenteredDeviceList(modifier) {
        item { DeviceScreenTitle("FRLG / Game Boost", "Walk Through Walls", "Existing upstream noclip resident hook") }
        item { DeviceButton("Back to operations", onBack, Modifier.fillMaxWidth()) }
        item {
            BezelDisplay("Operation status") {
                DisplayStatus(operationState.phase.label, if (operationState.running) StatusTone.WARNING else StatusTone.NEUTRAL)
                Text("Game: FireRed / LeafGreen", color = ScreenMuted)
                Text("Activation: hold ${WalkThroughWallsPreset.ACTIVATION_BUTTON}", color = ScreenMuted)
                DisplayStatus(if (adapterReady) "ESP32 ready" else "ESP32 not ready", if (adapterReady) StatusTone.POSITIVE else StatusTone.ERROR)
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
                keysState.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ScreenMuted) }
            }
        }
        item {
            ShellPanel("Requirements") {
                Text("Import your own prod.keys. The app stores the validated file only in private app storage.")
                DeviceButton("Import prod.keys", onImportKeys, Modifier.fillMaxWidth())
                Text(
                    "Wireless delivery is not enabled in this build: the Android LDN, PIA, RFU, and Mystery Gift layers still need to be ported. Start remains disabled so the app cannot claim a boost was sent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DeviceInk.copy(alpha = 0.72f),
                )
                DeviceButton("Start", {}, Modifier.fillMaxWidth(), enabled = false, style = DeviceButtonStyle.SECONDARY)
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
