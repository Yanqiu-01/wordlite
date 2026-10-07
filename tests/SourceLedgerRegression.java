package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.Map;

/**
 * 来源榜（0.5.4）回归：Σ 各篇 == duplicateChars、跨检索源同一篇并一条、DOI 否决不许误并、
 * 排序确定性、与「按检索源分布」共用一套真相、指纹带与句级命中不双算、报告三种空态。
 * 全程不联网、不碰 Android，语料全是手写的，每条期望值都是手算的整数——写 >= 0 那种断言挡不住任何回归。
 */
public final class SourceLedgerRegression {
    private static int count;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        count++;
        System.out.println("PASS " + message);
    }

    private static TextCorpus.Source source(String id, String title, String engine, String locator) {
        TextCorpus.Source source = new TextCorpus.Source();
        source.id = id;
        source.title = title;
        source.engine = engine;
        source.locator = locator;
        source.year = "2021";
        source.authors = "张三";
        return source;
    }

    private static int valid(String text) {
        return TextCorpus.validCount(TextCorpus.normalize(text), 0, text.length());
    }

    /** 来源榜各行字符数之和（含"其余 N 篇合计"那一行）。 */
    private static int sum(SourceLedger ledger) {
        int total = 0;
        for (int i = 0; i < ledger.rows.size(); i++) total += ledger.rows.get(i).duplicateChars;
        return total;
    }

    /** 逐行的 key 序列：两次运行必须逐字符相同，否则说明排序还依赖哈希桶序。 */
    private static String keySequence(SourceLedger ledger) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < ledger.rows.size(); i++) out.append(ledger.rows.get(i).key).append('|');
        return out.toString();
    }

    /** 来源榜按检索源拆开再合回去。 */
    private static int perEngineTotal(SourceLedger ledger, TextCorpus.Report report) {
        int total = 0;
        for (Map.Entry<String, Double> entry : report.byEngine.entrySet())
            total += ledger.duplicateCharsFor(entry.getKey());
        return total;
    }

    /** 账本与 byEngine 同一套真相：把两套算法的归属绑在一起，谁改了另一边走神就在这里断。 */
    private static void crossCheck(String name, TextCorpus corpus, String query) {
        TextCorpus.Report report = corpus.match(query, null);
        SourceLedger ledger = SourceLedger.aggregate(report.hits, TextCorpus.normalize(query), report.comparedChars);
        boolean same = !report.byEngine.isEmpty();
        for (Map.Entry<String, Double> entry : report.byEngine.entrySet()) {
            int fromLedger = ledger.duplicateCharsFor(entry.getKey());
            if (Math.abs(fromLedger * 100d / report.comparedChars - entry.getValue().doubleValue()) >= 1e-9)
                same = false;
        }
        check(same, name + "：来源榜按检索源汇总等于「按检索源分布」那一列，两张表不会各说各话");
        check(perEngineTotal(ledger, report) == ledger.duplicateChars
                        && perEngineTotal(ledger, report) == report.duplicateChars,
                name + "：按检索源拆开再合回去仍然是同一个总重复字符数");
    }

    // ---- 夹具 ----

    /** 一条 31 个有效字符的逐字复制：句级与指纹带两条路都会命中同一段。 */
    private static final String VERBATIM = "取样位置固定在接头中心两侧，每次试验都记录峰值载荷与断裂位置。";

    private static final String LINE_A = "深度学习模型的训练过程需要大量标注数据，否则模型很难收敛到稳定状态。";
    private static final String LINE_B = "把特征工程交给领域专家手工完成，是早期系统的常见做法。";

    private static final String HEAD = "本章先给出总体框架，再逐节展开细节说明。";
    private static final String SENT_ONE = "混响补偿把窗函数的宽度随噪声强度自动调整。";
    private static final String TAIL = "以下数据来自三次重复实验，单位为标准差。";
    private static final String SENT_TWO = "在强噪声下它比固定窗口的估计误差小两成。";
    private static final String TWIN_QUERY = HEAD + SENT_ONE + TAIL + SENT_TWO;
    private static final String TWIN_TITLE = "混响补偿的自适应窗口方法";
    private static final String TWIN_DOI = "10.1000/dd.2021.001";

    /** 13 行、每行 20 个有效字符，凑成"必须折叠"的那张表：13 * 20 = 260 个有效字符。 */
    private static final String[] FOLD_LINES = {
            "保温时间每增加十分钟化合物层增厚了两微米",
            "抗拉强度随外载荷上升断裂位置偏向热影响区",
            "采样频率取每毫秒一次峰值载荷读取误差半牛",
            "窗口宽度随噪声自动调信噪比改善约六个分贝",
            "数据集划分为训练验证留出比例取两成不参与",
            "门控注意力抑制了背景召回率提高四个百分点",
            "重采样统一到毫米体素层厚差异对指标影响小",
            "交叉验证用五折分层法折间标准差不超零点三",
            "梯度裁剪阈值取五单位训练曲线不再出现尖峰",
            "硬编码路径抽成配置项回归用例覆盖了主流程",
            "日志级别按模块设两档复现问题只保留告警级",
            "缓存失效时间取三十秒命中率维持在九成以上",
            "标签由两名医师背对背给出结论取第三人裁定",
    };
    private static final String[] FOLD_ENGINES = { "openalex", "crossref", "cqvip", "wanfang" };

    private static TextCorpus.Source foldSource(int index) {
        return source("D" + (index + 1), "折叠来源" + (index + 1), FOLD_ENGINES[index % FOLD_ENGINES.length],
                "https://doi.org/10.1000/led.2021.0" + (index + 1));
    }

    private static String foldKey(int index) {
        return "10.1000/led.2021.0" + (index + 1);
    }

    /** 一段话一份快照：DuplicateEngine.scan 只认 TextSelection，而它只能从 DocxDocument 造出来。 */
    private static TextSelection selection(String text) {
        DocxDocument document = new DocxDocument();
        DocxDocument.ParagraphBlock block = new DocxDocument.ParagraphBlock();
        block.index = 0;
        block.text = text;
        document.blocks.add(block);
        document.paragraphs.add(block);
        return TextSelection.all(document);
    }

    private static String foldQuery() {
        StringBuilder query = new StringBuilder();
        for (int i = 0; i < FOLD_LINES.length; i++) {
            if (i > 0) query.append('\n');
            query.append(FOLD_LINES[i]);
        }
        return query.toString();
    }

    private static TextCorpus foldCorpus() {
        TextCorpus corpus = new TextCorpus();
        for (int i = 0; i < FOLD_LINES.length; i++) corpus.add(foldSource(i), FOLD_LINES[i]);
        return corpus;
    }

    /** 甲篇的整句命中（句级路）与乙篇的指纹带部分重叠：带子只该拿到甲没盖住的那一段。 */
    private static final String DOC_P = "保温时间超过四十分钟之后反应层明显增厚，接头强度下降。";
    private static final String DOC_Q = "接头强度下降。随后界面金属间化合物沿晶界碎裂，断口形貌随之改变。";
    private static final String OVERLAP_TAIL = "这一现象在两组对照样品里都稳定复现，且与保温时间、压力峰值和冷却速率都没有明显的相关性，"
            + "我们把它归因于晶界处的元素偏聚。";
    private static final String OVERLAP_QUERY = DOC_P + "随后界面金属间化合物沿晶界碎裂，断口形貌随之改变，"
            + OVERLAP_TAIL;

    public static void main(String[] args) {
        conservation();
        merging();
        folding();
        ordering();
        bandVersusSentence();
        longSentence();
        reportHtml();
        System.out.println("SUMMARY " + count + " assertions passed"
                + " (来源榜只在 host JVM 上跑：检索链路与界面高亮不在本用例范围内).");
    }

    /** 账本与分子同口径：Σ 各篇 == duplicateChars，而且这个数是手算出来的。 */
    private static void conservation() {
        check(valid(VERBATIM) == 31, "夹具先自证：这条逐字复制是 31 个有效字符");

        TextCorpus both = new TextCorpus();
        both.add(source("fp4", "重复计数的对照", "local", ""), VERBATIM);
        String query = VERBATIM + "后面是我们自己写的分析部分，用来把分母撑大一些。";
        TextCorpus.Report once = both.match(query, null);
        SourceLedger ledger = SourceLedger.aggregate(once.hits, TextCorpus.normalize(query), once.comparedChars);
        check(once.duplicateChars == 31, "两套算法命中同一段时分子只算一次（钉死 31 个有效字符）");
        check(ledger.rows.size() == 1, "同一篇文献的句级命中与指纹带只占来源榜一行");
        check(sum(ledger) == 31 && ledger.duplicateChars == 31 && sum(ledger) == once.duplicateChars,
                "来源榜各篇之和等于手算的 31 且等于 duplicateChars：两套算法不会把分子翻倍");
        check(ledger.rows.get(0).hitCount == 1 && ledger.rows.get(0).sourceCount == 1,
                "指纹带被句级命中吃掉之后不会多出第二处命中，也不会多出第二个题录");
        check(ledger.rows.get(0).engineKeys.indexOf("local") == 0
                        && ledger.rows.get(0).engineChars.get(0).intValue() == 31,
                "自建库来源（engine 为空）在账本里记成 local，31 个字符全在它名下");
        crossCheck("自建库单篇", both, query);

        TextCorpus cross = new TextCorpus();
        cross.add(source("a", "来源A", "openalex", ""), LINE_A);
        cross.add(source("b", "来源B", "crossref", ""), LINE_B);
        String twoQuery = LINE_A + LINE_B;
        TextCorpus.Report twoReport = cross.match(twoQuery, null);
        SourceLedger two = SourceLedger.aggregate(twoReport.hits, TextCorpus.normalize(twoQuery),
                twoReport.comparedChars);
        check(valid(LINE_A) == 34 && valid(LINE_B) == 27 && two.comparedChars == 61,
                "夹具的分母是手算的 61 个有效字符（34 + 27）");
        check(two.rows.size() == 2, "两篇相邻但不同的文献各占一行，不会因为相邻并段混成一行");
        check(two.rows.get(0).duplicateChars == 34 && two.rows.get(1).duplicateChars == 27,
                "两篇分别贡献手算的 34 与 27 个有效字符，按篇分账而不是按检索源");
        check(sum(two) == twoReport.duplicateChars && sum(two) == 61,
                "两篇相加正好等于总重复字符 61，既没漏也没重");
        check(Math.abs(two.rows.get(0).share(61) - 34 * 100d / 61) < 1e-9,
                "该篇重复率用整篇有效字数 61 当分母：34 / 61");
        check(Math.abs(two.rows.get(0).share(61) + two.rows.get(1).share(61) - 100d) < 1e-9,
                "两篇的该篇重复率相加等于总相似度比，这就是分母统一的用处");
        check(two.rows.get(0).firstStart == 0 && two.rows.get(0).longestStart == 0
                        && two.rows.get(0).longestEnd == 34,
                "最长命中区间记的是这一篇自己那一处 0..34，拿去当命中样例");
        check(two.rows.get(0).duplicateChars >= two.rows.get(1).duplicateChars,
                "来源榜按重复字符降序排，34 字那篇在前面");
        crossCheck("两个检索源各一篇", cross, twoQuery);
    }

    /** 跨检索源归并只用 CandidateRanker 那一套键：该合的必须合，不该合的一行都不许合。 */
    private static void merging() {
        check(valid(HEAD) == 20 && valid(SENT_ONE) == 21 && valid(TAIL) == 20 && valid(SENT_TWO) == 20,
                "夹具的四段分别是手算的 20 / 21 / 20 / 20 个有效字符，分母 81");
        TextCorpus.Source openAlex = source("A1", TWIN_TITLE, "openalex", "https://doi.org/" + TWIN_DOI);
        TextCorpus.Source vip = source("91234", TWIN_TITLE, "cqvip", "https://www.cqvip.com/doc/91234");
        TextCorpus same = new TextCorpus();
        same.add(openAlex, SENT_ONE);
        same.add(vip, SENT_TWO);
        TextCorpus.Report sameReport = same.match(TWIN_QUERY, null);
        SourceLedger one = SourceLedger.aggregate(sameReport.hits, TextCorpus.normalize(TWIN_QUERY),
                sameReport.comparedChars);
        check(one.rows.size() == 1,
                "一条带 DOI、一条只有同题名的题录并成来源榜一行：DOI 键与题名键共用 CandidateRanker 的取法");
        check(one.rows.get(0).duplicateChars == 41 && one.duplicateChars == 41
                        && one.rows.get(0).duplicateChars == sameReport.duplicateChars,
                "归并后的那一篇贡献 21 + 20 = 41 个有效字符，与分子同一个数");
        check(one.rows.get(0).sourceCount == 2, "这一行背后并了两个 Source 对象");
        check(one.rows.get(0).hitCount == 2, "两处命中都记在同一行上");
        check(one.rows.get(0).engine.contains("OpenAlex") && one.rows.get(0).engine.contains("维普（中文期刊）"),
                "一行来源榜也要说清这篇从哪几个检索源回来过");
        check(TWIN_DOI.equals(one.rows.get(0).key), "并出来的那一行用 DOI 当键，题名指纹只能当别名");
        check(one.rows.get(0).engineKeys.indexOf("openalex") == 0
                        && one.rows.get(0).engineChars.get(0).intValue() == 21
                        && one.rows.get(0).engineKeys.indexOf("cqvip") == 1
                        && one.rows.get(0).engineChars.get(1).intValue() == 20,
                "并成一行也保留每个检索源各自的字符数：openalex 21、cqvip 20");
        check(Math.abs(one.rows.get(0).share(81) - 41 * 100d / 81) < 1e-9,
                "该篇重复率 = 41 / 81，与总相似度比同分母");
        crossCheck("跨检索源并成一行", same, TWIN_QUERY);

        TextCorpus twin = new TextCorpus();
        twin.add(openAlex, SENT_ONE);
        twin.add(source("V2", TWIN_TITLE, "cqvip", "https://doi.org/10.1000/dd.2021.999"), SENT_TWO);
        TextCorpus.Report twinReport = twin.match(TWIN_QUERY, null);
        SourceLedger split = SourceLedger.aggregate(twinReport.hits, TextCorpus.normalize(TWIN_QUERY),
                twinReport.comparedChars);
        check(split.rows.size() == 2, "同题名但 DOI 不同不合条，与 CandidateRanker.dedup 的 DOI 否决同一条规则");
        check(split.rows.get(0).duplicateChars == 21 && split.rows.get(1).duplicateChars == 20 && sum(split) == 41,
                "否决之后两条各自记账，总量仍然是 41 个有效字符");
        check(TWIN_DOI.equals(split.rows.get(0).key) && "10.1000/dd.2021.999".equals(split.rows.get(1).key),
                "两行的键是两个不同的 DOI，不是同一个题名");

        check(CandidateRanker.paperKeyOf(openAlex).equals(CandidateRanker.doiKey(openAlex.locator)),
                "有 DOI 时 paperKeyOf 就等于 doiKey，没偷偷造第三种键");
        TextCorpus.Source titleOnly = source("V3", TWIN_TITLE, "cqvip", "https://www.cqvip.com/doc/91234");
        check(CandidateRanker.paperKeyOf(titleOnly).equals("t:" + CandidateRanker.titleKey(TWIN_TITLE)),
                "没有 DOI 时 paperKeyOf 等于 t: 加题名指纹");
    }

    /** 13 篇各贡献一处命中：封顶、折叠、折叠后仍然守恒、并列时按最早命中定序。 */
    private static void folding() {
        for (int i = 0; i < FOLD_LINES.length; i++)
            check(valid(FOLD_LINES[i]) == 20, "折叠夹具第 " + (i + 1) + " 行是手算的 20 个有效字符");
        String text = foldQuery();
        TextCorpus corpus = foldCorpus();
        TextCorpus.Report report = corpus.match(text, null);
        SourceLedger ledger = SourceLedger.aggregate(report.hits, TextCorpus.normalize(text), report.comparedChars);
        check(report.comparedChars == 260, "13 行乘 20 个有效字符 = 手算的 260 个分母字符");
        check(ledger.paperCount == 13, "13 篇命中在账本里算 13 篇，标题上的篇数不是画出来的行数");
        check(ledger.rows.size() == SourceLedger.MAX_ROWS + 1, "来源榜最多 12 篇，第 13 篇起折成一行其他");
        check(SourceLedger.MAX_ROWS == 12, "封顶就是 12 篇，与每源候选上限同一个量级");
        SourceLedger.Row tail = ledger.rows.get(ledger.rows.size() - 1);
        check(tail.others && tail.othersCount == 1, "最后一行是「其余 N 篇合计」，这里折掉了 1 篇");
        check(!ledger.rows.get(0).others && !ledger.rows.get(SourceLedger.MAX_ROWS - 1).others,
                "前 12 行都是真的某一篇，不是折叠行");
        check(tail.duplicateChars == 20 && tail.hitCount == 1, "折叠行带着被折掉那篇的 20 个字符与 1 处命中");
        check(tail.locator.isEmpty() && tail.authors.isEmpty() && tail.doi.isEmpty(),
                "折叠行不写标识符与作者：一行装几篇，写哪个都会误导");
        check(sum(ledger) == ledger.duplicateChars && sum(ledger) == report.duplicateChars && sum(ledger) == 260,
                "折叠只丢展示不丢字符：含「其余」在内的各行之和仍是手算的 260 且等于 duplicateChars");
        check(ledger.rows.get(0).duplicateChars >= ledger.rows.get(1).duplicateChars, "来源榜按重复字符降序排");
        StringBuilder shown = new StringBuilder();
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < SourceLedger.MAX_ROWS; i++) {
            shown.append(ledger.rows.get(i).key).append('|');
            expected.append(foldKey(i)).append('|');
        }
        check(expected.toString().equals(shown.toString()),
                "13 篇字符数全并列时按最早命中偏移定序：前 12 行就是第 1 到第 12 篇");
        check(perEngineTotal(ledger, report) == 260, "折叠之后按检索源汇总仍然等于 260：折叠行也带着自己的分源账");
        crossCheck("十三篇折叠", corpus, text);
    }

    /** 同一份输入跑两次必须逐位相同，否则报告每次导出都在重新洗牌。 */
    private static void ordering() {
        String text = foldQuery();
        SourceLedger first = SourceLedger.aggregate(foldCorpus().match(text, null).hits,
                TextCorpus.normalize(text), 260);
        SourceLedger second = SourceLedger.aggregate(foldCorpus().match(text, null).hits,
                TextCorpus.normalize(text), 260);
        check(keySequence(first).equals(keySequence(second)),
                "同一份语料重跑一遍，来源榜逐行 key 序列逐位相同");
        check(first.rows.size() == second.rows.size() && sum(first) == sum(second),
                "两次运行的行数与总字符数也一样，没有哈希序带来的抖动");
        TextCorpus.Report report = foldCorpus().match(text, null);
        SourceLedger a = SourceLedger.aggregate(report.hits, TextCorpus.normalize(text), report.comparedChars);
        SourceLedger b = SourceLedger.aggregate(report.hits, TextCorpus.normalize(text), report.comparedChars);
        check(keySequence(a).equals(keySequence(b)), "同一份 hits 连算两次结果相同：账本是纯函数");
        ArrayList<TextCorpus.Hit> reversed = new ArrayList<TextCorpus.Hit>(report.hits);
        for (int i = report.hits.size() - 1; i >= 0; i--) reversed.add(report.hits.get(i));
        SourceLedger flipped = SourceLedger.aggregate(reversed, TextCorpus.normalize(text), report.comparedChars);
        check(keySequence(flipped).equals(keySequence(a)), "把 hits 倒过来喂，来源榜的行序不变：排序不靠命中先后");
    }

    /** 指纹带与句级命中重叠：两行各记自己那份，被抢走的字符只数一次。 */
    private static void bandVersusSentence() {
        check(valid(DOC_P) == 27, "甲篇那句整句是手算的 27 个有效字符");
        TextCorpus corpus = new TextCorpus();
        corpus.add(source("P1", "甲篇", "openalex", "https://doi.org/10.1/a.p"), DOC_P);
        corpus.add(source("Q1", "乙篇", "cqvip", "https://doi.org/10.1/q.p"), DOC_Q);
        TextCorpus.Report report = corpus.match(OVERLAP_QUERY, null);
        SourceLedger ledger = SourceLedger.aggregate(report.hits, TextCorpus.normalize(OVERLAP_QUERY),
                report.comparedChars);
        check(report.hits.size() == 2, "甲篇走句级命中、乙篇走指纹带，两条路各出一条命中");
        check(ledger.rows.size() == 2, "两篇各占来源榜一行");
        check(ledger.rows.get(0).duplicateChars == 27 && ledger.rows.get(1).duplicateChars == 24,
                "甲篇拿整句 27 个字，乙篇只拿没被盖住的 24 个字（带子长 31，重叠那 7 个字归甲篇）");
        check(sum(ledger) == report.duplicateChars && sum(ledger) == 51,
                "重叠的那 7 个字没有在两行里各算一遍：27 + 24 = 51 等于分子");
        check(corpus.disputedChars() == 7, "被两篇同时命中的字符数手算是 7 个：接头强度下降那七个字");
        check(ledger.rows.get(1).engineKeys.indexOf("cqvip") == 0
                        && ledger.rows.get(1).engineChars.get(0).intValue() == 24,
                "指纹带拿到的 24 个字记在乙篇的检索源名下");
        crossCheck("句级命中与指纹带抢同一段", corpus, OVERLAP_QUERY);

        DuplicateEngine.Report scanned = DuplicateEngine.scan(selection(OVERLAP_QUERY), corpus, false, null,
                new PaperSources.Limits(), new ApiClient.Task(), null);
        check(scanned.duplicateChars == 51, "整条链路扫一遍还是 51 个重复字符，注记不改判据");
        boolean explained = false;
        String wanted = "有 7 个字符同时被两篇以上文献命中，只记给了命中更长的那一篇";
        for (int i = 0; i < scanned.notes.size(); i++) if (wanted.equals(scanned.notes.get(i))) explained = true;
        check(explained, "报告用一条注记说清那个 0 处命中是怎么来的，不把假阴性留给用户猜");
    }

    /** 已知缺陷的钉桩（不修只钉）：超 512 有效字符的长句滑窗重叠 64 个字，分子里算了两遍。 */
    private static void longSentence() {
        StringBuilder a = new StringBuilder();
        for (int i = 0; i < 512; i++) a.append((char) (0x4E00 + (i * 37) % 400));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 320; i++) b.append((char) (0x4E00 + 400 + (i * 53) % 400));
        TextCorpus corpus = new TextCorpus();
        corpus.add(source("L1", "长句来源甲", "openalex", ""), a.toString());
        corpus.add(source("L2", "长句来源乙", "crossref", ""), b.toString());
        String query = a.toString() + b.toString();
        TextCorpus.Report report = corpus.match(query, null);
        SourceLedger ledger = SourceLedger.aggregate(report.hits, TextCorpus.normalize(query), report.comparedChars);
        check(report.comparedChars == 832, "分母按整句只数一次：512 + 320 = 832 个有效字符");
        check(report.hits.size() == 2 && report.hits.get(0).end - report.hits.get(0).start == 512
                        && report.hits.get(1).end - report.hits.get(1).start == 384,
                "两条命中是 0..512 与 448..832，窗口之间重叠 64 个字符");
        check(report.duplicateChars == 896,
                "已知缺陷：长句滑窗重叠的 64 个字符在分子里算了两遍（512 + 384 = 896，比分母多出的正是那 64）");
        check(sum(ledger) == ledger.duplicateChars && sum(ledger) == report.duplicateChars,
                "账本与分子同口径：缺陷被如实照抄，来源榜不会比总数多也不会比总数少");
        check(ledger.rows.get(0).duplicateChars == 512 && ledger.rows.get(1).duplicateChars == 384,
                "两篇各记自己那一段：512 与 384");
        check(corpus.disputedChars() == 64, "两篇真正抢同一组字符的只有滑窗重叠的那 64 个字符");
    }

    /** 报告的三种写法：有命中排表、没比对成整段不排、比对过但没命中要说清未命中不等于没重复。 */
    private static void reportHtml() {
        DuplicateEngine.Report hit = new DuplicateEngine.Report();
        hit.sourceText = "他说“重复率偏高”，随后重写了这一段正文。";
        hit.comparedChars = 1000;
        hit.duplicateChars = 5;
        hit.byEngine.put("openalex", Double.valueOf(0.5));
        hit.candidateCount.put("openalex", Integer.valueOf(2));
        PaperSources.Candidate candidate = new PaperSources.Candidate();
        candidate.source.engine = "openalex";
        candidate.source.title = "候选标题";
        hit.candidates.add(candidate);
        TextCorpus.Hit first = new TextCorpus.Hit();
        first.start = 3;
        first.end = 8;
        first.score = 0.87f;
        first.source = source("H1", "某篇论文 \"副本\"<script>", "openalex", "PMID:12345678");
        hit.hits.add(first);
        String html = CheckReport.html("来源榜<script>.docx", hit);
        check(html.contains("<h2>来源榜") && html.contains("共 1 篇命中"), "报告新增按文献聚合的来源榜，篇数写在标题上");
        check(html.indexOf("<h2>按检索源分布") < html.indexOf("<h2>来源榜")
                        && html.indexOf("<h2>来源榜") < html.indexOf("<h2>候选文献"),
                "来源榜排在「按检索源分布」之后、候选文献与具体片段之前");
        check(html.contains("0.50%"), "来源榜写出手算的该篇重复率 5 / 1000");
        check(html.contains("<mark>重复率偏高</mark>"), "来源榜的命中样例带着命中的那几个字，区间用 mark 包住");
        check(html.contains("第 1 篇的命中样例"), "命中样例按行号写清是哪一篇");
        check(html.contains("PMID:12345678") && !html.contains("href=\"PMID:12345678\""),
                "没有协议的标识符只当纯文本显示，不进 href");
        check(!html.contains("<script>") && html.contains("&lt;script&gt;"),
                "来源榜的标题与标识符一样过 escape，脚本进不了导出文件");
        check(html.contains("同一段字符只记给命中最长的那一篇"), "表下写死该篇重复率的分母口径与重叠归属");

        DuplicateEngine.Report linked = new DuplicateEngine.Report();
        linked.sourceText = "现场测试表明该写法在强噪声环境下仍然稳定。";
        linked.comparedChars = 102;
        TextCorpus.Hit linkedHit = new TextCorpus.Hit();
        linkedHit.start = 0;
        linkedHit.end = 6;
        linkedHit.source = source("H2", TWIN_TITLE, "openalex", "https://doi.org/" + TWIN_DOI);
        linked.hits.add(linkedHit);
        String linkedHtml = CheckReport.html("linked.docx", linked);
        check(linkedHtml.contains("<a href=\"https://doi.org/10.1000/dd.2021.001\" rel=\"noopener\">"
                        + TWIN_DOI + "</a>"),
                "字面 https:// 开头的 locator 才包成链接，链接文字用 DOI 本体");

        DuplicateEngine.Report quote = new DuplicateEngine.Report();
        quote.sourceText = "他说“重复率偏高”，随后重写了这一段正文。";
        quote.comparedChars = 1000;
        TextCorpus.Hit sneaky = new TextCorpus.Hit();
        sneaky.start = 3;
        sneaky.end = 8;
        sneaky.source = source("H3", "带引号的来源", "openalex", "https://x.example/\" onmouseover=\"alert(1)");
        quote.hits.add(sneaky);
        String quoteHtml = CheckReport.html("quote.docx", quote);
        check(!quoteHtml.contains("href=\"https://x.example/\"") && quoteHtml.contains("&quot; onmouseover="),
                "标识符里的引号先转义再进 href，属性切不出去");

        DuplicateEngine.Report blackout = new DuplicateEngine.Report();
        blackout.sourceText = "本文的结论建立在实验数据与既有报道之上。";
        blackout.retrievalIncomplete = true;
        blackout.retrievalReason = "5 个检索源本次全部不可用，联网检索没有取回可比对的候选文献";
        String blackoutHtml = CheckReport.html("blackout.docx", blackout);
        check(!blackoutHtml.contains("来源榜"),
                "什么都没比对成的报告不排来源榜，空表会被读成这篇很干净");
        DuplicateEngine.Report partial = new DuplicateEngine.Report();
        partial.sourceText = "本文的结论建立在实验数据与既有报道之上。";
        partial.comparedChars = 400;
        partial.retrievalIncomplete = true;
        partial.retrievalReason = "联网检索没有取回可比对的候选文献";
        TextCorpus.Hit local = new TextCorpus.Hit();
        local.start = 0;
        local.end = 6;
        local.source = source("H4", "自建库里的旧稿", "local", "input-liu.docx");
        partial.hits.add(local);
        String partialHtml = CheckReport.html("partial.docx", partial);
        check(partialHtml.contains("<h2>来源榜") && partialHtml.contains("未完成查重"),
                "联网没跑成但自建库真命中了：来源榜照样排，未完成查重那条照样在头部");

        DuplicateEngine.Report clean = new DuplicateEngine.Report();
        clean.sourceText = "全文都是我们自己写的，没有可比对的命中。";
        clean.comparedChars = 200;
        clean.candidates.add(candidate);
        String cleanHtml = CheckReport.html("clean.docx", clean);
        check(cleanHtml.contains("<h2>来源榜") && cleanHtml.contains("没有一篇命中相似片段"),
                "0 命中时来源榜写清楚比对过但没命中，不留一张空表");
        check(cleanHtml.contains("比对过 1 篇候选与自建库") && cleanHtml.contains("未命中不等于全文没有重复"),
                "这句话把「未命中」和「全文没有重复」分开，那是全应用最贵的一个误读");

        String text = foldQuery();
        TextCorpus.Report folded = foldCorpus().match(text, null);
        DuplicateEngine.Report many = new DuplicateEngine.Report();
        many.sourceText = text;
        many.comparedChars = folded.comparedChars;
        many.duplicateChars = folded.duplicateChars;
        many.hits.addAll(folded.hits);
        String manyHtml = CheckReport.html("many.docx", many);
        check(manyHtml.contains("共 13 篇命中") && manyHtml.contains("其余 1 篇合计"),
                "表只画 12 行加一行其余，标题仍然写一共 13 篇");
        check(manyHtml.contains("第 6 篇的命中样例") && !manyHtml.contains("第 7 篇的命中样例"),
                "命中样例只画前 6 行：导出成文件的报告体积得有上限");
    }
}