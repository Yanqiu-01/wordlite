#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/line-height-tail.py -- which paragraphs carry the line-height p90, and under which declaration?

Item 3 of the acceptance table is "median absolute error of the per-line line height, p90 <= 0.50 px".
A p90 of 2.533 px is not a rounding tail: some whole cohorts are being billed at the wrong height.
This reads a capture's line-height-rows.tsv (written by artifacts/agent-typeset/line-height-rows.ps1,
one row per line: what we billed, what we drew, Word's baseline advance, and the paragraph's own
sz / w:line / lineRule / snapToGrid / whether the line holds East Asian text or a script run) and
answers three questions:

  1. which rows are the tail (|error| at or above the p90), grouped by the declaration they sit under;
  2. for each group, Word's line height, ours, and the paragraph numbers involved;
  3. how much of the whole p90 each group is responsible for, so the fix order is set by evidence.

The docx paragraph numbers come from tools/break-agreement.py's point list when it sits in the capture
directory, so a person reading the table can open the paragraph in the document, not only in a capture.

Usage:
    py tools/line-height-tail.py                       # default capture artifacts/agent-layout-verify/pdfgate1
    py tools/line-height-tail.py -Capture artifacts/agent-layout-verify/265-release   # adds docx paragraphs
    py tools/line-height-tail.py -Capture <dir> -Top 8
"""
import argparse, collections, csv, os, statistics, sys


def quantile(vals, q):
    s = sorted(vals)
    if not s:
        return 0.0
    return s[min(len(s) - 1, int(q * (len(s) - 1)))]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-Capture", default="artifacts/agent-layout-verify/pdfgate1")
    ap.add_argument("-Rows", default="", help="default: <Capture>/line-height-rows.tsv")
    ap.add_argument("-Top", type=int, default=8)
    ap.add_argument("-Map", default="", help="break-agreement.tsv, to also print the Word paragraph"
                    " numbers of the blocks in each cohort (device block index and docx paragraph"
                    " index are not the same number: tables and figures take blocks too).")
    a = ap.parse_args()
    path = a.Rows or os.path.join(a.Capture, "line-height-rows.tsv")
    if not os.path.exists(path):
        print("missing %s" % path)
        return 1
    rows = list(csv.DictReader(open(path, encoding="utf-8-sig", newline=""), delimiter="\t"))
    docx_of_block = {}
    map_path = a.Map or os.path.join(a.Capture, "break-agreement.tsv")
    if os.path.exists(map_path):
        with open(map_path, encoding="utf-8-sig", newline="") as fh:
            for m in csv.DictReader(fh, delimiter="\t"):
                try:
                    docx_of_block[int(m["device_block"])] = int(m["word_para"])
                except (KeyError, ValueError):
                    pass
        print("block -> docx paragraph map: %s (%d blocks covered; a block missing here simply has no"
              " compared line)" % (map_path, len(docx_of_block)))
    for r in rows:
        r["delta"] = float(r["delta"]); r["abs"] = abs(float(r["delta"]))
        r["word"] = float(r["word"]); r["billed"] = float(r["billed"])
        r["size"] = r["size_pt"]; r["rule"] = r["rule"]
        r["linetw"] = r["line_twipssnap"]; r["snap"] = r["snap_set"]
        r["grid"] = r["grid_advance"]; r["ea"] = r["has_ea"]; r["script"] = r["has_script"]
    p90 = quantile([r["abs"] for r in rows], 0.9)
    med = statistics.median([r["abs"] for r in rows])
    print("rows=%d  median|delta|=%.3f px  p90|delta|=%.3f px  max=%.3f px"
          % (len(rows), med, p90, max(r["abs"] for r in rows)))

    def cohort(r):
        return "sz%s line%s %s snap=%s grid=%s %s" % (
            r["size"], r["linetw"], r["rule"], r["snap"], r["grid"],
            "EA" if r["ea"] == "true" else "Latin-only")

    tail = [r for r in rows if r["abs"] >= p90 - 1e-9]
    print("tail rows (|delta| >= p90): %d of %d" % (len(tail), len(rows)))
    print("")
    groups = collections.defaultdict(list)
    for r in tail:
        groups[cohort(r)].append(r)
    print("== the tail, by what the paragraph declares ==")
    for name, g in sorted(groups.items(), key=lambda kv: -sum(x["abs"] for x in kv[1])):
        blocks = sorted(set(int(x["block"]) for x in g))
        print("%-52s %2d rows | Word %7.3f px  ours %6.2f px  delta med %+.3f (min %+.3f)"
              % (name, len(g), statistics.median([x["word"] for x in g]),
                 statistics.median([x["billed"] for x in g]),
                 statistics.median([x["delta"] for x in g]), min(x["delta"] for x in g)))
        print("      device blocks: %s" % ", ".join(str(b) for b in blocks[:a.Top])
              + (" ..." if len(blocks) > a.Top else ""))
        known = [(b, docx_of_block[b]) for b in blocks if b in docx_of_block]
        if known:
            print("      docx paragraphs (same lines, Word's own numbering): %s"
                  % ", ".join("blk%d=para%d" % kb for kb in known[:a.Top])
                  + (" ..." if len(known) > a.Top else ""))
        print("      share of the whole |delta| sum in the tail: %.1f%%"
              % (100.0 * sum(x["abs"] for x in g) / sum(x["abs"] for x in tail)))
    print("")
    print("== every cohort in the capture, so the tail can be compared with the whole ==")
    allg = collections.defaultdict(list)
    for r in rows:
        allg[cohort(r)].append(r)
    for name, g in sorted(allg.items(), key=lambda kv: -len(kv[1])):
        print("%-52s %2d rows | Word %7.3f  ours %6.2f  med %+.3f  |med| vs 0.50 target: %s"
              % (name, len(g), statistics.median([x["word"] for x in g]),
                 statistics.median([x["billed"] for x in g]), statistics.median([x["delta"] for x in g]),
                 "over" if abs(statistics.median([x["delta"] for x in g])) > 0.5 else "ok"))
    return 0


if __name__ == "__main__":
    sys.exit(main())