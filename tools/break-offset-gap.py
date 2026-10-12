#!/usr/bin/env python3
"""分歧行到底差多少：把同一篇里配不上对的断点两两配对，算出差的字数与差的像素。

读的是 tools/break-agreement.py 的产物 breaks.tsv（每行一个断点，side=both 表示两边一致），
外加手机自己交上来的 lines-all.tsv（每行的 lineWidthPx / lineRoomPx / lineTailFree）。

    py tools/break-offset-gap.py [-Capture artifacts/agent-layout-verify/guard1]
"""
import argparse, csv, os, statistics
from collections import Counter, defaultdict

ap = argparse.ArgumentParser()
ap.add_argument("-Capture", default="artifacts/agent-layout-verify/guard1")
a = ap.parse_args()

rows = list(csv.DictReader(open(os.path.join(a.Capture, "breaks.tsv"), encoding="utf-8"), delimiter="\t"))
lines = list(csv.DictReader(open(os.path.join(a.Capture, "new", "lines-all.tsv"), encoding="utf-8"), delimiter="\t"))

agree = [r for r in rows if r["side"] == "both"]
dis = [r for r in rows if r["side"] != "both"]
print("断点总数 %d：一致 %d，手机多切 %d，Word 多切 %d" % (
    len(rows), len(agree),
    sum(1 for r in dis if r["side"] == "phone_only"),
    sum(1 for r in dis if r["side"] == "word_only")))

# 同一篇里，把手机多切的那一点和 Word 多切的那一点按距离配对：距离就是"差几个字"
by_para = defaultdict(list)
for r in dis:
    by_para[r["word_para"]].append(r)

deltas, pairs, unmatched = Counter(), [], []
for para, rs in by_para.items():
    ph = [r for r in rs if r["side"] == "phone_only"]
    wd = [r for r in rs if r["side"] == "word_only"]
    used = set()
    for w in sorted(wd, key=lambda r: int(r["offset"])):
        if not ph:
            continue
        best = min(range(len(ph)), key=lambda i: abs(int(ph[i]["offset"]) - int(w["offset"])))
        d = int(w["offset"]) - int(ph[best]["offset"])
        if abs(d) <= 6:
            deltas[d] += 1
            pairs.append((para, d, w["cut_class"], w["context"][:38]))
            used.add(ph[best]["offset"])
            ph.pop(best)
        else:
            unmatched.append((para, w["cut_class"]))
    for p in ph:
        unmatched.append((para, p["cut_class"]))

print("\n配成对的 %d 组，差的字数分布（正=手机切得早，负=手机切得晚）：" % sum(deltas.values()))
for d in sorted(deltas):
    print("   %+d 字  %2d 处" % (d, deltas[d]))
print("配不上对的 %d 处" % len(unmatched))

cls = Counter(r["cut_class"] for r in dis)
print("\n按断点类型：", "、".join("%s %d" % (k, v) for k, v in cls.most_common()))

# 我们自己每行行尾留下多少空：留得多说明宽度算贵了，不是断行规则缺
free = []
for l in lines:
    try:
        f = float(l["lineTailFree"])
    except (KeyError, ValueError):
        continue
    if int(l["lineChars"]) > 3 and f >= 0:
        free.append(f)
if free:
    free.sort()
    def q(p):
        return free[min(len(free) - 1, int(len(free) * p))]
    print("\n手机每行行尾剩余空间（px，只算 3 字以上的行，n=%d）：中位 %.1f  p75 %.1f  p90 %.1f  最大 %.1f" % (
        len(free), q(.5), q(.75), q(.90), free[-1]))
    print("   留白在 0~2px 的行 %d，2~10px %d，10~30px %d，>30px %d" % (
        sum(1 for f in free if f <= 2), sum(1 for f in free if 2 < f <= 10),
        sum(1 for f in free if 10 < f <= 30), sum(1 for f in free if f > 30)))

print("\n差得最远的几处（段, 差字数, 类型, 上下文）：")
for para, d, c, ctx in sorted(pairs, key=lambda x: -abs(x[1]))[:12]:
    print("   %4s %+d %-14s %s" % (para, d, c, ctx))
