"""Word's own ink width vs ours, on lines Word did not stretch.

The paragraph-final line of a justified document is ragged: Word leaves it at its natural
width. So (x1 - x0) in Word's PDF is the price Word paid for exactly those glyphs, and the
device's lineInkPx is the price we paid. Same characters, two prices, no break rule involved.
That is the cleanest available read on "are we drawing wider than Word", which is what the
one-character-per-line break disagreements look like.

  py tools/width-vs-word.py -Capture artifacts/agent-layout-verify/<tag>
"""
import argparse, csv, io, re, statistics
from collections import defaultdict


def norm(s):
    return re.sub(r'\s+', '', s)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('-Capture', required=True)
    ap.add_argument('-Truth', default='artifacts/agent-layout-verify/word-pdf-lines.tsv')
    ap.add_argument('-Top', type=int, default=25)
    a = ap.parse_args()

    word = list(csv.DictReader(io.open(a.Truth, encoding='utf-8'), delimiter='\t'))
    dev = list(csv.DictReader(io.open(a.Capture + '/new/lines-all.tsv', encoding='utf-8'),
                             delimiter='\t'))
    by_block = defaultdict(list)
    for r in dev:
        by_block[int(r['paragraph'])].append(r)
    for k in by_block:
        by_block[k].sort(key=lambda r: int(r['line']))

    # A device line and a Word line are the same line when they open on the same 8 characters.
    # Only the FIRST such line per device block is taken, so a repeated phrase cannot pair twice.
    pairs = []
    for w in word:
        wt = norm(w['text'])
        if len(wt) < 10:
            continue
        for blk in sorted(by_block):
            hit = None
            for li, l in enumerate(by_block[blk]):
                if norm(l['lineText'])[:8] == wt[:8] and len(norm(l['lineText'])) == len(wt):
                    hit = (li, l)
                    break
            if hit:
                pairs.append((blk, hit[0], wt, hit[1],
                              (float(w['x1']) - float(w['x0'])) * 96 / 72))
                break

    rows = []
    for blk, li, wt, l, wpx in pairs:
        ours = float(l['lineInkPx'] or l['lineNaturalPx'])
        stretch = float(l['lineStretchPx'] or 0)
        if stretch > 1.0:          # Word stretched it too, so the width is not a price
            continue
        if abs(ours - wpx) > 60:   # a mis-pairing, not a width difference
            continue
        rows.append((ours - wpx, blk, li, wpx, ours, l['lineChars'], wt))

    rows.sort(key=lambda r: -abs(r[0]))
    print('pairs of identical lines Word did not stretch: %d' % len(rows))
    print('%-6s %-5s %9s %8s %+8s  %s' % ('block', 'line', 'word_px', 'our_px', 'diff', 'text'))
    for d, blk, li, wpx, ours, chars, wt in rows[:a.Top]:
        print('%-6s %-5s %9.1f %8.1f %+8.1f  %s' % (blk, li, wpx, ours, d, wt[:30]))
    d = [r[0] for r in rows]
    if d:
        print('median %+.2f px   mean %+.2f px   ours wider on %d of %d lines'
              % (statistics.median(d), statistics.mean(d),
                 sum(1 for x in d if x > 0.5), len(d)))
        print('per character: median %+.4f px over %s chars/line'
              % (statistics.median([r[0] / max(1, int(r[5])) for r in rows]),
                 statistics.median([int(r[5]) for r in rows])))


main()
