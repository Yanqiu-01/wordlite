package com.rikkahub.wordlite;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.Locale;
import java.util.Map;

/** Read-only GET for the built-in literature sources: HTTPS only, no redirects, no secrets in errors. */
public final class HttpTransport {
    public static final int MAX_BODY = 2 * 1024 * 1024, MAX_FULL_TEXT = 512 * 1024;
    /**
     * 一份开放获取 PDF 的下载上限。实测能匿名取回的中文 OA PDF 是 700 KB–3.6 MB
     * （2026-10-09，12 份：0.72/0.88/0.93/0.97/1.15/1.38/1.61/3.62 MB），6 MB 装得下最大的那份还留一档。
     * 一轮最多抓 MAX_FULL_TEXTS=10 篇，所以这是"最坏情况 60 MB"的那道闸；实测一轮平均见正文。
     */
    public static final int MAX_PDF_BODY = 6 * 1024 * 1024;
    /** Beyond this a rate limit is not a momentary crowd, and waiting would look like a hang. */
    private static final int MAX_RETRY_WAIT_MILLIS = 10000;
    /* The sources do not require it, but a UA that names the project and a home page is the
     * polite-pool convention OpenAlex and Crossref ask for, and it makes our traffic findable
     * in their logs when something of ours misbehaves. */
    static final String USER_AGENT = "WordLite document checker (+https://github.com/Yanqiu-01/wordlite)";
    private HttpTransport() { }

    public static ApiClient.Response get(String url, Map<String, String> headers, int timeoutSeconds,
                                         ApiClient.Cancellation cancellation) throws IOException {
        return get(url, headers, timeoutSeconds, MAX_BODY, cancellation, null);
    }

    public static ApiClient.Response get(String url, Map<String, String> headers, int timeoutSeconds,
                                         ApiClient.Cancellation cancellation, java.net.Proxy proxy) throws IOException {
        return get(url, headers, timeoutSeconds, MAX_BODY, cancellation, proxy);
    }

    /** A shared anonymous quota is usually a momentary crowd, not a dead end: one patient retry
     *  keeps Semantic Scholar usable, while every other failure is reported straight away. */
    public static ApiClient.Response get(String url, Map<String, String> headers, int timeoutSeconds,
                                         int maxBytes, ApiClient.Cancellation cancellation) throws IOException {
        return get(url, headers, timeoutSeconds, maxBytes, cancellation, null);
    }

    /**
     * Some mobile networks only let this traffic out through a proxy, so the caller may hand one
     * over. The URL stays HTTPS and the proxy just tunnels it, because a proxy that could read the
     * request would be no better than the network we are trying to escape.
     */
    public static ApiClient.Response get(String url, Map<String, String> headers, int timeoutSeconds,
                                         int maxBytes, ApiClient.Cancellation cancellation,
                                         java.net.Proxy proxy) throws IOException {
        return send(url, headers, timeoutSeconds, maxBytes, cancellation, proxy, null, null);
    }

    /**
     * A search form for the sources that only answer POST, under the same guardrails. They are all
     * read-only searches, so a throttle gets the same single patient retry a GET would get.
     */
    public static ApiClient.Response post(String url, String form, Map<String, String> headers, int timeoutSeconds,
                                          int maxBytes, ApiClient.Cancellation cancellation,
                                          java.net.Proxy proxy) throws IOException {
        String payload = form == null ? "" : form;
        return send(url, headers, timeoutSeconds, maxBytes, cancellation, proxy,
                payload.getBytes(StandardCharsets.UTF_8), null);
    }

    /**
     * 万方那一路的检索体是 protobuf 而不是表单，回来的也是 protobuf，所以要一条按字节写、按字节读的路。
     * 护栏和 GET/POST 完全一样：只走 HTTPS、不追重定向、响应有上限、错误里不带地址。
     */
    public static ApiClient.Response postBytes(String url, byte[] payload, String contentType,
                                               Map<String, String> headers, int timeoutSeconds,
                                               int maxBytes, ApiClient.Cancellation cancellation,
                                               java.net.Proxy proxy) throws IOException {
        return send(url, headers, timeoutSeconds, maxBytes, cancellation, proxy,
                payload == null ? new byte[0] : payload, contentType);
    }

    /** 一份下载回来的字节，外加"是不是被我们的上限掐断的"。半份 PDF 也能解析出前面几页，所以掐断不是失败。 */
    public static final class Fetched {
        public int status = -1;
        public byte[] bytes = new byte[0];
        public boolean capped;
        public long millis;
        public String via = "";
    }

    /**
     * 取一份 PDF。与 get() 的两点区别，都为 PDF 这一类材料而设：
     * 读满 maxBytes 就收工并把 capped 标出来（"响应过大"对 PDF 没有意义——截半份照样能抽出前面的正文）；
     * allowPlainHttp 放行 http 由调用方负责——期刊官网自建站只有 http 的 PDF，白名单在检索侧管着，
     * 白名单之外一律照旧必须 HTTPS。
     */
    static final int MAX_PDF_REDIRECTS = 5;
    /** 301/302/303/307/308 都是"东西在别处"。 */
    static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }
    /**
     * 把 Location 接成下一条地址：相对写法按当前地址解析，只放行 http/https，
     * 调用方没放行时不许从加密掉进不加密——白名单那件事不能换个地方被绕开。
     */
    static String follow(String from, String location, boolean allowPlainHttp) throws ApiClient.Failure {
        if (location == null || location.trim().isEmpty())
            throw new ApiClient.Failure("检索源发生重定向", 302);
        String target;
        try {
            target = new java.net.URL(new java.net.URL(from), location.trim()).toString();
        } catch (java.net.MalformedURLException error) {
            throw new ApiClient.Failure("重定向地址无效", 0);
        }
        String lower = target.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("https://")) return target;
        if (lower.startsWith("http://")) {
            if (!allowPlainHttp) throw new ApiClient.Failure("重定向要转到不加密的地址，没放行", 0);
            return target;
        }
        throw new ApiClient.Failure("重定向地址不是网页或 PDF", 0);
    }

    public static Fetched getPdf(String url, Map<String, String> headers, int timeoutSeconds, int maxBytes,
                                 ApiClient.Cancellation cancellation, java.net.Proxy proxy,
                                 boolean allowPlainHttp) throws IOException {
        int limit = maxBytes <= 0 ? MAX_PDF_BODY : Math.min(maxBytes, MAX_PDF_BODY);
        boolean[] capped = new boolean[1];
        /* 一份 PDF 的链接十有八九要跳一次：文献标识符跳到出版社、期刊官网从加密跳到不加密、
           短链跳到真页。检索那一路把重定向当错误是对的（挡人页正靠这一跳认），可取全文时
           它只是路上的一站——真机那十四篇待下的 PDF 全落在这类形状上。所以只有这一路跟跳。 */
        String at = url;
        ApiClient.Response response;
        for (int hop = 0; ; hop++) {
            try {
                response = send(at, headers, timeoutSeconds, limit, cancellation, proxy,
                        null, null, capped, allowPlainHttp);
                break;
            } catch (ApiClient.Failure error) {
                if (!isRedirect(error.status) || hop >= MAX_PDF_REDIRECTS) throw error;
                at = follow(at, error.location, allowPlainHttp);
            }
        }
        Fetched fetched = new Fetched();
        fetched.status = response.status;
        fetched.bytes = response.raw == null ? new byte[0] : response.raw;
        fetched.capped = capped[0];
        fetched.millis = response.elapsedMillis;
        fetched.via = response.via == null ? "" : response.via;
        return fetched;
    }

    private static ApiClient.Response send(String url, Map<String, String> headers, int timeoutSeconds,
                                           int maxBytes, ApiClient.Cancellation cancellation,
                                           java.net.Proxy proxy, byte[] form, String contentType)
            throws IOException {
        return send(url, headers, timeoutSeconds, maxBytes, cancellation, proxy, form, contentType, null, false);
    }

    private static ApiClient.Response send(String url, Map<String, String> headers, int timeoutSeconds,
                                           int maxBytes, ApiClient.Cancellation cancellation,
                                           java.net.Proxy proxy, byte[] form, String contentType,
                                           boolean[] cappedOut, boolean allowPlainHttp)
            throws IOException {
        /* 一条请求可能同时有直连和经电脑代理两条路，而哪条通取决于用户此刻的网络：国内库直连快，
           海外源往往非得借代理才出得去。按主机名把候选路排个先后逐条试——只有"连不上"才值得换路，
           对端明确拒绝或限流的时候换路只是慢。 */
        java.util.List<java.net.Proxy> order = Routes.order(Routes.host(url), proxy);
        ApiClient.Failure failure;

        java.net.Proxy failedVia = proxy;
        /* 每条路最后撞上了什么，一路记着。真机实测：手机挂在 5G 上时先撞直连死路、再撞两个没人监听的
           回环代理端口，而异常里只剩最后那一句 "Failed to connect to /127.0.0.1:7890"——用户读到的是
           "我代理是不是填错了"，真正的原因是这台手机在自己的网络上出不去。谁撞了什么就都列出来。 */
        StringBuilder burned = new StringBuilder();
        boolean roadDown = false;
        for (int i = 0; ; ) {
            java.net.Proxy via = order.get(i);
            try {
                ApiClient.Response reached = once(url, headers,
                        Routes.connectSeconds(proxy, via, timeoutSeconds), maxBytes, cancellation,
                        via, form, contentType, cappedOut, allowPlainHttp);
                Routes.succeeded(via);
                Routes.note(url, via);
                reached.via = Routes.label(via);
                return reached;
            } catch (ApiClient.Failure error) {
                failure = error;
                failedVia = via;
                /* 只有这条路本身拨不通才记账、才换下一条：403/429 是对方答了话，换条路也是同样答复，
                   把这种失败记成「路不通」会让下一次绕开一条其实好着的路。 */

                roadDown = error.status == 0 && routeIsDown(error);
                /* 限流是唯一一种"换条路可能就通了"的答复：OpenAlex 与 Semantic Scholar 的匿名配额按
                   出口 IP 计，2026-10-09 实测同一条 OpenAlex 请求经 Clash 出口是 429
                   （响应里明写 "counts against the free daily budget shared by everyone on your IP"，
                   Retry-After 2768~3250 秒），同一条直连是 200。403/401 不一样，那是照着 UA 与 cookie
                   拒的，换条路同样挨拒，所以这一条只对 429 开。
                   换路也不把它记成"这条路不通"——那条路本身是好的，下一次还得走它。 */
                boolean reroutable = error.status == 429;
                if (roadDown) {
                    Routes.failed(url, via);
                    if (error.refused) Routes.portRefused(via);
                    if (burned.length() > 0) burned.append("；");
                    burned.append(Routes.label(via)).append(' ').append(brief(error));
                }
                if (++i >= order.size() || (!roadDown && !reroutable)) break;
            }
        }

        if (roadDown && burned.length() > 0 && order.size() > 1) {
            /* 路是全烧完的，不是某一条坏了：报"哪几条路各撞了什么"，而不是只报最后一条的下场。
               只有一条路时不改写：那时候"请求超时"已经是全部信息，而下游按前缀分类的判据照旧要认它。 */
            ApiClient.Failure exhausted = new ApiClient.Failure("试过的路都没通：" + burned, failure.status);
            exhausted.refused = failure.refused;
            throw exhausted;
        }
        if (failure.status != 429 || Thread.currentThread().isInterrupted()
                || (cancellation != null && cancellation.cancelled())) throw failure;
        /* 对方报了时限就等它说的这么久（有上限），没报就立刻重试只是撞在同一个窗口里：
           实测 Semantic Scholar 的匿名共享配额连续五次都不带 Retry-After，七百毫秒后再打一次必然还是 429。 */
        if (failure.retryAfterSeconds <= 0)
            throw new ApiClient.Failure("检索源限流（HTTP 429），本次跳过", failure.status);
        long wait = failure.retryAfterSeconds * 1000L;
        if (wait > MAX_RETRY_WAIT_MILLIS)
            throw new ApiClient.Failure("检索源限流，约 " + failure.retryAfterSeconds
                    + " 秒后恢复，本次跳过", failure.status);
        try {
            Thread.sleep(wait);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw failure;
        }
        ApiClient.Response retry = once(url, headers, timeoutSeconds, maxBytes, cancellation, failedVia,
                form, contentType, cappedOut, allowPlainHttp);
        retry.attempts = 2;
        retry.via = Routes.label(failedVia);
        return retry;
    }

    /**
     * 这条路本身没通：代理拨不上、网络把连接掐了、TLS 谈崩、或者干脆超时。
     * 只有这一类失败才值得换一条路再试；"已取消"也是零状态码，但它换哪条路都一样，不在此列。
     */

    /** 一条路上撞见的那一句，短到能和别的路并排写在同一行里；正文永远进不了这里。 */
    private static String brief(ApiClient.Failure error) {
        if (error.refused) return "拒绝连接";
        String text = String.valueOf(error.getMessage());
        if (text.startsWith("请求超时")) return "超时";
        if (text.startsWith("安全连接失败")) return "TLS 握手失败";
        int colon = text.indexOf(": ");
        if (colon > 0 && colon < 12) text = text.substring(colon + 2);
        return text.length() <= 28 ? text : text.substring(0, 28);
    }

    private static boolean routeIsDown(ApiClient.Failure failure) {
        String message = String.valueOf(failure.getMessage());
        return message.startsWith("网络连接失败") || message.startsWith("安全连接失败")
                || message.startsWith("请求超时");
    }
    private static ApiClient.Response once(String url, Map<String, String> headers, int timeoutSeconds,
                                           int maxBytes, ApiClient.Cancellation cancellation,
                                           java.net.Proxy proxy, byte[] form, String contentType,
                                           boolean[] cappedOut, boolean allowPlainHttp)
            throws IOException {
        /* 上限的天花板：要整份响应的源最多 MAX_BODY，愿意"读满就收工"的那一路（PDF）到 MAX_PDF_BODY。 */
        int ceiling = cappedOut == null ? MAX_BODY : MAX_PDF_BODY;
        int limit = maxBytes <= 0 ? MAX_BODY : Math.min(maxBytes, ceiling);
        int seconds = timeoutSeconds <= 0 ? 20 : Math.min(timeoutSeconds, 120);
        HttpURLConnection connection = null;
        long started = System.nanoTime();
        try {
            URL target = parse(url, allowPlainHttp);
            connection = (HttpURLConnection) (proxy == null ? target.openConnection() : target.openConnection(proxy));
            if (cancellation != null) cancellation.connection(connection);
            guard(cancellation);
            connection.setConnectTimeout(seconds * 1000);
            connection.setReadTimeout(seconds * 1000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(form == null ? "GET" : "POST");
            connection.setUseCaches(false);
            if (form != null)
                connection.setRequestProperty("Content-Type", contentType == null || contentType.trim().isEmpty()
                        ? "application/x-www-form-urlencoded; charset=UTF-8" : contentType.trim());
            connection.setRequestProperty("Accept", "application/json, application/xml, text/xml, text/plain;q=0.8, */*;q=0.5");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            if (headers != null) for (Map.Entry<String, String> header : headers.entrySet()) {
                String name = header.getKey(), value = header.getValue();
                if (name == null || value == null || name.trim().isEmpty() || unsafe(name) || unsafe(value))
                    throw new ApiClient.Failure("请求头无效", 0);
                connection.setRequestProperty(name, value);
            }
            if (form != null) writeForm(connection, form, cancellation);
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                ApiClient.Failure redirect = new ApiClient.Failure("检索源发生重定向", status);
                redirect.location = connection.getHeaderField("Location");
                drain(connection, cancellation);
                throw redirect;
            }
            if (status < 200 || status >= 300) {
                ApiClient.Failure failure = new ApiClient.Failure(statusMessage(status), status);
                if (status == 429) failure.retryAfterSeconds = retryAfterSeconds(connection);
                drain(connection, cancellation);
                throw failure;
            }
            ApiClient.Response response = new ApiClient.Response();
            response.status = status;
            byte[] raw;
            try (InputStream input = connection.getInputStream()) {
                raw = read(input, limit, cancellation, cappedOut);
            }
            response.raw = raw;
            response.body = new String(raw, StandardCharsets.UTF_8);
            response.attempts = 1;
            response.elapsedMillis = (System.nanoTime() - started) / 1000000;
            return response;
        } catch (ApiClient.Failure error) {
            throw error;
        } catch (MalformedURLException error) {
            throw new ApiClient.Failure("检索地址无效", 0);
        } catch (SocketTimeoutException error) {
            guard(cancellation);
            throw new ApiClient.Failure("请求超时", 0);
        } catch (javax.net.ssl.SSLException error) {
            throw new ApiClient.Failure("安全连接失败: " + cause(error), 0);

        } catch (java.net.ProtocolException error) {
            throw new ApiClient.Failure("检索请求不受支持", 0);
        } catch (java.net.ConnectException error) {
            /* 拒绝连接单独记一笔：移动网络把出站连接掐了，和回环端口上根本没人监听，异常里是同一句
               "Failed to connect to"。前者要换路，后者要用户去把代理建起来，混在一起就只能瞎猜。 */
            guard(cancellation);
            ApiClient.Failure refused = new ApiClient.Failure("网络连接失败: " + cause(error), 0);
            refused.refused = true;
            throw refused;
        } catch (IOException error) {
            guard(cancellation);
            throw new ApiClient.Failure("网络连接失败: " + cause(error), 0);
        } finally {
            if (connection != null) connection.disconnect();
            if (cancellation != null) cancellation.connection(null);
        }
    }
    /**
     * HTTPS everywhere except the loopback fixtures, so a stub server can serve the regressions.
     * allowPlainHttp 是检索侧为"只有 http 的期刊官网 PDF"要的一条例外，白名单在它那边；
     * 放行的是这一条下载，不是一般的检索请求，也不包括任何带着本机内容的请求。
     */
    private static URL parse(String url, boolean allowPlainHttp) throws MalformedURLException, ApiClient.Failure {
        if (url == null || url.trim().isEmpty()) throw new ApiClient.Failure("检索地址无效", 0);
        URL target = new URL(url.trim());
        String protocol = target.getProtocol() == null ? "" : target.getProtocol().toLowerCase(Locale.ROOT);
        String host = target.getHost() == null ? "" : target.getHost().toLowerCase(Locale.ROOT);
        if (protocol.equals("https")) return target;
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
        if (protocol.equals("http") && (loopback || allowPlainHttp)) return target;
        throw new ApiClient.Failure("检索源必须使用 HTTPS", 0);
    }
    /** The search form is built here, never taken from the document, so its length is not a leak. */
    private static void writeForm(HttpURLConnection connection, byte[] payload,
                                  ApiClient.Cancellation cancellation) throws IOException {
        connection.setFixedLengthStreamingMode(payload.length);
        connection.setDoOutput(true);
        try (OutputStream out = connection.getOutputStream()) {
            guard(cancellation);
            out.write(payload);
            out.flush();
        }
    }

    private static boolean unsafe(String value) {
        for (int i = 0; i < value.length(); i++) { char c = value.charAt(i); if (c == '\r' || c == '\n' || c == 0) return true; }
        return false;
    }
    private static void guard(ApiClient.Cancellation cancellation) throws ApiClient.Failure {
        if (Thread.currentThread().isInterrupted() || cancellation != null && cancellation.cancelled()) throw new ApiClient.Failure("已取消", 0);
    }
    private static byte[] read(InputStream input, int limit, ApiClient.Cancellation cancellation,
                               boolean[] cappedOut) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            guard(cancellation);
            if (out.size() + count > limit) {
                /* 截断还是报错，由调用方表过态没有决定：给了 cappedOut 就是"读满为止"——
                   半份 PDF 照样能抽出前面几页；没给说明这响应必须整份，截一半只会解出一个假答案。 */
                if (cappedOut == null) throw new ApiClient.Failure("响应过大", 0);
                int room = limit - out.size();
                if (room > 0) out.write(buffer, 0, Math.min(room, count));
                cappedOut[0] = true;
                break;
            }
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
    /** A throttled source and a dead source need different replies from the report, and the
     *  status code is the only evidence left once the body has been dropped, so it stays in the
     *  wording instead of a bare "network failure". */
    private static String statusMessage(int status) {
        if (status == 429) return "检索源限流（HTTP 429），稍后重试";
        if (status == 401) return "检索源拒绝了密钥（HTTP 401）";
        if (status == 403) return "检索源拒绝访问（HTTP 403）";
        if (status >= 500) return "检索源暂时不可用（HTTP " + status + "）";
        return "检索源返回 HTTP " + status;
    }

    /**
     * How far the source asked us to wait. Only the delta-seconds form is honoured; an HTTP date
     * or garbage falls back to the fixed delay rather than trusting a clock we do not control.
     */
    private static int retryAfterSeconds(HttpURLConnection connection) {
        try {
            int seconds = Integer.parseInt(connection.getHeaderField("Retry-After").trim());
            return seconds < 0 ? 0 : Math.min(seconds, 3600);
        } catch (Exception absent) {
            return 0;
        }
    }

    /**
     * The JSSE text says which part of the handshake died, which is the difference between a trust
     * store that has never been updated, a proxy that terminates TLS, and a network that resets the
     * connection. Kept short, and it never carries a URL: the note that shows it already names the
     * source.
     */
    private static String cause(Throwable error) {
        Throwable leaf = error;
        while (leaf.getCause() != null && leaf.getCause() != leaf) leaf = leaf.getCause();
        String text = (leaf.getClass().getSimpleName() + " " + leaf.getMessage()).replaceAll("\\s+", " ").trim();
        return text.length() <= 120 ? text : text.substring(0, 120);
    }

    private static void drain(HttpURLConnection connection, ApiClient.Cancellation cancellation) {
        try (InputStream error = connection.getErrorStream()) {
            if (error == null) return;
            byte[] buffer = new byte[4096];
            int total = 0;
            while (total < 4096) {
                if (cancellation != null && cancellation.cancelled()) return;
                int count = error.read(buffer);
                if (count < 0) return;
                total += count;
            }
        } catch (IOException ignored) { }
    }
}
