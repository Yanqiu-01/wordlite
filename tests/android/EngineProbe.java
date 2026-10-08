package com.rikkahub.wordlite;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Live retrieval probe for the connected Android device. It drives the production stack
 * (PaperSources -> HttpTransport -> Routes) with no Context, no Activity and no APK, so it runs
 * straight off a pushed dex:
 *
 *   adb shell CLASSPATH=/data/local/tmp/wordlite-engine-probe.dex app_process / \
 *       com.rikkahub.wordlite.EngineProbe engines "深度学习 图像分割 综述"
 *
 * Usage:  EngineProbe [engines] [query] [flags]
 *   --only=a,b,c        only these engines (default: every engine in PaperSources.defaults())
 *   --proxy=host:port   Limits.proxy, the same value the app stores for a retrieval pass
 *   --per=N             perEngine (default 5)
 *   --timeout=SECONDS   timeoutSeconds (default 25)
 *   --core-key=K        CORE api key, else WORDLITE_CORE_KEY
 *   --query-file=PATH   read the query from a UTF-8 file (how tools/device-probe.ps1 sends CJK)
 *   --no-tcp            skip the raw TCP pre-check on every engine host
 *
 * Two things this reports that the transport alone cannot:
 * 1. A raw TCP connect per engine host first, so "the phone has no route at all" is distinguishable
 *    from "the retrieval stack failed for a subtler reason". ICMP is blocked on this device, so
 *    ping proves nothing here; TCP 443 is the honest test.
 * 2. The route that carried the traffic. ApiClient.Response.via is per-response and
 *    PaperSources.search only hands back candidates, so this reads Routes.summary(), which records
 *    the last route that actually SUCCEEDED per host. A failed engine prints via=- with tried=N,
 *    because Routes only notes a route it used; the routes a failed engine burned are not
 *    recoverable from outside HttpTransport.
 */
public final class EngineProbe {
    /** Sentinel the staged CnkiSearch placeholder throws, so an excluded engine reads as SKIP. */
    private static final String EXCLUDED = "WORDLITE_PROBE_EXCLUDED";
    private static final int TCP_MILLIS = 5000;

    private EngineProbe() { }

    public static void main(String[] argv) throws Exception {
        String query = "深度学习 图像分割 综述";
        String only = "", proxy = "", coreKey = trim(System.getenv("WORDLITE_CORE_KEY")), file = "";
        int per = 5, timeout = 25, repeat = 1;
        boolean tcp = true, tcpOnly = false;
        ArrayList<String> words = new ArrayList<String>();
        for (String arg : argv) {
            if (arg == null) continue;
            if (arg.startsWith("--only=")) only = arg.substring(7);
            else if (arg.startsWith("--proxy=")) proxy = arg.substring(8);
            else if (arg.startsWith("--per=")) per = number(arg.substring(6), per);
            else if (arg.startsWith("--repeat=")) repeat = Math.max(1, number(arg.substring(9), repeat));
            else if (arg.startsWith("--timeout=")) timeout = number(arg.substring(10), timeout);
            else if (arg.startsWith("--core-key=")) coreKey = arg.substring(11).trim();
            else if (arg.startsWith("--query-file=")) file = arg.substring(13).trim();
            else if (arg.equals("--no-tcp")) tcp = false;
            else if (arg.equals("--tcp-only")) { tcp = true; tcpOnly = true; }
            else words.add(arg);
        }
        int at = words.indexOf("engines");
        if (at >= 0) words.remove(at);
        if (!words.isEmpty()) query = words.get(0);
        if (!file.isEmpty()) {
            String read = new String(Files.readAllBytes(Paths.get(file)), Charset.forName("UTF-8")).trim();
            if (!read.isEmpty()) query = read;
        }

        PaperSources.Limits limits = new PaperSources.Limits();
        limits.perEngine = per;
        limits.timeoutSeconds = timeout;
        limits.proxy = proxy.trim();
        limits.coreKey = coreKey;
        Routes.reset();

        System.out.println("probe-on-device query=" + cut(query, 48) + " chars=" + query.length()
                + " per=" + per + " timeout=" + timeout + "s proxy=" + (limits.proxy.isEmpty() ? "(none)" : limits.proxy));
        System.out.println("java=" + System.getProperty("java.vm.name", "?") + " " + System.getProperty("java.version", "?")
                + " android=" + System.getProperty("java.vm.version", "?"));
        if (tcp) tcpPrecheck(limits);
        if (tcpOnly) {
            System.out.println("SUMMARY tcp-only");
            return;
        }

        Set<String> wanted = wanted(only);
        int failures = 0, skipped = 0, candidates = 0, attempted = 0;
        /* 同一个查询重复几轮：路由记忆到底有没有省下那条死直连，只有第二轮往后的耗时说得清。 */
        for (int round = 1; round <= repeat; round++) {
            long roundStarted = System.nanoTime();
            for (String engine : PaperSources.engines()) {
                if (!wanted.contains(engine)) continue;
                attempted++;
                if ("core".equals(engine) && limits.coreKey.isEmpty()) {
                    skipped++;
                    System.out.printf(Locale.ROOT, "SKIP %-17s tried=%-8s via=%s%n", engine, "-", "needs WORDLITE_CORE_KEY");
                    continue;
                }
                int tried = Routes.order(Routes.host(PaperSources.endpoint(engine)),
                        PaperSources.proxyFor(limits)).size();
                long started = System.nanoTime();
                try {
                    ArrayList<PaperSources.Candidate> found = PaperSources.search(engine, query, limits, null);
                    long ms = (System.nanoTime() - started) / 1000000L;
                    candidates += found.size();
                    System.out.printf(Locale.ROOT, "OK   %-17s hits=%-3d %5dms tried=%-2d via=%-22s %s%n", engine,
                            found.size(), ms, tried, routeOf(engine),
                            found.isEmpty() ? "(no hits)" : cut(found.get(0).source.title, 60));
                } catch (Exception error) {
                    long ms = (System.nanoTime() - started) / 1000000L;
                    if (EXCLUDED.equals(String.valueOf(error.getMessage()))) {
                        skipped++;
                        System.out.printf(Locale.ROOT, "SKIP %-17s tried=%-8s via=%s%n", engine, "-",
                                "not in this probe build");
                        continue;
                    }
                    failures++;
                    String status = error instanceof ApiClient.Failure
                            ? " http=" + ((ApiClient.Failure) error).status : "";
                    System.out.printf(Locale.ROOT, "FAIL %-17s %9s tried=%-2d via=%-22s %s%s%n", engine, ms + "ms", tried,
                            "-", String.valueOf(error.getMessage()), status);
                }
            }
            System.out.println("ROUND " + round + " " + ((System.nanoTime() - roundStarted) / 1000000L)
                    + "ms candidates=" + candidates + " routes=" + Routes.summary());
        }
        String routes = Routes.summary();
        System.out.println("ROUTES " + (routes.isEmpty() ? "(no source was reached)" : routes));
        int ran = attempted - skipped;
        System.out.println("SUMMARY engines=" + attempted + " usable=" + (ran - failures) + " failures=" + failures
                + " skipped=" + skipped + " candidates=" + candidates);
        if (ran > 0 && failures == ran) {
            System.err.println("every attempted engine failed on this device");
            System.exit(1);
        }
    }

    /** Plain TCP connect to each engine host, so a dead phone radio is not mistaken for a code bug. */
    private static void tcpPrecheck(PaperSources.Limits limits) {
        Set<String> hosts = new LinkedHashSet<String>();
        for (String engine : PaperSources.engines()) {
            String host = Routes.host(PaperSources.endpoint(engine));
            if (!host.isEmpty()) hosts.add(host + ":443");
        }
        if (!limits.proxy.isEmpty()) hosts.add(limits.proxy + " (proxy)");
        StringBuilder out = new StringBuilder("TCP  ");
        int open = 0;
        for (String target : hosts) {
            boolean isProxy = target.endsWith(")");
            String hostPort = isProxy ? target.substring(0, target.indexOf(' ')) : target;
            int colon = hostPort.lastIndexOf(':');
            String host = hostPort.substring(0, colon);
            int port = number(hostPort.substring(colon + 1), 443);
            long started = System.nanoTime();
            Socket socket = new Socket();
            String note;
            try {
                socket.connect(new InetSocketAddress(host, port), TCP_MILLIS);
                open++;
                note = host + "=" + ((InetSocketAddress) socket.getRemoteSocketAddress()).getAddress().getHostAddress()
                        + "/" + ((System.nanoTime() - started) / 1000000L) + "ms";
            } catch (Exception error) {
                String why = String.valueOf(error); note = host + "=DOWN(" + why.substring(Math.max(0, why.length() - 76)) + ")";
            } finally {
                try { socket.close(); } catch (IOException ignored) { }
            }
            out.append(note).append(' ');
        }
        System.out.println(out + "=> " + open + " of " + hosts.size() + " open");
    }

    /** Route Routes recorded for this engine's host, or "-" when nothing succeeded there. */
    private static String routeOf(String engine) {
        String host = Routes.host(PaperSources.endpoint(engine));
        if (host.isEmpty()) return "-";
        for (String pair : Routes.summary().split("  ")) {
            int space = pair.indexOf(' ');
            if (space > 0 && pair.substring(0, space).equals(host)) return pair.substring(space + 1);
        }
        return "-";
    }

    private static Set<String> wanted(String only) {
        Set<String> out = new HashSet<String>(PaperSources.engines());
        if (only == null || only.trim().isEmpty()) return out;
        Set<String> picked = new HashSet<String>();
        for (String name : Arrays.asList(only.split(",")))
            if (!name.trim().isEmpty()) picked.add(name.trim().toLowerCase(Locale.ROOT));
        out.retainAll(picked);
        return out.isEmpty() ? new HashSet<String>(PaperSources.engines()) : out;
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }

    private static int number(String text, int fallback) {
        try { return Integer.parseInt(text.trim()); } catch (Exception ignored) { return fallback; }
    }

    private static String cut(String value, int max) {
        if (value == null || value.isEmpty()) return "(empty)";
        String flat = value.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }
}
