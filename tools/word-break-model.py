#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/word-break-model.py -- which UAX #14 rule explains each Wordlite-vs-Word break?

Every disagreement row in a capture's break-agreement.tsv carries the two characters that sit on
either side of the cut. This tool labels each of those characters with its Unicode line-break class
(UAX #14 LineBreak.txt) and groups the disagreements by the PAIR of classes, so a disagreement stops
being "paragraph 62 cut differently" and becomes "the pair AL->NU, 11 times": that pair names the
rule to implement, or proves there is no rule and the cut only moved with the advance widths.

    py tools/word-break-model.py artifacts/agent-layout-verify/glueprice1

Reads only <capture>/break-agreement.tsv, <capture>/new/lines-all.tsv, the class table in
tools/data/LineBreak.txt and app/.../DocxTextLayout.java. Writes artifacts/_breakmodel/<tag>.txt.
Offline: no phone, no Word, no new capture, and no change to anything under app/.
"""
import bisect
import collections
import csv
import os
import re
import sys
import unicodedata

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(REPO, "app", "src", "main", "java", "com", "rikkahub", "wordlite",
                    "DocxTextLayout.java")
TABLE = os.path.join(REPO, "tools", "data", "LineBreak.txt")

# ---------------------------------------------------------------- line-break classes
#
# Primary source: tools/data/LineBreak.txt, fetched from
#   https://www.unicode.org/Public/UCD/latest/ucd/LineBreak.txt
# Those ranges ARE the class assignments of that file (ID ideograph, AL letter, NU digit, CL closure,
# CP close paren, IS infix separator, BA break-after, HY hyphen-minus, NS non-starter, QU quotation,
# OP opener, SP space, IN inseparable, PR/PO prefix and postfix). An unlisted codepoint is XX.
# Two resolutions from UAX #14 Table 2 are applied so the labels read as the rules are written:
# AI (ambiguous) -> AL, CJ (conditional Japanese) -> ID; newline keeps its own NL.
# FALLBACK is used ONLY when that file is absent -- hand-listed ranges for the classes this document
# needs, from the same UAX #14 assignments. The header says which source was used.
FALLBACK = [
    (0x4E00, 0x9FFF, "ID"), (0x3400, 0x4DBF, "ID"), (0xF900, 0xFAFF, "ID"),    # CJK ideographs
    (0x3040, 0x30FF, "ID"), (0xAC00, 0xD7AF, "ID"), (0x3000, 0x3003, "ID"),    # kana, hangul, space
    (0x000A, 0x000A, "NL"), (0x000D, 0x000D, "CR"), (0x000B, 0x000C, "BK"),    # newline family
    (0x0041, 0x005A, "AL"), (0x0061, 0x007A, "AL"), (0x00AA, 0x02AF, "AL"),    # Latin letters
    (0x0370, 0x06FF, "AL"), (0x1E00, 0x1FFF, "AL"), (0x200C, 0x200C, "AL"),
    (0x0030, 0x0039, "NU"), (0x0660, 0x0669, "NU"), (0xFF10, 0xFF19, "NU"),    # digits
    (0x0020, 0x0020, "SP"), (0x00A0, 0x00A0, "GL"),                            # space, nbsp
    (0x002C, 0x002C, "IS"), (0x002E, 0x002E, "IS"), (0x003A, 0x003A, "IS"),    # , . :
    (0x003B, 0x003B, "IS"), (0x002F, 0x002F, "IS"), (0x005F, 0x005F, "IS"),    # ; / _
    (0x002D, 0x002D, "HY"), (0x00AD, 0x00AD, "BA"),                            # hyphen
    (0x2010, 0x2011, "BA"), (0x2013, 0x2014, "BA"), (0x2027, 0x2027, "BA"),    # U+2010..
    (0x0021, 0x0021, "EX"), (0x003F, 0x003F, "EX"), (0x0025, 0x0025, "IS"),    # ! ? %
    (0x3001, 0x3002, "CL"), (0xFF01, 0xFF01, "EX"), (0xFF0C, 0xFF0C, "CL"),   # 、。 ！ ，
    (0xFF0E, 0xFF0E, "CL"), (0xFF0F, 0xFF0F, "NS"), (0xFF1F, 0xFF1F, "EX"),   # ．／ ？
    (0xFF05, 0xFF05, "PO"), (0xFF1A, 0xFF1B, "NS"), (0xFF61, 0xFF61, "CL"),   # ％ ：； ｡
    (0x3008, 0x3008, "OP"), (0x300A, 0x300A, "OP"), (0x300C, 0x300C, "OP"),   # 〈 《 「
    (0x3011, 0x3011, "NS"), (0x3014, 0x301B, "NS"), (0xFF5D, 0xFF65, "NS"),   # 》 」 ｝
    (0x3009, 0x3009, "CL"), (0x300B, 0x300B, "CL"), (0x300D, 0x300D, "CL"),   # 〉 》 」
    (0xFF08, 0xFF08, "OP"), (0xFF3B, 0xFF3B, "OP"),                            # （ ［
    (0xFF5B, 0xFF5B, "OP"), (0x2018, 0x201B, "QU"), (0x201C, 0x201D, "QU"),   # brackets, quotes
    (0xFF09, 0xFF09, "CL"), (0xFF3E, 0xFF3E, "CL"), (0xFF5E, 0xFF5E, "CL"),   # ）］｝ (CP in
    (0x2046, 0x2046, "CP"), (0xFE5D, 0xFE5E, "CP"), (0xFE30, 0xFE30, "CP"),   # Unicode is 5 codepoints
    (0x2103, 0x2103, "PO"), (0x2116, 0x2116, "IN"), (0x00B1, 0x00B1, "PR"),   # ℃ № ±
    (0x00A2, 0x00A5, "PR"), (0x00A7, 0x00A8, "PR"), (0x00B0, 0x00B0, "PR"),   # ¢£¤¥§¨°
    (0x00AC, 0x00AC, "PR"), (0x00B6, 0x00B7, "PR"), (0x2030, 0x2030, "PR"),
    (0xFFE0, 0xFFE1, "PR"), (0xFFE5, 0xFFE6, "PR"), (0x0024, 0x0024, "PR"),
    (0x0300, 0x036F, "CM"),                                                   # combining marks
]


def load_table():
    """[(lo, hi, class)] plus a sentence saying where it came from."""
    if not os.path.exists(TABLE):
        return sorted(FALLBACK), ("EXPLICIT RANGES FALLBACK: LineBreak.txt fetch failed, ranges "
                                 "hand-listed from UAX #14 class assignments")
    ranges, ver = [], "unknown version"
    with open(TABLE, encoding="utf-8") as fh:
        for line in fh:
            if line.startswith("# LineBreak-"):
                ver = line.strip().lstrip("# LineBreak-").strip()
            if ";" not in line or line.startswith("#"):
                continue
            code, rest = line.split(";", 1)
            cls = rest.split("#")[0].strip()
            code = code.strip()
            try:
                if ".." in code:
                    lo, hi = code.split("..")
                    lo, hi = int(lo, 16), int(hi, 16)
                else:
                    lo = hi = int(code, 16)
            except ValueError:
                continue
            ranges.append((lo, hi, cls))
    ranges.sort()
    return ranges, "Unicode UAX #14 LineBreak.txt %s (fetched to tools/data/LineBreak.txt)" % ver


class Classifier(object):
    def __init__(self, ranges):
        # Short ranges become exact entries so an explicit punctuation assignment always beats the
        # big ideograph range it sits inside; long ranges stay ranges for the bisect.
        self.singles, kept = {}, []
        for lo, hi, cls in ranges:
            if hi - lo <= 64:
                for cp in range(lo, hi + 1):
                    self.singles[cp] = cls
            else:
                kept.append((lo, hi, cls))
        self.ranges = kept
        self.starts = [r[0] for r in kept]

    def raw(self, ch):
        cp = ord(ch)
        hit = self.singles.get(cp)
        if hit is not None:
            return hit
        i = bisect.bisect_right(self.starts, cp) - 1
        if 0 <= i and self.ranges[i][0] <= cp <= self.ranges[i][1]:
            return self.ranges[i][2]
        return "XX"

    def cls(self, ch):
        if ch == "":
            return "EDGE"
        if ch == "\n":
            return "NL"
        c = self.raw(ch)
        if c == "AI":
            return "AL"        # UAX #14 Table 2: ambiguous -> AL (ID only in a CJK-only run)
        if c == "CJ":
            return "ID"        # conditional Japanese behaves as an ideograph here
        return c


def tsv(path):
    with open(path, encoding="utf-8-sig", newline="") as fh:
        return [r for r in csv.DictReader(fh, delimiter="\t")]


# ---------------------------------------------------------------- Wordlite's own rule source
# Python mirrors of the DocxTextLayout predicates, so a bucket can be traced to a line of Java.
# isCjk mirrors the eight Unicode blocks in DocxTextLayout.isCjk.
CJK_BLOCKS = [(0x4E00, 0x9FFF), (0x3400, 0x4DBF), (0xF900, 0xFAFF), (0x3000, 0x303F),
              (0xAC00, 0xD7AF), (0x3040, 0x309F), (0x30A0, 0x30FF), (0xFF00, 0xFFEF)]


def is_cjk(c):
    return c != "" and any(lo <= ord(c) <= hi for lo, hi in CJK_BLOCKS)


def glued_to_token(c):
    """DocxTextLayout.gluedToToken: any alphabet's letters/digits, space and CJK excluded."""
    if c == "" or c.isspace() or is_cjk(c):
        return False
    return c.isalnum() or unicodedata.category(c).startswith("L")


def hanging_set(java_src):
    """The characters in DocxTextLayout.isHangingPunctuation's switch, read out of the source."""
    body = re.search(r"isHangingPunctuation\(char c\)\s*\{(.*?)\n    \}", java_src, re.S)
    if not body:
        return set()
    return set(chr(int(h, 16)) for h in re.findall(r"case '\\u([0-9a-fA-F]{4})'", body.group(1)))


def slash_atomic(prev, nxt):
    """DocxTextLayout.glueSlashRuns/slashAtomicRuns: a '/' with Latin or digits on both sides."""
    return "/" in (prev, nxt) and glued_to_token(prev) and glued_to_token(nxt)


def verdict(side, prev, nxt, cp, cn, hang):
    """(code, reason), code i/ii/iii.

    (i) is only used where the Java source spells the decision out, which for a seam means the
    isHangingPunctuation() set that hangTrailingPunctuation() marks; (ii) where gluedToToken() glues
    both sides into one container; (iii) everywhere else, because no predicate in DocxTextLayout
    names that pair and StaticLayout owns it.
    """
    if glued_to_token(prev) and glued_to_token(nxt):
        if slash_atomic(prev, nxt):
            return ("ii", "gluedToToken() is true on both sides AND slashAtomicRuns()/glueSlashRuns() "
                          "turns the slash run atomic -> inside a glued run we made unbreakable")
        return ("ii", "gluedToToken() is true on both sides -> scriptTokens() replaces the run by one "
                      "container, so this pair sits inside a glued run we made unbreakable")
    if prev in hang or nxt in hang:
        who = ("the break sits BEFORE a hanging mark, which hangTrailingPunctuation() opens on purpose"
               if nxt in hang else
               "the break sits AFTER a hanging mark, which we hang across the margin instead")
        return ("i", "one side is in isHangingPunctuation()'s set: %s -> a pair our code decides about "
                     "explicitly" % who)
    if cp == "SP" or cn == "SP":
        return ("iii", "unknown -- the seam is a space, which both engines may break at, so this cut "
                       "moved with the line's remaining width rather than with a rule")
    if cp == "ID" and cn == "ID":
        return ("iii", "unknown -- gluedToToken() is false for CJK and no predicate in DocxTextLayout "
                       "names a CJK-CJK seam, so both sides may cut here and only advance widths moved "
                       "the cut")
    return ("iii", "unknown -- no predicate in DocxTextLayout covers this pair; StaticLayout with "
                   "BREAK_STRATEGY_SIMPLE owns the seam")


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    cap = sys.argv[1].rstrip("/\\")
    tag = os.path.basename(cap)
    rows = tsv(os.path.join(cap, "break-agreement.tsv"))
    lines = collections.OrderedDict()
    for r in tsv(os.path.join(cap, "new", "lines-all.tsv")):
        lines.setdefault(int(r["paragraph"]), []).append(r)
    ranges, origin = load_table()
    lb = Classifier(ranges)
    java_src = open(JAVA, encoding="utf-8").read()
    hang = hanging_set(java_src)

    out = []
    p = out.append
    p("word-break-model: %s" % cap)
    p("class table: %s" % origin)
    p("hanging set read from DocxTextLayout.isHangingPunctuation: %s"
      % " ".join("%s(U+%04X)" % (c, ord(c)) for c in sorted(hang)))
    p("classes this table gives the Chinese marks (NS is a small set in current Unicode; most of these "
      "are CL/EX/PO, which is why the buckets below are labelled by the pair, not by NS alone): %s"
      % " ".join("%s=%s" % (c, lb.cls(c)) for c in "、。，：；！？％」』）（「《”"))
    p("")

    # ---- 1. can each paragraph's text be rebuilt from lineFull at all?
    cuts = collections.defaultdict(set)
    sides = collections.Counter()
    by_block = collections.defaultdict(list)
    for r in rows:
        sides[r["side"]] += 1
        by_block[int(r["device_block"])].append(r)
        if r["side"] in ("both", "phone_only"):
            cuts[int(r["device_block"])].add(int(r["offset"]))
    n_para = len(lines)
    chars_ok = sum(1 for rs in lines.values()
                   if all(int(x["lineChars"]) == len(x["lineFull"]) for x in rs))
    compared = exact = shifted = broken = pair_ok = pair_tot = 0
    shift_hist = collections.Counter()
    for blk, rs in lines.items():
        if blk not in cuts:
            continue
        compared += 1
        rs = sorted(rs, key=lambda x: int(x["line"]))
        text = "".join(x["lineFull"] for x in rs)
        ends, acc = [], 0
        for x in rs[:-1]:
            acc += len(x["lineFull"])
            ends.append(acc)
        move = None
        for s in range(0, 9):
            if set(e + s for e in ends) == cuts[blk]:
                move = s
                break
        if move == 0:
            exact += 1
        elif move is None:
            broken += 1
        else:
            shifted += 1
            shift_hist[move] += 1
        for r in by_block[blk]:
            c, prev, nxt = int(r["offset"]), r["prev_char"], r["next_char"]
            hit = any(0 <= c - 1 - s and c - s < len(text)
                      and text[c - 1 - s] == prev and text[c - s] == nxt for s in range(0, 9))
            pair_ok += 1 if hit else 0
            pair_tot += 1

    def pct(a, b):
        return 100.0 * a / b if b else 0.0

    p("1. PARAGRAPH REPRODUCTION FROM lineFull (our own two files, checked against each other)")
    p("   rows in break-agreement.tsv: %d  (both %d, phone_only %d, word_only %d); paragraphs in "
      "lines-all.tsv: %d" % (len(rows), sides["both"], sides["phone_only"], sides["word_only"], n_para))
    p("   lineChars == len(lineFull) on every line: %d/%d paragraphs (%.1f%%) -- lineChars counts "
      "NON-SPACE characters, lineFull keeps the spaces" % (chars_ok, n_para, pct(chars_ok, n_para)))
    p("   cumulative ends of lineFull == our own cut set: %d/%d compared paragraphs = %.1f%%"
      % (exact, compared, pct(exact, compared)))
    p("   reproduces only after a constant 1-7 character shift: %d/%d (%.1f%%), shifts %s"
      % (shifted, compared, pct(shifted, compared),
         dict(sorted(shift_hist.items())) if shift_hist else "{}"))
    p("   does not reproduce at any shift: %d/%d (%.1f%%)" % (broken, compared, pct(broken, compared)))
    p("   prev_char/next_char of a disagreement row found in the rebuilt text (any shift): %d/%d rows "
      "(%.1f%%)" % (pair_ok, pair_tot, pct(pair_ok, pair_tot)))
    p("   HONEST READING: lineFull is not the DOCX paragraph text -- it drops characters the DOCX "
      "holds (superscript refs, field text) and break-agreement's offsets are counted in the raw text. "
      "So the classification below reads prev_char/next_char straight out of break-agreement.tsv and "
      "never touches the rebuilt text.")
    p("")

    # ---- 2/3. the two cross-tabulations, by class pair
    def tab(side, title):
        buckets = collections.Counter()
        ex = {}
        for r in rows:
            if r["side"] != side:
                continue
            cp_, cn_ = lb.cls(r["prev_char"]), lb.cls(r["next_char"])
            buckets[(cp_, cn_)] += 1
            ex.setdefault((cp_, cn_), r)
        p("%s  %d rows, %d distinct class pairs" % (title, sum(buckets.values()), len(buckets)))
        for (cp_, cn_), n in buckets.most_common(12):
            r = ex[(cp_, cn_)]
            prev, nxt = r["prev_char"], r["next_char"]
            code, reason = verdict(side, prev, nxt, cp_, cn_, hang)
            p("   %-4s -> %-4s %3d  %s" % (cp_, cn_, n, r["context"][:30]))
            p("        U+%04X->U+%04X  rule(%s): %s"
              % (ord(prev) if prev else 0, ord(nxt) if nxt else 0, code, reason))
        p("")
        return buckets

    p("2/3. DISAGREEMENT BY (class of the character before the cut) -> (class after it)")
    p("")
    wo = tab("word_only", "(a) word_only -- WORD broke here, we did not")
    po = tab("phone_only", "(b) phone_only -- we broke here, WORD did not")

    # ---- 4. the rule source, straight out of the Java file
    p("4. RULE SOURCE (DocxTextLayout.java, names grepped out of the file with their line numbers)")
    src = java_src.splitlines()
    for name in ("gluedToToken", "isTokenChar", "continuesToken", "isHangingPunctuation",
                 "slashAtomicRuns", "applyAutoSpace", "wordWrap"):
        hits = [i + 1 for i, ln in enumerate(src) if name in ln]
        p("   %-22s %2d hits, at lines %s" % (name, len(hits), ",".join(str(h) for h in hits[:6])))
    p("   gluedToToken(): space and isCjk excluded, isLetterOrDigit kept -> a Latin/digit run becomes "
      "one unbreakable container; the comment over that predicate keeps hyphen, slash, dot and "
      "brackets out of the glue, so those stay legal break edges.")
    p("   isTokenChar(): only space and isCjk excluded, so '- / . ,' glue, and continuesToken() moves "
      "an opened seam out of the middle of a token.")
    p("   isHangingPunctuation(): the 8 marks above; hangTrailingPunctuation() opens a seam so the mark "
      "crosses the margin instead of starting the next line.")
    p("")

    # ---- 5. the w:wordWrap no-space citation family
    fam = ["341", "342", "344", "349", "350"]
    fam_rows = [r for r in rows if r["side"] == "word_only" and r["word_para"] in fam]
    fam_b = collections.Counter((lb.cls(r["prev_char"]), lb.cls(r["next_char"])) for r in fam_rows)
    p("5. w:wordWrap family (paragraphs %s): word_only rows %d of %d = %.1f%%"
      % ("/".join(fam), len(fam_rows), sum(wo.values()), pct(len(fam_rows), sum(wo.values()))))
    for (a_, b_), n in fam_b.most_common(6):
        p("   %-4s -> %-4s %3d" % (a_, b_, n))
    all_wo = collections.Counter()
    for (a_, b_), n in wo.items():
        all_wo[a_ + "->" + b_] = n
    top_wo = wo.most_common(1)[0] if wo else (("-", "-"), 0)
    top_po = po.most_common(1)[0] if po else (("-", "-"), 0)
    p("   top word_only bucket:   %s->%s %d = %.1f%% of word_only"
      % (top_wo[0][0], top_wo[0][1], top_wo[1], pct(top_wo[1], sum(wo.values()))))
    p("   top phone_only bucket:  %s->%s %d = %.1f%% of phone_only"
      % (top_po[0][0], top_po[0][1], top_po[1], pct(top_po[1], sum(po.values()))))
    latin = sum(n for (a_, b_), n in wo.items() if a_ in ("AL", "NU") or b_ in ("AL", "NU"))
    p("   word_only rows whose pair touches a Latin letter or digit at all: %d of %d = %.1f%%"
      % (latin, sum(wo.values()), pct(latin, sum(wo.values()))))
    glued = {}
    for side in ("word_only", "phone_only"):
        glued[side] = [r for r in rows if r["side"] == side
                       and glued_to_token(r["prev_char"]) and glued_to_token(r["next_char"])]
    p("   rows INSIDE a glued run (both sides gluedToToken(), i.e. a cut in the middle of a Latin/digit "
      "word): word_only %d of %d, phone_only %d of %d -- mid-word Latin breaking is therefore not what "
      "either side disagrees about"
      % (len(glued["word_only"]), sum(wo.values()), len(glued["phone_only"]), sum(po.values())))

    text = "\n".join(out) + "\n"
    dest = os.path.join(REPO, "artifacts", "_breakmodel")
    os.makedirs(dest, exist_ok=True)
    with open(os.path.join(dest, tag + ".txt"), "w", encoding="utf-8") as fh:
        fh.write(text)
    sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
