package com.rikkahub.wordlite;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DeflaterOutputStream;

/** Small classic PDF object writer: vector pages, embedded TrueType, no native PDF dependency. */
public final class PdfFile {
    private final ArrayList<byte[]> objects = new ArrayList<byte[]>();
    public PdfFile() { objects.add(null); }
    public int reserve() { objects.add(null); return objects.size() - 1; }
    public int add(String value) { int id = reserve(); set(id, value); return id; }
    public void set(int id, String value) { objects.set(id, value.getBytes(StandardCharsets.ISO_8859_1)); }
    public void stream(int id, String dictionary, byte[] data, boolean compress) throws IOException {
        byte[] bytes = data;
        if (compress) {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) { deflater.write(data); }
            bytes = compressed.toByteArray(); dictionary += " /Filter /FlateDecode";
        }
        ByteArrayOutputStream result = new ByteArrayOutputStream(bytes.length + 100);
        result.write(("<< " + dictionary + " /Length " + bytes.length + " >>\nstream\n").getBytes(StandardCharsets.ISO_8859_1));
        result.write(bytes); result.write("\nendstream".getBytes(StandardCharsets.ISO_8859_1));
        objects.set(id, result.toByteArray());
    }
    public byte[] finish(int root) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write("%PDF-1.7\n%\u00E2\u00E3\u00CF\u00D3\n".getBytes(StandardCharsets.ISO_8859_1));
        int[] offsets = new int[objects.size()];
        for (int i = 1; i < objects.size(); i++) {
            if (objects.get(i) == null) throw new IOException("PDF对象未完成");
            offsets[i] = result.size(); result.write((i + " 0 obj\n").getBytes(StandardCharsets.ISO_8859_1));
            result.write(objects.get(i)); result.write("\nendobj\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        int xref = result.size();
        result.write(("xref\n0 " + objects.size() + "\n0000000000 65535 f \n").getBytes(StandardCharsets.ISO_8859_1));
        for (int i = 1; i < objects.size(); i++) result.write(String.format(java.util.Locale.US,
                "%010d 00000 n \n", offsets[i]).getBytes(StandardCharsets.ISO_8859_1));
        result.write(("trailer\n<< /Size " + objects.size() + " /Root " + root + " 0 R >>\nstartxref\n"
                + xref + "\n%%EOF\n").getBytes(StandardCharsets.ISO_8859_1));
        return result.toByteArray();
    }
    public static String number(float value) {
        if ((Float.isNaN(value) || Float.isInfinite(value))) value = 0;
        return String.format(java.util.Locale.US, "%.4f", value);
    }

    // ---- 只读文字流抽取（ROADMAP 0.6.3）----
    //
    // 为什么在本类里读：导出侧 PdfCanvas + PdfTrueType 写的就是
    // "BT /F0 12 Tf ... <四位字形码> Tj ET" 再配一张 ToUnicode 对照表，
    // 反向读回正文必须对着同一套约定解析；为几页正文再引一个 PDF 库不划算，
    // 而且会把"自己导出的 PDF 自己读不回来"这类错误藏起来。

    /**
     * 抽取结果。textOps 与 imageBlocks 分开数：一个 PDF 一页字都没有（扫描版）
     * 和"有文字算子但字体编码映射不出 Unicode"是两回事，不能都当成空正文。
     */
    public static final class Extracted {
        public String text = "";
        public int pages;
        public int textOps;
        public int imageBlocks;
        public boolean undecodable;
        public boolean truncated;
    }

    /** 抽正文。不是 PDF / 已加密 / 结构坏了抛 IOException；"没有文字层"用 textOps == 0 表示。 */
    public static Extracted extractText(byte[] pdf) throws IOException {
        return extractText(pdf, 4 * 1024 * 1024);
    }

    public static Extracted extractText(byte[] pdf, int maxChars) throws IOException {
        if (pdf == null || pdf.length < 32) throw new IOException("PDF 内容为空");
        int probe = Math.min(pdf.length, 1024);
        if (new String(pdf, 0, probe, StandardCharsets.ISO_8859_1).indexOf("%PDF-") < 0) throw new IOException("不是 PDF 文件");
        Document document = new Document(pdf);
        if (document.objects.isEmpty()) throw new IOException("PDF 里没有可解析的对象");
        if (document.encrypted()) throw new IOException("PDF 已加密，读不出正文");
        ArrayList<Object> pages = document.pages();
        // 一页都找不到是结构读不出（缺 /Pages、对象流没解开），不能冒充"没文字层的扫描版"。
        if (pages.isEmpty()) throw new IOException("PDF 读不出页面结构");
        Extracted out = new Extracted();
        StringBuilder text = new StringBuilder();
        PageState state = new PageState(text, out, Math.max(1024, maxChars));
        for (Object ref : pages) {
            Dict page = document.dictOf(ref);
            if (page == null) continue;
            out.pages++;
            state.lineY = null;
            document.pageText(page, null, state, 0);
            if (state.reachedLimit) { out.truncated = true; break; }
            if (text.length() > 0 && text.charAt(text.length() - 1) != '\n') text.append('\n');
        }
        int limit = Math.max(16, maxChars);
        if (text.length() > limit) { text.setLength(limit); out.truncated = true; }
        out.text = text.toString();
        return out;
    }

    /** 一页（或一个 Form XObject）共享的抽取状态：行坐标、字体缓存、累加的正文。 */
    private static final class PageState {
        final StringBuilder text;
        final Extracted out;
        final Map<Object, Font> fonts = new HashMap<Object, Font>();
        final int limit;
        Float lineY;
        float penX;
        float penY;
        boolean reachedLimit;
        PageState(StringBuilder text, Extracted out, int limit) { this.text = text; this.out = out; this.limit = limit; }
    }

    /** 一个字体怎么把字节变成字符：优先 ToUnicode，其次 /Encoding 名字对应的现成字符集。 */
    private static final class Font {
        Map<Integer, String> toUnicode;
        Charset charset;
        boolean twoByte;
        boolean missing;
        String decode(byte[] raw) {
            if (raw == null || raw.length == 0) return "";
            if (toUnicode != null && !toUnicode.isEmpty()) {
                StringBuilder out = new StringBuilder();
                int step = twoByte ? 2 : 1;
                for (int i = 0; i + step <= raw.length; i += step) {
                    int code = 0;
                    for (int k = 0; k < step; k++) code = (code << 8) | (raw[i + k] & 255);
                    String mapped = toUnicode.get(Integer.valueOf(code));
                    if (mapped == null) { missing = true; continue; }
                    out.append(mapped);
                }
                return out.toString();
            }
            if (charset == null) { missing = true; return ""; }
            if (twoByte && raw.length % 2 != 0) return new String(raw, 0, raw.length - 1, charset);
            return new String(raw, charset);
        }
    }

    /** 对象表：线性扫 "N G obj"，不读 xref。导出侧写坏了 xref 也还能把正文捞出来。 */
    private static final class Document {
        private static final Pattern OBJECT = Pattern.compile("(?<![0-9])(\\d{1,10})[ \\t\\r\\n]+(\\d{1,5})[ \\t\\r\\n]+obj");
        private static final Pattern TRAILER = Pattern.compile("(?<![A-Za-z0-9])trailer");
        private static final int PAGE_LIMIT = 4000;
        private static final int STREAM_LIMIT = 32 * 1024 * 1024;

        final byte[] data;
        final Map<Integer, Object> objects = new HashMap<Integer, Object>();

        Document(byte[] data) { this.data = data; scan(); expandObjectStreams(); }

        Object resolve(Object value) {
            for (int guard = 0; guard < 8; guard++) {
                if (!(value instanceof Ref)) return value;
                value = objects.get(Integer.valueOf(((Ref) value).num));
                if (value == null) return null;
            }
            return null;
        }

        Dict dictOf(Object value) {
            Object resolved = resolve(value);
            if (resolved instanceof Dict) return (Dict) resolved;
            if (resolved instanceof StreamObj) return ((StreamObj) resolved).dict;
            return null;
        }

        static String nameOf(Object value) { return value instanceof Name ? ((Name) value).value : null; }

        static float numOf(Object value) {
            return value instanceof Number ? ((Number) value).floatValue() : Float.NaN;
        }

        /** 流解码。只认 FlateDecode（含裸 deflate 与 PNG 滤波两种常见变体），其余滤镜一律不猜。 */
        byte[] contentOf(StreamObj stream) {
            byte[] raw = stream.raw;
            if (raw == null) return null;
            Object filter = resolve(stream.dict.values.get("Filter"));
            List<String> names = new ArrayList<String>();
            if (filter instanceof Name) names.add(((Name) filter).value);
            else if (filter instanceof List) for (Object item : (List<?>) filter) if (item instanceof Name) names.add(((Name) item).value);
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                if (!name.equals("FlateDecode") && !name.equals("Fl")) return null;
                raw = inflate(raw);
                if (raw == null) return null;
                Object parmsValue = stream.dict.values.get("DecodeParms") == null ? stream.dict.values.get("DP") : stream.dict.values.get("DecodeParms");
                Dict parms = dictOf(parmsValue);
                if (parms != null) raw = predictor(raw, parms);
                if (raw == null) return null;
            }
            return raw;
        }

        /** /Predictor 12 在压缩对象流里很常见：不做逐行反滤波，整份 PDF 都读不出页面对象。 */
        private byte[] predictor(byte[] raw, Dict parms) {
            int predictor = (int) numOf(resolve(parms.values.get("Predictor")));
            if (predictor < 10) return raw;
            int columns = (int) numOf(resolve(parms.values.get("Columns")));
            int colors = (int) numOf(resolve(parms.values.get("Colors")));
            int bits = (int) numOf(resolve(parms.values.get("BitsPerComponent")));
            if (columns <= 0) columns = 1;
            if (colors <= 0) colors = 1;
            if (bits <= 0) bits = 8;
            if (bits != 8) return raw;
            int rowLength = colors * columns;
            int rows = raw.length / (rowLength + 1);
            if (rows <= 0 || rows * (rowLength + 1) != raw.length) return raw;
            byte[] out = new byte[rows * rowLength];
            byte[] previous = new byte[rowLength];
            for (int row = 0; row < rows; row++) {
                int at = row * (rowLength + 1);
                int type = raw[at] & 255;
                int target = row * rowLength;
                for (int i = 0; i < rowLength; i++) {
                    int value = raw[at + 1 + i] & 255;
                    int left = i >= rowLength / columns ? out[target + i - rowLength / columns] & 255 : 0;
                    int up = previous[i] & 255;
                    int upperLeft = i >= rowLength / columns ? previous[i - rowLength / columns] & 255 : 0;
                    int result;
                    switch (type) {
                        case 1: result = value + left; break;
                        case 2: result = value + up; break;
                        case 3: result = value + ((left + up) >>> 1); break;
                        case 4: {
                            int p = left + up - upperLeft;
                            int pa = Math.abs(p - left), pb = Math.abs(p - up), pc = Math.abs(p - upperLeft);
                            result = value + (pa <= pb && pa <= pc ? left : pb <= pc ? up : upperLeft);
                            break;
                        }
                        default: result = value; break;
                    }
                    out[target + i] = (byte) result;
                }
                System.arraycopy(out, target, previous, 0, rowLength);
            }
            return out;
        }

        private static byte[] inflate(byte[] raw) {
            byte[] plain = inflateWith(raw, false);
            if (plain == null || plain.length == 0) {
                byte[] bare = inflateWith(raw, true);   // 少数导出器漏了 zlib 头
                if (bare != null && bare.length > 0) return bare;
            }
            return plain;
        }

        private static byte[] inflateWith(byte[] raw, boolean nowrap) {
            java.util.zip.Inflater inflater = new java.util.zip.Inflater(nowrap);
            inflater.setInput(raw);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(4096, raw.length * 3));
            byte[] buffer = new byte[8192];
            try {
                while (true) {
                    int count = inflater.inflate(buffer);
                    if (count <= 0) break;
                    out.write(buffer, 0, count);
                    if (out.size() > STREAM_LIMIT) return null;
                }
            } catch (Exception error) {
                // 尾字节坏了的流也照样能用：能解出来多少算多少，缺的那部分按"读不出"处理。
            } finally {
                inflater.end();
            }
            return out.toByteArray();
        }

        private void scan() {
            String latin = new String(data, StandardCharsets.ISO_8859_1);
            Matcher matcher = OBJECT.matcher(latin);
            while (matcher.find()) {
                int num;
                try { num = Integer.parseInt(matcher.group(1)); } catch (Exception error) { continue; }
                if (num <= 0) continue;
                Object value;
                try { value = Reader.read(new Reader(data, matcher.end())); } catch (Exception error) { continue; }
                if (value == null || value == Reader.END) continue;
                Object kept = objects.get(Integer.valueOf(num));
                // 图片之类的二进制里可能刚好出现 "17 0 obj"，字典/流优先，别让巧合串顶掉真对象。
                if (kept != null && !(!structural(kept) && structural(value))) continue;
                objects.put(Integer.valueOf(num), value);
            }
        }

        private static boolean structural(Object value) { return value instanceof Dict || value instanceof StreamObj; }

        /** PDF 1.5 把对象塞进 /Type /ObjStm 的压缩流里；不展开就没有 /Pages，也就没有页面。 */
        private void expandObjectStreams() {
            for (Object value : new ArrayList<Object>(objects.values())) {
                if (!(value instanceof StreamObj)) continue;
                StreamObj stream = (StreamObj) value;
                if (!"ObjStm".equals(nameOf(stream.dict.values.get("Type")))) continue;
                byte[] bytes = contentOf(stream);
                int count = (int) numOf(resolve(stream.dict.values.get("N")));
                int first = (int) numOf(resolve(stream.dict.values.get("First")));
                if (bytes == null || count <= 0 || count > 200000 || first < 0 || first >= bytes.length) continue;
                Reader index = new Reader(bytes, 0);
                for (int i = 0; i < count; i++) {
                    Object number = Reader.read(index);
                    Object offset = Reader.read(index);
                    if (!(number instanceof Number) || !(offset instanceof Number)) break;
                    int at = first + ((Number) offset).intValue();
                    if (at < 0 || at >= bytes.length) continue;
                    try {
                        Object inner = Reader.read(new Reader(bytes, at));
                        Integer key = Integer.valueOf(((Number) number).intValue());
                        if (inner != null && inner != Reader.END && !objects.containsKey(key)) objects.put(key, inner);
                    } catch (Exception ignored) {
                        break;
                    }
                }
            }
        }

        /** 加密判断只查 trailer 与 XRef 流字典：全文搜 "/Encrypt" 会被二进制流里的巧合串骗到。 */
        boolean encrypted() {
            Matcher matcher = TRAILER.matcher(new String(data, StandardCharsets.ISO_8859_1));
            while (matcher.find()) {
                Dict trailer = dictOf(readQuietly(new Reader(data, matcher.end())));
                if (trailer != null && trailer.values.containsKey("Encrypt")) return true;
            }
            for (Object value : objects.values()) {
                Dict dict = dictOf(value);
                if (dict != null && "XRef".equals(nameOf(dict.values.get("Type"))) && dict.values.containsKey("Encrypt")) return true;
            }
            return false;
        }

        /** 目录树顺序的页面。没有 /Root 就退一步按对象号顺序找 /Type /Page（顺序不保证，但比读不出好）。 */
        ArrayList<Object> pages() {
            ArrayList<Object> out = new ArrayList<Object>();
            Dict catalog = dictOf(catalogRoot());
            Object root = catalog == null ? null : catalog.values.get("Pages");   // 目录树从 /Pages 往下走，/Catalog 自己不是页面
            if (root != null) walk(root, out, new HashSet<Integer>(), 0, null);
            if (out.isEmpty()) {
                ArrayList<Integer> keys = new ArrayList<Integer>(objects.keySet());
                java.util.Collections.sort(keys);
                for (Integer key : keys) {
                    Dict dict = dictOf(objects.get(key));
                    if (dict != null && "Page".equals(nameOf(dict.values.get("Type")))) out.add(objects.get(key));
                    if (out.size() >= PAGE_LIMIT) break;
                }
            }
            return out;
        }

        private Object catalogRoot() {
            Matcher matcher = TRAILER.matcher(new String(data, StandardCharsets.ISO_8859_1));
            while (matcher.find()) {
                Dict trailer = dictOf(readQuietly(new Reader(data, matcher.end())));
                Object root = trailer == null ? null : trailer.values.get("Root");
                if (dictOf(root) != null) return root;
            }
            ArrayList<Integer> keys = new ArrayList<Integer>(objects.keySet());
            java.util.Collections.sort(keys);
            for (Integer key : keys) {
                Dict dict = dictOf(objects.get(key));
                if (dict == null) continue;
                if ("Catalog".equals(nameOf(dict.values.get("Type")))) return objects.get(key);
                if ("XRef".equals(nameOf(dict.values.get("Type"))) && dict.values.containsKey("Root")) return dict.values.get("Root");
            }
            return null;
        }

        private void walk(Object node, ArrayList<Object> out, Set<Integer> visited, int depth, Dict inherited) {
            if (node == null || depth > 24 || out.size() >= PAGE_LIMIT) return;
            if (node instanceof Ref && !visited.add(Integer.valueOf(((Ref) node).num))) return;
            Dict dict = dictOf(node);
            if (dict == null) return;
            Dict merged = merge(dictOf(dict.values.get("Resources")), inherited);
            String type = nameOf(dict.values.get("Type"));
            if ("Page".equals(type) || (!dict.values.containsKey("Kids") && !"Pages".equals(type))) {
                out.add(node);
                return;
            }
            Object kids = resolve(dict.values.get("Kids"));
            if (!(kids instanceof List)) return;
            for (Object kid : (List<?>) kids) walk(kid, out, visited, depth + 1, merged);
        }

        /** 解析一页的文字流。inherited 是父节点继承下来的 /Resources，页面自己的优先。 */
        void pageText(Dict page, Dict inherited, PageState state, int depth) throws IOException {
            Dict resources = merge(dictOf(page.values.get("Resources")), inherited);
            Object contents = resolve(page.values.get("Contents"));
            if (contents instanceof StreamObj) {
                runStream((StreamObj) contents, resources, state, depth);
                return;
            }
            if (contents instanceof List) {
                for (Object item : (List<?>) contents) {
                    Object stream = resolve(item);
                    if (stream instanceof StreamObj) runStream((StreamObj) stream, resources, state, depth);
                    if (state.reachedLimit) return;
                }
            }
        }

        private void runStream(StreamObj stream, Dict resources, PageState state, int depth) {
            byte[] bytes = contentOf(stream);
            if (bytes == null || bytes.length == 0) return;
            try {
                interpret(bytes, resources, state, depth);
            } catch (Exception ignored) {
                // 内容流坏了就停在已读到的正文上：一页坏掉不该让整份 PDF 报错。
            }
        }

        /** 文字算子解释器。只跑文字排版相关的算子，绘图算子直接忽略。 */
        private void interpret(byte[] content, Dict resources, PageState state, int depth) {
            Reader reader = new Reader(content, 0);
            ArrayList<Object> operands = new ArrayList<Object>();
            Font font = null;
            float leading = 0f;
            while (!state.reachedLimit) {
                Object value = Reader.read(reader);
                if (value == Reader.END) break;
                if (!(value instanceof String)) {
                    operands.add(value);
                    if (operands.size() > 64) operands.subList(0, operands.size() - 32).clear();
                    continue;
                }
                String operator = (String) value;
                int size = operands.size();
                if (operator.equals("Tj") || operator.equals("'") || operator.equals("\"")) {
                    if (operator.length() > 1) { state.penY -= leading; state.lineY = null; }
                    show(size == 0 ? null : operands.get(size - 1), font, state);
                } else if (operator.equals("TJ")) {
                    Object array = resolve(size == 0 ? null : operands.get(size - 1));
                    if (array instanceof List) for (Object item : (List<?>) array) show(resolve(item), font, state);
                } else if (operator.equals("Tf") && size >= 2) {
                    String key = nameOf(operands.get(size - 2));
                    if (key != null) {
                        font = state.fonts.get(key);
                        if (font == null) {
                            font = fontFor(key, resources);
                            state.fonts.put(key, font);
                        }
                    }
                } else if (operator.equals("Td") || operator.equals("TD")) {
                    if (size >= 2) {
                        state.penX += numOf(operands.get(size - 2));
                        float dy = numOf(operands.get(size - 1));
                        state.penY += dy;
                        if (operator.equals("TD")) leading = -dy;
                    }
                } else if (operator.equals("Tm") && size >= 6) {
                    state.penX = numOf(operands.get(size - 2));
                    state.penY = numOf(operands.get(size - 1));
                } else if (operator.equals("TL") && size >= 1) {
                    leading = numOf(operands.get(size - 1));
                } else if (operator.equals("T*")) {
                    state.penY -= leading;
                    state.lineY = null;
                } else if (operator.equals("BT")) {
                    state.lineY = null;
                } else if (operator.equals("Do")) {
                    drawnObject(nameOf(size == 0 ? null : operands.get(size - 1)), resources, state, depth);
                } else if (operator.equals("BI")) {
                    // 内联图片：中间是裸二进制，直接跳到 EI 再继续，免得把 JPEG 字节当算子解析。
                    state.out.imageBlocks++;
                    int end = indexOf(content, "EI", reader.at);
                    if (end < 0) break;
                    reader.at = end + 2;
                }
                operands.clear();
            }
        }

        /** Do 的名字既可能是扫描页的图片，也可能是装着正文的 Form XObject。 */
        private void drawnObject(String key, Dict resources, PageState state, int depth) {
            if (key == null || resources == null) return;
            Dict xobjects = dictOf(resources.values.get("XObject"));
            if (xobjects == null) return;
            Object target = resolve(xobjects.values.get(key));
            Dict form = dictOf(target);
            if (form == null) return;
            String subtype = nameOf(form.values.get("Subtype"));
            if ("Image".equals(subtype)) { state.out.imageBlocks++; return; }
            if (!"Form".equals(subtype) || !(target instanceof StreamObj) || depth >= 4) return;
            runStream((StreamObj) target, merge(dictOf(form.values.get("Resources")), resources), state, depth + 1);
        }

        /**
         * 换行由笔的 y 坐标决定，而不是由 BT/ET 决定：导出侧每画一个字形就发一条
         * BT/ET，按 BT 分行会把整段正文剁成单字，句子再也拼不回来。
         */
        private static void show(Object value, Font font, PageState state) {
            byte[] raw = value instanceof Bin ? ((Bin) value).bytes : null;
            if (raw == null || raw.length == 0) return;
            state.out.textOps++;   // 数的是"真的画了字"的算子，扫描版恒为 0
            if (font == null) { state.out.undecodable = true; return; }
            String decoded = font.decode(raw);
            if (decoded.length() == 0) { state.out.undecodable |= font.missing; return; }
            if (state.lineY != null && Math.abs(state.penY - state.lineY.floatValue()) > 0.75f) state.text.append('\n');
            state.lineY = Float.valueOf(state.penY);
            state.text.append(decoded);
            if (state.text.length() >= state.limit) state.reachedLimit = true;
        }

        private Font fontFor(String key, Dict resources) {
            Font font = new Font();
            Dict fonts = resources == null ? null : dictOf(resources.values.get("Font"));
            Dict descriptor = fonts == null ? null : dictOf(fonts.values.get(key));
            if (descriptor == null) { font.missing = true; return font; }
            font.twoByte = "Type0".equals(nameOf(descriptor.values.get("Subtype")));
            Object toUnicode = resolve(descriptor.values.get("ToUnicode"));
            if (toUnicode instanceof StreamObj) {
                byte[] bytes = contentOf((StreamObj) toUnicode);
                if (bytes != null) font.toUnicode = ToUnicode.parse(new String(bytes, StandardCharsets.ISO_8859_1));
            }
            String encoding = nameOf(descriptor.values.get("Encoding"));
            if (encoding != null) {
                String upper = encoding.toUpperCase(java.util.Locale.US);
                if (upper.indexOf("UCS2") >= 0 || upper.indexOf("UTF16") >= 0) font.charset = Charset.forName("UTF-16BE");
                else if (upper.indexOf("GB2312") >= 0 || upper.indexOf("GBK") >= 0 || upper.indexOf("UNIGB") >= 0) font.charset = charset("GBK");
                else if (upper.indexOf("BIG5") >= 0) font.charset = charset("Big5");
                else if (upper.indexOf("JOHAB") >= 0 || upper.indexOf("KOREA") >= 0) font.charset = charset("MS949");
                else if (upper.indexOf("WINANSI") >= 0) font.charset = charset("windows-1252");
                else if (upper.indexOf("MACROMAN") >= 0) font.charset = charset("MacRoman");
                else if (!font.twoByte) font.charset = Charset.forName("ISO-8859-1");
            } else if (!font.twoByte) font.charset = Charset.forName("ISO-8859-1");
            return font;
        }

        private static Charset charset(String name) {
            try {
                return Charset.forName(name);
            } catch (Exception error) {
                return null;   // 个别 ROM 没带 GBK/Big5：宁可报"读不出编码"，也不给一串乱码
            }
        }

        /** 页面资源继承：子节点有的键盖住父节点的，缺的往下捡。 */
        private static Dict merge(Dict own, Dict inherited) {
            if (own == null) return inherited;
            if (inherited == null) return own;
            Dict out = new Dict();
            out.values.putAll(inherited.values);
            out.values.putAll(own.values);
            return out;
        }

        private Object readQuietly(Reader reader) {
            try {
                return Reader.read(reader);
            } catch (Exception error) {
                return null;
            }
        }

        static int indexOf(byte[] data, String token, int from) {
            byte[] needle = token.getBytes(StandardCharsets.ISO_8859_1);
            outer:
            for (int i = Math.max(0, from); i + needle.length <= data.length; i++) {
                for (int k = 0; k < needle.length; k++) if (data[i + k] != needle[k]) continue outer;
                return i;
            }
            return -1;
        }
    }

    private static final class Dict {
        final LinkedHashMap<String, Object> values = new LinkedHashMap<String, Object>();
    }

    private static final class Name {
        final String value;
        Name(String value) { this.value = value; }
        public String toString() { return value; }
    }

    /** 字符串字面量与十六进制串都只留字节，怎么变字符由字体说了算。 */
    private static final class Bin {
        final byte[] bytes;
        Bin(byte[] bytes) { this.bytes = bytes; }
    }

    private static final class Ref {
        final int num;
        final int gen;
        Ref(int num, int gen) { this.num = num; this.gen = gen; }
    }

    private static final class StreamObj {
        final Dict dict;
        final byte[] raw;
        StreamObj(Dict dict, byte[] raw) { this.dict = dict; this.raw = raw; }
    }

    /** ToUnicode CMap 只认 bfchar / bfrange 两种映射：导出侧写的就是 bfchar，生产 PDF 也基本不出这个范围。 */
    private static final class ToUnicode {
        private static final Pattern CHAR = Pattern.compile("beginbfchar\\s(.*?)endbfchar", Pattern.DOTALL);
        private static final Pattern RANGE = Pattern.compile("beginbfrange\\s(.*?)endbfrange", Pattern.DOTALL);
        private static final Pattern PAIR = Pattern.compile("<([0-9A-Fa-f\\s]+)>\\s*<([0-9A-Fa-f\\s]+)>");
        private static final Pattern TRIPLE = Pattern.compile("<([0-9A-Fa-f\\s]+)>\\s*<([0-9A-Fa-f\\s]+)>\\s*(<([0-9A-Fa-f\\s]+)>|\\[[^\\]]*\\])");
        private static final Pattern CELL = Pattern.compile("<([0-9A-Fa-f\\s]+)>");

        static Map<Integer, String> parse(String text) {
            Map<Integer, String> out = new HashMap<Integer, String>();
            if (text == null || text.length() == 0) return out;
            Matcher blocks = CHAR.matcher(text);
            while (blocks.find()) {
                Matcher pair = PAIR.matcher(blocks.group(1));
                while (pair.find()) {
                    Integer key = code(pair.group(1));
                    String value = unicode(pair.group(2));
                    if (key != null && value.length() > 0) out.put(key, value);
                }
            }
            blocks = RANGE.matcher(text);
            while (blocks.find()) {
                Matcher triple = TRIPLE.matcher(blocks.group(1));
                while (triple.find()) {
                    Integer low = code(triple.group(1));
                    Integer high = code(triple.group(2));
                    if (low == null || high == null || high < low || high - low > 65535) continue;
                    String tail = triple.group(3);
                    if (tail.startsWith("[")) {
                        Matcher cell = CELL.matcher(tail);
                        int offset = 0;
                        while (cell.find() && low + offset <= high) {
                            String value = unicode(cell.group(1));
                            if (value.length() > 0) out.put(Integer.valueOf(low + offset), value);
                            offset++;
                        }
                        continue;
                    }
                    String first = unicode(triple.group(4));
                    int start = first.length() > 0 ? first.charAt(0) : 0;
                    for (int i = 0; low + i <= high; i++) out.put(Integer.valueOf(low + i), String.valueOf((char) (start + i)));
                }
            }
            return out;
        }

        private static Integer code(String hexDigits) {
            byte[] bytes = hex(hexDigits);
            if (bytes == null || bytes.length == 0 || bytes.length > 4) return null;
            int value = 0;
            for (int i = 0; i < bytes.length; i++) value = (value << 8) | (bytes[i] & 255);
            return Integer.valueOf(value);
        }

        private static String unicode(String hexDigits) {
            byte[] bytes = hex(hexDigits);
            return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_16BE);
        }

        private static byte[] hex(String value) {
            if (value == null) return null;
            StringBuilder digits = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) digits.append(c);
            }
            if (digits.length() == 0) return new byte[0];
            if (digits.length() % 2 != 0) digits.append('0');   // 规范：末尾半个字节按 0 补
            byte[] out = new byte[digits.length() / 2];
            for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(digits.substring(i * 2, i * 2 + 2), 16);
            return out;
        }
    }

    /** 极简 PDF 词法与语法：字典、流、数组、名字、字符串、引用够了，其余结构当垃圾跳过。 */
    private static final class Reader {
        static final Object END = new Object();
        private static final String DELIMITERS = "()<>[]{}/%";

        final byte[] data;
        int at;

        Reader(byte[] data, int at) {
            this.data = data;
            this.at = Math.max(0, Math.min(at, data.length));
        }

        void skipSpace() {
            while (at < data.length) {
                int c = data[at] & 255;
                if (c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32) { at++; continue; }
                if (c == '%') { while (at < data.length && (data[at] & 255) != 10) at++; continue; }
                return;
            }
        }

        static Object read(Reader reader) {
            reader.skipSpace();
            if (reader.at >= reader.data.length) return END;
            int c = reader.data[reader.at] & 255;
            if (c == '<') {
                if (reader.at + 1 < reader.data.length && (reader.data[reader.at + 1] & 255) == '<') {
                    reader.at += 2;
                    Dict dict = readDict(reader);
                    int keep = reader.at;
                    reader.skipSpace();
                    if (startsWith(reader.data, reader.at, "stream")) return readStream(reader, dict);
                    reader.at = keep;
                    return dict;
                }
                return new Bin(readHex(reader));
            }
            if (c == '(') return new Bin(readLiteral(reader));
            if (c == '/') { reader.at++; return new Name(readName(reader)); }
            if (c == '[') {
                reader.at++;
                ArrayList<Object> list = new ArrayList<Object>();
                while (true) {
                    reader.skipSpace();
                    if (reader.at >= reader.data.length) return list;
                    if ((reader.data[reader.at] & 255) == ']') { reader.at++; return list; }
                    Object item = read(reader);
                    if (item == Reader.END) return list;
                    list.add(item);
                }
            }
            if (c == ']' || c == '>' || c == '{' || c == '}') { reader.at++; return null; }
            return readWord(reader);
        }

        static Dict readDict(Reader reader) {
            Dict dict = new Dict();
            while (true) {
                reader.skipSpace();
                if (reader.at >= reader.data.length) return dict;
                if ((reader.data[reader.at] & 255) == '>' && reader.at + 1 < reader.data.length
                        && (reader.data[reader.at + 1] & 255) == '>') { reader.at += 2; return dict; }
                if ((reader.data[reader.at] & 255) != '/') {
                    int close = Document.indexOf(reader.data, ">>", reader.at);
                    reader.at = close < 0 ? reader.data.length : close + 2;
                    return dict;
                }
                reader.at++;
                String key = readName(reader);
                Object value = read(reader);
                if (value == Reader.END) return dict;
                if (key.length() > 0) dict.values.put(key, value);
            }
        }

        /** /Length 可信就用，不可信就找 endstream；间接引用与写错的长度一律走搜索。 */
        private static StreamObj readStream(Reader reader, Dict dict) {
            reader.at += 6;
            if (reader.at < reader.data.length && (reader.data[reader.at] & 255) == 13) reader.at++;
            if (reader.at < reader.data.length && (reader.data[reader.at] & 255) == 10) reader.at++;
            int start = reader.at;
            int end = reader.data.length;
            Object length = dict.values.get("Length");
            if (length instanceof Number) {
                long declared = start + ((Number) length).longValue();
                if (declared >= start && declared <= reader.data.length) end = (int) declared;
            }
            int marker = Document.indexOf(reader.data, "endstream", start);
            if (marker >= 0 && marker < end) end = marker;
            while (end > start && (reader.data[end - 1] & 255) <= 32) end--;   // 尾部换行不算正文
            byte[] raw = new byte[Math.max(0, end - start)];
            System.arraycopy(reader.data, start, raw, 0, raw.length);
            reader.at = marker < 0 ? reader.data.length : Math.min(reader.data.length, marker + 9);
            return new StreamObj(dict, raw);
        }

        private static byte[] readHex(Reader reader) {
            int start = ++reader.at;
            int end = start;
            while (end < reader.data.length && (reader.data[end] & 255) != '>') end++;
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(8, (end - start) / 2));
            int value = -1;
            for (int i = start; i < end; i++) {
                int c = reader.data[i] & 255;
                int digit;
                if (c >= '0' && c <= '9') digit = c - '0';
                else if (c >= 'a' && c <= 'f') digit = c - 'a' + 10;
                else if (c >= 'A' && c <= 'F') digit = c - 'A' + 10;
                else continue;
                if (value < 0) value = digit;
                else { out.write((value << 4) | digit); value = -1; }
            }
            if (value >= 0) out.write(value << 4);   // 规范：末尾半个字节按 0 补
            reader.at = Math.min(reader.data.length, end + 1);
            return out.toByteArray();
        }

        private static byte[] readLiteral(Reader reader) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            reader.at++;
            int depth = 1;
            while (reader.at < reader.data.length && depth > 0) {
                int c = reader.data[reader.at++] & 255;
                if (c == '\\') {
                    if (reader.at >= reader.data.length) break;
                    int escape = reader.data[reader.at++] & 255;
                    if (escape == 'n') out.write('\n');
                    else if (escape == 'r') out.write('\r');
                    else if (escape == 't') out.write('\t');
                    else if (escape == 'b') out.write('\b');
                    else if (escape == 'f') out.write('\f');
                    else if (escape == '\n') { /* 续行 */ }
                    else if (escape == '\r') { /* 续行 */ }
                    else if (escape >= '0' && escape <= '7') {
                        int value = escape - '0';
                        for (int i = 1; i < 3 && reader.at < reader.data.length; i++) {
                            int d = reader.data[reader.at] & 255;
                            if (d < '0' || d > '7') break;
                            value = value * 8 + (d - '0');
                            reader.at++;
                        }
                        out.write(value & 255);
                    } else out.write(escape);
                    continue;
                }
                if (c == '(') depth++;
                else if (c == ')') { depth--; if (depth == 0) break; }
                out.write(c);
            }
            return out.toByteArray();
        }

        private static String readName(Reader reader) {
            int start = reader.at;
            while (reader.at < reader.data.length) {
                int c = reader.data[reader.at] & 255;
                if (c <= 32 || DELIMITERS.indexOf((char) c) >= 0) break;
                reader.at++;
            }
            String raw = new String(reader.data, start, reader.at - start, StandardCharsets.ISO_8859_1);
            if (raw.indexOf('#') < 0) return raw;
            StringBuilder out = new StringBuilder(raw.length());
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c == '#' && i + 2 < raw.length()) {
                    try {
                        out.append((char) Integer.parseInt(raw.substring(i + 1, i + 3), 16));
                        i += 2;
                        continue;
                    } catch (Exception ignored) {
                        // 不是合法的 #XX 转义，原样留着
                    }
                }
                out.append(c);
            }
            return out.toString();
        }

        /** 数字、引用 "N G R"、关键字三种都长得像 token，先取词再回退定位。 */
        private static Object readWord(Reader reader) {
            int start = reader.at;
            while (reader.at < reader.data.length) {
                int c = reader.data[reader.at] & 255;
                if (c <= 32 || DELIMITERS.indexOf((char) c) >= 0) break;
                reader.at++;
            }
            if (reader.at == start) { reader.at++; return ""; }
            String token = new String(reader.data, start, reader.at - start, StandardCharsets.ISO_8859_1);
            if (token.equals("true")) return Boolean.TRUE;
            if (token.equals("false")) return Boolean.FALSE;
            if (token.equals("null")) return null;
            Double value = number(token);
            if (value == null) return token;
            int after = reader.at;
            String generation = word(reader);
            String marker = generation == null ? null : word(reader);
            int consumed = reader.at;
            reader.at = after;
            if (generation != null && "R".equals(marker)) {
                Double gen = number(generation);
                if (gen != null) {
                    reader.at = consumed;
                    return new Ref(value.intValue(), gen.intValue());
                }
            }
            double whole = value.doubleValue();
            if (whole == Math.floor(whole) && !Double.isInfinite(whole) && Math.abs(whole) < 9.0E15) return Long.valueOf((long) whole);
            return value;
        }

        private static String word(Reader reader) {
            reader.skipSpace();
            int start = reader.at;
            while (reader.at < reader.data.length) {
                int c = reader.data[reader.at] & 255;
                if (c <= 32 || DELIMITERS.indexOf((char) c) >= 0) break;
                reader.at++;
            }
            if (reader.at == start) return null;
            return new String(reader.data, start, reader.at - start, StandardCharsets.ISO_8859_1);
        }

        private static Double number(String token) {
            if (token == null || token.length() == 0) return null;
            try {
                double value = Double.parseDouble(token);
                return Double.isNaN(value) || Double.isInfinite(value) ? null : Double.valueOf(value);
            } catch (Exception error) {
                return null;
            }
        }

        private static boolean startsWith(byte[] data, int at, String token) {
            byte[] needle = token.getBytes(StandardCharsets.ISO_8859_1);
            if (at < 0 || at + needle.length > data.length) return false;
            for (int i = 0; i < needle.length; i++) if (data[at + i] != needle[i]) return false;
            return true;
        }
    }
}
