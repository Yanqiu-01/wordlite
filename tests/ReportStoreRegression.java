package com.rikkahub.wordlite;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Locale;

/**
 * 报告中心数据层（1.0.0）回归：一次真跑完的检查存成一条记录后，读回来必须还能重画整张报告——
 * 四个比率、账本闭合、来源榜之和、证据区间、HTML 一处都不能变；体积有上限（单条 512 KB、库里 60 条、
 * 证据 40 条、摘要 160 字）；索引写坏了按目录重建，单条记录写坏了只丢那一条。
 *
 * 全程不联网、不碰 Android、不重跑第二遍比对：语料是手写的，比对只跑一次然后存档。
 * 期望值全是手算整数或逐字符相等的字符串，写 >= 0 那种断言挡不住任何回归。
 */
public final class ReportStoreRegression {
    private static int checks;
    private static final String RUN = Long.toString(System.nanoTime(), 36);

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
        System.out.println("PASS " + message);
    }

    private static void same(Object actual, Object expected, String message) {
        boolean equal = actual == null ? expected == null : actual.equals(expected);
        if (!equal) throw new AssertionError(message + "（期望 " + expected + "，实得 " + actual + "）");
        checks++;
        System.out.println("PASS " + message);
    }

    // ---- 夹具 ----

    /** 31 个有效字符的逐字复制：句级与指纹带两条路都会命中同一段。 */
    private static final String COPIED = "多孔铜在低温下即可与锡层反应，界面生成稳定的金属间化合物层。";
    private static final String FILLER = "实验在三种温度下各重复五次，取样位置固定在接头中心两侧。";

    private static DocxDocument.ParagraphBlock paragraph(int index, String text) {
        DocxDocument.ParagraphBlock value = new DocxDocument.ParagraphBlock();
        value.index = index;
        value.text = text;
        return value;
    }

    private static void add(DocxDocument document, DocxDocument.ParagraphBlock paragraph) {
        document.blocks.add(paragraph);
        document.paragraphs.add(paragraph);
    }

    private static TextCorpus corpus() {
        TextCorpus corpus = new TextCorpus();
        TextCorpus.Source source = new TextCorpus.Source();
        source.id = "local:多孔铜连接研究";
        source.title = "多孔铜连接研究";
        source.engine = "local";
        source.locator = "porous-copper.txt";
        source.year = "2021";
        source.authors = "张三";
        corpus.add(source, COPIED + FILLER);
        return corpus;
    }

    /** 只跑这一次比对：之后的断言全部对着读回来的记录做，"不必重跑"这件事本身就是被断言的。 */
    private static DuplicateEngine.Report scanned() {
        DocxDocument document = new DocxDocument();
        add(document, paragraph(0, COPIED + FILLER));
        add(document, paragraph(1, "保温时间过长会让反应层增厚，接头强度反而下降，断口形貌随之改变。"));
        return DuplicateEngine.scan(TextSelection.all(document), corpus(), false, null, null, null, null);
    }

    private static File tempDir(String name) {
        File dir = new File(System.getProperty("java.io.tmpdir"), "wordlite-reports-" + name + "-" + RUN);
        deleteTree(dir);
        dir.mkdirs();
        return dir;
    }

    private static void deleteTree(File file) {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (int i = 0; i < children.length; i++) deleteTree(children[i]);
        }
        file.delete();
    }

    private static void write(File file, String content) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(content.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    private static String read(File file) throws Exception {
        RandomAccessFile in = new RandomAccessFile(file, "r");
        try {
            byte[] buffer = new byte[(int) in.length()];
            in.readFully(buffer);
            return new String(buffer, "UTF-8");
        } finally {
            in.close();
        }
    }

    /** 手工拼一条记录：体积上限那几条断言要的是一条被人为撑大的报告，不该为此再跑一轮比对。 */
    private static ReportStore.Record hand(String fileName, long createdAt) {
        ReportStore.Record record = new ReportStore.Record();
        record.fileName = fileName;
        record.createdAt = createdAt;
        record.detectedAt = "2026-10-08 09:12";
        record.elapsedMillis = 4321L;
        record.comparedChars = 4000;
        record.duplicateChars = 500;
        record.citedDuplicateChars = 100;
        record.selfWrittenChars = 3500;
        record.overallRate = 12.5d;
        record.excludingCitationsRate = 10d;
        record.selfWrittenRate = 87.5d;
        record.citedDuplicateRate = 2.5d;
        record.sourceChars = 20000;
        record.textDigest = ReportStore.digest(fileName);
        return record;
    }

    private static ReportStore.Evidence evidence(int at, int length, String title) {
        ReportStore.Evidence hit = new ReportStore.Evidence();
        hit.start = at;
        hit.end = at + length;
        hit.score = 0.75d;
        hit.title = title;
        hit.engine = "wanfang";
        hit.year = "2021";
        hit.snippet = "取样位置固定在接头中心两侧。";
        return hit;
    }

    private static ReportStore.SourceRow sourceRow(int chars, int firstStart, String title) {
        ReportStore.SourceRow row = new ReportStore.SourceRow();
        row.title = title;
        row.engine = "wanfang";
        row.duplicateChars = chars;
        row.hitCount = 1;
        row.sourceCount = 1;
        row.firstStart = firstStart;
        row.share = chars * 100d / 4000d;
        return row;
    }

    private static int sourceTotal(ReportStore.Record record) {
        int total = 0;
        for (int i = 0; i < record.sources.size(); i++) total += record.sources.get(i).duplicateChars;
        return total;
    }

    // ---- 一次真跑完的检查 -> 一条记录 -> 原样读回 ----

    private static void persistedScan() throws Exception {
        File dir = tempDir("scan");
        DuplicateEngine.Report report = scanned();
        check(!report.hits.isEmpty() && report.duplicateChars > 0,
                "夹具本身真命中了：后面所有存档没丢东西的断言都以它为前提");
        ReportStore.Record record = ReportStore.recordFor(report, "论文-多孔铜.docx");
        check(ReportStore.STATE_COMPLETE.equals(record.state),
                "自建库比对且真取回了东西：三态记成完整检索，不是部分完成也不是未完成");
        check(record.metricsValid(), "问出过东西的记录才允许印比率");
        check(record.evidenceTotal == report.hits.size(),
                "证据条数记的是当时的全貌：" + record.evidenceTotal + " 处命中一个数都不少");
        check(record.sourcesTotal == record.sources.size(), "来源榜没到折叠线时 sourcesTotal 就是行数，不虚报篇数");
        check(record.notesTotal == report.notes.size() && !record.notes.isEmpty(),
                "注记整条搬过来：" + record.notes.size() + " 条，一条不裁");
        check(record.sourceChars == report.sourceText.length()
                        && record.textDigest == ReportStore.digest(report.sourceText),
                "正文长度与指纹跟着记录存下来：跳正文前验的是当时那篇，不是现在这篇");
        check(record.html.contains("查重与 AIGC 报告") && record.html.length() > 0,
                "HTML 一起存下来：详情页之外还能直接导出，不必再跑一遍比对");
        ReportStore.Evidence first = record.evidence.get(0);
        check(first.start == report.hits.get(0).start && first.end == report.hits.get(0).end,
                "第一处证据的字符区间原样保住：" + first.start + "-" + first.end);
        check(record.canJump(first), "这条区间落在正文之内，点它跳得过去");
        check(first.channel == report.hits.get(0).channel,
                "证据带着判据出处存下来（字面 / 抗改写），命中地图才有颜色可分");
        int aiWanted = report.aigc == null ? 0
                : Math.min(report.aigc.segments.size(), ReportStore.MAX_AI_SEGMENTS);
        check(record.aiSegments.size() == aiWanted
                        && record.aiSegmentsTotal == (report.aigc == null ? 0 : report.aigc.segments.size()),
                "AI 分段的区间整条存档，超出上限的那部分只报总数：留 " + record.aiSegments.size()
                        + " 段 / 共 " + record.aiSegmentsTotal + " 段");
        int sum = sourceTotal(record);
        check(sum == report.duplicateChars,
                "来源榜各行相加仍是总重复字符数 " + report.duplicateChars + "：聚合的账本过了磁盘还在");
        check(record.duplicateChars + record.selfWrittenChars == record.comparedChars,
                "重复 + 自编 == 分母 " + record.comparedChars + "：0.7.1 那把尺子在存档侧同样闭合");
        check(Math.abs(record.excludingCitationsRate + record.citedDuplicateRate + record.selfWrittenRate - 100d) < 1e-9,
                "去除引用重复比 + 引用区间重复比 + 自编率 == 100：三个桶的百分比存档后仍然对咬");
        check(record.ledgerResidual == 0, "存下来的账本残差是 0：报告不是凑出来的");

        ReportStore store = new ReportStore(dir);
        check(store.history().isEmpty(), "空目录列出空列表而不是 null，列表页第一条就不会崩");
        ReportStore.Record saved = store.save(record);
        check(saved != null && saved.id.length() > 0, "保存返回落盘那一份并带上编号");
        File file = new File(dir, saved.id + ".json");
        check(file.isFile(), "记录就是一个文件：" + file.getName());
        check(read(file).getBytes("UTF-8").length == saved.bytes,
                "bytes 就是文件实测字节数 " + saved.bytes + "，不是随口报的数");
        check(saved.bytes <= ReportStore.MAX_RECORD_BYTES, "单条记录在上限之内");
        check(!saved.evidenceTruncated && !saved.htmlCut, "这条小记录没被体积上限动过一刀");

        ReportStore.Record back = store.record(saved.id);
        check(back != null, "按编号读得回来");
        same(back.toJson(), saved.toJson(), "写进去什么就读回什么：整条记录的 JSON 逐字符相同");
        check(back.aiSegments.size() == saved.aiSegments.size(),
                "AI 分段过磁盘条数相同：" + saved.aiSegments.size() + " 对 " + back.aiSegments.size());
        if (!saved.aiSegments.isEmpty()) {
            ReportStore.AiSegment a = saved.aiSegments.get(0);
            ReportStore.AiSegment b = back.aiSegments.get(0);
            check(a.start == b.start && a.end == b.end && a.flagged == b.flagged
                            && Math.abs(a.score - b.score) < 1e-9 && a.chars == b.chars,
                    "AI 分段的位置、档位、分值、字数都回来了：" + a.start + "-" + a.end);
        }
        check(back.evidence.isEmpty() || back.evidence.get(0).channel == saved.evidence.get(0).channel,
                "判据出处过了磁盘还是那一个：颜色不许在存档之后变");
        ReportStore.Record legacy = ReportStore.Record.fromJson(ApiJson.parse(
                "{\"id\":\"old\",\"evidence\":[{\"start\":3,\"end\":9,\"score\":0.5}]}"));
        check(legacy != null && legacy.evidence.size() == 1 && legacy.aiSegments.isEmpty()
                        && legacy.evidence.get(0).channel == TextCorpus.CHANNEL_VERBATIM,
                "老库里没有 chan / ai 这两位的记录照样读得回来：缺的那位按字面证据与零段处理，不猜颜色");
        same(back.fileName, "论文-多孔铜.docx", "文件名回来");
        same(back.createdAt, saved.createdAt, "时间戳回来（列表按它排序）");
        same(back.detectedAt, report.detectedAt, "检测时间那一行回来");
        same(back.elapsedMillis, report.elapsedMillis, "用时回来");
        same(back.state, saved.state, "三态回来");
        same(back.coverageNote, ReportStore.coverageLine(report.windowsRetrieved, report.windowsAvailable,
                report.coveredChars, report.comparableChars, report.retrievalPartial),
                "覆盖率那一句与面板同源：同一组数在详情页印出同一行字");
        same(Double.valueOf(back.overallRate), Double.valueOf(report.overallRate), "指标卡之一：总相似度比逐位相同");
        same(Double.valueOf(back.excludingCitationsRate), Double.valueOf(report.excludingCitationsRate),
                "指标卡之二：去除引用重复比逐位相同");
        same(Double.valueOf(back.selfWrittenRate), Double.valueOf(report.selfWrittenRate), "指标卡之三：自编率逐位相同");
        // 引用区间重复比在 CharLedger.Balance 上，DuplicateEngine.Report 没这个字段：拿账本原值对，别拿一个不存在的字段。
        check(report.ledger != null, "离线扫描也会算账本：四个比率都有出处");
        same(Double.valueOf(back.citedDuplicateRate), Double.valueOf(report.ledger.citedDuplicateRate),
                "指标卡之四：引用区间重复比逐位相同");
        same(Double.valueOf(back.citedDuplicateRate),
                Double.valueOf(report.citedDuplicateChars * 100d / report.comparedChars),
                "引用区间重复比等于手算的引用重复字数除以分母，报告不是凑出来的");
        same(Double.valueOf(back.aigcScore), Double.valueOf(ReportStore.rate(report.aigcRate)),
                "机器腔均分不是比例，回来还是一个不带百分号的分数");
        same(back.aigcVerdict, DuplicateEngine.aigcTrend(report), "机器生成倾向那一整句回来，包括档位与字数");
        same(back.aigcUnmeasured, DuplicateEngine.aigcUnmeasured(report), "样本不足这件事跟着记录存下来");
        same(Integer.valueOf(back.comparedChars), Integer.valueOf(report.comparedChars), "分母字数回来");
        same(Integer.valueOf(back.duplicateChars), Integer.valueOf(report.duplicateChars), "重复字数回来");
        same(Integer.valueOf(back.citedDuplicateChars), Integer.valueOf(report.citedDuplicateChars), "引用区间内重复字数回来");
        same(Integer.valueOf(back.selfWrittenChars), Integer.valueOf(report.comparedChars - report.duplicateChars),
                "自编字数回来：" + back.selfWrittenChars + " 字");
        same(Integer.valueOf(back.excludedChars), Integer.valueOf(report.excludedChars), "结构性排除字数回来");
        same(Integer.valueOf(back.ledgerResidual), Integer.valueOf(0), "残差回来还是 0");
        same(back.notes, saved.notes, "注记逐条逐字回来");
        same(Integer.valueOf(back.sourcesTotal), Integer.valueOf(record.sourcesTotal), "折叠前的篇数回来，标题要说清一共几篇");
        same(Integer.valueOf(back.sources.size()), Integer.valueOf(record.sources.size()), "来源榜行数回来");
        same(Integer.valueOf(sourceTotal(back)), Integer.valueOf(report.duplicateChars),
                "读回来的来源榜相加还是 " + report.duplicateChars + " 个重复字符");
        check(back.sources.get(0).firstStart == record.sources.get(0).firstStart,
                "来源行的 firstStart 回来：点来源行也跳得回第一处命中");
        same(Integer.valueOf(back.engines.size()), Integer.valueOf(record.engines.size()), "按检索源分布的行数回来");
        same(Integer.valueOf(back.evidence.size()), Integer.valueOf(record.evidence.size()), "证据表行数回来");
        for (int i = 0; i < back.evidence.size(); i++) {
            ReportStore.Evidence kept = back.evidence.get(i);
            ReportStore.Evidence sent = record.evidence.get(i);
            check(kept.start == sent.start && kept.end == sent.end,
                    "第 " + (i + 1) + " 处证据的区间跳得回去：" + kept.start + "-" + kept.end);
            same(kept.title, sent.title, "第 " + (i + 1) + " 处证据的出处标题回来");
            same(kept.engine, sent.engine, "第 " + (i + 1) + " 处证据的检索源回来");
            same(Double.valueOf(kept.score), Double.valueOf(sent.score), "第 " + (i + 1) + " 处证据的相似度回来");
            String body = kept.snippet.endsWith("…")
                    ? kept.snippet.substring(0, kept.snippet.length() - 1) : kept.snippet;
            check(report.sourceText.startsWith(body, kept.start),
                    "第 " + (i + 1) + " 处证据的摘要确实是从那串字符上切下来的，没串位");
        }
        check(back.html.equals(saved.html), "HTML 原样回来，导出的还是当时那张");

        ArrayList<ReportStore.Summary> history = store.history();
        same(Integer.valueOf(history.size()), Integer.valueOf(1), "列表里就一条");
        ReportStore.Summary top = history.get(0);
        same(top.id, saved.id, "列表行的编号指向那条记录");
        same(top.fileName, "论文-多孔铜.docx", "列表行有文件名");
        same(Double.valueOf(top.overallRate), Double.valueOf(report.overallRate), "列表行上的相似率与详情同一个数");
        same(Integer.valueOf(top.duplicateChars), Integer.valueOf(report.duplicateChars), "列表行带的重复字数不用进详情就能看到");
        same(Integer.valueOf(top.evidenceTotal), Integer.valueOf(report.hits.size()), "列表行写得出共几处命中");
        same(Integer.valueOf(top.bytes), Integer.valueOf(saved.bytes), "列表行的体积就是那个文件的体积");
        check(!top.truncated, "没被截断的记录在列表上不打截断标记");
        check(new File(dir, "index.json").isFile(), "索引跟着写盘，列表页不必逐个读记录文件");

        String firstJson = read(file);
        String firstId = saved.id;
        ReportStore.Record twin = store.save(record);
        check(twin != null && !twin.id.equals(firstId), "同一份比对再存一次是多出一条历史，不是覆盖旧的那条");
        check(read(file).equals(firstJson), "旧那条记录文件一个字节都没动，" + firstId + " 仍是当时那份");
        same(Integer.valueOf(store.history().size()), Integer.valueOf(2), "再存一次列表多一条");
        check(!store.history().get(0).id.equals(firstId), "新的那条排在列表最前");
        deleteTree(dir);
    }

    // ---- 上限：数字先钉死，再证明每个上限真的拦得住东西 ----

    private static void caps() throws Exception {
        same(Integer.valueOf(ReportStore.RECORD_VERSION), Integer.valueOf(1), "记录格式版本是 1：读侧拒绝更高版本才有意义");
        same(Integer.valueOf(ReportStore.MAX_RECORDS), Integer.valueOf(60), "库里最多 60 条");
        same(Integer.valueOf(ReportStore.MAX_RECORD_BYTES), Integer.valueOf(524288), "单条最多 512 KB");
        same(Integer.valueOf(ReportStore.MAX_INDEX_BYTES), Integer.valueOf(262144), "索引最多 256 KB");
        same(Integer.valueOf(ReportStore.MAX_EVIDENCE), Integer.valueOf(40), "一条记录最多留 40 处证据");
        same(Integer.valueOf(ReportStore.MAX_SNIPPET_CHARS), Integer.valueOf(160), "证据摘要最多 160 字");
        same(Integer.valueOf(ReportStore.MAX_SOURCE_ROWS), Integer.valueOf(13),
                "来源榜最多 13 行 = SourceLedger 的 12 行加一行其余合计");
        same(Integer.valueOf(ReportStore.MAX_NOTES), Integer.valueOf(12), "注记最多 12 条");
        same(Integer.valueOf(ReportStore.MAX_HTML_CHARS), Integer.valueOf(163840), "HTML 最多 16 万字");
        same(Integer.valueOf(ReportStore.MAX_ENGINE_ROWS), Integer.valueOf(16), "按检索源分布最多 16 行");

        File dir = tempDir("caps");
        ReportStore store = new ReportStore(dir);

        StringBuilder longSnippet = new StringBuilder();
        for (int i = 0; i < 40; i++) longSnippet.append("取样位置固定在接头中心两侧，每次试验都记录峰值载荷与断裂位置。");
        same(Integer.valueOf(longSnippet.length()), Integer.valueOf(40 * 31),
                "夹具摘要是 40 句 31 字共 1240 字，够把摘要那一刀逼出来");
        ReportStore.Record wide = hand("摘要过长.docx", 1760000000000L);
        ReportStore.Evidence mouthful = evidence(100, 600, "过载试验方法");
        mouthful.snippet = longSnippet.toString();
        wide.evidence.add(mouthful);
        ReportStore.Record savedWide = store.save(wide);
        same(Integer.valueOf(savedWide.evidence.get(0).snippet.length()), Integer.valueOf(161),
                "摘要夹到 160 字再加一枚省略号，多一个字都不留");
        check(savedWide.evidence.get(0).snippetCut, "摘要被砍过这件事跟着记录存下来，界面上才敢写省略号");
        check(longSnippet.toString().startsWith(
                        savedWide.evidence.get(0).snippet.substring(0, ReportStore.MAX_SNIPPET_CHARS)),
                "留下的是最前面那 160 个字，不是从中间随机一段");

        ReportStore.Record many = hand("命中过多.docx", 1760000001000L);
        for (int i = 0; i < 400; i++) many.evidence.add(evidence(i * 20, 13, "文献" + i));
        ReportStore.Record savedMany = store.save(many);
        same(Integer.valueOf(savedMany.evidence.size()), Integer.valueOf(ReportStore.MAX_EVIDENCE),
                "400 处命中只留前 40 处，其余靠 evidenceTotal 说话");
        check(savedMany.evidenceTruncated, "截断这件事写在记录里，界面能说只保留了前 40 条");
        same(Integer.valueOf(savedMany.evidenceTotal), Integer.valueOf(400), "一共 400 处这个数不丢");
        boolean ordered = true;
        for (int i = 0; i < savedMany.evidence.size(); i++) {
            if (savedMany.evidence.get(i).start != i * 20) ordered = false;
        }
        check(ordered, "留下的是正文里最靠前的 40 处：跳正文要的位置，靠前的先点得着");
        ReportStore.Summary truncatedRow = store.history().get(0);
        check(truncatedRow.truncated && truncatedRow.evidenceTotal == 400,
                "列表行就标得出这条被截过，不必进详情才发现证据少了");

        ReportStore.Record crowd = hand("注记与来源过多.docx", 1760000002000L);
        for (int i = 0; i < 20; i++) crowd.notes.add(note(300, i));
        for (int i = 0; i < 30; i++) crowd.sources.add(sourceRow(20, i, "被引文献" + i + "——一个足够长的题名用来验证题名本身也被夹住" + i));
        for (int i = 0; i < 20; i++) {
            ReportStore.EngineRow row = new ReportStore.EngineRow();
            row.engine = "engine" + i;
            row.share = 1d;
            row.candidates = i;
            row.windows = i;
            crowd.engines.add(row);
        }
        ReportStore.Record savedCrowd = store.save(crowd);
        same(Integer.valueOf(savedCrowd.notes.size()), Integer.valueOf(12), "注记只留前 12 条");
        same(Integer.valueOf(savedCrowd.notesTotal), Integer.valueOf(20), "一共 20 条注记这件事写进记录");
        boolean notesBounded = true;
        for (int i = 0; i < savedCrowd.notes.size(); i++) {
            if (((String) savedCrowd.notes.get(i)).length() > ReportStore.MAX_NOTE_CHARS) notesBounded = false;
        }
        check(notesBounded, "单条注记不超过 200 字");
        same(Integer.valueOf(savedCrowd.sources.size()), Integer.valueOf(13), "来源榜最多 13 行");
        same(Integer.valueOf(savedCrowd.sourcesTotal), Integer.valueOf(30), "折叠前的 30 篇留在记录里");
        same(Integer.valueOf(savedCrowd.engines.size()), Integer.valueOf(16), "检索源分布最多 16 行");

        StringBuilder html = new StringBuilder();
        while (html.length() < ReportStore.MAX_HTML_CHARS + 5000) {
            html.append("<p>");
            for (int i = 0; i < 40; i++) html.append("这是一段用来把报告体积顶穿上限的中文正文");
            html.append("</p>");
        }
        ReportStore.Record oversized = hand("报告过长.docx", 1760000003000L);
        oversized.html = html.toString();
        for (int i = 0; i < ReportStore.MAX_EVIDENCE; i++) oversized.evidence.add(evidence(i * 20, 13, "文献" + i));
        ReportStore.Record savedHtml = store.save(oversized);
        check(savedHtml.html.length() <= ReportStore.MAX_HTML_CHARS, "HTML 夹在字符上限之内");
        check(savedHtml.htmlCut, "HTML 被砍过要留痕");
        check(savedHtml.html.endsWith("此处按上限截断。</em></p>"),
                "截断处留一句声明，导出的文件不会看着像完整报告");
        check(html.toString().startsWith(savedHtml.html.substring(0, 1000)), "报告开头原样保住");
        int markerAt = savedHtml.html.indexOf("<p><em>");
        check(markerAt > 0 && savedHtml.html.substring(0, markerAt).endsWith("</p>"),
                "只在段落边界下刀：声明之前收尾于完整的 </p>，不留半截标签");
        String marker = "<p><em>报告过长，此处按上限截断。</em></p>";
        same(savedHtml.html.substring(savedHtml.html.length() - marker.length()), marker,
                "截断声明就是最后一段：截完就收尾，后面不再拼第二刀");
        same(Integer.valueOf(savedHtml.evidence.size()), Integer.valueOf(ReportStore.MAX_EVIDENCE),
                "第一刀砍的是 HTML：详情页不靠它渲染，证据一条没少");

        ReportStore.Record bursting = hand("单条超预算.docx", 1760000004000L);
        StringBuilder whole = new StringBuilder();
        while (whole.length() < ReportStore.MAX_HTML_CHARS) whole.append("中");
        bursting.html = whole.toString();
        for (int i = 0; i < ReportStore.MAX_EVIDENCE; i++) {
            ReportStore.Evidence hit = evidence(i * 20, 13, "一个足够长的题名用来占位");
            hit.snippet = longSnippet.toString();
            bursting.evidence.add(hit);
        }
        for (int i = 0; i < 13; i++) bursting.sources.add(sourceRow(20, i, "一个足够长的题名用来占位"));
        for (int i = 0; i < 12; i++) bursting.notes.add(note(200, i));
        check(ReportStore.bytesOf(bursting.toJson()) > ReportStore.MAX_RECORD_BYTES,
                "这条夹具在砍之前确实越过 512 KB：下面那条断言不是空转");
        ReportStore.Record savedBurst = store.save(bursting);
        check(savedBurst.bytes <= ReportStore.MAX_RECORD_BYTES,
                "夹完落到 " + savedBurst.bytes + " 字节，仍在上限之内");
        check(savedBurst.html.isEmpty() && savedBurst.htmlCut,
                "字符上限兜不住时第二刀整份丢掉 HTML，并留下被丢掉的痕迹");
        same(Integer.valueOf(savedBurst.evidence.size()), Integer.valueOf(ReportStore.MAX_EVIDENCE),
                "被丢的是导出用的 HTML，不是详情页要用的证据表");
        same(Integer.valueOf(savedBurst.sources.size()), Integer.valueOf(13), "来源分布同样一行不丢");

        File countDir = tempDir("count");
        ReportStore counter = new ReportStore(countDir);
        long base = 1760000100000L;
        String oldestId = "";
        for (int i = 0; i <= ReportStore.MAX_RECORDS; i++) {
            ReportStore.Record kept = counter.save(hand("历史" + i + ".docx", base + i * 1000L));
            if (i == 0) oldestId = kept.id;
        }
        same(Integer.valueOf(counter.size()), Integer.valueOf(60), "存 61 条只留 60 条");
        same(Long.valueOf(counter.history().get(0).createdAt), Long.valueOf(base + 60000L), "最前一条是最新那条");
        same(Long.valueOf(counter.history().get(59).createdAt), Long.valueOf(base + 1000L),
                "被挤掉的是最旧的一条，新记录不会被自己最早的账挤掉");
        check(!new File(countDir, oldestId + ".json").exists(), "被挤掉那条的记录文件一起删掉，不留读不动的孤儿");
        check(counter.record(oldestId) == null, "已挤掉的编号读不回来");
        check(counter.indexBytes() <= ReportStore.MAX_INDEX_BYTES,
                "60 条摘要的索引 " + counter.indexBytes() + " 字节，在索引上限之内");
        check(counter.totalBytes() <= ReportStore.MAX_RECORDS * ReportStore.MAX_RECORD_BYTES,
                "整库实测 " + counter.totalBytes() + " 字节，最坏情况也压在上限乘积之下");

        File tieDir = tempDir("tie");
        ReportStore ties = new ReportStore(tieDir);
        ArrayList<String> saved = new ArrayList<String>();
        for (int i = 0; i < 3; i++) saved.add(ties.save(hand("同一毫秒.docx", 1760000200000L)).id);
        ArrayList<String> listed = new ArrayList<String>();
        for (int i = 0; i < ties.history().size(); i++) listed.add(ties.history().get(i).id);
        ArrayList<String> reversed = new ArrayList<String>();
        for (int i = saved.size() - 1; i >= 0; i--) reversed.add(saved.get(i));
        same(listed, reversed, "同一毫秒存三条：列表顺序由编号定序，两次读完全一致");
        deleteTree(dir);
        deleteTree(countDir);
        deleteTree(tieDir);
    }

    /** 长度正好 limit 字的注记，用来验证单条长度这一刀。 */
    private static String note(int length, int index) {
        StringBuilder out = new StringBuilder("注记" + index + "：");
        while (out.length() < length) out.append("这条提示用中文写成，长度由夹具算好");
        return out.substring(0, length);
    }

    // ---- 坏文件恢复：索引坏了按目录重建，单条坏了只丢那一条 ----

    private static void corruption() throws Exception {
        File dir = tempDir("index");
        File index = new File(dir, "index.json");
        ReportStore store = new ReportStore(dir);
        ReportStore.Record a = store.save(hand("甲.docx", 11L));
        ReportStore.Record b = store.save(hand("乙.docx", 22L));
        ReportStore.Record c = store.save(hand("丙.docx", 33L));
        check(index.isFile(), "三次保存后索引在场");

        String indexed = read(index);
        write(index, indexed.substring(0, indexed.length() / 2));
        ReportStore half = new ReportStore(dir);
        same(Integer.valueOf(half.history().size()), Integer.valueOf(3),
                "索引只剩半截（掉电就是这么留下的）：按目录重建，三条一条不少");
        same(half.history().get(0).fileName, "丙.docx", "重建出来的列表仍然是新的在前");
        check(half.history().get(0).duplicateChars == 500 && half.history().get(0).comparedChars == 4000,
                "重建不是只捡个文件名：相似率用的那几个数从记录里原样取回");
        Object reparsed = ApiJson.parse(read(index));
        same(Integer.valueOf(((java.util.List<?>) ((java.util.Map<?, ?>) reparsed).get("records")).size()),
                Integer.valueOf(3), "重建顺手把索引写回成一份能读的，下次开机不再走重建");

        index.delete();
        ReportStore noIndex = new ReportStore(dir);
        same(Integer.valueOf(noIndex.history().size()), Integer.valueOf(3), "索引整份没了也列得出历史");
        same(noIndex.record(b.id).fileName, "乙.docx", "重建之后按编号仍然读得回详情");
        check(index.isFile(), "读一次就把索引补回去");

        File bad = new File(dir, b.id + ".json");
        String intact = read(bad);
        write(bad, intact.substring(0, intact.length() / 2));
        ReportStore hurt = new ReportStore(dir);
        same(Integer.valueOf(hurt.history().size()), Integer.valueOf(3),
                "记录文件被截断但索引还在：列表照旧列三条，不偷偷少一条");
        check(hurt.record(b.id) == null, "读不动的那条返回 null，而不是画出半张报告");
        same(hurt.lastError(), "报告记录已损坏", "坏在哪儿说得出");
        check(hurt.record(a.id) != null && hurt.record(c.id) != null, "其余两条不受连累");
        index.delete();
        ReportStore rebuilt = new ReportStore(dir);
        same(Integer.valueOf(rebuilt.history().size()), Integer.valueOf(2),
                "按目录重建时那条读不动的自己出局，不拖累别人");
        check(!containsId(rebuilt, b.id), "出局的就是被截断那条");

        write(new File(dir, "r9999999999999-abcdef.json"), "{\"version\":99,\"id\":\"r9999999999999-abcdef\"}");
        ReportStore future = new ReportStore(dir);
        check(future.record("r9999999999999-abcdef") == null, "比本机还新的格式不猜着装懂");
        same(future.lastError(), "报告记录的版本比本机构还新", "拒读的理由写在 lastError 里");
        index.delete();
        ReportStore skips = new ReportStore(dir);
        same(Integer.valueOf(skips.history().size()), Integer.valueOf(2), "重建时看不懂版本的记录直接跳过");
        check(!containsId(skips, "r9999999999999-abcdef"), "看不懂的编号不会出现在列表里让人点空");

        File gone = new File(dir, a.id + ".json");
        check(gone.delete(), "把剩下两条里的一条文件删掉");
        ReportStore orphan = new ReportStore(dir);
        same(Integer.valueOf(orphan.history().size()), Integer.valueOf(1),
                "索引点了头但文件不在：那条不列，列表里点不出一份空报告");
        same(orphan.history().get(0).fileName, "丙.docx", "留下来的还是对的那条");
        deleteTree(dir);
    }

    private static boolean containsId(ReportStore store, String id) {
        ArrayList<ReportStore.Summary> history = store.history();
        for (int i = 0; i < history.size(); i++) if (history.get(i).id.equals(id)) return true;
        return false;
    }

    // ---- 护栏：拒收、数值收口、越界区间、编号越界、删除与清空 ----

    private static void guardrails() throws Exception {
        File dir = tempDir("guard");
        ReportStore store = new ReportStore(dir);
        check(store.save(null) == null && store.lastError().length() > 0, "空记录拒收，且说得出为什么");
        check(store.save(hand("   ", 5L)) == null, "没有文件名的报告拒收：列表里认不出是哪篇的记录不如不存");
        same(Integer.valueOf(store.size()), Integer.valueOf(0), "拒收不会留下半个文件");

        ReportStore.Record wild = hand("数值越界.docx", 6L);
        wild.sourceChars = 100;
        wild.overallRate = Double.NaN;
        wild.selfWrittenRate = 140d;
        wild.excludingCitationsRate = -3d;
        wild.duplicateChars = -7;
        wild.evidence.add(evidence(500, 20, "越界命中"));
        ReportStore.Evidence inside = evidence(10, 5, "正文之内");
        inside.score = 5d;
        wild.evidence.add(inside);
        ReportStore.Record tamed = store.save(wild);
        check(tamed != null, "数值离谱照样存得下来，只是被夹回合法域");
        same(Double.valueOf(tamed.overallRate), Double.valueOf(0d),
                "NaN 写进 JSON 会让整条记录读不回来，所以落盘前先夹成 0");
        same(Double.valueOf(tamed.selfWrittenRate), Double.valueOf(100d), "140% 夹成 100%");
        same(Double.valueOf(tamed.excludingCitationsRate), Double.valueOf(0d), "负数百分比夹成 0");
        same(Integer.valueOf(tamed.duplicateChars), Integer.valueOf(0), "负数字数夹成 0");
        same(Integer.valueOf(tamed.evidence.size()), Integer.valueOf(1),
                "落在正文之外的区间存进去就被丢掉：点它跳到别处去，比少一条证据坏得多");
        check(tamed.evidenceTruncated, "丢掉越界区间同样算截断，界面上要说得清");
        same(Double.valueOf(tamed.evidence.get(0).score), Double.valueOf(1d), "相似度分是 0-1 的分数，5 分夹成 1 分");
        check(ApiJson.parse(tamed.toJson()) != null, "夹完之后 JSON 仍然读得回来");

        same(Double.valueOf(ReportStore.rate(100.000001d)), Double.valueOf(100d), "比率收口在 0-100");
        same(Double.valueOf(ReportStore.fraction(-0.2d)), Double.valueOf(0d), "分数收口在 0-1");
        same(ReportStore.sanitizeId("../../evil"), "evil", "编号里的路径与点号一律剥掉");
        same(ReportStore.sanitizeId("r1-ABC"), "r1-abc", "编号统一小写");
        check(store.record("../evil") == null, "越出目录的编号读不到东西");
        check(store.record("") == null, "空编号读不到东西");
        check(!store.delete("no-such-id"), "删不存在的编号返回 false");

        ReportStore.Record jump = hand("能不能跳.docx", 7L);
        jump.sourceChars = 100;
        jump.evidence.add(evidence(10, 20, "在正文里"));
        ReportStore.Record jumped = store.save(jump);
        check(jumped.canJump(jumped.evidence.get(0)), "区间在正文之内才允许跳");
        check(!jumped.canJump(evidence(30, 0, "反向区间")), "end 不越过 start 的区间不许跳");
        check(!jumped.canJump(evidence(90, 20, "超出正文")), "end 越过正文长度的区间不许跳");
        check(!jumped.canJump(null), "空证据不许跳");

        check(ReportStore.digest("甲乙丙") != ReportStore.digest("甲乙丁"),
                "改一个字指纹就变：跳正文前那道验证不是摆设");
        same(Long.valueOf(ReportStore.digest(null)), Long.valueOf(ReportStore.digest("")), "null 与空串同一个指纹，不抛异常");

        same(ReportStore.coverageLine(3, 6, 1200, 4000, true), "检索覆盖 3/6 窗口 · 1200/4000 字 · 相似率是下限",
                "部分完成那句话逐字符定稿：面板与详情页读同一句");
        same(ReportStore.coverageLine(6, 6, 4000, 4000, false), "检索覆盖 6/6 窗口 · 4000/4000 字",
                "跑满了就不许再挂相似率是下限那句");

        StringBuilder small = new StringBuilder();
        while (small.length() < 200) small.append("<p>一段中文正文</p>");
        String cut = ReportStore.cutHtml(small.toString(), 60);
        check(cut.length() <= 60, "HTML 截断不越字符上限");
        check(cut.endsWith("此处按上限截断。</em></p>"), "截断处留一句声明");
        check(cut.startsWith("<p>一段中文正文</p>"), "开头原样保住，一整段都在");
        check(cut.substring(0, cut.indexOf("<p><em>")).endsWith("</p>"), "声明之前收尾于完整的 </p>：不在段落中间下刀");
        same(Integer.valueOf(ReportStore.cutHtml("一二三", 2).length()), Integer.valueOf(2),
                "上限比那句声明还短时就硬切，长度仍然越不过上限");
        String noEdge = ReportStore.cutHtml("<div>" + repeat('字', 200) + "</div>", 60);
        same(Integer.valueOf(noEdge.length()), Integer.valueOf(60), "找不到段落边界时硬切到上限，宁可不齐也不越预算");
        same(ReportStore.cutHtml("短的", 60), "短的", "没超上限就一个字都不动");

        ReportStore.Record temp = store.save(hand("删除与清空.docx", 8L));
        same(Integer.valueOf(store.history().size()), Integer.valueOf(3), "越界、能不能跳、删除与清空各一条");
        check(store.delete(temp.id), "删得掉");
        check(!new File(dir, temp.id + ".json").exists(), "删记录连文件一起删");
        check(store.record(temp.id) == null, "删完读不回来");
        same(Integer.valueOf(store.history().size()), Integer.valueOf(2), "索引跟着少一行");
        store.clear();
        same(Integer.valueOf(store.history().size()), Integer.valueOf(0), "清空之后列表为空");
        deleteTree(dir);
    }

    private static String repeat(char c, int times) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++) out.append(c);
        return out.toString();
    }

    public static void main(String[] args) throws Exception {
        persistedScan();
        caps();
        corruption();
        guardrails();
        System.out.println("SUMMARY " + checks + " report-store assertions passed"
                + "；一次比对落盘、原样读回、体积封顶、坏文件恢复，全程不联网不碰 Android");
    }
}
