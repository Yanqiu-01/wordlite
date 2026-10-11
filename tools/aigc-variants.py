# -*- coding: utf-8 -*-
"""
合流变体考试：节奏族单独 / 节奏+词汇各组合 / 词汇族单独，一次量四件事——
段级 AUC、整篇 AUC、三场外部考试、以及最要紧的"相邻句合并"规避下的命中与真人误报。

判据取舍由这张表决定，不由感觉决定。已知 L06 字组重复率 = 1 - L02 字组类型比（逐字恒等），
先把恒等误差量出来，再决定留哪一条。特征一律按文本记忆化，一段文字只算一遍。

    py tools/aigc-variants.py             # 全部
    py tools/aigc-variants.py --quick     # 只跑定标侧与外部考试，跳过句子切法扰动
"""
import argparse, importlib.util, json, os, random, sys
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
HERE = os.path.dirname(os.path.abspath(__file__)); REPO = os.path.dirname(HERE)
OUT = os.path.join(REPO, "artifacts", "agent-aigc-rules")
MIN_FLAG_CHARS, MIN_FLAG_SENTENCES = 120, 4      # 与 Java AigcRhythm 同一条判定口径

def load(mod, name):
    sp = importlib.util.spec_from_file_location(name, os.path.join(HERE, mod))
    m = importlib.util.module_from_spec(sp); sp.loader.exec_module(m); return m

LX = load("aigc-lexical.py", "lx"); RH, PD = LX.RH, LX.PD
RHY = json.load(open(os.path.join(OUT, "rhythm-export.json"), encoding="utf-8"))["rows"]
LEX = [("L02", "字组类型比", LX.f_ttr), ("L03", "只出现一次的字组占比", LX.f_hapax),
       ("L04", "字符熵", LX.f_char_ent), ("L05", "标点熵", LX.f_punct_ent)]
VARIANTS = [("R", "只节奏族（出厂那份）", [], 1.0),
            ("R4", "节奏+四条词汇", ["L02", "L03", "L04", "L05"], 0.6),
            ("R4", "节奏+四条词汇", ["L02", "L03", "L04", "L05"], 1.0),
            ("R4", "节奏+四条词汇", ["L02", "L03", "L04", "L05"], 1.25),
            ("R4", "节奏+四条词汇", ["L02", "L03", "L04", "L05"], 1.5),
            ("R4", "节奏+四条词汇", ["L02", "L03", "L04", "L05"], 2.0),
            ("R4", "节奏+四条词汇", ["L02", "L03", "L04", "L05"], 3.0)]
VARIANTS = [(("%s-x%g" % (v[0], v[3])) if v[2] else v[0], v[1], v[2], v[3]) for v in VARIANTS]

_vec, _lex, _nsent, _vlen = {}, {}, {}, {}
def vec(p):
    v = _vec.get(p)
    if v is None:
        v = RH.vec(p); _vec[p] = v
    return v
def lexv(p, fid, fn):
    key = (p, fid)
    v = _lex.get(key)
    if v is None:
        v = fn(p); _lex[key] = v
    return v
def vlen(p):
    v = _vlen.get(p)
    if v is None:
        v = RH.valid_len(p); _vlen[p] = v
    return v
def nsent(p):
    v = _nsent.get(p)
    if v is None:
        v = len(RH.sents(p)); _nsent[p] = v
    return v

def rhythm_score(p):
    v = vec(p)
    return sum(r["weight"] * r["sign"] * (v[i] - r["mean"]) / r["sd"] for i, r in enumerate(RHY))

def fit_lexical(ids, hu, ma):
    """词汇族在定标库上拟基线/方向/权重，权重口径与节奏族一致（两侧 z 均值差的一半）。"""
    rows = []
    for fid, name, fn in LEX:
        if fid not in ids:
            continue
        hv = [lexv(p, fid, fn) for p in hu]; mv = [lexv(p, fid, fn) for p in ma]
        mu, sd = RH.mean(hv), (RH.stdev(hv) or 1.0)
        sgn = 1.0 if RH.mean(mv) >= mu else -1.0
        gap = abs(RH.mean([(x - mu) / sd for x in mv]))
        w = 0.0 if gap < 0.15 else min(2.0, round(max(0.15, gap / 2.0), 4))
        a = PD.fast_auc([sgn * (x - mu) / sd for x in mv], [sgn * (x - mu) / sd for x in hv])
        rows.append(dict(id=fid, name=name, mean=round(mu, 5), sd=round(sd, 5), sign=int(sgn),
                         weight=w, auc=round(a, 4), fn=fn))
    return rows

def make_scorer(lex_rows, scale):
    if not lex_rows:
        return rhythm_score
    def fn(p):
        s = rhythm_score(p)
        for r in lex_rows:
            s += scale * r["weight"] * r["sign"] * (lexv(p, r["id"], r["fn"]) - r["mean"]) / r["sd"]
        return s
    return fn

def auc(pos, neg):
    return PD.fast_auc(pos, neg) if (pos and neg) else None

def gate(pool, sc, thr):
    """判定口径与出厂一致：段分过线，且段落 >=120 字 >=4 句。"""
    hit = tot = 0
    for p in pool:
        if vlen(p) < MIN_FLAG_CHARS or nsent(p) < MIN_FLAG_SENTENCES:
            continue
        tot += 1
        if sc(p) > thr:
            hit += 1
    return hit, tot

def pctl(xs, q):
    s = sorted(xs)
    return s[min(len(s) - 1, int(q * (len(s) - 1)))]

def lab(f):
    path = os.path.join(RH.LABELS, f)
    if not os.path.exists(path):
        return []
    body = "\n".join(l for l in open(path, encoding="utf-8").read().split("\n") if not l.startswith("#"))
    return [p for p in RH.lab_paras(body) if RH.valid_len(p) >= 100]

def main(quick=False):
    recs = []
    for line in open(os.path.join(RH.CORPUS, "drlx_zh_general.jsonl"), encoding="utf-8"):
        try:
            d = json.loads(line)
        except Exception:
            continue
        if (d.get("domain") or "") != "Academic":
            continue
        h, m = d.get("human_written_text") or "", d.get("llm_generated_text") or ""
        hp, mp = PD.chunk(h, 100, 700), PD.chunk(m, 100, 700)
        if hp and mp:
            recs.append((d.get("model") or "?", hp, mp, h, m))
    hu = [p for _, hp, mp, _, _ in recs for p in hp]
    ma = [p for _, hp, mp, _, _ in recs for p in mp]
    print("定标库 %d 对：%d 真人段 / %d 机器段" % (len(recs), len(hu), len(ma)))
    worst = max(abs(LX.f_repeat(p) - (1.0 - LX.f_ttr(p))) for p in (hu[:400] + ma[:400]))
    print("恒等检查：L06 与 1-L02 最大绝对误差 %.3e -> %s" % (worst, "恒等，L06 不进任何变体" if worst < 1e-9 else "不恒等"))

    eh = [p for f in ("real-prose.txt", "aigc-label-human-verbatim.txt", "aigc-label-human-polish-agent.txt") for p in lab(f)]
    exams = [(t, lab(f)) for t, f in (("机器整节直出", "aigc-label-machine-raw.txt"),
                                      ("机器避套话", "aigc-label-machine-evasive.txt"),
                                      ("机器领域对齐", "aigc-label-machine-domain.txt"))]
    print("外部考试：真人 %d 段；" % len(eh) + " ".join("%s %d 段" % (t, len(v)) for t, v in exams))

    rnd = random.Random(29)
    mh = [LX.perturb(p, "merge", rnd) for p in ma]      # 只改"句子怎么切"，用词一个字不动
    hh = [LX.perturb(p, "merge", rnd) for p in hu]
    dh = [r[3] for r in recs if vlen(r[3]) >= 1440]
    dm = [r[4] for r in recs if vlen(r[4]) >= 1440]
    todo = hu + ma + mh + hh + eh + dh + dm + [p for _t, ps in exams for p in ps]
    for i, p in enumerate(todo):
        vec(p); vlen(p); nsent(p)
        if i % 5000 == 0:
            print("  特征缓存 %d/%d" % (i, len(todo))); sys.stdout.flush()
    print("  特征缓存完成：%d 段调用" % len(todo))

    fits = {v[0]: fit_lexical(v[2], hu, ma) for v in VARIANTS}
    scales = {v[0]: v[3] for v in VARIANTS}
    for r in fits["R4-x1"]:
        print("  词汇族定标 %s 真人均值 %.4f sd %.4f 方向 %+d 权重 %.4f 单条AUC %.4f"
              % (r["name"], r["mean"], r["sd"], r["sign"], r["weight"], r["auc"]))

    print("")
    print("%-6s %-28s %-9s %-9s %-9s %-9s %-9s %-20s %-11s" %
          ("变体", "说明", "段级AUC", "整篇AUC", "直出", "避套话", "领域对齐", "合并句后机器命中", "合并句后误报"))
    res = {}
    for vid, note, ids, scale in VARIANTS:
        sc = make_scorer(fits[vid], scale)
        a_seg = auc([sc(p) for p in ma], [sc(p) for p in hu])
        a_doc = auc([sc(p) for p in dm], [sc(p) for p in dh])
        thr = pctl([sc(p) for p in hu], 0.99)
        ext = {t: auc([sc(p) for p in m2], [sc(p) for p in eh]) for t, m2 in exams}
        hit = fp = base = None
        if not quick:
            h1, t1 = gate(hh, sc, thr); h0, t0 = gate(mh, sc, thr); b0, bt = gate(ma, sc, thr)
            fp = 100.0 * h1 / max(1, t1); hit = 100.0 * h0 / max(1, t0); base = 100.0 * b0 / max(1, bt)
        res[vid] = dict(note=note, ids=ids, scale=scale, seg=round(a_seg, 4),
                        doc=None if a_doc is None else round(a_doc, 4),
                        ext={k: (None if v is None else round(v, 4)) for k, v in ext.items()},
                        thr=round(thr, 4), merge_hit=hit, merge_fp=fp, hit_undisturbed=base)
        print("%-6s %-28s %-9.4f %-9s %-9s %-9s %-9s %-20s %-11s" %
              (vid, note, a_seg, "-" if a_doc is None else "%.4f" % a_doc,
               *["-" if ext[t] is None else "%.4f" % ext[t] for t, _ in exams],
               "-" if quick else "%.1f%%（未扰动 %.1f%%）" % (hit, base), "-" if quick else "%.2f%%" % fp))
        sys.stdout.flush()

    if not quick:
        print("")
        for vid in ("R4-x0.6", "R4-x1", "R4-x1.5", "R4-x3"):
            lx = make_scorer(fits[vid], scales[vid])
            def only(p, lx=lx):
                return lx(p) - rhythm_score(p)
            thr = pctl([only(p) for p in hu], 0.99)
            h1, t1 = gate(hh, only, thr); h0, t0 = gate(mh, only, thr); b0, bt = gate(ma, only, thr)
            print("词汇族单独 %-3s：机器命中 未扰动 %.1f%% -> 合并句后 %.1f%%，合并句后真人误报 %.2f%%"
                  % (vid, 100.0 * b0 / max(1, bt), 100.0 * h0 / max(1, t0), 100.0 * h1 / max(1, t1)))
            res[vid]["lex_only_hit"] = round(100.0 * b0 / max(1, bt), 1)
            res[vid]["lex_only_merge_hit"] = round(100.0 * h0 / max(1, t0), 1)
            res[vid]["lex_only_merge_fp"] = round(100.0 * h1 / max(1, t1), 2)
    json.dump(dict(fitted={k: [{kk: vv for kk, vv in r.items() if kk != "fn"} for r in v]
                        for k, v in fits.items()}, variants=res),
              open(os.path.join(OUT, "variants.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("写出 artifacts/agent-aigc-rules/variants.json")

if __name__ == "__main__":
    ap = argparse.ArgumentParser(); ap.add_argument("--quick", action="store_true")
    main(quick=ap.parse_args().quick)
