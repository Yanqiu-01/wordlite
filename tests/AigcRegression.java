package com.rikkahub.wordlite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;

/**
 * AigcDetector 回归：系数生效、每条特征能单独算、人写与模板化文本的相对得分、区间聚合的恒等式、
 * 置信档位的双门槛、样本不足口径、短句门槛与偏移正确性。
 *
 * 0.7.0 改系数时按"序留住、值跟着实测重算、断言文案里带实测数"的规矩动过 9 条数值断言，
 * 每条旁边都注明了原值、新值与为什么安全；实测数出自 tools/aigc-calibration.ps1
 * （产物 artifacts/corpus/aigc-sweep.txt，读数见 docs/aigc-calibration.md）。
 */
public final class AigcRegression {
    private static int count;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        count++;
        System.out.println("PASS " + message);
    }

    /** 人写风格：长短句混排、具体细节、几乎没有模板句。实测 rate 4.35（0.5.4 是 5.5 上下）。 */
    private static final String HUMAN = "我们把三台标注机搬到走廊尽头，插座不够，最后拉了一卷线板。"
            + "第一天就出问题：录音里混进空调的嗡声，十一份样本得重录。"
            + "第二天换了麦克风位置，噪声是小了，可有个受访者的方言太重，转写软件整段听不懂，只好请本地一位老师做听写，一天三百块。"
            + "小王起初不同意，觉得太贵，预算本来就紧。"
            + "第三天下午忽然来了十七个学生，队伍排到楼梯口，那天反而录得最多。"
            + "晚上回宿舍对时间码，才发现有一段少了两分钟，谁也想不起是哪儿断的。";

    /** 有一点公文腔，但没有成套模板。实测 rate 13.51。 */
    private static final String MILD = "通过对实验数据的分析，可以发现该方法在噪声环境下表现较好。"
            + "实验在四个公开数据集上进行，每个数据集随机划分八二分。"
            + "我们手工检查了其中两百条错例，多数是标点引起的分句错误。"
            + "综上，方法有效，但仍需在更大规模的数据上验证。";

    /** 模板化 LLM 腔：排比、连接词、评价词、句首结构复读。实测 rate 27.64（组内取最强之后比 0.5.4 低一截）。 */
    private static final String AI = "综上所述，本研究通过对实验数据的全面分析，可以发现该方法在多个维度上都表现出显著优势。"
            + "首先，通过深入优化训练策略，可以显著提升模型的收敛速度。"
            + "其次，通过引入注意力机制，可以有效降低人工标注的成本。"
            + "再次，通过构建统一的评价体系，可以全面提高评估结果的稳定性。"
            + "值得注意的是，数据规模的扩大不仅提升了模型性能，还为后续研究提供了坚实的基础。"
            + "此外，该框架具有广阔的应用前景和重要的理论价值。";

    /** AI 的第一句，多处断言拿它当尺子，提成常量免得抄两遍。 */
    private static final String AI_SENTENCE_1 = "综上所述，本研究通过对实验数据的全面分析，可以发现该方法在多个维度上都表现出显著优势。";

    /** 满档机器腔：每句四条套话 + 排比 + 评价词，用来把档位推到复核档以上。实测单句 0.59 上下。 */
    private static final String HEAVY = "综上所述，通过上述分析可以发现，这套流程的稳定性还有提升空间，为后续优化提供了重要的参考，也具有显著的现实意义和可操作的改进方向。"
            + "首先，通过统一的评价体系，可以全面提高结果的可靠性，不仅降低了人工成本，而且为同类项目提供了有益的借鉴和可复用的模板。"
            + "其次，通过持续优化交付流程，可以有效压缩交付周期，为团队积累了扎实的经验，也为下一期奠定了良好的基础。"
            + "值得注意的是，随着技术的不断演进，该方案不仅具有突出的理论价值，还为推广应用提供了广阔的空间和充分的落地条件。";

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

    /**
     * 真实论文正文里"最像机写的真人句子"（钉子户清单，实测当前系数下最高 0.366，门槛 0.45，余量 +0.084）。
     * 同一批句子在 tests/corpus/aigc-hard-human.txt 里，标定台的 NAIL 行读那份文件；这里留一份是为了
     * 让本套件不依赖工作目录也能跑。
     */
    private static final String[] HARD_HUMAN = {
        "通过调控多孔铜的孔隙率、孔径、孔壁厚度和孔道连通性，建立多孔铜结构参数与其导电、导热、力学及润湿性能之间的关系，为多孔铜中间层的可控制备提供基础。",
        "该研究说明，多孔Cu参与TLP连接时，孔道填充、界面反应和压力控制相互关联，为后续设计多孔Cu中间层及确定连接参数提供了参考。",
        "因此，如何在缩短连接时间的同时，控制金属间化合物的生成，并改善接头的应力承载能力，成为瞬态液相连接技术进一步应用需要解决的问题。",
    };

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
        coefficients();
        features();
        segments();
        tiers();
        hardHuman();
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

    /** 一句话切成打分单元。 */
    private static AigcFeatures.Segment segment(String text) {
        String norm = TextCorpus.normalize(text);
        ArrayList<int[]> spans = TextCorpus.sentences(text);
        if (spans.isEmpty()) return AigcFeatures.segment(norm, 0, norm.length());
        int[] span = spans.get(0);
        return AigcFeatures.segment(norm, span[0], span[1]);
    }

    /** 一段话的文档级统计（切句 + 过字数门槛 + 句长变异系数与句首众数）。 */
    private static AigcFeatures.DocStats statsOf(String text) {
        String norm = TextCorpus.normalize(text);
        ArrayList<int[]> spans = TextCorpus.sentences(text);
        ArrayList<AigcFeatures.Segment> all = AigcFeatures.segments(norm, spans);
        ArrayList<AigcFeatures.Segment> scored = new ArrayList<AigcFeatures.Segment>();
        for (int i = 0; i < all.size(); i++) if (AigcFeatures.scores(all.get(i))) scored.add(all.get(i));
        return AigcFeatures.stats(norm, spans, scored);
    }

    /** 区间字数应当等于成员句有效字符之和。 */
    private static int expectedChars(AigcDetector.Result run, AigcDetector.Segment seg, String norm) {
        int chars = 0;
        for (int i = seg.fromSentence; i <= seg.toSentence; i++) {
            AigcDetector.Sentence member = run.sentences.get(i);
            chars += TextCorpus.validCount(norm, member.start, member.end);
        }
        return chars;
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
        // 0.7.0 改写：原断言 `longRun.rate > 20f`。组内取最强之后 AI×3 实测 27.64，仍然过 20，原门槛原样留住。
        check(longRun.verdict.length() > 0 && longRun.rate > 20f,
                "模板腔够重时结论是建议复核（实测 " + longRun.rate + " > 20）");
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
        // 0.7.0 改写：原 `human.rate < mild.rate - 10f`。间距常数 10 → 5：组内取最强把公文腔里重复的
        // 节奏证据压成一条，人写 4.35 对半公文 13.51 的差距（9.16）比 5 宽，但已经不到 10。
        check(human.rate < mild.rate - 5f, "the plain write-up scores below the semi-formal one ("
                + human.rate + " < " + mild.rate + " - 5)");
        // 0.7.0 改写：原 `mild.rate < ai.rate - 10f` → 同一把尺 5；实测 13.51 对 27.64，差 14.1。
        check(mild.rate < ai.rate - 5f, "the semi-formal write-up scores below the templated one ("
                + mild.rate + " < " + ai.rate + " - 5)");
        // 0.7.0 改写：原 `human.rate < ai.rate - 30f` → 20。组内 max-pooling 之后漫画腔从 55 掉到 27.6，
        // 与真人侧的差距是 23.3 个百分点；留 20 而不是 30，是为了让这条断言守着"序"而不是守着旧数值。
        check(human.rate < ai.rate - 20f, "the rate gap between human and templated prose is wide ("
                + human.rate + " vs " + ai.rate + ", gap 23.3)");
        // 0.7.0 改写：原 `ai.rate > 40f` → 25。40 是逐特征相加时代的数，组内取最强之后模板腔整篇 27.6；
        // 句子那头的证据更有意义：最高那句 0.532 仍然稳稳过 0.45 的可疑区间门槛。
        check(ai.rate > 25f && ai.rate <= 100f, "the templated paragraph lands above 25 percent ("
                + ai.rate + ")");
        check(topScore(ai) >= AigcDetector.SEGMENT_FLAG_GATE, "模板腔里最高那句仍然过可疑区间门槛（"
                + topScore(ai) + " >= " + AigcDetector.SEGMENT_FLAG_GATE + "）");
        // 0.7.0 改写：原 `human.rate < 12f` → 8。真人语料重排后均值 0.077（0.5.4 是 0.119），8 更严也更真。
        check(human.rate < 8f, "the human paragraph stays under 8 percent (" + human.rate + ")");
        // 拉丁族系数是从中文族平移的（没有英文正文可标定），这条只保证序，不保证值。
        check(humanEn.rate < aiEn.rate - 20f, "the same ordering holds for English prose ("
                + humanEn.rate + " < " + aiEn.rate + "); 英文族系数未经标定，这条只保证序)");
        // 0.7.0 改写：原 `aiEn.rate > 40f` → 25，与 ai.rate 同源：实测英文模板腔 31.86。
        check(aiEn.rate > 25f, "the English templated paragraph also reads as machine-flavoured ("
                + aiEn.rate + ")");
        check(rate(human) && rate(mild) && rate(ai) && rate(humanEn) && rate(aiEn),
                "every rate stays inside 0..100");
    }

    private static float topScore(AigcDetector.Result result) {
        float top = 0f;
        for (int i = 0; i < result.sentences.size(); i++)
            if (result.sentences.get(i).score > top) top = result.sentences.get(i).score;
        return top;
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
        // 0.7.0 改写：原 `sentence.score > 0.5f`。0.5 已经不是任何产品口径上的数（自编率那条线留给 0.7.2
        // 的字符账本），改按可疑区间门槛判：实测 0.508 >= 0.45，表情符号前缀不影响句分。
        check(sentence.score >= AigcDetector.SEGMENT_FLAG_GATE, "the templated tail still scores past the"
                + " flag gate after the emoji prefix (" + sentence.score + " >= "
                + AigcDetector.SEGMENT_FLAG_GATE + ")");
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
        // rate 的定义是本次重构的不可谈判约束：区间聚合必须做成"改视图不改数"，这条是那条恒等式的支点。
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
        // 本版有意改掉的一条：0 个计分句过去留着 insufficientSample=false，报告那一格印 0.00%。
        AigcDetector.Result empty = AigcDetector.detect("");
        check(empty.tier == AigcDetector.Tier.INSUFFICIENT_SAMPLE && empty.insufficientSample
                && empty.verdict.indexOf("样本不足") == 0, "空正文归样本不足，报告那一格不再出现 0.00%");
        check(nullResult.tier == AigcDetector.Tier.INSUFFICIENT_SAMPLE
                && AigcDetector.detect("   \t\n  ").tier == AigcDetector.Tier.INSUFFICIENT_SAMPLE,
                "null 与纯空白同样只给样本不足，不给比例");
        AigcDetector.Result marks = AigcDetector.detect("……———，，；；");
        check(marks.rate == 0f && marks.sentences.isEmpty()
                && marks.tier == AigcDetector.Tier.INSUFFICIENT_SAMPLE,
                "纯标点不出句，也只给样本不足");
        StringBuilder runOn = new StringBuilder();
        for (int i = 0; i < 60; i++) runOn.append("这一段的记录没有标点只能一直写下去");
        AigcDetector.Result longRun = AigcDetector.detect(runOn.toString());
        check(longRun.sentences.size() == 1 && longRun.comparedChars == runOn.length(),
                "a punctuation-free run of " + runOn.length() + " characters is treated as one sentence");
        check(longRun.rate >= 0f && longRun.rate <= 100f, "the run-on rate stays bounded");
        check(longRun.segments.size() == 1 && longRun.segments.get(0).toSentence == 0,
                "一句到底的稿子只有一个区间");
        AigcDetector.Result numbers = AigcDetector.detect("１２３４５６７８９０，２０２０ 年到 ２０２４ 年的样本量分别是三百、五百、八百。");
        check(numbers.rate >= 0f && numbers.rate <= 100f, "digit-heavy fullwidth text is handled without throwing");
        check(AigcDetector.detect("综上所述").sentences.isEmpty(), "a bare template phrase is too short to score");
        // 排除区间盖住整篇：没有计分句，区间表也必须是空的，档位回到样本不足。
        AigcDetector.Result covered = AigcDetector.detect(AI, new int[]{0, AI.length()});
        check(covered.segments.isEmpty() && covered.excludedChars > 0
                && covered.tier == AigcDetector.Tier.INSUFFICIENT_SAMPLE,
                "整篇圈成引用之后没有区间可报，档位是样本不足");
        // 排除区间插在中间：过去能并成一段的四句现在被切成两段，两侧都不越界。
        String hot = "综上所述，通过上述分析可以发现，这套流程的稳定性还有提升空间，为后续优化提供了重要的参考。";
        String cite = "总而言之，这段引文本身也是套话连篇，为论证提供了依据，具有重要的理论和实践意义。";
        String around = hot + cite + hot;
        AigcDetector.Result merged = AigcDetector.detect(around);
        check(merged.segments.size() == 1, "同一自然段里连着的同腔句并成一个区间");
        AigcDetector.Result split = AigcDetector.detect(around, new int[]{hot.length(), hot.length() + cite.length()});
        check(split.segments.size() == 2, "排除区间插在中间时区间在这里断开");
        boolean crosses = false;
        for (int i = 0; i < split.segments.size(); i++) {
            AigcDetector.Segment seg = split.segments.get(i);
            if (seg.start < hot.length() + cite.length() && seg.end > hot.length()) crosses = true;
        }
        check(!crosses, "没有区间跨过排除边界，跳正文不会跳到引用里");
        // 一句超过区间字数上限：自成一段且不拆。
        StringBuilder monster = new StringBuilder();
        while (monster.length() < 1400) monster.append("这一句被刻意写得很长以至于超过区间字数上限的设定");
        AigcDetector.Result giant = AigcDetector.detect(monster.toString() + "。");
        check(giant.sentences.size() == 1 && giant.segments.size() == 1,
                "一千四百字的一句话独占一个区间，本版不拆（拆分留给未来的窗口化）");
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
        // 0.7.0 改写：原 `20f < rate < 90f` → 下限 10。人写段落的句子在新表下大量归零，混合稿实测 14.53；
        // 上限不动。这条仍守着"混合稿明显高于纯人写"（纯人写实测 4.35）。
        check(result != null && result.rate > 10f && result.rate < 90f,
                "the mixed draft lands between the two poles (" + (result == null ? 0 : result.rate) + ")");
        check(best < 3000L, "detect over 20k characters stays fast on a desktop JVM (measured " + best + " ms)");
        System.out.println("PERF detect(20000 chars) = " + best + " ms, sentences="
                + (result == null ? 0 : result.sentences.size()));
    }

    /** 系数生效：ROADMAP 那句"权重集中成一组可标定的系数"的可执行版本。 */
    private static void coefficients() {
        AigcScorer.Coefficients base = AigcScorer.defaults(AigcFamily.CHINESE);
        check(base.version().length() > 0, "系数表带版本号，报告能追溯是哪一版算出来的");
        check("v1-order-only".equals(base.version()),
                "本轮没有一条系数够格定值，版本号直说只定了序（" + base.version() + "）");
        check(base.get(AigcFeatureId.TEMPLATE) > 0d
                        && base.get(AigcFeatureId.PARALLEL) < base.get(AigcFeatureId.TEMPLATE),
                "模板句式的系数高于节奏类系数（按真人侧触发率定序的结果）");
        check(base.get(AigcFeatureId.PUNCT_DASH) < base.get(AigcFeatureId.PARALLEL),
                "破折号那条无出处的系数被压在节奏组门面系数之下（" + base.get(AigcFeatureId.PUNCT_DASH)
                        + " < " + base.get(AigcFeatureId.PARALLEL) + "）");
        double total = 0d;
        double strongest = 0d;
        for (String label : base.groupTotals().keySet()) {
            double weight = base.groupTotals().get(label).doubleValue();
            total += weight;
            if (weight > strongest) strongest = weight;
        }
        check(total <= 1.0d + 1e-9d, "四组系数加和不超过 1.0（实测 " + total + "）");
        check(strongest < AigcDetector.SEGMENT_FLAG_GATE,
                "任何一条单独的证据都不足以把一句推到可疑（最强一组 " + strongest + " < 门槛 "
                        + AigcDetector.SEGMENT_FLAG_GATE + "）");
        String one = "综上所述，我们在四个数据集上把同一组实验重跑了一遍，结论和上次差不多。";
        AigcDetector.Result single = AigcDetector.detect(one);
        check(single.sentences.size() == 1 && single.sentences.get(0).features.size() == 1,
                "这句话只触发一条特征，才拿它当系数的量尺");
        double value = single.coefficients.get(AigcFeatureId.TEMPLATE) * 0.5d;
        check(Math.abs(single.sentences.get(0).score - (float) value) < 0.0001f,
                "句分等于特征值乘系数，没有别的东西掺进来（0.5 × " + value + "）");
        AigcScorer.Coefficients halved = AigcScorer.defaults(AigcFamily.CHINESE);
        halved.set(AigcFeatureId.TEMPLATE, base.get(AigcFeatureId.TEMPLATE) / 2d);
        AigcScorer.overrideCoefficients(halved);
        AigcDetector.Result turned;
        try {
            turned = AigcDetector.detect(one);
        } finally {
            AigcScorer.restoreCoefficients();
        }
        check(Math.abs(turned.sentences.get(0).score - (float) (value / 2d)) < 0.0001f,
                "标定口子把模板句式系数减半，句分立刻跟着减半——这就是系数生效");
        check(turned.sentences.get(0).features.equals(single.sentences.get(0).features),
                "改系数不改特征：判定依据照旧逐条列出来");
        check(Math.abs(AigcDetector.detect(one).sentences.get(0).score - single.sentences.get(0).score) < 0.0001f,
                "还原之后产品路径拿回的还是默认系数");
        check(Math.abs(AigcScorer.current(AigcFamily.CHINESE).get(AigcFeatureId.TEMPLATE)
                - base.get(AigcFeatureId.TEMPLATE)) < 1e-9d
                && AigcScorer.current(AigcFamily.CHINESE).version().equals(base.version()),
                "还原之后 current() 又是默认表：系数与版本号都回到 v1");
        check(AigcDetector.detect(AI).coefficientVersion != null
                        && AigcDetector.detect(AI).coefficientVersion.equals(base.version()),
                "没被标定时，结果里记的系数版本就是产品默认那一版");
        check(AigcScorer.score(null, base) == 0d && AigcScorer.score(new ArrayList<AigcFeatures.Hit>(), base) == 0d,
                "没有触发项就是零分，打分不看文本也不猜");
    }

    /** 每条特征能单独算、单独断言；族与族之间不串表。 */
    private static void features() {
        String flat = "第一段话也是一样的长。第二段话也是同样的长。第三段话仍旧是一样长。第四段话还是一般长度。";
        AigcFeatures.DocStats flatStats = statsOf(flat);
        AigcFeatures.Segment flatFirst = segment(flat);
        check(flatStats.scoredSentences == 4, "四句等长的段落四句都计分");
        check(AigcFeatures.value(AigcFeatureId.BURST, flatFirst, flatStats) > 0.9d,
                "四句等长的文档里，突发度这条特征取到接近满值（"
                        + AigcFeatures.value(AigcFeatureId.BURST, flatFirst, flatStats) + "）");
        check(AigcFeatures.value(AigcFeatureId.TEMPLATE, flatFirst, flatStats) == 0d,
                "同一段落里没写套话就是零，特征之间不互相污染");
        AigcFeatures.DocStats jumpy = statsOf(HUMAN);
        check(AigcFeatures.value(AigcFeatureId.BURST, segment(HUMAN), jumpy) == 0d,
                "长短悬殊的段落里突发度不亮灯");
        String three = "第一段话也是一样的长。第二段话也是同样的长。第三段话仍旧是一样长。";
        check(AigcFeatures.value(AigcFeatureId.BURST, segment(three), statsOf(three)) == 0d,
                "计分句不足四句的文档不给突发度，文档级证据不硬凑");
        ArrayList<AigcFeatures.Hit> heavy = AigcFeatures.of(segment(AI_SENTENCE_1), statsOf(AI));
        check(heavy.size() >= 2, "模板腔句子一次就亮出两条以上独立特征（" + heavy.size() + " 条）");
        boolean graded = true;
        for (AigcFeatureId id : AigcFeatureId.values())
            if (AigcFeatureId.COUNT <= id.ordinal()) graded = false;
        check(graded && AigcFeatureId.COUNT == AigcFeatureId.values().length,
                "特征登记表没有漏项，新增特征必须同时进系数表");
        boolean grouped = true;
        for (AigcFeatureId id : AigcFeatureId.values()) {
            int g = AigcFeatureId.group(id);
            if (g < 0 || g >= AigcFeatureId.GROUP_COUNT) grouped = false;
        }
        check(grouped && AigcFeatureId.GROUP_COUNT == AigcFeatureId.GROUP_LABELS.length,
                "每条特征都登记了证据组，组数与组名一样多");
        check(AigcFeatures.value(AigcFeatureId.TEMPLATE, segment("综上所述，结论是稳定的。"), statsOf(MILD)) > 0d,
                "模板特征只在真有套话的那句上亮灯");
        // 分族不串表：拉丁句不数中文连接词，中文句不数英文连接词。
        String latinWithCn = "the loader stalled halfway, 首先 其次 因此 we restarted the run.";
        AigcFeatures.Segment latin = segment(latinWithCn);
        check(latin.family == AigcFamily.LATIN, "拉丁字母占多的一句判为拉丁族（" + latin.family + "）");
        check(AigcFeatures.countOccurrences(latin.body, AigcFeatures.CN_CONNECTIVES) >= 3,
                "这句里确实塞了三个以上中文连接词，才拿它当分族的量尺");
        check(AigcFeatures.value(AigcFeatureId.CONNECTIVE, latin, statsOf(latinWithCn)) == 0d,
                "拉丁族不数中文连接词表——0.5.4 中英混数那个毛病已经没了");
        String cnWithEn = "这套流程在凌晨三点半卡住了，moreover 和 furthermore 都没能把日志救回来。";
        AigcFeatures.Segment chinese = segment(cnWithEn);
        check(chinese.family == AigcFamily.MIXED,
                "夹了两个英文连接词的中文句判为混排族，不会被当成整句英文（" + chinese.family + "）");
        check(AigcFeatures.countOccurrences(chinese.body, AigcFeatures.EN_CONNECTIVES) == 2,
                "这句里确实有两个英文连接词");
        check(AigcFeatures.value(AigcFeatureId.CONNECTIVE, chinese, statsOf(cnWithEn)) == 0d,
                "混排族只按中文连接词表数，英文连接词不掺进来加分");
        check(AigcFeatures.diversityFloor(AigcFamily.LATIN) != AigcFeatures.diversityFloor(AigcFamily.CHINESE),
                "多样度下限分族（拉丁 " + AigcFeatures.diversityFloor(AigcFamily.LATIN)
                        + " 对中文 " + AigcFeatures.diversityFloor(AigcFamily.CHINESE) + "）");
        check(AigcScorer.defaults(AigcFamily.LATIN).version().indexOf("latin") >= 0
                        && !AigcScorer.defaults(AigcFamily.LATIN).version()
                        .equals(AigcScorer.defaults(AigcFamily.CHINESE).version()),
                "拉丁族的版本号单独标注，未经标定的事写在版本号里");
    }
    /** 区间聚合：分区不挑选 + 恒等式 + 不跨段/不跨族/不跨排除区。 */
    private static void segments() {
        String[] texts = {AI + MILD + HUMAN, HEAVY + HUMAN, MILD + AI_EN, HUMAN_EN + AI};
        for (int t = 0; t < texts.length; t++) {
            String text = texts[t];
            AigcDetector.Result run = AigcDetector.detect(text);
            String norm = TextCorpus.normalize(text);
            check(!run.segments.isEmpty() && run.segments.size() <= run.sentences.size(),
                    "区间数不会多于句子数（" + run.segments.size() + " <= " + run.sentences.size() + "）");
            int chars = 0, members = 0, first = -1;
            double weighted = 0d;
            boolean partition = true, ordered = true;
            for (int i = 0; i < run.segments.size(); i++) {
                AigcDetector.Segment seg = run.segments.get(i);
                chars += seg.chars;
                members += seg.toSentence - seg.fromSentence + 1;
                weighted += (double) seg.score * seg.chars;
                if (seg.start != run.sentences.get(seg.fromSentence).start
                        || seg.end != run.sentences.get(seg.toSentence).end) partition = false;
                if (seg.chars != expectedChars(run, seg, norm)) partition = false;
                for (int k = seg.fromSentence; k <= seg.toSentence; k++) {
                    AigcDetector.Sentence member = run.sentences.get(k);
                    if (member.start < seg.start || member.end > seg.end) partition = false;
                }
                if (i > 0 && seg.start < run.segments.get(i - 1).end) ordered = false;
                if (first < 0) first = seg.start;
            }
            check(ordered, "区间按原文顺序排且不重叠");
            check(partition, "区间偏移不扩边，字数按成员句有效字符算");
            check(members == run.sentences.size(), "每个计分句恰好落进一个区间，低分句也自成区间");
            check(chars == run.comparedChars, "区间字数加和等于 comparedChars（" + chars
                    + " == " + run.comparedChars + "）");
            // 这条是"分区不挑选"的恒等式：区间只是视图，聚合一个字符都没有重复计或漏掉。
            check(Math.abs(weighted / Math.max(1, chars) * 100d - run.rate) < 0.05d,
                    "Σ 区间分×字数 / Σ 字数 == rate 是恒等式（" + run.rate + "）");
            check(run.segments.get(0).start == first, "第一个区间的起点就是第一个计分句的起点");
        }
        String mixed = AI + MILD + MILD + MILD;
        int aiEnd = AI.length();
        AigcDetector.Result quotedRun = AigcDetector.detect(mixed, new int[]{0, aiEnd});
        boolean clean = true;
        for (int i = 0; i < quotedRun.segments.size(); i++)
            if (quotedRun.segments.get(i).start < aiEnd) clean = false;
        check(clean, "被圈成引用的那一段把区间切断了，没有区间跨过排除边界");
        String mixedFamily = "We ran it overnight and it choked halfway. 综上所述，这套流程的稳定性还有提升空间。";
        AigcDetector.Result families = AigcDetector.detect(mixedFamily);
        boolean split = true;
        for (int i = 0; i < families.sentences.size(); i++) {
            if (families.sentences.get(i).score < AigcDetector.SEGMENT_SCORE_FLOOR) continue;
            boolean found = false;
            for (int k = 0; k < families.segments.size(); k++) {
                AigcDetector.Segment seg = families.segments.get(k);
                if (i >= seg.fromSentence && i <= seg.toSentence) {
                    found = true;
                    if (seg.fromSentence != seg.toSentence) split = false;
                }
            }
            if (!found) split = false;
        }
        check(split, "中文句和英文句不并成同一个区间（族切换即断）");
        AigcDetector.Result lone = AigcDetector.detect(AI_SENTENCE_1 + MILD + MILD + MILD);
        check(lone.segments.get(0).score == lone.sentences.get(0).score,
                "单句区间的区间分就等于句分——区间分数里没有夹带连续加成");
        check(lone.longestRunSentences == 1 && lone.longestRunChars > 0,
                "最长可疑区间的句数与字数按有效字符报出来，档位的依据不是黑箱（"
                        + lone.longestRunSentences + " 句 / " + lone.longestRunChars + " 字）");
        check(lone.flaggedSegments == 1 && lone.flaggedChars == lone.longestRunChars,
                "可疑字数只从过线的区间里来（" + lone.flaggedChars + " 字）");
        AigcDetector.Result quiet = AigcDetector.detect(HUMAN_EN + HUMAN_EN + HUMAN_EN);
        check(quiet.flaggedChars == 0 && quiet.flaggedSegments == 0 && quiet.longestRunSentences == 0,
                "没有过线区间时三个可疑量数全为 0，不靠巧合");
    }

    /** 五档边界 + 双门槛：比例与字数缺一样就降档。 */
    private static void tiers() {
        check(AigcDetector.detect("").tier == AigcDetector.Tier.INSUFFICIENT_SAMPLE
                        && AigcDetector.detect("").verdict.indexOf("样本不足") == 0,
                "空正文归样本不足，比例那一格闭嘴");
        check(AigcDetector.detect("！！！？？？……——、，，，").tier
                        == AigcDetector.Tier.INSUFFICIENT_SAMPLE,
                "只有标点也只给样本不足，不给比例");
        check(AigcDetector.detect(HUMAN).tier == AigcDetector.Tier.INSUFFICIENT_SAMPLE,
                "两百字的人写段落先落样本不足档，比例和档位一起闭嘴");
        AigcDetector.Result none = AigcDetector.detect(HUMAN_EN + HUMAN_EN + HUMAN_EN);
        check(none.tier == AigcDetector.Tier.NONE, "过了字数门槛的真人英文给一般档");
        AigcDetector.Result watch = AigcDetector.detect(AI + AI + AI);
        check(watch.rate >= AigcDetector.TIER_REVIEW_RATE && watch.flaggedChars < AigcDetector.TIER_REVIEW_CHARS
                        && watch.tier == AigcDetector.Tier.WATCH,
                "比例够但可疑字符不到 " + AigcDetector.TIER_REVIEW_CHARS + " 字时降一档（实测比例 "
                        + watch.rate + "、可疑 " + watch.flaggedChars + " 字、档位 " + watch.tier + "）");
        AigcDetector.Result lone = AigcDetector.detect(AI_SENTENCE_1 + HUMAN_EN + HUMAN_EN);
        check(lone.comparedChars >= AigcDetector.MIN_DOCUMENT_CHARS
                        && lone.longestRunSentences < AigcDetector.TIER_REVIEW_RUN
                        && lone.tier.ordinal() < AigcDetector.Tier.NEEDS_REVIEW.ordinal(),
                "单句高分不成档——最长可疑区间只有一句，够不到复核档（" + lone.tier + "）");
        AigcDetector.Result review = AigcDetector.detect(HEAVY + HUMAN_EN + HUMAN_EN);
        check(review.tier == AigcDetector.Tier.NEEDS_REVIEW,
                "成片且超过 " + AigcDetector.TIER_REVIEW_CHARS + " 字的机器腔给复核档（可疑 "
                        + review.flaggedChars + " 字 / 最长 " + review.longestRunSentences + " 句）");
        AigcDetector.Result strong = AigcDetector.detect(HEAVY + HEAVY + HEAVY);
        check(strong.tier == AigcDetector.Tier.STRONG,
                "整篇同腔且过了 " + AigcDetector.TIER_STRONG_CHARS + " 字给成段档（可疑 "
                        + strong.flaggedChars + " 字 / 最长 " + strong.longestRunSentences + " 句 / 比例 "
                        + strong.rate + "）");
        check(strong.tier.ordinal() > AigcDetector.Tier.NEEDS_REVIEW.ordinal()
                        && AigcDetector.Tier.NEEDS_REVIEW.ordinal() > AigcDetector.Tier.WATCH.ordinal()
                        && AigcDetector.Tier.WATCH.ordinal() > AigcDetector.Tier.NONE.ordinal(),
                "五档强弱顺序固定，报告与 UI 按 ordinal 比高低");
        check(strong.verdict.indexOf("%") < 0 && watch.verdict.indexOf("%") < 0
                        && none.verdict.indexOf("%") < 0,
                "档位文案里不塞百分比，一个口径一个数");
        check(strong.verdict.indexOf("成段机器腔") == 0 && strong.verdict.indexOf("段") > 0,
                "成段档那句人话说清了几段几字（" + strong.verdict + "）");
    }

    /** 真实论文里最像机写的真人句子：钉子户清单，一句都不许进可疑档。 */
    private static void hardHuman() {
        ArrayList<String> nails = new ArrayList<String>();
        for (int i = 0; i < HARD_HUMAN.length; i++) nails.add(HARD_HUMAN[i]);
        try {
            for (String line : Files.readAllLines(Paths.get("tests/corpus/aigc-hard-human.txt"),
                    StandardCharsets.UTF_8)) {
                String text = line.trim();
                if (text.length() == 0 || text.startsWith("#") || nails.indexOf(text) >= 0) continue;
                nails.add(text);
            }
        } catch (Exception missing) {
            // 语料文件不在就用内置的三句，套件不依赖工作目录也能跑。
        }
        int over = 0;
        float worst = 0f;
        AigcDetector.Tier top = AigcDetector.Tier.NONE;
        for (int i = 0; i < nails.size(); i++) {
            // 同一句念三遍，凑够 400 字的样本门槛，让档位判定也参与复检。
            AigcDetector.Result run = AigcDetector.detect(nails.get(i) + nails.get(i) + nails.get(i));
            for (int k = 0; k < run.sentences.size(); k++) {
                float score = run.sentences.get(k).score;
                if (score > worst) worst = score;
                if (score >= AigcDetector.SEGMENT_FLAG_GATE) over++;
            }
            if (run.tier.ordinal() > top.ordinal()) top = run.tier;
        }
        check(over == 0, "真实论文里最像机写的 " + nails.size() + " 句真人句子，一句都不许进可疑档"
                + "（最高 " + worst + "，门槛 " + AigcDetector.SEGMENT_FLAG_GATE + "，余量 "
                + (AigcDetector.SEGMENT_FLAG_GATE - worst) + "）");
        check(top.ordinal() < AigcDetector.Tier.NEEDS_REVIEW.ordinal(),
                "钉子户句子重复三遍也够不到复核档（实测 " + top + "）");
    }
}
