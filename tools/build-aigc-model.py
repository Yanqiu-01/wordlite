# -*- coding: utf-8 -*-
"""
Wordlite 离线 AIGC 模型训练台（CPU，不微调语言模型）。

    py tools/build-aigc-model.py fetch     # 拉公开带标注语料（默认走系统代理，--proxy 可覆盖）
    py tools/build-aigc-model.py train     # 字符 n-gram + 词表 + 逻辑回归，公共留出量 AUC
    py tools/build-aigc-model.py gate      # 真稿独立留出 + 滑窗困惑度落点，两条出厂门槛一起判
    py tools/build-aigc-model.py export    # 导出随包权重（tsv）与清单（json）

纪律（改这个脚本前先读）：
* 原文一律不进仓库。仓库里只进派生统计量（权重表 + 字频表）与清单，许可见 CORPORA。
* 建模决定只看公共侧：配置选择用"公共侧跨域留出 AUC 的均值"——轮流把整个域从拟合侧撤掉，只在被
  撤掉那个域的公共留出上量分；学术配对档与逐域明细一并落 train-summary.json。
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
MODEL_VERSION = "ngram-lr-2026-10-09b"

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
    dict(code="setask8zh", kind="zh", license="MIT（镜像卡声明；上游 GitHub 取不到，见 not_used）", ship_raw=False, anon=True,
         url="https://huggingface.co/datasets/anyangsong/SemEval2024-Task8-SubtaskA/resolve/main/chinese/subtaskA_train_chinese.jsonl",
         file="setask8_zh_train.jsonl",
         note="SemEval-2024 Task 8 中文子任务训练集：真人 6,000 / chatGPT 2,970 / davinci 2,964（网络社区口语域，不是学术域）"),
    dict(code="anxzh", kind="zh", license="MIT", ship_raw=False, anon=True,
         url="https://huggingface.co/datasets/AnxForever/chinese-ai-detection-dataset/resolve/main/train.csv",
         file="anx_zh_train.csv",
         note="中文 AI 检测集（MIT）：THUCNews 真人新闻 7,971 + 同一批的 AI 润色稿 C4 1,949 + 现代模型整篇稿 "
              "parallel_* 7,804（gpt-4.1-mini / gpt-5(cursor) / gemini-2.5-flash / claude-haiku-4.5 / Kimi-K2 / deepseek-v3.2）"
              " + C3 改写 1,265 + auto_* 1,346 + Human 3,535；HC3 重复副本与 C2 人机混合稿不进训练"),
    dict(code="magazh", kind="zh", license="MIT（数据集卡声明；卡里内嵌的人类源文本上游另有约束，见 not_used）",
         ship_raw=False, anon=True,
         url="https://huggingface.co/datasets/anyangsong/MAGA-cn/resolve/main/train/MGB-cn_train.jsonl",
         file="mgb_cn_train.jsonl",
         note="MAGA-Bench 中文基线档（arXiv:2601.04633，机器侧全部是 2024-2025 的模型）：10 个中文域 × 13 台模型整篇生成 "
              "DeepSeek-V3 / DeepSeek-R1-0528-Qwen3-8B / Qwen3-plus / Qwen3-8B / Hunyuan-7B-Instruct / Hunyuan-TurboS / "
              "GPT-4o-mini / Gemini-2.0-flash / Llama-3.1-8B-Instruct / gemma-3-12b-it / Ministral-8B / Mistral-Medium；"
              "真人侧是同一批提示词的人类原文，CSL 那一档是中文核心期刊论文摘要原文，CLTS 那一档是中文新闻特稿"),
    dict(code="magaaug", kind="zh", license="MIT（同上，数据集卡声明）", ship_raw=False, anon=True,
         url="https://huggingface.co/datasets/anyangsong/MAGA-cn/resolve/main/train/MAGA-cn_train.jsonl",
         file="maga_cn_train.jsonl",
         note="MAGA-Bench 中文 alignment-augment 档：同一批提示词在生成时挂一个与任务无关的人格/文风 system prompt"
              "（老舍、军人、侦探、八岁小孩……）。这是公开中文语料里唯一一档现代模型的强风格改写，"
              "对应真稿留出里 M-EVADE 那种规避档；域与机器侧模型与 magazh 同一套"),
    dict(code="drlxzh", kind="zh", license="MIT", ship_raw=False, anon=True,
         url="https://huggingface.co/datasets/WUJUNCHAO/DetectRL-X/resolve/main/Binary/binary_general_open.json",
         file="drlx_zh_general.jsonl", raw_file="drlx_general_open.json",
         note="DetectRL-X（ACL 2026 shared task 侧公开集，MIT）中文子集：真人原文 vs DeepSeek-V3 / Gemini-2.5-Flash / "
              "GPT-4o / Qwen-Max 整篇生成，四个域 academic / news / webtext / seo；上游是一个约 900 MB 的 JSON 数组，"
              "取回后只把 lang=chinese 的行另存成 drlx_zh_general.jsonl（原文一样留在 ../aigc-corpus，不进仓库）"),
    dict(code="ateeqq", kind="en", license="MIT", ship_raw=False, anon=True,
         url="https://huggingface.co/datasets/Ateeqq/AI-and-Human-Generated-Text/resolve/main/train.csv",
         file="ateeqq_train.csv", note="英文论文摘要人写/AI 写。中文模型不用，留着给拉丁族"),
]
NOT_USED = [
    ("TUPE（任务书指定的多语平行学术语料，arXiv 2305.09992）",
     "对不上号，取不到：arXiv 2305.09992 打开是《A Fusion Model: Towards a Virtual, Physical and Cognitive "
     "Integration and its Principles》（VR/AR 融合模型），与 AI 文本检测无关；arXiv 检索 ti:\"TUPE\" 只命中一篇"
     "非线性扩散方程论文，all:\"Text Under Pinch\" 零命中；OpenAlex title.search:TUPE 全是英国劳动法（TUPE transfers）；"
     "HuggingFace datasets?search=TUPE 零命中；Semantic Scholar 检索返回 HTTP 429"),
    ("SemEval-2024 Task 8 上游仓库 mbzuai-nlp/SemEval2024-task8",
     "许可核对不上：api.github.com 与 github.com 从本机出口一律 HTTP 451，raw.githubusercontent.com 与 codeload 一律 404"
     "（同一个 raw 域名对 octocat/Hello-World 返回 200，所以不是网络不通）。中文侧只用 HF 镜像 "
     "anyangsong/SemEval2024-Task8-SubtaskA（数据集卡声明 license: MIT；另一镜像 d0rj/SemEval2024-task8 声明 apache-2.0），"
     "仓库里只放派生统计量"),
    ("anyangsong/COLING2025-MGT-Detection-Task1 中文（MIT，已取回 chinese_train.jsonl 39.6 MB）",
     "内容与上游重复：train 前 4,000 行 source 字段只有 hc3（3,073）与 m4gt（927），就是 HC3 与 SemEval-2024 Task 8 "
     "的重新打包，进了训练等于同一条句子数两遍"),
    ("AnxForever 的 val.csv / test.csv 与 anx 里的 hc3_human·hc3_chatgpt 副本、C2 人机混合稿",
     "公共留出用我们自己的分组十等分，官方划分不叠加；hc3_* 与上游 HC3 逐字重复；C2 是"
     "人写开头 + 机器续写（带 [SEP]），逐句归属分不清，整条丢"),
    ("COLING-2025 MGT-Detection Task 1 官方 test 中文那份（镜像 anyangsong/COLING2025-MGT-Detection-Task1，"
     "test_set_chinese_with_label.jsonl 178,161,905 字节，已取回）",
     "不进训练，两条理由：镜像卡自称 Unofficial Mirror，上游仓库从本机一律 451/404，许可核对不了；"
     "而且它是别人比赛的考题。只当外部对照量一次（tools/build-aigc-model.py external）："
     "63,009 篇 → 1,222,872 计分句，与出厂这份训练集逐字重合 0 句，句级 AUC 0.6240，"
     "真人误报 36.68 句/千句——换一把外部的尺子同样印不出百分比"),
    ("QiYuan-tech/LLM-Detector（中文，7 个国产模型 + GPT-4 与真人答案配对，最贴产品域）",
     "要登录接受条款：匿名请求 https://huggingface.co/api/datasets/QiYuan-tech/LLM-Detector 与 "
     "resolve/main/train_set.json 都是 **HTTP 401**（上一轮同样 401，本轮复核仍是 401）。"
     "取回步骤（需要人工过一次授权，本轮没有账号，故没取）：① 用本人 HuggingFace 账号登录 "
     "huggingface.co/datasets/QiYuan-tech/LLM-Detector，点 \"Accept and access\" 接受该库的使用条款；"
     "② 在 huggingface.co/settings/tokens 建一个有 read 权限的 access token；"
     "③ 带着 token 取文件：curl -L -H \"Authorization: Bearer $HF_TOKEN\" "
     "-x http://127.0.0.1:7897 -o ../aigc-corpus/qiyuan_train.json "
     "\"https://huggingface.co/datasets/QiYuan-tech/LLM-Detector/resolve/main/train_set.json\""
     "（或 pip install \"huggingface_hub[hf_transfer]\" 后 hf download QiYuan-tech/LLM-Detector --repo-type dataset "
     "--local-dir ../aigc-corpus）；④ 按本文件的规矩接进训练：只进派生统计量，原文留在 ../aigc-corpus，"
     "许可与来源写进随包清单，995 句真稿一个字不进训练"),
    ("koakuma/RealDet（ACL 2025，中英双语，15 个域 22 台模型，已取回中文侧两份：真人 5.8 MB / 机器 102 MB）",
     "许可不过：数据集卡写 cc-by-nc-4.0（仅限非商业使用），而这份模型是随 APK 出厂的，不做训练语料。"
     "内容本身也接不上产品域：中文侧没有域字段（每行只有 text/label），抽出来是网络问答（游戏加点、汽车维修这类），"
     "真人侧只有 10,545 行、机器侧 125,295 行（Claude-3 / DeepSeek / GPT-4o / 文心一言 / 通义千问 / 360GPT / Baichuan / ChatGLM-2 各一万上下）；"
     "README 里那个 Academic Writing 域是 Arxiv Abstracts，中文侧没有对应的学术正文。"
     "本轮只拿它当第二把外部的尺子量一次（py tools/build-aigc-model.py external --file ../aigc-corpus/realdet_*.jsonl 那两份合并），"
     "一个字不进训练"),
    ("任务书点名的 ModelScope FlagEvaldet 系列中文 AIGC 检测数据集",
     "匿名查不到这个库：ModelScope 的组织/数据集接口从本机一律不通或不认——"
     "GET https://www.modelscope.cn/api/v1/datasets?Owner=FlagEvaldet → HTTP 200 且 Data=[]（同一个 Owner 参数对 simpleai 返回 2 条，"
     "所以参数与网络都没问题）；Owner=FlagEval 同样 0 条；Query=FlagEvaldet / AIGC检测 / 中文AIGC / AI文本检测 → 全部 0 条"
     "（Query=HC3 能返回 simpleai/HC3-Chinese，说明检索本身可用）；GET api/v1/datasets/FlagEvaldet/<任意名> → HTTP 404 \"不存在的数据集\"；"
     "organizations 接口 → HTTP 404；HuggingFace 侧 api/datasets?author=FlagEvaldet 与 author=FlagEval 也是 0 条"
     "（author=FlagEval 只有 CLCC_v1 / HalluDial / ERQA 这些视觉与认知库，没有文本检测）。"
     "顺手把 ModelScope 上能查到的两个同名 CCKS2025-大模型生成文本检测 库（hyx111111/CCKS2025、ssssyyyyadc/ccks2025，"
     "声明 Apache-2.0）都打开看了：仓库树里只有 .gitattributes 与 README.md 两个文件，一个数据文件都没有，弃用"),
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
    ("HH", "tests/corpus/aigc-hard-human.txt", 0, "真人侧最难判的句（历史分数最高的真人句）"),
    ("M-RAW", "tests/corpus/aigc-label-machine-raw.txt", 1, "代理生成未编辑"),
    ("M-EVADE", "tests/corpus/aigc-label-machine-evasive.txt", 1, "代理生成且要求规避风格"),
    ("M-DOMAIN", "tests/corpus/aigc-label-machine-domain.txt", 1, "与 H1 同领域同主题逐段配对的机器稿"),
]
TEMPLATE_TIERS = [("TPL-CARTOON", "tests/corpus/aigc-cartoon.txt"), ("TPL-FRAMES", "tests/corpus/aigc-frames.txt")]

# 第二把尺子：段落级真人真稿（120~656 字/段，多篇、多作者、逐篇带许可）。
# 上面那 995 句真稿基本出自一份稿子，真人侧只有 624 句——"误报 <= 2 句/千句"这条门槛在那么小的分母上
# 量不准（2/1000 x 624 = 1.2 句，一句之差就翻盘）。新集把真人侧做到几千段，来源与许可逐篇记在
# tests/corpus/aigc-holdout2-manifest.json，原文留在仓库外；构建与自审见 tools/build-holdout-corpus.py。
HOLDOUT2_DEFAULT = os.path.join(DEFAULT_DATA, "holdout2", "segments.jsonl")
BANDS = [(120, 199), (200, 319), (320, 479), (480, 656)]
BAND_LABELS = ["120-199", "200-319", "320-479", "480-656"]
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
        dst = os.path.join(args.data, spec.get("raw_file") or spec["file"])
        if os.path.exists(dst) and os.path.getsize(dst) > 1024 and not args.redo:
            print("已有 %-24s %8.1f MB" % (os.path.basename(dst), os.path.getsize(dst) / 1e6)); continue
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
            print("取回 %-24s %8.1f MB  匿名=是  许可=%s" % (os.path.basename(dst), got / 1e6, spec["license"]))
        except Exception as e:
            print("失败 %-24s %s" % (spec["file"], str(e)[:70]))
    ensure_drlx_zh(args.data, redo=args.redo)
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


ANX_DROP = ("C2",)             # 人写开头 + 机器续写：逐句归属分不清，整条丢

# MAGA-Bench 中文十个域 → 本文件的域标签。CSL（中文核心期刊论文摘要）单独成一档并算进学术域，
# 其余九档是网络问答/评论/百科/新闻特稿，只作诊断用，不许冒充产品域。
MAGA_DOMAINS = {"CSL": "academic-maga", "CLTS": "maga-feature", "Baidu Baike": "maga-baike",
                "Zhihu": "maga-zhihu", "Baidu Tieba": "maga-tieba", "Baidu Zhidao": "maga-zhidao",
                "Douban Review": "maga-douban", "Dianping": "maga-dianping",
                "Rednote Review": "maga-rednote", "Weibo Review": "maga-weibo"}
MAGA_ACADEMIC = ("academic-maga",)


def ensure_drlx_zh(data_dir, redo=False):
    """DetectRL-X 上游是一个约 900 MB 的 JSON 数组（八种语言混在一起）。这里流式扫一遍，
    只把 lang=chinese 的行另存成 jsonl 留在语料目录里，训练侧读那份小的；原文一个字不进仓库。"""
    raw = os.path.join(data_dir, "drlx_general_open.json")
    dst = os.path.join(data_dir, "drlx_zh_general.jsonl")
    if os.path.exists(dst) and os.path.getsize(dst) > 1024 and not redo:
        return dst
    if not os.path.exists(raw):
        return dst
    dec = json.JSONDecoder()
    txt = io.open(raw, encoding="utf-8", errors="ignore").read()
    i, n, kept = txt.find("{"), 0, 0
    with io.open(dst, "w", encoding="utf-8", newline="\n") as f:
        while i >= 0:
            try:
                o, end = dec.raw_decode(txt, i)
            except ValueError:
                break
            n += 1
            if o.get("lang") == "chinese":
                f.write(json.dumps(o, ensure_ascii=False) + "\n")
                kept += 1
            i = txt.find("{", end)
    print("DetectRL-X：%d 行里留下 lang=chinese 的 %d 行 → %s" % (n, kept, os.path.basename(dst)))
    return dst


def domain_of(code, src):
    """公共侧的"域"：按文本群体划分，专门用来量域偏移（同域配对的分 vs 跨域迁移的分）。"""
    if code == "hc3zh":
        return src                       # hc3-baike / hc3-law / hc3-medicine / ... 分开算域
    if code == "pangda":
        return "web-query"
    if code.startswith("prhpp"):
        return "academic-abstract"
    if code == "setask8zh":
        return "web-ugc"
    if code == "anxzh":
        return src
    if code in ("magazh", "magaaug", "drlxzh"):
        return src
    return code


def rows_of(data_dir, norm, only=None):
    """[(group_id, source_code, label, raw_sentence, compact, domain)]，label 1 = 机器。
    group_id 里带来源行号/标题：同一条样本的人写与机器版本落进同一组，防止改写对跨划分泄漏。"""
    out, stats, dropped = [], Counter(), Counter()
    import csv
    ensure_drlx_zh(data_dir)                    # 有原始大数组但没抽过中文子集时先抽一次
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
            dom = code
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
                sub = row.get("source") or "?"
                gid = "hc3zh:%s:%d" % (sub, i)
                dom = "hc3-" + sub
                pairs = [(x, 0) for x in as_list(row.get("human_answers"))] + \
                        [(x, 1) for x in as_list(row.get("chatgpt_answers"))]
            elif code == "setask8zh":
                gid = "setask8zh:%s:%d" % (row.get("model") or "?", i)
                dom = "web-ugc"
                pairs = [(row.get("text") or "", int(row.get("label", 1)))]
            elif code == "anxzh":
                src = (row.get("source") or "").strip()
                cat = (row.get("category") or "").strip()
                if src.startswith("hc3"):                     # HC3 的重复副本：上游已直接取过，不重复计入
                    dropped["anx-hc3-dup"] += 1
                    continue
                if cat in ANX_DROP:
                    dropped["anx-C2-mixed"] += 1
                    continue
                lab = int(row.get("label", 0))
                if src == "thucnews" or cat == "C4":          # 同一批新闻的 AI 润色稿：与真人新闻同域配对
                    dom = "news"
                elif cat == "Human":
                    dom = "misc-human"
                else:                                         # parallel_* / auto_* / C3：现代模型整篇稿
                    dom = "essay"
                gid = "anxzh:%s:%d" % (cat or src, i)
                pairs = [(row.get("text") or "", lab)]
            elif code in ("magazh", "magaaug"):
                mdl_v = str(row.get("model") or "")
                lab = 0 if mdl_v == "human" else 1
                d0 = str(row.get("domain") or "?").strip()
                dom = MAGA_DOMAINS.get(d0, "maga-" + d0.lower().replace(" ", "-"))
                # 同一条人类原文与它的各个机器版本必须落进同一组（两份文件共用一个前缀，防跨划分泄漏）
                gid = "maga:%s" % (row.get("human_source_id") or row.get("id") or i)
                pairs = [(row.get("text") or "", lab)]
            elif code == "drlxzh":
                dom = "drlx-" + str(row.get("domain") or "?").strip().lower()
                gid = "drlxzh:%d" % i                      # 同一条指令的真人稿与机器稿在一行里，一起进同一组
                pairs = [(row.get("human_written_text") or "", 0),
                         (row.get("llm_generated_text") or "", 1)]
            else:
                gid = "%s:%s" % (code, row.get("title") or i)
                lab = int(row.get("label", 1)) if row.get("label") is not None else 1
                pairs = [(row.get("text") or "", lab)]
            for text, lab in pairs:
                for sent in split_sentences(text or ""):
                    c = norm.compact(sent)
                    if not scoreable(c):
                        continue
                    out.append((gid, code, lab, sent, c, domain_of(code, dom)))
                    stats[(code, lab)] += 1
    if dropped:
        print("丢弃：%s" % dict(dropped))
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


def holdout2_rows(path, norm):
    """段落级真人真稿：一行一段，整段算一个计分单位（应用侧对段落打分本来就没有 300 字上限，
    MIN_SENT~300 那条是训练侧的口径）。返回 [(sid, paper_group, discipline, band, raw, compact, license)]。
    文件不在就返回空表：新集缺了只影响新那一档，不许把老口径的量法悄悄改掉。"""
    if not os.path.exists(path):
        print("段落级真稿留出缺文件（%s）：只跑老的句级口径" % path)
        return []
    out = []
    for line in io.open(path, encoding="utf-8"):
        r = json.loads(line)
        out.append((r["sid"], r["paper_group"], r["discipline"], int(r["band"]), r["text"],
                    norm.compact(r["text"]), r.get("license") or ""))
    return out


def holdout2_stats(big, p_big, p_machine):
    """段落级那一档：AUC(机器>真人)、真人误报，再按 学科 x 长度带 分格。
    机器侧仍用真稿留出里那三个机器档的句子——新集只有真人侧，真人侧不许拿模型生成的东西补齐，
    所以这一档的 AUC 是"几千段真人 对 那几百句机器"，两边条数不对称，看数的时候记住这一条。"""
    hum = np.asarray(p_big, dtype=np.float64)
    mac = np.asarray(p_machine, dtype=np.float64)
    y = np.concatenate([np.zeros(len(hum)), np.ones(len(mac))])
    sc = np.concatenate([hum, mac])
    n = max(1, len(hum))
    out = dict(n=int(len(hum)), auc=round(auc(y, sc), 4),
               p_p50=round(float(np.percentile(hum, 50)), 4), p_p90=round(float(np.percentile(hum, 90)), 4),
               fp_per_mille_at_045=round(1000.0 * float(np.sum(hum >= FLAG_GATE)) / n, 3),
               fp_per_mille_at_050=round(1000.0 * float(np.sum(hum >= 0.5)) / n, 3),
               machine_n=int(len(mac)),
               machine_hit_percent_at_045=round(100.0 * float(np.sum(mac >= FLAG_GATE)) / max(1, len(mac)), 2))
    need = int(math.ceil(len(hum) * (1.0 - GATE_FP_PER_MILLE / 1000.0))) - 1
    thr = float(np.sort(hum)[max(0, min(need, len(hum) - 1))]) if len(hum) else 1.0
    out["operating_point"] = dict(
        threshold=round(thr, 4),
        machine_hit_percent=round(100.0 * float(np.sum(mac >= thr)) / max(1, len(mac)), 2),
        human_fp_per_mille=round(1000.0 * float(np.sum(hum >= thr)) / n, 3))

    def cell(sel):
        v = hum[sel]
        if not len(v):
            return None
        yy = np.concatenate([np.zeros(len(v)), np.ones(len(mac))])
        ss = np.concatenate([v, mac])
        return dict(n=int(len(v)), flag_percent=round(100.0 * float(np.mean(v >= FLAG_GATE)), 2),
                    fp_per_mille=round(1000.0 * float(np.sum(v >= FLAG_GATE)) / len(v), 2),
                    p_p50=round(float(np.percentile(v, 50)), 4), p_p90=round(float(np.percentile(v, 90)), 4),
                    auc_vs_machine=round(auc(yy, ss), 4))

    disc = np.array([r[2] for r in big])
    band = np.array([r[3] for r in big])
    out["by_discipline_band"] = dict(
        (d, dict([(BAND_LABELS[i], cell((disc == d) & (band == i))) for i in range(len(BANDS))] +
                 [("合计", cell(disc == d))])) for d in sorted(set(disc)))
    out["by_discipline"] = dict((d, cell(disc == d)) for d in sorted(set(disc)))
    out["by_band"] = dict((BAND_LABELS[i], cell(band == i)) for i in range(len(BANDS)))
    out["papers"] = len(set(r[1] for r in big))
    out["licenses"] = dict(Counter(r[6] for r in big))
    return out


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

CAP_PER_DOMAIN = 60000    # 每个域最多取多少计分句（按组哈希整组砍）：句数不许成为配置之间的差异
# 本轮新加的那三个配置（N/O/P）用 25,000/域：机器只有 16 GB（空载剩 5 GB），
# 17 个域 × 60,000 会在字符 2-4-gram 的 CountVectorizer 上撑爆内存。三个新配置之间这个上限一致，
# 与 13 个老配置比句数时这一条差异写在 docs/aigc-offline-model.md 的表注里。
CAP_PER_DOMAIN_NEW = 25000
ACADEMIC_DOMAINS = ("academic-abstract",)   # ② 学术配对档看的域（挪到 CONFIGS 之前：配置要拿它拼 keep_domains）

CONFIGS = [
    dict(name="A-all", only=None, ngram=(2, 4), min_df=20, C=1.0, cap=CAP_PER_DOMAIN),
    dict(name="B-prhpp-only", only=["prhpp", "prhpp_te", "prhppgen"], ngram=(2, 4), min_df=5, C=1.0),
    dict(name="C-hc3-only", only=["hc3zh"], ngram=(2, 4), min_df=20, C=1.0, cap=CAP_PER_DOMAIN),
    dict(name="D-no-hc3", only=["prhpp", "prhpp_te", "prhppgen", "pangda"], ngram=(2, 4), min_df=10, C=1.0,
         cap=CAP_PER_DOMAIN),
    dict(name="E-formal-mix", only=None, ngram=(2, 4), min_df=20, C=1.0, cap=CAP_PER_DOMAIN,
         drop=("hc3zh:open_qa:", "hc3zh:psychology:", "hc3zh:nlpcc_dbqa:")),
    dict(name="F-all-1234", only=None, ngram=(1, 4), min_df=20, C=1.0, cap=CAP_PER_DOMAIN),
    # 本轮新增：两批中文新语料（SemEval-2024 Task 8 中文子任务 + AnxForever 现代模型稿）
    dict(name="G-new-corpus", only=["prhpp", "prhpp_te", "prhppgen", "pangda", "setask8zh", "anxzh"],
         ngram=(2, 4), min_df=10, C=1.0, cap=CAP_PER_DOMAIN),
    dict(name="H-consistent", only=None, ngram=(2, 4), min_df=20, C=1.0, prune="domain-sign", cap=CAP_PER_DOMAIN),
    dict(name="I-new-consistent", only=["prhpp", "prhpp_te", "prhppgen", "pangda", "setask8zh", "anxzh"],
         ngram=(2, 4), min_df=10, C=1.0, prune="domain-sign", cap=CAP_PER_DOMAIN),
    dict(name="J-formal-paired", only=["prhpp", "prhpp_te", "prhppgen", "setask8zh", "anxzh"],
         ngram=(2, 4), min_df=8, C=1.0, balance="domain", prune="domain-sign", cap=CAP_PER_DOMAIN),
    dict(name="K-pairs-only", only=["prhpp", "prhpp_te", "prhppgen", "anxzh"],
         ngram=(2, 4), min_df=8, C=1.0, balance="domain", cap=CAP_PER_DOMAIN),
    dict(name="L-consistent-strong", only=None, ngram=(2, 4), min_df=20, C=1.0, prune="domain-sign",
         min_share=0.9, cap=CAP_PER_DOMAIN),
    dict(name="M-academic-news", only=["prhpp", "prhpp_te", "prhppgen", "anxzh"],
         ngram=(2, 4), min_df=8, C=1.0, balance="domain", cap=CAP_PER_DOMAIN, keep_domains=("academic-abstract", "news")),
    # 本轮新增：机器侧换到 2024-2025 的现代模型（MAGA-Bench 中文 + DetectRL-X 中文学术档），现代学术域单独成档
    dict(name="N-maga-academic", only=["prhpp", "prhpp_te", "prhppgen", "magazh"],
         ngram=(2, 4), min_df=8, C=1.0, cap=CAP_PER_DOMAIN_NEW, balance="domain",
         keep_domains=ACADEMIC_DOMAINS + MAGA_ACADEMIC),
    dict(name="O-modern-mix", only=["prhpp", "prhpp_te", "prhppgen", "magazh", "drlxzh"],
         ngram=(2, 4), min_df=10, C=1.0, cap=CAP_PER_DOMAIN_NEW, balance="domain"),
    dict(name="P-modern-evasive", only=["prhpp", "prhpp_te", "prhppgen", "magazh", "magaaug", "drlxzh"],
         ngram=(2, 4), min_df=10, C=1.0, cap=CAP_PER_DOMAIN_NEW, balance="domain", prune="domain-sign"),
]
CACHE = {}
LODO_MIN_ROWS = 400          # 一个域要两边各 >= 400 句才进跨域考核


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


def both_class_domains(y, dom, min_rows=LODO_MIN_ROWS):
    out = []
    for d in sorted(set(dom)):
        m = dom == d
        if int((m & (y == 0)).sum()) >= min_rows and int((m & (y == 1)).sum()) >= min_rows:
            out.append(d)
    return out


def domain_sign_share(X, y, dom, doms):
    """每个特征在各域里"机器均值 - 真人均值"的符号一致比例（只用拟合侧算，留出侧一个字都不碰）。
    比例低 = 这个特征在有些域里指向机器、在另一些域里指向真人，几乎肯定是语域标记而不是"机器写的"的证据。"""
    diffs = []
    for d in doms:
        m = (dom == d) & (y == 1)
        h = (dom == d) & (y == 0)
        diffs.append(np.asarray(X[m].sum(axis=0)).ravel() / max(1, int(m.sum())) -
                     np.asarray(X[h].sum(axis=0)).ravel() / max(1, int(h.sum())))
    D = np.vstack(diffs)
    overall = D.mean(axis=0)
    share = (np.sign(D) == np.sign(overall)).mean(axis=0)
    share[np.abs(overall) <= 1e-12] = 0.0            # 全零特征谈不上一致，直接不给名额
    return share, dict((d, i) for i, d in enumerate(doms))


def domain_balance_weight(y, dom, doms):
    """域内类别配平：每个 (域, 类别) 组权重相同，大域不能靠句数把小域的声音盖掉。"""
    w = np.ones(len(y), dtype=np.float64)
    for d in doms:
        for lab in (0, 1):
            m = (dom == d) & (y == lab)
            if m.sum():
                w[m] = 1.0 / float(m.sum())
    other = ~np.isin(dom, doms)
    if other.sum():
        for lab in (0, 1):
            m = other & (y == lab)
            if m.sum():
                w[m] = 1.0 / float(m.sum())
    return w * (len(w) / w.sum())


def lr(C, max_iter=3000):
    return LogisticRegression(C=C, max_iter=max_iter, solver="liblinear")


def fit_side(X, y, sel, cols_w=None, C=1.0, max_iter=3000):
    m = lr(C, max_iter)
    m.fit(X[sel], y[sel], sample_weight=None if cols_w is None else cols_w[sel])
    return m


def cap_by_domain(comps_gids, doms_row, cap):
    """每个域最多留 cap 句：按 (group_id 哈希) 排序取前若干组，直到够了为止。同一组的人写与机器版本一起进或一起不出。"""
    if not cap:
        return np.ones(len(doms_row), dtype=bool)
    keep = np.zeros(len(doms_row), dtype=bool)
    for d in set(doms_row):
        idx = np.where(doms_row == d)[0]
        if len(idx) <= cap:
            keep[idx] = True
            continue
        groups = {}
        for i in idx:
            groups.setdefault(comps_gids[i], []).append(i)
        order = sorted(groups, key=lambda g: int(hashlib.md5((d + "|" + g).encode("utf-8")).hexdigest()[:8], 16))
        used = 0
        for g in order:
            if used >= cap:
                break
            for i in groups[g]:
                keep[i] = True
            used += len(groups[g])
    return keep


def fit_config(data_dir, norm, cfg, want_lodo=True, verbose=True):
    rows, stats = rows_of(data_dir, norm, only=cfg.get("only"))
    if cfg.get("drop"):
        rows = [r for r in rows if not any(r[0].startswith(d) for d in cfg["drop"])]
    gids = np.array([r[0] for r in rows])
    codes = np.array([r[1] for r in rows])
    doms_row = np.array([r[5] for r in rows])
    y = np.array([r[2] for r in rows])
    comps = [r[4] for r in rows]
    if cfg.get("keep_domains"):
        keep_d = np.isin(doms_row, list(cfg["keep_domains"]))
        rows = [r for r, k in zip(rows, keep_d) if k]
        gids, codes, doms_row, y, comps = (np.array([r[0] for r in rows]), np.array([r[1] for r in rows]),
                                           np.array([r[5] for r in rows]), np.array([r[2] for r in rows]),
                                           [r[4] for r in rows])
    kept = cap_by_domain(list(gids), doms_row, cfg.get("cap"))
    if not kept.all():
        print("[%s] 每域上限 %d 句：%d -> %d（被砍掉的是句数最多的那几个域，按组哈希整组砍）" %
              (cfg["name"], cfg["cap"], len(rows), int(kept.sum())))
        rows = [r for r, k in zip(rows, kept) if k]
        gids, codes, doms_row, y, comps = (np.array([r[0] for r in rows]), np.array([r[1] for r in rows]),
                                           np.array([r[5] for r in rows]), np.array([r[2] for r in rows]),
                                           [r[4] for r in rows])
    fv = np.array([fold_of(g) for g in gids])
    tr, va = fv != 0, fv == 0
    # 跨语料逐字重复：同一条 HC3 句子在镜像集里也出现过，训练侧必须剔掉公共留出里出现过的原句
    va_set = set(comps[i] for i in np.where(va)[0])
    dup = np.array([c in va_set for c in comps]) & tr
    n_dup = int(dup.sum())
    tr = tr & ~dup
    bd = both_class_domains(y[tr], doms_row[tr])
    vec = CountVectorizer(analyzer="char", ngram_range=tuple(cfg["ngram"]), lowercase=False,
                          min_df=cfg["min_df"])
    vec.fit([comps[i] for i in np.where(tr)[0]])
    X = vectors(comps, vec)
    share = None
    if cfg.get("prune") == "domain-sign":
        share, _ = domain_sign_share(X, y, doms_row, both_class_domains(y, doms_row))
    keep_mask = None if share is None else (share >= cfg.get("min_share", 0.75))
    sw = domain_balance_weight(y, doms_row, bd) if cfg.get("balance") == "domain" else None
    m1 = fit_side(X, y, tr, sw, cfg["C"])
    w_all = m1.coef_[0]
    rank = np.abs(w_all) if keep_mask is None else np.where(keep_mask, np.abs(w_all), -1.0)
    cols = np.sort(np.argsort(-rank)[:KEEP_FEATURES])
    sub = X[:, cols]
    sub = sub.multiply((1.0 / np.maximum(np.sqrt(np.asarray(sub.multiply(sub).sum(axis=1)).ravel()), 1e-9))[:, None]).tocsr()
    m2 = fit_side(sub, y, tr, sw, cfg["C"])
    p = m2.decision_function(sub)
    kept_share = None if share is None else share[cols]
    per = {}
    for code in sorted(set(codes)):
        m = va & (codes == code)
        if m.sum() > 40 and 0 < y[m].sum() < m.sum():
            per[code] = dict(n=int(m.sum()), auc=round(auc(y[m], p[m]), 4))
    per_dom = {}
    for d in sorted(set(doms_row)):
        m = va & (doms_row == d)
        if m.sum() > 40 and 0 < y[m].sum() < m.sum():
            per_dom[d] = dict(n=int(m.sum()), human=int((y[m] == 0).sum()), machine=int((y[m] == 1).sum()),
                              auc=round(auc(y[m], p[m]), 4))
    # 跨域迁移：轮流把整个域从拟合侧拿掉，只在被拿掉那个域的公共留出上量分（真稿留出仍一个字不看）
    lodo = {}
    if want_lodo:
        for d in both_class_domains(y, doms_row):
            sel = tr & (doms_row != d)
            te = va & (doms_row == d)
            if int(sel.sum()) < 1000 or int(te.sum()) < 200:
                continue
            mm = fit_side(sub, y, sel, sw, cfg["C"], max_iter=300)
            lodo[d] = dict(n=int(te.sum()), n_train=int(sel.sum()),
                           auc=round(auc(y[te], mm.decision_function(sub[te])), 4))
    res = dict(name=cfg["name"], rows=rows, y=y, tr=tr, va=va, vec=vec, cols=cols, model=m2,
               vocab=len(vec.vocabulary_), n=len(rows), n_sent=int(tr.sum()), n_dup=n_dup,
               auc_tr=round(auc(y[tr], p[tr]), 4), auc_va=round(auc(y[va], p[va]), 4),
               per=per, per_domain=per_dom, lodo=lodo, share_kept=kept_share,
               domains=sorted(set(doms_row)), both_class=both_class_domains(y, doms_row),
               cfg=cfg, scores=p, stats=stats)
    if verbose:
        print("[%s] 句 %-8d 重复剔除 %-6d 词表 %-7d 导出 %-5d  公共拟合 %.4f 公共留出 %.4f  分域 %s" %
              (res["name"], res["n"], res["n_dup"], res["vocab"], len(cols), res["auc_tr"], res["auc_va"],
               {k: v["auc"] for k, v in sorted(per_dom.items())}))
        if lodo:
            vals = [v["auc"] for v in lodo.values()]
            print("      跨域留出（轮流撤掉整个域再考它）均值 %.4f 最差 %.4f  明细 %s" %
                  (float(np.mean(vals)), float(np.min(vals)), {k: v["auc"] for k, v in sorted(lodo.items())}))
    return res


def academic_proxy(res):
    """公共侧唯一两份同域配对档之一：学术摘要（人写原稿 vs ChatGPT 润色）。只用于对照旧口径。"""
    vals = [res["per"][k]["auc"] for k in ("prhpp", "prhpp_te") if k in res["per"]]
    return min(vals) if len(vals) == 2 else None


def modern_academic_proxy(res):
    """本轮新加的一栏：学术域里"真人原文 vs 2024-2025 现代模型整篇生成"的公共留出 AUC
    （MAGA-Bench 的 CSL 档 = 中文核心期刊论文摘要）。量的是"机器侧换到现代模型上，同域还分不分得开"。
    与 academic_proxy 一样只看公共侧，不参与选择规则。"""
    v = res["per_domain"].get("academic-maga")
    return v["auc"] if v else None


def lodo_summary(res):
    vals = [v["auc"] for v in res["lodo"].values() if v["auc"] == v["auc"]]
    if not vals:
        return None
    return dict(n_domains=len(vals), mean=round(float(np.mean(vals)), 4), worst=round(float(np.min(vals)), 4),
                worst_domain=min(res["lodo"], key=lambda k: res["lodo"][k]["auc"]))


def stage_train(args):
    os.makedirs(ART, exist_ok=True)
    norm = Normalizer(load_trad_table())
    wanted = set(args.configs.split(",")) if args.configs else None
    results = []
    for cfg in CONFIGS:
        if wanted and cfg["name"] not in wanted:
            continue
        r = fit_config(args.data, norm, cfg, want_lodo=not args.no_lodo)
        r["proxy"] = academic_proxy(r)
        r["modern_proxy"] = modern_academic_proxy(r)
        r["lodo_sum"] = lodo_summary(r)
        results.append(r)
    if not results:
        raise SystemExit("没有匹配的配置：%s" % args.configs)
    # 选择规则（本轮之前写死的那条，只看公共侧）：产品要判的是学位论文正文，公共侧最贴这个域的
    # 就是学术配对档（人写原稿 vs 同一篇的机器润色），所以按"学术档留出 AUC"挑，跨域均值只作诊断。
    # 说明：把跨域均值当选择规则也试过（会选 A-all：跨域 0.8365 但真稿留出反而 0.4653），
    # 那条规则的优劣是我看了真稿成绩之后才判断的，不当它是事前规则，两栏数字都摆在表里。
    ok = [r for r in results if r["proxy"] is not None]
    if not ok:
        print("警告：这批配置都没有学术配对档，退回按公共留出挑——调试用，正式跑全量不许这样")
        ok = results
    pick = max(ok, key=lambda r: (r["proxy"], r["auc_va"]))
    for r in results:
        ls = r["lodo_sum"] or dict(mean=float("nan"), worst=float("nan"), n_domains=0)
        print("[%s] 公共留出 %.4f  学术档 %.4s  跨域均值 %.4f 跨域最差 %.4f（%d 个域）  同号特征 %s" %
              (r["name"], r["auc_va"], ("%.4f" % r["proxy"]) if r["proxy"] else "  —  ",
               ls["mean"], ls["worst"], ls["n_domains"],
               "-" if r["share_kept"] is None else "%.2f" % float(np.mean(r["share_kept"]))))
        print("      现代模型同域那一栏（学术域：真人摘要 vs 2024-2025 模型整篇生成）= %s" %
              (("%.4f" % r["modern_proxy"]) if r.get("modern_proxy") is not None else "这批配置里没有 MAGA 的学术档"))
    print("\n选择规则=公共侧学术档配对留出最高（不看真稿留出）：选 %s（学术档 %.4f，公共留出 %.4f）" %
          (pick["name"], pick["proxy"], pick["auc_va"]))
    summary = [dict(name=r["name"], n=r["n"], n_dup_with_valid=r["n_dup"], vocab=r["vocab"],
                    features=len(r["cols"]), auc_fit=r["auc_tr"], auc_valid=r["auc_va"],
                    academic_proxy=r["proxy"], modern_academic_proxy=r.get("modern_proxy"),
                    per_source=r["per"], per_domain=r["per_domain"],
                    lodo=r["lodo"], lodo_summary=r["lodo_sum"],
                    share_kept=None if r["share_kept"] is None else dict(
                        features=int(len(r["share_kept"])),
                        mean=round(float(np.mean(r["share_kept"])), 4),
                        p50=round(float(np.median(r["share_kept"])), 4),
                        min=round(float(np.min(r["share_kept"])), 4)),
                    cfg={k: v for k, v in r["cfg"].items() if k != "drop"})
               for r in results]
    json.dump(dict(picked=pick["name"], rule="公共侧学术配对档（人写原稿 vs 同一篇的 ChatGPT 润色）逐来源 AUC 最差值最大；"
                    "跨域均值与真稿留出都不参与选择",
                   configs=summary),
              io.open(os.path.join(ART, "train-summary.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    import pickle
    with open(os.path.join(ART, "model-cache.pkl"), "wb") as f:
        pickle.dump(dict(name=pick["name"], vec=pick["vec"], cols=pick["cols"], model=pick["model"],
                         cfg=pick["cfg"], rows=[(a, b, c, d, e, g) for a, b, c, d, e, g in pick["rows"]],
                         y=pick["y"], va=pick["va"], scores=pick["scores"],
                         domains=np.array([r[5] for r in pick["rows"]]),
                         per_domain=pick["per_domain"], lodo=pick["lodo"], lodo_sum=pick["lodo_sum"],
                         n_dup=pick["n_dup"], share_kept=pick["share_kept"]), f)
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

def stage_compare(args):
    """逐配置三档留出对照表 + "公共侧哪一栏预测得了真稿"的相关系数。
    这里只报数、不选配置（选择规则在 stage_train）；跨域 LODO 那一列由 train 那一步的
    train-summary.json 提供，这里不重跑（重跑一遍要多花十几分钟）。"""
    norm = Normalizer(load_trad_table())
    ho = holdout_rows(args.repo, norm)
    yh = np.array([r[1] for r in ho])
    big = holdout2_rows(getattr(args, "holdout2", HOLDOUT2_DEFAULT), norm)
    mach_mask = np.array([HOLDOUT_TIERS_ONLY_MACHINE(r[0]) for r in ho])
    out_rows = []
    wanted = set(args.configs.split(",")) if getattr(args, "configs", "") else None
    for cfg in CONFIGS:
        if wanted and cfg["name"] not in wanted:
            continue
        r = fit_config(args.data, norm, cfg, want_lodo=False, verbose=False)
        vec, cols, m2 = r["vec"], r["cols"], r["model"]
        y, va, scores = r["y"], r["va"], r["scores"]
        dom = np.asarray([q[5] for q in r["rows"]])
        hum = scores[va & (y == 0)]
        bias = 0.0
        for b in np.arange(0.0, -14.0, -0.02):
            if float(np.mean(sigmoid(hum + b) >= 0.5)) <= GATE_FP_PER_MILLE / 1000.0:
                bias = float(b)
                break

        def side(p, yy):
            nh = max(1, int((yy == 0).sum()))
            nm = max(1, int((yy == 1).sum()))
            return dict(n=int(len(yy)), auc=round(auc(yy, p), 4),
                        fp_per_mille=round(1000.0 * float(np.sum(p[yy == 0] >= FLAG_GATE)) / nh, 2),
                        machine_hit_percent=round(100.0 * float(np.sum(p[yy == 1] >= FLAG_GATE)) / nm, 2))

        vp = sigmoid(scores[va] + bias)
        yv, dv = y[va], dom[va]
        pub = side(vp, yv)
        mk = np.isin(dv, list(ACADEMIC_DOMAINS))
        aca = (side(vp[mk], yv[mk]) if mk.sum() and 0 < yv[mk].sum() < mk.sum()
               else dict(n=0, auc=None, fp_per_mille=None, machine_hit_percent=None))
        p = sigmoid(m2.decision_function(vectors([q[4] for q in ho], vec, cols)) + bias)
        real = side(p, yh)
        big_st = None
        if big:
            p_big = sigmoid(m2.decision_function(vectors([q[5] for q in big], vec, cols)) + bias)
            big_st = holdout2_stats(big, p_big, p[mach_mask])
        out_rows.append(dict(name=cfg["name"], n=r["n"], features=len(cols),
                             academic_proxy=academic_proxy(r), public=pub, academic=aca, holdout=real,
                             modern_academic_proxy=modern_academic_proxy(r), real_paragraph_scale=big_st))
        print("[%s] 句 %-7d  ① 公共 %.4f / 误报 %.2f  ② 学术 %s / 误报 %s  ③ 真稿 %.4f / 误报 %.2f / 机器过线 %.2f%%" %
              (cfg["name"], r["n"], pub["auc"], pub["fp_per_mille"],
               ("%.4f" % aca["auc"]) if aca["auc"] is not None else "  —  ",
               ("%.2f" % aca["fp_per_mille"]) if aca["fp_per_mille"] is not None else "  —  ",
               real["auc"], real["fp_per_mille"], real["machine_hit_percent"]), flush=True)
        if big_st:
            worst = sorted([(c["fp_per_mille"], d + " " + lab)
                            for d, row in big_st["by_discipline_band"].items()
                            for lab, c in row.items() if c and c["n"] >= 40], reverse=True)[:3]
            print("        ③c 段落级真稿 %.4f / 误报 %.2f 段/千段（%d 段）  误报最高的格子：%s" %
                  (big_st["auc"], big_st["fp_per_mille_at_045"], big_st["n"],
                   "  ".join("%s %.1f" % (nm, f) for f, nm in worst)), flush=True)

    def pear(a, b):
        if len(a) < 3:
            return None
        n = float(len(a))
        ma = sum(a) / n
        mb = sum(b) / n
        sab = sum((x - ma) * (y - mb) for x, y in zip(a, b))
        sa = sum((x - ma) ** 2 for x in a)
        sb = sum((y - mb) ** 2 for y in b)
        return None if sa <= 0 or sb <= 0 else round(sab / ((sa * sb) ** 0.5), 4)

    def col(key, sub="auc"):
        a, b = [], []
        for r in out_rows:
            v = r.get(key)
            v = v[sub] if isinstance(v, dict) else v
            if v is None:
                continue
            a.append(float(v))
            b.append(float(r["holdout"]["auc"]))
        return a, b

    pa, pb = col("public")
    aa, ab = col("academic")
    qa, qb = col("academic_proxy")
    ma, mb = col("modern_academic_proxy")
    corr = dict(n_configs=len(out_rows),
                public_auc=dict(r=pear(pa, pb), n=len(pa)),
                academic_auc=dict(r=pear(aa, ab), n=len(aa)),
                academic_proxy=dict(r=pear(qa, qb), n=len(qa)),
                modern_academic_proxy=dict(r=pear(ma, mb), n=len(ma)),
                note="对配置求相关（每行一个配置），不是对句子求相关；只描述，不参与选择")
    json.dump(dict(configs=out_rows, corr_with_holdout_auc=corr),
              io.open(os.path.join(ART, "compare.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("公共侧哪一栏预测得了真稿（对表里 %d 个配置求 Pearson r）：① 公共留出 %s  ② 学术配对档 %s  "
          "选择规则那一栏 %s  现代模型同域那一栏 %s" %
          (corr["n_configs"], corr["public_auc"]["r"], corr["academic_auc"]["r"], corr["academic_proxy"]["r"],
           corr["modern_academic_proxy"]["r"]))
    print("表与相关写 artifacts/agent-aigc-offline/compare.json")
    return dict(configs=out_rows, corr=corr)


EXTERNAL_URL = ("https://huggingface.co/datasets/anyangsong/COLING2025-MGT-Detection-Task1/"
                "resolve/main/chinese/test_set_chinese_with_label.jsonl")


def external_label(row):
    """外部对照语料的两种标法：COLING 那份 label 是 0/1，RealDet 那份 label 是 Human 或生成模型的名字。"""
    lab = row.get("label")
    if isinstance(lab, int) or (isinstance(lab, str) and lab.strip().isdigit()):
        lab = int(lab)
        return lab, str(row.get("source") or "?"), str(row.get("model") or ("human" if lab == 0 else "?"))
    tag = str(lab or "?").strip()
    is_h = tag.lower() in ("human", "human_written", "hwt", "真人")
    src_ = str(row.get("source") or "").strip() or "?"        # 没有 source 字段的由调用方按文件名补
    return (0 if is_h else 1), src_, ("human" if is_h else tag)


def stage_external(args):
    """第三方中文留出对出厂那份模型再量一次：COLING-2025 MGT-Detection Task 1 官方 test 的中文那份，
    或 koakuma/RealDet 的中文那两份（CC-BY-NC-4.0，只做对照不做训练，见 NOT_USED）。
    这些一个字不进训练，也不参与出厂门槛，只回答一个问题：
    真稿独立留出上那 0.55 是不是我们自己那份留出集太窄/太怪。--file 可以给逗号分隔的多个文件。"""
    import pickle
    path = args.file or os.path.join(DEFAULT_DATA, "coling25_zh_test.jsonl")
    paths = [p.strip() for p in str(path).split(",") if p.strip()]
    for p in paths:
        if not os.path.exists(p):
            raise SystemExit("缺外部对照语料：%s\n"
                             "  取法（COLING 那份 178 MB，走代理）：curl -x http://127.0.0.1:7897 -o \"%s\" \\\n    %s\n"
                             "  或下载后放别处再用 --file 指过来。" % (p, p, EXTERNAL_URL))
    norm = Normalizer(load_trad_table())
    with open(os.path.join(ART, "model-cache.pkl"), "rb") as f:
        cache = pickle.load(f)
    vec, cols, model = cache["vec"], cache["cols"], cache["model"]
    bias = cache_bias(cache)

    rows, docs, dropped_long = [], 0, 0
    for path in paths:
      for line in io.open(path, encoding="utf-8"):
        line = line.strip()
        if not line.startswith("{"):
            continue
        r = json.loads(line)
        docs += 1
        lab, src_, mdl = external_label(r)
        if src_ == "?":
            src_ = os.path.splitext(os.path.basename(path))[0]
        for s in split_sentences(str(r.get("text") or "")):
            c = norm.compact(s)
            if len(c) < MIN_SENT:
                continue
            if len(c) > MAX_SENT:
                dropped_long += 1
                continue
            rows.append((src_, mdl, lab, c))
    path = "+".join(os.path.basename(p) for p in paths)
    if not rows:
        raise SystemExit("外部对照语料读不出计分句：%s" % path)
    ps = []
    for i in range(0, len(rows), 20000):
        X = vectors([q[3] for q in rows[i:i + 20000]], vec, cols)
        ps.append(sigmoid(model.decision_function(X) + bias))
    p = np.concatenate(ps)
    y = np.array([q[2] for q in rows])
    srcs = np.array([q[0] for q in rows])
    mdl = np.array([q[1] for q in rows])
    lens = np.array([len(q[3]) for q in rows])

    def side(sel):
        yy, pp = y[sel], p[sel]
        nh, nm = max(1, int((yy == 0).sum())), max(1, int((yy == 1).sum()))
        return dict(n=int(len(yy)), human=int((yy == 0).sum()), machine=int((yy == 1).sum()),
                    auc=round(auc(yy, pp), 4),
                    fp_per_mille=round(1000.0 * float(np.sum(pp[yy == 0] >= FLAG_GATE)) / nh, 2),
                    machine_hit_percent=round(100.0 * float(np.sum(pp[yy == 1] >= FLAG_GATE)) / nm, 2))

    all_sel = np.ones(len(y), dtype=bool)
    per_source = {}
    for s in sorted(set(srcs)):
        m = srcs == s
        if m.sum() >= 40 and 0 < y[m].sum() < m.sum():
            per_source[s] = side(m)
    per_model = {}
    for s in sorted(set(mdl[y == 1])):
        m = mdl == s
        pool = m | (y == 0)
        if int(m.sum()) >= 40:
            per_model[s] = side(pool)
    train_compacts = set(r[4] for r in cache["rows"])
    dup_train = sum(1 for q in rows if q[3] in train_compacts)
    out = dict(model=cache.get("name"), file=path, url=EXTERNAL_URL if len(paths) == 1 else "多个文件（见 file 字段）",
               docs=docs, sentences=int(len(rows)), dropped_too_long=dropped_long,
               verbatim_overlap_with_this_model_s_training=dup_train,
               overall=side(all_sel),
               length_only_auc=round(auc(y, lens), 4),
               len_p50=dict(human=int(np.median(lens[y == 0])), machine=int(np.median(lens[y == 1]))),
               per_source=per_source, per_model=per_model,
               note="只读不训；出厂门槛仍然只认仓库里那份真稿独立留出，这里只是外部对照")
    json.dump(out, io.open(os.path.join(ART, "external-check.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    o = out["overall"]
    print("外部对照（%s，%d 篇 → %d 计分句，逐字撞上这份模型训练集的 %d 句）" %
          (out["file"], docs, out["sentences"], dup_train))
    print("  句级 AUC %.4f  真人误报 %.2f 句/千句  机器过线 %.2f%%  只看句长 %.4f（句长中位 真人 %d / 机器 %d）" %
          (o["auc"], o["fp_per_mille"], o["machine_hit_percent"], out["length_only_auc"],
           out["len_p50"]["human"], out["len_p50"]["machine"]))
    print("  按来源：")
    for s, v in sorted(per_source.items(), key=lambda kv: -kv[1]["n"]):
        print("    %-40s n=%-6d AUC %.4f  真人误报 %6.2f  机器过线 %6.2f%%" %
              (s, v["n"], v["auc"], v["fp_per_mille"], v["machine_hit_percent"]))
    print("  按生成模型（真人侧共用）：")
    for s, v in sorted(per_model.items(), key=lambda kv: -kv[1]["n"]):
        print("    %-40s n=%-6d AUC %.4f" % (s, v["n"], v["auc"]))
    print("明细写 artifacts/agent-aigc-offline/external-check.json")
    return out


def stage_gate(args, cache=None):
    """三档留出各量各的：公共留出 / 学术留出 / 真稿独立留出。出厂门槛只看第三档。"""
    import pickle
    norm = Normalizer(load_trad_table())
    if cache is None:
        with open(os.path.join(ART, "model-cache.pkl"), "rb") as f:
            cache = pickle.load(f)
    vec, cols, model = cache["vec"], cache["cols"], cache["model"]
    rows, y, va, scores = cache["rows"], cache["y"], cache["va"], cache["scores"]
    dom = np.asarray(cache.get("domains")) if cache.get("domains") is not None else None
    # 偏置只在公共真人验证侧挑：让 P>=0.5 的真人句比例不超过 2/1000
    hum = scores[va & (y == 0)]
    bias = 0.0
    for b in np.arange(0.0, -14.0, -0.02):
        if float(np.mean(sigmoid(hum + b) >= 0.5)) <= GATE_FP_PER_MILLE / 1000.0:
            bias = float(b); break
    valid_p = sigmoid(scores[va] + bias)
    y_va, dom_va = y[va], None if dom is None else dom[va]

    def side(p_sel, y_sel):
        nh, nm = int((y_sel == 0).sum()), int((y_sel == 1).sum())
        return dict(n=int(len(y_sel)), human=nh, machine=nm, auc=round(auc(y_sel, p_sel), 4),
                    fp_per_mille_at_045=round(1000.0 * float(np.sum(p_sel[y_sel == 0] >= FLAG_GATE)) / max(1, nh), 3),
                    fp_per_mille_at_050=round(1000.0 * float(np.sum(p_sel[y_sel == 0] >= 0.5)) / max(1, nh), 3),
                    machine_hit_percent_at_045=round(100.0 * float(np.sum(p_sel[y_sel == 1] >= FLAG_GATE)) / max(1, nm), 2))

    public = side(valid_p, y_va)
    public.update(auc_valid=public["auc"], auc_fit=round(auc(y[~va], scores[~va]), 4), n_all=len(rows))
    academic = None
    if dom_va is not None:
        m = np.isin(dom_va, list(ACADEMIC_DOMAINS))
        if m.sum() and 0 < y_va[m].sum() < m.sum():
            academic = side(valid_p[m], y_va[m])
            academic["domains"] = list(ACADEMIC_DOMAINS)
    per_domain = {}
    if dom_va is not None:
        for d in sorted(set(dom_va)):
            m = dom_va == d
            if m.sum() > 40 and 0 < y_va[m].sum() < m.sum():
                st = side(valid_p[m], y_va[m])
                per_domain[d] = dict(auc=st["auc"], human=st["human"], machine=st["machine"],
                                     fp_per_mille_at_045=st["fp_per_mille_at_045"])

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

    # 新那把尺子：段落级真人真稿。机器侧仍用上面那三个机器档——真人侧不许用模型生成的东西补齐。
    big = holdout2_rows(getattr(args, "holdout2", HOLDOUT2_DEFAULT), norm)
    holdout_big = None
    gates_big = None
    if big:
        p_big = sigmoid(model.decision_function(vectors([r[5] for r in big], vec, cols)) + bias)
        mach = p[np.array([HOLDOUT_TIERS_ONLY_MACHINE(c) for c in codes])]
        holdout_big = holdout2_stats(big, p_big, mach)
        gates_big = dict(n=holdout_big["n"], auc=holdout_big["auc"],
                         auc_pass=bool(holdout_big["auc"] >= GATE_AUC),
                         fp_per_mille=holdout_big["fp_per_mille_at_045"],
                         fp_pass=bool(holdout_big["fp_per_mille_at_045"] <= GATE_FP_PER_MILLE))
        gates_big["calibrated"] = bool(gates_big["auc_pass"] and gates_big["fp_pass"])
    seg = {}
    for r, pv in zip(ho, p):
        seg.setdefault((r[0], r[2]), []).append(float(pv))
    sy = [1 if HOLDOUT_TIERS_ONLY_MACHINE(c) else 0 for (c, i) in seg]
    ss = [float(np.mean(v)) for v in seg.values()]
    seg_auc = round(auc(np.array(sy), np.array(ss)), 4)

    # 真稿留出的事后分解（只描述失败长什么样，不参与任何选型；选型只看公共侧学术配对档）
    hlen = np.array([len(r[4]) for r in ho])
    per_file, hcodes, mcodes = [], [], []
    for code, rel, lab, note in HOLDOUT_TIERS:
        m = codes == code
        if m.sum() == 0:
            continue
        (mcodes if lab else hcodes).append(code)
        per_file.append(dict(code=code, side="机器" if lab else "真人", n=int(m.sum()), note=note,
                             len_p50=int(np.median(hlen[m])),
                             flag_percent=round(100.0 * float(np.mean(p[m] >= FLAG_GATE)), 2),
                             p_p50=round(float(np.percentile(p[m], 50)), 4),
                             p_p90=round(float(np.percentile(p[m], 90)), 4)))
    pair_auc = {}
    for hc in hcodes:
        for mc in mcodes:
            s = np.isin(codes, [hc, mc])
            pair_auc[hc + "_vs_" + mc] = round(auc((codes[s] == mc).astype(int), p[s]), 4)
    len_only_auc = round(auc(yh, hlen), 4)

    # 域差量两个不碰标签的数：模型那两万条特征在这批字上点亮多少，以及分数落在哪个带子
    vidx = np.where(va)[0]
    if len(vidx) > 8000:
        vidx = vidx[::int(math.ceil(len(vidx) / 8000.0))]
    Xp = vectors([rows[i][4] for i in vidx], vec, cols)

    def cover(Xs):
        nz_row = np.asarray((Xs > 0).sum(axis=1)).ravel()
        nz_col = np.asarray((Xs > 0).sum(axis=0)).ravel()
        return dict(matched_features_p50=int(np.median(nz_row)) if len(nz_row) else 0,
                    feature_columns_hit_percent=round(100.0 * float((nz_col > 0).sum()) / max(1, len(cols)), 2))

    domain_gap = dict(
        public=cover(Xp), holdout=cover(X),
        p_p50=dict(public_human=round(float(np.percentile(valid_p[y_va == 0], 50)), 4),
                   public_machine=round(float(np.percentile(valid_p[y_va == 1], 50)), 4),
                   holdout_human=round(float(np.percentile(p[yh == 0], 50)), 4),
                   holdout_machine=round(float(np.percentile(p[yh == 1], 50)), 4)),
        note="覆盖率不看标签，只问模型那两万条特征在这批字上点亮了多少条")

    # 试过的方向，量出来记在这里：分数对句长的归一化换一遍，三档各是什么
    w_coef = model.coef_.ravel().astype(np.float64)

    def score_variants(texts):
        Xv = vec.transform(texts)
        if cols is not None:
            Xv = Xv[:, cols]
        Xv = Xv.astype(np.float64)
        Xv.data = np.log1p(Xv.data)
        raw = Xv.dot(w_coef)
        nz = np.maximum(np.asarray((Xv > 0).sum(axis=1)).ravel(), 1.0)
        l2 = np.maximum(np.sqrt(np.asarray(Xv.multiply(Xv).sum(axis=1)).ravel()), 1e-9)
        L = np.maximum(np.array([len(t) for t in texts], dtype=np.float64), 1.0)
        return dict(no_norm=raw, l2=raw / l2, per_feature=raw / nz, per_char=raw / L,
                    per_sqrt_char=raw / np.sqrt(L))

    pv_v = score_variants([rows[i][4] for i in vidx])
    hv_v = score_variants([r[4] for r in ho])
    ypv = y[vidx]
    norm_probe = dict((k, dict(public=round(auc(ypv, pv_v[k]), 4), holdout=round(auc(yh, hv_v[k]), 4)))
                      for k in sorted(pv_v))
    norm_probe["note"] = "分数 = Σ(1+ln 次数)·权重 再除以某种长度；l2 是出厂这一份。这一列只用来证明换归一化不是出路"

    # 滑窗困惑度：频率表只用公开真人语料，阈值取公共真人窗口的 p0.1
    human_public = [r[4] for r in rows if r[2] == 0]
    formal = [r[4] for r in rows if r[2] == 0 and (r[1].startswith("prhpp") or
              r[1] == "hc3zh" and r[0].split(":")[1] in ("baike", "medicine", "law", "finance") or
              r[1] == "anxzh" and r[5] == "news")]
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
        hum2 = [d for d in seg_stat.values() if d["lab"] == 0]
        return seg_stat, win_tot, \
            float(sum(d["segs_hit"] for d in tpl)) / max(1, sum(d["segs"] for d in tpl)), \
            float(sum(d["segs_hit"] for d in hum2)) / max(1, sum(d["segs"] for d in hum2))

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
    lodo = cache.get("lodo") or {}
    lodo_vals = [v["auc"] for v in lodo.values() if v["auc"] == v["auc"]]
    out = dict(model_version=MODEL_VERSION, bias=round(float(bias), 4),
               public=public, academic=academic, per_domain_public=per_domain,
               cross_domain=dict(lodo=lodo,
                                 mean=round(float(np.mean(lodo_vals)), 4) if lodo_vals else None,
                                 worst=round(float(np.min(lodo_vals)), 4) if lodo_vals else None,
                                 note="轮流把整个域从拟合侧撤掉，只在被撤掉那个域的公共留出上量分；真稿留出全程没参与"),
               domain_gap=domain_gap, length_norm_probe=norm_probe, holdout_big=holdout_big,
               holdout=dict(n=int(len(ho)), auc=sent_auc, auc_paragraph=seg_auc,
                            per_file=per_file, pair_auc=pair_auc, length_only_auc=len_only_auc,
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
    # 新那把尺子单独判一次：3.0.0 的门槛该认这一档（几千段真人），不是老的 995 句
    out["gates_real_paragraph_scale"] = gates_big
    out["gates"]["window_calibrated"] = bool(out["gates"]["template_pass"] and out["gates"]["human_window_pass"])
    json.dump(out, io.open(os.path.join(ART, "gate.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    with open(os.path.join(ART, "freq-cache.pkl"), "wb") as f:
        pickle.dump(dict(lu=lu, oov=oov, lb=lb, lt=lt, thr=thr), f)
    print("三档留出（同一套权重、同一个偏置，只差在考哪批题）：")
    print("  ① 公共留出        AUC %.4f  真人误报 %.2f 句/千句  机器过线 %.2f%%（%d 句）" %
          (public["auc"], public["fp_per_mille_at_045"], public["machine_hit_percent_at_045"], public["n"]))
    if academic:
        print("  ② 学术留出        AUC %.4f  真人误报 %.2f 句/千句  机器过线 %.2f%%（%d 句）" %
              (academic["auc"], academic["fp_per_mille_at_045"], academic["machine_hit_percent_at_045"], academic["n"]))
    print("  ③ 真稿独立留出    AUC %.4f  真人误报 %.2f 句/千句  机器过线 %.2f%%（%d 句）  段级 %.4f" %
          (sent_auc, fp, hit, len(ho), seg_auc))
    if holdout_big:
        print("  ③c 段落级真稿      AUC %.4f  真人误报 %.2f 段/千段（%d 段 / %d 篇，机器侧仍用 ③ 那 %d 句）" %
              (holdout_big["auc"], holdout_big["fp_per_mille_at_045"], holdout_big["n"],
               holdout_big["papers"], holdout_big["machine_n"]))
        cells = []
        for d, row in holdout_big["by_discipline_band"].items():
            for lab, c in row.items():
                if c and c["n"] >= 40:
                    cells.append((c["fp_per_mille"], d + " " + lab, c["n"], c["auc_vs_machine"]))
        cells.sort(reverse=True)
        print("      按 学科 x 长度带 排，真人误报最高的格子：")
        for f, name, nn, au in cells[:6]:
            print("        %-44s n=%-5d 误报 %-7.2f 段/千段  对机器 AUC %.4f" % (name[:44], nn, f, au))
    if lodo_vals:
        print("  跨域留出（撤掉整个域再考）均值 %.4f 最差 %.4f（%d 个域）" %
              (float(np.mean(lodo_vals)), float(np.min(lodo_vals)), len(lodo_vals)))
    print(json.dumps({k: out[k] for k in ("holdout", "gates")}, ensure_ascii=False, indent=1)[:1600])
    print("滑窗：阈值 %.3f bits/字  模板段落点 %.1f%%（门槛 90%%）  真人段落点 %.1f%%（门槛 0%%）  窗口级 真人 %.2f%% / 机器 %.2f%%"
          % (thr, 100 * tpl_hit, 100 * hum_hit, out["window"]["human_window_rate"], out["window"]["machine_window_rate"]))
    print("真稿留出逐文件：")
    for d in per_file:
        print("    %-8s %-2s n=%-4d 句长中位 %-3d p50 %.3f p90 %.3f 过线 %.2f%%   %s" %
              (d["code"], d["side"], d["n"], d["len_p50"], d["p_p50"], d["p_p90"], d["flag_percent"], d["note"]))
    print("真稿两两 AUC（真人档 x 机器档）：%s" % json.dumps(pair_auc, ensure_ascii=False))
    print("只看句长（别的都不看）的 AUC %.4f；同领域同主题逐段配对那一档 H1 vs M-DOMAIN = %s" %
          (len_only_auc, pair_auc.get("H1_vs_M-DOMAIN")))
    print("特征覆盖（不看标签）：公共每句中位命中 %d 条 / 点亮 %.2f%% 的词表列；真稿每句中位命中 %d 条 / 点亮 %.2f%%" %
          (domain_gap["public"]["matched_features_p50"], domain_gap["public"]["feature_columns_hit_percent"],
           domain_gap["holdout"]["matched_features_p50"], domain_gap["holdout"]["feature_columns_hit_percent"]))
    print("分数带子 p50：公共真人 %.3f 公共机器 %.3f ｜ 真稿真人 %.3f 真稿机器 %.3f" %
          (domain_gap["p_p50"]["public_human"], domain_gap["p_p50"]["public_machine"],
           domain_gap["p_p50"]["holdout_human"], domain_gap["p_p50"]["holdout_machine"]))
    print("分数对句长的归一化换一遍（公共留出 / 真稿）：%s" % "  ".join(
        "%s %.4f/%.4f" % (k, v["public"], v["holdout"]) for k, v in norm_probe.items() if k != "note"))
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
    only = cache["cfg"].get("only")
    uses = (lambda code: True) if only is None else (lambda code: code in only)
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
    cd = gate["cross_domain"]
    cd_text = ("跨域留出（轮流撤掉整个域再考它）均值 %s，最差 %s。" % (cd["mean"], cd["worst"])
               if cd["mean"] is not None else
               "跨域留出：无（这份配置的公共侧只有一个域，撤掉就没题可考，逐域明细见 per_domain_public）。")
    mp = os.path.join(ASSETS, "aigc-model.tsv")
    metrics = gate
    mrows = [
        ("model_version", MODEL_VERSION),
        ("created", "2026-10-09"),
        ("what", "字符 2/3/4-gram + 词表 + 逻辑回归（CPU 训练，手机侧纯手写打分，无 ONNX、无新依赖）；"
                 "另附字级 1/2/3-gram 频率表做滑窗困惑度代理"),
        ("trained_on", "；".join("%s（%s，%s）" % (s["code"], s["license"], s["note"])
                                 for s in CORPORA if s["kind"] == "zh" and uses(s["code"]))),
        ("not_used", "；".join("%s：%s" % (w, why) for w, why in NOT_USED)),
        ("split", "按来源行/标题分组随机十等分，第 0 份留出；同一条样本的人写与机器版本落进同一组；另把与公共留出逐字相同的训练句从拟合侧剔掉（跨语料重复，见 counts 行）"),
        ("independent_holdout", "仓库内人工标注真稿：真人 H1 学位论文正文 + H2 已发表摘要逐字摘录 + H3 知网片段 + "
                                "X1 轻度润色 + HH 最难判的真人句，机器 M-RAW / M-EVADE / M-DOMAIN；这批一个字都没进训练"),
        ("provenance",
         "三档留出分开量（同一套权重、同一个偏置，只差在考哪批题）：① 公共留出 AUC " + str(metrics["public"]["auc"]) +
         "（" + str(metrics["public"]["n"]) + " 句，真人误报 " + str(metrics["public"]["fp_per_mille_at_045"]) +
         " 句/千句）；② 学术留出 AUC " + str(metrics["academic"]["auc"] if metrics.get("academic") else "无") +
         "（" + str(metrics["academic"]["n"] if metrics.get("academic") else 0) + " 句，真人误报 " +
         str(metrics["academic"]["fp_per_mille_at_045"] if metrics.get("academic") else "无") + " 句/千句）；"
         "③ 真稿独立留出 AUC(机器>真人)=" + str(metrics["holdout"]["auc"]) + "，段级 " +
         str(metrics["holdout"]["auc_paragraph"]) + "，真人误报 " + str(metrics["holdout"]["fp_per_mille_at_045"]) +
         " 句/千句，机器侧过线 " + str(metrics["holdout"]["machine_hit_percent_at_045"]) + "%。" +
         cd_text + "出厂门槛只认第三档：AUC >= 0.85 且真人误报 <= 2 句/千句；"
         + ("两条都过，calibrated=true。" if gate["gates"]["calibrated"] else
            "有一条或两条不过，calibrated=false，界面不印任何 AIGC 百分比。")),
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
        ("public_human_fp_per_mille", "%.3f" % metrics["public"]["fp_per_mille_at_045"]),
        ("academic_auc", "%.4f" % metrics["academic"]["auc"] if metrics.get("academic") else "nan"),
        ("academic_human_fp_per_mille",
         "%.3f" % metrics["academic"]["fp_per_mille_at_045"] if metrics.get("academic") else "nan"),
        ("cross_domain_mean", "%.4f" % metrics["cross_domain"]["mean"] if metrics["cross_domain"]["mean"] is not None else "nan"),
        ("cross_domain_note", ("n_domains=%d" % len(metrics["cross_domain"]["lodo"]))
         if metrics["cross_domain"]["mean"] is not None else
         "无：这份配置的公共侧只有一个域（学术摘要配对档），轮流撤掉整个域之后没题可考"),
        ("cross_domain_worst", "%.4f" % metrics["cross_domain"]["worst"] if metrics["cross_domain"]["worst"] is not None else "nan"),
        ("cross_domain_lodo", json.dumps(metrics["cross_domain"]["lodo"], ensure_ascii=False)),
        ("per_domain_public", json.dumps(metrics["per_domain_public"], ensure_ascii=False)),
        ("counts", "计分句 %d（拟合侧剔掉与公共留出逐字相同的 %d 句）；配置 %s" %
         (metrics["public"]["n_all"], int(cache.get("n_dup", 0)), cache.get("name", "?"))),
        ("holdout_auc", "%.4f" % metrics["holdout"]["auc"]),
        ("holdout_auc_paragraph", "%.4f" % metrics["holdout"]["auc_paragraph"]),
        ("holdout_human_fp_per_mille", "%.3f" % metrics["holdout"]["fp_per_mille_at_045"]),
        ("holdout_machine_hit_percent", "%.2f" % metrics["holdout"]["machine_hit_percent_at_045"]),
        ("holdout_length_only_auc", "%.4f" % metrics["holdout"]["length_only_auc"]),
        ("holdout_pair_topic_matched_auc",
         ("%.4f" % metrics["holdout"]["pair_auc"]["H1_vs_M-DOMAIN"])
         if metrics["holdout"]["pair_auc"].get("H1_vs_M-DOMAIN") is not None else "nan"),
        ("holdout_domain_gap",
         "模型词表被点亮的比例：公共留出 %.2f%% / 真稿 %.2f%%；每句命中特征中位数 公共 %d / 真稿 %d"
         % (metrics["domain_gap"]["public"]["feature_columns_hit_percent"],
            metrics["domain_gap"]["holdout"]["feature_columns_hit_percent"],
            metrics["domain_gap"]["public"]["matched_features_p50"],
            metrics["domain_gap"]["holdout"]["matched_features_p50"])),
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
        provenance_statement="三档留出：公共 " + str(gate["public"]["auc"]) + " / 学术 "
                             + str(gate["academic"]["auc"] if gate.get("academic") else None) + " / 真稿独立 "
                             + str(gate["holdout"]["auc"]) + "；出厂门槛只认真稿那一档（AUC >= " + str(GATE_AUC)
                             + " 且真人误报 <= " + str(GATE_FP_PER_MILLE) + " 句/千句）："
                             + ("过线，印百分比。" if gate["gates"]["calibrated"] else "没过线，只列证据不给比例。"),
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
    ap.add_argument("stage", choices=["fetch", "train", "compare", "external", "gate", "export", "all"])
    ap.add_argument("--file", default="", help="外部对照语料的本地路径（external 那一步用）")
    ap.add_argument("--data", default=DEFAULT_DATA)
    ap.add_argument("--repo", default=REPO)
    ap.add_argument("--proxy", default=os.environ.get("WORDLITE_AIGC_PROXY", "http://127.0.0.1:7897"))
    ap.add_argument("--redo", action="store_true")
    ap.add_argument("--configs", default="", help="只跑这些配置，逗号分隔；空=全跑")
    ap.add_argument("--no-lodo", action="store_true", help="跳过跨域留出（只用于快速调试）")
    ap.add_argument("--holdout2", default=HOLDOUT2_DEFAULT,
                    help="段落级真人真稿留出（tools/build-holdout-corpus.py build 的产物，留在仓库外）")
    a = ap.parse_args()
    if a.stage in ("fetch", "all"):
        stage_fetch(a)
    if a.stage in ("train", "all"):
        stage_train(a)
    if a.stage == "compare":
        stage_compare(a)
    if a.stage == "external":
        stage_external(a)
    if a.stage in ("gate", "all"):
        stage_gate(a)
    if a.stage in ("export", "all"):
        stage_export(a)


if __name__ == "__main__":
    main()
