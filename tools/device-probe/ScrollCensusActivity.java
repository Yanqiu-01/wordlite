package com.rikkahub.wordlite;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;

/**
 * "看不见"与"没有"的分辨器（探针包里的第二台，跑法见 tools/ui-visibility-oracle.ps1）。
 *
 * uiautomator 的一份 dump 只说得出屏幕那一片矩形里有什么：滚动容器折线以下的子节点它根本不报，
 * 被折线切到的那个报出来的还是切完的边界。这一台摆一个已知有 40 行的竖向 ScrollView，
 * 自己把 View 层的真值（每行的上下边界、视口高、内容高）写成表，主机那边再静止 dump 一次、
 * 滚到底 dump 一次，两边一比就知道 dump 漏了谁、切了谁——ribbon 表里"这排在视口外"那一栏靠的就是这条判据。
 *
 * -es hold 1 让它停在屏幕上不自己退，主机才好 dump；-es scroll 1 让它自己滚到底。
 */
public final class ScrollCensusActivity extends Activity {
    private static final String TAG = "WLuaudit";
    private static final int ROWS = 40;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        final boolean hold = getIntent() != null && getIntent().getStringExtra("hold") != null;
        float density = getResources().getDisplayMetrics().density;
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFFFFFFFF);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < ROWS; i++) {
            TextView row = new TextView(this);
            row.setText("WL行" + i);
            row.setTag("wl-row-" + i);
            row.setTextSize(18);
            row.setTextColor(0xFF101010);
            row.setGravity(android.view.Gravity.CENTER);
            box.addView(row, new LinearLayout.LayoutParams(-1, Math.round(120 * density)));
        }
        scroll.addView(box);
        setContentView(scroll);
        getWindow().getDecorView().post(() -> {
            if (getIntent() != null && getIntent().getStringExtra("scroll") != null) {
                // scrollTo 是立刻生效的；fullScroll 在布局还脏时会把请求存下来等下一次布局，
                // 那样量到的 scrollY 还是 0，等于没滚。
                scroll.scrollTo(0, box.getHeight());
                scroll.post(() -> census(scroll, box));
                Log.i(TAG, "CENSUS-DONE");
                if (!hold) finish();
                return;
            }
            census(scroll, box);
            Log.i(TAG, "CENSUS-DONE");
            if (!hold) finish();
        });
    }

    /** View 层的真值：每一行在内容坐标里的上下边界，以及视口有多高。dump 拿不到的是哪些，一比就知道。 */
    private void census(ScrollView scroll, LinearLayout box) {
        int viewport = scroll.getHeight(), content = box.getHeight(), y = scroll.getScrollY();
        ArrayList<String> lines = new ArrayList<String>();
        lines.add("tag\ttext\ttop\tbottom\tin_viewport\tviewport\tcontent\tscroll_y");
        for (int i = 0; i < box.getChildCount(); i++) {
            View row = box.getChildAt(i);
            lines.add(row.getTag() + "\t" + ((TextView) row).getText() + "\t" + row.getTop() + "\t"
                    + row.getBottom() + "\t" + (row.getBottom() > y && row.getTop() < y + viewport)
                    + "\t" + viewport + "\t" + content + "\t" + y);
        }
        File dir = getExternalFilesDir(null);
        File target = new File(dir, "scroll-census.tsv");
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(target), "UTF-8")) {
            for (String line : lines) { writer.write(line); writer.write("\n"); }
            writer.flush();
        } catch (Exception e) {
            Log.e(TAG, "写表失败 " + e, e);
        }
        Log.i(TAG, "CENSUS 视口=" + viewport + " 内容=" + content + " scrollY=" + y + " 表=" + target);
    }
}