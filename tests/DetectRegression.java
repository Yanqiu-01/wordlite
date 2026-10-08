package com.rikkahub.wordlite;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Loopback fixtures only: engine parsing, transport guardrails, phrase building, full scan, report. */
public final class DetectRegression {
    private static int checks;
    private static final LinkedHashMap<String, String> TARGETS = new LinkedHashMap<String, String>();
    private static final LinkedHashMap<String, AtomicInteger> HITS = new LinkedHashMap<String, AtomicInteger>();
    private static volatile String lastApiKey = "";
    private static volatile String lastMethod = "", lastForm = "";
    private static String cqvipFixture = "", ncpssdFixture = "";
    /* 万方那一路收发的是 protobuf 字节，夹具也照字节存，文本通道一概不碰。 */
    private static byte[] wanfangFixture = new byte[0];
    private static byte[] lastBinaryBody = new byte[0];
    /** tests/fixture-cidcmap.pdf：一张 CID 字体自带的 cmap，真值是铜焊图陆那四个字。 */
    private static byte[] oaPdf = new byte[0];
    /** tests/fixture-gb1-gb1.pdf：只有 Adobe-GB1 的 CID，没有 ToUnicode 也没有内嵌字体，随包表才读得出。 */
    private static byte[] gb1Pdf = new byte[0];
    /** 上面那份夹具的真值（由 tests/make_cid_cmap_fixture.py 的构造决定）。 */
    private static final String GB1_TRUTH = "碳纳米管网络在180℃下大量自组装成一根，孔隙率随之上升。";
    private static String lastContentType = "";
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
        System.out.println("PASS " + message);
    }
    private static void record(String path, String target) {
        TARGETS.put(path, target);
        AtomicInteger counter = HITS.get(path);
        if (counter == null) { counter = new AtomicInteger(); HITS.put(path, counter); }
        counter.incrementAndGet();
    }
    private static int hits(String path) {
        AtomicInteger counter = HITS.get(path);
        return counter == null ? 0 : counter.intValue();
    }
    private static void respond(HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            OutputStream out = exchange.getResponseBody();
            out.write(bytes);
            out.close();
        } catch (Exception ignored) { }
        exchange.close();
    }
    private static String queryOf(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawPath();
        String query = exchange.getRequestURI().getRawQuery();
        return query == null ? raw : raw + "?" + query;
    }
    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }

    private static final String EMPTY_CROSSREF = "{\"message\":{\"items\":[],\"total-results\":0}}";
    /** 收敛式（带 language:zh）空手而归、宽式有货的那一家：量"补问一次宽式"这条分支用。 */
    private static final String EMPTY_OPENALEX = "{\"meta\":{\"count\":0},\"results\":[]}";
    private static final String OPENALEX = "{\"meta\":{\"count\":1},\"results\":[{\"id\":\"https://openalex.org/W301\","
            + "\"doi\":\"https://doi.org/10.1000/abc\",\"title\":\"Brazing of SiC ceramic with Ag-Cu-Ti filler\","
            + "\"authorships\":[{\"author\":{\"display_name\":\"L. Zhang\"}},{\"author\":{\"display_name\":\"M. Kumar\"}}],"
            + "\"publication_year\":2021,\"open_access\":{\"is_oa\":true,\"oa_url\":\"https://repo.example.org/1234/paper.pdf\"},"
            + "\"abstract_inverted_index\":{\"Microstructural\":[0],\"evolution\":[1],\"of\":[2],\"Ag-Cu-Ti\":[3],\"joints\":[4]}}]}";
    private static final String CROSSREF = "{\"status\":\"ok\",\"message\":{\"total-results\":1,\"items\":[{\"DOI\":\"10.5555/dxy\","
            + "\"title\":[\"Effect of brazing gap on joint strength\"],"
            + "\"author\":[{\"given\":\"Anna\",\"family\":\"Becker\"}],\"issued\":{\"date-parts\":[[2019]]},"
            + "\"abstract\":\"<jats:p>The <jats:italic>gap</jats:italic> influenced wetting.</jats:p>\","
            + "\"link\":[{\"URL\":\"https://publisher.example.org/dxy.pdf\",\"content-type\":\"application/pdf\"}],"
            + "\"resource\":{\"primary\":{\"URL\":\"https://doi.org/10.5555/dxy\"}},"
            + "\"open-access\":{\"is_oa\":true,\"content-version\":\"vor\"}}]}}";
    private static final String SEMANTIC = "{\"total\":1,\"offset\":0,\"data\":[{\"paperId\":\"abc123\","
            + "\"title\":\"Machine learning for brazing process optimization\",\"year\":2022,"
            + "\"abstract\":\"A gradient boosted model predicted lap shear strength.\","
            + "\"authors\":[{\"name\":\"R. Ito\"},{\"name\":\"S. Novak\"}],"
            + "\"externalIds\":{\"DOI\":\"10.48550/arXiv.2201.00001\",\"ArXiv\":\"2201.00001\"},"
            + "\"openAccessPdf\":{\"url\":\"https://arxiv.org/pdf/2201.00001\"}}]}";
    private static final String EUROPEPMC = "{\"hitCount\":1,\"resultList\":{\"result\":[{\"id\":\"12345678\",\"source\":\"MED\","
            + "\"pmid\":\"12345678\",\"pmcid\":\"PMC9876543\",\"doi\":\"10.1371/journal.pone.0123456\","
            + "\"title\":\"Open access study of brazed seals\",\"pubYear\":\"2020\",\"isOpenAccess\":\"Y\",\"hasTextMinedTerms\":\"Y\","
            + "\"abstractText\":\"Sealed joints were characterised by helium leak testing.\","
            + "\"authorList\":{\"author\":[{\"firstName\":\"K\",\"lastName\":\"Ortega\"}]},"
            + "\"fullTextUrlList\":{\"fullTextUrl\":[{\"availability\":\"free\",\"documentStyle\":\"html\","
            + "\"url\":\"https://academic.oup.com/free/html\"},"
            /* 2026-10-09 实测的真形状：开放获取的 PMC 行自己就给四条 fullTextUrl，其中 documentStyle=pdf/xml
               那两条才是正文所在，html 那条要么要追跳转要么 403。__BASE__ 在回话时换成回环地址，
               好让"全文只从回环取"这条断言继续成立。 */
            + "{\"availability\":\"free\",\"availabilityCode\":\"F\",\"documentStyle\":\"xml\","
            + "\"site\":\"Europe_PMC\",\"url\":\"__BASE__/europepmc/body\"}]}}]}}";
    private static final String ARXIV = "<?xml version='1.0' encoding='UTF-8'?>\n<feed xmlns=\"http://www.w3.org/2005/Atom\">\n"
            + "  <id>https://arxiv.org/api/query</id>\n  <title>arXiv Query: search_query=all:brazing</title>\n"
            + "  <entry>\n    <id>http://arxiv.org/abs/2103.11222v2</id>\n"
            + "    <updated>2021-04-02T09:00:00Z</updated>\n    <published>2021-03-25T09:00:00Z</published>\n"
            + "    <title>Phase field simulation of brazing filler flow &amp; wetting</title>\n"
            + "    <summary>Capillary flow of the filler is simulated, showing &lt;gap&gt; dependence of the joint.</summary>\n"
            + "    <link title=\"pdf\" href=\"https://arxiv.org/pdf/2103.11222v2\" rel=\"related\"/>\n"
            + "    <author><name>H. Tanaka</name></author>\n    <author><name>P. Silva</name></author>\n  </entry>\n</feed>\n";
    private static final String CORE = "{\"total\":1,\"results\":[{\"id\":555,\"title\":\"Repository copy: brazing of ceramics\","
            + "\"abstracts\":[\"Full text deposit of the ceramics brazing study.\"],"
            + "\"authors\":[{\"name\":\"Q. Zhao\"}],\"yearPublished\":\"2018\","
            + "\"identifiers\":{\"doi\":\"10.5281/zenodo.555\"},"
            + "\"links\":[{\"type\":\"pdf\",\"uri\":\"https://core.example.org/files/555.pdf\"}],"
            + "\"landingPageUri\":\"https://core.example.org/display/555\"}]}";
    /** 与 deferredNote 文档对题的一条 Crossref 记录：零分闸门之后，答空的第二个窗口要真回对题的料。 */
    private static final String CROSSREF_ON_TOPIC = "{\"status\":\"ok\",\"message\":{\"total-results\":1,\"items\":[{"
            + "\"DOI\":\"10.5555/tlp-joint\",\"title\":[\"TLP 互连接头剪切强度实测值的分布研究\"],"
            + "\"author\":[{\"given\":\"Li\",\"family\":\"Zhang\"}],\"issued\":{\"date-parts\":[[2022]]},"
            + "\"abstract\":\"TLP 互连接头剪切强度实测值随保温时间上升，窗口切分稳定可预期。\","
            + "\"link\":[{\"URL\":\"https://publisher.example.org/tlp.pdf\",\"content-type\":\"application/pdf\"}]}]}}";
    /** 知网空结果页：一条 <div class="list-item"> 都没有，解析器给空表。 */
    private static final String CNKI_EMPTY = "<html><body class=\"search-result\"></body></html>";
    private static final String FULLTEXT_XML = "<article><body><sec><title>Intro</title>"
            + "<p>Brazing gaps below 0.2 mm produced the strongest joints.</p></sec></body></article>";

    public static void main(String[] args) throws Exception {
        /* 每源 400ms 的间隔是给真机防封 IP 的，回归里全部压成 0；DuplicateEngine 的两个注入值
           在 finally 里还原，免得下一个套件读到脏字段。 */
        DuplicateEngine.engineGapMillis = 0L;
        cqvipFixture = fixture(args.length > 0 ? args[0] : "tests/samples/cqvip-search.html");
        ncpssdFixture = fixture(args.length > 1 ? args[1] : "tests/samples/ncpssd-search.json");
        wanfangFixture = binary(args.length > 2 ? args[2] : "tests/samples/wanfang-search.bin");
        /* 万方答"检索参数为空"时把 status 置 false，原因写在 message 里，这条路径得单独演一遍。 */
        java.io.ByteArrayOutputStream refused = new java.io.ByteArrayOutputStream();
        ProtoWire.writeTag(refused, 1, 0);
        ProtoWire.writeVarint(refused, 0);
        ProtoWire.writeString(refused, 2, "检索参数为空");
        final byte[] refusedFrame = grpcFrame(refused.toByteArray());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/openalex", exchange -> { record("/openalex", queryOf(exchange)); respond(exchange, 200, OPENALEX); });
        /* 收敛式空手、宽式有货：请求串里带 language%3Azh 就当收敛式，回空表。 */
        server.createContext("/openalex-converged-empty", exchange -> {
            record("/openalex-converged-empty", queryOf(exchange));
            String raw = exchange.getRequestURI().getRawQuery();
            respond(exchange, 200, raw != null && raw.contains("language%3Azh") ? EMPTY_OPENALEX : OPENALEX);
        });
        /* 收敛式自己就有货：这一路一次问完，一次请求都不许多花。 */
        server.createContext("/openalex-converged-hit", exchange -> {
            record("/openalex-converged-hit", queryOf(exchange));
            respond(exchange, 200, OPENALEX);
        });
        server.createContext("/crossref", exchange -> { record("/crossref", queryOf(exchange)); respond(exchange, 200, CROSSREF); });
        server.createContext("/semantic-scholar", exchange -> { record("/semantic-scholar", queryOf(exchange)); respond(exchange, 200, SEMANTIC); });
        server.createContext("/europepmc/search", exchange -> { record("/europepmc/search", queryOf(exchange));
            respond(exchange, 200, EUROPEPMC.replace("__BASE__", "http://" + exchange.getRequestHeaders().getFirst("Host"))); });
        server.createContext("/europepmc/", exchange -> { record("/europepmc/fulltext", queryOf(exchange)); respond(exchange, 200, FULLTEXT_XML); });
        server.createContext("/arxiv", exchange -> { record("/arxiv", queryOf(exchange)); respond(exchange, 200, ARXIV); });
        /* 期刊官网自己发 PDF 的那一路：字节进、正文出，全走回环，不碰真网。 */
        server.createContext("/oa/", exchange -> {
            record("/oa", queryOf(exchange));
            byte[] body = exchange.getRequestURI().getPath().endsWith("gb1.pdf") ? gb1Pdf : oaPdf;
            try {
                exchange.getResponseHeaders().set("Content-Type", "application/pdf");
                exchange.sendResponseHeaders(200, body.length);
                OutputStream out = exchange.getResponseBody();
                out.write(body);
                out.close();
            } catch (Exception ignored) { }
            exchange.close();
        });
        /* HTML 正文的四种形状：防爬壳页、壳页没脚本、真论文页、正文里恰好带验证字样的论文页。 */
        server.createContext("/shell/waf", exchange -> { record("/shell/waf", queryOf(exchange)); respond(exchange, 200, wafPage()); });
        server.createContext("/shell/hollow", exchange -> { record("/shell/hollow", queryOf(exchange)); respond(exchange, 200, hollowPage()); });
        server.createContext("/shell/article", exchange -> { record("/shell/article", queryOf(exchange)); respond(exchange, 200, articlePage()); });
        server.createContext("/shell/security", exchange -> { record("/shell/security", queryOf(exchange)); respond(exchange, 200, securityPage()); });
        server.createContext("/oa-big", exchange -> {
            record("/oa-big", queryOf(exchange));
            byte[] big = new byte[3000];
            java.util.Arrays.fill(big, (byte) 120);
            try {
                exchange.sendResponseHeaders(200, big.length);
                OutputStream out = exchange.getResponseBody();
                out.write(big);
                out.close();
            } catch (Exception ignored) { }
            exchange.close();
        });
        server.createContext("/core", exchange -> {
            record("/core", queryOf(exchange));
            lastApiKey = exchange.getRequestHeaders().getFirst("api-key");
            respond(exchange, 200, CORE);
        });
        server.createContext("/cqvip", exchange -> { record("/cqvip", queryOf(exchange)); respond(exchange, 200, cqvipFixture); });
        /* 知网一直没有桩，scan(engines = null) 于是真的打通 search.cnki.com.cn：这句"loopback-only"
           以前是假的。空结果页就够——回归要的是"这个源被问了几次"有出处，不是知网真回料。 */
        server.createContext("/cnki", exchange -> { record("/cnki", queryOf(exchange)); respond(exchange, 200, CNKI_EMPTY); });
        server.createContext("/sometimes", exchange -> {
            String asked = queryOf(exchange);
            record("/sometimes", asked);
            respond(exchange, 200, asked.contains("alpha-marker") ? EMPTY_CROSSREF : CROSSREF_ON_TOPIC);
        });
        server.createContext("/ncpssd", exchange -> {
            record("/ncpssd", queryOf(exchange));
            lastMethod = exchange.getRequestMethod();
            lastForm = body(exchange);
            respond(exchange, 200, ncpssdFixture);
        });
        server.createContext("/wanfang", exchange -> {
            record("/wanfang", queryOf(exchange));
            lastMethod = exchange.getRequestMethod();
            lastContentType = String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type"));
            java.io.ByteArrayOutputStream asked = new java.io.ByteArrayOutputStream();
            InputStream input = exchange.getRequestBody();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) > 0) asked.write(buffer, 0, count);
            lastBinaryBody = asked.toByteArray();
            exchange.getResponseHeaders().set("Content-Type", "application/grpc-web+proto");
            exchange.sendResponseHeaders(200, wanfangFixture.length);
            exchange.getResponseBody().write(wanfangFixture);
            exchange.close();
        });
        server.createContext("/wanfang-refused", exchange -> {
            record("/wanfang-refused", queryOf(exchange));
            readQuietly(exchange);
            exchange.sendResponseHeaders(200, refusedFrame.length);
            exchange.getResponseBody().write(refusedFrame);
            exchange.close();
        });
        server.createContext("/wanfang-truncated", exchange -> {
            record("/wanfang-truncated", queryOf(exchange));
            readQuietly(exchange);
            byte[] half = java.util.Arrays.copyOf(wanfangFixture, 40);
            exchange.sendResponseHeaders(200, half.length);
            exchange.getResponseBody().write(half);
            exchange.close();
        });
        server.createContext("/notfound", exchange -> { record("/notfound", queryOf(exchange)); respond(exchange, 404, "{\"error\":\"missing\"}"); });
        server.createContext("/error", exchange -> { record("/error", queryOf(exchange)); respond(exchange, 500, "{\"error\":\"boom\"}"); });
        server.createContext("/throttled", exchange -> { record("/throttled", queryOf(exchange)); respond(exchange, 429, "{\"error\":\"rate limit\"}"); });
        server.createContext("/throttled-once", exchange -> {
            record("/throttled-once", queryOf(exchange));
            boolean first = hits("/throttled-once") == 1;
            /* 会好的限流会告诉你什么时候回来；不告诉的就不值得闭着眼睛再打一次。 */
            if (first) exchange.getResponseHeaders().set("Retry-After", "1");
            respond(exchange, first ? 429 : 200, first ? "{\"error\":\"rate limit\"}" : OPENALEX);
        });
        server.createContext("/redirect", exchange -> {
            record("/redirect", queryOf(exchange));
            exchange.getResponseHeaders().set("Location", "https://never-contact.test/fallback");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/huge", exchange -> {
            record("/huge", queryOf(exchange));
            try {
                exchange.sendResponseHeaders(200, 0);
                OutputStream out = exchange.getResponseBody();
                byte[] block = new byte[8192];
                java.util.Arrays.fill(block, (byte) 'x');
                for (int i = 0; i < 40; i++) out.write(block);
                out.close();
            } catch (Exception ignored) { }
            exchange.close();
        });
        server.createContext("/slow", exchange -> {
            record("/slow", queryOf(exchange));
            readQuietly(exchange);
            sleep(2500);
            respond(exchange, 200, "{}");
        });
        oaPdf = binary("tests/fixture-cidcmap.pdf");
        gb1Pdf = binary("tests/fixture-gb1-gb1.pdf");
        // 随包的字符集表：app 里由 ApiWorkflow 从 assets 装载，这里按同一个入口从包内文件装。
        CidUnicodeTables.install(new java.io.FileInputStream("app/src/main/assets/cmaps/adobe-gb1.cid"));
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            engines(base);
            guardrails(base);
            viaProxy(base);
            domestic(base);
            windowing();
            deferredNote(base);
            phrase();
            marking();
            scan(base);
            unreachable(base);
            reports();
            unfinishedReport();
            cidTableBody(base);
            htmlBody(base);
        } finally {
            server.stop(0);
            PaperSources.resetEndpoints();
            DuplicateEngine.engineGapMillis = DuplicateEngine.MIN_ENGINE_GAP_MILLIS;
            DuplicateEngine.searchMillis = DuplicateEngine.MAX_SEARCH_MILLIS;
        }
        rewriteEffect();
        System.out.println("SUMMARY " + checks + " assertions passed; loopback-only network");
    }
    /** 实测的防爬壳页（opticsjournal.net，2026-10-09）：HTTP 200、15,999 字节，给人读的字只剩一行 TraceID。 */
    private static String repeat(String unit, int times) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++) out.append(unit);
        return out.toString();
    }
    private static String wafPage() {
        StringBuilder js = new StringBuilder();
        while (js.length() < 12000) js.append("var requestInfo={\"token\":\"18deab0f6e5f25a1e74073609ca429c7\",\"type\":\"GET\"};");
        return "<html><head><script>appkey:\"CF_APP_WAF\"," + js + "</script></head>"
                + "<body><div>TraceID: 781bad4817914855087858834ecb6c</div></body></html>";
    }
    /** 真论文页：正文两千字以上，页面里照样挂着统计脚本和样式，那些不该进比对基线。 */
    private static String articlePage() {
        StringBuilder prose = new StringBuilder();
        while (prose.length() < 2400) prose.append("瞬态液相连接层的等温凝固过程决定互连接头的服役温度上限。");
        StringBuilder js = new StringBuilder();
        while (js.length() < 6000) js.append("ga('send','pageview');SCRIPTONLY");
        return "<!DOCTYPE html><html><head><style>.doi{color:red}STYLEONLY</style><script>" + js
                + "</script></head><body><h1>多孔铜与瞬态液相连接</h1><p>" + prose
                + "</p><p>结论：连接层在 250 摄氏度时效一千小时后剪切强度保持率百分之八十六。</p></body></html>";
    }
    /** 正文里就带"人机验证""安全验证"字样的论文（讲认证的方案）：不许被防爬标记误杀。 */
    private static String securityPage() {
        return articlePage().replace("<h1>多孔铜", "<h1>移动端人机验证与安全验证方案：多孔铜");
    }
    /** 没有防爬标记的空壳页：一大页 JS，给人读的字不到两百个——按"内容与字节对不上"这条判。 */
    private static String hollowPage() {
        StringBuilder js = new StringBuilder();
        while (js.length() < 9000) js.append("window.__data=(function(a){return decode(a)})([1,2,3,4,5]);");
        StringBuilder nav = new StringBuilder();
        while (nav.length() < 180) nav.append("<span>首页 期刊 在线投稿 作者指南 联系我们</span>");
        return "<html><head><script>" + js + "</script></head><body><div>请开启 JavaScript 后重试。</div>"
                + nav + "</body></html>";
    }
    private static String bodyOf(String base, String path, PaperSources.Limits limits) {
        PaperSources.Candidate candidate = new PaperSources.Candidate();
        candidate.source.engine = "openalex";
        candidate.fullTextUrl = base + path;
        try { return PaperSources.fullText(candidate, limits, null); }
        catch (IOException error) { return "IOException: " + error.getMessage(); }
    }
    /** HTML 正文的形状：防爬壳页不许冒充正文，页面里的脚本样式也不许混进比对基线。 */
    private static void htmlBody(String base) {
        String stripped = PaperSources.Xml.readable("<p>正文在这里</p><styled-content>留着</styled-content>"
                + "<script>SCRIPTONLY var a=1;</script><style>STYLEONLY</style>");
        check(stripped.contains("正文在这里") && stripped.contains("留着")
                        && !stripped.contains("SCRIPTONLY") && !stripped.contains("STYLEONLY"),
                "HTML 正文只留给人读的字：script/style 连内容一起扔，<styled-content> 不会被当成 <style>");
        check(PaperSources.MIN_BODY_CHARS == 20 && PaperSources.BLOCKED_BODY_CHARS == 2000
                        && PaperSources.SHELL_BODY_CHARS == 4000 && PaperSources.SHELL_READABLE_CHARS == 1000
                        && PaperSources.challengeOf("<p>人机验证</p>", 2000) == null
                        && "blocked".equals(PaperSources.challengeOf("<p>人机验证</p>", 1999))
                        && "thin".equals(PaperSources.challengeOf("<html>" + repeat("x", 5000) + "</html>", 39))
                        && "thin".equals(PaperSources.challengeOf("<html>" + repeat("x", 5000) + "</html>", 1000))
                        && PaperSources.challengeOf("<html>" + repeat("x", 5000) + "</html>", 1001) == null
                        && "thin".equals(PaperSources.challengeOf("<html>请开启</html>", 19))
                        && PaperSources.challengeOf("<html>请开启 JavaScript 后重试</html>", 20) == null,
                "阈值的真值：可读字 20 下限、大页面只解出 ≤ 1000 字算空壳、带防爬标记的到 2000 为止");

        final ArrayList<PaperSources.ShapeRow> rows = new ArrayList<PaperSources.ShapeRow>();
        PaperSources.Limits limits = limits();
        limits.shapes = rows::add;
        String waf = bodyOf(base, "/shell/waf", limits);
        check(waf.isEmpty() && rows.size() == 1 && rows.get(0).shape.equals("text-blocked")
                        && rows.get(0).status == 200 && rows.get(0).entries == 0,
                "防爬挑战页不再冒充正文：HTTP 200 也判成 text-blocked（实测同一份壳页挂在三个不同文章链接上）");
        String hollow = bodyOf(base, "/shell/hollow", limits);
        check(hollow.isEmpty() && rows.size() == 2 && rows.get(1).shape.equals("text-thin")
                        && rows.get(1).bodyBytes > 8000,
                "没标记的空壳页也拦住：一大页 JS 只解出不到两百个可读字，判 text-thin");
        String article = bodyOf(base, "/shell/article", limits);
        check(article.contains("剪切强度保持率百分之八十六") && !article.contains("SCRIPTONLY")
                        && !article.contains("STYLEONLY") && rows.get(2).shape.equals("text-entries")
                        && rows.get(2).excerpt.endsWith("chars"),
                "真论文页照常收进正文，字数量的是拆掉脚本之后的数：" + rows.get(2).excerpt);
        String security = bodyOf(base, "/shell/security", limits);
        check(security.contains("人机验证") && rows.get(3).shape.equals("text-entries"),
                "正文里带人机验证字样的论文不被防爬标记误杀");
        check(hits("/shell/waf") == 1 && hits("/shell/article") == 1 && hits("/shell/hollow") == 1,
                "这四份全文各只发一次请求");
    }

    private static void readQuietly(HttpExchange exchange) {
        try { InputStream input = exchange.getRequestBody(); while (input.read() >= 0) { } }
        catch (IOException ignored) { }
    }

    private static String query(String path) {
        String value = TARGETS.get(path);
        return value == null ? "" : value;
    }
    private static int totalHits() {
        int out = 0;
        for (String key : HITS.keySet()) out += HITS.get(key).intValue();
        return out;
    }
    private static PaperSources.Limits limits() {
        PaperSources.Limits limits = new PaperSources.Limits();
        limits.perEngine = 5;
        limits.timeoutSeconds = 8;
        return limits;
    }
    /** The loopback fixtures stand in for every built-in connector. */
    private static void loopback(String base) {
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
    /**
     * 中文论文最常命中的两个库：维普的检索页是服务端渲染的 HTML（题名由脚本后填），哲社中心只接
     * POST 且检索式必须带字段码。夹具取自两家真实返回，防的是自己的解析器在页面改版后静默给出空结果。
     */
    private static void domestic(String base) throws Exception {
        loopback(base);
        PaperSources.Limits limits = limits();
        ArrayList<PaperSources.Candidate> vip = PaperSources.search("cqvip", "碳纤维增强铝基复合材料", limits, null);
        check(vip.size() == 2, "维普检索页的两条记录都被读出来");
        check(vip.get(0).source.engine.equals("cqvip")
                        && vip.get(0).source.locator.equals("https://www.cqvip.com/doc/journal/673069239"),
                "维普记录以文献页地址为标识");
        check(vip.get(0).source.title.equals("《机械与电子》 2017年第8期") && vip.get(0).source.year.equals("2017")
                        && vip.get(0).source.authors.contains("李克新"),
                "题名缺失时用刊名与年期署名，作者与年份照旧");
        check(vip.get(0).abstractText.contains("图像处理算法") && vip.get(0).abstractText.indexOf('<') < 0,
                "维普摘要去掉高亮标签后才是可比对文本");
        check(query("/cqvip").startsWith("/cqvip?k=") && !query("/cqvip").contains("page="),
                "维普只请求服务端会给的第一页");
        ArrayList<PaperSources.Candidate> nssd = PaperSources.search("ncpssd", "碳纤维 复合材料", limits, null);
        check(nssd.size() == 2 && nssd.get(0).source.title.equals("碳纤维及复合材料回收现状及其研究"),
                "哲社中心读 data.rows 的干净题名");
        check(nssd.get(0).source.authors.equals("李晓林, 黄海超, 李杨, 王宝铭, 郭庆山")
                        && nssd.get(0).source.year.equals("2020"),
                "机构序号从作者里去掉");
        check(nssd.get(1).source.authors.equals("李雪娇, 孙墨珑, 赵朋远"),
                "用空格而不是分号隔开的中文姓名也能拆开");
        check(nssd.get(0).source.locator.equals("ncpssd:7101188468"),
                "HtmlUrl 为 null 时退回记录号，不编造一个链接");
        check(!nssd.get(0).abstractText.contains("<font") && nssd.get(0).abstractText.contains("碳纤维复合材料"),
                "带高亮标签的 ik_* 字段不会进入比对文本");
        String form = java.net.URLDecoder.decode(lastForm, "UTF-8");
        check(lastMethod.equals("POST") && form.startsWith("pageNum=1&pageSize=5&sType=0&search=")
                        && form.contains("IKTE=\"") && form.contains("IKST=\"") && form.contains("碳纤维"),
                "哲社中心收到带字段码的 POST 检索式");
        check(!query("/ncpssd").contains("IKTE") && form.length() < 320,
                "检索式只装得下短语，正文不参与");
        /* 整句套一个引号等于要求这几个字在原文里连排出现，实测这个库回 0 条；
           逐词加引号再用 AND 连起来才有结果（"深度学习 图像分割"两个词是 6 条，整句是 0 条）。 */
        String multiQuery = java.net.URLDecoder.decode(PaperSources.formFor("深度学习 图像分割 综述", 5)
                .substring(31), "UTF-8");
        check(multiQuery.split(" AND ", -1).length == 3
                        && multiQuery.contains("(IKTE=\"图像分割\" OR IKST=\"图像分割\" OR IKRK=\"图像分割\")")
                        && !multiQuery.contains("\"深度学习 图像分割\""),
                "哲社中心的检索式逐词加引号再 AND，不整句一个引号");
        /* 万方：拿真实的 gRPC-web 抓包做夹具，防的是字段号漂移之后解析器静默交白卷。 */
        ArrayList<PaperSources.Candidate> wf = PaperSources.search("wanfang", "机器学习", limits, null);
        check(wf.size() == 3, "万方一帧里的三条记录都被读出来");
        check(wf.get(0).source.locator.equals("https://d.wanfangdata.com.cn/periodical/gdxxhxxb202602011"),
                "万方记录以文献页地址为标识");
        check(wf.get(0).source.title.contains("催化电子捐赠的机器学习描述符")
                        && wf.get(0).source.title.endsWith("《高等学校化学学报》")
                        && !wf.get(0).source.title.contains("的 机器学习"),
                "题名取中文那条并挂上出处，去高亮时不在中文词中间补空格");
        check(wf.get(0).source.year.equals("2026") && wf.get(0).source.authors.startsWith("赵迎, 杨海迪"),
                "年份与作者顺序照著录项来");
        check(wf.get(0).abstractText.contains("梯度提升回归") && !wf.get(0).abstractText.contains("span"),
                "摘要去掉标签之后才是可比对文本");
        check(lastMethod.equals("POST") && lastContentType.equals("application/grpc-web+proto"),
                "万方只收 gRPC-web 的 POST");
        int declared = ((lastBinaryBody[1] & 0xFF) << 24) | ((lastBinaryBody[2] & 0xFF) << 16)
                | ((lastBinaryBody[3] & 0xFF) << 8) | (lastBinaryBody[4] & 0xFF);
        check(lastBinaryBody.length == declared + 5, "发出去的是自洽的一帧，帧长与字节数一致");
        check(contains(lastBinaryBody, "机器学习".getBytes(StandardCharsets.UTF_8)) && lastBinaryBody.length < 96,
                "帧里只有检索短语，正文不参与");
        PaperSources.setEndpoint("wanfang", base + "/wanfang-refused");
        String refusal = "";
        try { PaperSources.search("wanfang", "机器学习", limits, null); }
        catch (IOException error) { refusal = String.valueOf(error.getMessage()); }
        check(refusal.contains("检索参数为空"), "万方答不通时把它的原话带进注记，不自己编一句没有重复");
        PaperSources.setEndpoint("wanfang", base + "/wanfang-truncated");
        boolean torn = false;
        try { PaperSources.search("wanfang", "机器学习", limits, null); } catch (IOException error) { torn = true; }
        check(torn, "帧长和实际字节数对不上时不静默给空结果");
        PaperSources.setEndpoint("wanfang", base + "/wanfang");
    }

    /** gRPC-web 帧：1 字节标志 + 4 字节大端长度 + 消息体，和 WanfangProtocol 写出去的一致。 */
    private static byte[] grpcFrame(byte[] message) {
        byte[] out = new byte[message.length + 5];
        out[1] = (byte) (message.length >>> 24);
        out[2] = (byte) (message.length >>> 16);
        out[3] = (byte) (message.length >>> 8);
        out[4] = (byte) message.length;
        System.arraycopy(message, 0, out, 5, message.length);
        return out;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int at = 0; at + needle.length <= haystack.length; at++) {
            for (int i = 0; i < needle.length; i++) if (haystack[at + i] != needle[i]) continue outer;
            return true;
        }
        return false;
    }

    private static byte[] binary(String path) {
        try {
            return java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
        } catch (IOException error) {
            throw new IllegalStateException("missing binary fixture " + path, error);
        }
    }

    private static String fixture(String path) {
        try {
            return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),
                    StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("missing fixture " + path, error);
        }
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

    private static void windowing() {
        ArrayList<String> picked = DuplicateEngine.windows("本科毕业论文（设计）\n题目：多孔铜\n\n"
                + "本研究以多孔铜为中间层，TLP 过程中孔道内 Si 与母材反应生成硅化物，接头剪切强度提高到 187 MPa。\n"
                + "目录\n2.2.1  SiC高温封装与TLP互连技术4\n图 1 接头形貌 12\n");
        check(picked.size() == 1 && picked.get(0).contains("多孔铜为中间层") && !picked.get(0).contains("SiC高温封装"),
                "封面行与带页码的目录行不参与检索窗口");
    }

    /** "未命中" is a verdict about the whole run, not about one window of it. */
    private static void deferredNote(String base) {
        PaperSources.setEndpoint("crossref", base + "/sometimes");
        DocxDocument document = new DocxDocument();
        /* 六段正文共用一个题内短语：0.6.0 起与检索词零共同词的候选不再进语料，
           这个夹具要验的是"先空后中"，不是零分闸门，所以文档得真的与候选对题。 */
        add(document, paragraph(0, "alpha-marker 这一段先送去检索，TLP 互连接头剪切强度实测值第一次希望答空。", "", ""));
        add(document, paragraph(1, "中段内容用来把窗口撑满，TLP 互连接头剪切强度实测值使第一段单独成窗口。", "", ""));
        add(document, paragraph(2, "这一段同样只是正文，TLP 互连接头剪切强度实测值只保证窗口切分稳定。", "", ""));
        add(document, paragraph(3, "beta-marker 这一段再送去检索，TLP 互连接头剪切强度实测值这次应当给出候选。", "", ""));
        add(document, paragraph(4, "再补一段正文，TLP 互连接头剪切强度实测值让第二个窗口稳定成型。", "", ""));
        add(document, paragraph(5, "最后一段正文，TLP 互连接头剪切强度实测值到这里就覆盖两个窗口的差别。", "", ""));
        ArrayList<String> engines = new ArrayList<String>();
        engines.add("crossref");
        DuplicateEngine.Report report = DuplicateEngine.scan(TextSelection.all(document), new TextCorpus(),
                true, engines, limits(), new ApiClient.Task(), null);
        check(report.candidateCount.get("crossref") != null && report.candidateCount.get("crossref") > 0,
                "第二个窗口的检索结果照样进候选池");
        check(hits("/sometimes") >= 2, "这个源被问了两次：第一个窗口确实答了空");
        check(!String.valueOf(report.notes).contains("未命中相关文献"),
                "先空后中的检索源不再被报成未命中");
        PaperSources.setEndpoint("crossref", base + "/crossref");
    }

    private static void everyEndpointAt(String url) {
        ArrayList<String> all = PaperSources.engines();
        for (String engine : all) PaperSources.setEndpoint(engine, url);
    }

    private static void engines(String base) throws Exception {
        ArrayList<String> engines = PaperSources.engines();
        check(engines.size() == 10 && engines.contains("openalex") && engines.contains("semantic-scholar")
                        && engines.contains("europepmc") && engines.contains("core")
                        && engines.contains("cnki") && engines.contains("cqvip")
                        && engines.contains("wanfang") && engines.contains("ncpssd")
                        && engines.subList(0, 4).containsAll(
                                java.util.Arrays.asList("cnki", "cqvip", "wanfang", "ncpssd")),
                "ten built-in engines are registered, the four Chinese ones first");
        /* 万方从一开始就漏在默认名单之外，新装机于是天生少一个中文库；知网接进来时的版本迁移
           要把这两个一起补上，但不能把用户自己勾掉的源偷偷打开。 */
        EngineSettings fresh = new EngineSettings();
        check(fresh.engines.contains("cnki") && fresh.engines.contains("wanfang")
                        && fresh.engines.contains("cqvip") && !fresh.engines.contains("core"),
                "a fresh install checks CNKI, Wanfang and VIP without waiting for a key");
        EngineSettings upgraded = EngineSettings.deserialize("{\"version\":2,\"web\":true,"
                + "\"engines\":[\"cqvip\",\"openalex\"],\"perEngine\":12,\"timeout\":20,\"windows\":12,"
                + "\"coreKey\":\"\",\"proxy\":\"\"}");
        check(upgraded.engines.contains("cnki") && upgraded.engines.contains("wanfang")
                        && upgraded.engines.contains("cqvip") && !upgraded.engines.contains("semantic-scholar"),
                "the version 3 upgrade adds CNKI and Wanfang without re-enabling what the user unchecked");
        loopback(base);
        PaperSources.Limits limits = limits();

        PaperSources.Candidate openalex = PaperSources.search("openalex", "brazing temperature", limits, null).get(0);
        check(openalex.source.engine.equals("openalex")
                && openalex.source.title.equals("Brazing of SiC ceramic with Ag-Cu-Ti filler")
                && openalex.source.year.equals("2021") && openalex.source.locator.equals("https://doi.org/10.1000/abc"),
                "OpenAlex yields engine, title, year and DOI locator");
        check(openalex.abstractText.equals("Microstructural evolution of Ag-Cu-Ti joints"),
                "OpenAlex abstract_inverted_index is rebuilt in reading order");
        check(openalex.source.authors.contains("L. Zhang")
                && openalex.fullTextUrl.equals("https://repo.example.org/1234/paper.pdf"),
                "OpenAlex authors and open access link parsed");
        check(query("/openalex").contains("title_and_abstract.search%3Abrazing+temperature") && query("/openalex").contains("per-page=5"),
                "OpenAlex query carries the phrase inside its search filter only");
        String many = "Microstructural evolution of Ag-Cu-Ti joints brazed SiC ceramics under thermal cycling";
        check(PaperSources.narrowing(many, 5, 60).equals("Microstructural evolution of Ag-Cu-Ti joints"),
                "an OpenAlex phrase keeps its first five words because every word is a required term");
        check(PaperSources.narrowing("brazing temperature", 5, 60).equals("brazing temperature"),
                "a short phrase reaches OpenAlex untouched");
        String cjk = "为了满足上述要求瞬态液相连接技术被广泛研究并应用于第三代半导体封装领域并且不断迭代"
                + "优化工艺流程与可靠性评估体系以及多孔铜中间层的制备参数控制与微观组织演变规律分析";
        String cjkQuery = PaperSources.narrowing(cjk, 5, 60);
        check(cjkQuery.length() == 60 && cjk.startsWith(cjkQuery),
                "an unspaced Chinese phrase is cut to the character budget instead of sent whole");
        check(PaperSources.narrowing("   ", 5, 60).isEmpty(), "a blank phrase narrows to nothing");

        PaperSources.Candidate crossref = PaperSources.search("crossref", "brazing temperature", limits, null).get(0);
        check(crossref.source.engine.equals("crossref") && crossref.source.title.equals("Effect of brazing gap on joint strength")
                && crossref.source.locator.equals("10.5555/dxy") && crossref.source.year.equals("2019"),
                "Crossref bibliographic envelope parsed from message.items");
        check(crossref.abstractText.equals("The gap influenced wetting."), "Crossref JATS abstract loses its tags");
        check(crossref.source.authors.equals("Anna Becker") && crossref.fullTextUrl.equals("https://publisher.example.org/dxy.pdf"),
                "Crossref author name and OA pdf link parsed");
        check(query("/crossref").startsWith("/crossref?query.bibliographic=") && query("/crossref").contains("rows=5"),
                "Crossref query uses query.bibliographic with a row cap");

        PaperSources.Candidate semantic = PaperSources.search("semantic-scholar", "brazing temperature", limits, null).get(0);
        check(semantic.source.engine.equals("semantic-scholar") && semantic.source.year.equals("2022")
                && semantic.source.locator.equals("10.48550/arXiv.2201.00001"), "Semantic Scholar yields engine, year and DOI locator");
        check(semantic.abstractText.equals("A gradient boosted model predicted lap shear strength."), "Semantic Scholar abstract parsed");
        check(semantic.fullTextUrl.equals("https://arxiv.org/pdf/2201.00001") && semantic.source.authors.equals("R. Ito, S. Novak"),
                "Semantic Scholar openAccessPdf becomes the full text url");
        check(query("/semantic-scholar").contains("fields=title,abstract,year,authors,externalIds,openAccessPdf")
                && query("/semantic-scholar").contains("limit=5"), "Semantic Scholar asks for the contracted fields only");

        PaperSources.Candidate europe = PaperSources.search("europepmc", "brazing temperature", limits, null).get(0);
        check(europe.source.engine.equals("europepmc") && europe.source.locator.equals("10.1371/journal.pone.0123456")
                && europe.source.year.equals("2020") && europe.source.title.equals("Open access study of brazed seals"),
                "Europe PMC core result parsed with DOI locator");
        check(europe.fullTextUrl.equals(base + "/europepmc/body"),
                "Europe PMC 的全文链接取源自己在 fullTextUrlList 里给的那条（documentStyle=xml），"
                        + "不是拿 pmcid 拼的 MED/<pmcid>/fullTextXML——那条口实测六次全 404");
        check(query("/europepmc/search").contains("resultType=core") && query("/europepmc/search").contains("pageSize=5"),
                "Europe PMC asks for core results with a page size");
        int beforeFull = totalHits();
        String fullText = PaperSources.fullText(europe, limits, null);
        check(fullText.contains("Brazing gaps below 0.2 mm produced the strongest joints.") && fullText.indexOf('<') < 0,
                "Europe PMC fullTextXML is reduced to plain comparison text");
        check(totalHits() == beforeFull + 1 && query("/europepmc/fulltext").startsWith("/europepmc/body"),
                "full text is fetched once and only from loopback");
        /* .pdf 链接以前是被直接丢掉的；现在它必须真下回来、用 app 自己的 PDF 解析出正文。 */
        PaperSources.Candidate pdf = new PaperSources.Candidate();
        pdf.source.engine = "openalex";
        pdf.fullTextUrl = base + "/oa/zidong.pdf";
        int beforePdf = totalHits();
        String pdfText = PaperSources.fullText(pdf, limits, null);
        check(PaperSources.pdfLink("https://repo.example.org/1234/paper.pdf")
                        && PaperSources.pdfLink("http://www.cjmenet.com.cn/CN/article/downloadArticleFile.do"
                                + "?attachType=PDF&id=27657")
                        && !PaperSources.pdfLink(base + "/europepmc/search"),
                "pdf 链接认 .pdf 结尾，也认玛格泰克那种 attachType=PDF 的下载口");
        check(pdfText.contains("\u94dc\u710a\u5b54\u9699") && totalHits() == beforePdf + 1
                        && hits("/oa") == 1,
                "pdf 直链下载一次并解析出内嵌字体 cmap 里的四个字");
        check(PaperSources.hostOf("http://www.cjmenet.com.cn:8080/CN/x.do?a=1").equals("www.cjmenet.com.cn")
                        && PaperSources.hostOf("not-a-url").isEmpty(),
                "主机名解析取端口与查询串之前的部分，坏链接回空");
        check(PaperSources.pdfUrlRank("http://www.cjmenet.com.cn/CN/x.do") == 0
                        && PaperSources.pdfUrlRank("https://random-host.example.org/a.pdf") == 1
                        && PaperSources.pdfUrlRank("http://random-host.example.org/a.pdf") == 2
                        && PaperSources.pdfUrlRank("https://doi.org/10.1000/abc") == 3
                        && PaperSources.pdfUrlRank("") == 9,
                "白名单期刊官网 > 其它 https > 其它 http > doi.org 跳转壳");
        check(PaperSources.plainHttpAllowed("http://www.cjmenet.com.cn/CN/x.do")
                        && !PaperSources.plainHttpAllowed("http://evil.example.org/a.pdf")
                        && !PaperSources.plainHttpAllowed("http://127.0.0.1:1/a.pdf"),
                "只有白名单期刊官网的 PDF 允许走 http，其余一律 HTTPS");
        check(PaperSources.betterFullTextUrl("https://doi.org/10.1000/abc",
                        "https://journal.example.org/downloadArticleFile.do?attachType=PDF&id=7").contains("attachType")
                        && PaperSources.betterFullTextUrl("", "https://doi.org/10.1000/abc")
                                .equals("https://doi.org/10.1000/abc"),
                "doi.org 跳转壳排到最后，能直接下到文件的链接先花额度");
        String oaQuery = PaperSources.queryFor("openalex", "\u591a\u5b54\u94dc\u7684\u5236\u5907\u4e0e\u7ed3\u6784\u8c03\u63a7\u53ca\u5176\u77ac\u6001\u6db2\u76f8\u8fde\u63a5\u884c\u4e3a", 50);
        check(oaQuery.contains("open_access.is_oa") && oaQuery.contains("mailto=")
                        && PaperSources.openAlexTerm("multimodal copper brazing of porous copper").split(" ").length >= 2,
                "开放检索只问 OA 条目并带落款；拉丁文仍问满五个词");
        check(PaperSources.openAlexTerm("\u591a\u5b54\u94dc\u7684\u5236\u5907\u4e0e\u7ed3\u6784\u8c03\u63a7").length() <= 16,
                "中文检索式砍到 16 字以内：OpenAlex 把每个词都当必须命中，长串一命中就是 0 条");
        /* 中文稿的 OpenAlex 检索式必须收敛到"更可能带正文"的那一批；拉丁文稿一个字都不许多加。
           收敛条件与它值多少的实测账，写在 PaperSources.OPENALEX_BODY_FILTERS 上面那段。 */
        String zhFilter = PaperSources.openAlexFilter("\u591a\u5b54\u94dc\u7684\u5236\u5907\u4e0e\u7ed3\u6784\u8c03\u63a7");
        check(zhFilter.contains("open_access.is_oa:true") && zhFilter.contains("language:zh")
                        && zhFilter.contains("primary_location.source.has_issn:true"),
                "中文稿的 OpenAlex 式子收敛到中文条目 + 有 ISSN 的正式出版物：" + zhFilter);
        check(PaperSources.openAlexFilter("porous copper transient liquid phase bonding")
                        .equals(PaperSources.openAlexBroadFilter("porous copper transient liquid phase bonding"))
                        && !PaperSources.openAlexFilter("porous copper bonding").contains("language:zh"),
                "拉丁文稿不收敛：走的还是 2.3.0 那条只要求开放获取的式子");
        check(PaperSources.openAlexBroadFilter("\u591a\u5b54\u94dc").endsWith(",open_access.is_oa:true")
                        && !PaperSources.openAlexBroadFilter("\u591a\u5b54\u94dc").contains("language"),
                "补问那一路的宽式与出厂那条一模一样，没被收敛条件污染");

        /* 收敛式空手 → 补问一次宽式，并把宽式那一条拿回来；两次提问各落一行留档。 */
        PaperSources.setEndpoint("openalex", base + "/openalex-converged-empty");
        final ArrayList<PaperSources.ShapeRow> tierRows = new ArrayList<PaperSources.ShapeRow>();
        PaperSources.Limits tierLimits = limits();
        tierLimits.shapes = tierRows::add;
        int beforeEmpty = hits("/openalex-converged-empty");
        ArrayList<PaperSources.Candidate> rescued = PaperSources.search("openalex",
                "\u591a\u5b54\u94dc\u7684\u5236\u5907\u4e0e\u7ed3\u6784\u8c03\u63a7", tierLimits, null);
        check(hits("/openalex-converged-empty") == beforeEmpty + 2 && rescued.size() == 1
                        && rescued.get(0).source.title.contains("Brazing"),
                "收敛式一条都没回来 → 补问一次宽式并把那一条拿回来（这一扇共两次提问）");
        check(tierRows.size() == 2 && !tierRows.get(0).probe.contains(PaperSources.OPENALEX_BROAD_NOTE)
                        && tierRows.get(1).probe.endsWith(PaperSources.OPENALEX_BROAD_NOTE)
                        && tierRows.get(1).entries == 1,
                "两次提问各留一行留档，第二行标着宽式补问：" + tierRows.size() + " 行");

        /* 收敛式自己就有货 → 一次问完。多花一次提问就等于多占一轮的提问配额。 */
        PaperSources.setEndpoint("openalex", base + "/openalex-converged-hit");
        int beforeHit = hits("/openalex-converged-hit");
        PaperSources.search("openalex", "\u591a\u5b54\u94dc\u7684\u5236\u5907\u4e0e\u7ed3\u6784\u8c03\u63a7", limits(), null);
        check(hits("/openalex-converged-hit") == beforeHit + 1, "收敛式有货就不补问：一扇窗口只花一次提问");
        loopback(base);
        HttpTransport.Fetched part = HttpTransport.getPdf(base + "/oa-big", null, 20, 1024, null, null, false);
        check(part.bytes.length == 1024 && part.capped && part.status == 200,
                "PDF 读到上限就收工并把 capped 标出来，不再当成\u201c响应过大\u201d整份丢掉");
        boolean wholeRefused = false;
        try { HttpTransport.get(base + "/oa-big", null, 20, 1024, null, null); }
        catch (ApiClient.Failure error) { wholeRefused = error.getMessage().equals("\u54cd\u5e94\u8fc7\u5927"); }
        check(wholeRefused, "\u666e\u901a\u68c0\u7d22\u54cd\u5e94\u8d85\u8fc7\u4e0a\u9650\u7167\u65e7\u76f4\u63a5\u62a5\u9519\uff0c\u4e0d\u628a\u534a\u4efd JSON \u5f53\u6b63\u6587\u89e3");

        PaperSources.Candidate arxiv = PaperSources.search("arxiv", "brazing temperature", limits, null).get(0);
        check(arxiv.source.engine.equals("arxiv") && arxiv.source.locator.equals("http://arxiv.org/abs/2103.11222v2")
                && arxiv.source.year.equals("2021"), "arXiv Atom entry parsed by the tag scanner");
        check(arxiv.source.title.equals("Phase field simulation of brazing filler flow & wetting"), "arXiv title entities decoded");
        check(arxiv.abstractText.contains("<gap> dependence") && arxiv.source.authors.equals("H. Tanaka, P. Silva"),
                "arXiv summary entities decoded and authors joined");
        check(arxiv.fullTextUrl.equals("https://arxiv.org/pdf/2103.11222v2"), "arXiv pdf link read from link attributes");
        check(query("/arxiv").contains("search_query=all:brazing+temperature") && query("/arxiv").contains("max_results=5"),
                "arXiv query sends the terms with a result cap");

        boolean rejected = false;
        String coreError = "";
        int beforeCore = hits("/core");
        try { PaperSources.search("core", "brazing temperature", limits, null); }
        catch (IllegalArgumentException error) { rejected = true; coreError = String.valueOf(error.getMessage()); }
        check(rejected && coreError.equals("CORE 未配置 API Key"), "CORE refuses to query without an API key");
        check(hits("/core") == beforeCore, "keyless CORE never reaches the network");
        limits.coreKey = "core-secret-key";
        PaperSources.Candidate core = PaperSources.search("core", "brazing temperature", limits, null).get(0);
        check(core.source.engine.equals("core") && core.source.locator.equals("10.5281/zenodo.555") && core.source.year.equals("2018"),
                "CORE works payload parsed with DOI locator");
        check(core.abstractText.equals("Full text deposit of the ceramics brazing study.")
                && core.fullTextUrl.equals("https://core.example.org/files/555.pdf"), "CORE abstract list and pdf link parsed");
        check("core-secret-key".equals(lastApiKey), "CORE sends its key in the api-key header");
        check(!query("/core").contains("core-secret-key"), "CORE key never travels in the query string");
        rejected = false;
        try { PaperSources.search("Elsevier", "brazing", limits, null); } catch (IllegalArgumentException error) { rejected = true; }
        check(rejected, "unknown engine rejected before any request");
        rejected = false;
        try { PaperSources.search("openalex", "   ", limits, null); } catch (IllegalArgumentException error) { rejected = true; }
        check(rejected, "blank retrieval phrase rejected before any request");
        limits.coreKey = "";
    }

    /**
     * A network that resets these hosts leaves the user no way out but their own proxy, so the
     * transport has to hand it the absolute URL a proxy routes on and read the answer back. The stub
     * proxy is the only witness of that request line, and the fixture server is the witness that a
     * proxied pass does not also dial out on its own.
     */
    private static void viaProxy(String base) throws Exception {
        loopback(base);
        final ArrayList<String> requestLines = new ArrayList<String>();
        final ServerSocket proxy = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        Thread acceptor = new Thread(() -> {
            try (java.net.Socket socket = proxy.accept()) {
                socket.setSoTimeout(5000);
                ByteArrayOutputStream head = new ByteArrayOutputStream();
                int read = -1;
                while ((read = socket.getInputStream().read()) >= 0) {
                    head.write(read);
                    if (read == '\n' && new String(head.toByteArray(), StandardCharsets.UTF_8).endsWith("\r\n\r\n"))
                        break;
                }
                requestLines.add(new String(head.toByteArray(), StandardCharsets.UTF_8).split("\\r?\\n")[0]);
                byte[] body = OPENALEX.getBytes(StandardCharsets.UTF_8);
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                        + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
            } catch (IOException ignored) { }
        });
        acceptor.start();
        PaperSources.Limits proxied = limits();
        proxied.proxy = "127.0.0.1:" + proxy.getLocalPort();
        int before = hits("/openalex");
        PaperSources.Candidate through = PaperSources.search("openalex", "brazing temperature", proxied, null).get(0);
        acceptor.join(5000);
        proxy.close();
        check(through.source.title.equals("Brazing of SiC ceramic with Ag-Cu-Ti filler")
                        && hits("/openalex") == before,
                "a proxied retrieval is answered by the proxy instead of reaching the source twice");
        String routed = requestLines.isEmpty() ? "<none>" : requestLines.get(0);
        check(requestLines.size() == 1 && routed.startsWith("GET " + base + "/openalex?") && routed.contains("brazing+temperature"),
                "the proxy is given the absolute URL it needs to route on: " + routed);
        /* 手机上的代理是 adb reverse 出来的临时端口，拔线之后就没人听了：这时候该退回直连，
           而不是把一次好好的查重判成失败。 */
        PaperSources.Limits dead = limits();
        dead.proxy = "127.0.0.1:1";
        int beforeDead = hits("/openalex");
        check(PaperSources.search("openalex", "brazing temperature", dead, null).size() == 1
                        && hits("/openalex") == beforeDead + 1,
                "代理没人应答时退回直连，检索不因此失败");
        PaperSources.Limits portless = limits();
        portless.proxy = "127.0.0.1";
        int beforeDirect = hits("/openalex");
        check(PaperSources.search("openalex", "brazing temperature", portless, null).size() == 1
                        && hits("/openalex") == beforeDirect + 1,
                "a proxy without a port dials out directly instead of failing the pass");
        EngineSettings via = new EngineSettings();
        via.proxy = "127.0.0.1:7890";
        via.validate();
        check(EngineSettings.deserialize(EngineSettings.serialize(via)).proxy.equals("127.0.0.1:7890"),
                "the proxy survives the settings codec");
        boolean refused = false;
        try { EngineSettings junk = new EngineSettings(); junk.proxy = "http://127.0.0.1:7890/"; junk.validate(); }
        catch (IllegalArgumentException error) { refused = true; }
        check(refused, "a proxy written as a URL is refused rather than guessed at");
        EngineSettings upgraded = EngineSettings.deserialize(
                "{\"version\":1,\"web\":true,\"engines\":[\"openalex\",\"crossref\"],\"perEngine\":12,\"timeout\":20,\"windows\":12}");
        check(upgraded.engines.contains("openalex") && upgraded.engines.contains("cqvip")
                        && upgraded.engines.contains("ncpssd"),
                "旧设置里没见过的中文检索源按新装一起打开");
        upgraded.engines.remove("cqvip");
        EngineSettings trimmed = EngineSettings.deserialize(EngineSettings.serialize(upgraded));
        check(!trimmed.engines.contains("cqvip"), "2 号格式里勾掉的源不会被重新打开");
    }

    private static ApiClient.Failure fail(String url, java.util.Map<String, String> headers, int seconds, int maxBytes,
                                          ApiClient.Cancellation cancellation) {
        try { HttpTransport.get(url, headers, seconds, maxBytes, cancellation); }
        catch (ApiClient.Failure error) { return error; }
        catch (IOException error) { return new ApiClient.Failure("wrong type: " + error.getMessage(), -1); }
        return null;
    }
    private static void guardrails(String base) {
        String marker = "SECRETQUERY123";
        ApiClient.Failure failure = fail(base + "/notfound?q=" + marker, null, 8, 0, null);
        check(failure != null && failure.status == 404 && failure.getMessage().equals("检索源返回 HTTP 404"),
                "404 surfaces as ApiClient.Failure carrying the status");
        check(failure != null && !failure.getMessage().contains(marker), "404 message carries no query string");
        failure = fail(base + "/error?q=" + marker, null, 8, 0, null);
        check(failure != null && failure.status == 500 && !failure.getMessage().contains(marker),
                "500 fails concisely without echoing the request");
        check(failure != null && failure.getMessage().equals("检索源暂时不可用（HTTP 500）"),
                "a server-side failure says the source is unavailable");
        failure = fail(base + "/throttled?q=" + marker, null, 8, 0, null);
        check(failure != null && failure.status == 429 && !failure.getMessage().contains(marker)
                        && failure.getMessage().equals("检索源限流（HTTP 429），本次跳过"),
                "429 says the source throttled us rather than blaming the network");
        /* Semantic Scholar 的匿名共享配额实测五次有五次不回 Retry-After，七百毫秒后再打一次
           必然还是 429：对方没说什么时候回来，就一次都不多打，把时间留给别的源。 */
        check(hits("/throttled") == 1, "没给 Retry-After 的 429 不做无把握的重试");
        ApiClient.Response recovered = null;
        try {
            recovered = HttpTransport.get(base + "/throttled-once?q=" + marker, null, 8, 0, null);
        } catch (IOException ignored) { }
        check(recovered != null && recovered.attempts == 2 && hits("/throttled-once") == 2
                        && recovered.body.contains("Brazing of SiC ceramic"),
                "报了 Retry-After 的限流会等它说的这么久，再打一次，然后拿到结果");
        failure = fail(base + "/redirect?q=" + marker, null, 8, 0, null);
        check(failure != null && failure.status == 302 && failure.getMessage().equals("检索源发生重定向"),
                "302 is reported instead of being followed");
        check(hits("/redirect") == 1, "redirect answered once with no second hop");
        failure = fail(base + "/huge?q=" + marker, null, 8, 8192, null);
        check(failure != null && failure.getMessage().equals("响应过大"), "oversized body is cut off at the configured cap");
        check(failure != null && !failure.getMessage().contains(marker), "oversize error carries no query string");
        failure = fail(base + "/slow?q=" + marker, null, 1, 0, null);
        check(failure != null && failure.getMessage().equals("请求超时"), "read timeout becomes 请求超时");
        check(failure != null && !failure.getMessage().contains(marker), "timeout error carries no query string");
        int before = totalHits();
        ApiClient.Task pre = new ApiClient.Task();
        pre.cancel();
        failure = fail(base + "/openalex?q=" + marker, null, 8, 0, pre);
        check(failure != null && failure.getMessage().equals("已取消") && totalHits() == before,
                "a pre-cancelled task never issues the request");
        final ApiClient.Task flight = new ApiClient.Task();
        Thread killer = new Thread(() -> { sleep(250); flight.cancel(); });
        killer.start();
        long started = System.currentTimeMillis();
        failure = fail(base + "/slow?q=" + marker, null, 20, 0, flight);
        long elapsed = System.currentTimeMillis() - started;
        try { killer.join(); } catch (InterruptedException ignored) { }
        check(failure != null && failure.getMessage().equals("已取消"), "cancelling mid-flight disconnects and reports 已取消");
        check(elapsed < 2000, "cancellation aborts the wait instead of sitting out the timeout");
        failure = fail("http://api.openalex.org/works?q=" + marker, null, 8, 0, null);
        check(failure != null && failure.getMessage().equals("检索源必须使用 HTTPS"), "plain http refused for remote hosts");
        failure = fail("not a url", null, 8, 0, null);
        check(failure != null && failure.getMessage().equals("检索地址无效"), "malformed address fails without leaking input");
        java.util.Map<String, String> poisoned = new LinkedHashMap<String, String>();
        poisoned.put("X-Bad", "value\r\nAuthorization: forged");
        failure = fail(base + "/openalex?q=" + marker, poisoned, 8, 0, null);
        check(failure != null && failure.getMessage().equals("请求头无效"), "header injection rejected before sending");
        failure = fail("http://example.org/x?q=" + marker, null, 8, 0, null);
        check(failure != null && failure.getMessage().equals("检索源必须使用 HTTPS"), "loopback exception does not open http to the internet");
    }

    private static void phrase() {
        String paragraph = "本文研究了钎焊温度对SiC陶瓷接头剪切强度的影响，结果表明，当温度从850℃升高到960℃时，"
                + "界面反应层的厚度明显增加，而接头强度先升高后下降，因此需要通过调整保温时间来控制金属间化合物的生长。";
        String phrase = PaperSources.queryPhrase(paragraph, 60);
        check(!phrase.isEmpty() && phrase.length() <= 60, "query phrase respects the character budget");
        check(phrase.length() < paragraph.length(), "query phrase never ships the whole paragraph");
        boolean verbatim = true;
        String[] pieces = phrase.split(" ");
        for (int i = 0; i < pieces.length; i++) if (!paragraph.contains(pieces[i])) verbatim = false;
        check(verbatim, "every phrase fragment exists verbatim in the source text");
        String[] banned = { "的", "了", "本文", "结果", "表明", "通过", "需要", "进行", "而", "因此", "可以" };
        boolean stopwordFree = true;
        for (int i = 0; i < banned.length; i++) if (phrase.contains(banned[i])) stopwordFree = false;
        check(stopwordFree, "query phrase drops Chinese stopwords");
        check(phrase.contains("钎焊"), "query phrase keeps the most distinctive term");
        check(PaperSources.queryPhrase(paragraph, 160).length() <= 160, "longer budget still bounded");
        String english = "In this work, the effects of brazing temperature on the shear strength of SiC joints "
                + "were investigated by means of a response surface design.";
        String englishPhrase = PaperSources.queryPhrase(english, 48);
        String lowered = englishPhrase.toLowerCase();
        check(!englishPhrase.isEmpty() && englishPhrase.length() <= 48, "english phrase bounded too");
        check(!lowered.contains("the") && !lowered.contains("were") && !lowered.contains("this"),
                "english stopwords dropped from the phrase");
        check(PaperSources.queryPhrase("", 60).isEmpty() && PaperSources.queryPhrase(null, 60).isEmpty(),
                "blank input yields a blank phrase");
    }

    private static DocxDocument.ParagraphBlock paragraph(int index, String text, String styleId, String styleName) {
        DocxDocument.ParagraphBlock value = new DocxDocument.ParagraphBlock();
        value.index = index;
        value.text = text;
        value.styleId = styleId;
        value.styleName = styleName;
        return value;
    }
/** 改写效果对照：同一份基线，改没降下来由数字说话。 */
    private static void rewriteEffect() {
        TextCorpus corpus = new TextCorpus();
        TextCorpus.Source source = new TextCorpus.Source();
        source.title = "多孔铜连接研究";
        corpus.add(source, "多孔铜在低温下即可与锡层反应，界面生成稳定的金属间化合物层。"
                + "保温时间过长会让反应层增厚，接头强度反而下降。");
        String copied = "多孔铜在低温下即可与锡层反应，界面生成稳定的金属间化合物层。";
        String filler = "实验在三种温度下各重复五次，取样位置固定在接头中心两侧。";
        String paraphrase = "把多孔铜片与锡层贴合后升温，两侧界面会生长出连续的金属间化合物。";
        DuplicateEngine.RewriteDelta better = DuplicateEngine.compareRewrite(copied + filler, paraphrase + filler, corpus);
        check(better.measured, "改写效果在两边都有字数时才算得出");
        check(better.beforeRate > 0 && better.afterRate < better.beforeRate, "改写后相似率确实掉下来");
        check(better.verdict.indexOf("下降") >= 0, "真降了就说下降");
        check(better.delta() > 0, "差值为正表示降重有效");
        DuplicateEngine.RewriteDelta same = DuplicateEngine.compareRewrite(copied + filler, copied + filler, corpus);
        check(Math.abs(same.delta()) < 0.5d && same.verdict.indexOf("基本没动") >= 0, "改写没起作用时不报成功");
        DuplicateEngine.RewriteDelta worse = DuplicateEngine.compareRewrite(filler, copied + filler + copied, corpus);
        check(worse.verdict.indexOf("升高") >= 0, "改得更像原文要直说");
        DuplicateEngine.RewriteDelta none = DuplicateEngine.compareRewrite(copied, paraphrase, new TextCorpus());
        check(!none.measured && none.verdict.indexOf("查重") >= 0, "没有基线时让人先去查重");
        DocxDocument document = new DocxDocument();
        add(document, paragraph(0, copied + filler, "", ""));
        DuplicateEngine.Report report = DuplicateEngine.scan(TextSelection.all(document), corpus,
                false, null, null, null, null);
        check(report.baseline == corpus, "扫描把真正比对的那份语料留在报告里");
        check(report.overallRate > 0, "离线扫描照样给出相似率");
    }

    private static void add(DocxDocument document, DocxDocument.ParagraphBlock paragraph) {
        document.blocks.add(paragraph);
        document.paragraphs.add(paragraph);
    }
    private static boolean covered(int[] spans, int start, int end) {
        for (int i = 0; i + 1 < spans.length; i += 2) if (spans[i] <= start && spans[i + 1] >= end) return true;
        return false;
    }
    private static boolean touches(int[] spans, int start, int end) {
        for (int i = 0; i + 1 < spans.length; i += 2) if (spans[i] < end && start < spans[i + 1]) return true;
        return false;
    }
    private static int at(TextSelection selection, String text) { return selection.text.indexOf(text); }

    private static void marking() {
        DocxDocument document = new DocxDocument();
        DocxDocument.ParagraphBlock prose = paragraph(0, "本研究发现钎焊温度升高会改变界面反应层的厚度，接头强度也随之变化。", "", "");
        DocxDocument.ParagraphBlock quoted = paragraph(1, "作者指出“钎焊间隙小于 0.2 mm 时接头最为牢固”，这一结论与实验一致。", "", "");
        DocxDocument.ParagraphBlock styled = paragraph(2, "[1] Zhang L. Brazing of SiC ceramics. Journal of Materials, 2021, 12: 34-45.",
                "zwc-additional-ref", "参考文献");
        DocxDocument.ParagraphBlock numbered = paragraph(3, "[2] Becker A. Effect of brazing gap on joint strength. Welding Journal, 2019.",
                "", "");
        DocxDocument.ParagraphBlock heading = paragraph(4, "参考文献", "a3", "标题 2");
        heading.isHeading = true;
        DocxDocument.ParagraphBlock listed = paragraph(5, "3. Ito R. Machine learning for brazing joints. Science and Technology, 2022, 8: 100-112.",
                "", "");
        add(document, prose);
        add(document, quoted);
        add(document, styled);
        add(document, numbered);
        add(document, heading);
        add(document, listed);
        TextSelection selection = TextSelection.all(document);
        int[] spans = DuplicateEngine.citationSpans(selection, document);
        int quoteStart = at(selection, "“钎焊间隙小于");
        int quoteEnd = at(selection, "牢固”") + 3;
        check(quoteStart >= 0 && covered(spans, quoteStart, quoteEnd), "explicit quotation is marked as a citation range");
        check(!covered(spans, at(selection, quoted.text), at(selection, quoted.text) + quoted.text.length()),
                "the paragraph carrying a quotation is not fully marked");
        check(covered(spans, at(selection, styled.text), at(selection, styled.text) + styled.text.length()),
                "reference-styled paragraph is marked");
        check(covered(spans, at(selection, numbered.text), at(selection, numbered.text) + numbered.text.length()),
                "numbered [n] entry is marked through TextProtection");
        check(covered(spans, at(selection, listed.text), at(selection, listed.text) + listed.text.length()),
                "entry under the 参考文献 heading is marked");
        check(!touches(spans, at(selection, prose.text), at(selection, prose.text) + prose.text.length()),
                "ordinary prose paragraph is left unmarked");
        check(!covered(spans, at(selection, heading.text), at(selection, heading.text) + heading.text.length()),
                "the reference heading itself is not treated as a reference");
        check(spans.length % 2 == 0, "citation spans come in start/end pairs");
        DocxDocument empty = new DocxDocument();
        TextSelection only = TextSelection.all(empty);
        check(DuplicateEngine.citationSpans(only, empty).length == 0 && DuplicateEngine.citationSpans(null, document).length == 0,
                "empty selection yields no citation spans");
    }

    /**
     * 抓回来的 OA 正文只写着 Adobe-GB1 的 CID（方正那一族）时，查重必须还拿得到可比正文：
     * 这就是"查重率 0%"里最硬的那一段——抓回来的 PDF 一个字都读不出，判据再好也比不出东西。
     */
    private static void cidTableBody(String base) throws Exception {
        PaperSources.Candidate candidate = new PaperSources.Candidate();
        candidate.source.engine = "openalex";
        candidate.source.title = "碳纳米管自组装";
        candidate.fullTextUrl = base + "/oa/gb1.pdf";
        int before = totalHits();
        String body = PaperSources.fullText(candidate, limits(), null);
        check(body.contains(GB1_TRUTH) && totalHits() == before + 1,
                "只有 Adobe-GB1 码位的 OA PDF 也拿到正文：" + body.trim().length() + " 字");

        // 抓回来的正文进语料、稿子里抄一句：判据必须报出非零，不能因为"读不出"冒充"没重复"。
        TextCorpus corpus = new TextCorpus();
        TextCorpus.Source source = new TextCorpus.Source();
        source.engine = "openalex";
        source.title = "碳纳米管自组装";
        corpus.add(source, body + "保温时间过长会让反应层增厚，接头强度反而下降。");
        DocxDocument document = new DocxDocument();
        add(document, paragraph(0, GB1_TRUTH + "实验在三种温度下各重复五次，取样位置固定在接头中心两侧。", "", ""));
        DuplicateEngine.Report report = DuplicateEngine.scan(TextSelection.all(document), corpus,
                false, null, null, null, null);
        int spans = 0;
        for (TextCorpus.Hit hit : report.hits) if (hit.source == source) spans++;
        check(report.overallRate > 0 && spans > 0,
                "抄了这份正文的一句就要判出来：头条 " + report.overallRate + "% / 落到这篇的命中 " + spans + " 段");

        // 没有这张表就是改前的样子：抓回来的正文是空的，比对没有可比正文（当年印 0.13% 的那个坑）。
        CidUnicodeTables.uninstall();
        int beforeNoTable = totalHits();
        String empty = PaperSources.fullText(candidate, limits(), null);
        CidUnicodeTables.install(new java.io.FileInputStream("app/src/main/assets/cmaps/adobe-gb1.cid"));
        check(empty.trim().isEmpty() && totalHits() == beforeNoTable + 1,
                "撤掉表以后这份 PDF 一个正文字都读不出（这就是当年 0% 的来路）");
    }

    private static DocxDocument essay() {
        DocxDocument document = new DocxDocument();
        add(document, paragraph(0, "针对钎焊工艺参数的优化，本文提出了一种分层的实验设计思路。", "", ""));
        add(document, paragraph(1, "独有描述串乙丙丁戊己庚辛壬癸子丑寅卯辰巳午未申酉戌亥，用于验证检索请求不会带上正文。", "", ""));
        add(document, paragraph(2, "实验部分讨论了保温时间对界面组织的影响，并给出断口形貌的观察结果。", "", ""));
        add(document, paragraph(3, "作者指出“钎焊间隙小于 0.2 mm 时接头最为牢固”，与既有报道一致。", "", ""));
        add(document, paragraph(4, "[1] Zhang L. Brazing of SiC ceramics. Journal of Materials, 2021, 12: 34-45.", "", ""));
        return document;
    }

    private static void scan(String base) {
        String secret = "独有描述串乙丙丁戊己庚辛壬癸子丑寅卯辰巳午未申酉戌亥";
        int coreBefore = hits("/core");
        TextSelection selection = TextSelection.all(essay());
        PaperSources.Limits limits = limits();
        limits.perEngine = 3;
        final ArrayList<String> labels = new ArrayList<String>();
        DuplicateEngine.Report report = DuplicateEngine.scan(selection, new TextCorpus(), true, null, limits,
                new ApiClient.Task(), (label, done, total) -> labels.add(label));
        check(!report.candidates.isEmpty() && report.candidateCount.size() >= 4,
                "full scan collects candidates from several loopback engines");
        check(report.candidateCount.get("openalex") != null && report.candidateCount.get("arxiv") != null,
                "candidate counts are tracked per engine");
        int gathered = 0;
        for (String key : report.candidateCount.keySet()) gathered += report.candidateCount.get(key).intValue();
        /* 配额语义变了：perEngine 只管每次请求要几条，入库改成「全库 120 篇 + 逐源 perEngine 篇」
           双上限，"总量 <= 5 x perEngine" 这个旧口径量的是已经不存在的第三个数。 */
        int overCap = 0;
        for (String key : report.candidateCount.keySet())
            if (report.candidateCount.get(key).intValue() > limits.perEngine) overCap++;
        check(gathered == report.candidates.size() && gathered <= DuplicateEngine.MAX_CORPUS_PAPERS && overCap == 0,
                "corpus honours the two caps: 120 papers overall and perEngine per source");
        boolean skippedCore = false;
        for (String note : report.notes) if (note.contains("CORE") && note.contains("未配置 API Key")) skippedCore = true;
        check(skippedCore, "keyless CORE is reported as skipped instead of failing the scan");
        check(hits("/core") == coreBefore, "scan without a CORE key sends no CORE request");
        check(report.overallRate >= 0 && report.overallRate <= 100 && report.excludingCitationsRate >= 0
                        && report.excludingCitationsRate <= 100 && report.selfWrittenRate >= 0 && report.selfWrittenRate <= 100
                        && report.aigcRate >= 0 && report.aigcRate <= 100,
                "all four rates stay inside 0..100");
        /* 0.7.1 之后这不是"不超过 100"而是恰好等于 100：三个比率出自 CharLedger 同一次划分，
           自编率已经把已判重复的字符整个减掉了，所以两个数在同一个分母上严丝合缝地对咬。 */
        check(Math.abs(report.selfWrittenRate + report.overallRate - 100d) < 1e-9,
                "self-written rate and overall rate close 100 on one denominator");
        check(report.ledger != null && report.ledger.residual() == 0
                        && report.ledger.uncitedDuplicateChars + report.ledger.citedDuplicateChars
                        + report.ledger.selfWrittenChars == report.comparedChars,
                "character-level closure: uncited duplicate + cited duplicate + self-written equals comparedChars");
        check(report.excludingCitationsRate <= report.overallRate + 1e-9
                        && Math.abs(report.overallRate - report.excludingCitationsRate
                        - report.ledger.citedDuplicateRate) < 1e-9,
                "总相似度比减去引用内那份重复就是去除引用重复比，三个数同一把尺");
        check(!report.retrievalIncomplete && report.retrievalReason == null,
                "a loopback scan that took candidates is a complete run");
        check(!report.detectedAt.isEmpty() && report.elapsedMillis >= 0, "report stamps detection time and elapsed millis");
        check(report.sourceText.equals(selection.text), "report keeps the scanned text for snippet rendering");
        check(!labels.isEmpty() && labels.get(0).startsWith("检索"), "progress reports retrieval steps first");
        boolean privateRequests = true;
        for (String key : TARGETS.keySet()) {
            String target = TARGETS.get(key);
            if (target.contains(secret) || target.length() > 500 || target.contains("core-secret-key")) privateRequests = false;
        }
        check(privateRequests, "no retrieval request carries document text, an over-long query or a key");
        ApiClient.Task cancelled = new ApiClient.Task();
        cancelled.cancel();
        int beforeCancel = totalHits();
        DuplicateEngine.Report stopped = DuplicateEngine.scan(selection, new TextCorpus(), true, null, limits, cancelled, null);
        check(stopped.hits.isEmpty(), "cancelled scan produces no similarity hits");
        check(totalHits() == beforeCancel, "cancelled scan issues no retrieval requests");
        boolean noted = false;
        for (String note : stopped.notes) if (note.contains("取消")) noted = true;
        check(noted, "cancellation is written into notes");
        check(stopped.candidates.isEmpty(), "cancelled scan keeps no candidates");
        check(stopped.retrievalIncomplete && stopped.retrievalReason.contains("取消"),
                "a web scan cancelled before the search says the retrieval never happened");
        int beforeOffline = totalHits();
        DuplicateEngine.Report offline = DuplicateEngine.scan(selection, new TextCorpus(), false, null, limits, new ApiClient.Task(), null);
        check(totalHits() == beforeOffline, "local-only scan never touches the network");
        boolean explained = false;
        for (String note : offline.notes) if (note.contains("未启用联网检索")) explained = true;
        check(explained, "local-only scan explains its baseline in notes");
        check(offline.retrievalIncomplete && offline.retrievalReason.contains("自建库为空"),
                "a local-only scan over an empty library admits there was nothing to compare against");
        TextCorpus mirrored = new TextCorpus();
        TextCorpus.Source mirroredSource = new TextCorpus.Source();
        mirroredSource.engine = "openalex";
        mirroredSource.title = "收录了同一段落来源";
        mirroredSource.locator = "10.9/echo";
        mirroredSource.year = "2024";
        mirrored.add(mirroredSource, selection.text);
        DuplicateEngine.Report mirror = DuplicateEngine.scan(selection, mirrored, false, null, limits, new ApiClient.Task(), null);
        check(!mirror.hits.isEmpty() && mirror.duplicateChars > 0 && mirror.overallRate > 0,
                "a corpus holding the same wording produces hits and a non-zero duplication rate");
        check(mirror.hits.get(0).source != null && mirror.byEngine.isEmpty() == mirror.hits.isEmpty(),
                "hits carry their source and feed the per-engine distribution");
        check(mirror.candidates.isEmpty() && totalHits() == beforeOffline, "the offline mirror scan still never goes online");
        check(!mirror.retrievalIncomplete && mirror.retrievalReason == null,
                "a local-only scan against a non-empty library is a complete run");
        String mirrorHtml = CheckReport.html("mirror.docx", mirror);
        check(mirrorHtml.contains("<td>总相似度比</td>") && mirrorHtml.contains("<td>自编率</td>")
                        && !mirrorHtml.contains("未完成查重"),
                "the complete local run keeps the numeric headline");
        ArrayList<String> subset = new ArrayList<String>();
        subset.add("openalex");
        subset.add("不存在的源");
        int beforeSubset = hits("/openalex");
        DuplicateEngine.Report single = DuplicateEngine.scan(selection, new TextCorpus(), true, subset, limits, new ApiClient.Task(), null);
        check(hits("/openalex") > beforeSubset && single.candidateCount.get("openalex") != null,
                "engine subset limits requests to the requested source");
        boolean flagged = false;
        for (String note : single.notes) if (note.contains("未知检索源")) flagged = true;
        check(flagged, "unknown engine name is reported in notes and skipped");
    }

    /** The phone symptom: every connector dies at the transport, the library is empty, and 0.00% would be a lie. */
    private static void unreachable(String base) throws Exception {
        TextSelection selection = TextSelection.all(essay());
        PaperSources.Limits limits = limits();
        limits.perEngine = 2;
        ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        int port = probe.getLocalPort();
        probe.close();
        everyEndpointAt("http://127.0.0.1:" + port + "/");
        DuplicateEngine.Report blackout = DuplicateEngine.scan(selection, new TextCorpus(), true, null, limits,
                new ApiClient.Task(), null);
        loopback(base);
        check(blackout.candidates.isEmpty(), "a blackout scan keeps no candidates");
        check(blackout.retrievalIncomplete, "a web scan where every connector failed is flagged incomplete");
        check(blackout.retrievalReason != null && blackout.retrievalReason.contains("个检索源本次全部不可用")
                        && blackout.retrievalReason.contains("联网检索没有取回可比对的候选文献"),
                "the reason names the dead connectors with the note wording");
        boolean counted = false;
        for (String note : blackout.notes) if (note.contains("个检索源本次不可用")) counted = true;
        check(counted, "the blackout scan keeps its existing failure note");
        String html = CheckReport.html("thesis.docx", blackout);
        check(html.contains("未完成查重"), "the blackout report headlines the run as unfinished");
        check(!html.contains("总相似度比 0.00") && !html.contains("<td>总相似度比</td>")
                        && !html.contains("<td>去除引用重复比</td>") && !html.contains("<td>自编率</td>"),
                "the blackout report shows no numeric duplication rate anywhere");
        check(html.contains("机器生成倾向") && html.contains("AIGC 倾向句"),
                "the blackout report still carries the locally computed AIGC block");
        // 样本不足只说差多少字；这一格里不许有百分号，更不许有 0.00%。
        String blackoutRow = metricRow(html, "机器生成倾向");
        check(blackoutRow != null && blackoutRow.contains("样本不足（有效字符 ") && blackoutRow.contains("门槛 400 字")
                        && !blackoutRow.contains("%") && !html.contains("AIGC 生成比例"),
                "an insufficient sample prints tier plus a character deficit, never 0.00% and never a second 比例");
        // 整份报告里"比例"只剩 AIGC 那句"不给生成比例"，指标区再也没有冒充比例的数。
        check(occurrences(html, "比例") == 1 && html.contains("不给生成比例"),
                "the only surviving use of the word 比例 is the sentence that refuses to give one");
        ArrayList<String> unknown = new ArrayList<String>();
        unknown.add("不存在的源");
        DuplicateEngine.Report unselected = DuplicateEngine.scan(selection, new TextCorpus(), true, unknown, limits,
                new ApiClient.Task(), null);
        check(unselected.retrievalIncomplete && unselected.retrievalReason.contains("没有可用的检索源"),
                "a web scan with no usable source selected is flagged");
        DuplicateEngine.Report emptied = DuplicateEngine.scan(selection, new TextCorpus(), false, unknown, limits,
                new ApiClient.Task(), null);
        check(emptied.retrievalIncomplete && emptied.retrievalReason.contains("自建库为空"),
                "a local-only scan with an empty library is flagged whatever the source list says");
    }

    private static final String LEGACY_TAIL = "</style><h1>查重报告</h1><p>f.docx</p><p>重复率：12.50%</p>"
            + "<p>检测时间：2026-10-07T00:00:00Z</p><table><thead><tr><th>相似片段</th><th>相似来源</th></tr></thead><tbody>"
            + "<tr><td>&lt;script&gt;x&lt;/script&gt;</td><td>论文来源</td></tr></tbody></table></html>";

    private static void reports() {
        DuplicateEngine.Report report = new DuplicateEngine.Report();
        report.sourceText = "他说“重复率偏高”，随后重写了这一段正文。";
        /* 指标区的数不再手填，全出自同一本账：全文 21 个有效字符，重复是 [3,8) 的 5 个字
           （"重复率偏高"），其中引用区间 [6,9) 盖住 2 个字，机器腔区间 [10,18) 是 8 个字。
           于是 总相似度比 = 5/21、去除引用重复比 = 3/21、引用内重复比 = 2/21、自编率 = 16/21。 */
        report.ledger = CharLedger.closeSpans(report.sourceText, null, new int[]{6, 9},
                new int[]{3, 8}, new int[]{10, 18});
        report.comparedChars = report.ledger.totalChars;
        report.duplicateChars = report.ledger.duplicateChars;
        report.citedDuplicateChars = report.ledger.citedDuplicateChars;
        report.overallRate = report.ledger.overallRate;
        report.excludingCitationsRate = report.ledger.excludingCitationsRate;
        report.selfWrittenRate = report.ledger.selfWrittenRate;
        report.aigcRate = 17.5;
        report.detectedAt = "2026-10-07T10:00:00Z";
        report.elapsedMillis = 12;
        report.byEngine.put("openalex", Double.valueOf(9.5));
        report.candidateCount.put("openalex", Integer.valueOf(2));
        PaperSources.Candidate candidate = new PaperSources.Candidate();
        candidate.source.engine = "openalex";
        candidate.source.title = "<script>alert(\"x\")</script>";
        candidate.source.authors = "L. \"Lonely\" Zhang";
        candidate.source.year = "2021";
        candidate.source.locator = "10.1/\"a\"";
        report.candidates.add(candidate);
        TextCorpus.Hit hit = new TextCorpus.Hit();
        hit.start = 3;
        hit.end = 8;
        hit.score = 0.87f;
        hit.source = new TextCorpus.Source();
        hit.source.title = "某篇论文 \"副本\"";
        hit.source.year = "2020";
        hit.source.locator = "PMID:12345678";
        report.hits.add(hit);
        AigcDetector.Sentence sentence = new AigcDetector.Sentence();
        sentence.start = 0;
        sentence.end = 6;
        sentence.score = 0.9f;
        sentence.features.add("模板句式命中");
        report.aigc = new AigcDetector.Result();
        report.aigc.sentences.add(sentence);
        report.aigc.rate = 17.5f;
        report.aigc.comparedChars = 1000;
        report.aigc.tier = AigcDetector.Tier.WATCH;
        report.notes.add("已跳过 CORE：CORE 未配置 API Key");
        String html = CheckReport.html("报告<script>.docx", report);
        check(!html.contains("<script>"), "duplicate report escapes every script tag");
        check(html.contains("&lt;script&gt;") && html.contains("&quot;"), "tags and quotes inside foreign text are escaped");
        check(html.contains("总相似度比") && html.contains("去除引用重复比") && html.contains("自编率")
                        && html.contains("机器生成倾向"),
                "report lists the three rates plus the AIGC tier line");
        // 手算：5/21 = 23.809523...%、3/21 = 14.285714...%、16/21 = 76.190476...%，四舍五入两位小数。
        check(html.contains("23.81%") && html.contains("14.29%") && html.contains("76.19%"),
                "rates render as percentages of one 21-character denominator: 5/21, 3/21, 16/21");
        check(html.contains("参与比对 21 个有效字符") && html.contains("命中相似 5 个")
                        && html.contains("落在引用区间内 2 个"),
                "字数那一行说的就是账本里的 21、5、2，比率与绝对量不打架");
        check(!html.contains("AIGC 生成比例") && occurrences(html, "比例") == 0,
                "the finished report never calls anything a 比例 any more: the AIGC cell lost that name");
        String trendRow = metricRow(html, "机器生成倾向");
        // The scorer's direction is inverted on the labelled corpus (docs/aigc-corpus.md 4.2: AUC(machine>human)
        // = 0.305), so this cell refuses. The arithmetic it used to print stays pinned as three pieces: 8 flagged
        // characters out of a 21-character document, and WATCH still spells itself 观察.
        check(DuplicateEngine.trendMachineChars(report) == 8 && DuplicateEngine.trendTotalChars(report) == 21
                        && DuplicateEngine.aigcTierName(AigcDetector.Tier.WATCH).equals("观察"),
                "the numbers behind that cell are still 8 flagged of 21, and the WATCH tier name is still 观察");
        check(trendRow != null
                        && trendRow.equals("<td>机器生成倾向</td><td>" + DuplicateEngine.AIGC_UNCALIBRATED + "</td>")
                        && !trendRow.contains("%"),
                "the AIGC cell is exactly the refusal, nothing else: " + trendRow);
        check(metricRow(html, DuplicateEngine.AIGC_SCORE_LABEL) == null && !html.contains("17.5")
                        && !html.contains("17.50%"),
                "the weighted sentence score row is gone entirely while uncalibrated, not relabelled");
        check(html.contains("PMID:12345678") && html.contains("2020") && html.contains("重复率偏高"),
                "snippet row shows source title, year and locator");
        check(html.contains("模板句式命中") && html.contains("OpenAlex") && html.contains("已跳过 CORE"),
                "AIGC evidence, engine distribution and notes all rendered");
        check(html.contains("候选文献 1 篇") && html.contains("相似片段 1 处"), "candidate and snippet tables carry their counts");
        check(!html.contains("重复率："), "new overload does not reuse the legacy single-rate layout");
        ApiResult.Check legacy = new ApiResult.Check();
        legacy.rate = 12.5;
        legacy.detectedAt = "2026-10-07T00:00:00Z";
        ApiResult.Fragment fragment = new ApiResult.Fragment();
        fragment.text = "<script>x</script>";
        fragment.source = "论文来源";
        legacy.fragments.add(fragment);
        String legacyHtml = CheckReport.html("f.docx", legacy);
        check(legacyHtml.endsWith(LEGACY_TAIL), "legacy overload output stays byte-identical after adding the new one");
        check(!legacyHtml.contains("<script>") && !html.equals(legacyHtml), "legacy report still escapes API text");
    }

    /** A report with the retrieval flag up: only the locally computed AIGC share may keep a number. */
    private static void unfinishedReport() {
        DuplicateEngine.Report report = new DuplicateEngine.Report();
        report.sourceText = "本文的结论建立在实验数据与既有报道之上。";
        // 没有可比对的文献就没有任何重复可记：账本给出分母 20、分子 0、自编 20，三个比率仍然闭合。
        report.ledger = CharLedger.closeSpans(report.sourceText, null, null, null, null);
        report.comparedChars = report.ledger.totalChars;
        report.duplicateChars = report.ledger.duplicateChars;
        report.overallRate = report.ledger.overallRate;
        report.excludingCitationsRate = report.ledger.excludingCitationsRate;
        report.selfWrittenRate = report.ledger.selfWrittenRate;
        report.aigcRate = 10.71;
        report.retrievalIncomplete = true;
        report.retrievalReason = "5 个检索源本次全部不可用，联网检索没有取回可比对的候选文献";
        report.detectedAt = "2026-10-08T10:00:00Z";
        report.elapsedMillis = 640;
        AigcDetector.Sentence sentence = new AigcDetector.Sentence();
        sentence.start = 0;
        sentence.end = 6;
        sentence.score = 0.72f;
        sentence.features.add("书面连接词密集");
        report.aigc = new AigcDetector.Result();
        report.aigc.rate = 10.71f;
        report.aigc.comparedChars = 420;
        report.aigc.tier = AigcDetector.Tier.WATCH;
        report.aigc.sentences.add(sentence);
        // 机器腔区间 [0,6) = "本文的结论"，6 个字；全文 20 个有效字符，两者同一把尺。
        AigcDetector.Segment flagged = new AigcDetector.Segment();
        flagged.start = 0;
        flagged.end = 6;
        flagged.score = AigcDetector.SEGMENT_FLAG_GATE;
        flagged.flagged = true;
        report.aigc.segments.add(flagged);
        report.ledger = CharLedger.close(report.sourceText, null, null,
                new ArrayList<TextCorpus.Hit>(), report.aigc);
        report.notes.add("联网检索没有取回可比对的候选文献");
        String html = CheckReport.html("thesis.docx", report);
        check(html.contains("未完成查重") && html.contains("5 个检索源本次全部不可用"),
                "the unfinished headline shows why the run counts for nothing");
        check(!html.contains("总相似度比 0.00") && !html.contains("<td>总相似度比</td>")
                        && !html.contains("<td>去除引用重复比</td>") && !html.contains("<td>自编率</td>"),
                "no duplication rate survives an unfinished run");
        String trendRow = metricRow(html, "机器生成倾向");
        // 手算：可疑 6 个字 / 全文 20 个有效字符。0.7.2 起这一格只给拒绝句，那两个绝对量单独钉住：
        // 撤掉一格数字，不许顺手把账本口径也弄丢（判据方向没验正是撤它的理由，不是重算账的理由）。
        check(DuplicateEngine.trendMachineChars(report) == 6 && DuplicateEngine.trendTotalChars(report) == 20
                        && DuplicateEngine.aigcTierName(report.aigc.tier).equals("观察"),
                "the two character counts behind that cell are still 6 of 20, tier WATCH spells 观察");
        check(trendRow != null
                        && trendRow.equals("<td>机器生成倾向</td><td>" + DuplicateEngine.AIGC_UNCALIBRATED + "</td>")
                        && !trendRow.contains("%"),
                "an unfinished report refuses the AIGC figure too: " + trendRow);
        check(metricRow(html, DuplicateEngine.AIGC_SCORE_LABEL) == null && !html.contains("10.7"),
                "the 10.7 weighted score row is not printed while the scorer is uncalibrated");
        check(!html.contains("AIGC 生成比例") && occurrences(html, "比例") == 0,
                "an unfinished report has no rate at all, so the word 比例 must not survive anywhere in it");
        check(html.contains("AIGC 倾向句") && html.contains("书面连接词密集") && !html.contains("72.00%"),
                "the per-sentence list keeps its evidence words and loses the 72.00% score column");
        check(html.contains("不是可疑度排序"),
                "the sentence table now says its order is document order, not a suspicion ranking");
        check(html.contains("联网检索没有取回可比对的候选文献"), "the notes still explain the unfinished run");
    }

    /** "比例"这个词在报告里出现几次。0.7.1 之后它只许活在 AIGC 那句"不给生成比例"里。 */
    private static int occurrences(String text, String needle) {
        int at = 0, found = 0;
        while ((at = text.indexOf(needle, at)) >= 0) { found++; at += needle.length(); }
        return found;
    }

    /** 取某一格（<td>指标名</td> 到最近的 </tr>）：要说"这一格里没有百分号"就必须只看这一格。 */
    private static String metricRow(String html, String name) {
        int at = html.indexOf("<td>" + name + "</td>");
        if (at < 0) return null;
        int end = html.indexOf("</tr>", at);
        return end < 0 ? null : html.substring(at, end);
    }
}
