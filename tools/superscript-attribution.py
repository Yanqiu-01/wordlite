r"""tools/superscript-attribution.py -- 把"含上标/下标的行"单独拎出来对账：行高、基线、断点。

"含上标行"的取样口径（一句话）：Word 导出 PDF 的每一行按 tools/break-agreement.py 同一条单调
  行->段落匹配落到 tests/samples/input-liu.docx 的某一段的某个字符区间上，该区间里只要落着一个
  w:vertAlign run（或一个 Unicode 上下标字符），这一行就是含上标行；手机侧同一 block/line 用
  capture 的 lines-geo.tsv 对上去。样本集与第 7 项断点一致率用的是同 102 段，不许另起炉灶。

三把尺：
  行高  Word 的相邻基线距离（PDF 字符 origin）对手机画出来的行 advance（lines-geo.tsv）。
        两边都再减去"同一段自己的无上标行中位"，这一条对照把全篇 -0.767px 的字体行高偏差摘掉，
        剩下的才是上标本身有没有把行顶高。
  断点  与第 7 项同一套断点集合（同一份 PDF 真值、同一份 lines-all.tsv），每个分歧标出它落在第几行、
        那一行有没有上标、上标内容是什么。
  宽度  PDF 里 Word 自己把上标排成多大（span size）与它去掉两端对齐拉伸后的实际占位，
        对 DocxTextLayout.WordScriptSpan 的宽度算法（ceil(OS/2 缩放后的 measureText)）。

用法：
  py tools/superscript-attribution.py                       # 默认 capture=artifacts/agent-layout-verify/sup-base
  py tools/superscript-attribution.py -Capture artifacts/agent-layout-verify/<tag> -Top 15
输出：<OutTsv> 逐行表 + <OutTxt> 汇总表 + 屏幕同样的汇总。
"""
import argparse, collections, csv, importlib.util, json, math, os, re, statistics, sys, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))


def load(name, mod):
    spec = importlib.util.spec_from_file_location(mod, os.path.join(HERE, name))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


ba = load("break-agreement.py", "ba_for_sup")     # 复用第 7 项那把尺的段落匹配
wb = ba.wb
PT_PX = 4.0 / 3.0
SPACEISH = (" ", "\t", "\u3000", "\xa0")
SCRIPT_UNICODE = set("\u2080\u2081\u2082\u2083\u2084\u2085\u2086\u2087\u2088\u2089"
                     "\u00b9\u00b2\u00b3\u2070\u2074\u2075\u2076\u2077\u2078\u2079")


def script_spans_by_paragraph(docx):
    """{para_index: [(begin_stripped, end_stripped, 'sup'|'sub', text, declared_half_pt)]}.

    Offsets are in the same unit the device capture and the PDF walk use: non-space characters of the
    paragraph text as it sits in word/document.xml. The paragraph text is rebuilt exactly the way
    tools/width-bill.py does so the offsets land on the same characters.
    """
    with zipfile.ZipFile(docx) as z:
        xml = z.read("word/document.xml").decode("utf-8")
    out = {}
    for pi, p in enumerate(re.findall(r"<w:p[ >].*?</w:p>", xml, re.S)):
        body = re.sub(r"<w:instrText[^>]*>.*?</w:instrText>", "", p, flags=re.S)
        text = "".join(re.findall(r"<w:t(?: [^>]*)?>(.*?)</w:t>", body, re.S))
        text = (text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&quot;", '"').replace("&apos;", "'"))
        s, idx = wb.stripped(text)
        pos, spans = 0, []
        for r in re.findall(r"<w:r[ >].*?</w:r>", body, re.S):
            rt = "".join(re.findall(r"<w:t(?: [^>]*)?>(.*?)</w:t>", r, re.S))
            rt = (rt.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                     .replace("&quot;", '"').replace("&apos;", "'"))
            va = re.findall(r'<w:vertAlign w:val="(\w+)"', r)
            # w:vertAlign val="baseline" is an explicit turn-off, not a script: Word draws it full size.
            kind = ("sup" if va[0] == "superscript" else
                    "sub" if va[0] == "subscript" else None) if va else None
            half = re.findall(r'<w:sz w:val="(\d+)"', r)
            if kind and rt:
                raw_a, raw_b = pos, pos + len(rt)
                a = next((k for k, c in enumerate(s) if idx[k] >= raw_a), None)
                b = next((k for k in range(len(s) - 1, -1, -1) if idx[k] < raw_b), None)
                if a is not None and b is not None and b >= a:
                    spans.append([a, b + 1, kind, rt, int(half[0]) if half else 0])
            pos += len(rt)
        if spans:
            out[pi] = spans
    return out


def read_lines_with_baseline(pdf):
    """autogap-truth.read_lines() plus the base baseline of every line, in PDF points.

    The base baseline is the MAXIMUM char origin: a superscript sits above its line, so the tallest
    origin on the row is the row's own baseline. That is the number Word's line pitch is measured on.
    """
    import pymupdf
    doc = pymupdf.open(pdf)
    lines = []
    for pno, page in enumerate(doc, 1):
        for b in page.get_text("rawdict")["blocks"]:
            for ln in b.get("lines", []):
                chars, sizes = [], collections.Counter()
                for s in ln.get("spans", []):
                    face = s["font"].split("+")[-1]
                    for ch in s["chars"]:
                        if ch["c"] in ("\n", "\r"):
                            continue
                        chars.append((ch["c"], ch["origin"][0], s["size"], face, ch["origin"][1]))
                        sizes[round(s["size"], 2)] += len(s["chars"])
                if len(chars) < 2:
                    continue
                lines.append({"page": pno, "size": sizes.most_common(1)[0][0],
                              "chars": chars,
                              "baseline": max(c[4] for c in chars),
                              "small": min(c[4] for c in chars),
                              "min_size": min(round(c[2], 2) for c in chars)})
    return lines


class _Shim(object):
    def read_lines(self, pdf):
        return read_lines_with_baseline(pdf)


def word_lines_with_geometry(ba_mod, pdf, paras, size_pt):
    """The 7th metric's own paragraph matching, but each line keeps its baseline."""
    ba_mod.wb.load_sibling = lambda: _Shim()
    layout = ba_mod.word_layout(pdf, paras, size_pt)
    for pi, w in layout.items():
        for k, ln in enumerate(w["lines"]):
            # word_layout rebuilds the line dict, so the geometry read in read_lines_with_baseline
            # has to be recomputed from the surviving character tuples.
            ln["k"] = k
            ln["baseline"] = max(c[4] for c in ln["chars"])
            ln["min_size"] = round(min(c[2] for c in ln["chars"]), 2)
    return layout
def device_geo(tsv):
    """{(block, line): advance/baseline/page/text} from the capture lines-geo.tsv (document px)."""
    geo = {}
    with open(tsv, encoding="utf-8") as fh:
        for row in csv.DictReader(fh, delimiter="\t"):
            if row.get("kind") != "text":
                continue
            try:
                geo[(int(row["block"]), int(row["line"]))] = {
                    "page": int(row["page"]), "top": float(row["top"]),
                    "advance": float(row["advance"]), "baseline": float(row["baseline"]),
                    "bottom": float(row["bottom"]), "text": row.get("text", "")}
            except ValueError:
                continue
    return geo


def device_lines(blk):
    """[(k, cut_offset_stripped, page)] for one device block, cuts after each line but the last."""
    acc, out = 0, []
    for k, line in enumerate(blk["lines"]):
        acc += line["chars"]
        out.append((k, acc, line["page"]))
    return out


def flags(spans, a, b):
    """Which script spans sit inside [a, b) stripped offsets."""
    return [s for s in spans if s[0] < b and s[1] > a]


def median(values):
    return statistics.median(values) if values else None


def script_bill_ours(text, base_px, scale, advance_em):
    """What WordScriptSpan.getSize books: ceil(measure(text) at textSize x OS/2 scale)."""
    return math.ceil(sum(advance_em) * base_px * scale)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-Capture", default="artifacts/agent-layout-verify/sup-base")
    ap.add_argument("-Docx", default="tests/samples/input-liu.docx")
    ap.add_argument("-Pdf", default="artifacts/agent-typeset/pdf-truth/input-liu.pdf")
    ap.add_argument("-SizePt", type=float, default=12.0)
    ap.add_argument("-OutTsv", default="")
    ap.add_argument("-OutTxt", default="")
    ap.add_argument("-Top", type=int, default=15)
    a = ap.parse_args()
    cap = a.Capture.rstrip("/")
    out_tsv = a.OutTsv or os.path.join(cap, "superscript-lines-join.tsv")
    out_txt = a.OutTxt or os.path.join(cap, "superscript-attribution.txt")

    paras = ba.source_paragraphs(a.Docx)
    spans = script_spans_by_paragraph(a.Docx)
    word = word_lines_with_geometry(ba, a.Pdf, paras, a.SizePt)
    blocks = ba.phone_blocks(os.path.join(cap, "new", "lines-all.tsv"))
    pairs = ba.pair(blocks, word, paras)
    geo = device_geo(os.path.join(cap, "new", "lines-geo.tsv"))
    audit = width_audit(a.Pdf, paras, spans, a.SizePt, a.SizePt * PT_PX)

    rows, breaks = [], []
    for blk, pi in pairs:
        w = word[pi]
        sp = spans.get(pi, [])
        dlines = device_lines(blk)
        # --- per-side plain-line control: the paragraph's own advance with no script on the line ---
        wadv, dadv = {}, {}
        for ln in w["lines"][:-1]:
            nxt = w["lines"][ln["k"] + 1]
            if nxt["page"] == ln["page"]:
                wadv[ln["k"]] = (nxt["baseline"] - ln["baseline"]) * PT_PX
        for k, _cut, _pg in dlines[:-1]:
            g, g2 = geo.get((blk["block"], k)), geo.get((blk["block"], k + 1))
            if g and g2 and g["page"] == g2["page"]:
                dadv[k] = g2["baseline"] - g["baseline"]

        def side_median(table, skip_script):
            vals = []
            for k, v in table.items():
                begin = w["lines"][k]["begin_stripped"]
                end = w["lines"][k]["end_stripped"]
                if bool(flags(sp, begin, end)) == skip_script:
                    vals.append(v)
            return median(vals)
        wm_plain, dm_plain = side_median(wadv, False), side_median(dadv, False)
        wm_scr, dm_scr = side_median(wadv, True), side_median(dadv, True)

        for ln in w["lines"]:
            k = ln["k"]
            begin, end = ln["begin_stripped"], ln["end_stripped"]
            here = flags(sp, begin, end)
            d = dlines[k] if k < len(dlines) else None
            g = geo.get((blk["block"], k))
            dcut = d[1] if d else None
            cut_word_raw = ln["orig_end"]
            cut_dev_raw = (w["idx"][dcut - 1] + 1 if dcut and dcut <= len(w["idx"]) else None)
            cut_dev_raw = ba.canon(w["raw"], cut_dev_raw) if cut_dev_raw else None
            cut_word_raw = ba.canon(w["raw"], cut_word_raw)
            row = {
                "word_para": pi, "device_block": blk["block"], "line": k,
                "lines_word": len(w["lines"]), "lines_device": len(dlines),
                "page_word": ln["page"], "page_device": d[2] if d else "",
                "cut_word": cut_word_raw, "cut_device": cut_dev_raw,
                "cut_off_chars": ((dcut - end) if dcut is not None else ""),
                "script": "|".join(s[3] for s in here) if here else "",
                "script_kind": "|".join(sorted(set(s[2] for s in here))) if here else "",
                "script_size_pt": "|".join(sorted(set("%g" % (s[4] / 2.0) for s in here if s[4]))) if here else "",
                "word_pdf_small_pt": ln["min_size"],
                "word_advance_px": round(wadv[k], 3) if k in wadv else "",
                "device_advance_px": round(dadv[k], 3) if k in dadv else "",
                "word_minus_plain": round(wadv[k] - wm_plain, 3) if k in wadv and wm_plain else "",
                "device_minus_plain": round(dadv[k] - dm_plain, 3) if k in dadv and dm_plain else "",
                "device_baseline": round(g["baseline"], 2) if g else "",
                "word_baseline_pt": round(ln["baseline"], 2),
                "line_head": "".join(c[0] for c in ln["chars"][:10]),
                "line_tail": "".join(c[0] for c in ln["chars"][-10:]),
                "para_plain_word_px": round(wm_plain, 3) if wm_plain else "",
                "para_plain_device_px": round(dm_plain, 3) if dm_plain else "",
                "para_script_word_px": round(wm_scr, 3) if wm_scr else "",
                "para_script_device_px": round(dm_scr, 3) if dm_scr else "",
            }
            rows.append(row)
        # --- break divergences, labelled with the line they sit on ---
        # identical to the 7th metric: both sides canonicalise onto the next real character
        wset = set(ba.canon(w["raw"], w["idx"][e - 1] + 1) for e in w["cuts"])
        order = w["idx"]
        dset_raw = set()
        for x in sorted(set(c for _k, c, _p in dlines[:-1])):
            if x - 1 < len(order):
                dset_raw.add(ba.canon(w["raw"], order[x - 1] + 1))
        for k in sorted(wset | dset_raw):
            inw, ind = k in wset, k in dset_raw
            if inw == ind:
                continue
            ln = next((l for l in w["lines"] if l["orig_end"] >= k), w["lines"][-1])
            here = flags(sp, ln["begin_stripped"], ln["end_stripped"])
            edges = [e for s in sp for e in (w["idx"][s[0]], w["idx"][s[1] - 1] + 1)]
            nearest = min(edges, key=lambda e: abs(e - k)) if edges else None
            breaks.append({
                "word_para": pi, "device_block": blk["block"], "offset": k,
                "side": "word_only" if inw else "phone_only",
                "cut_class": ba.classify(w["raw"], k), "line": ln["k"],
                "line_has_script": bool(here), "script_on_line": "|".join(s[3] for s in here),
                "chars_to_nearest_script_edge": (abs(nearest - k) if nearest is not None else ""),
                "context": w["raw"][max(0, k - 12):k] + "|" + w["raw"][k:k + 12],
            })
    return report(rows, breaks, pairs, spans, out_tsv, out_txt, a.Top, audit)
def quantile(values, q):
    v = sorted(values)
    if not v:
        return None
    return v[min(len(v) - 1, int(math.ceil((len(v) - 1) * q)))]


def report(rows, breaks, pairs, spans, out_tsv, out_txt, top, audit=None):
    lines = []
    script_rows = [r for r in rows if r["script"]]
    plain_rows = [r for r in rows if not r["script"]]
    with_h = [p for p, _pi in pairs if True]
    par_with_script = len(set(r["word_para"] for r in script_rows))

    def h(row, key):
        v = row[key]
        return v if isinstance(v, (int, float)) else None

    def stats(sel, key):
        vals = [r[key] for r in sel if isinstance(r[key], (int, float))]
        if not vals:
            return "n=0"
        return ("n=%-4d med=%-8.3f p90=%-8.3f min=%-8.3f max=%-8.3f"
                % (len(vals), median(vals), quantile([abs(v) for v in vals], 0.9),
                   min(vals), max(vals)))

    os.makedirs(os.path.dirname(out_tsv) or ".", exist_ok=True)
    with open(out_tsv, "w", newline="", encoding="utf-8") as fh:
        wr = csv.DictWriter(fh, fieldnames=list(rows[0].keys()), delimiter="\t")
        wr.writeheader()
        wr.writerows(rows)

    lines.append("== 含上标行的取样（与第 7 项同一份 PDF 真值、同一份手机采样）==")
    lines.append("  配对段落 = %d ；段落里带 w:vertAlign 的 = %d ；Word 行合计 = %d ，其中含上标行 = %d"
                 % (len(pairs), par_with_script, len(rows), len(script_rows)))
    kinds = collections.Counter()
    for r in script_rows:
        for k in r["script_kind"].split("|"):
            kinds[k] += 1
    lines.append("  含上标行按种类 = " + ", ".join("%s=%d" % kv for kv in kinds.most_common()))
    lines.append("  上标内容出现次数（Top）= " + ", ".join(
        "%s x%d" % (t, n) for t, n in collections.Counter(
            s for r in script_rows for s in r["script"].split("|")).most_common(top)))
    lines.append("")
    lines.append("== 1) 行高/基线：Word 相邻基线距离 vs 手机画出来的 advance（文档 px）==")
    lines.append("  全样本            Word advance  %s" % stats(rows, "word_advance_px"))
    lines.append("  全样本            我方 advance  %s" % stats(rows, "device_advance_px"))
    dd = [r["device_advance_px"] - r["word_advance_px"] for r in rows
          if isinstance(r["device_advance_px"], float) and isinstance(r["word_advance_px"], float)]
    lines.append("  全样本            我方-Word     %s" % (
        "n=0" if not dd else "n=%-4d med=%-8.3f |误差|p90=%-8.3f min=%-8.3f max=%-8.3f"
        % (len(dd), median(dd), quantile([abs(v) for v in dd], 0.9), min(dd), max(dd))))
    for label, sel in (("含上标行", script_rows), ("无上标行(对照)", plain_rows)):
        d2 = [r["device_advance_px"] - r["word_advance_px"] for r in sel
              if isinstance(r["device_advance_px"], float) and isinstance(r["word_advance_px"], float)]
        lines.append("  %-16s 我方-Word     %s" % (label, "n=0" if not d2 else
                     "n=%-4d med=%-8.3f |误差|p90=%-8.3f max=%-8.3f"
                     % (len(d2), median(d2), quantile([abs(v) for v in d2], 0.9), max(d2))))
    lines.append("")
    lines.append("  同一段落内的对照（去掉全篇字体行高偏差）：本段无上标行中位为基准，看含上标行高多少")
    for label, key_w, key_d, sel in (("含上标行", "word_minus_plain", "device_minus_plain", script_rows),):
        wv = [r[key_w] for r in sel if isinstance(r[key_w], float)]
        dv = [r[key_d] for r in sel if isinstance(r[key_d], float)]
        lines.append("  %-8s Word 高出本段平线 %s" % (label, stats(wv and [{"v": v} for v in wv], "v")))
        lines.append("  %-8s 我方高出本段平线 %s" % (label, stats(dv and [{"v": v} for v in dv], "v")))
    worse = [r for r in script_rows if isinstance(r["device_minus_plain"], float)
             and r["device_minus_plain"] > 0.4]
    lines.append("  我方把含上标行顶高 >0.4px 的行数 = %d / %d" % (len(worse), len(script_rows)))
    for r in worse[:top]:
        lines.append("      para %-4s blk %-4s line %-3d 上标 %-10s 我方 +%s px（Word %s px）| %s"
                     % (r["word_para"], r["device_block"], r["line"], r["script"],
                        r["device_minus_plain"], r["word_minus_plain"], r["line_head"]))
    lines.append("")
    lines.append("== 2) 断点：每个分歧落在第几行、那一行有没有上标 ==")
    scr = [b for b in breaks if b["line_has_script"]]
    non = [b for b in breaks if not b["line_has_script"]]
    lines.append("  分歧总数 = %d ；落在含上标行上 = %d ；落在无上标行上 = %d" % (len(breaks), len(scr), len(non)))
    for side in ("phone_only", "word_only"):
        s = [b for b in scr if b["side"] == side]
        n = [b for b in non if b["side"] == side]
        lines.append("    %-10s 含上标行 %2d  无上标行 %2d" % (side, len(s), len(n)))
    par_scr = len(set(b["word_para"] for b in scr))
    lines.append("  有分歧的段落 = %d ，其中首个分歧就在含上标行上的段落 = %d"
                 % (len(set(b["word_para"] for b in breaks)), par_scr))
    lines.append("  含上标行上的分歧样本（Top %d）：" % top)
    for b in scr[:top]:
        lines.append("      %-10s para %-4s blk %-4s line %-3d %-16s 上标 %-9s 距上标 %s 字  %s"
                     % (b["side"], b["word_para"], b["device_block"], b["line"], b["cut_class"],
                        b["script_on_line"], b["chars_to_nearest_script_edge"], b["context"]))
    lines.append("")
    lines.append("== 3) 断点错位的方向：我方比 Word 早断（丢字）还是晚断（挤字）==")
    early = [r for r in script_rows if isinstance(r["cut_off_chars"], int) and r["cut_off_chars"] < 0]
    late = [r for r in script_rows if isinstance(r["cut_off_chars"], int) and r["cut_off_chars"] > 0]
    same = [r for r in script_rows if r["cut_off_chars"] == 0]
    e2 = [r for r in plain_rows if isinstance(r["cut_off_chars"], int) and r["cut_off_chars"] < 0]
    l2 = [r for r in plain_rows if isinstance(r["cut_off_chars"], int) and r["cut_off_chars"] > 0]
    s2 = [r for r in plain_rows if r["cut_off_chars"] == 0]
    lines.append("  含上标行   同点 %d / 早断(丢字) %d / 晚断 %d   早断率 %.1f%%"
                 % (len(same), len(early), len(late),
                    100.0 * len(early) / max(1, len(same) + len(early) + len(late))))
    lines.append("  无上标行   同点 %d / 早断(丢字) %d / 晚断 %d   早断率 %.1f%%"
                 % (len(s2), len(e2), len(l2),
                    100.0 * len(e2) / max(1, len(s2) + len(e2) + len(l2))))
    lines.append("")
    lines.append("== 4) 页归属：段落起始页 Word vs 手机 ==")
    pg = [(r["word_para"], r["device_block"], r["page_word"], r["page_device"]) for r in rows if r["line"] == 0]
    bad = [x for x in pg if x[2] != x[3]]
    bad_scr = [x for x in bad if any(s[0] == x[0] for s in [[r["word_para"], 0] for r in script_rows])]
    lines.append("  配对段落首页不一致 = %d / %d ；其中该段含上标 = %d" % (len(bad), len(pg), len(bad_scr)))
    for x in bad[:top]:
        lines.append("      para %-4s blk %-4s Word 第 %s 页 / 手机第 %s 页" % x)
    lines.append("")
    lines.append("== 5) 上标占的宽度：Word 的自然占位 vs 逐 span 取整的算法（改前的模型） ==")
    lines.append("  注：这一节模拟的是 2.6.5 那套\"每个上下标 span 各自 ceil \"的宽度算法；合并成整串容器（ScriptTokenSpan）之后，")
    lines.append("  一个串只取一次整，差额的算式在宿主断言 SuperscriptPaginationRegression 里用 billedAsOneToken/billedPerPart 钉住。")
    if audit is None:
        lines.append("  没量")
    else:
        col = COLUMN_PT * PT_PX
        bad = [x for x in audit if x["slack"] < -0.5]
        audit_ok = [x for x in audit if x["slack"] >= -0.5]
        lines.append("  版心宽 %.2fpx 。Word 自然占位超过版心的行 = %d / %d：这些行的行首/行尾标点是悬挂在版心外"
                     % (col, len(bad), len(audit)))
        lines.append("  的（overflowPunct/kinsoku），松量算不准，一律不计进下面的预测；可用行 = %d" % len(audit_ok))
        scr = [x for x in audit_ok if x["script_delta"]]
        lines.append("  含上标且可用的 Word 行 = %d ；其中我方算出的宽度与 Word 不同 = %d"
                     % (len([x for x in audit_ok if x["detail"]]), len(scr)))
        over = [x for x in scr if x["script_delta"] > 0 and x["script_delta"] > x["slack"]]
        under = [x for x in scr if x["script_delta"] < 0]
        grab = [x for x in under if x["slack"] + (-x["script_delta"]) >= 0]
        med = lambda vs: (statistics.median(vs) if vs else 0.0)
        lines.append("  差额(我方-Word) 中位 %.3f px ，最大 +%.3f ，最小 %.3f "
                     "（按含上标行计，n=%d）"
                     % (med([x["script_delta"] for x in scr]),
                        max([x["script_delta"] for x in scr] or [0]),
                        min([x["script_delta"] for x in scr] or [0]), len(scr)))
        lines.append("  因为这一差就挤掉一个字符的行（差额 > Word 剩余的松量） = %d" % len(over))
        for x in sorted(over, key=lambda y: -y["script_delta"])[:top]:
            lines.append("      para %-4s p%-3s 第 %-2d 行 %2d 字  松量 %6.2fpx  差额 %+6.2fpx  %s | %s..%s"
                         % (x["para"], x["page"], x["chars"], x["chars"], x["slack"],
                            x["script_delta"], x["detail"], x["head"], x["tail"]))
        lines.append("  （含上标行里松量为负、只能按悬挂标点那一档另算的 = %d）"
                     % len([x for x in audit if x["detail"] and x["slack"] < -0.5]))
        lines.append("  因为这一差反而多挤进一个字符的行（我方少算了宽度，行尾那个字符 Word 放不下） = %d" % len(grab))
        for x in sorted(grab, key=lambda y: y["script_delta"])[:top]:
            lines.append("      para %-4s p%-3s 第 %-2d 行 %2d 字  松量 %6.2fpx  差额 %+6.2fpx  %s | %s..%s"
                         % (x["para"], x["page"], x["chars"], x["chars"], x["slack"],
                            x["script_delta"], x["detail"], x["head"], x["tail"]))
        agg = collections.Counter()
        for x in scr:
            for d in x["detail"].split(","):
                agg[d.split(":")[0]] += float(d.split(":")[1])
        lines.append("  按上标内容累计差额 = " + ", ".join(
            "%s %+.2fpx" % (k, v) for k, v in sorted(agg.items(), key=lambda kv: -abs(kv[1]))[:top]))
    lines.append("")
    lines.append("明细表 = %s" % out_tsv)
    with open(out_txt, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")
    print("\n".join(lines))
    return 0
# ---------------------------------------------------------------- width audit
COLUMN_PT = 425.2          # pgSz 11906 - pgMar 1701 x 2 = 8504 twips = 425.2 pt (docs 第 0 节)
GAP_PX = 4.0               # Word 的 autoSpaceDE/DN 缝隙，autogap-truth.py 量到 0.2500 em @12pt


def char_em(c, face):
    try:
        em = wb.hmtx_em(wb.face_of(c, face), c)
    except Exception:
        em = None
    return em if em is not None else (1.0 if wb.is_cjk(c) else 0.5)


def natural_bill_px(chars):
    """Word's own line bill in document px: every character's advance at the size the PDF reports for
    THAT character (so a vertAlign run is billed at its small size), plus one autoSpace gap per
    CJK<->Latin seam. Spaces ride at their own advance: a justified line stretches them, which is
    exactly the slack this audit measures."""
    total = 0.0
    prev = None
    for c in chars:
        if c[0] in ("\n", "\r"):
            continue
        total += char_em(c[0], c[3]) * c[2] * PT_PX
        if prev is not None and wb.seam(prev[0], c[0]) and abs(prev[2] - c[2]) < 1.5:
            total += GAP_PX
        prev = c
    return total


def our_script_cost(text, base_px, scale):
    """ceil(measure(text) at textSize x OS/2 ySuper/ySub) -- WordScriptSpan.getSize."""
    em = sum(char_em(c, "TimesNewRomanPSMT") for c in text)
    return math.ceil(em * base_px * scale)


def word_script_cost(chars, a, b):
    """What the same span costs in Word: the advances the PDF itself shows, at the size Word set."""
    ink = [c for c in chars if c[0] not in SPACEISH][a:b]
    return sum(char_em(c[0], c[3]) * c[2] * PT_PX for c in ink)


def width_audit(pdf, paras, spans, size_pt, base_px):
    """Per Word line: natural bill, slack under the column, and the script billing delta."""
    shim = _Shim()
    lines = shim.read_lines(pdf)
    # walk the same way tools/width-bill.py does, so a line lands on the same paragraph
    pix, cursor = 0, 0
    out = []
    for ln in lines:
        ink_src = [c for c in ln["chars"] if c[0] not in SPACEISH]
        if len(ink_src) < 2:
            continue
        raw = "".join(c[0] for c in ink_src)
        start, found = (pix, cursor), False
        while pix < len(paras):
            if paras[pix]["s"].find(raw, cursor) >= 0:
                found = True
                break
            pix += 1
            cursor = 0
        if not found:
            pix, cursor = start
            continue
        p = paras[pix]
        end = p["s"].find(raw, cursor) + len(raw)
        begin = end - len(raw)
        bill = natural_bill_px(ln["chars"])
        col = COLUMN_PT * PT_PX
        delta = 0.0
        detail = []
        for s in spans.get(pix, []):
            if s[0] >= end or s[1] <= begin:
                continue
            mine = our_script_cost(s[3], base_px, 0.6499 if s[2] == "sup" else 0.6499)
            his = word_script_cost(ln["chars"], s[0] - begin, min(s[1] - begin, len(ink_src)))
            delta += mine - his
            detail.append("%s%s:%+.2f" % (s[2], s[3], mine - his))
        out.append({"para": pix, "page": ln["page"], "begin": begin, "end": end,
                    "bill": bill, "slack": col - bill, "script_delta": delta,
                    "detail": ",".join(detail), "chars": len(ink_src),
                    "head": raw[:12], "tail": raw[-12:]})
        cursor = end
        if cursor >= len(p["s"]):
            pix += 1
            cursor = 0
    return out


if __name__ == "__main__":
    sys.exit(main())
