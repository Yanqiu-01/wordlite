# 开源实现里的可借用参数

结论只写读到的源码或官方文档，每条带出处。落地版本标在标题后。

## 近似重复定位（0.5.x）

- Winnowing（Schleimer/Wilkerson/Aiken, SIGMOD'03，https://theory.stanford.edu/~aiken/publications/papers/sigmod03.pdf）：窗口 w 内取最小哈希的最右者；指纹密度理论值 2/(w+1)，局部算法下界 1.5/(w+1)；任何长度 ≥ t = w + k - 1 的公共子串必被命中，t 就是"最小可报告匹配长度"。网页文本实测取 k=50 字符、w=100、64 位哈希；低熵串（全 0、abbaabba）会让指纹暴涨，解法是 Robust Winnowing：优先沿用上一窗口已选哈希，否则取最右最小值。
- MOSS 的上报流程（同文 §5.2）：指纹 → (doc, 位置) 倒排 → 二次指纹化查询 → 按目标文档排序 → 按共享指纹数排序，低于阈值的配对不物化。实测 82% 的指纹只出现一次，2% 出现三次以上，高频指纹是菜单和法律套话，这就是"按指纹频率截断"的依据。MOSS 官方页（https://theory.stanford.edu/~aiken/moss/）：分数只用于相对排序，靠分数下结论是误报的起点；误报抑制靠 look-alike 库（预先登记"本来就该重复"的代码）。
- JPlag 用的是 GreedyStringTiling 而不是 winnowing：滚动哈希 `hash = 2*hash + token%64`，`MAX_HASH_LENGTH=25`，含被标记 token 的窗口置 NO_HASH（https://raw.githubusercontent.com/jplag/JPlag/main/core/src/main/java/de/jplag/comparison/RollingTokenHashTable.java）。每轮取最长匹配、禁止重叠、命中的 token 立刻标记不可用，直到不再增长；短于 `minimumTokenMatch` 的匹配进 ignoredMatches 而不是丢掉（https://raw.githubusercontent.com/jplag/JPlag/main/core/src/main/java/de/jplag/comparison/GreedyStringTiling.java）。Java 的 minimumTokenMatch = 9 token。相似度是 token 版 Dice：两侧匹配 token 数之和 / 两侧 divisisor 之和。
- JPlag 抗改写靠 match merging：`neighbor-length 2 / gap-size 6 / required-merges 6`，注释写明是"对抗轻微乱序与混淆"（.../merging/MergingOptions.java）；误报抑制靠 base-code 目录先比再把命中的 token 永久排除，以及高频匹配降权 `SigmoidWeighting, weightingFactor=0.25`。
- WCopyfind（https://wcopyfind.wfindapps.org/how-it-works.html、https://wcopyfind.wfindapps.org/settings.html）：词 → 32 位哈希，每文档存读取序与排序序两份，靠有序表线性合并求共享词；种子词必须 >3 字母，所以纯虚词短语永远不会成为匹配起点；`Match phrases of at least` 默认 6 词，`Report pairs that share at least` 默认 100 词（短文档建议 20..40），`Allow up to ___ imperfect words in a row` 默认 0、抗改写建议 2 且要求短语内 ≥80% 词完全相同。npm 复现实现同参数：PhraseLength 6 / WordThreshold 100 / SkipLength 20 / MismatchTolerance 2 / MismatchPercentage 80（https://github.com/cmroanirgo/pl-copyfind）。
- 中文取多少：公开实现没有"char n-gram + winnowing"的中文配置（antiplag 走 HanLP 分词后交给 JPlag/MOSS，https://github.com/fanghon/antiplag）。把 WCopyfind 的 6 词折算成中文约 9..12 字，再按 t = w + k - 1 对齐，字符级 n 取 8..12、w 取 10..25 才够"整句级最短匹配"。现在 TextCorpus 用字符 3-gram + Dice 0.60，等价于把最小匹配长度压到约 1.5 词，这是误报的主要来源。

## 候选检索排序（0.6.x）

- 两档参考实现：`BM25Okapi(k1=1.5, b=0.75, epsilon=0.25)`，idf = log((N-df+0.5)/(df+0.5))，负 idf 抬到 0.25×平均 idf（https://raw.githubusercontent.com/dorianbrown/rank_bm25/master/rank_bm25.py）；Lucene 默认 `k1=1.2, b=0.75`（https://raw.githubusercontent.com/apache/lucene/main/lucene/core/src/java/org/apache/lucene/search/similarities/BM25Similarity.java）。摘要之间长度差异远小于网页，b=0.75 会过度惩罚长摘要，取低一些更合适。
- OpenAlex：`filter=title_and_abstract.search:<短句>` 可用；空答案是 `meta.count: 0` 的 200 响应；摘要以 `abstract_inverted_index` 下发，要自己按位置还原（https://developers.openalex.org/guides/search）。Crossref 用 `query.bibliographic` + `rows` + `select`，带 `mailto` 进 polite pool。Semantic Scholar 无 key 的检索返回带 body 的 429，必须记成"引擎失败"而不是"0 命中"。
- 覆盖面实测：OpenAlex 里 `language:zh` 约 544 万条，`language:en` 约 2.26 亿条（zh 占 2.3%），zh 里开放获取仅约 29.6 万条。所以维普/哲社这类中文源只能爬 HTML，OpenAlex/Crossref 主要补英文综述与预印本；候选数=0 是覆盖面问题，不能直接翻译成"重复率 0%"。

## 改写后的相似度（0.5.x / 1.0.4）

- 经验上活得下来的特征，按代价排序：允许桥接少量不同词且设精确率下限（WCopyfind 2 个瑕疵词 + ≥80%）；相邻匹配跨 gap 合并（JPlag neighbor 2 / gap 6 / required 6）；停用词 n-gram 作特征，顺序无关且对内容词替换免疫（Stein 等，https://doi.org/10.1002/asi.21630）；containment 一侧的集合度量。
- 需要模型下载、纯 Java 离线做不到的：sentence-transformers 的 paraphrase_mining、Word Mover's Distance、MoverScore。手机端不放。
- 纯 Java 可落地的组合：内容词多重集 Dice + 字符 5..8 gram containment + 虚词 2/3-gram 余弦（中文取 的/了/是/而/则/以/并/但 这类闭集）+ 句级 LCS 对齐后逐句 Dice + JPlag 式"最长优先、禁重叠、用过即标记"。全部 O(n log n)。

## 离线 AIGC（0.7.x）

- Binoculars：performer 困惑度 / observer 交叉困惑度，`max_token_observed=512`，阈值 0.9015（按 F1）与 0.8536（按 FPR=0.01% 标定），需要两个 8B 级模型逐 token logprob（https://raw.githubusercontent.com/ahans30/Binoculars/main/binoculars/detector.py，https://arxiv.org/abs/2401.12070）。
- DetectGPT：50 次 T5 mask-fill 扰动，score = (LL(x) − mean)/std，阈值 0.7（https://arxiv.org/abs/2310.05130 与 https://raw.githubusercontent.com/BurhanUlTayyab/DetectGPT/main/model.py）；Fast-DetectGPT 用自采样差异省掉扰动采样（https://arxiv.org/abs/2310.05130）。GLTR 的三个检验依赖词概率与 rank 分桶（https://arxiv.org/abs/1906.04043）。这些都依赖 token 级 logprob，手机上不成立，别装。
- 负面结论要写进报告：MULTITuDE 用 11 种语言 74081 条文本测跨语言/跨生成器泛化（https://arxiv.org/abs/2310.13606）；递归复述攻击在约 300 token 段落上把统计、零样本、神经、检索四类检测器一起打下去（https://arxiv.org/abs/2303.11156）；中文微调检测器域内过拟合明显（https://arxiv.org/abs/2402.01158）；OpenAI 那版检测器模型卡自己写着"强烈不建议当 ChatGPT 检测器用"（https://huggingface.co/openai-community/roberta-base-openai-detector）。所以手机端的 AIGC 只能报"风格倾向 + 建议复核"。
- 可保留的离线特征：句长离散度、虚词密度与其 n-gram 分布、模板正则、标点轮廓。没有 logprob 就没有 burstiness 的正主（DetectGPT 仓库里 burstiness 的操作化定义是逐句 ppl 最大值）。

## 定标与报告口径（0.4.6 / 0.5.3 / 0.7.0）

- 三档判定 + "证据不足"是公开实现的常态：DetectGPT 复现仓库 <60 判 AI、60..80 判"可能含生成内容，需要更多文本"、>80 判人写，并有 `min 100 characters` / `min 100 words` 入口守卫与分块出分。
- 不当结论用：MOSS"分数只用于相对比较"；JPlag 默认 `DEFAULT_SIMILARITY_THRESHOLD = 0`、展示前 2500 个配对、短于 minimumTokenMatch 的匹配保留在 ignoredMatches 供人查；WCopyfind 用绝对量做闸门（共享词总数 ≥100，短文档 20..40），而不是只看比例。
- 落到本项目的三条硬规则：按比例 + 按字数双门槛；次阈值命中单独列出而不是并进 0%；可比对字数不足时禁止输出 0.00%，改说"可比对字数不足"。
## 没核实到的东西，别照着写

- "CopyCatch"作为查重工具没有可信开源仓库；同名 2013 年论文（https://doi.org/10.1145/2488388.2488400）讲的是社交网络的点赞锁步图检测，与文本查重无关。
- winnowing 的权威出处只有 Schleimer 那篇 SIGMOD'03；传闻中的"Schwieterman 版 winnowing"在 OpenAlex/Crossref/arXiv 都查不到，不引用。
- NetApat 的特征集没能核实：arXiv、OpenAlex 标题检索（`count: 0`）、Crossref、GitHub search 都没定位到论文或代码，Semantic Scholar 全程 429。"功能词 n-gram + 轻量线性模型"这条路线的出处只落在 Stein 的停用词 n-gram 那篇。
- MOSS 官方页没有任何"用搜索引擎找候选"的说法，它只比提交上来的文件集；候选检索这一段只能参考 OpenAlex/Crossref 的公开 API 实践。