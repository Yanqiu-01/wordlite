package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Schema-neutral response mapped by paths configured for each service. */
public final class ApiResult {
    public static final class Fragment {
        public int start = -1, end = -1;
        public String text = "", source = "";
    }
    public static final class Check {
        public double rate;
        public String detectedAt = "";
        public long elapsedMillis;
        public final ArrayList<Fragment> fragments = new ArrayList<Fragment>();
    }
    private ApiResult() { }
    public static Check check(ApiConfig config, ApiClient.Response response, String submitted) {
        Object root = ApiJson.parse(response.body); Object rawRate = mapped(root, config, "rate");
        if (rawRate == null) throw new IllegalArgumentException("重复率字段缺失");
        String rate = String.valueOf(rawRate).trim(); boolean percent = rate.endsWith("%");
        if (percent) rate = rate.substring(0, rate.length() - 1).trim();
        double value;
        try { value = Double.parseDouble(rate); } catch (NumberFormatException error) { throw new IllegalArgumentException("重复率无效"); }
        if (!percent && config.rateFraction) value *= 100;
        if ((Double.isNaN(value) || Double.isInfinite(value)) || value < 0 || value > 100) throw new IllegalArgumentException("重复率无效");
        Check result = new Check(); result.rate = value; result.elapsedMillis = response.elapsedMillis;
        Object time = mapped(root, config, "time"); result.detectedAt = time == null ? ReviewManager.now() : String.valueOf(time);
        Object fragments = mapped(root, config, "fragments");
        if (fragments != null && !(fragments instanceof List)) throw new IllegalArgumentException("相似片段字段无效");
        if (fragments instanceof List) for (Object item : (List<?>) fragments) {
            if (result.fragments.size() >= 5000) throw new IllegalArgumentException("相似片段过多");
            Fragment fragment = new Fragment(); fragment.text = string(mapped(item, config, "text"));
            Object source = mapped(item, config, "source");
            fragment.source = source instanceof Map || source instanceof List ? ApiJson.stringify(source) : string(source);
            fragment.start = integer(mapped(item, config, "start")); fragment.end = integer(mapped(item, config, "end"));
            if (config.codePointOffsets && fragment.start >= 0 && fragment.end >= fragment.start) {
                int points = submitted.codePointCount(0, submitted.length());
                if (fragment.end <= points) {
                    fragment.start = submitted.offsetByCodePoints(0, fragment.start);
                    fragment.end = submitted.offsetByCodePoints(0, fragment.end);
                } else fragment.start = fragment.end = -1;
            }
            if (fragment.start < 0 || fragment.end <= fragment.start || fragment.end > submitted.length()
                    || !fragment.text.isEmpty() && !submitted.substring(fragment.start, fragment.end).equals(fragment.text)) {
                fragment.start = fragment.text.isEmpty() ? -1 : submitted.indexOf(fragment.text);
                fragment.end = fragment.start < 0 ? -1 : fragment.start + fragment.text.length();
            } else if (fragment.text.isEmpty()) fragment.text = submitted.substring(fragment.start, fragment.end);
            result.fragments.add(fragment);
        }
        return result;
    }
    public static ArrayList<String> suggestions(ApiConfig config, ApiClient.Response response) {
        Object root = ApiJson.parse(response.body); Object array = mapped(root, config, "suggestions");
        ArrayList<String> result = new ArrayList<String>();
        if (array instanceof List) for (Object item : (List<?>) array) {
            String text = item instanceof String ? (String) item : string(mapped(item, config, "suggestionText"));
            if (!text.trim().isEmpty() && !result.contains(text)) result.add(text);
            if (result.size() >= 50) break;
        }
        String text = string(mapped(root, config, "rewrite"));
        if (!text.trim().isEmpty() && !result.contains(text)) result.add(0, text);
        if (result.isEmpty()) throw new IllegalArgumentException("改写字段缺失");
        return result;
    }
    private static Object mapped(Object root, ApiConfig config, String key) {
        String path = config.mappings.get(key); return path == null || path.isEmpty() ? null : ApiJson.path(root, path);
    }
    private static String string(Object value) { return value instanceof String ? (String) value : ""; }
    private static int integer(Object value) {
        try {
            double n = value instanceof Number ? ((Number) value).doubleValue() : Double.parseDouble(String.valueOf(value));
            if ((Double.isNaN(n) || Double.isInfinite(n)) || n != Math.rint(n) || n < 0 || n > Integer.MAX_VALUE) return -1;
            return (int) n;
        } catch (Exception error) { return -1; }
    }
}
