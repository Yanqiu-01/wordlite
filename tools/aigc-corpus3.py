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
import urllib.parse, urllib.request
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


def pack(text, cap=12):
    """句号级边界贪心打包到 TARGET 上下，160~520 字才算一段；一篇最多取 cap 段。"""
    out, cur = [], ""
    for s in sentences(norm(text)):
        n = len_ns(s)
        if not n:
            continue
        if not cur:
            cur = s
            continue
        l = len_ns(cur)
        if l < TARGET and l + n <= CEIL:
            cur = cur + s
        elif l < TARGET + TOL and l + n <= CEIL:
            cur = cur + s
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
    nsent = len(sentences(text))
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


def dedupe(units, seen_exact, seen_shingle):
    """跨篇去重：逐字相同（压缩后 SHA-1）与近重复（24 字长串撞上两处）。全局一本账，跨族也去。"""
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
        seen_exact[h] = u["pid"]
        for s in shingles(c):
            seen_shingle.setdefault(s, u["pid"])
        u["compact_sha1"] = h
        kept.append(u)
    return kept, dropped


def load_global_index(d):
    """去重账本落在 DATA 下（仓库外）：跨族、跨次运行都认同一本账。"""
    path = os.path.join(DATA, "_dedupe_index.json")
    if os.path.exists(path):
        j = json.load(io.open(path, encoding="utf-8"))
        return dict(j.get("exact") or {}), dict(j.get("shingle") or {})
    return {}, {}


def save_global_index(d, exact, shingle):
    json.dump({"exact": exact, "shingle": shingle, "built": now()},
              io.open(os.path.join(DATA, "_dedupe_index.json"), "w", encoding="utf-8", newline=""),
              ensure_ascii=False)


def stage_build(args):
    d = fam_dir(args.family, args.lang)
    xp = os.path.join(d, "xml")
    if not os.path.isdir(xp):
        raise SystemExit("先跑 fetch：%s 不存在" % xp)
    lang, fam = args.lang, args.family
    exact, shidx = load_global_index(d)
    before_exact, before_sh = len(exact), len(shidx)
    papers, units, stats, reasons, lic_seen = [], [], Counter(), Counter(), Counter()
    for art in iter_articles(xp):
        ids = dict((e.get("pub-id-type") or "?", flatten(e)) for e in art.iter() if local(e) == "article-id")
        pmcid = ids.get("pmcid") or ("PMC" + (ids.get("pmcaid") or ""))
        if not pmcid.strip("PMC"):
            stats["认不出 PMCID"] += 1
            continue
        lic, lic_raw = license_of(art)
        lic_seen[lic] += 1
        url = "https://pmc.ncbi.nlm.nih.gov/articles/%s/" % pmcid
        rec = {"pid": "pmc:" + pmcid, "family": fam, "lang": lang, "url": url,
               "doi": ids.get("doi"), "journal": art_field(art, "journal-title"),
               "title": art_field(art, "article-title")[:160], "year": art_field(art, "year"),
               "license": lic, "license_raw": re.sub(r"\s+", " ", lic_raw)[:160], "fetched_at": now(),
               "candidates": 0, "kept": 0}
        if lic not in ACCEPTED:
            stats["许可不过，整篇丢弃"] += 1
            papers.append(rec)
            continue
        stats["许可过的篇数"] += 1
        cand = []
        for order, (sec, txt) in enumerate(body_sections(art)):
            for piece in pack(txt, cap=args.per_paper * 3):
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
                     url=url, license=lic, index=i)
            units.append(u)
    kept, dropped = dedupe(units, exact, shidx)
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

# ---------------------------------------------------------------- 按篇三分与清单

SPLIT_RULE = "按篇：md5(family|pid) 稳定哈希，前 8%% 进 SPARE（备用样本位；原计划给机器改写，2026-10-09 那一路取消），其后 20%% 进 FIT（定阈值），余下进 HOLD（只量真人误报）"


def split_of(fam, pid):
    hv = int(hashlib.md5(("%s|%s" % (fam, pid)).encode("utf-8")).hexdigest()[:8], 16) % 100
    return "SPARE" if hv < 8 else ("FIT" if hv < 28 else "HOLD")


FAMILY_DOC = {
    "pmc": {"name": "PMC 开放获取子集（已发表论文正文 XML）",
            "license_source": "XML 的 permissions/license_ref 字段，逐篇核；读不到明确开放许可整篇丢"},
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
        per_family[name]["license_excerpts"] = dict((v, k[:150]) for k, v in lic_table.items())
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
    a = ap.parse_args()
    os.makedirs(DATA, exist_ok=True)
    if a.stage == "ids":
        h = opener(a.proxy)
        if a.family == "pmc":
            pmc_ids(h, fam_dir(a.family, a.lang), a.lang, a.redo)
        else:
            raise SystemExit("这一族还没接：%s" % a.family)
    elif a.stage == "fetch":
        h = opener(a.proxy)
        d = fam_dir(a.family, a.lang)
        if a.family == "pmc":
            ids = json.load(io.open(os.path.join(d, "ids.json"), encoding="utf-8"))
            pmc_xml(h, ids, os.path.join(d, "xml"), a.redo)
        else:
            raise SystemExit("这一族还没接：%s" % a.family)
    elif a.stage == "build":
        stage_build(a)
    else:
        stage_report(a)


if __name__ == "__main__":
    main()
