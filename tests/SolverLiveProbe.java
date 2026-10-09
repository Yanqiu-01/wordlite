package com.rikkahub.wordlite;

/**
 * 活体探针：拿真过验证服务走一遍生产代码（PaperSources.browserDetail + ChallengeSolver.fetch
 * + HttpTransport + TextCorpus.add），打的是字节与汉字数。不进 test-host（要外部服务），手动跑：
 *
 *   java -classpath <host classpath> com.rikkahub.wordlite.SolverLiveProbe http://127.0.0.1:8191 <详情页> [...]
 */
public final class SolverLiveProbe {
    public static void main(String[] args) throws Exception {
        String solver = args.length > 0 ? args[0] : "http://127.0.0.1:8191";
        PaperSources.Limits limits = new PaperSources.Limits();
        limits.solver = solver;
        limits.timeoutSeconds = 30;
        TextCorpus corpus = new TextCorpus();
        int sentences = 0;
        for (int i = 1; i < args.length; i++) {
            String url = args[i];
            PaperSources.Candidate candidate = new PaperSources.Candidate();
            candidate.source.engine = engineOf(url);
            candidate.source.locator = url;
            candidate.source.title = url;
            long began = System.nanoTime();
            String text = PaperSources.browserDetail(candidate, limits, null);
            long ms = (System.nanoTime() - began) / 1000000L;
            System.out.println("URL " + url);
            if (text == null) {
                System.out.println("  浏览器这条路没给可比材料（" + ms + "ms），照旧按摘要比");
                continue;
            }
            System.out.println("  " + ms + "ms 取回并剥出可读文本 " + text.length() + " 字，可读汉字 "
                    + PaperSources.readableChinese(text) + " 个");
            System.out.println("  头 120 字：" + text.substring(0, Math.min(120, text.length())).replace('\n', ' '));
            TextCorpus.Source source = new TextCorpus.Source();
            source.id = "flare:" + url;
            source.title = url;
            source.engine = "flare";
            source.material = DuplicateEngine.MATERIAL_FULL;
            int before = corpus.sentenceCount();
            corpus.add(source, text);
            sentences += corpus.sentenceCount() - before;
            System.out.println("  进比对语料 +" + (corpus.sentenceCount() - before) + " 句");
        }
        System.out.println("合计 " + corpus.sourceCount() + " 页 / " + sentences + " 句可比；注记：" + ChallengeSolver.summary());
    }

    private static String engineOf(String url) {
        String host = ChallengeSolver.hostOf(url);
        if (host.contains("wanfang")) return "wanfang";
        if (host.contains("cqvip")) return "cqvip";
        if (host.contains("cnki")) return "cnki";
        return "other";
    }
}