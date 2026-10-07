package com.rikkahub.wordlite;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;

/** Older/unsupported content is copied, not silently flattened on a basic edit. */
public final class PreservationRegression {
    private static int checks;
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message); checks++; System.out.println("PASS " + message);
    }
    public static void main(String[] args) throws Exception {
        DocxDocument document = DocxParser.parse(new FileInputStream(args[0]), "complex.docx");
        check(document.paragraphs.get(0).hyperlinks.size() == 1, "hyperlinks are parsed with actual ranges");
        check(document.paragraphs.get(0).hyperlinks.get(0).target.equals("https://example.test/paper"), "hyperlink target resolves external relationship");
        DocxDocument.ParagraphBlock p = document.paragraphs.get(1);
        p.text = "仅修改此段"; p.runs.clear(); p.runs.add(new DocxDocument.Run(p.text, p.baseRunStyle.copy()));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DocxWriter.write(new FileInputStream(args[0]), output, document);
        Map<String, byte[]> parts = DocxZipReader.read(new ByteArrayInputStream(output.toByteArray()));
        String xml = new String(parts.get("word/document.xml"), StandardCharsets.UTF_8);
        check(xml.contains("m:oMath") && xml.contains("m:f"), "unmodified OMML fraction survives neighboring paragraph edit");
        check(xml.contains("footnoteReference") && xml.contains("footnotePr"), "footnote reference and section rules survive");
        check(xml.contains("w:hyperlink") && xml.contains("r:id=\"link1\""), "hyperlink wrapper survives");
        check(xml.contains("w:sdt") && xml.contains("封面控件"), "opaque content controls survive");
        check(xml.contains("custom-prop") && xml.contains("mc:Ignorable") && xml.contains("xmlns:custom"), "extension attributes/namespaces survive");
        check(xml.contains("仅修改此段"), "modified paragraph still uses editor changes");
        check(new String(parts.get("word/footnotes.xml"), StandardCharsets.UTF_8).contains("脚注不可丢失"), "untouched notes part retained");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        for (Map.Entry<String, byte[]> part : parts.entrySet()) if (part.getKey().endsWith(".xml"))
            factory.newDocumentBuilder().parse(new ByteArrayInputStream(part.getValue()));
        check(true, "all saved XML parses namespace-aware");
        DocxDocument reopened = DocxParser.parse(new ByteArrayInputStream(output.toByteArray()), "saved.docx");
        check(reopened.paragraphs.get(1).text.equals("仅修改此段"), "reopen keeps ordinary edit");
        System.out.println("SUMMARY " + checks + " OOXML preservation assertions passed");
    }
}
