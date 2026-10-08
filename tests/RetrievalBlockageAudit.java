package com.rikkahub.wordlite;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;

/**
 * 检索被挡住的三种形状必须说三句不同的话（2.5.1）。
 *
 * <p>真机实测（Huawei CDY-AN90 / Android 10，2026-10-09，装的是 2.5.0）：电脑上不跑反代时，
 * tools/device-probe.ps1 的 TCP 探针读数是 "=&gt; 0 of 10 open"，十路检索源全部 ECONNREFUSED，
 * 结果页却写着"没有任何可比正文，相似度量不到"——学生把这行读成"我这稿子干净"。反代跑起来之后
 * 同一份代码 usable=3 failures=0，结果页是总相似度比 0.13% 加"知网、万方、维普只回摘要"。
 * 两种情况差的是出口，不是稿子，收尾那句话必须分开。</p>
 *
 * <p>这一套用回环桩把三档各跑一遍，比的是结果页收尾的字：①一个源都没答话——第一行是原因并把
 * 通了几个数带上，换行之后才是两条出路；②答了话但可比正文 0 篇——沿用今天那句，一字不改，
 * "知网、万方、维普只回摘要"也照旧留着；③可比正文 N 篇（N&gt;0）——整份报告逐字节不变。
 * 基准串与基准哈希取自 e9f5322（2.5.0 那棵树），样例文档、窗口数与桩和这里同一套。</p>
 *
 * <p>结果页那一行出自 ApiWorkflow.showScan()：它把 report.retrievalReason 原样贴上去，
 * HTML 报告那一屏出自 CheckReport.unfinished()，两边同一个串，所以这里比这一个串就够。</p>
 */
public final class RetrievalBlockageAudit {

    /** 九源全选：与真机检索设置的默认一致，也让"三个以下不许说没出口"那条判据真的约束得住。 */
    private static final String[] ENGINES = { "cnki", "cqvip", "wanfang", "ncpssd", "openalex",
            "crossref", "semantic-scholar", "europepmc", "arxiv" };

    /** e9f5322 上"全部拨不上"那一档的收尾原话。它把出口问题说成了材料问题。 */
    private static final String HEAD_CLOSING_NO_ROUTE =
            "9 个检索源本次全部不可用，联网检索没有取回可比对的候选文献";
    /** e9f5322 上"答了话但可比正文 0 篇"那一档的收尾原话，这一句必须继续一字不改。 */
    private static final String HEAD_CLOSING_NO_COMPARABLE =
            "联网检索取回 1 条候选，可比正文 0 篇（只有题录 1 篇，与检索词零共同词 11 篇），"
                    + "没有任何可比正文，相似度量不到：把检索设置的窗口数调大、允许开放获取全文抓取，"
                    + "或先把疑似来源的原文导入自建库再查一次";
    /** 中文三库只到摘要这条结构性短板，两档量不到的轮次都必须继续说出口。 */
    private static final String NOTE_ABSTRACT_ONLY =
            "知网、万方、维普只回摘要，正文与图表无法比对，相似率是下限";
    /** e9f5322 上"可比正文 1 篇"那一档整份报告的哈希，口径见 canonical()。 */
    private static final String HEAD_REPORT_HASH_WITH_COMPARABLE =
            "0d41e75c4a3bea23176841bd2dce26a102680d6204ca7a07e2fa6203ffcae47e";

    private static final String CAUSE_NO_ROUTE = "手机这次没能连上任何检索源";
    private static final String FIX_WLAN = "WLAN 或移动数据";
    private static final String FIX_GATEWAY = "pwsh tools/phone-gateway.ps1";

    private static TextSelection selection;
    private static String plant = "";
    private static String mode = "body";
    private static int failures;

    /**
     * 结果页收尾那一行：未完成那一屏是加粗的原因行，跑通那一屏是摘要层那一行。
     * ApiWorkflow 与 CheckReport 都原样贴它，不改写、不拼接。
     */
    private static String closing(DuplicateEngine.Report report) {
        if (report.retrievalIncomplete) {
            return report.retrievalReason == null
                    ? "检索没有取回可比对的文献，相似度类指标无法成立" : report.retrievalReason;
        }
        String layer = DuplicateEngine.abstractLayerLine(report);
        return layer.isEmpty() ? "" : DuplicateEngine.ABSTRACT_LAYER_LABEL + " " + layer;
    }

    private static boolean notesMention(DuplicateEngine.Report report, String fragment) {
        for (String note : report.notes) if (note.contains(fragment)) return true;
        return false;
    }

    private static void check(boolean ok, String message) {
        System.out.println((ok ? "  [ok]   " : "  [FAIL] ") + message);
        if (!ok) failures++;
    }

    private static DuplicateEngine.Report scan() {
        ArrayList<String> engines = new ArrayList<String>();
        for (String id : ENGINES) engines.add(id);
        PaperSources.Limits limits = new PaperSources.Limits();
        limits.timeoutSeconds = 4; limits.perEngine = 12; limits.windows = 2;
        limits.fullTexts = DuplicateEngine.MAX_FULL_TEXTS; limits.proxy = "";
        return DuplicateEngine.scan(selection, new TextCorpus(), true, engines, limits, null,
                new DuplicateEngine.Progress() {
                    public void step(String label, int done, int total) { }
                });
    }

    public static void main(String[] args) throws Exception {
        // 这台机器上真有一个 127.0.0.1:7897 的代理。要测的是"直连拨不上"，不是"代理替我拨上了"。
        System.clearProperty("http.proxyHost");
        System.clearProperty("http.proxyPort");
        File docx = new File(args.length > 0 ? args[0] : "tests/samples/input-liu.docx");
        DocxDocument document = DocxParser.parse(new FileInputStream(docx), docx.getName());
        selection = TextSelection.all(document);
        DuplicateEngine.WindowPlan plan = DuplicateEngine.windowPlan(selection.text, Integer.MAX_VALUE);
        String window = plan.groups.isEmpty() ? "" : plan.groups.get(0);
        int[] picked = null, best = null;
        for (int[] s : TextCorpus.sentences(window)) {
            String sentence = window.substring(s[0], s[1]);
            int n = TextCorpus.validCount(TextCorpus.normalize(sentence), 0, sentence.length());
            if (n < 26 || n > 90) continue;
            if (best == null || n > best[0]) { best = new int[] { n }; picked = s; }
        }
        if (picked == null) throw new IllegalStateException("样例文档第 1 扇窗口里没有 26-90 字的句子");
        plant = window.substring(picked[0], picked[1]).trim();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String[] paths = { "/cnki", "/cqvip", "/wanfang", "/ncpssd", "/openalex", "/crossref",
                "/semantic-scholar", "/europepmc/search", "/arxiv" };
        for (String path : paths) server.createContext(path, exchange -> respond(exchange, path));
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(16, runnable -> {
            Thread thread = new Thread(runnable, "blockage-stub");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        String live = "http://127.0.0.1:" + server.getAddress().getPort();
        // 开一个再关掉：这个端口上没人听，请求当场 ECONNREFUSED，与真机没有出口时同一个形状。
        HttpServer gone = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gone.start();
        int closedPort = gone.getAddress().getPort();
        gone.stop(0);
        DuplicateEngine.searchMillis = 90000L;
        DuplicateEngine.engineGapMillis = 0L;

        String closingNoRoute, closingNoComparable, closingMeasured;
        try {
            mode = "dead"; wire("http://127.0.0.1:" + closedPort);
            System.out.println("== 档一：手机没有出口（每个源都停在连接层）==");
            DuplicateEngine.Report noRoute = scan();
            closingNoRoute = closing(noRoute);
            System.out.println("  收尾[" + closingNoRoute + "]");
            line(noRoute);
            judgeNoRoute(noRoute, closingNoRoute);

            mode = "record"; wire(live);
            System.out.println("== 档二：源答了话，可比正文 0 篇 ==");
            DuplicateEngine.Report noComparable = scan();
            closingNoComparable = closing(noComparable);
            System.out.println("  收尾[" + closingNoComparable + "]");
            line(noComparable);
            judgeNoComparable(noComparable, closingNoComparable);

            mode = "body"; wire(live);
            System.out.println("== 档三：可比正文 N 篇（N>0）==");
            DuplicateEngine.Report measured = scan();
            closingMeasured = closing(measured);
            System.out.println("  收尾[" + closingMeasured + "]");
            line(measured);
            judgeMeasured(measured, closingMeasured);
        } finally {
            PaperSources.resetEndpoints();
            DuplicateEngine.searchMillis = DuplicateEngine.MAX_SEARCH_MILLIS;
            DuplicateEngine.engineGapMillis = DuplicateEngine.MIN_ENGINE_GAP_MILLIS;
            server.stop(0);
        }

        System.out.println("== 三档收尾互不相同 ==");
        check(!closingNoRoute.equals(closingNoComparable) && !closingNoRoute.equals(closingMeasured)
                        && !closingNoComparable.equals(closingMeasured),
                "三档收尾是三句不同的话，不是同一句换个数字");
        check(closingNoRoute.contains(FIX_GATEWAY) && !closingNoComparable.contains(FIX_GATEWAY)
                        && !closingMeasured.contains(FIX_GATEWAY),
                "插线跑 phone-gateway 这一步只在出口那一档出现——另两档用它没用");
        System.out.println("断言失败 " + failures + " 项");
        if (failures > 0) throw new AssertionError(failures + " 项断言失败");
        System.out.println("SUMMARY 检索被挡三档各说一句话，逐字核过（基准 e9f5322）");
    }

    private static void line(DuplicateEngine.Report report) {
        System.out.println("  问过 " + report.hostsAsked + " 个源，答话 " + report.hostsReached
                + " 个，连接没建成 " + report.hostsUnreachable + " 个；可比正文 "
                + report.comparableCandidates + " 篇，只有题录 " + report.recordOnlyCandidates
                + " 篇，零共同词 " + report.unrankedCandidates + " 篇");
    }

    private static void judgeNoRoute(DuplicateEngine.Report report, String closing) {
        int breakAt = closing.indexOf('\n');
        check(report.hostsAsked >= DuplicateEngine.MIN_HOSTS_FOR_NO_ROUTE && report.hostsReached == 0
                        && report.hostsUnreachable == report.hostsAsked,
                "这一轮问过的源全停在连接层：" + report.hostsAsked + " 个一个都没通");
        check(DuplicateEngine.noNetworkExit(report), "判据认这是手机没有出口那一档");
        check(closing.startsWith(CAUSE_NO_ROUTE), "第一行的主语是没连上，不是这份稿子：" + CAUSE_NO_ROUTE);
        check(closing.contains(report.hostsAsked + " 个检索源里通了 " + report.hostsReached + " 个"),
                "原因那一行把数字带上：" + report.hostsAsked + " 个里通了 " + report.hostsReached + " 个");
        check(breakAt > 0 && closing.substring(breakAt).contains(FIX_WLAN)
                        && closing.substring(breakAt).contains(FIX_GATEWAY),
                "两条出路排在换行之后，不跟原因挤在一行");
        check(!closing.equals(HEAD_CLOSING_NO_ROUTE) && !closing.contains("没有任何可比正文")
                        && !closing.contains("联网检索没有取回可比对的候选文献"),
                "老那句在这一档丢掉：同屏两句都在解释同一片空白，用户两句都不读");
        check(!notesMention(report, "个检索源里通了"),
                "数字不重复第二遍：注记里不再补一行通了几个源");
        String html = CheckReport.html("thesis.docx", report);
        check(!html.contains("<td>总相似度比</td>"), "报告里没有总相似度比那一格");
        check(!html.contains("检索源连通"), "报告那张表也不重复那个数");
        check(html.contains(CAUSE_NO_ROUTE) && html.contains(FIX_GATEWAY),
                "HTML 报告里原因与两条出路都在（换行拆成了两段）");
    }

    private static void judgeNoComparable(DuplicateEngine.Report report, String closing) {
        check(!DuplicateEngine.noNetworkExit(report), "源答过话，就不许说手机没有出口");
        check(report.hostsReached > 0 && report.comparableCandidates == 0,
                "答过话而可比正文 0 篇：" + report.hostsReached + " 个源答话，入库可比 0 篇");
        check(HEAD_CLOSING_NO_COMPARABLE.equals(closing), "这一档的收尾与 e9f5322 一字不差");
        check(notesMention(report, NOTE_ABSTRACT_ONLY), "「" + NOTE_ABSTRACT_ONLY + "」这句照旧留着");
        check(notesMention(report, report.hostsAsked + " 个检索源里通了 " + report.hostsReached + " 个"),
                "注记里补上通了几个源——与档一那句的区别正在这一个数");
        check(CheckReport.html("thesis.docx", report).contains("检索源连通"),
                "报告那张表里有一行检索源连通");
    }

    private static void judgeMeasured(DuplicateEngine.Report report, String closing) {
        check(!report.retrievalIncomplete, "这一轮不是未完成查重");
        check(report.comparableCandidates > 0 && report.overallRate > 0d,
                "可比正文 " + report.comparableCandidates + " 篇，总相似度比照印");
        check(notesMention(report, NOTE_ABSTRACT_ONLY), "摘要级这条短板仍在这屏上");
        check(!notesMention(report, "个检索源里通了"), "跑通的那一轮不许多出「通了几个源」这一行注记");
        String html = CheckReport.html("thesis.docx", report);
        check(!html.contains("检索源连通"), "报告那张表在跑通的轮次里不多这一行");
        check(HEAD_REPORT_HASH_WITH_COMPARABLE.equals(canonicalHash(html)),
                "整份报告与 e9f5322 逐字节相同（口径见 canonical）：" + canonicalHash(html));
        // 另一个方向的反证：把连通那几个数抹成零，同一轮的报告不许有半个字的变化。
        report.hostsAsked = 0; report.hostsReached = 0; report.hostsUnreachable = 0;
        check(canonicalHash(CheckReport.html("thesis.docx", report))
                .equals(HEAD_REPORT_HASH_WITH_COMPARABLE), "连通账目满不满，跑通那一轮的报告都不许变");
    }

    /**
     * 能拿来逐字节比对的报告口径。「检索响应留档」那张表的行序是四条泳道谁先回来的顺序，
     * 每次跑都不一样，所以把那一段的行排一遍；检测时间与耗时每次必变，掩掉。
     * 其余每一个字都参与比较。
     */
    private static String canonical(String html) {
        String masked = html.replaceAll("检测时间：[^<&]*", "检测时间：X").replaceAll("耗时 \\d+ ms", "耗时 N ms");
        int from = masked.indexOf("<h2>检索响应留档");
        if (from < 0) return masked;
        int to = masked.indexOf("</tbody>", from);
        if (to < 0) return masked;
        String body = masked.substring(from, to);
        ArrayList<String> rows = new ArrayList<String>();
        int cursor = 0;
        while (true) {
            int open = body.indexOf("<tr><td>", cursor);
            if (open < 0) break;
            int close = body.indexOf("</tr>", open) + 5;
            rows.add(body.substring(open, close));
            cursor = close;
        }
        Collections.sort(rows);
        StringBuilder rebuilt = new StringBuilder(body.substring(0, body.indexOf("<tr><td>")));
        for (String row : rows) rebuilt.append(row);
        return masked.substring(0, from) + rebuilt + masked.substring(to);
    }

    private static String canonicalHash(String html) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical(html).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    // ---- 回环桩：只监听 127.0.0.1，万方那一路给真的 gRPC-web 帧 ----

    private static void wire(String base) {
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

    private static void respond(HttpExchange exchange, String path) throws java.io.IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] buffer = new byte[4096];
            while (in.read(buffer) >= 0) { /* 请求体读掉就行，桩不看内容 */ }
        } catch (Exception ignored) { }
        byte[] payload;
        if (path.endsWith("/wanfang")) {
            payload = wanfangFrame();
        } else if (path.endsWith("/arxiv")) {
            payload = "<feed xmlns=\"http://www.w3.org/2005/Atom\"></feed>".getBytes(StandardCharsets.UTF_8);
        } else {
            payload = "{}".getBytes(StandardCharsets.UTF_8);
        }
        try {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, payload.length);
            OutputStream out = exchange.getResponseBody();
            out.write(payload);
            out.close();
        } catch (Exception ignored) { }
        exchange.close();
    }

    /**
     * 万方 12 条题录，第 1 条的题名是本文原话——题录与检索词对得上，所以它进得了语料。
     * record 档不给它摘要（老题录就是这个形状，知网维普的题录页也只给题名与出处），body 档才给。
     */
    private static byte[] wanfangFrame() {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        ProtoWire.writeInt32(response, 1, 1);
        ProtoWire.writeInt32(response, 3, 12);
        for (int i = 1; i <= 12; i++) {
            ByteArrayOutputStream journal = new ByteArrayOutputStream();
            ProtoWire.writeString(journal, 1, "WF1_" + i);
            ProtoWire.writeString(journal, 2, i == 1 ? plant : "行业综述 unrelated digest " + i);
            ProtoWire.writeString(journal, 3, "张三");
            if ("body".equals(mode) && i == 1) ProtoWire.writeString(journal, 20, plant);
            ProtoWire.writeString(journal, 24, "焊接学报");
            ProtoWire.writeInt32(journal, 33, 2024);
            ByteArrayOutputStream resource = new ByteArrayOutputStream();
            ProtoWire.writeString(resource, 1, "Periodical");
            ProtoWire.writeMessage(resource, 101, journal.toByteArray());
            ProtoWire.writeMessage(response, 4, resource.toByteArray());
        }
        byte[] payload = response.toByteArray();
        byte[] framed = new byte[payload.length + 5];
        framed[1] = (byte) (payload.length >>> 24);
        framed[2] = (byte) (payload.length >>> 16);
        framed[3] = (byte) (payload.length >>> 8);
        framed[4] = (byte) payload.length;
        System.arraycopy(payload, 0, framed, 5, payload.length);
        return framed;
    }
}
