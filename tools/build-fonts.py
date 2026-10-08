"""Build the bundled font set from the fonts already installed on this machine.

Big CJK faces are subset down to the character set a Chinese thesis can actually
contain (GB2312 + the punctuation/symbol blocks Word uses). Advance widths,
line metrics and hinting are copied untouched, so pagination does not change.
"""
import os, sys, zipfile
from fontTools.ttLib import TTFont, TTCollection
from fontTools.subset import Subsetter, Options

WIN = r"C:\Windows\Fonts"
OUT = r"E:\download\claude\Wordlite\app\src\main\assets\fonts"

def base_uni():
    cps = set(range(0x20, 0x7F)) | set(range(0xA0, 0x100))
    for lo, hi in [(0x2000, 0x206F), (0x20A0, 0x20BF), (0x2100, 0x218F), (0x2190, 0x21FF),
                   (0x2460, 0x24FF), (0x25A0, 0x25FF), (0x2600, 0x267F), (0x3000, 0x303F),
                   (0x3105, 0x312F), (0x31C0, 0x31EF), (0xFE10, 0xFE19), (0xFE30, 0xFE4F),
                   (0xFF01, 0xFF5E), (0xFFE0, 0xFFE6), (0x02C0, 0x02FF), (0x2E80, 0x2EFF)]:
        cps |= set(range(lo, hi + 1))
    for a in range(0xA1, 0xFF):          # every printable GB2312 double byte
        for b in range(0xA1, 0xFF):
            try:
                s = bytes((a, b)).decode("gb2312")
            except Exception:
                continue
            for ch in s:
                if ord(ch) > 0x7F: cps.add(ord(ch))
    return cps

GB = base_uni()
KANA = set(range(0x3041, 0x3097)) | set(range(0x30A1, 0x30F7)) | {0x30FB, 0x30FC, 0x300C, 0x300D, 0x3001, 0x3002}

DOC_CPS = set()
with zipfile.ZipFile(r"E:\download\claude\Wordlite\tests\samples\input-liu.docx") as z:
    import re
    for name in z.namelist():
        if name.endswith(".xml"):
            for m in re.finditer(rb"<w:t[^>]*>(.*?)</w:t>", z.read(name), re.S):
                for c in m.group(1).decode("utf8", "ignore"): DOC_CPS.add(ord(c))

PLAN = [
    # src, src index, out name, extra unicodes, full copy (no subsetting)?
    ("STXINWEI.TTF", 0, "stxinwei.ttf", set(), True),
    ("STLITI.TTF", 0, "stliti.ttf", set(), True),
    ("STXINGKA.TTF", 0, "stxingka.ttf", set(), True),
    ("SIMLI.TTF", 0, "lisu.ttf", set(), False),
    ("SIMYOU.TTF", 0, "youyuan.ttf", set(), False),
    ("Deng.ttf", 0, "dengxian.ttf", set(), False),
    ("msgothic.ttc", 0, "msgothic.ttf", KANA, False),
    ("STSONG.TTF", 0, "stsong.ttf", set(), False),
    ("STZHONGS.TTF", 0, "stzhongsong.ttf", set(), False),
    ("STKAITI.TTF", 0, "stkaiti.ttf", set(), False),
    ("STFANGSO.TTF", 0, "stfangsong.ttf", set(), False),
    ("STXIHEI.TTF", 0, "stxihei.ttf", set(), False),
]
COPY_ONLY = [("symbol.ttf", "symbol.ttf"), ("cour.ttf", "courier-new.ttf"),
             ("consola.ttf", "consolas.ttf")]

def widths(f):
    if f.getBestCmap() is None: return {}
    cmap = f.getBestCmap(); hm = f["hmtx"]; out = {}
    for cp, g in cmap.items():
        if cp < 0x20: continue
        out[cp] = hm[g][0]
    return out

import shutil
for src, dst in COPY_ONLY:
    shutil.copyfile(os.path.join(WIN, src), os.path.join(OUT, dst))
    print('%-16s -> %-16s %7.2fMB (copied as is)' % (src, dst, os.path.getsize(os.path.join(OUT, dst)) / 1048576.0))

for src, idx, dst, extra, full in PLAN:
    sp = os.path.join(WIN, src)
    dp = os.path.join(OUT, dst)
    if os.path.exists(dp): os.remove(dp)
    if src.lower().endswith(".ttc"):
        f = TTCollection(sp, lazy=False).fonts[idx]
    else:
        f = TTFont(sp, lazy=False)
    before = widths(f)
    have = set(f.getBestCmap().keys())
    keep = (have & (GB | DOC_CPS | extra)) | {0x20, 0x0D, 0x09}
    if not full:
        opt = Options()
        opt.drop_tables = []
        opt.recalc_bounds = False
        opt.name_IDs = [1, 2, 3, 4, 6, 16]
        s = Subsetter(options=opt)
        s.populate(unicodes=sorted(keep))
        s.subset(f)
    f.save(dp)
    g = TTFont(dp, lazy=False)
    after = widths(g)
    diff = sum(1 for cp, w in after.items() if before.get(cp) != w)
    print("%-16s -> %-16s %7.2fMB  glyphs=%-6d kept=%-6d dropped=%-6d width_changes=%d  hhea.caretSlope=%s os2.typo=%s" % (
        src, dst, os.path.getsize(dp) / 1048576.0, g["maxp"].numGlyphs, len(after), len(before) - len(after), diff,
        g["hhea"].caretSlopeRise, g["OS/2"].sTypoAscender))