package com.rikkahub.wordlite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AIGC 系数标定台：把系数放在真实论文正文上扫，先看**误报**那一头（唯一有外部效力的量），再看
 * 对本仓库想象的机器腔的召回。产物 artifacts/corpus/aigc-sweep.txt，读数规则与落表见
 * docs/aigc-calibration.md。跑法：{@code pwsh tools/aigc-calibration.ps1}。
 *
 * 这台机器造不出真机写文本，也没有任何带人工标注的人写/机写语料，所以这里没有"拟合出最优系数"这回事。
 * 三台一序：
 * LEGACY 先把 0.5.4 那套写死的权重原样复算一遍，作为反证基准——它必须复现设计稿
 *   （artifacts/research/check/human-probe.txt）里那份真人分布：mean 0.119 / p99 0.468 / max 0.540 /
 *   ≥0.5 两句。复现不了就说明特征提取在重构中变了形。
 * FPB 真人误报预算：真人最高句分与区间门槛之间的余量（margin），本版的头号指标。
 * TIER 双门槛下的文档级误报与召回，含 run=1 的对照组——把"≥2 连句"放松成"单句即可"，真人侧立刻
 *   从 0/78 变成非零；没有这几行，没人知道区间聚合到底买到了什么。
 * GRID 组系数网格，人机双向同屏；MARGIN 逐条打出"最像机写的真人句子离门槛多远"。
 *
 * 机写侧是**弱标签**，而且和打分特征同源：M2 用的骨架词就是模板正则里的词，M3 更是照着实现写的漫画句。
 * 所以启动时先打 OVERLAP 行把重叠项逐条点名，再打 FEAT/DELTA 行把每条特征在两侧的触发率差摊开，
 * 最后 GRADE 行按"不重叠 + 机写侧更高 + 真人侧 ≤ 5/千句"三条判它够不够格定值。本轮没有一条够格，
 * 所以系数表的版本号是 v1-order-only。
 */
public final class AigcCalibrationSweep {
    /** 定值判据第三条：真人侧触发率上限（每千计分句）。 */
    private static final double GRADE_HUMAN_RATE_CAP = 5d;
    /** 读数规则第一条：余量至少留这么多才进候选行。 */
    private static final double MARGIN_BUDGET = 0.05d;
    /** MARGIN 行打几条"最像机写的真人句子"。 */
    private static final int MARGIN_ROWS = 5;
    /** 骨架里的占位符 {1}..{n}。 */
    private static final Pattern SLOT = Pattern.compile("\\{(\\d)}");
    /** 骨架表与真人语料的默认位置。 */
    private static final String FRAMES = "tests/corpus/aigc-frames.txt";
    /** 0.5.4 的九条写死权重，只为 LEGACY 复现基准留在这里，产品代码里已经没有它们了。 */
    private static final double L_TEMPLATE = 0.30d, L_CONNECTIVE = 0.18d, L_BURST = 0.12d,
            L_OPENING = 0.12d, L_PUNCT = 0.11d, L_ENTROPY = 0.08d, L_DIVERSITY = 0.08d,
            L_PARALLEL = 0.12d, L_INTENSIFIER = 0.16d;

    private AigcCalibrationSweep() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) throw new IllegalArgumentException(
                "用法: AigcCalibrationSweep <真实论文正文.txt> [骨架表.txt] [漫画式机写稿.txt]");
        String humanPath = args[0];
        String framePath = args.length > 1 ? args[1] : FRAMES;
        String cartoonPath = args.length > 2 ? args[2] : "tests/corpus/aigc-cartoon.txt";
        List<String> human = load(humanPath);
        List<Frame> frames = loadFrames(framePath);
        List<String> machine = assemble(human, frames);
        List<String> cartoon = Files.exists(Paths.get(cartoonPath)) ? loadCartoon(cartoonPath)
                : new ArrayList<String>();

        System.out.println("GATE flag=" + AigcDetector.SEGMENT_FLAG_GATE
                + " merge-floor=" + AigcDetector.SEGMENT_SCORE_FLOOR
                + " tier-chars=" + AigcDetector.TIER_REVIEW_CHARS + "/" + AigcDetector.TIER_STRONG_CHARS
                + " tier-run=" + AigcDetector.TIER_REVIEW_RUN + "/" + AigcDetector.TIER_STRONG_RUN
                + " max-segment=" + AigcDetector.MAX_SEGMENT_SENTENCES + "句/" + AigcDetector.MAX_SEGMENT_CHARS + "字"
                + " coefficients=" + AigcScorer.defaults(AigcFamily.CHINESE).version());
        System.out.println("CORPUS human-path=" + humanPath + " human-docs=" + human.size()
                + " machine-docs=" + machine.size() + " frames=" + frames.size()
                + " cartoon-docs=" + cartoon.size());

        overlap(frames);
        List<Doc> humanDocs = docs(human);
        List<Doc> machineDocs = docs(machine);
        legacy(humanDocs);
        Table shipped = shipped();
        features(humanDocs, machineDocs);
        fpb(humanDocs, machineDocs, shipped);
        tiers(humanDocs, machineDocs, docs(cartoon), shipped);
        grid(humanDocs, machineDocs, docs(cartoon));
        margin(humanDocs, shipped);
        System.out.println("note: M1 (LocalRewriter 改写体) 实测配对胜率 6.4%，低于抛硬币，明确不用作机写标签；"
                + "M2/M3 与打分特征表同源，召回列只表示对本仓库想象的机器腔的一致性检查。");
        System.out.println("note: 定值判据＝不与骨架表重叠 且 机写侧触发率高于真人侧 且 真人侧 <= "
                + (int) GRADE_HUMAN_RATE_CAP + "/千句，三条同时成立；本轮无一符合，故版本号为 "
                + AigcScorer.VERSION + "（取值＝由真人误报预算反推的上限，不是标定值）。");
    }

    // ---------------------------------------------------------------- 语料

    /** 真人正文：每行一段，只留有实质内容长度的那些（与查重标定台同一条筛法）。 */
    private static List<String> load(String path) throws Exception {
        List<String> out = new ArrayList<String>();
        for (String line : Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8)) {
            String text = line.trim();
            if (text.startsWith("#") || text.length() == 0) continue;
            if (TextCorpus.normalize(text).length() < 120) continue;
            out.add(text);
        }
        return out;
    }

    /** 漫画式机写稿不做长度筛选：它就是一句一段的短样本。 */
    private static List<String> loadCartoon(String path) throws Exception {
        List<String> out = new ArrayList<String>();
        for (String line : Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8)) {
            String text = line.trim();
            if (text.startsWith("#") || text.length() == 0) continue;
            out.add(text);
        }
        return out;
    }

    private static final class Frame {
        final String skeleton;
        final int slots;
        Frame(String skeleton, int slots) {
            this.skeleton = skeleton;
            this.slots = slots;
        }
    }

    /** 骨架表：FRAME 行是骨架，TOKEN 行是审计用的骨架词清单。 */
    private static List<Frame> loadFrames(String path) throws Exception {
        List<Frame> out = new ArrayList<Frame>();
        if (path == null || !Files.exists(Paths.get(path))) return out;
        for (String line : Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8)) {
            String text = line.trim();
            if (!text.startsWith("FRAME ")) continue;
            String skeleton = text.substring(6).trim();
            Matcher m = SLOT.matcher(skeleton);
            int slots = 0;
            while (m.find()) slots = Math.max(slots, Integer.parseInt(m.group(1)));
            out.add(new Frame(skeleton, slots));
        }
        return out;
    }
    /** M2：第 i 段骨架拿第 i..i+slots-1 段的真人句子填充，与原文逐段配对，好算配对胜率。 */
    private static List<String> assemble(List<String> human, List<Frame> frames) {
        List<String> out = new ArrayList<String>();
        if (frames.isEmpty()) return out;
        for (int i = 0; i + deepest(frames) <= human.size(); i++) {
            Frame frame = frames.get(i % frames.size());
            StringBuilder filled = new StringBuilder(frame.skeleton);
            for (int k = 1; k <= frame.slots; k++) {
                String source = human.get(i + k - 1);
                ArrayList<int[]> spans = TextCorpus.sentences(source);
                String head = source;
                if (!spans.isEmpty()) {
                    int[] span = spans.get((i + k) % spans.size());
                    head = source.substring(span[0], span[1]);
                }
                String value = trim(head);
                if (value.length() < AigcDetector.MIN_SENTENCE_CHARS) value = trim(source);
                String slot = "{" + k + "}";
                int at = filled.indexOf(slot);
                if (at < 0) continue;
                filled.replace(at, at + slot.length(), value);
            }
            out.add(filled.toString());
        }
        return out;
    }

    /** 骨架里最多的占位符数：填充要从第 i+k-1 段取句，越界的起点直接不做。 */
    private static int deepest(List<Frame> frames) {
        int out = 0;
        for (int i = 0; i < frames.size(); i++) out = Math.max(out, frames.get(i).slots);
        return out;
    }

    private static String trim(String text) {
        String t = text.trim();
        while (t.length() > 0 && "。．.;；".indexOf(t.charAt(t.length() - 1)) >= 0)
            t = t.substring(0, t.length() - 1);
        return t;
    }

    // ---------------------------------------------------------------- 取样

    /** 一篇文档的特征取样结果：句段 + 文档级统计。打分留到换系数时再做。 */
    private static final class Doc {
        final String text;
        final ArrayList<AigcFeatures.Segment> scored;
        final AigcFeatures.DocStats stats;
        final int sentences;
        final int comparedChars;

        Doc(String text, ArrayList<AigcFeatures.Segment> scored, AigcFeatures.DocStats stats) {
            this.text = text;
            this.scored = scored;
            this.stats = stats;
            this.sentences = scored.size();
            int chars = 0;
            for (int i = 0; i < scored.size(); i++) chars += scored.get(i).validChars;
            this.comparedChars = chars;
        }
    }

    private static List<Doc> docs(List<String> texts) {
        List<Doc> out = new ArrayList<Doc>();
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            String norm = TextCorpus.normalize(text);
            ArrayList<int[]> spans = TextCorpus.sentences(text);
            ArrayList<AigcFeatures.Segment> all = AigcFeatures.segments(norm, spans);
            ArrayList<AigcFeatures.Segment> scored = new ArrayList<AigcFeatures.Segment>();
            for (int k = 0; k < all.size(); k++) if (AigcFeatures.scores(all.get(k))) scored.add(all.get(k));
            out.add(new Doc(text, scored, AigcFeatures.stats(norm, spans, scored)));
        }
        return out;
    }

    /** 一次运行的读数：逐句分、逐句字数，以及跑真链路（detect）得到的档位与可疑字数。 */
    private static final class Run {
        double[] scores = new double[0];
        int[] chars = new int[0];
        double rate;
        int flaggedChars;
        int flaggedSegments;
        int longestRun;
        int tier;
        int comparedChars;
    }

    /** 走产品那条路（AigcDetector.detect），量的是真正生效的规则，不是标定台自己另算一套。 */
    private static Run measure(String text) {
        AigcDetector.Result result = AigcDetector.detect(text);
        Run run = new Run();
        String norm = TextCorpus.normalize(text);
        run.scores = new double[result.sentences.size()];
        run.chars = new int[result.sentences.size()];
        for (int i = 0; i < result.sentences.size(); i++) {
            AigcDetector.Sentence sentence = result.sentences.get(i);
            run.scores[i] = sentence.score;
            run.chars[i] = TextCorpus.validCount(norm, sentence.start, sentence.end);
        }
        run.rate = result.rate;
        run.flaggedChars = result.flaggedChars;
        run.flaggedSegments = result.flaggedSegments;
        run.longestRun = result.longestRunSentences;
        run.tier = result.tier.ordinal();
        run.comparedChars = result.comparedChars;
        return run;
    }

    private static List<Run> measure(List<String> texts) {
        List<Run> out = new ArrayList<Run>();
        for (int i = 0; i < texts.size(); i++) out.add(measure(texts.get(i)));
        return out;
    }

    private static List<String> texts(List<Doc> docs) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < docs.size(); i++) out.add(docs.get(i).text);
        return out;
    }

    // ---------------------------------------------------------------- OVERLAP

    /** 骨架词与打分特征表的重叠：逐条点名，让循环论证在产物里可见。 */
    private static void overlap(List<Frame> frames) throws Exception {
        List<String> tokens = frameTokens();
        if (tokens.isEmpty()) {
            System.out.println("OVERLAP note=没有骨架表，跳过重叠审计");
            return;
        }
        int overlapped = 0;
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            ArrayList<String> hits = new ArrayList<String>();
            String folded = token.toLowerCase(java.util.Locale.US);
            for (int k = 0; k < AigcFeatures.TEMPLATES.length; k++) {
                if (AigcFeatures.TEMPLATES[k].pattern.matcher(folded).find()) hits.add("TEMPLATE");
            }
            if (AigcFeatures.countOccurrences(folded, AigcFeatures.CN_CONNECTIVES) > 0
                    || AigcFeatures.countOccurrences(folded, AigcFeatures.EN_CONNECTIVES) > 0)
                hits.add("CONNECTIVE");
            if (AigcFeatures.countOccurrences(folded, AigcFeatures.INTENSIFIERS) > 0) hits.add("INTENSIFIER");
            if (hits.isEmpty()) continue;
            overlapped++;
            java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<String>(hits);
            System.out.println("OVERLAP frame-token=\"" + token + "\" hits-feature="
                    + join(new ArrayList<String>(unique), "+"));
        }
        System.out.println("OVERLAP note: " + overlapped + " overlapped tokens of " + tokens.size()
                + "; coefficients of overlapped features are ORDER-ONLY (frames=" + frames.size() + ")");
    }

    private static List<String> frameTokens() {
        List<String> out = new ArrayList<String>();
        try {
            for (String line : Files.readAllLines(Paths.get(FRAMES), StandardCharsets.UTF_8)) {
                String text = line.trim();
                if (text.startsWith("# TOKEN ")) out.add(text.substring(8).trim());
            }
        } catch (Exception ignored) {
            // 没有骨架表就没有重叠可审计，GRADE 行的 overlap 全按 false 处理。
        }
        return out;
    }
    // ---------------------------------------------------------------- LEGACY

    /**
     * 0.5.4 的加法原样复算（逐特征相加、不是组内取最强；中英连接词混数；分号/破折号 else-if 链）。
     * 这一行只是反证基准：它必须复现设计稿那份真人分布，否则说明重构把特征提取改形了。
     */
    private static void legacy(List<Doc> human) {
        ArrayList<Double> scores = new ArrayList<Double>();
        LinkedHashMap<String, Integer> labels = new LinkedHashMap<String, Integer>();
        String[] names = {"模板句式", "连接词密度偏高", "句长突发度低", "句首结构重复", "分号密集（并列长句）",
                "分号衔接并列分句", "破折号使用", "引号密度偏高", "并列排比密集", "程度副词/评价词堆叠",
                "字符二元组熵偏低", "实词多样度低"};
        for (int i = 0; i < names.length; i++) labels.put(names[i], Integer.valueOf(0));
        double over = 0d;
        for (int i = 0; i < human.size(); i++) {
            Doc doc = human.get(i);
            for (int k = 0; k < doc.scored.size(); k++) {
                double score = legacyScore(doc.scored.get(k), doc.stats, labels);
                scores.add(Double.valueOf(score));
                if (score >= 0.5d) over++;
            }
        }
        double[] sorted = sorted(scores);
        int total = scores.size();
        System.out.println("LEGACY label=0.5.4写死权重复现 docs=" + human.size() + " sentences=" + total
                + " mean=" + fmt3(mean(sorted)) + " p50=" + fmt3(q(sorted, .5)) + " p90=" + fmt3(q(sorted, .9))
                + " p99=" + fmt3(q(sorted, .99)) + " max=" + fmt3(last(sorted))
                + " over-gate-0.5=" + (int) over + " margin-at-0.5=" + signed(0.5d - last(sorted))
                + " share-over-0.5=" + pct((int) over, total));
        for (Map.Entry<String, Integer> entry : labels.entrySet()) {
            int count = entry.getValue().intValue();
            if (count == 0) continue;
            System.out.println("LEGACY-FEAT " + pad(entry.getKey(), 24) + "count=" + pad(String.valueOf(count), 5)
                    + "per-1000=" + fmt1(1000d * count / Math.max(1, total)));
        }
    }

    private static double legacyScore(AigcFeatures.Segment seg, AigcFeatures.DocStats stats,
                                      Map<String, Integer> labels) {
        double total = 0d;
        int matched = 0;
        for (int i = 0; i < AigcFeatures.TEMPLATES.length; i++) {
            if (!AigcFeatures.TEMPLATES[i].pattern.matcher(seg.body).find()) continue;
            if (matched < 2) total += L_TEMPLATE;
            else if (matched < 4) total += L_TEMPLATE * 0.35d;
            if (matched < 4) bump(labels, "模板句式");
            matched++;
        }
        int connectives = AigcFeatures.countOccurrences(seg.body, AigcFeatures.CN_CONNECTIVES)
                + AigcFeatures.countOccurrences(seg.body, AigcFeatures.EN_CONNECTIVES);
        if (connectives >= 2) {
            total += L_CONNECTIVE;
            bump(labels, "连接词密度偏高");
        }
        if (stats.scoredSentences >= 4 && stats.lengthCv < AigcFeatures.FLAT_CV) {
            total += L_BURST;
            bump(labels, "句长突发度低");
        }
        if (stats.scoredSentences >= 5
                && stats.dominantCount >= AigcFeatures.OPENING_RATIO * stats.scoredSentences) {
            total += L_OPENING;
            bump(labels, "句首结构重复");
        }
        // 0.5.4 在这里对空 compact 直接返回 0 分，后面几条一概不加。
        if (seg.compact.length() == 0) return 0d;
        if (seg.semicolons >= 2) {
            total += L_PUNCT;
            bump(labels, "分号密集（并列长句）");
        } else if (seg.semicolons >= 1) {
            total += L_PUNCT * 0.6d;
            bump(labels, "分号衔接并列分句");
        } else if (seg.dashes >= 1) {
            total += L_PUNCT * 0.8d;
            bump(labels, "破折号使用");
        }
        if (seg.quotes >= 4) {
            total += L_PUNCT * 0.6d;
            bump(labels, "引号密度偏高");
        }
        if ((seg.semicolons >= 1 && seg.commas >= 3) || (seg.commas >= 4 && seg.body.length() < 90)) {
            total += L_PARALLEL;
            bump(labels, "并列排比密集");
        }
        if (AigcFeatures.countOccurrences(seg.body, AigcFeatures.INTENSIFIERS) >= 2) {
            total += L_INTENSIFIER;
            bump(labels, "程度副词/评价词堆叠");
        }
        if (seg.validChars >= 24 && AigcFeatures.bigramEntropy(seg.compact) <= AigcFeatures.LOW_ENTROPY) {
            total += L_ENTROPY;
            bump(labels, "字符二元组熵偏低");
        }
        if (seg.validChars >= 24
                && AigcFeatures.diversity(seg.compact, seg.family) <= AigcFeatures.diversityFloor(seg.family)) {
            total += L_DIVERSITY;
            bump(labels, "实词多样度低");
        }
        return total > 1d ? 1d : total;
    }

    private static void bump(Map<String, Integer> labels, String key) {
        Integer previous = labels.get(key);
        if (previous == null) return;
        labels.put(key, Integer.valueOf(previous.intValue() + 1));
    }

    // ---------------------------------------------------------------- FEAT

    /** 每条特征在两侧的触发率（取值 > 0 就算触发）、真人侧取值分布，以及它够不够格定值。 */
    private static void features(List<Doc> human, List<Doc> machine) {
        AigcFeatureId[] ids = AigcFeatureId.values();
        Map<String, Boolean> overlapFlags = overlappedFeatures();
        AigcScorer.Coefficients base = AigcScorer.defaults(AigcFamily.CHINESE);
        for (int i = 0; i < ids.length; i++) {
            AigcFeatureId id = ids[i];
            Stat h = stat(human, id);
            Stat m = stat(machine, id);
            boolean overlapped = Boolean.TRUE.equals(overlapFlags.get(id.name()));
            boolean gradeable = !overlapped && m.per1000 > h.per1000 && h.per1000 <= GRADE_HUMAN_RATE_CAP;
            System.out.println("FEAT " + pad(id.name(), 17) + "group=" + pad(AigcFeatureId.groupLabel(id), 6)
                    + "human=" + pad(fmt1(h.per1000), 7) + " machine2=" + pad(fmt1(m.per1000), 7)
                    + " delta=" + pad(signed1(m.per1000 - h.per1000), 8) + "human-p99=" + fmt3(h.p99)
                    + " human-max=" + fmt3(h.max) + " weight=" + fmt3(base.get(id)));
            System.out.println("GRADE " + pad(id.name(), 17) + (gradeable ? "VALUE-ABLE" : "ORDER-ONLY")
                    + " overlap=" + overlapped + " human-rate=" + fmt1(h.per1000) + "/1000"
                    + " machine-minus-human=" + signed1(m.per1000 - h.per1000));
        }
    }

    private static final class Stat {
        double per1000, p99, max;
        int sentences;
    }

    private static Stat stat(List<Doc> docs, AigcFeatureId id) {
        Stat out = new Stat();
        ArrayList<Double> values = new ArrayList<Double>();
        int fired = 0;
        for (int i = 0; i < docs.size(); i++) {
            Doc doc = docs.get(i);
            for (int k = 0; k < doc.scored.size(); k++) {
                double value = AigcFeatures.value(id, doc.scored.get(k), doc.stats);
                out.sentences++;
                values.add(Double.valueOf(value));
                if (value > 0d) fired++;
            }
        }
        double[] sorted = sorted(values);
        out.per1000 = 1000d * fired / Math.max(1, out.sentences);
        out.p99 = q(sorted, .99);
        out.max = last(sorted);
        return out;
    }

    /** 骨架表里的词命中了哪些特征：按正则与词表实际比对，不靠人工声明。 */
    private static Map<String, Boolean> overlappedFeatures() {
        LinkedHashMap<String, Boolean> out = new LinkedHashMap<String, Boolean>();
        AigcFeatureId[] ids = AigcFeatureId.values();
        for (int i = 0; i < ids.length; i++) out.put(ids[i].name(), Boolean.FALSE);
        List<String> tokens = frameTokens();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i).toLowerCase(java.util.Locale.US);
            for (int k = 0; k < AigcFeatures.TEMPLATES.length; k++) {
                if (AigcFeatures.TEMPLATES[k].pattern.matcher(token).find())
                    out.put(AigcFeatureId.TEMPLATE.name(), Boolean.TRUE);
            }
            if (AigcFeatures.countOccurrences(token, AigcFeatures.CN_CONNECTIVES) > 0
                    || AigcFeatures.countOccurrences(token, AigcFeatures.EN_CONNECTIVES) > 0)
                out.put(AigcFeatureId.CONNECTIVE.name(), Boolean.TRUE);
            if (AigcFeatures.countOccurrences(token, AigcFeatures.INTENSIFIERS) > 0)
                out.put(AigcFeatureId.INTENSIFIER.name(), Boolean.TRUE);
        }
        return out;
    }
    // ---------------------------------------------------------------- FPB

    /** 四个组旋钮。 */
    private static final class Table {
        final double cliche, frame, rhythm, lexis;
        Table(double cliche, double frame, double rhythm, double lexis) {
            this.cliche = cliche;
            this.frame = frame;
            this.rhythm = rhythm;
            this.lexis = lexis;
        }
    }

    private static Table shipped() {
        AigcScorer.Coefficients c = AigcScorer.defaults(AigcFamily.CHINESE);
        return new Table(c.get(AigcFeatureId.TEMPLATE), c.get(AigcFeatureId.CONNECTIVE),
                c.get(AigcFeatureId.PARALLEL), c.get(AigcFeatureId.DIVERSITY));
    }

    /** 按组缩放的候选表：组内各特征的相对高低照 v1 的比例走，扫的只是四个组旋钮。 */
    private static AigcScorer.Coefficients candidate(double cliche, double frame, double rhythm, double lexis) {
        AigcScorer.Coefficients base = AigcScorer.defaults(AigcFamily.CHINESE);
        AigcScorer.Coefficients out = new AigcScorer.Coefficients(AigcFamily.CHINESE, 1.00d, "grid-sweep");
        AigcFeatureId[] ids = AigcFeatureId.values();
        for (int i = 0; i < ids.length; i++) {
            double groupMax = base.get(strongest(ids[i]));
            double knob = knobOf(ids[i], cliche, frame, rhythm, lexis);
            out.set(ids[i], groupMax <= 0d ? 0d : base.get(ids[i]) / groupMax * knob);
        }
        return out;
    }

    private static double knobOf(AigcFeatureId id, double cliche, double frame, double rhythm, double lexis) {
        switch (AigcFeatureId.group(id)) {
            case AigcFeatureId.GROUP_CLICHE: return cliche;
            case AigcFeatureId.GROUP_FRAME: return frame;
            case AigcFeatureId.GROUP_RHYTHM: return rhythm;
            default: return lexis;
        }
    }

    /** 按组号取旋钮值。 */
    private static double groupWeightOf(Table t, int group) {
        switch (group) {
            case AigcFeatureId.GROUP_CLICHE: return t.cliche;
            case AigcFeatureId.GROUP_FRAME: return t.frame;
            case AigcFeatureId.GROUP_RHYTHM: return t.rhythm;
            default: return t.lexis;
        }
    }

    private static AigcFeatureId strongest(AigcFeatureId id) {
        AigcScorer.Coefficients base = AigcScorer.defaults(AigcFamily.CHINESE);
        AigcFeatureId best = id;
        AigcFeatureId[] ids = AigcFeatureId.values();
        for (int i = 0; i < ids.length; i++) {
            if (AigcFeatureId.group(ids[i]) != AigcFeatureId.group(id)) continue;
            if (base.get(ids[i]) > base.get(best)) best = ids[i];
        }
        return best;
    }

    /** 误报预算台：每个证据组在真人侧的触发面，以及真人最高句分与门槛之间的余量。 */
    private static void fpb(List<Doc> human, List<Doc> machine, Table shipped) {
        AigcFeatureId[] ids = AigcFeatureId.values();
        for (int g = 0; g < AigcFeatureId.GROUP_COUNT; g++) {
            int fired = 0;
            int sentences = 0;
            for (int i = 0; i < human.size(); i++) {
                Doc doc = human.get(i);
                for (int k = 0; k < doc.scored.size(); k++) {
                    boolean any = false;
                    for (int j = 0; j < ids.length; j++) {
                        if (AigcFeatureId.group(ids[j]) != g) continue;
                        if (AigcFeatures.value(ids[j], doc.scored.get(k), doc.stats) > 0d) any = true;
                    }
                    sentences++;
                    if (any) fired++;
                }
            }
            System.out.println("FPB  group=" + pad(AigcFeatureId.GROUP_LABELS[g], 6)
                    + " weight=" + fmt3(groupWeightOf(shipped, g))
                    + " human-flag-rate=" + fmt4((double) fired / Math.max(1, sentences))
                    + " (" + fired + "/" + sentences + ")");
        }
        List<String> humanTexts = texts(human);
        List<String> machineTexts = texts(machine);
        AigcScorer.overrideCoefficients(candidate(shipped.cliche, shipped.frame, shipped.rhythm, shipped.lexis));
        List<Run> h;
        List<Run> m;
        try {
            h = measure(humanTexts);
            m = measure(machineTexts);
        } finally {
            AigcScorer.restoreCoefficients();
        }
        double[] sorted = sentenceScores(h);
        System.out.println("FPB  gate=" + AigcDetector.SEGMENT_FLAG_GATE
                + " human-mean=" + fmt3(mean(sorted)) + " human-p50=" + fmt3(q(sorted, .5))
                + " human-p90=" + fmt3(q(sorted, .9)) + " human-p99=" + fmt3(q(sorted, .99))
                + " human-max=" + fmt3(last(sorted))
                + " margin=" + signed(AigcDetector.SEGMENT_FLAG_GATE - last(sorted))
                + " over-gate=" + atOrAbove(sorted, AigcDetector.SEGMENT_FLAG_GATE) + "/" + sentenceCount(h)
                + " budget=" + MARGIN_BUDGET + " docs-flagged=" + review(h) + "/" + h.size());
        double[] machineSorted = sentenceScores(m);
        System.out.println("FPB  同表机写侧 machine2-mean=" + fmt3(mean(machineSorted))
                + " p50=" + fmt3(q(machineSorted, .5)) + " p90=" + fmt3(q(machineSorted, .9))
                + " max=" + fmt3(last(machineSorted))
                + " over-gate=" + atOrAbove(machineSorted, AigcDetector.SEGMENT_FLAG_GATE)
                + "/" + sentenceCount(m) + "（与真人同屏，说明这一版余量花了多少召回）");
    }

    private static double[] sentenceScores(List<Run> runs) {
        ArrayList<Double> all = new ArrayList<Double>();
        for (int i = 0; i < runs.size(); i++)
            for (int k = 0; k < runs.get(i).scores.length; k++) all.add(Double.valueOf(runs.get(i).scores[k]));
        return sorted(all);
    }

    private static int sentenceCount(List<Run> runs) {
        int out = 0;
        for (int i = 0; i < runs.size(); i++) out += runs.get(i).scores.length;
        return out;
    }

    // ---------------------------------------------------------------- TIER

    /** 双门槛下的文档级误报与召回；run=1 那几行是对照组，没有它们没人知道区间聚合买了什么。 */
    private static void tiers(List<Doc> human, List<Doc> machine, List<Doc> cartoon, Table shipped) {
        AigcScorer.overrideCoefficients(candidate(shipped.cliche, shipped.frame, shipped.rhythm, shipped.lexis));
        List<Run> h;
        List<Run> m;
        List<Run> c;
        try {
            h = measure(texts(human));
            m = measure(texts(machine));
            c = measure(texts(cartoon));
        } finally {
            AigcScorer.restoreCoefficients();
        }
        // 0.25/0.30 是产品门槛以下的探针档：v1 表把真人最高句分压到 0.366，产品门槛 0.45 那一行
        // 真人侧本来就是 0，双门槛的用处量不出来；只有把门槛放到真人最高分以下才看得见差别。
        double[] gates = {0.25d, 0.30d, 0.35d, 0.40d, 0.45d, 0.50d};
        for (int i = 0; i < gates.length; i++) {
            double gate = gates[i];
            for (int run = 1; run <= 3; run++) {
                System.out.println("TIER gate=" + fmt3(gate) + " run=" + run
                        + " chars=" + AigcDetector.TIER_REVIEW_CHARS
                        + " human=" + flagged(h, gate, run, AigcDetector.TIER_REVIEW_CHARS) + "/" + h.size()
                        + " machine2=" + flagged(m, gate, run, AigcDetector.TIER_REVIEW_CHARS) + "/" + m.size()
                        + " machine3=" + flagged(c, gate, run, AigcDetector.TIER_REVIEW_CHARS) + "/" + c.size()
                        + (run == 1 ? "  <- 对照组：只保留字数门槛（单句即可成档）" : ""));
            }
            System.out.println("TIER gate=" + fmt3(gate) + " run=1 chars=0"
                    + " human=" + flagged(h, gate, 1, 0) + "/" + h.size()
                    + " machine2=" + flagged(m, gate, 1, 0) + "/" + m.size()
                    + " machine3=" + flagged(c, gate, 1, 0) + "/" + c.size()
                    + "  <- 对照组：双门槛全撤（哪句过线就报哪句）");
            System.out.println("RUNS gate=" + fmt3(gate) + " 真人字符在连句里的占比 >=1/>=2/>=3="
                    + runShare(h, gate, 1) + "/" + runShare(h, gate, 2) + "/" + runShare(h, gate, 3)
                    + " machine2=" + runShare(m, gate, 1) + "/" + runShare(m, gate, 2) + "/" + runShare(m, gate, 3)
                    + " machine3=" + runShare(c, gate, 1) + "/" + runShare(c, gate, 2) + "/" + runShare(c, gate, 3));
        }
        System.out.println("RULE 产品口径（区间分为字符加权均值 + 区间过线）档位分布 真人=" + tierCounts(h)
                + " machine2=" + tierCounts(m) + " machine3=" + tierCounts(c)
                + "（顺序 样本不足/一般/观察/复核/成段）");
        // 漫画式稿每篇只有九十字上下，绝对过不了 400 字的样本门槛（上一行 machine3 全是样本不足），
        // 所以再拼一轮：每 5 篇接成一篇，模拟"整节都是机器腔"，让复核/成段两档在机写侧有读数。
        List<String> long3 = new ArrayList<String>();
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < cartoon.size(); i++) {
            joined.append(cartoon.get(i).text);
            if ((i + 1) % 5 == 0 || i == cartoon.size() - 1) {
                long3.add(joined.toString());
                joined.setLength(0);
            }
        }
        List<Run> l3;
        AigcScorer.overrideCoefficients(candidate(shipped.cliche, shipped.frame, shipped.rhythm, shipped.lexis));
        try {
            l3 = measure(long3);
        } finally {
            AigcScorer.restoreCoefficients();
        }
        int flaggedChars = 0;
        for (int i = 0; i < l3.size(); i++) flaggedChars += l3.get(i).flaggedChars;
        System.out.println("LONG3 每 5 篇漫画稿接成一篇（跨过 400 字样本门槛）docs=" + l3.size()
                + " 产品口径档位分布 " + tierCounts(l3) + " 可疑字符合计 " + flaggedChars
                + "（短文只能停在样本不足，这是字数门槛的另一面，不是漏检）");
        System.out.println("PAIRED 配对胜率（机写 > 同段真人）machine2=" + wins(h, m) + "/"
                + Math.min(h.size(), m.size()) + "（弱标签能把序拉开，但它被构造偏置污染，只作旁证）");
    }

    private static int flagged(List<Run> runs, double gate, int minRun, int minChars) {
        int out = 0;
        for (int i = 0; i < runs.size(); i++) if (hasRun(runs.get(i), gate, minRun, minChars)) out++;
        return out;
    }

    private static boolean hasRun(Run run, double gate, int minRun, int minChars) {
        int length = 0, chars = 0;
        for (int i = 0; i < run.scores.length; i++) {
            if (run.scores[i] >= gate) {
                length++;
                chars += run.chars[i];
                if (length >= minRun && chars >= minChars) return true;
            } else {
                length = 0;
                chars = 0;
            }
        }
        return false;
    }

    /** 落在"连续 k 句都过线"里的字符占比（复刻设计稿 1.5 那张表的口径）。 */
    private static String runShare(List<Run> runs, double gate, int minRun) {
        int inRun = 0, total = 0;
        for (int i = 0; i < runs.size(); i++) {
            Run run = runs.get(i);
            boolean[] marks = new boolean[run.scores.length];
            int length = 0;
            for (int k = 0; k <= run.scores.length; k++) {
                boolean over = k < run.scores.length && run.scores[k] >= gate;
                if (k < run.scores.length) total += run.chars[k];
                if (over) {
                    marks[k] = true;
                    length++;
                    continue;
                }
                if (length < minRun) for (int j = k - length; j < k; j++) marks[j] = false;
                length = 0;
            }
            for (int k = 0; k < marks.length; k++) if (marks[k]) inRun += run.chars[k];
        }
        return pct(inRun, total);
    }

    private static String tierCounts(List<Run> runs) {
        int[] counts = new int[AigcDetector.Tier.values().length];
        for (int i = 0; i < runs.size(); i++) counts[runs.get(i).tier]++;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < counts.length; i++) {
            if (i > 0) out.append('/');
            out.append(counts[i]);
        }
        return out.toString();
    }

    private static int wins(List<Run> lower, List<Run> higher) {
        int out = 0;
        for (int i = 0; i < Math.min(lower.size(), higher.size()); i++)
            if (higher.get(i).rate > lower.get(i).rate + 0.0001d) out++;
        return out;
    }

    /** 过线的句子数。档位列会被 400 字样本下限压成常数，句级过线占比才看得出旋钮的梯度。 */
    private static int over(List<Run> runs, double gate) {
        int out = 0;
        for (int i = 0; i < runs.size(); i++)
            for (int k = 0; k < runs.get(i).scores.length; k++)
                if (runs.get(i).scores[k] >= gate) out++;
        return out;
    }

    /** 需要复核及以上档的文档数。 */
    private static int review(List<Run> runs) {
        int out = 0;
        for (int i = 0; i < runs.size(); i++)
            if (runs.get(i).tier >= AigcDetector.Tier.NEEDS_REVIEW.ordinal()) out++;
        return out;
    }
    // ---------------------------------------------------------------- GRID

    /** 组系数网格：人机双向同屏。读数规则见 docs/aigc-calibration.md：先挑 margin >= +0.05 且 h-flag=0 的行，
     *  再在剩下的行里取召回最大的一行；paired-win 只作旁证。 */
    private static void grid(List<Doc> human, List<Doc> machine, List<Doc> cartoon) {
        double[] cliches = {0.25d, 0.30d, 0.35d, 0.40d};
        double[] frames = {0.15d, 0.20d, 0.25d};
        double[] rhythms = {0.10d, 0.15d, 0.20d};
        double[] lexes = {0.05d, 0.10d};
        List<String> humanTexts = texts(human);
        List<String> machineTexts = texts(machine);
        List<String> cartoonTexts = texts(cartoon);
        ArrayList<String> rows = new ArrayList<String>();
        ArrayList<double[]> keys = new ArrayList<double[]>();
        double gate = AigcDetector.SEGMENT_FLAG_GATE;
        for (int i = 0; i < cliches.length; i++) {
            for (int j = 0; j < frames.length; j++) {
                for (int k = 0; k < rhythms.length; k++) {
                    for (int l = 0; l < lexes.length; l++) {
                        AigcScorer.overrideCoefficients(
                                candidate(cliches[i], frames[j], rhythms[k], lexes[l]));
                        try {
                            List<Run> h = measure(humanTexts);
                            List<Run> m = measure(machineTexts);
                            List<Run> c = measure(cartoonTexts);
                            double[] sorted = sentenceScores(h);
                            double max = last(sorted);
                            double m2over = (double) over(m, gate) / Math.max(1, sentenceCount(m));
                            double m3over = (double) over(c, gate) / Math.max(1, sentenceCount(c));
                            int hflag = review(h);
                            rows.add("GRID cliche=" + fmt3(cliches[i]) + " frame=" + fmt3(frames[j])
                                    + " rhythm=" + fmt3(rhythms[k]) + " lexis=" + fmt3(lexes[l])
                                    + " human-mean=" + fmt3(mean(sorted)) + " human-p99=" + fmt3(q(sorted, .99))
                                    + " human-max=" + fmt3(max)
                                    + " margin=" + signed(AigcDetector.SEGMENT_FLAG_GATE - max)
                                    + " h-flag=" + review(h) + "/" + h.size()
                                    + " m2-flag=" + review(m) + "/" + m.size()
                                    + " m3-flag=" + review(c) + "/" + c.size()
                                    + " m2-over=" + pct1(100d * m2over)
                                    + " m3-over=" + pct1(100d * m3over)
                                    + " paired-win=" + wins(h, m) + "/" + Math.min(h.size(), m.size()));
                            // 读数规则：先要 margin >= 预算且真人侧零误判，再在剩下的行里取召回最大的一行。
                            keys.add(new double[]{cliches[i], frames[j], rhythms[k], lexes[l],
                                    gate - max, hflag, m2over, m3over});
                        } finally {
                            AigcScorer.restoreCoefficients();
                        }
                    }
                }
            }
        }
        Table shipped = shipped();
        int best = -1;
        double bestRecall = -1d;
        for (int i = 0; i < keys.size(); i++) {
            double[] key = keys.get(i);
            if (key[4] < MARGIN_BUDGET || key[5] > 0d) continue;
            double recall = key[6] + key[7];
            if (recall > bestRecall) {
                bestRecall = recall;
                best = i;
            }
        }
        for (int i = 0; i < rows.size(); i++) System.out.println(rows.get(i));
        System.out.println("PICK shipped cliche=" + fmt3(shipped.cliche) + " frame=" + fmt3(shipped.frame)
                + " rhythm=" + fmt3(shipped.rhythm) + " lexis=" + fmt3(shipped.lexis)
                + " —— 与网格内同一行的 margin/召回一致（见上表对应行）");
        if (best >= 0) {
            double[] key = keys.get(best);
            System.out.println("PICK best-in-budget cliche=" + fmt3(key[0]) + " frame=" + fmt3(key[1])
                    + " rhythm=" + fmt3(key[2]) + " lexis=" + fmt3(key[3])
                    + " margin=" + signed(key[4]) + " h-flag=0 m2-over=" + pct1(100d * key[6])
                    + " m3-over=" + pct1(100d * key[7])
                    + "（读数规则：margin >= " + MARGIN_BUDGET + " 且 h-flag=0 里召回最大的一行）");
        } else {
            System.out.println("PICK best-in-budget 没有一行同时满足 margin >= " + MARGIN_BUDGET + " 与 h-flag=0");
        }
        System.out.println("PICK note=按 GRADE 行本轮没有一条系数够格定值，落表值仍是误报预算上限；"
                + "召回列是弱标签侧的一致性检查，不是真实召回率");
    }

    private static String pct1(double percent) {
        return String.format(java.util.Locale.US, "%.1f%%", Double.valueOf(percent));
    }

    // ---------------------------------------------------------------- MARGIN

    /** 最像机写的真人句子：逐条打它与门槛的距离。这几条之后要落进 aigc-hard-human.txt 当断言用的固定句。 */
    private static void margin(List<Doc> human, Table shipped) {
        ArrayList<double[]> rows = new ArrayList<double[]>();
        ArrayList<String> lines = new ArrayList<String>();
        ArrayList<String> groups = new ArrayList<String>();
        AigcScorer.overrideCoefficients(candidate(shipped.cliche, shipped.frame, shipped.rhythm, shipped.lexis));
        try {
            for (int i = 0; i < human.size(); i++) {
                Doc doc = human.get(i);
                for (int k = 0; k < doc.scored.size(); k++) {
                    AigcFeatures.Segment seg = doc.scored.get(k);
                    ArrayList<AigcFeatures.Hit> hits = AigcFeatures.of(seg, doc.stats);
                    AigcScorer.Coefficients table = AigcScorer.current(seg.family);
                    double score = AigcScorer.score(hits, table);
                    rows.add(new double[]{score, seg.validChars});
                    lines.add(doc.text.substring(seg.start, Math.min(doc.text.length(), seg.end)));
                    groups.add(groupBreakdown(hits, table));
                }
            }
        } finally {
            AigcScorer.restoreCoefficients();
        }
        Integer[] order = new Integer[rows.size()];
        for (int i = 0; i < order.length; i++) order[i] = Integer.valueOf(i);
        java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
            public int compare(Integer left, Integer right) {
                return Double.valueOf(rows.get(right.intValue())[0])
                        .compareTo(Double.valueOf(rows.get(left.intValue())[0]));
            }
        });
        for (int i = 0; i < Math.min(MARGIN_ROWS, order.length); i++) {
            int at = order[i].intValue();
            double score = rows.get(at)[0];
            String text = lines.get(at);
            System.out.println("MARGIN gate=" + AigcDetector.SEGMENT_FLAG_GATE
                    + " margin=" + signed(AigcDetector.SEGMENT_FLAG_GATE - score)
                    + " score=" + fmt3(score) + " " + groups.get(at)
                    + " chars=" + ((int) rows.get(at)[1]) + " "
                    + (text.length() > 60 ? text.substring(0, 60) : text));
            System.out.println("HARD score=" + fmt3(score)
                    + " margin=" + signed(AigcDetector.SEGMENT_FLAG_GATE - score) + " " + text);
        }
        nails(shipped);
    }

    /** 高分留出句清单（tests/corpus/aigc-hard-human.txt）逐条复检：这几句一旦被推到门槛之上就是回归。 */
    private static void nails(Table shipped) {
        List<String> nails;
        try {
            nails = loadCartoon("tests/corpus/aigc-hard-human.txt");
        } catch (Exception missing) {
            System.out.println("NAIL note=没有 tests/corpus/aigc-hard-human.txt，跳过高分留出句复检");
            return;
        }
        if (nails.isEmpty()) return;
        AigcScorer.overrideCoefficients(candidate(shipped.cliche, shipped.frame, shipped.rhythm, shipped.lexis));
        int over;
        double worst;
        try {
            over = 0;
            worst = 0d;
            for (int i = 0; i < nails.size(); i++) {
                Run run = measure(nails.get(i) + nails.get(i) + nails.get(i));
                for (int k = 0; k < run.scores.length; k++) {
                    worst = Math.max(worst, run.scores[k]);
                    if (run.scores[k] >= AigcDetector.SEGMENT_FLAG_GATE) over++;
                }
            }
        } finally {
            AigcScorer.restoreCoefficients();
        }
        System.out.println("NAIL lines=" + nails.size() + " over-gate=" + over
                + " worst=" + fmt3(worst) + " margin=" + signed(AigcDetector.SEGMENT_FLAG_GATE - worst)
                + (over == 0 ? " OK" : " 回归：高分留出句越线"));
    }

    /** 各组最强那条证据的贡献：句分为什么这么高，一眼能看出来。 */
    private static String groupBreakdown(ArrayList<AigcFeatures.Hit> hits, AigcScorer.Coefficients table) {
        double[] best = new double[AigcFeatureId.GROUP_COUNT];
        String[] owner = new String[AigcFeatureId.GROUP_COUNT];
        for (int i = 0; i < hits.size(); i++) {
            AigcFeatures.Hit hit = hits.get(i);
            int group = AigcFeatureId.group(hit.id);
            if (hit.value * table.get(hit.id) <= best[group]) continue;
            best[group] = hit.value * table.get(hit.id);
            owner[group] = hit.id.name();
        }
        StringBuilder out = new StringBuilder("groups=");
        for (int g = 0; g < best.length; g++) {
            if (best[g] <= 0d) continue;
            if (out.length() > "groups=".length()) out.append('+');
            out.append(AigcFeatureId.GROUP_LABELS[g]).append(AigcFeatures.format(best[g]))
                    .append('(').append(owner[g]).append(')');
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- 工具

    private static double[] sorted(ArrayList<Double> values) {
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) out[i] = values.get(i).doubleValue();
        java.util.Arrays.sort(out);
        return out;
    }

    private static double mean(double[] a) {
        double s = 0d;
        for (int i = 0; i < a.length; i++) s += a[i];
        return a.length == 0 ? 0d : s / a.length;
    }

    private static double q(double[] a, double p) {
        if (a.length == 0) return 0d;
        int at = (int) Math.min(a.length - 1, Math.round(p * (a.length - 1)));
        return a[at];
    }

    private static double last(double[] a) {
        return a.length == 0 ? 0d : a[a.length - 1];
    }

    private static int atOrAbove(double[] ascending, double gate) {
        int out = 0;
        for (int i = 0; i < ascending.length; i++) if (ascending[i] >= gate) out++;
        return out;
    }

    private static String pct(int a, int b) {
        return String.format(java.util.Locale.US, "%.1f%%", Double.valueOf(100d * a / Math.max(1, b)));
    }

    private static String fmt1(double v) {
        return String.format(java.util.Locale.US, "%.1f", Double.valueOf(v));
    }

    private static String fmt3(double v) {
        return String.format(java.util.Locale.US, "%.3f", Double.valueOf(v));
    }

    private static String fmt4(double v) {
        return String.format(java.util.Locale.US, "%.4f", Double.valueOf(v));
    }

    private static String signed(double v) {
        return (v >= 0d ? "+" : "") + fmt3(v);
    }

    private static String signed1(double v) {
        return (v >= 0d ? "+" : "") + fmt1(v);
    }

    private static String pad(String s, int width) {
        StringBuilder out = new StringBuilder(s);
        while (out.length() < width) out.append(' ');
        return out.toString();
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) out.append(sep);
            out.append(parts.get(i));
        }
        return out.toString();
    }
}