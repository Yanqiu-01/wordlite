package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 知网检索式对比台：同一篇真论文，用十种写法去问知网，看哪一种能把这篇本身问回来。
 *
 * <p>存在理由：{@code RecallProbe} 实测知网自召回 1/4，而万方与维普是 4/4——锅不在比对内核
 * （只要那篇被问回来，命中分和出处全对）。所以要把"是查询写法的问题"还是"这个匿名检索口本身
 * 就不按相关度给结果"这两件事分开，否则下一版又会在查询整形上白下功夫（0.7.2 已经干过一次）。
 *
 * <p>口径：种子来自知网自己返回的真摘要，取中段一句原样去问；"问回来"指返回候选里出现同一题名
 * （{@link CandidateRanker#titleKey} 归一化后相等）。题名那一条是对照组——它证明这篇文献确实在这个
 * 检索口里，于是其余各条的低召回就只能解释为写法问题，而不能拿"库里没有"抵赖。
 *
 * 不在闸门里：要联网、要代理，一跑四十次真实请求。
 *   java com.rikkahub.wordlite.CnkiFormProbe [proxy] [perEngine] [query]
 */
public final class CnkiFormProbe {
    private static final String ENDPOINT = "https://search.cnki.com.cn/search/listresult";
    private static final long GAP_MILLIS = 400L;

    private CnkiFormProbe() { }

    public static void main(String[] argv) throws Exception {
        String proxy = argv.length > 0 ? argv[0].trim() : "127.0.0.1:7897";
        int per = argv.length > 1 ? Integer.parseInt(argv[1]) : 5;
        String query = argv.length > 2 ? argv[2] : "碳化硅 瞬态液相扩散焊 界面组织 中间层 接头性能";

        PaperSources.Limits limits = new PaperSources.Limits();
        limits.perEngine = per;
        limits.timeoutSeconds = 25;
        limits.proxy = proxy;
        java.net.Proxy via = Routes.parse(proxy);

        List<PaperSources.Candidate> seeds = PaperSources.search("cnki", query, limits, null);
        System.out.println("seeds=" + seeds.size() + " proxy=" + proxy);
        java.util.LinkedHashMap<String, int[]> tally = new java.util.LinkedHashMap<String, int[]>();
        for (PaperSources.Candidate seed : seeds) {
            String sentence = middleSentence(seed.abstractText);
            if (sentence == null) continue;
            String phrase = midPhrase(seed.abstractText, 16);
            System.out.println("-- seed " + cut(seed.source.title, 34));
            System.out.println("   sentence " + cut(sentence, 40));
            String narrow = CnkiSearch.narrow(sentence);
            String[] variants = {
                    "narrow/Order2|Match0 " + narrow, "narrow/Order1 " + narrow,
                    "narrow/Order0 " + narrow, "narrow/Order3 " + narrow,
                    "narrow/Match2 " + narrow, "整句/Order2 " + clip(sentence, 45),
                    "整句/引号 " + quoted(clip(sentence, 45)), "中段16字 " + phrase,
                    "中段16字引号 " + quoted(phrase), "题名对照 " + seed.source.title,
            };
            for (String variant : variants) {
                int bar = variant.indexOf(' ');
                String label = variant.substring(0, bar);
                String spec = variant.substring(bar + 1);
                String content = label.equals("题名对照") ? seed.source.title : spec;
                int order = label.contains("Order0") ? 0 : label.contains("Order1") ? 1
                        : label.contains("Order3") ? 3 : 2;
                int match = label.contains("Match2") ? 2 : 0;
                List<PaperSources.Candidate> found = ask(content, order, match, per, via);
                boolean hit = contains(found, seed);
                int first = indexOf(found, seed);
                int[] row = tally.get(label);
                if (row == null) tally.put(label, row = new int[2]);
                row[0]++;
                row[1] += hit ? 1 : 0;
                System.out.println(String.format(Locale.US, "   %-18s n=%d hit=%-5s rank=%s top=%s",
                        label, found == null ? -1 : found.size(), hit,
                        first < 0 ? "-" : String.valueOf(first + 1),
                        cut(top(found), 26)));
            }
        }
        System.out.println("VARIANT SUMMARY (hit / asked)");
        for (String label : tally.keySet()) {
            int[] row = tally.get(label);
            System.out.println(String.format(Locale.US, "  %-18s %d/%d", label, row[1], row[0]));
        }
    }

    private static List<PaperSources.Candidate> ask(String content, int order, int match, int per,
                                                    java.net.Proxy via) {
        String form = "Content=" + urlEncode(content) + "&Type=0&Order=" + order + "&Page=1"
                + "&Match=" + match + "&IntervalTime=0&ArticleType=0";
        try {
            ApiClient.Response response = HttpTransport.post(ENDPOINT, form, CnkiSearch.headers(), 25,
                    HttpTransport.MAX_BODY, null, via);
            List<PaperSources.Candidate> out = CnkiSearch.parse(response.body, per);
            gap();
            return out;
        } catch (Exception error) {
            System.out.println("     query failed: " + error.getMessage());
            gap();
            return null;
        }
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception error) { return ""; }
    }

    private static boolean contains(List<PaperSources.Candidate> found, PaperSources.Candidate seed) {
        return indexOf(found, seed) >= 0;
    }

    private static int indexOf(List<PaperSources.Candidate> found, PaperSources.Candidate seed) {
        if (found == null) return -1;
        String wanted = CandidateRanker.titleKey(seed.source.title);
        for (int i = 0; i < found.size(); i++)
            if (wanted.equals(CandidateRanker.titleKey(found.get(i).source.title))) return i;
        return -1;
    }

    private static String top(List<PaperSources.Candidate> found) {
        return found == null || found.isEmpty() ? "-" : found.get(0).source.title;
    }

    private static String middleSentence(String text) {
        if (text == null || text.length() < 60) return null;
        ArrayList<int[]> ranges = TextCorpus.sentences(text);
        int pick = -1;
        for (int i = 0; i < ranges.size(); i++) {
            if (TextCorpus.validCount(text, ranges.get(i)[0], ranges.get(i)[1]) < 22) continue;
            if (pick < 0) pick = i;
            if (i >= ranges.size() / 2) break;
        }
        if (pick < 0) return null;
        int[] range = ranges.get(pick);
        return text.substring(range[0], range[1]).trim();
    }

    /** 摘要中段连续 16 个字，原样不加工——这是"逐字长串"最干净的探针。 */
    private static String midPhrase(String text, int chars) {
        if (text == null || text.length() < chars * 3) return "";
        int start = (int) (text.length() * 0.45);
        String raw = text.substring(start, Math.min(text.length(), start + chars));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c) || "，。；：、（）()《》".indexOf(c) >= 0) continue;
            out.append(c);
        }
        return out.toString();
    }

    private static String quoted(String value) {
        return "\"" + value + "\"";
    }

    private static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static void gap() {
        try { Thread.sleep(GAP_MILLIS); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private static String cut(String value, int max) {
        if (value == null || value.isEmpty()) return "-";
        String flat = value.replace('\n', ' ');
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }
}

