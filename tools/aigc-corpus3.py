#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""holdout3：把段落级的真人侧分母做到 2 万段以上，机器段由同一批原文改写（另开一台跑）。

为什么还要再抓一轮：上一轮（docs/aigc-paragraph-scale.md）真人误报的分母只有 4,961 段，
95% 区间 0.96~3.44/千段，量不出"<=2 段/千段"这件事。这一段只干一件事：把分母做大、来源做散，
并且逐篇记来源与许可。真人侧一个字都不许是模型生成的。

跑法（每一步都能中断续跑，缓存在仓库外 ../aigc-corpus/holdout3/）：
  py tools/aigc-corpus3.py ids    --family pmc --lang zh
  py tools/aigc-corpus3.py fetch  --family pmc --lang zh
  py tools/aigc-corpus3.py build  --family pmc --lang zh      # 逐篇核许可 + 切段 + 去重 + 每篇封顶
  py tools/aigc-corpus3.py report                             # 各族实抓/筛后/按篇三分的实数，清单落仓库

纪律：
* 原文不进仓库：全部留在 ../aigc-corpus/holdout3/<族>/units.jsonl；仓库里进的是逐篇来源与许可清单
  tests/corpus/aigc-holdout3-<族>.jsonl（URL、许可字段原文截取、抓取时间、该篇计分段数）与一份总清单。
* 许可逐篇核：拿不到明确开放许可（或写明不得转载）的整篇丢弃，许可原文前 160 字进清单。
* 切段口径与 tools/aigc-para-probe.py 完全一致：句号级边界贪心打包到 300 字上下，
  160~520 字才算一段；汉字两侧的空格在算特征之前就抹掉（PDF 断字痕迹），长度一律数非空字符。
* 按篇三分：FIT（定阈值）/ HOLD（只量真人误报）/ SPARE（备用样本位）。
  一篇只能落进一个档，FIT 里的篇目一次都不参与量误报。
  机器段不再现造：正例只用仓库外已缓存的公开数据集那几档（DetectRL-X / RealDet / HC3），
  本机不装也不下任何模型权重（2026-10-09 定）。
"""
import argparse, gzip, hashlib, io, json, os, re, sys, time
import math
import threading
import urllib.error, urllib.parse, urllib.request
from concurrent.futures import ThreadPoolExecutor
from collections import Counter
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
DATA = os.path.abspath(os.path.join(REPO, "..", "aigc-corpus", "holdout3"))
MAN_DIR = os.path.join(REPO, "tests", "corpus")
UA = "WordliteAigc/1.0 (mailto:wordlite.dev@example.com)"
PROXY_DEFAULT = os.environ.get("HTTPS_PROXY") or os.environ.get("HTTP_PROXY") or ""

FLOOR, TARGET, CEIL, TOL = 160, 300, 520, 90    # 与 tools/aigc-para-probe.py 同一把尺
BANDS = [(120, 199), (200, 319), (320, 479), (480, 656)]
ACCEPTED = ("CC BY", "CC BY-SA", "CC BY-NC", "CC BY-NC-SA", "CC BY-NC-ND",
            "CC0", "公有领域", "官方文件", "GFDL", "CC 其他档")
SHINGLE, SHINGLE_STRIDE, NEAR_DUP_HITS = 24, 12, 2

CJK = "\u4e00-\u9fff\u3400-\u4dbf"
SP_CJK = re.compile(r"(?<=[" + CJK + r"]) +| +(?=[" + CJK + r"])")
WS = re.compile(r"[ \t\u3000\u00a0\u2007\u202f]+")
RX_NS = re.compile(r"\s+")
SENT_END = "\u3002\uff01\uff1f\uff1b!?;\u2026"     # 句号级边界，与探针台一致
# ---------------------------------------------------------------- 口径与工具

def norm(text):
    """汉字两侧的空格先抹掉：那是 PDF 断字/抽取痕迹，不是行文。与探针台同一个函数口径。"""
    return SP_CJK.sub("", WS.sub(" ", text or "")).strip()


def len_ns(s):
    return len(RX_NS.sub("", s))


def band_of(n):
    for i, (lo, hi) in enumerate(BANDS):
        if lo <= n <= hi:
            return i
    return -1


# 英文侧的句边界不能只数句号级的符号：SENT_END 里没有 "."（那是给中文用的），
# 直接套会让整段变成一个"句子"，全被"不足两句"剔掉——英文第一版就是这么只出得 4,617 段。
EN_ABBREV = ("e.g", "i.e", "etc", "vs", "viz", "cf", "approx", "et al", "no", "nos", "dr", "prof",
             "mr", "mrs", "ms", "st", "fig", "figs", "ref", "refs", "sec", "vol", "vols", "eds",
             "trans", "pp", "inc", "ltd", "al", "dept", "univ", "resp", "misc")
NUL = "\u0000"
# 缩写后面那个点不能切句。这里必须按词边界匹：上一版用子串替换，结果 "observed."、"reported."
# 这种以 ed. 结尾的普通句子末尾全被保护起来，整段变成一个句子，英文侧只剩 5 段。
EN_ABBREV_RE = re.compile(r"(?<![A-Za-z])(?:" + "|".join(re.escape(a) for a in EN_ABBREV) + r")(\.)", re.I)


def split_sents(text, lang="zh"):
    return en_sentences(text) if lang != "zh" else sentences(text)


def en_sentences(text):
    t = re.sub(r"(?<=\d)\.(?=\d)", NUL, text)                       # 小数点：0.5 不是句末
    t = EN_ABBREV_RE.sub(lambda m: m.group(0)[:-1] + NUL, t)
    t = re.sub(r"(?<=\b[A-Z])\.(?=\s)", NUL, t)                     # 首字母缩写：J. Smith
    t = re.sub(r"(?<=\b[A-Z])\.(?=[A-Z])", NUL, t)
    parts = re.split(r"""(?<=[.!?;\u3002\uff01\uff1f])["')\]\u300d\u300f]*[ \t\r\n]+""", t)
    return [p.replace(NUL, ".").strip() for p in parts if p.strip()]


def sentences(text):
    out, cur = [], []
    for ch in text:
        if ch == "\n":
            if cur:
                out.append("".join(cur)); cur = []
            continue
        cur.append(ch)
        if ch in SENT_END:
            out.append("".join(cur)); cur = []
    if cur:
        out.append("".join(cur))
    return [s.strip() for s in out if s.strip()]


def pack(text, cap=12, lang="zh"):
    """句号级边界贪心打包到 TARGET 上下，160~520 字才算一段；一篇最多取 cap 段。拉丁族句子之间要补一个空格，否则拼回去的段再也切不开句（上一版英文只剩 5 段就是这个）。"""
    out, cur = [], ""
    for s in (split_sents(norm(text), lang) if lang != "zh" else sentences(norm(text))):
        n = len_ns(s)
        if not n:
            continue
        if not cur:
            cur = s
            continue
        l = len_ns(cur)
        if l < TARGET and l + n <= CEIL:
            cur = cur + ("" if lang == "zh" else " ") + s
        elif l < TARGET + TOL and l + n <= CEIL:
            cur = cur + ("" if lang == "zh" else " ") + s
        else:
            out.append(cur); cur = s
        if len(out) >= cap:
            break
    if cur and len(out) < cap:
        out.append(cur)
    return [c for c in out if FLOOR <= len_ns(c) <= CEIL]


def shingles(compact, k=SHINGLE, stride=SHINGLE_STRIDE):
    return set(compact[i:i + k] for i in range(0, max(0, len(compact) - k + 1), stride))


def cjk_ratio(t):
    n = len_ns(t)
    return (len(re.findall("[" + CJK + "]", t)) / n) if n else 0.0


def latin_ratio(t):
    n = len_ns(t)
    return (len(re.findall("[A-Za-z]", t)) / n) if n else 0.0


def reject_reason(text, lang):
    """段不合格的原因；None 是合格。目的是把题注、题录、表格残片、条目堆叠、链接这类剔掉。"""
    n = len_ns(text)
    if n < FLOOR:
        return "短于 %d 字" % FLOOR
    if n > CEIL:
        return "长于 %d 字" % CEIL
    low = text.lower()
    if "http://" in low or "https://" in low or "doi.org" in low or "www." in low:
        return "含链接"
    if re.match(r"^(关键词|keywords?|中图分类号|文献标志码|doi[:：]|abstract|摘要|references?|参考文献|"
                r"表\s*\d|图\s*\d|table\s*\d|figure\s*\d|note[:：]|注[:：])", text, re.I):
        return "题注/题录/声明开头"
    if re.search(r"(未经授权.{0,12}不得转载|保留所有权利|all rights reserved|版权所有|利益冲突声明)", text, re.I):
        return "版权或利益冲突声明"
    digits = len(re.findall(r"[0-9\uFF10-\uff19]", text))
    if digits > 0.30 * n:
        return "数字占比过高"
    nsent = len(split_sents(text, lang))
    if nsent < 2:
        return "不足两句"
    if text.count("\uff1b") > 12:
        return "像条目堆叠"
    if lang == "zh":
        if cjk_ratio(text) < 0.45:
            return "汉字占比低于 45%"
        if len(re.findall("[" + CJK + "]", text)) < 60:
            return "汉字不足 60 个"
    else:
        words = re.findall(r"[A-Za-z]+(?:['-][A-Za-z]+)*", text)
        if latin_ratio(text) < 0.55:
            return "拉丁字母占比低于 55%"
        if len(words) < 45:
            return "英文词数不足 45"
        if len(words) > 0 and sum(1 for w in words if w.isupper() and len(w) > 2) > 0.12 * len(words):
            return "全大写占比过高（像标题或表头）"
    return None


def norm_license(s):
    """把各家写法收敛到 ACCEPTED 那几档：CC BY-SA 4.0 / by-sa 3.0 是同一档，不能因为带版本号就整篇丢。"""
    t = re.sub(r"\s+", " ", (s or "").upper().replace("\u2013", "-").replace("\u2014", "-")).strip()
    if not t:
        return "NONE"
    if "BY-NC-ND" in t:
        return "CC BY-NC-ND"
    if "BY-NC-SA" in t:
        return "CC BY-NC-SA"
    if "BY-SA" in t or "SHARE ALIKE" in t:
        return "CC BY-SA"
    if "BY-NC" in t:
        return "CC BY-NC"
    if t.startswith("CC BY") or t.startswith("CC-BY") or "ATTRIBUTION 4.0" in t or t == "CC BY 3.0":
        return "CC BY"
    if "PUBLIC DOMAIN" in t or "CC0" in t or t.startswith("PD"):
        return "公有领域"
    if "GFDL" in t:
        return "GFDL"
    if "ALL RIGHTS RESERVED" in t or "\u7248\u6743\u6240\u6709" in t:
        return "CLOSED"
    return t[:24]


def now():
    return time.strftime("%Y-%m-%dT%H:%M:%S")


def opener(proxy=None):
    px = proxy if proxy is not None else PROXY_DEFAULT
    if px:
        return urllib.request.build_opener(urllib.request.ProxyHandler({"http": px, "https": px}))
    return urllib.request.build_opener()


def fetch(url, h, tries=4, wait=2.0, timeout=90, headers=None):
    last = None
    for k in range(tries):
        try:
            hd = {"User-Agent": UA, "Accept-Encoding": "gzip",
                  "Accept": "text/html,application/xml,application/json;q=0.9,*/*;q=0.8"}
            hd.update(headers or {})
            req = urllib.request.Request(url, headers=hd)
            with h.open(req, timeout=timeout) as r:
                raw = r.read()
                if (r.headers.get("Content-Encoding") or "").lower() == "gzip":
                    raw = gzip.decompress(raw)
                return raw
        except urllib.error.HTTPError as e:
            last = e
            # 撞 429 就硬退：Wikimedia 这一族是共享出口限速，2 秒重试等于继续撞
            time.sleep((20.0 if e.code in (429, 403, 503) else wait) * (k + 1))
        except Exception as e:
            last = e
            time.sleep(wait * (k + 1))
    raise IOError("取不到 %s（%s: %s）" % (url[:110], type(last).__name__, last))

# ---------------------------------------------------------------- 族一：PMC 开放获取正文

EUTILS = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/"
PMC_TERM = {
    "zh": 'chinese[lang] AND "open access"[filter]',                       # 全年份：7,200 篇命中
    "en": 'english[lang] AND "open access"[filter] AND 2013:2022[pdat]',   # 抽样取，全档 417 万篇
}
PMC_EN_SAMPLE = 4200


def pmc_ids_en(h, d, redo=False):
    """英文侧按年分层取：eutils 的 history 只能稳定取回前一万条，深翻页会直接报错，
    所以一年一条检索式各取 1,000 条，再按稳定哈希抽 PMC_EN_SAMPLE 篇（不是取前 N 个命中）。"""
    out = os.path.join(d, "ids.json")
    if os.path.exists(out) and not redo:
        ids = json.load(io.open(out, encoding="utf-8"))
        print("已有 ID：%d 篇" % len(ids))
        return ids
    pool = []
    for yr in range(2013, 2023):
        term = 'english[lang] AND "open access"[filter] AND %d:%d[pdat]' % (yr, yr)
        got = []
        for start in (0, 500):
            u = EUTILS + "esearch.fcgi?" + urllib.parse.urlencode(
                {"db": "pmc", "term": term, "retmax": 500, "retstart": start, "retmode": "json"})
            res = None
            for _ in range(4):
                try:
                    res = json.loads(fetch(u, h).decode("utf-8", "replace"), strict=False)["esearchresult"]
                    break
                except Exception:
                    time.sleep(2.0)
            if not res:
                break
            got += res.get("idlist") or []
            time.sleep(0.4)
        print("  %d 年取回 %-5d" % (yr, len(got)), flush=True)
        pool += got
    pool = sorted(set(pool))
    pool.sort(key=lambda i: hashlib.md5(("pmc-en|" + i).encode("utf-8")).hexdigest())
    ids = pool[:PMC_EN_SAMPLE]
    json.dump(ids, io.open(out, "w", encoding="utf-8"), ensure_ascii=False)
    print("ID 落盘：%d 篇（按年池 %d 篇，稳定哈希抽样）" % (len(ids), len(pool)))
    return ids



def fam_dir(fam, lang):
    d = os.path.join(DATA, "%s_%s" % (fam, lang))
    os.makedirs(d, exist_ok=True)
    return d


def pmc_ids(h, d, lang, redo=False):
    out = os.path.join(d, "ids.json")
    if os.path.exists(out) and not redo:
        ids = json.load(io.open(out, encoding="utf-8"))
        print("已有 ID：%d 篇" % len(ids))
        return ids
    if lang == "en":
        return pmc_ids_en(h, d, redo)
    term = PMC_TERM[lang]
    ids, total = [], "0"
    for start in range(0, 20000, 500):
        u = EUTILS + "esearch.fcgi?" + urllib.parse.urlencode(
            {"db": "pmc", "term": term, "retmax": 500, "retstart": start, "usehistory": "y", "retmode": "json"})
        res = None
        for _ in range(4):
            try:
                res = json.loads(fetch(u, h).decode("utf-8", "replace"), strict=False)["esearchresult"]
                break
            except Exception:
                time.sleep(2.0)
        if not res:
            break
        total = res.get("count")
        got = res.get("idlist") or []
        if not got:
            break
        ids += got
        print("  esearch retstart=%-6d 取回 %-4d 累计 %-6d / 命中 %s" % (start, len(got), len(ids), total), flush=True)
        time.sleep(0.4)
        if lang == "en" and len(ids) >= 40000:
            break
    ids = sorted(set(ids))
    if lang == "en":
        ids.sort(key=lambda i: hashlib.md5(("pmc-en|" + i).encode()).hexdigest())
        ids = ids[:PMC_EN_SAMPLE]
        print("  英文侧按稳定哈希抽样取前 %d 篇（不是取前 N 个命中，避免按 PMCID 顺序偏科）" % len(ids))
    json.dump(ids, io.open(out, "w", encoding="utf-8"), ensure_ascii=False)
    print("ID 落盘：%d 篇（检索式命中 %s 篇）" % (len(ids), total))
    return ids


def pmc_xml(h, ids, xml_dir, redo=False):
    os.makedirs(xml_dir, exist_ok=True)
    todo = [k for k in range(0, len(ids), 100)
            if not (os.path.exists(os.path.join(xml_dir, "b%04d.xml" % (k // 100))) and not redo)]
    print("取全文 XML：还差 %d 批（每批 100 篇）" % len(todo))
    for n, k in enumerate(todo):
        u = EUTILS + "efetch.fcgi?" + urllib.parse.urlencode(
            {"db": "pmc", "id": ",".join(ids[k:k + 100]), "retmode": "xml"})
        raw = fetch(u, h, timeout=240)
        try:
            txt = gzip.decompress(raw).decode("utf-8", "replace")
        except OSError:
            txt = raw.decode("utf-8", "replace")
        io.open(os.path.join(xml_dir, "b%04d.xml" % (k // 100)), "w", encoding="utf-8", newline="").write(txt)
        if n % 5 == 0:
            print("  xml batch %-4d / %d" % (k // 100 + 1, len(todo)), flush=True)
        time.sleep(0.4)


SKIP_TAGS = ("ref-list", "table-wrap", "table-wrap-foot", "fig", "disp-formula", "disp-quote",
             "ack", "app", "app-group", "supplementary-material", "fn-group", "author-notes",
             "bio", "embedded", "media", "graphic", "label", "def-list", "front", "article-meta")


def local(e):
    return e.tag.split("}")[-1]


def flatten(e):
    out = []
    for ch in e.iter():
        if local(ch) == "xref" and (ch.get("ref-type") or "") in ("bibr", "table", "fig"):
            continue                      # 文内参考文献角标 [12] 不算正文
        if ch.text:
            out.append(ch.text)
        if ch.tail and ch is not e:
            out.append(ch.tail)
    return re.sub(r"\s+", " ", " ".join(out)).strip()


def license_of(art):
    bits = []
    for e in art.iter():
        t = local(e)
        if t in ("license_ref", "license-p", "copyright-statement", "copyright-notice"):
            bits.append(flatten(e))
    txt = " ;; ".join(x for x in bits if x)
    low = txt.lower().replace(" ", "")
    if "by-nc-nd" in low:
        return "CC BY-NC-ND", txt
    if "by-nc-sa" in low:
        return "CC BY-NC-SA", txt
    if "by-sa" in low:
        return "CC BY-SA", txt
    if "by-nc" in low:
        return "CC BY-NC", txt
    if "publicdomain" in low or "cc0" in low or "public domain" in txt.lower():
        return "公有领域", txt
    if "creativecommons.org/licenses/by/" in low or "ccby4" in low:
        return "CC BY", txt
    if "creativecommons.org" in low:
        return "CC 其他档", txt
    if re.search(r"未经授权.{0,12}不得转载|保留所有权利|all rights reserved|版权所有", txt, re.I):
        return "CLOSED", txt
    return "NONE", txt

def iter_articles(xml_dir):
    bad = 0
    for name in sorted(os.listdir(xml_dir)):
        if not name.endswith(".xml"):
            continue
        xml = io.open(os.path.join(xml_dir, name), encoding="utf-8").read()
        for chunk in re.split(r"(?=<article )", xml):
            if not chunk.startswith("<article "):
                continue
            end = chunk.rfind("</article>")
            if end < 0:
                continue
            try:
                yield ET.fromstring(chunk[:end + 10])
            except ET.ParseError:
                bad += 1
    if bad:
        print("XML 解析失败被跳过：%d 篇" % bad)


def art_field(art, want):
    for e in art.iter():
        if local(e) == want:
            return flatten(e)
    return ""


def body_sections(art):
    body = None
    for ch in art:
        if local(ch) == "body":
            body = ch
            break
    if body is None:
        return []
    out = []

    def walk(elem, sec):
        for ch in elem:
            t = local(ch)
            if t in SKIP_TAGS:
                continue
            if t == "p":
                txt = flatten(ch)
                if txt:
                    out.append((sec, txt))
            elif t == "sec":
                st = sec
                for g in ch:
                    if local(g) == "title":
                        st = flatten(g) or sec
                walk(ch, st)
            else:
                walk(ch, sec)
    walk(body, "")
    return out


def dedupe(units, seen_exact, seen_shingle, home):
    """跨篇去重：逐字相同（压缩后 SHA-1）与近重复（24 字长串撞上两处）。"""
    kept, dropped = [], Counter()
    for u in units:
        c = norm(u["text"]).replace(" ", "")
        h = hashlib.sha1(c.encode("utf-8")).hexdigest()
        if h in seen_exact:
            dropped["逐字重复"] += 1
            continue
        hits = Counter(seen_shingle.get(s) for s in shingles(c) if s in seen_shingle)
        top, cnt = (hits.most_common(1) or [(None, 0)])[0]
        if cnt >= NEAR_DUP_HITS:
            dropped["近重复（24 字长串撞 %d 处）" % cnt] += 1
            continue
        seen_exact[h] = "%s|%s" % (u["pid"], home)
        for s in shingles(c):
            seen_shingle.setdefault(s, "%s|%s" % (u["pid"], home))
        u["compact_sha1"] = h
        kept.append(u)
    return kept, dropped


def load_global_index(d, home):
    """去重账本落在 DATA 下（仓库外）：跨族、跨次运行都认同一本账。
    重建某一族时先把它自己上一遍那批条目剔掉，否则重跑会把自家段落全判成"逐字重复"。"""
    path = os.path.join(DATA, "_dedupe_index.json")
    if not os.path.exists(path):
        return {}, {}
    j = json.load(io.open(path, encoding="utf-8"))
    tail = "|" + home
    drop = lambda kv: not str(kv[1]).endswith(tail)
    exact = dict(kv for kv in (j.get("exact") or {}).items() if drop(kv))
    shingle = dict(kv for kv in (j.get("shingle") or {}).items() if drop(kv))
    return exact, shingle


def save_global_index(d, exact, shingle):
    json.dump({"exact": exact, "shingle": shingle, "built": now()},
              io.open(os.path.join(DATA, "_dedupe_index.json"), "w", encoding="utf-8", newline=""),
              ensure_ascii=False)


def stage_build(args):
    d = fam_dir(args.family, args.lang)
    lang, fam = args.lang, args.family
    home = "%s_%s" % (args.family, args.lang)
    exact, shidx = load_global_index(d, home)
    before_exact, before_sh = len(exact), len(shidx)
    papers, units, stats, reasons, lic_seen = [], [], Counter(), Counter(), Counter()
    for art in article_records(fam, lang, d):
        lic = art["license"]
        lic_seen[lic] += 1
        rec = {"pid": art["pid"], "family": fam, "lang": lang, "url": art["url"],
               "doi": art.get("doi"), "journal": art.get("journal"),
               "title": (art.get("title") or "")[:160], "year": art.get("year"),
               "license": lic, "license_raw": re.sub(r"\s+", " ", art.get("license_raw") or "")[:160],
               "fetched_at": art.get("fetched_at") or now(), "candidates": 0, "kept": 0}
        if lic not in ACCEPTED:
            stats["许可不过，整篇丢弃"] += 1
            papers.append(rec)
            continue
        stats["许可过的篇数"] += 1
        cand = []
        for order, (sec, txt) in enumerate(art["sections"]):
            for piece in pack(txt, cap=args.per_paper * 3, lang=lang):
                why = reject_reason(piece, lang)
                if why:
                    reasons[why] += 1
                    continue
                cand.append({"order": order, "section": sec or "", "text": piece,
                             "chars": len_ns(piece), "band": band_of(len_ns(piece))})
        stats["合用正文段（去重前）"] += len(cand)
        cand.sort(key=lambda c: abs(c["chars"] - TARGET))
        take, seen_sec = [], set()
        for c in cand:
            if len(take) >= args.per_paper:
                break
            key = c["section"] or "_"
            if key in seen_sec:
                continue
            seen_sec.add(key)
            take.append(c)
        reasons["同一篇封顶后丢掉"] += len(cand) - len(take)
        rec["candidates"], rec["kept"] = len(cand), len(take)
        papers.append(rec)
        for i, c in enumerate(take):
            u = dict(c)
            u.update(uid="%s-%s:%05d" % (fam, lang, len(units) + 1), pid=rec["pid"], family=fam, lang=lang,
                     url=rec["url"], license=lic, index=i)
            units.append(u)
    kept, dropped = dedupe(units, exact, shidx, home)
    stats["去重后剩余段"] = len(kept)
    reasons.update(dropped)
    keptpids = set(u["pid"] for u in kept)
    for rec in papers:
        rec["kept"] = sum(1 for u in kept if u["pid"] == rec["pid"])
    with io.open(os.path.join(d, "units.jsonl"), "w", encoding="utf-8", newline="") as f:
        for u in kept:
            f.write(json.dumps(u, ensure_ascii=False) + "\n")
    with io.open(os.path.join(d, "papers.jsonl"), "w", encoding="utf-8", newline="") as f:
        for rec in papers:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    save_global_index(d, exact, shidx)
    stats["去重账本新增"] = "%d 条精确 / %d 条长串" % (len(exact) - before_exact, len(shidx) - before_sh)
    json.dump(dict(counts=dict(stats), licenses=dict(lic_seen), dropped=dict(reasons)),
              io.open(os.path.join(d, "build-stats.json"), "w", encoding="utf-8", newline=""),
              ensure_ascii=False, indent=1)
    print("族 %s/%s：篇 %d（许可过 %d）｜候选段 %d｜去重后计分段 %d｜覆盖篇 %d"
          % (fam, lang, len(papers), stats["许可过的篇数"], stats["合用正文段（去重前）"], len(kept), len(keptpids)))
    print("  许可分布：%s" % json.dumps(dict(lic_seen), ensure_ascii=False))
    print("  丢弃原因：%s" % json.dumps(dict(reasons), ensure_ascii=False))

# ---------------------------------------------------------------- 各族统一的"一篇"形状

def pmc_records(d):
    for art in iter_articles(os.path.join(d, "xml")):
        ids = dict((e.get("pub-id-type") or "?", flatten(e)) for e in art.iter() if local(e) == "article-id")
        pmcid = ids.get("pmcid") or ("PMC" + (ids.get("pmcaid") or ""))
        if not pmcid.strip("PMC"):
            continue
        lic, lic_raw = license_of(art)
        yield {"pid": "pmc:" + pmcid, "url": "https://pmc.ncbi.nlm.nih.gov/articles/%s/" % pmcid,
               "doi": ids.get("doi"), "journal": art_field(art, "journal-title"),
               "title": art_field(art, "article-title"), "year": art_field(art, "year"),
               "license": lic, "license_raw": lic_raw, "fetched_at": now(),
               "sections": body_sections(art)}


def article_records(fam, lang, d):
    if fam == "pmc":
        return pmc_records(d)
    if fam == "wiki":
        return wiki_records(lang, d)
    if fam == "qa":
        return qa_records(lang, d)
    if fam == "books":
        return ws_records(lang, d) if lang == "zh" else pg_records(lang, d)
    raise SystemExit("这一族还没接：build %s" % fam)

# ---------------------------------------------------------------- 族二：维基百科条目正文
#
# 条目正文的许可不在正文里，在站点使用条款里：每个语言版 siteinfo 的 rightsinfo 给出授权文本，
# 逐篇再记一次"取的是哪一号修订、什么时间"，这就是这一族可审的许可凭据。

WIKI = {
    "zh": {"host": "zh.wikipedia.org", "cats": ["Category:典范条目", "Category:優良條目"], "limit": 4600,
           "skip": r"^(参见|参考资料|参考資料|外部链接|外部連結|注释|註釋|注脚|註腳|脚注|引用|文献|文献列表|"
                   r"进一步阅读|進一步閱讀|扩展阅读|擴展閱讀|另见|另見|衍生作品|作品列表|备注|獎項|奖项记录)\s*$"},
    "en": {"host": "en.wikipedia.org", "cats": ["Category:Featured articles", "Category:Good articles"], "limit": 5200,
           "skip": r"^(see also|references?|external links?|notes?|footnotes?|further reading|bibliography|"
                   r"citations?|works cited|sources?|notes and references|gallery|video|soundtrack)\b"},
}


def wiki_api(h, lang, params, tries=5):
    host = WIKI[lang]["host"]
    params = dict(params)
    params["format"] = "json"
    u = "https://%s/w/api.php?%s" % (host, urllib.parse.urlencode(params))
    last = None
    for k in range(tries):
        try:
            return json.loads(fetch(u, h, tries=2).decode("utf-8", "replace"), strict=False)
        except Exception as e:
            last = e
            time.sleep(1.5 * (k + 1))
    raise IOError("接口取不回：%s（%s）" % (u[:110], type(last).__name__))


def wiki_titles(h, lang, d, redo=False):
    out = os.path.join(d, "titles.json")
    if os.path.exists(out) and not redo:
        print("已有条目清单：%d 篇" % len(json.load(io.open(out, encoding="utf-8"))))
        return json.load(io.open(out, encoding="utf-8"))
    got = {}
    for cat in WIKI[lang]["cats"]:
        cont = None
        n = 0
        while True:
            p = {"action": "query", "list": "categorymembers", "cmtitle": cat,
                 "cmtype": "page", "cmlimit": 500}
            if cont:
                p["cmcontinue"] = cont
            j = wiki_api(h, lang, p)
            for m in j["query"]["categorymembers"]:
                got[str(m["pageid"])] = {"pageid": m["pageid"], "title": m["title"], "cat": cat}
            n += len(j["query"]["categorymembers"])
            cont = (j.get("continue") or {}).get("cmcontinue")
            time.sleep(0.3)
            if not cont:
                break
        print("  %-28s 累计条目 %d" % (cat, n), flush=True)
    rows = sorted(got.values(), key=lambda r: hashlib.md5(("wiki|%s|%s" % (lang, r["pageid"])).encode()).hexdigest())
    rows = rows[:WIKI[lang]["limit"]]
    json.dump(rows, io.open(out, "w", encoding="utf-8"), ensure_ascii=False)
    print("条目清单落盘：%d 篇（封顶 %d）" % (len(rows), WIKI[lang]["limit"]))
    return rows


def wiki_rights(h, lang, d):
    """授权原文只取一次，落在缓存里；逐篇清单引用它再配修订号。"""
    p = os.path.join(d, "siteinfo.json")
    if os.path.exists(p):
        return json.load(io.open(p, encoding="utf-8"))
    j = wiki_api(h, lang, {"action": "query", "meta": "siteinfo", "siprop": "rightsinfo"})
    ri = j["query"]["rightsinfo"]
    rec = {"license": "CC BY-SA", "text": (ri.get("text") or "").strip(), "url": (ri.get("url") or "").strip(),
           "fetched_at": now()}
    json.dump(rec, io.open(p, "w", encoding="utf-8"), ensure_ascii=False)
    return rec


def wiki_fetch(h, lang, d, redo=False, workers=12):
    """整篇正文只能一篇一次请求：TextExtracts 不给 exlimit>1 的全文（一次回 20 篇时它只填第一篇，
    上一版 4,600 篇因此只落回 230 篇）。这里改成一篇一请求 + 4 个 worker，断点续跑。"""
    from concurrent.futures import ThreadPoolExecutor
    rows = json.load(io.open(os.path.join(d, "titles.json"), encoding="utf-8"))
    rights = wiki_rights(h, lang, d)
    out = os.path.join(d, "extracts.jsonl")
    have = set()
    if os.path.exists(out) and not redo:
        for line in io.open(out, encoding="utf-8"):
            have.add(json.loads(line)["pageid"])
    todo = [r for r in rows if r["pageid"] not in have]
    print("取条目正文：还差 %d 篇（一篇一请求，%d 个 worker）" % (len(todo), workers), flush=True)
    lock = threading.Lock()
    done = [0]
    f = io.open(out, "a" if have else "w", encoding="utf-8", newline="")

    def one(r):
        hh = opener(PROXY_DEFAULT)
        try:
            j = wiki_api(hh, lang, {"action": "query", "prop": "extracts|revisions", "explaintext": 1,
                                    "exsectionformat": "plain", "exlimit": 1, "rvprop": "ids|timestamp",
                                    "rvslots": "main", "pageids": r["pageid"]})
        except Exception as e:
            with lock:
                done[0] += 1
            return
        pages = (j.get("query") or {}).get("pages") or {}
        p = pages.get(str(r["pageid"])) or {}
        rev = (p.get("revisions") or [{}])[0]
        ex = (p.get("extract") or "").strip()
        with lock:
            done[0] += 1
            if ex:
                f.write(json.dumps({"pageid": r["pageid"], "title": r["title"], "cat": r["cat"],
                                    "extract": ex, "revid": rev.get("revid"), "revid_ts": rev.get("timestamp"),
                                    "fetched_at": now(), "license": rights["license"],
                                    "license_raw": "%s（%s）；本条取修订 %s（%s）"
                                                   % (rights["text"], rights["url"], rev.get("revid"),
                                                      rev.get("timestamp"))}, ensure_ascii=False) + "\n")
            if done[0] % 200 == 0:
                f.flush()
                print("  extracts 已试 %-6d / %d（写入 %d）" % (done[0], len(todo), _lines(out)), flush=True)
        time.sleep(0.05)

    try:
        with ThreadPoolExecutor(max_workers=workers) as ex:
            list(ex.map(one, todo))
    finally:
        f.close()
    print("条目正文落盘：%d 篇" % _lines(out))


def _lines(path):
    return sum(1 for _ in io.open(path, encoding="utf-8")) if os.path.exists(path) else 0


SEC_HEAD = re.compile(r"^(={2,6})\s*(.+?)\s*=+\s*$")


def wiki_sections(extract, skip_re):
    """纯文本正文按 == 小节标题切开；参考资料/外部链接那几节整节不要。"""
    secs, cur, buf = [], "", []
    for line in extract.split("\n"):
        m = SEC_HEAD.match(line.strip())
        if m:
            if buf and not skip_re.match(cur or ""):
                secs.append((cur, "\n".join(buf).strip()))
            cur, buf = m.group(2).strip(), []
        elif line.strip():
            buf.append(line.strip())
    if buf and not skip_re.match(cur or ""):
        secs.append((cur, "\n".join(buf).strip()))
    return secs


def wiki_records(lang, d):
    skip_re = re.compile(WIKI[lang]["skip"], re.I)
    host = WIKI[lang]["host"]
    for line in io.open(os.path.join(d, "extracts.jsonl"), encoding="utf-8"):
        r = json.loads(line)
        yield {"pid": "wiki:%s:%s" % (lang, r["pageid"]),
               "url": "https://%s/wiki/%s" % (host, urllib.parse.quote(r["title"].replace(" ", "_"))),
               "doi": None, "journal": "Wikipedia 条目（%s）" % r["cat"], "title": r["title"],
               "year": (r.get("revid_ts") or "")[:4], "license": r["license"], "license_raw": r["license_raw"],
               "fetched_at": r["fetched_at"], "sections": wiki_sections(r["extract"], skip_re)}


# ---------------------------------------------------------------- 族三：问答（Stack Exchange）
#
# 挑站子挑的是"散文多、代码少"：academia/history/philosophy/english/literature 这些站的长答案
# 是要量的人写散文，stackoverflow 那种代码答案不要。许可逐条取 API 回的 content_license，
# 再把作者与永久链接一起记下——CC BY-SA 的署名要求本来就欠这两样。

QA_SITES = {
    "en": [("academia", 5, 700), ("history", 40, 700), ("philosophy", 30, 600),
           ("english", 30, 500), ("literature", 20, 400), ("worldbuilding", 40, 400),
           ("travel", 30, 300), ("politics", 40, 300)],
    "zh": [("chinese", 2, 1200)],
}
TAG_LINE = re.compile(r"(?is)<[^>]+>")


def qa_api(h, params, path="answers"):
    u = "https://api.stackexchange.com/2.3/%s?%s" % (path, urllib.parse.urlencode(params))
    last = None
    for k in range(5):
        try:
            j = json.loads(fetch(u, h, tries=2).decode("utf-8", "replace"), strict=False)
            if j.get("error_id"):                       # 接口自己说错了：退避再看
                time.sleep(2.0 * (k + 1))
                continue
            return j
        except Exception as e:
            last = e
            time.sleep(1.5 * (k + 1))
    raise IOError("Stack Exchange 取不回：%s（%s）" % (u[:110], type(last).__name__))


def qa_answers(h, lang, d, redo=False):
    out = os.path.join(d, "answers.jsonl")
    have = set()
    if os.path.exists(out) and not redo:
        for line in io.open(out, encoding="utf-8"):
            have.add(json.loads(line)["answer_id"])
    with io.open(out, "a" if have else "w", encoding="utf-8", newline="") as f:
        for site, minscore, cap in QA_SITES[lang]:
            got = sum(1 for l in io.open(out, encoding="utf-8")
                      if json.loads(l)["site"] == site) if have else 0
            page = 1
            while got < cap and page <= 12:
                j = qa_api(h, {"site": site, "sort": "votes", "order": "desc", "filter": "withbody",
                               "minscore": minscore, "pagesize": 100, "page": page})
                items = j.get("items") or []
                if not items:
                    break
                for it in items:
                    own = it.get("owner") or {}
                    f.write(json.dumps({
                        "answer_id": it.get("answer_id"), "site": site, "question_id": it.get("question_id"),
                        "score": it.get("score"), "is_accepted": bool(it.get("is_accepted")),
                        "created": time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(it.get("creation_date") or 0)),
                        "link": it.get("link"), "body": it.get("body") or "",
                        "license": (it.get("content_license") or "").strip(),
                        "author": own.get("display_name"), "author_id": own.get("user_id"),
                        "fetched_at": now()}, ensure_ascii=False) + "\n")
                f.flush()
                got += len(items)
                print("  %-14s 第 %d 页取回 %-4d 累计 %-5d（门槛 %d 分，封顶 %d）"
                      % (site, page, len(items), got, minscore, cap), flush=True)
                page += 1
                time.sleep(0.35)
    print("答案缓存：%d 条" % sum(1 for _ in io.open(out, encoding="utf-8")))


def html_paragraphs(html):
    """答案 HTML 转正文：代码块、脚本整块去掉，段落与列表项换行。"""
    import html as _html
    t = re.sub(r"(?is)<(pre|script|style|kbd)[^>]*>.*?</>", " ", html or "")
    t = re.sub(r"(?is)<code[^>]*>.*?</code>", " ", t)
    t = re.sub(r"(?is)<br\s*/?>", "\n", t)
    t = re.sub(r"(?is)</(p|li|div|h[1-6]|blockquote|tr)>", "\n\n", t)
    t = re.sub(r"(?is)<li[^>]*>", "\u00b7 ", t)
    t = _html.unescape(TAG_LINE.sub(" ", t))
    return " ".join(t.split())


def qa_records(lang, d):
    for line in io.open(os.path.join(d, "answers.jsonl"), encoding="utf-8"):
        r = json.loads(line)
        txt = html_paragraphs(r.get("body"))
        if not txt:
            continue
        lic = (r.get("license") or "").strip()
        yield {"pid": "qa:%s:%s" % (r["site"], r["answer_id"]), "url": r.get("link"),
               "doi": None, "journal": "StackExchange/%s" % r["site"], "title": None,
               "year": (r.get("created") or "")[:4],
               "license": norm_license(lic),
               "license_raw": ("content_license=%s；本条取 %s 站答案 %s（作者 %s / id %s，%s 年，采纳=%s，%d 分）%s"
                               % (lic or "空", r["site"], r["answer_id"], r.get("author"), r.get("author_id"),
                                  (r.get("created") or "")[:4], r.get("is_accepted"), r.get("score") or 0,
                                  r.get("link") or "")),
               "fetched_at": r.get("fetched_at"), "sections": [("答案", txt)]}


# ---------------------------------------------------------------- 族四：书籍（公版正文）
#
# 中文取维基文库（zh.wikisource）里已经标了公版模板的作品，英文取 Project Gutenberg。
# 两边的许可都从作品页自己写的东西里读：维基文库读页面顶部的 PD 模板原文，
#古登堡读 txt 文件头那一句"这本书任何人都可以使用"。带 Copyright/Non-PD 标记的一律丢。

WS_HOST = "zh.wikisource.org"
WS_LISTS = ["Category:鲁迅", "Category:老舍", "Category:朱自清", "Category:郁达夫", "Category:萧红",
            "Category:许地山", "Category:庐隐", "Category:李劼人", "Category:废名", "Category:徐志摩",
            "Category:闻一多", "Category:张恨水", "Category:柔石", "Category:洪深", "Category:田汉",
            "Category:穆时英", "Category:殷夫", "Category:蒋光慈", "Category:王鲁彦", "Category:靳以",
            "Category:PD-1923", "Category:PD-1996"]
# 作者一律取逝于 1975 年及以前的（中国法人作品以外是作者身后 50 年进公版）；
# 名单只是撒网，真正的门是每篇页面顶上的 PD 模板，缺模板或带 Copyright 标记的一律丢。
WS_LIMIT = 900
WS_OK_TPL = ("PD-author", "PD-ZH", "PD-old", "PD-China", "PD-1996", "PD-textarticle", "PD-art",
             "Public domain", "PD-Show", "PD-Chess")
WS_NO_TPL = ("Non-PD", "CopyrightReview", "Copyrighted", "Permission", "FOP", "DERIVATIVE",
             "To be reviewed", "Copyvios", "Close copyright review request")


def ws_api(h, params):
    u = "https://%s/w/api.php?%s" % (WS_HOST, urllib.parse.urlencode(params))
    last = None
    for k in range(4):
        try:
            return json.loads(fetch(u, h, tries=2, timeout=60).decode("utf-8", "replace"), strict=False)
        except Exception as e:
            last = e
            time.sleep(1.5 * (k + 1))
    raise IOError("维基文库取不回：%s（%s）" % (u[:110], type(last).__name__))


def ws_titles(h, d, redo=False):
    out = os.path.join(d, "titles.json")
    if os.path.exists(out) and not redo:
        return json.load(io.open(out, encoding="utf-8"))
    got = {}
    for cat in WS_LISTS:
        cont = None
        n = 0
        while True:
            p = {"action": "query", "list": "categorymembers", "cmtitle": cat, "cmtype": "page",
                 "cmlimit": 300, "converttitles": 1}
            if cont:
                p["cmcontinue"] = cont
            j = ws_api(h, p)
            ms = (j.get("query") or {}).get("categorymembers") or []
            for m in ms:
                t = m["title"]
                if t.startswith(("作者:", "Author:", "Help:", "Wikisource:", "维基文库:")) or ":" in t[1:]:
                    continue
                got[t] = {"title": t, "pageid": m["pageid"], "cat": cat}
            n += len(ms)
            cont = (j.get("continue") or {}).get("cmcontinue")
            time.sleep(0.3)
            if not cont:
                break
        print("  %-22s 累计作品 %d" % (cat, n), flush=True)
    rows = sorted(got.values(), key=lambda r: hashlib.md5(("ws|" + r["title"]).encode("utf-8")).hexdigest())
    rows = rows[:WS_LIMIT]
    json.dump(rows, io.open(out, "w", encoding="utf-8"), ensure_ascii=False)
    print("作品清单落盘：%d 篇" % len(rows))
    return rows


def ws_fetch(h, d, redo=False):
    rows = json.load(io.open(os.path.join(d, "titles.json"), encoding="utf-8"))
    out = os.path.join(d, "wikitext.jsonl")
    have = set()
    if os.path.exists(out) and not redo:
        for line in io.open(out, encoding="utf-8"):
            have.add(json.loads(line)["title"])
    todo = [r for r in rows if r["title"] not in have]
    print("取 wikitext：还差 %d 篇（一次 20 篇）" % len(todo), flush=True)
    with io.open(out, "a" if have else "w", encoding="utf-8", newline="") as f:
        for k in range(0, len(todo), 20):
            batch = todo[k:k + 20]
            j = ws_api(h, {"action": "query", "prop": "revisions", "rvprop": "content|timestamp|ids",
                           "rvslots": "main", "rvlimit": 1, "converttitles": 1, "formatversion": 2,
                           "titles": "|".join(r["title"] for r in batch)})
            pages = (j.get("query") or {}).get("pages") or []
            by = dict((p.get("title"), p) for p in pages)
            for r in batch:
                p = by.get(r["title"]) or {}
                rev = (p.get("revisions") or [{}])[0]
                wt = ((rev.get("slots") or {}).get("main") or {}).get("content") or ""
                if not wt:
                    continue
                f.write(json.dumps({"title": r["title"], "cat": r["cat"], "pageid": p.get("pageid"),
                                    "wikitext": wt, "revid": rev.get("revid"), "rev_ts": rev.get("timestamp"),
                                    "fetched_at": now()}, ensure_ascii=False) + "\n")
            f.flush()
            if k % 400 == 0:
                print("  wikitext %-6d / %d" % (k + len(batch), len(todo)), flush=True)
            time.sleep(0.25)


TPL_STRIP = [(r"(?s)\{\{\s*(?:ref|note|footnote|NoteTag)\b.*?\}\}", " ")]


def wikitext_to_text(wt):
    t = wt
    t = re.sub(r"(?s)<ref[^>]*/>", " ", t)
    t = re.sub(r"(?s)<ref[^>]*>.*?</ref>", " ", t)
    t = re.sub(r"(?s)<!--.*?-->", " ", t)
    t = re.sub(r"(?s)\{\|.*?\|\}", " ", t)                       # 表格
    t = re.sub(r"(?s)<(noinclude|includeonly|inputbox|div|gallery|imagemap|score|timeline)[^>]*>.*?</\1>", " ", t)
    for _ in range(8):
        t2 = re.sub(r"\{\{[^{}]*\}\}", " ", t)
        if t2 == t:
            break
        t = t2
    t = re.sub(r"(?s)\{\{.*?\}\}", " ", t)
    t = re.sub(r"(?i)\[\[\s*(?:Image|File|图像|文件|Category|分类|Wikidata)\s*:[^\]]*\]\]", " ", t)
    t = re.sub(r"\[\[[^\]|]*\|([^\]]*)\]\]", r"\1", t)
    t = re.sub(r"\[\[([^\]]*)\]\]", r"\1", t)
    t = re.sub(r"\[\s*https?://[^\s\]]+\s+([^\]]+)\]", r"\1", t)
    t = re.sub(r"https?://\S+", " ", t)
    t = re.sub(r"'+", "", t)
    t = re.sub(r"(?m)^\s*[#!*:;]+", "", t)
    t = re.sub(r"__[^_]+__", " ", t)
    return t


def wikitext_sections(wt):
    """== 标题 == 当章节名，正文按空行分段。"""
    t = wikitext_to_text(wt)
    out, sec, para = [], "", []

    def flush():
        if para:
            txt = " ".join(" ".join(para).split())
            del para[:]
            if txt:
                out.append((sec, txt))

    for line in t.split("\n"):
        s = line.strip()
        m = SEC_HEAD.match(s)
        if m:
            flush()
            sec = m.group(2).strip()
        elif not s:
            flush()
        else:
            para.append(s)
    flush()
    return out


def ws_records(lang, d):
    for line in io.open(os.path.join(d, "wikitext.jsonl"), encoding="utf-8"):
        r = json.loads(line)
        wt = r["wikitext"]
        head = wt[:2500]
        bad = next((t for t in WS_NO_TPL if t.lower() in head.lower()), None)
        ok = next((t for t in WS_OK_TPL if re.search(r"\{\{\s*" + re.escape(t), head, re.I)), None)
        if bad or not ok:
            lic, raw = ("CLOSED" if bad else "NONE"), head[:200]
        else:
            i = head.lower().find(ok.lower())
            lic, raw = norm_license("PD"), " ".join(head[max(0, i - 30):i + 90].split())
        yield {"pid": "ws:" + str(r.get("pageid") or r["title"]),
               "url": "https://%s/wiki/%s" % (WS_HOST, urllib.parse.quote(r["title"].replace(" ", "_"))),
               "doi": None, "journal": "维基文库（%s）" % r["cat"], "title": r["title"],
               "year": (r.get("rev_ts") or "")[:4], "license": lic, "license_raw": raw,
               "fetched_at": r["fetched_at"], "sections": wikitext_sections(wt)}


# ---------------------------------------------------------------- 族四之二：Project Gutenberg（英文公版书）

PG_PAGE = "https://gutendex.com/books"
PG_N = 180


def pg_json(h, params):
    u = "%s?%s" % (PG_PAGE, urllib.parse.urlencode(params))
    last = None
    for k in range(4):
        try:
            return json.loads(fetch(u, h, tries=2).decode("utf-8", "replace"), strict=False)
        except Exception as e:
            last = e
            time.sleep(2.0 * (k + 1))
    raise IOError("gutendex 取不回：%s" % type(last).__name__)


def pg_ids(h, d, redo=False):
    out = os.path.join(d, "books.jsonl")
    if os.path.exists(out) and not redo:
        print("已有书目：%d 本" % sum(1 for _ in io.open(out, encoding="utf-8")))
        return
    want = "text/plain; charset=utf-8"
    picked, page = [], 1
    with io.open(out, "w", encoding="utf-8", newline="") as f:
        while len(picked) < PG_N and page <= 12:
            j = pg_json(h, {"languages": "en", "sort": "download_count", "mime_type": want,
                            "page": page, "results_per_page": 100})
            for b in j.get("results") or []:
                url = (b.get("formats") or {}).get(want)
                if not url:
                    continue
                rec = {"gencode": b.get("id"), "title": b.get("title"),
                       "authors": "; ".join(a.get("name") or "" for a in (b.get("authors") or [])),
                       "year": (b.get("copyright_year") or ""), "url": url,
                       "downloads": b.get("download_count"), "fetched_at": now()}
                f.write(json.dumps(rec, ensure_ascii=False) + "\n")
                picked.append(rec)
                if len(picked) >= PG_N:
                    break
            print("  gutendex 第 %d 页：累计 %d 本" % (page, len(picked)), flush=True)
            page += 1
            time.sleep(0.3)
    print("书目落盘：%d 本" % len(picked))


def pg_fetch(h, d, redo=False):
    recs = [json.loads(l) for l in io.open(os.path.join(d, "books.jsonl"), encoding="utf-8")]
    cache = os.path.join(d, "txt")
    os.makedirs(cache, exist_ok=True)
    todo = [r for r in recs if not (os.path.exists(os.path.join(cache, "%s.txt" % r["gencode"])) and not redo)]
    print("取正文：还差 %d 本" % len(todo), flush=True)
    for n, r in enumerate(todo):
        try:
            raw = fetch(r["url"], h, tries=3, timeout=120)
        except IOError as e:
            print("  跳过 %s：%s" % (r["url"][:70], e))
            continue
        io.open(os.path.join(cache, "%s.txt" % r["gencode"]), "wb").write(raw)
        if n % 20 == 0:
            print("  txt %-5d / %d（%s）" % (n + 1, len(todo), r["title"][:40]), flush=True)
        time.sleep(0.2)


PG_HEAD = re.compile(r"\*\*\*\s*START OF TH[IE] PROJECT GUTENBERG.*?\*\*\*", re.I)
PG_TAIL = re.compile(r"\*\*\*\s*END OF TH[IE] PROJECT GUTENBERG", re.I)
PG_LIC = re.compile(r"(?i)(This eBook is for the use of anyone[^\n]*|Title:\s*.*|Produced by[^\n]*|Release Date:[^\n]*|\*\*\*[\s]*[A-Z ]{8,}[\s]*\*\*\*)")
CHAPTER = re.compile(r"^(?:CHAPTER|Chapter\s|BOOK\s|Book\s|ACT\s|Act\s|CANTO|Volume\s|Vol\.\s|PART\s|Preface|INTRODUCTION|Introduction|EPILOGUE|Epilogue|APPENDIX|CONTENTS)\b")


def pg_text(raw_bytes):
    t = raw_bytes.decode("utf-8", "replace")
    try:
        t = t.encode("latin-1").decode("utf-8", "replace")
    except Exception:
        pass
    m = PG_HEAD.search(t)
    body = t[m.end():] if m else t
    m2 = PG_TAIL.search(body)
    if m2:
        body = body[:m2.start()]
    body = re.sub(r"(?m)^\s*\d{1,4}\s*$", "", body)                 # 页码行
    body = re.sub(r"(?m)^\s*\[Illustration[^\]]*\]\s*$", "", body)
    body = re.sub(r"[ \t]+", " ", body)
    head_lic = " ;; ".join(x.group(0).strip()[:120] for x in list(PG_LIC.finditer(t[:2500]))[:4])
    return body, head_lic


def pg_sections(body):
    out, sec, para = [], "", []

    def flush():
        if para:
            txt = " ".join(" ".join(para).split())
            del para[:]
            if not txt:
                return
            if len(txt) <= 70 and CHAPTER.match(txt):
                out.append((txt[:70], ""))
            else:
                out.append((sec, txt))

    for line in body.replace("\r\n", "\n").split("\n"):
        s = line.strip()
        if not s:
            flush()
        else:
            para.append(s)
    flush()
    return [(s, t) for s, t in out if t]


def pg_records(lang, d):
    cache = os.path.join(d, "txt")
    for line in io.open(os.path.join(d, "books.jsonl"), encoding="utf-8"):
        r = json.loads(line)
        p = os.path.join(cache, "%s.txt" % r["gencode"])
        if not os.path.exists(p):
            continue
        body, lic = pg_text(io.open(p, "rb").read())
        yield {"pid": "pg:%s" % r["gencode"], "url": r["url"], "doi": None,
               "journal": "Project Gutenberg（%s）" % (r.get("authors") or "")[:60],
               "title": r["title"], "year": r.get("year"), "license": "公有领域",
               "license_raw": (lic or "Project Gutenberg 电子文本，美国公版；文件头未抓到授权句")[:200],
               "fetched_at": r["fetched_at"], "sections": pg_sections(body)}


# ---------------------------------------------------------------- 按篇三分与清单

SPLIT_RULE = "按篇：md5(family|pid) 稳定哈希，前 8%% 进 SPARE（备用样本位；原计划给机器改写，2026-10-09 那一路取消），其后 20%% 进 FIT（定阈值），余下进 HOLD（只量真人误报）"


def split_of(fam, pid):
    hv = int(hashlib.md5(("%s|%s" % (fam, pid)).encode("utf-8")).hexdigest()[:8], 16) % 100
    return "SPARE" if hv < 8 else ("FIT" if hv < 28 else "HOLD")


FAMILY_DOC = {
    "pmc": {"name": "PMC 开放获取子集（已发表论文正文 XML）",
            "license_source": "XML 的 permissions/license_ref 字段，逐篇核；读不到明确开放许可整篇丢"},
    "qa": {"name": "Stack Exchange 长答案（挑散文多的站：academia/history/philosophy/english/literature/"
                                   "worldbuilding/travel/politics；中文取 chinese 站）",
            "license_source": "逐条取 API 回的 content_license 字段，并把作者名与永久链接一起记进清单（CC BY-SA 的署名要求）"},
    "books": {"name": "公版书正文：中文取维基文库（作者身后 50 年那批），英文取 Project Gutenberg",
              "license_source": "维基文库取作品页顶上的 PD 模板原文，古登堡取 txt 文件头那一句授权；"
                                "带 Copyright/Non-PD 标记的整篇丢"},
    "wiki": {"name": "维基百科条目正文（zh 取典范条目+优良条目，en 取 Featured+Good）",
             "license_source": "条目正文的授权在站点使用条款里：siteinfo.rightsinfo 的原文取一次存缓存，"
                               "逐篇再记取的是哪一号修订与时间，这两样一起进清单"},
}


def stage_report(args):
    rows = []
    per_family = {}
    for name in sorted(os.listdir(DATA)):
        up = os.path.join(DATA, name, "units.jsonl")
        pp = os.path.join(DATA, name, "papers.jsonl")
        if not (os.path.exists(up) and os.path.exists(pp)):
            continue
        units = [json.loads(l) for l in io.open(up, encoding="utf-8") if l.strip()]
        papers = [json.loads(l) for l in io.open(pp, encoding="utf-8") if l.strip()]
        for u in units:
            u["split"] = split_of(u["family"], u["pid"])
        sp_units = Counter(u["split"] for u in units)
        pid_split = dict((p["pid"], split_of(p["family"], p["pid"])) for p in papers)
        sp_papers = Counter(pid_split.values())
        langs = Counter(u["lang"] for u in units)
        per_family[name] = {
            "family_doc": FAMILY_DOC.get(papers[0]["family"], {}),
            "papers_seen": len(papers), "papers_with_units": len(set(u["pid"] for u in units)),
            "units": len(units), "units_by_lang": dict(langs),
            "papers_by_split": dict(sp_papers), "units_by_split": dict(sp_units),
            "bands": dict(("%d-%d" % b, sum(1 for u in units if u["band"] == i)) for i, b in enumerate(BANDS)),
            "licenses": dict(Counter(u["license"] for u in units)),
            "median_chars": int(sorted(u["chars"] for u in units)[len(units) // 2]) if units else 0,
            "build_counts": (json.load(io.open(os.path.join(DATA, name, "build-stats.json"), encoding="utf-8")
                                       ) if os.path.exists(os.path.join(DATA, name, "build-stats.json")) else {}),
        }
        rows.append((name, len(papers), len(set(u["pid"] for u in units)), len(units), sp_units, sp_papers))
        # 逐篇来源与许可清单进仓库（无正文）
        rel = os.path.join(MAN_DIR, "aigc-holdout3-%s.jsonl" % name)
        lic_table = {}
        with io.open(rel, "w", encoding="utf-8", newline="") as f:
            for rec in papers:
                raw = rec.get("license_raw") or ""
                lid = lic_table.setdefault(raw, "L%04d" % (len(lic_table) + 1))
                out = {k: rec.get(k) for k in ("pid", "url", "doi", "journal", "year",
                                               "license", "fetched_at", "candidates", "kept")}
                out["lic"] = lid
                out["family"], out["lang"] = rec["family"], rec["lang"]
                out["split"] = pid_split[rec["pid"]]
                f.write(json.dumps(out, ensure_ascii=False) + "\n")
        # 许可原文对照表：同一族里原文重复度极高，去重后仍可能上千条，全量嵌进清单会把清单撑到
        # 比正文摘要还大。这里只嵌使用次数最高的 300 条，其余每条仍给号，指向仓库外那份逐篇记录。
        by_use = Counter()
        for rec in papers:
            by_use[lic_table.get(rec.get("license_raw") or "", "?")] += 1
        top = set(k for k, _ in by_use.most_common(300))
        per_family[name]["license_excerpts"] = dict(
            (lid, (txt[:130] if lid in top else "（原文见仓库外 ../aigc-corpus/holdout3/%s/papers.jsonl 的 license_raw）" % name))
            for txt, lid in lic_table.items())
        per_family[name]["license_excerpt_count"] = len(lic_table)
        print("%-12s 篇 %-6d（有段 %-6d）｜段 %-7d｜三分 段 FIT %-6d HOLD %-6d SPARE %-6d｜篇 FIT %-5d HOLD %-5d SPARE %-5d"
              % (name, len(papers), len(set(u["pid"] for u in units)), len(units),
                 sp_units["FIT"], sp_units["HOLD"], sp_units["SPARE"],
                 sp_papers["FIT"], sp_papers["HOLD"], sp_papers["SPARE"]), flush=True)
    if not rows:
        print("还没有任何一族跑完 build")
        return
    tot_units = sum(r[3] for r in rows)
    tot_hold = sum(r[4]["HOLD"] for r in rows)
    man = {
        "what": "holdout3：段落级真人侧扩量集（160~520 字/段，切段口径与 tools/aigc-para-probe.py 一致）。"
                "真人侧没有一个字是模型生成的；原文一律不进仓库。",
        "built": now(),
        "builder": "tools/aigc-corpus3.py",
        "text_location": os.path.join("..", "aigc-corpus", "holdout3", "<族>", "units.jsonl") + "（仓库外，含原文）",
        "why_no_repo": "原文不进仓库；仓库里只有逐篇来源与许可清单 tests/corpus/aigc-holdout3-<族>.jsonl 与这一份计数",
        "license_policy": {"accepted": list(ACCEPTED),
                           "rejected": "拿不到明确开放许可、或写明未经授权不得转引的，整篇丢弃",
                           "why_nc_nd_ok": "原文只在本地测量时读取，不随包、不训练、不对外散布"},
        "split_rule": SPLIT_RULE,
        "totals": {"units": tot_units, "units_hold": tot_hold,
                   "fp_resolution_per_1000_at_2": round(1.96 * math.sqrt(2.0 * 1000.0 / max(1, tot_hold)), 3),
                   "hold_articles": sum(r[5]["HOLD"] for r in rows),
                   "note": "真人误报 2/千段时的 95% 区间半宽（每千段），分母就是 units_hold"},
        "families": per_family,
    }
    json.dump(man, io.open(os.path.join(MAN_DIR, "aigc-holdout3-manifest.json"), "w", encoding="utf-8", newline=""),
              ensure_ascii=False, indent=1)
    print("合计：段 %-7d（其中只量误报的 HOLD %d 段）｜2/千段的 95%% 区间半宽 %.3f/千段"
          % (tot_units, tot_hold, man["totals"]["fp_resolution_per_1000_at_2"]))
    print("清单（无正文）：tests/corpus/aigc-holdout3-manifest.json 与每族一份 jsonl")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("stage", choices=["ids", "fetch", "build", "report"])
    ap.add_argument("--family", default="pmc")
    ap.add_argument("--lang", default="zh", choices=["zh", "en"])
    ap.add_argument("--per-paper", type=int, default=4)
    ap.add_argument("--proxy", default=PROXY_DEFAULT)
    ap.add_argument("--redo", action="store_true")
    ap.add_argument("--workers", type=int, default=12)
    a = ap.parse_args()
    os.makedirs(DATA, exist_ok=True)
    if a.stage == "ids":
        h = opener(a.proxy)
        if a.family == "pmc":
            pmc_ids(h, fam_dir(a.family, a.lang), a.lang, a.redo)
        elif a.family == "wiki":
            wiki_titles(h, a.lang, fam_dir(a.family, a.lang), a.redo)
        elif a.family == "qa":
            qa_answers(h, a.lang, fam_dir(a.family, a.lang), a.redo)
        elif a.family == "books":
            (ws_titles(h, fam_dir(a.family, a.lang), a.redo) if a.lang == "zh"
             else pg_ids(h, fam_dir(a.family, a.lang), a.redo))
        else:
            raise SystemExit("这一族还没接：ids %s" % a.family)
    elif a.stage == "fetch":
        h = opener(a.proxy)
        d = fam_dir(a.family, a.lang)
        if a.family == "pmc":
            ids = json.load(io.open(os.path.join(d, "ids.json"), encoding="utf-8"))
            pmc_xml(h, ids, os.path.join(d, "xml"), a.redo)
        elif a.family == "wiki":
            wiki_fetch(h, a.lang, d, a.redo, workers=a.workers)
        elif a.family == "qa":
            print("这一族的正文与题录是一次请求同时回的，fetch 无额外动作；直接跑 build")
        elif a.family == "books":
            (ws_fetch(h, d, a.redo) if a.lang == "zh" else pg_fetch(h, d, a.redo))
        else:
            raise SystemExit("这一族还没接：fetch %s" % a.family)
    elif a.stage == "build":
        stage_build(a)
    else:
        stage_report(a)


if __name__ == "__main__":
    main()
