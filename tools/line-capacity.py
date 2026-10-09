#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/line-capacity.py -- on a line we broke early, how many characters did Word fit?

tools/break-agreement.py says WHERE the two engines disagree; this says whether the disagreement is
capacity (Word simply fitted more characters on that line) or seam choice (same number of characters,
different boundary). That distinction decides the fix: capacity is the width model, seam choice is the
break rule (which side of a Latin/digit block the line ends on).

Word's side is read out of Word's own exported PDF (same reader tools/break-agreement.py uses, so the
line splitting and the paragraph matching are the same code, not a second implementation). Our side is
the capture's lines-all.tsv. For each "we broke here, Word did not" row, this takes the SAME line
number of the SAME paragraph on both sides and prints the character counts.

Word's per-character figure is ink start to ink end divided by (characters - 1). On a justified line
Word stretches the advances, so that figure sits above the natural 16.00 px for 12 pt Chinese; it is
printed to show the stretch, not to compare advance tables.

Usage:
    py tools/line-capacity.py                                  # the 43 Latin/digit + cjk|cjk rows
    py tools/line-capacity.py -Capture artifacts/agent-layout-verify/265-release \
        -Classes "cjk|cjk,cjk|digit,cjk|latin,latin|cjk,digit|cjk,after '/'"
"""
import argparse
import collections
import csv
import importlib.util
import os
import statistics
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PT_TO_PX = 4.0 / 3.0


def load_sibling(name):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, name))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-Capture", default="artifacts/agent-layout-verify/265-release")
    ap.add_argument("-Rows", default="", help="default: <Capture>/break-agreement.tsv")
    ap.add_argument("-Pdf", default="artifacts/agent-typeset/pdf-truth/input-liu.pdf")
    ap.add_argument("-Docx", default="tests/samples/input-liu.docx")
    ap.add_argument("-SizePt", type=float, default=12.0)
    ap.add_argument("-Column", type=float, default=566.93, help="text column width in document px")
    ap.add_argument("-Classes", default="cjk|cjk,cjk|digit,cjk|latin,latin|cjk,digit|cjk")
    a = ap.parse_args()
    rows_path = a.Rows or os.path.join(a.Capture, "break-agreement.tsv")
    for f in (rows_path, os.path.join(a.Capture, "new", "lines-all.tsv"), a.Pdf, a.Docx):
        if not os.path.exists(f):
            print("missing input: " + f)
            return 1
    ba = load_sibling("break-agreement.py")
    wanted = set(c.strip() for c in a.Classes.split(",") if c.strip())
    rows = list(csv.DictReader(open(rows_path, encoding="utf-8-sig"), delimiter="\t"))
    picked = [r for r in rows if r["side"] == "phone_only" and r["cut_class"] in wanted]
    ours = collections.defaultdict(list)
    with open(os.path.join(a.Capture, "new", "lines-all.tsv"), encoding="utf-8") as fh:
        for r in csv.DictReader(fh, delimiter="\t"):
            ours[(int(r["paragraph"]), int(r["line"]))].append(r)
    wl = ba.word_layout(a.Pdf, ba.source_paragraphs(a.Docx), a.SizePt)
    print("capture=%s   phone_only rows in the asked-for classes=%d   Word paragraphs with a full line set=%d"
          % (a.Capture, len(picked), len(wl)))
    print("")
    print("%-6s %-5s %3s %3s %4s %9s %8s  %s"
          % ("para", "line", "wN", "oN", "dN", "wSpan", "w/char", "tail of our line"))
    diffs, wper = [], []
    for r in picked:
        wp, wline, blk = int(r["word_para"]), int(r["word_line"]), int(r["device_block"])
        if wp not in wl or wline - 1 >= len(wl[wp]["lines"]):
            continue
        w = wl[wp]["lines"][wline - 1]
        xs = [c[1] for c in w["chars"] if len(c) > 1]
        n = len(w["chars"])
        if len(xs) < 2:
            continue
        o = ours.get((blk, wline - 1))
        if not o:
            continue
        span = (max(xs) - min(xs)) * PT_TO_PX
        nc = int(o[0]["lineChars"])
        diffs.append(nc - n)
        wper.append(span / (n - 1))
        print("%-6d %-5d %3d %3d %+4d %9.2f %8.3f  %s"
              % (wp, wline, n, nc, nc - n, span, span / (n - 1), (o[0].get("lineFull") or o[0]["text"])[-14:]))
    if not diffs:
        print("nothing comparable")
        return 1
    print("")
    print("lines compared %d   characters per line, ours minus Word's: med %+.2f  min %+d  max %+d"
          % (len(diffs), statistics.median(diffs), min(diffs), max(diffs)))
    print("  we hold fewer characters: %d lines   same number: %d   we hold more: %d"
          % (sum(1 for d in diffs if d < 0), sum(1 for d in diffs if d == 0),
             sum(1 for d in diffs if d > 0)))
    print("  -> fewer = capacity (width model); same number but a different seam = break rule")
    print("Word's ink span per character on those lines: med %.3f px (12 pt Chinese natural advance is"
          " 16.00 px, column %.2f px)" % (statistics.median(wper), a.Column))
    return 0


if __name__ == "__main__":
    sys.exit(main())
