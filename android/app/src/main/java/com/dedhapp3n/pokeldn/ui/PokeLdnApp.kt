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
import androidx.compose.foundation.layout.size
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

private enum class AppDestination(val label: String, val code: String) {
    HOME("Home", "01"),
    GAMES("Games", "02"),
    DIAGNOSTICS("Diagnostics", "03"),
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
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onTestEsp32: () -> Unit,
    onDiagnosticBaudSelected: (Int) -> Unit,
    onReadBufferModeSelected: (RawReadBufferMode) -> Unit,
    onCaptureRaw: (Int, RawReadBufferMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var destination by rememberSaveable { mutableStateOf(AppDestination.HOME) }
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
                onScan = onScan,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onTestEsp32 = onTestEsp32,
                onOpenGames = { destination = AppDestination.GAMES },
                onOpenDiagnostics = { destination = AppDestination.DIAGNOSTICS },
                modifier = Modifier.padding(innerPadding),
            )
            AppDestination.GAMES -> GamesScreen(modifier = Modifier.padding(innerPadding))
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
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun DeviceHeader() {
    Surface(color = ShellRedBright, contentColor = CreamPanel, shadowElevation = 8.dp) {
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
    Surface(color = ShellRedDark, contentColor = CreamPanel, shadowElevation = 10.dp) {
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
                        Text(destination.code, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black)
                        Text(
                            destination.label.uppercase(),
                            modifier = Modifier.padding(start = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
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
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onTestEsp32: () -> Unit,
    onOpenGames: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val devices = (result as? UsbScanResult.Devices)?.items.orEmpty()
    val serialDevice = devices.firstOrNull { it.deviceName == connectionState.deviceName }
        ?: devices.firstOrNull { it.serialSupported }
    CenteredDeviceList(modifier) {
        item { DeviceScreenTitle("System 01 / Main", "Link console", "Adapter control and game access") }
        item {
            MainAdapterDisplay(
                device = serialDevice,
                connectionState = connectionState,
                esp32State = esp32State,
                onScan = onScan,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onTestEsp32 = onTestEsp32,
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
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onTestEsp32: () -> Unit,
) {
    val serialConnected = connectionState.phase == SerialConnectionPhase.CONNECTED
    val verified = serialConnected && esp32State.phase == Esp32HandshakePhase.VERIFIED && esp32State.info != null
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
                        verified -> "Adapter ready"
                        esp32State.phase == Esp32HandshakePhase.TESTING -> "Verifying link"
                        serialConnected -> "Serial link online"
                        busy -> "Link in progress"
                        device != null -> "Adapter detected"
                        else -> "No adapter ready"
                    },
                    when {
                        verified -> StatusTone.POSITIVE
                        busy -> StatusTone.WARNING
                        esp32State.phase == Esp32HandshakePhase.FAILED -> StatusTone.ERROR
                        else -> StatusTone.NEUTRAL
                    },
                )
                Text(
                    when {
                        verified -> "PokeLDN radio verified. Choose a game to continue."
                        serialConnected -> "Run verification to identify the connected ESP32."
                        device != null -> "USB serial hardware found. Connect it to continue."
                        else -> "Attach the ESP32 through USB OTG, then scan again."
                    },
                    color = ScreenMuted,
                )
            }
            if (busy) CircularProgressIndicator(Modifier.size(30.dp), color = IndicatorCyan, strokeWidth = 3.dp)
        }
        if (verified) {
            val info = checkNotNull(esp32State.info)
            Surface(
                color = ScreenText.copy(alpha = 0.08f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, ScreenMuted.copy(alpha = 0.5f)),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    DetailLine("Firmware", info.firmwareVersion.ifBlank { "Verified" }, dark = true)
                    DetailLine("Protocol", info.protocolVersion.toString(), dark = true)
                    DetailLine("ESP32 revision", info.chipRevision.toString(), dark = true)
                }
            }
            DeviceButton("Disconnect adapter", onDisconnect, Modifier.fillMaxWidth(), style = DeviceButtonStyle.DISPLAY)
        } else if (serialConnected) {
            esp32State.detail?.let { Text(it, color = IndicatorRed) }
            DeviceButton(
                if (esp32State.phase == Esp32HandshakePhase.FAILED) "Retry verification" else "Verify ESP32",
                onTestEsp32,
                Modifier.fillMaxWidth(),
                enabled = !busy,
                style = DeviceButtonStyle.DISPLAY,
            )
        } else if (device != null) {
            DeviceButton(
                "Connect adapter",
                { onConnect(device.deviceName) },
                Modifier.fillMaxWidth(),
                enabled = !busy && device.serialSupported,
                style = DeviceButtonStyle.DISPLAY,
            )
        } else {
            DeviceButton("Scan for adapter", onScan, Modifier.fillMaxWidth(), style = DeviceButtonStyle.DISPLAY)
        }
        connectionState.detail?.let { Text(it, color = IndicatorRed) }
    }
}

@Composable
private fun GamesScreen(modifier: Modifier = Modifier) {
    CenteredDeviceList(modifier) {
        item {
            DeviceScreenTitle("Catalog 02 / Games", "Game modules", "FireRed and LeafGreen are the first Android focus")
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
                            rowModules.forEach { module -> GameModulePanel(module, Modifier.weight(1f)) }
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
private fun GameModulePanel(module: GameModule, modifier: Modifier = Modifier) {
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
                if (module.primary) "In development" else "Coming later",
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
                enabled = false,
            )
        }
    }
}
