package com.dedhapp3n.pokeldn.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException

/** Synchronous serial boundary. Call its methods from a worker thread. */
interface SerialIo {
    fun read(buffer: ByteArray, timeoutMillis: Int): Int
    fun write(bytes: ByteArray, timeoutMillis: Int)
}

interface SerialTransport : SerialIo {
    fun connect(device: UsbDevice, baudRate: Int)
    fun disconnect()
}

class UsbSerialTransport(private val manager: UsbManager) : SerialTransport {
    private val prober = UsbSerialProber.getDefaultProber()
    @Volatile
    private var connection: UsbDeviceConnection? = null
    @Volatile
    private var port: UsbSerialPort? = null
    @Volatile
    var configuredBaudRate: Int? = null
        private set

    fun connect(device: UsbDevice) = connect(device, BAUD_RATE)

    override fun connect(device: UsbDevice, baudRate: Int) {
        require(baudRate > 0) { "Baud rate must be positive" }
        check(port == null) { "Serial port already open" }
        val driver = prober.probeDevice(device) ?: throw IOException("No serial driver for this device")
        val openedConnection = manager.openDevice(device) ?: throw IOException("Could not open USB device")
        try {
            val openedPort = driver.ports.firstOrNull() ?: throw IOException("No serial port found")
            openedPort.open(openedConnection)
            openedPort.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            openedPort.setFlowControl(UsbSerialPort.FlowControl.NONE)
            val lines = openedPort.supportedControlLines
            if (UsbSerialPort.ControlLine.DTR in lines) openedPort.dtr = false
            if (UsbSerialPort.ControlLine.RTS in lines) openedPort.rts = false
            connection = openedConnection
            port = openedPort
            configuredBaudRate = baudRate
        } catch (error: Exception) {
            try {
                driver.ports.firstOrNull()?.takeIf { it.isOpen }?.close()
            } catch (_: Exception) {
                // The connection is closed below even when the port cannot close cleanly.
            }
            openedConnection.close()
            throw error
        }
    }

    override fun disconnect() {
        val openedPort = port
        val openedConnection = connection
        port = null
        connection = null
        configuredBaudRate = null
        try {
            openedPort?.close()
        } finally {
            openedConnection?.close()
        }
    }

    override fun read(buffer: ByteArray, timeoutMillis: Int): Int =
        requirePort().read(buffer, timeoutMillis)

    override fun write(bytes: ByteArray, timeoutMillis: Int) {
        requirePort().write(bytes, timeoutMillis)
    }

    private fun requirePort(): UsbSerialPort = port ?: throw IOException("Serial port is not connected")

    companion object {
        const val BAUD_RATE = 115200
    }
}
