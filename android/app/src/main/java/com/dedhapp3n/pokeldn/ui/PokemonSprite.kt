package com.dedhapp3n.pokeldn.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.dedhapp3n.pokeldn.sprites.PokemonSpriteKey
import com.dedhapp3n.pokeldn.sprites.PokemonSpriteRepository

@Composable
internal fun PokemonSprite(repository: PokemonSpriteRepository, key: PokemonSpriteKey?, size: Dp, modifier: Modifier = Modifier) {
    val bitmap = produceState<android.graphics.Bitmap?>(null, repository, key) {
        value = if (key == null) null else withContext(Dispatchers.IO) {
            repository.load(key)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
    }.value
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (bitmap == null) Text("?", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
        else Image(bitmap.asImageBitmap(), contentDescription = "Pokémon sprite",
            modifier = Modifier.size(size), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
    }
}
