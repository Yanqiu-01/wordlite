#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/tail-fit.py -- when a wrap leaves a blank at the end of a line, does that blank cost us a break?

The engine means to bill a line's trailing blanks nothing (DocxTextLayout.BlankTail: Word's line width
stops at the last character that has ink). It only puts that span on during the justification pass, and
the pass throws its result away when no line of the paragraph could be spread -- so on paragraphs that
never stretch the blank stays priced. tools/capture-device.ps1 now measures both halves of that on every
line (lineTailChars = blanks the wrap left inside the line, lineTailFree = whether BlankTail covers them)
and writes new/tail-fit.tsv, which lays every affected paragraph out a second time with those blanks
billed nothing (which=relay).

This tool scores both layouts against Word's own break points, taken from the PDF Word exported for that
paragraph, so the answer is a number and not an argument:

    cuts_matched   how many of our line breaks sit exactly on a Word break (stripped character offsets)
    first_diff     the first character offset where our break sequence and Word's part company

A paragraph whose relay column matches more of Word's breaks than the live column is a paragraph the
blank cost us. Nothing here changes the engine; it prices the change before anyone makes it.

Per line it also prints the cheaper test that needs no second layout:
    room + tail_cost >= next_run_px   the refused range would have fitted had the blank been free

Usage:
    py tools/tail-fit.py                                     # default capture below
    py tools/tail-fit.py -Capture artifacts/agent-layout-verify/tailfit1
    py tools/tail-fit.py -Tsv artifacts/word-break/tail-fit.tsv

Inputs it needs from the capture: new/lines-all.tsv (with lineTailChars/lineTailFree/lineRoomPx/
nextRunPx columns) and new/tail-fit.tsv, both produced by the current tools/device-probe/DeviceCapture.java.
"""
import argparse
import collections
import csv
import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def load(name):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, name))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def fnum(row, key, default=0.0):
    try:
        return float(row[key])
    except (KeyError, TypeError, ValueError):
        return default


def cuts_from(rows_by_line):
    """Break offsets in stripped coordinates from per-line character counts."""
    lines = [rows_by_line[k] for k in sorted(rows_by_line)]
    cuts, acc = [], 0
    for r in lines[:-1]:
        acc += int(r["lineChars"])
        cuts.append(acc)
    return cuts


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-Capture", default="artifacts/agent-layout-verify/tailfit1")
    ap.add_argument("-Docx", default="tests/samples/input-liu.docx")
    ap.add_argument("-Pdf", default="artifacts/agent-typeset/pdf-truth/input-liu.pdf")
    ap.add_argument("-SizePt", type=float, default=12.0)
    ap.add_argument("-Tsv", default="")
    a = ap.parse_args()
    lines_tsv = os.path.join(a.Capture, "new", "lines-all.tsv")
    tail_tsv = os.path.join(a.Capture, "new", "tail-fit.tsv")
    for f in (lines_tsv, tail_tsv, a.Pdf, a.Docx):
        if not os.path.exists(f):
            print("missing input: " + f)
            return 1
    head = open(lines_tsv, encoding="utf-8").readline()
    for col in ("lineTailChars", "lineTailFree", "lineRoomPx", "nextRunPx"):
        if col not in head:
            print(lines_tsv + " has no " + col + " column: re-run tools/capture-device.ps1 with the "
                  "current tools/device-probe/DeviceCapture.java")
            return 1

    ba = load("break-agreement.py")
    lines = list(csv.DictReader(open(lines_tsv, encoding="utf-8"), delimiter="\t"))
    tail = list(csv.DictReader(open(tail_tsv, encoding="utf-8"), delimiter="\t"))
    paras = ba.source_paragraphs(a.Docx)
    word = ba.word_layout(a.Pdf, paras, a.SizePt)
    blocks = ba.phone_blocks(lines_tsv)
    by_block = {b["block"]: b for b in blocks}
    paired = {b["block"]: pi for (b, pi) in ba.pair(blocks, word, paras)}
    blocks_by_id = {b["block"]: b for b in blocks}

    # --- per line: would the refused range have fitted with the blank free? ---
    live_rows = collections.defaultdict(dict)
    relay_rows = collections.defaultdict(dict)
    for r in tail:
        key = int(r["paragraph"])
        (live_rows if r["which"] == "live" else relay_rows)[key][int(r["line"])] = r

    priced = []
    for r in lines:
        n = int(fnum(r, "lineTailChars"))
        if n <= 0:
            continue
        room = fnum(r, "lineRoomPx")
        tail_px = fnum(r, "lineTailPx")
        nxt = fnum(r, "nextRunPx")
        billed = int(fnum(r, "lineTailFree")) == 0
        fit_now = nxt >= 0 and room >= nxt
        fit_free = nxt >= 0 and room + (tail_px if billed else 0.0) >= nxt
        priced.append({"page": r["page"], "block": r["paragraph"], "line": r["line"],
                       "tailChars": n, "tailPx": tail_px, "billed": billed,
                       "room": room, "nextRunPx": nxt, "nextRun": r["nextRunChars"],
                       "fits_now": fit_now, "fits_if_free": fit_free})

    would_move = [p for p in priced if p["billed"] and not p["fits_now"] and p["fits_if_free"]]
    print("lines with a blank left at the tail: %d; of those the blank is billed (no BlankTail): %d"
          % (len(priced), sum(1 for p in priced if p["billed"])))
    print("billed-tail lines where the refused range fits once the blank is free and does not without: %d"
          % len(would_move))
    for p in would_move[:20]:
        print("   page %s block %s line %s tail %s px room %.2f next %s (%s px)"
              % (p["page"], p["block"], p["line"], p["tailPx"], p["room"], p["nextRun"], p["nextRunPx"]))

    # --- paragraph level: score live and relay against Word's break points ---
    out_rows = []
    improved = same = worse = unpaired = 0
    for blk, rows in sorted(live_rows.items()):
        relay = relay_rows.get(blk, {})
        if blk not in paired:
            unpaired += 1
            continue
        pi = paired[blk]
        wcut = set(word[pi]["cuts"])
        live_cuts = cuts_from(rows)
        if not relay or max(int(k) for k in relay) + 1 < len(rows):
            relay_cuts = None
        else:
            relay_cuts = cuts_from(relay)
        lm = sum(1 for c in live_cuts if c in wcut)
        rm = lm if relay_cuts is None else sum(1 for c in relay_cuts if c in wcut)
        if relay_cuts is None:
            verdict = "no relay"
        elif rm > lm:
            verdict = "relay matches more"
            improved += 1
        elif rm < lm:
            verdict = "relay matches fewer"
            worse += 1
        else:
            verdict = "same"
            same += 1
        out_rows.append({"page": rows[min(rows)]["page"], "block": blk, "word_para": pi,
                         "lines_live": len(rows), "lines_relay": len(relay) or 0,
                         "word_cuts": len(wcut), "live_matched": lm, "relay_matched": rm,
                         "live_cuts": ",".join(str(c) for c in live_cuts),
                         "relay_cuts": ",".join(str(c) for c in (relay_cuts or [])),
                         "word_cut_list": ",".join(str(c) for c in sorted(wcut)),
                         "verdict": verdict})
    print("paragraphs that leave a blank at a line end and pair to a Word paragraph: %d "
          "(relay better %d, same %d, relay worse %d, unpairable %d)"
          % (len(out_rows), improved, same, worse, unpaired))
    for r in out_rows:
        if r["verdict"] != "same":
            print("   %s: page %s block %s word_para %s live %s/%s relay %s/%s  live_cuts[%s] relay[%s] word[%s]"
                  % (r["verdict"], r["page"], r["block"], r["word_para"], r["live_matched"],
                     r["word_cuts"], r["relay_matched"], r["word_cuts"], r["live_cuts"],
                     r["relay_cuts"], r["word_cut_list"]))
    if a.Tsv:
        os.makedirs(os.path.dirname(a.Tsv) or ".", exist_ok=True)
        cols = list(out_rows[0].keys()) if out_rows else ["block"]
        with open(a.Tsv, "w", encoding="utf-8", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=cols, delimiter="\t")
            w.writeheader()
            for r in out_rows:
                w.writerow(r)
        print("wrote " + a.Tsv)
    return 0


if __name__ == "__main__":
    sys.exit(main())