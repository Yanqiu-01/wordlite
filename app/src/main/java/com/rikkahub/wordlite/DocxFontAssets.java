package com.rikkahub.wordlite;

import java.util.Locale;

/**
 * The font table. Every family listed here maps to a file that really ships in
 * app/src/main/assets/fonts, and tools/build-fonts.py is what puts those files there: the CJK faces are
 * the same faces desktop Word uses on Windows (华文新魏 is STXINWEI.TTF, 隶书 is SIMLI.TTF, and so on),
 * each face ships with the coverage its Windows original has, so a rare character cannot drop out.
 * unchanged, so a face can be added without moving a single line of text.
 *
 * A name we genuinely have no face for still says so out loud: substitution() returns the sentence the
 * 字体 dialog shows, so 华文新魏 turning into 宋体 is never something the user has to spot by eye.
 */
public final class DocxFontAssets {
    // Latin, shipped in four styles.
    public static final String TIMES = "fonts/times-new-roman.ttf";
    public static final String TIMES_BOLD = "fonts/times-new-roman-bold.ttf";
    public static final String TIMES_ITALIC = "fonts/times-new-roman-italic.ttf";
    public static final String TIMES_BOLD_ITALIC = "fonts/times-new-roman-bolditalic.ttf";
    // Latin, shipped in regular only; bold/italic are synthesised by the platform from these.
    public static final String ARIAL = "fonts/arial.ttf";
    public static final String ARIAL_BOLD = "fonts/arial-bold.ttf";
    public static final String CALIBRI = "fonts/calibri.ttf";
    public static final String CAMBRIA = "fonts/cambria.ttf";
    public static final String COURIER_NEW = "fonts/courier-new.ttf";
    public static final String CONSOLAS = "fonts/consolas.ttf";
    // CJK text faces, full character set.
    public static final String SONG = "fonts/song.ttc";
    public static final String HEI = "fonts/simhei.ttf";
    public static final String KAI = "fonts/kaiti.ttf";
    public static final String FANG = "fonts/fangsong.ttf";
    public static final String FZ_SMALL_SONG = "fonts/fz-small-song.ttf";
    // CJK faces that desktop Word has and a phone does not: shipped so a declared name gets its own face.
    public static final String ST_XINWEI = "fonts/stxinwei.ttf";        // 华文新魏
    public static final String ST_LITI = "fonts/stliti.ttf";            // 华文隶书
    public static final String ST_XINGKAI = "fonts/stxingka.ttf";       // 华文行楷
    public static final String LI_SU = "fonts/lisu.ttf";                // 隶书
    public static final String YOU_YUAN = "fonts/youyuan.ttf";          // 幼圆
    public static final String DENG_XIAN = "fonts/dengxian.ttf";        // 等线
    public static final String MS_YAHEI = "fonts/msyh.ttf";              // 微软雅黑
    public static final String MS_GOTHIC = "fonts/msgothic.ttf";        // ＭＳ ゴシック
    public static final String ST_SONG = "fonts/stsong.ttf";            // 华文宋体
    public static final String ST_ZHONGSONG = "fonts/stzhongsong.ttf";  // 华文中宋
    public static final String ST_KAITI = "fonts/stkaiti.ttf";          // 华文楷体
    public static final String ST_FANGSONG = "fonts/stfangsong.ttf";    // 华文仿宋
    public static final String ST_XIHEI = "fonts/stxihei.ttf";          // 华文细黑
    public static final String ST_CAIYUN = "fonts/stcaiyun.ttf";         // 华文彩云
    public static final String ST_HUPO = "fonts/sthupo.ttf";             // 华文琥珀
    public static final String MATH = "fonts/stix-two-math.ttf";

    /** Every file that ships. Anything this list does not contain must never come out of pathFor(). */
    public static final String[] PATHS = {
        TIMES, TIMES_BOLD, TIMES_ITALIC, TIMES_BOLD_ITALIC,
        SONG, HEI, KAI, FANG, FZ_SMALL_SONG, MATH, COURIER_NEW, CONSOLAS,
        ARIAL, ARIAL_BOLD, CALIBRI, CAMBRIA,
        ST_XINWEI, ST_LITI, ST_XINGKAI, LI_SU, YOU_YUAN, DENG_XIAN, MS_GOTHIC,
        ST_SONG, ST_ZHONGSONG, ST_KAITI, ST_FANGSONG, ST_XIHEI, MS_YAHEI,
        ST_CAIYUN, ST_HUPO
    };

    /** What the 字体 dialog lists. Word's names, not our file names: the user reads the docx names. */
    public static final String[] PICKER = {
        "宋体", "黑体", "楷体", "仿宋", "等线", "微软雅黑", "幼圆", "隶书",
        "方正小标宋", "华文新魏", "华文隶书", "华文行楷", "华文楷体", "华文仿宋", "华文细黑", "华文中宋",
        "华文彩云", "华文琥珀",
        "Times New Roman", "Arial", "Calibri", "Cambria", "Courier New", "Consolas",
        "MS Gothic", "MS Mincho", "ＭＳ 明朝"
    };

    private DocxFontAssets() { }

    public static String pathFor(String family) {
        return pathFor(family, 0);
    }

    /** Anything this returns is a file that ships in the APK; unknown Latin names get null, as before. */
    public static String pathFor(String family, int style) {
        String exact = exactPathFor(family, style);
        if (exact != null) return exact;
        String key = key(family);
        if (key.isEmpty()) return null;
        if (key.contains("新魏") || key.contains("xinwei")) return ST_XINWEI;
        if (key.contains("行楷") || key.contains("xingkai")) return ST_XINGKAI;
        if (key.contains("彩云") || key.contains("caiyun")) return ST_CAIYUN;
        if (key.contains("琥珀") || key.contains("hupo")) return ST_HUPO;
        if (key.contains("隶") || key.contains("lishu")) return LI_SU;
        if (key.contains("幼圆") || key.contains("youyuan")) return YOU_YUAN;
        if (key.contains("等线") || key.contains("dengxian")) return DENG_XIAN;
        if (key.contains("雅黑") || key.contains("yahei") || key.contains("msyh")) return MS_YAHEI;
        if (key.contains("中宋") || key.contains("zhongsong")) return ST_ZHONGSONG;
        if (key.contains("song") || key.contains("sun") || key.contains("明") || key.contains("书")) return SONG;
        /* mincho 只会是日文明朝体。本机没有那张字库（mincho 只在装了日文语言的 Windows 上），
           落到宋体——绝不能落到 Times 这类拉丁字库上，它一个假名汉字都画不出来。 */
        if (key.contains("mincho")) return SONG;
        if (key.contains("kai") || key.contains("楷")) return KAI;
        if (key.contains("fang") || key.contains("仿")) return FANG;
        if (key.contains("hei") || key.contains("黑") || key.contains("gothic")) return HEI;
        if (key.contains("标宋") || key.contains("小标宋")) return FZ_SMALL_SONG;
        if (key.contains("cambria")) return CAMBRIA;
        if (key.contains("calibri") || key.contains("aptos")) return CALIBRI;
        if (key.contains("arial") || key.contains("tahoma") || key.contains("verdana") || key.contains("segoe") || key.contains("helvetica") || key.contains("opensans")) return ARIAL;
        if (key.contains("times") || key.contains("serif") || key.contains("georgia")) return TIMES;
        if (key.contains("mono") || key.contains("consol")) return CONSOLAS;
        if (key.contains("courier")) return COURIER_NEW;
        /* 认不出来的名字分两种：中文名落到宋体（Word 对认不出的中文字体也走这一路）；拉丁字母名照旧
           交给系统字体——那一路以前就是这样，没重新量过就不动它。 */
        return hasCjk(family) ? SONG : null;
    }

    /**
    * 这个字体名在字库里有没有本尊。没有就说清换成了哪张脸——这一句要能进界面，
    * 用户不该靠眼睛去发现标题变成了宋体。
    */
    public static String substitution(String family) {
        String wanted = key(family);
        if (wanted.isEmpty()) return "";
        String path = pathFor(family);
        return isOwnName(path, wanted) ? ""
                : "字库没有「" + family.trim() + "」，用「" + label(path) + "」显示";
    }

    /** The face a path really is, in the name a user would recognise. */
    public static String label(String path) {
        if (SONG.equals(path)) return "宋体";
        if (HEI.equals(path)) return "黑体";
        if (KAI.equals(path)) return "楷体";
        if (FANG.equals(path)) return "仿宋";
        if (FZ_SMALL_SONG.equals(path)) return "方正小标宋";
        if (ST_XINWEI.equals(path)) return "华文新魏";
        if (ST_LITI.equals(path)) return "华文隶书";
        if (ST_XINGKAI.equals(path)) return "华文行楷";
        if (LI_SU.equals(path)) return "隶书";
        if (YOU_YUAN.equals(path)) return "幼圆";
        if (DENG_XIAN.equals(path)) return "等线";
        if (MS_YAHEI.equals(path)) return "微软雅黑";
        if (MS_GOTHIC.equals(path)) return "ＭＳ ゴシック";
        if (ST_SONG.equals(path)) return "华文宋体";
        if (ST_ZHONGSONG.equals(path)) return "华文中宋";
        if (ST_KAITI.equals(path)) return "华文楷体";
        if (ST_FANGSONG.equals(path)) return "华文仿宋";
        if (ST_XIHEI.equals(path)) return "华文细黑";
        if (ST_CAIYUN.equals(path)) return "华文彩云";
        if (ST_HUPO.equals(path)) return "华文琥珀";
        if (COURIER_NEW.equals(path)) return "Courier New";
        if (CONSOLAS.equals(path)) return "Consolas";
        if (CALIBRI.equals(path)) return "Calibri";
        if (CAMBRIA.equals(path)) return "Cambria";
        if (ARIAL_BOLD.equals(path) || ARIAL.equals(path)) return "Arial";
        if (MATH.equals(path)) return "STIX Two Math";
        if (TIMES.equals(path) || TIMES_BOLD.equals(path) || TIMES_ITALIC.equals(path)
                || TIMES_BOLD_ITALIC.equals(path)) return "Times New Roman";
        return path == null ? "系统字体" : path;
    }

    /** The names a file really IS; any other name that lands here got substituted. */
    private static boolean isOwnName(String path, String wanted) {
        if (SONG.equals(path))
            return oneOf(wanted, "宋体", "simsun", "nsimsun", "songti", "song", "新宋体", "simsunb", "原版宋体");
        if (HEI.equals(path)) return oneOf(wanted, "黑体", "simhei", "heiti", "黑体gb2312");
        if (KAI.equals(path)) return oneOf(wanted, "楷体", "simkai", "kaiti", "楷体gb2312");
        if (FANG.equals(path)) return oneOf(wanted, "仿宋", "simfang", "fangsong", "仿宋gb2312");
        if (FZ_SMALL_SONG.equals(path))
            return oneOf(wanted, "方正小标宋", "方正公文小标宋", "fzxiaobiaosong", "fzdocxiaobiaosong", "小标宋");
        if (ST_XINWEI.equals(path)) return oneOf(wanted, "华文新魏", "stxinwei", "xinwei");
        if (ST_LITI.equals(path)) return oneOf(wanted, "华文隶书", "stliti");
        if (ST_XINGKAI.equals(path)) return oneOf(wanted, "华文行楷", "stxingkai", "xingkai");
        if (LI_SU.equals(path)) return oneOf(wanted, "隶书", "lisu", "lishu");
        if (YOU_YUAN.equals(path)) return oneOf(wanted, "幼圆", "youyuan", "幼圆gb2312");
        if (DENG_XIAN.equals(path)) return oneOf(wanted, "等线", "dengxian");
        if (MS_YAHEI.equals(path)) return oneOf(wanted, "微软雅黑", "msyh", "microsoftyahei", "雅黑");
        if (MS_GOTHIC.equals(path)) return oneOf(wanted, "msgothic", "mspgothic", "msゴシック", "msｐゴシック");
        if (ST_SONG.equals(path)) return oneOf(wanted, "华文宋体", "stsong");
        if (ST_ZHONGSONG.equals(path)) return oneOf(wanted, "华文中宋", "stzhongsong");
        if (ST_KAITI.equals(path)) return oneOf(wanted, "华文楷体", "stkaiti");
        if (ST_FANGSONG.equals(path)) return oneOf(wanted, "华文仿宋", "stfangsong");
        if (ST_XIHEI.equals(path)) return oneOf(wanted, "华文细黑", "stxihei");
        if (ST_CAIYUN.equals(path)) return oneOf(wanted, "华文彩云", "stcaiyun");
        if (ST_HUPO.equals(path)) return oneOf(wanted, "华文琥珀", "sthupo");
        if (COURIER_NEW.equals(path)) return oneOf(wanted, "courier", "couriernew");
        if (CONSOLAS.equals(path)) return oneOf(wanted, "consolas");
        if (CALIBRI.equals(path)) return oneOf(wanted, "calibri");
        if (CAMBRIA.equals(path)) return oneOf(wanted, "cambria");
        if (ARIAL.equals(path) || ARIAL_BOLD.equals(path)) return oneOf(wanted, "arial");
        if (MATH.equals(path)) return wanted.contains("math");
        return wanted.contains("times") || oneOf(wanted, "serif", "tnr");
    }

    /** 名字里带中日韩字符：认不出时落到宋体，而不是落到系统的拉丁字体上。 */
    static boolean hasCjk(String family) {
        if (family == null) return false;
        for (int i = 0; i < family.length(); i++) {
            char c = family.charAt(i);
            if (c >= 0x2E80 && c <= 0x9FFF) return true;
        }
        return false;
    }

    private static boolean oneOf(String wanted, String... names) {
        for (String name : names) if (wanted.equals(name)) return true;
        return false;
    }

    public static boolean isTimes(String family) {
        if (family == null) return false;
        String key = key(family);
        return key.contains("times") || "serif".equals(key);
    }

    /**
     * Word writes these names with stray spaces, underscores, and (for the Japanese faces) full-width
     * characters: eastAsia="ＭＳ 明朝". Folding full-width ASCII to ASCII is what makes those match.
     */
    private static String key(String family) {
        if (family == null) return "";
        String trimmed = family.trim().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c >= 0xFF01 && c <= 0xFF5E) c = (char) (c - 0xFEE0);
            else if (c == 0x3000) c = ' ';
            if (c == ' ' || c == '_' || c == '-') continue;
            out.append(c);
        }
        return out.toString();
    }

    private static String exactPathFor(String family, int style) {
        String key = key(family);
        if (key.isEmpty()) return null;
        boolean bold = (style & 1) != 0;
        boolean italic = (style & 2) != 0;
        if (key.contains("math") || key.contains("symbol")) return MATH;
        if (key.equals("simsun") || key.equals("nsimsun") || key.equals("songti")
                || key.equals("song") || key.equals("宋体") || key.equals("原版宋体")
                || key.equals("simsunb") || key.equals("新宋体")) return SONG;
        if (key.equals("simhei") || key.equals("heiti") || key.equals("黑体") || key.equals("黑体gb2312")) return HEI;
        if (key.equals("微软雅黑") || key.equals("microsoftyahei") || key.equals("msyh")
                || key.equals("雅黑")) return MS_YAHEI;
        if (key.equals("kaiti") || key.equals("simkai") || key.equals("楷体") || key.equals("楷体gb2312")
                || key.equals("楷")) return KAI;
        if (key.equals("fangsong") || key.equals("simfang") || key.equals("仿宋")
                || key.equals("仿宋gb2312")) return FANG;
        if (key.equals("stxinwei") || key.equals("华文新魏") || key.equals("xinwei")) return ST_XINWEI;
        if (key.equals("stliti") || key.equals("华文隶书")) return ST_LITI;
        if (key.equals("stxingkai") || key.equals("华文行楷") || key.equals("xingkai")) return ST_XINGKAI;
        if (key.equals("隶书") || key.equals("lishu") || key.equals("lisu")) return LI_SU;
        if (key.equals("幼圆") || key.equals("youyuan") || key.equals("幼圆gb2312")) return YOU_YUAN;
        if (key.equals("等线") || key.equals("dengxian") || key.equals("等线light")) return DENG_XIAN;
        if (key.equals("stxihei") || key.equals("华文细黑")) return ST_XIHEI;
        if (key.equals("stkaiti") || key.equals("华文楷体")) return ST_KAITI;
        if (key.equals("stfangsong") || key.equals("华文仿宋")) return ST_FANGSONG;
        if (key.equals("stsong") || key.equals("华文宋体")) return ST_SONG;
        if (key.equals("stzhongsong") || key.equals("华文中宋")) return ST_ZHONGSONG;
        if (key.equals("stcaiyun") || key.equals("华文彩云")) return ST_CAIYUN;
        if (key.equals("sthupo") || key.equals("华文琥珀")) return ST_HUPO;
        if (key.equals("msgothic") || key.equals("mspgothic") || key.equals("msゴシック")
                || key.equals("msｐゴシック")) return MS_GOTHIC;
        /* ＭＳ 明朝本机就没有字库（mincho 只在装了日文语言的 Windows 上），落到宋体：
           同为衬线宋体骨架，差在笔形，替换话术会照实说。 */
        if (key.equals("msmincho") || key.equals("mspmincho") || key.equals("明朝")
                || key.equals("ms明朝")) return SONG;
        if (key.equals("方正小标宋") || key.equals("方正公文小标宋") || key.equals("fzxiaobiaosong")
                || key.equals("fzdocxiaobiaosong") || key.equals("小标宋")) return FZ_SMALL_SONG;
        if (key.equals("cambria")) return bold && italic ? TIMES_BOLD_ITALIC : bold ? CAMBRIA : CAMBRIA;
        if (key.equals("calibri") || key.equals("aptos")) return CALIBRI;
        if (key.equals("arial")) return bold ? ARIAL_BOLD : ARIAL;
        if (key.equals("tahoma") || key.equals("verdana")) return ARIAL;
        if (key.equals("courier") || key.equals("couriernew")) return COURIER_NEW;
        if (key.equals("consolas") || key.equals("menlo") || key.equals("monaco")) return CONSOLAS;
        if (key.equals("timesnewroman") || key.equals("timesnewromanpsmt") || key.equals("times")
                || key.equals("tnr") || key.equals("serif") || key.equals("georgia"))
            return bold && italic ? TIMES_BOLD_ITALIC : bold ? TIMES_BOLD : italic ? TIMES_ITALIC : TIMES;
        return null;
    }
}