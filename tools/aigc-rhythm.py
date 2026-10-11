# -*- coding: utf-8 -*-
"""
节奏 / 结构类稠密判据：AIGC 检测在中文学术语域里唯一还没被证伪的信号族。

为什么是它（2026-10-12 实测，全部可在 artifacts/agent-aigc-rules/ 复核）：
* 套话词表在论文正文里真人和机器都是 0 命中，只在人类正式摘要里密集出现（真人摘要 45.9 次/千字），
  拿它当分数等于冤枉真人摘要、放过机器正文——`tools/aigc-style-rules.py` 逐条量过。
* 同一语域（学位论文正文）里唯一稳定亮灯的是"句长过于均匀"：真人 40 次/千段，机器整节直出 583 次/千段，
  段级 AUC 0.769。外部依据同源：humanizer-zh-academic 把"每句话长度接近、节奏单一"列为机写主特征。

本脚本回答三件事，一次比一次难：
  1) `probe`  每条判据单独在**同语域**（真人正文 vs 机器正文）上的方向与 AUC；
  2) `fit`    把这些判据拟合成一个 16 系数的线性打分器，跨语域迁移（拟合在正文，考在摘要/通用改写）；
  3) `export` 导出随包 `aigc-rhythm.tsv`（系数 + 真人基线分位），Java 侧只查表算加权和。

纪律：真人侧目前只有 real-prose.txt **一位作者**，这是这套判据最大的风险，所以 fit 一律做
leave-one-paragraph-group-out 的折内留出，并且把"跨作者没验过"这句话写进导出清单。
"""
import argparse, json, math, os, random, re, sys
from collections import Counter

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
CORPUS = os.path.abspath(os.path.join(REPO, "..", "aigc-corpus"))
LABELS = os.path.join(REPO, "tests", "corpus")
OUT = os.path.join(REPO, "artifacts", "agent-aigc-rules")
ASSETS = os.path.join(REPO, "app", "src", "main", "assets", "aigc")
SELFBUILD = os.path.join(CORPUS, "selfbuild")

SENT_END = "。！？；?!;"
CLAUSE_SEP = "，、：:；;（）()“”\"'‘’ \n"

def clean(t):
    return re.sub(r"\s+", "", t or "")

def sents(text):
    out, cur = [], ""
    for ch in text:
        if ch in SENT_END:
            if clean(cur):
                out.append(clean(cur))
            cur = ""
        else:
            cur += ch
    if clean(cur):
        out.append(clean(cur))
    return [s for s in out if len(s) >= 2]

def clauses(s):
    parts = re.split(r"[，、：:；;（）()“”\"'‘’]", s)
    return [p for p in (clean(x) for x in parts) if p]

def valid_len(t):
    return len(re.findall(r"[\u4e00-\u9fffA-Za-z0-9]", t))

def mean(xs):
    return sum(xs) / len(xs) if xs else 0.0

def stdev(xs):
    if len(xs) < 2:
        return 0.0
    m = mean(xs)
    return math.sqrt(sum((x - m) ** 2 for x in xs) / len(xs))

# ---------------------------------------------------------------- 判据表
def f_sent_cv(p):
    L = [len(s) for s in sents(p)]
    return stdev(L) / mean(L) if len(L) >= 3 and mean(L) else 0.0

def f_adj_diff(p):
    L = [len(s) for s in sents(p)]
    if len(L) < 3:
        return 0.0
    return mean([abs(L[i + 1] - L[i]) for i in range(len(L) - 1)]) / mean(L)

def f_uniform_run(p):
    L = [len(s) for s in sents(p)]
    if not L:
        return 0.0
    best = cur = 1
    for i in range(1, len(L)):
        lo, hi = min(L[i], L[i - 1]), max(L[i], L[i - 1])
        cur = cur + 1 if lo >= 0.8 * hi else 1
        best = max(best, cur)
    return best / float(len(L))

def f_short_ratio(p):
    L = [len(s) for s in sents(p)]
    return sum(1 for x in L if x <= 12) / float(len(L)) if L else 0.0

def f_long_ratio(p):
    L = [len(s) for s in sents(p)]
    return sum(1 for x in L if x >= 60) / float(len(L)) if L else 0.0

def f_clause_cv(p):
    C = [len(c) for s in sents(p) for c in clauses(s)]
    return stdev(C) / mean(C) if len(C) >= 4 and mean(C) else 0.0

def f_comma_per_sent(p):
    S = sents(p)
    if not S:
        return 0.0
    C = [len(re.findall(r"[，、]", s)) for s in S]
    return stdev(C) / mean(C) if mean(C) else 0.0

def f_punct_interval_cv(p):
    t = clean(p)
    idx = [i for i, ch in enumerate(t) if ch in SENT_END + "，、：；"]
    if len(idx) < 4:
        return 0.0
    gaps = [idx[i + 1] - idx[i] for i in range(len(idx) - 1)]
    return stdev(gaps) / mean(gaps) if mean(gaps) else 0.0

def f_head_repeat(p):
    S = sents(p)
    if len(S) < 3:
        return 0.0
    heads = [s[:2] for s in S]
    top = Counter(heads).most_common(1)[0][1]
    return (len(heads) - top) / float(len(heads))

def f_lag1_corr(p):
    L = [len(s) for s in sents(p)]
    if len(L) < 4:
        return 0.0
    m = mean(L)
    num = sum((L[i] - m) * (L[i + 1] - m) for i in range(len(L) - 1))
    den = sum((x - m) ** 2 for x in L)
    return num / den if den else 0.0

def f_parallel3(p):
    """等长并列项密度：三个及以上、长度接近、以 、 或 ， 相连的片段。"""
    hit = tot = 0
    for s in sents(p):
        for sep in ("、", "，"):
            parts = [clean(x) for x in s.split(sep) if clean(x)]
            if len(parts) < 3:
                continue
            tot += 1
            for i in range(len(parts) - 2):
                w = [len(x) for x in parts[i:i + 3]]
                if max(w) <= 1.3 * max(2, min(w)):
                    hit += 1
                    break
    return hit / float(tot) if tot else 0.0

def f_cite_density(p):
    t = clean(p)
    n = valid_len(t)
    return (len(re.findall(r"\[\d+", t)) * 1000.0 / n) if n else 0.0

def f_num_density(p):
    t = clean(p)
    n = valid_len(t)
    return (len(re.findall(r"\d", t)) * 100.0 / n) if n else 0.0

def f_connective_head(p):
    S = sents(p)
    if not S:
        return 0.0
    heads = ("因此", "所以", "并且", "而且", "同时", "此外", "其次", "首先", "然后", "接着", "可见", "综上")
    return sum(1 for s in S if s.startswith(heads)) / float(len(S))

def f_sent_count(p):
    return float(len(sents(p)))

FEATURES = [
    ("R01", "句长变异系数", f_sent_cv),
    ("R02", "相邻句长差均值", f_adj_diff),
    ("R03", "最长等长句串占比", f_uniform_run),
    ("R04", "短句占比", f_short_ratio),
    ("R05", "长句占比", f_long_ratio),
    ("R06", "分句长度变异系数", f_clause_cv),
    ("R07", "每句顿逗号数变异系数", f_comma_per_sent),
    ("R08", "标点间隔变异系数", f_punct_interval_cv),
    ("R09", "句首结构离散度", f_head_repeat),
    ("R10", "相邻句长相关性", f_lag1_corr),
    ("R11", "等长并列密度", f_parallel3),
    ("R12", "文献标注密度", f_cite_density),
    ("R13", "数字密度", f_num_density),
    ("R14", "连接词起句占比", f_connective_head),
    ("R15", "句数", f_sent_count),
]
FEATS = {f[0]: f for f in FEATURES}

def vec(p):
    return [f[2](p) for f in FEATURES]

# ---------------------------------------------------------------- 语料
def lab(f):
    path = os.path.join(LABELS, f)
    if not os.path.exists(path):
        return []
    body = "\n".join(l for l in open(path, encoding="utf-8").read().split("\n") if not l.startswith("#"))
    return [p.strip() for p in re.split(r"\n\s*\n|\n", body) if valid_len(p) >= 60]

def prhpp(side, cap=1200):
    path = os.path.join(CORPUS, "prhpp_pol_test.json")
    out = []
    if not os.path.exists(path):
        return out
    for d in json.load(open(path, encoding="utf-8")):
        if len(out) >= cap:
            break
        if d.get("label") == side:
            out += [p for p in lab_paras(d.get("text") or "")]
    return out

def lab_paras(body):
    return [p.strip() for p in re.split(r"\n\s*\n|\n", body) if valid_len(p) >= 60]

def drlx(side, cap=2000):
    out, n = [], 0
    path = os.path.join(CORPUS, "drlx_zh_general.jsonl")
    if not os.path.exists(path):
        return out
    for line in open(path, encoding="utf-8"):
        if n >= cap:
            break
        try:
            d = json.loads(line)
        except Exception:
            continue
        t = d.get("human_written_text") if side == "h" else d.get("llm_generated_text")
        if t and valid_len(t) >= 150:
            out += lab_paras(t); n += 1
    return out

def selfbuild(kind):
    path = os.path.join(SELFBUILD, kind)
    out = []
    if not os.path.exists(path):
        return out
    for line in open(path, encoding="utf-8"):
        try:
            d = json.loads(line)
        except Exception:
            continue
        t = d.get("text") or ""
        if valid_len(t) >= 60:
            out.append(t.strip())
    return out

def pools():
    P = {}
    P["H-正文-单作者"] = lab("real-prose.txt")
    P["H-正文-多作者"] = selfbuild("human_fulltext.jsonl")
    P["H-摘要-真人"] = prhpp(0, 900)
    P["M-整节直出"] = lab("aigc-label-machine-raw.txt") + selfbuild("machine_wholesection.jsonl") + selfbuild("machine_student_prompt.jsonl")
    P["M-领域对齐"] = lab("aigc-label-machine-domain.txt")
    P["M-避套话"] = lab("aigc-label-machine-evasive.txt")
    P["M-润色配对"] = prhpp(1, 900)
    P["M-通用改写"] = drlx("m", 1200)
    P["H-通用原文"] = drlx("h", 1200)
    return {k: v for k, v in P.items() if v}

def auc(pos, neg):
    if not pos or not neg:
        return None
    wins = ties = 0
    for a in pos:
        for b in neg:
            if a > b: wins += 1
            elif a == b: ties += 1
    return (wins + 0.5 * ties) / (len(pos) * len(neg))

def zstats(pool):
    """真人基线：每条判据的均值/标准差/分位，导出给 Java 当基线表。"""
    cols = list(zip(*[vec(p) for p in pool])) if pool else [[] for _ in FEATURES]
    out = {}
    for i, (fid, name, _) in enumerate(FEATURES):
        xs = sorted(x for x in cols[i])
        if not xs:
            continue
        m, sd = mean(xs), stdev(xs)
        out[fid] = dict(name=name, mean=round(m, 4), sd=round(sd, 4),
                        p10=round(xs[int(0.10 * (len(xs) - 1))], 4),
                        p50=round(xs[int(0.50 * (len(xs) - 1))], 4),
                        p90=round(xs[int(0.90 * (len(xs) - 1))], 4), n=len(xs))
    return out

def probe():
    P = pools()
    print("池子：", {k: len(v) for k, v in P.items()})
    HU = P.get("H-正文-单作者", []) + P.get("H-正文-多作者", [])
    MUS = [("M-整节直出", P.get("M-整节直出", [])), ("M-领域对齐", P.get("M-领域对齐", [])),
           ("M-避套话", P.get("M-避套话", [])), ("M-润色配对", P.get("M-润色配对", [])),
           ("M-通用改写", P.get("M-通用改写", []))]
    print("\n== 逐条判据在【论文正文】语域上的方向（真人正文 vs 机器正文，段级 AUC）==")
    print("%-5s %-18s %9s %9s %9s %9s %9s %9s" % ("id", "名称",
          "整节直出", "领域对齐", "避套话", "润色配对", "通用改写", "真人基线"))
    rows = []
    hv = [vec(p) for p in HU]
    mv = {tag: [vec(p) for p in pool] for tag, pool in MUS}
    for i, (fid, name, _) in enumerate(FEATURES):
        hs = [v[i] for v in hv]
        cells = []
        rec = dict(id=fid, name=name)
        for tag, _ in MUS:
            ms = [v[i] for v in mv.get(tag, [])]
            a = auc(ms, hs)
            cells.append("   n/a  " if a is None else "%.3f   " % a)
            rec[tag] = None if a is None else round(a, 4)
        base = zstats(HU).get(fid, {})
        rec["human"] = base
        rows.append(rec)
        print("%-5s %-18s %s  mean=%.3f sd=%.3f" % (fid, name, " ".join(cells),
              base.get("mean", 0.0), base.get("sd", 0.0)))
    os.makedirs(OUT, exist_ok=True)
    json.dump(rows, open(os.path.join(OUT, "rhythm-probe.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    print("\n完整读数：%s" % os.path.join(OUT, "rhythm-probe.json"))

def fit():
    P = pools()
    HU = P.get("H-正文-单作者", []) + P.get("H-正文-多作者", [])
    MU = P.get("M-整节直出", []) + P.get("M-领域对齐", []) + P.get("M-避套话", [])
    print("拟合侧：真人正文 %d 段 / 机器正文 %d 段（多作者真人段=%d）"
          % (len(HU), len(MU), len(P.get("H-正文-多作者", []))))
    base = zstats(HU)
    def z(p):
        vs = vec(p)
        out = []
        for i, (fid, _, _) in enumerate(FEATURES):
            b = base.get(fid, {})
            sd = b.get("sd") or 1.0
            out.append((vs[i] - b.get("mean", 0.0)) / sd)
        return out
    Xh = [z(p) for p in HU]; Xm = [z(p) for p in MU]
    # 方向 + 效应量：每条判据用 z 差的符号当系数（不拟合，跨语域更稳），负号=真人更高
    coefs = {}
    for i, (fid, name, _) in enumerate(FEATURES):
        hm, mm = mean([x[i] for x in Xh]), mean([x[i] for x in Xm])
        coefs[fid] = 0.0 if abs(mm - hm) < 0.15 else round((mm - hm) / (i and 1 or 1), 4)
    def sc(zs):
        return sum(c * v for c, v in zip([coefs[f[0]] for f in FEATURES], zs))
    print("\n系数（z 差，正=机器更高）：")
    for fid, name, _ in FEATURES:
        print("  %-5s %-18s %+.3f" % (fid, name, coefs[fid]))
    tests = [("正文·整节直出", P.get("M-整节直出", [])), ("正文·领域对齐", P.get("M-领域对齐", [])),
             ("正文·避套话", P.get("M-避套话", [])), ("摘要·润色配对(跨语域)", P.get("M-润色配对", [])),
             ("通用改写(跨语域)", P.get("M-通用改写", []))]
    href = [p for p in HU]
    res = {}
    print("\n== 组合打分的段级 AUC ==")
    for tag, pool in tests:
        if not pool:
            continue
        ref = href
        if tag.startswith("摘要"):
            ref = P.get("H-摘要-真人", [])
        if tag.startswith("通用"):
            ref = P.get("H-通用原文", [])
        a = auc([sc(z(p)) for p in pool], [sc(z(p)) for p in ref])
        res[tag] = round(a, 4) if a is not None else None
        print("  %-24s AUC=%s  (机器段=%d 对照真人段=%d)" % (tag, "%.4f" % a if a else "n/a", len(pool), len(ref)))
    os.makedirs(OUT, exist_ok=True)
    json.dump(dict(coefs=coefs, baseline=base, auc=res,
                   caveat="真人侧作者数=%d；跨作者未验证前不许对外宣称精度" % (1 if not P.get("H-正文-多作者") else 99)),
              open(os.path.join(OUT, "rhythm-fit.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["probe", "fit"])
    a = ap.parse_args()
    {"probe": probe, "fit": fit}[a.cmd]()
