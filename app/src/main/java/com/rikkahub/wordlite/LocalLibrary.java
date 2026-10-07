package com.rikkahub.wordlite;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
    }

    private static final String INDEX_NAME = "index.json";
    private static final int INDEX_VERSION = 1;
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

    /** 返回错误信息，成功返回 null。文件名净化后写入私有目录，重名自动加序号。 */
    public String addDocument(String fileName, byte[] content) {
        load();
        if (content == null || content.length == 0) return "文件内容为空";
        if (content.length > MAX_DOCUMENT_BYTES) return "单个文件不能超过 24 MB";
        String safe = sanitize(fileName);
        if (safe == null) return "文件名无效";
        if (!isSupported(extensionOf(safe))) return "只支持 .docx/.txt/.md 文件";
        try {
            String text = textOf(content, safe);
            if (text.trim().length() == 0) return "没有从文件里读到文本";
        } catch (Exception error) {
            return describe(error);
        }
        if (entries.size() >= MAX_ENTRIES) return "自建库最多 " + MAX_ENTRIES + " 个文件";
        long total = 0L;
        for (int i = 0; i < entries.size(); i++) total += entries.get(i).bytes;
        if (total + content.length > MAX_TOTAL_BYTES) return "自建库容量已满（上限 96 MB）";
        if (!directory.isDirectory() && !directory.mkdirs()) return "无法创建自建库目录";
        File target = place(uniqueName(safe));
        if (target == null) return "文件名越出自建库目录";
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(target);
            out.write(content);
        } catch (IOException error) {
            return "写入失败：" + describe(error);
        } finally {
            close(out);
        }
        Entry entry = new Entry();
        entry.name = target.getName();
        entry.bytes = content.length;
        entry.addedAt = System.currentTimeMillis();
        entry.sentences = 0;
        entries.add(entry);
        persist();
        return null;
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

    /** .docx 走 DocxParser 抽正文，.txt/.md 按 UTF-8（容忍 BOM）。 */
    public static String textOf(byte[] content, String fileName) throws IOException {
        if (content == null || content.length == 0) return "";
        String extension = extensionOf(fileName == null ? "" : fileName);
        if ("docx".equals(extension)) return docxText(content, fileName);
        if ("txt".equals(extension) || "md".equals(extension)) {
            String text = new String(content, "UTF-8");
            if (text.length() > 0 && text.charAt(0) == 0xFEFF) text = text.substring(1);
            return text.length() <= MAX_TEXT_CHARS ? text : text.substring(0, MAX_TEXT_CHARS);
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

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        String extension = name.substring(dot + 1).toLowerCase(java.util.Locale.US);
        return extension.length() <= 8 ? extension : "";
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private static boolean isSupported(String extension) {
        return "docx".equals(extension) || "txt".equals(extension) || "md".equals(extension);
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
            if (INDEX_NAME.equalsIgnoreCase(file.getName())) continue;
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
                    .append('}');
        }
        out.append("]}");
        FileOutputStream stream = null;
        try {
            stream = new FileOutputStream(new File(directory, INDEX_NAME));
            stream.write(out.toString().getBytes("UTF-8"));
        } catch (IOException error) {
            // 写不进磁盘时保留内存状态，下一次操作再试；读取端有目录重建兜底。
        } finally {
            close(stream);
        }
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
