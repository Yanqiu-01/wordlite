package com.rikkahub.wordlite;

import java.io.*;
import java.util.*;

public final class Regression {
    private static int count;
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        count++;
        System.out.println("PASS " + message);
    }
    private static PageBreaker.Item item(int id, int n, float height) {
        float[] a = new float[n]; Arrays.fill(a, height); return new PageBreaker.Item(id, a);
    }
    private static List<PageBreaker.Page> pages(float height, PageBreaker.Item... items) {
        return PageBreaker.paginate(Arrays.asList(items), height);
    }
    public static void main(String[] args) throws Exception {
        PageBreaker.Item longParagraph = item(1, 23, 10);
        List<PageBreaker.Page> result = pages(50, longParagraph);
        check(result.size() == 5, "23-line paragraph on empty page splits over five pages");
        verifyCoverage(Arrays.asList(longParagraph), result, 50);
        check(result.get(4).fragments.get(0).endLine == 23, "long-paragraph tail is not lost");

        PageBreaker.Item pre = item(0, 1, 40), p = item(1, 7, 10);
        result = pages(100, pre, p);
        check(result.get(0).fragments.get(1).endLine == 5 && result.get(1).fragments.get(0).startLine == 5,
                "widow control changes 6+1 into 5+2");
        pre = item(0, 1, 90); p = item(1, 5, 10);
        result = pages(100, pre, p);
        check(result.get(0).fragments.size() == 1 && result.get(1).fragments.get(0).endLine == 5,
                "orphan control moves paragraph instead of a one-line first fragment");
        p.widowControl = false;
        result = pages(100, pre, p);
        check(result.get(0).fragments.get(1).endLine == 1, "widowControl=false permits one-line fragment");
        p.keepLines = true;
        result = pages(100, pre, p);
        check(result.get(0).fragments.size() == 1, "keepLines moves a paragraph that fits an empty page");
        p = item(2, 23, 10); p.keepLines = true;
        result = pages(50, p);
        check(result.size() == 5, "keepLines relaxes for a paragraph taller than a page");
        verifyCoverage(Arrays.asList(p), result, 50);

        PageBreaker.Item mixed = new PageBreaker.Item(3, 10, 10, 40, 10, 10);
        mixed.widowControl = false;
        result = pages(30, mixed);
        check(result.size() == 3 && result.get(1).overflow && result.get(1).fragments.get(0).height == 40,
                "individual line heights used; oversize line reports overflow without looping");
        PageBreaker.Item a = item(0, 1, 10), b = item(1, 1, 10);
        a.after = 20; b.before = 30;
        result = pages(100, a, b);
        check(result.get(0).fragments.get(1).top == 40, "adjacent paragraph spacing takes max, not sum");
        b.pageBreakBefore = true;
        result = pages(100, a, b);
        check(result.size() == 2 && result.get(1).fragments.get(0).top == 0, "explicit page break starts at the new page top");
        a.pageBreakBefore = true;
        check(pages(100, a).size() == 1, "pageBreakBefore on first paragraph does not create empty page");
        a.before = 30;
        result = pages(100, a);
        check(result.get(0).fragments.get(0).top == 0,
                "pageBreakBefore suppresses paragraph-before spacing at the new page top");
        // 分节符开出来的那一页同理：节首那一段的段前距不画。真值在目录页——Word 给"目 录"标题上方
        // 只留 2.7px，而我们按 beforeLines=100 计了 24.0px（docs/layout-parity-target.md 第 15 节）。
        PageBreaker.Item secHead = item(0, 1, 10), secBody = item(1, 1, 10);
        secHead.before = 24; secHead.sectionStart = true;
        result = pages(100, secHead, secBody);
        check(result.get(0).fragments.get(0).top == 0 && result.get(0).fragments.get(1).top == 10,
                "分节符开的那一页不画节首段的段前距：段顶落在 0，下一段紧跟在 10");
        PageBreaker.Item firstPlain = item(0, 1, 10), secondPlain = item(1, 1, 10);
        firstPlain.before = 24;
        result = pages(100, firstPlain, secondPlain);
        check(result.get(0).fragments.get(0).top == 24,
                "文档第一节的第一页不是分页符开出来的：段前距照旧画，这一条改动没碰它");
        pre = item(0, 1, 70);
        a = item(1, 1, 10); b = item(2, 1, 10); p = item(3, 3, 10);
        a.keepNext = b.keepNext = true;
        result = pages(100, pre, a, b, p);
        check(result.size() == 2 && result.get(0).fragments.size() == 1,
                "keepNext chain keeps headings with following body lines");
        check(pages(100).size() == 1, "empty document has one page");

        Random random = new Random(20261002);
        for (int trial = 0; trial < 500; trial++) {
            List<PageBreaker.Item> items = new ArrayList<PageBreaker.Item>();
            for (int i = 0; i < 1 + random.nextInt(20); i++) {
                float[] lines = new float[1 + random.nextInt(70)];
                for (int k = 0; k < lines.length; k++) lines[k] = 4 + random.nextInt(25);
                PageBreaker.Item it = new PageBreaker.Item(i, lines);
                it.before = random.nextInt(5); it.after = random.nextInt(5);
                it.keepNext = random.nextBoolean(); it.keepLines = random.nextBoolean();
                it.widowControl = random.nextBoolean(); it.pageBreakBefore = random.nextInt(5) == 0;
                items.add(it);
            }
            verifyCoverage(items, PageBreaker.paginate(items, 150), 150);
        }
        check(true, "500 randomized documents: ordered line coverage, no duplicates, no missing lines, no page overflow");

        DocxDocument.SectionSettings section = new DocxDocument.SectionSettings();
        section.marginLeftTwips = section.marginTopTwips = 0;
        PageGeometry geometry = new PageGeometry(section);
        check(geometry.left == 0 && geometry.top == 0, "explicit zero margins remain zero");
        check(Math.abs(PageGeometry.points(12) - 16) < 0.001, "12pt = 16 document pixels at 96DPI");
        check(Math.abs(geometry.width * geometry.fitScale(390) - 390) < 0.001, "fit-width scales all document coordinates uniformly");
        section.gutterTwips = 360;
        PageGeometry gutterGeometry = new PageGeometry(section);
        check(Math.abs(gutterGeometry.left - 24f) < 0.001 && Math.abs(gutterGeometry.contentWidth - (geometry.contentWidth - 24f)) < 0.001,
                "Word gutter enlarges the effective inside margin without changing the page width");
        section.gutterTwips = 0;
        section.pageWidthTwips = 11906;
        section.pageHeightTwips = 16838;
        section.landscape = true;
        PageGeometry landscapeGeometry = new PageGeometry(section);
        check(landscapeGeometry.width > landscapeGeometry.height && landscapeGeometry.width == 16838f / 15f,
                "landscape orientation normalizes portrait-encoded pgSz dimensions");
        section.landscape = false;
        check(EditOffsets.adjust(8, 2, 3, 1, false) == 6, "anchor after replacement shifts by text delta");
        check(EditOffsets.adjust(2, 2, 0, 2, false) == 2 && EditOffsets.adjust(5, 2, 0, 2, true) == 7,
                "inserted text at a range start expands anchored range");

        File fixture = new File(args[0]);
        DocxDocument d = DocxParser.parse(new FileInputStream(fixture), "regression.docx");
        DocxDocument.ParagraphBlock first = d.paragraphs.get(0);
        check(first.text.equals("普通红色粗体斜体尾"), "production parser reads mixed-format paragraph");
        check(first.runs.size() == 5 && first.runs.get(1).style.color == 0xFFFF0000 && first.runs.get(2).style.bold,
                "production parser reads run styles");
        check(first.format.keepLinesSet && !first.format.keepLines && first.format.widowControl,
                "explicit false overrides style inheritance; explicit widow=true read");
        check(first.baseRunStyle.eastAsiaFontFamily.equals("宋体")
                        && first.baseRunStyle.asciiFontFamily.equals("Arial")
                        && first.baseRunStyle.fontSizeHalfPoints == 24,
                "paragraph inherits distinct East Asian/Latin fonts and the real Normal-style size");
        check(first.format.lineSpacingTwips == 360 && "auto".equals(first.format.lineRule),
                "paragraph reads 1.5-line auto spacing without converting twips prematurely");
        DocxDocument.ParagraphBlock fields = d.paragraphs.get(1);
        check(fields.fields.size() == 3, "duplicate PAGE fields and fldSimple are not deduplicated");
        check(fields.fields.get(0).start == 8 && fields.fields.get(1).start == 14 && fields.fields.get(2).start == 24,
                "field offsets distinguish cached results from equal ordinary text");
        check(fields.fields.get(0).instruction.equals("PAGE"), "split instrText pieces are concatenated");
        DocxDocument.Comment comment = d.comments.get(0);
        check(comment.start == 0 && comment.end == 5, "comment anchor offsets include tabs and soft breaks (A\\tB\\nC = 5 chars)");
        check(comment.date.equals("2024-03-04T05:06:07Z"), "original comment timestamp read");
        check(d.imageCount == 1 && d.tableCount == 1, "production parser reads image and table");

        File output = new File(args[1]);
        for (int pass = 0; pass < 2; pass++) {
            File target = new File(output, "roundtrip-" + pass + ".docx");
            DocxWriter.write(new FileInputStream(fixture), new FileOutputStream(target), d);
            d = DocxParser.parse(new FileInputStream(target), "regression.docx");
            first = d.paragraphs.get(0); fields = d.paragraphs.get(1); comment = d.comments.get(0);
            check(first.runs.size() == 5 && first.runs.get(1).style.color == 0xFFFF0000
                    && first.runs.get(2).style.bold && first.runs.get(3).style.italic && first.runs.get(3).style.strike,
                    "roundtrip " + pass + ": every run boundary and local format survives");
            check(fields.text.equals("literal 1 / p=1 / title=T") && fields.fields.size() == 3
                    && fields.fields.get(0).start == 8 && fields.fields.get(1).start == 14 && fields.fields.get(2).start == 24,
                    "roundtrip " + pass + ": fields preserve locations without duplicate results");
            check(comment.start == 0 && comment.end == 5 && comment.date.equals("2024-03-04T05:06:07Z"),
                    "roundtrip " + pass + ": comment range and original timestamp survive");
            check(first.format.keepLinesSet && !first.format.keepLines && first.format.widowControl
                    && first.format.firstLineIndentTwips == 0,
                    "roundtrip " + pass + ": false pagination flags and zero-indent override survive");
            check(d.imageCount == 1 && d.tableCount == 1, "roundtrip " + pass + ": image and simple table remain readable");
            fixture = target;
        }
        // Edit one run only, then exercise the actual writer again.
        d.paragraphs.get(0).runs.get(1).style.color = 0xFF0000FF;
        File edited = new File(output, "edited.docx");
        DocxWriter.write(new FileInputStream(fixture), new FileOutputStream(edited), d);
        DocxDocument read = DocxParser.parse(new FileInputStream(edited), "edited.docx");
        check(read.paragraphs.get(0).runs.get(1).style.color == 0xFF0000FF && read.paragraphs.get(0).runs.get(2).style.bold,
                "local color edit does not flatten neighboring bold formatting");

        // --- Field display substitution keeps offsets mappable both ways ---
        DocxDocument.ParagraphBlock fieldParagraph = null;
        for (DocxDocument.ParagraphBlock q : read.paragraphs) if (!q.fields.isEmpty()) { fieldParagraph = q; break; }
        check(fieldParagraph != null, "field paragraph available for display substitution tests");
        DocxFieldEngine.DisplayMap map = DocxFieldEngine.displayTextWithMap(read, fieldParagraph, 3, 12);
        check(map.changed && map.text.equals("literal 3 / p=3 / title=" + read.title),
                "PAGE/TITLE substituted into display text with per-character source mapping");
        check(map.sourceIndex.length == map.text.length() + 1
                        && map.sourceIndex[0] == 0 && map.sourceIndex[map.sourceIndex.length - 1] == fieldParagraph.text.length(),
                "display map endpoints match paragraph text length");
        check(map.sourceIndex[8] == 8 && map.sourceIndex[9] == 9 && map.sourceIndex[24] == 24,
                "display offsets map back to their source offsets; result characters land on the field start");

        // --- Table structure editing survives a full roundtrip ---
        DocxDocument.TableBlock table = null;
        for (DocxDocument.Block block : read.blocks) if (block instanceof DocxDocument.TableBlock) { table = (DocxDocument.TableBlock) block; break; }
        check(table != null && table.rows.size() > 0, "fixture table located");
        DocxDocument.ParagraphBlock cellParagraph = table.rows.get(0).get(0).paragraphs.get(0);
        check(TableOps.locate(read, cellParagraph.index) != null, "TableOps locates a cell paragraph");
        int baseRows = table.rows.size();
        check(TableOps.insertRow(read, table, 0, false) && table.rows.size() == baseRows + 1, "insert row at the end of the row list");
        check(TableOps.insertRow(read, table, 0, true) && table.rows.size() == baseRows + 2, "insert row before the first row");
        check(TableOps.deleteRow(read, table, 0) && TableOps.deleteRow(read, table, 0), "two inserted rows removed");
        int baseColumns = table.columns;
        check(TableOps.insertColumn(read, table, 0, false) && table.columns == baseColumns + 1, "insert column after the first");
        check(TableOps.deleteColumn(read, table, 1) && table.columns == baseColumns, "inserted column removed");
        File tableFile = new File(output, "table-edit.docx");
        DocxWriter.write(new FileInputStream(fixture), new FileOutputStream(tableFile), read);
        DocxDocument tableRead = DocxParser.parse(new FileInputStream(tableFile), "table-edit.docx");
        DocxDocument.TableBlock tableBack = null;
        for (DocxDocument.Block block : tableRead.blocks) if (block instanceof DocxDocument.TableBlock) { tableBack = (DocxDocument.TableBlock) block; break; }
        check(tableBack != null && tableBack.rows.size() == baseRows && tableBack.columns == baseColumns,
                "table structure roundtrips unchanged after add/remove returns to the original shape");

        // --- Inserted images write a new part, relationship and content type ---
        DocxDocument.ParagraphBlock imageHost = read.paragraphs.get(0);
        int beforeImages = imageHost.images.size();
        int beforeCount = read.imageCount;
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
        DocxDocument.EmbeddedImage image = new DocxDocument.EmbeddedImage(png, "inserted-test");
        image.partName = "word/media/inserted-test.png";
        image.relationshipId = "rIdInsertedTest";
        image.isNew = true;
        image.widthEmu = 914400; image.heightEmu = 914400;
        imageHost.images.add(image);
        File imageFile = new File(output, "image-insert.docx");
        DocxWriter.write(new FileInputStream(fixture), new FileOutputStream(imageFile), read);
        java.util.zip.ZipFile written = new java.util.zip.ZipFile(imageFile);
        boolean hasPart = false, hasRel = false, hasType = false, allValid = true;
        try {
            hasPart = written.getEntry("word/media/inserted-test.png") != null;
            String rels = new String(readAll(written, "word/_rels/document.xml.rels"), java.nio.charset.StandardCharsets.UTF_8);
            hasRel = rels.contains("rIdInsertedTest") && rels.contains("Target=\"media/inserted-test.png\"");
            String types = new String(readAll(written, "[Content_Types].xml"), java.nio.charset.StandardCharsets.UTF_8);
            hasType = types.contains("Extension=\"png\"");
            javax.xml.parsers.DocumentBuilder builder = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder();
            for (java.util.Enumeration<? extends java.util.zip.ZipEntry> e = written.entries(); e.hasMoreElements();) {
                java.util.zip.ZipEntry entry = e.nextElement();
                if (entry.getName().endsWith(".xml") || entry.getName().endsWith(".rels"))
                    try { builder.parse(new java.io.ByteArrayInputStream(readAll(written, entry.getName()))); }
                    catch (Exception parseError) { allValid = false; }
            }
        } finally { written.close(); }
        check(hasPart, "inserted image part written as word/media/inserted-test.png");
        check(hasRel, "document.xml.rels gains the inserted image relationship");
        check(hasType, "[Content_Types].xml keeps a png default");
        check(allValid, "every XML part of the image-insert document still parses");
        DocxDocument imageRead = DocxParser.parse(new FileInputStream(imageFile), "image-insert.docx");
        check(imageRead.paragraphs.get(0).images.size() == beforeImages + 1
                        && imageRead.imageCount == beforeCount + 1,
                "re-opened document sees the inserted image (bridge from parser to writer)");

        check(DocxFontAssets.TIMES.equals(DocxFontAssets.pathFor("Times New Roman")),
                "Times New Roman still maps to the bundled regular file");
        check(DocxFontAssets.TIMES_BOLD.equals(DocxFontAssets.pathFor("Times New Roman", 1)),
                "Times New Roman bold maps to the real bold file");
        check(DocxFontAssets.SONG.equals(DocxFontAssets.pathFor("宋体"))
                        && DocxFontAssets.SONG.equals(DocxFontAssets.pathFor("华文新魏"))
                        && DocxFontAssets.SONG.equals(DocxFontAssets.pathFor("MS Mincho")),
                "document CJK families use explicit bundled fallbacks after resource reduction");
        File timesFile = new File("app/src/main/assets/fonts/times-new-roman.ttf");
        FontScriptMetrics timesMetrics = FontScriptMetrics.read(new FileInputStream(timesFile));
        check(timesMetrics.scale(true) > 0 && timesMetrics.scale(true) < 1 && timesMetrics.offset(true) < 0,
                "Times OS/2 superscript metrics remain readable after TTC-capable parser");
        File songFile = new File("app/src/main/assets/fonts/song.ttc");
        FontScriptMetrics songMetrics = FontScriptMetrics.read(new FileInputStream(songFile));
        check(songMetrics.scale(false) > 0 && songMetrics.scale(false) <= 1,
                "SimSun collection OS/2 metrics remain readable");

        check(DocxFontAssets.TIMES.equals(DocxFontAssets.pathFor("Times New Roman")),
                "Times New Roman resolves to the bundled regular file");
        check(DocxFontAssets.TIMES_BOLD.equals(DocxFontAssets.pathFor("Times New Roman", 1)),
                "Times New Roman bold resolves to the bundled bold file");
        check(DocxFontAssets.SONG.equals(DocxFontAssets.pathFor("宋体")),
                "Songti resolves to the bundled SimSun file");
        check(DocxFontAssets.pathFor("serif") != null, "generic serif alias is not left to Android");
        System.out.println("SUMMARY " + count + " assertions passed; Android shaping/UI and Word pixel comparison NOT run here.");
    }

    private static byte[] readAll(java.util.zip.ZipFile file, String name) throws Exception {
        java.io.InputStream in = file.getInputStream(file.getEntry(name));
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        in.close();
        return out.toByteArray();
    }
    private static void verifyCoverage(List<PageBreaker.Item> items, List<PageBreaker.Page> pages, float capacity) {
        IdentityHashMap<PageBreaker.Item,Integer> offsets = new IdentityHashMap<PageBreaker.Item,Integer>();
        int lastItem = -1;
        for (PageBreaker.Page page : pages) {
            float end = 0;
            for (PageBreaker.Fragment f : page.fragments) {
                int next = offsets.containsKey(f.item) ? offsets.get(f.item) : 0;
                if (f.startLine != next || f.endLine <= next || f.endLine > f.item.lines.length) throw new AssertionError("Missing/duplicated lines");
                int index = items.indexOf(f.item);
                if (index < lastItem || f.top < end - 0.02f) throw new AssertionError("Out of order/overlap");
                if (!page.overflow && f.top + f.height > capacity + 0.02f) throw new AssertionError("Unreported overflow");
                offsets.put(f.item, f.endLine); end = f.top + f.height; lastItem = index;
            }
        }
        for (PageBreaker.Item it : items) if (!Integer.valueOf(it.lines.length).equals(offsets.get(it))) throw new AssertionError("Missing tail");
    }
}
