package com.dedhapp3n.pokeldn.za

import org.junit.Assert.*
import org.junit.Test

class ZaSaveDocumentTest {
    private fun snapshot(count: Int, unreadable: Int = 0): ZaSnapshot = ZaSnapshot(
        "00000000-0000-0000-0000-000000000000", 1, 2, true, null, null, null,
        List(count) { ZaCapturedPokemon("$it", byteArrayOf(), fixturePokemon(), 1, 2, 1) },
        count, 0, null, null, unreadablePreviews = unreadable,
    )

    private fun fixturePokemon(): ZaPokemonInfo =
        ZaMessages.preview(javaClass.getResourceAsStream("/za/preview.bin")!!.use { it.readBytes() }).pokemon

    @Test fun partialCapturePaginatesThirtyWithoutClaimingBoxPositions() {
        val doc = ZaSaveDocument.fromCapture(snapshot(34))
        assertFalse(doc.isFullSave)
        assertEquals(2, doc.pages.size)
        assertEquals("Captured Page 1", doc.pages[0].label)
        assertEquals(null, doc.pages[0].verifiedBoxNumber)
        assertEquals(30, doc.pages[0].cells.count { it is ZaGridCell.Pokemon })
        assertEquals(4, doc.pages[1].cells.count { it is ZaGridCell.Pokemon })
        assertEquals(26, doc.pages[1].cells.count { it is ZaGridCell.Empty })
    }

    @Test fun emptyAndUnreadableCellsAreExplicitAndFullSaveStateIsDistinct() {
        val partial = ZaSaveDocument.fromCapture(snapshot(0, 2))
        assertEquals(ZaGridCell.Unreadable(2), partial.pages.single().cells.first())
        assertEquals(29, partial.pages.single().cells.count { it == ZaGridCell.Empty })
        assertTrue(partial.description.contains("Partial"))
        val full = ZaSaveDocument(ZaDocumentSource.FULL_SAVE,
            listOf(ZaGridPage(1, List(30) { ZaGridCell.Empty }, verifiedBoxNumber = 1)), null)
        assertTrue(full.isFullSave)
        assertEquals("Box 1", full.pages.single().label)
    }
}
