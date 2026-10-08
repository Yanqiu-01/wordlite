package com.rikkahub.wordlite;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * 在线量一台：同一批检索窗口，OpenAlex 的几种 filter 写法各自问回什么。
 *
 * <p>要回答的只有一个问题——哪一种写法把"更可能带正文"的候选问回来：条数、带 pdf_url 的条数、
 * pdf 落在自家白名单期刊官网的条数、中文条目数、过得了零分闸门的条数，以及各自花掉多少字节与毫秒。
 * 检索式与产品链路完全同源（DuplicateEngine.windowProbes + PaperSources.openAlexTerm），
 * 所以这里量到的差别就是产品里会发生的差别。不在闸门里，见
 * {@code tools/recall-probe.ps1 -Probe OpenAlexFilterProbe}。
 */
public final class OpenAlexFilterProbe {
    /** 两次提问之间的间隔，和生产链路同一个理由：别把共享的匿名配额一次打光。 */
    private static final long GAP_MILLIS = 350L;

    private OpenAlexFilterProbe() { }

    /** 一种 filter 写法：名字 + 跟在 title_and_abstract.search 后面的那串条件。 */
    static final class Variant {
        final String name, extra;
        Variant(String name, String extra) { this.name = name; this.extra = extra; }
    }

    static final String AFFILIATIONS = "raw_affiliation_strings.search:" + "大学|研究院|研究所|学院";

    static final Variant[] VARIANTS = {
            new Variant("A-现在", ",open_access.is_oa:true"),
            new Variant("B-中文", ",open_access.is_oa:true,language:zh"),
            new Variant("C-机构串", ",open_access.is_oa:true," + AFFILIATIONS),
            new Variant("D-中文+机构串", ",open_access.is_oa:true,language:zh," + AFFILIATIONS),
            new Variant("E-中文+有ISSN", ",open_access.is_oa:true,language:zh,primary_location.source.has_issn:true"),
    };

    /** 一种写法在所有窗口上的总账。 */
    static final class Tally {
        long declared, entries, pdf, hostOk, zh, gated, gatePassed, bytes, millis;
        int requests, failures;

        void add(Tally other) {
            declared += other.declared; entries += other.entries; pdf += other.pdf;
            hostOk += other.hostOk; zh += other.zh; gated += other.gated;
            gatePassed += other.gatePassed; bytes += other.bytes; millis += other.millis;
            requests += other.requests; failures += other.failures;
        }
    }

    public static void main(String[] argv) throws Exception {
        String docx = argv.length > 0 ? argv[0] : "tests/samples/input-liu.docx";
        String proxy = argv.length > 1 ? argv[1].trim() : "";
        int windows = argv.length > 2 ? Integer.parseInt(argv[2]) : 12;
        int per = argv.length > 3 ? Integer.parseInt(argv[3]) : 12;
        int maxTerms = argv.length > 4 ? Integer.parseInt(argv[4]) : windows;

        DocxDocument document;
        FileInputStream input = new FileInputStream(docx);
        try { document = DocxParser.parse(input, docx); } finally { input.close(); }
        String text = TextSelection.all(document).text;
        DuplicateEngine.WindowPlan plan = DuplicateEngine.windowPlan(text, windows);
        ArrayList<String> terms = new ArrayList<String>();
        for (int i = 0; i < plan.members.size() && terms.size() < maxTerms; i++) {
            for (String phrase : DuplicateEngine.windowProbes(plan.members.get(i))) {
                String term = PaperSources.openAlexTerm(phrase);
                if (term.isEmpty() || terms.contains(term)) continue;
                terms.add(term);
            }
        }
        System.out.println("docx=" + docx + " 窗口组=" + plan.groups.size() + " 检索式=" + terms.size()
                + " per-page=" + per + " proxy=" + (proxy.isEmpty() ? "直连优先" : proxy));

        LinkedHashMap<String, Tally> tallies = new LinkedHashMap<String, Tally>();
        for (Variant variant : VARIANTS) tallies.put(variant.name, new Tally());
        for (int i = 0; i < terms.size(); i++) {
            String term = terms.get(i);
            StringBuilder line = new StringBuilder("-- term[" + i + "] " + term);
            for (Variant variant : VARIANTS) {
                Tally one = ask(variant, term, per, proxy);
                tallies.get(variant.name).add(one);
                line.append("  | ").append(variant.name).append(String.format("%s/%sp%sz%sg%s", one.entries, one.declared, one.pdf, one.zh, one.gatePassed));
            }
            System.out.println(line);
            sleep();
        }
        System.out.println("== 总账（" + terms.size() + " 条检索式）==");
        for (Variant variant : VARIANTS) {
            Tally t = tallies.get(variant.name);
            System.out.printf(Locale.ROOT, "%-14s declared=%d entries=%d pdf=%d 白名单站=%d zh=%d "
                            + "过零分闸门=%d/%d bytes=%d millis=%d requests=%d failures=%d%n",
                    variant.name, Long.valueOf(t.declared), Long.valueOf(t.entries), Long.valueOf(t.pdf),
                    Long.valueOf(t.hostOk), Long.valueOf(t.zh), Long.valueOf(t.gatePassed),
                    Long.valueOf(t.gated), Long.valueOf(t.bytes), Long.valueOf(t.millis),
                    Integer.valueOf(t.requests), Integer.valueOf(t.failures));
        }
    }

    String shortLine() { return ""; }

    /** 一次提问：拼 filter、发请求、数条目，再把候选交给产品自己的 BM25 看能过几条。 */
    static Tally ask(Variant variant, String term, int per, String proxy) {
        Tally out = new Tally();
        out.requests = 1;
        String filter = "title_and_abstract.search:" + term + variant.extra;
        String url = PaperSources.endpoint("openalex") + "?filter=" + enc(filter) + "&per-page=" + per
                + "&mailto=" + enc(PaperSources.POLITE_MAILTO)
                + "&select=id,doi,title,language,authorships,publication_year,open_access,"
                + "best_oa_location,primary_location,abstract_inverted_index";
        ApiClient.Response response;
        try {
            response = HttpTransport.get(url, null, 25, HttpTransport.MAX_BODY, null, Routes.parse(proxy));
        } catch (Exception error) {
            out.failures = 1;
            System.out.println("   " + variant.name + " 提问失败：" + error.getMessage());
            sleep();
            return out;
        }
        out.bytes = response.raw == null ? 0 : response.raw.length;
        out.millis = response.elapsedMillis;
        Object root;
        try { root = ApiJson.parse(response.body); }
        catch (RuntimeException error) { out.failures = 1; return out; }
        Object count = ApiJson.path(root, "meta.count");
        if (count instanceof Number) out.declared += (long) ((Number) count).doubleValue();
        Object results = ApiJson.path(root, "results");
        ArrayList<PaperSources.Candidate> pool = new ArrayList<PaperSources.Candidate>();
        if (!(results instanceof List)) return out;
        for (Object item : (List<?>) results) {
            out.entries++;
            String pdf = str(ApiJson.path(item, "best_oa_location.pdf_url"));
            if (!pdf.isEmpty()) {
                out.pdf++;
                if (PaperSources.pdfUrlRank(pdf) == 0) out.hostOk++;
            }
            if ("zh".equals(str(ApiJson.path(item, "language")))) out.zh++;
            PaperSources.Candidate candidate = new PaperSources.Candidate();
            candidate.source.engine = "openalex";
            candidate.source.id = str(ApiJson.path(item, "id"));
            candidate.source.title = str(ApiJson.path(item, "title"));
            candidate.source.locator = str(ApiJson.path(item, "doi"));
            candidate.abstractText = PaperSources.clip(
                    PaperSources.inverted(ApiJson.path(item, "abstract_inverted_index")));
            candidate.fullTextUrl = pdf;
            pool.add(candidate);
        }
        out.gated = pool.size();
        for (CandidateRanker.Scored scored : CandidateRanker.rank(term, pool))
            if (scored.score > 0d) out.gatePassed++;
        sleep();
        return out;
    }

    static String str(Object value) { return value == null ? "" : String.valueOf(value); }
    static String enc(String value) {
        try { return java.net.URLEncoder.encode(value, "UTF-8"); }
        catch (Exception error) { return value; }
    }
    static void sleep() {
        try { Thread.sleep(GAP_MILLIS); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
