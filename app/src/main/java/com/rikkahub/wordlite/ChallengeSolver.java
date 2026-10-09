package com.rikkahub.wordlite;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 过人机验证：FlareSolverr 客户端。
 *
 * <p>知网、万方、维普这一类站点挡人的时候，回的不是内容而是一层验证壳页（那种壳
 * {@code PaperSources.challengeOf} 认得出来）。手机上解不了它——那一层要真的执行 JS 才过得去。
 * 所以这一条路交给跑在电脑上的 FlareSolverr：它开一个真浏览器把那一页过掉，把<b>渲染完的那一页</b>
 * 连同 cookie 与浏览器标识一起交回来。手机自己不装浏览器，只当客户端。
 *
 * <p>两条用法分开，因为它们各自解决不同的实测问题（{@code docs/endpoints-access.md} 第二节）：
 * <ul>
 *   <li>{@link #attach} —— 只借它给的 cookie 与浏览器标识，之后仍由本机自己发那个请求。
 *       Cloudflare 那一类发 cf_clearance 的站点吃这一条；</li>
 *   <li>{@link #fetch} —— 直接用它渲染完的页面字节。万方、维普这类<b>一个 cookie 都不发</b>、
 *       内容全靠前端二次请求填的站点只能吃这一条：2026-10-09 活体量到，同一张万方详情页裸 HTTP
 *       只有 494 个可读汉字，走它拿到 3,331 个（完整摘要 + 万方自己从全文生成的"全文精要" + 参考文献表）。</li>
 * </ul>
 *
 * <p>三条规矩写在明面上：一个站点/一个 URL 的会话 30 分钟内复用；一轮最多开
 * {@link #MAX_SOLVE_PER_PASS} 次浏览器；没配地址就一切照旧，绝不因为缺它而少发一个请求。
 */
public final class ChallengeSolver {
    /** FlareSolverr 的命令接口固定挂在 /v1。 */
    public static final String API_PATH = "/v1";
    /** 一次过验证最多等多久：要加载页面、跑 JS，给足 60 秒。 */
    public static final int MAX_TIMEOUT_MILLIS = 60000;
    /** 会话与渲染页算旧的门槛。cf_clearance 绑出口 IP 与浏览器标识，寿命就是这个量级，取保守的一头。 */
    public static final long SESSION_TTL_MILLIS = 30 * 60 * 1000L;
    /** 一轮里最多开几次浏览器：过验证很贵，不能把整轮请求额度花在开浏览器上。 */
    public static final int MAX_SOLVE_PER_PASS = 6;
    /**
     * 检索页最多占掉其中几次。检索渲染回来只是一张结果列表，真正能比对的正文在详情页那一头：
     * 2026-10-09 活体量到一整轮 0% 就是这么来的——三渲染额度全被检索页花光，16 篇候选
     * 一篇正文都没捞着，只剩下 128 字的摘要预览可比。
     */
    public static final int MAX_SOLVE_FOR_SEARCH = 2;

    /** 渲染页留在内存里的上限：比一次检索响应的上限还大一档，再大就该落盘了。 */
    public static final int MAX_RENDERED = 2 * 1024 * 1024;

    /** 一次过验证换回来的东西：浏览器标识 + cookie +（可选）渲染完的页面。 */
    public static final class Session {
        public final String host;
        public final String userAgent;
        public final String cookie;
        public final String rendered;
        /** 测试要能造一个"已经很旧"的会话出来，所以时间戳可由调用方给。 */
        public final long solvedAt;

        public Session(String host, String userAgent, String cookie, long solvedAt) {
            this(host, userAgent, cookie, "", solvedAt);
        }

        public Session(String host, String userAgent, String cookie, String rendered, long solvedAt) {
            this.host = host == null ? "" : host;
            this.userAgent = userAgent == null ? "" : userAgent;
            this.cookie = cookie == null ? "" : cookie;
            this.rendered = rendered == null ? "" : rendered;
            this.solvedAt = solvedAt;
        }

        boolean fresh(long now) { return now - solvedAt < SESSION_TTL_MILLIS; }

        /** 贴请求头：cookie 与浏览器标识一起贴，只贴一半等于没过验证。 */
        public void applyTo(Map<String, String> headers) {
            if (headers == null) return;
            if (cookie.length() > 0) headers.put("Cookie", cookie);
            if (userAgent.length() > 0) headers.put("User-Agent", userAgent);
        }
    }

    private static final ConcurrentHashMap<String, Session> SESSIONS = new ConcurrentHashMap<String, Session>();
    private static final ConcurrentHashMap<String, Session> PAGES = new ConcurrentHashMap<String, Session>();
    private static final ArrayList<String> LOG = new ArrayList<String>();
    private static int solvedThisPass;
    private static int pagesFetched;

    private ChallengeSolver() { }

    /** 下一轮开始：丢掉上一轮的注记与开浏览器的次数，会话与渲染页照旧复用。 */
    public static synchronized void beginPass() {
        LOG.clear();
        solvedThisPass = 0;
        pagesFetched = 0;
    }

    /** 自检与单测用的全量清空。 */
    public static synchronized void reset() {
        SESSIONS.clear();
        PAGES.clear();
        beginPass();
    }

    /** 这一轮要不要在报告里说一句：一句都没有就回空串，报告不添噪音。 */
    public static synchronized String summary() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < LOG.size(); i++) {
            if (i > 0) out.append("；");
            out.append(LOG.get(i));
        }
        return out.toString();
    }

    private static synchronized void log(String line) {
        if (line != null && line.length() > 0 && LOG.size() < 8) LOG.add(line);
    }

    /**
     * 领一次开浏览器的额度。取不到额度的一方自己收摊，不许偷偷多开。
     *
     * @param detail  true = 详情页那一路，能用满 {@link #MAX_SOLVE_PER_PASS}；
     *                false = 检索页那一路，最多占 {@link #MAX_SOLVE_FOR_SEARCH} 次，
     *                剩下的必须留给详情页，否则整轮比的就只有摘要预览。
     */
    private static synchronized boolean spend(boolean detail) {
        if (solvedThisPass >= ceiling(detail)) return false;
        solvedThisPass++;
        return true;
    }

    private static int ceiling(boolean detail) {
        return detail ? MAX_SOLVE_PER_PASS : Math.min(MAX_SOLVE_PER_PASS, MAX_SOLVE_FOR_SEARCH);
    }

    /** 挡下这一页的是哪一道线：检索页那道与总额度那道不是同一句话。 */
    private static synchronized String starved(boolean detail) {
        return detail ? "这一轮开浏览器的次数已用完（上限 " + MAX_SOLVE_PER_PASS + " 次）"
                : "检索页这一路的浏览器额度已用完（每轮 " + ceiling(false) + " 次，剩下的留给详情页）";
    }

    /** 过验证服务在电脑上的默认端口：FlareSolverr 的默认值，USB 反代也指到这里。 */
    public static final int LOOPBACK_PORT = 8191;
    /** 回环上探过一次没人听，多久之内不再探。USB 反代随拔线一起消失，每扇窗口重探一次太贵。 */
    static final long AUTO_PROBE_COOLDOWN_MILLIS = 5 * 60 * 1000L;
    static final int AUTO_PROBE_CONNECT_MILLIS = 400;
    /** 回归夹具注入点：跑完必须还原，做法参照 DuplicateEngine.searchMillis。 */
    static int loopbackProbePort = LOOPBACK_PORT, autoProbeConnectMillis = AUTO_PROBE_CONNECT_MILLIS;
    private static long autoDownUntil;
    private static String autoHit = "";

    /**
     * 检索与详情页真正要用的服务地址：用户在设置里填了就用他的，留空时试一眼回环上那一台。
     *
     * <p>手机自己跑不了浏览器，值钱的是电脑上那一台，而 {@code tools/phone-gateway.ps1} 本来就把
     * {@code adb reverse tcp:8191} 建好了——那之后应用里这一格还空着就等于视而不见。端口上有人在听
     * 就用它，没人听记下时间冷却五分钟，检索照旧只按摘要比，一分钟也不为它多等。</p>
     */
    public static String resolve(String raw) {
        String explicit = endpoint(raw);
        if (explicit.length() > 0) return explicit;
        long now = System.currentTimeMillis();
        synchronized (ChallengeSolver.class) {
            if (autoHit.length() > 0) return autoHit;
            if (now < autoDownUntil) return "";
        }
        String found = listening() ? "http://127.0.0.1:" + loopbackProbePort : "";
        synchronized (ChallengeSolver.class) {
            if (found.length() > 0) autoHit = found;
            else autoDownUntil = System.currentTimeMillis() + AUTO_PROBE_COOLDOWN_MILLIS;
        }
        return found;
    }

    private static boolean listening() {
        java.net.Socket socket = new java.net.Socket();
        try {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", loopbackProbePort),
                    Math.max(1, autoProbeConnectMillis));
            return true;
        } catch (IOException error) {
            return false;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 探听过而已，关不上也不影响结论。
            }
        }
    }

    /** 回归夹具：把冷却与命中的记忆清掉，并把探测端口换成假服务的端口。 */
    static synchronized void overrideLoopback(int port, int connectMillis) {
        loopbackProbePort = port;
        autoProbeConnectMillis = connectMillis;
        autoDownUntil = 0L;
        autoHit = "";
    }

    static synchronized void restoreLoopback() {
        loopbackProbePort = LOOPBACK_PORT;
        autoProbeConnectMillis = AUTO_PROBE_CONNECT_MILLIS;
        autoDownUntil = 0L;
        autoHit = "";
    }

    static synchronized long autoDownUntil() {
        return autoDownUntil;
    }

    /**
     * 把设置里那一格变成能用的地址。允许只写 host:port（跟代理那一格同一个手感），
     * 也允许写全 http://host:8191/。空的一律回空串 = 这条路不走。
     */
    public static String endpoint(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() == 0) return "";
        if (value.indexOf("://") < 0) value = "http://" + value;
        int schemeEnd = value.indexOf("://") + 3;
        int slash = value.indexOf('/', schemeEnd);
        String head = slash < 0 ? value : value.substring(0, slash);
        String tail = slash < 0 ? "" : value.substring(slash);
        if (head.substring(schemeEnd).length() == 0 || head.indexOf(' ') >= 0
                || head.indexOf('@') >= 0) return "";
        return head + tail;
    }

    /** 一个 URL 的 host，用来当会话的键；解不出来回空串。 */
    public static String hostOf(String url) {
        String value = url == null ? "" : url.trim();
        int schemeEnd = value.indexOf("://");
        if (schemeEnd < 0) return "";
        int end = value.indexOf('/', schemeEnd + 3);
        String host = end < 0 ? value.substring(schemeEnd + 3) : value.substring(schemeEnd + 3, end);
        int at = host.lastIndexOf('@');
        if (at >= 0) host = host.substring(at + 1);
        int colon = host.indexOf(':');
        return (colon < 0 ? host : host.substring(0, colon)).toLowerCase(Locale.ROOT);
    }

    /** FlareSolverr v1 的命令体。session 留空 = 每次新开一个，不复用它内部的浏览器会话。 */
    public static String bodyFor(String url, int maxTimeoutMillis) {
        return "{\"cmd\":\"request.get\",\"url\":" + ApiJson.quote(url == null ? "" : url)
                + ",\"maxTimeout\":" + maxTimeoutMillis + ",\"session\":null}";
    }

    /** 解一次响应成会话（不含渲染页）。 */
    public static Session parse(String json, String host) throws IOException {
        return session(json, host, false);
    }

    private static Session session(String json, String host, boolean keepBody) throws IOException {
        Object root;
        try { root = ApiJson.parse(json == null ? "" : json); }
        catch (RuntimeException error) { throw new IOException("过验证响应不是 JSON"); }
        if (!"ok".equals(ApiJson.path(root, "status"))) {
            Object message = ApiJson.path(root, "message");
            throw new IOException("过验证没成：" + (message == null ? "没答 status=ok" : String.valueOf(message)));
        }
        /* 活体量过：2026-10-09 本机那台服务把答复装在 solution 里（cookies、headers、response、
           status、url、userAgent 六样）；早年文档写的是 data，两个名字都接受，哪个里面有东西用哪个。 */
        Object data = ApiJson.path(root, "solution");
        if (data == null) data = ApiJson.path(root, "data");
        String agent = string(ApiJson.path(data, "userAgent"));
        List<?> cookies = ApiJson.path(data, "cookies") instanceof List
                ? (List<?>) ApiJson.path(data, "cookies") : null;
        StringBuilder jar = new StringBuilder();
        if (cookies != null)
            for (Object item : cookies) {
                if (!(item instanceof Map)) continue;
                String name = string(((Map<?, ?>) item).get("name"));
                String value = string(((Map<?, ?>) item).get("value"));
                if (name.length() == 0 || value.length() == 0) continue;
                if (jar.length() > 0) jar.append("; ");
                jar.append(name).append('=').append(value);
                if (jar.length() > 8192) break;
            }
        String body = "";
        if (keepBody) {
            String raw = string(ApiJson.path(data, "response"));
            body = raw.length() > MAX_RENDERED ? raw.substring(0, MAX_RENDERED) : raw;
        }
        return new Session(host, agent, jar.toString(), body, System.currentTimeMillis());
    }

    /**
     * 让验证站点的请求头带上过了验证的那一套：有新鲜会话就贴，没有就先解一次。
     * 回 true 表示这一条请求确实带上了会话头。万方、维普那一类一个 cookie 都不发的站点
     * 在这条路上永远回 false——它们要走 {@link #fetch}，原因会记进注记。
     */
    public static boolean attach(String endpoint, String targetUrl, Map<String, String> headers,
                                 int timeoutSeconds, java.net.Proxy via,
                                 ApiClient.Cancellation cancellation) {
        String api = resolve(endpoint);
        if (api.length() == 0) return false;
        String host = hostOf(targetUrl);
        if (host.length() == 0 || headers == null) return false;
        long now = System.currentTimeMillis();
        Session session = SESSIONS.get(host);
        if (session != null && !session.fresh(now)) {
            SESSIONS.remove(host);
            session = null;
        }
        if (session == null) {
            if (!spend(false)) {
                log(host + " 的验证没解：" + starved(false));
                return false;
            }
            try {
                session = session(post(api, targetUrl, timeoutSeconds, via, cancellation), host, false);
            } catch (IOException error) {
                log(host + " 的验证没过：" + message(error));
                return false;
            }
            if (session.cookie.length() == 0) {
                log(host + " 过了验证但不发 cookie，本机重发拿不到同一页（改用浏览器取回的那一页）");
                return false;
            }
            SESSIONS.put(host, session);
            log(host + " 已过验证（cookie " + countPairs(session.cookie) + " 条，30 分钟内复用）");
        }
        session.applyTo(headers);
        return true;
    }

    /**
     * 用它渲染完的页面字节。成功回那段 HTML（可能没有 cookie，万方/维普就是这样），
     * 失败或这一轮浏览器额度用完回 null——调用方照旧按摘要比对，不许把壳页当正文入库。
     */
    public static String fetch(String endpoint, String url, int timeoutSeconds,
                               java.net.Proxy via, ApiClient.Cancellation cancellation) {
        String api = resolve(endpoint);
        if (api.length() == 0 || url == null || url.length() == 0) return null;
        Session cached = PAGES.get(url);
        long now = System.currentTimeMillis();
        if (cached != null && cached.fresh(now) && cached.rendered.length() > 0) return cached.rendered;
        if (cached != null) PAGES.remove(url);
        if (!spend(true)) {
            log("浏览器取页没排队上：" + starved(true));
            return null;
        }
        try {
            Session session = session(post(api, url, timeoutSeconds, via, cancellation), hostOf(url), true);
            pagesFetched++;
            PAGES.put(url, session);
            if (session.cookie.length() > 0) SESSIONS.put(session.host, session);
            log(hostOf(url) + " 用浏览器取回 " + session.rendered.length() + " 字节页面");
            return session.rendered;
        } catch (IOException error) {
            log(hostOf(url) + " 的页面没取回来：" + message(error));
            return null;
        }
    }

    private static String post(String api, String targetUrl, int timeoutSeconds,
                               java.net.Proxy via, ApiClient.Cancellation cancellation) throws IOException {
        LinkedHashMap<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Content-Type", "application/json");
        return HttpTransport.post(api + API_PATH, bodyFor(targetUrl, MAX_TIMEOUT_MILLIS), headers,
                Math.max(timeoutSeconds, MAX_TIMEOUT_MILLIS / 1000 + 5),
                HttpTransport.MAX_BODY, cancellation, via).body;
    }

    /** 会话过期或被站点重新挡住时丢掉，下一扇窗口会重新解一次。 */
    public static void invalidate(String url) {
        String host = hostOf(url);
        if (host.length() > 0) SESSIONS.remove(host);
        if (url != null) PAGES.remove(url);
    }

    /** 自检可以放一个指定时间戳的会话进来，用来验 30 分钟那一道线。 */
    static void park(Session session) {
        if (session == null || session.host.length() == 0) return;
        SESSIONS.put(session.host, session);
    }

    /** 自检可以按 URL 放一份渲染页进来（验的是 fetch 那条路的复用与过期）。 */
    static void parkPage(String url, Session session) {
        if (url != null && session != null) PAGES.put(url, session);
    }

    /** 现在缓存着什么：给自检看，不给用户看。 */
    public static int cachedSessions() { return SESSIONS.size(); }

    public static int cachedPages() { return PAGES.size(); }

    /** 这一轮真取回来几页渲染完的页面：报告用它决定那句"只回摘要"还要不要说 */
    public static synchronized int pagesFetched() { return pagesFetched; }

    private static String message(IOException error) {
        return error.getMessage() == null ? "没答话" : error.getMessage();
    }

    private static int countPairs(String cookie) {
        int n = cookie == null || cookie.length() == 0 ? 0 : 1;
        for (int i = 0; cookie != null && i < cookie.length(); i++) if (cookie.charAt(i) == ';') n++;
        return n;
    }

    private static String string(Object value) {
        return value instanceof String ? (String) value : "";
    }
}