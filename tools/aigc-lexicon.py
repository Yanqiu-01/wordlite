# -*- coding: utf-8 -*-
"""
从"同一篇人类稿 vs 它的模型版本"配对语料里挖对比词表（contrastive lexicon，
log-odds + Dirichlet 先验，方法出自 Iyyer et al. 2009 / Monroe et al. 2021 一系）。

为什么走这一步：aigc-style-rules.py 量到手写套话表太稀疏——158 个真人段里 142 个一次都不命中，
段级 AUC 只有 0.55~0.69。套话能当证据，当比例不行。要稠密就得让数据自己说"哪些词只在模型笔下出现"。
词表来自配对语料（同选题同领域，只有写法不同），把"内容/领域"这个混淆项消掉。

    py tools/aigc-lexicon.py mine      # 5 折挖词表 + 留出 AUC（含完全外部的本地标注档）
    py tools/aigc-lexicon.py export    # 把留出表现最好那版写成随包资产 aigc-lexicon.tsv

纪律：本地 tests/corpus/ 那批真稿只做外部评测，一次都不进词表拟合。
"""
import argparse, json, math, os, random, re, sys
from collections import Counter, defaultdict

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
CORPUS = os.path.abspath(os.path.join(REPO, "..", "aigc-corpus"))
LABELS = os.path.join(REPO, "tests", "corpus")
OUT = os.path.join(REPO, "artifacts", "agent-aigc-rules")
ASSETS = os.path.join(REPO, "app", "src", "main", "assets", "aigc")
PRIOR = 0.5          # Dirichlet 先验（对称），太小会让生僻词虚高
TOPK = 400           # 每侧取多少条

STOPS = set("的了是在和与也就都很及或等之中为了对将从因此并且但是然而其中通过进行具有相关基于")

def norm(t):
    return re.sub(r"\s+", "", re.sub(r"[^\u4e00-\u9fffA-Za-z0-9]", "", t or ""))

def toks(t):
    s = norm(t)
    out = []
    for n in (2, 3):
        for i in range(len(s) - n + 1):
            g = s[i:i+n]
            if g in STOPS:
                continue
            out.append(g)
    return out

def load_pairs():
    """[(来源, 人类文本, 机器文本)]"""
    out = []
    for name in ("prhpp_pol_train.json", "prhpp_pol_test.json"):
        path = os.path.join(CORPUS, name)
        if not os.path.exists(path):
            continue
        data = json.load(open(path, encoding="utf-8"))
        by = defaultdict(dict)
        for d in data:
            lab, txt = d.get("label"), (d.get("text") or "").strip()
            if isinstance(lab, int) and len(norm(txt)) >= 60:
                by[d.get("title", "")][lab] = txt
        for t, d in by.items():
            if 0 in d and 1 in d:
                out.append(("PRHPPT", d[0], d[1]))
    path = os.path.join(CORPUS, "drlx_zh_general.jsonl")
    if os.path.exists(path):
        n = 0
        for line in open(path, encoding="utf-8"):
            if n >= 4000:
                break
            try:
                d = json.loads(line)
            except Exception:
                continue
            h, m = (d.get("human_written_text") or ""), (d.get("llm_generated_text") or "")
            if len(norm(h)) >= 120 and len(norm(m)) >= 120:
                out.append(("DRLX", h, m)); n += 1
    return out

def read_label(name):
    path = os.path.join(LABELS, name)
    if not os.path.exists(path):
        return []
    raw = open(path, encoding="utf-8").read()
    body = "\n".join(l for l in raw.split("\n") if not l.startswith("#"))
    return [p.strip() for p in re.split(r"\n\s*\n|\n", body) if len(norm(p)) >= 60]

def external():
    hu, ma = [], []
    for f in ("aigc-label-human-verbatim.txt", "aigc-label-human-polish-agent.txt", "real-prose.txt"):
        hu += read_label(f)
    for f in ("aigc-label-machine-raw.txt", "aigc-label-machine-evasive.txt", "aigc-label-machine-domain.txt"):
        ma += read_label(f)
    return hu, ma

def logodds(hc, mc, nh, nm, prior=PRIOR):
    """机器侧相对真人侧的对数几率；nh/nm 是两侧的 token 总量。"""
    vocab = set(hc) | set(mc)
    out = {}
    a = sum(hc.values()) + prior * len(vocab)
    b = sum(mc.values()) + prior * len(vocab)
    for w in vocab:
        ph = (hc[w] + prior) / a
        pm = (mc[w] + prior) / b
        out[w] = math.log(pm) - math.log(ph)
    return out

def score(text, lex):
    ts = toks(text)
    if not ts:
        return 0.0
    return sum(lex.get(t, 0.0) for t in ts) / len(ts)

def auc(pos, neg):
    if not pos or not neg:
        return None
    wins = ties = 0
    for a in pos:
        for b in neg:
            if a > b: wins += 1
            elif a == b: ties += 1
    return (wins + 0.5 * ties) / (len(pos) * len(neg))

def mine(topk=TOPK, prior=PRIOR, seed=7):
    prs = load_pairs()
    random.Random(seed).shuffle(prs)
    srcs = sorted(set(p[0] for p in prs))
    print("配对语料：来源 %s，共 %d 对" % (srcs, len(prs)))
    K = 5
    folds = defaultdict(list)
    for i, p in enumerate(prs):
        folds[i % K].append(p)
    per_src_report = defaultdict(list)
    for k in range(K):
        train = [p for j in range(K) if j != k for p in folds[j]]
        test = folds[k]
        hc, mc = Counter(), Counter()
        for src, h, m in train:
            hc.update(toks(h)); mc.update(toks(m))
        lo = logodds(hc, mc, 1, 1, prior)
        pos = [w for w, _ in sorted(lo.items(), key=lambda x: -x[1])[:topk]]
        negw = [w for w, _ in sorted(lo.items(), key=lambda x: x[1])[:topk]]
        lex = dict([(w, 1.0) for w in pos] + [(w, -1.0) for w in negw])
        for src in srcs:
            tp = [p for p in test if p[0] == src]
            if len(tp) < 20:
                continue
            a = auc([score(m, lex) for _, _, m in tp], [score(h, lex) for _, h, _ in tp])
            per_src_report[src].append(a)
    for src, lst in per_src_report.items():
        print("  %-8s 折内留出 AUC = %.4f  (各折 %s)" % (src, sum(lst)/len(lst), ["%.3f" % x for x in lst]))

    # 完全外部：本地标注真稿 vs 本地机器稿（tests/corpus，一次都没参与拟合）
    hc, mc = Counter(), Counter()
    for src, h, m in prs:
        hc.update(toks(h)); mc.update(toks(m))
    lo = logodds(hc, mc, 1, 1, prior)
    posw = [w for w, _ in sorted(lo.items(), key=lambda x: -x[1])[:topk]]
    negw = [w for w, _ in sorted(lo.items(), key=lambda x: x[1])[:topk]]
    lex = dict([(w, 1.0) for w in posw] + [(w, -1.0) for w in negw])
    hu, ma = external()
    a = auc([score(t, lex) for t in ma], [score(t, lex) for t in hu])
    print("  %-8s 外部留出 AUC = %.4f  (真人段=%d 机器段=%d)" % ("LOCAL", a, len(hu), len(ma)))
    print("  机器侧特权词样例：%s" % " ".join(posw[:25]))
    print("  真人侧特权词样例：%s" % " ".join(negw[:25]))
    os.makedirs(OUT, exist_ok=True)
    json.dump(dict(topk=topk, prior=prior, per_source={k: sum(v)/len(v) for k, v in per_src_report.items()},
                   local_auc=a, machine_terms=posw, human_terms=negw),
              open(os.path.join(OUT, "lexicon-audit.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    return lex

def export():
    lex = mine()
    os.makedirs(ASSETS, exist_ok=True)
    path = os.path.join(ASSETS, "aigc-lexicon.tsv")
    rows = ["term\tweight\tside"]
    for w, v in sorted(lex.items(), key=lambda x: (-x[1], x[0])):
        rows.append("%s\t%s\t%s" % (w, "1.0" if v > 0 else "-1.0", "machine" if v > 0 else "human"))
    open(path, "w", encoding="utf-8", newline="\n").write("\n".join(rows) + "\n")
    print("写出 %s：%d 条，%.1f KB" % (path, len(rows) - 1, os.path.getsize(path) / 1024.0))

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["mine", "export"])
    a = ap.parse_args()
    {"mine": mine, "export": export}[a.cmd]()
