#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/cjk-cjk-gap.py -- 我们在一堆汉字中间断了行、Word 那行根本没断：逐条对账，看是谁的账错了。

背景：tools/break-agreement.py 里最大的一类是 phone_only cjk|cjk（runin1：15 处）。它只说明
"我们的行末落在两个汉字之间，而 Word 在这里不断"，没说是为什么。本工具把这 15 处逐条摊开：

  1. 手机自己交上来的那一行的账（<Capture>/new/lines-all.tsv）：lineNaturalPx 自然宽、
     lineRoomPx 还差多少才够下一个串、nextCharPx/nextRunPx/nextRunChars 下一个字与不可断串的价格、
     hangOverPx 挂出边界多少、lineStretchPx/openGaps/autoGaps 这一行为对齐摊开了多少；
  2. Word 那一行的真坐标（word-pdf-lines.tsv，px = pt*96/72）：x0/x1 与正文右边界的差，
     用来判断 Word 有没有把标点挂到版心外、它的行有没有摊到边界、首行有没有缩进；
  3. 两行的起点是不是同一个字：起点不同，说明这一处只是上一行分歧的后遗症；
  4. width-bill.py 的估价（Word 内嵌字体的 hmtx + 中英自动间距）当第三方：我们收的钱、
     估价收的钱、Word 实际装下的字数，三个数摆在一起才看得出是"我们引擎自己算歪"还是"价格表本身就贵"。

断点集合、分类、配对一律调用 tools/break-agreement.py 里的同一套函数（同一把尺子），不另起口径。

列口径（读 lines-all.tsv 表头核对过）：lineRoomPx = 版心 - lineNaturalPx，即"再塞东西还剩多少"
（runin1 反推：自然宽 560 -> room 7.0；544 -> 23.0；567 -> 0.0）。所以
short = nextRunPx - lineRoomPx 就是"这一行为了装下下一个串还差几像素"，是引擎自己的硬数字。

只读，不写任何文件。

用法：
    py tools/cjk-cjk-gap.py -Capture artifacts/agent-layout-verify/runin1
    py tools/cjk-cjk-gap.py -Capture artifacts/agent-layout-verify/guard1 -Top 40
"""
import argparse
import collections
import csv
import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
COLUMN = 566.9333          # 版心宽 px@96dpi，与 tools/right-edge-audit.py 同一条
SPACEISH = (" ", "\t", "\u3000", "\xa0")
PT = 96.0 / 72.0
HANG_PX = 8.0              # Word 行末超出右边界这么多 px 才算"把标点挂出了版心"
ROUND_PX = 3.0             # 我们差这么多 px 以内算取整级误差

CAUSE_A = "A 上一行 Word 把行末标点挂出了版心"
CAUSE_B = "B 级联：这一行的起点早就和 Word 不一样"
CAUSE_C = "C 同一行里我们算贵了 1~3 px"
CAUSE_D = "D 同一行里我们算贵了 4 px 以上"
CAUSE_E = "E Word 那一行算得比我们贵"
CAUSE_ORDER = [CAUSE_A, CAUSE_B, CAUSE_C, CAUSE_D, CAUSE_E]
VERDICT = {
    CAUSE_A: "改几何不改规则：把行末全角标点按 Word 那样整字挂出边界（约 16 px），"
             "上游那行就能装下引用号，这一处跟着消失",
    CAUSE_B: "不用动断行规则：错在上一行那一处，先把上一行的账修对",
    CAUSE_C: "改计费不改规则：只差 1~3 px，是字宽/自动间距取整，不是断行规则不认这个点",
    CAUSE_D: "改计费不改规则：差到四分之一个字以上，得找出这行里哪个串我们收贵了",
    CAUSE_E: "改计费/几何不改规则：方向反过来——同一串字我们收得比 Word 便宜（或让字挂到了界外），"
             "才多装了一个字",
}


def load(name, mod):
    spec = importlib.util.spec_from_file_location(mod, os.path.join(HERE, name))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass


def strip(s):
    return "".join(c for c in s if c not in SPACEISH)


def num(row, col):
    """老采样可能缺列，缺了就回 None。"""
    try:
        return float(row[col])
    except (KeyError, TypeError, ValueError):
        return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-Capture", default="artifacts/agent-layout-verify/runin1")
    ap.add_argument("-Docx", default="tests/samples/input-liu.docx")
    ap.add_argument("-Pdf", default="artifacts/agent-typeset/pdf-truth/input-liu.pdf")
    ap.add_argument("-WordTsv", default="artifacts/agent-layout-verify/word-pdf-lines.tsv")
    ap.add_argument("-SizePt", type=float, default=12.0)
    ap.add_argument("-Top", type=int, default=30, help="每条归因最多列几处")
    a = ap.parse_args()

    def path(rel):
        return rel if os.path.isabs(rel) else os.path.join(ROOT, rel)

    cap = path(a.Capture)
    ba = load("break-agreement.py", "ba_for_cjkgap")
    wb = load("width-bill.py", "wb_for_cjkgap")
    em = a.SizePt * PT
    gap_px = wb.GAP_EM * a.SizePt * PT

    def price(s):
        """width-bill 的估价：Word 内嵌字体的 hmtx + 中英自动间距。只是第三方参照，不是引擎收的钱。"""
        tot, prev = 0.0, None
        for ch in s:
            if prev is not None and wb.seam(prev, ch):
                tot += gap_px
            tot += wb.advance_px(ch, None, a.SizePt)
            prev = ch
        return tot

    # ---- 1. 用同一把尺子取断点 ------------------------------------------------------------
    paras = ba.source_paragraphs(path(a.Docx))
    word = ba.word_layout(path(a.Pdf), paras, a.SizePt)
    lines_tsv = os.path.join(cap, "new", "lines-all.tsv")
    if not os.path.exists(lines_tsv):
        lines_tsv = os.path.join(cap, "lines-all.tsv")
    blocks = ba.phone_blocks(lines_tsv)
    pairs = ba.pair(blocks, word, paras)

    phone = collections.defaultdict(list)
    with open(lines_tsv, encoding="utf-8-sig", newline="") as fh:
        for r in csv.DictReader(fh, delimiter="\t"):
            phone[int(r["paragraph"])].append(r)
    for b in phone:
        phone[b].sort(key=lambda r: int(r["line"]))

    cases, n_phone_only = [], 0
    for blk, pi in pairs:
        raw, idx = word[pi]["raw"], word[pi]["idx"]
        wset = sorted(ba.canon(raw, word[pi]["idx"][e - 1] + 1) for e in word[pi]["cuts"])
        acc, dmap = 0, {}
        for i, ln in enumerate(blk["lines"][:-1]):
            acc += ln["chars"]
            dmap[ba.canon(raw, idx[acc - 1] + 1)] = i
        dset = set(dmap)
        for k in sorted(dset - set(wset)):
            n_phone_only += 1
            cls = ba.classify(raw, k)
            if cls != "cjk|cjk":
                continue
            near = min(wset, key=lambda e: (abs(e - k), e))
            pw = max([e for e in wset if e < near], default=0)       # Word 这一行从哪起
            pdv = max([e for e in dset if e < k], default=0)         # 我们这一行从哪起
            ppw = max([e for e in wset if e < pw], default=0)        # Word 上一行从哪起
            mism = [e for e in sorted((dset | set(wset)) - (dset & set(wset))) if e < k]
            cases.append({"para": pi, "blk": blk["block"], "k": k, "raw": raw, "wset": wset,
                          "dset": dset, "dmap": dmap, "i": dmap[k], "near": near, "pw": pw,
                          "pdv": pdv, "ppw": ppw, "mism": mism,
                          "off": (ba.span_chars(raw, k, near) if near != k else 0)
                                 * (1 if near > k else -1)})
    cases.sort(key=lambda c: (c["blk"], c["k"]))

    # ---- 2. Word 的逐行坐标 ---------------------------------------------------------------
    wl = []
    with open(path(a.WordTsv), encoding="utf-8") as fh:
        for r in csv.DictReader(fh, delimiter="\t"):
            wl.append({"page": int(r["page"]), "line": int(r["line"]),
                       "x0": float(r["x0"]) * PT, "x1": float(r["x1"]) * PT,
                       "size": float(r["size"]), "text": r["text"], "s": strip(r["text"])})
    hist = collections.Counter(round(w["x1"], 1) for w in wl
                              if abs(w["size"] - a.SizePt) < 0.01 and len(w["s"]) >= 18)
    flush = hist.most_common(1)[0][0]
    left = collections.Counter(round(w["x0"], 1) for w in wl
                              if abs(w["size"] - a.SizePt) < 0.01 and len(w["s"]) >= 18
                              ).most_common(1)[0][0]

    def word_line(page, begin_raw, end_raw):
        """按"行首那段文字"在 TSV 里定位 Word 的一行（行尾会被 text[:40] 截掉，行首不会）。"""
        key = strip(raw[begin_raw:begin_raw + 10]) if False else strip(raw[begin_raw:end_raw])[:10]
        if not key:
            return []
        return [w for w in wl if abs(w["page"] - page) <= 1 and w["s"][:10] == key]

    print("capture   = %s" % a.Capture)
    print("比对的段  = %d   我们多切的断点 = %d   其中 cjk|cjk = %d" % (len(pairs), n_phone_only, len(cases)))
    old = os.path.join(cap, "break-agreement.tsv")
    if os.path.exists(old):
        with open(old, encoding="utf-8-sig", newline="") as fh:
            n_old = sum(1 for r in csv.DictReader(fh, delimiter="\t")
                        if r["side"] == "phone_only" and r["cut_class"] == "cjk|cjk")
        print("尺子核对  = %s（break-agreement.tsv 里这一类 %d 条）"
              % ("一致" if n_old == len(cases) else "不一致：它 %d 条" % n_old, n_old))
    print("版心      = %.2f px（%.1f 个汉字）；一个汉字/全角标点 %.2f px；中英自动间距 %.2f px"
          % (COLUMN, COLUMN / em, em, gap_px))
    print("Word 正文右边界 = %.1f px（%d 个 12pt 长行 x1 的众数，ink）；左边界 %.1f px。"
          "下文 hang = 该行 x1 减这个右边界。" % (flush, sum(hist.values()), left))
    print("short     = nextRunPx - lineRoomPx，引擎自己承认的『这一行装下下一个串还差几像素』。")
    print("")

    # ---- 3. 逐条对账 ----------------------------------------------------------------------
    for c in cases:
        raw, k = c["raw"], c["k"]
        rows = phone[c["blk"]]
        L, N = rows[c["i"]], (rows[c["i"] + 1] if c["i"] + 1 < len(rows) else None)
        P = rows[c["i"] - 1] if c["i"] else None
        room, nxt, run = num(L, "lineRoomPx"), num(L, "nextCharPx"), num(L, "nextRunPx")
        nat, hang = num(L, "lineNaturalPx"), num(L, "hangOverPx")
        short = None if (room is None or run is None) else run - room
        line_text = (L.get("lineText") or L.get("lineFull") or "").rstrip()
        lead = len(line_text) - len(line_text.lstrip())
        pure = not any(ch.isascii() and ch.isalnum() for ch in line_text)
        full = len(strip(line_text)) * em
        m_line = price(line_text) + (hang or 0.0)
        nxt_token = L.get("nextRunChars") or ""
        m_plus = m_line + price(nxt_token) + (gap_px if nxt_token and line_text
                                             and wb.seam(line_text[-1], nxt_token[0]) else 0.0)
        our_line = strip(raw[c["pdv"]:k])
        word_line_text = strip(raw[c["pw"]:c["near"]])
        page = word[c["para"]]["page"]
        hits = word_line(page, c["pw"], c["near"])
        phits = word_line(page, c["ppw"], c["pw"]) if c["pw"] else []
        c.update({"room": room, "run": run, "short": short, "nat": nat, "hang": hang,
                  "our_chars": len(our_line), "word_chars": len(word_line_text),
                  "aligned": c["pw"] == c["pdv"], "m_line": m_line, "m_plus": m_plus,
                  "w": hits[0] if len(hits) == 1 else None,
                  "wprev": phits[0] if len(phits) == 1 else None})
        print("blk %3d  L%-2s  %s  Word 的断点比我们%s %d 个字    %s"
              % (c["blk"], L["line"], "【起点同字】" if c["aligned"] else "【起点已错开】",
                 "晚" if c["off"] > 0 else "早", abs(c["off"]),
                 strip(raw[max(0, k - 12):k]) + "|" + strip(raw[k:k + 12])))
        print("  我们 : %2d 字  自然宽 %s  行宽 %s  还能塞 %s px  下一串 %r %s px  挂出 %s  摊开 %s(open %s/auto %s)"
              % (int(L["lineChars"]), nat, num(L, "lineWidthPx"), room, nxt_token, run, hang,
                 num(L, "lineStretchPx"), L.get("openGaps"), L.get("autoGaps")))
        if short is not None:
            print("         -> 装下这串还差 %.2f px（%.2f 个字宽）；width-bill 估价：这行 %.1f px（引擎收 %.1f%s）"
                  "，再加这串 %.1f px = %.1f px，%s"
                  % (short, short / em, m_line, (nat or 0.0) + (hang or 0.0),
                     "，含行首 %d 个空格" % lead if lead else "",
                     m_plus - m_line, m_plus,
                     "估价说装得下" if m_plus <= COLUMN else "估价也说装不下"))
            if pure:
                d = full - ((nat or 0.0) + (hang or 0.0))
                print("         整行都是整宽字：%d 字应为 %.1f px，引擎收到 %.1f px（引擎%s %.1f px）"
                      % (len(strip(line_text)), full, (nat or 0.0) + (hang or 0.0),
                         "少收，差额挂在界外或字宽偏小" if d > 0 else "多收", abs(d)))
        if N:
            print("  下行 : %r" % N["lineText"][:36])
        if c["w"]:
            h = c["w"]
            note = ("摊到了右边界" if abs(h["x1"] - flush) <= 2.0
                    else ("末字越出右边界 %.1f px" % (h["x1"] - flush) if h["x1"] > flush
                          else "离右边界还差 %.1f px" % (flush - h["x1"])))
            print("  Word : %2d 字  x0 %.1f  x1 %.1f  ink %.1f  hang %+.1f  p%dL%d  末字 %r  %s"
                  % (c["word_chars"], h["x0"], h["x1"], h["x1"] - h["x0"], h["x1"] - flush,
                     h["page"], h["line"], h["s"][-1], note))
            if not any(ch.isascii() and ch.isalnum() for ch in word_line_text):
                print("         Word 那行也是整宽字：%d 字 = %.1f px 本体，ink %.1f px（差 %+.1f 是对齐摊开/收拢）"
                      % (c["word_chars"], c["word_chars"] * em, h["x1"] - h["x0"],
                         (h["x1"] - h["x0"]) - c["word_chars"] * em))
            if c["aligned"]:
                print("         同一起点、同一版心：Word 装 %d 字，我们装 %d 字，差 %+d 个字"
                      % (c["word_chars"], c["our_chars"], c["word_chars"] - c["our_chars"]))
            else:
                print("         起点不同（Word 从第 %d 字起，我们从第 %d 字起），字数不可直接比"
                      % (len(strip(raw[:c["pw"]])), len(strip(raw[:c["pdv"]]))))
            if abs(h["x0"] - left) > 4:
                print("         Word 这行起点在 %.1f（比左边界 %.1f 缩进 %.1f px），它的可用宽度比我们窄"
                      % (h["x0"], left, h["x0"] - left))
        else:
            print("  Word : 在 %s 里没定位到这一行（命中 %d 条），只报字数 %d"
                  % (os.path.basename(a.WordTsv), len(hits), c["word_chars"]))
        if c["wprev"]:
            q = c["wprev"]
            tail = q["s"][-4:]
            print("  Word 前一行: %2d 字  x1 %.1f  hang %+.1f  末字 %r  行尾 %r（估 %.1f px）  %r"
                  % (len(strip(raw[c["ppw"]:c["pw"]])), q["x1"], q["x1"] - flush, q["s"][-1],
                     tail, price(tail), q["text"][-14:]))
            if P is not None:
                proom, prun = num(P, "lineRoomPx"), num(P, "nextRunPx")
                pshort = None if (proom is None or prun is None) else prun - proom
                why = ("差 %.2f px 装不下，整串挪到了下一行" % pshort) if (pshort or 0) > 0                     else "余量本来够，是这个位置我们不给断"
                print("             我们前一行 L%s: 还能塞 %s px，下一串 %r %s px -> %s"
                      % (P["line"], proom, P.get("nextRunChars"), prun, why))
        if c["mism"]:
            f0, l0 = c["mism"][0], c["mism"][-1]
            fi = c["dmap"].get(f0)
            side = "我们多切" if f0 in c["dset"] else "Word 多切"
            where = ("我们第 %s 行行末" % rows[fi]["line"]) if fi is not None else "我们这边没有对应行末"
            print("  上游 : 本段第一次错开在偏移 %d（%s，%s，%s）；离这里最近的一次错开在偏移 %d（%s，%s）"
                  % (f0, ba.classify(raw, f0), side, where,
                     l0, ba.classify(raw, l0),
                     "我们多切" if l0 in c["dset"] else "Word 多切"))
        print("")

    # ---- 4. 归因 --------------------------------------------------------------------------
    for c in cases:
        q = c["wprev"]
        hang_prev = (q["x1"] - flush) if q else None
        mark = q["s"][-1] if (q and q["s"]) else ""
        if not c["aligned"] and hang_prev is not None and hang_prev >= HANG_PX and wb.is_cjk_punct(mark):
            c["cause"] = CAUSE_A
        elif not c["aligned"]:
            c["cause"] = CAUSE_B
        elif c["off"] > 0:
            c["cause"] = CAUSE_C if (c["short"] is not None and c["short"] <= ROUND_PX) else CAUSE_D
        else:
            c["cause"] = CAUSE_E

    print("=" * 100)
    print("归因（阈值：Word 行末超出右边界 %.0f px 以上记为挂出；我们 short <= %.0f px 记为取整级）"
          % (HANG_PX, ROUND_PX))
    tally = collections.Counter(c["cause"] for c in cases)
    for name in CAUSE_ORDER:
        ins = [c for c in cases if c["cause"] == name]
        if not ins:
            continue
        print("")
        print("%s  %d 处" % (name, len(ins)))
        print("   判定：%s" % {
            CAUSE_A: "起点不同 + Word 前一行 x1 超出右边界 >= %.0f px + 那行末字是标点" % HANG_PX,
            CAUSE_B: "起点不同，且不是上一条那种挂出",
            CAUSE_C: "起点同字 + Word 多装了字 + 我们 short <= %.0f px" % ROUND_PX,
            CAUSE_D: "起点同字 + Word 多装了字 + 我们 short > %.0f px" % ROUND_PX,
            CAUSE_E: "起点同字 + 是 Word 早断了一个字（我们反而多装）",
        }[name])
        print("   能不能靠断行规则修：%s" % VERDICT[name])
        for c in ins[:a.Top]:
            L = phone[c["blk"]][c["i"]]
            print("      blk %3d L%-2s off %+2d | 我们 %2d 字 自然宽 %s 还能塞 %s px 下一串 %s px short %s | "
                  "Word %2d 字 x1 %.1f hang %s"
                  % (c["blk"], L["line"], c["off"], c["our_chars"], c["nat"], c["room"], c["run"],
                     ("%.2f" % c["short"]) if c["short"] is not None else "?",
                     c["word_chars"], c["w"]["x1"] if c["w"] else float("nan"),
                     ("%+.1f" % (c["w"]["x1"] - flush)) if c["w"] else "?"))
        latin = sum(1 for c in ins
                    if sum(1 for x in (phone[c["blk"]][c["i"]].get("lineText") or "")
                           if x.isascii() and x.isalnum()) >= 10)
        print("   这一类里西文/数字 10 个以上的行：%d/%d" % (latin, len(ins)))
    print("")
    a_b = tally[CAUSE_A] + tally[CAUSE_B]
    print("统计：%d 处里 %d 处（%.0f%%）不是这里出的错——上一行就跟 Word 错开了（A+B）；"
          "真正落在这一行的是 %d 处：C %d、D %d、E %d。"
          % (len(cases), a_b, 100.0 * a_b / max(1, len(cases)),
             len(cases) - a_b, tally[CAUSE_C], tally[CAUSE_D], tally[CAUSE_E]))
    print("另外 %d 处的 Word 那行都恰好摊到右边界（|hang| <= 2 px），也就是两边看着都是齐的，"
          "差别只在哪个字收尾。"
          % sum(1 for c in cases if c["w"] and abs(c["w"]["x1"] - flush) <= 2.0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
