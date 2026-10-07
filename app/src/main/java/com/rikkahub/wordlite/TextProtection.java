package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Citation/field/formula/terminology islands survive rewriting byte-for-byte. */
public final class TextProtection {
    public static final class Island {
        public final int start, end;
        public final String original, token;
        Island(int start, int end, String original, String token) { this.start = start; this.end = end; this.original = original; this.token = token; }
    }
    public static final class Mask {
        public final String original, submitted;
        public final ArrayList<Island> islands;
        Mask(String original, String submitted, ArrayList<Island> islands) { this.original = original; this.submitted = submitted; this.islands = islands; }
        /** Plain segments between protected islands, ordered and never silently repaired. */
        public ArrayList<String> segments(String suggestion) {
            ArrayList<String> segments = new ArrayList<String>(); int cursor = 0;
            for (Island island : islands) {
                int at = suggestion.indexOf(island.token, cursor);
                if (at < 0 || suggestion.indexOf(island.token, at + island.token.length()) >= 0)
                    throw new IllegalArgumentException("引用或专业内容未保留");
                segments.add(suggestion.substring(cursor, at)); cursor = at + island.token.length();
            }
            segments.add(suggestion.substring(cursor));
            for (String segment : segments) if (segment.contains("⟦WL_")) throw new IllegalArgumentException("改写格式无效");
            return segments;
        }
        public String restore(String suggestion) {
            ArrayList<String> segments = segments(suggestion); StringBuilder result = new StringBuilder();
            for (int i = 0; i < islands.size(); i++) result.append(segments.get(i)).append(islands.get(i).original);
            return result.append(segments.get(segments.size() - 1)).toString();
        }
    }
    private static final Pattern CITATION = Pattern.compile("[\\[［][0-9０-９]+(?:\\s*[-–—,，、;；]\\s*[0-9０-９]+)*[\\]］]");
    private static final Pattern TECH = Pattern.compile("[A-Za-z]*[\\p{InGreek}\\p{InGreekExtended}][A-Za-z0-9]*|[A-Za-z][A-Za-z0-9]*(?:[₀-₉⁰¹²³⁴⁵⁶⁷⁸⁹][A-Za-z0-9]*)+|\\b(?:[A-Z]{2,}[A-Za-z0-9-]*|[A-Za-z]+[0-9]+[A-Za-z0-9-]*)\\b|[+-]?[0-9]+(?:\\.[0-9]+)?(?:\\s*(?:%|℃|°C|MPa|GPa|kPa|Pa|mm|cm|nm|μm|um|kg|mg|Hz|kHz|MHz|K|V|A))?|[∫∑∏√∞≈≠≤≥±×÷∂∇∈∉⊂⊃∪∩∀∃∴∵ℏℓℝℕℤℚℂ←-⇿]");
    private static final Pattern EQUATION = Pattern.compile("(?:[A-Za-z0-9α-ωΑ-Ω₀-₉]+\\s*(?:=|≈|≤|≥|≠|∝)\\s*[A-Za-z0-9α-ωΑ-Ω₀-₉.+*/()^−-]+)");
    private TextProtection() { }
    public static Mask mask(DocxDocument.ParagraphBlock paragraph, int start, int end, List<String> terms) {
        String original = paragraph.text.substring(start, end); ArrayList<int[]> ranges = new ArrayList<int[]>();
        for (DocxDocument.FieldCode field : paragraph.fields) protect(ranges, field.start, field.end, start, end);
        for (DocxDocument.Hyperlink link : paragraph.hyperlinks) protect(ranges, link.start, link.end, start, end);
        int at = 0;
        for (DocxDocument.Run run : paragraph.runs) {
            int next = at + run.text.length();
            if (run.style.superscript || run.style.subscript) protect(ranges, at, next, start, end); at = next;
        }
        matches(ranges, original, CITATION); matches(ranges, original, TECH); matches(ranges, original, EQUATION);
        for (String term : terms) {
            if (term == null || term.isEmpty()) continue;
            for (int i = 0; (i = original.indexOf(term, i)) >= 0; i += term.length()) ranges.add(new int[]{i, i + term.length()});
        }
        Collections.sort(ranges, (a, b) -> Integer.compare(a[0], b[0]));
        ArrayList<int[]> merged = new ArrayList<int[]>();
        for (int[] range : ranges) {
            if (range[1] <= range[0]) continue;
            if (!merged.isEmpty() && merged.get(merged.size() - 1)[1] >= range[0])
                merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], range[1]);
            else merged.add(range.clone());
        }
        String prefix = "⟦WL_" + UUID.randomUUID().toString().replace("-", "") + '_';
        ArrayList<Island> islands = new ArrayList<Island>(); StringBuilder submitted = new StringBuilder(); int cursor = 0;
        for (int[] range : merged) {
            String token = prefix + islands.size() + "⟧";
            islands.add(new Island(range[0], range[1], original.substring(range[0], range[1]), token));
            submitted.append(original, cursor, range[0]).append(token); cursor = range[1];
        }
        submitted.append(original.substring(cursor)); return new Mask(original, submitted.toString(), islands);
    }
    private static void protect(ArrayList<int[]> ranges, int lo, int hi, int start, int end) {
        if (lo < 0 || hi <= lo || hi <= start || lo >= end) return;
        if (lo < start || hi > end) throw new IllegalArgumentException("选区未包含完整引用");
        ranges.add(new int[]{lo - start, hi - start});
    }
    private static void matches(ArrayList<int[]> ranges, String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) if (matcher.end() > matcher.start()) ranges.add(new int[]{matcher.start(), matcher.end()});
    }
    public static boolean referenceParagraph(DocxDocument.ParagraphBlock paragraph) {
        String style = (paragraph.styleId + ' ' + paragraph.styleName).toLowerCase(java.util.Locale.ROOT);
        return style.contains("bibliography") || style.contains("reference") || style.contains("参考文献")
                || paragraph.text.trim().matches("^(\\[[0-9]+]|［[0-9]+］)\\s+.*");
    }
}
