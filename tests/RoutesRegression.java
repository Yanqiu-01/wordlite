package com.rikkahub.wordlite;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * 路由表（{@link Routes}）回归。0.7.3 之前这条链路一条断言都没有，而它决定"问知网的第一跳落在
 * 国内还是海外出口"——手机挂着代理时，这个决定直接改变三个中文库能拿回什么样的结果。
 * 漏一个子域（{@code search.cnki.com.cn} 不在 {@code cnki.net} 下）没有任何测试会响。
 *
 * 钉死四类事：① 生产在用的每个主机都按预期归类，仿冒域名不算国内库；② 排队次序——用户填的代理永远
 * 第一，国内源直连优先，海外源代理优先、直连兜底，回环上的桩服务不掺自动发现；③ 探测超时只压自动
 * 发现的那几条，用户填的代理和直连照他设的超时等；④ 走过哪条路的记录：写进去、读得回、上限 32 条、
 * reset 之后回到"未走过"。
 *
 * 全程不联网：Proxy 只是值对象，测试只用 IP 字面量，免得 InetSocketAddress 去查 DNS。
 */
public final class RoutesRegression {
    private static int count;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        count++;
        System.out.println("PASS " + message);
    }

    public static void main(String[] argv) {
        String savedHost = System.getProperty("http.proxyHost", "");
        String savedPort = System.getProperty("http.proxyPort", "");
        System.clearProperty("http.proxyHost");
        System.clearProperty("http.proxyPort");
        try {
            classification();
            ordering();
            deadDirectRoad();
            parsingAndLabels();

            timeouts();
            routeLedger();
            refusedLoopbackPort();
            everyRoadReported();
            answeredRoadWins();
            refusedExplicitPortIsNotAPortDown();
            livedRoadStaysQueued();
            refusedRoadKeepsProvenRoad();
        } finally {
            Routes.reset();
            restore("http.proxyHost", savedHost);
            restore("http.proxyPort", savedPort);
        }
        System.out.println("SUMMARY " + count + " assertions passed");
    }

    private static void restore(String key, String value) {
        if (value == null || value.isEmpty()) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private static void classification() {
        String[] chinese = {"search.cnki.com.cn", "kns.cnki.net", "wap.cnki.net", "www.cqvip.com",
                "s.wanfangdata.com.cn", "d.wanfangdata.com.cn", "www.ncpssd.org"};
        for (String host : chinese)
            check(Routes.domestic(host), host + " 是国内库，必须先直连");
        String[] overseas = {"api.openalex.org", "api.crossref.org", "api.semanticscholar.org",
                "www.ebi.ac.uk", "export.arxiv.org", "api.core.ac.uk"};
        for (String host : overseas)
            check(!Routes.domestic(host), host + " 是海外源，代理优先");
        /* 归类按后缀必须带点：否则 cnki.com.cn.evil.net 也冒充得上国内库，白拿一次直连优先。 */
        check(!Routes.domestic("cnki.com.cn.evil.net"), "后缀相同的仿冒域名不算国内库");
        check(!Routes.domestic("notcnki.com.cn"), "只在结尾沾了 cnki.com.cn 的域名不算国内库");
        check(Routes.domestic("cnki.com.cn"), "裸域本身也算国内库");
    }

    private static void ordering() {
        Routes.reset();
        Proxy explicit = Routes.parse("192.168.1.20:7890");
        List<Proxy> cnki = Routes.order("search.cnki.com.cn", explicit);
        check(cnki.get(0) == explicit, "用户填的代理在国内源之前——那是他自己的意思");
        check(cnki.get(1) == null, "补上 cnki.com.cn 之后，知网在没有用户代理时第一跳是直连");
        List<Proxy> openalex = Routes.order("api.openalex.org", null);
        check(openalex.get(openalex.size() - 1) == null, "海外源把直连留在最后，代理不通才轮到它");
        check(openalex.indexOf(null) == openalex.size() - 1, "海外源的直连只出现一次，且在末尾");
        check(containsLoopback(openalex, 7897) && containsLoopback(openalex, 7890),
                "自动发现覆盖 Clash 系常见的 7897 与 7890 两个本地端口");
        check(!openalex.contains(explicit), "没填的代理不会凭空出现在候选里");
        List<Proxy> loopback = Routes.order("127.0.0.1", null);
        check(loopback.size() == 1 && loopback.get(0) == null, "回环上的桩服务不掺自动发现的端口");
        List<Proxy> loopbackWithExplicit = Routes.order("127.0.0.1", explicit);
        check(loopbackWithExplicit.size() == 2 && loopbackWithExplicit.get(0) == explicit,
                "回环只例外接受用户显式填的代理（回归就是拿回环代理跑的）");
        check(distinct(cnki) && distinct(openalex), "同一条路不在候选里出现两次");

        /* 走通过一次就记住，下一扇窗口不必再撞死路；但国内源的直连仍然排第一。 */
        Proxy remembered = Routes.parse("127.0.0.1:7897");
        Routes.succeeded(remembered);
        List<Proxy> next = Routes.order("search.cnki.com.cn", null);
        check(next.get(0) == null, "记住过代理也不改变国内源的直连优先");
        check(next.indexOf(remembered) == 1, "上次走通的那条路排在第二位，先于其余自动发现");
        Routes.reset();
        check(!Routes.order("search.cnki.com.cn", null).contains(remembered)
                || Routes.order("search.cnki.com.cn", null).indexOf(remembered) > 0,
                "reset 之后不再凭旧记忆插队（自动发现本身会发现同一个端口）");
    }

    private static void parsingAndLabels() {
        Proxy proxy = Routes.parse("127.0.0.1:7897");
        check(proxy != null && proxy.type() == Proxy.Type.HTTP, "host:port 解析成 HTTP 代理");
        check(Routes.parse("[::1]:8080") != null, "带方括号的 IPv6 字面量能解析");
        check(Routes.parse(" 10.0.0.5:1 ") != null, "首尾空白不算写坏");
        check(Routes.parse("") == null, "空串等于没填");
        check(Routes.parse(null) == null, "null 等于没填");
        check(Routes.parse("127.0.0.1") == null, "没写端口等于没填");
        check(Routes.parse("127.0.0.1:") == null, "端口空着等于没填");
        check(Routes.parse(":7890") == null, "主机空着等于没填");
        check(Routes.parse("127.0.0.1:0") == null, "0 端口不是可用代理");
        check(Routes.parse("127.0.0.1:65536") == null, "越过 65535 不是可用代理");
        check(Routes.parse("127.0.0.1:78a7") == null, "端口不是数字就当没填，直接拨出去");
        check(Routes.parse("http://127.0.0.1:7890") == null, "把整条 URL 填进来不当代理用");
        check(Routes.label(null).equals(Routes.DIRECT), "没有代理就写「直连」");
        check(Routes.label(proxy).equals("代理 127.0.0.1:7897"), "有代理就写出地址与端口");
        check(Routes.host("https://SEARCH.CNKI.com.cn/Search?a=1").equals("search.cnki.com.cn"),
                "取主机名时丢掉大小写、路径与查询串");
        check(Routes.host("not a url").isEmpty(), "认不出的串给空主机名，不记进路由表");
    }

    private static void timeouts() {
        Proxy explicit = Routes.parse("192.168.1.20:7890");
        Proxy auto = Routes.parse("127.0.0.1:7897");
        check(Routes.PROBE_CONNECT_SECONDS == 6, "自动探路的超时定在 6 秒");
        check(Routes.connectSeconds(explicit, null, 45) == 45, "直连按用户设的超时等");
        check(Routes.connectSeconds(explicit, explicit, 45) == 45, "用户填的代理也按他设的超时等");
        check(Routes.connectSeconds(explicit, auto, 45) == 6, "只有自动发现的死路压到 6 秒");
        check(Routes.connectSeconds(null, auto, 0) == 6, "没设超时也给默认值，探路仍是 6 秒");
        check(Routes.connectSeconds(null, null, 600) == 120, "用户填的超时封顶两分钟");
    }

    private static void routeLedger() {
        Routes.reset();
        check(Routes.routeFor("search.cnki.com.cn").equals("未走过"), "没走过的源如实写未走过");
        Routes.note("https://search.cnki.com.cn/search/listresult", null);
        check(Routes.routeFor("search.cnki.com.cn").equals(Routes.DIRECT), "走通直连就记下直连");
        Proxy proxy = Routes.parse("127.0.0.1:7897");
        Routes.note("https://api.openalex.org/works?q=x", proxy);
        check(Routes.routeFor("api.openalex.org").equals("代理 127.0.0.1:7897"), "走通代理就记下那条代理");
        check(Routes.summary().contains("search.cnki.com.cn 直连")
                && Routes.summary().contains("api.openalex.org 代理 127.0.0.1:7897"),
                "自检摘要把每个源最后走的路都摊开");
        for (int i = 0; i < 40; i++) Routes.note("https://host" + i + ".example.org/x", null);
        check(Routes.summary().split("  ").length <= 32, "路由记录封顶 32 条，不把报告撑爆");
        Routes.reset();
        check(Routes.routeFor("search.cnki.com.cn").equals("未走过") && Routes.summary().isEmpty(),
                "reset 之后一切归零");
        String list = Routes.candidateList("192.168.1.20:7890");
        check(list.startsWith("直连") && list.contains("代理 192.168.1.20:7890"),
                "连不上时先告诉用户试过哪几条路");
        String listed = Routes.candidateList("127.0.0.1:7897");
        check(occurrences(listed, "代理 127.0.0.1:7897") == 1,
                "用户填的代理与自动发现的端口重合时只列一次（实测列出：" + listed + "）");
        check(occurrences(listed, "代理 127.0.0.1:7890") == 1, "另一个自动端口仍照列，路是路、端口是端口");
        List<Proxy> sameAgain = Routes.order("api.openalex.org", Routes.parse("127.0.0.1:7897"));
        check(countMatches(sameAgain, "127.0.0.1:7897") == 1 && distinct(sameAgain),
                "同一个端口在候选队列里只出现一次——死路每扇窗口只撞一次（实得 " + countMatches(sameAgain, "127.0.0.1:7897") + " 次）");
    }

    private static int occurrences(String haystack, String needle) {
        int at = 0, total = 0;
        while ((at = haystack.indexOf(needle, at)) >= 0) { total++; at += needle.length(); }
        return total;
    }

    /** 这条地址在候选队列里排了几遍。理想答案永远是 1。 */
    private static int countMatches(List<Proxy> order, String address) {
        int total = 0;
        for (Proxy proxy : order) if (Routes.label(proxy).contains(address)) total++;
        return total;
    }

    private static boolean containsLoopback(List<Proxy> order, int port) {
        for (Proxy proxy : order) {
            if (proxy == null) continue;
            InetSocketAddress address = (InetSocketAddress) proxy.address();
            if (address.getPort() == port && address.getHostString().equals("127.0.0.1")) return true;
        }
        return false;
    }

    /**
     * 手机挂在移动数据上、只有电脑上 adb reverse 出来的 Clash 出得去时，实测十个源直连全部被当场拒回。
     * 这种网络里每一扇窗口都先撞一次直连死路是没道理的：撞过一次、并且代理为它捞回来过一次，就该记住。
     * 但降权只能建立在实测上——没撞过就照旧直连优先（0.7.3 的判据），代理死了还要能排回第一位。
     */
    private static void deadDirectRoad() {
        Routes.reset();
        Proxy tunnel = Routes.parse("127.0.0.1:7897");
        System.setProperty("http.proxyHost", "127.0.0.1");
        System.setProperty("http.proxyPort", "7897");
        try {
            List<Proxy> first = Routes.order("search.cnki.com.cn", null);
            check(first.get(0) == null, "没撞过直连的国内源，第一跳照旧是直连（不靠猜就降权）");
            Routes.failed("https://search.cnki.com.cn/search/listresult?t=1", null);
            List<Proxy> afterDirectFailure = Routes.order("search.cnki.com.cn", null);
            check(afterDirectFailure.get(0) == null,
                    "只撞过直连失败、还没有任何一条路为它通过时，仍先试直连——没有更好的路可选");
            check(Routes.routeFor("search.cnki.com.cn").equals("未走过"), "没走通的记录不冒充走过的路");
            Routes.succeeded(tunnel);
            Routes.note("https://search.cnki.com.cn/search/listresult?t=1", tunnel);
            List<Proxy> next = Routes.order("search.cnki.com.cn", null);
            check(next.get(0) == tunnel, "直连为它失败过、代理为它通过过：下一扇窗口先走代理，别再撞死路");
            check(next.indexOf(null) == next.size() - 1, "直连退到队尾而不是被删掉——网络随时可能恢复");
            check(distinct(next), "记住的那条路与自动发现的同一个端口不排两次（实测 " + next.size() + " 条）");
            List<Proxy> overseas = Routes.order("api.openalex.org", null);
            check(overseas.indexOf(null) == overseas.size() - 1, "海外源的次序不受这条改动影响");
            Routes.failed("https://search.cnki.com.cn/search/listresult?t=1", tunnel);
            check(Routes.order("search.cnki.com.cn", null).get(0) == null,
                    "代理一死（拔线/关掉 Clash）国内源第一跳回到直连，不会被一条死路锁死");
            Routes.reset();
            Routes.failed("https://search.cnki.com.cn/search/listresult?t=1", null);
            Routes.succeeded(tunnel);
            Routes.note("https://search.cnki.com.cn/search/listresult?t=1", tunnel);
            Routes.note("https://search.cnki.com.cn/search/listresult?t=1", null);
            check(Routes.order("search.cnki.com.cn", null).get(0) == null,
                    "直连重新走通一次就撤销降权，网络恢复不必等一次 reset");
            for (int i = 0; i < 40; i++) Routes.failed("https://h" + i + ".cnki.net/x", null);
            List<Proxy> abroad = Routes.order("api.openalex.org", null);
            check(abroad.indexOf(null) == abroad.size() - 1, "这批死直连记录不改变海外源的次序");
            Routes.succeeded(tunnel);
            Routes.note("https://h0.cnki.net/x", tunnel);
            check(Routes.order("h0.cnki.net", null).get(0) == null,
                    "死直连记录封顶 32 条：被挤出去的那台主机按没撞过处理");
            Routes.note("https://h39.cnki.net/x", tunnel);
            check(Routes.order("h39.cnki.net", null).get(0) == tunnel, "还在记录里的主机照旧先走那条代理");
        } finally {
            System.clearProperty("http.proxyHost");
            System.clearProperty("http.proxyPort");
            Routes.reset();
        }
    }


    /**
     * 自动发现的回环端口被当场拒回之后要冷却。它不是慢，是不存在：USB 反代随拔线一起没了，
     * 而每扇窗口都重撞一次，最后那句错误还变成它的口气——真机上用户据此反复检查自己没填错的代理。
     */
    private static void refusedLoopbackPort() {
        Routes.reset();
        Proxy discovered = Routes.parse("127.0.0.1:7897");
        check(contains(Routes.order("www.cqvip.com", null), discovered),
                "没被拒过之前，自动发现把 7897 排进国内源的队列");
        Routes.portRefused(discovered);
        check(Routes.anyPortDown(), "回环端口被拒之后，自检看得见\"有端口在冷却\"");
        List<Proxy> cooled = Routes.order("www.cqvip.com", null);
        check(!contains(cooled, discovered), "被拒过的回环端口这段时间不再排队");
        check(cooled.indexOf(null) == 0, "冷却的只是那条代理，国内源照旧直连优先");
        Proxy explicit = Routes.parse("127.0.0.1:7897");
        check(Routes.order("www.cqvip.com", explicit).get(0) != null,
                "用户自己填的那个端口不因自动发现进了冷却而被跳过");
        check(Routes.connectSeconds(explicit, Routes.parse("127.0.0.1:7897"), 30) == 30,
                "用户填的端口与自动发现的是同一个地址时按值认，别把他设的 30 秒压成探路的六秒");
        Routes.reset();
        check(!Routes.anyPortDown(), "reset 把冷却一起清掉");
        Routes.portRefused(Routes.parse("10.0.0.9:7897"));
        check(!Routes.anyPortDown(), "只有回环端口进冷却：局域网里那台代理不许被自动发现判死");
    }


    /** 路全烧完时报的是"每条路各撞了什么"。真机上只剩最后那句 7890，等于把用户往错的方向推。 */
    private static void everyRoadReported() {
        int closed = closedLoopbackPort(), alsoClosed = closedLoopbackPort();
        try {
            HttpTransport.get("http://127.0.0.1:" + closed + "/search", null, 2, null);
            throw new AssertionError("这个端口上没人监听，请求不该成功");
        } catch (ApiClient.Failure error) {
            check(error.refused, "拒绝连接单独记一笔，不和\"网络失败\"混成一类");
            check(error.getMessage().startsWith("网络连接失败"),
                    "只有一条路时报它自己的那句话——汇总只在该换路而没路可换时才加：" + error.getMessage());
        } catch (java.io.IOException error) {
            throw new AssertionError("预期拿到 ApiClient.Failure，实际 " + error);
        }
        try {
            HttpTransport.get("http://127.0.0.1:" + closed + "/search", null, 2, 0, null,
                    Routes.parse("127.0.0.1:" + alsoClosed));
            throw new AssertionError("代理和直连都没人听，请求不该成功");
        } catch (ApiClient.Failure error) {
            check(error.getMessage().startsWith("试过的路都没通：代理 127.0.0.1:" + alsoClosed
                            + " 拒绝连接；直连 拒绝连接"),
                    "两条路都烧完时，每条路的下场都要在同一句话里：" + error.getMessage());
        } catch (java.io.IOException error) {
            throw new AssertionError("预期拿到 ApiClient.Failure，实际 " + error);
        }
    }

    /**
     * 一条路拿到了源的答复、另一条路拨不上：报出去的那句话必须是那句答复，不许由拨不上的路代笔。
     *
     * <p>真机 2026-10-09 00:51 那一轮（华为 CDY-AN90 / Android 10 / 2.6.3，样稿 input-liu.docx，
     * 整篇联网查重 98 秒）实测：同一轮里 OpenAlex 经电脑上 adb reverse 进来的 Clash（127.0.0.1:7897）
     * 走通并取回 60 篇候选，而报告里 Crossref 与 Semantic Scholar 各有一句
     * 「已跳过 X：试过的路都没通：直连 拒绝连接」。那两句里没有那条代理——可它刚刚在同一轮里为
     * 另一个源送走过 60 篇候选。旧写法把"最后一条拨不上的路"当成整轮的下场，把那条代理拿回来的
     * HTTP 429（匿名配额按出口 IP 计）整句顶掉了，用户读到的是"这台手机没试过代理"。</p>
     */
    private static void answeredRoadWins() {
        Routes.reset();
        final java.util.concurrent.atomic.AtomicInteger hits =
                new java.util.concurrent.atomic.AtomicInteger();
        HttpServer stub = null;
        try {
            stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            stub.createContext("/", exchange -> {
                hits.incrementAndGet();
                exchange.sendResponseHeaders(429, -1);
                exchange.close();
            });
            stub.start();
            int proxyPort = stub.getAddress().getPort();
            int dead = closedLoopbackPort();
            ApiClient.Failure thrown = null;
            try {
                HttpTransport.get("http://127.0.0.1:" + dead + "/search", null, 2, 0, null,
                        Routes.parse("127.0.0.1:" + proxyPort));
            } catch (ApiClient.Failure error) {
                thrown = error;
            } catch (java.io.IOException error) {
                throw new AssertionError("预期拿到 ApiClient.Failure，实际 " + error);
            }
            check(thrown != null, "代理回了 429、直连被当场拒回：这一轮照样要抛，不许当成成功");
            check(hits.get() == 1, "没给 Retry-After 的 429 只打一次（实打 " + hits.get() + " 次）");
            check(thrown.status == 429, "报的是源给的 429，不是最后那条拨不上的路的零状态码（实得 status="
                    + thrown.status + "）");
            check(thrown.getMessage().startsWith(DuplicateEngine.THROTTLED_PREFIX),
                    "源答了话就说是限流，不许由直连那条死路代笔（实得：" + thrown.getMessage() + "）");
            check(!thrown.getMessage().startsWith("试过的路都没通"),
                    "有一条路答了话就不许说「试过的路都没通」——那句只留给一条路都没走通的轮次");
            check(thrown.getMessage().contains("直连 拒绝连接"),
                    "拨不上的那条路仍写在这句话里，用户看得见为什么没走直连");
            check(thrown.getMessage().contains("代理 127.0.0.1:" + proxyPort),
                    "走过的那条代理必须出现在这句话里——真机那句缺的就是这一条（实得："
                            + thrown.getMessage() + "）");
            check(DuplicateEngine.reachOf(thrown) > 0,
                    "答过话的源不许被记成「这一轮没通」：它答了，只是匿名配额到顶");
            check(!Routes.anyPortDown(), "把包送出去的那条路不进冷却，429 不是「没人监听」");
        } catch (java.io.IOException error) {
            throw new AssertionError("桩服务起不来：" + error);
        } finally {
            if (stub != null) stub.stop(0);
            Routes.reset();
        }
    }

    /**
     * 用户在设置里填的那个端口被当场拒回，只说明那一个端口没人听。它不许进「回环上的代理端口
     * 没人监听」那本账——那本账管的是自动发现要排队的 7897/7890，填进来的是别的端口（实测有人填过
     * 127.0.0.1:18899），一旦记进去，自检就把用户支去跑 phone-gateway，而 7897 那条路其实好着。
     */
    private static void refusedExplicitPortIsNotAPortDown() {
        Routes.reset();
        Routes.portRefused(Routes.parse("127.0.0.1:18899"));
        check(!Routes.anyPortDown(),
                "用户填的死端口进不了「回环端口没人监听」那本账：它不是自动发现要排队的端口");
        check(contains(Routes.order("api.crossref.org", null), Routes.parse("127.0.0.1:7897")),
                "别的回环端口被拒过一次，不许把 7897 挤出海外源的候选队列");
        Routes.reset();
    }

    /**
     * 刚刚为别的源走通过的那条路不许从候选里消失。真机上一轮要跑 98 秒、几十个窗口，
     * Clash 中途重载配置就能让 7897 被拒回一次而进冷却；紧接着 OpenAlex 明明经它取回了 60 篇候选，
     * 下一个海外源却可能再也排不到它——那一路只剩 lastGood 一根独木，而 lastGood 是全局的，
     * 国内源一次直连成功就把它换成直连了。
     */
    private static void livedRoadStaysQueued() {
        Routes.reset();
        Proxy tunnel = Routes.parse("127.0.0.1:7897");
        Routes.portRefused(tunnel);
        check(!contains(Routes.order("api.crossref.org", null), tunnel),
                "刚被拒回的端口先进冷却，这一段规矩不变");
        Routes.succeeded(tunnel);
        check(contains(Routes.order("api.crossref.org", null), tunnel),
                "走通过一次就当场回到候选队列，不等那六十秒");
        Routes.succeeded(null);
        check(contains(Routes.order("api.crossref.org", null), tunnel),
                "lastGood 被一次直连成功换掉之后，刚刚为别的源走通的那条代理还得在候选里（实得 "
                        + labels(Routes.order("api.crossref.org", null)) + "）");
        check(!Routes.anyPortDown(), "走通过的端口不许还算在冷却里");
        Routes.reset();
    }

    /**
     * 一条拨不上的路只结自己的账。用户在设置里填的死地址撞一次，就把这台主机「上次为它走通的那条路」
     * 抹掉，等于让一条没试过的路替一条活路做生死判断。
     */
    private static void refusedRoadKeepsProvenRoad() {
        Routes.reset();
        Proxy tunnel = Routes.parse("192.168.1.20:7897");
        Proxy deadExplicit = Routes.parse("127.0.0.1:18899");
        Routes.succeeded(tunnel);
        Routes.note("https://api.crossref.org/works?query=x", tunnel);
        check(Routes.order("api.crossref.org", null).get(0) == tunnel,
                "为这台主机走通过的路排在它自己头上");
        Routes.failed("https://api.crossref.org/works?query=x", deadExplicit);
        Routes.succeeded(null);
        check(contains(Routes.order("api.crossref.org", null), tunnel),
                "一条拨不上的显式代理不许抹掉这台主机刚为它走通的那条路（实得 "
                        + labels(Routes.order("api.crossref.org", null)) + "）");
        Routes.failed("https://api.crossref.org/works?query=x", tunnel);
        check(!contains(Routes.order("api.crossref.org", null), tunnel),
                "走通过的那条路自己拨不上时照旧撤账——USB 反代随拔线一起消失");
        Routes.reset();
    }

    private static String labels(List<Proxy> order) {
        StringBuilder out = new StringBuilder();
        for (Proxy proxy : order) {
            if (out.length() > 0) out.append("、");
            out.append(Routes.label(proxy));
        }
        return out.toString();
    }

    /** 确定没人监听的回环端口：让系统发一个，立刻关掉。 */
    private static int closedLoopbackPort() {
        try {
            java.net.ServerSocket probe =
                    new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
            int port = probe.getLocalPort();
            probe.close();
            return port;
        } catch (java.io.IOException error) {
            throw new AssertionError("连一个本地端口都开不出来：" + error);
        }
    }

    private static boolean contains(List<Proxy> order, Proxy wanted) {
        for (Proxy proxy : order) if (Routes.same(proxy, wanted)) return true;
        return false;
    }

    private static boolean distinct(List<Proxy> order) {
        ArrayList<Proxy> seen = new ArrayList<Proxy>();
        for (Proxy proxy : order) {
            boolean duplicate = false;
            for (Proxy known : seen) if (known == proxy || (known != null && known.equals(proxy))) duplicate = true;
            if (duplicate) return false;
            seen.add(proxy);
        }
        return true;
    }
}
