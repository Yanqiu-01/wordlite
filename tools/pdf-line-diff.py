#!/usr/bin/env python3
# -*- coding: utf-8 -*-
'''PyMuPDF 有、app 没有的行——逐行找出来，别拿一个总字数差糊过去。

配套的量法（两边都用同一份 PDF，谁也不多谁也不少）：
  1) 先把 app 自己解出来的字倒出来：
     java -Dfile.encoding=UTF-8 -classpath "artifacts/build/host-test-classes;artifacts/build/host-classes;tools/android-35.jar" `
          com.rikkahub.wordlite.PdfExtractProbe -cmap app/src/main/assets/cmaps/adobe-gb1.cid -dump artifacts/tmp/pdf-dump <pdf 路径...>
  2) 再逐行比：
     py tools/pdf-line-diff.py <pdf 路径...> --dump artifacts/tmp/pdf-dump

判定口径：把整篇正文的空白全部去掉后按"这一段在不在里面"算，所以换行、缩进、断行位置差都算不上差异。
需要 pymupdf（pip install pymupdf）；它在这里只当对照尺子，不进包、不参与产品逻辑。
'''
import collections
import os
import sys

def nospace(text):
    return "".join(ch for ch in text if not ch.isspace())

def main(argv):
    dump = "artifacts/tmp/pdf-dump"
    pdfs = []
    i = 0
    while i < len(argv):
        if argv[i] == "--dump":
            dump = argv[i + 1]; i += 2; continue
        pdfs.append(argv[i]); i += 1
    if not pdfs:
        print(__doc__); return 2
    try:
        import pymupdf
    except ImportError:
        print("没装 pymupdf，这份对照量不了：pip install pymupdf"); return 2
    for pdf in pdfs:
        name = os.path.join(dump, os.path.basename(pdf) + ".txt")
        if not os.path.exists(name):
            print("跳过 %s：找不到 %s（先跑 PdfExtractProbe -dump）" % (pdf, name)); continue
        app = nospace(open(name, encoding="utf-8").read())
        doc = pymupdf.open(pdf)
        ref = nospace("".join(page.get_text() for page in doc))
        missing = collections.Counter(ref) - collections.Counter(app)
        print("%s  PyMuPDF 非空白字=%d  app=%d  差=%d  app 独有的字符数=%d"
              % (os.path.basename(pdf), len(ref), len(app), len(ref) - len(app),
                 sum((collections.Counter(app) - collections.Counter(ref)).values())))
        if missing:
            print("   差的码位（前 20）: " + ", ".join(
                "U+%04X %s x%d" % (ord(ch), ch, n) for ch, n in missing.most_common(20)))
        found = 0
        for pno, page in enumerate(doc):
            for block in page.get_text("dict")["blocks"]:
                for line in block.get("lines", []):
                    text = "".join(span["text"] for span in line["spans"])
                    key = nospace(text)
                    if key and key not in app:
                        found += 1
                        if found <= 12:
                            d = line["dir"]
                            print("   页%-3d dir=(%.2f,%.2f) 这一段 app 里没有: %r"
                                  % (pno + 1, d[0], d[1], text[:70]))
        print("   app 缺的行数=%d" % found)
    return 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
