#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/midword-audit.py -- count the lines where the phone cut a Latin or digit token in half.

Why: Word's own exported PDF of tests/samples/input-liu.docx has 803 text lines and cuts a
[A-Za-z0-9] run on none of them (27 lines end at '-', 3 end at '/' with a blank after it), so every
such cut on the phone is a defect the user can see -- "Cu/SB/P-Cu/SB/C" | "u 夹层结构" on page 8.

Counting them from a device capture needs the source text: the capture drops blanks from its line
text (DeviceCapture.clean), so "...尺寸为8" | "mm×8mm..." looks exactly like "...局部SE" | "M形貌...".
Only the source tells a break at a word space (legal, Word does it too) from a break inside a token
(a defect). This script reads the docx paragraphs and matches each line boundary against them.

Method: for every pair of adjacent lines inside one device paragraph, take the 8 last characters of
the first line and the 8 first of the second (what lines-all.tsv stores, whitespace-free), and find
that window inside a paragraph of the document. The window's middle is the break. If the original
text has a blank at that point, the break is a word-space break; if not, the token was cut.

    py tools/midword-audit.py --lines artifacts/agent-layout-verify/<tag>/new/lines-all.tsv
    py tools/midword-audit.py --lines ... --docx tests/samples/input-liu.docx --show 12

Exit code 1 when at least one token was cut, so a test can call it.
"""
import argparse
import re
import sys
import zipfile

TOKEN = re.compile(r'[A-Za-z0-9]')
TAG = re.compile(r'<[^>]+>')


def docx_paragraphs(path):
    with zipfile.ZipFile(path) as z:
        xml = z.read('word/document.xml').decode('utf-8')
    out = []
    for p in re.findall(r'<w:p[ >].*?</w:p>', xml, re.S):
        text = ''.join(m.replace('&amp;', '&').replace('&lt;', '<').replace('&gt;', '>')
                       .replace('&quot;', '"').replace('&apos;', "'")
                       .replace('\u00a0', ' ')
                       for m in re.findall(r'<w:t[^>]*>(.*?)</w:t>', p, re.S))
        if len(text) >= 2:
            out.append(text)
    return out


def strip_space(text):
    """(space_free_chars, [index_in_original_of_each_char])"""
    chars, idx = [], []
    for i, c in enumerate(text):
        if c.isspace():
            continue
        chars.append(c)
        idx.append(i)
    return ''.join(chars), idx


def blank_between(original, i, j):
    """True when original[i+1:j] holds a blank -- i and j are two non-blank positions."""
    return any(c.isspace() for c in original[i + 1:j])


def audit(lines, paragraphs):
    stripped = [strip_space(p) for p in paragraphs]
    counts = {'space': 0, 'cut': 0, 'unknown': 0}
    hits = []
    for n in range(len(lines) - 1):
        a, b = lines[n], lines[n + 1]
        if a['paragraph'] != b['paragraph'] or a['page'] != b['page']:
            continue
        window = a['last8'] + b['first8']
        if not window or not TOKEN.match(a['last8'][-1]) or not TOKEN.match(b['first8'][0]):
            continue
        half = len(a['last8'])
        found = []
        for text, (sf, idx) in zip(paragraphs, stripped):
            at, step = 0, 0
            while True:
                at = sf.find(window, at)
                if at < 0:
                    break
                step += 1
                i = idx[at + half - 1]
                j = idx[at + half]
                found.append(blank_between(text, i, j))
                at += 1
        if len(found) != 1:
            counts['unknown'] += 1          # never found, or several paragraphs to choose from
            continue
        if found[0]:
            counts['space'] += 1            # Word breaks here too
        else:
            counts['cut'] += 1
            hits.append((a, b))
    return counts, hits


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--lines', required=True)
    ap.add_argument('--docx', default='tests/samples/input-liu.docx')
    ap.add_argument('--show', type=int, default=8)
    args = ap.parse_args()
    rows = []
    with open(args.lines, encoding='utf-8') as fh:
        head = fh.readline().rstrip('\n').split('\t')
        for line in fh:
            cells = line.rstrip('\n').split('\t')
            if len(cells) == len(head):
                rows.append(dict(zip(head, cells)))
    counts, hits = audit(rows, docx_paragraphs(args.docx))
    print('lines=%d  latin_boundaries=%d  at_word_space=%d  token_cut=%d  unmatched=%d'
          % (len(rows), sum(counts.values()), counts['space'], counts['cut'], counts['unknown']))
    for a, b in hits[:args.show]:
        print('  CUT page=%s para=%s line=%s : ...%s | %s...'
              % (a['page'], a['paragraph'], a['line'], a['last8'], b['first8']))
    return 1 if counts['cut'] else 0


if __name__ == '__main__':
    sys.exit(main())