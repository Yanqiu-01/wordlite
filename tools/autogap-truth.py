#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/autogap-truth.py -- how much room does Word really leave at a Chinese/Latin seam?

Why this exists: the engine bills the w:autoSpaceDE / w:autoSpaceDN gap as
    ceil(advance + textSize / 4)            (DocxTextLayout.AutoGap)
so two numbers in that one line are guesses: the 1/4 em and the round-UP. Both can only make our
lines WIDER than Word's, and 32 measured break decisions already read "Word fitted one more
character on this line". Before changing either number, measure Word's own.

Method (no Word session: this uses the same PDF Word exported that font-advance-audit.py uses):
  1. pymupdf rawdict -> per line: (char, x origin, size, face), spans stitched back into one line.
  2. natural advance of a (char, face) = MODE of the origin steps over the whole document. Justified
     lines stretch every gap, so the mode is the natural value (same reduction font-advance-audit.py
     uses); the tail of the distribution is justification.
  3. the seam is measured on RAGGED lines only: on a justified line the stretch would be read as gap.
     Ragged here means ink width < - RaggedPt - of the text column.
  4. seam excess = step - natural advance of the LEFT character. cjk|cjk is the control row: Word
     leaves no autoSpace between two Chinese characters, so it should read about zero, and any bias
     in step 2 shows up there and cancels when the other rows are compared with it.

Units: PDF points; our document px = pt * 4/3 (PageGeometry.points). em = pt / font size.

Usage:
    py tools/autogap-truth.py
    py tools/autogap-truth.py -Pdf artifacts/agent-typeset/pdf-truth/input-liu.pdf -RaggedPt 380
"""
import argparse
import collections
import statistics
import sys

import pymupdf

PT_TO_PX = 4.0 / 3.0
CJK_PUNCT = set([0x2018, 0x2019, 0x201C, 0x201D, 0x2026, 0x2013, 0x2014, 0x00B7])


def kind(c):
    """Coarse script class, same split the engine uses for autoSpaceDE/DN."""
    if c in (' ', '\t', '\u3000'):
        return 'sp'
    o = ord(c)
    if o in CJK_PUNCT or 0x3000 <= o <= 0x303F or 0xFF01 <= o <= 0xFF65:
        return 'cp'                      # ，。、！？（）…
    if 0x3040 <= o <= 0x33FF or 0x3400 <= o <= 0x4DBF or 0x4E00 <= o <= 0x9FFF \
            or 0xF900 <= o <= 0xFAFF or 0x20000 <= o:
        return 'cjk'
    if c.isdigit():
        return 'd'
    if c.isalpha() and o < 0x250:
        return 'lat'
    return 'o'

def read_lines(pdf):
    """[{page, size, chars:[(char, x_origin_pt, face)]}] -- spans of one PDF line stitched together."""
    out = []
    doc = pymupdf.open(pdf)
    for pno, page in enumerate(doc, 1):
        for b in page.get_text("rawdict")["blocks"]:
            for ln in b.get("lines", []):
                chars = []
                for s in ln.get("spans", []):
                    size = s["size"]
                    face = s["font"].split("+")[-1]
                    for ch in s["chars"]:
                        if ch["c"] in ("\n", "\r"):
                            continue
                        chars.append((ch["c"], ch["origin"][0], size, face))
                if len(chars) < 2:
                    continue
                sizes = collections.Counter(round(c[2], 2) for c in chars)
                out.append({"page": pno, "size": sizes.most_common(1)[0][0], "chars": chars})
    return out


def steps(lines):
    """Origin steps between neighbours, skipping any pair that touches a space."""
    for ln in lines:
        cs = ln["chars"]
        for i in range(len(cs) - 1):
            c, x, _, face = cs[i]
            n, nx, _, _ = cs[i + 1]
            if kind(c) == 'sp' or kind(n) == 'sp' or nx <= x:
                continue
            yield (c, face, ln["size"], nx - x, ln, i)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-Pdf", default="artifacts/agent-typeset/pdf-truth/input-liu.pdf")
    ap.add_argument("-ColumnPt", type=float, default=425.2, help="text column width in pt")
    ap.add_argument("-RaggedPt", type=float, default=380.0,
                    help="a line this narrow cannot be justified, so its gaps are unstretched")
    ap.add_argument("-SizePt", type=float, default=12.0, help="body size to report em figures for")
    a = ap.parse_args()

    lines = read_lines(a.Pdf)
    all_steps = list(steps(lines))
    print("pdf=%s  lines=%d  steps=%d" % (a.Pdf, len(lines), len(all_steps)))

    natural = {}
    seen = collections.defaultdict(collections.Counter)
    for c, face, size, step, _, _ in all_steps:
        seen[(c, face, round(size, 2))][round(step, 3)] += 1
    for k, counter in seen.items():
        natural[k] = counter.most_common(1)[0][0]

    def width_pt(ln):
        return ln["chars"][-1][1] - ln["chars"][0][1]

    ragged = [ln for ln in lines if width_pt(ln) < a.RaggedPt and abs(ln["size"] - a.SizePt) < 0.6]
    print("ragged body lines used for the seam: %d of %d (ink < %.1f pt at %.1f pt)"
          % (len(ragged), len(lines), a.RaggedPt, a.SizePt))

    # control: what the natural-advance estimate leaves over when there is no seam at all
    rows = collections.defaultdict(list)
    for ln in ragged:
        cs = ln["chars"]
        for i in range(len(cs) - 1):
            c, x, _, face = cs[i]
            n, nx, _, _ = cs[i + 1]
            kc, kn = kind(c), kind(n)
            if kc == 'sp' or kn == 'sp' or nx <= x:
                continue
            adv = natural.get((c, face, round(ln["size"], 2)))
            if adv is None:
                continue
            rows["%s|%s" % (kc, kn)].append((nx - x) - adv)

    print("")
    print("seam excess = origin step - natural advance of the left character, on ragged lines")
    print("cjk|cjk is the control (Word leaves nothing there); em is at %.1f pt" % a.SizePt)
    print("%-10s %7s %9s %9s %9s %9s %9s" %
          ("seam", "n", "med px", "mean px", "p10 px", "p90 px", "med em"))
    def key(kv):
        return -len(kv[1])
    for name, vals in sorted(rows.items(), key=key):
        if len(vals) < 5:
            continue
        v = sorted(vals)
        med = statistics.median(v)
        q = lambda f: v[min(len(v) - 1, int((len(v) - 1) * f))]
        print("%-10s %7d %9.3f %9.3f %9.3f %9.3f %9.4f" %
              (name, len(v), med * PT_TO_PX, statistics.mean(v) * PT_TO_PX,
               q(0.10) * PT_TO_PX, q(0.90) * PT_TO_PX, med / a.SizePt))

    print("")
    print("natural CJK advance read the same way (should be 1.000 em = 16.000 px at 12 pt):")
    for probe in ("\u4e2d", "\u6587", "\uff0c", "\u3002"):
        for (c, face, size), adv in sorted(natural.items()):
            if c == probe and abs(size - a.SizePt) < 0.6:
                n = sum(seen[(c, face, size)].values())
                print("  U+%04X %-22s %.3f pt = %.3f px = %.4f em  (n=%d)"
                      % (ord(c), face, adv, adv * PT_TO_PX, adv / size, n))


if __name__ == "__main__":
    sys.exit(main())
