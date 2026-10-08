package com.dedhapp3n.pokeldn.usb

import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbConnectionLogicTest {
    @Test
    fun cp2102IdIsRecognizedByOurLabelAndTheSerialDriver() {
        assertTrue(isCp210xBridge(0x10C4, 0xEA60))
        assertTrue(Cp21xxSerialDriver.getSupportedDevices()[0x10C4]?.contains(0xEA60) == true)
        assertFalse(isCp210xBridge(0x303A, 0x1001))
    }

    @Test
    fun permissionStateTracksEnumeratedDevicePermission() {
        val device = UsbDeviceInfo("usb1", 0x10C4, 0xEA60, "Silicon Labs", "CP2102", false, true)
        assertEquals(SerialConnectionPhase.PERMISSION_REQUIRED, devicePhase(device))
        assertEquals(SerialConnectionPhase.READY, devicePhase(device.copy(hasPermission = true)))
    }

    @Test
    fun connectionPhasesHaveClearUserFacingStates() {
        assertEquals("Requesting permission", SerialConnectionPhase.REQUESTING_PERMISSION.label)
        assertEquals("Permission denied", SerialConnectionPhase.PERMISSION_DENIED.label)
        assertEquals("Serial connected", SerialConnectionPhase.CONNECTED.label)
        assertEquals("Device disconnected", SerialConnectionPhase.DISCONNECTED.label)
    }
}
