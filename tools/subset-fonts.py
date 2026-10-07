#!/usr/bin/env python3
"""Reproducible build-only subsets; never discard existing CJK character coverage."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
from fontTools import subset
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets/fonts'
ORIGINALS = ROOT / 'resources/fonts/subset-originals'
LATIN = [(0x20, 0x24F), (0x300, 0x52F), (0x1E00, 0x1EFF), (0x2000, 0x26FF),
         (0x27C0, 0x2AFF), (0x1D400, 0x1D7FF), (0xFE00, 0xFE0F)]
REQUIRED = 'αβγδεζηθικλμνξπρστυφχψωΓΔΘΛΞΠΣΦΨΩ∫∑∏√∞≈≠≤≥±×÷∂∇∈∉⊂⊃∪∩∀∃∴∵°′″‰ℏℓℝℕℤℚℂ₀₁₂₃₄₅₆₇₈₉⁰¹²³⁴⁵⁶⁷⁸⁹'
CJK = {'song.ttc', 'simhei.ttf', 'kaiti.ttf', 'fangsong.ttf', 'fz-small-song.ttf'}


def make_subset(name, source, all_chars=False):
    font = TTFont(source, fontNumber=0, recalcTimestamp=False)
    before_cmap = font.getBestCmap()
    before_math = font.getTableData('MATH') if 'MATH' in font else None
    options = subset.Options()
    options.layout_features = ['*']
    options.name_IDs = ['*']
    options.name_languages = [0x409, 0x804, 0x404]
    options.notdef_glyph = True
    options.notdef_outline = True
    options.recommended_glyphs = True
    options.hinting = True
    options.drop_tables += ['DSIG', 'EBDT', 'EBLC', 'EBSC']
    engine = subset.Subsetter(options=options)
    chars = set(before_cmap) if all_chars else {
        cp for lo, hi in LATIN for cp in range(lo, hi + 1)} | {ord(c) for c in REQUIRED}
    engine.populate(unicodes=chars)
    engine.subset(font)
    after_cmap = font.getBestCmap()
    if all_chars and set(after_cmap) != set(before_cmap):
        raise RuntimeError('CJK coverage changed: ' + name)
    if before_math is not None and 'MATH' not in font:
        raise RuntimeError('MATH table lost: ' + name)
    output = ASSETS / name
    temp = output.with_suffix('.subset.tmp')
    font.save(temp)
    temp.replace(output)
    return {'file': name, 'originalBytes': Path(source).stat().st_size,
            'subsetBytes': output.stat().st_size, 'characters': len(after_cmap),
            'keepsMath': 'MATH' in font, 'cjkCoverageUnchanged': all_chars,
            'sha256': hashlib.sha256(output.read_bytes()).hexdigest()}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--math-source', type=Path)
    args = parser.parse_args()
    ORIGINALS.mkdir(parents=True, exist_ok=True)
    report = []
    for source in sorted(ASSETS.glob('*')):
        if source.suffix not in ['.ttf', '.ttc'] or source.name == 'stix-two-math.ttf':
            continue
        backup = ORIGINALS / source.name
        if not backup.exists():
            shutil.copy2(source, backup)
        report.append(make_subset(source.name, backup, source.name in CJK))
    if args.math_source:
        report.append(make_subset('stix-two-math.ttf', args.math_source))
    elif (ASSETS / 'stix-two-math.ttf').exists():
        report.append({'file': 'stix-two-math.ttf', 'subsetBytes': (ASSETS / 'stix-two-math.ttf').stat().st_size})
    union = set()
    for source in ASSETS.glob('*'):
        if source.suffix in ['.ttf', '.ttc']:
            union.update(TTFont(source, fontNumber=0).getBestCmap())
    missing = [c for c in REQUIRED if ord(c) not in union]
    if missing:
        raise RuntimeError('Missing required characters: ' + ''.join(missing))
    result = {'fonts': report, 'requiredCharacters': REQUIRED, 'missing': missing,
              'totalBytes': sum(p.stat().st_size for p in ASSETS.iterdir() if p.is_file())}
    dest = ROOT / 'reports/current/font-subsets.json'
    dest.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf8')
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
