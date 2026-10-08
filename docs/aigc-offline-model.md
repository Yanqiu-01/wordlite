# 离线 AIGC 模型（字符 n-gram + 逻辑回归）：三档留出、新语料、为什么界面上还是不印百分比

日期 2026-10-09（第二轮补料：按第 8 节那张单子找到并接进三路现代模型语料，重训三个配置，真稿那一档还是没过）。同日第一轮：换/补同域语料重训；更早一版：从 0 到 1 训出模型。
训练台 `tools/build-aigc-model.py`，权重与字频表在 `app/src/main/assets/aigc/`，打分器
`AigcNgramModel.java`，测试 `tests/AigcOfflineModelRegression.java`（48 条断言，已进 `tools/test-host.ps1`）。

## 0. 一句话

第二轮补料（本轮）：按第 8 节那张单子又找到并接进**三路机器侧是 2024-2025 模型的中文语料**
（MAGA-Bench 中文基线档 + 风格改写档、DetectRL-X 中文四台现代模型），其中学术域两档
——中文核心期刊论文摘要（真人原文 vs 现代模型整篇写）与中文期刊正文（真人原文 vs 现代模型改写）。
新语料在自己那一档上好分得很：**同域 AUC 0.9488 / 0.9333**，可**真稿独立留出那一档 0.4519 ~ 0.5006**，
出厂门槛（AUC ≥ 0.85 且真人误报 ≤ 2 句/千句）一条都没过，界面继续一个 AIGC 百分比都不印。
候选清单、许可、逐配置三档数与第二把外部的尺子（RealDet 928,978 句 → 0.6639）都在第 8b 节。

任务书点名的两路同域语料：SemEval-2024 Task 8 中文子任务**取回来了并且进了训练**；
TUPE **按给的 arXiv 号取不到**（第 3 节逐条附 HTTP 状态；今天顺着这批论文里出现的另一个名字
"TUPA / Text Under Pinch"又挖了一遍，还是取不到）。另外取回两套东西：MIT 许可的现代大模型
中文稿集（`AnxForever/chinese-ai-detection-dataset`，进训练），以及一份第三方中文 test
（COLING-2025 MGT-Detection Task 1，许可核对不了，**一个字不进训练**，只当外部的尺子量一次）。

13 个配置全量重训（`py tools/build-aigc-model.py train`，约 26 分钟，选出的仍是 **B-prhpp-only**，
规则那一栏 0.7756）、每域句数上限统一、与公共留出逐字相同的训练句剔干净之后，五把尺子的数：

| 量在哪一批题上 | AUC(机器>真人) | 真人误报 | 量它的命令 |
| --- | --- | --- | --- |
| ① 公共留出（公开语料分组十等分） | 0.8091 | 4.39 句/千句 | `gate` |
| ② 学术配对留出（人写原稿 vs 同一篇 ChatGPT 润色） | 0.8091（选择规则看的是 `min(prhpp, prhpp_te)` = 0.7756） | 4.39 句/千句 | `gate` / `train` |
| ③ 真稿独立留出（995 计分句，出厂门槛只认这一档） | **0.5556** | **3.21 句/千句** | `gate` |
| ③b ③ 里面域与主题都按住的那一对（H1 论文正文 vs M-DOMAIN 同领域同主题机器稿，382+110 句） | **0.5791** | — | `gate` |
| ④ 第三方中文 test（63,009 篇 → 1,222,872 计分句，与这份模型训练集逐字重合 **0** 句） | **0.6240** | 36.68 句/千句 | `external` |

出厂门槛是 ③ 的 AUC ≥ 0.85 **且** 真人误报 ≤ 2 句/千句：两条都不过，清单里 `calibrated=false`，
界面一个 AIGC 百分比都不印。**这条没做到，原因是数据不是算法**：公开中文语料教的是"哪种腔调像机器"，
产品要判的是学位论文正文里整段机器写的中文（第 6 节六条能复算的证据）。④ 那个数特别说明问题——
换一把完全不靠我们这份留出集构造的尺子，也只有 0.6240，离 0.85 差 0.2260。

## 1. 三档留出：分开量，分开报

同一套权重、同一个偏置，只差在考哪批题：

| 档 | 是哪批题 | 出厂门槛认不认它 |
| --- | --- | --- |
| ① 公共留出 | 公开语料按来源分组十等分取第 0 份（`fold_of(group_id)==0`） | 不认 |
| ② 学术留出 | 公共留出里 `academic-abstract` 那一档（人写原稿 vs 同一篇的机器润色） | 不认 |
| ③ 真稿独立留出 | 仓库里那批人工标注真稿：H1 学位论文正文 + H2 已发表摘要逐字摘录 + H3 知网片段 + X1 轻度润色 + HH 硬真人句；机器 M-RAW / M-EVADE / M-DOMAIN | **只认这一档** |

第 ④ 档（第三方 test）不在这三档里，也不进门槛：它是别人的比赛考题，许可核对不了，
我们只读它、只拿它复核一次结论（第 6b 节），一个字不进训练，`gate` 与 `train` 都不读它。

`AigcNgramModel.calibrated()` 只看第 ③ 档的两个实测数（`holdout_auc` 与
`holdout_human_fp_per_mille`），清单里的旗子单独改不成；`tools/build-aigc-model.py gate`
一次把三档都算出来，写进 `artifacts/agent-aigc-offline/gate.json` 与随包清单。

上一版（第一轮）的错就是把 ① 的 0.9663 当成绩写在第一行——那是那份旧模型自己的公共留出；
同样口径换成这一版出厂这份是 0.8091（第 4 节第 3 条：剔掉与公共留出逐字相同的训练句之后掉的那些）。
这一版起 ① ② ③ 永远并排列，谁也不许代替谁。

## 2. 语料（两轮分别新增两路 / 三路，许可与可分性）

只允许放派生统计量：仓库里进的是权重表、字频表与这份清单，**一个字原文都不进**；
原文留在仓库外的同级目录 `../aigc-corpus/`。

| 代号 | 数据集 / 文件 | 许可 | 域（本轮起给每个来源打域标签） | 计分句 真人 / 机器 |
| --- | --- | --- | --- | --- |
| hc3zh | `Hello-SimpleAI/HC3-Chinese/all.jsonl` | CC-BY-SA-4.0 | hc3-baike / hc3-law / hc3-medicine / hc3-finance / hc3-psychology / hc3-open_qa / hc3-nlpcc_dbqa | 88,616 / 90,782 |
| prhpp / prhpp_te / prhppgen | `FreedomIntelligence/ChatGPT-Detection-PR-HPPT` | Apache-2.0 | academic-abstract（学术摘要：人写原稿 vs 同一篇 ChatGPT 润色 / 整篇生成） | 5,610 / 8,287（prhpp 3,802 / 4,966，prhpp_te 1,808 / 2,293，prhppgen 仅机器 1,028） |
| pangda | `pangda/chatgpt-paraphrases-zh`（前 30,000 行） | MIT | web-query（检索短句） | 23,198 / 59,296 |
| **setask8zh（新）** | `anyangsong/SemEval2024-Task8-SubtaskA` 的 `chinese/subtaskA_train_chinese.jsonl`（22.1 MB，HTTP 200） | 镜像卡声明 MIT（上游许可核对不了，见第 3 节） | web-ugc（网络社区口语：11,934 篇 = 真人 6,000 / chatGPT 2,970 / davinci 2,964） | 67,459 / 36,389 |
| **anxzh（新）** | `AnxForever/chinese-ai-detection-dataset` 的 `train.csv`（87.3 MB，HTTP 200） | MIT | news（THUCNews 真人新闻 + 同一批的 AI 润色稿 C4）/ essay（`parallel_*`、`auto_*`、C3 改写）/ misc-human | 170,228 / 480,251（news 159,151 / 37,857，essay 仅机器 442,394，misc-human 11,077 / —） |
| ateeqq | `Ateeqq/AI-and-Human-Generated-Text` | MIT | 英文，本轮中文模型不用 | — |
| **magazh（本轮新）** | `anyangsong/MAGA-cn` 的 `train/MGB-cn_train.jsonl`（348 MB，HTTP 200） | MIT（数据集卡声明；内嵌的人类源文本上游另有约束，见第 3 节） | 10 个中文域：`academic-maga`（CSL＝中文核心期刊论文摘要）+ `maga-feature`（CLTS 新闻特稿）/ `maga-baike` / `maga-zhihu` / `maga-tieba` / `maga-zhidao` / `maga-douban` / `maga-dianping` / `maga-rednote` / `maga-weibo` | 792,692 / 500,549（academic-maga 那一档 20,610 / 48,589） |
| **magaaug（本轮新）** | 同一仓库的 `train/MAGA-cn_train.jsonl`（400 MB，HTTP 200） | MIT（同上） | 同一套 10 个域；差别是生成时挂一个与任务无关的人格/文风 system prompt＝公开中文语料里唯一一档现代模型强风格改写 | 792,692 / 689,645 |
| **drlxzh（本轮新）** | `WUJUNCHAO/DetectRL-X` 的 `Binary/binary_general_open.json`（878 MB，HTTP 200；取回后只留 `lang=chinese` 的 15,600 行另存 jsonl） | MIT | 六个域：`drlx-academic`（中文期刊/鉴定文书正文）+ `drlx-news` / `drlx-novel` / `drlx-seo` / `drlx-webtext` / `drlx-wiki` | 366,646 / 343,030（drlx-academic 那一档 70,180 / 74,835） |

这三路只进了本轮新加的 N / O / P 三个配置，**出厂那一份 B-prhpp-only 一个字都没用到它们**
（选择规则这一轮仍选 B，见第 8b 节；本轮起随包清单的 `trained_on` 也只写这份配置真正读到的来源）。
机器侧模型清单：magazh / magaaug 是 DeepSeek-V3、DeepSeek-R1-0528-Qwen3-8B、Qwen3-plus、Qwen3-8B、
Hunyuan-7B-Instruct、Hunyuan-TurboS、GPT-4o-mini、Gemini-2.0-flash、Llama-3.1-8B-Instruct、gemma-3-12b-it、
Ministral-8B-Instruct-2410、Mistral-Medium；drlxzh 是 DeepSeek-V3、Gemini-2.5-Flash、GPT-4o、Qwen-Max。
**没有一台是 gpt-3.5 / davinci**——这正是第 6b 节末尾那句"语料的机器侧停在两年前，能力就停在两年前"要补的东西。

全量合计 **1,030,116 个计分句**（真人 355,111 / 机器 675,005），去掉逐字重复后 966,708 句。

`anxzh` 是这轮唯一带来**现代生成模型**的一路：机器侧含 `parallel_gpt-4.1-mini`、
`parallel_cursor2-gpt-5`、`parallel_gemini-2.5-flash`、`parallel_claude-haiku-4-5`、
`parallel_Kimi-K2`、`parallel_deepseek-v3.2`。它同时也带来一个跨语料重复的坑：里面
`hc3_human` / `hc3_chatgpt` 两档共 **29,693 条**是 HC3 的副本，训练台一律丢掉（走上游那份），
另外 `C2`（人写开头 + 机器续写，带 `[SEP]`）**1,614 条**也丢——逐句归属分不清，宁可少要。

`setask8zh` 的"域"要说清楚：它虽然挂着 SemEval-2024 Task 8"多领域含学术摘要"的名，
**中文子任务里没有学术域**。11,934 行只有 `source=chinese` 一个值、`domain` 字段全空，
抽出来是人肉搜索/贴吧式的口语问答（例：真人侧"我也是传3玩家，你的问题我经常遇到……"）。
这一条本身就是本轮的一个结论：任务书里"SemEval-2024 Task 8 中文含学术摘要"这个前提不成立。

也下载了但不进训练的：`anyangsong/COLING2025-MGT-Detection-Task1` 中文（MIT，39.6 MB，
HTTP 200）——`chinese_train.jsonl` 前 4,000 行的 `source` 只有 `hc3`(3,073) 与 `m4gt`(927)，
就是 HC3 + SemEval Task 8 的重新打包，进训练等于同一条句子数两遍。

## 3. 取不到的，按 HTTP 状态原样记

| 目标 | 结果 |
| --- | --- |
| **TUPE（任务书指定）** | 对不上号。`https://arxiv.org/abs/2305.09992` → HTTP 200，但正文是《A Fusion Model: Towards a Virtual, Physical and Cognitive Integration and its Principles》（VR/AR 融合模型），与 AI 文本检测无关；arXiv API `ti:"TUPE"` → HTTP 200、0 条相关（只命中一篇非线性扩散方程论文）；`all:"Text Under Pinch"` → HTTP 200、0 条；`abs:"TUPE" AND abs:"generated text"` → HTTP 200、0 条；OpenAlex `title.search:TUPE` → HTTP 200、全是英国劳动法（TUPE transfers）；HuggingFace `datasets?search=TUPE` → HTTP 200、只有 4 个无关仓库（`tuperte69/sdft-test-03` 之类，液晶面板与花瓶的图像集）；Semantic Scholar 检索 → **HTTP 429** |
| SemEval-2024 Task 8 上游仓库 `mbzuai-nlp/SemEval2024-task8` | 许可核对不了：`api.github.com/repos/...` → **HTTP 451**，`github.com/...` → **HTTP 451**，`raw.githubusercontent.com/...`（main/HEAD/master 三种写法）与 `codeload.github.com/...` → **HTTP 404**。同一个 `raw.githubusercontent.com` 对 `octocat/Hello-World/README` 返回 **HTTP 200**，所以不是网络不通，是那个仓库路径取不到。中文侧只能用 HF 镜像（卡上声明 MIT；另一镜像 `d0rj/SemEval2024-task8` 声明 apache-2.0） |
| `QiYuan-tech/LLM-Detector`（中文，7 个国产模型 + GPT-4 与真人答案配对，最贴产品域） | 要登录接受条款：匿名 **HTTP 401**（上一轮同样 401） |
| `krisfu/Chinese-Corpus-DetectGPT`、`liud169/ChatGPT-detector` | 匿名 **HTTP 401** |
| 顺着名字再挖一遍（怀疑任务书把数据集名写错了：这批 SemEval-2024 Task 8 的分析论文里出现的名字是 **TUPA / "Text Under Pinch"**） | 还是取不到。`export.arxiv.org/api/query?...ti:"TUPA"` → HTTP 200，5 条命中全是航空/离子阱/宇宙线，没有一条是 NLP 数据集；`all:"Text Under Pinch"` → HTTP 200、`totalResults=0`；`all:"TUPA" AND all:"machine-generated text"` → HTTP 200、0 条；`arxiv.org/search/?query=TUPA+adversarial+machine-generated` → HTTP 200、页面写 "produced no results"（同一个页面查 `SemEval-2024 Task 8 multilingual dataset creation` 也说 produced no results，而那篇是 arXiv 2402.11169，所以这个检索页本身不可信，以上面的 API 为准）；`huggingface.co/api/datasets?author=TNO-UnitNLP` → HTTP 200、`[]`；`api/datasets/TNO-UnitNLP/TUPA` → **HTTP 401**；HuggingFace 全文检索 `TUPA text under pinch` → HTTP 200、唯一命中是《切韵拼音（TUPA）》音韵数据集；`html.duckduckgo.com/html/?q="Text+Under+Pinch"+adversarial+dataset+LLM-generated+detection+huggingface` → HTTP 200、页面写 "No results found"；Semantic Scholar → **HTTP 429** |
| 顺手查的两个候选：`SNEAKO`、`MGT-detection` | HuggingFace `api/datasets?search=SNEAKO` → HTTP 200、`[]`；`api/datasets?search=MGT-detection` → HTTP 200、只命中下一行那个镜像 |
| **COLING-2025 MGT-Detection Task 1 中文**（镜像 `anyangsong/COLING2025-MGT-Detection-Task1`，官方 test 带标签 178,161,905 字节，HTTP 200 已取回） | 不进训练，两条理由：① 镜像卡自称 Unofficial Mirror，上游 `github.com/mbzuai-nlp/COLING-2025-Workshop-on-MGT-Detection-Task1` 从本机一律 **HTTP 451 / 404**，许可核对不了；② `chinese_train.jsonl`（39,577,183 字节）与 `chinese_dev.jsonl`（16,614,725 字节，14,772 行）的 `source` 字段只有 `hc3`（11,324）与 `m4gt`（3,448），就是 HC3 + SemEval-2024 Task 8 的重新打包，进训练等于同一条句子数两遍。**但官方 test 那份是另一批料**（`source` = MNBVC-Gov-Report / CUDRT / Zhihu-qa / high-school-student-essay / 325_gaokao_titles / human_student_essays，机器侧 model = GPT-4o / GPT-4o-mini / claude-3-5-sonnet / glm-4-9b-chat / Baichuan2-13B-Chat / ChatGLM3-6B），所以只拿来当外部对照量一次（第 6b 节），一个字不进训练 |

本轮补料时另外查过的（都写进随包清单的 `not_used`）：

| 目标 | 结果 |
| --- | --- |
| **任务书点名的 ModelScope `FlagEvaldet` 系列中文 AIGC 检测数据集** | **查不到这个库**。ModelScope 的检索接口从本机可用，但对这个名字一律空：`GET /api/v1/datasets?Owner=FlagEvaldet` → HTTP 200 且 `Data=[]`（同一个 `Owner` 参数对 `simpleai` 返回 2 条 HC3，所以参数与网络都没问题）；`Owner=FlagEval` 同样 0 条；`Query=FlagEvaldet` / `AIGC检测` / `中文AIGC` / `AI文本检测` 全部 0 条（`Query=HC3` 能返回 `simpleai/HC3-Chinese`，检索本身是好的）；`GET /api/v1/datasets/FlagEvaldet/<任意名>` → HTTP 404「不存在的数据集」；`/api/v1/organizations/FlagEvaldet` → HTTP 404；HuggingFace 侧 `api/datasets?author=FlagEvaldet` → 0 条、`author=FlagEval` 只有 CLCC_v1 / HalluDial / ERQA 这些视觉与认知库。GitHub 检索 `FlagEvaldet` → `total_count=0` |
| ModelScope 上唯一对得上的两个同名库 | `hyx111111/CCKS2025`（卡片写「CCKS2025-大模型生成文本检测」，声明 Apache-2.0）与 `ssssyyyyadc/ccks2025`（Apache-2.0）：仓库树里只有 `.gitattributes` 与 `README.md` 两个文件，**一个数据文件都没有**，弃用 |
| `koakuma/RealDet`（ACL 2025，中英双语，15 个域 22 台模型） | **许可不过：数据集卡 `cc-by-nc-4.0`，仅限非商业使用**，而这份模型随 APK 出厂，不做训练语料。内容也接不上产品域：中文侧每行只有 `text`/`label` 两个字段、没有域标签，抽出来是网络问答（游戏加点、汽车维修这类）；真人侧只有 10,545 行，机器侧 125,295 行（Claude-3 / DeepSeek / GPT-4o / 文心一言 / 通义千问 / 360GPT / 星火 / Baichuan / ChatGLM-2 / MOSS / BLOOMz）；README 里那个 Academic Writing 域是 Arxiv Abstracts，中文侧没有对应的学术正文。**只当第二把外部的尺子量了一次**（第 8b 节末尾），一个字不进训练 |
| `QiYuan-tech/LLM-Detector`（仍是这几路里最贴产品域的） | 匿名仍是 **HTTP 401**（要登录接受条款）。取回步骤写死在随包清单的 `not_used` 里：登录该库页面点 Accept and access → 在 settings/tokens 建 read 权限 token → 带 `Authorization: Bearer $HF_TOKEN` 走 7897 代理 `resolve/main/train_set.json` 下到 `../aigc-corpus/`（或 `hf download QiYuan-tech/LLM-Detector --repo-type dataset`）→ 按本文件规矩只进派生统计量。这一步需要人工授权，本轮没有账号，没取 |

网络口径：任务书说走 clash 的 7890。这台机器上 `http://127.0.0.1:7890` 是
**连接被拒（由于目标计算机积极拒绝，无法连接）**，`http://127.0.0.1:7897` 返回
`https://huggingface.co/api/datasets?limit=1` → **HTTP 200**。训练台默认走 7897，
`--proxy ""` 可改直连（直连不通：本机 DNS 把 `huggingface.co` 解析到别的地址）。

## 4. 训练台本轮改了十件事（`tools/build-aigc-model.py`）

1. **域标签**：每个来源打一个域（`hc3-baike` / `hc3-law` / … / `academic-abstract` /
   `web-query` / `web-ugc` / `news` / `essay` / `misc-human`），共 13 个域。域是用来量域偏移的，不是用来挑数据的。
2. **每域上限 60,000 计分句**（`CAP_PER_DOMAIN`）：按 `group_id` 哈希整组砍，不挑内容。
   不然 `essay` 一个域 442,394 句会把 `academic-abstract` 的 5,610 句淹掉，句数就成了配置之间的差异。
3. **逐字重复剔除**：把与公共留出逐字相同的训练句从拟合侧剔掉。跨语料的逐字重复实测：
   `hc3zh→anxzh` 4,079 句、`prhpp→prhpp_te` 2,586 句、`hc3zh→setask8zh` 622 句、
   `setask8zh→anxzh` 402 句、`prhpp→anxzh` 17 句；全量 1,030,116 句里有 63,408 句是重复行。
   上一版没剔这一层，B 配置的公共留出 AUC 因此虚高 0.026（0.8352 → 剔完 0.8091）。
4. **跨域留出（LODO）**：轮流把整个域从拟合侧撤掉，只在被撤掉那个域的公共留出上量 AUC。
   这是把"域"这件事量出来的那一把尺，也是本轮新加的诊断列。**它是诊断，不是选择规则**（见第 5 节末）。
5. **域内类别配平**（`balance="domain"`）与**跨域同号剪枝**（`prune="domain-sign"`）：
   前者让每个 (域, 类别) 组权重相同；后者要求一个特征在 ≥ 阈值比例（默认 0.75，L 配置 0.9）的域里
   "机器均值 − 真人均值"的符号一致才许进导出表——符号在不同域里翻来翻去的特征，量的是语域，不是"机器写的"。
6. **三档留出一次算完**（`gate`）：公共 / 学术 / 真稿的 AUC、真人误报、机器过线全写进
   `gate.json` 与随包清单，`AigcNgramModel` 直接读，报告里的话由这些数拼出来。
7. **`train` 收尾那个会崩的序列化**：`share_kept` 是"每条导出特征一个数"的数组（两万个数），
   原来直接 `json.dump` 会 `TypeError: Object of type ndarray is not JSON serializable`——
   13 个配置全拟合完，最后写 `train-summary.json` 时崩。改成写均值 / 中位 / 最小值三个数。
   修完之后 `py tools/build-aigc-model.py train` 全量跑通（约 26 分钟），选出的仍是 **B-prhpp-only**
   （学术配对档 0.7756，公共留出 0.8091），与出厂那份一致。
8. **新增 `compare` 那一步**：一条命令把 13 个配置的三档留出摆成一张表，并算"公共侧哪一栏预测得了
   真稿"的 Pearson r，写 `compare.json`。第 5 节那张表与三个 r 都由它出，不再依赖仓库外的脚本。
   它只报数，选择规则仍在 `train` 那一步。
9. **`gate` 多写三笔**：真稿留出逐文件的分数带子与句长、真人档 × 机器档的两两 AUC、只看句长的 AUC；
   另加一个不碰标签的域差数——模型那两万条特征在公共留出与真稿上各点亮多少条。
10. **新增 `external` 那一步**：拿上一节那份第三方中文 test 对出厂模型再量一次，写
    `external-check.json`。读，不训，也不参与出厂门槛。

## 5. 13 个配置的三档成绩

计分句是加了每域上限之后的数；真人误报与机器过线都在 `SEGMENT_FLAG_GATE = 0.45` 上量；"规则那一栏"是选择规则看的 `min(prhpp, prhpp_te)`；"跨域"那一列是 LODO 的均值 / 最差。
表里每个数都不手抄：① ② ③ 由 `py tools/build-aigc-model.py compare` 现取（`compare.json`），规则那一栏与跨域由 `py tools/build-aigc-model.py train` 现取（`train-summary.json`）。**加粗的是出厂那份。**

| 配置 | 用什么料 | 计分句 | 规则那一栏 | ① 公共留出 | ② 学术配对档 | ③ 真稿 AUC | ③ 真人误报/千句 | ③ 机器过线 | 跨域均值 / 最差 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **B-prhpp-only** | 只有学术摘要配对档 | **13,897** | 0.7756 | **0.8091** | 0.8091 | **0.5556** | **3.21** | 0.27% | 无（只有一个域） |
| D-no-hc3 | 学术 + 检索短句改写 | 73,899 | 0.7693 | 0.9540 | 0.8048 | 0.5952 | 9.62 | 1.62% | 0.6109 / 0.5996 |
| K-pairs-only | 学术 + 新闻（只留同域配对）+ 域内配平 | 144,980 | 0.7347 | 0.9390 | 0.7771 | 0.4883 | 33.65 | 3.23% | 0.7108 / 0.6028 |
| J-formal-paired | 学术 + 新语料 + 配平 + 同号剪枝 | 204,994 | 0.6886 | 0.8382 | 0.7497 | 0.4194 | 24.04 | 1.35% | 0.7384 / 0.7228 |
| G-new-corpus | 学术 + 短句 + 两套新语料 | 264,996 | 0.6626 | 0.9314 | 0.7234 | 0.4295 | 36.86 | 0.81% | 0.6993 / 0.6480 |
| I-new-consistent | 同上 + 同号剪枝 0.75 | 264,996 | 0.6227 | 0.8774 | 0.6990 | 0.4416 | 28.85 | 1.62% | 0.6786 / 0.6473 |
| E-formal-mix | 去掉口语问答的混合（含新语料） | 332,308 | 0.6838 | 0.9317 | 0.7439 | 0.4574 | 16.03 | 0.54% | 0.8123 / 0.6900 |
| L-consistent-strong | 全部 13 域 + 同号剪枝 0.9 | 444,394 | 0.6106 | 0.8591 | 0.6734 | 0.4346 | 6.41 | 0.81% | 0.8113 / 0.5627 |
| A-all | 全部 13 域，不剪枝 | 444,394 | 0.6938 | 0.9388 | 0.7512 | 0.4198 | 20.83 | 0.27% | 0.8682 / 0.7053 |
| C-hc3-only | 只有 HC3 问答 | 179,398 | — | 0.9769 | 无学术档 | 0.3324 | 4.81 | 0.00% | 0.9503 / 0.8514 |
| F-all-1234 | 全部 13 域 + 单字特征 | 444,394 | 0.7162 | 0.9428 | 0.7716 | 0.4411 | 8.01 | 1.62% | 0.8687 / 0.7311 |
| H-consistent | 全部 13 域 + 同号剪枝 0.75 | 444,394 | 0.6358 | 0.8698 | 0.6952 | 0.4154 | 17.63 | 0.27% | 0.8310 / 0.6662 |
| M-academic-news | 学术 + 新闻（域筛 + 配平） | 73,903 | 0.7617 | 0.8597 | 0.7953 | 0.4934 | 40.06 | 1.89% | 0.5735 / 0.5717 |

三件事一眼能看出来：

- **① 与 ③ 反着走。** ① 最高的是 C-hc3-only（0.9769），它在 ③ 上 0.3324——比抛硬币还差；
  ③ 最好的 D-no-hc3（0.5952）在 ① 上是 0.9540，恰好排第二。单个反例不算数，
  13 个配置合起来 r(①, ③) = **-0.2275**（下一小节那张表）。
- **跨域留出也救不了。** C-hc3-only 在 7 个 HC3 子域之间互考拿到 0.9503 均值、0.8514 最差，
  两个数都已经"过"了 0.85 这道线，而它在真稿上是 0.3324。公共侧的任何留出手法——
  分组切、逐字去重、撤掉整个域再考——量的都是"这批公开域之间的距离"，不是"能不能判学位论文"。
- **加语料的方向是错的。** 从 B（13,897 句，只有学术）到 A（444,394 句，13 个域），
  ① 从 0.8091 涨到 0.9388，③ 从 0.5556 掉到 0.4198，真人误报从 3.21 涨到 20.83 句/千句。

### 公共侧哪一栏预测得了真稿（`compare` 那一步就是量这个的）

| 拿公共侧的哪一栏去猜 ③ 真稿 AUC | Pearson r | 几个配置 |
| --- | --- | --- |
| ① 公共留出 AUC | **-0.2275** | 13 |
| ② 学术配对档（按域那个 AUC） | +0.7454 | 12 |
| 选择规则那一栏 `min(prhpp, prhpp_te)` | **+0.7794** | 12 |
| 跨域留出（LODO）均值 | **-0.7604** | 12 |

前三行是 `compare` 自己打印并写进 `compare.json` 的；最后一行是两份产物现拼——
`train-summary.json` 的 `lodo_summary.mean` 对上 `compare.json` 的 ③。

- **① 那栏不仅没用，方向还是反的。** 13 个配置里 ① 刷到 0.90 以上的有 7 个，
  它们在 ③ 上落在 0.3324 ~ 0.5952，一个都没过线。拿 ① 当产品能力，等于拿反着的尺子量。
- **跨域留出更反常（-0.7604）。** 在公开那 13 个域之间迁移得越好，到真稿上越差。
  C-hc3-only 在 7 个 HC3 子域之间互考拿到均值 0.9503 / 最差 0.8514，两个数都"过"了 0.85 这道线，
  它在 ③ 上是 0.3324。所以跨域留出只当诊断，绝不能当选择规则。
- **只有学术配对档这一栏是真信号。** 选择规则停在 `min(prhpp, prhpp_te)` 身上是有账的（+0.7794），
  不是习惯——话虽如此，这一轮它选出来的 B 在 ③ 上（0.5556）反而不如规则第二名 D-no-hc3（0.5952），
  第 8 节把这件事原样记下来了。
- n 是配置条数（12~13），不是句子条数。这个 r 只用来排"该看哪一栏"，不许当显著性检验用。

### 选择规则这件事说清楚

出厂那份是 **B-prhpp-only**，用的是上一轮就写死的那条规则：**只看公共侧学术配对档**
（`academic_proxy` = `prhpp` 与 `prhpp_te` 两档里最差的那个 AUC）——产品要判的是论文正文，
公共侧最贴这个域的就是这份。本轮试过把跨域留出均值当选择规则，它会选中 A-all
（跨域 0.8682），而 A-all 在真稿上是 0.4198，比出厂这份差 0.136。**这个"哪个规则更好"的判断
是我看过真稿成绩之后才做的，不假装它是事前规则**，所以两栏数字都摆在上面那张表里，
规则本身留在代码注释与 `train-summary.json` 里可查。

## 6. 为什么加了同域语料反而更差：六条能复算的证据

**证据一：域内成绩与产品域无关。** `E-formal-mix` 在公共留出上按域拆开：

| 域 | 公共留出 AUC | 撤掉这个域再考（LODO） |
| --- | --- | --- |
| hc3-medicine | 0.9798 | 0.9441 |
| hc3-finance | 0.9670 | 0.8524 |
| web-query | 0.9538 | 0.7240 |
| hc3-law | 0.9507 | 0.9211 |
| web-ugc（新，SemEval 中文） | 0.8602 | 0.7668 |
| news（新，THUCNews + AI 润色） | 0.8260 | 0.7850 |
| **academic-abstract** | **0.7439** | **0.6900** |

同一个模型，最好的域与最差的域差 0.236。越是接近产品域的档，分越低。

**证据二：机器侧越像"论文里的机器稿"，分越低。** 出厂那份 B 在真稿八档上的句分均值
（995 计分句：真人 624 / 机器 371）：

| 档 | 侧 | 句数 | 均值 | 最高 |
| --- | --- | --- | --- | --- |
| H1 学位论文正文 | 真人 | 382 | 0.0592 | 0.4411 |
| H2 已发表摘要逐字摘录 | 真人 | 129 | **0.0961** | 0.5002 |
| H3 知网片段 | 真人 | 39 | 0.0903 | 0.4928 |
| X1 真人原文 + 轻度润色 | 真人 | 67 | 0.0779 | 0.2613 |
| HH 最难真人句 | 真人 | 7 | 0.0990 | 0.4286 |
| M-RAW 机器直出 | 机器 | 175 | 0.0845 | 0.4620 |
| M-EVADE 机器 + 规避风格 | 机器 | 86 | 0.0759 | 0.3712 |
| **M-DOMAIN 与 H1 同领域同主题** | 机器 | 110 | **0.0600** | 0.3085 |

产品真正要抓的那一档（M-DOMAIN，跟 H1 同一学科同一主题的机器整段）在八个档里**垫底**。
它比真人 H1 只高 0.0008，比 H2 低 0.036。模型学到的是"学术书面腔 = 真人写的"，
因为公开学术档（prhpp）的真人侧本来就是学术书面腔。

**证据三：没有可用工作点。** 出厂这份在真稿上扫门槛：

| 门槛 | 真人误报 / 千句 | 机器侧过线 |
| --- | --- | --- |
| 0.45（应用现用） | 3.21 | 0.27%（1/371 句） |
| 0.50 | 1.60 | **0.00%**（0/371 句） |
| 0.55 及以上 | 0.00 | 0.00% |

要把真人误报压进 2 句/千句，门槛得抬到 0.50 以上，那时**一句机器稿都抓不到**。
AUC 0.5556 说的就是这件事：分数与真值几乎无关。

滑窗困惑度那条路同样没有工作点：阈值取公共真人窗口 p0.1 = 2.099 bits/字时模板段落点 0.0%、
真人段落点 0.0%；一路抬到真人零误报的最高点 3.386 bits/字，模板段落点仍然是 0.0%（门槛要 90%）。

**证据四：把域和主题都按住，那一对也只有 0.5791。** 真稿八档两两配对量 AUC
（`gate` 写进 `gate.json` 的 `holdout.pair_auc`，整池分数会被大户摊平，两两才看得清）：

| 真人档 \ 机器档 | M-RAW 机器直出 | M-EVADE 规避风格 | M-DOMAIN 同领域同主题 |
| --- | --- | --- | --- |
| H1 学位论文正文 | 0.6592 | 0.6282 | **0.5791** |
| H2 已发表摘要逐字摘录 | 0.4423 | 0.4068 | **0.3363（反的）** |
| H3 知网片段 | 0.5343 | 0.4928 | 0.4303 |
| X1 真人原文 + 轻度润色 | 0.5132 | 0.4722 | 0.4058 |
| HH 最难判的真人句 | 0.5853 | 0.5664 | 0.5052 |

产品真正要抓的那一对（H1 vs M-DOMAIN：同一学科、同一主题、逐段配对的机器整段）单独量只有
**0.5791**。而且行越往下/越像学术书面腔，判得越歪——H2（已发表论文摘要）对 M-DOMAIN 是
**0.3363**，模型把真人写的摘要当成更像机器的那一边。

**证据五：整批真稿都堆在模型"最像真人"的那一头，词表也点亮得更少。** 两个不碰标签的数
（`gate` 的 `holdout.per_file` 与 `domain_gap`）：

| 量什么 | 公共留出 | 真稿独立留出 |
| --- | --- | --- |
| 机器档分数中位 ÷ 真人档分数中位 | 0.129 ÷ 0.045 = **2.87 倍** | 0.056 ÷ 0.047 = **1.19 倍** |
| 每个计分句中位命中的模型特征条数 | 29 | 20 |
| 两万条导出特征被点亮的比例 | 32.10% | 25.39% |
| 八个档的分数中位所在区间 | — | 0.040 ~ 0.077（三个机器档全在里面） |

公开留出上机器档中位数是真人档的 2.87 倍，真稿上只剩 1.19 倍；同时真稿只点亮模型词表的
25.39%（公共留出 32.10%）。差距不在"分数偏低"，在这批字根本没怎么触发过它学到的东西。

**证据六：连"只看句子长短"都不如，而且这不是长度归一化的锅。** 真稿留出里真人句折行去空白之后
中位 46 ~ 73 字、机器句 33 ~ 39 字；只按句长排序、内容一个字不看，AUC 是 **0.3280**（机器偏短），
反过来记就是 **0.6720**，比出厂这份 0.5556 高 **0.1164**。这条当不了成绩——它是我们这份留出集
自己的构造偏置（机器稿整体偏短），所以做法是：清单里把 `holdout_length_only_auc=0.3280` 记下来、
回归里 Java 侧复算一遍对账（差 ≤ 0.15），而**不把句长加进特征**——加进去就是拟合留出集的构造。
也不是分数没除长度：把 `Σ(1+ln 次数)·权重` 的分母换着数（`gate` 的 `length_norm_probe`，
公共留出 / 真稿）——`l2` 出厂这份 0.8091/0.5556、不除 0.8073/0.5753、除以命中特征条数
0.8020/0.5322、除以字数 0.8104/0.5531、除以 √字数 0.8111/0.5681。最乐观的一档比出厂这份
高 0.0197，离 0.85 还差 0.2747。这条试过了，不是出路。

> 下面第 6b、7、8 三节引的数字都是 py tools/build-aigc-model.py gate 对**出厂那一份**的实测。
> 重跑 	rain 之后如果规则选到了别的配置，这些数要跟着新的 gate.json 改，别抄旧的。

## 6b. 外部那把尺子：第三方中文 test 量出来 0.6240

`py tools/build-aigc-model.py external`（读 `../aigc-corpus/coling25_zh_test.jsonl`，
178,161,905 字节，来源与许可见第 3 节；**只读不训**，`train` 与 `gate` 都不碰这个文件）。

规模与体检：63,009 篇 → 切句并卡 8~300 字之后 **1,222,872 个计分句**（真人 616,841 / 机器 606,031），
与出厂这份模型自己的训练集**逐字相同 0 句**；只看句长的 AUC 0.5048（句长中位 真人 34 / 机器 35，
没有我们那份留出集的长度偏置）；机器侧开头像指令词的（"作为…""题目…"）792 篇 / 33,062 篇 ≈ 2.4%，
真人侧同类 91 篇（63,009 篇里机器 33,062 篇、真人 29,947 篇），所以这个数不是提示词尾巴刷出来的。

| 按来源分（`source` 字段） | 计分句 | 真人 / 机器 | AUC | 真人误报 / 千句 | 机器过线 |
| --- | --- | --- | --- | --- | --- |
| MNBVC-Gov-Report（政府工作报告） | 541,850 | 203,233 / 338,617 | 0.6702 | 14.33 | 10.66% |
| CUDRT（中文阅读理解问答） | 211,081 | 186,437 / 24,644 | 0.5817 | 28.32 | 4.48% |
| MNBVC-qa-zhihu（知乎问答） | 72,467 | 16,735 / 55,732 | 0.6159 | 26.71 | 3.49% |
| chinese_essay_w_human_written_parallel_51（同一题目的人写 + 机写平行档） | 5,029 | 1,343 / 3,686 | 0.6624 | 173.49 | 35.73% |
| 合起来（全部来源一起量） | 1,222,872 | 616,841 / 606,031 | **0.6240** | **36.68** | 10.22% |

上表四档加起来是 830,427 句，其余 392,445 句来自 `source` 只有一边有题的几个档（Zhihu-qa / high-school-student-essay / 325_gaokao_titles 等，单边没有真人或机器，AUC 量不出来，所以 `external` 那一步按 `n >= 40 且两边都有` 把它们跳过了；总数那行是全量）。

机器侧按生成模型拆开（真人侧共用同一批 616,841 句；从高到低全 12 档，一个不落）：

| 生成模型 | AUC | 生成模型 | AUC |
| --- | --- | --- | --- |
| claude-3-5-sonnet | **0.7819** | GPT-4o | 0.6150 |
| glm-4-9b-chat | 0.7491 | GPT3.5 | 0.6020 |
| GPT-4o-mini | 0.6736 | Baichuan2-13B-Chat | 0.5826 |
| Qwen | 0.6617 | ChatGLM | 0.5706 |
| GPT4 | 0.6537 | ChatGLM3-6B | 0.5679 |
| gpt-4o-2024-05-13 | 0.6257 | Baichuan | **0.5592** |

三条读法：

1. **结论没有被外部尺子推翻。** 1,222,872 个计分句、零逐字重合，句级 AUC 0.6240，
   离出厂门槛的 0.85 差 0.2260；0.45 那个门槛上真人误报 36.68 句/千句，是门槛（2 句/千句）的 18 倍。
   ③ 真稿那 0.5556 不是"我们的留出集挑得太刁"——换成公开考题同样印不出百分比。
2. **它比 ③ 高 0.0684，高出来的部分就是域差。** 这批题是政府工作报告 / 问答 / 中学生作文，
   不是学位论文正文；同一套权重在两种域上一个 0.6240 一个 0.5556。
3. **换一台生成机器，分数就换一档。** 同一批真人稿当对照，最好判的 claude-3-5-sonnet 0.7819，
   最难的 Baichuan 0.5592，跨 0.2227。而这 12 台生成机器**没有一台**与我们训练集里的机器侧对得上
   （第 2 节：现代档只有 gpt-4.1-mini / gpt-5 / gemini-2.5-flash / claude-haiku-4-5 / Kimi-K2 /
   deepseek-v3.2，其余全是 2023 年那批 ChatGPT 腔——而 ChatGPT 腔在自己的留出集上能到 ① 0.8091）。
   机器侧样本停在哪一年，能力就在哪一年。

一处诚实的保留：这份 test 的 `source` 只有四档进了上表（其余来源两边不齐，n 太小或单边没真人），
`CUDRT` / `MNBVC-qa-zhihu` 的人机两边不是同一批题目配对的，所以它们那两个 AUC 里可能混着题目差。

## 7. 出厂了什么、关住了什么

出厂（随包，可核对）

- `assets/aigc/aigc-zh-weights.tsv` 351 KB / 20,000 条 `w<TAB>n-gram<TAB>权重`
- `assets/aigc/aigc-zh-freq.tsv` 547 KB / 33,216 条字频派生量
- `assets/aigc/aigc-model.tsv` 8 KB 清单：模型版本 `ngram-lr-2026-10-09b`、训练数据与许可、
  取不到的语料（含 HTTP 状态）、三档留出各自的 AUC 与误报、逐域明细、跨域留出的数或"为什么没有"、
  两条门槛的判定、6 条黄金样本；本轮加了三行诊断数，都随包（不是装饰，第 7 节末尾那两条断言要读它们）：
  `holdout_pair_topic_matched_auc = 0.5791`（同领域同主题那一对）、
  `holdout_length_only_auc = 0.3280`（只看句长）、
  `holdout_domain_gap = 模型词表被点亮的比例：公共留出 32.10% / 真稿 25.39%；每句命中特征中位数 公共 29 / 真稿 20`
- `AigcNgramModel.java`：装载（20,000 + 33,216 条实测 63~101 ms）、句分、滑窗 bits/字、
  `calibrated()` / `engaged()` 两个开关，清单里的浮点数遇到 `nan` 不炸装载，
  本轮再读两个新字段 `holdoutPairTopicMatchedAuc` / `holdoutLengthOnlyAuc`
- `tools/build-aigc-model.py`：`fetch / train / gate / export` 四步，门槛常量在文件头，
  `--configs` 可单跑某个配置
- `tests/AigcOfflineModelRegression.java`：**48 条断言**（上一版 34 → 上一轮 43 → 本轮 48），已进 `tools/test-host.ps1`。
  本轮私有目录实跑：AigcOfflineModelRegression 48、AigcRegression 158、AigcFeatureAuditRegression 24、
  CharLedgerRegression 100、DetectRegression 209，全部 exit 0

关住（改一行就能开，现在不许开）

- `AigcNgramModel.calibrated()` 要**同时**满足：清单写 `calibrated=true`、清单里的真稿 AUC ≥ 0.85、
  真人误报 ≤ 2 句/千句。今天的实测是 0.5556 / 3.21，所以开关是关的。
  回归里两条方向都验：把清单旗子单独改成 `true`（实测没变）→ 仍然判不达标；
  把清单里的实测数换成 0.9100 / 0.500 → 开关才开。
- 开关没开时 `AigcDetector` 一句都不问模型要分（`Result.modelSentences == 0`），
  `verdict()` 仍是"判据未标定…"，报告与界面继续不印 AIGC 百分比；
  这条与 `AigcScorer.calibrated()`（`v1-order-only`）是两道独立的开关。
- 本轮把测试里两条写死的判据改成了"开关必须跟实测数一致"（`assertGateAgreesWithMeasurement`）：
  将来真稿 AUC 真的过 0.85，测试会要求把门打开，而不是继续钉着"必须没过线"。
  权重条数也不再钉死 20,000，改成"与清单 `exported_features` 一致 + 在 2,000 ~ 60,000 之间"，
  因为带跨域剪枝的配置可能导出更少条。
- **反冒名那条新断言**：把清单里 ① 公共留出改成 0.9999、② 学术留出改成 0.9999、跨域均值改成 0.9800、
  跨域最差改成 0.9600，只要 ③ 真稿那一档没过线，`calibrated()` 必须仍是 false。本轮量出来
  r(①, ③) = -0.2275（第 5 节），那三栏的高分连方向都不保证，所以它们没有资格开门。
- **两个诊断数两侧对账**：Java 侧自己复算一遍——同领域同主题那一对 382+110 句量到 **0.5855**
  （清单写的 0.5791，差 0.0064）；只看句长量到 **0.3305**（清单写的 0.3280，差 0.0025）。
  留出集自身的构造偏置这笔账，Python 与 Java 必须记同一个数。
- 字数口径不双计：模型分走的还是 `Sentence.score → Segment → flaggedChars` 那一条路，
  回归里"同一批可疑区间（含重叠）交两遍，60 字不变"这条仍在。
- `AigcNgramModel.loadFromAssets` 已在 `ApiWorkflow.java:242` 接线（上一版那条"还差装载那一行"已经补上了）。

## 8. 差多少，以及要什么数据才补得上

差多少（都是本轮实测，命令见第 9 节）：

| 门槛 | 出厂这份 | 13 个配置里最好的 | 差距 |
| --- | --- | --- | --- |
| 真稿 AUC ≥ 0.85 | 0.5556 | 0.5952（D-no-hc3） | 差 0.2944 / 最好那份也差 0.2548 |
| 同一把尺子换第三方 test（第 6b 节，1,222,872 句） | 0.6240 | 出厂这份唯一量过 | 差 0.2260 |
| 真人误报 ≤ 2 句/千句 | 3.21（Python 切句口径）/ 4.06（Java 产品打分口径） | 2.20（D-no-hc3 的学术侧，不是真稿） | 真稿上没有任何配置到过线 |

一件必须交代的事：选择规则这一轮选出来的还是 B-prhpp-only（规则那一栏 0.7756），
而 13 个配置里 ③ 真稿最好的是 D-no-hc3（0.5952，规则那一栏 0.7693，只差 0.0063）。
也就是说这条规则平均而言对（r = +0.7454，第 5 节），这一轮却没有选中真稿上最好的那份。
我们**没有**因为看了真稿成绩就换成 D——那等于把真稿留出用掉一次，之后那个数就不干净了。
两边数字都摆在第 5 节那张表里，谁接手谁自己判。

这条没做到，原因是数据不是算法。要补的是这三样，缺一样都不行：

1. **真人侧：多篇多位作者的学位论文/期刊正文**（不是摘要、不是问答、不是新闻）。
   现在公共侧学术真人只有 `prhpp` 的 5,610 句摘要，产品要判的是 H1 那种段落（每段 120~656 字）。
2. **机器侧：现代大模型整篇生成的同域中文**（不是"把真人稿润色一遍"）。
   `prhpp` 的机器侧是润色档，模型学的是润色痕迹；`anxzh` 的 `parallel_*` 是现代模型但域在新闻/论说；
   `setask8zh` 是口语社区。真稿里最难的 M-DOMAIN（同领域同主题机器整段）在公开语料里**没有对应档**。
3. **规避风格档**：M-EVADE 那种"提示词里就要求别像 AI"的机器稿，公开集里一份都没有，
   本轮三个带剪枝/配平的配置（H/I/J/L）在真稿上最好也只到 0.4416。

第 6b 节那个外部数顺带把"要什么数据"说得更死了：同一批真人稿当对照，
claude-3-5-sonnet 还有 0.7819、glm-4-9b-chat 0.7491，ChatGLM3-6B / Baichuan 掉到 0.56~0.57，
而我们公开语料的机器侧主力是 2023 年那批 ChatGPT 腔（公共留出 0.8091）。
**语料的机器侧停在两年前，能力就停在两年前**：新数据必须带现代模型，而且要整篇生成，不是润色。

最接近的公开集还是 `QiYuan-tech/LLM-Detector`（7 个国产模型 + GPT-4 与真人答案配对），
它要登录接受条款才能取（本轮匿名请求仍是 **HTTP 401**）。要走这条路就得人工过一次授权，
拿回来之后照本文件的规矩：只进派生统计量，许可与来源写进清单，留出真稿一个字不进训练。


## 8b. 第二轮补料：现代模型 + 学术域找到了，接进来重训，真稿那一档 0.45~0.50

按第 8 节那三条要求筛完，公开侧能拿到的是三路（许可、行数、模型清单、域分布见第 2 节的表，
查过又放弃的见第 3 节）：**magazh / magaaug**（MAGA-Bench 中文，MIT 声明，13 台 2024-2025 模型）与
**drlxzh**（DetectRL-X 中文，MIT，DeepSeek-V3 / Gemini-2.5-Flash / GPT-4o / Qwen-Max）。
三条要求现在的状态：

1. **真人侧学术正文：补上一半。** `drlx-academic` 的真人侧是中文期刊与法医学鉴定文书的正文（70,180 计分句，
   多篇多位作者），这是公开中文语料里第一次有正文级的真人学术稿；`academic-maga`（CSL）仍然只是中文核心期刊的**摘要**，
   和 `prhpp` 一样到不了 H1 那种 120~656 字的段落。
2. **机器侧现代模型整篇生成：补上了。** 12 台 + 4 台，全部 2024-2025，没有 gpt-3.5 / davinci。
   但生成方式是"按题目整篇写摘要"（MAGA）和"拿真人正文当材料改写/摘要"（DetectRL-X 的 Academic 档），
   不是"给一个论文选题整篇写正文"。
3. **规避风格档：补上一部分。** `magaaug` 是挂人格/文风 system prompt 的强风格改写档；
   DetectRL-X 另有 11 种改写策略（回译、encoder/decoder/seq2seq 改写、压缩、扩写、字符增删、零宽字符……），
   本轮只接了 `general` 那一路，改写那几路每路 0.7~1.2 GB，没下。

接进之后新加三个配置（`tools/build-aigc-model.py` 的 `CONFIGS`），三档留出的数：

| 配置 | 用什么料 | 计分句 | ① 公共留出 | ② 学术配对档 | 现代模型同域那一栏 | 规则那一栏 | ③ 真稿 AUC | ③ 真人误报/千句 | ③ 机器过线 | 跨域均值 / 最差 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| N-maga-academic | prhpp 全部 + MAGA 的学术档（摘要，两边同域） | 38,898 | 0.8940 | 0.7869 | **0.9488** | 0.7444 | **0.4519** | 35.26 | 1.08% | 0.7108 / 0.6700 |
| O-modern-mix | 上面 + MAGA 全部 10 域 + DetectRL-X 全部 6 域（域内配平） | 398,340 | **0.9330** | 0.7521 | 0.9400 | 0.7055 | **0.5006** | 22.44 | 0.00% | 0.9011 / 0.7056 |
| P-modern-evasive | 上面 + MAGA 风格改写档 + 跨域同号剪枝 0.75 | 414,257 | 0.8642 | 0.6798 | 0.8657 | 0.6205 | **0.4933** | 11.22 | 0.00% | 0.8418 / 0.6490 |
| **B-prhpp-only（出厂这份，本轮没动）** | 只有学术摘要配对档 | 13,897 | 0.8091 | 0.8091 | 无 | **0.7756** | 0.5556 | 3.21 | 0.27% | 无 |

表注一：三个新配置的每域上限是 **25,000 句**（`CAP_PER_DOMAIN_NEW`），不是老配置的 60,000。
这台机器只有 16 GB（空载剩 5 GB），17 个域 × 60,000 句在字符 2-4-gram 的 CountVectorizer 上会撑爆内存；
三个新配置之间这个上限一致，与 13 个老配置比句数时把这一条差异按实记在这儿。
表注二：① ② ③ 与"现代模型同域那一栏"由 `py tools/build-aigc-model.py compare --configs N-maga-academic,O-modern-mix,P-modern-evasive`
现取（`compare.json`）；规则那一栏、跨域与逐域明细由 `py tools/build-aigc-model.py train --configs B-prhpp-only,N-maga-academic,O-modern-mix,P-modern-evasive`
现取（`train-summary.json`）。"现代模型同域那一栏"是本轮新加的诊断列 = `academic-maga` 那一档的公共留出 AUC
（真人摘要 vs 2024-2025 模型整篇生成），只报数，不参与选择规则。

四条读法：

- **新语料在自己那一档好分得很，真稿上不买账。** `academic-maga` 同域 **0.9488**、`drlx-academic` 同域 **0.9333**，
  跨域留出均值最高 0.9011（17 个域），这是这套字符 n-gram 在公开侧拿到过的最好一档；
  同一份权重的 ③ 真稿却是 0.4519 / 0.5006 / 0.4933，三份都低于出厂那份的 0.5556。
  第 5 节那句"公共侧任何留出手法量的都是公开域之间的距离"，换成现代模型 + 学术域之后一模一样。
- **① 越高 ③ 不一定越好。** ① 刷到 0.9330（公开侧历史最高一档）的那份，真稿 0.5006。
  本轮三个配置自己算 r(①,③) = +0.2148，n=3，什么也说明不了，别当结论用。
- **真人误报这一栏全线不过。** 门槛是 2 句/千句，本轮三个配置是 35.26 / 22.44 / 11.22，
  连最保守的 P（带跨域同号剪枝）也超五倍。域加得越多、公共侧越好分、真稿上把真人误伤得越狠，
  与第 5 节"从 B 到 A"那条同向。
- **选择规则没动，出厂那份没变。** 规则那一栏 B 0.7756 > N 0.7444 > O 0.7055 > P 0.6205，
  `train` 仍选 **B-prhpp-only**；`gate` 重跑复核与上一版一字不差（真稿 0.5556、真人误报 3.21、
  逐对 AUC 与第 6 节那张表逐格相同），所以 `assets/aigc/` 三个文件与随包清单本轮**一个字节都没改**，
  `calibrated()` 仍是 false，界面继续不印百分比。新语料没进出厂那份，是规则没选它，不是没接上。

第二把外部的尺子：`koakuma/RealDet` 中文那两份（CC-BY-NC-4.0，只做对照，一个字没进训练）。
135,840 篇 → **928,978 个计分句**，与出厂这份训练集**逐字重合 0 句**，句长没有偏置
（只看句长 AUC 0.4780，句长中位 真人 36 / 机器 35）：

| 量在哪 | 计分句 | AUC | 真人误报/千句 | 机器过线 |
| --- | --- | --- | --- | --- |
| RealDet 中文全部（真人 9.2 万句 + 机器 83.7 万句） | 928,978 | **0.6639** | 22.31 | 4.24% |

按生成模型拆开（真人侧共用同一批）：tongyiqianwen 0.7261、360GPT 0.7100、ChatGLM-2 0.7055、MOSS 0.7007、
wenxinyiyan 0.6977、星火 0.6922、Baichuan 0.6873、GPT-4o 0.6600、BLOOMz 0.6542、DeepSeek 0.6250、
**Claude-3 只有 0.5305**。与第 6b 节同一件事：换一台生成机器，分数就换一档（这里跨 0.1955），越新的模型越难判。
这把尺子同样指着 0.62~0.66 那一带，离 0.85 差 0.19 以上。一处保留：RealDet 中文真人侧只有 10,545 篇
（机器侧 125,295 篇），两边不成比例，那十一个 AUC 用的是同一个真人池。

**还差的那一段，两轮筛完仍然在公开侧之外。** 要的是：真人学位论文/期刊**正文**（多位作者、段落级 120~656 字）
与"同一个选题让现代模型整篇写"逐段配对的中文。本轮这两路都差一截：MAGA 的学术档是摘要 + 人格 prompt，
DetectRL-X 的 Academic 档是"真人正文 → 模型改写/摘要"。它同域能到 0.9333 恰恰说明这种配对有改写痕迹可学，
而真稿里最难那一档 M-DOMAIN 是整篇直出，一点痕迹都没有（它在公开语料里依然**没有对应档**，第 8 节第 2 条原样成立）。
往下只剩两条路：① `QiYuan-tech/LLM-Detector` 那类要人工授权才能取的库（步骤在第 3 节与随包清单里）；
② 自建：拿真稿那篇的同一批选题让现代模型整篇写正文当机器侧，真人侧另找多位作者的公开学位论文/期刊正文。
第 ② 条有一条硬边界：不许拿 `tests/corpus/` 里那 995 句真稿当训练料，那是留出集；
本轮复核过三路新语料与它**逐字相同 0 句**，用了这个数就废了。

训练台这一轮改了五件事（都在 `tools/build-aigc-model.py`）：

1. `CORPORA` 加 `magazh` / `magaaug` / `drlxzh` 三路，`rows_of` 认它们的字段
   （MAGA：`model=="human"` 判真人、`domain` 查 `MAGA_DOMAINS` 落域、组号用 `human_source_id`
   且两份文件共用一个前缀，同一条人类原文与它的机器版本一定落在同一折；
   DetectRL-X：一行里同时有 `human_written_text` 与 `llm_generated_text`，一行拆成一对）。
2. `fetch` 支持"下载的文件名与训练读的文件名不是一回事"（`raw_file`），并自动把 DetectRL-X 那个
   878 MB 的 JSON 数组流式扫一遍，只留 `lang=chinese` 的 15,600 行另存 jsonl（`ensure_drlx_zh`）。
   原文与抽取件都留在 `../aigc-corpus/`，仓库里只有派生统计量。
3. `CONFIGS` 加 N / O / P 三个配置 + 新的每域上限 `CAP_PER_DOMAIN_NEW = 25000`（内存只有 16 GB，见 8b 表注一）。
4. `compare` 也吃 `--configs` 了：17 个配置全跑一遍要十几分钟，指哪几个跑哪几个。
5. 随包清单的 `trained_on` 改成只写这份配置**真正读到**的来源（以前把 `CORPORA` 里所有中文源一律列上去，
   选了 B 也照抄全套，等于清单在说谎）；`external` 那一步认第二种标法（RealDet 的 `label` 是模型名字而不是 0/1），
   `--file` 支持逗号分隔的多个文件。

## 9. 怎么复现

```
# 0) 代理：这台机器 7890 连接被拒，可用的是 7897（默认值就是它）
# 1) 取公开语料到 ../aigc-corpus/（默认代理 127.0.0.1:7897，--proxy "" 走直连）
py tools/build-aigc-model.py fetch
# 2) 13 个配置全跑：公共/学术/跨域三档 + 逐域明细，落 artifacts/agent-aigc-offline/train-summary.json
py tools/build-aigc-model.py train
#    单跑一个：py tools/build-aigc-model.py train --configs B-prhpp-only
#    本轮新加的三个配置（现代模型中文语料，别和全量一起跑；三个约 12 分钟）：
#    py tools/build-aigc-model.py train   --configs B-prhpp-only,N-maga-academic,O-modern-mix,P-modern-evasive
#    py tools/build-aigc-model.py compare --configs N-maga-academic,O-modern-mix,P-modern-evasive
# 3) 三档留出 + 滑窗落点 + 两条出厂门槛，落 gate.json（只有这一格能把 calibrated 打开：③ 真稿）
py tools/build-aigc-model.py gate
# 3b) 外部对照：第三方中文 test（只读不训）。先取回 178 MB：
#     py -c "..." 见第 3 节那一行的 URL，存成 ../aigc-corpus/coling25_zh_test.jsonl
py tools/build-aigc-model.py external
#     第二把外部的尺子（RealDet 中文那两份，CC-BY-NC-4.0：只读不训；--file 可以逗号分隔给多个文件）：
#     py tools/build-aigc-model.py external --file ../aigc-corpus/realdet_hwt_cn.jsonl,../aigc-corpus/realdet_mgt_cn.jsonl
#     本轮三路新料的取回：MAGA-cn 的 train/MGB-cn_train.jsonl 与 train/MAGA-cn_train.jsonl，
#     DetectRL-X 的 Binary/binary_general_open.json —— fetch 那一步会自动把里面 lang=chinese 的 15,600 行
#     另存成 ../aigc-corpus/drlx_zh_general.jsonl，原文与抽取件都留在仓库外，不进仓库
# 3c) 13 个配置的三档对照表 + "公共侧哪一栏预测得了真稿"的 r（约 10 分钟，落 compare.json）
py tools/build-aigc-model.py compare
# 4) 导出随包三件（只写派生统计量，不写原文）
py tools/build-aigc-model.py export
# 5) 主机测试（会清共享目录 artifacts/build/host-classes，别和别人的编译同时跑）
pwsh tools/test-host.ps1
#    只想自己验 AIGC 那一套时，用私有输出目录编译，别碰 host-classes：
#    javac -encoding UTF-8 -classpath tools/android-35.jar -d artifacts/build/aigc-classes <app 源文件>
#    javac -encoding UTF-8 -classpath artifacts/build/aigc-classes;tools/android-35.jar -d artifacts/build/aigc-test-classes tests/AigcOfflineModelRegression.java
#    java -cp artifacts/build/aigc-test-classes;artifacts/build/aigc-classes;tools/android-35.jar ^
#         com.rikkahub.wordlite.AigcOfflineModelRegression app/src/main/assets/aigc tests/corpus
```

本轮量第 5 节那张表用的是同一份 `fit_config`（`tools/build-aigc-model.py` 里的函数），
逐配置的三档数与逐域明细落在仓库外的 `../aigc-work2/diag.json`；
语料句数与逐字重复数是 `tools/build-aigc-model.py` 的 `rows_of` + 一份 SHA-1 去重计数跑出来的。
Python 侧只要 numpy + scikit-learn（本轮为读 parquet 镜像另装了 pyarrow，最后没用上：
中文侧走的是 JSONL 镜像）；Java 侧什么都不用加。
