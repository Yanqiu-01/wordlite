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

    public static void main(String[] args) throws Exception {
        normalization();
        splitting();
        similarity();
        matching();
        merging();
        rates();
        citations();
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
        check(report.comparedChars == compared, "comparedChars equals the valid characters of every counted sentence");
        check(report.duplicateChars == duplicated, "duplicateChars equals the valid characters inside the merged hits");
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
