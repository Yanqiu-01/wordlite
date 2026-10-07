package com.rikkahub.wordlite;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
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
public class ApiUITest {
    @Test public void settingsHasAllStandardConfigurationFieldsAndNoDefaultSecret() {
        RuntimeEnvironment.getApplication().getSharedPreferences("wordlite-secure-v1", Activity.MODE_PRIVATE).edit().clear().commit();
        Activity activity = Robolectric.buildActivity(ApiSettingsActivity.class).setup().get();
        View root = activity.findViewById(android.R.id.content);
        for (String name : new String[]{"API 地址", "API Key", "请求头（JSON）", "请求体模板", "响应字段映射（JSON）", "超时（秒）", "重试次数", "保留术语"})
            assertNotNull(root.findViewWithTag("setting-" + name));
        assertEquals("", ((EditText) root.findViewWithTag("setting-API Key")).getText().toString());
        assertEquals("", ((EditText) root.findViewWithTag("setting-API 地址")).getText().toString());
        assertFalse(root.findViewWithTag("setting-API Key").isSaveEnabled());
        assertNotNull(find(root, "保存")); assertNotNull(find(root, "测试连接"));
    }
    @Test public void reviewRibbonRoutesBothConfiguredServices() {
        ArrayList<String> commands = new ArrayList<>();
        RibbonUI ribbon = new RibbonUI(RuntimeEnvironment.getApplication(), "document.docx", commands::add);
        ribbon.select("审阅");
        assertNull(ribbon.findViewWithTag("command-check-api"));
        assertNull(ribbon.findViewWithTag("command-rewrite-api"));
        ribbon.findViewWithTag("command-track").performClick();
        assertEquals(Arrays.asList("track"), commands);
    }
    @Test public void diffMarksOnlyChangedTextAndDoesNotSplitSurrogatePairs() {
        TextDiff diff = new TextDiff("A😀旧段[2]", "A😀新段[2]");
        assertEquals(3, diff.start); assertEquals(4, diff.originalEnd); assertEquals(4, diff.changedEnd);
        TextDiff emoji = new TextDiff("A😀B", "A😁B"); assertEquals(1, emoji.start);
    }
    private TextView find(View view, String name) {
        if (view instanceof TextView && ((TextView) view).getText().toString().equals(name)) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { TextView result = find(group.getChildAt(i), name); if (result != null) return result; }
        }
        return null;
    }
}
