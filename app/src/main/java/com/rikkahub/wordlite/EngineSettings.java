package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Built-in scan engines, per-engine depth and the optional CORE key. */
public final class EngineSettings {
    /** Engines that need no credential; CORE stays opt-in because it needs a key. */
    public static final String[] DEFAULT_ENGINES = {"openalex", "crossref", "semantic-scholar", "europepmc", "arxiv"};
    public boolean web = true;
    public final ArrayList<String> engines = new ArrayList<String>();
    public int perEngine = 12, timeoutSeconds = 20, windows = 12;
    public String coreKey = "";

    public EngineSettings() {
        for (String engine : DEFAULT_ENGINES) engines.add(engine);
    }

    public EngineSettings copy() {
        EngineSettings out = new EngineSettings();
        out.web = web;
        out.engines.clear();
        for (String engine : engines) out.engines.add(engine);
        out.perEngine = perEngine;
        out.timeoutSeconds = timeoutSeconds;
        out.windows = windows;
        out.coreKey = coreKey;
        return out;
    }

    public void validate() {
        if (perEngine < 1 || perEngine > 50 || timeoutSeconds < 5 || timeoutSeconds > 120
                || windows < 1 || windows > 24) throw new IllegalArgumentException("检索参数无效");
        if (coreKey.length() > 4096 || coreKey.indexOf('\r') >= 0 || coreKey.indexOf('\n') >= 0)
            throw new IllegalArgumentException("CORE 密钥无效");
    }

    public static String serialize(EngineSettings value) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("version", 1); out.put("web", value.web); out.put("engines", value.engines);
        out.put("perEngine", value.perEngine); out.put("timeout", value.timeoutSeconds);
        out.put("windows", value.windows); out.put("coreKey", value.coreKey);
        return ApiJson.stringify(out);
    }

    public static EngineSettings deserialize(String text) {
        Object root = ApiJson.parse(text);
        if (!(root instanceof Map)) throw new IllegalArgumentException("检索设置无效");
        EngineSettings out = new EngineSettings();
        out.web = !Boolean.FALSE.equals(ApiJson.path(root, "web"));
        Object list = ApiJson.path(root, "engines");
        if (list instanceof List) {
            out.engines.clear();
            for (Object item : (List<?>) list)
                if (item instanceof String && ((String) item).length() > 0) out.engines.add((String) item);
        }
        out.perEngine = integer(ApiJson.path(root, "perEngine"), out.perEngine);
        out.timeoutSeconds = integer(ApiJson.path(root, "timeout"), out.timeoutSeconds);
        out.windows = integer(ApiJson.path(root, "windows"), out.windows);
        Object key = ApiJson.path(root, "coreKey");
        out.coreKey = key instanceof String ? (String) key : "";
        return out;
    }

    private static int integer(Object value, int fallback) {
        if (!(value instanceof Number)) return fallback;
        return ((Number) value).intValue();
    }
}
