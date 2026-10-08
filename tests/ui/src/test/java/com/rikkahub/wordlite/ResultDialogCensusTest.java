package com.rikkahub.wordlite;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

/**
 * 结果页（查重结果）的 View 层普查。这一屏整个装在 ScrollView 里，而 uiautomator 的 dump 只报
 * 与滚动视口有交集的节点：交集为空的那个根本不出现。于是"dump 里没有"既可能是"没建"，也可能是
 * "建了但在折线外"，两句话的意思相反——2.5.0 那一次的实测就是这样：10 篇可下载的候选在静止那份
 * dump 里一篇都没露，可点得动的那十行其实全在。这里绕开 dump，走真代码路径 showScan() 把同一屏
 * 在 360dp 上建出来，再按 dump 同一条求交规矩分类。视口高取真机 dump 量到的滚动带 [278..2166]。
 */
@RunWith(RobolectricTestRunner.class)
// 与 RibbonUITest 同一档：480dpi、360dp 宽 = 1080px。文字度量用 NATIVE（默认），LEGACY 下中文
// 字宽接近 0，行高假小，折线位置就不是手机上那条。
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "zh-rCN-w360dp-xxhdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
public class ResultDialogCensusTest {
    private static final int WIDTH = 1080;
    /** 真机那一次结果页的滚动带高度：2166 - 278。 */
    private static final int VIEWPORT = 1888;
    private static final int CANDIDATES = 10;
    private static final int NOTES = 22;

    /** 静止与滚到底两档的分类都从这一棵树上读：树建不出来，后面什么都不必谈。 */
    private static final class Screen {
        ScrollView scroll;
        LinearLayout box;
    }

    @Test public void everySectionOfTheResultPageIsBuiltAsViews() throws Exception {
        Screen screen = resultPage();
        // 四行指标区 + 时间戳 + 提示×N + 汇总两行 + 逐篇 N 行 + 降重一行 + 命中片段一行。
        assertEquals("结果页建的行数对不上（有人动了 showScan 的结构就看这里）",
                4 + NOTES + 2 + CANDIDATES + 2, screen.box.getChildCount());
        int header = 4 + NOTES;
        int every = header + 1;
        int first = every + 1;
        int fix = first + CANDIDATES;
        TextView headerLine = (TextView) screen.box.getChildAt(header);
        assertFalse("汇总那一行是说明，不是按钮", headerLine.isClickable());
        assertTrue("汇总那一行要带篇数", headerLine.getText().toString().contains(CANDIDATES + " 篇"));
        assertTrue("汇总那一行要说下去哪儿", headerLine.getText().toString().contains("自建库"));
        TextView everyLine = (TextView) screen.box.getChildAt(every);
        assertTrue("整批那一行点得动", everyLine.isClickable());
        assertTrue("整批那一行是粗体", isBold(everyLine));
        for (int i = 0; i < CANDIDATES; i++) {
            TextView row = (TextView) screen.box.getChildAt(first + i);
            assertTrue("第 " + (i + 1) + " 篇那一行点得动", row.isClickable());
            assertTrue("第 " + (i + 1) + " 篇那一行有字", row.getText().length() > 0);
        }
        TextView fixLine = (TextView) screen.box.getChildAt(fix);
        assertTrue("降重那一行点得动", fixLine.isClickable());
        assertTrue("降重那一行是粗体", isBold(fixLine));
        assertTrue("降重那一行跟着命中数", fixLine.getText().toString().contains("1"));
        assertNotNull("命中片段那一行也在", screen.box.getChildAt(fix + 1));
    }

    @Test public void theCutSectionIsBelowTheFoldAndScrollingBringsItBack() throws Exception {
        Screen screen = resultPage();
        int header = 4 + NOTES;
        int fix = header + 1 + CANDIDATES + 1;
        int content = screen.box.getMeasuredHeight();
        assertTrue("这一屏比视口矮的话，本项量不到折线：内容 " + content + " 视口 " + VIEWPORT,
                content > VIEWPORT);
        census(screen, "静止 scroll_y=0");
        assertEquals("静止时降重那一行在折线外", "折线外",
                visibility(screen.scroll, screen.box.getChildAt(fix)));
        assertTrue("静止时至少有 5 行整行在折线外（dump 不报它们的真正原因）：实际 "
                + countBelowFold(screen), countBelowFold(screen) >= 5);
        screen.scroll.scrollTo(0, content - VIEWPORT);
        census(screen, "滚到底 scroll_y=" + screen.scroll.getScrollY());
        assertEquals("滚到底时降重那一行完整可见：它在，只是要滚", "看得全",
                visibility(screen.scroll, screen.box.getChildAt(fix)));
        // 再把汇总那一行整行挪进视口：能挪进来的东西就不是没建的东西。
        screen.scroll.scrollTo(0, offsetInScroll(screen.scroll, screen.box.getChildAt(header)));
        census(screen, "挪到汇总行 scroll_y=" + screen.scroll.getScrollY());
        assertEquals("汇总那一行滚得进来", "看得全",
                visibility(screen.scroll, screen.box.getChildAt(header)));
        assertEquals("整批下载那一行也滚得进来", "看得全",
                visibility(screen.scroll, screen.box.getChildAt(header + 1)));
    }

    private Screen resultPage() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ApiWorkflow workflow = new ApiWorkflow(activity, new StubHost());
        DuplicateEngine.Report report = new DuplicateEngine.Report();
        report.overallRate = 0.0013; report.excludingCitationsRate = 0.0013;
        report.selfWrittenRate = 0.9987;
        report.detectedAt = "2026-10-08T22:46:38Z"; report.elapsedMillis = 24_000L;
        for (int i = 0; i < NOTES; i++)
            report.notes.add("可比材料只有摘要的有 37 篇，共 48 篇，其中 0 篇抓到开放获取全文，"
                    + "11 篇只有题录无法比对，相似率是下限，第 " + i + " 条");
        for (int i = 0; i < CANDIDATES; i++)
            report.downloadables.add(new DuplicateEngine.Downloadable(
                    "Isolation, purification and immunomodulatory activity of polysaccharide "
                            + "fractions from a medicinal mushroom, sample " + i,
                    "openalex", "https://example.org/open-access-" + i));
        TextCorpus.Hit hit = new TextCorpus.Hit();
        hit.start = 0; hit.end = 20; hit.score = 0.82f; hit.channel = TextCorpus.CHANNEL_VERBATIM;
        report.hits.add(hit);
        Field lastScan = ApiWorkflow.class.getDeclaredField("lastScan");
        lastScan.setAccessible(true);
        lastScan.set(workflow, report);
        Method showScan = ApiWorkflow.class.getDeclaredMethod("showScan");
        showScan.setAccessible(true);
        showScan.invoke(workflow);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull("结果页没弹出来", dialog);
        Screen screen = new Screen();
        screen.scroll = findScroll(dialog.getWindow().getDecorView());
        assertNotNull("结果页那一屏不在滚动容器里，量法要改", screen.scroll);
        screen.scroll.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(VIEWPORT, View.MeasureSpec.EXACTLY));
        screen.scroll.layout(0, 0, WIDTH, VIEWPORT);
        screen.box = (LinearLayout) ((ViewGroup) screen.scroll).getChildAt(0);
        return screen;
    }

    private int countBelowFold(Screen screen) {
        int n = 0;
        for (int i = 0; i < screen.box.getChildCount(); i++)
            if ("折线外".equals(visibility(screen.scroll, screen.box.getChildAt(i)))) n++;
        return n;
    }

    /** 与 uiautomator 同一条规矩：节点矩形对滚动视口求交，交集为空的那个不会出现在 dump 里。 */
    private String visibility(ScrollView scroll, View child) {
        int top = offsetInScroll(scroll, child) - scroll.getScrollY();
        int bottom = top + child.getHeight();
        int bandTop = scroll.getPaddingTop();
        int bandBottom = scroll.getHeight() - scroll.getPaddingBottom();
        int shown = Math.min(bottom, bandBottom) - Math.max(top, bandTop);
        if (shown <= 0) return top < bandTop ? "折线以上" : "折线外";
        return shown >= child.getHeight() ? "看得全" : "切断";
    }

    private int offsetInScroll(ScrollView scroll, View child) {
        int y = 0;
        for (View v = child; v != null && v != scroll; ) {
            y += v.getTop();
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return y;
    }

    /** AlertDialog 自己就带一层 scrollView（装标题与按钮面板），别把它当成那一屏。 */
    private ScrollView findScroll(View view) {
        ScrollView[] best = new ScrollView[1];
        int[] bestCount = new int[1];
        collectScrolls(view, best, bestCount);
        return best[0];
    }

    private void collectScrolls(View view, ScrollView[] best, int[] bestCount) {
        if (view instanceof ScrollView) {
            int texts = countTexts(view);
            if (texts > bestCount[0]) { bestCount[0] = texts; best[0] = (ScrollView) view; }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectScrolls(group.getChildAt(i), best, bestCount);
        }
    }

    private int countTexts(View view) {
        int n = view instanceof TextView ? 1 : 0;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) n += countTexts(group.getChildAt(i));
        }
        return n;
    }

    private boolean isBold(View view) {
        return view instanceof TextView
                && ((TextView) view).getTypeface().isBold() == Typeface.DEFAULT_BOLD.isBold();
    }

    /** 普查表打到标准输出，gradle 的报告里留着，docs/ui-ergonomics.md 引的就是它。 */
    private void census(Screen screen, String phase) {
        StringBuilder out = new StringBuilder("结果页普查 ").append(phase)
                .append(" 内容高=").append(screen.box.getMeasuredHeight())
                .append(" 视口=").append(VIEWPORT).append('\n');
        for (int i = 0; i < screen.box.getChildCount(); i++) {
            View child = screen.box.getChildAt(i);
            out.append("  行").append(i).append(" y=").append(offsetInScroll(screen.scroll, child))
                    .append("..").append(offsetInScroll(screen.scroll, child) + child.getHeight())
                    .append(" 点得动=").append(child.isClickable())
                    .append(" ").append(visibility(screen.scroll, child)).append('\n');
        }
        System.out.println(out);
    }

    private static final class StubHost implements ApiWorkflow.Host {
        public DocxDocument document() { return null; }
        public void sync() { }
        public DocxDocument.ParagraphBlock currentParagraph() { return null; }
        public int[] selectedRange() { return null; }
        public Uri source() { return null; }
        public String fileName() { return "sample.docx"; }
        public void busy(boolean value) { }
        public void beforeRewrite(int paragraph) { }
        public void changed() { }
        public void navigate(int paragraph, int start, int end) { }
        public void pickLibrary() { }
    }
}
