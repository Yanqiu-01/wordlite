# 段落落在第几页：Word 与我们的差值直方图。
#
# 这把尺是补出来的，不是原来那把。原来量页归属的是 artifacts/word/pages.tsv（Word 自己报的每段
# 起页）加上 artifacts/agent-typeset/ 下的 run-repaginate.ps1 / line-height-rows.ps1，三样都没进
# 过版本库，2026-10-11 清理 artifacts/ 时一起没了，回收站里也找不到生产者脚本。所以在 Word 导出
# 的 PDF 真值（tools/export-word-pdf.ps1 生成，tools/verify-pdf-truth.py 验货）上重搭了这一把：
# 读 break-agreement.py 的逐段配对结果，比 word_page 与 device_page。
#
# 口径差别要讲清楚：它只看得见 PDF 配对覆盖到的段落（参考稿上是 104 段），不是 word-parity 那把
# 206 段的全量尺，两个数不可并列引用。它够回答那一类投诉——"我的内容比 Word 早一页/晚一页"，
# 差值直方图里 -1 的个数就是"早一页"的段数。
#
# 用法：py tools/page-mismatch-ruler.py <break-agreement.tsv> [<另一个标签的同一份文件> ...]
import csv, collections, sys

def mism(p):
    d = {}
    with open(p, encoding='utf-8-sig', newline='') as f:
        for r in csv.DictReader(f, delimiter='\t'):
            k = (int(r['word_para']), int(r['device_block']))
            wp, dp = int(r['word_page']), int(r['device_page'])
            d.setdefault(k, (wp, dp))
    return d, {k: v for k, v in d.items() if v[0] != v[1]}

for p in sys.argv[1:] or ['artifacts/agent-layout-verify/head0426f64/break-agreement.tsv']:
    allp, bad = mism(p)
    hist = collections.Counter(v[1] - v[0] for v in allp.values())
    print('%s: paragraphs=%d  page mismatches=%d  delta histogram %s'
          % (p, len(allp), len(bad), dict(sorted(hist.items()))))
    for k, v in sorted(bad.items()):
        print('    word_para=%d device_block=%d word_page=%d our_page=%d delta=%+d'
              % (k[0], k[1], v[0], v[1], v[1] - v[0]))
