#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/break-class-truth.py -- which character pairs does Word actually break a line at?

Word truth, no guessing: every consecutive pair of Word-PDF lines inside one paragraph of
tests/samples/input-liu.docx is matched back to the source, so the break is read at the source offset
(where the blanks are still there) and classified by the characters on both sides. The table below is
the rule list the engine has to reproduce; the counts are how often Word took each opportunity.

  py tools/break-class-truth.py -PdfLines artifacts/agent-layout-verify/pdf-lines.tsv
"""
import argparse, collections, re, sys, zipfile

TAG = re.compile(r"<[^>]+>")
def paragraphs(path):
    xml = zipfile.ZipFile(path).read("word/document.xml").decode("utf8")
    out = []
    for p in TAG.sub("", re.sub(r"</w:p>", "\n", xml)).split("\n"):
        p = p.replace("\t", " ")
        if p.strip(): out.append(p)
    return out

def ns(s):
    keep, idx = [], []
    for i, c in enumerate(s):
        if not c.isspace(): keep.append(c); idx.append(i)
    return "".join(keep), idx

def kind(c):
    o = ord(c)
    if 0x2E80 <= o <= 0x9FFF or 0xF900 <= o <= 0xFAFF or 0xFF00 <= o <= 0xFF60 or 0x3000 <= o <= 0x303F: return "cjk"
    if c.isalpha(): return "lat"
    if c.isdigit(): return "num"
    return "sym"

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-PdfLines", default="artifacts/agent-layout-verify/pdf-lines.tsv")
    ap.add_argument("-Docx", default="tests/samples/input-liu.docx")
    ap.add_argument("-Show", type=int, default=40)
    a = ap.parse_args()
    paras = []
    for p in paragraphs(a.Docx):
        n, idx = ns(p)
        paras.append((p, n, idx))
    rows = []
    for line in open(a.PdfLines, encoding="utf8"):
        if line.startswith("#") or line.startswith("page\t"): continue
        rows.append(line.rstrip("\n").split("\t"))
    counts = collections.Counter(); pairs = collections.Counter(); matched = unmatched = 0
    for i in range(len(rows) - 1):
        cur, nxt = rows[i], rows[i + 1]
        if cur[0] != nxt[0]: continue
        a1, _b1 = ns(cur[9]); a2, _b2 = ns(nxt[9])
        if not a1 or not a2: continue
        window = a1[-8:] + a2[:8]
        hit = False
        for (src, n, idx) in paras:
            at = n.find(window)
            if at >= 0: hit = True
            while at >= 0:
                cut = at + min(8, len(a1))
                if cut > 0 and cut < len(n):
                    si = idx[cut - 1]; sj = idx[cut]
                    left, right = src[si], src[sj]
                    gap = src[si + 1:sj]
                    matched += 1
                    if (left.isalpha() or left.isdigit()) and (right.isalpha() or right.isdigit()) \
                       and kind(left) != "cjk" and kind(right) != "cjk" and not gap.strip():
                        cls = "CUT-mid-token"
                    elif kind(left) == "cjk" and kind(right) == "cjk": cls = "cjk|cjk"
                    elif kind(left) == "cjk": cls = "cjk|" + kind(right)
                    elif kind(right) == "cjk": cls = kind(left) + "|cjk"
                    elif gap.strip(): cls = "at-space"
                    else: cls = "other"
                    counts[cls] += 1
                    if cls in ("CUT-mid-token", "other", "at-space"):
                        pairs["%s|%s %s %r" % (left, right, cls, gap)] += 1
                at = n.find(window, at + 1)
            if hit: break
        if not hit: unmatched += 1
    print("word_lines=%d matched_breaks=%d unmatched_pairs=%d" % (len(rows), matched, unmatched))
    for cls, n in counts.most_common(): print("  %-16s %d" % (cls, n))
    print("detail (other / cut / at-space):")
    for p, n in pairs.most_common(a.Show): print("  %-28s %d" % (p, n))

main()

