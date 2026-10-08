package com.rikkahub.wordlite;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;

/** Lazy, process-local font cache. No font file is opened by initialize(). */
public final class FontManager {
    private static Context context;
    private static final HashMap<String, Typeface> faces = new HashMap<String, Typeface>();
    private static final HashMap<String, FontScriptMetrics> metrics = new HashMap<String, FontScriptMetrics>();
    /**
     * 装载失败的字库，到什么时候才可以再试一次。
     *
     * 这里不永久拉黑。Typeface.createFromAsset 失败最常见的原因是那一刻内存不够——随包的中文脸
     * 单张 4-20 MB，而以前它们在这台手机的 APK 里是压缩存放的，装载时要先在原生内存里整张展开——
     * 缓一缓就过得去。以前的写法是一失败就记进 unavailable 从此不再尝试：华文新魏的标题会在整个
     * 进程剩下的时间里安静地画成宋体，界面上一个字都不说。改成隔一段时间重试，并把失败原因留下，
     * 让 字体 面板能说清现在到底用的哪张脸。
     */
    private static final HashMap<String, Long> retryAfter = new HashMap<String, Long>();
    /** 每张脸最后一次装载失败的原因，好让界面如实说出来。 */
    private static final LinkedHashMap<String, String> loadErrors
            = new LinkedHashMap<String, String>();
    static final long LOAD_RETRY_MILLIS = 20000L;
    private static final HashMap<String, Boolean> coverage = new HashMap<String, Boolean>();
    private static final HashMap<String, PdfTrueType> cmaps = new HashMap<String, PdfTrueType>();
    private static boolean covers(String path, int codePoint) {
        if (context == null || path == null) return false;
        try {
            PdfTrueType font = cmaps.get(path);
            if (font == null) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                try (InputStream input = context.getAssets().open(path)) {
                    byte[] buffer = new byte[16384]; int count;
                    while ((count = input.read(buffer)) > 0) out.write(buffer, 0, count);
                }
                font = new PdfTrueType(out.toByteArray(), path, ""); cmaps.put(path, font);
            }
            return font.glyph(codePoint) != 0;
        } catch (Exception error) { return false; }
    }
    private static final java.util.IdentityHashMap<Typeface, String> derived = new java.util.IdentityHashMap<Typeface, String>();
    public static synchronized void associate(Typeface face, Typeface original) {
        String path = pathFor(original); if (path != null) derived.put(face, path);
    }

    private FontManager() { }

    public static synchronized void initialize(Context value) {
        if (value != null) context = value.getApplicationContext();
    }

    public static synchronized int loadedCount() { return faces.size(); }
    public static synchronized String pathFor(Typeface face) {
        if (derived.containsKey(face)) return derived.get(face);
        for (java.util.Map.Entry<String, Typeface> entry : faces.entrySet())
            if (entry.getValue() == face || entry.getValue().equals(face)) return entry.getKey();
        return null;
    }

    public static synchronized Typeface load(String path) {
        if (path == null || context == null) return null;
        Typeface cached = faces.get(path);
        if (cached != null) return cached;
        Long until = retryAfter.get(path);
        if (until != null) {
            if (until.longValue() > android.os.SystemClock.elapsedRealtime()) return null;
            retryAfter.remove(path);
        }
        try {
            Typeface made = Typeface.createFromAsset(context.getAssets(), path);
            if (made == null) throw new IllegalStateException("createFromAsset 返回空");
            faces.put(path, made);
            loadErrors.remove(path);
            return made;
        } catch (RuntimeException error) {
            noteFailure(path, error);
            return null;
        } catch (OutOfMemoryError error) {
            /* 一张 20 MB 的脸在内存紧张时能把进程顶崩。宁可这一屏退回系统字库并在 字体 面板里说清，
               也不让用户写着写着闪退——但这是 Error，只认这一种，别的照旧往上抛。 */
            noteFailure(path, error);
            return null;
        }
    }

    private static void noteFailure(String path, Throwable error) {
        retryAfter.put(path, Long.valueOf(android.os.SystemClock.elapsedRealtime() + LOAD_RETRY_MILLIS));
        loadErrors.put(path, error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()));
    }

    /**
     * 现在有哪几张脸装不上。字体 面板与诊断导出拿它说真话："字库里有这张脸"和"这一屏画得出这张脸"
     * 是两件事，用户看到的应该是后者。
     */
    public static synchronized String failureNote() {
        if (loadErrors.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (java.util.Map.Entry<String, String> entry : loadErrors.entrySet()) {
            if (faces.containsKey(entry.getKey())) continue;
            if (out.length() > 0) out.append("；");
            out.append(DocxFontAssets.label(entry.getKey())).append("（")
                    .append(entry.getValue()).append("）");
        }
        return out.length() == 0 ? "" : "字库装载失败：" + out + "。这几张脸暂时用宋体显示，稍等或重开文档会自己重试。";
    }

    public static synchronized FontScriptMetrics metrics(String family) {
        String path = DocxFontAssets.pathFor(family);
        if (path == null || context == null) return FontScriptMetrics.DEFAULT;
        FontScriptMetrics cached = metrics.get(path);
        if (cached != null) return cached;
        try (InputStream input = context.getAssets().open(path)) {
            cached = FontScriptMetrics.read(input);
            /* Word 实测过行高的那几张脸用实测值（WordLineHeights）：字体表里的任何一个和都算不出
               Word 的行距，差的 0.77-3.27px/行按一页 27 行累计就是把段落提前一页。
               没量过的脸照旧走表推导，不猜。 */
            // 实测行高默认不套用：见 WordLineHeights.APPLIED_TO_LAYOUT 那笔真机对账。
            Float measured = WordLineHeights.APPLIED_TO_LAYOUT ? WordLineHeights.ratioFor(path) : null;
            if (measured != null) cached = cached.withLineHeight(measured.floatValue());
            metrics.put(path, cached);
            return cached;
        } catch (Exception error) { return FontScriptMetrics.DEFAULT; }
    }

    /** Preserve the requested family in OOXML while rendering missing symbols safely. */
    public static synchronized String symbolFamily(String family, int codePoint, int style) {
        if (codePoint < 0x80 || codePoint >= 0x2E80 && codePoint <= 0xFFFF) return family;
        String path = DocxFontAssets.pathFor(family, style);
        Typeface face = load(path);
        if (face == null) return family;
        String key = path + ':' + codePoint;
        Boolean supports = coverage.get(key);
        if (supports == null) {
            supports = covers(path, codePoint);
            coverage.put(key, supports);
        }
        return supports || !covers(DocxFontAssets.MATH, codePoint) ? family : "STIX Two Math";
    }
}
