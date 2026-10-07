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
    private static final int RETRY_DELAY_MILLIS = 700;
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
        try {
            return once(url, headers, timeoutSeconds, maxBytes, cancellation, proxy, form, contentType);
        } catch (ApiClient.Failure failure) {
            /* 手机上的代理多半是 adb reverse 出来的临时端口，线没接上的时候直连反而通。
               只有"连不上"才值得换条路重试；对端明确拒绝或限流时，再试一次只是慢。 */
            if (proxy != null && failure.status == 0 && unreachableThroughProxy(failure))
                return once(url, headers, timeoutSeconds, maxBytes, cancellation, null, form, contentType);
            if (failure.status != 429 || Thread.currentThread().isInterrupted()
                    || (cancellation != null && cancellation.cancelled())) throw failure;
            long wait = failure.retryAfterSeconds > 0
                    ? failure.retryAfterSeconds * 1000L : RETRY_DELAY_MILLIS;
            if (wait > MAX_RETRY_WAIT_MILLIS)
                throw new ApiClient.Failure("检索源限流，约 " + failure.retryAfterSeconds
                        + " 秒后恢复，本次跳过", failure.status);
            try {
                Thread.sleep(wait);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw failure;
            }
            ApiClient.Response retry = once(url, headers, timeoutSeconds, maxBytes, cancellation, proxy,
                    form, contentType);
            retry.attempts = 2;
            return retry;
        }
    }

    /** 拨不通代理和代理替我们对端谈崩了，都表现为一次没有状态码的连接失败。 */
    private static boolean unreachableThroughProxy(ApiClient.Failure failure) {
        String message = String.valueOf(failure.getMessage());
        return message.startsWith("网络连接失败") || message.startsWith("安全连接失败");
    }

    /** Only https, no redirects, and the request line never carries document text. */

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
