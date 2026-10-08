package com.rikkahub.wordlite;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * "查重率 0% 怎么可能"的另一半证据。{@code ZeroRateAudit} 数的是"哪些合法路径能得到 0%"，本套件数的是
 * 反面：**语料里确实有可比材料时，抄进去的那一段绝不能是 0.00%**，也就是说判据没有死。
 *
 * 语料是 tests/corpus/floor-source-*.txt 五篇合成文献；稿件由它们拼出来，每一段身份明确：逐字抄的一段、
 * 轻改写（换词序 + 同义替换）的一段、真自己写的一段、带引号与著录的引用一段、以及必须被结构排除的参考文献表。
 * 走的是产品那三个公开入口——{@code LocalLibrary.index} 灌库、{@code TextCorpus.match} 出命中、
 * {@code CharLedger.close} 出三个比率——零网络、零检索、不碰 {@code DuplicateEngine}（它正在被另一个人改），
 * 也不动任何判据与阈值：阈值只被读出来打印，一次也不覆盖。
 *
 * 断言全部是行为断言，且每条都带实测数字：
 * 1. 逐字抄的那一段必须 100% 命中，命中区间的头尾必须正好落在这段的句首与句尾；
 * 2. 轻改写那一段的重复占比必须严格高于自写段落——这一档现在是 KNOWN_GRANULARITY_BLIND_SPOT 的读数台：
 *    盲区还在时它只把测量结果报进 SUMMARY（沿用 AigcFeatureAuditRegression 的写法，exit 0），
 *    开关一翻掉就变回硬断言。其余每一条断言一律致命。
 * 3. 自写段落贡献的重复字符必须是 0；
 * 4. 引用段落的命中必须落进 CharLedger 的"引用区间内重复"那一桶；
 * 5. 参考文献表的字数必须从分母里掉出去；并且拿掉结构排除后它确实会命中——证明归零是排除在起作用，
 *    不是碰巧没配上；
 * 6. 三个互斥桶仍然闭合 100%；
 * 7. 头条：语料里有可比对象且有命中时，总相似度比必须大于 0，屏幕那一格不许印成 0.00%。
 *
 * 缺语料一律 throw，不静默跳过。
 */
public final class DetectionFloor {
    private static final String VERBATIM_DOC = "floor-source-01-verbatim.txt";
    private static final String PARAPHRASE_DOC = "floor-source-02-paraphrase.txt";
    private static final String QUOTED_DOC = "floor-source-03-quoted.txt";
    private static final String DECOY_DOC = "floor-source-04-decoy.txt";
    private static final String REFERENCES_DOC = "floor-source-05-references.txt";
    private static final String[] CORPUS_FILES = {
            VERBATIM_DOC, PARAPHRASE_DOC, QUOTED_DOC, DECOY_DOC, REFERENCES_DOC,
    };

    /**
     * 已知判据盲区：把语料里那一句长句改写成两句之后，比对粒度是"稿件一句 对 语料一句"，两半各自对整句
     * 原话的袋 Dice 只有 0.522 / 0.634（第二半连 0.683 的可达上界都够不到），而整段对整段是 0.772，
     * 高过 SIMILAR_BAG_DICE=0.72。只把那枚分号换成逗号、一字不改，同一段就报出 37/73 字 = 50.68%。
     * 差的是粒度，不是阈值松紧，所以修在判据侧（袋口径的候选粒度），不修在这里。
     *
     * 要翻成 false 的那一版：2.2.0（"功能加一版"那一档，判据粒度改动的落点）。翻之前必须按
     * docs/detection-calibration.md 的口径重跑一遍 `pwsh tools/detect-calibration.ps1`，把同源负例在
     * PAIR / CROSS / ENGINE 三层的误报字符占比重新量一遍——"真人负例零误报"这一档不许沿用旧表，因为把
     * 袋口径从"一句对一句"扩到"多句对一句"吃进去的正是那批同领域术语撞车的负例。量完在 docs 里落新表，
     * 再把这个开关翻掉；翻掉之后这一档仍是硬断言（跑不过就 exit 非 0）。
     */
    static final boolean KNOWN_GRANULARITY_BLIND_SPOT = true;
    /** 与 DuplicateEngine.QUOTES 同一份引号对：那个常量是包内私有，测试这边按同一口径复刻一份。 */
    private static final char[][] QUOTES = {
            { '\u201c', '\u201d' }, { '\u300c', '\u300d' }, { '\u300e', '\u300f' },
            { '"', '"' }, { '\u300a', '\u300b' },
    };

    /** 稿件里五段的内容。逐字抄与引用那两段的正文不在这里，运行时从语料文件里原样取，防止抄漏一个字的漂移。 */
    private static final String INTRO = "1 引言\n"
            + "城市内涝治理的讨论长期围着设施规模打转，投资被当成一道容积的算术题，仿佛调蓄空间做够了，"
            + "积水也就随之消解。这套说法漏掉了一件更便宜的事：同一笔钱花在监测与调度上，往往比重复修池子见效更快。"
            + "本文走的是后一条路，不新增一寸调蓄空间，只把已经装好的雨量筒数据接进现有的闸门调度逻辑。";
    private static final String ENDING = "4 小结\n"
            + "本文把雨量信息接进既有闸泵的调度逻辑，没有新增一寸构筑物。下一步要回答的是，"
            + "当雨量遥测本身缺站的时候，这条路的收益还能剩下多少。";
    /** 轻改写：换词序 + 同义替换，句子还是那一句。与 floor-source-02 第一行的字符重合度由下面的实测打印。 */
    private static final String PARAPHRASE = "低影响开发设施能不能削峰，不只由设施本身的调蓄容积决定，"
            + "也与它在汇水分区里怎样铺开有关；多数设计雨型下，把同等容积分散布设要优于集中放在管网末端。";
    private static final String QUOTE_LEAD = "3 设施布设与制图尺度\n关于制图尺度，该文那句判断今天仍然适用：";
    private static final String QUOTE_TAIL = "\uff08王莉、陈默，2018\uff09。本文的分辨率选择正是照着这条标准倒推的。";
    /** 稿件末尾的参考文献表：四条条目与 floor-source-05 里的条目逐字相同，由下面的完整性核对钉住。 */
    private static final String[] REFERENCES = {
            "[1] 王莉, 陈默. 城市内涝风险图制图中格网尺度的敏感性[J]. 水文与水文气象, 2018, 35(4): 61-68.",
            "[2] 赵晓峰. 海绵城市设施全生命周期成本研究[D]. 上海: 同济大学出版社, 2020.",
            "[3] Field J, Brown R. Grid resolution effects on urban pluvial flood mapping[J]. "
                    + "Journal of Hydraulic Engineering, 2017, 143(9): 04017052.",
            "[4] 李慕青, 周衡. 低影响开发设施布设优化的多目标算法[J]. 排水公报, 2019, 46(1): 12-19.",
    };

    private static int checks;
    private static int failures;
    private static int knownBlindSpots;

    /** 一段稿件正文的身份与它在稿件串上的区间，外加实测出来的命中字数。 */
    private static final class Section {
        final String label;
        final int start;
        final int end;
        int valid;
        int duplicate;
        int hitCount;

        Section(String label, int start, int end) {
            this.label = label;
            this.start = start;
            this.end = end;
        }

        double rate() {
            return valid <= 0 ? 0d : duplicate * 100d / (double) valid;
        }
    }

    private static final PrintStream OUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);

    private static void head(String title) {
        OUT.println();
        OUT.println("== " + title + " ==");
    }

    private static void line(String key, Object value) {
        OUT.println("    " + key + " = " + value);
    }

    private static void check(boolean ok, String message) {
        checks++;
        OUT.println("  " + (ok ? "[OK]   " : "[FAIL] ") + message);
        if (!ok) failures++;
    }

    /** 已知盲区的测量结果照打，但计入 knownBlindSpots 而不是 failures：它由 SUMMARY 交代，不拦门。 */
    private static void blind(String message) {
        checks++;
        knownBlindSpots++;
        OUT.println("  [BLIND ] " + message);
    }

    /** 与 ApiWorkflow.showScan / ReportCenterUI 同一个写法，打出来的就是用户屏幕上那串。 */
    private static String pct(double rate) {
        return String.format(Locale.CHINA, "%.2f%%", Double.valueOf(rate));
    }

    /** 缺文件、空文件一律当场抛：静默跳过会让这套件永远"通过"。 */
    private static String readFixture(File file) throws Exception {
        if (!file.isFile()) {
            throw new IllegalStateException("DetectionFloor: missing corpus fixture " + file.getAbsolutePath());
        }
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        if (text.startsWith("\ufeff")) text = text.substring(1);
        if (text.trim().length() < 60) {
            throw new IllegalStateException("DetectionFloor: corpus fixture too thin (" + text.trim().length()
                    + " chars): " + file.getAbsolutePath());
        }
        return text;
    }

    /** 取语料文件里的第 index 个非空行，取不到就抛。 */
    private static String fixtureLine(String document, int index, String owner) {
        int seen = 0;
        for (String row : document.split("\n")) {
            String trimmed = row.trim();
            if (trimmed.length() == 0) continue;
            if (seen++ == index) return trimmed;
        }
        throw new IllegalStateException("DetectionFloor: " + owner + " has no non-empty line #" + index);
    }
    public static void main(String[] args) throws Exception {
        File corpusDir = new File(args.length > 0 ? args[0] : "tests/corpus");
        File libraryDir = new File(args.length > 1 ? args[1] : "artifacts/build/host-detection-floor");

        LinkedHashMap<String, String> documents = new LinkedHashMap<String, String>();
        for (int i = 0; i < CORPUS_FILES.length; i++) {
            documents.put(CORPUS_FILES[i], readFixture(new File(corpusDir, CORPUS_FILES[i])));
        }
        String copied = fixtureLine(documents.get(VERBATIM_DOC), 0, VERBATIM_DOC);
        String paraphraseSource = fixtureLine(documents.get(PARAPHRASE_DOC), 0, PARAPHRASE_DOC);
        String quotedSource = fixtureLine(documents.get(QUOTED_DOC), 0, QUOTED_DOC);

        // 稿件的参考文献表要与 floor-source-05 的条目逐字相同，否则"结构排除"测的就不是同一批字。
        StringBuilder referenceBlock = new StringBuilder("参考文献");
        for (int i = 0; i < REFERENCES.length; i++) {
            if (documents.get(REFERENCES_DOC).indexOf(REFERENCES[i]) < 0) {
                throw new IllegalStateException("DetectionFloor: manuscript reference #" + (i + 1)
                        + " is not verbatim inside " + REFERENCES_DOC);
            }
            referenceBlock.append('\n').append(REFERENCES[i]);
        }

        // 拼稿件：段与段之间空一行，每段区间当场记下来，后面所有边界断言都按这些区间判。
        StringBuilder ms = new StringBuilder();
        Section intro = section(ms, "自写段落一（引言）", INTRO);
        Section copy = section(ms, "逐字抄（floor-source-01 第 1 行）", copied);
        Section paraphrased = section(ms, "轻改写（floor-source-02 第 1 行）", PARAPHRASE);
        String quoteBody = QUOTE_LEAD + "\u300c" + quotedSource + "\u300d" + QUOTE_TAIL;
        Section quote = section(ms, "带引号与著录的引用（floor-source-03 第 1 行）", quoteBody);
        Section references = section(ms, "参考文献表（必须结构排除）", referenceBlock.toString());
        Section ending = section(ms, "自写段落二（小结）", ENDING);
        Section[] sections = { intro, copy, paraphrased, quote, references, ending };
        String text = ms.toString();
        String norm = TextCorpus.normalize(text);
        int wholeValid = TextCorpus.validCount(norm, 0, text.length());

        int quoteStart = text.indexOf('\u300c');
        int quoteEnd = text.indexOf('\u300d') + 1;
        if (quoteStart < 0 || quoteEnd <= quoteStart) {
            throw new IllegalStateException("DetectionFloor: the quoted passage lost its quote markers");
        }

        head("输入：五篇合成文献 + 一段拼出来的稿件（全程零网络、零检索）");
        line("语料目录", corpusDir.getPath() + "（" + documents.size() + " 篇）");
        line("稿件字符 text.length()", Integer.valueOf(text.length()));
        line("稿件有效字符（整篇）", Integer.valueOf(wholeValid));
        line("逐字段落区间", copy.start + "-" + copy.end + "，与语料第 1 行逐字相同="
                + text.substring(copy.start, copy.end).equals(copied));
        line("引用区间（含引号）", quoteStart + "-" + quoteEnd);

        // 产品入口一：落盘进自建库，再 index() 灌成语料。目录先清空，上一轮的条目不许混进分母。
        wipe(libraryDir);
        LocalLibrary library = new LocalLibrary(libraryDir);
        library.clear();
        for (Map.Entry<String, String> document : documents.entrySet()) {
            String error = library.addDocument(document.getKey(),
                    document.getValue().getBytes(StandardCharsets.UTF_8));
            if (error != null) {
                throw new IllegalStateException("DetectionFloor: library rejected " + document.getKey() + ": " + error);
            }
        }
        TextCorpus corpus = new TextCorpus();
        library.index(corpus);

        // 产品入口二与三：structure 圈排除区 -> match 出命中 -> CharLedger 出三个比率。
        TextCorpus.Structure structure = TextCorpus.structure(text);
        int[] citations = citationSpans(text);
        int[] excluded = structure.spanArray();
        TextCorpus.Report matched = corpus.match(text, citations, excluded);
        CharLedger.Balance ledger = CharLedger.close(text, excluded, citations, matched.hits, null);
        int[] hits = merged(matched.hits, text.length());

        head("自建库与语料");
        line("自建库文件数 library.size()", Integer.valueOf(library.size()));
        line("入库句子条目 corpus.sentenceCount()", Integer.valueOf(corpus.sentenceCount()));
        line("结构排除节数（参考文献 / 其它）", structure.bibliographySections + " / " + structure.otherSections);
        line("结构排除字数 structure.excludedChars", Integer.valueOf(structure.excludedChars));
        line("引用区间条数", Integer.valueOf(citations.length / 2));

        head("逐段实测（分子按命中区间取并集后裁到该段，字数一律是有效字符）");
        for (int i = 0; i < sections.length; i++) {
            Section item = sections[i];
            item.valid = TextCorpus.validCount(norm, item.start, item.end);
            item.duplicate = duplicateIn(norm, hits, item.start, item.end);
            item.hitCount = overlapping(matched.hits, item.start, item.end);
            OUT.println(String.format(Locale.CHINA, "    [%-34s] %5d-%-5d 有效 %4d 命中 %4d 占比 %7s 命中段数 %d",
                    item.label, Integer.valueOf(item.start), Integer.valueOf(item.end), Integer.valueOf(item.valid),
                    Integer.valueOf(item.duplicate), pct(item.rate()), Integer.valueOf(item.hitCount)));
        }
        for (int i = 0; i < matched.hits.size(); i++) {
            TextCorpus.Hit hit = matched.hits.get(i);
            OUT.println("    HIT " + hit.start + "-" + hit.end + " 分数 "
                    + String.format(Locale.CHINA, "%.3f", Float.valueOf(hit.score))
                    + " 通道 " + (hit.channel == TextCorpus.CHANNEL_REWRITE ? "改写(袋)" : "字面")
                    + " 出处 " + (hit.source == null ? "?" : hit.source.title));
        }

        head("轻改写那一段的判据读数（阈值只读出来打印，一次也不覆盖）");
        line("稿件那一段", PARAPHRASE);
        line("语料原句", paraphraseSource);
        float wholeDice = TextCorpus.dice(PARAPHRASE, paraphraseSource);
        float wholeBag = TextCorpus.bagDice(PARAPHRASE, paraphraseSource);
        char[] queryBag = TextCorpus.bagOf(PARAPHRASE);
        char[] sourceBag = TextCorpus.bagOf(paraphraseSource);
        line("TextCorpus.dice 三元组", Float.valueOf(wholeDice)
                + "，门槛 SIMILAR_DICE=" + Float.valueOf(TextCorpus.SIMILAR_DICE));
        line("三元组包含率", Float.valueOf(containment(PARAPHRASE, paraphraseSource))
                + "，门槛 SIMILAR_CONTAINMENT=" + Float.valueOf(TextCorpus.SIMILAR_CONTAINMENT));
        line("TextCorpus.bagDice 字符袋（整段对整段）", Float.valueOf(wholeBag)
                + "，门槛 SIMILAR_BAG_DICE=" + Float.valueOf(TextCorpus.SIMILAR_BAG_DICE));
        line("这一段实测命中", paraphrased.duplicate + "/" + paraphrased.valid + " 字 = " + pct(paraphrased.rate()));
        line("字符袋规模与可达上界", "去重后 稿件 " + queryBag.length + " 字 / 语料 " + sourceBag.length
                + " 字，bagReach 上界 " + Float.valueOf(TextCorpus.bagReach(queryBag, sourceBag))
                + "（袋口径先要过这一档才去数交集）");
        line("本轮袋口径倒排精算次数 corpus.bagProbes()", Integer.valueOf(corpus.bagProbes()));
        TextCorpus paired = new TextCorpus();
        paired.add(sourceOf(PARAPHRASE_DOC), paraphraseSource);
        TextCorpus.Report pairedReport = paired.match(PARAPHRASE, null, null);
        line("对照一：语料里只放这一篇原句", pairedReport.hits.size() + " 段命中，"
                + pairedReport.duplicateChars + "/" + pairedReport.comparedChars + " 字 = "
                + pct(pairedReport.overallRate) + "（排掉其它条目抢候选位之后的答案；语料 "
                + paired.sentenceCount() + " 条，袋口径精算 " + paired.bagProbes() + " 次）");
        // 判据的比对粒度是"稿件的一句 对 语料的一句"。下面把这些句子摊开：句子被改写者重新切过之后，
        // 每一半各自去对整句原话，袋 Dice 就掉到地板以下，哪怕整段对着整段是过线的。
        float[] clauseBag = new float[8];
        float[] clauseReach = new float[8];
        int clauses = 0;
        for (int[] span : TextCorpus.sentences(PARAPHRASE)) {
            String clause = PARAPHRASE.substring(span[0], span[1]);
            char[] clauseBagOf = TextCorpus.bagOf(clause);
            float clauseDice = TextCorpus.bagDice(clause, paraphraseSource);
            float clauseUpper = TextCorpus.bagReach(clauseBagOf, sourceBag);
            if (clauses < clauseBag.length) {
                clauseBag[clauses] = clauseDice;
                clauseReach[clauses] = clauseUpper;
            }
            clauses++;
            OUT.println("    切句后每一句单独对原句：袋 Dice " + Float.valueOf(clauseDice)
                    + "，三元组 Dice " + Float.valueOf(TextCorpus.dice(clause, paraphraseSource))
                    + "，袋规模 " + Integer.valueOf(clauseBagOf.length) + " 对 " + Integer.valueOf(sourceBag.length)
                    + "，袋可达上界 " + Float.valueOf(clauseUpper));
        }
        TextCorpus.Report shapeReport = paired.match(PARAPHRASE.replace('\uff1b', '\uff0c'), null, null);
        line("对照二：只把那枚分号换成逗号（其余一字不改，只动切句）", shapeReport.hits.size() + " 段命中，"
                + shapeReport.duplicateChars + "/" + shapeReport.comparedChars + " 字 = "
                + pct(shapeReport.overallRate) + "；它与上面几行的差是粒度差，不是用词差");
        head("断言");
        // 1. 逐字抄：整段必须进分子，命中区间的头尾必须正好落在这段的句首与句尾。
        check(copy.valid > 0 && copy.duplicate == copy.valid,
                "逐字段落必须整段进分子：实测 " + copy.duplicate + "/" + copy.valid + " 字 = "
                        + pct(copy.rate()) + "（命中 " + copy.hitCount + " 段）");
        int sentenceStart = -1;
        int sentenceEnd = -1;
        for (int[] span : TextCorpus.sentences(text)) {
            if (span[0] >= copy.start && span[1] <= copy.end) {
                if (sentenceStart < 0) sentenceStart = span[0];
                sentenceEnd = span[1];
            }
        }
        if (sentenceStart < 0) throw new IllegalStateException("DetectionFloor: the copied section holds no sentence");
        int hitStart = Integer.MAX_VALUE;
        int hitEnd = -1;
        boolean literalChannel = false;
        for (int i = 0; i < matched.hits.size(); i++) {
            TextCorpus.Hit hit = matched.hits.get(i);
            if (hit.end <= copy.start || hit.start >= copy.end) continue;
            hitStart = Math.min(hitStart, hit.start);
            hitEnd = Math.max(hitEnd, hit.end);
            if (hit.channel == TextCorpus.CHANNEL_VERBATIM) literalChannel = true;
        }
        check(hitStart == sentenceStart && hitEnd == sentenceEnd,
                "逐字命中的区间头尾必须正好落在这段的句首与句尾：实测 " + hitStart + "-" + hitEnd
                        + "，句首 " + sentenceStart + " / 句尾 " + sentenceEnd);
        check(literalChannel,
                "逐字段落必须有字面证据通道（CHANNEL_VERBATIM）的命中，不能只靠袋口径兜：实测命中 " + copy.hitCount + " 段");

        // 2. 自写段落：一个字都不许进分子。
        check(intro.duplicate == 0 && ending.duplicate == 0,
                "自写段落贡献的重复字符必须为 0：实测引言 " + intro.duplicate + " 字、小结 " + ending.duplicate
                        + " 字（两段共 " + (intro.valid + ending.valid) + " 字有效字符）");

        // 3. 轻改写：占比必须严格高于自写段落。这一档现在是 KNOWN_GRANULARITY_BLIND_SPOT 的读数台：
        //    盲区还在且开关开着 -> 打 [BLIND] 并写进 SUMMARY，不拦门；开关翻成 false -> 立刻变回硬断言。
        //    别改断言也别改语料去凑：把语料那一句长句重新切成两句，判据的比对粒度是"稿件一句 对 语料一句"，
        //    两半各自对整句原话都够不到地板，而整段对整段是过线的（数字见上面那组读数）。
        double ownRate = Math.max(intro.rate(), ending.rate());
        boolean paraphraseCaught = paraphrased.duplicate > 0 && paraphrased.rate() > ownRate;
        String paraphraseNumbers = "实测 " + pct(paraphrased.rate()) + "（" + paraphrased.duplicate + "/"
                + paraphrased.valid + " 字）对比自写 " + pct(ownRate);
        int lastClause = Math.min(1, Math.max(0, clauses - 1));
        if (paraphraseCaught) {
            check(true, "轻改写段落的重复占比严格高于自写段落：" + paraphraseNumbers
                    + "；KNOWN_GRANULARITY_BLIND_SPOT 这一档没再复现，可以按注释里的条件（先重跑标定台）翻成 false");
        } else if (KNOWN_GRANULARITY_BLIND_SPOT) {
            blind("light paraphrase re-segmented into two sentences is not detected（已知粒度盲区，开关 "
                    + "KNOWN_GRANULARITY_BLIND_SPOT=true）：" + paraphraseNumbers);
            line("整段对整段", "袋 Dice " + Float.valueOf(wholeBag) + "，门槛 SIMILAR_BAG_DICE="
                    + Float.valueOf(TextCorpus.SIMILAR_BAG_DICE) + "（过线）；三元组 Dice "
                    + Float.valueOf(wholeDice) + "，门槛 " + Float.valueOf(TextCorpus.SIMILAR_DICE) + "（不过线）");
            line("切句后逐句对整句原话", "袋 Dice " + Float.valueOf(clauseBag[0]) + " / "
                    + Float.valueOf(clauseBag[lastClause]) + "，袋可达上界 " + Float.valueOf(clauseReach[0])
                    + " / " + Float.valueOf(clauseReach[lastClause])
                    + "（两半都在地板之下，第二半连可达上界这一档都没过，交集根本不数）");
            line("只动切句的对照", "把那个分号换成逗号、其余一字不改：" + shapeReport.hits.size()
                    + " 段命中 " + shapeReport.duplicateChars + "/" + shapeReport.comparedChars + " 字 = "
                    + pct(shapeReport.overallRate) + "；这一档差额就是粒度差的全部体量");
        } else {
            check(false, "轻改写段落的重复占比必须严格高于自写段落：" + paraphraseNumbers
                    + "；KNOWN_GRANULARITY_BLIND_SPOT 已经是 false，这一档就是硬断言，不许再降级");
        }

        // 4. 带引用的那一段：命中必须落进"引用区间内重复"那一桶。
        int quotedValid = TextCorpus.validCount(norm, quoteStart, quoteEnd);
        int quotedHit = duplicateIn(norm, hits, quoteStart, quoteEnd);
        check(quotedValid > 0 && quotedHit == quotedValid,
                "引号里的原文必须整块认出来：实测 " + quotedHit + "/" + quotedValid + " 字");
        check(ledger.citedDuplicateChars == quotedValid,
                "这部分必须记进 CharLedger 的引用桶：citedDuplicateChars=" + ledger.citedDuplicateChars
                        + "，引号内有效字符=" + quotedValid);
        check(ledger.uncitedDuplicateChars == ledger.duplicateChars - ledger.citedDuplicateChars
                        && ledger.excludingCitationsRate < ledger.overallRate,
                "去除引用重复比必须低于总相似度比：" + pct(ledger.excludingCitationsRate) + " 对比 "
                        + pct(ledger.overallRate) + "（引用桶 " + ledger.citedDuplicateChars + " 字）");

        // 5. 参考文献表：字数从分母里掉出去，而且排除确实在起作用。
        int referenceValid = TextCorpus.validCount(norm, references.start, references.end);
        check(structure.bibliographySections == 1 && structure.otherSections == 0,
                "稿件里必须恰好认出一节参考文献表：实测 bibliographySections="
                        + structure.bibliographySections + "，otherSections=" + structure.otherSections);
        check(structure.excludedChars == referenceValid,
                "结构排除的字数必须正好等于参考文献表那一段：实测 " + structure.excludedChars + " 对比 " + referenceValid);
        check(ledger.totalChars == wholeValid - referenceValid,
                "参考文献表的字数必须不在分母里：整篇 " + wholeValid + " 字 - 排除 " + referenceValid
                        + " 字 = 分母 " + ledger.totalChars + " 字（实测 ledger.totalChars）");
        check(references.duplicate == 0,
                "参考文献表一个字都不许进分子：实测命中 " + references.duplicate + " 字");
        TextCorpus.Report unguarded = corpus.match(text, citations, null);
        int unguardedReference = duplicateIn(norm, merged(unguarded.hits, text.length()),
                references.start, references.end);
        check(unguardedReference > 0,
                "拿掉结构排除后参考文献表确实会命中（实测 " + unguardedReference + " 字）："
                        + "所以上面那条归零是排除在起作用，不是碰巧没配上");

        // 6. 三个互斥桶闭合 100%。
        check(ledger.residual() == 0,
                "三个互斥桶必须闭合：residual=" + ledger.residual() + "（未引用的重复 "
                        + ledger.uncitedDuplicateChars + " + 引用内重复 " + ledger.citedDuplicateChars
                        + " + 自编 " + ledger.selfWrittenChars + " = 分母 " + ledger.totalChars + "）");
        check(Math.abs(ledger.rateResidual()) < 1e-9 && Math.abs(ledger.headlineResidual()) < 1e-9,
                "百分比口径同样闭合：rateResidual=" + ledger.rateResidual() + "，headlineResidual="
                        + ledger.headlineResidual());
        check(ledger.totalChars == matched.comparedChars && ledger.duplicateChars == matched.duplicateChars
                        && ledger.excludedChars == matched.excludedChars
                        && ledger.citedDuplicateChars == matched.citedDuplicateChars,
                "账本与 TextCorpus 必须出自同一次划分：ledger " + ledger.duplicateChars + "/" + ledger.totalChars
                        + " 对比 match " + matched.duplicateChars + "/" + matched.comparedChars
                        + "（排除 " + ledger.excludedChars + " 对比 " + matched.excludedChars + "）");

        // 7. 头条：有可比对象且有命中，率就不许是 0.00%。
        check(corpus.sentenceCount() > 0,
                "语料里必须有可比对象：入库句子 " + corpus.sentenceCount() + " 条（离线口径的 comparableCandidates）");
        check(!matched.hits.isEmpty(), "判据必须给得出命中段：实测 hits=" + matched.hits.size());
        check(ledger.overallRate > 0d && !"0.00%".equals(pct(ledger.overallRate)),
                "头条：可比对象 " + corpus.sentenceCount() + " 条、命中 " + matched.hits.size()
                        + " 段时，总相似度比必须大于 0——实测分子 " + ledger.duplicateChars + " / 分母 "
                        + ledger.totalChars + " = " + pct(ledger.overallRate) + "，自编率 "
                        + pct(ledger.selfWrittenRate));

        head("结论");
        line("总相似度比", pct(ledger.overallRate));
        line("去除引用重复比", pct(ledger.excludingCitationsRate));
        line("自编率", pct(ledger.selfWrittenRate));
        line("分子 / 分母 / 排除", ledger.duplicateChars + " / " + ledger.totalChars + " / " + ledger.excludedChars);
        line("致命断言失败 / 已知盲区", failures + " / " + knownBlindSpots);
        // SUMMARY 必须是最后打的那一行：tools/test-host.ps1 取日志里最后一条非空行当套件尾注，
        // 尾注里没有这一句，跑整套件的人就看不见"判据活着，但这一档改写形状漏了"。
        OUT.println("SUMMARY " + checks + " checks: " + (checks - failures - knownBlindSpots) + " passed, "
                + failures + " fatal failed, " + knownBlindSpots + " known blind spot reported "
                + "(verbatim " + copy.duplicate + "/" + copy.valid + " = " + pct(copy.rate())
                + ", quoted run " + quotedValid + " chars booked in the cited bucket, references " + referenceValid
                + " chars excluded from the " + ledger.totalChars + "-char denominator, ledger residual "
                + ledger.residual() + ", headline " + ledger.duplicateChars + "/" + ledger.totalChars + " = "
                + pct(ledger.overallRate) + "); verdict: judge alive, light paraphrase re-segmented at sentence "
                + "granularity undetected (whole-passage bag Dice " + wholeBag + " over the "
                + TextCorpus.SIMILAR_BAG_DICE + " floor, per-sentence " + clauseBag[0] + "/" + clauseBag[lastClause]
                + ", bagReach " + clauseReach[lastClause] + ", ';'->',' control " + shapeReport.duplicateChars + "/"
                + shapeReport.comparedChars + " = " + pct(shapeReport.overallRate) + ")");
        if (failures > 0) {
            throw new AssertionError("DetectionFloor failed: " + failures
                    + " fatal assertion(s) 断言失败（数字见上面的 [FAIL] 行）");
        }
    }

    /** 往稿件上追加一段，返回它在稿件串上的区间。段间空一行，与真文档的段落边界一致。 */
    private static Section section(StringBuilder ms, String label, String body) {
        if (ms.length() > 0) ms.append("\n\n");
        int start = ms.length();
        ms.append(body);
        return new Section(label, start, ms.length());
    }

    private static void wipe(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (int i = 0; i < files.length; i++) if (files[i].isFile()) files[i].delete();
    }

    private static int overlapping(ArrayList<TextCorpus.Hit> hits, int from, int to) {
        int count = 0;
        for (int i = 0; i < hits.size(); i++) {
            TextCorpus.Hit hit = hits.get(i);
            if (Math.max(hit.start, from) < Math.min(hit.end, to)) count++;
        }
        return count;
    }

    /** 命中区间先取并集，再裁到 [from,to)，最后按有效字符数字：与 CharLedger 的分子同一把尺。 */
    private static int duplicateIn(String norm, int[] merged, int from, int to) {
        int total = 0;
        for (int i = 0; i + 1 < merged.length; i += 2) {
            int lo = Math.max(from, merged[i]);
            int hi = Math.min(to, merged[i + 1]);
            if (hi > lo) total += TextCorpus.validCount(norm, lo, hi);
        }
        return total;
    }

    private static int[] merged(ArrayList<TextCorpus.Hit> hits, int length) {
        int[] raw = new int[hits.size() * 2];
        for (int i = 0; i < hits.size(); i++) {
            raw[i * 2] = hits.get(i).start;
            raw[i * 2 + 1] = hits.get(i).end;
        }
        return TextCorpus.mergeSpans(raw, length);
    }

    /**
     * 稿件侧的引用区间，与 DuplicateEngine.citationSpans 同一条路：编号条目行 + 引号里的显式引用。
     * 那边的 referenceEntry/quotations 都是包内私有，这里用公开的 TextCorpus.citationLike 配同一份引号对复刻，
     * 免得把测试挂在 DuplicateEngine 这个正在被改的类上。
     */
    private static int[] citationSpans(String text) {
        ArrayList<int[]> spans = new ArrayList<int[]>();
        int lineStart = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i < text.length() && text.charAt(i) != '\n') continue;
            String row = text.substring(lineStart, i).trim();
            if (row.length() > 0 && TextCorpus.citationLike(row)) spans.add(new int[]{ lineStart, i });
            lineStart = i + 1;
        }
        for (int q = 0; q < QUOTES.length; q++) {
            int at = 0;
            while (true) {
                int start = text.indexOf(QUOTES[q][0], at);
                if (start < 0) break;
                int end = text.indexOf(QUOTES[q][1], start + 1);
                if (end < 0) break;
                int stop = Math.min(end + 1, text.length());
                if (stop - start >= 8 && stop - start <= 4000) spans.add(new int[]{ start, stop });
                at = stop;
            }
        }
        int[] raw = new int[spans.size() * 2];
        for (int i = 0; i < spans.size(); i++) {
            raw[i * 2] = spans.get(i)[0];
            raw[i * 2 + 1] = spans.get(i)[1];
        }
        return TextCorpus.mergeSpans(raw, text.length());
    }

    /** 与 LocalLibrary.sourceOf 同一个形状的出处条目，给"只放一篇原句"那个对照组用。 */
    private static TextCorpus.Source sourceOf(String name) {
        TextCorpus.Source source = new TextCorpus.Source();
        source.engine = "local";
        source.id = "local:" + name;
        source.title = name;
        source.locator = name;
        source.year = "";
        source.authors = "";
        return source;
    }
    /** 三元组包含率：短的一侧有多少比例的三元组出现在长的一侧。与判据同一个算式，只是拿来打印。 */
    private static float containment(String a, String b) {
        long[] first = TextCorpus.gramsOf(TextCorpus.compactOf(a));
        long[] second = TextCorpus.gramsOf(TextCorpus.compactOf(b));
        if (first.length == 0 || second.length == 0) return 0f;
        int shared = 0;
        int i = 0;
        int j = 0;
        while (i < first.length && j < second.length) {
            if (first[i] == second[j]) {
                shared++;
                i++;
                j++;
            } else if (first[i] < second[j]) {
                i++;
            } else {
                j++;
            }
        }
        return (float) shared / (float) Math.min(first.length, second.length);
    }
}