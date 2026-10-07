#!/usr/bin/env python3
from pathlib import Path
from pypdf import PdfReader
from fontTools.ttLib import TTFont
from io import BytesIO
import sys
path = Path(sys.argv[1])
reader = PdfReader(path, strict=True)
assert len(reader.pages) >= 1
print('PDF', path.name, 'pages=', len(reader.pages), 'bytes=', path.stat().st_size)
seen = set()
for i, page in enumerate(reader.pages):
    fonts = page['/Resources'].get('/Font', {})
    print('PAGE', i + 1, 'box=', list(page.mediabox), 'text=', repr(page.extract_text()))
    for key, reference in fonts.items():
        font = reference.get_object()
        descendant = font['/DescendantFonts'][0].get_object()
        descriptor = descendant['/FontDescriptor'].get_object()
        assert '/FontFile2' in descriptor, 'font must be embedded'
        assert '/ToUnicode' in font, 'Unicode extraction required'
        data = descriptor['/FontFile2'].get_object().get_data()
        actual = TTFont(BytesIO(data))
        assert actual.getBestCmap()
        from pathlib import Path
        import hashlib
        font_hashes = {hashlib.sha256(p.read_bytes()).hexdigest() for p in Path('/workspace/wordlite/app/src/main/assets/fonts').glob('*')}
        assert hashlib.sha256(data).hexdigest() in font_hashes, 'PDF embeds real bundled subset, not Android fallback'
        if key not in seen:
            print('EMBEDDED', key, font['/BaseFont'], 'bytes=', len(data), 'glyphs=', len(actual.getGlyphOrder()), 'MATH=', 'MATH' in actual)
            seen.add(key)
assert seen
print('SUMMARY PDF independently parsed; embedded fonts and ToUnicode valid')
