package com.rikkahub.wordlite;

import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Comparator;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/** Keep unsupported OOXML intact whenever its owning block has not changed. */
public final class OoxmlPreserver {
    private OoxmlPreserver() { }
    public static String xml(Node node) throws Exception {
        TransformerFactory factory = TransformerFactory.newInstance();
        try { factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true); } catch (Exception ignored) { }
        Transformer transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter result = new StringWriter();
        transformer.transform(new DOMSource(node), new StreamResult(result));
        return result.toString();
    }
    public static String rootAttributes(Element root) {
        StringBuilder attributes = new StringBuilder(); NamedNodeMap map = root.getAttributes();
        for (int i = 0; i < map.getLength(); i++) {
            Node attribute = map.item(i); String name = attribute.getNodeName();
            if (name.equals("xmlns:w") || name.equals("xmlns:r") || name.equals("xmlns:wp")
                    || name.equals("xmlns:a") || name.equals("xmlns:pic")) continue;
            attributes.append(' ').append(name).append("=\"").append(escape(attribute.getNodeValue())).append('"');
        }
        return attributes.toString();
    }
    public static void capture(DocxDocument.Block block, Element source, DocxDocument document) throws Exception {
        block.originalXml = xml(source); block.originalSignature = signature(block, document);
    }
    public static boolean unchanged(DocxDocument.Block block, DocxDocument document) {
        return !block.originalXml.isEmpty() && block.originalSignature.equals(signature(block, document));
    }
    public static String signature(DocxDocument.Block block, DocxDocument document) {
        StringBuilder out = new StringBuilder(); out.append(block.sectionIndex).append(':');
        if (block instanceof DocxDocument.ParagraphBlock) {
            DocxDocument.ParagraphBlock p = (DocxDocument.ParagraphBlock) block;
            out.append(p.text).append('\u0001'); properties(out, p.format); properties(out, p.baseRunStyle);
            out.append(p.styleId).append(':').append(p.isHeading).append(':').append(p.listLabel);
            for (DocxDocument.Run run : p.runs) { out.append(run.text); properties(out, run.style); }
            for (DocxDocument.RangeStyle style : p.editedStyles) { out.append(style.start).append(':').append(style.end); properties(out, style.style); }
            for (DocxDocument.EmbeddedImage image : p.images)
                out.append(image.relationshipId).append(':').append(image.partName).append(':').append(image.widthEmu).append(':').append(image.heightEmu).append(':').append(image.isNew);
            for (DocxDocument.FieldCode field : p.fields) properties(out, field);
            for (DocxDocument.Hyperlink link : p.hyperlinks) properties(out, link);
            for (DocxDocument.Revision revision : document.revisions)
                if (revision.paragraphIndex == p.index) {
                    properties(out, revision); out.append(revision.kind);
                    for (DocxDocument.Run previous : revision.previousRuns) { out.append(previous.text); properties(out, previous.style); }
                }
            for (DocxDocument.Comment comment : document.comments)
                if (comment.paragraphIndex == p.index && comment.parentId < 0)
                    out.append(comment.id).append(':').append(comment.start).append(':').append(comment.end);
        } else if (block instanceof DocxDocument.TableBlock) {
            DocxDocument.TableBlock table = (DocxDocument.TableBlock) block; out.append(table.columns);
            for (java.util.ArrayList<DocxDocument.Cell> row : table.rows) {
                out.append('['); for (DocxDocument.Cell cell : row) {
                    out.append('{'); for (DocxDocument.ParagraphBlock p : cell.paragraphs) out.append(signature(p, document)); out.append('}');
                } out.append(']');
            }
        }
        return out.toString();
    }
    public static String sectionSignature(DocxDocument.SectionSettings section) {
        StringBuilder out = new StringBuilder(); properties(out, section); return out.toString();
    }
    private static void properties(StringBuilder out, Object value) {
        try {
            Field[] fields = value.getClass().getFields(); Arrays.sort(fields, (a, b) -> a.getName().compareTo(b.getName()));
            for (Field field : fields) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                Object item = field.get(value);
                if (item == null || item instanceof Number || item instanceof Boolean || item instanceof String)
                    out.append(field.getName()).append('=').append(item).append(';');
            }
        } catch (IllegalAccessException error) { throw new IllegalStateException(error); }
    }
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
    }
}
