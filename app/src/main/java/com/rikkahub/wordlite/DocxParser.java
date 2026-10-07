package com.rikkahub.wordlite;

import android.content.Context;
import android.net.Uri;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Reads the OOXML parts needed for a phone reader. It deliberately does not
 * modify the package: files stay local and are accessed through SAF.
 */
public final class DocxParser {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String REL = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final int MAX_ENTRIES = 4096;
    private static final int MAX_PART_BYTES = 32 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 64 * 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 256 * 1024 * 1024;

    private final Map<String, byte[]> parts;
    private final Map<String, String> themeFonts = new HashMap<String, String>();
    private final Map<String, String> relationships = new HashMap<String, String>();
    private final Map<String, String> externalLinks = new HashMap<String, String>();
    private final Map<String, StyleDef> styles = new HashMap<String, StyleDef>();
    private final Map<String, String> numToAbstract = new HashMap<String, String>();
    private final Map<String, ListLevel> listLevels = new HashMap<String, ListLevel>();
    private final Map<Integer, DocxDocument.Comment> commentsById = new HashMap<Integer, DocxDocument.Comment>();
    private final int[] listCounters = new int[9];
    private int activeListNum = -1;
    private final DocxDocument output = new DocxDocument();
    private final DocxDocument.RunStyle defaultRunStyle = new DocxDocument.RunStyle();
    private final DocxDocument.ParagraphFormat defaultParagraphFormat = new DocxDocument.ParagraphFormat();
    private String defaultParagraphStyleId;
    private int nextBlockIndex;
    private int activeSectionIndex;

    private static final class StyleDef {
        String id;
        String name = "";
        String basedOn = "";
        boolean paragraph;
        boolean isDefault;
        final DocxDocument.ParagraphFormat paragraphFormat = new DocxDocument.ParagraphFormat();
        final DocxDocument.RunStyle runStyle = new DocxDocument.RunStyle();
    }

    private static final class ResolvedStyle {
        final DocxDocument.ParagraphFormat paragraphFormat;
        final DocxDocument.RunStyle runStyle;

        ResolvedStyle(DocxDocument.ParagraphFormat paragraphFormat, DocxDocument.RunStyle runStyle) {
            this.paragraphFormat = paragraphFormat;
            this.runStyle = runStyle;
        }
    }

    private static final class ListLevel {
        String format = "decimal";
        String text = "%1.";
    }

    private static final class PackageData {
        final Map<String, byte[]> parts = new HashMap<String, byte[]>();
        int totalBytes;
    }

    private DocxParser(PackageData packageData) {
        this.parts = packageData.parts;
    }

    public static DocxDocument parse(Context context, Uri uri, String displayName) throws Exception {
        if (uri == null) throw new IllegalArgumentException("未选择文件");
        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null) throw new IllegalArgumentException("无法读取文件");
        return parse(input, displayName);
    }

    /** Reads and closes an input stream; also used by the real JVM round-trip tests. */
    public static DocxDocument parse(InputStream input, String displayName) throws Exception {
        PackageData data;
        try {
            data = readPackage(input);
        } finally {
            try { input.close(); } catch (Exception ignored) { }
        }
        if (!data.parts.containsKey("word/document.xml")) {
            throw new IllegalArgumentException("这不是可读取的 Word 文档：缺少 word/document.xml");
        }
        DocxParser parser = new DocxParser(data);
        return parser.read(displayName == null ? "论文.docx" : displayName);
    }

    private static PackageData readPackage(InputStream input) throws Exception {
        PackageData data = new PackageData();
        Map<String, byte[]> all = DocxZipReader.read(input);
        for (Map.Entry<String, byte[]> entry : all.entrySet()) {
            String name = entry.getKey();
            boolean wanted = "word/document.xml".equals(name)
                    || "word/styles.xml".equals(name)
                    || "word/theme/theme1.xml".equals(name)
                    || "word/numbering.xml".equals(name)
                    || "word/settings.xml".equals(name)
                    || "word/_rels/document.xml.rels".equals(name)
                    || "docProps/core.xml".equals(name)
                    || name.startsWith("word/header")
                    || name.startsWith("word/footer")
                    || name.startsWith("word/footnotes")
                    || name.startsWith("word/endnotes")
                    || name.startsWith("word/comments")
                    || name.startsWith("word/media/")
                    || name.endsWith("vbaProject.bin");
            if (!wanted) continue;
            byte[] bytes = entry.getValue();
            int limit = name.startsWith("word/media/") ? MAX_IMAGE_BYTES : MAX_PART_BYTES;
            if (bytes.length > limit || data.totalBytes + bytes.length > MAX_TOTAL_BYTES)
                throw new IllegalArgumentException("文档过大，已停止读取");
            data.totalBytes += bytes.length;
            data.parts.put(name, bytes);
        }
        return data;
    }

    private DocxDocument read(String displayName) throws Exception {
        output.fileName = displayName;
        output.title = readCoreTitle();
        if (output.title.length() == 0) output.title = stripExtension(displayName);
        for (String name : parts.keySet()) {
            if (name.endsWith("vbaProject.bin")) output.hasMacros = true;
            if (name.startsWith("word/header") || name.startsWith("word/footer")) output.hasHeaderFooter = true;
            if (name.startsWith("word/footnotes")) output.hasFootnotes = true;
            if (name.startsWith("word/endnotes")) output.hasEndnotes = true;
            if (name.startsWith("word/comments")) output.hasComments = true;
        }
        readRelationships();
        readTheme();
        readStyles();
        readNumbering();
        readComments();
        try { output.trackRevisions = booleanValue(firstDescendant(xml("word/settings.xml").getDocumentElement(), "trackRevisions"), false); }
        catch (Exception ignored) { }
        readDocument();
        for (DocxDocument.Comment comment : output.comments) if (comment.parentId >= 0) {
            DocxDocument.Comment parent = commentsById.get(comment.parentId);
            if (parent != null) { comment.paragraphIndex = parent.paragraphIndex; comment.start = parent.start; comment.end = parent.end; }
        }
        if (output.imageCount == 0) {
            // A document can contain drawings that this lightweight view cannot
            // extract as raster images; never erase an earlier unresolved-relation warning.
            output.hasUnsupportedDrawing |= findPartWithPrefix("word/drawings/");
        }
        return output;
    }

    private String readCoreTitle() {
        try {
            Document doc = xml("docProps/core.xml");
            Element title = firstDescendant(doc.getDocumentElement(), "title");
            return title == null ? "" : text(title).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private void readRelationships() {
        try {
            Document doc = xml("word/_rels/document.xml.rels");
            Element root = doc.getDocumentElement();
            for (Element relationship : directChildren(root, "Relationship")) {
                String id = attr(relationship, "Id");
                String target = attr(relationship, "Target");
                if (id.length() > 0 && "External".equalsIgnoreCase(attr(relationship, "TargetMode")))
                    externalLinks.put(id, target);
                if (id.length() > 0 && target.length() > 0
                        && !"External".equalsIgnoreCase(attr(relationship, "TargetMode"))) {
                    relationships.put(id, chooseExistingPart(target.startsWith("/")
                            ? normalizePath(target) : target, target));
                }
            }
        } catch (Exception ignored) {
            // Relationships are optional for text-only files.
        }
    }

    private void readTheme() {
        try {
            Document doc = xml("word/theme/theme1.xml");
            Element root = doc.getDocumentElement();
            Element fontScheme = firstDescendant(root, "fontScheme");
            if (fontScheme == null) return;
            Element major = directChild(fontScheme, "majorFont");
            Element minor = directChild(fontScheme, "minorFont");
            readThemeFontGroup(major, "major");
            readThemeFontGroup(minor, "minor");
        } catch (Exception ignored) {
            // Theme fonts are optional; literal w:rFonts and Android fallback remain usable.
        }
    }

    private void readThemeFontGroup(Element group, String prefix) {
        if (group == null) return;
        Element latin = directChild(group, "latin");
        Element eastAsia = directChild(group, "ea");
        Element complex = directChild(group, "cs");
        if (latin != null) themeFonts.put(prefix + "Ascii", attr(latin, "typeface"));
        if (latin != null) themeFonts.put(prefix + "HAnsi", attr(latin, "typeface"));
        if (eastAsia != null) themeFonts.put(prefix + "EastAsia", attr(eastAsia, "typeface"));
        if (complex != null) themeFonts.put(prefix + "Cs", attr(complex, "typeface"));
    }

    private String themeFont(Element rFonts, String attribute) {
        if (rFonts == null) return null;
        String theme = attr(rFonts, attribute);
        if (theme == null || theme.length() == 0) return null;
        String key;
        if (theme.contains("major")) key = "major";
        else if (theme.contains("minor")) key = "minor";
        else return null;
        if (theme.contains("Ascii")) return themeFonts.get(key + "Ascii");
        if (theme.contains("HAnsi")) return themeFonts.get(key + "HAnsi");
        if (theme.contains("EastAsia")) return themeFonts.get(key + "EastAsia");
        if (theme.contains("Cs")) return themeFonts.get(key + "Cs");
        return null;
    }

    private void readStyles() {
        try {
            Document doc = xml("word/styles.xml");
            Element root = doc.getDocumentElement();
            Element defaults = directChild(root, "docDefaults");
            if (defaults != null) {
                Element rPrDefault = directChild(defaults, "rPrDefault");
                if (rPrDefault != null) defaultRunStyle.merge(parseRunProperties(directChild(rPrDefault, "rPr")));
                Element pPrDefault = directChild(defaults, "pPrDefault");
                if (pPrDefault != null) defaultParagraphFormat.merge(parseParagraphProperties(directChild(pPrDefault, "pPr")));
            }
            for (Element styleElement : directChildren(root, "style")) {
                StyleDef style = new StyleDef();
                style.id = attr(styleElement, "styleId");
                style.name = childValue(styleElement, "name");
                style.basedOn = childValue(styleElement, "basedOn");
                style.paragraph = !"character".equals(attr(styleElement, "type"));
                style.isDefault = isTrue(attr(styleElement, "default"));
                style.paragraphFormat.merge(parseParagraphProperties(directChild(styleElement, "pPr")));
                style.runStyle.merge(parseRunProperties(directChild(styleElement, "rPr")));
                if (style.id.length() > 0) styles.put(style.id, style);
                if (style.isDefault && style.paragraph) defaultParagraphStyleId = style.id;
            }
        } catch (Exception ignored) {
            // Word permits styles.xml to be absent. The parser then uses Word's normal defaults.
        }
    }

    private void readNumbering() {
        try {
            Document doc = xml("word/numbering.xml");
            Element root = doc.getDocumentElement();
            for (Element abstractNum : directChildren(root, "abstractNum")) {
                String abstractId = attr(abstractNum, "abstractNumId");
                for (Element level : directChildren(abstractNum, "lvl")) {
                    String ilvl = attr(level, "ilvl");
                    ListLevel parsed = new ListLevel();
                    String format = childValue(level, "numFmt");
                    String template = childValue(level, "lvlText");
                    if (format.length() > 0) parsed.format = format;
                    if (template.length() > 0) parsed.text = template;
                    listLevels.put(abstractId + "#" + ilvl, parsed);
                }
            }
            for (Element num : directChildren(root, "num")) {
                String numId = attr(num, "numId");
                Element abstractId = directChild(num, "abstractNumId");
                if (abstractId != null) numToAbstract.put(numId, attr(abstractId, "val"));
            }
        } catch (Exception ignored) {
            // Lists are still rendered as bullets when numbering.xml is incomplete.
        }
    }

    private String nextListLabel(int numId, int level) {
        if (numId != activeListNum) {
            for (int i = 0; i < listCounters.length; i++) listCounters[i] = 0;
            activeListNum = numId;
        }
        int safeLevel = Math.max(0, Math.min(listCounters.length - 1, level));
        for (int i = safeLevel + 1; i < listCounters.length; i++) listCounters[i] = 0;
        if (listCounters[safeLevel] == 0) listCounters[safeLevel] = 1;
        else listCounters[safeLevel]++;
        String abstractId = numToAbstract.get(String.valueOf(numId));
        ListLevel listLevel = abstractId == null ? null : listLevels.get(abstractId + "#" + safeLevel);
        if (listLevel == null) {
            return safeLevel == 0 ? "•" : "◦";
        }
        String format = listLevel.format == null ? "" : listLevel.format.toLowerCase();
        if (format.contains("bullet") || format.contains("none")) {
            return safeLevel == 0 ? "•" : safeLevel == 1 ? "◦" : "▪";
        }
        String label = listLevel.text == null || listLevel.text.length() == 0 ? "%1." : listLevel.text;
        for (int i = 0; i < listCounters.length; i++) {
            label = label.replace("%" + (i + 1), String.valueOf(listCounters[i] == 0 ? 1 : listCounters[i]));
        }
        return label;
    }

    private void readDocument() throws Exception {
        Document document = xml("word/document.xml");
        output.originalRootAttributes = OoxmlPreserver.rootAttributes(document.getDocumentElement());
        Element body = firstDescendant(document.getDocumentElement(), "body");
        if (body == null) throw new IllegalArgumentException("Word 文档正文为空");
        ArrayList<Element> children = childElements(body);
        ArrayList<Element> sectionProperties = new ArrayList<Element>();
        for (Element child : children) {
            if (!"p".equals(localName(child))) continue;
            Element pPr = directChild(child, "pPr");
            Element sectPr = pPr == null ? null : directChild(pPr, "sectPr");
            if (sectPr != null) sectionProperties.add(sectPr);
        }
        Element finalSect = directChild(body, "sectPr");
        if (finalSect != null) {
            sectionProperties.add(finalSect);
            output.originalFinalSection = OoxmlPreserver.xml(finalSect);
        }
        output.sections.clear();
        if (sectionProperties.isEmpty()) sectionProperties.add(null);
        for (Element sectPr : sectionProperties) {
            DocxDocument.SectionSettings section = new DocxDocument.SectionSettings();
            if (sectPr != null) parseSection(sectPr, section);
            parseHeaderFooter(section);
            output.sections.add(section);
        }
        activeSectionIndex = 0;
        for (Element child : children) {
            String name = localName(child);
            if ("p".equals(name)) {
                DocxDocument.ParagraphBlock paragraph = parseParagraph(child);
                paragraph.sectionIndex = Math.min(activeSectionIndex, output.sections.size() - 1);
                output.blocks.add(paragraph);
                OoxmlPreserver.capture(paragraph, child, output);
                Element pPr = directChild(child, "pPr");
                Element sectPr = pPr == null ? null : directChild(pPr, "sectPr");
                if (sectPr != null && activeSectionIndex + 1 < output.sections.size()) activeSectionIndex++;
            } else if ("tbl".equals(name)) {
                DocxDocument.TableBlock table = parseTable(child);
                table.sectionIndex = Math.min(activeSectionIndex, output.sections.size() - 1);
                output.blocks.add(table);
                OoxmlPreserver.capture(table, child, output);
            } else if (!"sectPr".equals(name)) {
                DocxDocument.OpaqueBlock opaque = new DocxDocument.OpaqueBlock();
                opaque.index = -output.blocks.size() - 1; opaque.sectionIndex = activeSectionIndex;
                opaque.originalXml = OoxmlPreserver.xml(child); output.blocks.add(opaque);
            }
        }
        output.section.set(output.sections.get(output.sections.size() - 1));
        output.originalFinalSectionSignature = OoxmlPreserver.sectionSignature(output.section);
    }

    private void parseHeaderFooter(DocxDocument.SectionSettings section) {
        section.headerText = partVisibleText(section.headerPart, true, section);
        section.footerTemplate = partVisibleText(section.footerPart, false, section);
    }

    private String partVisibleText(String part, boolean header, DocxDocument.SectionSettings section) {
        if (part == null || part.length() == 0 || !parts.containsKey(part)) return "";
        try {
            Document doc = xml(part);
            StringBuilder out = new StringBuilder();
            for (Element paragraph : descendantsNamed(doc.getDocumentElement(), "p")) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
                if (header) readPartParagraphStyle(paragraph, section, true);
                else readPartParagraphStyle(paragraph, section, false);
                appendPartParagraph(paragraph, out);
            }
            return out.toString().trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private void readPartParagraphStyle(Element paragraph, DocxDocument.SectionSettings section, boolean header) {
        Element pPr = directChild(paragraph, "pPr");
        if (pPr != null) {
            DocxDocument.ParagraphFormat format = parseParagraphProperties(pPr);
            if (header) {
                section.headerAlignment = format.alignment >= 0 ? format.alignment : section.headerAlignment;
                Element borders = directChild(pPr, "pBdr");
                Element bottom = directChild(borders, "bottom");
                section.headerHasBottomBorder = bottom != null && !"nil".equalsIgnoreCase(attr(bottom, "val"));
                if (bottom != null) {
                    section.headerBorderValue = attr(bottom, "val");
                    section.headerBorderSizeEighthPt = Math.max(2, intValue(attr(bottom, "sz"), section.headerBorderSizeEighthPt));
                    section.headerBorderSpacePt = Math.max(0, intValue(attr(bottom, "space"), section.headerBorderSpacePt));
                }
            } else {
                section.footerAlignment = format.alignment >= 0 ? format.alignment : section.footerAlignment;
            }
        }
        Element run = firstDescendant(paragraph, "r");
        Element rPr = run == null ? null : directChild(run, "rPr");
        DocxDocument.RunStyle style = parseRunProperties(rPr);
        if (header) {
            if (style.fontFamily != null) section.headerFontFamily = style.fontFamily;
            if (style.fontSizeHalfPoints > 0) section.headerFontSizePt = style.fontSizeHalfPoints / 2f;
        } else {
            if (style.fontFamily != null) section.footerFontFamily = style.fontFamily;
            if (style.fontSizeHalfPoints > 0) section.footerFontSizePt = style.fontSizeHalfPoints / 2f;
        }
    }

    private void appendPartParagraph(Element paragraph, StringBuilder out) {
        PartFieldState field = new PartFieldState();
        appendPartNode(paragraph, out, field);
    }

    private static final class PartFieldState {
        boolean inField;
        boolean result;
        StringBuilder instruction = new StringBuilder();
    }

    private void appendPartNode(Node node, StringBuilder out, PartFieldState field) {
        if (node == null) return;
        String name = node.getNodeType() == Node.ELEMENT_NODE ? localName(node) : "";
        if ("fldChar".equals(name)) {
            String type = attr((Element) node, "fldCharType");
            if ("begin".equals(type)) { field.inField = true; field.result = false; field.instruction.setLength(0); }
            else if ("separate".equals(type)) field.result = true;
            else if ("end".equals(type)) {
                String instruction = field.instruction.toString().trim().toUpperCase(java.util.Locale.US);
                if (instruction.matches(".*\\bNUMPAGES\\b.*")) out.append("%NUMPAGES%");
                else if (instruction.matches(".*\\bPAGE\\b.*")) out.append("%PAGE%");
                field.inField = false;
                field.result = false;
            }
            return;
        }
        if ("instrText".equals(name)) {
            if (field.inField && !field.result) field.instruction.append(text(node));
            return;
        }
        if ("t".equals(name)) {
            if (!field.inField) out.append(text(node));
            return; // cached field results are deliberately not copied
        }
        if ("tab".equals(name)) { if (!field.inField) out.append('\t'); return; }
        if ("br".equals(name) || "cr".equals(name)) { if (!field.inField) out.append('\n'); return; }
        NodeList nodes = node.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) appendPartNode(nodes.item(i), out, field);
        if ("p".equals(name) && out.length() > 0 && out.charAt(out.length() - 1) == '\n') out.setLength(out.length() - 1);
    }

    private void readComments() {
        try {
            Document comments = xml("word/comments.xml");
            for (Element comment : directChildren(comments.getDocumentElement(), "comment")) {
                int id = intValue(attr(comment, "id"), output.comments.size());
                String author = attr(comment, "author");
                String value = visibleText(comment).trim();
                DocxDocument.Comment item = new DocxDocument.Comment(id, -1, 0, 0, value);
                if (author.length() > 0) item.author = author;
                item.date = attr(comment, "date");
                Element lastParagraph = lastDescendant(comment, "p");
                item.paraId = lastParagraph == null ? "" : attr(lastParagraph, "paraId");
                item.resolved = "done".equalsIgnoreCase(attr(comment, "done"));
                output.comments.add(item);
                commentsById.put(id, item);
            }
            if (parts.containsKey("word/commentsExtended.xml")) {
                Document extended = xml("word/commentsExtended.xml");
                HashMap<String, DocxDocument.Comment> byParagraph = new HashMap<String, DocxDocument.Comment>();
                for (DocxDocument.Comment item : output.comments) if (!item.paraId.isEmpty()) byParagraph.put(item.paraId, item);
                for (Element element : childElements(extended.getDocumentElement())) {
                    DocxDocument.Comment item = byParagraph.get(attr(element, "paraId"));
                    if (item == null) continue;
                    item.resolved = isTrue(attr(element, "done"));
                    DocxDocument.Comment parent = byParagraph.get(attr(element, "paraIdParent"));
                    if (parent != null) item.parentId = parent.id;
                }
            }
        } catch (Exception ignored) {
            // comments.xml is optional
        }
    }

    private DocxDocument.TableBlock parseTable(Element tableElement) {
        DocxDocument.TableBlock table = new DocxDocument.TableBlock();
        table.index = nextBlockIndex++;
        output.tableCount++;
        Element tableProperties = directChild(tableElement, "tblPr");
        if (tableProperties != null) {
            Element tableWidth = directChild(tableProperties, "tblW");
            if (tableWidth != null) {
                table.widthTwips = intValue(attr(tableWidth, "w"), 0);
                if (attr(tableWidth, "type").length() > 0) table.widthType = attr(tableWidth, "type");
            }
            table.indentTwips = twipsAttribute(directChild(tableProperties, "tblInd"), 0);
            Element layout = directChild(tableProperties, "tblLayout");
            table.fixedLayout = layout != null && "fixed".equals(attr(layout, "type"));
            Element alignment = directChild(tableProperties, "jc");
            if (attr(alignment, "val").length() > 0) table.alignment = attr(alignment, "val");
            Element tableMargins = directChild(tableProperties, "tblCellMar");
            if (tableMargins != null) {
                table.cellMarginTopTwips = twipsAttribute(directChild(tableMargins, "top"), table.cellMarginTopTwips);
                table.cellMarginLeftTwips = twipsAttribute(directChild(tableMargins, "left"), table.cellMarginLeftTwips);
                table.cellMarginBottomTwips = twipsAttribute(directChild(tableMargins, "bottom"), table.cellMarginBottomTwips);
                table.cellMarginRightTwips = twipsAttribute(directChild(tableMargins, "right"), table.cellMarginRightTwips);
            }
        }
        for (Element grid : directChildren(tableElement, "tblGrid"))
            for (Element column : directChildren(grid, "gridCol"))
                table.gridColumns.add(Integer.valueOf(intValue(attr(column, "w"), 0)));
        for (Element rowElement : directChildren(tableElement, "tr")) {
            DocxDocument.RowFormat format = new DocxDocument.RowFormat();
            Element rowProperties = directChild(rowElement, "trPr");
            if (rowProperties != null) {
                format.cantSplit = directChild(rowProperties, "cantSplit") != null;
                format.repeatAsHeader = directChild(rowProperties, "tblHeader") != null;
                Element height = directChild(rowProperties, "trHeight");
                if (height != null) {
                    format.heightTwips = intValue(attr(height, "val"), 0);
                    if (attr(height, "hRule").length() > 0) format.heightRule = attr(height, "hRule");
                }
            }
            table.rowFormats.add(format);
            ArrayList<DocxDocument.Cell> row = new ArrayList<DocxDocument.Cell>();
            for (Element cellElement : directChildren(rowElement, "tc")) {
                DocxDocument.Cell cell = new DocxDocument.Cell();
                Element cellProperties = directChild(cellElement, "tcPr");
                if (cellProperties != null) {
                    Element cellWidth = directChild(cellProperties, "tcW");
                    if (cellWidth != null) {
                        cell.widthTwips = intValue(attr(cellWidth, "w"), 0);
                        if (attr(cellWidth, "type").length() > 0) cell.widthType = attr(cellWidth, "type");
                    }
                    cell.gridSpan = Math.max(1,
                            intValue(attr(directChild(cellProperties, "gridSpan"), "val"), 1));
                    Element merge = directChild(cellProperties, "vMerge");
                    cell.mergeContinues = merge != null && !"restart".equals(attr(merge, "val"));
                    Element vertical = directChild(cellProperties, "vAlign");
                    if (attr(vertical, "val").length() > 0) cell.verticalAlign = attr(vertical, "val");
                    Element cellMargins = directChild(cellProperties, "tcMar");
                    if (cellMargins != null) {
                        cell.marginTopTwips = twipsAttribute(directChild(cellMargins, "top"), -1);
                        cell.marginLeftTwips = twipsAttribute(directChild(cellMargins, "left"), -1);
                        cell.marginBottomTwips = twipsAttribute(directChild(cellMargins, "bottom"), -1);
                        cell.marginRightTwips = twipsAttribute(directChild(cellMargins, "right"), -1);
                    }
                }
                for (Element child : childElements(cellElement)) {
                    if ("p".equals(localName(child))) {
                        cell.paragraphs.add(parseParagraph(child));
                    } else if ("tbl".equals(localName(child))) {
                        // Nested table text is still useful in a reader; flatten its paragraphs into the cell.
                        DocxDocument.TableBlock nested = parseTable(child);
                        for (ArrayList<DocxDocument.Cell> nestedRow : nested.rows) {
                            for (DocxDocument.Cell nestedCell : nestedRow) cell.paragraphs.addAll(nestedCell.paragraphs);
                        }
                    }
                }
                row.add(cell);
            }
            if (row.size() > table.columns) table.columns = row.size();
            table.rows.add(row);
        }
        return table;
    }

    private DocxDocument.ParagraphBlock parseParagraph(Element paragraphElement) {
        DocxDocument.ParagraphBlock paragraph = new DocxDocument.ParagraphBlock();
        paragraph.index = nextBlockIndex++;
        output.paragraphs.add(paragraph);

        Element pPr = directChild(paragraphElement, "pPr");
        String styleId = "";
        if (pPr != null) {
            Element pStyle = directChild(pPr, "pStyle");
            styleId = pStyle == null ? "" : attr(pStyle, "val");
        }
        if (styleId.length() == 0) styleId = defaultParagraphStyleId == null ? "" : defaultParagraphStyleId;
        paragraph.styleId = styleId;
        StyleDef styleDef = styles.get(styleId);
        paragraph.styleName = styleDef == null ? "" : styleDef.name;
        ResolvedStyle resolved = resolveStyle(styleId, new HashSet<String>());
        paragraph.baseRunStyle.merge(resolved.runStyle);
        paragraph.format.merge(resolved.paragraphFormat);
        paragraph.format.merge(parseParagraphProperties(pPr));
        paragraph.isHeading = isHeading(styleId, paragraph.styleName, pPr);
        paragraph.isList = paragraph.format.numId >= 0;
        if (paragraph.isList) paragraph.listLabel = nextListLabel(paragraph.format.numId, paragraph.format.ilvl);
        if (pPr != null && hasDirectRunOrParagraphProperty(pPr)) paragraph.hasDirectFormatting = true;

        parseRunContainer(paragraphElement, paragraph, resolved.runStyle);
        // Images may be wrapped in mc:AlternateContent, a text box, or a
        // producer-specific container that is not a direct child of w:r.
        // Scan the complete paragraph as a deduplicated fallback.
        addImages(paragraphElement, paragraph);
        StringBuilder visible = new StringBuilder();
        for (DocxDocument.Run run : paragraph.runs) visible.append(run.text);
        paragraph.text = visible.toString();
        parseFields(paragraphElement, paragraph);
        attachParagraphComments(paragraphElement, paragraph);
        Element change = directChild(pPr, "pPrChange");
        if (change != null) {
            DocxDocument.Revision revision = readRevision(change, paragraph, DocxDocument.Revision.Kind.PARAGRAPH_FORMAT);
            revision.end = paragraph.text.length();
            Element previous = directChild(change, "pPr");
            revision.previousParagraphFormat = parseParagraphProperties(previous);
            try { revision.previousPropertiesXml = previous == null ? "" : OoxmlPreserver.xml(previous); } catch (Exception ignored) { }
            output.revisions.add(revision); output.hasTrackedChanges = true;
        }
        for (int i = output.revisions.size() - 1; i >= 0; i--) {
            DocxDocument.Revision current = output.revisions.get(i);
            if (current.paragraphIndex != paragraph.index) continue;
            for (int j = 0; j < i; j++) {
                DocxDocument.Revision prior = output.revisions.get(j);
                if (prior.paragraphIndex == current.paragraphIndex && prior.id == current.id && prior.kind == current.kind
                        && current.kind != DocxDocument.Revision.Kind.DELETE && prior.end == current.start) {
                    prior.end = current.end; prior.previousRuns.addAll(current.previousRuns);
                    if (!prior.previousPropertiesXml.equals(current.previousPropertiesXml)) prior.previousPropertiesXml = "";
                    output.revisions.remove(i); break;
                }
            }
        }
        return paragraph;
    }

    private void attachParagraphComments(Element paragraphElement, DocxDocument.ParagraphBlock paragraph) {
        ArrayList<Integer> open = new ArrayList<Integer>();
        walkParagraphComments(paragraphElement, paragraph, open, 0);
    }

    private int walkParagraphComments(Node node, DocxDocument.ParagraphBlock paragraph,
                                      ArrayList<Integer> open, int visibleOffset) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            String name = localName(child);
            if ("commentRangeStart".equals(name)) {
                int id = intValue(attr((Element) child, "id"), -1);
                open.add(id);
                DocxDocument.Comment comment = commentsById.get(id);
                if (comment != null) {
                    comment.paragraphIndex = paragraph.index;
                    comment.start = visibleOffset;
                    comment.end = visibleOffset;
                }
            } else if ("commentRangeEnd".equals(name)) {
                int id = intValue(attr((Element) child, "id"), -1);
                DocxDocument.Comment comment = commentsById.get(id);
                if (comment != null) {
                    comment.paragraphIndex = paragraph.index;
                    comment.end = Math.max(comment.end, visibleOffset);
                }
                open.remove(Integer.valueOf(id));
            } else if ("t".equals(name)) {
                visibleOffset += text(child).length();
                markOpenComments(open, visibleOffset);
            } else if ("tab".equals(name) || "br".equals(name) || "cr".equals(name)) {
                visibleOffset++;
                markOpenComments(open, visibleOffset);
            } else if (!"instrText".equals(name) && !"del".equals(name) && !"pPr".equals(name) && !"rPr".equals(name)) {
                visibleOffset = walkParagraphComments(child, paragraph, open, visibleOffset);
            }
        }
        return visibleOffset;
    }

    private void markOpenComments(ArrayList<Integer> open, int visibleOffset) {
        for (Integer id : open) {
            DocxDocument.Comment comment = commentsById.get(id);
            if (comment != null) comment.end = visibleOffset;
        }
    }

    private void parseRunContainer(Element parent, DocxDocument.ParagraphBlock paragraph, DocxDocument.RunStyle baseStyle) {
        for (Element child : childElements(parent)) {
            String name = localName(child);
            if ("r".equals(name)) {
                int start = paragraphLength(paragraph), firstRun = paragraph.runs.size();
                parseRun(child, paragraph, baseStyle);
                Element change = directChild(directChild(child, "rPr"), "rPrChange");
                if (change != null) {
                    DocxDocument.Revision revision = readRevision(change, paragraph, DocxDocument.Revision.Kind.FORMAT);
                    revision.start = start; revision.end = paragraphLength(paragraph);
                    Element previous = directChild(change, "rPr");
                    DocxDocument.RunStyle old = baseStyle.copy(); old.merge(parseRunProperties(previous));
                    for (int i = firstRun; i < paragraph.runs.size(); i++) revision.previousRuns.add(new DocxDocument.Run(paragraph.runs.get(i).text, old.copy()));
                    try { revision.previousPropertiesXml = previous == null ? "" : OoxmlPreserver.xml(previous); } catch (Exception ignored) { }
                    output.revisions.add(revision); output.hasTrackedChanges = true;
                }
            } else if ("hyperlink".equals(name) || "fldSimple".equals(name) || "ins".equals(name)
                    || "sdt".equals(name) || "sdtContent".equals(name) || "smartTag".equals(name) || "customXml".equals(name)
                    || "AlternateContent".equals(name) || "Choice".equals(name) || "Fallback".equals(name)) {
                int linkStart = paragraphLength(paragraph);
                DocxDocument.Revision insertion = "ins".equals(name) ? readRevision(child, paragraph, DocxDocument.Revision.Kind.INSERT) : null;
                if (insertion != null) { insertion.start = linkStart; output.hasTrackedChanges = true; }
                parseRunContainer(child, paragraph, baseStyle);
                if (insertion != null) { insertion.end = paragraphLength(paragraph); output.revisions.add(insertion); }
                if ("hyperlink".equals(name)) {
                    DocxDocument.Hyperlink link = new DocxDocument.Hyperlink();
                    link.start = linkStart; link.end = paragraphLength(paragraph);
                    link.relationshipId = attr(child, "id"); link.anchor = attr(child, "anchor");
                    link.target = externalLinks.containsKey(link.relationshipId) ? externalLinks.get(link.relationshipId) : "";
                    paragraph.hyperlinks.add(link);
                }
            } else if ("drawing".equals(name) || "pict".equals(name) || "object".equals(name)) {
                addImages(child, paragraph);
            } else if ("del".equals(name)) {
                output.hasTrackedChanges = true;
                DocxDocument.Revision deletion = readRevision(child, paragraph, DocxDocument.Revision.Kind.DELETE);
                deletion.start = deletion.end = paragraphLength(paragraph);
                StringBuilder value = new StringBuilder();
                for (Element run : descendantsNamed(child, "r")) {
                    DocxDocument.RunStyle old = baseStyle.copy(); old.merge(parseRunProperties(directChild(run, "rPr")));
                    StringBuilder runText = new StringBuilder();
                    for (Element item : childElements(run)) {
                        String local = localName(item);
                        if ("delText".equals(local) || "t".equals(local)) runText.append(text(item));
                        if ("tab".equals(local)) runText.append('\t');
                        if ("br".equals(local)) runText.append('\n');
                    }
                    value.append(runText); deletion.previousRuns.add(new DocxDocument.Run(runText.toString(), old));
                }
                deletion.text = value.toString(); output.revisions.add(deletion);
            }
        }
    }

    private DocxDocument.Revision readRevision(Element element, DocxDocument.ParagraphBlock paragraph, DocxDocument.Revision.Kind kind) {
        DocxDocument.Revision revision = new DocxDocument.Revision();
        revision.id = intValue(attr(element, "id"), ReviewManager.nextRevisionId(output));
        revision.kind = kind; revision.paragraphIndex = paragraph.index;
        String author = attr(element, "author"); if (!author.isEmpty()) revision.author = author;
        revision.date = attr(element, "date"); return revision;
    }

    private static int paragraphLength(DocxDocument.ParagraphBlock paragraph) {
        int length = 0; for (DocxDocument.Run run : paragraph.runs) length += run.text.length(); return length;
    }

    private static final class FieldState {
        final StringBuilder instruction = new StringBuilder();
        int start = -1;
    }

    private void parseFields(Element element, DocxDocument.ParagraphBlock paragraph) {
        ArrayList<FieldState> stack = new ArrayList<FieldState>();
        scanFields(element, paragraph, stack, new int[]{0});
        java.util.Collections.sort(paragraph.fields, (a, b) -> Integer.compare(a.start, b.start));
    }

    private void scanFields(Node node, DocxDocument.ParagraphBlock paragraph,
                            ArrayList<FieldState> stack, int[] offset) {
        String name = localName(node);
        if ("del".equals(name) || "pPr".equals(name) || "rPr".equals(name)) return;
        if ("fldSimple".equals(name)) {
            int start = offset[0];
            NodeList nodes = node.getChildNodes();
            for (int i = 0; i < nodes.getLength(); i++)
                if (nodes.item(i).getNodeType() == Node.ELEMENT_NODE) scanFields(nodes.item(i), paragraph, stack, offset);
            addField(paragraph, attr((Element) node, "instr"), start, offset[0], true);
            return;
        }
        if ("fldChar".equals(name)) {
            String type = attr((Element) node, "fldCharType");
            if ("begin".equals(type)) stack.add(new FieldState());
            else if ("separate".equals(type) && !stack.isEmpty()) stack.get(stack.size() - 1).start = offset[0];
            else if ("end".equals(type) && !stack.isEmpty()) {
                FieldState state = stack.remove(stack.size() - 1);
                addField(paragraph, state.instruction.toString(), state.start < 0 ? offset[0] : state.start, offset[0], false);
            }
            return;
        }
        if ("instrText".equals(name)) {
            if (!stack.isEmpty()) stack.get(stack.size() - 1).instruction.append(text(node));
            return;
        }
        if ("t".equals(name)) { offset[0] += text(node).length(); return; }
        if ("tab".equals(name) || "br".equals(name) || "cr".equals(name)) { offset[0]++; return; }
        NodeList nodes = node.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++)
            if (nodes.item(i).getNodeType() == Node.ELEMENT_NODE) scanFields(nodes.item(i), paragraph, stack, offset);
    }

    private void addField(DocxDocument.ParagraphBlock paragraph, String code, int start, int end, boolean simple) {
        start = Math.min(paragraph.text.length(), Math.max(0, start));
        end = Math.min(paragraph.text.length(), Math.max(start, end));
        DocxDocument.FieldCode field = new DocxDocument.FieldCode(code, paragraph.text.substring(start, end), simple);
        field.start = start; field.end = end;
        paragraph.fields.add(field);
    }

    private static String visibleText(Node node) {
        if (node == null) return "";
        StringBuilder result = new StringBuilder();
        if (node.getNodeType() == Node.ELEMENT_NODE && "t".equals(localName(node))) {
            result.append(text(node));
        }
        NodeList nodes = node.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node child = nodes.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) result.append(visibleText(child));
        }
        return result.toString();
    }

    private void parseRun(Element runElement, DocxDocument.ParagraphBlock paragraph, DocxDocument.RunStyle baseStyle) {
        Element rPr = directChild(runElement, "rPr");
        DocxDocument.RunStyle style = baseStyle == null ? new DocxDocument.RunStyle() : baseStyle.copy();
        DocxDocument.RunStyle direct = parseRunProperties(rPr);
        if (rPr != null) paragraph.hasDirectFormatting = true;
        style.merge(direct);
        StringBuilder value = new StringBuilder();
        for (Element child : childElements(runElement)) {
            String name = localName(child);
            if ("t".equals(name) || "delText".equals(name)) {
                value.append(text(child));
            } else if ("tab".equals(name)) {
                value.append('\t');
            } else if ("br".equals(name) || "cr".equals(name)) {
                value.append('\n');
            }
        }
        if (value.length() > 0) paragraph.runs.add(new DocxDocument.Run(value.toString(), style));
        for (Element imageContainer : descendantsNamed(runElement, "drawing")) addImages(imageContainer, paragraph);
        for (Element imageContainer : descendantsNamed(runElement, "pict")) addImages(imageContainer, paragraph);
        for (Element imageContainer : descendantsNamed(runElement, "object")) addImages(imageContainer, paragraph);
    }

    private void addImages(Element container, DocxDocument.ParagraphBlock paragraph) {
        ArrayList<Element> imageNodes = new ArrayList<Element>();
        imageNodes.addAll(descendantsNamed(container, "blip"));
        imageNodes.addAll(descendantsNamed(container, "imagedata"));
        for (Element imageNode : imageNodes) {
            String relId = attr(imageNode, "embed");
            if (relId.length() == 0) relId = attr(imageNode, "id");
            String partName = relationships.get(relId);
            byte[] bytes = partName == null ? null : parts.get(partName);
            if (bytes == null) {
                output.hasUnsupportedDrawing = true;
                continue;
            }
            DocxDocument.EmbeddedImage image = new DocxDocument.EmbeddedImage(bytes, partName);
            image.relationshipId = relId;
            image.partName = partName;
            boolean duplicate = false;
            for (DocxDocument.EmbeddedImage existing : paragraph.images) {
                if (relId.equals(existing.relationshipId) && partName.equals(existing.partName)) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) continue;
            Element extent = firstDescendant(container, "extent");
            if (extent == null) extent = firstDescendant(container, "ext");
            if (extent != null) {
                image.widthEmu = intValue(attr(extent, "cx"), -1);
                image.heightEmu = intValue(attr(extent, "cy"), -1);
            }
            paragraph.images.add(image);
            output.imageCount++;
        }
    }

    private void parseSection(Element sectPr, DocxDocument.SectionSettings section) {
        Element pageSize = directChild(sectPr, "pgSz");
        if (pageSize != null) {
            section.pageWidthTwips = intValue(attr(pageSize, "w"), section.pageWidthTwips);
            section.pageHeightTwips = intValue(attr(pageSize, "h"), section.pageHeightTwips);
            String orientation = attr(pageSize, "orient");
            section.landscape = "landscape".equalsIgnoreCase(orientation);
        }
        Element margins = directChild(sectPr, "pgMar");
        if (margins != null) {
            section.marginTopTwips = intValue(attr(margins, "top"), section.marginTopTwips);
            section.marginBottomTwips = intValue(attr(margins, "bottom"), section.marginBottomTwips);
            section.marginLeftTwips = intValue(attr(margins, "left"), section.marginLeftTwips);
            section.marginRightTwips = intValue(attr(margins, "right"), section.marginRightTwips);
            section.gutterTwips = Math.max(0, intValue(attr(margins, "gutter"), section.gutterTwips));
            section.headerDistanceTwips = intValue(attr(margins, "header"), section.headerDistanceTwips);
            section.footerDistanceTwips = intValue(attr(margins, "footer"), section.footerDistanceTwips);
        }
        Element grid = directChild(sectPr, "docGrid");
        if (grid != null) {
            int pitch = intValue(attr(grid, "linePitch"), section.lineGridPitchTwips);
            if (pitch > 0) section.lineGridPitchTwips = pitch;
        }
        for (Element ref : childElements(sectPr)) {
            String name = localName(ref);
            if ("headerReference".equals(name) && "default".equalsIgnoreCase(attr(ref, "type"))) {
                section.headerRelationshipId = attr(ref, "id");
                section.headerPart = relationships.get(section.headerRelationshipId);
            } else if ("footerReference".equals(name) && "default".equalsIgnoreCase(attr(ref, "type"))) {
                section.footerRelationshipId = attr(ref, "id");
                section.footerPart = relationships.get(section.footerRelationshipId);
            }
            else if ("pgNumType".equals(name)) {
                String format = attr(ref, "fmt");
                if (format.length() > 0) section.pageNumberFormat = format;
                section.pageNumberStart = intValue(attr(ref, "start"), section.pageNumberStart);
            }
        }
        if (section.headerDistanceTwips < 0) section.headerDistanceTwips = 720;
        if (section.footerDistanceTwips < 0) section.footerDistanceTwips = 720;
    }

    private ResolvedStyle resolveStyle(String styleId, Set<String> visiting) {
        DocxDocument.ParagraphFormat p = defaultParagraphFormat.copy();
        DocxDocument.RunStyle r = defaultRunStyle.copy();
        if (styleId != null && styleId.length() > 0 && visiting.add(styleId)) {
            StyleDef style = styles.get(styleId);
            if (style != null) {
                if (style.basedOn != null && style.basedOn.length() > 0) {
                    ResolvedStyle parent = resolveStyle(style.basedOn, visiting);
                    p = parent.paragraphFormat.copy();
                    r = parent.runStyle.copy();
                }
                p.merge(style.paragraphFormat);
                r.merge(style.runStyle);
            }
            visiting.remove(styleId);
        }
        return new ResolvedStyle(p, r);
    }

    private static boolean isHeading(String styleId, String styleName, Element pPr) {
        String value = ((styleId == null ? "" : styleId) + " " + (styleName == null ? "" : styleName)).toLowerCase();
        if (value.contains("heading") || value.contains("标题") || value.matches(".*(^|\\D)[1-9](\\D|$).*")) return true;
        Element outline = pPr == null ? null : directChild(pPr, "outlineLvl");
        return outline != null;
    }

    private static boolean hasDirectRunOrParagraphProperty(Element pPr) {
        for (Element child : childElements(pPr)) {
            String name = localName(child);
            if (!"pStyle".equals(name) && !"numPr".equals(name)) return true;
        }
        return false;
    }

    private static DocxDocument.ParagraphFormat parseParagraphProperties(Element pPr) {
        DocxDocument.ParagraphFormat format = new DocxDocument.ParagraphFormat();
        if (pPr == null) return format;
        for (Element child : childElements(pPr)) {
            String name = localName(child);
            if ("jc".equals(name)) {
                String value = attr(child, "val").toLowerCase();
                if ("center".equals(value)) format.alignment = 1;
                else if ("right".equals(value) || "end".equals(value)) format.alignment = 2;
                else if ("both".equals(value) || "distribute".equals(value)) format.alignment = 3;
                else format.alignment = 0;
            } else if ("ind".equals(name)) {
                format.leftIndentTwips = intValue(attr(child, "left"), format.leftIndentTwips);
                format.rightIndentTwips = intValue(attr(child, "right"), format.rightIndentTwips);
                int first = intValue(attr(child, "firstLine"), -1);
                int hanging = intValue(attr(child, "hanging"), -1);
                if (first >= 0) format.firstLineIndentTwips = first;
                else if (hanging >= 0) format.firstLineIndentTwips = -hanging;
            } else if ("spacing".equals(name)) {
                format.spacingBeforeTwips = intValue(attr(child, "before"), format.spacingBeforeTwips);
                format.spacingAfterTwips = intValue(attr(child, "after"), format.spacingAfterTwips);
                format.spacingBeforeLines = intValue(attr(child, "beforeLines"), format.spacingBeforeLines);
                format.spacingAfterLines = intValue(attr(child, "afterLines"), format.spacingAfterLines);
                format.lineSpacingTwips = intValue(attr(child, "line"), format.lineSpacingTwips);
                format.lineRule = attr(child, "lineRule");
            } else if ("snapToGrid".equals(name)) {
                format.snapToGridSet = true;
                format.snapToGrid = booleanValue(child, true);
            } else if ("pageBreakBefore".equals(name)) {
                format.pageBreakBeforeSet = true;
                format.pageBreakBefore = booleanValue(child, true);
            } else if ("keepNext".equals(name)) {
                format.keepNextSet = true;
                format.keepNext = booleanValue(child, true);
            } else if ("keepLines".equals(name)) {
                format.keepLinesSet = true;
                format.keepLines = booleanValue(child, true);
            } else if ("widowControl".equals(name)) {
                format.widowControlSet = true;
                format.widowControl = booleanValue(child, true);
            } else if ("autoSpaceDE".equals(name)) {
                format.autoSpaceDeSet = true;
                format.autoSpaceDe = booleanValue(child, true);
            } else if ("autoSpaceDN".equals(name)) {
                format.autoSpaceDnSet = true;
                format.autoSpaceDn = booleanValue(child, true);
            } else if ("wordWrap".equals(name)) {
                format.wordWrapSet = true;
                format.wordWrap = booleanValue(child, true);
            } else if ("overflowPunct".equals(name)) {
                format.overflowPunctSet = true;
                format.overflowPunct = booleanValue(child, true);
            } else if ("tabs".equals(name)) {
                for (Element tab : childElements(child)) {
                    if (!"tab".equals(localName(tab))) continue;
                    if ("right".equalsIgnoreCase(attr(tab, "val"))) {
                        format.rightTabTwips = intValue(attr(tab, "pos"), format.rightTabTwips);
                        format.tabLeader = attr(tab, "leader");
                    }
                }
            } else if ("numPr".equals(name)) {
                Element numId = directChild(child, "numId");
                Element ilvl = directChild(child, "ilvl");
                format.numId = numId == null ? format.numId : intValue(attr(numId, "val"), format.numId);
                format.ilvl = ilvl == null ? format.ilvl : intValue(attr(ilvl, "val"), format.ilvl);
            }
        }
        return format;
    }

    private DocxDocument.RunStyle parseRunProperties(Element rPr) {
        DocxDocument.RunStyle style = new DocxDocument.RunStyle();
        if (rPr == null) return style;
        for (Element child : childElements(rPr)) {
            String name = localName(child);
            if ("rFonts".equals(name)) {
                style.asciiFontFamily = nonEmpty(attr(child, "ascii"));
                style.highAnsiFontFamily = nonEmpty(attr(child, "hAnsi"));
                style.eastAsiaFontFamily = nonEmpty(attr(child, "eastAsia"));
                style.complexScriptFontFamily = nonEmpty(attr(child, "cs"));
                if (style.asciiFontFamily == null) style.asciiFontFamily = themeFont(child, "asciiTheme");
                if (style.highAnsiFontFamily == null) style.highAnsiFontFamily = themeFont(child, "hAnsiTheme");
                if (style.eastAsiaFontFamily == null) style.eastAsiaFontFamily = themeFont(child, "eastAsiaTheme");
                if (style.complexScriptFontFamily == null) style.complexScriptFontFamily = themeFont(child, "cstheme");
                style.fontFamily = style.eastAsiaFontFamily != null ? style.eastAsiaFontFamily
                        : style.asciiFontFamily != null ? style.asciiFontFamily
                        : style.highAnsiFontFamily;
            } else if ("sz".equals(name)) {
                style.fontSizeHalfPoints = intValue(attr(child, "val"), style.fontSizeHalfPoints);
            } else if ("szCs".equals(name)) {
                style.complexScriptSizeHalfPoints = intValue(attr(child, "val"), style.complexScriptSizeHalfPoints);
            } else if ("color".equals(name)) {
                String value = attr(child, "val");
                if (value.length() > 0 && !"auto".equalsIgnoreCase(value)) {
                    style.color = parseColor(value, 0xFF222222);
                    style.colorSet = true;
                }
            } else if ("b".equals(name)) {
                style.bold = booleanValue(child, true); style.boldSet = true;
            } else if ("i".equals(name)) {
                style.italic = booleanValue(child, true); style.italicSet = true;
            } else if ("u".equals(name)) {
                style.underline = !"none".equalsIgnoreCase(attr(child, "val")); style.underlineSet = true;
            } else if ("strike".equals(name)) {
                style.strike = booleanValue(child, true); style.strikeSet = true;
            } else if ("vertAlign".equals(name)) {
                String value = attr(child, "val");
                style.superscript = "superscript".equalsIgnoreCase(value); style.superscriptSet = true;
                style.subscript = "subscript".equalsIgnoreCase(value); style.subscriptSet = true;
            } else if ("position".equals(name)) {
                style.positionHalfPoints = intValue(attr(child, "val"), 0);
                style.positionSet = true;
            } else if ("highlight".equals(name)) {
                style.highlight = highlightColor(attr(child, "val"));
            }
        }
        return style;
    }

    private Document xml(String name) throws Exception {
        byte[] bytes = parts.get(name);
        if (bytes == null) throw new IllegalArgumentException("缺少 " + name);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) { }
        try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignored) { }
        try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignored) { }
        try { factory.setXIncludeAware(false); } catch (Exception ignored) { }
        try { factory.setExpandEntityReferences(false); } catch (Exception ignored) { }
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(bytes));
    }

    private boolean findPartWithPrefix(String prefix) {
        for (String name : parts.keySet()) if (name.startsWith(prefix)) return true;
        return false;
    }

    private String chooseExistingPart(String normalizedOrAbsolute, String rawTarget) {
        ArrayList<String> candidates = new ArrayList<String>();
        candidates.add(normalizePath(normalizedOrAbsolute));
        String raw = rawTarget == null ? "" : rawTarget;
        candidates.add(normalizePath(raw));
        candidates.add(normalizePath("word/" + raw));
        // Relationship targets are relative to word/, but a number of exporters
        // incorrectly write ../media/... or word/media/...; accept both without
        // guessing arbitrary package parts.
        String noParent = raw;
        while (noParent.startsWith("../")) noParent = noParent.substring(3);
        candidates.add(normalizePath("word/" + noParent));
        candidates.add(normalizePath(noParent));
        for (String candidate : candidates) if (parts.containsKey(candidate)) return candidate;
        // Last safe fallback for a media target: match the exact media filename.
        String file = raw.replace('\\', '/');
        int slash = file.lastIndexOf('/');
        file = slash < 0 ? file : file.substring(slash + 1);
        for (String part : parts.keySet())
            if (part.startsWith("word/media/") && part.endsWith("/" + file)) return part;
        return candidates.isEmpty() ? normalizePath(raw) : candidates.get(0);
    }

    private static String normalizePath(String value) {
        String path = value.replace('\\', '/');
        while (path.startsWith("/")) path = path.substring(1);
        ArrayList<String> segments = new ArrayList<String>();
        for (String segment : path.split("/")) {
            if (segment.length() == 0 || ".".equals(segment)) continue;
            if ("..".equals(segment)) {
                if (!segments.isEmpty()) segments.remove(segments.size() - 1);
            } else segments.add(segment);
        }
        StringBuilder out = new StringBuilder();
        for (String segment : segments) {
            if (out.length() > 0) out.append('/');
            out.append(segment);
        }
        return out.toString();
    }

    private static String stripExtension(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String base = slash >= 0 ? name.substring(slash + 1) : name;
        int dot = base.lastIndexOf('.');
        return dot > 0 ? base.substring(0, dot) : base;
    }

    private static String localName(Node node) {
        String local = node.getLocalName();
        if (local != null) return local;
        String value = node.getNodeName();
        int colon = value.indexOf(':');
        return colon >= 0 ? value.substring(colon + 1) : value;
    }

    private static String attr(Element element, String local) {
        if (element == null) return "";
        String value = element.getAttributeNS(W, local);
        if (value == null || value.length() == 0) value = element.getAttributeNS(R, local);
        if (value == null || value.length() == 0) value = element.getAttributeNS(REL, local);
        if (value == null || value.length() == 0) value = element.getAttributeNS("http://schemas.microsoft.com/office/word/2010/wordml", local);
        if (value == null || value.length() == 0) value = element.getAttributeNS("http://schemas.microsoft.com/office/word/2012/wordml", local);
        if (value == null || value.length() == 0) value = element.getAttribute(local);
        if (value == null || value.length() == 0) value = element.getAttribute("w:" + local);
        if (value == null || value.length() == 0) value = element.getAttribute("r:" + local);
        if (value == null) return "";
        return value;
    }

    /** A w:w attribute in twips. Word only writes dxa for table indents and cell margins. */
    private static int twipsAttribute(Element element, int fallback) {
        if (element == null) return fallback;
        String value = attr(element, "w");
        if (value.length() == 0) return fallback;
        try {
            return (int) Math.round(Double.parseDouble(value.trim()));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static ArrayList<Element> childElements(Element parent) {
        ArrayList<Element> result = new ArrayList<Element>();
        if (parent == null) return result;
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) result.add((Element) node);
        }
        return result;
    }

    private static ArrayList<Element> directChildren(Element parent, String name) {
        ArrayList<Element> result = new ArrayList<Element>();
        for (Element child : childElements(parent)) if (name.equals(localName(child))) result.add(child);
        return result;
    }

    private static Element directChild(Element parent, String name) {
        if (parent == null) return null;
        for (Element child : childElements(parent)) if (name.equals(localName(child))) return child;
        return null;
    }

    private static Element firstDescendant(Node parent, String name) {
        if (parent == null) return null;
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element element = (Element) node;
            if (name.equals(localName(element))) return element;
            Element nested = firstDescendant(element, name);
            if (nested != null) return nested;
        }
        return null;
    }

    private static Element lastDescendant(Node parent, String name) {
        Element found = null;
        if (parent == null) return null;
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element element = (Element) node;
            Element nested = lastDescendant(element, name);
            if (nested != null) found = nested;
            if (name.equals(localName(element))) found = element;
        }
        return found;
    }

    private static ArrayList<Element> descendantsNamed(Node parent, String name) {
        ArrayList<Element> result = new ArrayList<Element>();
        if (parent == null) return result;
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element element = (Element) node;
            if (name.equals(localName(element))) result.add(element);
            result.addAll(descendantsNamed(element, name));
        }
        return result;
    }

    private static String text(Node node) {
        return node == null ? "" : node.getTextContent();
    }

    private static String childValue(Element parent, String childName) {
        Element child = directChild(parent, childName);
        return child == null ? "" : attr(child, "val");
    }

    private static String nonEmpty(String value) { return value == null || value.length() == 0 ? null : value; }

    private static int intValue(String value, int fallback) {
        if (value == null || value.length() == 0) return fallback;
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return fallback; }
    }

    private static boolean isTrue(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value);
    }

    private static boolean booleanValue(Element element, boolean fallback) {
        if (element == null) return fallback;
        String value = attr(element, "val");
        if (value.length() == 0) return fallback;
        return !"0".equals(value) && !"false".equalsIgnoreCase(value) && !"off".equalsIgnoreCase(value) && !"none".equalsIgnoreCase(value);
    }

    private static int parseColor(String value, int fallback) {
        try { return 0xFF000000 | Integer.parseInt(value, 16); } catch (Exception ignored) { return fallback; }
    }

    private static int highlightColor(String value) {
        if ("yellow".equalsIgnoreCase(value)) return 0xFFFFFF80;
        if ("green".equalsIgnoreCase(value)) return 0xFFB7E1CD;
        if ("cyan".equalsIgnoreCase(value)) return 0xFFB2EBF2;
        if ("magenta".equalsIgnoreCase(value)) return 0xFFFFB7D5;
        if ("blue".equalsIgnoreCase(value)) return 0xFFB3D9FF;
        if ("red".equalsIgnoreCase(value)) return 0xFFFFB7B7;
        if ("gray".equalsIgnoreCase(value)) return 0xFFD6D6D6;
        return -1;
    }


}
