#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/break-common.py -- re-score item 7 over only the paragraphs BOTH captures compared.

Item 7 (py tools/break-agreement.py) compares the set of line-end offsets Word used against the set the
phone used, one paragraph at a time, and a paragraph only enters the ratio when its lines can be paired
at all. A paragraph can therefore JOIN the pool because a change made its line count match Word's -- and
bring its own, unrelated defects with it. When that happens the headline percentage moves for a reason
that is not the change under test, so the honest comparison is the intersection: the paragraphs that
were compared in both runs.

    py tools/break-common.py artifacts/agent-layout-verify/265-release/break-agreement.tsv `
                             artifacts/agent-layout-verify/slashglue2/break-agreement.tsv
Prints each side over all paragraphs and then over the common ones, plus the paragraphs that moved.
Reads only the two TSVs; no device, no Word session.
"""
import collections
import csv
import sys


def load(path):
    with open(path, encoding="utf-8-sig", newline="") as handle:
        return [r for r in csv.DictReader(handle, delimiter="\t")]


def score(rows, paras=None):
    per = collections.defaultdict(collections.Counter)
    for r in rows:
        per[r["word_para"]][r["side"]] += 1
    picked = {p: c for p, c in per.items() if paras is None or p in paras}
    both = sum(c["both"] for c in picked.values())
    phone = sum(c["phone_only"] for c in picked.values())
    word = sum(c["word_only"] for c in picked.values())
    union = both + phone + word
    return picked, both, phone, word, union


def line(tag, both, phone, word, union, paras):
    rate = 100.0 * both / union if union else 0.0
    print("  %-22s %3d paragraphs  %d/%d = %5.1f%%  (we broke extra %d, Word broke extra %d)"
          % (tag, paras, both, union, rate, phone, word))


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    old, new = load(sys.argv[1]), load(sys.argv[2])
    po, pn = score(old)[0], score(new)[0]
    print("old: %s\nnew: %s" % (sys.argv[1], sys.argv[2]))
    for tag, rows in (("old, all paragraphs", old), ("new, all paragraphs", new)):
        picked, both, phone, word, union = score(rows)
        line(tag, both, phone, word, union, len(picked))
    common = sorted(set(po) & set(pn), key=lambda p: int(p))
    print("common to both runs (%d paragraphs):" % len(common))
    for tag, rows in (("old", old), ("new", new)):
        picked, both, phone, word, union = score(rows, set(common))
        line(tag, both, phone, word, union, len(picked))
    print("paragraphs whose counts moved:")
    for p in common:
        if po[p] != pn[p]:
            print("  para %-5s old both=%d phone=%d word=%d  ->  new both=%d phone=%d word=%d"
                  % (p, po[p]["both"], po[p]["phone_only"], po[p]["word_only"],
                     pn[p]["both"], pn[p]["phone_only"], pn[p]["word_only"]))
    for p in sorted(set(pn) - set(po), key=lambda p: int(p)):
        print("  para %-5s JOINED the pool: new both=%d phone=%d word=%d"
              % (p, pn[p]["both"], pn[p]["phone_only"], pn[p]["word_only"]))
    for p in sorted(set(po) - set(pn), key=lambda p: int(p)):
        print("  para %-5s LEFT the pool: old both=%d phone=%d word=%d"
              % (p, po[p]["both"], po[p]["phone_only"], po[p]["word_only"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
