package com.rikkahub.wordlite;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;

public final class ReviewRegression {
    private static int checks;
    private static void check(boolean value, String text) {
        if (!value) throw new AssertionError(text); checks++; System.out.println("PASS " + text);
    }
    public static void main(String[] args) throws Exception {
        DocxDocument document = DocxParser.parse(new FileInputStream(args[0]), "fixture.docx");
        document.trackRevisions = true; document.reviewAuthor = "测试作者";
        DocxDocument.ParagraphBlock p = document.paragraphs.get(0);
        String original = p.text;
        ReviewManager.recordEdit(document, p, 1, 1, "插入αβ");
        p.text = original.substring(0, 1) + "插入αβ" + original.substring(2);
        p.runs.clear(); p.runs.add(new DocxDocument.Run(p.text, p.baseRunStyle.copy()));
        check(document.revisions.size() == 2, "replacement tracks deletion and insertion");
        check(document.revisions.get(0).text.equals(original.substring(1, 2)), "deleted text remains available");
        DocxDocument.Comment comment = ReviewManager.comment(document, p.index, 0, 2, "检查 μ π");
        DocxDocument.Comment reply = ReviewManager.reply(document, comment, "回复 α");
        comment.resolved = true;
        check(reply.parentId == comment.id && !reply.date.isEmpty(), "thread reply has parent/date/author");
        ReviewManager.format(document, p, 2, 4);
        ArrayList<DocxDocument.Run> changed = new ArrayList<DocxDocument.Run>();
        DocxDocument.RunStyle bold = p.baseRunStyle.copy(); bold.bold = true; bold.boldSet = true;
        changed.add(new DocxDocument.Run(p.text.substring(2, 4), bold));
        ReviewManager.replaceStyles(p, 2, 4, changed);
        ReviewManager.paragraphFormat(document, p); p.format.alignment = 2;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DocxWriter.write(new FileInputStream(args[0]), output, document);
        Map<String, byte[]> parts = DocxZipReader.read(new ByteArrayInputStream(output.toByteArray()));
        String xml = new String(parts.get("word/document.xml"), StandardCharsets.UTF_8);
        check(xml.contains("<w:ins") && xml.contains("<w:del") && xml.contains("<w:delText"), "standard insertion/deletion OOXML");
        check(xml.contains("<w:rPrChange") && xml.contains("<w:pPrChange"), "standard run/paragraph formatting OOXML");
        check(parts.containsKey("word/commentsExtended.xml"), "standard commentsExtended written");
        check(new String(parts.get("word/commentsExtended.xml"), StandardCharsets.UTF_8).contains("paraIdParent"), "reply relationship stored as paraIdParent");
        javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        for (Map.Entry<String, byte[]> part : parts.entrySet()) if (part.getKey().endsWith(".xml"))
            factory.newDocumentBuilder().parse(new ByteArrayInputStream(part.getValue()));
        check(true, "all review XML parses");
        DocxDocument reopened = DocxParser.parse(new ByteArrayInputStream(output.toByteArray()), "saved.docx");
        check(reopened.paragraphs.get(0).text.equals(p.text), "accepted-view text unchanged after reopen");
        check(reopened.trackRevisions, "track mode persists in settings.xml");
        boolean parentFound = false, replyFound = false;
        for (DocxDocument.Comment item : reopened.comments) {
            if (item.id == comment.id) parentFound = item.resolved && item.author.equals("测试作者") && item.date.equals(comment.date);
            if (item.id == reply.id) replyFound = item.parentId == comment.id && item.paragraphIndex == p.index;
        }
        check(parentFound && replyFound, "replies and resolved/author/time survive reopen");
        int insert = 0, deleted = 0, format = 0, paragraphFormat = 0;
        for (DocxDocument.Revision r : reopened.revisions) {
            if (r.kind == DocxDocument.Revision.Kind.INSERT) insert++;
            if (r.kind == DocxDocument.Revision.Kind.DELETE) deleted++;
            if (r.kind == DocxDocument.Revision.Kind.FORMAT) format++;
            if (r.kind == DocxDocument.Revision.Kind.PARAGRAPH_FORMAT) paragraphFormat++;
        }
        check(insert > 0 && deleted > 0 && format > 0 && paragraphFormat > 0, "all four revision kinds parsed");
        // Reject formatting before the text it applies to, then reject inserts in reverse order.
        for (DocxDocument.Revision r : new ArrayList<DocxDocument.Revision>(reopened.revisions))
            if (r.kind == DocxDocument.Revision.Kind.FORMAT || r.kind == DocxDocument.Revision.Kind.PARAGRAPH_FORMAT) ReviewManager.decide(reopened, r, false);
        ArrayList<DocxDocument.Revision> revisions = new ArrayList<DocxDocument.Revision>(reopened.revisions);
        java.util.Collections.reverse(revisions);
        for (DocxDocument.Revision r : revisions) if (r.kind == DocxDocument.Revision.Kind.INSERT) ReviewManager.decide(reopened, r, false);
        for (DocxDocument.Revision r : new ArrayList<DocxDocument.Revision>(reopened.revisions)) ReviewManager.decide(reopened, r, false);
        check(reopened.paragraphs.get(0).text.equals(original), "reject all restores original text including deleted characters");
        check(reopened.revisions.isEmpty(), "rejected changes removed");
        DocxDocument acceptDoc = DocxParser.parse(new ByteArrayInputStream(output.toByteArray()), "saved.docx");
        for (DocxDocument.Revision r : new ArrayList<DocxDocument.Revision>(acceptDoc.revisions)) ReviewManager.decide(acceptDoc, r, true);
        check(acceptDoc.paragraphs.get(0).text.equals(p.text) && acceptDoc.revisions.isEmpty(), "accept all retains edited text");
        ByteArrayOutputStream accepted = new ByteArrayOutputStream();
        DocxWriter.write(new ByteArrayInputStream(output.toByteArray()), accepted, acceptDoc);
        String acceptedXml = new String(DocxZipReader.read(new ByteArrayInputStream(accepted.toByteArray())).get("word/document.xml"), StandardCharsets.UTF_8);
        java.nio.file.Path acceptedDir = java.nio.file.Paths.get("artifacts/tests/review");
        java.nio.file.Files.createDirectories(acceptedDir);   // a fresh checkout has no artifacts/ yet
        java.nio.file.Files.write(acceptedDir.resolve("accepted.xml"), acceptedXml.getBytes(StandardCharsets.UTF_8));
        org.w3c.dom.Document acceptedDocument = factory.newDocumentBuilder().parse(new ByteArrayInputStream(acceptedXml.getBytes(StandardCharsets.UTF_8)));
        String w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        check(acceptedDocument.getElementsByTagNameNS(w, "ins").getLength() == 0
                && acceptedDocument.getElementsByTagNameNS(w, "del").getLength() == 0
                && acceptedDocument.getElementsByTagNameNS(w, "rPrChange").getLength() == 0
                && acceptedDocument.getElementsByTagNameNS(w, "pPrChange").getLength() == 0, "accepted OOXML removes revision markers");
        try (FileOutputStream file = new FileOutputStream(args[1])) { file.write(output.toByteArray()); }
        DocxDocument typing = new DocxDocument(); typing.trackRevisions = true;
        DocxDocument.ParagraphBlock typed = new DocxDocument.ParagraphBlock(); typed.index = 0; typed.text = "AB";
        typed.runs.add(new DocxDocument.Run(typed.text, typed.baseRunStyle.copy())); typing.paragraphs.add(typed); typing.blocks.add(typed);
        edit(typing, typed, 1, 0, "x"); edit(typing, typed, 2, 0, "y");
        check(typing.revisions.size() == 1 && typing.revisions.get(0).end == 3, "adjacent typing coalesces into single insertion");
        edit(typing, typed, 2, 1, "");
        check(typing.revisions.size() == 1 && typing.revisions.get(0).kind == DocxDocument.Revision.Kind.INSERT, "backspace over own insertion does not create phantom deletion");
        edit(typing, typed, 0, 2, "");
        check(typing.revisions.size() == 1 && typing.revisions.get(0).text.equals("A"), "deleting accepted and pending text tracks only accepted characters");
        ReviewManager.decide(typing, typing.revisions.get(0), false);
        check(typed.text.equals("AB"), "mixed deletion rejects without resurrecting unaccepted typing");
        edit(typing, typed, 1, 0, "XY"); typing.trackRevisions = false; edit(typing, typed, 1, 0, "u");
        check(typing.revisions.get(0).start == 2 && typing.revisions.get(0).end == 4, "untracked insertion does not inherit tracked range");
        ReviewManager.decide(typing, typing.revisions.get(0), false);
        check(typed.text.equals("AuB"), "reject insertion retains later untracked edge text");
        System.out.println("SUMMARY " + checks + " review/thread/revision assertions passed");
    }
    private static void edit(DocxDocument document, DocxDocument.ParagraphBlock p, int start, int count, String inserted) {
        ReviewManager.recordEdit(document, p, start, count, inserted);
        p.text = p.text.substring(0, start) + inserted + p.text.substring(start + count);
        p.runs.clear(); p.runs.add(new DocxDocument.Run(p.text, p.baseRunStyle.copy()));
    }
}
