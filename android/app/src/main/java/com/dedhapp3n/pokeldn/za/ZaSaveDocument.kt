package com.dedhapp3n.pokeldn.za

/** Completeness is independent of the screen used to display a document. */
internal enum class ZaDocumentSource { LOCAL_WIRELESS_CAPTURE, FULL_SAVE }

internal sealed interface ZaGridCell {
    data class Pokemon(val pokemonIndex: Int, val info: ZaPokemonInfo) : ZaGridCell
    data class Unreadable(val previewCount: Int) : ZaGridCell
    data object Empty : ZaGridCell
}

internal data class ZaGridPage(
    val number: Int,
    val cells: List<ZaGridCell>,
    val verifiedBoxNumber: Int? = null,
) {
    init { require(cells.size == PAGE_SIZE) }
    val label: String get() = verifiedBoxNumber?.let { "Box $it" } ?: "Captured Page $number"
}

internal data class ZaSaveDocument(
    val source: ZaDocumentSource,
    val pages: List<ZaGridPage>,
    val trainer: ZaTrainer?,
) {
    val isFullSave: Boolean get() = source == ZaDocumentSource.FULL_SAVE
    val description: String get() = if (isFullSave) "Complete Z-A save" else
        "Partial live capture from local wireless. Box positions and full save data are unavailable."

    companion object {
        fun fromCapture(snapshot: ZaSnapshot): ZaSaveDocument {
            // Unreadable messages cannot be tied to distinct save slots; group their count in one cell.
            val entries = snapshot.pokemon.mapIndexed { index, pokemon -> ZaGridCell.Pokemon(index, pokemon.summary) } +
                if (snapshot.unreadablePreviews > 0) listOf(ZaGridCell.Unreadable(snapshot.unreadablePreviews)) else emptyList()
            val pages = (if (entries.isEmpty()) listOf(emptyList()) else entries.chunked(PAGE_SIZE))
                .mapIndexed { index, cells ->
                    ZaGridPage(index + 1, cells + List(PAGE_SIZE - cells.size) { ZaGridCell.Empty })
                }
            return ZaSaveDocument(ZaDocumentSource.LOCAL_WIRELESS_CAPTURE, pages, snapshot.trainer)
        }
    }
}

private const val PAGE_SIZE = 30
