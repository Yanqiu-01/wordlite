package com.rikkahub.wordlite;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;

/**
 * 重复率 0% 的诊断台。只诊断：不改判据、不改阈值、不碰界面，也不改 tools/test-host.ps1。
 *
 * 它做三件事：
 * 1. 把"总相似度比"的分子分母按产品链路原样打出来（CharLedger 那一次划分：命中区间并集的有效字符 /
 *    排除区之外的一切有效字符，两者同一次划分）；
 * 2. 逐条跑"能合法得到 0% 的路径"，每条都打命中数、候选数、总字数、最终百分比；
 * 3. 每条给一个可核查的判定：这一轮的 0% 是"检索根本没拿到可比对象"还是"论文真的没重复"。
 *
 * 输入全部来自 tests/ 下已有样例：真文档 tests/samples/input-liu.docx（正文走的正是产品那条
 * DocxParser -> TextSelection.all）与真实语料 tests/corpus/real-prose.txt。检索侧只用一个监听
 * 127.0.0.1 的桩服务顶掉九个源的真接口（PaperSources.setEndpoint 是包内测试口子），桩回什么由 mode
 * 决定：empty=200+零条目，error=全部非 200，mixed=一个源 200+零条目其余全非 200，
 * offtopic=回了条目但与检索式零共同三元组，ontopic=回了一篇摘要含本文原句的候选。全程不出外网。
 */
public final class ZeroRateAudit {
    static final String[] ALL_ENGINES = { "cnki", "cqvip", "wanfang", "ncpssd", "openalex",
            "crossref", "semantic-scholar", "europepmc", "arxiv" };
    /** 与中文正文一个共同三元组都没有的候选：BM25 零分闸门本该整条挡住它。 */
    private static final String OFF_TOPIC =
            "ESPnet2 end-to-end speech recognition toolkit for transformer based multilingual corpora";

    private static TextSelection selection;
    private static String plant = "";
    private static String mode = "empty";
    private static String lastProgress = "";
    private static final LinkedHashMap<String, Integer> requests = new LinkedHashMap<String, Integer>();
    private static int failures;

    private static void line(String key, Object value) {
        System.out.println("    " + key + " = " + value);
    }

    private static void head(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    private static void check(boolean ok, String message) {
        System.out.println("  " + (ok ? "[OK]   " : "[FAIL] ") + message);
        if (!ok) failures++;
    }

    /** 与 ApiWorkflow.showScan / ReportCenterUI 同一个写法，打出来的就是用户屏幕上那串。 */
    private static String pct(double rate) {
        return String.format(Locale.CHINA, "%.2f%%", Double.valueOf(rate));
    }

    private static String state(DuplicateEngine.Report report) {
        return ReportStore.recordFor(report, "audit.docx").state;
    }

    /** 一次 scan 的全部原始读数：命中数、候选数、总字数、最终百分比、三态、注记。 */
    private static void dump(String label, DuplicateEngine.Report report, int corpusEntries) {
        System.out.println("  [" + label + "]");
        line("报告那一格", pct(report.overallRate));
        line("去除引用重复比", pct(report.excludingCitationsRate));
        line("自编率", pct(report.selfWrittenRate));
        line("分子 duplicateChars（命中区间取并集后的有效字符）", Integer.valueOf(report.duplicateChars));
        line("其中引用区间内 citedDuplicateChars", Integer.valueOf(report.citedDuplicateChars));
        line("分母 comparedChars（= ledger.totalChars）", Integer.valueOf(report.comparedChars));
        line("结构排除 excludedChars", Integer.valueOf(report.excludedChars));
        line("账本闭合残差 residual（必须为 0）",
                report.ledger == null ? Integer.valueOf(-1) : Integer.valueOf(report.ledger.residual()));
        line("命中段数 hits.size()", Integer.valueOf(report.hits.size()));
        line("report.candidates（零分闸门之后才入库的那批）", Integer.valueOf(report.candidates.size()));
        line("入库可比 comparableCandidates", Integer.valueOf(report.comparableCandidates));
        line("仅题录 recordOnly", Integer.valueOf(report.recordOnlyCandidates));
        line("与检索词零共同词被挡 unranked", Integer.valueOf(report.unrankedCandidates));
        line("抓到开放获取全文 fullText", Integer.valueOf(report.fullTextCandidates));
        line("语料句子条目 corpus.sentenceCount()", Integer.valueOf(corpusEntries));
        line("窗口 available/planned/retrieved", report.windowsAvailable + " / " + report.windowsPlanned
                + " / " + report.windowsRetrieved);
        line("检索覆盖分母 comparableChars / 已覆盖 coveredChars",
                report.comparableChars + " / " + report.coveredChars);
        line("逐源提问次数 windowsAsked", report.windowsAsked);
        line("逐源候选数 candidateCount", report.candidateCount);
        line("来源分布 byEngine", report.byEngine);
        line("retrievalIncomplete", Boolean.valueOf(report.retrievalIncomplete)
                + "  reason=" + report.retrievalReason);
        line("retrievalPartial", Boolean.valueOf(report.retrievalPartial)
                + "  reason=" + report.retrievalPartialReason);
        line("报告中心状态 state", state(report));
        line("报告中心比率是否照印 metricsValid",
                Boolean.valueOf(ReportStore.recordFor(report, "audit.docx").metricsValid()));
        line("最后一次进度回调", lastProgress);
        line("耗时 ms", Long.valueOf(report.elapsedMillis));
        for (String note : report.notes) line("NOTE", note);
    }

    private static PaperSources.Limits limits(int windows) {
        PaperSources.Limits limits = new PaperSources.Limits();
        limits.timeoutSeconds = 5;
        limits.perEngine = 12;
        limits.windows = windows;
        limits.fullTexts = DuplicateEngine.MAX_FULL_TEXTS;
        limits.proxy = "";
        return limits;
    }

    private static ArrayList<String> engines(String[] ids) {
        ArrayList<String> out = new ArrayList<String>();
        for (String id : ids) out.add(id);
        return out;
    }

    private static DuplicateEngine.Report scan(TextCorpus corpus, boolean useWeb, ArrayList<String> engines) {
        return DuplicateEngine.scan(selection, corpus, useWeb, engines, limits(2), null,
                new DuplicateEngine.Progress() {
                    public void step(String label, int done, int total) {
                        lastProgress = label + " " + done + "/" + total;
                    }
                });
    }

    private static TextCorpus.Source source(String id, String title) {
        TextCorpus.Source source = new TextCorpus.Source();
        source.engine = "local";
        source.id = id;
        source.title = title;
        source.locator = id;
        source.year = "";
        source.authors = "";
        return source;
    }

    /** 逐字符码点平移一位：每个字都变，字面三元组一枚不剩，用来量"改写到判据下线"那一档。 */
    private static String shifted(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            out.append(Character.isHighSurrogate(c) || Character.isLowSurrogate(c) ? c : (char) (c + 1));
        }
        return out.toString();
    }

    private static int countLines(String value) {
        int count = value.isEmpty() ? 0 : 1;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) == '\n') count++;
        return count;
    }

    public static void main(String[] args) throws Exception {
        File docx = new File(args.length > 0 ? args[0] : "tests/samples/input-liu.docx");
        File proseFile = new File(args.length > 1 ? args[1] : "tests/corpus/real-prose.txt");
        DocxDocument document = DocxParser.parse(new FileInputStream(docx), docx.getName());
        selection = TextSelection.all(document);
        String text = selection.text;
        String norm = TextCorpus.normalize(text);
        String prose = new String(Files.readAllBytes(proseFile.toPath()), StandardCharsets.UTF_8);
        DuplicateEngine.WindowPlan plan = DuplicateEngine.windowPlan(text, Integer.MAX_VALUE);

        head("输入与口径（产品链路：DocxParser -> TextSelection.all -> DuplicateEngine.scan）");
        line("文档", docx.getPath() + "（段落 " + document.paragraphs.size() + "）");
        line("正文字符 text.length()", Integer.valueOf(text.length()));
        line("正文有效字符 validCount(整篇)",
                Integer.valueOf(TextCorpus.validCount(norm, 0, text.length())));
        TextCorpus.Structure structure = TextCorpus.structure(text);
        line("结构排除节数 / 字数（参考文献表、致谢、目录）",
                structure.sectionCount() + " / " + structure.excludedChars);
        line("可切检索窗口组数 windowPlan.groups", Integer.valueOf(plan.groups.size()));
        line("可检索正文 comparableChars", Integer.valueOf(plan.comparableChars));
        line("语料样例", proseFile.getPath() + "（" + countLines(prose) + " 行）");
        int probed = 0, inside = 0;
        for (String row : prose.split("\n")) {
            String row2 = row.trim();
            if (row2.length() < 24) continue;
            if (++probed >= 30) break;
            if (text.contains(row2)) inside++;
        }
        line("取样核对：语料前 " + probed + " 行里逐字出现在本文中的行数", Integer.valueOf(inside));

        String windowZero = plan.groups.isEmpty() ? "" : plan.groups.get(0);
        int[] pick = null;
        int best = 0;
        for (int[] s : TextCorpus.sentences(windowZero)) {
            String sentence = windowZero.substring(s[0], s[1]);
            int n = TextCorpus.validCount(TextCorpus.normalize(sentence), 0, sentence.length());
            if (n >= 26 && n <= 90 && n > best) { best = n; pick = s; }
        }
        if (pick == null) throw new IllegalStateException("第一个检索窗口里没有 26-90 字的句子，换样例文档");
        plant = windowZero.substring(pick[0], pick[1]).trim();
        line("植入句（逐字取自本文第 1 扇检索窗口）", plant);
        line("植入句有效字符", Integer.valueOf(TextCorpus.validCount(TextCorpus.normalize(plant), 0, plant.length())));
        check(!plan.groups.isEmpty(), "样例文档切得出检索窗口");

        // ---- A0 口径：自建库内容与本文逐字重合时，分子分母必须数得出接近上限的数 ----
        head("场景 A0：本机查重（未联网），自建库=与本文逐字重合的真实语料。零网络");
        TextCorpus same = new TextCorpus();
        same.add(source("local:real-prose.txt", "自建库：real-prose.txt"), prose);
        DuplicateEngine.Report a0 = scan(same, false, null);
        dump("A0 未联网 + 自建库与本文重合", a0, same.sentenceCount());
        check(a0.comparedChars == TextCorpus.validCount(norm, 0, text.length()) - a0.excludedChars,
                "A0：分母 == 整篇有效字符 - 结构排除字数。分母取的是本文，不是语料条数、也不是语料字数");
        check(Integer.valueOf(a0.ledger.residual()).equals(Integer.valueOf(0)), "A0：三个互斥桶闭合 100%");
        check(a0.overallRate > 0d, "A0：语料里有重合文字时率照实上去，判据不会把非零压成零");
        line("分母对照", "语料条目 " + same.sentenceCount() + " 句 / 语料字符 " + prose.length()
                + "；分母 " + a0.comparedChars + "，与前者无数值关系");
        line("四舍五入闸门", "本文分母 " + a0.comparedChars + " 字：命中 1 个字就印 "
                + pct(100d / (double) Math.max(1, a0.comparedChars))
                + "；要印成 0.00% 必须分子 < " + (a0.comparedChars * 0.00005d) + " 字，也就是分子恰好等于 0");

        // ---- A 自建库非空但不对题：这是"合法 0%"的机器路径 ----
        head("场景 A：本机查重（未启用联网检索），自建库非空但不对题。零网络");
        File offFile = new File(args.length > 2 ? args[2] : "tests/corpus/aigc-cartoon.txt");
        String off = new String(Files.readAllBytes(offFile.toPath()), StandardCharsets.UTF_8);
        TextCorpus unrelated = new TextCorpus();
        unrelated.add(source("local:" + offFile.getName(), "自建库：与本文不同主题的真实文本"), off);
        DuplicateEngine.Report a = scan(unrelated, false, null);
        dump("A 未联网 + 非空但不对题的自建库", a, unrelated.sentenceCount());
        check(!unrelated.isEmpty(), "A：自建库非空（markRetrievalGap 因此不报警）");
        check(a.retrievalIncomplete == false, "A：这一轮不是\"未完成查重\"");
        check(ReportStore.STATE_COMPLETE.equals(state(a)), "A：报告中心状态=\"完整检索\"，比率照印");
        check(a.windowsPlanned == 0, "A：未联网时检索窗口 0，界面那行覆盖率不显示，报告里也没有\"检索覆盖率\"数字");

        // ---- B 反证：语料里真有那句原文，率必须起来 ----
        head("场景 B：语料里逐字植入本文的一句（同一条链路，只换语料内容）");
        TextCorpus verbatim = new TextCorpus();
        verbatim.add(source("10.1000/planted", "一篇真把这句话写在前面的文献"), plant);
        DuplicateEngine.Report b = scan(verbatim, false, null);
        dump("B 语料含逐字原文", b, verbatim.sentenceCount());
        check(!b.hits.isEmpty() && b.duplicateChars > 0 && b.overallRate > 0d,
                "B：判据与分子分母本身没坏——语料里有原文时率就不是 0");

        // ---- C 阈值/粒度：同一句改写重到字面三元组一枚不剩 ----
        head("场景 C：语料非空，但放的是那句的等长改写（逐字符平移，字面三元组零重合）");
        String paraphrase = shifted(plant);
        TextCorpus rewritten = new TextCorpus();
        rewritten.add(source("10.1000/rewritten", "同一句话的重写本"), paraphrase);
        DuplicateEngine.Report c = scan(rewritten, false, null);
        dump("C 语料只有重写句", c, rewritten.sentenceCount());
        line("TextCorpus.dice(原句, 改写句)", Float.valueOf(TextCorpus.dice(plant, paraphrase))
                + "；判据门槛 SIMILAR_DICE=" + Float.valueOf(TextCorpus.SIMILAR_DICE)
                + "，包含率门槛 SIMILAR_CONTAINMENT=" + Float.valueOf(TextCorpus.SIMILAR_CONTAINMENT)
                + "，最短句门 MIN_SENTENCE_CHARS=" + Integer.valueOf(TextCorpus.MIN_SENTENCE_CHARS));
        check(TextCorpus.dice(plant, paraphrase) == 0f, "C：这条改写在字面三元组口径下 Dice 为 0，判据不认");
        check(c.retrievalIncomplete == false && c.overallRate == 0d,
                "C：语料非空但字面无可重合内容 -> 合法 0%，且状态仍是\"完整检索\"");

        // ---- D 起：九个源全部指向回环桩 ----
        HttpServer server = start();
        DuplicateEngine.searchMillis = 30000L;
        DuplicateEngine.engineGapMillis = 0L;
        try {
            mode = "empty";
            requests.clear();
            DuplicateEngine.Report d = scan(new TextCorpus(), true, engines(ALL_ENGINES));
            dump("D 联网：九个源全部 HTTP 200、但一条条目都解不出来（反爬页 / 版面改版 / 真零命中同一形状）", d, 0);
            line("桩收到的请求", requests);
            check(requests.size() > 0, "D：请求确实发出去了，不是没问");
            check(d.candidates.isEmpty(), "D：候选 0 篇");
            /* 2.2.0：这一档不再是"完整检索 + 0.00%"。HTTP 200 + 零条目在 everyConnectorFailed 里
            仍然不算失败（它只看 skipped），但可比正文 0 篇本身就是未完成：百分比不许出口。 */
            check(d.retrievalIncomplete,
                    "D：HTTP 200 + 零条目骗得过 everyConnectorFailed，骗不过可比正文 0 篇这一档");
            check(ReportStore.STATE_UNFINISHED.equals(state(d))
                    && !ReportStore.recordFor(d, "audit.docx").metricsValid(),
                    "D：报告中心进未完成查重那一档，总相似度比不印");
            check(d.overallRate == 0d && d.retrievalReason != null
                    && d.retrievalReason.contains("可比正文 0 篇"),
                    "D：账本仍是 0.00%（分子真的是 0），但理由必须写明是可比正文 0 篇");
            check(d.overallRate == 0d, "D：这一轮实测就是 0.00%");

            mode = "error";
            requests.clear();
            DuplicateEngine.Report e = scan(new TextCorpus(), true, engines(ALL_ENGINES));
            dump("E 联网：九个源全部 HTTP 500（传输层报错，异常沿 IOException 上抛）", e, 0);
            line("桩收到的请求", requests);
            check(e.retrievalIncomplete,
                    "E：全源报错被标成未完成——异常没被吞掉当成\"0 条命中\"继续算分");
            check(ReportStore.STATE_UNFINISHED.equals(state(e))
                    && !ReportStore.recordFor(e, "audit.docx").metricsValid(),
                    "E：报告中心状态=未完成查重且 metricsValid=false，界面与详情都不印比率");

            mode = "mixed";
            requests.clear();
            DuplicateEngine.Report m = scan(new TextCorpus(), true, engines(ALL_ENGINES));
            dump("E2 联网：只有 1 个源答话（200 + 零条目），其余 8 个源全部 HTTP 500", m, 0);
            line("桩收到的请求", requests);
            check(m.retrievalIncomplete,
                    "E2：1 个源答话不再等于这一轮成立——可比正文 0 篇照样是未完成");
            check(ReportStore.STATE_UNFINISHED.equals(state(m))
                    && !ReportStore.recordFor(m, "audit.docx").metricsValid(),
                    "E2：状态是未完成查重，比率不印给用户");
            check(m.overallRate == 0d && m.candidates.isEmpty(), "E2：实测 0.00%，候选 0 篇");

            mode = "offtopic";
            requests.clear();
            DuplicateEngine.Report f = scan(new TextCorpus(), true, engines(ALL_ENGINES));
            dump("F 联网：检索回了条目，但每条与检索式零共同三元组（BM25 零分闸门之外）", f, 0);
            line("桩收到的请求", requests);
            check(f.unrankedCandidates > 0 && f.comparableCandidates == 0,
                    "F：候选被零分闸门整条挡住，一篇都没进语料（report.candidates 是闸门之后那份，所以为 0）");
            check(f.overallRate == 0d && f.retrievalIncomplete
                    && f.retrievalReason.contains("零共同词"),
                    "F：可比对象一篇都没进语料，0.00% 被标成未完成，不许读成这篇干净");

            // ---- D2：连接根本没建成。真机那一轮就是这一档，而它要的下一步与"可比正文 0 篇"相反 ----
            String liveBase = "http://127.0.0.1:" + server.getAddress().getPort();
            pointAll("http://127.0.0.1:1");
            requests.clear();
            DuplicateEngine.Report dead = scan(new TextCorpus(), true, engines(ALL_ENGINES));
            dump("D2 联网：九个源的地址全部指向没人监听的端口（手机没有网络出口：连接层就失败，站点一个字都没答）", dead, 0);
            check(requests.isEmpty(), "D2：连接没建成，桩一次请求都没收到——这一档不是站点拒绝回答");
            check(dead.hostsAsked >= DuplicateEngine.MIN_HOSTS_FOR_NO_ROUTE && dead.hostsReached == 0
                            && dead.hostsUnreachable == dead.hostsAsked,
                    "D2：连通那本账记成 " + dead.hostsAsked + " 个里通了 " + dead.hostsReached + " 个");
            check(DuplicateEngine.noNetworkExit(dead),
                    "D2：这一轮认成手机没有出口，而不是可比正文 0 篇");
            check(dead.retrievalReason != null
                            && dead.retrievalReason.startsWith("手机这次没能连上任何检索源")
                            && dead.retrievalReason.contains("个检索源里通了 0 个"),
                    "D2：那一句先说没连上，数字跟在原因后面：" + dead.retrievalReason);
            check(dead.retrievalReason != null && dead.retrievalReason.contains("phone-gateway"),
                    "D2：那一句紧跟一步能救回来的：连 WLAN/移动数据，或跑 tools/phone-gateway.ps1");
            check(ReportStore.STATE_UNFINISHED.equals(state(dead))
                            && !ReportStore.recordFor(dead, "audit.docx").metricsValid(),
                    "D2：状态是未完成查重，比率不印给用户");
            check(!DuplicateEngine.noNetworkExit(null), "D2：报告为空时不猜这一档");
            DuplicateEngine.Report half = new DuplicateEngine.Report();
            half.hostsAsked = 10; half.hostsReached = 3; half.hostsUnreachable = 7;
            check("10 个检索源里通了 3 个，其余 7 个连接没建成".equals(DuplicateEngine.hostTallyLine(half)),
                    "D2：通了 3 个与通了 0 个是两句相反的话：" + DuplicateEngine.hostTallyLine(half));
            check(!DuplicateEngine.noNetworkExit(half),
                    "D2：只要有一个源答过话就不算没有出口——那一步是换问法，不是修网线");
            check(DuplicateEngine.reachOf(new ApiClient.Failure("网络连接失败: Connection refused", 0)) < 0
                            && DuplicateEngine.reachOf(new ApiClient.Failure("请求超时", 0)) < 0
                            && DuplicateEngine.reachOf(new ApiClient.Failure("试过的路都没通：2", 0)) < 0,
                    "D2：拨不上、超时、选路用尽三类全部记成没通");
            check(DuplicateEngine.reachOf(new ApiClient.Failure("HTTP 403", 403)) > 0
                            && DuplicateEngine.reachOf(new ApiClient.Failure("HTTP 429", 429)) > 0,
                    "D2：403/429 是答了话在挡人，不许算成没通");
            check(DuplicateEngine.reachOf(new ApiClient.Failure("已取消", 0)) == 0,
                    "D2：取消两边都不记，免得把用户自己停掉算成站点故障");
            pointAll(liveBase);

            mode = "ontopic";
            requests.clear();
            ArrayList<String> single = new ArrayList<String>();
            single.add("crossref");
            DuplicateEngine.Report g = scan(new TextCorpus(), true, single);
            dump("G 联网：检索回一篇摘要含本文原句的候选（同一条链路，只换候选内容）", g, 0);
            line("桩收到的请求", requests);
            check(g.comparableCandidates > 0, "G：这篇进了语料");
            check(!g.hits.isEmpty() && g.overallRate > 0d, "G：只要检索真把出处问回来，0% 就不是判据的问题");

            head("场景 H：报告中心读盘——默认值会不会被当成结果（Record.fromJson 是包内测试口子）");
            ReportStore.Record back = ReportStore.Record.fromJson(
                    ApiJson.parse(ReportStore.recordFor(e, "a.docx").toJson()));
            line("H1 一轮未完成的记录存盘再读回 state / rate", back.state + " / " + pct(back.overallRate));
            check(ReportStore.STATE_UNFINISHED.equals(back.state) && !back.metricsValid(),
                    "H1：未完成记录走正常存盘再读回，仍是未完成，比率不印");
            ReportStore.Record hole = ReportStore.Record.fromJson(ApiJson.parse(
                    "{\"version\":1,\"id\":\"x\",\"fileName\":\"b.docx\"}"));
            line("H2 缺 state 缺 metrics 的记录 state", "[" + hole.state + "]");
            line("H2 缺 state 缺 metrics 的记录 overallRate", pct(hole.overallRate));
            line("H2 缺 state 缺 metrics 的记录 metricsValid", Boolean.valueOf(hole.metricsValid()));
            check(hole.state.isEmpty() && hole.metricsValid() && hole.overallRate == 0d,
                    "H2：读盘一侧不调 normalize()（只在写盘侧 normalized() 里调），state 缺失就原样留着空串；"
                            + "而 metricsValid() 只认\"未完成查重\"这四个字，空串照样通过，比率按字段默认值印成 0.00%");
        } finally {
            PaperSources.resetEndpoints();
            DuplicateEngine.searchMillis = DuplicateEngine.MAX_SEARCH_MILLIS;
            DuplicateEngine.engineGapMillis = DuplicateEngine.MIN_ENGINE_GAP_MILLIS;
            server.stop(0);
        }

        head("结论读数（原样，不做修饰）");
        line("A0 自建库与本文重合", pct(a0.overallRate) + "  state=" + state(a0));
        line("A  自建库非空但不对题", pct(a.overallRate) + "  state=" + state(a));
        line("B  语料含逐字原文", pct(b.overallRate) + "  state=" + state(b));
        line("C  语料只含重写句", pct(c.overallRate) + "  state=" + state(c));
        System.out.println("诊断断言失败 " + failures + " 项");
        if (failures > 0) throw new AssertionError(failures + " 项诊断断言失败");
        System.out.println("诊断台全部断言通过");
    }

    // ---- 回环桩：九个源共用的空壳服务，只监听 127.0.0.1 ----

    private static HttpServer start() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String[] paths = { "/cnki", "/cqvip", "/wanfang", "/ncpssd", "/openalex", "/crossref",
                "/semantic-scholar", "/europepmc/search", "/arxiv" };
        for (String path : paths) server.createContext(path, exchange -> respond(exchange, path));
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(16, runnable -> {
            Thread thread = new Thread(runnable, "audit-stub");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        pointAll("http://127.0.0.1:" + server.getAddress().getPort());
        return server;
    }

    /** 九个源一次性指向同一个地址：端口换成没人监听的那一个，就是"手机没有出口"那一档。 */
    private static void pointAll(String base) {
        PaperSources.setEndpoint("cnki", base + "/cnki");
        PaperSources.setEndpoint("cqvip", base + "/cqvip");
        PaperSources.setEndpoint("wanfang", base + "/wanfang");
        PaperSources.setEndpoint("ncpssd", base + "/ncpssd");
        PaperSources.setEndpoint("openalex", base + "/openalex");
        PaperSources.setEndpoint("crossref", base + "/crossref");
        PaperSources.setEndpoint("semantic-scholar", base + "/semantic-scholar");
        PaperSources.setEndpoint("europepmc", base + "/europepmc/search");
        PaperSources.setEndpoint("arxiv", base + "/arxiv");
    }

    /** 这一路要不要报错：mixed 只留 crossref 活口，error 全灭，其余全 200。状态与响应体分开算。 */
    private static boolean refuses(String path) {
        if ("error".equals(mode)) return true;
        return "mixed".equals(mode) && !path.endsWith("/crossref");
    }

    private static void respond(HttpExchange exchange, String path) throws java.io.IOException {
        synchronized (requests) {
            Integer seen = requests.get(path);
            requests.put(path, Integer.valueOf(seen == null ? 1 : seen.intValue() + 1));
        }
        try (InputStream in = exchange.getRequestBody()) {
            byte[] buffer = new byte[4096];
            while (in.read(buffer) >= 0) { /* 请求体读掉就行，桩不看内容 */ }
        } catch (Exception ignored) { }
        int status = refuses(path) ? 500 : 200;
        byte[] payload;
        if (path.endsWith("/wanfang")) {
            /* gRPC-web 空帧：帧头在、载荷零字节，解出 0 条题录，不报错。 */
            payload = new byte[] { 0, 0, 0, 0, 0 };
        } else {
            String body = "{}";
            if (path.endsWith("/cnki") || path.endsWith("/cqvip")) {
                body = "<html><body>请输入验证码后继续访问</body></html>";
            } else if (path.endsWith("/arxiv")) {
                body = "<feed xmlns=\"http://www.w3.org/2005/Atom\"></feed>";
            } else if (status == 200 && "offtopic".equals(mode)
                    && (path.endsWith("/crossref") || path.endsWith("/openalex"))) {
                body = offTopicJson();
            } else if (status == 200 && "ontopic".equals(mode) && path.endsWith("/crossref")) {
                body = onTopicJson();
            }
            payload = body.getBytes(StandardCharsets.UTF_8);
        }
        try {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, payload.length);
            OutputStream out = exchange.getResponseBody();
            out.write(payload);
            out.close();
        } catch (Exception ignored) { }
        exchange.close();
    }

    private static String offTopicJson() {
        StringBuilder out = new StringBuilder("{\"message\":{\"items\":[");
        for (int i = 0; i < 12; i++) {
            if (i > 0) out.append(',');
            out.append("{\"DOI\":").append(ApiJson.quote("10.1000/offtopic." + i))
                    .append(",\"title\":[").append(ApiJson.quote(OFF_TOPIC + " part " + i)).append("]")
                    .append(",\"abstract\":").append(ApiJson.quote(OFF_TOPIC + " volume " + i + " pages 1-9"))
                    .append('}');
        }
        return out.append("]}}").toString();
    }

    private static String onTopicJson() {
        String title = plant.length() > 24 ? plant.substring(0, 24) : plant;
        return "{\"message\":{\"items\":[{\"DOI\":" + ApiJson.quote("10.1000/ontopic.1")
                + ",\"title\":[" + ApiJson.quote(title) + "],\"abstract\":" + ApiJson.quote(plant) + "}]}}";
    }
}
