package com.rikkahub.wordlite;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Adds standard PDF outlines/URI/text annotations via a conservative incremental update. */
public final class PdfExtras {
    public static final class Bookmark {
        public int page;
        public float top;
        public String title = "";
    }
    public static final class Annotation {
        public int page;
        public float left, bottom, right, top;
        public String uri = "", text = "", author = "";
    }
    private static final Pattern OBJECT = Pattern.compile("(?m)^(\\d+)\\s+0\\s+obj\\b");
    private static final Pattern ROOT = Pattern.compile("/Root\\s+(\\d+)\\s+0\\s+R");
    private static final Pattern REF = Pattern.compile("(\\d+)\\s+0\\s+R");
    private PdfExtras() { }

    public static byte[] append(byte[] original, List<Bookmark> bookmarks, List<Annotation> annotations) throws IOException {
        if (bookmarks.isEmpty() && annotations.isEmpty()) return original;
        String source = new String(original, StandardCharsets.ISO_8859_1);
        if (!source.startsWith("%PDF-")) throw new IOException("PDF格式无效");
        Matcher rm = ROOT.matcher(source); int root = -1;
        while (rm.find()) root = Integer.parseInt(rm.group(1));
        if (root < 0) throw new IOException("PDF目录缺失");
        int startxref = source.lastIndexOf("startxref");
        if (startxref < 0) throw new IOException("PDF索引缺失");
        Matcher prevMatch = Pattern.compile("startxref\\s+(\\d+)").matcher(source.substring(startxref));
        if (!prevMatch.find()) throw new IOException("PDF索引无效");
        long prev = Long.parseLong(prevMatch.group(1));
        LinkedHashMap<Integer, String> objects = new LinkedHashMap<Integer, String>();
        if (prev < 0 || prev >= source.length() || !source.startsWith("xref", (int) prev))
            throw new IOException("PDF索引类型不支持");
        String[] lines = source.substring((int) prev).split("\\r?\\n");
        TreeMap<Integer, Integer> offsetToObject = new TreeMap<Integer, Integer>();
        int line = 1, max = 0;
        while (line < lines.length && !lines[line].startsWith("trailer")) {
            String header = lines[line++].trim(); if (header.isEmpty()) continue;
            String[] section = header.split("\\s+");
            if (section.length != 2) throw new IOException("PDF索引无效");
            int first = Integer.parseInt(section[0]), count = Integer.parseInt(section[1]);
            if (count < 0 || count > 1000000 || line > lines.length - count) throw new IOException("PDF索引越界");
            for (int i = 0; i < count; i++) {
                String[] entry = lines[line++].trim().split("\\s+");
                if (entry.length != 3 || !"n".equals(entry[2])) continue;
                if (!"00000".equals(entry[1])) throw new IOException("PDF对象版本不支持");
                int offset = Integer.parseInt(entry[0]);
                if (offset <= 0 || offset >= prev) throw new IOException("PDF对象越界");
                offsetToObject.put(offset, first + i); max = Math.max(max, first + i);
            }
        }
        ArrayList<Integer> objectOffsets = new ArrayList<Integer>(offsetToObject.keySet());
        for (int i = 0; i < objectOffsets.size(); i++) {
            int offset = objectOffsets.get(i), next = i + 1 < objectOffsets.size() ? objectOffsets.get(i + 1) : (int) prev;
            String body = source.substring(offset, next);
            Matcher object = OBJECT.matcher(body);
            if (!object.find() || object.start() != 0) throw new IOException("PDF对象标识无效");
            int number = Integer.parseInt(object.group(1));
            if (number != offsetToObject.get(offset)) throw new IOException("PDF对象索引不匹配");
            int end = body.lastIndexOf("endobj");
            if (end < object.end()) throw new IOException("PDF对象无效");
            objects.put(number, body.substring(object.end(), end).trim());
        }
        String catalog = objects.get(root);
        if (catalog == null) throw new IOException("PDF目录无效");
        ArrayList<Integer> pages = new ArrayList<Integer>();
        Matcher pm = Pattern.compile("/Pages\\s+(\\d+)\\s+0\\s+R").matcher(catalog);
        if (!pm.find()) throw new IOException("PDF页树缺失");
        collectPages(Integer.parseInt(pm.group(1)), objects, pages, 0);
        TreeMap<Integer, String> updates = new TreeMap<Integer, String>();
        Map<Integer, ArrayList<Integer>> pageAnnotations = new LinkedHashMap<Integer, ArrayList<Integer>>();
        for (Annotation annotation : annotations) {
            if (annotation.page < 0 || annotation.page >= pages.size()) continue;
            int id = ++max;
            String rect = "[" + number(annotation.left) + ' ' + number(annotation.bottom) + ' '
                    + number(annotation.right) + ' ' + number(annotation.top) + ']';
            String value = annotation.uri.isEmpty()
                    ? "<< /Type /Annot /Subtype /Text /Rect " + rect + " /Contents " + unicode(annotation.text)
                            + " /T " + unicode(annotation.author) + " /Name /Comment /F 4 >>"
                    : "<< /Type /Annot /Subtype /Link /Rect " + rect + " /Border [0 0 0] /A << /S /URI /URI "
                            + unicode(annotation.uri) + " >> >>";
            updates.put(id, value);
            ArrayList<Integer> ids = pageAnnotations.get(annotation.page);
            if (ids == null) { ids = new ArrayList<Integer>(); pageAnnotations.put(annotation.page, ids); }
            ids.add(id);
        }
        for (Map.Entry<Integer, ArrayList<Integer>> entry : pageAnnotations.entrySet()) {
            int pageId = pages.get(entry.getKey()); String page = objects.get(pageId);
            if (page.contains("/Annots")) throw new IOException("PDF已有批注，不能重复添加");
            StringBuilder refs = new StringBuilder(" /Annots [");
            for (Integer id : entry.getValue()) refs.append(id).append(" 0 R ");
            refs.append("]"); updates.put(pageId, extend(page, refs.toString()));
        }
        ArrayList<Bookmark> valid = new ArrayList<Bookmark>();
        for (Bookmark bookmark : bookmarks)
            if (bookmark.page >= 0 && bookmark.page < pages.size()) valid.add(bookmark);
        if (!valid.isEmpty()) {
            int outline = ++max, first = max + 1;
            max += valid.size();
            for (int i = 0; i < valid.size(); i++) {
                Bookmark bookmark = valid.get(i); int id = first + i;
                String value = "<< /Title " + unicode(bookmark.title) + " /Parent " + outline
                        + " 0 R /Dest [" + pages.get(bookmark.page) + " 0 R /XYZ null " + number(bookmark.top) + " null]";
                if (i > 0) value += " /Prev " + (id - 1) + " 0 R";
                if (i + 1 < valid.size()) value += " /Next " + (id + 1) + " 0 R";
                updates.put(id, value + " >>");
            }
            updates.put(outline, "<< /Type /Outlines /First " + first + " 0 R /Last " + max + " 0 R /Count " + valid.size() + " >>");
            if (catalog.contains("/Outlines")) throw new IOException("PDF已有书签");
            updates.put(root, extend(catalog, " /Outlines " + outline + " 0 R"));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(original.length + updates.size() * 300);
        out.write(original); out.write('\n');
        TreeMap<Integer, Integer> offsets = new TreeMap<Integer, Integer>();
        for (Map.Entry<Integer, String> entry : updates.entrySet()) {
            offsets.put(entry.getKey(), out.size());
            write(out, entry.getKey() + " 0 obj\n" + entry.getValue() + "\nendobj\n");
        }
        int xref = out.size(); write(out, "xref\n0 1\n0000000000 65535 f \n");
        for (Map.Entry<Integer, Integer> entry : offsets.entrySet()) {
            write(out, entry.getKey() + " 1\n" + String.format(java.util.Locale.US, "%010d 00000 n \n", entry.getValue()));
        }
        write(out, "trailer\n<< /Size " + (max + 1) + " /Root " + root + " 0 R /Prev " + prev
                + " >>\nstartxref\n" + xref + "\n%%EOF\n");
        return out.toByteArray();
    }

    private static void collectPages(int object, Map<Integer, String> objects, List<Integer> pages, int depth) throws IOException {
        if (depth > 64) throw new IOException("PDF页树过深");
        String value = objects.get(object); if (value == null) throw new IOException("PDF页树无效");
        if (Pattern.compile("/Type\\s*/Page(?!s)\\b").matcher(value).find()) { pages.add(object); return; }
        Matcher kids = Pattern.compile("/Kids\\s*\\[([^]]*)]", Pattern.DOTALL).matcher(value);
        if (!kids.find()) throw new IOException("PDF页面缺失");
        Matcher refs = REF.matcher(kids.group(1));
        while (refs.find()) collectPages(Integer.parseInt(refs.group(1)), objects, pages, depth + 1);
    }
    private static String extend(String dictionary, String entries) throws IOException {
        int end = dictionary.lastIndexOf(">>"); if (end < 0) throw new IOException("PDF字典无效");
        return dictionary.substring(0, end) + entries + dictionary.substring(end);
    }
    private static String unicode(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_16BE); StringBuilder hex = new StringBuilder("<FEFF");
        for (byte b : bytes) hex.append(String.format(java.util.Locale.US, "%02X", b & 255));
        return hex.append('>').toString();
    }
    private static String number(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) value = 0;
        return String.format(java.util.Locale.US, "%.3f", value);
    }
    private static void write(ByteArrayOutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
    }
}
