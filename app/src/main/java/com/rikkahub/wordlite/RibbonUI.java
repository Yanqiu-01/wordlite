package com.rikkahub.wordlite;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Word-style top ribbon. Document commands stay above the page; the bottom
 * editor switcher is owned by EditorActivity and only contains 正文/查重.
 */
public final class RibbonUI extends LinearLayout {
    public interface Listener { void command(String id); }
    public static final String[] TABS = {"文件", "开始", "插入", "引用", "审阅", "视图"};
    private static final int ACCENT = 0xFF2B579A;
    private final Listener listener;
    private final LinearLayout tabs, commands;
    private final Map<String, TextView> tabButtons = new LinkedHashMap<String, TextView>();
    private final Typeface regular, medium;
    private String selected = "开始";
    private String context = "";
    private boolean collapsed;
    public final int surface, foreground, subtle;

    public RibbonUI(Context context, String fileName, Listener listener) {
        super(context);
        this.listener = listener;
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        surface = dark ? 0xFF1E1E1E : 0xFFFFFFFF;
        foreground = dark ? 0xFFE8E8E8 : 0xFF1A1A1A;
        subtle = dark ? 0xFF9A9A9A : 0xFF6B7178;
        regular = Typeface.create("sans-serif", Typeface.NORMAL);
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        setOrientation(VERTICAL);
        setBackgroundColor(surface);

        tabs = new LinearLayout(context);
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        for (String tab : TABS) addTab(tab);
        addView(horizontal(tabs), new LayoutParams(-1, dp(42)));

        View divider = new View(context);
        divider.setBackgroundColor(dark ? 0xFF3A3A3A : 0xFFD6D6D6);
        addView(divider, new LayoutParams(-1, 1));

        commands = new LinearLayout(context);
        commands.setGravity(Gravity.CENTER_VERTICAL);
        commands.setPadding(dp(4), 0, dp(4), 0);
        addView(horizontal(commands), new LayoutParams(-1, dp(46)));

        select("开始");
        setElevation(dp(2));
    }

    private void addTab(String tab) {
        TextView button = new TextView(getContext());
        button.setText(tab);
        button.setTextSize(13);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackgroundResource(selectable());
        button.setTag("tab-" + tab);
        button.setOnClickListener(view -> select(tab));
        tabs.addView(button, new LayoutParams(-2, -1));
        tabButtons.put(tab, button);
    }

    public void select(String tab) {
        if (!tabButtons.containsKey(tab)) return;
        selected = tab;
        for (Map.Entry<String, TextView> entry : tabButtons.entrySet()) {
            boolean active = entry.getKey().equals(tab);
            entry.getValue().setTypeface(active ? medium : regular);
            entry.getValue().setTextColor(active ? ACCENT : subtle);
            entry.getValue().setPaintFlags(active ? Paint.UNDERLINE_TEXT_FLAG : 0);
        }
        commands.removeAllViews();
        switch (tab) {
            case "文件":
                cmd("open", "打开"); cmd("save", "保存"); cmd("save-as", "另存为");
                sep();
                cmd("pdf", "导出 PDF");
                sep();
                cmd("close", "关闭");
                break;
            case "开始":
                cmd("bold", "B"); cmd("italic", "I"); cmd("underline", "U");
                sep();
                cmd("font", "字体"); cmd("size", "字号"); cmd("color", "颜色");
                sep();
                cmd("left", "左"); cmd("center", "中"); cmd("right", "右"); cmd("justify", "两端");
                sep();
                cmd("spacing", "行距"); cmd("paragraph", "段落");
                sep();
                cmd("undo", "撤销"); cmd("redo", "重做");
                break;
            case "插入":
                cmd("image", "图片"); cmd("table", "表格"); cmd("symbol", "符号"); cmd("comment", "批注");
                break;
            case "引用":
                cmd("outline", "导航"); cmd("fields", "域"); cmd("check", "格式检查");
                break;
            case "审阅":
                cmd("track", "修订"); cmd("review", "审阅窗格");
                sep();
                cmd("accept", "接受"); cmd("reject", "拒绝"); cmd("previous", "上一条"); cmd("next", "下一条");
                break;
            case "视图":
                cmd("page-view", "页面"); cmd("reading", "阅读");
                sep();
                cmd("zoom-out", "缩小"); cmd("zoom-in", "放大"); cmd("zoom-reset", "100%");
                break;
            case "表格工具": cmd("table", "布局"); break;
            case "图片工具": cmd("image", "更换"); cmd("image-size", "大小"); break;
            case "公式工具": cmd("symbol", "符号"); break;
        }
    }

    private void cmd(String id, String label) {
        TextView button = new TextView(getContext());
        button.setText(label);
        button.setTextSize(13);
        button.setTextColor(foreground);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(11), 0, dp(11), 0);
        button.setBackgroundResource(selectable());
        button.setTag("command-" + id);
        if ("bold".equals(id)) button.setTypeface(Typeface.create("serif", Typeface.BOLD));
        else if ("italic".equals(id)) button.setTypeface(Typeface.create("serif", Typeface.ITALIC));
        else if ("underline".equals(id)) {
            button.setTypeface(Typeface.create("serif", Typeface.NORMAL));
            button.setPaintFlags(button.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        } else button.setTypeface(medium);
        button.setOnClickListener(view -> listener.command(id));
        commands.addView(button, new LayoutParams(-2, dp(46)));
    }

    private void sep() {
        View view = new View(getContext());
        view.setBackgroundColor(0x22808080);
        LayoutParams params = new LayoutParams(dp(1), dp(24));
        params.setMargins(dp(6), 0, dp(6), 0);
        commands.addView(view, params);
    }

    public void setContext(String value) {
        if (context.equals(value)) return;
        if (!context.isEmpty()) {
            TextView old = tabButtons.remove(context);
            tabs.removeView(old);
        }
        context = value == null ? "" : value;
        if (!context.isEmpty()) addTab(context);
        if (!tabButtons.containsKey(selected)) select("开始");
    }

    public void setCollapsed(boolean value) {
        collapsed = value;
        ((View) commands.getParent()).setVisibility(value ? GONE : VISIBLE);
    }

    public String selectedTab() { return selected; }
    public boolean isCollapsed() { return collapsed; }

    private HorizontalScrollView horizontal(View child) {
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(child);
        return scroll;
    }

    private int selectable() {
        TypedValue value = new TypedValue();
        return getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                ? value.resourceId : 0;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
