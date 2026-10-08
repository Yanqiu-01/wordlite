# 抗改写（降重）匹配改造方案

出处与逐字引用在 [artifacts/research/paraphrase-robustness.md](../artifacts/research/paraphrase-robustness.md)。判据基线在 [docs/rewrite-robustness.md](rewrite-robustness.md)（`tests/RewriteRobustnessRegression.java`，17 档确定性变换）。本文只回答一个问题：**改写之后漏掉的那批字，按公开实现的哪一套参数改能捞回来，风险多大。**

## 1. 基线（已实测，本文不重复造轮子）

`docs/rewrite-robustness.md` 在 `SIMILAR_DICE = 0.50` 下的现行数字，20 段种入 / 85 句同领域负例：

| 口径 | 召回 | 备注 |
| --- | --- | --- |
| verbatim / app-rewriter / synonym / clause-order / sentence-order / number-style / drop-20pct / stacked / sub-char-10 / merge-pairs | 99.7%-100% | 已经打满，动匹配没有空间 |
| spliced | 99.5% | 包含率那条路兜住了 |
| pruned | 97.8% | 0.45 档能到 99.4%，但那一档不做（见第 4 节） |
| split-commas | 98.1%（归属 98.8%） | **在 0.35-0.55 整张阈值表上一动不动**，结构性不可达，见 D1 |
| sub-char-25 | 26.4% | 悬崖，见 D2 |
| sub-char-50 | 1.7% | 同上 |
| local-edit-16 / local-edit-25 | 99.5% / 99.8% | 大模型改写的真实形状：几处 3~6 字的局部疤。已经打满，D3 的桥接条件不成立 |

三条本文必须服从的既有结论：删与换序不打这把尺子；换字才是悬崖，位置与 `1 - ∛0.50 = 20.6%` 对得上（三元组 Dice ≈ `(1-s)^3`）；本应用自己的离线降重只推动 6% 的改动量（20 段共 291 字），所以 `app-rewriter` 那一行离 `verbatim` 几乎没有距离 —— **威胁模型是第三方/大模型改写，而这一头实测台自己承认没有可复现语料**。

## 2. 匹配阶段要扛住的操作清单

### 2.1 `LocalRewriter`（产品自己的降重）逐条枚举

先划边界：`protect()` 与 `TextProtection` 把数字、单位、型号、登记术语各自折成一枚私有区字符再还原（`LocalRewriter.java:397-435`），外加 `islandsIntact` 与 `brackets()` 两道守卫，所以**这个类不动数字**；"数字换写法"来自人手或第三方工具。

- 结构（改动最大，全是删虚词 + 搬宾语）：`对X进行了V ↔ V了X`、`X被V(了) → 将XV了`、删"广泛/大量/普遍…"前的 `被`、`把XV → 将XV`、`通过X可以|能够|来Y → 借助X…Y`、`副词+地+动词 → 副词+动词`、英文 `which|that is V-ed → V-ed`（`LocalRewriter.java:304-338`）。
- 词序：英文被动翻转 `The X was V-ed by Y → Y V-ed the X`；中文比较式翻转 `X高于Y → Y低于X` 等 8 对（`COMPARATIVES` 218-221 行）—— 全套规则里唯一会把两段长距离对调的变换。
- 词汇/连接词/语气：中文 37 对 2-4 字整词互换（`LEXICAL` 223-262 行），英文 20 词对 + 5 短语（含大写副本）。
- 破坏半径：`RECIPES` 三条配方（367-371 行）+ 单规则候选，`collect()` 每条配方**每个策略只采纳第一条命中的规则**，所以一个候选最多 5 处改动；实测台的 `app-rewriter` 是逐轮采纳到底（`REWRITE_ROUNDS = 24`）。

### 2.2 第三方改写会做的事与实测台的对应档

同义词替换（`synonym`）、句内分句倒序（`clause-order`）、段内句序倒序（`sentence-order`）、数字换写法（`number-style`）、删句（`drop-20pct`/`pruned`）、拆句（`split-commas`）、并句（`merge-pairs`）、跨句拼接（`spliced`）、以及只有容忍度意义的均匀逐字替换（`sub-char-10/25/50`）。**真实大模型改写的形状是"每句几处、每处几个字的局部改动 + 少量句子增删"，这批口径里没有一档长成这样** —— 这是 D3 的由来。

## 3. Delta 清单（按"预期召回增益 / 抬高相似率的风险"排序）

### D1（第一位）拆句碎片的可达区间：`LENGTH_BOUND_FLOOR` 当前 0.5f → 新增 `CONTAINMENT_BOUND_FLOOR = 0.38f` 并让包含率通道用它

`TextCorpus.java:691-692` 的包含率分支现在要求 `containment >= 0.90f && dice >= LENGTH_BOUND_FLOOR && max/min >= 1.6f`，候选裁剪那一刀（`TextCorpus.java:674`）也用同一个 0.5；提议把**包含率通道**的两处下限换成 0.38（等价于把可用的长短比从 3.0 放宽到 4.3），并给这条通道单独一条证据地板 `MIN_SHARED_GRAMS_CONTAINMENT = 8`（现在两通道共用 `MIN_SHARED_GRAMS = 3`，`TextCorpus.java:66`），因为实测已经指明这是结构问题而不是阈值问题：`split-commas` 的召回来回钉在 98.1%（0.35/0.40/0.45/0.50/0.55 五档一位不差），196 个碎片里 93 个**连候选都没问到**，而包含率通道的可达区间被 `dice >= 0.5` 自己卡在 1.6-3.0 倍，16 字碎片对 54 字原句是 3.4 倍（`docs/rewrite-robustness.md` 的 `-Drrroc` 一节）。机制上这条与公开实现同源：WCopyfind 对"带瑕疵的短语"用的精确率地板是 80%（`https://wcopyfind.wfindapps.org/settings.html`，`Allow up to … imperfect words` 0→2、`% of words that must match` 100→80），Plaggie 的相似度本来就是**单边包含**（`getSimilarityValueA/B` 返回 `matchedTokens / allTokens`，`plaggie2/src/plag/parser/SimpleTokenSimilarityChecker.java:152-188`），也就是说"短句整段嵌进长句"在别家是一等判据而不是被几何条件挡在门外的特例。预期影响 recall 与归属（`split-commas` 是唯一在阈值表上纹丝不动的一行，也是降重手册上最便宜的一招），噪声风险低——**注意风险低不是来自 0.90 那个数**（实测包含率 0.90/0.80/0.70 三档逐位相同，单降它等于不做事），而是来自"一段碎片 90% 的三元组原样出现在库里某句里"这件事本身有多不像巧合：16 字的碎片只有约 14 枚三元组，0.90 的包含率要求其中至少 13 枚在库里某句里原样活着。代价是候选裁剪放宽之后每句多评若干条，仍受 `MAX_CANDIDATES = 400` 与 `POSTING_SCAN_CAP = 6000` 封顶。

配套要一起改的一处：`LENGTH_BOUND_FLOOR` 的注释（`TextCorpus.java:73-79`）说"包含式判定自己就要求 Dice >= 0.5，故该裁剪无漏报"，这条前提一改就要跟着改，否则候选在进判据之前就被裁掉了，D1 等于没做。

### D2（第二位）词序无关的第二判据：新增字符袋 Dice `SIMILAR_BAG` 与虚词二元组 `SIMILAR_FUNCTION`，只作 OR 通道，**且先量它自己的负例天花板**

这是唯一能碰到 `sub-char-25` 那批字的路线，实测台的 ROC 已经把话说到这份上：83 句正例里阈值那一头最多捞回 9 句，剩下 45 句压在 0.45 以下，"那需要词序无关的特征（词集/语义），不是 n-gram 家族能做到的事"（`docs/rewrite-robustness.md`）。为什么袋口径能翻盘是算术：

| 逐字替换率 s | 三元组 Dice ≈ `(1-s)^3` | 字符袋 Dice ≈ `2(1-s)/(2-s)` |
| --- | --- | --- |
| 0.10 | 0.729 | 0.947 |
| 0.25 | 0.422（低于 0.50，所以现在漏） | 0.857 |
| 0.50 | 0.125 | 0.667 |

出处是 Stein 等的停用词 n-gram 路线（`Plagiarism detection using stopword n-grams`, JASIST, `https://doi.org/10.1002/asi.21630`；**本轮只核实到标题，论文里的 n 与阈值未核实**），中文侧取一个写死的虚词闭集（的/了/是/而/则/以/并/但/且/把/被/将/对/给/从/到），与 `Fingerprints.tokens()` 跳过标点的现有口径互补；袋口径的动机不是抄某家默认值，而是它把判据的衰减指数从 3 降到 1。**但它的负例天花板现在谁都没测过**：0.358 那个数是三元组口径下的（`docs/rewrite-robustness.md` 的长度分桶表），袋口径天然会给同领域句子打高分。所以 D2 的第一步不是加判据而是给实测台加一列 `MARGIN_BAG`（每条负例在文库里的最高袋 Dice），照三元的同一条规矩把地板定在天花板 +0.14 之上；量不出来就不落地。预期影响 recall（`sub-char-25` 的 45 句、以及未来真实大模型改写），噪声风险四条里最高，因为它主动放弃顺序证据，而 50-80 字长句桶本来就是负例最高分所在。

### D3（第三位）先补一档真实形状的口径，再谈 WCopyfind 式桥接

`RUN_BRIDGE_GAP`（带子内部允许的连续错配字数）当前等价于 0（`run()` 只逐 token 比 `Fingerprints.code`，一枚不等就停，`TextCorpus.java:322-337`），WCopyfind 用的是 2 个瑕疵词 + 80% 精确率这一对值（官方推荐值表 0→2、100→80；引擎默认同为 2/80，`clib/CompareDocuments.cpp:46-51`；实现见同文件 334-500 与 1152-1154 行的 `Flaws` 计数与 `PercentMatching = 200*Perfect/(跨度左+跨度右)`），JPlag 的同族旋钮是 `DEFAULT_NEIGHBOR_LENGTH = 2` + `DEFAULT_GAP_SIZE = 6` + `DEFAULT_REQUIRED_MERGES = 6`（`core/src/main/java/de/jplag/merging/MergingOptions.java`，中文单字信息量低于代码 token，gap 起点应取 3-4 字而不是 6）。机制无可争议，**但它在现行实测台上量不出收益**：这套桥接针对的是"5%-20% 的**局部**改动密度"，而现有 15 档要么已经打满（`app-rewriter` 6% 改动、99.8% 召回；`synonym` 99.8%），要么是**均匀**逐字替换（`sub-char-25` 平均每 4 字就一处改动，任何精确率 ≥0.80 的桥接都必然被自己的地板挡住，这正是它该挡的）。所以提议的顺序是：先给实测台加一档 `local-edit`（每句 4 处、每处连续 3-6 字的替换/插入/删除，最贴近大模型改写），若那一行明显低于 99%，再落 `RUN_BRIDGE_GAP = 4` + `RUN_BRIDGE_PRECISION = 0.80f`，并允许 10 枚 token 的短片段作为**桥接种子**（报告仍要求链总长 ≥ `MIN_MATCH = 18`）；若那一行也在 99% 以上，这条就作废，不要为了理论完整性上桥接。风险等级取决于新口径的量出来的缺口，现在给数字就是编。

**已结：这条作废。** `local-edit-16`（每 25 个汉字整块换 4 字，20 段共改 450 字）召回 **99.5%**，
`local-edit-25`（每 12 个汉字换 3 字，改 713 字）召回 **99.8%**，归属都是 100.0%，噪声 0。
按上面自己定的规矩，桥接那一档不上。顺带把机制说清楚：n-gram 只数连续重合，整块替换只在疤周围毁掉
`块长 + n - 1` 枚三元组，长段原文照抄的部分整段活着；`sub-char-25` 之所以塌，是因为它平均每 4 字就一处改动，
平均每枚三元组都带着 0.75 的概率被打散。**同一个改动率，分布决定生死**，而大模型改写落在"疤"这一侧，
所以匹配阶段真正还欠着的只有 D1（拆句碎片的可达区间）与 D2（词序无关的第二判据）两条。

### D4（第四位）`MIN_SENTENCE_CHARS` 当前 12 → 10，同时把短句桶的共享三元组地板从 3 抬到 5

因为 WCopyfind 的 6 词种子折成中文是 9-12 字（settings.html，"a phrase must share at least six words in a row to count"），机制是 `fragment()` 在第 777 行对不足 12 个有效字符的片段直接 `return null`，拆句与删句之后的短碎片连比对都不进，而 0.7.2 之后这些字仍在分母里（`Report.comparedChars`，`TextCorpus.java:91-99`）—— 也就是说它们只压比率不压召回，这条修的是"该认的没认"，不是统计口径。预期影响 recall（`split-commas`、`pruned`、`drop-20pct` 的短碎片），噪声风险中等（短句天然容易撞，所以必须绑 `MIN_SHARED_GRAMS` 3→5，不能白送两个字），代价接近零。Plaggie 的 `minimumMatchLength=5` 不能照搬，那是 Java token。

## 4. 明确不做（每条都有实测或标定证据，别重新发明）

| 想做的事 | 为什么不做 |
| --- | --- |
| 单降 `SIMILAR_CONTAINMENT` 0.90 | 实测 0.90/0.80/0.70 三档逐位相同（`docs/rewrite-robustness.md` 阈值扫描一节）。真正卡住的是长短比区间，见 D1 |
| `SIMILAR_DICE` 再往 0.45/0.40 走 | 0.45 多认回约 5 个点、0.40 认回 46.1%，但 0.35 就撞出 399 字误标，而负例天花板 0.358 只剩 0.09 空档；现行停在 0.50 的决定是对的，放行条件写在 `TextCorpus.java:29-30` |
| 按句长放松下限（"长句证据多所以可以松"） | 已被实测否掉：负例最高 Dice 0.358 恰恰出现在 50-80 字桶，`<20` 桶只有 0.158，方向与直觉相反 |
| `Fingerprints.GRAM` 7 → 6 换召回 | 锚点层扫格结果里 n=6/w=16/min=3 的同源误报是 2.9%，n=7/w=12/min=3 才是 0.0%（`docs/detection-calibration.md`）。D2/D3 落地之前降 n 是拿误报换召回 |
| `MIN_SHARED_FINGERPRINTS` 3 → 2 | 旧表上这一步把同源误报从 0.0% 抬回 40.4%。真要动，动成"2 枚且落在同一 40 字窗口内"的成簇条件，依据是 winnowing 论文 §5.2 的按共享指纹数排序 + 低于阈值不物化 |
| `MERGE_GAP`、`WINDOW_CHARS`/`WINDOW_OVERLAP`、`SIGNATURE_SLOTS` 的 MinHash | 前两者只影响高亮观感与开销（0.7.3 已把重叠双算改成命中并集）；MinHash 只做同分排序，过滤一律按共享三元组数（`TextCorpus.java:878-891`） |
| 句向量 / WMD / MoverScore / LFP | 要模型或跨语料矩阵分解，手机端无原生依赖这条过不去；本轮 LFP 的原始论文也没取到（见研究记录第 8 节） |

## 5. 验收与回退

一条 delta 一次改动，跑 `RewriteRobustnessRegression` 全 15 行 + `-Drrs` + `-Drrroc`。放行四条同时成立：目标行召回上升；`floors()` 的噪声为 0 仍绿（它自带"把阈值压到 0.30 必须撞出误标"的反证，别绕）；`verbatim` 一个字符不动；归属率不降。任何一条让噪声 > 0 就回退这条 delta，不要靠再叠阈值压回去 —— 那等于把 0.142 的空档吃光，而文库规模上来之后的偶发撞车还没进过任何一批样本。顺序：D1 → D4 →（加 `local-edit` 口径）→ D3 →（加 `MARGIN_BAG` 基线）→ D2。

## 6. 本轮未核实，别照着写

MOSS 服务端实际用的 k 与 s（官方页与 SIGMOD'03 论文都没有，网上流传的 k=9/s=3 无可引用出处）；Stitch 的 n-gram 取值与 internal/external 定义；Yap、Yap-SG、`yap4sg` 的一切参数与误报率；Latent Semantic Fingerprinting 的维度与效果；SimHash / MinHash-LSH / SSTtraw 的任何数值；plaggie.org 那版 Plaggie 的 `n` 与 `min-match-length`；Stein 停用词 n-gram 论文里的 n 与阈值；VCopyfind 的 `MINIMUM_WORD_COUNT` 与 `EXCLUDE_FLAG`；`docs/oss-algorithms.md` 里"82% 的指纹只出现一次"这句（winnowing PDF 文本层检索不到）。逐条失败端点记在 `artifacts/research/paraphrase-robustness.md` 第 0 节。