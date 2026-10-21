txt = open(r'E:\VS\repos\pokeldn\build\pret-pokefirered\src\text.c', encoding='utf-8', errors='ignore').read()

def extract(name):
    i = txt.find(name)
    while i >= 0:
        # definition has a '(' then params then ')' then '{' with body
        j = txt.find('{', i)
        # skip if this is a forward declaration ending in ';'
        if txt.find(';', i) < j and txt.find(';', i) != -1:
            i = txt.find(name, i + 1)
            continue
        depth = 0
        k = j
        while k < len(txt):
            if txt[k] == '{':
                depth += 1
            elif txt[k] == '}':
                depth -= 1
                if depth == 0:
                    break
            k += 1
        body = txt[i:k + 1]
        if 'glyphs' in body or 'pixels' in body:
            print('===================', name, 'at', i, '===================')
            print(body)
            print()
            return
        i = txt.find(name, i + 1)
    print('DEF NOT FOUND:', name)

extract('static void DecompressGlyph_NormalCopy2(')
extract('void DecompressGlyphTile(')