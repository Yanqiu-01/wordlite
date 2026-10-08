package com.rikkahub.wordlite;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Word-style top ribbon. Document commands stay above the page; the bottom
 * editor switcher is owned by EditorActivity and only contains 正文/查重.
 *
 * 两条横向滚动行都带左右渐隐：这一排超出屏宽时，被屏幕边切掉的那半个按钮要一眼看得出是被切的，
 * 而不是让人以为"就只有这几个"。命令的先后按手机上真正按得频繁的顺序排：撤销重做在最左，
 * 对齐紧跟加粗一族（中文正文要两端对齐，那个状态得在眼前），字体字号颜色排在最后。
 */
public final class RibbonUI extends LinearLayout {
    public interface Listener { void command(String id); }
    public static final String[] TABS = {"文件", "开始", "插入", "引用", "审阅", "视图"};
    private static final int ACCENT = 0xFF2B579A;
    /** 选中态的底色拿 ribbon 自己的蓝压一档铺在按钮背后（Word 也是这么画当前状态的），不引入新色相。 */
    private static final int ACCENT_FILL_LIGHT = 0x1F2B579A, ACCENT_FILL_DARK = 0x552B579A;
    private static final int EDGE_FADE_DP = 20;

    private final Listener listener;
    private final LinearLayout tabs, commands;
    private final ScrollRow tabsRow, commandsRow;
    private final Map<String, TextView> tabButtons = new LinkedHashMap<String, TextView>();
    private final Map<String, TextView> commandButtons = new LinkedHashMap<String, TextView>();
    private final Typeface regular, medium;
    private final boolean dark;
    private String selected = "开始";
    private String context = "";
    private boolean collapsed;
    // 当前段落（或选区）的格式状态；stateAlignment 是 -1 表示没有在编辑的段落，谁都不亮。
    private boolean stateBold, stateItalic, stateUnderline;
    private int stateAlignment = -1;
    private static final String[] ALIGNMENT_COMMANDS = { "left", "center", "right", "justify" };
    public final int surface, foreground, subtle;

    public RibbonUI(Context context, String fileName, Listener listener) {
        super(context);
        this.listener = listener;
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
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
        tabsRow = new ScrollRow(context, tabs, "tab");
        addView(tabsRow, new LayoutParams(-1, dp(42)));

        View divider = new View(context);
        divider.setBackgroundColor(dark ? 0xFF3A3A3A : 0xFFD6D6D6);
        addView(divider, new LayoutParams(-1, 1));

        commands = new LinearLayout(context);
        commands.setGravity(Gravity.CENTER_VERTICAL);
        commands.setPadding(dp(4), 0, dp(4), 0);
        commandsRow = new ScrollRow(context, commands, "command");
        addView(commandsRow, new LayoutParams(-1, dp(46)));

        // 渐隐要看内容和视口的差，只有量完才说得准；换页签与换宽度都从这条路走。
        addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            tabsRow.refreshEdgeHints();
            commandsRow.refreshEdgeHints();
        });

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
        commandButtons.clear();
        switch (tab) {
            case "文件":
                cmd("open", "打开"); cmd("save", "保存"); cmd("save-as", "另存为");
                sep();
                cmd("pdf", "导出 PDF");
                sep();
                cmd("close", "关闭");
                break;
            case "开始":
                // 撤销/重做是手机上按得最多的两个动作，排在最左，静止时就看得见；
                // 对齐紧跟加粗一族，中文正文默认两端对齐，那个状态要在眼前才敢确认。
                cmd("undo", "撤销"); cmd("redo", "重做");
                sep();
                cmd("bold", "B"); cmd("italic", "I"); cmd("underline", "U");
                sep();
                cmd("left", "左"); cmd("center", "中"); cmd("right", "右"); cmd("justify", "两端");
                sep();
                cmd("spacing", "行距"); cmd("paragraph", "段落");
                sep();
                cmd("font", "字体"); cmd("size", "字号"); cmd("color", "颜色");
                break;
            case "插入":
                cmd("image", "图片"); cmd("table", "表格"); cmd("symbol", "符号"); cmd("comment", "批注");
                break;
            case "引用":
                cmd("outline", "导航"); cmd("fields", "域"); cmd("check", "格式检查");
                /* 报告中心放在引用页：查重结果本来就是引用侧要核对的东西，
                   而且这一页已有导航与格式检查，跳正文的动线在这里是连着的。 */
                cmd("report-center", "报告中心");
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
        // 换页签回到行首：上一排滚到哪儿不该带进这一排。
        commandsRow.scrollToStart();
        applyStates();
    }

    private void cmd(String id, String label) {
        TextView button = new TextView(getContext());
        button.setText(label);
        button.setTextSize(13);
        button.setTextColor(foreground);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(11), 0, dp(11), 0);
        button.setBackground(commandBackground());
        button.setTag("command-" + id);
        if ("bold".equals(id)) button.setTypeface(Typeface.create("serif", Typeface.BOLD));
        else if ("italic".equals(id)) button.setTypeface(Typeface.create("serif", Typeface.ITALIC));
        else if ("underline".equals(id)) {
            button.setTypeface(Typeface.create("serif", Typeface.NORMAL));
            button.setPaintFlags(button.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        } else button.setTypeface(medium);
        button.setOnClickListener(view -> listener.command(id));
        commands.addView(button, new LayoutParams(-2, dp(46)));
        commandButtons.put(id, button);
    }

    /** 平时是系统的水波纹按压反馈，当前段落已经带这个格式时换成一层 Word 蓝的底色。 */
    private StateListDrawable commandBackground() {
        GradientDrawable on = new GradientDrawable();
        on.setColor(dark ? ACCENT_FILL_DARK : ACCENT_FILL_LIGHT);
        on.setCornerRadius(dp(4));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{ android.R.attr.state_activated }, on);
        int ripple = selectable();
        if (ripple != 0) states.addState(new int[0], getContext().getDrawable(ripple));
        return states;
    }

    private void sep() {
        View view = new View(getContext());
        view.setBackgroundColor(0x22808080);
        LayoutParams params = new LayoutParams(dp(1), dp(24));
        params.setMargins(dp(6), 0, dp(6), 0);
        commands.addView(view, params);
    }

    /**
     * 当前段落（或选区）的格式状态，亮到对应的按钮上：一个看不见的状态比一个要多滑一下的按钮更糟。
     *
     * @param alignment 0 左 1 中 2 右 3 两端，-1 表示此刻没有在编辑的段落
     */
    public void setParagraphState(boolean bold, boolean italic, boolean underline, int alignment) {
        if (stateBold == bold && stateItalic == italic && stateUnderline == underline
                && stateAlignment == alignment) return;
        stateBold = bold; stateItalic = italic; stateUnderline = underline; stateAlignment = alignment;
        applyStates();
    }

    private void applyStates() {
        Map<String, Boolean> on = new HashMap<String, Boolean>();
        on.put("bold", stateBold); on.put("italic", stateItalic); on.put("underline", stateUnderline);
        for (String id : ALIGNMENT_COMMANDS) on.put(id, stateAlignment >= 0 && stateAlignment == indexOf(id));
        TextView reveal = null;
        for (Map.Entry<String, TextView> entry : commandButtons.entrySet()) {
            Boolean value = on.get(entry.getKey());
            boolean active = value != null && value.booleanValue();
            TextView button = entry.getValue();
            if (button.isActivated() != active) {
                button.setActivated(active);
                // 深色底下蓝压深一档已经看得出来是谁亮着，字色就不动了。
                button.setTextColor(active && !dark ? ACCENT : foreground);
            }
            if (active && reveal == null && outsideViewport(button)) reveal = button;
        }
        revealInViewport(reveal);
    }

    private int indexOf(String id) {
        for (int i = 0; i < ALIGNMENT_COMMANDS.length; i++) if (ALIGNMENT_COMMANDS[i].equals(id)) return i;
        return -1;
    }

    private boolean outsideViewport(View button) {
        int width = commandsRow.scroll.getWidth();
        if (width <= 0) return false;
        int x = commandsRow.scroll.getScrollX();
        return button.getLeft() - x < 0 || button.getRight() - x > width;
    }

    /** 亮起来的按钮如果被屏幕边切着，就把这一排挪到它看得见的位置；看不见的那个状态等于没有。 */
    private void revealInViewport(View button) {
        if (button == null) return;
        int range = Math.max(0, commands.getWidth() - commandsRow.scroll.getWidth());
        int target = Math.max(0, Math.min(button.getLeft() - dp(8), range));
        if (commandsRow.scroll.getScrollX() != target) {
            commandsRow.scroll.scrollTo(target, 0);
            commandsRow.refreshEdgeHints();
        }
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
        tabsRow.refreshEdgeHints();
    }

    public void setCollapsed(boolean value) {
        collapsed = value;
        commandsRow.setVisibility(value ? GONE : VISIBLE);
    }

    public String selectedTab() { return selected; }
    public boolean isCollapsed() { return collapsed; }

    private int selectable() {
        TypedValue value = new TypedValue();
        return getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true)
                ? value.resourceId : 0;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** 一条横向滚动行外加左右两道渐隐：只有这一排真放不下时才亮，滚到头就灭。 */
    private final class ScrollRow extends FrameLayout {
        private final HorizontalScrollView scroll;
        private final View edgeLeft, edgeRight;

        ScrollRow(Context context, LinearLayout child, String tag) {
            super(context);
            scroll = new HorizontalScrollView(context);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.addView(child);
            addView(scroll, new FrameLayout.LayoutParams(-1, -1));
            // tag 不许用 tab-/command- 开头：那两套前缀是页签与命令控件的，别人按前缀找控件。
            edgeLeft = edge("edge-" + tag + "-left", GradientDrawable.Orientation.LEFT_RIGHT);
            edgeRight = edge("edge-" + tag + "-right", GradientDrawable.Orientation.RIGHT_LEFT);
            addView(edgeLeft, new FrameLayout.LayoutParams(dp(EDGE_FADE_DP), -1, Gravity.START));
            addView(edgeRight, new FrameLayout.LayoutParams(dp(EDGE_FADE_DP), -1, Gravity.END));
            edgeLeft.setVisibility(GONE);
            edgeRight.setVisibility(GONE);
            scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> refreshEdgeHints());
        }

        /** 渐隐用 ribbon 自己的底色淡到透明，不引入新颜色。 */
        private View edge(String tag, GradientDrawable.Orientation orientation) {
            View view = new View(getContext());
            GradientDrawable shade = new GradientDrawable(orientation,
                    new int[]{ surface, 0x00000000 });
            view.setBackground(shade);
            view.setTag(tag);
            view.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            return view;
        }

        void refreshEdgeHints() {
            int range = scroll.getChildCount() == 0 ? 0
                    : Math.max(0, scroll.getChildAt(0).getWidth() - scroll.getWidth());
            int x = scroll.getScrollX();
            edgeLeft.setVisibility(x > 0 ? VISIBLE : GONE);
            edgeRight.setVisibility(x < range ? VISIBLE : GONE);
        }

        void scrollToStart() {
            scroll.scrollTo(0, 0);
            refreshEdgeHints();
        }
    }
}
