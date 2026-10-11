# 把 Wordlite 与 Word 的每一个断行分歧，按 UAX #14 的行断类别归类。
#
# 为什么要这个：343 个断点里 86 个对不上（43 处我们断 Word 不断，43 处 Word 断我们不断）。
# 光看段号看不出该改哪条规则，看"断点处左右两个字符各是什么行断类别"才看得出：
# Word 的断行器在哪些类别对上放行、我们在哪些类别对上放行。这一步不需要手机，
# 也不需要重新排一遍——Word 的断点已经导出在 break-agreement.tsv 里。
#
# 类别表来自 Unicode 官方的 LineBreak.txt（UAX #14），存在 tools/data/LineBreak.txt，
# 用法：py tools/word-break-model.py <capture>
import csv, sys, bisect, collections

def load_classes(path):
    rng = []
    for ln in open(path, encoding='utf-8'):
        ln = ln.split('#')[0].strip()
        if not ln or ';' not in ln:
            continue
        lhs, cls = ln.split(';', 1)
        lhs, cls = lhs.strip(), cls.strip()
        if '..' in lhs:
            a, b = lhs.split('..')
        else:
            a = b = lhs
        rng.append((int(a, 16), int(b, 16), cls))
    rng.sort()
    return rng

RNG = load_classes('tools/data/LineBreak.txt')
STARTS = [r[0] for r in RNG]

def cls(ch):
    if not ch:
        return '?'
    cp = ord(ch[0])
    i = bisect.bisect_right(STARTS, cp) - 1
    if i >= 0 and RNG[i][1] >= cp:
        return RNG[i][2]
    return 'XX'

def pair_at(prev, nxt):
    return '%s|%s' % (cls(prev), cls(nxt))

cap = sys.argv[1].rstrip('/') + '/'
rows = list(csv.DictReader(open(cap + 'break-agreement.tsv', encoding='utf-8-sig'), delimiter='\t'))
GLUE = {'AL', 'NU', 'IS', 'HY', 'BA', 'PR', 'AI'}
buckets = collections.defaultdict(list)
for r in rows:
    if r['side'] == 'both':
        continue
    key = pair_at(r['prev_char'], r['next_char'])
    buckets[(r['side'], key)].append(r)

print('UAX#14 类别区间 %d 条；分歧行 %d' % (
    len(RNG), sum(1 for r in rows if r['side'] != 'both')))
for side, title in (('word_only', 'Word 断了、我们没断（我们少断的地方）'),
                    ('phone_only', '我们断了、Word 没断（我们多断的地方）')):
    print('\n== %s' % title)
    items = [(k, v) for (s, k), v in buckets.items() if s == side]
    items.sort(key=lambda kv: -len(kv[1]))
    for k, v in items[:12]:
        both_glueable = all(cls(x['prev_char']) in GLUE and cls(x['next_char']) in GLUE for x in v)
        ex = v[0]['context'][:26].replace('\n', '')
        print('  %-10s %3d%s  例: %s' % (k, len(v), '   <- 两侧都是西文/数字（粘串族）' if both_glueable else '', ex))
