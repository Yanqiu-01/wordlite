package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Built-in scan engines, per-engine depth and the optional CORE key. */
public final class EngineSettings {
    /** Engines that need no credential; CORE stays opt-in because it needs a key. */
    public static final String[] DEFAULT_ENGINES = {"cqvip", "ncpssd", "openalex", "crossref", "semantic-scholar", "europepmc", "arxiv"};
    public boolean web = true;
    public final ArrayList<String> engines = new ArrayList<String>();
    public int perEngine = 12, timeoutSeconds = 20, windows = 12;
    public String coreKey = "";
    /** Optional HTTP proxy for the built-in sources, written as host:port and empty by default:
     *  a phone on a network that resets these hosts needs one to retrieve anything at all. */
    public String proxy = "";

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
        out.proxy = proxy;
        return out;
    }

    public void validate() {
        if (perEngine < 1 || perEngine > 50 || timeoutSeconds < 5 || timeoutSeconds > 120
                || windows < 1 || windows > 24) throw new IllegalArgumentException("检索参数无效");
        if (coreKey.length() > 4096 || coreKey.indexOf('\r') >= 0 || coreKey.indexOf('\n') >= 0)
            throw new IllegalArgumentException("CORE 密钥无效");
        proxy = proxy.trim();
        if (proxy.length() > 0) {
            int colon = proxy.lastIndexOf(':');
            if (colon < 1 || colon == proxy.length() - 1 || proxy.indexOf('/') >= 0
                    || proxy.indexOf(' ') >= 0 || proxy.indexOf('@') >= 0 || proxy.indexOf("://") >= 0)
                throw new IllegalArgumentException("代理需写成 host:port");
            int port;
            try { port = Integer.parseInt(proxy.substring(colon + 1)); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("代理端口无效"); }
            if (port < 1 || port > 65535) throw new IllegalArgumentException("代理端口无效");
        }
    }

    public static String serialize(EngineSettings value) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("version", 2); out.put("web", value.web); out.put("engines", value.engines);
        out.put("perEngine", value.perEngine); out.put("timeout", value.timeoutSeconds);
        out.put("windows", value.windows); out.put("coreKey", value.coreKey);
        out.put("proxy", value.proxy);
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
            /* 1 号格式早于后来加的检索源，那些源按"新装"处理一起打开；从 2 号格式起，用户勾掉的就是勾掉了。 */
            Object version = ApiJson.path(root, "version");
            int saved = version instanceof Number ? ((Number) version).intValue() : 1;
            if (saved < 2)
                for (String engine : PaperSources.engines())
                    if (!out.engines.contains(engine)) out.engines.add(engine);
        }
        out.perEngine = integer(ApiJson.path(root, "perEngine"), out.perEngine);
        out.timeoutSeconds = integer(ApiJson.path(root, "timeout"), out.timeoutSeconds);
        out.windows = integer(ApiJson.path(root, "windows"), out.windows);
        Object key = ApiJson.path(root, "coreKey");
        out.coreKey = key instanceof String ? (String) key : "";
        Object via = ApiJson.path(root, "proxy");
        out.proxy = via instanceof String ? ((String) via).trim() : "";
        return out;
    }

    private static int integer(Object value, int fallback) {
        if (!(value instanceof Number)) return fallback;
        return ((Number) value).intValue();
    }
}
