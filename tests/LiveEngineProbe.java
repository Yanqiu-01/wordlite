package com.rikkahub.wordlite;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

/**
 * Opt-in live check for the built-in literature search. tools/test-host.ps1 keeps every
 * network call on a loopback stub, so this probe is the only place that asks the six real
 * endpoints a question. Run it by hand; it is deliberately not part of the default suite.
 *
 *   java com.rikkahub.wordlite.LiveEngineProbe engines [query]
 *   java com.rikkahub.wordlite.LiveEngineProbe scan <file.docx>
 *
 * CORE is the only engine that needs a key: set WORDLITE_CORE_KEY before running it.
 */
public final class LiveEngineProbe {
    private LiveEngineProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "scan".equals(args[0])) scan(args);
        else engines(args);
    }

    private static void engines(String[] args) {
        String query = args.length > 1 ? args[1]
                : "porous copper transient liquid phase bonding";
        PaperSources.Limits limits = limits();
        int failures = 0, skipped = 0, candidates = 0;
        for (String engine : PaperSources.engines()) {
            if ("core".equals(engine) && limits.coreKey.isEmpty()) {
                skipped++;
                System.out.println("SKIP core              needs WORDLITE_CORE_KEY");
                continue;
            }
            long started = System.nanoTime();
            try {
                ArrayList<PaperSources.Candidate> found = PaperSources.search(engine, query, limits, null);
                candidates += found.size();
                String sample = found.isEmpty() ? "-" : found.get(0).source.title;
                System.out.printf(Locale.ROOT, "OK   %-16s %2d hits %5.1fs  %s%n", engine, found.size(),
                        (System.nanoTime() - started) / 1000000000d, cut(sample, 72));
            } catch (Exception error) {
                failures++;
                String status = error instanceof ApiClient.Failure
                        ? " http=" + ((ApiClient.Failure) error).status : "";
                System.out.printf(Locale.ROOT, "FAIL %-16s %s%s [%s]%n", engine,
                        String.valueOf(error.getMessage()), status, error.getClass().getSimpleName());
            }
        }
        int total = PaperSources.engines().size();
        System.out.println("SUMMARY engines=" + total + " usable=" + (total - failures - skipped)
                + " failures=" + failures + " skipped=" + skipped + " candidates=" + candidates);
        if (total - skipped == failures) throw new IllegalStateException("every built-in engine failed");
    }

    private static void scan(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("scan needs a .docx path");
        DocxDocument document;
        FileInputStream input = new FileInputStream(args[1]);
        try {
            document = DocxParser.parse(input, args[1]);
        } finally {
            input.close();
        }
        TextSelection selection = TextSelection.all(document);
        System.out.println("chars=" + selection.text.length()
                + " paragraphs=" + document.paragraphs.size());
        long started = System.nanoTime();
        DuplicateEngine.Report report = DuplicateEngine.scan(selection, new TextCorpus(), true,
                PaperSources.engines(), limits(), null, new DuplicateEngine.Progress() {
                    public void step(String label, int done, int total) {
                        System.out.println("  [" + done + "/" + total + "] " + label);
                    }
                });
        System.out.printf(Locale.ROOT, "overall=%.2f%% excluding-citations=%.2f%% self-written=%.2f%% aigc=%.2f%%%n",
                report.overallRate, report.excludingCitationsRate, report.selfWrittenRate, report.aigcRate);
        System.out.println("compared=" + report.comparedChars + " duplicate=" + report.duplicateChars
                + " cited-duplicate=" + report.citedDuplicateChars + " hits=" + report.hits.size()
                + " candidates=" + report.candidates.size());
        for (Map.Entry<String, Integer> entry : report.candidateCount.entrySet())
            System.out.println("  candidates " + entry.getKey() + " = " + entry.getValue());
        for (Map.Entry<String, Double> entry : report.byEngine.entrySet())
            System.out.printf(Locale.ROOT, "  share %-16s %.2f%%%n", entry.getKey(), entry.getValue());
        int shown = 0;
        for (TextCorpus.Hit hit : report.hits) {
            if (shown++ >= 8) break;
            System.out.printf(Locale.ROOT, "  hit %5d-%-5d score=%.2f %s%n", hit.start, hit.end, hit.score,
                    cut(slice(selection.text, hit.start, hit.end), 60));
        }
        for (String note : report.notes) System.out.println("  note: " + note);
        System.out.println("html-report=" + CheckReport.html(args[1], report).length() + " chars");
        System.out.println("SUMMARY scan finished in "
                + (System.nanoTime() - started) / 1000000000d + "s");
    }

    private static PaperSources.Limits limits() {
        PaperSources.Limits limits = new PaperSources.Limits();
        limits.perEngine = 5;
        limits.timeoutSeconds = 25;
        /* WORDLITE_PROXY=host:port 让同一份探针从手机或电脑经代理再跑一遍。 */
        String via = System.getenv("WORDLITE_PROXY");
        limits.proxy = via == null ? "" : via.trim();
        String key = System.getenv("WORDLITE_CORE_KEY");
        limits.coreKey = key == null ? "" : key.trim();
        return limits;
    }

    private static String slice(String text, int start, int end) {
        if (text == null || start < 0 || end <= start || end > text.length()) return "";
        return text.substring(start, end).replace('\n', ' ');
    }

    private static String cut(String value, int max) {
        if (value == null) return "-";
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}