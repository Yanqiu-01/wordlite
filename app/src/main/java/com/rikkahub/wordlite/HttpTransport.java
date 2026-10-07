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

    private static ApiClient.Response send(String url, Map<String, String> headers, int timeoutSeconds,
                                           int maxBytes, ApiClient.Cancellation cancellation,
                                           java.net.Proxy proxy, byte[] form, String contentType)
            throws IOException {
        /* 一条请求可能同时有直连和经电脑代理两条路，而哪条通取决于用户此刻的网络：国内库直连快，
           海外源往往非得借代理才出得去。按主机名把候选路排个先后逐条试——只有"连不上"才值得换路，
           对端明确拒绝或限流的时候换路只是慢。 */
        java.util.List<java.net.Proxy> order = Routes.order(Routes.host(url), proxy);
        ApiClient.Failure failure;
        java.net.Proxy failedVia = proxy;
        for (int i = 0; ; ) {
            java.net.Proxy via = order.get(i);
            try {
                ApiClient.Response reached = once(url, headers,
                        Routes.connectSeconds(proxy, via, timeoutSeconds), maxBytes, cancellation,
                        via, form, contentType);
                Routes.succeeded(via);
                Routes.note(url, via);
                reached.via = Routes.label(via);
                return reached;
            } catch (ApiClient.Failure error) {
                failure = error;
                failedVia = via;
                if (++i >= order.size() || error.status != 0 || !routeIsDown(error)) break;
            }
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
                form, contentType);
        retry.attempts = 2;
        retry.via = Routes.label(failedVia);
        return retry;
    }

    /**
     * 这条路本身没通：代理拨不上、网络把连接掐了、TLS 谈崩、或者干脆超时。
     * 只有这一类失败才值得换一条路再试；"已取消"也是零状态码，但它换哪条路都一样，不在此列。
     */
    private static boolean routeIsDown(ApiClient.Failure failure) {
        String message = String.valueOf(failure.getMessage());
        return message.startsWith("网络连接失败") || message.startsWith("安全连接失败")
                || message.startsWith("请求超时");
    }
    private static ApiClient.Response once(String url, Map<String, String> headers, int timeoutSeconds,
                                           int maxBytes, ApiClient.Cancellation cancellation,
                                           java.net.Proxy proxy, byte[] form, String contentType)
            throws IOException {
        int limit = maxBytes <= 0 ? MAX_BODY : Math.min(maxBytes, MAX_BODY);
        int seconds = timeoutSeconds <= 0 ? 20 : Math.min(timeoutSeconds, 120);
        HttpURLConnection connection = null;
        long started = System.nanoTime();
        try {
            URL target = parse(url);
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
            if (status >= 300 && status < 400) throw new ApiClient.Failure("检索源发生重定向", status);
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
                raw = read(input, limit, cancellation);
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
        } catch (IOException error) {
            guard(cancellation);
            throw new ApiClient.Failure("网络连接失败: " + cause(error), 0);
        } finally {
            if (connection != null) connection.disconnect();
            if (cancellation != null) cancellation.connection(null);
        }
    }
    /** HTTPS everywhere except the loopback fixtures, so a stub server can serve the regressions. */
    private static URL parse(String url) throws MalformedURLException, ApiClient.Failure {
        if (url == null || url.trim().isEmpty()) throw new ApiClient.Failure("检索地址无效", 0);
        URL target = new URL(url.trim());
        String protocol = target.getProtocol() == null ? "" : target.getProtocol().toLowerCase(Locale.ROOT);
        String host = target.getHost() == null ? "" : target.getHost().toLowerCase(Locale.ROOT);
        if (protocol.equals("https")) return target;
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
        if (protocol.equals("http") && loopback) return target;
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
    private static byte[] read(InputStream input, int limit, ApiClient.Cancellation cancellation) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            guard(cancellation);
            if (out.size() + count > limit) throw new ApiClient.Failure("响应过大", 0);
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
