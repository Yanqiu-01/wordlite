"""Word's own seam width at every script boundary, straight from its PDF.

The phone charges a quarter em (4 px at 12 pt) wherever Chinese text touches Latin, and the
same again where it touches a digit, because the document turns on both autoSpaceDE and
autoSpaceDN. Only one of those two is proven by Word's PDF in this document, so this measures
both: on a line Word did NOT justify, the step from one glyph origin to the next minus the
natural advance of the left glyph is exactly the gap Word put there.

  py tools/seam-boundary-truth.py [-Pdf artifacts/agent-typeset/pdf-truth/input-liu.pdf]

CJK advance in this document is exactly 1.0000 em (autogap-truth.py), so the left glyph's own
width is known and the gap falls out without any font on this machine.
"""
import argparse, collections, re
import pymupdf

PUNCT = set('\u3000\u3001\u3002\uff01\uff08\uff09\uff0c\uff1a\uff1b\uff1f\u201c\u201d'
            '\u2018\u2019\u300a\u300b\u3010\u3011\uff3b\uff3d\u2014\u2026\u00b7\uff5e')


def kind(ch):
    o = ord(ch)
    if 0x3041 <= o <= 0x30ff or 0x4e00 <= o <= 0x9fff or 0x3000 <= o <= 0x303f             or 0xff00 <= o <= 0xffef:
        return 'cjkp' if ch in PUNCT else 'cjk'
    if ch.isalpha():
        return 'lat'
    if ch.isdigit():
        return 'dig'
    return 'oth'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('-Pdf', default='artifacts/agent-typeset/pdf-truth/input-liu.pdf')
    ap.add_argument('-Size', type=float, default=12.0)
    ap.add_argument('-RaggedUnder', type=float, default=380.0,
                    help='only lines whose ink ends before this x (pt): Word left them unstretched')
    a = ap.parse_args()
    doc = pymupdf.open(a.Pdf)
    gaps = collections.defaultdict(list)
    nlines = 0
    for page in doc:
        d = page.get_text('rawdict')
        for blk in d['blocks']:
            for ln in blk.get('lines', []):
                chars = [dict(c, size=s.get('size', 0.0))
                         for s in ln['spans'] for c in s.get('chars', [])]
                chars = [c for c in chars if c['c'].strip()]
                if len(chars) < 3:
                    continue
                x0 = min(c['bbox'][0] for c in chars)
                x1 = max(c['bbox'][2] for c in chars)
                if x1 > a.RaggedUnder:
                    continue          # justified: the stretch would be counted as a gap
                if not any(abs(c['size'] - a.Size) < 0.3 for c in chars):
                    continue
                nlines += 1
                for i in range(len(chars) - 1):
                    l, r = chars[i], chars[i + 1]
                    if l['origin'][1] != r['origin'][1]:
                        continue      # a superscript steps, that is another rule
                    step = (r['origin'][0] - l['origin'][0]) * 96 / 72
                    own = (l['bbox'][2] - l['bbox'][0]) * 96 / 72
                    if kind(l['c']) == 'cjk':
                        own = a.Size * 96 / 72      # bbox of a wide glyph is ink, not advance
                    gap = step - own
                    if gap < -2 or gap > 12:
                        continue
                    gaps['%s|%s' % (kind(l['c']), kind(r['c']))].append(gap)

    print('pdf=%s  ragged %s pt lines used: %d' % (a.Pdf, a.Size, nlines))
    print('%-12s %5s %9s %9s %9s %9s' % ('boundary', 'n', 'median', 'mean', 'p10', 'p90'))
    def q(v, p):
        v = sorted(v)
        return v[min(len(v) - 1, int(p * (len(v) - 1)))]
    for k in sorted(gaps, key=lambda k: -len(gaps[k])):
        v = gaps[k]
        if len(v) < 3:
            continue
        import statistics
        print('%-12s %5d %9.3f %9.3f %9.3f %9.3f'
              % (k, len(v), statistics.median(v), statistics.mean(v), q(v, .10), q(v, .90)))
    print()
    print('boundaries with fewer than 3 samples: %s'
          % ', '.join('%s=%d' % (k, len(v)) for k, v in sorted(gaps.items()) if len(v) < 3))


main()
