# -*- coding: utf-8 -*-
"""
定标整台 AIGC 仪器（15 条节奏/结构 + 4 条自算词汇），写随包资产 aigc/aigc-rhythm.tsv。
定标只允许用 DetectRL-X 中文学术域配对库；仓库 tests/corpus 那批真稿一律不进定标，只当外部考试。
表里每一行都由 tools/aigc-instrument.py（Python）与 AigcRhythm.java（Java）同一条公式消费，
两侧不可能算出两个数。

    py tools/aigc-instrument-export.py

词汇族整族带一个倍率 LEX_FACTOR：它由 tools/aigc-variants.py 那张表量出来——
领域对齐那场最难的外部考试 0.700 -> 0.960，"把相邻句合并"这种规避下的机器命中 1.5% -> 21.8%。
"""
import importlib.util, json, os, sys
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
HERE = os.path.dirname(os.path.abspath(__file__)); REPO = os.path.dirname(HERE)
OUT = os.path.join(REPO, "artifacts", "agent-aigc-rules")
ASSETS = os.path.join(REPO, "app", "src", "main", "assets", "aigc")
VER = "instrument-19-2026-10-12"
LEX_FACTOR = 1.25
DOC_MIN_CHARS = 1440

def load(mod, name):
    sp = importlib.util.spec_from_file_location(name, os.path.join(HERE, mod))
    m = importlib.util.module_from_spec(sp); sp.loader.exec_module(m); return m

PD = load("aigc-paired.py", "pd")
LX = PD.LX if hasattr(PD, "LX") else load("aigc-lexical.py", "lx")
RH = PD.RH
RHY = load("aigc-variants.py", "va")
LEX = [("L02", "字组类型比", LX.f_ttr), ("L03", "只出现一次的字组占比", LX.f_hapax),
       ("L04", "字符熵", LX.f_char_ent), ("L05", "标点熵", LX.f_punct_ent)]

def pctl(xs, q):
    s = sorted(xs)
    return s[min(len(s) - 1, int(q * (len(s) - 1)))]

def fit(fid, name, value, hu, ma, factor=1.0):
    hv = [value(p) for p in hu]; mv = [value(p) for p in ma]
    mu, sd = RH.mean(hv), (RH.stdev(hv) or 1.0)
    sgn = 1.0 if RH.mean(mv) >= mu else -1.0
    gap = abs(RH.mean([(x - mu) / sd for x in mv]))
    w = 0.0 if gap < 0.15 else min(2.0, round(max(0.15, gap / 2.0), 4))
    a = PD.fast_auc([sgn * (x - mu) / sd for x in mv], [sgn * (x - mu) / sd for x in hv])
    return dict(id=fid, name=name, mean=round(mu, 5), sd=round(sd, 5), sign=int(sgn),
                weight=round(w * factor, 4), fit_auc=round(a, 4),
                human_p50=round(pctl(hv, .5), 5), human_p90=round(pctl(hv, .9), 5),
                n_human=len(hv), value=value)

def main():
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
    models = sorted(set(r[0] for r in recs))
    print("定标库：DetectRL-X 中文学术域 %d 对 %d/%d 段，生成器 %s" % (len(recs), len(hu), len(ma), models))

    # 特征一律先按段算一遍存住：节奏族 15 条共用同一个向量，词汇族四条各存一列
    uniq = list(dict.fromkeys(hu + ma))
    VEC = {}
    def vec_of(p):
        v = VEC.get(p)
        if v is None:
            v = RH.vec(p); VEC[p] = v
        return v
    for i, p in enumerate(uniq):
        vec_of(p)
        if i % 3000 == 0:
            print("  节奏特征 %d/%d" % (i, len(uniq))); sys.stdout.flush()
    print("  节奏特征完成 %d 段" % len(uniq))
    def memo(fn):
        cache = {}
        def g(p):
            v = cache.get(p)
            if v is None:
                v = fn(p); cache[p] = v
            return v
        return g
    rows = []
    for i, (fid, name, _fn) in enumerate(RH.FEATURES):
        rows.append(fit(fid, name, (lambda i: lambda p: vec_of(p)[i])(i), hu, ma))
    for fid, name, fn in LEX:
        rows.append(fit(fid, name, memo(fn), hu, ma, LEX_FACTOR))
    for r in rows:
        r["score"] = lambda p, r=r: r["weight"] * r["sign"] * (r["value"](p) - r["mean"]) / r["sd"]

    def score(p):
        return sum(r["score"](p) for r in rows)

    a_seg = PD.fast_auc([score(p) for p in ma], [score(p) for p in hu])
    dh = [r[3] for r in recs if RH.valid_len(r[3]) >= DOC_MIN_CHARS]
    dm = [r[4] for r in recs if RH.valid_len(r[4]) >= DOC_MIN_CHARS]
    a_doc = PD.fast_auc([score(p) for p in dm], [score(p) for p in dh])
    print("段级 AUC=%.4f 整篇(>=%d 字)AUC=%.4f（%d vs %d 篇）" % (a_seg, DOC_MIN_CHARS, a_doc, len(dm), len(dh)))

    def lab(f):
        path = os.path.join(RH.LABELS, f)
        if not os.path.exists(path):
            return []
        body = "\n".join(l for l in open(path, encoding="utf-8").read().split("\n") if not l.startswith("#"))
        return [p for p in RH.lab_paras(body) if RH.valid_len(p) >= 100]
    eh = [p for f in ("real-prose.txt", "aigc-label-human-verbatim.txt", "aigc-label-human-polish-agent.txt") for p in lab(f)]
    ext = {}
    for tag, f in (("raw", "aigc-label-machine-raw.txt"), ("evasive", "aigc-label-machine-evasive.txt"),
                   ("domain", "aigc-label-machine-domain.txt")):
        m2 = lab(f)
        if m2:
            ext[tag] = round(PD.fast_auc([score(p) for p in m2], [score(p) for p in eh]), 4)
            print("外部考试 %-8s AUC=%.4f（机器 %d 段 / 真人 %d 段）" % (tag, ext[tag], len(m2), len(eh)))

    thr = pctl([score(p) for p in hu], 0.99)
    def gate(pool):
        hit = tot = 0
        for p in pool:
            if RH.valid_len(p) < 120 or len(RH.sents(p)) < 4:
                continue
            tot += 1
            if score(p) > thr:
                hit += 1
        return 100.0 * hit / max(1, tot), tot
    fp, th = gate(hu); tp, tm = gate(ma)
    print("划线 human_p99=%.4f：真人误报 %.2f%%（%d 段）机器段命中 %.1f%%（%d 段）" % (thr, fp, th, tp, tm))

    cols = ["id", "name", "human_mean", "human_sd", "sign", "weight", "fit_auc", "human_p50", "human_p90", "n_human"]
    keymap = {"human_mean": "mean", "human_sd": "sd", "fit_auc": "fit_auc", "n_human": "n_human"}
    hs = [score(p) for p in hu]
    exts = "|".join("%s:%s" % (k, v) for k, v in ext.items())
    lines = ["# version=%s" % VER,
             "# calibration=DetectRL-X中文学术域%d对 human=%d machine=%d generators=%s"
             % (len(recs), len(hu), len(ma), ",".join(models)),
             "# auc_segment=%.4f auc_document=%.4f doc_min_chars=%d" % (a_seg, a_doc, DOC_MIN_CHARS),
             "# external=%s external_human=%d" % (exts, len(eh)),
             "# lexical_factor=%s lex_dropped=L01,L06" % LEX_FACTOR,
             "# human_p50=%s human_p90=%s human_p99=%s human_max=%s"
             % (round(pctl(hs, .5), 4), round(pctl(hs, .9), 4), round(thr, 4), round(max(hs), 4)),
             "# false_positive_pct=%.2f machine_hit_pct=%.1f gate_min_chars=120 gate_min_sentences=4" % (fp, tp),
             "# 说明 段分为 19 条判据的 z 加权和；机器高于真人的判据 sign 为正。",
             "# 说明 L01 字组稀遇率需要额外词表、L06 字组重复率与 L02 恒等（1-L02），两条都不进表。",
             "# 说明 未验证：跨学科分层、学生学位论文语域、刻意模仿人类节奏的机器稿；不许当概率对外宣称。",
             "\t".join(cols)]
    for r in rows:
        lines.append("\t".join(str(r[keymap.get(c, c)]) for c in cols))
    os.makedirs(ASSETS, exist_ok=True)
    path = os.path.join(ASSETS, "aigc-rhythm.tsv")
    open(path, "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")
    print("写出 %s（%d 条判据，%.1f KB）" % (path, len(rows), os.path.getsize(path) / 1024.0))
    json.dump(dict(version=VER, lexical_factor=LEX_FACTOR, rows=[{k: v for k, v in r.items() if k != "value" and k != "score"}
                                                                 for r in rows],
                   auc=dict(segment=round(a_seg, 4), document=round(a_doc, 4)), external=ext,
                   gate=dict(p50=round(pctl(hs, .5), 4), p90=round(pctl(hs, .9), 4), p99=round(thr, 4),
                             max=round(max(hs), 4), fp_pct=round(fp, 2), hit_pct=round(tp, 1),
                             n_human_gate=th, n_machine_gate=tm)),
              open(os.path.join(OUT, "instrument-export.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("写出 artifacts/agent-aigc-rules/instrument-export.json")

if __name__ == "__main__":
    main()
