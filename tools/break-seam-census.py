#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/break-seam-census.py -- which characters does each engine put a line break after?

The acceptance item 7 counts disagreements; this counts RULES. It takes every line boundary each side
actually produced and classifies the pair of characters the boundary sits between, on both sides of the
comparison, from the same two sources item 7 uses:

    Word  : its own exported PDF, per paragraph (tools/break-agreement.py's paragraph matching, so the
            same line set item 7 scores -- no second implementation of the reader)
    phone : the capture's lines-all.tsv, line by line inside each device block

The point is to settle, from truth rather than from a spec quotation, what Word does on the characters
that are under dispute right now: "/" (Word allegedly never breaks there), "-" (Word breaks there, we
do not), and the edge of a Latin/digit run next to Chinese. Every "/" and "-" occurrence in the
document is listed with the page/line it falls on and whether a break sits right after it, so a rule
change cannot silently widen or narrow the set of breakable positions (URLs, dates like 2026-10-08,
alloy names like Cu-Sn are all in that list).

Usage:
    py tools/break-seam-census.py
    py tools/break-seam-census.py -Capture artifacts/agent-layout-verify/265-release -Chars "/,-,\\\\u2014"
"""
import argparse
import collections
import csv
import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def load(name, mod):
    spec = importlib.util.spec_from_file_location(mod, os.path.join(HERE, name))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def kind(c):
    if c is None:
        return "edge"
    o = ord(c)
    if c.isspace():
        return "space"
    if ("0" <= c <= "9"):
        return "digit"
    if ("A" <= c <= "Z") or ("a" <= c <= "z") or (0x00C0 <= o <= 0x024F):
        return "latin"
    if (0x2E80 <= o <= 0x9FFF) or (0xF900 <= o <= 0xFAFF) or (0xFF00 <= o <= 0xFF60) \
            or (0x3000 <= o <= 0x303F) or (0xAC00 <= o <= 0xD7AF):
        return "cjk"
    return "punct"


def seam_class(a, b):
    ka, kb = kind(a), kind(b)
    if a in "/\\-\u2013\u2014":
        return "after %r" % a
    if b in "/\\-\u2013\u2014":
        return "before %r" % b
    if ka == "space" or kb == "space":
        return "at a space"
    if ka == "cjk" and kb == "cjk":
        return "cjk|cjk"
    if ka == "cjk":
        return "cjk|%s" % kb
    if kb == "cjk":
        return "%s|cjk" % ka
    if ka in ("latin", "digit") and kb in ("latin", "digit"):
        return "inside alnum run"
    return "%s|%s" % (ka, kb)


def word_seams(pdf, docx, size_pt):
    ba = load("break-agreement.py", "ba_for_seams")
    paras = ba.source_paragraphs(docx)
    wl = ba.word_layout(pdf, paras, size_pt)
    out = []
    for pi, d in sorted(wl.items()):
        text = d["text"]
        run = d["lines"]
        for i in range(len(run) - 1):
            cut = run[i]["end_stripped"]
            out.append({"where": "para %d page %s line %d" % (pi, run[i]["page"], i + 1),
                        "a": text[cut - 1], "b": text[cut] if cut < len(text) else None,
                        "ctx": text[max(0, cut - 8):cut] + "|" + text[cut:cut + 8]})
    return out, len(wl)


def phone_seams(tsv):
    per = collections.OrderedDict()
    with open(tsv, encoding="utf-8") as fh:
        for r in csv.DictReader(fh, delimiter="\t"):
            per.setdefault(int(r["paragraph"]), []).append(r)
    out = []
    for blk, rows in per.items():
        rows.sort(key=lambda r: int(r["line"]))
        for i in range(len(rows) - 1):
            a = (rows[i]["lineFull"] or "").rstrip()
            b = (rows[i + 1]["lineFull"] or "").lstrip()
            if not a or not b:
                continue
            out.append({"where": "blk %d page %s line %s" % (blk, rows[i]["page"], rows[i]["line"]),
                        "a": a[-1], "b": b[0],
                        "ctx": a[-8:] + "|" + b[:8]})
    return out, len(per)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-Capture", default="artifacts/agent-layout-verify/265-release")
    ap.add_argument("-Pdf", default="artifacts/agent-typeset/pdf-truth/input-liu.pdf")
    ap.add_argument("-Docx", default="tests/samples/input-liu.docx")
    ap.add_argument("-SizePt", type=float, default=12.0)
    ap.add_argument("-Chars", default="/,-", help="characters to list every occurrence of")
    ap.add_argument("-Show", default="", help="comma list of seam-class substrings; prints up to -Top"
                    " examples of each class from both sides")
    ap.add_argument("-Top", type=int, default=12)
    a = ap.parse_args()
    for f in (a.Pdf, a.Docx, os.path.join(a.Capture, "new", "lines-all.tsv")):
        if not os.path.exists(f):
            print("missing input: " + f)
            return 1
    w, wpar = word_seams(a.Pdf, a.Docx, a.SizePt)
    ba0 = load("break-agreement.py", "ba_for_seams0")
    src_word = "\n".join(x["s"] for x in ba0.source_paragraphs(a.Docx))
    src_phone = "\n".join((r["lineFull"] or "") for r in
                          csv.DictReader(open(os.path.join(a.Capture, "new", "lines-all.tsv"),
                                              encoding="utf-8"), delimiter="\t"))
    p, pblk = phone_seams(os.path.join(a.Capture, "new", "lines-all.tsv"))
    print("Word: %d paragraphs with a full line set, %d line boundaries" % (wpar, len(w)))
    print("phone: %d device blocks, %d line boundaries" % (pblk, len(p)))
    cw = collections.Counter(seam_class(x["a"], x["b"]) for x in w)
    cp = collections.Counter(seam_class(x["a"], x["b"]) for x in p)
    print("")
    print("%-22s %8s %8s" % ("seam class", "Word", "phone"))
    for cls in sorted(set(cw) | set(cp), key=lambda c: -(cw[c] + cp[c])):
        print("%-22s %8d %8d" % (cls, cw[cls], cp[cls]))
    for ch in [c.strip().encode().decode("unicode_escape") for c in a.Chars.split(",") if c.strip()]:
        wch = [x for x in w if x["a"] == ch or x["b"] == ch]
        pch = [x for x in p if x["a"] == ch or x["b"] == ch]
        print("")
        print("line boundaries touching %r: Word %d, phone %d   (denominators: %r occurs %d times in"
              " Word's compared text, %d times in the phone's)"
              % (ch, len(wch), len(pch), ch, src_word.count(ch), src_phone.count(ch)))
        for x in wch:
            print("   WORD  %-28s break AFTER %r: %s" % (x["where"], x["a"] == ch, x["ctx"]))
        for x in pch:
            print("   PHONE %-28s break AFTER %r: %s" % (x["where"], x["a"] == ch, x["ctx"]))
    if a.Show:
        for want in [x.strip() for x in a.Show.split(",") if x.strip()]:
            wl = [x for x in w if want in seam_class(x["a"], x["b"])]
            pl = [x for x in p if want in seam_class(x["a"], x["b"])]
            print("")
            print("examples of seam class matching %r: Word %d, phone %d" % (want, len(wl), len(pl)))
            for x in wl[:a.Top]:
                print("   WORD  %-28s %s" % (x["where"], x["ctx"]))
            for x in pl[:a.Top]:
                print("   PHONE %-28s %s" % (x["where"], x["ctx"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
