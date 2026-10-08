package com.dedhapp3n.pokeldn.usb

enum class SerialConnectionPhase(val label: String) {
    DEVICE_DETECTED("USB device detected"),
    PERMISSION_REQUIRED("Permission required"),
    REQUESTING_PERMISSION("Requesting permission"),
    PERMISSION_DENIED("Permission denied"),
    READY("Ready"),
    CONNECTING("Connecting"),
    CONNECTED("Serial connected"),
    DISCONNECTING("Disconnecting"),
    CONNECTION_FAILED("Connection failed"),
    DISCONNECTED("Device disconnected"),
}

data class SerialConnectionState(
    val deviceName: String? = null,
    val phase: SerialConnectionPhase = SerialConnectionPhase.DEVICE_DETECTED,
    val detail: String? = null,
)

fun devicePhase(device: UsbDeviceInfo): SerialConnectionPhase =
    if (device.hasPermission) SerialConnectionPhase.READY
    else SerialConnectionPhase.PERMISSION_REQUIRED
