# -*- coding: utf-8 -*-
"""
学术语域配对考试：DetectRL-X 中文学术域 2600 对（同一行里就是"真人原文 + 同选题模型稿"，
生成器含 deepseek-v3 / gemini-2.5-flash / gpt-4o / qwen-max）。语域天然对齐，
比的不可能是"是不是摘要"，只能比"写法"——这正是这几天所有 AIGC 读数翻车的那个变量。

两族判据同批考试：节奏/结构族（tools/aigc-rhythm.py R01~R15）与套话词表族
（tools/aigc-style-rules.py T*/P*，来源 humanizer-zh-academic，MIT）。
折内留出定符号与基线，考试折没见过；再按生成器拆开看跨模型稳不稳。

    py tools/aigc-paired.py                # 段级
    py tools/aigc-paired.py --doc-per 5    # 再把每 5 段拼成一篇看篇级
"""
import argparse, importlib.util, json, math, os, random, sys
from collections import defaultdict

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
OUT = os.path.join(REPO, "artifacts", "agent-aigc-rules")

def load(mod, name):
    sp = importlib.util.spec_from_file_location(name, os.path.join(HERE, mod))
    m = importlib.util.module_from_spec(sp); sp.loader.exec_module(m); return m

RH = load("aigc-rhythm.py", "rh")
SR = load("aigc-style-rules.py", "sr")
FEATURES, valid_len = RH.FEATURES, RH.valid_len

def fast_auc(pos, neg):
    """Mann-Whitney 秩统计（含并列修正），O(n log n)，比两两比较快几个数量级。"""
    if not pos or not neg:
        return None
    pairs = sorted([(v, 1) for v in pos] + [(v, 0) for v in neg])
    ranks = {}
    i = 0
    total_pos = len(pos)
    rank_sum_pos = 0.0
    n = len(pairs)
    while i < n:
        j = i
        while j + 1 < n and pairs[j + 1][0] == pairs[i][0]:
            j += 1
        avg = (i + j) / 2.0 + 1.0
        for k in range(i, j + 1):
            if pairs[k][1] == 1:
                rank_sum_pos += avg
        i = j + 1
    return (rank_sum_pos - total_pos * (total_pos + 1) / 2.0) / (float(len(pos)) * len(neg))

SENT_END = "。！？；?!;"

def sent_spans(text):
    """按原文偏移切句，标点保留（分窗时不能把句号弄丢，否则句级判据全被打死）。"""
    out, start = [], 0
    for i, ch in enumerate(text):
        if ch in SENT_END:
            seg = text[start:i + 1]
            if RH.valid_len(seg) >= 1:
                out.append((start, i + 1))
            start = i + 1
    if RH.valid_len(text[start:]) >= 1:
        out.append((start, len(text)))
    return out

def chunk(text, lo, hi):
    out, s0, s1 = [], None, None
    for a, b in sent_spans(text):
        if s0 is None:
            s0, s1 = a, b
            continue
        if RH.valid_len(text[s0:b]) <= hi:
            s1 = b
        else:
            if RH.valid_len(text[s0:s1]) >= lo:
                out.append(text[s0:s1])
            s0, s1 = a, b
    if s0 is not None and RH.valid_len(text[s0:s1]) >= lo:
        out.append(text[s0:s1])
    return [p for p in out if lo <= RH.valid_len(p) <= hi]

def main(lo=100, hi=700, folds=4, doc_per=0):
    _rows = SR.accept(json.load(open(os.path.join(OUT, "rules-audit.json"), encoding="utf-8")))
    TELLS = [x["id"] for x in _rows if x.get("paired_pass")]
    path = os.path.join(RH.CORPUS, "drlx_zh_general.jsonl")
    records = []
    for line in open(path, encoding="utf-8"):
        try:
            d = json.loads(line)
        except Exception:
            continue
        if (d.get("domain") or "") != "Academic":
            continue
        h, m = d.get("human_written_text") or "", d.get("llm_generated_text") or ""
        hp = [p for p in chunk(h, lo, hi) if lo <= valid_len(p) <= hi]
        mp = [p for p in chunk(m, lo, hi) if lo <= valid_len(p) <= hi]
        if hp and mp:
            records.append(dict(model=d.get("model") or "?",
                                h=[RH.vec(p) + [float(x) for x in SR.hits_all(p, TELLS)] for p in hp],
                                m=[RH.vec(p) + [float(x) for x in SR.hits_all(p, TELLS)] for p in mp],
                                hfull=h, mfull=m))
    models = sorted(set(r["model"] for r in records))
    nh = sum(len(r["h"]) for r in records); nm = sum(len(r["m"]) for r in records)
    print("学术配对 %d 对；生成器 %s；合格段 真人 %d / 机器 %d（段长 %d~%d 字）"
          % (len(records), models, nh, nm, lo, hi))
    NT = len(TELFS := TELLS)
    rnd = random.Random(17); rnd.shuffle(records)

    def cols(pool, idx):
        return [r[idx] for r in pool]

    def zfit(tr_h, tr_m, idx):
        hs = cols(tr_h, idx); ms = cols(tr_m, idx)
        bm = RH.mean(hs); bs = RH.stdev(hs) or 1.0
        sgn = 1.0 if RH.mean(ms) >= bm else -1.0
        return bm, bs, sgn

    print("")
    print("%-5s %-20s %-9s %-9s %s" % ("id", "判据", "段级AUC", "折间极差", "分生成器 AUC + 方向"))
    report = {}
    ncol = len(FEATURES)
    for idx, (fid, name, _) in enumerate(FEATURES):
        per_fold = []
        for k in range(folds):
            tr_h = [v for i, r in enumerate(records) if i % folds != k for v in r["h"]]
            tr_m = [v for i, r in enumerate(records) if i % folds != k for v in r["m"]]
            te_h = [v for i, r in enumerate(records) if i % folds == k for v in r["h"]]
            te_m = [v for i, r in enumerate(records) if i % folds == k for v in r["m"]]
            if not (te_h and te_m):
                continue
            bm, bs, sgn = zfit(tr_h, tr_m, idx)
            a = fast_auc([sgn * ((v[idx] - bm) / bs) for v in te_m], [sgn * ((v[idx] - bm) / bs) for v in te_h])
            if a is not None:
                per_fold.append(a)
        cols_by_model = []
        all_h = [v for r in records for v in r["h"]]; all_m = [v for r in records for v in r["m"]]
        bm, bs, sgn = zfit(all_h, all_m, idx)
        for model in models:
            mh = [v for r in records if r["model"] == model for v in r["h"]]
            mm = [v for r in records if r["model"] == model for v in r["m"]]
            a = fast_auc([sgn * v[idx] for v in mm], [sgn * v[idx] for v in mh]) if mh and mm else None
            cols_by_model.append(None if a is None else round(a, 3))
        if not per_fold:
            continue
        mu = sum(per_fold) / len(per_fold)
        report[fid] = dict(name=name, family="rhythm", auc=round(mu, 4),
                           spread=round(max(per_fold) - min(per_fold), 4),
                           per_model=cols_by_model, direction="机器更高" if sgn > 0 else "真人更高")
        print("%-5s %-20s %-9.3f %-9.3f %s [%s]" % (fid, name, mu, max(per_fold) - min(per_fold),
              " ".join("n/a" if c is None else "%.2f" % c for c in cols_by_model), report[fid]["direction"]))
    print("")
    print("套话词表族（同批数据，命中次数当分数）")
    for t, rid in enumerate(TELLS):
        idx = ncol + t
        per_fold = []
        for k in range(folds):
            te_h = [v for i, r in enumerate(records) if i % folds == k for v in r["h"]]
            te_m = [v for i, r in enumerate(records) if i % folds == k for v in r["m"]]
            if te_h and te_m:
                a = fast_auc([v[idx] for v in te_m], [v[idx] for v in te_h])
                if a is not None:
                    per_fold.append(a)
        if per_fold:
            mu = sum(per_fold) / len(per_fold)
            hit_h = sum(v[idx] for v in all_h) * 1000.0 / max(1, len(all_h))
            hit_m = sum(v[idx] for v in all_m) * 1000.0 / max(1, len(all_m))
            report[rid] = dict(name=SR.RULES[rid]["name"], family="tells", auc=round(mu, 4),
                               per_kilo_human=round(hit_h, 2), per_kilo_machine=round(hit_m, 2))
            print("  %-5s %-20s 段级 AUC=%.3f  每千段命中 真人 %.1f / 机器 %.1f"
                  % (rid, SR.RULES[rid]["name"], mu, hit_h, hit_m))
    # 组合打分：节奏族 z 加权
    stats = [zfit(all_h, all_m, i) for i in range(ncol)]
    def score_rhythm(v):
        return sum(stats[i][2] * ((v[i] - stats[i][0]) / stats[i][1]) for i in range(ncol))
    a_seg = fast_auc([score_rhythm(v) for v in all_m], [score_rhythm(v) for v in all_h])
    print("")
    print("节奏族组合打分（%d 条 z 加权）段级 AUC = %s" % (ncol, "n/a" if a_seg is None else "%.4f" % a_seg))
    report["_combined"] = dict(rhythm_segment_auc=None if a_seg is None else round(a_seg, 4))
    if doc_per:
        minchars = doc_per * 120
        dh = [r["hfull"] for r in records if RH.valid_len(r["hfull"]) >= minchars]
        dm = [r["mfull"] for r in records if RH.valid_len(r["mfull"]) >= minchars]
        if dh and dm:
            vh = [RH.vec(t) + [float(x) for x in SR.hits_all(t, TELLS)] for t in dh]
            vm = [RH.vec(t) + [float(x) for x in SR.hits_all(t, TELLS)] for t in dm]
            a_doc = fast_auc([score_rhythm(v) for v in vm], [score_rhythm(v) for v in vh])
            a_tell = fast_auc([sum(v[ncol + t] for t in range(len(TELLS))) for v in vm],
                              [sum(v[ncol + t] for t in range(len(TELLS))) for v in vh]) if TELLS else None
            at = lambda x: "n/a" if x is None else "%.4f" % x
            print("整篇（>= %d 字，千字归一）：节奏族 AUC=%s，套话族 AUC=%s（真人 %d 篇 / 机器 %d 篇）"
                  % (minchars, at(a_doc), at(a_tell), len(dh), len(dm)))
            report["_combined"].update(doc_minchars=minchars, doc_rhythm_auc=None if a_doc is None else round(a_doc, 4),
                                       doc_tells_auc=None if a_tell is None else round(a_tell, 4))
    # 跨生成器：留一个生成器完全不参与定基线与定符号，只当考生
    loro = {}
    for hold in models:
        th = [v for r in records if r["model"] != hold for v in r["h"]]
        tm = [v for r in records if r["model"] != hold for v in r["m"]]
        if not (th and tm):
            continue
        st = [zfit(th, tm, i) for i in range(ncol)]
        f = lambda v: sum(st[i][2] * ((v[i] - st[i][0]) / st[i][1]) for i in range(ncol))
        ph = [v for r in records if r["model"] == hold for v in r["h"]]
        pm = [v for r in records if r["model"] == hold for v in r["m"]]
        a = fast_auc([f(v) for v in pm], [f(v) for v in ph])
        loro[hold] = None if a is None else round(a, 4)
    print("")
    print("跨生成器留出（该生成器完全没参与定标，只当考生）：",
          "  ".join("%s=%s" % (k, "n/a" if v is None else "%.3f" % v) for k, v in loro.items()))
    report["_combined"]["leave_one_generator_out"] = loro
    os.makedirs(OUT, exist_ok=True)
    json.dump(dict(pairs=len(records), models=models, seg_h=nh, seg_m=nm, report=report),
              open(os.path.join(OUT, "paired-academic.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    print("完整读数 -> " + os.path.join(OUT, "paired-academic.json"))

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--minlen", type=int, default=100)
    ap.add_argument("--maxlen", type=int, default=700)
    ap.add_argument("--folds", type=int, default=4)
    ap.add_argument("--doc-per", dest="doc_per", type=int, default=0)
    a = ap.parse_args()
    main(a.minlen, a.maxlen, a.folds, a.doc_per)
