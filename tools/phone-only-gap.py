# 我们断行而 Word 没断的那些行，差多少像素才装得下下一个串。
#
# 这条只回答一个问题：43 处"我们比 Word 早断"里面，有多少是断行规则不同意（余量本来就装得下，
# 是规则不让断），有多少是我们把这一行的字宽算贵了（装得下但只差一两像素）。前者要改规则，
# 后者要改计费，两件事不能混着修。
#
# 用法：py tools/phone-only-gap.py <capture>   例：py tools/phone-only-gap.py artifacts/agent-layout-verify/glueprice1
import csv, collections, sys, bisect

cap = sys.argv[1] + '/'
lines = collections.defaultdict(list)
with open(cap + 'new/lines-all.tsv', encoding='utf-8-sig', newline='') as f:
    for r in csv.DictReader(f, delimiter='\t'):
        lines[int(r['paragraph'])].append(r)
for p in lines:
    lines[p].sort(key=lambda r: int(r['line']))

buckets = collections.Counter()
rows = []
with open(cap + 'break-agreement.tsv', encoding='utf-8-sig', newline='') as f:
    for r in csv.DictReader(f, delimiter='\t'):
        if r['side'] != 'phone_only':
            continue
        blk, off = int(r['device_block']), int(r['offset'])
        cum, hit = 0, None
        for ln in lines.get(blk, []):
            cum += int(ln['lineChars'])
            if cum == off:
                hit = ln
                break
        if hit is None:
            buckets['行末偏移对不上'] += 1
            continue
        room = float(hit['lineRoomPx'])
        one, run = float(hit['nextCharPx']), float(hit['nextRunPx'])
        if room >= one:
            buckets['余量本来就够（规则不同意，不是宽度问题）'] += 1
            gap = 0.0
        else:
            gap = min(one, run) - room
            if gap <= 1.0: buckets['差 <=1 px'] += 1
            elif gap <= 3.0: buckets['差 1~3 px'] += 1
            elif gap <= 8.0: buckets['差 3~8 px'] += 1
            else: buckets['差 >8 px（本来就装不下）'] += 1
        rows.append((blk, int(hit['line']), round(room, 2), one, run, round(gap, 2), r['cut_class']))

for k, v in sorted(buckets.items(), key=lambda kv: -kv[1]):
    print('%-38s %d' % (k, v))
print()
print('block line  room_px  nextChar  nextRun  gap_px  class')
for t in sorted(rows, key=lambda t: t[5])[:18]:
    print('%5d %4d %8s %9s %8s %7s  %s' % t)
