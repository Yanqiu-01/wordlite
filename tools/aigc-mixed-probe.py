# -*- coding: utf-8 -*-
"""
混合文档考试：一份稿子里只有几段是机器写的，现在这台仪器还能不能挑出来。

真实场景就是这样——学位论文二十段里三段是模型整段写的，不是整节都是。
只按"绝对线"（真人 P99）判，混在真人稿里的机器段可能整批漏掉；
再加一条"文档内相对线"（这一段比同篇其余段落明显更像机器）能不能补上，补多少，
代价是纯真人稿的误报涨多少。这一张表决定要不要给 Java 侧加第二条线。

    py tools/aigc-mixed-probe.py
"""
import importlib.util, json, os, random, statistics, sys
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
HERE = os.path.dirname(os.path.abspath(__file__)); REPO = os.path.dirname(HERE)
OUT = os.path.join(REPO, "artifacts", "agent-aigc-rules")

def load(mod, name):
    sp = importlib.util.spec_from_file_location(name, os.path.join(HERE, mod))
    m = importlib.util.module_from_spec(sp); sp.loader.exec_module(m); return m

PD = load("aigc-paired.py", "pd"); RH = PD.RH
INSTRUMENT = load("aigc-instrument.py", "ins").read()
P99 = INSTRUMENT.p99
P90 = INSTRUMENT.p90
_CACHE = {}

def score(p):
    v = _CACHE.get(p)
    if v is None:
        v = INSTRUMENT.score(p); _CACHE[p] = v
    return v

def eligible(p):
    return INSTRUMENT.eligible(p)

def main(docs_per_kind=220, min_paras=5):
    by_model = {}
    for line in open(os.path.join(RH.CORPUS, "drlx_zh_general.jsonl"), encoding="utf-8"):
        try:
            d = json.loads(line)
        except Exception:
            continue
        if (d.get("domain") or "") != "Academic":
            continue
        hp = PD.chunk(d.get("human_written_text") or "", 100, 700)
        mp = PD.chunk(d.get("llm_generated_text") or "", 100, 700)
        if len(hp) >= 3 and len(mp) >= 2:
            by_model.setdefault(d.get("model") or "?", []).append((hp, mp))
    print("可用配对（真人段>=%d、机器段>=2，按生成器分桶）：%s"
          % (min_paras, {k: len(v) for k, v in by_model.items()}))
    rnd = random.Random(41)
    for v in by_model.values():
        rnd.shuffle(v)

    def build(k, want_machine):
        """一份混合稿：真人段若干，随机把 want_machine 段换成"同一生成器另一篇"的机器段。"""
        models = [m for m, v in by_model.items() if len(v) > k + want_machine]
        if not models:
            return None
        mo = rnd.choice(models)
        pool = by_model[mo]
        # 这批配对每篇只切得出 2~4 段，凑不满一篇的上下文就再接上邻篇的真人段：
        # 判据只看写法，跨选题拼接不影响读数，但给"文档内相对线"留出可比段落
        base = list(pool[k][0])
        j = k + 1
        while len(base) < min_paras and j < len(pool):
            base += list(pool[j][0]); j += 1
        if len(base) < min_paras:
            return None
        donor = pool[k + 1 + (k % max(1, len(pool) - k - 2))][1]
        if not donor:
            return None
        spots = rnd.sample(range(len(base)), min(want_machine, len(base)))
        flags = [False] * len(base)
        for j, s in enumerate(spots):
            base[s] = donor[j % len(donor)]
            flags[s] = True
        return base, flags

    def relz(scores):
        """文档内相对线：以本篇可比段的中位数与 MAD 作稳健基线。"""
        if len(scores) < min_paras:
            return [0.0] * len(scores)
        med = statistics.median(scores)
        mad = statistics.median([abs(x - med) for x in scores]) * 1.4826
        if mad < 1e-6:
            mad = statistics.pstdev(scores) or 1.0
        return [(s - med) / mad for s in scores]

    def run(mixed):
        """逐篇给出 (真值, 段分, 文档内 z) 三元组，按篇分组保留，文档级规则要看得见整篇。"""
        docs = []
        for paras, truth in mixed:
            sc = [score(p) for p in paras]
            rz = relz(sc)
            rows = [(t, s, z) for p, s, z, t in zip(paras, sc, rz, truth) if eligible(p)]
            if rows:
                docs.append(rows)
        return docs

    def clean_majority(rows):
        """整篇里过 P90 的可比段不超过三分之一，才允许用文档内相对线：
        整节都是机器稿时相对线没有基线可用，硬用只会自己骗自己。"""
        above = sum(1 for _t, s, _z in rows if s > P90)
        return above * 3 <= len(rows)

    RULES = (("绝对线", lambda rows, r: r[1] > P99),
             ("绝对或相对(P90,z>2)", lambda rows, r: r[1] > P99 or (r[1] > P90 and r[2] > 2.0)),
             ("绝对或相对+多数干净", lambda rows, r: r[1] > P99 or (clean_majority(rows) and r[1] > P90 and r[2] > 2.0)),
             ("绝对或相对+多数干净(z>2.5)", lambda rows, r: r[1] > P99 or (clean_majority(rows) and r[1] > P90 and r[2] > 2.5)))

    def report(tag, docs):
        tp = [(d, r) for d in docs for r in d if r[0]]
        fp = [(d, r) for d in docs for r in d if not r[0]]
        if not tp or not fp:
            return None
        print("%-26s 可比段 机器 %d / 真人 %d" % (tag, len(tp), len(fp)))
        out = {}
        for name, rule in RULES:
            hit = sum(1 for d, r in tp if rule(d, r)) / float(len(tp))
            wrong = sum(1 for d, r in fp if rule(d, r)) / float(len(fp))
            out[name] = (round(100 * hit, 1), round(100 * wrong, 2))
            print("    %-26s 机器段命中 %5.1f%%   真人段误报 %5.2f%%" % (name, 100 * hit, 100 * wrong))
        return out

    res = {}
    for want in (1, 2, 3):
        mixed = []
        for k in range(docs_per_kind):
            d = build(k, want)
            if d:
                mixed.append(d)
        res["mixed%d" % want] = report("每篇混入 %d 段机器（%d 篇）" % (want, len(mixed)), run(mixed))
    pure = []
    for k in range(docs_per_kind):
        pool = rnd.choice(list(by_model.values()))
        d = list(pool[k % len(pool)][0])
        j = k % len(pool) + 1
        while len(d) < min_paras and j < len(pool):
            d += list(pool[j][0]); j += 1
        pure.append((d, [False] * len(d)))
    res["pure_human"] = report("纯真人稿（%d 篇）" % len(pure), run(pure))
    json.dump(res, open(os.path.join(OUT, "mixed-probe.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("写出 artifacts/agent-aigc-rules/mixed-probe.json")

if __name__ == "__main__":
    main()
