# -*- coding: utf-8 -*-
"""
Wordlite 离线 AIGC 模型训练台（CPU，不微调语言模型）。

    py tools/build-aigc-model.py fetch     # 拉公开带标注语料（默认走系统代理，--proxy 可覆盖）
    py tools/build-aigc-model.py train     # 字符 n-gram + 词表 + 逻辑回归，公共留出量 AUC
    py tools/build-aigc-model.py gate      # 真稿独立留出 + 滑窗困惑度落点，两条出厂门槛一起判
    py tools/build-aigc-model.py export    # 导出随包权重（tsv）与清单（json）

纪律（改这个脚本前先读）：
* 原文一律不进仓库。仓库里只进派生统计量（权重表 + 字频表）与清单，许可见 CORPORA。
* 建模决定只看公共侧：配置选择用"公共留出里最差那一档的 AUC"（且该配置必须覆盖 prhpp 学术档），
  仓库那批人工标注真稿只在 gate 这一步读，不参与任何拟合或选配置。
* 两条出厂门槛写死在这里，量不过就在清单里 calibrated=false，Java 侧就不许印百分比。
"""
import argparse, ast, hashlib, io, json, math, os, re, sys, urllib.request
from collections import Counter

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
DEFAULT_DATA = os.path.abspath(os.path.join(REPO, "..", "aigc-corpus"))
ASSETS = os.path.join(REPO, "app", "src", "main", "assets", "aigc")
ART = os.path.join(REPO, "artifacts", "agent-aigc-offline")
TEXTCORPUS = os.path.join(REPO, "app", "src", "main", "java", "com", "rikkahub", "wordlite", "TextCorpus.java")

MIN_SENT = 8            # AigcDetector.MIN_SENTENCE_CHARS
MAX_SENT = 300          # 训练侧句长上限（应用侧没有上限，报告里说明这一条差异）
KEEP_FEATURES = 20000   # 导出文件的特征条数
GATE_AUC = 0.85         # 真稿独立留出：AUC(机器>真人) 下限
GATE_FP_PER_MILLE = 2.0 # 真稿真人侧在 0.45 门槛上的误报上限（句/千句）
GATE_TEMPLATE_HIT = 0.90  # 模板段滑窗落点下限
GATE_HUMAN_WINDOW = 0     # 真人段不许有窗口进阈值
FLAG_GATE = 0.45        # AigcDetector.SEGMENT_FLAG_GATE
WINDOW_CHARS, WINDOW_STRIDE, WINDOW_MIN = 24, 8, 12
MODEL_VERSION = "ngram-lr-2026-10-09"

# ---------------------------------------------------------------- 语料清单（含许可与可分发性）

CORPORA = [
    dict(code="hc3zh", kind="zh", license="CC-BY-SA-4.0", ship_raw=False, anon=True,
         url="https://huggingface.co/datasets/Hello-SimpleAI/HC3-Chinese/resolve/main/all.jsonl",
         file="hc3_zh_all.jsonl",
         note="HC3 中文：真人答案 22,231 条 / ChatGPT 答案 17,383 条，百科·开放问答·法律·医学·金融·心理·NLPCC"),
    dict(code="prhpp", kind="zh", license="Apache-2.0", ship_raw=False, anon=True,
         url="https://huggingface.co/datasets/FreedomIntelligence/ChatGPT-Detection-PR-HPPT/resolve/main/Chinese_data/polished_abstract/train.json",
         file="prhpp_pol_train.json",
         note="PR-HPPT 中文学术摘要：label 0 作者原文 739 篇，label 1 同一篇的 ChatGPT 润色 739 篇（配对）"),
    dict(code="prhpp_te", kind="zh", license="Apache-2.0", ship_raw=False, anon=True,
         url="https://huggingface.co/datasets/FreedomIntelligence/ChatGPT-Detection-PR-HPPT/resolve/main/Chinese_data/polished_abstract/test.json",
         file="prhpp_pol_test.json", note="同一批的官方 test 划分（仍按标题分组切分）"),
    dict(code="prhppgen", kind="zh", license="Apache-2.0", ship_raw=False, anon=True,
         url="https://huggingface.co/datasets/FreedomIntelligence/ChatGPT-Detection-PR-HPPT/resolve/main/Chinese_data/generated_abstract/generated_abstract.jsonl",
         file="prhpp_gen.jsonl", note="ChatGPT 整篇生成的中文摘要 100 篇（只有机器侧）"),
    dict(code="pangda", kind="zh", license="MIT", ship_raw=False, anon=True,
         url="https://huggingface.co/datasets/pangda/chatgpt-paraphrases-zh/resolve/main/chatgpt_paraphrases_zh.csv",
         file="pangda_zh_head.csv", note="中文检索短句 + ChatGPT 逐条改写（短问句域，只取前 30,000 行、每行 2 条改写）"),
    dict(code="ateeqq", kind="en", license="MIT", ship_raw=False, anon=True,
         url="https://huggingface.co/datasets/Ateeqq/AI-and-Human-Generated-Text/resolve/main/train.csv",
         file="ateeqq_train.csv", note="英文论文摘要人写/AI 写。中文模型不用，留着给拉丁族"),
]
NOT_USED = [
    ("QiYuan-tech/LLM-Detector（中文，7 个国产模型 + GPT-4 与真人答案配对）", "要登录接受条款，匿名取回 401"),
    ("liud169/ChatGPT-detector（Reddit 英文）", "同上，匿名 401"),
    ("krisfu/Chinese-Corpus-DetectGPT", "同上，匿名 401"),
    ("HaochenWang/TreeBench", "名字叫 TreeBench，内容实为 ScienceQA 图文题（TSV 里是 base64 图片），不是人写/机写语料"),
    ("haoxianc/shamimhasan8_ai-vs-human-text-dataset", "正文是 'AI-generated content sample 1: ...' 这类占位模板，会把模板当特征学，弃用"),
    ("dmitva/human_ai_generated_text（CC-BY-4.0，3.8 GB 英文）", "匿名可下但体积过大，本轮中文模型不需要"),
]
PANGDA_ROWS = 30000
HOLDOUT_TIERS = [
    ("H1", "tests/corpus/real-prose.txt", 0, "学位论文正文（真人，单作者）"),
    ("H2", "tests/corpus/aigc-label-human-verbatim.txt", 0, "已发表论文摘要逐字摘录（真人，20 组作者）"),
    ("H3", "tests/corpus/cnki-cross.txt", 0, "知网检索页摘要片段（真人）"),
    ("X1", "tests/corpus/aigc-label-human-polish-agent.txt", 0, "真人原文 + 代理轻度润色（算真人侧）"),
    ("HH", "tests/corpus/aigc-hard-human.txt", 0, "真人最高分钉子户句"),
    ("M-RAW", "tests/corpus/aigc-label-machine-raw.txt", 1, "代理生成未编辑"),
    ("M-EVADE", "tests/corpus/aigc-label-machine-evasive.txt", 1, "代理生成且要求规避风格"),
    ("M-DOMAIN", "tests/corpus/aigc-label-machine-domain.txt", 1, "与 H1 同领域同主题逐段配对的机器稿"),
]
TEMPLATE_TIERS = [("TPL-CARTOON", "tests/corpus/aigc-cartoon.txt"), ("TPL-FRAMES", "tests/corpus/aigc-frames.txt")]
# ---------------------------------------------------------------- 归一化与切句（口径抄 TextCorpus）

BLANKS = {0x20, 0x09, 0x0A, 0x0D, 0x0C, 0x0B, 0x85, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000}
PUNCT = {}
for src, dst in [("\u3001", ","), ("\u3002", "."), ("\u30fb", "."), ("\u00b7", "."), ("\u2027", "."),
                 ("\u3008", "<"), ("\u300a", "<"), ("\u3009", ">"), ("\u300b", ">"),
                 ("\u300c", "["), ("\u300e", "["), ("\u3010", "["), ("\u3014", "["),
                 ("\u300d", "]"), ("\u300f", "]"), ("\u3011", "]"), ("\u3015", "]"),
                 ("\u201c", '"'), ("\u201d", '"'), ("\u201e", '"'), ("\u201f", '"'),
                 ("\u301d", '"'), ("\u301e", '"'), ("\u2018", "'"), ("\u2019", "'"),
                 ("\u201b", "'"), ("\u2032", "'"), ("\u2014", "-"), ("\u2013", "-"),
                 ("\u2015", "-"), ("\u2012", "-"), ("\u2010", "-"), ("\u2011", "-"),
                 ("\u2500", "-"), ("\u2022", "-"), ("\u2026", ".")]:
    PUNCT[ord(src)] = dst
for code, dst in [(0xFE10, ","), (0xFE11, ","), (0xFE12, "."), (0xFE19, "."),
                  (0xFE13, ":"), (0xFE14, ";"), (0xFE15, "!"), (0xFE16, "?")]:
    PUNCT[code] = dst


def load_trad_table(java_path=TEXTCORPUS):
    """从 TextCorpus.java 解析繁简表：训练侧与 Java 侧必须共用同一张表。"""
    text = io.open(java_path, encoding="utf-8").read()

    def grab(name):
        m = re.search(r"String\s+" + name + r"\s*=\s*(.*?);", text, re.S)
        if not m:
            raise RuntimeError("TextCorpus 里找不到 " + name)
        return "".join(re.findall(r'"([^"]*)"', m.group(1)))
    trad, simp = grab("TRADITIONAL"), grab("SIMPLIFIED")
    if len(trad) != len(simp):
        raise RuntimeError("繁简表长度不一致：%d vs %d" % (len(trad), len(simp)))
    return {ord(t): s for t, s in zip(trad, simp) if t != s}


class Normalizer(object):
    """TextCorpus.normalize 的 Python 镜像 + 丢掉空白与不可见字符（等价于 validCount 数出来的那串字）。"""

    def __init__(self, trad):
        self.trad = trad

    def fold(self, ch):
        o = ord(ch)
        if 65 <= o <= 90:
            return chr(o + 32)
        if (97 <= o <= 122) or (48 <= o <= 57):
            return ch
        if o in BLANKS or (0x2000 <= o <= 0x200A):
            return " "
        if 0xFF01 <= o <= 0xFF5E:
            half = o - 0xFEE0
            return chr(half + 32) if 65 <= half <= 90 else chr(half)
        if o in PUNCT:
            return PUNCT[o]
        if o in self.trad:
            return self.trad[o]
        return ch.lower()

    def compact(self, s):
        out = []
        for ch in s:
            o = ord(ch)
            if (0x200B <= o <= 0x200F) or o in (0x2060, 0xFEFF, 0xAD, 0x34F, 0x180E):
                continue
            if o <= 0x08 or (0x0E <= o <= 0x1F) or (0x7F <= o <= 0x9F) or (0xE000 <= o <= 0xF8FF):
                continue
            f = self.fold(ch)
            if f != " ":
                out.append(f)
        return "".join(out)


CLOSERS = set("\u3002\uff01\uff1f\uff1b\u2026!?;.'\u201d\u2019\u300f\u300d)\uff09]\uff3d\u3011*>")
TERM = set("\u3002\uff01\uff1f\uff1b!?;\u2026")


def is_terminator(s, i):
    c = s[i]
    if c in TERM:
        return True
    if c not in (".", "\uff0e"):
        return False
    prev = s[i - 1] if i > 0 else ""
    nxt = s[i + 1] if i + 1 < len(s) else ""
    if nxt.isdigit() or prev == "." or prev == "\u2026" or nxt == "." or nxt == "\u2026":
        return False
    if nxt in ("", " ", "\n", "\r", "\t", "\x0b") or nxt in CLOSERS or nxt.isupper():
        return True
    o = ord(nxt)
    return (0x3000 <= o <= 0x303F) or (0x4E00 <= o <= 0x9FFF) or (0xFF00 <= o <= 0xFF65)


def split_sentences(text):
    out, n, start, i = [], len(text), 0, 0

    def emit(frm, to):
        seg = text[frm:to].strip()
        if seg:
            out.append(seg)
    while i < n:
        if text[i] in "\n\r\x0b\x0c\u2028\u2029":
            emit(start, i); start = i + 1; i += 1; continue
        if is_terminator(text, i):
            end = i + 1
            while end < n and text[end] in CLOSERS:
                end += 1
            emit(start, end); start = end; i = end; continue
        i += 1
    emit(start, n)
    return out


def scoreable(compact_text):
    return MIN_SENT <= len(compact_text) <= MAX_SENT

# ---------------------------------------------------------------- 取数与读语料

def stage_fetch(args):
    os.makedirs(args.data, exist_ok=True)
    proxy = args.proxy if args.proxy is not None else "http://127.0.0.1:7897"
    handlers = [urllib.request.ProxyHandler({"http": proxy, "https": proxy})] if proxy else []
    opener = urllib.request.build_opener(*handlers)
    print("代理：%s（清空 --proxy 即直连；本机直连不通，DNS 把 huggingface.co 污染到了别的地址）" % (proxy or "直连"))
    for spec in CORPORA:
        dst = os.path.join(args.data, spec["file"])
        if os.path.exists(dst) and os.path.getsize(dst) > 1024 and not args.redo:
            print("已有 %-24s %8.1f MB" % (spec["file"], os.path.getsize(dst) / 1e6)); continue
        req = urllib.request.Request(spec["url"], headers={"User-Agent": "wordlite-aigc/1.0"})
        cap = 90_000_000 if spec["code"] == "pangda" else None
        got = 0
        try:
            with opener.open(req, timeout=240) as r, open(dst, "wb") as f:
                while True:
                    chunk = r.read(1 << 20)
                    if not chunk:
                        break
                    f.write(chunk); got += len(chunk)
                    if cap and got >= cap:
                        break
            print("取回 %-24s %8.1f MB  匿名=是  许可=%s" % (spec["file"], got / 1e6, spec["license"]))
        except Exception as e:
            print("失败 %-24s %s" % (spec["file"], str(e)[:70]))
    print("\n试过但没用的：")
    for what, why in NOT_USED:
        print("   - %s：%s" % (what, why))


def read_json(path):
    txt = io.open(path, encoding="utf-8").read().strip()
    return json.loads(txt) if txt.startswith("[") else [json.loads(l) for l in txt.splitlines() if l.strip()]


def as_list(v):
    if v is None:
        return []
    if isinstance(v, str):
        try:
            v = json.loads(v)
        except Exception:
            return []
    return [x for x in (v or []) if isinstance(x, str) and x.strip()]


def rows_of(data_dir, norm, only=None):
    """[(group_id, source_code, label, raw_sentence, compact)]，label 1 = 机器。
    group_id 里带来源行号/标题：同一条样本的人写与机器版本落进同一组，防止改写对跨划分泄漏。"""
    out, stats = [], Counter()
    import csv
    for spec in CORPORA:
        if spec["kind"] != "zh" or not os.path.exists(os.path.join(data_dir, spec["file"])):
            continue
        if only and spec["code"] not in only:
            continue
        path = os.path.join(data_dir, spec["file"])
        if spec["file"].endswith(".csv"):
            reader = (csv.DictReader(io.open(path, encoding="utf-8", errors="ignore", newline="")))
        else:
            reader = read_json(path)
        for i, row in enumerate(reader):
            code = spec["code"]
            if code == "pangda":
                if i >= PANGDA_ROWS:
                    break
                gid = "pangda:%d" % i
                try:
                    paras = ast.literal_eval(row.get("paraphrase") or "[]")
                except Exception:
                    paras = []
                pairs = [(row.get("text") or "", 0)] + [(x, 1) for x in list(paras)[:2]]
            elif code == "hc3zh":
                gid = "hc3zh:%s:%d" % (row.get("source") or "?", i)
                pairs = [(x, 0) for x in as_list(row.get("human_answers"))] + \
                        [(x, 1) for x in as_list(row.get("chatgpt_answers"))]
            else:
                gid = "%s:%s" % (code, row.get("title") or i)
                lab = int(row.get("label", 1)) if row.get("label") is not None else 1
                pairs = [(row.get("text") or "", lab)]
            for text, lab in pairs:
                for sent in split_sentences(text or ""):
                    c = norm.compact(sent)
                    if not scoreable(c):
                        continue
                    out.append((gid, code, lab, sent, c))
                    stats[(code, lab)] += 1
    return out, stats


def holdout_rows(repo, norm):
    rows = []
    for code, rel, lab, note in HOLDOUT_TIERS:
        p = os.path.join(repo, rel.replace("/", os.sep))
        if not os.path.exists(p):
            raise SystemExit("缺独立留出的语料：%s（缺语料一律判失败，不许静默跳过）" % rel)
        for i, line in enumerate(io.open(p, encoding="utf-8")):
            for sent in split_sentences(line.rstrip("\n")):
                c = norm.compact(sent)
                if scoreable(c):
                    rows.append((code, lab, i, sent, c))
    return rows


def template_rows(repo, norm):
    rows = []
    for code, rel in [(t[0], t[1]) for t in TEMPLATE_TIERS]:
        p = os.path.join(repo, rel.replace("/", os.sep))
        for line in io.open(p, encoding="utf-8"):
            c = norm.compact(line.rstrip("\n"))
            if len(c) >= 60:
                rows.append((code, 1, 0, line.rstrip("\n"), c))
    for code, rel, lab, note in HOLDOUT_TIERS:
        p = os.path.join(repo, rel.replace("/", os.sep))
        if not os.path.exists(p):
            continue
        for line in io.open(p, encoding="utf-8"):
            c = norm.compact(line.rstrip("\n"))
            if len(c) >= 60:
                rows.append((code, lab, 0, line.rstrip("\n"), c))
    return rows

# ---------------------------------------------------------------- 模型

import numpy as np
from sklearn.feature_extraction.text import CountVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import roc_auc_score

CONFIGS = [
    dict(name="A-all", only=None, ngram=(2, 4), min_df=20, C=1.0),
    dict(name="B-prhpp-only", only=["prhpp", "prhpp_te", "prhppgen"], ngram=(2, 4), min_df=5, C=1.0),
    dict(name="C-hc3-only", only=["hc3zh"], ngram=(2, 4), min_df=20, C=1.0),
    dict(name="D-no-hc3", only=["prhpp", "prhpp_te", "prhppgen", "pangda"], ngram=(2, 4), min_df=10, C=1.0),
    dict(name="E-formal-mix", only=None, ngram=(2, 4), min_df=20, C=1.0,
         drop=("hc3zh:open_qa:", "hc3zh:psychology:", "hc3zh:nlpcc_dbqa:")),
    dict(name="F-all-1234", only=None, ngram=(1, 4), min_df=20, C=1.0),
]
CACHE = {}


def fold_of(gid, folds=10):
    return int(hashlib.md5(gid.encode("utf-8")).hexdigest()[:8], 16) % folds


def auc(y, s):
    y = np.asarray(y)
    if y.sum() == 0 or y.sum() == len(y):
        return float("nan")
    return float(roc_auc_score(y, s))


def vectors(texts, vec, cols=None):
    X = vec.transform(texts)
    if cols is not None:
        X = X[:, cols]
    X = X.astype(np.float32)
    X.data = np.log1p(X.data)                       # 次线性词频
    nrm = np.sqrt(np.asarray(X.multiply(X).sum(axis=1)).ravel())
    nrm[nrm == 0] = 1.0
    return X.multiply((1.0 / nrm)[:, None]).tocsr()  # 只在这份特征子集上归一化，Java 侧才能算出同一个数


def sigmoid(z):
    return 1.0 / (1.0 + np.exp(-np.clip(z, -40, 40)))


def fit_config(data_dir, norm, cfg):
    rows, stats = rows_of(data_dir, norm, only=cfg.get("only"))
    if cfg.get("drop"):
        rows = [r for r in rows if not any(r[0].startswith(d) for d in cfg["drop"])]
    gids = [r[0] for r in rows]
    y = np.array([r[2] for r in rows])
    comps = [r[4] for r in rows]
    fv = np.array([fold_of(g) for g in gids])
    tr, va = fv != 0, fv == 0
    vec = CountVectorizer(analyzer="char", ngram_range=tuple(cfg["ngram"]), lowercase=False,
                          min_df=cfg["min_df"])
    vec.fit([comps[i] for i in np.where(tr)[0]])
    X = vectors(comps, vec)
    model = LogisticRegression(C=cfg["C"], max_iter=3000, solver="liblinear").fit(X[tr], y[tr])
    w = model.coef_[0]
    cols = np.sort(np.argsort(-np.abs(w))[:KEEP_FEATURES])
    sub = X[:, cols]
    sub = sub.multiply((1.0 / np.maximum(np.sqrt(np.asarray(sub.multiply(sub).sum(axis=1)).ravel()), 1e-9))[:, None]).tocsr()
    m2 = LogisticRegression(C=cfg["C"], max_iter=3000, solver="liblinear").fit(sub[tr], y[tr])
    p = m2.decision_function(sub)
    per = {}
    for code in sorted({r[1] for r in rows}):
        m = va & np.array([r[1] == code for r in rows])
        if m.sum() > 40 and 0 < y[m].sum() < m.sum():
            per[code] = dict(n=int(m.sum()), auc=round(auc(y[m], p[m]), 4))
    return dict(name=cfg["name"], rows=rows, y=y, tr=tr, va=va, vec=vec, cols=cols, model=m2,
                vocab=len(vec.vocabulary_), n=len(rows), n_sent=int(tr.sum()),
                auc_tr=round(auc(y[tr], p[tr]), 4), auc_va=round(auc(y[va], p[va]), 4), per=per,
                scores=p, cfg=cfg)


def academic_proxy(res):
    """公共侧唯一一份"同一位作者同一篇稿，人写原稿 vs ChatGPT 润色"的配对档，用它当跨域代理选配置。"""
    vals = [res["per"][k]["auc"] for k in ("prhpp", "prhpp_te") if k in res["per"]]
    return min(vals) if len(vals) == 2 else None


def stage_train(args):
    os.makedirs(ART, exist_ok=True)
    norm = Normalizer(load_trad_table())
    results = []
    for cfg in CONFIGS:
        r = fit_config(args.data, norm, cfg)
        r["proxy"] = academic_proxy(r)
        results.append(r)
        print("[%s] 句 %-7d 词表 %-7d 导出 %-5d  公共拟合 %.4f 公共留出 %.4f 学术配对档 %s  分档 %s" %
              (r["name"], r["n"], r["vocab"], len(r["cols"]), r["auc_tr"], r["auc_va"],
                 r["proxy"], {k: v["auc"] for k, v in r["per"].items()}))
    ok = [r for r in results if r["proxy"] is not None]
    pick = max(ok, key=lambda r: (r["proxy"], r["auc_va"]))
    print("\n选择规则=公共留出里学术配对档最差那一档最高（不看真稿留出）：选 %s（代理 AUC %.4f）" %
          (pick["name"], pick["proxy"]))
    summary = [dict(name=r["name"], n=r["n"], vocab=r["vocab"], features=len(r["cols"]),
                    auc_fit=r["auc_tr"], auc_valid=r["auc_va"], academic_proxy=r["proxy"],
                    per_source=r["per"]) for r in results]
    json.dump(dict(picked=pick["name"], rule="公共留出·学术配对档最差值最大", configs=summary),
              io.open(os.path.join(ART, "train-summary.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    import pickle
    with open(os.path.join(ART, "model-cache.pkl"), "wb") as f:
        pickle.dump(dict(name=pick["name"], vec=pick["vec"], cols=pick["cols"], model=pick["model"],
                         cfg=pick["cfg"], rows=[(a, b, c, d, e) for a, b, c, d, e in pick["rows"]],
                         y=pick["y"], va=pick["va"], scores=pick["scores"]), f)
    return pick


# ---------------------------------------------------------------- 困惑度滑窗

def build_freq(texts, min_bi=3, min_tri=5, alpha=4.0):
    uni, bi, tri, ctx_b, ctx_t = Counter(), Counter(), Counter(), Counter(), Counter()
    for t in texts:
        s = "^" + t + "$"
        for i in range(len(s)):
            uni[s[i]] += 1
            if i + 1 < len(s):
                bi[s[i:i + 2]] += 1; ctx_b[s[i]] += 1
            if i + 2 < len(s):
                tri[s[i:i + 3]] += 1; ctx_t[s[i:i + 2]] += 1
    n_total = sum(uni.values()) or 1
    lu = {c: math.log2(float(n) / n_total) for c, n in uni.items()}
    oov = math.log2(0.5 / (n_total * max(1, len(uni))))
    lb, lt = {}, {}
    for g, n in bi.items():
        if n < min_bi or len(g) != 2:
            continue
        pu = 2.0 ** lu.get(g[1], oov)
        lb[g] = math.log2((n + alpha * pu) / (ctx_b[g[0]] + alpha))
    for g, n in tri.items():
        if n < min_tri or len(g) != 3:
            continue
        pb = 2.0 ** lb.get(g[1:], lu.get(g[2], oov))
        lt[g] = math.log2((n + alpha * pb) / (ctx_t[g[:2]] + alpha))
    return lu, oov, lb, lt


def bits_per_char(s, lu, oov, lb, lt):
    tot, n = 0.0, 0
    for i in range(len(s)):
        g3 = s[max(0, i - 2):i + 1]
        if len(g3) == 3 and g3 in lt:
            tot += lt[g3]; n += 1; continue
        g2 = s[max(0, i - 1):i + 1]
        if len(g2) == 2 and g2 in lb:
            tot += lb[g2]; n += 1; continue
        tot += lu.get(s[i], oov); n += 1
    return -tot / max(1, n)


def windows(s, w=WINDOW_CHARS, stride=WINDOW_STRIDE):
    if len(s) <= w:
        yield 0, len(s); return
    i = 0
    while i < len(s):
        j = min(len(s), i + w)
        yield i, j
        if j == len(s):
            break
        i += stride

# ---------------------------------------------------------------- 出厂门槛

def stage_gate(args, cache=None):
    import pickle
    norm = Normalizer(load_trad_table())
    if cache is None:
        with open(os.path.join(ART, "model-cache.pkl"), "rb") as f:
            cache = pickle.load(f)
    vec, cols, model = cache["vec"], cache["cols"], cache["model"]
    rows, y, va, scores = cache["rows"], cache["y"], cache["va"], cache["scores"]
    # 偏置只在公共真人验证侧挑：让 P>=0.5 的真人句比例不超过 2/1000
    hum = scores[va & (y == 0)]
    # 往负方向挪偏置只会更保守（P>=0.5 等价于 raw>=-b），所以从 0 往下找"最灵敏且公共真人误报达标"的那一档
    bias = 0.0
    for b in np.arange(0.0, -14.0, -0.02):
        if float(np.mean(sigmoid(hum + b) >= 0.5)) <= GATE_FP_PER_MILLE / 1000.0:
            bias = float(b); break
    valid_p = sigmoid(scores[va] + bias)
    ho = holdout_rows(args.repo, norm)
    X = vectors([r[4] for r in ho], vec, cols)
    p = sigmoid(model.decision_function(X) + bias)
    yh = np.array([r[1] for r in ho])
    codes = np.array([r[0] for r in ho])
    tiers = []
    for code, rel, lab, note in HOLDOUT_TIERS:
        m = codes == code
        if m.sum() == 0:
            continue
        tiers.append(dict(code=code, side="机器" if lab else "真人", n=int(m.sum()),
                          mean=round(float(p[m].mean()), 4), max=round(float(p[m].max()), 4),
                          min=round(float(p[m].min()), 4)))
    fp = 1000.0 * float(np.sum(p[yh == 0] >= FLAG_GATE)) / max(1, int((yh == 0).sum()))
    fp05 = 1000.0 * float(np.sum(p[yh == 0] >= 0.5)) / max(1, int((yh == 0).sum()))
    hit = 100.0 * float(np.sum(p[yh == 1] >= FLAG_GATE)) / max(1, int((yh == 1).sum()))
    sent_auc = round(auc(yh, p), 4)
    # 段级（每段一个样本，段分取段内句分均值）
    seg = {}
    for r, pv in zip(ho, p):
        seg.setdefault((r[0], r[2]), []).append(float(pv))
    sy = [1 if HOLDOUT_TIERS_ONLY_MACHINE(c) else 0 for (c, i) in seg]
    ss = [float(np.mean(v)) for v in seg.values()]
    seg_auc = round(auc(np.array(sy), np.array(ss)), 4)

    # 滑窗困惑度：频率表只用公共真人语料，阈值取公共真人窗口的 p0.1
    human_public = [r[4] for r in rows if r[2] == 0]
    formal = [r[4] for r in rows if r[2] == 0 and (r[1].startswith("prhpp") or
              r[1] == "hc3zh" and r[0].split(":")[1] in ("baike", "medicine", "law", "finance"))]
    lu, oov, lb, lt = build_freq(formal)
    pub_win = [bits_per_char(s[a:b], lu, oov, lb, lt)
               for s in human_public[:40000] for a, b in windows(norm.compact(s)) if b - a >= WINDOW_MIN]
    pub_win = np.array(pub_win)
    pools = template_rows(args.repo, norm)
    cache_win = [(code, lab, [(a, b, bits_per_char(c[a:b], lu, oov, lb, lt))
                              for a, b in windows(c) if b - a >= WINDOW_MIN])
                 for code, lab, _, raw, c in pools]

    def sweep(thr):
        seg_stat, win_tot = {}, {0: [0, 0], 1: [0, 0]}
        for code, lab, ws in cache_win:
            hits = [1 if v < thr else 0 for _, _, v in ws]
            d = seg_stat.setdefault(code, dict(lab=lab, segs=0, segs_hit=0, wins=0, wins_hit=0))
            d["segs"] += 1; d["wins"] += len(ws); d["wins_hit"] += sum(hits); d["segs_hit"] += (1 if sum(hits) else 0)
            win_tot[lab][0] += len(ws); win_tot[lab][1] += sum(hits)
        tpl = [d for d in seg_stat.values() if d["lab"] == 1]
        hum = [d for d in seg_stat.values() if d["lab"] == 0]
        return seg_stat, win_tot, \
            float(sum(d["segs_hit"] for d in tpl)) / max(1, sum(d["segs"] for d in tpl)), \
            float(sum(d["segs_hit"] for d in hum)) / max(1, sum(d["segs"] for d in hum))

    thr = float(np.percentile(pub_win, 0.1))
    seg_stat, win_tot, tpl_hit, hum_hit = sweep(thr)
    curve = []
    for pct in (0.1, 1.0, 5.0, 25.0, 50.0):
        s, w, th, hh = sweep(float(np.percentile(pub_win, pct)))
        curve.append(dict(public_percentile=pct, threshold_bits=round(float(np.percentile(pub_win, pct)), 3),
                          template_segment_hit=round(th, 4), human_segment_hit=round(hh, 4),
                          human_window=round(100.0 * w[0][1] / max(1, w[0][0]), 3),
                          machine_window=round(100.0 * w[1][1] / max(1, w[1][0]), 3)))
    best = None
    for b in np.arange(float(np.percentile(pub_win, 90)), float(np.percentile(pub_win, 0.1)), -0.05):
        s, w, th, hh = sweep(float(b))
        if hh <= GATE_HUMAN_WINDOW and (best is None or th > best[1]):
            best = (float(b), th, hh)
    sweep_best = None if best is None else dict(threshold_bits=round(best[0], 3),
                                                template_segment_hit=round(best[1], 4),
                                                human_segment_hit=round(best[2], 4))
    out = dict(model_version=MODEL_VERSION, bias=round(float(bias), 4),
               public=dict(n=len(rows), auc_fit=round(auc(y[~va], scores[~va]), 4),
                           auc_valid=round(auc(y[va], scores[va]), 4)),
               holdout=dict(n=int(len(ho)), auc=sent_auc, auc_paragraph=seg_auc,
                            fp_per_mille_at_045=round(fp, 3), fp_per_mille_at_050=round(fp05, 3),
                            machine_hit_percent_at_045=round(hit, 2), tiers=tiers,
                            operating_point=operating_point(p, yh)),
               window=dict(threshold_bits_per_char=round(thr, 4), table=dict(uni=len(lu), bi=len(lb), tri=len(lt)),
                           public_windows=len(pub_win),
                           template_segment_hit_rate=round(tpl_hit, 4), human_segment_hit_rate=round(hum_hit, 4),
                           human_window_rate=round(100.0 * win_tot[0][1] / max(1, win_tot[0][0]), 3),
                           machine_window_rate=round(100.0 * win_tot[1][1] / max(1, win_tot[1][0]), 3),
                           per_tier=seg_stat, threshold_curve=curve,
                           best_zero_human_point=sweep_best),
               gates=dict(auc_required=GATE_AUC, fp_required=GATE_FP_PER_MILLE,
                          template_required=GATE_TEMPLATE_HIT, human_window_allowed=GATE_HUMAN_WINDOW,
                          auc_pass=sent_auc >= GATE_AUC, fp_pass=fp <= GATE_FP_PER_MILLE,
                          template_pass=tpl_hit >= GATE_TEMPLATE_HIT, human_window_pass=hum_hit <= GATE_HUMAN_WINDOW))
    out["gates"]["calibrated"] = bool(out["gates"]["auc_pass"] and out["gates"]["fp_pass"])
    out["gates"]["window_calibrated"] = bool(out["gates"]["template_pass"] and out["gates"]["human_window_pass"])
    json.dump(out, io.open(os.path.join(ART, "gate.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    with open(os.path.join(ART, "freq-cache.pkl"), "wb") as f:
        pickle.dump(dict(lu=lu, oov=oov, lb=lb, lt=lt, thr=thr), f)
    print(json.dumps({k: out[k] for k in ("holdout", "gates")}, ensure_ascii=False, indent=1)[:1600])
    print("滑窗：阈值 %.3f bits/字  模板段落点 %.1f%%（门槛 90%%）  真人段落点 %.1f%%（门槛 0%%）  窗口级 真人 %.2f%% / 机器 %.2f%%"
          % (thr, 100 * tpl_hit, 100 * hum_hit, out["window"]["human_window_rate"], out["window"]["machine_window_rate"]))
    print("结论：句级打分 calibrated=%s   滑窗 calibrated=%s" %
          (out["gates"]["calibrated"], out["gates"]["window_calibrated"]))
    return out


def operating_point(p, yh):
    """事后算的那个工作点：要让真人误报 <= 2/1000，门槛得抬到哪，那时机器侧还能剩几句。
    这一列只用来证明"没有可用工作点"，不参与任何建模决定。"""
    hum = np.sort(p[yh == 0])
    need = int(math.ceil(len(hum) * (1.0 - GATE_FP_PER_MILLE / 1000.0))) - 1
    thr = float(hum[max(0, min(need, len(hum) - 1))])
    return dict(threshold=round(thr, 4),
                machine_hit_percent=round(100.0 * float(np.sum(p[yh == 1] >= thr)) / max(1, int((yh == 1).sum())), 2),
                human_fp_per_mille=round(1000.0 * float(np.sum(hum >= thr)) / max(1, len(hum)), 3))


def HOLDOUT_TIERS_ONLY_MACHINE(code):
    return code.startswith("M-")

# ---------------------------------------------------------------- 导出（进仓库的只有派生统计量）

def fmt(x):
    return ("%.4f" % x).rstrip("0").rstrip(".")


def stage_export(args):
    import pickle
    gate = json.load(io.open(os.path.join(ART, "gate.json"), encoding="utf-8"))
    with open(os.path.join(ART, "model-cache.pkl"), "rb") as f:
        cache = pickle.load(f)
    with open(os.path.join(ART, "freq-cache.pkl"), "rb") as f:
        freq = pickle.load(f)
    vec, cols, model = cache["vec"], cache["cols"], cache["model"]
    inv = {v: k for k, v in vec.vocabulary_.items()}
    w = model.coef_[0]
    os.makedirs(ASSETS, exist_ok=True)
    pairs = [(inv[c], float(w[i])) for i, c in enumerate(cols) if c in inv]
    pairs.sort(key=lambda kv: -abs(kv[1]))
    wp = os.path.join(ASSETS, "aigc-zh-weights.tsv")
    with io.open(wp, "w", encoding="utf-8", newline="\n") as f:
        f.write("# Wordlite 离线 AIGC 打分权重：逻辑回归系数，键是字符 n-gram（已按 TextCorpus.normalize 折叠并去掉空白）\n")
        f.write("# 列：w<TAB>ngram<TAB>weight　　分数 = sigmoid(截距 + Σ (1+ln 次数)*权重 / L2 长度)\n")
        f.write("# 来源与许可见 aigc-model.tsv；模型未达标，calibrated=false，界面不许印百分比\n")
        for gram, weight in pairs:
            f.write("w\t%s\t%s\n" % (gram, fmt(weight)))
    fp_path = os.path.join(ASSETS, "aigc-zh-freq.tsv")
    with io.open(fp_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("# Wordlite 字级 n-gram 频率表（只由公开真人语料估计）：滑窗困惑度代理用，log2 概率\n")
        f.write("# 列：ns<TAB>字串<TAB>log2p　　ns=u 单字 / b 二字 / t 三字；查表顺序 t->b->u，查不到用 oov\n")
        for g, v in sorted(freq["lu"].items(), key=lambda kv: -kv[1]):
            f.write("u\t%s\t%s\n" % (g, fmt(v)))
        for g, v in sorted(freq["lb"].items(), key=lambda kv: -kv[1]):
            f.write("b\t%s\t%s\n" % (g, fmt(v)))
        for g, v in sorted(freq["lt"].items(), key=lambda kv: -kv[1]):
            f.write("t\t%s\t%s\n" % (g, fmt(v)))
    # 黄金样本：Java 侧必须复算出同一个分数，否则说明两侧归一化口径漂了
    norm = Normalizer(load_trad_table())
    rows, y, va = cache["rows"], cache["y"], cache["va"]
    idx = np.where(va)[0]
    hum = [i for i in idx if y[i] == 0][:3]
    mac = [i for i in idx if y[i] == 1][:3]
    golden = []
    for i in hum + mac:
        raw, comp = rows[i][3], rows[i][4]
        p = float(sigmoid(model.decision_function(vectors([comp], vec, cols)) + cache_bias(cache))[0])
        golden.append(dict(text=raw, side="human" if y[i] == 0 else "machine", p=round(p, 4)))
    gw = [dict(text=rows[hum[0]][3], bits=round(bits_per_char(norm.compact(rows[hum[0]][3])[:24],
                                                          freq["lu"], freq["oov"], freq["lb"], freq["lt"]), 4))]
    mp = os.path.join(ASSETS, "aigc-model.tsv")
    metrics = gate
    mrows = [
        ("model_version", MODEL_VERSION),
        ("created", "2026-10-09"),
        ("what", "字符 2/3/4-gram + 词表 + 逻辑回归（CPU 训练，手机侧纯手写打分，无 ONNX、无新依赖）；"
                 "另附字级 1/2/3-gram 频率表做滑窗困惑度代理"),
        ("trained_on", "；".join("%s（%s，%s）" % (s["code"], s["license"], s["note"]) for s in CORPORA if s["kind"] == "zh")),
        ("not_used", "；".join("%s：%s" % (w, why) for w, why in NOT_USED)),
        ("split", "按来源行/标题分组随机十等分，第 0 份留出；同一条样本的人写与机器版本落进同一组"),
        ("independent_holdout", "仓库内人工标注真稿：真人 H1 学位论文正文 + H2 已发表摘要逐字摘录 + H3 知网片段 + "
                                "X1 轻度润色 + 钉子户，机器 M-RAW / M-EVADE / M-DOMAIN；这批一个字都没进训练"),
        ("provenance", "公共语料侧留出 AUC " + str(metrics["public"]["auc_valid"]) + "（拟合 " +
                       str(metrics["public"]["auc_fit"]) + "，" + str(metrics["public"]["n"]) + " 句）；"
                       "真稿独立留出 AUC(机器>真人)=" + str(metrics["holdout"]["auc"]) +
                       "，段级 " + str(metrics["holdout"]["auc_paragraph"]) + "，真人误报 " +
                       str(metrics["holdout"]["fp_per_mille_at_045"]) + " 句/千句，机器侧过线 " +
                       str(metrics["holdout"]["machine_hit_percent_at_045"]) + "%；"
                       "出厂门槛 0.85 与 2 句/千句，两条都不过，故 calibrated=false，界面不印任何 AIGC 百分比"),
        ("ngram_min", str(cache["cfg"]["ngram"][0])),
        ("ngram_max", str(cache["cfg"]["ngram"][1])),
        ("exported_features", str(len(pairs))),
        ("vocab_size", str(len(vec.vocabulary_))),
        ("min_df", str(cache["cfg"]["min_df"])),
        ("intercept", "%.6f" % float(model.intercept_[0])),
        ("bias", "%.4f" % float(gate["bias"])),
        ("oov_log2", "%.4f" % float(freq["oov"])),
        ("window_chars", str(WINDOW_CHARS)),
        ("window_stride", str(WINDOW_STRIDE)),
        ("window_min_chars", str(WINDOW_MIN)),
        ("window_threshold", "%.4f" % float(freq["thr"])),
        ("freq_table", "单字 %d / 二字 %d / 三字 %d（只由公开真人语料估计）" % (len(freq["lu"]), len(freq["lb"]), len(freq["lt"]))),
        ("public_valid_auc", "%.4f" % metrics["public"]["auc_valid"]),
        ("holdout_auc", "%.4f" % metrics["holdout"]["auc"]),
        ("holdout_auc_paragraph", "%.4f" % metrics["holdout"]["auc_paragraph"]),
        ("holdout_human_fp_per_mille", "%.3f" % metrics["holdout"]["fp_per_mille_at_045"]),
        ("holdout_machine_hit_percent", "%.2f" % metrics["holdout"]["machine_hit_percent_at_045"]),
        ("window_threshold_curve", json.dumps(metrics["window"]["threshold_curve"], ensure_ascii=False)),
        ("template_window_hit", "%.4f" % metrics["window"]["template_segment_hit_rate"]),
        ("human_window_hit", "%.4f" % metrics["window"]["human_segment_hit_rate"]),
        ("calibrated", "true" if gate["gates"]["calibrated"] else "false"),
        ("window_calibrated", "true" if gate["gates"]["window_calibrated"] else "false"),
    ]
    with io.open(mp, "w", encoding="utf-8", newline="\n") as f:
        f.write("# Wordlite 离线 AIGC 模型清单：一行一个键值，Java 侧 AigcNgramModel 直接读这份\n")
        f.write("# 列：key<TAB>value　　#开头是注释\n")
        for k, v in mrows:
            f.write("%s\t%s\n" % (k, str(v).replace("\t", " ").replace("\n", " ")))
        for g in golden:
            f.write("golden\t%s\t%s\t%.4f\n" % (g["text"].replace("\t", " ").replace("\n", " "), g["side"], g["p"]))
        for g in gw:
            f.write("golden_bits\t%s\t%.4f\n" % (g["text"].replace("\t", " ").replace("\n", " "), g["bits"]))
    if os.path.exists(os.path.join(ASSETS, "aigc-model.json")):
        os.remove(os.path.join(ASSETS, "aigc-model.json"))

    manifest = dict(   # 只用于打印与自检，进仓库的清单是上面那份 aigc-model.tsv
        model_version=MODEL_VERSION,
        created="2026-10-09",
        what="字符 2/3/4-gram + 词表 + 逻辑回归（CPU 训练，手机侧纯手写打分，无 ONNX、无新依赖）"
             "；另附字级 1/2/3-gram 频率表做滑窗困惑度代理",
        trained_on=[dict(code=s["code"], license=s["license"], redistributable_raw=s["ship_raw"],
                         note=s["note"], url=s["url"].split("/resolve/")[0]) for s in CORPORA if s["kind"] == "zh"],
        not_used=[dict(what=w, why=why) for w, why in NOT_USED],
        split="按来源行/标题分组随机十等分，第 0 份留出；同一条样本的人写与机器版本落进同一组",
        features=dict(ngram_min=cache["cfg"]["ngram"][0], ngram_max=cache["cfg"]["ngram"][1],
                      exported=len(pairs), vocab=len(vec.vocabulary_), min_df=cache["cfg"]["min_df"],
                      intercept=round(float(model.intercept_[0]), 6)),
        window=dict(chars=WINDOW_CHARS, stride=WINDOW_STRIDE, min_chars=WINDOW_MIN,
                    oov_log2=round(float(freq["oov"]), 4), threshold=round(float(freq["thr"]), 4)),
        metrics=gate,
        gates=gate["gates"],
        calibrated=bool(gate["gates"]["calibrated"]),
        window_calibrated=bool(gate["gates"]["window_calibrated"]),
        provenance_statement="在真稿独立留出（真人 493 计分句 + 机器 262 计分句那批）上 AUC(机器>真人)="
                             + str(gate["holdout"]["auc"]) + "，低于出厂门槛 " + str(GATE_AUC)
                             + "，所以只交付模型与报告，界面不印任何 AIGC 百分比",
        golden_scores=golden, golden_bits=gw,
        files=dict(weights="aigc-zh-weights.tsv", freq="aigc-zh-freq.tsv"),
    )
    for pth in (wp, fp_path, mp):
        print("%-34s %7.1f KB" % (os.path.basename(pth), os.path.getsize(pth) / 1024.0))
    print("导出权重 %d 条，频率表 %d 条，黄金样本 %d 条" % (len(pairs), len(freq["lu"]) + len(freq["lb"]) + len(freq["lt"]), len(golden)))
    return manifest


def cache_bias(cache):
    return json.load(io.open(os.path.join(ART, "gate.json"), encoding="utf-8"))["bias"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("stage", choices=["fetch", "train", "gate", "export", "all"])
    ap.add_argument("--data", default=DEFAULT_DATA)
    ap.add_argument("--repo", default=REPO)
    ap.add_argument("--proxy", default=os.environ.get("WORDLITE_AIGC_PROXY", "http://127.0.0.1:7897"))
    ap.add_argument("--redo", action="store_true")
    a = ap.parse_args()
    if a.stage in ("fetch", "all"):
        stage_fetch(a)
    if a.stage in ("train", "all"):
        stage_train(a)
    if a.stage in ("gate", "all"):
        stage_gate(a)
    if a.stage in ("export", "all"):
        stage_export(a)


if __name__ == "__main__":
    main()
