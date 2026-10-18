package com.dedhapp3n.pokeldn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** A small accent layer over the same shared PokeLDN components. */
internal data class GameSkin(val label: String, val accent: Color, val secondary: Color)

internal object GameSkins {
    val FRLG = GameSkin("GEN III · GBA", Color(0xFFFFC55C), Color(0xFF8CDE8B))
    val ZA = GameSkin("LEGENDS: Z-A · READER", Color(0xFF83DBEF), Color(0xFF9E95EC))
}

@Composable
internal fun GameScreenTitle(skin: GameSkin, code: String, title: String, subtitle: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DeviceScreenTitle(code, title, subtitle)
        Surface(color = skin.accent, modifier = Modifier.fillMaxWidth().height(4.dp)) {}
    }
}
