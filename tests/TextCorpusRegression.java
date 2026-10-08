package com.rikkahub.wordlite;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Random;

/** TextCorpus / LocalLibrary 回归：归一化等长、句子偏移、改写命中、区间合并、指标与自建库。 */
public final class TextCorpusRegression {
    private static int count;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String PUNCT_SAMPLE = "，。！？、；：“”‘’（）《》…—～";

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        count++;
        System.out.println("PASS " + message);
    }

    /**
     * 相交的命中对数。0.7.3 之后 match() 把命中区间取了并集（重叠段划给先占的那一篇），这个数必须恒为 0：
     * 它是"一个字符只算一次"最直接的结构性证据，比任何总数相等都难蒙混。
     */
    private static int overlapPairs(ArrayList<TextCorpus.Hit> hits) {
        int pairs = 0;
        for (int i = 0; i < hits.size(); i++)
            for (int j = i + 1; j < hits.size(); j++)
                if (hits.get(i).start < hits.get(j).end && hits.get(j).start < hits.get(i).end) pairs++;
        return pairs;
    }

    public static void main(String[] args) throws Exception {
        normalization();
        splitting();
        similarity();
        bagChannel();
        shortFragments();
        matching();
        fingerprints();
        merging();
        rates();
        citations();
        structure();
        edgeCases();
        performance();
        library();
        System.out.println("SUMMARY " + count + " assertions passed"
                + " (phone-side highlighting/JSON UI not exercised here).");
    }

    private static void normalization() {
        String text = "Ｐａｐｅｒ　ＡＢＣ，１２３！实验体說裏臺…「引用」『书名』—–～＃＄％";
        String folded = TextCorpus.normalize(text);
        check(folded.length() == text.length(), "normalize keeps UTF-16 length so offsets survive");
        check(folded.charAt(0) == 'p' && folded.charAt(4) == 'r' && folded.charAt(5) == ' ',
                "normalize folds fullwidth letters and the fullwidth space at the very same indices");
        check(folded.indexOf("paper abc,123!") >= 0, "normalize folds fullwidth punctuation and width in place");
        check(folded.indexOf('体') >= 0 && folded.indexOf('體') < 0, "normalize folds common traditional characters");
        check(folded.indexOf('说') >= 0 && folded.indexOf('說') < 0, "normalize folds 說 to 说");
        check(folded.indexOf('里') >= 0 && folded.indexOf('裏') < 0, "normalize folds 裏 to 里");
        check(folded.indexOf('「') < 0 && folded.indexOf('『') < 0, "normalize folds CJK brackets to ascii brackets");
        check(folded.indexOf('—') < 0 && folded.indexOf('–') < 0, "normalize folds dashes to hyphen");
        check(TextCorpus.normalize(folded).equals(folded), "normalize is idempotent");
        check(TextCorpus.normalize("").length() == 0 && TextCorpus.normalize(null).length() == 0,
                "normalize handles empty and null without throwing");
        String emoji = "前\uD83D\uDE00后\uD83D\uDE42中\u200d复合";
        check(TextCorpus.normalize(emoji).length() == emoji.length(), "normalize keeps surrogate pairs and variation selectors same-length");
        check(TextCorpus.normalize(emoji).equals(emoji), "normalize leaves surrogate-only text untouched");
        Random random = new Random(20261007L);
        boolean stable = true;
        boolean asciiOnly = true;
        for (int round = 0; round < 400; round++) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 120; i++) {
                int pick = random.nextInt(6);
                if (pick == 0) builder.append((char) ('Ａ' + random.nextInt(26)));
                else if (pick == 1) builder.append((char) (0x4E00 + random.nextInt(2000)));
                else if (pick == 2) builder.append(PUNCT_SAMPLE.charAt(random.nextInt(PUNCT_SAMPLE.length())));
                else if (pick == 3) builder.append(" \t　".charAt(random.nextInt(3)));
                else if (pick == 4) builder.appendCodePoint(0x1F300 + random.nextInt(400));
                else builder.append((char) (0x200B + random.nextInt(4)));
            }
            String probe = builder.toString();
            if (TextCorpus.normalize(probe).length() != probe.length()) stable = false;
            String compact = TextCorpus.compactOf(probe);
            for (int i = 0; i < compact.length(); i++) {
                char c = compact.charAt(i);
                if (c == ' ' || c == '　') asciiOnly = false;
            }
            if (compact.indexOf(0x200B) >= 0 || compact.indexOf(0xFEFF) >= 0 || compact.indexOf(0x00AD) >= 0) asciiOnly = false;
        }
        check(stable, "400 randomized strings: normalize never changes length");
        check(asciiOnly, "compact form drops blanks and zero-width characters");
        check(TextCorpus.translationTableAligned(), "traditional/simplified table is aligned and non-trivial");
        check(TextCorpus.validCount("实验　结果 ", 0, 5) == 4, "validCount skips fullwidth space");
    }

    private static void splitting() {
        String text = "第一句在这里。\n第二句带 emoji 😀 结尾！第三句（全角）结束？第四句；第五句结束。\n\n最后一行没有标点";
        ArrayList<int[]> spans = TextCorpus.sentences(text);
        String[] expected = {"第一句在这里。", "第二句带 emoji 😀 结尾！", "第三句（全角）结束？", "第四句；",
                "第五句结束。", "最后一行没有标点"};
        check(spans.size() == expected.length, "sentence split finds " + expected.length + " spans, got " + spans.size());
        boolean exact = spans.size() == expected.length;
        for (int i = 0; exact && i < expected.length; i++) {
            int[] span = spans.get(i);
            if (!text.substring(span[0], span[1]).equals(expected[i])) exact = false;
        }
        check(exact, "every sentence span substring equals the expected sentence (newlines, fullwidth marks, surrogate pair)");
        String quoted = "他说：“今天的数据跑完了。”第二天继续。\n";
        ArrayList<int[]> quoteSpans = TextCorpus.sentences(quoted);
        check(quoteSpans.size() == 2 && quoted.substring(quoteSpans.get(0)[0], quoteSpans.get(0)[1]).equals("他说：“今天的数据跑完了。”"),
                "closing quotes stay attached to the sentence they end");
        String number = "样本温度控制在 3.5 摄氏度左右，误差约 0.25。";
        ArrayList<int[]> numberSpans = TextCorpus.sentences(number);
        check(numberSpans.size() == 1, "decimal points inside numbers do not split sentences");
        String dots = "结果还需要再看……。";
        check(TextCorpus.sentences(dots).size() == 1, "ellipses collapse into one sentence boundary");
        String english = "The model converged. Then we measured latency.";
        ArrayList<int[]> englishSpans = TextCorpus.sentences(english);
        check(englishSpans.size() == 2 && english.substring(englishSpans.get(1)[0], englishSpans.get(1)[1]).equals("Then we measured latency."),
                "english sentences split on period plus space");
        boolean ordered = true;
        String mixed = "第一段。\n\n第二段！第三段？\r\n第四段;";
        ArrayList<int[]> mixedSpans = TextCorpus.sentences(mixed);
        int previousEnd = -1;
        for (int i = 0; i < mixedSpans.size(); i++) {
            int[] span = mixedSpans.get(i);
            if (span[0] < previousEnd || span[1] <= span[0] || span[1] > mixed.length()) ordered = false;
            previousEnd = span[1];
        }
        check(ordered && mixedSpans.size() == 4, "spans are ordered, in range and non-overlapping on mixed newline input");
        check(TextCorpus.sentences("").isEmpty() && TextCorpus.sentences(null).isEmpty()
                && TextCorpus.sentences("\n\n  \t ").isEmpty(), "splitting empty or whitespace text returns no spans");
    }

    private static TextCorpus.Source source(String id, String title, String engine) {
        TextCorpus.Source source = new TextCorpus.Source();
        source.id = id;
        source.title = title;
        source.engine = engine;
        source.year = "2021";
        source.authors = "张三";
        return source;
    }

    private static void similarity() {
        String base = "我们在实验中采用了改进的卷积神经网络结构，有效提升了图像分类的准确率。";
        String swap3 = "我们在实验中采用了改进的卷积神经网络架构，显著提升了图像分类的准确度。";
        String swap4 = "我们在实验里使用了改进的卷积神经网络架构，显著提升了图像分类的准确度。";
        String unrelated = "昨天的晚餐是红烧肉和清炒时蔬，饭后我们沿着河边的步道走了很久。";
        String sameFrame = "图 7 展示了不同浓度下样品的电导率变化曲线与拟合优度。";
        check(Math.abs(TextCorpus.dice(base, base) - 1f) < 1e-6, "dice of identical text is 1.0");
        check(TextCorpus.dice(base, swap3) >= TextCorpus.SIMILAR_DICE, "dice keeps a three-word rewrite above the threshold");
        check(TextCorpus.dice(base, swap4) >= TextCorpus.SIMILAR_DICE, "dice keeps a four-word rewrite above the threshold");
        check(TextCorpus.dice(base, unrelated) < 0.15f, "dice of unrelated sentences stays far below the threshold");
        check(TextCorpus.dice(base, sameFrame) < TextCorpus.SIMILAR_DICE,
                "same sentence frame with different facts stays below the threshold");
        check(Math.abs(TextCorpus.dice("Ｐａｐｅｒ　ＡＢＣ 123", "paper abc 123") - 1f) < 1e-6,
                "dice ignores width, case and spacing differences");
        check(TextCorpus.dice("體說裏臺", "体说里台") > 0.9f, "dice folds traditional and simplified before comparing");
        check(TextCorpus.dice("", base) == 0f && TextCorpus.dice(null, null) == 0f && TextCorpus.dice("。。。", "！！！") >= 0f,
                "dice tolerates empty, null and punctuation-only inputs");
        String embedded = "此外还需要指出的是，" + base + "这一点在后续工作中还会继续验证。";
        check(TextCorpus.dice(base, embedded) >= TextCorpus.SIMILAR_DICE, "an intact sentence inside a longer rewrite still reads as similar");
    }

    private static void matching() {
        TextCorpus corpus = new TextCorpus();
        TextCorpus.Source paper = source("p1", "指纹匹配研究", "openalex");
        corpus.add(paper, "深度学习模型的训练过程需要大量标注数据，否则模型很难收敛到稳定状态。"
                + "另一种做法是把特征工程交给领域专家手工完成。");
        check(corpus.sentenceCount() == 2, "corpus indexed two sentences and dropped nothing");
        check(!corpus.isEmpty(), "corpus reports itself non-empty");
        String query = "前文无关的一句开场白，用来占位并保证偏移不在零。深度学习模型的训练过程需要海量标注数据，"
                + "不然模型难以收敛到稳定状态。后面又是一句全新的话，讲的是手工特征工程的历史。";
        TextCorpus.Report report = corpus.match(query, null);
        check(!report.hits.isEmpty(), "match finds the paraphrased passage");
        TextCorpus.Hit hit = null;
        for (int i = 0; i < report.hits.size(); i++) {
            TextCorpus.Hit candidate = report.hits.get(i);
            if (candidate.source == paper) hit = candidate;
        }
        check(hit != null, "hit carries the source object it came from");
        String fragment = hit == null ? "" : query.substring(hit.start, hit.end);
        check(fragment.length() > 8 && fragment.contains("标注数据"),
                "hit offsets slice out the paraphrased sentence from the original string");
        check(hit != null && hit.score >= TextCorpus.SIMILAR_DICE && hit.score <= 1f,
                "hit score is a Dice inside the contracted 0..1 range");
        check(report.byEngine.containsKey("openalex") && report.byEngine.get("openalex").doubleValue() > 0d,
                "byEngine attributes duplicated characters to the engine name");
        TextCorpus.Report clean = corpus.match("今天的天气不错，我们决定去爬山，山顶的风很大。", null);
        check(clean.hits.isEmpty(), "unrelated query produces no hits");
        TextCorpus emojiCorpus = new TextCorpus();
        emojiCorpus.add(source("p2", "含表情来源", "local"), "实验组🙂的准确率提升了十二个百分点，差异具有统计意义。");
        String emojiQuery = "\uD83D\uDE00\uD83D\uDE42\u200B\u3000前言部分\u3000实验组\uD83D\uDE42的准确率提升了 12 个百分点，差异具有统计意义。\u3000结论部分";
        TextCorpus.Report emojiReport = emojiCorpus.match(emojiQuery, null);
        check(emojiReport.hits.size() == 1, "surrogate pairs and zero-width marks do not break matching");
        TextCorpus.Hit emojiHit = emojiReport.hits.get(0);
        String emojiFragment = emojiQuery.substring(emojiHit.start, emojiHit.end);
        check(emojiFragment.contains("准确率") && emojiFragment.contains("统计意义"),
                "hit offsets land on the real sentence even with emoji and zero-width characters before it");
        check(emojiFragment.length() > 0 && !emojiFragment.startsWith("　") && !emojiFragment.endsWith("　"),
                "hit range is trimmed of leading and trailing whitespace");
        TextCorpus crossSource = new TextCorpus();
        crossSource.add(source("a", "来源A", "openalex"), "深度学习模型的训练过程需要大量标注数据，否则模型很难收敛到稳定状态。");
        crossSource.add(source("b", "来源B", "crossref"), "把特征工程交给领域专家手工完成，是早期系统的常见做法。");
        TextCorpus.Report two = crossSource.match("深度学习模型的训练过程需要大量标注数据，否则模型很难收敛到稳定状态。"
                + "把特征工程交给领域专家手工完成，是早期系统的常见做法。", null);
        check(two.hits.size() == 2, "two adjacent hits from different sources stay as two separate segments");
        check(two.byEngine.size() == 2, "byEngine lists both engines");
        check(overlapPairs(report.hits) == 0 && overlapPairs(two.hits) == 0,
                "命中区间两两不重叠：来源榜与「按检索源分布」那两张表都建立在这一点上");
    }

    /**
     * 第三条判据：字符袋（D2 落地）。钉四件事——度量本身、它换来的那次命中、关掉它那次命中就消失
     * （证明这条命中确实归它，不是别的通道顺手收的）、以及它的落点只红两边真重合的那几段。
     */
    private static void bagChannel() {
        String base = "城市道路交通拥堵的治理需要把停车管理、公共交通优先和路网信号配时这三项措施放在一起来考虑，"
                + "单独推行其中任何一项都难以在三年之内看到效果。";
        String swapped = "城区路网交通阻塞的管控必须把车位管制、公共交通优先和绿灯配时这三项措施放在一起来权衡，"
                + "孤立地推行其中任何一项都很难在三年之内看到效果。";
        float gram = TextCorpus.dice(base, swapped);
        float bag = TextCorpus.bagDice(base, swapped);
        System.out.println("BAG CASE 三元组 Dice=" + gram + " 袋 Dice=" + bag
                + "（地板 " + TextCorpus.SIMILAR_BAG_DICE + "）");
        check(gram < TextCorpus.SIMILAR_DICE, "这一对必须是三元组判据漏掉的那一档，实测 Dice " + gram);
        check(bag >= TextCorpus.SIMILAR_BAG_DICE, "这一对必须过袋口径的地板，实测袋 Dice " + bag);
        // 度量本身的性质：相同为 1、无关为 0、对称、去重、上界。地板压在天花板之上那条在
        // RewriteRobustnessRegression 的 bagStudy 里钉，这里只管实现对不对。
        check(TextCorpus.bagDice(base, base) == 1f, "同一个袋的距离是 1");
        check(TextCorpus.bagDice("昨天的晚餐是红烧肉和清炒时蔬", "图 7 展示了拟合优度") < 0.3f,
                "两句话题无关时袋口径也必须低");
        check(TextCorpus.bagDice(base, swapped) == TextCorpus.bagDice(swapped, base), "袋口径对称");
        check(TextCorpus.bagOf("重复重复的字字啊啊啊").length == 5, "字符袋去重：九个位置五种字");
        check(TextCorpus.bagOf("重复 重复\t的字字啊啊啊\n").length == 5, "空白与不可见字符进不了袋");
        // 标点留在袋里：天花板 0.577 是带着标点量出来的，口径不许在实现里偷偷改。
        check(TextCorpus.bagOf("abc。、").length == 5, "标点照常进袋，与实测天花板的口径一致");
        char[] big = TextCorpus.bagOf(base + base + swapped);
        char[] small = TextCorpus.bagOf(swapped);
        check(TextCorpus.bagDiceOf(big, small) <= TextCorpus.bagReach(big, small) + 1e-6f,
                "袋 Dice 不许超过它自己的上界");
        // 端到端：这一对必须报出来，而且分值仍是字面三元组的 Dice（改写越重报出的分越低，不许虚高）。
        TextCorpus corpus = new TextCorpus();
        TextCorpus.Source paper = source("bag-1", "城市交通治理研究", "wanfang");
        corpus.add(paper, base);
        String query = "本文的第一节先交代研究背景与数据来源。" + swapped + "第二节给出模型的推导过程与参数设置。";
        TextCorpus.Report on = corpus.match(query, null);
        check(!on.hits.isEmpty(), "改写过的句子必须被袋口径认出来");
        TextCorpus.Hit hit = null;
        for (int i = 0; i < on.hits.size(); i++) if (on.hits.get(i).source == paper) hit = on.hits.get(i);
        check(hit != null, "袋口径的命中要署得出来源");
        // 1.1.6 起袋口径的打分管径改成 max(字面 Dice, 袋 Dice x 0.8)：字面三元组可以是零，
        // 报"相似度 0.0%"配一段红字就是用户眼里的 bug。八折沿用包含率那条通道的系数。
        check(hit != null && Math.abs(hit.score - Math.max(gram, bag * 0.8f)) < 0.02f,
                "袋口径的命中分值 = max(字面 Dice, 袋 Dice 的八折)，实测 " + (hit == null ? 0f : hit.score)
                        + "，字面 " + gram + "，袋 " + bag);
        TextCorpus.overrideBagFloor(1.01f);
        TextCorpus.Report off;
        try {
            off = corpus.match(query, null);
        } finally {
            TextCorpus.restoreBagFloor();
        }
        boolean offSawIt = false;
        for (int i = 0; i < off.hits.size(); i++) if (off.hits.get(i).source == paper) offSawIt = true;
        check(!offSawIt, "关掉袋口径这一对就查不出来——开着才查得出，证明这条命中归袋口径这条通道");
        // 落点：只许红两边真重合的那几段。改写掉的 23 个字一个都不许跟着红。
        String flagged = "";
        for (int i = 0; i < on.hits.size(); i++) {
            TextCorpus.Hit each = on.hits.get(i);
            if (each.source == paper) flagged += query.substring(each.start, each.end);
        }
        System.out.println("BAG SPAN 改写句 " + swapped.length() + " 字，红在正文里的是 " + flagged.length()
                + " 字：" + flagged);
        check(flagged.contains("公共交通优先") && flagged.contains("三年之内看到效果"),
                "没被改写的那几段必须落在命中里");
        // 改写掉的词不许整块红。"很难"那两个字会跟着红是 MERGE_GAP=2 并段的结果——两边各自验过一段
        // 逐字公共块、间隔只有两个字时并成一条，这是 1.1.1 就定下的落点口径，不是袋口径新引入的。
        check(!flagged.contains("孤立地") && !flagged.contains("车位管制") && !flagged.contains("权衡")
                && !flagged.contains("城区路网") && !flagged.contains("管控"),
                "改写掉的词不许整块红：" + flagged);
        check(flagged.length() <= swapped.length() - 18,
                "整句 " + swapped.length() + " 字最多只许红 " + (swapped.length() - 18) + " 字，实测 "
                        + flagged.length() + " 字：自己写的与改写掉的字必须留在红区之外");
        // 机制：把整句倒过来写——字符袋一个字不差，三元组一枚不剩（Dice 实测 0.0）。以前候选只来自
        // 三元组倒排，这种句子连被袋口径看一眼的机会都没有；现在由字符袋倒排补进候选。这一档不是
        // 为了抓"倒着抄"，是为了钉住"取候选这一步已经脱离三元组"这件事：把它改回只认三元组倒排，
        // 这两条断言当场失败。
        String reversed = new StringBuilder(base).reverse().toString();
        TextCorpus res = new TextCorpus();
        TextCorpus.Source rescueSource = source("bag-3", "倒排取候选", "wanfang");
        res.add(rescueSource, base);
        String reversedQuery = "前言部分占位文字若干字，用来把偏移推开一点。" + reversed;
        TextCorpus.Report onRescue = res.match(reversedQuery, null);
        boolean sawReversed = false;
        for (int i = 0; i < onRescue.hits.size(); i++) {
            if (onRescue.hits.get(i).source == rescueSource) sawReversed = true;
        }
        check(sawReversed, "字面三元组为零、字符袋全等的改写句必须查得出来（袋口径的字符袋倒排取候选）");
        check(res.bagProbes() > 0, "这一枪必须真的走过字符袋倒排的精算，实测精算 " + res.bagProbes() + " 个候选");
        TextCorpus offRes = new TextCorpus();
        offRes.add(rescueSource, base);
        TextCorpus.overrideBagFloor(1.01f);
        TextCorpus.Report offRescue;
        try {
            offRescue = offRes.match(reversedQuery, null);
        } finally {
            TextCorpus.restoreBagFloor();
        }
        check(offRescue.hits.isEmpty() && offRes.bagProbes() == 0,
                "地板抬到 1 以上时字符袋那条取候选的路必须整条空转：命中 " + offRescue.hits.size()
                        + " 条、精算 " + offRes.bagProbes() + " 个候选");
        // 负例：同领域、同一套术语、说的是另一件事——三元组与袋都不许过线。
        TextCorpus negative = new TextCorpus();
        negative.add(source("bag-2", "另一篇交通论文", "wanfang"), base);
        check(negative.match("城区路网的投资强度在过去十年里持续上升，公共交通的客运分担率却停滞不前，"
                + "这两条曲线背后的政策逻辑完全不同。", null).hits.isEmpty(), "同领域另一件事不许被袋口径撞车");
        /* 字族门（TextCorpus.BAG_LATIN_CEILING）：字符袋在拉丁字母表上没有意义——二十六个字母加数字，
           同一个子领域的两个英文题名、两条参考文献，字符袋天然互相包含。真人语料实测：袋口径过线的
           406 对全部落在拉丁占比 >= 0.5 那一桶，最靠近门的一对是 0.743，0.5 以下 0 对（复现
           pwsh tools/bag-false-positive-probe.ps1）。下面三条钉住：拉丁文本不许走袋口径；把门抬到 0.95
           把门拆掉它就报出来（证明拒它的是这道门，不是别的环节）；而中文句子里夹几个英文术语不受影响——
           那条断言就是上面 bag-1 那一枪，它的拉丁占比实测在下面打印。 */
        String latinA = "Review of die attached silver sintering technology for high temperature electronics packaging";
        String latinB = "Recent progress in transient liquid phase bonding of silicon carbide devices at high temperature";
        float latinBag = TextCorpus.bagDice(latinA, latinB);
        System.out.println("LATIN GATE 两条英文题名的袋 Dice=" + latinBag + "，拉丁占比 "
                + AigcFamily.latinRatio(TextCorpus.compactOf(latinB)) + "（门 " + AigcFamily.LATIN_RATIO + "）");
        check(latinBag >= TextCorpus.SIMILAR_BAG_DICE,
                "这两条英文题名必须过袋口径的地板——拉丁袋谁都过线，实测 " + latinBag);
        TextCorpus latin = new TextCorpus();
        TextCorpus.Source latinSource = source("bag-4", "英文题名", "cnki");
        latin.add(latinSource, latinA);
        boolean latinSaw = saw(latin.match(latinB, null), latinSource);
        check(!latinSaw, "拉丁文本不许走袋口径：两条英文题名撞袋是常态，报出来就是误报");
        TextCorpus.Report gateLifted;
        TextCorpus.overrideBagLatinCeiling(1.01d);
        try {
            gateLifted = latin.match(latinB, null);
        } finally {
            TextCorpus.restoreBagLatinCeiling();
        }
        check(saw(gateLifted, latinSource), "把字族门抬到 1.01（等于不设门）这一对就得报出来——证明上一条拒它的是这道门");
    }

    /** 命中里有没有署给某个来源的。 */
    private static boolean saw(TextCorpus.Report report, TextCorpus.Source source) {
        for (int i = 0; i < report.hits.size(); i++) if (report.hits.get(i).source == source) return true;
        return false;
    }

    /**
     * 短碎片（D4 的实测结论钉在这里）：把抄来的段落每十个字补一个逗号，碎片全部短于句长门 12，
     * 整段照旧认得出来——接住它的是指纹带那一层（Fingerprints.tokens() 跳标点，补进去的逗号打不断
     * 字符流）。所以句长门停在 12：放开到 10 甚至 9，五列口径逐位不动，表与理由见
     * docs/rewrite-robustness.md 的 1.1.5 一节。要改这一档，先拿出能看出差别的口径。
     */
    private static void shortFragments() {
        String library = "深度学习模型的训练过程需要大量标注数据，否则模型很难收敛到稳定状态，"
                + "另一种做法是把特征工程交给领域专家手工完成，两条路线的代价完全不同。";
        TextCorpus corpus = new TextCorpus();
        TextCorpus.Source paper = source("sf-1", "两条路线的代价", "local");
        corpus.add(paper, library);
        StringBuilder chopped = new StringBuilder("本节先交代实验设置与评价指标。");
        int run = 0;
        for (int i = 0; i < library.length(); i++) {
            char c = library.charAt(i);
            chopped.append(c);
            if (!Character.isWhitespace(c)) run++;
            if (run >= 10 && i + 1 < library.length()) {
                chopped.append('，');
                run = 0;
            }
        }
        chopped.append("最后一节是结论与展望。");
        String query = chopped.toString();
        TextCorpus.Report report = corpus.match(query, null);
        check(!report.hits.isEmpty(), "十字一段的碎片必须仍然被认出来：接住它的是指纹带，不是句长门");
        int flagged = 0;
        for (int i = 0; i < report.hits.size(); i++) {
            TextCorpus.Hit hit = report.hits.get(i);
            if (hit.source == paper) flagged += nonBlank(query.substring(hit.start, hit.end));
        }
        int planted = nonBlank(library);
        System.out.println("SHORT FRAGMENTS 种入 " + planted + " 字，十字一刀之后认回 " + flagged + " 字");
        check(flagged * 100 >= planted * 95, "十字一刀的抄写至少要认回 95%，实测 " + flagged + "/" + planted);
        check(TextCorpus.MIN_SENTENCE_CHARS == 12,
                "句长门停在 12：放开到 10 与 9 在实测台上逐位不动，改这一档要先拿出能看出差别的口径");
    }

    /** 非空白字符数：这一段自己数，不去碰引擎里的私有口径。 */
    private static int nonBlank(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) if (!Character.isWhitespace(text.charAt(i))) n++;
        return n;
    }

    /** 指纹带：与断句无关的连续重复，短于最短匹配长度的巧合不算重复。 */
    private static void fingerprints() {
        /* 参数不是照抄论文里的 8 和 12，是标定台扫出来的：真实论文正文做文库，正例是原文的六种
           写法，负例取同一篇论文里内容无关的句子。n=7、w=12 配三枚共通指纹那一档，召回与
           n=8、w=12 配两枚一样是满的，锚点层误报却从 45.5% 掉到 0.0%。原始表格见
           docs/detection-calibration.md，重跑 tools/detect-calibration.ps1。 */
        check(Fingerprints.GRAM == 7 && Fingerprints.WINDOW == 12, "取样参数标定在 7 元组配 12 的窗口");
        check(Fingerprints.MIN_MATCH == 18, "最短可报告匹配是 18 个字符");
        String[] pieces = {"第一段只有十来个字。", "第二段也差不多长。", "第三段同样如此而已。"};
        String stitched = "第一段只有十来个字第二段也差不多长第三段同样如此而已";
        check(TextCorpus.sentences(pieces[0]).size() == 1, "语料里的每一小段都是一句");
        TextCorpus small = new TextCorpus();
        TextCorpus.Source paper = source("fp1", "跨句复制的样本", "local");
        small.add(paper, pieces[0] + pieces[1] + pieces[2]);
        check(small.sentenceCount() == 0, "短于十二字的句子不进句级索引");
        String copied = "文献的原话是" + stitched + "，这一点在实验记录里可以对上。";
        TextCorpus.Report stitchedReport = small.match(copied, null);
        check(!stitchedReport.hits.isEmpty(), "每句都不够长时，指纹带仍然找到连续复制");
        boolean spansBoundary = false;
        for (int i = 0; i < stitchedReport.hits.size(); i++) {
            TextCorpus.Hit hit = stitchedReport.hits.get(i);
            if (hit.start <= copied.indexOf(stitched) && hit.end >= copied.indexOf(stitched) + stitched.length())
                spansBoundary = true;
        }
        check(spansBoundary, "跨句的连续复制被报成一段而不是切碎");
        check(stitchedReport.duplicateChars >= Fingerprints.MIN_MATCH, "重复字符数记进了分子");
        check("跨句复制的样本".equals(stitchedReport.hits.get(0).source.title), "指纹命中带着来源标题");

        /* 数字换写法。折成同一枚 token 只解决了一半：滚出窗口时若照原字符减回去，两边减掉的
           权重不一样，哈希里留下一笔残值，数字之后的整条带子两边不再相等——一条完整复制被
           一个数字砍成两截，每截都不到最短可报告长度，等于没检出。 */
        TextCorpus numberSwap = new TextCorpus();
        numberSwap.add(source("fp-num", "数字写法", "local"),
                "在四十摄氏度的恒温箱里，样品保持了七十二小时的稳定状态，其间没有观察到任何异常变化。");
        TextCorpus.Report rewritten = numberSwap.match(
                "在40摄氏度的恒温箱里，样品保持了72小时的稳定状态，其间没有观察到任何异常变化。", null);
        int longest = 0;
        for (int i = 0; i < rewritten.hits.size(); i++)
            longest = Math.max(longest, rewritten.hits.get(i).end - rewritten.hits.get(i).start);
        check(longest >= 30, "数字换写法之后的整段复制仍是一条带子，没被数字砍断");

        /* 只撞术语不算重复：同一篇论文里内容无关的句子，是标定台上最难也最诚实的负例。 */
        TextCorpus sharedTerms = new TextCorpus();
        sharedTerms.add(source("fp-term", "同领域其它论文", "local"),
                "该模型的训练采用分层的卷积结构，并在公开数据集上报告了 top-5 准确率。");
        TextCorpus.Report termQuery = sharedTerms.match(
                "分层的卷积结构在本实验里只用于特征提取，top-5 准确率是附带记录的一项指标，与本文主线无关。", null);
        check(termQuery.duplicateChars < Fingerprints.MIN_MATCH, "只共用几个术语的句子不算重复");

        TextCorpus shortShare = new TextCorpus();
        shortShare.add(source("fp2", "术语来源", "local"),
                "这套流程的名称叫做自适应窗口取样算法，其余内容与本题无关，纯粹是为了凑够长度。");
        TextCorpus.Report shortQuery = shortShare.match(
                "自适应窗口取样算法这个名字听起来很直白，别的句子都是我们自己写的，没有从别处抄。", null);
        check(shortQuery.hits.isEmpty(), "共享不足十九个字符时不报重复");

        TextCorpus punctuated = new TextCorpus();
        punctuated.add(source("fp3", "标点干扰", "local"),
                "保温时间超过四十分钟之后反应层明显增厚，接头强度随之下降到原来的七成左右。");
        TextCorpus.Report punctReport = punctuated.match(
                "保温时间超过 40 分钟之后，反应层明显增厚；接头强度随之下降到原来的 70 %左右。这样的写法很常见。", null);
        check(!punctReport.hits.isEmpty(), "标点与半角数字改动不影响连续重复的认定");

        TextCorpus both = new TextCorpus();
        both.add(source("fp4", "重复计数的对照", "local"),
                "取样位置固定在接头中心两侧，每次试验都记录峰值载荷与断裂位置。");
        String verbatim = "取样位置固定在接头中心两侧，每次试验都记录峰值载荷与断裂位置。";
        TextCorpus.Report overlap = both.match(verbatim + "后面是我们自己写的分析部分，用来把分母撑大一些。", null);
        int once = TextCorpus.validCount(TextCorpus.normalize(verbatim), 0, verbatim.length());
        check(overlap.duplicateChars == once, "两套算法命中同一段时字符只算一次");

        String reference = "参考文献\n[1] 张三. 取样位置对峰值载荷的影响[J]. 焊接学报, 2020, 41(3): 12-20.\n"
                + "[2] 李四. 反应层厚度与接头强度[J]. 材料工程, 2021, 49(8): 33-41.\n";
        TextCorpus citedOut = new TextCorpus();
        citedOut.add(source("fp5", "被引用的条目", "local"),
                "取样位置对峰值载荷的影响在接头中心两侧最为明显，这一点已经被反复验证过。");
        TextCorpus.Report citedReport = citedOut.match("正文从这里开始讲我们的实验设置。\n" + reference,
                null, TextCorpus.structure("正文从这里开始讲我们的实验设置。\n" + reference).spanArray());
        check(citedReport.hits.isEmpty(), "落在参考文献区间里的重复不会被指纹带翻出来");
    }

    private static void merging() {
        TextCorpus corpus = new TextCorpus();
        TextCorpus.Source paper = source("m1", "同来源", "local");
        corpus.add(paper, "第一段内容讲的是数据采集流程与清洗规则。第二段内容讲的是模型训练与评估指标。");
        String query = "第一段内容讲的是数据采集流程与清洗规则，接着第二段内容讲的是模型训练与评估指标。";
        TextCorpus.Report report = corpus.match(query, null);
        check(report.hits.size() == 1, "adjacent hits from the same source merge into one interval");
        check(report.hits.get(0).start == 0 && report.hits.get(0).end == query.length(),
                "merged interval spans from the first to the last duplicated character");
        check(report.hits.get(0).score > 0.5f && report.hits.get(0).score <= 1f,
                "merged score is the weighted mean, not the minimum");
        TextCorpus.Report same = corpus.match("第一段内容讲的是数据采集流程与清洗规则。第二段内容讲的是模型训练与评估指标。", null);
        check(same.hits.size() == 1 && same.hits.get(0).end - same.hits.get(0).start > 20,
                "sentences separated by one punctuation mark merge as well (gap within MERGE_GAP)");
        TextCorpus far = new TextCorpus();
        far.add(source("m2", "远隔来源", "local"), "第一句是关于数据采集的描述内容。第二句是关于模型评估的描述内容。");
        String farQuery = "第一句是关于数据采集的描述内容。" + "中间插入了完全不同的一句话，讲的是实验室的空调坏了三天。"
                + "第二句是关于模型评估的描述内容。";
        check(far.match(farQuery, null).hits.size() == 2, "hits separated by an unrelated sentence stay separate");
        check(overlapPairs(report.hits) == 0 && overlapPairs(same.hits) == 0,
                "并段之后的命中两两不重叠：并段靠的是区间合并，不是把两条命中叠在同一批字上");
    }

    private static void rates() {
        String head = "本章先给出总体框架，再逐节展开细节说明。";
        String dupA = "本文采用混合注意力机制处理长文本的特征表示。";
        String dupB = "实验在四个公开数据集上重复了全部对比实验。";
        String tail = "最后一行是未被命中的正文内容说明。";
        TextCorpus corpus = new TextCorpus();
        corpus.add(source("r1", "指标来源", "openalex"), dupA + dupB);
        String plain = head + dupA + tail + dupB;
        TextCorpus.Report report = corpus.match(plain, null);
        int compared = TextCorpus.validCount(head, 0, head.length()) + TextCorpus.validCount(dupA, 0, dupA.length())
                + TextCorpus.validCount(tail, 0, tail.length()) + TextCorpus.validCount(dupB, 0, dupB.length());
        int duplicated = TextCorpus.validCount(dupA, 0, dupA.length()) + TextCorpus.validCount(dupB, 0, dupB.length());
        check(report.comparedChars == compared,
                "comparedChars = 排除区之外的一切有效字符（这句里就是那 4 句正文的 " + compared + " 个字）");
        check(report.duplicateChars == duplicated, "duplicateChars equals the valid characters inside the merged hits");
        // 0.7.3 换分母的正面回归：再塞一句只有 5 个有效字符的短句。它短到切不出比对片段
        // （fragment 在 codePoints < MIN_SENTENCE_CHARS 时返回 null），0.7.2 因此把整句从分母里抹掉——
        // 真文档 tests/samples/input-liu.docx 实测就这样比账本分母少 946 个字（14962 对 15908）。
        // 现在按"整篇减排除区"数，短句照进分母，与 CharLedger.totalChars 是同一个算式。
        String shortOne = "本节小结。";
        String withShort = head + dupA + shortOne + tail + dupB;
        TextCorpus.Report shorted = corpus.match(withShort, null);
        check(TextCorpus.validCount(TextCorpus.normalize(shortOne), 0, shortOne.length()) == 5,
                "夹具自证：这句小结 5 个有效字符，短到切不出比对片段");
        check(shorted.comparedChars == compared + 5,
                "分母 " + compared + " + 5 = " + shorted.comparedChars + "：切不出片段的短句不再两边都不算");
        check(shorted.duplicateChars == duplicated, "短句只进分母，分子一个字符都不多");
        check(overlapPairs(shorted.hits) == 0, "带短句这一版的命中也两两不重叠");
        CharLedger.Balance plainBalance = CharLedger.close(withShort, null, null, shorted.hits, null);
        check(plainBalance.totalChars == shorted.comparedChars
                        && plainBalance.duplicateChars == shorted.duplicateChars
                        && plainBalance.citedDuplicateChars == shorted.citedDuplicateChars,
                "与 CharLedger 同一把尺：分母 " + shorted.comparedChars + " / 分子 " + shorted.duplicateChars
                        + " / 引用内 " + shorted.citedDuplicateChars + " 三个数与账本一字不差");
        check(Math.abs(report.overallRate - duplicated * 100d / compared) < 1e-9, "overallRate = duplicateChars / comparedChars * 100");
        check(report.citedDuplicateChars == 0 && Math.abs(report.excludingCitationsRate - report.overallRate) < 1e-9,
                "without citation spans nothing is excluded and the two rates coincide");
        check(report.byEngine.size() == 1
                && Math.abs(report.byEngine.get("openalex").doubleValue() - report.overallRate) < 1e-9,
                "a single source attributes the whole duplication to its engine");

        String quotedLine = "“" + dupB + "”";
        String reference = "[1] 张三. 混合注意力机制研究综述[J]. 计算机学报, 2021.";
        TextCorpus cited = new TextCorpus();
        cited.add(source("r2", "带引用来源", "crossref"), dupA + dupB + "\n" + reference);
        String mixed = head + dupA + "\n" + quotedLine + "\n" + reference;
        int quoteFrom = mixed.indexOf('“');
        int quoteTo = mixed.indexOf('”') + 1;
        int refFrom = mixed.indexOf("[1]");
        int refTo = mixed.length();
        int[] spans = {quoteFrom, quoteTo, refFrom, refTo};
        TextCorpus.Report citedReport = cited.match(mixed, spans);
        check(citedReport.duplicateChars > 0, "cited sample still reports duplication");
        check(citedReport.citedDuplicateChars > 0, "characters inside quote and reference spans are counted as cited");
        check(citedReport.citedDuplicateChars < citedReport.duplicateChars, "part of the duplication stays uncited");
        check(Math.abs(citedReport.excludingCitationsRate
                - (citedReport.duplicateChars - citedReport.citedDuplicateChars) * 100d / citedReport.comparedChars) < 1e-9,
                "excludingCitationsRate drops exactly the cited share");
        check(citedReport.excludingCitationsRate < citedReport.overallRate, "excluding-citations rate is the lower number");
        int insideSpans = 0;
        for (int i = 0; i < citedReport.hits.size(); i++) {
            TextCorpus.Hit hit = citedReport.hits.get(i);
            if (hit.start >= quoteFrom && hit.end <= quoteTo) {
                insideSpans += TextCorpus.validCount(mixed, hit.start, hit.end);
            }
        }
        check(insideSpans > 0 && insideSpans <= citedReport.citedDuplicateChars,
                "the quoted line contributes its whole valid character count to citedDuplicateChars");
        check(cited.match(mixed, new int[]{mixed.length(), 0}).citedDuplicateChars == 0,
                "malformed citation spans are ignored instead of crashing");
        check(cited.match(mixed, new int[]{5}).citedDuplicateChars == 0, "odd-length citation span array is tolerated");
    }

    private static void citations() {
        check(TextCorpus.citationLike("[1] 张三. 标题[J]. 学报, 2020."),
                "citationLike accepts the numbered journal reference");
        check(!TextCorpus.citationLike("实验结果表明，该方法在噪声环境下仍然保持稳定，识别率提高了十二个百分点。"),
                "citationLike rejects an ordinary body sentence");
        check(TextCorpus.citationLike("Smith J, Li W. Deep learning for text mining. Journal of Machine Learning, 2019: 112-130."),
                "citationLike accepts an author-initial journal reference");
        check(!TextCorpus.citationLike("我们在 2020 年采集了三千份样本，其中约 10-15% 存在缺失值。"),
                "citationLike rejects a body sentence that only mentions a year and a range");
        check(!TextCorpus.citationLike("1. 引言") && !TextCorpus.citationLike("") && !TextCorpus.citationLike(null),
                "citationLike rejects headings, empty and null input");
        check(TextCorpus.citationLike("[12］王五，赵六．面向长文本的注意力机制［Ｊ］．软件学报，２０２２（３）：４５－５２．"),
                "citationLike accepts fullwidth numbered references");
    }

    private static void edgeCases() {
        TextCorpus empty = new TextCorpus();
        TextCorpus.Report report = empty.match("任意一段足够长的正文内容，用来检查空语料不会抛异常。", null);
        check(report.hits.isEmpty() && report.overallRate == 0d && report.excludingCitationsRate == 0d,
                "matching against an empty corpus yields an empty report without throwing");
        check(empty.sentenceCount() == 0 && empty.isEmpty(), "fresh corpus is empty");
        TextCorpus corpus = new TextCorpus();
        corpus.add(null, "来源为空的文本也要能进入索引，这句话本身足够长。");
        check(corpus.sentenceCount() == 1, "null source is replaced by a blank source instead of failing");
        check(corpus.match(null, null).hits.isEmpty() && corpus.match("", null).hits.isEmpty(),
                "matching null or empty text returns without throwing");
        TextCorpus tiny = new TextCorpus();
        tiny.add(source("t", "短句", "local"), "好的。走吧。明天见吧。");
        check(tiny.sentenceCount() == 0, "sentences under 12 valid characters never enter the index");
        check(tiny.isEmpty(), "corpus of only short sentences stays empty");
        check(tiny.match("好的。走吧。明天见吧。", null).hits.isEmpty(),
                "short-only text produces no hits instead of a false positive");
        tiny.add(source("t2", "混合", "local"), "这一句足够长所以会被纳入索引范围。但是后面这一句太短。");
        check(tiny.sentenceCount() == 1, "only the long sentence of a mixed paragraph is indexed");
        TextCorpus cleared = new TextCorpus();
        cleared.add(source("c", "清空", "local"), "这一段足够长的中文文本用来验证清空语义是否正确。");
        check(cleared.sentenceCount() == 1, "corpus holds one sentence before clear");
        cleared.clear();
        check(cleared.isEmpty() && cleared.sentenceCount() == 0
                && cleared.match("这一段足够长的中文文本用来验证清空语义是否正确。", null).hits.isEmpty(),
                "clear drops sentences, the inverted index and the sources");
        TextCorpus longLine = new TextCorpus();
        StringBuilder runOn = new StringBuilder();
        for (int i = 0; i < 900; i++) runOn.append("无标点长串片段");
        String longText = runOn.toString();
        longLine.add(source("l", "长串", "local"), longText);
        check(longLine.sentenceCount() >= 3, "a punctuation-free run is windowed instead of being one giant sentence");
        long start = System.nanoTime();
        TextCorpus.Report longReport = longLine.match(longText, null);
        long millis = (System.nanoTime() - start) / 1000000L;
        check(!longReport.hits.isEmpty() && longReport.overallRate > 90d,
                "the run-on text still matches itself in " + millis + " ms");
        // 900 段七字连排 = 6300 个有效字符，按 step = 512 - 64 = 448 切 14 窗（语料侧 sentenceCount 也是 14）。
        // 同一来源会并成一条命中，所以这一条在 0.7.2 也算对了；留着它是钉住"分子不会越过自己的分母"。
        check(longReport.comparedChars == 6300 && longReport.duplicateChars == 6300,
                "连排长串自比：分子 == 分母 == 6300，切 14 窗取并集不会让一个字符多算一次");
        // 真正会双算的是「两篇各拿一窗」：按 step = 512 - 64 = 448 切窗，相邻两窗重叠 64 个字。
        // 0.7.2 把甲篇那一窗的 512 与乙篇那一窗的 384 各加一遍，分子 896 越过分母 832（来源榜跟着虚高）。
        StringBuilder winA = new StringBuilder();
        for (int i = 0; i < 512; i++) winA.append((char) (0x4E00 + (i * 37) % 400));
        StringBuilder winB = new StringBuilder();
        for (int i = 0; i < 320; i++) winB.append((char) (0x4E00 + 400 + (i * 53) % 400));
        TextCorpus sliding = new TextCorpus();
        sliding.add(source("sl1", "滑窗甲", "openalex"), winA.toString());
        sliding.add(source("sl2", "滑窗乙", "crossref"), winB.toString());
        TextCorpus.Report slid = sliding.match(winA.toString() + winB.toString(), null);
        check(slid.comparedChars == 832 && slid.duplicateChars == 832,
                "两篇各拿一窗时分子取并集：512 + 320 = 832 == 分母（旧口径 512 + 384 = 896 双算了重叠的 64）");
        check(overlapPairs(slid.hits) == 0 && slid.hits.size() == 2
                        && slid.hits.get(1).start == 512,
                "第二条命中被裁到 512 起：重叠的 64 个字划归先占的甲篇，两条命中不再相交");
    }

    private static void performance() {
        String[] atoms = {"深度学习模型", "在自然语言处理", "的特征提取", "实验中", "显著提升了", "训练收敛速度",
                "并降低了", "人工标注成本", "该方法", "结合注意力机制", "对长文本", "表现出更好的泛化能力",
                "需要注意的是", "数据规模", "对结果影响明显", "本文提出的", "混合结构", "在公开数据集上", "验证了有效性"};
        Random random = new Random(20261007L);
        StringBuilder corpusText = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            int words = 8 + random.nextInt(10);
            for (int k = 0; k < words; k++) corpusText.append(atoms[random.nextInt(atoms.length)]);
            corpusText.append('。');
        }
        TextCorpus corpus = new TextCorpus();
        TextCorpus.Source bulk = source("bulk", "批量语料", "local");
        String[] lines = corpusText.toString().split("。");
        long indexBegan = System.nanoTime();
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].length() > 0) corpus.add(bulk, lines[i] + "。");
        }
        long indexMillis = (System.nanoTime() - indexBegan) / 1000000L;
        check(corpus.sentenceCount() >= 2500, "bulk corpus indexes " + corpus.sentenceCount() + " sentences");
        System.out.println("PERF index(" + corpus.sentenceCount() + " sentences, " + corpusText.length()
                + " corpus chars) = " + indexMillis + " ms");
        String query = corpusText.substring(0, 20000);
        corpus.match(query.substring(0, 4000), null);
        long best = Long.MAX_VALUE;
        TextCorpus.Report report = null;
        for (int round = 0; round < 3; round++) {
            long began = System.nanoTime();
            report = corpus.match(query, null);
            long millis = (System.nanoTime() - began) / 1000000L;
            if (millis < best) best = millis;
        }
        check(report != null && report.overallRate > 99d, "the duplicated 20k-character passage is reported as fully duplicated");
        check(best < 4000L, "match over 20k characters and 3k corpus sentences stays under 4s on desktop JVM (measured "
                + best + " ms)");
        System.out.println("PERF match(20000 chars, " + corpus.sentenceCount() + " corpus sentences) = " + best + " ms"
                + ", hits=" + report.hits.size() + ", comparedChars=" + report.comparedChars);

        StringBuilder rewritten = new StringBuilder();
        for (int i = 0; i < lines.length && rewritten.length() < 20000; i++) {
            if (lines[i].length() == 0) continue;
            rewritten.append("另外，").append(lines[i]).append("，这一点在实际工程里很关键。");
        }
        String paraphrase = rewritten.substring(0, 20000);
        corpus.match(paraphrase.substring(0, 4000), null);
        long fuzzy = Long.MAX_VALUE;
        TextCorpus.Report fuzzyReport = null;
        for (int round = 0; round < 3; round++) {
            long began = System.nanoTime();
            fuzzyReport = corpus.match(paraphrase, null);
            long millis = (System.nanoTime() - began) / 1000000L;
            if (millis < fuzzy) fuzzy = millis;
        }
        check(fuzzyReport != null && fuzzyReport.overallRate > 60d,
                "a sentence-by-sentence rewrite of the same 20k characters is still flagged ("
                        + (fuzzyReport == null ? 0d : fuzzyReport.overallRate) + "% duplicated)");
        check(fuzzy < 4000L, "the rewrite path stays bounded too (measured " + fuzzy + " ms)");
        System.out.println("PERF match(20000 chars paraphrased, " + corpus.sentenceCount()
                + " corpus sentences) = " + fuzzy + " ms, hits=" + fuzzyReport.hits.size()
                + ", rate=" + fuzzyReport.overallRate);
        String[] offTopic = {"会议安排在周三下午", "会议室在三楼东侧", "投影仪需要自带转接头", "纪要由行政同事整理", "午餐改成自助形式", "停车证在前台领取", "来访登记在一层大厅", "网络密码每周更换一次"};
        TextCorpus unrelated = new TextCorpus();
        TextCorpus.Source noise = source("u", "无关语料", "local");
        StringBuilder other = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            int words = 8 + random.nextInt(10);
            for (int k = 0; k < words; k++) other.append(offTopic[random.nextInt(offTopic.length)]);
            other.append("。");
        }
        unrelated.add(noise, other.toString());
        check(unrelated.sentenceCount() >= 2500, "the off-topic corpus holds " + unrelated.sentenceCount() + " sentences");
        long cold = System.nanoTime();
        TextCorpus.Report miss = unrelated.match(query, null);
        long missMillis = (System.nanoTime() - cold) / 1000000L;
        check(miss.hits.isEmpty() && miss.duplicateChars == 0, "a corpus with no shared vocabulary produces no hits");
        System.out.println("PERF no-overlap match of the same query = " + missMillis + " ms");
        check(missMillis < 4000L, "candidate search without overlap stays bounded (measured " + missMillis + " ms)");
        check(miss.comparedChars == report.comparedChars, "comparedChars depends on the query, not on the corpus size");
    }

    private static void library() throws Exception {
        File directory = new File("artifacts/tests/library");
        deleteRecursively(directory);
        byte[] docx = readAll(new File("tests/fixture.docx"));
        LocalLibrary library = new LocalLibrary(directory);
        check(library.size() == 0 && library.entries().isEmpty(), "a fresh library is empty");
        String note = "本文提出一种基于倒排索引的中文论文指纹匹配方法，并在自建语料上评估。";
        String other = "深度学习方法在文本重复检测与生成内容识别两个任务上都被广泛使用。";
        check(library.addDocument("notes.txt", (note + "\n").getBytes("UTF-8")) == null, "addDocument accepts a .txt file");
        check(library.addDocument("survey.md", ("## 综述\n" + other + "\n").getBytes("UTF-8")) == null,
                "addDocument accepts a .md file");
        check(library.addDocument("fixture.docx", docx) == null, "addDocument accepts a real .docx file");
        check(library.addDocument("tool.exe", new byte[]{1, 2, 3}) != null, "addDocument rejects an unsupported extension");
        check(library.addDocument("empty.txt", new byte[0]) != null, "addDocument rejects empty content");
        check(library.addDocument("index.json", "内容需要足够长才能通过正文检查。".getBytes("UTF-8")) != null,
                "addDocument refuses to overwrite index.json");
        String traversalText = "会议纪要定在下周三下午三点，一层大厅签到，行政同事负责整理与分发。这段文字用于验证路径穿越净化。";
        check(library.addDocument("../../evil/../evil.txt", traversalText.getBytes("UTF-8")) == null,
                "addDocument accepts a path-traversal name after sanitising it");
        ArrayList<LocalLibrary.Entry> entries = library.entries();
        check(library.size() == 4, "library keeps four documents, got " + library.size());
        String traversal = null;
        boolean namesClean = true;
        for (int i = 0; i < entries.size(); i++) {
            String name = entries.get(i).name;
            if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf("..") >= 0
                    || new File(name).isAbsolute()) namesClean = false;
            if (name.endsWith("evil.txt")) traversal = name;
        }
        check(namesClean, "every stored name is free of path separators and dot-dot segments");
        check(traversal != null && new File(directory, traversal).isFile(), "the traversal file landed inside the library directory");
        check(!new File(directory.getParentFile(), "evil.txt").exists() && !new File("evil.txt").exists(),
                "nothing was written outside the library directory");
        check(readAll(new File(directory, traversal)).length > 0, "the sanitised file really holds the bytes");
        check(LocalLibrary.sanitize("C:\\windows\\system32\\drivers\\etc\\hosts.txt").equals("hosts.txt"),
                "sanitize strips a windows absolute path down to the file name");
        check(LocalLibrary.sanitize("../../x").equals("x.txt"), "sanitize collapses dot-dot runs and defaults the extension");
        check(LocalLibrary.sanitize("a/b/c.txt").equals("c.txt"), "sanitize keeps only the last path segment");
        check(LocalLibrary.sanitize("na|me<>:?.txt").equals("na_me____.txt"), "sanitize replaces every illegal character");
        check(LocalLibrary.sanitize("  \u0001\u0007\u200b.  ") == null,
                "sanitize rejects names left with no usable characters");
        check(LocalLibrary.sanitize("index.json") == null, "sanitize refuses the reserved index name");

        String extracted = LocalLibrary.textOf(docx, "fixture.docx");
        check(extracted.indexOf("普通红色粗体斜体尾") >= 0, "textOf pulls the paragraph text out of the real .docx");
        check(LocalLibrary.textOf("\uFEFF带 BOM 的文本内容。\n".getBytes("UTF-8"), "a.md").startsWith("带 BOM"),
                "textOf tolerates a UTF-8 BOM in markdown");
        boolean rejected = false;
        try {
            LocalLibrary.textOf(new byte[]{1}, "x.rtf");
        } catch (java.io.IOException error) {
            rejected = error.getMessage().indexOf("不支持") >= 0;
        }
        check(rejected, "textOf throws a readable IOException for unsupported types");

        TextCorpus corpus = new TextCorpus();
        library.index(corpus);
        check(corpus.sentenceCount() >= 3, "index() pushed library text into the corpus");
        int notesSentences = 0;
        int docxSentences = 0;
        for (int i = 0; i < library.entries().size(); i++) {
            LocalLibrary.Entry entry = library.entries().get(i);
            if (entry.name.equals("notes.txt")) notesSentences = entry.sentences;
            if (entry.name.equals("fixture.docx")) docxSentences = entry.sentences;
        }
        check(notesSentences >= 1, "index() records the sentence count per entry");
        check(docxSentences >= 1, "index() indexes the long line extracted from the .docx");
        TextCorpus.Report report = corpus.match("下面这段抄自自建库：" + other, null);
        check(!report.hits.isEmpty(), "match() finds a sentence that only exists in the library");
        check(!report.hits.isEmpty() && "local".equals(report.hits.get(0).source.engine)
                && "survey".equals(report.hits.get(0).source.title), "library hits carry the local engine and file title");
        check(new File(directory, "index.json").isFile(), "index.json is persisted next to the documents");
        String persisted = new String(readAll(new File(directory, "index.json")), "UTF-8");
        check(persisted.indexOf("\"version\"") >= 0 && persisted.indexOf("notes.txt") >= 0,
                "index.json holds the version and the entry names");
        Object parsed = ApiJson.parse(persisted);
        check(ApiJson.path(parsed, "$.entries[0].name") instanceof String, "index.json parses back through ApiJson");

        LocalLibrary reopened = new LocalLibrary(directory);
        check(reopened.size() == 4, "a reopened library reads the same four entries from index.json");
        check(reopened.remove("notes.txt"), "remove deletes a known entry");
        check(reopened.size() == 3 && !new File(directory, "notes.txt").exists(),
                "the removed file disappears from disk and from the listing");
        check(!reopened.remove("notes.txt"), "removing an unknown name reports false");
        java.io.FileOutputStream broken = new java.io.FileOutputStream(new File(directory, "index.json"));
        broken.write("{ this is not json".getBytes("UTF-8"));
        broken.close();
        LocalLibrary rebuilt = new LocalLibrary(directory);
        check(rebuilt.size() == 3, "a corrupt index.json is rebuilt from the directory contents");
        rebuilt.index(new TextCorpus());
        check(new File(directory, "index.json").isFile()
                && ApiJson.parse(new String(readAll(new File(directory, "index.json")), "UTF-8")) != null,
                "the rebuilt index.json is valid JSON again");
        rebuilt.clear();
        check(rebuilt.size() == 0, "clear empties the entry list");
        check(new File(directory, "notes.txt").exists() == false && new File(directory, "survey.md").exists() == false,
                "clear removes the stored documents");
        deleteRecursively(directory);
    }

    /** 参考文献表/致谢/附录/目录不参与比对：它们重复了也不是抄袭，还会长大分母。 */
    private static void structure() {
        check(TextCorpus.structureHeading("参考文献"), "structureHeading 认得裸标题「参考文献」");
        check(TextCorpus.structureHeading("参考文献（References）:"), "structureHeading 放过带括注和冒号的标题");
        check(TextCorpus.structureHeading("六、致谢"), "structureHeading 认得带编号的「致谢」");
        check(TextCorpus.structureHeading("附录A：调查问卷"), "structureHeading 认得带小题的「附录A」");
        check(TextCorpus.structureHeading("Acknowledgements"), "structureHeading 认得英文致谢");
        check(TextCorpus.structureHeading("目 录"), "structureHeading 认得中间空格的「目 录」");
        check(!TextCorpus.structureHeading("参考文献的编排要遵循国标。"),
                "structureHeading 不把提到参考文献的正文句当标题");
        check(!TextCorpus.structureHeading("本章小结"), "structureHeading 不把普通章节名当结构标题");
        StringBuilder longLine = new StringBuilder("附录");
        for (int i = 0; i < 45; i++) longLine.append('文');
        check(!TextCorpus.structureHeading(longLine.toString()), "structureHeading 拒绝长得像正文的行");
        check(TextCorpus.bibliographyHeading("参考文献及注释"), "bibliographyHeading 认得「参考文献及注释」");
        check(!TextCorpus.bibliographyHeading("致谢"), "bibliographyHeading 不把致谢算作参考文献表");

        String bodyCopy = "深度学习模型的训练过程需要大量标注数据，否则模型很难收敛到稳定状态。";
        String tailCopy = "滑动窗口的宽度取十六个字符时误报最少，再窄一些就会漏掉被改写过的句子。";
        String entry = "[3] Smith J, Li W. Near-duplicate detection at scale[J]. ACM Computing Surveys, 2019, 52(4): 1-38.";
        String text = "本文先在采集端做归一化，再用滑动窗口统计字符三元组，四个数据集上各重复五折。\n"
                + bodyCopy + "\n"
                + "参考文献\n"
                + "[1] 张三. 面向长文本的查重方法[J]. 计算机学报, 2020, 43(3): 512-524.\n"
                + "[2] 李四, 王五. 中文文本相似度计算综述[J]. 软件学报, 2021, 32(8): 2456-2470.\n"
                + entry + "\n"
                + "[4] 赵六. 学位论文查重系统的实现[D]. 哈尔滨: 哈尔滨工业大学, 2018.\n"
                + "致谢\n"
                + "感谢导师三年来的悉心指导，也感谢实验室同学在数据采集阶段提供的帮助，没有他们这篇论文不可能完成。\n"
                + "第六章 实验设计\n"
                + tailCopy;
        int refsAt = text.indexOf("参考文献");
        int tailAt = text.indexOf(tailCopy);

        TextCorpus.Structure found = TextCorpus.structure(text);
        check(!found.isEmpty(), "structure 找得到参考文献表");
        check(found.bibliographySections == 1, "structure 只把参考文献那一节记成文献表");
        check(found.citationLines == 4, "structure 数出四条参考文献条目");
        check(found.otherSections == 1, "structure 另外认出致谢一节");
        check(found.excludedChars > 0, "structure 统计出被排除的字数");
        check(found.spans.get(0)[0] == refsAt, "排除区间从「参考文献」标题起算");
        check(found.spans.get(0)[1] > text.indexOf("致谢") && found.spans.get(0)[1] <= tailAt,
                "致谢整节被排除，但下一章节没有陪着进去");

        TextCorpus corpus = new TextCorpus();
        TextCorpus.Source source = new TextCorpus.Source();
        source.title = "查重方法研究";
        corpus.add(source, bodyCopy + tailCopy
                + "Smith J, Li W. Near-duplicate detection at scale[J]. ACM Computing Surveys, 2019, 52(4): 1-38.");
        int[] excluded = found.spanArray();
        TextCorpus.Report loose = corpus.match(text, null);
        TextCorpus.Report strict = corpus.match(text, null, excluded);
        boolean referenceHitReported = false;
        for (int i = 0; i < loose.hits.size(); i++)
            if (loose.hits.get(i).start >= refsAt) referenceHitReported = true;
        check(referenceHitReported, "不排除时，参考文献条目会被当成抄袭命中");
        boolean insideExcluded = false;
        for (int i = 0; i < strict.hits.size(); i++)
            if (strict.hits.get(i).start >= refsAt && strict.hits.get(i).start < tailAt) insideExcluded = true;
        check(!insideExcluded, "排除后参考文献里不再产生命中");
        boolean bodyKept = false, tailKept = false;
        for (int i = 0; i < strict.hits.size(); i++) {
            int start = strict.hits.get(i).start;
            if (start == text.indexOf(bodyCopy)) bodyKept = true;
            if (start == tailAt) tailKept = true;
        }
        check(bodyKept, "正文里的真实重复照样命中");
        check(tailKept, "致谢之后的正文仍然参与比对");
        check(strict.comparedChars < loose.comparedChars, "排除后分母变小");
        check(strict.excludedChars > 0 && strict.duplicateChars < loose.duplicateChars,
                "排除的字数进了报告，重复字数随之下降");

        // 0.7.3：分母与排除字数是同一次划分的两边，加起来必须正好是整篇；再与 CharLedger 对一遍三个数。
        int wholeValid = TextCorpus.validCount(TextCorpus.normalize(text), 0, text.length());
        check(strict.comparedChars + strict.excludedChars == wholeValid,
                "分母 " + strict.comparedChars + " + 排除 " + strict.excludedChars + " == 整篇 " + wholeValid
                        + " 个有效字符：同一次划分的两边，加起来不多也不少");
        CharLedger.Balance structureBalance = CharLedger.close(text, excluded, null, strict.hits, null);
        check(structureBalance.totalChars == strict.comparedChars
                        && structureBalance.duplicateChars == strict.duplicateChars
                        && structureBalance.excludedChars == strict.excludedChars,
                "带排除区也同一把尺：报告 分母/分子/排除 = " + strict.comparedChars + "/" + strict.duplicateChars
                        + "/" + strict.excludedChars + " 对账本 " + structureBalance.totalChars + "/"
                        + structureBalance.duplicateChars + "/" + structureBalance.excludedChars);
        check(overlapPairs(loose.hits) == 0 && overlapPairs(strict.hits) == 0,
                "结构性排除前后的命中都两两不重叠");
        String lone = "参考文献\n[1] 张三. 面向长文本的查重方法[J]. 计算机学报, 2020, 43(3): 512-524.\n"
                + "这一节之后的内容明显是正文，它讲的是实验设置与随机种子的取法，跟文献无关。";
        check(TextCorpus.structure(lone).isEmpty(), "只有一条条目时不敢把整节当成文献表");

        String headless = "正文段落先把方法和数据交代清楚，再给出可以复现的步骤。\n"
                + "[1] 王五. 中文文本相似度计算综述[J]. 软件学报, 2021, 32(8): 2456-2470.\n"
                + "[2] 赵六. 学位论文查重系统的实现[D]. 哈尔滨: 哈尔滨工业大学, 2018.\n"
                + "[3] 孙七. 长文本指纹比对[D]. 北京: 清华大学, 2019.";
        TextCorpus.Structure bare = TextCorpus.structure(headless);
        check(!bare.isEmpty() && bare.bibliographySections == 1, "没有标题的条目串也认作文献表");

        TextCorpus.Structure toc = TextCorpus.structure("目录\n1 引言 ......... 1\n2 方法 ......... 5\n"
                + "3 实验 ......... 12\n第一章 引言\n引言从研究背景讲起，先说明为什么要做这件事。\n");
        check(!toc.isEmpty() && toc.otherSections == 1, "目录被排除，正文从第一章继续");
    }


    private static byte[] readAll(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        in.close();
        return out.toByteArray();
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (int i = 0; i < children.length; i++) deleteRecursively(children[i]);
        }
        file.delete();
    }
}
