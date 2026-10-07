#!/usr/bin/env python3
"""Validate real cmap/layout tables rather than platform-font fallbacks."""
from pathlib import Path
import json
from fontTools.ttLib import TTFont
ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets/fonts'
report = json.loads((ROOT / 'reports/current/font-subsets.json').read_text())
required = report['requiredCharacters']
checks = 0
for record in report['fonts']:
    font = TTFont(ASSETS / record['file'], fontNumber=0)
    assert font.getBestCmap(), record['file']
    assert 'OS/2' in font and 'head' in font and 'hmtx' in font
    checks += 2
    if record.get('cjkCoverageUnchanged'):
        original = TTFont(ROOT / 'resources/fonts/subset-originals' / record['file'], fontNumber=0)
        assert set(original.getBestCmap()) == set(font.getBestCmap()), record['file']
        assert original['head'].unitsPerEm == font['head'].unitsPerEm
        assert original['OS/2'].usWinAscent == font['OS/2'].usWinAscent
        assert original['OS/2'].usWinDescent == font['OS/2'].usWinDescent
        checks += 4
math = TTFont(ASSETS / 'stix-two-math.ttf')
assert 'MATH' in math
checks += 1
for char in required:
    assert ord(char) in math.getBestCmap(), f'No mathematical glyph U+{ord(char):04X}'
    checks += 1
assert 'GSUB' in math and 'GPOS' in math
checks += 1
assert report['totalBytes'] < 80 * 1024 * 1024
checks += 1
print(f'SUMMARY {checks} font subset/coverage assertions passed; Android glyph drawing not tested')
