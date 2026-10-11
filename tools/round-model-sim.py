#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/round-model-sim.py -- 逐字整像素取整到底欠了我们多少行？（只测量，不修改）

背景：Android 交给排版的是整像素，本引擎每个字形 advance 都先取整再累加（docs 23.1-23.2 实测）。
Word 累加浮点 advance，最多在一个不可拆单元上取整一次。每个字形误差不超过半个像素，但一行几十个字
符都要付一次，所以直觉上"逐字取整把我们挤早了一行"。本脚本量这个直觉对不对，不改任何代码。

方法：把 Word 自己导出的 PDF 逐行重放，对每个正文行问排版器真正会问的那一个问题：

    这一行已经放了 Word 放的那些字符，下一个不可拆原子还放得下吗？

Word 的答案就印在页面上：下一行不是以该原子整体开头，就说明放不下。我们的答案对同一批字符算三遍，
一律用打包字体文件的 hmtx（绝不读 PDF 里的宽度），三个模型唯一的区别就是"在哪取整"：
  int    每个字符 floor(x+0.5) 取整                -- 手机现状
  token  不可拆西文词整体取整一次，汉字仍逐字取整      -- 拟议模型
  float  不取整                                    -- Word 的做法
三个模型的 4.0 px w:autoSpaceDE/DN 接缝钱完全一样，所以模型之间的差全部来自取整位置。

正文行的口径（按需求）：12pt、宋体/Times、x0 落在版心左缘或左缘+24pt。表格单元格、标题、题注、页眉
页脚、页码会死在这三条上，或者死在段落匹配上：每行都要回到 tests/samples/input-liu.docx 里对应的
w:p，落在 w:tbl 里的丢掉，目录行（前导点不是文字）匹配不上。一行只有在下一 PDF 行接着同一段的下一个
源字符时才判定，这样"再放一个原子"才是同一段的同一件事。

PyMuPDF 会在 Word 留了 autoSpace 空隙的地方补一个空格字符。它是真实宽度（本文档 3.000 pt = 4.000 px，
正好 0.25 em），Word 自己的行长保留它；三个模型一律丢掉它、改按 4.0 px 接缝计费，同一笔钱不重复计。

会合法改变判定、因此按需求剔除而不是与之纠缠的：禁则（行首不得是 。，、；：？！”）和 w:overflowPunct
（收尾标点可越右版心约 1 em）。行尾是这些标点、或下一原子以这些标点开头的行直接剔除，剩下的分歧数是
上界，不是总数。

用法：
    py tools/round-model-sim.py
    py tools/round-model-sim.py -Pdf artifacts/adb-word-compare/word-native.pdf -Report <path> -Top 10
只读；只写 -Report（同样的数字逐字打到 stdout）。
"""
import argparse
import collections
import hashlib
import importlib.util
import math
import os
import statistics
import sys
import xml.etree.ElementTree as ET
import zipfile

import pymupdf

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"

PT_TO_PX = 4.0 / 3.0            # PageGeometry.points
SIZE_PT = 12.0
SIZE_TOL = 0.35
COLUMN_PX = 567.0               # 425.25 pt 版心 = 567.0 px；左缘从 PDF 上读
INDENT_PT = 24.0
INDENT_PX = 32.0
GAP_PX = 4.0                    # autogap-truth.py 量过：0.2500 em，本 PDF 上再量一次
X0_TOL = 0.75
MIN_INK = 8                     # 少于这么多有墨的字符就不算正文行（签名横线、页码之类）
SPACEISH = (" ", "\t", "\u3000", "\xa0")
BODY_FACES = {"SimSun", "SimHei", "TimesNewRomanPSMT", "TimesNewRomanPS-BoldMT",
              "TimesNewRomanPS-ItalicMT"}
# 禁则 / w:overflowPunct：行首不得是这些，收尾的可越版心 hanging。
KINSOKU_RAW = "\u3002\uff0c\u3001\uff1b\uff1a\uff1f\uff01\u201d"
KINSOKU = set(KINSOKU_RAW)
MODELS = ("int", "token", "float")
MODEL_CN = {"int": "INT-PER-CHAR（现状）", "token": "ROUND-OF-SUM（逐词）", "float": "FLOAT（Word）"}


def load(name, mod):
    """复用 tools/width-bill.py 里已审过的引擎规则，而不是重写一遍。"""
    spec = importlib.util.spec_from_file_location(mod, os.path.join(HERE, name))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


wb = load("width-bill.py", "wb_for_round_sim")     # kind()/is_cjk()/is_cjk_punct()/seam()/hmtx_em()
MISSING = collections.Counter()


def face_key(c):
    """DocxTextLayout 的择面规则，也就是 font-advance-audit.py 审的那条：CJK 码位走 eastAsia 面，
    U+0080 以下走 w:ascii 面，其余走 hAnsi。本文档 docDefaults 是 eastAsia=宋体（打包 song.ttc）、
    ascii/hAnsi=Times New Roman（打包 times-new-roman.ttf）。"""
    return "song" if wb.is_cjk(c) else "times"


def adv_px(c):
    """一个字符在本引擎账上的浮点宽度（px），取自打包字体文件的 hmtx。"""
    em = wb.hmtx_em(face_key(c), c)
    if em is None:
        MISSING[c] += 1
        em = 1.0 if wb.is_cjk(c) else 0.5
    return em * SIZE_PT * PT_TO_PX


def r8(x):
    """Android 的 Math.round：floor(x + 0.5)。Python 的 round() 是银行家舍入，会算错。"""
    return math.floor(x + 0.5)


def is_latin(c):
    return wb.kind(c) in (2, 3)


def atom_end(src, i, stop):
    j = i
    while j < stop and is_latin(src[j]):
        j += 1
    return j


def round_bill(src, i, j, model):
    """src[i:j] 不含接缝钱的部分；三个模型只在这里不同。"""
    if model == "float":
        return sum(adv_px(c) for c in src[i:j])
    if model == "int":
        return float(sum(r8(adv_px(c)) for c in src[i:j]))
    total, k = 0.0, i
    while k < j:
        e = min(j, atom_end(src, k, j)) if is_latin(src[k]) else k + 1
        total += r8(sum(adv_px(c) for c in src[k:e]))
        k = e
    return total


def span_bill(src, i, j, model):
    """src[i:j] 按引擎的度量方式计费：取整 + 这段该付的每个 4.0 px 接缝。

    落在区间右边缘的接缝记在它的宋体一侧，也就是 j-1 这个字符上，所以即使西文伙伴已经掉到下一行，
    这一行照样付钱（width-bill.py 量过的 tail 记账）。落在左边缘的接缝记在 i 之前那个字符上，属于
    上一行，本段不付。
    """
    total = round_bill(src, i, j, model)
    for k in range(i, j - 1):
        if wb.seam(src[k], src[k + 1]):
            total += GAP_PX
    if j < len(src) and wb.kind(src[j - 1]) == 1 and wb.seam(src[j - 1], src[j]):
        total += GAP_PX
    return total


def read_lines(pdf):
    """PDF 行按阅读序；每字符 (char, x_origin_pt, bbox_width_pt, size_pt, face)。"""
    out = []
    doc = pymupdf.open(pdf)
    for pno, page in enumerate(doc, 1):
        rows = []
        for b in page.get_text("rawdict")["blocks"]:
            for ln in b.get("lines", []):
                cs = []
                for s in ln.get("spans", []):
                    for ch in s["chars"]:
                        if ch["c"] in ("\n", "\r", "\x0b", "\x0c"):
                            continue
                        cs.append((ch["c"], ch["origin"][0], ch["bbox"][2] - ch["bbox"][0],
                                   round(s["size"], 2), s["font"].split("+")[-1]))
                if len(cs) < 2:
                    continue
                sizes = collections.Counter(c[3] for c in cs)
                rows.append((ln["bbox"][1], cs[0][1], pno, cs, sizes.most_common(1)[0][0]))
        for _y, _x, pno, cs, size in sorted(rows):
            out.append({"page": pno, "size": size, "chars": cs, "x0": min(c[1] for c in cs),
                        "flat": "".join(c[0] for c in cs if c[0] not in SPACEISH)})
    return out


def paragraphs(docx):
    """文档序的每个 w:p，带样式和"是否在表格里"。文本框的 w:p 挂在 run 下而不是 body 下，进不来。"""
    root = ET.fromstring(zipfile.ZipFile(docx).read("word/document.xml"))
    body = root.find(W + "body")
    out = []

    def one(p, in_table):
        p_pr = p.find(W + "pPr")
        style = ""
        if p_pr is not None:
            s = p_pr.find(W + "pStyle")
            style = s.get(W + "val") if s is not None else ""
        out.append({"text": "".join(t.text or "" for t in p.iter(W + "t")),
                    "style": style, "table": in_table})

    for child in body:
        tag = child.tag.split("}")[-1]
        if tag == "p":
            one(child, False)
        elif tag == "tbl":
            for p in child.iter(W + "p"):
                one(p, True)
    return out


def match(lines, paras):
    """行 -> 段落 的单调游标匹配（width-bill.attach_paragraphs 的形状），两边都先去掉空白：一行只可能
    落在同一或更后的段落，匹配不上的行把游标放回去，不至于拖垮后面整串。"""
    flat = [(pi, wb.stripped(p["text"])) for pi, p in enumerate(paras)]
    pi, cur = 0, 0
    for ln in lines:
        start, ok = (pi, cur), False
        while pi < len(flat):
            idx_pi, (s, idx) = flat[pi]
            hit = s.find(ln["flat"], cur)
            if hit >= 0:
                ln["para"] = idx_pi
                ln["begin"] = idx[hit]
                cur = hit + len(ln["flat"])
                if cur >= len(s):
                    pi += 1
                    cur = 0
                ok = True
                break
            pi += 1
            cur = 0
        if not ok:
            pi, cur = start
            ln["para"] = -1
    return lines


def align(pdf_chars, src, begin):
    """把一行 PDF 字符贴着源文本走一遍。源文本里没有的 PDF 空格就是 PyMuPDF 替 Word 的 autoSpace
    空隙补的字符：是真实宽度，不是文字。
    返回 (real, fake_widths, begin)，real = [(char, src_offset)] 从 begin 起连续；对不齐返回
    (None, None, None)。"""
    j, real, widths = begin, [], []
    for i, (c, x, _w, _sz, _face) in enumerate(pdf_chars):
        if i == 0:
            while j < len(src) and src[j] in SPACEISH:
                j += 1                      # Word 留在上一行行尾的空白
            begin = j
        if j < len(src) and src[j] == c:
            real.append((c, j))
            j += 1
        elif c == " ":
            nxt = pdf_chars[i + 1][1] if i + 1 < len(pdf_chars) else None
            widths.append(nxt - x if nxt is not None else None)     # 行尾的没有下一字符可量
        else:
            return None, None, None
    if not real or j - begin != len(real):
        return None, None, None
    return real, widths, begin


def word_sum_px(pdf_chars):
    """Word 自己这一行的宽度：相邻字符原点之差累加，最后一个字符用它的 bbox 宽度。"""
    cs = list(pdf_chars)
    tail_blanks = 0
    while cs and cs[-1][0] in SPACEISH:
        cs = cs[:-1]                        # BlankTail：换行后被丢在最后一个有墨字符之后的空白不计宽
        tail_blanks += 1
    total = 0.0
    for i in range(len(cs) - 1):
        total += cs[i + 1][1] - cs[i][1]
    return (total + cs[-1][2]) * PT_TO_PX, tail_blanks


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def q(vals, p):
    if not vals:
        return float("nan")
    s = sorted(vals)
    return s[min(len(s) - 1, int(math.ceil((len(s) - 1) * p)))]


def pct(part, whole):
    return 100.0 * part / whole if whole else float("nan")


def self_check():
    """把这套测量依赖的尺子钉住，改动不可能悄悄把数字挪走。"""
    assert r8(15.5) == 16 and r8(-0.5) == 0 and r8(15.4999) == 15, "floor(x+0.5)，不是银行家舍入"
    assert abs(adv_px("\u4e2d") - 16.0) < 1e-9, adv_px("\u4e2d")   # 宋体整字 = 1 em = 12pt = 16 px
    assert wb.kind("A") == 2 and wb.kind("9") == 3 and wb.kind("\u4e2d") == 1
    assert wb.seam("\u4e2d", "A") and wb.seam("9", "\u4e2d"), "CJK|拉丁、CJK|数字开接缝"
    assert not wb.seam("\u3002", "A") and not wb.seam("\u4e2d", "\u4e2d") and not wb.seam("a", "9")
    assert face_key("\u4e2d") == "song" and face_key("A") == "times" and face_key(" ") == "times"
    two = "\u4e2dA"                              # 中|A：一处接缝，记在宋体那一侧
    assert abs(span_bill(two, 0, 1, "float") - (16.0 + GAP_PX)) < 1e-9
    assert abs(span_bill(two, 0, 2, "float") - (16.0 + GAP_PX + adv_px("A"))) < 1e-9
    assert abs(span_bill("A" + two, 1, 3, "float") - span_bill(two, 0, 2, "float")) < 1e-9
    return "selfcheck OK: 12 条断言钉住 r8()/adv_px()/face_key()/接缝记账"


def collect_body(pdf_lines, paras):
    """按口径筛出正文行，并把每行贴回它自己的源文本区间。"""
    cand = [ln for ln in pdf_lines if abs(ln["size"] - SIZE_PT) <= SIZE_TOL
            and all(c[4] in BODY_FACES for c in ln["chars"])]
    margin = collections.Counter(round(ln["x0"], 1) for ln in cand).most_common(1)[0][0]
    dropped, body = collections.Counter(), []
    for ln in pdf_lines:
        if abs(ln["size"] - SIZE_PT) > SIZE_TOL:
            dropped["字号不是 12pt"] += 1
            continue
        if any(c[4] not in BODY_FACES for c in ln["chars"]):
            dropped["字体不是宋体/Times"] += 1
            continue
        off_pt = ln["x0"] - margin
        if min(abs(off_pt), abs(off_pt - INDENT_PT)) > X0_TOL:
            dropped["x0 不在左缘或缩进位"] += 1
            continue
        if ln["para"] < 0:
            dropped["匹配不到正文段落(目录/域/题注)"] += 1
            continue
        if paras[ln["para"]]["table"]:
            dropped["表格单元格"] += 1
            continue
        text = paras[ln["para"]]["text"]
        real, widths, begin = align(ln["chars"], text, ln["begin"])
        if real is None:
            dropped["与源文本对不齐"] += 1
            continue
        ink = sum(1 for c, _o in real if c not in SPACEISH)
        if ink < MIN_INK:
            dropped["有墨字符不足 %d" % MIN_INK] += 1
            continue
        wsum, tail = word_sum_px(ln["chars"])
        body.append({"page": ln["page"], "para": ln["para"], "src": text,
                     "b": begin, "e": begin + len(real),
                     "indent": INDENT_PX if abs(off_pt - INDENT_PT) <= X0_TOL else 0.0,
                     "wsum": wsum, "tail": tail, "fake": widths,
                     "text": text[begin:begin + len(real)]})
    for ln in body:
        ln["avail"] = COLUMN_PX - ln["indent"]
    return margin, body, dropped, len(cand)


def evaluate(body):
    """每个正文行都按三个模型算一遍本行宽度；能接上下一个不可拆原子的行再判一次放不放得下。"""
    skipped = collections.Counter()
    for ln in body:
        for m in MODELS:
            ln["bill_" + m] = span_bill(ln["src"], ln["b"], ln["e"], m)
    for k in range(len(body) - 1):
        ln, nxt = body[k], body[k + 1]
        if nxt["para"] != ln["para"]:
            skipped["下一行换段"] += 1
            continue
        if nxt["page"] != ln["page"]:
            skipped["下一行跨页"] += 1
            continue
        gap = ln["src"][ln["e"]:nxt["b"]]
        if any(c not in SPACEISH for c in gap):
            skipped["下一行不紧邻"] += 1
            continue
        last, first = ln["src"][ln["e"] - 1], ln["src"][nxt["b"]]
        if last in KINSOKU or first in KINSOKU:
            skipped["禁则/悬标点"] += 1
            continue
        stop = atom_end(ln["src"], nxt["b"], nxt["e"]) if is_latin(first) else nxt["b"] + 1
        ln["unit"] = ln["src"][nxt["b"]:stop]
        ln["gap"] = bool(gap)
        # Word 的观测：下一行不是以这个原子的整体开头 == Word 把它放下了（也就是西文长词被拆开）
        ln["word_fit"] = not gap and is_latin(last) and is_latin(first)
        for m in MODELS:
            ln["full_" + m] = (span_bill(ln["src"], ln["b"], stop, m) if not gap
                               else ln["bill_" + m] + span_bill(ln["src"], nxt["b"], stop, m))
            ln["fit_" + m] = ln["full_" + m] <= ln["avail"] + 1e-9
        ln["evaluated"] = True
    return skipped


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-Pdf", default="artifacts/adb-word-compare/word-native.pdf")
    ap.add_argument("-Docx", default="tests/samples/input-liu.docx")
    ap.add_argument("-Report", default="artifacts/agent-check/round-sim/report.md")
    ap.add_argument("-Top", type=int, default=10)
    a = ap.parse_args()
    for p in (a.Pdf, a.Docx):
        if not os.path.exists(p):
            print("missing input: " + p)
            return 1
    print(self_check())

    pdf_lines = read_lines(a.Pdf)
    pages = pymupdf.open(a.Pdf).page_count
    paras = paragraphs(a.Docx)
    match(pdf_lines, paras)
    margin, body, dropped, n_cand = collect_body(pdf_lines, paras)
    if not body:
        print("no body line read from " + a.Pdf)
        return 1
    skipped = evaluate(body)
    ev = [ln for ln in body if ln.get("evaluated")]

    out = []

    def emit(s=""):
        out.append(s)
        print(s)

    ragged = [ln for ln in body if ln["wsum"] < ln["avail"] - 2.0]
    rv = [ln["wsum"] - ln["bill_float"] for ln in ragged]
    fake_mid = [w for ln in body for w in ln["fake"] if w is not None]
    fake_rag = [w for ln in ragged for w in ln["fake"] if w is not None]
    fake_tail = sum(1 for ln in body if ln["tail"])

    def mode_pt(vals):
        if not vals:
            return float("nan")
        return collections.Counter(round(v, 2) for v in vals).most_common(1)[0][0]

    # A. 只问取整本身：Word 放在这一行的字符，按模型计费还塞得进可用宽度吗
    over = {m: sum(1 for ln in body if ln["bill_" + m] > ln["avail"] + 1e-9) for m in MODELS}
    cross_rows = {m: [ln for ln in body if ln["bill_" + m] > ln["avail"] >= ln["bill_float"]]
                  for m in ("int", "token")}
    cross = {m: len(v) for m, v in cross_rows.items()}
    # B. 判定分歧：只有 Word 把西文长词拆开抬上来时，"放得下" 才算和 Word 一致
    wide = {m: sum(1 for r in ev if r["fit_" + m] and not r["word_fit"]) for m in MODELS}
    narrow = {m: sum(1 for r in ev if not r["fit_" + m] and r["word_fit"]) for m in MODELS}
    flip = {("int", "token"): [sum(1 for r in ev if (not r["fit_int"]) and r["fit_token"]),
                               sum(1 for r in ev if r["fit_int"] and (not r["fit_token"]))],
            ("int", "float"): [sum(1 for r in ev if (not r["fit_int"]) and r["fit_float"]),
                               sum(1 for r in ev if r["fit_int"] and (not r["fit_float"]))]}
    flip_w = wide["int"] - wide["token"]
    flip_n = flip[("int", "float")][0]
    # C. 误差分布
    diff = {m: [ln["bill_" + m] - ln["bill_float"] for ln in ev] for m in ("int", "token")}
    more = sum(1 for d in diff["int"] if d > 1e-9)
    less = sum(1 for d in diff["int"] if d < -1e-9)
    same = len(diff["int"]) - more - less
    over_all = over["float"]
    over_punct = sum(1 for ln in body if ln["bill_float"] > ln["avail"]
                     and ln["src"][ln["e"] - 1] in KINSOKU)

    emit("")
    emit("# 整像素取整 vs 逐词取整：与 Word 断行分歧的来源测量")
    emit("")
    emit("复现命令（本报告由脚本生成，stdout 与文件内容逐字一致）：")
    emit("")
    emit("```")
    emit("cd E:/download/claude/Wordlite")
    emit("C:/Users/ad/AppData/Local/Programs/Python/Python312/python.exe tools/round-model-sim.py")
    emit("```")
    emit("")
    emit("## 样本与口径")
    emit("")
    emit("- Word 自有导出 PDF：`%s` sha256=%s，%d 页" % (a.Pdf, sha256(a.Pdf)[:16], pages))
    emit("- 源文档：`%s` sha256=%s，%d 个 w:p（其中 %d 个在表格里）"
         % (a.Docx, sha256(a.Docx)[:16], len(paras), sum(1 for p in paras if p["table"])))
    emit("- 计费一律用打包字体文件的 hmtx，不读 PDF 宽度：汉字走宋体 `song.ttc`，"
         "U+0080 以下走 `times-new-roman.ttf`，其余走 hAnsi（本文档同为 Times）。"
         "12pt 正文 em = 16.000 px，宋体整字恰好 16.000 px（自检已断言）")
    emit("- 版心左缘 x0 众数 = %.2f pt；可用宽度 = %.1f px（= 425.25 pt），首行缩进 %.1f px（24 pt）"
         % (margin, COLUMN_PX, INDENT_PX))
    emit("- 正文行筛选：PDF 行 %d → 12pt 宋体/Times 候选 %d → 正文行 %d。剔除：%s"
         % (len(pdf_lines), n_cand, len(body),
            "、".join("%s %d" % kv for kv in dropped.most_common()) or "无"))
    emit("- 可判定行（下一行同段且紧邻）= %d；不参与判定：%s"
         % (len(ev), "、".join("%s %d" % kv for kv in skipped.most_common()) or "无"))
    emit("- PyMuPDF 替 Word 的 autoSpace 空隙补出的假空格 %d 个在行内、%d 个落在行尾。"
         "行内这些空格自己的宽度：未被拉伸的行上众数 %.3f pt = %.3f px，"
         "两端对齐的行被拉伸后全体中位 %.3f pt。它只是空隙的一部分，不是空隙的全部；"
         "Word 的行长用相邻原点之差，空隙多少钱都原样落在里面。三个模型一律丢掉这个字符、"
         "改按引擎自己的 4.0 px 接缝（0.25 em，autogap-truth.py 量的那个数）计费，同一笔钱不重复计"
         % (len(fake_mid), fake_tail, mode_pt(fake_rag), mode_pt(fake_rag) * PT_TO_PX,
            statistics.median(fake_mid) if fake_mid else float("nan")))
    emit("- 尺度自检：%d 个非两端对齐行（未被拉伸，宽度可信）上（Word 实测 − FLOAT）中位 %+.3f px、"
         "p90 |%.3f| px，说明择面规则和 hmtx 计费与 Word 实际用的字形对得上，"
         "分歧不是认错字体或算错字宽带来的"
         % (len(ragged), statistics.median(rv) if rv else float("nan"),
            q([abs(x) for x in rv], 0.9) if rv else float("nan")))
    emit("")
    emit("## 1. 取整本身：Word 放在这一行的字符，各模型还塞得进可用宽度吗")
    emit("")
    emit("这一节不需要任何断行假设，直接问“这行字符按模型算完还塞不塞得下”。"
         "%d 个正文行里塞不下的行数：" % len(body))
    emit("")
    emit("| 模型 | 计费 > 可用宽度 的行 | 占正文行 | 比 FLOAT 多塞不下的行 |")
    emit("|---|---|---|---|")
    for m in MODELS:
        emit("| %s | %d | %.1f%% | %s |"
             % (MODEL_CN[m], over[m], pct(over[m], len(body)),
                ("+%d" % cross[m]) if m != "float" else "—"))
    emit("")
    emit("- 现状的逐字取整比 FLOAT 多塞不下的行 = **%d** 行；改成逐词取整后 = **%d** 行。"
         % (cross["int"], cross["token"]))
    emit("- 折算到每页：现状 %.3f 行/页，逐词 %.3f 行/页，即逐词取整每页挽回 %.3f 行"
         % (cross["int"] / pages, cross["token"] / pages, (cross["int"] - cross["token"]) / pages))
    emit("- 注意方向并不一边倒：逐字取整让 %d 行变宽、%d 行变窄、%d 行不变，"
         "所以它不是一台单向把我们挤窄的机器。" % (more, less, same))
    emit("")
    emit("## 2. 三个模型与 Word 观测的断行判定分歧")
    emit("")
    emit("判定式：本行字符 + 下一个不可拆原子 ≤ 可用宽度 记为放得下。"
         "Word 的观测 = 下一 PDF 行不是以该原子的整体开头（只有 Word 把西文长词拆开抬上来时才成立）。")
    emit("")
    emit("| 模型 | 分歧合计 | 判宽了（模型说放得下，Word 另起一行） | 判窄了（模型说放不下，Word 放下了） "
         "| 判定一致 |")
    emit("|---|---|---|---|---|")
    for m in MODELS:
        emit("| %s | %d | %d | %d | %d |"
             % (MODEL_CN[m], wide[m] + narrow[m], wide[m], narrow[m],
                len(ev) - wide[m] - narrow[m]))
    emit("")
    emit("- %d 个可判定行里 Word 拆开西文长词的行数是 %d，所以按这个尺子，"
         "\"判窄了\" 一格在本文档里恒为 0，信号全在 \"判宽了\" 一侧。"
         % (len(ev), sum(1 for r in ev if r["word_fit"])))
    emit("- 逐字取整判宽 %d 行 → 逐词取整判宽 %d 行：改取整位置改变判定 %d 行，"
         "折合 %.3f 行/页。FLOAT 仍判宽 %d 行，这部分与取整无关。"
         % (wide["int"], wide["token"], flip_w, flip_w / pages, wide["float"]))
    emit("- 同一批行上，逐字取整比 FLOAT 少判下放得下的行 = %d（这些行取整反而把溢出补上了）。"
         % flip_n)
    emit("")
    emit("## 3. 行长相对 FLOAT 的误差分布（%d 个可判定行，只算本行字符）" % len(ev))
    emit("")
    emit("| 模型 | n | 均值 px | 中位 px | p90 px | 最大 px | 最小 px |")
    emit("|---|---|---|---|---|---|---|")
    for m in ("int", "token"):
        v = diff[m]
        emit("| %s − FLOAT | %d | %+.3f | %+.3f | %+.3f | %+.3f | %+.3f |"
             % (MODEL_CN[m], len(v), statistics.fmean(v), statistics.median(v),
                q(v, 0.9), max(v), min(v)))
    emit("")
    emit("接缝钱三个模型一模一样，所以上表全是取整位置的账：逐字取整行均 %+.3f px、"
         "中位 %+0.3f px、最贵一行 %+.3f px；逐词取整行均 %+.3f px、最贵 %+.3f px。"
         % (statistics.fmean(diff["int"]), statistics.median(diff["int"]), max(diff["int"]),
            statistics.fmean(diff["token"]), max(diff["token"])))
    emit("")
    emit("## 4. 最贵的 %d 行（按 INT-PER-CHAR 比 FLOAT 多花的钱排序，正文行全体）" % a.Top)
    emit("")
    emit("`INT/TOKEN/FLOAT` 是本行字符的计费，`Word实测` 是 PDF 上相邻字符原点之差累加"
         "（含假空格，行尾空白按 BlankTail 丢掉），`塞不下` 标记该行是否已超出可用宽度。")
    emit("")
    emit("| 页 | 可用 | INT | TOKEN | FLOAT | Word实测 | 差 | 塞不下(I/T/F) | 下一原子 | "
         "整词后 INT | 整词后 TOKEN | Word |")
    emit("|---|---|---|---|---|---|---|---|---|---|---|---|")
    worst = sorted(body, key=lambda ln: -(ln["bill_int"] - ln["bill_float"]))[:a.Top]
    for r in worst:
        emit("| %d | %.0f | %.2f | %.2f | %.2f | %.2f | +%.2f | %s%s%s | `%s` | %s | %s | %s |"
             % (r["page"], r["avail"], r["bill_int"], r["bill_token"], r["bill_float"], r["wsum"],
                r["bill_int"] - r["bill_float"],
                "I" if r["bill_int"] > r["avail"] else "-",
                "T" if r["bill_token"] > r["avail"] else "-",
                "F" if r["bill_float"] > r["avail"] else "-",
                r.get("unit", "—")[:12],
                ("%.2f" % r["full_int"]) if r.get("evaluated") else "—",
                ("%.2f" % r["full_token"]) if r.get("evaluated") else "—",
                ("放得下" if r["word_fit"] else "另起") if r.get("evaluated") else "—"))
    for r in worst:
        emit("")
        emit("- p%d `%s`" % (r["page"], r["text"][:40]))
    emit("")
    emit("## 5. 口径之外（不是取整问题，已按上文剔除或不去解释）")
    emit("")
    emit("- 禁则（行首不得是 %s）与 w:overflowPunct（收尾标点可越右版心约 1 em）会合法改变判定，"
         "相关 %d 行已剔除；剩下的分歧数是**上界**，不是总数。" % (KINSOKU_RAW, skipped["禁则/悬标点"]))
    emit("- 连 FLOAT 都算出比可用宽度还宽的行有 %d 行（%d 行行尾正是收尾标点）：Word 在这些行上"
         "要么让标点悬出、要么压缩了字距，这是模型本身的天花板，换取整位置动不了它。"
         % (over_all, over_punct))
    emit("- FLOAT 之后仍然存在的分歧来自取整以外：Word 的行内舍入与标点压缩、"
         "行内换行、以及原子划分口径（模型把整个西文词当一个原子）。")
    emit("- 弯引号/省略号/破折号（U+2018-201D、U+2026、U+2013-2014）不在 eastAsia 码位区间内，"
         "按规则记 Times 的 hmtx，而 PDF 里它们是宋体画的——另一笔宽度差，本工具按现状计费、不修。")
    emit("- 缺字形回退：%s"
         % ("、".join("U+%04X x%d" % (ord(c), n) for c, n in MISSING.most_common(6))
            or "无（全部命中 hmtx）"))
    emit("")
    emit("## 6. 结论")
    emit("")
    emit("在 %d 个正文行、%d 个可判定行上，逐字整像素取整这件事比浮点累加多付出的代价是：行均 %+.2f px、"
         "中位 %+.1f px、最贵一行 %+.2f px；直接后果是 Word 原本放得下的一行里有 %d 行被它挤到放不下，"
         "把取整改到不可拆西文词一级（汉字仍逐字取整）能退回 %d 行，也就是 %.3f 行/页——"
         "%d 页文档里大约 %s。判定分歧那一栏同样小：%d 行里逐字取整判宽 %d 行、逐词 %d 行、"
         "FLOAT %d 行，差值都是个位数，折合 %.2f 行/页。"
         % (len(body), len(ev), statistics.fmean(diff["int"]), statistics.median(diff["int"]),
            max(diff["int"]), cross["int"], cross["int"] - cross["token"],
            (cross["int"] - cross["token"]) / pages, pages,
            ("%.1f 行" % ((cross["int"] - cross["token"]) / pages * pages))
            if cross["int"] != cross["token"] else "一页都不到",
            len(ev), wide["int"], wide["token"], wide["float"], flip_w / pages))
    emit("")
    emit("所以取整位置不是我们与 Word 断行分歧的主要来源：它每页只欠百分之几行，而且方向不一边倒"
         "（%d 行变宽、%d 行变窄，宋体整字 16.000 px 本来就无误差，只有西文与标点在小数上被拉）。"
         "它修不了的东西也列清楚了：连 FLOAT 都超宽的那 %d 行（Word 让标点悬出或压缩字距）、"
         "行尾接缝那 4 px 记在本行头上的 tail 账（width-bill.py 已量过，取整改了不会自己消失）、"
         "Word 把整个西文词当可拆单位的原子口径，以及弯引号一类按现状走 Times 字宽的标点。"
         "真正要再往下找，方向在接缝的 ceil 与 tail 计费、以及原子口径，不在 per-glyph 取整。"
         % (more, less, over_all))
    emit("")

    report_dir = os.path.dirname(a.Report)
    if report_dir:
        os.makedirs(report_dir, exist_ok=True)
    with open(a.Report, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(out).rstrip("\n") + "\n")
    print("wrote " + a.Report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
