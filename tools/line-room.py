#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/line-room.py -- on a line we broke early, was there room for what we refused to take?

tools/break-agreement.py says WHERE the two engines break differently and tools/line-capacity.py says
whether the two lines hold a different number of characters. Neither answers the question that decides
the fix: did our line stop because the next thing genuinely did not fit, or did a rule refuse it while
room was standing there unused?

It needed a new rig for one reason: lineWidthPx is measured AFTER the justification pass stretched the
line, so on a spread line it reads the column width whether the line is full or half empty. The capture
now carries the figure before that stretch (lineNaturalPx), the room the layout had
(lineRoomPx = layout width - line left offset - natural width), and the natural width of the range that
had to cross the break whole (nextRunPx -- a glued Latin/digit string moves entire, so one character's
box is the wrong yardstick for a line that stopped in front of it).

Alignment is done on paragraph character offsets, not on character counts. That matters: several of
these rows differ at the line's HEAD (our line above took one character Word left to this one), and
pricing such a line as "Word's characters minus its last" bills a character set neither side has. That
mistake read as "the phone bills 17-29 px over our own table" on seven rows; with the offsets lined up
the same rows sit within a pixel. A row whose head is shifted is reported as a knock-on from the line
above and kept out of the medians.

Both sides are then priced out of the same embedded advance tables (tools/width-bill.py) for the same
characters, which splits the family without a guess:
    word_bill <= column    Word's characters fit our advance table, so a rule (not the width model)
                           moved them, and our line still had lineRoomPx of the column free.
    word_bill >  column    our table charges more than Word's for those characters; the gap is
                           word_bill - column px and it belongs to the width model.

Usage:
    py tools/line-room.py                                        # default capture below
    py tools/line-room.py -Capture artifacts/agent-layout-verify/prestretch1
    py tools/line-room.py -Classes "cjk|cjk"                     # one class
    py tools/line-room.py -Tsv artifacts/word-break/line-room.tsv
"""
import argparse
import collections
import csv
import importlib.util
import math
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


def fnum(row, key):
    try:
        return float(row[key])
    except (KeyError, TypeError, ValueError):
        return float("nan")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-Capture", default="artifacts/agent-layout-verify/prestretch1")
    ap.add_argument("-Rows", default="", help="default: <Capture>/break-agreement.tsv")
    ap.add_argument("-Pdf", default="artifacts/agent-typeset/pdf-truth/input-liu.pdf")
    ap.add_argument("-Docx", default="tests/samples/input-liu.docx")
    ap.add_argument("-SizePt", type=float, default=12.0)
    ap.add_argument("-Column", type=float, default=566.93)
    ap.add_argument("-Classes", default="cjk|cjk,cjk|digit,cjk|latin,latin|cjk,digit|cjk")
    ap.add_argument("-Tsv", default="")
    a = ap.parse_args()
    rows_path = a.Rows or os.path.join(a.Capture, "break-agreement.tsv")
    lines_tsv = os.path.join(a.Capture, "new", "lines-all.tsv")
    for f in (rows_path, lines_tsv, a.Pdf, a.Docx):
        if not os.path.exists(f):
            print("missing input: " + f)
            return 1
    if "lineNaturalPx" not in open(lines_tsv, encoding="utf-8").readline():
        print(lines_tsv + " carries no lineNaturalPx column: re-run tools/capture-device.ps1 with the "
              "current tools/device-probe/DeviceCapture.java")
        return 1
    ba = load_sibling("break-agreement.py")
    wb = load_sibling("width-bill.py")
    wanted = set(c.strip() for c in a.Classes.split(",") if c.strip())

    rows = list(csv.DictReader(open(rows_path, encoding="utf-8-sig"), delimiter="\t"))
    picked = [r for r in rows if r["side"] == "phone_only" and r["cut_class"] in wanted]
    ours = collections.defaultdict(list)
    with open(lines_tsv, encoding="utf-8") as fh:
        for r in csv.DictReader(fh, delimiter="\t"):
            ours[(int(r["paragraph"]), int(r["line"]))].append(r)
    blocks = collections.defaultdict(list)
    for (blk, line), v in ours.items():
        blocks[blk].append((line, v))
    starts = {}
    for blk, lst in blocks.items():
        acc = 0
        for line, v in sorted(lst):
            starts[(blk, line)] = acc
            acc += int(v[0]["lineChars"])
    wl = ba.word_layout(a.Pdf, ba.source_paragraphs(a.Docx), a.SizePt)

    def para_chars(pi):
        """Every character of a Word paragraph with the face Word drew it in, by stripped offset."""
        out = []
        for rec in wl[pi]["lines"]:
            out.extend(rec["chars"])
        return out

    def raw_stream(pi):
        """(char, face, size) for every RAW character of a paragraph, blanks included.

        Word's PDF lines carry no blanks. Pricing a line out of that list silently drops every space the
        engine bills (4.00 px at 12 pt) and cuts off the seam that sits on the character just outside the
        window: the engine hangs an autoSpace gap on its CJK side, so a line whose FIRST character is that
        CJK side pays for a boundary the window never shows. Both artifacts read as "the phone bills
        8-25 px more than our own table", and neither is real. The paragraph's raw text plus Word's
        stripped-to-raw index map puts the blanks back, and neighbours are then read from the raw string
        whether or not they fall inside the window being priced.
        """
        rec = wl[pi]
        pc = para_chars(pi)
        raw, idx = rec["raw"], rec["idx"]
        face = [None] * len(raw)
        size = [None] * len(raw)
        for k, o in enumerate(idx):
            if k < len(pc):
                _c, _x, sz, f = pc[k]
                face[o], size[o] = f, sz
        for o, c in enumerate(raw):
            if face[o] is not None:
                continue
            nxt = next((size[j] for j in range(o + 1, len(raw)) if size[j]), None)
            prv = next((size[j] for j in range(o - 1, -1, -1) if size[j]), None)
            face[o], size[o] = "TimesNewRomanPSMT", (nxt or prv or 12.0)
        return [(raw[o], face[o], size[o]) for o in range(len(raw))]

    def price_raw(stream, lo, hi):
        """What our own table says the engine's characters lo..hi cost: advances, seams, blanks."""
        if hi - lo < 1:
            return float("nan"), 0
        total = 0.0
        seams = 0
        for o in range(lo, min(hi, len(stream))):
            c, f, sz = stream[o]
            if c in (" ", "\t", "\u3000", "\xa0"):
                total += wb.advance_px(" ", f, sz)      # the engine takes a blank from the Western face
                continue
            adv = wb.advance_px(c, f, sz)
            n = 0
            if wb.kind(c) == 1 and not wb.is_cjk_punct(c):
                left = stream[o - 1][0] if o > 0 else " "
                right = stream[o + 1][0] if o + 1 < len(stream) else " "
                n = (1 if wb.seam(left, c) else 0) + (1 if wb.seam(c, right) else 0)
            unit = math.floor(adv + 0.5)                # the device bills a whole pixel per glyph
            seams += n
            total += math.ceil(unit + n * sz * PT_TO_PX * 0.25) if n else unit
        return total, seams

    print("capture=%s   Word paragraphs with a full line set=%d   rows in the asked-for classes=%d"
          % (a.Capture, len(wl), len(picked)))
    print("%-5s %-4s %-3s %-9s %4s %8s %7s %7s %8s %7s %-9s %8s  %s"
          % ("para", "line", "pg", "class", "short", "ourNat", "room", "runPx", "ourBill",
             "wordBill", "refused", "dev-bill", "what the numbers say"))
    out_rows, verdicts = [], collections.Counter()
    for r in picked:
        wp, wline, blk = int(r["word_para"]), int(r["word_line"]), int(r["device_block"])
        if wp not in wl or wline - 1 >= len(wl[wp]["lines"]) or (blk, wline - 1) not in starts:
            continue
        o = ours[(blk, wline - 1)][0]
        wrec = wl[wp]["lines"][wline - 1]
        pc = para_chars(wp)
        o_begin, w_begin, w_end = starts[(blk, wline - 1)], wrec["begin_stripped"], wrec["end_stripped"]
        o_end = o_begin + int(o["lineChars"])
        if o_end > len(pc) or w_end > len(pc):
            continue
        short = w_end - o_end                      # characters Word kept on this line that we gave up
        head = o_begin - w_begin
        natural, room = fnum(o, "lineNaturalPx"), fnum(o, "lineRoomPx")
        run_px = fnum(o, "nextRunPx")
        refused = pc[o_end][0] if 0 <= o_end < len(pc) else "?"

        stream = raw_stream(wp)
        idx = wl[wp]["idx"]

        def raw_of(k):
            return idx[k] if 0 <= k < len(idx) else len(stream)

        our_bill, our_seams = price_raw(stream, raw_of(o_begin), raw_of(o_end - 1) + 1)
        word_bill, word_seams = price_raw(stream, raw_of(w_begin), raw_of(w_end - 1) + 1)
        dev_bill = natural - our_bill
        next_px = wb.advance_px(pc[o_end][0], pc[o_end][3], pc[o_end][2]) if 0 <= o_end < len(pc) else float("nan")
        if head != 0:
            verdict = "knock-on: this line starts %+d chars off, the break above moved" % head
        elif short <= 0:
            verdict = "not short on this line"
        elif 0 < run_px <= room:
            verdict = "the refused run fits the room left: rule, not width"
        elif word_bill == word_bill and word_bill <= a.Column + 0.5:
            verdict = ("Word's characters fit our table (%.2f px spare) and our own line already cost "
                       "%.2f of %.2f: the phone bills %+.2f px over our table"
                       % (a.Column - word_bill, natural, a.Column, dev_bill))
        else:
            verdict = "Word's characters cost %.2f px over the column in our table: width model" % (
                word_bill - a.Column)
        verdicts[verdict.split(":")[0]] += 1
        print("%-5d %-4d %-3s %-9s %4d %8.2f %7.2f %7.2f %8.2f %7.2f %-9s %+8.2f  %s"
              % (wp, wline, r["word_page"], r["cut_class"], short, natural, room, run_px, our_bill,
                 word_bill, refused, dev_bill, verdict))
        out_rows.append({"word_para": wp, "word_line": wline, "word_page": r["word_page"],
                         "device_block": blk, "cut_class": r["cut_class"], "head_shift": head,
                         "chars_short": short, "our_natural_px": round(natural, 2),
                         "our_room_px": round(room, 2), "our_stretch_px": round(fnum(o, "lineStretchPx"), 2),
                         "refused_run_px": round(run_px, 2), "refused_char": refused,
                         "refused_char_px": round(next_px, 2), "our_bill_px": round(our_bill, 2),
                         "our_seams": our_seams, "word_seams": word_seams,
                         "word_bill_px": round(word_bill, 2), "phone_over_own_table_px": round(dev_bill, 2),
                         "column_px": a.Column, "verdict": verdict})
    if not out_rows:
        print("nothing comparable")
        return 1
    aligned = [r for r in out_rows if r["head_shift"] == 0 and r["chars_short"] > 0]
    print("")
    print("rows %d   aligned at the head and short on this line: %d   knock-ons from the line above: %d"
          % (len(out_rows), len(aligned), sum(1 for r in out_rows if r["head_shift"] != 0)))
    if aligned:
        print("      characters we gave up: med %.1f  max %d   room still free when we broke: med %.2f px  max %.2f"
              % (statistics.median([r["chars_short"] for r in aligned]),
                 max(r["chars_short"] for r in aligned),
                 statistics.median([r["our_room_px"] for r in aligned]),
                 max(r["our_room_px"] for r in aligned)))
        print("      the phone minus our own table for the SAME characters: med %+.2f px  min %+.2f  max %+.2f"
              % (statistics.median([r["phone_over_own_table_px"] for r in aligned]),
                 min(r["phone_over_own_table_px"] for r in aligned),
                 max(r["phone_over_own_table_px"] for r in aligned)))
        print("      room >= the whole refused run: %d of %d rows"
              % (sum(1 for r in aligned if 0 < r["refused_run_px"] <= r["our_room_px"]), len(aligned)))
        print("      Word's characters fit our table (<= column): %d of %d rows"
              % (sum(1 for r in aligned if r["word_bill_px"] <= a.Column + 0.5), len(aligned)))
    for k, v in verdicts.most_common():
        print("      %-58s %d" % (k, v))
    if a.Tsv:
        with open(a.Tsv, "w", encoding="utf-8", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=list(out_rows[0].keys()), delimiter="\t")
            w.writeheader()
            for r in out_rows:
                w.writerow(r)
        print("wrote " + a.Tsv)
    return 0


if __name__ == "__main__":
    sys.exit(main())
