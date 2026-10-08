package com.rikkahub.wordlite;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * 全文额度的来路：一条候选的 fullTextUrl 是从哪来的、它值不值得花掉一次抓取、没抓成时账上留下什么。
 *
 * <p>逼出这一轮的事实测在 2026-10-09：Europe PMC 的检索行明明带着 isOpenAccess 和 fullTextUrlList
 * （开放获取那几条自己就给 documentStyle=pdf 的直链），解析器却拿 pmcid 自己拼一条
 * <code>.../rest/MED/&lt;pmcid&gt;/fullTextXML</code> 当全文链接，而那条口实测两种拼法六次全 404
 * （143 B 的 JSON 报错，连 isOpenAccess=Y 又 inEPMC=Y 的记录也一样）。检索式"多孔铜 瞬态液相连接 制备"
 * 那一轮 27 条候选里 7 条挂着全文链接，其中 5 条是这种拼出来的假链接，六个全文额度被它们占满，
 * 唯一一条真能带回字的 https://pdf.hanspub.org/....pdf（12,853 字）差点没轮到。
 * <p>所以这里钉四件事：链接只能来自源自己给的证据；没证据就留空并把原因落到响应账里；
 * 路径自己说是 XML/HTML 的不许和 PDF 直链同价；抓失败时形状要带上源答了什么。
 * 全程只走回环，不碰真接口。
 */
public class FullTextYieldRegression {
    private static int checks;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
        System.out.println("PASS " + message);
    }

    private static final String ROW = "\"pmcid\":\"PMC13522914\",\"source\":\"MED\",\"pmid\":\"42706052\","
            + "\"doi\":\"10.0000/tlp.2026.001\",\"title\":\"Transient liquid phase bonding of porous copper\","
            + "\"pubYear\":\"2025\",\"abstractText\":\" porous copper 瞬态液相连接 制备 工艺 参数 。\","
            + "\"authorList\":{\"author\":[{\"firstName\":\"A.\",\"lastName\":\"Ito\"}]}";

    /** 源明说这篇不开放获取：手里给了 fullTextUrl 也不算数。 */
    private static String rowNoOpenAccess() {
        return "{\"hitCount\":1,\"resultList\":{\"result\":[{" + ROW
                + ",\"isOpenAccess\":\"N\",\"fullTextUrlList\":{\"fullTextUrl\":[{"
                + "\"availability\":\"Elsevier\",\"documentStyle\":\"xml\",\"url\":\"https://publisher.example.org/xml\"}]}}]}}";
    }

    /** 开放获取且自己给了 pdf 与 html 两条：要拿 pdf 那条，html 那条实测要么跳转要么 403。 */
    private static String rowOpenAccessWithPdf(String base) {
        return "{\"hitCount\":1,\"resultList\":{\"result\":[{" + ROW
                + ",\"isOpenAccess\":\"Y\",\"inEPMC\":\"Y\",\"fullTextUrlList\":{\"fullTextUrl\":["
                + "{\"availability\":\"Free\",\"documentStyle\":\"html\",\"url\":\"https://academic.example.org/html\"},"
                + "{\"availability\":\"Free\",\"availabilityCode\":\"F\",\"documentStyle\":\"pdf\",\"url\":\""
                + base + "/epmc/file\"}]}}]}}";
    }

    /** 只有 html 的：按实测那两条一条要追 301、一条 403，宁可留空。 */
    private static String rowOpenAccessHtmlOnly() {
        return "{\"hitCount\":1,\"resultList\":{\"result\":[{" + ROW
                + ",\"isOpenAccess\":\"Y\",\"fullTextUrlList\":{\"fullTextUrl\":[{"
                + "\"availability\":\"Free\",\"documentStyle\":\"html\",\"url\":\"https://europepmc.org/articles/PMC13522914\"}]}}]}}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        OutputStream out = exchange.getResponseBody();
        out.write(bytes);
        out.close();
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final ArrayList<PaperSources.ShapeRow> rows = new ArrayList<PaperSources.ShapeRow>();
        server.createContext("/epmc", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            if (path.endsWith("/no-oa")) respond(exchange, 200, rowNoOpenAccess());
            else if (path.endsWith("/oa-html")) respond(exchange, 200, rowOpenAccessHtmlOnly());
            else respond(exchange, 200, rowOpenAccessWithPdf(base));
        });
        server.createContext("/epmc/file", exchange -> respond(exchange, 200,
                "<article><body><p>保温 30 分钟后界面处的孔隙率降到 4% 以下，" + "说明瞬态液相把多孔铜的连通孔基本填满，接头剪切强度随之上升。</p></body></article>"));
        server.createContext("/epmc/gone", exchange -> respond(exchange, 404,
                "{\"error\":\"Not Found\",\"message\":\"no full text\",\"status\":404}"));
        server.createContext("/epmc/jump", exchange -> {
            exchange.getResponseHeaders().set("Location", "https://elsewhere.example.org/file");
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "stub");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            PaperSources.Limits limits = new PaperSources.Limits();
            limits.perEngine = 5;
            limits.timeoutSeconds = 10;
            limits.shapes = new PaperSources.ShapeSink() {
                public synchronized void record(PaperSources.ShapeRow row) { rows.add(row); }
            };

            /* 1) 源说不开放获取：一条链接都不许有，且为什么没得抓要留在响应账里。 */
            PaperSources.setEndpoint("europepmc", base + "/epmc/no-oa");
            rows.clear();
            PaperSources.Candidate closed = PaperSources.search("europepmc", "多孔铜 瞬态液相连接", limits, null).get(0);
            check(closed.fullTextUrl.trim().isEmpty(),
                    "isOpenAccess=N 的行不给全文链接：手里那条 publisher 的 xml 不算开放获取证据");
            check(!closed.abstractText.isEmpty(), "同一行的摘要照旧留着——不给正文不等于这条白收");
            PaperSources.ShapeRow audit = lastShape(rows, "no-open-access-evidence");
            check(audit != null && audit.entries == 1 && audit.status == 200 && audit.bodyBytes > 0,
                    "被证据挡下的候选在响应账里留了一行（形状 no-open-access-evidence，条数 1，带着检索响应的状态与字节）");

            /* 2) 开放获取：用源自己给的那条，而且拿 pdf 不拿 html。 */
            PaperSources.setEndpoint("europepmc", base + "/epmc/oa-pdf");
            rows.clear();
            PaperSources.Candidate open = PaperSources.search("europepmc", "多孔铜 瞬态液相连接", limits, null).get(0);
            check(open.fullTextUrl.equals(base + "/epmc/file"),
                    "全文链接取 fullTextUrlList 里 documentStyle=pdf 的那一条，不取同列表里的 html");
            check(PaperSources.fullText(open, limits, null).contains("界面处的孔隙率降到 4% 以下"),
                    "源自己给的链接抓得回来，正文进得了比对");
            check(rows.isEmpty() || lastShape(rows, "no-open-access-evidence") == null,
                    "有证据的候选不会被记成\u201c没证据\u201d");

            /* 3) 只有 html：实测要么 301 要么 403，留空比占额度诚实。 */
            PaperSources.setEndpoint("europepmc", base + "/epmc/oa-html");
            rows.clear();
            PaperSources.Candidate html = PaperSources.search("europepmc", "多孔铜 瞬态液相连接", limits, null).get(0);
            check(html.fullTextUrl.trim().isEmpty(), "只有 documentStyle=html 的行也不 mint 链接：那两条实测一条要跳转一条 403");
            check(lastShape(rows, "no-open-access-evidence") != null, "这一条同样在响应账里说清为什么没去抓");

            /* 4) 失败形状要带上源答了什么：假链接的 404 和要 cookie 的 403 是两种下一步。 */
            PaperSources.Candidate gone = new PaperSources.Candidate();
            gone.source.engine = "europepmc";
            gone.fullTextUrl = base + "/epmc/gone";
            rows.clear();
            check(PaperSources.fullText(gone, limits, null).isEmpty(), "404 的全文链接回空正文");
            check(lastShape(rows, "link-not-found") != null,
                    "404 记成 link-not-found：\u201c这条链接是编的\u201d第一次和\u201c这篇真的没字\u201d分得开");
            PaperSources.Candidate jump = new PaperSources.Candidate();
            jump.source.engine = "europepmc";
            jump.fullTextUrl = base + "/epmc/jump";
            rows.clear();
            PaperSources.fullText(jump, limits, null);
            check(lastShape(rows, "redirect-not-followed") != null, "301 记成 redirect-not-followed：传输层不追跳转，别再当成抓到空正文");
            check(PaperSources.fetchFailureShape(403).equals("needs-entitlement")
                            && PaperSources.fetchFailureShape(402).equals("paywalled")
                            && PaperSources.fetchFailureShape(429).equals("throttled")
                            && PaperSources.fetchFailureShape(503).equals("source-unavailable")
                            && PaperSources.fetchFailureShape(-1).equals("fetch-failed"),
                    "状态码到形状的换算：403/402/429/5xx/没答话各归一档");

            /* 5) 格式不符不许和 PDF 同价：以前 hanspub 的 PDF 和 Europe PMC 的 fullTextXML 同为 1。 */
            check(PaperSources.pdfUrlRank("https://pdf.hanspub.org/MS20170300000_52023950.pdf") == 0
                            && PaperSources.pdfUrlRank("https://some-journal.example.org/a.pdf") == 1
                            && PaperSources.pdfUrlRank("http://some-journal.example.org/a.pdf") == 2,
                    "PDF 直链照旧按站点与协议分档（白名单 0、https 1、http 2）");
            check(PaperSources.pdfUrlRank(base + "/epmc/PMC13522914/fullTextXML") == 4
                            && PaperSources.pdfUrlRank("https://x.example.org/article/abs/123.html") == 4,
                    "路径明写 XML/HTML 的\u201c全文\u201d端点单独一档 4，不再与 PDF 直链同为 1");
            check(PaperSources.pdfUrlRank(base + "/epmc/file") == 2, "路径没说格式的那条不被误降级（还是按协议给 2）");

            /* 6) 额度排队：同一条检索式下，真 PDF 先上，XML 端点不占额度。 */
            PaperSources.Candidate pdf = new PaperSources.Candidate();
            pdf.source.engine = "semantic-scholar";
            pdf.source.id = "10.0000/pdf";
            pdf.source.locator = "10.0000/pdf";
            pdf.source.title = "多孔铜 瞬态液相连接 制备";
            pdf.abstractText = "多孔铜 瞬态液相连接 制备 的 保温 时间 与 孔隙率 关系。";
            pdf.fullTextUrl = "https://pdf.hanspub.org/a.pdf";
            PaperSources.Candidate xml = new PaperSources.Candidate();
            xml.source.engine = "europepmc";
            xml.source.id = "10.0000/xml";
            xml.source.locator = "10.0000/xml";
            xml.source.title = "多孔铜 瞬态液相连接 制备";
            xml.abstractText = "多孔铜 瞬态液相连接 制备 的 保温 时间 与 孔隙率 关系。";
            xml.fullTextUrl = "https://www.ebi.ac.uk/europepmc/webservices/rest/PMC/PMC13522914/fullTextXML";
            double pdfWeight = CandidateRanker.fetchWeight(pdf), xmlWeight = CandidateRanker.fetchWeight(xml);
            check(pdfWeight > 0d && xmlWeight == 0d,
                    "排队分量：PDF 直链 " + pdfWeight + " > 0，XML 端点记 0（实测六次全 404，不再花额度）");
            ArrayList<PaperSources.Candidate> pool = new ArrayList<PaperSources.Candidate>();
            pool.add(xml);
            pool.add(pdf);
            ArrayList<PaperSources.Candidate> queue =
                    CandidateRanker.quotaOrder(CandidateRanker.rank("多孔铜 瞬态液相连接 制备", pool), 6);
            check(queue.size() == 1 && queue.get(0) == pdf,
                    "六个全文额度里排进去的是那条 PDF，XML 端点一条也不占（排到 " + queue.size() + " 条）");

            System.out.println("SUMMARY " + checks + " assertions passed; loopback-only network");
        } finally {
            server.stop(0);
        }
    }

    private static PaperSources.ShapeRow lastShape(ArrayList<PaperSources.ShapeRow> rows, String shape) {
        synchronized (rows) {
            for (int i = rows.size() - 1; i >= 0; i--)
                if (shape.equals(rows.get(i).shape)) return rows.get(i);
        }
        return null;
    }
}