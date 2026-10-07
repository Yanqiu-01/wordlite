package com.rikkahub.wordlite;

import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
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
@Config(manifest = Config.NONE, sdk = 28)
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
