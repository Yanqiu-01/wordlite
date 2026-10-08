'''PDF 取字真值台（主机侧，PyMuPDF 口径）。

对每份 PDF 报三样：
1) PyMuPDF 能读出的正文字符数（去掉空白后的字符数，和 app 侧 PdfExtractProbe 同口径）；
2) 这些字落在哪些字体上，各自占多少；
3) 每个 Type0 字体有没有 ToUnicode、有没有内嵌字体文件、CIDSystemInfo 报的是哪套字符集。
第 3 项用来把"码到字的对照表不在这份 PDF 里"的那部分字数出来：这类字只能靠随包的 Adobe-GB1 表补，
改前改后都拿它当分母，别拿整篇字数当分母。
命令：py tools/pdf-truth.py <pdf 路径>...
'''
import sys, re, collections
import pymupdf as fitz


def short(base):
    """span 里的字体名会丢掉六位子集前缀：FIFKOG+STKaiti 与 STKaiti 是同一张脸。"""
    return base.split("+")[-1].strip()


def font_facts(doc, xref):
    obj = doc.xref_object(xref, compressed=True) or ""
    has_tu = "/ToUnicode" in obj
    ordering, embedded = "n/a", "/FontFile2" in obj or "/FontFile3" in obj or "/FontFile" in obj
    m = re.search(r"/DescendantFonts\s*\[?\s*(\d+)\s+0\s+R", obj)
    if m:
        desc = doc.xref_object(int(m.group(1)), compressed=True) or ""
        embedded = embedded or "/FontFile2" in desc or "/FontFile3" in desc or "/FontFile" in desc
        csi = re.search(r"/CIDSystemInfo\s*<<(.*?)>>", desc, re.S)
        if csi:
            o = re.search(r"/Ordering\s*\(([^)]*)\)", csi.group(1))
            ordering = o.group(1) if o else "?"
    return has_tu, ordering, embedded


def report(path):
    doc = fitz.open(path)
    per_font = collections.Counter()
    total = 0
    for page in doc:
        for block in page.get_text("rawdict")["blocks"]:
            for line in block.get("lines", []):
                for span in line.get("spans", []):
                    n = sum(1 for c in span["chars"] if not c["c"].isspace())
                    per_font[short(span["font"])] += n
                    total += n
    facts = {}
    for i in range(doc.page_count):
        for f in doc.get_page_fonts(i, full=True):
            key = short(f[3])
            if key not in facts:
                facts[key] = font_facts(doc, f[0])
    naked = sum(n for base, n in per_font.items()
                if facts.get(base, (True, "", True))[0] is False and facts.get(base, (True, "", True))[2] is False)
    gb1 = sum(n for base, n in per_font.items()
              if facts.get(base, (True, "", True)) == (False, "GB1", False))
    print("TRUTH %-22s pages=%-3d fitz_chars=%-6d 无对照表的字=%d (%.1f%%)，其中 Ordering=GB1 可补=%d (%.1f%%)"
          % (path.split("/")[-1], doc.page_count, total, naked, 100.0 * naked / total if total else 0,
             gb1, 100.0 * gb1 / total if total else 0))
    for base, n in per_font.most_common():
        has_tu, ordering, embedded = facts.get(base, (False, "?", False))
        flag = "" if has_tu else "  <-- 无ToUnicode(内嵌=%s, Ordering=%s)" % (embedded, ordering)
        print("      %-30s %6d%s" % (base, n, flag))


for p in sys.argv[1:]:
    report(p)
