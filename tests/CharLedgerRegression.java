package com.rikkahub.wordlite;

import java.util.ArrayList;

/**
 * 字符账本（0.7.1）回归。要挡的回归只有一件事：报告里的"占多少"重新变回各量各的。
 *
 * 钉死四类事：① 三个互斥桶（未标引用的重复 / 引用区间内的重复 / 自编）字符级与百分比级都闭合，
 * 残差恒为 0——它是同一次划分的结果，所以这条断言不依赖数字凑巧对上；② 自编率真的减掉了已判重复的字符，
 * 连落在引用区间里的那部分也减（旧口径只减未标注的那份，引用内的 150 字会被白算成自编）；
 * ③ 判"可疑"的门槛就是检测侧那条 0.45，账本不再藏 0.5；④ 边界：空文档、全重复、全自编、
 * 只有引用区间、命中越界、命中互相重叠、排除区里的命中、排除区吃掉全文。
 *
 * 全程不联网、不碰 Android。夹具是纯汉字块 + 已知长度，每个期望值都是手算整数；
 * 比率一律写成"手算分数"的样子（例如 364 * 100 / 900），不抄实测输出。
 */
public final class CharLedgerRegression {
    private static int count;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        count++;
        System.out.println("PASS " + message);
    }

    /** 长度即字数的汉字块：不含空白与不可见字符，所以有效字符数 == 长度，手算只用加减。 */
    private static String block(int chars, int seed) {
        StringBuilder out = new StringBuilder(chars);
        for (int i = 0; i < chars; i++) out.append((char) (0x4E00 + ((i + seed) * 37) % 400));
        return out.toString();
    }

    private static int[] spans(int... pairs) { return pairs; }

    /**
     * 每个夹具都过这一关：三个桶互斥且加和等于分母（字符级严格等于 0 残差），
     * 百分比级两条闭合在 1e-9 内成立，分子不许越过分母，自编率不许是负数。
     */
    private static void closed(CharLedger.Balance balance, String label) {
        check(balance.residual() == 0, label + "：未标引用重复 + 引用内重复 + 自编 - 分母 == 0（字符级恒等，实测残差 "
                + balance.residual() + "）");
        check(Math.abs(balance.rateResidual()) < 1e-9, label + "：三个互斥桶的占比之和 == 100（残差 "
                + balance.rateResidual() + "）");
        check(Math.abs(balance.headlineResidual()) < 1e-9, label + "：总相似度比 + 自编率 == 100（残差 "
                + balance.headlineResidual() + "）");
        check(balance.duplicateChars >= 0 && balance.duplicateChars <= balance.totalChars,
                label + "：分子不会越过分母（重复 " + balance.duplicateChars + " <= 分母 " + balance.totalChars + "）");
        check(balance.selfWrittenChars >= 0 && balance.citedDuplicateChars <= balance.duplicateChars
                        && balance.uncitedDuplicateChars + balance.citedDuplicateChars == balance.duplicateChars,
                label + "：自编不为负，引用内外两份重复加起来就是总重复");
    }

    // ---- 主夹具：1000 个有效字符、三段、两个换行 ----
    // A 段 600 字 [0,600)，换行 [600,601)，B 段 300 字 [601,901)，换行 [901,902)，C 段 100 字 [902,1002)。
    // 换行是空白，validCount 不计，所以全文有效字符 600 + 300 + 100 = 1000。

    private static final String A = block(600, 1);
    private static final String B = block(300, 2);
    private static final String C = block(100, 3);
    private static final String DOC = A + "\n" + B + "\n" + C;

    /** 主夹具的重复区间：并集是 [0,450) + [595,610) + [700,750)，另有一条整段落在被排除的 C 段里。 */
    private static final int[] DOC_DUPLICATES = spans(0, 400, 350, 450, 700, 750, 595, 610, 910, 960);
    private static final int[] DOC_CITATIONS = spans(300, 500);
    private static final int[] DOC_EXCLUDED = spans(902, 1002);
    private static final int[] DOC_MACHINE = spans(100, 200, 860, 905);

    public static void main(String[] args) {
        ruler();
        closure();
        gate();
        edges();
        overruns();
        versusTextCorpus();
        System.out.println("SUMMARY " + count + " assertions passed"
                + " (字符账本只在 host JVM 上跑：检索链路与界面高亮不在本用例范围内).");
    }

    /** 分母就是那把唯一的尺子：与 TextCorpus 的 validCount、AigcDetector 的 comparedChars 同一个数。 */
    private static void ruler() {
        check(A.length() == 600 && B.length() == 300 && C.length() == 100 && DOC.length() == 1002,
                "夹具先自证：600+300+100 个汉字，加两个换行是 1002 个 UTF-16 单元");
        check(TextCorpus.validCount(TextCorpus.normalize(DOC), 0, DOC.length()) == 1000,
                "全文有效字符手算 1000：两个换行是空白，一个都不算");
        CharLedger.Balance plain = CharLedger.closeSpans(DOC, null, null, null, null);
        check(plain.totalChars == 1000 && plain.excludedChars == 0 && plain.duplicateChars == 0,
                "没有排除、没有命中：分母 1000，分子 0");
        closed(plain, "空账本");
        // 检测侧自己数出来的计分字数必须与账本分母同一个数，否则"可疑 412 字 / 全文 6300 字"是两把尺。
        AigcDetector.Result aigc = AigcDetector.detect(DOC);
        check(aigc.comparedChars == plain.totalChars,
                "与 AIGC 同尺：AigcDetector.comparedChars " + aigc.comparedChars + " == 账本分母 " + plain.totalChars);
        check(plain.selfWrittenChars == 1000 && plain.selfWrittenRate == 100d,
                "一个字都没判重复时自编率是 100，不是 0");
    }

    /** 闭合与自编率：分子分母同尺、三个桶互斥、自编真的减掉了全部已判重复。 */
    private static void closure() {
        CharLedger.Balance balance = CharLedger.closeSpans(DOC, DOC_EXCLUDED, DOC_CITATIONS, DOC_DUPLICATES, DOC_MACHINE);
        closed(balance, "主夹具");
        // 分母：1000 减去被排除的 C 段 100 == 900。
        check(balance.totalChars == 900, "分母手算 900：1000 个字减去排除区（C 段）100 个字");
        check(balance.excludedChars == 100, "排除区自己那份是 100 个字");
        // 分子：并集 [0,450)=450 + [595,610)=14（跨过换行，换行不计）+ [700,750)=50 == 514。
        check(balance.duplicateChars == 514,
                "重复字符手算 514：重叠那 50 个字（350..400）只算一次，跨换行那段只算 14 个字");
        // 落在引用区间 [300,500) 里的那份重复是 [300,450) == 150。
        check(balance.citedDuplicateChars == 150, "引用区间内的重复手算 150：并集与 [300,500) 相交得 [300,450)");
        check(balance.uncitedDuplicateChars == 364, "未标引用的重复 514 - 150 == 364");
        // 这条就是 0.7.1 要修的本体：自编 = 900 - 514，引用区间内的 150 个字同样不算自编。
        check(balance.selfWrittenChars == 386, "自编字符 900 - 514 == 386：已判重复的一律从自编里减掉");
        check(balance.selfWrittenChars != balance.totalChars - balance.uncitedDuplicateChars,
                "旧口径（只减未标注的重复，得 536 字）在这里必须不成立：引用内的 150 个字不能算自编");
        check(balance.overallRate == 514 * 100d / 900d && balance.excludingCitationsRate == 364 * 100d / 900d
                        && balance.citedDuplicateRate == 150 * 100d / 900d
                        && balance.selfWrittenRate == 386 * 100d / 900d,
                "四个比率都写成手算分数：514/900、364/900、150/900、386/900，分母是同一个 900");
        // 机器腔是另一条轴：可疑区间与重复区间重叠也不许互相冲抵。
        check(balance.machineChars == 141,
                "机器腔可疑字符手算 141：[100,200) 整段 100 + B 段尾巴 41（越进排除区 C 的那 3 个字被裁掉）");
        check(balance.selfWrittenRate == 386 * 100d / 900d,
                "机器腔那 141 个字没从自编率里被减第二次：两条轴不互相冲抵");
    }

    /** 门槛口径：账本认的那条线就是检测侧的 0.45，而且只收 flagged 的区间。 */
    private static void gate() {
        check(CharLedger.FLAG_SCORE_GATE == AigcDetector.SEGMENT_FLAG_GATE,
                "门槛常量直接引用检测侧那一个：CharLedger.FLAG_SCORE_GATE == SEGMENT_FLAG_GATE == 0.45");
        check(CharLedger.FLAG_SCORE_GATE == 0.45f, "这条线实测是 0.45（docs/aigc-calibration.md 的 TIER 行）");
        check(CharLedger.FLAG_SCORE_GATE != 0.5f,
                "0.7.0 账本里那把凭空的 0.5 尺子已经没了：同一篇稿子不允许指标区与证据区各说各话");
        AigcDetector.Result aigc = new AigcDetector.Result();
        aigc.segments.add(segment(0, 100, 0.44f, false));
        aigc.segments.add(segment(100, 200, AigcDetector.SEGMENT_FLAG_GATE, true));
        aigc.segments.add(segment(200, 300, 0.9f, false));
        aigc.segments.add(segment(300, 400, 0.6f, true));
        ArrayList<TextCorpus.Hit> none = new ArrayList<TextCorpus.Hit>();
        CharLedger.Balance balance = CharLedger.close(DOC, null, null, none, aigc);
        check(balance.machineChars == 200,
                "只有过线且 flagged 的区间进账：0.44 不算、0.45 压线算、0.9 但没 flagged 也不算 == 200 字");
        closed(balance, "门槛夹具");
        check(balance.selfWrittenChars == 1000 && balance.overallRate == 0d,
                "机器腔一个字都不进重复：可疑 200 字的情况下总相似度比仍是 0");
        AigcDetector.Result empty = new AigcDetector.Result();
        check(CharLedger.close(DOC, null, null, none, empty).machineChars == 0, "没有区间时可疑字数是 0，不是分母");
    }

    private static AigcDetector.Segment segment(int start, int end, float score, boolean flagged) {
        AigcDetector.Segment segment = new AigcDetector.Segment();
        segment.start = start;
        segment.end = end;
        segment.score = score;
        segment.flagged = flagged;
        return segment;
    }

    /** 四个边界：空文档、全重复、全自编、只有引用区间。 */
    private static void edges() {
        String text = block(200, 7);
        CharLedger.Balance none = CharLedger.closeSpans(text, null, null, null, null);
        closed(none, "全自编");
        check(none.totalChars == 200 && none.duplicateChars == 0 && none.selfWrittenChars == 200
                        && none.overallRate == 0d && none.selfWrittenRate == 100d,
                "全自编：分母 200、分子 0、自编率 100");
        CharLedger.Balance all = CharLedger.closeSpans(text, null, null, spans(0, 200), null);
        closed(all, "全重复");
        check(all.totalChars == 200 && all.duplicateChars == 200 && all.selfWrittenChars == 0
                        && all.overallRate == 100d && all.selfWrittenRate == 0d,
                "全重复：分母 200、分子 200、自编率 0，闭合仍然成立");
        CharLedger.Balance cited = CharLedger.closeSpans(text, null, spans(0, 200), spans(0, 100), null);
        closed(cited, "只有引用区间");
        check(cited.citedDuplicateChars == 100 && cited.uncitedDuplicateChars == 0
                        && cited.excludingCitationsRate == 0d && cited.overallRate == 50d
                        && cited.selfWrittenRate == 50d,
                "重复全部落在引用区间内：去除引用重复比是 0，总相似度比 50，自编率 50");
        CharLedger.Balance empty = CharLedger.closeSpans("", null, null, null, null);
        closed(empty, "空文档");
        CharLedger.Balance nullText = CharLedger.closeSpans(null, null, null, null, null);
        closed(nullText, "null 文档");
        CharLedger.Balance blanks = CharLedger.closeSpans("   \t\n  ", null, null, spans(0, 3), null);
        closed(blanks, "只有空白");
        check(empty.totalChars == 0 && nullText.totalChars == 0 && blanks.totalChars == 0
                        && empty.overallRate == 0d && empty.selfWrittenRate == 0d
                        && blanks.selfWrittenRate == 0d,
                "没有可计分的字时一个比率都不给：自编率是 0 而不是 100，空文档不是一份干净的稿子");
        CharLedger.Balance swallowed = CharLedger.closeSpans(text, spans(0, 200), null, spans(0, 200), null);
        closed(swallowed, "排除区吃掉全文");
        check(swallowed.totalChars == 0 && swallowed.excludedChars == 200 && swallowed.duplicateChars == 0,
                "全文都被排除时分子也跟着归零：排除区里的命中一个字都不算重复");
    }

    /** 越界与重叠：分子永远不可能越过分母，这是闭合的另一半保障。 */
    private static void overruns() {
        String text = block(200, 11);
        // 越界、倒置、零长、重复登记四件事一次演完：裁到 [0,200) 之后并集是 [0,100) + [180,200)。
        CharLedger.Balance messy = CharLedger.closeSpans(text, null, null,
                spans(-50, 100, 150, 90, 120, 120, 180, 9999, 0, 60), null);
        closed(messy, "越界命中");
        check(messy.duplicateChars == 120 && messy.selfWrittenChars == 80,
                "重复字符手算 120：[0,100) 与 [180,200) 共 120 个字，越界的 9799 个字被裁掉而不是加进分子");
        // 两条命中互相重叠：一个字符只认一次，这是旧口径里长句滑窗会数两遍的那笔账。
        CharLedger.Balance overlap = CharLedger.closeSpans(text, null, null, spans(0, 150, 50, 200), null);
        closed(overlap, "重叠命中");
        check(overlap.duplicateChars == 200 && overlap.selfWrittenChars == 0,
                "重叠的 100 个字只算一次：并集 200 个字，不会像按命中逐条累加那样报出 350");
        check(overlap.overallRate == 100d, "分子等于分母时总相似度比封顶在 100，不会给出 175% 这种数");
    }

    /**
     * 与 TextCorpus 的已知缺陷对账：长句滑窗重叠的 64 个字在 match() 的分子里算了两遍（896 > 832），
     * 账本从同一批命中出发必须先并区间再数字——那 64 个字在报告里只能算一次。
     */
    private static void versusTextCorpus() {
        StringBuilder longA = new StringBuilder();
        for (int i = 0; i < 512; i++) longA.append((char) (0x4E00 + (i * 37) % 400));
        StringBuilder longB = new StringBuilder();
        for (int i = 0; i < 320; i++) longB.append((char) (0x4E00 + 400 + (i * 53) % 400));
        TextCorpus corpus = new TextCorpus();
        TextCorpus.Source first = new TextCorpus.Source();
        first.title = "长句来源甲";
        first.engine = "openalex";
        TextCorpus.Source second = new TextCorpus.Source();
        second.title = "长句来源乙";
        second.engine = "crossref";
        corpus.add(first, longA.toString());
        corpus.add(second, longB.toString());
        String query = longA.toString() + longB.toString();
        TextCorpus.Report matched = corpus.match(query, null);
        check(matched.comparedChars == 832 && matched.duplicateChars == 896,
                "TextCorpus 那侧的已知缺陷照旧：分母 832，分子 896（滑窗重叠的 64 个字算了两遍）");
        check(matched.hits.size() == 2, "两条命中：0..512 与 448..832");
        CharLedger.Balance ledger = CharLedger.close(query, null, null, matched.hits, null);
        closed(ledger, "长句滑窗");
        check(ledger.totalChars == 832, "账本分母与 match 的分母同一个数：832 个有效字符");
        check(ledger.duplicateChars == 832 && ledger.selfWrittenChars == 0,
                "账本把 896 收敛成 832：重叠的 64 个字只认一次，分子不再越过分母");
        check(ledger.overallRate == 100d && Math.abs(ledger.headlineResidual()) < 1e-9,
                "收敛之后总相似度比 100 加自编率 0 仍然闭合；旧口径这里会算出 107.69%");
        ArrayList<TextCorpus.Hit> hits = new ArrayList<TextCorpus.Hit>();
        hits.addAll(matched.hits);
        TextCorpus.Hit foreign = new TextCorpus.Hit();
        foreign.start = 900;
        foreign.end = 1200;
        hits.add(foreign);
        check(CharLedger.close(query, null, null, hits, null).duplicateChars == 832,
                "凭空多出来的一条越界命中被裁光：分子还是 832，报告不会被撑破");
    }
}