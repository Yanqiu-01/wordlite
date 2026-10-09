package com.rikkahub.wordlite;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;

// 上下标不许把拉丁/数字串切成可断点：宿主这一侧钉得住的部分。
//
// 为什么要改：平台把每个 ReplacementSpan 的首尾都交给断行器当可断点，所以"串里再套一个上下标
// span"就等于在 Word 不许断的地方开两个口子。真机量到的那一条在 tests/samples/input-liu.docx
// 第 19 页、docx 第 159 段第 3 行："、孔洞率、Ag3" | "Sn分布"（tools/midword-audit.py 数的
// token_cut），而 Word 自己导出的 PDF 803 行里没有一行截断过一个拉丁/数字串。尺子与实例见
// docs/layout-parity-target.md 第 24.2 节：串内 span 边的条数在采样的 span-edges.tsv，实际切断
// 在 midword-audit，两边都在手机上量。
//
// 这个测试只吃字符与 run 的上下标标记（DocxTextLayout.scriptTokens 与宽度取整那两个静态方法）：宿主
// JVM 上 android 的 Paint/Spannable 一律是 Stub!，量不了字宽也造不了 span。所以钉在这里的是分节
// 口径、整串一次取整的算式、以及"上下标不抬高整行"那条盒算术；换行点与像素在手机上量，不在这里充数。
public class SuperscriptPaginationRegression {
    private static int checks;

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
        System.out.println("  PASS " + what);
    }

    private static void same(String what, String expected, String actual) {
        check(expected.equals(actual), what + " expected=" + expected + " actual=" + actual);
    }

    private static String shape(DocxTextLayout.ScriptToken token) {
        StringBuilder kinds = new StringBuilder();
        for (int kind : token.kind) kinds.append(kind);
        return "[" + token.start + "," + token.end + ") kinds=" + kinds
                + " ends=" + Arrays.toString(token.partEnd) + " unicodeOnly=" + token.unicodeOnly;
    }

    /** 把上下标 run 的区间标到字符上：引擎里 apply() 看到的是同一件事。 */
    private static boolean[] marks(String text, int[]... ranges) {
        boolean[] marked = new boolean[text.length()];
        for (int[] range : ranges)
            for (int i = range[0]; i < Math.min(text.length(), range[1]); i++) marked[i] = true;
        return marked;
    }

    private static DocxTextLayout.ScriptToken only(String text, boolean[] vert, boolean[] sup) {
        DocxTextLayout.ScriptToken[] tokens = DocxTextLayout.scriptTokens(text, vert, sup);
        check(tokens.length == 1, "一个容器盖住 " + text + "，实际 " + tokens.length + " 个");
        return tokens.length == 1 ? tokens[0] : null;
    }

    public static void main(String[] args) throws Exception {
        // --- 1) 真稿那几句的分节（段落号是 docx 段落序）---
        String heading = "2.2.5  Ag/Ag3Sn调控与高温时效";              // para 29
        // 这一句的下标 run 是 "3Sn" 三个字符（docx 里就这么切的），分节因此是两节。
        DocxTextLayout.ScriptToken ag = only(heading, marks(heading, new int[]{12, 15}),
                new boolean[heading.length()]);
        same("para 29 的 Ag3Sn", "[10,15) kinds=02 ends=[2, 5] unicodeOnly=false", shape(ag));
        check(heading.charAt(ag.start) == 'A' && heading.charAt(ag.end - 1) == 'n',
                "斜杠不跟着进串：容器正好是 Ag3Sn，不是 Ag/Ag3Sn");

        String formulas = "以Cu₄₀Al₆₀合金为前驱体";                    // para 79
        DocxTextLayout.ScriptToken cu = only(formulas, new boolean[formulas.length()],
                new boolean[formulas.length()]);
        same("para 79 的 Cu₄₀Al₆₀（Unicode 上下标）",
                "[1,9) kinds=0303 ends=[2, 4, 6, 8] unicodeOnly=true", shape(cu));
        check(!cu.script[1] && cu.unicodeOnly,
                "Unicode 上下标本来就全尺寸画：它是普通一节，但它那条 span 的边也一并被容器吃掉");

        String imc = "区分Cu骨架、Cu6Sn5、Cu3Sn、Ag3Sn、残余Sn和孔洞";   // para 114
        // docx 里的下标 run：Cu6Sn5 的 6 与 5 各一条，Cu3Sn 的是 3，Ag3Sn 的是 3Sn
        boolean[] vert = marks(imc, new int[]{9, 10}, new int[]{12, 13},
                new int[]{16, 17}, new int[]{22, 25});
        DocxTextLayout.ScriptToken[] three =
                DocxTextLayout.scriptTokens(imc, vert, new boolean[imc.length()]);
        check(three.length == 3, "三个化合物各自一个容器，got " + three.length);
        same("Cu6Sn5 的两个下标在同一节序列里",
                "[7,13) kinds=0202 ends=[2, 3, 5, 6] unicodeOnly=false", shape(three[0]));
        same("Cu3Sn 只有一个下标 run（3），Ag3Sn 的那条 run 是 3Sn",
                "[14,19) kinds=020 ends=[2, 3, 5] unicodeOnly=false", shape(three[1]));
        same("Ag3Sn 的下标 run 连着 Sn 就是一节",
                "[20,25) kinds=02 ends=[2, 5] unicodeOnly=false", shape(three[2]));

        // 引文号那一族不动：[12] 整串本来就只有一个 span，没有内部边界要遮。
        String cited = "软化、蠕变甚至重熔[12]。";
        check(DocxTextLayout.scriptTokens(cited, marks(cited, new int[]{9, 13}),
                marks(cited, new int[]{9, 13})).length == 0,
                "引文号 [12] 不进容器：它今天就没有串内边（保持 2.6.5 的行为）");

        String gas = "在N2、H2气氛下保温";
        DocxTextLayout.ScriptToken[] pair = DocxTextLayout.scriptTokens(gas,
                marks(gas, new int[]{2, 3}, new int[]{5, 6}), new boolean[gas.length()]);
        check(pair.length == 2, "N2 与 H2 是两个串：顿号不许把它们粘成一串，got " + pair.length);

        check(DocxTextLayout.scriptTokens("SAC305浸渗", new boolean[8], new boolean[8]).length == 0,
                "没有上下标的串一个容器也不建");

        // --- 2) 整篇真稿：容器数，以及"还会在串里留下边的 run 是不是都进了容器" ---
        InputStream in = new FileInputStream("tests/samples/input-liu.docx");
        DocxDocument doc;
        try {
            doc = DocxParser.parse(in, "input-liu.docx");
        } finally {
            in.close();
        }
        int tokens = 0, edges = 0, covered = 0, citationRuns = 0, citationCovered = 0;
        StringBuilder coveredCitations = new StringBuilder();
        StringBuilder leftOutside = new StringBuilder();
        for (DocxDocument.ParagraphBlock p : doc.paragraphs) {
            String text = p.text;
            if (text == null || text.length() == 0) continue;
            boolean[] v = new boolean[text.length()];
            boolean[] s = new boolean[text.length()];
            int cursor = 0;
            for (DocxDocument.Run run : p.runs) {
                int end = Math.min(text.length(), cursor + run.text.length());
                boolean script = run.style != null && (run.style.superscript || run.style.subscript);
                if (script) {
                    // 引文号那一族（[1]…[36]）整串本来就只有一个 span：它不许被容器吃掉，行为一字不改。
                    boolean brackets = text.substring(cursor, end).startsWith("[");
                    if (brackets) citationRuns++;
                    boolean alone = false;
                    for (DocxTextLayout.ScriptToken t : DocxTextLayout.scriptTokens(text,
                            marks(text, new int[]{cursor, end}), marks(text, new int[]{cursor, end})))
                        if (cursor >= t.start && end <= t.end) alone = true;
                    if (brackets && alone) {
                        citationCovered++;
                        if (coveredCitations.length() < 200)
                            coveredCitations.append(text, cursor, end).append(' ');
                    }
                }
                for (int i = cursor; i < end; i++) {
                    v[i] = script;
                    s[i] = script && run.style.superscript;
                }
                cursor = end;
            }
            DocxTextLayout.ScriptToken[] ts = DocxTextLayout.scriptTokens(text, v, s);
            tokens += ts.length;
            for (DocxTextLayout.ScriptToken t : ts)
                check(text.charAt(t.start) != '[' && text.charAt(t.end - 1) != ']',
                        "容器从不吃引文号的方括号：" + text.substring(t.start, t.end));
            // 与手机上 span-edges.tsv 同一把尺：串内部的位置（两边的字符都被 Word 粘住），
            // 如果今天有一条上下标 span 的边落在它上面，合并之后必须被某个容器盖住——容器的边
            // 只落在串首与串尾，那两条 Word 自己也会断。
            boolean[] spanned = new boolean[text.length()];
            for (int i = 0; i < text.length(); i++)
                spanned[i] = v[i] || FontScriptMetrics.unicodeScript(text.charAt(i)) != 0;
            for (int i = 1; i < text.length(); i++) {
                if (!glued(text.charAt(i - 1)) || !glued(text.charAt(i))) continue;
                if (spanned[i] == spanned[i - 1]) continue;
                edges++;
                boolean inside = false;
                for (DocxTextLayout.ScriptToken t : ts) if (t.start < i && i < t.end) inside = true;
                if (!inside && leftOutside.length() < 240)
                    leftOutside.append(" [").append(text, Math.max(0, i - 5), Math.min(text.length(), i + 5))
                            .append("]");
                covered += inside ? 1 : 0;
            }
        }
        check(tokens == 39, "真稿里该被容器盖住的串数 = 39（Ag3Sn、Cu6Sn5、Cu₄₀Al₆₀、Ni₂₈In₇₂、N2…），got "
                + tokens);
        check(edges > 0 && covered == edges,
                "每一条落在串内部的上下标边都被容器盖住：edges=" + edges + " covered=" + covered
                        + " 漏掉=" + leftOutside);
        check(citationCovered == 0,
                "引文号那一族一条都没进容器（" + citationRuns + " 条 run 照 2.6.5 那样排）："
                        + coveredCitations);

        // --- 3) 宽度：一个串一次取整 ---
        // Word 拿浮点字宽排线，只在断行那一步比大小；一个 span 一节地各取一次整是改之前的记法。
        // 真值：tools/superscript-attribution.py 在 input-liu.docx 可用的 35 条含上标行上量到的差额
        // （我方-Word）中位 +0.50 px、最小 +0.14 px，全是正号——只多要，不少要。
        float[] ag3sn = {21.40f, 5.36f, 26.80f};     // 5.36 = Word PDF 里一个 12pt 下标数字的占位
        int one = DocxTextLayout.ScriptTokenSpan.billedAsOneToken(ag3sn);
        int each = DocxTextLayout.ScriptTokenSpan.billedPerPart(ag3sn);
        check(one == (int) Math.ceil(21.40f + 5.36f + 26.80f), "整串一次取整：got " + one);
        check(one <= each, "整串记法不许比逐节记法要得多：" + one + " vs " + each);
        check(each - one < ag3sn.length, "逐节多要的那几分之一像素不超过节数：" + (each - one));
        check(DocxTextLayout.ScriptTokenSpan.billedAsOneToken(new float[]{12.38f})
                        == DocxTextLayout.ScriptTokenSpan.billedPerPart(new float[]{12.38f}),
                "只有一节的串（引文号那一族）两种记法同值：合并没动它的账");
        check(DocxTextLayout.ScriptTokenSpan.billedAsOneToken(new float[]{0f}) == 1,
                "空节不许把串记成 0 宽");

        // --- 4) 上下标不许抬高整行 ---
        // 容器交给行的仍是基准字体的行度（getSize 里 paint.getFontMetricsInt），需求那一头走
        // ScriptGeometry.declared：声明正文字号的 vertAlign run 与正文抢同一格。真机 31 条含上标行
        // 里我方比本段平线高出 >0.4 px 的行数 = 0/31，Word 自己也是 0.000（superscript-attribution
        // 第 1 表）；行高 p90 那条尾巴与上标无关——同档的无上标对照行一样高。
        FontScriptMetrics metrics = FontScriptMetrics.DEFAULT;
        ScriptGeometry declared = ScriptGeometry.declared(metrics);
        int[] grown = ScriptGeometry.lineBox(22, 6, Math.round(16f * declared.ascentEm),
                Math.round(16f * declared.descentEm), 25, false, true);
        same("12 pt 正文里的 vertAlign run 一格都不多要", "{22, 6}", "{" + grown[0] + ", " + grown[1] + "}");
        int[] bigScript = ScriptGeometry.lineBox(22, 6, Math.round(32f * declared.ascentEm),
                Math.round(32f * declared.descentEm), 0, false, true);
        check(bigScript[0] > 22, "声明得比正文大的上下标才长行（实验室量到 24 pt 的上下标高 15.333 px）："
                + bigScript[0]);

        // --- 5) 并度量 span 的那条边界规则（metricSpanAction）---
        // 真稿 para 82 的第一个串是 Ni28In72 [150,158)。apply() 给整个 OOXML run 挂了一条
        // PointSizeSpan，这里取 [140,400)：它从串外盖住整个串，本来必须原地不动。上一版先判
        // 左边界，于是把它截到 150，该 run 后面的字符全丢了字号，para 82 与 Word 一致的断点
        // 从 8 处掉到 0 处（tools/parity-six.ps1 -Tag sup-fix2：断点一致率 227/353 变 220/359）。
        int ts = 150, te = 158;
        check(DocxTextLayout.metricSpanAction(ts, te, 140, 400) == DocxTextLayout.METRIC_KEEP,
                "盖住整串的 run 级 PointSizeSpan 不许截：para 82 就是被它截断后丢了 8 处断点");
        check(DocxTextLayout.metricSpanAction(ts, te, ts, te) == DocxTextLayout.METRIC_KEEP,
                "边界与串完全重合的 span 原地不动（它的两条边正是 Word 自己允许断的地方）");
        check(DocxTextLayout.metricSpanAction(ts, te, 150, 152) == DocxTextLayout.METRIC_REMOVE,
                "串内的字符段 span 撤掉（它就是那个可断点）");
        check(DocxTextLayout.metricSpanAction(ts, te, 140, te) == DocxTextLayout.METRIC_KEEP,
                "左边在串外、右边与串尾重合的 span 同样不动（它的边都在串外）");
        check(DocxTextLayout.metricSpanAction(ts, te, 148, 152) == DocxTextLayout.METRIC_CUT_RIGHT,
                "从左边界探进来的 span 只留串外那半截");
        check(DocxTextLayout.metricSpanAction(ts, te, 155, 170) == DocxTextLayout.METRIC_CUT_LEFT,
                "从右边界探出去的 span 只留串外那半截");

        System.out.println("SuperscriptPaginationRegression: " + checks
                + " 条——容器分节 / 整串一次取整 / 行高不抬 / 并度量的边界规则 四组");
    }

    private static boolean glued(char c) {
        if (Character.isWhitespace(c) || isCjk(c)) return false;
        return Character.isLetterOrDigit(c) || FontScriptMetrics.unicodeScript(c) != 0;
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || b == Character.UnicodeBlock.HANGUL_SYLLABLES
                || b == Character.UnicodeBlock.HIRAGANA
                || b == Character.UnicodeBlock.KATAKANA
                || b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }
}
