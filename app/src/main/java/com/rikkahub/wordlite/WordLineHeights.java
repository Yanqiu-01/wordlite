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
     * 这张实测表要不要真的用到排版上。2026-10-08 关掉，因为真机对账不认它：
     * 把宋体的行高从字库表的 1.000 em 换成 Word 实测的 1.31335 em 之后，同一篇稿子在真机上
     * 从 29 页变成 30 页（Word 28 页），word-parity 的错位段从 50 段涨到 74 段、页码全对的比例
     * 从 75.7% 掉到 64.1%（reports 见 CHANGELOG 2.1.1）。也就是说"行高对不上"不是分页漂移的主因，
     * 至少在这篇稿子上不是：错位的形状是从 Word 第 2 页末段（para 56）起整篇 +1 页，
     * 那是封面/目录那几页的一次性溢出，不是正文每行累计出来的。
     *
     * 表和算式一律留着——它们是量出来的真值，配套的 PageBreaker 基线判页尾（Item.hang）也留着，
     * Host 侧 47 条断言钉着。要重开这个开关，必须先把那一次性溢出的成因找出来并重新真机对账，
     * 不许在这儿把数调成能让页码对上的样子。
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
                float runH = half / 2f * Math.max(mEA.lineHeightRatio, mLatin.lineHeightRatio);
                if (run.style.superscript || run.style.subscript)
                    // 折算上下标用的是"哪张脸更高"，平手时以 Latin 脸为准——沿用改动前的取法，
                    // 免得这条改动顺手把上下标的行盒也改了（那是另一件事，要动得另外拿真值）。
                    runH *= ScriptGeometry.of(run.style.superscript,
                            mLatin.lineHeightRatio >= mEA.lineHeightRatio ? mLatin : mEA).scale;
                if (runH > maxH) {
                    maxH = runH;
                    winner = mEA.lineHeightRatio >= mLatin.lineHeightRatio ? ea : latin;
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
     * Word 的 auto 行距是一步算完的小数：单一行高(pt) × 96/72 × w:line/240，中间不取整。
     * 我们画出来的行盒仍然落在整数上，分页要补的就是这一条与整数行盒之差。
     */
    static float advancePx(float singleLineHeightPt, int lineTwips, float coordinateScale) {
        double ratio = lineTwips > 0 ? lineTwips / 240d : 1d;
        return (float) (singleLineHeightPt * PageGeometry.points(1f) * coordinateScale * ratio);
    }
}
