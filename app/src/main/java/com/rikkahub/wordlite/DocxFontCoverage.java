package com.rikkahub.wordlite;

/**
 * 每张随包字库到底带了多少字。这份文件由 <py tools/build-font-coverage.py> 从
 * app/src/main/assets/fonts 逐码位量出来后写死，不要手改（改了也会被下一次生成冲掉，
 * 而 tools/build-font-coverage.py --check 会先红）。字体面板里那句“多少个汉字”念的就是
 * 这里的数，tests/FontSubstitution 第 12 段会拿 java.awt 的 canDisplay 逐张脸复量一遍。
 */
public final class DocxFontCoverage {
    private DocxFontCoverage() { }

    /** 基本汉字区（U+4E00-U+9FFF）画得出笔画的字数。 */
    public static int hanzi(String path) {
        if (path == null) return 0;
        if (DocxFontAssets.TIMES.equals(path)) return 0;
        if (DocxFontAssets.TIMES_BOLD.equals(path)) return 0;
        if (DocxFontAssets.TIMES_ITALIC.equals(path)) return 0;
        if (DocxFontAssets.TIMES_BOLD_ITALIC.equals(path)) return 0;
        if (DocxFontAssets.ARIAL.equals(path)) return 0;
        if (DocxFontAssets.ARIAL_BOLD.equals(path)) return 0;
        if (DocxFontAssets.CALIBRI.equals(path)) return 0;
        if (DocxFontAssets.CAMBRIA.equals(path)) return 0;
        if (DocxFontAssets.COURIER_NEW.equals(path)) return 0;
        if (DocxFontAssets.CONSOLAS.equals(path)) return 0;
        if (DocxFontAssets.SONG.equals(path)) return 20992;
        if (DocxFontAssets.HEI.equals(path)) return 20902;
        if (DocxFontAssets.KAI.equals(path)) return 20902;
        if (DocxFontAssets.FANG.equals(path)) return 20902;
        if (DocxFontAssets.FZ_SMALL_SONG.equals(path)) return 20902;
        if (DocxFontAssets.ST_XINWEI.equals(path)) return 6763;
        if (DocxFontAssets.ST_LITI.equals(path)) return 6763;
        if (DocxFontAssets.ST_XINGKAI.equals(path)) return 6763;
        if (DocxFontAssets.LI_SU.equals(path)) return 20902;
        if (DocxFontAssets.YOU_YUAN.equals(path)) return 20902;
        if (DocxFontAssets.DENG_XIAN.equals(path)) return 20992;
        if (DocxFontAssets.MS_YAHEI.equals(path)) return 20992;
        if (DocxFontAssets.MS_GOTHIC.equals(path)) return 12584;
        if (DocxFontAssets.ST_SONG.equals(path)) return 20902;
        if (DocxFontAssets.ST_ZHONGSONG.equals(path)) return 20902;
        if (DocxFontAssets.ST_KAITI.equals(path)) return 20902;
        if (DocxFontAssets.ST_FANGSONG.equals(path)) return 20902;
        if (DocxFontAssets.ST_XIHEI.equals(path)) return 20902;
        if (DocxFontAssets.ST_CAIYUN.equals(path)) return 6763;
        if (DocxFontAssets.ST_HUPO.equals(path)) return 6763;
        if (DocxFontAssets.MATH.equals(path)) return 0;
        return 0;
    }

    /** Unicode 映射里指向一个真有笔画的字形的码位总数（含拉丁、假名、标点）。 */
    public static int glyphs(String path) {
        if (path == null) return 0;
        if (DocxFontAssets.TIMES.equals(path)) return 1765;
        if (DocxFontAssets.TIMES_BOLD.equals(path)) return 1765;
        if (DocxFontAssets.TIMES_ITALIC.equals(path)) return 1765;
        if (DocxFontAssets.TIMES_BOLD_ITALIC.equals(path)) return 1765;
        if (DocxFontAssets.ARIAL.equals(path)) return 1558;
        if (DocxFontAssets.ARIAL_BOLD.equals(path)) return 1558;
        if (DocxFontAssets.CALIBRI.equals(path)) return 1569;
        if (DocxFontAssets.CAMBRIA.equals(path)) return 3871;
        if (DocxFontAssets.COURIER_NEW.equals(path)) return 3180;
        if (DocxFontAssets.CONSOLAS.equals(path)) return 2489;
        if (DocxFontAssets.SONG.equals(path)) return 28849;
        if (DocxFontAssets.HEI.equals(path)) return 28522;
        if (DocxFontAssets.KAI.equals(path)) return 28522;
        if (DocxFontAssets.FANG.equals(path)) return 28522;
        if (DocxFontAssets.FZ_SMALL_SONG.equals(path)) return 22002;
        if (DocxFontAssets.ST_XINWEI.equals(path)) return 7819;
        if (DocxFontAssets.ST_LITI.equals(path)) return 7819;
        if (DocxFontAssets.ST_XINGKAI.equals(path)) return 7819;
        if (DocxFontAssets.LI_SU.equals(path)) return 21983;
        if (DocxFontAssets.YOU_YUAN.equals(path)) return 21982;
        if (DocxFontAssets.DENG_XIAN.equals(path)) return 29460;
        if (DocxFontAssets.MS_YAHEI.equals(path)) return 29905;
        if (DocxFontAssets.MS_GOTHIC.equals(path)) return 16126;
        if (DocxFontAssets.ST_SONG.equals(path)) return 24367;
        if (DocxFontAssets.ST_ZHONGSONG.equals(path)) return 24367;
        if (DocxFontAssets.ST_KAITI.equals(path)) return 24367;
        if (DocxFontAssets.ST_FANGSONG.equals(path)) return 24367;
        if (DocxFontAssets.ST_XIHEI.equals(path)) return 24367;
        if (DocxFontAssets.ST_CAIYUN.equals(path)) return 7819;
        if (DocxFontAssets.ST_HUPO.equals(path)) return 7819;
        if (DocxFontAssets.MATH.equals(path)) return 3864;
        return 0;
    }

    /** 面板里那一行小字：汉字多的脸先报汉字数，纯拉丁的脸只报总字数。 */
    public static String detail(String path) {
        int h = hanzi(path), total = glyphs(path);
        if (path == null || total == 0) return "";
        return h > 0 ? group(h) + " 个汉字 · 共 " + group(total) + " 字"
                : "共 " + group(total) + " 字";
    }

    /** 6763 → "6,763"；面板里的数要能一眼看出多少。 */
    static String group(int value) {
        String digits = Integer.toString(value);
        StringBuilder out = new StringBuilder(digits.length() + 4);
        int head = digits.length() % 3;
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (i - head) % 3 == 0) out.append(',');
            out.append(digits.charAt(i));
        }
        return out.toString();
    }
}
