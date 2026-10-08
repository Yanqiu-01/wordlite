#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/script-edge-audit.py -- 静态找出"引擎会在字母数字串内部放一个可断边界"的所有位置。

为什么需要它：真机上量死过两件事（docs/layout-parity-target.md 第 24.3、25 节）：
  1) StaticLayout 把 ReplacementSpan 的边界当成可断点（同一个字符，挂上 span 就被断，缩放与否无关）；
  2) Android 10 不让我们把自己的断行规则交给平台（setBreakIterator 反射 NoSuchMethodException）。
所以只要引擎在一个 Word 不许断的位置挂了 span，那一行就可能从那里断。这个脚本不跑手机，
只把源文里所有这种位置一次性列全：段落号、串、边界两侧各是什么 run 属性。

Word 那边同口径的真值：tools/break-class-truth.py 对 Word 导出 PDF 全篇 803 行分类，串内断 = 0；
tools/word-break-truth.ps1 量到 w:wordWrap 缺省/true 时 [A-Za-z0-9] 整串不可断。

什么算"Word 不许断的一串"：连续的拉丁字母/数字，加上紧贴的上下标字符（U+00B2/00B3/00B9、U+2070-2089、
U+03BC 这类），中间没有空格、没有被 "-" 分开（"-" 之后 Word 允许断，见 hyphen-edge 用例）。

    py tools/script-edge-audit.py                     # 全篇列出来
    py tools/script-edge-audit.py -Docx tests/samples/input-liu.docx -Quiet

退出码：还有这种位置 = 1，一个都没有 = 0（可以当断言用）。
"""
import argparse
import re
import sys
import zipfile

RUN = re.compile(r"<w:r(?:\s[^>]*)?>(.*?)</w:r>", re.S)
TEXT = re.compile(r"<w:t(?:\s[^>]*)?>(.*?)</w:t>", re.S)
VERT = re.compile(r'<w:vertAlign\s+w:val="(superscript|subscript)"/>')
POS = re.compile(r'<w:position\s+w:val="(-?\d+)"')
UL = re.compile(r'<w:u\s+w:val="(?!none)')
COLOR = re.compile(r'<w:color\s+w:val="([0-9A-Fa-f]{6})"')
HL = re.compile(r'<w:highlight\s+w:val="(?!none)')
SZ = re.compile(r'<w:sz\s+w:val="(\d+)"')
FONTS = re.compile(r'<w:rFonts([^/>]*)/>')
ATTR = re.compile(r'w:(ascii|eastAsia|hAnsi|cs)="([^"]*)"')
ENT = {"&amp;": "&", "&lt;": "<", "&gt;": ">", "&quot;": '"', "&apos;": "'"}

SCRIPT_CODE = set([0x00B9, 0x00B2, 0x00B3]) | set(range(0x2070, 0x2090)) | set(range(0x00D7, 0x00D8))


def unesc(s):
    return re.sub(r"&#(\d+);", lambda m: chr(int(m.group(1))),
                  re.sub(r"&[a-z]+;", lambda m: ENT.get(m.group(0), m.group(0)), s))


def para_runs(xml):
    """[[ (text, kind) ], ...] per paragraph; kind is the run properties the engine hangs spans on."""
    out = []
    for p in re.findall(r"<w:p(?:\s[^>]*)?>.*?</w:p>", xml, re.S):
        runs = []
        for r in RUN.findall(p):
            body = "".join(TEXT.findall(r))
            if not body:
                continue
            fonts = {}
            fm = FONTS.search(r)
            if fm:
                fonts = dict((k, v) for k, v in ATTR.findall(fm.group(1)))
            kind = {
                "script": VERT.search(r).group(1) if VERT.search(r) else "",
                "position": POS.search(r).group(1) if POS.search(r) else "",
                "underline": bool(UL.search(r)),
                "color": COLOR.search(r).group(1) if COLOR.search(r) else "",
                "highlight": bool(HL.search(r)),
                "sz": SZ.search(r).group(1) if SZ.search(r) else "",
                "fonts": tuple(sorted(fonts.items())),
                "unicode": any(ord(c) in SCRIPT_CODE for c in unesc(body)),
            }
            runs.append((unesc(body), kind))
        if runs:
            out.append(runs)
    return out


def glued(ch):
    """A character Word keeps glued to its neighbours inside a Latin/digit token."""
    o = ord(ch)
    return ch.isascii() and ch.isalnum() or o in SCRIPT_CODE or ch == "\u03bc"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-Docx", default="tests/samples/input-liu.docx")
    ap.add_argument("-Quiet", action="store_true")
    ap.add_argument("-Show", type=int, default=25)
    a = ap.parse_args()
    xml = zipfile.ZipFile(a.Docx).read("word/document.xml").decode("utf8")
    hits = 0
    shown = 0
    by_kind = {}
    for pi, runs in enumerate(para_runs(xml)):
        # flat string + per-character kind, so a boundary is read at the character level
        chars = []
        for text, kind in runs:
            for c in text:
                chars.append((c, kind))
        for i in range(1, len(chars)):
            left, lk = chars[i - 1]
            right, rk = chars[i]
            if not glued(left) or not glued(right):
                continue
            if lk == rk:
                continue
            diff = tuple(sorted(k for k in lk if lk[k] != rk[k]))
            if not diff:
                continue
            hits += 1
            key = "+".join(d for d in diff)
            by_kind[key] = by_kind.get(key, 0) + 1
            if not a.Quiet and shown < a.Show:
                shown += 1
                ctx = "".join(c for c, _ in chars[max(0, i - 12):i + 12])
                print("  para=%d cut=%d  %r  %s|%s  %s -> %s"
                      % (pi, i, ctx, lk["script"] or ("U" if lk["unicode"] else "-"),
                         rk["script"] or ("U" if rk["unicode"] else "-"),
                         {k: v for k, v in lk.items() if k in diff},
                         {k: v for k, v in rk.items() if k in diff}))
    print("span_edges_inside_a_token=%d" % hits)
    for k, v in sorted(by_kind.items(), key=lambda kv: -kv[1]):
        print("  %-40s %d" % (k, v))
    return 1 if hits else 0


sys.exit(main())
