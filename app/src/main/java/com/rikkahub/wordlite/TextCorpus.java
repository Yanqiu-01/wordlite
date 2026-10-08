package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 本机查重指纹库：归一化、句子切分、字符 n-gram 与 MinHash 指纹、倒排候选、Dice 判定与重复率统计。 */
public final class TextCorpus {
    /** 有效字符数少于该值的句子不参与匹配。 */
    public static final int MIN_SENTENCE_CHARS = 12;
    /**
     * 判定相似所需的字符三元组 Dice 下限。0.60 是当年凭三个手搓例句定的，标定台在真实语料上扫过
     * 之后发现它偏紧：同篇论文内部的负例（术语全撞车那种最难判的）和从 CNKI 检索页上摘下来的
     * 别领域论文摘要片段，误报率从 0.50 到 0.85 整段都是 0.0%，而 0.60 要在句级少认回 5/120 条改写句、
     * 引擎级召回少 1.2 个点。全部表格见 docs/detection-calibration.md。
     *
     * 0.55 降到 0.50 是抗改写实测台逼出来的（docs/rewrite-robustness.md）。当初留 0.55 的理由是
     * "0.50 与 0.55 实测逐位相同，多留一档余量给真实文库"，那句话只在原文与常规改写的样本上成立：
     * 口径换成逐字替换之后两档不再相同——同样 20 段种入、同样 85 句同领域负例，替换掉两成汉字时召回
     * 19.2% 对 26.4%，分句抽稀 96.2% 对 97.8%，而两边的误报都还是零。同一批负例实测能摸到的最高
     * Dice 是 0.358，且出现在 50-80 字的长句桶里而不是短句桶里，所以 0.50 头顶仍有 0.14 的空档。
     *
     * 停在 0.50 而不去 0.45：0.45 还能再多认回约 5 个点，但头顶空档只剩 0.09，而文库上了规模之后
     * 偶发撞车要吃的正是这一档。等真实文库够大、负例天花板仍压在 0.36 以下，再往 0.45 走。
     */
    public static final float SIMILAR_DICE = 0.50f;
    /** 长短悬殊时改用的三元组包含率下限：整句原文嵌进改写过的长句。标定台上这一列不构成约束——
     * 0.80 到 1.00 逐位相同，因为能配上的句子长短本来就接近；留着它是因为整句原文嵌进长句那种写法只有它抓得住。
     */
    public static final float SIMILAR_CONTAINMENT = 0.90f;

    /**
     * 标定台用的临时覆盖。默认就是上面那两个产品值，产品路径一次也不碰它，跑完必须还原；
     * 有了这个口子，阈值才能在真实语料上扫而不是写死之后凭感觉调（见 tools/detect-calibration.ps1）。
     */
    private static float diceFloor = SIMILAR_DICE, containmentFloor = SIMILAR_CONTAINMENT;

    static void overrideThresholds(float dice, float containment) {
        diceFloor = dice;
        containmentFloor = containment;
    }

    static void restoreThresholds() {
        diceFloor = SIMILAR_DICE;
        containmentFloor = SIMILAR_CONTAINMENT;
    }
    /** 精算 Dice 的候选句上限。 */
    public static final int MAX_CANDIDATES = 400;
    /** 间隔不超过该字符数的相邻命中并为一段。 */
    public static final int MERGE_GAP = 2;
    /** 同一来源才并段，避免把两个来源混进一段高亮。 */
    public static final int WINDOW_CHARS = 512;
    private static final int WINDOW_OVERLAP = 64;
    private static final int SIGNATURE_SLOTS = 16;
    /** 超过该 posting 长度的三元组视为停用词，不参与候选累计。 */
    private static final int STOP_POSTINGS = 192;
    private static final int POSTING_CAP = STOP_POSTINGS * 2;
    /** 单句匹配最多扫描的 posting 条数，保证最坏情形开销有界。 */
    private static final int POSTING_SCAN_CAP = 6000;
    private static final int MIN_SHARED_GRAMS = 3;
    private static final int MAX_CORPUS_SENTENCES = 40000;
    /** 指纹索引的 token 上限，超了就只保留句级比对。 */
    private static final int MAX_FINGERPRINT_TOKENS = 1200000;
    /** 一篇文献至少要共享两枚指纹才值得去验证，一枚多半是巧合。 */
    private static final int MIN_SHARED_FINGERPRINTS = 3;
    private static final int MAX_ANCHORS_PER_DOC = 4000;
    /**
     * 精算 Dice 前使用的 Dice 上界下限。包含式判定自己就要求 Dice >= 0.5，所以按这一档裁剪不会漏报；
     * 但句级 Dice 下限是活的（diceFloor，标定台会临时压低它），裁剪必须跟着那个活值取小：
     * 下限一旦降到 0.5 以下，还按 0.5 裁就把 0.4~0.5 之间该复核的候选悄悄扔掉了，而那种漏查处处都是
     * 又查不出来。产品值 0.50 与这一档相等，所以这条 min 今天不改变任何一次判定。
     */
    private static final float LENGTH_BOUND_FLOOR = 0.5f;

    public static final class Source {
        public String id = "", title = "", authors = "", year = "", locator = "", engine = "";
    }

    /** 被检文本上的一段相似区间，偏移是 match() 传入文本的 UTF-16 下标。 */
    public static final class Hit {
        public int start, end;
        public float score;
        public Source source;
    }

    public static final class Report {
        public final ArrayList<Hit> hits = new ArrayList<Hit>();
        /**
         * 分母：排除区之外的一切有效字符，与 CharLedger.totalChars 是同一个算式。0.7.2 之前这里数的是
         * "切得出比对片段的句子"，短到切不出片段的句子既不进分子也不进分母，真文档
         * tests/samples/input-liu.docx 实测比账本分母少 946 个字（14962 对 15908）。
         */
        public int comparedChars;
        /** 分子：hits 里那些区间取并集之后的有效字符，一个字符只认一次（重叠的那段先占者得）。 */
        public int duplicateChars;
        /** 分子里落在引用区间内的部分，同样是并集口径，所以总相似度比 = 去除引用比 + 引用重复比。 */
        public int citedDuplicateChars;
        /** 参考文献表、致谢这类结构性文本占的有效字符：与 comparedChars 是同一次划分的两边，加起来等于全文。 */
        public int excludedChars;
        public double overallRate, excludingCitationsRate;
        /**
         * 每个检索源的重复字符占比。一个字符至多挂在一个检索源名下（hits 两两不重叠，见 attributeHits），
         * 所以把这里的百分比乘回分母再相加 == duplicateChars == CharLedger.duplicateChars，
         * 与来源榜那张表共用同一把尺；被别篇抢走多少由 disputedChars 那条注记说，不在这里重复扣。
         */
        public final LinkedHashMap<String, Double> byEngine = new LinkedHashMap<String, Double>();
    }

    private static final class Entry {
        final String key;
        final long[] grams;
        final long[] signature;
        final int sourceIndex;
        final int chars;
        Entry(String key, long[] grams, long[] signature, int sourceIndex, int chars) {
            this.key = key;
            this.grams = grams;
            this.signature = signature;
            this.sourceIndex = sourceIndex;
            this.chars = chars;
        }
    }

    /** 折叠串 + 原串偏移映射；start/end 直接落在 match() 的入参文本上。 */
    private static final class Frag {
        String key;
        int[] map;
        long[] grams;
        long[] signature;
        int chars;
        int start, end;
    }

    private static final class Match {
        int entryId;
        float score;
    }

    private final ArrayList<Source> sources = new ArrayList<Source>();
    private final ArrayList<Entry> entries = new ArrayList<Entry>();
    private final GramIndex index = new GramIndex();
    /** 原文窗口折叠串 -> 句子 id：逐字相同的抄袭不依赖倒排，重复度极高的文本也不会漏报。 */
    private final HashMap<String, Integer> exact = new HashMap<String, Integer>();
    private int skippedSentences;
    /**
     * 上一次 match() 里同一段字符被两篇以上文献命中的字符数（有效字符口径：把所有"被别篇先占走"的区间
     * 先取并集再数字，同一段字符被三篇抢也只算一次）。指纹带之间的抢段与句级命中之间被滑窗切出来的重叠
     * 都记在这里；只供 DuplicateEngine 写一条注记，让用户能正确读出来源榜里那个 0，
     * 不参与判据、不进任何比率、也不进分子。
     */
    private int disputedOverlap;

    public TextCorpus() { }

    /** 追加一个来源的文本；过短句子直接丢弃，不参与匹配。 */
    public void add(Source source, String text) {
        if (text == null || text.length() == 0) return;
        sources.add(source == null ? new Source() : source);
        int sourceIndex = sources.size() - 1;
        indexFingerprints(sourceIndex, text);
        String norm = normalize(text);
        ArrayList<int[]> spans = sentences(text);
        for (int i = 0; i < spans.size(); i++) {
            if (entries.size() >= MAX_CORPUS_SENTENCES) return;
            int[] span = spans.get(i);
            ArrayList<Frag> pieces = fragments(norm, span[0], span[1]);
            if (pieces.isEmpty()) { skippedSentences++; continue; }
            for (int f = 0; f < pieces.size(); f++) {
                if (entries.size() >= MAX_CORPUS_SENTENCES) return;
                Frag frag = pieces.get(f);
                Entry entry = new Entry(frag.key, frag.grams, signature(frag.grams), sourceIndex, frag.chars);
                int entryId = entries.size();
                entries.add(entry);
                index.add(entry.grams, entryId);
                if (!exact.containsKey(frag.key)) exact.put(frag.key, Integer.valueOf(entryId));
            }
        }
    }

    /** 每篇文献一份归一化全文与 token 下标；指纹 -> {docId, token 下标} 的倒排。 */
    private final ArrayList<String> docNorms = new ArrayList<String>();
    private final ArrayList<int[]> docTokens = new ArrayList<int[]>();
    private final ArrayList<Integer> docSource = new ArrayList<Integer>();
    private final HashMap<Long, ArrayList<int[]>> fingerprints = new HashMap<Long, ArrayList<int[]>>();
    private int fingerprintTokens;

    /** winnowing 取样结果进倒排，一篇文献一份全文，跨句复制才追得到。 */
    private void indexFingerprints(int sourceIndex, String text) {
        if (fingerprintTokens >= MAX_FINGERPRINT_TOKENS || text == null) return;
        String flat = normalize(text);
        int[] at = Fingerprints.tokens(flat);
        if (at.length < Fingerprints.MIN_MATCH) return;
        long[] hashes = Fingerprints.rolling(flat, at);
        int[] picked = Fingerprints.sample(hashes);
        int docId = docNorms.size();
        docNorms.add(flat);
        docTokens.add(at);
        docSource.add(Integer.valueOf(sourceIndex));
        for (int i = 0; i < picked.length; i++) {
            Long key = Long.valueOf(hashes[picked[i]]);
            ArrayList<int[]> postings = fingerprints.get(key);
            if (postings == null) {
                postings = new ArrayList<int[]>(2);
                fingerprints.put(key, postings);
            }
            postings.add(new int[]{docId, picked[i]});
        }
        fingerprintTokens += at.length;
    }

    /**
     * 指纹带：与断句无关的连续重复区间，长度不少于 Fingerprints.MIN_MATCH 个 token。
     * 逐篇收集按 docId 升序（原来是 HashMap 的桶序），最后排成确定的 (长度降序, docId 升序)：
     * 两篇近重复文献抢同一段字符时，谁赢不该取决于哈希桶落在哪一格。带子集合与总分子都不因此改变。
     */
    private ArrayList<Hit> fingerprintHits(String norm, int[] excluded) {
        ArrayList<Hit> out = new ArrayList<Hit>();
        if (fingerprints.isEmpty()) return out;
        int[] at = Fingerprints.tokens(norm);
        if (at.length < Fingerprints.MIN_MATCH) return out;
        long[] hashes = Fingerprints.rolling(norm, at);
        int[] picked = Fingerprints.sample(hashes);
        HashMap<Integer, ArrayList<int[]>> anchors = new HashMap<Integer, ArrayList<int[]>>();
        for (int i = 0; i < picked.length; i++) {
            ArrayList<int[]> postings = fingerprints.get(Long.valueOf(hashes[picked[i]]));
            if (postings == null) continue;
            for (int p = 0; p < postings.size(); p++) {
                Integer key = Integer.valueOf(postings.get(p)[0]);
                ArrayList<int[]> list = anchors.get(key);
                if (list == null) {
                    list = new ArrayList<int[]>();
                    anchors.put(key, list);
                }
                if (list.size() < MAX_ANCHORS_PER_DOC) list.add(new int[]{picked[i], postings.get(p)[1]});
            }
        }
        ArrayList<Integer> order = new ArrayList<Integer>(anchors.keySet());
        Collections.sort(order);
        ArrayList<Integer> bandDocs = new ArrayList<Integer>();
        for (int d = 0; d < order.size(); d++) {
            Integer docId = order.get(d);
            ArrayList<int[]> shared = anchors.get(docId);
            if (shared.size() < MIN_SHARED_FINGERPRINTS) continue;
            int before = out.size();
            collectRuns(norm, at, docNorms.get(docId.intValue()), docTokens.get(docId.intValue()),
                    shared, excluded, sources.get(docSource.get(docId.intValue()).intValue()), out);
            for (int i = before; i < out.size(); i++) bandDocs.add(docId);
        }
        sortBands(out, bandDocs);
        return out;
    }

    /**
     * 带子的处理顺序：长度降序，同长度按入库序（docId 升序）。这是"最长命中优先、同长先到先得"的
     * 显式写法，取代原来 anchors.entrySet() 的哈希桶序。docs 只在这次比较里用，不必跟着重排。
     */
    private static void sortBands(final ArrayList<Hit> bands, final ArrayList<Integer> docs) {
        if (bands.size() < 2) return;
        ArrayList<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < bands.size(); i++) order.add(Integer.valueOf(i));
        Collections.sort(order, new Comparator<Integer>() {
            public int compare(Integer left, Integer right) {
                Hit a = bands.get(left.intValue()), b = bands.get(right.intValue());
                int lengthA = a.end - a.start, lengthB = b.end - b.start;
                if (lengthA != lengthB) return lengthA > lengthB ? -1 : 1;
                int docA = docs.get(left.intValue()).intValue(), docB = docs.get(right.intValue()).intValue();
                return docA < docB ? -1 : (docA == docB ? 0 : 1);
            }
        });
        ArrayList<Hit> sorted = new ArrayList<Hit>(bands.size());
        for (int i = 0; i < order.size(); i++) sorted.add(bands.get(order.get(i).intValue()));
        bands.clear();
        bands.addAll(sorted);
    }

    /** 最长匹配优先、用过的 token 不再参与下一次匹配，这两条照抄 JPlag 的 GreedyStringTiling。 */
    private void collectRuns(String norm, int[] at, String flat, int[] cat, ArrayList<int[]> anchors,
                             int[] excluded, Source source, ArrayList<Hit> out) {
        boolean[] used = new boolean[at.length];
        boolean[] done = new boolean[anchors.size()];
        while (true) {
            int best = -1, bestLength = 0;
            int[] bestSpan = null;
            for (int a = 0; a < anchors.size(); a++) {
                if (done[a]) continue;
                int[] pair = anchors.get(a);
                if (used[pair[0]]) { done[a] = true; continue; }
                int[] span = run(norm, at, pair[0], flat, cat, pair[1]);
                int length = span[1] - span[0] + 1;
                if (length > bestLength) {
                    bestLength = length;
                    best = a;
                    bestSpan = span;
                }
            }
            if (best < 0 || bestLength < Fingerprints.MIN_MATCH) return;
            done[best] = true;
            boolean clash = false;
            for (int i = bestSpan[0]; i <= bestSpan[1]; i++) if (used[i]) { clash = true; break; }
            if (clash) continue;
            for (int i = bestSpan[0]; i <= bestSpan[1]; i++) used[i] = true;
            int start = at[bestSpan[0]];
            int end = at[bestSpan[1]] + 1;
            if (insideSpan(excluded, start)) continue;
            Hit hit = new Hit();
            hit.start = start;
            hit.end = end;
            int valid = Math.max(1, validCount(norm, start, end));
            hit.score = (float) Math.min(1d, bestLength / (double) valid);
            hit.source = source;
            out.add(hit);
        }
    }

    /** 包含这对锚点的最长逐字符相等片段，返回 token 下标的 {起, 止}。 */
    private int[] run(String norm, int[] at, int docIndex, String flat, int[] cat, int corpusIndex) {
        int from = docIndex, to = docIndex, cf = corpusIndex, ct = corpusIndex;
        /* 逐 token 比的是 Fingerprints.code，跟取样同一个口径：数字在这里折过一刀，
           验算时再按原字符比，锚点两边立刻断掉，指纹带等于白打。 */
        while (from - 1 >= 0 && cf - 1 >= 0
                && Fingerprints.code(norm, at[from - 1]) == Fingerprints.code(flat, cat[cf - 1])) {
            from--;
            cf--;
        }
        while (to + 1 < at.length && ct + 1 < cat.length
                && Fingerprints.code(norm, at[to + 1]) == Fingerprints.code(flat, cat[ct + 1])) {
            to++;
            ct++;
        }
        return new int[]{from, to};
    }

    public int sentenceCount() { return entries.size(); }

    public boolean isEmpty() { return entries.isEmpty(); }

    public void clear() {
        sources.clear();
        entries.clear();
        index.clear();
        exact.clear();
        docNorms.clear();
        docTokens.clear();
        docSource.clear();
        fingerprints.clear();
        fingerprintTokens = 0;
        skippedSentences = 0;
    }

    int sourceCount() { return sources.size(); }

    /** 因过短而被忽略的句子数，供自检与报告使用。 */
    int skippedSentenceCount() { return skippedSentences; }

    /** 上一次 match() 里被两篇以上文献同时命中的字符数，只给报告的注记用。 */
    int disputedChars() { return disputedOverlap; }

    /** citationSpans 是 {start,end} 成对数组，可为 null。 */
    public Report match(String text, int[] citationSpans) { return match(text, citationSpans, null); }

    /** excludedSpans 圈住的句子整体退出比对：参考文献表、致谢这类文本重复了也不是抄袭。 */
    public Report match(String text, int[] citationSpans, int[] excludedSpans) {
        Report report = new Report();
        disputedOverlap = 0;
        if (text == null || text.length() == 0) return report;
        int[] citations = mergeSpans(citationSpans, text.length());
        int[] excluded = mergeSpans(excludedSpans, text.length());
        String norm = normalize(text);
        ArrayList<int[]> spans = sentences(text);
        Counter counter = new Counter(entries.isEmpty() ? 64 : Math.min(1 << 15, 4 + entries.size()));
        int[] postings = new int[POSTING_CAP];
        long[] scratch = new long[Math.max(64, Math.min(entries.size() + 1, POSTING_SCAN_CAP))];
        Match best = new Match();
        HashMap<String, Integer> engineChars = new HashMap<String, Integer>();
        int runStart = -1, runEnd = -1, runWeight = 0;
        double runScore = 0d;
        Source runSource = null;
        for (int i = 0; i < spans.size(); i++) {
            int[] span = spans.get(i);
            // 整体落在排除区里的句子连匹配都不做，省的是开销；它占多少字由下面按区间一次算清，
            // 不再在这里按"整句"累加，否则一句跨在排除区边上就会多算或少算。
            if (insideSpan(excluded, span[0])) continue;
            ArrayList<Frag> frags = fragments(norm, span[0], span[1]);
            if (frags.isEmpty()) continue;
            for (int f = 0; f < frags.size(); f++) {
                Frag frag = frags.get(f);
                if (!bestMatch(frag, counter, postings, scratch, best)) continue;
                Entry entry = entries.get(best.entryId);
                Source source = sources.get(entry.sourceIndex);
                if (runStart >= 0 && frag.start - runEnd <= MERGE_GAP && runSource == source
                        && sameCitationContext(citations, runEnd, frag.start)) {
                    if (frag.end > runEnd) runEnd = frag.end;
                    runScore += (double) best.score * frag.chars;
                    runWeight += frag.chars;
                } else {
                    if (runStart >= 0) flush(report, runStart, runEnd, runWeight, runScore, runSource);
                    runStart = frag.start;
                    runEnd = frag.end;
                    runWeight = frag.chars;
                    runScore = (double) best.score * frag.chars;
                    runSource = source;
                }
            }
        }
        if (runStart >= 0) flush(report, runStart, runEnd, runWeight, runScore, runSource);
        // 指纹带仍然先按句级命中裁一次——那一步决定哪条带子值得进 hits（补不出 MIN_MATCH 个字符就整条丢）。
        // 它顺手记下的"这段被别篇先占走"会和句级命中之间的重叠并成一份，不再各自数一遍。
        ArrayList<int[]> lost = addBandHits(report.hits, fingerprintHits(norm, excluded), norm);
        // 分子、引用分子、来源分布全部从同一次"取并集"里数：见 attributeHits。
        int[] counted = attributeHits(report.hits, norm, citations, excluded, lost, engineChars);
        disputedOverlap = countValid(norm, mergeRanges(lost));
        java.util.Collections.sort(report.hits, new Comparator<Hit>() {
            public int compare(Hit a, Hit b) { return a.start != b.start ? a.start - b.start : a.end - b.end; }
        });
        // 分母与排除字数是同一次划分的两边：整篇有效字符 = comparedChars + excludedChars，
        // 与 CharLedger 的 totalChars / excludedChars 用的是同一个算式，两张表不再各拿一把尺。
        report.excludedChars = countValid(norm, spanList(excluded));
        report.comparedChars = countValid(norm, subtract(spanList(excluded), 0, text.length()));
        report.duplicateChars = counted[0];
        report.citedDuplicateChars = Math.min(counted[1], counted[0]);
        if (report.comparedChars > 0) {
            double compared = report.comparedChars;
            report.overallRate = report.duplicateChars * 100d / compared;
            double uncited = report.duplicateChars - report.citedDuplicateChars;
            report.excludingCitationsRate = uncited < 0 ? 0d : uncited * 100d / compared;
            for (Map.Entry<String, Integer> item : engineChars.entrySet())
                report.byEngine.put(item.getKey(), item.getValue().doubleValue() * 100d / compared);
        }
        return report;
    }

    /**
     * 指纹带只补句级比对没盖住的字符：一条带子先减掉已覆盖的区间，补不出 MIN_MATCH 个有效字符就整条丢掉。
     * 返回"这段字符被另一篇文献先占走"的那几段区间（拿带子的原始区间去问 claims），由 attributeHits 与
     * 句级命中之间的重叠并成一份，只喂给 disputedChars 那条注记，不参与判据、不进任何比率。
     * 注意：这里 push 进 hits 的区间与后面记进分子的 valid 必须是同一个区间，来源榜靠这一点从 hits 反推每篇的账。
     */
    private ArrayList<int[]> addBandHits(ArrayList<Hit> hits, ArrayList<Hit> bands, String norm) {
        ArrayList<int[]> lost = new ArrayList<int[]>();
        if (bands.isEmpty()) return lost;
        // covered 决定"这条带子还能拿走哪些字符"。subtract 里的 mergeRanges 会就地改写它拿到的区间数组，
        // 所以归属另记一份 claims：那份数组永远不会交给 subtract，"这段被谁占走"才不会被合并动作改写。
        ArrayList<int[]> covered = new ArrayList<int[]>();
        ArrayList<Claim> claims = new ArrayList<Claim>();
        for (int i = 0; i < hits.size(); i++) {
            covered.add(new int[]{hits.get(i).start, hits.get(i).end});
            claims.add(new Claim(hits.get(i).start, hits.get(i).end, hits.get(i).source));
        }
        for (int b = 0; b < bands.size(); b++) {
            Hit band = bands.get(b);
            ArrayList<int[]> rest = subtract(covered, band.start, band.end);
            claimFromOthers(lost, claims, band);
            for (int r = 0; r < rest.size(); r++) {
                int[] range = rest.get(r);
                // 已被句级命中盖住，补不出最短可报告长度就不值得再报一条。
                if (validCount(norm, range[0], range[1]) < Fingerprints.MIN_MATCH) continue;
                Hit trimmed = new Hit();
                trimmed.start = range[0];
                trimmed.end = range[1];
                trimmed.score = band.score;
                trimmed.source = band.source;
                hits.add(trimmed);
                covered.add(new int[]{range[0], range[1]});
                claims.add(new Claim(range[0], range[1], band.source));
            }
        }
        return lost;
    }

    /** 已占住的一段字符与抢到它的文献。只给归属注记用，不参与分子。 */
    private static final class Claim {
        final int start, end;
        final Source source;
        Claim(int start, int end, Source source) {
            this.start = start;
            this.end = end;
            this.source = source;
        }
    }

    /** 这条带子里被"另一篇文献"先占走的字符。同一篇自己的句级命中不算争抢，那只是两套算法撞在同一段上。 */
    private static void claimFromOthers(ArrayList<int[]> lost, ArrayList<Claim> claims, Hit band) {
        ArrayList<int[]> foreign = new ArrayList<int[]>();
        for (int i = 0; i < claims.size(); i++) {
            Claim claim = claims.get(i);
            if (claim.source != band.source) foreign.add(new int[]{claim.start, claim.end});
        }
        if (foreign.isEmpty()) return;
        // 两次 subtract：第一次取没被别篇占走的部分，第二次取它的补集，就是被别篇占走的那几段。
        lost.addAll(subtract(subtract(foreign, band.start, band.end), band.start, band.end));
    }

    /** [from,to) 里还没被 covered 盖住的部分。 */
    private static ArrayList<int[]> subtract(ArrayList<int[]> covered, int from, int to) {
        ArrayList<int[]> out = new ArrayList<int[]>();
        int cursor = from;
        ArrayList<int[]> merged = mergeRanges(covered);
        for (int i = 0; i < merged.size() && cursor < to; i++) {
            int[] span = merged.get(i);
            if (span[1] <= cursor || span[0] >= to) continue;
            if (span[0] > cursor) out.add(new int[]{cursor, Math.min(span[0], to)});
            cursor = Math.max(cursor, span[1]);
        }
        if (cursor < to) out.add(new int[]{cursor, to});
        return out;
    }

    private static ArrayList<int[]> mergeRanges(ArrayList<int[]> ranges) {
        ArrayList<int[]> out = new ArrayList<int[]>();
        if (ranges.isEmpty()) return out;
        ArrayList<int[]> sorted = new ArrayList<int[]>(ranges);
        java.util.Collections.sort(sorted, new Comparator<int[]>() {
            public int compare(int[] a, int[] b) { return a[0] != b[0] ? a[0] - b[0] : a[1] - b[1]; }
        });
        int[] current = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            int[] next = sorted.get(i);
            if (next[0] <= current[1]) {
                if (next[1] > current[1]) current[1] = next[1];
            } else {
                out.add(current);
                current = next;
            }
        }
        out.add(current);
        return out;
    }

    /**
     * 结束一段命中：只把区间与加权分值写成一条 Hit。字符数、引用重叠、来源归属一律不在这里记——
     * 那三笔账由 attributeHits 从取好并集的 hits 里数，于是"报告说的重复字数"与"来源榜从 hits
     * 反推的每篇字数"数的是同一批字符，不可能一个双算一个不双算。
     */
    private static void flush(Report report, int start, int end, int weight, double scoreSum, Source source) {
        Hit hit = new Hit();
        hit.start = start;
        hit.end = end;
        hit.score = weight <= 0 ? 0f : (float) (scoreSum / weight);
        hit.source = source;
        report.hits.add(hit);
    }

    /**
     * 收口：命中区间取并集，一个字符只认一次；分子、引用分子、来源分布这三笔账全从这一次并集里数。
     *
     * 为什么要单独走这一遍：0.7.2 的分子是"每条命中各自加一遍有效字符"，可命中区间并不互斥。
     * 超过 WINDOW_CHARS 的长句按 512 个有效字符切窗、相邻两窗重叠 64 个字（step = 512 - 64 = 448），
     * 两篇各赢一窗时那 64 个字在两条命中里都出现，同一批语料实测分子 896 对分母 832；来源榜是按 hits
     * 反推每篇的账，也就跟着一起虚高。账本从同一批命中出发先并区间再数字，报告却按条累加，两张表必然打架。
     *
     * 归属只有一条规矩：先写进 hits 的那一条得这段字符。句级命中按正文先后入列，指纹带按长度降序补在后面
     * 并在 addBandHits 里已经减过一次，所以"同一段字符只记给命中更长的那一篇"在带子之间照旧成立，句级与
     * 带子之间也照旧是句级先占。一个字符至多挂在一个检索源名下，于是 Σ byEngine 的字符数 ==
     * duplicateChars == CharLedger.duplicateChars，来源榜与「按检索源分布」共用同一把尺；被别篇先占走的那些
     * 区间由 claimFromOthers 记进 lost，只喂给 disputedChars 那条注记，不进任何比率、不进任何分子。
     *
     * 排除区在这里当作"没有主人的已覆盖区间"先扣掉：账本的分子是 minus(并集, 排除区)，这边也这么扣，
     * 两边的 duplicateChars 才是结构上相等，而不是碰巧对上。
     *
     * 一条命中被裁成两段以上就拆成多条 Hit（宁可多出出处数，也不让并集里的字符在 hits 里没有归宿）；
     * 有效字符为 0 的碎片丢掉，它进不了任何账。hits 就地换成裁剪后的结果，返回 {分子, 引用分子}。
     */
    private int[] attributeHits(ArrayList<Hit> hits, String norm, int[] citations, int[] excluded,
                                ArrayList<int[]> lost, HashMap<String, Integer> engineChars) {
        ArrayList<int[]> covered = spanList(excluded);
        ArrayList<Claim> claims = new ArrayList<Claim>();
        ArrayList<Hit> kept = new ArrayList<Hit>(hits.size());
        int duplicate = 0, cited = 0;
        for (int i = 0; i < hits.size(); i++) {
            Hit hit = hits.get(i);
            claimFromOthers(lost, claims, hit);
            ArrayList<int[]> rest = subtract(covered, hit.start, hit.end);
            for (int r = 0; r < rest.size(); r++) {
                int[] range = rest.get(r);
                int valid = validCount(norm, range[0], range[1]);
                if (valid <= 0) continue;
                Hit piece = r == 0 ? hit : new Hit();
                if (piece != hit) {
                    piece.score = hit.score;
                    piece.source = hit.source;
                }
                piece.start = range[0];
                piece.end = range[1];
                kept.add(piece);
                duplicate += valid;
                cited += overlapValid(norm, range[0], range[1], citations);
                tally(engineChars, engineKey(hit.source), valid);
                covered.add(range);
                claims.add(new Claim(range[0], range[1], hit.source));
            }
        }
        hits.clear();
        hits.addAll(kept);
        return new int[]{duplicate, cited};
    }

    /** 扁平的 {start,end} 成对数组 -> subtract/mergeRanges 用的区间列表。 */
    private static ArrayList<int[]> spanList(int[] flat) {
        ArrayList<int[]> out = new ArrayList<int[]>();
        for (int i = 0; i + 1 < flat.length; i += 2) out.add(new int[]{flat[i], flat[i + 1]});
        return out;
    }

    /** 一组互不重叠区间里的有效字符总数：区间不重叠，直接相加就不会把一个字符数两遍。 */
    private static int countValid(String norm, ArrayList<int[]> ranges) {
        int total = 0;
        for (int i = 0; i < ranges.size(); i++) total += validCount(norm, ranges.get(i)[0], ranges.get(i)[1]);
        return total;
    }

    /** 检索源键：空引擎记成 local，与 SourceLedger 那一侧同一个写法，两张表才对得上。 */
    private static String engineKey(Source source) {
        String engine = source == null || source.engine == null ? "" : source.engine;
        return engine.length() == 0 ? "local" : engine;
    }

    /** 引用段内外的命中不并段：否则一段引文会被并进正文重复，去除引用比就失去意义。 */
    private static boolean sameCitationContext(int[] spans, int runEnd, int nextStart) {
        return spanIdAt(spans, runEnd - 1) == spanIdAt(spans, nextStart);
    }

    /** pos 所在引用段的序号，不在任何引用段内返回 -1。 */
    private static int spanIdAt(int[] spans, int pos) {
        for (int i = 0; i + 1 < spans.length; i += 2) {
            if (pos >= spans[i] && pos < spans[i + 1]) return i >> 1;
        }
        return -1;
    }

    private static void tally(HashMap<String, Integer> map, String key, int delta) {
        Integer previous = map.get(key);
        map.put(key, Integer.valueOf(previous == null ? delta : previous.intValue() + delta));
    }

    /** 倒排取候选，按命中数降序取前 MAX_CANDIDATES 个做精确 Dice。 */
    private boolean bestMatch(Frag frag, Counter counter, int[] postings, long[] scratch, Match out) {
        if (entries.isEmpty() || frag.grams.length < MIN_SHARED_GRAMS) return false;
        Integer verbatim = exact.get(frag.key);
        if (verbatim != null) {
            int id = verbatim.intValue();
            if (id >= 0 && id < entries.size()) {
                out.entryId = id;
                out.score = 1f;
                return true;
            }
        }
        if (frag.signature == null) frag.signature = signature(frag.grams);
        counter.begin();
        long[] grams = frag.grams;
        int scanned = 0;
        for (int i = 0; i < grams.length && scanned < POSTING_SCAN_CAP; i++) {
            scanned = index.accumulate(grams[i], postings, counter, scanned);
        }
        int found = counter.collect(scratch, entries, frag.signature);
        if (found == 0) return false;
        Arrays.sort(scratch, 0, found);
        int queryLength = grams.length;
        float bestScore = 0f;
        int bestId = -1;
        boolean bestExact = false;
        int evaluated = 0;
        for (int i = found - 1; i >= 0 && evaluated < MAX_CANDIDATES; i--) {
            int entryId = (int) (scratch[i] & 0xffffffffL);
            if (entryId < 0 || entryId >= entries.size()) continue;
            Entry candidate = entries.get(entryId);
            int candidateLength = candidate.grams.length;
            float bound = 2f * Math.min(queryLength, candidateLength) / (queryLength + candidateLength);
            if (bound < Math.min(LENGTH_BOUND_FLOOR, diceFloor)) continue;
            evaluated++;
            int total = queryLength + candidateLength;
            int smaller = Math.min(queryLength, candidateLength);
            // 倒排计数是共享三元组数的下界（停用词三元组被跳过）：够高直接取，不够再精确复核。
            int shared = (int) (scratch[i] >>> 37);
            float dice = 2f * shared / total;
            float containment = (float) shared / smaller;
            boolean exact = dice >= diceFloor || containment >= containmentFloor;
            if (!exact) {
                shared = intersectCount(grams, candidate.grams);
                dice = 2f * shared / total;
                containment = (float) shared / smaller;
            }
            if (shared < MIN_SHARED_GRAMS) continue;
            float score = dice;
            boolean similar = dice >= diceFloor;
            if (!similar && containment >= containmentFloor && dice >= LENGTH_BOUND_FLOOR
                    && Math.max(queryLength, candidateLength) >= 1.6f * Math.min(queryLength, candidateLength)) {
                score = Math.max(dice, containment * 0.8f);
                similar = true;
            }
            if (!similar || score <= bestScore) continue;
            bestScore = score;
            bestId = entryId;
            bestExact = exact;
            if (bestScore >= 0.999f) break;
        }
        if (bestId < 0) return false;
        if (!bestExact) {
            // 分值要给 UI 看，命中段最后按精确交集再算一次。
            Entry chosen = entries.get(bestId);
            bestScore = 2f * intersectCount(grams, chosen.grams) / (queryLength + chosen.grams.length);
        }
        out.entryId = bestId;
        out.score = bestScore;
        return true;
    }

    /** 单句最长 WINDOW_CHARS 有效字符，超长串切窗，保证逐句匹配开销线性。 */
    private static ArrayList<Frag> fragments(String norm, int from, int to) {
        ArrayList<Frag> out = new ArrayList<Frag>();
        Frag whole = fragment(norm, from, to);
        if (whole == null) return out;
        if (whole.chars <= WINDOW_CHARS) { out.add(whole); return out; }
        int units = whole.map.length;
        int[] starts = new int[units + 1];
        int count = 0;
        int at = 0;
        while (at < units) {
            starts[count++] = at;
            at += Character.isHighSurrogate(whole.key.charAt(at)) ? 2 : 1;
        }
        starts[count] = units;
        int step = WINDOW_CHARS - WINDOW_OVERLAP;
        for (int s = 0; s < count; s += step) {
            int e = Math.min(count, s + WINDOW_CHARS);
            int fromUnit = starts[s];
            int toUnit = starts[e];
            if (e - s < MIN_SENTENCE_CHARS) {
                if (e >= units) break;
                continue;
            }
            Frag piece = new Frag();
            piece.key = whole.key.substring(fromUnit, toUnit);
            piece.map = Arrays.copyOfRange(whole.map, fromUnit, toUnit);
            piece.grams = gramsOf(piece.key);
            piece.signature = signature(piece.grams);
            piece.chars = e - s;
            piece.start = piece.map[0];
            piece.end = piece.map[piece.map.length - 1] + 1;
            out.add(piece);
            if (e >= count) break;
        }
        return out;
    }

    /** 折叠片段 + 原串偏移映射；有效字符不足 MIN_SENTENCE_CHARS 返回 null。 */
    static Frag fragment(String norm, int from, int to) {
        int start = skipPrefix(norm, from, to);
        int capacity = Math.max(16, to - start);
        StringBuilder key = new StringBuilder(capacity);
        int[] map = new int[capacity];
        int units = 0;
        int codePoints = 0;
        for (int i = start; i < to; i++) {
            char c = norm.charAt(i);
            if (isBlank(c) || ignorable(c)) continue;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < to && Character.isLowSurrogate(norm.charAt(i + 1))) {
                    key.append(c).append(norm.charAt(i + 1));
                    map[units++] = i;
                    map[units++] = i + 1;
                    codePoints++;
                    i++;
                }
                continue;
            }
            if (Character.isLowSurrogate(c)) continue;
            key.append(c);
            map[units++] = i;
            codePoints++;
        }
        if (codePoints < MIN_SENTENCE_CHARS || units == 0) return null;
        Frag frag = new Frag();
        frag.key = key.toString();
        frag.map = Arrays.copyOf(map, units);
        frag.grams = gramsOf(frag.key);
        frag.chars = codePoints;
        frag.start = frag.map[0];
        frag.end = frag.map[units - 1] + 1;
        return frag;
    }

    /** 剥离句首序号/项目符号，让 "1. 方法" 与 "方法" 可比。 */
    private static int skipPrefix(String norm, int from, int to) {
        int i = from;
        for (int round = 0; round < 2 && i < to; round++) {
            int probe = i;
            while (probe < to && norm.charAt(probe) == ' ') probe++;
            if (probe >= to) return i;
            char c = norm.charAt(probe);
            if (c == '-' || c == '*' || c == '>' || c == '#') { i = probe + 1; continue; }
            if (c == '(' || c == '[') {
                int j = probe + 1;
                int digits = 0;
                while (j < to && norm.charAt(j) >= '0' && norm.charAt(j) <= '9' && digits < 4) { j++; digits++; }
                if (digits > 0 && j < to && (norm.charAt(j) == ')' || norm.charAt(j) == ']')) {
                    i = j + 1;
                    continue;
                }
                return i;
            }
            if (c >= '0' && c <= '9') {
                int j = probe;
                int digits = 0;
                while (j < to && norm.charAt(j) >= '0' && norm.charAt(j) <= '9' && digits < 4) { j++; digits++; }
                if (j < to && (norm.charAt(j) == '.' || norm.charAt(j) == ',' || norm.charAt(j) == ')')) {
                    if (j + 1 >= to || !(norm.charAt(j + 1) >= '0' && norm.charAt(j + 1) <= '9')) {
                        i = j + 1;
                        continue;
                    }
                }
                if (j < to && (norm.charAt(j) == '、' || norm.charAt(j) == ':')) { i = j + 1; continue; }
                return i;
            }
            if (c >= 'i' && c <= 'z') {
                int j = probe;
                int letters = 0;
                while (j < to && ((norm.charAt(j) >= 'a' && norm.charAt(j) <= 'z')
                        || (norm.charAt(j) >= '0' && norm.charAt(j) <= '9')) && letters < 4) { j++; letters++; }
                if (letters > 0 && letters <= 3 && j < to && norm.charAt(j) == ')') { i = j + 1; continue; }
                if (letters > 0 && letters <= 3 && j < to && norm.charAt(j) == '.'
                        && (j + 1 >= to || norm.charAt(j + 1) == ' ')) { i = j + 1; continue; }
                return i;
            }
            return i;
        }
        return i;
    }

    /** 折叠串的字符三元组哈希，已排序去重，可直接求交。 */
    static long[] gramsOf(String key) {
        int units = key.length();
        if (units < 3) return new long[0];
        int[] codePoints = new int[units];
        int count = 0;
        int at = 0;
        while (at < units) {
            int c = key.codePointAt(at);
            codePoints[count++] = c;
            at += Character.charCount(c);
        }
        if (count < 3) return new long[0];
        long[] grams = new long[count - 2];
        for (int i = 0; i + 2 < count; i++) {
            grams[i] = mix(((long) codePoints[i] << 42) ^ ((long) codePoints[i + 1] << 21) ^ codePoints[i + 2]);
        }
        Arrays.sort(grams);
        int n = 0;
        for (int i = 0; i < grams.length; i++) {
            if (n > 0 && grams[n - 1] == grams[i]) continue;
            grams[n++] = grams[i];
        }
        return n == grams.length ? grams : Arrays.copyOf(grams, n);
    }

    private static int intersectCount(long[] a, long[] b) {
        int i = 0, j = 0, hits = 0;
        while (i < a.length && j < b.length) {
            long x = a[i], y = b[j];
            if (x == y) { hits++; i++; j++; }
            else if (x < y) i++;
            else j++;
        }
        return hits;
    }

    private static final long[] SALTS = new long[SIGNATURE_SLOTS];

    static {
        for (int i = 0; i < SIGNATURE_SLOTS; i++) SALTS[i] = mix(0x51EDL + i * 0x9E3779B97F4A7C15L);
    }

    /** MinHash 签名：只用于候选同分时的排序参考，过滤一律按共享三元组数，故不会造成漏报。 */
    private static long[] signature(long[] grams) {
        long[] sig = new long[SIGNATURE_SLOTS];
        for (int s = 0; s < SIGNATURE_SLOTS; s++) {
            long salt = SALTS[s];
            long min = Long.MAX_VALUE;
            for (int i = 0; i < grams.length; i++) {
                long h = mix(grams[i] ^ salt);
                if (h < min) min = h;
            }
            sig[s] = min;
        }
        return sig;
    }

    private static int agreement(long[] a, long[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        int same = 0;
        for (int i = 0; i < a.length; i++) if (a[i] == b[i]) same++;
        return same;
    }

    private static long mix(long value) {
        long z = value + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }

    // ---- 静态文本工具 ----

    /** 全半角、大小写、标点、空白与常用繁简折叠；逐字符等长，偏移与原串一一对应。 */
    public static String normalize(String text) {
        if (text == null) return "";
        if (text.length() == 0) return text;
        char[] out = new char[text.length()];
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out[i] = Character.isHighSurrogate(c) || Character.isLowSurrogate(c)
                    ? c : fold(c);
        }
        return new String(out);
    }

    private static char fold(char c) {
        if (c >= 'A' && c <= 'Z') return (char) (c + 32);
        if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) return c;
        if (isBlank(c)) return ' ';
        if (c >= 0xFF01 && c <= 0xFF5E) {
            char half = (char) (c - 0xFEE0);
            return half >= 'A' && half <= 'Z' ? (char) (half + 32) : half;
        }
        switch (c) {
            case '、': return ',';
            case '。': case '・': case '·': case '‧': return '.';
            case '〈': case '《': return '<';
            case '〉': case '》': return '>';
            case '「': case '『': case '【': case '〔': return '[';
            case '」': case '』': case '】': case '〕': return ']';
            case '“': case '”': case '„': case '‟': case '〝': case '〞': return '"';
            case '‘': case '’': case '‛': case '′': return '\'';
            case '—': case '–': case '―': case '‒': case '‐': case '‑': case '─': case '•': return '-';
            case '…': return '.';
            default: break;
        }
        if (c >= 0xFE10 && c <= 0xFE19) {
            switch (c) {
                case '︐': case '︑': return ',';
                case '︒': case '︙': return '.';
                case '︓': return ':';
                case '︔': return ';';
                case '︕': return '!';
                case '︖': return '?';
                default: return c;
            }
        }
        Character simplified = TRAD_TO_SIMP.get(Character.valueOf(c));
        if (simplified != null) return simplified.charValue();
        return Character.toLowerCase(c);
    }

    private static boolean isBlank(char c) {
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B) return true;
        if (c == 0x85 || c == 0xA0 || c == 0x1680) return true;
        if (c >= 0x2000 && c <= 0x200A) return true;
        if (c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F) return true;
        return c == 0x3000;
    }

    /** 比对时直接丢弃的不可见字符：零宽、软连字符、控制符、私有区。 */
    static boolean ignorable(char c) {
        if (c >= 0x200B && c <= 0x200F) return true;
        if (c == 0x2060 || c == 0xFEFF || c == 0xAD || c == 0x34F || c == 0x180E) return true;
        if (c <= 0x08) return true;
        if (c >= 0x0E && c <= 0x1F) return true;
        if (c >= 0x7F && c <= 0x9F) return true;
        return c >= 0xE000 && c <= 0xF8FF;
    }

    /** 原串上的句子区间 {start,end}：按 。！？；!?. 与换行切分，闭合标点并入前句。 */
    public static ArrayList<int[]> sentences(String text) {
        ArrayList<int[]> out = new ArrayList<int[]>();
        if (text == null || text.length() == 0) return out;
        int n = text.length();
        int start = 0;
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == 0x0B || c == 0x0C || c == 0x2028 || c == 0x2029) {
                emit(out, text, start, i);
                start = i + 1;
                i++;
                continue;
            }
            if (isTerminator(text, i, n)) {
                int end = i + 1;
                while (end < n && isCloser(text.charAt(end))) end++;
                emit(out, text, start, end);
                start = end;
                i = end;
                continue;
            }
            i++;
        }
        emit(out, text, start, n);
        return out;
    }

    private static boolean isTerminator(String s, int i, int n) {
        char c = s.charAt(i);
        if (c == '。' || c == '！' || c == '？' || c == '；') return true;
        if (c == '!' || c == '?' || c == ';' || c == '…') return true;
        if (c != '.' && c != '．') return false;
        char previous = i > 0 ? s.charAt(i - 1) : 0;
        char next = i + 1 < n ? s.charAt(i + 1) : 0;
        if (isDigit(next)) return false;
        if (previous == '.' || next == '.' || previous == '…' || next == '…') return false;
        if (next == 0 || next == ' ' || next == '\n' || next == '\r' || next == 0x09 || next == 0x0B) return true;
        if (isCloser(next)) return true;
        if (Character.isUpperCase(next)) return true;
        return isCJK(next);
    }

    private static boolean isCloser(char c) {
        if (c == '。' || c == '！' || c == '？' || c == '；' || c == '…') return true;
        if (c == '!' || c == '?' || c == ';' || c == '.') return true;
        if (c == '"' || c == '\'' || c == '”' || c == '’' || c == '』' || c == '」') return true;
        return c == ')' || c == '）' || c == ']' || c == '］' || c == '】' || c == '*' || c == '>';
    }

    private static boolean isDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= '０' && c <= '９');
    }

    private static boolean isCJK(char c) {
        if (c >= 0x3000 && c <= 0x303F) return true;
        if (c >= 0x3400 && c <= 0x4DBF) return true;
        if (c >= 0x4E00 && c <= 0x9FFF) return true;
        if (c >= 0xF900 && c <= 0xFAFF) return true;
        if (c >= 0xFF00 && c <= 0xFF65) return true;
        return c == '"' || c == '\'' || c == '“' || c == '”' || c == '‘' || c == '’';
    }

    private static void emit(ArrayList<int[]> out, String text, int from, int to) {
        if (to <= from) return;
        int s = from;
        int e = to;
        while (s < e && (isBlank(text.charAt(s)) || ignorable(text.charAt(s)))) s++;
        while (e > s && (isBlank(text.charAt(e - 1)) || ignorable(text.charAt(e - 1)))) e--;
        if (e <= s) return;
        out.add(new int[]{s, e});
    }

    /** 归一化后的字符三元组 Dice，0..1。 */
    public static float dice(String a, String b) {
        if (a == null || b == null) return 0f;
        String left = compactOf(a);
        String right = compactOf(b);
        if (left.length() == 0 || right.length() == 0) return 0f;
        long[] first = gramsOf(left);
        long[] second = gramsOf(right);
        if (first.length == 0 || second.length == 0) return 0f;
        return (float) (2d * intersectCount(first, second) / (first.length + second.length));
    }

    /** normalize 之后再去掉空白与不可见字符的比较串。 */
    static String compactOf(String text) {
        String norm = normalize(text);
        StringBuilder out = new StringBuilder(norm.length());
        for (int i = 0; i < norm.length(); i++) {
            char c = norm.charAt(i);
            if (isBlank(c) || ignorable(c)) continue;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < norm.length() && Character.isLowSurrogate(norm.charAt(i + 1))) {
                    out.append(c).append(norm.charAt(i + 1));
                    i++;
                }
                continue;
            }
            if (Character.isLowSurrogate(c)) continue;
            out.append(c);
        }
        return out.toString();
    }

    /** 该区间内参与比对的有效字符数（按 Unicode 码点计，去空白与不可见字符）。 */
    static int validCount(String norm, int from, int to) {
        if (norm == null || to <= from) return 0;
        int limit = Math.min(norm.length(), to);
        int start = Math.max(0, from);
        int count = 0;
        for (int i = start; i < limit; i++) {
            char c = norm.charAt(i);
            if (isBlank(c) || ignorable(c)) continue;
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < limit && Character.isLowSurrogate(norm.charAt(i + 1))) i++;
                else continue;
                count++;
                continue;
            }
            if (Character.isLowSurrogate(c)) continue;
            count++;
        }
        return count;
    }

    /** {start,end} 成对数组 -> 裁剪、排序、合并后的扁平区间数组。 */
    /** spans 是成对区间，判断一个点是否落在其中。 */
    static boolean insideSpan(int[] spans, int pos) {
        for (int i = 0; i + 1 < spans.length; i += 2)
            if (pos >= spans[i] && pos < spans[i + 1]) return true;
        return false;
    }

    static int[] mergeSpans(int[] raw, int length) {
        if (raw == null || raw.length < 2 || length <= 0) return new int[0];
        int pairs = raw.length / 2;
        long[] packed = new long[pairs];
        int found = 0;
        for (int i = 0; i + 1 < raw.length; i += 2) {
            int start = raw[i];
            int end = raw[i + 1];
            if (start < 0) start = 0;
            if (end > length) end = length;
            if (end <= start) continue;
            packed[found++] = ((long) start << 32) | (end & 0xffffffffL);
        }
        if (found == 0) return new int[0];
        Arrays.sort(packed, 0, found);
        int[] out = new int[found * 2];
        int count = 0;
        int currentStart = (int) (packed[0] >>> 32);
        int currentEnd = (int) packed[0];
        for (int i = 1; i < found; i++) {
            int start = (int) (packed[i] >>> 32);
            int end = (int) packed[i];
            if (start <= currentEnd) {
                if (end > currentEnd) currentEnd = end;
                continue;
            }
            out[count++] = currentStart;
            out[count++] = currentEnd;
            currentStart = start;
            currentEnd = end;
        }
        out[count++] = currentStart;
        out[count++] = currentEnd;
        return Arrays.copyOf(out, count);
    }

    /** 命中区间里落在引用段内的有效字符数。 */
    static int overlapValid(String norm, int from, int to, int[] spans) {
        if (spans == null || spans.length < 2) return 0;
        int total = 0;
        for (int i = 0; i + 1 < spans.length; i += 2) {
            int lo = Math.max(from, spans[i]);
            int hi = Math.min(to, spans[i + 1]);
            if (hi > lo) total += validCount(norm, lo, hi);
        }
        return total;
    }

    // ---- 结构性文本：参考文献表、致谢、附录、成果清单、目录 ----

    /** 单独成行即为分节标题。这些段落既不该算重复，也不该进相似率的分母。 */
    private static final String[] STRUCTURE_TITLES = {
        "参考文献", "引用文献", "主要参考文献", "参考书目", "文献目录", "致谢", "鸣谢", "后记",
        "附录", "目录", "目次", "references", "reference", "bibliography", "works cited",
        "acknowledgement", "acknowledgements", "acknowledgment", "acknowledgments",
        "appendix", "appendices", "contents", "table of contents",
    };
    private static final String[] BIBLIOGRAPHY_TITLES = {
        "参考文献", "引用文献", "主要参考文献", "参考书目", "文献目录",
        "references", "reference", "bibliography", "works cited",
    };
    private static final int STRUCTURE_TITLE_LIMIT = 40;
    private static final int STRUCTURE_PROSE_CHARS = 60;
    private static final int STRUCTURE_MIN_ENTRIES = 2;
    /** 标题上的编号、括注和冒号："六、参考文献（References）:" 要能落到关键词上。 */
    private static final Pattern STRUCTURE_NUMBER_PREFIX = Pattern.compile(
            "^(?:[0-9]{1,2}(?:[.][0-9]{1,2})*|[一二三四五六七八九十]{1,3})[.,]?");
    private static final Pattern STRUCTURE_TRAILING = Pattern.compile(
            "(?:\\s*\\([^()]{0,30}\\))?\\s*:?\\s*(?:\\s*\\([^()]{0,30}\\))?\\s*[.]*\\s*$");
    private static final Pattern STRUCTURE_TITLE_PREFIX = Pattern.compile(
            "^(?:附录[^；;]{0,14}|攻读.{0,12}学位.{0,12}(?:成果|论文|论著|科研|项目|获奖)"
                    + "|在读期间.{0,8}(?:成果|论文)|发表论文.{0,12}|学术成果)$");
    /** 参考文献标题的写法比"参考文献"多一种就要在这里加一种，宁缺毋滥。 */
    private static final Pattern STRUCTURE_BIBLIO = Pattern.compile(
            "^参考文献(?:及|与|和)?(?:注释|书目|文献|列表)?$");
    /** 下一章节的开头，用来给致谢/附录这类段落收口。 */
    private static final Pattern STRUCTURE_CHAPTER = Pattern.compile(
            "^(?:[0-9]{1,2}(?:[.][0-9]{1,2})*\\s+\\S|第?[零一二三四五六七八九十百]{1,4}(?:章|节|部分))");
    /** 目录行：编号开头，中间是点线、连续空格或制表符，结尾落在页码上。 */
    private static final Pattern STRUCTURE_TOC_LINE = Pattern.compile(
            "^(?:[0-9]{1,3}(?:[.][0-9]{1,3})*|[第附篇][零〇一二三四五六七八九十百0-9]{0,6}|摘\\s*要|abstract)"
                    + "[^\\n]{0,80}(?:[.·…]{2,}[ ]*|[ ]{2,}|\\t)[0-9]{1,4}$", Pattern.CASE_INSENSITIVE);

    /** 目录类标题：段里是一行行"标题 + 页码"，跟参考文献表一样要有内容才认。 */
    public static boolean contentsHeading(String line) {
        if (!structureHeading(line)) return false;
        String norm = STRUCTURE_NUMBER_PREFIX.matcher(
                STRUCTURE_TRAILING.matcher(normalize(line.trim())).replaceFirst("")).replaceFirst("");
        norm = norm.replace(" ", "");
        return norm.equals("目录") || norm.equals("目次")
                || norm.equals("contents") || norm.equals("tableofcontents");
    }

    /** 是否是"参考文献/致谢/附录/目录/成果清单"这类结构标题行。 */
    public static boolean structureHeading(String line) {
        if (line == null) return false;
        String trimmed = line.trim();
        if (trimmed.length() == 0 || trimmed.length() > STRUCTURE_TITLE_LIMIT) return false;
        String norm = normalize(trimmed);
        norm = STRUCTURE_TRAILING.matcher(norm).replaceFirst("");
        norm = STRUCTURE_NUMBER_PREFIX.matcher(norm).replaceFirst("");
        norm = norm.replace(" ", "");
        if (norm.length() == 0 || norm.length() > 24) return false;
        for (int i = 0; i < STRUCTURE_TITLES.length; i++)
            if (norm.equals(STRUCTURE_TITLES[i])) return true;
        if (STRUCTURE_BIBLIO.matcher(norm).matches()) return true;
        // 带句读的就不是标题了，"参考文献的编排要遵循国标"这类正文句必须放行。
        if (norm.indexOf('.') >= 0 || norm.indexOf('!') >= 0 || norm.indexOf('?') >= 0
                || norm.indexOf(';') >= 0 || norm.length() > 20) return false;
        return STRUCTURE_TITLE_PREFIX.matcher(norm).matches();
    }

    /** 结构标题里哪一类是参考文献表：那一类必须有真条目才认，其余按标题即认。 */
    public static boolean bibliographyHeading(String line) {
        if (line == null) return false;
        String trimmed = line.trim();
        if (trimmed.length() == 0 || trimmed.length() > STRUCTURE_TITLE_LIMIT) return false;
        String norm = STRUCTURE_NUMBER_PREFIX.matcher(
                STRUCTURE_TRAILING.matcher(normalize(trimmed)).replaceFirst("")).replaceFirst("");
        norm = norm.replace(" ", "");
        for (int i = 0; i < BIBLIOGRAPHY_TITLES.length; i++)
            if (norm.equals(BIBLIOGRAPHY_TITLES[i])) return true;
        return STRUCTURE_BIBLIO.matcher(norm).matches();
    }

    /** 排除了哪些结构性文本，各多少字，供报告如实交代。 */
    public static final class Structure {
        public final ArrayList<int[]> spans = new ArrayList<int[]>();
        public int bibliographySections, otherSections, citationLines, excludedChars;
        public boolean isEmpty() { return spans.isEmpty(); }
        public int sectionCount() { return bibliographySections + otherSections; }
        /** 展平成 {start,end} 成对数组，直接交给 match。 */
        public int[] spanArray() {
            int[] out = new int[spans.size() * 2];
            for (int i = 0; i < spans.size(); i++) {
                out[i * 2] = spans.get(i)[0];
                out[i * 2 + 1] = spans.get(i)[1];
            }
            return out;
        }
    }

    /**
     * 找参考文献表、致谢、附录、成果清单、目录。参考文献表要求段内至少两条条目，
     * 免得把一句"参考文献很重要"当成一节；没有标题的条目串（连续三条以上）也一并排除。
     */
    public static Structure structure(String text) {
        Structure found = new Structure();
        if (text == null || text.length() == 0) return found;
        ArrayList<int[]> lines = lines(text);
        for (int i = 0; i < lines.size(); i++) {
            String title = text.substring(lines.get(i)[0], lines.get(i)[1]);
            if (!structureHeading(title)) continue;
            boolean bibliography = bibliographyHeading(title);
            boolean contents = contentsHeading(title);
            int end = lines.get(i)[1];
            int entries = 0, leaders = 0;
            for (int j = i + 1; j < lines.size(); j++) {
                String line = text.substring(lines.get(j)[0], lines.get(j)[1]);
                if (structureHeading(line)) break;
                // 目录条目长得就像下一章节（"第1章 绪论	3"），所以目录段不按章节名收口。
                boolean leader = STRUCTURE_TOC_LINE.matcher(line).find();
                if (leader) leaders++;
                else if (!contents && STRUCTURE_CHAPTER.matcher(normalize(line)).find()) break;
                boolean entry = citationLike(line);
                if (entry) entries++;
                if (leader) {
                    end = lines.get(j)[1];
                    continue;
                }
                // 条目本身可以很长（英文文献一行七八十个字符），只有正文句才收口。
                if ((bibliography || contents) && !entry && line.length() >= STRUCTURE_PROSE_CHARS) break;
                end = lines.get(j)[1];
            }
            if (bibliography && entries < STRUCTURE_MIN_ENTRIES) continue;
            if (contents && leaders < STRUCTURE_MIN_ENTRIES) continue;
            found.spans.add(new int[]{lines.get(i)[0], end});
            if (bibliography) found.bibliographySections++; else found.otherSections++;
            found.citationLines += entries;
        }
        int runStart = -1, runEnd = -1, runEntries = 0;
        for (int i = 0; i <= lines.size(); i++) {
            boolean entry = i < lines.size()
                    && bibliographyEntry(text.substring(lines.get(i)[0], lines.get(i)[1]));
            if (entry) {
                if (runStart < 0) runStart = lines.get(i)[0];
                runEnd = lines.get(i)[1];
                runEntries++;
            } else if (runEntries >= 3) {
                if (!covered(found.spans, runStart, runEnd)) {
                    found.spans.add(new int[]{runStart, runEnd});
                    found.bibliographySections++;
                }
                runStart = -1; runEnd = -1; runEntries = 0;
            }
        }
        ArrayList<int[]> merged = mergeSpanList(found.spans);
        found.spans.clear();
        found.spans.addAll(merged);
        String norm = normalize(text);
        for (int i = 0; i < found.spans.size(); i++)
            found.excludedChars += validCount(norm, found.spans.get(i)[0], found.spans.get(i)[1]);
        return found;
    }


    /** 这段条目是不是已经落在某个按标题圈出来的区间里，避免同一节被数两次。 */
    private static boolean covered(ArrayList<int[]> spans, int start, int end) {
        for (int i = 0; i < spans.size(); i++)
            if (start >= spans.get(i)[0] && end <= spans.get(i)[1]) return true;
        return false;
    }

    /** 按行切分并去掉空行，保留原文偏移。 */
    private static ArrayList<int[]> lines(String text) {
        ArrayList<int[]> out = new ArrayList<int[]>();
        if (text == null) return out;
        int n = text.length(), start = 0;
        for (int i = 0; i <= n; i++) {
            boolean cut = i == n || text.charAt(i) == '\n' || text.charAt(i) == '\r'
                    || text.charAt(i) == 0x0B || text.charAt(i) == 0x0C
                    || text.charAt(i) == 0x2028 || text.charAt(i) == 0x2029;
            if (!cut) continue;
            int s = start, e = i;
            while (s < e && isBlank(text.charAt(s))) s++;
            while (e > s && isBlank(text.charAt(e - 1))) e--;
            if (e > s) out.add(new int[]{s, e});
            start = i + 1;
        }
        return out;
    }

    /** 起点排序并合并相邻或重叠的区间。 */
    private static ArrayList<int[]> mergeSpanList(ArrayList<int[]> spans) {
        ArrayList<int[]> out = new ArrayList<int[]>();
        if (spans.isEmpty()) return out;
        Collections.sort(spans, new Comparator<int[]>() {
            public int compare(int[] a, int[] b) { return a[0] != b[0] ? a[0] - b[0] : a[1] - b[1]; }
        });
        int[] current = spans.get(0);
        for (int i = 1; i < spans.size(); i++) {
            int[] next = spans.get(i);
            if (next[0] <= current[1] + 1) {
                if (next[1] > current[1]) current[1] = next[1];
            } else {
                out.add(current);
                current = next;
            }
        }
        out.add(current);
        return out;
    }
    // ---- 参考文献条目特征 ----

    private static final Pattern LEADING_INDEX = Pattern.compile(
            "^(?:\\[\\d{1,3}\\]|\\(\\d{1,3}\\)|\\d{1,3}[.,)）]|[a-z]\\.|\\d{1,3}-\\d{1,3})\\s*");
    private static final Pattern DOC_TYPE = Pattern.compile("\\[[a-z]{1,3}(?:/[a-z]{1,3})?\\]");
    private static final Pattern YEAR = Pattern.compile("(?:1[5-9]|20)\\d{2}");
    private static final Pattern IDENTIFIER = Pattern.compile(
            "doi|isbn|issn|pmid|arxiv|et al|访问日期|引用日期|收稿日期|页码|文档编号|\\bpp\\b");
    private static final Pattern PAGE_RANGE = Pattern.compile("\\d+\\s*[-~]\\s*\\d+");
    private static final Pattern INITIALS = Pattern.compile("(?:[a-z][a-z]*[ ,]+[a-z]\\.){2,}");
    private static final Pattern SOURCE_WORD = Pattern.compile(
            "学报|期刊|杂志|论文集|学位论文|毕业设计|出版社|书局|书店|印刷厂|技术报告|proceedings|journal|press|transactions|springer|elsevier|ieee|acm|publisher");

    /**
     * 参考文献表里的条目比"像条目"更严：必须带 [J]/[M]/[D] 这类文献类型标识或 DOI 等标识符。
     * 正文里"（2）热压连接采用 240、250 和 260 ℃"这种编号段落也会通过 citationLike，
     * 参考论文实测有 8984 字差点被当成文献表排除，所以这一条不能松。
     */
    static boolean bibliographyEntry(String line) {
        if (!citationLike(line)) return false;
        String norm = normalize(line);
        return DOC_TYPE.matcher(norm).find() || IDENTIFIER.matcher(norm).find();
    }
    /** 是否是参考文献条目：编号、文献类型标识、来源刊名、年份、标识符、多段点号共同判定。 */
    public static boolean citationLike(String line) {
        if (line == null) return false;
        String trimmed = line.trim();
        if (trimmed.length() < 6 || trimmed.length() > 400) return false;
        String norm = normalize(trimmed);
        int score = 0;
        boolean indexed = LEADING_INDEX.matcher(norm).find();
        boolean typed = DOC_TYPE.matcher(norm).find();
        boolean sourced = SOURCE_WORD.matcher(norm).find();
        if (indexed) score += 2;
        if (typed) score += 2;
        if (sourced) score += 1;
        if (YEAR.matcher(norm).find()) score += 1;
        if (IDENTIFIER.matcher(norm).find()) score += 1;
        if (PAGE_RANGE.matcher(norm).find()) score += 1;
        if (INITIALS.matcher(norm).find()) score += 1;
        int dots = 0;
        for (int i = 0; i < norm.length(); i++) if (norm.charAt(i) == '.') dots++;
        if (dots >= 3) score += 1;
        return score >= 3 && (indexed || typed || sourced);
    }

    // ---- 常用繁简折叠表 ----

    private static final String TRADITIONAL =
            "體國學說時後來對觀點關機係經濟業產義習實際與為爲於衆區嚴黃兒孫傳傷億僅從偉獨變讓讀麗圖團場"
            + "壞雲飛馬鳥魚龍門問間開閉聞陽陰電風車東長頁頭題類飯館網靜萬舉藝節葉處條腳號設語誤論議謝講"
            + "許訴調談認識譯試課動務勝勞勢醫發盡監確礙禮種樣權歡歲殺氣漢滿燈營現當覺計訂討訓詞詩話該請"
            + "諸誰豐貝財貨費資賞購贊輕輝邊運遊適連選遺郵釋銀銅錄隊隨隱難雞雙齊齒頂項順頓頻額駐駕驗騎鳴"
            + "麥黨數據統證測術應構顯環境護態採品質標準斷備檔訊規範評審幹曆製週裡裏臺範";
    private static final String SIMPLIFIED =
            "体国学说时后来对观点关机系经济业产义习实际与为为于众区严黄儿孙传伤亿仅从伟独变让读丽图团场"
            + "坏云飞马鸟鱼龙门问间开闭闻阳阴电风车东长页头题类饭馆网静万举艺节叶处条脚号设语误论议谢讲"
            + "许诉调谈认识译试课动务胜劳势医发尽监确碍礼种样权欢岁杀气汉满灯营现当觉计订讨训词诗话该请"
            + "诸谁丰贝财货费资赏购赞轻辉边运游适连选遗邮释银铜录队随隐难鸡双齐齿顶项顺顿频额驻驾验骑鸣"
            + "麦党数据统证测术应构显环境护态采品质标准断备档讯规范评审干历制周里里台范";

    private static final HashMap<Character, Character> TRAD_TO_SIMP = traditionalTable();

    private static HashMap<Character, Character> traditionalTable() {
        HashMap<Character, Character> map = new HashMap<Character, Character>(TRADITIONAL.length() * 2);
        int limit = Math.min(TRADITIONAL.length(), SIMPLIFIED.length());
        for (int i = 0; i < limit; i++) {
            char t = TRADITIONAL.charAt(i);
            char s = SIMPLIFIED.charAt(i);
            if (t != s) map.put(Character.valueOf(t), Character.valueOf(s));
        }
        return map;
    }

    /** 繁简表长度一致性，供自检使用。 */
    static boolean translationTableAligned() {
        return TRADITIONAL.length() == SIMPLIFIED.length() && TRAD_TO_SIMP.size() > 100;
    }

    // ---- 原生索引结构（无装箱）----

    /** 三元组哈希 -> 句子 id 倒排表，链表式 posting，超过停用词上限后不再存储。 */
    private static final class GramIndex {
        private long[] keys = new long[1024];
        private int[] heads = new int[1024];
        private int[] tails = new int[1024];
        private int[] counts = new int[1024];
        private int[] values = new int[4096];
        private int[] next = new int[4096];
        private int mask = 1023;
        private int size;
        private int used;

        void clear() {
            keys = new long[1024];
            heads = new int[1024];
            tails = new int[1024];
            counts = new int[1024];
            values = new int[4096];
            next = new int[4096];
            mask = 1023;
            size = 0;
            used = 0;
        }

        void add(long[] grams, int entryId) {
            for (int i = 0; i < grams.length; i++) addOne(grams[i], entryId);
        }

        private void addOne(long gram, int entryId) {
            if ((size + 1) * 2 >= keys.length) resize(keys.length << 1);
            int slot = (int) (mix(gram) & mask);
            while (counts[slot] != 0 && keys[slot] != gram) slot = (slot + 1) & mask;
            if (counts[slot] == 0) {
                ensure(used + 1);
                keys[slot] = gram;
                counts[slot] = 1;
                values[used] = entryId;
                next[used] = 0;
                heads[slot] = used + 1;
                tails[slot] = used + 1;
                used++;
                size++;
                return;
            }
            counts[slot]++;
            if (counts[slot] > POSTING_CAP) return;
            ensure(used + 1);
            values[used] = entryId;
            next[used] = 0;
            next[tails[slot] - 1] = used + 1;
            tails[slot] = used + 1;
            used++;
        }

        /** 把该三元组的 posting 计入候选计数器，返回累计扫描条数；停用词与未出现的直接跳过。 */
        int accumulate(long gram, int[] buffer, Counter counter, int scanned) {
            int slot = find(gram);
            if (slot < 0 || counts[slot] >= STOP_POSTINGS) return scanned;
            int limit = counts[slot] < buffer.length ? counts[slot] : buffer.length;
            int at = heads[slot];
            for (int i = 0; i < limit && at != 0; i++) {
                counter.add(values[at - 1] & 0xffffffffL);
                at = next[at - 1];
            }
            return scanned + limit;
        }

        private int find(long gram) {
            if (size == 0) return -1;
            int slot = (int) (mix(gram) & mask);
            int guard = 0;
            while (counts[slot] != 0) {
                if (keys[slot] == gram) return slot;
                slot = (slot + 1) & mask;
                if (++guard > mask) return -1;
            }
            return -1;
        }

        private void ensure(int needed) {
            if (needed <= values.length) return;
            int capacity = values.length << 1;
            while (capacity < needed) capacity <<= 1;
            values = Arrays.copyOf(values, capacity);
            next = Arrays.copyOf(next, capacity);
        }

        private void resize(int capacity) {
            long[] oldKeys = keys;
            int[] oldHeads = heads;
            int[] oldTails = tails;
            int[] oldCounts = counts;
            keys = new long[capacity];
            heads = new int[capacity];
            tails = new int[capacity];
            counts = new int[capacity];
            mask = capacity - 1;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldCounts[i] == 0) continue;
                int slot = (int) (mix(oldKeys[i]) & mask);
                while (counts[slot] != 0) slot = (slot + 1) & mask;
                keys[slot] = oldKeys[i];
                heads[slot] = oldHeads[i];
                tails[slot] = oldTails[i];
                counts[slot] = oldCounts[i];
            }
        }
    }

    /** 候选句共享三元组计数器：开放寻址 + 世代标记，逐句复用。 */
    private static final class Counter {
        private long[] keys;
        private int[] values;
        private int[] stamp;
        private int mask;
        private int generation = 1;
        private int size;

        Counter(int expected) {
            int capacity = 16;
            while (capacity < expected * 2) capacity <<= 1;
            keys = new long[capacity];
            values = new int[capacity];
            stamp = new int[capacity];
            mask = capacity - 1;
        }

        void begin() {
            generation++;
            if (generation == Integer.MAX_VALUE) {
                Arrays.fill(stamp, 0);
                generation = 1;
            }
            size = 0;
        }

        void add(long key) {
            int slot = (int) (mix(key) & mask);
            while (stamp[slot] == generation) {
                if (keys[slot] == key) {
                    values[slot]++;
                    return;
                }
                slot = (slot + 1) & mask;
            }
            if ((size + 1) * 2 >= keys.length) {
                grow();
                add(key);
                return;
            }
            stamp[slot] = generation;
            keys[slot] = key;
            values[slot] = 1;
            size++;
        }

        /** 打包成可排序的 long：命中数最高位，MinHash 相符槽数居中，句子 id 在低位。 */
        int collect(long[] target, ArrayList<Entry> entries, long[] querySignature) {
            int found = 0;
            for (int i = 0; i < stamp.length && found < target.length; i++) {
                if (stamp[i] != generation) continue;
                int entryId = (int) keys[i];
                int agree = entryId >= 0 && entryId < entries.size()
                        ? agreement(querySignature, entries.get(entryId).signature) : 0;
                target[found++] = ((long) values[i] << 37) | ((long) agree << 32) | (keys[i] & 0xffffffffL);
            }
            return found;
        }

        private void grow() {
            int capacity = keys.length << 1;
            long[] newKeys = new long[capacity];
            int[] newValues = new int[capacity];
            int[] newStamp = new int[capacity];
            int newMask = capacity - 1;
            int previous = generation;
            for (int i = 0; i < stamp.length; i++) {
                if (stamp[i] != previous) continue;
                int slot = (int) (mix(keys[i]) & newMask);
                while (newStamp[slot] == 1) slot = (slot + 1) & newMask;
                newStamp[slot] = 1;
                newKeys[slot] = keys[i];
                newValues[slot] = values[i];
            }
            keys = newKeys;
            values = newValues;
            stamp = newStamp;
            mask = newMask;
            generation = 1;
        }
    }
}
