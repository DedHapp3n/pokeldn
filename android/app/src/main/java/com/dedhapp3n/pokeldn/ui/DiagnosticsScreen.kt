package com.dedhapp3n.pokeldn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.dedhapp3n.pokeldn.frlg.ProdKeysPhase
import com.dedhapp3n.pokeldn.frlg.ProdKeysState
import com.dedhapp3n.pokeldn.ldn.LdnApTestPhase
import com.dedhapp3n.pokeldn.ldn.LdnApTestState
import com.dedhapp3n.pokeldn.ui.theme.CreamPanel
import com.dedhapp3n.pokeldn.ui.theme.DeviceBezel
import com.dedhapp3n.pokeldn.ui.theme.DeviceInk
import com.dedhapp3n.pokeldn.ui.theme.IndicatorCyan
import com.dedhapp3n.pokeldn.ui.theme.ScreenBlack
import com.dedhapp3n.pokeldn.ui.theme.ScreenText
import com.dedhapp3n.pokeldn.usb.SerialConnectionPhase
import com.dedhapp3n.pokeldn.usb.SerialConnectionState
import com.dedhapp3n.pokeldn.usb.UsbDeviceInfo
import com.dedhapp3n.pokeldn.usb.UsbScanResult
import com.dedhapp3n.pokeldn.usb.UsbSerialTransport
import com.dedhapp3n.pokeldn.usb.devicePhase
import com.dedhapp3n.pokeldn.usb.isCp210xBridge
import com.dedhapp3n.pokeldn.usb.usbIdHex

private val diagnosticBaudRates = listOf(115200, 230400, 460800, 921600)

@Composable
internal fun DiagnosticsScreen(
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
    prodKeysState: ProdKeysState,
    onImportProdKeys: () -> Unit,
    ldnApTestState: LdnApTestState,
    onStartLdnApTest: () -> Unit,
    onStopLdnApTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val devices = (result as? UsbScanResult.Devices)?.items.orEmpty()
    val busy = connectionState.phase in setOf(
        SerialConnectionPhase.REQUESTING_PERMISSION,
        SerialConnectionPhase.CONNECTING,
        SerialConnectionPhase.CONNECTED,
        SerialConnectionPhase.DISCONNECTING,
    )
    CenteredDeviceList(modifier) {
        item {
            DeviceScreenTitle(null, "Diagnostics", "USB, serial, protocol, and capture controls")
        }
        item {
            ShellPanel("USB scanner") {
                Text(usbScanStatus(result), fontWeight = FontWeight.SemiBold)
                DeviceButton("Scan USB devices", onScan, Modifier.fillMaxWidth(), style = DeviceButtonStyle.SECONDARY)
            }
        }
        if (devices.isEmpty()) {
            item {
                BezelDisplay("Service monitor") {
                    DisplayStatus("Waiting for USB", StatusTone.NEUTRAL)
                    Text("Connect the adapter through USB OTG to expose service controls.")
                }
            }
        }
        items(devices.size, key = { devices[it].deviceName }) { index ->
            val device = devices[index]
            DiagnosticDevicePanel(
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
                prodKeysState = prodKeysState,
                onImportProdKeys = onImportProdKeys,
                ldnApTestState = if (connectionState.deviceName == device.deviceName) ldnApTestState
                    else LdnApTestState(),
                onStartLdnApTest = onStartLdnApTest,
                onStopLdnApTest = onStopLdnApTest,
            )
        }
    }
}

@Composable
@Suppress("DEPRECATION")
private fun DiagnosticDevicePanel(
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
    prodKeysState: ProdKeysState,
    onImportProdKeys: () -> Unit,
    ldnApTestState: LdnApTestState,
    onStartLdnApTest: () -> Unit,
    onStopLdnApTest: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val ldnActive = ldnApTestState.phase in setOf(
        LdnApTestPhase.ADVERTISING,
        LdnApTestPhase.CONSOLE_ACTIVITY,
        LdnApTestPhase.AUTH_REQUEST,
        LdnApTestPhase.AUTH_RESPONSE_SENT,
        LdnApTestPhase.AUTHENTICATION_REJECTED,
        LdnApTestPhase.PARTICIPANT_REGISTERED,
        LdnApTestPhase.STATION_ASSOCIATED,
        LdnApTestPhase.PIA_REQUEST,
        LdnApTestPhase.PIA_RESPONSE_SENT,
        LdnApTestPhase.PIA_ESTABLISHED,
        LdnApTestPhase.RELIABLE_ESTABLISHED,
        LdnApTestPhase.RFU_CONNECTED,
        LdnApTestPhase.LINK_PLAYER_EXCHANGED,
    )
    val ldnBusy = ldnApTestState.phase in setOf(
        LdnApTestPhase.PREPARING,
        LdnApTestPhase.STARTING_AP,
        LdnApTestPhase.STOPPING,
    ) || ldnActive
    ShellPanel(device.productName ?: "USB device") {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (isCp210xBridge(device.vendorId, device.productId)) "CP210X LINK ADAPTER" else "USB DEVICE",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Black,
                )
                device.manufacturer?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            StatusBadge(
                if (device.serialSupported) "Serial supported" else "Generic USB",
                if (device.serialSupported) StatusTone.POSITIVE else StatusTone.NEUTRAL,
            )
        }

        DiagnosticSection("USB") {
            DetailLine("Device name", device.deviceName)
            DetailLine("VID / PID", "${usbIdHex(device.vendorId)} / ${usbIdHex(device.productId)}")
            device.manufacturer?.let { DetailLine("Manufacturer", it) }
            device.productName?.let { DetailLine("Product", it) }
            DetailLine("Permission", if (device.hasPermission) "Granted" else "Required")
        }

        if (!device.serialSupported) {
            Text("No supported serial driver is available for this device.")
            return@ShellPanel
        }

        DiagnosticSection("Serial") {
            StatusBadge(state.phase.label, state.phase.toStatusTone())
            state.detail?.let { ErrorText(it) }
            DetailLine("Settings", "$connectedBaudRate baud / 8N1 / no flow control")
            if (state.phase == SerialConnectionPhase.CONNECTED) {
                DeviceButton(
                    "Disconnect serial",
                    onDisconnect,
                    Modifier.fillMaxWidth(),
                    enabled = esp32State.phase != Esp32HandshakePhase.TESTING &&
                        !ldnBusy &&
                        rawCaptureState.phase != RawCapturePhase.CAPTURING,
                )
            } else {
                DeviceButton(
                    "Connect serial",
                    onConnect,
                    Modifier.fillMaxWidth(),
                    enabled = canConnect && state.phase !in setOf(
                        SerialConnectionPhase.REQUESTING_PERMISSION,
                        SerialConnectionPhase.CONNECTING,
                    ),
                )
            }
        }

        if (state.phase == SerialConnectionPhase.CONNECTED) {
            DiagnosticSection("ESP32 protocol") {
                StatusBadge(esp32State.phase.label, esp32State.phase.toStatusTone())
                esp32State.detail?.let { ErrorText(it) }
                esp32State.info?.let { info ->
                    DetailLine("Protocol", info.protocolVersion.toString())
                    DetailLine("Firmware", info.firmwareText)
                    if (info.firmwareVersion.isNotEmpty()) DetailLine("Firmware version", info.firmwareVersion)
                    DetailLine("Chip revision", info.chipRevision.toString())
                    DetailLine("Station MAC", info.stationMac.formatMac())
                    DetailLine("Access point MAC", info.accessPointMac.formatMac())
                }
                DeviceButton(
                    "ESP32 HELLO",
                    onTestEsp32,
                    Modifier.fillMaxWidth(),
                    enabled = esp32State.phase !in setOf(
                        Esp32HandshakePhase.TESTING,
                        Esp32HandshakePhase.VERIFIED,
                    ) &&
                        rawCaptureState.phase != RawCapturePhase.CAPTURING,
                    style = DeviceButtonStyle.SECONDARY,
                )
            }

            DiagnosticSection("Nintendo LDN AP") {
                StatusBadge(
                    if (prodKeysState.phase == ProdKeysPhase.AVAILABLE) "Keys valid" else "Keys missing",
                    if (prodKeysState.phase == ProdKeysPhase.AVAILABLE) StatusTone.POSITIVE else StatusTone.WARNING,
                )
                StatusBadge(ldnApTestState.phase.label, ldnApTestState.phase.toStatusTone())
                ldnApTestState.detail?.let {
                    if (ldnApTestState.phase in setOf(
                            LdnApTestPhase.FAILED,
                            LdnApTestPhase.CLEANUP_REQUIRED,
                        )) ErrorText(it) else Text(it)
                }
                if (ldnActive) {
                    DetailLine("AP", "Active")
                    ldnApTestState.channel?.let { DetailLine("Channel", it.toString()) }
                    DetailLine("Advertisements sent", ldnApTestState.advertisementsSent.toString())
                    DetailLine("Discovery activity", ldnApTestState.discoveryActivityCount.toString())
                    DetailLine("Authentication requests", ldnApTestState.authenticationRequests.toString())
                    DetailLine("Authentication responses", ldnApTestState.authenticationResponses.toString())
                    if (ldnApTestState.authenticationFailures > 0) {
                        DetailLine("Authentication rejected", ldnApTestState.authenticationFailures.toString())
                    }
                    DetailLine(
                        "Participant",
                        if (ldnApTestState.participantRegistered) "Registered" else "Not registered",
                    )
                    DetailLine("PIA Net probes", ldnApTestState.piaNetRequests.toString())
                    DetailLine("PIA requests", ldnApTestState.piaSessionRequests.toString())
                    DetailLine("PIA responses", ldnApTestState.piaSessionResponses.toString())
                    DetailLine("PIA session", if (ldnApTestState.piaEstablished) "Established" else "Waiting")
                    DetailLine(
                        "Reliable",
                        if (ldnApTestState.reliableEstablished) "Established" else "Waiting",
                    )
                    DetailLine("Reliable RX / TX", "${ldnApTestState.reliableFramesReceived} / ${ldnApTestState.reliableFramesSent}")
                    DetailLine("RFU", if (ldnApTestState.rfuConnected) "Connected" else "Waiting")
                    DetailLine("LinkPlayer", if (ldnApTestState.linkPlayerExchanged) "Exchanged" else "Waiting")
                    ldnApTestState.detectedCartridge?.let { DetailLine("Cartridge", it) }
                    DetailLine(
                        "Station",
                        if (ldnApTestState.stationDetected) "Associated" else "Not detected",
                    )
                }
                if (prodKeysState.phase != ProdKeysPhase.AVAILABLE) {
                    Text("Import your own prod.keys before starting the hardware lifecycle test.")
                    DeviceButton("Import prod.keys", onImportProdKeys, Modifier.fillMaxWidth())
                }
                if (ldnActive) {
                    DeviceButton("Stop LDN AP", onStopLdnApTest, Modifier.fillMaxWidth())
                } else {
                    DeviceButton(
                        "Test LDN AP",
                        onStartLdnApTest,
                        Modifier.fillMaxWidth(),
                        enabled = esp32State.phase == Esp32HandshakePhase.VERIFIED &&
                            prodKeysState.phase == ProdKeysPhase.AVAILABLE &&
                            ldnApTestState.phase in setOf(
                                LdnApTestPhase.READY,
                                LdnApTestPhase.FAILED,
                            ),
                        style = DeviceButtonStyle.SECONDARY,
                    )
                }
            }

            DiagnosticSection("Raw capture") {
                Text("Passive capture only. No protocol data is sent.")
                Text("BAUD RATE", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black)
                diagnosticBaudRates.chunked(2).forEach { baudRow ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        baudRow.forEach { baud ->
                            DiagnosticChoice(
                                label = baud.toString(),
                                selected = baud == selectedDiagnosticBaud,
                                enabled = rawCaptureState.phase != RawCapturePhase.CAPTURING,
                                onClick = { onDiagnosticBaudSelected(baud) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (baudRow.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Text("READ BUFFER MODE", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black)
                RawReadBufferMode.entries.forEach { mode ->
                    DiagnosticChoice(
                        label = mode.label,
                        selected = mode == selectedReadBufferMode,
                        enabled = rawCaptureState.phase != RawCapturePhase.CAPTURING,
                        onClick = { onReadBufferModeSelected(mode) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                DeviceButton(
                    "Capture raw serial",
                    { onCaptureRaw(selectedDiagnosticBaud, selectedReadBufferMode) },
                    Modifier.fillMaxWidth(),
                    enabled = esp32State.phase != Esp32HandshakePhase.TESTING &&
                        !ldnBusy &&
                        rawCaptureState.phase != RawCapturePhase.CAPTURING,
                    style = DeviceButtonStyle.SECONDARY,
                )

                when (rawCaptureState.phase) {
                    RawCapturePhase.IDLE -> Unit
                    RawCapturePhase.CAPTURING -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = IndicatorCyan, strokeWidth = 3.dp)
                        Text("Raw capture: ${rawCaptureState.detail ?: "capturing"}")
                    }
                    RawCapturePhase.FAILED -> ErrorText(
                        "Raw capture failed: ${rawCaptureState.detail ?: "unknown error"}"
                    )
                    RawCapturePhase.COMPLETE -> rawCaptureState.result?.let { capture ->
                        Text("CAPTURE RESULT", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black)
                        DeviceButton(
                            "Copy raw capture",
                            { clipboard.setText(AnnotatedString(capture.displayText())) },
                            Modifier.fillMaxWidth(),
                        )
                        SelectionContainer {
                            Surface(
                                color = ScreenBlack,
                                contentColor = ScreenText,
                                shape = RoundedCornerShape(9.dp),
                                border = BorderStroke(2.dp, DeviceBezel),
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
        } else if (esp32State.phase == Esp32HandshakePhase.FAILED) {
            ErrorText(esp32State.detail ?: "ESP32 handshake failed")
        }
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
    DeviceButton(
        if (selected) "$label / selected" else label,
        onClick,
        modifier,
        enabled,
        if (selected) DeviceButtonStyle.SECONDARY else DeviceButtonStyle.PRIMARY,
    )
}

private fun usbScanStatus(result: UsbScanResult?): String = when (result) {
    null -> "Checking USB devices..."
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

private fun LdnApTestPhase.toStatusTone(): StatusTone = when (this) {
    LdnApTestPhase.READY, LdnApTestPhase.ADVERTISING,
    LdnApTestPhase.CONSOLE_ACTIVITY, LdnApTestPhase.AUTH_REQUEST,
    LdnApTestPhase.AUTH_RESPONSE_SENT, LdnApTestPhase.PARTICIPANT_REGISTERED,
    LdnApTestPhase.STATION_ASSOCIATED, LdnApTestPhase.PIA_REQUEST,
    LdnApTestPhase.PIA_RESPONSE_SENT, LdnApTestPhase.PIA_ESTABLISHED,
    LdnApTestPhase.RELIABLE_ESTABLISHED, LdnApTestPhase.RFU_CONNECTED,
    LdnApTestPhase.LINK_PLAYER_EXCHANGED -> StatusTone.POSITIVE
    LdnApTestPhase.AUTHENTICATION_REJECTED, LdnApTestPhase.PIA_FAILED -> StatusTone.WARNING
    LdnApTestPhase.PREPARING, LdnApTestPhase.STARTING_AP,
    LdnApTestPhase.STOPPING -> StatusTone.WARNING
    LdnApTestPhase.FAILED, LdnApTestPhase.CLEANUP_REQUIRED -> StatusTone.ERROR
    LdnApTestPhase.DISCONNECTED -> StatusTone.NEUTRAL
}
