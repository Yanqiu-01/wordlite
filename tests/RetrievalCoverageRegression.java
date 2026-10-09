package com.rikkahub.wordlite;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 0.6.0「检索预算成真 + 覆盖率诚实披露 + 按名次入库」回归。
 *
 * 这个套件锁的是四件在 0.5.x 里说谎的事：
 * 一、设置里的窗口数以前没人读，每个源其实只被问一次；
 * 二、请求数与挂钟闸门撞不撞得到没人知道，撞到了也不说；
 * 三、报告里的比率看不出"只比对了论文的一小段"；
 * 四、谁先回来谁进语料，第一个窗口的套话能占满入库名额。
 * 九个回环桩覆盖默认名单里的全部检索源，一个真网络请求都不许发出去。
 */
public final class RetrievalCoverageRegression {
    /** 默认检索名单（EngineSettings.DEFAULT_ENGINES）：CORE 要密钥，不在默认里。 */
    private static final String[] NINE = {"cnki", "cqvip", "wanfang", "ncpssd", "openalex",
            "crossref", "semantic-scholar", "europepmc", "arxiv"};
    private static int checks;
    private static final LinkedHashMap<String, AtomicInteger> HITS = new LinkedHashMap<String, AtomicInteger>();
    private static String base = "";
    private static HttpServer server;
    /** 桩侧的执行线程池：并发回话用的，必须是守护线程并在收尾时关掉，否则它会钉着 JVM 不走。 */
    private static java.util.concurrent.ExecutorService stubPool;
    /** 桩一次请求回几条候选：真源按 per-page 供货，场景自己定这个数。 */
    private static int responseSize = 12;
    /** semantic-scholar 是否改用「万能句」夹具：题名摘要与检索词零共同词。 */
    private static boolean boilerplate;
    /** crossref 的返回条数单独可调：D 组只回一条对题论文。 */
    private static int crossrefSize = -1;
    /** 打开后每个桩都回同样的两条候选：用来演「连续两个窗口零新增」的取尽判据。 */
    private static boolean sticky;
    /** E 组夹具：真出处只在第 MID_WINDOW_SEQ 扇窗口回得来，其余窗口一律回套话。 */
    private static boolean midWindow;
    /** 全文桩回空正文：验"抓回来没有正文层"那一档仍然按摘要比对，不许谎称比过正文。 */
    private static boolean fullTextEmpty;
    /** 慢桩每请求的耗时，时间闸门那组用它把挂钟闸门压出来。 */
    private static long slowMillis;
    /** 命中这些路径直接回 429，用来验限流不被误算成整轮不可用。 */
    private static final ArrayList<String> throttled = new ArrayList<String>();
    /** 逐路径的桩耗时：慢源只许拖死自己这一路，不许把其余源一起拖死，所以慢要能慢在一条路上。 */
    private static final LinkedHashMap<String, Long> SLOW_MILLIS = new LinkedHashMap<String, Long>();
    /** 同时在飞的请求数与峰值：跨源并行是这一版的全部理由，必须直接量到，不许拿"整轮快了一点"当证据。 */
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private static final AtomicInteger PEAK_IN_FLIGHT = new AtomicInteger();
    /** 逐路径上一次被问的时刻，以及整轮里同源相邻两次提问最短的间隔：并行最容易破的就是这条限速。 */
    private static final LinkedHashMap<String, Long> LAST_ASK = new LinkedHashMap<String, Long>();
    private static long shortestGapMillis = Long.MAX_VALUE;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
        System.out.println("PASS " + message);
    }

    /** 记一次请求，返回它是该路径的第几次被问：桩靠这个数让每个窗口的候选都是新的。 */
    private static int record(String path) {
        return entered(path);
    }
    /**
     * 记账在锁里，慢桩的睡眠在锁外：睡在锁里等于桩自己把并行改成串行，那样峰值并发永远量不到 2，
     * 下面那一组"跨源并行"的断言就成了永远为真的空话。
     */
    private static synchronized int entered(String path) {
        long now = System.currentTimeMillis();
        Long previous = LAST_ASK.get(path);
        if (previous != null) shortestGapMillis = Math.min(shortestGapMillis, now - previous.longValue());
        LAST_ASK.put(path, Long.valueOf(now));
        AtomicInteger counter = HITS.get(path);
        if (counter == null) { counter = new AtomicInteger(); HITS.put(path, counter); }
        int seq = counter.incrementAndGet();
        int inflight = IN_FLIGHT.incrementAndGet();
        if (inflight > PEAK_IN_FLIGHT.get()) PEAK_IN_FLIGHT.set(inflight);
        return seq;
    }
    private static synchronized void left() { IN_FLIGHT.decrementAndGet(); }
    /** 这条路径该睡多久：整轮一致的 slowMillis 与逐路径的 SLOW_MILLIS 取更慢的那一档。 */
    private static synchronized long delayMillis(String path) {
        Long per = SLOW_MILLIS.get(path);
        return per == null ? slowMillis : Math.max(slowMillis, per.longValue());
    }
    private static synchronized int peakInFlight() { return PEAK_IN_FLIGHT.get(); }
    private static synchronized long shortestGap() { return shortestGapMillis; }
    private static synchronized int hits(String path) {
        AtomicInteger counter = HITS.get(path);
        return counter == null ? 0 : counter.intValue();
    }
    /** 记一次请求；sticky 模式下候选内容不变，但计数照加。 */
    private static int ask(String path) {
        int seq = record(path);
        return sticky ? 1 : seq;
    }
    private static int totalHits() {
        int total = 0;
        for (String key : HITS.keySet()) total += hits(key);
        return total;
    }
    private static void resetCounters() {
        synchronized (RetrievalCoverageRegression.class) {
            HITS.clear();
            LAST_ASK.clear();
            IN_FLIGHT.set(0);
            PEAK_IN_FLIGHT.set(0);
            shortestGapMillis = Long.MAX_VALUE;
        }
    }
    private static void resetFixtures() {
        responseSize = 12;
        boilerplate = false;
        crossrefSize = -1;
        sticky = false;
        midWindow = false;
        fullTextEmpty = false;
        slowMillis = 0L;
        SLOW_MILLIS.clear();
        throttled.clear();
        resetCounters();
    }
    private static String label(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }
    private static String queryOf(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawPath();
        String query = exchange.getRequestURI().getRawQuery();
        return query == null ? raw : raw + "?" + query;
    }
    private static String body(HttpExchange exchange) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream input = exchange.getRequestBody();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) > 0) out.write(buffer, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            return "";
        }
    }
    private static void respond(HttpExchange exchange, int status, String text) {
        respondBytes(exchange, status, text.getBytes(StandardCharsets.UTF_8));
    }
    private static void respondBytes(HttpExchange exchange, int status, byte[] bytes) {
        long wait = delayMillis(exchange.getRequestURI().getPath());
        try {
            if (wait > 0L) Thread.sleep(wait);
            if (!throttled.isEmpty() && throttled.contains(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(429, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            OutputStream out = exchange.getResponseBody();
            out.write(bytes);
            out.close();
        } catch (Exception ignored) { }
        left();
        exchange.close();
    }
    // ---- 回环桩 ----

    private static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cnki", exchange -> {
            int seq = ask("/cnki");
            queryOf(exchange);
            body(exchange);
            respond(exchange, 200, cnkiBody(seq, responseSize));
        });
        server.createContext("/cqvip", exchange -> {
            int seq = ask("/cqvip");
            queryOf(exchange);
            respond(exchange, 200, cqvipBody(seq, responseSize));
        });
        server.createContext("/wanfang", exchange -> {
            int seq = ask("/wanfang");
            body(exchange);
            respondBytes(exchange, 200, wanfangBody(seq, responseSize));
        });
        server.createContext("/ncpssd", exchange -> {
            int seq = ask("/ncpssd");
            body(exchange);
            respond(exchange, 200, ncpssdBody(seq, responseSize));
        });
        server.createContext("/openalex", exchange -> {
            int seq = ask("/openalex");
            queryOf(exchange);
            respond(exchange, 200, openAlexBody(seq, responseSize));
        });
        server.createContext("/crossref", exchange -> {
            int seq = ask("/crossref");
            queryOf(exchange);
            respond(exchange, 200, crossrefBody(seq, crossrefSize > 0 ? crossrefSize : responseSize));
        });
        server.createContext("/semantic-scholar", exchange -> {
            int seq = ask("/semantic-scholar");
            queryOf(exchange);
            respond(exchange, 200, semanticBody(seq, responseSize));
        });
        server.createContext("/europepmc/search", exchange -> {
            int seq = ask("/europepmc/search");
            queryOf(exchange);
            respond(exchange, 200, europePmcBody(seq, responseSize).replace("__BASE__",
                    "http://" + exchange.getRequestHeaders().getFirst("Host")));
        });
        /* europepmc 的全文链接由源自己在 fullTextUrlList 里给（见 europePmcBody 的 __BASE__ 那条），
           落在 /europepmc/ 下；2.5.0 之前这一路是拿 pmcid 拼出来的，那条口实测 404。 */
        server.createContext("/europepmc/", exchange -> {
            record("/europepmc/fulltext");
            if (fullTextEmpty) { respond(exchange, 200, ""); return; }
            respond(exchange, 200, FULL_TEXT_XML);
        });
        server.createContext("/arxiv", exchange -> {
            int seq = ask("/arxiv");
            queryOf(exchange);
            respond(exchange, 200, arxivBody(seq, responseSize));
        });
        /* crossref 那条对题论文的开放获取位置：只有名次第一才配得上这次抓取。 */
        server.createContext("/fulltext", exchange -> {
            record("/fulltext");
            respond(exchange, 200, fullTextEmpty ? "" : FULL_TEXT_XML);
        });
        /* 恒 429 的源：验"补试一次"与"N 个源不可用"按源计而不是按次数计。 */
        server.createContext("/throttled", exchange -> {
            record("/throttled");
            respond(exchange, 200, "{}");
        });
        /* 桩自己必须能并发回话：桩按默认单线程回话时，客户端再怎么并行，桩侧看到的峰值永远是 1，
           那一组断言就成了测桩而不是测引擎。线程取守护线程，配合下面 finally 里的 shutdownNow，
           免得这套回归把 JVM 钉在退出之前。 */
        stubPool = java.util.concurrent.Executors.newFixedThreadPool(16, runnable -> {
            Thread thread = new Thread(runnable, "stub-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(stubPool);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        PaperSources.setEndpoint("cnki", base + "/cnki");
        PaperSources.setEndpoint("cqvip", base + "/cqvip");
        PaperSources.setEndpoint("wanfang", base + "/wanfang");
        PaperSources.setEndpoint("ncpssd", base + "/ncpssd");
        PaperSources.setEndpoint("openalex", base + "/openalex");
        PaperSources.setEndpoint("crossref", base + "/crossref");
        PaperSources.setEndpoint("semantic-scholar", base + "/semantic-scholar");
        PaperSources.setEndpoint("europepmc", base + "/europepmc/search");
        PaperSources.setEndpoint("arxiv", base + "/arxiv");
        PaperSources.setEndpoint("core", base + "/core");
    }

    /* ---- 给量台借用的几个出口（tools/coverage-budget-probe.ps1 -> CoverageBudgetProbe）----
       夹具只有一份：这台起桩、那台量额度，两处各写一套九个源的假响应，早晚会有一套说谎。 */

    /** 起九个回环桩并把 endpoints 指过去。用完必须 stopStub()，否则 endpoints 还指着 127.0.0.1。 */
    static void startStub() throws Exception {
        start();
    }

    /** 收桩并把 endpoints 还回真源：漏掉这一步，下一个真联网探针会对着回环桩量出个假数。 */
    static void stopStub() {
        if (server != null) server.stop(0);
        if (stubPool != null) stubPool.shutdownNow();
        PaperSources.resetEndpoints();
        server = null;
        stubPool = null;
    }

    /** 清命中计数与夹具开关：量台连着量两轮，第二轮不能接着第一轮的账。 */
    static void resetStub() {
        resetFixtures();
    }

    /** 桩认识的路径（逐源延迟表按它铺）：都是 /<源名> 那一个形状。 */
    static ArrayList<String> stubPaths() {
        ArrayList<String> out = new ArrayList<String>();
        for (String engine : NINE) out.add("/" + engine);
        return out;
    }

    /** 给某一家桩定一次响应耗时：墙钟那一笔要在同一条代码路径上量，只能把延迟钉在桩这一头。 */
    static void setStubDelay(String path, long millis) {
        synchronized (RetrievalCoverageRegression.class) {
            SLOW_MILLIS.put(path, Long.valueOf(millis));
        }
    }

    private static final String FULL_TEXT_XML = "<article><body><sec><title>Intro</title>"
            + "<p>钎焊界面扩散层厚度实测为二十七微米，保温六十分钟后不再长厚。</p></sec></body></article>";
    private static final String FULL_TEXT_PROBE = "钎焊界面扩散层厚度实测为二十七微米，保温六十分钟后不再长厚。";
    private static final String BOILERPLATE_PROBE = "综上所述，随着相关领域的持续发展，本文系统梳理了总体进展。";
    /** 与整篇正文同源的一句：零分闸门放开时它才能进语料。 */
    private static final String TOPIC = "多孔铜中间层钎焊界面组织演变";

    /**
     * 候选署名里带上检索源自己的名字：跨源标题指纹合并只在两侧 DOI 都缺时兜底（MIN_TITLE_KEY），
     * 九个桩若用同一个标题会先被并掉一批，A 组「每个源各贡献若干条」就没得看了。
     * 只有回环线程会走到这里，一次只处理一个请求，所以这个字段不需要锁。
     */
    private static String sourceTag = "";
    private static String tail(int seq, int i) {
        return sourceTag + "样本" + seq + "组第" + i + "篇";
    }
    private static String topicTitle(int seq, int i) {
        return TOPIC + "研究" + tail(seq, i);
    }
    private static String topicAbstract(int seq, int i) {
        return TOPIC + "在保温过程中生成硅化物，接头强度上升，见" + tail(seq, i) + "。";
    }
    /** D 组的陪跑候选：只共享「多孔铜」这一个查询三元组，名次必须低于对题那条。 */
    private static String weakTitle(int seq, int i) {
        return "多孔铜数据集" + tail(seq, i);
    }
    private static String weakAbstract(int seq, int i) {
        return "该记录只有著录信息，另附编号清单与馆藏地址，未提供可读正文。";
    }
    private static String boilerTitle(int seq, int i) {
        return "行业综述" + tail(seq, i);
    }
    private static String boilerAbstract(int seq, int i) {
        return "综上所述，随着相关领域的持续发展，本文系统梳理了总体进展，另附编号清单。";
    }
    /** 知网：条目页只给题名、出处与 128 字摘要预览。 */
    private static String cnkiBody(int seq, int count) {
        sourceTag = "知网";
        StringBuilder out = new StringBuilder("<html><body>");
        for (int i = 1; i <= count; i++) {
            String code = "CND" + seq + "X" + i + ".CAJ";
            String title = topicTitle(seq, i);
            /* ITEM 匹配的是 <div class="list-item"> 这一个整串，data-fn 只能挂在块内元素上。 */
            out.append("<div class=\"list-item\"><p class=\"source\" data-fn=\"").append(code).append("\">")
                    .append("<p class=\"tit\"><a href=\"https://chn.oversea.cnki.net/KCMS/detail/detail.aspx?dbcode=CJFD&filename=")
                    .append(code).append("\" title=\"").append(title).append("\">").append(title).append("</a></p>")
                    .append("<span title=\"焊接学报\">焊接学报</span><span>2024-03-01</span></p>")
                    .append("<p class=\"nr\">").append(topicAbstract(seq, i)).append("</p></div>");
        }
        return out.append("</body></html>").toString();
    }

    /** 维普：服务端只渲染摘要，题名由脚本后填，所以可比材料只剩摘要。 */
    private static String cqvipBody(int seq, int count) {
        sourceTag = "维普";
        StringBuilder out = new StringBuilder("<html><body><div class=\"content-arrange\">");
        for (int i = 1; i <= count; i++) {
            out.append("<div class=\"item\"><a href=\"/doc/journal/CQV").append(seq).append("_").append(i)
                    .append("\" class=\"title\"></a><a href=\"/journal/J001\">焊接学报</a>")
                    .append("<a class=\"author-name\">张三</a> 2024年 第").append(i).append("期 ")
                    .append("<span class=\"abstr\">").append(topicAbstract(seq, i)).append("</span></div>");
        }
        return out.append("</div></body></html>").toString();
    }

    /** 万方：gRPC-web 的帧里裹 protobuf，期刊载荷是 oneof 的 101。 */
    private static byte[] wanfangBody(int seq, int count) {
        sourceTag = "万方";
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        ProtoWire.writeInt32(response, 1, 1);
        ProtoWire.writeInt32(response, 3, count);
        for (int i = 1; i <= count; i++) {
            ByteArrayOutputStream journal = new ByteArrayOutputStream();
            ProtoWire.writeString(journal, 1, "WF" + seq + "_" + i);
            ProtoWire.writeString(journal, 2, topicTitle(seq, i));
            ProtoWire.writeString(journal, 3, "张三");
            ProtoWire.writeString(journal, 20, topicAbstract(seq, i));
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

    private static String ncpssdBody(int seq, int count) {
        sourceTag = "哲社";
        StringBuilder out = new StringBuilder("{\"data\":{\"count\":").append(count).append(",\"rows\":[");
        for (int i = 1; i <= count; i++) {
            if (i > 1) out.append(',');
            out.append("{\"id\":\"NP").append(seq).append('_').append(i)
                    .append("\",\"HtmlUrl\":\"https://ncpssd.example.org/read/").append(seq).append('-').append(i)
                    .append("\",\"title\":\"").append(topicTitle(seq, i))
                    .append("\",\"creator\":\"张三\",\"years\":\"2023\",\"remark\":\"").append(topicAbstract(seq, i))
                    .append("\"}");
        }
        return out.append("]}}").toString();
    }

    private static String openAlexBody(int seq, int count) {
        sourceTag = "开放源";
        if (midWindow) {
            /* E 组：第 MID_WINDOW_SEQ 扇窗口回那条真出处，其余窗口全是与正文零共同词的套话。 */
            StringBuilder found = new StringBuilder("{\"meta\":{\"count\":1},\"results\":[");
            if (seq == MID_WINDOW_SEQ) {
                openAlexItem(found, seq, 1, "10.9999/mid-0001", MID_TITLE, MID_ABSTRACT);
                return found.append("]}").toString();
            }
            for (int i = 1; i <= count; i++)
                openAlexItem(found, seq, i, "10.1000/oa-" + seq + "-" + i, boilerTitle(seq, i), boilerAbstract(seq, i));
            return found.append("]}").toString();
        }
        StringBuilder out = new StringBuilder("{\"meta\":{\"count\":").append(count).append("},\"results\":[");
        for (int i = 1; i <= count; i++) {
            /* 第 3 条与 crossref 对题那条共用同一个 DOI：跨源合并的账要能对上一遍。 */
            String doi = (crossrefSize > 0 && i == 3) ? DOI_ON_TOPIC : "10.1000/oa-" + seq + "-" + i;
            String title = crossrefSize > 0 ? weakTitle(seq, i) : topicTitle(seq, i);
            String summary = crossrefSize > 0 ? weakAbstract(seq, i) : topicAbstract(seq, i);
            openAlexItem(out, seq, i, doi, title, summary);
        }
        return out.append("]}").toString();
    }

    /** 一条 OpenAlex 条目：逗号由容器判断，夹具只管填内容。 */
    private static void openAlexItem(StringBuilder out, int seq, int i, String doi, String title, String summary) {
        if (out.charAt(out.length() - 1) != '[') out.append(',');
        out.append("{\"id\":\"https://openalex.org/W").append(seq).append('_').append(i)
                .append("\",\"doi\":\"https://doi.org/").append(doi)
                .append("\",\"title\":\"").append(title)
                .append("\",\"authorships\":[{\"author\":{\"display_name\":\"L. Zhang\"}}],\"publication_year\":2021,")
                .append("\"open_access\":{\"is_oa\":false},\"abstract_inverted_index\":{\"").append(summary)
                .append("\":[0]}}");
    }

    /** crossref 对题那条的 DOI：openalex 的第 3 条与它同源。 */
    private static final String DOI_ON_TOPIC = "10.5555/tlp-0001";

    private static String crossrefBody(int seq, int count) {
        sourceTag = "crossref";
        StringBuilder out = new StringBuilder("{\"status\":\"ok\",\"message\":{\"total-results\":")
                .append(count).append(",\"items\":[");
        for (int i = 1; i <= count; i++) {
            if (i > 1) out.append(',');
            String title = crossrefSize > 0 ? TOPIC + "实测研究" : topicTitle(seq, i);
            String summary = crossrefSize > 0 ? ON_TOPIC_ABSTRACT : topicAbstract(seq, i);
            out.append("{\"DOI\":\"").append(crossrefSize > 0 ? DOI_ON_TOPIC : "10.5555/cr-" + seq + "-" + i)
                    .append("\",\"title\":[\"").append(title).append("\"],")
                    .append("\"author\":[{\"given\":\"Li\",\"family\":\"Zhang\"}],\"issued\":{\"date-parts\":[[2022]]},")
                    .append("\"abstract\":\"").append(summary).append("\"");
            if (crossrefSize > 0)
                out.append(",\"open-access\":{\"is_oa\":true},\"resource\":{\"primary\":{\"URL\":\"")
                        .append(base).append("/fulltext/tlp\"}}");
            out.append("}");
        }
        return out.append("]}}").toString();
    }

    private static final String ON_TOPIC_ABSTRACT =
            "多孔铜中间层钎焊界面组织演变词元0000接头接头接头接头接头接头接头接头接头接头接。";

    private static String semanticBody(int seq, int count) {
        sourceTag = "语义源";
        StringBuilder out = new StringBuilder("{\"total\":").append(count).append(",\"offset\":0,\"data\":[");
        for (int i = 1; i <= count; i++) {
            if (i > 1) out.append(',');
            String title = boilerplate ? boilerTitle(seq, i) : topicTitle(seq, i);
            String summary = boilerplate ? boilerAbstract(seq, i) : topicAbstract(seq, i);
            out.append("{\"paperId\":\"SS").append(seq).append('_').append(i)
                    .append("\",\"title\":\"").append(title).append("\",\"year\":2022,")
                    .append("\"abstract\":\"").append(summary)
                    .append("\",\"authors\":[{\"name\":\"R. Ito\"}],\"externalIds\":{\"DOI\":\"10.2222/ss-").append(seq)
                    .append('-').append(i).append("\"},\"openAccessPdf\":{}}");
        }
        return out.append("]}").toString();
    }

    private static String europePmcBody(int seq, int count) {
        sourceTag = "欧洲库";
        StringBuilder out = new StringBuilder("{\"hitCount\":").append(count).append(",\"resultList\":{\"result\":[");
        for (int i = 1; i <= count; i++) {
            if (i > 1) out.append(',');
            String title = crossrefSize > 0 ? weakTitle(seq, i) : topicTitle(seq, i);
            String summary = crossrefSize > 0 ? weakAbstract(seq, i) : topicAbstract(seq, i);
            out.append("{\"id\":\"EP").append(seq).append('_').append(i)
                    .append("\",\"pmid\":\"EP").append(seq).append('_').append(i).append("\"")
                    .append(",\"pmcid\":\"PMC").append(seq).append('_').append(i).append("\"")
                    .append(",\"source\":\"MED\",\"doi\":\"10.3333/ep-").append(seq).append('-').append(i)
                    .append("\",\"title\":\"").append(title).append("\",\"pubYear\":\"2020\"")
                    .append(",\"authorList\":{\"author\":[{\"firstName\":\"A.\",\"lastName\":\"Ito\"}]}")
                    .append(",\"abstractText\":\"").append(summary).append("\"")
                    /* 源自己说这篇开放获取并给出一条 documentStyle=xml 的正文链接——这是 2026-10-09
                       对着真接口量到的形状，解析器只认这种自己给的证据。 */
                    .append(",\"isOpenAccess\":\"Y\",\"fullTextUrlList\":{\"fullTextUrl\":[{\"availability\":\"Free\","
                    + "\"documentStyle\":\"xml\",\"url\":\"__BASE__/europepmc/body\"}]}}");
        }
        return out.append("]}}").toString();
    }

    private static String arxivBody(int seq, int count) {
        sourceTag = "预印本";
        StringBuilder out = new StringBuilder("<feed xmlns=\"http://www.w3.org/2005/Atom\">");
        for (int i = 1; i <= count; i++) {
            out.append("<entry><id>http://arxiv.org/abs/2103.").append(seq).append('V').append(i)
                    .append("</id><title>").append(topicTitle(seq, i))
                    .append("</title><updated>2021-03-11T00:00:00Z</updated><summary>").append(topicAbstract(seq, i))
                    .append("</summary><link title=\"pdf\" href=\"https://arxiv.org/pdf/2103.").append(seq)
                    .append('V').append(i).append(".pdf\"/></entry>");
        }
        return out.append("</feed>").toString();
    }
    // ---- 夹具与工具 ----

    /** 每段正好 40 字，够 retrievable()，结尾不是裸页码；"词元"编号让每个窗口的短语互不相同。 */
    private static String paragraphText(int index) {
        StringBuilder out = new StringBuilder(TOPIC);
        out.append(String.format(Locale.ROOT, "词元%04d", index));
        while (out.length() < 39) out.append("接头");
        out.setLength(39);
        return out.append('。').toString();
    }

    // ---- E 组夹具：抄在论文中段的一整段原话，与只有那扇窗口问得回它的那篇来源 ----

    /** 原封不动抄进正文的三句真论文原话，正好铺满第三扇检索窗口（第六、七、八段）。 */
    private static final String[] MID_LINES = {
            "多孔铜中间层钎焊界面组织演变随保温时间延长而粗化，界面反应层生成薄层状硅化物，"
                    + "晶粒尺寸沿界面方向呈梯度分布。",
            "保温六十分钟后扩散层不再明显长厚，接头抗剪强度随保温时间先升后降，断口未见孔洞与微裂纹。",
            "据此把工艺窗口压到六十分钟以内，随炉冷却到三百度以下再开炉，界面组织保持稳定不再继续粗化。",
    };
    /** 那篇真出处：摘要就以被抄走的三句原话开头。 */
    private static final String MID_TITLE = "多孔铜中间层钎焊界面组织演变研究";
    private static final String MID_ABSTRACT =
            MID_LINES[0] + MID_LINES[1] + MID_LINES[2] + "随炉冷却速率取每分钟八度时接头变形量最小。";
    /** 只有第 3 扇窗口问得回这篇来源，其余五扇全回套话。 */
    private static final int MID_WINDOW_SEQ = 3;

    /**
     * 十八段轨道客流正文，第六到第八段是从别处抄来的一整段。
     *
     * 段长是刻意排的：前两扇每段八个短句，是全篇里最"具体"的材料；抄稿那一扇只有钎焊词。把整篇压成
     * 四十八个字的检索式，落点必然在前两扇的客流词上，那条真出处一个查询词都分不到——真机那篇
     * 19967 字开题报告报 0.00% 走的就是这个口子。段号必须写进首句，否则四十八个字装不到它，
     * 相邻窗口会撞出同一个检索式被去重；段尾也不能收在数字上，否则 retrievable() 当它是目录页码丢掉。
     */
    private static DocxDocument roadmap(int paragraphs) {
        DocxDocument document = new DocxDocument();
        for (int i = 0; i < paragraphs; i++) {
            DocxDocument.ParagraphBlock block = new DocxDocument.ParagraphBlock();
            block.index = i;
            block.text = i >= 6 && i <= 8 ? MID_LINES[i - 6] : roadmapLine(i);
            document.blocks.add(block);
            document.paragraphs.add(block);
        }
        return document;
    }

    /** 客流句：前五段铺八个短句（整篇最"具体"的材料），其余铺三个。 */
    private static String roadmapLine(int index) {
        StringBuilder out = new StringBuilder("市域快线");
        out.append(String.format(Locale.ROOT, "%04d", index));
        out.append("断面客流早高峰上行集中");
        out.append("，换乘客流在枢纽节点叠加");
        if (index <= 5) {
            out.append("，走廊运输能力持续紧张");
            out.append("，开行方案只能分时拆解");
            out.append("，站点服务能力需要同步核验");
            out.append("，换乘通道排队长度越演越烈");
            out.append("，运能运力匹配反复测算良久");
            out.append("，客流组织方案数易其稿");
        }
        out.append("，调查数据取自自动售检票记录。");
        return out.toString();
    }

    private static DocxDocument thesis(int paragraphs) {
        DocxDocument document = new DocxDocument();
        for (int i = 0; i < paragraphs; i++) {
            DocxDocument.ParagraphBlock block = new DocxDocument.ParagraphBlock();
            block.index = i;
            block.text = paragraphText(i);
            document.blocks.add(block);
            document.paragraphs.add(block);
        }
        return document;
    }
    private static ArrayList<String> engines(String... names) {
        ArrayList<String> out = new ArrayList<String>();
        for (String name : names) out.add(name);
        return out;
    }
    private static ArrayList<String> nine() {
        return engines(NINE);
    }
    private static PaperSources.Limits limits(int perEngine, int windows) {
        PaperSources.Limits limits = new PaperSources.Limits();
        limits.perEngine = perEngine;
        limits.windows = windows;
        limits.timeoutSeconds = 8;
        return limits;
    }
    private static DuplicateEngine.Report scan(DocxDocument document, TextCorpus corpus,
                                               ArrayList<String> engines, PaperSources.Limits limits) {
        return DuplicateEngine.scan(TextSelection.all(document), corpus, true, engines, limits,
                new ApiClient.Task(), null);
    }
    private static boolean notes(DuplicateEngine.Report report, String fragment) {
        for (String note : report.notes) if (note != null && note.contains(fragment)) return true;
        return false;
    }
    private static int asked(DuplicateEngine.Report report, String engine) {
        Integer value = report.windowsAsked.get(engine);
        return value == null ? 0 : value.intValue();
    }
    private static int requests(DuplicateEngine.Report report) {
        int total = 0;
        for (String engine : report.windowsAsked.keySet()) total += asked(report, engine);
        return total;
    }
    private static int gathered(DuplicateEngine.Report report) {
        int total = 0;
        for (String engine : report.candidateCount.keySet())
            total += report.candidateCount.get(engine).intValue();
        return total;
    }
    /** 语料里有没有这句话：比对器自己说了算，不另造一套包含判断。 */
    private static boolean corpusContains(TextCorpus corpus, String probe) {
        return !corpus.isEmpty() && !corpus.match(probe, null).hits.isEmpty();
    }
    private static boolean lists(PaperSources.Candidate candidate, String engine) {
        return engine.equals(candidate.source.engine);
    }

    // ---- A 组：设置里的窗口数 = 每家最多问几扇；问哪几扇由 allocate() 按窗口里的中文字数排 ----

    /**
     * 九个源全选、每源上限 12：九家各问 12 次共 108 次提问，67 扇窗口全排上。
     *
     * 改之前这一轮的走法是“前 12 扇每扇问遍九家”，第 13 扇起一个字没问；现在同样的 108 次请求
     * 摊到 67 扇，每扇 1-2 家。这里锁的就是这个形状：每源次数守恒、覆盖变宽、没有任何源整轮没被问。
     */
    private static DuplicateEngine.Report askedOncePerWindow() {
        resetFixtures();
        DuplicateEngine.Report report = scan(thesis(200), new TextCorpus(), nine(), limits(12, 12));
        String[] paths = {"/cnki", "/cqvip", "/wanfang", "/ncpssd", "/openalex", "/crossref",
                "/semantic-scholar", "/europepmc/search", "/arxiv"};
        for (String path : paths) {
            check(hits(path) == 12, label(path) + " 被问了 12 次：每源上限就是设置里的窗口数");
        }

        check(hits("/cnki") == hits("/cqvip") && hits("/cqvip") == hits("/arxiv")
                        && hits("/arxiv") == hits("/europepmc/search"),
                "所有选中的源被问的次数一致，没有谁在半轮里被悄悄丢掉");
        check(report.windowsAvailable == 67, "200 段正文按三段一组切成 67 个检索窗口");
        check(report.windowsPlanned == 67, "九家各 12 扇的额度买得起全部 67 扇，就不许只排前 12 扇");
        check(report.windowsRetrieved == 67, "排上的窗口全部发出了请求");
        check(report.windowsUnstaffed == 0, "额度够用时一扇也不许落下");
        for (String engine : NINE)
            check(asked(report, engine) == 12, engine + " 的提问次数记成 12，HTML 的「提问次数」列读的就是它");
        check(requests(report) == 9 * 12, "提问总次数守恒在九家乘每源上限 = 108 次");
        check(report.asksPlanned == 108 && report.asksSent == 108, "排期 108 次、真发 108 次");
        check(report.windowDepthLow == 1 && report.windowDepthHigh == 2, "每窗 1-2 家：先铺宽再铺深");
        check(report.chineseWindowsAsked == 36, "三家中文主库各 12 扇 = 36 扇问到中文");
        check(!report.retrievalIncomplete, "取回了候选的检索绝不许被标成什么都没查");
        check(!report.retrievalPartial, "67 扇全问到了就不许自认部分是");
        System.out.println("SHAPE nine/12 planned=" + report.windowsPlanned + "/" + report.windowsAvailable
                + " retrieved=" + report.windowsRetrieved + " unstaffed=" + report.windowsUnstaffed
                + " asks=" + report.asksSent + "/" + report.asksPlanned
                + " depth=" + report.windowDepthLow + "-" + report.windowDepthHigh
                + " chinese=" + report.chineseWindowsAsked + " covered=" + report.coveredChars
                + "/" + report.comparableChars + " partial=" + report.retrievalPartial);
        return report;
    }
    /** 六十段一模一样的正文：十二个窗口会切出一模一样的检索短语，去重必须挡住后十一次。 */
    private static void identicalWindowIsNotAskedTwice() {
        resetFixtures();
        DocxDocument document = new DocxDocument();
        for (int i = 0; i < 60; i++) {
            DocxDocument.ParagraphBlock block = new DocxDocument.ParagraphBlock();
            block.index = i;
            block.text = "宽禁带半导体封装烧结银接头的热机械可靠性试验方法与结果讨论。";
            document.blocks.add(block);
            document.paragraphs.add(block);
        }
        DuplicateEngine.Report report = scan(document, new TextCorpus(), nine(), limits(12, 12));
        check(report.windowsAvailable == 20 && report.windowsPlanned == 20,
                "六十段相同的正文切出 20 个窗口，额度买得起就全排上");
        check(requests(report) == 9, "同一条检索短语不会问第二次：二十个窗口只发了九个请求");
        check(hits("/cqvip") == 1 && hits("/cnki") == 1,
                "每个源只在第一次遇到这条短语时被问一次，剩下那些是白送的");
        /* 九次提问摊到哪几扇是排期自己的事（同一句正文切出的窗口本就同一条短语），
           这里只锁两件事：已检索窗口数不是一扇，也不是没问过的那些；覆盖字数严格等于它乘一扇。 */
        check(report.windowsRetrieved >= 2 && report.windowsRetrieved <= 9,
                "只有真问出去的窗口才计数：本轮九次提问落在 "
                        + report.windowsRetrieved + " 扇（不是 1 扇，也不许超过九次）");
        check(report.windowsRetrieved * 90 == report.coveredChars,
                "这一篇一扇九十字，覆盖字数严格等于问到的扇数乘九十：" + report.coveredChars);
        check(report.asksSpilled > 0 && report.asksRefilled == 0,
                "去重挡下的格子排回补问队列，但换谁问都是同一条短语，一次也不该真补出去："
                        + report.asksSpilled + "/" + report.asksRefilled);
        check(!notes(report, "已取尽"), "短语去重挡下来的窗口不该被算成该源已取尽");
        check(report.retrievalPartial, "被去重挡掉的窗口同样要承认这次只跑了一个窗口");
    }
    // ---- B 组：设置里的窗口数真的被读，闸门真的可达 ----

    private static void singleWindowSetting() {
        resetFixtures();
        DuplicateEngine.Report one = scan(thesis(200), new TextCorpus(), nine(), limits(12, 1));
        check(one.windowsPlanned == 9 && one.windowsRetrieved == 9,
                "设置里的 1 扇是每源上限：九家各问一扇，一共问九扇");
        check(hits("/cqvip") == 1, "1 扇上限下的维普真的只发了 1 次请求");
        check(one.coveredChars == 9 * 120, "九扇各覆盖三段四十字，覆盖字数记 1080 字");
        check(one.windowDepthLow == 1 && one.windowDepthHigh == 1, "每扇恰好被问一次，一次也不多花");
        check(one.chineseWindowsAsked == 3, "这九个名额先尽着三家中文主库：三扇各占一家");
    }

    private static void requestCeiling() {
        resetFixtures();
        check(DuplicateEngine.MAX_REQUESTS == 120, "整轮请求上限是 120 次");
        check(DuplicateEngine.MAX_CORPUS_PAPERS == 120, "入库总量上限是 120 篇");
        check(DuplicateEngine.MAX_SEARCH_MILLIS == 180000L, "整轮挂钟闸门是 180 秒");
        check(DuplicateEngine.MIN_ENGINE_GAP_MILLIS == 400L, "同一源两次提问之间至少隔 400 毫秒");
        check(new EngineSettings().windows == 6, "新装机的检索窗口数默认降到 6，不再把闸门当默认路径");
        check(new PaperSources.Limits().perEngine == 12, "perEngine 仍是每次要几条，默认 12");
        /* 每源上限放到 24：九家乘 24 = 216 次，远超 120 的额度。排期必须自己就停在 120 次以内，
           而不是先排满再去撞闸——先排满再撞闸就是 2.6.2 那轮的形状，只是那时候没人看得见。 */
        DuplicateEngine.Report capped = scan(thesis(200), new TextCorpus(), nine(), limits(12, 24));
        check(capped.asksPlanned == DuplicateEngine.MAX_REQUESTS,
                "排期自己就落在 120 次以内：" + capped.asksPlanned);
        check(requests(capped) == DuplicateEngine.MAX_REQUESTS,
                "真发出去的请求正好 120 次：" + requests(capped));
        check(capped.windowsPlanned == 67 && capped.windowsRetrieved == 67,
                "同一份额度花在铺宽上：120 次照样把 67 扇全排上、全问到");
        check(capped.windowsUnstaffed == 0, "这一轮没有落下的窗口");
        int lowest = Integer.MAX_VALUE, highest = 0;
        for (String engine : NINE) {
            int times = asked(capped, engine);
            check(times >= 1, engine + " 没有整轮被砍：照样问到了 " + times + " 次");
            lowest = Math.min(lowest, times);
            highest = Math.max(highest, times);
        }
        /* 九家不再一样多是刻意的：第一层先尽着中文主库，它们各自把 24 扇名额花满，
           剩下的额度才轮到海外六家。摊平那条规矩移到没勾中文源的那一档去锁
           （见 allocationRules 里的 flat 那段）。 */
        check(asked(capped, "cnki") >= 20 && asked(capped, "cqvip") >= 20
                        && asked(capped, "wanfang") >= 20,
                "中文主库把自己那 24 扇名额几乎花满（重复短语会省掉一两次）："
                        + asked(capped, "cnki") + "/" + asked(capped, "cqvip")
                        + "/" + asked(capped, "wanfang"));        check(lowest >= 8,
                "海外六家也没被撑到一次也没问：最少的一家也问了 " + lowest + " 次，"
                        + "最多的 " + highest + " 次");
        check(!capped.retrievalIncomplete, "取回了候选的轮次是跑完，不是未完成");
        check(!capped.retrievalPartial, "67 扇全问到了就不许多自认部分是");
        /* 额度真不够用的那一档：200 扇窗口、九家各 24 扇的手，120 次只买得起 120 扇。 */
        resetFixtures();
        DuplicateEngine.Report thin = scan(thesis(600), new TextCorpus(), nine(), limits(12, 24));
        check(thin.windowsAvailable == 200, "600 段正文切出 200 扇窗口");
        check(thin.asksPlanned == DuplicateEngine.MAX_REQUESTS, "排期正好把 120 次花完");
        check(thin.windowsPlanned == 120 && thin.windowsUnstaffed == 80,
                "额度只排得出 120 扇，剩下 80 扇明说没排上：" + thin.windowsPlanned
                        + "/" + thin.windowsUnstaffed);
        check(thin.budgetCapped && !thin.capCapped, "挡下这 80 扇的是请求额度，不是每源上限");
        check(notes(thin, "检索请求额度 120 次只排得出 120 扇窗口，剩余 80 扇本轮没排上"),
                "额度挡的必须写成额度，不能含糊成没跑完");
        check(thin.retrievalPartial && thin.retrievalPartialReason.contains("相似率是下限"),
                "被额度截断的比率必须自己声明是下限");
        check(notes(thin, thin.retrievalPartialReason), "面板上那行原因在注记里有同一句原文");
    }

    private static void timeCeiling() {
        resetFixtures();
        slowMillis = 300L;
        DuplicateEngine.searchMillis = 4000L;
        PaperSources.Limits limits = limits(12, 12);
        limits.timeoutSeconds = 1;
        DuplicateEngine.Report slow = scan(thesis(200), new TextCorpus(),
                engines("cnki", "cqvip"), limits);
        slowMillis = 0L;
        DuplicateEngine.searchMillis = DuplicateEngine.MAX_SEARCH_MILLIS;
        check(notes(slow, "检索时间已用满"), "时间用完必须写进注记，不能悄悄结束");
        check(notes(slow, "个检索窗口后时间用完"), "注记要写清检索到第几个窗口时时间用完");
        check(slow.windowsPlanned == 24 && slow.windowsUnstaffed == 43,
                "两家各 12 扇只排得起 24 扇，剩下 43 扇开场就报了数：" + slow.windowsPlanned
                        + "/" + slow.windowsUnstaffed);
        check(slow.windowsRetrieved >= 2 && slow.windowsRetrieved < slow.windowsPlanned,
                "挂钟闸门确实在计划窗口跑完之前停了下来");
        check(notes(slow, "剩余 " + (slow.windowsPlanned - slow.windowsRetrieved + slow.windowsUnstaffed)
                        + " 个检索窗口未检索"),
                "剩余那一句按整篇算：排上了没来得及问的与根本没排上的都算进去，不能只报一半");
        check(slow.retrievalPartial && slow.retrievalPartialReason.contains("检索时间已用满"),
                "时间截断的部分完成原因指向时间闸门");
        check(!slow.retrievalIncomplete, "已经取回候选的时间截断轮次不许走未完成那一态");
    }

    // ---- C 组：报告与 HTML 里的覆盖率是真数字 ----

    private static void coverageDisclosure(DuplicateEngine.Report report) {
        check(report.windowsAvailable == 67 && report.windowsRetrieved == 67,
                "报告同时带着可切窗口数与实检窗口数两个数");
        check(report.comparableChars == 8000, "200 段四十字的可检索正文按 validCount 记 8000 字");
        check(report.coveredChars == 8000, "67 扇全问到就覆盖全部 8000 字");
        check(report.coveredChars <= report.comparableChars, "覆盖字数不会超过可比对正文");
        check(notes(report, "已检索 67/67 个检索窗口"), "覆盖率注记写的是实检比可切");
        check(notes(report, "覆盖 8000 字（全文可比对 8000 字，100.00%）"), "覆盖率注记带字数与百分比");
        check(notes(report, "真问出去 108 次，摊到这些窗口每窗 1-2 家"),
                "覆盖率那一句要交代这 120 次额度真花成了什么形状，不能只报一个窗口数");
        check(notes(report, "其中 36 扇问到知网/万方/维普"),
                "哪几扇问到过中文主库也写在这一句里——中文稿查重看的就是这三家");
        check(notes(report, "可比材料只有摘要的有"), "报告要交代语料里有多少篇只有摘要可比");
        check(notes(report, "摘要层（近似"), "抓到了全文也要单独报一行摘要级的数，不与正文级混成一个数");
        check(report.abstractOnlyCandidates + report.fullTextCandidates == report.comparableCandidates,
                "只有摘要与抓到全文的篇数加起来等于可比候选数");
        check(report.recordOnlyCandidates == 0, "只有题录的候选不计入可比候选");
        check(!report.retrievalPartial && report.retrievalPartialReason.isEmpty(),
                "全篇问到了就不许自认部分是，也不许多留一句下限");
        String wide = CheckReport.html("thesis.docx", report);
        check(wide.contains("检索覆盖率"), "HTML 里有覆盖率小节");
        check(wide.contains("67/67"), "HTML 里写着实检比可切的窗口数");
        check(wide.contains("真问出去 108 次"), "HTML 那一格也写着提问次数与每窗几家");
        check(wide.contains("仅摘要可比"), "HTML 里写着只有摘要可比的篇数");
        check(wide.contains("提问次数"), "来源表里有每个源被提问的次数");
        check(wide.contains("可比材料"), "候选表里有可比材料这一列");
        check(wide.contains("总相似度比") && wide.contains("自编率"), "报告照样给出三个比率");
        check(!wide.contains("未完成查重"), "跑完的轮次绝不允许走未完成那一态");
        check(report.notes.size() <= DuplicateEngine.MAX_NOTES, "注记条数仍在 MAX_NOTES 之内");
    }

    /** 额度买不起全部窗口的那一轮：没排上的那几扇必须点名，比率必须自称下限，还要给出路。 */
    private static void partialDisclosure() {
        resetFixtures();
        PaperSources.Limits tight = limits(12, 2);
        tight.fullTexts = 0;   // 一篇全文都不许抓：只剩摘要可比那条短板必须自己说出口
        DuplicateEngine.Report narrow = scan(thesis(200), new TextCorpus(), nine(), tight);
        check(narrow.windowsAvailable == 67 && narrow.windowsPlanned == 18,
                "每家只许问 2 扇时九家只排得上 18 扇：" + narrow.windowsPlanned);
        check(narrow.windowsUnstaffed == 49, "剩下 49 扇当场报数，不许悄悄不算");
        check(narrow.capCapped && !narrow.budgetCapped, "挡下这 49 扇的是每源上限，不是 120 次额度");
        check(notes(narrow, "每个检索源按设置在 2 扇处截断，剩余 49 扇本轮没排上"),
                "被每源上限挡住就说每源上限，别写成检索请求已达上限");
        check(notes(narrow, "把检索设置的窗口数调到 24 可扩大覆盖"), "被设置挡住要告诉用户怎么扩大覆盖");
        check(narrow.coveredChars == 18 * 120, "覆盖字数只算真问到的 18 扇：" + narrow.coveredChars);
        check(notes(narrow, "已检索 18/67 个检索窗口"), "覆盖率注记写的是实检比可切");
        check(narrow.retrievalPartial && narrow.retrievalPartialReason.contains("相似率是下限"),
                "部分完成必须写出下限措辞");
        check(notes(narrow, narrow.retrievalPartialReason), "面板上那行原因在注记里有同一句原文");
        String html = CheckReport.html("thesis.docx", narrow);
        check(html.contains("18/67"), "HTML 里写着实检比可切的窗口数");
        check(html.contains("相似率是下限"), "HTML 把部分覆盖的比率标成下限");
        check(notes(narrow, "知网、万方、维普只回摘要"),
                "只剩摘要可比的时候，这条结构性短板必须说出口");
        check(!html.contains("未完成查重"), "部分完成绝不允许走未完成那一态");
    }

    // ---- G 组：额度分配器本身。不联网，纯算排期，哪条规矩破了当场就能看见 ----

    private static ArrayList<Integer> vals(int... values) {
        ArrayList<Integer> out = new ArrayList<Integer>();
        for (int value : values) out.add(Integer.valueOf(value));
        return out;
    }
    private static ArrayList<Integer> repeated(int times, int value) {
        ArrayList<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < times; i++) out.add(Integer.valueOf(value));
        return out;
    }
    private static int staffed(DuplicateEngine.Allocation a) { return a.windowsPlanned; }

    private static void allocationRules() {
        ArrayList<String> nine = nine();
        /* 一、铺宽优先：只要额度买得起，每一扇都要被问到一次。 */
        DuplicateEngine.Allocation wide = DuplicateEngine.allocate(repeated(60, 300), repeated(60, 300), repeated(60, 1),
                nine, 12, 120, DuplicateEngine.ORPHAN_PROBE_LAYER);
        check(staffed(wide) == 60 && wide.unstaffed == 0,
                "六十扇等长窗口、九家各 12 扇：60 扇全排上");
        check(wide.asks.size() == 108,
                "先用 60 次把每扇铺一遍，剩下的才回头补第二家：实测 "
                        + wide.asks.size());
        check(wide.depthLow == 1 && wide.depthHigh == 2,
                "铺宽排在加深前面：最浅的一扇一家，最深的也只有两家");
        /* 二、额度挡住的那一档：排期正好花完，没排上的数目与闸门来源都写清楚。 */
        DuplicateEngine.Allocation thin = DuplicateEngine.allocate(repeated(200, 300), repeated(200, 300), repeated(200, 1),
                nine, 24, 120, DuplicateEngine.ORPHAN_PROBE_LAYER);
        check(staffed(thin) == 120 && thin.unstaffed == 80,
                "两百扇窗口、120 次额度：只排得出 120 扇，剩 80 扇");
        check(thin.cappedByRequests && !thin.cappedByCap, "这是额度花光了，不是每源上限挡的");
        check(thin.depthLow == 1 && thin.depthHigh == 1, "额度不够时宁可每扇一次铺满，不肯把几扇问穿");
        /* 三、每源上限挡住的那一档。 */
        DuplicateEngine.Allocation narrow = DuplicateEngine.allocate(repeated(67, 300), repeated(67, 300), repeated(67, 1),
                nine, 4, 120, DuplicateEngine.ORPHAN_PROBE_LAYER);
        check(staffed(narrow) == 36 && narrow.unstaffed == 31,
                "九家各 4 扇 = 36 扇，剩下 31 扇：实测 " + staffed(narrow) + "/" + narrow.unstaffed);
        check(narrow.cappedByCap && !narrow.cappedByRequests, "这一档挡人的是每源上限，额度还剩着");
        /* 四、窗口按覆盖字数排队：几个字的过场段落不配和整段结论抢同一份额度。 */
        ArrayList<Integer> mixed = vals(50, 400, 60, 500, 70, 30);
        /* 与 mixed 一一对应的中文字数：这一段是正文，字数多的那几扇中文字也多，顺序一致。 */
        ArrayList<Integer> mixedChinese = vals(40, 330, 50, 420, 55, 20);
        DuplicateEngine.Allocation byValue = DuplicateEngine.allocate(mixedChinese, mixed, repeated(6, 1),
                engines("cnki"), 2, 120, DuplicateEngine.ORPHAN_PROBE_LAYER);
        check(staffed(byValue) == 2, "一家两扇的名额就排两扇");
        check(askedWindow(byValue, 3) && askedWindow(byValue, 1) && !askedWindow(byValue, 0),
                "排上的是 500 字与 400 字那两扇，不是正文最前面那扇 50 字的过场");
        check(covered(byValue, mixed) == 900, "同样两个名额买到 900 字进比对：" + covered(byValue, mixed));
        boolean saved = DuplicateEngine.windowsByChars;
        try {
            DuplicateEngine.windowsByChars = false;
            DuplicateEngine.Allocation byOrder = DuplicateEngine.allocate(mixedChinese, mixed, repeated(6, 1),
                    engines("cnki"), 2, 120, DuplicateEngine.ORPHAN_PROBE_LAYER);
            check(covered(byOrder, mixed) == 450,
                    "按正文顺序拿走的是前两扇：" + covered(byOrder, mixed) + " 字");
            check(covered(byValue, mixed) == 2 * covered(byOrder, mixed),
                    "同一两个名额，按字数排买到的字数翻一倍："
                            + covered(byValue, mixed) + " 对 " + covered(byOrder, mixed));
            DuplicateEngine.Allocation tied = DuplicateEngine.allocate(repeated(6, 100), repeated(6, 100), repeated(6, 1),
                    engines("cnki"), 2, 120, DuplicateEngine.ORPHAN_PROBE_LAYER);
            check(askedWindow(tied, 0) && askedWindow(tied, 1),
                    "字数一样的窗口还是按正文顺序排，不靠运气");
        } finally {
            DuplicateEngine.windowsByChars = saved;
        }
        /* 五、中文主库优先，且没勾中文源时不许崩。 */
        DuplicateEngine.Allocation firstPass = DuplicateEngine.allocate(repeated(9, 300), repeated(9, 300), repeated(9, 1),
                nine, 1, 120, DuplicateEngine.ORPHAN_PROBE_LAYER);
        check(firstPass.chineseWindows == 3,
                "九个名额里三家中文主库先各占一扇：" + firstPass.chineseWindows);
        DuplicateEngine.Allocation abroad = DuplicateEngine.allocate(repeated(6, 300), repeated(6, 300), repeated(6, 1),
                engines("openalex", "arxiv"), 3, 120, DuplicateEngine.ORPHAN_PROBE_LAYER);
        check(staffed(abroad) == 6 && abroad.chineseWindows == 0,
                "只勾海外两家也照样把六扇铺满，一家中文源也没有");
        /* 六、混主题那条补问式排在铺宽之后：额度只够每扇一次时它一次也不许排。 */
        ArrayList<Integer> four = repeated(4, 300);
        DuplicateEngine.Allocation tight = DuplicateEngine.allocate(four, four, vals(1, 2, 1, 1),
                engines("cnki", "arxiv"), 8, 6, DuplicateEngine.ORPHAN_PROBE_LAYER);
        check(staffed(tight) == 4 && tight.asks.size() == 6,
                "六次额度先把四扇各问一次再补两扇："
                        + staffed(tight) + " 扇 / " + tight.asks.size() + " 次");
        for (int i = 0; i < tight.asks.size(); i++)
            check(tight.asks.get(i).probe == 0,
                    "铺宽还没铺完就轮不到那条补问式：第 " + i + " 次");
        DuplicateEngine.Allocation roomy = DuplicateEngine.allocate(four, four, vals(1, 2, 1, 1),
                engines("cnki", "arxiv"), 8, 9, DuplicateEngine.ORPHAN_PROBE_LAYER);
        check(roomy.asks.size() == 9 && roomy.asks.get(8).probe == 1,
                "八次把四扇各问两家，第九次才轮到那条补问式："
                        + roomy.asks.size() + " 次");
        /* 七、没有任何一家被整轮跳过。 */
        LinkedHashMap<String, Integer> per = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < thin.asks.size(); i++) {
            String key = thin.asks.get(i).engine;
            Integer had = per.get(key);
            per.put(key, Integer.valueOf(had == null ? 1 : had.intValue() + 1));
        }
        for (String engine : NINE)
            check(per.get(engine) != null && per.get(engine).intValue() >= 1,
                    engine + " 没被整轮跳过：分到 " + per.get(engine) + " 次");
        check(thin.chineseWindows == 72,
                "第一层先尽着中文主库：三家各 24 扇 = 72 扇先铺到中文，"
                        + "剩下 48 扇由六家海外源接住");
        /* 没勾中文源的时候没有优先层，同一份额度必须摊平。 */
        DuplicateEngine.Allocation flat = DuplicateEngine.allocate(repeated(200, 300), repeated(200, 300),
                repeated(200, 1), engines("openalex", "crossref", "arxiv", "europepmc"),
                60, 120, DuplicateEngine.ORPHAN_PROBE_LAYER);
        int[] counts = new int[4];
        for (int i = 0; i < flat.asks.size(); i++)
            counts[engines("openalex", "crossref", "arxiv", "europepmc").indexOf(
                    flat.asks.get(i).engine)]++;
        int lo = counts[0], hi = counts[0];
        for (int value : counts) { lo = Math.min(lo, value); hi = Math.max(hi, value); }
        check(hi - lo <= 1,
                "没勾中文源时 120 次摊到四家相差不超过一次：实测 " + lo + "-" + hi);

        /* 八、排窗口先看中文字数，再看总字数。这篇样稿里最长的一扇是正文段落（856 字），
           第二长与第五长却是两条参考文献条目（705 字、667 字，一个中文字都没有）：光按总字数排，
           每家只被问两扇的时候钱全花在英文书目上，中文主库拿到英文书目只回一屏不相干的英文题录。 */
        ArrayList<Integer> refChars = vals(600, 705, 660, 300);
        ArrayList<Integer> refChinese = vals(560, 0, 640, 290);
        ArrayList<Integer> order = DuplicateEngine.windowOrder(refChinese, refChars);
        check(order.get(0).intValue() == 2 && order.get(1).intValue() == 0,
                "先排中文字数多的那两扇（640 与 560）：" + order);
        check(order.get(3).intValue() == 1, "705 字的中零条排到最后：" + order);
        DuplicateEngine.Allocation refs = DuplicateEngine.allocate(refChinese, refChars, repeated(4, 1),
                engines("cnki"), 2, 120, DuplicateEngine.ORPHAN_PROBE_LAYER);
        check(askedWindow(refs, 2) && askedWindow(refs, 0) && !askedWindow(refs, 1),
                "两个名额给正文那两扇，不给最长的那扇参考文献条目");
        check(covered(refs, refChars) == 1260, "同样两个名额买到 " + covered(refs, refChars) + " 字正文");
        /* 纯英文稿子不受这条影响：中文字数全是零，退回复比总字数，顺序与之前一致。 */
        ArrayList<Integer> allAbroad = DuplicateEngine.windowOrder(repeated(4, 0), refChars);
        check(allAbroad.get(0).intValue() == 1 && allAbroad.get(1).intValue() == 2,
                "中文字数全零时还是按总字数从大到小排：" + allAbroad);
    }

    private static boolean askedWindow(DuplicateEngine.Allocation a, int window) {
        for (int i = 0; i < a.asks.size(); i++) if (a.asks.get(i).window == window) return true;
        return false;
    }
    private static int covered(DuplicateEngine.Allocation a, ArrayList<Integer> chars) {
        boolean[] hit = new boolean[chars.size()];
        int sum = 0;
        for (int i = 0; i < a.asks.size(); i++) {
            int w = a.asks.get(i).window;
            if (hit[w]) continue;
            hit[w] = true;
            sum += chars.get(w).intValue();
        }
        return sum;

    }

    /** 排给一家却问不动的那些格子必须换人补上：真机 2.6.3 那轮 5 个源判取尽，几十扇窗口跟着没问。 */
    private static void stoppedSourceHandsItsWindowsBack() {
        resetFixtures();
        throttled.add("/throttled");
        PaperSources.setEndpoint("arxiv", base + "/throttled");
        DuplicateEngine.Report report = scan(thesis(20), new TextCorpus(),
                engines("cnki", "cqvip", "wanfang", "openalex", "arxiv"), limits(12, 6));
        PaperSources.setEndpoint("arxiv", base + "/arxiv");
        throttled.clear();
        check(asked(report, "arxiv") == 2,
                "被限流的源补试一次就停手：" + asked(report, "arxiv"));
        check(report.asksSpilled == 4,
                "它排到的六格里没问出去的四格全部排回补问队列：" + report.asksSpilled);
        /* 谁来补是四条道当场抢的，谁抢到不确定；锁死的是结果：排上的窗口
           一扇也不许因为一家停了就整扇没问。 */
        check(report.windowsPlanned == 7 && report.windowsRetrieved == report.windowsPlanned,
                "一家停在半路上，排上的七扇还是全部问到了："
                        + report.windowsRetrieved + "/" + report.windowsPlanned);
        check(report.asksRefilled <= report.asksSpilled,
                "补问不会多于退回：" + report.asksRefilled + "/" + report.asksSpilled);
        check(report.asksSpilled == 0
                        || notes(report, "次提问被停问的检索源退回"),
                "退回了几格必须写进注记");
        check(report.asksRefilled == 0
                        || notes(report, "次改由其他检索源补问"),
                "补了几次写几次，一次没补也不许写");
    }


    // ---- H 组：扫描时顺手抓开放获取正文（检索设置里那个开关） ----

    /**
     * 真机 2.6.4 那一轮：12/49 扇、比对材料 51 篇，正文可比只有 4 篇，另有 8 篇挂着能直接下的 PDF
     * 等用户到结果页点。这一组量的是同一轮里顺手把它们抓回来按正文比对之后，屏幕上那几个数怎么变，
     * 以及额度不够时是不是照实说"还有几篇没来得及下"。
     */
    private static void autoFullTextFetch() {
        /* 一、开关与上限：默认开着、最多 6 篇；老设置里没这两项也按开着处理。 */
        EngineSettings fresh = new EngineSettings();
        check(fresh.autoPdf && fresh.autoPdfs == 6,
                "新装机默认顺手抓正文，上限 6 篇：" + fresh.autoPdf + "/" + fresh.autoPdfs);
        EngineSettings saved = new EngineSettings();
        saved.autoPdf = false; saved.autoPdfs = 2;
        EngineSettings back = EngineSettings.deserialize(EngineSettings.serialize(saved));
        check(!back.autoPdf && back.autoPdfs == 2, "关掉与改上限都存得住、读得回");
        EngineSettings old = EngineSettings.deserialize("{\"version\":3,\"web\":true,"
                + "\"engines\":[\"cnki\",\"openalex\"],\"perEngine\":12,\"timeout\":20,"
                + "\"windows\":12,\"coreKey\":\"\",\"proxy\":\"\"}");
        check(old.autoPdf && old.autoPdfs == 6,
                "3 号设置里没这两项：按新装机一样开着，不许静默关掉继续给用户看 0%");
        boolean refused = false;
        try { EngineSettings greedy = new EngineSettings(); greedy.autoPdfs = 11; greedy.validate(); }
        catch (IllegalArgumentException error) { refused = true; }
        check(refused, "上限超过 10 篇直接拒，不许拿设置当挡箭牌把请求额度全花在下载上");

        /* 二、开关开着：名次那一份全文额度之外的候选，由剩下的请求额度接住。 */
        resetFixtures();
        crossrefSize = 1;
        responseSize = 12;
        TextCorpus corpus = new TextCorpus();
        PaperSources.Limits on = limits(12, 4);
        on.fullTexts = 1;
        on.autoFullTexts = 3;
        DuplicateEngine.Report got = scan(thesis(3), corpus,
                engines("crossref", "openalex", "semantic-scholar", "europepmc"), on);
        check(got.autoPdfTried == 3 && got.autoPdfFetched == 3,
                "设了 3 篇就抓 3 篇，篇篇都带回正文：试 " + got.autoPdfTried + " 抓成 " + got.autoPdfFetched);
        check(got.fullTextCandidates == 4,
                "正文可比材料从名次队那 1 篇变成 4 篇：" + got.fullTextCandidates);
        check(hits("/fulltext") + hits("/europepmc/fulltext") == 4,
                "全文请求确实发了四次，不是报告自己写的："
                        + hits("/fulltext") + "+" + hits("/europepmc/fulltext"));
        check(corpusContains(corpus, FULL_TEXT_PROBE), "抓回来的正文进了比对语料");
        check(got.autoPdfLeft == 0, "额度够的时候不许喊没来得及：" + got.autoPdfLeft);
        check(notes(got, "本轮顺手抓了 3 篇开放获取全文，3 篇已按正文比对"),
                "注记按篇数说实话");
        check(!notes(got, "没来得及下"), "上限是自己设的，不许写成没来得及");
        check(CheckReport.html("thesis.docx", got).contains("顺手抓正文"),
                "报告那张表里有顺手抓正文那一行");
        int fullWith = got.fullTextCandidates;

        /* 三、开关关掉（或上限 0）：与之前一字不差，一篇也不许多抓。 */
        resetFixtures();
        crossrefSize = 1;
        responseSize = 12;
        PaperSources.Limits off = limits(12, 4);
        off.fullTexts = 1;
        off.autoFullTexts = 0;
        DuplicateEngine.Report shut = scan(thesis(3), new TextCorpus(),
                engines("crossref", "openalex", "semantic-scholar", "europepmc"), off);
        check(shut.autoPdfTried == 0 && shut.autoPdfLeft == 0,
                "上限 0 就是关掉：一次下载都不许多发");
        check(shut.fullTextCandidates == 1 && shut.fullTextCandidates < fullWith,
                "关掉之后正文可比回到名次队那一篇：" + shut.fullTextCandidates);
        check(!notes(shut, "顺手抓"), "没做这件事就不许在注记里提它");
        check(!CheckReport.html("thesis.docx", shut).contains("顺手抓正文"),
                "报告里也不多那一行");

        /* 四、抓回来没有正文层：仍然按摘要比对，且这一篇不许冒充正文可比。 */
        resetFixtures();
        crossrefSize = 1;
        responseSize = 12;
        fullTextEmpty = true;
        PaperSources.Limits dry = limits(12, 4);
        dry.fullTexts = 1;
        dry.autoFullTexts = 3;
        DuplicateEngine.Report empty = scan(thesis(3), new TextCorpus(),
                engines("crossref", "openalex", "semantic-scholar", "europepmc"), dry);
        check(empty.autoPdfTried == DuplicateEngine.MAX_AUTO_MISS_STREAK
                        && empty.autoPdfFetched == 0 && empty.autoPdfFailed == DuplicateEngine.MAX_AUTO_MISS_STREAK,
                "连着两篇抓回来一个字都没有就收手，剩下的请求还给检索：试 "
                        + empty.autoPdfTried + " 抓成 " + empty.autoPdfFetched
                        + " 落空 " + empty.autoPdfFailed);
        check(empty.fullTextCandidates == 0,
                "抓回空正文的一篇也不许标成正文可比：" + empty.fullTextCandidates);
        check(notes(empty, "2 篇抓回来没有正文层，仍按摘要比对"), "落空那几篇要说清仍按摘要比");
        check(notes(empty, "没来得及下（连着 2 篇都读不出正文层）"),
                "收手之后剩下的篇数照实写成没来得及，并写明是被连着落空挡的：" + empty.autoPdfLeft);
        check(notes(empty, "回来的页面几乎没字 " + DuplicateEngine.MAX_AUTO_MISS_STREAK + " 篇"),
                "落空按形状说人话，不许只写一句\u201c没抓到\u201d：" + empty.autoPdfShapes);
        check(CheckReport.html("thesis.docx", empty).contains("回来的页面几乎没字"),
                "报告那张表里的顺手抓正文一行也带形状");
        check(DuplicateEngine.autoFetchLine(2, 0, 2, 1, "连着 2 篇都读不出正文层",
                "回来的页面几乎没字 2 篇").contains("仍按摘要比对（回来的页面几乎没字 2 篇）"),
                "形状只跟在落空那半句后面");
        fullTextEmpty = false;

        /* 五、检索把 120 次额度花光了：剩下的候选照实写"没来得及下"，并写明是被什么挡的。 */
        resetFixtures();
        responseSize = 12;
        PaperSources.Limits tight = limits(12, 24);
        tight.autoFullTexts = 10;
        DuplicateEngine.Report drained = scan(thesis(200), new TextCorpus(), nine(), tight);
        check(drained.asksSent == DuplicateEngine.MAX_REQUESTS,
                "这一轮检索先把请求额度花光：" + drained.asksSent + "/" + DuplicateEngine.MAX_REQUESTS);
        check(drained.asksSent + drained.autoPdfTried <= DuplicateEngine.MAX_REQUESTS,
                "顺手抓正文与检索抢的是同一个 120 次，合计不许越过去："
                        + drained.asksSent + "+" + drained.autoPdfTried);
        check(drained.autoPdfLeft > 0 && notes(drained, "没来得及下（检索请求额度已用完）"),
                "还有 " + drained.autoPdfLeft + " 篇挂着 PDF 没排上，注记里要写明是额度用完");
        check(drained.autoPdfTried == 0, "额度见底之后一篇也不许多抓：" + drained.autoPdfTried);

        /* 六、抓回的正文要落进自建库。用户要的是"顺手抓进自建库"，不是这一轮在内存里比完就丢：
           下一轮检索即使那几家不再回正文，自建库里也还留着那几篇可比正文。 */
        check(got.autoBodies.size() == 3,
                "抓成正文的三篇都留着正文本身交给自建库：" + got.autoBodies.size());
        check(shut.autoBodies.isEmpty() && drained.autoBodies.isEmpty(),
                "关掉开关与额度见底这两轮一篇也不许多存："
                        + shut.autoBodies.size() + "/" + drained.autoBodies.size());
        LocalLibrary library = new LocalLibrary(freshLibraryDir("auto-fetch"));
        String stored = DuplicateEngine.fileAutoBodies(library, got);
        /* 桩对三次全文请求回的是同一份 PDF 文本，入库那一条哈希判重的规矩就该把它们挡成一篇。
           断言按规矩写，不按"抓了三篇就该有三篇"写：那是测愿望。 */
        check(library.size() == 1, "同一份正文抓三次也只进一篇：" + library.size());
        check(stored.contains("顺手抓回的正文已存进自建库 1 篇（自建库现在 1 篇")
                && stored.contains("另有 2 篇库里已有同一正文，没有重复入库"),
                "存库那一句把存了几篇、几篇是同一正文都说明白：" + stored);
        int storeAt = got.notes.indexOf(stored), fetchAt = -1;
        for (int i = 0; i < got.notes.size(); i++) {
            if (got.notes.get(i).startsWith("本轮顺手抓了")) fetchAt = i;
        }
        check(fetchAt >= 0 && storeAt == fetchAt + 1,
                "存库那一句紧跟在顺手抓正文那一句后面，不被注记上限挤掉：" + storeAt + "/" + fetchAt);
        check(CheckReport.html("thesis.docx", got).contains("顺手抓回的正文已存进自建库"),
                "报告那张表/注记里读得到存库这一句");
        TextCorpus reread = new TextCorpus();
        library.index(reread);
        check(corpusContains(reread, FULL_TEXT_PROBE),
                "重开这个库再 index，抓回的正文仍在比对语料里——下一轮不必再花请求重抓");

        /* 正文各不相同的三篇：三篇都该进库，库里的篇数要跟着涨，第二遍重跑不许翻倍。 */
        LocalLibrary distinct = new LocalLibrary(freshLibraryDir("auto-fetch-distinct"));
        DuplicateEngine.Report three = new DuplicateEngine.Report();
        three.autoBodies.add(new DuplicateEngine.AutoBody("钎焊界面扩散层研究", "openalex",
                FULL_TEXT_PROBE + "第一篇的其余段落，讲保温时间与硬度分布。"));
        three.autoBodies.add(new DuplicateEngine.AutoBody("互连层的可靠性评估", "europepmc",
                FULL_TEXT_PROBE + "第二篇的其余段落，讲热循环下的失效判据。"));
        three.autoBodies.add(new DuplicateEngine.AutoBody("多孔铜的制备参数", "cnki",
                FULL_TEXT_PROBE + "第三篇的其余段落，讲孔隙率与电流密度的关系。"));
        String threeLine = DuplicateEngine.fileAutoBodies(distinct, three);
        check(distinct.size() == 3 && threeLine.startsWith("顺手抓回的正文已存进自建库 3 篇（自建库现在 3 篇"),
                "三份不同的正文三篇都进库：" + distinct.size() + " " + threeLine);
        String twice = DuplicateEngine.fileAutoBodies(distinct, three);
        check(distinct.size() == 3 && twice.contains("另有 3 篇库里已有同一正文"),
                "同一轮重跑一遍不许多存一篇：" + distinct.size() + " " + twice);

        /* 空正文与"没开开关"都不该在库里留东西，也不该在报告里留一句空话。 */
        LocalLibrary dryRun = new LocalLibrary(freshLibraryDir("auto-fetch-dry"));
        check(DuplicateEngine.fileAutoBodies(dryRun, shut).isEmpty() && dryRun.size() == 0,
                "关掉开关的那一轮：自建库一个字都不许多，报告里也不多一句");
        check(DuplicateEngine.autoStoreLine(0, 0, 0, "", 0).isEmpty(),
                "一篇没存、一篇没挡就不开口");
        DuplicateEngine.Report blank = new DuplicateEngine.Report();
        blank.autoBodies.add(new DuplicateEngine.AutoBody("抓回来是空的", "crossref", "   "));
        String blankLine = DuplicateEngine.fileAutoBodies(new LocalLibrary(freshLibraryDir("auto-fetch-blank")), blank);
        check(blankLine.contains("1 篇没存进自建库："), "空正文按落空算并写明原因：" + blankLine);
        resetFixtures();
        crossrefSize = -1;
    }

    /** 每次都给一个空的库目录：篇数断言只有从零开始数才算数。 */
    private static File freshLibraryDir(String name) {
        File directory = new File("artifacts/build/host-auto-library/" + name);
        File[] children = directory.listFiles();
        if (children != null) for (int i = 0; i < children.length; i++) children[i].delete();
        directory.mkdirs();
        return directory;
    }

    // ---- D 组：按名次入库，零分候选不进语料 ----

    private static void rankedIntake() {
        resetFixtures();
        boilerplate = true;
        crossrefSize = 1;
        responseSize = 12;
        /* 每源上限 4 扇：四个源在这一扇窗口上各问一次，与改前那轮同样四次提问，
           名次与全文额度那些数才还能一个不动地比。 */
        PaperSources.Limits limits = limits(12, 4);
        limits.fullTexts = 1;
        TextCorpus corpus = new TextCorpus();
        DuplicateEngine.Report report = scan(thesis(3), corpus,
                engines("crossref", "openalex", "semantic-scholar", "europepmc"), limits);
        check(!report.candidates.isEmpty(), "取回了候选文献");
        check(lists(report.candidates.get(0), "crossref"),
                "入库第一位是给题的那条 crossref 记录，不是先回来的十二套话");
        check(report.candidates.get(0).source.title.contains("多孔铜"),
                "第一个入库名额落在与本文对题的候选上");
        check(report.unrankedCandidates == 12, "十二条与检索词零共同词的候选全部拦在语料之外");
        check(notes(report, "12 条候选与检索词无任何共同词，未纳入比对"), "被挡掉的条数要写进注记");
        boolean leaked = false;
        for (PaperSources.Candidate candidate : report.candidates)
            if (lists(candidate, "semantic-scholar")) leaked = true;
        check(!leaked, "零分候选一条都没混进语料");
        check(!corpusContains(corpus, BOILERPLATE_PROBE), "万能句摘要没有进入本机语料，分母不被污染");
        check(report.mergedDuplicates == 1, "同一个 DOI 从两个源各回一次，只占一个入库名额");
        check(notes(report, "跨源合并"), "合并这件事在注记里留了账");
        check(report.candidates.size() == 24, "三十七条候选并掉一条、再挡掉十二条零分，剩下二十四条");
        check(hits("/fulltext") == 1 && hits("/europepmc/fulltext") == 0,
                "唯一的全文额度花在名次第一的那条上");
        check(report.fullTextCandidates == 1, "报告数得出真正带着已抓全文的那一条");
        check(DuplicateEngine.MATERIAL_FULL.equals(report.candidates.get(0).comparableMaterial),
                "名次第一的那条标成全文可比");
        check(corpusContains(corpus, FULL_TEXT_PROBE), "抓回来的全文确实进了比对语料");
        check(report.abstractOnlyCandidates == 23, "其余二十三条只有摘要可比");
        String html = CheckReport.html("thesis.docx", report);
        check(html.contains("按 DOI"), "HTML 写得出这一对是按哪个 DOI 并的");
        check(html.contains("零共同词被挡掉 12 篇"), "HTML 的覆盖率小节也交代零分闸门挡掉了几篇");
        boilerplate = false;
        crossrefSize = -1;
        sticky = false;
    }

    /** 逐源配额与全库上限同时在场：perEngine=20 时九个源能要 180 条，总闸必须先落。 */
    private static void corpusCeiling() {
        resetFixtures();
        responseSize = 20;
        DuplicateEngine.Report report = scan(thesis(200), new TextCorpus(), nine(), limits(20, 2));
        check(report.candidates.size() == DuplicateEngine.MAX_CORPUS_PAPERS,
                "入库数停在 120 篇这一道闸，不会继续吃进语料");
        check(gathered(report) <= DuplicateEngine.MAX_CORPUS_PAPERS, "逐源计数加起来不超过全库上限");
        check(notes(report, "候选文献已达上限 120 篇"), "语料闸门也必须留字据");
        int gates = 0;
        for (String note : report.notes)
            if (note.equals("候选文献已达上限 120 篇，后续检索结果未入库")) gates++;
        check(gates == 1, "同一道闸门一轮只发一条注记，不刷屏");
        responseSize = 12;
    }

    // ---- 限流、取尽、限速、取消 ----

    private static void throttleCountsBySource() {
        resetFixtures();
        throttled.add("/throttled");
        PaperSources.setEndpoint("arxiv", base + "/throttled");
        DuplicateEngine.Report report = scan(thesis(200), new TextCorpus(),
                engines("cnki", "arxiv"), limits(12, 4));
        PaperSources.setEndpoint("arxiv", base + "/arxiv");
        throttled.clear();
        check(hits("/throttled") == 2, "被限流的源在整轮里只补试一次，不拿配额去撞同一个窗口");
        check(asked(report, "arxiv") == 2 && asked(report, "cnki") == 4,
                "补试用完之后才放弃这个源，另一条源照常被问满四个窗口");
        check(notes(report, "已重试"), "限流补试的那一次不许谎称已经跳过这个源");
        check(notes(report, "1 个检索源本次不可用"), "不可用按源计，两次失败也只算一个源");
        check(!notes(report, "2 个检索源本次不可用"), "限流补试不会被算成第二个不可用的源");
        check(!report.retrievalIncomplete, "另一个源取回了候选，整轮就不是什么都没查");
    }

    private static void exhaustedSourceStopsAsking() {
        resetFixtures();
        sticky = true;
        responseSize = 2;
        DuplicateEngine.Report report = scan(thesis(200), new TextCorpus(), engines("cnki"), limits(12, 6));
        check(asked(report, "cnki") == 3, "连续两个窗口没有新增文献之后判定该源已取尽，不再花配额");
        check(notes(report, "个检索源已取尽"), "取尽这件事要在注记里说清楚");
        check(report.windowsRetrieved == 3 && report.windowsPlanned == 6,
                "取尽之后剩余窗口一次也没被问，报告如实记成三个窗口");
        check(report.retrievalPartial, "源自己取尽导致的截断同样是部分完成");
        responseSize = 12;
    }

    /** 400ms 的间隔是给真机防封 IP 的，这里压成 250ms 再量，免得回归白等两秒。 */
    private static void sameSourceIsPaced() {
        resetFixtures();
        DuplicateEngine.engineGapMillis = 250L;
        DuplicateEngine.Report paced = scan(thesis(200), new TextCorpus(), engines("cnki"), limits(12, 4));
        DuplicateEngine.engineGapMillis = 0L;
        check(asked(paced, "cnki") == 4, "限速不改变提问次数，只改变间隔");
        check(paced.elapsedMillis >= 700L,
                "同一源相邻两次提问之间真的隔开了一个完整间隔：" + paced.elapsedMillis + "ms");
    }

    // ---- F 组：跨源并行。串行时最慢的那个源会把其余源的挂钟一起花掉，这是覆盖率的头号漏洞 ----

    /** 三个源各睡 300ms：串行实现里永远只有一条请求在飞，并行必须一次量到三条。 */
    private static void sourcesAskAtTheSameTime() {
        resetFixtures();
        SLOW_MILLIS.put("/cnki", Long.valueOf(300L));
        SLOW_MILLIS.put("/cqvip", Long.valueOf(300L));
        SLOW_MILLIS.put("/wanfang", Long.valueOf(300L));
        DuplicateEngine.Report report = scan(thesis(200), new TextCorpus(),
                engines("cnki", "cqvip", "wanfang"), limits(12, 4));
        check(DuplicateEngine.MAX_LANES >= 3, "泳道上限至少 3，否则三个源永远碰不到一起");
        check(peakInFlight() >= 3, "三条源同时在飞：峰值 " + peakInFlight() + " 条并发，串行实现里这个数永远是 1");
        check(asked(report, "cnki") == 4 && asked(report, "cqvip") == 4 && asked(report, "wanfang") == 4,
                "并行不改变每个源被问的次数：四扇窗口每个源照旧四次");
        check(report.windowsRetrieved == 12, "三家各四扇的名额排满十二扇，全部问出去");
        check(report.windowsUnstaffed == 55
                        && report.retrievalPartialReason.contains("每个检索源按设置在 4 扇处截断"),
                "六十七扇只排上十二扇：自认部分是，且原因指向每源上限而不是银幕");
    }

    /** 一个慢源挂在一扇窗口上，其余源不许陪它一起等：这一条在串行实现里必输，因为它只能问出一次。 */
    private static void slowSourceCannotStarveTheOthers() {
        resetFixtures();
        SLOW_MILLIS.put("/cqvip", Long.valueOf(1200L));
        DuplicateEngine.searchMillis = 4000L;
        PaperSources.Limits budget = limits(12, 12);
        budget.timeoutSeconds = 2;
        DuplicateEngine.Report report = scan(thesis(200), new TextCorpus(), engines("cnki", "cqvip"), budget);
        DuplicateEngine.searchMillis = DuplicateEngine.MAX_SEARCH_MILLIS;
        int fast = asked(report, "cnki"), slow = asked(report, "cqvip");
        check(slow >= 1, "慢源一次也没被丢掉，只是问得少：" + slow + " 次");
        check(fast >= 3 * slow, "慢源只许拖死自己这一路：快的问了 " + fast + " 次，慢的 " + slow + " 次");
        check(report.windowsRetrieved >= 3 * Math.max(1, slow),
                "慢源还在第一扇窗口上时，快源已经往前多问了好几扇：覆盖 " + report.windowsRetrieved + " 扇");
        check(report.retrievalPartial && report.retrievalPartialReason.contains("检索时间已用满"),
                "时间闸门截断的这一轮照样自认部分完成，原因指向时间");
    }

    /** 并行最容易破的就是"同一源两次提问之间隔 400ms"——那是给真机防封 IP 的，量的是桩看到的间隔。 */
    private static void pacingHoldsAcrossLanes() {
        resetFixtures();
        DuplicateEngine.engineGapMillis = DuplicateEngine.MIN_ENGINE_GAP_MILLIS;
        DuplicateEngine.Report report = scan(thesis(200), new TextCorpus(), nine(), limits(12, 6));
        DuplicateEngine.engineGapMillis = 0L;
        check(requests(report) == 9 * 6, "六个窗口九个源各问一次，总共 54 次请求：" + requests(report));
        check(shortestGap() >= DuplicateEngine.MIN_ENGINE_GAP_MILLIS - 40L,
                "四条泳道同时跑，同一源相邻两次提问仍隔满一个间隔：实测最短 " + shortestGap() + "ms");
        check(peakInFlight() >= 2, "同源限速没把整轮退回串行：峰值 " + peakInFlight() + " 条并发");
        check(report.windowsRetrieved == 54, "限速之下九家各六扇 = 54 扇照样全跑完");
    }
    private static void cancelledAndPrivate(String documentText) {
        resetFixtures();
        ApiClient.Task task = new ApiClient.Task();
        task.cancel();
        DuplicateEngine.Report stopped = DuplicateEngine.scan(TextSelection.all(thesis(3)), new TextCorpus(),
                true, nine(), limits(12, 6), task, null);
        check(totalHits() == 0, "在检索之前就取消的轮次一个请求都不发");
        check(stopped.notes.toString().contains("取消"), "取消同样写进注记");
        check(stopped.retrievalIncomplete, "什么都没查的轮次仍是未完成那一态");
        resetFixtures();
        DuplicateEngine.Report report = scan(thesis(20), new TextCorpus(), nine(), limits(12, 3));
        int longest = 0;
        for (String path : HITS.keySet()) longest = Math.max(longest, hits(path));
        check(longest == 3, "二十段正文切出七个窗口，但设置里只许三个窗口");
        check(!report.notes.toString().contains(documentText), "注记里不会回贴整篇正文");
    }


    /**
     * E 组：只有中段那扇窗口问得回的真出处，也必须进比对语料。
     *
     * 这是 2026-10-08 真机那篇 19967 字开题报告报 0.00% 的复现。第二阶段以前拿
     * queryPhrase(整篇, 48) 排序：整篇压成四十八个字只够一两句的词，其余窗口问回来的候选
     * 一个三元组都对不上，全被零分闸门挡在语料之外，最后没几条可比，整篇就报零。
     * 排序式换成"这一轮真正问出去的检索式"之后，中段命中的那条按名次入库，零分闸门照旧拦套话。
     */
    /**
     * E 组：只有中段那扇窗口问得回的真出处，也必须进比对语料。
     *
     * 这是 2026-10-08 真机那篇 19967 字开题报告报 0.00% 的复现。第二阶段以前拿
     * queryPhrase(整篇, 48) 当排序式：整篇压成四十八个字只够一两句的词，其余窗口问回来的候选一个
     * 三元组都对不上，全被零分闸门挡在语料之外，最后没几条可比，整篇就报零。排序式换成"这一轮真正
     * 问出去的检索式"之后，中段命中的那条按名次入库，零分闸门照旧拦套话。
     */
    private static void midWindowHitEntersCorpus() {
        resetFixtures();
        midWindow = true;
        responseSize = 2;
        TextCorpus corpus = new TextCorpus();
        DuplicateEngine.Report report = scan(roadmap(18), corpus, engines("openalex"), limits(12, 6));
        check(hits("/openalex") == 7, "六扇窗口各问一次，抄稿那一扇多问一次：被邻居盖住的那段单独补问");
        DuplicateEngine.WindowPlan plan = DuplicateEngine.windowPlan(TextSelection.all(roadmap(18)).text, 99);
        ArrayList<String> probes = DuplicateEngine.windowProbes(plan.members.get(2));
        check(probes.size() == 2, "抄稿那一扇压着两种主题，整窗一次之外那段自己再问一次");
        check(probes.get(1).contains("扩散层"), "补问那次的检索式出自被整窗检索式盖住的那一段");
        check(DuplicateEngine.windowProbes(plan.members.get(0)).size() == 1, "主题一致的那扇窗口照旧一次问完，不多花请求");
        check(report.candidates.size() == 1, "只有中段窗口问回来的那条真出处进了语料");
        check(report.candidates.get(0).source.title.contains("多孔铜"), "入库的那条就是被抄的那篇");
        check(corpusContains(corpus, MID_LINES[1]), "中段命中的真出处进了比对语料，不再整条被丢");
        check(report.comparableCandidates == 1, "可比候选只有一条，就是那句抄稿的来源");
        check(report.unrankedCandidates == 11, "十一条与任何检索式零共同词的套话仍被零分闸门拦在外面");
        check(notes(report, "11 条候选与检索词无任何共同词"), "被挡掉的条数照样写进注记");
        check(report.duplicateChars >= 60, "抄在中段的那一段被标成重复：" + report.duplicateChars + " 字");
        check(report.overallRate > 0d, "整篇总相似度比不再是零：" + report.overallRate + "%");
        String html = CheckReport.html("roadmap.docx", report);
        check(html.contains("零共同词被挡掉 11 篇"), "HTML 的覆盖率小节照样交代零分闸门挡掉了几篇");
        midWindow = false;
        responseSize = 12;
    }

    public static void main(String[] args) throws Exception {
        long savedGap = DuplicateEngine.engineGapMillis;
        long savedMillis = DuplicateEngine.searchMillis;
        try {
            DuplicateEngine.engineGapMillis = 0L;
            start();
            DuplicateEngine.Report wide = askedOncePerWindow();
            coverageDisclosure(wide);
            partialDisclosure();
            allocationRules();
            stoppedSourceHandsItsWindowsBack();
            singleWindowSetting();
            identicalWindowIsNotAskedTwice();
            requestCeiling();
            timeCeiling();
            rankedIntake();
            autoFullTextFetch();
            midWindowHitEntersCorpus();
            corpusCeiling();
            throttleCountsBySource();
            exhaustedSourceStopsAsking();
            sameSourceIsPaced();
            sourcesAskAtTheSameTime();
            slowSourceCannotStarveTheOthers();
            pacingHoldsAcrossLanes();
            cancelledAndPrivate(thesis(3).paragraphs.get(0).text);
        } finally {
            DuplicateEngine.engineGapMillis = savedGap;
            DuplicateEngine.searchMillis = savedMillis;
            if (server != null) server.stop(0);
            if (stubPool != null) stubPool.shutdownNow();
            PaperSources.resetEndpoints();
        }
        System.out.println("SUMMARY " + checks + " assertions passed; loopback-only network");
    }
}
