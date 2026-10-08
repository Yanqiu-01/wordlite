package com.rikkahub.wordlite;

import java.util.ArrayList;

/**
 * 包含率通道的命中落点：抄一句就报那一句，不再把学生自己写的两头一起报成重复。
 *
 * 为什么单独建一台：这一档的判据看的是"短句几乎整块落在长片段里 + 长短悬殊"，是一个**比例**判据，
 * 可命中区间一度记的是整个片段，于是抄一句 22 字报出来整句 90 字全红。实测台
 * （RewriteRobustnessRegression 的 embedding 档）量的是整批同源语料上的残余多报，这一台量的是单句
 * 形状：落点必须正好等于被抄那句、交错抄袭必须端到端盖住、而 Dice 那一条路的区间一个字都不许动。
 * 三件事各自成档，混在一起就分不清是哪一条被改坏了。
 */
public final class SharedSpanRegression {
    private static int count;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        count++;
        System.out.println("PASS " + message);
    }

    private static TextCorpus.Source source(String id) {
        TextCorpus.Source source = new TextCorpus.Source();
        source.id = id;
        source.title = "文献" + id;
        return source;
    }

    private static int valid(String norm, int from, int to) {
        return from >= to ? 0 : TextCorpus.validCount(norm, from, to);
    }

    private static int validOf(String text) {
        String norm = TextCorpus.normalize(text);
        return valid(norm, 0, text.length());
    }

    /** [from,to) 里被命中盖住的有效字符。 */
    private static int covered(String norm, int from, int to, ArrayList<TextCorpus.Hit> hits) {
        int total = 0;
        for (int i = 0; i < hits.size(); i++) {
            TextCorpus.Hit hit = hits.get(i);
            if (hit.end <= from || hit.start >= to) continue;
            total += valid(norm, Math.max(from, hit.start), Math.min(to, hit.end));
        }
        return total;
    }

    /**
     * 裁剪之后的三条硬账：命中不许为零长、两两不许重叠、各条命中的有效字符之和必须等于分子。
     * 第三条是"报告 == 来源榜 == 字符账"那套等式的地基：区间一重叠或者一为空就对不上号。
     */
    private static void invariants(String label, TextCorpus.Report report, String norm) {
        ArrayList<TextCorpus.Hit> hits = report.hits;
        int sum = 0;
        for (int i = 0; i < hits.size(); i++) {
            TextCorpus.Hit hit = hits.get(i);
            check(hit.end > hit.start, label + "：第 " + i + " 条命中不是零长 [" + hit.start + "," + hit.end + ")");
            check(valid(norm, hit.start, hit.end) > 0, label + "：第 " + i + " 条命中里确有有效字符");
            sum += valid(norm, hit.start, hit.end);
            for (int j = i + 1; j < hits.size(); j++) {
                TextCorpus.Hit other = hits.get(j);
                check(!(hit.start < other.end && other.start < hit.end),
                        label + "：命中两两不重叠，第 " + i + " 与 " + j + " 条咬在一起了");
            }
        }
        check(sum == report.duplicateChars,
                label + "：各条命中有效字符之和 == 分子（" + sum + " == " + report.duplicateChars + "）");
    }

    /** 折叠串的三元组数 == 有效字符数 - 2（散文里没有重复三元组，够用来做夹具自检）。 */
    private static int gramRatioPercent(int queryChars, int candidateChars) {
        return Math.round((queryChars - 2) * 100f / (candidateChars - 2));
    }

    private static void geometry(String label, int draftChars, int copiedChars) {
        int ratio = gramRatioPercent(draftChars, copiedChars);
        check(draftChars >= TextCorpus.MIN_SENTENCE_CHARS && copiedChars >= TextCorpus.MIN_SENTENCE_CHARS,
                label + "：夹具自证，两句都进得了比对（" + draftChars + " / " + copiedChars + " 个有效字符）");
        check(ratio >= 160 && ratio <= 300, label + "：夹具自证，这是长短悬殊的嵌入（三元组比 " + ratio
                + "%，这一档可达 160%~300%）");
    }

    /** 一、嵌入：自写前句 + 抄来的整句 + 自写后句。命中必须正好是被抄那句，两头一个字都不许红。 */
    private static void embeddingLandsOnTheCopiedSentence() {
        String copied = "真空钎焊时界面金属间化合物的厚度随保温时间持续增大，超过四十分钟之后接头强度显著下降";
        String ownHead = "我们在三组对照样品上各重复了五次";
        String ownTail = "并把炉温偏差稳定控制在正负两摄氏度以内";
        String draft = ownHead + "，" + copied + "，" + ownTail + "。";
        int from = ownHead.length() + 1;
        int to = from + copied.length();

        TextCorpus corpus = new TextCorpus();
        corpus.add(source("A1"), copied + "。");
        TextCorpus.Report report = corpus.match(draft, null);
        String norm = TextCorpus.normalize(draft);
        geometry("嵌入", validOf(draft.substring(0, draft.length() - 1)), validOf(copied));

        invariants("嵌入", report, norm);
        check(report.hits.size() == 1, "嵌入只出一条命中，实测 " + report.hits.size() + " 条");
        TextCorpus.Hit hit = report.hits.get(0);
        check(hit.start == from && hit.end == to,
                "命中正好是被抄那句：期望 [" + from + "," + to + ") 实测 [" + hit.start + "," + hit.end + ")");
        check(hit.source != null && "A1".equals(hit.source.id), "这一处记在真正出处 A1 名下");
        check(covered(norm, 0, from, report.hits) == 0 && covered(norm, to, draft.length(), report.hits) == 0,
                "自己写的两头 " + (valid(norm, 0, from) + valid(norm, to, draft.length())) + " 字零红");
        check(covered(norm, from, to, report.hits) == valid(norm, from, to),
                "被抄那句 " + valid(norm, from, to) + " 个字全认回来");
    }

    /**
     * 二、交错：抄来的句子中间插一句自己的话。两截抄写各自成一段命中，各自逐字盖住；插在中间那句
     * 自己写的话一个字都不许红——它没出现在文库里，报成重复就是拿相似率换冤枉。两头再各挂一句自己的
     * 话：整段报出来就会连这两头一起红，只有按落点裁才裁得掉。
     */
    private static void interleavedCopySplitsIntoTwoSharedBlocks() {
        String left = "取样位置固定在接头中心两侧";
        String right = "每次试验都记下峰值载荷与断裂位置";
        String copied = left + "，" + right;
        String own = "剔除明显离群的三次记录";
        String ownHead = "数据处理阶段";
        String ownTail = "样本全部保留";
        String draft = ownHead + "，" + left + "，" + own + "，" + right + "，" + ownTail + "。";
        // 被抄整句在稿子里被自己那句话劈成两截，于是实际共享区间也是两截。
        int from = ownHead.length() + 1;
        int leftTo = from + left.length();
        int ownFrom = leftTo + 1;
        int ownTo = ownFrom + own.length();
        int rightFrom = ownTo + 1;
        int to = rightFrom + right.length();

        TextCorpus corpus = new TextCorpus();
        corpus.add(source("B1"), copied + "。");
        TextCorpus.Report report = corpus.match(draft, null);
        String norm = TextCorpus.normalize(draft);
        check(validOf(own) > 0 && validOf(ownHead) > 0 && validOf(ownTail) > 0,
                "交错：夹具自证，插进去和挂在外面的都是自己写的有效字符");
        invariants("交错", report, norm);
        check(report.hits.size() == 2, "交错的两截抄写各出一条命中，实测 " + report.hits.size() + " 条");
        TextCorpus.Hit first = report.hits.get(0), last = report.hits.get(report.hits.size() - 1);
        check(first.start == from && first.end == leftTo,
                "前一截逐字盖住：期望 [" + from + "," + leftTo + ") 实测 [" + first.start + "," + first.end + ")");
        check(last.start == rightFrom && last.end == to,
                "后一截逐字盖住：期望 [" + rightFrom + "," + to + ") 实测 [" + last.start + "," + last.end + ")");
        check(covered(norm, ownFrom, ownTo, report.hits) == 0,
                "插在中间的自己那句 " + valid(norm, ownFrom, ownTo) + " 字零红：没在文库里出现的字不许算重复");
        check(covered(norm, 0, from, report.hits) == 0 && covered(norm, to, draft.length(), report.hits) == 0,
                "挂在两头的自己话 " + (valid(norm, 0, from) + valid(norm, to, draft.length())) + " 字零红");
        check(covered(norm, from, to, report.hits) == valid(norm, from, leftTo) + valid(norm, rightFrom, to),
                "两截抄写 " + (valid(norm, from, leftTo) + valid(norm, rightFrom, to)) + " 字全认回来，一段都不许丢");
    }

    private static void diceChannelKeepsTheWholeFragment() {
        String original = "硬度沿截面呈梯度分布，峰值恰好落在金属间化合物一侧";
        String rewritten = "维氏硬度沿截面呈梯度分布，峰值恰好落在金属间化合物靠母材一侧";
        TextCorpus corpus = new TextCorpus();
        corpus.add(source("C1"), original + "。");
        TextCorpus.Report report = corpus.match(rewritten + "。", null);
        String norm = TextCorpus.normalize(rewritten + "。");
        int ratio = gramRatioPercent(validOf(rewritten), validOf(original));
        check(ratio < 160, "Dice 改写：夹具自证，长短接近（三元组比 " + ratio + "%，够不到 160% 那一档）");
        invariants("Dice 改写", report, norm);
        check(report.hits.size() == 1, "Dice 改写出一条命中");
        TextCorpus.Hit hit = report.hits.get(0);
        // 句末的"。"算有效字符、也进折叠串，所以整个片段连它一起报——这正是改动前逐字相同的区间。
        check(hit.start == 0 && hit.end == rewritten.length() + 1,
                "Dice 通道的命中仍是整个片段：期望 [0," + (rewritten.length() + 1) + ") 实测 ["
                        + hit.start + "," + hit.end + ")");
        check(covered(norm, 0, rewritten.length() + 1, report.hits) == valid(norm, 0, rewritten.length() + 1),
                "改写句 " + valid(norm, 0, rewritten.length() + 1) + " 个有效字符全在命中里，一个没裁掉");

        TextCorpus verbatim = new TextCorpus();
        verbatim.add(source("C2"), original + "。");
        TextCorpus.Report same = verbatim.match(original + "。", null);
        String sameNorm = TextCorpus.normalize(original + "。");
        invariants("逐字", same, sameNorm);
        check(same.hits.size() == 1 && same.hits.get(0).start == 0
                        && same.hits.get(0).end == original.length() + 1,
                "逐字复制的命中区间不动：期望 [0," + (original.length() + 1) + ") 实测 ["
                        + (same.hits.isEmpty() ? -1 : same.hits.get(0).start) + ","
                        + (same.hits.isEmpty() ? -1 : same.hits.get(0).end) + ")");
        check(covered(sameNorm, 0, original.length() + 1, same.hits) == valid(sameNorm, 0, original.length() + 1),
                "逐字那 " + valid(sameNorm, 0, original.length() + 1) + " 个有效字符一个不少");
    }

    /**
     * 四、反向嵌入不许跟着裁：草稿这侧更短、整块落在库里那句长句里时，整个片段本来就全是抄的，
     * 报整段是对的（拆句那一档靠它）。几何判据里的方向条件就是为这一条留的。
     */
    private static void shortFragmentInsideLongSentenceKeepsItsRange() {
        String longOne = "显微组织观察表明，热影响区边缘的晶界处存在明显的元素偏聚，局部电位差升高之后腐蚀抗力随之下降";
        int cut = 24;
        String slice = longOne.substring(0, cut);
        TextCorpus corpus = new TextCorpus();
        corpus.add(source("D1"), longOne + "。");
        TextCorpus.Report report = corpus.match(slice + "。", null);
        String norm = TextCorpus.normalize(slice + "。");
        int ratio = gramRatioPercent(validOf(longOne), validOf(slice));
        check(ratio >= 160 && ratio <= 300,
                "反向嵌入：夹具自证，长短同样悬殊（三元组比 " + ratio + "%），只是方向反过来");
        invariants("反向嵌入", report, norm);
        check(report.hits.size() == 1, "反向嵌入出一条命中");
        TextCorpus.Hit hit = report.hits.get(0);
        check(hit.start == 0 && hit.end == cut + 1,
                "草稿更短的那一侧仍然报整段（连句末标点）：期望 [0," + (cut + 1) + ") 实测 ["
                        + hit.start + "," + hit.end + ")");
        check(covered(norm, 0, cut, report.hits) == valid(norm, 0, cut),
                "碎片 " + valid(norm, 0, cut) + " 个有效字符全在命中里");
    }

    /** 五、成批跑一遍：落点裁剪不许造出零长、重叠，或者与分子对不上号的账。 */
    private static void batchKeepsTheLedger() {
        String[][] pairs = {
            {"真空钎焊时界面金属间化合物的厚度随保温时间持续增大，超过四十分钟之后接头强度显著下降",
             "我们在三组对照样品上各重复了五次，并把炉温偏差稳定控制在正负两摄氏度以内"},
            {"取样位置固定在接头中心两侧，每次试验都记下峰值载荷与断裂位置",
             "剔除明显离群的三次记录，数据处理阶段重新拟合回归曲线"},
            {"硬度沿截面呈梯度分布，峰值恰好落在金属间化合物一侧",
             "对比试样采用同炉同批材料，以排除批次差异带来的干扰"},
            {"衍射峰位偏移说明晶格常数发生了变化，固溶程度不同是主要原因",
             "标定用标准试块由第三方实验室提供，并附检定证书编号"},
        };
        StringBuilder draft = new StringBuilder();
        TextCorpus corpus = new TextCorpus();
        for (int i = 0; i < pairs.length; i++) {
            corpus.add(source("E" + i), pairs[i][0] + "。");
            String[] halves = pairs[i][1].split("，");
            draft.append(halves[0]).append("，").append(pairs[i][0]).append("，");
            if (halves.length > 1) draft.append(halves[1]).append("，");
            draft.append("其余工艺参数保持不变。\n");
        }
        String text = draft.toString();
        TextCorpus.Report report = corpus.match(text, null);
        String norm = TextCorpus.normalize(text);
        invariants("成批", report, norm);
        check(report.hits.size() >= pairs.length,
                "成批 " + pairs.length + " 句各有命中，实测 " + report.hits.size() + " 条");
        check(report.duplicateChars > 0 && report.duplicateChars < report.comparedChars,
                "成批这一档两头都量到了：分子 " + report.duplicateChars + " < 分母 " + report.comparedChars);
        for (int i = 0; i < pairs.length; i++) {
            if (!text.contains(pairs[i][0])) throw new AssertionError("夹具拼坏了：第 " + i + " 句没进稿子");
        }
    }

    public static void main(String[] args) {
        embeddingLandsOnTheCopiedSentence();
        interleavedCopySplitsIntoTwoSharedBlocks();
        diceChannelKeepsTheWholeFragment();
        shortFragmentInsideLongSentenceKeepsItsRange();
        batchKeepsTheLedger();
        System.out.println("SUMMARY " + count + " assertions passed"
                + " (单句落点只在 host JVM 上跑：界面高亮与报告排版不在本用例范围内).");
    }
}