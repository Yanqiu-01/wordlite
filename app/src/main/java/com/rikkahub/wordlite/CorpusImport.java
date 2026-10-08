package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自建库批量导入（ROADMAP 0.6.2 的"一次选多个 TXT/DOCX，逐文件进度、按正文哈希去重"，
 * 外加 0.6.3 的 PDF 通道）。界面只管把选中的文件读成字节交进来，拿回一批回执。
 *
 * 三条硬规矩，每条都有自己的断言守着：
 * 一个坏文件不许拖垮一批——逐文件 try/catch，异常只落在自己那条回执上；
 * 去重认正文不认文件名——同名不同内容必须入库两次，改名不改正文必须只入库一次；
 * 扫描版 PDF 不是"导入成功的空文件"——单独一档 NO_TEXT_LAYER，零字符且不落盘。
 */
public final class CorpusImport {
    private CorpusImport() { }

    /** 一个待导入文件：显示名 + 内容字节。SAF 那边读成 byte[] 再交进来，本类不碰 Context。 */
    public static final class Source {
        public final String name;
        public final byte[] content;
        /**
         * 题录级：这一份只有题名/作者/刊名/关键词/摘要，一个字的正文都没有（RecordImport 那一路）。
         * 落库带上这个档，比对与来源榜才知道这里的命中只能算摘要级证据。
         */
        public boolean recordLevel;
        /**
         * 材料档（DuplicateEngine.MATERIAL_* 之一）：手机渲染详情页取回的那一批走这一格，
         * 拿到的多是"全文精要"而不是整篇正文，档位不写清楚就会被读成正文级证据。
         * recordLevel 是它的旧写法（只有摘要档），两者都给时以本字段为准。
         */
        public String material = "";

        public Source(String name, byte[] content) {
            this.name = name == null ? "" : name;
            this.content = content;
        }
    }

    public enum Status { IMPORTED, DUPLICATE, UNSUPPORTED, NO_TEXT_LAYER, FAILED }

    /** 一个文件一条回执，界面按它渲染 "3/17" 与每行的结果。 */
    public static final class Receipt {
        public int number;
        public String name = "";
        public Status status = Status.FAILED;
        public String message = "";
        /** 落库后的文件名：重名会被 LocalLibrary 加序号，界面要显示的是这个而不是原名字。 */
        public String storedName = "";
        public String duplicateOf = "";
        /** 参与比对的有效字符数（去空白与不可见字符），不是原始字符数。 */
        public int chars;
        /** 切出的句段数，含过短被比对引擎丢掉的那些，只用来做进度说明。 */
        public int spans;
        /** PDF 才有意义；其它类型恒为 0。 */
        public int pages;

        public boolean imported() {
            return status == Status.IMPORTED;
        }

        /** 一行能读完这一篇的下场：编号、结果、题名、进了库的文件名与字数，没成则说为什么。 */
        public String describe() {
            StringBuilder out = new StringBuilder().append(number).append(". ").append(statusLabel(status));
            if (name.length() > 0) out.append(' ').append(name);
            if (status == Status.IMPORTED) {
                out.append(" → ").append(storedName).append("（").append(chars).append(" 字");
                if (pages > 0) out.append(" / ").append(pages).append(" 页");
                out.append('）');
                // 只有带了档位的那几批才会有这句话，普通导入的回执一个字都不变。
                if (message.length() > 0) out.append(" · ").append(message);
            } else if (status == Status.DUPLICATE) {
                out.append(" → 库里已有同一篇：").append(duplicateOf);
            } else {
                out.append(" → ").append(message.length() == 0 ? "没进库" : message);
            }
            return out.toString();
        }

        private static String statusLabel(Status status) {
            if (status == Status.IMPORTED) return "已入库";
            if (status == Status.DUPLICATE) return "重复";
            if (status == Status.NO_TEXT_LAYER) return "无文字层";
            if (status == Status.UNSUPPORTED) return "不支持";
            return "失败";
        }
    }

    public static final class Batch {
        public int total;
        /** 被取消（或界面回调炸了）而提前收工；已处理完的回执仍然有效。 */
        public boolean cancelled;
        public final ArrayList<Receipt> receipts = new ArrayList<Receipt>();

        public int count(Status status) {
            int total = 0;
            for (int i = 0; i < receipts.size(); i++) if (receipts.get(i).status == status) total++;
            return total;
        }

        public int imported() { return count(Status.IMPORTED); }
        public int duplicates() { return count(Status.DUPLICATE); }
        public int unsupported() { return count(Status.UNSUPPORTED); }
        public int noTextLayer() { return count(Status.NO_TEXT_LAYER); }
        public int failed() { return count(Status.FAILED); }

        /** 这批量实际进了多少个字：界面拿它证明"没进库的那些不是白跑"。 */
        public int importedChars() {
            int total = 0;
            for (int i = 0; i < receipts.size(); i++) if (receipts.get(i).imported()) total += receipts.get(i).chars;
            return total;
        }

        /** 第一条非成功的说明，够界面拼一句尾巴；全成功返回空串。 */
        public String firstProblem() {
            for (int i = 0; i < receipts.size(); i++) {
                Receipt receipt = receipts.get(i);
                if (!receipt.imported() && receipt.message.length() > 0) return receipt.message;
            }
            return "";
        }

        /**
         * 逐篇一行的结果，"下进自建库"那一步直接贴它。
         * 为什么不让界面自己拼：以前那一格只把 batch.receipts.get(0) 的说明弹给用户看，
         * 一批十篇里九篇失败也只有第一条露得出脸，剩下的成没成没人知道。
         */
        public String detail() {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < receipts.size(); i++) {
                if (i > 0) out.append('\n');
                out.append(receipts.get(i).describe());
            }
            if (cancelled)
                out.append("\n已取消，剩余 ").append(Math.max(0, total - receipts.size())).append(" 篇未处理");
            return out.toString();
        }

        public String summary() {
            StringBuilder out = new StringBuilder("导入 ").append(imported()).append(" 篇");
            if (duplicates() > 0) out.append("，重复跳过 ").append(duplicates()).append(" 篇");
            if (noTextLayer() > 0) out.append("，无文字层 ").append(noTextLayer()).append(" 篇");
            if (unsupported() > 0) out.append("，不支持 ").append(unsupported()).append(" 个");
            if (failed() > 0) out.append("，失败 ").append(failed()).append(" 个");
            if (cancelled) out.append("（已取消，剩余未处理）");
            return out.toString();
        }
    }

    /** 每处理完一个文件回调一次：done 从 1 数到 total，receipt 已经是终态。 */
    public interface Progress {
        void onReceipt(int done, int total, Receipt receipt);
    }

    /** 取消问一句答一句，由调用方决定（取消按钮、后台任务被回收、超时都算）。 */
    public interface Cancel {
        boolean cancelled();
    }

    public static Batch run(LocalLibrary library, List<Source> sources, Progress progress) {
        return run(library, sources, progress, null);
    }

    /**
     * 跑一批。返回的 Batch 里每个被处理过的文件都有一条回执；
     * 被取消时后面那些文件根本没有回执，而不是伪造一堆"失败"。
     */
    public static Batch run(LocalLibrary library, List<Source> sources, Progress progress, Cancel cancel) {
        if (library == null) throw new IllegalArgumentException("library == null");
        ArrayList<Source> items = sources == null ? new ArrayList<Source>() : new ArrayList<Source>(sources);
        Batch batch = new Batch();
        batch.total = items.size();
        // 本批内部的哈希表：同一个合集里塞了同一篇论文的三个副本，只该进一次。
        Map<String, String> seen = new HashMap<String, String>();
        for (int i = 0; i < items.size(); i++) {
            if (cancel != null && cancel.cancelled()) {
                batch.cancelled = true;
                break;
            }
            Receipt receipt = new Receipt();
            receipt.number = i + 1;
            receipt.name = items.get(i).name;
            try {
                importOne(library, items.get(i), receipt, seen);
            } catch (Exception error) {
                // 一个坏文件不许拖垮一批：异常只记在自己这条回执上，循环继续走下一个。
                receipt.status = Status.FAILED;
                receipt.message = describe(error);
            }
            batch.receipts.add(receipt);
            if (progress == null) continue;
            try {
                progress.onReceipt(receipt.number, batch.total, receipt);
            } catch (Exception error) {
                // 界面回调炸了（视图销毁、Activity 没了）按取消处理：已经进库的照样留在库里。
                batch.cancelled = true;
                break;
            }
        }
        return batch;
    }

    /** 这一份该落哪个档：material 优先，退到旧的 recordLevel 布尔，再退到正文（导进库的原文）。 */
    private static String tierOf(Source source) {
        if (source.material != null && source.material.length() > 0) return source.material;
        return source.recordLevel ? DuplicateEngine.MATERIAL_ABSTRACT : "";
    }

    private static void importOne(LocalLibrary library, Source source, Receipt receipt, Map<String, String> seen) {
        String safe = LocalLibrary.sanitize(source.name);
        if (safe == null) {
            receipt.status = Status.FAILED;
            receipt.message = "文件名无效";
            return;
        }
        receipt.name = safe;
        String extension = LocalLibrary.extensionOf(safe);
        if (!LocalLibrary.isSupported(extension)) {
            // 先看扩展名再决定读不读：一个文件夹里几十个文件，.exe 不该花一次解析的钱。
            receipt.status = Status.UNSUPPORTED;
            receipt.message = extension.length() == 0 ? "没有扩展名，看不出是什么文件" : "不支持的文件类型：." + extension;
            return;
        }
        if (source.content == null || source.content.length == 0) {
            // 空文件不值得走一次解析，也不该和"解析完发现没正文"共用一句说明。
            receipt.status = Status.FAILED;
            receipt.message = "文件内容为空";
            return;
        }
        if (library.capacityRemaining() <= 0) {
            receipt.status = Status.FAILED;
            receipt.message = "自建库名额已满（上限 " + library.capacity() + " 个文件）";
            return;
        }
        LocalLibrary.Extract extract;
        try {
            extract = LocalLibrary.extract(safe, source.content);
        } catch (Exception error) {
            receipt.status = Status.FAILED;
            receipt.message = describe(error);
            return;
        }
        receipt.pages = extract.pages;
        if (extract.noTextLayer) {
            // 扫描版：一个字都没读到是事实，不是成功。落盘也只是占地方不参与比对。
            receipt.status = Status.NO_TEXT_LAYER;
            receipt.chars = 0;
            // 文案只有一份：批量回执与 addDocument 的说法必须一字不差，界面上才会出现一句解释。
            receipt.message = LocalLibrary.NO_TEXT_LAYER_MESSAGE;
            return;
        }
        String text = extract.text == null ? "" : extract.text;
        String normalized = TextCorpus.normalize(text);
        receipt.chars = TextCorpus.validCount(normalized, 0, normalized.length());
        receipt.spans = TextCorpus.sentences(text).size();
        if (receipt.chars == 0) {
            // 有文字算子却拼不出一个有效字符 = 字体编码读不出，跟"文件本来就是空的"分开报。
            receipt.status = Status.FAILED;
            receipt.message = extract.undecodable
                    ? "PDF 有文字流，但字体编码映射不出文字"
                            + (extract.undecodableNote.isEmpty() ? "" : "：" + extract.undecodableNote)
                    : "没有从文件里读到文本";
            return;
        }
        String hash = LocalLibrary.bodyHash(text);
        String twin = seen.get(hash);
        if (twin != null) {
            receipt.status = Status.DUPLICATE;
            receipt.duplicateOf = twin;
            receipt.message = "本批已有同一篇正文：" + twin;
            return;
        }
        String material = tierOf(source);
        LocalLibrary.AddResult added = library.addDocument(safe, source.content, hash, true, material);
        if (added.ok) {
            receipt.status = Status.IMPORTED;
            receipt.storedName = added.name;
            // 档位写进回执：一篇"只有精要可读"的材料进库，用户要在回执上看得见这件事。
            if (DuplicateEngine.MATERIAL_ABSTRACT.equals(material)) receipt.message = "摘要级入库";
            else if (material.length() > 0) receipt.message = CorpusLedger.materialLabel(material) + "入库";
            seen.put(hash, added.name);
            return;
        }
        if (added.duplicateOf.length() > 0) {
            receipt.status = Status.DUPLICATE;
            receipt.duplicateOf = added.duplicateOf;
            receipt.message = "库里已有同一篇正文：" + added.duplicateOf;
            seen.put(hash, added.duplicateOf);   // 同一篇后面再来一次要按"批内重复"处理，别去重算一遍磁盘
            return;
        }
        receipt.status = Status.FAILED;
        receipt.message = added.error.length() == 0 ? "入库失败" : added.error;
    }

    /** 一条"从链接下载后入库"的候选：显示名 + PDF 直链。名字由调用方定（一般是题名）。 */
    public static final class Pick {
        public final String name;
        public final String url;
        public Pick(String name, String url) {
            this.name = name == null ? "" : name;
            this.url = url == null ? "" : url;
        }
    }

    /** 取字节这一步由调用方给（Android 上是 PaperSources.downloadPdf，回归测试里是回环服务器）。 */
    public interface Fetch {
        byte[] get(String url) throws Exception;
    }

    /**
     * 把一批候选的 PDF 下回来再走一遍普通导入。
     * 下载失败与导入失败同样一文件一条回执，一个坏链接不许拖垮整批——批量导入那三条规矩一条不改。
     */
    public static Batch download(LocalLibrary library, List<Pick> picks, Fetch fetch,
                                 Progress progress, Cancel cancel) {
        if (library == null) throw new IllegalArgumentException("library == null");
        if (fetch == null) throw new IllegalArgumentException("fetch == null");
        ArrayList<Pick> items = picks == null ? new ArrayList<Pick>() : new ArrayList<Pick>(picks);
        Batch batch = new Batch();
        batch.total = items.size();
        Map<String, String> seen = new HashMap<String, String>();
        for (int i = 0; i < items.size(); i++) {
            if (cancel != null && cancel.cancelled()) { batch.cancelled = true; break; }
            Pick pick = items.get(i);
            Receipt receipt = new Receipt();
            receipt.number = i + 1;
            receipt.name = pick == null ? "" : safeName(pick);
            try {
                byte[] bytes = pick == null || pick.url.trim().isEmpty()
                        ? null : fetch.get(pick.url.trim());
                if (bytes == null || bytes.length == 0) {
                    receipt.status = Status.FAILED;
                    receipt.message = "没下载到内容";
                } else {
                    importOne(library, new Source(receipt.name, bytes), receipt, seen);
                }
            } catch (Exception error) {
                receipt.status = Status.FAILED;
                receipt.message = describe(error);
            }
            batch.receipts.add(receipt);
            if (progress == null) continue;
            try {
                progress.onReceipt(receipt.number, batch.total, receipt);
            } catch (Exception error) {
                batch.cancelled = true;
                break;
            }
        }
        return batch;
    }

    /** 下载入库的文件名：题名净化后补 .pdf，空题名退到链接主机名，绝不带 URL 里的查询串。 */
    private static String safeName(Pick pick) {
        String title = pick.name.trim();
        if (title.length() > 0) return title.endsWith(".pdf") ? title : title + ".pdf";
        String host = PaperSources.hostOf(pick.url);
        return (host.isEmpty() ? "open-access" : host) + ".pdf";
    }

    private static String describe(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        if (message == null || message.trim().length() == 0) {
            return error == null ? "未知错误" : error.getClass().getSimpleName();
        }
        return message.trim();
    }
}