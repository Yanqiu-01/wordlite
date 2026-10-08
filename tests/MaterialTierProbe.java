package com.rikkahub.wordlite;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;

/**
 * 材料档的量台（不联网，只吃本地语料）：同一篇真论文的"摘要"与"正文"各成一档，拿同一份稿子各比一次，
 * 把"摘要级命中必须降权"这句话落成两个数——覆盖与假命中密度。
 *
 * 素材（全是真东西，且查询侧与库侧主题不同）：
 *   tests/corpus/oa-planted-cjmenet.txt  一篇真论文正文里的 11 句（《机械工程学报》60 卷 19 期，DOI 10.3901/JME.2024.19.318）
 *   tests/corpus/oa-abstract-cjmenet.txt 同一篇在 OpenAlex 上的摘要（abstract_inverted_index 复原）
 *   tests/corpus/real-prose.txt          另一主题（宽禁带半导体封装互连）的真人论文，量假命中用
 *
 * 两个问题：
 *   一、覆盖：同一篇论文，只有摘要的库能捞到正文级命中的几成？
 *   二、假命中：拿一篇毫不相干的真论文当库，摘要档与正文档各假命中多少字？
 *
 * 跑法（先编一份私有类目录，再直跑）：
 *   pwsh tools/run-suites-private.ps1 -Tree . -OutDir artifacts/agent-material -Suite MaterialTierProbe
 */
public final class MaterialTierProbe {
    /** 一条比对：一份稿子对一份语料，只报命中处数、命中字数与分母。 */
    private static void line(String name, String query, TextCorpus.Source source, String body) {
        TextCorpus corpus = new TextCorpus();
        corpus.add(source, body);
        TextCorpus.Report matched = corpus.match(query, null);
        int chars = TextCorpus.validCount(TextCorpus.normalize(query), 0, query.length());
        System.out.println("  " + name + "  库 " + TextCorpus.validCount(TextCorpus.normalize(body), 0, body.length())
                + " 字 -> 命中 " + matched.hits.size() + " 处 / " + matched.duplicateChars + " 字 / 分母 " + chars
                + " 字 = " + String.format(java.util.Locale.CHINA, "%.2f%%",
                matched.duplicateChars * 100d / Math.max(1, chars)));
        for (int i = 0; i < matched.hits.size(); i++) {
            TextCorpus.Hit hit = matched.hits.get(i);
            System.out.println("      [" + source.material + "] " + hit.start + "-" + hit.end
                    + " score=" + String.format(java.util.Locale.CHINA, "%.3f", hit.score)
                    + "  " + query.substring(hit.start, Math.min(hit.end, hit.start + 40)));
        }
    }

    private static TextCorpus.Source source(String title, String material) {
        TextCorpus.Source source = new TextCorpus.Source();
        source.id = "probe:" + material;
        source.title = title;
        source.engine = "local";
        source.material = material;
        return source;
    }

    private static String read(String path) throws IOException {
        byte[] bytes = Files.readAllBytes(new File(path).toPath());
        String text = new String(bytes, Charset.forName("UTF-8"));
        if (text.length() > 0 && text.charAt(0) == 0xFEFF) text = text.substring(1);
        return text.replace("\r\n", "\n");
    }

    /** 语料文件里 # 开头的是取回说明，不进语料。 */
    private static String readBody(String path) throws IOException {
        StringBuilder out = new StringBuilder();
        for (String row : read(path).split("\n")) {
            if (row.startsWith("#") || row.trim().isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(row);
        }
        return out.toString();
    }

    public static void main(String[] args) throws IOException {
        String dir = args.length > 0 ? args[0] : "tests/corpus";
        String abstractPath = args.length > 1 ? args[1] : "tests/corpus/oa-abstract-cjmenet.txt";
        String body = readBody(dir + "/oa-planted-cjmenet.txt");
        String abstractText = readBody(abstractPath);   // 夹具头部那几行取回说明不进语料
        String prose = read(dir + "/real-prose.txt");
        String title = "钛铝合金低温切削加工温度的实验和仿真研究";
        System.out.println("同一篇真论文的两档材料：摘要 " + abstractText.length() + " 字，正文 11 句 "
                + body.length() + " 字");

        System.out.println("一、覆盖：稿子 = 那 11 句正文（等于逐字抄这篇的正文）");
        line("摘要档", body, source(title, DuplicateEngine.MATERIAL_ABSTRACT), abstractText);
        line("正文档", body, source(title, DuplicateEngine.MATERIAL_FULL), body);

        System.out.println("二、假命中：稿子 = 另一主题的真人论文（" + prose.length()
                + " 字，与这篇论文无关），库里两种档各一篇不相干材料都不该撞上");
        line("摘要档", prose, source(title, DuplicateEngine.MATERIAL_ABSTRACT), abstractText);
        line("正文档", prose, source(title, DuplicateEngine.MATERIAL_FULL), body);

        System.out.println("三、混合库（真链路会遇到的形状）：稿子 = 那 11 句正文，库里同放这篇的摘要与那 11 句正文");
        TextCorpus mixed = new TextCorpus();
        mixed.add(source(title, DuplicateEngine.MATERIAL_ABSTRACT), abstractText);
        mixed.add(source(title + "（正文）", DuplicateEngine.MATERIAL_FULL), body);
        TextCorpus.Report m = mixed.match(body, null);
        SourceLedger ledger = SourceLedger.aggregate(m.hits, TextCorpus.normalize(body), m.comparedChars);
        System.out.println("  命中 " + m.hits.size() + " 处 / " + m.duplicateChars + " 字，来源榜 "
                + ledger.rows.size() + " 行，第一行 " + ledger.rows.get(0).title);
        ArrayList<String> tiers = new ArrayList<String>();
        for (int i = 0; i < ledger.rows.size(); i++) tiers.add(ledger.rows.get(i).material);
        System.out.println("  各档命中字数：正文级 " + ledger.fullDuplicateChars + "，摘要级 "
                + ledger.abstractDuplicateChars + "（分档之和 " + (ledger.fullDuplicateChars
                + ledger.abstractDuplicateChars) + " == 分子 " + ledger.duplicateChars + "）");
        System.out.println("  行的档位顺序：" + tiers);
    }
}