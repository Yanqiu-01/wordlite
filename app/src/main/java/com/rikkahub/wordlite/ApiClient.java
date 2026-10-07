package com.rikkahub.wordlite;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Explicit user-requested HTTP calls only; platform TLS validation and no secret logging. */
public final class ApiClient {
    public static final int MAX_RESPONSE = 8 * 1024 * 1024, MAX_UPLOAD = 32 * 1024 * 1024;
    public interface Cancellation {
        boolean cancelled();
        default void connection(HttpURLConnection value) { }
    }
    public static final class Task implements Cancellation {
        private volatile boolean cancelled;
        private volatile HttpURLConnection connection;
        public boolean cancelled() { return cancelled; }
        public void connection(HttpURLConnection value) { connection = value; if (cancelled && value != null) value.disconnect(); }
        public void cancel() { cancelled = true; HttpURLConnection active = connection; if (active != null) active.disconnect(); }
    }
    public static final class Response {
        public int status;
        public String body = "";
        /** 同一段响应的原始字节：万方的 gRPC-web 响应不是文本，得先按字节解完再谈编码。 */
        public byte[] raw = new byte[0];
        public long elapsedMillis;
        public int attempts;
    }
    public static final class Failure extends IOException {
        public final int status;
        /** Retry-After in whole seconds from a 429, or 0 when the source did not say. */
        public int retryAfterSeconds;
        public Failure(String message, int status) { super(message); this.status = status; }
    }
    private ApiClient() { }
    public static Response execute(ApiConfig config, String text, byte[] file, String fileName, Cancellation cancellation) throws IOException {
        config.validate();
        if (text == null) text = "";
        if (text.length() > MAX_UPLOAD || text.getBytes(StandardCharsets.UTF_8).length > MAX_UPLOAD
                || file != null && file.length > MAX_UPLOAD) throw new Failure("内容过大", 0);
        if (file != null && config.mode != ApiConfig.Mode.MULTIPART) throw new Failure("请选择文档上传请求类型", 0);
        String idempotency = UUID.randomUUID().toString();
        Prepared request = prepare(config, text, file, fileName, idempotency);
        if (request.body.length > MAX_UPLOAD) throw new Failure("内容过大", 0);
        long started = System.nanoTime();
        Failure last = null;
        for (int attempt = 0; attempt <= config.retries; attempt++) {
            if (Thread.currentThread().isInterrupted() || cancellation != null && cancellation.cancelled()) throw new Failure("已取消", 0);
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(request.url).openConnection();
                if (cancellation != null) cancellation.connection(connection);
                if (cancellation != null && cancellation.cancelled()) throw new Failure("已取消", 0);
                connection.setConnectTimeout(config.timeoutSeconds * 1000); connection.setReadTimeout(config.timeoutSeconds * 1000);
                connection.setInstanceFollowRedirects(false); connection.setRequestMethod(config.method);
                connection.setUseCaches(false);
                for (Map.Entry<String, String> header : config.headers.entrySet())
                    connection.setRequestProperty(header.getKey(), header.getValue().replace("{key}", config.key));
                if (!config.key.isEmpty() && !contains(config.headers, "Authorization")) connection.setRequestProperty("Authorization", "Bearer " + config.key);
                if (!contains(config.headers, "Content-Type")) connection.setRequestProperty("Content-Type", request.contentType);
                if (!contains(config.headers, "Accept")) connection.setRequestProperty("Accept", "application/json");
                if (!contains(config.headers, "Idempotency-Key")) connection.setRequestProperty("Idempotency-Key", idempotency);
                if (!config.method.equals("GET")) {
                    connection.setDoOutput(true); connection.setFixedLengthStreamingMode(request.body.length);
                    try (java.io.OutputStream output = connection.getOutputStream()) { output.write(request.body); }
                }
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) throw new Failure("API 地址发生重定向", status);
                if (status < 200 || status >= 300) throw new Failure("HTTP " + status, status);
                String body;
                try (InputStream input = connection.getInputStream()) { body = read(input, cancellation); }
                Response response = new Response(); response.status = status; response.body = body;
                response.attempts = attempt + 1; response.elapsedMillis = (System.nanoTime() - started) / 1000000;
                return response;
            } catch (Failure error) {
                last = error;
                if (!(error.status == 408 || error.status == 429 || error.status >= 500) || attempt == config.retries) throw error;
            } catch (java.net.SocketTimeoutException error) {
                last = new Failure("请求超时", 0); if (attempt == config.retries) throw last;
            } catch (javax.net.ssl.SSLException error) { throw new Failure("安全连接失败", 0); }
            catch (java.net.ProtocolException error) { throw new Failure("请求方式不受支持", 0); }
            catch (IOException error) {
                last = new Failure("网络连接失败", 0); if (attempt == config.retries) throw last;
            } finally {
                if (connection != null) connection.disconnect();
                if (cancellation != null) cancellation.connection(null);
            }
            try { Thread.sleep(Math.min(2000, 250L << attempt)); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new Failure("已取消", 0); }
        }
        throw last == null ? new Failure("请求失败", 0) : last;
    }
    private static String read(InputStream input, Cancellation cancellation) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) >= 0) {
            if (Thread.currentThread().isInterrupted() || cancellation != null && cancellation.cancelled()) throw new Failure("已取消", 0);
            if (out.size() > MAX_RESPONSE - count) throw new Failure("响应过大", 0); out.write(buffer, 0, count);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
    static final class Prepared { String url, contentType; byte[] body; }
    static Prepared prepare(ApiConfig config, String text, byte[] file, String fileName, String id) throws IOException {
        Prepared prepared = new Prepared(); prepared.url = config.url;
        if (config.method.equals("GET")) {
            if (file != null) throw new Failure("GET不支持文档上传", 0);
            prepared.url += (config.url.contains("?") ? '&' : '?') + URLEncoder.encode(config.textField, "UTF-8") + '=' + URLEncoder.encode(text, "UTF-8");
            prepared.contentType = "application/json"; prepared.body = new byte[0]; return prepared;
        }
        switch (config.mode) {
            case JSON:
                String value = ApiTemplate.json(config.bodyTemplate, text, config.key, fileName);
                prepared.body = value.getBytes(StandardCharsets.UTF_8); prepared.contentType = "application/json; charset=utf-8"; break;
            case TEXT:
                prepared.body = text.getBytes(StandardCharsets.UTF_8); prepared.contentType = "text/plain; charset=utf-8"; break;
            case FORM:
                prepared.body = (URLEncoder.encode(config.textField, "UTF-8") + '=' + URLEncoder.encode(text, "UTF-8")).getBytes(StandardCharsets.UTF_8);
                prepared.contentType = "application/x-www-form-urlencoded; charset=utf-8"; break;
            default:
                String boundary = "WordLite" + id.replace("-", ""); ByteArrayOutputStream body = new ByteArrayOutputStream();
                write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + config.textField + "\"\r\n\r\n" + text + "\r\n");
                if (file != null) {
                    String name = fileName == null ? "document.docx" : fileName.replaceAll("[\\r\\n\"\\\\]", "_");
                    write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + config.fileField + "\"; filename=\"" + name
                            + "\"\r\nContent-Type: application/vnd.openxmlformats-officedocument.wordprocessingml.document\r\n\r\n");
                    body.write(file); write(body, "\r\n");
                }
                write(body, "--" + boundary + "--\r\n"); prepared.body = body.toByteArray();
                prepared.contentType = "multipart/form-data; boundary=" + boundary; break;
        }
        return prepared;
    }
    private static boolean contains(Map<String, String> headers, String name) {
        for (String header : headers.keySet()) if (name.equalsIgnoreCase(header)) return true; return false;
    }
    private static void write(ByteArrayOutputStream out, String value) throws IOException { out.write(value.getBytes(StandardCharsets.UTF_8)); }
}
