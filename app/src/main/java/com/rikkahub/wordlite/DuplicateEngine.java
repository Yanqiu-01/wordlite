package com.rikkahub.wordlite;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Duplication and AIGC orchestration: citation marking, bounded retrieval, corpus match, one report. */
public final class DuplicateEngine {
    /* 检索短语上限 48 字：实测维普对 160 字的句子返回 0 条，同一篇摘要截到 40 字就返回 20 多条，
       OpenAlex / Crossref 这类源在短查询上也没有变差。 */
    static final int WINDOW_PARAGRAPHS = 3, MAX_WINDOWS = 24, MAX_PHRASE_CHARS = 48;
    /* MAX_WINDOWS 只是校验上限，与 EngineSettings.validate() 的 1-24 同源；运行时切几组窗口由
       limits.windows 说了算。MAX_REQUESTS 是"窗口数 x 源数"的真上限且默认可达：默认 9 个源 x 6 个
       窗口 = 54 次，用户把窗口拉到 24 就是 216 次，撞顶必须留字据。
       MAX_CORPUS_PAPERS 是语料侧的入库总闸，逐源上限取 limits.perEngine：9 个源 x 12 篇 = 108 <= 120。 */
    static final int MAX_REQUESTS = 120, MAX_FULL_TEXTS = 6, MAX_CORPUS_PAPERS = 120, MAX_NOTES = 40;
    /* 挂钟闸门与限速：实测一轮 9 个源约 10 秒（维普最慢 4062ms），但 Routes 的多路尝试能把单个
       请求拖到 20 秒以上，所以只设请求数上限挡不住慢网络，两个闸必须同时存在。 */
    static final long MAX_SEARCH_MILLIS = 180000L, MIN_ENGINE_GAP_MILLIS = 400L;
    /** 同一源被限流后整轮还允许的补试次数：实测 Semantic Scholar 匿名配额一进就 429。 */
    static final int MAX_THROTTLE_RETRIES = 1;
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
    public interface Progress { void step(String label, int done, int total); }
    public static final class Report {
        public double overallRate, excludingCitationsRate, selfWrittenRate, aigcRate;
        public int comparedChars, duplicateChars, citedDuplicateChars;
        /** 按论文结构排除在比对之外的字数（参考文献表、致谢、附录、目录）。 */
        public int excludedChars;
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
        /** 每个检索源被提问的窗口数，HTML 的「提问窗口数」列读它。 */
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
    }

    private DuplicateEngine() { }

    public static Report scan(TextSelection selection, TextCorpus corpus, boolean useWeb, ArrayList<String> engines,
                              PaperSources.Limits limits, ApiClient.Cancellation cancellation, Progress progress) {
        long started = System.nanoTime();
        Report report = new Report();
        String text = selection == null || selection.text == null ? "" : selection.text;
        report.sourceText = text;
        TextCorpus library = corpus == null ? new TextCorpus() : corpus;
        report.baseline = library;
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
            rates(report, matched);
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
        if (!report.candidates.isEmpty()) return;
        if (cancelled(cancellation)) gap(report, GAP_CANCELLED);
        else if (aborted) gap(report, GAP_NOTHING_RETRIEVED);
    }
    /** The first named gap wins, so the sharpest reason is the one search() found. */
    private static void gap(Report report, String reason) {
        if (report.retrievalIncomplete) return;
        report.retrievalIncomplete = true;
        report.retrievalReason = reason;
    }

    private static void rates(Report report, TextCorpus.Report matched) {
        if (matched != null) {
            report.hits.addAll(matched.hits);
            report.comparedChars = matched.comparedChars;
            report.duplicateChars = matched.duplicateChars;
            report.citedDuplicateChars = matched.citedDuplicateChars;
            report.excludedChars = matched.excludedChars;
            report.overallRate = clamp(matched.overallRate);
            report.excludingCitationsRate = clamp(matched.excludingCitationsRate);
            for (Map.Entry<String, Double> entry : matched.byEngine.entrySet())
                report.byEngine.put(entry.getKey(), clamp(entry.getValue() == null ? 0 : entry.getValue().doubleValue()));
        }
        if (report.aigc != null) {
            report.aigcRate = clamp(report.aigc.rate);
            report.aigcInsufficient = report.aigc.insufficientSample;
            report.aigcVerdict = report.aigc.verdict == null ? "" : report.aigc.verdict;
            int flagged = 0;
            for (AigcDetector.Sentence sentence : report.aigc.sentences)
                if (sentence.score >= 0.5f) flagged += Math.max(0, sentence.end - sentence.start);
            int base = report.comparedChars > 0 ? report.comparedChars : report.aigc.comparedChars;
            double share = base > 0 ? flagged * 100d / base : report.aigcRate;
            // 样本不足时那份倾向连自编率都不该拉动。
            if (report.aigcInsufficient) share = 0d;
            report.selfWrittenRate = clamp(100 - report.overallRate - clamp(share));
        } else report.selfWrittenRate = clamp(100 - report.overallRate);
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
        /* perEngine 只管"每次向该源要几条"；逐源入库上限取同一个数，9 个源 x 12 篇 = 108 篇，
           与 MAX_CORPUS_PAPERS = 120 自洽，不必再拍第三个数。 */
        int perSourceCap = Math.max(1, limits.perEngine);
        int poolCap = Math.max(60, 8 * perSourceCap * engines.size());
        long deadline = System.currentTimeMillis() + Math.max(1L, searchMillis);
        long gap = Math.max(0L, engineGapMillis);
        LinkedHashMap<String, Long> lastAsk = new LinkedHashMap<String, Long>();
        LinkedHashMap<String, Boolean> askedPhrase = new LinkedHashMap<String, Boolean>();
        LinkedHashMap<String, Boolean> poolKeys = new LinkedHashMap<String, Boolean>();
        LinkedHashMap<String, Boolean> skipped = new LinkedHashMap<String, Boolean>();
        LinkedHashMap<String, Boolean> exhausted = new LinkedHashMap<String, Boolean>();
        LinkedHashMap<String, Boolean> silent = new LinkedHashMap<String, Boolean>();
        LinkedHashMap<String, Boolean> failedLast = new LinkedHashMap<String, Boolean>();
        LinkedHashMap<String, Integer> retries = new LinkedHashMap<String, Integer>();
        LinkedHashMap<String, Integer> quiet = new LinkedHashMap<String, Integer>();
        ArrayList<PaperSources.Candidate> pool = new ArrayList<PaperSources.Candidate>();
        int requests = 0, poolDropped = 0, done = 0, total = planned * engines.size();
        boolean requestCap = false, timeCap = false;
        for (int w = 0; w < planned; w++) {
            if (cancelled(cancellation)) { note(report, "检索已取消，结果只覆盖已完成的窗口"); break; }
            String phrase = PaperSources.queryPhrase(plan.groups.get(w), MAX_PHRASE_CHARS);
            LinkedHashMap<String, Integer> gained = new LinkedHashMap<String, Integer>();
            LinkedHashMap<String, Boolean> askedNow = new LinkedHashMap<String, Boolean>();
            boolean asked = false, poolFull = false;
            for (String engine : engines) {
                if (phrase.isEmpty() || Boolean.TRUE.equals(skipped.get(engine))
                        || Boolean.TRUE.equals(exhausted.get(engine))) continue;
                if (requests >= MAX_REQUESTS) { requestCap = true; break; }
                /* 剩余额度连一次带超时的请求都放不下就不再发：那是发出去必死的请求。 */
                if (deadline - System.currentTimeMillis() <= limits.timeoutSeconds * 1000L + 1000L) { timeCap = true; break; }
                /* 相邻窗口的重复段落会凑出同一个 48 字短语，这种请求纯属白送。 */
                if (Boolean.TRUE.equals(askedPhrase.get(engine + "|" + phrase))) continue;
                waitTurn(lastAsk, engine, gap, deadline, cancellation);
                askedPhrase.put(engine + "|" + phrase, Boolean.TRUE);
                askedNow.put(engine, Boolean.TRUE);
                asked = true;
                requests++;
                bump(report.windowsAsked, engine);
                step(progress, "检索 " + PaperSources.label(engine), ++done, total);
                try {
                    ArrayList<PaperSources.Candidate> found = PaperSources.search(engine, phrase, limits, cancellation);
                    failedLast.remove(engine);
                    for (PaperSources.Candidate candidate : found) {
                        String key = poolKey(engine, candidate);
                        if (key.isEmpty()) continue;
                        if (pool.size() >= poolCap) { poolDropped++; poolFull = true; continue; }
                        if (Boolean.TRUE.equals(poolKeys.get(key))) continue;
                        poolKeys.put(key, Boolean.TRUE);
                        pool.add(candidate);
                        bump(gained, engine);
                    }
                } catch (IllegalArgumentException error) {
                    skipped.put(engine, Boolean.TRUE); failedLast.put(engine, Boolean.TRUE);
                    note(report, "已跳过 " + PaperSources.label(engine) + "：" + message(error));
                } catch (IOException error) {
                    /* 429 与"这个源挂了"不是一回事：匿名共享配额是暂时的，一次失败就整轮放弃太狠。
                       判据用注记前缀而不是状态码，因为 PaperSources.search() 只往上抛 IOException。 */
                    failedLast.put(engine, Boolean.TRUE);
                    boolean throttled = message(error).startsWith(THROTTLED_PREFIX)
                            && count(retries, engine) < MAX_THROTTLE_RETRIES;
                    if (throttled) {
                        bump(retries, engine);
                        note(report, "已重试 " + PaperSources.label(engine) + "：" + message(error));
                    } else {
                        skipped.put(engine, Boolean.TRUE);
                        note(report, "已跳过 " + PaperSources.label(engine) + "：" + message(error));
                    }
                }
            }
            /* 取尽判据：同一个源连着两个窗口一篇新的都没多，就别再花配额问它。池子被我们自己
               装满的那些轮不算它沉默，否则是把内存上限伪装成源的意愿。 */
            if (!poolFull) for (String engine : engines) {
                if (!Boolean.TRUE.equals(askedNow.get(engine))) continue;
                /* 刚报错的源不配被说成"已取尽"：它没有沉默，是失败了，"本次不可用"才是它的账。 */
                if (Boolean.TRUE.equals(failedLast.get(engine))) continue;
                if (count(gained, engine) > 0) { quiet.put(engine, Integer.valueOf(0)); silent.remove(engine); continue; }
                silent.put(engine, Boolean.TRUE);
                int streak = count(quiet, engine) + 1;
                quiet.put(engine, Integer.valueOf(streak));
                if (streak >= 2) exhausted.put(engine, Boolean.TRUE);
            }
            if (asked) { report.windowsRetrieved++; report.coveredChars += plan.chars.get(w).intValue(); }
            if (requestCap || timeCap) break;
        }
        phaseB(text, corpus, report, engines, limits, cancellation, pool, skipped, failedLast, exhausted, silent,
                requestCap, timeCap, poolCap, poolDropped, deadline);
    }

    /**
     * 第二阶段：跨源合并 -> BM25 名次 -> 按名次入库。全文额度也跟着名次走，
     * 不再是谁先回来谁先抓。
     */
    private static void phaseB(String text, TextCorpus corpus, Report report, ArrayList<String> engines,
                               PaperSources.Limits limits, ApiClient.Cancellation cancellation,
                               ArrayList<PaperSources.Candidate> pool, LinkedHashMap<String, Boolean> skipped,
                               LinkedHashMap<String, Boolean> failedLast, LinkedHashMap<String, Boolean> exhausted,
                               LinkedHashMap<String, Boolean> silent, boolean requestCap, boolean timeCap,
                               int poolCap, int poolDropped, long deadline) {
        CandidateRanker.Dedup merged = CandidateRanker.dedup(pool);
        report.mergedDuplicates = merged.merges.size();
        report.merges.addAll(merged.merges);
        /* 排序用整篇文档的检索短语：BM25 在这儿回答的是"这篇候选与本文整体对不对题"，
           拿第 7 窗口的串排序会让一句口语式过渡句把无关论文抬进语料头部并吃掉全文额度。 */
        String query = PaperSources.queryPhrase(text, MAX_PHRASE_CHARS);
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
        int grouped = 0, limit = cap < 1 ? 1 : cap;
        for (int i = 0; i < kept.size(); i++) {
            int[] span = spans.get(i);
            int chars = TextCorpus.validCount(norm, span[0], span[1]);
            out.comparableChars += chars;
            if (group.length() > 0) group.append(' ');
            group.append(kept.get(i));
            grouped += chars;
            if (i % WINDOW_PARAGRAPHS != WINDOW_PARAGRAPHS - 1 && i != kept.size() - 1) continue;
            /* 超出上限的组不再追加：设置里的窗口数就是用户许给这次检索的提问次数，
               切得出多少组是文档的事，问不问是设置的事，两个数各记各的。 */
            if (out.groups.size() >= limit) continue;
            out.groups.add(group.toString());
            out.chars.add(Integer.valueOf(grouped));
            group.setLength(0);
            grouped = 0;
        }
        return out;
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
