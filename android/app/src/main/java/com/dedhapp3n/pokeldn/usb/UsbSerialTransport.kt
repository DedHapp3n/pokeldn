package com.dedhapp3n.pokeldn.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.io.IOException

/** Synchronous serial boundary. Call its methods from a worker thread. */
interface SerialIo {
    fun read(buffer: ByteArray, timeoutMillis: Int): Int
    fun write(bytes: ByteArray, timeoutMillis: Int)
}

interface SerialTransport : SerialIo {
    val configuredBaudRate: Int?
    fun connect(device: UsbDevice, baudRate: Int)
    fun setBaudRate(baudRate: Int)
    fun disconnect()
}

data class UsbEndpointDiagnostic(
    val interfaceIndex: Int,
    val endpointIndex: Int,
    val address: Int,
    val type: String,
    val direction: String,
    val maxPacketSize: Int,
)

data class UsbSerialOpenDiagnostic(
    val vendorId: Int,
    val productId: Int,
    val driverClass: String,
    val portClass: String,
    val portCount: Int,
    val selectedPortIndex: Int,
    val requestedBaudRate: Int,
    val setParametersCompleted: Boolean,
    val endpoints: List<UsbEndpointDiagnostic>,
)

class UsbSerialTransport(private val manager: UsbManager) : SerialTransport {
    private val prober = UsbSerialProber.getDefaultProber()
    @Volatile
    private var connection: UsbDeviceConnection? = null
    @Volatile
    private var port: UsbSerialPort? = null
    @Volatile
    private var ioManager: SerialInputOutputManager? = null
    @Volatile
    private var readQueue: SerialReadQueue? = null
    @Volatile
    override var configuredBaudRate: Int? = null
        private set
    @Volatile
    var openDiagnostic: UsbSerialOpenDiagnostic? = null
        private set

    fun connect(device: UsbDevice) = connect(device, BAUD_RATE)

    override fun connect(device: UsbDevice, baudRate: Int) {
        require(baudRate > 0) { "Baud rate must be positive" }
        check(port == null) { "Serial port already open" }
        val driver = prober.probeDevice(device) ?: throw IOException("No serial driver for this device")
        val openedConnection = manager.openDevice(device) ?: throw IOException("Could not open USB device")
        var openedIoManager: SerialInputOutputManager? = null
        var openedReadQueue: SerialReadQueue? = null
        try {
            val ports = driver.ports
            val openedPort = ports.firstOrNull() ?: throw IOException("No serial port found")
            openedPort.open(openedConnection)
            openedPort.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            openedPort.setFlowControl(UsbSerialPort.FlowControl.NONE)
            val lines = openedPort.supportedControlLines
            if (UsbSerialPort.ControlLine.DTR in lines) openedPort.dtr = false
            if (UsbSerialPort.ControlLine.RTS in lines) openedPort.rts = false

            val receiveQueue = SerialReadQueue()
            openedReadQueue = receiveQueue
            val receiveManager = SerialInputOutputManager(
                openedPort,
                object : SerialInputOutputManager.Listener {
                    override fun onNewData(data: ByteArray) {
                        receiveQueue.offer(data)
                    }

                    override fun onRunError(error: Exception) {
                        receiveQueue.fail(error)
                    }
                },
            )
            openedIoManager = receiveManager
            connection = openedConnection
            port = openedPort
            readQueue = receiveQueue
            ioManager = receiveManager
            receiveManager.start()
            configuredBaudRate = baudRate
            openDiagnostic = UsbSerialOpenDiagnostic(
                vendorId = device.vendorId,
                productId = device.productId,
                driverClass = driver.javaClass.name,
                portClass = openedPort.javaClass.name,
                portCount = ports.size,
                selectedPortIndex = 0,
                requestedBaudRate = baudRate,
                setParametersCompleted = true,
                endpoints = device.endpointDiagnostics(),
            )
        } catch (error: Exception) {
            ioManager = null
            readQueue = null
            port = null
            connection = null
            openedReadQueue?.close()
            openedIoManager?.stop()
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
        val openedIoManager = ioManager
        val openedReadQueue = readQueue
        val openedPort = port
        val openedConnection = connection
        ioManager = null
        readQueue = null
        port = null
        connection = null
        configuredBaudRate = null
        openDiagnostic = null
        openedReadQueue?.close()
        openedIoManager?.stop()
        try {
            openedPort?.close()
        } finally {
            openedConnection?.close()
        }
    }

    override fun setBaudRate(baudRate: Int) {
        require(baudRate > 0) { "Baud rate must be positive" }
        requirePort().setParameters(
            baudRate,
            UsbSerialPort.DATABITS_8,
            UsbSerialPort.STOPBITS_1,
            UsbSerialPort.PARITY_NONE,
        )
        configuredBaudRate = baudRate
    }

    override fun read(buffer: ByteArray, timeoutMillis: Int): Int =
        requireReadQueue().read(buffer, timeoutMillis)

    override fun write(bytes: ByteArray, timeoutMillis: Int) {
        requirePort().write(bytes, timeoutMillis)
    }

    private fun requirePort(): UsbSerialPort = port ?: throw IOException("Serial port is not connected")

    private fun requireReadQueue(): SerialReadQueue =
        readQueue ?: throw IOException("Serial port is not connected")

    companion object {
        const val BAUD_RATE = 115200
    }
}

private fun UsbDevice.endpointDiagnostics(): List<UsbEndpointDiagnostic> = buildList {
    for (interfaceIndex in 0 until interfaceCount) {
        val usbInterface = getInterface(interfaceIndex)
        for (endpointIndex in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(endpointIndex)
            add(
                UsbEndpointDiagnostic(
                    interfaceIndex = interfaceIndex,
                    endpointIndex = endpointIndex,
                    address = endpoint.address,
                    type = when (endpoint.type) {
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"
                        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isochronous"
                        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "control"
                        else -> "unknown(${endpoint.type})"
                    },
                    direction = when (endpoint.direction) {
                        UsbConstants.USB_DIR_IN -> "IN"
                        UsbConstants.USB_DIR_OUT -> "OUT"
                        else -> "unknown(${endpoint.direction})"
                    },
                    maxPacketSize = endpoint.maxPacketSize,
                )
            )
        }
    }
}
