package com.rikkahub.wordlite;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 袋口径那条"取候选"路的探针（不入库、不联网，跑法见 tools/bag-rescue-probe.ps1）。三件事：
 *
 * 一、机制。把一条句子倒过来写：字符袋一个字不差（袋 Dice 1.0），字面三元组一枚不剩（Dice 0.0）。
 * 候选只来自三元组倒排的年代，这种句子连被袋口径看一眼的机会都没有；现在由字符袋倒排补进候选。
 * 倒着抄不是真实形状，它只是"结构全换、字还是那些字"最省事的造法——真实大模型改写是它的温和版。
 *
 * 二、开销。真人语料一篇当稿子、另一批段落当文库，报袋口径多精算了几个候选、整篇 match 多久。
 * 计时只看量级：同一个 JVM 里先跑的那一轮要付 JIT 预热，两轮之差量不出是开销还是预热。
 *
 * 三、同一篇论文自己对自己。稿子与文库来自同一篇（单双行劈开），这是袋口径最容易虚报的形状：
 * 同一篇文章的术语本来就反复出现。这一段只报数与样例，不当判据——它回答的是"新增的那些字数像不像抄的"。
 */
public final class BagRescueProbe {

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "tests/corpus/real-prose.txt";
        String[] halves = load(path);
        mechanism();
        cost(halves[0], halves[1]);
        sameDoc(halves[0], halves[1]);
    }

    /** 单数行进文库、双数行进稿子（同一篇论文的正文行，劈开两边都不含对方的原句）。 */
    private static String[] load(String path) throws Exception {
        List<String> lines = Files.readAllLines(Paths.get(path), Charset.forName("UTF-8"));
        StringBuilder library = new StringBuilder();
        StringBuilder draft = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.length() < 40) continue;
            if (i % 2 == 0) library.append(line).append('\n');
            else draft.append(line).append('\n');
        }
        return new String[] { draft.toString(), library.toString() };
    }

    private static TextCorpus.Source source(String id) {
        TextCorpus.Source source = new TextCorpus.Source();
        source.id = id;
        source.title = "探针来源";
        source.engine = "local";
        return source;
    }

    private static void mechanism() {
        String sentence = "城市道路交通拥堵的治理需要把停车管理、公共交通优先和路网信号配时这三项措施放在一起来考虑。";
        String reversed = new StringBuilder(sentence).reverse().toString();
        System.out.println("MECHANISM 倒过来写：三元组 Dice=" + TextCorpus.dice(sentence, reversed)
                + " 袋 Dice=" + TextCorpus.bagDice(sentence, reversed));
        TextCorpus on = new TextCorpus();
        on.add(source("probe"), sentence);
        TextCorpus.Report hits = on.match("前言部分占位文字若干字，用来把偏移推开一点。" + reversed, null);
        TextCorpus off = new TextCorpus();
        off.add(source("probe"), sentence);
        TextCorpus.Report none;
        TextCorpus.overrideBagFloor(1.01f);
        try {
            none = off.match("前言部分占位文字若干字，用来把偏移推开一点。" + reversed, null);
        } finally {
            TextCorpus.restoreBagFloor();
        }
        String score = hits.hits.isEmpty() ? "-" : String.valueOf(hits.hits.get(0).score);
        System.out.println("  开着袋口径：命中 " + hits.hits.size() + " 条，精算 " + on.bagProbes()
                + " 个候选，分值 " + score);
        System.out.println("  关掉袋口径：命中 " + none.hits.size() + " 条，精算 " + off.bagProbes()
                + " 个候选");
        if (hits.hits.isEmpty() || !none.hits.isEmpty() || on.bagProbes() == 0 || off.bagProbes() != 0) {
            throw new AssertionError("袋口径取候选这条路的机制不成立了：见上面两行");
        }
    }

    private static void cost(String draft, String library) {
        System.out.println("COST 稿子 " + draft.length() + " 字");
        for (int round = 0; round < 2; round++) {
            boolean off = round == 0;
            TextCorpus corpus = new TextCorpus();
            corpus.add(source("probe-cost"), library);
            if (off) TextCorpus.overrideBagFloor(1.01f);
            long begin = System.nanoTime();
            TextCorpus.Report report = corpus.match(draft, null);
            long millis = (System.nanoTime() - begin) / 1000000L;
            if (off) TextCorpus.restoreBagFloor();
            System.out.println("  " + (off ? "关掉袋口径 " : "开着袋口径 ") + millis + "ms，命中 "
                    + report.hits.size() + " 条，重复字符 " + report.duplicateChars + "，精算 "
                    + corpus.bagProbes() + " 个候选，文库 " + corpus.sentenceCount() + " 句");
        }
        System.out.println("  上限：每片段 4 枚种子、单枚 posting 256 条、每片段最多精算 64 个候选；"
                + "df 超过 64 的字不进索引");
    }

    /**
     * 同一篇论文自己对自己：袋口径新增了多少字、这些新增的段落长什么样。两遍——一遍裸跑（什么都不排除，
     * 最容易虚报的形状），一遍走产品口径（TextCorpus.structure 把参考文献表与标题那几段排除掉，
     * 与 DuplicateEngine.check 传的第三个参数逐字相同）。两遍都报，因为 1.1.6 第一版就是只看裸跑那一遍
     * 才发现袋口径把整张参考文献表报成了重复。
     */
    private static void sameDoc(String draft, String library) {
        TextCorpus.Report base;
        TextCorpus.overrideBagFloor(1.01f);
        try {
            TextCorpus off = new TextCorpus();
            off.add(source("probe-same"), library);
            base = off.match(draft, null);
        } finally {
            TextCorpus.restoreBagFloor();
        }
        TextCorpus on = new TextCorpus();
        on.add(source("probe-same"), library);
        TextCorpus.Report now = on.match(draft, null);
        int extra = 0;
        int spans = 0;
        List<String> samples = new ArrayList<String>();
        for (TextCorpus.Hit hit : now.hits) {
            int uncovered = hit.end - hit.start - overlap(hit, base.hits);
            if (uncovered <= 0) continue;
            spans++;
            extra += uncovered;
            if (samples.size() < 5) {
                int stop = Math.min(hit.end, hit.start + 40);
                samples.add("  +" + uncovered + " 字 score=" + String.format("%.2f", hit.score) + "："
                        + draft.substring(hit.start, stop).replace('\n', ' '));
            }
        }
        System.out.println("SAME-DOC 同一篇劈成两边：关掉袋口径 " + base.hits.size() + " 条 / "
                + base.duplicateChars + " 字，开着 " + now.hits.size() + " 条 / " + now.duplicateChars
                + " 字；袋口径净新增 " + extra + " 字，落在 " + spans + " 段，精算 "
                + on.bagProbes() + " 个候选");
        for (String sample : samples) System.out.println(sample);

        int[] excluded = TextCorpus.structure(draft).spanArray();
        TextCorpus.Report productBase;
        TextCorpus.overrideBagFloor(1.01f);
        try {
            TextCorpus off = new TextCorpus();
            off.add(source("probe-same"), library);
            productBase = off.match(draft, null, excluded);
        } finally {
            TextCorpus.restoreBagFloor();
        }
        TextCorpus product = new TextCorpus();
        product.add(source("probe-same"), library);
        TextCorpus.Report productNow = product.match(draft, null, excluded);
        int productExtra = 0;
        for (int i = 0; i < productNow.hits.size(); i++) {
            TextCorpus.Hit hit = productNow.hits.get(i);
            productExtra += Math.max(0, hit.end - hit.start - overlap(hit, productBase.hits));
        }
        System.out.println("  产品口径（排除参考文献表与结构性文本 " + TextCorpus.structure(draft).excludedChars
                + " 字）：关掉袋口径 " + productBase.hits.size() + " 条 / " + productBase.duplicateChars
                + " 字，开着 " + productNow.hits.size() + " 条 / " + productNow.duplicateChars
                + " 字；袋口径净新增 " + productExtra + " 字");
    }

    private static int overlap(TextCorpus.Hit hit, List<TextCorpus.Hit> others) {
        int sum = 0;
        for (TextCorpus.Hit other : others) {
            int lo = Math.max(hit.start, other.start);
            int hi = Math.min(hit.end, other.end);
            if (hi > lo) sum += hi - lo;
        }
        return sum;
    }
}