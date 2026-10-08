package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.Collections;

/**
 * 命中地图口径的回归：四段互不重叠、宽度加起来等于字符账、优先级（重复 > 改写 > AI 可疑 > 疑似）、
 * 越界收口、空隙的点击语义、被截断时必须标出不完整。全程不碰 Android、不读文件。
 */
public final class HitMapRegression {
    private static int count;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        count++;
        System.out.println("PASS " + message);
    }

    private static ReportStore.Evidence evidence(int start, int end, int channel) {
        ReportStore.Evidence hit = new ReportStore.Evidence();
        hit.start = start;
        hit.end = end;
        hit.channel = channel;
        return hit;
    }

    private static ReportStore.AiSegment ai(int start, int end, boolean flagged) {
        ReportStore.AiSegment seg = new ReportStore.AiSegment();
        seg.start = start;
        seg.end = end;
        seg.chars = end - start;
        seg.flagged = flagged;
        return seg;
    }

    private static HitMapModel.Map build(int total, ArrayList<ReportStore.Evidence> ev,
                                         ArrayList<ReportStore.AiSegment> ai) {
        return HitMapModel.build(total, ev, ai, false, ev == null ? 0 : ev.size(),
                ev == null ? 0 : ev.size(), ai == null ? 0 : ai.size(), ai == null ? 0 : ai.size());
    }

    /** 账目恒等式：段宽之和 + 空隙 == 全文字数；段两两不重叠且按位置排好。 */
    private static void ledger(HitMapModel.Map map, String label) {
        int sum = 0;
        for (int i = 0; i < map.bands.size(); i++) {
            HitMapModel.Band band = map.bands.get(i);
            sum += band.chars();
            check(band.chars() > 0, label + "：每段必须为正宽度");
            if (i > 0) {
                HitMapModel.Band last = map.bands.get(i - 1);
                check(band.start >= last.end, label + "：段与段不许重叠，实测 " + last.start + "-" + last.end
                        + " 与 " + band.start + "-" + band.end);
            }
        }
        check(sum == map.flaggedChars, label + "：flaggedChars 必须等于段宽之和，实测 " + map.flaggedChars
                + " 对 " + sum);
        check(map.flaggedChars + map.gapChars == map.totalChars,
                label + "：涂上的字加上没涂的字必须正好是全文，实测 " + map.flaggedChars + " + "
                        + map.gapChars + " != " + map.totalChars);
    }

    public static void main(String[] args) {
        emptyAndDegenerate();
        duplicatesAndRewrites();
        priorityOverAi();
        edgesAndClicks();
        System.out.println("SUMMARY " + count + " hit-map assertions passed（地图宽度与色带只到 host JVM；"
                + "onDraw 的像素与点击派发不在本套件范围内）.");
    }

    private static void emptyAndDegenerate() {
        HitMapModel.Map empty = build(4000, new ArrayList<ReportStore.Evidence>(),
                new ArrayList<ReportStore.AiSegment>());
        check(empty.isEmpty(), "什么都没查出来时地图是空的（界面据此印未检出，而不是画一条零宽条）");
        check(empty.flaggedChars == 0 && empty.gapChars == 4000, "空报告里全文都算没涂上的字");
        ledger(empty, "空报告");
        HitMapModel.Map zeroTotal = build(0, one(evidence(0, 10, TextCorpus.CHANNEL_VERBATIM)), null);
        check(zeroTotal.isEmpty() && zeroTotal.gapChars == 0, "全文零字时返回空图而不是崩");
        check(build(-5, null, null).isEmpty(), "分母为负也照样只是空图");
    }

    private static void duplicatesAndRewrites() {
        ArrayList<ReportStore.Evidence> ev = new ArrayList<ReportStore.Evidence>();
        ev.add(evidence(100, 200, TextCorpus.CHANNEL_VERBATIM));
        ev.add(evidence(300, 340, TextCorpus.CHANNEL_REWRITE));
        HitMapModel.Map map = build(1000, ev, null);
        check(map.bands.size() == 2, "两处命中就是两段，实测 " + map.bands.size());
        check(map.bands.get(0).kind == HitMapModel.KIND_DUPLICATE
                && map.bands.get(1).kind == HitMapModel.KIND_REWRITTEN,
                "色带的颜色由命中的判据出处决定，不许都涂成重复");
        check(Math.abs(map.share(map.bands.get(0)) - 0.1d) < 1e-9,
                "段宽就是字数占比：一百字占一千字 = 0.1");
        ledger(map, "两段命中");
        // 颜色与输入顺序无关（同一次检测里 hits 是排好序的，但重建老库时不一定）。
        ArrayList<ReportStore.Evidence> shuffled = new ArrayList<ReportStore.Evidence>();
        shuffled.add(evidence(300, 340, TextCorpus.CHANNEL_REWRITE));
        shuffled.add(evidence(100, 200, TextCorpus.CHANNEL_VERBATIM));
        HitMapModel.Map again = build(1000, shuffled, null);
        check(again.bands.size() == 2 && again.bands.get(0).start == 100
                        && again.bands.get(1).kind == HitMapModel.KIND_REWRITTEN,
                "输入顺序换了，地图必须一模一样（按位置排，不按入列顺序排）");
    }

    private static void priorityOverAi() {
        ArrayList<ReportStore.Evidence> ev = new ArrayList<ReportStore.Evidence>();
        ev.add(evidence(100, 200, TextCorpus.CHANNEL_VERBATIM));
        ArrayList<ReportStore.AiSegment> ai = new ArrayList<ReportStore.AiSegment>();
        ai.add(ai(120, 160, true));      // 整段落在重复里：重复已经进过分子，AI 不许再占一次
        ai.add(ai(180, 400, true));      // 跨出重复边界：只许画没被占走的那一截
        ai.add(ai(600, 700, false));     // 疑似档，独立一段
        HitMapModel.Map map = build(1000, ev, ai);
        ledger(map, "重复压 AI");
        check(map.bands.get(0).kind == HitMapModel.KIND_DUPLICATE && map.bands.get(0).end == 200,
                "重复段整段保住，不被 AI 切开");
        boolean sawFullInsideAi = false;
        boolean clipped = false;
        int aiChars = 0;
        for (int i = 0; i < map.bands.size(); i++) {
            HitMapModel.Band band = map.bands.get(i);
            if (band.kind != HitMapModel.KIND_AI) continue;
            if (band.start == 120 && band.end == 160) sawFullInsideAi = true;
            if (band.start == 200 && band.end == 400) clipped = true;
            aiChars += band.chars();
        }
        check(!sawFullInsideAi, "整段落在重复里的 AI 段必须消失：同一批字不许涂两种颜色");
        check(clipped, "跨过重复边界的 AI 段要裁成剩下那截，实测 AI 段合计 " + aiChars + " 字");
        check(aiChars == 200, "AI 可疑只画 200-400 那一截 = 200 字：180 之前那 20 个字已经被重复占走，疑似那一档另算");
        boolean suspected = false;
        for (int i = 0; i < map.bands.size(); i++) {
            if (map.bands.get(i).kind == HitMapModel.KIND_SUSPECTED) suspected = true;
        }
        check(suspected, "没到可疑线的 AI 段仍然要画出来——它进地图但不进任何比率");
        // 疑似与可疑重叠时，可疑赢。
        ArrayList<ReportStore.AiSegment> both = new ArrayList<ReportStore.AiSegment>();
        both.add(ai(500, 600, false));
        both.add(ai(540, 560, true));
        HitMapModel.Map tiers = build(1000, null, both);
        ledger(tiers, "两档 AI 重叠");
        for (int i = 0; i < tiers.bands.size(); i++) {
            HitMapModel.Band band = tiers.bands.get(i);
            check(band.kind != HitMapModel.KIND_SUSPECTED || band.end <= 540 || band.start >= 560,
                    "可疑段占住的那 20 个字不许再涂成疑似");
        }
    }

    private static void edgesAndClicks() {
        ArrayList<ReportStore.Evidence> ev = new ArrayList<ReportStore.Evidence>();
        ev.add(evidence(-20, 12, TextCorpus.CHANNEL_VERBATIM));   // 起点越界
        ev.add(evidence(990, 1040, TextCorpus.CHANNEL_VERBATIM)); // 终点越界
        ev.add(evidence(500, 500, TextCorpus.CHANNEL_VERBATIM));  // 空区间
        ev.add(evidence(300, 302, TextCorpus.CHANNEL_VERBATIM));  // 短于 MIN_BAND_CHARS
        HitMapModel.Map map = build(1000, ev, null);
        ledger(map, "越界与碎片");
        check(map.bands.size() == 2, "越界收口、空区间与两字碎片都不成段，实测 " + map.bands.size() + " 段");
        check(map.bands.get(0).start == 0 && map.bands.get(0).end == 12, "起点越界收到 0");
        check(map.bands.get(1).end == 1000, "终点越界收到全文末尾");
        check(map.indexAt(5) == 0 && map.indexAt(20) == -1 && map.indexAt(12) == -1,
                "点在某段里返回该段，点在空隙或段的右端点上返回 -1");
        check(map.indexAt(999) == 1, "最后一格的点击要落得进去");
        // 空隙的落点：跳最近的那一段，口径在模型里定，View 只换算像素。
        ArrayList<ReportStore.Evidence> two = new ArrayList<ReportStore.Evidence>();
        two.add(evidence(100, 200, TextCorpus.CHANNEL_VERBATIM));
        two.add(evidence(800, 900, TextCorpus.CHANNEL_REWRITE));
        HitMapModel.Map tapped = build(1000, two, null);
        check(tapped.nearestBand(150) == tapped.bands.get(0), "落点在某段里就是那一段");
        check(tapped.nearestBand(300) == tapped.bands.get(0) && tapped.nearestBand(700) == tapped.bands.get(1),
                "空隙里的落点跳最近的那一段");
        check(tapped.nearestBand(-9) == tapped.bands.get(0) && tapped.nearestBand(4000) == tapped.bands.get(1),
                "带子两端的落点各自跳到头一段与末一段，不许跳到中间去");
        check(build(1000, null, null).nearestBand(500) == null, "空地图上的落点什么都跳不到");
        HitMapModel.Map cut = HitMapModel.build(1000, ev, null, true, 40, 128, 0, 0);
        check(cut.incomplete && cut.keptEvidence == 40 && cut.totalEvidence == 128,
                "证据被上限截过时，地图自己带着已读/应收这两个数，界面才有得说");
        ledger(cut, "截断态");
    }

    private static ArrayList<ReportStore.Evidence> one(ReportStore.Evidence hit) {
        ArrayList<ReportStore.Evidence> list = new ArrayList<ReportStore.Evidence>();
        list.add(hit);
        Collections.reverse(list);
        return list;
    }
}