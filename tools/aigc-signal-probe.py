# -*- coding: utf-8 -*-
"""候选信号实测台（主机侧，一次量完，不进包）。

    py tools/aigc-signal-probe.py sig                # 逐信号表：真人分布 / 机器分布 / AUC
    py tools/aigc-signal-probe.py sig --cap 200      # 少取训练料，先确认脚本能跑

量的是同一批真稿句子（tests/corpus 那批独立留出 + 段落级第二把尺子 holdout2），每个候选信号
只算一个数，不做任何封装：一个信号在这一批句子上就分不开，包一层也分不开。

方向符号在这里先验定死，不许看完真稿成绩再翻（翻了那个数就不干净了）：
  bits_*    每字 bits（困惑度）。机器稿是模型的高概率输出，理应"更好猜"，机写向 = -bits。
  dom       bits(通用真人模型) - bits(同域真人模型)：这段有多"像真稿的行文"。真人真稿该更高，
            机器稿（只是话题沾学术、行文是通用腔）该更低，机写向 = -dom。
  contrast  bits(机器同域模型) - bits(真人同域模型)：更像机器写的那一路还是真人写的那一路，机写向 = -contrast。
  sies      熵带统计量（DetectGPT 那篇的 SIES，落到 n 元字模型上）：比模型自己预期的更好猜多少，机写向 = sies。
  logrank   对数秩（Fast-DetectGPT 用秩换掉对数概率 / GLTR 的Colour 同源）：秩小 = 模型本来就想这么写，机写向 = -logrank。

留出集与训练料逐字不重合由脚本自己算并写进产物（verbatim_overlap / longstring_overlap），不靠人保证。
产物：artifacts/aigc-signal-probe/signals.json
"""
import argparse, bisect, hashlib, importlib.util, io, json, math, os, sys, time
import numpy as np
from collections import Counter, defaultdict

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
ART = os.path.join(REPO, "artifacts", "aigc-signal-probe")
ASSETS = os.path.join(REPO, "app", "src", "main", "assets", "aigc")
SHINGLES = 24
LOG2 = math.log(2.0)


def load_train_module():
    spec = importlib.util.spec_from_file_location("aigcmodel", os.path.join(HERE, "build-aigc-model.py"))
    tm = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(tm)
    return tm


# ---------------------------------------------------------------- 字符 n-gram 语言模型

class NGramLM(object):
    """插值绝对折扣的字符 n-gram 模型（只用于主机实测；包内那套是硬回退，两码事）。

    P_k(w|c) = max(A(c,w)-D,0)/A(c·) + lam(c)·P_{k-1}(w|c')，lam(c)=D·T(c)/A(c·)，一阶是带平滑
    的单字分布加一个未登录桶。之所以要把整条分布算出来而不是只查"这一条 gram"：熵带统计量
    要的就是整个字表上的期望，只查得到真值字那一条算不上熵。
    """

    def __init__(self, order=3, discount=1.0, alpha=1.0, name=""):
        self.order = max(1, int(order))
        self.D = float(discount)
        self.alpha = float(alpha)
        self.name = name
        self.ctr = [dict() for _ in range(self.order + 1)]     # ctr[k][ngram] = 次数
        self.cw = [dict() for _ in range(self.order + 1)]      # cw[k][ctx] = [(字, 次数)]
        self.tot = [dict() for _ in range(self.order + 1)]     # tot[k][ctx] = 该上下文总次数
        self.n = 0
        self.train_chars = 0
        self.train_units = 0

    def train(self, texts):
        # 建表阶段每个上下文用 dict 累加，最后才转成 [(字, 次数)] 列表：
        # 直接在列表里找同名续字会让高频上下文退化成平方级，一百万字要跑好几分钟。
        o = self.order
        build = [dict() for _ in range(o + 1)]
        for t in texts:
            s = "^" * (o - 1) + t + "$"
            self.train_chars += len(t)
            self.train_units += 1
            for i in range(len(s)):
                w = s[i]
                self.n += 1
                self.ctr[1][w] = self.ctr[1].get(w, 0) + 1
                for k in range(2, o + 1):
                    if i - k + 1 < 0:
                        continue
                    g = s[i - k + 1:i + 1]
                    d = self.ctr[k]
                    d[g] = d.get(g, 0) + 1
                    cx = g[:-1]
                    b = build[k].setdefault(cx, {})
                    b[w] = b.get(w, 0) + 1
                    self.tot[k][cx] = self.tot[k].get(cx, 0) + 1
        for k in range(2, o + 1):
            self.cw[k] = dict((cx, [[w, c] for w, c in d.items()]) for cx, d in build[k].items())
        del build
        self.vocab = sorted(self.ctr[1].keys())
        V = len(self.vocab) + 1
        denom = float(self.n) + self.alpha * V
        self.p1_by = dict((w, (self.ctr[1][w] + self.alpha) / denom) for w in self.vocab)
        self.p1_oov = self.alpha / denom
        self.p1_sorted = sorted(self.p1_by.values())
        self.p1_sum_sorted = self.p1_sorted
        self.H1 = -sum(p * math.log(p, 2) for p in self.p1_by.values()) \
            - self.p1_oov * math.log(self.p1_oov, 2)
        self._pc = [dict() for _ in range(self.order + 1)]
        self._H = [dict() for _ in range(self.order + 1)]
        self._rk = {}
        return self

    # ---- 概率：log2 P_k(g[-1] | g[:-1])
    def logp(self, k, g):
        if k == 1:
            v = self.p1_by.get(g)
            return math.log(v, 2) if v else math.log(self.p1_oov, 2)
        pc = self._pc[k]
        v = pc.get(g)
        if v is not None:
            return v
        c, w = g[:-1], g[-1]
        tot = self.tot[k].get(c, 0)
        if not tot:
            v = self.logp(k - 1, w)
        else:
            lam = self.D * len(self.cw[k][c]) / float(tot)
            low = 2.0 ** self.logp(k - 1, w)
            p = (max(self.ctr[k].get(g, 0) - self.D, 0.0) / tot) + lam * low
            v = math.log(p, 2) if p > 0 else -40.0
        pc[g] = v
        return v

    def _lam(self, k, c):
        tot = self.tot[k].get(c, 0)
        return (self.D * len(self.cw[k][c]) / float(tot)) if tot else 0.0

    # ---- 熵：对整个字表（含未登录桶）逐字精确
    def entropy(self, k, c):
        if k == 1:
            return self.H1
        m = self._H[k]
        v = m.get(c)
        if v is not None:
            return v
        tot = self.tot[k].get(c, 0)
        if not tot:
            v = self.entropy(k - 1, c[1:])
            m[c] = v
            return v
        lam = self.D * len(self.cw[k][c]) / float(tot)
        h_low = self.entropy(k - 1, c[1:])
        s1 = s2 = h_seen = 0.0
        for w, cnt in self.cw[k][c]:
            pl = 2.0 ** self.logp(k - 1, w)
            s1 += pl
            s2 += pl * math.log(pl, 2)
            p = (max(cnt - self.D, 0.0) / tot) + lam * pl
            if p > 0:
                h_seen += p * math.log(p, 2)
        tail = lam * ((1.0 - s1) * math.log(lam, 2) + (-h_low - s2))
        v = max(0.0, -(h_seen + tail))
        m[c] = v
        return v

    # ---- 秩：真值字在该上下文分布里排第几（1 = 模型本来最想写它）
    def rank(self, k, c, w):
        key = (k, c)
        box = self._rk.get(key)
        if box is None:
            tot = self.tot[k].get(c, 0)
            if not tot:
                box = (None, None, 0)
            else:
                lam = self.D * len(self.cw[k][c]) / float(tot)
                ps = []
                for x, cnt in self.cw[k][c]:
                    ps.append((max(cnt - self.D, 0.0) / tot) + lam * (2.0 ** self.logp(k - 1, x)))
                ps.sort()
                box = (ps, lam, tot)
            self._rk[key] = box
        ps, lam, tot = box
        pw = 2.0 ** self.logp(k, c + w)
        if ps is None:
            return 1 + bisect.bisect_right(self.p1_sorted, pw)
        r = 1 + (len(ps) - bisect.bisect_right(ps, pw))
        # 未登录续字的概率是 lam·P1(x)：全表里比它大的个数用一阶分布二分，再减掉已出现那批里多算的
        if lam > 0:
            need = pw / lam
            all_hi = len(self.p1_sorted) - bisect.bisect_right(self.p1_sorted, need)
            seen_hi = 0
            for x, cnt in self.cw[k][c]:
                if self.p1_by[x] > need:
                    seen_hi += 1
            oov_hi = max(0, all_hi - seen_hi)
            if self.p1_oov > need:
                oov_hi += 1
            r += oov_hi
        return r

    # ---- 逐位打分：每字 log2 概率 / 熵 / 秩
    def scan(self, s):
        o = self.order
        t = "^" * (o - 1) + s + "$"
        lp = [0.0] * len(t)
        ent = [0.0] * len(t)
        rk = [0] * len(t)
        for i in range(o - 1, len(t)):
            w = t[i]
            c = t[i - o + 1:i]
            lp[i] = self.logp(o, c + w)
            ent[i] = self.entropy(o, c)
            rk[i] = self.rank(o, c, w)
        lo = o - 1
        return lp[lo:], ent[lo:], rk[lo:]

    def bits_per_char(self, s):
        lp, _e, _r = self.scan(s)
        return -sum(lp) / (LOG2 * max(1, len(lp)))
# ---------------------------------------------------------------- 随包模型（原样读，不改）

def read_shipped():
    """读 app/src/main/assets/aigc/ 三件：口径逐字照 AigcNgramModel.java。"""
    def tsv(rel):
        rows = []
        for line in io.open(os.path.join(ASSETS, rel), encoding="utf-8"):
            if not line or line[0] == "#":
                continue
            p = line.rstrip("\n").split("\t")
            if len(p) >= 3:
                rows.append(p)
        return rows

    man = {}
    for line in io.open(os.path.join(ASSETS, "aigc-model.tsv"), encoding="utf-8"):
        if line[:1] == "#":
            continue
        p = line.rstrip("\n").split("\t")
        if len(p) >= 2:
            man[p[0]] = p[1]

    def num(k, d=float("nan")):
        v = man.get(k, "")
        if v == "" or v.lower() == "nan" or v == "None":
            return d
        return float(v)

    w = {}
    for p in read_shipped_rows("aigc-zh-weights.tsv"):
        w[p[1]] = float(p[2])
    f = {}
    for p in read_shipped_rows("aigc-zh-freq.tsv"):
        f[p[1]] = float(p[2])
    return dict(man=man, weights=w, freq=f,
                nmin=int(num("ngram_min", 2)), nmax=int(num("ngram_max", 4)),
                intercept=num("intercept", 0.0), bias=num("bias", 0.0), oov=num("oov_log2", -24.0))


def read_shipped_rows(rel):
    out = []
    for line in io.open(os.path.join(ASSETS, rel), encoding="utf-8"):
        if not line or line[0] == "#":
            continue
        p = line.rstrip("\n").split("\t")
        if len(p) >= 3 and p[1]:
            out.append(p)
    return out


def shipped_bits(sh, s):
    """与 AigcNgramModel.bitsPerChar 同口径：三字 -> 二字 -> 单字硬回退，查不到退 oov。"""
    f, oov = sh["freq"], sh["oov"]
    tot, cnt = 0.0, 0
    cp = list(s)
    for i in range(len(cp)):
        lp = None
        for ln in (3, 2, 1):
            begin = i - ln + 1
            if begin < 0:
                continue
            v = f.get("".join(cp[begin:i + 1]))
            if v:
                lp = v
                break
        if lp is None:
            lp = oov
        tot += lp
        cnt += 1
    return -tot / (LOG2 * max(1, cnt))


def shipped_score(sh, s):
    """与 AigcNgramModel.score 同口径：次线性词频 x=1+ln(次数)，只在导出表上点特征、也只在该表上归一化。"""
    cp = list(s)
    n = len(cp)
    if n < sh["nmin"]:
        return 0.0
    seen = {}
    for i in range(n):
        for ln in range(sh["nmin"], sh["nmax"] + 1):
            if i + ln > n:
                break
            g = "".join(cp[i:i + ln])
            seen[g] = seen.get(g, 0) + 1
    dot = sq = 0.0
    for g, c in seen.items():
        w = sh["weights"].get(g)
        if not w:
            continue
        x = 1.0 + math.log(float(c))
        dot += x * w
        sq += x * x
    if sq <= 0:
        return 0.0
    z = sh["intercept"] + sh["bias"] + dot / math.sqrt(sq)
    z = max(-40.0, min(40.0, z))
    return 1.0 / (1.0 + math.exp(-z))


# ---------------------------------------------------------------- 语料（训练料与留出料严格分家）

def bucket_round_robin(buckets, cap_chars, cap_units):
    """各桶轮流取，取到字符数上限为止：不让某一个语料把额度吃光，也不引入随机数。"""
    out, chars, units = [], 0, 0
    i = 0
    while chars < cap_chars and units < cap_units:
        moved = False
        for name in sorted(buckets):
            rows = buckets[name]
            if i >= len(rows):
                continue
            r = rows[i]
            out.append(r)
            chars += len(r[0])
            units += 1
            moved = True
            if chars >= cap_chars or units >= cap_units:
                break
        if not moved:
            break
        i += 1
    return out, chars, units


def drlx_units(path, norm, tm, want_split, want_domain, label):
    """DetectRL-X 中文：一行里同时有真人原文与现代模型改写稿——配对关系就在行内。"""
    out = []
    if not os.path.exists(path):
        return out
    for line in io.open(path, encoding="utf-8"):
        r = json.loads(line)
        if r.get("split") != want_split:
            continue
        dom = str(r.get("domain") or "")
        if want_domain == "Academic" and dom != "Academic":
            continue
        if want_domain != "Academic" and dom == "Academic":
            continue
        txt = r.get("human_written_text") if label == 0 else r.get("llm_generated_text")
        for sent in tm.split_sentences(txt or ""):
            c = norm.compact(sent)
            if tm.scoreable(c):
                out.append((c, "drlx:%s:%s:%d" % (want_split, dom, len(out)), label, "drlx-" + dom,
                            r.get("model") or ""))
    return out


def gather_training(tm, data, norm, cap_chars, cap_units, verbose=True):
    """训练料：只从仓库外的公开语料里取，全部带许可记录（见 tools/build-aigc-model.py 的 CORPORA）。
    返回 {lm 名: [(compact, gid, label, domain, extra)]}，外加一份逐字/长串对照用的全集。"""
    only = ["prhpp", "prhpp_te", "prhppgen", "hc3zh", "setask8zh", "anxzh", "pangda"]
    rows, _stats = tm.rows_of(data, norm, only=only)
    per = defaultdict(list)
    for gid, code, lab, raw, comp, dom in rows:
        per[(code, dom, lab)].append((comp, "%s:%s" % (code, gid), lab, dom, ""))
    drlx = os.path.join(data, "drlx_zh_general.jsonl")
    for dom_flag, lab in (("Academic", 0), ("Academic", 1), ("other", 0), ("other", 1)):
        got = drlx_units(drlx, norm, tm, "train", dom_flag, lab)
        per[("drlxzh", "drlx-%s" % ("academic" if dom_flag == "Academic" else "general"), lab)].extend(got)
        if verbose:
            print("  drlx %s 侧 %s：%d 计分句" % (dom_flag, "真人" if lab == 0 else "机器", len(got)))

    # 先给"全部公开训练料"打个逐字指纹与 24 字长串指纹：留出集到底有没有沾过训练料，
    # 由这里算，不靠人保证。用的是取上限之前的全量，不只是真喂进模型那一份。
    pool_sha, pool_sh = set(), set()
    for key in sorted(per):
        for comp, gid, lab, dom, extra in per[key]:
            pool_sha.add(hashlib.sha1(comp.encode("utf-8")).hexdigest())
            for j in range(0, max(1, len(comp) - SHINGLES + 1), 12):
                if len(comp[j:j + SHINGLES]) == SHINGLES:
                    pool_sh.add(comp[j:j + SHINGLES])
    del rows

    def pick(codes, lab, doms=None):
        b = {}
        for (code, dom, l), v in per.items():
            if code in codes and l == lab and (doms is None or dom in doms):
                b["%s/%s" % (code, dom)] = v
        return b

    # 同域真人：中文学术摘要（prhpp）+ 中文期刊/鉴定文书正文（DetectRL-X Academic 真人侧，train 划分）
    ah, ca, ua = bucket_round_robin(pick(("prhpp", "prhpp_te", "drlxzh"), 0,
                                         ("academic-abstract", "drlx-academic")), cap_chars, cap_units)
    # 通用真人：HC3 各子域 + 社区口语 + 新闻 + 检索短句 + DetectRL-X 非学术真人侧
    gh, cg, ug = bucket_round_robin(pick(("hc3zh", "setask8zh", "anxzh", "pangda", "drlxzh"), 0,
                                         None), cap_chars, cap_units)
    # 同域机器：同一批学术题的现代模型改写稿
    am, cm, um = bucket_round_robin(pick(("prhpp", "prhpp_te", "prhppgen", "drlxzh"), 1,
                                         ("academic-abstract", "drlx-academic")), cap_chars, cap_units)
    if verbose:
        print("训练料字符数：同域真人 %.2fM / 通用真人 %.2fM / 同域机器 %.2fM" %
              (ca / 1e6, cg / 1e6, cm / 1e6))
        print("公开训练料全量指纹：逐字 %d 条 / %d 字长串 %d 条" % (len(pool_sha), SHINGLES, len(pool_sh)))
    return dict(ah=ah, gh=gh, am=am), dict(ah=(ca, ua), gh=(cg, ug), am=(cm, um)), pool_sha, pool_sh


def paired_probe(tm, data, norm, cap_units, seed_stride=1):
    """配对档：同一篇真稿的真人原句 vs 现代模型改写句（DetectRL-X Academic 的 test 划分，
    与上面训练料按 split 严格分开）。逐字完全相同的那一对整对丢掉——同一条句子不许一边当人一边当机。"""
    path = os.path.join(data, "drlx_zh_general.jsonl")
    hum, mac = [], []
    if not os.path.exists(path):
        return hum, mac, {}
    by_doc = {}
    for line in io.open(path, encoding="utf-8"):
        r = json.loads(line)
        if r.get("split") != "test" or str(r.get("domain")) != "Academic":
            continue
        gid = "drlxtest:%s" % (r.get("human_written_text") or "")[:40]
        box = by_doc.setdefault(gid, {"h": [], "m": [], "models": set()})
        for sent in tm.split_sentences(r.get("human_written_text") or ""):
            c = norm.compact(sent)
            if tm.scoreable(c):
                box["h"].append(c)
        for sent in tm.split_sentences(r.get("llm_generated_text") or ""):
            c = norm.compact(sent)
            if tm.scoreable(c):
                box["m"].append(c)
        box["models"].add(str(r.get("model")))
    docs = sorted(by_doc.items())[::seed_stride]
    amb = same = 0
    for gid, box in docs:
        hs = set(box["h"])
        ms = [x for x in box["m"] if x not in hs]
        same += len(box["m"]) - len(ms)
        if not ms or not box["h"]:
            amb += 1
            continue
        hum.extend([(x, gid) for x in box["h"]])
        mac.extend([(x, gid) for x in ms])
    if len(hum) > cap_units:
        hum, mac = hum[:cap_units], mac[:cap_units]
    return hum, mac, dict(docs=len(docs), human=len(hum), machine=len(mac),
                          dropped_identical_docs=amb, verbatim_machine_sentences_dropped=same,
                          models=sorted(set().union(*[b["models"] for b in by_doc.values()]) if by_doc else set()))
# ---------------------------------------------------------------- 信号

def bits_of(lm, s):
    o = lm.order
    t = "^" * (o - 1) + s + "$"
    tot = 0.0
    for i in range(o - 1, len(t)):
        tot += lm.logp(o, t[i - o + 1:i + 1])
    return -tot / (LOG2 * max(1, len(t) - (o - 1)))


def sies_of(lm, s):
    """SIES（Mitchell 等 DetectGPT 那篇里的熵带统计量）：Z_k = Σ(实际信息量 - 模型自己的期望)，
    只累计 Z 掉到 0 以下的部分。机器稿比模型的预期更好猜 -> Z 早早就掉下去 -> 值更大。"""
    o = lm.order
    t = "^" * (o - 1) + s + "$"
    z = acc = 0.0
    n = 0
    for i in range(o - 1, len(t)):
        u = -lm.logp(o, t[i - o + 1:i + 1])
        z += u - lm.entropy(o, t[i - o + 1:i])
        if z < 0:
            acc += z
        n += 1
    return (acc - min(0.0, z)) / max(1, n)


def logrank_of(lm, s):
    """平均 log2 秩：模型在该位置把真值字排第几。秩小 = 模型本来就想这么写 = 机器味。"""
    o = lm.order
    t = "^" * (o - 1) + s + "$"
    tot = 0.0
    n = 0
    for i in range(o - 1, len(t)):
        c = t[i - o + 1:i]
        tot += math.log(max(1, lm.rank(o, c, t[i])), 2)
        n += 1
    return tot / max(1, n)


SIGNALS = [
    # 名字, 机写向符号（+1 = 该值越大越像机器）, 说明
    ("bits_shipped", -1, "随包字级 1/2/3-gram 频率表的 bits/字（AigcNgramModel.bitsPerChar 同口径）"),
    ("s_shipped", +1, "随包逻辑回归句分（现在出厂那份，③ 真稿 AUC 0.5556 的那个）"),
    ("bits_ah", -1, "bits/字：同域真人模型（中文学术摘要 + 中文期刊正文，全部公开语料）"),
    ("bits_gh", -1, "bits/字：通用真人模型（HC3 各域 + 社区口语 + 新闻 + 检索短句）"),
    ("bits_am", -1, "bits/字：同域机器模型（同一批学术题的现代模型改写稿）"),
    ("dom", -1, "bits(通用) - bits(同域)：两个模型的对数似然差 = 这段有多像真稿行文"),
    ("contrast", -1, "bits(同域机器) - bits(同域真人)：两个模型的对数似然差 = 走的是哪一路"),
    ("sies_ah2", +1, "熵带 SIES（同域真人模型，二字上下文）"),
    ("sies_ah3", +1, "熵带 SIES（同域真人模型，三字上下文）"),
    ("logrank_ah2", -1, "平均 log2 秩（同域真人模型，二字上下文，秩精确）"),
    ("chars", +1, "只看长度（反证尺：留出集自身的构造偏置）"),
]


def compute_signals(unit, sh, lms, want):
    out = {}
    c = unit
    if "bits_shipped" in want:
        out["bits_shipped"] = shipped_bits(sh, c)
    if "s_shipped" in want:
        out["s_shipped"] = shipped_score(sh, c)
    if "bits_ah" in want:
        out["bits_ah"] = bits_of(lms["ah3"], c)
    if "bits_gh" in want:
        out["bits_gh"] = bits_of(lms["gh3"], c)
    if "bits_am" in want:
        out["bits_am"] = bits_of(lms["am3"], c)
    if "dom" in want:
        out["dom"] = out["bits_gh"] - out["bits_ah"]
    if "contrast" in want:
        out["contrast"] = out["bits_am"] - out["bits_ah"]
    if "sies_ah2" in want:
        out["sies_ah2"] = sies_of(lms["ah2"], c)
    if "sies_ah3" in want:
        out["sies_ah3"] = sies_of(lms["ah3"], c)
    if "logrank_ah2" in want:
        out["logrank_ah2"] = logrank_of(lms["ah2"], c)
    out["chars"] = float(len(c))
    return out


# ---------------------------------------------------------------- 报表

def auc_machine_gt_human(h, m, sign):
    """AUC(机写 > 真人)：等价于 Mann-Whitney U，并列算半分。sign 把每个信号折成机写向。"""
    a = [sign * x for x in h]
    b = [sign * x for x in m]
    if not a or not b:
        return float("nan")
    sa = sorted(a)
    wins = 0.0
    for x in b:
        lo = bisect.bisect_left(sa, x)
        hi = bisect.bisect_right(sa, x)
        wins += hi + 0.5 * (hi - lo)   # P(机写向分数 > 真人向分数)，并列算半分
    return wins / (float(len(a)) * len(b))


def pct(v, q):
    if not v:
        return float("nan")
    s = sorted(v)
    i = int(round((len(s) - 1) * q))
    return s[max(0, min(len(s) - 1, i))]


def operating_point(h, m, sign, fp_per_mille=2.0):
    """把阈值抬到"真人侧只有 fp_per_mille 句越线"的位置，看机器侧还剩多少过线。"""
    a = sorted(sign * x for x in h)
    if not a:
        return None
    need = int(math.ceil(len(a) * (1.0 - fp_per_mille / 1000.0))) - 1
    thr = a[max(0, min(len(a) - 1, need))]
    thr = math.nextafter(thr, float("inf"))
    fp = 1000.0 * sum(1 for x in a if x >= thr) / len(a)
    hit = 100.0 * sum(1 for x in m if sign * x >= thr) / len(m) if m else float("nan")
    return dict(thr=thr, human_fp_per_mille=round(fp, 3), machine_hit_percent=round(hit, 3))


def eval_recs(recs, sh, lms, slow_units, verbose=True):
    want = set(n for n, _s, _n2 in SIGNALS)
    slow = set(["sies_ah2", "sies_ah3", "logrank_ah2"])
    stride = max(1, int(math.ceil(len(recs) / float(max(1, slow_units)))))
    t0 = time.time()
    for k, r in enumerate(recs):
        w = want if (k % stride == 0 or not slow) else (want - slow)
        r["sig"] = compute_signals(r["compact"], sh, lms, w)
        if verbose and k and k % 2000 == 0:
            print("    已量 %d/%d 条（%.0f 秒）" % (k, len(recs), time.time() - t0))
    return recs


def overlap_proof(recs, pool_sha, pool_sh, tag):
    """留出侧对训练料的重合：逐字（SHA-1）与 24 字长串两把尺子。"""
    verbatim = hits1 = hits2 = 0
    for r in recs:
        c = r["compact"]
        if hashlib.sha1(c.encode("utf-8")).hexdigest() in pool_sha:
            verbatim += 1
            r["verbatim_in_train"] = True
        h = 0
        for j in range(0, max(1, len(c) - SHINGLES + 1), 12):
            if len(c[j:j + SHINGLES]) == SHINGLES and c[j:j + SHINGLES] in pool_sh:
                h += 1
        if h >= 1:
            hits1 += 1
        if h >= 2:
            hits2 += 1
    out = dict(pool=tag, n=len(recs), verbatim=verbatim, longstring_ge1=hits1, longstring_ge2=hits2)
    print("重合自检[%s]：逐字 %d / %d 条；共用 >=1 条 %d 字长串 %d 条；>=2 条 %d 条" %
          (tag, verbatim, len(recs), SHINGLES, hits1, hits2))
    return out


def holdout2_rows(tm, path, norm):
    """段落级真人真稿那一档（清单 tests/corpus/aigc-holdout2-manifest.json，原文留在仓库外）。
    tools/build-aigc-model.py 里有同名函数就用它的口径，没有就按同一个元组形状自己读：
    这个探针要能在干净的 HEAD 上跑起来，不许把还没验过的改动当成自己的前提。
    返回 [(sid, paper_group, discipline, band, raw, compact, license)]，文件不在就是空表。"""
    if hasattr(tm, "holdout2_rows"):
        return tm.holdout2_rows(path, norm)
    if not path or not os.path.isfile(path):
        print("段落级真稿留出缺文件（%s）：第二把尺子不量" % path)
        return []
    out = []
    for line in io.open(path, encoding="utf-8"):
        r = json.loads(line)
        out.append((r["sid"], r["paper_group"], r["discipline"], int(r["band"]), r["text"],
                    norm.compact(r["text"]), r.get("license") or ""))
    return out


def load_audit(path, norm):
    """出厂审计（tests/AigcFeatureAuditRegression.java）导出的逐句清单：出厂那条测试实际打分的就是
    这一批句子，拿它当第一把尺子，实测就能和出厂读数一字不差地对上。只有跑审计时设了
    WORDLITE_AIGC_AUDIT_DUMP 才有这个文件；里面是论文原句，落在 artifacts/ 下，不进仓库。"""
    if not path or not os.path.isfile(path):
        print("出厂审计逐句：%s 不在（跑出厂审计时设 WORDLITE_AIGC_AUDIT_DUMP 才会导出）——这一档不量" % path)
        return []
    recs = []
    for k, line in enumerate(io.open(path, encoding="utf-8")):
        parts = line.rstrip("\n").split("\t")
        if len(parts) < 5:
            continue
        c = norm.compact("\t".join(parts[4:]))
        if not c:
            continue
        recs.append(dict(sid="AUD%d" % k, tier=parts[0], y=0 if parts[1] == "HUMAN" else 1,
                         compact=c, audit_score=float(parts[3])))
    print("出厂审计逐句 %d 条（真人 %d / 机器 %d，档位 %s）：出厂那条审计实际打分的就是这批句子" %
          (len(recs), sum(1 for r in recs if r["y"] == 0), sum(1 for r in recs if r["y"] == 1),
           dict(sorted(Counter(r["tier"] for r in recs).items()))))
    return recs


def stage_sig(args):
    if not os.path.isdir(ART):
        os.makedirs(ART)
    tm = load_train_module()
    norm = tm.Normalizer(tm.load_trad_table())
    sh = read_shipped()
    print("随包模型 %s：权重 %d 条 / 频率 %d 条 / 清单 AUC(真稿)=%s" %
          (sh["man"].get("model_version"), len(sh["weights"]), len(sh["freq"]),
           sh["man"].get("holdout_auc")))

    lmsets, lmstats, pool_sha, pool_sh = gather_training(tm, args.data, norm, args.cap_chars, args.cap_units)
    print("建语言模型……")
    lms = {}
    lms["ah3"] = NGramLM(3, args.discount, name="同域真人/三字").train([x[0] for x in lmsets["ah"]])
    lms["gh3"] = NGramLM(3, args.discount, name="通用真人/三字").train([x[0] for x in lmsets["gh"]])
    lms["am3"] = NGramLM(3, args.discount, name="同域机器/三字").train([x[0] for x in lmsets["am"]])
    lms["ah2"] = NGramLM(2, args.discount, name="同域真人/二字").train([x[0] for x in lmsets["ah"]])
    for k in sorted(lms):
        print("  %-4s %-14s 训练字数 %.2fM 条 %d / 二字表 %d / 三字表 %d / 字表 %d" %
              (k, lms[k].name, lms[k].train_chars / 1e6, lms[k].train_units,
               len(lms[k].tot[2]), len(lms[k].tot[3]) if lms[k].order >= 3 else 0, len(lms[k].vocab)))

    # ③ 真稿独立留出（出厂门槛只认这一档）
    ho = tm.holdout_rows(args.repo, norm)
    t3 = [dict(sid="%s#%d" % (c, i), tier=c, y=int(l), compact=c2) for c, l, i, s, c2 in ho]
    # 第二把尺子：段落级真人真稿（只有真人侧），按篇取前若干段封顶后切成句
    h2raw = holdout2_rows(tm, args.holdout2, norm)
    papers = sorted(set(r[1] for r in h2raw))
    stride = max(1, int(math.ceil(len(papers) / float(max(1, args.h2_papers)))))
    keep = set(papers[::stride])
    h2 = []
    for r in h2raw:
        if r[1] not in keep:
            continue
        for sent in tm.split_sentences(r[4]):
            c = norm.compact(sent)
            if tm.scoreable(c):
                h2.append(dict(sid="%s#s%d" % (r[0], len(h2)), tier="H2-PARA", y=0, compact=c,
                               disc=r[2], lic=r[6]))
        if len(h2) >= args.h2_units:
            break
    h2 = h2[:args.h2_units]
    print("③ 真稿句 %d 条（真人 %d / 机器 %d，档位 %s）" %
          (len(t3), sum(1 for r in t3 if r["y"] == 0), sum(1 for r in t3 if r["y"] == 1),
           dict(sorted(Counter(r["tier"] for r in t3).items()))))
    print("第二把尺子：段落级真稿 %d 篇 %d 计分句（逐段许可 %s）" %
          (len(set(r["sid"].split("#")[0] for r in h2)), len(h2),
           dict(sorted(Counter(r.get("lic") for r in h2).items()))))
    ph, pm, pmeta = paired_probe(tm, args.data, norm, args.paired_units)
    pr = [dict(sid="P-H%d" % i, tier="P-HUMAN", y=0, compact=c) for i, (c, g) in enumerate(ph)] + \
         [dict(sid="P-M%d" % i, tier="P-MACHINE", y=1, compact=c) for i, (c, g) in enumerate(pm)]
    print("配对档（DetectRL-X Academic test，现代模型 %s）：真人 %d / 机器 %d，丢掉整对逐字相同的 %d 篇，"
          "机器侧逐字与真人原句相同而剔除 %d 句" %
          ("/".join(pmeta.get("models", [])), pmeta.get("human", 0), pmeta.get("machine", 0),
           pmeta.get("dropped_identical_docs", 0), pmeta.get("verbatim_machine_sentences_dropped", 0)))

    proofs = {}
    for tag, key, recs in (("③ 真稿句", "tier3", t3), ("段落级真稿", "holdout2", h2), ("配对档", "paired", pr)):
        pf = overlap_proof(recs, pool_sha, pool_sh, tag)
        bad = [r for r in recs if r.get("verbatim_in_train")]
        if bad:
            print("    其中逐字落进过公开训练料而剔出打分池：%d 条（%s...）" % (len(bad), bad[0]["compact"][:40]))
        recs = [r for r in recs if not r.get("verbatim_in_train")]
        pf["scored"] = len(recs)
        proofs[key] = pf
        if key == "tier3":
            t3 = recs
        elif key == "holdout2":
            h2 = recs
        else:
            pr = recs
    hs = set(r["compact"] for r in t3 if r["y"] == 0)
    ms = set(r["compact"] for r in t3 if r["y"] == 1)
    conflict = sorted(hs & ms)
    print("③ 内部自洽：同一条句子在真人档和机器档里逐字都出现的 %d 条 -> 两侧一并剔掉，不参与打分"
          % len(conflict))
    for x in conflict:
        print("    冲突句：%s" % x[:70])
    t3 = [r for r in t3 if r["compact"] not in conflict]

    audit = load_audit(args.audit_dump, norm)
    if audit:
        pf = overlap_proof(audit, pool_sha, pool_sh, "出厂审计逐句")
        bad = [r for r in audit if r.get("verbatim_in_train")]
        if bad:
            print("    其中逐字落进过公开训练料而剔出打分池：%d 条（%s...）" % (len(bad), bad[0]["compact"][:40]))
        audit = [r for r in audit if not r.get("verbatim_in_train")]
        pf["scored"] = len(audit)
        proofs["audit_dump"] = pf
        aconf = sorted(set(r["compact"] for r in audit if r["y"] == 0)
                       & set(r["compact"] for r in audit if r["y"] == 1))
        if aconf:
            print("    同一条句子在审计的真人档与机器档里逐字都出现的 %d 条 -> 两侧一并剔掉：%s" %
                  (len(aconf), aconf[0][:60]))
            audit = [r for r in audit if r["compact"] not in aconf]

    print("逐条量信号……")
    for tag, recs in (("③", t3), ("段落级", h2), ("配对档", pr), ("出厂审计逐句", audit)):
        print("  %s：%d 条" % (tag, len(recs)))
        eval_recs(recs, sh, lms, args.slow_units)

    hum3 = [(r["sid"], r) for r in t3 if r["y"] == 0]
    mac3 = [(r["sid"], r) for r in t3 if r["y"] == 1]
    hum_big = hum3 + [(r["sid"], r) for r in h2 if r["y"] == 0]
    humP = [(r["sid"], r) for r in pr if r["y"] == 0]
    macP = [(r["sid"], r) for r in pr if r["y"] == 1]
    humA = [(r["sid"], r) for r in audit if r["y"] == 0]
    macA = [(r["sid"], r) for r in audit if r["y"] == 1]
    pools = [("③真稿独立留出", (hum3, mac3)), ("③c 真人侧扩到段落级", (hum_big, mac3)),
             ("配对档 真人原句 vs 同句模型改写", (humP, macP))]
    if humA and macA:
        pools.insert(0, ("①出厂审计逐句(同一批)", (humA, macA)))

    lines = ["信号实测：随包模型 %s / 语言模型 折扣 %.2f / 训练料上限 %d 字" %
             (sh["man"].get("model_version"), args.discount, args.cap_chars)]
    jp = dict((name, {}) for name, _r in pools)
    table = {}
    for name, sign, note in SIGNALS:
        for pool, (humR, macR) in pools:
            hum = [(sid, r["sig"][name]) for sid, r in humR if name in r["sig"]]
            mac = [(sid, r["sig"][name]) for sid, r in macR if name in r["sig"]]
            if not hum or not mac:
                continue
            a = auc_machine_gt_human([v for _, v in hum], [v for _, v in mac], sign)
            op = operating_point([v for _, v in hum], [v for _, v in mac], sign)
            table.setdefault(pool, {})[name] = (a, len(hum), len(mac), op)
            lines.append("SIG %-12s %-26s n=%d/%d  AUC(机写>真人)=%.4f  机写向=%+d  真人p50=%.4f 机器p50=%.4f  "
                         "[FP<=2/千句 阈值=%.4f 真人误报=%.2f 机器过线=%.2f%%]" %
                         (name, pool, len(hum), len(mac), a, sign, pct([v for _, v in hum], 0.5),
                          pct([v for _, v in mac], 0.5), op["thr"], op["human_fp_per_mille"],
                          op["machine_hit_percent"]))
            jp[pool][name] = dict(auc=round(a, 4), sign=sign, n_human=len(hum), n_machine=len(mac),
                                  human_p50=round(pct([v for _, v in hum], 0.5), 4),
                                  machine_p50=round(pct([v for _, v in mac], 0.5), 4),
                                  human_p=[round(pct([v for _, v in hum], q), 4) for q in (0.05, 0.25, 0.5, 0.75, 0.95)],
                                  machine_p=[round(pct([v for _, v in mac], q), 4) for q in (0.05, 0.25, 0.5, 0.75, 0.95)],
                                  op_2permille=op, note=note)
    print("\n".join(lines))

    print("\n逐档均值（③ 真稿，机写向折好符号）：")
    for name, sign, note in SIGNALS:
        by = {}
        for r in t3:
            if name not in r["sig"]:
                continue
            by.setdefault(r["tier"], []).append(sign * r["sig"][name])
        print("  %-12s %s" % (name, "  ".join("%s=%.4f" % (k, sum(v) / len(v)) for k, v in sorted(by.items()))))

    out = {}
    maga_hum = stage_maga(args, tm, norm, lms, out) if args.maga_domain else []
    maga = [dict(sid="MG%05d" % i, tier="MAGA-HUMAN", y=0, compact=c) for i, c in enumerate(maga_hum)]
    if maga:
        eval_recs(maga, sh, lms, 10 ** 9, verbose=False)

    headline(sh, t3, h2, maga, out, audit)

    verdict = {}
    for pool, d in table.items():
        best = sorted(d.items(), key=lambda kv: -kv[1][0])[:3]
        verdict[pool] = [("%.4f" % v[0]) + " " + k for k, v in best]
        print("最好三档[%s]：%s" % (pool, "  ".join(verdict[pool])))
    out.update(dict(created=time.strftime("%Y-%m-%d %H:%M:%S"), shipped_model=sh["man"].get("model_version"),
               shipped_manifest_auc_holdout=sh["man"].get("holdout_auc"),
               gate=dict(auc_min=0.85, human_fp_per_mille_max=2.0),
               lm=dict(discount=args.discount, cap_chars=args.cap_chars, cap_units=args.cap_units,
                       train_units={k: v[1] for k, v in lmstats.items()},
                       orders=dict((k, lms[k].order) for k in lms),
                       vocab=dict((k, len(lms[k].vocab)) for k in lms)),
               pools=dict((k, v) for k, v in jp.items()),
               counts=dict(tier3=len(t3), tier3_human=len(hum3), tier3_machine=len(mac3),
                           holdout2=len(h2), paired=dict(pmeta), audit_dump=len(audit),
                           audit_dump_human=sum(1 for r in audit if r["y"] == 0)),
               overlap=proofs, tier3_human_machine_verbatim_same=len(conflict)))
    gate_hits = []
    for pool, d in table.items():
        for name, (a, _nh, _nm, op) in sorted(d.items(), key=lambda kv: -kv[1][0]):
            if a >= 0.85 and op and op["human_fp_per_mille"] <= 2.0:
                gate_hits.append(dict(pool=pool, signal=name, auc=round(a, 4), op=op,
                                      usable=bool(op and op["machine_hit_percent"] >= 1.0)))
    out["gate_two_line_hits"] = gate_hits
    json.dump(out, io.open(os.path.join(ART, "signals.json"), "w", encoding="utf-8", newline=""),
              ensure_ascii=False, indent=1)
    print("产物：%s" % os.path.join(ART, "signals.json"))
    print("判定：门槛的两条字面判据是 ③ 真稿 AUC(机写>真人) >= 0.85 且真人误报 <= 2 句/千句。"
          "字面上过这两条的逐档连同一工作点的机器召回一起打出来——"
          "在那个阈值上一句机器稿都抓不到的判据，没有可印的数。")
    for g in gate_hits:
        print("  字面过两条：%s / %s AUC=%.4f 真人误报=%.2f/千句 -> 同阈值机器过线 %.2f%%%s" %
              (g["pool"], g["signal"], g["auc"], g["op"]["human_fp_per_mille"],
               g["op"]["machine_hit_percent"], "" if g["usable"] else "（工作点退化：阈值就压在真人尾分位那个点上，不可用）"))
    if not any(g["pool"] == "③真稿独立留出" and g["usable"] for g in gate_hits):
        print("  ③ 真稿没有任何一条能同时过 AUC>=0.85、FP<=2/千句、并且在该阈值上真的抓到机器稿：" 
              "calibrated() 继续 false，界面保持判据未标定。")


# ---------------------------------------------------------------- 头号指标：真人被误判率

HEADLINE_SIGNALS = ["s_shipped", "bits_shipped", "bits_ah", "bits_gh", "dom", "sies_ah3", "logrank_ah2"]
FLAG_GATE = 0.45          # AigcDetector.SEGMENT_FLAG_GATE


def headline(sh, t3, h2, maga, out, audit=()):
    """阈值一律先在"真稿③ 真人句"这一档定到 FP<=2/千句，然后原封不动搬到别的池子上量。
    换池子就爆表的信号，说明它量的是域不是作者——这一档就是专门看这件事的。"""
    pools = [("真稿③ 真人句", [r for r in t3 if r["y"] == 0]),
             ("真稿段落级 真人段", [r for r in h2 if r["y"] == 0]),
             ("MAGA-cn 真人句(留出折)", [r for r in maga if r["y"] == 0]),
             ("出厂审计逐句 真人侧", [r for r in audit if r["y"] == 0])]
    pools = [(n, rs) for n, rs in pools if rs]
    print("\n真人被误判率（头号指标；阈值定在真稿③ 真人侧 FP<=2/千句处，再原样量到其它池子）：")
    res = {}
    for name in HEADLINE_SIGNALS:
        sign = dict((n, sg) for n, sg, _x in SIGNALS)[name]
        base = [r["sig"][name] for r in t3 if r["y"] == 0 and name in r["sig"]]
        if len(base) < 200:
            continue
        op = operating_point(base, base, sign, 2.0)
        thr = op["thr"]
        print("  信号 %-12s 机写向=%+d 阈值=%.4f" % (name, sign, thr))
        row = {}
        for pool, rs in pools:
            v = [sign * r["sig"][name] for r in rs if name in r["sig"]]
            if not v:
                continue
            over = sum(1 for x in v if x >= thr)
            per = 1000.0 * over / len(v)
            row[pool] = dict(n=len(v), flagged=over, per_mille=round(per, 2),
                             p50=round(pct([sign * x for x in v], 0.5), 4),
                             p99=round(pct([sign * x for x in v], 0.99), 4),
                             max=round(max(sign * x for x in v), 4))
            print("     %-22s n=%-6d 误判 %5.2f/千句（%d 句越线）  p50=%.4f p99=%.4f max=%.4f" %
                  (pool, len(v), per, over, row[pool]["p50"], row[pool]["p99"], row[pool]["max"]))
        res[name] = dict(threshold=round(thr, 4), sign=sign, pools=row)
    sv = [r["sig"]["s_shipped"] for r in t3 if r["y"] == 0]
    print("  随包逻辑回归句分用应用现用门槛 %.2f 直接量（不重新定阈值）：" % FLAG_GATE)
    for pool, rs in pools:
        v = [r["sig"]["s_shipped"] for r in rs if "s_shipped" in r["sig"]]
        if not v:
            continue
        over = sum(1 for x in v if x >= FLAG_GATE)
        print("     %-22s n=%-6d 判为机写 %d 句 = %.2f%%（%.1f/千句）  mean=%.4f p99=%.4f max=%.4f" %
              (pool, len(v), over, 100.0 * over / len(v), 1000.0 * over / len(v),
               sum(v) / len(v), pct(v, 0.99), max(v)))
        res.setdefault("s_shipped_at_045", {})[pool] = dict(
            n=len(v), flagged=over, percent=round(100.0 * over / len(v), 3),
            per_mille=round(1000.0 * over / len(v), 2), mean=round(sum(v) / len(v), 4),
            p99=round(pct(v, 0.99), 4), max=round(max(v), 4))
    out["headline_human_flagrate"] = res
    out["note_calibrated"] = ("calibrated() 仍为 false：这一档不参与出厂判定，只说明"
                              "在两个池子上 FP<=2/千句 是不是同一条门槛")


# ---------------------------------------------------------------- 同域线性模型：真人被判率实测

def sha_set(comps):
    return set(hashlib.sha1(c.encode("utf-8")).hexdigest() for c in comps)


def load_real_thesis(tm, norm, args):
    ho = tm.holdout_rows(args.repo, norm)
    t3 = [dict(sid="%s#%d" % (c, i), tier=c, y=int(l), compact=c2) for c, l, i, st, c2 in ho]
    h2 = []
    for r in holdout2_rows(tm, args.holdout2, norm):
        for sent in tm.split_sentences(r[4]):
            c = norm.compact(sent)
            if tm.scoreable(c):
                h2.append(dict(sid="%s#s%d" % (r[0], len(h2)), tier="H2-PARA", y=0, compact=c))
        if len(h2) >= args.h2_units:
            break
    return t3[:], h2[:args.h2_units]


def fit_one(name, tr_h, tr_m, ev_sets, C, min_df, ngram, log):
    """tr_h / tr_m：训练用真人句与机器句。ev_sets：[(池子名, [(label, compact)])]，label 1=机器。
    口径逐字照 tools/build-aigc-model.py：char 2-4 gram + 次线性词频 + 只在选中列上归一 + 逻辑回归。"""
    from sklearn.feature_extraction.text import CountVectorizer
    from sklearn.linear_model import LogisticRegression
    n = min(len(tr_h), len(tr_m))
    hs, ms = tr_h[:n], tr_m[:n]
    comps = hs + ms
    y = np.array([0] * len(hs) + [1] * len(ms))
    vec = CountVectorizer(analyzer="char", ngram_range=tuple(ngram), lowercase=False, min_df=min_df)
    vec.fit(comps)
    X = tm_vectors(comps, vec)
    m = LogisticRegression(C=C, max_iter=3000)
    m.fit(X, y)
    print("\n[%s] 训练：真人 %d / 机器 %d 句，特征 %d 列" % (name, len(hs), len(ms), len(vec.vocabulary_)))
    rows = {}
    scores = {}
    for pool, items in ev_sets:
        if not items:
            continue
        comp = [c for _l, c in items]
        yy = np.array([l for l, _c in items])
        sc = tm_sigmoid(m.decision_function(tm_vectors(comp, vec)))
        scores[pool] = (yy, sc)
        f45 = int(np.sum(sc >= 0.45))
        f50 = int(np.sum(sc >= 0.5))
        a = auc_machine_gt_human(list(sc[yy == 0]), list(sc[yy == 1]), +1) if (yy == 0).any() and (yy == 1).any() else float("nan")
        rows[pool] = dict(n=len(comp), human=int((yy == 0).sum()), machine=int((yy == 1).sum()),
                          auc=round(float(a), 4), flag45_percent=round(100.0 * f45 / len(comp), 3),
                          human_flag_percent=round(100.0 * float(np.sum(sc[yy == 0] >= 0.45)) / max(1, int((yy == 0).sum())), 3),
                          human_flag50_percent=round(100.0 * float(np.sum(sc[yy == 0] >= 0.5)) / max(1, int((yy == 0).sum())), 3),
                          machine_flag_percent=round(100.0 * float(np.sum(sc[yy == 1] >= 0.45)) / max(1, int((yy == 1).sum())), 3),
                          human_mean=round(float(np.mean(sc[yy == 0])), 4),
                          machine_mean=round(float(np.mean(sc[yy == 1])), 4) if (yy == 1).any() else None)
        print("   %-30s n=%-7d 真人 %d / 机器 %d  AUC=%.4f  真人判机 %.2f%%（>=0.5: %.2f%%）  "
              "机器判机 %.2f%%  真人分 mean=%.4f p99=%.4f" %
              (pool, len(comp), rows[pool]["human"], rows[pool]["machine"], rows[pool]["auc"],
               rows[pool]["human_flag_percent"], rows[pool]["human_flag50_percent"],
               rows[pool]["machine_flag_percent"], rows[pool]["human_mean"],
               pct(list(sc[yy == 0]), 0.99)))
    # 把阈值抬到真稿真人侧 FP<=2/千句，再看两边各剩什么
    for anchor in ("真稿③真人句", "真稿段落级"):
        if anchor not in scores:
            continue
        yy, sc = scores[anchor]
        hv = np.sort(sc[yy == 0])
        if not len(hv):
            continue
        need = int(math.ceil(len(hv) * (1.0 - 2.0 / 1000.0))) - 1
        thr = float(np.nextafter(hv[max(0, min(need, len(hv) - 1))], 2.0))
        line = "   [%s] 阈值抬到真稿 FP<=2/千句 -> thr=%.4f；" % (anchor, thr)
        det = {}
        for pool, (yy2, sc2) in scores.items():
            if (yy2 == 1).any():
                rec = 100.0 * float(np.sum(sc2[yy2 == 1] >= thr)) / int((yy2 == 1).sum())
                fp = 1000.0 * float(np.sum(sc2[yy2 == 0] >= thr)) / max(1, int((yy2 == 0).sum()))
                det[pool] = dict(machine_recall_percent=round(rec, 3), human_fp_per_mille=round(fp, 2))
                line += " %s：机器召回 %.2f%% / 真人误报 %.2f 千句 ;" % (pool, rec, fp)
        print(line)
        rows["_at_" + anchor] = dict(threshold=round(thr, 4), detail=det)
    log[name] = rows


def stage_fit(args):
    global tm_
    tm = load_train_module()
    globals()["tm_mod"] = tm
    norm = tm.Normalizer(tm.load_trad_table())
    path = os.path.join(args.data, os.path.basename(args.maga))
    docs = read_maga(path, norm, tm, args.maga_domain, 10, 0)
    ev = dict((k, v) for k, v in docs.items() if v["fold"] == 0)
    tr = dict((k, v) for k, v in docs.items() if v["fold"] != 0)
    models = sorted(set(m for d in docs.values() for m in d["mac"]))
    tr_h = sents([t for v in tr.values() for t in v["human"]], norm, tm, cap=args.fit_side_cap)
    tr_m_all = [(t, m) for v in tr.values() for m, ts in v["mac"].items() for t in ts]
    tr_m_all = sents([t for t, _m in tr_m_all], norm, tm, cap=args.fit_side_cap)
    ev_h = sents([t for v in ev.values() for t in v["human"]], norm, tm, cap=args.fit_eval_cap)
    ev_m_all = sents([t for v in ev.values() for m, ts in v["mac"].items() for t in ts],
                     norm, tm, cap=args.fit_eval_cap)
    focal = args.maga_focal if args.maga_focal in models else models[-1]
    fm = [t for v in tr.values() for m, ts in v["mac"].items() if m == focal for t in ts]
    tr_m_focal = sents(fm, norm, tm, cap=args.fit_side_cap)
    print("MAGA[%s]：题 %d（训练折 %d / 留出折 %d）台数 %d；真人训练句 %d，机器训练句 %d（全部）/%d（%s）；"
          "留出真人 %d 机器 %d" % (args.maga_domain, len(docs), len(tr), len(ev), len(models),
                                   len(tr_h), len(tr_m_all), len(tr_m_focal), focal, len(ev_h), len(ev_m_all)))
    t3, h2 = load_real_thesis(tm, norm, args)
    print("真稿：③ %d 句（真人 %d / 机器 %d）+ 段落级 %d 句" %
          (len(t3), sum(1 for r in t3 if r["y"] == 0), sum(1 for r in t3 if r["y"] == 1), len(h2)))
    overlap = sha_set([r["compact"] for r in t3 + h2]) & sha_set(tr_h + tr_m_all + ev_h + ev_m_all)
    print("真稿与 MAGA 逐字相同句：%d（必须是 0，否则这一档不能用）" % len(overlap))
    drlx = os.path.join(args.data, "drlx_zh_general.jsonl")
    dh = drlx_units(drlx, norm, tm, "train", "Academic", 0)
    dm = drlx_units(drlx, norm, tm, "train", "Academic", 1)
    print("DetectRL-X Academic（train 划分）：真人 %d / 机器 %d" % (len(dh), len(dm)))

    ev_sets = [("MAGA 留出（同域同分布）", [(0, c) for c in ev_h] + [(1, c) for c in ev_m_all]),
               ("真稿③真人句", [(0, r["compact"]) for r in t3 if r["y"] == 0]),
               ("真稿③机器句", [(1, r["compact"]) for r in t3 if r["y"] == 1]),
               ("真稿段落级", [(0, r["compact"]) for r in h2])]
    log = {}
    fit_one("MAGA %s 全部12台" % args.maga_domain, tr_h, tr_m_all, ev_sets, args.C, args.min_df,
            (args.ngram_min, args.ngram_max), log)
    fit_one("MAGA %s 只用 %s" % (args.maga_domain, focal), tr_h, tr_m_focal, ev_sets, args.C,
            args.min_df, (args.ngram_min, args.ngram_max), log)
    fit_one("DetectRL-X Academic 配对（现代4台改写）", [x[0] for x in dh][:args.fit_side_cap],
            [x[0] for x in dm][:args.fit_side_cap], ev_sets, args.C, args.min_df,
            (args.ngram_min, args.ngram_max), log)
    out = dict(file=os.path.basename(path), license=MAGA_LICENSE, domain=args.maga_domain,
               focal_generator=focal, models=models, maga=doc_counts(docs, tr, ev),
               real_thesis=dict(tier3=len(t3), holdout2=len(h2)),
               maga_real_thesis_verbatim_overlap=len(overlap), drlx=dict(human=len(dh), machine=len(dm)),
               flag_gate=0.45, results=log)
    json.dump(out, io.open(os.path.join(ART, "fit-flagrate.json"), "w", encoding="utf-8", newline=""),
              ensure_ascii=False, indent=1)
    print("产物：%s" % os.path.join(ART, "fit-flagrate.json"))


def doc_counts(docs, tr, ev):
    return dict(docs=len(docs), train_docs=len(tr), eval_docs=len(ev),
                pairs=int(sum(sum(len(x) for x in v["mac"].values()) for v in docs.values())))


def tm_vectors(comps, vec):
    tm = globals()["tm_mod"]
    return tm.vectors(comps, vec)


def tm_sigmoid(z):
    tm = globals()["tm_mod"]
    return tm.sigmoid(z)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("stage", choices=["sig", "fit"])
    ap.add_argument("--repo", default=REPO)
    ap.add_argument("--data", default=os.path.abspath(os.path.join(REPO, "..", "aigc-corpus")))
    ap.add_argument("--holdout2", default=os.path.join(os.path.abspath(os.path.join(REPO, "..", "aigc-corpus")),
                                                       "holdout2", "segments.jsonl"))
    ap.add_argument("--cap-chars", dest="cap_chars", type=int, default=1200000)
    ap.add_argument("--cap-units", dest="cap_units", type=int, default=200000)
    ap.add_argument("--h2-papers", dest="h2_papers", type=int, default=120)
    ap.add_argument("--h2-units", dest="h2_units", type=int, default=4000)
    ap.add_argument("--paired-units", dest="paired_units", type=int, default=1500)
    ap.add_argument("--slow-units", dest="slow_units", type=int, default=4000)
    ap.add_argument("--audit-dump", dest="audit_dump", default=os.path.join(ART, "audit-scored-sentences.tsv"),
                    help="出厂审计逐句导出的落点（跑 AigcFeatureAuditRegression 时设 WORDLITE_AIGC_AUDIT_DUMP 生成）")
    ap.add_argument("--discount", type=float, default=1.0)
    ap.add_argument("--maga", default=os.path.join(os.path.abspath(os.path.join(REPO, "..", "aigc-corpus")),
                                                   "mgb_cn_train.jsonl"))
    ap.add_argument("--maga-domain", dest="maga_domain", default="CSL")
    ap.add_argument("--maga-focal", dest="maga_focal", default="DeepSeek-V3")
    ap.add_argument("--maga-per-model", dest="maga_per_model", type=int, default=220)
    ap.add_argument("--maga-human-units", dest="maga_human_units", type=int, default=1200)
    ap.add_argument("--maga-lm-chars", dest="maga_lm_chars", type=int, default=600000)
    ap.add_argument("--maga-stride", dest="maga_stride", type=int, default=1)
    ap.add_argument("--fit-side-cap", dest="fit_side_cap", type=int, default=60000)
    ap.add_argument("--fit-eval-cap", dest="fit_eval_cap", type=int, default=12000)
    ap.add_argument("--C", type=float, default=1.0)
    ap.add_argument("--min-df", dest="min_df", type=int, default=10)
    ap.add_argument("--ngram-min", dest="ngram_min", type=int, default=2)
    ap.add_argument("--ngram-max", dest="ngram_max", type=int, default=4)
    a = ap.parse_args()
    if a.stage == "fit":
        stage_fit(a)
    else:
        stage_sig(a)


# ---------------------------------------------------------------- MAGA-cn：同一台生成器的上限

MAGA_LICENSE = ("MIT（数据集卡声明；原文不进仓库，只读不训真稿留出）；"
                "取回命令见 tools/build-aigc-model.py 的 CORPORA 里 magazh/magaaug 两行： "
                "py tools/build-aigc-model.py fetch（默认代理 http://127.0.0.1:7897），"
                "落盘 ../aigc-corpus/mgb_cn_train.jsonl 与 ../aigc-corpus/maga_cn_train.jsonl")


def read_maga(path, norm, tm, domain, folds, eval_fold):
    """按 human_source_id 分组：同一条人类原文与它的 12 台机器版本一定落在同一折。"""
    docs = {}
    for line in io.open(path, encoding="utf-8"):
        r = json.loads(line)
        dom = str(r.get("domain") or "")
        if domain != "all" and dom != domain:
            continue
        gid = str(r.get("human_source_id") or r.get("id"))
        box = docs.get(gid)
        if box is None:
            box = docs[gid] = dict(fold=int(hashlib.md5(gid.encode("utf-8")).hexdigest(), 16) % folds,
                                   domain=dom, human=[], mac={})
        mdl = str(r.get("model"))
        txt = str(r.get("text") or "")
        if mdl == "human":
            box["human"].append(txt)
        else:
            box["mac"].setdefault(mdl, []).append(txt)
    return docs


def sents(texts, norm, tm, cap=0, stride=1):
    out, k = [], 0
    for t in texts:
        for s in tm.split_sentences(t):
            k += 1
            if k % stride:
                continue
            c = norm.compact(s)
            if tm.scoreable(c):
                out.append(c)
                if cap and len(out) >= cap:
                    return out
    return out


def stage_maga(args, tm, norm, lms, out_json):
    path = os.path.join(args.data, os.path.basename(args.maga))
    if not os.path.exists(path):
        print("MAGA 文件不在（%s）：这一档不量" % path)
        return
    docs = read_maga(path, norm, tm, args.maga_domain, 10, 0)
    models = sorted(set(m for d in docs.values() for m in d["mac"]))
    ev = dict((k, v) for k, v in docs.items() if v["fold"] == 0)
    tr = dict((k, v) for k, v in docs.items() if v["fold"] != 0)
    print("MAGA[%s] 题 %d（留出折 %d 题）/ 机器台数 %d：%s" %
          (args.maga_domain, len(docs), len(ev), len(models), " ".join(models)))
    pairs = sum(len(v["mac"]) and sum(len(x) for x in v["mac"].values()) for v in docs.values())
    ahm = NGramLM(3, args.discount, name="MAGA 同域真人").train(
        sents([t for v in tr.values() for t in v["human"]], norm, tm, cap=0, stride=args.maga_stride))
    focal = args.maga_focal if args.maga_focal in models else models[-1]
    amf = NGramLM(3, args.discount, name="MAGA 机器/同一台").train(
        sents([t for v in tr.values() for m, ts in v["mac"].items() if m == focal for t in ts],
              norm, tm, cap=args.maga_lm_chars, stride=args.maga_stride))
    amo = NGramLM(3, args.discount, name="MAGA 机器/其余各台").train(
        sents([t for v in tr.values() for m, ts in v["mac"].items() if m != focal for t in ts],
              norm, tm, cap=args.maga_lm_chars, stride=args.maga_stride))
    print("  焦点生成器=%s；三个模型字数 %d / %d / %d" %
          (focal, ahm.train_chars, amf.train_chars, amo.train_chars))

    hum = []
    for gid in sorted(ev):
        hum.extend([(x, gid, "eval") for x in sents(ev[gid]["human"], norm, tm,
                                                   cap=args.maga_human_units, stride=args.maga_stride)])
        if len(hum) >= args.maga_human_units:
            break
    hum = hum[:args.maga_human_units]
    hum_v = [bits_of(ahm, c) for c, _g, _t in hum]
    hum_g = [bits_of(lms["gh3"], c) for c, _g, _t in hum]
    rows = []
    for m in models:
        ms, seen_src = [], []
        for gid in sorted(ev):
            ms.extend(sents(ev[gid]["mac"].get(m, []), norm, tm, cap=args.maga_per_model, stride=args.maga_stride))
            if len(ms) >= args.maga_per_model:
                break
        for gid in sorted(tr):
            seen_src.extend(sents(tr[gid]["mac"].get(m, []), norm, tm,
                                  cap=max(80, args.maga_per_model // 2), stride=args.maga_stride))
            if len(seen_src) >= max(80, args.maga_per_model // 2):
                break
        for tag, pool, seen in (("换生成器+换题", ms, False), ("同题+同一台生成器（上限）", seen_src, True)):
            if len(pool) < 40:
                continue
            b_amf = [bits_of(amf, x) for x in pool]
            b_amo = [bits_of(amo, x) for x in pool]
            b_ahm = [bits_of(ahm, x) for x in pool]
            b_gh = [bits_of(lms["gh3"], x) for x in pool]
            b_ship = [shipped_bits(read_shipped_cached(), x) for x in pool]
            one = dict(model=m, mode=tag, source_seen=seen, n_machine=len(pool), n_human=len(hum),
                       auc_shipped_bits=round(auc_machine_gt_human(hum_v, b_ship, -1), 4),
                       auc_bits_ahm=round(auc_machine_gt_human(hum_v, b_ahm, -1), 4),
                       auc_dom_gh_minus_ahm=round(auc_machine_gt_human(
                           [a - b for a, b in zip(hum_g, hum_v)], [a - b for a, b in zip(b_gh, b_ahm)], -1), 4),
                       auc_contrast_same_generator=round(auc_machine_gt_human(hum_v, b_amf, -1), 4),
                       auc_contrast_other_generators=round(auc_machine_gt_human(hum_v, b_amo, -1), 4))
            rows.append(one)
            print("  MAGA %-26s %-14s n=%d/%d  随包bits=%.4f  同域bits=%.4f  域差(通用-同域)=%.4f  "
                  "对比(同一台)=%.4f  对比(其余各台)=%.4f" %
                  (m, tag, one["n_machine"], len(hum), one["auc_shipped_bits"], one["auc_bits_ahm"],
                   one["auc_dom_gh_minus_ahm"], one["auc_contrast_same_generator"],
                   one["auc_contrast_other_generators"]))
    out_json["maga"] = dict(file=os.path.basename(path), license=MAGA_LICENSE, domain=args.maga_domain,
                            focal_generator=focal, models=models, pairs=pairs, docs=len(docs),
                            eval_docs=len(ev), rows=rows,
                            note="同一台生成器那一档是上限，不是能力：机器稿的生成器进过对比模型，"
                                 "换一台生成器还剩多少看另一半行")
    return [c for c, _g, _t in hum]


_SHIP = {}


def read_shipped_cached():
    if not _SHIP:
        _SHIP.update(read_shipped())
    return _SHIP
if __name__ == '__main__':
    main()
