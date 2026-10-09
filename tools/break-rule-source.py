#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/break-rule-source.py -- every break the phone took and Word did not, with the flags Word read.

Input is the point list `tools/break-agreement.py` writes (one row per break point, side=phone_only means
"we broke here, Word did not"). This tool answers, per instance:

  * which paragraph (docx index and device block), which of OUR lines ends there, on which page;
  * the two characters the break sits between, with code points, plus 8 characters of context each side;
  * where Word's nearest break for the same paragraph sits, in characters (chars_off > 0 = Word broke
    later, so our line simply ended short);
  * what that paragraph declares in its own pPr: w:wordWrap, w:kinsoku, w:adjustRightInd, w:autoSpaceDE,
    w:autoSpaceDN, w:overflowPunct, w:snapToGrid, w:widowControl, and its w:pStyle.

That last column is what turns "we break somewhere Word does not" into a rule to implement: it says
whether the paragraph that misbehaves even carries the switch that governs Latin wrapping and East Asian
line breaking, or whether it sits under a style that turns it off. The tool states no spec text on
purpose: the flags are reported as declared here, and Word's own behaviour is the PDF truth already in
the rows; what a flag means is then decided from those two, not from memory.

Inheritance, measured on tests/samples/input-liu.docx rather than assumed (so pPr-declared IS the value
the engine receives for these switches -- nothing above the paragraph says otherwise):
    word/document.xml   <w:wordWrap/>            105 times, never w:val="0"
    word/styles.xml     <w:wordWrap ...>            0 times
    word/styles.xml     <w:kinsoku ...>             0 times
    docDefaults/pPrDefault/pPr                      empty
The same holds for w:kinsoku / w:adjustRightInd / w:autoSpaceDE / w:autoSpaceDN / w:overflowPunct /
w:snapToGrid / w:widowControl: they appear only inside <w:pPr> of the document body. A flag printed as
None below therefore means "the document never says, Word's own default applies", not "hidden in a style".

Each row also carries the verdict, decided by the sign of word_cut_chars_off:
    off > 0   Word's nearest break for that paragraph is LATER  -> our line simply ended early (width)
    off < 0   Word's nearest break is EARLIER                   -> Word moved a whole block we split (rule)
    off = 0   same offset, different class                       -> the seam itself is the disagreement

Usage:
    py tools/break-agreement.py -Capture <tag> -Out <tag>/break-agreement.tsv
    py tools/break-rule-source.py -Rows <tag>/break-agreement.tsv -Capture <tag>
    py tools/break-rule-source.py ... -Classes "inside alnum run,after /,cjk|latin,latin|cjk,cjk|digit,digit|cjk,at a space"
"""
import argparse, collections, csv, importlib.util, os, re, sys, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
TAG_RE = re.compile(r"<[^>]+>")
FLAGS = ("wordWrap", "kinsoku", "adjustRightInd", "autoSpaceDE", "autoSpaceDN",
         "overflowPunct", "snapToGrid", "widowControl")


def load(name, mod):
    spec = importlib.util.spec_from_file_location(mod, os.path.join(HERE, name))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


wb = load("width-bill.py", "wb_for_brs")


def paragraph_flags(docx):
    """Per docx paragraph (same order as width-bill.docx_paragraphs): declared pPr flags + pStyle.

    A flag written as <w:foo/> means true, <w:foo w:val="false"/> means false, absent means None: the
    document said nothing here, so the value would come from the style or the spec default.
    """
    xml = zipfile.ZipFile(docx).read("word/document.xml").decode("utf-8")
    out = []
    for para in re.findall(r"<w:p\b[^>]*>.*?</w:p>|<w:p\b[^>]*/>", xml, re.S):
        m = re.search(r"<w:pPr>.*?</w:pPr>", para, re.S)
        ppr = m.group(0) if m else ""
        row = {}
        for f in FLAGS:
            hit = re.search(r"<w:" + f + r"(?:\s+w:val=\"([^\"]*)\")?\s*/?>", ppr)
            if not hit:
                row[f] = None
            else:
                v = hit.group(1)
                row[f] = True if v is None else (v not in ("false", "0"))
        st = re.search(r"<w:pStyle\s+w:val=\"([^\"]+)\"", ppr)
        row["style"] = st.group(1) if st else "-"
        out.append(row)
    return out


def our_line_of(capture_tsv, block, want_stripped):
    """Which of our laid-out lines ends at `want_stripped` characters into the paragraph."""
    acc, page, lineno, last_tail = 0, "?", "?", ""
    with open(capture_tsv, encoding="utf-8-sig", newline="") as fh:
        for row in csv.DictReader(fh, delimiter="\t"):
            if int(row["paragraph"]) != block:
                continue
            if lineno == "?":
                page = row["page"]
            lineno = row["line"]
            last_tail = (row["lineFull"] or "")[-12:]
            if acc + int(row["lineChars"]) == want_stripped:
                return row["page"], row["line"], row["lineFull"][-12:]
            acc += int(row["lineChars"])
    # not ending exactly at a line end: report the last line we have and say so
    return page, lineno, "(no line ends here; last %s)" % last_tail


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-Rows", default="artifacts/agent-layout-verify/hang2/break-agreement.tsv")
    ap.add_argument("-Capture", default="artifacts/agent-layout-verify/hang2")
    ap.add_argument("-Docx", default="tests/samples/input-liu.docx")
    ap.add_argument("-Top", type=int, default=60)
    ap.add_argument("-Classes", default="inside alnum run,after '/',after '-',cjk|latin,latin|cjk,"
                                        "cjk|digit,digit|cjk,at a space")
    a = ap.parse_args()
    for f in (a.Rows, a.Docx):
        if not os.path.exists(f):
            print("missing input: " + f)
            return 1
    tsv = os.path.join(a.Capture, "new", "lines-all.tsv")
    if not os.path.exists(tsv):
        print("missing capture lines: " + tsv)
        return 1
    paras = wb.docx_paragraphs(a.Docx)
    flags = paragraph_flags(a.Docx)
    rows = list(csv.DictReader(open(a.Rows, encoding="utf-8-sig", newline=""), delimiter="\t"))
    wanted = [c.strip() for c in a.Classes.split(",") if c.strip()]
    order = {c: i for i, c in enumerate(wanted)}
    picked = [r for r in rows if r["side"] == "phone_only" and r["cut_class"] in order]
    # What class of seam Word itself used on the same line of the same paragraph. This is the column that
    # turns "we broke here" into "which of Word's rules we disagree with": if Word's own break on that
    # line sits at a latin|cjk seam, Word kept the Latin block whole and broke in front of it.
    word_seam = collections.defaultdict(list)
    for r in rows:
        if r["side"] == "word_only":
            word_seam[(r["word_para"], r["word_line"])].append(r["cut_class"])
    picked.sort(key=lambda r: (order[r["cut_class"]], int(r["word_para"])))
    print("phone_only rows in %s: %d of %d; classes asked for: %s"
          % (os.path.basename(a.Rows), len(picked), len(rows), ", ".join(wanted)))
    print("")
    by_class = collections.Counter(r["cut_class"] for r in picked)
    wrap = collections.Counter((r["cut_class"], flags[int(r["word_para"])]["wordWrap"]) for r in picked)
    print("class                 instances   of those: paragraph declares w:wordWrap")
    for c, n in by_class.most_common():
        on = wrap.get((c, True), 0)
        print("  %-20s %3d        on %d / off %d / undeclared %d"
              % (c, n, on, wrap.get((c, False), 0), wrap.get((c, None), 0)))
    print("")

    def verdict(r):
        if r["word_cut_chars_off"] == "":
            return "no Word break recorded for that paragraph", "other"
        off = int(r["word_cut_chars_off"])
        if off > 0:
            return "our line ended %d chars early (width)" % off, "width"
        if off < 0:
            return "Word moved the whole block we split (%s chars earlier)" % (-off), "rule"
        return "same offset, different seam class", "seam"
    tally = collections.Counter(verdict(r)[1] for r in picked)
    print("verdict over these %d instances: width %d / rule %d / seam %d"
          % (len(picked), tally["width"], tally["rule"], tally["seam"]))
    print("")
    shown = 0
    for r in picked:
        pi = int(r["word_para"])
        raw = paras[pi] if pi < len(paras) else ""
        stripped_before = sum(1 for ch in raw[:int(r["offset"])] if not ch.isspace())
        pg, ln, tail = our_line_of(tsv, int(r["device_block"]), stripped_before)
        f = flags[pi] if pi < len(flags) else {}
        decl = " ".join("%s=%s" % (k, f.get(k)) for k in FLAGS if f.get(k) is not None) or "(pPr says none)"
        prev, nxt = r["prev_char"], r["next_char"]
        print("%-18s para %3d blk %3d  ours p%s line %s  word p%s line %s/%s  off %s"
              % (r["cut_class"], pi, int(r["device_block"]), pg, ln, r["word_page"], r["word_line"],
                 r["word_lines"], r["word_cut_chars_off"]))
        print("      cut between %r (U+%04X) and %r (U+%04X)   context %s"
              % (prev, ord(prev) if prev else 0, nxt, ord(nxt) if nxt else 0, r["context"]))
        print("      our line ends: %r" % tail)
        print("      pStyle=%s  %s" % (f.get("style"), decl))
        print("      verdict: %s" % verdict(r)[0])
        print("      Word's own break on that line: %s"
              % (", ".join(word_seam.get((r["word_para"], r["word_line"]), [])) or "(none recorded)"))
        shown += 1
        if shown >= a.Top:
            print("      ... (%d more instances not printed)" % (len(picked) - shown))
            break
    return 0


if __name__ == "__main__":
    sys.exit(main())
