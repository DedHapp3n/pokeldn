package com.dedhapp3n.pokeldn.usb

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialProber

data class UsbDeviceInfo(
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
    val manufacturer: String?,
    val productName: String?,
    val hasPermission: Boolean,
    val serialSupported: Boolean,
)

sealed interface UsbScanResult {
    data class Devices(val items: List<UsbDeviceInfo>) : UsbScanResult
    data object HostUnavailable : UsbScanResult
    data object ScanFailed : UsbScanResult
}

/** Enumerates USB devices without opening them or requesting USB permission. */
class UsbDeviceScanner(private val context: Context) {
    private val prober = UsbSerialProber.getDefaultProber()

    fun scan(): UsbScanResult {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            return UsbScanResult.HostUnavailable
        }

        val manager = context.getSystemService(UsbManager::class.java)
            ?: return UsbScanResult.HostUnavailable

        return try {
            UsbScanResult.Devices(
                manager.deviceList.values.map { device -> device.toInfo(manager, prober) }
                    .sortedBy { it.deviceName }
            )
        } catch (_: SecurityException) {
            UsbScanResult.ScanFailed
        }
    }
}

private fun UsbDevice.toInfo(manager: UsbManager, prober: UsbSerialProber) = UsbDeviceInfo(
    deviceName = deviceName,
    vendorId = vendorId,
    productId = productId,
    manufacturer = optionalDescriptor { manufacturerName },
    productName = optionalDescriptor { productName },
    hasPermission = manager.hasPermission(this),
    serialSupported = prober.probeDevice(this) != null,
)

private inline fun optionalDescriptor(read: () -> String?): String? =
    try {
        read()?.takeIf { it.isNotBlank() }
    } catch (_: SecurityException) {
        null
    }

fun usbIdHex(id: Int): String = "0x%04X".format(id)

fun isCp210xBridge(vendorId: Int, productId: Int): Boolean =
    vendorId == 0x10C4 && productId == 0xEA60
