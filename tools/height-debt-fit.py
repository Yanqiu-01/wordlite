#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/height-debt-fit.py -- is the page height we overspend coming from lines or from paragraphs?

docs section 13 measured the shape: with Word's own measured line heights switched on
(WordLineHeights.APPLIED_TO_LAYOUT) every sampled line matches Word (median error 0.000 px) and yet the
document gains exactly one page box (+868.9 px, 72 paragraphs one page late). So something other than
line heights is being charged, about 8.6 px per page, and page assignment agrees with Word today only
because the two errors run in opposite directions. This tool separates them.

Method: Word's page_stack.tsv gives, per paragraph, the top of its first line on the page
(wdVerticalPositionRelativeToPage is a line top -- proven by the first line of a text page landing on
the declared top margin). Our capture's lines-geo.tsv gives the same thing for our blocks. A paragraph
is matched by its own text (whitespace dropped, first 12 characters), and only kept when it sits whole
on one page on both sides, on the same page number, with the same line count. Then

    delta = our first-line top - Word's first-line top      (page-relative px)

delta is cumulative inside a page: every line before it contributes (our line height minus Word's) and
every paragraph boundary before it contributes (our spacing minus Word's). A two-term least squares with
no intercept over all aligned paragraphs gives one number per line and one per paragraph boundary:

    delta_k = a * (lines before it on the page) + b * (paragraph starts before it on the page)

a > 0 means our lines are taller than Word's, b > 0 means our paragraph spacing is. Both in px.

Usage:
    py tools/height-debt-fit.py -Capture artifacts/agent-layout-verify/lh-apply2
    py tools/height-debt-fit.py -Capture artifacts/agent-layout-verify/hang2 -Show 12
"""
import argparse
import csv
import os
import re
import sys

PT_TO_PX = 4.0 / 3.0


def norm(s):
    return re.sub(r"\s+", "", s or "")


def read_word(path):
    out = []
    with open(path, encoding="utf-8-sig", newline="") as fh:
        for r in csv.DictReader(fh, delimiter="\t"):
            try:
                sp, ep = int(r["start_page"]), int(r["end_page"])
                y = float(r["y_first_pt"]) * PT_TO_PX
                n = int(r["end_line"]) - int(r["start_line"]) + 1
            except ValueError:
                continue
            out.append({"page": sp if sp == ep else None, "y": y, "n": n,
                        "key": norm(r["text40"])[:12], "text": r["text40"][:20]})
    return out


def read_ours(new_dir):
    acc = {}
    with open(os.path.join(new_dir, "lines-geo.tsv"), encoding="utf-8-sig", newline="") as fh:
        for r in csv.DictReader(fh, delimiter="\t"):
            if r["kind"] != "text":
                continue
            b = acc.setdefault(int(r["block"]), {"pages": set(), "first": None, "n": 0, "text": ""})
            b["pages"].add(int(r["page"]))
            t = float(r["top"])
            if b["first"] is None or t < b["first"]:
                b["first"] = t
                b["text"] = r["text"] or ""
            b["n"] += 1
    return [acc[k] for k in sorted(acc)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-Capture", default="artifacts/agent-layout-verify/lh-apply2")
    ap.add_argument("-WordStack", default="artifacts/agent-layout-verify/page-stack-all/page_stack.tsv")
    ap.add_argument("-Show", type=int, default=0, help="print this many aligned rows")
    a = ap.parse_args()
    for f in (os.path.join(a.Capture, "new", "lines-geo.tsv"), a.WordStack):
        if not os.path.exists(f):
            print("missing input: " + f)
            return 1
    wpara = read_word(a.WordStack)
    ours = read_ours(os.path.join(a.Capture, "new"))
    rows = []
    cursor = 0
    for w in wpara:
        if w["page"] is None or len(w["key"]) < 4:
            continue
        hit = None
        for j in range(cursor, min(cursor + 40, len(ours))):
            b = ours[j]
            k = norm(b["text"])
            if k.startswith(w["key"][:8]) or w["key"].startswith(k[:8]):
                hit = j
                break
        if hit is None:
            continue
        b = ours[hit]
        if b["pages"] == {w["page"]} and b["n"] == w["n"]:
            rows.append({"page": w["page"], "word_lines": w["n"], "our_lines": b["n"],
                         "delta": b["first"] - w["y"], "text": w["text"]})
        cursor = hit + 1
    print("capture=%s" % a.Capture)
    print("word paragraphs=%d  our blocks=%d  aligned (one page each, same page, same line count)=%d"
          % (len(wpara), len(ours), len(rows)))
    if len(rows) < 10:
        print("too few aligned paragraphs; check that capture and Word stack describe the same run")
        return 1
    per_page = {}
    for r in rows:
        per_page.setdefault(r["page"], []).append(r)
    pts = []
    for page in sorted(per_page):
        rs = per_page[page]
        lines = 0
        paras = 0
        for r in sorted(rs, key=lambda r: r["delta"]):
            pts.append((lines, paras, r["delta"], r))
            lines += r["word_lines"]
            paras += 1
    sxx = sum(l * l for l, _, _, _ in pts)
    syy = sum(p * p for _, p, _, _ in pts)
    sxy = sum(l * p for l, p, _, _ in pts)
    sxr = sum(l * d for l, _, d, _ in pts)
    syr = sum(p * d for _, p, d, _ in pts)
    det = sxx * syy - sxy * sxy
    if abs(det) < 1e-9:
        print("degenerate fit")
        return 1
    aa = (syy * sxr - sxy * syr) / det
    bb = (sxx * syr - sxy * sxr) / det
    resid = sorted(abs(d - (aa * l + bb * p)) for l, p, d, _ in pts)
    deltas = sorted(r["delta"] for _, _, _, r in pts)
    print("")
    print("fit over %d aligned paragraphs: delta = a*lines + b*paragraph-starts" % len(pts))
    print("  a (per line, px)      %+7.3f" % aa)
    print("  b (per paragraph, px) %+7.3f" % bb)
    print("  residual              med %6.2f  p90 %6.2f  max %6.2f"
          % (resid[len(resid) // 2], resid[int(0.9 * (len(resid) - 1))], resid[-1]))
    print("  delta raw             med %+6.2f px  min %+6.2f  max %+6.2f"
          % (deltas[len(deltas) // 2], deltas[0], deltas[-1]))
    if a.Show:
        print("")
        print("first %d aligned rows (page, Word lines, our lines, delta px, text):" % a.Show)
        for _, _, _, r in pts[:a.Show]:
            print("  p%-3d %2d %2d  %+7.2f  %s" % (r["page"], r["word_lines"], r["our_lines"],
                                                    r["delta"], r["text"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
