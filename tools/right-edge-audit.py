#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/right-edge-audit.py -- 一行一行量"右边界齐不齐"，中文行与西文行分开算（只测量，不修改）

原生 Word 的 w:jc=both 有两种拉伸：中文行摊在每个字缝上，西文行只摊在词间空格上。两种都要摊到
右边界为止，最后一行除外。本工具拿一次真机采样回答三个问题：

  1. 有多少行根本没摊（openGaps=0 且 lineStretchPx=0），却离右边界超过 2 px；
  2. 按行内文字分堆（含汉字 / 纯西文数字），每堆各差多少；
  3. justify-trace.tsv 里有多少段以 give-up 收场（摊了又被整个段丢回去）。

行右边界 = lineLeftPx + lineWidthPx + hangOverPx（悬挂标点按 Word 的口径算进去），版心取
566.93 px（15.00 cm @ 96 dpi，PageGeometry 同一条换算）。段末行不计：Word 自己也不拉伸它。

用法：
    py tools/right-edge-audit.py artifacts/agent-layout-verify/<tag> [<tag2> ...]
    py tools/right-edge-audit.py <tag> -Tolerance 2
"""
import argparse
import collections
import csv
import os

COLUMN = 566.9333
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def cjk_in(s):
    return any(0x3000 <= ord(c) <= 0x9FFF or 0xFF00 <= ord(c) <= 0xFFEF
               or 0x2E80 <= ord(c) <= 0x2EFF for c in s)


def kind(row):
    """cjk = 行内有汉字或中日韩标点；lat = 纯西文/数字/标点（Word 只摊它的词间空格）。

    优先读 lineText（新采样，空格原样保留）；老采样只有去空格的 lineFull，判定结果一样。
    """
    return "cjk" if cjk_in(row.get("lineText") or row["lineFull"]) else "lat"


def lines_path(capture):
    for cand in (os.path.join(capture, "new", "lines-all.tsv"),
                 os.path.join(capture, "lines-all.tsv")):
        if os.path.exists(cand):
            return cand
    raise SystemExit("no lines-all.tsv under " + capture)


def gave_up(capture):
    path = os.path.join(lines_path(capture).rsplit(os.sep, 1)[0], "justify-trace.tsv")
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle, delimiter="\t"))
    return collections.Counter("give-up" if r["gave_up"] == "1" else "ok" for r in rows), \
        [r["block"] for r in rows if r["gave_up"] == "1"]


def audit(capture, tol):
    with open(lines_path(capture), encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle, delimiter="\t"))
    by = collections.defaultdict(list)
    for r in rows:
        by[int(r["paragraph"])].append(r)
    for b in by:
        by[b].sort(key=lambda r: int(r["line"]))
    stat = collections.Counter()
    worst = collections.defaultdict(list)
    for b, ls in by.items():
        for i, r in enumerate(ls):
            if i == len(ls) - 1:
                continue                      # 段末行：Word 也不拉伸
            k = kind(r)
            right = float(r["lineLeftPx"]) + float(r["lineWidthPx"]) + float(r["hangOverPx"])
            deficit = COLUMN - right
            stat[k + ":行"] += 1
            stat[k + (":短于边界" if deficit > tol else ":齐平")] += 1
            if int(r["openGaps"]) == 0 and float(r["lineStretchPx"]) == 0.0:
                stat[k + ":完全没摊"] += 1
            if deficit > tol:
                worst[k].append((deficit, b, int(r["line"]), r["lineFull"][-26:]))
    print("capture = %s" % capture)
    for k in sorted(stat):
        if k.endswith(":行"):
            print("  %-14s %d 行" % (k.split(":")[0], stat[k]))
    for k in sorted(stat):
        if not k.endswith(":行"):
            print("    %-16s %4d" % (k.split(":", 1)[1], stat[k]))
    for k in sorted(worst):
        v = sorted(worst[k], key=lambda t: -t[0])[:5]
        print("    %s 最差的几行: %s" % (k, ", ".join("blk%d L%d 差%.0fpx" % (x[1], x[2], x[0])
                                                     for x in v)))
    trace = gave_up(capture)
    if trace:
        cnt, blocks = trace
        print("    justify-trace: %s；give-up 的段 %s" % (dict(cnt),
              (" ".join(blocks) if len(blocks) <= 12 else "%d 段" % len(blocks))))
    print("")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("captures", nargs="*",
                    default=["artifacts/agent-layout-verify/spacefix1"])
    ap.add_argument("-Tolerance", type=float, default=2.0, help="允许离右边界的像素，默认 2")
    a = ap.parse_args()
    for c in a.captures:
        audit(c if os.path.isabs(c) else os.path.join(ROOT, c), a.Tolerance)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
