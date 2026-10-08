package com.rikkahub.wordlite;

import java.util.LinkedHashMap;

/**
 * Word 实测的"单一行高"，单位 em。这张表里的数不是字体表里任何一个和，而是从微软 Word 的相邻基线
 * 距离反推出来的：em = Word 行距 px ÷ (w:line/240) ÷ (字号 pt × 96/72)。
 *
 * 为什么要另存一张实测表（`docs/layout-parity-target.md` 第 2 节逐条量过）：12pt、w:line=300、
 * lineRule=auto 的中文正文里 Word 的相邻基线距离是 26.267px，反推单一行高 1.3134 em；而 song.ttc
 * 的 usWinAscent+usWinDescent = 1.000 em、hhea+lineGap = 1.141 em，Times New Roman 的表内和
 * 1.1074 em、Word 的 Latin 行 1.1767 em。没有任何一张表算得出 Word 的数。差的量按行累计：
 * 一行 -0.767px、一页 27 行 -20.7px = 0.8 行，够把整段提前一页，这就是"分页与原生不一致"的主因。
 *
 * 规矩：每个数都必须写清是哪张脸、什么字号、几行样本量出来的；**没量过的字体一律不进表**，
 * 继续走字体表推导，不许拿相近字体的数凑，也不许在这张表里调参——要改就得重新量。
 */
final class WordLineHeights {
    private WordLineHeights() { }
    /**
     * 这张实测表要不要真的用到排版上。两次真机对账都不许开：
     *
     * 2.1.1 第一次开：宋体行高从字库表的 1.000 em 换成实测 1.31335 em，同一篇稿子 29→30 页
     * （Word 28 页），错位段 50→74。当时记下的两个原因现在都修好了——选脸改按字符走（西文行
     * 不再被中文脸抬高一档），长高的行也不再补齐到整格（下标行不再整行翻倍）。
     *
     * cand-lh1 第二次开（修完再开）：逐行行高误差中位 -0.767px → 0.000px、p90 3.267px → 0.934px、
     * 纯西文段落回到 Word 的 23.533px、整行翻倍归零，可页码全对反而从 96.6% 掉到 65.05%
     * （错页段 7→72，全是晚一页），页数 28→29，全篇多算 868.9px = 正好一个版心页。
     *
     * 这条读数把话说白了：HEAD 的 96.6% 是两处反向误差抵出来的假平账——行高每行少算 0.767px，
     * 另一处每页多算约 8.6px（真值账见 docs/layout-parity-target.md 第 13 节）。所以开关继续关掉，
     * 等那处多算被单独定出来并修掉再开；不许把比值调小去凑页码。
     */
    static boolean APPLIED_TO_LAYOUT = false;
    /** 键取 DocxFontAssets 的资源路径：决定行高的是真正落笔那张脸，不是文档里写的字体名。 */
    private static final LinkedHashMap<String, Float> EM = new LinkedHashMap<String, Float>();
    static {
        /* 宋体：12pt / w:line=300 / auto / 不开 snapToGrid，Word 16.0 COM 相邻基线距离中位 26.267px
           （58 段纯中文行、逐行样本 380 个），26.267 ÷ 1.25 ÷ 16 = 1.31335 em。 */
        EM.put(DocxFontAssets.SONG, Float.valueOf(1.31335f));
        /* Times New Roman：同一篇里纯西文的参考文献行，Word 中位 23.533px（19 段），
           23.533 ÷ 1.25 ÷ 16 = 1.17665 em。混排行仍由 DocxTextLayout 取 max(中西) 决定整行高，
           所以中文段落里这两条同时成立：中文行 26.267px、纯西文行 23.533px。 */
        EM.put(DocxFontAssets.TIMES, Float.valueOf(1.17665f));
    }
    /** 这张脸量过就返回实测 em，没量过返回 null（调用方退回字体表推导）。 */
    static Float ratioFor(String path) {
        return path == null ? null : EM.get(path);
    }
    /** 回归用：表里有几条实测值。新增一条必须同时带来测量出处。 */
    static int measured() { return EM.size(); }

    /** 挑脸的两位掩码：EAST_ASIAN 是有中日韩文字或全角标点，LATIN 是西文字母、数字、半角符号。 */
    private static final int EAST_ASIAN = 1, LATIN = 2;

    /** 这一笔里出现了哪几类字符；空白不算任何一边。 */
    private static int scriptsIn(CharSequence text) {
        int kinds = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 32 || c == 9 || c == 10 || c == 13 || c == 12 || c == 0xA0 || c == 0x3000) continue;            kinds |= isEastAsianChar(c) ? EAST_ASIAN : LATIN;
        }
        return kinds;
    }

    /**
     * Word 在行高上的选脸口径：中日韩文字与全角标点归中文脸，其余归西文脸。这里只收无歧义的区段
     * ——宽度歧义那一批（— … “ ” ·）归西文脸，因为唯一的西文真值是那 19 段纯西文参考文献行 23.533px
     * （docs/layout-parity-target.md 第 1 节），把它们归进中文脸就把 19 段抬到了 26.267px。
     * 代理对（扩展 B 及以后）没有分类：本仓库还没有那种样本，不猜。
     */
    static boolean isEastAsianChar(char c) {
        if (c >= 0x1100 && c <= 0x115F) return true;      // 谚文字母
        if (c >= 0x2E80 && c <= 0x33FF) return true;      // 部首、康熙、CJK 符号与标点、假名、注音、兼容区
        if (c >= 0x3400 && c <= 0x4DBF) return true;      // 扩展 A
        if (c >= 0x4E00 && c <= 0xA4CF) return true;      // 统一汉字、彝文
        if (c >= 0xA960 && c <= 0xA97F) return true;      // 谚文字母扩展
        if (c >= 0xAC00 && c <= 0xD7FF) return true;      // 谚文音节
        if (c >= 0xF900 && c <= 0xFAFF) return true;      // 兼容表意文字
        if (c >= 0xFE10 && c <= 0xFE1F) return true;      // 竖排标点
        if (c >= 0xFE30 && c <= 0xFE6F) return true;      // CJK 兼容形式
        if (c >= 0xFF00 && c <= 0xFFDC) return true;      // 全角形式、半角片假名
        return c >= 0xFFE0 && c <= 0xFFE6;                // 全角货币符号
    }

    /** 把"字体名 -> 该脸的度量"这条依赖交进来：本类不许碰 Android。 */
    interface Metrics {
        FontScriptMetrics forFamily(String family);
    }

    /** 一段话的行高，以及真正撑起这个行高的那张脸。 */
    static final class Height {
        final float pt;
        final String family;
        Height(float pt, String family) { this.pt = pt; this.family = family; }
    }

    /**
     * Word 的"取最高那一张脸"扫描：同一个段里按 pt × 单一行高比值 比大小（不是只比比值），
     * 上下标先按 OS/2 的字号折算，所以 8pt 的上标撑不高一整行 12pt 正文。
     *
     * 这个扫描必须只有一份实现：行高的数（`Spacing` 用它算 desiredHeight）和"这张脸量过没有"
     * 的判断必须是同一张脸说了算。早先判断走的是"只比比值不算字号"的另一套扫描，于是
     * 16pt 黑体 + 12pt Times 的标题里，撑起行高的是黑体（16pt），判断却选中 Times（比值大），
     * 结果拿 Times 的实测小数去补一段没量过的黑体——那是凭空造出来的行高。
     */
    static Height tallest(DocxDocument.ParagraphBlock p, Metrics metrics, String fallbackFamily) {
        int baseHalf = p != null && p.baseRunStyle != null ? p.baseRunStyle.fontSizeHalfPoints : -1;
        String baseEA = p != null && p.baseRunStyle != null ? p.baseRunStyle.eastAsiaFontFamily : null;
        String baseLatin = p == null || p.baseRunStyle == null ? null
                : p.baseRunStyle.asciiFontFamily != null ? p.baseRunStyle.asciiFontFamily
                        : p.baseRunStyle.fontFamily;
        float maxH = 0f;
        String winner = null;
        if (p != null) {
            for (DocxDocument.Run run : p.runs) {
                if (run.text == null || run.text.trim().isEmpty()) continue;
                int half = run.style.fontSizeHalfPoints > 0 ? run.style.fontSizeHalfPoints : baseHalf;
                if (half <= 0) half = 24; // Word default
                String ea = run.style.eastAsiaFontFamily != null ? run.style.eastAsiaFontFamily : baseEA;
                String latin = run.style.asciiFontFamily != null ? run.style.asciiFontFamily : baseLatin;
                FontScriptMetrics mEA = metrics.forFamily(ea);
                FontScriptMetrics mLatin = metrics.forFamily(latin);
                String face;
                float ratio;
                FontScriptMetrics scriptMetrics;
                if (APPLIED_TO_LAYOUT) {
                    /* Word 按字符挑脸：这一笔里出现了中日韩文字或全角标点才轮到中文脸，只有西文字母、
                       数字和半角符号才轮到西文脸。无条件取 max(中西) 等于让没参与这一行的中文脸替纯西文行
                       定行高：真机对账量到 19 段纯西文参考文献被抬到 26.267px，Word 是 23.533px，每行多算
                       2.7~3.6px（docs/layout-parity-target.md 第 7 节 A 表）。分开之后中文行 26.267px 与
                       纯西文行 23.533px 同时成立，那才是 Word 的口径。 */
                    int kinds = scriptsIn(run.text);
                    boolean eaSide = (kinds & EAST_ASIAN) != 0;
                    boolean latinSide = (kinds & LATIN) != 0;
                    if (eaSide && ea != null
                            && (!latinSide || mEA.lineHeightRatio >= mLatin.lineHeightRatio)) {
                        face = ea;
                        ratio = mEA.lineHeightRatio;
                    } else {
                        // 只剩一个宽度歧义的符号（— … “ ” ·）也按西文脸算：全角标点 、。「」 在
                        // scriptsIn 里已经归到中文脸，所以中文行不会因为这条矮下去。
                        face = latin != null ? latin : ea;
                        ratio = latin != null ? mLatin.lineHeightRatio : mEA.lineHeightRatio;
                    }
                    scriptMetrics = face == ea ? mEA : mLatin;
                } else {
                    /* 开关关掉时保持 2.1.0 的取法，一个字都不改：无条件取中西两脸里比值较大的那一张。
                       这不是 Word 的口径，但字库表给中文脸的 1.000 em 本来就是错的（Word 量到 1.313 em），
                       HEAD 一直是靠西文脸的 1.1074 em 把中文行垫到 23px；先按字符选脸会把它们垫到 20px，
                       离 Word 的 25.8px 更远——snap 段还有网格地板兜底，170 段非 snap 的没有。所以这条改动
                       与实测行高同进同退，不单独开。上下标的折算脸也沿用原取法，保持逐字节一致。 */
                    boolean eastAsiaWins = mEA.lineHeightRatio >= mLatin.lineHeightRatio;
                    face = eastAsiaWins ? ea : latin;
                    ratio = eastAsiaWins ? mEA.lineHeightRatio : mLatin.lineHeightRatio;
                    scriptMetrics = mLatin.lineHeightRatio >= mEA.lineHeightRatio ? mLatin : mEA;
                }
                float runH = half / 2f * ratio;
                if (run.style.superscript || run.style.subscript)
                    runH *= ScriptGeometry.of(run.style.superscript, scriptMetrics).scale;
                if (runH > maxH) {
                    maxH = runH;
                    winner = face;
                }
            }
        }
        if (maxH <= 0f) {
            // 空段/整段都是空白：退回段默认字体，口径与原来 wordSingleLineHeightPt 的空段分支一致。
            FontScriptMetrics only = metrics.forFamily(fallbackFamily);
            return new Height((baseHalf > 0 ? baseHalf / 2f : 11f) * only.lineHeightRatio, winner);
        }
        return new Height(maxH, winner);
    }

    /** 这一段能不能带 Word 实测的小数行距：只有撑起行高那张脸在实测表里才行。 */
    static boolean carries(DocxDocument.ParagraphBlock p, Metrics metrics, String fallbackFamily) {
        if (p == null || !APPLIED_TO_LAYOUT) return false;
        String winner = tallest(p, metrics, fallbackFamily).family;
        return winner != null && ratioFor(DocxFontAssets.pathFor(winner)) != null;
    }

    /**
     * Word 的固定值行距（w:lineRule="exact"）是一把绝对长度，不是倍数，所以中间不许取整：
     * 400 twips（20 磅）在这一套文档坐标里就是 400/15 = 26.6667px。这个换算的前提是真机量到的
     * 比例：tools/line-spacing-probe.ps1 在 CDY-AN90（sdk 29）上读到 12pt 的字大小
     * getTextSize()=16.0000 文档px、1pt=1.333333px、1440twips=96px，也就是 px/em = 1.000，
     * 不是 1.0741。整像素的行盒由 fixedBoxPx 画，小数那一截由 Spacing.lineCarry 交给分页器。
     */
    static float fixedAdvancePx(int lineTwips) {
        return Math.max(1, lineTwips) / PageGeometry.TWIPS_PER_UNIT;
    }

    /** 平台上真画得出来的那一整像素行盒：固定值向下取整到最近的整数，差额走 lineCarry。 */
    static int fixedBoxPx(int lineTwips, float coordinateScale) {
        return Math.max(1, Math.round(fixedAdvancePx(lineTwips) * coordinateScale));
    }

    /**
     * Word 的 auto 行距是一步算完的小数：单一行高(pt) × 96/72 × w:line/240，中间不取整。
     * 我们画出来的行盒仍然落在整数上，分页要补的就是这一条与整数行盒之差。
     */
    static float advancePx(float singleLineHeightPt, int lineTwips, float coordinateScale) {
        double ratio = lineTwips > 0 ? lineTwips / 240d : 1d;
        return (float) (singleLineHeightPt * PageGeometry.points(1f) * coordinateScale * ratio);
    }
}
