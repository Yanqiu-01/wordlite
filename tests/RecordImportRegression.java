package com.rikkahub.wordlite;

import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;

/**
 * 题录批量入库的回归：五种导出写法各验一遍字段落位、编码嗅探（知网的 txt 常是 ANSI）、
 * 坏记录不拖垮整份文件、认不出格式时一个字都不许进库，最后走一遍真链路——
 * 题录进自建库 → LocalLibrary.index → TextCorpus.match 撞上抄自摘要的那句。
 *
 * 夹具全部内联在下面的字符串里，形状照各站导出页的真实写法手抄，不依赖网络也不依赖账号：
 * 这一条验收的是"用户手里那份导出文件能不能变成可比对材料"，与接口通不通无关。
 */
public final class RecordImportRegression {
    private static int checks;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
        System.out.println("PASS " + message);
    }

    private static final String ROOT = "artifacts/tests/record-import";
    private static final String ABSTRACT_A =
            "针对医学影像分割中标注数据稀缺的问题，提出一种基于深度学习的半监督分割网络，"
                    + "先用一致性正则约束无标注样本，再用边界注意力模块细化分割边缘。";
    private static final String TITLE_A = "基于深度学习的医学影像半监督分割网络";
    private static final String VENUE_A = "计算机工程与应用";

    public static void main(String[] args) throws Exception {
        noteExpressShape();
        refWorksShape();
        risShape();
        bibtexShape();
        plainCiteLines();
        plainAbstractBlocks();
        encodingSniffing();
        utf16AndTruncation();
        brokenRecords();
        unrecognisableFormat();
        routingDecides();
        intoLibraryAndMatch();
        deleteRecursively(new File(ROOT));
        System.out.println("SUMMARY " + checks
                + " 条题录入库断言通过；真机 SAF 选文件与知网导出页的按钮位置不在本机模拟");
    }

    // ---- 1. NoteExpress ----

    private static final String NOTE_EXPRESS =
              "%0 JournalArticle\r\n"
            + "%A 张明远\r\n"
            + "%A 李慧\r\n"
            + "%T " + TITLE_A + "\r\n"
            + "%J " + VENUE_A + "\r\n"
            + "%D 2021\r\n"
            + "%V 57\r\n"
            + "%N 03\r\n"
            + "%P 112-119\r\n"
            + "%K 深度学习;医学影像分割;半监督学习\r\n"
            + "%X " + ABSTRACT_A + "\r\n"
            + "%C 11-2127/TP\r\n"
            + "%@ 1002-8331\r\n"
            + "%R 10.16520/j.issn.1002-8331.2021.03.015\r\n"
            + "%U https://kns.cnki.net/kcms2/article/abstract?v=AAA\r\n"
            + "%9 CNKI\r\n"
            + "%0 JournalArticle\r\n"
            + "%A 王铸\r\n"
            + "%T 碳化硅瞬态液相扩散焊界面组织研究\r\n"
            + "%J 焊接学报\r\n"
            + "%D 2015\r\n"
            + "%N 04\r\n"
            + "%X 以 304 不锈钢为中间层，在 980 ℃ 下对 SiC 陶瓷进行瞬态液相扩散焊，观察界面反应层的形貌与成分分布。\r\n"
            + "%0 Thesis\r\n"
            + "%T 无摘要的一篇学位论文题录\r\n"
            + "%J 中国科学技术大学\r\n"
            + "%D 2020\r\n";

    private static void noteExpressShape() {
        RecordImport.Parsed parsed = RecordImport.parse(bytes(NOTE_EXPRESS), "cnki-noteexpress.txt");
        check(RecordImport.FORMAT_NOTEEXPRESS.equals(parsed.format),
                "%0 类型连写成 JournalArticle 认作 NoteExpress，got " + parsed.format);
        check("UTF-8".equals(parsed.charset), "无 BOM 的 UTF-8 按 UTF-8 读，got " + parsed.charset);
        check(parsed.records.size() == 3, "三块 %0 解析出三篇，got " + parsed.records.size());
        check(parsed.blankTitle == 0 && parsed.strayLines == 0, "没有丢记录，也没有落不进记录的行");

        RecordImport.Record a = parsed.records.get(0);
        check(TITLE_A.equals(a.title), "题名落在 %T");
        check(a.authors.size() == 2 && "张明远".equals(a.authors.get(0)) && "李慧".equals(a.authors.get(1)),
                "两个 %A 是两个人，不是一个人被劈成两半");
        check(VENUE_A.equals(a.venue), "刊名落在 %J，got [" + a.venue + "]");
        check("2021".equals(a.year) && "57".equals(a.volume) && "03".equals(a.issue), "年卷期各归各位");
        check("112-119".equals(a.pages), "页码落在 %P");
        check(a.keywords.size() == 3, "一行 %K 里的三个关键词被分开，got " + a.keywords.size());
        check(ABSTRACT_A.equals(a.abstractText), "摘要逐字进 %X");
        check("10.16520/j.issn.1002-8331.2021.03.015".equals(a.doi), "%R 里是 DOI 形状时按 DOI 收");
        check(a.locator.startsWith("https://kns.cnki.net/"), "%U 进 locator");
        check("CNKI".equals(a.database), "%9 的数据库名收进 database");
        check(a.hasAbstract() && DuplicateEngine.MATERIAL_ABSTRACT.equals(a.material()),
                "带摘要的一篇算摘要级材料");
        RecordImport.Parsed nameless = RecordImport.parse(bytes("%0 Thesis\r\n%A 某人\r\n"), "x.txt");
        check(nameless.records.isEmpty() && nameless.blankTitle == 1,
                "只有作者没有题名的块：丢掉并计数，不抛异常");

        String body = a.body();
        check(body.startsWith(ABSTRACT_A), "可比文本以摘要开头（与知网联网候选那一路同形）");
        check(body.indexOf('\n') < body.indexOf(TITLE_A), "题名录在摘要后面一行，不与摘要混成一句");
        check(body.contains("深度学习; 医学影像分割; 半监督学习"), "关键词进可比文本：照抄关键词的稿子撞得上");
        check(body.contains("2021,57(03)") || (body.contains("2021") && body.contains("57(03)")),
                "年期进可比文本");
        check(body.indexOf("http") < 0 && body.indexOf("10.16520") < 0,
                "链接与 DOI 不进可比文本：它们谁都一样，撞上了是假命中");
        check(!body.contains("11-2127/TP"), "%C 那个刊号没被当刊名塞进可比文本");
        check(a.fileName().endsWith(".txt") && a.fileName().indexOf('/') < 0,
                "入库文件名用题名：" + a.fileName());

        RecordImport.Record c = parsed.records.get(2);
        check(!c.hasAbstract() && DuplicateEngine.MATERIAL_RECORD.equals(c.material()),
                "第三篇只有题录，算题录级而不是摘要级");
        check(c.body().contains("无摘要的一篇学位论文题录"), "只有题录的一篇仍然有可比材料（题名那一行）");
        check(parsed.withAbstract() == 2 && parsed.withoutAbstract() == 1,
                "带摘要 2 篇、只有题录 1 篇分开数");
        check(parsed.summary().contains("解析 3 篇") && parsed.summary().contains("带摘要 2 篇"),
                "一句总结报得出篇数：" + parsed.summary());
    }

    // ---- 2. RefWorks ----

    private static final String REF_WORKS =
              "%0 Journal Article\r\n"
            + "%A 张明远\r\n"
            + "%T " + TITLE_A + "\r\n"
            + "%B " + VENUE_A + "\r\n"
            + "%D 2021\r\n"
            + "%7 57\r\n"
            + "%8 03\r\n"
            + "%9 112-119\r\n"
            + "%K 深度学习, 医学影像分割\r\n"
            + "%X " + ABSTRACT_A.substring(0, 24) + "\r\n"
            + " 这半句是摘要折行的下半截，不带标签，必须接回同一篇的摘要里\r\n"
            + "%Y 这一号我们不认\r\n"
            + "%2 这一号也不认\r\n";

    private static void refWorksShape() {
        RecordImport.Parsed parsed = RecordImport.parse(bytes(REF_WORKS), "cnki-refworks.txt");
        check(RecordImport.FORMAT_REFWORKS.equals(parsed.format),
                "%0 类型带空格（Journal Article）认作 RefWorks，got " + parsed.format);
        check(parsed.records.size() == 1, "一篇，got " + parsed.records.size());
        RecordImport.Record r = parsed.records.get(0);
        check(VENUE_A.equals(r.venue), "RefWorks 的刊名在 %B，也落到 venue");
        check("57".equals(r.volume), "%7 是卷号");
        check("03".equals(r.issue), "%8 放的是两位期号时期号归它");
        check("112-119".equals(r.pages), "%9 是页码形状时归页码，不当 ISSN");
        check(r.keywords.size() == 2, "逗号分隔的关键词被分开，got " + r.keywords.size());
        check(r.abstractText.endsWith("必须接回同一篇的摘要里"), "摘要折行的下半截接回同一条摘要");
        check(r.abstractText.indexOf('\n') == ABSTRACT_A.substring(0, 24).length(),
                "折行接在同一句里，没被劈成两句");
        check(r.abstractText.indexOf("这半句") > 0 && !r.abstractText.contains(" 这半句"),
                "汉字之间不补空格：补出来的空格会把词劈开");
        check(parsed.unknownTags == 2, "未认的标签数出两种（%Y、%2），got " + parsed.unknownTags);
        check(parsed.summary().contains("未认的标签 2 种"), "总结里说得出没认的标签种类");
    }
    // ---- 3. EndNote / RIS ----

    private static final String RIS =
              "TY  - JOUR\r\n"
            + "TI  - " + TITLE_A + "\r\n"
            + "AU  - 张明远\r\n"
            + "AU  - 李慧\r\n"
            + "JO  - " + VENUE_A + "\r\n"
            + "PY  - 2021\r\n"
            + "VL  - 57\r\n"
            + "IS  - 03\r\n"
            + "SP  - 112\r\n"
            + "EP  - 119\r\n"
            + "AB  - " + ABSTRACT_A + "\r\n"
            + "KW  - 深度学习\r\n"
            + "KW  - 医学影像分割\r\n"
            + "LA  - chinese\r\n"
            + "C1  - 这是被引列表里的一条，不该进可比文本\r\n"
            + "CR  - 参考 1\r\n"
            + "AN  - CAHD202103015\r\n"
            + "ER  - \r\n"
            + "TY  - JOUR\r\n"
            + "TI  - 第二条记录\r\n"
            + "AB  - 第二条的摘要。\r\n"
            + "ER  - ";

    private static void risShape() {
        RecordImport.Parsed parsed = RecordImport.parse(bytes(RIS), "cnki-endnote.txt");
        check(RecordImport.FORMAT_RIS.equals(parsed.format), "TY  - 起头认作 RIS，got " + parsed.format);
        check(parsed.records.size() == 2, "两个 ER 收尾出两篇，got " + parsed.records.size());
        RecordImport.Record a = parsed.records.get(0);
        check(TITLE_A.equals(a.title), "TI 落到题名");
        check(a.authors.size() == 2, "两行 AU 是两个人");
        check(VENUE_A.equals(a.venue), "JO 落到刊名");
        check("2021".equals(a.year) && "57".equals(a.volume) && "03".equals(a.issue), "PY/VL/IS 各归各位");
        check("112-119".equals(a.pages), "SP 与 EP 拼成页码范围，got " + a.pages);
        check(ABSTRACT_A.equals(a.abstractText), "AB 逐字进摘要");
        check(a.keywords.size() == 2, "两行 KW 都收进来");
        check("CAHD202103015".equals(a.id), "AN 是文献号");
        check(parsed.unknownTags == 0, "LA/C1/CR 这些认得但不进可比文本的标签不算未认，got " + parsed.unknownTags);
        check(!a.body().contains("被引列表") && !a.body().contains("参考 1"), "被引列表没混进可比文本");
        check("第二条记录".equals(parsed.records.get(1).title), "ER 之后的 TY 开新的一篇");
    }

    // ---- 4. BibTeX ----

    private static final String BIBTEX =
              "%BibTeX-file{ encoding = \"utf-8\" }\r\n"
            + "@Comment{这一条整个跳过}\r\n"
            + "@Article{CAHD202103015,\r\n"
            + "  title = {{深度学习}半监督分割网络综述},\r\n"
            + "  author = {张明远 and 李慧 and 王铸},\r\n"
            + "  journal = {" + VENUE_A + "},\r\n"
            + "  year = {2021},\r\n"
            + "  number = {03},\r\n"
            + "  pages = {1--9},\r\n"
            + "  abstract = {" + ABSTRACT_A + "},\r\n"
            + "  keywords = {深度学习; 半监督},\r\n"
            + "  doi = {10.16520/j.x},\r\n"
            + "  url = {https://kns.cnki.net/x}\r\n"
            + "}\r\n"
            + "@Article{WZ2015,\r\n"
            + "  title = {碳化硅瞬态液相扩散焊界面组织研究},\r\n"
            + "  journal = {焊接学报},\r\n"
            + "  year = 2015\r\n"
            + "}\r\n";

    private static void bibtexShape() {
        RecordImport.Parsed parsed = RecordImport.parse(bytes(BIBTEX), "cnki.bib");
        check(RecordImport.FORMAT_BIBTEX.equals(parsed.format), "@Article{ 认作 BibTeX，got " + parsed.format);
        check(parsed.records.size() == 2, "@Comment 不算记录，两篇，got " + parsed.records.size());
        RecordImport.Record a = parsed.records.get(0);
        check("CAHD202103015".equals(a.id), "条目键当文献号收着");
        check("深度学习半监督分割网络综述".equals(a.title), "保护大小写的外层花括号被拆掉，got " + a.title);
        check(a.authors.size() == 3 && "王铸".equals(a.authors.get(2)), "author 里的 and 分成三个人");
        check(VENUE_A.equals(a.venue) && "2021".equals(a.year) && "03".equals(a.issue), "journal/year/number 落位");
        check("1-9".equals(a.pages), "BibTeX 的双连字符页码收成单个连字符，got " + a.pages);
        check(ABSTRACT_A.equals(a.abstractText), "abstract 逐字进摘要");
        check(a.keywords.size() == 2, "keywords 分开收");
        check("10.16520/j.x".equals(a.doi) && a.locator.endsWith("/x"), "doi 与 url 各归各位");
        RecordImport.Record b = parsed.records.get(1);
        check("2015".equals(b.year), "不带花括号的裸数值 year 也认，got " + b.year);
        check(!b.hasAbstract() && b.body().contains("焊接学报"), "只有题录的一篇照样有可比材料");
    }

    // ---- 5. GB/T 7714 一行式题录 ----

    private static final String PLAIN_CITES =
              "[1]张明远;李慧." + TITLE_A + "[J]." + VENUE_A + ",2021,57(03):112-119.\r\n"
            + "[2]王铸.碳化硅瞬态液相扩散焊界面组织研究[J].焊接学报,2015,36(4):45-50.\r\n"
            + "[3]刘敏.面向嵌入式平台的推理加速方法研究[D].合肥:中国科学技术大学,2020.\r\n";

    private static void plainCiteLines() {
        RecordImport.Parsed parsed = RecordImport.parse(bytes(PLAIN_CITES), "cnki-plain.txt");
        check(RecordImport.FORMAT_PLAINTEXT.equals(parsed.format),
                "带 [J]/[D] 文献类型标志的行认作题录文本，got " + parsed.format);
        check(parsed.records.size() == 3, "三行三条题录解析三篇，got " + parsed.records.size());
        check(parsed.strayLines == 0, "没有落不进记录的行，got " + parsed.strayLines);
        RecordImport.Record a = parsed.records.get(0);
        check(TITLE_A.equals(a.title), "小数点与 [J] 之间的就是题名，got " + a.title);
        check(a.authors.size() == 2 && "李慧".equals(a.authors.get(1)), "分号隔开的两个作者分开收");
        check(VENUE_A.equals(a.venue), "[J] 之后到第一个逗号之间是刊名");
        check("2021".equals(a.year) && "57".equals(a.volume) && "03".equals(a.issue), "年,卷(期) 三段分开收");
        check("112-119".equals(a.pages), "冒号后是页码范围，got " + a.pages);
        check(!a.hasAbstract() && a.body().contains(TITLE_A), "一行式题录没有摘要，可比材料是题录那一行");
        RecordImport.Record c = parsed.records.get(2);
        check("中国科学技术大学".equals(c.venue), "学位论文的出版地前缀被剥掉，只留授予单位，got " + c.venue);
        check("2020".equals(c.year), "[D] 那条的年份也读得出来");
    }

    // ---- 6. 知网"摘要"那种导出：题录行 +【字段】行 ----

    private static final String PLAIN_BLOCKS =
              "1. 张明远,李慧." + TITLE_A + "[J]." + VENUE_A + ",2021,57(03):112-119.\r\n"
            + "【作者】张明远;李慧\r\n"
            + "【作者单位】合肥工业大学计算机与信息学院\r\n"
            + "【摘要】" + ABSTRACT_A + "\r\n"
            + "【关键词】深度学习;医学影像分割;半监督学习\r\n"
            + "【DOI】10.16520/j.issn.1002-8331.2021.03.015\r\n"
            + "【基金】国家自然科学基金项目(62076001)\r\n"
            + "【网刊发布时间】2021-02-10\r\n"
            + "这一行没有字段名，也没接在任何摘要后面\r\n"
            + "2. 王铸.碳化硅瞬态液相扩散焊界面组织研究[J].焊接学报,2015,36(4):45-50.\r\n"
            + "【摘要】以 304 不锈钢为中间层进行瞬态液相扩散焊。\r\n";

    private static void plainAbstractBlocks() {
        RecordImport.Parsed parsed = RecordImport.parse(bytes(PLAIN_BLOCKS), "cnki-abstract.txt");
        check(parsed.records.size() == 2, "两条题录行开两篇，got " + parsed.records.size());
        RecordImport.Record a = parsed.records.get(0);
        check(TITLE_A.equals(a.title), "题录行出题名");
        check(a.authors.size() == 2, "【作者】那行补上的作者收进来（与题录行重复也只两个人）");
        check(ABSTRACT_A.equals(a.abstractText), "【摘要】整段进摘要");
        check(a.keywords.size() == 3, "【关键词】三个词分开收");
        check("10.16520/j.issn.1002-8331.2021.03.015".equals(a.doi), "【DOI】进 doi");
        check(a.body().indexOf("合肥工业大学") < 0, "【作者单位】不进可比文本：它是谁的单位与查重无关");
        check(a.body().indexOf("自然科学基金") < 0, "【基金】不进可比文本");
        check(parsed.strayLines == 1, "没有字段名又接不上摘要的行数出来，不硬塞给谁，got " + parsed.strayLines);
        check(parsed.withAbstract() == 2, "两篇都带摘要");
    }

    // ---- 7. 编码 ----

    private static void encodingSniffing() {
        byte[] utf8 = bytes(NOTE_EXPRESS);
        byte[] bom = concat(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF }, utf8);
        byte[] gbk;
        try {
            gbk = NOTE_EXPRESS.replace("\r\n", "\n").getBytes(Charset.forName("GB18030"));
        } catch (Exception error) {
            throw new AssertionError("本机没有 GB18030，测不了编码嗅探");
        }
        RecordImport.Parsed plain = RecordImport.parse(utf8, "a.txt");
        RecordImport.Parsed withBom = RecordImport.parse(bom, "a.txt");
        RecordImport.Parsed ansi = RecordImport.parse(gbk, "a.txt");
        check("UTF-8 带 BOM".equals(withBom.charset), "带 BOM 的读法要说出来：" + withBom.charset);
        check(withBom.records.size() == 3 && TITLE_A.equals(withBom.records.get(0).title),
                "BOM 没混进第一篇的题名");
        check("GB18030".equals(ansi.charset), "ANSI(GBK) 那份严格 UTF-8 解不动，退到 GB18030：" + ansi.charset);
        check(ansi.records.size() == 3, "GBK 那份同样解析出三篇，got " + ansi.records.size());
        check(TITLE_A.equals(ansi.records.get(0).title), "GBK 那份的题名与 UTF-8 那份逐字相同");
        check(ABSTRACT_A.equals(ansi.records.get(0).abstractText), "GBK 那份的摘要也逐字相同");
        check(ansi.bodyChars() == plain.bodyChars(), "两份编码不同的同一个内容，可比字数一样："
                + ansi.bodyChars());
    }
    // ---- 8. 坏记录不拖垮整份文件 ----

    private static void brokenRecords() {
        check(RecordImport.parse(new byte[0], "empty.txt").records.isEmpty(), "空文件解析出 0 篇，不抛异常");
        check(RecordImport.parse(null, "null.txt").records.isEmpty(), "content 为 null 也不抛");
        check(RecordImport.parse(new byte[0], "empty.txt").notes.size() == 1, "空文件留下一句说明");

        String messy = NOTE_EXPRESS
                + "%0 JournalArticle\r\n%A  nobody\r\n%J 一本刊\r\n"     // 没有 %T
                + "%0 JournalArticle\r\n%A 赵七\r\n%T 又一篇能认出来的题录\r\n%X 这一篇的摘要有足够长的一句话，用来确认坏记录没影响它。\r\n";
        RecordImport.Parsed parsed = RecordImport.parse(bytes(messy), "messy.txt");
        check(parsed.records.size() == 4, "四条里丢掉没有题名的一条，剩下三条照常入库，got " + parsed.records.size());
        check(parsed.blankTitle == 1, "没有题名的那条数进 blankTitle，got " + parsed.blankTitle);
        RecordImport.Record last = parsed.records.get(3);
        check("又一篇能认出来的题录".equals(last.title) && last.abstractText.startsWith("这一篇的摘要"),
                "坏记录后面那条照常读出题名与摘要");

        // 同一篇在一份文件里出现两次：解析出两条，入库时按正文哈希只进一条。
        String twice = NOTE_EXPRESS + NOTE_EXPRESS;
        RecordImport.Parsed dup = RecordImport.parse(bytes(twice), "twice.txt");
        check(dup.records.size() == 6, "同一篇导出两遍，解析阶段照数 6 条（去重是入库那一步的事）");
    }

    private static void unrecognisableFormat() {
        String prose = "本节先介绍研究背景。近年来，随着城市排水管网的老化，内涝风险不断上升，"
                + "多地开展了管网普查与改造工作。本文以某市老城区为对象，讨论改造方案的比选方法。\r\n"
                + "第二节讨论水力模型的选择。\r\n";
        RecordImport.Parsed parsed = RecordImport.parse(bytes(prose), "note.txt");
        check(parsed.format.length() == 0, "一篇普通散文认不出题录格式，got " + parsed.format);
        check(parsed.records.isEmpty(), "认不出格式就一篇都不许进库，got " + parsed.records.size());
        check(parsed.notes.size() == 1 && parsed.notes.get(0).contains("认不出"), "说明里写清为什么");
        check(parsed.summary().indexOf("NoteExpress") >= 0 && parsed.summary().indexOf("BibTeX") >= 0,
                "认不出时把支持的五种写法报给用户：" + parsed.summary());

        String halfTagged = "%A 只有作者没有类型行\r\n%T 一个题名\r\n";
        RecordImport.Parsed loose = RecordImport.parse(bytes(halfTagged), "loose.txt");
        check(RecordImport.FORMAT_NOTEEXPRESS.equals(loose.format) || RecordImport.FORMAT_REFWORKS.equals(loose.format),
                "省掉 %0 的标签式导出照样认作标签式，got " + loose.format);
        check(loose.records.size() == 1 && "一个题名".equals(loose.records.get(0).title),
                "省掉类型行的那一条也能入库");
    }

    // ---- 9. 该走题录那条路，还是整份当文档进库 ----

    private static void routingDecides() {
        // 标签式四种写法没有歧义，文件名是 .txt 也照走题录那条路。
        check(RecordImport.prefersRecords(bytes(NOTE_EXPRESS), "知网导出.txt",
                RecordImport.parse(bytes(NOTE_EXPRESS), "知网导出.txt")), "NoteExpress 的 .txt 走题录");
        check(RecordImport.prefersRecords(bytes(BIBTEX), "cnki.bib", RecordImport.parse(bytes(BIBTEX), "cnki.bib")),
                ".bib 走题录");
        check(RecordImport.prefersRecords(bytes(RIS), "cnki.ris", RecordImport.parse(bytes(RIS), "cnki.ris")),
                ".ris 走题录");
        check(RecordImport.prefersRecords(bytes(PLAIN_CITES), "题录.txt",
                RecordImport.parse(bytes(PLAIN_CITES), "题录.txt")), "整份都是题录行的 .txt 走题录");

        // 用户的论文正文自己也带一段参考文献表：把它劈成几十条只选题名的记录是实打实的材料损失。
        StringBuilder thesis = new StringBuilder();
        for (int i = 0; i < 26; i++)
            thesis.append("第 ").append(i + 1).append(" 节正文：这一节讨论改造方案的比选，"
                    + "先给结论，再给水力模型的率定过程与三条支管的复核结果。\r\n");
        for (int i = 0; i < 4; i++)
            thesis.append("[").append(i + 1).append("]张三.参考文献一条题名[J].某学报,2020,")
                    .append(i + 1).append("(2):30-40.\r\n");
        RecordImport.Parsed asThesis = RecordImport.parse(bytes(thesis.toString()), "我的论文.txt");
        check(asThesis.records.size() == 4, "正文里那四条参考文献条目被认出来了，got " + asThesis.records.size());
        check(!RecordImport.prefersRecords(bytes(thesis.toString()), "我的论文.txt", asThesis),
                "但这份文件仍按文档整份入库：30 行里只有 4 行是题录，不能让参考文献表决定整份文件的命运");
        check(asThesis.lines == 30, "非空行数数得准（30 行），got " + asThesis.lines);

        // 反过来：一份从头到尾都是参考文献表的文件，走题录才对。
        StringBuilder refs = new StringBuilder();
        for (int i = 0; i < 6; i++)
            refs.append("[").append(i + 1).append("]李四.另一条参考文献题名[J].另一学报,2019,")
                    .append(i).append("(3):1-8.\r\n");
        check(RecordImport.prefersRecords(bytes(refs.toString()), "参考文献.txt",
                RecordImport.parse(bytes(refs.toString()), "参考文献.txt")), "整份都是参考文献表：走题录");

        // 明确不是题录的容器一律不抢。
        check(!RecordImport.prefersRecords(bytes(NOTE_EXPRESS), "文档.docx",
                RecordImport.parse(bytes(NOTE_EXPRESS), "文档.docx")), ".docx 永远整份当文档");
        check(!RecordImport.prefersRecords(bytes(PLAIN_CITES), "扫描.pdf",
                RecordImport.parse(bytes(PLAIN_CITES), "扫描.pdf")), ".pdf 同理");
        String prose = "这只是一段散文，没有任何题录记号。\r\n";
        check(!RecordImport.prefersRecords(bytes(prose), "散文.txt", RecordImport.parse(bytes(prose), "散文.txt")),
                "认不出格式的文件不动原来的路");
    }

    // ---- 10. 真链路：题录 → 自建库 → 语料 → 命中 ----

    private static void intoLibraryAndMatch() throws Exception {
        File dir = new File(ROOT, "library");
        dir.mkdirs();
        LocalLibrary library = new LocalLibrary(dir);
        RecordImport.Parsed parsed = RecordImport.parse(bytes(NOTE_EXPRESS), "cnki-noteexpress.txt");
        CorpusImport.Batch batch = RecordImport.into(library, parsed, null, null);
        check(batch.total == 3 && batch.imported() == 3, "三篇题录全部入库：" + batch.summary());
        check(library.size() == 3, "每篇单独成篇（不是整份文件并成一篇），库里 3 条");
        check(batch.importedChars() > ABSTRACT_A.length() * 2,
                "入库可比字数 " + batch.importedChars() + " 字，摘要真的进了材料");
        for (int i = 0; i < batch.receipts.size(); i++)
            check(batch.receipts.get(i).storedName.endsWith(".txt"),
                    "回执上的落库文件名带着题名：" + batch.receipts.get(i).storedName);

        // 同一份导出再导一次：按正文哈希全部撞车，一条都不许多进。
        CorpusImport.Batch again = RecordImport.into(library, parsed, null, null);
        check(again.imported() == 0 && again.duplicates() == 3, "重导同一份导出：3 篇全判重复——" + again.summary());

        // 题名相同、摘要不同：两条都要进，靠 uniqueName 加序号。
        String twins = "%0 JournalArticle\r\n%T 同名不同摘要的一篇\r\n%X 第一段摘要讲的是扩散焊的界面反应层。\r\n"
                + "%0 JournalArticle\r\n%T 同名不同摘要的一篇\r\n%X 第二段摘要讲的是排水管网的水力模型率定。\r\n";
        CorpusImport.Batch twinBatch = RecordImport.into(library, RecordImport.parse(bytes(twins), "twins.txt"),
                null, null);
        check(twinBatch.imported() == 2, "题名相同、摘要不同的两条都进了库，got " + twinBatch.summary());
        check(library.size() == 5, "库里累计 5 条，got " + library.size());

        // 档位要能穿过索引持久化：重开一个库实例，题录级仍然是题录级。
        LocalLibrary reopened = new LocalLibrary(dir);
        ArrayList<LocalLibrary.Entry> entries = reopened.entries();
        int records = 0;
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).record) records++;
        check(records == 5, "重开库后 5 条仍带题录级标记，got " + records);
        library.addDocument("普通原文.txt", bytes("这是一份导进自建库的原文，正文级材料不该被降权。".repeat(12)),
                null, false);
        LocalLibrary third = new LocalLibrary(dir);
        boolean plainIsFull = false;
        for (int i = 0; i < third.entries().size(); i++)
            if (third.entries().get(i).name.equals("普通原文.txt")) plainIsFull = !third.entries().get(i).record;
        check(third.size() == 6 && plainIsFull, "导进来的原文不算题录级：它带的是正文");

        // 索引 → 语料：档位跟着 Source 走，命中才降得动权。
        TextCorpus corpus = new TextCorpus();
        reopened.index(corpus);
        check(corpus.sourceCount() == 5, "五篇题录进了比对语料，got " + corpus.sourceCount());
        String planted = "先用一致性正则约束无标注样本，再用边界注意力模块细化分割边缘。";
        String draft = "本研究的现场部分集中在老城区排水管网改造 one 带。施工前的普查发现，管段接错与淤积同时存在，"
                + "水力模型的率定因此分成两步。第一步以旱季流量校核管段阻力系数，第二步以设计暴雨复核峰值流量。"
                + planted
                + "此后我们又对三条支管做了复核，结论与主段的判断一致，改造顺序按汇水面积重排即可。";
        TextCorpus.Report matched = corpus.match(draft, null);
        check(!matched.hits.isEmpty(), "抄自题录摘要的那一句被检出，命中 " + matched.hits.size() + " 处");
        boolean attributed = false;
        boolean abstractLevel = false;
        for (int i = 0; i < matched.hits.size(); i++) {
            TextCorpus.Hit hit = matched.hits.get(i);
            if (hit.source == null) continue;
            if (TITLE_A.equals(hit.source.title)) attributed = true;
            if (DuplicateEngine.MATERIAL_ABSTRACT.equals(hit.source.material)) abstractLevel = true;
        }
        check(attributed, "命中署到那一篇的题名上（每篇单独成篇换来的就是这件事）");
        check(abstractLevel, "这条命中带着摘要级档位：它不能和正文级命中混成一个数");
    }

    // ---- helpers ----

    // ---- 编码：UTF-16 的导出（记事本"Unicode"）----

    private static void utf16AndTruncation() {
        RecordImport.Parsed le = RecordImport.parse(
                withBom(bytesAs(NOTE_EXPRESS, "UTF-16LE"), new byte[] { (byte) 0xFF, (byte) 0xFE }),
                "cnki-utf16le.txt");
        check(RecordImport.FORMAT_NOTEEXPRESS.equals(le.format),
                "UTF-16LE 带 BOM 的导出照样认得出写法，got " + le.format);
        check(le.charset.startsWith("UTF-16LE"), "charset 说的是 UTF-16LE，got " + le.charset);
        check(le.records.size() == 3 && TITLE_A.equals(le.records.get(0).title),
                "UTF-16LE 读出来的题名里没有 NUL（" + le.records.size() + " 篇）");

        RecordImport.Parsed be = RecordImport.parse(
                withBom(bytesAs(NOTE_EXPRESS, "UTF-16BE"), new byte[] { (byte) 0xFE, (byte) 0xFF }),
                "cnki-utf16be.txt");
        check(be.records.size() == 3 && TITLE_A.equals(be.records.get(0).title),
                "UTF-16BE 同样读得出（" + be.records.size() + " 篇，" + be.charset + "）");

        // 没写 BOM 的 UTF-16LE：按 UTF-8 严格解会"成功"，得到一串夹 NUL 的乱码——这一条必须被拦下。
        RecordImport.Parsed bare = RecordImport.parse(bytesAs(NOTE_EXPRESS, "UTF-16LE"), "cnki-noBom.txt");
        check(bare.records.size() == 3 && TITLE_A.equals(bare.records.get(0).title),
                "没有 BOM 的 UTF-16LE 不许被当成 UTF-8 读成乱码（" + bare.records.size()
                        + " 篇，charset=" + bare.charset + "）");
        check(bare.charset.startsWith("UTF-16LE"), "这一份的 charset 要说清是按 UTF-16LE 读的，got " + bare.charset);

        // 截断不许把一对代理劈开。
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 19999; i++) big.append('中');
        big.append('\uDB40\uDD00').append("尾字");
        RecordImport.Record longOne = new RecordImport.Record();
        longOne.abstractText = big.toString();
        longOne.title = "超长摘要的一篇";
        String body = longOne.body();
        check(body.length() <= RecordImport.MAX_RECORD_CHARS,
                "可比文本不超过上限（" + body.length() + " <= " + RecordImport.MAX_RECORD_CHARS + "）");
        check(!Character.isSurrogate(body.charAt(body.length() - 1)),
                "截在一对代理中间时退回整对，末尾不许留半个字符");
    }

    private static byte[] withBom(byte[] body, byte[] bom) {
        byte[] out = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(body, 0, out, bom.length, body.length);
        return out;
    }

    private static byte[] bytesAs(String text, String charset) {
        try {
            return text.getBytes(charset);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static byte[] bytes(String text) {
        try {
            return text.getBytes("UTF-8");
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static byte[] concat(byte[] head, byte[] tail) {
        byte[] out = new byte[head.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
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