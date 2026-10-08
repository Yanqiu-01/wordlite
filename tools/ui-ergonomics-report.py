#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 ribbon 可达性探针量回来的两张表（改前 / 改后）打成 markdown。

数据来源：pwsh tools/build-uiaudit-probe.ps1 -Tree <树> -OutTsv <tsv>
（探针 RibbonErgonomicsActivity 在真机上把每条横向滚动行里每个控件的左右边界量出来，
 自己写进探针包的外部目录，脚本 pull 回来。）

分类只有三种，而且都是在说"看得见多少"，不是在说"存不存在"：
  visible 看得全；cut 被屏幕边切一刀；fold 在这一排的折线以外（往那一排的方向滚一下就到）。
表是从 View 层数出来的，节点没建就根本不会有那一行——所以 fold 永远不等于"这个按钮没做"。
dump 那一侧为什么证不了这件事，见 tools/ui-visibility-oracle.ps1 与 docs/ui-ergonomics.md。

用法：python tools/ui-ergonomics-report.py --before <before.tsv> --after <after.tsv>
"""
import argparse
import csv
import io
import sys

STATE = {"visible": "看得全", "clipped": "切断", "fold": "折线外", "hidden": "折线外"}


def read(path):
    with io.open(path, encoding="utf-8") as handle:
        return list(csv.DictReader(handle, delimiter="\t"))


def summary(rows, label):
    rows = [r for r in rows if "+" not in r["profile"]]
    print("**%s**\n" % label)
    print("| 档 | 哪一排 | 页签 | 控件数 | 视口 | 内容宽 | 可滚 | 静止：看得全/切断/折线外 | 边缘提示 |")
    print("|---|---|---|--:|--:|--:|--:|:-:|---|")
    seen = []
    for r in rows:
        key = (r["profile"], r["part"], r["tab"])
        if key in seen:
            continue
        seen.append(key)
        same = [x for x in rows if (x["profile"], x["part"], x["tab"]) == key and x["kind"] == "cmd"]
        counts = {"visible": 0, "clipped": 0, "fold": 0, "hidden": 0}
        for x in same:
            counts[x["at_rest"]] = counts.get(x["at_rest"], 0) + 1
        fold = counts["fold"] + counts["hidden"]
        print("| %s | %s | %s | %d | %s | %s | %s | %d / %d / %d | %s |" % (
            r["profile"], r["part"], r["tab"], len(same), r["viewport"], r["content"], r["range"],
            counts["visible"], counts["clipped"], fold, r["hint"]))
    print("")


def detail(before, after, profile, tab, part="command"):
    def pick(rows, prof, tab, part="command", kind="cmd"):
        want = prof
        return [r for r in rows if r["profile"] == want and r["tab"] == tab
                and r["part"] == part and r["kind"] == kind]
    print("**`%s` 页签的%s（`%s`）**\n"
          % (tab, "命令行" if part == "command" else "页签条", profile))
    print("| 控件 | 改前边界 | 改前静止 | 改前滚到底 | 改后边界 | 改后静止 | 改后滚到底 | 改后选中态 |")
    print("|---|---|:-:|:-:|---|:-:|:-:|:-:|")
    b = {r["tag"]: r for r in pick(before, profile, tab, part)}
    a = {r["tag"]: r for r in pick(after, profile, tab, part)}
    state_profile = profile + "+state"
    a_state = {r["tag"]: r for r in pick(after, state_profile, tab, part)}
    for tag in sorted(set(list(b.keys()) + list(a.keys())),
                      key=lambda t: int((a.get(t) or b.get(t))["left"])):
        rb, ra, rs = b.get(tag), a.get(tag), a_state.get(tag)
        name = (ra or rb)["label"] or "分隔线"
        def cell(r):
            return "-" if r is None else "%s..%s" % (r["left"], r["right"])
        def st(r, col):
            return "-" if r is None else STATE.get(r[col], r[col])
        sel = "-" if rs is None else rs.get("selected", "-")
        print("| %s `%s` | %s | %s | %s | %s | %s | %s | %s |" % (
            name, (tag or "sep").replace("command-", ""), cell(rb), st(rb, "at_rest"), st(rb, "at_end"),
            cell(ra), st(ra, "on_screen"), st(ra, "at_end"), sel))
    rows = (pick(after, state_profile, tab, part) or pick(after, profile, tab, part))
    if part != "command":
        rows = []
    if rows:
        moved = rows[0]["scroll_x"]
        print("")
        print("- 改后这一排静止时停在 `scroll_x=%s`；`+state` 那一档报的是"
              "「这一段已经加粗并且两端对齐」的选中态与自动挪动。" % moved)
    print("")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--before", required=True)
    ap.add_argument("--after", required=True)
    args = ap.parse_args()
    before, after = read(args.before), read(args.after)
    profiles = []
    for r in after:
        if r["profile"] not in profiles:
            profiles.append(r["profile"])
    print("探针：`tools/device-probe/RibbonErgonomicsActivity.java`（独立包 com.rikkahub.wordlite.uiaudit，"
          "不覆盖用户机里的 com.rikkahub.wordlite）。重跑：\n")
    print("```powershell\n"
          "pwsh tools/build-uiaudit-probe.ps1 -Tree <源码树> -OutTsv artifacts/analysis/ui-ergonomics/after.tsv\n"
          "```")
    print("表里的边界是探针在真机上 layout 完直接读 View 层得到的，单位 px。\n")
    summary(before, "改前（HEAD 那一棵树）")
    summary(after, "改后")
    for profile in [p for p in profiles if "+state" not in p]:
        detail(before, after, profile, "开始")
    for profile in [p for p in profiles if "+state" not in p]:
        detail(before, after, profile, "表格工具", part="tab")
    return 0


if __name__ == "__main__":
    sys.exit(main())