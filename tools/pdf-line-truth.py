r"""tools/pdf-line-truth.py -- Word 导出 PDF 的逐行基线真值。

Word 的分页真值只能靠 COM 一段一段问（tools/word-page-stack.ps1），那套读数只给"每段首行"的
位置，续排页的页顶与表格内部的行都问不到。Word 自己导出的 PDF 里每一行的基线是精确坐标，
所以逐页账本用它当分母：每页三条数（首行基线到页顶、首末基线之差、末行基线到页底）加起来
正好等于页高，不需要任何估算。

命令：  py tools/pdf-line-truth.py [-Pdf 路径] [-Out 路径]
        py tools/pdf-line-truth.py -Steps [-Size 12] [-Ratio 1.25]
-Steps 不写 TSV，改打相邻两行行顶之差的分布（按字号与中英分组、页眉页脚剔除），并换算成 em：
WordLineHeights 表里的实测 em 就是从这里来的，改表里的数必须重跑这条命令。
输出 TSV 前两行是 # 开头的指纹（sha256、页高、PyMuPDF 版本），其余每行一条：
  page  line  top  baseline  bottom  x0  x1  size  fonts  text
坐标是 PDF 点（1pt = 4/3 我方文档像素），原点在页顶，top/bottom 是墨迹盒（不是行盒）。
"""
import argparse, collections, hashlib, re, statistics, sys, pymupdf

CJK_RE = re.compile(u"[\u3000-\u303f\u4e00-\u9fff\uff00-\uffef]")
LAT_RE = re.compile(u"[A-Za-z0-9]")


def kind(text):
    """一行算中文还是西文：有中日韩文字或全角标点算中文，只有字母数字算西文。"""
    if CJK_RE.search(text):
        return "CJK"
    return "LAT" if LAT_RE.search(text) else "OTH"

def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-Pdf", default="artifacts/agent-typeset/pdf-truth/input-liu.pdf")
    ap.add_argument("-Out", default="artifacts/agent-layout-verify/word-pdf-lines.tsv")
    ap.add_argument("-Steps", action="store_true",
                    help="打相邻行顶之差的分布（WordLineHeights 的实测 em 出处），不写 TSV")
    ap.add_argument("-Size", type=float, default=12.0, help="-Steps 只量这个字号的行，单位 pt")
    ap.add_argument("-Ratio", type=float, default=1.25,
                    help="-Steps 换算 em 用的行距倍数（w:line/240）")
    a = ap.parse_args()
    doc = pymupdf.open(a.Pdf)
    rows = ["# pdf=%s" % a.Pdf, "# sha256=%s  pymupdf=%s  pages=%d" % (sha256(a.Pdf), pymupdf.__version__, doc.page_count)]
    heights = set()
    all_pages = []
    for i in range(doc.page_count):
        page = doc[i]
        heights.add(round(page.rect.height, 2))
        found = []
        for block in page.get_text("dict")["blocks"]:
            for line in block.get("lines", []):
                spans = [s for s in line.get("spans", []) if s.get("text", "").strip()]
                if not spans:
                    continue
                text = "".join(s["text"] for s in spans).strip()
                found.append((
                    min(s["bbox"][1] for s in spans),
                    max(s["origin"][1] for s in spans),
                    max(s["bbox"][3] for s in spans),
                    min(s["bbox"][0] for s in spans),
                    max(s["bbox"][2] for s in spans),
                    max(s["size"] for s in spans),
                    ",".join(sorted(set(s["font"] for s in spans))),
                    " ".join(text.split())[:40],
                ))
        # One PDF line per visual row: spans sharing a baseline within 0.6 pt and overlapping x.
        found.sort(key=lambda r: (r[1], r[3]))
        merged = []
        for r in found:
            hit = None
            for m in merged:
                if abs(m[1] - r[1]) <= 0.6 and not (r[3] > m[4] + 1 or m[3] > r[4] + 1):
                    hit = m
                    break
            if hit is None:
                merged.append(list(r[:7]) + [r[7]])
                continue
            hit[0] = min(hit[0], r[0]); hit[1] = max(hit[1], r[1]); hit[2] = max(hit[2], r[2])
            hit[3] = min(hit[3], r[3]); hit[4] = max(hit[4], r[4]); hit[5] = max(hit[5], r[5])
            f = set(hit[6].split(",")) | set(r[6].split(","))
            hit[6] = ",".join(sorted(f))
            if r[7] not in hit[7]:
                hit[7] = (hit[7] + " " + r[7]).strip()[:40]
        merged.sort(key=lambda m: (m[1], m[3]))
        all_pages.append(merged)
        for n, m in enumerate(merged):
            rows.append("\t".join([str(i + 1), str(n), "%.2f" % m[0], "%.2f" % m[1], "%.2f" % m[2],
                                   "%.2f" % m[3], "%.2f" % m[4], "%.2f" % m[5], m[6], m[7]]))
    if a.Steps:
        groups = collections.defaultdict(list)
        for merged in all_pages:
            body = [m for m in merged if 100.0 < m[0] < 740.0]      # 页眉页脚不算
            for x, y in zip(body, body[1:]):
                step_pt = y[0] - x[0]
                if not (15.0 < step_pt < 34.0):
                    continue
                if round(x[5], 2) != round(a.Size, 2) or round(y[5], 2) != round(a.Size, 2):
                    continue
                if kind(x[7]) != kind(y[7]) or kind(x[7]) == "OTH":
                    continue
                groups[kind(x[7])].append(step_pt * 96.0 / 72.0)
        for name in sorted(groups):
            v = sorted(groups[name])
            med = statistics.median(v)
            print("size=%.0fpt %s n=%d median=%.3fpx mean=%.3fpx em=%.5f"
                  % (a.Size, name, len(v), med, sum(v) / len(v),
                     med / a.Ratio / (a.Size * 96.0 / 72.0)))
            print("    分布:", collections.Counter(round(x, 2) for x in v).most_common(5))
        return
    with open(a.Out, "w", encoding="utf-8", newline="\n") as f:
        f.write("page\tline\ttop\tbaseline\tbottom\tx0\tx1\tsize\tfonts\ttext\n")
        f.write("\n".join(rows[2:]) + "\n")
    print("out=%s rows=%d page_heights=%s sha256=%s" % (a.Out, len(rows) - 2, sorted(heights), sha256(a.Pdf)[:16]))

main()
