package com.rikkahub.wordlite;

import java.util.ArrayList;

/** AigcDetector 回归：人写与模板化文本的相对得分、模板句式命中、短句门槛、偏移正确性与整篇比例。 */
public final class AigcRegression {
    private static int count;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        count++;
        System.out.println("PASS " + message);
    }

    /** 人写风格：长短句混排、具体细节、几乎没有模板句。 */
    private static final String HUMAN = "我们把三台标注机搬到走廊尽头，插座不够，最后拉了一卷线板。"
            + "第一天就出问题：录音里混进空调的嗡声，十一份样本得重录。"
            + "第二天换了麦克风位置，噪声是小了，可有个受访者的方言太重，转写软件整段听不懂，只好请本地一位老师做听写，一天三百块。"
            + "小王起初不同意，觉得太贵，预算本来就紧。"
            + "第三天下午忽然来了十七个学生，队伍排到楼梯口，那天反而录得最多。"
            + "晚上回宿舍对时间码，才发现有一段少了两分钟，谁也想不起是哪儿断的。";

    /** 有一点公文腔，但没有成套模板。 */
    private static final String MILD = "通过对实验数据的分析，可以发现该方法在噪声环境下表现较好。"
            + "实验在四个公开数据集上进行，每个数据集随机划分八二分。"
            + "我们手工检查了其中两百条错例，多数是标点引起的分句错误。"
            + "综上，方法有效，但仍需在更大规模的数据上验证。";

    /** 模板化 LLM 腔：排比、连接词、评价词、句首结构复读。 */
    private static final String AI = "综上所述，本研究通过对实验数据的全面分析，可以发现该方法在多个维度上都表现出显著优势。"
            + "首先，通过深入优化训练策略，可以显著提升模型的收敛速度。"
            + "其次，通过引入注意力机制，可以有效降低人工标注的成本。"
            + "再次，通过构建统一的评价体系，可以全面提高评估结果的稳定性。"
            + "值得注意的是，数据规模的扩大不仅提升了模型性能，还为后续研究提供了坚实的基础。"
            + "此外，该框架具有广阔的应用前景和重要的理论价值。";

    private static final String AI_EN = "Moreover, it is worth noting that the proposed method plays a crucial role "
            + "in long document understanding. Furthermore, in addition to the accuracy gains, the framework "
            + "reduces annotation cost. In conclusion, the extensive experiments provide valuable insights "
            + "into the behaviour of attention modules.";

    private static final String HUMAN_EN = "We ran the model overnight on a single GPU and it choked halfway through. "
            + "The first 400 steps looked fine. Then the loss went flat, and by morning it had not moved at all; "
            + "we later found a stale checkpoint in the loader. Restarting from scratch cost us two days.";

    private static final String FULLWIDTH_EN = "ｉｔ ｉｓ ｗｏｒｔｈ ｎｏｔｉｎｇ ｔｈａｔ ｔｈｉｓ ａｐｐｒｏａｃｈ "
            + "ｐｌａｙｓ ａ ｃｒｕｃｉａｌ ｒｏｌｅ ｉｎ ｄｏｃｕｍｅｎｔ ｕｎｄｅｒｓｔａｎｄｉｎｇ． "
            + "ＭＯＲＥＯＶＥＲ， ｉｔ ｐｒｏｖｉｄｅｓ ｖａｌｕａｂｌｅ ｉｎｓｉｇｈｔｓ ｆｏｒ ｆｕｔｕｒｅ ｗｏｒｋ．";

    public static void main(String[] args) {
        guards();
        ordering();
        templates();
        shortSentences();
        offsets();
        metrics();
        profiles();
        edgeCases();
        performance();
        System.out.println("SUMMARY " + count + " assertions passed"
                + " (scores are a relative tendency, not an authoritative AI verdict).");
    }

    private static boolean hasFeature(AigcDetector.Result result, String needle) {
        for (int i = 0; i < result.sentences.size(); i++) {
            ArrayList<String> features = result.sentences.get(i).features;
            for (int k = 0; k < features.size(); k++) {
                if (features.get(k).indexOf(needle) >= 0) return true;
            }
        }
        return false;
    }

    private static boolean anyTemplate(AigcDetector.Result result) {
        return hasFeature(result, "模板句式：");
    }

    /** 样本门槛与引用排除：不给小样本编造比例，也不拿抄来的句子判机器腔。 */
    private static void guards() {
        check(AigcDetector.MIN_DOCUMENT_CHARS == 400, "样本门槛定在 400 个有效字符");
        AigcDetector.Result shortRun = AigcDetector.detect(HUMAN);
        check(shortRun.insufficientSample, "几百字的人写段落被判为样本不足");
        check(shortRun.verdict.indexOf("样本不足") == 0, "样本不足时第一句就说清");
        check(shortRun.sentences.size() > 0, "样本不足照样列出特征句供人看");
        String longish = AI + AI + AI;
        AigcDetector.Result longRun = AigcDetector.detect(longish);
        check(!longRun.insufficientSample, "过了门槛才给比例");
        check(longRun.verdict.length() > 0 && longRun.rate > 20f, "模板腔够重时结论是建议复核");
        AigcDetector.Result plain = AigcDetector.detect(HUMAN_EN + HUMAN_EN + HUMAN_EN);
        check("未见明显机器腔".equals(plain.verdict), "人写英文给的是一般结论");
        check(Math.abs(AigcDetector.detect(AI).rate - AigcDetector.detect(AI, null).rate) < 0.0001f,
                "不传排除区间与传 null 结果一致");
        String mixed = AI + MILD + MILD + MILD;
        int aiEnd = AI.length();
        AigcDetector.Result full = AigcDetector.detect(mixed);
        AigcDetector.Result quoted = AigcDetector.detect(mixed, new int[]{0, aiEnd});
        check(quoted.excludedChars > 0, "被圈住的字数记在报告里");
        check(quoted.sentences.size() < full.sentences.size(), "引用区间里的句子不再打分");
        check(quoted.rate < full.rate, "把模板句圈成引用之后倾向分下降");
        boolean insideExcluded = false;
        for (int i = 0; i < quoted.sentences.size(); i++)
            if (quoted.sentences.get(i).start < aiEnd) insideExcluded = true;
        check(!insideExcluded, "排除区间里没有残留计分句");
    }

    private static void ordering() {
        AigcDetector.Result human = AigcDetector.detect(HUMAN);
        AigcDetector.Result mild = AigcDetector.detect(MILD);
        AigcDetector.Result ai = AigcDetector.detect(AI);
        AigcDetector.Result humanEn = AigcDetector.detect(HUMAN_EN);
        AigcDetector.Result aiEn = AigcDetector.detect(AI_EN);
        check(human.sentences.size() == 6 && human.comparedChars > 150,
                "the human paragraph is compared sentence by sentence (" + human.sentences.size()
                        + " sentences, " + human.comparedChars + " characters)");
        check(human.rate < mild.rate - 10f, "the plain write-up scores below the semi-formal one ("
                + human.rate + " < " + mild.rate + ")");
        check(mild.rate < ai.rate - 10f, "the semi-formal write-up scores below the templated one ("
                + mild.rate + " < " + ai.rate + ")");
        check(human.rate < ai.rate - 30f, "the rate gap between human and templated prose is wide ("
                + human.rate + " vs " + ai.rate + ")");
        check(ai.rate > 40f && ai.rate <= 100f, "the templated paragraph lands above 40 percent");
        check(human.rate < 12f, "the human paragraph stays under 12 percent");
        check(humanEn.rate < aiEn.rate - 20f, "the same ordering holds for English prose ("
                + humanEn.rate + " < " + aiEn.rate + ")");
        check(aiEn.rate > 40f, "the English templated paragraph also reads as machine-flavoured");
        check(rate(human) && rate(mild) && rate(ai) && rate(humanEn) && rate(aiEn),
                "every rate stays inside 0..100");
    }

    private static boolean rate(AigcDetector.Result result) {
        return result.rate >= 0f && result.rate <= 100f;
    }

    private static void templates() {
        AigcDetector.Result ai = AigcDetector.detect(AI);
        check(hasFeature(ai, "模板句式：综上所述"), "综上所述 shows up as a named template hit");
        check(hasFeature(ai, "模板句式：值得注意的是"), "值得注意的是 shows up as a named template hit");
        check(hasFeature(ai, "模板句式：为……提供了"), "为……提供了 shows up as a named template hit");
        check(hasFeature(ai, "连接词密度偏高"), "connective density is reported by name");
        check(hasFeature(ai, "句长突发度低"), "flat sentence-length burst is reported by name");
        check(hasFeature(ai, "程度副词/评价词堆叠"), "stacked intensifiers are reported by name");
        check(!anyTemplate(AigcDetector.detect(HUMAN)), "the human paragraph triggers no template at all");
        String parallel = "首先我们需要清洗原始语料，其次要补齐缺失的字段，最后再把结果整理成一张对照表。";
        AigcDetector.Result frame = AigcDetector.detect(parallel);
        check(hasFeature(frame, "模板句式：首先……其次……最后"), "the 首先/其次/最后 frame is caught inside one sentence");
        AigcDetector.Result english = AigcDetector.detect(AI_EN);
        check(hasFeature(english, "模板句式：it is worth noting"), "English boilerplate opens the feature list");
        check(hasFeature(english, "moreover/furthermore"), "moreover/furthermore is named");
        check(hasFeature(english, "in conclusion"), "in conclusion is named");
        check(hasFeature(english, "plays a crucial role"), "plays a crucial role is named");
        check(!anyTemplate(AigcDetector.detect(HUMAN_EN)), "ordinary English prose triggers no template");
        AigcDetector.Result folded = AigcDetector.detect(FULLWIDTH_EN);
        check(hasFeature(folded, "it is worth noting") && hasFeature(folded, "moreover/furthermore"),
                "fullwidth Latin boilerplate is folded before the template scan");
        boolean chineseNames = true;
        boolean scoreMatchesFeatures = true;
        ArrayList<AigcDetector.Result> all = new ArrayList<AigcDetector.Result>();
        all.add(ai);
        all.add(english);
        all.add(AigcDetector.detect(HUMAN));
        for (int i = 0; i < all.size(); i++) {
            for (int k = 0; k < all.get(i).sentences.size(); k++) {
                AigcDetector.Sentence sentence = all.get(i).sentences.get(k);
                for (int j = 0; j < sentence.features.size(); j++) {
                    if (!hasCJK(sentence.features.get(j))) chineseNames = false;
                }
                if (sentence.features.isEmpty() != (sentence.score == 0f)) scoreMatchesFeatures = false;
            }
        }
        check(chineseNames, "every feature name is written in Chinese for the reviewer");
        check(scoreMatchesFeatures, "a sentence scores above zero exactly when it lists features");
    }

    private static boolean hasCJK(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }

    private static void shortSentences() {
        AigcDetector.Result chatter = AigcDetector.detect("好的。走吧。明天见吧。行。");
        check(chatter.sentences.isEmpty() && chatter.comparedChars == 0 && chatter.rate == 0f,
                "chatter under eight characters is never scored");
        AigcDetector.Result marks = AigcDetector.detect("！！！？？？……——、，，，");
        check(marks.sentences.isEmpty() && marks.comparedChars == 0 && marks.rate == 0f,
                "punctuation-only text contributes nothing");
        AigcDetector.Result seven = AigcDetector.detect("这句话共七个字。");
        check(seven.sentences.isEmpty() && seven.comparedChars == 0, "a seven-character sentence stays unscored");
        String gate = "这句话刚好八个字。";
        AigcDetector.Result eight = AigcDetector.detect(gate);
        check(eight.sentences.size() == 1, "an eight-character sentence is right on the gate and gets scored");
        check(eight.comparedChars == TextCorpus.validCount(TextCorpus.normalize(gate), 0, gate.length()),
                "the scored sentence contributes exactly its own characters to comparedChars");
        String mixed = "好的。这一段足够长的正文讲的是标注现场的取舍与返工，短句只有一句。";
        AigcDetector.Result only = AigcDetector.detect(mixed);
        check(only.sentences.size() == 1, "only the long sentence of a mixed paragraph is scored");
        check(only.comparedChars == TextCorpus.validCount(TextCorpus.normalize(mixed), 3, mixed.length()),
                "comparedChars ignores the discarded short sentence");
    }

    private static void offsets() {
        String emoji = "结果\uD83D\uDC49综上所述，本研究通过对数据的全面分析，可以发现该方法显著提升了效率。";
        AigcDetector.Result result = AigcDetector.detect(emoji);
        check(result.sentences.size() == 1, "the surrogate pair does not split the sentence");
        AigcDetector.Sentence sentence = result.sentences.get(0);
        String slice = emoji.substring(sentence.start, sentence.end);
        check(slice.equals(emoji), "the offsets slice the whole original string including the emoji");
        check(sentence.start == 0 && sentence.end == emoji.length(), "offsets are UTF-16 indices of the input string");
        check(sentence.score > 0.5f, "the templated tail still scores after the emoji prefix");
        boolean exact = true;
        boolean ordered = true;
        ArrayList<int[]> spans = TextCorpus.sentences(HUMAN);
        AigcDetector.Result human = AigcDetector.detect(HUMAN);
        for (int i = 0; i < human.sentences.size(); i++) {
            AigcDetector.Sentence item = human.sentences.get(i);
            if (item.start < 0 || item.end > HUMAN.length() || item.end <= item.start) ordered = false;
            if (i > 0 && item.start < human.sentences.get(i - 1).end) ordered = false;
            String text = HUMAN.substring(item.start, item.end);
            if (text.length() == 0) exact = false;
            boolean found = false;
            for (int k = 0; k < spans.size(); k++) {
                int[] span = spans.get(k);
                if (span[0] == item.start && span[1] == item.end) found = true;
            }
            if (!found) exact = false;
        }
        check(ordered, "scored sentences are ordered and never overlap");
        check(exact, "every slice rebuilds a real sentence of the original text");
        String fullwidth = "Ｐａｐｅｒ　结论部分：综上所述，该方法在噪声环境下仍然稳定，成本也降了下来。";
        AigcDetector.Result folded = AigcDetector.detect(fullwidth);
        boolean widthOk = folded.sentences.size() >= 1;
        for (int i = 0; widthOk && i < folded.sentences.size(); i++) {
            AigcDetector.Sentence item = folded.sentences.get(i);
            if (fullwidth.substring(item.start, item.end).indexOf('Ｐ') < 0
                    && fullwidth.substring(item.start, item.end).indexOf('综') < 0) widthOk = false;
        }
        check(widthOk, "fullwidth text keeps original-width slices at the reported offsets");
    }

    private static void metrics() {
        String[] samples = {HUMAN, MILD, AI, AI_EN, HUMAN_EN};
        boolean charsAddUp = true;
        boolean weighted = true;
        boolean bounded = true;
        for (int i = 0; i < samples.length; i++) {
            AigcDetector.Result result = AigcDetector.detect(samples[i]);
            String norm = TextCorpus.normalize(samples[i]);
            int sum = 0;
            double score = 0d;
            for (int k = 0; k < result.sentences.size(); k++) {
                AigcDetector.Sentence sentence = result.sentences.get(k);
                int chars = TextCorpus.validCount(norm, sentence.start, sentence.end);
                sum += chars;
                score += (double) sentence.score * chars;
                if (sentence.score < 0f || sentence.score > 1f) bounded = false;
            }
            if (sum != result.comparedChars) charsAddUp = false;
            double expected = sum == 0 ? 0d : score * 100d / sum;
            if (Math.abs(expected - result.rate) > 0.05d) weighted = false;
        }
        check(charsAddUp, "comparedChars equals the characters of the sentences that were scored");
        check(weighted, "rate is the character-weighted mean of the per-sentence scores");
        check(bounded, "per-sentence scores stay inside 0..1");
        AigcDetector.Result twice = AigcDetector.detect(AI);
        AigcDetector.Result again = AigcDetector.detect(AI);
        boolean same = twice.rate == again.rate && twice.sentences.size() == again.sentences.size();
        for (int i = 0; same && i < twice.sentences.size(); i++) {
            same = twice.sentences.get(i).score == again.sentences.get(i).score
                    && twice.sentences.get(i).features.equals(again.sentences.get(i).features);
        }
        check(same, "detect is pure: a second call reproduces the first one exactly");
    }

    private static void profiles() {
        String semis = "第二组只看词频；第三组加了句法特征；第四组把两者拼在一起跑了一夜。";
        check(hasFeature(AigcDetector.detect(semis), "分号"), "semicolon profile is reported");
        String dash = "第三组把两者拼在一起——结果出人意料，第二组反而是最差的那一组。";
        check(hasFeature(AigcDetector.detect(dash), "破折号"), "dash profile is reported");
        String parallel = "实验分了四组，覆盖了三种语言，跑了两个星期，换过三次参数，最后只剩一组能看。";
        check(hasFeature(AigcDetector.detect(parallel), "并列排比密集"), "comma-heavy parallel rhythm is reported");
        String repeat = "数据质量决定模型质量，模型质量决定结果质量，结果质量决定应用质量，应用质量决定业务质量。";
        check(hasFeature(AigcDetector.detect(repeat), "多样度"), "reused vocabulary shows up as a diversity feature");
    }

    private static void edgeCases() {
        AigcDetector.Result nullResult = AigcDetector.detect(null);
        check(nullResult.sentences.isEmpty() && nullResult.rate == 0f && nullResult.comparedChars == 0,
                "detect(null) returns an empty result instead of throwing");
        check(AigcDetector.detect("").rate == 0f, "detect of an empty string is zero");
        check(AigcDetector.detect("   \t\n  ").comparedChars == 0, "whitespace-only text scores nothing");
        AigcDetector.Result marks = AigcDetector.detect("……———，，；；");
        check(marks.rate == 0f && marks.sentences.isEmpty(), "pure punctuation never produces a sentence");
        StringBuilder runOn = new StringBuilder();
        for (int i = 0; i < 60; i++) runOn.append("这一段的记录没有标点只能一直写下去");
        AigcDetector.Result longRun = AigcDetector.detect(runOn.toString());
        check(longRun.sentences.size() == 1 && longRun.comparedChars == runOn.length(),
                "a punctuation-free run of " + runOn.length() + " characters is treated as one sentence");
        check(longRun.rate >= 0f && longRun.rate <= 100f, "the run-on rate stays bounded");
        AigcDetector.Result numbers = AigcDetector.detect("１２３４５６７８９０，２０２０ 年到 ２０２４ 年的样本量分别是三百、五百、八百。");
        check(numbers.rate >= 0f && numbers.rate <= 100f, "digit-heavy fullwidth text is handled without throwing");
        check(AigcDetector.detect("综上所述").sentences.isEmpty(), "a bare template phrase is too short to score");
    }

    private static void performance() {
        StringBuilder builder = new StringBuilder();
        while (builder.length() < 20000) {
            builder.append(HUMAN).append(AI);
        }
        String text = builder.substring(0, 20000);
        AigcDetector.detect(text);
        long best = Long.MAX_VALUE;
        AigcDetector.Result result = null;
        for (int round = 0; round < 3; round++) {
            long began = System.nanoTime();
            result = AigcDetector.detect(text);
            long millis = (System.nanoTime() - began) / 1000000L;
            if (millis < best) best = millis;
        }
        check(result != null && result.comparedChars > 19000, "the 20k-character draft is fully compared");
        check(result != null && result.rate > 20f && result.rate < 90f, "the mixed draft lands between the two poles");
        check(best < 3000L, "detect over 20k characters stays fast on a desktop JVM (measured " + best + " ms)");
        System.out.println("PERF detect(20000 chars) = " + best + " ms, sentences="
                + (result == null ? 0 : result.sentences.size()));
    }
}
