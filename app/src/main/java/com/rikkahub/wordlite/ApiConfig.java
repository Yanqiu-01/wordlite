package com.rikkahub.wordlite;

import java.util.LinkedHashMap;
import java.util.Map;

/** User supplied transport/templates/mappings only; no built-in server or credential. */
public final class ApiConfig {
    public enum Service { CHECK, REWRITE }
    public enum Mode { JSON, TEXT, FORM, MULTIPART }
    public String url = "", key = "", method = "POST";
    public Mode mode = Mode.JSON;
    public final LinkedHashMap<String, String> headers = new LinkedHashMap<String, String>();
    public String bodyTemplate = "{\"text\":{text}}";
    public String textField = "text", fileField = "file";
    public int timeoutSeconds = 30, retries = 0;
    public boolean rateFraction;
    public boolean codePointOffsets;
    public final LinkedHashMap<String, String> mappings = new LinkedHashMap<String, String>();
    public final java.util.ArrayList<String> terms = new java.util.ArrayList<String>();
    public ApiConfig() {
        mappings.put("rate", "data.rate"); mappings.put("fragments", "data.fragments");
        mappings.put("text", "text"); mappings.put("source", "source");
        mappings.put("start", "start"); mappings.put("end", "end");
        mappings.put("time", "data.detected_at"); mappings.put("rewrite", "data.text");
        mappings.put("suggestions", "data.suggestions"); mappings.put("suggestionText", "text");
    }
    public void validate() {
        java.net.URI uri;
        try { uri = java.net.URI.create(url); } catch (Exception error) { throw new IllegalArgumentException("API 地址无效"); }
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("API 地址无效");
        if (!method.matches("GET|POST|PUT|PATCH|DELETE")) throw new IllegalArgumentException("请求方式无效");
        if (timeoutSeconds < 1 || timeoutSeconds > 300 || retries < 0 || retries > 3) throw new IllegalArgumentException("超时或重试次数无效");
        if (bodyTemplate.length() > 128 * 1024 || key.length() > 16384 || headers.size() > 100 || terms.size() > 1000
                || key.indexOf('\r') >= 0 || key.indexOf('\n') >= 0)
            throw new IllegalArgumentException("请求配置无效");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (!header.getKey().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || header.getValue().contains("\r") || header.getValue().contains("\n"))
                throw new IllegalArgumentException("请求头无效");
            if (header.getKey().equalsIgnoreCase("Host") || header.getKey().equalsIgnoreCase("Content-Length")
                    || header.getKey().equalsIgnoreCase("Connection")) throw new IllegalArgumentException("请求头无效");
        }
        if (!textField.matches("[A-Za-z0-9_.-]+") || !fileField.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("上传字段无效");
        for (String path : mappings.values()) if (path.length() > 1024) throw new IllegalArgumentException("响应字段映射无效");
        for (String term : terms) if (term.length() > 1000) throw new IllegalArgumentException("保留术语过长");
    }
}
