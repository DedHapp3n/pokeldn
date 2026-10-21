import re
t = open(r'E:\VS\repos\pokeldn\build\pret-pokefirered\src\text.c', encoding='utf-8', errors='ignore').read()
out = []
# every occurrence of DecompressGlyph_Normal (exact word, not Copy)
for m in re.finditer(r'DecompressGlyph_Normal\b(?!Copy)', t):
    out.append('DGN @%d: %s' % (m.start(), t[max(0,m.start()-120):m.start()+60].replace('\n',' ')))
out.append('\n=== struct with maxLetterHeight ===')
i = t.find('maxLetterHeight')
# find struct start
s = t.rfind('struct', 0, i)
s = t.rfind('{', 0, i)
out.append(t[max(0,s-200):s+400])
out.append('\n=== gFonts array def ===')
for m in re.finditer(r'gFonts\s*\[', t):
    out.append('@%d:\n%s\n---\n' % (m.start(), t[max(0,m.start()-300):m.start()+800]))
open(r'E:\VS\repos\pokeldn\build\fontref.txt','w').write('\n'.join(out))
print('n', len(out))