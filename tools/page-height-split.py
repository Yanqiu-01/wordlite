#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/page-height-split.py -- split one page's height into line heights and everything else.

Why this exists: billing every line at Word's own line height (WordLineHeights.APPLIED_TO_LAYOUT=true,
docs section 13) matches Word line by line and still costs one extra page. If the line heights are right
and the pages still overflow, part of the page is spent on something that is not a line height. This tool
separates the parts so that amount becomes a number instead of an argument.

Both sides are read as LINE TOPS. Word's y comes from wdVerticalPositionRelativeToPage, which is a line
top: the first line of a text page lands on the declared top margin (page 2: 110.05 pt against a
107.7 pt top margin), not a baseline below it. Our side takes the `top` column of the capture's
lines-geo.tsv, which is StaticLayout's line top.

    top    = our first line top - Word's first line top      (what sits above the first line)
    span   = last line top - first line top                  (the line heights added up)
    slack  = text-area bottom - bottom of the last line      (what is left under the last line)

Units are our document px (pt * 4/3, PageGeometry.points). The text-area bottom comes from the capture's
page-geo.tsv: the section's declared page height minus its declared bottom margin, so both sides are
measured against the same declared box (sections 2/3: 1122.53 - 143.60 - 113.40 = 865.53 px).

Page numbers only line up while both engines paginate the same way, so the table is a starting point:
read the medians, and use tools/height-debt-fit.py for the content-aligned version of the same question.

Usage:
    py tools/page-height-split.py -Capture artifacts/agent-layout-verify/lh-apply2
    py tools/page-height-split.py -Capture artifacts/agent-layout-verify/hang2 -PerPage 20
    py tools/page-height-split.py -Capture <dir> -Pages 12-28 -WordPageLines <page_lines.tsv>
"""
import argparse
import csv
import os
import statistics
import sys

PT_TO_PX = 4.0 / 3.0


def quantile(vals, q):
    s = sorted(vals)
    return 0.0 if not s else s[min(len(s) - 1, int(q * (len(s) - 1)))]


def read_word(path):
    """page -> (line count, first line top px, last line top px) from Word's COM dump."""
    out = {}
    with open(path, encoding="utf-8-sig", newline="") as fh:
        for r in csv.DictReader(fh, delimiter="\t"):
            if not str(r["page"]).strip().isdigit():
                continue
            out[int(r["page"])] = (int(r["max_line_no"]),
                                   float(r["first_line_y_pt"]) * PT_TO_PX,
                                   float(r["last_line_y_pt"]) * PT_TO_PX)
    return out


def read_ours(new_dir):
    """page -> line count, first/last line top, advance of the last line; plus the page box."""
    lines, geo = {}, {}
    with open(os.path.join(new_dir, "lines-geo.tsv"), encoding="utf-8-sig", newline="") as fh:
        for r in csv.DictReader(fh, delimiter="\t"):
            if r["kind"] != "text":
                continue
            page = int(r["page"])
            d = lines.setdefault(page, {"n": 0, "first": None, "last": None, "adv": 0.0})
            t = float(r["top"])
            d["n"] += 1
            if d["first"] is None or t < d["first"]:
                d["first"] = t
            if d["last"] is None or t >= d["last"]:
                d["last"], d["adv"] = t, float(r["advance"])
    with open(os.path.join(new_dir, "page-geo.tsv"), encoding="utf-8-sig", newline="") as fh:
        for r in csv.DictReader(fh, delimiter="\t"):
            geo[int(r["page"])] = r
    return lines, geo


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-Capture", default="artifacts/agent-layout-verify/hang2")
    ap.add_argument("-WordPageLines",
                    default="artifacts/agent-layout-verify/page-stack-all/page_lines.tsv")
    ap.add_argument("-Pages", default="", help="page range to print, e.g. 12-28 (default: all)")
    ap.add_argument("-PerPage", type=int, default=0,
                    help="only print pages with at least this many lines on either side")
    a = ap.parse_args()
    new_dir = os.path.join(a.Capture, "new")
    for f in (os.path.join(new_dir, "lines-geo.tsv"), os.path.join(new_dir, "page-geo.tsv"),
              a.WordPageLines):
        if not os.path.exists(f):
            print("missing input: " + f)
            return 1
    word = read_word(a.WordPageLines)
    ours, geo = read_ours(new_dir)
    lo, hi = (1, 10 ** 6)
    if a.Pages:
        part = a.Pages.split("-")
        lo = int(part[0])
        hi = int(part[1]) if len(part) > 1 else lo

    rows = []
    for page in sorted(set(word) & set(ours)):
        if page < lo or page > hi:
            continue
        wn, wf, wl = word[page]
        o = ours[page]
        if a.PerPage and max(wn, o["n"]) < a.PerPage:
            continue
        g = geo[page]
        bottom = float(g["marginTop"]) + float(g["contentH"])   # text-area bottom, page-relative
        pitch = (wl - wf) / (wn - 1) if wn > 1 else 0.0
        rows.append({"page": page, "wn": wn, "on": o["n"], "wf": wf, "of": o["first"],
                     "top": o["first"] - wf, "ws": wl - wf, "os": o["last"] - o["first"],
                     "word_used": wl + pitch - float(g["marginTop"]), "our_used": o["last"] + o["adv"] - float(g["marginTop"]),
                     "bottom_word": bottom - (wl + pitch), "bottom_ours": bottom - (o["last"] + o["adv"]),
                     "contentH": float(g["contentH"]), "used": float(g["used"])})

    print("capture=%s" % a.Capture)
    print("word pages=%s  our text pages=%s  pages on both sides=%s  (page numbers stop matching once"
          " one engine is a page ahead)" % (len(word), len(ours), len(rows)))
    print("")
    print(" pg  wN oN  dN |  wTop oTop   dTop |   wSpan  oSpan  dSpan | wSlack oSlack  dSlack")
    for r in rows:
        print("%3d %3d %3d %+3d | %6.1f %6.1f %+6.1f | %6.1f %6.1f %+6.1f | %6.1f %6.1f %+6.1f"
              % (r["page"], r["wn"], r["on"], r["on"] - r["wn"], r["wf"], r["of"], r["top"],
                 r["ws"], r["os"], r["os"] - r["ws"],
                 r["bottom_word"], r["bottom_ours"], r["bottom_ours"] - r["bottom_word"]))

    def med(key):
        return statistics.median([r[key] for r in rows]) if rows else 0.0
    print("")
    print("over the %d pages compared:" % len(rows))
    for key, label in (("wf", "Word's first line top on the page"),
                       ("of", "our first line top on the page"),
                       ("top", "our first line top - Word's"),
                       ("bottom_word", "Word's slack under its last line"),
                       ("bottom_ours", "our slack under our last line")):
        print("  %-36s med %+8.2f px  min %+8.2f  max %+8.2f"
              % (label, med(key), min((r[key] for r in rows), default=0.0),
                 max((r[key] for r in rows), default=0.0)))
    dn = [r["on"] - r["wn"] for r in rows]
    print("  %-36s med %+8.2f  min %+8.2f  max %+8.2f  pages not equal: %d"
          % ("lines per page, ours minus Word's", med("on") - med("wn"), min(dn, default=0),
             max(dn, default=0), sum(1 for x in dn if x)))
    print("  %-36s ours %8.1f px   Word %8.1f px   diff %+7.1f px"
          % ("whole-document height billed", sum(r["our_used"] for r in rows),
             sum(r["word_used"] for r in rows),
             sum(r["our_used"] - r["word_used"] for r in rows)))
    dtop = [r["top"] for r in rows]
    dspan = [r["os"] - r["ws"] for r in rows]
    print("  per page, why our last line sits where it does:")
    print("      the first line starts lower            med %+7.2f px  p90 abs %6.2f"
          % (statistics.median(dtop), quantile([abs(x) for x in dtop], 0.9)))
    print("      the line heights add up differently    med %+7.2f px  p90 abs %6.2f"
          % (statistics.median(dspan), quantile([abs(x) for x in dspan], 0.9)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
