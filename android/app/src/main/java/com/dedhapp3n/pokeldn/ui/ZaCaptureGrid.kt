package com.dedhapp3n.pokeldn.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dedhapp3n.pokeldn.sprites.PokemonSpriteKey
import com.dedhapp3n.pokeldn.sprites.PokemonSpriteRepository
import com.dedhapp3n.pokeldn.ui.theme.CreamPanelDark
import com.dedhapp3n.pokeldn.ui.theme.DeviceBezel
import com.dedhapp3n.pokeldn.za.ZaGridCell
import com.dedhapp3n.pokeldn.za.ZaGridPage
import com.dedhapp3n.pokeldn.za.ZaNames

@Composable
internal fun ZaCaptureGrid(
    page: ZaGridPage,
    names: ZaNames,
    sprites: PokemonSpriteRepository,
    onPokemon: (Int) -> Unit,
) {
    ShellPanel(page.label) {
        page.cells.chunked(5).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { cell ->
                    Surface(
                        modifier = Modifier.weight(1f).then(if (cell is ZaGridCell.Pokemon)
                            Modifier.clickable { onPokemon(cell.pokemonIndex) } else Modifier),
                        color = CreamPanelDark,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, DeviceBezel),
                    ) {
                        Column(Modifier.padding(3.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            when (cell) {
                                is ZaGridCell.Pokemon -> {
                                    val info = cell.info
                                    PokemonSprite(sprites, info.species.takeIf { it > 0 }
                                        ?.let { PokemonSpriteKey(it, info.form.coerceAtLeast(0), info.shiny) }, 42.dp)
                                    Text(names.species(info.species), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    Text("Lv. ${info.level}${if (info.shiny) " ✦" else ""}",
                                        style = MaterialTheme.typography.labelSmall)
                                }
                                is ZaGridCell.Unreadable -> {
                                    PokemonSprite(sprites, null, 42.dp)
                                    Text("${cell.previewCount} unreadable preview${if (cell.previewCount == 1) "" else "s"}", maxLines = 2,
                                        style = MaterialTheme.typography.labelSmall)
                                }
                                ZaGridCell.Empty -> {
                                    Spacer(Modifier.size(42.dp))
                                    Text("Empty", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
