package com.rikkahub.wordlite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 知网查询质量的实测台：把真实论文窗口交给 {@link CnkiSearch#narrow} 整形，真的发一次请求，
 * 再用字符二元组重合度给返回的题录打分，与 0.5.4 那套"最长两个词"的旧口径并排比。
 *
 * <p>这台机器<strong>不在闸门里</strong>：它要联网、要代理、每次跑掉二十来次真实请求，
 * 而且知网随时可能改版。跑法见 {@code tools/cnki-live-probe.ps1}。它存在的理由是一条实测结论：
 * 查询串写得再干净，知网匿名检索的相关度也只有十几个点（11 个真实窗口实测：旧口径平均重合
 * 10.2%，整形后 11.5%），所以"对题率低"不是查询词的锅——写下这个数，是为了阻止下一版
 * 继续在没有瓶颈的地方加功夫。
 *
 * <p>打分口径：把窗口和返回的题名+摘要都切成字符二元组（汉字逐字、拉丁按词内二元组，
 * 标点丢掉），取本次返回候选里重合度的最大值。用重合度而不是"标题里有没有查询词"，
 * 是因为后者会把《基于TPMS几何结构的多孔NiTi合金性能研究》判成不相关，而它跟
 * "定向凝固多孔金属"明明是一类东西。
 */
public final class CnkiLiveProbe {
    private CnkiLiveProbe() { }

    public static void main(String[] argv) throws Exception {
        String proxy = argv.length > 0 ? argv[0] : "127.0.0.1:7897";
        String corpus = argv.length > 1 ? argv[1] : "tests/corpus/real-prose.txt";
        int windows = argv.length > 2 ? Integer.parseInt(argv[2]) : 11;
        ArrayList<String> picks = windowsFrom(corpus, windows);
        System.out.println("probe corpus=" + corpus + " windows=" + picks.size() + " proxy=" + proxy);

        double sumOld = 0d, sumNew = 0d;
        int repeatsOld = 0, repeatsNew = 0, emptyOld = 0, emptyNew = 0;
        Map<String, Integer> firstOld = new HashMap<String, Integer>();
        Map<String, Integer> firstNew = new HashMap<String, Integer>();
        for (String window : picks) {
            String queryOld = legacyNarrow(window), queryNew = CnkiSearch.narrow(window);
            List<PaperSources.Candidate> hitsOld = ask(queryOld, proxy);
            List<PaperSources.Candidate> hitsNew = ask(queryNew, proxy);
            double scoreOld = overlap(window, hitsOld), scoreNew = overlap(window, hitsNew);
            sumOld += scoreOld; sumNew += scoreNew;
            if (hitsOld == null || hitsOld.isEmpty()) emptyOld++;
            if (hitsNew == null || hitsNew.isEmpty()) emptyNew++;
            repeatsOld += bump(firstOld, title(hitsOld));
            repeatsNew += bump(firstNew, title(hitsNew));
            System.out.println(String.format(Locale.US,
                    "  old=%5.1f%% new=%5.1f%%  q_old=[%s]  q_new=[%s]",
                    scoreOld, scoreNew, queryOld, queryNew));
        }
        int n = Math.max(1, picks.size());
        System.out.println(String.format(Locale.US,
                "SUMMARY windows=%d mean_overlap_old=%.1f mean_overlap_new=%.1f"
                        + " empty_old=%d empty_new=%d repeated_first_title_old=%d new=%d",
                picks.size(), sumOld / n, sumNew / n, emptyOld, emptyNew, repeatsOld, repeatsNew));
        System.out.println("读法：mean_overlap 是相关度上限的近似，repeated_first_title 是填充条目的信号"
                + "（不同查询返回同一篇=知网没匹配上）；两个数都不构成'改查询能救回来'的证据。");
    }

    private static List<PaperSources.Candidate> ask(String query, String proxy) {
        PaperSources.Limits limits = new PaperSources.Limits();
        limits.timeoutSeconds = 20;
        limits.perEngine = 5;
        limits.proxy = proxy;
        try { return PaperSources.search("cnki", query, limits, null); }
        catch (Exception error) { return null; }
    }

    private static String title(List<PaperSources.Candidate> hits) {
        if (hits == null || hits.isEmpty()) return "(none)";
        String t = hits.get(0).source.title;
        return t == null ? "(none)" : t;
    }

    private static int bump(Map<String, Integer> seen, String title) {
        int count = seen.containsKey(title) ? seen.get(title).intValue() : 0;
        seen.put(title, Integer.valueOf(count + 1));
        return count > 0 ? 1 : 0;
    }

    /** 0.5.4 的口径，原样重写一遍做 A/B 的左半边：按空白切，留最长的两个。 */
    static String legacyNarrow(String phrase) {
        if (phrase == null) return "";
        String[] parts = phrase.trim().split("\\s+");
        ArrayList<String> keep = new ArrayList<String>();
        for (String part : parts) if (part.length() >= 2) keep.add(part);
        keep.sort(new Comparator<String>() {
            @Override public int compare(String a, String b) { return b.length() - a.length(); }
        });
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < keep.size() && i < 2; i++) {
            if (out.length() > 0) out.append(' ');
            out.append(keep.get(i));
        }
        return out.toString();
    }

    /** 汉字逐字、字母数字按词，其余当分隔符；词内取相邻二元组。 */
    static Set<String> grams(String value) {
        StringBuilder flat = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) flat.append(c);
            else if (Character.isLetterOrDigit(c)) flat.append(Character.toLowerCase(c));
            else flat.append(' ');
        }
        Set<String> out = new HashSet<String>();
        for (String token : flat.toString().split(" +")) {
            if (token.length() == 1) out.add(token);
            for (int i = 0; i + 2 <= token.length(); i++) out.add(token.substring(i, i + 2));
        }
        return out;
    }

    static double overlap(String window, List<PaperSources.Candidate> hits) {
        if (hits == null || hits.isEmpty()) return 0d;
        Set<String> want = grams(window);
        if (want.isEmpty()) return 0d;
        double best = 0d;
        for (PaperSources.Candidate candidate : hits) {
            String hay = (candidate.source.title == null ? "" : candidate.source.title)
                    + " " + (candidate.abstractText == null ? "" : candidate.abstractText);
            Set<String> have = grams(hay);
            Set<String> both = new HashSet<String>(want);
            both.retainAll(have);
            double score = both.size() * 100d / want.size();
            if (score > best) best = score;
        }
        return best;
    }

    private static ArrayList<String> windowsFrom(String path, int limit) throws Exception {
        ArrayList<String> out = new ArrayList<String>();
        String[] lines = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8).split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.length() >= 30 && trimmed.length() <= 60) out.add(trimmed);
            if (out.size() >= limit) break;
        }
        return out;
    }
}
