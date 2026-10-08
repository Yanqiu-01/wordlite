package com.rikkahub.wordlite;

import android.app.Activity;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Locale;

/**
 * 手机上的 ribbon 尺寸探针（不进发布前的必跑清单：要真机）。
 *
 * 它只做一件事：把真的 RibbonUI 摆到真的屏幕上，按真的密度量一遍每条横向滚动行里每个控件的
 * 左右边界，写成一张表，好让"哪个按钮被屏幕边切掉了"这句话有出处，而不是谁的感觉。
 * 量两个档：手机本来的宽度，和一个 320dp 宽的小屏（同一块屏只改视口宽度，密度不动）。
 *
 * 它是独立包（com.rikkahub.wordlite.uiaudit），永远不覆盖用户机里的 com.rikkahub.wordlite。
 *
 * 跑法：pwsh tools/build-uiaudit-probe.ps1
 * 结果：外部目录 ribbon-ergonomics.tsv（脚本会 pull 回来），logcat -s WLuaudit 看进度，末尾一行 DONE。
 */
public final class RibbonErgonomicsActivity extends Activity {
    private static final String TAG = "WLuaudit";
    private static final String[] COLUMNS = { "profile", "part", "tab", "tag", "label", "left", "right",
            "width", "height", "at_rest", "at_end", "on_screen", "scroll_x", "kind", "selected",
            "viewport", "content", "range", "hint" };

    private final ArrayList<String> lines = new ArrayList<String>();

    private String extra(String key) {
        return getIntent() == null ? null : getIntent().getStringExtra(key);
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        final RibbonUI ribbon = new RibbonUI(this, "probe.docx", id -> { });
        setContentView(ribbon);
        getWindow().getDecorView().post(() -> measure(ribbon,
                extra("hold") != null, extra("tab") == null ? "开始" : extra("tab")));
    }

    private void measure(RibbonUI ribbon, boolean hold, String holdTab) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        // 320dp 是小屏那一档：同一块屏只改视口宽度，密度不动，dp 预算就是小屏的预算。
        int small = Math.round(320 * dm.density);
        int[] widths = dm.widthPixels > small
                ? new int[]{ dm.widthPixels, small } : new int[]{ dm.widthPixels };
        say("HDR\tdevice\t" + dm.widthPixels + "x" + dm.heightPixels + "\t"
                + dm.densityDpi + "dpi\t" + fmt(dm.density));
        for (int width : widths) {
            String profile = "w" + width + "px-" + Math.round(width / dm.density) + "dp";
            for (String tab : RibbonUI.TABS) walk(ribbon, profile, width, tab, null);
            // 再走一遍"这一段已经加粗并且两端对齐"的状态：量选中态亮在谁身上，
            // 以及那个亮起来却在小屏上被切掉的按钮，ribbon 有没有把这一排挪到它看得见。
            if (dm.widthPixels > small) {
                reportState(ribbon, false, false, false, -1);
                // 旧一棵树里还没有 setParagraphState（选中态是这一版才加的），探针要能量两棵树，
                // 所以这里走反射：没有这个方法就在档名上老实写明 nostate。
                walk(ribbon, profile + (supportsState(ribbon) ? "+state" : "+nostate"),
                        width, "开始", new int[]{ 3, 1 });
            }
            // 光标落在表格里时 ribbon 会多出一个上下文页签，那一页才是页签条最长的时候。
            ribbon.setContext("表格工具");
            walk(ribbon, profile, width, "表格工具", null);
            ribbon.setContext("");
        }
        write();
        if (hold) {
            // 停在屏幕上不自己退：主机要拿 uiautomator 再 dump 一次，和这张表对质。
            ribbon.select(holdTab);
            Log.i(TAG, "HOLD " + holdTab);
            return;
        }
        Log.i(TAG, "DONE " + lines.size() + " 行");
        finish();
    }

    /** 按指定宽度重新量一遍 ribbon，然后把每一条横向滚动行里的每个控件打一行。 */
    private void walk(RibbonUI ribbon, String profile, int width, String tab, int[] state) {
        ribbon.select(tab);
        ribbon.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        ribbon.layout(0, 0, width, ribbon.getMeasuredHeight());
        if (state != null && supportsState(ribbon)) {
            // 量完尺寸再报段落状态：选中态与"把亮起来的按钮挪进视口"都发生在这一刻。
            reportState(ribbon, state[1] == 1, false, false, state[0]);
            ribbon.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            ribbon.layout(0, 0, width, ribbon.getMeasuredHeight());
        }
        ArrayList<HorizontalScrollView> rows = new ArrayList<HorizontalScrollView>();
        collect(ribbon, rows);
        for (HorizontalScrollView row : rows) {
            LinearLayout inner = (LinearLayout) row.getChildAt(0);
            int viewport = row.getWidth();
            int content = inner.getWidth();
            int range = Math.max(0, content - viewport);
            int scrollX = row.getScrollX();
            String part = partName(inner);
            String hint = hint(row);
            Log.i(TAG, "ROW " + profile + " " + part + " " + tab + " 视口=" + viewport
                    + " 内容=" + content + " 可滚=" + range + " 此刻滚动=" + scrollX + " 提示=" + hint);
            for (int i = 0; i < inner.getChildCount(); i++) {
                View child = inner.getChildAt(i);
                String tag = String.valueOf(child.getTag());
                if (tag.equals("null")) tag = "";
                String label = child instanceof TextView ? ((TextView) child).getText().toString() : "";
                int left = child.getLeft(), right = child.getRight();
                add(profile, part, tab, tag, label, left, right, right - left, child.getHeight(),
                        state(left, right, viewport, 0), state(left, right, viewport, range),
                        state(left, right, viewport, scrollX), scrollX,
                        child instanceof TextView ? "cmd" : "sep", child.isActivated() ? "selected" : "plain",
                        viewport, content, range, hint);
            }
            row.scrollTo(0, 0);
        }
    }

    /** 滚动行旁边有没有"这一排还有东西"的提示（边缘渐隐），以及它此刻亮不亮。 */
    private String hint(HorizontalScrollView row) {
        View parent = (View) row.getParent();
        if (!(parent instanceof ViewGroup)) return "none";
        StringBuilder found = new StringBuilder();
        for (int i = 0; i < ((ViewGroup) parent).getChildCount(); i++) {
            View sibling = ((ViewGroup) parent).getChildAt(i);
            Object tag = sibling.getTag();
            if (tag instanceof String && ((String) tag).endsWith("-edge")) {
                if (found.length() > 0) found.append("+");
                found.append(((String) tag).substring(0, ((String) tag).length() - 5))
                        .append("=").append(sibling.getVisibility() == View.VISIBLE ? "shown" : "gone");
            }
        }
        return found.length() == 0 ? "none" : found.toString();
    }

    private String partName(LinearLayout inner) {
        for (int i = 0; i < inner.getChildCount(); i++) {
            Object tag = inner.getChildAt(i).getTag();
            if (tag instanceof String && ((String) tag).startsWith("tab-")) return "tab";
        }
        return "command";
    }

    /**
     * 这一个控件在给定滚动位置下与视口的关系。三个值都在说"看得见多少"，不是在说"存不存在"：
     * 这一行是 View 层量出来的，节点没建就根本不会有这一行，所以 fold 只可能是"这一排没滚到它"，
     * 永远不是"这个按钮没做"。dump 里看不见的东西才需要另证，见 tools/ui-visibility-oracle.ps1。
     *   visible 看得全；clipped 被屏幕边切一刀；fold 在这一排的折线以外（滚一下就到）。
     */
    private String state(int left, int right, int viewport, int scrollX) {
        int l = left - scrollX, r = right - scrollX;
        if (r <= 0 || l >= viewport) return "fold";
        if (l < 0 || r > viewport) return "clipped";
        return "visible";
    }

    private void collect(View view, ArrayList<HorizontalScrollView> out) {
        if (view instanceof HorizontalScrollView) out.add((HorizontalScrollView) view);
        if (view instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
                collect(((ViewGroup) view).getChildAt(i), out);
    }

    private void add(Object... cells) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) line.append('\t');
            line.append(cells[i] == null ? "" : String.valueOf(cells[i]));
        }
        lines.add(line.toString());
    }

    private String fmt(float value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private boolean supportsState(RibbonUI ribbon) {
        return stateMethod() != null;
    }

    private java.lang.reflect.Method stateMethod() {
        try {
            return RibbonUI.class.getMethod("setParagraphState",
                    boolean.class, boolean.class, boolean.class, int.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 量两棵不同的树：新方法在旧树里不存在，反射调不到就算没报。 */
    private void reportState(RibbonUI ribbon, boolean bold, boolean italic, boolean underline, int alignment) {
        try {
            java.lang.reflect.Method method = stateMethod();
            if (method != null) method.invoke(ribbon, bold, italic, underline, alignment);
        } catch (Exception e) {
            Log.i(TAG, "setParagraphState 调不到 " + e);
        }
    }

    /** 表写进探针自己的外部目录：logcat 会被终端的编码折腾，量出来的数不靠它传。 */
    private void write() {
        File dir = getExternalFilesDir(null);
        ArrayList<String> out = new ArrayList<String>();
        StringBuilder header = new StringBuilder();
        for (int i = 0; i < COLUMNS.length; i++) {
            if (i > 0) header.append('\t');
            header.append(COLUMNS[i]);
        }
        out.add(header.toString());
        out.addAll(lines);
        File target = new File(dir, "ribbon-ergonomics.tsv");
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(target), "UTF-8")) {
            for (String line : out) { writer.write(line); writer.write("\n"); }
            writer.flush();
        } catch (Exception e) {
            Log.e(TAG, "写表失败 " + e, e);
        }
        Log.i(TAG, "TSV " + target.getAbsolutePath() + " " + out.size() + " 行");
    }

    private void say(String line) {
        Log.i(TAG, line);
    }
}