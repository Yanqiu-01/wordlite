package com.rikkahub.wordlite;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

/**
 * 2.1.1 的行高口径：Word 实测的每字体单一行高（`WordLineHeights`）、Word 的一步小数行距算式
 * （`WordLineHeights.advancePx`）、以及"哪张脸撑起这一段行高"的扫描（`WordLineHeights.tallest`）。
 *
 * 这个套件锁的是"分页按 Word 的相邻基线距离走"这一条账，而且只在 Host 侧跑：被测的三个方法必须
 * 是不碰 Android 的纯 Java（`DocxTextLayout` 在 Host 上连类都装不起来，一碰就
 * NoClassDefFoundError: android/text/Spannable）。字体度量直接读 `app/src/main/assets/fonts/`
 * 里那几份真要打进 APK 的字库，和 FontManager 在手机上算出来的数一模一样。
 *
 * Word 真值来自桌面 Word 16.0 COM 的逐行基线坐标（`artifacts/agent-typeset/word-line-pitch.ps1`，
 * 121 段 / 508 行 / 380 个可测行距），结论写在 `docs/layout-parity-target.md`：12pt、w:line=300、
 * 不开网格的中文正文，相邻基线距离中位 26.267px；纯西文的参考文献行 23.533px。字库表里任何一个和
 * （宋体 1.000 em、Times 1.1074 em、hhea+lineGap 1.141 em）都算不出这两个数，所以只能实测。
 * 手机上的真实渲染不在这里测，由 tools/capture-device.ps1 与 word-parity/edge-parity 两条线负责。
 */
public final class WordLineHeightRegression {
    private static int checks;
    /** Word 真值（px，96dpi 文档单位）。这两个数一改就必须重新量。 */
    private static final double WORD_CJK_PX = 26.267, WORD_LATIN_PX = 23.533;
    private static final float SONG_EM = 1.31335f, TIMES_EM = 1.17665f;
    private static final String ROOT = new File(System.getProperty("user.dir")).getAbsolutePath();
    private static final HashMap<String, FontScriptMetrics> CACHE =
            new HashMap<String, FontScriptMetrics>();

    /** 与 FontManager.metrics 同一口径：认不出的字体退回 DEFAULT，量过的脸换成 Word 实测行高。 */
    private static final WordLineHeights.Metrics REAL = new WordLineHeights.Metrics() {
        @Override public FontScriptMetrics forFamily(String family) {
            if (family == null) return FontScriptMetrics.DEFAULT;
            String path = DocxFontAssets.pathFor(family);
            if (path == null) return FontScriptMetrics.DEFAULT;
            Float measured = WordLineHeights.ratioFor(path);
            String key = path + '#' + (measured == null ? "" : measured);
            FontScriptMetrics cached = CACHE.get(key);
            if (cached != null) return cached;
            try (InputStream in = new FileInputStream(new File(ROOT, "app/src/main/assets/" + path))) {
                FontScriptMetrics read = FontScriptMetrics.read(in);
                cached = measured == null ? read : read.withLineHeight(measured.floatValue());
                CACHE.put(key, cached);
                return cached;
            } catch (Exception error) {
                throw new AssertionError("读不到随包字库 " + path + "：" + error);
            }
        }
    };
    /** 字库表推导那一张口径：中文脸 1.000 em、西文脸 1.1074 em（宋体与 Times 表里的 win 和）。 */
    private static final WordLineHeights.Metrics TABLE = new WordLineHeights.Metrics() {
        @Override public FontScriptMetrics forFamily(String family) {
            return "Times New Roman".equals(family)
                    ? FontScriptMetrics.DEFAULT.withLineHeight(1.1074f)
                    : FontScriptMetrics.DEFAULT.withLineHeight(1.000f);
        }
    };

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
        System.out.println("PASS " + message);
    }
    private static void close(double actual, double expected, double tolerance, String message) {
        check(Math.abs(actual - expected) <= tolerance,
                message + "（实测 " + round3(actual) + "，期望 " + expected + "）");
    }
    private static String round3(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    private static DocxDocument.RunStyle style(int halfPoints, String eastAsia, String ascii) {
        DocxDocument.RunStyle s = new DocxDocument.RunStyle();
        s.fontSizeHalfPoints = halfPoints;
        s.eastAsiaFontFamily = eastAsia;
        s.asciiFontFamily = ascii;
        return s;
    }
    /** 一段话：runs 里每个元素是 {文本, 中文字体, 西文字体, 字号pt, 是否上标}。 */
    private static DocxDocument.ParagraphBlock paragraph(String baseEA, String baseLatin,
                                                         Object[]... runs) {
        DocxDocument.ParagraphBlock block = new DocxDocument.ParagraphBlock();
        block.index = 0;
        block.baseRunStyle.fontSizeHalfPoints = 24;
        block.baseRunStyle.eastAsiaFontFamily = baseEA;
        block.baseRunStyle.asciiFontFamily = baseLatin;
        for (Object[] r : runs) {
            String text = (String) r[0];
            DocxDocument.RunStyle s = style(((Integer) r[3]).intValue() * 2, (String) r[1], (String) r[2]);
            s.superscript = Boolean.TRUE.equals(r[4]);
            block.text = (block.text == null ? "" : block.text) + text;
            block.runs.add(new DocxDocument.Run(text, s));
        }
        return block;
    }

    /** 表里只许有量过的脸，且每个数都要能反推回 Word 那条实测的 px。 */
    private static void tableHoldsOnlyMeasuredFaces() {
        check(WordLineHeights.measured() == 2,
                "实测表里只有两条：宋体与 Times，没量过的字体一律不进表");
        close(WordLineHeights.ratioFor(DocxFontAssets.SONG).floatValue(), SONG_EM, 0.00001d,
                "宋体的实测单一行高（em）");
        close(WordLineHeights.ratioFor(DocxFontAssets.TIMES).floatValue(), TIMES_EM, 0.00001d,
                "Times New Roman 的实测单一行高（em）");
        check(WordLineHeights.ratioFor(DocxFontAssets.HEI) == null,
                "黑体没量过，返回 null 走字库表推导，不拿宋体的数凑");
        check(WordLineHeights.ratioFor(DocxFontAssets.CALIBRI) == null, "Calibri 没量过，同样不猜");
        check(WordLineHeights.ratioFor(null) == null, "路径为空返回 null，不许抛");
        close(WORD_CJK_PX / 1.25d / 16d, SONG_EM, 0.0005d, "宋体那条能反推回 Word 实测的 26.267px");
        close(WORD_LATIN_PX / 1.25d / 16d, TIMES_EM, 0.0005d, "Times 那条能反推回 Word 实测的 23.533px");
    }

    /** Word 的 auto 行距是一步小数：26.267，不是两步取整的 26。 */
    private static void advanceIsOneStepFractional() {
        close(WordLineHeights.advancePx(12f * SONG_EM, 300, 1f), WORD_CJK_PX, 0.02d,
                "12pt 宋体 / w:line=300 一行占多高（Word 真值 26.267px）");
        close(WordLineHeights.advancePx(12f * TIMES_EM, 300, 1f), WORD_LATIN_PX, 0.02d,
                "12pt Times / w:line=300 一行占多高（Word 真值 23.533px）");
        check(Math.round(WordLineHeights.advancePx(12f * SONG_EM, 300, 1f)) == 26,
                "画出来的行盒仍落在整数上：26px");
        close(WordLineHeights.advancePx(12f * SONG_EM, 300, 1f) - 26f, 0.267d, 0.01d,
                "分页要补的那一截小数（26.267 - 26）");
        close((WordLineHeights.advancePx(12f * SONG_EM, 300, 1f) - 26f) * 27d, 7.2d, 0.2d,
                "这一截在一页 27 行上累计多少 px：够把一段翻到下一页，所以必须补");
        close(WordLineHeights.advancePx(12f * SONG_EM, 240, 1f), 21.013d, 0.02d,
                "单倍行距（w:line=240）时同一张脸给 21.013px");
        close(WordLineHeights.advancePx(12f * SONG_EM, 0, 1f), 21.013d, 0.02d,
                "没有 w:line 时按单倍算，不乘比例");
        close(WordLineHeights.advancePx(10.5f * SONG_EM, 300, 1f), 22.984d, 0.02d,
                "五号字（10.5pt）同一条算式，比值与字号无关");
        close(WordLineHeights.advancePx(12f * SONG_EM, 300, 0.5f) * 2f, WORD_CJK_PX, 0.02d,
                "缩放一页显示时同一比例缩小，补的那截小数也同比缩小");
    }

    /** 撑起行高的那张脸按 pt × 比值算，不是按比值单独算。 */
    private static void tallestWeighsSizeNotJustRatio() {
        DocxDocument.ParagraphBlock body = paragraph("宋体", "Times New Roman",
                new Object[]{"接头性能随连接时间变化。", "宋体", "Times New Roman", 12, false});
        WordLineHeights.Height h = WordLineHeights.tallest(body, REAL, "宋体");
        close(h.pt, 12d * SONG_EM, 0.002d, "宋体正文的行高由宋体撑（12pt × 1.31335）");
        check(DocxFontAssets.SONG.equals(DocxFontAssets.pathFor(h.family)),
                "宋体实测 1.31335 em 已高于 Times 的 1.17665 em，中西混排时说话的是宋体");

        DocxDocument.ParagraphBlock heading = paragraph("黑体", "黑体",
                new Object[]{"第一章 绪论", "黑体", "黑体", 16, false},
                new Object[]{"（见表 1-1）", "黑体", "Times New Roman", 12, false});
        WordLineHeights.Height hh = WordLineHeights.tallest(heading, REAL, "黑体");
        close(hh.pt, 16d, 0.002d, "16pt 黑体标题的行高由黑体撑（16pt × 1.000 > 12pt × 1.17665）");
        check(DocxFontAssets.HEI.equals(DocxFontAssets.pathFor(hh.family)),
                "字号大的没量过的脸赢下行高：这一段不许带小数");

        DocxDocument.ParagraphBlock sup = paragraph("宋体", "Times New Roman",
                new Object[]{"混凝土强度等级为 C30", "宋体", "Times New Roman", 12, false},
                new Object[]{"2", "宋体", "Times New Roman", 8, true});
        close(WordLineHeights.tallest(sup, REAL, "宋体").pt, 12d * SONG_EM, 0.002d,
                "8pt 上标按 OS/2 折算后仍然撑不高 12pt 正文这一行：上下标不再改分页");
    }

    /** 只有撑起行高那张脸量过，才允许带小数行距；没量过的字体行为一个字节都不改。 */
    private static void onlyMeasuredFacesCarry() {
        boolean saved = WordLineHeights.APPLIED_TO_LAYOUT;
        WordLineHeights.APPLIED_TO_LAYOUT = true;
        check(WordLineHeights.carries(paragraph("宋体", "Times New Roman",
                new Object[]{"正文。", "宋体", "Times New Roman", 12, false}), REAL, "宋体"),
                "宋体正文带实测行高");
        check(WordLineHeights.carries(paragraph("SimSun", "Arial",
                new Object[]{"正文。", "SimSun", "Arial", 12, false}), REAL, "SimSun"),
                "SimSun 与宋体认到同一张 song.ttc，实测 1.31335 也高过 Arial 的 1.1172，照旧算量过");
        check(WordLineHeights.carries(paragraph("宋体", "Times New Roman",
                new Object[]{"References", "宋体", "Times New Roman", 12, false}), REAL, "宋体"),
                "纯西文段同样量过：Times 自己就在表里");
        check(!WordLineHeights.carries(paragraph("黑体", "黑体",
                new Object[]{"黑体小标题。", "黑体", "黑体", 12, false}), REAL, "黑体"),
                "纯黑体段没量过：照旧走两步取整，不补小数");
        check(!WordLineHeights.carries(paragraph("黑体", "黑体",
                new Object[]{"黑体标题", "黑体", "黑体", 16, false},
                new Object[]{"（见表 1-1）", "黑体", "Times New Roman", 12, false}), REAL, "黑体"),
                "16pt 黑体里夹 12pt Times：行高是黑体的，就不许拿 Times 的实测小数去补（旧写法在这里会误判成量过）");
        check(!WordLineHeights.carries(paragraph("黑体", "Calibri",
                new Object[]{"Acknowledgements", "黑体", "Calibri", 12, false}), REAL, "黑体"),
                "黑体配 Calibri：1.2207 高于黑体的 1.000，说话的是没量过的 Calibri，不补");
        check(!WordLineHeights.carries(paragraph("宋体", "Calibri",
                new Object[]{"Abstract", "宋体", "Calibri", 12, false}), REAL, "宋体"),
                "纯西文的 Abstract 由 Calibri 撑行高：段里写着宋体也不算量过。2.1.1 那版就是把中文脸的"
                        + " 1.31335 套到这 19 段参考文献上，每行多算 2.7~3.6px（layout-parity-target 第 7 节 A 表）");
        check(WordLineHeights.carries(paragraph("宋体", "Times New Roman",
                new Object[]{"接头性能随连接时间变化，Cu 含量 3%。", "宋体", "Times New Roman", 12, false}),
                REAL, "宋体"),
                "中西混排：这一笔里有中文，中文脸 1.31335 高于 Times 1.17665，照旧带实测行高");
        check(!WordLineHeights.carries(null, REAL, "宋体"), "空段落返回 false，不许抛");
        check(!WordLineHeights.carries(paragraph("宋体", "Times New Roman"), REAL, "宋体"),
                "整段没有可测的 run：退回段默认字体判断，不猜");
        WordLineHeights.APPLIED_TO_LAYOUT = saved;
        // 出厂状态必须是被关掉的那一个，且关的是"用不用实测值"，不是把数改小。
        check(!WordLineHeights.APPLIED_TO_LAYOUT,
                "APPLIED_TO_LAYOUT 出厂为 false：实测行高不参与分页（cand-lh1 真机对账：行高中位误差已经是 0.000px，"
                        + " 但错页段 7→72、页数 28→29；HEAD 的 96.6% 是反向误差抵出来的，账在第 13 节）");
        check(!WordLineHeights.carries(paragraph("宋体", "Times New Roman",
                new Object[]{"正文。", "宋体", "Times New Roman", 12, false}), REAL, "宋体"),
                "开关关掉之后宋体正文也不带小数行距，也不带垂下量：分页与 2.1.0 一字不差");
        check(WordLineHeights.ratioFor(DocxFontAssets.SONG) != null,
                "关掉的是套用，不是把实测数删掉：表还留着，重开之前不必重新量");
    }

    /**
     * Word 分页看的是基线，不是整行行盒。真值：版心 865.53px（841.9 - 107.7 - 85.05 = 649.15pt）、
     * 12pt 宋体 / w:line=300 行距 26.267px，Word 每页实排 33 行（artifacts/word/page_budget.tsv 里
     * 排得最满那页的 last_body_line）。33 × 26.267 = 866.81px 已经比版心高，按整行行盒判只装得下 32 行
     * ——手机上那一版就是在这里每页少一行，整篇比 Word 多出两页（word-parity：错位段 50 → 74）。
     */
    private static void pageBudgetCountsBaselinesNotLineBoxes() {
        float capacity = 865.5333f;
        float advance = WordLineHeights.advancePx(12f * SONG_EM, 300, 1f);
        int ascent = Math.round(26f * 0.8f);                       // Spacing.chooseHeight 画上那一档
        float hang = advance - ascent;
        close(advance, WORD_CJK_PX, 0.02d, "这一版每行占的高度就是 Word 实测的 26.267px");
        close(hang, 5.267d, 0.01d, "末行允许垂到版心以下的量（26.267 - 21）");
        close(ascent + 32d * advance, 861.54d, 0.02d,
                "第 33 行的基线落在 861.54px，还在 865.53px 的版心里：所以 Word 排得下 33 行");
        check(linesOnPage(capacity, advance, 0f, 40) == 32,
                "整行行盒装进版心：只排得下 32 行——这正是手机上多出来的那两页");
        check(linesOnPage(capacity, advance, hang, 40) == 33,
                "把末行的下伸还给版心：排 33 行，与 Word 每页行数对上");
        check(linesOnPage(capacity, advance, hang, 100) == 33,
                "垂下去的只有末行：一段 100 行也只能排 33 行，不会一路垂下去");
        check(twoParagraphsOnFirstPage(capacity, advance, hang) == 33,
                "两段各 20 行：第一页合起来仍是 33 行，垂下量不按段重复发放");
        check(linesOnPage(capacity, advance, 0f, 10) == 10,
                "没量过的段落（hang=0）判断一字未改：整段照旧按行盒算");
    }
    private static PageBreaker.Item item(float advance, int lines, float hang) {
        float[] heights = new float[lines];
        for (int i = 0; i < lines; i++) heights[i] = advance;
        PageBreaker.Item item = new PageBreaker.Item(0, heights);
        item.hang = hang;
        return item;
    }
    private static int linesOnPage(float capacity, float advance, float hang, int lines) {
        java.util.List<PageBreaker.Page> pages =
                PageBreaker.paginate(Collections.singletonList(item(advance, lines, hang)), capacity);
        return pages.get(0).fragments.get(0).endLine;
    }
    private static int twoParagraphsOnFirstPage(float capacity, float advance, float hang) {
        ArrayList<PageBreaker.Item> items = new ArrayList<PageBreaker.Item>();
        items.add(item(advance, 20, hang));
        items.add(item(advance, 20, hang));
        java.util.List<PageBreaker.Page> pages = PageBreaker.paginate(items, capacity);
        int counted = 0;
        for (PageBreaker.Fragment f : pages.get(0).fragments) counted += f.endLine - f.startLine;
        return counted;
    }

    /** 换行高比值这件事只能动行高，不能顺手把上下标和基线占比一起动了。 */
    private static void overrideKeepsEverythingElse() {
        FontScriptMetrics base = FontScriptMetrics.DEFAULT;
        FontScriptMetrics overridden = base.withLineHeight(SONG_EM);
        close(overridden.lineHeightRatio, SONG_EM, 0.0005d, "套上实测值之后行高比值就是实测值");
        close(overridden.ascentFraction, base.ascentFraction, 0.00001d,
                "上下伸占比原样保留：基线位置不许跟着动");
        close(overridden.superscriptScale, base.superscriptScale, 0.00001d, "上标缩放不许被带着改");
        close(overridden.subscriptOffset, base.subscriptOffset, 0.00001d, "下标偏移不许被带着改");
        check(overridden != base, "换行高要产出新对象，缓存里那份表推导的数不许被就地改掉");
        check(base.withLineHeight(0f) == base && base.withLineHeight(-1f) == base,
                "比值不合法就原样返回，不许把行高压成 0");
        close(REAL.forFamily("宋体").lineHeightRatio, SONG_EM, 0.0005d,
                "随包 song.ttc 读完表再套实测值：手机与这里读到的是同一条口径");
        close(REAL.forFamily("黑体").lineHeightRatio, 1.0d, 0.0005d,
                "黑体没套实测值，仍是字库表里的 1.000 em");
    }

    /**
     * 行高由这一行里真正落了笔的那张脸说了算，不是由段里写着哪几张脸说了算。
     *
     * 这条是 2.1.1 实测行高被真机对账否掉的直接原因之一：`tallest` 原来无条件取
     * max(中西比值)，纯西文的参考文献段被中文脸抬到 26.267px，Word 量到 23.533px，
     * 19 段每行多算 2.7~3.6px，整篇多算出一页半（docs/layout-parity-target.md 第 7 节 A/B 表）。
     */
    private static void faceFollowsTheCharactersOnTheLine() {
        boolean savedFlag = WordLineHeights.APPLIED_TO_LAYOUT;
        WordLineHeights.APPLIED_TO_LAYOUT = true;
        try {
        WordLineHeights.Height latin = WordLineHeights.tallest(
                paragraph("宋体", "Times New Roman",
                        new Object[]{"[12] Kim T, Su W. Copper bonding at 250 C for 30 min.",
                                "宋体", "Times New Roman", 12, false}), REAL, "宋体");
        close(latin.pt, 12d * TIMES_EM, 0.002d, "纯西文段按 Times 算行高（12pt × 1.17665），不跟中文脸走");
        check(DocxFontAssets.TIMES.equals(DocxFontAssets.pathFor(latin.family)),
                "撑起纯西文行高的是 Times：这一段的小数按 23.533px 补，不是 26.267px");

        WordLineHeights.Height cjk = WordLineHeights.tallest(
                paragraph("宋体", "Times New Roman",
                        new Object[]{"接头强度 245MPa，孔隙率 3.1%。", "宋体", "Times New Roman", 12, false}),
                REAL, "宋体");
        close(cjk.pt, 12d * SONG_EM, 0.002d, "同字号混排仍由中文脸撑：12pt × 1.31335");
        check(DocxFontAssets.SONG.equals(DocxFontAssets.pathFor(cjk.family)),
                "这一行里有中文，中文脸就是说了算的那一张");

        WordLineHeights.Height punct = WordLineHeights.tallest(
                paragraph("宋体", "Times New Roman",
                        new Object[]{"\u3001\u3002\uff08\uff09\u300c\u300d", "宋体", "Times New Roman", 12, false}),
                REAL, "宋体");
        check(DocxFontAssets.SONG.equals(DocxFontAssets.pathFor(punct.family)),
                "全角标点 、。（）「」 归中文脸：中文行不会因为没有汉字就矮下去");
        WordLineHeights.Height ambiguous = WordLineHeights.tallest(
                paragraph("宋体", "Times New Roman",
                        new Object[]{"\u2014\u2026\u201c\u201d\u00b7", "宋体", "Times New Roman", 12, false}),
                REAL, "宋体");
        check(DocxFontAssets.TIMES.equals(DocxFontAssets.pathFor(ambiguous.family)),
                "宽度歧义那一批（— … “ ” ·）归西文脸：唯一的西文真值 23.533px 就是靠这条守住的");
        check(WordLineHeights.isEastAsianChar('\u6c49') && WordLineHeights.isEastAsianChar('\u3001')
                        && WordLineHeights.isEastAsianChar('\uff08') && WordLineHeights.isEastAsianChar('\uac00'),
                "汉字、CJK 标点、全角形式、谚文都算中文字符");
        check(!WordLineHeights.isEastAsianChar('A') && !WordLineHeights.isEastAsianChar('3')
                        && !WordLineHeights.isEastAsianChar('.') && !WordLineHeights.isEastAsianChar('\u2014'),
                "字母、数字、半角符号与那个破折号都不算中文字符");
        } finally {
            WordLineHeights.APPLIED_TO_LAYOUT = savedFlag;
        }
    }

    /**
     * 按字符选脸这条必须与实测行高同进同退，不许单独开。
     *
     * 字库表口径下中文脸只有 1.000 em（Word 实测量到的是 1.313 em），HEAD 一直靠"取中西较大值"
     * 用西文脸的 1.1074 em 把中文行垫到 23px。关掉开关时就继续这么垫（与 2.1.0 逐字节一致）；
     * 要是先把按字符选脸单独开出去，170 段 snapToGrid=false 的正文会从 23px 掉到 20px，离 Word
     * 的 25.8px 更远（snap 段还有网格地板兜着，非 snap 段没有）。这是 cand-lh1 真机对账之后补的
     * 一条闸，账记在 docs/layout-parity-target.md 第 13 节。
     */
    private static void characterFaceRuleTravelsWithTheSwitch() {
        DocxDocument.ParagraphBlock cjk = paragraph("宋体", "Times New Roman",
                new Object[]{"接头性能随连接时间变化。", "宋体", "Times New Roman", 12, false});
        DocxDocument.ParagraphBlock latinOnly = paragraph("宋体", "Times New Roman",
                new Object[]{"[12] Kim T, Su W.", "宋体", "Times New Roman", 12, false});
        boolean saved = WordLineHeights.APPLIED_TO_LAYOUT;
        try {
            WordLineHeights.APPLIED_TO_LAYOUT = false;
            close(WordLineHeights.tallest(cjk, TABLE, "宋体").pt, 12d * 1.1074d, 0.002d,
                    "关掉开关：中文行照旧取中西较大值（西文脸 1.1074 垫着），与 2.1.0 一字不差");
            close(WordLineHeights.tallest(latinOnly, TABLE, "宋体").pt, 12d * 1.1074d, 0.002d,
                    "纯西文段本来就走西文脸：开关前后一样，这条改动没碰它");
            WordLineHeights.APPLIED_TO_LAYOUT = true;
            close(WordLineHeights.tallest(cjk, TABLE, "宋体").pt, 12d * 1.0d, 0.002d,
                    "开着开关：这一行里没有西文字符，中文脸表里的 1.000 自己说话——这正是要与实测值同开的原因");
            close(WordLineHeights.tallest(latinOnly, TABLE, "宋体").pt, 12d * 1.1074d, 0.002d,
                    "开着开关：纯西文行仍按西文脸 1.1074，段里写着宋体也不算数");
        } finally {
            WordLineHeights.APPLIED_TO_LAYOUT = saved;
        }
    }

    /**
     * 固定值行距（w:lineRule="exact"）是一把绝对长度，不是倍数。真机量到文档坐标到像素的比例是
     * 1.000（tools/line-spacing-probe.ps1，CDY-AN90 sdk 29：12pt 的 getTextSize()=16.0000 文档px、
     * 1pt=1.333333px、1440twips=96.0px），所以 20 磅就是 26.6667px、16 磅就是 21.3333px。
     * 行盒必须整数、分页高度不许：否则一行差 0.3333px，一页 30 行就差 10px。
     */
    private static void fixedLineHeightIsAnAbsoluteLength() {
        close(WordLineHeights.fixedAdvancePx(400), 26.6667d, 0.0002d,
                "固定值 20 磅 = 400twips = 26.6667 文档px，中间不取整");
        close(WordLineHeights.fixedAdvancePx(320), 21.3333d, 0.0002d, "固定值 16 磅 = 21.3333px");
        close(WordLineHeights.fixedAdvancePx(380), 25.3333d, 0.0002d,
                "input-liu.docx 全篇唯一那条 exact（封面段 line=380）：25.3333px，旧口径按 25px 收费");
        check(WordLineHeights.fixedBoxPx(400, 1f) == 27,
                "平台画的是整像素行盒 27px，剩下的 0.3333px 走 lineCarry");
        check(WordLineHeights.fixedBoxPx(380, 1f) == 25, "380twips 行盒 25px，差额 0.3333px/行");
        close(WordLineHeights.fixedAdvancePx(400) / (20d * 96d / 72d), 1.0d, 0.0001d,
                "比例锁在 1.000：真机 px/em 量到 1.000，Word 真值里 0 段的相邻基线是 20.0pt，"
                        + "所以 1154/1080 那个 1.0741 不许进引擎");
    }

    public static void main(String[] args) {
        tableHoldsOnlyMeasuredFaces();
        advanceIsOneStepFractional();
        tallestWeighsSizeNotJustRatio();
        onlyMeasuredFacesCarry();
        faceFollowsTheCharactersOnTheLine();
        characterFaceRuleTravelsWithTheSwitch();
        pageBudgetCountsBaselinesNotLineBoxes();
        overrideKeepsEverythingElse();
        fixedLineHeightIsAnAbsoluteLength();
        System.out.println("SUMMARY " + checks + " line-height assertions passed (Word truth from "
                + "desktop Word 16.0 COM baselines, font metrics read from the bundled assets; "
                + "device rendering is checked by capture-device/word-parity, not here).");
    }
}
