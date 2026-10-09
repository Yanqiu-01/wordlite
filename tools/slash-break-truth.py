#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/slash-break-truth.py -- does Word ever cut at "/" when it had the room to?

The acceptance count (item 7) says we put 3 line ends after a "/" and Word put 0, out of 59 "/"
occurrences inside Word's compared lines. Counting absences proves nothing: if no "/" ever sat where a
break was due, Word had nothing to decide. This tool prices each line instead, with the engine's own
arithmetic, and asks the one question that decides the rule:

    free   = the room the line still had = (Word's right margin - the line's own left edge) * 4/3
             - what the line already costs us (whole-px hmtx advances + one quarter-em autoSpace seam
             at each CJK/Latin or CJK/digit run boundary -- DocxTextLayout.applyAutoSpace/AutoGap,
             the same model tools/width-bill.py bills with)
    cut    = what Word's NEXT line starts with, priced twice:
             cost_slash = up to and including the first "/"  (the break-after-slash model)
             cost_token = up to the end of the whole "/"-joined run (the glued model)

A line where cost_slash <= free < cost_token is a decision Word actually made: it had room for the text
up to the slash and refused it. Count those and the rule is settled; zero of them means the document
cannot settle it and the change would be a guess.

The line's own left edge comes from the PDF (x0 of its first drawn character), so an indented first
line is priced against the width it really had. Justification stretch is ignored on purpose: it is not
room, it is what Word added on top of the natural width to fill the line.

The second half of the output answers a different question, one the pricing above cannot: when a token
holds BOTH the disputed character and a "-" ("Cu/SB/P-Cu/SB/Cu"), does Word carry the whole thing down,
or does it cut at the internal "-"? Every such token is looked for in Word's own lines -- whole inside
one line, at the head of one (carried down), or split across two lines on the same page.

Usage:
    py tools/slash-break-truth.py
    py tools/slash-break-truth.py -Round 0        # float advances instead of whole px
    py tools/slash-break-truth.py -Char / -Top 12
    py tools/slash-break-truth.py -Tokens 0       # skip the whole-token table
"""
import argparse
import collections
import importlib.util
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SPACE = (" ", "\t", "\u3000", "\xa0")


def load(name, mod):
    spec = importlib.util.spec_from_file_location(mod, os.path.join(HERE, name))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-Pdf", default="artifacts/agent-typeset/pdf-truth/input-liu.pdf")
    ap.add_argument("-Docx", default="tests/samples/input-liu.docx")
    ap.add_argument("-SizePt", type=float, default=12.0)
    ap.add_argument("-Round", type=int, default=1)
    ap.add_argument("-Char", default="/", help="the character whose breakability is in question")
    ap.add_argument("-Top", type=int, default=12)
    ap.add_argument("-Tokens", type=int, default=1,
                    help="1 = also print the whole-token table below")
    a = ap.parse_args()
    wb = load("width-bill.py", "wb_for_slash")
    ba = load("break-agreement.py", "ba_for_slash")
    paras = ba.source_paragraphs(a.Docx)
    wl = ba.word_layout(a.Pdf, paras, a.SizePt)
    RIGHT = (11906 - 1701) * 72.0 / 1440.0          # 510.25 pt, the body right margin
    em_px = a.SizePt * 4.0 / 3.0
    round_adv = bool(a.Round)

    def adv(c, face, size):
        px = wb.advance_px(c, face, size)
        return math.floor(px + 0.5) if round_adv else px

    def tok_end(chars, i):
        """End of the run chars[i] belongs to: letters/digits, joined across the disputed character."""
        j = i
        while j < len(chars) and (chars[j][0].isalnum() or chars[j][0] == a.Char):
            j += 1
        return j

    def cost(chars, stop):
        """What the engine would bill for chars[0:stop], with a seam in front if the CJK seam is there."""
        total = sum(adv(c, f, s) for c, _x, s, f in chars[:stop])
        return total

    # Price the lines from the PDF's own drawn characters INCLUDING blanks: tools/break-agreement.py
    # strips blanks to match lines to paragraphs, and a Latin reference line without its blanks bills
    # 4 px light per word, which invents room that is not there.
    rows = []
    stats = collections.Counter()
    raw_lines = wb.load_sibling().read_lines(a.Pdf)
    ink_of = lambda line: "".join(c[0] for c in line["chars"] if c[0] not in SPACE)
    cursor = 0
    for pi in sorted(wl):
        run = wl[pi]["lines"]
        for li in range(len(run) - 1):
            lines_here = []
            for which in (li, li + 1):
                want = ink_of(run[which])
                while cursor < len(raw_lines) and not (raw_lines[cursor]["page"] == run[which]["page"]
                                                       and ink_of(raw_lines[cursor]).startswith(want[:12])):
                    cursor += 1
                lines_here.append([c for c in raw_lines[cursor]["chars"]] if cursor < len(raw_lines)
                                  else [c for c in run[which]["chars"] if c[0] not in SPACE])
                cursor += 1
            cur, nxt = lines_here[0], lines_here[1]
            while cur and cur[-1][0] in SPACE: cur.pop()      # a blank past the last ink hangs the line
            while nxt and nxt[0][0] in SPACE: nxt.pop(0)      # and never starts one
            if not cur or not nxt: continue
            bill = 0.0
            for i, (c, x, s, f) in enumerate(cur):
                unit = adv(c, f, s)
                seams = (1 if i and wb.seam(cur[i - 1][0], c) else 0) \
                    + (1 if i + 1 < len(cur) and wb.seam(c, cur[i + 1][0]) else 0)
                bill += math.ceil(unit + seams * em_px * 0.25) if seams else unit
            free = (RIGHT - [c for c in cur if c[0] not in SPACE][0][1]) * 4.0 / 3.0 - bill
            if a.Char not in "".join(c[0] for c in nxt):
                continue                     # only lines whose next line contains the disputed char
            # the piece of the next line up to the first "/" and up to the end of its run
            k = next((i for i, c in enumerate(nxt) if c[0] == a.Char), None)
            if k is None or k == 0:
                continue                     # nothing in front of the character on that line to price
            # The rule under test is about a run like "Cu/SB": alnum on BOTH sides of the character.
            # "（IMCs）/Cu" is a different animal (the character sits against punctuation) and pricing it
            # as a run would put a 0-width "run" on the books.
            before, after = nxt[k - 1][0], nxt[k + 1][0] if k + 1 < len(nxt) else " "
            if not (before.isalnum() and after.isalnum()):
                stats["skipped: %r not between two letters/digits" % a.Char] += 1
                continue
            c_slash = cost(nxt, k + 1)
            c_token = cost(nxt, tok_end(nxt, 0)) if nxt[0][0].isalnum() else \
                cost(nxt, tok_end(nxt, k))
            seam_in_front = em_px * 0.25 if wb.seam(cur[-1][0], nxt[0][0]) and wb.is_cjk(nxt[0][0]) else 0.0
            c_slash += seam_in_front
            c_token += seam_in_front
            stats["lines whose next line holds a %r" % a.Char] += 1
            if c_slash <= free and c_token > free:
                stats["DECISIVE: room for the text up to the slash, not for the whole run"] += 1
                rows.append(("decisive", pi, run[li]["page"], li + 1, free, c_slash, c_token,
                             "".join(c[0] for c in cur)[-10:], "".join(c[0] for c in nxt)[:16]))
            elif c_token <= free:
                stats["no evidence: the whole run would have fit too"] += 1
                rows.append(("no-evidence", pi, run[li]["page"], li + 1, free, c_slash, c_token,
                             "".join(c[0] for c in cur)[-10:], "".join(c[0] for c in nxt)[:16]))
            else:
                stats["no room even for the text up to the slash (Word had nothing to decide)"] += 1
    print("pricing model: whole-px hmtx advances + quarter-em autoSpace seams"
          if round_adv else "pricing model: float hmtx advances + quarter-em autoSpace seams")
    print("right margin %.2f pt; each line priced against its OWN left edge from the PDF" % RIGHT)
    for k, v in stats.items():
        print("  %-64s %d" % (k, v))
    print("")
    for want in ("decisive", "no-evidence"):
        sel = [r for r in rows if r[0] == want]
        print("%s lines (%d), first %d:" % (want, len(sel), min(len(sel), a.Top)))
        for tag, pi, pg, li, free, c1, c2, tail, head in sel[:a.Top]:
            print("   para %3d p%-2s line %-2d  free %7.2f px | to-slash %7.2f | whole-run %7.2f | ...%s | %s..."
                  % (pi, pg, li, free, c1, c2, tail, head))
        print("")

    if a.Tokens:
        import re
        token_of = re.compile(r"[A-Za-z0-9][A-Za-z0-9/\-]*[A-Za-z0-9]")
        page_lines = [(ln["page"], "".join(c[0] for c in ln["chars"] if c[0] not in SPACE))
                      for ln in raw_lines]
        # Tokens are read out of the DOCUMENT, one paragraph at a time. Joining the PDF's lines instead
        # would weld a reference entry onto the entry after it and invent a token nobody typed.
        counts = collections.Counter()
        for entry in paras:
            text = entry["s"]
            for t in token_of.findall(text):
                if a.Char in t and "-" in t:
                    counts[t] += 1
        whole = head = occ = sighted = 0
        splits = []
        print("tokens that hold both %r and a hyphen -- does Word keep the whole thing on one line?" % a.Char)
        print("(a token wider than the column cannot be kept, so its line says the opposite: see the")
        print(" width guard in DocxTextLayout.slashAtomicRuns)")
        for t in sorted(counts):
            occ += counts[t]
            lines_with = [i for i, (_p, s2) in enumerate(page_lines) if t in s2]
            heads = sum(1 for i in lines_with if page_lines[i][1].startswith(t))
            whole += len(lines_with)
            head += heads
            sighted += min(len(lines_with), counts[t])
            for k in range(1, len(t)):
                for i in range(len(page_lines) - 1):
                    if page_lines[i][0] != page_lines[i + 1][0]:
                        continue                     # a line pair only counts inside one page
                    s_a, s_b = page_lines[i][1], page_lines[i + 1][1]
                    if s_a.endswith(t[:k]) and s_b.startswith(t[k:]):
                        cuts_at = "%s|%s" % (t[k - 1], t[k])
                        splits.append((cuts_at, s_a[-14:], s_b[:14]))
            print("   %-24s x%-2d in the document | whole inside %d Word line(s), %d of them at a line head"
                  % (t[:24], counts[t], len(lines_with), heads))
        print("   total: %d occurrences; %d sightings whole; %d at a line head; %d split across a line"
              % (occ, sighted, head, len(splits)))
        for cuts_at, s_a, s_b in splits[:a.Top]:
            print("   SPLIT (%s)  ...%s | %s..." % (cuts_at, s_a, s_b))
    return 0


if __name__ == "__main__":
    sys.exit(main())


