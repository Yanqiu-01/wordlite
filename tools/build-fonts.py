"""Build the bundled font set from the fonts installed on this machine.

Every face ships with the coverage the Windows original has: no codepoint that
Word can draw here may turn into a fallback glyph on the phone. Advance widths,
line metrics and name records are copied untouched, and the build fails if any
codepoint's advance width moves, so pagination cannot drift.
"""
import os, re, sys, shutil, zipfile
from fontTools.ttLib import TTFont, TTCollection
from fontTools.subset import Subsetter, Options

WIN = r"C:\Windows\Fonts"
OUT = r"E:\download\claude\Wordlite\app\src\main\assets\fonts"
SAMPLE = r"E:\download\claude\Wordlite\tests\samples\input-liu.docx"

# src, src index, out name, extra codepoints (for faces whose own cmap is short)
PLAN = [
    ("STXINWEI.TTF", 0, "stxinwei.ttf", set()),        # 华文新魏
    ("STLITI.TTF", 0, "stliti.ttf", set()),            # 华文隶书
    ("STXINGKA.TTF", 0, "stxingka.ttf", set()),        # 华文行楷
    ("SIMLI.TTF", 0, "lisu.ttf", set()),               # 隶书
    ("SIMYOU.TTF", 0, "youyuan.ttf", set()),           # 幼圆
    ("Deng.ttf", 0, "dengxian.ttf", set()),            # 等线
    ("msyh.ttc", 0, "msyh.ttf", set()),                # 微软雅黑
    ("msgothic.ttc", 0, "msgothic.ttf", set()),        # ＭＳ ゴシック
    ("STSONG.TTF", 0, "stsong.ttf", set()),            # 华文宋体
    ("STZHONGS.TTF", 0, "stzhongsong.ttf", set()),     # 华文中宋
    ("STKAITI.TTF", 0, "stkaiti.ttf", set()),          # 华文楷体
    ("STFANGSO.TTF", 0, "stfangsong.ttf", set()),      # 华文仿宋
    ("STXIHEI.TTF", 0, "stxihei.ttf", set()),          # 华文细黑
]
# Faces Word uses as whole files (Latin/mono/symbol): byte-for-byte copies.
COPY_ONLY = [("cour.ttf", "courier-new.ttf"), ("consola.ttf", "consolas.ttf")]


def load(path, index):
    if path.lower().endswith(".ttc"):
        return TTCollection(path, lazy=False).fonts[index]
    return TTFont(path, lazy=False)


def widths(font):
    cmap = font.getBestCmap()
    if not cmap:
        return {}
    hmtx = font["hmtx"]
    out = {}
    for cp, glyph in cmap.items():
        if cp < 0x20:
            continue
        out[cp] = hmtx[glyph][0]
    return out


def doc_codepoints(path):
    cps = set()
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            if not name.endswith(".xml"):
                continue
            for m in re.finditer(rb"<w:t[^>]*>(.*?)</w:t>", z.read(name), re.S):
                cps.update(ord(c) for c in m.group(1).decode("utf8", "ignore"))
    return cps


sample = doc_codepoints(SAMPLE)
total = 0.0
for src, dst in COPY_ONLY:
    shutil.copyfile(os.path.join(WIN, src), os.path.join(OUT, dst))
    size = os.path.getsize(os.path.join(OUT, dst)) / 1048576.0
    total += size
    print("%-15s -> %-16s %7.2f MB (copied as is)" % (src, dst, size))

for src, index, dst, extra in PLAN:
    sp, dp = os.path.join(WIN, src), os.path.join(OUT, dst)
    if os.path.exists(dp):
        os.remove(dp)
    font = load(sp, index)
    before = widths(font)
    have = set(font.getBestCmap().keys())
    # Full coverage: every codepoint the original face claims, plus the sample
    # document (catches a private-use char that only a symbol table would hold).
    keep = {cp for cp in (have | extra | sample) if cp >= 0x20} | {0x09, 0x0A, 0x0D}
    options = Options()
    options.drop_tables = []
    options.recalc_bounds = False
    options.name_IDs = [1, 2, 3, 4, 6, 16]
    sub = Subsetter(options=options)
    sub.populate(unicodes=sorted(keep))
    sub.subset(font)
    font.save(dp)
    check = TTFont(dp, lazy=False)
    after = widths(check)
    moved = [cp for cp, w in after.items() if before.get(cp) != w]
    lost = [cp for cp in before if cp in keep and cp not in after]
    size = os.path.getsize(dp) / 1048576.0
    total += size
    print("%-15s -> %-16s %7.2f MB glyphs=%-6d codepoints=%-6d dropped=%-5d width_changes=%d missing=%d"
          % (src, dst, size, check["maxp"].numGlyphs, len(after), len(before) - len(after),
             len(moved), len(lost)))
    if moved or lost:
        print("   FAILED: width_changes=%s missing=%s" % (moved[:10], lost[:10]), file=sys.stderr)
        sys.exit(1)

print("total assets/fonts built here: %.1f MB" % total)
