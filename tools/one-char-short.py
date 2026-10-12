"""The lines where Word fit one character more than we did: what was the shortfall?

For a device line whose text is a strict prefix of a Word line (Word kept going where we
broke), the question is arithmetic: our natural width plus the price of the character Word
still fitted, against the room the column gave that line. The answer says whether we lost by
a hair (a price question) or by a whole character (a capacity or start-position question).

  py tools/one-char-short.py -Capture artifacts/agent-layout-verify/<tag>
"""
import argparse, csv, io, re
from collections import defaultdict


def norm(s):
    return re.sub(r'\s+', '', s)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('-Capture', required=True)
    ap.add_argument('-Truth', default='artifacts/agent-layout-verify/word-pdf-lines.tsv')
    a = ap.parse_args()
    word = list(csv.DictReader(io.open(a.Truth, encoding='utf-8'), delimiter='\t'))
    dev = list(csv.DictReader(io.open(a.Capture + '/new/lines-all.tsv', encoding='utf-8'),
                             delimiter='\t'))
    wnorm = [(norm(w['text']), w) for w in word if len(norm(w['text'])) > 4]

    out = []
    for l in dev:
        t = norm(l['lineText'])
        if len(t) < 6 or int(l['lineChars']) <= 1:
            continue
        for wt, w in wnorm:
            if len(wt) <= len(t) or not wt.startswith(t):
                continue
            extra = len(wt) - len(t)
            if extra > 6:
                continue
            room = float(l['lineRoomPx'])
            nxt = float(l['nextCharPx'])
            short = nxt - room                     # px we were missing for that character
            out.append((abs(short), l, w, extra, room, nxt, short, t))
            break

    print('device lines that are a strict prefix of a Word line: %d' % len(out))
    print('%-6s %-5s %5s %8s %8s %8s %8s  %s' %
          ('block', 'line', 'extra', 'room', 'nextpx', 'short', 'x0pt', 'text'))
    for _, l, w, extra, room, nxt, short, t in sorted(out, key=lambda r: -abs(r[6]))[:30]:
        print('%-6s %-5s %5s %8.1f %8.1f %+8.1f %8s  %s' %
              (l['paragraph'], l['line'], extra, room, nxt, short, w['x0'], t[:28]))
    shorts = [r[6] for r in out]
    under = [s for s in shorts if s <= 2.0]
    print('shortfall <= 2 px: %d of %d ; <= 8 px: %d ; > 8 px: %d'
          % (len(under), len(shorts), sum(1 for s in shorts if s <= 8),
             sum(1 for s in shorts if s > 8)))


main()
