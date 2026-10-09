#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/break-replay.py -- lay Word's own paragraphs out again with OUR width model and OUR rules,
and see which rule has to change for the break points to land where Word put them.

Why this exists: width-bill.py measured that on Word's own ragged lines our width model bills the
same width Word did (median delta -0.000 px over 123 lines) and that the w:autoSpaceDE/DN gap is
4.000 px in all four script directions (fit residual median -0.040 px). So the 50 lines where the
phone and Word break differently are not a width problem. They have to come from a break RULE:
  kinsoku   a closing mark may not start a line, an opening mark may not end one
  tailgap   whether the 4 px seam counts in the decision when the break lands on the seam
  round     Android's whole-pixel advance quantisation versus exact (linear) advances
  column    StaticLayout takes an integer width (567) while Word has 566.93 px
This tool replays every paragraph Word laid out, under each combination, and scores how many of
Word's own break points it lands on. The combination that scores best names the rule to change.

Word's break points come out of the PDF Word exported: each line is matched back to its paragraph in
tests/samples/input-liu.docx with a monotonic cursor, so the paragraph text is the canonical string
and each line carries an exact character range. A paragraph is used only when its PDF lines cover it
without gaps (a field code or a dot leader in between would break the coverage).

Usage:
    py tools/break-replay.py
    py tools/break-replay.py -Top 6
"""
import argparse
import collections
import importlib.util
import os
import statistics
import sys

spec = importlib.util.spec_from_file_location("width_bill", os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "width-bill.py"))
wb = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wb)

PT = wb.PT_TO_PX
# Word's kinsoku sets, the marks that may not start / may not end a line.
NO_START = set("。，、；：？！）］〉」』】〗”’…—％℃‰·:;,>?!)}")
NO_END = set("（［〈「『【〖“‘({[")


def no_start(c):
    return c in NO_START or wb.is_cjk_punct(c) and c in "，。、；：？！）］〉」』】”’"


def no_end(c):
    return c in NO_END


# Marks Word lets hang past the right edge (w:overflowPunct): a line that would break one character
# early keeps the mark and pushes it into the margin instead of onto the next line.
HANGABLE = set([chr(0xFF0C), chr(0x3002), chr(0x3001), chr(0xFF1B), chr(0xFF1A), chr(0xFF1F),
                chr(0xFF01), chr(0xFF09), chr(0xFF3D), chr(0x3009), chr(0x300D), chr(0x300F),
                chr(0x201D), chr(0x2019), chr(0x2026), chr(0x2014), chr(0xFF05), chr(0x2103),
                chr(0x2019), chr(0x201D)]) | set(",.;:!?)")


def bill_fit(text, i, j, size_pt, round_adv, tail_gap, hang):
    """Width the line-break decision has to clear: trailing hangable marks cost nothing."""
    k, hung = j, 0
    while k > i + 1 and hung < hang and text[k - 1] in HANGABLE:
        k -= 1
        hung += 1
    return bill(text, i, k, size_pt, round_adv, tail_gap)


def line_records(pdf, docx, size_pt, min_lines):
    """Paragraphs Word laid out, with Word's own break points in canonical-text offsets."""
    paras = wb.docx_paragraphs(docx)
    flat = []
    for pi, text in enumerate(paras):
        s, idx = wb.stripped(text)
        flat.append((text, s, idx))
    edges = collections.Counter()
    firsts = collections.Counter()
    pending = []
    pix, cursor = 0, 0
    for ln in wb.load_sibling().read_lines(pdf):
        chars = [c for c in ln["chars"] if c[0] not in ("\t", "\u3000")]
        ink = [c for c in chars if c[0] != " "]
        if abs(ln["size"] - size_pt) > 0.6 or len(ink) < 4:
            continue
        c, x, sz, pf = ink[-1]
        edges[round(x + wb.advance_px(c, pf, sz) / PT, 0)] += 1
        firsts[round(ink[0][1], 0)] += 1
        raw = "".join(c[0] for c in ink)
        start, hit, found = (pix, cursor), -1, False
        while pix < len(flat):
            text, s, idx = flat[pix]
            hit = s.find(raw, cursor)
            if hit >= 0:
                found = True
                break
            pix += 1
            cursor = 0
        if not found:
            pix, cursor = start
            pending.append({"para": None})
            continue
        text, s, idx = flat[pix]
        begin = idx[hit]
        end = idx[hit + len(raw) - 1] + 1
        pending.append({"para": pix, "begin": begin, "end": end, "x": ink[0][1],
                        "right": x + wb.advance_px(c, pf, sz), "size": sz})
        cursor = hit + len(raw)
        if cursor >= len(s):
            pix += 1
            cursor = 0
    column_pt = max(edges.items(), key=lambda kv: kv[1])[0]
    left_pt = min(firsts.items(), key=lambda kv: -kv[1])[0]
    # Group the matched lines into paragraphs, keeping only runs that cover their paragraph.
    out = []
    run = []
    for rec in pending + [{"para": None}]:
        same = run and rec.get("para") == run[-1]["para"]
        if not same:
            if len(run) >= min_lines:
                out.append(run)
            run = []
        if rec.get("para") is not None:
            run.append(rec)
    paras_out = []
    for run in out:
        pi = run[0]["para"]
        text = paras[pi]
        if run[0]["begin"] != 0 or run[-1]["end"] != len(text.rstrip()):
            continue
        if any(run[i + 1]["begin"] != run[i]["end"] for i in range(len(run) - 1)):
            continue
        paras_out.append({"text": text, "cuts": [r["end"] for r in run[:-1]],
                          "indent_px": (run[0]["x"] - left_pt) * PT,
                          "page": run[0].get("page")})
    return paras_out, (column_pt - left_pt) * PT, left_pt * PT


def bill(text, i, j, size_pt, round_adv, tail_gap):
    """Width of text[i:j] in our document px, with our seam rule."""
    total = 0.0
    for k in range(i, j):
        c = text[k]
        if k >= j - 1 and c == " ":
            continue
        adv = wb.advance_px(c, "SimSun" if wb.is_cjk(c) else "times", size_pt)
        seams = 0
        if k > i and wb.seam(text[k - 1], c):
            seams += 1
        if k + 1 < j and wb.seam(c, text[k + 1]):
            seams += 1
        if k + 1 == j and tail_gap and k + 1 < len(text) and wb.seam(c, text[k + 1]):
            seams += 1
        em = size_pt * PT
        unit = (adv + seams * em * wb.GAP_EM) if seams else adv
        total += round(unit) if round_adv else unit
    return total


def breakable(text, j, kinsoku):
    """May a line END at j (so text[j] starts the next line)?"""
    if j >= len(text):
        return True
    prev, nxt = text[j - 1], text[j]
    if nxt in NO_START and kinsoku:
        return False
    if prev in NO_END and kinsoku:
        return False
    if prev == " " or nxt == " ":
        return True
    if wb.is_cjk(prev) and not wb.is_cjk(nxt):
        return True
    if wb.is_cjk(prev) and wb.is_cjk(nxt):
        return True
    if not wb.is_cjk(prev) and wb.kind(prev) == 0 and wb.kind(nxt) != 0:
        return True
    if wb.is_cjk(nxt) and not wb.is_cjk(prev):
        return True
    return False    # inside a Latin or digit run, or after a slash: Word does not cut there


def replay(par, column_px, size_pt, round_adv, tail_gap, kinsoku, int_column, hang=0):
    """Greedy line filling under one rule set; returns Word-style cut offsets."""
    # The paragraph indent is a first-line-only affair; the lines after it get the whole column.
    text = par["text"]
    end_all = len(text.rstrip())
    base = float(int(round(column_px)) if int_column else column_px)
    ind = float(int(round(par["indent_px"])) if int_column else par["indent_px"])
    cuts, i, first = [], 0, True
    while i < end_all:
        width = base - ind if first else base
        best, last_ok = -1, -1
        for j in range(i + 1, len(text) + 1):
            if bill_fit(text, i, j, size_pt, round_adv, tail_gap, hang) > width:
                break
            if breakable(text, j, kinsoku):
                last_ok = j
            best = j
            if j >= end_all:
                break
        cut = best if best == end_all else last_ok
        if cut <= i:
            cut = i + 1
        cuts.append(cut)
        i = cut
        first = False
    return [c for c in cuts if c < end_all]


def score(paras, **kw):
    hits = total = 0
    miss = []
    for par in paras:
        mine = replay(par, **kw)
        want = par["cuts"]
        hits += len(set(mine) & set(want))
        total += len(want)
        first = next((k for k in range(max(len(mine), len(want)))
                      if (mine[k] if k < len(mine) else None) != (want[k] if k < len(want) else None)),
                     None)
        if first is not None:
            miss.append((par, mine, want, first))
    return (100.0 * hits / total if total else 0.0), hits, total, miss


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-Pdf", default="artifacts/agent-typeset/pdf-truth/input-liu.pdf")
    ap.add_argument("-Docx", default="tests/samples/input-liu.docx")
    ap.add_argument("-SizePt", type=float, default=12.0)
    ap.add_argument("-MinLines", type=int, default=2)
    ap.add_argument("-Top", type=int, default=5)
    a = ap.parse_args()
    paras, column_px, left_px = line_records(a.Pdf, a.Docx, a.SizePt, a.MinLines)
    print("paragraphs replayed=" + str(len(paras)) + "  Word break points="
          + str(sum(len(p["cuts"]) for p in paras)))
    print("column=" + ("%.2f" % column_px) + " px  left edge=" + ("%.2f" % left_px)
          + " px  (both read off the PDF)")
    print("")
    print("%-46s %8s %s" % ("rule set", "agree", "of Word's break points"))
    sets = [("ours today: round, seam counted at the break, no kinsoku, int column",
             dict(round_adv=True, tail_gap=True, kinsoku=False, int_column=True)),
            ("+ kinsoku (no closing mark may start a line)",
             dict(round_adv=True, tail_gap=True, kinsoku=True, int_column=True)),
            ("+ seam NOT counted when the break lands on it",
             dict(round_adv=True, tail_gap=False, kinsoku=False, int_column=True)),
            ("+ kinsoku + seam not counted at the break",
             dict(round_adv=True, tail_gap=False, kinsoku=True, int_column=True)),
            ("+ exact (linear) advances",
             dict(round_adv=False, tail_gap=True, kinsoku=False, int_column=True)),
            ("+ exact advances + kinsoku + seam free at the break",
             dict(round_adv=False, tail_gap=False, kinsoku=True, int_column=True)),
            ("+ float column 566.93 instead of 567",
             dict(round_adv=True, tail_gap=True, kinsoku=False, int_column=False)),
            ("+ one hanging mark at the line end (overflowPunct)",
             dict(round_adv=True, tail_gap=True, kinsoku=False, int_column=True, hang=1)),
            ("+ hanging mark + kinsoku",
             dict(round_adv=True, tail_gap=True, kinsoku=True, int_column=True, hang=1)),
            ("+ hanging mark + kinsoku + seam free at the break",
             dict(round_adv=True, tail_gap=False, kinsoku=True, int_column=True, hang=1)),
            ("+ hanging mark + kinsoku + exact advances",
             dict(round_adv=False, tail_gap=True, kinsoku=True, int_column=True, hang=1)),
            ("+ up to two hanging marks + kinsoku",
             dict(round_adv=True, tail_gap=True, kinsoku=True, int_column=True, hang=2))]
    best = None
    for name, kw in sets:
        pct, hits, total, miss = score(paras, column_px=column_px, size_pt=a.SizePt, **kw)
        print("%-46s %7.1f%% %d/%d" % (name, pct, hits, total))
        if best is None or pct > best[0]:
            best = (pct, name, kw, miss)
    print("")
    pct, name, kw, miss = best
    print("best rule set: " + name + " (" + ("%.1f" % pct) + "%)")
    print("paragraphs where even the best set disagrees with Word, first disagreement only:")
    for par, mine, want, first in miss[:a.Top]:
        at = want[first] if first < len(want) else len(par["text"])
        prev = want[first - 1] if first else 0
        ours = ("(no such line)" if first >= len(mine) else par["text"][prev:mine[first]][-6:])
        print("  cut %d of %d: Word ends with %r, we end with %r  |  %r" %
              (first, len(want), par["text"][prev:at][-6:], ours, par["text"][:12]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
