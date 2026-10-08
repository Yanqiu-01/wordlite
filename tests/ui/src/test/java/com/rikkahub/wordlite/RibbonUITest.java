package com.rikkahub.wordlite;

import android.content.res.Configuration;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
// qualifiers 钉住用户机那一档：480dpi(密度 3.0)、360dp 宽 = 1080px。ribbon 的尺寸全按 dp 算，
// 默认密度 1.0 量出来的数与手机上的不是一回事。
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "zh-rCN-w360dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class RibbonUITest {
    @Test public void allSixTabsExistInWordOrder() {
        RibbonUI ribbon = new RibbonUI(RuntimeEnvironment.getApplication(), "sample.docx", id -> {});
        List<String> tabs = new ArrayList<>();
        collect(ribbon, tabs);
        assertEquals(Arrays.asList(RibbonUI.TABS), tabs);
        assertEquals("开始", ribbon.selectedTab());
    }
    @Test public void fileAndHomeCommandsCallActualListener() {
        List<String> actions = new ArrayList<>();
        RibbonUI ribbon = new RibbonUI(RuntimeEnvironment.getApplication(), "sample.docx", actions::add);
        ribbon.select("文件");
        ribbon.findViewWithTag("command-save").performClick();
        ribbon.select("开始");
        ribbon.findViewWithTag("command-bold").performClick();
        assertEquals(Arrays.asList("save", "bold"), actions);
        ribbon.select("文件");
        assertNotNull(ribbon.findViewWithTag("command-pdf"));
        assertNull(ribbon.findViewWithTag("command-api-settings"));
    }
    @Test public void rulerWidgetIsGoneAndTabsUseTextViews() {
        RibbonUI ribbon = new RibbonUI(RuntimeEnvironment.getApplication(), "sample.docx", id -> {});
        ribbon.select("视图");
        assertNull(ribbon.findViewWithTag("command-ruler"));
        View tab = ribbon.findViewWithTag("tab-开始");
        assertTrue(tab instanceof TextView);
        ribbon.select("开始");
        assertNull(ribbon.findViewWithTag("command-save"));
        assertEquals("开始", ribbon.selectedTab());
    }
    @Test public void contextualTabDoesNotPersistAfterSelectionChanges() {
        RibbonUI ribbon = new RibbonUI(RuntimeEnvironment.getApplication(), "sample.docx", id -> {});
        ribbon.setContext("表格工具"); ribbon.select("表格工具");
        assertNotNull(ribbon.findViewWithTag("command-table"));
        ribbon.setContext("");
        assertNull(ribbon.findViewWithTag("tab-表格工具"));
        assertEquals("开始", ribbon.selectedTab());
        ribbon.setCollapsed(true); assertTrue(ribbon.isCollapsed());
        ribbon.setCollapsed(false); assertFalse(ribbon.isCollapsed());
    }
    @Test public void navigationContainsOnlyHeadings() {
        DocxDocument document = new DocxDocument();
        DocxDocument.ParagraphBlock heading = new DocxDocument.ParagraphBlock();
        heading.text = "1 背景"; heading.index = 7; heading.isHeading = true;
        document.paragraphs.add(heading);
        DocxDocument.ParagraphBlock body = new DocxDocument.ParagraphBlock();
        body.text = "正文"; document.paragraphs.add(body);
        NavigationPane pane = new NavigationPane(RuntimeEnvironment.getApplication());
        int[] selected = {-1};
        pane.bind(document, 0xFFFFFFFF, 0xFF000000, block -> selected[0] = block);
        ViewGroup items = (ViewGroup) pane.getChildAt(0);
        assertEquals(1, items.getChildCount()); items.getChildAt(0).performClick();
        assertEquals(7, selected[0]);
    }
    @Test public void fontInitializationDoesNotPreloadWholeWhitelist() {
        int before = FontManager.loadedCount();
        DocxTextLayout.initialize(RuntimeEnvironment.getApplication());
        assertEquals(before, FontManager.loadedCount());
    }
    /* ---------------- 可达性：命令行与页签条摆得对不对 ----------------
       绝对边界以真机量到的那张表为准（docs/ui-ergonomics.md：用户机 1080x2400 @480dpi，
       改前 开始 那一排内容宽 1781px，视口只有 1080px，两端 被屏幕边切一刀，
       行距/段落/撤销/重做 整个在折线以外）。
       本机跑不出手机那套中文字宽（Robolectric 没带中文字体，一个汉字量出来接近 0），
       所以这里钉的是与字体度量无关的三件事：控件一个都不少、先后顺序、
       以及"排不下的那一排必须亮边缘提示，并且被切到的控件要露出一截"。
       要用的视口宽度按量到的内容宽度现算，不把手机上的像素数写死。 */

    @Test public void homeRowPutsUndoRedoFirstAndKeepsTheAlignmentGroupTogether() {
        RibbonUI ribbon = laidOut(1080, "开始");
        // 顺序本身就是这一条：14 个控件一个都不少（"没做这个按钮"与"在折线以外"是两回事，
        // 这里从 View 层数，不靠 uiautomator 的 dump），而且按手机上真正按得频繁的顺序排。
        assertEquals(Arrays.asList("undo", "redo", "bold", "italic", "underline",
                        "left", "center", "right", "justify", "spacing", "paragraph",
                        "font", "size", "color"), idsInRowOrder(ribbon));
        assertEquals("撤销是命令行最左的那个控件", dp(4), at(ribbon, "command-undo").getLeft());
        assertTrue("重做紧跟在撤销后面",
                at(ribbon, "command-redo").getLeft() >= at(ribbon, "command-undo").getRight());
        assertTrue("对齐那一组排在行距之前：屏幕边切下来时先切到行距，不是切到两端",
                at(ribbon, "command-justify").getRight() <= at(ribbon, "command-spacing").getLeft());
    }

    @Test public void aCommandRowWiderThanTheViewportShowsItsHintAndACutControl() {
        // 视口按量到的内容宽度现算：比内容窄一点，就是"这一排放不下"的那种屏。
        int content = commandRow(laidOut(1080, "开始")).getChildAt(0).getWidth();
        int viewport = content - dp(60);
        RibbonUI ribbon = laidOut(viewport, "开始");
        HorizontalScrollView row = commandRow(ribbon);
        assertEquals("静止时停在行首", 0, row.getScrollX());
        assertEquals("放不下就亮右边缘提示", View.VISIBLE, edge(ribbon, "edge-command-right").getVisibility());
        assertEquals("还没滚就不许亮左边缘提示", View.GONE, edge(ribbon, "edge-command-left").getVisibility());
        String straddler = null;
        for (String id : idsInRowOrder(ribbon)) {
            View cut = at(ribbon, "command-" + id);
            if (cut.getLeft() < viewport && cut.getRight() > viewport) straddler = id;
        }
        assertNotNull("屏幕边要正好切在某个控件中间：看得出一半，才知道后面还有", straddler);
        row.scrollTo(content, 0);
        assertEquals("滚到头右边缘就该灭", View.GONE, edge(ribbon, "edge-command-right").getVisibility());
        assertEquals("滚到头左边缘要亮：左边还有东西",
                View.VISIBLE, edge(ribbon, "edge-command-left").getVisibility());
    }

    @Test public void theEdgeHintTracksEachRowsOwnOverflowAndNothingElse() {
        // 提示只跟"这一排放不放进当前视口"挂钩，跟页签是谁无关：
        // 刚好放得下不许亮，差 1px 就得亮。真机上六个页签里只有 开始 会溢出（552~987 vs 1781，
        // 视口 1080），那张表在 docs/ui-ergonomics.md，本机不复现手机的中文字宽，所以不写死 1080。
        for (String tab : RibbonUI.TABS) {
            int content = commandRow(laidOut(1080, tab)).getChildAt(0).getWidth();
            assertEquals(tab + " 刚好放得下时不许亮提示",
                    View.GONE, edge(laidOut(content, tab), "edge-command-right").getVisibility());
            assertEquals(tab + " 差 1px 就要亮提示",
                    View.VISIBLE, edge(laidOut(content - 1, tab), "edge-command-right").getVisibility());
        }
    }

    @Test public void theHomeCommandRowIsTheWidestOfTheSixTabs() {
        // 需要横滑的就是 开始 这一排：它比其余任何一排都长，改完之后仍然只有它长到放不下。
        int home = commandRow(laidOut(1080, "开始")).getChildAt(0).getWidth();
        for (String tab : new String[]{"文件", "插入", "引用", "审阅", "视图"}) {
            int content = commandRow(laidOut(1080, tab)).getChildAt(0).getWidth();
            assertTrue(tab + " 那一排比 开始 短（" + content + " < " + home + "）", content < home);
        }
    }

    @Test public void paragraphStateLightsTheMatchingCommands() {
        RibbonUI ribbon = laidOut(1080, "开始");
        ribbon.setParagraphState(true, false, false, 3);
        assertTrue("这一段是加粗的，B 就该亮着", at(ribbon, "command-bold").isActivated());
        assertTrue("这一段是两端对齐的，两端就该亮着", at(ribbon, "command-justify").isActivated());
        assertFalse("左对齐没开着就不许亮", at(ribbon, "command-left").isActivated());
        assertFalse("I 没开着就不许亮", at(ribbon, "command-italic").isActivated());
        ribbon.setParagraphState(false, false, false, 0);
        assertFalse("换段落之后旧的选中态要灭", at(ribbon, "command-bold").isActivated());
        assertTrue("左对齐亮了", at(ribbon, "command-left").isActivated());
        ribbon.setParagraphState(false, false, false, -1);
        assertFalse("没有在编辑段落时一个都不许亮", at(ribbon, "command-left").isActivated());
    }

    @Test public void paragraphStateRevealsTheActiveCommandWhenItIsCutOff() {
        // 视口切在 两端 中间的那种屏：亮着却看不见的状态等于没有，所以这一排要自己挪过去。
        RibbonUI probe = laidOut(1080, "开始");
        int viewport = at(probe, "command-justify").getLeft() + dp(20);
        RibbonUI ribbon = laidOut(viewport, "开始");
        assertTrue("这一档宽度下 两端 本来是被切的",
                at(ribbon, "command-justify").getRight() > viewport);
        ribbon.setParagraphState(false, false, false, 3);
        int scrolled = commandRow(ribbon).getScrollX();
        assertTrue("报了段落状态之后，这一排自己挪到 两端 那儿", scrolled > 0);
        assertTrue("挪完就看得全了", at(ribbon, "command-justify").getRight() - scrolled <= viewport);
        assertEquals("挪过之后左边亮着提示：左边还有东西",
                View.VISIBLE, edge(ribbon, "edge-command-left").getVisibility());
        // 放得下的屏不许为了状态乱挪这一排。
        RibbonUI wide = laidOut(commandRow(probe).getChildAt(0).getWidth(), "开始");
        wide.setParagraphState(false, false, false, 3);
        assertEquals("静止时就看得见，就别乱动", 0, commandRow(wide).getScrollX());
    }

    @Test public void tabStripShowsItsEdgeHintOnlyWhenItOverflows() {
        RibbonUI plain = laidOut(1080, "开始");
        assertEquals("六个页签放得下，不该亮提示", View.GONE, edge(plain, "edge-tab-right").getVisibility());
        // 光标落在表格里时多一个上下文页签，那一页才是页签条最长的时候（真机上就是它溢出）。
        RibbonUI contextual = laidOut(1080, "表格工具", "表格工具");
        int withContext = tabRow(contextual).getChildAt(0).getWidth();
        assertTrue("上下文页签让页签条更长（" + tabRow(plain).getChildAt(0).getWidth() + " -> " + withContext + "）",
                withContext > tabRow(plain).getChildAt(0).getWidth());
        RibbonUI narrow = laidOut(withContext - dp(30), "表格工具", "表格工具");
        assertEquals("页签条放不下就要亮提示", View.VISIBLE, edge(narrow, "edge-tab-right").getVisibility());
    }
    /** 按指定宽度量好再摆出来，量到的边界才是稳定可对质的数。 */
    private RibbonUI laidOut(int widthPx, String tab) {
        return laidOut(widthPx, tab, "");
    }

    private RibbonUI laidOut(int widthPx, String tab, String context) {
        RibbonUI ribbon = new RibbonUI(RuntimeEnvironment.getApplication(), "论文.docx", id -> { });
        ribbon.setContext(context);
        ribbon.select(tab);
        ribbon.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        ribbon.layout(0, 0, widthPx, ribbon.getMeasuredHeight());
        return ribbon;
    }

    private TextView at(RibbonUI ribbon, String tag) {
        View view = ribbon.findViewWithTag(tag);
        assertNotNull("找不到 " + tag, view);
        assertTrue(tag + " 不是 TextView", view instanceof TextView);
        return (TextView) view;
    }

    private View edge(RibbonUI ribbon, String tag) {
        View view = ribbon.findViewWithTag(tag);
        assertNotNull("滚动行旁边缺了渐隐 " + tag, view);
        return view;
    }

    /** 命令行（不是页签条）：哪个横向滚动行里有 command- 开头的控件，哪个就是它。 */
    private HorizontalScrollView commandRow(RibbonUI ribbon) {
        ArrayList<HorizontalScrollView> rows = new ArrayList<>();
        collectScrollers(ribbon, rows);
        for (HorizontalScrollView row : rows)
            if (row.getChildAt(0) instanceof LinearLayout
                    && String.valueOf(((View) ((LinearLayout) row.getChildAt(0)).getChildAt(0)).getTag())
                    .startsWith("command-")) return row;
        throw new AssertionError("ribbon 里没有命令行");
    }

    /** 页签条那条横向滚动行。 */
    private HorizontalScrollView tabRow(RibbonUI ribbon) {
        ArrayList<HorizontalScrollView> rows = new ArrayList<>();
        collectScrollers(ribbon, rows);
        for (HorizontalScrollView row : rows)
            if (row.getChildAt(0) instanceof LinearLayout
                    && String.valueOf(((LinearLayout) row.getChildAt(0)).getChildAt(0).getTag())
                    .startsWith("tab-")) return row;
        throw new AssertionError("ribbon 里没有页签条");
    }

    private void collectScrollers(View view, List<HorizontalScrollView> out) {
        if (view instanceof HorizontalScrollView) out.add((HorizontalScrollView) view);
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
                collectScrollers(((ViewGroup) view).getChildAt(i), out);
    }

    /** 命令行里控件的先后顺序（去掉分隔线），只留 command id。 */
    private List<String> idsInRowOrder(RibbonUI ribbon) {
        LinearLayout row = (LinearLayout) commandRow(ribbon).getChildAt(0);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < row.getChildCount(); i++) {
            Object tag = row.getChildAt(i).getTag();
            if (tag instanceof String && ((String) tag).startsWith("command-"))
                ids.add(((String) tag).substring("command-".length()));
        }
        return ids;
    }

    private int dp(int value) {
        return Math.round(value * RuntimeEnvironment.getApplication().getResources()
                .getDisplayMetrics().density);
    }

    private void collect(View view, List<String> labels) {
        Object tag = view.getTag();
        if (tag instanceof String && ((String) tag).startsWith("tab-"))
            labels.add(((TextView) view).getText().toString());
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), labels);
        }
    }
}
