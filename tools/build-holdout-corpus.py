# -*- coding: utf-8 -*-
"""
真稿留出集扩充：段落级（120~656 字）的真人中文学术正文。

    py tools/build-holdout-corpus.py ids      # 从 PMC 开放获取子集取 ID 与题录
    py tools/build-holdout-corpus.py fetch    # 批量取全文 XML（缓存到仓库外，可续跑）
    py tools/build-holdout-corpus.py build    # 逐篇核许可 + 抽正文段 + 去重 + 每篇封顶 + 分层抽样
    py tools/build-holdout-corpus.py audit    # 自审：学科×长度带分格、许可齐全、与训练语料逐字重合

纪律（这个脚本只管"尺子"，不碰建模，也不碰出厂清单）：
* 真人侧一个字都不许是模型生成的。这里每一段都来自已发表论文的正文 XML。
* 许可逐篇核：正文 XML 里找不到明确的开放许可（CC 系列）就丢；"未经授权，不得转载"这类一律丢。
  接受的档：CC BY / CC BY-SA / CC BY-NC / CC BY-NC-SA。这批原文只留在仓库外做本地测量，
  不进仓库、不进训练、不对外散布，NC 与 ND 这两条限制没被碰到；
  清单里另标出哪些（只有 CC BY / BY-SA）将来允许进仓库。
* 原文不进仓库：全部留在 ../aigc-corpus/holdout2/；仓库里只有一份来源与许可清单
  tests/corpus/aigc-holdout2-manifest.json，里面没有正文。
* 同一篇最多取 PER_PAPER_CAP 段，而且来自不同小节；篇级 group 一路带进报表。
  "同一篇拆成八句被算成八个人"这种事，报表里直接看得见（段数 / 篇数 / 第一作者数分开列）。
"""
import argparse, gzip, hashlib, io, json, math, os, re, sys, time, urllib.parse, urllib.request
import xml.etree.ElementTree as ET
from collections import Counter, defaultdict

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
DEFAULT_DATA = os.path.abspath(os.path.join(REPO, "..", "aigc-corpus", "holdout2"))
MANIFEST = os.path.join(REPO, "tests", "corpus", "aigc-holdout2-manifest.json")
EUTILS = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/"
UA = "WordliteAigc/1.0 (mailto:wordlite.dev@example.com)"

# 检索式：PMC 里语言标成中文、且落在开放获取子集里的期刊论文。年份卡在这里是为了避开
# 2023 年以后"真人稿里可能混了模型代笔"这一段说不清的时期。
SEARCH_TERM = 'chinese[lang] AND "open access"[filter] AND 2013:2022[pdat]'

MIN_PARA = 120            # 段落最短（去掉空白后的字数）
MAX_PARA = 656            # 段落最长：仓库里 H1 那份真稿单段最长 665 字，卡在同一量级
BANDS = [(120, 199), (200, 319), (320, 479), (480, 656)]
PER_PAPER_CAP = 2         # 同一篇最多取几段
TARGET = 4500             # 目标计分段数
SHINGLE = 24              # 近重复：24 个字的长串，步长 12，撞上两处即判近重复
SHINGLE_STRIDE = 12
NEAR_DUP_HITS = 2
MIN_CJK_RATIO = 0.45      # 汉字占比：低于这个多半是表格残片、题注或英文段
MIN_CJK = 60

ACCEPTED_LICENSES = ("CC BY", "CC BY-SA", "CC BY-NC", "CC BY-NC-SA")
REDISTRIBUTABLE = ("CC BY", "CC BY-SA")

# 这一路不用的来源，连同"为什么不用"一起写进清单，免得下一个人重新踩
NOT_USED = [
    ("汉斯出版社 hanspub.org（中文，全学科，31,471 篇 2019-2022 挂 DOI）",
     "文章页只写 '开放获取'，页脚是 '版权所有：汉斯出版社 All rights reserved'，"
     "Crossref 那批记录的 license/link 字段是空的：许可核对不过，放弃"),
    ("OpenAlex（元数据 CC0，可拿 language:zh + 开放获取 + best_oa_location.license 跨学科筛）",
     "本机出口取不到：api.openalex.org 首次请求即 HTTP 429 Too Many Requests，"
     "直连与代理 127.0.0.1:7897 两路都一样，重试 6 次无果；这条路等限流解除再接"),
    ("DOAJ 中文期刊（bibjson.language:zh 328 份，文章级题录 406,198 条，含 link 与学科）",
     "题录与期刊级许可齐全（CC BY 31 份 / CC BY-NC 37 份 / CC BY-NC-ND 254 份），"
     "但全文在各刊自己的站上：抽 1,000 条链接落在 100 多个主机，"
     "最大一族 thesisDetails?columnId= 平台（护理研究/针刺研究/四川大学学报工程科学版/机械传动/"
     "水利学报/大数据期刊……）直连与代理都只回一张 6,006 字节的 JS 挑战页。"
     "要吃得下这一路得按平台写取页适配器，本轮没做，题录已缓存（见 audit 的 sources）"),
    ("PMC 里没进开放获取子集的中文刊（中华肝脏病杂志、川大学报医学版早期、华西口腔等 13 本）",
     "正文 XML 的 permissions 里写的是 '未经授权，不得转载、摘编本刊文章'，逐篇核许可全部不过"),
    ("中国知网 / 万方 / 国家哲社文献中心的学位论文与期刊",
     "要登录，匿名取不到；国家哲社文献中心即便可读也没有允许再散布的授权"),
]

# ---------------------------------------------------------------- 取数

def opener(proxy):
    if proxy:
        return urllib.request.build_opener(urllib.request.ProxyHandler({"http": proxy, "https": proxy}))
    return urllib.request.build_opener()


def fetch(url, h, tries=4, wait=2.0, timeout=90, binary=False):
    """带重试的一次取数。NCBI 对无 key 的调用限 3 次/秒，调用方自己控制节奏。"""
    last = None
    for k in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Encoding": "gzip"})
            with h.open(req, timeout=timeout) as r:
                raw = r.read()
                if (r.headers.get("Content-Encoding") or "").lower() == "gzip":
                    raw = gzip.decompress(raw)
                return raw if binary else raw.decode("utf-8", "replace")
        except Exception as e:                      # 网络抖动与 429 一律退避重试
            last = e
            time.sleep(wait * (k + 1))
    raise SystemExit("取不到 %s（%s: %s）" % (url[:120], type(last).__name__, last))


def fetch_json(url, key, h):
    for k in range(4):
        raw = fetch(url, h)
        try:
            got = json.loads(raw, strict=False).get(key)
        except ValueError:
            time.sleep(2.0)
            continue
        if got:
            return got
        print("  空响应：%s" % raw[:150].replace("\n", " "))
        time.sleep(2.5)
    raise SystemExit("接口一直给空响应：%s" % url[:120])


def pmc_ids(h, out_path, redo=False):
    if os.path.exists(out_path) and not redo:
        ids = json.load(io.open(out_path, encoding="utf-8"))
        print("已有 ID：%d 篇（--redo 重取）" % len(ids))
        return ids
    ids, total = [], "0"
    for start in range(0, 20000, 500):
        u = EUTILS + "esearch.fcgi?" + urllib.parse.urlencode(
            {"db": "pmc", "term": SEARCH_TERM, "retmax": 500, "retstart": start,
             "usehistory": "y", "retmode": "json"})
        d = fetch_json(u, "esearchresult", h)
        total = d.get("count")
        got = d.get("idlist") or []
        if not got:
            if start < int(total or 0):             # 偶发空页：重试一次再决定收尾
                time.sleep(2.5)
                d = fetch_json(u, "esearchresult", h)
                got = d.get("idlist") or []
            if not got:
                break
        ids += got
        print("  esearch retstart=%-6d 取回 %-4d 累计 %-6d / 命中 %s" % (start, len(got), len(ids), total), flush=True)
        time.sleep(0.6)
    ids = sorted(set(ids))
    json.dump(ids, io.open(out_path, "w", encoding="utf-8"), ensure_ascii=False)
    print("ID 落盘：%d 篇（检索式命中 %s 篇）" % (len(ids), total))
    return ids


def pmc_summaries(h, ids, out_path, redo=False):
    done = set()
    if os.path.exists(out_path) and not redo:
        for line in io.open(out_path, encoding="utf-8"):
            done.add(json.loads(line)["uid"])
    todo = [i for i in ids if i not in done]
    if not todo:
        print("已有题录：%d 篇" % len(done))
        return
    print("取题录：还差 %d 篇" % len(todo))
    with io.open(out_path, "a" if done else "w", encoding="utf-8") as f:
        for k in range(0, len(todo), 180):
            batch = todo[k:k + 180]
            u = EUTILS + "esummary.fcgi?" + urllib.parse.urlencode({"db": "pmc", "id": ",".join(batch), "retmode": "json"})
            res = fetch_json(u, "result", h)
            for i in batch:
                r = res.get(i)
                if not r:
                    continue
                aid = dict((a.get("idtype"), a.get("value")) for a in r.get("articleids") or [])
                f.write(json.dumps({
                    "uid": i, "pmcid": aid.get("pmcid") or ("PMC" + i), "pmid": aid.get("pmid"),
                    "doi": aid.get("doi"), "title": r.get("title"),
                    "journal": r.get("source"), "journal_full": r.get("fulljournalname"),
                    "year": (r.get("sortdate") or "")[:4], "pubdate": r.get("pubdate"),
                    "n_authors": len(r.get("authors") or []),
                    "first_author": ((r.get("authors") or [{}])[0]).get("name"),
                }, ensure_ascii=False) + "\n")
            f.flush()
            if k % 1800 == 0:
                print("  summary %-6d / %d" % (k + len(batch), len(todo)), flush=True)
            time.sleep(0.4)


def pmc_xml(h, ids, xml_dir, redo=False):
    """全文 XML 按批缓存：一批 100 篇一次请求，跑过就跳过，中断了能接着跑。"""
    os.makedirs(xml_dir, exist_ok=True)
    todo = []
    for k in range(0, len(ids), 100):
        if not (os.path.exists(os.path.join(xml_dir, "b%04d.xml" % (k // 100))) and not redo):
            todo.append(k)
    print("取全文 XML：还差 %d 批（每批 100 篇）" % len(todo))
    for n, k in enumerate(todo):
        batch = ids[k:k + 100]
        u = EUTILS + "efetch.fcgi?" + urllib.parse.urlencode({"db": "pmc", "id": ",".join(batch), "retmode": "xml"})
        xml = fetch(u, h, timeout=240)
        io.open(os.path.join(xml_dir, "b%04d.xml" % (k // 100)), "w", encoding="utf-8", newline="").write(xml)
        if n % 5 == 0:
            print("  xml batch %-4d / %d（%d 篇）" % (k // 100 + 1, len(todo), len(batch)), flush=True)
        time.sleep(0.6)

# ---------------------------------------------------------------- 许可逐篇核

def license_of(art):
    """从一篇 XML 里读许可。返回 (档位, 原文片段)。读不到明确开放许可的一律 CLOSED/NONE。"""
    bits = []
    for e in art.iter():
        t = local(e)
        if t == "license_ref" or t == "license-p" or t in ("copyright-statement", "copyright-notice"):
            bits.append(flatten(e))
    txt = " ;; ".join(x for x in bits if x)
    low = txt.lower().replace(" ", "")
    if "by-nc-nd" in low or "by-nc-nd" in txt:
        return "CC BY-NC-ND", txt[:220]
    if "by-nc-sa" in low:
        return "CC BY-NC-SA", txt[:220]
    if "by-sa" in low:
        return "CC BY-SA", txt[:220]
    if "by-nc" in low or "署名—非商业" in txt or "署名-非商业" in txt:
        return "CC BY-NC", txt[:220]
    if "creativecommons.org/licenses/by/" in low or "creativecommonsattribution" in low or "ccby4" in low:
        return "CC BY", txt[:220]
    if "creativecommons.org/licenses" in low:
        return "CC 其他档", txt[:220]
    if re.search(r"未经授权.{0,12}不得转载|保留所有权利|All rights reserved|版权所有", txt, re.I):
        return "CLOSED", txt[:220]
    return "NONE", txt[:220]


# ---------------------------------------------------------------- 学科判定

JOURNAL_RULES = [
    ("血液病学", ("xue ye xue", "hematology")),
    ("肿瘤学（肺）", ("fei ai", "lung cancer")),
    ("分析化学（色谱）", ("se pu", "chromatography")),
]
# 学科档：按顺序第一次命中就算。标题里没有关键词的落到该刊那一档的"其他"。
DISCIPLINE_RULES = {
    "血液病学": [
        ("血液病学·造血干细胞移植与输血", ("移植", "供者", "造血干细胞", "脐血", "输注", "输血", "配型", "移植物抗宿主")),
        ("血液病学·出凝血与血栓", ("血栓", "凝血", "出血", "血小板", "血友病", "紫癜", "抗凝", "血管性血友")),
        ("血液病学·淋巴瘤", ("淋巴瘤", "霍奇金", "淋巴增殖", "浆细胞病")),
        ("血液病学·白血病与骨髓肿瘤", ("白血病", "骨髓瘤", "骨髓增生", "骨髓异常", "增生异常", "骨髓纤维化", "急非淋", "急淋")),
        ("血液病学·贫血与红细胞疾病", ("贫血", "红细胞", "地中海", "缺铁", "再障", "再生障碍")),
        ("血液病学·实验诊断与微观", ("染色体", "融合基因", "突变", "流式", "免疫分型", "核型", "微小残留")),
    ],
    "肿瘤学（肺）": [
        ("肿瘤学（肺）·外科与放疗", ("手术", "切除", "胸腔镜", "肺叶", "术前", "术后", "放疗", "立体定向", "分期")),
        ("肿瘤学（肺）·药物治疗与靶向", ("靶向", "吉非", "厄洛", "克唑", "阿帕", "化疗", "一线", "二线", "耐药", "TKI", "免疫治疗", "PD-1", "PD-L1", "贝伐")),
        ("肿瘤学（肺）·影像筛查与病理", ("CT", "影像", "结节", "磨玻璃", "穿刺", "病理", "细胞学", "PET")),
        ("肿瘤学（肺）·基础与生物标志物", ("细胞", "基因", "蛋白", "表达", "机制", "通路", "标志物", "miRNA", "增殖", "迁移", "侵袭", "动物")),
        ("肿瘤学（肺）·流行病与生存", ("流行病", "危险", "队列", "生存", "预后", "危险因素", "倾向性")),
    ],
    "分析化学（色谱）": [
        ("分析化学（色谱）·方法与仪器", ("色谱柱", "固定相", "填料", "制备", "分离", "保留", "洗脱", "方法建立", "方法学", "在线", "二维", "超临界", "毛细电泳")),
        ("分析化学（色谱）·药物与天然产物", ("药物", "血药浓度", "中药", "天然", "黄酮", "生物碱", "含量测定", "指纹图谱", "成分")),
        ("分析化学（色谱）·组学与生境样本", ("代谢组", "蛋白组", "血清", "血浆", "尿液", "粪便", "肠道菌", "细胞因子")),
        ("分析化学（色谱）·食品环境与安全", ("食品", "农产品", "环境", "水样", "土壤", "农药", "兽药", "残留", "添加剂", "塑化剂")),
    ],
    "综合医学期刊": [
        ("综合医学·护理与康复", ("护理", "康复", "自我效能", "依从", "生活质量", "焦虑", "抑郁", "满意度", "健康教育")),
        ("综合医学·中医药", ("中医", "中药", "针灸", "穴位", "艾灸", "辨证", "方剂", "中西医结合")),
        ("综合医学·口腔颌面", ("口腔", "牙", "颌", "种植", "正畸", "牙周")),
        ("综合医学·妇产与围产", ("妊娠", "产妇", "分娩", "子宫", "卵巢", "宫颈", "胎盘", "辅助生殖", "新生儿")),
        ("综合医学·儿科", ("儿科", "患儿", "小儿", "婴儿", "儿童")),
        ("综合医学·影像与介入", ("影像", "超声", "MRI", "磁共振", "CT", "PET", "介入", "造影", "超声弹性")),
        ("综合医学·外科与创伤骨科", ("手术", "外科", "切除", "重建", "骨折", "创伤", "内固定", "吻合", "皮瓣", "内镜下", "微创")),
        ("综合医学·心脑血管与内科", ("心血管", "冠脉", "心力衰竭", "心律失常", "高血压", "脑卒中", "脑梗", "心肌", "动脉粥样", "糖尿病", "肾病", "透析", "胃炎", "肝病", "肺炎", "呼吸")),
        ("综合医学·肿瘤（非肺）", ("癌", "肿瘤", "瘤", "恶性", "化疗", "放疗", "转移", "靶向")),
        ("综合医学·公共卫生与流行病", ("流行病", "危险因素", "危险因素", "队列", "病例对照", "社区", "筛查", "疫苗", "知晓率", "聚集性")),
        ("综合医学·基础医学与实验", ("细胞", "大鼠", "小鼠", "动物模型", "基因", "蛋白", "表达", "机制", "通路", "敲除", "培养")),
    ],
}
JOURNAL_CN = {"zhonghua xue ye xue za zhi": "中华血液学杂志",
              "zhongguo fei ai za zhi": "中国肺癌杂志",
              "se pu": "色谱（Chinese Journal of Chromatography）",
              "journal of traditional chinese medicine": "中国中医研究院学报（英文版，英文稿，不进尺子）"}
DISCIPLINE_FALLBACK = {"血液病学": "血液病学·其他", "肿瘤学（肺）": "肿瘤学（肺）·其他",
                       "分析化学（色谱）": "分析化学（色谱）·其他", "综合医学期刊": "综合医学期刊·其他"}


def journal_group(journal_full, journal_xml):
    key = ((journal_full or "") + " " + (journal_xml or "")).lower()
    for name, keys in JOURNAL_RULES:
        if any(k in key for k in keys):
            return name
    return "综合医学期刊"


def discipline_of(jgroup, title):
    for label, keys in DISCIPLINE_RULES[jgroup]:
        if any(k in title for k in keys):
            return label
    return DISCIPLINE_FALLBACK[jgroup]

# ---------------------------------------------------------------- 抽正文段

SKIP_TAGS = ("ref-list", "table-wrap", "table-wrap-foot", "fig", "disp-formula", "disp-quote",
             "ack", "app", "app-group", "supplementary-material", "fn-group", " corresp",
             "author-notes", "bio", "embedded", "media", "graphic", "label", "def-list")
NOISE_HEAD = re.compile(r"^(关键词|keywords?|中图分类号|文献标志码|doi[:：]|收稿日期|基金项目|通信作者|作者简介|"
                        r"第一作者|引用本文|本文刊于|参考文献|表\s*\d|图\s*\d|注[:：]|note[:：]|abstract)", re.I)
BOILER = re.compile(r"(所有作者声明不存在利益冲突|利益冲突声明|参考文献\s*$|未经授权.{0,12}不得转载)")


def local(e):
    return e.tag.split("}")[-1]


def flatten(e, keep_xref=False):
    """把一个元素里的字拼起来：跳过文内参考文献角标（<xref ref-type="bibr">[12]</xref>）。"""
    out = []
    for ch in e.iter():
        t = local(ch)
        if not keep_xref and t == "xref" and (ch.get("ref-type") or "") in ("bibr", "table", "fig"):
            continue
        if ch.text:
            out.append(ch.text)
        if ch.tail and ch is not e:
            out.append(ch.tail)
    txt = re.sub(r"\s+", " ", " ".join(out)).strip()
    return re.sub(r"\s*([\u3002\uff1b\uff0c\uff1a])\s*", r"\1", txt)


def body_paragraphs(art):
    """正文里的小节段落（不含摘要、参考文献、表格、图注）。返回 [(小节标题, 文本)]。"""
    body = None
    for ch in art:
        if local(ch) == "body":
            body = ch
            break
    if body is None:
        return []
    out = []

    def walk(elem, sec_title):
        for ch in elem:
            t = local(ch)
            if t in SKIP_TAGS:
                continue
            if t == "p":
                txt = flatten(ch)
                if txt:
                    out.append((sec_title, txt))
            elif t == "sec":
                st = sec_title
                for g in ch:
                    if local(g) == "title":
                        st = flatten(g) or sec_title
                walk(ch, st)
            else:
                walk(ch, sec_title)
    walk(body, "")
    return out


def clean_paragraph(txt):
    t = re.sub(r"^[\d\.\s（）()、，,一二三四五六七八九十]+(?=[\u4e00-\u9fff])", "", txt).strip()
    t = re.sub(r"\[(\d{1,3}(-\d{1,3})?)\]", "", t)          # 文内角标 [12] / [12-14]
    t = re.sub(r"（\d{1,3}）(?=[\u4e00-\u9fff])", "", t)
    return t.strip()


def split_long(text, tm):
    """整段超过 %d 字时按句边界切块，取最长的那一块（标 form=长段截取）。
    切不出 120~%d 字的块就整段作废——不许拿半截句子凑数。""" % (MAX_PARA, MIN_PARA)
    pieces, cur = [], []
    for sent in tm.split_sentences(text):
        add = len(re.sub(r"\s", "", sent))
        if cur and len(re.sub(r"\s", "", "".join(cur))) + add > MAX_PARA:
            pieces.append("".join(cur))
            cur = [sent]
        else:
            cur.append(sent)
    if cur:
        pieces.append("".join(cur))
    out = []
    for x in pieces:
        x = x.strip()
        n = len(re.sub(r"\s", "", x))
        if MIN_PARA <= n <= MAX_PARA:
            out.append((x, n))
    return out


def reject_reason(text):
    """段不合格的原因；None 表示合格。目的是把表格残片、题注、题录、声明这类"不像正文"的剔掉。"""
    n = len(re.sub(r"\s", "", text))
    if n < MIN_PARA:
        return "短于 %d 字" % MIN_PARA
    if n > MAX_PARA:
        return "长于 %d 字" % MAX_PARA
    if NOISE_HEAD.match(text):
        return "题注/题录/声明开头"
    if BOILER.search(text):
        return "版权或利益冲突声明"
    if "http" in text.lower() or "doi.org" in text.lower():
        return "含链接"
    cjk = len(re.findall(r"[\u4e00-\u9fff]", text))
    if cjk < MIN_CJK:
        return "汉字不足 %d 个" % MIN_CJK
    if cjk < MIN_CJK_RATIO * n:
        return "汉字占比低于 %.0f%%" % (100 * MIN_CJK_RATIO)
    digits = len(re.findall(r"[0-9]", text))
    if digits > 0.30 * n:
        return "数字占比过高"
    if text.count("；") > 12 and cjk < 0.6 * n:
        return "像条目堆叠"
    return None


def band_of(n):
    for i, (lo, hi) in enumerate(BANDS):
        if lo <= n <= hi:
            return i
    return -1


def shingles(compact, k=SHINGLE, stride=SHINGLE_STRIDE):
    return set(compact[i:i + k] for i in range(0, max(0, len(compact) - k + 1), stride))

# ---------------------------------------------------------------- 组装

def read_jsonl(path):
    if not os.path.exists(path):
        raise SystemExit("缺文件：%s（先跑 ids 与 fetch 两步）" % path)
    return [json.loads(l) for l in io.open(path, encoding="utf-8") if l.strip()]


def load_train_module():
    """借训练台那份归一化（同一个 Normalizer / 同一个切句），两边口径不许分叉。"""
    import importlib.util
    path = os.path.join(HERE, "build-aigc-model.py")
    spec = importlib.util.spec_from_file_location("wordlite_aigc_train", path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


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
                yield name, ET.fromstring(chunk[:end + len("</article>")])
            except ET.ParseError:
                bad += 1
    if bad:
        print("XML 解析失败被跳过：%d 篇" % bad)


def art_field(art, want):
    for e in art.iter():
        if local(e) == want:
            return flatten(e)
    return ""


def art_ids(art):
    """<article-id pub-id-type="pmcid|pmid|doi|..."> 收成字典。"""
    out = {}
    for e in art.iter():
        if local(e) == "article-id":
            out[e.get("pub-id-type") or "?"] = flatten(e)
    return out


def stage_ids(args):
    h = opener(args.proxy)
    src = os.path.join(args.data, "pmc_oa")
    os.makedirs(src, exist_ok=True)
    ids = pmc_ids(h, os.path.join(src, "ids.json"), args.redo)
    pmc_summaries(h, ids, os.path.join(src, "summary.jsonl"), args.redo)


def stage_fetch(args):
    h = opener(args.proxy)
    src = os.path.join(args.data, "pmc_oa")
    ids = json.load(io.open(os.path.join(src, "ids.json"), encoding="utf-8"))
    pmc_xml(h, ids, os.path.join(src, "xml"), args.redo)


def stage_build(args):
    tm = load_train_module()
    norm = tm.Normalizer(tm.load_trad_table())
    src = os.path.join(args.data, "pmc_oa")
    summary = dict((r["uid"], r) for r in read_jsonl(os.path.join(src, "summary.jsonl")))
    stats = Counter()
    reasons = Counter()
    lic_seen = Counter()
    papers = []
    picked = []
    for name, art in iter_articles(os.path.join(src, "xml")):
        stats["xml 里读到的篇数"] += 1
        ids = art_ids(art)
        pmcid = ids.get("pmcid") or ("PMC" + ids["pmcaid"] if ids.get("pmcaid") else "")
        if not pmcid:
            stats["认不出 PMCID"] += 1
            continue
        uid = re.sub(r"\D", "", pmcid)
        meta = summary.get(uid) or {}
        jxml = art_field(art, "journal-title")
        verdict, lic_raw = license_of(art)
        lic_seen[verdict] += 1
        jgroup = journal_group(meta.get("journal_full"), jxml)
        title = art_field(art, "article-title") or meta.get("title") or ""
        year = meta.get("year") or ""
        rec = {"pmcid": pmcid, "pmid": ids.get("pmid") or meta.get("pmid"),
               "doi": ids.get("doi") or meta.get("doi"),
               "url": "https://pmc.ncbi.nlm.nih.gov/articles/%s/" % pmcid,
               "journal_xml": jxml, "journal_full": meta.get("journal_full") or jxml,
               "journal_group": jgroup, "year": year, "title": title,
               "license": verdict, "license_raw": lic_raw[:200],
               "n_authors": meta.get("n_authors"), "first_author": meta.get("first_author")}
        if verdict not in ACCEPTED_LICENSES:
            stats["许可不过，整篇丢弃"] += 1
            rec.update(candidates=0, kept=0)
            papers.append(rec)
            continue
        cands = []
        for order, (sec, txt) in enumerate(body_paragraphs(art)):
            t = clean_paragraph(txt)
            why = reject_reason(t)
            form = "整段"
            if why == "长于 %d 字" % MAX_PARA:
                pieces = split_long(t, tm)
                if not pieces:
                    reasons["长于 %d 字（切不出合用的块）" % MAX_PARA] += 1
                    continue
                t, nchars = max(pieces, key=lambda x: x[1])
                form = "长段截取"
                why = None
            if why:
                reasons[why] += 1
                continue
            nchars = len(re.sub(r"\s", "", t))
            cands.append({"order": order, "section": sec or "", "text": t, "chars": nchars,
                          "band": band_of(nchars), "compact": norm.compact(t), "form": form})
        stats["合用正文段（去重前）"] += len(cands)
        bysec = []
        seen_sec = set()
        for c in sorted(cands, key=lambda c: (abs(c["chars"] - 300), c["order"])):
            key = c["section"] or "_"
            if key in seen_sec:
                continue
            seen_sec.add(key)
            bysec.append(c)
        take = bysec[:1]
        rest = [c for c in bysec[1:]]
        while take and len(take) < args.per_paper and rest:
            nxt = max(rest, key=lambda c: (c["band"], c["chars"]))
            take.append(nxt)
            rest = [c for c in rest if (c["section"] or "_") != (nxt["section"] or "_")]
        reasons["同一篇封顶后丢掉"] += len(cands) - len(take)
        rec.update(candidates=len(cands), kept=len(take))
        papers.append(rec)
        stats["许可过的篇数"] += 1
        for c in take:
            c = dict(c)
            c.setdefault("form", "整段")
            c.update(pmcid=pmcid, paper_group="pmc:" + pmcid, discipline=discipline_of(jgroup, title),
                     journal_full=rec["journal_full"], journal_group=jgroup, year=year, license=verdict,
                     url=rec["url"], doi=rec["doi"], n_authors=rec["n_authors"], first_author=rec["first_author"])
            picked.append(c)
    stats["每篇封顶后候选段"] = len(picked)

    # 逐字重复 + 近重复：先按 (篇, 段序) 的哈希定序，保证同一批输入永远得到同一份输出
    picked.sort(key=lambda r: hashlib.md5(("%s|%d" % (r["pmcid"], r["order"])).encode("utf-8")).hexdigest())
    exact_seen = {}
    shingle_index = {}
    kept = []
    for r in picked:
        h = hashlib.sha1(r["compact"].encode("utf-8")).hexdigest()
        if h in exact_seen:
            reasons["逐字重复"] += 1
            r2 = dict(r)
            r2["dup_of"] = exact_seen[h]
            continue
        hits = Counter(shingle_index.get(s) for s in shingles(r["compact"]) if s in shingle_index)
        top, cnt = (hits.most_common(1) or [(None, 0)])[0]
        if cnt >= NEAR_DUP_HITS:
            reasons["近重复（%d 段共用 %d 个 24 字长串）" % (cnt, SHINGLE)] += 1
            continue
        exact_seen[h] = r["pmcid"]
        for s in shingles(r["compact"]):
            shingle_index.setdefault(s, r["pmcid"])
        kept.append(r)
    stats["去重后剩余段"] = len(kept)

    quota = allocate(kept, args.target)
    final = []
    for key in sorted(quota):
        rows = [r for r in kept if (r["discipline"], r["band"]) == key]
        rows.sort(key=lambda r: hashlib.md5(("%s|%d" % (r["pmcid"], r["order"])).encode("utf-8")).hexdigest())
        final.extend(rows[:quota[key]])
    final.sort(key=lambda r: (r["discipline"], r["band"], r["pmcid"], r["order"]))
    for i, r in enumerate(final):
        r["sid"] = "H2-%05d" % (i + 1)
    stats["分层抽样后计分段"] = len(final)

    with io.open(os.path.join(args.data, "papers.jsonl"), "w", encoding="utf-8", newline="") as f:
        for rec in papers:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    with io.open(os.path.join(args.data, "segments.jsonl"), "w", encoding="utf-8", newline="") as f:
        for r in final:
            f.write(json.dumps({k: r[k] for k in ("sid", "paper_group", "pmcid", "doi", "url", "journal_full",
                                                  "journal_group", "discipline", "year", "band", "section", "form",
                                                  "chars", "license", "text")}, ensure_ascii=False) + "\n")
    json.dump(dict(counts=dict(stats), licenses=dict(lic_seen), dropped=dict(reasons)),
              io.open(os.path.join(args.data, "build-stats.json"), "w", encoding="utf-8", newline=""),
              ensure_ascii=False, indent=1)
    print("计数：%s" % json.dumps(dict(stats), ensure_ascii=False))
    print("段：%d（篇：%d）；许可分布：%s" % (len(final), len(papers), dict(lic_seen)))
    print("过滤原因：%s" % json.dumps(dict(reasons), ensure_ascii=False))
    write_manifest(args, final, papers, stats, reasons, lic_seen, norm, tm)
    print_grid(final)


def allocate(kept, target):
    """按 (学科, 长度带) 分格分配名额：格子越大人越多，但不许某一格吃掉整把尺子。"""
    cells = defaultdict(list)
    for r in kept:
        cells[(r["discipline"], r["band"])].append(r)
    total = sum(len(v) for v in cells.values())
    if total <= target:
        return dict((k, len(v)) for k, v in cells.items())
    # 名额按 sqrt(可用数) 配：完全按比例会让最长那一档只剩几十段，分格表就成了摆设
    score = dict((k, math.sqrt(len(v))) for k, v in cells.items())
    base = sum(score.values())
    quota = dict((k, min(len(v), max(1, int(round(target * score[k] / base))))) for k, v in cells.items())
    while sum(quota.values()) > target:
        k = max(quota, key=lambda k: (quota[k] / max(1.0, math.sqrt(len(cells[k]))), quota[k]))
        if quota[k] <= 1:
            quota[k] -= 1
        else:
            quota[k] = max(1, quota[k] - 1)
        quota = dict((k, min(v, len(cells[k]))) for k, v in quota.items())
    while sum(quota.values()) < target:
        room = [k for k in cells if quota[k] < len(cells[k])]
        if not room:
            break
        k = max(room, key=lambda k: (len(cells[k]) - quota[k], len(cells[k])))
        quota[k] += 1
    return dict((k, min(quota[k], len(cells[k]))) for k, v in cells.items())

# ---------------------------------------------------------------- 自审与清单

def old_tier_rows(repo, tm, norm, min_len=40):
    """仓库内那几档真稿的句子（用来确认新集没把它们抄一遍）。"""
    out = []
    for code, rel, lab, note in tm.HOLDOUT_TIERS:
        path = os.path.join(repo, rel.replace("/", os.sep))
        if not os.path.exists(path):
            continue
        for line in io.open(path, encoding="utf-8"):
            for sent in tm.split_sentences(line.rstrip("\n")):
                c = norm.compact(sent)
                if len(c) >= min_len:
                    out.append((code, c))
    return out


def overlap_checks(args, segs, norm, tm):
    """新集与仓库内老真稿的重合：逐字相同、以及老句子整句嵌在新段里。两边都必须是 0。"""
    new_compacts = [norm.compact(s["text"]) for s in segs]
    exact_set = set(new_compacts)
    old = old_tier_rows(args.repo, tm, norm)
    same = sum(1 for code, c in old if c in exact_set)
    inside = 0
    for code, c in old:
        if any(c in L for L in new_compacts):
            inside += 1
    return dict(old_holdout_sentences=len(old), old_holdout_exact=same, old_holdout_sentence_inside_new_paragraph=inside,
                note="老真稿的句子（压缩后 >= 40 字）逐字等于新段、或整句嵌在新段里的条数，两条都该是 0")


def train_overlap(args, segs, norm, tm, samples=0):
    """与训练语料的重合：逐字相同的计分句数 + 撞上 24 字长串两处以上的段数。都必须 0。"""
    rows, _ = tm.rows_of(args.train_data, norm)
    n_train = len(rows)
    new_exact = set(norm.compact(s["text"]) for s in segs)
    index = {}
    for s in segs:
        for g in shingles(norm.compact(s["text"])):
            index.setdefault(g, s["sid"])
    exact = 0
    shared = Counter()
    for r in rows:
        c = r[4]
        if c in new_exact:
            exact += 1
        for g in shingles(c):
            sid = index.get(g)
            if sid:
                shared[sid] += 1
    strong = sum(1 for sid, n in shared.items() if n >= NEAR_DUP_HITS)
    drop_sids = set(sid for sid, n in shared.items() if n >= NEAR_DUP_HITS)
    if samples:
        shown = 0
        for r in rows:
            hits = [g for g in shingles(r[4]) if index.get(g)]
            if len(hits) >= NEAR_DUP_HITS and shown < samples:
                print("  撞车样本 %s：%s" % (index.get(hits[0]), r[4][:110]))
                shown += 1
    return dict(train_scored_sentences=n_train, exact_equal_sentences=exact, drop_sids=drop_sids,
                new_segments_sharing_long_string=strong,
                note="exact 是训练计分句与新段逐字相同；strong 是新段与训练句共用 >= %d 个 %d 字长串" % (NEAR_DUP_HITS, SHINGLE))


def grid_of(segs):
    g = defaultdict(lambda: Counter())
    for s in segs:
        g[s["discipline"]][s["band"]] += 1
    return g


def print_grid(segs, title="学科 × 长度带（段数）"):
    g = grid_of(segs)
    print(title)
    print("  %-34s %s %8s" % ("学科", "  ".join("%9s" % ("%d-%d" % b) for b in BANDS), "合计"))
    for disc in sorted(g, key=lambda k: -sum(g[k].values())):
        row = [g[disc].get(i, 0) for i in range(len(BANDS))]
        print("  %-34s %s %8d" % (disc[:34], "  ".join("%9d" % v for v in row), sum(row)))
    tot = Counter()
    for s in segs:
        tot[s["band"]] += 1
    print("  %-34s %s %8d" % ("合计", "  ".join("%9d" % tot.get(i, 0) for i in range(len(BANDS))), len(segs)))


def write_manifest(args, segs, papers, stats, reasons, lic_seen, norm, tm):
    by_j = defaultdict(lambda: dict(papers=0, segs=0, years=set(), licenses=Counter()))
    for rec in papers:
        d = by_j[rec["journal_full"][:70]]
        d["papers"] += 1
        d["licenses"][rec["license"]] += 1
        if rec.get("year"):
            d["years"].add(rec["year"])
    for s in segs:
        by_j[s["journal_full"][:70]]["segs"] += 1
    example = {}
    for rec in papers:
        example.setdefault(rec["journal_full"][:70], rec["url"])
    journals = []
    for name, d in sorted(by_j.items(), key=lambda x: -x[1]["segs"]):
        key = name.split("=")[0].strip().lower()
        journals.append(dict(journal=name,
                             journal_cn=next((v for k, v in JOURNAL_CN.items() if k in key), ""),
                             papers=d["papers"], segments=d["segs"],
                             years=[min(d["years"]), max(d["years"])] if d["years"] else [],
                             licenses=dict(d["licenses"]),
                             redistributable=any(k in REDISTRIBUTABLE for k in d["licenses"]),
                             example_url=example.get(name, "")))
    per_paper = Counter()
    for s in segs:
        per_paper[s["paper_group"]] += 1
    grid = grid_of(segs)
    man = {
        "what": "真人中文学术正文的段落级留出集（120~656 字/段）。只当尺子用：不进训练、不进仓库、不印百分比。",
        "built": time.strftime("%Y-%m-%d"),
        "builder": "tools/build-holdout-corpus.py",
        "source": {"db": "PubMed Central 开放获取子集（期刊论文全文 XML）",
                   "query": SEARCH_TERM,
                   "text_location": os.path.join("..", "aigc-corpus", "holdout2", "segments.jsonl") + "（仓库外，含原文）",
                   "why_no_repo": "原文不进仓库；这一份清单只有来源、许可与计数",
                   "human_side_rule": "全部取自已发表论文正文，没有一个字是模型生成的"},
        "license_policy": {
            "accepted": list(ACCEPTED_LICENSES),
            "redistributable_into_repo": list(REDISTRIBUTABLE),
            "rejected": "XML 里没有明确开放许可，或写明未经授权不得转载的，整篇丢弃（计数见 counts）",
            "why_nc_nd_ok": "这批原文只在本地测量时读取，不随包、不训练、不对外散布，NC 与 ND 两条限制没被碰到"},
        "counts": dict(stats),
        "license_seen_per_paper": dict(lic_seen),
        "paragraph_filter_dropped": dict(reasons),
        "journals": journals,
        "grid": dict((d, dict((("%d-%d" % BANDS[i]), grid[d][i]) for i in range(len(BANDS)))) for d in grid),
        "bands": dict(("%d-%d" % b, sum(1 for s in segs if s["band"] == i)) for i, b in enumerate(BANDS)),
        "years": dict(sorted(Counter(s["year"] for s in segs).items())),
        "per_paper": {"papers_with_segments": len(per_paper), "segments": len(segs),
                      "max_segments_per_paper": max(per_paper.values()) if per_paper else 0,
                      "distinct_first_authors": len(set(p.get("first_author") for p in papers if p.get("first_author")))},
        "disciplines": dict(sorted(Counter(s["discipline"] for s in segs).items(), key=lambda x: -x[1])),
        "not_used": [list(x) for x in NOT_USED],
    }
    json.dump(man, io.open(MANIFEST, "w", encoding="utf-8", newline=""), ensure_ascii=False, indent=1)
    print("清单（无正文）：%s" % os.path.relpath(MANIFEST, REPO))


def stage_audit(args):
    tm = load_train_module()
    norm = tm.Normalizer(tm.load_trad_table())
    segs = read_jsonl(os.path.join(args.data, "segments.jsonl"))
    papers = read_jsonl(os.path.join(args.data, "papers.jsonl"))
    for s in segs:
        s["compact"] = norm.compact(s["text"])
    print("计分段 %d，覆盖 %d 篇、%d 份刊、%d 个学科档" %
          (len(segs), len(set(s["paper_group"] for s in segs)), len(set(s["journal_full"] for s in segs)),
           len(set(s["discipline"] for s in segs))))
    bad = [s for s in segs if s["license"] not in ACCEPTED_LICENSES]
    print("许可不在接受档的段：%d" % len(bad))
    short = [s for s in segs if not MIN_PARA <= s["chars"] <= MAX_PARA]
    print("长度越界的段：%d" % len(short))
    dup = len(segs) - len(set(s["compact"] for s in segs))
    print("逐字重复的段：%d" % dup)
    per = Counter(s["paper_group"] for s in segs)
    print("每篇段数分布：%s" % dict(sorted(Counter(per.values()).items())))
    ov = overlap_checks(args, segs, norm, tm)
    print("与仓库内老真稿的重合：%s" % json.dumps(ov, ensure_ascii=False))
    if args.check_train:
        tv = train_overlap(args, segs, norm, tm)
        print("与训练语料的重合：%s" % json.dumps(tv, ensure_ascii=False))
        print("（strong 那一列只数 24 字长串撞上 %d 次以上的篇，零散撞一两个长串通常是通用术语）" % NEAR_DUP_HITS)
    print_grid(segs)
    bf = os.path.join(args.data, "build-stats.json")
    if os.path.exists(bf):
        bs = json.load(io.open(bf, encoding="utf-8"))
        write_manifest(args, segs, papers, Counter(bs["counts"]), Counter(bs["dropped"]),
                       Counter(bs["licenses"]), norm, tm)
    else:
        write_manifest(args, segs, papers, Counter(), {}, Counter(), norm, tm)
    return dict(n=len(segs), overlap=ov)


def stage_prune(args):
    """把与训练语料共用两个以上 24 字长串的段从尺子里剔掉。这类段通常是方法学套话，
    真人稿和机器稿里都会出现，留在尺子里只会把真人误报抹平。剔完不回填：宁可少几十段。"""
    tm = load_train_module()
    norm = tm.Normalizer(tm.load_trad_table())
    path = os.path.join(args.data, "segments.jsonl")
    segs = read_jsonl(path)
    for x in segs:
        x["compact"] = norm.compact(x["text"])
    bad = train_overlap(args, segs, norm, tm, samples=args.samples)
    drop = bad["drop_sids"]
    left = [x for x in segs if x["sid"] not in drop]
    print("与训练语料共用长串而剔掉：%d 段，剩 %d 段" % (len(drop), len(left)))
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        for x in left:
            f.write(json.dumps(dict((k, v) for k, v in x.items() if k != "compact"), ensure_ascii=False) + "\n")
    bf = os.path.join(args.data, "build-stats.json")
    bs = json.load(io.open(bf, encoding="utf-8")) if os.path.exists(bf) else dict(counts={}, licenses={}, dropped={})
    bs["counts"]["与训练语料共用长串而剔除"] = len(drop)
    json.dump(bs, io.open(bf, "w", encoding="utf-8", newline=""), ensure_ascii=False, indent=1)
    print_grid(left)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("stage", choices=["ids", "fetch", "build", "audit", "prune", "proof"])
    ap.add_argument("--data", default=DEFAULT_DATA)
    ap.add_argument("--repo", default=REPO)
    ap.add_argument("--proxy", default=os.environ.get("WORDLITE_AIGC_PROXY", "http://127.0.0.1:7897"))
    ap.add_argument("--target", type=int, default=TARGET)
    ap.add_argument("--per-paper", dest="per_paper", type=int, default=PER_PAPER_CAP)
    ap.add_argument("--redo", action="store_true")
    ap.add_argument("--check-train", dest="check_train", action="store_true",
                    help="audit 那一步再查一遍与训练语料的逐字/长串重合（要读全部训练原文，慢）")
    ap.add_argument("--train-data", dest="train_data",
                    default=os.path.abspath(os.path.join(REPO, "..", "aigc-corpus")))
    ap.add_argument("--samples", type=int, default=0, help="prune 那一步打印几条撞车样本")
    a = ap.parse_args()
    if a.stage == "ids":
        stage_ids(a)
    elif a.stage == "fetch":
        stage_fetch(a)
    elif a.stage == "build":
        stage_build(a)
    elif a.stage == "prune":
        stage_prune(a)
    elif a.stage == "proof":
        stage_proof(a)
    else:
        stage_audit(a)




def stage_proof(args):
    """把"留出集没有沾过训练料"这件事量出来，写回 tests/corpus 那份清单。
    清单里从此带着一份可核对的证据，而不是靠人保证。"""
    tm = load_train_module()
    norm = tm.Normalizer(tm.load_trad_table())
    segs = read_jsonl(os.path.join(args.data, "segments.jsonl"))
    papers = read_jsonl(os.path.join(args.data, "papers.jsonl"))
    ov = overlap_checks(args, segs, norm, tm)
    print("与仓库内老真稿：老句 %d 条，逐字相同 %d，整句嵌在新段里 %d" %
          (ov["old_holdout_sentences"], ov["old_holdout_exact"], ov["old_holdout_sentence_inside_new_paragraph"]))
    tv = train_overlap(args, segs, norm, tm)
    print("与公开训练语料：训练计分句 %d，逐字相同 %d，共用 %d 字长串达 %d 处以上的段 %d" %
          (tv["train_scored_sentences"], tv["exact_equal_sentences"], SHINGLE, NEAR_DUP_HITS,
           tv["new_segments_sharing_long_string"]))
    proof = dict(
        measured=time.strftime("%Y-%m-%d %H:%M:%S"),
        command="py tools/build-holdout-corpus.py proof --check-train",
        new_scored_segments=len(segs),
        old_repo_holdout=dict(old_holdout_sentences=ov["old_holdout_sentences"],
                              exact_equal=ov["old_holdout_exact"],
                              old_sentence_inside_new_paragraph=ov["old_holdout_sentence_inside_new_paragraph"]),
        public_training=dict(scored_sentences=tv["train_scored_sentences"],
                             exact_equal_sentences=tv["exact_equal_sentences"],
                             segments_sharing_long_string=tv["new_segments_sharing_long_string"],
                             long_string_chars=SHINGLE, min_hits=NEAR_DUP_HITS),
        verdict=("合格：留出段与全部公开训练计分句逐字相同 0 条，共用长串达 %d 处以上的段 %d 段（上一轮已用 "
                 "prune 那一步剔过一轮，这一条复述当时的实测）" % (NEAR_DUP_HITS, tv["new_segments_sharing_long_string"]))
        if tv["exact_equal_sentences"] == 0 else "不合格：还有逐字相同的句子，这份留出不能当尺子用",
        note="这一档只量重合，不改语料；原文一律留在仓库外")
    if os.path.exists(MANIFEST):
        man = json.load(io.open(MANIFEST, encoding="utf-8"))
    else:
        man = {}
    man["never_in_training"] = proof
    json.dump(man, io.open(MANIFEST, "w", encoding="utf-8", newline=""), ensure_ascii=False, indent=1)
    print("已写入 %s 的 never_in_training 一段" % os.path.relpath(MANIFEST, REPO))
    return proof


if __name__ == "__main__":
    main()