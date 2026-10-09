#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/hang-truth.py -- does Word let a line end past its own right margin, and on which character?

This is w:overflowPunct measured out of the PDF Word exported, not taken from the spec. For every body
line that starts on the left margin and is drawn at 12 pt, it compares the last character's box with
Word's own right margin and reports how far past it lands.

The number DocxTextLayout's hanging-punctuation rule is built on (2026-10-09 run):
    370 body lines at the left margin drawn at 12 pt
     22 of them end with the last character's box 11.42 .. 12.44 pt past the right margin
     one full em of one full-width mark, never two marks; the marks seen are ，。、；：？！
So a closing mark that no longer fits is not pushed to the next line: Word keeps it at the end of this
line and lets it draw outside. Our engine used to ask for the mark's ink to fit INSIDE the column,
which on a justified Chinese line can never happen because that line is already spread to the margin.

Truth file is pinned by sha256 so a re-exported PDF cannot quietly move the number:
    artifacts/agent-typeset/pdf-truth/input-liu.pdf
    AB298AC416DCFC72855645E5AD8E472ABDA3DFFC9A6B10925A4FAA1396E761A1

Usage:
    py tools/hang-truth.py
    py tools/hang-truth.py -Pdf <path> -Left 85.1 -Right 510.3 -SizePt 12.0 -Top 12

    py tools/hang-truth.py -Capture artifacts/agent-layout-verify/hang1
        second mode: takes every Word line that ends past its own margin and looks for the same line in
        a device capture, so "we let the mark hang" is checked line by line instead of assumed. It
        answers with the phone's own last character, character count, line width and hangOverPx.
"""
import argparse, collections, hashlib, os, sys

PINNED_SHA = "AB298AC416DCFC72855645E5AD8E472ABDA3DFFC9A6B10925A4FAA1396E761A1"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-Pdf", default="artifacts/agent-typeset/pdf-truth/input-liu.pdf")
    ap.add_argument("-Left", type=float, default=85.1, help="left margin pt, mode of line left edges")
    ap.add_argument("-Right", type=float, default=510.3, help="right margin pt, mode of line right edges")
    ap.add_argument("-SizePt", type=float, default=12.0)
    ap.add_argument("-TolerancePt", type=float, default=1.0)
    ap.add_argument("-Top", type=int, default=12)
    ap.add_argument("-SkipShaCheck", action="store_true")
    ap.add_argument("-Capture", default="", help="device capture dir; compare those 22 lines")
    a = ap.parse_args()

    if not os.path.exists(a.Pdf):
        print("missing truth file: " + a.Pdf)
        return 1
    digest = hashlib.sha256(open(a.Pdf, "rb").read()).hexdigest().upper()
    print("truth pdf: %s" % a.Pdf)
    print("  sha256 %s" % digest)
    if digest != PINNED_SHA and not a.SkipShaCheck:
        print("  NOT the file this rule was measured on (pinned %s)." % PINNED_SHA)
        print("  Re-measure the engine rule before quoting it; pass -SkipShaCheck to measure anyway.")
        return 1

    import pymupdf
    doc = pymupdf.open(a.Pdf)
    hangs, overs, body = collections.Counter(), [], 0
    word_lines = []
    body_texts = []
    for pno in range(len(doc)):
        for b in doc[pno].get_text("rawdict")["blocks"]:
            for ln in b.get("lines", []):
                chars = []
                for s in ln["spans"]:
                    for c in s["chars"]:
                        chars.append((c["c"], c["bbox"], s["size"]))
                ink = [c for c in chars if c[0] != " "]
                if len(ink) < 6:
                    continue
                if ink[0][1][3] - ink[0][1][1] < 8:          # superscripts and other small marks
                    continue
                if ink[0][1][0] > a.Left + 0.5:              # only lines starting on the margin
                    continue
                sizes = collections.Counter(round(c[2], 2) for c in ink)
                if abs(sizes.most_common(1)[0][0] - a.SizePt) > 0.6:
                    continue
                body += 1
                body_texts.append("".join(c[0] for c in ink))
                over = ink[-1][1][2] - a.Right
                if over > a.TolerancePt:
                    hangs[ink[-1][0]] += 1
                    overs.append(over)
                    word_lines.append((pno + 1, "".join(c[0] for c in ink), ink[-1][0], over))
    print("body lines at the left margin drawn at %.1f pt: %d" % (a.SizePt, body))
    print("ending more than %.1f pt past Word's own right margin: %d" % (a.TolerancePt, sum(hangs.values())))
    if overs:
        overs.sort()
        print("  how far past (pt): min %.2f  median %.2f  max %.2f"
              % (overs[0], overs[len(overs) // 2], overs[-1]))
        print("  one em at %.1f pt is %.2f pt, so that is one full character box outside"
              % (a.SizePt, a.SizePt))
    print("  which character hangs: %s" % ", ".join("%s x%d" % (k, v) for k, v in hangs.most_common(a.Top)))

    counts = sorted(len(t) for _p, t, _m, _o in word_lines)
    widest = max([len(t) for t in body_texts] or [0])
    print("  characters on those %d lines: min %d, median %d, max %d; widest body line overall: %d"
          % (len(counts), counts[0], counts[len(counts) // 2], counts[-1], widest)
          )
    print('  so "a line holds at most N full-width characters" has to be counted INSIDE the margin:')
    print('  a hanging mark is one more character on the line, drawn outside it.')

    if a.Capture:
        compare_with_capture(a.Capture, word_lines, a.Top)
    return 0


def compare_with_capture(capture, word_lines, top):
    """Look for each of Word's over-margin lines in a device capture, line by line.

    Three outcomes per line, and they mean different things:
      hung like Word      the phone's line has the same text (the mark stayed on this line)
      mark pushed down    the phone's line is Word's line without the trailing mark (one char short)
      no line to match    the phone broke this paragraph somewhere else, so this line proves nothing
    """
    import csv
    tsv = os.path.join(capture, "new", "lines-all.tsv")
    if not os.path.exists(tsv):
        print("capture has no new/lines-all.tsv: " + tsv)
        return
    ours, by_text = [], collections.defaultdict(list)
    with open(tsv, encoding="utf-8-sig", newline="") as fh:
        for row in csv.DictReader(fh, delimiter="\t"):
            text = (row.get("lineFull") or "").replace(" ", "")
            ours.append(row)
            by_text[text].append(row)
    hung = dropped = missing = 0
    print("")
    print("the same lines on the phone (%s)" % capture)
    shown = 0
    for page, text, mark, over in word_lines:
        if text in by_text:
            hung += 1
            row, how = by_text[text][0], "hung like Word"
        elif text[:-1] in by_text:
            dropped += 1
            row, how = by_text[text[:-1]][0], "mark pushed down (one char short)"
        else:
            missing += 1
            row, how = None, "no line to match"
        if row is None:
            print("  word p%-2d %-28s %s" % (page, "%r past by %.2f pt" % (mark, over), how))
        else:
            print("  word p%-2d %-28s %s  phone p%s blk %s line %s: %s chars, %.2f px, hangOverPx %s"
                  % (page, "%r past by %.2f pt" % (mark, over), how, row["page"], row["paragraph"],
                     row["line"], row["lineChars"], float(row["lineWidthPx"]),
                     row.get("hangOverPx", "n/a")))
            shown += 1
        if shown >= top:
            break
    print("  total: hung like Word %d, mark pushed down %d, no line to match %d (of %d)"
          % (hung, dropped, missing, len(word_lines)))


if __name__ == "__main__":
    sys.exit(main())