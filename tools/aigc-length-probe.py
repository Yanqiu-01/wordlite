# -*- coding: utf-8 -*-
"""
长段落复检：定标只吃了 100~700 字一段，学位论文里一整段上千字很常见。
R15 句数是"机器更高"的判据，段越长句数越多，长真人段会不会被自己的长度顶过线？

分档量真人段的段分与误报，顺带量机器档，看要不要按段长分档划线。

    py tools/aigc-length-probe.py
"""
import importlib.util, json, os, sys
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
HERE = os.path.dirname(os.path.abspath(__file__)); REPO = os.path.dirname(HERE)
OUT = os.path.join(REPO, "artifacts", "agent-aigc-rules")

def load(mod, name):
    sp = importlib.util.spec_from_file_location(name, os.path.join(HERE, mod))
    m = importlib.util.module_from_spec(sp); sp.loader.exec_module(m); return m

PD = load("aigc-paired.py", "pd"); RH = PD.RH
IMOD = load("aigc-instrument.py", "ins"); INSTRUMENT = IMOD.read()
RH_IDS = IMOD.RHY_IDS
_CACHE = {}

def score(p):
    v = _CACHE.get(p)
    if v is None:
        v = INSTRUMENT.score(p); _CACHE[p] = v
    return v

def contrib(p, rid):
    for r, val in zip(INSTRUMENT.rows, INSTRUMENT.values(p)):
        if r["id"] == rid:
            return r["weight"] * r["sign"] * (val - r["mean"]) / r["sd"]
    return 0.0

def main(lo=650, hi=2600):
    hu, ma = [], []
    for line in open(os.path.join(RH.CORPUS, "drlx_zh_general.jsonl"), encoding="utf-8"):
        try:
            d = json.loads(line)
        except Exception:
            continue
        if (d.get("domain") or "") != "Academic":
            continue
        hu += PD.chunk(d.get("human_written_text") or "", lo, hi)
        ma += PD.chunk(d.get("llm_generated_text") or "", lo, hi)
    print("长段样本：真人 %d 段 / 机器 %d 段（切成 %d~%d 字一段）" % (len(hu), len(ma), lo, hi))
    print("判定线 human_p99=%.3f\n" % INSTRUMENT.p99)
    print("%-12s %-7s %-9s %-9s %-9s %-11s %-11s" % ("段长档", "n", "均分", "中位分", "P90", "过线率", "R15贡献"))
    res = {}
    for blo, bhi in ((650, 800), (800, 1000), (1000, 1300), (1300, 1700), (1700, 2600)):
        hs = [p for p in hu if blo <= RH.valid_len(p) < bhi]
        ms = [p for p in ma if blo <= RH.valid_len(p) < bhi]
        if len(hs) < 25:
            continue
        vs = sorted(score(p) for p in hs)
        n = len(vs)
        over = 100.0 * sum(1 for v in vs if v > INSTRUMENT.p99) / n
        r15 = sum(contrib(p, "R15") for p in hs) / n
        mhit = ("%.1f%%" % (100.0 * sum(1 for p in ms if score(p) > INSTRUMENT.p99) / len(ms))) if ms else "-"
        print("%-12s %-7d %-9.2f %-9.2f %-9.2f %-11s %-11.2f  机器 n=%d 过线 %s"
              % ("%d~%d" % (blo, bhi), n, sum(vs) / n, vs[n // 2], vs[int(0.9 * (n - 1))], "%.2f%%" % over, r15, len(ms), mhit))
        res["%d-%d" % (blo, bhi)] = dict(n=n, mean=round(sum(vs) / n, 3), p50=round(vs[n // 2], 3),
                                         over_pct=round(over, 2), r15=round(r15, 3),
                                         machine_n=len(ms), machine_over_pct=None if not ms else round(100.0 * sum(1 for p in ms if score(p) > INSTRUMENT.p99) / len(ms), 1))
    json.dump(res, open(os.path.join(OUT, "length-probe.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("写出 artifacts/agent-aigc-rules/length-probe.json")

if __name__ == "__main__":
    main()
