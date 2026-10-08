package com.rikkahub.wordlite;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 查重器对"改写过的段落"还剩多少眼力：只测量、只报数，判据一个字节都不动。
 *
 * 要回答的问题是：降重把自家查重器的读数压下去那几个点，是"真的不重复了"还是"只有这台器认不出来"。
 * 一段真论文正文原样进自建库（逐字基线），再把同一段文本按六个变量逐档加强改写，每档只动一个变量，
 * 拿同一份库重新比，看命中字数按哪一条判据掉得最快。
 *
 * 语料全部来自版本库，不联网：
 *   tests/corpus/real-prose.txt          真人论文（宽禁带半导体封装互连），既进库又出这一段稿子
 *   tests/corpus/oa-planted-cjmenet.txt  另一篇真论文正文 11 句，当同领域干扰项 + 降重闭环的基线
 *   tests/corpus/oa-abstract-cjmenet.txt 同一篇的 OpenAlex 摘要，摘要档干扰项
 *   tests/samples/input-liu.docx         真稿（开题报告），也是降重闭环那一步的靶子
 *
 * 这四个文件不是四篇独立的文献：real-prose.txt 就是 input-liu.docx 抽出来的正文（125 段逐段能在 docx
 * 里逐字找到）。所以自建库里同一篇正文存了两份（.txt 与 .docx），"命中文档数"那列的上天就是 2，
 * 它不是召回信号；真正的干扰项只剩另一篇论文的 11 句与它的摘要，去掉源段的对照库也只放这两份。
 * 词表随文件提交，一个随机数都没有：tests/paraphrase-synonyms.txt（同义词）、
 * tests/paraphrase-modifiers.txt（删修饰成分）、tests/paraphrase-connectives.txt（插连接词）、
 * tests/paraphrase-numbers.txt（数字与单位）。跑两次一字不差，末尾自己复核一遍。
 *
 * 分通道怎么来的：Hit.channel 只分"字面/改写"两档，分不出是三元组 Dice、包含率还是字符袋收下的枪。
 * 这里不新写判据，改走实测台早就留好的活值口子（TextCorpus.overrideThresholds / overrideBagFloor，
 * 产品路径一次也不读它），每一档把三条相似判据里的两条地板抬到 2.0（一定够不到），只留一条活着，
 * 于是"这一路单独还剩多少字"是量出来的而不是推出来的。代价是各通道之和不必等于全开那一列：
 * 并段（MERGE_GAP）、命中区取并集先占者得、指纹带咬句级命中那三件事都会让"三条各自跑"与"三条一起跑"
 * 差几个字。所以表里两个数都摆着，比的趋势看单开通道，落地的读数看全开。
 *
 * 跑法（私有类目录，不进 tools/test-host.ps1 的必跑清单）：
 *   pwsh tools/run-suites-private.ps1 -Tree . -OutDir artifacts/agent-paraphrase/out -Suite ParaphraseRecallAudit
 */
public final class ParaphraseRecallAudit {

    /** 五种匹配配置：0 = 产品判据全开，其余每次只留一条相似判据，逐字那一档三条全抬。 */
    private static final int ALL = 0, VERBATIM = 1, DICE = 2, CONTAINMENT = 3, BAG = 4;
    private static final String[] CONFIGS = {
        "全开", "逐字（指纹带+整段字面）", "SIMILAR_DICE 单开", "SIMILAR_CONTAINMENT 单开",
        "SIMILAR_BAG_DICE 单开",
    };
    /** 抬地板用的"一定够不到"值：三条判据都是 0~1 的比值，2.0 关得死。 */
    private static final float OFF = 2.0f;

    private ParaphraseRecallAudit() { }

    private static void head(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    private static void line(String label, String value) {
        System.out.println("  " + pad(label, 30) + value);
    }

    /** 中文按两格宽算一遍，表格才对得齐。 */
    private static String pad(String text, int width) {
        int wide = 0;
        for (int i = 0; i < text.length(); i++) wide += text.charAt(i) < 0x2E80 ? 1 : 2;
        StringBuilder out = new StringBuilder(text);
        for (int i = wide; i < width; i++) out.append(' ');
        return out.toString();
    }

    private static String pct(double value) {
        return String.format(Locale.CHINA, "%.2f%%", value);
    }

    private static String ratio(double now, double base) {
        if (base <= 0d) return base <= 0d && now <= 0d ? "  --  " : "   --";
        return String.format(Locale.CHINA, "%5.1f%%", now * 100d / base);
    }

    private static String read(String path) throws IOException {
        String text = new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
        if (text.length() > 0 && text.charAt(0) == 0xFEFF) text = text.substring(1);
        return text.replace("\r\n", "\n");
    }

    /** 词表文件：# 开头与空行不算。 */
    private static ArrayList<String> readWords(String path) throws IOException {
        ArrayList<String> out = new ArrayList<String>();
        for (String row : read(path).split("\n")) {
            String t = row.trim();
            if (!t.isEmpty() && !t.startsWith("#")) out.add(t);
        }
        return out;
    }

    private static final class Rule {
        final String from, to;
        Rule(String from, String to) { this.from = from; this.to = to; }
    }

    /** 读一张 "源=>替换" 表：源词不许重复、替换词不许等于源词，排序 = 最长优先 + 同长字典序。 */
    private static ArrayList<Rule> loadRules(String path) throws IOException {
        ArrayList<Rule> out = new ArrayList<Rule>();
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        for (String row : readWords(path)) {
            int at = row.indexOf("=>");
            if (at < 0) throw new IllegalStateException(path + " 有一行不带 => ：" + row);
            String from = row.substring(0, at).trim(), to = row.substring(at + 2).trim();
            if (from.isEmpty()) throw new IllegalStateException(path + " 有空的源词：" + row);
            if (from.equals(to)) throw new IllegalStateException(path + " 源词与替换词相同：" + row);
            if (!seen.add(from)) throw new IllegalStateException(path + " 源词重复：" + from);
            out.add(new Rule(from, to));
        }
        Collections.sort(out, new Comparator<Rule>() {
            public int compare(Rule a, Rule b) {
                if (a.from.length() != b.from.length()) return b.from.length() - a.from.length();
                return a.from.compareTo(b.from);
            }
        });
        return out;
    }

    private static final class Site {
        final int start;
        final Rule rule;
        Site(int start, Rule rule) { this.start = start; this.rule = rule; }
        int length() { return rule.from.length(); }
    }

    /** 单次从左到右、最长优先扫描；替下来的字不再回头扫，所以跑两次一定同一个结果。 */
    private static ArrayList<Site> scan(String text, ArrayList<Rule> rules) {
        ArrayList<Site> out = new ArrayList<Site>();
        int i = 0;
        while (i < text.length()) {
            Rule hit = null;
            for (int r = 0; r < rules.size(); r++) {
                if (text.startsWith(rules.get(r).from, i)) { hit = rules.get(r); break; }
            }
            if (hit == null) { i++; continue; }
            out.add(new Site(i, hit));
            i += hit.from.length();
        }
        return out;
    }

    /** 一个变量改了多少：位点数、吃掉多少字、吐出多少字、每个词各命中几次。 */
    private static final class Bill {
        int sites, fromChars, toChars;
        final Map<String, Integer> tally = new LinkedHashMap<String, Integer>();
        void count(String word) {
            sites++;
            toChars += word.length();
            Integer old = tally.get(word);
            tally.put(word, Integer.valueOf(old == null ? 1 : old.intValue() + 1));
        }

        void add(Site site, String replacement) {
            sites++;
            fromChars += site.length();
            toChars += replacement.length();
            Integer old = tally.get(site.rule.from);
            tally.put(site.rule.from, Integer.valueOf(old == null ? 1 : old.intValue() + 1));
        }
        int delta() { return toChars - fromChars; }
        String describe() {
            return sites + " 处 / 换掉 " + fromChars + " 字 / 落笔 " + toChars + " 字";
        }
    }
    /** 删词表：一行一个词，替换成空串。源词同样不许重复。 */
    private static ArrayList<Rule> loadDeletions(String path) throws IOException {
        ArrayList<Rule> out = new ArrayList<Rule>();
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        for (String word : readWords(path)) {
            if (!seen.add(word)) throw new IllegalStateException(path + " 词重复：" + word);
            out.add(new Rule(word, ""));
        }
        Collections.sort(out, new Comparator<Rule>() {
            public int compare(Rule a, Rule b) {
                if (a.from.length() != b.from.length()) return b.from.length() - a.from.length();
                return a.from.compareTo(b.from);
            }
        });
        return out;
    }

    /**
     * 按比例改：目标 = 改动字数占基线有效字数的比例。位点在整段等距抽（不是从头往后吃），
     * 抽到够用为止；词位用尽仍不够，就照实全用，账单里报实际值。
     */
    private static String applySpread(String text, ArrayList<Rule> rules, double target, int baseValid, Bill bill) {
        ArrayList<Site> sites = scan(text, rules);
        int pool = 0;
        for (int i = 0; i < sites.size(); i++) pool += sites.get(i).length();
        int need = (int) Math.ceil(target * baseValid);
        int k = pool <= 0 || sites.isEmpty() ? 0 : (int) Math.ceil(need * (double) sites.size() / pool);
        if (k > sites.size()) k = sites.size();
        boolean[] picked = new boolean[sites.size()];
        int got = 0;
        for (int j = 0; j < k && got < need; j++) {
            int idx = (int) ((long) j * sites.size() / Math.max(1, k));
            if (idx < 0 || idx >= sites.size() || picked[idx]) continue;
            picked[idx] = true;
            got += sites.get(idx).length();
        }
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        for (int i = 0; i < sites.size(); i++) {
            if (!picked[i]) continue;
            Site site = sites.get(i);
            out.append(text, cursor, site.start).append(site.rule.to);
            cursor = site.start + site.length();
            bill.add(site, site.rule.to);
        }
        out.append(text, cursor, text.length());
        return out.toString();
    }

    /** 全部位点都改（数字与单位那一档：换写法没有"比例"这回事，见多少换多少）。 */
    private static String applyAll(String text, ArrayList<Rule> rules, Bill bill) {
        ArrayList<Site> sites = scan(text, rules);
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        for (int i = 0; i < sites.size(); i++) {
            Site site = sites.get(i);
            out.append(text, cursor, site.start).append(site.rule.to);
            cursor = site.start + site.length();
            bill.add(site, site.rule.to);
        }
        out.append(text, cursor, text.length());
        return out.toString();
    }

    /** 插入位点：每个句首 + 每个句内 "，" "；" 之后。 */
    private static ArrayList<Integer> insertionSpots(String text) {
        LinkedHashSet<Integer> spots = new LinkedHashSet<Integer>();
        ArrayList<int[]> sentences = TextCorpus.sentences(text);
        for (int i = 0; i < sentences.size(); i++) spots.add(Integer.valueOf(sentences.get(i)[0]));
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '，' || c == '；') spots.add(Integer.valueOf(i + 1));
        }
        ArrayList<Integer> out = new ArrayList<Integer>(spots);
        Collections.sort(out, new Comparator<Integer>() {
            public int compare(Integer a, Integer b) { return a.intValue() - b.intValue(); }
        });
        return out;
    }

    /** 插连接词：位点等距抽，词按表里的顺序轮转取，同样不用随机数。 */
    private static String insertWords(String text, ArrayList<String> words, double target, int baseValid, Bill bill) {
        ArrayList<Integer> spots = insertionSpots(text);
        int average = 0;
        for (int i = 0; i < words.size(); i++) average += words.get(i).length();
        average = Math.max(1, Math.round(average / (float) Math.max(1, words.size())));
        int need = (int) Math.ceil(target * baseValid);
        int want = Math.min(spots.size(), Math.max(1, (int) Math.ceil(need / (double) average)));
        boolean[] picked = new boolean[spots.size()];
        int got = 0, taken = 0;
        for (int j = 0; j < want && got < need; j++) {
            int idx = (int) ((long) j * spots.size() / Math.max(1, want));
            if (idx < 0 || idx >= spots.size() || picked[idx]) continue;
            picked[idx] = true;
            got += words.get(taken % words.size()).length();
            taken++;
        }
        ArrayList<Integer> chosen = new ArrayList<Integer>();
        for (int i = 0; i < picked.length; i++) if (picked[i]) chosen.add(spots.get(i));
        for (int i = chosen.size() - 1; i >= 0; i--) {
            int at = chosen.get(i).intValue();
            String word = words.get(i % words.size());
            text = text.substring(0, at) + word + text.substring(at);
            bill.count(word);
        }
        return text;
    }

    /** 调序：段内按块倒装（block = 2/3），block <= 0 整段倒序。切句用引擎自己的 TextCorpus.sentences。 */
    private static String reorder(String text, int block) {
        ArrayList<int[]> spans = TextCorpus.sentences(text);
        ArrayList<String> parts = new ArrayList<String>();
        for (int i = 0; i < spans.size(); i++) {
            int[] span = spans.get(i);
            if (i > 0) {
                String gap = text.substring(spans.get(i - 1)[1], span[0]);
                if (gap.trim().length() > 0) {
                    throw new IllegalStateException("探针只吃句子首尾相接的段落，句间还有：[" + gap + "]");
                }
            }
            parts.add(text.substring(span[0], span[1]));
        }
        StringBuilder out = new StringBuilder();
        if (block <= 0) {
            for (int i = parts.size() - 1; i >= 0; i--) out.append(parts.get(i));
            return out.toString();
        }
        for (int start = 0; start < parts.size(); start += block) {
            int end = Math.min(parts.size(), start + block);
            for (int i = end - 1; i >= start; i--) out.append(parts.get(i));
        }
        return out.toString();
    }

    /** 取哪一段：非空行里句子数 >= 4 且有效字数最长的那一段（并列取靠前的一条）。 */
    private static String pickParagraph(String prose) {
        String best = "";
        int bestChars = 0, bestSentences = 0;
        for (String row : prose.split("\n")) {
            String p = row.trim();
            if (p.isEmpty()) continue;
            int sentences = TextCorpus.sentences(p).size();
            if (sentences < 4) continue;
            int chars = TextCorpus.validCount(TextCorpus.normalize(p), 0, p.length());
            if (chars > bestChars) { best = p; bestChars = chars; bestSentences = sentences; }
        }
        if (best.isEmpty()) throw new IllegalStateException("语料里没有句子数 >= 4 的段落");
        System.out.println("  取段规则：句子数 >= 4 且有效字数最长 -> " + bestChars + " 有效字 / "
                + bestSentences + " 句");
        return best;
    }

    /** 一次比对：按配置把不要的那几条判据地板抬到够不到，跑完立刻还原。 */
    private static TextCorpus.Report matchIn(TextCorpus corpus, String text, int config) {
        try {
            switch (config) {
                case VERBATIM:
                    TextCorpus.overrideThresholds(OFF, OFF);
                    TextCorpus.overrideBagFloor(OFF);
                    break;
                case DICE:
                    TextCorpus.overrideThresholds(TextCorpus.SIMILAR_DICE, OFF);
                    TextCorpus.overrideBagFloor(OFF);
                    break;
                case CONTAINMENT:
                    TextCorpus.overrideThresholds(OFF, TextCorpus.SIMILAR_CONTAINMENT);
                    TextCorpus.overrideBagFloor(OFF);
                    break;
                case BAG:
                    TextCorpus.overrideThresholds(OFF, OFF);
                    TextCorpus.overrideBagFloor(TextCorpus.SIMILAR_BAG_DICE);
                    break;
                default:
                    break;
            }
            return corpus.match(text, null, TextCorpus.structure(text).spanArray());
        } finally {
            TextCorpus.restoreThresholds();
            TextCorpus.restoreBagFloor();
        }
    }
    private static final class Tier {
        final String name, how;
        final String text;
        final Bill bill;
        Tier(String name, String how, String text, Bill bill) {
            this.name = name; this.how = how; this.text = text; this.bill = bill;
        }
    }

    /** 逐档加强：每一档只动一个变量，比例一律按"占基线段落有效字数的百分比"算。 */
    private static ArrayList<Tier> buildTiers(String base, int baseValid, ArrayList<Rule> synonyms,
                                              ArrayList<Rule> deletions, ArrayList<String> connectives,
                                              ArrayList<Rule> numbers) {
        ArrayList<Tier> out = new ArrayList<Tier>();
        out.add(new Tier("基线（逐字种入）", "未改", base, new Bill()));
        double[] steps = { 0.05d, 0.10d, 0.20d, 0.35d };
        for (int i = 0; i < steps.length; i++) {
            Bill bill = new Bill();
            out.add(new Tier("同义词替换 " + (int) Math.round(steps[i] * 100) + "%", "只换词，位点等距抽",
                    applySpread(base, synonyms, steps[i], baseValid, bill), bill));
        }
        int[] blocks = { 2, 3, 0 };
        String[] blockNames = { "调序·两句块倒装", "调序·三句块倒装", "调序·全段倒序" };
        for (int i = 0; i < blocks.length; i++) {
            out.add(new Tier(blockNames[i], "只调句序，一字不换", reorder(base, blocks[i]), new Bill()));
        }
        double[] growth = { 0.05d, 0.10d };
        for (int i = 0; i < growth.length; i++) {
            Bill bill = new Bill();
            out.add(new Tier("插连接词 +" + (int) Math.round(growth[i] * 100) + "%", "只插虚词，位点等距抽",
                    insertWords(base, connectives, growth[i], baseValid, bill), bill));
        }
        for (int i = 0; i < growth.length; i++) {
            Bill bill = new Bill();
            out.add(new Tier("删修饰成分 -" + (int) Math.round(growth[i] * 100) + "%", "只删虚词修饰，位点等距抽",
                    applySpread(base, deletions, growth[i], baseValid, bill), bill));
        }
        Bill numberBill = new Bill();
        out.add(new Tier("数字与单位换写法", "数字换汉字、单位符号换中文读法",
                applyAll(base, numbers, numberBill), numberBill));

        // 叠加档：一个学生拿降重软件过一遍的形状——四件事一起上，句序只按两句块倒装。
        Bill stacked = new Bill();
        String text = applyAll(base, numbers, stacked);
        text = applySpread(text, synonyms, 0.20d, baseValid, stacked);
        text = applySpread(text, deletions, 0.05d, baseValid, stacked);
        text = insertWords(text, connectives, 0.05d, baseValid, stacked);
        text = reorder(text, 2);
        out.add(new Tier("叠加（降重软件过一遍）", "数字单位 + 同义 20% + 删 5% + 插 5% + 两句块倒装",
                text, stacked));
        return out;
    }

    private static int valid(String text) {
        return TextCorpus.validCount(TextCorpus.normalize(text), 0, text.length());
    }

    private static int documentCount(TextCorpus.Report report) {
        LinkedHashSet<String> ids = new LinkedHashSet<String>();
        for (int i = 0; i < report.hits.size(); i++) {
            TextCorpus.Hit hit = report.hits.get(i);
            ids.add(hit.source == null ? "?" : hit.source.id);
        }
        return ids.size();
    }

    private static float bestScore(TextCorpus.Report report) {
        float best = 0f;
        for (int i = 0; i < report.hits.size(); i++) best = Math.max(best, report.hits.get(i).score);
        return best;
    }

    /** 整段那一档的判据读数：整段对整段的三元组 Dice 与字符袋 Dice，各自对着自己的地板。 */
    private static String wholeTextVerdict(String query, String source) {
        float triple = TextCorpus.dice(query, source);
        float bag = TextCorpus.bagDice(query, source);
        return "三元组 " + trim(triple) + (triple >= TextCorpus.SIMILAR_DICE ? "过" : "不过")
                + "｜袋 " + trim(bag) + (bag >= TextCorpus.SIMILAR_BAG_DICE ? "过" : "不过");
    }

    private static String trim(float value) {
        return String.format(Locale.CHINA, "%.3f", Float.valueOf(value));
    }

    /**
     * 句级那一档的过线句数：改写后的每一句各自去对源段里的每一句，三元组 Dice 与袋 Dice 各数几句过线；
     * 再数几句连"袋口径可达上界"（bagReach = 2·min/(|A|+|B|)）都够不到地板——够不到是结构性不可达，
     * 不是阈值调高的锅。这一段与 DetectionFloor 用的是同一批原语（TextCorpus.dice/bagDice/bagReach）。
     */
    private static String sentenceVerdict(String query, String source) {
        ArrayList<String> left = sentencesOf(query), right = sentencesOf(source);
        char[] sourceBag = TextCorpus.bagOf(source);
        int triple = 0, bag = 0, reach = 0;
        for (int i = 0; i < left.size(); i++) {
            String clause = left.get(i);
            float bestTriple = 0f, bestBag = 0f, bestReach = 0f;
            for (int j = 0; j < right.size(); j++) {
                bestTriple = Math.max(bestTriple, TextCorpus.dice(clause, right.get(j)));
                bestBag = Math.max(bestBag, TextCorpus.bagDice(clause, right.get(j)));
                bestReach = Math.max(bestReach, TextCorpus.bagReach(TextCorpus.bagOf(clause),
                        TextCorpus.bagOf(right.get(j))));
            }
            bestReach = Math.max(bestReach, TextCorpus.bagReach(TextCorpus.bagOf(clause), sourceBag));
            if (bestTriple >= TextCorpus.SIMILAR_DICE) triple++;
            if (bestBag >= TextCorpus.SIMILAR_BAG_DICE) bag++;
            if (bestReach >= TextCorpus.SIMILAR_BAG_DICE) reach++;
        }
        return "三元组过线 " + triple + "/" + left.size() + " 句｜袋过线 " + bag + "/" + left.size()
                + " 句｜袋可达上界够线 " + reach + "/" + left.size() + " 句";
    }

    private static ArrayList<String> sentencesOf(String text) {
        ArrayList<String> out = new ArrayList<String>();
        ArrayList<int[]> spans = TextCorpus.sentences(text);
        for (int i = 0; i < spans.size(); i++) {
            String piece = text.substring(spans.get(i)[0], spans.get(i)[1]).trim();
            if (valid(piece) >= TextCorpus.MIN_SENTENCE_CHARS) out.add(piece);
        }
        return out;
    }
    /**
     * 判据开关自检：本实验里包含率那一路的读数与逐字那一路逐位相同（边际贡献 0 字），这既可能是事实，
     * 也可能只是我的开关没接上。这里造一条只有包含率收得下的样本分清这两种：库里放一句 17 字的短句，
     * 稿子 = 一句长短比约 3.3 倍的长句（原句整块嵌在末尾）——连续逐字只有 17 字，够不到 MIN_MATCH=18，
     * 所以逐字那一档必须报 0；三元组 Dice 与字符袋可达上界都打印出来，看它们各自离自己的地板多远。
     * 硬断言只有两条：逐字报 0（带子确实断在 18 字以下）与包含率报得出来（那一路的开关是通的）。
     */
    private static void selfCheck() {
        String shortSentence = "互连层应同时具备良好的导电导热性能";
        String embed = "在批量装配与回流焊的产线上，工艺窗口往往比实验室数据更窄，返修记录也对得上，" + shortSentence;
        TextCorpus tiny = new TextCorpus();
        TextCorpus.Source source = new TextCorpus.Source();
        source.id = "selfcheck:short-sentence";
        source.title = "开关自检：一句 17 字的短句";
        source.engine = "local";
        source.material = DuplicateEngine.MATERIAL_FULL;
        tiny.add(source, shortSentence + "。");
        int[] reading = new int[5];
        StringBuilder line = new StringBuilder();
        for (int c = 0; c < 5; c++) {
            reading[c] = matchIn(tiny, embed, c).duplicateChars;
            line.append(CONFIGS[c]).append(' ').append(reading[c]).append(" 字  ");
        }
        char[] left = TextCorpus.bagOf(embed), right = TextCorpus.bagOf(shortSentence);
        System.out.println("  判据开关自检  库 " + valid(shortSentence) + " 字 / 稿 " + valid(embed)
                + " 字，长短比 " + String.format(Locale.CHINA, "%.2f",
                valid(embed) / (double) valid(shortSentence)) + "；三元组 Dice "
                + trim(TextCorpus.dice(embed, shortSentence)) + "（地板 " + trim(TextCorpus.SIMILAR_DICE)
                + "）｜袋 Dice " + trim(TextCorpus.bagDice(embed, shortSentence)) + "，可达上界 "
                + trim(TextCorpus.bagReach(left, right)) + "（地板 " + trim(TextCorpus.SIMILAR_BAG_DICE) + "）");
        System.out.println("              " + line);
        if (reading[VERBATIM] != 0 || reading[CONTAINMENT] <= 0) {
            throw new IllegalStateException("判据开关自检没按预期分开：逐字 " + reading[VERBATIM]
                    + " / Dice " + reading[DICE] + " / 包含 " + reading[CONTAINMENT] + " / 袋 " + reading[BAG]);
        }
    }

    /** 第几档第一次让这一路的命中字数掉到基线的 percent% 以下；没有就返回 -1。 */
    private static int firstBelow(int[][] dup, int config, int percent) {
        for (int t = 1; t < dup.length; t++) {
            if (dup[0][config] > 0 && dup[t][config] * 100 < dup[0][config] * percent) return t;
        }
        return -1;
    }

    /**
     * 自家降重写实的形状：改完的每一段拿它自己的原文当尺，整段口径过不过线、句级口径过几句。
     * DetectionFloor 量到的是"整段过线而句级逐句不过线"，这里看产品自己的改写落在哪一侧。
     */
    private static String segmentSplit(RewriteLoop.Result result) {
        int changed = 0, wholeTriple = 0, wholeBag = 0, sentences = 0, triplePass = 0, bagPass = 0;
        List<RewriteLoop.Segment> details = RewriteLoop.details(result);
        for (int i = 0; i < details.size(); i++) {
            RewriteLoop.Segment segment = details.get(i);
            if (!segment.changed || segment.original.equals(segment.adopted)) continue;
            changed++;
            if (TextCorpus.dice(segment.adopted, segment.original) >= TextCorpus.SIMILAR_DICE) wholeTriple++;
            if (TextCorpus.bagDice(segment.adopted, segment.original) >= TextCorpus.SIMILAR_BAG_DICE) wholeBag++;
            ArrayList<String> mine = sentencesOf(segment.adopted), theirs = sentencesOf(segment.original);
            for (int m = 0; m < mine.size(); m++) {
                sentences++;
                float bestTriple = 0f, bestBag = 0f;
                for (int j = 0; j < theirs.size(); j++) {
                    bestTriple = Math.max(bestTriple, TextCorpus.dice(mine.get(m), theirs.get(j)));
                    bestBag = Math.max(bestBag, TextCorpus.bagDice(mine.get(m), theirs.get(j)));
                }
                if (bestTriple >= TextCorpus.SIMILAR_DICE) triplePass++;
                if (bestBag >= TextCorpus.SIMILAR_BAG_DICE) bagPass++;
            }
        }
        return changed + " 段被改写：整段口径三元组过线 " + wholeTriple + " 段、袋过线 " + wholeBag
                + " 段；句级口径 " + sentences + " 句里三元组过线 " + triplePass + " 句、袋过线 " + bagPass + " 句";
    }

    /** 落盘进自建库（走产品入口一），material 为空按正文算。 */
    private static void put(LocalLibrary library, String name, byte[] content, String material) {
        LocalLibrary.AddResult result = library.addDocument(name, content, null, false, material);
        if (!result.ok) throw new IllegalStateException("自建库收下 " + name + " 失败：" + result.error);
    }

    private static String bodyOf(String path) throws IOException {
        StringBuilder out = new StringBuilder();
        for (String row : read(path).split("\n")) {
            if (row.startsWith("#") || row.trim().isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(row.trim());
        }
        return out.toString();
    }

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : "tests/corpus";
        String docx = args.length > 1 ? args[1] : "tests/samples/input-liu.docx";
        String outDir = args.length > 2 ? args[2] : "artifacts/tests/paraphrase-recall";
        String tableDir = args.length > 3 ? args[3] : "tests";
        new File(outDir).mkdirs();

        head("输入");
        ArrayList<Rule> synonyms = loadRules(tableDir + "/paraphrase-synonyms.txt");
        ArrayList<Rule> deletions = loadDeletions(tableDir + "/paraphrase-modifiers.txt");
        ArrayList<String> connectives = readWords(tableDir + "/paraphrase-connectives.txt");
        ArrayList<Rule> numbers = loadRules(tableDir + "/paraphrase-numbers.txt");
        line("同义词表", synonyms.size() + " 条（" + tableDir + "/paraphrase-synonyms.txt）");
        line("删词表", deletions.size() + " 条");
        line("插入词表", connectives.size() + " 条");
        line("数字与单位表", numbers.size() + " 条");

        String prose = read(dir + "/real-prose.txt");
        String planted = bodyOf(dir + "/oa-planted-cjmenet.txt");
        String oaAbstract = bodyOf(dir + "/oa-abstract-cjmenet.txt");
        byte[] manuscript = Files.readAllBytes(new File(docx).toPath());

        File libraryDir = new File(outDir, "library");
        libraryDir.mkdirs();
        LocalLibrary library = new LocalLibrary(libraryDir);
        library.clear();
        put(library, "real-prose.txt", prose.getBytes(StandardCharsets.UTF_8), DuplicateEngine.MATERIAL_FULL);
        put(library, "oa-planted-cjmenet.txt", planted.getBytes(StandardCharsets.UTF_8),
                DuplicateEngine.MATERIAL_FULL);
        put(library, "oa-abstract-cjmenet.txt", oaAbstract.getBytes(StandardCharsets.UTF_8),
                DuplicateEngine.MATERIAL_ABSTRACT);
        put(library, "input-liu.docx", manuscript, DuplicateEngine.MATERIAL_FULL);
        TextCorpus corpus = new TextCorpus();
        library.index(corpus);
        line("自建库", library.size() + " 篇 / 入库句子 " + corpus.sentenceCount() + " 条 / 摘要档 1 篇");

        head("稿子：从 real-prose.txt 里取一段，原样进库（逐字基线）");
        String base = pickParagraph(prose);
        int baseValid = valid(base);
        line("这一段", baseValid + " 有效字 / " + sentencesOf(base).size() + " 句");
        line("开头", base.substring(0, Math.min(36, base.length())) + "……");

        ArrayList<Tier> tiers = buildTiers(base, baseValid, synonyms, deletions, connectives, numbers);
        ArrayList<Tier> second = buildTiers(base, baseValid, synonyms, deletions, connectives, numbers);
        for (int i = 0; i < tiers.size(); i++) {
            if (!tiers.get(i).text.equals(second.get(i).text)) {
                throw new IllegalStateException("同一份词表跑两次不一致：" + tiers.get(i).name);
            }
        }
        line("确定性", tiers.size() + " 档文本两次构造逐字相同（词表里没有随机数）");

        // 库去掉源段那一档：剩下的三篇（另一主题正文 + 同领域 11 句 + 那篇的摘要 + 真稿）当干扰项。
        File controlDir = new File(outDir, "library-without-source");
        controlDir.mkdirs();
        LocalLibrary control = new LocalLibrary(controlDir);
        control.clear();
        put(control, "oa-planted-cjmenet.txt", planted.getBytes(StandardCharsets.UTF_8),
                DuplicateEngine.MATERIAL_FULL);
        put(control, "oa-abstract-cjmenet.txt", oaAbstract.getBytes(StandardCharsets.UTF_8),
                DuplicateEngine.MATERIAL_ABSTRACT);
        TextCorpus noiseCorpus = new TextCorpus();
        control.index(noiseCorpus);
        line("干扰库", control.size() + " 篇 / 入库句子 " + noiseCorpus.sentenceCount()
                + " 条（另一篇论文的 11 句 + 它的摘要；input-liu.docx 不放，它就是源段那一篇）");
        TextCorpus.Report blank = new TextCorpus().match(base, null, TextCorpus.structure(base).spanArray());
        TextCorpus.Report probe = matchIn(noiseCorpus, base, ALL);
        StringBuilder titles = new StringBuilder();
        for (int i = 0; i < probe.hits.size(); i++) {
            TextCorpus.Hit hit = probe.hits.get(i);
            titles.append(hit.source == null ? "?" : hit.source.title).append(' ')
                    .append(hit.start).append('-').append(hit.end).append("；");
        }
        line("空库自检", blank.duplicateChars + " 字 / " + blank.hits.size() + " 处");
        line("基线对干扰库", probe.duplicateChars + " 字 / " + probe.hits.size() + " 处：" + titles);
        selfCheck();

        head("逐档重比：同一份库，五种判据配置各跑一遍");
        int[][] dup = new int[tiers.size()][5];
        int[][] hits = new int[tiers.size()][5];
        int[][] noise = new int[tiers.size()][5];
        TextCorpus.Report[] product = new TextCorpus.Report[tiers.size()];
        long began = System.currentTimeMillis();
        for (int t = 0; t < tiers.size(); t++) {
            for (int c = 0; c < 5; c++) {
                TextCorpus.Report report = matchIn(corpus, tiers.get(t).text, c);
                dup[t][c] = report.duplicateChars;
                hits[t][c] = report.hits.size();
                if (c == ALL) product[t] = report;
                noise[t][c] = matchIn(noiseCorpus, tiers.get(t).text, c).duplicateChars;
            }
        }
        long elapsed = System.currentTimeMillis() - began;

        System.out.println();
        System.out.println("  " + pad("档位", 34) + pad("改动", 20) + pad("命中字数/分母", 18)
                + pad("处数", 7) + pad("文档", 6) + pad("overallRate", 13) + pad("与基线之比", 12)
                + pad("最高分", 9) + "干扰库命中");
        for (int t = 0; t < tiers.size(); t++) {
            Tier tier = tiers.get(t);
            int delta = valid(tier.text) - baseValid;
            String change = (delta >= 0 ? "+" : "") + delta + " 字 / " + tier.bill.sites + " 处";
            System.out.println("  " + pad(tier.name, 34) + pad(change, 20)
                    + pad(dup[t][ALL] + "/" + product[t].comparedChars, 18)
                    + pad(String.valueOf(hits[t][ALL]), 7)
                    + pad(String.valueOf(documentCount(product[t])), 6)
                    + pad(pct(product[t].overallRate), 13)
                    + pad(ratio(dup[t][ALL], dup[0][ALL]), 12)
                    + pad(trim(bestScore(product[t])), 9) + noise[t][ALL] + " 字");
        }

        System.out.println();
        System.out.println("  分通道（每条判据单独活着，其余两条地板抬到 " + OFF + "；括号 = 与该通道自己基线之比）");
        System.out.println("  " + pad("档位", 34) + pad("逐字", 18) + pad("SIMILAR_DICE", 18)
                + pad("SIMILAR_CONTAINMENT", 22) + pad("SIMILAR_BAG_DICE", 18) + pad("三路之和", 12)
                + "全开");
        for (int t = 0; t < tiers.size(); t++) {
            int sum = dup[t][VERBATIM] + dup[t][DICE] + dup[t][CONTAINMENT] + dup[t][BAG];
            System.out.println("  " + pad(tiers.get(t).name, 34)
                    + pad(dup[t][VERBATIM] + " (" + ratio(dup[t][VERBATIM], dup[0][VERBATIM]) + ")", 18)
                    + pad(dup[t][DICE] + " (" + ratio(dup[t][DICE], dup[0][DICE]) + ")", 18)
                    + pad(dup[t][CONTAINMENT] + " (" + ratio(dup[t][CONTAINMENT], dup[0][CONTAINMENT]) + ")", 22)
                    + pad(dup[t][BAG] + " (" + ratio(dup[t][BAG], dup[0][BAG]) + ")", 18)
                    + pad(String.valueOf(sum), 12) + dup[t][ALL]);
        }

        head("粒度对照：同一档改写，整段那一路 vs 句级那一路");
        System.out.println("  " + pad("档位", 34) + "整段对整段（地板 0.50 / 0.72）");
        for (int t = 0; t < tiers.size(); t++) {
            System.out.println("  " + pad(tiers.get(t).name, 34) + wholeTextVerdict(tiers.get(t).text, base));
            System.out.println("  " + pad("", 34) + sentenceVerdict(tiers.get(t).text, base));
        }
        System.out.println("  注：整段那一行是拿 TextCorpus.dice / bagDice 直接量改写段与源段（DetectionFloor"
                + " 同一批原语）；句级那一行是产品自己的切句口径，每一句去对源段里最像的一句。");
        head("降重闭环自己的复核读数（判据、预算、基线全照 RewriteRateRegression，不另算一套）");
        List<String> plantedSentences = RewriteRateRegression.lines(dir + "/oa-planted-cjmenet.txt");
        TextCorpus loopCorpus = new TextCorpus();
        TextCorpus.Source loopSource = new TextCorpus.Source();
        loopSource.id = "local:oa-planted-cjmenet";
        loopSource.title = "自建库：钛铝合金低温切削（开放获取，11 句派生）";
        loopSource.engine = "local";
        loopSource.material = DuplicateEngine.MATERIAL_FULL;
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < plantedSentences.size(); i++) joined.append(plantedSentences.get(i)).append("。\n");
        loopCorpus.add(loopSource, joined.toString());
        String target = outDir + "/input-liu-planted.docx";
        RewriteRateRegression.plant(docx, plantedSentences, target);
        String before = RewriteRateRegression.text(target);
        line("基线与靶子", loopCorpus.sentenceCount() + " 句入库 / 靶子 " + before.length() + " 字");
        RewriteLoop.Limits limits = new RewriteLoop.Limits();
        limits.rounds = 10;
        limits.depth = 8;
        limits.candidates = 8;
        limits.verifications = 240;
        long loopBegan = System.currentTimeMillis();
        RewriteLoop.Result result = RewriteLoop.run(before, loopCorpus, RewriteRateRegression.TERMS, limits);
        line("闭环复核读数", "改写前 " + result.hitsBefore + " 处 / " + result.dupBefore + " 字 = "
                + pct(result.rateBefore) + "  ->  改完还剩 " + result.hitsAfter + " 处 / " + result.dupAfter
                + " 字 = " + pct(result.rateAfter));
        line("闭环自己的账", "命中段 " + result.segments + " 段，改写覆盖 " + result.segmentsChanged
                + " 段，改完单独比对已无命中 " + result.segmentsCleared + " 段；整篇验证 " + result.verified
                + " 次，耗时 " + (System.currentTimeMillis() - loopBegan) + " ms");
        line("闭环判定", result.verdict);
        StringBuilder split = new StringBuilder();
        int[] beforeSplit = new int[5], afterSplit = new int[5];
        for (int c = 0; c < 5; c++) {
            beforeSplit[c] = matchIn(loopCorpus, before, c).duplicateChars;
            afterSplit[c] = matchIn(loopCorpus, result.text, c).duplicateChars;
            if (c > 0) split.append("  ");
            split.append(CONFIGS[c]).append(" ").append(beforeSplit[c]).append("->").append(afterSplit[c]).append(" 字");
        }
        line("补充·同一把尺的分通道", split.toString());
        line("补充·逐段粒度（改完的段 vs 它自己的原文）", segmentSplit(result));

        head("结论（只报数，阈值一个没动）");
        line("逐字基线", dup[0][ALL] + "/" + product[0].comparedChars + " 字 = " + pct(product[0].overallRate)
                + "，分通道 逐字 " + dup[0][VERBATIM] + " / Dice " + dup[0][DICE] + " / 包含 "
                + dup[0][CONTAINMENT] + " / 袋 " + dup[0][BAG]);
        int last = tiers.size() - 1;
        for (int c = 0; c < 5; c++) {
            int leak = firstBelow(dup, c, 90), blind = firstBelow(dup, c, 50);
            System.out.println("  " + pad(CONFIGS[c], 34)
                    + (leak < 0 ? "全程没掉到九成以下" : "首档掉到九成以下：「" + tiers.get(leak).name
                            + "」剩 " + ratio(dup[leak][c], dup[0][c]))
                    + "；" + (blind < 0 ? "全程没跌破一半" : "首档跌破一半：「" + tiers.get(blind).name
                            + "」剩 " + ratio(dup[blind][c], dup[0][c]))
                    + "；最狠一档（" + tiers.get(last).name + "）剩 " + ratio(dup[last][c], dup[0][c]));
        }
        boolean containmentIsVerbatim = true;
        for (int t = 0; t < tiers.size(); t++) {
            if (dup[t][CONTAINMENT] != dup[t][VERBATIM]) containmentIsVerbatim = false;
        }
        int firstToDie = dup[last][DICE] <= dup[last][BAG] ? DICE : BAG;
        int lastToDie = firstToDie == DICE ? BAG : DICE;
        line("包含率那一路", (containmentIsVerbatim
                ? "14 档读数与逐字那一路逐位相同：这条判据在本实验的形状里边际贡献 0 字。"
                : "14 档读数与逐字那一路不完全相同，逐档差值见分通道表。")
                + "它要长短比 1.6~3.76 倍，而整段/整句改写的长短比≈1；开关自检证过它能收下候选。");
        line("先倒下的一条", CONFIGS[firstToDie] + "：" + tiers.get(last).name + " 只剩 "
                + ratio(dup[last][firstToDie], dup[0][firstToDie]));
        line("最后倒下的一条", CONFIGS[lastToDie] + "：" + tiers.get(last).name + " 还剩 "
                + ratio(dup[last][lastToDie], dup[0][lastToDie]));
        System.out.println("  " + pad("整段那一路 vs 句级那一路", 34) + "叠加档整段袋 Dice "
                + trim(TextCorpus.bagDice(tiers.get(last).text, base)) + "（地板 "
                + trim(TextCorpus.SIMILAR_BAG_DICE) + "）与三元组 "
                + trim(TextCorpus.dice(tiers.get(last).text, base)) + "（地板 " + trim(TextCorpus.SIMILAR_DICE)
                + "）——证据在整段口径上还活着，掉下去的是句级读数");
        line("总耗时", elapsed + " ms（" + tiers.size() + " 档 x 5 种判据配置 x 2 份库）");
    }
}