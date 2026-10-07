package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.List;

/** Minimal plain-text edits between protected islands preserve runs, anchors and revision history. */
public final class TextRewriter {
    public static final class Edit {
        public int start, end;
        public String replacement;
        Edit(int start, int end, String replacement) { this.start = start; this.end = end; this.replacement = replacement; }
    }
    private TextRewriter() { }
    public static ArrayList<Edit> plan(TextProtection.Mask mask, String suggestion) {
        ArrayList<String> segments = mask.segments(suggestion); ArrayList<Edit> edits = new ArrayList<Edit>(); int cursor = 0;
        for (int i = 0; i <= mask.islands.size(); i++) {
            int limit = i < mask.islands.size() ? mask.islands.get(i).start : mask.original.length();
            String old = mask.original.substring(cursor, limit), changed = segments.get(i);
            if (!old.equals(changed)) {
                int prefix = 0;
                while (prefix < old.length() && prefix < changed.length() && old.charAt(prefix) == changed.charAt(prefix)) prefix++;
                if (prefix > 0 && prefix < old.length() && Character.isLowSurrogate(old.charAt(prefix))) prefix--;
                int suffix = 0;
                while (suffix < old.length() - prefix && suffix < changed.length() - prefix
                        && old.charAt(old.length() - suffix - 1) == changed.charAt(changed.length() - suffix - 1)) suffix++;
                if (suffix > 0 && suffix < old.length() && Character.isLowSurrogate(old.charAt(old.length() - suffix))) suffix--;
                edits.add(new Edit(cursor + prefix, limit - suffix, changed.substring(prefix, changed.length() - suffix)));
            }
            if (i < mask.islands.size()) cursor = mask.islands.get(i).end;
        }
        return edits;
    }
    public static void apply(DocxDocument document, DocxDocument.ParagraphBlock paragraph, int start,
                             TextProtection.Mask mask, String suggestion) {
        if (start < 0 || start + mask.original.length() > paragraph.text.length()
                || !paragraph.text.substring(start, start + mask.original.length()).equals(mask.original)) throw new IllegalArgumentException("原文已更改");
        ArrayList<Edit> edits = plan(mask, suggestion);
        for (int i = edits.size() - 1; i >= 0; i--) {
            Edit edit = edits.get(i); int lo = start + edit.start, hi = start + edit.end;
            ArrayList<DocxDocument.Run> before = ReviewManager.runs(paragraph, 0, lo), after = ReviewManager.runs(paragraph, hi, paragraph.text.length());
            DocxDocument.RunStyle style = style(paragraph, lo);
            ReviewManager.recordEdit(document, paragraph, lo, hi - lo, edit.replacement);
            paragraph.runs.clear(); paragraph.runs.addAll(before);
            if (!edit.replacement.isEmpty()) paragraph.runs.add(new DocxDocument.Run(edit.replacement, style));
            paragraph.runs.addAll(after); paragraph.text = paragraph.text.substring(0, lo) + edit.replacement + paragraph.text.substring(hi);
        }
        paragraph.textEdited |= !edits.isEmpty();
    }
    private static DocxDocument.RunStyle style(DocxDocument.ParagraphBlock paragraph, int offset) {
        int cursor = 0; for (DocxDocument.Run run : paragraph.runs) {
            if (offset < cursor + run.text.length()) return run.style.copy(); cursor += run.text.length();
        }
        return paragraph.runs.isEmpty() ? paragraph.baseRunStyle.copy() : paragraph.runs.get(paragraph.runs.size() - 1).style.copy();
    }
}
