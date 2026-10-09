#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/width-bill.py -- bill Word's own lines under our width model and see who loses a character.

Why this exists: we lay out 17 more lines than Word over the thesis (749 -> 766), which means our
lines run out of room earlier. autogap-truth.py already measured that Word's w:autoSpaceDE/DN gap is
4.000 px (0.2500 em) and that a Chinese character is exactly 16.000 px, so the gap SIZE is right.
What is left is where the money is charged. The candidates all live in DocxTextLayout:
  round  - Android charges every glyph advance as a whole pixel (measured, docs 23.1-23.2)
  tail   - the AutoGap rides the last character of the Chinese run, so when the line BREAKS at that
           seam the 4 px are still charged to the line even though the partner is on the next line

Every variant is billed over the strings Word itself fitted on a ragged (unjustified) line, so the
only difference between the models is the model:
  word       Word's own bill = origin span of the line + the last character's advance
  v_round    what we charge today: round every advance, seam charged whether or not it is the break
  v_tail     v_round but a seam at the END of the line is not charged
  v_lin      linear metrics (exact advances), seam charged either way
  v_lintail  linear metrics AND no charge for a seam at the end of the line
A variant "loses" a line when Word's own bill fits the column but the variant's bill does not, i.e.
when overbill = variant_bill - word_bill is bigger than the slack Word still had on that line.

The character that follows a line is read out of tests/samples/input-liu.docx, not out of the PDF:
AutoGap spans are built per paragraph, so a line that ends a paragraph holds no seam either way, and
a space between the two scripts kills the seam in the engine as well. Lines are matched to their
paragraph by walking the PDF in document order with a cursor.

Units: our document px = pt * 4/3 (PageGeometry.points). The text column's right edge is read off
the PDF (the mode of line right edges: justified lines land exactly on it).

Usage:
    py tools/width-bill.py
    py tools/width-bill.py -Pdf artifacts/agent-typeset/pdf-truth/input-liu.pdf -SizePt 12.0 -Top 8
"""
import argparse
import collections
import importlib.util
import math
import os
import re
import statistics
import sys
import zipfile

from fontTools.ttLib import TTFont

PT_TO_PX = 4.0 / 3.0
GAP_EM = 0.2500           # measured in autogap-truth.py: Word charges 4.000 px at 12 pt
FONTS = "app/src/main/assets/fonts/"
FILE_OF = {"song": "song.ttc", "simhei": "simhei.ttf", "msgothic": "msgothic.ttf",
           "stxinwei": "stxinwei.ttf", "kaiti": "kaiti.ttf", "times": "times-new-roman.ttf",
           "times-bold": "times-new-roman-bold.ttf", "times-italic": "times-new-roman-italic.ttf"}
FACE_OF = {"SimSun": "song", "SimHei": "simhei", "MS-Mincho": "msgothic", "STXihei": "stxinwei",
           "KaiTi_GB2312": "kaiti", "TimesNewRomanPSMT": "times",
           "TimesNewRomanPS-BoldMT": "times-bold", "TimesNewRomanPS-ItalicMT": "times-italic"}
CJK = [(0x4E00, 0x9FFF), (0x3400, 0x4DBF), (0xF900, 0xFAFF), (0x3000, 0x303F),
       (0xAC00, 0xD7A3), (0x3040, 0x309F), (0x30A0, 0x30FF), (0xFF00, 0xFFEF)]
_cache = {}


def load_sibling():
    """reuse read_lines() from tools/autogap-truth.py so both tools see the same line split."""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "autogap-truth.py")
    spec = importlib.util.spec_from_file_location("autogap_truth", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def hmtx_em(face_key, c):
    if face_key not in _cache:
        f = TTFont(FONTS + FILE_OF[face_key], lazy=True)
        _cache[face_key] = (f.getBestCmap(), f["hmtx"], f["head"].unitsPerEm)
    cm, hmtx, units = _cache[face_key]
    g = cm.get(ord(c))
    return None if g is None else hmtx[g][0] / float(units)


def is_cjk(c):
    o = ord(c)
    return any(a <= o <= b for a, b in CJK)


def is_cjk_punct(c):
    o = ord(c)
    if 0x3001 <= o <= 0x303F or o in (0x2018, 0x2019, 0x201C, 0x2026, 0x2013, 0x2014):
        return True
    if 0xFF01 <= o <= 0xFF65:
        return not ((0xFF21 <= o <= 0xFF3A) or (0xFF41 <= o <= 0xFF5A))
    return False


def kind(c):
    """DocxTextLayout.scriptKind(): 1 CJK, 2 Latin letters, 3 ASCII digits, 0 anything else."""
    if is_cjk(c):
        return 1
    if ("A" <= c <= "Z") or ("a" <= c <= "z"):
        return 2
    if "0" <= c <= "9":
        return 3
    return 0


def seam(left, right):
    """Does DocxTextLayout.applyAutoSpace open a gap between these two neighbours?"""
    a, b = kind(left), kind(right)
    if a == 0 or b == 0 or a == b:
        return False
    if is_cjk_punct(left) or is_cjk_punct(right):
        return False
    return {1, 2} == {a, b} or {1, 3} == {a, b}


def face_of(c, pdf_face):
    return FACE_OF.get(pdf_face) or ("song" if is_cjk(c) else "times")


def advance_px(c, pdf_face, size_pt):
    """Advance of one character in our document px, out of the same file Word embedded."""
    em = hmtx_em(face_of(c, pdf_face), c)
    if em is None:
        em = 1.0 if is_cjk(c) else 0.5
    return em * size_pt * PT_TO_PX


def docx_paragraphs(docx):
    """Text of every w:p in document order, as the paginator sees it (tabs and breaks -> no width)."""
    with zipfile.ZipFile(docx) as z:
        xml = z.read("word/document.xml").decode("utf-8")
    paras = []
    for p in re.findall(r"<w:p[ >].*?</w:p>", xml, re.S):
        body = re.sub(r"<w:instrText[^>]*>.*?</w:instrText>", "", p, flags=re.S)
        text = "".join(re.findall(r"<w:t(?: [^>]*)?>(.*?)</w:t>", body, re.S))
        text = (text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&quot;", '"').replace("&apos;", "'"))
        paras.append(text)
    return paras


def stripped(text):
    out, idx = [], []
    for i, c in enumerate(text):
        if c not in (" ", "\t", "\u3000", "\xa0"):
            out.append(c)
            idx.append(i)
    return "".join(out), idx


def attach_paragraphs(ragged, paras):
    """Set ln["tail"] = 1 when the paragraph character after the line opens a seam on its last one.

    The walk is monotonic: a line can only land in the same or a later paragraph than the line
    before it, so a repeated short line cannot snap back to an earlier copy of itself. A line that
    no paragraph contains (a table-of-contents leader, a Wingdings bullet) is left unmatched with
    para = -1 and the cursor is restored, so one unmatchable line does not eat the whole walk.
    """
    flat = []
    for pi, text in enumerate(paras):
        s, idx = stripped(text)
        flat.append((pi, text, s, idx))
    para_ix, cursor = 0, 0
    matched = 0
    for ln in ragged:
        want = ln["raw"]
        start, ok = (para_ix, cursor), False
        while para_ix < len(flat):
            pi, text, s, idx = flat[para_ix]
            hit = s.find(want, cursor)
            if hit >= 0:
                end = idx[hit + len(want) - 1] + 1
                ln["para"] = pi
                ln["tail"] = 1 if (end < len(text) and seam(text[end - 1], text[end])) else 0
                cursor = hit + len(want)
                if cursor >= len(s):
                    para_ix += 1
                    cursor = 0
                ok = True
                break
            para_ix += 1
            cursor = 0
        if not ok:
            para_ix, cursor = start
            ln["para"], ln["tail"] = -1, 0
        else:
            matched += 1
    return matched

def bill(chars, round_adv, charge_tail, tail_seams):
    """Total width of one line under one billing model, in our document px."""
    total = 0.0
    n = len(chars)
    # BlankTail: the blanks a wrap leaves past the last character with ink are measured as nothing,
    # and Word's own line width stops at that character too, so both sides ignore them.
    last_ink = max([i for i, ch in enumerate(chars) if ch[0] != " "] or [-1])
    for i, (c, _x, sz, pf) in enumerate(chars):
        if i > last_ink:
            continue
        adv = advance_px(c, pf, sz)
        seams = 0
        em_px = sz * PT_TO_PX
        if i > 0 and seam(chars[i - 1][0], c):
            seams += 1
        if i + 1 < n and seam(c, chars[i + 1][0]):
            seams += 1
        if c == " ":                       # a blank is never the character an AutoGap rides on
            total += math.floor(adv + 0.5) if round_adv else adv
            continue
        if i + 1 == n and charge_tail:
            seams += tail_seams
        unit = math.floor(adv + 0.5) if round_adv else adv
        total += math.ceil(unit + seams * em_px * GAP_EM) if seams else unit
    return total


SEAM_CLASSES = {(1, 2): "cjk>latin", (2, 1): "latin>cjk",
                (1, 3): "cjk>digit", (3, 1): "digit>cjk"}


def seam_classes(text):
    """Counts of each seam class the engine would open across this string."""
    out = collections.Counter()
    for i in range(len(text) - 1):
        a, b = kind(text[i]), kind(text[i + 1])
        if seam(text[i], text[i + 1]):
            out[SEAM_CLASSES[(a, b)]] += 1
    return out


def fit_excess(rows_by_class, targets):
    """Least squares: overbill = sum over seam classes of (count x excess). No intercept: a line
    with no seam must bill exactly what Word billed, or the model is wrong somewhere else."""
    keys = sorted({k for r in rows_by_class for k in r})
    m = len(keys)
    ata = [[0.0] * m for _ in range(m)]
    atb = [0.0] * m
    for row, y in zip(rows_by_class, targets):
        v = [float(row.get(k, 0)) for k in keys]
        for i in range(m):
            atb[i] += v[i] * y
            for j in range(m):
                ata[i][j] += v[i] * v[j]
    for i in range(m):                      # Gaussian elimination with partial pivoting
        piv = max(range(i, m), key=lambda r: abs(ata[r][i]))
        if abs(ata[piv][i]) < 1e-9:
            return None
        ata[i], ata[piv] = ata[piv], ata[i]
        atb[i], atb[piv] = atb[piv], atb[i]
        for r in range(i + 1, m):
            f = ata[r][i] / ata[i][i]
            for c in range(i, m):
                ata[r][c] -= f * ata[i][c]
            atb[r] -= f * atb[i]
    sol = [0.0] * m
    for i in reversed(range(m)):
        sol[i] = (atb[i] - sum(ata[i][j] * sol[j] for j in range(i + 1, m))) / ata[i][i]
    resid = [y - sum(float(row.get(k, 0)) * sol[ix] for ix, k in enumerate(keys))
             for row, y in zip(rows_by_class, targets)]
    return keys, sol, resid


def quantile(vals, q):
    s = sorted(vals)
    if not s:
        return float("nan")
    return s[min(len(s) - 1, int(math.ceil((len(s) - 1) * q)))]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-Pdf", default="artifacts/agent-typeset/pdf-truth/input-liu.pdf")
    ap.add_argument("-Docx", default="tests/samples/input-liu.docx")
    ap.add_argument("-SizePt", type=float, default=12.0)
    ap.add_argument("-MinChars", type=int, default=8)
    ap.add_argument("-Top", type=int, default=8)
    a = ap.parse_args()
    if not (os.path.exists(a.Pdf) and os.path.exists(a.Docx)):
        print("missing input: " + a.Pdf + " / " + a.Docx)
        return 1
    sib = load_sibling()
    body = []
    for ln in sib.read_lines(a.Pdf):
        chars = [c for c in ln["chars"] if c[0] not in ("\t", "\u3000")]
        ink = [c for c in chars if c[0] != " "]
        if abs(ln["size"] - a.SizePt) > 0.6 or len(ink) < a.MinChars:
            continue
        body.append({"page": ln["page"], "size": ln["size"], "chars": chars, "ink": ink,
                     "raw": "".join(c[0] for c in ink)})
    if not body:
        print("no body lines read from " + a.Pdf)
        return 1
    edges = collections.Counter()
    for ln in body:
        c, x, sz, pf = ln["ink"][-1]
        edges[round(x + advance_px(c, pf, sz) / PT_TO_PX, 0)] += 1
    column_pt = max(edges.items(), key=lambda kv: kv[1])[0]
    ragged = []
    for ln in body:
        c, x, sz, pf = ln["ink"][-1]
        ln["right_pt"] = x + advance_px(c, pf, sz) / PT_TO_PX
        if ln["right_pt"] < column_pt - 2.0:
            ragged.append(ln)
    matched = attach_paragraphs(ragged, docx_paragraphs(a.Docx))
    ragged = [ln for ln in ragged if ln["para"] >= 0]
    if not ragged:
        print("no ragged line could be matched back to a paragraph in " + a.Docx)
        return 1

    variants = [("round", True, True), ("round+tailfree", True, False),
                ("linear", False, True), ("linear+tailfree", False, False)]
    names = ["word"] + [v[0] for v in variants]
    rows = collections.OrderedDict((n, []) for n in names)
    loses = collections.Counter()
    latin = collections.OrderedDict((n, []) for n in names)
    latin_loses = collections.Counter()
    tail_lines = 0
    worst = []
    for ln in ragged:
        first_x = ln["ink"][0][1]
        last_c, last_x, last_sz, last_pf = ln["ink"][-1]
        word_px = (last_x - first_x) * PT_TO_PX + advance_px(last_c, last_pf, last_sz)
        bills = {"word": word_px}
        for nm, rnd, tail in variants:
            bills[nm] = bill(ln["chars"], rnd, tail, ln["tail"])
        is_latin = any(("A" <= c <= "z") or ("0" <= c <= "9") for c in ln["raw"])
        slack_px = (column_pt - ln["right_pt"]) * PT_TO_PX
        if ln["tail"]:
            tail_lines += 1
        for nm in names:
            over = bills[nm] - word_px
            rows[nm].append(over)
            if is_latin:
                latin[nm].append(over)
            if nm != "word" and over > slack_px:
                loses[nm] += 1
                if is_latin:
                    latin_loses[nm] += 1
        worst.append((bills["round"] - word_px - slack_px, ln["page"], ln["raw"],
                      slack_px, bills["round"] - word_px, ln["tail"]))

    print("pdf=" + a.Pdf)
    print("body 12pt lines=" + str(len(body)) + "  ragged=" + str(len(ragged))
          + "  matched to a docx paragraph=" + str(matched)
          + "  lines ending on a seam we still charge=" + str(tail_lines))
    print("text column right edge off the PDF = " + ("%.2f" % column_pt) + " pt")
    print("")
    print("%-16s %8s %8s %8s %8s %9s" %
          ("model", "med dpx", "avg dpx", "p90 |d|", "loses", "lose rate"))
    for nm in names:
        v = rows[nm]
        absd = [abs(x) for x in v]
        lr = 100.0 * loses[nm] / len(ragged) if ragged else float("nan")
        print("%-16s %8.3f %8.3f %8.3f %8d %8.1f%%" %
              (nm or "word", med(v), statistics.fmean(v) if v else float("nan"),
               quantile(absd, 0.9), loses[nm], lr))
    print("")
    print("same bill over the ragged lines that hold Latin letters or digits:")
    for nm in names:
        v = latin[nm]
        print("  %-16s n=%-4d med %7.3f px  avg %7.3f px  loses %d" %
              (nm or "word", len(v), med(v),
               statistics.fmean(v) if v else float("nan"), latin_loses[nm]))
    print("")
    counts = [seam_classes(ln["raw"]) for ln in ragged]
    targets = [bill(ln["chars"], True, False, ln["tail"]) -
               ((ln["ink"][-1][1] - ln["ink"][0][1]) * PT_TO_PX +
                advance_px(ln["ink"][-1][0], ln["ink"][-1][3], ln["ink"][-1][2]))
               for ln in ragged]
    print("")
    print("which seam class carries the overbill? least squares over " + str(len(ragged))
          + " lines, intercept forced to 0 (a line with no seam must bill exactly what Word did):")
    tot = collections.Counter()
    for c in counts:
        tot.update(c)
    got = fit_excess(counts, targets)
    if got is None:
        print("  seam classes are not separable on this sample")
    else:
        keys, sol, resid = got
        for k, v in zip(keys, sol):
            print("  %-12s opened %3d times, we charge 4.000 px each, Word charges %6.3f px"
                  % (k, tot[k], v))
        print("  residual after the fit: med %6.3f px  p90|r| %6.3f px  max|r| %6.3f px"
              % (med(resid), quantile([abs(r) for r in resid], 0.9), max(abs(r) for r in resid)))
        print("  lines the fit leaves furthest off:")
        for r, raw in sorted(zip(resid, [ln["raw"] for ln in ragged]), reverse=True)[:a.Top]:
            print("    %+7.3f px  %s" % (r, raw[:30]))
    print("worst " + str(a.Top) + " ragged lines under v_round (overbill minus Word's leftover slack,"
          " tail=1 means the line ends on a seam):")
    for delta, page, raw, slack, over, tail in sorted(worst, reverse=True)[:a.Top]:
        print("  page %2d tail=%d slack %6.2f px  overbill %6.2f px  short by %6.2f px  %s" %
              (page, tail, slack, over, delta, raw[:26]))
    return 0


def med(vals):
    return statistics.median(vals) if vals else float("nan")


if __name__ == "__main__":
    sys.exit(main())
