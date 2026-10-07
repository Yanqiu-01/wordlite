package com.rikkahub.wordlite;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Heuristic checks aimed at common Chinese thesis templates, not a university standard. */
public final class DocxChecker {
    public static final class Profile {
        public boolean useCommonChineseThesisBaseline = true;
        public String expectedFont = "宋体";
        public int expectedSizeHalfPoints = 24; // 12 pt, often called 小四
        public int expectedLineSpacingTwips = 360; // 1.5 lines when Word uses 240 = single
        public boolean checkLineSpacing;
        public boolean checkFirstLineIndent;
        public int expectedFirstLineIndentTwips = 480; // roughly two Chinese characters at 12 pt

        public Profile copy() {
            Profile copy = new Profile();
            copy.useCommonChineseThesisBaseline = useCommonChineseThesisBaseline;
            copy.expectedFont = expectedFont;
            copy.expectedSizeHalfPoints = expectedSizeHalfPoints;
            copy.expectedLineSpacingTwips = expectedLineSpacingTwips;
            copy.checkLineSpacing = checkLineSpacing;
            copy.checkFirstLineIndent = checkFirstLineIndent;
            copy.expectedFirstLineIndentTwips = expectedFirstLineIndentTwips;
            return copy;
        }
    }

    private DocxChecker() { }

    public static ArrayList<DocxDocument.FormatIssue> check(DocxDocument document, Profile profile) {
        ArrayList<DocxDocument.FormatIssue> issues = document.issues;
        issues.clear();
        if (profile == null) profile = new Profile();
        final ArrayList<DocxDocument.ParagraphBlock> body = new ArrayList<DocxDocument.ParagraphBlock>();
        HashMap<Integer, Integer> sizeCounts = new HashMap<Integer, Integer>();
        HashMap<String, Integer> fontCounts = new HashMap<String, Integer>();

        for (DocxDocument.ParagraphBlock paragraph : document.paragraphs) {
            if (!isBodyParagraph(paragraph)) continue;
            body.add(paragraph);
            for (DocxDocument.Run run : paragraph.runs) {
                if (run.text.trim().length() == 0) continue;
                if (run.style.fontSizeHalfPoints > 0) increment(sizeCounts, run.style.fontSizeHalfPoints);
                if (run.style.fontFamily != null && run.style.fontFamily.length() > 0) {
                    increment(fontCounts, normalizeFont(run.style.fontFamily));
                }
            }
        }

        int dominantSize = dominantInt(sizeCounts);
        String dominantFont = dominantString(fontCounts);
        int consecutiveEmpty = 0;
        for (int i = 0; i < document.paragraphs.size(); i++) {
            DocxDocument.ParagraphBlock paragraph = document.paragraphs.get(i);
            if (paragraph.isEmpty()) {
                consecutiveEmpty++;
                if (consecutiveEmpty >= 2) {
                    add(issues, DocxDocument.FormatIssue.WARNING, paragraph.index, "连续空段落",
                            "第 " + (i + 1) + " 个段落为空，可能造成论文版面出现异常空白。建议用段落间距代替空格段落。");
                }
            } else {
                consecutiveEmpty = 0;
            }
        }

        for (DocxDocument.ParagraphBlock paragraph : body) {
            Set<Integer> paragraphSizes = new HashSet<Integer>();
            Set<String> paragraphFonts = new HashSet<String>();
            boolean hasLeadingSpaces = false;
            String visible = paragraph.text == null ? "" : paragraph.text;
            if (visible.startsWith(" ") || visible.startsWith("\t") || visible.startsWith("　")) hasLeadingSpaces = true;
            for (DocxDocument.Run run : paragraph.runs) {
                if (run.text.trim().length() == 0) continue;
                if (run.style.fontSizeHalfPoints > 0) paragraphSizes.add(run.style.fontSizeHalfPoints);
                if (run.style.fontFamily != null && run.style.fontFamily.length() > 0) {
                    paragraphFonts.add(normalizeFont(run.style.fontFamily));
                }
            }
            if (paragraphSizes.size() > 1) {
                add(issues, DocxDocument.FormatIssue.WARNING, paragraph.index, "段落内字号混用",
                        location(paragraph) + "同时出现 " + paragraphSizes.size() + " 种字号，建议统一正文格式。");
            }
            if (paragraphFonts.size() > 1) {
                add(issues, DocxDocument.FormatIssue.WARNING, paragraph.index, "段落内字体混用",
                        location(paragraph) + "同时出现多种字体；中文、英文和数字可按学校模板分别设置，但请确认不是误操作。");
            }
            int size = paragraph.firstSizeHalfPoints();
            if (dominantSize > 0 && size > 0 && size != dominantSize && !paragraph.isHeading
                    && (!profile.useCommonChineseThesisBaseline || dominantSize != profile.expectedSizeHalfPoints)) {
                add(issues, DocxDocument.FormatIssue.WARNING, paragraph.index, "正文主字号不一致",
                        location(paragraph) + "使用 " + formatPt(size) + "，正文多数内容为 " + formatPt(dominantSize) + "。");
            }
            if (hasLeadingSpaces) {
                add(issues, DocxDocument.FormatIssue.INFO, paragraph.index, "段首含空格或制表符",
                        location(paragraph) + "检测到段首空格/Tab。论文首行缩进应优先使用段落缩进设置。");
            }
            if (paragraph.format.pageBreakBeforeSet && paragraph.format.pageBreakBefore) {
                add(issues, DocxDocument.FormatIssue.INFO, paragraph.index, "段落强制分页",
                        location(paragraph) + "设置了段前分页，检查它是否会造成标题孤立。");
            }
            if (profile.useCommonChineseThesisBaseline) {
                checkBaseline(paragraph, profile, issues);
            }
        }

        if (profile.useCommonChineseThesisBaseline) {
            checkSection(document, issues);
        }
        if (document.hasTrackedChanges) {
            add(issues, DocxDocument.FormatIssue.WARNING, -1, "文档包含修订标记",
                    "检测到插入/删除修订。当前查看器按最终可见文本阅读，提交前请在 Word 中确认是否全部接受或拒绝修订。");
        }
        if (document.hasMacros) {
            add(issues, DocxDocument.FormatIssue.INFO, -1, "文档包含宏部件",
                    "检测到 VBA 宏部件。查看器不会执行宏，也不会修改原文件。");
        }
        if (document.hasHeaderFooter) {
            add(issues, DocxDocument.FormatIssue.INFO, -1, "页眉/页脚已参与页面预览",
                    "已按各 section 的页边距、页眉线、页脚距离和 PAGE 字段绘制；脚注/尾注仍不参与正文分页。");
        }
        if (document.hasFootnotes || document.hasEndnotes) {
            add(issues, DocxDocument.FormatIssue.INFO, -1, "脚注/尾注需单独复核",
                    "文档包含脚注或尾注部件；正文预览保留主体内容，但不会模拟 Word 的分页注释布局。");
        }
        if (document.hasComments) {
            add(issues, DocxDocument.FormatIssue.INFO, -1, "文档包含批注",
                    "批注不会显示在正文预览中，请在 Word 中确认批注是否已经处理完毕。");
        }
        if (document.hasUnsupportedDrawing) {
            add(issues, DocxDocument.FormatIssue.INFO, -1, "存在未还原的绘图对象",
                    "文档可能包含复杂绘图、SmartArt 或浮动对象；当前版本优先保证正文、表格和常规图片可读。");
        }
        if (dominantFont.length() > 0 && profile.useCommonChineseThesisBaseline) {
            // This is deliberately one document-level hint rather than one warning per run.
            if (!fontLooksLike(profile.expectedFont, dominantFont)) {
                add(issues, DocxDocument.FormatIssue.INFO, -1, "正文主字体提示",
                        "检测到正文主字体为“" + dominantFont + "”，当前基准为“" + profile.expectedFont + "”。若学校模板不同，请在规则中关闭该基准。");
            }
        }
        if (issues.isEmpty()) {
            add(issues, DocxDocument.FormatIssue.INFO, -1, "暂未发现明显问题",
                    "已完成结构化预检。视觉分页、目录域、浮动对象和学校专用模板仍建议用 Word 最终复核。");
        }
        return issues;
    }

    private static void checkBaseline(DocxDocument.ParagraphBlock paragraph, Profile profile,
                                      ArrayList<DocxDocument.FormatIssue> issues) {
        if (paragraph.isHeading || paragraph.text.trim().length() == 0) return;
        int size = paragraph.firstSizeHalfPoints();
        if (size > 0 && profile.expectedSizeHalfPoints > 0 && size != profile.expectedSizeHalfPoints) {
            add(issues, DocxDocument.FormatIssue.WARNING, paragraph.index, "不符合当前字号基准",
                    location(paragraph) + "为 " + formatPt(size) + "，常用中文论文基准为 " + formatPt(profile.expectedSizeHalfPoints) + "。");
        }
        if (profile.checkLineSpacing && paragraph.format.lineSpacingTwips > 0
                && Math.abs(paragraph.format.lineSpacingTwips - profile.expectedLineSpacingTwips) > 24) {
            add(issues, DocxDocument.FormatIssue.WARNING, paragraph.index, "行距偏离当前基准",
                    location(paragraph) + "显式设置为约 " + paragraph.format.lineSpacingTwips + " twips，基准为 "
                            + profile.expectedLineSpacingTwips + " twips。不同 Word 模板的行距规则可能不同。");
        }
        if (profile.checkFirstLineIndent && paragraph.format.firstLineIndentTwips != -1
                && Math.abs(paragraph.format.firstLineIndentTwips - profile.expectedFirstLineIndentTwips) > 80) {
            add(issues, DocxDocument.FormatIssue.WARNING, paragraph.index, "首行缩进偏离当前基准",
                    location(paragraph) + "为 " + paragraph.format.firstLineIndentTwips + " twips，基准为 "
                            + profile.expectedFirstLineIndentTwips + " twips。");
        }
        if (profile.expectedFont != null && profile.expectedFont.trim().length() > 0) {
            for (DocxDocument.Run run : paragraph.runs) {
                if (run.text.trim().length() == 0 || run.style.fontFamily == null || run.style.fontFamily.length() == 0) continue;
                if (containsChinese(run.text) && !fontLooksLike(profile.expectedFont, run.style.fontFamily)) {
                    add(issues, DocxDocument.FormatIssue.INFO, paragraph.index, "中文字体与当前基准不同",
                            location(paragraph) + "检测到“" + run.style.fontFamily + "”，当前基准为“" + profile.expectedFont + "”。");
                    break;
                }
            }
        }
    }

    private static void checkSection(DocxDocument document, ArrayList<DocxDocument.FormatIssue> issues) {
        DocxDocument.SectionSettings section = document.section;
        if (section.pageWidthTwips > 0 && section.pageHeightTwips > 0) {
            int width = Math.min(section.pageWidthTwips, section.pageHeightTwips);
            int height = Math.max(section.pageWidthTwips, section.pageHeightTwips);
            if (Math.abs(width - 11906) > 120 || Math.abs(height - 16838) > 160) {
                add(issues, DocxDocument.FormatIssue.WARNING, -1, "页面尺寸不是常见 A4",
                        "检测到约 " + twipsToCm(width) + " × " + twipsToCm(height) + " cm。请按学校模板确认是否应使用 A4。");
            }
        }
        if (section.marginLeftTwips > 0 && section.marginRightTwips > 0
                && section.marginTopTwips > 0 && section.marginBottomTwips > 0) {
            boolean closeToOneInch = close(section.marginLeftTwips, 1440, 150)
                    && close(section.marginRightTwips, 1440, 150)
                    && close(section.marginTopTwips, 1440, 150)
                    && close(section.marginBottomTwips, 1440, 150);
            if (!closeToOneInch) {
                add(issues, DocxDocument.FormatIssue.INFO, -1, "页边距需要核对",
                        "当前页边距约为左 " + twipsToCm(section.marginLeftTwips) + "、右 "
                                + twipsToCm(section.marginRightTwips) + "、上 " + twipsToCm(section.marginTopTwips)
                                + "、下 " + twipsToCm(section.marginBottomTwips) + " cm；查看学校模板要求。");
            }
        }
    }

    private static boolean isBodyParagraph(DocxDocument.ParagraphBlock paragraph) {
        if (paragraph == null || paragraph.text == null || paragraph.text.trim().length() == 0) return false;
        if (paragraph.isHeading) return false;
        String style = (paragraph.styleId + " " + paragraph.styleName).toLowerCase(Locale.US);
        return !style.contains("caption") && !style.contains("题注") && !style.contains("toc");
    }

    private static String location(DocxDocument.ParagraphBlock paragraph) {
        return "第 " + (paragraph.index + 1) + " 个内容段落";
    }

    private static String formatPt(int halfPoints) {
        if (halfPoints % 2 == 0) return (halfPoints / 2) + " pt";
        return String.format(Locale.US, "%.1f pt", halfPoints / 2.0f);
    }

    private static String twipsToCm(int twips) {
        return String.format(Locale.US, "%.2f", twips / 1440.0 * 2.54);
    }

    private static boolean close(int value, int target, int tolerance) {
        return Math.abs(value - target) <= tolerance;
    }

    private static boolean containsChinese(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 0x3400 && c <= 0x9FFF) return true;
        }
        return false;
    }

    private static String normalizeFont(String font) {
        return font == null ? "" : font.trim().toLowerCase(Locale.US);
    }

    private static boolean fontLooksLike(String expected, String actual) {
        String e = normalizeFont(expected);
        String a = normalizeFont(actual);
        if (e.length() == 0 || a.length() == 0) return true;
        if (a.contains(e) || e.contains(a)) return true;
        if ((e.contains("宋体") || e.contains("simsun")) && (a.contains("宋体") || a.contains("simsun"))) return true;
        if ((e.contains("黑体") || e.contains("simhei")) && (a.contains("黑体") || a.contains("simhei"))) return true;
        return false;
    }

    private static void increment(Map<Integer, Integer> map, int key) {
        Integer value = map.get(key);
        map.put(key, value == null ? 1 : value + 1);
    }

    private static void increment(Map<String, Integer> map, String key) {
        Integer value = map.get(key);
        map.put(key, value == null ? 1 : value + 1);
    }

    private static int dominantInt(Map<Integer, Integer> map) {
        int best = -1;
        int count = 0;
        for (Map.Entry<Integer, Integer> entry : map.entrySet()) {
            if (entry.getValue() > count) { best = entry.getKey(); count = entry.getValue(); }
        }
        return best;
    }

    private static String dominantString(Map<String, Integer> map) {
        String best = "";
        int count = 0;
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            if (entry.getValue() > count) { best = entry.getKey(); count = entry.getValue(); }
        }
        return best;
    }

    private static void add(ArrayList<DocxDocument.FormatIssue> list, int severity, int blockIndex,
                            String title, String detail) {
        if (list.size() >= 120) return;
        for (DocxDocument.FormatIssue issue : list) {
            if (issue.blockIndex == blockIndex && issue.title.equals(title)) return;
        }
        list.add(new DocxDocument.FormatIssue(severity, blockIndex, title, detail));
    }
}
