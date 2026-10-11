# -*- coding: utf-8 -*-
"""
节奏族仪器的软肋测量：它到底怕什么，界面文案就得怎么写。

三种扰动，全部只动"排版层面的句子切分"，一个字的用词都不改：
  merge  随机把相邻两句并成一句（模拟"句子写得很长很齐"）
  split  随机把长句在逗号处拆成短句（模拟刻意打散节奏降 AI 味）
  uniform 把每段句子切成等长（最端的节奏伪装）
另外量分数对段长的敏感性（长段不该天然得高分）。

    py tools/aigc-robustness.py
"""
import importlib.util, json, os, random, sys
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
OUT = os.path.join(REPO, "artifacts", "agent-aigc-rules")
ASSETS = os.path.join(REPO, "app", "src", "main", "assets", "aigc")

def load(mod, name):
    sp = importlib.util.spec_from_file_location(name, os.path.join(HERE, mod))
    m = importlib.util.module_from_spec(sp); sp.loader.exec_module(m); return m

PD = load("aigc-paired.py", "pd")
RH = PD.RH
INSTRUMENT = load("aigc-instrument.py", "ins").read()      # 仪器只有一张表：随包资产
ENDS = "。！？；"
_SCORE_CACHE = {}

def score(text):
    """段分一律走随包表算出来的那一个数，缓存住免得同一段反复算。"""
    v = _SCORE_CACHE.get(text)
    if v is None:
        v = INSTRUMENT.score(text); _SCORE_CACHE[text] = v
    return v

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
                a = a.rstrip("".join(ENDS)) + "，" + ss[i + 1].lstrip()
                i += 2
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
                out.append("，".join(parts[:k]) + "。")
                out.append("，".join(parts[k:]))
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

def main():
    rnd = random.Random(23)
    recs = []
    for line in open(os.path.join(RH.CORPUS, "drlx_zh_general.jsonl"), encoding="utf-8"):
        try:
            d = json.loads(line)
        except Exception:
            continue
        if (d.get("domain") or "") != "Academic":
            continue
        h, m = d.get("human_written_text") or "", d.get("llm_generated_text") or ""
        hp, mp = PD.chunk(h, 200, 700), PD.chunk(m, 200, 700)
        if hp and mp:
            recs.append((hp, mp))
        if len(recs) >= 700:
            break
    hu = [p for hp, mp in recs for p in hp][:2500]
    ma = [p for hp, mp in recs for p in mp][:2500]
    print("样本：真人 %d 段 / 机器 %d 段" % (len(hu), len(ma)))
    p99 = INSTRUMENT.p99
    print("仪器 %s，%d 条判据，划线（真人 P99）= %.3f" % (INSTRUMENT.describe(), len(INSTRUMENT.rows), p99))
    def stat(pool, txt=None):
        """命中口径与出厂判定一致：段落 >=120 有效字、>=4 句才允许判可疑。"""
        pool = txt if txt is not None else pool
        pool = [p for p in pool if INSTRUMENT.eligible(p)]
        vs = [score(p) for p in pool]
        hit = sum(1 for v in vs if v > p99) / float(max(1, len(vs)))
        return (sum(vs) / max(1, len(vs))), hit
    hm, hh = stat(hu); mm, mh = stat(ma)
    print("未扰动：真人均分 %.2f 命中 %.1f%%   机器均分 %.2f 命中 %.1f%%" % (hm, 100 * hh, mm, 100 * mh))
    res = {"baseline": dict(human_mean=round(hm, 3), human_hit=round(hh, 4),
                            machine_mean=round(mm, 3), machine_hit=round(mh, 4))}
    for mode in ("merge", "split", "uniform"):
        ph = [perturb(p, mode, rnd) for p in hu]
        pm = [perturb(p, mode, rnd) for p in ma]
        h2, hh2 = stat(None, ph); m2, mh2 = stat(None, pm)
        a = PD.fast_auc([score(p) for p in pm], [score(p) for p in ph])
        print("%-8s 真人均分 %.2f→(扰动后) %.2f 命中 %.1f%%   机器 %.2f→%.2f 命中 %.1f%%   AUC %.3f"
              % (mode, hm, h2, 100 * hh2, mm, m2, 100 * mh2, a))
        res[mode] = dict(human_mean=round(h2, 3), human_hit=round(hh2, 4), machine_mean=round(m2, 3),
                         machine_hit=round(mh2, 4), auc=None if a is None else round(a, 4))
    # 段长敏感性：只看真人侧，长段是否天然高分
    pairs = sorted((RH.valid_len(p), score(p)) for p in hu)
    print("\n真人段分数对段长的敏感性：")
    for lo, hi in ((100, 200), (200, 300), (300, 450), (450, 700)):
        seg = [s for l, s in pairs if lo <= l < hi]
        if len(seg) > 20:
            print("  %3d~%3d 字  n=%-5d 均分 %.2f  P99以上 %.1f%%" % (lo, hi, len(seg), sum(seg) / len(seg),
                  100.0 * sum(1 for s in seg if s > p99) / len(seg)))
            res.setdefault("length", {})["%d-%d" % (lo, hi)] = dict(n=len(seg), mean=round(sum(seg) / len(seg), 3))
    json.dump(res, open(os.path.join(OUT, "robustness.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)

if __name__ == "__main__":
    main()
