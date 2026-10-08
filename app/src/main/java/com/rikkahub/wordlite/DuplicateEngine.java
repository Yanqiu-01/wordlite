package com.rikkahub.wordlite;

import java.io.IOException;
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
    /** Retrieval gaps phrased with the note(...) vocabulary so the headline and the notes never disagree. */
    static final String GAP_NOTHING_RETRIEVED = "联网检索没有取回可比对的候选文献";
    static final String GAP_CANCELLED = "检索已取消，没有联网取候选文献";
    static final String GAP_NO_SOURCE = "没有可用的检索源，本次没有执行联网检索";
    static final String GAP_EMPTY_LIBRARY = "未启用联网检索，自建库为空，没有可比对的语料";
    /* 问到了条目不等于问到了可比正文（2.2.0）。真机最常落的正是这一档：知网、万方、维普只回摘要，
       摘要里没有被抄的那段正文，判据一句也认不出，报告却照印 0.00%、状态还是"完整检索"。
       这一条把那一档降级成未完成查重：判据与阈值一个字没改，改的是"没得比"不许冒充"没重复"。 */
    static final String GAP_NO_COMPARABLE = "没有任何可比正文，相似度量不到";
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
        /** 设置允许本次做的窗口组数 = min(windowsAvailable, limits.windows)。 */
        public int windowsPlanned;
        /** 真正发出过请求的窗口组数：同一窗口只要有任一源被问过就算。 */
        public int windowsRetrieved;
        /** 已检索窗口覆盖到的正文字数，口径 TextCorpus.validCount(normalize(text), from, to)。 */
        public int coveredChars;
        /** 可检索的正文总字数：retrievable() 通过的段落字数；结构性文本另计 excludedChars。 */
        public int comparableChars;
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
        reason.append("联网检索取回 ").append(report.candidates.size()).append(" 条候选，可比正文 0 篇")
                .append("（只有题录 ").append(report.recordOnlyCandidates).append(" 篇，与检索词零共同词 ")
                .append(report.unrankedCandidates).append(" 篇）");
        if (report.windowsRetrieved < report.windowsPlanned)
            reason.append("，检索也只跑了 ").append(report.windowsRetrieved)
                    .append("/").append(report.windowsPlanned).append(" 个窗口");
        reason.append("，").append(GAP_NO_COMPARABLE)
                .append("：把检索设置的窗口数调大、允许开放获取全文抓取，"
                        + "或先把疑似来源的原文导入自建库再查一次");
        return reason.toString();
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
    private static void search(String text, TextCorpus corpus, Report report, ArrayList<String> engines,
                               PaperSources.Limits limits, ApiClient.Cancellation cancellation, Progress progress) {
        if (engines.isEmpty()) return;
        WindowPlan plan = windowPlan(text, Integer.MAX_VALUE);
        report.windowsAvailable = plan.groups.size();
        report.comparableChars = plan.comparableChars;
        if (plan.groups.isEmpty()) { note(report, "正文没有可用于检索的段落"); return; }
        int planned = Math.min(plan.groups.size(), Math.max(1, Math.min(limits.windows, MAX_WINDOWS)));
        report.windowsPlanned = planned;
        int perSourceCap = Math.max(1, limits.perEngine);
        int poolCap = Math.max(60, 8 * perSourceCap * engines.size());
        /* 检索式在起线程之前全部算好：泳道之间只共用这一排算完的检索式与候选池，
           谁都不把自己的检索现场摊给别的线程看。 */
        /* 响应留档（A4）先建账本再起泳道：每一次请求都要落一行，成功也要落。
           Limits 是调用方递进来的，本轮的出口不往它身上挂——先复制一份再挂（PaperSources.Limits#copy）。 */
        ShapeLedger shapes = new ShapeLedger(report);
        PaperSources.Limits pass = limits.copy();
        pass.shapes = shapes;
        Sweep sweep = new Sweep(pass, shapes, cancellation, progress, planned, poolCap,
                Math.max(0L, engineGapMillis), System.currentTimeMillis() + Math.max(1L, searchMillis),
                planned * engines.size());
        for (int w = 0; w < planned; w++)
            sweep.addWindow(windowProbes(plan.members.get(w)), plan.chars.get(w).intValue());
        ArrayList<Lane> lanes = lanes(sweep, engines);
        runLanes(lanes);
        /* 注记与逐源状态等全部泳道收工后，按设置里的源顺序并回来：谁先回来不得影响注记顺序。 */
        LinkedHashMap<String, Boolean> failed = new LinkedHashMap<String, Boolean>();
        LinkedHashMap<String, Boolean> rawSkipped = new LinkedHashMap<String, Boolean>();
        LinkedHashMap<String, Boolean> rawExhausted = new LinkedHashMap<String, Boolean>();
        LinkedHashMap<String, Boolean> rawSilent = new LinkedHashMap<String, Boolean>();
        boolean requestCap = false, timeCap = false;
        for (int i = 0; i < lanes.size(); i++) {
            Lane lane = lanes.get(i);
            rawSkipped.putAll(lane.skipped);
            rawExhausted.putAll(lane.exhausted);
            rawSilent.putAll(lane.silent);
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
        if (cancelled(cancellation)) note(report, "检索已取消，结果只覆盖已完成的窗口");
        report.windowsRetrieved = sweep.windowsRetrieved();
        report.coveredChars = sweep.coveredChars();
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
        final ArrayList<ArrayList<String>> probes = new ArrayList<ArrayList<String>>();
        final ArrayList<Integer> chars = new ArrayList<Integer>();
        final ArrayList<PaperSources.Candidate> pool = new ArrayList<PaperSources.Candidate>();
        final Object lock = new Object();
        /** 主线程被打断时置位：泳道在每一扇窗口、每一次请求之前都看它一眼。 */
        volatile boolean stop;
        private final LinkedHashMap<String, Boolean> poolKeys = new LinkedHashMap<String, Boolean>();
        private final ArrayList<Boolean[]> sent = new ArrayList<Boolean[]>();
        private boolean[] askedWindow;
        private int dropped;
        private final AtomicInteger done = new AtomicInteger();
        Sweep(PaperSources.Limits limits, ShapeLedger shapes, ApiClient.Cancellation cancellation,
              Progress progress, int planned, int poolCap, long gap, long deadline, int total) {
            this.limits = limits; this.shapes = shapes;
            this.cancellation = cancellation; this.progress = progress;
            this.planned = planned; this.poolCap = poolCap; this.gap = gap; this.deadline = deadline;
            this.total = total; this.askedWindow = new boolean[planned];
        }
        void addWindow(ArrayList<String> phrases, int validChars) {
            synchronized (lock) {
                probes.add(phrases);
                chars.add(Integer.valueOf(validChars));
                sent.add(new Boolean[phrases.size()]);
            }
        }
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
        /** 记"这一扇窗口的这一条检索式真的问出去了"：覆盖字数与排序式都从这儿出。 */
        void sent(int window, int probe) {
            synchronized (lock) {
                askedWindow[window] = true;
                sent.get(window)[probe] = Boolean.TRUE;
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
        final ArrayList<String> engines; int quota;
        final LinkedHashMap<String, Boolean> skipped = new LinkedHashMap<String, Boolean>();
        final LinkedHashMap<String, Boolean> exhausted = new LinkedHashMap<String, Boolean>();
        final LinkedHashMap<String, Boolean> silent = new LinkedHashMap<String, Boolean>();
        final LinkedHashMap<String, Boolean> failedLast = new LinkedHashMap<String, Boolean>();
        final ArrayList<String> notes = new ArrayList<String>();
        boolean quotaCap, timeCap;
        private final Sweep sweep;
        private int asks;
        private final LinkedHashMap<String, Long> lastAsk = new LinkedHashMap<String, Long>();
        private final LinkedHashMap<String, Boolean> askedPhrase = new LinkedHashMap<String, Boolean>();
        private final LinkedHashMap<String, Integer> gained = new LinkedHashMap<String, Integer>();
        private final LinkedHashMap<String, Integer> quiet = new LinkedHashMap<String, Integer>();
        private final LinkedHashMap<String, Integer> retries = new LinkedHashMap<String, Integer>();
        private final LinkedHashMap<String, Integer> askedBy = new LinkedHashMap<String, Integer>();
        private final LinkedHashMap<String, Boolean> askedNow = new LinkedHashMap<String, Boolean>();
        Lane(Sweep sweep, ArrayList<String> engines, int quota) {
            this.sweep = sweep; this.engines = engines; this.quota = quota;
        }
        int asksOf(String engine) { return count(askedBy, engine); }
        public void run() {
            for (int w = 0; w < sweep.planned; w++) {
                if (cancelled(sweep.cancellation) || sweep.stop) return;
                if (asks >= quota) { quotaCap = true; return; }
                ArrayList<String> phrases = sweep.probes.get(w);
                boolean poolFull = false;
                askedNow.clear();
                gained.clear();
                for (int p = 0; p < phrases.size() && !quotaCap && !timeCap; p++) {
                    String phrase = phrases.get(p);
                    if (phrase.isEmpty()) continue;
                    for (int e = 0; e < engines.size(); e++) {
                        String engine = engines.get(e);
                        if (Boolean.TRUE.equals(skipped.get(engine))
                                || Boolean.TRUE.equals(exhausted.get(engine))) continue;
                        if (asks >= quota) { quotaCap = true; break; }
                        /* 剩余额度连一次带超时的请求都放不下就不再发：那是发出去必死的请求。 */
                        if (sweep.deadline - System.currentTimeMillis()
                                <= sweep.limits.timeoutSeconds * 1000L + 1000L) { timeCap = true; break; }
                        /* 相邻窗口的重复段落会凑出同一个 48 字短语，这种请求纯属白送。 */
                        if (Boolean.TRUE.equals(askedPhrase.get(engine + "|" + phrase))) continue;
                        waitTurn(lastAsk, engine, sweep.gap, sweep.deadline, sweep.cancellation);
                        askedPhrase.put(engine + "|" + phrase, Boolean.TRUE);
                        askedNow.put(engine, Boolean.TRUE);
                        bump(askedBy, engine);
                        asks++;
                        sweep.sent(w, p);
                        sweep.step("检索 " + PaperSources.label(engine));
                        int shapesBefore = sweep.shapes.mark();
                        try {
                            ArrayList<PaperSources.Candidate> found =
                                    PaperSources.search(engine, phrase, sweep.limits, sweep.cancellation);
                            failedLast.remove(engine);
                            poolFull = poolFull | sweep.collect(engine, found, gained);
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
                    }
                }
                /* 取尽判据原样留在"同一个源"这一格：连着两个窗口一篇新的都没多就不再花配额问它。
                   池子被我们自己装满的那些轮不算它沉默，否则是把内存上限伪装成源的意愿。 */
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
    }

    /**
     * 泳道怎么分：源按轮转进道（第 i 个源进第 i mod 道数 条），总配额按泳道里源数的份额切，
     * 余数发给前面的泳道，各道加起来正好等于 MAX_REQUESTS。
     *
     * 为什么是轮转而不是切段：真机上慢的永远是同几个源（维普 4062ms、知网匿名口十几秒），
     * 按顺序切段会把它们全挤进同一条泳道，那条泳道一慢就等于那个源整轮没被问——
     * 那正是原来串行实现的病，不能换个名字再犯一遍。
     */
    private static ArrayList<Lane> lanes(Sweep sweep, ArrayList<String> engines) {
        int count = Math.min(engines.size(), MAX_LANES);
        ArrayList<ArrayList<String>> grouped = new ArrayList<ArrayList<String>>();
        for (int i = 0; i < count; i++) grouped.add(new ArrayList<String>());
        for (int i = 0; i < engines.size(); i++) grouped.get(i % count).add(engines.get(i));
        ArrayList<Lane> out = new ArrayList<Lane>();
        int assigned = 0;
        for (int i = 0; i < count; i++) {
            int share = (int) ((long) MAX_REQUESTS * grouped.get(i).size() / engines.size());
            out.add(new Lane(sweep, grouped.get(i), share));
            assigned += share;
        }
        for (int i = 0; i < count && assigned < MAX_REQUESTS; i++) {
            out.get(i).quota = out.get(i).quota + 1;
            assigned++;
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
    private static void disclose(Report report, LinkedHashMap<String, Boolean> silent,
                                 LinkedHashMap<String, Boolean> failedLast, LinkedHashMap<String, Boolean> exhausted,
                                 boolean requestCap, boolean timeCap, boolean corpusCap, int poolCap, int poolDropped) {
        double rate = report.comparableChars <= 0 ? 0d : report.coveredChars * 100d / report.comparableChars;
        int left = report.windowsPlanned - report.windowsRetrieved;
        note(report, "已检索 " + report.windowsRetrieved + "/" + report.windowsAvailable + " 个检索窗口，覆盖 "
                + report.coveredChars + " 字（全文可比对 " + report.comparableChars + " 字，" + percent(rate) + "）");
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
        if (corpusCap && gate.isEmpty()) {
            /* 语料闸拦住的是"取回的比入库的多"，跟窗口没跑完是两件事，理由不能借隔壁那句。 */
            gate = "候选文献已达上限 " + MAX_CORPUS_PAPERS + " 篇，检索到的其余候选未入库";
        }
        if (exhausted.size() > 0)
            note(report, exhausted.size() + " 个检索源已取尽（连续两个窗口没有新增文献），后续窗口未再提问");
        /* 口径：按"最后一次尝试失败的源"计，不按失败次数累加——限流补试会让同一个源失败两次。 */
        if (!failedLast.isEmpty()) note(report, failedLast.size() + " 个检索源本次不可用");
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
            reason = "检索窗口数按设置在 " + report.windowsPlanned + " 段处截断，全文还可切出 "
                    + (report.windowsAvailable - report.windowsPlanned) + " 段；把检索设置的窗口数调到 24 可扩大覆盖";
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
        if (report.candidates.isEmpty() && !engines.isEmpty() && skipped.size() >= engines.size())
            gap(report, engines.size() + " 个检索源本次全部不可用，" + GAP_NOTHING_RETRIEVED);
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
        /** 与 groups 一一对应：这一扇窗口里那几段的原文。组串只是检索式的原料，段落自己那句才是探针要护住的东西。 */
        final ArrayList<ArrayList<String>> members = new ArrayList<ArrayList<String>>();
        /** retrievable() 通过的段落总字数：文档覆盖率的分母。 */
        int comparableChars;
    }

    /**
     * 段落 -> 三段落一组的窗口，并算出每组的覆盖字数。normalize() 逐字符等长，所以原文偏移
     * 可以直接搬到归一化串上做 validCount，不必再切一遍文本。
     */
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
        int grouped = 0, limit = cap < 1 ? 1 : cap;
        for (int i = 0; i < kept.size(); i++) {
            int[] span = spans.get(i);
            int chars = TextCorpus.validCount(norm, span[0], span[1]);
            out.comparableChars += chars;
            if (group.length() > 0) group.append(' ');
            group.append(kept.get(i));
            member.add(kept.get(i));
            grouped += chars;
            if (i % WINDOW_PARAGRAPHS != WINDOW_PARAGRAPHS - 1 && i != kept.size() - 1) continue;
            /* 超出上限的组不再追加：设置里的窗口数就是用户许给这次检索的提问次数，
               切得出多少组是文档的事，问不问是设置的事，两个数各记各的。 */
            if (out.groups.size() >= limit) continue;
            out.groups.add(group.toString());
            out.chars.add(Integer.valueOf(grouped));
            out.members.add(new ArrayList<String>(member));
            group.setLength(0);
            member.clear();
            grouped = 0;
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

