#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""段落级风格统计的实测台：把计分单位从"一句"换成"一段 120~656 字"，逐条量 AUC、真人误报、同一阈值上的机器召回。

为什么换判别面：一句话只有几十字，句长分布、人称密度、连接词密度、数字与单位写法密度这些风格统计
在那么短的单位上根本不稳；而应用侧 AIGC 打分本来就是按段打的。见 docs/aigc-detection.md §8 第二条。

四档（切分组按"同一篇人类原文"，拟合侧与留出侧不共用一篇原文；见 prep 里的 doc）：
  FIT    DetectRL-X zh Academic 的 train 划分（1,600 篇原文，四台模型改写）——只在这里看方向、选特征、定阈值
  VAL    同一数据集 Academic 的 test 划分（1,000 篇）——段落级配对留出：同一篇原文的人写段 对 机写段
  H2     段落级真人真稿留出（清单 tests/corpus/aigc-holdout2-manifest.json，PMC 开放获取正文 4,455 段）
         ——另一家来源、另一套排版（XML 抽的，没有 PDF 断字），只在最后量一次真人误报
  OOD    同一数据集非学术那五个域（News/Wiki/Novel/SEO/Webtext）：train 划分用来查特征方向稳不稳，
         test 划分的机器段用来看换域还抓不抓得到
先验符号在 SIGN 里写死，量完不许翻符号；[11] 这类引文序号、①② 这类带圈号、汉字间断字空格是 PDF 抽取痕迹，
不是行文风格，列在 LEAKY 里只报不选。

跑法：
  py tools/aigc-para-probe.py prep    # 切段、配对、算特征，落 artifacts/aigc-para-probe/units.jsonl（不进仓库）
  py tools/aigc-para-probe.py sig     # 逐特征表：拟合 AUC、五域方向一致性、头部召回
  py tools/aigc-para-probe.py fit     # 组合与阈值：真人误报（每千段）与同一阈值上的机器召回
"""
import argparse
import collections
import hashlib
import io
import json
import math
import os
import re
import sys

import numpy as np

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.abspath(os.path.join(REPO, "..", "aigc-corpus"))
DRLX_DEFAULT = os.path.join(DATA, "drlx_zh_general.jsonl")
HOLDOUT2_DEFAULT = os.path.join(DATA, "holdout2", "segments.jsonl")
ART = os.path.join(REPO, "artifacts", "aigc-para-probe")
UNITS = os.path.join(ART, "units.jsonl")
# 读数清单进仓库（只有计数与读数，一段原文都不进）；check 档会重算比对，防"文档里的数与跑出来的数两张皮"
SUMMARY_DEFAULT = "tests/corpus/aigc-para-probe-summary.json"
# 随包判据那五个文件的指纹：本工具一条都不许改；动了 check 就红
SHIPPED_GUARD = ["app/src/main/java/com/rikkahub/wordlite/AigcScorer.java",
                 "app/src/main/java/com/rikkahub/wordlite/AigcNgramModel.java",
                 "app/src/main/assets/aigc/aigc-model.tsv",
                 "app/src/main/assets/aigc/aigc-zh-freq.tsv",
                 "app/src/main/assets/aigc/aigc-zh-weights.tsv"]

FLOOR, TARGET, CEIL, TOL = 160, 300, 520, 90   # 段落档：切到 300~390 字为主，与 H2 真人段（中位 349 字）同量级
FP_GATE = 2.0                            # 验收线：真人误报 <= 2 段/千段
H2_FLOOR, H2_CEIL = 120, 656             # 段落级真人真稿那把尺子整把收（120~656 字），不把难量那一头剪掉
BANDS = [(120, 199), (200, 319), (320, 479), (480, 656)]   # 报误报时按长度带分开看，别拿总量盖住某一格
AUC_GATE = 0.85                          # 验收线：AUC(机器>真人)
LEN_RATIO = (0.70, 1.43)                 # 配对段的长度比：不许让 AUC 靠"机器段更长"白拿
PAIR_DRIFT = 0.12                        # 配对段在全文里的相对位置差上限
OOD_DOMAINS = ["News", "Wiki", "Novel", "SEO", "Webtext"]
# 第三档：出厂那条审计自己用的标注语料（H1 学位论文正文 / H2 已发表摘要逐字 / H3 知网片段 / X1 轻度润色 /
# HH 最难真人句 对 M-RAW / M-EVADE / M-DOMAIN）。机器侧生成前读过判据，所以这一档只能当"换一批稿子还成不成"
# 的旁证，不能当验收；长度门槛沿用出厂那条的 120 字。
T3_TIERS = [("H1", "tests/corpus/real-prose.txt", "human"),
            ("H2", "tests/corpus/aigc-label-human-verbatim.txt", "human"),
            ("H3", "tests/corpus/cnki-cross.txt", "human"),
            ("X1", "tests/corpus/aigc-label-human-polish-agent.txt", "human"),
            ("HH", "tests/corpus/aigc-hard-human.txt", "human"),
            ("M-RAW", "tests/corpus/aigc-label-machine-raw.txt", "machine"),
            ("M-EVADE", "tests/corpus/aigc-label-machine-evasive.txt", "machine"),
            ("M-DOMAIN", "tests/corpus/aigc-label-machine-domain.txt", "machine")]
MAX_CHUNKS_PER_DOC = 8

# ---------------------------------------------------------------- 归一化与切段
WS = re.compile(r"[ \t\u3000\u00a0\u2007\u202f]+")
CJK = "\u4e00-\u9fff\u3400-\u4dbf"
# PDF 抽出来的正文满行是"汉 族""德 国 Qiagen"这种断字空格；汉字两侧的单空格一律抹掉，
# 两侧都不是汉字（纯英文术语之间）的空格保留。抹掉之后汉字间空格这个痕迹就不在任何特征里了。
SP_CJK = re.compile(r"(?<=[" + CJK + r"]) +| +(?=[" + CJK + r"])")
RX_CJKRUN = re.compile(r"[" + CJK + r"]")


def norm(text):
    return WS.sub("", text.replace("\r", "\n")).strip()


def flat(text):
    """抹掉汉字与汉字/汉字与英文之间的单空格（断字痕迹），字符级统计一律在它上面算。"""
    return SP_CJK.sub("", text)


def spacing_artifact(text):
    """抹掉之前有多少个这类空格：只当"这批人写稿是不是 PDF 抽的"的仪表，不进任何候选。"""
    return len(SP_CJK.findall(text))


SENT_END = "。！？；!?;…"


def sentences(text):
    out, cur = [], []
    for ch in text:
        if ch == "\n":
            if cur:
                out.append("".join(cur))
                cur = []
            continue
        cur.append(ch)
        if ch in SENT_END:
            out.append("".join(cur))
            cur = []
    if cur:
        out.append("".join(cur))
    return [s.strip() for s in out if s.strip()]


def chunks(text, floor=FLOOR, target=TARGET, tol=TOL, ceil=CEIL, cap=MAX_CHUNKS_PER_DOC):
    """按句号级边界贪心打包：够 target 就收，再加一句会过 ceil 就断段。切不出合用长度的段直接丢。"""
    out, cur = [], ""
    for s in sentences(flat(norm(text))):
        n = len_ns(s)   # 段长口径：全部非空字符
        if not cur:
            cur = s
            continue
        l = len_ns(cur)
        if l < target and l + n <= ceil:
            cur = cur + s                       # 没到目标长度，继续收
        elif l < target + tol and l + n <= ceil:
            cur = cur + s                       # 略过目标也认，别让下一段起点太偏
        else:
            out.append(cur)
            cur = s
        if len(out) >= cap:
            break
    if cur:
        out.append(cur)
    return [c for c in out if floor <= len_ns(c) <= ceil]


def doc_key(text):
    return hashlib.sha1(text.encode("utf-8")).hexdigest()[:12]

# ---------------------------------------------------------------- 段落级风格统计
RX_NS = re.compile(r"\s+")
RX_DIGIT = re.compile(r"[0-9０-９]")
RX_COLON = re.compile(r"[：:]")
RX_LISTMARK = re.compile(r"(?:^|[。；；！？!?])\s*(?:[0-9０-９]{1,2}\s*[、.）)]|[（(]\s*[0-9０-９]{1,2}\s*[）)])")
RX_MD = re.compile(r"(?:\*\*|^\s*[•·-]\s|\d\s*\))")
RX_DASH = re.compile(r"[—–―─－]")
RX_QUOTE = re.compile(r"[“”「」]")
RX_CITE = re.compile(r"[\[［][0-9０-９，,、\-—–~～\s]{1,14}[\]］]")
RX_CIRC = re.compile(r"[①-⑳⑴-⒇]")
RX_LATIN = re.compile(r"[A-Za-z]")
RX_UNIT = re.compile(r"[0-9０-９]\s*(?:%|％|mg|ml|mL|kg|μg|µg|nm|mm|cm|℃|°C|U/L|IU|ng|μl|µl|g\b|h\b|min\b|L\b)")
RX_MEASURE = re.compile(r"[0-9０-９][0-9０-９.]*\s*(?:例|岁|只|例次|片|ml)")
RX_OUR = re.compile(r"我们|咱们")
RX_SELF = re.compile(r"本文|笔者|本研究|本试验|本方法|该方法|该研究")
RX_BEI = re.compile(r"被")
RX_CLAUSE = re.compile(r"[，、：：,;]")
RX_PUNCT = re.compile(r"[，。、；：！？（）()《》“”\"'—…．·／/％%－\-_【】\[\]％]")

# 机器中文里常见的"框架连接词"：枚举、递进、总结那一批。写死，不从这里之外现编。
ENUM_WORDS = ["首先", "其次", "再次", "再者", "最后", "此外", "另外", "同时", "综上", "总而言之",
              "总的来说", "总的来看", "值得注意", "需要指出", "需要强调", "不难发现", "由此可见",
              "一言以蔽之", "其一", "其二", "第一", "第二", "第三"]
ENUM_CORE = ["首先", "其次", "再次", "再者", "最后"]
CONNECTIVES = ["不仅", "而且", "并且", "但是", "然而", "不过", "或者", "以及", "即使", "由于", "因此", "所以"]
FRAME_TAIL = ["总之", "综上", "总而言之", "总的来说", "总的来看", "由此可见", "不难发现"]
FRAME_LEAD = ["引言", "前言", "研究背景", "概述", "简介", "背景"]

# 先验符号：+1 = 机器段该更高，-1 = 机器段该更低，0 = 只报不选（诊断）。量完不许翻。
SIGN = collections.OrderedDict([
    ("colon_p100", 1), ("enum_p100", 1), ("enum_distinct", 1), ("enum_triad", 1),
    ("listmark_p100", 1), ("md_p100", 1), ("dash_p100", 1), ("quote_p100", 1),
    ("our_p100", 1), ("frame_tail", 1), ("frame_lead", 1),
    ("start_repeat_run", 1), ("start_repeat_pairs", 1), ("clause_mean", 1), ("sent_mean", 1),
    ("paren_half", 1), ("connective_distinct", 1),
    ("sent_cv", -1), ("sent_std", -1), ("frac_short", -1), ("frac_long", -1),
    ("start_diverse", -1), ("ttr_cjk", -1), ("punct_entropy", -1),
    ("digit_p100", -1), ("unit_p100", -1), ("measure_p100", -1), ("self_p100", -1), ("bei_p100", -1),
    # 下面这五条是痕迹或量纲，只当仪表：引文序号与带圈号是"排版有没有参考文献"，不是行文风格；
    # 断字空格是 PDF 抽取痕迹；拉丁密度两边都高；段长是反证尺。
    ("chars", 0), ("n_sent", 0), ("cite_p100", 0), ("circ_p100", 0), ("space_artifact_p100", 0),
    ("latin_p100", 0),
])
LEAKY = set(["cite_p100", "circ_p100", "space_artifact_p100"])
NAN = float("nan")


def entropy(vals):
    tot = float(sum(vals))
    if tot <= 0:
        return NAN
    e = 0.0
    for v in vals:
        if v > 0:
            p = v / tot
            e -= p * math.log(p, 2.0)
    return e


def feats_of(raw):
    """一个段落单位的全部风格统计。单位长度=非空字符数，密度一律按每百字。"""
    body = flat(norm(raw))
    art = spacing_artifact(norm(raw))
    n = max(1, len(RX_NS.sub("", body)))
    ss = [RX_NS.sub("", s) for s in sentences(body)]
    ss = [s for s in ss if s]
    lens = [len(s) for s in ss]
    f = {}
    f["chars"] = float(n)
    f["n_sent"] = float(len(ss))
    f["space_artifact_p100"] = 100.0 * art / n
    f["cite_p100"] = 100.0 * len(RX_CITE.findall(body)) / n
    f["circ_p100"] = 100.0 * len(RX_CIRC.findall(body)) / n
    f["latin_p100"] = 100.0 * len(RX_LATIN.findall(body)) / n
    f["digit_p100"] = 100.0 * len(RX_DIGIT.findall(body)) / n
    f["colon_p100"] = 100.0 * len(RX_COLON.findall(body)) / n
    f["listmark_p100"] = 100.0 * len(RX_LISTMARK.findall(body)) / n
    f["md_p100"] = 100.0 * len(RX_MD.findall(body)) / n
    f["dash_p100"] = 100.0 * len(RX_DASH.findall(body)) / n
    f["quote_p100"] = 100.0 * len(RX_QUOTE.findall(body)) / n
    f["unit_p100"] = 100.0 * len(RX_UNIT.findall(body)) / n
    f["measure_p100"] = 100.0 * len(RX_MEASURE.findall(body)) / n
    f["our_p100"] = 100.0 * len(RX_OUR.findall(body)) / n
    f["self_p100"] = 100.0 * len(RX_SELF.findall(body)) / n
    f["bei_p100"] = 100.0 * len(RX_BEI.findall(body)) / n
    f["enum_p100"] = 100.0 * sum(body.count(w) for w in ENUM_WORDS) / n
    f["enum_distinct"] = float(sum(1 for w in ENUM_WORDS if w in body))
    f["enum_triad"] = float(sum(1 for w in ENUM_CORE if w in body) >= 2)
    f["connective_distinct"] = float(sum(1 for w in CONNECTIVES if w in body))
    f["frame_tail"] = float(bool(ss) and any(w in ss[-1] for w in FRAME_TAIL))
    f["frame_lead"] = float(bool(ss) and any(w in ss[0][:24] for w in FRAME_LEAD))
    half = body.count("(") + body.count(")")
    full = body.count("（") + body.count("）")
    f["paren_half"] = half / float(half + full) if (half + full) > 0 else NAN
    cjk = RX_CJKRUN.findall(body)
    f["ttr_cjk"] = (len(set(cjk)) / float(len(cjk))) if len(cjk) >= 30 else NAN
    pts = collections.Counter(RX_PUNCT.findall(body))
    f["punct_entropy"] = entropy(list(pts.values())) if sum(pts.values()) >= 5 else NAN
    if len(lens) >= 3:
        mean = sum(lens) / float(len(lens))
        var = sum((x - mean) ** 2 for x in lens) / float(len(lens))
        f["sent_mean"] = mean
        f["sent_std"] = math.sqrt(var)
        f["sent_cv"] = math.sqrt(var) / mean if mean > 0 else NAN
        f["frac_short"] = sum(1 for x in lens if x <= 12) / float(len(lens))
        f["frac_long"] = sum(1 for x in lens if x >= 60) / float(len(lens))
        clauses = [len(c) for s in ss for c in RX_CLAUSE.split(s) if c]
        f["clause_mean"] = sum(clauses) / float(len(clauses)) if clauses else NAN
        heads = [s[:2] for s in ss if len(s) >= 2]
        run, best = 1, 1
        for i in range(1, len(heads)):
            run = run + 1 if heads[i] == heads[i - 1] else 1
            best = max(best, run)
        f["start_repeat_run"] = float(best)
        f["start_repeat_pairs"] = float(sum(1 for i in range(1, len(heads)) if heads[i] == heads[i - 1]))
        f["start_diverse"] = len(set(heads)) / float(len(heads)) if heads else NAN
    else:
        for k in ("sent_mean", "sent_std", "sent_cv", "frac_short", "frac_long", "clause_mean",
                  "start_repeat_run", "start_repeat_pairs", "start_diverse"):
            f[k] = NAN
    return f

# ---------------------------------------------------------------- prep：切段、配对、算特征
def len_ns(s):
    return len(RX_NS.sub("", s))


def frac_positions(cs):
    tot = float(sum(max(1, len_ns(c)) for c in cs))
    out, cum = [], 0.0
    for c in cs:
        w = max(1, len_ns(c))
        out.append((cum + w / 2.0) / tot)
        cum += w
    return out


def pair_sides(hc, mc):
    """按段在全文里的相对位置配对，并要求长度可比；一篇原文贡献多少对就多少对。"""
    hp, mp = frac_positions(hc), frac_positions(mc)
    used, out = set(), []
    for i, hf in enumerate(hp):
        best, bd = None, 1e9
        for j, mf in enumerate(mp):
            if abs(hf - mf) < bd:
                best, bd = j, abs(hf - mf)
        if best is None or bd > PAIR_DRIFT or best in used:
            continue
        r = len_ns(hc[i]) / float(max(1, len_ns(mc[best])))
        if not (LEN_RATIO[0] <= r <= LEN_RATIO[1]):
            continue
        used.add(best)
        out.append((i, best))
    return out


def make_unit(seq, pool, side, doc, model, domain, text, extra=None):
    u = dict(uid="%s-%06d" % (pool, seq), pool=pool, side=side, doc=doc, model=model,
             domain=domain, text_len=len_ns(text),
             sha=hashlib.sha1(flat(norm(text)).encode("utf-8")).hexdigest()[:16])
    u.update(feats_of(text))
    if extra:
        u.update(extra)
    return u


def stage_prep(args):
    if not os.path.exists(args.drlx):
        raise SystemExit("缺配对档原文：%s（取回见 tools/build-aigc-model.py 的 CORPORA）" % args.drlx)
    if not os.path.exists(args.holdout2):
        raise SystemExit("缺段落级真人真稿：%s（py tools/build-holdout-corpus.py build）" % args.holdout2)
    units, seq = [], collections.Counter()
    counts = collections.Counter()
    seen_docs = set()
    ood_train = collections.Counter()
    for line in io.open(args.drlx, encoding="utf-8"):
        d = json.loads(line)
        if d.get("lang") != "chinese":
            continue
        dom, split, model = d["domain"], d["split"], d["model"]
        academic = dom == "Academic"
        if split == "train":
            pool = "FIT" if academic else "OODFIT"
            if not academic:
                if ood_train[dom] >= args.ood_cap:
                    continue
                ood_train[dom] += 1
        else:
            pool = "VAL" if academic else "OODVAL"
        h, m = d["human_written_text"], d["llm_generated_text"]
        doc = doc_key(h)
        if doc in seen_docs:      # 同一篇原文出现两次（那批里有这么一对）：只留第一条，别让配对外键撞车
            counts["重复原文，跳过"] += 1
            continue
        seen_docs.add(doc)
        hc, mc = chunks(h), chunks(m)
        counts["pool=%s 原文篇" % pool] += 1
        counts["pool=%s 人段" % pool] += len(hc)
        counts["pool=%s 机段" % pool] += len(mc)
        pr = pair_sides(hc, mc) if pool in ("FIT", "VAL") else []
        keep_h = dict((i, k) for k, (i, _) in enumerate(pr))
        keep_m = dict((j, k) for k, (_, j) in enumerate(pr))
        for i, c in enumerate(hc):
            extra = dict(pair=("%s|%s|%d" % (pool, doc, keep_h[i])) if i in keep_h else "")
            units.append(make_unit(seq[pool], pool, "human", doc, "human", dom, c, extra))
            seq[pool] += 1
        for j, c in enumerate(mc):
            extra = dict(pair=("%s|%s|%d" % (pool, doc, keep_m[j])) if j in keep_m else "")
            units.append(make_unit(seq[pool], pool, "machine", doc, model, dom, c, extra))
            seq[pool] += 1
    for tag, rel, side in T3_TIERS:
        path = os.path.join(REPO, rel.replace("/", os.sep))
        if not os.path.exists(path):
            counts["缺文件 %s" % rel] += 1
            continue
        for line in io.open(path, encoding="utf-8"):
            t = norm(line)
            if not t or t.startswith("#"):
                continue
            if not (120 <= len_ns(t) <= 656):
                counts["T3 %s 不在 120~656 字" % tag] += 1
                continue
            units.append(make_unit(seq["T3"], "T3", side, tag, "human" if side == "human" else tag, tag, t))
            seq["T3"] += 1
            counts["T3 %s %s" % (side, tag)] += 1
    for line in io.open(args.holdout2, encoding="utf-8"):
        r = json.loads(line)
        if not (H2_FLOOR <= r["chars"] <= H2_CEIL):
            continue
        units.append(make_unit(seq["H2"], "H2", "human", "pmc:" + r["paper_group"], "human",
                               "PMC-OA", r["text"], dict(license=r.get("license", ""),
                                                         discipline=r.get("discipline", ""))))
        seq["H2"] += 1
        counts["pool=H2 人段"] += 1
    if not os.path.isdir(ART):
        os.makedirs(ART)
    with io.open(UNITS, "w", encoding="utf-8") as f:
        for u in units:
            f.write(json.dumps(u, ensure_ascii=False) + "\n")
    for pool in ("FIT", "VAL", "H2", "T3", "OODFIT", "OODVAL"):
        by = collections.Counter((u["side"], u["domain"]) for u in units if u["pool"] == pool)
        paired = len(set(u["pair"] for u in units if u["pool"] == pool and u.get("pair")))
        print("%-7s 段 %6d  配对段 %6d  %s" % (pool, sum(by.values()), paired,
              " ".join("%s/%s=%d" % (dd, sd, c) for (sd, dd), c in sorted(by.items()))))
    json.dump(dict(counts=dict(counts), pairs_by_pool=dict(
        (p, len(set(u["pair"] for u in units if u["pool"] == p and u.get("pair"))))
        for p in ("FIT", "VAL"))), io.open(os.path.join(ART, "prep.json"), "w", encoding="utf-8"),
        ensure_ascii=False, indent=1)
    print("写 %s（%d 段，含特征；原文不进仓库，artifacts/ 已被 .gitignore 挡着）" % (UNITS, len(units)))


def load_units(path=UNITS):
    return [json.loads(l) for l in io.open(path, encoding="utf-8")]

# ---------------------------------------------------------------- 量的口径
from scipy.stats import rankdata          # 只用它算带并列的 Mann-Whitney 秩


def values(us, name):
    return np.array([u.get(name, NAN) for u in us], dtype=float)


def auc_gt(a, b):
    """P(a > b)，并列算 0.5。NaN 两边一起剔掉（同一段在两档里都得有值才参加）。"""
    a = np.asarray(a, dtype=float)
    b = np.asarray(b, dtype=float)
    keep_a, keep_b = ~np.isnan(a), ~np.isnan(b)
    a, b = a[keep_a], b[keep_b]
    if len(a) == 0 or len(b) == 0:
        return NAN
    x = np.concatenate([a, b])
    r = rankdata(x)
    ra = r[:len(a)].sum()
    return float((ra - len(a) * (len(a) + 1) / 2.0) / (len(a) * len(b)))


def fold(raw_auc, sign):
    return raw_auc if sign >= 0 else 1.0 - raw_auc


def fold_vals(us, name, sign):
    v = values(us, name)
    return v if sign >= 0 else -v


def need_index(n, fp_per_mille=FP_GATE):
    """把门槛放在"真人分布的第 (1000 - fp) 分位"上：n 段真人里越线的段数不超过 n*fp/1000。"""
    if n <= 0:
        return 0
    return int(max(0, min(n - 1, int(math.ceil(n * (1.0 - fp_per_mille / 1000.0))) - 1)))


def threshold_of(fit_human_scores, fp_per_mille=FP_GATE):
    s = np.sort(np.asarray(fit_human_scores, dtype=float))
    return float(s[need_index(len(s), fp_per_mille)])


def fp_per_mille(pool_scores, thr):
    v = np.asarray(pool_scores, dtype=float)
    v = v[~np.isnan(v)]
    if len(v) == 0:
        return NAN, 0, 0
    hit = int(np.sum(v >= thr))
    return 1000.0 * hit / len(v), hit, len(v)


def recall(pool_scores, thr):
    v = np.asarray(pool_scores, dtype=float)
    v = v[~np.isnan(v)]
    if len(v) == 0:
        return NAN, 0, 0
    hit = int(np.sum(v >= thr))
    return 100.0 * hit / len(v), hit, len(v)


class Ref(object):
    """参考分布 = 拟合档的真人段。两步：先把每条特征对段长做一次线性回归（长段的 sent_std 天生就大，
    不归掉等于拿"这一段长"当风格），再按真人残差排分位。应用侧要落的东西：每条特征一个斜率 + 一张残差分位表。"""

    def __init__(self, fit_human):
        self.ref, self.med, self.lin = {}, {}, {}
        x = values(fit_human, "chars")
        for name in SIGN:
            y = values(fit_human, name)
            keep = (~np.isnan(x)) & (~np.isnan(y))
            xs, ys = x[keep], y[keep]
            if name == "chars" or len(xs) < 40 or float(np.var(xs)) < 1e-9:
                b, a = 0.0, (float(np.median(ys)) if len(ys) else 0.0)
            else:
                b, a = [float(t) for t in np.polyfit(xs, ys, 1)]
            self.lin[name] = (a, b)
            r = np.sort(ys - (a + b * xs)) if len(ys) else np.zeros(0)
            self.ref[name] = r
            self.med[name] = float(np.median(r)) if len(r) else 0.0

    def clean(self, name, xs):
        xs = np.asarray(xs, dtype=float)
        return np.where(np.isnan(xs), self.med[name], xs)

    def resid(self, name, us):
        y = values(us, name)
        x = values(us, "chars")
        x = np.where(np.isnan(x), 0.0, x)
        a, b = self.lin[name]
        return y - (a + b * x)

    def pctl(self, name, us):
        r = self.ref[name]
        v = self.clean(name, self.resid(name, us))
        if len(r) == 0:
            return np.zeros(len(v))
        return np.searchsorted(r, v, side="right") / float(len(r))


def rank_score(ref, us, names, signs):
    out = np.zeros(len(us))
    for name, sg in zip(names, signs):
        p = ref.pctl(name, us)
        out += p if sg > 0 else 1.0 - p
    return out / max(1, len(names))


def wilson_per_mille(k, n, z=1.96):
    """误报率的 95% 区间（Wilson）：真人侧只有几千段时，2/千段这条线本来就量不"实"，区间要跟数一起报。"""
    if n <= 0:
        return (NAN, NAN)
    p = k / float(n)
    d = 1.0 + z * z / n
    mid = p + z * z / (2.0 * n)
    half = z * math.sqrt(p * (1.0 - p) / n + z * z / (4.0 * n * n))
    return ((mid - half) / d * 1000.0, (mid + half) / d * 1000.0)


def pool(us, name, side=None):
    return [u for u in us if u["pool"] == name and (side is None or u["side"] == side)]

# ---------------------------------------------------------------- sig：逐特征
def stage_sig(args):
    us = load_units()
    fh, fm = pool(us, "FIT", "human"), pool(us, "FIT", "machine")
    vh, vm = pool(us, "VAL", "human"), pool(us, "VAL", "machine")
    h2 = pool(us, "H2", "human")
    ood = dict((d, ([u for u in pool(us, "OODFIT", "human") if u["domain"] == d],
                    [u for u in pool(us, "OODFIT", "machine") if u["domain"] == d])) for d in OOD_DOMAINS)
    print("FIT 拟合 %d 人 / %d 机   VAL 配对留出 %d 人 / %d 机   H2 独立真人 %d   OOD 拟合 %d 段" %
          (len(fh), len(fm), len(vh), len(vm), len(h2),
           sum(len(a) + len(b) for a, b in ood.values())))
    print("特征名 sign | 拟合AUC 配对留出AUC 跨来源AUC(H2真人对VAL机器) | 五域方向(News Wiki Novel SEO Webtext) "
          "| 一致性 入选 | 阈值上机器召回 拟合/配对留出 | 真人误报 配对留出/H2 (每千段)")
    rows = []
    for name, sg in SIGN.items():
        raw_fit = auc_gt(values(fm, name), values(fh, name))
        raw_val = auc_gt(values(vm, name), values(vh, name))
        raw_h2 = auc_gt(values(vm, name), values(h2, name))
        raw_ood = dict((d, auc_gt(values(mb, name), values(mh, name))) for d, (mh, mb) in ood.items())
        a_fit, a_val, a_h2 = fold(raw_fit, sg), fold(raw_val, sg), fold(raw_h2, sg)
        od = dict((d, fold(v, sg)) for d, v in raw_ood.items())
        ok_ood = [v for v in od.values() if not np.isnan(v)]
        consistent = bool(ok_ood) and all(v > 0.52 for v in ok_ood)
        eligible = bool(sg != 0) and (name not in LEAKY) and (not np.isnan(a_fit)) and a_fit >= 0.55 and consistent
        elig_in = bool(sg != 0) and (name not in LEAKY) and (not np.isnan(a_fit)) and a_fit >= 0.60
        # C 口径：符号在拟合档上定（这是普通建模选择，配对留出与段落级真人真稿全程没参与），
        # 定完还要在五个外域上都站得住——符号只在这批池子里成立的照样踢掉。
        chosen = sg if (sg != 0 and not np.isnan(a_fit) and a_fit >= 0.5) else (-sg if sg != 0 else 0)
        c_fit = fold(raw_fit, chosen)
        c_ood = dict((d, fold(v, chosen)) for d, v in raw_ood.items())
        cok = [v for v in c_ood.values() if not np.isnan(v)]
        elig_c = bool(chosen != 0) and (name not in LEAKY) and (not np.isnan(c_fit)) and c_fit >= 0.60 \
            and bool(cok) and all(v > 0.52 for v in cok)
        thr = threshold_of(fold_vals(fh, name, sg))
        r_fit = recall(fold_vals(fm, name, sg), thr)[0]
        r_val = recall(fold_vals(vm, name, sg), thr)[0]
        f_val = fp_per_mille(fold_vals(vh, name, sg), thr)[0]
        f_h2 = fp_per_mille(fold_vals(h2, name, sg), thr)[0]
        rows.append(dict(name=name, sign=sg, auc_fit=round(a_fit, 4), auc_val=round(a_val, 4),
                         auc_h2_cross=round(a_h2, 4), auc_ood=dict((d, round(v, 4)) for d, v in od.items()),
                         ood_consistent=consistent, eligible=eligible,
                         eligible_in_domain=elig_in, sign_chosen=chosen,
                         eligible_fit_sign=elig_c, auc_fit_chosen=round(c_fit, 4),
                         head_recall_fit=round(r_fit, 2), head_recall_val=round(r_val, 2),
                         fp_val=round(f_val, 2), fp_h2=round(f_h2, 2)))
    rows.sort(key=lambda r: -(r["auc_fit"] if not np.isnan(r["auc_fit"]) else -1))
    for r in rows:
        print("%-20s %+d | %.4f %.4f %.4f | %s | %s %s | %5.1f%% %5.1f%% | %6.2f %6.2f" % (
            r["name"], r["sign"], r["auc_fit"], r["auc_val"], r["auc_h2_cross"],
            " ".join(("%5.3f" % r["auc_ood"][d]) if not np.isnan(r["auc_ood"][d]) else "  nan"
                     for d in OOD_DOMAINS),
            "一致" if r["ood_consistent"] else "翻面", "入选" if r["eligible"] else "--",
            r["head_recall_fit"], r["head_recall_val"], r["fp_val"], r["fp_h2"]))
    out = dict(created=str(__import__("datetime").date.today()),
               counts=dict(fit_human=len(fh), fit_machine=len(fm), val_human=len(vh), val_machine=len(vm),
                           h2_human=len(h2), ood_fit=sum(len(a) + len(b) for a, b in ood.values())),
               gate=dict(auc=AUC_GATE, fp_per_mille=FP_GATE), rows=rows)
    json.dump(out, io.open(os.path.join(ART, "signals.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    sel = [r["name"] for r in rows if r["eligible"]]
    print("入选（方向与先验一致 + 五域不翻面 + 拟合 AUC>=0.55，且不在 LEAKY 里）：%d 条 %s" % (len(sel), sel))
    print("写 %s" % os.path.join(ART, "signals.json"))


# ---------------------------------------------------------------- fit：组合、阈值、机器召回
def design(ref, us, names):
    cols = []
    for name in names:
        r = ref.ref[name]
        sd = float(np.std(r)) if len(r) else 1.0
        cols.append((ref.clean(name, ref.resid(name, us)) - ref.med[name]) / (sd if sd > 1e-12 else 1.0))
    return np.column_stack(cols) if cols else np.zeros((len(us), 0))


def candidates(ref, fh, fm, rows):
    """两套入选口径，都只在拟合档上选，一次都不看 VAL/H2：
       A 严格：方向与先验一致 + 五个外域不翻面 + 拟合 AUC>=0.55
       B 域内：只看学术域（拟合 AUC>=0.60，不管外域）——它的符号是这批学术池给的，只配当旁证
    """
    def mk(el, tag, sign_key="sign"):
        el = sorted(el, key=lambda r: -r["auc_fit"])
        names = [r["name"] for r in el]
        if not names:
            return []
        signs = dict((r["name"], r[sign_key]) for r in el)

        def nk(k):
            ns = names[:k]
            return ns, [signs[n] for n in ns]
        o = [(tag + " 单条最高 " + names[0], "rank", *nk(1))]
        o.append((tag + " 秩平均 全部(%d)" % len(names), "rank", *nk(len(names))))
        if len(names) >= 5:   # 特征太少时投票是退化的（真人几乎全挤在 0 票），不摆出来当样子
            o.append((tag + " 并尾投票 q99(%d)" % len(names), "vote99", *nk(len(names))))
            o.append((tag + " 并尾投票 q95(%d)" % len(names), "vote95", *nk(len(names))))
        o.append((tag + " 逻辑回归 全部(%d)" % len(names), "lr", *nk(len(names))))
        return o
    out = mk([r for r in rows if r["eligible"]], "A严格")
    out += mk([r for r in rows if r.get("eligible_in_domain")], "B域内")
    out += mk([r for r in rows if r.get("eligible_fit_sign")], "C符号拟合定", sign_key="sign_chosen")
    out.append(("反证：只看段长", "raw", ["chars"], [1]))
    strict = sorted([r for r in rows if r["eligible"]], key=lambda r: -r["auc_fit"])
    return out, [r["name"] for r in strict], dict((r["name"], r["sign"]) for r in strict)


C_GRID = (0.02, 0.1, 0.5, 2.0)
_C_CACHE = {}


def doc_folds(us, k=3):
    return [int(hashlib.sha1(str(u["doc"]).encode("utf-8")).hexdigest()[:6], 16) % k for u in us]


def cv_pick_c(ref, fh, fm, names):
    """正则强度在拟合档内部按"同一篇原文"三折自己选——留出与两把真人尺子一次都不参与。"""
    from sklearn.linear_model import LogisticRegression
    key = tuple(names)
    if key in _C_CACHE:
        return _C_CACHE[key]
    x = np.vstack([design(ref, fh, names), design(ref, fm, names)])
    y = np.concatenate([np.zeros(len(fh)), np.ones(len(fm))])
    folds = doc_folds(fh + fm)
    best, best_auc = C_GRID[0], -1.0
    for cval in C_GRID:
        aucs = []
        for f in range(3):
            tr = np.array([d != f for d in folds])
            te = ~tr
            if len(set(y[te])) < 2:
                continue
            clf = LogisticRegression(C=cval, max_iter=4000).fit(x[tr], y[tr])
            aucs.append(auc_gt(clf.decision_function(x[te])[y[te] == 1],
                               clf.decision_function(x[te])[y[te] == 0]))
        m = float(np.mean(aucs)) if aucs else -1.0
        if m > best_auc:
            best, best_auc = cval, m
    _C_CACHE[key] = (best, round(best_auc, 4))
    return _C_CACHE[key]


def spec_of(kind, names, signs, ref, fh, fm):
    """把一条候选的构成写成能重算的规格：check 档靠它在不带 signals.json 的情况下原样重建打分。"""
    sp = dict(kind=kind, feats=list(names), signs=[int(s) for s in signs])
    if kind == "lr":
        sp["c"] = cv_pick_c(ref, fh, fm, names)[0]
    if kind.startswith("vote"):
        sp["level"] = float(kind[4:])
    return sp


def scorer_from_spec(ref, fh, fm, sp):
    return make_scorer(sp["kind"], ref, fh, fm, sp["feats"], sp["signs"])


def make_scorer(kind, ref, fh, fm, names, signs, log=None):
    if kind == "raw":
        return lambda us: values(us, names[0])
    if kind == "rank":
        return lambda us: rank_score(ref, us, names, signs)
    if kind.startswith("vote"):
        # 并尾投票：数"有几条特征同时越过了拟合档真人的 q 分位"。单条特征的机器尾巴压不过真人尾分位，
        # 但一个段落如果在好几条上都同时是真人千分之一的样子，那就不一样了——要的就是这个联合尾部。
        level = float(kind[4:]) / 100.0
        cuts = [float(np.quantile(ref.ref[n], level)) if len(ref.ref[n]) else 0.0 for n in names]

        def vote(us):
            tot = np.zeros(len(us))
            for name, sg, cut in zip(names, signs, cuts):
                p = ref.pctl(name, us)
                tot += (p if sg > 0 else 1.0 - p) >= cut
            return tot
        return vote
    from sklearn.linear_model import LogisticRegression
    cval, cv_auc = cv_pick_c(ref, fh, fm, names)
    if log is not None:
        log.append("逻辑回归按篇三折自己选到 C=%s（折内 AUC %.4f）" % (cval, cv_auc))
    xh, xm = design(ref, fh, names), design(ref, fm, names)
    x = np.vstack([xh, xm])
    y = np.concatenate([np.zeros(len(xh)), np.ones(len(xm))])
    clf = LogisticRegression(C=cval, max_iter=4000).fit(x, y)
    return lambda us: clf.decision_function(design(ref, us, names))


def paired_subset(us, sfun, pool_name, cond):
    """只在满足条件的那批配对段上重算 AUC（用来把"PDF 断字痕迹"这类来源痕迹的影响单独拎出来）。"""
    keep = [u for u in pool(us, pool_name) if u.get("pair") and cond(u)]
    pairs = collections.defaultdict(dict)
    for u in keep:
        pairs[u["pair"]][u["side"]] = u
    both = [v for v in pairs.values() if "human" in v and "machine" in v]
    if len(both) < 20:
        return NAN, len(both)
    sub = [u for v in both for u in (v["human"], v["machine"])]
    s = sfun(sub)
    h = np.array([s[i] for i, u in enumerate(sub) if u["side"] == "human"])
    m = np.array([s[i] for i, u in enumerate(sub) if u["side"] == "machine"])
    return auc_gt(m, h), len(both)


def hash_split(us, frac=0.5, key="doc"):
    """按"同一篇原文/同一篇文章"切两半：同一篇的段不许一半定阈值一半量误报。"""
    cut = int(frac * 1000)
    keep = [u for u in us
            if (int(hashlib.sha1(str(u.get(key, "")).encode("utf-8")).hexdigest()[:8], 16) % 1000) < cut]
    ids = set(id(u) for u in keep)
    return keep, [u for u in us if id(u) not in ids]


def stage_fit(args):
    us = load_units()
    sjson = os.path.join(ART, "signals.json")
    if not os.path.exists(sjson):
        stage_sig(args)
    rows = json.load(io.open(sjson, encoding="utf-8"))["rows"]
    fh, fm = pool(us, "FIT", "human"), pool(us, "FIT", "machine")
    vh, vm = pool(us, "VAL", "human"), pool(us, "VAL", "machine")
    h2 = pool(us, "H2", "human")
    oodm = pool(us, "OODVAL", "machine")
    t3h, t3m = pool(us, "T3", "human"), pool(us, "T3", "machine")
    h2cal, h2test = hash_split(h2, 0.5)
    ref = Ref(fh)
    cands, names, signs = candidates(ref, fh, fm, rows)
    print("拟合档 %d 人 / %d 机   配对留出 %d 人 / %d 机   段落级真人真稿 %d 段（按篇分两半：%d 定阈值 / %d 只量误报）"
          % (len(fh), len(fm), len(vh), len(vm), len(h2), len(h2cal), len(h2test)))
    print("旁证档 T3（出厂审计那批标注稿，机器侧生成前读过判据）：%d 真人段 / %d 机器段" % (len(t3h), len(t3m)))
    print("阈值两种定法：fit=只看拟合档真人的千分之二分位；cal=拟合档真人 + 那半批段落级真人真稿一起定同一分位")
    print("误报只在没参与定阈值的人写段上量（配对留出 + 段落级真稿另一半）；机器召回用同一个阈值")
    print("候选 | 阈值 | 条数 真人/机器 | AUC(配对留出) | AUC(跨来源) | 真人误报/千段 配对留出/真稿另半/合并"
          "[合并 95%% 区间] | 机器召回 配对留出/换域 | 旁证 T3：AUC 误报 召回 | 判")
    out = []
    for label, kind, cnames, csigns in cands:
        log = []
        sfun = make_scorer(kind, ref, fh, fm, cnames, csigns, log)
        s_vh, s_vm = sfun(vh), sfun(vm)
        s_fh, s_fm, s_oodm = sfun(fh), sfun(fm), sfun(oodm)
        s_h2cal, s_h2test = sfun(h2cal), sfun(h2test)
        s_t3h, s_t3m = sfun(t3h), sfun(t3m)
        clean_auc, clean_n = paired_subset(us, sfun, "VAL", lambda u: u.get("space_artifact_p100", 0) == 0)
        lb = [u for u in vh + vm if 280 <= u["text_len"] <= 420]
        lb_h = [u for u in lb if u["side"] == "human"]
        lb_m = [u for u in lb if u["side"] == "machine"]
        auc_lenb = auc_gt(sfun(lb_m), sfun(lb_h)) if lb_h else NAN
        for tag, thr in (("fit", threshold_of(s_fh)), ("cal", threshold_of(np.concatenate([s_fh, s_h2cal])))):
            f_val, hv, nv = fp_per_mille(s_vh, thr)
            f_ht, ht, nt = fp_per_mille(s_h2test, thr)
            f_comb = 1000.0 * (hv + ht) / max(1, nv + nt)
            r_val, _, nm = recall(s_vm, thr)
            r_ood = recall(s_oodm, thr)[0]
            auc_val, auc_cross = auc_gt(s_vm, s_vh), auc_gt(s_vm, s_h2test)
            by_band = {}
            for lo, hi in BANDS:
                hh = [x for x, u in zip(np.concatenate([s_vh, s_h2test]), vh + h2test) if lo <= u["text_len"] <= hi]
                mm = [x for x, u in zip(s_vm, vm) if lo <= u["text_len"] <= hi]
                by_band["%d-%d" % (lo, hi)] = dict(
                    n_human=len(hh),
                    fp_per_mille=round(1000.0 * sum(1 for x in hh if x >= thr) / max(1, len(hh)), 2),
                    n_machine=len(mm),
                    recall=round(100.0 * sum(1 for x in mm if x >= thr) / max(1, len(mm)), 1))
            per_model = {}
            for mdl in sorted(set(u["model"] for u in vm)):
                sel = [s_vm[i] for i, u in enumerate(vm) if u["model"] == mdl]
                per_model[mdl] = dict(recall=round(recall(sel, thr)[0], 2), n=len(sel))
            t3_auc, t3_f, t3_r = auc_gt(s_t3m, s_t3h), fp_per_mille(s_t3h, thr)[0], recall(s_t3m, thr)[0]
            lo_ci, hi_ci = wilson_per_mille(hv + ht, nv + nt)
            pass_line = bool(auc_val >= AUC_GATE and f_comb <= FP_GATE and r_val > 0.0)
            print("%-31s %s | %5d/%5d | %.4f | %.4f | %6.2f %6.2f %6.2f[%.2f-%.2f] | %5.1f%% %5.1f%% | "
                  "%.4f %6.2f %5.1f%% | %s%s" % (
                      label[:31], tag, nv + nt, nm, auc_val, auc_cross, f_val, f_ht, f_comb, lo_ci, hi_ci,
                      r_val, r_ood, t3_auc, t3_f, t3_r, "过线" if pass_line else "不过线",
                      ("  [" + "; ".join(log) + "]") if (log and tag == "fit") else ""))
            out.append(dict(label=label, thr_from=tag, kind=kind, feats=cnames, threshold=round(thr, 5),
                            spec=spec_of(kind, cnames, csigns, ref, fh, fm),
                            n_human=nv + nt, n_machine=nm, auc_val_pair=round(auc_val, 4),
                            auc_cross_true_half=round(auc_cross, 4),
                            auc_length_band_280_420=(round(auc_lenb, 4) if not np.isnan(auc_lenb) else None),
                            auc_no_spacing_artifact=(round(clean_auc, 4) if not np.isnan(clean_auc) else None),
                            n_pairs_no_spacing_artifact=clean_n,
                            fp_val_per_mille=round(f_val, 3), fp_true_half_per_mille=round(f_ht, 3),
                            fp_combined_per_mille=round(f_comb, 3), human_flags=[hv + ht, nv + nt],
                            fp_combined_ci95=[round(lo_ci, 3), round(hi_ci, 3)],
                            t3_auc=round(t3_auc, 4), t3_fp_per_mille=round(t3_f, 3),
                            t3_recall=round(t3_r, 2),
                            recall_val=round(r_val, 2), recall_ood_machine=round(r_ood, 2),
                            by_band=by_band, per_model=per_model,
                            auc_fit_insample=round(auc_gt(s_fm, s_fh), 4), pass_line=pass_line))
    passing = [r for r in out if r["pass_line"]]
    within = [r for r in out if r["fp_combined_per_mille"] <= FP_GATE]
    if passing:
        best = max(passing, key=lambda r: r["recall_val"])
    else:
        # 没过线时，"最接近"按 AUC 达标 + 召回不为 0 + 误报离 2/千段最近 来排；
        # 只看误报最小会挑出一条召回 0.2% 的，那不是"最接近能上线"。
        pool_ = [r for r in out if r["auc_val_pair"] >= AUC_GATE and r["recall_val"] > 0.0] or \
                [r for r in out if r["recall_val"] > 0.0] or out
        best = min(pool_, key=lambda r: (max(0.0, r["fp_combined_per_mille"] - FP_GATE) / FP_GATE
                                         + max(0.0, AUC_GATE - r["auc_val_pair"]) / AUC_GATE))
    json.dump(dict(created=str(__import__("datetime").date.today()),
                   gate=dict(auc=AUC_GATE, fp_per_mille=FP_GATE, recall_must_be_positive=True),
                   pools=dict(fit_human=len(fh), fit_machine=len(fm), val_human=len(vh), val_machine=len(vm),
                              h2=len(h2), h2_for_threshold=len(h2cal), h2_for_fp_only=len(h2test)),
                   eligible_features=names, candidates=out, best=best["label"], any_pass=bool(passing)),
              io.open(os.path.join(ART, "fit.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("最接近验收线的那条：%s（阈值定法 %s，配对留出 AUC %.4f，合并真人误报 %.2f/千段，同一阈值机器召回 %.1f%%）"
          % (best["label"], best["thr_from"], best["auc_val_pair"], best["fp_combined_per_mille"], best["recall_val"]))
    print("   入选特征 %s" % " ".join(best["feats"]))
    print("   逐台机器召回：" + "  ".join("%s=%.1f%%(%d)" % (k, v["recall"], v["n"])
                                        for k, v in sorted(best["per_model"].items())))
    print("   按长度带（真人=配对留出+真稿另一半）：带 真人段数 误报/千段 机器段数 召回")
    for k, v in best["by_band"].items():
        print("     %-8s %6d %8.2f %6d %6.1f%%" % (k, v["n_human"], v["fp_per_mille"], v["n_machine"], v["recall"]))
    hit = [c for c in cands if c[0] == best["label"]]
    if hit:
        _, kind, cnames, csigns = hit[0]
        sfun = make_scorer(kind, ref, fh, fm, cnames, csigns)
        thr = best["threshold"]

        def guarded(us, s):
            return np.array([s[i] if (us[i].get("n_sent") or 0) >= 4 else -1e9 for i in range(len(us))])
        g_vh, g_vm = guarded(vh, sfun(vh)), guarded(vm, sfun(vm))
        g_h2 = guarded(h2test, sfun(h2test))
        gf1, h1_, n1_ = fp_per_mille(g_vh, thr)
        gf2, h2_, n2_ = fp_per_mille(g_h2, thr)
        print("   护栏（不足 4 句的段落一律不判）：真人误报 合并 %.2f/千段（%d/%d），机器召回 %.1f%%（护栏前 %.1f%%）"
              % (1000.0 * (h1_ + h2_) / max(1, n1_ + n2_), h1_ + h2_, n1_ + n2_,
                 recall(g_vm, thr)[0], best["recall_val"]))
        best["guard_n_sent_4"] = dict(fp_combined_per_mille=round(1000.0 * (h1_ + h2_) / max(1, n1_ + n2_), 3),
                                      recall_val=round(recall(g_vm, thr)[0], 2))
        json.dump(best, io.open(os.path.join(ART, "best.json"), "w", encoding="utf-8"),
                  ensure_ascii=False, indent=1)
    print("写 %s" % os.path.join(ART, "fit.json"))
    write_summary(us, out, best, args.summary)


def sha1_file(rel):
    with open(os.path.join(REPO, rel.replace("/", os.sep)), "rb") as f:
        return hashlib.sha1(f.read()).hexdigest()


def pool_counts(us):
    return dict((p, sum(1 for u in us if u["pool"] == p))
                for p in ("FIT", "VAL", "H2", "T3", "OODFIT", "OODVAL"))


def write_summary(us, out, best, rel):
    doc = dict(
        what="段落级风格统计实测的读数清单（tools/aigc-para-probe.py 写；只记计数与读数，原文一段不进仓库）",
        how="py tools/aigc-para-probe.py prep && ... sig && ... fit，重算比对：py tools/aigc-para-probe.py check",
        gate=dict(auc=AUC_GATE, fp_per_mille=FP_GATE, recall_must_be_positive=True),
        lengths=dict(chunk_floor=FLOOR, chunk_target=TARGET, chunk_ceil=CEIL, h2=[H2_FLOOR, H2_CEIL]),
        counts=pool_counts(us),
        pairs=dict((p, len(set(u["pair"] for u in us if u["pool"] == p and u.get("pair"))))
                   for p in ("FIT", "VAL")),
        verdict=dict(any_pass=any(bool(r["pass_line"]) for r in out),
                     closest=best["label"], thr_from=best["thr_from"],
                     auc_val_pair=best["auc_val_pair"],
                     fp_combined_per_mille=best["fp_combined_per_mille"],
                     fp_combined_ci95=best["fp_combined_ci95"], recall_val=best["recall_val"],
                     t3_auc=best["t3_auc"], t3_recall=best["t3_recall"]),
        strongest=(max([r for r in out if r["auc_val_pair"] >= AUC_GATE and r["recall_val"] > 0.0],
                       key=lambda r: r["recall_val"], default=None)),
        candidates=[dict((k, r[k]) for k in (
            "label", "thr_from", "kind", "threshold", "n_human", "n_machine", "auc_val_pair",
            "auc_cross_true_half", "fp_val_per_mille", "fp_true_half_per_mille", "fp_combined_per_mille",
            "fp_combined_ci95", "recall_val", "recall_ood_machine", "t3_auc", "t3_fp_per_mille", "t3_recall",
            "auc_fit_insample", "pass_line")) for r in out],
        best=best, shipped_guard=dict((rel2, sha1_file(rel2)) for rel2 in SHIPPED_GUARD))
    path = os.path.join(REPO, rel.replace("/", os.sep))
    io.open(path, "w", encoding="utf-8", newline="\n").write(
        json.dumps(doc, ensure_ascii=False, indent=1) + "\n")
    print("写 %s（计数与读数清单，进仓库）" % rel)


def stage_check(args):
    rel = args.summary
    path = os.path.join(REPO, rel.replace("/", os.sep))
    s = json.load(io.open(path, encoding="utf-8")) if os.path.exists(path) else {}
    us = load_units() if os.path.exists(UNITS) else []
    n = [0]
    res = []

    def ok(cond, msg):
        n[0] += 1
        res.append((bool(cond), "%2d %s" % (n[0], msg)))
    ok(bool(us), "切段产物在：%s（%d 段）" % (UNITS, len(us)))
    ok(bool(s), "读数清单在：%s" % rel)
    ok(pool_counts(us) == s.get("counts"), "六档条数与清单一致：%s" % pool_counts(us))
    ok(all(H2_FLOOR <= u["text_len"] <= H2_CEIL for u in us), "每段长度都在 %d~%d 字之间" % (H2_FLOOR, H2_CEIL))
    ok(all(u["side"] in ("human", "machine") for u in us), "两侧标签只有 human/machine")
    ok(all(all(k in u for k in SIGN) for u in us), "每段都带齐 %d 个特征键" % len(SIGN))
    fd = set(u["doc"] for u in pool(us, "FIT"))
    vd = set(u["doc"] for u in pool(us, "VAL"))
    ok(not (fd & vd), "拟合与配对留出不共用同一篇原文（交集 %d 篇）" % len(fd & vd))
    inter = 0
    names4 = ["FIT", "VAL", "H2", "T3"]
    shas = dict((p, set(u["sha"] for u in pool(us, p))) for p in names4)
    for i in range(len(names4)):
        for j in range(i + 1, len(names4)):
            inter += len(shas[names4[i]] & shas[names4[j]])
    ok(inter == 0, "四档之间逐字相同的段 %d 条（必须 0：同一段不许一边算人一边算机）" % inter)
    pr = collections.defaultdict(list)
    for u in us:
        if u.get("pair"):
            pr[u["pair"]].append(u["side"])
    ok(all(sorted(v) == ["human", "machine"] for v in pr.values()),
       "每个配对段恰好一真人一机器（%d 对）" % len(pr))
    man = json.load(io.open(os.path.join(REPO, "tests", "corpus", "aigc-holdout2-manifest.json"), encoding="utf-8"))
    acc = set(man["license_policy"]["accepted"])
    ok(all(u.get("license") in acc for u in pool(us, "H2")), "段落级真人真稿每段许可都在清单认可列里")
    ok(len(set(u["doc"] for u in pool(us, "H2"))) >= 1000,
       "段落级真人真稿来自 %d 篇文章（真人侧不是几位作者凑的）" % len(set(u["doc"] for u in pool(us, "H2"))))
    cal, tst = hash_split(pool(us, "H2"), 0.5)
    ok(not (set(u["doc"] for u in cal) & set(u["doc"] for u in tst)),
       "定阈值那半批与只量误报那半批不共用一篇文章")
    b = s.get("best") or {}
    sp = b.get("spec") or {}
    fh, fm = pool(us, "FIT", "human"), pool(us, "FIT", "machine")
    vh, vm = pool(us, "VAL", "human"), pool(us, "VAL", "machine")
    h2 = pool(us, "H2", "human")
    h2cal, h2test = hash_split(h2, 0.5)
    same = True
    if sp.get("feats"):
        ref = Ref(fh)
        sfun = scorer_from_spec(ref, fh, fm, sp)
        thr = b["threshold"]
        a = auc_gt(sfun(vm), sfun(vh))
        f1, k1, m1 = fp_per_mille(sfun(vh), thr)
        f2, k2, m2 = fp_per_mille(sfun(h2test), thr)
        r = recall(sfun(vm), thr)[0]
        same = (abs(a - b["auc_val_pair"]) < 2e-4 and abs(r - b["recall_val"]) < 0.06
                and abs(1000.0 * (k1 + k2) / (m1 + m2) - b["fp_combined_per_mille"]) < 0.06)
        ok(same, "最佳候选重算一致：AUC %.4f/%.4f 误报 %.2f/%.2f 召回 %.2f/%.2f" %
           (a, b["auc_val_pair"], 1000.0 * (k1 + k2) / (m1 + m2), b["fp_combined_per_mille"], r, b["recall_val"]))
    else:
        ok(False, "清单里没有最佳候选的规格，无法重算")
    v = s.get("verdict") or {}
    claimed = (v.get("auc_val_pair", 0.0) >= 0.85 and v.get("fp_combined_per_mille", 9e9) <= 2.0
               and v.get("recall_val", 0.0) > 0.0)
    ok(isinstance(v.get("any_pass"), bool) and v["any_pass"] == claimed,
       "清单里记的过线结论与它自己记的那三个数对得上（any_pass=%s）" % v.get("any_pass"))
    guard = s.get("shipped_guard") or {}
    bad = [rel2 for rel2, dig in guard.items() if sha1_file(rel2) != dig]
    ok(bool(guard) and not bad, "随包判据那 %d 个文件的 sha1 与清单一致（动了就红）%s" % (len(guard), bad))
    g = s.get("gate") or {}
    ok(abs(g.get("auc", 0) - 0.85) < 1e-9 and abs(g.get("fp_per_mille", 0) - 2.0) < 1e-9
       and g.get("recall_must_be_positive") is True, "验收线口径没被改：AUC 0.85 / 真人误报 2 每千段 / 召回不许为 0")
    bad_n = sum(1 for c, _ in res if not c)
    for c, msg in res:
        print("%s %s" % ("通过" if c else "红了", msg))
    print("%d 条断言：%d 条通过，%d 条红" % (len(res), len(res) - bad_n, bad_n))
    if bad_n:
        raise SystemExit(1)


def main():
    ap = argparse.ArgumentParser(description="段落级风格统计实测台")
    ap.add_argument("stage", choices=["prep", "sig", "fit", "check"])
    ap.add_argument("--summary", default=SUMMARY_DEFAULT, help="读数清单（进仓库那份）")
    ap.add_argument("--drlx", default=DRLX_DEFAULT)
    ap.add_argument("--holdout2", default=HOLDOUT2_DEFAULT)
    ap.add_argument("--ood-cap", type=int, default=300, help="非学术域在拟合侧每域封顶多少篇（0=全都要）")
    a = ap.parse_args()
    {"prep": stage_prep, "sig": stage_sig, "fit": stage_fit, "check": stage_check}[a.stage](a)


if __name__ == "__main__":
    main()
