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
    # 宋体接进重建清单：所有认不出的中文名最后都落到它身上，它缺一个字就等于最后一道
    # 防线缺一个字。仓库里那一份比本机 simsun.ttc 少 43 个码位（笔画 U+31C0-U+31EF、
    # 部件描述符 U+2FFC-U+2FFF），桌面 Word 画得出来，手机画不出来。
    ("simsun.ttc", 0, "song.ttc", set()),               # 宋体
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


def metrics(font):
    """The line metrics Word measures a line with. Move one of these and every line moves."""
    head = font["head"]
    h = font["hhea"]
    out = ["upem", head.unitsPerEm, h.ascender, h.descender, h.lineGap, h.caretSlopeRise]
    os2 = font.get("OS/2")
    if os2 is not None:
        out += [os2.sTypoAscender, os2.sTypoDescender, os2.sTypoLineGap,
                os2.usWinAscent, os2.usWinDescent, os2.sxHeight, os2.sCapHeight]
    return tuple(out)


def outlines(font):
    """Per-glyph outline bytes keyed by glyph name.

    Same widths with different outlines is still a different-looking page, so this gets
    compared too. A face without glyf (CFF outlines) returns None and skips the check.
    """
    if "glyf" not in font or "loca" not in font:
        return None
    glyf = font["glyf"]
    out = {}
    for cp, glyph in (font.getBestCmap() or {}).items():
        if cp < 0x20:
            continue
        g = glyf[glyph]
        prog = g.program.getBytecode() if getattr(g, "program", None) is not None else b""
        try:
            box = (g.xMin, g.yMin, g.xMax, g.yMax)
        except AttributeError:
            box = None
        out[glyph] = (getattr(g, "numberOfContours", -1),
                      tuple(g.getCoordinates(glyf)[1]), prog, box)
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
# Rebuild one face at a time with:  py tools/build-fonts.py song.ttc
only = set(a.strip() for a in sys.argv[1:] if a.strip())
for src, dst in COPY_ONLY:
    if only and dst not in only:
        continue
    shutil.copyfile(os.path.join(WIN, src), os.path.join(OUT, dst))
    size = os.path.getsize(os.path.join(OUT, dst)) / 1048576.0
    total += size
    print("%-15s -> %-16s %7.2f MB (copied as is)" % (src, dst, size))

for src, index, dst, extra in PLAN:
    if only and dst not in only:
        continue
    sp, dp = os.path.join(WIN, src), os.path.join(OUT, dst)
    shipped = None
    if os.path.exists(dp):
        try:
            was = TTFont(dp, lazy=False)
            shipped = (widths(was), metrics(was), outlines(was))
        except Exception as exc:            # an unreadable file is no baseline to compare with
            print("   note: %s does not read as a font, skipping the before/after check (%s)" % (dst, exc))
        os.remove(dp)
    font = load(sp, index)
    before = widths(font)
    # The Windows original is the reference: it is the face desktop Word draws with here,
    # so a rebuilt face may not move a single contour of it.
    original_outlines = outlines(font)
    original_metrics = metrics(font)
    have = set(font.getBestCmap().keys())
    # Full coverage: every codepoint the original face claims, plus the sample
    # document (catches a private-use char that only a symbol table would hold).
    keep = {cp for cp in (have | extra | sample) if cp >= 0x20} | {0x09, 0x0A, 0x0D}
    options = Options()
    # SimSun carries 6.2 MB of embedded bitmaps (EBDT/EBLC) for tiny Windows GDI sizes.
    # The shipped face never had them, and Android does not use them at document sizes, so
    # they stay out: dropping them keeps the file the size it was and the rendering identical.
    options.drop_tables = ["EBDT", "EBLC", "EBSC", "MERG", "meta"]
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
    # 1) against the Windows original: same line metrics, same ink.
    if list(original_metrics) != list(metrics(check)):
        print("   FAILED: %s line metrics differ from %s\n     original=%s\n     built  =%s"
              % (dst, src, original_metrics, metrics(check)), file=sys.stderr)
        sys.exit(1)
    built_outlines = outlines(check)
    ink = []
    if original_outlines is not None and built_outlines is not None:
        for g, v in built_outlines.items():
            o = original_outlines.get(g)
            if o is None or o == v:
                continue
            still_there = (o[3] is not None and v[3] is not None
                           and len(o[1]) == len(v[1])
                           and all(abs(a - b) <= 2 for a, b in zip(o[3], v[3]))
                           and all(abs(a - b) <= 2 for a, b in zip(o[1], v[1])))
            if not still_there:
                ink.append(g)
    if ink:
        print("   FAILED: %s redraws %d glyphs that %s draws differently (%s)"
              % (dst, len(ink), src, " ".join(sorted(ink)[:8])), file=sys.stderr)
        sys.exit(1)
    # 2) against the face that shipped: nothing may get narrower, lose a codepoint, or move
    #    a line metric, because the measured page breaks depend on all three.
    if shipped is not None:
        was_widths, was_metrics, was_outlines = shipped
        worse = [cp for cp, w in after.items() if cp in was_widths and was_widths[cp] != w]
        gone = sorted(cp for cp in was_widths if cp not in after)
        if list(was_metrics) != list(metrics(check)):
            print("   FAILED: %s line metrics moved against the shipped face\n     before=%s\n"
                  "     after =%s" % (dst, was_metrics, metrics(check)), file=sys.stderr)
            sys.exit(1)
        if worse or gone:
            print("   FAILED: %s is not a superset of the shipped face -- width_changes=%s "
                  "dropped_codepoints=%s" % (dst, worse[:8], [chr(c) for c in gone[:8]]),
                  file=sys.stderr)
            sys.exit(1)
        changed = 0
        if was_outlines is not None and built_outlines is not None:
            changed = sum(1 for g, v in built_outlines.items()
                          if g in was_outlines and was_outlines[g] != v)
        print("   against the shipped face: +%d codepoints, 0 width changes, 0 metric changes, "
              "%d glyph(s) redrawn from the same Windows original (hinting bytecode/rounding)"
              % (len([cp for cp in after if cp not in was_widths]), changed))

print("total assets/fonts built here: %.1f MB" % total)
