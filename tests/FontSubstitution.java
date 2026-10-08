package com.rikkahub.wordlite;

import java.awt.Font;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 字体名的落点。存在的理由很具体：用户论文的标题是华文新魏，界面上一直是宋体，而旧的断言写着
 * "华文新魏就该是宋体"——字库文件早就删了，断言把损失洗成了正确。现在随包带的是桌面 Word 在 Windows
 * 上用的那几张原脸（华文新魏=STXINWEI、隶书=SIMLI、华文行楷=STXINGKA……由 tools/build-fonts.py
 * 从本机字库子集化而来，只删字形、不动任何度量）。这里钉住四件事：认得出的名字落到真的在包里的文件；
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
                        && DocxFontAssets.substitution("微软雅黑").contains("黑体")
                        && DocxFontAssets.substitution("方正清刻本悦宋").contains("宋体"),
                "真的没有本尊的必须点名：" + DocxFontAssets.substitution("MS Mincho")
                        + " / " + DocxFontAssets.substitution("微软雅黑"));

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
        /* 子集只保留 GB2312 + 假名 + 稿子里真用到的字（见 tools/build-fonts.py）。
           所以这里测假名和拉丁；日文旧字体（漢 这类）本来就不在包里，落到系统字体。 */
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
        System.out.println("SUMMARY " + checks + " font-substitution assertions passed; " + summary);
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