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
    /** 判定相似所需的字符三元组 Dice 下限：实测轻度改写 0.61~0.91，同框架不同事实落在 0.52~0.58。 */
    public static final float SIMILAR_DICE = 0.60f;
    /** 长短悬殊时改用的三元组包含率下限：整句原文嵌进改写过的长句。 */
    public static final float SIMILAR_CONTAINMENT = 0.90f;
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
    /** 精算 Dice 前使用的 Dice 上界下限，包含式判定也要求 Dice >= 0.5，故该裁剪无漏报。 */
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
        public int comparedChars, duplicateChars, citedDuplicateChars;
        /** 参考文献表、致谢这类结构性文本的字数，它们既不算重复也不计分母。 */
        public int excludedChars;
        public double overallRate, excludingCitationsRate;
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

    public TextCorpus() { }

    /** 追加一个来源的文本；过短句子直接丢弃，不参与匹配。 */
    public void add(Source source, String text) {
        if (text == null || text.length() == 0) return;
        sources.add(source == null ? new Source() : source);
        int sourceIndex = sources.size() - 1;
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

    public int sentenceCount() { return entries.size(); }

    public boolean isEmpty() { return entries.isEmpty(); }

    public void clear() {
        sources.clear();
        entries.clear();
        index.clear();
        exact.clear();
        skippedSentences = 0;
    }

    int sourceCount() { return sources.size(); }

    /** 因过短而被忽略的句子数，供自检与报告使用。 */
    int skippedSentenceCount() { return skippedSentences; }

    /** citationSpans 是 {start,end} 成对数组，可为 null。 */
    public Report match(String text, int[] citationSpans) { return match(text, citationSpans, null); }

    /** excludedSpans 圈住的句子整体退出比对：参考文献表、致谢这类文本重复了也不是抄袭。 */
    public Report match(String text, int[] citationSpans, int[] excludedSpans) {
        Report report = new Report();
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
        int compared = 0, duplicate = 0, cited = 0, skipped = 0;
        int runStart = -1, runEnd = -1, runWeight = 0, runValid = 0;
        double runScore = 0d;
        Source runSource = null;
        HashMap<String, Integer> runTally = null;
        for (int i = 0; i < spans.size(); i++) {
            int[] span = spans.get(i);
            if (insideSpan(excluded, span[0])) {
                skipped += validCount(norm, span[0], span[1]);
                continue;
            }
            ArrayList<Frag> frags = fragments(norm, span[0], span[1]);
            if (frags.isEmpty()) continue;
            // 有效字符按句子计一次：超长句的滑动窗口重叠，不能把同一个字符算两遍。
            compared += validCount(norm, span[0], span[1]);
            for (int f = 0; f < frags.size(); f++) {
                Frag frag = frags.get(f);
                if (!bestMatch(frag, counter, postings, scratch, best)) continue;
                Entry entry = entries.get(best.entryId);
                Source source = sources.get(entry.sourceIndex);
                String engine = source.engine == null ? "" : source.engine;
                if (runStart >= 0 && frag.start - runEnd <= MERGE_GAP && runSource == source
                        && sameCitationContext(citations, runEnd, frag.start)) {
                    if (frag.end > runEnd) runEnd = frag.end;
                    runScore += (double) best.score * frag.chars;
                    runWeight += frag.chars;
                } else {
                    if (runStart >= 0) {
                        int[] flushed = flush(runStart, runEnd, runWeight, runScore, runSource, runTally, report,
                                norm, citations, engineChars);
                        duplicate += flushed[0];
                        cited += flushed[1];
                    }
                    runStart = frag.start;
                    runEnd = frag.end;
                    runWeight = frag.chars;
                    runScore = (double) best.score * frag.chars;
                    runSource = source;
                    runTally = new HashMap<String, Integer>();
                }
                if (runTally != null) tally(runTally, engine, frag.chars);
            }
        }
        if (runStart >= 0) {
            int[] flushed = flush(runStart, runEnd, runWeight, runScore, runSource, runTally, report,
                    norm, citations, engineChars);
            duplicate += flushed[0];
            cited += flushed[1];
        }
        report.comparedChars = compared;
        report.excludedChars = skipped;
        report.duplicateChars = duplicate;
        report.citedDuplicateChars = Math.min(cited, duplicate);
        if (compared > 0) {
            report.overallRate = duplicate * 100d / compared;
            double uncited = duplicate - Math.min(cited, duplicate);
            report.excludingCitationsRate = uncited < 0 ? 0d : uncited * 100d / compared;
            for (Map.Entry<String, Integer> item : engineChars.entrySet()) {
                report.byEngine.put(item.getKey().length() == 0 ? "local" : item.getKey(),
                        item.getValue().doubleValue() * 100d / compared);
            }
        }
        return report;
    }

    /** 结束一段命中：写 Hit、算有效字符与引用重叠、把重复字符记到主导来源名下。 */
    private int[] flush(int start, int end, int weight, double scoreSum, Source source,
                        HashMap<String, Integer> tally, Report report, String norm, int[] citations,
                        HashMap<String, Integer> engineChars) {
        Hit hit = new Hit();
        hit.start = start;
        hit.end = end;
        hit.score = weight <= 0 ? 0f : (float) (scoreSum / weight);
        hit.source = source;
        report.hits.add(hit);
        int valid = validCount(norm, start, end);
        int cited = overlapValid(norm, start, end, citations);
        if (tally != null && !tally.isEmpty()) {
            String dominant = null;
            int dominantChars = -1;
            for (Map.Entry<String, Integer> item : tally.entrySet()) {
                int chars = item.getValue().intValue();
                if (chars > dominantChars) { dominantChars = chars; dominant = item.getKey(); }
            }
            if (dominant != null) tally(engineChars, dominant, valid);
        }
        return new int[]{valid, cited};
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
            if (bound < LENGTH_BOUND_FLOOR) continue;
            evaluated++;
            int total = queryLength + candidateLength;
            int smaller = Math.min(queryLength, candidateLength);
            // 倒排计数是共享三元组数的下界（停用词三元组被跳过）：够高直接取，不够再精确复核。
            int shared = (int) (scratch[i] >>> 37);
            float dice = 2f * shared / total;
            float containment = (float) shared / smaller;
            boolean exact = dice >= SIMILAR_DICE || containment >= SIMILAR_CONTAINMENT;
            if (!exact) {
                shared = intersectCount(grams, candidate.grams);
                dice = 2f * shared / total;
                containment = (float) shared / smaller;
            }
            if (shared < MIN_SHARED_GRAMS) continue;
            float score = dice;
            boolean similar = dice >= SIMILAR_DICE;
            if (!similar && containment >= SIMILAR_CONTAINMENT && dice >= LENGTH_BOUND_FLOOR
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
