package com.rikkahub.wordlite;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ChallengeSolver 回归。两条路各自要验的事不同：
 * attach 验 cookie 的复用与过期，fetch 验"渲染完的那一页"的复用、过期与字数闸门；
 * 再加一条不配地址时一个请求都不许多发。
 */
public final class ChallengeSolverRegression {
    private static int count;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String WITH_COOKIES = "{\"status\":\"ok\",\"solution\":{\"userAgent\":\"FAKE/1.0\","
            + "\"cookies\":[{\"name\":\"cf_clearance\",\"value\":\"abc\"}]}}";

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        count++;
        System.out.println("PASS " + message);
    }

    public static void main(String[] args) throws Exception {
        // 这台电脑上可能真开着 FlareSolverr：先关掉回环自动发现，否则没配地址那些断言会打到真服务上。
        ChallengeSolver.overrideLoopback(0, 1);
        address();
        protocol();
        parsing();
        reuse();
        expiry();
        rendering();
        failures();
        legacyKey();
        detailUrls();
        budget();
        autodiscover();
        ChallengeSolver.restoreLoopback();
        System.out.println("SUMMARY " + count + " solver assertions");
    }

    private static void address() {
        check(ChallengeSolver.endpoint("").length() == 0, "留空就是不走过验证：地址解出来还是空");
        check(ChallengeSolver.endpoint("192.168.1.20:8191").equals("http://192.168.1.20:8191"),
                "只写 host:port 也认：" + ChallengeSolver.endpoint("192.168.1.20:8191"));
        check(ChallengeSolver.endpoint("http://pc.local:8191/solve/").equals("http://pc.local:8191/solve/"),
                "写全地址时路径段留着：" + ChallengeSolver.endpoint("http://pc.local:8191/solve/"));
        check(ChallengeSolver.endpoint("not a url").length() == 0, "带空格的废地址一律当没配");
        check(ChallengeSolver.hostOf("https://search.cnki.com.cn/search/listresult")
                .equals("search.cnki.com.cn"), "会话按 host 分键");
    }

    private static void protocol() {
        String body = ChallengeSolver.bodyFor("https://www.cqvip.com/search?q=x", 60000);
        check(body.contains("\"cmd\":\"request.get\"") && body.contains("\"maxTimeout\":60000")
                        && body.contains("https://www.cqvip.com/search?q=x"), "命令体带 cmd、maxTimeout 与 URL：" + body);
    }

    private static void parsing() {
        try {
            ChallengeSolver.Session session = ChallengeSolver.parse(
                    "{\"status\":\"ok\",\"solution\":{\"userAgent\":\"FAKE/1.0\",\"cookies\":["
                            + "[\"x\"],{\"name\":\"cf_clearance\",\"value\":\"abc\"},"
                            + "{\"name\":\"s\",\"value\":\"1\"},{\"name\":\"\",\"value\":\"skip\"}]}}", "x");
            check(session.userAgent.equals("FAKE/1.0") && session.cookie.equals("cf_clearance=abc; s=1"),
                    "只把有名有值的 cookie 拼进请求头：" + session.cookie);
        } catch (java.io.IOException error) {
            check(false, "正常响应必须解得开：" + error.getMessage());
        }
        try {
            ChallengeSolver.parse("{\"status\":\"error\",\"message\":\"导航超时\"}", "x");
            check(false, "status 不是 ok 必须报错，不能回一个空会话");
        } catch (java.io.IOException error) {
            check(String.valueOf(error.getMessage()).contains("导航超时"),
                    "解不到的原因要能直接进报告：" + error.getMessage());
        }
        try {
            ChallengeSolver.Session bare = ChallengeSolver.parse("{\"status\":\"ok\",\"solution\":{\"cookies\":[]}}", "x");
            check(bare.cookie.length() == 0, "一个 cookie 都不给不再算错：万方与维普就是这样，那一页只能走 fetch");
        } catch (java.io.IOException error) {
            check(false, "空 cookie 不该抛异常：" + error.getMessage());
        }
    }

    private static void reuse() throws Exception {
        Stub stub = Stub.start(WITH_COOKIES);
        try {
            ChallengeSolver.reset();
            LinkedHashMap<String, String> headers = new LinkedHashMap<String, String>();
            check(!ChallengeSolver.attach("", stub.url(), headers, 20, null, null) && stub.calls.get() == 0,
                    "没配地址时一个请求都不许多发");
            check(ChallengeSolver.attach(stub.base, stub.url(), headers, 20, null, null)
                            && stub.calls.get() == 1, "配了地址就要去解一次");
            check("FAKE/1.0".equals(headers.get("User-Agent")) && "cf_clearance=abc".equals(headers.get("Cookie")),
                    "cookie 与浏览器标识一起贴上去：" + headers.get("Cookie") + " / " + headers.get("User-Agent"));
            LinkedHashMap<String, String> again = new LinkedHashMap<String, String>();
            check(ChallengeSolver.attach(stub.base, stub.url(), again, 20, null, null)
                            && stub.calls.get() == 1 && "cf_clearance=abc".equals(again.get("Cookie")),
                    "同一个站点 30 分钟内复用会话，不再开第二次浏览器");
            check(ChallengeSolver.attach(stub.base, "https://search.cnki.com.cn/search/listresult",
                            new LinkedHashMap<String, String>(), 20, null, null) && stub.calls.get() == 2,
                    "换站点必须重新解一次");
            check(ChallengeSolver.summary().contains("已过验证"), "解成功要在报告里留一行：" + ChallengeSolver.summary());
        } finally {
            stub.stop();
        }
    }

    private static void expiry() throws Exception {
        Stub stub = Stub.start("{\"status\":\"ok\",\"solution\":{\"userAgent\":\"NEW/2.0\","
                + "\"cookies\":[{\"name\":\"cf_clearance\",\"value\":\"fresh\"}]}}");
        try {
            ChallengeSolver.reset();
            ChallengeSolver.park(new ChallengeSolver.Session("www.cqvip.com", "OLD/1.0",
                    "cf_clearance=stale", System.currentTimeMillis() - ChallengeSolver.SESSION_TTL_MILLIS - 1L));
            LinkedHashMap<String, String> headers = new LinkedHashMap<String, String>();
            check(ChallengeSolver.attach(stub.base, stub.url(), headers, 20, null, null)
                            && stub.calls.get() == 1 && "cf_clearance=fresh".equals(headers.get("Cookie")),
                    "超过 30 分钟的会话不许接着用");
            ChallengeSolver.park(new ChallengeSolver.Session("www.cqvip.com", "OLD/1.0", "cf_clearance=stale",
                    System.currentTimeMillis()));
            ChallengeSolver.invalidate(stub.url());
            check(ChallengeSolver.attach(stub.base, stub.url(), new LinkedHashMap<String, String>(),
                    20, null, null) && stub.calls.get() == 2, "会话作废之后下一扇窗口重新解");
        } finally {
            stub.stop();
        }
    }

    /** 万方、维普不发 cookie，值钱的是渲染完的那一页：这条路单独验复用、过期与闸门。 */
    private static void rendering() throws Exception {
        Stub stub = Stub.start("{\"status\":\"ok\",\"solution\":{\"userAgent\":\"UA/1.0\",\"cookies\":[],"
                + "\"response\":\"<html><body>混凝土抗压强度试验结果与数值模拟吻合</body></html>\"}}");
        try {
            ChallengeSolver.reset();
            LinkedHashMap<String, String> headers = new LinkedHashMap<String, String>();
            check(!ChallengeSolver.attach(stub.base, stub.url(), headers, 20, null, null)
                            && stub.calls.get() == 1 && headers.get("Cookie") == null,
                    "不发 cookie 的站点在 attach 这条路上回 false，且不许偷偷改请求头");
            check(ChallengeSolver.summary().contains("不发 cookie"),
                    "这一条要在报告里说清为什么要换一条路：" + ChallengeSolver.summary());
            String page = ChallengeSolver.fetch(stub.base, stub.url(), 20, null, null);
            check(page != null && page.contains("混凝土抗压强度"), "fetch 拿到渲染完的那一页（" + stub.calls.get() + " 次调用）");
            check(ChallengeSolver.pagesFetched() == 1, "取回几页要能数出来：" + ChallengeSolver.pagesFetched());
            check(ChallengeSolver.fetch(stub.base, stub.url(), 20, null, null) != null
                            && stub.calls.get() == 2,
                    "同一个 URL 的渲染页 30 分钟内复用，不重开浏览器（attach 与 fetch 各解过一次）");
            ChallengeSolver.parkPage(stub.url(), new ChallengeSolver.Session("www.cqvip.com", "UA", "",
                    "<html>旧页</html>", System.currentTimeMillis() - ChallengeSolver.SESSION_TTL_MILLIS - 1L));
            check(String.valueOf(ChallengeSolver.fetch(stub.base, stub.url(), 20, null, null))
                            .contains("混凝土") && stub.calls.get() == 3,
                    "过期的渲染页不许接着喂下一轮");
            check(ChallengeSolver.fetch("", stub.url(), 20, null, null) == null, "没配地址时 fetch 直接回 null");
        } finally {
            stub.stop();
        }
    }

    private static void failures() throws Exception {
        Stub down = Stub.start("{\"status\":\"error\",\"message\":\"Cloudflare 5 秒内没解开\"}");
        try {
            ChallengeSolver.reset();
            LinkedHashMap<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("User-Agent", "keep-me");
            check(!ChallengeSolver.attach(down.base, down.url(), headers, 20, null, null),
                    "解不到时 attach 回 false，调用方照旧按被挡记账");
            check("keep-me".equals(headers.get("User-Agent")) && headers.get("Cookie") == null,
                    "解不到就不许改请求头：原来那套原样发出去");
            check(ChallengeSolver.summary().contains("Cloudflare 5 秒内没解开"),
                    "为什么没过要原话进报告：" + ChallengeSolver.summary());
            check(ChallengeSolver.fetch(down.base, down.url(), 20, null, null) == null, "同一条坏服务在 fetch 这条路也回 null");
        } finally {
            down.stop();
        }
        ChallengeSolver.reset();
        check(ChallengeSolver.summary().length() == 0 && ChallengeSolver.cachedSessions() == 0
                        && ChallengeSolver.pagesFetched() == 0,
                "beginPass 之后账本清空，上一轮的话不许留到这一轮");
    }

    /** 详情页只许开这三家：拿浏览器去解海外 API 只会白白吃掉一轮里那几次额度。 */
    /** 早年文档里那个叫 data 的旧写法也得读得动：换一台服务不能集体失明。 */
    private static void legacyKey() {
        try {
            ChallengeSolver.Session old = ChallengeSolver.parse(
                    "{\"status\":\"ok\",\"data\":{\"userAgent\":\"OLD/1.0\",\"cookies\":[{\"name\":\"a\",\"value\":\"1\"}]}}", "x");
            check(old.cookie.equals("a=1"), "data 那个旧名字也接受：" + old.cookie);
        } catch (java.io.IOException error) {
            check(false, "data 写法不许报错：" + error.getMessage());
        }
    }

    private static void detailUrls() {
        check(PaperSources.browserDetailUrl(candidate("wanfang",
                "https://d.wanfangdata.com.cn/periodical/jsjgcyyy202103005")).length() > 0, "万方的详情页收");
        check(PaperSources.browserDetailUrl(candidate("cqvip", "https://www.cqvip.com/doc/journal/7103817796"))
                .length() > 0, "维普的详情页收");
        check(PaperSources.browserDetailUrl(candidate("cnki", "https://wap.cnki.net/touch/web/Journal/Article/X.html"))
                .length() > 0, "知网的详情页收");
        check(PaperSources.browserDetailUrl(candidate("openalex",
                "https://api.openalex.org/works/W2741809833")).length() == 0, "海外 API 的 URL 不收");
        check(PaperSources.browserDetailUrl(candidate("wanfang", "wanfang")).length() == 0,
                "题录里没有可点的 URL 就不开浏览器");
        String shell = "<html><head><script>var _ts=\"set\";if(x){location=1}</script>"
                + "<style>a{color:#000}</style></head><body>Just a moment... 安全验证</body></html>";
        check(PaperSources.readableChinese(PaperSources.readablePage(shell)) < 20, "壳页剥完只剩几个字："
                + PaperSources.readablePage(shell).replace('\n', ' '));
        StringBuilder real = new StringBuilder("<html><body>");
        for (int i = 0; i < 40; i++) real.append("混凝土抗压强度与养护龄期的关系试验研究。");
        real.append("</body></html>");
        check(PaperSources.readableChinese(PaperSources.readablePage(real.toString())) >= 600,
                "内容页剥完留下的汉字数：" + PaperSources.readableChinese(PaperSources.readablePage(real.toString())));
        /* 两段脚本夹着的正文一个不许丢：万方那张 292,620 字的详情页就是被这种连跳吃成 0 字的。 */
        StringBuilder cascade = new StringBuilder("<html><head><script>var _ts=\"set\";</SCRIPT></head><body>");
        for (int i = 0; i < 30; i++) cascade.append("本研究以港珠澳大桥沉管隧道为工程背景开展验证。");
        cascade.append("<script>window.__INIT__={html:\"<div>\"};</script>");
        cascade.append("<STYLE>.x{color:red}</STYLE>");
        for (int i = 0; i < 20; i++) cascade.append("结论表明掺合料掺量对抗裂强度影响显著。");
        cascade.append("<p>差异显著 P&lt;0.05，样本量 n=128，n<500 时不成立。</p></body></html>");
        String cascadeText = PaperSources.readablePage(cascade.toString());
        check(PaperSources.readableChinese(cascadeText) >= 1000,
                "脚本之间与大写闭合之后留下的汉字数：" + PaperSources.readableChinese(cascadeText));
        check(cascadeText.contains("掺合料掺量"), "第二段脚本之后的正文必须还在");
        check(!cascadeText.contains("__INIT__") && !cascadeText.contains("color:red"), "脚本与样式里的字不许冒充正文");
        check(cascadeText.contains("0.05") && cascadeText.contains("128") && cascadeText.contains("时不成立"),
                "正文里的转义小于号与裸小于号都不许带走后面的话：" + cascadeText.replace('\n', ' '));

    }

    /** 检索页不许把这一轮的浏览器额度吃光：剩下的必须留给详情页，否则整轮只剩 128 字摘要预览。 */
    private static void budget() throws Exception {
        Stub stub = Stub.start("{\"status\":\"ok\",\"solution\":{\"userAgent\":\"UA/1.0\",\"cookies\":["
                + "{\"name\":\"cf_clearance\",\"value\":\"abc\"}],\"response\":\"<html><body>正文</body></html>\"}}");
        try {
            ChallengeSolver.reset();
            for (int i = 0; i < ChallengeSolver.MAX_SOLVE_PER_PASS; i++)
                ChallengeSolver.attach(stub.base, "https://site" + i + ".example/search/list",
                        new LinkedHashMap<String, String>(), 20, null, null);
            check(stub.calls.get() == ChallengeSolver.MAX_SOLVE_FOR_SEARCH,
                    "检索页每轮最多开 " + ChallengeSolver.MAX_SOLVE_FOR_SEARCH + " 次浏览器，实际 " + stub.calls.get());
            check(ChallengeSolver.MAX_SOLVE_PER_PASS > ChallengeSolver.MAX_SOLVE_FOR_SEARCH,
                    "详情页那一路的额度必须比检索页多，否则正文永远排不上");
            check(ChallengeSolver.summary().contains("剩下的留给详情页"),
                    "被检索页那道线挡下时要说出剩下留给谁：" + ChallengeSolver.summary());
            String page = ChallengeSolver.fetch(stub.base, "https://d.wanfangdata.com.cn/periodical/x0",
                    20, null, null);
            check(page != null && page.contains("正文"), "检索页吃满之后详情页照样开得开浏览器：" + page);
            for (int i = 1; i < 8; i++)
                ChallengeSolver.fetch(stub.base, "https://d.wanfangdata.com.cn/periodical/x" + i, 20, null, null);
            check(stub.calls.get() == ChallengeSolver.MAX_SOLVE_PER_PASS,
                    "总额度封顶不许被突破：实际 " + stub.calls.get() + " 次，上限 "
                            + ChallengeSolver.MAX_SOLVE_PER_PASS + " 次");
        } finally {
            stub.stop();
        }
    }

    /**
     * 手机自己跑不了浏览器，值钱的是电脑上那一台：设置留空时试一眼回环，有人听就用，
     * 没人听当场冷却，绝不为一台不存在的服务反复花连接时间。
     */
    /**
     * 手机自己跑不了浏览器，值钱的是电脑上那一台：设置留空时试一眼回环，有人听就用，
     * 没人听当场冷却，绝不为一台不存在的服务反复花连接时间。
     */
    private static void autodiscover() throws Exception {
        Stub stub = Stub.start(WITH_COOKIES);
        java.net.ServerSocket spare = new java.net.ServerSocket(0);
        int sparePort = spare.getLocalPort();
        spare.close();
        java.net.ServerSocket late = null;
        try {
            int port = stub.server.getAddress().getPort();
            ChallengeSolver.overrideLoopback(port, 300);
            check(("http://127.0.0.1:" + port).equals(ChallengeSolver.resolve("")),
                    "设置留空也能找到回环上那一台：" + ChallengeSolver.resolve(""));
            ChallengeSolver.reset();
            LinkedHashMap<String, String> headers = new LinkedHashMap<String, String>();
            check(ChallengeSolver.attach("", stub.url(), headers, 20, null, null)
                            && stub.calls.get() == 1 && "cf_clearance=abc".equals(headers.get("Cookie")),
                    "留空时检索请求照样贴上过验证的 cookie（服务由回环自动发现）");
            check("http://127.0.0.1:18899".equals(ChallengeSolver.resolve("127.0.0.1:18899")),
                    "用户自己填的地址永远压过自动发现");
            ChallengeSolver.overrideLoopback(sparePort, 300);
            check(ChallengeSolver.resolve("").length() == 0, "回环上没人听就回空串，检索照旧只按摘要比");
            check(ChallengeSolver.autoDownUntil() > System.currentTimeMillis(),
                    "没人听过这一次要记下冷却时间：" + ChallengeSolver.autoDownUntil());
            late = new java.net.ServerSocket(sparePort);
            check(ChallengeSolver.resolve("").length() == 0,
                    "冷却期内即使有人刚站起来也不再探第二次");
            ChallengeSolver.overrideLoopback(sparePort, 300);
            check(("http://127.0.0.1:" + sparePort).equals(ChallengeSolver.resolve("")),
                    "冷却过了就重新排队：拔线又插回来、FlareSolverr 刚开起来都算");
        } finally {
            if (late != null) late.close();
            ChallengeSolver.restoreLoopback();
            stub.stop();
        }
    }

    private static PaperSources.Candidate candidate(String engine, String locator) {
        PaperSources.Candidate candidate = new PaperSources.Candidate();
        candidate.source.engine = engine;
        candidate.source.locator = locator;
        return candidate;
    }

    /** 一台假的过验证服务：只答 /v1，把收到的次数数下来。 */
    private static final class Stub {
        final HttpServer server;
        final String base;
        final AtomicInteger calls;

        private Stub(HttpServer server, String base, AtomicInteger calls) {
            this.server = server; this.base = base; this.calls = calls;
        }

        static Stub start(String answer) throws Exception {
            final byte[] payload = answer.getBytes(UTF8);
            final AtomicInteger counter = new AtomicInteger();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", new HttpHandler() {
                public void handle(HttpExchange exchange) {
                    try {
                        counter.incrementAndGet();
                        drain(exchange.getRequestBody());
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, payload.length);
                        OutputStream body = exchange.getResponseBody();
                        body.write(payload);
                        body.close();
                    } catch (Exception ignored) {
                    }
                }
            });
            server.start();
            return new Stub(server, "http://127.0.0.1:" + server.getAddress().getPort(), counter);
        }

        String url() { return "https://www.cqvip.com/search?q=x"; }

        void stop() { server.stop(0); }

        private static void drain(InputStream input) throws Exception {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int read = input.read(buffer); read > 0; read = input.read(buffer)) sink.write(buffer, 0, read);
            input.close();
        }
    }
}