package com.rikkahub.wordlite;

import java.io.IOException;

/** Expand only tokens from the original template; inserted text is never templated again. */
public final class ApiTemplate {
    private ApiTemplate() { }
    public static String json(String template, String text, String key, String fileName) throws IOException {
        StringBuilder result = new StringBuilder(); boolean inString = false, escape = false;
        for (int at = 0; at < template.length(); ) {
            String token = null, replacement = null;
            for (String candidate : new String[]{"{text}", "{content}", "{key}", "{filename}"}) {
                if (template.startsWith(candidate, at)) { token = candidate; break; }
            }
            if (token != null) {
                replacement = "{key}".equals(token) ? key : "{filename}".equals(token) ? fileName : text;
                if (replacement == null) replacement = "";
                String encoded = ApiJson.quote(replacement);
                result.append(inString ? encoded.substring(1, encoded.length() - 1) : encoded);
                at += token.length(); escape = false; continue;
            }
            char c = template.charAt(at++); result.append(c);
            if (escape) escape = false;
            else if (inString && c == '\\') escape = true;
            else if (c == '"') inString = !inString;
        }
        if (result.length() > ApiClient.MAX_UPLOAD) throw new ApiClient.Failure("内容过大", 0);
        try { ApiJson.parse(result.toString()); }
        catch (IllegalArgumentException error) { throw new ApiClient.Failure("请求体 JSON 无效", 0); }
        return result.toString();
    }
}
