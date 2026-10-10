package com.dedhapp3n.pokeldn.frlg

import java.io.ByteArrayOutputStream
import java.security.SecureRandom

internal object FrlgRfu {
    const val SLOT_SIZE = 14
    const val TABLE_SIZE = 70
    const val NULL = 0
    const val NI_START = 1
    const val NI = 2
    const val NI_END = 3
    const val UNI = 4

    const val SEND_BLOCK_INIT = 0x8800
    const val SEND_BLOCK = 0x8900
    const val READY_EXIT_STANDBY = 0x6600
    const val READY_CLOSE_LINK = 0x5f00
    const val SEND_BLOCK_REQ = 0xa100
    const val SEND_PLAYER_IDS = 0x7700
    const val MASK = 0xff00

    fun childFields(slot: ByteArray): RfuFields {
        require(slot.size >= 2)
        val value = le16(slot)
        return RfuFields(value ushr 10 and 15, value ushr 9 and 1,
            value ushr 7 and 3, value ushr 5 and 3, value and 31)
    }

    fun parentFields(slot: ByteArray): RfuFields {
        require(slot.size >= 3)
        val value = slot[0].u8() or (slot[1].u8() shl 8) or (slot[2].u8() shl 16)
        return RfuFields(value ushr 14 and 15, value ushr 13 and 1,
            value ushr 11 and 3, value ushr 9 and 3, value and 0x7f)
    }

    fun parentSlot(state: Int, size: Int, ack: Int = 0, n: Int = 0,
                   phase: Int = 0, bitmap: Int = 1): ByteArray {
        val value = ((state and 15) shl 14) or ((bitmap and 15) shl 18) or
            ((ack and 1) shl 13) or ((n and 3) shl 11) or ((phase and 3) shl 9) or (size and 0x7f)
        return byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte())
    }

    fun serialize(words: IntArray): ByteArray = ByteArray(SLOT_SIZE).also { out ->
        for (index in 0 until minOf(7, words.size)) putLe16(out, index * 2, words[index])
    }

    fun playerIds() = intArrayOf(SEND_PLAYER_IDS, 2, 1, 0, 0, 0, 0)
    fun blockRequest() = intArrayOf(SEND_BLOCK_REQ, 0, 0, 0, 0, 0, 0)
    fun blockInit(count: Int) = intArrayOf(SEND_BLOCK_INIT, count, 0x80, 0, 0, 0, 0)
    fun blockFragment(index: Int, data: ByteArray): IntArray = IntArray(7).also { words ->
        words[0] = SEND_BLOCK or (index and 31)
        val padded = data.copyOf(12)
        for (i in 0 until 6) words[i + 1] = le16(padded, i * 2)
    }
}

internal data class RfuFields(val state: Int, val ack: Int, val n: Int, val phase: Int, val size: Int)

internal object FrlgGbaFrame {
    const val MARKER = 0x57
    const val T = 0x54
    const val K = 0x4b
    const val C = 0x43
    const val A = 0x41
    const val D = 0x44
    const val G = 0x47

    fun frame(type: Int, body: ByteArray): ByteArray =
        byteArrayOf(MARKER.toByte(), type.toByte(), body.size.toByte(), (body.size ushr 8).toByte()) + body

    fun accept(hostId: ByteArray, connectId: ByteArray) =
        frame(A, hostId.copyOf(2) + connectId.copyOf(2) + byteArrayOf(0, 0))
    fun state(value: Int) = frame(G, byteArrayOf(value.toByte(), 0, 0, 0))
    fun disconnect(connectId: ByteArray) = frame(D, connectId.copyOf(2))

    fun parentT(slot: ByteArray, timestamp: Long): ByteArray {
        val padded = slot.copyOf((slot.size + 3) and 3.inv())
        val body = ByteArray(8).also {
            putLe32(it, 0, timestamp)
            it[4] = slot.size.toByte()
        } + padded
        return frame(T, body)
    }

    fun parseChild(payload: ByteArray): ChildFrame? {
        if (payload.size < 4 || payload[0].u8() != MARKER) return null
        val size = le16(payload, 2)
        if (payload.size < 4 + size) return null
        val body = payload.copyOfRange(4, 4 + size)
        if (payload[1].u8() != T) return ChildFrame(payload[1].u8(), body)
        if (body.size < 8) return null
        val slotLength = body[5].u8()
        if (body.size < 8 + slotLength) return null
        return ChildFrame(T, body, body.copyOfRange(8, 8 + slotLength))
    }
}

internal data class ChildFrame(val type: Int, val body: ByteArray, val slot: ByteArray? = null)

internal enum class FrlgRfuState { WAIT_CONNECT, CHILD_NI, PARENT_NI, UNI, DISCONNECTED }

/** Upstream RFULeader subset used by the FRLG Mystery Gift host. */
internal class FrlgRfuLeader(
    hostSessionId: ByteArray = byteArrayOf(SecureRandom().nextInt(256).toByte(), 0xf1.toByte()),
) {
    val hostSessionId = hostSessionId.copyOf(2)
    var state = FrlgRfuState.WAIT_CONNECT
        private set
    var childGameData: ByteArray? = null
        private set
    var childCommand: ByteArray = ByteArray(FrlgRfu.SLOT_SIZE)
        private set
    private var connectId: ByteArray? = null
    private var timestamp = 1L
    private val pending = ArrayDeque<ByteArray>()
    private val echo = ArrayDeque<ByteArray>()
    private val echoSet = mutableSetOf<List<Byte>>()
    private val seenNi = mutableSetOf<List<Byte>>()
    private val childNi = ChildNiReceiver()
    private val parentNi = ParentNiSender()
    private var parentWaiting: Triple<Int, Int, Int>? = null
    private var parentCurrent: ByteArray? = null

    fun receive(payload: ByteArray): String? {
        val frame = FrlgGbaFrame.parseChild(payload) ?: return null
        when (frame.type) {
            FrlgGbaFrame.C -> {
                if (frame.body.size < 2) return null
                val id = frame.body.copyOf(2)
                if (connectId == null) {
                    connectId = id
                    state = FrlgRfuState.CHILD_NI
                    pending += FrlgGbaFrame.accept(hostSessionId, id)
                    pending += FrlgGbaFrame.state(0)
                    return "connect"
                }
                return if (connectId!!.contentEquals(id) && state != FrlgRfuState.DISCONNECTED)
                    "connect_duplicate" else "connect_rejected"
            }
            FrlgGbaFrame.D -> { state = FrlgRfuState.DISCONNECTED; pending.clear(); return "disconnect" }
            FrlgGbaFrame.K -> return "k_ack"
            FrlgGbaFrame.T -> Unit
            else -> return null
        }
        if (connectId == null) return null
        val slot = frame.slot ?: return null
        if (slot.size < 2) return null
        val fields = FrlgRfu.childFields(slot)
        if (fields.ack == 1) {
            val got = Triple(fields.state, fields.n, fields.phase)
            return if (state == FrlgRfuState.PARENT_NI && got == parentWaiting) {
                parentWaiting = null; parentCurrent = null; "parent_ni_ack"
            } else "ni_ack_ignored"
        }
        if (fields.state != FrlgRfu.UNI) {
            if (state != FrlgRfuState.CHILD_NI && state != FrlgRfuState.PARENT_NI) return "ni_out_of_phase"
            val key = listOf(fields.state.toByte(), fields.n.toByte(), fields.phase.toByte()) + slot.drop(2)
            if (!seenNi.add(key)) {
                if (fields.state in FrlgRfu.NI_START..FrlgRfu.NI_END) queueNiAck(fields)
                return "child_ni_duplicate"
            }
            val ack = childNi.receive(slot)
            if (ack) queueNiAck(fields)
            if (fields.state == FrlgRfu.NULL && childNi.complete && state == FrlgRfuState.CHILD_NI) {
                childGameData = childNi.data
                pending += FrlgGbaFrame.state(1)
                state = FrlgRfuState.PARENT_NI
                return "child_ni_complete"
            }
            return "child_ni"
        }
        if (state != FrlgRfuState.UNI || slot.size < 2 + FrlgRfu.SLOT_SIZE) return "uni_early"
        childCommand = slot.copyOfRange(2, 2 + FrlgRfu.SLOT_SIZE).also { it[0] = (it[0].u8() and 31).toByte() }
        val key = childCommand.toList()
        if (echoSet.add(key)) echo += childCommand.copyOf()
        return "uni"
    }

    fun tick(parentWords: IntArray? = null): ByteArray? {
        if (pending.isNotEmpty()) return pending.removeFirst()
        if (state == FrlgRfuState.PARENT_NI) {
            parentCurrent?.let { return wrap(it) }
            val slot = parentNi.next()
            if (slot != null) {
                val fields = FrlgRfu.parentFields(slot)
                if (fields.state == FrlgRfu.NULL) state = FrlgRfuState.UNI
                else {
                    parentWaiting = Triple(fields.state, fields.n, fields.phase)
                    parentCurrent = slot
                }
                return wrap(slot)
            }
        }
        if (state != FrlgRfuState.UNI) return null
        val parent = parentWords?.let(FrlgRfu::serialize) ?: ByteArray(FrlgRfu.SLOT_SIZE)
        val child = if (echo.isNotEmpty()) echo.removeFirst().also { echoSet.remove(it.toList()) } else childCommand
        val table = ByteArray(FrlgRfu.TABLE_SIZE)
        parent.copyInto(table, 0, 0, FrlgRfu.SLOT_SIZE)
        child.copyInto(table, FrlgRfu.SLOT_SIZE, 0, FrlgRfu.SLOT_SIZE)
        return wrap(FrlgRfu.parentSlot(FrlgRfu.UNI, table.size) + table)
    }

    fun disconnect(): ByteArray? = connectId?.let {
        state = FrlgRfuState.DISCONNECTED
        pending.clear()
        FrlgGbaFrame.disconnect(it)
    }

    private fun queueNiAck(fields: RfuFields) {
        pending += wrap(FrlgRfu.parentSlot(fields.state, 0, ack = 1, n = fields.n, phase = fields.phase))
    }
    private fun wrap(slot: ByteArray) = FrlgGbaFrame.parentT(slot, timestamp++)
}

private class ChildNiReceiver {
    var complete = false
    var data: ByteArray? = null
    private var payloadSize = 12
    private var dataSize: Int? = null
    private val header = ByteArrayOutputStream()
    private var buffer = ByteArray(0)
    private val phaseCount = IntArray(4)

    fun receive(slot: ByteArray): Boolean {
        val f = FrlgRfu.childFields(slot)
        if (f.ack != 0) return false
        val payload = slot.copyOfRange(2, minOf(slot.size, 2 + f.size))
        when (f.state) {
            FrlgRfu.NI_START -> {
                header.write(payload)
                if (dataSize == null && header.size() >= 7) {
                    val h = header.toByteArray()
                    payloadSize = le16(h, 1).takeIf { it != 0 } ?: payloadSize
                    dataSize = le32(h, 3).toInt()
                }
            }
            FrlgRfu.NI -> {
                val phase = f.phase and 3
                val offset = phase * payloadSize + phaseCount[phase] * 4 * payloadSize
                if (buffer.size < offset + payload.size) buffer = buffer.copyOf(offset + payload.size)
                payload.copyInto(buffer, offset)
                phaseCount[phase]++
            }
            FrlgRfu.NI_END, FrlgRfu.NULL -> {
                complete = true
                data = buffer.copyOf(dataSize?.coerceAtMost(buffer.size) ?: buffer.size)
            }
        }
        return f.state in FrlgRfu.NI_START..FrlgRfu.NI_END
    }
}

private class ParentNiSender {
    private val slots: List<ByteArray>
    private var index = 0
    init {
        val header = byteArrayOf(0, 5, 0, 1, 0, 0, 0)
        slots = listOf(
            FrlgRfu.parentSlot(FrlgRfu.NI_START, 5, n = 1) + header.copyOfRange(0, 5),
            FrlgRfu.parentSlot(FrlgRfu.NI_START, 2, n = 2) + header.copyOfRange(5, 7),
            FrlgRfu.parentSlot(FrlgRfu.NI, 1, n = 1) + byteArrayOf(5),
            FrlgRfu.parentSlot(FrlgRfu.NI_END, 0),
            FrlgRfu.parentSlot(FrlgRfu.NULL, 0, n = 1),
        )
    }
    fun next(): ByteArray? = slots.getOrNull(index++)
}

private fun Byte.u8() = toInt() and 0xff
private fun le16(data: ByteArray, offset: Int = 0) = data[offset].u8() or (data[offset + 1].u8() shl 8)
private fun le32(data: ByteArray, offset: Int = 0): Long = (0 until 4).fold(0L) { v, i -> v or (data[offset + i].u8().toLong() shl (8 * i)) }
private fun putLe16(data: ByteArray, offset: Int, value: Int) { data[offset] = value.toByte(); data[offset + 1] = (value ushr 8).toByte() }
private fun putLe32(data: ByteArray, offset: Int, value: Long) { for (i in 0 until 4) data[offset + i] = (value ushr (8 * i)).toByte() }
