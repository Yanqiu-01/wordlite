#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/word-breakpoints.py -- line-by-line break-point table: Word's lines against the phone's.

Reads Word's break points out of the PDF Word exported with pymupdf (page.get_text("rawdict"), whose
line objects agree with Word's own lines: 803 body lines, the same count page.get_text("dict") gives;
clustering page.get_text("words") by y gives 1020 "lines", i.e. it splits spans apart and is only
good as a cross-check). Each Word line is matched back to its paragraph in tests/samples/input-liu.docx
with a monotonic cursor, so every line carries its exact character range and the size Word drew each
character at (that is how a superscript citation [29] is seen at 8.04 pt instead of 12 pt).

The phone side comes from a capture's lines-all.tsv, and the paragraph pairing (device block -> Word
paragraph) is taken from the parity report's line-delta.tsv so this table reconciles with the 69.7%
figure in docs/layout-parity-target.md section 0 instead of inventing its own sample.

Every line is billed twice under OUR width model: the characters Word fitted, and the characters the
phone fitted. The difference is what the missing characters cost, and that is what names the rule:
a cost of ~4 px per character is a seam, ~9 px is a script run billed at base size, ~16 px is a plain
Chinese character, and a line that ends on a mark that Word let hang shows up as a negative cost.

Usage:
    py tools/word-breakpoints.py
    py tools/word-breakpoints.py -Capture artifacts/agent-layout-verify/main251 -Out artifacts/word-break/breakpoints.tsv
"""
import argparse
import collections
import csv
import importlib.util
import os
import statistics
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def load(name, mod):
    spec = importlib.util.spec_from_file_location(mod, os.path.join(HERE, name))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


wb = load("width-bill.py", "wb_for_bpp")
HANGABLE = wb.__dict__.setdefault("HANGABLE", None)

NO_START = set([chr(0xFF0C), chr(0x3002), chr(0x3001), chr(0xFF1B), chr(0xFF1A), chr(0xFF1F),
                chr(0xFF01), chr(0xFF09), chr(0x201D), chr(0x2019), chr(0x2026), chr(0x2014)])


def bill_word_line(chars, charge_seams=True):
    """Our width model applied to the characters Word put on one line (per-character size from the PDF)."""
    total = 0.0
    n = len(chars)
    for i, (c, x, sz, pf) in enumerate(chars):
        adv = wb.advance_px(c, pf, sz)
        seams = 0
        if charge_seams:
            if i > 0 and wb.seam(chars[i - 1][0], c):
                seams += 1
            if i + 1 < n and wb.seam(c, chars[i + 1][0]):
                seams += 1
        total = total + (adv + seams * sz * wb.PT_TO_PX * wb.GAP_EM if seams else adv)
    return total


def word_lines(pdf, docx, size_pt):
    """[{text, cuts:[offsets], lines:[pdf chars per line], indent_px, page}], Word's own layout."""
    paras = wb.docx_paragraphs(docx)
    flat = [(t,) + wb.stripped(t) for t in paras]
    pix, cursor = 0, 0
    seq = []
    for ln in wb.load_sibling().read_lines(pdf):
        chars = [c for c in ln["chars"] if c[0] not in ("\t", "\u3000")]
        ink = [c for c in chars if c[0] != " "]
        if abs(ln["size"] - size_pt) > 0.6 or len(ink) < 4:
            continue
        raw = "".join(c[0] for c in ink)
        start, found, hit = (pix, cursor), False, -1
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
            seq.append(None)
            continue
        text, s, idx = flat[pix]
        seq.append({"para": pix, "begin": idx[hit], "end": idx[hit + len(raw) - 1] + 1,
                    "chars": ink, "x": ink[0][1], "size": ln["size"], "page": ln["page"]})
        cursor = hit + len(raw)
        if cursor >= len(s):
            pix += 1
            cursor = 0
    out, run = [], []
    for rec in seq + [None]:
        if not (run and rec and rec["para"] == run[-1]["para"]):
            if len(run) >= 2:
                out.append(run)
            run = []
        if rec:
            run.append(rec)
    paras_out = []
    for run in out:
        pi = run[0]["para"]
        text = paras[pi]
        if run[0]["begin"] != 0 or any(run[i + 1]["begin"] != run[i]["end"] for i in range(len(run) - 1)):
            continue
        paras_out.append({"para": pi, "text": text, "page": run[0]["page"],
                          "indent_px": (run[0]["x"] - run[-1]["x"] * 0 - min(r["x"] for r in run))
                          * wb.PT_TO_PX,
                          "lines": run, "cuts": [r["end"] for r in run[:-1]]})
    return paras_out


def device_lines(tsv):
    out = collections.defaultdict(list)
    with open(tsv, encoding="utf-8") as fh:
        for row in csv.DictReader(fh, delimiter="\t"):
            out[int(row["paragraph"])].append({"line": int(row["line"]), "chars": int(row["lineChars"]),
                                               "px": float(row["lineWidthPx"]),
                                               "page": int(row["page"]), "text": row["lineFull"]})
    return out


def pairing(delta_tsv):
    out = {}
    with open(delta_tsv, encoding="utf-8") as fh:
        for row in csv.DictReader(fh, delimiter="\t"):
            out[int(row["device_block"])] = int(row["word_para"])
    return out


def cost_of(text, i, j, size_pt):
    return bill_word_line([(text[k], 0.0, size_pt, "SimSun" if wb.is_cjk(text[k]) else "times")
                           for k in range(i, j)])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-Pdf", default="artifacts/agent-typeset/pdf-truth/input-liu.pdf")
    ap.add_argument("-Docx", default="tests/samples/input-liu.docx")
    ap.add_argument("-Capture", default="artifacts/agent-layout-verify/main251")
    ap.add_argument("-Out", default="artifacts/word-break/breakpoints.tsv")
    ap.add_argument("-SizePt", type=float, default=12.0)
    ap.add_argument("-Top", type=int, default=14)
    a = ap.parse_args()
    lines = word_lines(a.Pdf, a.Docx, a.SizePt)
    dev = device_lines(os.path.join(a.Capture, "new", "lines-all.tsv"))
    pairs = pairing(os.path.join(a.Capture, "parity", "line-delta.tsv"))
    by_para = {p["para"]: p for p in lines}
    rows = []
    for block, para in sorted(pairs.items()):
        if block not in dev or para not in by_para:
            continue
        p = by_para[para]
        text = p["text"]
        bounds = [0] + p["cuts"] + [len(text.rstrip())]
        dlines = sorted(dev[block], key=lambda d: d["line"])
        for k in range(min(len(bounds) - 1, len(dlines))):
            i, j = bounds[k], bounds[k + 1]
            d = dlines[k]
            word_bill = bill_word_line(p["lines"][k]["chars"]) if k < len(p["lines"]) else 0.0
            extra = j - (i + d["chars"])
            # what the characters Word fitted and the phone did not cost under our own model
            cost = cost_of(text, i + d["chars"], j, a.SizePt) if extra > 0 else 0.0
            if extra == 0 and d["chars"] == j - i:
                mech = "same"
            elif extra > 0 and all(ord(c) < 0x2E80 for c in text[i + d["chars"]:j]) and len(
                    [c for c in text[i + d["chars"]:j] if not wb.is_cjk(c)]) == j - i - d["chars"]:
                mech = "latin/digit token"
            elif extra > 0 and text[j - 1] in NO_START:
                mech = "trailing mark hang"
            elif extra > 0 and any(wb.seam(text[m - 1], text[m]) for m in range(i + 1, j)):
                mech = "script seam"
            elif extra > 0:
                mech = "advance/rounding"
            elif extra < 0:
                mech = "phone holds more"
            else:
                mech = "line count"
            rows.append({"word_para": para, "device_block": block, "line": k + 1,
                         "word_page": p["page"], "device_page": d["page"],
                         "word_chars": j - i, "device_chars": d["chars"], "delta_chars": extra,
                         "word_line_px_ourmodel": round(word_bill, 2),
                         "device_line_px": d["px"],
                         "missing_cost_px": round(cost, 2),
                         "missing_text": text[i + d["chars"]:j] if extra > 0 else "",
                         "word_tail": text[max(i, j - 8):j], "mechanism": mech})
    os.makedirs(os.path.dirname(a.Out), exist_ok=True)
    with open(a.Out, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()), delimiter="\t")
        w.writeheader()
        w.writerows(rows)
    print("rows=" + str(len(rows)) + "  lines where Word and the phone cut identically="
          + str(sum(1 for r in rows if r["mechanism"] == "same")) + "  -> " + a.Out)
    hist = collections.Counter(r["mechanism"] for r in rows)
    print("")
    print("break-point differences by mechanism (of the %d compared lines):" % len(rows))
    for mech, n in hist.most_common():
        sub = [r for r in rows if r["mechanism"] == mech]
        words_more = sum(1 for r in sub if r["delta_chars"] > 0)
        cost = [r["missing_cost_px"] for r in sub if r["missing_cost_px"]]
        print("  %-20s %3d lines  (%d Word holds more / %d phone holds more)  median cost of the "
              "missing characters %s px" % (mech, n, words_more, n - words_more - (mech == "same"),
                                            ("%.2f" % statistics.median(cost)) if cost else "-"))
    print("")
    print("the lines where Word held the most extra characters:")
    for r in sorted(rows, key=lambda r: -r["delta_chars"])[:a.Top]:
        print("  para %3d line %2d  word %2d chars vs phone %2d  (+%d, costs %6.2f px)  word tail %r"
              "  missing %r" % (r["word_para"], r["line"], r["word_chars"], r["device_chars"],
                                r["delta_chars"], r["missing_cost_px"], r["word_tail"],
                                r["missing_text"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
