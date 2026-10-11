# -*- coding: utf-8 -*-
"""
随包判据表 aigc/aigc-rhythm.tsv 的唯一读法：Python 侧所有工具都从这里取分数，
Java 侧 AigcRhythm 读同一张表、同一条公式，两边不可能算出两个数。

段分 = Σ weight · sign · (值 - human_mean) / human_sd
判"可疑段落"：段分 > human_p99，且段落 >=120 有效字、>=4 句（与 Java 同一条口径）。

    py tools/aigc-instrument.py            # 打印表头出处 + 逐条读数自检
"""
import importlib.util, math, os, sys
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__)); REPO = os.path.dirname(HERE)
ASSET = os.path.join(REPO, "app", "src", "main", "assets", "aigc", "aigc-rhythm.tsv")
MIN_FLAG_CHARS, MIN_FLAG_SENTENCES = 120, 4

def load(mod, name):
    sp = importlib.util.spec_from_file_location(name, os.path.join(HERE, mod))
    m = importlib.util.module_from_spec(sp); sp.loader.exec_module(m); return m

RH = load("aigc-rhythm.py", "rh")
LX = load("aigc-lexical.py", "lx")

# 每条判据怎么算，写死在这里，Java 按同一个 id 对齐
LEX_FN = {"L02": LX.f_ttr, "L03": LX.f_hapax, "L04": LX.f_char_ent, "L05": LX.f_punct_ent}
RHY_IDS = [fid for fid, _n, _f in RH.FEATURES]

class Instrument(object):
    def __init__(self, rows, meta):
        self.rows, self.meta = rows, meta
        self.p50 = meta.get("human_p50"); self.p90 = meta.get("human_p90")
        self.p99 = meta.get("human_p99"); self.max = meta.get("human_max")

    def values(self, text):
        """返回 [(id, 值)]，顺序与表里行序一致。"""
        vec = None
        out = []
        for r in self.rows:
            rid = r["id"]
            if rid in LEX_FN:
                out.append(LEX_FN[rid](text))
            else:
                if vec is None:
                    vec = RH.vec(text)
                out.append(vec[RHY_IDS.index(rid)])
        return out

    def score(self, text):
        return sum(r["weight"] * r["sign"] * (v - r["mean"]) / r["sd"]
                   for r, v in zip(self.rows, self.values(text)))

    def contrib(self, text):
        """逐条贡献（带符号），给界面列"这一段凭什么可疑"用。"""
        return [(r["id"], r["name"], r["weight"] * r["sign"] * (v - r["mean"]) / r["sd"])
                for r, v in zip(self.rows, self.values(text))]

    def eligible(self, text):
        return (RH.valid_len(text) >= MIN_FLAG_CHARS and len(RH.sents(text)) >= MIN_FLAG_SENTENCES)

    def flagged(self, text):
        return self.eligible(text) and self.score(text) > self.p99

    def describe(self):
        return self.meta.get("version", "?")

def read(path=ASSET):
    rows, meta = [], {}
    header = None
    for raw in open(path, encoding="utf-8"):
        line = raw.rstrip("\n").rstrip("\r")
        if not line.strip():
            continue
        if line.startswith("#"):
            body = line[1:].strip()
            if "=" in body.split("\t")[0]:
                for kv in body.split():
                    if "=" in kv:
                        k, v = kv.split("=", 1)
                        meta[k] = v
            continue
        parts = line.split("\t")
        if header is None:
            header = parts
            continue
        rec = dict(zip(header, parts))
        try:
            rows.append(dict(id=rec["id"], name=rec.get("name", ""),
                             mean=float(rec["human_mean"]), sd=float(rec["human_sd"]),
                             sign=float(rec["sign"]), weight=float(rec["weight"]),
                             auc=float(rec.get("fit_auc") or "nan")))
        except (KeyError, ValueError):
            continue
    for k in ("human_p50", "human_p90", "human_p99", "human_max"):
        if k in meta:
            try:
                meta[k] = float(meta[k])
            except ValueError:
                pass
    return Instrument(rows, meta)

def main():
    ins = read()
    print("表 %s：%d 条判据（节奏 %d + 词汇 %d）" %
          (ins.meta.get("version"), len(ins.rows),
           sum(1 for r in ins.rows if r["id"] in RHY_IDS), sum(1 for r in ins.rows if r["id"] in LEX_FN)))
    for k in ("calibration", "auc_segment", "auc_document", "external", "note"):
        if k in ins.meta:
            print("  %-12s %s" % (k, ins.meta[k]))
    print("  判定线 human_p99=%s（真人 P50=%s P90=%s）" % (ins.p99, ins.p50, ins.p90))
    print("  %-6s %-22s %-9s %-9s %-4s %-8s" % ("id", "判据", "真人均值", "sd", "方向", "权重"))
    for r in ins.rows:
        print("  %-6s %-22s %-9.5f %-9.5f %-+4d %-8.4f" % (r["id"], r["name"], r["mean"], r["sd"], r["sign"], r["weight"]))

if __name__ == "__main__":
    main()
