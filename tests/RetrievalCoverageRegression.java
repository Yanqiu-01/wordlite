package com.rikkahub.wordlite;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
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
            respond(exchange, 200, FULL_TEXT_XML);
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

    // ---- A 组：窗口数就是每个源被问的次数 ----

    private static DuplicateEngine.Report askedOncePerWindow() {
        resetFixtures();
        DuplicateEngine.Report report = scan(thesis(200), new TextCorpus(), nine(), limits(12, 12));
        String[] paths = {"/cnki", "/cqvip", "/wanfang", "/ncpssd", "/openalex", "/crossref",
                "/semantic-scholar", "/europepmc/search", "/arxiv"};
        for (String path : paths) {
            check(hits(path) == 12, label(path) + " 被问了 12 次：设置里有几个窗口就问几次");
        }

        check(hits("/cnki") == hits("/cqvip") && hits("/cqvip") == hits("/arxiv")
                        && hits("/arxiv") == hits("/europepmc/search"),
                "所有选中的源被问的次数一致，没有谁在半轮里被悄悄丢掉");
        check(report.windowsAvailable == 67, "200 段正文按三段一组切成 67 个检索窗口");
        check(report.windowsPlanned == 12, "本次计划检索的窗口数取自设置里的 12");
        check(report.windowsRetrieved == 12, "计划内的窗口全部发出了请求");
        for (String engine : NINE)
            check(asked(report, engine) == 12, engine + " 的提问次数记成 12，HTML 的「提问次数」列读的就是它");
        check(!report.retrievalIncomplete, "取回了候选的检索绝不许被标成什么都没查");
        check(report.retrievalPartial, "67 个窗口只跑了 12 个，这一轮必须自认部分是");
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
        check(report.windowsAvailable == 20 && report.windowsPlanned == 12, "六十段相同的正文切出 20 个窗口");
        check(requests(report) == 9, "同一条检索短语不会问第二次：十二个窗口只发了九个请求");
        check(hits("/cqvip") == 1 && hits("/cnki") == 1, "每个源只在第一个窗口被问过，剩下十一次是白送的");
        check(report.windowsRetrieved == 1, "只有真正发过请求的窗口才计入已检索窗口数");
        check(!notes(report, "已取尽"), "短语去重挡下来的窗口不该被算成该源已取尽");
        check(report.retrievalPartial, "被去重挡掉的窗口同样要承认这次只跑了一个窗口");
    }
    // ---- B 组：设置里的窗口数真的被读，闸门真的可达 ----

    private static void singleWindowSetting() {
        resetFixtures();
        DuplicateEngine.Report one = scan(thesis(200), new TextCorpus(), nine(), limits(12, 1));
        check(one.windowsPlanned == 1 && one.windowsRetrieved == 1, "设置成 1 个窗口就只跑 1 个窗口");
        check(hits("/cqvip") == 1, "1 个窗口的设置真的只发了 1 次维普请求");
        check(one.coveredChars == 120, "1 个窗口覆盖三段四十字，覆盖字数记 120 字");
    }

    private static void requestCeiling() {
        resetFixtures();
        check(DuplicateEngine.MAX_REQUESTS == 120, "整轮请求上限是 120 次");
        check(DuplicateEngine.MAX_CORPUS_PAPERS == 120, "入库总量上限是 120 篇");
        check(DuplicateEngine.MAX_SEARCH_MILLIS == 180000L, "整轮挂钟闸门是 180 秒");
        check(DuplicateEngine.MIN_ENGINE_GAP_MILLIS == 400L, "同一源两次提问之间至少隔 400 毫秒");
        check(new EngineSettings().windows == 6, "新装机的检索窗口数默认降到 6，不再把闸门当默认路径");
        check(new PaperSources.Limits().perEngine == 12, "perEngine 仍是每次要几条，默认 12");
        DuplicateEngine.Report capped = scan(thesis(200), new TextCorpus(), nine(), limits(12, 24));
        check(requests(capped) == DuplicateEngine.MAX_REQUESTS, "计划 216 次请求时正好撞在 120 这道闸上");
        check(notes(capped, "检索请求已达上限 120 次"), "撞顶必须在注记里留下字据，不许静默收工");
        check(capped.windowsPlanned == 24 && capped.windowsRetrieved < capped.windowsPlanned,
                "闸门截断的是尾部窗口，并且报告承认没跑完");
        check(capped.windowsRetrieved == 14, "第 14 个窗口只问得动前三个源，之后一道闸就落下来了");
        check(asked(capped, "cnki") == 14 && asked(capped, "arxiv") == 13,
                "被牺牲的是最后一个窗口的尾部源，不是某个源整轮被砍");
        int lowest = Integer.MAX_VALUE, highest = 0;
        for (String engine : NINE) {
            int times = asked(capped, engine);
            check(times >= 1, engine + " 没有整轮被砍：照样问到了 " + times + " 次");
            lowest = Math.min(lowest, times);
            highest = Math.max(highest, times);
        }
        check(highest - lowest <= 1, "撞总配额时各源提问次数相差不超过一次（实测 " + lowest + "-" + highest
                + "）：配额按泳道份额分，快泳道不许把慢泳道的份额花光");
        check(!capped.retrievalIncomplete, "取回了候选的截断轮次是部分完成，不是未完成");
        check(capped.retrievalPartial && capped.retrievalPartialReason.contains("相似率是下限"),
                "被闸门截断的比率必须自己声明是下限");
        check(notes(capped, capped.retrievalPartialReason), "面板上那行原因在注记里有同一句原文");
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
        check(slow.windowsRetrieved >= 2 && slow.windowsRetrieved < slow.windowsPlanned,
                "挂钟闸门确实在计划窗口跑完之前停了下来");
        check(slow.retrievalPartial && slow.retrievalPartialReason.contains("检索时间已用满"),
                "时间截断的部分完成原因指向时间闸门");
        check(!slow.retrievalIncomplete, "已经取回候选的时间截断轮次不许走未完成那一态");
    }

    // ---- C 组：报告与 HTML 里的覆盖率是真数字 ----

    private static void coverageDisclosure(DuplicateEngine.Report report) {
        check(report.windowsAvailable == 67 && report.windowsRetrieved == 12,
                "报告同时带着可切窗口数与实检窗口数两个数");
        check(report.comparableChars == 8000, "200 段四十字的可检索正文按 validCount 记 8000 字");
        check(report.coveredChars == 3 * 12 * 40, "12 个窗口覆盖 1440 字");
        check(report.coveredChars <= report.comparableChars, "覆盖字数不会超过可比对正文");
        check(notes(report, "已检索 12/67 个检索窗口"), "覆盖率注记写的是实检比可切");
        check(notes(report, "覆盖 1440 字（全文可比对 8000 字，18.00%）"), "覆盖率注记带字数与百分比");
        check(notes(report, "相似率是下限"), "部分完成必须写出下限措辞");
        check(notes(report, "可比材料只有摘要的有"), "报告要交代语料里有多少篇只有摘要可比");
        check(notes(report, "知网、万方、维普只回摘要"), "中文三库只有摘要可比这条结构性短板必须说出口");
        check(report.abstractOnlyCandidates + report.fullTextCandidates == report.comparableCandidates,
                "只有摘要与抓到全文的篇数加起来等于可比候选数");
        check(report.recordOnlyCandidates == 0, "只有题录的候选不计入可比候选");
        check(report.retrievalPartial && report.retrievalPartialReason.contains("把检索设置的窗口数调到 24"),
                "被设置挡住的情况要告诉用户怎么扩大覆盖");
        String html = CheckReport.html("thesis.docx", report);
        check(html.contains("检索覆盖率"), "HTML 里有覆盖率小节");
        check(html.contains("12/67"), "HTML 里写着实检比可切的窗口数");
        check(html.contains("仅摘要可比"), "HTML 里写着只有摘要可比的篇数");
        check(html.contains("相似率是下限"), "HTML 把部分覆盖的比率标成下限");
        check(html.contains("提问次数"), "来源表里有每个源被提问的次数");
        check(html.contains("可比材料"), "候选表里有可比材料这一列");
        check(html.contains("总相似度比") && html.contains("自编率"), "部分完成的报告照样给出三个比率");
        check(!html.contains("未完成查重"), "部分完成绝不允许走未完成那一态");
        check(report.notes.size() <= DuplicateEngine.MAX_NOTES, "注记条数仍在 MAX_NOTES 之内");
    }
    // ---- D 组：按名次入库，零分候选不进语料 ----

    private static void rankedIntake() {
        resetFixtures();
        boilerplate = true;
        crossrefSize = 1;
        responseSize = 12;
        PaperSources.Limits limits = limits(12, 1);
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
        check(report.windowsRetrieved == 4, "四扇窗口全部问出去");
        check(report.retrievalPartial && report.retrievalPartialReason.contains("按设置在"),
                "六十七扇窗口只计划了四扇，这一轮照样要自认部分是，原因指向设置里的窗口数");
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
        check(report.windowsRetrieved == 6, "限速之下六个窗口照样全跑完");
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
            singleWindowSetting();
            identicalWindowIsNotAskedTwice();
            requestCeiling();
            timeCeiling();
            rankedIntake();
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
