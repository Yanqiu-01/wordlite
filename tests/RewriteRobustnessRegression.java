package com.rikkahub.wordlite;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抗改写召回实测台：改写口径下的召回。
 *
 * 口径。从真人语料里取若干段当文献库，一段算一篇；再把其中一部分段以八种口径之一种种进一份稿子，
 * 稿子里其余段落是同一语料里**没有进库**的段落，充当学生自己写的话。跑一遍 TextCorpus.match()，量三件事：
 *   召回 = 种进去那段里被标成重复的有效字符 / 这段的有效字符；
 *   归属 = 其中记在真正出处名下的字符 / 被标记的字符（指到隔壁那篇要扣分，不算召回）；
 *   噪声 = 那些压根没进库的段落被误标的字符。噪声不为零，同一行的召回就是拿误报换来的。
 *
 * 八种口径全是确定性变换：同一份输入永远给同一份输出，不含随机数、不联网，所以能进闸门。
 * 「降重工具」这一条用的是本应用自己的 LocalRewriter，逐轮采纳第一条建议，等价于一个把所有建议
 * 都点采纳的用户。第三方改写工具不在射程内，这一点在文档里同样写明白。
 */
public final class RewriteRobustnessRegression {
    private static int count;
    private static int droppedCitations;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 短段一句改写就能整段换掉，测不出稳健性。 */
    private static final int MIN_PARAGRAPH_CHARS = 120;
    private static final int MIN_COPIES = 6;
    /** 采纳建议的轮数上限：离线降重一次只给三条建议，这里采纳到没有为止。 */
    private static final int REWRITE_ROUNDS = 24;
    /** -Drrd=1 时逐条打印被误标的段落片段，只在排查噪声来源时开。 */
    private static final boolean DEBUG = System.getProperty("rrd") != null;
    /** -Drrs=1 时扫阈值：每条改写口径的召回与噪声一起报，用来找拐点。 */
    private static final boolean SWEEP = System.getProperty("rrs") != null;
    /** -Drrroc=1 时打印分值分布：漏掉的字是卡在阈值上，还是压根没进候选。 */
    private static final boolean ROC = System.getProperty("rrroc") != null;
    private static final String[] ROC_CASES = { "verbatim", "sub-char-25", "pruned", "split-commas" };
    private static final String[] SWEEP_CASES = {
        "verbatim", "synonym", "sub-char-10", "sub-char-25", "pruned", "split-commas", "spliced",
    };
    private static final float[] SWEEP_DICE = { 0.55f, 0.50f, 0.45f, 0.40f, 0.35f };
    private static final float[] SWEEP_CONTAINMENT = { 0.90f, 0.80f, 0.70f };

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        count++;
        System.out.println("PASS " + message);
    }

    interface Mutator {
        String run(String text);
    }

    /**
     * 嵌入改写：把库里某篇的一句短句整块嵌进一句学生自己写的长句里。量两个数：
     * 嵌进去那段被标没有（该标的要标），以及它**外面**那些字被标了多少字（多报）。
     * 为什么要单独一档：包含率那条路记的命中区间是整个片段，不是实际共享的那一段，
     * 于是"抄进去一句 22 字、报出来整句 90 字全红"是它天生的形状。这个数不量出来，
     * 它就会以"相似率比知网卡出来的高"的形式出现在用户的报告里，而没人知道为什么。
     */
    private static void embedding(ArrayList<String> copies, ArrayList<String> fillers,
                                 ArrayList<TextCorpus.Source> sources, TextCorpus corpus) {
        StringBuilder draft = new StringBuilder();
        ArrayList<int[]> phrases = new ArrayList<int[]>();
        ArrayList<int[]> sentences = new ArrayList<int[]>();
        for (int i = 0; i < copies.size(); i++) {
            String shortOne = shortestSentence(copies.get(i));
            String host = longestSentence(fillers.get(i % fillers.size()));
            if (shortOne == null || host == null) continue;
            String body = stripTail(host);
            int comma = body.indexOf('，');
            if (comma < 4) continue;
            int from = draft.length();
            draft.append(body, 0, comma).append('，').append(stripTail(shortOne)).append('，')
                    .append(body.substring(comma + 1));
            int end = draft.length();
            draft.append('。').append('\n');
            int phraseStart = from + comma + 1 + 1;
            phrases.add(new int[] { phraseStart, phraseStart + stripTail(shortOne).length() });
            sentences.add(new int[] { from, end });
        }
        if (phrases.isEmpty()) throw new AssertionError("嵌入夹具一句都没拼出来");
        String text = draft.toString();
        String norm = TextCorpus.normalize(text);
        ArrayList<TextCorpus.Hit> hits = corpus.match(text, null).hits;
        int phraseChars = 0, phraseFlagged = 0, hostChars = 0, hostFlagged = 0;
        for (int i = 0; i < phrases.size(); i++) {
            int[] phrase = phrases.get(i);
            int[] sentence = sentences.get(i);
            phraseChars += valid(text.substring(phrase[0], phrase[1]));
            phraseFlagged += cover(norm, phrase[0], phrase[1], hits, null);
            hostChars += valid(text.substring(sentence[0], sentence[1])) - valid(text.substring(phrase[0], phrase[1]));
            hostFlagged += cover(norm, sentence[0], sentence[1], hits, null)
                    - cover(norm, phrase[0], phrase[1], hits, null);
        }
        System.out.println();
        System.out.println("嵌入改写 " + phrases.size() + " 句：嵌进去的 " + phraseChars + " 字标了 "
                + phraseFlagged + " 字（" + percent(phraseChars == 0 ? 0d : phraseFlagged * 100d / phraseChars)
                + "），它外面的 " + hostChars + " 字被连带标了 " + hostFlagged + " 字");
        check(phraseFlagged * 100d >= 90d * phraseChars,
                "整句原文嵌进长句也得认出来：实测 " + phraseFlagged + "/" + phraseChars);
        // 实测每句连带标红 41 字（嵌进去 778 字认回 716 字 = 92.0%，外面 1578 字被带走 688 字 = 43.6%）。
        // 这是一条已知缺陷的天花板，不是达标线：包含率命中记的是整个片段区间，等命中落到实际共享区间
        // 那一天（docs/ROADMAP.md 的 1.1.1），这条应该一路掉到个位数。钉在这里只为了它别再加。
        check(hostFlagged <= 45 * phrases.size(),
                "多报天花板：平均每句连带标红不许超过 45 字，实测每句 "
                        + (hostFlagged + phrases.size() - 1) / phrases.size() + " 字");
    }

    private static String shortestSentence(String paragraph) {
        return pickSentence(paragraph, true);
    }

    private static String longestSentence(String paragraph) {
        return pickSentence(paragraph, false);
    }

    private static String pickSentence(String paragraph, boolean shortest) {
        ArrayList<String> list = sentencesOf(paragraph);
        String best = null;
        for (int i = 0; i < list.size(); i++) {
            String s = stripTail(list.get(i));
            int chars = valid(s);
            if (chars < TextCorpus.MIN_SENTENCE_CHARS) continue;
            if (best == null || (shortest ? chars < valid(stripTail(best)) : chars > valid(stripTail(best)))) best = list.get(i);
        }
        return best;
    }

    /** 一条改写口径的实测结果。 */
    static final class Score {
        final String name;
        int copies;
        int plantedChars;
        int flaggedChars;
        int attributedChars;
        int noiseChars;
        int changedChars;
        double recall;
        double attribution;

        Score(String name) { this.name = name; }
    }

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "tests/corpus/real-prose.txt";
        ArrayList<String> paragraphs = paragraphs(path);
        check(paragraphs.size() >= MIN_COPIES * 2,
                "语料段落不足，实测台没有统计意义：" + paragraphs.size());

        // 偶数段进文献库、也是被抄的段；奇数段只当稿子里学生自己的话，永远不进库。
        ArrayList<String> copies = new ArrayList<String>();
        ArrayList<String> fillers = new ArrayList<String>();
        for (int i = 0; i < paragraphs.size(); i++) {
            if (i % 2 == 0) copies.add(paragraphs.get(i));
            else fillers.add(paragraphs.get(i));
        }
        check(copies.size() >= MIN_COPIES, "被抄段数不足：" + copies.size());

        TextCorpus corpus = new TextCorpus();
        ArrayList<TextCorpus.Source> sources = new ArrayList<TextCorpus.Source>();
        int corpusChars = 0;
        for (int i = 0; i < copies.size(); i++) {
            TextCorpus.Source source = new TextCorpus.Source();
            source.id = "PAPER-" + i;
            source.title = "文献" + i;
            source.engine = "自建库";
            sources.add(source);
            corpus.add(source, copies.get(i));
            corpusChars += copies.get(i).length();
        }
        check(!corpus.isEmpty(), "文献库索引为空");

        Score[] scores = new Score[] {
            measure("verbatim", copies, fillers, sources, corpus, mutator("verbatim")),
            measure("app-rewriter", copies, fillers, sources, corpus, mutator("app-rewriter")),
            measure("synonym", copies, fillers, sources, corpus, mutator("synonym")),
            measure("clause-order", copies, fillers, sources, corpus, mutator("clause-order")),
            measure("sentence-order", copies, fillers, sources, corpus, mutator("sentence-order")),
            measure("number-style", copies, fillers, sources, corpus, mutator("number-style")),
            measure("drop-20pct", copies, fillers, sources, corpus, mutator("drop-20pct")),
            measure("stacked", copies, fillers, sources, corpus, mutator("stacked")),
            measure("pruned", copies, fillers, sources, corpus, mutator("pruned")),
            measure("split-commas", copies, fillers, sources, corpus, mutator("split-commas")),
            measure("merge-pairs", copies, fillers, sources, corpus, mutator("merge-pairs")),
            measure("sub-char-10", copies, fillers, sources, corpus, mutator("sub-char-10")),
            measure("sub-char-25", copies, fillers, sources, corpus, mutator("sub-char-25")),
            measure("sub-char-50", copies, fillers, sources, corpus, mutator("sub-char-50")),
            measure("local-edit-16", copies, fillers, sources, corpus, mutator("local-edit-16")),
            measure("local-edit-25", copies, fillers, sources, corpus, mutator("local-edit-25")),
            measure("spliced", copies, fillers, sources, corpus, mutator("spliced")),
        };
        printTable(scores);
        // 诊断模式（-Drrs / -Drrroc）不跑地板：这两台是拿来查明原因的，
        // 让一条地板断言半路把进程掐掉，就永远看不到后面的分布表了。
        if (SWEEP || ROC) {
            if (SWEEP) sweep(copies, fillers, sources, corpus);
            if (ROC) roc(copies, fillers, sources, corpus);
            return;
        }
        embedding(copies, fillers, sources, corpus);
        floors(scores, copies, fillers, sources, corpus);
        System.out.println("RewriteRobustnessRegression OK: " + count + " assertions"
                + " (copies=" + copies.size() + ", corpusChars=" + corpusChars
                + ", droppedCitationLines=" + droppedCitations + ")");
    }

    // ---- 量 ----

    private static Score measure(String name, ArrayList<String> copies, ArrayList<String> fillers,
                                 ArrayList<TextCorpus.Source> sources, TextCorpus corpus, Mutator mutator) {
        Score score = new Score(name);
        StringBuilder draft = new StringBuilder();
        ArrayList<int[]> planted = new ArrayList<int[]>();
        ArrayList<Integer> plantedSource = new ArrayList<Integer>();
        ArrayList<int[]> own = new ArrayList<int[]>();
        for (int i = 0; i < copies.size(); i++) {
            String original = copies.get(i);
            String mutated = mutator.run(original);
            String filler = fillers.get(i % fillers.size());
            int from = draft.length();
            draft.append(filler).append('\n');
            own.add(new int[] { from, draft.length() });
            from = draft.length();
            draft.append(mutated).append('\n');
            planted.add(new int[] { from, draft.length() });
            plantedSource.add(Integer.valueOf(i));
            score.changedChars += changedChars(original, mutated);
            score.copies++;
        }
        String text = draft.toString();
        String norm = TextCorpus.normalize(text);
        ArrayList<TextCorpus.Hit> hits = corpus.match(text, null).hits;
        int overlap = 0;
        for (int i = 0; i < hits.size(); i++)
            for (int j = i + 1; j < hits.size(); j++)
                if (hits.get(i).start < hits.get(j).end && hits.get(j).start < hits.get(i).end) overlap++;
        if (overlap != 0) throw new AssertionError(name + ": hits 重叠，直接相加会双算 " + overlap);
        for (int i = 0; i < planted.size(); i++) {
            int[] span = planted.get(i);
            TextCorpus.Source want = sources.get(plantedSource.get(i).intValue());
            score.plantedChars += TextCorpus.validCount(norm, span[0], span[1]);
            score.flaggedChars += cover(norm, span[0], span[1], hits, null);
            score.attributedChars += cover(norm, span[0], span[1], hits, want);
        }
        for (int i = 0; i < own.size(); i++) {
            int[] span = own.get(i);
            score.noiseChars += cover(norm, span[0], span[1], hits, null);
            if (DEBUG && "verbatim".equals(name)) debugNoise(text, norm, span, hits);
        }
        score.recall = score.plantedChars <= 0 ? 0d : score.flaggedChars * 100d / score.plantedChars;
        score.attribution = score.flaggedChars <= 0 ? 0d
                : score.attributedChars * 100d / score.flaggedChars;
        // 变换必须真的动过文字，否则这一行是在拿原文的召回冒充改写的召回。
        if (score.changedChars == 0 && !"verbatim".equals(name))
            throw new AssertionError(name + ": 变换没有改动任何字符，这一行是假的");
        return score;
    }

    /** [from,to) 里被命中区间盖住的有效字符；want 非空时只认记在那一篇名下的命中。 */
    private static int cover(String norm, int from, int to, ArrayList<TextCorpus.Hit> hits,
                             TextCorpus.Source want) {
        int total = 0;
        for (int i = 0; i < hits.size(); i++) {
            TextCorpus.Hit hit = hits.get(i);
            if (hit.end <= from || hit.start >= to) continue;
            if (want != null && hit.source != want) continue;
            total += TextCorpus.validCount(norm, Math.max(from, hit.start), Math.min(to, hit.end));
        }
        return total;
    }

    /** 位置不同的字符数。只用来看变换有没有动过文字，不是编辑距离。 */
    private static int changedChars(String a, String b) {
        int shared = Math.min(a.length(), b.length());
        int diff = Math.abs(a.length() - b.length());
        for (int i = 0; i < shared; i++) if (a.charAt(i) != b.charAt(i)) diff++;
        return diff;
    }

    private static void printTable(Score[] scores) {
        System.out.println("| 口径 | 段数 | 种入字数 | 标记字数 | 召回 | 归属 | 噪声 | 改动字数 |");
        System.out.println("| --- | --- | --- | --- | --- | --- | --- | --- |");
        for (int i = 0; i < scores.length; i++) {
            Score s = scores[i];
            System.out.println("| " + s.name + " | " + s.copies + " | " + s.plantedChars + " | "
                    + s.flaggedChars + " | " + percent(s.recall) + " | " + percent(s.attribution)
                    + " | " + s.noiseChars + " | " + s.changedChars + " |");
        }
    }

    private static String percent(double value) {
        return String.valueOf(Math.round(value * 10d) / 10d) + "%";
    }

    private static Score by(Score[] scores, String name) {
        for (int i = 0; i < scores.length; i++) if (scores[i].name.equals(name)) return scores[i];
        throw new AssertionError("缺一条口径：" + name);
    }

    // ---- 钉住的地板，实测之后按数字填，不许凭感觉写 ----

    private static void floors(Score[] scores, ArrayList<String> copies, ArrayList<String> fillers,
                              ArrayList<TextCorpus.Source> sources, TextCorpus corpus) {
        // 一、噪声。没进库的段落一个字都不许被标。召回可以谈，误报没有商量。
        for (int i = 0; i < scores.length; i++)
            check(scores[i].noiseChars == 0, "噪声为零：" + scores[i].name
                    + " 误标了没进库的段落 " + scores[i].noiseChars + " 字");
        // 二、这台实测台看得见噪声。同批负例实测最高 Dice 0.358，把阈值压到 0.30 必须撞出误标来；
        // 撞不出来说明上面那一串零是瞎出来的，整台机器就不作数了。
        int blind = measureAt("verbatim", 0.30f, SIMILAR_CONTAINMENT_AT_TEST, copies, fillers, sources, corpus).noiseChars;
        check(blind > 0, "阈值压到 0.30 时这台实测台必须能看见误标，看见 " + blind + " 字");
        // 三、每条口径的召回地板。数字来自实测，不是拍的；要往下改必须先重跑口径再改地板。
        recall(scores, "verbatim", 99d);
        recall(scores, "app-rewriter", 99d);
        recall(scores, "synonym", 99d);
        recall(scores, "clause-order", 99d);
        recall(scores, "sentence-order", 99d);
        recall(scores, "number-style", 99d);
        recall(scores, "drop-20pct", 99d);
        recall(scores, "stacked", 99d);
        recall(scores, "pruned", 97d);
        recall(scores, "split-commas", 99d);   // 0.55->0.50 之后 98.1，带子分块修复之后 99.4
        recall(scores, "merge-pairs", 99d);
        recall(scores, "sub-char-10", 99d);
        recall(scores, "sub-char-25", 26d);
        recall(scores, "local-edit-16", 99d);
        recall(scores, "local-edit-25", 99d);
        recall(scores, "spliced", 99d);
        // sub-char-50 只报数不钉地板：换掉一半汉字之后的文本已经不是语言，追它的召回没有意义。
        // 四、归属。标了却指到隔壁那篇，等于把出处写错。
        for (int i = 0; i < scores.length; i++)
            check(scores[i].attribution >= 98d, "归属不低于 98%：" + scores[i].name
                    + " 实测 " + percent(scores[i].attribution));
        // 五、0.50 这一档换来了什么。这条断言是这次改阈值的证据本身：
        // 0.55 与 0.50 在原文口径上逐位相同，只有换成逐字替换才分得开，差值必须看得见。
        Score at55 = measureAt("sub-char-25", 0.55f, SIMILAR_CONTAINMENT_AT_TEST, copies, fillers, sources, corpus);
        double gain = by(scores, "sub-char-25").recall - at55.recall;
        check(gain >= 6d, "0.50 相对 0.55 的抗改写召回增益要看得见，实测 " + percent(gain)
                + "；掉了说明阈值被改回去了或者口径变了");
        check(at55.noiseChars == 0, "0.55 那一档同样不许有噪声");
    }

    /** 产品值之外再测一遍：临时覆盖阈值、测完立刻还原。 */
    private static Score measureAt(String name, float dice, float containment, ArrayList<String> copies,
                                  ArrayList<String> fillers, ArrayList<TextCorpus.Source> sources,
                                  TextCorpus corpus) {
        TextCorpus.overrideThresholds(dice, containment);
        try {
            return measure(name, copies, fillers, sources, corpus, mutator(name));
        } finally {
            TextCorpus.restoreThresholds();
        }
    }

    private static final float SIMILAR_CONTAINMENT_AT_TEST = TextCorpus.SIMILAR_CONTAINMENT;

    private static void recall(Score[] scores, String name, double floor) {
        Score score = by(scores, name);
        check(score.recall >= floor, "召回地板 " + floor + "%：" + name + " 实测 " + percent(score.recall));
    }

    /** 阈值扫描：召回随阈值掉多少、噪声随阈值涨多少，两张表必须一起看。 */
    private static void sweep(ArrayList<String> copies, ArrayList<String> fillers,
                              ArrayList<TextCorpus.Source> sources, TextCorpus corpus) {
        System.out.println();
        System.out.println("| dice | containment | " + join(SWEEP_CASES) + " | 噪声 |");
        System.out.print("| --- | --- |");
        for (int i = 0; i < SWEEP_CASES.length; i++) System.out.print(" --- |");
        System.out.println(" --- |");
        for (int d = 0; d < SWEEP_DICE.length; d++) {
            for (int c = 0; c < SWEEP_CONTAINMENT.length; c++) {
                TextCorpus.overrideThresholds(SWEEP_DICE[d], SWEEP_CONTAINMENT[c]);
                StringBuilder row = new StringBuilder();
                row.append("| ").append(SWEEP_DICE[d]).append(" | ").append(SWEEP_CONTAINMENT[c]).append(" |");
                int noise = 0;
                for (int i = 0; i < SWEEP_CASES.length; i++) {
                    Score score = measureOnce(SWEEP_CASES[i], copies, fillers, sources, corpus);
                    row.append(" ").append(percent(score.recall)).append(" |");
                    noise += score.noiseChars;
                }
                System.out.println(row.append(" ").append(noise).append(" |"));
            }
        }
        TextCorpus.restoreThresholds();
    }

    private static String join(String[] names) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < names.length; i++) out.append(i == 0 ? "" : " | ").append(names[i]);
        return out.toString();
    }

    private static Score measureOnce(String name, ArrayList<String> copies, ArrayList<String> fillers,
                                     ArrayList<TextCorpus.Source> sources, TextCorpus corpus) {
        return measure(name, copies, fillers, sources, corpus, mutator(name));
    }

    /** 一条口径一个确定性变换。主表和阈值扫描共用这一份，两边不可能测的是两件事。 */
    private static Mutator mutator(final String name) {
        if ("verbatim".equals(name)) return new Mutator() {
            public String run(String text) { return text; }
        };
        if ("app-rewriter".equals(name)) return new Mutator() {
            public String run(String text) { return byRewriter(text); }
        };
        if ("synonym".equals(name)) return new Mutator() {
            public String run(String text) { return synonyms(text); }
        };
        if ("clause-order".equals(name)) return new Mutator() {
            public String run(String text) { return clauseReorder(text); }
        };
        if ("sentence-order".equals(name)) return new Mutator() {
            public String run(String text) { return sentenceReorder(text); }
        };
        if ("number-style".equals(name)) return new Mutator() {
            public String run(String text) { return numberStyle(text); }
        };
        if ("drop-20pct".equals(name)) return new Mutator() {
            public String run(String text) { return dropSentences(text); }
        };
        if ("stacked".equals(name)) return new Mutator() {
            public String run(String text) { return dropSentences(clauseReorder(byRewriter(text))); }
        };
        if ("pruned".equals(name)) return new Mutator() {
            public String run(String text) { return prunedClauses(text); }
        };
        if ("split-commas".equals(name)) return new Mutator() {
            public String run(String text) { return splitSentences(text); }
        };
        if ("merge-pairs".equals(name)) return new Mutator() {
            public String run(String text) { return mergeSentences(text); }
        };
        if ("sub-char-10".equals(name)) return new Mutator() {
            public String run(String text) { return substitute(text, 10); }
        };
        if ("sub-char-25".equals(name)) return new Mutator() {
            public String run(String text) { return substitute(text, 4); }
        };
        if ("sub-char-50".equals(name)) return new Mutator() {
            public String run(String text) { return substitute(text, 2); }
        };
        if ("local-edit-16".equals(name)) return new Mutator() {
            public String run(String text) { return localEdits(text, 25, 4); }
        };
        if ("local-edit-25".equals(name)) return new Mutator() {
            public String run(String text) { return localEdits(text, 12, 3); }
        };
        if ("spliced".equals(name)) return new Mutator() {
            public String run(String text) { return spliced(text); }
        };
        throw new AssertionError("unknown case " + name);
    }

    /**
     * 分值分布。把阈值压到近乎为零，让每个句子都把"库里最像的那一条"报出来，于是漏检的原因可以分开看：
     * 分值堆在 0.40~0.55 之间是阈值卡住的；分值正好是 0 的是压根没进候选（最短共用三元组、停用词倒排、
     * MAX_CANDIDATES 那一层挡掉的）。正例是改写后的种入段，负例是没进库的段落。
     */
    private static void roc(ArrayList<String> copies, ArrayList<String> fillers,
                            ArrayList<TextCorpus.Source> sources, TextCorpus corpus) {
        float[] cuts = { 0f, 0.001f, 0.3f, 0.4f, 0.45f, 0.5f, 0.55f, 0.7f, 1.01f };
        String[] labels = { "=0(\u6ca1\u8fdb\u5019\u9009)", "(0,0.3]", "(0.3,0.4]", "(0.4,0.45]",
                "(0.45,0.5]", "(0.5,0.55]", "(0.55,0.7]", ">0.7" };
        TextCorpus.overrideThresholds(0.0001f, 0.0001f);
        try {
            for (int c = 0; c < ROC_CASES.length; c++) {
                Mutator mutator = mutator(ROC_CASES[c]);
                int[] pos = new int[labels.length];
                int[] neg = new int[labels.length];
                int posChars = 0, posCharsRightSource = 0;
                double[] lenSum = new double[labels.length];
                int[] lenCount = new int[labels.length];
                for (int i = 0; i < copies.size(); i++) {
                    String mutated = mutator.run(copies.get(i));
                    ArrayList<String> sentences = sentencesOf(mutated);
                    for (int j = 0; j < sentences.size(); j++) {
                        String sentence = sentences.get(j);
                        int chars = TextCorpus.validCount(TextCorpus.normalize(sentence), 0, sentence.length());
                        if (chars < TextCorpus.MIN_SENTENCE_CHARS) continue;
                        TextCorpus.Hit best = bestHit(corpus, sentence);
                        int b = bucket(cuts, best == null ? 0f : best.score);
                        pos[b]++;
                        lenSum[b] += chars;
                        lenCount[b]++;
                        posChars += chars;
                        if (best != null && best.source == sources.get(i)) posCharsRightSource += chars;
                    }
                }
                for (int i = 0; i < fillers.size(); i++) {
                    ArrayList<String> sentences = sentencesOf(fillers.get(i));
                    for (int j = 0; j < sentences.size(); j++) {
                        String sentence = sentences.get(j);
                        int chars = TextCorpus.validCount(TextCorpus.normalize(sentence), 0, sentence.length());
                        if (chars < TextCorpus.MIN_SENTENCE_CHARS) continue;
                        TextCorpus.Hit best = bestHit(corpus, sentence);
                        neg[bucket(cuts, best == null ? 0f : best.score)]++;
                    }
                }
                printLengthTable(ROC_CASES[c], copies, fillers, sources, corpus, mutator(ROC_CASES[c]));
                System.out.println();
                System.out.println("ROC " + ROC_CASES[c] + " \u6b63\u4f8b\u53e5 " + sum(pos) + " \u6761 / \u8d1f\u4f8b\u53e5 "
                        + sum(neg) + " \u6761\uff1b\u6b63\u4f8b\u91cc\u6700\u4f73\u5019\u9009\u6307\u5bf9\u6b63\u786e\u51fa\u5904\u7684\u5b57\u7b26 "
                        + posCharsRightSource + "/" + posChars);
                System.out.println("| \u5206\u503c\u6bb5 | \u6b63\u4f8b\u53e5 | \u8d1f\u4f8b\u53e5 | \u5e73\u5747\u957f\u5ea6 |");
                for (int b = 0; b < labels.length; b++) {
                    System.out.println("| " + labels[b] + " | " + pos[b] + " | " + neg[b] + " | "
                            + (lenCount[b] == 0 ? "-" : String.valueOf((int) (lenSum[b] / lenCount[b]))) + " |");
                }
            }
        } finally {
            TextCorpus.restoreThresholds();
        }
    }

    private static int sum(int[] counts) {
        int total = 0;
        for (int i = 0; i < counts.length; i++) total += counts[i];
        return total;
    }

    private static int bucket(float[] cuts, float score) {
        for (int b = cuts.length - 2; b >= 1; b--) if (score >= cuts[b]) return b;
        return 0;
    }

    private static final int[] LEN_EDGES = { 20, 35, 50, 80 };

    /**
     * 按句长分桶看"今天漏掉的正例"和"负例能摸到的最高分"落在不在同一个长度上。
     * 长句的证据多（一枚 Dice=0.45 在 60 字句上是 26 枚共用三元组，在 14 字碎片上只有 6 枚），
     * 所以长度加权的安全余量本来就该不一样。这张表就是来看能不能这么分。
     */
    private static void printLengthTable(String name, ArrayList<String> copies, ArrayList<String> fillers,
                                         ArrayList<TextCorpus.Source> sources, TextCorpus corpus,
                                         Mutator mutator) {
        int buckets = LEN_EDGES.length + 1;
        int[][] posBands = new int[buckets][3];
        double[] negMax = new double[buckets];
        int[] negSentences = new int[buckets];
        for (int i = 0; i < copies.size(); i++) {
            ArrayList<String> sentences = sentencesOf(mutator.run(copies.get(i)));
            for (int j = 0; j < sentences.size(); j++) {
                int chars = valid(sentences.get(j));
                if (chars < TextCorpus.MIN_SENTENCE_CHARS) continue;
                TextCorpus.Hit best = bestHit(corpus, sentences.get(j));
                posBands[lengthBucket(chars)][band(best == null ? 0f : best.score)]++;
            }
        }
        for (int i = 0; i < fillers.size(); i++) {
            ArrayList<String> sentences = sentencesOf(fillers.get(i));
            for (int j = 0; j < sentences.size(); j++) {
                int chars = valid(sentences.get(j));
                if (chars < TextCorpus.MIN_SENTENCE_CHARS) continue;
                TextCorpus.Hit best = bestHit(corpus, sentences.get(j));
                int b = lengthBucket(chars);
                negSentences[b]++;
                negMax[b] = Math.max(negMax[b], best == null ? 0f : best.score);
            }
        }
        System.out.println();
        System.out.println("\u957f\u5ea6\u5206\u6876 " + name
                + "\uff08\u6f0f\u68c0 = \u5206\u503c\u4f4e\u4e8e\u73b0\u5728\u7684 0.55\uff0c\u53ef\u637e = \u843d\u5728 0.45~0.55\uff09");
        System.out.println("| \u53e5\u957f | \u6b63\u4f8b\u6f0f\u68c0 | \u5176\u4e2d\u53ef\u637e | \u6b63\u4f8b\u5df2\u6807 | \u8d1f\u4f8b\u53e5 | \u8d1f\u4f8b\u6700\u9ad8\u5206 |");
        for (int b = 0; b < buckets; b++) {
            String label = b == 0 ? "<20" : (b == LEN_EDGES.length ? ">=" + LEN_EDGES[b - 1]
                    : LEN_EDGES[b - 1] + "-" + LEN_EDGES[b]);
            System.out.println("| " + label + " | " + (posBands[b][0] + posBands[b][1]) + " | " + posBands[b][1]
                    + " | " + posBands[b][2] + " | " + negSentences[b] + " | "
                    + String.valueOf(Math.round(negMax[b] * 1000d) / 1000d) + " |");
        }
    }

    private static int valid(String text) {
        String norm = TextCorpus.normalize(text);
        return TextCorpus.validCount(norm, 0, text.length());
    }

    /** 0 = \u4e25\u91cd\u4e0d\u8db3 0.45\uff0c1 = 0.45~0.55\uff08\u677e\u4e00\u683c\u5c31\u80fd\u637e\u56de\uff09\uff0c2 = \u73b0\u5728\u5df2\u7ecf\u4f1a\u6807\u3002 */
    private static int band(float score) {
        return score >= 0.55f ? 2 : (score >= 0.45f ? 1 : 0);
    }

    private static int lengthBucket(int chars) {
        for (int i = 0; i < LEN_EDGES.length; i++) if (chars < LEN_EDGES[i]) return i;
        return LEN_EDGES.length;
    }

    /** \u8fd9\u53e5\u8bdd\u5728\u5e93\u91cc\u5206\u503c\u6700\u9ad8\u7684\u5019\u9009\uff08\u9608\u503c\u5df2\u88ab\u538b\u5230\u8fd1\u96f6\uff09\uff1bnull \u8868\u793a\u8fde\u5019\u9009\u90fd\u6ca1\u95ee\u5230\u3002 */
    private static TextCorpus.Hit bestHit(TextCorpus corpus, String sentence) {
        ArrayList<TextCorpus.Hit> hits = corpus.match(sentence, null).hits;
        TextCorpus.Hit best = null;
        for (int i = 0; i < hits.size(); i++) {
            if (best == null || hits.get(i).score > best.score) best = hits.get(i);
        }
        return best;
    }

    // ---- 八种确定性变换 ----

    /** 逐轮采纳第一条建议，等价于把离线降重建议全部点采纳。 */
    private static String byRewriter(String text) {
        String out = text;
        List<String> terms = Collections.emptyList();
        for (int round = 0; round < REWRITE_ROUNDS; round++) {
            ArrayList<LocalRewriter.Option> options = LocalRewriter.rewrite(out, terms);
            if (options.isEmpty()) break;
            String next = options.get(0).text;
            if (next == null || next.equals(out)) break;
            out = next;
        }
        return out;
    }

    /** 同义词与连接词替换。任何一条的目标词都不做另一条的源词，避免换过去又被下一条换回来。 */
    private static final String[][] SYNONYMS = {
        { "例如", "比如" }, { "因此", "所以" }, { "然而", "可是" }, { "通常", "一般" },
        { "显著", "明显" }, { "导致", "造成" }, { "提高", "提升" }, { "降低", "减小" },
        { "采用", "使用" }, { "通过", "借助" }, { "以及", "和" }, { "具有", "拥有" },
        { "表明", "显示" }, { "由于", "因为" }, { "能够", "可以" }, { "目前", "当前" },
        { "并且", "而且" }, { "获得", "得到" }, { "广泛", "普遍" }, { "逐渐", "逐步" },
        { "迅速", "快速" }, { "方面", "层面" }, { "主要", "核心" }, { "同时", "此外" },
    };

    private static String synonyms(String text) {
        String out = text;
        for (int i = 0; i < SYNONYMS.length; i++) out = out.replace(SYNONYMS[i][0], SYNONYMS[i][1]);
        return out;
    }

    private static final Pattern CLAUSE = Pattern.compile("[，；、：]");

    /** 句内分句倒序，分句间分隔符统一成逗号；不足三个分句的句子不动。 */
    private static String clauseReorder(String text) {
        ArrayList<int[]> spans = TextCorpus.sentences(text);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < spans.size(); i++) {
            int[] span = spans.get(i);
            out.append(reorderClauses(text.substring(span[0], span[1])));
        }
        return out.toString();
    }

    private static String reorderClauses(String sentence) {
        StringBuilder terminator = new StringBuilder();
        String body = sentence;
        while (body.length() > 0 && isTerminator(body.charAt(body.length() - 1))) {
            terminator.insert(0, body.charAt(body.length() - 1));
            body = body.substring(0, body.length() - 1);
        }
        String[] parts = CLAUSE.split(body);
        if (parts.length < 3) return sentence;
        StringBuilder out = new StringBuilder();
        for (int i = parts.length - 1; i >= 0; i--) {
            if (parts[i].length() == 0) continue;
            if (out.length() > 0) out.append('，');
            out.append(parts[i]);
        }
        return out.append(terminator).toString();
    }

    private static boolean isTerminator(char c) {
        return c == '。' || c == '！' || c == '？' || c == '；' || c == '”' || c == '』' || c == '」';
    }

    /** 段内句序倒序。 */
    private static String sentenceReorder(String text) {
        ArrayList<int[]> spans = TextCorpus.sentences(text);
        ArrayList<String> sentences = new ArrayList<String>();
        for (int i = 0; i < spans.size(); i++) {
            int[] span = spans.get(i);
            String s = text.substring(span[0], span[1]);
            if (s.trim().length() > 0) sentences.add(s);
        }
        Collections.reverse(sentences);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < sentences.size(); i++) out.append(sentences.get(i));
        return out.toString();
    }

    private static final Pattern NUMBER = Pattern.compile("[0-9]+(?:\\.[0-9]+)?%?");
    private static final String[] CN_DIGIT = { "零", "一", "二", "三", "四", "五", "六", "七", "八", "九" };

    /** 数目字写法互换：12.5% 写成百分之十二点五。数字在指纹里折成同一枚 token，这条本该不掉召回。 */
    private static String numberStyle(String text) {
        Matcher matcher = NUMBER.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group();
            boolean percent = token.endsWith("%");
            String digits = percent ? token.substring(0, token.length() - 1) : token;
            int dot = digits.indexOf('.');
            String integer = dot < 0 ? digits : digits.substring(0, dot);
            StringBuilder cn = new StringBuilder();
            String word = integer.length() <= 4 ? chineseNumber(Integer.parseInt(integer)) : null;
            cn.append(word != null ? word : digitsOf(integer));
            if (dot >= 0) {
                cn.append('点');
                cn.append(digitsOf(digits.substring(dot + 1)));
            }
            String replacement = percent ? "百分之" + cn : cn.toString();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String digitsOf(String digits) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) out.append(CN_DIGIT[digits.charAt(i) - '0']);
        return out.toString();
    }

    /** 0-9999 的中文写法，十到十九写作"十二"而不是"一十二"。 */
    private static String chineseNumber(int value) {
        if (value < 0 || value > 9999) return null;
        String[] unit = { "", "十", "百", "千" };
        if (value == 0) return CN_DIGIT[0];
        if (value < 20) return "十" + (value % 10 == 0 ? "" : CN_DIGIT[value % 10]);
        StringBuilder out = new StringBuilder();
        boolean zeroPending = false;
        boolean started = false;
        for (int p = 3; p >= 0; p--) {
            int scale = 1;
            for (int k = 0; k < p; k++) scale *= 10;
            int d = (value / scale) % 10;
            if (d == 0) {
                if (started) zeroPending = true;
                continue;
            }
            if (zeroPending) out.append('零');
            zeroPending = false;
            started = true;
            out.append(CN_DIGIT[d]).append(unit[p]);
        }
        return out.toString();
    }

    /** 每五句丢一句（丢下标 3 那一档），实测删掉两成之后的召回。 */
    private static String dropSentences(String text) {
        ArrayList<int[]> spans = TextCorpus.sentences(text);
        StringBuilder out = new StringBuilder();
        int kept = 0;
        for (int i = 0; i < spans.size(); i++) {
            if (i % 5 == 3) continue;
            int[] span = spans.get(i);
            out.append(text, span[0], span[1]);
            kept++;
        }
        return kept == 0 ? text : out.toString();
    }

    /** 逐条打印被误标的段落片段与它撞上的出处，用来判断噪声到底是哪一种。 */
    private static void debugNoise(String text, String norm, int[] span, ArrayList<TextCorpus.Hit> hits) {
        for (int i = 0; i < hits.size(); i++) {
            TextCorpus.Hit hit = hits.get(i);
            if (hit.end <= span[0] || hit.start >= span[1]) continue;
            int from = Math.max(span[0], hit.start);
            int to = Math.min(span[1], hit.end);
            System.out.println("NOISE chars=" + TextCorpus.validCount(norm, from, to)
                    + " score=" + hit.score + " source=" + (hit.source == null ? "?" : hit.source.id)
                    + " :: " + text.substring(from, Math.min(to, from + 46)).replace('\n', ' '));
        }
    }

    /** 拆句：句中每个逗号都改成句号。降重手册上最便宜的一招，专门打句子级比对。 */
    private static String splitSentences(String text) {
        return text.replace('，', '。').replace('、', '。');
    }

    /** 并句：相邻两句合成一句，句号改成逗号。反向打同一把尺子。 */
    private static String mergeSentences(String text) {
        ArrayList<String> sentences = sentencesOf(text);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < sentences.size(); i++) {
            String sentence = stripTail(sentences.get(i));
            out.append(i + 1 < sentences.size() ? sentence + '，' : sentence + '。');
        }
        return out.toString();
    }

    private static final char[] SUBSTITUTE_POOL =
            "机制性能结构参数工艺温度界面组织成分应力应变载荷条件均匀细化".toCharArray();

    /** 每 stride 个汉字换一个：模拟大范围的词汇替换。这是逐字替换，比真实同义词替换更狠，用作容忍度曲线。 */
    private static String substitute(String text, int stride) {
        char[] cs = text.toCharArray();
        int cjk = 0;
        for (int i = 0; i < cs.length; i++) {
            if (cs[i] < 0x4E00 || cs[i] > 0x9FFF) continue;
            cjk++;
            if (cjk % stride != 0) continue;
            cs[i] = SUBSTITUTE_POOL[(cjk / stride + i) % SUBSTITUTE_POOL.length];
        }
        return new String(cs);
    }

    /**
     * 局部改动：每 step 个汉字就整块换掉 runLen 个连续汉字。改动率和 sub-char 相当，但空间分布完全不同——
     * 大段原文整块活着，只留下几处 3~6 字的疤。这才是 LLM 改写和大段誊抄笔误的真实形状，
     * 也是逐字替换那种"均匀铺开"的口径测不到的一档。
     */
    private static String localEdits(String text, int step, int runLen) {
        char[] cs = text.toCharArray();
        int cjk = 0;
        int i = 0;
        while (i < cs.length) {
            if (cs[i] < 0x4E00 || cs[i] > 0x9FFF) {
                i++;
                continue;
            }
            cjk++;
            if (cjk % step != 0) {
                i++;
                continue;
            }
            int replaced = 0;
            int j = i;
            while (j < cs.length && replaced < runLen) {
                if (cs[j] >= 0x4E00 && cs[j] <= 0x9FFF) {
                    cs[j] = SUBSTITUTE_POOL[(cjk + replaced + j) % SUBSTITUTE_POOL.length];
                    replaced++;
                }
                j++;
            }
            i = j;
        }
        return new String(cs);
    }

    /** 只留偶数位的分句：抄进来的一半被抽稀成互不相邻的碎块，十九字的指纹带必然断掉。 */
    private static String prunedClauses(String text) {
        ArrayList<String> sentences = sentencesOf(text);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < sentences.size(); i++) {
            String sentence = sentences.get(i);
            String[] parts = CLAUSE.split(stripTail(sentence));
            if (parts.length < 3) {
                out.append(sentence);
                continue;
            }
            StringBuilder kept = new StringBuilder();
            for (int j = 0; j < parts.length; j += 2) {
                if (parts[j].length() == 0) continue;
                if (kept.length() > 0) kept.append('，');
                kept.append(parts[j]);
            }
            out.append(kept).append(tailOf(sentence));
        }
        return out.toString();
    }

    /** 上句的头接下句的尾：跨句拼接，经典降重写法，句子级比对在这里最容易被绕开。 */
    private static String spliced(String text) {
        ArrayList<String> sentences = sentencesOf(text);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i + 1 < sentences.size(); i += 2) {
            String head = firstHalf(sentences.get(i));
            String tailPart = secondHalf(sentences.get(i + 1));
            out.append(head).append('，').append(tailPart).append('。');
        }
        if (sentences.size() % 2 != 0) out.append(sentences.get(sentences.size() - 1));
        return out.toString();
    }

    private static ArrayList<String> sentencesOf(String text) {
        ArrayList<int[]> spans = TextCorpus.sentences(text);
        ArrayList<String> out = new ArrayList<String>();
        for (int i = 0; i < spans.size(); i++) {
            int[] span = spans.get(i);
            String s = text.substring(span[0], span[1]);
            if (s.trim().length() > 0) out.add(s);
        }
        return out;
    }

    private static String stripTail(String sentence) {
        int end = sentence.length();
        while (end > 0 && isTerminator(sentence.charAt(end - 1))) end--;
        return sentence.substring(0, end);
    }

    private static String tailOf(String sentence) {
        int end = sentence.length();
        while (end > 0 && isTerminator(sentence.charAt(end - 1))) end--;
        return sentence.substring(end);
    }

    /** 句子按分句切成两半的前半。 */
    private static String firstHalf(String sentence) {
        String body = stripTail(sentence);
        String[] parts = CLAUSE.split(body);
        if (parts.length < 3) return body;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i <= parts.length / 2 && i < parts.length; i++) {
            if (parts[i].length() == 0) continue;
            if (out.length() > 0) out.append('，');
            out.append(parts[i]);
        }
        return out.toString();
    }

    /** 句子按分句切成两半的后半。 */
    private static String secondHalf(String sentence) {
        String body = stripTail(sentence);
        String[] parts = CLAUSE.split(body);
        if (parts.length < 3) return body;
        StringBuilder out = new StringBuilder();
        for (int i = parts.length / 2 + 1; i < parts.length; i++) {
            if (parts[i].length() == 0) continue;
            if (out.length() > 0) out.append('，');
            out.append(parts[i]);
        }
        return out.length() == 0 ? body : out.toString();
    }

    // ---- 语料 ----

    private static ArrayList<String> paragraphs(String path) throws Exception {
        String all = new String(Files.readAllBytes(Paths.get(path)), UTF8);
        ArrayList<String> out = new ArrayList<String>();
        for (String line : all.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.length() < MIN_PARAGRAPH_CHARS) continue;
            // 用本应用自己的引文判据把参考文献条剔掉：参考文献之间互相判重是另一件事，
            // 那一档由 bibliography() 单独测，混在这里只会把正文的噪声数字弄脏。
            if (TextCorpus.citationLike(trimmed)) { droppedCitations++; continue; }
            out.add(trimmed);
        }
        return out;
    }
}