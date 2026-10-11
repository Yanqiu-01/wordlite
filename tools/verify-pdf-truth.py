import csv, collections, sys
def load(p):
    d = collections.defaultdict(lambda: {"lines": set(), "pages": set()})
    with open(p, encoding='utf-8-sig', newline='') as f:
        for r in csv.DictReader(f, delimiter='\t'):
            k = int(r['word_para'])
            if r.get('word_lines'): d[k]['lines'].add(int(r['word_lines']))
            if r.get('word_page'):  d[k]['pages'].add(int(r['word_page']))
    return d
old = load(sys.argv[1]); new = load(sys.argv[2])
shared = sorted(set(old) & set(new))
bad_lines = [p for p in shared if old[p]['lines'] != new[p]['lines']]
bad_pages = [p for p in shared if old[p]['pages'] != new[p]['pages']]
print('old paragraphs=%d  new paragraphs=%d  shared=%d' % (len(old), len(new), len(shared)))
print('Word line-count disagrees on %d of %d shared paragraphs' % (len(bad_lines), len(shared)))
for p in bad_lines[:10]:
    print('   para %s old=%s new=%s' % (p, sorted(old[p]['lines']), sorted(new[p]['lines'])))
print('Word page disagrees on %d of %d shared paragraphs' % (len(bad_pages), len(shared)))
for p in bad_pages[:10]:
    print('   para %s old=%s new=%s' % (p, sorted(old[p]['pages']), sorted(new[p]['pages'])))
