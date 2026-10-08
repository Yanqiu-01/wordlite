# -*- coding: utf-8 -*-
"""从一篇开放获取论文的 PDF 里按固定规则挑 N 句，做降重负载测试的靶子句子。

规则（与 CHANGELOG 2.3.0 那一发同源，两处放宽写在下面）：
  1. 全文去掉所有空白，按 。； 切句；
  2. 句子里必须有"，"，长度 22-58 字；
  3. 丢掉 PDF 提取残渣：页眉页脚（机械工程学报 / JOURNAL / 第N卷 / Vol / No. / Oct /
     章节号开头 / 参考文献 / 作者简介 / 基金项目 / 收稿 / 关键词 / Abstract）、
     公式残渣（= ≥ ≤ ≠ τ σ μ 希腊字母 / "(1)" 这类编号）、图题表题开头（图N / 表N / 如图 / 如表 / 式中）、
     作者简介残渣（@ / E-mail / NNNN年出生 / 硕士 / 博士 / 讲师 / 导师 / 教授。去 whitespace 以后
     简介正文会和邮箱粘成一句，混进靶子就是把一段根本不是正文的东西送去改写）；
  4. 取以中轴对齐的连续 N 句；给了 --must-contain 时保证窗口把那几句全包住
     （这样 N=11 / 25 / 45 三个负载点是同一批句子的不同负载，不是换了一批句子）。

与 2.3.0 的差别（写在文档里也要写在这儿）：那一次还要求"句子里不含数字和字母"，
满足全部条件的句子在这篇论文里只有 22 句，凑不出 25 与 45，这一版把这条去掉，
改成靠第 3 条去残渣。副作用是重负载那两档的句子里带上了数字、单位与引文序号，
不动区断言反而更难过。

用法：py tools/make-planted-sentences.py <pdf> <n> <输出文件> [--must-contain <句子文件>] [--strict]
"""
import hashlib
import io
import re
import sys

import pymupdf

HEADER = re.compile(r"机械工程学报|JOURNAL|Vol|No\.|Oct|第\d+卷|^\d+(\.\d+)*[\u4e00-\u9fa5]"
                    r"|参考文献|作者简介|基金项目|收稿|关键词|Abstract")
FORMULA = re.compile(r"[=≥≤≠τσμ\u0391-\u03c9]|\(\d+\)|（\d+）")
CAPTION = re.compile(r"^(图|表|式中|如图|如表)\d*")
RESIDUE = re.compile(r"@|E-mail|mail|\d{4}年出生|硕士|博士，|讲师|导师|教授")


def pool(pdf, strict=False):
    """按规则 1-3 挑出的句子，保持原文顺序。strict 就是 2.3.0 原样：窗口 22-58 且不含数字字母。

    非 strict 那一档放宽两处（都写在文档里）：窗口放到 22-62、允许句子里有数字与字母，
    换成靠 HEADER/FORMULA/CAPTION/RESIDUE 四条去残渣。原因是严格口径在这篇论文里只有
    22 句，凑不出 25 与 45 两个负载点。
    """
    doc = pymupdf.open(pdf)
    text = re.sub(r"\s+", "", "\n".join(page.get_text() for page in doc))
    lo, hi = (22, 58) if strict else (22, 62)
    out = []
    for s in re.split(r"[。；]", text):
        if "\uFF0C" not in s or not (lo <= len(s) <= hi):
            continue
        if HEADER.search(s) or FORMULA.search(s) or CAPTION.search(s) or RESIDUE.search(s):
            continue
        if strict and re.search(r"[0-9A-Za-z]", s):
            continue
        out.append(s)
    return out


def window(picks, n, must=None, strict=False):
    """中轴对齐取 N 句；must 里那几句必须在窗口里。strict 用 2.3.0 那句 picks[len//2:] 。"""
    n = min(n, len(picks))
    if strict:
        start = max(0, min(len(picks) - n, len(picks) // 2))
        return picks[start:start + n], start
    if must:
        idx = [picks.index(s) for s in must]
        lo, hi = min(idx), max(idx) + 1
        span = hi - lo
        n = max(n, span)
        start = max(0, min(len(picks) - n, (lo + hi) // 2 - n // 2))
    else:
        start = max(0, min(len(picks) - n, len(picks) // 2 - n // 2))
    return picks[start:start + n], start


def main():
    pdf, n, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    must = []
    if "--must-contain" in sys.argv:
        f = sys.argv[sys.argv.index("--must-contain") + 1]
        must = [x.strip() for x in io.open(f, encoding="utf-8").read().split("\n")
                if x.strip() and not x.strip().startswith("#")]
    strict = "--strict" in sys.argv
    picks = pool(pdf, strict)
    got, start = window(picks, n, must, strict)
    body = "\n".join(got) + "\n"
    io.open(out, "w", encoding="utf-8").write(body)
    covered = "窗口含住参照句=%s" % (all(s in got for s in must) if must else "n/a")
    print("口径=%s 池子=%d 窗口起点=%d 取=%d 字=%d sha256=%s %s -> %s"
          % ("严格(2.3.0 原样)" if strict else "放宽(允许数字字母)", len(picks), start, len(got),
             sum(len(s) for s in got), hashlib.sha256(body.encode("utf-8")).hexdigest()[:12],
             covered, out))


main()
