Pixel-style front PNGs are loaded on demand from the direct `pokemon/` paths in
[PokeAPI/sprites](https://github.com/PokeAPI/sprites). Normal and shiny URLs are
separate. The repository documents these as its default Gen 5 style sprites.

Only form 0 uses a direct species URL. A nonzero form, missing image, or network
failure shows a placeholder; the app does not substitute a potentially wrong
base-form image. Successful images are stored in the app-private sprite cache
and survive app restarts. No sprite pack is bundled in the APK.
