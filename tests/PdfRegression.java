package com.rikkahub.wordlite;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Range/copy and standards metadata tested without faking Android PDF drawing. */
public final class PdfRegression {
    private static int checks;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++; System.out.println("PASS " + message);
    }
    public static void main(String[] args) throws Exception {
        PdfExportOptions options = new PdfExportOptions();
        check(options.pages(3).length == 3, "full export page range");
        options.scope = PdfExportOptions.Scope.CURRENT_PAGE; options.currentPage = 1;
        check(options.pages(3)[0] == 1 && options.pages(3).length == 1, "current page range");
        boolean rejected = false;
        try { options.currentPage = 4; options.pages(2); } catch (IllegalArgumentException error) { rejected = true; }
        check(rejected, "invalid page range rejected");
        DocxDocument document = new DocxDocument();
        DocxDocument.ParagraphBlock paragraph = new DocxDocument.ParagraphBlock();
        paragraph.index = 9; paragraph.text = "前文αβ后文";
        paragraph.baseRunStyle.fontSizeHalfPoints = 24;
        paragraph.runs.add(new DocxDocument.Run(paragraph.text, paragraph.baseRunStyle.copy()));
        document.blocks.add(paragraph); document.paragraphs.add(paragraph);
        options.scope = PdfExportOptions.Scope.SELECTION; options.paragraphIndex = 9;
        options.selectionStart = 2; options.selectionEnd = 4;
        DocxDocument slice = DocumentSlices.selection(document, options);
        check(slice.paragraphs.get(0).text.equals("αβ"), "selection clone retains Unicode text");
        check(slice.paragraphs.get(0).runs.get(0).style.fontSizeHalfPoints == 24, "selection clone retains formatting");
        check(paragraph.text.equals("前文αβ后文"), "selection export never mutates source");
        options.selectionEnd = 2; rejected = false;
        try { DocumentSlices.selection(document, options); } catch (IllegalArgumentException error) { rejected = true; }
        check(rejected, "empty selection fails rather than exporting whole document");
        String[] objects = {"<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R >>",
                "<< /Length 0 >>\nstream\n\nendstream"};
        StringBuilder pdf = new StringBuilder("%PDF-1.4\n"); int[] offsets = new int[5];
        for (int i = 0; i < objects.length; i++) { offsets[i + 1] = pdf.length(); pdf.append(i + 1).append(" 0 obj\n").append(objects[i]).append("\nendobj\n"); }
        int xref = pdf.length(); pdf.append("xref\n0 5\n0000000000 65535 f \n");
        for (int i = 1; i < 5; i++) pdf.append(String.format(java.util.Locale.US, "%010d 00000 n \n", offsets[i]));
        pdf.append("trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        ArrayList<PdfExtras.Bookmark> bookmarks = new ArrayList<PdfExtras.Bookmark>();
        PdfExtras.Bookmark bookmark = new PdfExtras.Bookmark(); bookmark.page = 0; bookmark.top = 720; bookmark.title = "论文 αβ";
        bookmarks.add(bookmark);
        ArrayList<PdfExtras.Annotation> annotations = new ArrayList<PdfExtras.Annotation>();
        PdfExtras.Annotation annotation = new PdfExtras.Annotation(); annotation.page = 0;
        annotation.left = 20; annotation.right = 40; annotation.bottom = 700; annotation.top = 715;
        annotation.uri = "https://example.test/α"; annotations.add(annotation);
        PdfExtras.Annotation comment = new PdfExtras.Annotation(); comment.page = 0;
        comment.text = "批注 μ"; comment.author = "作者"; annotations.add(comment);
        byte[] output = PdfExtras.append(pdf.toString().getBytes(StandardCharsets.ISO_8859_1), bookmarks, annotations);
        String contents = new String(output, StandardCharsets.ISO_8859_1);
        check(contents.contains("/Outlines") && contents.contains("/Subtype /Link"), "standard PDF outlines and links");
        check(contents.contains("/Subtype /Text") && contents.contains("/Prev " + xref), "standard comment and valid incremental predecessor");
        new File(args[0]).getParentFile().mkdirs();
        try (FileOutputStream file = new FileOutputStream(args[0])) { file.write(output); }
        /* CID \u5b57\u4f53\u53ea\u5e26\u5185\u5d4c cmap\u3001\u6ca1\u6709 ToUnicode \u7684\u90a3\u4e00\u65cf\uff08\u4e2d\u6587\u671f\u520a PDF \u5e38\u89c1\uff09\u3002
           \u5939\u5177\u7531 tests/make_cid_cmap_fixture.py \u751f\u6210\uff0c\u771f\u503c\u7531\u6784\u9020\u51b3\u5b9a\u3002 */
        PdfFile.Extracted cmap = PdfFile.extractText(java.nio.file.Files.readAllBytes(
                new File("tests/fixture-cidcmap.pdf").toPath()));
        check(cmap.text.contains("\u94dc\u710a\u5b54\u9699") && !cmap.undecodable && cmap.pages == 1,
                "\u53ea\u6709\u5185\u5d4c\u5b57\u4f53 cmap \u7684 CID \u5b57\u4f53\u4e5f\u80fd\u628a\u5b57\u62fc\u51fa\u6765\uff08\u6ca1\u6709 ToUnicode\uff09");
        PdfFile.Extracted partial = PdfFile.extractText(java.nio.file.Files.readAllBytes(
                new File("tests/fixture-cidcmap-partial.pdf").toPath()));
        check(partial.text.equals("\u94dc\u710a\n") && partial.undecodable && partial.undecodableGlyphs == 2,
                "\u5b57\u5f62\u53f7\u5bf9\u4e0d\u4e0a cmap \u7684\u90a3\u4e24\u4e2a\u5b57\u4e0d\u731c\u5b57\uff0c\u53ea\u628a\u5b83\u4eec\u6309\u4e2a\u6570\u8bb0\u8fdb\u8d26");
        /* ---------- 随包字符集表：方正那一族期刊 PDF 的码到字（真值由夹具构造决定）----------
           夹具由 tests/make_cid_cmap_fixture.py 生成，字形码取自 Adobe 公布的 UniGB-UTF16-H。 */
        String cmapAsset = "app/src/main/assets/cmaps/adobe-gb1.cid";
        check(new File(cmapAsset).isFile(), "随包的 Adobe-GB1 表在包里（" + cmapAsset + "）");
        CidUnicodeTables tables = CidUnicodeTables.install(new java.io.FileInputStream(cmapAsset));
        check(tables.describe().startsWith("Adobe-GB1="), "装进来的是 Adobe-GB1 那一套：" + tables.describe());
        CidUnicodeTables.Section gb1 = CidUnicodeTables.section("Adobe", "GB1");
        check(gb1 != null && gb1.slots() > 30000, "表覆盖 3 万多个 CID：" + (gb1 == null ? 0 : gb1.slots()));
        check("中".equals(gb1.get(4559)) && "一".equals(gb1.get(4162))
                        && "册".equals(gb1.get(1192)) && "碳".equals(gb1.get(3599)) && "网".equals(gb1.get(3753)),
                "抽查 CID：4559=中、4162=一、1192=册、3599=碳、3753=网（码位取自 Adobe 公布的 UniGB-UTF16-H）");
        check(String.valueOf(Character.toChars(0x2CE93)).equals(gb1.get(30571)),
                "U+FFFF 以上的 CID 也查得出（CID 30571 = U+2CE93，走代理对那条路）");
        check(gb1.get(0) == null && gb1.get(99999) == null && gb1.get(-1) == null,
                "notdef 与越界的 CID 返回空，绝不猜字");
        check(CidUnicodeTables.section("Adobe", "Identity") == null
                        && CidUnicodeTables.section("Microsoft", "GB1") == null
                        && CidUnicodeTables.section(null, "GB1") == null,
                "Ordering=Identity 或不是 Adobe 的 Registry 一律拿不到表");

        String gb1Truth = "碳纳米管网络在180℃下大量自组装成一根，孔隙率随之上升。";
        PdfFile.Extracted fromCid = PdfFile.extractText(java.nio.file.Files.readAllBytes(
                new File("tests/fixture-gb1-gb1.pdf").toPath()));
        check(fromCid.text.contains(gb1Truth),
                "没有 ToUnicode、没有内嵌字体的 GB1 字体拼得出正文：" + fromCid.text.trim());
        check(!fromCid.undecodable && fromCid.undecodableGlyphs == 0, "这份 PDF 一个字都没丢");
        check(fromCid.cidTableChars == gb1Truth.length() && "GB1".equals(fromCid.cidTableOrderings),
                "回执说清补回多少字、查的哪套表：" + fromCid.cidTableChars + " 字 / " + fromCid.cidTableOrderings);

        PdfFile.Extracted radical = PdfFile.extractText(java.nio.file.Files.readAllBytes(
                new File("tests/fixture-gb1-radical.pdf").toPath()));
        check(radical.text.contains("一册") && radical.text.indexOf('\u2f00') < 0,
                "Adobe 官方把 CID 4162 写成康熙部首\u2f00，这里必须读成汉字本体「一」（实测读出：" + radical.text.trim() + "）");
        check(radical.undecodableGlyphs == 1, "CID 0（notdef）按读不出记账，不许蒙一个字：" + radical.undecodableGlyphs);

        PdfFile.Extracted identity = PdfFile.extractText(java.nio.file.Files.readAllBytes(
                new File("tests/fixture-gb1-identity.pdf").toPath()));
        check(identity.text.trim().isEmpty() && identity.undecodableGlyphs == gb1Truth.length(),
                "同一批码声明 Ordering=Identity 时不许套表：" + identity.undecodableGlyphs + " 个字全按读不出记账");

        // 把表撤掉必须退回改前那一版：这一路字一个都不许读出来（钉住"是这张表在起作用"）
        CidUnicodeTables.uninstall();
        check(CidUnicodeTables.active() == null, "卸载之后没有表可用");
        PdfFile.Extracted noTable = PdfFile.extractText(java.nio.file.Files.readAllBytes(
                new File("tests/fixture-gb1-gb1.pdf").toPath()));
        check(noTable.text.trim().isEmpty() && noTable.undecodableGlyphs == gb1Truth.length()
                        && noTable.undecodableFonts.contains("FZCIDSJW"),
                "没有表时那一路字退回读不出，并报出卡在哪个字体：" + noTable.undecodableFonts);
        CidUnicodeTables.install(new java.io.FileInputStream(cmapAsset));

        /* 两页都用 /C1 这个名字、指的却是两张脸：按资源名缓存字体会让第二页整页丢字
           （真刊 scichina.pdf 就是这么少读 2,447 字：整份只读出 1,846 字，真值 9,414 字）。 */
        PdfFile.Extracted collide = PdfFile.extractText(java.nio.file.Files.readAllBytes(
                new File("tests/fixture-font-key-collision.pdf").toPath()));
        check(collide.pages == 2 && collide.text.contains("三维碳纳米管网络状结构")
                        && collide.text.contains("接头导电率保持在两者之间") && collide.undecodableGlyphs == 0,
                "同名字体名跨页不串味：第一页查表、第二页用自带 cmap，两页都要读出来（" + collide.text.trim().replace("\n", " / ") + "）");
        check(!collide.text.contains(gb1Truth), "串味时才会出现的串读：第二页不许拿第一页的表去解自己的字形码");

        System.out.println("SUMMARY " + checks + " PDF range/metadata assertions passed; Android native rendering not simulated");
    }
}
