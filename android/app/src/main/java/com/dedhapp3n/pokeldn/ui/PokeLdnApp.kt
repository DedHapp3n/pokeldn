package com.dedhapp3n.pokeldn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dedhapp3n.pokeldn.esp32.Esp32HandshakePhase
import com.dedhapp3n.pokeldn.esp32.Esp32HandshakeState
import com.dedhapp3n.pokeldn.esp32.RawCapturePhase
import com.dedhapp3n.pokeldn.esp32.RawCaptureState
import com.dedhapp3n.pokeldn.esp32.RawReadBufferMode
import com.dedhapp3n.pokeldn.esp32.formatMac
import com.dedhapp3n.pokeldn.usb.SerialConnectionPhase
import com.dedhapp3n.pokeldn.usb.SerialConnectionState
import com.dedhapp3n.pokeldn.usb.UsbDeviceInfo
import com.dedhapp3n.pokeldn.usb.UsbScanResult
import com.dedhapp3n.pokeldn.usb.UsbSerialTransport
import com.dedhapp3n.pokeldn.usb.devicePhase
import com.dedhapp3n.pokeldn.usb.isCp210xBridge
import com.dedhapp3n.pokeldn.usb.usbIdHex

private enum class AppDestination(val label: String, val symbol: String) {
    HOME("Home", "H"),
    GAMES("Games", "G"),
    DIAGNOSTICS("Diagnostics", "D"),
}

private enum class StatusTone { NEUTRAL, POSITIVE, WARNING, ERROR }

private val diagnosticBaudRates = listOf(115200, 230400, 460800, 921600)

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
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                AppDestination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (destination == item) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.size(28.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        item.symbol,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (destination == item) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                        label = { Text(item.label) },
                    )
                }
            }
        },
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
    CenteredList(modifier) {
        item { AppHeader() }
        item {
            Esp32ConnectionCard(
                device = serialDevice,
                connectionState = connectionState,
                esp32State = esp32State,
                onScan = onScan,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onTestEsp32 = onTestEsp32,
                onOpenDiagnostics = onOpenDiagnostics,
            )
        }
        item {
            SectionHeading("Quick status", "The essentials at a glance")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                QuickStatusCard(
                    title = "USB",
                    value = when {
                        connectionState.phase == SerialConnectionPhase.CONNECTED -> "Connected"
                        serialDevice != null -> "Detected"
                        else -> "Not detected"
                    },
                    modifier = Modifier.weight(1f),
                )
                QuickStatusCard(
                    title = "Protocol",
                    value = when (esp32State.phase) {
                        Esp32HandshakePhase.VERIFIED -> "Verified"
                        Esp32HandshakePhase.TESTING -> "Verifying"
                        else -> "Not verified"
                    },
                    modifier = Modifier.weight(1f),
                )
                QuickStatusCard(
                    title = "Firmware",
                    value = esp32State.info?.firmwareVersion?.ifBlank { null } ?: "Unknown",
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item { SectionHeading("Start here", "Choose what you want to do") }
        item {
            WorkflowCard(
                title = "Games",
                description = "Choose a supported game and see available workflows.",
                action = "Browse games",
                available = true,
                onClick = onOpenGames,
            )
        }
        item {
            WorkflowCard(
                title = "Operations",
                description = "Trades, Mystery Gift, and other operations will appear here.",
                action = "Coming later",
                available = false,
            )
        }
        item {
            WorkflowCard(
                title = "Saved data & history",
                description = "Future access to exports, backups, and recent activity.",
                action = "Coming later",
                available = false,
            )
        }
    }
}

@Composable
private fun Esp32ConnectionCard(
    device: UsbDeviceInfo?,
    connectionState: SerialConnectionState,
    esp32State: Esp32HandshakeState,
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onTestEsp32: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val serialConnected = connectionState.phase == SerialConnectionPhase.CONNECTED
    val verified = serialConnected && esp32State.phase == Esp32HandshakePhase.VERIFIED && esp32State.info != null
    val busy = connectionState.phase in setOf(
        SerialConnectionPhase.REQUESTING_PERMISSION,
        SerialConnectionPhase.CONNECTING,
        SerialConnectionPhase.DISCONNECTING,
    ) || esp32State.phase == Esp32HandshakePhase.TESTING
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (verified) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        when {
                            verified -> "ESP32 Connected"
                            esp32State.phase == Esp32HandshakePhase.TESTING -> "Verifying ESP32"
                            serialConnected -> "USB Serial Connected"
                            busy -> "Connecting ESP32"
                            device != null -> "ESP32 Adapter Detected"
                            else -> "ESP32 Disconnected"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        when {
                            verified -> "Your PokeLDN adapter is verified and ready."
                            serialConnected -> "Run the protocol check to verify the connected adapter."
                            busy -> connectionState.phase.label
                            device != null -> "Connect the detected USB serial adapter to continue."
                            else -> "Connect the ESP32 through USB OTG, then scan for it."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            }

            when {
                verified -> {
                    val info = checkNotNull(esp32State.info)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill("Firmware ${info.firmwareVersion.ifBlank { "verified" }}", StatusTone.POSITIVE)
                        StatusPill("Protocol ${info.protocolVersion}", StatusTone.POSITIVE)
                    }
                    Text("ESP32 revision ${info.chipRevision}", style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                            Text("Diagnostics")
                        }
                        OutlinedButton(onClick = onDisconnect, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                            Text("Disconnect")
                        }
                    }
                }
                serialConnected -> {
                    esp32State.detail?.let { ErrorText(it) }
                    Button(
                        onClick = onTestEsp32,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) { Text(if (esp32State.phase == Esp32HandshakePhase.FAILED) "Retry ESP32 verification" else "Verify ESP32") }
                }
                device != null -> Button(
                    onClick = { onConnect(device.deviceName) },
                    enabled = !busy && device.serialSupported,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text("Connect ESP32") }
                else -> Button(onClick = onScan, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text("Scan USB Devices")
                }
            }
            connectionState.detail?.let { ErrorText(it) }
        }
    }
}

@Composable
private fun GamesScreen(modifier: Modifier = Modifier) {
    CenteredList(modifier) {
        item {
            ScreenHeader(
                title = "Games",
                subtitle = "Game integrations will be added here as their Android workflows are completed.",
            )
        }
        item {
            StatusPill("Foundation screen", StatusTone.NEUTRAL)
        }
        item {
            PlannedGameCard(
                title = "FireRed / LeafGreen",
                detail = "Planned first integration for Mystery Gift and trade workflows.",
            )
        }
        item {
            PlannedGameCard(
                title = "More supported titles",
                detail = "Additional PokeLDN game modules will follow after the first workflow is stable.",
            )
        }
        item {
            Text(
                "No game operation is available in this build.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiagnosticsScreen(
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
    val devices = (result as? UsbScanResult.Devices)?.items.orEmpty()
    val busy = connectionState.phase in setOf(
        SerialConnectionPhase.REQUESTING_PERMISSION,
        SerialConnectionPhase.CONNECTING,
        SerialConnectionPhase.CONNECTED,
        SerialConnectionPhase.DISCONNECTING,
    )
    CenteredList(modifier) {
        item {
            ScreenHeader(
                title = "Diagnostics",
                subtitle = "USB, serial, protocol, and bounded raw-capture tools for development.",
            )
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(usbScanStatus(result), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onScan, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text("Scan USB Devices")
                    }
                }
            }
        }
        if (devices.isEmpty()) {
            item {
                Text(
                    "Connect the adapter through USB OTG to view device and serial controls.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(devices, key = { it.deviceName }) { device ->
            DiagnosticDeviceCard(
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
                selectedReadBufferMode = selectedReadBufferMode,
                onConnect = { onConnect(device.deviceName) },
                onDisconnect = onDisconnect,
                onTestEsp32 = onTestEsp32,
                onDiagnosticBaudSelected = onDiagnosticBaudSelected,
                onReadBufferModeSelected = onReadBufferModeSelected,
                onCaptureRaw = onCaptureRaw,
            )
        }
    }
}

@Composable
@Suppress("DEPRECATION")
private fun DiagnosticDeviceCard(
    device: UsbDeviceInfo,
    state: SerialConnectionState,
    canConnect: Boolean,
    esp32State: Esp32HandshakeState,
    rawCaptureState: RawCaptureState,
    connectedBaudRate: Int,
    selectedDiagnosticBaud: Int,
    selectedReadBufferMode: RawReadBufferMode,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onTestEsp32: () -> Unit,
    onDiagnosticBaudSelected: (Int) -> Unit,
    onReadBufferModeSelected: (RawReadBufferMode) -> Unit,
    onCaptureRaw: (Int, RawReadBufferMode) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(device.productName ?: "USB device", style = MaterialTheme.typography.titleLarge)
                    if (isCp210xBridge(device.vendorId, device.productId)) {
                        Text("Silicon Labs CP210x USB serial bridge", color = MaterialTheme.colorScheme.primary)
                    }
                }
                StatusPill(
                    text = if (device.serialSupported) "Serial supported" else "Generic USB",
                    tone = if (device.serialSupported) StatusTone.POSITIVE else StatusTone.NEUTRAL,
                )
            }
            DetailLine("Device name", device.deviceName)
            DetailLine("VID / PID", "${usbIdHex(device.vendorId)} / ${usbIdHex(device.productId)}")
            device.manufacturer?.let { DetailLine("Manufacturer", it) }
            device.productName?.let { DetailLine("Product", it) }

            if (!device.serialSupported) {
                Text("No supported serial driver for this device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("Serial connection", style = MaterialTheme.typography.titleMedium)
            StatusPill(state.phase.label, state.phase.toStatusTone())
            state.detail?.let { ErrorText(it) }
            Text("$connectedBaudRate baud · 8N1 · no flow control", color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (state.phase == SerialConnectionPhase.CONNECTED) {
                Button(
                    onClick = onDisconnect,
                    enabled = esp32State.phase != Esp32HandshakePhase.TESTING &&
                        rawCaptureState.phase != RawCapturePhase.CAPTURING,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text("Disconnect") }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("ESP32 protocol", style = MaterialTheme.typography.titleMedium)
                StatusPill(esp32State.phase.label, esp32State.phase.toStatusTone())
                esp32State.detail?.let { ErrorText(it) }
                esp32State.info?.let { info ->
                    DetailLine("Protocol version", info.protocolVersion.toString())
                    DetailLine("Firmware", info.firmwareText)
                    if (info.firmwareVersion.isNotEmpty()) DetailLine("Firmware version", info.firmwareVersion)
                    DetailLine("Chip revision", info.chipRevision.toString())
                    DetailLine("Station MAC", info.stationMac.formatMac())
                    DetailLine("Access point MAC", info.accessPointMac.formatMac())
                }
                Button(
                    onClick = onTestEsp32,
                    enabled = esp32State.phase != Esp32HandshakePhase.TESTING &&
                        rawCaptureState.phase != RawCapturePhase.CAPTURING,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text("ESP32 HELLO") }

                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("Raw serial capture", style = MaterialTheme.typography.titleMedium)
                Text("Passive capture only. No protocol data is sent.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Baud rate", style = MaterialTheme.typography.labelLarge)
                diagnosticBaudRates.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { baud ->
                            DiagnosticChoice(
                                label = baud.toString(),
                                selected = baud == selectedDiagnosticBaud,
                                enabled = rawCaptureState.phase != RawCapturePhase.CAPTURING,
                                onClick = { onDiagnosticBaudSelected(baud) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Text("Read buffer mode", style = MaterialTheme.typography.labelLarge)
                RawReadBufferMode.entries.forEach { mode ->
                    DiagnosticChoice(
                        label = mode.label,
                        selected = mode == selectedReadBufferMode,
                        enabled = rawCaptureState.phase != RawCapturePhase.CAPTURING,
                        onClick = { onReadBufferModeSelected(mode) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Button(
                    onClick = { onCaptureRaw(selectedDiagnosticBaud, selectedReadBufferMode) },
                    enabled = esp32State.phase != Esp32HandshakePhase.TESTING &&
                        rawCaptureState.phase != RawCapturePhase.CAPTURING,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text("Capture Raw Serial") }
            } else {
                if (esp32State.phase == Esp32HandshakePhase.FAILED) {
                    StatusPill(esp32State.phase.label, StatusTone.ERROR)
                    esp32State.detail?.let { ErrorText(it) }
                }
                Button(
                    onClick = onConnect,
                    enabled = canConnect && state.phase !in setOf(
                        SerialConnectionPhase.REQUESTING_PERMISSION,
                        SerialConnectionPhase.CONNECTING,
                    ),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text("Connect Serial") }
            }

            when (rawCaptureState.phase) {
                RawCapturePhase.IDLE -> Unit
                RawCapturePhase.CAPTURING -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
                        Text("Raw capture: ${rawCaptureState.detail ?: "capturing"}")
                    }
                }
                RawCapturePhase.FAILED -> ErrorText(
                    "Raw capture failed: ${rawCaptureState.detail ?: "unknown error"}"
                )
                RawCapturePhase.COMPLETE -> rawCaptureState.result?.let { capture ->
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text("Raw capture result", style = MaterialTheme.typography.titleMedium)
                    Text("Long press the output to select it, or copy it directly.")
                    Button(
                        onClick = { clipboard.setText(AnnotatedString(capture.displayText())) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) { Text("Copy Raw Capture") }
                    SelectionContainer {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(
                                capture.displayText(),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredList(
    modifier: Modifier,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
private fun AppHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("PokeLDN", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(
            "Android companion for the PokeLDN ESP32 adapter",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScreenHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QuickStatusCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
        }
    }
}

@Composable
private fun WorkflowCard(
    title: String,
    description: String,
    action: String,
    available: Boolean,
    onClick: () -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                StatusPill(if (available) "Available" else "Coming later", if (available) StatusTone.POSITIVE else StatusTone.NEUTRAL)
            }
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (available) {
                OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(action)
                }
            } else {
                OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(action)
                }
            }
        }
    }
}

@Composable
private fun PlannedGameCard(title: String, detail: String) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                StatusPill("Planned", StatusTone.NEUTRAL)
            }
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusPill(text: String, tone: StatusTone) {
    val colors = when (tone) {
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        StatusTone.POSITIVE -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        StatusTone.WARNING -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        StatusTone.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(color = colors.first, contentColor = colors.second, shape = RoundedCornerShape(999.dp)) {
        Text(
            "● $text",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.42f))
        Text(value, modifier = Modifier.weight(0.58f))
    }
}

@Composable
private fun DiagnosticChoice(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp)) {
            Text("$label · selected")
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp)) {
            Text(label)
        }
    }
}

@Composable
private fun ErrorText(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

private fun usbScanStatus(result: UsbScanResult?): String = when (result) {
    null -> "Checking USB devices…"
    UsbScanResult.HostUnavailable -> "USB Host is unavailable on this device."
    UsbScanResult.ScanFailed -> "Could not scan USB devices. Try again."
    is UsbScanResult.Devices -> if (result.items.isEmpty()) {
        "No USB devices found. Check the cable and USB OTG connection."
    } else {
        "${result.items.size} USB device${if (result.items.size == 1) "" else "s"} visible to Android."
    }
}

private fun SerialConnectionPhase.toStatusTone(): StatusTone = when (this) {
    SerialConnectionPhase.CONNECTED, SerialConnectionPhase.READY -> StatusTone.POSITIVE
    SerialConnectionPhase.REQUESTING_PERMISSION, SerialConnectionPhase.CONNECTING,
    SerialConnectionPhase.DISCONNECTING, SerialConnectionPhase.PERMISSION_REQUIRED -> StatusTone.WARNING
    SerialConnectionPhase.PERMISSION_DENIED, SerialConnectionPhase.CONNECTION_FAILED,
    SerialConnectionPhase.DISCONNECTED -> StatusTone.ERROR
    SerialConnectionPhase.DEVICE_DETECTED -> StatusTone.NEUTRAL
}

private fun Esp32HandshakePhase.toStatusTone(): StatusTone = when (this) {
    Esp32HandshakePhase.VERIFIED -> StatusTone.POSITIVE
    Esp32HandshakePhase.TESTING -> StatusTone.WARNING
    Esp32HandshakePhase.FAILED -> StatusTone.ERROR
    Esp32HandshakePhase.IDLE -> StatusTone.NEUTRAL
}
