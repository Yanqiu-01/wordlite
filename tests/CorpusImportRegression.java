package com.rikkahub.wordlite;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * 0.6.2 + 0.6.3 引擎侧回归：自建库批量导入（TXT/DOCX/PDF 混合）、按正文哈希双向去重、
 * 扫描版 PDF 的"无文字层"口径、批量中途被打断后索引仍然可用、逐文件进度序列。
 *
 * 纯 JVM：不联网、不碰 Android。PDF 夹具由 tests/make_pdf_fixtures.py 生成，
 * 正文取自 tests/corpus/real-prose.txt 的真实段落——去重要是在真中文上测的，
 * 不是拿一串 ASCII 糊过去。0.6.3 验收原写在 PdfRegression 上，本次按分工落在这里。
 */
public final class CorpusImportRegression {
    private static int checks;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
        System.out.println("PASS " + message);
    }

    private static final String ROOT = "artifacts/tests/corpus-import";
    private static final String KEY_A = "宽禁带半导体";
    private static final String KEY_B = "低熔点金属";

    public static void main(String[] args) throws Exception {
        pdfTextStream();
        batchOfMixedTypes();
        dedupBothDirections();
        scannedPdfOutcome();
        oneBadFile();
        interruptedBatch();
        tornIndex();
        pdfIntoCorpus();
        downloadedPicks();
        writerRoundTrip();
        libraryApiStillWorks();
        deleteRecursively(new File(ROOT));
        System.out.println("SUMMARY " + checks
                + " corpus-import assertions passed; 真机 SAF 选文件与界面进度条不在本机模拟");
    }

    // ---- 0.6.3：PdfFile 的文字流读回来 ----

    private static void pdfTextStream() throws Exception {
        byte[] ucs2 = readAll(new File("tests/fixture-cn.pdf"));
        byte[] toUnicode = readAll(new File("tests/fixture-tounicode.pdf"));
        byte[] scanned = readAll(new File("tests/fixture-scanned.pdf"));
        byte[] encrypted = readAll(new File("tests/fixture-encrypted.pdf"));
        String lineA = corpusLine(KEY_A);
        String lineB = corpusLine(KEY_B);

        PdfFile.Extracted cn = PdfFile.extractText(ucs2);
        check(cn.pages == 2, "中文 PDF 读出两页，got " + cn.pages);
        check(cn.textOps > 0, "中文 PDF 有文字算子（" + cn.textOps + " 条）");
        check(!cn.undecodable, "UniGB-UCS2-H 的中文 PDF 不报编码问题");
        String joined = cn.text.replace("\n", "");
        check(joined.startsWith(lineA) && joined.endsWith(lineB),
                "UniGB-UCS2-H 抽出的正文与语料原文逐字相等（" + joined.length() + " 字）");
        check(cjkCount(joined) > 150, "抽出来的确实是中文而不是乱码：CJK 字符 " + cjkCount(joined) + " 个");
        check(cn.text.indexOf('<') < 0 && cn.text.indexOf('>') < 0, "正文里没有漏进来的 PDF 语法字符 <>");
        check(cn.text.indexOf("Tj") < 0 && cn.text.indexOf("BT") < 0, "正文里没有漏进来的算子文本");

        PdfFile.Extracted identity = PdfFile.extractText(toUnicode);
        check(identity.pages == 2, "Identity-H + ToUnicode 的 PDF 同样两页");
        check(identity.text.equals(cn.text),
                "ToUnicode 与 UniGB-UCS2-H 两条编码路径抽出同一份正文");

        PdfFile.Extracted scan = PdfFile.extractText(scanned);
        check(scan.pages == 1, "扫描版 PDF 读得出页面对象");
        check(scan.textOps == 0, "扫描版 PDF 一个文字算子都没有");
        check(scan.imageBlocks >= 1, "扫描版 PDF 认出了整页位图（" + scan.imageBlocks + " 张）");
        check(scan.text.length() == 0, "扫描版抽出来是空正文，不是乱码");

        boolean encryptedRejected = false;
        String encryptedMessage = "";
        try {
            PdfFile.extractText(encrypted);
        } catch (IOException error) {
            encryptedRejected = true;
            encryptedMessage = error.getMessage();
        }
        check(encryptedRejected, "带 /Encrypt 的 PDF 直接报错，不假装读出正文");
        check(encryptedMessage.indexOf("加密") >= 0, "加密 PDF 的报错说清了原因：" + encryptedMessage);

        byte[] lyingPdf = new byte[4096];
        System.arraycopy("%PDF-1.4 not really a pdf".getBytes(StandardCharsets.ISO_8859_1), 0, lyingPdf, 0, 25);
        boolean junkRejected = false;
        try {
            PdfFile.extractText(lyingPdf);
        } catch (IOException error) {
            junkRejected = error.getMessage() != null && error.getMessage().indexOf("PDF") >= 0;
        }
        check(junkRejected, "顶着 %PDF 头的垃圾字节按异常收，不给一份空正文");

        LocalLibrary.Extract asPdf = LocalLibrary.extract("论文.pdf", scanned);
        check(asPdf.noTextLayer, "LocalLibrary.extract 把扫描版单独标成 noTextLayer");
        check(asPdf.pages == 1 && asPdf.text.length() == 0, "扫描版在 LocalLibrary 里同样是 0 字正文");
        check(LocalLibrary.textOf(ucs2, "论文.pdf").equals(cn.text), "textOf 仍走同一条抽取路径，结果一致");
    }

    // ---- 0.6.2：一批混合类型，逐文件回执与进度 ----

    private static void batchOfMixedTypes() throws Exception {
        String lineA = corpusLine(KEY_A);
        String lineB = corpusLine(KEY_B);
        byte[] docx = readAll(new File("tests/fixture.docx"));
        byte[] scanned = readAll(new File("tests/fixture-scanned.pdf"));
        byte[] encrypted = readAll(new File("tests/fixture-encrypted.pdf"));
        byte[] lyingPdf = new byte[4096];
        System.arraycopy("%PDF-1.4 not really a pdf".getBytes(StandardCharsets.ISO_8859_1), 0, lyingPdf, 0, 25);

        LocalLibrary library = library("mixed");
        ArrayList<CorpusImport.Source> sources = new ArrayList<CorpusImport.Source>();
        sources.add(source("第一组.txt", lineA));
        sources.add(source("第二组.md", lineB));
        sources.add(new CorpusImport.Source("fixture.docx", docx));
        sources.add(new CorpusImport.Source("论文.pdf", readAll(new File("tests/fixture-cn.pdf"))));
        sources.add(source("同一篇改了名的.txt", lineA));
        sources.add(source("同一篇的第三副本.txt", lineA));
        sources.add(new CorpusImport.Source("tool.exe", new byte[]{1, 2, 3}));
        sources.add(new CorpusImport.Source("照片.jpg", new byte[]{9, 9, 9}));
        sources.add(source("没有扩展名", lineB));
        sources.add(new CorpusImport.Source("坏掉的.pdf", lyingPdf));
        sources.add(new CorpusImport.Source("扫描版.pdf", scanned));
        sources.add(new CorpusImport.Source("加密版.pdf", encrypted));

        ProgressLog log = new ProgressLog();
        CorpusImport.Batch batch = CorpusImport.run(library, sources, log);

        check(batch.total == 12 && batch.receipts.size() == 12, "十二个文件十二条回执，一个不吞");
        check(log.lines.size() == 12 && log.inOrder(), "进度回调每个文件一次，序号 1..12 连续");
        check(log.lines.get(0).startsWith("1/12 ") && log.lines.get(11).startsWith("12/12 "),
                "进度就是界面要的 3/17 那种分子分母");
        check(batch.imported() == 4, "四个不同正文各进一次，got " + batch.imported());
        check(batch.duplicates() == 3, "两个改名副本 + 一个无扩展名的同正文都被跳过，got " + batch.duplicates());
        check(batch.unsupported() == 2, ".exe 与 .jpg 各算不支持，got " + batch.unsupported());
        check(batch.noTextLayer() == 1, "扫描版单独算一档，没混进失败里，got " + batch.noTextLayer());
        check(batch.failed() == 2, "坏 PDF 与加密 PDF 各一条失败，got " + batch.failed());
        check(batch.imported() + batch.duplicates() + batch.unsupported() + batch.noTextLayer()
                + batch.failed() == batch.receipts.size(), "五种状态正好分完全部回执，没有第六种");
        check(!batch.cancelled, "没被取消的批次 cancelled 为假");
        check(status(batch, 0) == CorpusImport.Status.IMPORTED, "第 1 条：TXT 入库");
        check(status(batch, 1) == CorpusImport.Status.IMPORTED, "第 2 条：Markdown 入库");
        check(status(batch, 2) == CorpusImport.Status.IMPORTED, "第 3 条：真 DOCX 入库");
        check(status(batch, 3) == CorpusImport.Status.IMPORTED, "第 4 条：PDF 入库");
        check(status(batch, 4) == CorpusImport.Status.DUPLICATE, "第 5 条：改名同正文 = 重复");
        check(status(batch, 8) == CorpusImport.Status.DUPLICATE, "第 9 条：没扩展名按 .txt 认，正文重复照样挡");
        check(receipt(batch, 8).duplicateOf.equals("第二组.md"), "无扩展名那条撞上的正是同正文的那篇 md");
        check(status(batch, 10) == CorpusImport.Status.NO_TEXT_LAYER, "第 11 条：扫描版 = 无文字层");
        check(status(batch, 11) == CorpusImport.Status.FAILED, "第 12 条：加密件 = 失败");
        check(receipt(batch, 4).duplicateOf.equals(receipt(batch, 0).storedName),
                "重复回执说清了撞在哪一篇上");
        check(receipt(batch, 3).storedName.equals("论文.pdf"), "PDF 按原名落库");
        check(receipt(batch, 3).pages == 2, "PDF 回执带上页数");
        check(receipt(batch, 3).chars > 300, "PDF 回执带的是有效字符数（" + receipt(batch, 3).chars + "）");
        check(receipt(batch, 6).message.indexOf(".exe") >= 0, "不支持的回执写清了扩展名");
        check(receipt(batch, 7).message.indexOf(".jpg") >= 0, "图片也按扩展名挡在解析之前");
        check(status(batch, 6) == CorpusImport.Status.UNSUPPORTED
                && status(batch, 7) == CorpusImport.Status.UNSUPPORTED, "两个非文档扩展名都不支持");
        check(receipt(batch, 11).message.indexOf("加密") >= 0, "加密件的失败原因带得上界面");
        check(library.size() == 4, "库里只落了四篇，got " + library.size());
        check(!new File(libraryDir("mixed"), "tool.exe").exists(), "不支持的文件没被写进库目录");
        check(!new File(libraryDir("mixed"), "扫描版.pdf").exists(), "扫描版根本没落盘");
        check(batch.importedChars() == receipt(batch, 0).chars + receipt(batch, 1).chars
                        + receipt(batch, 2).chars + receipt(batch, 3).chars,
                "importedChars 是成功那几条之和，不把跳过的算进来");
        check(batch.summary().indexOf("导入 4 篇") >= 0 && batch.summary().indexOf("重复跳过 3 篇") >= 0
                && batch.summary().indexOf("无文字层 1 篇") >= 0, "汇总文案：" + batch.summary());
        check(batch.firstProblem().length() > 0, "firstProblem 给出第一条非成功说明");
    }

    // ---- 去重必须双向：改名要认得，同名不同内容不许误杀 ----

    private static void dedupBothDirections() throws Exception {
        byte[] docx = readAll(new File("tests/fixture.docx"));
        String docxText = LocalLibrary.textOf(docx, "fixture.docx");

        LocalLibrary docxFirst = library("docx-first");
        CorpusImport.Batch first = CorpusImport.run(docxFirst, listOf(
                new CorpusImport.Source("原文.docx", docx)), null);
        CorpusImport.Batch then = CorpusImport.run(docxFirst, listOf(
                source("同一篇正文的txt版.txt", docxText)), null);
        check(first.imported() == 1, "DOCX 先进库");
        check(then.duplicates() == 1, "同一篇正文换个格式再来 = 重复（DOCX 先）");
        check(then.receipts.get(0).duplicateOf.equals("原文.docx"), "跨格式重复指回了原来那篇 DOCX");

        LocalLibrary txtFirst = library("txt-first");
        CorpusImport.run(txtFirst, listOf(source("同一篇正文的txt版.txt", docxText)), null);
        CorpusImport.Batch reverse = CorpusImport.run(txtFirst, listOf(
                new CorpusImport.Source("原文.docx", docx)), null);
        check(reverse.duplicates() == 1, "反过来 TXT 先、DOCX 后同样判重复");
        check(reverse.receipts.get(0).duplicateOf.equals("同一篇正文的txt版.txt"), "反方向也指得回原名");

        LocalLibrary sameName = library("same-name");
        CorpusImport.Batch two = CorpusImport.run(sameName, listOf(
                source("同号.txt", "这一篇讲的是多孔铜的孔隙率与浸渗行为。"),
                source("同号.txt", "这一篇讲的是SiC 器件的功率循环与焊点疲劳。")), null);
        check(two.imported() == 2, "同名不同内容必须两次都入库");
        check(sameName.size() == 2, "库里的确是两篇，不是被去重吃掉一篇");
        check(!receipt(two, 0).storedName.equals(receipt(two, 1).storedName),
                "落库文件名自动错开：" + receipt(two, 0).storedName + " / " + receipt(two, 1).storedName);
        check(receipt(two, 1).storedName.equals("同号_2.txt"), "第二篇拿到带序号的名字");

        String padded = "  同一篇论文，正文。\n\n";
        String folded = "同一篇论文, 正文.";
        check(LocalLibrary.bodyHash(padded).equals(LocalLibrary.bodyHash(folded)),
                "只差空白与全半角标点算同一篇：这是刻意的口径");
        check(!LocalLibrary.bodyHash(padded).equals(LocalLibrary.bodyHash(padded + "新句子。")),
                "多一个真句子就不算同一篇");
        check(LocalLibrary.bodyHash("随便一段正文").equals(LocalLibrary.bodyHash("随便一段正文")),
                "同一串正文的哈希稳定");
        check(LocalLibrary.bodyHash("").length() == 0 && LocalLibrary.bodyHash("   \n\t ").length() == 0,
                "空正文不给哈希，免得两篇空文件互相判重复");

        LocalLibrary bare = library("bare-name");
        CorpusImport.Batch noExtension = CorpusImport.run(bare, listOf(source("没有扩展名的文件名", "没有扩展名的文件按 txt 认，这是库既有的命名规矩。")), null);
        check(noExtension.imported() == 1 && receipt(noExtension, 0).storedName.endsWith(".txt"),
                "没扩展名的文件补上 .txt 入库：" + receipt(noExtension, 0).storedName);

        LocalLibrary batchOfClones = library("clones");
        CorpusImport.Batch clones = CorpusImport.run(batchOfClones, listOf(
                source("副本甲.txt", padded), source("副本乙.txt", folded), source("副本丙.txt", padded)), null);
        check(clones.imported() == 1 && clones.duplicates() == 2, "一个批次里三份副本只进一篇");
        check(batchOfClones.size() == 1, "库里的确只有一篇");

        LocalLibrary reopened = new LocalLibrary(libraryDir("clones"));
        CorpusImport.Batch afterReopen = CorpusImport.run(reopened, listOf(source("又一份.txt", padded)), null);
        check(afterReopen.duplicates() == 1, "重开一个实例仍然判重复：哈希写在索引里，不在内存里");
        String index = new String(readAll(new File(libraryDir("clones"), "index.json")), "UTF-8");
        check(index.indexOf("\"hash\"") >= 0 && index.indexOf("\"version\":2") >= 0,
                "index.json 带上了 hash 且版本升到 2");

        LocalLibrary legacy = library("legacy-v1");
        byte[] legacyBody = "旧索引里那篇论文的正文内容需要长一些才不被丢掉。".getBytes("UTF-8");
        writeAll(new File(libraryDir("legacy-v1"), "旧论文.txt"), legacyBody);
        writeAll(new File(libraryDir("legacy-v1"), "index.json"),
                ("{\"version\":1,\"entries\":[{\"name\":\"旧论文.txt\",\"bytes\":" + legacyBody.length
                        + ",\"addedAt\":1,\"sentences\":0}]}").getBytes("UTF-8"));
        check(legacy.size() == 1, "版本 1 的索引照样读得进来");
        check(legacy.nameForHash(LocalLibrary.bodyHash(new String(legacyBody, "UTF-8"))).equals("旧论文.txt"),
                "老索引缺的 hash 会按文件补算，旧文件一样挡重复");
        String upgraded = new String(readAll(new File(libraryDir("legacy-v1"), "index.json")), "UTF-8");
        check(upgraded.indexOf("\"version\":2") >= 0 && upgraded.indexOf("\"hash\"") >= 0,
                "补算之后顺手把索引写成版本 2");
    }
    // ---- 扫描版：单独一档，零字符，不落盘 ----

    private static void scannedPdfOutcome() throws Exception {
        LocalLibrary library = library("scanned");
        byte[] scanned = readAll(new File("tests/fixture-scanned.pdf"));
        CorpusImport.Batch batch = CorpusImport.run(library, listOf(
                new CorpusImport.Source("扫描版.pdf", scanned),
                source("正常一篇.txt", corpusLine(KEY_B))), null);
        CorpusImport.Receipt receipt = receipt(batch, 0);
        check(receipt.status == CorpusImport.Status.NO_TEXT_LAYER, "扫描版回执是 NO_TEXT_LAYER");
        check(LocalLibrary.NO_TEXT_LAYER_MESSAGE.equals(receipt.message),
                "批量回执与 addDocument 共用同一句文案：" + receipt.message);
        check(receipt.chars == 0, "扫描版报进来的字符数是 0");
        check(receipt.spans == 0 && receipt.storedName.length() == 0, "扫描版没有落库文件名，也没句段");
        check(receipt.pages == 1, "扫描版仍然报得出页数，用户知道文件没坏");
        check(batch.imported() == 1 && batch.noTextLayer() == 1, "一批里扫描版不挡正常文件");
        check(library.size() == 1, "库里有正常那一篇，扫描版没占名额");
        File[] leftovers = libraryDir("scanned").listFiles();
        check(leftovers != null && countEnding(leftovers, ".pdf") == 0, "库目录里没有任何 PDF 残留");
        String direct = library.addDocument("直接塞的扫描版.pdf", scanned);
        check(LocalLibrary.NO_TEXT_LAYER_MESSAGE.equals(direct), "老 API 也拒收扫描版：" + direct);
    }

    // ---- 一个坏文件不许拖垮一批 ----

    private static void oneBadFile() throws Exception {
        LocalLibrary library = library("broken");
        byte[] fakeDocx = new byte[]{80, 75, 3, 4, 'n', 'o', 't', ' ', 'a', ' ', 'z', 'i', 'p'};
        CorpusImport.Batch batch = CorpusImport.run(library, listOf(
                new CorpusImport.Source("坏.docx", fakeDocx),
                new CorpusImport.Source("空.txt", new byte[0]),
                new CorpusImport.Source("空内容.txt", null),
                source("", "文件名都没有"),
                source("好的一篇.txt", corpusLine(KEY_A))), null);
        check(batch.receipts.size() == 5, "五个文件五条回执，坏的前面也照跑");
        check(status(batch, 0) == CorpusImport.Status.FAILED, "解不开的 docx 是失败，不是空正文");
        check(status(batch, 1) == CorpusImport.Status.FAILED && status(batch, 2) == CorpusImport.Status.FAILED,
                "空内容与 null 内容各一条失败，不抛异常");
        check(status(batch, 3) == CorpusImport.Status.FAILED, "空文件名报文件名无效，不炸");
        check(status(batch, 4) == CorpusImport.Status.IMPORTED, "排在最后的正常文件照样入库");
        check(batch.failed() == 4 && batch.imported() == 1, "统计口径没把失败算成成功");
        check(receipt(batch, 0).message.length() > 0, "失败回执一定带原因");
        check(library.size() == 1, "库里的确只有那一篇好的");
    }

    // ---- 批量中途被打断：索引必须还是那份读得懂的索引 ----

    private static void interruptedBatch() throws Exception {
        LocalLibrary library = library("interrupted");
        ArrayList<CorpusImport.Source> four = listOf(
                source("第一批甲.txt", corpusLine(KEY_A)),
                source("第一批乙.txt", corpusLine(KEY_B)),
                source("第一批丙.txt", "多孔铜的孔径与孔隙率会显著影响接头的力学表现。"),
                source("第一批丁.txt", "功率循环下焊点的蠕变开裂是主要失效形式。"));
        final int[] done = new int[]{0};
        CorpusImport.Batch part = CorpusImport.run(library, four, new CorpusImport.Progress() {
            public void onReceipt(int complete, int total, CorpusImport.Receipt receipt) {
                done[0] = complete;
            }
        }, new CorpusImport.Cancel() {
            public boolean cancelled() {
                return done[0] >= 2;
            }
        });
        check(part.cancelled, "取消被如实记下来");
        check(part.receipts.size() == 2, "取消后不再补造后面的回执");
        check(part.imported() == 2 && library.size() == 2, "已经导入的两篇真的落到了盘上");
        String index = new String(readAll(new File(libraryDir("interrupted"), "index.json")), "UTF-8");
        Object parsed = ApiJson.parse(index);
        check(parsed != null, "被打断的当下 index.json 仍解析得开");
        check(ApiJson.path(parsed, "$.entries[1].name") instanceof String
                && ApiJson.path(parsed, "$.entries[1].hash") instanceof String,
                "每条条目都带着名字与正文哈希，不是只写了半条");
        check(!new File(libraryDir("interrupted"), "index.json.tmp").exists(), "写完就改名，不留临时文件");

        LocalLibrary reopened = new LocalLibrary(libraryDir("interrupted"));
        check(reopened.size() == 2, "重开实例仍是两篇，没有凭白多出来或丢掉的");
        CorpusImport.Batch rest = CorpusImport.run(reopened, four, null, null);
        check(rest.duplicates() == 2 && rest.imported() == 2, "重跑整批：前两篇判重复，后两篇补进来");
        check(reopened.size() == 4, "补齐之后库里四篇");

        CorpusImport.Batch screaming = CorpusImport.run(reopened, listOf(source("回调炸了.txt", "进度回调抛异常的批次应当收住。")),
                new CorpusImport.Progress() {
                    public void onReceipt(int complete, int total, CorpusImport.Receipt receipt) {
                        throw new IllegalStateException("视图已经销毁");
                    }
                }, null);
        check(screaming.cancelled && screaming.receipts.size() == 1, "界面回调抛异常按取消收住，不整批炸掉");
        check(reopened.size() == 5, "回调炸掉之前那篇已经落库");
    }

    // ---- 索引被写坏：临时文件与半截 JSON 都不许冒充文档 ----

    private static void tornIndex() throws Exception {
        File directory = libraryDir("interrupted");
        writeAll(new File(directory, "index.json.tmp"), "{\"version\":2,\"entries\":[{\"na".getBytes("UTF-8"));
        LocalLibrary ignoringTmp = new LocalLibrary(directory);
        check(ignoringTmp.size() == 5, "半截的 index.json.tmp 既不是文档也不是索引");
        check(!ignoringTmp.entries().get(0).name.endsWith(".tmp"), "临时文件没混进条目列表");

        writeAll(new File(directory, "index.json"), "{\"version\":2,\"entries\":[{\"na".getBytes("UTF-8"));
        LocalLibrary rebuilt = new LocalLibrary(directory);
        check(rebuilt.size() == 5, "半截的 index.json 触发按目录重建，五篇文档都还在");
        CorpusImport.Batch redo = CorpusImport.run(rebuilt, listOf(source("换了名字的同一篇.txt", corpusLine(KEY_A))), null);
        check(redo.duplicates() == 1, "重建出来的索引补算哈希，重复照样挡住");
        rebuilt.index(new TextCorpus());
        String rewritten = new String(readAll(new File(directory, "index.json")), "UTF-8");
        check(ApiJson.parse(rewritten) != null && rewritten.indexOf("\"version\":2") >= 0,
                "重建之后下一次写盘又是完整索引");
    }
    // ---- PDF 进了自建库之后，真的参与本机比对 ----

    private static void pdfIntoCorpus() throws Exception {
        LocalLibrary library = library("corpus");
        CorpusImport.Batch batch = CorpusImport.run(library, listOf(
                new CorpusImport.Source("论文.pdf", readAll(new File("tests/fixture-cn.pdf")))), null);
        check(batch.imported() == 1, "PDF 进得了自建库");
        TextCorpus corpus = new TextCorpus();
        library.index(corpus);
        check(corpus.sentenceCount() >= 3, "PDF 正文进了比对语料，" + corpus.sentenceCount() + " 句");
        LocalLibrary.Entry entry = library.entries().get(0);
        check(entry.sentences >= 3, "条目记下了 PDF 抽出的句段数：" + entry.sentences);
        check(entry.hash.length() == 64, "落库的正文哈希是 SHA-256 十六进制 64 位");
        String lineA = corpusLine(KEY_A);
        String quoted = lineA.substring(60, 100);
        TextCorpus.Report report = corpus.match("这段是照着 PDF 抄的：" + quoted, null);
        check(!report.hits.isEmpty(), "抄自 PDF 正文的句子被本机比对抓到");
        check(!report.hits.isEmpty() && "local".equals(report.hits.get(0).source.engine), "命中来源标成 local");
        check(!report.hits.isEmpty() && "论文".equals(report.hits.get(0).source.title), "命中带上 PDF 的篇名");
    }

    /**
     * 用 PdfFile 自己的写入口造一份 PDF 再读回来。
     * 这一步证明"复用 PdfFile 的文字流"不是空话：导出侧那套 BT/Tf/Tm/Tj 加 ToUnicode，
     * 读侧一个字都不靠第三方库。故意不嵌字体程序——抽取只读 /Encoding 与 /ToUnicode。
     */
    private static void writerRoundTrip() throws Exception {
        String text = "多孔铜的孔径、孔隙率与孔道连通性会显著影响力学性能。";
        String expected = text + text;
        ArrayList<String> rows = new ArrayList<String>();
        rows.add(text);
        rows.add(text);
        byte[] pdf = writeWithPdfFile(rows);
        check(pdf.length > 300, "PdfFile 自己写出的 PDF 有 " + pdf.length + " 字节");
        PdfFile.Extracted back = PdfFile.extractText(pdf);
        check(back.pages == 1, "自写自读认出一页");
        check(back.textOps == 2, "两条 Tj 都数到了");
        check(!back.undecodable, "自写自读不报编码问题");
        check(expected.equals(back.text.replace("\n", "")), "PdfFile 写出的文字流原样读回：" + back.text.length() + " 字");
        check(PdfFile.extractText(pdf, 12).truncated, "带上限的抽取会如实标 truncated，不静默截断");
    }

    private static byte[] writeWithPdfFile(ArrayList<String> rows) throws Exception {
        java.util.LinkedHashMap<Character, Integer> glyphs = new java.util.LinkedHashMap<Character, Integer>();
        for (int r = 0; r < rows.size(); r++) {
            String row = rows.get(r);
            for (int i = 0; i < row.length(); i++) {
                char c = row.charAt(i);
                if (!glyphs.containsKey(Character.valueOf(c))) glyphs.put(Character.valueOf(c), Integer.valueOf(glyphs.size() + 1));
            }
        }
        StringBuilder operations = new StringBuilder();
        float y = 760f;
        for (int r = 0; r < rows.size(); r++) {
            StringBuilder hex = new StringBuilder();
            String row = rows.get(r);
            for (int i = 0; i < row.length(); i++) {
                hex.append(String.format(java.util.Locale.US, "%04X",
                        glyphs.get(Character.valueOf(row.charAt(i))).intValue()));
            }
            operations.append("BT /F1 12 Tf 1 0 0 1 60 ").append(PdfFile.number(y)).append(" Tm <")
                    .append(hex).append("> Tj ET\n");
            y -= 18f;
        }
        StringBuilder mapping = new StringBuilder("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n"
                + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
                + "/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n1 begincodespacerange\n<0000> <FFFF>\n"
                + "endcodespacerange\n" + glyphs.size() + " beginbfchar\n");
        for (java.util.Map.Entry<Character, Integer> item : glyphs.entrySet()) {
            mapping.append(String.format(java.util.Locale.US, "<%04X> <%04X>\n",
                    item.getValue().intValue(), (int) item.getKey().charValue()));
        }
        mapping.append("endbfchar\nendcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");

        PdfFile pdf = new PdfFile();
        int catalog = pdf.reserve();
        int pages = pdf.reserve();
        int page = pdf.reserve();
        int content = pdf.reserve();
        int font = pdf.reserve();
        int descendant = pdf.reserve();
        int toUnicode = pdf.reserve();
        pdf.stream(content, "", operations.toString().getBytes(StandardCharsets.ISO_8859_1), true);
        pdf.stream(toUnicode, "", mapping.toString().getBytes(StandardCharsets.ISO_8859_1), true);
        pdf.set(descendant, "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /RoundTrip /CIDSystemInfo "
                + "<< /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> /CIDToGIDMap /Identity /DW 1000 >>");
        pdf.set(font, "<< /Type /Font /Subtype /Type0 /BaseFont /RoundTrip /Encoding /Identity-H /DescendantFonts ["
                + descendant + " 0 R] /ToUnicode " + toUnicode + " 0 R >>");
        pdf.set(page, "<< /Type /Page /Parent " + pages + " 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 "
                + font + " 0 R >> >> /Contents " + content + " 0 R >>");
        pdf.set(pages, "<< /Type /Pages /Count 1 /Kids [" + page + " 0 R] >>");
        pdf.set(catalog, "<< /Type /Catalog /Pages " + pages + " 0 R >>");
        return pdf.finish(catalog);
    }

    /** 既有公开 API 的行为一个字都没改，这一节是本地版的守门（TextCorpusRegression 那份照旧要绿）。 */
    private static void libraryApiStillWorks() throws Exception {
        LocalLibrary library = library("api");
        check(library.capacity() == 400, "容量口径还是 400 篇");
        check(library.capacityRemaining() == 400, "空库剩满额名额");
        check(library.addDocument("一篇.txt", "两参版本仍然不去重，这是老语义。".getBytes("UTF-8")) == null,
                "两参 addDocument 照样能入库");
        check(library.addDocument("另一篇.txt", "两参版本仍然不去重，这是老语义。".getBytes("UTF-8")) == null,
                "两参 addDocument 撞了同一篇正文也照收：去重是新重载才有的");
        check(library.size() == 2 && library.capacityRemaining() == 398, "入库后名额随之减少");
        check(library.addDocument("tool.exe", new byte[]{1, 2, 3}) != null, "不支持的扩展名仍然被拒");
        check(library.addDocument("空的.txt", new byte[0]) != null, "空内容仍然被拒");
        String pdfError = library.addDocument("扫描版.pdf", readAll(new File("tests/fixture-scanned.pdf")));
        check(LocalLibrary.NO_TEXT_LAYER_MESSAGE.equals(pdfError), "两参版本也拒收扫描版 PDF：" + pdfError);
        boolean threw = false;
        try {
            LocalLibrary.textOf(new byte[]{1}, "x.rtf");
        } catch (IOException error) {
            threw = error.getMessage().indexOf("不支持") >= 0;
        }
        check(threw, "textOf 对未知类型仍然抛可读的 IOException");
        check(library.remove("一篇.txt") && library.size() == 1, "remove 仍然好用");
        library.clear();
        check(library.size() == 0, "clear 仍然清空整库");
        check(!new File(libraryDir("api"), "index.json.tmp").exists(), "常规写盘不留临时文件");
    }

    // ---- 夹具与断言小工具 ----

    private static final class ProgressLog implements CorpusImport.Progress {
        final ArrayList<String> lines = new ArrayList<String>();

        public void onReceipt(int done, int total, CorpusImport.Receipt receipt) {
            lines.add(done + "/" + total + " " + receipt.status);
        }

        boolean inOrder() {
            for (int i = 0; i < lines.size(); i++) {
                if (!lines.get(i).startsWith((i + 1) + "/" + lines.size() + " ")) return false;
            }
            return true;
        }
    }

    private static CorpusImport.Receipt receipt(CorpusImport.Batch batch, int index) {
        return batch.receipts.get(index);
    }

    private static CorpusImport.Status status(CorpusImport.Batch batch, int index) {
        return batch.receipts.get(index).status;
    }

    private static ArrayList<CorpusImport.Source> listOf(CorpusImport.Source... sources) {
        ArrayList<CorpusImport.Source> out = new ArrayList<CorpusImport.Source>();
        for (int i = 0; i < sources.length; i++) out.add(sources[i]);
        return out;
    }

    // ---- \u4e00\u952e\u628a\u5f00\u653e\u83b7\u53d6\u5168\u6587\u4e0b\u8fdb\u81ea\u5efa\u5e93 ----

    /** \u4e0b\u8f7d\u5165\u5e93\u4e0e\u666e\u901a\u5bfc\u5165\u5171\u7528\u90a3\u4e09\u6761\u89c4\u77e9\uff1a\u4e00\u4e2a\u6587\u4ef6\u4e00\u6761\u56de\u6267\uff0c\u574f\u94fe\u63a5\u4e0d\u8bb8\u62d6\u57ae\u6574\u6279\u3002 */
    private static void downloadedPicks() throws Exception {
        deleteRecursively(libraryDir("download"));   // \u4e0a\u4e00\u6b21\u5931\u8d25\u7684\u65e7\u5e93\u4e0d\u80fd\u628a\u8fd9\u4e00\u6279\u5224\u6210\u91cd\u590d
        LocalLibrary library = new LocalLibrary(libraryDir("download"));
        final byte[] pdf = readAll(new File("tests/fixture-cn.pdf"));
        CorpusImport.Batch batch = CorpusImport.download(library, java.util.Arrays.asList(
                new CorpusImport.Pick("\u5f00\u653e\u83b7\u53d6\u6837\u4f8b", "https://journal.example.org/a.pdf"),
                new CorpusImport.Pick("\u7a7a\u8fd4\u56de", "https://journal.example.org/empty.pdf"),
                new CorpusImport.Pick("\u94fe\u63a5\u574f\u4e86", "https://journal.example.org/boom.pdf")),
                new CorpusImport.Fetch() {
                    public byte[] get(String url) throws Exception {
                        if (url.endsWith("empty.pdf")) return new byte[0];
                        if (url.endsWith("boom.pdf")) throw new java.io.IOException("\u8fde\u4e0d\u4e0a");
                        return pdf;
                    }
                }, null, null);
        check(batch.total == 3 && batch.receipts.size() == 3, "\u4e09\u6761\u94fe\u63a5\u4e09\u6761\u56de\u6267\uff0c\u4e00\u6761\u574f\u7684\u4e0d\u8bb8\u5e26\u8d70\u5176\u5b83\u4e24\u6761");
        CorpusImport.Receipt ok = batch.receipts.get(0);
        check(ok.imported() && ok.storedName.equals("\u5f00\u653e\u83b7\u53d6\u6837\u4f8b.pdf") && ok.chars == 468
                        && ok.pages == 2, "\u4e0b\u56de\u6765\u7684 PDF \u6309\u666e\u901a PDF \u5165\u5e93\uff1a\u9898\u540d\u8865 .pdf\u3001\u5b57\u6570\u4e0e\u9875\u6570\u7167\u5b9e\u586b");
        check(batch.receipts.get(1).status == CorpusImport.Status.FAILED
                        && batch.receipts.get(1).message.equals("\u6ca1\u4e0b\u8f7d\u5230\u5185\u5bb9"), "\u7a7a\u8fd4\u56de\u5355\u72ec\u62a5\u201c\u6ca1\u4e0b\u8f7d\u5230\u5185\u5bb9\u201d");
        check(batch.receipts.get(2).message.contains("\u8fde\u4e0d\u4e0a"), "\u4e0b\u8f7d\u5931\u8d25\u628a\u539f\u56e0\u5e26\u56de\u6765\uff0c\u4e0d\u9759\u9ed8\u8df3\u8fc7");
        CorpusImport.Batch empty = CorpusImport.download(library,
                new java.util.ArrayList<CorpusImport.Pick>(),
                new CorpusImport.Fetch() { public byte[] get(String url) { return new byte[0]; } },
                null, null);
        check(empty.total == 0 && empty.receipts.isEmpty(), "\u6ca1\u6709\u5019\u9009\u65f6\u53ea\u7ed9\u4e00\u4efd\u7a7a\u56de\u6267\uff0c\u4e0d\u62a5\u9519");
    }

    private static CorpusImport.Source source(String name, String text) throws Exception {
        return new CorpusImport.Source(name, text.getBytes("UTF-8"));
    }

    private static LocalLibrary library(String name) {
        deleteRecursively(libraryDir(name));
        return new LocalLibrary(libraryDir(name));
    }

    private static File libraryDir(String name) {
        return new File(ROOT, name);
    }

    /** 按关键词取真实语料里的一行，不写死整句：语料改了这句话就跟着变。 */
    private static String corpusLine(String keyword) throws Exception {
        File corpus = new File("tests/corpus/real-prose.txt");
        InputStream in = new FileInputStream(corpus);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        in.close();
        String[] lines = new String(out.toByteArray(), "UTF-8").split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].indexOf(keyword) >= 0 && lines[i].trim().length() > 200) return lines[i].trim();
        }
        throw new AssertionError("语料里找不到足够长的一行：" + keyword);
    }

    private static int cjkCount(String text) {
        int total = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) >= 0x4E00 && text.charAt(i) <= 0x9FFF) total++;
        return total;
    }

    private static int countEnding(File[] files, String suffix) {
        int total = 0;
        for (int i = 0; i < files.length; i++) if (files[i].getName().endsWith(suffix)) total++;
        return total;
    }

    private static byte[] readAll(File file) throws Exception {
        InputStream in = new FileInputStream(file);
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(1024L, file.length()));
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        in.close();
        return out.toByteArray();
    }

    private static void writeAll(File file, byte[] content) throws Exception {
        file.getParentFile().mkdirs();
        FileOutputStream out = new FileOutputStream(file);
        out.write(content);
        out.close();
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (int i = 0; i < children.length; i++) deleteRecursively(children[i]);
        }
        file.delete();
    }
}