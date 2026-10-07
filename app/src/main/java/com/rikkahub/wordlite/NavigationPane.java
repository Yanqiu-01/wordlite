package com.rikkahub.wordlite;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Heading navigation kept outside the paper and document model. */
public final class NavigationPane extends ScrollView {
    public interface Listener { void navigate(int block); }
    private final LinearLayout items;
    public NavigationPane(Context context) {
        super(context);
        items = new LinearLayout(context); items.setOrientation(LinearLayout.VERTICAL);
        setFillViewport(true); addView(items); setTag("navigation-pane");
    }
    public void bind(DocxDocument document, int surface, int foreground, Listener listener) {
        setBackgroundColor(surface); items.removeAllViews();
        if (document == null) return;
        for (DocxDocument.ParagraphBlock paragraph : document.paragraphs) {
            if (!paragraph.isHeading || paragraph.text.trim().isEmpty()) continue;
            TextView row = new TextView(getContext());
            row.setText(paragraph.text); row.setTextColor(foreground); row.setTextSize(13);
            row.setMaxLines(2); row.setGravity(Gravity.CENTER_VERTICAL);
            int pad = Math.round(12 * getResources().getDisplayMetrics().density);
            row.setPadding(pad, pad, pad, pad);
            row.setOnClickListener(view -> listener.navigate(paragraph.index));
            items.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }
}
