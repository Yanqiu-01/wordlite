#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""uiautomator 的 dump 与 View 层的真值对质。

一台已知内容的滚动容器（探针 ScrollCensusActivity 的 40 行，或 RibbonErgonomicsActivity 的真 ribbon）
自己写下 View 层真值表；主机在同一时刻 dump 一份 uiautomator。逐行回答三件事：

  1. 这个节点在 dump 里有没有；
  2. dump 报的边界是不是"原边界 ∩ 视口"那一圈（被折线切到的节点，dump 报的是切完的边界）；
  3. 折线以外（below the fold）与根本没建（absent）能不能只凭一份 dump 分开。

最后一行 CONCLUSION 是给文档用的结论：只要出现 dump-drops-offscreen，就说明"dump 里没有"
证不了"屏幕上没有"，两者分开的办法是滚一段再 dump 一次，或者直接读 View 层。

用法：
  python tools/ui-dump-census.py --kind scroll --tree <rest.tsv> --dump <rest.xml> \
      --tree2 <end.tsv> --dump2 <end.xml>
  python tools/ui-dump-census.py --kind ribbon --tree <ribbon.tsv> --dump <dump.xml> --tab 开始
表格打到 stdout（TSV）。
"""
import argparse
import csv
import io
import re
import sys
import xml.etree.ElementTree as ET

BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def dump_nodes(path):
    """dump 里的全部节点：(文本, 类名, 边界)。坐标是屏幕坐标。"""
    out = []
    for node in ET.parse(path).getroot().iter("node"):
        match = BOUNDS.match(node.get("bounds") or "")
        if match:
            out.append(((node.get("text") or "").strip(), node.get("class") or "",
                        tuple(int(g) for g in match.groups())))
    return out


def container_band(nodes, axis):
    """滚动容器在屏幕上的那一片：树表是内容坐标，dump 是屏幕坐标，差的就是这一段。
    竖向取上下，横向取左右——横向滚动行的左边界一般是 0。"""
    for _, cls, rect in nodes:
        if cls.endswith("ScrollView"):
            return (rect[1], rect[3]) if axis == "y" else (rect[0], rect[2])
    return (0, 1 << 30)


def read_tree(path, kind, tab, profile):
    with io.open(path, encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle, delimiter="\t"))
    if kind == "ribbon":
        return [r for r in rows if r.get("tab") == tab and r.get("kind") == "cmd"
                and (not profile or r.get("profile") == profile)]
    return [r for r in rows if r.get("tag", "").startswith("wl-row-")]


def census(tree, nodes, kind, label, counts, out):
    axis = "y" if kind == "scroll" else "x"
    key, lo_key, hi_key, off_key = (("text", "top", "bottom", "scroll_y") if kind == "scroll"
                                    else ("label", "left", "right", "scroll_x"))
    lo, hi = container_band(nodes, axis)
    by_text = {}
    for text, _, rect in nodes:
        if text:
            by_text.setdefault(text, rect)
    for row in tree:
        marker = row[key] or row.get("tag", "")
        off = int(row[off_key] or 0)
        # 内容坐标 -> 屏幕坐标：减掉这一排已滚的量，再抬到容器在屏幕上的位置，
        # 然后裁进容器那一片矩形——裁完为空的就是 dump 根本不报的那些。
        shift = lo
        top, bottom = int(row[lo_key]) - off + shift, int(row[hi_key]) - off + shift
        expected = (max(top, lo), min(bottom, hi))
        has_area = expected[1] - expected[0] > 0
        got = by_text.get(marker)
        if got:
            got_band = (got[1], got[3]) if axis == "y" else (got[0], got[2])
            verdict = "dump-full-bounds" if got_band == expected else "dump-disagrees"
            says = "[%d,%d]" % got_band
        else:
            verdict = "dump-drops-offscreen" if not has_area else "dump-drops-visible"
            says = "-"
        flag = row.get("in_viewport") or row.get("on_screen") or ""
        counts["%s/%s" % (label, verdict)] = counts.get("%s/%s" % (label, verdict), 0) + 1
        out.append("%s\t%s\t%s\t[%d,%d]\t%s\t%s\t%s" % (
            marker, label, ("no", "yes")[flag in ("true", "visible", "clipped")],
            top, bottom, "yes" if has_area else "no", says, verdict))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--kind", choices=["scroll", "ribbon"], required=True)
    ap.add_argument("--tree", required=True)
    ap.add_argument("--dump", required=True)
    ap.add_argument("--tree2", default="")
    ap.add_argument("--dump2", default="")
    ap.add_argument("--tab", default="开始")
    ap.add_argument("--profile", default="", help="ribbon 表里量哪一档宽度，默认第一档（手机本来的宽度）")
    args = ap.parse_args()

    profile = args.profile
    if args.kind == "ribbon" and not profile:
        with io.open(args.tree, encoding="utf-8") as handle:
            first = next(iter(csv.DictReader(handle, delimiter="\t")), {})
        profile = first.get("profile", "")
        print("# 量的是 %s 这一档、%s 页签的命令行：一份 dump 只对得上这一档的宽度。" % (profile, args.tab))

    out = ["marker\tdump\tin_tree_viewport\ttree_on_screen_rect\texpected_in_dump\tdump_says\tverdict"]
    counts = {}
    census(read_tree(args.tree, args.kind, args.tab, profile), dump_nodes(args.dump),
           args.kind, "rest", counts, out)
    if args.dump2:
        tree2 = args.tree2 or args.tree
        if tree2 != args.tree:
            # 换了树表就得换档：第二份 dump 对的是滚到底那一档的 scroll_y。
            rows = read_tree(tree2, args.kind, args.tab, "")
            census(rows, dump_nodes(args.dump2), args.kind, "end", counts, out)
        else:
            census(read_tree(args.tree, args.kind, args.tab, profile), dump_nodes(args.dump2),
                   args.kind, "end", counts, out)
    print("\n".join(out))
    print("CONCLUSION\t%s" % "\t".join("%s=%d" % kv for kv in sorted(counts.items())))
    return 0


if __name__ == "__main__":
    sys.exit(main())