package com.rikkahub.wordlite;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Locale;

/**
 * 两侧对齐闸门：{@code tools/aigc-instrument-golden.py} 用随包表算出来的每一段（仓库标注语料 +
 * 一批边界样本）× 19 条判据取值 + 段分，Java 侧 {@link AigcRhythm} 必须逐条复现。
 * 量台与手机算的不是同一个数，这条当场红。
 *
 * <p><b>为什么按 id 而不是按行序。</b>黄金样本的列序是表里的行序，Java 侧的槽位是 {@code ids} 里定的
 * 19 格；比对走的是"表里那条判据的 id → Java 那一格"，所以表改了行序、加了行、或者有人把 L 族挪到
 * R 族前面，都照样量得准——也正因此，只要有人把某条判据接错格子（把 L04 的熵填进 R04 的位置），这里立刻炸。
 *
 * <p><b>容差。</b>单条判据 {@code |java - golden| <= 1e-6 × max(1, |golden|)}；段分
 * {@code |java - golden| <= 1e-4}（黄金样本每列只存 10 位有效数字，本身带 ~1e-10 的舍入）。
 * 每条判据的最坏偏差逐条打印，另附有效字数逐段比对——分句或计数口径漂了，先从这里露出来。
 *
 * <p>顺带把表头出处也钉住：判定线、真人刻度三把线、外部考试、划线误报与命中、配对规模，
 * 全都必须是从表里读出来的数，代码里不许抄死。缺黄金样本或缺随包表一律判失败，不许静默跳过。
 * 跑法（工作目录＝仓库根）：
 * <pre>    java com.rikkahub.wordlite.AigcInstrumentParityRegression</pre>
 */
public final class AigcInstrumentParityRegression {

    /** 单条判据的容差：相对 1e-6，绝对下限 1e-6（黄金样本给的是 10 位有效数字）。 */
    private static final double VALUE_TOLERANCE = 1e-6;
    /** 段分的容差：绝对 1e-4。 */
    private static final double SCORE_TOLERANCE = 1e-4;

    private static final String DEFAULT_TABLE = "app/src/main/assets/aigc/aigc-rhythm.tsv";
    private static final String DEFAULT_GOLDEN = "tests/corpus/aigc-instrument-golden.tsv";

    private static int checks;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }

    /** 一行的读数：判据取值 + 段分 + 有效字数 + 原文。 */
    private static final class Row {
        String id = "", source = "", text = "";
        int validChars;
        final double[] values = new double[AigcRhythm.CRITERIA];
        double score;
    }

    public static void main(String[] args) throws Exception {
        String tablePath = args.length > 0 ? args[0] : DEFAULT_TABLE;
        String goldenPath = args.length > 1 ? args[1] : DEFAULT_GOLDEN;

        AigcRhythm.Table table = install(tablePath);
        check(table.matched == AigcRhythm.CRITERIA,
                "随包表 " + tablePath + " 只对上 " + table.matched + " 条判据，期望 "
                        + AigcRhythm.CRITERIA + " 条（R01~R15 + L02~L05）");
        check(AigcRhythm.engaged(), "装载之后这件仪器仍然不干活：表或真人分位不完整，比对无意义");
        check(AigcRhythm.CRITERIA == 19, "判据条数是 " + AigcRhythm.CRITERIA + "，这台仪器的刻度是按 19 条定的");
        check(AigcRhythm.VERSION.equals(table.version),
                "随包表版本是 " + table.version + "，代码期望 " + AigcRhythm.VERSION
                        + "：黄金样本是按哪一版算的就此失去意义，先让两边同一版再跑");

        // 判定线与真人刻度必须是表里读出来的：换一版定标表，界面上那条线要跟着走，代码里不许抄死。
        check(Math.abs(AigcRhythm.humanP99() - 4.5585d) < 1e-9d
                        && Math.abs(AigcRhythm.humanP99() - table.humanP99) < 1e-12d,
                "判定线不是表里那条 human_p99（表 " + table.humanP99 + "，humanP99() 给 "
                        + AigcRhythm.humanP99() + "）");
        check(Math.abs(table.humanP50 - 0.4496d) < 1e-9d && Math.abs(table.humanP90 - 2.958d) < 1e-9d
                        && Math.abs(table.humanMax - 7.4108d) < 1e-9d,
                "真人刻度读表读歪了：human_p50=" + table.humanP50 + " human_p90=" + table.humanP90
                        + " human_max=" + table.humanMax);
        // 表头注释里的出处也得真解析出来——解析不出来就会悄悄退回代码里那个数，换一版表就谎报。
        check(Math.abs(table.pairs - 2531d) < 1e-9d,
                "calibration 里的配对规模解析不出来（读到 " + table.pairs + "），出处行会退回代码常量");
        check(Math.abs(table.falsePositive - 1.11d) < 1e-9d && Math.abs(table.machineHit - 41.9d) < 1e-9d
                        && Math.abs(AigcRhythm.falsePositivePerMille() - 11.1d) < 1e-9d,
                "划线那一档的误报/命中没从表头读出来：" + table.falsePositive + "% / "
                        + table.machineHit + "%，每千段误报 " + AigcRhythm.falsePositivePerMille());
        check(Math.abs(table.externalRaw - 0.9978d) < 1e-9d && Math.abs(table.externalEvasive - 0.9971d) < 1e-9d
                        && Math.abs(table.externalDomain - 0.9602d) < 1e-9d,
                "外部考试三场解析歪了：" + table.externalRaw + " / " + table.externalEvasive + " / "
                        + table.externalDomain + "（external=" + table.external + "）");
        check(Math.abs(table.segmentAuc - 0.9295d) < 1e-9d && Math.abs(table.documentAuc - 0.9816d) < 1e-9d,
                "段级/整篇 AUC 没从表头读出来：" + table.segmentAuc + " / " + table.documentAuc);
        check(table.caveat.contains("未验证"),
                "表头那句未验证限定没读到，界面上会退回代码里那句默认话（" + table.caveat + "）");

        ArrayList<String> ids = new ArrayList<String>();
        ArrayList<Row> rows = readGolden(goldenPath, ids);
        check(!rows.isEmpty(), "黄金样本 " + goldenPath + " 里一行都没有");

        // 列 -> Java 槽位。认不上就是有人在两侧之间改了 id 或改了格子，不比对直接红。
        int[] slot = new int[ids.size()];
        for (int c = 0; c < ids.size(); c++) {
            slot[c] = -1;
            for (int i = 0; i < table.criteria.size(); i++) {
                AigcRhythm.Criterion criterion = table.criteria.get(i);
                if (ids.get(c).equals(criterion.id)) slot[c] = criterion.feature;
            }
            check(slot[c] >= 1 && slot[c] <= AigcRhythm.CRITERIA,
                    "黄金样本里的判据 " + ids.get(c) + " 在 Java 侧没有对应槽位（feature="
                            + (slot[c] < 0 ? "无" : String.valueOf(slot[c])) + "）");
        }
        check(table.criteria.size() == ids.size(),
                "随包表 " + table.criteria.size() + " 行，黄金样本 " + ids.size() + " 列：两边条数不一致");
        // 四条词汇判据必须落在 RHYTHM+1..RHYTHM+4 这四格上：接错格子（把熵填进比率的位置）就是这里炸。
        for (int i = 0; i < AigcRhythm.LEXICAL.length; i++) {
            String lexicalId = AigcRhythm.LEXICAL[i];
            int at = ids.indexOf(lexicalId);
            check(at >= 0 && slot[at] == AigcRhythm.RHYTHM + 1 + i,
                    "词汇判据 " + lexicalId + " 落在槽位 " + (at < 0 ? "无" : String.valueOf(slot[at]))
                            + "，期望 " + (AigcRhythm.RHYTHM + 1 + i) + "：四条词汇判据接错格子");
        }

        // 改法与格子必须一一对上：adviceOf 拿 1 基槽位当 0 基下标用的话，界面上每条判据都会念出下一条的改法。
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i), name = AigcRhythm.nameOf(id);
            String byId = AigcRhythm.adviceOf(id), byName = AigcRhythm.adviceOf(name);
            check(byId.length() > 0 && byId.equals(byName),
                    "判据 " + id + "（" + name + "）的改法对不上：按 id 给「" + byId + "」，按中文名给「"
                            + byName + "」");
            check(slot[i] >= 1 && slot[i] <= AigcRhythm.CRITERIA,
                    "判据 " + id + " 的槽位越界：" + slot[i]);
        }

        double[] worstAbs = new double[ids.size()];
        double[] worstRel = new double[ids.size()];
        String[] worstRow = new String[ids.size()];
        double worstScore = 0d;
        String worstScoreRow = "";
        ArrayList<String> problems = new ArrayList<String>();
        int charMismatch = 0;

        for (int r = 0; r < rows.size(); r++) {
            Row golden = rows.get(r);
            AigcRhythm.Reading reading = AigcRhythm.measure(golden.text, 0, golden.text.length());
            check(reading.measured, "第 " + golden.id + " 段没量到值（" + reading.note + "）");
            if (reading.validChars != golden.validChars) {
                charMismatch++;
                problems.add(golden.id + " 有效字数 Java " + reading.validChars + " vs Python "
                        + golden.validChars + "：" + golden.source);
            }
            for (int c = 0; c < ids.size(); c++) {
                double want = golden.values[c];
                double have = reading.values[slot[c] - 1];
                double abs = Math.abs(have - want);
                double rel = abs / Math.max(1d, Math.abs(want));
                if (worstRow[c] == null || abs > worstAbs[c]) {
                    worstAbs[c] = abs;
                    worstRel[c] = rel;
                    worstRow[c] = golden.id;
                }
                if (!(rel <= VALUE_TOLERANCE) || Double.isNaN(have)) {
                    if (problems.size() < 40)
                        problems.add(golden.id + " 判据 " + ids.get(c) + " Java " + fmt(have, 12)
                                + " vs Python " + fmt(want, 12) + "（绝对 " + fmt(abs, 3) + "）");
                }
            }
            double abs = Math.abs(reading.score - golden.score);
            if (abs > worstScore) {
                worstScore = abs;
                worstScoreRow = golden.id;
            }
            if (!(abs <= SCORE_TOLERANCE) || Double.isNaN(reading.score))
                if (problems.size() < 40)
                    problems.add(golden.id + " 段分 Java " + fmt(reading.score, 12) + " vs Python "
                            + fmt(golden.score, 12) + "（绝对 " + fmt(abs, 3) + "）：" + golden.source);
        }

        System.out.println("黄金样本 " + goldenPath + "：" + rows.size() + " 段 × " + ids.size()
                + " 条判据，表版本 " + table.version);
        System.out.println(String.format(Locale.US, "%-6s %-22s %-11s %-11s %s",
                "id", "判据", "最坏绝对差", "最坏相对差", "出在"));
        double worstValue = 0d;
        String worstValueId = "";
        for (int c = 0; c < ids.size(); c++) {
            System.out.println(String.format(Locale.US, "%-6s %-22s %-11s %-11s %s", ids.get(c),
                    AigcRhythm.nameOf(ids.get(c)), sci(worstAbs[c]), sci(worstRel[c]), worstRow[c]));
            if (worstAbs[c] > worstValue) {
                worstValue = worstAbs[c];
                worstValueId = ids.get(c);
            }
        }
        System.out.println(String.format(Locale.US, "段分最坏绝对差 %s（%s），判据最坏绝对差 %s（%s），有效字数不一致 %d 段",
                sci(worstScore), worstScoreRow, sci(worstValue), worstValueId,
                Integer.valueOf(charMismatch)));
        for (int i = 0; i < problems.size(); i++) System.out.println("FAIL " + problems.get(i));
        check(charMismatch == 0, "有 " + charMismatch + " 段的有效字数两侧对不上（分句或计数口径漂了）");
        check(problems.isEmpty(), "有 " + problems.size() + " 处超出容差（判据 " + VALUE_TOLERANCE
                + " 相对 / 段分 " + SCORE_TOLERANCE + " 绝对），上面逐条列了");
        check(worstRelMaximum(worstRel) <= VALUE_TOLERANCE,
                "最坏相对差 " + sci(worstRelMaximum(worstRel)) + " 超过 " + VALUE_TOLERANCE);
        check(worstScore <= SCORE_TOLERANCE,
                "段分最坏绝对差 " + sci(worstScore) + " 超过 " + SCORE_TOLERANCE);
        System.out.println("SUMMARY " + checks + " parity assertions passed on " + rows.size()
                + " golden paragraphs x " + ids.size() + " criteria (worst criterion diff "
                + sci(worstValue) + " on " + worstValueId + ", worst score diff " + sci(worstScore)
                + " on " + worstScoreRow + ", table " + table.version + ").");
    }

    private static double worstRelMaximum(double[] rel) {
        double out = 0d;
        for (int i = 0; i < rel.length; i++) if (rel[i] > out) out = rel[i];
        return out;
    }

    private static AigcRhythm.Table install(String path) throws IOException {
        InputStream in = open(path);
        AigcRhythm.Table table = AigcRhythm.install(in);
        check(AigcRhythm.current() == table, "装载之后 current() 不是刚装的那份表");
        return table;
    }

    /** 读黄金样本：注释行跳过，第一行非注释是表头（id source valid_chars R01..L05 score text）。 */
    private static ArrayList<Row> readGolden(String path, ArrayList<String> ids) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(open(path), Charset.forName("UTF-8")));
        ArrayList<Row> rows = new ArrayList<Row>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.length() == 0 || line.charAt(0) == '#') continue;
            String[] cells = line.split("\t", -1);
            if (ids.isEmpty()) {
                for (int i = 3; i < cells.length - 2; i++) ids.add(cells[i].trim());
                check(cells.length >= 22, "黄金样本表头只有 " + cells.length + " 列，读不出 19 条判据");
                continue;
            }
            check(cells.length == ids.size() + 5,
                    "黄金样本 " + cells[0] + " 有 " + cells.length + " 列，表头说的是 "
                            + (ids.size() + 5) + " 列");
            Row row = new Row();
            row.id = cells[0].trim();
            row.source = cells[1].trim();
            row.validChars = (int) number(cells[2], row.id + ".valid_chars");
            for (int i = 0; i < ids.size(); i++) row.values[i] = number(cells[3 + i], row.id + "." + ids.get(i));
            row.score = number(cells[3 + ids.size()], row.id + ".score");
            row.text = cells[cells.length - 1];
            rows.add(row);
        }
        reader.close();
        return rows;
    }

    private static InputStream open(String path) throws IOException {
        InputStream in = new FileInputStream(path);   // 打不开就抛：缺文件必须红，不许静默跳过
        return in;
    }

    private static double number(String raw, String where) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (Exception bad) {
            throw new AssertionError("黄金样本 " + where + " 不是数：" + raw);
        }
    }

    private static String fmt(double value, int width) {
        return String.format(Locale.US, "%" + width + ".10g", Double.valueOf(value));
    }

    private static String sci(double value) {
        return String.format(Locale.US, "%.3e", Double.valueOf(value));
    }
}
