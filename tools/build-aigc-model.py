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
CREATED = "2026-10-09"          # 随包清单的 created；export --created 可覆盖（导暂存那一份用新日期）

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
    # 2026-10-12 第四轮：子组在 ../aigc-corpus/selfbuild/ 自造的缺失档。这个目录到本轮收尾仍然没有 manifest.json，
    # 来源按文件字段逐份核（gen / source）；machine_evasive.jsonl 本轮仍是 0 行，不进 CORPORA，等它有了再按同一套规矩接。
    dict(code="sbwhole", kind="zh", license="自造（本机代理整节生成，无第三方许可约束；原文不进仓库）", ship_raw=False, anon=True,
         local=True, url="", file="selfbuild/machine_wholesection.jsonl",
         note="自造整篇直出档：12 个学科按学位论文口吻整节生成（gen=claude-agent-raw），只有学科级对齐、没有逐篇配对。"
              "本轮这份读数用的是冻结副本 selfbuild-frozen2-20261012：180 行 / 2,040 计分句。"
              "公开中文语料里没有这一档，真稿留出的 M-DOMAIN（与 H1 同学科同主题的机器整篇稿）正对着它。"
              "与全体中文公共池逐字重复 0 句、与真稿留出逐字重复 0 句（py tools/build-aigc-model.py guard）"),
    dict(code="sbstudent", kind="zh", license="自造（同上）", ship_raw=False, anon=True,
         local=True, url="", file="selfbuild/machine_student_prompt.jsonl",
         note="自造学生提示档：按'交上去的段落'这类学生提示生成的机器稿，最贴真稿里 M-RAW / M-EVADE 那一档的改写味。"
              "本轮这份读数用的是冻结副本 selfbuild-frozen2-20261012：60 行 / 724 计分句。"
              "与全体中文公共池、与真稿留出逐字重复均为 0 句"),
    dict(code="sbevasive", kind="zh", license="自造（本机代理按规避提示生成，无第三方许可约束；原文不进仓库）", ship_raw=False, anon=True,
         local=True, url="", file="selfbuild/machine_evasive.jsonl",
         note="自造规避档：按『别被看出是机器写的』这类提示生成的整段（gen=claude-agent-evasive），对着真稿留出的 M-EVADE 那一档"),
    dict(code="sbhuman", kind="zh",
         license="开放获取论文正文（source 字段记到站点：oa-pdf:ojs.omniscient.sg / oa-pdf:cn.front-sci.com / "
                 "oa-pdf:files.sciengine.com；按各刊开放获取声明使用，仓库里只进派生统计量）",
         ship_raw=False, anon=True, local=True, url="", file="selfbuild/human_fulltext.jsonl",
         note="自造真人整篇档（本轮这份读数用的是冻结副本 selfbuild-frozen2-20261012：6,409 行 / 35,614 计分句 / 15 学科，"
              "其中 geoscience、physics、psychology 三个学科只有人写、没有机器料）：开放获取期刊 PDF 里抽的论文正文整段，"
              "是这一轮唯一贴近学位论文正文分布的真人料。注意这个文件在子组手里换过一版——早上那 959 行是上游"
              "公开集的重新打包（mgb-baike / drlx-book / hc3-baike），与全体中文公共池逐字重复 95.8%；"
              "所以混池配置一律带 sb_dedupe（把与本池其他来源逐字相同的句子剔掉），并用 guard --pool 复核"),
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

# 自造语料（../aigc-corpus/selfbuild/，无 manifest.json，来源按文件字段核）：code -> label（1 = 机器）
SELFBUILD_LABEL = {"sbwhole": 1, "sbstudent": 1, "sbevasive": 1, "sbhuman": 0}
# 自造档没有逐篇配对（machine_wholesection 只有 topic，human_fulltext 是另一批上游文本），只有学科级对齐：
# 同一学科的人写句与机器句共用一个 group_id，配对拟合才能在这一档上做"同学科 人写 vs 机器整篇"的差。
SELFBUILD_GROUP = "sbd"
# --selfbuild 指到一份冻结副本时，所有 local=True 的语料都从那里读。子组那边一边跑一边追加文件，
# 一整张表就会打在不同版本上（本轮实测：一次 compare 跑到一半 machine_wholesection 被追加），必须能钉住。
SELFBUILD_DIR = ""


def sb_path(spec, data_dir):
    """自建档的真实路径：默认 <data>/selfbuild/<file>，--selfbuild 指到副本就读副本。"""
    if not spec.get("local"):
        return os.path.join(data_dir, spec["file"])
    return os.path.join(SELFBUILD_DIR or os.path.join(data_dir, "selfbuild"), os.path.basename(spec["file"]))
_GUARD = {}


def real_holdout_guard(norm, repo=REPO):
    """真稿留出那批 tests/corpus 文件（含模板段）的逐字句集。训练侧读自造语料时逐句比对，
    命中一句就整行不进训练——自造料和真稿可能同题同域，逐字排除是并进训练的前置条件，不是走过场。
    粒度与训练单位一致：训练与打分都以"计分句"为单位，切句与归一化两边共用同一套函数。"""
    key = id(norm)
    if key not in _GUARD:
        sents = set()
        for code, rel, lab, note in list(HOLDOUT_TIERS) + [(t[0], t[1], 1, "") for t in TEMPLATE_TIERS]:
            p = os.path.join(repo, rel.replace("/", os.sep))
            if not os.path.exists(p):
                raise SystemExit("逐字守卫缺文件：%s" % rel)
            for line in io.open(p, encoding="utf-8"):
                for s in split_sentences(line.rstrip("\n")):
                    c = norm.compact(s)
                    if c:
                        sents.add(c)
        _GUARD[key] = frozenset(sents)
    return _GUARD[key]


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
        if spec.get("local"):
            print("跳过 %-34s 自造语料：本台不下载，放在 ../aigc-corpus/selfbuild/，接进训练前跑 guard" % spec["file"])
            continue
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
    if code in SELFBUILD_LABEL:
        return src                    # 自造档传进来的就是 selfbuild/<m|h>-<学科>；不认就会退回 code，学科域全丢
    if code == "hc3zh":
        return src                       # hc3-baike / hc3-law / hc3-medicine / ... 分开算域
    if code == "pangda":
        return "web-query"
    if code.startswith("prhpp"):
        return src if str(src).startswith("academic-abstract") else "academic-abstract"
    if code == "setask8zh":
        return "web-ugc"
    if code == "anxzh":
        return src
    if code in ("magazh", "magaaug", "drlxzh"):
        return src
    return code


def rows_of(data_dir, norm, only=None, subject_split=False):
    """[(group_id, source_code, label, raw_sentence, compact, domain)]，label 1 = 机器。
    group_id 里带来源行号/标题：同一条样本的人写与机器版本落进同一组，防止改写对跨划分泄漏。"""
    out, stats, dropped = [], Counter(), Counter()
    import csv
    ensure_drlx_zh(data_dir)                    # 有原始大数组但没抽过中文子集时先抽一次
    # 自造语料要逐字比对真稿留出：命中一句整行不进训练（guard 子命令报的就是这个口径）
    guard = real_holdout_guard(norm) if any(sp["code"] in SELFBUILD_LABEL and not (only and sp["code"] not in only)
                                            for sp in CORPORA) else frozenset()
    for spec in CORPORA:
        path = sb_path(spec, data_dir)        # 自建档走 --selfbuild 那份（见 SELFBUILD_DIR 的注释）
        if spec["kind"] != "zh" or not os.path.exists(path):
            continue
        if only and spec["code"] not in only:
            continue
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
            elif code in SELFBUILD_LABEL:
                lab = SELFBUILD_LABEL[code]
                d0 = (str(row.get("domain") or "?").strip().lower() or "?")
                dom = "selfbuild/%s-%s" % ("m" if lab else "h", d0)
                gid = "%s:%s" % (SELFBUILD_GROUP, d0)      # 同学科的人写与机器句同组：见 SELFBUILD_GROUP 的注释
                hit = [c for c in (norm.compact(s) for s in split_sentences(row.get("text") or "")) if c in guard]
                if hit:                                    # 与真稿留出逐字相同：整行剔掉，一句都不进训练侧
                    dropped["selfbuild-vs-real-holdout"] += 1
                    continue
                pairs = [(row.get("text") or "", lab)]
            else:
                gid = "%s:%s" % (code, row.get("title") or i)
                lab = int(row.get("label", 1)) if row.get("label") is not None else 1
                if code.startswith("prhpp"):
                    dom = "academic-abstract"
                    if subject_split and row.get("category"):    # prhpp 的学科字段：摘要档再切学科子域
                        dom = "academic-abstract/" + str(row["category"]).strip()
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
    # 2026-10-12 新增：针对"公共侧过好、真稿侧崩"，一条路子一个配置，每个都有独立读数
    # ① 配对拟合：同一篇人写原文与它的机器改写落同一折，且权重只从组内差样本里学
    dict(name="Q-pairdiff-abstract", only=["prhpp", "prhpp_te", "prhppgen"], ngram=(2, 4), min_df=5,
         C=1.0, pair="gdiff"),
    # ② 配对 + 学科均衡：prhpp 的 category 字段把摘要档切成 13 个学科子域，再按 (学科, 类别) 配平
    dict(name="R-pairdiff-subjectbal", only=["prhpp", "prhpp_te", "prhppgen"], ngram=(2, 4), min_df=5,
         C=1.0, pair="gdiff", subject_split=True, balance="domain"),
    # ③ 配对 + 正文级学术料：加 DetectRL-X 中文 Academic 档（真人正文 vs 现代模型整篇改写，同一行成对）
    dict(name="S-pairdiff-academic", only=["prhpp", "prhpp_te", "prhppgen", "drlxzh"], ngram=(2, 4),
         min_df=8, C=1.0, pair="gdiff", balance="domain", cap=CAP_PER_DOMAIN_NEW,
         keep_domains=ACADEMIC_DOMAINS + ("drlx-academic",)),
    # ④ 同上但不筛域，六个域全上（"域越杂越像真稿"这件事到底成不成立）
    dict(name="T-pairdiff-fullmix", only=["prhpp", "prhpp_te", "prhppgen", "drlxzh"], ngram=(2, 4),
         min_df=8, C=1.0, pair="gdiff", balance="domain", cap=CAP_PER_DOMAIN_NEW),
    # ⑤ 特征面按域 AUC 收窄：候选放宽到 3 倍，再用"域内单变量 AUC 一致率 + 幅度"筛回 20000 条
    dict(name="U-domauc-prune", only=["prhpp", "prhpp_te", "prhppgen", "anxzh"], ngram=(2, 4), min_df=8,
         C=1.0, balance="domain", prune="domain-auc", cand_mult=3, cap=CAP_PER_DOMAIN_NEW),
    # ⑥ 域均衡 + 降权 + 句长带：远的料（SEO、网文、口语问答、摘要体整篇稿）压权，短句与超长句剔除
    dict(name="V-lean-formal", only=["prhpp", "prhpp_te", "prhppgen", "drlxzh", "anxzh"], ngram=(2, 4),
         min_df=8, C=1.0, pair="gdiff", balance="domain", cap=CAP_PER_DOMAIN_NEW, len_range=(20, 90),
         keep_domains=ACADEMIC_DOMAINS + ("drlx-academic", "drlx-news", "drlx-wiki", "news", "essay"),
         domain_weight=[("drlx-novel", 0.3), ("drlx-seo", 0.3), ("drlx-webtext", 0.3), ("essay", 0.5)],
         bias_pool="formal"),
    # ⑦ 标定池单独一条：与 S 同一份权重，只把挑偏置用的那批真人句换成学术档
    dict(name="Y-bias-formal", only=["prhpp", "prhpp_te", "prhppgen", "drlxzh"], ngram=(2, 4),
         min_df=8, C=1.0, pair="gdiff", balance="domain", cap=CAP_PER_DOMAIN_NEW,
         keep_domains=ACADEMIC_DOMAINS + ("drlx-academic",), bias_pool="formal"),
    # ⑧ 特征面收窄的另外两刀：正则化加强 / 去掉 4-gram（4-gram 最容易学到领域词汇）
    dict(name="W-lowc", only=["prhpp", "prhpp_te", "prhppgen", "drlxzh"], ngram=(2, 4),
         min_df=8, C=0.1, pair="gdiff", balance="domain", cap=CAP_PER_DOMAIN_NEW,
         keep_domains=ACADEMIC_DOMAINS + ("drlx-academic",)),
    dict(name="X-ngram23", only=["prhpp", "prhpp_te", "prhppgen", "drlxzh"], ngram=(2, 3),
         min_df=8, C=1.0, pair="gdiff", balance="domain", cap=CAP_PER_DOMAIN_NEW,
         keep_domains=ACADEMIC_DOMAINS + ("drlx-academic",)),
    # 同日第二轮：X 那一档（去掉 4-gram）真稿误报已经进门槛，围着它再切几刀，每刀一个独立读数
    # X6 / X7 与 X 的权重完全一样（句级三档数一字不差），只把滑窗频率表的语料池从 formal 换成
    # "这份配置读到的全部公开真人句"：句级打分不受影响，只动滑窗那两栏；X7 再加条数上限控体积
    dict(name="X6-ngram23-humanfreq", only=["prhpp", "prhpp_te", "prhppgen", "drlxzh"], ngram=(2, 3),
         min_df=8, C=1.0, pair="gdiff", balance="domain", cap=CAP_PER_DOMAIN_NEW,
         keep_domains=ACADEMIC_DOMAINS + ("drlx-academic",), freq_pool="human"),
    dict(name="X7-ngram23-freqcap", only=["prhpp", "prhpp_te", "prhppgen", "drlxzh"], ngram=(2, 3),
         min_df=8, C=1.0, pair="gdiff", balance="domain", cap=CAP_PER_DOMAIN_NEW,
         keep_domains=ACADEMIC_DOMAINS + ("drlx-academic",), freq_pool="human",
         freq_cap_bi=40000, freq_cap_tri=60000),
    dict(name="X2-ngram23-lowc", only=["prhpp", "prhpp_te", "prhppgen", "drlxzh"], ngram=(2, 3),
         min_df=8, C=0.3, pair="gdiff", balance="domain", cap=CAP_PER_DOMAIN_NEW,
         keep_domains=ACADEMIC_DOMAINS + ("drlx-academic",)),
    dict(name="X3-ngram23-nopair", only=["prhpp", "prhpp_te", "prhppgen", "drlxzh"], ngram=(2, 3),
         min_df=8, C=1.0, balance="domain", cap=CAP_PER_DOMAIN_NEW,
         keep_domains=ACADEMIC_DOMAINS + ("drlx-academic",)),
    dict(name="X4-ngram23-drlxonly", only=["drlxzh"], ngram=(2, 3), min_df=8, C=1.0, pair="gdiff",
         balance="domain", cap=CAP_PER_DOMAIN_NEW, keep_domains=("drlx-academic",)),
    dict(name="X5-ngram23-fullmix", only=["prhpp", "prhpp_te", "prhppgen", "drlxzh"], ngram=(2, 3),
         min_df=8, C=1.0, pair="gdiff", balance="domain", cap=CAP_PER_DOMAIN_NEW),
    dict(name="L1-longform-bias", only=["prhpp", "prhpp_te", "prhppgen", "drlxzh"], ngram=(2, 3),
         min_df=8, C=1.0, pair="gdiff", balance="domain", cap=CAP_PER_DOMAIN_NEW,
         keep_domains=ACADEMIC_DOMAINS + ("drlx-academic",), bias_pool="longform"),
    dict(name="BL1-blend", only=["prhpp", "prhpp_te", "prhppgen", "drlxzh"], ngram=(2, 3),
         min_df=8, C=1.0, pair="gdiff", balance="domain", cap=CAP_PER_DOMAIN_NEW, blend=1.0,
         keep_domains=ACADEMIC_DOMAINS + ("drlx-academic",)),
    # 把"按域 AUC 筛特征"重做一遍：域铺开到 19 个（上一版只有 2 个域，一致率一刀就刷光了），
    # 阈值也放软（一致率 0.7、平均幅度 0.005），每域上限压到 12,000 句防内存
    dict(name="Z2-domauc-wide", only=["prhpp", "prhpp_te", "prhppgen", "hc3zh", "pangda", "setask8zh",
                                       "anxzh", "drlxzh"], ngram=(2, 3), min_df=10, C=1.0, pair="gdiff",
         balance="domain", prune="domain-auc", cand_mult=3, min_share=0.7, min_gap=0.005, cap=12000),
    # ⑩ 工作点单独一条：偏置是分数上的单调平移，改它 AUC 一个字都不动，能动的只有"真人误报"和"机器过线"。
    #    出厂门槛的 2/千句是按 0.45 量的，挑偏置按 0.5，两边差着一截；这里把挑偏置的目标收紧，
    #    看 ③ 那一栏跟不跟得下来。严格度（0.5、0.2）是我看过 ③ 误报之后定的，等于用掉了一次真稿留出，
    #    文档里按这个口径标注，不算事前规则。
    dict(name="Z3-strict-bias-d", only=["prhpp", "prhpp_te", "prhppgen", "pangda"], ngram=(2, 4),
         min_df=10, C=1.0, cap=CAP_PER_DOMAIN, bias_fp_target=0.5,
         bias_note="这一档的严格度（0.5/千句）是看过 ③ 真人误报之后定的：用掉了一次真稿留出，"
                   "它的 ③ 误报不能当独立复核用"),
    dict(name="Z4-strictest-bias-d", only=["prhpp", "prhpp_te", "prhppgen", "pangda"], ngram=(2, 4),
         min_df=10, C=1.0, cap=CAP_PER_DOMAIN, bias_fp_target=0.2,
         bias_note="同 Z3，严格度（0.2/千句）是看过 ③ 之后定的"),
    dict(name="Z5-strict-bias-x", only=["prhpp", "prhpp_te", "prhppgen", "drlxzh"], ngram=(2, 3),
         min_df=8, C=1.0, pair="gdiff", balance="domain", cap=CAP_PER_DOMAIN_NEW,
         keep_domains=ACADEMIC_DOMAINS + ("drlx-academic",), bias_fp_target=0.5,
         bias_note="同 Z3，严格度（0.5/千句）是看过 ③ 之后定的"),
    # ⑪ 上一条严格度是看过 ③ 才定的，这一条不是：只把挑偏置用的尺子换成出厂门槛那把（0.45，2/千句），
    #    事前口径，权重与 D / X 一字不差，AUC 也一字不差，动的只有工作点。
    dict(name="Z6-gatecal-d", only=["prhpp", "prhpp_te", "prhppgen", "pangda"], ngram=(2, 4),
         min_df=10, C=1.0, cap=CAP_PER_DOMAIN, bias_at_gate=True),
    dict(name="Z7-gatecal-x", only=["prhpp", "prhpp_te", "prhppgen", "drlxzh"], ngram=(2, 3),
         min_df=8, C=1.0, pair="gdiff", balance="domain", cap=CAP_PER_DOMAIN_NEW,
         keep_domains=ACADEMIC_DOMAINS + ("drlx-academic",), bias_at_gate=True),
    # 2026-10-12 第四轮：把子组自造的缺失档（../aigc-corpus/selfbuild/）并进训练。每一刀只在上一个配置上动一处，
    # 逐字守卫（真稿留出命中即整行剔除）与 sb_dedupe（自造真人档与本池其他来源逐字相同的句子剔掉）都在 rows_of /
    # fit_config 里，不在配置里选。对照的基线：D-no-hc3 ③ 0.5952 / 误报 9.62 / 机器过线 1.62%，X-ngram23 ③ 0.5754 / 1.60 / 0。
    # SA：只加机器侧自造档（整篇直出 + 学生提示），与 D 唯一的差别就是这 1.2k 句新料
    dict(name="SA-sb-mach", only=["prhpp", "prhpp_te", "prhppgen", "pangda", "sbwhole", "sbstudent"],
         ngram=(2, 4), min_df=10, C=1.0, cap=CAP_PER_DOMAIN),
    # SB：再加自造真人整篇档（对 D 这一档的池子来说它是新料：与 prhpp+pangda 逐字相同只有 4 句）
    dict(name="SB-sb-both", only=["prhpp", "prhpp_te", "prhppgen", "pangda", "sbwhole", "sbstudent", "sbhuman"],
         ngram=(2, 4), min_df=10, C=1.0, cap=CAP_PER_DOMAIN, sb_dedupe=True),
    # SC：自造那一档升权 4 倍（1.2k 句 vs 15 万句，不升权等于没说话）
    dict(name="SC-sb-both-up", only=["prhpp", "prhpp_te", "prhppgen", "pangda", "sbwhole", "sbstudent", "sbhuman"],
         ngram=(2, 4), min_df=10, C=1.0, cap=CAP_PER_DOMAIN, sb_dedupe=True,
         domain_weight=[("selfbuild/", 4.0)]),
    # SD：同一份料改成域内类别配平（域均衡这条杠杆在有新档之后重测一次）
    dict(name="SD-sb-both-bal", only=["prhpp", "prhpp_te", "prhppgen", "pangda", "sbwhole", "sbstudent", "sbhuman"],
         ngram=(2, 4), min_df=10, C=1.0, cap=CAP_PER_DOMAIN, sb_dedupe=True, balance="domain"),
    # SE：SB 的工作点换成出厂那把尺子（0.45 / 2 千句）——AUC 与 SB 一字不差，动的只有误报与机器过线
    dict(name="SE-sb-both-gatecal", only=["prhpp", "prhpp_te", "prhppgen", "pangda", "sbwhole", "sbstudent", "sbhuman"],
         ngram=(2, 4), min_df=10, C=1.0, cap=CAP_PER_DOMAIN, sb_dedupe=True, bias_at_gate=True),
    # SF：配对那一档（X-ngram23）加自造档：同学科的人写句与机器整篇稿同组，配对差样本能吃到这一档
    dict(name="SF-sb-x-pair", only=["prhpp", "prhpp_te", "prhppgen", "drlxzh", "sbwhole", "sbstudent", "sbhuman"],
         ngram=(2, 3), min_df=8, C=1.0, pair="gdiff", balance="domain", cap=CAP_PER_DOMAIN_NEW,
         keep_domains=ACADEMIC_DOMAINS + ("drlx-academic", "selfbuild"), sb_dedupe=True),
    # SG：只用自造档训练（同学科 人写整篇 vs 机器整篇），量"最贴真稿分布的这一档单独能考多少"
    dict(name="SG-sb-only", only=["sbwhole", "sbstudent", "sbhuman"], ngram=(2, 3), min_df=2, C=1.0,
         pair="gdiff", balance="domain", cap=CAP_PER_DOMAIN_NEW),
    # 上面一轮（domain_of 还没修）里 SA 是唯一把 ③ 抬过 0.60 的一档，围着它再切：谁在出力 / 域均衡 / 升权 / 特征面 / 工作点
    # SJ：只留整篇直出那一档，剥掉学生提示档——0.6477 里有多少是整篇直出带来的
    dict(name="SJ-sb-whole-only", only=["prhpp", "prhpp_te", "prhppgen", "pangda", "sbwhole"],
         ngram=(2, 4), min_df=10, C=1.0, cap=CAP_PER_DOMAIN),
    # SI：SA + 域内类别配平（域均衡这条杠杆在"新料只有机器侧"的池子里重测）
    dict(name="SI-sb-mach-bal", only=["prhpp", "prhpp_te", "prhppgen", "pangda", "sbwhole", "sbstudent"],
         ngram=(2, 4), min_df=10, C=1.0, cap=CAP_PER_DOMAIN, balance="domain"),
    # SK：SA + 自造那一档升权 4 倍（1.2k 句 vs 7.5 万句）
    dict(name="SK-sb-mach-up", only=["prhpp", "prhpp_te", "prhppgen", "pangda", "sbwhole", "sbstudent"],
         ngram=(2, 4), min_df=10, C=1.0, cap=CAP_PER_DOMAIN, domain_weight=[("selfbuild/", 4.0)]),
    # SH：SA 的工作点换成出厂那把尺子（0.45 / 2 千句），AUC 一字不差，只看误报与机器过线跟不跟下来
    dict(name="SH-sb-mach-gatecal", only=["prhpp", "prhpp_te", "prhppgen", "pangda", "sbwhole", "sbstudent"],
         ngram=(2, 4), min_df=10, C=1.0, cap=CAP_PER_DOMAIN, bias_at_gate=True),
    # SM / SN：SA 去掉 4-gram（上一轮这一刀把 X 那一档的 ③ 误报从 4.81 压到 1.60），再配工作点
    dict(name="SM-sb-mach-23", only=["prhpp", "prhpp_te", "prhppgen", "pangda", "sbwhole", "sbstudent"],
         ngram=(2, 3), min_df=10, C=1.0, cap=CAP_PER_DOMAIN),
    dict(name="SN-sb-mach-23-gatecal", only=["prhpp", "prhpp_te", "prhppgen", "pangda", "sbwhole", "sbstudent"],
         ngram=(2, 3), min_df=10, C=1.0, cap=CAP_PER_DOMAIN, bias_at_gate=True),
    # SG（只用自造档）③ 到了 0.7444，是这台机器上量过的最高值，但 ③ 误报 14.42；下面四条顺着"自造档权重越高越好"
    # 这条梯度再切，并且把 SG 的工作点换成出厂那把尺子。注意：这四档是看过 ③ 之后才切的，等于又用掉一次真稿留出。
    # SO：SG + 出厂尺子的工作点（事前口径，AUC 一字不差）
    dict(name="SO-sb-only-gatecal", only=["sbwhole", "sbstudent", "sbhuman"], ngram=(2, 3), min_df=2, C=1.0,
         pair="gdiff", balance="domain", cap=CAP_PER_DOMAIN_NEW, bias_at_gate=True),
    # SQ：SD（域均衡那一档）+ 出厂尺子
    dict(name="SQ-sb-both-bal-gatecal", only=["prhpp", "prhpp_te", "prhppgen", "pangda", "sbwhole", "sbstudent", "sbhuman"],
         ngram=(2, 4), min_df=10, C=1.0, cap=CAP_PER_DOMAIN, sb_dedupe=True, balance="domain", bias_at_gate=True),
    # SR / SS：把自造档升权拉到 10 倍——SK（4 倍）与 SG（只有自造档）之间这条梯度到底还有多少余量
    dict(name="SR-sb-mach-up10", only=["prhpp", "prhpp_te", "prhppgen", "pangda", "sbwhole", "sbstudent"],
         ngram=(2, 4), min_df=10, C=1.0, cap=CAP_PER_DOMAIN, domain_weight=[("selfbuild/", 10.0)]),
    dict(name="SS-sb-both-up10-bal", only=["prhpp", "prhpp_te", "prhppgen", "pangda", "sbwhole", "sbstudent", "sbhuman"],
         ngram=(2, 4), min_df=10, C=1.0, cap=CAP_PER_DOMAIN, sb_dedupe=True, balance="domain",
         domain_weight=[("selfbuild/", 10.0)]),
    # 自建档补齐之后（machine_evasive 也到了，gen=claude-agent-evasive）再各加一条：规避档进得来吗
    # ST：SQ（域均衡 + 出厂尺子）+ 规避档
    dict(name="ST-sb-all-bal-gatecal", only=["prhpp", "prhpp_te", "prhppgen", "pangda", "sbwhole", "sbstudent",
                                             "sbevasive", "sbhuman"],
         ngram=(2, 4), min_df=10, C=1.0, cap=CAP_PER_DOMAIN, sb_dedupe=True, balance="domain", bias_at_gate=True),
    # SU：只用自建档 + 出厂尺子（SO 加上规避档）
    dict(name="SU-sb-all-only-gatecal", only=["sbwhole", "sbstudent", "sbevasive", "sbhuman"],
         ngram=(2, 3), min_df=2, C=1.0, pair="gdiff", balance="domain", cap=CAP_PER_DOMAIN_NEW, bias_at_gate=True),
    # SV：与 SU 同一套权重（句级三档数一字不差），只给滑窗频率表加条数上限——自建档句子长，
    # 三字表一度涨到 85,342 条，随包三件合计 1,767 KB，超了 1.5 MB 的随包预算。
    dict(name="SV-sb-all-freqcap", only=["sbwhole", "sbstudent", "sbevasive", "sbhuman"],
         ngram=(2, 3), min_df=2, C=1.0, pair="gdiff", balance="domain", cap=CAP_PER_DOMAIN_NEW,
         bias_at_gate=True, freq_cap_bi=15000, freq_cap_tri=30000),
    # ---- 第二次冻结副本（../aigc-corpus/selfbuild-frozen2-20261012，05:03 追加之后）：
    # 人写整篇 17,787 -> 35,614 计分句，整篇直出 1,819 -> 2,040，学生提示 523 -> 724，规避档 231 -> 730。
    # 下面四档与 SU/SV 同口径，只是多了 sb_domain_pair：同一学科的 selfbuild/h-x 与 selfbuild/m-x 并成一个域。
    # SW：SU + 学科域并档（学科配平这才真的在自建档上生效）
    dict(name="SW-sb2-pairdom", only=["sbwhole", "sbstudent", "sbevasive", "sbhuman"],
         ngram=(2, 3), min_df=2, C=1.0, pair="gdiff", balance="domain", cap=CAP_PER_DOMAIN_NEW,
         bias_at_gate=True, sb_domain_pair=True, balance_min_rows=100,
         freq_cap_bi=15000, freq_cap_tri=30000),
    # SX：SW + 按学科域内单变量 AUC 收窄特征面（一个特征要在多数学科里同向且幅度够才留）
    dict(name="SX-sb2-pairdom-prune", only=["sbwhole", "sbstudent", "sbevasive", "sbhuman"],
         ngram=(2, 3), min_df=2, C=1.0, pair="gdiff", balance="domain", cap=CAP_PER_DOMAIN_NEW,
         bias_at_gate=True, sb_domain_pair=True, balance_min_rows=100,
         freq_cap_bi=15000, freq_cap_tri=30000,
         prune="domain-auc", auc_min_rows=100, cand_mult=3, min_share=0.75, min_gap=0.02),
    # SY：SW + 按学科域内符号一致率收窄（同一件事的另一把尺子）
    dict(name="SY-sb2-pairdom-sign", only=["sbwhole", "sbstudent", "sbevasive", "sbhuman"],
         ngram=(2, 3), min_df=2, C=1.0, pair="gdiff", balance="domain", cap=CAP_PER_DOMAIN_NEW,
         bias_at_gate=True, sb_domain_pair=True, balance_min_rows=100,
         freq_cap_bi=15000, freq_cap_tri=30000, prune="domain-sign", min_share=0.8),
    # SZ：SW 去掉配对拟合（并档之后把 pair 这一刀单独摘出来量）
    dict(name="SZ-sb2-nopair", only=["sbwhole", "sbstudent", "sbevasive", "sbhuman"],
         ngram=(2, 3), min_df=2, C=1.0, balance="domain", cap=CAP_PER_DOMAIN_NEW,
         bias_at_gate=True, sb_domain_pair=True, balance_min_rows=100,
         freq_cap_bi=15000, freq_cap_tri=30000),
    # TA：把"一组最多 12 对"的上限抬到 48——追加之后每个学科两侧各有几百句，12 对让配对实际只用 132 条差样本
    dict(name="TA-sb2-pair48", only=["sbwhole", "sbstudent", "sbevasive", "sbhuman"],
         ngram=(2, 3), min_df=2, C=1.0, pair="gdiff", balance="domain", cap=CAP_PER_DOMAIN_NEW,
         bias_at_gate=True, sb_domain_pair=True, balance_min_rows=100, pair_per_group=48,
         freq_cap_bi=15000, freq_cap_tri=30000),
    # TB / TC：配对权重与普通权重按 1:blend 混合。SZ（不配对）③ AUC 最高但 ③ 误报 17.63，
    # 配对把误报压死之外还倒贴一点 AUC——混合比就是这两者之间的滑杆。
    dict(name="TB-sb2-blend05", only=["sbwhole", "sbstudent", "sbevasive", "sbhuman"],
         ngram=(2, 3), min_df=2, C=1.0, pair="gdiff", balance="domain", cap=CAP_PER_DOMAIN_NEW,
         bias_at_gate=True, sb_domain_pair=True, balance_min_rows=100, pair_per_group=48, blend=0.5,
         freq_cap_bi=15000, freq_cap_tri=30000),
    dict(name="TC-sb2-blend10", only=["sbwhole", "sbstudent", "sbevasive", "sbhuman"],
         ngram=(2, 3), min_df=2, C=1.0, pair="gdiff", balance="domain", cap=CAP_PER_DOMAIN_NEW,
         bias_at_gate=True, sb_domain_pair=True, balance_min_rows=100, pair_per_group=48, blend=1.0,
         freq_cap_bi=15000, freq_cap_tri=30000),
    # TD / TE：SZ 那一套权重不动（AUC 与工作点无关），只把公共侧挑偏置的目标从 2/千句 收到 0.2/千句，
    # 看真稿那一侧的误报能不能跟着落到门槛以内。TE 再叠一条按学科域符号一致率筛特征。
    dict(name="TD-sb2-nopair-strict", only=["sbwhole", "sbstudent", "sbevasive", "sbhuman"],
         ngram=(2, 3), min_df=2, C=1.0, balance="domain", cap=CAP_PER_DOMAIN_NEW,
         bias_at_gate=True, sb_domain_pair=True, balance_min_rows=100, bias_fp_target=0.2,
         freq_cap_bi=15000, freq_cap_tri=30000),
    dict(name="TE-sb2-nopair-sign", only=["sbwhole", "sbstudent", "sbevasive", "sbhuman"],
         ngram=(2, 3), min_df=2, C=1.0, balance="domain", cap=CAP_PER_DOMAIN_NEW,
         bias_at_gate=True, sb_domain_pair=True, balance_min_rows=100, bias_fp_target=0.2,
         prune="domain-sign", min_share=0.8,
         freq_cap_bi=15000, freq_cap_tri=30000),
    # TE 是这台机器上第一版"③ AUC 往上走、③ 误报还留在校栏以内"的配置（0.8032 / 0.00），围着它再切四刀：
    # TF：特征面放到 2-4 gram（筛特征之前候选面更宽）
    dict(name="TF-sb2-sign-24", only=["sbwhole", "sbstudent", "sbevasive", "sbhuman"],
         ngram=(2, 4), min_df=3, C=1.0, balance="domain", cap=CAP_PER_DOMAIN_NEW,
         bias_at_gate=True, sb_domain_pair=True, balance_min_rows=100, bias_fp_target=0.2,
         prune="domain-sign", min_share=0.8,
         freq_cap_bi=15000, freq_cap_tri=30000),
    # TG：符号一致率门槛从 0.8 提到 0.9（筛得更狠）
    dict(name="TG-sb2-sign-share90", only=["sbwhole", "sbstudent", "sbevasive", "sbhuman"],
         ngram=(2, 3), min_df=2, C=1.0, balance="domain", cap=CAP_PER_DOMAIN_NEW,
         bias_at_gate=True, sb_domain_pair=True, balance_min_rows=100, bias_fp_target=0.2,
         prune="domain-sign", min_share=0.9,
         freq_cap_bi=15000, freq_cap_tri=30000),
    # TH：min_df 从 2 抬到 3（少见字符组合在真稿侧覆盖不到，先在公共侧就丢掉）
    dict(name="TH-sb2-sign-mindf3", only=["sbwhole", "sbstudent", "sbevasive", "sbhuman"],
         ngram=(2, 3), min_df=3, C=1.0, balance="domain", cap=CAP_PER_DOMAIN_NEW,
         bias_at_gate=True, sb_domain_pair=True, balance_min_rows=100, bias_fp_target=0.2,
         prune="domain-sign", min_share=0.8,
         freq_cap_bi=15000, freq_cap_tri=30000),
    # TI：TE 的权重里掺 0.3 份配对权重——TE 的机器过线只有 3.77%，看配对能不能把召回换回来一点
    dict(name="TI-sb2-sign-blend03", only=["sbwhole", "sbstudent", "sbevasive", "sbhuman"],
         ngram=(2, 3), min_df=2, C=1.0, pair="gdiff", balance="domain", cap=CAP_PER_DOMAIN_NEW,
         bias_at_gate=True, sb_domain_pair=True, balance_min_rows=100, bias_fp_target=0.2,
         pair_per_group=48, blend=0.3, prune="domain-sign", min_share=0.8,
         freq_cap_bi=15000, freq_cap_tri=30000),
    # TI（0.8086 / 0.00）是这一轮综合最好的一版，最后围着它切三刀就收：min_df、特征面、混合比
    dict(name="TJ-sb2-sign-blend03-mindf3", only=["sbwhole", "sbstudent", "sbevasive", "sbhuman"],
         ngram=(2, 3), min_df=3, C=1.0, pair="gdiff", balance="domain", cap=CAP_PER_DOMAIN_NEW,
         bias_at_gate=True, sb_domain_pair=True, balance_min_rows=100, bias_fp_target=0.2,
         pair_per_group=48, blend=0.3, prune="domain-sign", min_share=0.8,
         freq_cap_bi=15000, freq_cap_tri=30000),
    dict(name="TK-sb2-sign-blend03-24", only=["sbwhole", "sbstudent", "sbevasive", "sbhuman"],
         ngram=(2, 4), min_df=3, C=1.0, pair="gdiff", balance="domain", cap=CAP_PER_DOMAIN_NEW,
         bias_at_gate=True, sb_domain_pair=True, balance_min_rows=100, bias_fp_target=0.2,
         pair_per_group=48, blend=0.3, prune="domain-sign", min_share=0.8,
         freq_cap_bi=15000, freq_cap_tri=30000),
    dict(name="TL-sb2-sign-blend06", only=["sbwhole", "sbstudent", "sbevasive", "sbhuman"],
         ngram=(2, 3), min_df=2, C=1.0, pair="gdiff", balance="domain", cap=CAP_PER_DOMAIN_NEW,
         bias_at_gate=True, sb_domain_pair=True, balance_min_rows=100, bias_fp_target=0.2,
         pair_per_group=48, blend=0.6, prune="domain-sign", min_share=0.8,
         freq_cap_bi=15000, freq_cap_tri=30000),
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


# -------------------------------------------- 2026-10-12 新增：配对拟合、按域 AUC 筛特征、标定池、域降权

def dom_in(d, names):
    """域匹配：允许 "academic-abstract/工学" 这类学科子域挂在 "academic-abstract" 名下一起算。"""
    for n in names:
        if d == n or d.startswith(n + "/"):
            return True
    return False


def academic_mask(dom):
    return np.array([dom_in(d, ACADEMIC_DOMAINS) for d in dom], dtype=bool)


class FlatModel(object):
    """只剩一个权重向量和一个截距的"模型"。decision_function / coef_ / intercept_ 都在，
    打分与导出两侧只用到这三样，所以两份权重线性混合之后还能当出厂那份用。"""

    def __init__(self, w, b=0.0):
        self.coef_ = np.array([np.asarray(w, dtype=np.float64)])
        self.intercept_ = np.array([float(b)])

    def decision_function(self, X):
        return X.dot(self.coef_[0]) + self.intercept_[0]


def _unit(v):
    n = float(np.linalg.norm(v))
    return np.asarray(v, dtype=np.float64) / (n if n > 1e-12 else 1.0)


def choose_bias(scores, va, y, dom, cfg=None, comps=None):
    """偏置只在公共真人留出侧挑：让 P>=0.5 的真人句比例不超过 2/1000（原来内联在 gate 与 compare 里，
    现在两边共用一条口径）。bias_pool="formal" 时只用学术/新闻那几档的真人句挑——真稿正文的分数尾巴
    像它们，不像口语问答；拿全体公共真人句一起挑，门槛会放得比真稿需要的松。仍然一个字不碰真稿留出。
    bias_fp_target 覆盖这里的目标（默认就是出厂那条 2/千句）：调工作点用的，AUC 不受它影响。
    bias_at_gate=True 把这里量的门槛从 0.5 换成出厂那把尺子用的 0.45（FLAG_GATE）——出厂门槛与导出清单
    都在 0.45 上数真人误报，挑偏置却在 0.5 上数，两边本来就差着一截；换成同一条尺子是事前就能定的口径，
    不看真稿留出。"""
    m = va & (y == 0)
    pool = cfg.get("bias_pool") if cfg else None
    if pool == "formal" and dom is not None:
        f = m & academic_mask(np.asarray(dom))
        if int(f.sum()) >= 400:
            m = f
    elif pool == "longform" and comps is not None:
        lo, hi = cfg.get("bias_len", (25, 90))      # 真稿 H1 句长中位 46；公共真人句普遍短得多
        ln = np.array([len(c) for c in comps])
        f = m & (ln >= lo) & (ln <= hi)
        if int(f.sum()) >= 400:
            m = f
    hum = scores[m]
    target = float(cfg.get("bias_fp_target", GATE_FP_PER_MILLE)) if cfg else float(GATE_FP_PER_MILLE)
    thr = FLAG_GATE if (cfg and cfg.get("bias_at_gate")) else 0.5
    bias = 0.0
    for b in np.arange(0.0, -14.0, -0.02):
        if float(np.mean(sigmoid(hum + b) >= thr)) <= target / 1000.0:
            bias = float(b)
            break
    return bias


def domain_style_weight(y, dom, rules):
    """按域前缀降权/升权，rules = [("drlx-webtext", 0.3), ...]：与学位论文正文分布差太远的料少说话。
    乘在域内配平之上，只动拟合侧的样本权重，不动句数、不动留出侧。"""
    w = np.ones(len(y), dtype=np.float64)
    for i, d in enumerate(dom):
        for pre, mult in rules:
            if d == pre or d.startswith(pre):
                w[i] = float(mult)
                break
    return w


PAIR_MAX_PER_GROUP = 12      # 一组（同一篇人类原文与它的机器改写版）最多取几对
PAIR_MAX_TOTAL = 40000       # 差样本总条数上限：机器 16 GB，配对料再多也不许把内存吃穿


def _even_pick(seq, k):
    if len(seq) <= k:
        return list(seq)
    if k <= 1:
        return [seq[0]]
    return [seq[int(round(j * (len(seq) - 1) / float(k - 1)))] for j in range(k)]


def pair_diff_fit(X, y, gids, dom, cfg, mask=None):
    """配对拟合（配置里 pair="gdiff"）：只在同时有人写句与机器句的组内做差。
    组内按句序一一配对（润色稿大体保留句序），两边句数不等时再补一条"组均值差"；每条差向量
    和它的相反数各算一个样本，标签 1/0。同组两侧一样的东西——选题、学科、领域、作者习惯——
    全被减掉，w 只能学改写痕迹。返回 (逻辑回归, 差样本条数, 用到的组数)。"""
    from scipy import sparse as sp
    sel = np.ones(len(y), dtype=bool) if mask is None else np.asarray(mask, dtype=bool)
    groups = {}
    for i, g in enumerate(gids):
        if sel[i]:
            groups.setdefault(g, []).append(i)
    cands = []
    for g, idx in groups.items():
        h = [i for i in idx if y[i] == 0]
        m = [i for i in idx if y[i] == 1]
        if h and m:
            cands.append((g, h, m))
    cands.sort(key=lambda t: int(hashlib.md5(t[0].encode("utf-8")).hexdigest()[:8], 16))
    total = int(cfg.get("pair_total", PAIR_MAX_TOTAL))
    rows, wts, dcols = [], [], []
    for g, h, m in cands:
        if len(rows) >= total:
            break
        k = min(len(h), len(m), int(cfg.get("pair_per_group", PAIR_MAX_PER_GROUP)))
        hh, mm = _even_pick(h, k), _even_pick(m, k)
        diffs = [X[j] - X[i] for i, j in zip(hh, mm)]
        if len(h) != len(m):
            diffs.append(sp.csr_matrix((np.asarray(X[m].mean(axis=0)).ravel() -
                                        np.asarray(X[h].mean(axis=0)).ravel()).reshape(1, -1)))
        wt = 1.0 / len(diffs)          # 一组一票：长文档不许靠句数压过短文档
        for d, j in zip(diffs, mm):
            rows.append(d)
            wts.append(wt)
            dcols.append(dom[j])
    if not rows:
        return None, 0, 0
    D = sp.vstack(rows).tocsr()
    Xd = sp.vstack([D, -D]).tocsr()
    yd = np.r_[np.ones(D.shape[0], dtype=np.int64), np.zeros(D.shape[0], dtype=np.int64)]
    w = np.array(wts + wts, dtype=np.float64)
    dcols = np.array(dcols + dcols)
    for d in set(dcols.tolist()):      # 域之间等权，与 domain_balance_weight 同一口径
        s = dcols == d
        if s.sum():
            w[s] *= 1.0 / float(s.sum())
    w *= len(w) / w.sum()
    mdl = lr(float(cfg.get("C", 1.0)), 3000)
    mdl.fit(Xd, yd, sample_weight=w)
    return mdl, int(D.shape[0]), len(cands)


def _auc_hist(M, yy, ncol, cap=63):
    """一域一矩阵：每个候选列在该域内的单变量 AUC（机器句的原始计数排在真人句之上的概率，并列记 0.5；
    两边都没出现过这个特征也算并列）。计数截到 cap，按 (列, 计数) 直方图整域一次算完，不逐列排序。"""
    nh, nm = int((yy == 0).sum()), int((yy == 1).sum())
    if not nh or not nm:
        return np.full(ncol, 0.5, dtype=np.float32)
    hist = []
    for lab in (0, 1):
        s = M[yy == lab].tocsr()          # csr 的 indices 就是列号，直方图按 (列, 计数) 建
        col = s.indices.astype(np.int64)
        v = np.minimum(np.asarray(s.data, dtype=np.int64), cap)
        u, c = np.unique(col * (cap + 1) + v, return_counts=True)
        hist.append((u, c.astype(np.float64)))
    allk = np.union1d(hist[0][0], hist[1][0])
    if not len(allk):
        return np.full(ncol, 0.5, dtype=np.float32)

    def align(u, c):
        p = np.clip(np.searchsorted(u, allk), 0, len(u) - 1)
        hit = u[p] == allk
        a = np.zeros(len(allk), dtype=np.float64)
        a[hit] = c[p[hit]]
        return a

    v0, v1 = align(*hist[0]), align(*hist[1])
    col = allk // (cap + 1)
    starts = np.flatnonzero(np.r_[np.ones(1, dtype=bool), col[1:] != col[:-1]])
    grp = np.repeat(np.arange(len(starts)), np.diff(np.r_[starts, len(col)]))
    cs0 = np.cumsum(v0)
    before = np.r_[0.0, cs0[starts[1:] - 1]]       # 每列起点之前的累计人头：取起点前一位的前缀和                 # 每列起点之前的人头累计
    excl = cs0 - before[grp] - v0                         # 同列里计数严格更小的人头
    tot0 = np.bincount(col, weights=v0, minlength=ncol)
    tot1 = np.bincount(col, weights=v1, minlength=ncol)
    z0, z1 = nh - tot0, nm - tot1                         # 该特征没出现在那些行里
    win = v1 * (z0[col] + excl + 0.5 * v0)
    num = np.bincount(col, weights=win, minlength=ncol) + 0.5 * z1 * z0
    return (num / float(nh * nm)).astype(np.float32)


def domain_auc_matrix(vec, cand, comps, y, dom, tr, cfg):
    """每个候选特征在每个域内的单变量 AUC。只用拟合侧的行，一个域最多抽 auc_rows 句。
    这是"按域"而不是"按公共侧总体"：一个特征必须在多数域里都朝着同一边、而且差得够多才算数，
    公共侧总 AUC 很高但只在两三个域里有用的那种特征会被刷掉。真稿留出全程不参与。"""
    inv = {v: k for k, v in vec.vocabulary_.items()}
    vocab, keep_cols = {}, []
    for c in cand:
        g = inv.get(c)
        if g is not None and g not in vocab:
            vocab[g] = len(keep_cols)
            keep_cols.append(c)
    vc = CountVectorizer(analyzer="char", ngram_range=tuple(vec.ngram_range), lowercase=False, vocabulary=vocab)
    doms = both_class_domains(y[tr], dom[tr], min_rows=int(cfg.get("auc_min_rows", 300)))
    A = np.full((len(doms), len(keep_cols)), 0.5, dtype=np.float32)
    n_rows = int(cfg.get("auc_rows", 20000))
    for di, d in enumerate(doms):
        idx = np.where(tr & (dom == d))[0]
        if len(idx) > n_rows:
            idx = idx[np.linspace(0, len(idx) - 1, n_rows).astype(int)]
        yy = y[idx]
        if int(yy.sum()) == 0 or int(yy.sum()) == len(yy):
            continue
        A[di] = _auc_hist(vc.transform([comps[i] for i in idx]), yy, len(keep_cols))
    return A, doms, np.array(keep_cols, dtype=np.int64)


def select_by_domain_auc(A, doms, cfg):
    """按域 AUC 矩阵挑特征：符号一致率 >= min_share 且域间平均 |AUC-0.5| >= min_gap 才留，
    留下的按 一致率 × 平均幅度 排序。返回 (候选下标, 诊断)。"""
    dev = A - 0.5
    sgn = np.sign(dev.mean(axis=0))
    share = (np.sign(dev) == sgn[None, :]).mean(axis=0)
    mag = np.abs(dev).mean(axis=0)
    worst = np.abs(dev).min(axis=0)
    ok = (sgn != 0) & (share >= float(cfg.get("min_share", 0.75))) & (mag >= float(cfg.get("min_gap", 0.02)))
    score = np.where(ok, share * mag, -1.0)
    keep = [int(i) for i in np.argsort(-score) if ok[i]]
    diag = dict(n_domains=len(doms), n_candidates=int(A.shape[1]), n_kept=len(keep),
                min_share=float(cfg.get("min_share", 0.75)), min_gap=float(cfg.get("min_gap", 0.02)),
                mag_p50_kept=round(float(np.median(mag[keep])), 4) if keep else None,
                worst_p50_kept=round(float(np.median(worst[keep])), 4) if keep else None)
    return np.array(keep, dtype=np.int64), diag


def fit_config(data_dir, norm, cfg, want_lodo=True, verbose=True):
    rows, stats = rows_of(data_dir, norm, only=cfg.get("only"), subject_split=bool(cfg.get("subject_split")))
    if cfg.get("drop"):
        rows = [r for r in rows if not any(r[0].startswith(d) for d in cfg["drop"])]
    if cfg.get("sb_domain_pair"):      # 自建档每个学科本来是两个域（selfbuild/h-x 与 selfbuild/m-x，各自只有一个类别），
        def _sb_dom(d):                # 于是 both_class_domains 在一个自建档域里都找不到，域配平和按域 AUC 筛特征
            if not d.startswith("selfbuild/"):      # 在这一档上全都空转。这里把同一学科的人写与机器并成一个域。
                return d
            t = d[len("selfbuild/"):]
            return "selfbuild/" + (t[2:] if t[:2] in ("h-", "m-") else t)
        rows = [r[:5] + (_sb_dom(r[5]),) for r in rows]
    if cfg.get("len_range"):          # 只筛训练侧句长（短句、问答体、摘要体离学位论文正文太远）；
        lo, hi = cfg["len_range"]     # 真稿留出永远全量照判，这一条不改口径
        rows = [r for r in rows if lo <= len(r[4]) <= hi]

    def rearray(rs):
        return (np.array([q[0] for q in rs]), np.array([q[1] for q in rs]), np.array([q[5] for q in rs]),
                np.array([q[2] for q in rs]), [q[4] for q in rs])

    gids, codes, doms_row, y, comps = rearray(rows)
    if cfg.get("keep_domains"):
        keep_d = np.array([dom_in(d, cfg["keep_domains"]) for d in doms_row])
        rows = [r for r, k in zip(rows, keep_d) if k]
        gids, codes, doms_row, y, comps = rearray(rows)
    if cfg.get("sb_dedupe"):        # 自造真人整篇档是上游公开集的重新打包（与全体中文公共池逐字重复 95.8%）：
        pub = set(c for c, cd in zip(comps, codes) if cd not in SELFBUILD_LABEL)   # 与本池其他来源逐字相同的句子
        if pub:                                        # 一律剔掉，剩下的才算这一档带来的新料
            drop_sb = np.array([cd in SELFBUILD_LABEL and c in pub for c, cd in zip(comps, codes)])
            if int(drop_sb.sum()):
                print("[%s] 自造档去重：剔掉与本池其他来源逐字相同的 %d 句（剩 %d 句自造料）" %
                      (cfg["name"], int(drop_sb.sum()), int((~drop_sb & np.isin(codes, list(SELFBUILD_LABEL))).sum())))
                rows = [r for r, k in zip(rows, ~drop_sb) if k]
                gids, codes, doms_row, y, comps = rearray(rows)
    kept = cap_by_domain(list(gids), doms_row, cfg.get("cap"))
    if not kept.all():
        print("[%s] 每域上限 %d 句：%d -> %d（被砍掉的是句数最多的那几个域，按组哈希整组砍）" %
              (cfg["name"], cfg["cap"], len(rows), int(kept.sum())))
        rows = [r for r, k in zip(rows, kept) if k]
        gids, codes, doms_row, y, comps = rearray(rows)
    fv = np.array([fold_of(g) for g in gids])
    tr, va = fv != 0, fv == 0
    # 跨语料逐字重复：同一条 HC3 句子在镜像集里也出现过，训练侧必须剔掉公共留出里出现过的原句
    va_set = set(comps[i] for i in np.where(va)[0])
    dup = np.array([c in va_set for c in comps]) & tr
    n_dup = int(dup.sum())
    tr = tr & ~dup
    bd = both_class_domains(y[tr], doms_row[tr], min_rows=int(cfg.get("balance_min_rows", LODO_MIN_ROWS)))
    vec = CountVectorizer(analyzer="char", ngram_range=tuple(cfg["ngram"]), lowercase=False,
                          min_df=cfg["min_df"])
    vec.fit([comps[i] for i in np.where(tr)[0]])
    X = vectors(comps, vec)
    share = None
    if cfg.get("prune") == "domain-sign":
        share, _ = domain_sign_share(X, y, doms_row, both_class_domains(
            y, doms_row, min_rows=int(cfg.get("balance_min_rows", LODO_MIN_ROWS))))
    keep_mask = None if share is None else (share >= cfg.get("min_share", 0.75))
    sw = domain_balance_weight(y, doms_row, bd) if cfg.get("balance") == "domain" else None
    if cfg.get("domain_weight"):                   # 域降权：配平先把域拉平，再按"离论文正文远不远"缩放
        mult = domain_style_weight(y, doms_row, cfg["domain_weight"])
        sw = mult if sw is None else sw * mult
        sw = sw * (len(sw) / sw.sum())
    m1 = fit_side(X, y, tr, sw, cfg["C"])
    w_all = m1.coef_[0]
    rank = np.abs(w_all) if keep_mask is None else np.where(keep_mask, np.abs(w_all), -1.0)
    pool = np.sort(np.argsort(-rank)[:(KEEP_FEATURES * int(cfg.get("cand_mult", 1)))])
    dauc = None
    if cfg.get("prune") == "domain-auc":           # 候选面放宽，再按"域内单变量 AUC"收窄到 KEEP_FEATURES
        A, adoms, cand = domain_auc_matrix(vec, pool, comps, y, doms_row, tr, cfg)
        sel_idx, dauc = select_by_domain_auc(A, adoms, cfg)
        pool = cand[sel_idx] if len(sel_idx) else pool
    cols = np.sort(pool[:KEEP_FEATURES])
    sub = X[:, cols]
    sub = sub.multiply((1.0 / np.maximum(np.sqrt(np.asarray(sub.multiply(sub).sum(axis=1)).ravel()), 1e-9))[:, None]).tocsr()
    n_pair = n_pair_groups = 0
    if cfg.get("pair") == "gdiff":                 # 权重由组内差样本学出来，特征子集与上面同一套
        m2, n_pair, n_pair_groups = pair_diff_fit(sub, y, gids, doms_row, cfg, mask=tr)
        if m2 is None:
            print("[%s] 警告：没有同时含人写句与机器句的组，配对拟合退回普通拟合" % cfg["name"])
            m2 = fit_side(sub, y, tr, sw, cfg["C"])
    else:
        m2 = fit_side(sub, y, tr, sw, cfg["C"])
    if cfg.get("blend") is not None and n_pair:   # 两份权重各归一化再相加：还是"一个权重向量"，导出格式不变
        w_plain = fit_side(sub, y, tr, sw, cfg["C"]).coef_[0]
        w_pair0 = m2.coef_[0]
        w_new = _unit(w_pair0) + float(cfg["blend"]) * _unit(w_plain)
        # 混合后的尺度与逻辑回归系数完全不同，不先把尺度对回来，后面挑偏置那一步会被顶穿
        zp = np.asarray(sub[tr].dot(w_pair0)).ravel()
        zn = np.asarray(sub[tr].dot(w_new)).ravel()
        m2 = FlatModel(w_new * (float(np.std(zp)) / max(1e-9, float(np.std(zn)))), 0.0)   # 两份权重各归一化再相加：还是"一个权重向量"，导出格式不变
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
            mm = None
            if cfg.get("pair") == "gdiff":
                mm, _, _ = pair_diff_fit(sub, y, gids, doms_row, cfg, mask=sel)
            if mm is None:
                mm = fit_side(sub, y, sel, sw, cfg["C"], max_iter=300)
            lodo[d] = dict(n=int(te.sum()), n_train=int(sel.sum()),
                           auc=round(auc(y[te], mm.decision_function(sub[te])), 4))
    res = dict(name=cfg["name"], rows=rows, y=y, tr=tr, va=va, vec=vec, cols=cols, model=m2,
               vocab=len(vec.vocabulary_), n=len(rows), n_sent=int(tr.sum()), n_dup=n_dup,
               n_pair=n_pair, n_pair_groups=n_pair_groups, domain_auc=dauc,
               auc_tr=round(auc(y[tr], p[tr]), 4), auc_va=round(auc(y[va], p[va]), 4),
               per=per, per_domain=per_dom, lodo=lodo, share_kept=kept_share,
               domains=sorted(set(doms_row)), both_class=both_class_domains(y, doms_row),
               cfg=cfg, scores=p, stats=stats)
    if verbose:
        print("[%s] 句 %-8d 重复剔除 %-6d 词表 %-7d 导出 %-5d  公共拟合 %.4f 公共留出 %.4f  分域 %s" %
              (res["name"], res["n"], res["n_dup"], res["vocab"], len(cols), res["auc_tr"], res["auc_va"],
               {k: v["auc"] for k, v in sorted(per_dom.items())}))
        if n_pair:
            print("      配对拟合：%d 个组同时有人写句与机器句，取 %d 条组内差样本（每条连它的相反数一起进拟合）" %
                  (n_pair_groups, n_pair))
        if dauc:
            print("      按域 AUC 筛特征：%d 个域 × %d 个候选，留下 %d 条（一致率>=%s 且平均|AUC-0.5|>=%s）" %
                  (dauc["n_domains"], dauc["n_candidates"], dauc["n_kept"], dauc["min_share"], dauc["min_gap"]))
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
    rule_pick = max(ok, key=lambda r: (r["proxy"], r["auc_va"]))
    pick = rule_pick
    if getattr(args, "pick", ""):
        hit = [r for r in results if r["name"] == args.pick]
        if not hit:
            raise SystemExit("--pick 指定的配置不在这一批里：%s（这一批：%s）" %
                             (args.pick, ",".join(r["name"] for r in results)))
        pick = hit[0]
        print("注意：--pick 强行指定了 %s；按公共侧规则那一条选的是 %s。"
              "按真稿成绩挑配置等于把真稿留出用掉一次，这份产物的真稿数不能再当独立复核用。" %
              (pick["name"], rule_pick["name"]))
    for r in results:
        ls = r["lodo_sum"] or dict(mean=float("nan"), worst=float("nan"), n_domains=0)
        print("[%s] 公共留出 %.4f  学术档 %.4s  跨域均值 %.4f 跨域最差 %.4f（%d 个域）  同号特征 %s" %
              (r["name"], r["auc_va"], ("%.4f" % r["proxy"]) if r["proxy"] else "  —  ",
               ls["mean"], ls["worst"], ls["n_domains"],
               "-" if r["share_kept"] is None else "%.2f" % float(np.mean(r["share_kept"]))))
        print("      现代模型同域那一栏（学术域：真人摘要 vs 2024-2025 模型整篇生成）= %s" %
              (("%.4f" % r["modern_proxy"]) if r.get("modern_proxy") is not None else "这批配置里没有 MAGA 的学术档"))
    print("\n选择规则=公共侧学术档配对留出最高（不看真稿留出）：选 %s（学术档 %s，公共留出 %.4f）" %
          (pick["name"], ("%.4f" % pick["proxy"]) if pick["proxy"] is not None
           else "无：这一档的公共侧没有学术配对档（只用自建档的配置就是这种）", pick["auc_va"]))
    summary = [dict(name=r["name"], n=r["n"], n_dup_with_valid=r["n_dup"], vocab=r["vocab"],
                    features=len(r["cols"]), auc_fit=r["auc_tr"], auc_valid=r["auc_va"],
                    academic_proxy=r["proxy"], modern_academic_proxy=r.get("modern_proxy"),
                    pair_diff_rows=r.get("n_pair", 0), pair_diff_groups=r.get("n_pair_groups", 0),
                    domain_auc_filter=r.get("domain_auc"),
                    per_source=r["per"], per_domain=r["per_domain"],
                    lodo=r["lodo"], lodo_summary=r["lodo_sum"],
                    share_kept=None if r["share_kept"] is None else dict(
                        features=int(len(r["share_kept"])),
                        mean=round(float(np.mean(r["share_kept"])), 4),
                        p50=round(float(np.median(r["share_kept"])), 4),
                        min=round(float(np.min(r["share_kept"])), 4)),
                    cfg={k: v for k, v in r["cfg"].items() if k != "drop"})
               for r in results]
    json.dump(dict(picked=pick["name"], rule_pick=rule_pick["name"],
                   forced_pick=bool(getattr(args, "pick", "")), rule="公共侧学术配对档（人写原稿 vs 同一篇的 ChatGPT 润色）逐来源 AUC 最差值最大；"
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

def build_freq(texts, min_bi=3, min_tri=5, alpha=4.0, cap_bi=0, cap_tri=0):
    """cap_bi / cap_tri：随包频率表有条数上限（三个导出文件加起来必须 <= 1.5 MB），
    超了就按出现次数留最稳的那批；三字表回退时查不到的二字项退回单字，与 Java 侧 t->b->u 一致。"""
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
    if cap_bi and len(lb) > int(cap_bi):          # 二字表超出上限：留出现次数最多的那些
        for g in sorted(lb, key=lambda k: -bi[k])[int(cap_bi):]:
            del lb[g]
    for g, n in tri.items():
        if n < min_tri or len(g) != 3:
            continue
        pb = 2.0 ** lb.get(g[1:], lu.get(g[2], oov))
        lt[g] = math.log2((n + alpha * pb) / (ctx_t[g[:2]] + alpha))
    if cap_tri and len(lt) > int(cap_tri):
        for g in sorted(lt, key=lambda k: -tri[k])[int(cap_tri):]:
            del lt[g]
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

def window_block(repo, norm, rows, cfg=None):
    """滑窗困惑度代理：频率表只用公开真人语料，阈值取公共真人窗口的 p0.1。
    从 gate 里抽出来是给 compare --window 用的，两边同一条口径。
    freq_pool：formal（默认，学术/百科/法律/医学/金融那几档真人句）/ human（全部公开真人句）；
    freq_cap_bi / freq_cap_tri：二字、三字表条数上限（随包三件加起来要 <= 1.5 MB）。
    返回 (window 那一节 dict, (lu, oov, lb, lt, thr))。"""
    cfg = cfg or {}
    human_public = [r[4] for r in rows if r[2] == 0]
    formal = [r[4] for r in rows if r[2] == 0 and (r[1].startswith("prhpp") or
              r[1] == "hc3zh" and r[0].split(":")[1] in ("baike", "medicine", "law", "finance") or
              r[1] == "anxzh" and r[5] == "news")]
    pool = [r[4] for r in rows if r[2] == 0 and (
        r[1].startswith("prhpp") or r[1] == "hc3zh" or
        (r[1] == "anxzh" and r[5] in ("news", "essay")))] if cfg.get("freq_pool") == "formal+" else \
        (human_public if cfg.get("freq_pool") == "human" else formal)
    if not pool:
        pool = human_public or formal
    lu, oov, lb, lt = build_freq(pool, min_bi=int(cfg.get("freq_min_bi", 3)),
                                 min_tri=int(cfg.get("freq_min_tri", 5)),
                                 cap_bi=cfg.get("freq_cap_bi"), cap_tri=cfg.get("freq_cap_tri"))
    pub_win = [bits_per_char(s[a:b], lu, oov, lb, lt)
               for s in human_public[:40000] for a, b in windows(norm.compact(s)) if b - a >= WINDOW_MIN]
    pub_win = np.array(pub_win)
    pools = template_rows(repo, norm)
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
    return dict(threshold_bits_per_char=round(thr, 4), table=dict(uni=len(lu), bi=len(lb), tri=len(lt)),
                public_windows=len(pub_win),
                template_segment_hit_rate=round(tpl_hit, 4), human_segment_hit_rate=round(hum_hit, 4),
                human_window_rate=round(100.0 * win_tot[0][1] / max(1, win_tot[0][0]), 3),
                machine_window_rate=round(100.0 * win_tot[1][1] / max(1, win_tot[1][0]), 3),
                per_tier=seg_stat, threshold_curve=curve, freq_pool=cfg.get("freq_pool", "formal"),
                best_zero_human_point=sweep_best), (lu, oov, lb, lt, thr)


# ---------------------------------------------------------------- 出厂门槛

def stage_compare(args):
    """逐配置三档留出对照表 + "公共侧哪一栏预测得了真稿"的相关系数。
    这里只报数、不选配置（选择规则在 stage_train）；跨域 LODO 那一列由 train 那一步的
    train-summary.json 提供，这里不重跑（重跑一遍要多花十几分钟）。"""
    norm = Normalizer(load_trad_table())
    ho = holdout_rows(args.repo, norm)
    yh = np.array([r[1] for r in ho])
    out_rows = []
    wanted = set(args.configs.split(",")) if getattr(args, "configs", "") else None
    for cfg in CONFIGS:
        if wanted and cfg["name"] not in wanted:
            continue
        r = fit_config(args.data, norm, cfg, want_lodo=False, verbose=False)
        vec, cols, m2 = r["vec"], r["cols"], r["model"]
        y, va, scores = r["y"], r["va"], r["scores"]
        dom = np.asarray([q[5] for q in r["rows"]])
        bias = choose_bias(scores, va, y, dom, cfg, comps=[q[4] for q in r["rows"]])

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
        wb = None
        if getattr(args, "window", False):      # 滑窗两栏只依赖频率表（公开真人语料），要就算，不碰标签
            wb, _ = window_block(args.repo, norm, r["rows"], cfg)
        out_rows.append(dict(name=cfg["name"], n=r["n"], features=len(cols),
                             academic_proxy=academic_proxy(r), public=pub, academic=aca, holdout=real,
                             modern_academic_proxy=modern_academic_proxy(r),
                             pair_diff_rows=r.get("n_pair", 0), pair_diff_groups=r.get("n_pair_groups", 0),
                             domain_auc_filter=r.get("domain_auc"),
                             window=None if wb is None else dict(
                                 threshold_bits=wb["threshold_bits_per_char"],
                                 table=wb["table"], freq_pool=wb["freq_pool"],
                                 template_segment_hit=wb["template_segment_hit_rate"],
                                 human_segment_hit=wb["human_segment_hit_rate"],
                                 best_zero_human_point=wb["best_zero_human_point"])))
        print("[%s] 句 %-7d  ① 公共 %.4f / 误报 %.2f  ② 学术 %s / 误报 %s  ③ 真稿 %.4f / 误报 %.2f / 机器过线 %.2f%%" %
              (cfg["name"], r["n"], pub["auc"], pub["fp_per_mille"],
               ("%.4f" % aca["auc"]) if aca["auc"] is not None else "  —  ",
               ("%.2f" % aca["fp_per_mille"]) if aca["fp_per_mille"] is not None else "  —  ",
               real["auc"], real["fp_per_mille"], real["machine_hit_percent"]), flush=True)
        extra = []
        if r.get("n_pair"):
            extra.append("配对差样本 %d 条 / %d 组" % (r["n_pair"], r["n_pair_groups"]))
        if r.get("domain_auc"):
            extra.append("按域 AUC 筛特征：%d 域 × %d 候选 -> 留 %d 条" %
                         (r["domain_auc"]["n_domains"], r["domain_auc"]["n_candidates"],
                          r["domain_auc"]["n_kept"]))
        if wb is not None:
            extra.append("滑窗 模板段 %.1f%% / 真人段 %.1f%%（门槛 90%% / 0%%）" %
                         (100 * wb["template_segment_hit_rate"], 100 * wb["human_segment_hit_rate"]))
        if extra:
            print("      " + "　".join(extra))

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
    # 偏置只在公共真人验证侧挑：让 P>=0.5 的真人句比例不超过 2/1000（口径见 choose_bias）
    cfg_g = cache.get("cfg") or {}
    bias = choose_bias(scores, va, y, dom, cfg_g, comps=[q[4] for q in rows])
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
    # 留出集的语料文件开头有 # 说明行（语料作者写的元数据），它们也按所属文件的标签进了计分句。
    # 这一栏只诊断"这批元数据行在多大程度上影响了读数"，出厂门槛仍按全量判，口径不动。
    hdr = np.array([r[3].lstrip().startswith("#") for r in ho])
    body = ~hdr
    nh0 = max(1, int((body & (yh == 0)).sum()))
    holdout_no_header = dict(
        n_excluded=int(hdr.sum()), n_kept=int(body.sum()),
        per_file_excluded={c: int(((codes == c) & hdr).sum()) for c in sorted(set(codes)) if int(((codes == c) & hdr).sum())},
        auc=round(auc(yh[body], p[body]), 4),
        fp_per_mille_at_045=round(1000.0 * float(np.sum(p[body & (yh == 0)] >= FLAG_GATE)) / nh0, 3),
        note="# 说明行不是被打分的稿子（M-DOMAIN 那份 110 句里占 38 句）；这一栏只说明门槛数受它影响多大，不参与门槛判定")

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

    # 滑窗困惑度：口径抽到 window_block，compare --window 走的是同一份代码
    win, (lu, oov, lb, lt, thr) = window_block(args.repo, norm, rows, cfg_g)
    lodo = cache.get("lodo") or {}
    lodo_vals = [v["auc"] for v in lodo.values() if v["auc"] == v["auc"]]
    out = dict(model_version=MODEL_VERSION, bias=round(float(bias), 4),
               public=public, academic=academic, per_domain_public=per_domain,
               cross_domain=dict(lodo=lodo,
                                 mean=round(float(np.mean(lodo_vals)), 4) if lodo_vals else None,
                                 worst=round(float(np.min(lodo_vals)), 4) if lodo_vals else None,
                                 note="轮流把整个域从拟合侧撤掉，只在被撤掉那个域的公共留出上量分；真稿留出全程没参与"),
               domain_gap=domain_gap, length_norm_probe=norm_probe,
               holdout=dict(n=int(len(ho)), auc=sent_auc, auc_paragraph=seg_auc,
                            per_file=per_file, pair_auc=pair_auc, length_only_auc=len_only_auc,
                            fp_per_mille_at_045=round(fp, 3), fp_per_mille_at_050=round(fp05, 3),
                            machine_hit_percent_at_045=round(hit, 2), tiers=tiers,
                            operating_point=operating_point(p, yh),
                            without_header_lines=holdout_no_header),
               window=win,
               gates=dict(auc_required=GATE_AUC, fp_required=GATE_FP_PER_MILLE,
                          template_required=GATE_TEMPLATE_HIT, human_window_allowed=GATE_HUMAN_WINDOW,
                          auc_pass=sent_auc >= GATE_AUC, fp_pass=fp <= GATE_FP_PER_MILLE,
                          template_pass=win["template_segment_hit_rate"] >= GATE_TEMPLATE_HIT,
                          human_window_pass=win["human_segment_hit_rate"] <= GATE_HUMAN_WINDOW))
    out["gates"]["calibrated"] = bool(out["gates"]["auc_pass"] and out["gates"]["fp_pass"])
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
    if lodo_vals:
        print("  跨域留出（撤掉整个域再考）均值 %.4f 最差 %.4f（%d 个域）" %
              (float(np.mean(lodo_vals)), float(np.min(lodo_vals)), len(lodo_vals)))
    print(json.dumps({k: out[k] for k in ("holdout", "gates")}, ensure_ascii=False, indent=1)[:1600])
    print("滑窗：阈值 %.3f bits/字  模板段落点 %.1f%%（门槛 90%%）  真人段落点 %.1f%%（门槛 0%%）  窗口级 真人 %.2f%% / 机器 %.2f%%"
          % (thr, 100 * win["template_segment_hit_rate"], 100 * win["human_segment_hit_rate"], out["window"]["human_window_rate"], out["window"]["machine_window_rate"]))
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
    """导出随包三件。默认写 app/src/main/assets/aigc/；--out 指到别处就只写那份暂存，
    assets 一个字节都不动（本轮的活是把当前最好那版放到 artifacts/agent-aigc-offline/export/ 等着复核）。"""
    import pickle
    out_dir = getattr(args, "out", "") or ASSETS
    version = getattr(args, "model_version", "") or MODEL_VERSION
    created = getattr(args, "created", "") or CREATED
    gate = json.load(io.open(os.path.join(ART, "gate.json"), encoding="utf-8"))
    with open(os.path.join(ART, "model-cache.pkl"), "rb") as f:
        cache = pickle.load(f)
    with open(os.path.join(ART, "freq-cache.pkl"), "rb") as f:
        freq = pickle.load(f)
    vec, cols, model = cache["vec"], cache["cols"], cache["model"]
    only = cache["cfg"].get("only")
    present = set(r[1] for r in cache["rows"])      # 这一版真正读到、且没被域筛/每域上限清零的来源才算"训练用过"
    uses = (lambda code: code in present) if only is None else (lambda code: code in only and code in present)
    inv = {v: k for k, v in vec.vocabulary_.items()}
    w = model.coef_[0]
    os.makedirs(out_dir, exist_ok=True)
    pairs = [(inv[c], float(w[i])) for i, c in enumerate(cols) if c in inv]
    pairs.sort(key=lambda kv: -abs(kv[1]))
    wp = os.path.join(out_dir, "aigc-zh-weights.tsv")
    with io.open(wp, "w", encoding="utf-8", newline="\n") as f:
        f.write("# Wordlite 离线 AIGC 打分权重：逻辑回归系数，键是字符 n-gram（已按 TextCorpus.normalize 折叠并去掉空白）\n")
        f.write("# 列：w<TAB>ngram<TAB>weight　　分数 = sigmoid(截距 + Σ (1+ln 次数)*权重 / L2 长度)\n")
        f.write("# 来源与许可见 aigc-model.tsv；calibrated=%s，%s\n" % (
            "true" if gate["gates"]["calibrated"] else "false",
            "界面可以印百分比" if gate["gates"]["calibrated"] else "有一条或两条没过，界面不许印百分比"))
        for gram, weight in pairs:
            f.write("w\t%s\t%s\n" % (gram, fmt(weight)))
    fp_path = os.path.join(out_dir, "aigc-zh-freq.tsv")
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
    mp = os.path.join(out_dir, "aigc-model.tsv")
    metrics = gate
    mrows = [
        ("model_version", version),
        ("created", created),
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
    if os.path.exists(os.path.join(out_dir, "aigc-model.json")):
        os.remove(os.path.join(out_dir, "aigc-model.json"))

    cfg_m = cache["cfg"]
    manifest = dict(   # 随包清单是上面那份 aigc-model.tsv；这份 json 只给暂存目录复核用
        model_version=version,
        created=created,
        config=cache.get("name", "?"),
        config_options={k: (list(v) if isinstance(v, tuple) else v)
                        for k, v in cfg_m.items() if k != "name"},
        what="字符 2/3/4-gram + 词表 + 逻辑回归（CPU 训练，手机侧纯手写打分，无 ONNX、无新依赖）"
             "；另附字级 1/2/3-gram 频率表做滑窗困惑度代理",
        operating_point=dict(
            bias=round(float(gate["bias"]), 4),
            flag_gate=FLAG_GATE,
            rule=("公共真人留出上 P>=%s 的比例 <= %s/千句（bias_at_gate=True：与出厂门槛同一把尺子）"
                  % (FLAG_GATE, cfg_m.get("bias_fp_target", GATE_FP_PER_MILLE))
                  if cfg_m.get("bias_at_gate") else
                  "公共真人留出上 P>=0.5 的比例 <= %s/千句" % cfg_m.get("bias_fp_target", GATE_FP_PER_MILLE)),
            bias_pool=cfg_m.get("bias_pool", "all（全体公共真人留出句）"),
            disclosure=cfg_m.get("bias_note", "偏置只看了公共侧，没看真稿留出"),
            note="偏置是分数上的单调平移：改它 AUC 一个字不动，只动误报与过线两栏"),
        trained_on=[dict(code=s["code"], license=s["license"], redistributable_raw=s["ship_raw"],
                         note=s["note"], url=s["url"].split("/resolve/")[0])
                    for s in CORPORA if s["kind"] == "zh" and uses(s["code"])],
        trained_domains=(sorted(set(str(d) for d in cache["domains"]))
                         if cache.get("domains") is not None else []),
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
    if os.path.abspath(out_dir) != os.path.abspath(ASSETS):
        # 随包的只有那三个 tsv（Java 只认 tsv）；这份 json 是暂存目录里给人复核用的清单副本
        json.dump(manifest, io.open(os.path.join(out_dir, "aigc-manifest.json"), "w", encoding="utf-8"),
                  ensure_ascii=False, indent=1)
    for pth in (wp, fp_path, mp):
        print("%-34s %7.1f KB" % (os.path.basename(pth), os.path.getsize(pth) / 1024.0))
    if os.path.abspath(out_dir) != os.path.abspath(ASSETS):
        print("%-34s %7.1f KB" % ("aigc-manifest.json（不进包）",
                                  os.path.getsize(os.path.join(out_dir, "aigc-manifest.json")) / 1024.0))
    print("导出权重 %d 条，频率表 %d 条，黄金样本 %d 条" % (len(pairs), len(freq["lu"]) + len(freq["lb"]) + len(freq["lt"]), len(golden)))
    return manifest


def stage_guard(args):
    """自造语料（../aigc-corpus/selfbuild/）逐字守卫：逐句比对 tests/corpus 那批真稿（含模板段），
    有多少句/多少行命中，一目了然。训练侧读这些文件时调的是同一个 real_holdout_guard、同一份口径，
    命中一句整行不进训练，这里只是把同一把尺子的读数印出来。
    --pool 再多量一栏"与全体中文公共池逐字重复"（要把公共池全量过一遍切句，慢，约两分钟）。"""
    norm = Normalizer(load_trad_table())
    guard = real_holdout_guard(norm, args.repo)
    print("真稿独立留出逐字句（tests/corpus，含模板段）：%d 条" % len(guard))
    d = args.dir or SELFBUILD_DIR or os.path.join(args.data, "selfbuild")
    pool = None
    if args.pool:
        rows, _ = rows_of(args.data, norm)
        pool = set(r[4] for r in rows if r[1] not in SELFBUILD_LABEL)
        print("全体中文公共池逐字句（rows_of 全量，已排除自造档自身）：%d 条" % len(pool))
    for fn in sorted(f for f in os.listdir(d) if f.endswith(".jsonl")):
        spec = next((s for s in CORPORA if str(s["file"]).endswith(fn)), None)
        n = ns = hit = rhit = dup = 0
        lens = []
        for line in io.open(os.path.join(d, fn), encoding="utf-8"):
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            n += 1
            cs = [c for c in (norm.compact(s) for s in split_sentences(row.get("text") or "")) if scoreable(c)]
            ns += len(cs)
            lens += [len(c) for c in cs]
            h = sum(1 for c in cs if c in guard)
            hit += h
            rhit += 1 if h else 0
            if pool is not None:
                dup += sum(1 for c in cs if c in pool)
        lens.sort()
        extra = ("  与公共池逐字重复 %d（%.1f%%）" % (dup, 100.0 * dup / max(1, ns))) if pool is not None else ""
        print("%-30s 行 %-5d 计分句 %-6d 真稿逐字命中 %-3d（含命中的行 %-3d）句长中位 %-4s %s%s" %
              (fn, n, ns, hit, rhit, lens[len(lens) // 2] if lens else "-",
               "code=" + spec["code"] if spec else "未接进 CORPORA：本轮不进训练", extra))
    print("判定：真稿逐字命中必须为 0；不为 0 的那一档不许进训练。")


def cache_bias(cache):
    return json.load(io.open(os.path.join(ART, "gate.json"), encoding="utf-8"))["bias"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("stage", choices=["fetch", "train", "compare", "external", "gate", "export", "guard", "all"])
    ap.add_argument("--dir", default="", help="guard：自造语料目录，默认 <data>/selfbuild")
    ap.add_argument("--pool", action="store_true", help="guard：顺带量自造档与全体中文公共池的逐字重复（慢，约两分钟）")
    ap.add_argument("--file", default="", help="外部对照语料的本地路径（external 那一步用）")
    ap.add_argument("--data", default=DEFAULT_DATA)
    ap.add_argument("--repo", default=REPO)
    ap.add_argument("--proxy", default=os.environ.get("WORDLITE_AIGC_PROXY", "http://127.0.0.1:7897"))
    ap.add_argument("--redo", action="store_true")
    ap.add_argument("--configs", default="", help="只跑这些配置，逗号分隔；空=全跑")
    ap.add_argument("--no-lodo", action="store_true", help="跳过跨域留出（只用于快速调试）")
    ap.add_argument("--pick", default="", help="train：强行指定写进 model-cache.pkl 的配置（默认按公共侧规则挑）")
    ap.add_argument("--window", action="store_true", help="compare：连滑窗两栏一起量（每个配置多花约一分钟）")
    ap.add_argument("--out", default="", help="export：导出目录；默认 app/src/main/assets/aigc，指别处就不碰 assets")
    ap.add_argument("--model-version", default="", help="export：清单里的 model_version，默认沿用出厂那个")
    ap.add_argument("--created", default="", help="export：清单里的 created，默认 %s" % CREATED)
    ap.add_argument("--selfbuild", default="", help="自建档目录（钉版本用）：空=<data>/selfbuild，指到副本就读那份")
    a = ap.parse_args()
    global SELFBUILD_DIR
    SELFBUILD_DIR = os.path.abspath(a.selfbuild) if a.selfbuild else ""
    print("自建档目录：%s" % (SELFBUILD_DIR or "<data>/selfbuild"))
    if a.stage in ("fetch", "all"):
        stage_fetch(a)
    if a.stage in ("train", "all"):
        stage_train(a)
    if a.stage == "compare":
        stage_compare(a)
    if a.stage == "external":
        stage_external(a)
    if a.stage == "guard":
        stage_guard(a)
    if a.stage in ("gate", "all"):
        stage_gate(a)
    if a.stage in ("export", "all"):
        stage_export(a)


if __name__ == "__main__":
    main()
