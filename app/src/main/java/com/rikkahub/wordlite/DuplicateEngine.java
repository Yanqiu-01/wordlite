package com.rikkahub.wordlite;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Duplication and AIGC orchestration: citation marking, bounded retrieval, corpus match, one report. */
public final class DuplicateEngine {
    /* 检索短语上限 48 字：实测维普对 160 字的句子返回 0 条，同一篇摘要截到 40 字就返回 20 多条，
       OpenAlex / Crossref 这类源在短查询上也没有变差。 */
    static final int WINDOW_PARAGRAPHS = 3, MAX_WINDOWS = 24, MAX_PHRASE_CHARS = 48;
    /* MAX_WINDOWS 只是校验上限，与 EngineSettings.validate() 的 1-24 同源；运行时切几组窗口由
       limits.windows 说了算。MAX_REQUESTS 是"窗口数 x 源数"的真上限且默认可达：默认 9 个源 x 6 个
       窗口 = 54 次，用户把窗口拉到 24 就是 216 次，撞顶必须留字据。
       MAX_CORPUS_PAPERS 是语料侧的入库总闸，逐源上限取 limits.perEngine：9 个源 x 12 篇 = 108 <= 120。 */
    /**
     * 全文额度 10：开放获取的 PDF 是"抽得出字才算一篇"，实测 12 次下载成 4 次（2026-10-09），
     * 6 次额度期望只有 2 篇可比正文，10 次才够 3-4 篇。代价是流量：一轮实测见 docs/retrieval-recall.md。
     */
    static final int MAX_REQUESTS = 120, MAX_FULL_TEXTS = 10, MAX_CORPUS_PAPERS = 120, MAX_NOTES = 40;
    /** 分档命中那一格的名字。它不是第四个比率，只是把分子按材料档拆开说一遍。 */
    public static final String MATERIAL_SPLIT_LABEL = "命中材料分档";
    /** 报告里列几条"可以下进自建库"的候选：再多那一屏就没人看了。 */
    static final int MAX_DOWNLOADABLES = 10;
    /* 挂钟闸门与限速：实测一轮 9 个源约 10 秒（维普最慢 4062ms），但 Routes 的多路尝试能把单个
       请求拖到 20 秒以上，所以只设请求数上限挡不住慢网络，两个闸必须同时存在。 */
    static final long MAX_SEARCH_MILLIS = 180000L, MIN_ENGINE_GAP_MILLIS = 400L;
    /** 同一源被限流后整轮还允许的补试次数：实测 Semantic Scholar 匿名配额一进就 429。 */
    static final int MAX_THROTTLE_RETRIES = 1;
    /* 泳道上限：一条泳道一个线程、一条在飞的连接（单条响应体上限 2MB，四路并发就是 8MB 顶）。
       四路是"最慢的源再也拖不住其余源"与"手机上不要同时开十条连接"之间的取舍；真机上常用的
       中文三库（知网/万方/维普）正好全部并行，九个源全选时每道摊到两三个源。 */
    static final int MAX_LANES = 4;
    /* 回归夹具注入点：跑完必须还原，做法参照 TextCorpus.restoreThresholds()。
       真机上这两个值就是上面两个常量，测试把它们压小，免得回归白等几分钟。 */
    static long searchMillis = MAX_SEARCH_MILLIS, engineGapMillis = MIN_ENGINE_GAP_MILLIS;
    /* HttpTransport 把 429 写成"检索源限流…"再抛 IOException，PaperSources.search() 只往上抛
       IOException，所以这里只能按注记前缀认限流。这是权宜：0.6.1 让 ApiClient.Failure 带上状态码。 */
    static final String THROTTLED_PREFIX = "检索源限流";
    /** 候选清单「可比材料」列的三个取值，入库时逐条写死，报告里不许再现场猜。 */
    static final String MATERIAL_FULL = "全文", MATERIAL_ABSTRACT = "摘要", MATERIAL_RECORD = "仅题录";
    /**
     * 精要档：手机上把详情页渲染出来取回的那一段——万方那 1,500+ 字是网站自己从整篇里摘出来的
     * "全文精要"，比摘要厚、比正文薄。它不许并进"全文"那一档：一句撞进精要，只说明那句话被网站
     * 摘进去了，正文其余的部分根本没进过比对。
     */
    static final String MATERIAL_DIGEST = "精要";
    /** Retrieval gaps phrased with the note(...) vocabulary so the headline and the notes never disagree. */
    static final String GAP_NOTHING_RETRIEVED = "联网检索没有取回可比对的候选文献";
    static final String GAP_CANCELLED = "检索已取消，没有联网取候选文献";
    static final String GAP_NO_SOURCE = "没有可用的检索源，本次没有执行联网检索";
    static final String GAP_EMPTY_LIBRARY = "未启用联网检索，自建库为空，没有可比对的语料";
    /* 问到了条目不等于问到了可比正文（2.2.0）。真机最常落的正是这一档：知网、万方、维普只回摘要，
       摘要里没有被抄的那段正文，判据一句也认不出，报告却照印 0.00%、状态还是"完整检索"。
       这一条把那一档降级成未完成查重：判据与阈值一个字没改，改的是"没得比"不许冒充"没重复"。 */
    static final String GAP_NO_COMPARABLE = "没有任何可比正文，相似度量不到";

    /* 三种"量不到"各有各的下一步（真机实测 Huawei CDY-AN90 / Android 10，2026-10-09）：手机挂在自己
       那张网上时十个检索源全部拨不上，tools/device-probe.ps1 的 TCP 探针读数是 => 0 of 10 open；电脑上
       pwsh tools/phone-gateway.ps1 把 Clash 反代到 127.0.0.1:7897 之后，同一份检索代码知网/万方/维普
       各问回 3 条。"没有任何可比正文"对两种情况都说得通，对第一种却毫无用处——那一步修的是出口。 */
    static final String GAP_NO_ROUTE = "手机这次没能连上任何检索源";
    /**
     * 出口那一档的两条下一步，各救一种：手机自己的网，或者电脑上的那个反代脚本。
     * 命令与 tools/ 里真在的那个脚本一字不差。
     */
    static final String FIX_NO_ROUTE = "下一步：连上 WLAN 或移动数据再查一次；平时走电脑代理的手机插上 USB 线，"
            + "在电脑上运行 pwsh tools/phone-gateway.ps1 再查一次";
    /** 手机出得去、源也答了话，却一条条目都没问回来：挡人页、改版后的空响应与真零命中同一个形状。 */
    static final String GAP_SOURCE_SILENT = "答话的检索源一条条目都没给";
    /** 答话里带着 403/412 或挡人页——那是被拒绝回答，与"这一式真的零命中"要的两步相反。 */
    static final String GAP_SOURCE_BLOCKED = "答话的检索源回的是挡人页或拒绝访问（HTTP 403/412）";
    /* 三个以下主机同时拨不上不敢说"手机没有出口"：一个站挂了是那个站自己的事，三个以上同时拨不上
       才是这台机器出不去。真机那一轮是 10 个。 */
    static final int MIN_HOSTS_FOR_NO_ROUTE = 3;
    /* ---- 摘要层（近似）：可比正文 0 篇的那一轮，报告也要能给出一个能核对的数（2.2.x）----
       知网/万方/维普这个公开检索口给的是题名 + 摘要，正文段落与摘要在字符级判据上不是一个层级，
       所以正文级那三个比率在这一轮天然量不到（实测见 artifacts/agent-solver/ABSTRACT-LAYER.md）。
       摘要层给的是另一个数：分子分母都是句子数，名字里写死"近似"与"只到摘要"，
       它不并入总相似度比，也不与它相加，更不许顶替它。 */
    /** 这一层在报告里的名字。口径必须长在标题上，不能只写在脚注里。 */
    public static final String ABSTRACT_LAYER_LABEL = "摘要层（近似，知网/万方/维普公开检索只到摘要）";
    /** 逐句证据的条数上限：超出的按分数从高到低裁，最高那一句一定在表里。 */
    static final int MAX_ABSTRACT_EVIDENCE = 24;
    /** 摘要侧句子数的上限（大约 120 篇候选的摘要），把这一层的开销钉成常数倍。 */
    static final int MAX_ABSTRACT_UNITS = 4000;
    /** 每次检索请求的响应留档行数上限（A4）：MAX_REQUESTS 120 次加上万方换页的补帧，150 行装得下。 */
    static final int MAX_SHAPE_ROWS = 150;
    /** 「机器腔均分」那一栏的名字：它是一条 0-100 的分而不是占比，所以不许挂"比例"两个字。 */
    public static final String AIGC_SCORE_LABEL = "机器腔均分";
    private static final String AIGC_TIER_INSUFFICIENT = "样本不足";
    private static final String AIGC_NOT_RUN = "本机 AIGC 分析未执行";
    /**
     * 判据未标定那一格的说法。它必须自带方向，因为这一版错的方向是"冤枉真人"，
     * 只写"未标定"三个字会被读成"大概没问题，只是没测"。实测数字在 AigcScorer.UNCALIBRATED_NOTE 里。
     */
    static final String AIGC_UNCALIBRATED = "判据未标定（方向实测为反：真人句分比机器句分更高）";
    public interface Progress { void step(String label, int done, int total); }
    public static final class Report {
        /* 0.7.1 起这三个比率全部出自 ledger 那一次划分：分子分母同一把尺，自编率已减掉已判重复的字符，
           三个互斥桶闭合 100%。字段名与语义对下游（CheckReport、ApiWorkflow、TextCorpus、SourceLedger）不动。 */
        public double overallRate, excludingCitationsRate, selfWrittenRate, aigcRate;
        /** 这把尺子的原始账目：字符绝对量与闭合残差都在里面，报告里每个数都查得出处。 */
        public CharLedger.Balance ledger;
        public int comparedChars, duplicateChars, citedDuplicateChars;
        /** 按论文结构排除在比对之外的字数（参考文献表、致谢、附录、目录）。 */
        public int excludedChars;
        /** 上面那些字数对应的区间。来源榜按篇数字必须扣同一批区间，否则两边分子分母不是一把尺。 */
        public int[] structureSpans = new int[0];
        /**
         * 按"自建库 / 联网"两档分的重复字符。要把这两档分开报，只能从这里取数：
         * 自己按 hits 再数一遍就会把参考文献表那些字算进分子，114.49% 那种比率就是这么来的。
         * 两档相加 == duplicateChars == 总相似度比的分子。
         */
        public int localDuplicateChars, webDuplicateChars;
        /** 上面两档各自的比率：分母都是 comparedChars，与总相似度比同一把尺，上限 100%。 */
        public double localRate, webRate;
        /**
         * 上面那两档回答"从哪儿比来的"，这两档回答"拿什么档的材料比的"：撞上的时候对面那篇
         * 有没有正文可查。两档相加 == duplicateChars，但界面上不许相加成一个数读——
         * 摘要级那一档的覆盖面只有正文级的一小截（实测见 docs/material-tiers.md）。
         */
        public int fullTextDuplicateChars, digestDuplicateChars, abstractDuplicateChars;
        /** 各档有几篇命中，与上面那几个字数同源（SourceLedger 折叠前的全量篇数）。 */
        public int fullTextHitPapers, digestHitPapers, abstractHitPapers;
        /** 这次到底比了哪些库、各多少篇（含每库几篇只有摘要）：结果页与 HTML 都读它。 */
        public CorpusLedger inventory;
        /** 本次真正拿来比对的语料（自建库加检索到的候选），改写效果要用同一份基线。 */
        public TextCorpus baseline;
        public long elapsedMillis;
        public String detectedAt = "";
        public final ArrayList<TextCorpus.Hit> hits = new ArrayList<TextCorpus.Hit>();
        public final ArrayList<PaperSources.Candidate> candidates = new ArrayList<PaperSources.Candidate>();
        public final LinkedHashMap<String, Double> byEngine = new LinkedHashMap<String, Double>();
        public final LinkedHashMap<String, Integer> candidateCount = new LinkedHashMap<String, Integer>();
        public AigcDetector.Result aigc;
        /** AIGC 样本不足时，比例不该被当成结论。 */
        public boolean aigcInsufficient;
        public String aigcVerdict = "";
        public final ArrayList<String> notes = new ArrayList<String>();
        /** Scanned text so the report can cut snippets without re-opening the document. */
        public String sourceText = "";
        /** True when the run consulted nothing, so the rates must not read as "nothing is duplicated". */
        public boolean retrievalIncomplete;
        /** Why the run is unfinished, in the same wording as the notes; null while the run is complete. */
        public String retrievalReason;
        /* ---- 0.6.0 覆盖率披露：三态里的"部分完成"必须自带数字，不能只留一句道歉 ---- */
        /** 全文按 retrievable() 过滤后能切出的窗口组数，文档覆盖率的分母。 */
        public int windowsAvailable;
        /** 本轮排期排上的窗口组数：额度与每源上限之内，按覆盖字数从大到小选出来的。 */
        public int windowsPlanned;
        /** 真正发出过请求的窗口组数：同一窗口只要有任一源被问过就算。 */
        public int windowsRetrieved;
        /** 已检索窗口覆盖到的正文字数，口径 TextCorpus.validCount(normalize(text), from, to)。 */
        public int coveredChars;
        /** 可检索的正文总字数：retrievable() 通过的段落字数；结构性文本另计 excludedChars。 */
        public int comparableChars;
        /** 本轮排期打算发出去的提问次数（不超 MAX_REQUESTS）；一次提问一次请求。 */
        public int asksPlanned;
        /** 本轮可用的请求额度，与"每家最多问几扇"的上限（取自设置里的窗口数）。 */
        public int windowsBudget, perSourceWindowCap;
        /** 真问出去的提问次数，与排期打算发的次数排在一起看：撞了挂钟时前者小于后者。 */
        public int asksSent;
        /** 停问的源退回来的提问格子数，与其中真被别家补问掉的那些：报告里那句补问读这两个数。 */
        public int asksSpilled, asksRefilled;
        /** 真问出去的提问里，问得最浅与最深的一扇各问了几家：报告里"每窗问 2-3 家"读这两个数。 */
        public int windowDepthLow, windowDepthHigh;
        /** 排上的窗口里，有几扇至少问到过一家中文主库（知网/万方/维普）。 */
        public int chineseWindowsAsked;
        /** 全文切得出、本轮一次也没排上的窗口数；被谁挡的看下面两个布尔。 */
        public int windowsUnstaffed;
        /** 没排满是请求额度用尽（budgetCapped）还是每源上限用尽（capCapped）：两句话不一样。 */
        public boolean budgetCapped, capCapped;
        /** 每个检索源被提问的次数：一扇窗口最多两次（整窗一次，混主题那段单独一次）。HTML 的「提问次数」列读它。 */
        public final LinkedHashMap<String, Integer> windowsAsked = new LinkedHashMap<String, Integer>();
        /** 真正进了语料（摘要或全文非空）的候选数。 */
        public int comparableCandidates;
        /** 其中只有摘要可比的篇数。 */
        public int abstractOnlyCandidates;
        /** 其中抓到开放获取全文的篇数。 */
        public int fullTextCandidates;
        /** 只有题录、连摘要都没有、对匹配毫无贡献的篇数。 */
        public int recordOnlyCandidates;
        /** 与检索词没有任何共同词（BM25 得分为 0）、因此没让它进语料的候选数。 */
        public int unrankedCandidates;

        /* ---- 检索源连通情况：这一轮顺手记下的账。报告绝不为"手机有没有网"再多发一次请求 ---- */
        /** 本轮真问到过话的检索源个数（一个源算一个）：那句"10 个检索源里通了 N 个"的分母。 */
        public int hostsAsked;
        /** 其中答过话的个数：200/403/412/429 都算对面回了话。 */
        public int hostsReached;
        /** 每一次尝试都停在连接层（拨不上、超时、TLS 没谈成）的个数：与"站点拒绝回答"是两回事。 */
        public int hostsUnreachable;
        /** 跨源合并掉的篇数：同一篇在两个源各出现一次，只占一个比对名额。 */
        public int mergedDuplicates;
        /** 跨源合并的账目，报告里要能说出「为什么留下的是知网那条」。 */
        public final ArrayList<CandidateRanker.Merged> merges = new ArrayList<CandidateRanker.Merged>();
        /** true = 检索真做了但没跑完。与 retrievalIncomplete 互斥：这一态的比率仍然成立，只是是下限。 */
        public boolean retrievalPartial;
        /** 与注记同句的部分完成原因；完整检索为空串。 */
        public String retrievalPartialReason = "";
        /** 检索之前自建库里有几篇材料；检索回来的另计 comparableCandidates。 */
        public int localDocuments;
        /**
         * 有没有资格印一个百分比。三态只说了检索做没做，没说比对对象存不存在：
         * comparableCandidates=0 且自建库也空时，0.00% 不是"没有重复"，是"没得比"。
         */
        public boolean hasComparableEvidence() {
            return !hits.isEmpty() || comparableCandidates > 0 || localDocuments > 0;
        }
        /* ---- 摘要层（近似）：只有"摘要"可比的候选给得出这一层，正文级那三个比率与它不同尺 ---- */
        /** 分母：本文里够格参与摘要层比对的句子数（切得出句、不在引用与结构性文本里、够 MIN_SENTENCE_CHARS）。 */
        public int abstractSentencesCompared;
        /** 分子：这些句子里"逐句 max 袋 Dice"过线的句数。一句只数一次，与字数无关。 */
        public int abstractSentencesMatched;
        /** 分子/分母 x 100。分母为 0 时这一层算没量到，abstractLayerMeasured() 会把它挡在报告外。 */
        public double abstractLayerRate;
        /** 参与这一层的候选篇数（comparableMaterial == 摘要 的那些；抓到全文的那批走正文级，不进这里）。 */
        public int abstractCandidates;
        /** 摘要侧被切成多少个比较单元（句），以及是否撞到了 MAX_ABSTRACT_UNITS。 */
        public int abstractUnits;
        public boolean abstractLayerTruncated;
        /** 逐句证据：本文那一句、撞上的摘要那一句、来源题名，按正文顺序列。 */
        public final ArrayList<AbstractHit> abstractHits = new ArrayList<AbstractHit>();
        /** 每次检索请求的响应留档（A4）：逐源逐次，成功也留，一行一次请求（万方换页每页一行）。 */
        public final ArrayList<PaperSources.ShapeRow> shapes = new ArrayList<PaperSources.ShapeRow>();
        /**
         * 带着可下载 PDF 直链的候选：结果页给一条"下进自建库"的入口。
         * 自建库走的是与联网正文同一个判据（TextCorpus 那一次 match），所以这一条不是安慰奖。
         */
        public final ArrayList<Downloadable> downloadables = new ArrayList<Downloadable>();
        /** 留档被 MAX_SHAPE_ROWS 裁过的行数：档里没这一行才说明真的问了几次就是几行。 */
        public int shapesDropped;
    }

    /** 结果页"下进自建库"的一条：题名、哪个检索源给的、PDF 直链。 */
    public static final class Downloadable {
        public String title = "", engine = "", url = "";
        Downloadable(String title, String engine, String url) {
            this.title = title == null ? "" : title;
            this.engine = engine == null ? "" : engine;
            this.url = url == null ? "" : url;
        }
    }

    private DuplicateEngine() { }
    /**
     * 摘要层的一条证据：本文的哪一句，撞上了哪篇候选摘要里的哪一句。
     * 它不进 hits——hits 是正文级命中的账，混进同一张表就等于让摘要层的数冒充正文级的数。
     */
    public static final class AbstractHit {
        public int start, end;
        /** 这一句在所有摘要单元里拿到的最大袋 Dice。 */
        public double score;
        public String title = "", engine = "", abstractSentence = "";
    }

    /** 这一轮摘要层量没量到：一篇摘要候选都没有、或本文一句都不够长，就没有这一层，报告里也不许出现那个数。 */
    public static boolean abstractLayerMeasured(Report report) {
        return report != null && report.abstractSentencesCompared > 0;
    }

    /**
     * 摘要层那一行的唯一写法。名字里写死"近似"与"只到摘要"，数字带上分子分母与阈值，
     * 面板、HTML 报告、报告中心三处读这一句，不许各写一遍。
     */
    public static String abstractLayerLine(Report report) {
        if (!abstractLayerMeasured(report)) return "";
        return abstractLayerLine(report.abstractLayerRate, report.abstractSentencesMatched,
                report.abstractSentencesCompared, report.abstractCandidates);
    }

    /**
     * 同一串字的数字入口：报告中心拿的是存档记录（Record），不是刚跑完的 Report，
     * 但那一行必须长得一模一样，所以格式化只有这一份。
     */
    public static String abstractLayerLine(double rate, int matched, int compared, int candidates) {
        if (compared <= 0) return "";
        return percent(rate) + "（" + matched + "/" + compared + " 句，袋 Dice ≥ "
                + String.format(Locale.US, "%.2f", Double.valueOf(TextCorpus.bagFloor()))
                + "，摘要候选 " + candidates + " 篇）";
    }

    /** 这一层的口径声明：它不是什么，必须和它是什么写在同一屏。 */
    public static String abstractLayerCaveat(Report report) {
        if (!abstractLayerMeasured(report)) return "";
        return abstractLayerCaveat(report.abstractSentencesMatched, report.abstractSentencesCompared,
                report.abstractCandidates, report.abstractLayerTruncated);
    }

    /** 同上：数字入口，存档记录那一侧读的是同一份措辞。 */
    public static String abstractLayerCaveat(int matched, int compared, int candidates, boolean truncated) {
        if (compared <= 0) return "";
        StringBuilder out = new StringBuilder();
        out.append("口径：按句算，分母是本文参与比对的 ").append(compared)
                .append(" 句（引用段落与参考文献表已排除），分子是其中撞上摘要句的 ")
                .append(matched)
                .append(" 句。它不是正文级重复率：公开检索口只到摘要，这一层看不见正文，")
                .append("也不计入总相似度比，不与它相加。");
        if (truncated)
            out.append("摘要单元已达上限 ").append(MAX_ABSTRACT_UNITS).append(" 句，其余摘要未参与这一层。");
        return out.toString();
    }

    /**
     * "没有任何可比正文"那一屏必须跟着给出的下一步。写两句实话：全文要授权（软件解决不了），
     * 以及现在就能做的那一件（导入自建库，那是唯一能做出正文级比对的入口）。
     */
    public static String abstractLayerNextSteps() {
        return "下一步：正文级重复率需要全文，知网/万方/维普的公开检索口只到摘要、全文要授权；"
                + "手头有原文的疑似来源可以导进自建库再查一次，那一条路给得出正文级数字。";
    }

    /** 分档命中那一行的唯一写法：结果页、HTML、存档读同一句，两处各起一名就一定漂移。 */
    public static String materialSplitLine(Report report) {
        if (report == null) return "";
        return materialSplitLine(report.fullTextDuplicateChars, report.digestDuplicateChars,
                report.abstractDuplicateChars, report.comparedChars, report.fullTextHitPapers,
                report.digestHitPapers, report.abstractHitPapers);
    }

    /**
     * 同上，数字入口（存档记录那一侧读的是这一份）。各档之间那个"另有"是"几把尺并列"，
     * 不是"合成一个数"：正文级那部分能与总相似度比对照，精要级与摘要级都只能与摘要层对照。
     * 一处命中都没有时返回空串，界面连那一行都不画。
     */
    public static String materialSplitLine(int fullChars, int digestChars, int abstractChars,
                                           int comparedChars, int fullPapers, int digestPapers,
                                           int abstractPapers) {
        StringBuilder out = new StringBuilder();
        if (fullChars > 0) appendTier(out, "正文级", fullChars, comparedChars, fullPapers, "篇有正文可比");
        if (digestChars > 0)
            appendTier(out, "精要级", digestChars, comparedChars, digestPapers,
                    "篇只有精要可读，那是网站从整篇里自己摘的一段");
        if (abstractChars > 0)
            appendTier(out, "摘要级", abstractChars, comparedChars, abstractPapers,
                    "篇只有摘要可比，正文没比过");
        if (out.length() == 0) return "";
        if (fullChars <= 0) out.insert(0, "命中不含正文级：");
        return out.toString();
    }

    /** 一档的写法：档名 + 字数 + 用整篇分母算出的比率 + 几篇，以及那一档到底比的是什么。 */
    private static void appendTier(StringBuilder out, String name, int chars, int comparedChars,
                                   int papers, String papersNote) {
        out.append(out.length() > 0 ? "，另有" : "").append(name).append(' ').append(chars).append(" 字 = ")
                .append(percent(SourceLedger.rate(chars, comparedChars))).append("（")
                .append(papers).append(" ").append(papersNote).append("）");
    }

    /** 比对材料清单那一行的唯一写法；空语料返回空串，界面连那一行都不画。 */
    public static String inventoryLine(Report report) {
        return report == null || report.inventory == null ? "" : report.inventory.summaryLine();
    }

    /** 摘要层的注记：报告中心与面板的注记列表都要能查到这一层做了、做了什么口径。 */
    static String abstractLayerNote(Report report) {
        return ABSTRACT_LAYER_LABEL + "：" + report.abstractSentencesMatched + "/"
                + report.abstractSentencesCompared + " 句 = " + percent(report.abstractLayerRate)
                + "（摘要候选 " + report.abstractCandidates + " 篇）";
    }

    public static Report scan(TextSelection selection, TextCorpus corpus, boolean useWeb, ArrayList<String> engines,
                              PaperSources.Limits limits, ApiClient.Cancellation cancellation, Progress progress) {
        long started = System.nanoTime();
        Report report = new Report();
        String text = selection == null || selection.text == null ? "" : selection.text;
        report.sourceText = text;
        TextCorpus library = corpus == null ? new TextCorpus() : corpus;
        report.baseline = library;
        /* 检索之前语料侧有几篇，是"有没有得比"的另一半：只数 comparableCandidates 会把离线只跟
           自建库比的那种正常场合误判成没得比。 */
        report.localDocuments = library.sourceCount();
        PaperSources.Limits safe = limits == null ? new PaperSources.Limits() : limits;
        ArrayList<String> wanted = new ArrayList<String>();
        boolean aborted = false;
        try {
            wanted.addAll(pick(engines, report));
            int[] spans = citationSpans(selection, null);
            if (spans.length >= 2) note(report, "已标出 " + (spans.length / 2) + " 处引用段落，这部分只计入总相似度比");
            if (!useWeb) note(report, "未启用联网检索，只与自建库比对");
            else if (cancelled(cancellation)) note(report, GAP_CANCELLED);
            else search(text, library, report, wanted, safe, cancellation, progress);
            if (library.isEmpty()) note(report, "自建库为空，比对基线只有检索到的候选文献摘要");
            TextCorpus.Report matched = null;
            TextCorpus.Structure structure = TextCorpus.structure(text);
            if (cancelled(cancellation)) note(report, "检测到取消，未执行语料比对");
            else {
                step(progress, "比对语料", 2, 4);
                matched = library.match(text, spans, structure.spanArray());
                // 来源榜里出现 0 处命中的那一篇，多半是输在了"同一段字符只记给命中更长的那一篇"，
                // 不把这件事说出来，用户会把那个 0 读成"这篇没抄"。
                if (library.disputedChars() > 0)
                    note(report, "有 " + library.disputedChars() + " 个字符同时被两篇以上文献命中，"
                            + "只记给了命中更长的那一篇");
                if (!structure.isEmpty())
                    note(report, "已排除结构性文本 " + structure.excludedChars + " 字：参考文献表 "
                            + structure.bibliographySections + " 节共 " + structure.citationLines
                            + " 条，致谢/附录/目录等 " + structure.otherSections + " 节；这部分不计入相似率");
            }
            // 语料到此定型（检索回来的候选已入库、比对也做完了），清单再数，篇数才对得上比的那一份。
            report.inventory = CorpusLedger.aggregate(library);
            if (cancelled(cancellation)) note(report, "检测到取消，未执行 AIGC 倾向分析");
            else {
                step(progress, "AIGC 倾向分析", 3, 4);
                int[] quoted = TextCorpus.mergeSpans(concat(spans, structure.spanArray()), text.length());
                report.aigc = AigcDetector.detect(text, quoted);
                if (report.aigc.excludedChars > 0)
                    note(report, "AIGC 分析跳过引用与结构性文本 " + report.aigc.excludedChars + " 字");
            }
            rates(report, matched, spans, structure.spanArray());
            /* 摘要层（近似）排在正文级比对之后：它说的是"摘要这一档能比到什么"，
               只有正文级那一轮真的比过（或确定比不成）才轮到它开口。被取消的那一轮不补这一层。 */
            if (!cancelled(cancellation))
                abstractLayer(report, text,
                        TextCorpus.mergeSpans(concat(spans, structure.spanArray()), text.length()));
            step(progress, "汇总报告", 4, 4);
        } catch (RuntimeException error) {
            note(report, "检测中断：" + message(error));
            aborted = true;
        }
        markRetrievalGap(report, useWeb, wanted, library, cancellation, aborted);
        report.elapsedMillis = (System.nanoTime() - started) / 1000000;
        report.detectedAt = ReviewManager.now();
        return report;
    }

    /** Nothing consulted must never read as nothing duplicated: name the gap so the report can say so. */
    private static void markRetrievalGap(Report report, boolean useWeb, ArrayList<String> engines,
                                         TextCorpus corpus, ApiClient.Cancellation cancellation, boolean aborted) {
        if (report.retrievalIncomplete) return;
        if (!useWeb) {
            if (corpus.isEmpty()) gap(report, GAP_EMPTY_LIBRARY);
            return;
        }
        if (engines.isEmpty()) { gap(report, GAP_NO_SOURCE); return; }
        /* 形状一（手机没有出口）排在可比正文之前：可比正文 0 篇也是真的，但这一屏真正要回答的是
           "手机这会儿出不出得去"。拿可比正文的措辞去说没有出口，用户就会去调检索窗口数——那一步没用。 */
        if (!cancelled(cancellation) && noNetworkExit(report)) {
            gap(report, noNetworkExitReason(report, engines.size()));
            return;
        }
        /* 可比正文这一档排在"有没有取回候选"之前判：取回 36 条而 34 条只有题录、2 条与检索词零共同词，
           等于一篇可比正文都没进来，这一轮不许算"完整检索 + 0.00%"。取消另有一句话，让给它。 */
        if (!report.hasComparableEvidence() && !cancelled(cancellation)) {
            gap(report, noComparableMaterial(report, true));
            return;
        }
        if (!report.candidates.isEmpty()) return;
        if (cancelled(cancellation)) gap(report, GAP_CANCELLED);
        else if (aborted) gap(report, GAP_NOTHING_RETRIEVED);
    }
    /**
     * 为什么这一轮的相似度量不到，写成一句能进报告的话。
     *
     * 措辞里必须带着数字与下一步：只说"未完成"等于把球踢回给用户，而这一幕的成因
     * （只回摘要、窗口太少、自建库空）是检索设置里就能改的。
     */
    static String noComparableMaterial(Report report, boolean useWeb) {
        if (!useWeb)
            return "自建库有 " + report.localDocuments + " 篇材料，但没有一句能用于比对，"
                    + GAP_NO_COMPARABLE + "：导入了原文还是量不到，多半是那些文件读不出正文";
        StringBuilder reason = new StringBuilder();
        reason.append("联网检索取回 ").append(report.candidates.size()).append(" 条候选，可比正文 ")
                .append(report.comparableCandidates).append(" 篇")
                .append("（只有题录 ").append(report.recordOnlyCandidates).append(" 篇，与检索词零共同词 ")
                .append(report.unrankedCandidates).append(" 篇）");
        /* 通了几个源不进这一句：这一句的措辞是 2.2.0 定下来的，"可比正文 0 篇"那几档成因共用它，
           在这里插一句等于把历史报告的说法刷新掉。那个数排在注记与报告的指标表里说。 */
        /* 形状二：手机出得去、源也答了话，只是什么都没问回来。它的下一步既不是修出口，也不是放宽
           全文抓取，而是换一个问法，所以这一句得自己站得住，不能只留"可比正文 0 篇"。 */
        if (report.candidates.isEmpty() && report.unrankedCandidates == 0 && report.hostsReached > 0)
            reason.append('，').append(blockedSources(report) > 0 ? GAP_SOURCE_BLOCKED : GAP_SOURCE_SILENT);
        if (report.windowsRetrieved < report.windowsPlanned)
            reason.append("，检索也只跑了 ").append(report.windowsRetrieved)
                    .append("/").append(report.windowsPlanned).append(" 个窗口");
        reason.append("，").append(GAP_NO_COMPARABLE)
                .append("：把检索设置的窗口数调大、允许开放获取全文抓取，"
                        + "或先把疑似来源的原文导入自建库再查一次");
        return reason.toString();
    }

    /**
     * 那句"10 个检索源里通了 3 个"。通了几个是形状一与形状二的分界：全部没建成是手机出不去，
     * 通了几个却什么都没问回来是站点在挡人。数字出自泳道自己记下的那一笔（Lane.answered /
     * Lane.deadRoute），报告不为此多发一次请求。
     */
    public static String hostTallyLine(Report report) {
        if (report == null || report.hostsAsked <= 0) return "";
        StringBuilder out = new StringBuilder();
        out.append(report.hostsAsked).append(" 个检索源里通了 ").append(report.hostsReached).append(" 个");
        if (report.hostsReached == 0) out.append("，连接全部没建成");
        else if (report.hostsUnreachable > 0)
            out.append("，其余 ").append(report.hostsUnreachable).append(" 个连接没建成");
        return out.toString();
    }
    /** 这一轮是不是"手机没有网络出口"那一档：好几个源同时停在连接层，才敢这么说。 */
    static boolean noNetworkExit(Report report) {
        return report != null && report.hostsAsked >= MIN_HOSTS_FOR_NO_ROUTE && report.hostsReached == 0
                && report.hostsUnreachable >= report.hostsAsked;
    }
    /**
     * 形状一的那一句。第一行是原因，数字跟着原因走：通了几个与拨不上几个是同一件事的两头。
     * 换行之后才是两条出路：两段都在解释同一片空白，排成一段就没人读第二段。
     * 老那句"没有任何可比正文"在这一档丢掉——量不到材料是结果，不是这一档的原因。
     */
    static String noNetworkExitReason(Report report, int engines) {
        int asked = report.hostsAsked > 0 ? report.hostsAsked : Math.max(0, engines);
        return GAP_NO_ROUTE + "：" + asked + " 个检索源里通了 " + report.hostsReached + " 个，"
                + "对面一个字都没答话，不是它们拒绝回答。\n" + FIX_NO_ROUTE;
    }

    /**
     * "全部不可用"那一句。手机没出口那一档另有一句话；留在这一支的轮次必须自己说清通了几个、
     * 是不是被挡人页挡回来的——不然它与形状一只差一个数，读报告的人看不出来。
     */
    static String everyConnectorFailedReason(Report report, int engines) {
        int asked = report.hostsAsked > 0 ? report.hostsAsked : Math.max(0, engines);
        StringBuilder reason = new StringBuilder();
        reason.append(asked).append(" 个检索源本次全部不可用，").append(GAP_NOTHING_RETRIEVED);
        if (report.hostsReached > 0) {
            String tally = hostTallyLine(report);
            if (!tally.isEmpty()) reason.append("：").append(tally);
            reason.append("，").append(blockedSources(report) > 0 ? GAP_SOURCE_BLOCKED : GAP_SOURCE_SILENT);
        }
        return reason.toString();
    }
    /** 答过话却在挡人的那几个源：403/412，或留档里已经标成 blocked / js-shell 的那几页。 */
    static int blockedSources(Report report) {
        if (report == null) return 0;
        ArrayList<String> named = new ArrayList<String>();
        for (PaperSources.ShapeRow row : report.shapes) {
            if (row == null) continue;
            boolean blocked = row.status == 403 || row.status == 412
                    || row.shape != null && (row.shape.contains("blocked") || row.shape.contains("js-shell"));
            if (blocked && !named.contains(row.engine)) named.add(row.engine);
        }
        return named.size();
    }
    /**
     * 一次失败落在哪一头：正数 = 对面答了话（403/412/429/5xx 都是答了话），负数 = 连接本身没建成，
     * 零 = 说不上（取消、地址无效这一类，两边都不记）。判据认的是 HttpTransport 那句原话的前缀——
     * 检索侧只往上抛 IOException，状态码只有在这一层还认得回来。
     */
    static int reachOf(Throwable error) {
        if (!(error instanceof ApiClient.Failure)) return 1;   // 能走到解析那一步，响应已经回来了
        ApiClient.Failure failure = (ApiClient.Failure) error;
        if (failure.status > 0) return 1;
        String message = String.valueOf(failure.getMessage());
        if (message.startsWith("已取消")) return 0;
        return message.startsWith("网络连接失败") || message.startsWith("安全连接失败")
                || message.startsWith("请求超时") || message.startsWith("试过的路都没通") ? -1 : 0;
    }
    /** The first named gap wins, so the sharpest reason is the one search() found. */
    private static void gap(Report report, String reason) {
        if (report.retrievalIncomplete) return;
        report.retrievalIncomplete = true;
        report.retrievalReason = reason;
    }

    /**
     * 三个比率一律走 {@link CharLedger}（0.7.1）：分子分母同一把尺，自编率是"减掉已判重复字符之后剩下的"，
     * 未标引用的重复 + 引用区间内的重复 + 自编三个桶闭合 100%。
     *
     * 旧写法有两处对不上：分母用的是 matched.comparedChars（只有切得出比对片段的句子才算数），自编率还从
     * 100 里再减一次凭 sentence.score >= 0.5f 现场圈的 AIGC 句占比。0.5 这把尺检测侧不认（区间门槛是
     * AigcDetector.SEGMENT_FLAG_GATE = 0.45），而且"抄来的"与"机器写的"是两条轴，同一批字可以两边都占，
     * 放进一个减法里就是互相冲抵。现在机器腔单独记在 ledger.machineChars，谁也不减谁。
     * 三个比率字段的名字与语义对下游不动，改的是算法；byEngine 仍是 TextCorpus 自己那本归属账。
     */
    private static void rates(Report report, TextCorpus.Report matched, int[] citationSpans, int[] structureSpans) {
        if (matched != null) {
            report.hits.addAll(matched.hits);
            for (Map.Entry<String, Double> entry : matched.byEngine.entrySet())
                report.byEngine.put(entry.getKey(), clamp(entry.getValue() == null ? 0 : entry.getValue().doubleValue()));
        }
        if (report.aigc != null) {
            report.aigcRate = clamp(report.aigc.rate);
            report.aigcInsufficient = report.aigc.insufficientSample;
            report.aigcVerdict = report.aigc.verdict == null ? "" : report.aigc.verdict;
        }
        CharLedger.Balance balance = CharLedger.close(report.sourceText, structureSpans, citationSpans,
                report.hits, report.aigc);
        report.ledger = balance;
        report.comparedChars = balance.totalChars;
        report.duplicateChars = balance.duplicateChars;
        report.citedDuplicateChars = balance.citedDuplicateChars;
        report.excludedChars = balance.excludedChars;
        report.structureSpans = structureSpans == null ? new int[0] : structureSpans.clone();
        report.overallRate = balance.overallRate;
        report.excludingCitationsRate = balance.excludingCitationsRate;
        report.selfWrittenRate = balance.selfWrittenRate;
        /* 来源榜与整篇比率当场对一次账，并把两档归属算好交给界面。
           分子多出来的字数不悄悄夹掉——注记里点名，结果页、报告、存档都会带上这一句。 */
        SourceLedger byPaper = SourceLedger.aggregate(report.hits,
                TextCorpus.normalize(report.sourceText), report.comparedChars, structureSpans);
        report.localDuplicateChars = byPaper.localDuplicateChars();
        report.webDuplicateChars = byPaper.webDuplicateChars();
        report.localRate = SourceLedger.rate(report.localDuplicateChars, report.comparedChars);
        report.webRate = SourceLedger.rate(report.webDuplicateChars, report.comparedChars);
        report.fullTextDuplicateChars = byPaper.fullDuplicateChars;
        report.digestDuplicateChars = byPaper.digestDuplicateChars;
        report.abstractDuplicateChars = byPaper.abstractDuplicateChars;
        report.fullTextHitPapers = byPaper.fullPapers;
        report.digestHitPapers = byPaper.digestPapers;
        report.abstractHitPapers = byPaper.abstractPapers;
        /* 摘要级命中必须单独说一句：只拿摘要比过的那些篇，撞上一句只说明那句话在摘要里，
           不说明整篇比过。来源榜已把它们排在正文级那几篇后面，这一句负责把话说明白。 */
        if (byPaper.abstractDuplicateChars > 0)
            note(report, "撞上的材料里有 " + byPaper.abstractPapers + " 篇只有摘要可比（"
                    + byPaper.abstractDuplicateChars + " 字）：这部分只说明那句原话在那几篇的摘要里，"
                    + "正文没有比过，所以不与正文级那 " + byPaper.fullDuplicateChars + " 字混成一个数");
        if (byPaper.digestDuplicateChars > 0)
            note(report, "还有 " + byPaper.digestPapers + " 篇只有精要可读（"
                    + byPaper.digestDuplicateChars + " 字）：那是手机上把详情页渲染出来后取回的"
                    + "「全文精要」，网站从整篇里自己摘的一段，不是整篇正文，也不与上面两档混成一个数");
        if (byPaper.numeratorOverflowChars > 0)
            note(report, "来源榜各行相加比整篇可比字数多 " + byPaper.numeratorOverflowChars
                    + " 字：那些字落在参考文献表/致谢这类结构性文本里，不参与比率；"
                    + "每一行的该篇重复率已按 100% 上限显示");
    }

    /** AIGC 五档的人话名字。指标区与结果面板共用这一份，两处各起一名就一定漂移。 */
    public static String aigcTierName(AigcDetector.Tier tier) {
        if (tier == null) return AIGC_TIER_INSUFFICIENT;
        switch (tier) {
            case STRONG: return "成段";
            case NEEDS_REVIEW: return "复核";
            case WATCH: return "观察";
            case NONE: return "一般";
            default: return AIGC_TIER_INSUFFICIENT;
        }
    }

    /** 那一格的全文字数：账本在就用账本。账本只在手工拼的报告里缺席，那种场合退到比对侧自己数的字数。 */
    public static int trendTotalChars(Report report) {
        if (report == null) return 0;
        return report.ledger == null ? report.comparedChars : report.ledger.totalChars;
    }

    /** 那一格的可疑字数：账本按 CharLedger.FLAG_SCORE_GATE 重数过一遍，与全文同一个尺子。 */
    public static int trendMachineChars(Report report) {
        if (report == null) return 0;
        if (report.ledger != null) return report.ledger.machineChars;
        return report.aigc == null ? 0 : report.aigc.flaggedChars;
    }

    /** 样本不足、或者这一轮压根没跑 AIGC：都不许印出任何看起来像结论的数。 */
    public static boolean aigcUnmeasured(Report report) {
        if (report == null || report.aigc == null) return true;
        // 判据方向没验正过，和"没跑这一项"是同一档：量出来的数不可比，就不许印出去。
        return !AigcScorer.calibrated() || report.aigcInsufficient || report.aigc.insufficientSample;
    }

    /** 未量到的三种说法之一：没跑 / 样本不足 / 判据未标定。面板、HTML、存档都读这一个出处。 */
    public static String aigcUnmeasuredReason(Report report) {
        if (report == null || report.aigc == null) return AIGC_NOT_RUN;
        if (report.aigcInsufficient || report.aigc.insufficientSample)
            return AIGC_TIER_INSUFFICIENT + "（有效字符 " + report.aigc.comparedChars + " 字，门槛 "
                    + AigcDetector.MIN_DOCUMENT_CHARS + " 字）";
        return AIGC_UNCALIBRATED;
    }

    /**
     * "机器生成倾向"那一格：档位 + 可疑字数 + 全文字数，三个都是绝对量，一个百分号都没有。
     * 0.7.0 之前这一格印的是字符加权句分、还挂"AIGC 生成比例"的名字，与同表的总相似度比撞名——
     * 一份报告里不能有两个都叫比例的数，用户挑不出哪个是错的。
     */
    public static String aigcTrend(Report report) {
        if (aigcUnmeasured(report)) return aigcUnmeasuredReason(report);
        return aigcTierName(report.aigc.tier) + "（可疑 " + trendMachineChars(report) + " 字 / 全文 "
                + trendTotalChars(report) + " 字）";
    }

    /**
     * 字符加权句分（Σ 句分 x 有效字符 / Σ 有效字符）留档但不冒充比例：值不带百分号、名字带"均分"、
     * 同一格里写死"非占比"。样本不足时给空串，这一格宁可不画。
     */
    public static String aigcScoreLine(Report report) {
        // 未标定与样本不足都走这一条：整格不画。这一位是 AigcScorer.calibrated() 的唯一对外出口之一，
        // 想在报告里看到一个句分，先把 VERSION 与方向一起改对（见 AigcScorer#calibrated）。
        if (report == null || aigcUnmeasured(report)) return "";
        return String.format(java.util.Locale.US, "%.1f", Double.valueOf(clamp(report.aigcRate)))
                + "（字符加权句分，非占比）";
    }
    /** 两段成对区间接在一起，交给 mergeSpans 合并。 */
    static int[] concat(int[] first, int[] second) {
        int a = first == null ? 0 : first.length;
        int b = second == null ? 0 : second.length;
        int[] out = new int[a + b];
        System.arraycopy(first, 0, out, 0, a);
        if (b > 0) System.arraycopy(second, 0, out, a, b);
        return out;
    }

    /** 改写前后的相似率对照，必须用同一份比对基线，否则两个数字没有可比性。 */
    public static final class RewriteDelta {
        public double beforeRate, afterRate;
        public int beforeCompared, afterCompared, beforeDuplicate, afterDuplicate;
        public boolean measured;
        public String reason = "";
        public double delta() { return beforeRate - afterRate; }
        public String verdict = "";
    }

    public static RewriteDelta compareRewrite(String before, String after, TextCorpus corpus) {
        RewriteDelta delta = new RewriteDelta();
        if (corpus == null || corpus.isEmpty() || before == null || after == null) {
            delta.reason = "没有可比对的基线语料";
            delta.verdict = "先做一次查重，才知道改写有没有用";
            return delta;
        }
        TextCorpus.Report a = corpus.match(before, null, TextCorpus.structure(before).spanArray());
        TextCorpus.Report b = corpus.match(after, null, TextCorpus.structure(after).spanArray());
        delta.beforeRate = clamp(a.overallRate);
        delta.afterRate = clamp(b.overallRate);
        delta.beforeCompared = a.comparedChars;
        delta.afterCompared = b.comparedChars;
        delta.beforeDuplicate = a.duplicateChars;
        delta.afterDuplicate = b.duplicateChars;
        delta.measured = a.comparedChars > 0 && b.comparedChars > 0;
        double shift = delta.delta();
        if (!delta.measured) delta.verdict = "可比对字数不足，衡量不出改写效果";
        else if (a.duplicateChars == 0 && b.duplicateChars == 0) delta.verdict = "两边都没有重复，衡量不出改写效果";
        else if (Math.abs(shift) < 0.5d) delta.verdict = "改写后相似率基本没动（" + percent(delta.beforeRate)
                + " → " + percent(delta.afterRate) + "），这一轮改写没有真的降重";
        else if (shift > 0d) delta.verdict = "改写后相似率下降 " + percent(shift) + "（" + percent(delta.beforeRate)
                + " → " + percent(delta.afterRate) + "）";
        else delta.verdict = "改写后相似率反而升高 " + percent(-shift) + "（" + percent(delta.beforeRate)
                + " → " + percent(delta.afterRate) + "），检查改写是不是把句子改得更像原文";
        return delta;
    }

    static String percent(double value) {
        double safe = Double.isNaN(value) || Double.isInfinite(value) ? 0d : value;
        return String.format(java.util.Locale.US, "%.2f%%", safe < 0d ? 0d : safe > 100d ? 100d : safe);
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0;
        return value < 0 ? 0 : value > 100 ? 100 : value;
    }

    private static ArrayList<String> pick(ArrayList<String> requested, Report report) {
        ArrayList<String> known = PaperSources.engines();
        ArrayList<String> out = new ArrayList<String>();
        if (requested == null || requested.isEmpty()) return known;
        for (String engine : requested) {
            String name = engine == null ? "" : engine.trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) continue;
            if (name.equals("semantic scholar") || name.equals("semanticscholar")) name = "semantic-scholar";
            if (name.equals("europe-pmc") || name.equals("pmc")) name = "europepmc";
            if (!known.contains(name)) { note(report, "未知检索源 " + name + " 已忽略"); continue; }
            if (!out.contains(name)) out.add(name);
        }
        if (out.isEmpty()) { note(report, "没有可用的检索源，只与自建库比对"); return new ArrayList<String>(); }
        return out;
    }

    /**
     * 两阶段检索：先把「窗口 x 源」的候选全收进池子，收完再按 BM25 名次决定谁进语料、谁吃全文额度。
     *
     * 为什么必须拆两阶段：收一条入一条等于谁先回来谁占满入库名额与全文额度，第一个窗口回来的
     * 套话会把后面窗口里真正对题的那篇挤到队尾。窗口在外、引擎在内也不是随手定的顺序——预算被砍时
     * 牺牲的必须是尾部窗口（覆盖宽度均匀），而不是尾部源（那会让某个库整轮零命中，
     * 等于偷偷换成"只有先答的库算数"）。
     */
    /**
     * 一扇窗口的混主题补问式排在第几层：前两层先按整窗检索式问两家，第三层才补问那条例式。
     *
     * 排期永远是先铺宽再铺深（每一层给所有已排上的窗口各补一次），所以这一层的位置决定的是
     * "补问式排在第三次提问之前还是之后"。docs/retrieval-recall.md 里 0/5 到 2/5 那笔账说明
     * 补问式比"同一扇多问一家"值钱，所以它排在第 2 层后面、第 3 层前面，而不是排到最后。
     */
    static final int ORPHAN_PROBE_LAYER = 2;
    /* 额度分配的两个旋钮，同样跑完必须还原：orphanLayer 是那条混主题补问式排在第几层，
       windowsByChars 是窗口按"这一扇里有多少中文字"排还是按正文顺序排。量台
       tools/coverage-budget-probe.ps1 用 windowsByChars 在同一条 allocate() 路径上量两种排法的差距，
       用 --engines= 把额度压小好把排法的差量出来。 */
    static int orphanLayer = ORPHAN_PROBE_LAYER;
    static boolean windowsByChars = true;
    /** 中文稿最常抄的三家。每扇窗口的第一个名额优先留给它们（本篇勾了才算）。 */
    static final String[] CHINESE_ENGINES = { "cnki", "cqvip", "wanfang" };

    /** 一次提问：本轮排期里的第几扇窗口、这一扇的第几条检索式、问哪一家。 */
    static final class Ask {
        final int window, probe; final String engine;
        Ask(int window, int probe, String engine) {
            this.window = window; this.probe = probe; this.engine = engine;
        }
    }

    /**
     * 窗口的提问顺序：先比"这一扇里有多少中文字"，其次比"这一扇一共多少字"，最后按正文顺序。
     *
     * <p>第一把尺不用总字数，是这篇样稿量出来的：按总字数排，最长的几扇里排在第二与第五的是两条参考文献条目（705 字与 667 字，中文字数都是 0）。每家只被问两扇的时候钱全花在英文书目上：回环桩实测 12 条英文题录条条进得了语料（与检索式零共同词那一档从 11 篇变成 0 篇），而正文里真该问的段落一扇也没问到。纯英文的稿子不受影响：它的中文字数全零，退回复比总字数，与之前同一条顺序。</p>
     */
    static ArrayList<Integer> windowOrder(ArrayList<Integer> chinese, ArrayList<Integer> chars) {
        int total = chars == null ? 0 : chars.size();
        ArrayList<Integer> order = new ArrayList<Integer>();
        for (int w = 0; w < total; w++) order.add(Integer.valueOf(w));
        final ArrayList<Integer> cjk = chinese != null && chinese.size() == total ? chinese : null;
        Collections.sort(order, new Comparator<Integer>() {
            public int compare(Integer left, Integer right) {
                if (cjk != null) {
                    int byCjk = cjk.get(right.intValue()).intValue() - cjk.get(left.intValue()).intValue();
                    if (byCjk != 0) return byCjk;
                }
                int by = chars.get(right.intValue()).intValue() - chars.get(left.intValue()).intValue();
                return by != 0 ? by : left.intValue() - right.intValue();
            }
        });
        return order;
    }

    /** allocate() 的结果：本轮的提问排期，外加没排上的窗口有几个、被哪一道闸挡的。 */
    static final class Allocation {
        final ArrayList<Ask> asks = new ArrayList<Ask>();
        /** 排进本轮的窗口数（按窗口去重，不是提问次数）。 */
        int windowsPlanned;
        /** 排上的窗口里问得最浅与最深各几家：报告里"每窗问 2-3 家"读这两个数。 */
        int depthLow, depthHigh;
        /** 排上的窗口里，有几扇至少问到过一家中文主库。 */
        int chineseWindows;
        /** 全文切得出、本轮一次也没排上的窗口数。 */
        int unstaffed;
        /** 挡下剩余窗口的是哪一道闸：120 次额度用尽，还是每源上限（设置里的窗口数）用尽。 */
        boolean cappedByRequests, cappedByCap;
    }

    /** 这一家的材料是中文稿最可能抄的那三家之一吗。 */
    static boolean chineseEngine(String engine) {
        for (int i = 0; i < CHINESE_ENGINES.length; i++) if (CHINESE_ENGINES[i].equals(engine)) return true;
        return false;
    }

    /**
     * 额度怎么花：先决定问哪几扇窗口，再决定每一扇问哪几家。
     *
     * 以前没有这一层，走法是"窗口按正文顺序一扇一扇过，每扇把勾选的源挨个问一遍"，撞到 120 次或
     * 180 秒才停。九个源全选、每扇又要为混主题那段补问一次时，一扇窗口吃掉 18 次，于是真机那轮
     * 只走到 8/49 扇就整轮停摆，报告落到"可比正文 0 篇"，屏幕上就是一个 0%。这一层把顺序倒过来：
     * 一、窗口按"这一扇里有多少中文字"从大到小排——"1 引言"那种几个字的段落不配和整段结论抢同一份额度，
     *     排序用的字数与报告分母用的是同一把尺（TextCorpus.validCount）；
     * 二、第 0 层名额先给中文主库，排到它们的每源上限见底为止；
     * 三、往后每一层给所有已排上的窗口各补一次，宽一层永远排在深一层前面：额度只够每扇一次时，
     *     它宁可让 108 扇各问一次，也不肯让 54 扇各问两次——报告的分母是"这一篇有多少字进过比对"，
     *     砍窗口数就是直接砍那个数；混主题那条补问式排在 orphanLayer 那一层
     *     （docs/retrieval-recall.md 里 0/5 到 2/5 那笔账靠的就是它），排在同一扇的第三次提问之前，
     *     但永远不许插到还没被问过的新窗口前面。
     * 每家最多被问 limits.windows 扇——设置里那个数管的就是这个，所以把它调大是"每家多问几扇"，
     * 不是"整轮只看前几扇"。
     */
    static Allocation allocate(ArrayList<Integer> chinese, ArrayList<Integer> chars, ArrayList<Integer> probes,
                               ArrayList<String> engines, int perSourceCap, int budget, int orphanAt) {
        Allocation out = new Allocation();
        int total = chars.size();
        out.unstaffed = total;
        if (total == 0 || engines.isEmpty() || budget <= 0) return out;
        ArrayList<Integer> order = new ArrayList<Integer>();
        for (int w = 0; w < total; w++) order.add(Integer.valueOf(w));
        if (windowsByChars) order = windowOrder(chinese, chars);
        int cap = Math.max(1, perSourceCap);
        /* 补问式最早也只能排在第二层：第一层管的是"这一扇到底有没有被问过"，抢在它前面是白送。 */
        int orphanAfter = Math.max(1, Math.min(orphanAt, engines.size()));
        LinkedHashMap<String, Integer> spare = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < engines.size(); i++) spare.put(engines.get(i), Integer.valueOf(cap));
        ArrayList<boolean[]> used = new ArrayList<boolean[]>();
        ArrayList<Integer> depth = new ArrayList<Integer>();
        for (int w = 0; w < total; w++) {
            used.add(new boolean[slotCeiling(probes.get(w).intValue(), engines.size()) * engines.size()]);
            depth.add(Integer.valueOf(0));
        }
        int left = budget, layer = 0;
        while (left > 0) {
            boolean added = false;
            for (int i = 0; i < order.size() && left > 0; i++) {
                int w = order.get(i).intValue();
                int probe = slotProbe(probes.get(w).intValue(), layer, orphanAfter, engines.size());
                if (probe < 0) continue;
                /* 补问式不占每源名额，所以名额花光的这家也能被排上；整窗式才要求还有名额。 */
                String engine = pickEngine(engines, spare, used.get(w), probe, layer == 0, probe == 0);
                if (engine == null) continue;
                int slot = engines.indexOf(engine);
                /* 每源上限管的是“这家最多问几扇”，不是“最多发几次”：同一扇上的第二条
                   检索式（混主题那条补问式）不再扣一扇名额，否则设置里写 6 就等于把补问挤掉。 */
                boolean newWindowForEngine = !coversWindow(used.get(w), engines.size(), slot);
                used.get(w)[probe * engines.size() + slot] = true;
                if (newWindowForEngine)
                    spare.put(engine, Integer.valueOf(spare.get(engine).intValue() - 1));
                depth.set(w, Integer.valueOf(depth.get(w).intValue() + 1));
                out.asks.add(new Ask(w, probe, engine));
                left--;
                added = true;
            }
            if (!added) break;
            layer++;
        }
        int staffed = 0, low = Integer.MAX_VALUE, high = 0;
        for (int w = 0; w < total; w++) {
            int asked = depth.get(w).intValue();
            if (asked <= 0) continue;
            staffed++;
            low = Math.min(low, asked);
            high = Math.max(high, asked);
        }
        boolean[] chineseWindow = new boolean[total];
        for (int i = 0; i < out.asks.size(); i++)
            if (chineseEngine(out.asks.get(i).engine)) chineseWindow[out.asks.get(i).window] = true;
        for (int w = 0; w < total; w++) if (chineseWindow[w]) out.chineseWindows++;
        out.windowsPlanned = staffed;
        out.depthLow = staffed == 0 ? 0 : low;
        out.depthHigh = high;
        out.unstaffed = total - staffed;
        out.cappedByRequests = out.unstaffed > 0 && left <= 0;
        out.cappedByCap = out.unstaffed > 0 && left > 0;
        return out;
    }

    /** 这一家在这一扇上是否已经被排过（任一条检索式算数）。 */
    private static boolean coversWindow(boolean[] used, int engines, int slot) {
        for (int i = 0; i < used.length; i++) if (i % engines == slot && used[i]) return true;
        return false;
    }

    /** 这一层这一扇该问哪条检索式：-1 表示这一扇到此为止，没有更多名额。 */
    private static int slotProbe(int probeCount, int layer, int orphanAfter, int engines) {
        if (layer >= slotCeiling(probeCount, engines)) return -1;
        return (probeCount > 1 && layer == orphanAfter) ? 1 : 0;
    }

    /** 一扇窗口最多排几次：每家人手一次整窗式，混主题那条补问式再多一次。 */
    private static int slotCeiling(int probeCount, int engines) {
        return engines + (probeCount > 1 ? 1 : 0);
    }

    /**
     * 挑下一家问：没在这条检索式上问过的、还有额度的里面，剩余额度最多的那家（并列按勾选顺序）。
     *
     * 按剩余额度挑就是按"最少被问过"挑：九家全选时它把提问摊平，谁也不会整轮没被问过——
     * 报告里"每个检索源至少问到一次"那条老规矩靠这个维持。第一层多一条规矩：先尽着中文主库。
     */
    private static String pickEngine(ArrayList<String> engines, LinkedHashMap<String, Integer> spare,
                                     boolean[] used, int probe, boolean firstSlot, boolean needSpare) {
        int size = engines.size();
        for (int pass = 0; pass < (firstSlot ? 2 : 1); pass++) {
            String best = null;
            int bestSpare = 0;
            for (int i = 0; i < size; i++) {
                String engine = engines.get(i);
                if (used[probe * size + i]) continue;
                int value = spare.get(engine).intValue();
                if (needSpare && value <= 0) continue;
                if (firstSlot && pass == 0 && !chineseEngine(engine)) continue;
                if (best == null || value > bestSpare) { best = engine; bestSpare = value; }
            }
            if (best != null) return best;
        }
        return null;
    }

    private static void search(String text, TextCorpus corpus, Report report, ArrayList<String> engines,
                               PaperSources.Limits limits, ApiClient.Cancellation cancellation, Progress progress) {
        if (engines.isEmpty()) return;
        WindowPlan plan = windowPlan(text, Integer.MAX_VALUE);
        report.windowsAvailable = plan.groups.size();
        report.comparableChars = plan.comparableChars;
        if (plan.groups.isEmpty()) { note(report, "正文没有可用于检索的段落"); return; }
        int perSourceCap = Math.max(1, limits.perEngine);
        int poolCap = Math.max(60, 8 * perSourceCap * engines.size());
        /* 检索式与额度分配在起线程之前全部算完：泳道之间只共用这一排算完的检索式与提问排期，
           谁都不把自己的检索现场摊给别的线程看。切得出多少扇是文档的事，问哪几扇是额度算出来的。 */
        ArrayList<ArrayList<String>> allProbes = new ArrayList<ArrayList<String>>();
        ArrayList<Integer> probeCounts = new ArrayList<Integer>();
        for (int w = 0; w < plan.members.size(); w++) {
            ArrayList<String> phrases = windowProbes(plan.members.get(w));
            allProbes.add(phrases);
            probeCounts.add(Integer.valueOf(phrases.size()));
        }
        /* 每源上限仍然取自设置里那个数，并且像以前一样截到 MAX_WINDOWS：超出去的那一截花的是同一份 120 次额度，
           而报告里那句"把窗口数调到 24 可扩大覆盖"也只有在这里真的截了才不至于说假话。 */
        int windowCap = Math.min(MAX_WINDOWS, Math.max(1, limits.windows));
        Allocation budget = allocate(plan.chinese, plan.chars, probeCounts, engines, windowCap, MAX_REQUESTS,
                orphanLayer);
        report.windowsPlanned = budget.windowsPlanned;
        report.asksPlanned = budget.asks.size();
        report.windowsBudget = MAX_REQUESTS;
        report.perSourceWindowCap = windowCap;
        report.windowsUnstaffed = budget.unstaffed;
        report.budgetCapped = budget.cappedByRequests;
        report.capCapped = budget.cappedByCap;
        /* 只有排进本轮的窗口进扫描表，且按正文顺序进：排序式必须按文档顺序重建，
           并行与"按字数排窗口"都不许改动它（见 Sweep#askedQueries）。 */
        boolean[] staffed = new boolean[plan.members.size()];
        for (int i = 0; i < budget.asks.size(); i++) staffed[budget.asks.get(i).window] = true;
        int[] local = new int[plan.members.size()];
        int kept = 0;
        for (int w = 0; w < plan.members.size(); w++) {
            local[w] = kept;
            if (staffed[w]) kept++;
        }
        /* 响应留档（A4）先建账本再起泳道：每一次请求都要落一行，成功也要落。
           Limits 是调用方递进来的，本轮的出口不往它身上挂——先复制一份再挂（PaperSources.Limits#copy）。 */
        ShapeLedger shapes = new ShapeLedger(report);
        PaperSources.Limits pass = limits.copy();
        pass.shapes = shapes;
        Sweep sweep = new Sweep(pass, shapes, cancellation, progress, kept, poolCap,
                Math.max(0L, engineGapMillis), System.currentTimeMillis() + Math.max(1L, searchMillis),
                budget.asks.size(), MAX_REQUESTS);
        for (int w = 0; w < plan.members.size(); w++)
            if (staffed[w]) sweep.addWindow(allProbes.get(w), plan.chars.get(w).intValue());
        ArrayList<Ask> schedule = new ArrayList<Ask>();
        for (int i = 0; i < budget.asks.size(); i++) {
            Ask ask = budget.asks.get(i);
            schedule.add(new Ask(local[ask.window], ask.probe, ask.engine));
        }
        ArrayList<Lane> lanes = lanes(sweep, engines, schedule);
        runLanes(lanes);
        /* 注记与逐源状态等全部泳道收工后，按设置里的源顺序并回来：谁先回来不得影响注记顺序。 */
        LinkedHashMap<String, Boolean> failed = new LinkedHashMap<String, Boolean>();
        LinkedHashMap<String, Boolean> rawSkipped = new LinkedHashMap<String, Boolean>();
        LinkedHashMap<String, Boolean> rawExhausted = new LinkedHashMap<String, Boolean>();
        LinkedHashMap<String, Boolean> rawSilent = new LinkedHashMap<String, Boolean>();
        LinkedHashMap<String, Boolean> rawAnswered = new LinkedHashMap<String, Boolean>();
        LinkedHashMap<String, Boolean> rawDeadRoute = new LinkedHashMap<String, Boolean>();
        boolean requestCap = false, timeCap = false;
        for (int i = 0; i < lanes.size(); i++) {
            Lane lane = lanes.get(i);
            rawSkipped.putAll(lane.skipped);
            rawExhausted.putAll(lane.exhausted);
            rawSilent.putAll(lane.silent);
            rawAnswered.putAll(lane.answered);
            rawDeadRoute.putAll(lane.deadRoute);
            failed.putAll(lane.failedLast);
            requestCap = requestCap || lane.quotaCap;
            timeCap = timeCap || lane.timeCap;
        }
        LinkedHashMap<String, Boolean> skipped = orderedFlags(engines, rawSkipped);
        LinkedHashMap<String, Boolean> exhausted = orderedFlags(engines, rawExhausted);
        LinkedHashMap<String, Boolean> silent = orderedFlags(engines, rawSilent);
        LinkedHashMap<String, Boolean> failedLast = orderedFlags(engines, failed);
        for (int i = 0; i < lanes.size(); i++)
            for (int n = 0; n < lanes.get(i).notes.size(); n++) note(report, lanes.get(i).notes.get(n));
        for (int i = 0; i < engines.size(); i++) {
            int times = 0;
            for (int j = 0; j < lanes.size(); j++) times += lanes.get(j).asksOf(engines.get(i));
            if (times > 0) report.windowsAsked.put(engines.get(i), Integer.valueOf(times));
        }

        /* 连通情况在这儿并账：泳道逐次分过"答了话"与"连接没建成"，这里只数现成的账。
           一个源答过一次就算通了——它证明手机这一段走得出去，剩下的是站点自己的事。 */
        LinkedHashMap<String, Boolean> answered = orderedFlags(engines, rawAnswered);
        LinkedHashMap<String, Boolean> deadRoute = orderedFlags(engines, rawDeadRoute);
        for (int i = 0; i < engines.size(); i++) {
            String engine = engines.get(i);
            if (count(report.windowsAsked, engine) <= 0) continue;
            report.hostsAsked++;
            if (Boolean.TRUE.equals(answered.get(engine))) report.hostsReached++;
            else if (Boolean.TRUE.equals(deadRoute.get(engine))) report.hostsUnreachable++;
        }
        if (cancelled(cancellation)) note(report, "检索已取消，结果只覆盖已完成的窗口");
        report.windowsRetrieved = sweep.windowsRetrieved();
        report.coveredChars = sweep.coveredChars();
        /* 每窗几家、问了几次、几扇问到中文主库：一律按真问出去的算，不按排期算——撞了挂钟那一轮，
           排期说的数做不到，报告不能替它圆。 */
        report.asksSent = sweep.asksSent();
        report.windowDepthLow = sweep.depthLow();
        report.windowDepthHigh = sweep.depthHigh();
        report.chineseWindowsAsked = sweep.chineseWindows();
        report.asksSpilled = sweep.spillCells();
        report.asksRefilled = sweep.refillCells();
        /* 第二阶段用本轮那份 Limits（带留档出口）：全文抓取也要落一行，出口不在这就没法落。 */
        phaseB(sweep.askedQueries(), corpus, report, engines, sweep.limits, cancellation, sweep.pool, skipped,
                failedLast, exhausted, silent, requestCap, timeCap, poolCap, sweep.droppedCandidates(), sweep.deadline);
    }

    /**
     * 一轮检索共用的东西：算好的检索式、候选池、挂钟、进度。
     *
     * 泳道之间只经由这个类的同步方法碰彼此；每个源自己的会话状态（维普的 sessionid、知网的检索页
     * token）留在自己那条泳道的线程里，一次请求从头到尾在同一个线程内完成，不跨线程交接。
     */
    /**
     * 响应留档这本账（A4）。泳道是并行的，所以记账在锁里；它还要能回答"这一次调用里检索侧
     * 自己落了几行"——落过就不再补一行 error，没落过（连接压根没建成那一类）才补，
     * 一档一次请求一行，不多不少。
     */
    private static final class ShapeLedger implements PaperSources.ShapeSink {
        private final Report report; private final Object lock = new Object();
        private final LinkedHashMap<Long, Integer> byThread = new LinkedHashMap<Long, Integer>();
        private int dropped;
        ShapeLedger(Report report) { this.report = report; }
        public void record(PaperSources.ShapeRow row) {
            if (row == null) return;
            synchronized (lock) { add(row); bump(); }
        }
        /** 本线程到现在落了几行：Lane 拿它当"这一次调用留没留下响应账"的凭据。 */
        int mark() { synchronized (lock) { return count(); } }
        /** 异常而账上没多出行：补一行 no-response，别让"连接都没建成"在档里是空白。 */
        void miss(String engine, String phrase, String error, int before) {
            synchronized (lock) {
                if (count() != before) return;
                PaperSources.ShapeRow row = new PaperSources.ShapeRow();
                row.engine = engine == null ? "" : engine;
                row.probe = phrase == null ? "" : (phrase.length() <= 60 ? phrase : phrase.substring(0, 60) + "…");
                row.shape = "no-response";
                row.failed = true;
                row.error = error == null ? "" : error;
                add(row); bump();
            }
        }
        private int count() {
            Integer value = byThread.get(Long.valueOf(Thread.currentThread().getId()));
            return value == null ? 0 : value.intValue();
        }
        private void bump() {
            Long key = Long.valueOf(Thread.currentThread().getId());
            Integer value = byThread.get(key);
            byThread.put(key, Integer.valueOf(value == null ? 1 : value.intValue() + 1));
        }
        private void add(PaperSources.ShapeRow row) {
            if (report.shapes.size() >= MAX_SHAPE_ROWS) {
                dropped++;
                report.shapesDropped = dropped;
                return;
            }
            report.shapes.add(row);
        }
    }

    private static final class Sweep {
        final PaperSources.Limits limits; final ApiClient.Cancellation cancellation;
        /** 本轮的响应留档账本，随 limits.shapes 递给检索侧，泳道只经由它记账。 */
        final ShapeLedger shapes;
        final Progress progress; final int planned; final int poolCap; final long gap; final long deadline;
        final int total;
        /** 还没花掉的请求额度（整轮一个数，不按泳道切份额）：Lane 每发一次问扣一次。 */
        private final AtomicInteger requestsLeft;
        final ArrayList<ArrayList<String>> probes = new ArrayList<ArrayList<String>>();
        final ArrayList<Integer> chars = new ArrayList<Integer>();
        final ArrayList<PaperSources.Candidate> pool = new ArrayList<PaperSources.Candidate>();
        final Object lock = new Object();
        /** 主线程被打断时置位：泳道在每一扇窗口、每一次请求之前都看它一眼。 */
        volatile boolean stop;
        private final LinkedHashMap<String, Boolean> poolKeys = new LinkedHashMap<String, Boolean>();
        private final ArrayList<Boolean[]> sent = new ArrayList<Boolean[]>();
        /** 格子 = 一扇窗口的一条检索式。问不动的格子排到这里，等同道其他源本轮补问。 */
        private final ArrayDeque<int[]> spill = new ArrayDeque<int[]>();
        private final ArrayList<boolean[]> spilled = new ArrayList<boolean[]>();
        private int spillCells, refillCells;
        private boolean[] askedWindow;
        /** 每扇窗口真问出去几次、有没有问到过一家中文主库：报告的"每窗问 2-3 家"读这两个数组。 */
        private int[] asksByWindow;
        private boolean[] chineseWindow;
        private int dropped;
        private final AtomicInteger done = new AtomicInteger();
        Sweep(PaperSources.Limits limits, ShapeLedger shapes, ApiClient.Cancellation cancellation,
              Progress progress, int planned, int poolCap, long gap, long deadline, int total, int requests) {
            this.limits = limits; this.shapes = shapes;
            this.cancellation = cancellation; this.progress = progress;
            this.planned = planned; this.poolCap = poolCap; this.gap = gap; this.deadline = deadline;
            this.total = total; this.askedWindow = new boolean[planned];
            this.asksByWindow = new int[planned]; this.chineseWindow = new boolean[planned];
            this.requestsLeft = new AtomicInteger(Math.max(0, requests));
        }
        /** 还剩几次请求额度：只读，报告与断言看它。 */
        int requestsLeft() { return requestsLeft.get(); }
        /** 领一次请求额度：120 次这道闸在这里扣，扣不动就是撞闸，四条道抢同一个数也超不出去。 */
        boolean spendRequest() {
            while (true) {
                int now = requestsLeft.get();
                if (now <= 0) return false;
                if (requestsLeft.compareAndSet(now, now - 1)) return true;
            }
        }
        void addWindow(ArrayList<String> phrases, int validChars) {
            synchronized (lock) {
                probes.add(phrases);
                chars.add(Integer.valueOf(validChars));
                sent.add(new Boolean[phrases.size()]);
                spilled.add(new boolean[phrases.size()]);
            }
            /* 这一格问不动时落回补问队列（Sweep#spill）。 */
        }
        /**
         * 这一格没问出去（排到的那家报错、已判取尽、或这条短语问过）：排回补问队列。
         *
         * 同一格只排一次：九家都停的时候不能把队列堆成九倍，补问也没那么多额度。
         */
        void spill(int window, int probe) {
            synchronized (lock) {
                if (window < 0 || window >= spilled.size()) return;
                boolean[] flags = spilled.get(window);
                if (probe < 0 || probe >= flags.length || flags[probe]) return;
                flags[probe] = true;
                spill.addLast(new int[] { window, probe });
                spillCells++;
            }
        }
        /** 取一格等补问的；取不到就是排期已经落地。 */
        int[] takeSpill() {
            synchronized (lock) {
                return spill.pollFirst();
            }
        }
        /** 补问成功一次记一笔：报告要说清退回多少、补回多少。 */
        void refilled() {
            synchronized (lock) {
                refillCells++;
            }
        }
        int spillCells() { synchronized (lock) { return spillCells; } }
        int refillCells() { synchronized (lock) { return refillCells; } }
        /** 一次检索的候选并进池子：返回 true 表示池子已经装到顶，那一次的沉默不能算成源自己取尽。 */
        boolean collect(String engine, ArrayList<PaperSources.Candidate> found,
                        LinkedHashMap<String, Integer> gained) {
            synchronized (lock) {
                boolean full = false;
                for (int i = 0; i < found.size(); i++) {
                    PaperSources.Candidate candidate = found.get(i);
                    String key = poolKey(engine, candidate);
                    if (key.isEmpty()) continue;
                    if (pool.size() >= poolCap) { dropped++; full = true; continue; }
                    if (Boolean.TRUE.equals(poolKeys.get(key))) continue;
                    poolKeys.put(key, Boolean.TRUE);
                    pool.add(candidate);
                    bump(gained, engine);
                }
                return full;
            }
        }
        /** 记"这一扇窗口的这一条检索式真的问出去了"：覆盖字数、排序式、每窗几家都从这儿出。 */
        void sent(int window, int probe, String engine) {
            synchronized (lock) {
                askedWindow[window] = true;
                asksByWindow[window]++;
                if (chineseEngine(engine)) chineseWindow[window] = true;
                sent.get(window)[probe] = Boolean.TRUE;
            }
        }
        /** 真问出去的提问次数。 */
        int asksSent() {
            synchronized (lock) {
                int sum = 0;
                for (int w = 0; w < planned; w++) sum += asksByWindow[w];
                return sum;
            }
        }
        /** 问到过的窗口里最浅与最深：没问过的窗口不参与，免得把"没排上"混进"问得浅"。 */
        int depthLow() {
            synchronized (lock) {
                int low = 0;
                for (int w = 0; w < planned; w++)
                    if (asksByWindow[w] > 0 && (low == 0 || asksByWindow[w] < low)) low = asksByWindow[w];
                return low;
            }
        }
        int depthHigh() {
            synchronized (lock) {
                int high = 0;
                for (int w = 0; w < planned; w++) high = Math.max(high, asksByWindow[w]);
                return high;
            }
        }
        int chineseWindows() {
            synchronized (lock) {
                int count = 0;
                for (int w = 0; w < planned; w++) if (chineseWindow[w]) count++;
                return count;
            }
        }
        int windowsRetrieved() {
            synchronized (lock) {
                int count = 0;
                for (int w = 0; w < planned; w++) if (askedWindow[w]) count++;
                return count;
            }
        }
        int coveredChars() {
            synchronized (lock) {
                int sum = 0;
                for (int w = 0; w < planned; w++) if (askedWindow[w]) sum += chars.get(w).intValue();
                return sum;
            }
        }
        int droppedCandidates() { synchronized (lock) { return dropped; } }
        /**
         * 这一轮真正问出去的检索式，按窗口顺序重排（排序阶段要用，见 phaseB 的 ranking）。
         * 并行之下"谁先回来"是不确定的，所以排序式必须按文档顺序重建，不许按完成顺序。
         */
        ArrayList<String> askedQueries() {
            synchronized (lock) {
                ArrayList<String> out = new ArrayList<String>();
                for (int w = 0; w < probes.size(); w++) {
                    ArrayList<String> phrases = probes.get(w);
                    Boolean[] flags = sent.get(w);
                    for (int p = 0; p < phrases.size(); p++)
                        if (Boolean.TRUE.equals(flags[p]) && !out.contains(phrases.get(p))) out.add(phrases.get(p));
                }
                return out;
            }
        }
        void step(String label) {
            int doneSoFar = done.incrementAndGet();
            if (progress != null) progress.step(label, Math.min(doneSoFar, total), total);
        }
    }

    /**
     * 一条泳道 = 若干个源：道内串行、道间并行。
     *
     * 为什么必须并行：真机实测一篇 6376 字的稿子问三个中文源，一扇窗口串行要 19 秒（维普单源实测
     * 4062ms，知网匿名口常拖到十几秒），180 秒那道挂钟闸门只够问完 8/11 扇——最慢的那个源把其余
     * 两个源的时间一起花掉了，覆盖率的天花板是它给的，不是论文给的。防封 IP 那道 400ms 是
     * "同一个源两次提问之间"的规矩，跨源并行不碰它：每个源仍然只在自己的线程里被逐次提问。
     */
    private static final class Lane implements Runnable {
        final ArrayList<String> engines;
        /** 本轮排给这一道的提问（窗口序号已换成本轮扫描表里的序号），按排期顺序问。 */
        final ArrayList<Ask> plan = new ArrayList<Ask>();
        final LinkedHashMap<String, Boolean> skipped = new LinkedHashMap<String, Boolean>();
        final LinkedHashMap<String, Boolean> exhausted = new LinkedHashMap<String, Boolean>();
        final LinkedHashMap<String, Boolean> silent = new LinkedHashMap<String, Boolean>();
        final LinkedHashMap<String, Boolean> failedLast = new LinkedHashMap<String, Boolean>();
        /** 这个源至少答过一次话（200/403/412/429 都算）：连通那本账的正账。 */
        final LinkedHashMap<String, Boolean> answered = new LinkedHashMap<String, Boolean>();
        /** 这个源至少有一次停在连接层：整轮都没答过话的源才会被算成"没通"。 */
        final LinkedHashMap<String, Boolean> deadRoute = new LinkedHashMap<String, Boolean>();
        final ArrayList<String> notes = new ArrayList<String>();
        /** 120 次请求额度见底 / 挂钟用完：两道闸各自留字据。 */
        boolean quotaCap, timeCap;
        private final Sweep sweep;
        private final LinkedHashMap<String, Long> lastAsk = new LinkedHashMap<String, Long>();
        private final LinkedHashMap<String, Boolean> askedPhrase = new LinkedHashMap<String, Boolean>();
        private final LinkedHashMap<String, Integer> gained = new LinkedHashMap<String, Integer>();
        private final LinkedHashMap<String, Integer> quiet = new LinkedHashMap<String, Integer>();
        private final LinkedHashMap<String, Integer> retries = new LinkedHashMap<String, Integer>();
        private final LinkedHashMap<String, Integer> askedBy = new LinkedHashMap<String, Integer>();
        private final LinkedHashMap<String, Boolean> askedNow = new LinkedHashMap<String, Boolean>();
        Lane(Sweep sweep, ArrayList<String> engines) {
            this.sweep = sweep; this.engines = engines;
        }
        int asksOf(String engine) { return count(askedBy, engine); }
        public void run() {
            int next = 0;
            while (next < plan.size()) {
                /* 一次到站 = 排给这一道的、同一扇窗口里的所有提问。取尽判据要按窗口看，
                   所以它落在下面一站问完之后，与改之前逐窗过账的口径一字不差。 */
                int window = plan.get(next).window;
                boolean poolFull = false;
                askedNow.clear();
                gained.clear();
                while (next < plan.size() && plan.get(next).window == window) {
                    if (cancelled(sweep.cancellation) || sweep.stop) return;
                    Ask ask = plan.get(next);
                    next++;
                    ArrayList<String> phrases = sweep.probes.get(window);
                    if (ask.probe < 0 || ask.probe >= phrases.size()) continue;
                    String phrase = phrases.get(ask.probe);
                    if (phrase.isEmpty()) continue;
                    int sent = askOn(ask.engine, window, ask.probe, phrase);
                    if (sent == STOP) return;
                    /* 排给这一家的这一格问不动（它报了错、已判取尽，或这条短语刚问过）：落回排期，
                       本轮内由这一道里还活着的源补问。不补的话，这一扇窗口就跟着一个
                       停下的源一起没被问过——真机 2.6.3 那轮 5 个源判了取尽，120 次请求
                       全花在 10 扇窗口上，后面 39 扇一次也没问到。 */
                    if (sent == MISS) sweep.spill(window, ask.probe);
                    else poolFull = poolFull | sentPoolFull;
                }
                settleQuiet(poolFull);
            }
            refill();
        }

        /** askOn() 的三种下场：真问出去 / 这一格问不动 / 额度或挂钟不等人，整条道收手。 */
        private static final int SENT = 0, MISS = 1, STOP = 2;
        /** 上一次 askOn() 是不是把候选池装到了顶：取尽判据要把它与源自己的沉默分开。 */
        private boolean sentPoolFull;

        /**
         * 向指定的那一家问这一格：120 次请求与 180 秒挂钟都在这里扣，四条道抢的是同一个原子数。
         *
         * MISS 不是错：该问的那一家问不了（停了、或这条短语刚问过），怎么接着办由调用方定。
         */
        private int askOn(String engine, int window, int probe, String phrase) {
            sentPoolFull = false;
            if (Boolean.TRUE.equals(skipped.get(engine))
                    || Boolean.TRUE.equals(exhausted.get(engine))) return MISS;
            /* 相邻窗口的重复段落会凑出同一个 48 字短语，这种请求纯属白送，也不算额度。 */
            if (Boolean.TRUE.equals(askedPhrase.get(engine + "|" + phrase))) return MISS;
            /* 剩余额度连一次带超时的请求都放不下就不再发：那是发出去必死的请求。 */
            if (sweep.deadline - System.currentTimeMillis()
                    <= sweep.limits.timeoutSeconds * 1000L + 1000L) { timeCap = true; return STOP; }
            if (!sweep.spendRequest()) { quotaCap = true; return STOP; }
            waitTurn(lastAsk, engine, sweep.gap, sweep.deadline, sweep.cancellation);
            askedPhrase.put(engine + "|" + phrase, Boolean.TRUE);
            askedNow.put(engine, Boolean.TRUE);
            bump(askedBy, engine);
            sweep.sent(window, probe, engine);
            sweep.step("检索 " + PaperSources.label(engine));
            int shapesBefore = sweep.shapes.mark();
            try {
                ArrayList<PaperSources.Candidate> found =
                        PaperSources.search(engine, phrase, sweep.limits, sweep.cancellation);
                failedLast.remove(engine);
                answered.put(engine, Boolean.TRUE);
                sentPoolFull = sweep.collect(engine, found, gained);
            } catch (IllegalArgumentException error) {
                sweep.shapes.miss(engine, phrase, message(error), shapesBefore);
                skipped.put(engine, Boolean.TRUE); failedLast.put(engine, Boolean.TRUE);
                notes.add("已跳过 " + PaperSources.label(engine) + "：" + message(error));
            } catch (IOException error) {
                /* 留档：响应侧没机会落行的失败（连不上、超时、429 被包成 IOException）
                   在这里补一行，档里从此没有"问了但什么都没留下"这一格。 */
                sweep.shapes.miss(engine, phrase, message(error), shapesBefore);
                /* 429 与"这个源挂了"不是一回事：匿名共享配额是暂时的，一次失败就整轮放弃太狠。
                   判据用注记前缀而不是状态码，因为 PaperSources.search() 只往上抛 IOException。 */
                failedLast.put(engine, Boolean.TRUE);
                /* 连通情况按这一次的下场记：403/412 是答了话，拨不上与超时是路没通。 */
                int reach = reachOf(error);
                if (reach > 0) answered.put(engine, Boolean.TRUE);
                else if (reach < 0) deadRoute.put(engine, Boolean.TRUE);
                boolean throttled = message(error).startsWith(THROTTLED_PREFIX)
                        && count(retries, engine) < MAX_THROTTLE_RETRIES;
                if (throttled) {
                    bump(retries, engine);
                    notes.add("已重试 " + PaperSources.label(engine) + "：" + message(error));
                } else {
                    skipped.put(engine, Boolean.TRUE);
                    notes.add("已跳过 " + PaperSources.label(engine) + "：" + message(error));
                }
            }
            return SENT;
        }

        /**
         * 本轮内的补问：停问的源没能问出去的那些格子，捾回来问给这一道里还活着的源。
         *
         * 为什么不等下一轮：排期是起线程之前算死的，源是当场停的。差的那一口当场不补，那一
         * 扇窗口这一轮就一次也没被问过。额度花光时 askOn 自己会把它停下，不会多花一次请求。
         */
        private void refill() {
            while (true) {
                if (cancelled(sweep.cancellation) || sweep.stop) return;
                int[] cell = sweep.takeSpill();
                if (cell == null) return;
                ArrayList<String> phrases = sweep.probes.get(cell[0]);
                if (cell[1] < 0 || cell[1] >= phrases.size()) continue;
                String phrase = phrases.get(cell[1]);
                if (phrase.isEmpty()) continue;
                askedNow.clear();
                gained.clear();
                boolean poolFull = false;
                for (int i = 0; i < engines.size(); i++) {
                    int sent = askOn(engines.get(i), cell[0], cell[1], phrase);
                    if (sent == STOP) return;
                    if (sent != SENT) continue;
                    poolFull = poolFull | sentPoolFull;
                    sweep.refilled();
                    break;
                }
                settleQuiet(poolFull);
            }
        }

        /** 取尽判据：连着两次问完一篇新的都没多的源，判它取尽，不再花配额问它。 */
        private void settleQuiet(boolean poolFull) {
                if (!poolFull) for (int e = 0; e < engines.size(); e++) {
                    String engine = engines.get(e);
                    if (!Boolean.TRUE.equals(askedNow.get(engine))) continue;
                    /* 刚报错的源不配被说成"已取尽"：它没有沉默，是失败了，"本次不可用"才是它的账。 */
                    if (Boolean.TRUE.equals(failedLast.get(engine))) continue;
                    if (count(gained, engine) > 0) { quiet.put(engine, Integer.valueOf(0)); silent.remove(engine); continue; }
                    silent.put(engine, Boolean.TRUE);
                    int streak = count(quiet, engine) + 1;
                    quiet.put(engine, Integer.valueOf(streak));
                    if (streak >= 2) exhausted.put(engine, Boolean.TRUE);
                }
        }
    }

    /**
     * 泳道怎么分：源按轮转进道（第 i 个源进第 i mod 道数 条），排期里属于哪一道的提问就交给哪一道。
     *
     * 为什么是轮转而不是切段：真机上慢的永远是同几个源（维普 4062ms、知网匿名口十几秒），
     * 按顺序切段会把它们全挤进同一条泳道，那条泳道一慢就等于那个源整轮没被问——
     * 那正是原来串行实现的病，不能换个名字再犯一遍。
     *
     * 额度不再按泳道切份额：120 次是整轮的额度，由 Sweep 拿一个原子数逐次扣（Lane 每发一次问扣一次）。
     * 按份额切的做法撞闸时只砍在份额先花完的那几条道上，剩下的道还在按自己的份额白问。
     */
    private static ArrayList<Lane> lanes(Sweep sweep, ArrayList<String> engines, ArrayList<Ask> plan) {
        int count = Math.min(engines.size(), MAX_LANES);
        ArrayList<ArrayList<String>> grouped = new ArrayList<ArrayList<String>>();
        for (int i = 0; i < count; i++) grouped.add(new ArrayList<String>());
        for (int i = 0; i < engines.size(); i++) grouped.get(i % count).add(engines.get(i));
        ArrayList<Lane> out = new ArrayList<Lane>();
        for (int i = 0; i < count; i++) out.add(new Lane(sweep, grouped.get(i)));
        for (int i = 0; i < plan.size(); i++) {
            Ask ask = plan.get(i);
            out.get(Math.max(0, engines.indexOf(ask.engine)) % count).plan.add(ask);
        }
        return out;
    }


    /** 起线程与收线程：只有一个源就不值得起线程，那条路走的还是原来那条串行代码。 */
    private static void runLanes(ArrayList<Lane> lanes) {
        if (lanes.size() == 1) { lanes.get(0).run(); return; }
        ArrayList<Thread> threads = new ArrayList<Thread>();
        for (int i = 0; i < lanes.size(); i++) {
            Thread thread = new Thread(lanes.get(i), "wordlite-search-" + (i + 1));
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }
        for (int i = 0; i < threads.size(); i++) {
            try {
                threads.get(i).join();
            } catch (InterruptedException error) {
                /* 主线程被打断：让泳道在下一个检查点自己收手，不许留三条线程在下面继续写池子。 */
                Thread.currentThread().interrupt();
                for (int j = 0; j < lanes.size(); j++) lanes.get(j).sweep.stop = true;
                return;
            }
        }
    }

    /** 逐源状态按设置里的源顺序重排：注记的顺序不许受并行完成顺序的影响。 */
    private static LinkedHashMap<String, Boolean> orderedFlags(ArrayList<String> engines,
                                                               LinkedHashMap<String, Boolean> from) {
        LinkedHashMap<String, Boolean> out = new LinkedHashMap<String, Boolean>();
        for (int i = 0; i < engines.size(); i++)
            if (Boolean.TRUE.equals(from.get(engines.get(i)))) out.put(engines.get(i), Boolean.TRUE);
        return out;
    }

    /**
     * 第二阶段：跨源合并 -> BM25 名次 -> 按名次入库。全文额度也跟着名次走，
     * 不再是谁先回来谁先抓。
     */
    private static void phaseB(ArrayList<String> asked, TextCorpus corpus, Report report, ArrayList<String> engines,
                               PaperSources.Limits limits, ApiClient.Cancellation cancellation,
                               ArrayList<PaperSources.Candidate> pool, LinkedHashMap<String, Boolean> skipped,
                               LinkedHashMap<String, Boolean> failedLast, LinkedHashMap<String, Boolean> exhausted,
                               LinkedHashMap<String, Boolean> silent, boolean requestCap, boolean timeCap,
                               int poolCap, int poolDropped, long deadline) {
        CandidateRanker.Dedup merged = CandidateRanker.dedup(pool);
        report.mergedDuplicates = merged.merges.size();
        report.merges.addAll(merged.merges);

        /* 排序式 = 这一轮真正问出去的检索式拼起来（1.1.3）。
           BM25 在这一层回答的是"这篇候选与本文对不对题"，所以它的查询必须是**本文**——而本文在检索时
           是一扇一扇窗口分开问出去的，排序式就必须是同一批窗口式合起来。以前这里是
           `queryPhrase(text, 48)`：整篇文档压成 48 个字，几乎必然只剩开头几段的词。真机跑一篇
           19967 字的开题报告（2026-10-08）：十个源问回 36 条，其中 34 条与那 48 个字一个三元组都不沾，
           按旧规矩整条丢掉，最后入库 2 条，总相似度比 0.00%——那个 0% 不是论文干净，是比对根本没拿到
           可比的对象。只命中在论文中段的真出处，在旧口径下必被丢掉。 */
        StringBuilder ranking = new StringBuilder();
        for (int i = 0; i < asked.size(); i++) {
            if (i > 0) ranking.append('\n');
            ranking.append(asked.get(i));
        }
        String query = ranking.toString();
        int budget = limits.fullTexts > 0 ? limits.fullTexts : MAX_FULL_TEXTS;
        ArrayList<CandidateRanker.Selection> plan = CandidateRanker.plan(query, merged.kept, budget,
                Math.max(1, limits.perEngine));
        boolean corpusCap = false;
        for (int i = 0; i < plan.size(); i++) {
            CandidateRanker.Selection pick = plan.get(i);
            if (cancelled(cancellation)) { note(report, "检索已取消，结果只覆盖已完成的窗口"); break; }
            if (report.candidates.size() >= MAX_CORPUS_PAPERS) {
                note(report, "候选文献已达上限 " + MAX_CORPUS_PAPERS + " 篇，后续检索结果未入库");
                corpusCap = true;
                break;
            }
            PaperSources.Candidate candidate = pick.candidate;
            /* 题名与摘要里一个查询词都没有的候选不进语料：实测"宽禁带半导体 封装 互连材料 可靠性"
               这条检索式，维普首条回《中国建筑》2025 年第 10 期、OpenAlex 回 ESPnet2 模型说明，
               这种条目进语料只会污染分母与来源榜。 */
            if (pick.score <= 0d) { report.unrankedCandidates++; continue; }
            String body = candidate.abstractText == null ? "" : candidate.abstractText;
            boolean fetched = false;
            /* 全文抓取是整轮里最贵的一步：每次抓之前都要重新看一眼取消与挂钟。 */
            if (pick.fetchFullText && !cancelled(cancellation)
                    && deadline - System.currentTimeMillis() > limits.timeoutSeconds * 1000L + 1000L) {
                try {
                    String got = PaperSources.fullText(candidate, limits, cancellation);
                    if (got != null && !got.trim().isEmpty()) { body = body.isEmpty() ? got : body + "\n" + got; fetched = true; }
                } catch (IOException error) { note(report, "全文抓取失败，改用摘要比对：" + message(error)); }
            }
            report.candidates.add(candidate);
            bump(report.candidateCount, CandidateRanker.sourceKey(candidate));
            if (body.trim().isEmpty()) {
                candidate.comparableMaterial = MATERIAL_RECORD;
                report.recordOnlyCandidates++;
                continue;
            }
            candidate.comparableMaterial = fetched ? MATERIAL_FULL : MATERIAL_ABSTRACT;
            /* 同一个档必须落到语料那条来源上：SourceLedger 按 Source.material 分档记账，
               只写在 candidate 上等于只有候选表知道、命中侧全按正文算——摘要级命中就冒充正文级了。 */
            if (candidate.source != null) candidate.source.material = candidate.comparableMaterial;
            if (fetched) report.fullTextCandidates++; else report.abstractOnlyCandidates++;
            report.comparableCandidates++;
            corpus.add(candidate.source, body);
            /* 抓到正文的就不用再列了；还只有摘要可比、但手里挂着 PDF 直链的，给用户一条自己把它
               下进自建库的路——自建库那一路的判据与联网正文完全同一条，缺的从来只是材料。 */
            if (!fetched && report.downloadables.size() < MAX_DOWNLOADABLES
                    && PaperSources.pdfUrlRank(pick.candidate.fullTextUrl) <= 2)
                report.downloadables.add(new Downloadable(candidate.source.title,
                        candidate.source.engine, pick.candidate.fullTextUrl.trim()));
        }
        everyConnectorFailed(report, engines, skipped);
        disclose(report, silent, failedLast, exhausted, requestCap, timeCap, corpusCap, poolCap, poolDropped);
    }

    /**
     * 闸门、覆盖率、可比材料——每一个没做完的理由都要在报告里留下字据，一条注记只发一次。
     *
     * 注记入队顺序是刻意的：覆盖率与闸门必须先于「X 未命中相关文献」一类逐源注记，否则
     * MAX_NOTES 写满时被挤掉的恰好是最要紧的那几句。
     */
    /**
     * 覆盖率那行后面追的一句：这一轮的钱花成了什么形状。全按真问出去的数说，排期不算数。
     *
     * 用户看到的"覆盖率"是一个百分比，它背后其实是两件事——问了多少扇、每扇问了几家。
     * 2.6.2 那轮只说 8/49，谁也不知道那 120 次请求是被"每扇问遍九家"吃掉的。
     */
    private static String allocationLine(Report report) {
        if (report.asksSent <= 0 || report.windowsRetrieved <= 0) return "";
        String depth = report.windowDepthLow == report.windowDepthHigh
                ? String.valueOf(report.windowDepthLow)
                : report.windowDepthLow + "-" + report.windowDepthHigh;
        String out = "；真问出去 " + report.asksSent + " 次，摊到这些窗口每窗 " + depth + " 家";
        if (report.chineseWindowsAsked > 0)
            out = out + "，其中 " + report.chineseWindowsAsked + " 扇问到知网/万方/维普";
        return out;
    }

    private static void disclose(Report report, LinkedHashMap<String, Boolean> silent,
                                 LinkedHashMap<String, Boolean> failedLast, LinkedHashMap<String, Boolean> exhausted,
                                 boolean requestCap, boolean timeCap, boolean corpusCap, int poolCap, int poolDropped) {
        double rate = report.comparableChars <= 0 ? 0d : report.coveredChars * 100d / report.comparableChars;
        /* "剩余几个窗口没检索"按整篇算：既包括排上了却没来得及问的，也包括额度根本没能排进本轮的。
           只报前者就是把"钱不够"说成"没时间"，那正是 2.6.2 那轮报告最容易被读错的地方。 */
        int left = report.windowsPlanned - report.windowsRetrieved + report.windowsUnstaffed;
        note(report, "已检索 " + report.windowsRetrieved + "/" + report.windowsAvailable + " 个检索窗口，覆盖 "
                + report.coveredChars + " 字（全文可比对 " + report.comparableChars + " 字，" + percent(rate)
                + "）" + allocationLine(report));
        /* 源停在半路上退回来的那些提问：补了多少、没补上多少。只说覆盖率不说这一句，
           用户看不出去这一轮的窗口是自巶问完的还是补兜补完的。 */
        if (report.asksSpilled > 0)
            note(report, "检索中途有 " + report.asksSpilled + " 次提问被停问的检索源退回，其中 "
                    + report.asksRefilled + " 次改由其他检索源补问"
                    + (report.asksRefilled == report.asksSpilled ? "" : "，剩下的没问上"));
        String gate = "";
        if (requestCap) {
            note(report, "检索请求已达上限 " + MAX_REQUESTS + " 次，剩余 " + left + " 个检索窗口未检索");
            gate = "检索请求已达上限 " + MAX_REQUESTS + " 次，剩余 " + left + " 个检索窗口未检索";
        }
        if (timeCap) {
            note(report, "检索时间已用满 " + (searchMillis / 1000L) + " 秒，已检索 " + report.windowsRetrieved + "/"
                    + report.windowsPlanned + " 个检索窗口后时间用完，剩余 " + left + " 个检索窗口未检索");
            if (gate.isEmpty())
                gate = "检索时间已用满 " + (searchMillis / 1000L) + " 秒，剩余 " + left + " 个检索窗口未检索";
        }
        if (report.windowsUnstaffed > 0 && !requestCap && !timeCap) {
            /* 额度排不下不是"检索中途出了事"，是这一份额度就买得起这么多扇：说清是哪一道闸挡的，
               用户才知道下一步该动哪个旋钮（多勾的源在争同一份每源上限，不是额度）。 */
            String why = report.budgetCapped
                    ? "检索请求额度 " + MAX_REQUESTS + " 次只排得出 " + report.windowsPlanned
                            + " 扇窗口，剩余 " + report.windowsUnstaffed + " 扇本轮没排上"
                    : "每个检索源按设置在 " + report.perSourceWindowCap + " 扇处截断，剩余 "
                            + report.windowsUnstaffed + " 扇本轮没排上；把检索设置的窗口数调到 24 可扩大覆盖";
            note(report, why);
            if (gate.isEmpty()) gate = why;
        }
        if (corpusCap && gate.isEmpty()) {
            /* 语料闸拦住的是"取回的比入库的多"，跟窗口没跑完是两件事，理由不能借隔壁那句。 */
            gate = "候选文献已达上限 " + MAX_CORPUS_PAPERS + " 篇，检索到的其余候选未入库";
        }
        if (exhausted.size() > 0)
            note(report, exhausted.size() + " 个检索源已取尽（连续两个窗口没有新增文献），后续窗口未再提问");
        /* 口径：按"最后一次尝试失败的源"计，不按失败次数累加——限流补试会让同一个源失败两次。 */
        if (!failedLast.isEmpty()) note(report, failedLast.size() + " 个检索源本次不可用");
        /* 通了几个源就排在"几个源不可用"后面：这两个数并排才分得清"手机出不去"与"站点在挡人"。 */
        /* 这两个数并排才分得清"手机出不去"与"站点在挡人"，所以只在可比正文一篇都没有的那两轮说；
           出口那一档也不说：它的原因行里已经带着这个数，同一屏说两遍等于谁都没说。 */
        String tally = hostTallyLine(report);
        if (!tally.isEmpty() && report.comparableCandidates == 0 && !noNetworkExit(report))
            note(report, tally);
        if (poolDropped > 0)
            note(report, "候选池上限 " + poolCap + " 条已用满，后续窗口新取回的 " + poolDropped + " 条未参与排序");
        if (report.unrankedCandidates > 0)
            note(report, report.unrankedCandidates + " 条候选与检索词无任何共同词，未纳入比对");
        if (report.mergedDuplicates > 0)
            note(report, "按 DOI/标题指纹跨源合并 " + report.mergedDuplicates + " 篇，同一篇只占一个比对名额");
        String reason = "";
        if (!gate.isEmpty()) reason = gate + "，相似率是下限";
        else if (report.windowsRetrieved < report.windowsPlanned)
            reason = "检索中途停止，只跑了 " + report.windowsRetrieved + "/" + report.windowsPlanned
                    + " 个检索窗口，相似率是下限";
        else if (report.windowsPlanned < report.windowsAvailable)
            reason = "本轮排到 " + report.windowsPlanned + "/" + report.windowsAvailable
                    + " 个检索窗口，全文还能切出的段落没问完，相似率是下限";
        /* 未完成与部分是互斥的两态：什么都没问出来才走 incomplete，问出来了但没跑完绝不能走那条，
           否则 CheckReport 会把真实测出的比率整块抹掉。 */
        if (!report.retrievalIncomplete && !reason.isEmpty()) {
            report.retrievalPartial = true;
            report.retrievalPartialReason = reason;
            note(report, reason);
        }
        int chinese = 0;
        for (String engine : new String[] { "cnki", "cqvip", "wanfang" })
            if (count(report.candidateCount, engine) > 0) chinese++;
        if (chinese > 0 && report.fullTextCandidates == 0)
            note(report, "知网、万方、维普只回摘要，正文与图表无法比对，相似率是下限");
        if (!report.downloadables.isEmpty())
            note(report, "另有 " + report.downloadables.size() + " 篇候选挂着可直接下载的开放获取 PDF，"
                    + "结果页可一键下进自建库（自建库按正文比对，不按摘要）");
        if (!report.candidates.isEmpty()) {
            note(report, "共取回 " + report.candidates.size() + " 篇候选文献（跨源合并 " + report.mergedDuplicates
                    + " 篇），入库比对 " + report.comparableCandidates + " 篇");
            note(report, "可比材料只有摘要的有 " + report.abstractOnlyCandidates + " 篇（共 " + report.candidates.size()
                    + " 篇，其中 " + report.fullTextCandidates + " 篇抓到开放获取全文，" + report.recordOnlyCandidates
                    + " 篇只有题录无法比对），相似率是下限");
        } else note(report, GAP_NOTHING_RETRIEVED);
        for (String engine : report.candidateCount.keySet()) silent.remove(engine);
        for (String engine : silent.keySet()) note(report, PaperSources.label(engine) + " 未命中相关文献");
    }

    /**
     * 同一源两次请求之间的最小间隔：连打 24 次是封 IP 的形状，不是查重的形状。
     *
     * 记的是"请求真正发出去的那一刻"而不是进函数的一刻：等完觉之后不把钟拨到醒来时分，
     * 下一次进来看到的是"距离上次已经过了整个等待时长"，于是跳过等待——实测那样只能拉开
     * 半个间隔，四个窗口连打八次，防封 IP 的闸门等于没装。
     */
    private static void waitTurn(LinkedHashMap<String, Long> lastAsk, String engine, long gap, long deadline,
                                 ApiClient.Cancellation cancellation) {
        long now = System.currentTimeMillis();
        Long at = lastAsk.get(engine);
        if (at == null || gap <= 0L || cancelled(cancellation)) {
            lastAsk.put(engine, Long.valueOf(now));
            return;
        }
        long wait = Math.min(at + gap - now, deadline - now);
        if (wait > 0L) {
            try { Thread.sleep(wait); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            now = System.currentTimeMillis();
        }
        lastAsk.put(engine, Long.valueOf(now));
    }

    /** 池内预筛键：同源跨窗口重复（相邻窗口常命中同一篇）在这儿就丢掉，别让"已取尽"判据被骗过去。 */
    private static String poolKey(String engine, PaperSources.Candidate candidate) {
        if (candidate == null || candidate.source == null) return "";
        String mark = !candidate.source.locator.isEmpty() ? candidate.source.locator : candidate.source.title;
        return mark.isEmpty() ? "" : engine + "|" + mark;
    }
    /** Every wanted connector threw and nothing usable came back: the web half of the run never happened. */
    private static void everyConnectorFailed(Report report, ArrayList<String> engines,
                                             LinkedHashMap<String, Boolean> skipped) {
        if (!report.candidates.isEmpty() || engines.isEmpty() || skipped.size() < engines.size())
            return;
        /* "全部不可用"有两种成因，下一步正好相反：连接根本没建成要修出口，答了话却被挡要换材料。
           "候选为空 + 每个想要的源都被跳过"，只在真的一个源都没回来时才发这个因。 */
        gap(report, noNetworkExit(report) ? noNetworkExitReason(report, engines.size())
                : everyConnectorFailedReason(report, engines.size()));
    }
    /**
     * 三个段落一组，最多 MAX_WINDOWS 组。封面行、目录行、图表注这类行拿去检索只会命中"毕业论文 专业
     * 设计"这种通用词，实测会把候选池污染成教学管理论文：短于 20 字的段和不以句号结尾而以页码收尾的段
     * （目录行就是"2.2.1 SiC高温封装与TLP互连技术4"这个形状）都不进窗口。
     */
    static final int MIN_WINDOW_PARAGRAPH_CHARS = 20;

    /** 目录行、图表注的共同形状：结尾是一个裸页码。 */
    static boolean retrievable(String paragraph) {
        return paragraph.length() >= MIN_WINDOW_PARAGRAPH_CHARS
                && !Character.isDigit(paragraph.charAt(paragraph.length() - 1));
    }
    /**
     * 一次切窗的完整结果。窗口组串只是探针，用户想知道的是"我这篇 3 万字的论文它真看了多少"，
     * 所以每组还要带上它覆盖的有效字数。
     */
    static final class WindowPlan {
        final ArrayList<String> groups = new ArrayList<String>();
        /** 与 groups 一一对应：该组三段落在正文里的有效字数，口径 TextCorpus.validCount。 */
        final ArrayList<Integer> chars = new ArrayList<Integer>();
        /** 与 groups 一一对应：这一扇窗口里有多少个中文字。排提问顺序用它，不用 chars——参考文献条目那种整段英文的窗口最长，可它一个中文字也没有。 */
        final ArrayList<Integer> chinese = new ArrayList<Integer>();
        /** 与 groups 一一对应：这一扇窗口里那几段的原文。组串只是检索式的原料，段落自己那句才是探针要护住的东西。 */
        final ArrayList<ArrayList<String>> members = new ArrayList<ArrayList<String>>();
        /** retrievable() 通过的段落总字数：文档覆盖率的分母。 */
        int comparableChars;
    }

    /**
     * 段落 -> 三段落一组的窗口，并算出每组的覆盖字数。normalize() 逐字符等长，所以原文偏移
     * 可以直接搬到归一化串上做 validCount，不必再切一遍文本。
     */
    /** 归一化串这一段里有几个中文字（标点、空白不算）。窗口排队用它判"这一扇值不值得问中文库"。 */
    static int cjkCount(String norm, int from, int to) {
        int count = 0;
        for (int i = Math.max(0, from); i < Math.min(to, norm.length()); i++) {
            char c = norm.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) count++;
        }
        return count;
    }

    static WindowPlan windowPlan(String text, int cap) {
        WindowPlan out = new WindowPlan();
        String value = text == null ? "" : text;
        String norm = TextCorpus.normalize(value);
        ArrayList<String> kept = new ArrayList<String>();
        ArrayList<int[]> spans = new ArrayList<int[]>();
        StringBuilder current = new StringBuilder();
        int start = 0;
        for (int i = 0; i <= value.length(); i++) {
            boolean cut = i == value.length() || value.charAt(i) == '\n';
            if (!cut) { current.append(value.charAt(i)); continue; }
            String raw = current.toString();
            int head = 0, tail = raw.length();
            while (head < tail && Character.isWhitespace(raw.charAt(head))) head++;
            while (tail > head && Character.isWhitespace(raw.charAt(tail - 1))) tail--;
            String value2 = raw.substring(head, tail);
            if (retrievable(value2)) {
                kept.add(value2);
                spans.add(new int[] { start + head, start + tail });
            }
            current.setLength(0);
            start = i + 1;
        }
        StringBuilder group = new StringBuilder();
        ArrayList<String> member = new ArrayList<String>();
        int grouped = 0, groupedCjk = 0, limit = cap < 1 ? 1 : cap;
        for (int i = 0; i < kept.size(); i++) {
            int[] span = spans.get(i);
            int chars = TextCorpus.validCount(norm, span[0], span[1]);
            out.comparableChars += chars;
            if (group.length() > 0) group.append(' ');
            group.append(kept.get(i));
            member.add(kept.get(i));
            grouped += chars;
            groupedCjk += cjkCount(norm, span[0], span[1]);
            if (i % WINDOW_PARAGRAPHS != WINDOW_PARAGRAPHS - 1 && i != kept.size() - 1) continue;
            /* 超出上限的组不再追加：设置里的窗口数就是用户许给这次检索的提问次数，
               切得出多少组是文档的事，问不问是设置的事，两个数各记各的。 */
            if (out.groups.size() >= limit) continue;
            out.groups.add(group.toString());
            out.chars.add(Integer.valueOf(grouped));
            out.chinese.add(Integer.valueOf(groupedCjk));
            out.members.add(new ArrayList<String>(member));
            group.setLength(0);
            member.clear();
            grouped = 0;
            groupedCjk = 0;
        }
        return out;
    }

    /** 段落检索式在整窗检索式里剩下的三元组低于这个数，就认为这一扇窗口压着第二种主题。 */
    static final double MIN_PROBE_OVERLAP = 1d / 3d;

    /**
     * 一扇窗口要问出去的检索式：平时一条；窗口里压着另一种主题时，那一段单独再问一次。
     *
     * 三段落一扇是拿请求换覆盖，代价写在实测里（2026-10-08，FullScanProbe 走产品链路）：把五句真论文
     * 原话种进 6376 字段落之间，按整窗检索式问了 11 扇、入库 17 篇，五句一句没抓到；同一批句子逐句去问，
     * 光维普就是 4/4 自召回（reports/recall-t2-after-cqvip.txt）。原因是整窗那四十八个字只装得下最具体的
     * 那一段——抄来的一段被邻居的关键词盖住时，它的词一个字都进不了检索式，问都问不到。判据因此是
     * "这一段自己的检索式在整窗检索式里还剩多少三元组"：剩得少说明这一扇里有两种主题，而那正是抄稿最常
     * 待的地方，这种窗口才值得多花一次请求；主题一致的窗口照旧一次问完。
     */
    static ArrayList<String> windowProbes(ArrayList<String> paragraphs) {
        ArrayList<String> out = new ArrayList<String>();
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < paragraphs.size(); i++) {
            if (i > 0) joined.append(' ');
            joined.append(paragraphs.get(i));
        }
        String whole = PaperSources.queryPhrase(joined.toString(), MAX_PHRASE_CHARS);
        if (!whole.isEmpty()) out.add(whole);
        long[] windowGrams = TextCorpus.gramsOf(TextCorpus.normalize(whole));
        String orphan = "";
        double weakest = 1d;
        for (int i = 0; i < paragraphs.size(); i++) {
            String paragraph = paragraphs.get(i);
            if (!retrievable(paragraph)) continue;
            String phrase = PaperSources.queryPhrase(paragraph, MAX_PHRASE_CHARS);
            if (phrase.isEmpty() || phrase.equals(whole)) continue;
            double kept = sharedRatio(phrase, windowGrams);
            if (kept < MIN_PROBE_OVERLAP && kept < weakest) { weakest = kept; orphan = phrase; }
        }
        /* 一扇最多补一条：补两条就等于把窗口切成段落，那是另一种预算口径，不在这里偷偷改。 */
        if (!orphan.isEmpty()) out.add(orphan);
        return out;
    }

    /** 这段的检索式有多少三元组落在整窗检索式里：零就意味着整窗那句问出去，这段一个字都问不到。 */
    private static double sharedRatio(String phrase, long[] windowGrams) {
        long[] grams = TextCorpus.gramsOf(TextCorpus.normalize(phrase));
        if (grams.length == 0) return 1d;
        if (windowGrams.length == 0) return 0d;
        int i = 0, j = 0, hits = 0;
        while (i < grams.length && j < windowGrams.length) {
            int by = Long.compare(grams[i], windowGrams[j]);
            if (by == 0) { hits++; i++; j++; } else if (by < 0) i++; else j++;
        }
        return hits / (double) grams.length;
    }

    /** 旧签名：校验上限 MAX_WINDOWS 下的窗口组串，`windowing()` 那类回归不必改。 */
    static ArrayList<String> windows(String text) { return windows(text, MAX_WINDOWS); }

    /** 运行时的窗口组串；数量由调用方给的 cap 决定，不再藏在常量里。 */
    static ArrayList<String> windows(String text, int cap) { return windowPlan(text, cap).groups; }

    /** {start,end} pairs over the selection text: reference paragraphs plus explicit quotations. */
    public static int[] citationSpans(TextSelection selection, DocxDocument document) {
        if (selection == null) return new int[0];
        String text = selection.text == null ? "" : selection.text;
        ArrayList<int[]> spans = new ArrayList<int[]>();
        ArrayList<Integer> section = referenceSection(document);
        for (TextSelection.Piece piece : selection.pieces) {
            int start = piece.submittedStart, end = piece.submittedStart + piece.original.length();
            if (end <= start) continue;
            DocxDocument.ParagraphBlock paragraph = document == null ? null : TextSelection.find(document, piece.paragraphIndex);
            boolean marked;
            if (paragraph == null) marked = referenceEntry(piece.original);
            else marked = TextProtection.referenceParagraph(paragraph) || referenceEntry(piece.original)
                    || section.contains(Integer.valueOf(piece.paragraphIndex))
                    && (referenceEntry(piece.original) || TextCorpus.citationLike(piece.original));
            if (marked) spans.add(new int[]{ start, end });
        }
        quotations(text, spans);
        return merge(spans, text.length());
    }
    /** Paragraph indexes that sit under a 参考文献 / References heading, until a blank line or next heading. */
    private static ArrayList<Integer> referenceSection(DocxDocument document) {
        ArrayList<Integer> out = new ArrayList<Integer>();
        if (document == null) return out;
        boolean inside = false;
        for (DocxDocument.ParagraphBlock paragraph : document.paragraphs) {
            String trimmed = paragraph.text.trim();
            boolean heading = referenceHeading(trimmed);
            if (paragraph.isHeading || heading) { inside = heading; continue; }
            if (trimmed.isEmpty()) { inside = false; continue; }
            if (inside) out.add(Integer.valueOf(paragraph.index));
        }
        return out;
    }
    private static boolean referenceHeading(String text) {
        String value = text.replace(" ", "").replace('\u3000', ' ').trim().toLowerCase(Locale.ROOT);
        return value.equals("参考文献") || value.equals("引用文献") || value.equals("参考书目") || value.equals("references")
                || value.equals("reference") || value.equals("bibliography") || value.equals("works cited");
    }
    /** Conservative entry shape: numbered or bracketed lead plus a publication year. */
    static boolean referenceEntry(String text) {
        String value = text == null ? "" : text.trim();
        if (value.length() < 20 || value.length() > 600) return false;
        int at = 0;
        boolean lead = value.charAt(0) == '[' || value.charAt(0) == '［';
        if (!lead) {
            int digits = 0;
            while (at < value.length() && value.charAt(at) >= '0' && value.charAt(at) <= '9') { at++; digits++; }
            lead = digits >= 1 && digits <= 3 && at < value.length() && (value.charAt(at) == ']' || value.charAt(at) == ')'
                    || value.charAt(at) == '）' || value.charAt(at) == '.' || value.charAt(at) == '、');
        }
        return lead && !yearIn(value).isEmpty();
    }
    private static String yearIn(String value) {
        for (int i = 0; i + 3 < value.length(); i++) {
            if (value.charAt(i) < '0' || value.charAt(i) > '9') continue;
            int digits = 0;
            while (i + digits < value.length() && value.charAt(i + digits) >= '0' && value.charAt(i + digits) <= '9') digits++;
            if (digits == 4) {
                int parsed = Integer.parseInt(value.substring(i, i + 4));
                if (parsed >= 1500 && parsed <= 2200) return String.valueOf(parsed);
            }
            i += digits;
        }
        return "";
    }
    private static final char[][] QUOTES = { { '\u201C', '\u201D' }, { '\u300C', '\u300D' }, { '\u300E', '\u300F' },
            { '"', '"' }, { '\u300A', '\u300B' } };
    private static void quotations(String text, ArrayList<int[]> spans) {
        for (int i = 0; i < QUOTES.length; i++) {
            int at = 0;
            while (true) {
                int start = text.indexOf(QUOTES[i][0], at);
                if (start < 0) break;
                int end = text.indexOf(QUOTES[i][1], start + 1);
                if (end < 0) break;
                int stop = Math.min(end + 1, text.length());
                if (stop - start >= 8 && stop - start <= 4000) spans.add(new int[]{ start, stop });
                at = stop;
            }
        }
    }
    private static int[] merge(ArrayList<int[]> spans, int length) {
        if (spans.isEmpty()) return new int[0];
        for (int[] span : spans) {
            if (span[0] < 0) span[0] = 0;
            if (span[1] > length) span[1] = length;
        }
        Collections.sort(spans, new Comparator<int[]>() {
            public int compare(int[] left, int[] right) { return left[0] != right[0] ? left[0] - right[0] : left[1] - right[1]; }
        });
        ArrayList<int[]> merged = new ArrayList<int[]>();
        for (int[] span : spans) {
            if (span[1] <= span[0]) continue;
            if (!merged.isEmpty() && span[0] - merged.get(merged.size() - 1)[1] <= 1)
                merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], span[1]);
            else merged.add(new int[]{ span[0], span[1] });
        }
        int[] out = new int[merged.size() * 2];
        for (int i = 0; i < merged.size(); i++) {
            out[i * 2] = merged.get(i)[0];
            out[i * 2 + 1] = merged.get(i)[1];
        }
        return out;
    }

    private static boolean cancelled(ApiClient.Cancellation cancellation) {
        return Thread.currentThread().isInterrupted() || cancellation != null && cancellation.cancelled();
    }
    private static void step(Progress progress, String label, int done, int total) {
        if (progress != null) progress.step(label, done, total);
    }
    /** 摘要侧的一个比较单元：一句摘要，加它属于哪篇候选。 */
    private static final class AbstractUnit {
        final TextCorpus.BagUnit unit;
        final String title, engine;
        AbstractUnit(TextCorpus.BagUnit unit, String title, String engine) {
            this.unit = unit;
            this.title = title == null ? "" : title;
            this.engine = engine == null ? "" : engine;
        }
    }

    /**
     * 摘要层（近似）。可比正文 0 篇的那一轮，检索并没有空手：真机实测知网回 20 条、维普回 20 条、
     * 万方回 2 条，给的全是题名 + 摘要。这一层不再假装在做正文级比对，而是改问一个答得出的问题：
     * **本文每一句，与这些摘要里的每一句，最像能像到什么程度**。
     *
     * <p>判据沿用正文级那一条袋判据（{@link TextCorpus#bagJudge}：字族门、长度门、三元组底数、
     * 袋 Dice 地板，一档不落），不另起一套；每句取 max，过线句数除以参与句数就是这一层的数——
     * 分子分母都是句子数，与正文级那三个"字符数"口径的比率不是一把尺，所以既不并入也不相加。
     *
     * <p>阈值不是拍的，是量出来的（artifacts/agent-solver/abstract-layer-gates.txt）：真稿 374 句
     * 对 82 篇同领域活摘要的实测天花板 0.585，无关领域真摘要 37 句对同一批摘要是 0.396，
     * 而"逐字相同"是 1.000、"逐字替换 25%"那一档最低 0.720。0.72 落在两条实测分界之间，
     * 也正是 SIMILAR_BAG_DICE 一直挂着的那一档；按句+0.14 的老规矩（0.585+0.14≈0.72）也是同一个数。
     *
     * <p>只吃 comparableMaterial == 摘要 的候选：抓到开放获取全文的那批已经在正文级语料里比过了，
     * 再算进这一层就是把同一个数报两遍。
     */
    /* 包内可见而不是 private：AbstractLayerRegression 直接拿这两堆量分界，不绕过产品那条判据。 */
    static void abstractLayer(Report report, String text, int[] excluded) {
        if (report.candidates.isEmpty() || text == null || text.isEmpty()) return;
        ArrayList<AbstractUnit> units = new ArrayList<AbstractUnit>();
        for (int i = 0; i < report.candidates.size(); i++) {
            PaperSources.Candidate candidate = report.candidates.get(i);
            if (candidate == null || !MATERIAL_ABSTRACT.equals(candidate.comparableMaterial)) continue;
            report.abstractCandidates++;
            TextCorpus.Source source = candidate.source == null ? new TextCorpus.Source() : candidate.source;
            String title = source.title == null ? "" : source.title.trim();
            /* 题名也是可比材料的一部分：学位论文的条目常把正文章节一起截进摘要里，题名本身照样能撞。 */
            String material = (title + (title.isEmpty() || title.endsWith("。") ? "" : "。")
                    + (candidate.abstractText == null ? "" : candidate.abstractText)).trim();
            for (int[] span : TextCorpus.sentences(material)) {
                String one = material.substring(span[0], span[1]).trim();
                if (one.length() < TextCorpus.MIN_SENTENCE_CHARS) continue;
                TextCorpus.BagUnit unit = TextCorpus.bagUnit(one);
                if (unit.chars < TextCorpus.MIN_SENTENCE_CHARS) continue;
                units.add(new AbstractUnit(unit, title, source.engine));
                if (units.size() >= MAX_ABSTRACT_UNITS) break;
            }
            if (units.size() >= MAX_ABSTRACT_UNITS) { report.abstractLayerTruncated = true; break; }
        }
        report.abstractUnits = units.size();
        if (units.isEmpty()) return;
        float floor = TextCorpus.bagFloor();
        ArrayList<AbstractHit> fired = new ArrayList<AbstractHit>();
        for (int[] span : TextCorpus.sentences(text)) {
            if (overlaps(excluded, span[0], span[1])) continue;
            String one = text.substring(span[0], span[1]).trim();
            if (one.length() < TextCorpus.MIN_SENTENCE_CHARS) continue;
            TextCorpus.BagUnit query = TextCorpus.bagUnit(one);
            if (query.chars < TextCorpus.MIN_SENTENCE_CHARS) continue;
            report.abstractSentencesCompared++;
            double best = 0d;
            AbstractUnit who = null;
            for (int u = 0; u < units.size(); u++) {
                AbstractUnit unit = units.get(u);
                float value = TextCorpus.bagJudge(query, unit.unit);
                if (value > best) { best = value; who = unit; }
            }
            if (who == null || best < floor) continue;
            AbstractHit hit = new AbstractHit();
            hit.start = span[0];
            hit.end = span[1];
            hit.score = best;
            hit.title = who.title;
            hit.engine = who.engine;
            hit.abstractSentence = who.unit.text;
            fired.add(hit);
        }
        report.abstractSentencesMatched = fired.size();
        report.abstractLayerRate = report.abstractSentencesCompared <= 0
                ? 0d : clamp(fired.size() * 100d / report.abstractSentencesCompared);
        /* 证据表按正文顺序画，但"最高那一句"必须在表里：先按分数留前 N 条，再按正文顺序重排。 */
        Collections.sort(fired, new Comparator<AbstractHit>() {
            public int compare(AbstractHit a, AbstractHit b) { return Double.compare(b.score, a.score); }
        });
        for (int i = 0; i < fired.size() && report.abstractHits.size() < MAX_ABSTRACT_EVIDENCE; i++)
            report.abstractHits.add(fired.get(i));
        Collections.sort(report.abstractHits, new Comparator<AbstractHit>() {
            public int compare(AbstractHit a, AbstractHit b) { return a.start - b.start; }
        });
        if (abstractLayerMeasured(report)) note(report, abstractLayerNote(report));
    }

    /** 这一段与排除区（引用段落、参考文献表、致谢、附录、目录）有没有重叠：摘要层的分母也不许吃这些。 */
    private static boolean overlaps(int[] spans, int start, int end) {
        for (int i = 0; i + 1 < spans.length; i += 2) if (start < spans[i + 1] && end > spans[i]) return true;
        return false;
    }

    private static void note(Report report, String value) {
        if (report.notes.size() < MAX_NOTES && !report.notes.contains(value)) report.notes.add(value);
    }
    private static int count(LinkedHashMap<String, Integer> counts, String engine) {
        Integer value = counts.get(engine);
        return value == null ? 0 : value.intValue();
    }
    private static void bump(LinkedHashMap<String, Integer> counts, String engine) {
        String name = engine == null ? "unknown" : engine;
        counts.put(name, Integer.valueOf(count(counts, name) + 1));
    }
    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value.trim();
    }
}

