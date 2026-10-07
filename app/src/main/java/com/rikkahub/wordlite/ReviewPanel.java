package com.rikkahub.wordlite;

import android.content.Context;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.LinkedHashSet;

/** Threaded comments and revisions in a standard review pane, no tutorial copy. */
public final class ReviewPanel extends LinearLayout {
    public interface Listener {
        void navigate(int paragraph, int offset);
        void decide(DocxDocument.Revision revision, boolean accept);
        void reply(DocxDocument.Comment comment);
        void resolve(DocxDocument.Comment comment);
        void author();
    }
    private final Spinner filter;
    private final LinearLayout list;
    private DocxDocument document;
    private Listener listener;
    private int ink;
    private String authorFilter = "";
    public ReviewPanel(Context context) {
        super(context); setOrientation(VERTICAL); setTag("review-panel");
        LinearLayout top = new LinearLayout(context);
        filter = new Spinner(context); top.addView(filter, new LayoutParams(0, -2, 1));
        Button author = action("作者", () -> { if (listener != null) listener.author(); }); top.addView(author);
        addView(top, new LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(context); list = new LinearLayout(context); list.setOrientation(VERTICAL); scroll.addView(list);
        addView(scroll, new LayoutParams(-1, 0, 1));
        filter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                authorFilter = position == 0 ? "" : String.valueOf(parent.getItemAtPosition(position)); refresh();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
    }
    public void bind(DocxDocument document, int surface, int ink, Listener listener) {
        this.document = document; this.listener = listener; this.ink = ink; setBackgroundColor(surface);
        LinkedHashSet<String> authors = new LinkedHashSet<String>(); authors.add("全部作者");
        for (DocxDocument.Comment comment : document.comments) authors.add(comment.author);
        for (DocxDocument.Revision revision : document.revisions) authors.add(revision.author);
        ArrayList<String> values = new ArrayList<String>(authors);
        filter.setAdapter(new ArrayAdapter<String>(getContext(), android.R.layout.simple_spinner_dropdown_item, values));
        int selection = values.indexOf(authorFilter); filter.setSelection(Math.max(0, selection)); refresh();
    }
    public String authorFilter() { return authorFilter; }
    private boolean visible(String author) { return authorFilter.isEmpty() || authorFilter.equals(author); }
    public void refresh() {
        list.removeAllViews(); if (document == null) return;
        for (DocxDocument.Revision revision : document.revisions) {
            if (!visible(revision.author)) continue;
            LinearLayout item = column();
            String type = revision.kind == DocxDocument.Revision.Kind.INSERT ? "插入" : revision.kind == DocxDocument.Revision.Kind.DELETE ? "删除" : "格式";
            TextView text = label(revision.author + " · " + revision.date + "\n" + type + " · " + revisionText(revision));
            text.setOnClickListener(view -> listener.navigate(revision.paragraphIndex, revision.start)); item.addView(text);
            LinearLayout buttons = new LinearLayout(getContext());
            buttons.addView(action("接受", () -> listener.decide(revision, true)));
            buttons.addView(action("拒绝", () -> listener.decide(revision, false)));
            item.addView(buttons); list.addView(item);
        }
        for (DocxDocument.Comment comment : document.comments) {
            if (comment.parentId >= 0) continue;
            boolean match = visible(comment.author);
            for (DocxDocument.Comment reply : document.comments) if (reply.parentId == comment.id) match |= visible(reply.author);
            if (!match) continue;
            LinearLayout item = column();
            TextView text = label(comment.author + " · " + comment.date + (comment.resolved ? " · 已解决" : "") + "\n" + comment.text);
            text.setOnClickListener(view -> listener.navigate(comment.paragraphIndex, comment.start)); item.addView(text);
            for (DocxDocument.Comment reply : document.comments) if (reply.parentId == comment.id) {
                TextView child = label(reply.author + " · " + reply.date + "\n" + reply.text);
                child.setPadding(dp(16), dp(6), dp(6), dp(6)); item.addView(child);
            }
            LinearLayout buttons = new LinearLayout(getContext());
            buttons.addView(action("回复", () -> listener.reply(comment)));
            buttons.addView(action(comment.resolved ? "重新打开" : "解决", () -> listener.resolve(comment)));
            item.addView(buttons); list.addView(item);
        }
    }
    private String revisionText(DocxDocument.Revision revision) {
        if (revision.kind == DocxDocument.Revision.Kind.DELETE) return revision.text;
        for (DocxDocument.ParagraphBlock p : document.paragraphs) if (p.index == revision.paragraphIndex)
            return p.text.substring(Math.max(0, Math.min(revision.start, p.text.length())), Math.max(0, Math.min(revision.end, p.text.length())));
        return "";
    }
    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(getContext()); layout.setOrientation(VERTICAL);
        layout.setPadding(dp(8), dp(8), dp(8), dp(8)); return layout;
    }
    private TextView label(String value) {
        TextView text = new TextView(getContext()); text.setText(value); text.setTextSize(12); text.setTextColor(ink);
        text.setTextIsSelectable(true); return text;
    }
    private Button action(String value, Runnable handler) {
        Button button = new Button(getContext()); button.setText(value); button.setTextSize(12); button.setAllCaps(false);
        button.setOnClickListener(view -> handler.run()); return button;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
