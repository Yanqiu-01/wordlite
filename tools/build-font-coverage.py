import io, os, re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "fonts")
TABLE = os.path.join(ROOT, "app", "src", "main", "java", "com", "rikkahub", "wordlite", "DocxFontAssets.java")
OUT = os.path.join(ROOT, "app", "src", "main", "java", "com", "rikkahub", "wordlite", "DocxFontCoverage.java")
OVERRIDES = os.path.join(ROOT, "tools", "font-coverage-awt.json")

USAGE = """把每张随包字库带了多少字量出来，写死成 DocxFontCoverage.java。

    py tools/build-font-coverage.py            # 重新生成
    py tools/build-font-coverage.py --check    # 只核对：文件与实际字库不一致就退出 1

为什么要有这个文件：字体面板里那句"这张脸带了多少汉字"是给用户看的，不能靠人往代码里手抄，
也不能靠 docs 里的一段话。数由这里从 app/src/main/assets/fonts 逐码位量出来：只认渲染器真正读的
那张 Unicode 映射（getBestCmap，也就是 Android 与 java.awt.Font 读的那一张），并且该码位必须指向
一个真的有笔画的字形。把 cmap 的所有子表并起来数会多出 15,021 个画不出东西的空映射（谚文、C1 控制符、
泰卢固文那几段），那种数法测出来的"缺字"是假的。

界面那句数与 java.awt 的实测必须一致：tests/FontSubstitution 第 12 段逐张脸用 canDisplay 复量一遍。
万一两个口径真的对不上（不同 JDK 读 cmap 的边角情况），把 java.awt 的数记进
tools/font-coverage-awt.json（{"fonts/xxx.ttf": 20990}），以界面用的那个口径为准。
"""
import sys, json
from fontTools.ttLib import TTFont, TTCollection


def load(path):
    with open(path, "rb") as fh:
        if fh.read(4) == b"ttcf":
            return TTCollection(path, lazy=True).fonts[0]
    return TTFont(path, lazy=True)


def measure(path):
    font = load(path)
    cmap = dict(font.getBestCmap() or {})
    names = set(font.getGlyphSet().keys())
    real = set(cp for cp, glyph in cmap.items() if glyph in names)
    hanzi = sum(1 for cp in real if 0x4E00 <= cp <= 0x9FFF)
    return hanzi, len(real)


def render():
    table = io.open(TABLE, encoding="utf-8").read()
    faces = re.findall(r'public static final String (\w+) = "(fonts/[^"]+)";', table)
    overrides = {}
    if os.path.isfile(OVERRIDES):
        overrides = json.load(io.open(OVERRIDES, encoding="utf-8"))
    rows = []
    for const, rel in faces:
        path = os.path.join(ASSETS, os.path.basename(rel))
        if not os.path.isfile(path):
            raise SystemExit("字库文件不在：%s（%s）" % (rel, path))
        hanzi, total = measure(path)
        if rel in overrides:
            hanzi = int(overrides[rel])
        rows.append((const, rel, hanzi, total))
    print("%-30s %-16s %-8s %s" % ("constant", "asset", "hanzi", "glyphs"))
    for const, rel, hanzi, total in rows:
        print("%-30s %-16s %-8d %d" % ("DocxFontAssets." + const, rel, hanzi, total))

    def block(method, doc, index):
        out = ["    /** %s */" % doc,
               "    public static int %s(String path) {" % method,
               "        if (path == null) return 0;"]
        for const, rel, hanzi, total in rows:
            out.append("        if (DocxFontAssets.%s.equals(path)) return %d;"
                       % (const, hanzi if index == 0 else total))
        out += ["        return 0;", "    }"]
        return "\n".join(out)

    return (
        "package com.rikkahub.wordlite;\n\n"
        "/**\n"
        " * 每张随包字库到底带了多少字。这份文件由 <py tools/build-font-coverage.py> 从\n"
        " * app/src/main/assets/fonts 逐码位量出来后写死，不要手改（改了也会被下一次生成冲掉，\n"
        " * 而 tools/build-font-coverage.py --check 会先红）。字体面板里那句\u201c多少个汉字\u201d念的就是\n"
        " * 这里的数，tests/FontSubstitution 第 12 段会拿 java.awt 的 canDisplay 逐张脸复量一遍。\n"
        " */\n"
        "public final class DocxFontCoverage {\n"
        "    private DocxFontCoverage() { }\n\n"
        + block("hanzi", u"基本汉字区（U+4E00-U+9FFF）画得出笔画的字数。", 0) + "\n\n"
        + block("glyphs", u"Unicode 映射里指向一个真有笔画的字形的码位总数（含拉丁、假名、标点）。", 1) + "\n\n"
        "    /** 面板里那一行小字：汉字多的脸先报汉字数，纯拉丁的脸只报总字数。 */\n"
        "    public static String detail(String path) {\n"
        "        int h = hanzi(path), total = glyphs(path);\n"
        "        if (path == null || total == 0) return \"\";\n"
        "        return h > 0 ? group(h) + \" \u4e2a\u6c49\u5b57 \u00b7 \u5171 \" + group(total) + \" \u5b57\"\n"
        "                : \"\u5171 \" + group(total) + \" \u5b57\";\n"
        "    }\n\n"
        "    /** 6763 \u2192 \"6,763\"\uff1b\u9762\u677f\u91cc\u7684\u6570\u8981\u80fd\u4e00\u773c\u770b\u51fa\u591a\u5c11\u3002 */\n"
        "    static String group(int value) {\n"
        "        String digits = Integer.toString(value);\n"
        "        StringBuilder out = new StringBuilder(digits.length() + 4);\n"
        "        int head = digits.length() % 3;\n"
        "        for (int i = 0; i < digits.length(); i++) {\n"
        "            if (i > 0 && (i - head) % 3 == 0) out.append(',');\n"
        "            out.append(digits.charAt(i));\n"
        "        }\n"
        "        return out.toString();\n"
        "    }\n"
        "}\n")


text = render()
if "--check" in sys.argv:
    have = io.open(OUT, encoding="utf-8").read() if os.path.isfile(OUT) else ""
    if have != text:
        print("--check: DocxFontCoverage.java \u4e0e\u5b9e\u9645\u5b57\u5e93\u5bf9\u4e0d\u4e0a\uff0c\u91cd\u65b0\u751f\u6210\uff1apy tools/build-font-coverage.py",
              file=sys.stderr)
        sys.exit(1)
    print("--check ok: DocxFontCoverage.java \u4e0e\u968f\u5305\u5b57\u5e93\u4e00\u81f4")
    sys.exit(0)

tmp = OUT + ".new"
io.open(tmp, "w", encoding="utf-8", newline="\n").write(text)
back = io.open(tmp, encoding="utf-8").read()
if back != text or chr(0) in back:
    raise SystemExit("write verify failed")
os.replace(tmp, OUT)
print("wrote %s (%d chars)" % (OUT, len(text)))
