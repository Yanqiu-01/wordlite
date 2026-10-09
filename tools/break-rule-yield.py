#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/break-rule-yield.py -- what each candidate line-break rule is worth on this thesis, in break points.

tools/break-agreement.py says which break points disagree. It cannot say whether a candidate RULE is
worth writing: a rule that forbids one seam moves the break to the NEXT seam, so the gain is the
disagreement it removes and the cost is the agreement it destroys. This prints both, per rule, over the
same paragraphs and the same truth.

The characters are classified by their UAX#14 Line_Break class (the same table tools/../docs
/line-break-rules-uax14.md is written against): ID Han, NU digits, AL letters, PO numeric postfix
(U+2103 DEGREE CELSIUS, U+00B0, U+0025), PR prefix, CL/CP closing, IS infix separator, NS nonstarter,
EX, OP, QU, HY, BA, SY, IN, SP. class_of() below is that table narrowed to the classes this document
can produce; pass -LineBreak <LineBreak.txt> to read the authoritative file instead of the table.

Two shapes per rule, because a space changes the rule set (LB18 breaks after a space, and only LB16,
LB17 and ICU's LB14/LB15a bridge one):
  adjacent   the seam falls between two neighbours with nothing between them
  across SP  the seam falls after a run of blanks, between the characters either side of it

For each rule the table gives
  slots      positions of this shape inside the compared paragraphs
  word_at    how many of them Word's own exported PDF ends a line at
  ours_at    how many our capture ends a line at
  only_us    we cut, Word does not   -- what a forbidding rule can remove
  only_word  Word cuts, we do not    -- what an allowing rule would add (the rest are new risks)
A rule is SUPPORTED when word_at is far below ours_at: Word refuses a seam we offer, so the seam is
ours to remove. When the two sit near each other the class is legal for both engines and the
disagreement inside it is width, not a rule -- writing that rule moves a break sideways.

Both sides' offsets come from the same code path as the parity report (this imports
tools/break-agreement.py rather than matching paragraphs a second time) and stay in raw
document.xml offsets, so the blanks that DeviceCapture.clean and the stripped counters drop are visible.

Usage:
    py tools/break-rule-yield.py                       # default capture 267-release
    py tools/break-rule-yield.py -Capture artifacts/agent-layout-verify/<tag>
    py tools/break-rule-yield.py -LineBreak artifacts/uax14/ref/LineBreak.txt
"""
import argparse
import collections
import csv
import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SPACEISH = (" ", "\t", "\u3000", "\xa0")

# UAX#14 Line_Break, narrowed to what this document produces. Provenance: Unicode
# LineBreak.txt (15.1), read against icu4c/source/data/brkitr/rules/line.txt; the entries worth
# checking by hand are the ones a reader is most likely to guess wrong --
#   U+2103 DEGREE CELSIUS  PO   (not ID: it is a numeric postfix)
#   U+00B0 DEGREE SIGN     PO   U+0025 PERCENT SIGN  PO   U+2030  PO
#   U+FF0C / U+3001 / U+3002  CL (not IS: fullwidth comma, ideographic comma and full stop close)
#   U+FF1B U+FF1A NS   U+FF01 U+FF1F EX   U+FF08 U+300A OP   U+FF09 U+300B CL
#   U+00D7 MULTIPLICATION SIGN and U+00B7 are AI, which LB1 resolves to AL
PO = u"\u2103\u2104\u00b0\u0025\u2030\u2031\u00a2\u00a3\u00a5\u20ac\u0e3f\u300d\u00b1"
PR = u"\u0024\u00a2\u00a3\u00a5\u20ac\u0023\u002b\u2212\u00b1"
CL = u"\uFF0C\u3001\u3002\uFF09\u300B\uFF3D\u3011\u300F\u3015\u3019\uFF05\u0029\u005D\u007D\u3041"
CP = u"\uFF09\u0029\u005D\uFF3D"
IS = u"\u002E\u002C\u003A\u003B\u002F\uFF0E\uFF0C\uFF1A\uFF1B\uFF0F\u00B7"
NS = u"\uFF1B\uFF1A\u30FC\u3005\u30FB\uFF70\u2010\u2011\u30FB"
EX = u"\uFF01\uFF1F\u0021\u003F\u300C\u300D"
OP = u"\uFF08\u300A\uFF3B\u3014\uFF5B\u0028\u005B\u300C\u300E\u201C\u2018"
QU = u"\u201C\u201D\u2018\u2019\u00AB\u00BB\u300C\u300D\uFF02\u0022\u0027"
HY = u"\u002D\u2010\u2013"
BA = u"\u002F\u2014\u2015\u005C"
SY = u"\u0026\uFF20\u0040"
IN = u"\u2026\u22EF\u2025"


def load(name, mod):
    spec = importlib.util.spec_from_file_location(mod, os.path.join(HERE, name))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def build_class_of(path):
    """class_of(cp) from the authoritative LineBreak.txt, with the equated pairs resolved."""
    ranges, equated = [], {}
    for raw in open(path, encoding="utf-8"):
        s = raw.split("#")[0].strip()
        if not s or s.startswith("@"):
            continue
        if ";" in s:
            a, b = [x.strip() for x in s.split(";")]
            lo, hi = [int(x, 16) for x in a.split("..")] if ".." in a else (int(a, 16),) * 2
            ranges.append((lo, hi, b))
        elif ":" in s:
            a, b = [x.strip() for x in s.split(":")]
            equated.setdefault(a, b)
    lookup = {}

    def class_of(cp):
        if cp not in lookup:
            for lo, hi, b in ranges:
                if lo <= cp <= hi:
                    lookup[cp] = equated.get(b, b)
                    break
            else:
                lookup[cp] = "XX"
        return lookup[cp]
    return class_of


def class_of_builtin(c):
    o = ord(c)
    if c in SPACEISH:
        return "SP"
    if "0" <= c <= "9":
        return "NU"
    if ("A" <= c <= "Z") or ("a" <= c <= "z") or 0x0391 <= o <= 0x03c9 or 0x0410 <= o <= 0x044f:
        return "AL"
    if (0x2E80 <= o <= 0x9FFF and not (0x3000 <= o <= 0x303F)) or 0xF900 <= o <= 0xFAFF \
            or 0x4E00 <= o <= 0x9FFF or 0xFE30 <= o <= 0xFE4F or 0xFF66 <= o <= 0xFF9D or o >= 0x20000:
        return "ID"
    if 0x3041 <= o <= 0x30ff:
        return "ID"
    for name, group in (("PO", PO), ("PR", PR), ("CL", CL), ("CP", CP), ("IS", IS), ("NS", NS),
                        ("EX", EX), ("OP", OP), ("QU", QU), ("HY", HY), ("BA", BA), ("SY", SY),
                        ("IN", IN)):
        if c in group:
            return name
    if c in u"\u00d7\u00b7\u30fb":
        return "AL"                       # AI -> AL by LB1
    if o == 0x2014 or o == 0x2015:
        return "BA"
    if o in (0x3001, 0x3002, 0xFF0C):
        return "CL"
    if o in (0xFF1B, 0xFF1A):
        return "NS"
    if o in (0xFF08, 0x300A):
        return "OP"
    if o in (0xFF09, 0x300B):
        return "CL"
    if o in (0xFF01, 0xFF1F):
        return "EX"
    if o == 0x3000:
        return "B2"
    return "XX"


def neighbour(raw, k, step):
    """The character step-neighbouring the blanks around offset k (k is the offset after the blanks)."""
    i = k - 1 if step < 0 else k
    while 0 <= i < len(raw) and raw[i] in SPACEISH:
        i += step
    return raw[i] if 0 <= i < len(raw) else ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-Capture", default="artifacts/agent-layout-verify/267-release")
    ap.add_argument("-Pdf", default="artifacts/agent-typeset/pdf-truth/input-liu.pdf")
    ap.add_argument("-Docx", default="tests/samples/input-liu.docx")
    ap.add_argument("-SizePt", type=float, default=12.0)
    ap.add_argument("-LineBreak", default="", help="authoritative LineBreak.txt; default: built-in table")
    ap.add_argument("-Out", default="artifacts/uax14/break-rule-yield.tsv")
    ap.add_argument("-Min", type=int, default=1, help="hide rules cut fewer than this many times")
    a = ap.parse_args()
    lines = os.path.join(a.Capture, "new", "lines-all.tsv")
    for f in (lines, a.Pdf, a.Docx):
        if not os.path.exists(f):
            print("missing input: " + f)
            return 1
    class_of = build_class_of(a.LineBreak) if a.LineBreak else class_of_builtin
    ba = load("break-agreement.py", "ba_for_bry")
    paras = ba.source_paragraphs(a.Docx)
    word = ba.word_layout(a.Pdf, paras, a.SizePt)
    blocks = ba.phone_blocks(lines)
    pairs = ba.pair(blocks, word, paras)
    tally = collections.defaultdict(lambda: collections.Counter())
    where = collections.defaultdict(list)
    for blk, pi in pairs:
        w = word[pi]
        raw = w["raw"]
        wset = {ba.canon(raw, w["idx"][e - 1] + 1) for e in w["cuts"]}
        dset, acc = set(), 0
        for line in blk["lines"][:-1]:
            acc += line["chars"]
            dset.add(acc)
        dset_raw = {ba.canon(raw, w["idx"][x - 1] + 1) if x - 1 < len(w["idx"]) else len(raw)
                    for x in sorted(dset)}

        def note(kind, k):
            t = tally[kind]
            inw, ind = k in wset, k in dset_raw
            t["slots"] += 1
            t["word_at"] += inw
            t["ours_at"] += ind
            t["both"] += inw and ind
            t["only_us"] += ind and not inw
            t["only_word"] += inw and not ind
            if ind and not inw and len(where[kind]) < 5:
                where[kind].append("para %d  %s|%s" % (pi, raw[max(0, k - 9):k], raw[k:k + 9]))

        for k in range(1, len(raw)):
            left, right = raw[k - 1], raw[k]
            if left in SPACEISH or right in SPACEISH:
                if left in SPACEISH and right not in SPACEISH and k > 1:
                    left_far, right_far = neighbour(raw, k, -1), class_of(right)
                    if left_far and right_far != "SP":
                        note("%s SP %s  (across a blank)" % (class_of(left_far), right_far), k)
                continue
            note("%s x %s  (adjacent)" % (class_of(left), class_of(right)), k)
    rows, print_rows = [], []
    for kind, t in sorted(tally.items(), key=lambda kv: (-kv[1]["only_us"], -kv[1]["slots"])):
        if t["only_us"] + t["only_word"] < a.Min:
            continue
        rows.append(dict(rule=kind, **{k: t[k] for k in
                                       ("slots", "word_at", "ours_at", "both", "only_us", "only_word")}))
        print_rows.append((kind, t))
    print("capture=%s   paragraphs compared=%d   class source=%s"
          % (a.Capture, len(pairs), a.LineBreak or "built-in table (UAX#14)"))
    print("")
    print("%-30s %7s %8s %8s %6s %8s %9s" % ("rule shape", "slots", "word_at", "ours_at",
                                             "both", "only_us", "only_word"))
    for kind, t in print_rows:
        print("%-30s %7d %8d %8d %6d %8d %9d"
              % (kind, t["slots"], t["word_at"], t["ours_at"], t["both"],
                 t["only_us"], t["only_word"]))
    print("")
    print("What each row says about a rule that forbids the seam:")
    for kind, t in print_rows:
        if not t["only_us"]:
            continue
        verdict = ("Word refuses this seam (0 of %d slots): ours to remove" % t["slots"]
                   if t["word_at"] * 4 < t["ours_at"] else
                   "both engines cut here (%d vs %d): width, not a rule -- %d agreements would break"
                   % (t["word_at"], t["ours_at"], t["both"]))
        print("  %-30s %4d only_us  %s" % (kind, t["only_us"], verdict))
        for line in where[kind]:
            print("        %s" % line)
    if a.Out and rows:
        os.makedirs(os.path.dirname(a.Out), exist_ok=True)
        with open(a.Out, "w", newline="", encoding="utf-8") as fh:
            wr = csv.DictWriter(fh, fieldnames=list(rows[0].keys()), delimiter="\t")
            wr.writeheader()
            wr.writerows(rows)
        print("wrote " + a.Out)
    return 0


if __name__ == "__main__":
    sys.exit(main())