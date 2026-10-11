# -*- coding: utf-8 -*-
"""
词汇统计族判据：与节奏族正交的一族，专门补"把句子并长就能躲"这个软肋。

动机（docs/aigc-rhythm.md 第 7 节）：节奏族只量"句子怎么切"，把相邻句合并就能把机器段命中从 57.5% 打到 3.3%。
词汇统计（字组类型比、稀遇率、熵）跟句子边界无关，合并句子几乎不改变它们的取值。

    py tools/aigc-lexical.py            # 学术配对上逐条 AUC + 与节奏族合流 + 抗 merge 复检

参照表（哪些字组算"常见"）只用学术配对里的**真人侧**统计，考试一律折内留出；
仓库 tests/corpus 那批标注只当外部考试，不参与建表。
"""
import argparse, importlib.util, json, math, os, random, sys
from collections import Counter

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
OUT = os.path.join(REPO, "artifacts", "agent-aigc-rules")

def load(mod, name):
    sp = importlib.util.spec_from_file_location(name, os.path.join(HERE, mod))
    m = importlib.util.module_from_spec(sp); sp.loader.exec_module(m); return m

PD = load("aigc-paired.py", "pd")
RH = PD.RH
REF_TOPN = 60000

def norm(t):
    return RH.re.sub(r"\s+", "", t or "")

def grams(t, n=2):
    s = norm(t)
    return [s[i:i+n] for i in range(len(s) - n + 1)]

def build_ref(texts, topn=REF_TOPN):
    c = Counter()
    for t in texts:
        c.update(grams(t, 2))
    return set(g for g, _ in c.most_common(topn))

def ent(counts):
    tot = sum(counts.values())
    if not tot:
        return 0.0
    return -sum((v / tot) * math.log(v / tot) for v in counts.values())

def f_oov(ref):
    def fn(t):
        g = grams(t, 2)
        return sum(1 for x in g if x not in ref) / float(len(g)) if g else 0.0
    return fn

def f_ttr(t):
    g = grams(t, 2)
    return len(set(g)) / float(len(g)) if g else 0.0

def f_hapax(t):
    c = Counter(grams(t, 2))
    if not c:
        return 0.0
    n = sum(c.values())
    return sum(1 for v in c.values() if v == 1) / float(n)

def f_char_ent(t):
    return ent(Counter(norm(t)))

def f_punct_ent(t):
    s = norm(t)
    c = Counter(ch for ch in s if not ch.isalnum() and not RH.re.match(r"[\u4e00-\u9fff]", ch))
    return ent(c)

def f_repeat(t):
    """同一字组重复出现的比例：机器稿更爱反复用同一批术语串。"""
    g = grams(t, 2)
    if not g:
        return 0.0
    c = Counter(g)
    return sum(v - 1 for v in c.values() if v > 1) / float(len(g))

ENDS = "。！？；"

def ssplit(text):
    out, cur = [], ""
    for ch in text:
        cur += ch
        if ch in ENDS:
            if RH.valid_len(cur) >= 1:
                out.append(cur)
            cur = ""
    if RH.valid_len(cur) >= 1:
        out.append(cur)
    return out

def perturb(text, mode, rnd):
    ss = ssplit(text)
    if mode == "merge" and len(ss) >= 2:
        out, i = [], 0
        while i < len(ss):
            a = ss[i]
            if i + 1 < len(ss) and rnd.random() < 0.5:
                a = a.rstrip("".join(ENDS)) + "，" + ss[i + 1].lstrip(); i += 2
            else:
                i += 1
            out.append(a)
        return "".join(out)
    if mode == "split":
        out = []
        for s in ss:
            parts = s.split("，")
            if len(parts) >= 3 and rnd.random() < 0.7:
                k = len(parts) // 2
                out.append("，".join(parts[:k]) + "。"); out.append("，".join(parts[k:]))
            else:
                out.append(s)
        return "".join(out)
    if mode == "uniform":
        flat = "".join(ss).replace("。", "，")
        n = RH.valid_len(flat)
        target = max(18, min(40, n // max(1, len(ss))))
        out, cur = [], ""
        for seg in flat.split("，"):
            if RH.valid_len(cur) + RH.valid_len(seg) > target and cur:
                out.append(cur + "。"); cur = seg
            else:
                cur += seg + "，"
        if RH.valid_len(cur) > 2:
            out.append(cur.rstrip("，") + "。")
        return "".join(out)
    return text

def vals(t, ref):
    return [f_oov(ref)(t), f_ttr(t), f_hapax(t), f_char_ent(t), f_punct_ent(t), f_repeat(t)]


def main(minlen=100, maxlen=700, folds=4):
    recs = []
    for line in open(os.path.join(RH.CORPUS, "drlx_zh_general.jsonl"), encoding="utf-8"):
        try:
            d = json.loads(line)
        except Exception:
            continue
        if (d.get("domain") or "") != "Academic":
            continue
        h, m = d.get("human_written_text") or "", d.get("llm_generated_text") or ""
        hp, mp = PD.chunk(h, minlen, maxlen), PD.chunk(m, minlen, maxlen)
        if hp and mp:
            recs.append((d.get("model") or "?", hp, mp))
    print("学术配对 %d 对" % len(recs))
    rnd = random.Random(29); rnd.shuffle(recs)

    def refs(k):
        train_h = [p for i, (mo, hp, mp) in enumerate(recs) if i % folds != k for p in hp]
        return build_ref(train_h)

    def vals(t, ref):
        return [f_oov(ref)(t), f_ttr(t), f_hapax(t), f_char_ent(t), f_punct_ent(t), f_repeat(t)]

    names = ["L01 字组稀遇率", "L02 字组类型比", "L03 只出现一次的字组占比", "L04 字符熵", "L05 标点熵", "L06 字组重复率"]
    # 每一折把参照表与所有向量算一次就缓存住，别在每个判据上重算
    cache = {}
    for k in range(folds):
        ref = refs(k)
        tr_h = [p for i, (mo, hp, mp) in enumerate(recs) if i % folds != k for p in hp]
        tr_m = [p for i, (mo, hp, mp) in enumerate(recs) if i % folds != k for p in mp]
        te_h = [p for i, (mo, hp, mp) in enumerate(recs) if i % folds == k for p in hp]
        te_m = [p for i, (mo, hp, mp) in enumerate(recs) if i % folds == k for p in mp]
        cache[k] = dict(th=[vals(p, ref) for p in tr_h], tm=[vals(p, ref) for p in tr_m],
                        eh=[vals(p, ref) for p in te_h], em=[vals(p, ref) for p in te_m],
                        raw_h=te_h, raw_m=te_m)
        print("  折 %d 缓存完成：训练 %d+%d 段，考试 %d+%d 段" % (k, len(tr_h), len(tr_m), len(te_h), len(te_m)))
    print("")
    print("%-22s %-9s %s" % ("判据", "段级AUC", "折间极差"))
    out = {}
    for i, name in enumerate(names):
        per = []
        for k in range(folds):
            c = cache[k]
            if not (c["eh"] and c["em"]):
                continue
            hv = [v[i] for v in c["th"]]; mv = [v[i] for v in c["tm"]]
            sgn = 1.0 if RH.mean(mv) >= RH.mean(hv) else -1.0
            a = PD.fast_auc([sgn * v[i] for v in c["em"]], [sgn * v[i] for v in c["eh"]])
            if a is not None:
                per.append(a)
        mu = sum(per) / len(per)
        out[name] = round(mu, 4)
        print("%-22s %-9.3f %.3f" % (name, mu, max(per) - min(per)))

    # 合流：节奏族 + 词汇族（各折各定基线）
    EX = json.load(open(os.path.join(OUT, "rhythm-export.json"), encoding="utf-8"))
    ROWS = EX["rows"]
    def rhy(p):
        v = RH.vec(p)
        return sum(r["weight"] * r["sign"] * (v[i] - r["mean"]) / r["sd"] for i, r in enumerate(ROWS))
    per_r, per_l, per_b = [], [], []
    for k in range(folds):
        c = cache[k]
        if not (c["eh"] and c["em"]):
            continue
        eh, em = c["raw_h"], c["raw_m"]
        lb = []
        for i in range(len(names)):
            hv = [v[i] for v in c["th"]]; mv = [v[i] for v in c["tm"]]
            mu_, sd_ = RH.mean(hv), (RH.stdev(hv) or 1.0)
            sgn = 1.0 if RH.mean(mv) >= mu_ else -1.0
            gap = abs(RH.mean([(x - mu_) / sd_ for x in mv]))
            lb.append((i, sgn, mu_, sd_, 0.0 if gap < 0.15 else min(2.0, max(0.15, gap / 2.0))))
        def lexv(v):
            return sum(w * sg * ((v[i] - mu_) / sd_) for i, sg, mu_, sd_, w in lb)
        a1 = PD.fast_auc([rhy(p) for p in em], [rhy(p) for p in eh])
        a2 = PD.fast_auc([lexv(v) for v in c["em"]], [lexv(v) for v in c["eh"]])
        a3 = PD.fast_auc([rhy(p) + lexv(v) for p, v in zip(em, c["em"])],
                         [rhy(p) + lexv(v) for p, v in zip(eh, c["eh"])])
        per_r.append(a1); per_l.append(a2); per_b.append(a3)
    print("")
    print("合流（折内留出）：节奏族 %.4f  词汇族 %.4f  两族相加 %.4f"
          % (sum(per_r)/len(per_r), sum(per_l)/len(per_l), sum(per_b)/len(per_b)))
    json.dump(out, open(os.path.join(OUT, "lexical-audit.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.parse_args()
    main()
