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
            + "\"url\":\"https://academic.oup.com/free/html\"}]}}]}}";
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
    private static final String FULLTEXT_XML = "<article><body><sec><title>Intro</title>"
            + "<p>Brazing gaps below 0.2 mm produced the strongest joints.</p></sec></body></article>";

    public static void main(String[] args) throws Exception {
        cqvipFixture = fixture(args.length > 0 ? args[0] : "tests/samples/cqvip-search.html");
        ncpssdFixture = fixture(args.length > 1 ? args[1] : "tests/samples/ncpssd-search.json");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/openalex", exchange -> { record("/openalex", queryOf(exchange)); respond(exchange, 200, OPENALEX); });
        server.createContext("/crossref", exchange -> { record("/crossref", queryOf(exchange)); respond(exchange, 200, CROSSREF); });
        server.createContext("/semantic-scholar", exchange -> { record("/semantic-scholar", queryOf(exchange)); respond(exchange, 200, SEMANTIC); });
        server.createContext("/europepmc/search", exchange -> { record("/europepmc/search", queryOf(exchange)); respond(exchange, 200, EUROPEPMC); });
        server.createContext("/europepmc/", exchange -> { record("/europepmc/fulltext", queryOf(exchange)); respond(exchange, 200, FULLTEXT_XML); });
        server.createContext("/arxiv", exchange -> { record("/arxiv", queryOf(exchange)); respond(exchange, 200, ARXIV); });
        server.createContext("/core", exchange -> {
            record("/core", queryOf(exchange));
            lastApiKey = exchange.getRequestHeaders().getFirst("api-key");
            respond(exchange, 200, CORE);
        });
        server.createContext("/cqvip", exchange -> { record("/cqvip", queryOf(exchange)); respond(exchange, 200, cqvipFixture); });
        server.createContext("/sometimes", exchange -> {
            String asked = queryOf(exchange);
            record("/sometimes", asked);
            respond(exchange, 200, asked.contains("alpha-marker") ? EMPTY_CROSSREF : CROSSREF);
        });
        server.createContext("/ncpssd", exchange -> {
            record("/ncpssd", queryOf(exchange));
            lastMethod = exchange.getRequestMethod();
            lastForm = body(exchange);
            respond(exchange, 200, ncpssdFixture);
        });
        server.createContext("/notfound", exchange -> { record("/notfound", queryOf(exchange)); respond(exchange, 404, "{\"error\":\"missing\"}"); });
        server.createContext("/error", exchange -> { record("/error", queryOf(exchange)); respond(exchange, 500, "{\"error\":\"boom\"}"); });
        server.createContext("/throttled", exchange -> { record("/throttled", queryOf(exchange)); respond(exchange, 429, "{\"error\":\"rate limit\"}"); });
        server.createContext("/throttled-once", exchange -> {
            record("/throttled-once", queryOf(exchange));
            boolean first = hits("/throttled-once") == 1;
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
        } finally {
            server.stop(0);
            PaperSources.resetEndpoints();
        }
        rewriteEffect();
        System.out.println("SUMMARY " + checks + " assertions passed; loopback-only network");
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
        PaperSources.setEndpoint("cqvip", base + "/cqvip");
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
        add(document, paragraph(0, "alpha-marker 这一段先送去检索，希望这个源第一次答空。", "", ""));
        add(document, paragraph(1, "中段内容用来把窗口撑满，使第一段单独成为一个检索窗口。", "", ""));
        add(document, paragraph(2, "这一段同样只是正文，不参与断言，只保证窗口切分稳定可预期。", "", ""));
        add(document, paragraph(3, "beta-marker 这一段再送去检索，这一次同一个源应当给出候选。", "", ""));
        add(document, paragraph(4, "再补一段正文，让第二个窗口稳定成型而不受标点影响。", "", ""));
        add(document, paragraph(5, "最后一段正文，检索窗口到这里就足够覆盖两个窗口的差别。", "", ""));
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
        check(engines.size() == 8 && engines.contains("openalex") && engines.contains("semantic-scholar")
                        && engines.contains("europepmc") && engines.contains("core")
                        && engines.contains("cqvip") && engines.contains("ncpssd"),
                "eight built-in engines are registered, the two Chinese ones first");
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
        check(europe.fullTextUrl.equals(base + "/europepmc/MED/PMC9876543/fullTextXML"),
                "Europe PMC full text url stays on the configured endpoint");
        check(query("/europepmc/search").contains("resultType=core") && query("/europepmc/search").contains("pageSize=5"),
                "Europe PMC asks for core results with a page size");
        int beforeFull = totalHits();
        String fullText = PaperSources.fullText(europe, limits, null);
        check(fullText.contains("Brazing gaps below 0.2 mm produced the strongest joints.") && fullText.indexOf('<') < 0,
                "Europe PMC fullTextXML is reduced to plain comparison text");
        check(totalHits() == beforeFull + 1 && query("/europepmc/fulltext").startsWith("/europepmc/MED/PMC9876543/fullTextXML"),
                "full text is fetched once and only from loopback");
        PaperSources.Candidate pdf = new PaperSources.Candidate();
        pdf.source.engine = "openalex";
        pdf.fullTextUrl = "https://repo.example.org/1234/paper.pdf";
        int beforePdf = totalHits();
        check(PaperSources.fullText(pdf, limits, null).isEmpty() && totalHits() == beforePdf,
                "pdf-only candidates are never downloaded");

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
                        && failure.getMessage().equals("检索源限流（HTTP 429），稍后重试"),
                "429 says the source throttled us rather than blaming the network");
        ApiClient.Response recovered = null;
        try {
            recovered = HttpTransport.get(base + "/throttled-once?q=" + marker, null, 8, 0, null);
        } catch (IOException ignored) { }
        check(recovered != null && recovered.attempts == 2 && hits("/throttled-once") == 2
                        && recovered.body.contains("Brazing of SiC ceramic"),
                "a throttled source gets exactly one patient retry and then answers");
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
        check(gathered == report.candidates.size() && gathered <= 5 * limits.perEngine,
                "per-engine candidate cap honoured and totals consistent");
        boolean skippedCore = false;
        for (String note : report.notes) if (note.contains("CORE") && note.contains("未配置 API Key")) skippedCore = true;
        check(skippedCore, "keyless CORE is reported as skipped instead of failing the scan");
        check(hits("/core") == coreBefore, "scan without a CORE key sends no CORE request");
        check(report.overallRate >= 0 && report.overallRate <= 100 && report.excludingCitationsRate >= 0
                        && report.excludingCitationsRate <= 100 && report.selfWrittenRate >= 0 && report.selfWrittenRate <= 100
                        && report.aigcRate >= 0 && report.aigcRate <= 100,
                "all four rates stay inside 0..100");
        check(report.selfWrittenRate + report.overallRate <= 100.0001, "self-written rate never double counts duplicated text");
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
        check(html.contains("AIGC 生成比例") && html.contains("AIGC 倾向句"),
                "the blackout report still carries the locally computed AIGC block");
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
        report.overallRate = 12.5;
        report.excludingCitationsRate = 4.25;
        report.selfWrittenRate = 70;
        report.aigcRate = 17.5;
        report.comparedChars = 1000;
        report.duplicateChars = 125;
        report.citedDuplicateChars = 82;
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
        report.notes.add("已跳过 CORE：CORE 未配置 API Key");
        String html = CheckReport.html("报告<script>.docx", report);
        check(!html.contains("<script>"), "duplicate report escapes every script tag");
        check(html.contains("&lt;script&gt;") && html.contains("&quot;"), "tags and quotes inside foreign text are escaped");
        check(html.contains("总相似度比") && html.contains("去除引用重复比") && html.contains("自编率") && html.contains("AIGC 生成比例"),
                "report lists the three rates plus the AIGC share");
        check(html.contains("12.50%") && html.contains("4.25%") && html.contains("70.00%") && html.contains("17.50%"),
                "rates render as bounded percentages");
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
        report.overallRate = 0;
        report.excludingCitationsRate = 0;
        report.selfWrittenRate = 99.65;
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
        report.aigc.sentences.add(sentence);
        report.notes.add("联网检索没有取回可比对的候选文献");
        String html = CheckReport.html("thesis.docx", report);
        check(html.contains("未完成查重") && html.contains("5 个检索源本次全部不可用"),
                "the unfinished headline shows why the run counts for nothing");
        check(!html.contains("总相似度比 0.00") && !html.contains("<td>总相似度比</td>")
                        && !html.contains("<td>去除引用重复比</td>") && !html.contains("<td>自编率</td>"),
                "no duplication rate survives an unfinished run");
        check(html.contains("AIGC 生成比例") && html.contains("10.71%"),
                "the AIGC figure keeps its place in an unfinished report");
        check(html.contains("AIGC 倾向句") && html.contains("书面连接词密集") && html.contains("72.00%"),
                "the per-sentence AIGC list renders as it does today");
        check(html.contains("联网检索没有取回可比对的候选文献"), "the notes still explain the unfinished run");
    }
}
