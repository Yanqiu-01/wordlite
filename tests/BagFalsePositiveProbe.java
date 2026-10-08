package com.rikkahub.wordlite;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 袋口径的误报形状（不入库、不联网，跑法见 tools/bag-false-positive-probe.ps1）。
 *
 * 袋是"出现过的字"的集合，所以它的误报率由**可用字的多少**决定：汉字的可用字上千，两句不相关的中文
 * 撞不到七成五；拉丁字母折成小写只有三十来个，同一子领域的两个英文标题、两条参考文献，字符袋天然
 * 互相包含。这份探针拿真人语料里跨条目两两配对的袋 Dice 分脚本统计，问三件事：
 * 一、过 0.72 这条线的配对里，拉丁侧占多少；二、这些过线配对共享了几个"稀字"（df <= R 的字）；
 * 三、真人中文负例的同一批数是多少——只有两边的分布分得开，"共享稀字"才配当一道门。
 */
public final class BagFalsePositiveProbe {

    private static final int MIN_LINE = 24;

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "tests/corpus/real-prose.txt";
        List<String> lines = Files.readAllLines(Paths.get(path), Charset.forName("UTF-8"));
        ArrayList<String> items = new ArrayList<String>();
        for (int i = 0; i < lines.size(); i++) {
            String compact = TextCorpus.compactOf(lines.get(i).trim());
            if (compact.length() >= MIN_LINE) items.add(compact);
        }
        int n = items.size();
        char[][] bags = new char[n][];
        Map<Character, Integer> df = new HashMap<Character, Integer>();
        for (int i = 0; i < n; i++) {
            bags[i] = TextCorpus.bagOfKey(items.get(i));
            for (int k = 0; k < bags[i].length; k++) {
                Integer seen = df.get(Character.valueOf(bags[i][k]));
                df.put(Character.valueOf(bags[i][k]), Integer.valueOf(seen == null ? 1 : seen.intValue() + 1));
            }
        }
        Stats cjk = new Stats();
        Stats latin = new Stats();
        int[] byMaxLatin = new int[5];
        double maxLatinOfPair = -1d;
        float maxLatinOfCjkPair = 0f;
        ArrayList<String> samples = new ArrayList<String>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                float dice = TextCorpus.bagDiceOf(bags[i], bags[j]);
                if (dice < TextCorpus.SIMILAR_BAG_DICE) continue;
                double left = AigcFamily.latinRatio(items.get(i));
                double right = AigcFamily.latinRatio(items.get(j));
                double maxLatin = Math.max(left, right);
                byMaxLatin[latinBucket(maxLatin)]++;
                if (maxLatin > 0d && maxLatinOfPair < 0d) maxLatinOfPair = maxLatin;
                maxLatinOfPair = Math.min(maxLatinOfPair, maxLatin);
                boolean latinPair = maxLatin >= AigcFamily.MIXED_RATIO;
                if (!latinPair) maxLatinOfCjkPair = (float) Math.max(maxLatinOfCjkPair, maxLatin);
                Stats bucket = latinPair ? latin : cjk;
                bucket.pairs++;
                bucket.max = Math.max(bucket.max, dice);
                bucket.rare8 = Math.max(bucket.rare8, sharedRare(bags[i], bags[j], df, 8));
                bucket.rare16 = Math.max(bucket.rare16, sharedRare(bags[i], bags[j], df, 16));
                bucket.minBag = bucket.minBag < 0
                        ? Math.min(bags[i].length, bags[j].length)
                        : Math.min(bucket.minBag, Math.min(bags[i].length, bags[j].length));
                if (latinPair && samples.size() < 6) {
                    samples.add("  dice=" + String.format("%.2f", dice)
                            + " 稀字共享(df<=8)=" + sharedRare(bags[i], bags[j], df, 8)
                            + " | " + head(items.get(i)) + " || " + head(items.get(j)));
                }
            }
        }
        System.out.println("FALSEPOSITIVE 语料 " + path + "，条目 " + n + " 条，不同字 " + df.size()
                + " 个（df 上限不设，探针自己数）");
        System.out.println("  袋口径过线(" + TextCorpus.SIMILAR_BAG_DICE + "+) 的真人跨条目配对："
                + "中文对 " + cjk.pairs + " 对，含拉丁的一侧 " + latin.pairs + " 对");
        cjk.print("  中文对");
        latin.print("  拉丁对");
        System.out.println("  过线配对按拉丁占比分桶（取两边较大的那一个）：低于 "
                + AigcFamily.MIXED_RATIO + " 共 " + surviving(byMaxLatin, 0.15d) + " 对、低于 "
                + AigcFamily.LATIN_RATIO + " 共 " + surviving(byMaxLatin, 0.5d) + " 对；"
                + "分桶 <0.05 / 0.05-0.15 / 0.15-0.3 / 0.3-0.5 / >=0.5 = " + byMaxLatin[0] + " / "
                + byMaxLatin[1] + " / " + byMaxLatin[2] + " / " + byMaxLatin[3] + " / " + byMaxLatin[4]);
        System.out.println("  非拉丁配对（两边占比都低于 " + AigcFamily.MIXED_RATIO
                + "）里拉丁占比最高的那一对：" + String.format("%.3f", maxLatinOfCjkPair)
                + "；过线配对里最靠近门的那一对：" + String.format("%.3f", maxLatinOfPair));
        for (int i = 0; i < samples.size(); i++) System.out.println(samples.get(i));
    }

    private static final class Stats {
        int pairs;
        float max;
        int rare8 = -1;
        int rare16 = -1;
        int minBag = -1;

        void print(String label) {
            if (pairs == 0) {
                System.out.println(label + "：过线 0 对");
                return;
            }
            System.out.println(label + "：过线 " + pairs + " 对，最高袋 Dice "
                    + String.format("%.3f", max) + "，最小题袋 " + minBag
                    + " 字，共享稀字上限 df<=8 时 " + rare8 + " 个、df<=16 时 " + rare16 + " 个");
        }
    }

    private static int sharedRare(char[] first, char[] second, Map<Character, Integer> df, int cap) {
        int i = 0, j = 0, shared = 0;
        while (i < first.length && j < second.length) {
            int by = Character.compare(first[i], second[j]);
            if (by != 0) {
                if (by < 0) i++; else j++;
                continue;
            }
            Integer count = df.get(Character.valueOf(first[i]));
            if (count != null && count.intValue() <= cap) shared++;
            i++;
            j++;
        }
        return shared;
    }

    private static int latinBucket(double maxLatin) {
        if (maxLatin < 0.05d) return 0;
        if (maxLatin < AigcFamily.MIXED_RATIO) return 1;
        if (maxLatin < 0.30d) return 2;
        if (maxLatin < AigcFamily.LATIN_RATIO) return 3;
        return 4;
    }

    /** 门放在 t（要求两边拉丁占比都低于 t）时，还能活下来的过线配对数。 */
    private static int surviving(int[] byMaxLatin, double t) {
        int sum = 0;
        int stop = latinBucket(t);
        for (int i = 0; i < stop; i++) sum += byMaxLatin[i];
        return sum;
    }

    private static String head(String compact) {
        String s = compact.length() <= 34 ? compact : compact.substring(0, 34);
        return s + (compact.length() <= 34 ? "" : "…");
    }
}