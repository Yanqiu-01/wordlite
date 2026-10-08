package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * "这篇文档到底用了哪些字体、各自拿什么显示"。以前这件事只能靠眼睛发现：标题写着华文新魏，
 * 屏幕上其实是宋体，界面上一个字都不说。这份清单进 字体 对话框，也进设备侧导出的诊断表。
 */
public final class DocxFonts {
    /** 一个声明过的字体名，和它实际落到哪张脸上。 */
    public static final class Used {
        public String family = "";
        public String path = "";
        /** 非空 = 字库里没有本尊，换成了别的脸。 */
        public String note = "";
        public int runs;
    }

    private DocxFonts() { }

    /** 按用到的文字段数从多到少排；样式表里写了但没用在文字上的字体不算。 */
    public static ArrayList<Used> usedIn(DocxDocument document) {
        LinkedHashMap<String, Used> found = new LinkedHashMap<String, Used>();
        if (document != null) {
            collect(document.paragraphs, found);
            for (int i = 0; i < document.blocks.size(); i++)
                if (document.blocks.get(i) instanceof DocxDocument.TableBlock)
                    collectTable((DocxDocument.TableBlock) document.blocks.get(i), found);
        }
        ArrayList<Used> out = new ArrayList<Used>(found.values());
        for (int i = 1; i < out.size(); i++)
            for (int j = i; j > 0 && out.get(j).runs > out.get(j - 1).runs; j--) out.add(j - 1, out.remove(j));
        return out;
    }

    public static ArrayList<Used> usedInParagraphs(ArrayList<DocxDocument.ParagraphBlock> paragraphs) {
        LinkedHashMap<String, Used> found = new LinkedHashMap<String, Used>();
        collect(paragraphs, found);
        return new ArrayList<Used>(found.values());
    }

    /** 表格里的字也要字体：单元格自有一套段落。 */
    private static void collectTable(DocxDocument.TableBlock table, LinkedHashMap<String, Used> found) {
        for (int r = 0; r < table.rows.size(); r++) {
            ArrayList<DocxDocument.Cell> row = table.rows.get(r);
            if (row == null) continue;
            for (int c = 0; c < row.size(); c++) {
                DocxDocument.Cell cell = row.get(c);
                if (cell != null) collect(cell.paragraphs, found);
            }
        }
    }

    private static void collect(ArrayList<DocxDocument.ParagraphBlock> paragraphs, LinkedHashMap<String, Used> found) {
        if (paragraphs == null) return;
        for (int i = 0; i < paragraphs.size(); i++) {
            DocxDocument.ParagraphBlock p = paragraphs.get(i);
            if (p == null) continue;
            for (int r = 0; r < p.runs.size(); r++) count(p.runs.get(r).style, found);
            count(p.baseRunStyle, found);
        }
    }

    private static void count(DocxDocument.RunStyle style, LinkedHashMap<String, Used> found) {
        if (style == null) return;
        add(style.eastAsiaFontFamily, found);
        add(style.asciiFontFamily, found);
    }

    private static void add(String family, LinkedHashMap<String, Used> found) {
        if (family == null) return;
        String name = family.trim();
        if (name.isEmpty()) return;
        Used used = found.get(name);
        if (used == null) {
            used = new Used();
            used.family = name;
            used.path = DocxFontAssets.pathFor(name);
            used.note = DocxFontAssets.substitution(name);
            found.put(name, used);
        }
        used.runs++;
    }

    /** 一行话，进对话框那一行小字，也进诊断导出。 */
    public static String summary(ArrayList<Used> used) {
        if (used == null || used.isEmpty()) return "本文没有指定字体，全部用宋体显示";
        StringBuilder out = new StringBuilder("本文用到 ");
        int substitutes = 0;
        for (int i = 0; i < used.size(); i++) {
            Used one = used.get(i);
            if (!one.note.isEmpty()) substitutes++;
            if (i > 0) out.append("；");
            out.append(one.family).append("→").append(DocxFontAssets.label(one.path));
        }
        if (substitutes > 0)
            out.append("。其中 ").append(substitutes).append(" 种字库里没有，用了最接近的字代替");
        /* 字库里有这张脸，和这一屏画得出这张脸，是两件事。装载失败过就说出来——用户抱怨过
           "标题的华文新魏没了，也不知道留的到底是什么字"：那句话本来就该由应用先说，不该等他发现。 */
        String failed = FontManager.failureNote();
        if (!failed.isEmpty()) out.append("。").append(failed);
        return out.toString();
    }
}