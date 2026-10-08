package com.rikkahub.wordlite;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 题录批量入库：把用户从知网/万方/维普的"导出/参考文献"里自己导出的题录文件读成一篇一条，
 * 再走 CorpusImport 那条已经通了的路进自建库。
 *
 * 先做这一条的理由：正文要机构账号（本机没有账号，这是授权问题不是解析问题），而题录是用户
 * 登录后点一下就能拿到的东西——一份几百条的导出文本里有题名、作者、刊名年期、关键词、摘要，
 * 摘要级的比对材料当场就有了，不碰任何反爬，也不等任何接口开恩。
 *
 * 认五种写法（前四种对应知网导出页上的四个按钮，第五种是复制成文本粘进记事本的那种）：
 *   NoteExpress  %0/%T/%A/%J/%D/%N/%P/%K/%X/%R/%U 标签，类型写成 JournalArticle（连写）
 *   RefWorks     同一族 % 标签，类型写成 Journal Article（带空格），刊名在 %B
 *   EndNote/RIS  TY  - / TI  - / AU  - / AB  - 起头，ER  - 收尾
 *   BibTeX       @Article{键, title={...}, author={A and B}, journal={...}, abstract={...}}
 *   题录文本     GB/T 7714 一行式（题名[J].刊名,年,卷(期):页码.）加【摘要】【关键词】那几行
 *
 * 三条规矩与 CorpusImport 同源，一条一个断言：
 *   一个坏记录不许拖垮整个文件——认不出题名的那条只丢自己，条数记进 blankTitle；
 *   认不出格式就当没解析出来——返回 0 条加一句说明，绝不把整份文件当一篇文章塞进库；
 *   摘要级材料必须标出来——入库时打题录级标记，来源榜才降得动权（见 DuplicateEngine.MATERIAL_*）。
 */
public final class RecordImport {
    private RecordImport() { }

    public static final String FORMAT_NOTEEXPRESS = "NoteExpress";
    public static final String FORMAT_REFWORKS = "RefWorks";
    public static final String FORMAT_RIS = "RIS";
    public static final String FORMAT_BIBTEX = "BibTeX";
    public static final String FORMAT_PLAINTEXT = "题录文本";

    /** 一片正文的上限：题录里的摘要实测 200~900 字，留出十倍余量，防的是手滑把整本书导进来。 */
    static final int MAX_RECORD_CHARS = 20000;

    /** 一份题录里的一篇。什么都可以空，题名不能空（空题名整条丢掉并计数）。 */
    public static final class Record {
        public String format = "";
        /** 库里给的名号：知网的文献号、万方的论文 ID、维普的维普号。 */
        public String id = "";
        public String title = "";
        public final ArrayList<String> authors = new ArrayList<String>();
        /** 刊名 / 学位授予单位 / 论文集名，题录里只有一个位置放它。 */
        public String venue = "";
        public String year = "", volume = "", issue = "", pages = "", doi = "", locator = "";
        public final ArrayList<String> keywords = new ArrayList<String>();
        public String abstractText = "";
        /** 数据库名（CNKI / WANFANG / VIP），%9、DB 字段或文件里那句"来自数据库"。 */
        public String database = "";
        /** 这条是从哪种写法里读出来的第几块，出问题时回执里要说得清是哪一条炸的。 */
        public int ordinal;

        public boolean hasAbstract() {
            return abstractText.trim().length() > 0;
        }

        /** 作者串：题录里作者本来就是分行的，拼成一行用分号隔开，与联网候选那一路同形。 */
        public String authorLine() {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < authors.size(); i++) {
                String name = authors.get(i).trim();
                if (name.length() == 0) continue;
                if (out.length() > 0) out.append("; ");
                out.append(name);
            }
            return out.toString();
        }

        public String keywordLine() {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < keywords.size(); i++) {
                String word = keywords.get(i).trim();
                if (word.length() == 0) continue;
                if (out.length() > 0) out.append("; ");
                out.append(word);
            }
            return out.toString();
        }

        /** 年 卷(期)：只出题录里真有的那几段，缺期不许编一个括号出来。 */
        public String dateLine() {
            StringBuilder out = new StringBuilder();
            if (year.length() > 0) out.append(year);
            if (volume.length() > 0) {
                if (out.length() > 0) out.append(',');
                out.append(volume);
            }
            if (issue.length() > 0) out.append('(').append(issue).append(')');
            return out.toString();
        }

        /**
         * 入库用的可比文本。形状照 CnkiSearch.abstractOf：摘要在前，题录跟在后面一行，
         * 题名/作者/刊名/年期/关键词一起进可比材料——照抄关键词的稿子要靠这几行撞得上。
         * 这里头一个字的正文都没有，所以它只能支撑摘要级的比对，material() 说的就是这件事。
         */
        public String body() {
            StringBuilder out = new StringBuilder(abstractText.trim());
            StringBuilder tail = new StringBuilder();
            append(tail, title);
            append(tail, authorLine());
            append(tail, venue);
            append(tail, dateLine());
            append(tail, keywordLine());
            if (out.length() > 0 && tail.length() > 0) out.append('\n');
            out.append(tail);
            if (out.length() <= MAX_RECORD_CHARS) return out.toString();
            int cut = MAX_RECORD_CHARS;
            // 截断不许把一对代理劈开：劈开得到半个字符，那一个字在比对里永远对不上。
            if (Character.isHighSurrogate(out.charAt(cut - 1))) cut--;
            return out.substring(0, cut);
        }

        /** 这一篇可比到什么档：有摘要是摘要级，连摘要都没有就只剩题名那一行。 */
        public String material() {
            return hasAbstract() ? DuplicateEngine.MATERIAL_ABSTRACT : DuplicateEngine.MATERIAL_RECORD;
        }

        /** 入库文件名：题名当名字，走 LocalLibrary 那套净化，空题名退到序号。 */
        public String fileName() {
            String title = this.title.trim();
            String name = title.length() == 0 ? ("未命名题录-" + ordinal) : title;
            String safe = LocalLibrary.sanitize(name + ".txt");
            return safe == null ? ("未命名题录-" + ordinal + ".txt") : safe;
        }

        private static void append(StringBuilder out, String value) {
            if (value == null || value.trim().isEmpty()) return;
            if (out.length() > 0) out.append(' ');
            out.append(value.trim());
        }
    }
    /** 一份文件的解析结果：认成了什么写法、按什么编码读的、解析出几篇、丢了几条、为什么。 */
    public static final class Parsed {
        public String format = "";
        public String charset = "";
        public int bytes;
        public final ArrayList<Record> records = new ArrayList<Record>();
        /** 认出一条记录但没有题名：整条丢掉，条数记在这里。 */
        public int blankTitle;
        /** 这份文件里出现过的、我们不认的标签种类数（%Y、%2 这类），只用来把话说全。 */
        public int unknownTags;
        /** 没并进任何记录的行数（纯文本那种写法里最常见）。 */
        public int strayLines;
        /** 这份文件里非空的行数：纯文本那种写法靠它判"这份文件整体上就是一份题录"。 */
        public int lines;
        public final ArrayList<String> notes = new ArrayList<String>();
        private final java.util.HashSet<String> kinds = new java.util.HashSet<String>();

        /** 没认的标签/字段种类（同一种出现一百回也只数一种），summary() 里那句"未认的标签"读它。 */
        boolean markUnknownKind(String kind) {
            return kinds.add(kind);
        }

        public int withAbstract() {
            int total = 0;
            for (int i = 0; i < records.size(); i++) if (records.get(i).hasAbstract()) total++;
            return total;
        }

        public int withoutAbstract() {
            return records.size() - withAbstract();
        }

        /** 可比字数总和：入库回执之外，界面上那句"这次能给比对多出多少材料"读它。 */
        public int bodyChars() {
            int total = 0;
            for (int i = 0; i < records.size(); i++) total += records.get(i).body().length();
            return total;
        }

        /** 一行话说清这次认出了什么、拿到多少、缺什么。界面与回执共用这一份措辞。 */
        public String summary() {
            StringBuilder out = new StringBuilder();
            if (format.length() == 0) return "认不出这是哪一种题录导出格式（支持 NoteExpress、RefWorks、"
                    + "EndNote/RIS、BibTeX 与 GB/T 7714 题录文本）";
            out.append("认作 ").append(format);
            if (charset.length() > 0) out.append("（").append(charset).append(" 读入）");
            out.append("，解析 ").append(records.size()).append(" 篇，其中带摘要 ").append(withAbstract())
                    .append(" 篇");
            if (withoutAbstract() > 0) out.append("、只有题录 ").append(withoutAbstract()).append(" 篇");
            if (blankTitle > 0) out.append("；丢掉没有题名的 ").append(blankTitle).append(" 条");
            if (unknownTags > 0) out.append("；未认的标签 ").append(unknownTags).append(" 种");
            if (strayLines > 0) out.append("；没归进任何一篇的行 ").append(strayLines).append(" 行");
            return out.toString();
        }
    }

    /** 一份导出文件。文件名只用来在说明里说人话，不参与解析。 */
    public static Parsed parse(byte[] content, String fileName) {
        Parsed parsed = new Parsed();
        parsed.bytes = content == null ? 0 : content.length;
        String text = decode(content, parsed);
        if (text.trim().length() == 0) {
            parsed.notes.add("文件里没有读到文本");
            return parsed;
        }
        countLines(text, parsed);
        parse(text, parsed);
        if (parsed.format.length() == 0)
            parsed.notes.add("认不出题录格式，整份文件没有入库");
        return parsed;
    }

    /** 已经拿在手里的文本（界面上"粘贴题录"那一路）。 */
    public static Parsed parse(String text) {
        Parsed parsed = new Parsed();
        parsed.bytes = text == null ? 0 : text.length();
        parse(text, parsed);
        return parsed;
    }

    private static void parse(String text, Parsed parsed) {
        String format = formatOf(text);
        parsed.format = format;
        if (FORMAT_NOTEEXPRESS.equals(format) || FORMAT_REFWORKS.equals(format))
            parseTagged(text, parsed, format);
        else if (FORMAT_RIS.equals(format)) parseRis(text, parsed);
        else if (FORMAT_BIBTEX.equals(format)) parseBibtex(text, parsed);
        else if (FORMAT_PLAINTEXT.equals(format)) parsePlain(text, parsed);
        for (int i = 0; i < parsed.records.size(); i++) parsed.records.get(i).ordinal = i + 1;
    }

    /**
     * 编码嗅探。知网的 txt 导出在 Windows 记事本里另存过就是 ANSI，直接按 UTF-8 读会得到一串问号，
     * 那比读不出更坏——所以先严格试 UTF-8，报错才退 GB18030（GBK/GB2312 的超集）。
     * 两边都解不出时按 UTF-8 宽松解并在说明里写死：这一份的编码没验出来。
     */
    static String decode(byte[] content, Parsed parsed) {
        if (content == null || content.length == 0) return "";
        int offset = 0;
        if (content.length >= 3 && (content[0] & 0xFF) == 0xEF && (content[1] & 0xFF) == 0xBB
                && (content[2] & 0xFF) == 0xBF) {
            offset = 3;
            parsed.charset = "UTF-8 带 BOM";
        }
        // 记事本里那个"Unicode"存出来是 UTF-16。先认 BOM；没有 BOM 的看字节形状。
        // 不拦的后果很难看：UTF-16 的 ASCII 段按 UTF-8 严格解是合法的，会得到一串夹 NUL 的乱码，
        // 一声不响地入库，比对时对不上也没人知道为什么。
        String bom = utf16Charset(content);
        if (bom != null) {
            String wide = tryDecode(content, 2, bom, true);
            if (wide == null) wide = tryDecode(content, 2, bom, false);
            if (wide != null) {
                parsed.charset = bom + "（带 BOM）";
                return wide;
            }
        }
        String strict = tryDecode(content, offset, "UTF-8", true);
        if (strict != null && strict.indexOf('\u0000') < 0) {
            if (parsed.charset.length() == 0) parsed.charset = "UTF-8";
            return strict;
        }
        String bare16 = utf16Bare(content);
        if (bare16 != null) {
            String wide = tryDecode(content, 0, bare16, true);
            if (wide != null && wide.indexOf('\u0000') < 0) {
                parsed.charset = bare16 + "（无 BOM）";
                return wide;
            }
        }
        String gb = tryDecode(content, offset, "GB18030", true);
        if (gb != null) {
            parsed.charset = "GB18030";
            return gb;
        }
        parsed.charset = "按 UTF-8 勉强读（编码没验出来）";
        String loose = tryDecode(content, offset, "UTF-8", false);
        return loose == null ? "" : loose;
    }

    /** UTF-16 的 BOM。认出来返回 charset 名，认不出返回 null。 */
    private static String utf16Charset(byte[] c) {
        if (c.length >= 2 && (c[0] & 0xFF) == 0xFF && (c[1] & 0xFF) == 0xFE) return "UTF-16LE";
        if (c.length >= 2 && (c[0] & 0xFF) == 0xFE && (c[1] & 0xFF) == 0xFF) return "UTF-16BE";
        return null;
    }

    /**
     * 没写 BOM 的 UTF-16 只能靠字节形状认：ASCII 为主的题录编码成 UTF-16 之后每两个字节里就有一个 0，
     * 0 落在奇位还是偶位分出大小端。阈值取"三分之一的对子里有一个 0"：汉字在 UTF-16 里两个字节都在
     * 高位，所以中文越多的稿子这个比例越接近一半以下，再卡高就认不出来了；而 GB18030 的中文两字节都在
     * 高位、纯 ASCII 里根本不会出现 0x00，这两种都到不了三分之一的 0，不会被误判成 UTF-16。
     */
    static String utf16Bare(byte[] c) {
        if (c == null || c.length < 16 || (c.length & 1) != 0) return null;
        int oddZero = 0, evenZero = 0;
        for (int i = 0; i + 1 < c.length; i += 2) {
            if ((c[i + 1] & 0xFF) == 0) oddZero++;
            if ((c[i] & 0xFF) == 0) evenZero++;
        }
        int pairs = c.length / 2;
        if (oddZero * 3 >= pairs && oddZero >= evenZero) return "UTF-16LE";
        if (evenZero * 3 >= pairs) return "UTF-16BE";
        return null;
    }

    private static String tryDecode(byte[] content, int offset, String charset, boolean strict) {
        Charset found = charset(charset);
        if (found == null) return null;
        try {
            CharsetDecoder decoder = found.newDecoder();
            decoder.onMalformedInput(strict ? CodingErrorAction.REPORT : CodingErrorAction.REPLACE);
            decoder.onUnmappableCharacter(strict ? CodingErrorAction.REPORT : CodingErrorAction.REPLACE);
            CharBuffer chars = decoder.decode(ByteBuffer.wrap(content, offset, content.length - offset));
            return chars.toString();
        } catch (Exception error) {
            return null;
        }
    }

    private static Charset charset(String name) {
        try {
            return Charset.forName(name);
        } catch (Exception error) {
            return null;   // 个别 ROM 没带 GB18030：宁可说读不出编码，也不交一串乱码进库
        }
    }

    // ---- 格式识别 ----

    private static final String[] RIS_TAGS = { "TY", "TI", "AB", "AU", "ER" };

    /** 只扫前 200 行：题录文件的开头足够定格式，一份 5 MB 的文件不该为认格式整份扫一遍。 */
    static String formatOf(String text) {
        if (text == null) return "";
        String[] lines = text.split("\r?\n", -1);
        int scan = Math.min(lines.length, 200);
        boolean tagged = false, ris = false, bibtex = false, plain = false;
        String taggedType = "";
        for (int i = 0; i < scan; i++) {
            String line = lines[i];
            if (line.length() == 0) continue;
            if (line.charAt(0) == '@' && line.length() > 1 && Character.isLetter(line.charAt(1))
                    && line.indexOf('{') > 0) bibtex = true;
            if (line.length() >= 2 && line.charAt(0) == '%' && isTag(line.charAt(1))) {
                tagged = true;
                if (line.charAt(1) == '0' && taggedType.length() == 0)
                    taggedType = line.substring(2).trim();
            }
            for (int r = 0; r < RIS_TAGS.length; r++)
                if (matchesRis(line, RIS_TAGS[r])) ris = true;
            if (TYPE_MARK.matcher(line).find() || line.startsWith("【")) plain = true;
        }
        if (bibtex) return FORMAT_BIBTEX;
        if (ris) return FORMAT_RIS;
        if (tagged) return taggedDialect(taggedType);
        if (plain) return FORMAT_PLAINTEXT;
        return "";
    }

    /**
     * NoteExpress 与 RefWorks 共用一族 % 标签，靠 %0 的类型值分开：
     * NoteExpress 连写（JournalArticle、Thesis），RefWorks 带空格（Journal Article）。
     * 认不出类型值时按 RefWorks 处理——它的刊名位 %B 与 NoteExpress 的 %J 在赋值表里都认，
     * 定错方言最多是标签种类数说得不准，不至于丢字段。
     */
    private static String taggedDialect(String type) {
        String value = type == null ? "" : type.trim();
        if (value.length() == 0) return FORMAT_REFWORKS;
        return value.indexOf(' ') < 0 ? FORMAT_NOTEEXPRESS : FORMAT_REFWORKS;
    }

    /**
     * % 后面跟的什么都算一号：除了字母数字，知网与 RefWorks 还用得上 %@（ISSN）与 %~（数据库名）。
     * 放过非字母数字的那几号，才不会把 %@ 那一行当成上一号的折行，把 ISSN 拼到刊名尾巴上去。
     */
    private static boolean isTag(char c) {
        return !Character.isWhitespace(c);
    }

    /** RIS 的一行是 "TY  - JOUR"：两个字母、至少两个空格、一个连字符。字段值可以为空。 */
    private static boolean matchesRis(String line, String tag) {
        if (!line.startsWith(tag)) return false;
        int i = tag.length();
        if (i >= line.length() || line.charAt(i) != ' ' && line.charAt(i) != '\t') return false;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return i < line.length() && line.charAt(i) == '-' && i + 1 <= line.length();
    }

    /** GB/T 7714 的文献类型标志：[J]、[D]、[J/OL]、[C]//…。这是纯文本题录唯一跑不掉的记号。 */
    private static final Pattern TYPE_MARK = Pattern.compile("\\[[A-Z]{1,3}(?:/(?:OL|DK|MT|CD))?(?:\\]\\[)?\\]");
    // ---- 解析：四种机读写法 + 一种人读写法 ----

    /** 没认的标签种类在这里只数一次；同一种出现一百回也只记一种。 */
    static void markUnknown(Parsed parsed, String kind) {
        if (parsed.markUnknownKind(kind)) parsed.unknownTags++;
    }

    private static void parseTagged(String text, Parsed parsed, String format) {
        String[] lines = text.split("\r?\n", -1);
        Record current = null;
        char last = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().length() == 0) continue;
            if (line.length() >= 2 && line.charAt(0) == '%' && isTag(line.charAt(1))) {
                char tag = line.charAt(1);
                if (tag == '0') {
                    if (current != null) keep(parsed, current);
                    current = new Record();
                    current.format = format;
                    last = '0';
                    continue;
                }
                if (current == null) {
                    current = new Record();     // 有的导出把 %0 省了，第一块直接从 %A 起
                    current.format = format;
                }
                last = tag;
                assignTag(parsed, current, tag, line.substring(2).trim(), false);
                continue;
            }
            if (current == null || last == 0 || last == '0') {
                parsed.strayLines++;
                continue;
            }
            // 折行续排：摘要与长题名在导出文件里常被折成两行，下半截不带标签。
            assignTag(parsed, current, last, line.trim(), true);
        }
        if (current != null) keep(parsed, current);
    }

    /**
     * % 标签到字段的对应表。两种方言各占一边（NoteExpress 的刊名在 %J、RefWorks 在 %B），
     * 两个都能落到 venue 就不必为方言各写一份表；对不上的那几号只数种类，不猜内容。
     */
    private static boolean assignTag(Parsed parsed, Record rec, char tag, String value, boolean cont) {
        if (value == null || value.trim().length() == 0) return true;
        String text = value.trim();
        switch (tag) {
            case 'T':
                rec.title = join(rec.title, text, cont);
                return true;
            case 'A':
            case 'E':
                addNames(rec.authors, text);
                return true;
            case 'J':
            case 'B':
                rec.venue = join(rec.venue, text, cont);
                return true;
            case 'C':
                // 知网在这里放的是刊号（11-2127/TP），不是刊名；认得出刊号就不许把它当刊名。
                if (CN_ISSUE.matcher(text).matches()) return true;
                if (rec.venue.length() == 0) rec.venue = join("", text, false);
                else rec.venue = join(rec.venue, text, cont);
                return true;
            case 'D':
                if (rec.year.length() == 0) rec.year = yearOf(text);
                return true;
            case 'V':
            case '7':
                rec.volume = join(rec.volume, digitsLeading(text), cont);
                return true;
            case 'N':
                rec.issue = join(rec.issue, text, cont);
                return true;
            case '8':
                // 两种方言在这号上一个放期、一个放日期，按值的形状分。
                if (text.matches("\\d{1,4}")) rec.issue = join(rec.issue, text, cont);
                else if (rec.year.length() == 0) rec.year = yearOf(text);
                return true;
            case 'P':
                rec.pages = join(rec.pages, text, cont);
                return true;
            case '9':
                // 这号两种方言装的东西不同：知网的 NoteExpress 在这里放数据库名（CNKI），
                // RefWorks 放 ISSN 或页码。没有别的办法，只能按形状分给三边。
                if (ISSN.matcher(text).matches()) return true;
                if (PAGES.matcher(text).matches()) {
                    rec.pages = join(rec.pages, text, cont);
                } else if (DATABASE_NAME.matcher(text).matches()) {
                    rec.database = join(rec.database, text, false);
                }
                return true;
            case 'X':
            case '3':
                rec.abstractText = join(rec.abstractText, text, cont, true);
                return true;
            case 'K':
                addWords(rec.keywords, text);
                return true;
            case 'R':
                if (isDoi(text)) rec.doi = join(rec.doi, text, false);
                else rec.id = join(rec.id, text, false);
                return true;
            case 'U':
                rec.locator = join(rec.locator, text, false);
                return true;
            case '@':
                return true;      // ISSN：认得它，但它不进可比文本
            case '%':
            case '~':
                rec.database = join(rec.database, text, false);
                return true;
            default:
                markUnknown(parsed, "%" + tag);
                return false;
        }
    }

    /** 国内刊号是 `11-2127/TP` 这个形状：前段两到四位、后段四位、可带分类号。 */
    private static final Pattern CN_ISSUE = Pattern.compile("\\d{2,4}-\\d{4}[A-Z]?(?:/[A-Z]{1,3})?");
    private static final Pattern ISSN = Pattern.compile("\\d{4}-\\d{3}[\\dXx]");
    private static final Pattern PAGES = Pattern.compile("([A-Za-z]?\\d{1,4})(?:\\s*[-–—]\\s*([A-Za-z]?\\d{1,4}))?");
    /** 数据库名那一号：纯 ASCII 的短词（CNKI / WANFANG / VIP），汉字值不会误进这一支。 */
    private static final Pattern DATABASE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_+-]{1,15}");
    private static final Pattern YEAR = Pattern.compile("(1[89]\\d{2}|20\\d{2})");

    private static boolean isDoi(String value) {
        return value.regionMatches(true, 0, "10.", 0, 3) || value.regionMatches(true, 0, "doi:", 0, 4);
    }

    private static String yearOf(String text) {
        Matcher m = YEAR.matcher(text == null ? "" : text);
        return m.find() ? m.group(1) : "";
    }

    private static String digitsLeading(String text) {
        Matcher m = Pattern.compile("^\\d{1,4}").matcher(text.trim());
        return m.find() ? m.group() : text.trim();
    }

    /**
     * 作者与关键词的分行写法：一个 %A 一个人，也有导出把一排人塞进同一号里用分号隔开。
     * 逗号只在纯文本题录那一族里当分隔符（GB/T 7714 写 `张三,李四`）——标签式里
     * `Smith, J.` 是英文倒装名，逗号一劈就把一个人劈成两个。
     * 同一个人出现两次（题录行一次、【作者】行一次）只留一个。
     */
    private static void addNames(ArrayList<String> into, String text) {
        addNames(into, text, false);
    }

    private static void addNames(ArrayList<String> into, String text, boolean commaSplits) {
        String[] parts = text.split(commaSplits ? "[;,，；、]|\\s+and\\s+" : "[;；、]|\\s+and\\s+");
        for (int i = 0; i < parts.length; i++) {
            String name = parts[i].trim();
            if (name.length() == 0 || name.equals("等") || name.equals("et al")) continue;
            if (!into.contains(name)) into.add(name);
        }
    }

    private static void addWords(ArrayList<String> into, String text) {
        String[] parts = text.split("[;；，,、]");
        for (int i = 0; i < parts.length; i++) {
            String word = parts[i].trim();
            if (word.length() > 0) into.add(word);
        }
    }

    /** 同一个字段再落一次：折行接在原句后面，另起一行另给一次同一个标签则用空格隔开。 */
    private static String join(String existing, String value, boolean lineWrap) {
        return join(existing, value, lineWrap, false);
    }

    private static String join(String existing, String value, boolean lineWrap, boolean paragraph) {
        String old = existing == null ? "" : existing;
        if (old.trim().length() == 0) return value;
        if (paragraph) return old + "\n" + value;
        if (!lineWrap) return old + " " + value;
        return old + (touchesCjk(old, value) ? value : " " + value);
    }

    /** 汉字之间补空格会把"深度学习"劈成两半（CnkiSearch.plain 里记着同一笔账），这里不补。 */
    private static boolean touchesCjk(String left, String right) {
        String a = left.trim(), b = right.trim();
        if (a.length() == 0 || b.length() == 0) return true;
        return isCjk(a.charAt(a.length() - 1)) || isCjk(b.charAt(0));
    }

    private static boolean isCjk(char c) {
        return c >= 0x2E80 && c <= 0x9FFF;
    }

    private static void keep(Parsed parsed, Record rec) {
        if (rec == null) return;
        if (rec.title.trim().length() == 0) {
            parsed.blankTitle++;
            return;
        }
        parsed.records.add(rec);
    }

    // ---- RIS / EndNote ----

    private static final Pattern RIS_LINE = Pattern.compile("^([A-Z][A-Za-z0-9]{0,3})\\s+-\\s?(.*)$");
    /** 认得但不进可比文本的 RIS 标签：语言、出版地、馆藏、被引列表这些对查重没有贡献。 */
    private static final String[] RIS_IGNORED = {
        "LA", "PB", "CY", "AV", "N1", "N3", "M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9",
        "C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8", "C9", "CSP", "ET", "ID", "L1", "L4", "CR",
        "CN", "RP", "ST", "U1", "U2", "U3", "U4", "U5", "Y2", "NV", "NS", "SP", "EP", "A2", "A3",
    };

    private static void parseRis(String text, Parsed parsed) {
        String[] lines = text.split("\r?\n", -1);
        Record current = null;
        String last = "";
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().length() == 0) continue;
            Matcher m = RIS_LINE.matcher(line);
            if (m.matches()) {
                String tag = m.group(1).toUpperCase(Locale.US);
                String value = m.group(2).trim();
                if ("TY".equals(tag)) {
                    if (current != null) keep(parsed, current);
                    current = new Record();
                    current.format = FORMAT_RIS;
                    last = tag;
                    continue;
                }
                if ("ER".equals(tag)) {
                    keep(parsed, current);
                    current = null;
                    last = "";
                    continue;
                }
                if (current == null) {
                    current = new Record();
                    current.format = FORMAT_RIS;
                }
                last = tag;
                assignRis(parsed, current, tag, value, false);
                continue;
            }
            if (current == null || last.length() == 0) {
                parsed.strayLines++;
                continue;
            }
            assignRis(parsed, current, last, line.trim(), true);
        }
        if (current != null) keep(parsed, current);
    }

    private static void assignRis(Parsed parsed, Record rec, String tag, String value, boolean cont) {
        if (value.length() == 0) return;
        if ("TI".equals(tag) || "T1".equals(tag)) rec.title = join(rec.title, value, cont);
        else if ("AU".equals(tag) || "A1".equals(tag)) addNames(rec.authors, value);
        else if ("T2".equals(tag) || "JO".equals(tag) || "JF".equals(tag) || "BT".equals(tag)
                || "T3".equals(tag) || "JA".equals(tag)) rec.venue = join(rec.venue, value, cont);
        else if ("PY".equals(tag) || "Y1".equals(tag)) { if (rec.year.length() == 0) rec.year = yearOf(value); }
        else if ("DA".equals(tag)) { if (rec.year.length() == 0) rec.year = yearOf(value); }
        else if ("VL".equals(tag)) rec.volume = join(rec.volume, digitsLeading(value), cont);
        else if ("IS".equals(tag) || "NI".equals(tag)) rec.issue = join(rec.issue, value, cont);
        else if ("SP".equals(tag) || "EP".equals(tag)) rec.pages = rec.pages.length() == 0 ? value : rec.pages + "-" + value;
        else if ("AB".equals(tag) || "N2".equals(tag)) rec.abstractText = join(rec.abstractText, value, cont, true);
        else if ("KW".equals(tag)) addWords(rec.keywords, value);
        else if ("DO".equals(tag) || "DI".equals(tag)) rec.doi = join(rec.doi, value.replaceFirst("(?i)^doi:", ""), false);
        else if ("UR".equals(tag) || "L2".equals(tag)) rec.locator = join(rec.locator, value, false);
        else if ("AN".equals(tag)) rec.id = join(rec.id, value, false);
        else if ("DB".equals(tag)) rec.database = join(rec.database, value, false);
        else if ("SN".equals(tag)) return;
        else if ("ER".equals(tag)) return;
        else if (indexOf(RIS_IGNORED, tag) >= 0) return;
        else markUnknown(parsed, tag);
    }

    private static int indexOf(String[] table, String value) {
        for (int i = 0; i < table.length; i++) if (table[i].equals(value)) return i;
        return -1;
    }
    // ---- BibTeX ----

    private static void parseBibtex(String text, Parsed parsed) {
        int at = 0;
        while (at < text.length()) {
            int start = text.indexOf('@', at);
            if (start < 0) break;
            int brace = text.indexOf('{', start + 1);
            if (brace < 0) {
                parsed.strayLines++;
                break;
            }
            String type = text.substring(start + 1, brace).trim();
            int end = matchBrace(text, brace);
            String body = text.substring(brace + 1, end < 0 ? text.length() : end);
            at = end < 0 ? text.length() : end + 1;
            if (end < 0) parsed.notes.add("有一条 @ 条目没闭合，最后一条只读到文件末尾");
            if ("comment".equalsIgnoreCase(type) || "string".equalsIgnoreCase(type)
                    || "preamble".equalsIgnoreCase(type)) continue;
            Record rec = new Record();
            rec.format = FORMAT_BIBTEX;
            int comma = body.indexOf(',');
            String fields = body;
            if (comma > 0) {
                rec.id = body.substring(0, comma).trim();   // 知网的条目键就是文献号（CAHD202103005 这一族）
                fields = body.substring(comma + 1);
            }
            assignBibtex(parsed, rec, fields);
            keep(parsed, rec);
        }
    }

    /** 从左花括号起找到配对的右花括号，字符串里的括号与转义的括号不算数。找不到返回 -1。 */
    private static int matchBrace(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                i++;
                continue;
            }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static void assignBibtex(Parsed parsed, Record rec, String body) {
        int i = 0;
        while (i < body.length()) {
            int eq = body.indexOf('=', i);
            if (eq < 0) break;
            String name = body.substring(i, eq).trim().toLowerCase(Locale.US);
            int j = eq + 1;
            while (j < body.length() && Character.isWhitespace(body.charAt(j))) j++;
            String value;
            int next;
            if (j < body.length() && body.charAt(j) == '{') {
                int close = matchBrace(body, j);
                int stop = close < 0 ? body.length() : close;
                value = unwrapBraces(body.substring(j + 1, stop));
                next = close < 0 ? body.length() : close + 1;
            } else if (j < body.length() && body.charAt(j) == '"') {
                int close = j + 1;
                while (close < body.length() && body.charAt(close) != '"') close++;
                value = body.substring(j + 1, Math.min(close, body.length()));
                next = Math.min(close + 1, body.length());
            } else {
                int stop = j;
                while (stop < body.length() && body.charAt(stop) != ',') stop++;
                value = body.substring(j, stop).trim();
                next = Math.min(stop + 1, body.length());
            }
            assignBibtexField(parsed, rec, name, value.trim());
            i = skipComma(body, next);
        }
    }

    private static int skipComma(String body, int from) {
        int i = from;
        while (i < body.length() && (body.charAt(i) == ',' || Character.isWhitespace(body.charAt(i)))) i++;
        return i;
    }

    /**
     * 拆掉 BibTeX 的花括号。外面那层是"整个值别动"，里面那些是"这几个字母别小写化"
     * （{深度学习}半监督分割网络综述 这种），两层都是排版记号，不是内容：
     * 留着花括号，比对时这道墙两侧的字就断开了。
     */
    private static String unwrapBraces(String value) {
        String text = value.trim();
        while (text.length() >= 2 && text.charAt(0) == '{' && text.charAt(text.length() - 1) == '}') {
            int close = matchBrace(text, 0);
            if (close != text.length() - 1) break;
            text = text.substring(1, text.length() - 1).trim();
        }
        return text.replace("\\&", "&").replace("\\%", "%").replace("\\_", "_")
                .replace("{", "").replace("}", "");
    }

    private static void assignBibtexField(Parsed parsed, Record rec, String name, String value) {
        if (value.length() == 0) return;
        if ("title".equals(name)) rec.title = join(rec.title, value, false);
        else if ("author".equals(name) || "editor".equals(name)) addNames(rec.authors, value);
        else if ("journal".equals(name) || "journaltitle".equals(name) || "booktitle".equals(name)
                || "series".equals(name) || "school".equals(name) || "institution".equals(name))
            rec.venue = join(rec.venue, value, false);
        else if ("year".equals(name) || "date".equals(name)) { if (rec.year.length() == 0) rec.year = yearOf(value); }
        else if ("number".equals(name) || "issue".equals(name)) rec.issue = join(rec.issue, value, false);
        else if ("volume".equals(name)) rec.volume = join(rec.volume, digitsLeading(value), false);
        else if ("pages".equals(name)) rec.pages = join(rec.pages, value.replace("--", "-"), false);
        else if ("abstract".equals(name) || "annotation".equals(name))
            rec.abstractText = join(rec.abstractText, value, false, true);
        else if ("keywords".equals(name) || "keyword".equals(name)) addWords(rec.keywords, value);
        else if ("doi".equals(name)) rec.doi = join(rec.doi, value, false);
        else if ("url".equals(name) || "ee".equals(name) || "link".equals(name))
            rec.locator = join(rec.locator, value, false);
        else if ("isbn".equals(name) || "issn".equals(name) || "month".equals(name) || "type".equals(name)
                || "publisher".equals(name) || "address".equals(name) || "note".equals(name)) return;
        else markUnknown(parsed, name);
    }

    // ---- GB/T 7714 那一族纯文本题录 ----

    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\s*(?:\\[\\d{1,3}\\]|\\d{1,3}[.、)])\\s*");
    private static final Pattern FIELD_LINE = Pattern.compile("^【\\s*([^】]{1,16}?)\\s*】[:：]?\\s*(.*)$");
    private static final Pattern VOLUME_ISSUE = Pattern.compile("(\\d{1,4})\\s*[（(]\\s*(\\d{1,4})\\s*[）)]");
    private static final Pattern ISSUE_ONLY = Pattern.compile("[,，]\\s*(\\d{1,4})\\s*:");
    private static final Pattern TAIL_PREFIX = Pattern.compile("^[.。、,，;；:：]+");
    private static final Pattern PLAIN_PAGES = Pattern.compile("[:：]\\s*(\\d{1,4}(?:\\s*[-–—]\\s*\\d{1,4})?)\\s*[.。]?\\s*$");

    private static void parsePlain(String text, Parsed parsed) {
        String[] lines = text.split("\r?\n", -1);
        Record current = null;
        // 只有紧跟在【摘要】后面（或紧跟在摘要的另一截后面）的行才算摘要的折行：
        // 隔了一个字段行再来的散装句子，宁可数成 stray，也不许长进摘要里。
        boolean inAbstractWrap = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0) continue;
            Matcher mark = TYPE_MARK.matcher(line);
            if (mark.find() && line.length() > mark.end()) {
                if (current != null) keep(parsed, current);
                current = new Record();
                current.format = FORMAT_PLAINTEXT;
                readCiteLine(current, line, mark.start(), mark.end());
                inAbstractWrap = false;
                continue;
            }
            Matcher field = FIELD_LINE.matcher(line);
            if (field.matches()) {
                if (current == null) {
                    current = new Record();
                    current.format = FORMAT_PLAINTEXT;
                }
                String name = field.group(1).trim();
                assignPlain(parsed, current, name, field.group(2).trim());
                inAbstractWrap = name.equals("摘要") && current.abstractText.length() > 0;
                continue;
            }
            if (current != null && inAbstractWrap) {
                current.abstractText = join(current.abstractText, line, true, true);
                continue;
            }
            parsed.strayLines++;
        }
        if (current != null) keep(parsed, current);
    }

    /**
     * 一行 GB/T 7714：`[1]张三;李四.题名[J].刊名,2021,57(03):12-20.`
     * 分界线是那个文献类型标志——它前面的最后一个小数点分开作者与题名，它后面到第一个逗号是刊名。
     */
    private static void readCiteLine(Record rec, String line, int markStart, int markEnd) {
        String head = LEADING_NUMBER.matcher(line.substring(0, markStart)).replaceFirst("").trim();
        // [J] 后面照例还跟着一个小数点（`[J].刊名`），不先脱掉它，刊名会被读成空串。
        String tail = TAIL_PREFIX.matcher(line.substring(markEnd)).replaceFirst("").trim();
        int cut = Math.max(head.lastIndexOf('.'), head.lastIndexOf('。'));
        if (cut >= 0) {
            addNames(rec.authors, head.substring(0, cut).trim(), true);
            rec.title = head.substring(cut + 1).trim();
        } else {
            rec.title = head;
        }
        int venueEnd = tail.length();
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (c == ',' || c == '，' || c == '.' || c == '。') {
                venueEnd = i;
                break;
            }
        }
        rec.venue = PLACE_PREFIX.matcher(tail.substring(0, venueEnd).trim()).replaceFirst("").trim();
        rec.year = yearOf(tail);
        Matcher vi = VOLUME_ISSUE.matcher(tail);
        if (vi.find()) {
            rec.volume = vi.group(1);
            rec.issue = vi.group(2);
        } else {
            Matcher io = ISSUE_ONLY.matcher(tail);
            if (io.find()) rec.issue = io.group(1);
        }
        Matcher pg = PLAIN_PAGES.matcher(tail);
        if (pg.find()) rec.pages = pg.group(1);
    }

    /** 学位论文的出版地前缀（`合肥: 中国科学技术大学`），留学校去地。 */
    private static final Pattern PLACE_PREFIX = Pattern.compile("^[\\u4e00-\\u9fa5]{2,10}[:：]\\s*");

    private static void assignPlain(Parsed parsed, Record rec, String name, String value) {
        if (value.length() == 0) return;
        if (name.contains("题名") || name.contains("篇名") || name.equals("标题")) rec.title = value;
        else if (name.contains("单位")) return;   // 【作者单位】必须挡在【作者】前面，否则院系被当成第三个人
        else if (name.contains("作者")) addNames(rec.authors, value, true);
        else if (name.equals("摘要")) rec.abstractText = join(rec.abstractText, value, false, true);
        else if (name.contains("关键词")) addWords(rec.keywords, value);
        else if (name.contains("刊名") || name.contains("期刊") || name.contains("文献来源")
                || name.contains("发表刊物") || name.contains("授予单位") || name.contains("导师"))
            rec.venue = join(rec.venue, value, false);
        else if (name.equals("年")) rec.year = yearOf(value);
        else if (name.equals("期")) rec.issue = value;
        else if (name.equals("卷")) rec.volume = digitsLeading(value);
        else if (name.contains("页")) rec.pages = value;
        else if (name.equalsIgnoreCase("DOI")) rec.doi = value;
        else if (name.contains("网址") || name.contains("链接") || name.equalsIgnoreCase("URL"))
            rec.locator = value;
        else if (name.contains("文献号") || name.contains("编号")) rec.id = value;
        else if (name.contains("基金") || name.contains("分类号")
                || name.contains("日期") || name.contains("专辑") || name.contains("专题")
                || name.contains("收录") || name.contains("价格")) return;
        else markUnknown(parsed, "【" + name + "】");
    }

    /** 非空行数只数一次，供 prefersRecords 用；解析本身不靠它。 */
    private static void countLines(String text, Parsed parsed) {
        String[] lines = text.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) if (lines[i].trim().length() > 0) parsed.lines++;
    }

    // ---- 入库 ----

    /** 这些扩展名就是"用户从导出页存下来的文件"，不用再猜。 */
    private static final String[] RECORD_EXTENSIONS = {
        "bib", "ris", "enl", "enlx", "nbib", "repec", "noteexpress", "refworks", "refer",
    };

    /**
     * 这个文件该走题录那条路，还是该当成一篇文档整份进库？
     *
     * 扩展名先说话：.bib/.ris/.enl 这些是导出文件，没有第二种可能。剩下的是 .txt——
     * 那既可能是知网的纯文本题录导出，也可能是用户自己的论文正文（里面正好有一段参考文献表）。
     * 后者被劈成一百条只选题名的"记录"是实打实的材料损失，所以 .txt 里的题录文本写法
     * 必须占满这份文件（题录行数 >= 3 且不少于非空行的六成）才改走题录那条路；
     * 标签式那四种写法（%0 / TY / @）没有这种歧义，认出来就走。
     */
    public static boolean prefersRecords(byte[] content, String fileName, Parsed parsed) {
        if (parsed == null || parsed.records.isEmpty()) return false;
        String extension = LocalLibrary.extensionOf(fileName == null ? "" : fileName);
        for (int i = 0; i < RECORD_EXTENSIONS.length; i++)
            if (RECORD_EXTENSIONS[i].equals(extension)) return true;
        if ("docx".equals(extension) || "pdf".equals(extension) || "md".equals(extension)) return false;
        if (FORMAT_NOTEEXPRESS.equals(parsed.format) || FORMAT_REFWORKS.equals(parsed.format)
                || FORMAT_RIS.equals(parsed.format) || FORMAT_BIBTEX.equals(parsed.format)) return true;
        return parsed.records.size() >= 3 && parsed.records.size() * 5 >= parsed.lines * 3;
    }

    // ---- 老写法 ----

    /**
     * 解析结果送进自建库：每篇一个文件，走 CorpusImport.run 那条已经通着的路。
     * 每篇单独成篇而不是整份文件进库，是为了来源榜能点到具体那一篇——整份进库的话，
     * 五百条题录并成一行，报告只会指着"这份导出文件"说重复了多少字。
     */
    public static CorpusImport.Batch into(LocalLibrary library, Parsed parsed,
                                          CorpusImport.Progress progress, CorpusImport.Cancel cancel) {
        return CorpusImport.run(library, sources(parsed), progress, cancel);
    }

    /** 只要文件不要入库（界面先给用户看一眼清单，再决定要不要真往里灌）。 */
    public static ArrayList<CorpusImport.Source> sources(Parsed parsed) {
        ArrayList<CorpusImport.Source> out = new ArrayList<CorpusImport.Source>();
        if (parsed == null) return out;
        for (int i = 0; i < parsed.records.size(); i++) {
            Record rec = parsed.records.get(i);
            CorpusImport.Source source = new CorpusImport.Source(rec.fileName(), bytes(rec.body()));
            // 连摘要都没有的那一条落"仅题录"档：它的可比文本只剩题名一行，别冒充摘要级证据。
            source.material = rec.material();
            out.add(source);
        }
        return out;
    }

    private static byte[] bytes(String text) {
        try {
            return text.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException error) {
            throw new IllegalStateException(error);
        }
    }
}