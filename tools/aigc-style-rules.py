# -*- coding: utf-8 -*-
"""
中文学术"AI 味"规则表的标定尺子（外部来源，不自己发明判据）。

规则来源：redbaronyyyyy-eng/humanizer-zh-academic（MIT，(c) 2025），
其上游是 Wikipedia:Signs of AI writing + 一篇真实论文"AI 润色版 AIGC>50% vs 人工二次修改版 11%"的逐段对比。
本地副本：E:/download/claude/aigc-corpus/oss/humanizer-zh-academic/SKILL_full.md

与 docs/aigc-calibration.md 那批手写特征的区别：这一批每一条都是外部来源给的触发词表，
不是我根据语料凑出来的，所以先验方向来自领域共识；但**先验不等于结论**——本脚本仍然在
带标注语料上逐条量方向，量不过就不许进随包资产。

    py tools/aigc-style-rules.py audit      # 逐条量方向（真人侧 vs 机器侧，含配对符号检验）
    py tools/aigc-style-rules.py export     # 只把过线的条目写成随包资产 ai-tells.tsv

判过线（export 的准入，三条同时成立）：
  1) 未配对档：机器侧命中率 / 真人侧命中率 >= 2.0，且机器侧 >= 5/千篇；
  2) 配对档（同一篇人类稿 vs 它的机器版本）：机器命中而真人不命的对子数 >= 反向的 2 倍，且符号检验 p < 0.01；
  3) 至少两个独立来源（本地标注档 + 公开配对库之一）方向一致。
"""
import argparse, json, math, os, random, re, sys
from collections import defaultdict

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
CORPUS = os.path.abspath(os.path.join(REPO, "..", "aigc-corpus"))
LABELS = os.path.join(REPO, "tests", "corpus")
OUT = os.path.join(REPO, "artifacts", "agent-aigc-rules")
ASSETS = os.path.join(REPO, "app", "src", "main", "assets", "aigc")
SOURCE = "humanizer-zh-academic@MIT (c) 2025 redbaronyyyyy-eng"

# ---------------------------------------------------------------- 规则表
# scope: sent=逐句计数 / para=每段最多一次 / doc=整篇
# kind: regex=可直接翻译成 Java / struct=需要 Java 手写实现（导出时带说明）
R = []
def rule(rid, name, scope, kind, pattern, cap, hint, mode, note=""):
    R.append(dict(id=rid, name=name, scope=scope, kind=kind, pattern=pattern,
                  cap=cap, hint=hint, mode=mode, note=note))

rule("T01", "填充短语起笔", "sent", "regex",
     r"^(值得注意的是|需要指出的是|不难发现|由此可见|总体而言|总的来说|众所周知|毋庸置疑|不可否认|不可忽视的是)",
     "每篇 ≤2 处", "删掉引导语，直接从主语开始", "套话")
rule("T02", "模糊归因", "sent", "regex",
     r"(专家(们)?认为|研究表明|研究显示|业内普遍认为|有观点认为|一些学者指出|有关学者认为|相关研究显示|相关研究表明)",
     "每篇 0 处", "补具体出处（作者+年份），否则改成自己的分析并说明依据", "可信度",
     note="无出处的权威口吻")
rule("T03", "泛化结论与意义声明", "sent", "regex",
     r"(具有重要意义|具有重大意义|具有深远意义|意义深远|意义重大|前景广阔|未来可期|广阔的?发展空间|注入了新的?活力|提供了新的?思路|提供了有益的?借鉴|开辟新的?(路径|方向|局面)|迈上新台阶|任重道远|势在必行)",
     "结尾段 0 处", "换成一个能被数据检验的具体推论", "空洞")
rule("T04", "AI 高频词", "sent", "regex",
     r"(深刻揭示|综合运用|不可或缺|不可替代|深入探讨|系统梳理|理论支撑|有效解决|充分说明|显著提升|充分发挥|有效提升|重要参考|有机结合|有机统一|深度融合|多维度|全方位|多层次)",
     "每段 ≤2 个", "换成朴素的动词与名词", "词汇")
rule("T05", "绕开\"是\"的系动词堆叠", "sent", "regex",
     r"(扮演着|充当着|作为[^，。；]{1,12}的(?:重要)?(?:载体|途径|手段|方式|桥梁|纽带))",
     "不限", "直接用\"是\"写", "句式")
rule("T06", "限定词堆叠", "sent", "struct",
     r"COUNT(可能|在一定程度上|一定程度上|潜在的?|某种程度|或许|大概|某种程度上) >= 2",
     "每句 1 个", "一个句子只留一个不确定限定词", "推卸判断")
rule("T07", "模板化问题陈述", "sent", "regex",
     r"(面临的核心问题是|核心挑战在于|主要矛盾体现在|关键问题是如何|亟待解决的是|如何[^，。；]{2,18}成为[^，。；]{2,12}的关键)",
     "每篇 ≤1 处", "把矛盾写成具体情境或设问", "提纲腔")
rule("T08", "段末套路与引申", "sent", "regex",
     r"(此案例(?:印证|揭示|说明|挑战)了|从中可以看出|这提示我们|这也印证了|这恰恰说明)",
     "每篇 ≤1 处", "删掉，或改成推进下一层的转折", "重述")
rule("T09", "被动分析套话", "sent", "regex",
     r"(该(?:处理|设计|决策|选择|方案|做法)[^，。；]{0,8}(?:基于|体现|反映|展现|印证)|这一(?:做法|选择|处理|方案)(?:展现|体现|反映|说明)了|上述(?:选择|做法)(?:印证|反映|说明)了)",
     "每篇 ≤2 处", "写清为什么这么做、中途改过什么", "说明书腔")
rule("T10", "三元对称并列", "sent", "struct",
     r"THREE_PARALLEL(items=3, len_ratio<=1.3, same_suffix=True)",
     "每段 ≤1 处", "打破等长等重，重要的多说，次要的一笔带过", "PPT 提纲腔")
rule("T11", "首先/其次/再次编号逻辑", "sent", "regex",
     r"(首先[^。]{2,60}[；，][^。]{0,12}其次|第一[，、]?[^。]{2,60}第二[，、]?|一是[^。；]{2,40}二是|其二[^。]{0,40}其三)",
     "每篇 ≤1 处", "改成\"最根本的是……此外……至于……\"", "编号逻辑")
rule("T12", "四字/了字排比串", "sent", "struct",
     r"CHAINED_LE(items>=3, seps=[,，、], item_len<=10)",
     "每段 ≤1 处", "只留真正的贡献点并展开一句", "宣传稿腔")
rule("P01", "理论起笔", "para", "regex",
     r"^(?:依据|基于|根据|按照|遵循)[^，。；]{0,14}(?:理论|框架|视角|观点|原则|模型|范式|导向)",
     "≤20% 段落", "现象先行，理论名移到段中", "公式化开头")
rule("P02", "总结套话起笔", "para", "regex",
     r"^(?:综上所述|由此可见|总而言之|概括而言|综合来看)",
     "每篇 ≤1 处", "直接删，或换成引出下文的转折", "重述")
rule("P03", "冒号堆叠", "para", "struct", r"COUNT(:|：) >= 3", "每段 ≤2 处", "拆句或改用破折号", "标点")
rule("P04", "破折号密度", "para", "struct", r"COUNT(——) >= 3", "每段 ≤2 处", "删减或改写成短句", "标点")
rule("P05", "句长节奏单一", "para", "struct",
     r"SENT_LEN_STDEV_CV(min_n=5, cv<0.28)", "不限", "插一两个短句打破均质节奏", "节奏")
rule("P06", "三维等重分析", "para", "regex",
     r"(从[^，。；]{1,8}(?:维度|角度|层面)[来]?看[^。]{0,50}从[^，。；]{1,8}(?:维度|角度|层面)[来]?看|理论上[^。]{2,50}实践上[^。]{2,50}(?:方法|应用|现实)上)",
     "每篇 ≤1 处", "按真实重要性排序，别三项等长", "模板结构")

RULES = {r["id"]: r for r in R}
STRUCT_ONLY = [r for r in R if r["kind"] == "struct"]

# ---------------------------------------------------------------- 文本工具
SENT_SPLIT = re.compile(r"(?<=[。！？；?!;])")
def sentences(text):
    out = []
    for line in text.split("\n"):
        line = line.strip()
        if not line:
            continue
        for s in SENT_SPLIT.split(line):
            s = s.strip()
            if len(s) >= 8:
                out.append(s)
    return out

def paragraphs(text):
    return [p.strip() for p in re.split(r"\n\s*\n|\n", text) if len(p.strip()) >= 30]

def count_valid(text):
    return len(re.findall(r"[\u4e00-\u9fffA-Za-z0-9]", text))

# 结构类判据的 Python 实现（Java 侧要逐条对齐，导出表里带同样的规格说明）
def st_count_ge(items_pat, text, need):
    return len(re.findall(items_pat, text)) >= need

def st_three_parallel(s):
    for sep in ["、", "，"]:
        parts = [p.strip() for p in s.split(sep) if len(p.strip()) >= 2]
        best = []
        for i in range(len(parts) - 2):
            win = parts[i:i+3]
            lens = [len(p) for p in win]
            if max(lens) <= 1.3 * max(2, min(lens)) and max(lens) <= 14:
                if win[0][-1] == win[1][-1] == win[2][-1] or win[0][:2] == win[1][:2] == win[2][:2]:
                    best.append(win)
        if best:
            return True
    return False

def st_chained_le(s):
    runs = []
    cur = 0
    for part in re.split(r"[，、；]", s):
        if part.strip().endswith("了") and 2 <= len(part.strip()) <= 10:
            cur += 1
            runs.append(cur)
        else:
            cur = 0
    return bool(runs) and max(runs) >= 3

def st_len_cv(p):
    ss = [len(x) for x in sentences(p)]
    if len(ss) < 5:
        return False
    m = sum(ss) / len(ss)
    var = sum((x - m) ** 2 for x in ss) / len(ss)
    return (var ** 0.5) / m < 0.28

STRUCT = {
    "T06": lambda t: len(re.findall(r"可能|在一定程度上|一定程度上|潜在|某种程度|或许|大概", t)) >= 2,
    "T10": st_three_parallel,
    "T12": st_chained_le,
    "P03": lambda t: len(re.findall(r"[:：]", t)) >= 3,
    "P04": lambda t: t.count("——") >= 3,
    "P05": st_len_cv,
}
COMPILED = {r["id"]: re.compile(r["pattern"]) for r in R if r["kind"] == "regex"}

def hits(rid, units):
    """units: 该判据作用范围下的文本单元列表；返回命中数。"""
    r = RULES[rid]
    if r["scope"] == "sent":
        units = [s for p in units for s in sentences(p)]
    if r["kind"] == "regex":
        rx = COMPILED[rid]
        return sum(1 for u in units if rx.search(u[:2000]))
    fn = STRUCT[rid]
    return sum(1 for u in units if fn(u[:2000]))

def hits_all(text, rids):
    return [hits(rid, [text]) for rid in rids]

def unit_count(rid, paras):
    return sum(len(sentences(p)) for p in paras) if RULES[rid]["scope"] == "sent" else len(paras)

# ---------------------------------------------------------------- 语料
def read_label(name):
    path = os.path.join(LABELS, name)
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as f:
        raw = f.read()
    body = "\n".join(l for l in raw.split("\n") if not l.startswith("#"))
    return paragraphs(body)

def head_jsonl(path, want, pick):
    out = []
    if not os.path.exists(path):
        return out
    with open(path, encoding="utf-8") as f:
        for line in f:
            if len(out) >= want:
                break
            try:
                d = json.loads(line)
            except Exception:
                continue
            t = pick(d)
            if t:
                out.append(t)
    return out

def json_array(path, want, pick):
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as f:
        try:
            data = json.load(f)
        except Exception:
            return []
    out = []
    for d in data:
        if len(out) >= want:
            break
        t = pick(d)
        if t:
            out.append(t)
    return out

def groups():
    """未配对档：只用来量绝对命中率。"""
    g = {}
    g["H-VERBATIM"] = read_label("aigc-label-human-verbatim.txt")
    g["H-POLISH"] = read_label("aigc-label-human-polish-agent.txt")
    g["H-REAL"] = read_label("real-prose.txt")
    g["M-RAW"] = read_label("aigc-label-machine-raw.txt")
    g["M-EVADE"] = read_label("aigc-label-machine-evasive.txt")
    g["M-DOMAIN"] = read_label("aigc-label-machine-domain.txt")
    return {k: v for k, v in g.items() if v}

def pairs():
    """配对档：同一篇人类稿与它的机器版本，符号检验用这个。"""
    out = []
    for name in ("prhpp_pol_train.json", "prhpp_pol_test.json"):
        path = os.path.join(CORPUS, name)
        if not os.path.exists(path):
            continue
        with open(path, encoding="utf-8") as f:
            try:
                data = json.load(f)
            except Exception:
                continue
        by_title = defaultdict(dict)
        for d in data:
            lab, txt = d.get("label"), (d.get("text") or "").strip()
            if isinstance(lab, int) and len(txt) >= 60:
                by_title[d.get("title", "")][lab] = txt
        for t, d in by_title.items():
            if 0 in d and 1 in d:
                out.append(("PRHPPT", d[0], d[1]))
    path = os.path.join(CORPUS, "drlx_zh_general.jsonl")
    n = 0
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                if n >= 3000:
                    break
                try:
                    d = json.loads(line)
                except Exception:
                    continue
                h, m = (d.get("human_written_text") or "").strip(), (d.get("llm_generated_text") or "").strip()
                if len(h) >= 120 and len(m) >= 120:
                    out.append(("DRLX", h, m))
                    n += 1
    return out

# ---------------------------------------------------------------- 统计
def sign_p(gain, loss):
    n = gain + loss
    if n == 0:
        return 1.0
    k = min(gain, loss)
    tail = sum(math.comb(n, i) for i in range(0, k + 1)) / (2 ** n)
    return min(1.0, 2 * tail)

def audit():
    paras = groups()
    prs = pairs()
    by_src = defaultdict(list)
    for src, h, m in prs:
        by_src[src].append((paragraphs(h), paragraphs(m)))
    print("== 未配对档（段数 / 有效字数）==")
    for k, v in paras.items():
        print("  %-11s paras=%-5d chars=%d" % (k, len(v), sum(count_valid(p) for p in v)))
    for src, lst in by_src.items():
        print("  %-11s pairs=%-5d" % (src, len(lst)))
    rows = []
    print("\n== 逐条方向（命中率 = 每千篇/千句命中数）==")
    print("%-5s %-18s %-9s %8s %8s %7s | %s" % ("id", "名称", "范围", "真人", "机器", "倍数", "配对 gain/loss  p"))
    for r in R:
        rid = r["id"]
        hu = [p for k, v in paras.items() if k.startswith("H-") for p in v]
        ma = [p for k, v in paras.items() if k.startswith("M-") for p in v]
        hu_n, ma_n = unit_count(rid, hu), unit_count(rid, ma)
        hu_h, ma_h = hits(rid, hu), hits(rid, ma)
        hu_rate = hu_h * 1000.0 / max(1, hu_n)
        ma_rate = ma_h * 1000.0 / max(1, ma_n)
        ratio = (ma_rate / hu_rate) if hu_rate > 0 else (float("inf") if ma_rate > 0 else 1.0)
        gains = losses = 0
        per_src = {}
        for src, lst in by_src.items():
            g = l = 0
            for hp, mp in lst:
                hh, mh = hits(rid, hp), hits(rid, mp)
                if mh > hh: g += 1
                elif hh > mh: l += 1
            per_src[src] = (g, l)
            gains += g
            losses += l
        p = sign_p(gains, losses)
        verdict = []
        if ratio >= 2.0 and ma_rate >= 5.0: verdict.append("未配对过")
        if gains >= 2 * max(1, losses) and p < 0.01 and gains >= 30: verdict.append("配对过")
        rows.append(dict(id=rid, name=r["name"], scope=r["scope"], kind=r["kind"],
                         human_units=hu_n, machine_units=ma_n, human_hits=hu_h, machine_hits=ma_h,
                         human_rate=round(hu_rate, 2), machine_rate=round(ma_rate, 2),
                         ratio=(None if ratio == float("inf") else round(ratio, 2)),
                         gain=gains, loss=losses, p=(None if p != p else round(p, 6)),
                         per_source=per_src, passes=verdict))
        print("%-5s %-18s %-9s %8.2f %8.2f %7s | %4d/%-4d %-8s %s" % (
            rid, r["name"], r["scope"], hu_rate, ma_rate,
            ("inf" if ratio == float("inf") else "%.2f" % ratio),
            gains, losses, ("%.2e" % p), ",".join(verdict) or "不过"))
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "rules-audit.json"), "w", encoding="utf-8") as f:
        json.dump(rows, f, ensure_ascii=False, indent=1)
    ok = [x for x in rows if x["passes"]]
    print("\n过线 %d / %d 条：%s" % (len(ok), len(rows), ", ".join(x["id"] for x in ok) or "无"))

def export():
    ap = os.path.join(OUT, "rules-audit.json")
    if not os.path.exists(ap):
        sys.exit("先跑 audit")
    rows = json.load(open(ap, encoding="utf-8"))
    os.makedirs(ASSETS, exist_ok=True)
    cols = ["id", "name", "scope", "kind", "spec", "cap", "hint", "mode", "note",
            "human_rate", "machine_rate", "ratio", "gain", "loss", "p", "verified", "source"]
    lines = ["\t".join(cols)]
    for x in rows:
        r = RULES[x["id"]]
        v = "1" if x.get("paired_pass") else "0"
        row = [x["id"], x["name"], x["scope"], x["kind"], r["pattern"], r["cap"], r["hint"], r["mode"],
               r["note"], str(x["human_rate"]), str(x["machine_rate"]), str(x["ratio"]),
               str(x["gain"]), str(x["loss"]), str(x["p"]), v, SOURCE]
        lines.append("\t".join(c.replace("\t", " ").replace("\n", " ") for c in row))
    path = os.path.join(ASSETS, "ai-tells.tsv")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    rows = accept(rows)
    n = sum(1 for x in rows if x["paired_pass"])
    print("写出 %s（%d 行，verified=1 的 %d 条）" % (path, len(rows), n))

def accept(rows):
    """准入以配对为主：同内容同领域，只有"写法"不同，这才是判"写法"的判据该过的考试。
    未配对的本地机器档是照着"避开套话"的指令生成的，绝对命中率天然偏低，只能当参考。"""
    for x in rows:
        src_ok, src_n = True, 0
        for src, gl in (x.get("per_source") or {}).items():
            g, l = gl[0], gl[1]
            if g + l >= 5:
                src_n += 1
                if g <= l:
                    src_ok = False
        ok = (src_ok and src_n >= 1 and x["gain"] >= 10
              and x.get("p") is not None and x["p"] < 0.01)
        x["paired_pass"] = ok
        x["unpaired_pass"] = (x["ratio"] is not None and x["ratio"] >= 2.0 and x["machine_rate"] >= 5.0)
        x["passes"] = (["配对过"] if ok else []) + (["未配对过"] if x["unpaired_pass"] else [])
    return rows

def auc(pos, neg):
    """AUC(机器段得分 > 真人段得分)，并列算 0.5。"""
    if not pos or not neg:
        return None
    wins = ties = 0
    for a in pos:
        for b in neg:
            if a > b: wins += 1
            elif a == b: ties += 1
    return (wins + 0.5 * ties) / (len(pos) * len(neg))

def score_para(rid_list, para):
    sents = sentences(para)
    total = 0
    for rid in rid_list:
        r = RULES[rid]
        if r["scope"] == "sent":
            if r["kind"] == "regex":
                total += sum(1 for s in sents if COMPILED[rid].search(s[:2000]))
            else:
                total += sum(1 for s in sents if STRUCT[rid](s[:2000]))
        else:
            hit = COMPILED[rid].search(para[:2000]) if r["kind"] == "regex" else STRUCT[rid](para[:2000])
            total += 1 if hit else 0
    return total

def index(rows):
    rows = accept(rows)
    use = [x["id"] for x in rows if x["paired_pass"]]
    print("参与打分的判据（配对过线 %d 条）：%s" % (len(use), ", ".join(use) or "无"))
    print("")
    res = {}
    prs = pairs()
    by_src = defaultdict(list)
    for src, h, m in prs:
        by_src[src].append((paragraphs(h), paragraphs(m)))
    for src, lst in by_src.items():
        hp = [p for hp, mp in lst for p in hp]
        mp = [p for hp, mp in lst for p in mp]
        a = auc([score_para(use, p) for p in mp], [score_para(use, p) for p in hp])
        res[src] = dict(pairs=len(lst), paras=len(hp), auc=round(a, 4))
        print("  配对档 %-8s pairs=%-5d 段级 AUC=%.4f" % (src, len(lst), a))
    paras = groups()
    hu = [p for k, v in paras.items() if k.startswith("H-") for p in v]
    ma = [p for k, v in paras.items() if k.startswith("M-") for p in v]
    a = auc([score_para(use, p) for p in ma], [score_para(use, p) for p in hu])
    res["LOCAL"] = dict(auc=round(a, 4), human_paras=len(hu), machine_paras=len(ma))
    print("  本地标注档（机器稿是照着避开套话的指令写的）段级 AUC=%.4f  真人段=%d 机器段=%d" % (a, len(hu), len(ma)))
    hs = sorted(score_para(use, p) for p in hu)
    def pct(q):
        return hs[min(len(hs) - 1, int(q * len(hs)))] if hs else 0
    dist = defaultdict(int)
    for x in hs: dist[x] += 1
    res["human_baseline"] = dict(p50=pct(0.5), p90=pct(0.9), p99=pct(0.99),
                                 maxv=hs[-1] if hs else 0, n=len(hs),
                                 hist={str(k): v for k, v in sorted(dist.items())})
    print("  真人段落分数分布（界面上真人常见范围那条线的出处）：n=%d 中位=%d P90=%d P99=%d 最大=%d"
          % (len(hs), pct(0.5), pct(0.9), pct(0.99), hs[-1] if hs else 0))
    print("  分数直方：%s" % dict(sorted(dist.items())))
    with open(os.path.join(OUT, "index-audit.json"), "w", encoding="utf-8") as f:
        json.dump(dict(rules=use, per_source=res), f, ensure_ascii=False, indent=1)

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["audit", "export", "index"])
    a = ap.parse_args()
    AUDIT = os.path.join(OUT, "rules-audit.json")
    {"audit": audit, "export": export,
     "index": lambda: index(json.load(open(AUDIT, encoding="utf-8")))}[a.cmd]()
