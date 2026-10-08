package com.rikkahub.wordlite;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 随包的"字符集号 → 字"对照表（Adobe-GB1 这一族）。
 *
 * 为什么要有它：中文期刊 PDF（方正排版那一族）常见 Type0 + /Encoding /Identity-H，
 * 既不写 ToUnicode，也不内嵌字体文件——PDF 里只有字形码，码到字的表不在文件里。
 * 这类字体的 /CIDSystemInfo 会写 /Registry (Adobe) /Ordering (GB1)，等于声明
 * "我的码就是 Adobe-GB1 的 CID"，那就得拿 Adobe 公布的表去查（PyMuPDF 也是这么补的）。
 *
 * 表由 tools/build-cmaps.py 从 third_party/cmap-resources（BSD 3 条款）生成，
 * 随包在 app/src/main/assets/cmaps/adobe-gb1.cid，许可见 assets/licenses/。
 * 表里没有的码一律返回 null，由调用方按"读不出"记账，绝不猜字。
 */
public final class CidUnicodeTables {
    public static final String ASSET = "cmaps/adobe-gb1.cid";
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final String REGISTRY = "Adobe";

    private static volatile CidUnicodeTables active;
    private static boolean loadAttempted;

    private final Map<String, Section> byOrdering = new HashMap<String, Section>();

    /** 一套字符集（一个 /Ordering）的表。 */
    public static final class Section {
        public final String ordering;
        private final char[] bmp;
        private final int[] extraCids;
        private final int[] extraPoints;
        private int hits;

        Section(String ordering, char[] bmp, int[] extraCids, int[] extraPoints) {
            this.ordering = ordering;
            this.bmp = bmp;
            this.extraCids = extraCids;
            this.extraPoints = extraPoints;
        }

        /** CID → 字；表里没这个号返回 null。 */
        public String get(int cid) {
            if (cid < 0) return null;
            if (cid < bmp.length && bmp[cid] != 0) {
                hits++;
                return String.valueOf(bmp[cid]);
            }
            /* U+FFFF 以上的那些 CID 在基本表里占着槽位但是 0，必须再查一次补充平面那张表，
               不然"槽位里有号"会把它们全挡掉（这一条是 CID 30571 = U+2CE93 那条断言抓出来的）。 */
            int at = java.util.Arrays.binarySearch(extraCids, cid);
            if (at < 0) return null;
            hits++;
            return new String(Character.toChars(extraPoints[at]));
        }

        /** 这份表在一次解析里补回了多少个字：只用于回执与测试，不落盘。 */
        public int hits() {
            return hits;
        }

        public int slots() {
            return bmp.length + extraCids.length;
        }
    }

    private CidUnicodeTables() { }

    /** 装一次：app 侧从 assets 开流，主机回归从文件开流，两边同一个入口。 */
    public static CidUnicodeTables install(InputStream input) throws IOException {
        byte[] data = readAll(input, MAX_BYTES);
        CidUnicodeTables tables = new CidUnicodeTables();
        int at = 0;
        if (data.length < 6 || data[0] != 'W' || data[1] != 'L' || data[2] != 'C' || data[3] != 'M')
            throw new IOException("对照表文件头不对");
        at = 4;
        int version = data[at++] & 255;
        if (version != 1) throw new IOException("对照表版本不认识：" + version);
        int sections = data[at++] & 255;
        if (sections < 1 || sections > 16) throw new IOException("对照表分段数不对：" + sections);
        for (int index = 0; index < sections; index++) {
            if (at >= data.length) throw new IOException("对照表在第 " + (index + 1) + " 段截断了");
            int nameLength = data[at++] & 255;
            if (nameLength < 1 || nameLength > 63 || at + nameLength > data.length)
                throw new IOException("对照表字符集名字读不出");
            String ordering = new String(data, at, nameLength, java.nio.charset.StandardCharsets.US_ASCII);
            at += nameLength;
            if (at + 2 > data.length) throw new IOException("对照表缺 CID 上限：" + ordering);
            int maxCid = u16(data, at);
            at += 2;
            int rows = maxCid + 1;
            if (rows <= 0 || at + rows * 2 > data.length) throw new IOException("对照表基本表越界：" + ordering);
            char[] bmp = new char[rows];
            for (int cid = 0; cid < rows; cid++) {
                int unit = u16(data, at);
                at += 2;
                bmp[cid] = unit >= 0xD800 && unit <= 0xDFFF ? 0 : (char) unit;
            }
            if (at + 2 > data.length) throw new IOException("对照表缺补充平面条数：" + ordering);
            int extras = u16(data, at);
            at += 2;
            if (at + extras * 6 > data.length) throw new IOException("对照表补充平面段越界：" + ordering);
            int[] extraCids = new int[extras];
            int[] extraPoints = new int[extras];
            for (int i = 0; i < extras; i++) {
                extraCids[i] = u16(data, at);
                at += 2;
                extraPoints[i] = ((data[at] & 255) << 24) | ((data[at + 1] & 255) << 16)
                        | ((data[at + 2] & 255) << 8) | (data[at + 3] & 255);
                at += 4;
            }
            tables.byOrdering.put(ordering, new Section(ordering, bmp, extraCids, extraPoints));
        }
        if (tables.byOrdering.isEmpty()) throw new IOException("对照表是空的");
        active = tables;
        return tables;
    }

    /** 装了哪几套表、各有多少码位：探针与回归用它说清这一次跑的是带表还是不带表。 */
    public String describe() {
        StringBuilder out = new StringBuilder();
        for (Section section : byOrdering.values()) {
            if (out.length() > 0) out.append(", ");
            out.append("Adobe-").append(section.ordering).append("=").append(section.slots());
        }
        return out.toString();
    }

    public static CidUnicodeTables active() {
        return active;
    }

    public static void uninstall() {
        active = null;
    }

    /** 主机回归用：让 loadFromAssets 的下一次调用真的重读一遍。 */
    public static void resetLoadAttempt() {
        loadAttempted = false;
    }

    /**
     * 从随包资源装载一次。读不到就当没有这张表：PDF 里那一路字照样按"读不出"记账，
     * 不许因为缺表让查重中断，也不许换个字顶上。
     */
    public static void loadFromAssets(android.content.Context context) {
        if (loadAttempted || active != null) return;
        loadAttempted = true;
        try {
            install(context.getAssets().open(ASSET));
        } catch (Exception missing) {
            active = null;
            android.util.Log.w("WordliteCmap", "CID table asset unreadable: " + missing);
        }
    }

    /**
     * 取一套表。只认 /CIDSystemInfo 报得出来的那一套：Registry 必须是 Adobe，
     * Ordering 得在表里。(Adobe, Identity) 永远拿不到——那是"码就是字形号"，任何外来的表都不许套上去。
     */
    public static Section section(String registry, String ordering) {
        CidUnicodeTables tables = active;
        if (tables == null || registry == null || ordering == null) return null;
        if (!REGISTRY.equals(registry.trim())) return null;
        return tables.byOrdering.get(ordering.trim());
    }

    private static int u16(byte[] data, int at) {
        return ((data[at] & 255) << 8) | (data[at + 1] & 255);
    }

    static byte[] readAll(InputStream input, int cap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        byte[] buffer = new byte[16 * 1024];
        int got;
        int total = 0;
        while ((got = input.read(buffer)) > 0) {
            total += got;
            if (total > cap) throw new IOException("对照表文件超过上限 " + cap + " 字节");
            out.write(buffer, 0, got);
        }
        return out.toByteArray();
    }
}
