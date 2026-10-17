package com.dedhapp3n.pokeldn.frlg

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrlgSaveBackupEngineTest {
    @Test fun `complete synthetic save is reconstructed byte for byte and only then published`() {
        val expected = FrlgSaveTest.syntheticSave(40, 41, FrlgSave.Layout.LATIN)
        val responses = deflateMessages(expected).iterator()
        val engine = FrlgSaveBackupEngine()
        val sent = mutableListOf<Int>()

        while (engine.action !is FrlgSaveBackupEngine.Action.Done) {
            when (val action = engine.action) {
                is FrlgSaveBackupEngine.Action.Send -> {
                    sent += action.ident
                    engine.onSent()
                }
                is FrlgSaveBackupEngine.Action.Receive -> {
                    if (action.ident == FrlgSaveBackupEngine.MG_LINKID_GAME_DATA) {
                        engine.onReceived(action.ident, gameData("BPRF"))
                    } else {
                        assertNull(engine.save())
                        engine.onReceived(action.ident, if (responses.hasNext()) responses.next() else ByteArray(0))
                    }
                }
                FrlgSaveBackupEngine.Action.Done -> Unit
            }
        }

        assertArrayEquals(expected, engine.save())
        assertEquals(FrlgSaveBackupEngine.Stage.COMPLETED, engine.snapshot().stage)
        assertEquals(FrlgSave.SAVE_SIZE, engine.snapshot().bytesReceived)
        assertEquals(true, engine.snapshot().hasWholeSlot)
        assertEquals(setOf(16, 25), sent.toSet())
        assertFalse(engine.writesConsoleSave)
    }

    @Test fun `unsupported game is rejected before sending backup payload`() {
        val engine = FrlgSaveBackupEngine()
        engine.onSent()
        engine.onReceived(FrlgSaveBackupEngine.MG_LINKID_GAME_DATA, gameData("XXXX"))
        assertEquals(FrlgSaveBackupEngine.Stage.FAILED, engine.snapshot().stage)
        assertTrue(engine.snapshot().detail!!.contains("Unsupported"))
        assertNull(engine.save())
        assertFalse(engine.writesConsoleSave)
    }

    @Test fun `pass script matches upstream command order and maximum size`() {
        val script = FrlgSaveBackupEngine.passesScript(32)
        assertEquals(792, script.size)
        assertEquals(2, le32(script, 0))
        assertEquals(25, le32(script, 4))
        repeat(32) { pass ->
            val at = 8 + pass * 24
            assertEquals(listOf(14, 21, 3), listOf(le32(script, at), le32(script, at + 8), le32(script, at + 16)))
        }
        assertEquals(2, le32(script, script.size - 16))
        assertEquals(16, le32(script, script.size - 12))
        assertEquals(4, le32(script, script.size - 8))
    }

    private fun gameData(code: String) = ByteArray(0x64).also {
        put32(it, 0, 0x101); put32(it, 4, 1); put32(it, 8, 1); put32(it, 12, 1); put32(it, 16, 1)
        code.encodeToByteArray().copyInto(it, 0x5c)
    }

    /** Upstream token coding, grouped without splitting a token between responses. */
    private fun deflateMessages(data: ByteArray): List<ByteArray> {
        val tokens = mutableListOf<ByteArray>()
        var i = 0
        while (i < data.size) {
            var run = 1
            while (i + run < data.size && run < 130 && data[i + run] == data[i]) run++
            if (run >= 3) {
                tokens += byteArrayOf((0x80 + run - 3).toByte(), data[i])
                i += run
            } else {
                val start = i
                while (i < data.size && i - start < 128) {
                    if (i > start && i + 2 < data.size && data[i] == data[i + 1] && data[i] == data[i + 2]) break
                    i++
                }
                tokens += byteArrayOf((i - start - 1).toByte()) + data.copyOfRange(start, i)
            }
        }
        val messages = mutableListOf<ByteArray>()
        var current = ArrayList<Byte>()
        tokens.forEach { token ->
            if (current.size + token.size > 900) {
                messages += current.toByteArray()
                current = ArrayList()
            }
            current.addAll(token.toList())
        }
        if (current.isNotEmpty()) messages += current.toByteArray()
        return messages
    }

    private fun put32(data: ByteArray, at: Int, value: Int) = repeat(4) { i -> data[at + i] = (value ushr (8 * i)).toByte() }
    private fun le32(data: ByteArray, at: Int) = (0 until 4).fold(0) { v, i -> v or ((data[at + i].toInt() and 0xff) shl (8 * i)) }
}
