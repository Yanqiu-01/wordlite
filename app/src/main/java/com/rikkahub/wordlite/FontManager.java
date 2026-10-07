package com.rikkahub.wordlite;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;

/** Lazy, process-local font cache. No font file is opened by initialize(). */
public final class FontManager {
    private static Context context;
    private static final HashMap<String, Typeface> faces = new HashMap<String, Typeface>();
    private static final HashMap<String, FontScriptMetrics> metrics = new HashMap<String, FontScriptMetrics>();
    private static final HashSet<String> unavailable = new HashSet<String>();
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
        if (path == null || context == null || unavailable.contains(path)) return null;
        Typeface cached = faces.get(path);
        if (cached != null) return cached;
        try {
            cached = Typeface.createFromAsset(context.getAssets(), path);
            faces.put(path, cached);
            return cached;
        } catch (RuntimeException error) {
            unavailable.add(path);
            return null;
        }
    }

    public static synchronized FontScriptMetrics metrics(String family) {
        String path = DocxFontAssets.pathFor(family);
        if (path == null || context == null) return FontScriptMetrics.DEFAULT;
        FontScriptMetrics cached = metrics.get(path);
        if (cached != null) return cached;
        try (InputStream input = context.getAssets().open(path)) {
            cached = FontScriptMetrics.read(input);
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
