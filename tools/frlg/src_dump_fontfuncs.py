import re
t = open(r'E:\VS\repos\pokeldn\build\pret-pokefirered\src\text.c', encoding='utf-8', errors='ignore').read()
# print the definition bodies of the normal-latin font functions
for m in re.finditer(r'(static )?(?:u8|void) (?:DecompressGlyph|GetGlyphWidth)_(?:Normal|NormalCopy1|NormalCopy2)\(', t):
    i = m.start()
    j = t.find('{', i)
    depth = 0
    k = j
    while k < len(t):
        if t[k] == '{':
            depth += 1
        elif t[k] == '}':
            depth -= 1
            if depth == 0:
                break
        k += 1
    print('#### %s (offset %d)' % (t[i:j].strip(), i))
    print(t[i:k+1])
    print()