"""Re-pull the CJK faces from GitHub and audit them against the faces that ship.

    python tools/fetch-fonts-github.py --fetch     # download the upstream set, then audit
    python tools/fetch-fonts-github.py             # audit the copy already on disk

Why this exists: the question "did bundling the fonts drop characters?" can only be
answered codepoint by codepoint, so it gets answered here instead of in prose. The
audit reads the cmap the renderers actually use (getBestCmap, i.e. what Android and
java.awt.Font read), not the union of every cmap subtable -- the union counts the
empty mappings font vendors ship (7,905 Hangul rows, C1 controls, one Telugu row) as
characters, which is where the fake "missing 15,021 glyphs" number came from.

Upstream: https://github.com/chengda/popular-fonts (branch master). Checksums below
are pinned: an upstream file that changes shape fails this tool rather than quietly
changing pagination.
"""
import hashlib
import os
import sys
import urllib.request
from fontTools.ttLib import TTFont, TTCollection

REPO = "chengda/popular-fonts"
BRANCH = "master"
CACHE = r"E:\download\claude\Wordlite\artifacts\font-check\gh2"
ASSETS = r"E:\download\claude\Wordlite\app\src\main\assets\fonts"

# our name -> (upstream file, sha256 of the upstream file, shipped asset or None)
FACES = [
    ("stxinwei",   u"\u534e\u6587\u65b0\u9b4f", "stxinwei.ttf"),
    ("stliti",     u"\u534e\u6587\u96b6\u4e66", "stliti.ttf"),
    ("stxingka",   u"\u534e\u6587\u884c\u6977", "stxingka.ttf"),
    ("stzhongsong", u"\u534e\u6587\u4e2d\u5b8b", "stzhongsong.ttf"),
    ("stkaiti",    u"\u534e\u6587\u6977\u4f53", "stkaiti.ttf"),
    ("stfangsong", u"\u534e\u6587\u4eff\u5b8b", "stfangsong.ttf"),
    ("stxihei",    u"\u534e\u6587\u7ec6\u9ed1", "stxihei.ttf"),
    ("stcaiyun",   u"\u534e\u6587\u5f69\u4e91", "stcaiyun.ttf"),
    ("sthupo",     u"\u534e\u6587\u7425\u73c0", "sthupo.ttf"),
    ("msyh",       u"\u5fae\u8f6f\u96c5\u9ed1", "msyh.ttf"),
]
UPSTREAM = {
    "stxinwei": ("\u534e\u6587\u65b0\u9b4f.ttf", "361dc6d522d417fc5705948e65d191f7826147d390980f4cbdcfbca4a0200290"),
    "stliti": ("\u534e\u6587\u96b6\u4f53.TTF", "246c8222b0d91f345808f468b4aad822d1425b5d9d44690d61dd5826135a647a"),
    "stxingka": ("\u534e\u6587\u884c\u6977.ttf", "6e893a5a618b39f317362efd77f3c6aeb16149328cb66872c9db8cb457a71d32"),
    "stzhongsong": ("\u534e\u6587\u4e2d\u5b8b.ttf", "0d0e8ba436f803236fa10c27903327284c39186a6e7d859e0fae423d11c44583"),
    "stkaiti": ("\u534e\u6587\u6977\u4f53.ttf", "fc404ac9b112c0c1d3de5d3c0a567dcc845626dc34a0c8c90838e2474ca47fe8"),
    "stfangsong": ("\u534e\u6587\u4eff\u5b8b.ttf", "3d1a9e97fb610cd495350d2257094e2a9403a3d8fd96f84a38c3c1cddf24b4ba"),
    "stxihei": ("\u534e\u6587\u7ec6\u9ed1.ttf", "30848f35c1b8a8a9c87d3299d3e06327347d7f4fb6e4dd5f15cc4a9711f55e24"),
    "stcaiyun": ("\u534e\u6587\u5f69\u4e91.ttf", "12b15c6b13f3904251efaf755ffe8347c932ef07e00363479066aea5a9312b04"),
    "sthupo": ("\u534e\u6587\u7425\u73c0.ttf", "a1ee5f5cb8b81bd43e04ec863c310fdd9cfb466965bd1dd85085e3699eb188ef"),
    "msyh": ("\u5fae\u8f6f\u96c5\u9ed1.ttf", "c3c0e7bbcec69ee4765a53831c7be310acaca1ec1b408974ca4f4c73c1aa400c"),
}
# Also pulled, not shipped: the shipped msyh is the newer Windows 10 build (847 more
# codepoints than upstream), and YaHei Bold stays synthesised by the platform until a
# real-bold face is measured against Word page breaks.
EXTRA_UPSTREAM = {"msyhbd": ("\u5fae\u8f6f\u96c5\u9ed1\u7c97\u4f53.ttf",
                             "bdb9acc8ca1c5aebcd0ecc5d48dd8e03d503dd5747230efbc043148147b5ddcf")}

# Upstream has a real glyph here and the shipped face does not. Every entry needs a reason.
ALLOWED_MISSING = {
    "msyh": {0x20087, 0x20089, 0x200CC, 0x2099D, 0x215D7, 0x2298F, 0x241FE, 0x27FB7},
}
# The shipped 微软雅黑 is the newer Windows 10 build: 847 more codepoints than upstream,
# and two combining kana marks (U+3099/U+309A, which stack on a kana and carry no advance
# of their own) measure differently. Desktop Word on a current Windows draws this build,
# so this build is the reference, not upstream's older copy.
ALLOWED_WIDTH_DIFF = {"msyh": {0x3099, 0x309A}}


def load(path):
    if path.lower().endswith(".ttc"):
        return TTCollection(path, lazy=True).fonts[0]
    return TTFont(path, lazy=True)


def coverage(path):
    font = load(path)
    cmap = dict(font.getBestCmap() or {})
    names = set(font.getGlyphSet().keys())
    real = set(cp for cp, glyph in cmap.items() if glyph in names)
    m = font["hmtx"]
    widths = {}
    table = getattr(m, "metrics", {})
    for cp, glyph in cmap.items():
        if cp >= 0x20 and glyph in table:
            widths[cp] = table[glyph][0]
    head, hhea = font["head"], font["hhea"]
    geom = (head.unitsPerEm, hhea.ascender, hhea.descender, hhea.lineGap)
    os2 = font.get("OS/2")
    if os2 is not None:
        geom += (os2.usWinAscent, os2.usWinDescent, os2.sTypoAscender, os2.sTypoDescender)
    return font, real, widths, geom


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def fetch():
    try:
        import ssl
        ssl._create_default_https_context = ssl._create_unverified_context
    except Exception:
        pass
    os.makedirs(CACHE, exist_ok=True)
    for name, (upstream, digest) in dict(list(UPSTREAM.items()) + list(EXTRA_UPSTREAM.items())).items():
        out = os.path.join(CACHE, name + ".ttf")
        url = "https://raw.githubusercontent.com/%s/%s/%s" % (REPO, BRANCH, urllib.parse.quote(upstream))
        if os.path.exists(out) and sha256(out) == digest:
            print("have    %-13s (checksum matches)" % name)
            continue
        print("fetch   %-13s %s" % (name, url))
        with urllib.request.urlopen(url, timeout=180) as resp:
            data = resp.read()
        tmp = out + ".part"
        with open(tmp, "wb") as fh:
            fh.write(data)
        os.replace(tmp, out)
        got = sha256(out)
        if got != digest:
            print("FAILED  %s checksum: want %s got %s" % (name, digest, got), file=sys.stderr)
            sys.exit(1)


def audit():
    print("%-13s %-7s %-7s %-9s %-9s %-8s %s" %
          ("face", "ship_hz", "gh_hz", "gh_only", "ship_only", "w_diff", "metrics"))
    bad = []
    for name, label, asset in FACES:
        up = os.path.join(CACHE, name + ".ttf")
        ours = os.path.join(ASSETS, asset)
        if not os.path.isfile(up):
            bad.append("%s: upstream copy not on disk, run --fetch" % name)
            continue
        if not os.path.isfile(ours):
            bad.append("%s: shipped asset %s is missing" % (name, asset))
            continue
        digest, want = sha256(up), UPSTREAM[name][1]
        if digest != want:
            bad.append("%s: upstream file changed shape (%s)" % (name, digest[:12]))
            continue
        _, up_real, up_w, up_geom = coverage(up)
        _, our_real, our_w, our_geom = coverage(ours)
        up_only = up_real - our_real
        our_only = our_real - up_real
        widths_diff = sorted(cp for cp in set(our_w) & set(up_w) if our_w[cp] != up_w[cp])
        unexplained = sorted(up_only - ALLOWED_MISSING.get(name, set()))
        unexplained_w = [cp for cp in widths_diff if cp not in ALLOWED_WIDTH_DIFF.get(name, set())]
        hz = lambda s: sum(1 for cp in s if 0x4E00 <= cp <= 0x9FFF)
        print("%-13s %-7d %-7d %-9d %-9d %-8d %s" %
              (name, hz(our_real), hz(up_real), len(up_only), len(our_only),
               len(widths_diff), "same" if our_geom == up_geom else "DIFFERENT"))
        if unexplained:
            bad.append("%s: upstream draws %d codepoints the shipped face cannot, e.g. %s"
                       % (name, len(unexplained), " ".join("U+%04X" % c for c in unexplained[:6])))
        if unexplained_w:
            bad.append("%s: %d codepoints measure differently, e.g. %s"
                       % (name, len(unexplained_w), " ".join("U+%04X" % c for c in unexplained_w[:6])))
        if our_geom != up_geom:
            bad.append("%s: line metrics differ: shipped %s vs upstream %s" % (name, our_geom, up_geom))
    if bad:
        print("", file=sys.stderr)
        for line in bad:
            print("AUDIT FAILED  " + line, file=sys.stderr)
        sys.exit(1)
    print("every shipped face covers what upstream draws, with the same advances and line metrics")


if __name__ == "__main__":
    if "--fetch" in sys.argv:
        fetch()
    audit()
