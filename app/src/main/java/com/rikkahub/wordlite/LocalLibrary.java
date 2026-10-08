package com.rikkahub.wordlite;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 自建文献库：文档写进应用私有目录，index.json 存元数据，index() 把正文灌进 TextCorpus 参与本机比对。 */
public final class LocalLibrary {
    public static final class Entry {
        public String name = "";
        public long bytes;
        public long addedAt;
        public int sentences;
        /** 正文哈希（归一化正文的 SHA-256）。version 1 的索引没这个字段，读到空值就补算。 */
        public String hash = "";
    }

    /** 抽取结果：正文之外还得说清"为什么没有正文"，扫描版和空文件不是一回事。 */
    public static final class Extract {
        public String text = "";
        public int pages;
        /** PDF 一页文字算子都没有 = 扫描版，得先 OCR。 */
        public boolean noTextLayer;
        /** PDF 有文字算子，但字体编码映射不出 Unicode。 */
        public boolean undecodable;
        /** 映射不出的是哪些字、有多少：回执与报告用它把"少字"说成一句有出处的事实。 */
        public String undecodableNote = "";
    }

    /** 入库回执：批量导入要知道落库的文件名与"重复撞在哪一篇上"，只回一个错误串不够用。 */
    public static final class AddResult {
        public boolean ok;
        public String name = "";
        public String error = "";
        public String duplicateOf = "";
    }

    private static final String INDEX_NAME = "index.json";
    /**
     * 版本 2 起每条带正文哈希。批量导入是一串连续写盘，中途取消或进程被杀都是常态，
     * 所以索引必须任何时候都读得回来，见 persist() 的临时文件 + 改名。
     */
    private static final int INDEX_VERSION = 2;
    private static final String INDEX_TMP_NAME = "index.json.tmp";
    /** 扫描版 PDF 单独一句口径：它不是"读出来正好是空"，是压根没有文字层。 */
    public static final String NO_TEXT_LAYER_MESSAGE = "这个 PDF 没有文字层（扫描版），请先转成可复制文字的 PDF";
    private static final long MAX_DOCUMENT_BYTES = 24L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 96L * 1024 * 1024;
    private static final int MAX_ENTRIES = 400;
    private static final int MAX_NAME_CHARS = 120;
    private static final int MAX_TEXT_CHARS = 4 * 1024 * 1024;

    private final File directory;
    private final ArrayList<Entry> entries = new ArrayList<Entry>();
    private boolean loaded;

    /** 只接 File：由调用方给出应用私有目录，本类不碰 Context。 */
    public LocalLibrary(File directory) {
        this.directory = directory == null ? new File(".") : directory;
    }

    /** 库内文件清单（列表是副本，条目对象与内部一致）。 */
    public ArrayList<Entry> entries() {
        load();
        return new ArrayList<Entry>(entries);
    }

    public int size() {
        load();
        return entries.size();
    }

    /** 返回错误信息，成功返回 null。文件名净化后写入私有目录，重名自动加序号，不做去重。 */
    public String addDocument(String fileName, byte[] content) {
        AddResult result = addDocument(fileName, content, null, false);
        return result.ok ? null : result.error;
    }

    /**
     * 批量导入入口：正文哈希由调用方算好传进来（一份文件只抽一次正文），
     * skipDuplicates 为真时撞上同一正文就整个跳过，不落盘也不动索引。
     * 两参版本的语义一个字没改——它从来不去重，去重只在新重载里生效。
     */
    public AddResult addDocument(String fileName, byte[] content, String bodyHash, boolean skipDuplicates) {
        AddResult result = new AddResult();
        load();
        if (content == null || content.length == 0) return fail(result, "文件内容为空");
        if (content.length > MAX_DOCUMENT_BYTES) return fail(result, "单个文件不能超过 24 MB");
        String safe = sanitize(fileName);
        if (safe == null) return fail(result, "文件名无效");
        if (!isSupported(extensionOf(safe))) return fail(result, "只支持 .docx/.txt/.md/.pdf 文件");
        String text;
        try {
            Extract extract = extract(safe, content);
            // 扫描版 PDF 单独一档：落进库也只占地方不参与比对，用户会误以为这篇没抄。
            if (extract.noTextLayer) return fail(result, NO_TEXT_LAYER_MESSAGE);
            text = extract.text;
        } catch (Exception error) {
            return fail(result, describe(error));
        }
        if (text.trim().length() == 0) return fail(result, "没有从文件里读到文本");
        String hash = bodyHash == null || bodyHash.length() == 0 ? bodyHash(text) : bodyHash;
        if (skipDuplicates) {
            String known = nameForHash(hash);
            if (known != null) {
                result.duplicateOf = known;
                return fail(result, "库里已有同一篇正文：" + known);
            }
        }
        if (entries.size() >= MAX_ENTRIES) return fail(result, "自建库最多 " + MAX_ENTRIES + " 个文件");
        long total = 0L;
        for (int i = 0; i < entries.size(); i++) total += entries.get(i).bytes;
        if (total + content.length > MAX_TOTAL_BYTES) return fail(result, "自建库容量已满（上限 96 MB）");
        if (!directory.isDirectory() && !directory.mkdirs()) return fail(result, "无法创建自建库目录");
        File target = place(uniqueName(safe));
        if (target == null) return fail(result, "文件名越出自建库目录");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(target);
            out.write(content);
        } catch (IOException error) {
            return fail(result, "写入失败：" + describe(error));
        } finally {
            close(out);
        }
        Entry entry = new Entry();
        entry.name = target.getName();
        entry.bytes = content.length;
        entry.addedAt = System.currentTimeMillis();
        entry.sentences = 0;
        entry.hash = hash;
        entries.add(entry);
        // 每成功一篇就落一次索引：批量跑到一半被杀，已经进来的那些不至于看不见。
        persist();
        result.ok = true;
        result.name = entry.name;
        return result;
    }

    private static AddResult fail(AddResult result, String error) {
        result.error = error;
        return result;
    }

    /** 还剩几个名额：批量导入靠它提前收工，而不是把剩下几十个文件全撞成失败回执。 */
    public int capacityRemaining() {
        load();
        return Math.max(0, MAX_ENTRIES - entries.size());
    }

    /** 上限本身也要说得出：界面那句"自建库名额已满（上限 400 个文件）"不该把数字抄两份。 */
    public int capacity() {
        return MAX_ENTRIES;
    }

    /** 删除库内文件与条目，成功返回 true。 */
    public boolean remove(String name) {
        load();
        String wanted = name == null ? "" : name.trim();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (!entry.name.equals(wanted)) continue;
            File file = place(entry.name);
            if (file != null && file.isFile() && !file.delete()) return false;
            entries.remove(i);
            persist();
            return true;
        }
        return false;
    }

    public void clear() {
        load();
        for (int i = 0; i < entries.size(); i++) {
            File file = place(entries.get(i).name);
            if (file != null && file.isFile()) file.delete();
        }
        entries.clear();
        persist();
    }

    /** 把库里每个文档的正文按句子灌入语料，并回写各条目的句子数。 */
    public void index(TextCorpus corpus) {
        load();
        if (corpus == null) return;
        boolean changed = false;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            File file = place(entry.name);
            if (file == null || !file.isFile()) continue;
            String text;
            try {
                text = textOf(readBytes(file), entry.name);
            } catch (Exception error) {
                entry.sentences = 0;
                changed = true;
                continue;
            }
            if (entry.hash == null || entry.hash.length() == 0) entry.hash = bodyHash(text);
            int before = corpus.sentenceCount();
            corpus.add(sourceOf(entry), text);
            entry.sentences = corpus.sentenceCount() - before;
            changed = true;
        }
        if (changed) persist();
    }

    private static TextCorpus.Source sourceOf(Entry entry) {
        TextCorpus.Source source = new TextCorpus.Source();
        source.id = "local:" + entry.name;
        source.title = stripExtension(entry.name);
        source.engine = "local";
        source.locator = entry.name;
        source.year = "";
        source.authors = "";
        return source;
    }

    /** .docx 走 DocxParser，.txt/.md 按 UTF-8（容忍 BOM），.pdf 走 PdfFile 的文字流。 */
    public static String textOf(byte[] content, String fileName) throws IOException {
        return extract(fileName, content).text;
    }

    /**
     * 抽正文，并把"没有正文"的两种原因分开带回来。
     * PDF 复用 PdfFile.extractText（导出侧那套文字流的反向读法），不引第三方 PDF 库。
     * 扫描版与"字体编码读不出来"绝不能都塞成一份成功的空正文：空正文进了比对基线
     * 只会白白摊薄分母，用户还以为是这篇论文没抄。
     */
    public static Extract extract(String fileName, byte[] content) throws IOException {
        Extract result = new Extract();
        if (content == null || content.length == 0) return result;
        String extension = extensionOf(fileName == null ? "" : fileName);
        if ("docx".equals(extension)) {
            result.text = docxText(content, fileName);
            return result;
        }
        if ("txt".equals(extension) || "md".equals(extension)) {
            String text = new String(content, "UTF-8");
            if (text.length() > 0 && text.charAt(0) == 0xFEFF) text = text.substring(1);
            result.text = text.length() <= MAX_TEXT_CHARS ? text : text.substring(0, MAX_TEXT_CHARS);
            return result;
        }
        if ("pdf".equals(extension)) {
            PdfFile.Extracted pdf = PdfFile.extractText(content, MAX_TEXT_CHARS);
            result.pages = pdf.pages;
            result.undecodable = pdf.undecodable;
            result.undecodableNote = pdf.undecodableGlyphs > 0
                    ? pdf.undecodableGlyphs + " 个字形映射不出"
                            + (pdf.undecodableFonts.isEmpty() ? "" : "，字体：" + pdf.undecodableFonts)
                    : "";
            result.noTextLayer = pdf.textOps == 0;
            result.text = pdf.text;
            return result;
        }
        throw new IOException("不支持的文件类型：" + fileName);
    }

    private static String docxText(byte[] content, String fileName) throws IOException {
        DocxDocument document;
        try {
            document = DocxParser.parse(new ByteArrayInputStream(content), fileName);
        } catch (Exception error) {
            throw new IOException("无法解析 docx：" + describe(error));
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < document.paragraphs.size(); i++) {
            DocxDocument.ParagraphBlock paragraph = document.paragraphs.get(i);
            if (paragraph == null || paragraph.text.length() == 0) continue;
            if (out.length() > 0) out.append('\n');
            out.append(paragraph.text);
            if (out.length() > MAX_TEXT_CHARS) break;
        }
        return out.toString();
    }

    /** 净化文件名：剥掉路径、控制字符与非法字符，压掉点号串，限制长度。 */
    static String sanitize(String fileName) {
        if (fileName == null) return null;
        String name = fileName.trim();
        int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (cut >= 0) name = name.substring(cut + 1);
        if (name.length() >= 2 && name.charAt(1) == ':' && Character.isLetter(name.charAt(0))) {
            name = name.substring(2);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length() && out.length() <= MAX_NAME_CHARS; i++) {
            char c = name.charAt(i);
            if (Character.isISOControl(c) || TextCorpus.ignorable(c)) continue;
            if (c >= 0x7F && c <= 0x9F) continue;
            if ("\\/:*?\"<>|".indexOf(c) >= 0) {
                out.append('_');
                continue;
            }
            if (c == ' ') {
                if (out.length() == 0) continue;
                out.append(' ');
                continue;
            }
            out.append(c);
        }
        String collapsed = out.toString().replaceAll("\\.{2,}", ".");
        while (collapsed.length() > 0 && (collapsed.charAt(0) == '.' || collapsed.charAt(0) == ' ')) {
            collapsed = collapsed.substring(1);
        }
        while (collapsed.length() > 0
                && (collapsed.charAt(collapsed.length() - 1) == '.' || collapsed.charAt(collapsed.length() - 1) == ' ')) {
            collapsed = collapsed.substring(0, collapsed.length() - 1);
        }
        if (collapsed.length() == 0) return null;
        if (collapsed.length() > MAX_NAME_CHARS) collapsed = collapsed.substring(0, MAX_NAME_CHARS);
        if (INDEX_NAME.equalsIgnoreCase(collapsed)) return null;
        if (extensionOf(collapsed).length() == 0) collapsed = collapsed + ".txt";
        return collapsed;
    }

    private String uniqueName(String name) {
        String candidate = name;
        int tail = 2;
        while (taken(candidate)) {
            String base = stripExtension(name);
            String extension = extensionOf(name);
            candidate = extension.length() == 0
                    ? base + "_" + tail : base + "_" + tail + "." + extension;
            tail++;
            if (tail > 999) break;
        }
        return candidate;
    }

    private boolean taken(String name) {
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).name.equals(name)) return true;
        File file = place(name);
        return file != null && file.exists();
    }

    /** 目录内的目标文件；越界返回 null。 */
    private File place(String name) {
        if (name == null || name.length() == 0) return null;
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) return null;
        if (INDEX_NAME.equalsIgnoreCase(name)) return null;
        File file = new File(directory, name);
        try {
            String root = directory.getCanonicalPath();
            String path = file.getCanonicalPath();
            if (!path.startsWith(root + File.separator) && !path.equals(root)) return null;
        } catch (IOException error) {
            return null;
        }
        return file;
    }

    static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        String extension = name.substring(dot + 1).toLowerCase(java.util.Locale.US);
        return extension.length() <= 8 ? extension : "";
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    /** pdf 也进自建库：正文由 PdfFile 的文字流读回来，扫描版在 addDocument 里就被挡掉。 */
    static boolean isSupported(String extension) {
        return "docx".equals(extension) || "txt".equals(extension) || "md".equals(extension)
                || "pdf".equals(extension);
    }

    // ---- index.json 持久化 ----

    private void load() {
        if (loaded) return;
        loaded = true;
        entries.clear();
        File index = new File(directory, INDEX_NAME);
        if (index.isFile()) {
            try {
                Object root = ApiJson.parse(new String(readBytes(index), "UTF-8"));
                Object list = ApiJson.path(root, "$.entries");
                if (list instanceof List) {
                    for (Object item : (List<?>) list) {
                        if (!(item instanceof Map)) continue;
                        Map<?, ?> map = (Map<?, ?>) item;
                        Object name = map.get("name");
                        if (!(name instanceof String)) continue;
                        Entry entry = new Entry();
                        entry.name = (String) name;
                        entry.bytes = number(map.get("bytes"));
                        entry.addedAt = number(map.get("addedAt"));
                        entry.sentences = (int) number(map.get("sentences"));
                        Object hash = map.get("hash");
                        entry.hash = hash instanceof String ? (String) hash : "";
                        if (place(entry.name) == null || !isSupported(extensionOf(entry.name))) continue;
                        entries.add(entry);
                    }
                }
                return;
            } catch (Exception error) {
                entries.clear();
            }
        }
        rebuildFromDirectory();
    }

    /** 索引缺失或损坏时按目录里的文件重建，句子数留空等下一次 index() 回写。 */
    private void rebuildFromDirectory() {
        File[] files = directory.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files, new java.util.Comparator<File>() {
            public int compare(File left, File right) {
                return left.getName().compareToIgnoreCase(right.getName());
            }
        });
        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            if (file == null || !file.isFile()) continue;
            // 临时文件与索引本身都不是文档；.tmp 的扩展名本来也进不来，写明白一点省得以后误删。
            if (INDEX_NAME.equalsIgnoreCase(file.getName()) || INDEX_TMP_NAME.equalsIgnoreCase(file.getName())) continue;
            if (!isSupported(extensionOf(file.getName()))) continue;
            Entry entry = new Entry();
            entry.name = file.getName();
            entry.bytes = file.length();
            entry.addedAt = file.lastModified();
            entry.sentences = 0;
            entries.add(entry);
        }
        if (!entries.isEmpty()) persist();
    }

    private void persist() {
        if (!directory.isDirectory() && !directory.mkdirs()) return;
        StringBuilder out = new StringBuilder(96 + entries.size() * 96);
        out.append("{\"version\":").append(INDEX_VERSION).append(",\"entries\":[");
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (i > 0) out.append(',');
            out.append("{\"name\":").append(ApiJson.quote(entry.name))
                    .append(",\"bytes\":").append(entry.bytes)
                    .append(",\"addedAt\":").append(entry.addedAt)
                    .append(",\"sentences\":").append(entry.sentences)
                    .append(",\"hash\":").append(ApiJson.quote(entry.hash == null ? "" : entry.hash))
                    .append('}');
        }
        out.append("]}");
        writeIndex(out.toString());
    }

    /**
     * 原子换索引：先写 index.json.tmp，再改名盖掉 index.json。
     * 批量导入中途被杀时，磁盘上要么是整个旧索引、要么是整个新索引，
     * 不会出现半截 JSON 触发"按目录重建"把哈希与句子数全丢掉。
     * Android/Linux 的 rename 盖已存在文件是原子的；主机回归跑在 Windows 上才需要退一步先删。
     */
    private void writeIndex(String payload) {
        File target = new File(directory, INDEX_NAME);
        File temporary = new File(directory, INDEX_TMP_NAME);
        FileOutputStream stream = null;
        try {
            stream = new FileOutputStream(temporary);
            stream.write(payload.getBytes("UTF-8"));
            stream.flush();
            close(stream);
            stream = null;
            if (temporary.renameTo(target)) return;
            if (target.isFile() && !target.delete()) return;
            temporary.renameTo(target);
        } catch (IOException error) {
            // 写不进磁盘时保留内存状态，下一次操作再试；读取端有目录重建兜底。
        } finally {
            close(stream);
            if (temporary.isFile()) temporary.delete();
        }
    }

    /** 这篇正文是否已在库里？返回撞上的那个文件名，没有返回 null。 */
    public String nameForHash(String hash) {
        load();
        if (hash == null || hash.length() == 0) return null;
        ensureHashes();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (hash.equals(entry.hash)) return entry.name;
        }
        return null;
    }

    public boolean containsHash(String hash) {
        return nameForHash(hash) != null;
    }

    /**
     * 正文哈希 = 归一化正文的 SHA-256。
     * 为什么用 TextCorpus.compactOf 而不是原始字节：去重判的是"正文是否同一篇"，
     * 文件名、打包差异、全半角与繁简折叠、行尾空白都不该让它变成两篇——
     * 这些差异在比对引擎眼里本来就是同一段文字。原始字节哈希会把同一篇 docx
     * 换个文件名再存一遍算成两篇，那正是 0.6.2 要治的病。
     */
    public static String bodyHash(String text) {
        String compact = TextCorpus.compactOf(text == null ? "" : text);
        if (compact.length() == 0) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(compact.getBytes("UTF-8"));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (int i = 0; i < bytes.length; i++) out.append(String.format(java.util.Locale.US, "%02x", bytes[i] & 255));
            return out.toString();
        } catch (Exception error) {
            return "";   // SHA-256 是 JVM 必备算法，真拿不到就当没算出来，让调用方按"没重复"处理
        }
    }

    /** 版本 1 的索引没有 hash：按文件补算一次并回写，之后靠 add/remove/index 维护。 */
    private void ensureHashes() {
        boolean changed = false;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.hash != null && entry.hash.length() > 0) continue;
            File file = place(entry.name);
            if (file == null || !file.isFile()) continue;
            try {
                // 读不出正文就留空串：宁可漏判一次重复，也不拿空串的哈希去误杀新文件。
                entry.hash = bodyHash(extract(entry.name, readBytes(file)).text);
                changed = true;
            } catch (Exception error) {
                entry.hash = "";
            }
        }
        if (changed) persist();
    }

    File directoryFile() {
        return directory;
    }

    private static long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static byte[] readBytes(File file) throws IOException {
        long length = file.length();
        if (length > MAX_DOCUMENT_BYTES) throw new IOException("文件超过 24 MB");
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(length > 0 ? (int) length : 4096);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static void close(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
            // 关闭失败不影响已经写入的内容
        }
    }

    private static String describe(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        if (message == null || message.trim().length() == 0) {
            return error == null ? "未知错误" : error.getClass().getSimpleName();
        }
        return message.trim();
    }
}
