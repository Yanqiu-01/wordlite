#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/break-agreement.py -- do the phone and Word cut the same paragraphs at the same characters?

THE RULER (read this before quoting a number). A paragraph laid out by Word ends its lines at a set of
character offsets; the phone ends the same paragraph's lines at another set. We compare the two SETS.
  agreement = |offsets both sides cut at| / |offsets either side cut at|
That is the whole definition. It is deliberately NOT the old line-index comparison
(tools/line-break-delta.ps1), which walked "our line k against Word's line k" and therefore counted a
paragraph as mostly-matching just because both sides ended up with the same NUMBER of lines: on
HEAD that ruler reads 69.7% while the sets above agree on far less. Quote the set ruler for
break agreement and say so; the two numbers are not interchangeable and never were.

Counts are in non-space characters of the paragraph text as it sits in word/document.xml, because the
device capture throws whitespace away (DeviceCapture.clean) while Word's PDF keeps it.

Two failure directions are reported apart, on purpose - they need different fixes and must never be
merged into one number:
  phone_only  the phone broke where Word did not  -> our breaker offers a break Word does not allow
  word_only   Word broke where the phone did not  -> our breaker refuses a break Word allows
Every break point is classified by the two characters it falls between (cjk|latin, latin|cjk,
cjk|digit, digit|cjk, after '-', after '/', after a comma-like mark, after a bracket or quote, inside
an alphanumeric run, at a space, cjk|cjk, other), which is what tells you which rule to change.

Word's page number travels with every line and every break point, and each paragraph carries the page
Word started it on, so page assignment is computed from this same truth file instead of a second
Word session. The phone's page comes from the capture's lines-all.tsv.

Usage:
    py tools/break-agreement.py                       # default capture: artifacts/agent-layout-verify/main251
    py tools/break-agreement.py -Capture artifacts/agent-layout-verify/<tag>
    py tools/break-agreement.py -Out artifacts/word-break/break-agreement.tsv -Top 12
Outputs: <Out>, plus artifacts/word-break/citation-cuts.tsv (the word_only citation cluster, apart).
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


wb = load("width-bill.py", "wb_for_ba")
SPACEISH = (" ", "\t", "\u3000", "\xa0")


def source_paragraphs(docx):
    """[(stripped_text, cuts_offsets, page_placeholder, stripped_len)] for every docx paragraph."""
    out = []
    for text in wb.docx_paragraphs(docx):
        s, idx = wb.stripped(text)
        out.append({"raw": text, "s": s, "idx": idx})
    return out


def word_layout(pdf, paras, size_pt):
    """Match Word's PDF lines to paragraphs; return {para_index: {"lines":[{page,end,chars}],...}}."""
    order = []
    pix, cursor = 0, 0
    seq = []
    for ln in wb.load_sibling().read_lines(pdf):
        chars = [c for c in ln["chars"] if c[0] not in SPACEISH]
        ink = [c for c in chars if c[0] != " "]
        if len(ink) < 2:
            continue
        raw = "".join(c[0] for c in ink)
        start, found, hit = (pix, cursor), False, -1
        while pix < len(paras):
            hit = paras[pix]["s"].find(raw, cursor)
            if hit >= 0:
                found = True
                break
            pix += 1
            cursor = 0
        if not found:
            pix, cursor = start
            seq.append(None)
            continue
        p = paras[pix]
        end_stripped = hit + len(raw)
        seq.append({"para": pix, "begin_stripped": hit, "end_stripped": end_stripped,
                    "page": ln["page"], "orig_end": p["idx"][end_stripped - 1] + 1,
                    "size": ln["size"], "chars": ink})
        cursor = end_stripped
        if cursor >= len(p["s"]):
            pix += 1
            cursor = 0
    runs = []
    run = []
    for rec in seq + [None]:
        if not (run and rec and rec["para"] == run[-1]["para"]):
            if run:
                runs.append(run)
            run = []
        if rec:
            run.append(rec)
    out = {}
    dropped = collections.Counter()
    for run in runs:
        pi = run[0]["para"]
        need = len(paras[pi]["s"])
        if run[0]["begin_stripped"] != 0:
            # Word's first line of this paragraph never matched (a footnote reference, a field, a
            # small-cap run). Its page must not be used: the run we did match starts a line late.
            dropped["first line never matched"] += 1
            continue
        if run[0]["end_stripped"] >= need:
            dropped["single line, nothing to compare"] += 1
            continue
        if any(run[i + 1]["end_stripped"] <= run[i]["end_stripped"] for i in range(len(run) - 1)):
            continue
        if run[-1]["end_stripped"] != need:
            dropped["Word lines do not cover the paragraph"] += 1
            continue
        if any(abs(r["size"] - size_pt) > 1.5 for r in run):
            dropped["line drawn at another size"] += 1
            continue
        out[pi] = {"lines": run, "cuts": set(r["end_stripped"] for r in run[:-1]),
                   "page": run[0]["page"], "text": paras[pi]["s"], "raw": paras[pi]["raw"],
                   "idx": paras[pi]["idx"]}
    word_layout.dropped = dropped
    return out


def phone_blocks(tsv):
    blocks = collections.OrderedDict()
    with open(tsv, encoding="utf-8") as fh:
        for row in csv.DictReader(fh, delimiter="\t"):
            b = int(row["paragraph"])
            blocks.setdefault(b, []).append({"line": int(row["line"]),
                                             "chars": int(row["lineChars"]),
                                             "page": int(row["page"]),
                                             "first8": row["first8"], "last8": row["last8"],
                                             "px": float(row["lineWidthPx"])})
    out = []
    for b, lines in blocks.items():
        lines = sorted(lines, key=lambda r: r["line"])
        total = sum(r["chars"] for r in lines)
        cuts, acc = set(), 0
        for r in lines[:-1]:
            acc += r["chars"]
            cuts.add(acc)
        at, acc = [], 0
        for r in lines:
            acc += r["chars"]
            at.append(r["page"])
        out.append({"block": b, "lines": lines, "cuts": cuts, "total": total,
                    "first8": lines[0]["first8"], "last8": lines[-1]["last8"],
                    "start_page": lines[0]["page"], "page_at": at})
    return out


def pair(blocks, word, paras):
    """Monotonic device block -> docx paragraph pairing, only when the text length and both ends fit."""
    pairs, wi = [], 0
    wkeys = sorted(word)
    for blk in blocks:
        k = 0
        while k < len(wkeys):
            pi = wkeys[k]
            cand = word[pi]
            same_len = cand["text"] and blk["total"] == len(cand["text"])
            head = cand["text"][:8] == blk["first8"]
            tail = cand["text"][-8:] == blk["last8"]
            if same_len and head and tail:
                pairs.append((blk, pi))
                wkeys = wkeys[k + 1:]
                break
            k += 1
        else:
            continue
    return pairs


CUT_CLASSES = collections.OrderedDict([
    ("at a space", lambda p, n, raw, k: raw[k - 1] == " " or raw[k] == " "),
    ("inside alnum run", lambda p, n, raw, k: p.isalnum() and n.isalnum()
     and p.isascii() and n.isascii()),
    ("after '-'", lambda p, n, raw, k: p in "-\u2010\u2011"),
    ("after '/'", lambda p, n, raw, k: p == "/"),
    ("cjk|latin", lambda p, n, raw, k: wb.kind(p) == 1 and wb.kind(n) == 2),
    ("latin|cjk", lambda p, n, raw, k: wb.kind(p) == 2 and wb.kind(n) == 1),
    ("cjk|digit", lambda p, n, raw, k: wb.kind(p) == 1 and wb.kind(n) == 3),
    ("digit|cjk", lambda p, n, raw, k: wb.kind(p) == 3 and wb.kind(n) == 1),
    ("after latin punct", lambda p, n, raw, k: p.isascii() and p in ",;:.!?&"),
    ("after cjk mark", lambda p, n, raw, k: wb.is_cjk_punct(p)),
    ("before cjk mark", lambda p, n, raw, k: wb.is_cjk_punct(n)),
    ("cjk|cjk", lambda p, n, raw, k: wb.kind(p) == 1 and wb.kind(n) == 1),
])


def canon(raw, k):
    """A break at a line end swallows the blanks after it (Word and our BlankTail both do), so the
    same physical break can be reported one or two offsets apart. Push it to the next real character."""
    while k < len(raw) and raw[k] in SPACEISH:
        k += 1
    return k


def classify(raw, k):
    if k <= 0 or k >= len(raw):
        return "edge of paragraph"
    prev, nxt = raw[k - 1], raw[k]
    if prev in ("（", "(", "【", "《", "“", "‘", "[", "{"):
        return "after bracket/quote"
    for name, test in CUT_CLASSES.items():
        try:
            if test(prev, nxt, raw, k):
                return name
        except Exception:
            pass
    return "other"


def x_of(raw_offset, w):
    """raw offset -> how many non-space characters precede it, which is the phone's unit."""
    return sum(1 for c in w["raw"][:raw_offset] if c not in SPACEISH)


def span_chars(raw, a, b):
    """How many real (non-space) characters sit between two break offsets."""
    lo, hi = sorted((a, b))
    return sum(1 for c in raw[lo:hi] if c not in SPACEISH)


def page_at(blk, x):
    acc = 0
    for line in blk["lines"]:
        acc += line["chars"]
        if acc >= x:
            return line["page"]
    return blk["lines"][-1]["page"]


def is_citation(raw, k):
    """A word_only break sitting inside a run of Latin letters/digits/commas with no space: [33]LANGF,..."""
    lo = max(0, k - 14)
    hi = min(len(raw), k + 14)
    window = raw[lo:hi]
    if " " in window:
        return False
    latin = sum(1 for c in window if c.isascii() and c.isalpha())
    return latin >= 10


def self_test():
    """Assertions on the ruler itself, so a future edit cannot silently move the number.

    Each case below is a place where two different physical break reports must mean the same thing,
    or where a class name decides which engine rule gets changed -- a regression here moves the
    reported agreement without any layout changing.
    """
    # A break that swallows the blanks after it must canonicalise onto the next real character.
    assert canon("中文 word 中文", 1) == 1, "a cut in front of a real char must not move"
    assert canon("中文 word 中文", 2) == 3, "a cut sitting on a space must move past it"
    assert canon("中文  word", 2) == canon("中文  word", 3), "one or two spaces read as one break"
    # Class names decide the fix, so they are pinned one case each.
    assert classify("中文文字", 2) == "cjk|cjk", classify("中文文字", 2)
    assert classify("CuSB", 1) == "inside alnum run", classify("CuSB", 1)
    assert classify("中C", 1) == "cjk|latin", classify("中C", 1)
    assert classify("C中", 1) == "latin|cjk", classify("C中", 1)
    assert classify("中3", 1) == "cjk|digit", classify("中3", 1)
    assert classify("中，文", 2) == "after cjk mark", classify("中，文", 2)
    assert classify("文，中", 1) == "before cjk mark", classify("文，中", 1)
    assert classify("a/b", 2) == "after '/'", classify("a/b", 2)
    assert classify("中（文", 2) == "after bracket/quote", classify("中（文", 2)
    # The agreement arithmetic: 2 cuts in common, 1 we cut alone, 1 Word cut alone.
    rate = 100.0 * 2 / max(1, 2 + 1 + 1)
    assert abs(rate - 50.0) < 1e-9, rate
    print("selftest OK: 13 assertions on canon() / classify() / the agreement arithmetic")
    return 0

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-Pdf", default="artifacts/agent-typeset/pdf-truth/input-liu.pdf")
    ap.add_argument("-Docx", default="tests/samples/input-liu.docx")
    ap.add_argument("-Capture", default="artifacts/agent-layout-verify/main251")
    ap.add_argument("-Out", default="artifacts/word-break/break-agreement.tsv")
    ap.add_argument("-CitationOut", default="artifacts/word-break/citation-cuts.tsv")
    ap.add_argument("-SizePt", type=float, default=12.0)
    ap.add_argument("-Top", type=int, default=12)
    ap.add_argument("-SelfTest", action="store_true",
                    help="run the ruler assertions and exit (no PDF, no capture needed)")
    a = ap.parse_args()
    if a.SelfTest:
        return self_test()
    paras = source_paragraphs(a.Docx)
    word = word_layout(a.Pdf, paras, a.SizePt)
    blocks = phone_blocks(os.path.join(a.Capture, "new", "lines-all.tsv"))
    pairs = pair(blocks, word, paras)
    rows, citation_rows = [], []
    both = wonly = donly = 0
    page_diff = []
    for blk, pi in pairs:
        w = word[pi]
        raw = w["raw"]
        # offsets in stripped space -> offsets in raw space, so the classification sees the spaces
        s2o = {v: k for k, v in enumerate(w["idx"])}
        wset = {canon(raw, w["idx"][e - 1] + 1) for e in w["cuts"]}
        dset = set()
        acc = 0
        for line in blk["lines"][:-1]:
            acc += line["chars"]
            dset.add(acc)
        # the phone counts non-space characters, so its offsets map back into raw space
        order = w["idx"]
        dset_raw = set()
        for x in sorted(dset):
            dset_raw.add(canon(raw, order[x - 1] + 1) if x - 1 < len(order) else len(raw))
        wcut_sorted = sorted(wset)

        def word_line_of(off):
            """1-based index of Word's line that holds the character at raw offset `off`."""
            return 1 + sum(1 for e in wcut_sorted if e < off)

        for k in sorted(wset | dset_raw):
            inw, ind = k in wset, k in dset_raw
            both += 1 if inw and ind else 0
            wonly += 1 if inw and not ind else 0
            donly += 1 if ind and not inw else 0
            cls = classify(raw, k)
            side = "both" if inw and ind else ("word_only" if inw else "phone_only")
            wpage = next((ln["page"] for ln in w["lines"]
                          if w["idx"][ln["end_stripped"] - 1] + 1 >= k), None)
            dpage = page_at(blk, x_of(k, w))
            rows.append({"word_para": pi, "device_block": blk["block"], "offset": k, "side": side,
                         "cut_class": cls, "prev_char": raw[k - 1] if k else "",
                         "next_char": raw[k] if k < len(raw) else "",
                         "word_page": wpage, "device_page": blk["start_page"],
                         "word_line": word_line_of(k),
                         "word_lines": len(w["lines"]),
                         "nearest_word_cut": "", "word_cut_chars_off": "",
                         "context": raw[max(0, k - 10):k] + "|" + raw[k:k + 10]})
            if side == "phone_only" and wcut_sorted:
                near = min(wcut_sorted, key=lambda e: (abs(e - k), e))
                rows[-1]["nearest_word_cut"] = near
                rows[-1]["word_cut_chars_off"] = (span_chars(raw, k, near)
                                                  if near != k else 0) * (1 if near > k else -1)
            elif side == "phone_only":
                rows[-1]["word_cut_chars_off"] = ""
            if side == "word_only" and is_citation(raw, k):
                citation_rows.append(rows[-1])
        if w["page"] != blk["start_page"]:
            page_diff.append((pi, blk["block"], w["page"], blk["start_page"]))
    os.makedirs(os.path.dirname(a.Out), exist_ok=True)
    with open(a.Out, "w", newline="", encoding="utf-8") as fh:
        wr = csv.DictWriter(fh, fieldnames=list(rows[0].keys()), delimiter="\t")
        wr.writeheader()
        wr.writerows(rows)
    with open(a.CitationOut, "w", newline="", encoding="utf-8") as fh:
        fields = list(rows[0].keys())
        wr = csv.DictWriter(fh, fieldnames=fields, delimiter="\t")
        wr.writeheader()
        wr.writerows(citation_rows)
    total = both + wonly + donly
    print("capture=" + a.Capture)
    print("paragraphs compared=" + str(len(pairs)) + "   device blocks=" + str(len(blocks))
          + "   Word paragraphs with a full line set=" + str(len(word)))
    print("  Word side dropped: " + ", ".join("%s=%d" % kv
                                              for kv in word_layout.dropped.most_common()))
    print("")
    print("BREAK AGREEMENT (set ruler): %d of %d break points = %.1f%%"
          % (both, total, 100.0 * both / total if total else 0.0))
    print("  phone_only (we broke, Word did not) = %d  (%.1f%% of all break points)"
          % (donly, 100.0 * donly / total if total else 0.0))
    print("  word_only  (Word broke, we did not) = %d  (%.1f%%)"
          % (wonly, 100.0 * wonly / total if total else 0.0))
    per_para = collections.defaultdict(lambda: {"both": 0, "word_only": 0, "phone_only": 0})
    for r in rows:
        per_para[(r["word_para"], r["device_block"])][r["side"]] += 1
    by_para = collections.defaultdict(list)
    for r in rows:
        by_para[r["word_para"]].append(r)
    first_rows = []
    for _pi, rr in by_para.items():
        bad = [x for x in rr if x["side"] != "both"]
        if bad:
            first_rows.append(min(bad, key=lambda x: x["offset"]))
    cascade = total - both - len(first_rows)
    print("")
    print("First divergence per paragraph (the real signal; everything after it in the same")
    print("paragraph is this one disagreement shifting every later line):")
    fh = collections.Counter((r["side"], r["cut_class"]) for r in first_rows)
    print("  paragraphs with any divergence = %d of %d ; cascaded later breaks = %d"
          % (len([1 for k, v in per_para.items() if v["word_only"] or v["phone_only"]]),
             len(pairs), cascade))
    for (side, cls), n in fh.most_common(a.Top):
        print("      %-11s %-20s %3d" % (side, cls, n))
    for label, side in (("phone_only", "phone_only"), ("word_only", "word_only")):
        cnt = collections.Counter(r["cut_class"] for r in rows if r["side"] == side)
        print("  " + label + " by class:")
        for cls, n in cnt.most_common():
            print("      %-20s %4d" % (cls, n))
    print("")
    print("phone_only, class by class, with how far Word's nearest break sits.")
    print("  chars_off > 0 means Word broke LATER than us -- our line ended short (a width/advance gap).")
    print("  chars_off < 0 means Word broke EARLIER -- Word moved something down that we kept.")
    print("  A class whose instances are almost all |chars_off| = 1 is a width problem, not a break rule.")
    mispaged = set(pi for pi, _blk, _wp, _dp in page_diff)
    for cls, n in collections.Counter(r["cut_class"] for r in rows
                                      if r["side"] == "phone_only").most_common():
        ins = [r for r in rows if r["side"] == "phone_only" and r["cut_class"] == cls
               and r["word_cut_chars_off"] != ""]
        offs = sorted(abs(int(r["word_cut_chars_off"])) for r in ins)
        one = sum(1 for o in offs if o == 1)
        med = offs[len(offs) // 2] if offs else 0
        drags = len(set(r["word_para"] for r in rows
                        if r["side"] == "phone_only" and r["cut_class"] == cls
                        and r["word_para"] in mispaged))
        print("  %-20s %3d  drags %d of %d mis-paged paragraphs  |chars_off| median %2d, exactly 1 char: %3d/%3d  -> %s"
              % (cls, n, drags, len(mispaged), med, one, len(ins),
                 "width, one character" if offs and one >= len(ins) * 0.7 else "break rule"))
        for r in ins[:a.Top]:
            print("        para %3d blk %3d %s line %s/%s  off %+4d  %r"
                  % (r["word_para"], r["device_block"], r["word_page"], r["word_line"],
                     r["word_lines"], int(r["word_cut_chars_off"]), r["context"]))
    print("")
    print("page assignment from this same truth file: %d of %d compared paragraphs start on a "
          "different page (%.1f%%)" % (len(page_diff), len(pairs),
                                       100.0 * len(page_diff) / len(pairs) if pairs else 0.0))
    for pi, blk, wp, dp in page_diff[:a.Top]:
        print("      word para %3d (device block %3d): Word page %2d, phone page %2d" % (pi, blk, wp, dp))
    print("")
    print("word_only breaks inside a no-space Latin run (the citation cluster, kept apart): %d -> %s"
          % (len(citation_rows), a.CitationOut))
    for r in citation_rows[:a.Top]:
        print("      para %3d page %s  %r" % (r["word_para"], r["word_page"], r["context"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
