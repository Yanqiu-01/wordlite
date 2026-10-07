package com.rikkahub.wordlite;

/**
 * 打分族。中文族与拉丁族各一张系数表（见 {@link AigcScorer#defaults(int)}），混排先走中文族再乘折扣。
 *
 * 判定沿用 0.5.4 的 latinRatio 口径：normalize 之后的 compact 串里拉丁字母占比。0.5.4 只把它用在
 * "实词多样度"那一条上换个下限，连接词却是中英两张表混在一起数（一句中文里夹三个英文术语就能凑够
 * "连接词密度偏高"），本版起族决定用哪张词表、哪一档多样度下限。
 */
public final class AigcFamily {
    public static final int CHINESE = 0, LATIN = 1, MIXED = 2;
    /** 混排段先按中文族打分再乘这一档折扣；0.7.0 取 1.0（等于现状），落值是 0.7.1 的活。 */
    static final double MIXED_DISCOUNT = 1.00d;
    /** 拉丁字母占比到这一档算拉丁族。 */
    static final double LATIN_RATIO = 0.50d;
    /** 拉丁字母占比低于这一档算中文族，中间是混排。 */
    static final double MIXED_RATIO = 0.15d;

    private AigcFamily() { }

    /** compact 串里的拉丁字母占比。compact 已过 normalize，大写折成小写，全角折成半角。 */
    static double latinRatio(String compact) {
        if (compact == null || compact.length() == 0) return 0d;
        int latin = 0;
        for (int i = 0; i < compact.length(); i++) {
            if (compact.charAt(i) >= 'a' && compact.charAt(i) <= 'z') latin++;
        }
        return (double) latin / compact.length();
    }

    /** 这一段属于哪一族。空串按中文族处理：没有反证就不改判。 */
    static int familyOf(String compact) {
        double ratio = latinRatio(compact);
        if (ratio >= LATIN_RATIO) return LATIN;
        if (ratio < MIXED_RATIO) return CHINESE;
        return MIXED;
    }

    static String label(int family) {
        switch (family) {
            case LATIN: return "拉丁";
            case MIXED: return "混排";
            default: return "中文";
        }
    }
}