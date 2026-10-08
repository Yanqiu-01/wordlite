package com.rikkahub.wordlite;

import java.awt.Font;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 字体名的落点。存在的理由很具体：用户论文的标题是华文新魏，界面上一直是宋体，而旧的断言写着
 * "华文新魏就该是宋体"——字库文件早就删了，断言把损失洗成了正确。现在随包带的是桌面 Word 在 Windows
 * 上用的那几张原脸（华文新魏=STXINWEI、隶书=SIMLI、华文行楷=STXINGKA……由 tools/build-fonts.py
 * 从本机字库建库，带的是每张脸本机就有的全部字形，度量一字不动）。这里钉住四件事：认得出的名字落到真的在包里的文件；
 * 本尊在包里的名字不许谎称替代；本尊不在的必须说出换成了什么；每张新脸真的画得出这篇稿子用到的字。
 */
public final class FontSubstitution {
    /** 样稿封面用华文新魏写的那两行。 */
    private static final String COVER_TITLE = "哈尔滨工业大学深圳校区毕业论文（设计）开题报告";
    /** 样稿封面用隶书写的那一行。 */
    private static final String COVER_LISHU = "毕业设计";
    private static int checks;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        File sample = new File(args.length > 0 ? args[0] : "tests/samples/input-liu.docx");
        if (!sample.isFile()) throw new IllegalStateException("FontSubstitution: missing sample " + sample);
        Set<String> shipped = new HashSet<String>(Arrays.asList(DocxFontAssets.PATHS));

        // 1. 字体会话里能选到的每一个名字，都必须落到一个真的在包里的文件。
        for (String name : DocxFontAssets.PICKER) {
            String path = DocxFontAssets.pathFor(name);
            check(path != null && shipped.contains(path),
                    "字体选项「" + name + "」落到随包文件：" + path);
        }
        check(DocxFontAssets.PICKER.length >= 20, "字体对话框列出 " + DocxFontAssets.PICKER.length + " 项");

        // 2. 用户稿子里的每一个字体名都拿到自己的脸。
        check(DocxFontAssets.ST_XINWEI.equals(DocxFontAssets.pathFor("华文新魏"))
                        && DocxFontAssets.ST_XINWEI.equals(DocxFontAssets.pathFor("STXinwei")),
                "华文新魏 → 华文新魏本尊");
        check(!DocxFontAssets.SONG.equals(DocxFontAssets.pathFor("华文新魏")),
                "华文新魏不许再落到宋体：这就是用户看到的那个 bug");
        check(DocxFontAssets.LI_SU.equals(DocxFontAssets.pathFor("隶书")), "隶书 → 隶书本尊");
        check(DocxFontAssets.ST_LITI.equals(DocxFontAssets.pathFor("华文隶书")), "华文隶书 → 华文隶书本尊");
        check(DocxFontAssets.ST_XINGKAI.equals(DocxFontAssets.pathFor("华文行楷")), "华文行楷 → 本尊");
        check(DocxFontAssets.ST_KAITI.equals(DocxFontAssets.pathFor("华文楷体")), "华文楷体 → 本尊");
        check(DocxFontAssets.ST_FANGSONG.equals(DocxFontAssets.pathFor("华文仿宋")), "华文仿宋 → 本尊");
        check(DocxFontAssets.ST_XIHEI.equals(DocxFontAssets.pathFor("华文细黑")), "华文细黑 → 本尊");
        check(DocxFontAssets.ST_SONG.equals(DocxFontAssets.pathFor("华文宋体"))
                        && DocxFontAssets.ST_ZHONGSONG.equals(DocxFontAssets.pathFor("华文中宋")),
                "华文宋体 / 华文中宋 → 本尊");
        check(DocxFontAssets.DENG_XIAN.equals(DocxFontAssets.pathFor("等线"))
                        && DocxFontAssets.YOU_YUAN.equals(DocxFontAssets.pathFor("幼圆")),
                "等线 / 幼圆 → 本尊（Word 默认主题字体不能再靠宋体顶）");
        check(DocxFontAssets.SONG.equals(DocxFontAssets.pathFor("宋体"))
                        && DocxFontAssets.HEI.equals(DocxFontAssets.pathFor("黑体"))
                        && DocxFontAssets.KAI.equals(DocxFontAssets.pathFor("楷体"))
                        && DocxFontAssets.FANG.equals(DocxFontAssets.pathFor("仿宋")),
                "宋黑楷仿照旧是自己的脸，没被替代表动到");

        // 3. 等宽与日文：以前 Courier 直接落到"没有文件"，日文名带全角字符匹配不上。
        check(DocxFontAssets.COURIER_NEW.equals(DocxFontAssets.pathFor("Courier"))
                        && DocxFontAssets.COURIER_NEW.equals(DocxFontAssets.pathFor("Courier New"))
                        && DocxFontAssets.CONSOLAS.equals(DocxFontAssets.pathFor("Consolas")),
                "Courier / Courier New 落到 Courier New，Consolas 落到 Consolas");
        check(DocxFontAssets.MS_GOTHIC.equals(DocxFontAssets.pathFor("ＭＳ ゴシック"))
                        && DocxFontAssets.MS_GOTHIC.equals(DocxFontAssets.pathFor("MS Gothic")),
                "全角与半角写的 ＭＳ ゴシック 都落到 MS Gothic 本尊");
        check(DocxFontAssets.SONG.equals(DocxFontAssets.pathFor("ＭＳ 明朝"))
                        && DocxFontAssets.SONG.equals(DocxFontAssets.pathFor("MS Mincho")),
                "ＭＳ 明朝 落宋体（本机就没有 mincho 字库）");

        // 4. 认不出的名字：中文名落到宋体，拉丁名交给系统字体（这一路没重测过，保持原样）。
        check(DocxFontAssets.pathFor("NonexistentFont123") == null
                        && DocxFontAssets.pathFor("") == null,
                "认不出的拉丁名照旧交给系统字体");
        check(DocxFontAssets.SONG.equals(DocxFontAssets.pathFor("方正清刻本悦宋")),
                "字库里没有的中文名落到宋体");

        // 5. 替代必须说出来，而且只有真的没有本尊时才许说。
        check(DocxFontAssets.substitution("宋体").isEmpty()
                        && DocxFontAssets.substitution("楷体").isEmpty()
                        && DocxFontAssets.substitution("Times New Roman").isEmpty(),
                "用的是本尊就不许谎称替代");
        check(DocxFontAssets.substitution("华文新魏").isEmpty()
                        && DocxFontAssets.substitution("隶书").isEmpty()
                        && DocxFontAssets.substitution("华文行楷").isEmpty()
                        && DocxFontAssets.substitution("等线").isEmpty(),
                "华文新魏/隶书/华文行楷/等线现在都有本尊，不许再报替代："
                        + DocxFontAssets.substitution("华文新魏") + DocxFontAssets.substitution("隶书"));
        check(DocxFontAssets.substitution("MS Mincho").contains("宋体")
                        && DocxFontAssets.substitution("方正清刻本悦宋").contains("宋体"),
                "真的没有本尊的必须点名：" + DocxFontAssets.substitution("MS Mincho"));
        check(DocxFontAssets.substitution("MS Gothic").isEmpty(),
                "MS Gothic 只是ＭＳ ゴシック的另一种写法，不是替代："
                        + DocxFontAssets.substitution("MS Gothic"));
        check(DocxFontAssets.substitution("微软雅黑").isEmpty(),
                "微软雅黑有自己的字库了，不许再报替代：" + DocxFontAssets.substitution("微软雅黑"));

        /* 汉字覆盖。名字里的生僻字（赟 镕 頔 这类只在本级字库之外的）以前会掉到系统字体上，
           桌面 Word 用本机字库画得出来，我们就必须画得出来。见 tools/build-fonts.py。 */
        String rare = "赟镕頔昺甯昇玥龘靐齾爨鑫燚妤翊胤珩瑀旻顼弢赓翀荻蕰犇骉鱻馥淼垚芈鄠迠玊";
        needGlyphs(DocxFontAssets.MS_YAHEI, rare, "微软雅黑");
        needGlyphs(DocxFontAssets.DENG_XIAN, rare, "等线");
        needGlyphs(DocxFontAssets.LI_SU, rare, "隶书");
        needGlyphs(DocxFontAssets.YOU_YUAN, rare, "幼圆");
        needGlyphs(DocxFontAssets.ST_SONG, rare, "华文宋体");
        needGlyphs(DocxFontAssets.ST_ZHONGSONG, rare, "华文中宋");
        needGlyphs(DocxFontAssets.ST_KAITI, rare, "华文楷体");
        needGlyphs(DocxFontAssets.ST_FANGSONG, rare, "华文仿宋");
        needGlyphs(DocxFontAssets.ST_XIHEI, rare, "华文细黑");
        /* 每张脸的字形数不许再缩回 GB2312：缩一次就有一批人名用字换张脸画。
           地板按本机字库实测取，最低的那张（华文新魏 7819 字）本来就只有这么多。 */
        long[] floors = { 3_500_000L, 3_500_000L, 3_500_000L, 8_500_000L, 6_000_000L,
                14_000_000L, 17_000_000L, 6_000_000L, 10_500_000L, 11_000_000L,
                11_500_000L, 10_000_000L, 9_000_000L };
        String[] floored = { DocxFontAssets.ST_XINWEI, DocxFontAssets.ST_LITI, DocxFontAssets.ST_XINGKAI,
                DocxFontAssets.LI_SU, DocxFontAssets.YOU_YUAN, DocxFontAssets.DENG_XIAN,
                DocxFontAssets.MS_YAHEI, DocxFontAssets.MS_GOTHIC, DocxFontAssets.ST_SONG,
                DocxFontAssets.ST_ZHONGSONG, DocxFontAssets.ST_KAITI, DocxFontAssets.ST_FANGSONG,
                DocxFontAssets.ST_XIHEI };
        for (int i = 0; i < floored.length; i++) {
            long bytes = new File("app/src/main/assets/" + floored[i]).length();
            check(bytes >= floors[i], floored[i] + " 的字形数不许缩水（" + bytes + " < " + floors[i] + "）");
        }

        // 6. 每张脸真的画得出这篇稿子用到的字——不然换了等于没换。
        needGlyphs(DocxFontAssets.ST_XINWEI, COVER_TITLE, "华文新魏");
        needGlyphs(DocxFontAssets.LI_SU, COVER_LISHU, "隶书");
        needGlyphs(DocxFontAssets.SONG, COVER_TITLE, "宋体");
        needGlyphs(DocxFontAssets.HEI, COVER_TITLE, "黑体");
        needGlyphs(DocxFontAssets.ST_KAITI, COVER_TITLE, "华文楷体");
        needGlyphs(DocxFontAssets.ST_FANGSONG, COVER_TITLE, "华文仿宋");
        needGlyphs(DocxFontAssets.ST_XIHEI, COVER_TITLE, "华文细黑");
        needGlyphs(DocxFontAssets.DENG_XIAN, COVER_TITLE, "等线");
        needGlyphs(DocxFontAssets.YOU_YUAN, COVER_TITLE, "幼圆");
        needGlyphs(DocxFontAssets.ST_ZHONGSONG, COVER_TITLE, "华文中宋");
        /* 随包的假名/拉丁照测（见 tools/build-fonts.py：每张脸带的是本机字库的全部覆盖）。
           日文旧字体（漢 这类）ＭＳ ゴシック 本机字库就没有，落到系统字体。 */
        needGlyphs(DocxFontAssets.MS_GOTHIC, "テストゴシックあいう012", "ＭＳ ゴシック");
        Font courier = Font.createFont(Font.TRUETYPE_FONT,
                new File("app/src/main/assets/" + DocxFontAssets.COURIER_NEW));
        check(courier.canDisplay('A') && courier.canDisplay('0'), "Courier New 能画拉丁与数字");
        for (String path : DocxFontAssets.PATHS) {
            File f = new File("app/src/main/assets/" + path);
            check(f.isFile() && f.length() > 4096, path + " 这个文件真的在仓库里");
        }

        // 7. 这份清单要能从真文档里读出来，并进对话框。
        DocxDocument document;
        try (InputStream input = new FileInputStream(sample)) {
            document = DocxParser.parse(input, sample.getName());
        }
        ArrayList<DocxFonts.Used> used = DocxFonts.usedIn(document);
        check(!used.isEmpty(), "样稿里读到 " + used.size() + " 种声明的字体");
        DocxFonts.Used xinwei = null;
        for (int i = 0; i < used.size(); i++) if ("华文新魏".equals(used.get(i).family)) xinwei = used.get(i);
        check(xinwei != null, "样稿标题那两行的华文新魏被认出来了");
        check(xinwei != null && xinwei.runs > 0 && xinwei.note.isEmpty(),
                "它有本尊，所以不该带替换说明：" + (xinwei == null ? "" : xinwei.note));
        String summary = DocxFonts.summary(used);
        check(summary.contains("华文新魏→华文新魏"), "对话框那一行说得清：" + summary);
        /* 8. 中文字体名一个都不许落到画不出汉字的拉丁字库上。"标题的华文新魏没了"就是这么来的：
              字库文件被删，名字照样解析成功，只是落到一张只会画拉丁的字库上，界面一声不响。 */
        String[] cjkNames = { "宋体", "黑体", "楷体", "仿宋", "新宋体", "等线", "等线 Light", "微软雅黑",
                "幼圆", "隶书", "华文新魏", "华文隶书", "华文行楷", "华文楷体", "华文仿宋", "华文细黑",
                "华文宋体", "华文中宋", "方正小标宋简体", "方正清刻本悦宋", "仿宋_GB2312", "楷体_GB2312",
                "SimSun", "SimHei", "ＭＳ ゴシック", "ＭＳ 明朝", "MS Mincho", "MS Mincho Regular", "明朝" };
        Set<String> latinOnly = new HashSet<String>(Arrays.asList(
                DocxFontAssets.TIMES, DocxFontAssets.TIMES_BOLD, DocxFontAssets.TIMES_ITALIC,
                DocxFontAssets.TIMES_BOLD_ITALIC, DocxFontAssets.ARIAL, DocxFontAssets.ARIAL_BOLD,
                DocxFontAssets.CALIBRI, DocxFontAssets.CAMBRIA, DocxFontAssets.COURIER_NEW,
                DocxFontAssets.CONSOLAS, DocxFontAssets.MATH));
        for (String name : cjkNames) {
            String path = DocxFontAssets.pathFor(name);
            check(path != null && !latinOnly.contains(path), "中文/日文字体名不许落到拉丁字库：" + name + " -> " + path);
        }

        /* 9. 宋体是最后一道防线——所有认不出的中文名都落到它身上，所以它一个码位都不许少。
              仓库里那一份曾经比本机 simsun.ttc 少 43 个字（笔画 ㇐-㇥、部件描述符 ⿼-⿿），
              桌面 Word 画得出来，手机画不出来。tools/build-fonts.py 现在把它一起重建。 */
        needGlyphs(DocxFontAssets.SONG, "\u31d0\u31d1\u31d2\u31d3\u31d4\u31d5\u31d6\u31d7\u31d8"
                + "\u31d9\u31da\u31db\u31dc\u31dd\u31de\u31df\u31e0\u31e1\u31e2\u31e3\u31e4"
                + "\u31e5\u31ef\u2ffc\u2ffd\u2ffe\u2fff", "宋体（笔画与部件描述符）");
        long[] systemFloors = { 11_500_000L, 9_200_000L, 11_000_000L, 10_000_000L };
        String[] systemFaces = { DocxFontAssets.SONG, DocxFontAssets.HEI, DocxFontAssets.KAI, DocxFontAssets.FANG };
        for (int i = 0; i < systemFaces.length; i++) {
            long bytes = new File("app/src/main/assets/" + systemFaces[i]).length();
            check(bytes >= systemFloors[i],
                    systemFaces[i] + " 的字形数不许缩水（" + bytes + " < " + systemFloors[i] + "）");
        }

        /* 10. 整篇稿子逐字对账：文档里每个字体名声明要画的那些字，落点那张脸必须全部画得出来。
              这就是"华文新魏那一行到底画成什么样"的可量化说法，也是唯一能挡住"以后又少字"的断言。 */
        Map<String, String> chosen = new HashMap<String, String>();
        Map<String, StringBuilder> wanted = new HashMap<String, StringBuilder>();
        collectDeclaredGlyphs(sample, chosen, wanted);
        int auditedFaces = 0, distinctGlyphs = 0, declaredChars = 0;
        StringBuilder cannot = new StringBuilder();
        Map<String, Font> faces = new HashMap<String, Font>();
        for (Map.Entry<String, StringBuilder> entry : wanted.entrySet()) {
            String family = entry.getKey();
            String path = chosen.get(family);
            String text = unique(entry.getValue().toString());
            if (path == null) {
                cannot.append("[").append(family).append(" -> 没有随包文件]").append(text).append(" ");
                continue;
            }
            Font face = faces.get(path);
            if (face == null) {
                face = Font.createFont(Font.TRUETYPE_FONT,
                        new File("app/src/main/assets/" + path)).deriveFont(20f);
                faces.put(path, face);
            }
            auditedFaces++;
            declaredChars += entry.getValue().length();
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                distinctGlyphs++;
                if (!face.canDisplay(cp)) cannot.append("[").append(family).append(" 画不出 ").appendCodePoint(cp).append("] ");
            }
        }
        check(auditedFaces >= 4 && declaredChars > 13_000 && distinctGlyphs > 900,
                "样稿逐字对账跑了 " + auditedFaces + " 张脸 / " + declaredChars
                        + " 个位置 / " + distinctGlyphs + " 个不同的字");
        check(cannot.length() == 0, "样稿里每一个声明了的字体名都画得出它要画的字，缺字：" + cannot);

        System.out.println("SUMMARY " + checks + " font-substitution assertions passed; " + summary);
    }

    /** 文档里每个字体名声明了什么字，就去那个字体名下收集：中日韩与假名只认 eastAsia，其余认 ascii。 */
    private static void collectDeclaredGlyphs(File docx, Map<String, String> chosen,
                                              Map<String, StringBuilder> wanted) throws Exception {
        Pattern run = Pattern.compile("<w:r[ >].*?</w:r>", Pattern.DOTALL);
        Pattern fonts = Pattern.compile("<w:rFonts\\b([^>]*)>");
        Pattern text = Pattern.compile("<w:t\\b[^>]*>(.*?)</w:t>", Pattern.DOTALL);
        ZipFile zip = new ZipFile(docx);
        java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!name.startsWith("word/") || !name.endsWith(".xml")) continue;
            InputStream input = zip.getInputStream(entry);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = input.read(buffer)) > 0) bytes.write(buffer, 0, read);
            input.close();
            String xml = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            Matcher m = run.matcher(xml);
            while (m.find()) {
                String block = m.group();
                String ascii = null, eastAsia = null;
                Matcher f = fonts.matcher(block);
                if (f.find()) {
                    String attrs = f.group(1);
                    ascii = attribute(attrs, "w:ascii");
                    eastAsia = attribute(attrs, "w:eastAsia");
                }
                Matcher t = text.matcher(block);
                while (t.find()) {
                    String body = t.group(1).replaceAll("&[a-z]+;", "").replaceAll("&#?[0-9]+;", "");
                    for (int i = 0; i < body.length(); ) {
                        int cp = body.codePointAt(i);
                        i += Character.charCount(cp);
                        if (cp <= 0x20) continue;
                        boolean cjk = cp > 0x2E7F;
                        String family = cjk ? eastAsia : (ascii != null ? ascii : eastAsia);
                        if (family == null || family.isEmpty()) family = cjk ? "宋体" : "Times New Roman";
                        family = family.replaceAll("&amp;", "&");
                        String path = DocxFontAssets.pathFor(family);
                        if (!cjk) continue;               // 拉丁字符走的是系统字体那一路，这轮不判它
                        if (!chosen.containsKey(family)) chosen.put(family, path);
                        StringBuilder sb = wanted.get(family);
                        if (sb == null) { sb = new StringBuilder(); wanted.put(family, sb); }
                        sb.appendCodePoint(cp);
                    }
                }
            }
        }
        zip.close();
    }

    private static String attribute(String attrs, String name) {
        Matcher m = Pattern.compile(Pattern.quote(name) + "=\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : null;
    }

    private static String unique(String text) {
        LinkedHashSet<Integer> seen = new LinkedHashSet<Integer>();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (seen.add(Integer.valueOf(cp))) out.appendCodePoint(cp);
        }
        return out.toString();
    }

    /** 一张脸必须能画出这些字，缺一个就报出来。 */
    private static void needGlyphs(String path, String text, String who) throws Exception {
        Font face = Font.createFont(Font.TRUETYPE_FONT,
                new File("app/src/main/assets/" + path)).deriveFont(20f);
        LinkedHashSet<Character> need = new LinkedHashSet<Character>();
        for (int i = 0; i < text.length(); i++) need.add(Character.valueOf(text.charAt(i)));
        StringBuilder missing = new StringBuilder();
        for (Character c : need) if (!face.canDisplay(c.charValue())) missing.append(c.charValue());
        check(missing.length() == 0, who + " 覆盖 " + need.size() + " 个测试字"
                + (missing.length() == 0 ? "" : "，缺：" + missing));
    }
}