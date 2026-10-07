package com.rikkahub.wordlite;

import java.util.ArrayList;

/** One paragraph/selection review item; each request response is accepted independently. */
public final class RewriteTask {
    public final int paragraphIndex, start, end;
    public final TextProtection.Mask mask;
    public final ArrayList<String> suggestions = new ArrayList<String>();
    public int selectedSuggestion;
    public boolean accepted, rejected;
    public String error = "";
    public RewriteTask(DocxDocument.ParagraphBlock paragraph, int start, int end, ApiConfig config) {
        this(paragraph, start, end, config.terms);
    }
    /** Offline rewrites keep the same protected islands without any endpoint config. */
    public RewriteTask(DocxDocument.ParagraphBlock paragraph, int start, int end, java.util.List<String> terms) {
        if (TextProtection.referenceParagraph(paragraph)) throw new IllegalArgumentException("参考文献未改写");
        paragraphIndex = paragraph.index; this.start = start; this.end = end;
        mask = TextProtection.mask(paragraph, start, end, terms);
    }
    public boolean unchanged(DocxDocument document) {
        DocxDocument.ParagraphBlock p = TextSelection.find(document, paragraphIndex);
        return p != null && end <= p.text.length() && p.text.substring(start, end).equals(mask.original);
    }
    public String raw() { return suggestions.isEmpty() ? "" : suggestions.get(Math.min(selectedSuggestion, suggestions.size() - 1)); }
    public String rewritten() { return suggestions.isEmpty() ? "" : mask.restore(raw()); }
    public void suggestions(ArrayList<String> values) {
        ArrayList<String> valid = new ArrayList<String>();
        for (String value : values) {
            try { mask.restore(value); valid.add(value); } catch (IllegalArgumentException ignored) { }
        }
        if (valid.isEmpty()) throw new IllegalArgumentException("引用或专业内容未保留");
        suggestions.clear(); suggestions.addAll(valid); selectedSuggestion = 0;
    }
}
