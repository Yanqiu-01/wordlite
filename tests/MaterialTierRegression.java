package com.rikkahub.wordlite;

import java.io.File;
import java.util.ArrayList;

/**
 * 材料档的回归：同一轮查重里正文级、精要级、摘要级命中各记各的账——来源榜每行带档、三档字数相加等于分子、
 * 只有精要/只有摘要的那几行排在正文级后面、结果页与 HTML 与存档三处说的是同一句话，
 * 外加"这次到底比了哪些库、各多少篇"那张清单。全程不联网、不碰 Android，期望值全是手算的。
 *
 * 为什么钉这么死：这三档的覆盖面差着量级。同一篇真论文正文里的 505 字逐字抄进稿子，拿它的摘要比抓到
 * 0 字、拿它的正文比抓到 505 字（tests/MaterialTierProbe，数字在 docs/material-tiers.md）。
 * 把三档加成一个数，等于把"没比过的那部分正文"写成"比过且不重复"。
 */
public final class MaterialTierRegression {
    private static int checks;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
        System.out.println("PASS " + message);
    }

    private static void same(String actual, String expected, String message) {
        if (!expected.equals(actual))
            throw new AssertionError(message + "：期望「" + expected + "」，实到「" + actual + "」");
        checks++;
        System.out.println("PASS " + message);
    }

    private static void same(int actual, int expected, String message) {
        if (actual != expected)
            throw new AssertionError(message + "：期望 " + expected + "，实到 " + actual);
        checks++;
        System.out.println("PASS " + message + " = " + actual);
    }

    /** 正文级来源里那句。 */
    private static final String FULL_SENTENCE =
            "真空回流焊的升温斜率超过每分钟八度时，芯片表面的助焊剂会来不及排出气孔。";
    /** 精要级来源里那句（形状照万方「全文精要」里那种第三人称转述句）。 */
    private static final String DIGEST_SENTENCE =
            "作者认为升温过快会让助焊剂中的挥发分在界面残留，形成连续的气孔带。";
    /** 摘要级来源里那句。 */
    private static final String ABSTRACT_SENTENCE =
            "试验结果表明该涂层在中性盐雾环境中仍能维持较低的腐蚀电流密度。";
    /** 与三句话都不相干的填充文字：分母由它撑开，比率才不是一两句在跳。 */
    private static final String FILLER =
            "本节先记录试样编号与保温时间，再给出三次重复试验的载荷峰值与断裂位置，单位均为标准差。";
    private static final String FILLER_TWO =
            "夹具刚度不足会把试验机自身的振动带进读数，所以每一组都先做空载本底。";

    private static TextCorpus.Source source(String title, String engine, String material) {
        TextCorpus.Source source = new TextCorpus.Source();
        source.id = "probe:" + title;
        source.title = title;
        source.engine = engine;
        source.year = "2022";
        source.material = material;
        return source;
    }

    private static int valid(String text) {
        return TextCorpus.validCount(TextCorpus.normalize(text), 0, text.length());
    }

    private static SourceLedger ledgerOf(TextCorpus corpus, String query) {
        TextCorpus.Report matched = corpus.match(query, null);
        return SourceLedger.aggregate(matched.hits, TextCorpus.normalize(query), matched.comparedChars);
    }

    private static int sum(SourceLedger ledger) {
        int total = 0;
        for (int i = 0; i < ledger.rows.size(); i++) total += ledger.rows.get(i).duplicateChars;
        return total;
    }

    private static DocxDocument.ParagraphBlock paragraph(int index, String text) {
        DocxDocument.ParagraphBlock value = new DocxDocument.ParagraphBlock();
        value.index = index;
        value.text = text;
        value.styleId = "";
        value.styleName = "";
        return value;
    }

    private static void add(DocxDocument document, DocxDocument.ParagraphBlock block) {
        document.blocks.add(block);
        document.paragraphs.add(block);
    }

    public static void main(String[] args) throws Exception {
        splitLedger();
        tiersOutrankCharCounts();
        ungradedReadsAsBody();
        wordingKeepsThreeRulersApart();
        foldKeepsTheSplit();
        inventoryNamesEveryLibrary();
        scanEndToEnd();
        archivedRecordKeepsIt();
        oldRecordsReadAsBody();
        nothingComparedSaysNothing();
        System.out.println("SUMMARY " + checks
                + " 条材料档断言通过；两个对话框与报告中心的排版只过了编译，真机屏幕不在本机模拟");
    }

    // ---- 1. 来源榜按档分开记账 ----

    private static void splitLedger() {
        String query = FILLER + FULL_SENTENCE + DIGEST_SENTENCE
                + "这一段是本文自己的讨论，接着说夹具刚度对读数的影响。" + ABSTRACT_SENTENCE;
        TextCorpus corpus = new TextCorpus();
        corpus.add(source("真空回流焊的气孔控制", "local", DuplicateEngine.MATERIAL_FULL),
                FULL_SENTENCE + "降温阶段同样要压住斜率。");
        corpus.add(source("钎焊界面气孔的成因分析", "wanfang", DuplicateEngine.MATERIAL_DIGEST),
                DIGEST_SENTENCE + "该文用扫描电镜看了截面。");
        corpus.add(source("中性盐雾涂层试验", "cnki", DuplicateEngine.MATERIAL_ABSTRACT),
                ABSTRACT_SENTENCE + "本文采用电化学阻抗谱跟踪涂层老化。");
        SourceLedger ledger = ledgerOf(corpus, query);
        same(ledger.rows.size(), 2 + 1, "三篇各占来源榜一行");
        same(sum(ledger), ledger.duplicateChars, "Σ 各行 == 分子");
        same(ledger.fullDuplicateChars + ledger.digestDuplicateChars + ledger.abstractDuplicateChars,
                ledger.duplicateChars, "三档相加 == 总重复字符（合起来才是分子）");
        same(ledger.fullPapers + ledger.digestPapers + ledger.abstractPapers, ledger.paperCount,
                "三档命中篇数相加 == 命中篇数");
        SourceLedger.Row full = ledger.rows.get(0);
        SourceLedger.Row digest = ledger.rows.get(1);
        SourceLedger.Row abstractRow = ledger.rows.get(2);
        same(full.duplicateChars, valid(FULL_SENTENCE), "正文级那一行的字数就是那句正文的有效字数");
        same(digest.duplicateChars, valid(DIGEST_SENTENCE), "精要级那一行的字数就是那句精要的有效字数");
        same(abstractRow.duplicateChars, valid(ABSTRACT_SENTENCE), "摘要级那一行的字数就是那句摘要的有效字数");
        same(full.fullChars, full.duplicateChars, "正文级那一行没有别的档的字");
        same(digest.digestChars, digest.duplicateChars, "精要级那一行整行都是精要级");
        same(abstractRow.abstractChars, abstractRow.duplicateChars, "摘要级那一行整行都是摘要级");
        check(!full.digestOnly() && !full.abstractOnly(), "正文级那一行既不是只有精要也不是只有摘要");
        check(digest.digestOnly(), "精要级那一行算只有精要可读");
        check(abstractRow.abstractOnly(), "摘要级那一行算只有摘要可比");
        same(SourceLedger.materialLabel(full), "正文", "正文行的标注");
        same(SourceLedger.materialLabel(digest), "只有精要可读", "精要行的标注");
        same(SourceLedger.materialLabel(abstractRow), "只有摘要可比", "摘要行的标注");
        same(digest.tierRank(), 1, "排序档序：精要级是 1");
        same(abstractRow.tierRank(), 2, "排序档序：摘要级是 2");
        same(full.tierRank(), 0, "排序档序：正文级是 0");
        check(Math.abs(digest.fullShare(ledger.comparedChars)) < 1e-9,
                "精要行的正文级比率是 0：那一档一行正文都没比过");
        check(Math.abs(digest.digestShare(ledger.comparedChars) - digest.share(ledger.comparedChars)) < 1e-9,
                "整行只有精要时，精要级比率就是该篇比率");
        check(ledger.abstractDuplicateChars == valid(ABSTRACT_SENTENCE)
                        && ledger.digestDuplicateChars == valid(DIGEST_SENTENCE)
                        && ledger.fullDuplicateChars == valid(FULL_SENTENCE),
                "分档字数逐个对得上：正文 " + ledger.fullDuplicateChars + " / 精要 "
                        + ledger.digestDuplicateChars + " / 摘要 " + ledger.abstractDuplicateChars);
    }

    // ---- 2. 三档的先后与字数无关 ----

    private static void tiersOutrankCharCounts() {
        String longAbstract = ABSTRACT_SENTENCE
                + "涂层在划伤后的自修复速率与硅烷含量正相关，含量过高反而让膜层发脆，这一点与盐雾结果一致。";
        String middleDigest = DIGEST_SENTENCE + "该文还用扫描电镜看了截面的形貌。";
        String query = FILLER + FULL_SENTENCE + middleDigest + longAbstract;
        TextCorpus corpus = new TextCorpus();
        corpus.add(source("最短的正文那篇", "local", DuplicateEngine.MATERIAL_FULL), FULL_SENTENCE);
        corpus.add(source("中等的精要那篇", "wanfang", DuplicateEngine.MATERIAL_DIGEST), middleDigest);
        corpus.add(source("最长的摘要那篇", "cnki", DuplicateEngine.MATERIAL_ABSTRACT), longAbstract);
        SourceLedger ledger = ledgerOf(corpus, query);
        same(ledger.rows.size(), 3, "三篇各一行");
        check("最短的正文那篇".equals(ledger.rows.get(0).title), "来源榜第一行是正文级那篇");
        check("中等的精要那篇".equals(ledger.rows.get(1).title), "第二行是精要级那篇");
        check("最长的摘要那篇".equals(ledger.rows.get(2).title), "第三行才是字数最多的那篇（只有摘要可比）");
        check(ledger.rows.get(2).duplicateChars > ledger.rows.get(0).duplicateChars,
                "摘要级那篇的字数比正文级那篇多（" + ledger.rows.get(2).duplicateChars + " > "
                        + ledger.rows.get(0).duplicateChars + "）：压过字数的只有档位");
        check(ledger.rows.get(1).duplicateChars > ledger.rows.get(0).duplicateChars,
                "精要级那篇的字数也比正文级那篇多，照样排在后面");
    }

    // ---- 3. 没记档的老语料整份按正文算 ----

    private static void ungradedReadsAsBody() {
        String longBody = FULL_SENTENCE + "降温阶段也要压住斜率，否则气孔率会回升到原来的三倍。";
        TextCorpus corpus = new TextCorpus();
        corpus.add(source("没记档甲", "local", ""), FULL_SENTENCE);
        corpus.add(source("没记档乙", "openalex", null), longBody);
        SourceLedger ledger = ledgerOf(corpus, FILLER + FULL_SENTENCE + longBody);
        same(ledger.rows.size(), 2, "两篇各一行");
        same(ledger.fullDuplicateChars, ledger.duplicateChars, "没记档的整份按正文级算");
        same(ledger.digestDuplicateChars + ledger.abstractDuplicateChars, 0,
                "新加的两档不会让老语料凭空少字");
        check(ledger.rows.get(0).duplicateChars > ledger.rows.get(1).duplicateChars,
                "同一档内按字数排：字数多的那篇在第一行");
        same(ledger.rows.get(0).material, DuplicateEngine.MATERIAL_FULL, "没记档的行的档位写成正文");
    }

    // ---- 4. 一句话写法：三把尺并列，不是合成一个数 ----

    private static void wordingKeepsThreeRulersApart() {
        same(DuplicateEngine.materialSplitLine(0, 0, 0, 1000, 0, 0, 0), "", "一处命中都没有时那一行不给界面画");
        String all = DuplicateEngine.materialSplitLine(400, 88, 112, 6400, 3, 1, 2);
        check(all.indexOf("正文级 400 字") >= 0 && all.indexOf("精要级 88 字") >= 0
                        && all.indexOf("摘要级 112 字") >= 0, "三档各报各的字数：" + all);
        check(all.indexOf("600") < 0 && all.indexOf("512") < 0 && all.indexOf("6.02") < 0,
                "那句话里不许出现相加出来的总数：" + all);
        check(all.indexOf("6.25%") >= 0 && all.indexOf("1.38%") >= 0 && all.indexOf("1.75%") >= 0,
                "三档各自的比率都从整篇分母算出来：" + all);
        check(all.indexOf("1 篇只有精要可读") >= 0, "精要级那一篇要点出它是精要：" + all);
        check(all.indexOf("命中不含正文级") < 0, "有正文级命中时不加那句前缀：" + all);
        String noBody = DuplicateEngine.materialSplitLine(0, 88, 112, 6400, 0, 1, 2);
        check(noBody.indexOf("命中不含正文级：") == 0, "没有正文级命中时先说这一句：" + noBody);
        check(noBody.indexOf("正文级 ") < 0, "没有正文级命中时不写正文级那半句：" + noBody);
        String onlyAbstract = DuplicateEngine.materialSplitLine(0, 0, 112, 6400, 0, 0, 2);
        check(onlyAbstract.indexOf("精要级") < 0 && onlyAbstract.indexOf("摘要级 112 字") >= 0,
                "只剩一档时那一档之外的半句都不出现：" + onlyAbstract);
    }

    // ---- 5. 折叠行也得带着分档走 ----

    private static void foldKeepsTheSplit() {
        String[] lines = new String[SourceLedger.MAX_ROWS + 3];
        String[] topics = { "振动台的夹具刚度", "盐雾箱的喷雾流量", "拉伸试样的标距长度", "焊膏的印刷厚度",
                "回流焊的峰值温度", "切削液的喷嘴角度", "涂层的烘烤时间", "阻抗谱的扰动幅值",
                "疲劳试验的应力比", "超声清洗的频率", "金相试样的抛光时间", "测力仪的采样频率",
                "热循环的温度上限", "划痕试验的载荷速率", "盐雾后的称重天平" };
        StringBuilder query = new StringBuilder();
        TextCorpus corpus = new TextCorpus();
        for (int i = 0; i < lines.length; i++) {
            lines[i] = topics[i] + "是这一组试验里唯一被改变的参数，其余条件保持不变，共记录六十个循环。";
            query.append(lines[i]);
            // 最后两组是"只有精要"与"只有摘要"，它们必然被折到折叠行里去。
            String material = i < lines.length - 2 ? DuplicateEngine.MATERIAL_FULL
                    : (i == lines.length - 2 ? DuplicateEngine.MATERIAL_DIGEST : DuplicateEngine.MATERIAL_ABSTRACT);
            corpus.add(source(topics[i] + "那篇", i < lines.length - 2 ? "local" : "cqvip", material), lines[i]);
        }
        SourceLedger ledger = ledgerOf(corpus, query.toString());
        same(ledger.paperCount, lines.length, "折叠前的篇数就是语料篇数");
        same(ledger.rows.size(), SourceLedger.MAX_ROWS + 1, "表里最多 " + (SourceLedger.MAX_ROWS + 1) + " 行");
        same(ledger.fullPapers + ledger.digestPapers + ledger.abstractPapers, lines.length,
                "分档篇数在折叠之后仍是全量");
        same(ledger.digestDuplicateChars, valid(lines[lines.length - 2]),
                "精要级字数按折叠前的全量算，折叠不许把它藏起来");
        same(ledger.abstractDuplicateChars, valid(lines[lines.length - 1]),
                "摘要级字数同样按折叠前的全量算");
        SourceLedger.Row tail = ledger.rows.get(ledger.rows.size() - 1);
        check(tail.others, "最后一行是合计行");
        same(tail.fullChars + tail.digestChars + tail.abstractChars, tail.duplicateChars, "合计行自己也分档");
        check(tail.digestChars + tail.abstractChars > 0,
                "被折掉的正是那两篇薄材料的，合计行里看得见：" + tail.digestChars + " + " + tail.abstractChars);
        check(ledger.fullDuplicateChars + ledger.digestDuplicateChars + ledger.abstractDuplicateChars
                        == ledger.duplicateChars && ledger.duplicateChars == sum(ledger),
                "折叠之后三本账还对得上：" + ledger.duplicateChars);
    }

    // ---- 6. 这次到底比了哪些库、各多少篇 ----

    private static void inventoryNamesEveryLibrary() {
        TextCorpus corpus = new TextCorpus();
        corpus.add(source("自建库原文一篇", "local", DuplicateEngine.MATERIAL_FULL), FULL_SENTENCE + FILLER);
        corpus.add(source("自建库题录一篇", "local", DuplicateEngine.MATERIAL_ABSTRACT), ABSTRACT_SENTENCE);
        corpus.add(source("万方精要一篇", "wanfang", DuplicateEngine.MATERIAL_DIGEST), DIGEST_SENTENCE);
        corpus.add(source("没记档的一篇", "openalex", ""), FILLER_TWO);
        CorpusLedger ledger = CorpusLedger.aggregate(corpus);
        same(ledger.papers, 4, "清单数的篇数 == 语料篇数");
        same(ledger.groups, 4, "四个来源档就是四组");
        same(ledger.fullPapers, 1, "正文一篇");
        same(ledger.digestPapers, 1, "精要一篇");
        same(ledger.abstractPapers, 1, "摘要一篇");
        same(ledger.otherPapers, 1, "没记档的那篇单独一组，不冒充正文库");
        same(ledger.rows.get(0).label(), "自建库 · 正文", "第一组的组名");
        same(ledger.rows.get(1).label(), "自建库 · 只有摘要可比", "同库不同档要分成两组");
        same(ledger.rows.get(2).label(), "万方数据 · 只有精要可读", "精要组的组名");
        same(ledger.rows.get(3).label(), "OpenAlex · 未记档", "没记档那组的组名");
        same(ledger.rows.get(0).chars, valid(FULL_SENTENCE + FILLER), "每组带的字数是这一篇拿来比对的字数");
        String line = ledger.summaryLine();
        check(line.indexOf("本次比对材料 4 篇（4 组）") == 0, "清单那一行开头就说总数：" + line);
        check(line.indexOf("万方数据 · 只有精要可读 1 篇") >= 0, "哪几库只有精要，这一行里直接看得见：" + line);
        same(CorpusLedger.aggregate(new TextCorpus()).summaryLine(), "", "空语料那一行不给界面画");
        same(CorpusLedger.sourceLabel("local"), "自建库", "自建库不露 local 这个键");
        same(CorpusLedger.materialLabel(DuplicateEngine.MATERIAL_DIGEST), "只有精要可读", "精要档的名字");
        same(CorpusLedger.materialLabel(DuplicateEngine.MATERIAL_RECORD), "只有题录", "仅题录档的名字");
    }

    // ---- 7. 一次真跑完的离线查重：报告、清单、注记、HTML ----

    private static void scanEndToEnd() {
        DocxDocument document = new DocxDocument();
        add(document, paragraph(0, FILLER));
        add(document, paragraph(1, FULL_SENTENCE + FILLER_TWO));
        add(document, paragraph(2, DIGEST_SENTENCE));
        add(document, paragraph(3, ABSTRACT_SENTENCE));
        TextCorpus corpus = new TextCorpus();
        corpus.add(source("真空回流焊的气孔控制", "local", DuplicateEngine.MATERIAL_FULL),
                FULL_SENTENCE + "降温段也要压住斜率，否则气孔率回升。");
        TextCorpus.Source wanfang = source("钎焊界面气孔的成因分析", "wanfang", DuplicateEngine.MATERIAL_DIGEST);
        wanfang.locator = "https://d.wanfangdata.com.cn/periodical/hjclxb202203001";
        corpus.add(wanfang, "全文精要\n" + DIGEST_SENTENCE + "该文用扫描电镜观察了界面形貌。");
        TextCorpus.Source cnki = source("中性盐雾涂层试验", "cnki", DuplicateEngine.MATERIAL_ABSTRACT);
        corpus.add(cnki, "摘要\n" + ABSTRACT_SENTENCE + "本文用阻抗谱跟踪涂层老化过程。");
        DuplicateEngine.Report report = DuplicateEngine.scan(TextSelection.all(document), corpus,
                false, null, null, null, null);
        check(!report.retrievalIncomplete, "自建库有料，离线查重要给出指标而不是未完成");
        same(report.hits.size(), 3, "三处命中");
        same(report.fullTextDuplicateChars + report.digestDuplicateChars + report.abstractDuplicateChars,
                report.duplicateChars, "报告上的分子分了三档，相加还是那个分子");
        same(report.fullTextHitPapers, 1, "正文级命中一篇");
        same(report.digestHitPapers, 1, "精要级命中一篇");
        same(report.abstractHitPapers, 1, "摘要级命中一篇");
        boolean notedDigest = false;
        boolean notedAbstract = false;
        for (int i = 0; i < report.notes.size(); i++) {
            if (report.notes.get(i).indexOf("只有精要可读") >= 0) notedDigest = true;
            if (report.notes.get(i).indexOf("只有摘要可比") >= 0) notedAbstract = true;
        }
        check(notedDigest, "注记里点出那一篇只有精要可读，并说清它是渲染回来的那一段");
        check(notedAbstract, "注记里点出那一篇只有摘要可比");
        check(report.inventory != null && report.inventory.papers == 3,
                "报告带着比对材料清单：" + (report.inventory == null ? "空" : report.inventory.papers + " 篇"));
        String line = DuplicateEngine.inventoryLine(report);
        check(line.indexOf("自建库 · 正文 1 篇") >= 0 && line.indexOf("万方数据 · 只有精要可读 1 篇") >= 0
                        && line.indexOf("知网") >= 0, "清单点到每个库与每个档：" + line);
        String split = DuplicateEngine.materialSplitLine(report);
        check(split.indexOf("正文级") >= 0 && split.indexOf("精要级") >= 0 && split.indexOf("摘要级") >= 0,
                "分档那一行三档都在：" + split);
        String html = CheckReport.html("测试稿.docx", report);
        check(html.indexOf("比对材料 3 篇") >= 0, "HTML 里有比对材料那张表");
        check(html.indexOf("<th>可比材料</th>") >= 0, "来源榜多出来的那一列在表头里");
        check(html.indexOf("只有精要可读") >= 0 && html.indexOf("只有摘要可比") >= 0,
                "HTML 里点得出哪几篇只有精要、哪几篇只有摘要");
        int from = html.indexOf("来源榜 &#183; 按文献");
        int to = html.indexOf("<h2>候选文献");
        check(from >= 0 && to > from, "HTML 里排出了来源榜那一张表");
        String board = html.substring(from, to);
        check(board.indexOf("真空回流焊的气孔控制") < board.indexOf("钎焊界面气孔的成因分析")
                        && board.indexOf("钎焊界面气孔的成因分析") < board.indexOf("中性盐雾涂层试验"),
                "HTML 里的行序也是先正文、再精要、最后摘要");
        check(board.indexOf("正文级 3 篇") >= 0 || board.indexOf("正文级 ") >= 0, "表下面那句分档小结在");
    }

    // ---- 8. 存档记录：写盘、读回、重画 ----

    private static void archivedRecordKeepsIt() throws Exception {
        File root = new File("artifacts/tests/material-tier");
        deleteRecursively(root);
        ReportStore store = new ReportStore(root);
        DuplicateEngine.Report report = new DuplicateEngine.Report();
        report.sourceText = FILLER + DIGEST_SENTENCE;
        report.comparedChars = valid(report.sourceText);
        report.digestDuplicateChars = valid(DIGEST_SENTENCE);
        report.digestHitPapers = 2;
        report.detectedAt = "2026-10-09 12:00";
        TextCorpus corpus = new TextCorpus();
        corpus.add(source("题录导进来的一篇", "local", DuplicateEngine.MATERIAL_ABSTRACT), ABSTRACT_SENTENCE);
        corpus.add(source("万方渲染的一篇", "wanfang", DuplicateEngine.MATERIAL_DIGEST), DIGEST_SENTENCE);
        report.inventory = CorpusLedger.aggregate(corpus);
        ReportStore.Record saved = store.save(ReportStore.recordFor(report, "分档.docx"));
        check(saved != null, "记录落盘");
        ReportStore.Record back = store.record(saved.id);
        check(back != null, "记录读得回来");
        same(back.digestDuplicateChars, valid(DIGEST_SENTENCE), "读回来的精要级字数还是那个数");
        same(back.digestHitPapers, 2, "读回来的精要级篇数还是 2");
        same(back.fullTextDuplicateChars + back.abstractDuplicateChars, 0, "另外两档没字就是没字");
        same(back.materials.size(), 2, "比对材料清单原样回来");
        same(back.materials.get(1).label(), "万方数据 · 只有精要可读", "清单里精要那组的组名原样回来");
        same(back.materialsPapers, 2, "清单的总篇数原样回来");
        ReportStore.Record again = ReportStore.Record.fromJson(ApiJson.parse(saved.toJson()));
        same(again.digestDuplicateChars, back.digestDuplicateChars, "同一段 JSON 两次读回的精要级字数一致");
        String line = DuplicateEngine.materialSplitLine(again.fullTextDuplicateChars,
                again.digestDuplicateChars, again.abstractDuplicateChars, again.comparedChars,
                again.fullTextHitPapers, again.digestHitPapers, again.abstractHitPapers);
        check(line.indexOf("精要级 " + valid(DIGEST_SENTENCE) + " 字") >= 0, "存档重画出的那一行还是那句话：" + line);
        deleteRecursively(root);
    }

    // ---- 9. 老记录里没有的键按正文读，不编数 ----

    private static void oldRecordsReadAsBody() {
        String legacy = "{\"version\":1,\"id\":\"x1\",\"fileName\":\"老记录.docx\","
                + "\"ledger\":{\"compared\":100,\"duplicate\":40},"
                + "\"sources\":[{\"key\":\"k1\",\"title\":\"没记档的老行\",\"engine\":\"local\","
                + "\"duplicateChars\":40,\"hitCount\":1,\"sourceCount\":1,\"firstStart\":3,\"share\":40.0}]}";
        ReportStore.Record back = ReportStore.Record.fromJson(ApiJson.parse(legacy));
        check(back != null, "老记录读得回来");
        same(back.sources.size(), 1, "老来源行读回来一行");
        same(back.sources.get(0).material.length(), 0, "老记录里没有档位，读回来是空串");
        same(back.sources.get(0).fullChars + back.sources.get(0).digestChars
                + back.sources.get(0).abstractChars, 0, "老记录里没有分档字数，读回来全是 0");
        same(back.materials.size(), 0, "老记录里没有材料清单，读回来是空表而不是假装有");
        same(DuplicateEngine.materialSplitLine(back.fullTextDuplicateChars, back.digestDuplicateChars,
                back.abstractDuplicateChars, back.comparedChars, 0, 0, 0), "",
                "老记录重画时那一行不给界面画，不编一个数出来");
    }

    // ---- 10. 什么都没比时一个数都不许冒出来 ----

    private static void nothingComparedSaysNothing() {
        DocxDocument document = new DocxDocument();
        add(document, paragraph(0, FILLER));
        DuplicateEngine.Report report = DuplicateEngine.scan(TextSelection.all(document), new TextCorpus(),
                false, null, null, null, null);
        check(report.retrievalIncomplete, "空自建库的离线查重是未完成查重");
        same(report.fullTextDuplicateChars + report.digestDuplicateChars + report.abstractDuplicateChars,
                0, "没有命中就没有分档字数");
        same(DuplicateEngine.materialSplitLine(report), "", "分档那一行不画");
        same(DuplicateEngine.inventoryLine(report), "", "空语料的清单那一行也不画");
        check(report.inventory != null && report.inventory.papers == 0, "清单是空的，不是 null");
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (int i = 0; i < children.length; i++) deleteRecursively(children[i]);
        file.delete();
    }
}