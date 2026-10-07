package com.rikkahub.wordlite;

import android.content.Context;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Writes edited text parts while streaming untouched OOXML package parts through. */
public final class DocxWriter {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String REL = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String RelationshipImage =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image";
    private static final String WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture";

    private DocxWriter() { }

    public static void write(Context context, Uri source, Uri target, DocxDocument document) throws Exception {
        if (source == null || target == null) throw new IllegalArgumentException("保存路径无效");
        if (source.toString().equals(target.toString())) throw new IllegalArgumentException("不能把文档保存到自身；请选择另存位置");
        InputStream input = context.getContentResolver().openInputStream(source);
        OutputStream output = context.getContentResolver().openOutputStream(target, "w");
        if (input == null || output == null) throw new IllegalArgumentException("无法创建输出文件");
        write(input, output, document);
    }

    /** Same serializer as Android SAF, accessible to JVM integration tests; closes both streams. */
    public static void write(InputStream input, OutputStream output, DocxDocument document) throws Exception {
        Map<String, byte[]> packageParts;
        try { packageParts = DocxZipReader.read(input); }
        finally { try { input.close(); } catch (Exception ignored) { } }
        ZipOutputStream out = new ZipOutputStream(output);
        boolean hadComments = packageParts.containsKey("word/comments.xml");
        boolean hadRelationships = packageParts.containsKey("word/_rels/document.xml.rels");
        boolean hadContentTypes = packageParts.containsKey("[Content_Types].xml");
        ArrayList<DocxDocument.EmbeddedImage> images = newImages(document);
        boolean needsSettings = document.trackRevisions || !document.comments.isEmpty() || packageParts.containsKey("word/settings.xml");
        try {
            for (Map.Entry<String, byte[]> entry : packageParts.entrySet()) {
                String name = entry.getKey();
                byte[] original = entry.getValue();
                if ("word/document.xml".equals(name)) {
                    put(out, name, documentXml(document).getBytes(StandardCharsets.UTF_8));
                } else if ("word/settings.xml".equals(name)) {
                    put(out, name, reviewSettings(original, document).getBytes(StandardCharsets.UTF_8));
                } else if ("word/commentsExtended.xml".equals(name)) {
                    put(out, name, commentsExtendedXml(document).getBytes(StandardCharsets.UTF_8));
                } else if ("word/comments.xml".equals(name)) {
                    put(out, name, commentsXml(document).getBytes(StandardCharsets.UTF_8));
                } else if ("word/_rels/document.xml.rels".equals(name)
                        && (!document.comments.isEmpty() || !images.isEmpty() || needsSettings)) {
                    String updated = new String(original, StandardCharsets.UTF_8);
                    if (!document.comments.isEmpty()) updated = addExtendedCommentRelationship(addCommentRelationship(updated));
                    updated = addImageRelationships(updated, images);
                    if (needsSettings) updated = settingsRelationship(updated);
                    put(out, name, updated.getBytes(StandardCharsets.UTF_8));
                } else if ("[Content_Types].xml".equals(name)
                        && (!document.comments.isEmpty() || !images.isEmpty() || needsSettings)) {
                    String updated = new String(original, StandardCharsets.UTF_8);
                    if (!document.comments.isEmpty()) updated = addExtendedCommentContentType(addCommentContentType(updated));
                    updated = addImageContentTypes(updated, images);
                    if (needsSettings) updated = settingsContentType(updated);
                    put(out, name, updated.getBytes(StandardCharsets.UTF_8));
                } else {
                    put(out, name, original);
                }
            }
            if (!document.comments.isEmpty()) {
                if (!hadComments) put(out, "word/comments.xml", commentsXml(document).getBytes(StandardCharsets.UTF_8));
                if (!hadRelationships) put(out, "word/_rels/document.xml.rels", settingsRelationship(addExtendedCommentRelationship(newRelationships())).getBytes(StandardCharsets.UTF_8));
                if (!hadContentTypes) put(out, "[Content_Types].xml", settingsContentType(addExtendedCommentContentType(newContentTypes())).getBytes(StandardCharsets.UTF_8));
            }
            if (!document.comments.isEmpty() && !packageParts.containsKey("word/commentsExtended.xml"))
                put(out, "word/commentsExtended.xml", commentsExtendedXml(document).getBytes(StandardCharsets.UTF_8));
            if (!packageParts.containsKey("word/settings.xml") && (document.trackRevisions || !document.comments.isEmpty()))
                put(out, "word/settings.xml", reviewSettings(null, document).getBytes(StandardCharsets.UTF_8));
            if (document.comments.isEmpty() && needsSettings) {
                if (!hadRelationships) put(out, "word/_rels/document.xml.rels", settingsRelationship("<Relationships xmlns=\"" + REL + "\"></Relationships>").getBytes(StandardCharsets.UTF_8));
                if (!hadContentTypes) put(out, "[Content_Types].xml", settingsContentType(newContentTypes()).getBytes(StandardCharsets.UTF_8));
            }
            for (DocxDocument.EmbeddedImage image : images) put(out, image.partName, image.bytes);
            out.finish();
        } finally {
            try { out.close(); } catch (Exception ignored) { }
            try { input.close(); } catch (Exception ignored) { }
            try { output.close(); } catch (Exception ignored) { }
        }
    }

    /** New images to write as additional parts. */
    private static ArrayList<DocxDocument.EmbeddedImage> newImages(DocxDocument document) {
        ArrayList<DocxDocument.EmbeddedImage> images = new ArrayList<DocxDocument.EmbeddedImage>();
        for (DocxDocument.Block block : document.blocks) {
            if (!(block instanceof DocxDocument.ParagraphBlock)) continue;
            for (DocxDocument.EmbeddedImage image : ((DocxDocument.ParagraphBlock) block).images)
                if (image.isNew && image.bytes != null && image.partName != null) images.add(image);
        }
        return images;
    }

    private static String addImageRelationships(String source, ArrayList<DocxDocument.EmbeddedImage> images) {
        StringBuilder extra = new StringBuilder();
        for (DocxDocument.EmbeddedImage image : images) {
            if (source.contains("Id=\"" + image.relationshipId + "\"")) continue;
            extra.append("<Relationship Id=\"").append(image.relationshipId)
                    .append("\" Type=\"").append(RelationshipImage).append("\" Target=\"")
                    .append(targetFromPart(image.partName)).append("\"/>");
        }
        if (extra.length() == 0) return source;
        int at = source.indexOf("</Relationships>");
        if (at < 0) return source;
        return source.substring(0, at) + extra + source.substring(at);
    }

    private static String addImageContentTypes(String source, ArrayList<DocxDocument.EmbeddedImage> images) {
        HashSet<String> extensions = new HashSet<String>();
        for (DocxDocument.EmbeddedImage image : images) extensions.add(extensionOf(image.partName));
        StringBuilder extra = new StringBuilder();
        for (String ext : extensions) {
            if (source.contains("Extension=\"" + ext + "\"")) continue;
            String mime = "png".equals(ext) ? "image/png"
                    : "jpg".equals(ext) || "jpeg".equals(ext) ? "image/jpeg"
                    : "gif".equals(ext) ? "image/gif" : "image/png";
            extra.append("<Default Extension=\"").append(ext).append("\" ContentType=\"").append(mime).append("\"/>");
        }
        if (extra.length() == 0) return source;
        int at = source.indexOf("</Types>");
        if (at < 0) return source;
        return source.substring(0, at) + extra + source.substring(at);
    }

    /** Relationship targets are relative to the source part folder ("word/"). */
    private static String targetFromPart(String partName) {
        return partName.startsWith("word/") ? partName.substring(5) : partName;
    }

    private static String extensionOf(String partName) {
        int dot = partName.lastIndexOf('.');
        return dot >= 0 ? partName.substring(dot + 1).toLowerCase(Locale.US) : "png";
    }

    private static byte[] readEntry(InputStream input, byte[] buffer) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static void put(ZipOutputStream out, String name, byte[] bytes) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        out.putNextEntry(entry);
        out.write(bytes);
        out.closeEntry();
    }

    private static String documentXml(DocxDocument document) {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?> ");
        xml.append("<w:document xmlns:w=\"").append(W)
                .append("\" xmlns:r=\"").append(R)
                .append("\" xmlns:wp=\"").append(WP)
                .append("\" xmlns:a=\"").append(A)
                .append("\" xmlns:pic=\"").append(PIC).append("\"").append(document.originalRootAttributes).append(">");
        xml.append("<w:body>");
        for (int i = 0; i < document.blocks.size(); i++) {
            DocxDocument.Block block = document.blocks.get(i);
            if (block instanceof DocxDocument.ParagraphBlock) {
                DocxDocument.SectionSettings boundary = null;
                if (i + 1 < document.blocks.size()) {
                    DocxDocument.Block next = document.blocks.get(i + 1);
                    if (next.sectionIndex > block.sectionIndex) {
                        // sectPr in a paragraph's pPr describes the section that
                        // ends at this paragraph, not the following section.
                        boundary = sectionAt(document, block.sectionIndex);
                    }
                }
                xml.append(paragraphXml((DocxDocument.ParagraphBlock) block, document, boundary));
            } else if (block instanceof DocxDocument.TableBlock) {
                xml.append(OoxmlPreserver.unchanged(block, document) ? block.originalXml
                        : tableXml((DocxDocument.TableBlock) block, document));
            } else if (block instanceof DocxDocument.OpaqueBlock) {
                xml.append(block.originalXml);
            }
        }
        DocxDocument.SectionSettings lastSection = sectionAt(document, document.sections.size() - 1);
        xml.append(!document.originalFinalSection.isEmpty()
                && document.originalFinalSectionSignature.equals(OoxmlPreserver.sectionSignature(lastSection))
                ? document.originalFinalSection : sectionPropertiesXml(lastSection));
        xml.append("</w:body></w:document>");
        return xml.toString();
    }

    private static String paragraphXml(DocxDocument.ParagraphBlock paragraph, DocxDocument document) {
        return paragraphXml(paragraph, document, null);
    }

    private static String paragraphXml(DocxDocument.ParagraphBlock paragraph, DocxDocument document,
                                       DocxDocument.SectionSettings sectionBoundary) {
        if (OoxmlPreserver.unchanged(paragraph, document)) return paragraph.originalXml;
        StringBuilder xml = new StringBuilder("<w:p>");
        String properties = paragraphProperties(paragraph, sectionBoundary);
        for (DocxDocument.Revision revision : document.revisions) {
            if (revision.paragraphIndex != paragraph.index || revision.kind != DocxDocument.Revision.Kind.PARAGRAPH_FORMAT) continue;
            DocxDocument.ParagraphBlock previous = new DocxDocument.ParagraphBlock();
            if (revision.previousParagraphFormat != null) previous.format.set(revision.previousParagraphFormat);
            String old = revision.previousPropertiesXml.isEmpty() ? paragraphProperties(previous) : revision.previousPropertiesXml;
            if (old.isEmpty()) old = "<w:pPr/>";
            String change = "<w:pPrChange" + revisionAttributes(revision) + ">" + old + "</w:pPrChange>";
            properties = properties.isEmpty() ? "<w:pPr>" + change + "</w:pPr>"
                    : properties.replace("</w:pPr>", change + "</w:pPr>");
        }
        xml.append(properties);
        xml.append(richTextXml(paragraph, document));
        for (DocxDocument.EmbeddedImage image : paragraph.images) xml.append(imageXml(image));
        xml.append("</w:p>");
        return xml.toString();
    }

    /**
     * Emits the visible paragraph text with Word's standard field and comment
     * anchors. Field position uses explicit visible offsets recorded during parsing; this
     * keeps PAGE/DATE usable in Word without pretending to be a full field engine.
     */
    private static String richTextXml(DocxDocument.ParagraphBlock paragraph, DocxDocument document) {
        String text = paragraph.text == null ? "" : paragraph.text;
        int length = text.length();
        TreeSet<Integer> points = new TreeSet<Integer>();
        HashMap<Integer, ArrayList<String>> openMarkers = new HashMap<Integer, ArrayList<String>>();
        HashMap<Integer, ArrayList<String>> closeMarkers = new HashMap<Integer, ArrayList<String>>();
        points.add(0);
        points.add(length);
        // Preserve EVERY original run boundary, even if no comment/field is present there.
        int runEnd = 0;
        for (DocxDocument.Run run : paragraph.runs) {
            runEnd += run.text.length();
            points.add(clamp(runEnd, 0, length));
        }
        for (DocxDocument.RangeStyle range : paragraph.editedStyles) {
            points.add(clamp(range.start, 0, length)); points.add(clamp(range.end, 0, length));
        }

        for (DocxDocument.Comment comment : document.comments) {
            if (comment.parentId >= 0 || comment.paragraphIndex != paragraph.index) continue;
            int start = clamp(comment.start, 0, length);
            int end = clamp(comment.end, 0, length);
            if (end < start) continue;
            if (end == start) { add(openMarkers, start, "<w:r><w:commentReference w:id=\"" + comment.id + "\"/></w:r>"); points.add(start); continue; }
            points.add(start);
            points.add(end);
            add(openMarkers, start, "<w:commentRangeStart w:id=\"" + comment.id + "\"/>");
            add(closeMarkers, end, "<w:commentRangeEnd w:id=\"" + comment.id
                    + "\"/><w:r><w:rPr><w:rStyle w:val=\"CommentReference\"/></w:rPr><w:commentReference w:id=\""
                    + comment.id + "\"/></w:r>");
        }

        for (DocxDocument.Hyperlink link : paragraph.hyperlinks) {
            int start = clamp(link.start, 0, length), end = clamp(link.end, start, length);
            if (end <= start) continue;
            points.add(start); points.add(end);
            String open = "<w:hyperlink";
            if (!link.relationshipId.isEmpty()) open += " r:id=\"" + xmlEscape(link.relationshipId) + "\"";
            if (!link.anchor.isEmpty()) open += " w:anchor=\"" + xmlEscape(link.anchor) + "\"";
            add(openMarkers, start, open + ">"); add(closeMarkers, end, "</w:hyperlink>");
        }

        for (DocxDocument.Revision revision : document.revisions) {
            if (revision.paragraphIndex != paragraph.index || revision.kind == DocxDocument.Revision.Kind.PARAGRAPH_FORMAT) continue;
            points.add(clamp(revision.start, 0, length)); points.add(clamp(revision.end, 0, length));
            if (revision.kind == DocxDocument.Revision.Kind.FORMAT) {
                int at = revision.start;
                for (DocxDocument.Run previous : revision.previousRuns) { at += previous.text.length(); points.add(clamp(at, 0, length)); }
            }
        }
        int fallbackCursor = 0;
        for (DocxDocument.FieldCode field : paragraph.fields) {
            int start = field.start;
            int end = field.end;
            if (start < 0 || end < start) {
                start = field.cachedResult.isEmpty() ? length : text.indexOf(field.cachedResult, fallbackCursor);
                if (start < 0) continue; // Do not invent a second result at the paragraph end.
                end = start + field.cachedResult.length();
            }
            start = clamp(start, 0, length); end = clamp(end, start, length);
            fallbackCursor = Math.max(fallbackCursor, end);
            points.add(start); points.add(end);
            if (start == end) add(openMarkers, start, fieldOpen(field) + fieldClose());
            else {
                add(openMarkers, start, fieldOpen(field));
                add(closeMarkers, end, fieldClose());
            }
        }

        StringBuilder xml = new StringBuilder();
        int cursor = 0;
        for (Integer point : points) {
            int at = Math.min(point, length);
            if (at > cursor) {
                xml.append(revisionRunXml(text.substring(cursor, at), cursor, paragraph, document));
                cursor = at;
            }
            for (DocxDocument.Revision revision : document.revisions) {
                if (revision.paragraphIndex == paragraph.index && revision.kind == DocxDocument.Revision.Kind.DELETE
                        && clamp(revision.start, 0, length) == at) {
                    xml.append("<w:del").append(revisionAttributes(revision)).append('>');
                    if (revision.previousRuns.isEmpty()) xml.append(runXml(new DocxDocument.Run(revision.text, styleAt(paragraph, at)))
                            .replace("<w:t ", "<w:delText ").replace("</w:t>", "</w:delText>"));
                    for (DocxDocument.Run previous : revision.previousRuns)
                        xml.append(runXml(previous).replace("<w:t ", "<w:delText ").replace("</w:t>", "</w:delText>"));
                    xml.append("</w:del>");
                }
            }
            appendMarkers(xml, closeMarkers.get(at));
            appendMarkers(xml, openMarkers.get(at));
        }
        if (cursor < length) xml.append(revisionRunXml(text.substring(cursor), cursor, paragraph, document));
        return xml.toString();
    }

    private static String revisionAttributes(DocxDocument.Revision revision) {
        return " w:id=\"" + revision.id + "\" w:author=\"" + xmlEscape(revision.author)
                + "\" w:date=\"" + xmlEscape(revision.date.isEmpty() ? ReviewManager.now() : revision.date) + "\"";
    }
    private static String revisionRunXml(String text, int offset, DocxDocument.ParagraphBlock paragraph, DocxDocument document) {
        String result = runXml(new DocxDocument.Run(text, styleAt(paragraph, offset)));
        DocxDocument.Revision insertion = null, format = null;
        for (DocxDocument.Revision revision : document.revisions) {
            if (revision.paragraphIndex != paragraph.index || offset < revision.start || offset >= revision.end) continue;
            if (revision.kind == DocxDocument.Revision.Kind.INSERT) insertion = revision;
            if (revision.kind == DocxDocument.Revision.Kind.FORMAT) format = revision;
        }
        if (format != null) {
            String old = format.previousPropertiesXml;
            if (old.isEmpty()) {
                DocxDocument.RunStyle previous = paragraph.baseRunStyle; int cursor = format.start;
                for (DocxDocument.Run run : format.previousRuns) { if (offset < cursor + run.text.length()) { previous = run.style; break; } cursor += run.text.length(); }
                String run = runXml(new DocxDocument.Run("", previous)); int start = run.indexOf("<w:rPr>"), end = run.indexOf("</w:rPr>");
                old = start >= 0 ? run.substring(start, end + 8) : "<w:rPr/>";
            }
            String change = "<w:rPrChange" + revisionAttributes(format) + ">" + old + "</w:rPrChange>";
            result = result.contains("</w:rPr>") ? result.replace("</w:rPr>", change + "</w:rPr>")
                    : result.replace("<w:r>", "<w:r><w:rPr>" + change + "</w:rPr>");
        }
        if (insertion != null) result = "<w:ins" + revisionAttributes(insertion) + ">" + result + "</w:ins>";
        return result;
    }

    private static String fieldOpen(DocxDocument.FieldCode field) {
        return "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>"
                + "<w:r><w:instrText xml:space=\"preserve\"> " + xmlEscape(field.instruction) + " </w:instrText></w:r>"
                + "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>";
    }

    private static String fieldClose() {
        return "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>";
    }

    private static void appendMarkers(StringBuilder xml, ArrayList<String> markers) {
        if (markers == null) return;
        for (String marker : markers) xml.append(marker);
    }

    private static void add(Map<Integer, ArrayList<String>> map, int position, String xml) {
        ArrayList<String> list = map.get(position);
        if (list == null) {
            list = new ArrayList<String>();
            map.put(position, list);
        }
        list.add(xml);
    }

    private static DocxDocument.RunStyle styleAt(DocxDocument.ParagraphBlock paragraph, int offset) {
        if (paragraph.runs == null || paragraph.runs.isEmpty()) return new DocxDocument.RunStyle();
        int cursor = 0;
        for (DocxDocument.Run run : paragraph.runs) {
            int length = run.text == null ? 0 : run.text.length();
            if (offset >= cursor && offset < cursor + Math.max(1, length)) return run.style.copy();
            cursor += length;
        }
        return paragraph.runs.get(paragraph.runs.size() - 1).style.copy();
    }

    private static String paragraphProperties(DocxDocument.ParagraphBlock paragraph) {
        return paragraphProperties(paragraph, null);
    }

    private static String paragraphProperties(DocxDocument.ParagraphBlock paragraph,
                                              DocxDocument.SectionSettings sectionBoundary) {
        DocxDocument.ParagraphFormat format = paragraph.format;
        StringBuilder xml = new StringBuilder();
        boolean used = false;
        if (paragraph.styleId != null && paragraph.styleId.length() > 0) used = true;
        if (format.alignment >= 0 || format.leftIndentTwips >= 0 || format.rightIndentTwips >= 0
                || format.firstLineIndentTwips != -1 || format.spacingBeforeTwips >= 0 || format.spacingAfterTwips >= 0
                || format.spacingBeforeLines >= 0 || format.spacingAfterLines >= 0
                || format.lineSpacingTwips >= 0 || format.snapToGridSet || format.numId >= 0 || format.pageBreakBeforeSet
                || format.keepNextSet || format.keepLinesSet || format.widowControlSet
                || format.rightTabTwips >= 0 || format.autoSpaceDeSet || format.autoSpaceDnSet
                || format.wordWrapSet || format.overflowPunctSet) used = true;
        if (sectionBoundary != null) used = true;
        if (!used) return "";
        xml.append("<w:pPr>");
        if (paragraph.styleId != null && paragraph.styleId.length() > 0) {
            xml.append("<w:pStyle w:val=\"").append(xmlEscape(paragraph.styleId)).append("\"/>");
        }
        if (format.alignment >= 0) {
            String value = format.alignment == 1 ? "center" : format.alignment == 2 ? "right" : format.alignment == 3 ? "both" : "left";
            xml.append("<w:jc w:val=\"").append(value).append("\"/>");
        }
        if (format.leftIndentTwips >= 0 || format.rightIndentTwips >= 0 || format.firstLineIndentTwips != -1) {
            xml.append("<w:ind");
            if (format.leftIndentTwips >= 0) xml.append(" w:left=\"").append(format.leftIndentTwips).append("\"");
            if (format.rightIndentTwips >= 0) xml.append(" w:right=\"").append(format.rightIndentTwips).append("\"");
            if (format.firstLineIndentTwips >= 0) xml.append(" w:firstLine=\"").append(format.firstLineIndentTwips).append("\"");
            if (format.firstLineIndentTwips < -1) xml.append(" w:hanging=\"").append(-format.firstLineIndentTwips).append("\"");
            xml.append("/>");
        }
        if (format.spacingBeforeTwips >= 0 || format.spacingAfterTwips >= 0
                || format.spacingBeforeLines >= 0 || format.spacingAfterLines >= 0
                || format.lineSpacingTwips >= 0) {
            xml.append("<w:spacing");
            if (format.spacingBeforeTwips >= 0) xml.append(" w:before=\"").append(format.spacingBeforeTwips).append("\"");
            if (format.spacingAfterTwips >= 0) xml.append(" w:after=\"").append(format.spacingAfterTwips).append("\"");
            if (format.spacingBeforeLines >= 0) xml.append(" w:beforeLines=\"").append(format.spacingBeforeLines).append("\"");
            if (format.spacingAfterLines >= 0) xml.append(" w:afterLines=\"").append(format.spacingAfterLines).append("\"");
            if (format.lineSpacingTwips >= 0) xml.append(" w:line=\"").append(format.lineSpacingTwips).append("\"");
            if (format.lineRule != null && format.lineRule.length() > 0) xml.append(" w:lineRule=\"").append(xmlEscape(format.lineRule)).append("\"");
            xml.append("/>");
        }
        if (format.autoSpaceDeSet)
            xml.append("<w:autoSpaceDE").append(format.autoSpaceDe ? "/>" : " w:val=\"0\"/>");
        if (format.autoSpaceDnSet)
            xml.append("<w:autoSpaceDN").append(format.autoSpaceDn ? "/>" : " w:val=\"0\"/>");
        if (format.wordWrapSet)
            xml.append("<w:wordWrap").append(format.wordWrap ? "/>" : " w:val=\"0\"/>");
        if (format.overflowPunctSet)
            xml.append("<w:overflowPunct").append(format.overflowPunct ? "/>" : " w:val=\"0\"/>");
        if (format.rightTabTwips >= 0) {
            xml.append("<w:tabs><w:tab w:val=\"right\"");
            if (format.tabLeader != null && format.tabLeader.length() > 0)
                xml.append(" w:leader=\"").append(xmlEscape(format.tabLeader)).append("\"");
            xml.append(" w:pos=\"").append(format.rightTabTwips).append("\"/></w:tabs>");
        }
        if (format.numId >= 0) {
            xml.append("<w:numPr><w:ilvl w:val=\"").append(format.ilvl).append("\"/><w:numId w:val=\"").append(format.numId).append("\"/></w:numPr>");
        }
        if (format.pageBreakBeforeSet) xml.append("<w:pageBreakBefore w:val=\"").append(format.pageBreakBefore ? "1" : "0").append("\"/>");
        if (format.keepNextSet) xml.append("<w:keepNext w:val=\"").append(format.keepNext ? "1" : "0").append("\"/>");
        if (format.keepLinesSet) xml.append("<w:keepLines w:val=\"").append(format.keepLines ? "1" : "0").append("\"/>");
        if (format.widowControlSet) xml.append("<w:widowControl w:val=\"").append(format.widowControl ? "1" : "0").append("\"/>");
        if (format.snapToGridSet) xml.append("<w:snapToGrid w:val=\"").append(format.snapToGrid ? "1" : "0").append("\"/>");
        if (sectionBoundary != null) xml.append(sectionPropertiesXml(sectionBoundary));
        xml.append("</w:pPr>");
        return xml.toString();
    }

    private static DocxDocument.SectionSettings sectionAt(DocxDocument document, int index) {
        if (document == null || document.sections.isEmpty())
            return document == null ? new DocxDocument.SectionSettings() : document.section;
        return document.sections.get(Math.max(0, Math.min(index, document.sections.size() - 1)));
    }

    private static String sectionPropertiesXml(DocxDocument.SectionSettings section) {
        if (section == null) section = new DocxDocument.SectionSettings();
        StringBuilder xml = new StringBuilder("<w:sectPr>");
        if (section.headerRelationshipId != null && section.headerRelationshipId.length() > 0)
            xml.append("<w:headerReference w:type=\"default\" r:id=\"")
                    .append(xmlEscape(section.headerRelationshipId)).append("\"/>");
        if (section.footerRelationshipId != null && section.footerRelationshipId.length() > 0)
            xml.append("<w:footerReference w:type=\"default\" r:id=\"")
                    .append(xmlEscape(section.footerRelationshipId)).append("\"/>");
        if (section.hasPageSize()) {
            xml.append("<w:pgSz w:w=\"").append(section.pageWidthTwips)
                    .append("\" w:h=\"").append(section.pageHeightTwips).append("\"");
            if (section.landscape) xml.append(" w:orient=\"landscape\"");
            xml.append("/>");
        }
        boolean hasMargins = section.marginTopTwips >= 0 || section.marginBottomTwips >= 0
                || section.marginLeftTwips >= 0 || section.marginRightTwips >= 0
                || section.gutterTwips > 0
                || section.headerDistanceTwips >= 0 || section.footerDistanceTwips >= 0;
        if (hasMargins) {
            xml.append("<w:pgMar");
            if (section.marginTopTwips >= 0) xml.append(" w:top=\"").append(section.marginTopTwips).append("\"");
            if (section.marginRightTwips >= 0) xml.append(" w:right=\"").append(section.marginRightTwips).append("\"");
            if (section.marginBottomTwips >= 0) xml.append(" w:bottom=\"").append(section.marginBottomTwips).append("\"");
            if (section.marginLeftTwips >= 0) xml.append(" w:left=\"").append(section.marginLeftTwips).append("\"");
            if (section.gutterTwips > 0) xml.append(" w:gutter=\"").append(section.gutterTwips).append("\"");
            if (section.headerDistanceTwips >= 0) xml.append(" w:header=\"").append(section.headerDistanceTwips).append("\"");
            if (section.footerDistanceTwips >= 0) xml.append(" w:footer=\"").append(section.footerDistanceTwips).append("\"");
            xml.append("/>");
        }
        if (section.lineGridPitchTwips > 0) {
            xml.append("<w:docGrid w:linePitch=\"").append(section.lineGridPitchTwips).append("\"/>");
        }
        if (section.pageNumberFormat != null && section.pageNumberFormat.length() > 0
                && (!"decimal".equals(section.pageNumberFormat) || section.pageNumberStart != 1)) {
            xml.append("<w:pgNumType w:fmt=\"").append(xmlEscape(section.pageNumberFormat)).append("\"");
            if (section.pageNumberStart != 1) xml.append(" w:start=\"").append(section.pageNumberStart).append("\"");
            xml.append("/>");
        }
        return xml.append("</w:sectPr>").toString();
    }

    private static String runXml(DocxDocument.Run run) {
        StringBuilder xml = new StringBuilder("<w:r>");
        DocxDocument.RunStyle style = run.style == null ? new DocxDocument.RunStyle() : run.style;
        if (style.fontFamily != null || style.asciiFontFamily != null || style.highAnsiFontFamily != null
                || style.eastAsiaFontFamily != null || style.complexScriptFontFamily != null
                || style.fontSizeHalfPoints > 0 || style.complexScriptSizeHalfPoints > 0 || style.positionSet
                || style.boldSet || style.italicSet || style.underlineSet || style.colorSet
                || style.strikeSet || style.superscriptSet || style.subscriptSet || style.highlight >= 0) {
            xml.append("<w:rPr>");
            if (style.fontFamily != null || style.asciiFontFamily != null || style.highAnsiFontFamily != null
                    || style.eastAsiaFontFamily != null || style.complexScriptFontFamily != null) {
                xml.append("<w:rFonts");
                appendFontAttr(xml, "ascii", style.asciiFontFamily != null ? style.asciiFontFamily : style.fontFamily);
                appendFontAttr(xml, "hAnsi", style.highAnsiFontFamily != null ? style.highAnsiFontFamily : style.fontFamily);
                appendFontAttr(xml, "eastAsia", style.eastAsiaFontFamily != null ? style.eastAsiaFontFamily : style.fontFamily);
                appendFontAttr(xml, "cs", style.complexScriptFontFamily);
                xml.append("/>");
            }
            if (style.fontSizeHalfPoints > 0) xml.append("<w:sz w:val=\"").append(style.fontSizeHalfPoints).append("\"/>");
            if (style.complexScriptSizeHalfPoints > 0) xml.append("<w:szCs w:val=\"").append(style.complexScriptSizeHalfPoints).append("\"/>");
            if (style.positionSet) xml.append("<w:position w:val=\"").append(style.positionHalfPoints).append("\"/>");
            if (style.boldSet) xml.append("<w:b w:val=\"").append(style.bold ? "1" : "0").append("\"/>");
            if (style.italicSet) xml.append("<w:i w:val=\"").append(style.italic ? "1" : "0").append("\"/>");
            if (style.underlineSet) xml.append("<w:u w:val=\"").append(style.underline ? "single" : "none").append("\"/>");
            if (style.strikeSet) xml.append("<w:strike w:val=\"").append(style.strike ? "1" : "0").append("\"/>");
            if (style.superscriptSet && style.superscript) xml.append("<w:vertAlign w:val=\"superscript\"/>");
            if (style.subscriptSet && style.subscript) xml.append("<w:vertAlign w:val=\"subscript\"/>");
            if (style.highlight >= 0) xml.append("<w:shd w:val=\"clear\" w:fill=\"")
                    .append(String.format(Locale.US, "%06X", style.highlight & 0xFFFFFF)).append("\"/>");
            if ((style.superscriptSet || style.subscriptSet) && !style.superscript && !style.subscript)
                xml.append("<w:vertAlign w:val=\"baseline\"/>");
            if (style.colorSet) xml.append("<w:color w:val=\"").append(String.format(Locale.US, "%06X", style.color & 0xFFFFFF)).append("\"/>");
            xml.append("</w:rPr>");
        }
        xml.append(textRuns(run.text == null ? "" : run.text));
        xml.append("</w:r>");
        return xml.toString();
    }

    private static void appendFontAttr(StringBuilder xml, String name, String value) {
        if (value != null && value.length() > 0)
            xml.append(" w:").append(name).append("=\"").append(xmlEscape(value)).append("\"");
    }

    private static String textRuns(String value) {
        StringBuilder xml = new StringBuilder();
        if (value.length() == 0) return "<w:t xml:space=\"preserve\"></w:t>";
        String[] lines = value.split("\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) xml.append("<w:br/>");
            String[] pieces = lines[i].split("\\t", -1);
            for (int j = 0; j < pieces.length; j++) {
                if (j > 0) xml.append("<w:tab/>");
                if (!pieces[j].isEmpty()) xml.append("<w:t xml:space=\"preserve\">").append(xmlEscape(pieces[j])).append("</w:t>");
            }
        }
        return xml.toString();
    }

    private static String imageXml(DocxDocument.EmbeddedImage image) {
        if (image == null || image.relationshipId == null || image.relationshipId.length() == 0) return "";
        int cx = image.widthEmu > 0 ? image.widthEmu : 3657600;
        int cy = image.heightEmu > 0 ? image.heightEmu : 2743200;
        String name = image.partName == null || image.partName.length() == 0 ? "picture" : image.partName;
        return "<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">"
                + "<wp:extent cx=\"" + cx + "\" cy=\"" + cy + "\"/>"
                + "<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>"
                + "<wp:docPr id=\"1\" name=\"" + xmlEscape(name) + "\"/>"
                + "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">"
                + "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"0\" name=\"" + xmlEscape(name) + "\"/>"
                + "<pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"" + xmlEscape(image.relationshipId)
                + "\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/>"
                + "<a:ext cx=\"" + cx + "\" cy=\"" + cy + "\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom>"
                + "</pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>";
    }

    private static String tableXml(DocxDocument.TableBlock table, DocxDocument document) {
        StringBuilder xml = new StringBuilder("<w:tbl>");
        xml.append("<w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/><w:tblBorders><w:top w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" +
                "<w:left w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/><w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" +
                "<w:right w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/><w:insideH w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" +
                "<w:insideV w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/></w:tblBorders></w:tblPr>");
        for (ArrayList<DocxDocument.Cell> row : table.rows) {
            xml.append("<w:tr>");
            for (DocxDocument.Cell cell : row) {
                xml.append("<w:tc><w:tcPr><w:tcW w:w=\"0\" w:type=\"auto\"/></w:tcPr>");
                for (DocxDocument.ParagraphBlock paragraph : cell.paragraphs) xml.append(paragraphXml(paragraph, document));
                if (cell.paragraphs.isEmpty()) xml.append("<w:p/>");
                xml.append("</w:tc>");
            }
            xml.append("</w:tr>");
        }
        xml.append("</w:tbl>");
        return xml.toString();
    }

    private static String commentsXml(DocxDocument document) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:comments xmlns:w=\"");
        xml.append(W).append("\" xmlns:w14=\"http://schemas.microsoft.com/office/word/2010/wordml\">");
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String date = format.format(new Date());
        for (DocxDocument.Comment comment : document.comments) {
            xml.append("<w:comment w:id=\"").append(comment.id).append("\" w:author=\"")
                    .append(xmlEscape(comment.author)).append("\" w:date=\"").append(xmlEscape(comment.date.isEmpty() ? date : comment.date)).append("\">")
                    .append("<w:p w14:paraId=\"").append(comment.paraId.isEmpty() ? ReviewManager.paraId(comment.id) : xmlEscape(comment.paraId)).append("\"><w:r><w:t xml:space=\"preserve\">").append(xmlEscape(comment.text))
                    .append("</w:t></w:r></w:p></w:comment>");
        }
        return xml.append("</w:comments>").toString();
    }

    private static String commentsExtendedXml(DocxDocument document) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><w15:commentsEx xmlns:w15=\"http://schemas.microsoft.com/office/word/2012/wordml\">");
        for (DocxDocument.Comment comment : document.comments) {
            String para = comment.paraId.isEmpty() ? ReviewManager.paraId(comment.id) : comment.paraId;
            xml.append("<w15:commentEx w15:paraId=\"").append(xmlEscape(para)).append("\" w15:done=\"").append(comment.resolved ? '1' : '0').append('"');
            if (comment.parentId >= 0) for (DocxDocument.Comment parent : document.comments) if (parent.id == comment.parentId)
                xml.append(" w15:paraIdParent=\"").append(xmlEscape(parent.paraId.isEmpty() ? ReviewManager.paraId(parent.id) : parent.paraId)).append('"');
            xml.append("/>");
        }
        return xml.append("</w15:commentsEx>").toString();
    }
    private static String addExtendedCommentRelationship(String source) {
        if (source.contains("/relationships/commentsExtended")) return source;
        int at = source.lastIndexOf("</Relationships>"); if (at < 0) return source;
        String value = "<Relationship Id=\"" + unusedRelationshipId(source) + "\" Type=\"http://schemas.microsoft.com/office/2011/relationships/commentsExtended\" Target=\"commentsExtended.xml\"/>";
        return source.substring(0, at) + value + source.substring(at);
    }
    private static String addExtendedCommentContentType(String source) {
        if (source.contains("/word/commentsExtended.xml")) return source;
        int at = source.lastIndexOf("</Types>"); if (at < 0) return source;
        return source.substring(0, at) + "<Override PartName=\"/word/commentsExtended.xml\" ContentType=\"application/vnd.ms-word.commentsExtended+xml\"/>" + source.substring(at);
    }
    private static String settingsRelationship(String source) {
        if (source.contains("/relationships/settings")) return source;
        int at = source.lastIndexOf("</Relationships>"); if (at < 0) return source;
        return source.substring(0, at) + "<Relationship Id=\"" + unusedRelationshipId(source) + "\" Type=\"" + R + "/settings\" Target=\"settings.xml\"/>" + source.substring(at);
    }
    private static String settingsContentType(String source) {
        if (source.contains("/word/settings.xml")) return source;
        int at = source.lastIndexOf("</Types>"); if (at < 0) return source;
        return source.substring(0, at) + "<Override PartName=\"/word/settings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml\"/>" + source.substring(at);
    }
    private static String reviewSettings(byte[] bytes, DocxDocument document) throws Exception {
        javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) { }
        org.w3c.dom.Document xml = bytes == null ? factory.newDocumentBuilder().newDocument()
                : factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(bytes));
        if (xml.getDocumentElement() == null) xml.appendChild(xml.createElementNS(W, "w:settings"));
        org.w3c.dom.Element root = xml.getDocumentElement();
        org.w3c.dom.NodeList previous = root.getElementsByTagNameNS(W, "trackRevisions");
        org.w3c.dom.Element track = previous.getLength() > 0 ? (org.w3c.dom.Element) previous.item(0) : xml.createElementNS(W, "w:trackRevisions");
        track.setAttributeNS(W, "w:val", document.trackRevisions ? "1" : "0");
        if (track.getParentNode() == null) root.appendChild(track);
        return OoxmlPreserver.xml(xml);
    }

    private static String addCommentRelationship(String source) {
        if (source.contains("wordprocessingml.document/comments") || source.contains("/relationships/comments")) return source;
        int at = source.lastIndexOf("</Relationships>");
        if (at < 0) return source;
        String id = unusedRelationshipId(source);
        String rel = "<Relationship Id=\"" + id + "\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments\" Target=\"comments.xml\"/>";
        return source.substring(0, at) + rel + source.substring(at);
    }

    private static String unusedRelationshipId(String source) {
        for (int i = 1; i < 1000; i++) {
            String id = "rId" + i;
            if (!source.contains("Id=\"" + id + "\"")) return id;
        }
        return "rIdThesisComments" + System.currentTimeMillis();
    }

    private static String newRelationships() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"" + REL
                + "\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments\" Target=\"comments.xml\"/></Relationships>";
    }

    private static String addCommentContentType(String source) {
        if (source.contains("/word/comments.xml")) return source;
        int at = source.lastIndexOf("</Types>");
        if (at < 0) return source;
        String value = "<Override PartName=\"/word/comments.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml\"/>";
        return source.substring(0, at) + value + source.substring(at);
    }

    private static String newContentTypes() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "<Override PartName=\"/word/comments.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml\"/>"
                + "</Types>";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String xmlEscape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
