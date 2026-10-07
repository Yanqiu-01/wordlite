package com.rikkahub.wordlite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 阈值标定台：窗口长度、winnowing 窗、最短命中数、"万能句"的文档频率上限，放在同一批
 * 带标注的样本上扫一遍，看召回和误报在哪里分岔，而不是照抄论文里的 8 和 12。
 *
 * 样本从一篇真实论文的正文里长出来。正例是原文句子的六种写法——原样、数字换写法、去掉
 * 标点、相邻两句拼接、前后半句对调、同义词替换；负例取同一篇论文里内容无关的句子。用同
 * 一篇论文做负例是故意的：术语、刊名、方法名全撞在一起，这是最难也最诚实的负例。
 *
 * 四个层次的行分开打，因为误报只在自己的那一层才看得见：BAND 是锚点层的近似（最短共通
 * 指纹数那一关就在那儿），PAIR 绕开指纹直接让负例句子和文库句子两两配对，CROSS 是第二个
 * 参数指定的另一批论文（默认放 CNKI 检索页上摘的别领域摘要片段）对同一个文库的配对，
 * ENGINE 跑的是真正那条 match() 路径。句级那两条门槛只影响 ENGINE 与 PAIR/CROSS，
 * 锚点层扫它没有意义；反过来引擎级剩下的那点误报在整张 dice 网格上一动不动，说明也不是
 * 句级门槛的事。MARGIN 打的是每条外部探针在文库里能摸到的最高 Dice，门槛与它之间的空白
 * 才是余量——只看"误报 0.0%"是不够的，万一门槛和最近的负例只差一句话呢。
 */
public final class CalibrationSweep {
    private static final long BASE = 0x100000001B3L;
    /** 学术套话的标记词：命中的字符里这些词越密，越像把模板句当成了重复。 */
    private static final String[] BOILERPLATE = {"随着", "发展", "重要意义", "本文", "综上所述", "关键词",
            "应用价值", "影响", "分析", "相关研究", "的基础上", "提出", "目前", "国内外"};

    private CalibrationSweep() { }

    /** 独立跑时用绝对路径指文库，见 tools/detect-calibration.ps1。 */
    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException("用法: CalibrationSweep <真实论文正文.txt>");
        run(args[0], args.length > 1 ? args[1] : null);
    }

    public static void run(String path, String foreign) throws Exception {
        List<String> lines = load(path);
        int half = lines.size() / 2;
        List<String> source = lines.subList(0, half);
        List<String> negatives = lines.subList(half, lines.size());
        Fixture fixture = build(source, negatives);
        System.out.println("CORPUS sourceLines=" + source.size() + " negativeLines=" + negatives.size()
                + " positives=" + fixture.kinds.size() + " queryChars=" + fixture.query.length());
        engine("CURRENT", fixture, source);
        pairGrid(fixture, source, negatives);
        sentenceGrid(fixture, source);
        grid(fixture, source, negatives);
        if (foreign != null && foreign.length() > 0) {
            List<String> outsider = load(foreign);
            System.out.println("CROSS library=sourceA probeLines=" + outsider.size()
                    + " libraryParts=" + fragmentsOf(source).size());
            crossGrid(source, outsider);
            crossMargin(source, outsider);
        }
        System.out.println("note: BAND rows are the anchor layer; PAIR/CROSS rows pair sentences with no"
                + " fingerprint gate; ENGINE rows run the real match() path.");
    }

    /** 真实正文：每行一段，只留有实质内容长度的那些。 */
    private static List<String> load(String path) throws Exception {
        List<String> out = new ArrayList<String>();
        for (String line : Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8)) {
            String text = line.trim();
            if (TextCorpus.normalize(text).length() < 120) continue;
            out.add(text);
        }
        return out;
    }

    private static final class Fixture {
        String query = "";
        final ArrayList<int[]> positives = new ArrayList<int[]>();
        final ArrayList<Integer> kinds = new ArrayList<Integer>();
        final ArrayList<String> texts = new ArrayList<String>();

        /** 命中落在哪一条正例上；落不到就回 -1，误报那部分本来也不该有编号。 */
        int indexAt(int start) {
            for (int i = 0; i < positives.size(); i++) {
                int[] span = positives.get(i);
                if (start >= span[0] && start < span[1]) return i;
            }
            return -1;
        }
    }

    /** 正例从文库前半里取，负例用后半——同一批文字里两边内容不重叠，标注才站得住。 */
    private static Fixture build(List<String> source, List<String> negatives) {
        Fixture out = new Fixture();
        StringBuilder query = new StringBuilder();
        int taken = 0;
        for (int i = 0; i < source.size() && taken < 40; i += 2) {
            ArrayList<int[]> parts = TextCorpus.sentences(source.get(i));
            if (parts.isEmpty()) continue;
            int[] span = parts.get(source.get(i).length() % Math.max(1, parts.size()));
            String sentence = source.get(i).substring(span[0], span[1]).trim();
            if (TextCorpus.normalize(sentence).length() < 40) continue;
            String[][] variants = {
                    { sentence, "0" },
                    { digits(sentence), "1" },
                    { unpunctuated(sentence), "2" },
                    { sentence + "　" + next(source, i), "3" },
                    { halvesSwapped(sentence), "4" },
                    { synonyms(sentence), "5" },
            };
            for (int k = 0; k < variants.length; k++) {
                if (taken >= 40) break;
                String negative = negatives.get(taken % negatives.size());
                query.append(negative).append('\n');
                int start = query.length();
                query.append(variants[k][0]).append('\n');
                out.positives.add(new int[] { start, query.length() - 1 });
                out.kinds.add(Integer.valueOf(variants[k][1]));
                out.texts.add(variants[k][0]);
                query.append('\n');
                taken++;
            }
        }
        out.query = query.toString();
        return out;
    }

    private static String next(List<String> lines, int at) {
        String tail = at + 1 < lines.size() ? lines.get(at + 1) : lines.get(at);
        ArrayList<int[]> parts = TextCorpus.sentences(tail);
        return parts.isEmpty() ? "" : tail.substring(parts.get(0)[0], parts.get(0)[1]).trim();
    }

    /** 数字换写法：阿拉伯数字改中文数目字，"40 分钟"对"四十分钟"。这是降重最常动的一处。 */
    private static String digits(String text) {
        String[] pairs = { "0", "零", "1", "一", "2", "二", "3", "三", "4", "四",
                "5", "五", "6", "六", "7", "七", "8", "八", "9", "九" };
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            String c = String.valueOf(text.charAt(i));
            int at = -1;
            for (int k = 0; k < pairs.length; k += 2) if (pairs[k].equals(c)) at = k + 1;
            out.append(at >= 0 ? pairs[at] : c);
        }
        return out.toString();
    }

    /** 去标点：指纹只看汉字与字母数字，所以这一刀本来就该完全躲不掉，躲掉了就是参数太松/太紧。 */
    private static String unpunctuated(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Fingerprints.readable(c) || c == ' ') out.append(c);
        }
        return out.toString();
    }

    private static void engine(String label, Fixture fixture, List<String> sourceLines) {
        TextCorpus corpus = new TextCorpus();
        TextCorpus.Source source = new TextCorpus.Source();
        source.id = "calibration";
        StringBuilder joined = new StringBuilder();
        for (String line : sourceLines) joined.append(line).append('\n');
        corpus.add(source, joined.toString());
        TextCorpus.Report report = corpus.match(fixture.query, null);
        int positiveChars = 0, covered = 0, falseChars = 0, boilerplate = 0;
        for (int[] span : fixture.positives) positiveChars += span[1] - span[0];
        for (TextCorpus.Hit hit : report.hits) {
            int inside = 0, total = hit.end - hit.start;
            for (int[] span : fixture.positives) {
                int from = Math.max(hit.start, span[0]), to = Math.min(hit.end, span[1]);
                if (to > from) inside += to - from;
            }
            int at = fixture.indexAt(hit.start);
            if (inside * 2 >= total) {
                covered += inside;
                if (at >= 0 && boilerplate(fixture.texts.get(at))) boilerplate += inside;
            } else falseChars += total;
        }
        System.out.printf("ENGINE %-18s recall=%.1f%% fpChars=%d dupChars=%d hits=%d boilerplateShare=%.0f%%%n",
                label, positiveChars == 0 ? 0 : 100d * covered / positiveChars, falseChars,
                report.duplicateChars, report.hits.size(),
                covered == 0 ? 0 : 100d * boilerplate / covered);
    }
    /** 句级那两个门槛也在引擎级实测：引擎剩下的误报就是这一层报出来的，锚点层看不见它。 */
    private static void sentenceGrid(Fixture fixture, List<String> source) {
        for (float dice = 0.50f; dice <= 0.851f; dice += 0.05f) {
            for (float containment = 0.80f; containment <= 1.001f; containment += 0.05f) {
                TextCorpus.overrideThresholds(Float.valueOf(dice).floatValue(),
                        Float.valueOf(containment).floatValue());
                try {
                    engine(String.format(java.util.Locale.ROOT, "dice=%.2f cont=%.2f",
                            Float.valueOf(dice), Float.valueOf(containment)), fixture, source);
                } finally {
                    TextCorpus.restoreThresholds();
                }
            }
        }
    }

    /**
     * 句级那两条门槛的误报只能在这一层量。锚点层扫不到它——负例先被最短共通指纹数挡掉了；
     * 引擎级也看不见它——整张 dice 网格上 fpChars 一直是 471 一动不动，说明引擎剩下的那点
     * 误报跟句级门槛无关。这里绕开指纹，直接让负例的句子和文库的句子两两配对，判定式照抄
     * bestMatch 那两条路（Dice 达标，或长短悬殊时包含率达标），数有多少内容无关的句子会被
     * 判成相似。不复算句首序号剥离，那一刀只会把分数压低一点，方向上偏保守。
     */
    private static void pairGrid(Fixture fixture, List<String> source, List<String> negatives) {
        ArrayList<long[]> library = new ArrayList<long[]>();
        for (ArrayList<long[]> part : fragmentsOf(source)) library.addAll(part);
        ArrayList<ArrayList<long[]>> probes = fragmentsOf(negatives);
        ArrayList<ArrayList<long[]>> rewritten = new ArrayList<ArrayList<long[]>>();
        for (int i = 0; i < fixture.texts.size(); i++) {
            List<String> single = new ArrayList<String>();
            single.add(fixture.texts.get(i));
            ArrayList<ArrayList<long[]>> part = fragmentsOf(single);
            rewritten.add(part.isEmpty() ? new ArrayList<long[]>() : part.get(0));
        }
        for (float dice = 0.50f; dice <= 0.851f; dice += 0.05f) {
            for (float containment = 0.80f; containment <= 1.001f; containment += 0.05f) {
                int recovered = 0;
                for (int i = 0; i < rewritten.size(); i++) {
                    if (anyMatch(rewritten.get(i), library, Float.valueOf(dice).floatValue(),
                            Float.valueOf(containment).floatValue())) recovered++;
                }
                int misfire = 0, total = 0;
                for (int i = 0; i < probes.size(); i++) {
                    ArrayList<long[]> part = probes.get(i);
                    for (int k = 0; k < part.size(); k++) {
                        total++;
                        if (matches(part.get(k), library, Float.valueOf(dice).floatValue(),
                                Float.valueOf(containment).floatValue())) misfire++;
                    }
                }
                System.out.printf("PAIR dice=%.2f cont=%.2f recall=%5.1f%% falsePositive=%5.1f%% pairs=%d%n",
                        Float.valueOf(dice), Float.valueOf(containment),
                        100d * recovered / Math.max(1, rewritten.size()),
                        100d * misfire / Math.max(1, total), Integer.valueOf(total));
            }
        }
    }

    /** 与 TextCorpus.bestMatch 同一条判定：Dice 达标，或长短悬殊时按包含率达标。 */
    private static boolean matches(long[] probe, ArrayList<long[]> library, float diceFloor, float stop) {
        for (int i = 0; i < library.size(); i++) {
            long[] other = library.get(i);
            if (other.length < 3) continue;
            int shared = shared(probe, other);
            int smaller = Math.min(probe.length, other.length);
            float dice = 2f * shared / (probe.length + other.length);
            boolean roomy = shared / (float) smaller >= stop && dice >= 0.5f
                    && Math.max(probe.length, other.length) >= 1.6f * smaller;
            if (dice >= diceFloor || roomy) return true;
        }
        return false;
    }

    private static boolean anyMatch(ArrayList<long[]> parts, ArrayList<long[]> library, float diceFloor, float stop) {
        for (int i = 0; i < parts.size(); i++) {
            if (matches(parts.get(i), library, diceFloor, stop)) return true;
        }
        return false;
    }

    /** 两边都是排好序去重过的三元组，交集大小用归并数。 */
    private static int shared(long[] a, long[] b) {
        int i = 0, j = 0, hits = 0;
        while (i < a.length && j < b.length) {
            long x = a[i], y = b[j];
            if (x == y) { hits++; i++; j++; } else if (x < y) i++; else j++;
        }
        return hits;
    }

    /** 切比对单位：与引擎一样先归一化再按句切，比对串太短或三元组太少的丢掉。 */
    private static ArrayList<ArrayList<long[]>> fragmentsOf(List<String> lines) {
        ArrayList<ArrayList<long[]>> out = new ArrayList<ArrayList<long[]>>();
        for (int i = 0; i < lines.size(); i++) {
            String norm = TextCorpus.normalize(lines.get(i));
            ArrayList<long[]> part = new ArrayList<long[]>();
            ArrayList<int[]> spans = TextCorpus.sentences(norm);
            for (int k = 0; k < spans.size(); k++) {
                String compact = TextCorpus.compactOf(norm.substring(spans.get(k)[0], spans.get(k)[1]));
                if (compact.length() < TextCorpus.MIN_SENTENCE_CHARS) continue;
                long[] grams = TextCorpus.gramsOf(compact);
                if (grams.length < 3) continue;
                part.add(grams);
            }
            if (!part.isEmpty()) out.add(part);
        }
        return out;
    }

    /**
     * 跨库误报：文库是一篇论文的全文，探针换成另一批毫不相干的论文片段。同一篇论文内部的
     * 负例考的是术语干扰，这一台考的才是产品真正面对的场景——库里装的是别人的论文，投稿来的
     * 也是别人的论文，两边不该互相认亲。探针比文库短得多也没关系，句级配对只看单句。
     */
    private static void crossGrid(List<String> libraryLines, List<String> foreign) {
        ArrayList<long[]> library = new ArrayList<long[]>();
        for (ArrayList<long[]> part : fragmentsOf(libraryLines)) library.addAll(part);
        ArrayList<ArrayList<long[]>> probes = fragmentsOf(foreign);
        for (float dice = 0.50f; dice <= 0.851f; dice += 0.05f) {
            for (float containment = 0.80f; containment <= 1.001f; containment += 0.201f) {
                int misfire = 0, total = 0;
                for (int i = 0; i < probes.size(); i++) {
                    ArrayList<long[]> part = probes.get(i);
                    for (int k = 0; k < part.size(); k++) {
                        total++;
                        if (matches(part.get(k), library, Float.valueOf(dice).floatValue(),
                                Float.valueOf(containment).floatValue())) misfire++;
                    }
                }
                System.out.printf("CROSS dice=%.2f cont=%.2f falsePositive=%5.1f%% probes=%d%n",
                        Float.valueOf(dice), Float.valueOf(containment),
                        100d * misfire / Math.max(1, total), Integer.valueOf(total));
            }
        }
    }

    /**
     * 门槛差多少才安全，光看误报率是 0 还不够——万一 0.50 与 0.49 之间只隔一句话呢。
     * 这里把每条探针句子在文库里能找到的最高 Dice 都算出来，打印最高的五条，
     * 门栏与最高分之间的那段空白才是真正的余量。
     */
    private static void crossMargin(List<String> libraryLines, List<String> foreign) {
        ArrayList<long[]> library = new ArrayList<long[]>();
        for (ArrayList<long[]> part : fragmentsOf(libraryLines)) library.addAll(part);
        String[] bestText = new String[5];
        float[] bestScore = new float[5];
        for (int i = 0; i < bestScore.length; i++) bestScore[i] = -1f;
        for (int i = 0; i < foreign.size(); i++) {
            String norm = TextCorpus.normalize(foreign.get(i));
            ArrayList<int[]> spans = TextCorpus.sentences(norm);
            for (int k = 0; k < spans.size(); k++) {
                String compact = TextCorpus.compactOf(norm.substring(spans.get(k)[0], spans.get(k)[1]));
                if (compact.length() < TextCorpus.MIN_SENTENCE_CHARS) continue;
                long[] probe = TextCorpus.gramsOf(compact);
                if (probe.length < 3) continue;
                float top = 0f;
                for (int m = 0; m < library.size(); m++) {
                    long[] other = library.get(m);
                    if (other.length < 3) continue;
                    float dice = 2f * shared(probe, other) / (probe.length + other.length);
                    if (dice > top) top = dice;
                }
                for (int slot = 0; slot < bestScore.length; slot++) {
                    if (top <= bestScore[slot]) break;
                    for (int tail = bestScore.length - 1; tail > slot; tail--) {
                        bestScore[tail] = bestScore[tail - 1];
                        bestText[tail] = bestText[tail - 1];
                    }
                    bestScore[slot] = top;
                    bestText[slot] = compact.length() > 46 ? compact.substring(0, 46) : compact;
                    break;
                }
            }
        }
        for (int i = 0; i < bestScore.length; i++) {
            if (bestText[i] == null) continue;
            System.out.printf("MARGIN best-dice=%.3f probe=%s%n",
                    Float.valueOf(bestScore[i]), bestText[i]);
        }
    }

    /** 参数扫描走的是锚点层的近似：同样的取样、同样的文档频率过滤，只少跑了那一段延伸验算。 */
    private static void grid(Fixture fixture, List<String> source, List<String> negatives) {
        for (int g = 6; g <= 9; g++) {
            for (int w = 8; w <= 16; w += 2) {
                for (int minShared = 1; minShared <= 3; minShared++) {
                    for (int dfStop = 0; dfStop <= 6; dfStop += 6) {
                        score("BAND", "g=" + g + " w=" + w + " min=" + minShared
                                + " dfStop=" + (dfStop == 0 ? "off" : dfStop), g, w, minShared, dfStop,
                                fixture, source, negatives);
                    }
                }
            }
        }
    }

    private static void score(String label, String what, int gram, int window, int minShared,
                              int dfStop, Fixture fixture, List<String> source, List<String> negatives) {
        Map<Long, Integer> documentFrequency = new LinkedHashMap<Long, Integer>();
        ArrayList<LinkedHashSet<Long>> sourceSets = new ArrayList<LinkedHashSet<Long>>();
        for (String line : source) {
            LinkedHashSet<Long> set = sampled(TextCorpus.normalize(line), gram, window);
            for (Long hash : set) {
                Integer seen = documentFrequency.get(hash);
                documentFrequency.put(hash, Integer.valueOf(seen == null ? 1 : seen.intValue() + 1));
            }
            sourceSets.add(set);
        }
        int positiveChars = 0, covered = 0, negativeChars = 0, falselyMatched = 0;
        for (String text : fixture.texts) {
            int shared = bestShared(sampled(TextCorpus.normalize(text), gram, window),
                    sourceSets, documentFrequency, dfStop);
            positiveChars += text.length();
            if (shared >= minShared) covered += text.length();
        }
        /* 负例才是这台的刻度：同一篇论文里内容无关的句子，术语全撞在一起，
           只有它们被误判的比率才说明这组参数是不是太松。 */
        for (String text : negatives) {
            int shared = bestShared(sampled(TextCorpus.normalize(text), gram, window),
                    sourceSets, documentFrequency, dfStop);
            negativeChars += text.length();
            if (shared >= minShared) falselyMatched += text.length();
        }
        System.out.printf("%s %-26s recall=%5.1f%% falsePositive=%5.1f%%%n", label, what,
                positiveChars == 0 ? 0 : 100d * covered / positiveChars,
                negativeChars == 0 ? 0 : 100d * falselyMatched / negativeChars);
    }

    /** 一条文字与文库里最长的那条锚点带：命中数低于门槛就当没锚上。 */
    private static int bestShared(LinkedHashSet<Long> set, ArrayList<LinkedHashSet<Long>> sourceSets,
                                  Map<Long, Integer> documentFrequency, int dfStop) {
        int best = 0;
        for (LinkedHashSet<Long> hashes : sourceSets) {
            int shared = 0;
            for (Long hash : set) {
                if (!hashes.contains(hash)) continue;
                Integer frequency = documentFrequency.get(hash);
                if (dfStop > 0 && frequency != null && frequency.intValue() > dfStop) continue;
                shared++;
            }
            if (shared > best) best = shared;
        }
        return best;
    }
    /** 前后两半对调：降重里最省事的写法，句子还是那句话，顺序反了。 */
    private static String halvesSwapped(String text) {
        int at = text.length() / 2;
        for (int i = at; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '，' || c == '。' || c == '；') { at = i + 1; break; }
        }
        return text.substring(at) + text.substring(0, at);
    }

    /** 同义词替换：降重写法里最常动的一批动词，逐个换一遍。 */
    private static String synonyms(String text) {
        String[][] pairs = { { "使用", "采用" }, { "方法", "手段" }, { "提高", "提升" },
                { "结果", "结论" }, { "分析", "探讨" }, { "具有", "具备" }, { "通过", "借助" },
                { "影响", "作用" }, { "表明", "说明" } };
        String out = text;
        for (String[] pair : pairs) out = out.replace(pair[0], pair[1]);
        return out;
    }
    /** 取样：与 Fingerprints 同一条滚动哈希与同一个 robust winnowing，只是把 n 与 w 摊开成参数。 */
    private static LinkedHashSet<Long> sampled(String norm, int gram, int window) {
        LinkedHashSet<Long> out = new LinkedHashSet<Long>();
        int[] at = Fingerprints.tokens(norm);
        if (at.length < gram) return out;
        long pow = 1L;
        for (int i = 0; i < gram; i++) pow *= BASE;
        long[] hashes = new long[at.length];
        long hash = 0L;
        for (int i = 0; i < at.length; i++) {
            hash = hash * BASE + Fingerprints.code(norm, at[i]);
            if (i >= gram) hash -= pow * Fingerprints.code(norm, at[i - gram]);
            if (i + 1 >= gram) hashes[i] = Fingerprints.mix(hash);
        }
        long last = 0L;
        boolean emitted = false;
        for (int end = gram - 1; end < at.length; end++) {
            int from = Math.max(gram - 1, end - window + 1);
            int best = -1;
            for (int i = from; i <= end; i++) if (best < 0 || hashes[i] <= hashes[best]) best = i;
            if (!emitted || hashes[best] != last) {
                out.add(Long.valueOf(hashes[best]));
                last = hashes[best];
                emitted = true;
            }
        }
        return out;
    }

    private static boolean boilerplate(String text) {
        if (text == null) return false;
        int hits = 0;
        for (String marker : BOILERPLATE) if (text.contains(marker)) hits++;
        return hits >= 2;
    }
}
