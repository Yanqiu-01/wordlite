package com.rikkahub.wordlite;

import java.util.Locale;

/** Exact aliases for the bundled font files; unknown families stay unknown. */
public final class DocxFontAssets {
    // Times New Roman (4 weights)
    public static final String TIMES = "fonts/times-new-roman.ttf";
    public static final String TIMES_BOLD = "fonts/times-new-roman-bold.ttf";
    public static final String TIMES_ITALIC = "fonts/times-new-roman-italic.ttf";
    public static final String TIMES_BOLD_ITALIC = "fonts/times-new-roman-bolditalic.ttf";
    // CJK core
    public static final String SONG = "fonts/song.ttc";
    public static final String HEI = "fonts/simhei.ttf";
    public static final String KAI = "fonts/kaiti.ttf";
    public static final String KAI_GB = "fonts/kaiti-gb2312.ttf";
    public static final String FANG = "fonts/fangsong.ttf";
    public static final String FANG_GB = "fonts/fangsong-gb2312.ttf";
    // CJK decorative
    public static final String ST_XINWEI = "fonts/stxinwei.ttf";
    public static final String ST_KAITI = "fonts/stkaiti.ttf";
    public static final String ST_FANGSONG = "fonts/stfangsong.ttf";
    public static final String ST_XIHEI = "fonts/stxihei.ttf";
    public static final String ST_XINGKAI = "fonts/stxingkai.ttf";
    public static final String MS_MINCHO = "fonts/ms-mincho.ttf";
    // FZ series
    public static final String FZ_SMALL_SONG = "fonts/fz-small-song.ttf";
    public static final String FZ_SMALL_SONG_GBK = "fonts/fz-xiaobiaosong-gbk.ttf";
    public static final String FZ_XIAO_BIAO_SONG = "fonts/fz-xiaobiaosong.ttf";
    public static final String FZ_DA_BIAO_SONG = "fonts/fz-dabiaosong.ttf";
    public static final String FZ_DA_BIAO_SONG_JF = "fonts/fz-dabiaosong-jf.ttf";
    public static final String FZ_FANGSONG = "fonts/fz-fangsong.ttf";
    public static final String FZ_FANGSONG_GBK = "fonts/fz-fangsong-gbk.ttf";
    public static final String FZ_KAITI = "fonts/fz-kaiti.ttf";
    public static final String FZ_KAITI_GBK = "fonts/fz-kaiti-gbk.ttf";
    public static final String FZ_HEITI = "fonts/fz-heiti.ttf";
    public static final String FZ_HEITI_GBK = "fonts/fz-heiti-gbk.ttf";
    // Latin
    public static final String CALIBRI = "fonts/calibri.ttf";
    public static final String CALIBRI_BOLD = "fonts/calibri-bold.ttf";
    public static final String CALIBRI_ITALIC = "fonts/calibri-italic.ttf";
    public static final String CALIBRI_BOLD_ITALIC = "fonts/calibri-bolditalic.ttf";
    public static final String CAMBRIA = "fonts/cambria.ttf";
    public static final String CAMBRIA_BOLD = "fonts/cambria-bold.ttf";
    public static final String CAMBRIA_ITALIC = "fonts/cambria-italic.ttf";
    public static final String CAMBRIA_BOLD_ITALIC = "fonts/cambria-bolditalic.ttf";
    public static final String CONSOLAS = "fonts/consolas.ttf";
    public static final String CONSOLAS_BOLD = "fonts/consolas-bold.ttf";
    public static final String CONSOLAS_ITALIC = "fonts/consolas-italic.ttf";
    public static final String CONSOLAS_BOLD_ITALIC = "fonts/consolas-bolditalic.ttf";
    public static final String APTOS = "fonts/aptos.ttf";
    public static final String APTOS_BOLD = "fonts/aptos-bold.ttf";
    public static final String APTOS_ITALIC = "fonts/aptos-italic.ttf";
    public static final String APTOS_BOLD_ITALIC = "fonts/aptos-bolditalic.ttf";
    public static final String GEORGIA = "fonts/georgia.ttf";
    public static final String GEORGIA_BOLD = "fonts/georgia-bold.ttf";
    public static final String GEORGIA_ITALIC = "fonts/georgia-italic.ttf";
    public static final String GEORGIA_BOLD_ITALIC = "fonts/georgia-bolditalic.ttf";
    public static final String ARIAL = "fonts/arial.ttf";
    public static final String ARIAL_BOLD = "fonts/arial-bold.ttf";
    public static final String ARIAL_ITALIC = "fonts/arial-italic.ttf";
    public static final String ARIAL_BOLD_ITALIC = "fonts/arial-bolditalic.ttf";
    public static final String COURIER_NEW = "fonts/courier-new.ttf";
    public static final String COURIER_NEW_BOLD = "fonts/courier-new-bold.ttf";
    public static final String COURIER_NEW_ITALIC = "fonts/courier-new-italic.ttf";
    public static final String COURIER_NEW_BOLD_ITALIC = "fonts/courier-new-bolditalic.ttf";
    public static final String TAHOMA = "fonts/tahoma.ttf";
    public static final String TAHOMA_BOLD = "fonts/tahoma-bold.ttf";
    public static final String VERDANA = "fonts/verdana.ttf";
    public static final String VERDANA_BOLD = "fonts/verdana-bold.ttf";

    public static final String MATH = "fonts/stix-two-math.ttf";
    public static final String[] PATHS = {
        TIMES, TIMES_BOLD, TIMES_ITALIC, TIMES_BOLD_ITALIC,
        SONG, HEI, KAI, FANG, FZ_SMALL_SONG,
        ARIAL, ARIAL_BOLD, CALIBRI, CAMBRIA, MATH
    };

    private static String available(String requested, String fallback) {
        for (String path : PATHS) if (path.equals(requested)) return requested;
        return fallback;
    }

    private DocxFontAssets() { }

    public static String pathFor(String family) {
        return pathFor(family, 0);
    }

    public static String pathFor(String family, int style) {
        String requested = exactPathFor(family, style);
        if (requested == null) return null;
        for (String path : PATHS) if (path.equals(requested)) return requested;
        String key = family.toLowerCase(Locale.ROOT);
        if (key.contains("consolas") || key.contains("courier")) return null;
        if (key.contains("calibri") || key.contains("aptos")) return CALIBRI;
        if (key.contains("cambria")) return CAMBRIA;
        if (key.contains("arial") || key.contains("tahoma") || key.contains("verdana"))
            return (style & 1) != 0 ? ARIAL_BOLD : ARIAL;
        if (key.contains("georgia")) return (style & 1) != 0 ? TIMES_BOLD : TIMES;
        if (key.contains("kai") || key.contains("楷")) return KAI;
        if (key.contains("fang") || key.contains("仿")) return FANG;
        if (key.contains("hei") || key.contains("黑") || key.contains("细")) return HEI;
        if (key.contains("标宋")) return FZ_SMALL_SONG;
        return SONG;
    }

    private static String exactPathFor(String family, int style) {
        if (family == null) return null;
        boolean bold = (style & 1) != 0;
        boolean italic = (style & 2) != 0;
        String key = family.trim().toLowerCase(Locale.ROOT).replace(" ", "")
                .replace("_", "").replace("-", "");
        switch (key) {
            case "stixtwomath": case "cambria math": case "cambriamath":
            case "latinmodernmath": case "xitsmath": case "symbol": return MATH;
            case "microsoftyahei": case "微软雅黑": return HEI;
            case "timesnewroman": case "timesnewromanpsmt": case "tnr": case "serif":
                if (bold && italic) return TIMES_BOLD_ITALIC;
                if (bold) return TIMES_BOLD;
                if (italic) return TIMES_ITALIC;
                return TIMES;
            case "simsun": case "nsimsun": case "songti":
            case "\u5b8b\u4f53": case "\u539f\u7248\u5b8b\u4f53": return SONG;
            case "simhei": case "heiti": case "\u9ed1\u4f53": case "\u9ed1\u4f53gb2312": return HEI;
            case "kaiti": case "simkai": case "\u6977\u4f53": return KAI;
            case "kaitigb2312": case "\u6977\u4f53gb2312": return KAI_GB;
            case "fangsong": case "simfang": case "\u4eff\u5b8b": return FANG;
            case "fangsonggb2312": case "\u4eff\u5b8bgb2312": return FANG_GB;
            case "stxinwei": case "\u534e\u6587\u65b0\u9b4f": return ST_XINWEI;
            case "stkaiti": case "\u534e\u6587\u6977\u4f53": return ST_KAITI;
            case "stfangsong": case "\u534e\u6587\u4eff\u5b8b": return ST_FANGSONG;
            case "stxihei": case "\u534e\u6587\u7ec6\u9ed1": return ST_XIHEI;
            case "stxingkai": case "\u534e\u6587\u884c\u6977": return ST_XINGKAI;
            case "msmincho": case "mspmincho": case "\uff2d\uff33 \u660e\u671d":
            case "\uff2d\uff33\u660e\u671d": case "ms\u660e\u671d":
            case "\uff4d\uff53\u660e\u671d": return MS_MINCHO;
            case "fzdocxiaobiaosong": case "fzxiaobiaosong":
            case "\u65b9\u6b63\u5c0f\u6807\u5b8b": case "\u65b9\u6b63\u516c\u6587\u5c0f\u6807\u5b8b": return FZ_SMALL_SONG;
            case "\u65b9\u6b63\u5c0f\u6807\u5b8bgbk": return FZ_SMALL_SONG_GBK;
            case "\u65b9\u6b63\u5927\u6807\u5b8b": return FZ_DA_BIAO_SONG;
            case "calibri":
                if (bold && italic) return CALIBRI_BOLD_ITALIC;
                if (bold) return CALIBRI_BOLD;
                if (italic) return CALIBRI_ITALIC;
                return CALIBRI;
            case "cambria":
                if (bold && italic) return CAMBRIA_BOLD_ITALIC;
                if (bold) return CAMBRIA_BOLD;
                if (italic) return CAMBRIA_ITALIC;
                return CAMBRIA;
            case "consolas":
                if (bold && italic) return CONSOLAS_BOLD_ITALIC;
                if (bold) return CONSOLAS_BOLD;
                if (italic) return CONSOLAS_ITALIC;
                return CONSOLAS;
            case "aptos":
                if (bold && italic) return APTOS_BOLD_ITALIC;
                if (bold) return APTOS_BOLD;
                if (italic) return APTOS_ITALIC;
                return APTOS;
            case "georgia":
                if (bold && italic) return GEORGIA_BOLD_ITALIC;
                if (bold) return GEORGIA_BOLD;
                if (italic) return GEORGIA_ITALIC;
                return GEORGIA;
            case "arial":
                if (bold && italic) return ARIAL_BOLD_ITALIC;
                if (bold) return ARIAL_BOLD;
                if (italic) return ARIAL_ITALIC;
                return ARIAL;
            case "couriernew": case "courier":
                if (bold && italic) return COURIER_NEW_BOLD_ITALIC;
                if (bold) return COURIER_NEW_BOLD;
                if (italic) return COURIER_NEW_ITALIC;
                return COURIER_NEW;
            case "tahoma": return bold ? TAHOMA_BOLD : TAHOMA;
            case "verdana": return bold ? VERDANA_BOLD : VERDANA;
            default: return null;
        }
    }

    public static boolean isTimes(String family) {
        if (family == null) return false;
        String key = family.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        return key.contains("times") || "serif".equals(key);
    }
}
