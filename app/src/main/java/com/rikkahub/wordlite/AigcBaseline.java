package com.rikkahub.wordlite;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;

/**
 * 真人论文正文的<b>同学科基线</b>（随包 {@code assets/aigc/aigc-baseline.tsv}）。
 *
 * <p>为什么非要有它：一个"AI 味特征密度 2.4/千字"本身不说明任何事。同一个判据在真人正式摘要里
 * 本来就能到 45.9 次/千字（见 {@link AigcTells} 类注释），在真人论文正文里是另一个量级。
 * 密度只有和<b>同一语域的真人正文基线</b>并排印出来，读者才知道 2.4 是高还是正常，
 * 所以这两个数在界面上必须写在一行里，不许只印前一半。
 *
 * <p>读不到基线表就是读不到：本类一律不猜数、不把缺的当 0。缺文件、缺判据那一行、缺某一分位，
 * 界面都退到"暂无同学科基线，只给绝对密度"（{@link #MISSING}）。基线资产由训练台另出，
 * 与 {@code tools/build-aigc-model.py} 那批导出物一起入库。
 *
 * <p>列名按训练台导出的表头认（学科 / 判据id / 每千字均值 / P50 / P90 / P99 / 样本段数，
 * 同名的英文列也认）；认不出表头就按这个列序读。缺的列读回 NaN，
 * NaN 在界面上写成"量不出"，不参与比较。
 */
public final class AigcBaseline {
    /** 随包文件（相对 assets）。 */
    public static final String FILE = "aigc/aigc-baseline.tsv";
    public static final int MAX_ROWS = 4096;
    /** 整篇汇总那一行用的判据名；表里没这一行时只查具体判据。 */
    public static final String AGGREGATE = "ALL";
    /** 缺基线时界面上那句话。不许在这里放任何编出来的数。 */
    public static final String MISSING = "暂无同学科基线，只给绝对密度";

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static volatile AigcBaseline active;
    private static boolean loadAttempted;

    /** 一个学科在一个判据上的真人基线。 */
    public static final class Row {
        public String discipline = "", criterion = "";
        public double mean = Double.NaN, p50 = Double.NaN, p90 = Double.NaN, p99 = Double.NaN;
        public int segments = -1;

        /** 有没有能用来对比的数。 */
        public boolean usable() {
            return !Double.isNaN(mean) || !Double.isNaN(p50) || !Double.isNaN(p90) || !Double.isNaN(p99);
        }

        /** 拿哪个数当基线：优先均值，缺了就退到中位数。 */
        public double reference() {
            return !Double.isNaN(mean) ? mean : p50;
        }
    }

    private final ArrayList<Row> rows = new ArrayList<Row>();

    private AigcBaseline() { }

    public static AigcBaseline current() {
        return active;
    }

    public static AigcBaseline install(InputStream table) throws IOException {
        AigcBaseline loaded = new AigcBaseline();
        loaded.read(table);
        active = loaded;
        return loaded;
    }

    /** 读不到就当没有基线：一个数都不许编。 */
    public static void loadFromAssets(android.content.Context context) {
        if (loadAttempted || active != null) return;
        loadAttempted = true;
        try {
            install(context.getAssets().open(FILE));
        } catch (Exception missing) {
            active = null;
            android.util.Log.i("WordliteAigc", "baseline asset absent: " + missing);
        }
    }

    public static void resetLoadAttempt() {
        loadAttempted = false;
    }

    public static void uninstall() {
        active = null;
    }

    public ArrayList<Row> rows() {
        return rows;
    }

    /** 查一个判据的基线；同一判据有多行（多个学科）时取第一行，学科列原样带进文案。 */
    public Row lookup(String criterion) {
        if (criterion == null) return null;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.criterion.equals(criterion)) return row;
        }
        return null;
    }

    private void read(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, UTF8));
        String line;
        String[] header = null;
        int rowsRead = 0;
        while ((line = r.readLine()) != null) {
            if (line.length() == 0 || line.charAt(0) == '#') continue;
            if (++rowsRead > MAX_ROWS) throw new IOException("基线行数超过上限 " + MAX_ROWS);
            String[] parts = line.split("\t", -1);
            if (header == null) {
                boolean labelled = looksLikeHeader(parts);
                header = labelled ? parts : new String[]{
                        "学科", "判据id", "每千字均值", "P50", "P90", "P99", "样本段数",
                };
                if (!labelled) bind(rows, header, parts);
                continue;
            }
            bind(rows, header, parts);
        }
        if (rows.isEmpty()) throw new IOException("基线一行都没读到");
    }

    private static boolean looksLikeHeader(String[] parts) {
        for (int i = 0; i < parts.length; i++) {
            String v = parts[i] == null ? "" : parts[i].trim();
            if ("学科".equals(v) || "判据id".equals(v) || "id".equalsIgnoreCase(v)
                    || "discipline".equalsIgnoreCase(v) || "criterion".equalsIgnoreCase(v)) return true;
        }
        return false;
    }

    private static void bind(ArrayList<Row> into, String[] header, String[] parts) {
        Row row = new Row();
        int numeric = 0;
        for (int i = 0; i < parts.length; i++) {
            String column = i < header.length ? header[i].trim() : "";
            String v = parts[i] == null ? "" : parts[i].trim();
            if (isDiscipline(column) || (column.length() == 0 && row.discipline.length() == 0
                    && v.length() > 0 && Double.isNaN(num(v)))) row.discipline = v;
            else if (isCriterion(column)) row.criterion = v;
            else if (isMean(column)) row.mean = num(v);
            else if (isP(column, "50")) row.p50 = num(v);
            else if (isP(column, "90")) row.p90 = num(v);
            else if (isP(column, "99")) row.p99 = num(v);
            else if (isSegments(column)) row.segments = (int) num(v);
            else if (!Double.isNaN(num(v))) {
                // 表头认不出来的数字列：按出现顺序填 均值/P50/P90/P99，填不满就留着 NaN。
                if (numeric == 0) row.mean = num(v);
                else if (numeric == 1) row.p50 = num(v);
                else if (numeric == 2) row.p90 = num(v);
                else if (numeric == 3) row.p99 = num(v);
                else row.segments = (int) num(v);
                numeric++;
            }
        }
        if (row.criterion.length() == 0 && row.discipline.length() > 0) {
            // 有的导出表把判据名放在第一列、学科放在文件名里：那种表认第一列为判据。
            row.criterion = row.discipline;
            row.discipline = "";
        }
        if (row.criterion.length() == 0) return;
        into.add(row);
    }

    private static boolean isDiscipline(String c) {
        return "学科".equals(c) || "语域".equals(c) || "discipline".equalsIgnoreCase(c)
                || "domain".equalsIgnoreCase(c) || "subject".equalsIgnoreCase(c);
    }

    private static boolean isCriterion(String c) {
        return "判据id".equals(c) || "判据".equals(c) || "id".equalsIgnoreCase(c)
                || "criterion".equalsIgnoreCase(c) || "rule".equalsIgnoreCase(c);
    }

    private static boolean isMean(String c) {
        return c.contains("均值") || c.contains("每千字") || "mean".equalsIgnoreCase(c)
                || "avg".equalsIgnoreCase(c) || "per1000".equalsIgnoreCase(c);
    }

    private static boolean isP(String c, String digits) {
        return c.equalsIgnoreCase("P" + digits) || c.contains("P" + digits) || c.contains("p" + digits);
    }

    private static boolean isSegments(String c) {
        return c.contains("样本段") || c.contains("段数") || "segments".equalsIgnoreCase(c)
                || "n".equalsIgnoreCase(c) || "count".equalsIgnoreCase(c);
    }

    private static double num(String v) {
        if (v.length() == 0 || "nan".equalsIgnoreCase(v) || "None".equals(v) || "-".equals(v))
            return Double.NaN;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    // ---------------------------------------------------------------- 界面那一行

    /**
     * 密度与基线并排那一行。基线读不到就只给绝对密度，并把"读不到"照实写出来。
     *
     * @param densityPer1000 本机实测的每千字密度
     * @param criterion      查基线用的判据名（整篇汇总传 {@link #AGGREGATE}）
     */
    public static String densityLine(double densityPer1000, String criterion) {
        String mine = one(densityPer1000);
        AigcBaseline table = active;
        Row row = table == null ? null : table.lookup(criterion);
        if (row == null || !row.usable()) return "AI 味特征密度 " + mine + "/千字（" + MISSING + "）";
        String from = row.discipline.length() == 0
                ? "同学科真人论文正文基线" : row.discipline + "真人论文正文基线";
        return "AI 味特征密度 " + mine + "/千字（" + from + " " + one(row.reference()) + "/千字）";
    }

    /** 出处行里那句基线自述：装载了几行、来自哪些学科。没装就说没装。 */
    public static String describe() {
        AigcBaseline table = active;
        if (table == null) return "真人正文基线未装载（" + FILE + "）";
        ArrayList<String> disciplines = new ArrayList<String>();
        for (int i = 0; i < table.rows.size(); i++) {
            String d = table.rows.get(i).discipline;
            if (d.length() > 0 && !disciplines.contains(d)) disciplines.add(d);
        }
        StringBuilder out = new StringBuilder("真人正文基线 ").append(table.rows.size()).append(" 行");
        if (!disciplines.isEmpty()) out.append("（学科：").append(join(disciplines)).append('）');
        return out.toString();
    }

    private static String join(ArrayList<String> values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.size() && i < 8; i++) {
            if (out.length() > 0) out.append('、');
            out.append(values.get(i));
        }
        if (values.size() > 8) out.append(" 等 ").append(values.size()).append(" 个");
        return out.toString();
    }

    private static String one(double value) {
        return Double.isNaN(value) ? "量不出" : String.format(java.util.Locale.US, "%.1f", Double.valueOf(value));
    }
}
