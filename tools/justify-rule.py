#!/usr/bin/env python3
"""Recompute the CJK justification rule that Microsoft Word actually applied.

Inputs are the captured Word measurements written by tools/word-justify-truth.ps1
(Mode=Measure then Mode=Analyze). Nothing here talks to Word; the TSVs are the record.

  artifacts/word-justify/chars.tsv            x of every character in points, Range.Information(7)
                                              == wdHorizontalPositionRelativeToTextBoundary
  artifacts/word-justify/lines-summary.tsv    one row per Word line: kind, chars, x, right edge
  artifacts/word-justify/gaps.tsv             one row per seam: gap_pt = x[i+1] - x[i]
  artifacts/word-justify/inventory-summary.txt page geometry (text width 425.2 pt)

Every number quoted in docs/word-justification-rule.md is printed by this script.

  py -3 tools/justify-rule.py
"""
import io
import os
from collections import Counter, defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ART = os.path.join(ROOT, "artifacts", "word-justify")

TEXT_WIDTH_PT = 425.2      # section 2/3 text width (inventory-summary.txt)
PX = 4.0 / 3.0             # document px = pt * 96/72, same factor as PageGeometry.points()
GRID_PT = 0.05             # 1 twip: the grid Word reports positions on
EM_PT = 12.0               # body size is 12 pt; a CJK ideograph advances one em
CELL_PT = 12.1             # modal CJK cell Word actually places characters on
QUANTUM_PT = 0.6           # 12 twips: candidate per-seam step, checked in section 5
WIDENED_AT = 0.35          # a seam counts as widened at this delta from natural
NON_JUST = ("justify-LAST-line", "left")
JUST = "justify-not-last"


def read_tsv(name):
    with io.open(os.path.join(ART, name), encoding="utf-8-sig") as fh:
        rows = fh.read().splitlines()
    head = rows[0].split("\t")
    out = []
    for row in rows[1:]:
        f = row.split("\t")
        f += [""] * (len(head) - len(f))
        out.append(dict(zip(head, f)))
    return out


def r2(v):
    return round(v + 0.0, 2)


def quantiles(vals):
    s = sorted(vals)
    if not s:
        return None

    def at(q):
        return s[min(len(s) - 1, max(0, int(round(q * (len(s) - 1)))))]

    return dict(n=len(s), min=s[0], p25=at(0.25), med=at(0.5), p75=at(0.75),
                p95=at(0.95), max=s[-1], mean=sum(s) / len(s))


def report(name, vals, unit="pt"):
    q = quantiles(vals)
    if not q:
        print("  %-34s (no samples)" % name)
        return
    print("  %-34s n=%-4d min=%7.2f p25=%7.2f med=%7.2f p75=%7.2f max=%7.2f  (%s)"
          % (name, q["n"], q["min"], q["p25"], q["med"], q["p75"], q["max"], unit))


def show(counter, top=99):
    return " ".join("%s x%d" % (k, v) for k, v in counter.most_common(top))


def load_lines():
    """Lines keyed by (para, line), characters in document order, para mark dropped."""
    by = defaultdict(list)
    for c in read_tsv("chars.tsv"):
        by[(c["para"], c["line"])].append(c)
    lines = []
    for meta in read_tsv("lines-summary.tsv"):
        rows = sorted(by[(meta["para"], meta["line"])], key=lambda r: int(r["k"]))
        chars = [r for r in rows if r["class"] != "paraend"]
        if not chars:
            continue
        xs = [float(c["x_rel_pt"]) for c in chars]
        seams = []
        for i in range(len(chars) - 1):
            seams.append(dict(i=i,
                              prev=chars[i]["char"], prev_cls=chars[i]["class"],
                              next=chars[i + 1]["char"], next_cls=chars[i + 1]["class"],
                              gap=round(xs[i + 1] - xs[i], 2)))
        lines.append(dict(para=meta["para"], page=meta["page"], line=meta["line"],
                          kind=meta["kind"], chars=chars, xs=xs, seams=seams,
                          left=xs[0], size=float(meta["pitch_median_pt"] or 0)))
    return lines


def is_cjk(cls):
    return cls in ("cjk", "cjkpunct")


def is_western(cls):
    return cls in ("latin", "digit")


def is_autospace_boundary(prev_cls, next_cls):
    """Word's auto 1/4 em between Chinese text and Latin/digits; CJK punctuation never gets it."""
    if prev_cls == "cjkpunct" or next_cls == "cjkpunct":
        return False
    return (prev_cls == "cjk" and is_western(next_cls)) or (is_western(prev_cls) and next_cls == "cjk")


def advance_table(lines, auto_pt):
    """Median advance per character, taken from lines Word did not stretch, with Word's own
    auto 1/4 em taken back out of the raw gap. The Latin/digit advances carry a placement
    wobble of about +-0.35 pt, so per-line slack on Latin-heavy lines is only good to ~1 pt."""
    seen = defaultdict(list)
    for L in lines:
        if L["kind"] not in NON_JUST:
            continue
        for s in L["seams"]:
            extra = auto_pt if is_autospace_boundary(s["prev_cls"], s["next_cls"]) else 0.0
            seen[s["prev"]].append(round(s["gap"] - extra, 2))
    table = dict((ch, quantiles(v)["med"]) for ch, v in seen.items())
    cls_of = {}
    for L in lines:
        for c in L["chars"]:
            cls_of[c["char"]] = c["class"]
    by_class = {}
    for cls in ("cjk", "cjkpunct", "latin", "digit", "space", "other"):
        pool = [v for ch, v in table.items() if cls_of.get(ch) == cls]
        if pool:
            by_class[cls] = quantiles(pool)["med"]
    by_class["cjk"] = CELL_PT
    by_class["cjkpunct"] = CELL_PT
    return table, by_class


def autospace_samples(lines):
    """Auto-space width at a Chinese/Latin seam = measured gap minus the CJK cell. Measured only
    where the left character is a plain ideograph, so no advance table is needed yet."""
    vals = []
    for L in lines:
        if L["kind"] not in NON_JUST:
            continue
        for s in L["seams"]:
            if is_autospace_boundary(s["prev_cls"], s["next_cls"]) and s["prev_cls"] == "cjk":
                vals.append(round(s["gap"] - CELL_PT, 2))
    return vals


def char_adv(ch, cls, table, by_class, em_basis):
    """(advance, measured) -- measured is False when the character never appears on a line Word
    left alone, so the class median stands in and any delta on that seam is not trustworthy."""
    if em_basis and is_cjk(cls):
        return EM_PT, True
    if ch in table:
        return table[ch], True
    return by_class.get(cls, CELL_PT), False


def measure(L, table, by_class, auto_pt, em_basis):
    """Natural width of a line under the model, plus the per-seam deltas Word applied."""
    nat_seams, known = [], []
    for s in L["seams"]:
        a, ok = char_adv(s["prev"], s["prev_cls"], table, by_class, em_basis)
        gap_nat = a + (auto_pt if is_autospace_boundary(s["prev_cls"], s["next_cls"]) else 0.0)
        nat_seams.append(gap_nat)
        known.append(ok)
    last = L["chars"][-1]
    last_adv, known_last = char_adv(last["char"], last["class"], table, by_class, em_basis)
    natural = sum(nat_seams) + last_adv
    available = TEXT_WIDTH_PT - L["left"]
    right_edge = L["xs"][-1] + last_adv
    out = dict(nat_seams=nat_seams, known=known, known_last=known_last, last_adv=last_adv, natural=natural,
               available=available, right_edge=right_edge,
               fill_err=right_edge - TEXT_WIDTH_PT,
               slack=available - natural,
               delivered=(L["xs"][-1] - L["xs"][0]) - sum(nat_seams),
               deltas=[round(s["gap"] - g, 2) for s, g in zip(L["seams"], nat_seams)])
    return out


LINES = load_lines()
AUTO_SAMPLES = autospace_samples(LINES)
AUTO_PT = quantiles(AUTO_SAMPLES)["med"] if AUTO_SAMPLES else 2.90
TABLE, BY_CLASS = advance_table(LINES, AUTO_PT)



def annotated(em_basis=False):
    out = []
    for L in LINES:
        m = measure(L, TABLE, BY_CLASS, AUTO_PT, em_basis)
        L2 = dict(L)
        L2["m"] = m
        out.append(L2)
    return out


EM = annotated(True)
CELL = annotated(False)


def sel(lines, kinds):
    return [L for L in lines if L["kind"] in kinds]


def pure_cjk(L):
    return all(is_cjk(c["class"]) for c in L["chars"])


def zd(L):
    """(seam, delta) for every seam whose natural width was actually measured somewhere in the
    corpus; seams whose left character never appears on an unstretched line are left out."""
    return [(s, d) for s, d, ok in zip(L["seams"], L["m"]["deltas"], L["m"]["known"]) if ok]


def widened(L):
    return [i for s, d in zd(L) for i in [s["i"]] if d >= WIDENED_AT]


def id_of(L):
    return "para %s / page %s / line %s" % (L["para"], L["page"], L["line"])


def sec(title):
    print("")
    print("=" * 78)
    print(title)
    print("=" * 78)


# --------------------------------------------------------------------- section 0
sec("0. INPUTS")
print("  text width              %.1f pt = %.2f document px (96 dpi)"
      % (TEXT_WIDTH_PT, TEXT_WIDTH_PT * PX))
print("  position grid           %.2f pt (1 twip); 12 twips = %.2f pt" % (GRID_PT, QUANTUM_PT))
print("  lines                   %d  (%s)"
      % (len(LINES), show(Counter(L["kind"] for L in LINES))))
print("  seams                   %d  (%s)"
      % (sum(len(L["seams"]) for L in LINES), show(Counter(L["kind"] for L in LINES))))
print("  paragraphs              %d" % len(set(L["para"] for L in LINES)))
print("  pure-CJK lines          %d justified / %d not justified"
      % (len([L for L in LINES if pure_cjk(L) and L["kind"] == JUST]),
         len([L for L in LINES if pure_cjk(L) and L["kind"] != JUST])))
print("  paragraph font size     12 pt everywhere (paras-measured.tsv); one body size in this corpus")

# --------------------------------------------------------------------- section 1
sec("1. DOES THE LINE REACH THE RIGHT EDGE  (target = %.1f pt)" % TEXT_WIDTH_PT)
print("  right edge = x(last char) + natural advance of that char; the capture script")
print("  instead adds the last GAP, which over-counts whenever the last seam was widened.")
print("  left indent taken from the measured x of the first character of the line.")
skipped = [L for L in EM if L["chars"][-1]["class"] == "space"]
print("  %d line(s) end on a trailing space; the trailing space is not part of the line width,"
      % len(skipped))
print("  so those lines are left out of the numbers below.")
for kind in (JUST, "justify-LAST-line", "left"):
    vals = [L["m"]["fill_err"] for L in EM if L["kind"] == kind and L["chars"][-1]["class"] != "space"]
    report(kind, vals)
    report(kind + " (document px)", [v * PX for v in vals], "px")
print("  exact fill_err values on the justified lines above: %s"
      % show(Counter(r2(L["m"]["fill_err"]) for L in EM if L["kind"] == JUST
                     and L["chars"][-1]["class"] != "space")))
worst = sorted((L for L in EM if L["kind"] == JUST), key=lambda L: -abs(L["m"]["fill_err"]))
print("  worst justified lines:")
for L in worst[:6]:
    print("    %-32s chars=%-3d fill_err=%+6.2f pt (%+6.2f px)  last char %s (%s)"
          % (id_of(L), len(L["chars"]), L["m"]["fill_err"], L["m"]["fill_err"] * PX,
             "U+%04X" % ord(L["chars"][-1]["char"]), L["chars"][-1]["class"]))
overs = [L for L in EM if L["kind"] == JUST and L["m"]["fill_err"] > 1.0]
print("  justified lines that end past the right edge: %d" % len(overs))
for L in overs:
    print("    %-32s chars=%-3d fill_err=%+6.2f pt  last char U+%04X (%s)"
          % (id_of(L), len(L["chars"]), L["m"]["fill_err"], ord(L["chars"][-1]["char"]),
             L["chars"][-1]["class"]))

# --------------------------------------------------------------------- section 2
sec("2. WHAT 'NATURAL' MEANS  (widths on lines Word left alone)")
print("  -- CJK cell pitch, pure-CJK lines that are NOT justified (last lines + left paragraphs)")
pitch = []
for L in LINES:
    if L["kind"] == JUST or not pure_cjk(L) or len(L["seams"]) < 4:
        continue
    pitch.append(sum(s["gap"] for s in L["seams"]) / len(L["seams"]))
report("mean CJK cell per line", pitch)
print("  -- CJK cell values, cjk>cjk seams only")
for kind in (JUST, "justify-LAST-line", "left"):
    vals = Counter(s["gap"] for L in LINES if L["kind"] == kind for s in L["seams"]
                   if s["prev_cls"] == "cjk" and s["next_cls"] == "cjk")
    print("    %-18s %s" % (kind, show(vals)))
print("  -- compressed cells (gap 11.5 = one quantum under the 12.1 modal cell)")
for kind in (JUST, "justify-LAST-line", "left"):
    tot = sum(1 for L in LINES if L["kind"] == kind for s in L["seams"] if is_cjk(s["prev_cls"]))
    comp = sum(1 for L in LINES if L["kind"] == kind for s in L["seams"]
               if is_cjk(s["prev_cls"]) and s["gap"] <= 11.6)
    print("    %-18s %4d of %5d CJK seams compressed (%.1f%%)" % (kind, comp, tot, 100.0 * comp / tot))
print("  -- Word's auto 1/4 em at the Chinese/Latin seam (gap minus the left char's advance)")
report("measured auto-space (not justified)", AUTO_SAMPLES)
print("     sample values %s" % show(Counter(AUTO_SAMPLES)))
print("  -- advance table used for non-CJK characters (modal, measured on lines Word did not stretch)")
rows = []
for cls in ("latin", "digit", "space", "other"):
    got = [(ch, v) for ch, v in TABLE.items()
           if any(s["prev"] == ch and s["prev_cls"] == cls for L in LINES for s in L["seams"])]
    got.sort(key=lambda kv: (kv[1], kv[0]))
    print("     %-7s %s" % (cls, " ".join("%s=%.2f" % (("U+%04X" % ord(ch)), v) for ch, v in got)))
print("  -- model check: predicted natural width minus measured width, lines Word did NOT stretch")
for basis, name in ((True, "em basis (CJK cell = 12.00)"), (False, "modal basis (CJK cell = 12.10)")):
    pool = EM if basis else CELL
    report(name + ", pure CJK", [L["m"]["delivered"] for L in pool
                                 if L["kind"] in NON_JUST and pure_cjk(L)])
    report(name + ", mixed CJK+Latin", [L["m"]["delivered"] for L in pool
                                        if L["kind"] in NON_JUST and not pure_cjk(L)
                                        and any(is_cjk(c["class"]) for c in L["chars"])])
    report(name + ", Latin only", [L["m"]["delivered"] for L in pool
                                   if L["kind"] in NON_JUST and not any(is_cjk(c["class"]) for c in L["chars"])])
print("     (delivered = measured span - predicted natural span; 0.00 would mean the model is exact)")

# --------------------------------------------------------------------- section 3
sec("3. HOW MUCH SLACK DOES A JUSTIFIED LINE HAVE  (question 1)")
J = [L for L in EM if L["kind"] == JUST]
Jc = [L for L in CELL if L["kind"] == JUST]


def has_cjk(L):
    return any(is_cjk(c["class"]) for c in L["chars"])


JC = [L for L in J if has_cjk(L)]
JLAT = [L for L in J if not has_cjk(L)]
print("  slack = (%.1f pt - line indent) - natural width of the same characters" % TEXT_WIDTH_PT)
print("  Chinese-containing lines carry the claims; the two Latin-only reference lines are listed apart.")
report("slack, em basis, Chinese lines", [L["m"]["slack"] for L in JC])
report("slack, em basis, Chinese lines (px)", [L["m"]["slack"] * PX for L in JC], "px")
report("slack, modal-cell basis, Chinese lines", [L["m"]["slack"] for L in Jc if has_cjk(L)])
report("delivered stretch, em basis, Chinese lines", [L["m"]["delivered"] for L in JC])
report("delivered stretch, modal basis, Chinese lines",
       [L["m"]["delivered"] for L in Jc if has_cjk(L)])
report("slack, Latin-only reference lines", [L["m"]["slack"] for L in JLAT])
report("fill error, Latin-only reference lines", [L["m"]["fill_err"] for L in JLAT])
print("  per-seam share of the slack")
report("slack / seam count, em basis", [L["m"]["slack"] / max(1, len(L["seams"])) for L in JC])
report("slack / seam count, modal basis", [L["m"]["slack"] / max(1, len(L["seams"])) for L in Jc if has_cjk(L)])
print("  how many seams of a line actually move (delta >= %.2f pt)" % WIDENED_AT)
counts = []
for L in J:
    n = [L["seams"][i]["gap"] - L["m"]["nat_seams"][i] for i in range(len(L["seams"]))]
    k = sum(1 for d in n if d >= WIDENED_AT)
    counts.append((k, len(L["seams"]), L))
report("widened seams per line, Chinese lines", [c[0] for c in counts if has_cjk(c[2])], "seams")
report("seams per line, Chinese lines", [c[1] for c in counts if has_cjk(c[2])], "seams")
report("widened share of seams, Chinese lines", [100.0 * c[0] / c[1] for c in counts if has_cjk(c[2])], "%")
print("  widest-slack lines")
for L in sorted(J, key=lambda L: -L["m"]["slack"])[:8]:
    m = L["m"]
    print("    %-32s chars=%-3d indent=%5.1f natural=%7.2f slack=%+6.2f pt (%+6.2f px) delivered=%6.2f fill=%+5.2f"
          % (id_of(L), len(L["chars"]), L["left"], m["natural"], m["slack"], m["slack"] * PX,
             m["delivered"], m["fill_err"]))

# --------------------------------------------------------------------- section 4
sec("4. WHICH SEAMS GET WIDENED  (question 2)")
print("  widened = a seam whose delta from its measured natural width is >= %.2f pt" % WIDENED_AT)
dec, dec_right, first_pos, last_pos, spacing, endhits = Counter(), Counter(), [], [], [], Counter()
for L in J:
    n, wid = len(L["seams"]), widened(L)
    if not wid:
        continue
    first_pos.append(wid[0] / float(n))
    last_pos.append((wid[-1] + 1) / float(n))
    spacing += [wid[i + 1] - wid[i] for i in range(len(wid) - 1)]
    if wid[0] == 0:
        endhits["first seam of the line"] += 1
    if wid[-1] == n - 1:
        endhits["last seam of the line"] += 1
    for i in wid:
        dec[min(9, int(10.0 * i / n))] += 1
        dec_right[min(9, int(10.0 * (n - 1 - i) / max(1, n - 1)))] += 1
tot = sum(dec.values())
print("  %d widened seams over %d justified lines" % (tot, len(J)))
print("  position along the line, by decile (0 = leftmost tenth of the line)")
print("    " + "  ".join("d%d:%-4d" % (i, dec.get(i, 0)) for i in range(10)))
print("    share per decile: %s" % "  ".join("%.0f%%" % (100.0 * dec.get(i, 0) / tot) for i in range(10)))
print("  the same seams counted back from the right edge")
print("    " + "  ".join("d%d:%-4d" % (i, dec_right.get(i, 0)) for i in range(10)))
print("  how far the widened seams sit from the ends, as a fraction of the line")
report("first widened seam on the line", first_pos)
report("last widened seam on the line", last_pos)
report("seams between two widened seams", spacing, "seams")
print("  lines whose very first seam is widened: %d; whose very last seam is widened: %d"
      % (endhits["first seam of the line"], endhits["last seam of the line"]))
print("")
print("  pure-CJK justified lines grouped by (characters, indent): the widened seam indices")
groups = defaultdict(list)
for L in J:
    if not pure_cjk(L):
        continue
    groups[(len(L["chars"]), r2(L["left"]))].append(L)
for key in sorted(groups):
    print("    chars=%-3d indent=%5.1f  %d line(s)" % (key[0], key[1], len(groups[key])))
    for L in groups[key]:
        w = widened(L)
        print("      %-32s widened=%-20s spacing=%-14s total delivered=%5.2f pt, on those seams=%5.2f pt"
              % (id_of(L), str(w),
                 str([w[i + 1] - w[i] for i in range(len(w) - 1)]),
                 L["m"]["delivered"], sum(L["m"]["deltas"][i] for i in w)))
print("")
print("  widened pattern on the pure-CJK lines ('w' = widened seam, '.' = left at natural)")
for L in [x for x in J if pure_cjk(x)]:
    w = set(widened(L))
    print("    %-32s %s" % (id_of(L), "".join("w" if i in w else "." for i in range(len(L["seams"])))))
# --------------------------------------------------------------------- section 5
sec("5. HOW MUCH, AND IS IT QUANTIZED  (question 3)")
unk = sum(1 for L in EM for ok in L["m"]["known"] if not ok)
print("  %d of %d seams are dropped from the delta statistics: their left character never occurs on"
      % (unk, sum(len(L["seams"]) for L in EM)))
print("  a line Word left alone, so its natural advance would only be a class guess.")
pool = [(d, s, L) for L in EM for s, d in zd(L)]
pool_j = [(d, s, L) for d, s, L in pool if L["kind"] == JUST]
pool_n = [(d, s, L) for d, s, L in pool if L["kind"] in NON_JUST]
report("delta, all justified lines", [d for d, _, _ in pool_j])
report("delta, lines Word did not stretch", [d for d, _, _ in pool_n])
report("delta, widened seams only", [d for d, _, _ in pool_j if d >= WIDENED_AT])
print("  CJK seam deltas on justified lines, on the 0.05 pt report grid")
hist = Counter(r2(d) for d, s, _ in pool_j if is_cjk(s["prev_cls"]))
for d in sorted(hist):
    print("    %+6.2f pt  %4d" % (d, hist[d]))
print("  every CJK seam delta against the nearest multiple of %.2f pt (12 twips)" % QUANTUM_PT)
report("|delta - nearest 0.6 pt multiple|, justified",
       [abs(d - QUANTUM_PT * round(d / QUANTUM_PT)) for d, s, _ in pool_j if is_cjk(s["prev_cls"])])
report("|delta - nearest 0.6 pt multiple|, not stretched",
       [abs(d - QUANTUM_PT * round(d / QUANTUM_PT)) for d, s, _ in pool_n if is_cjk(s["prev_cls"])])
print("  report grid test: distance of each captured gap from the 0.05 pt grid")
off = max(abs(s["gap"] / GRID_PT - round(s["gap"] / GRID_PT)) for L in LINES for s in L["seams"])
print("    worst off-grid gap = %.4f pt" % off)
print("  CJK seam deltas inside one line")
lows, highs, spread, uniq, adj = [], [], [], Counter(), Counter()
for L in EM:
    if L["kind"] != JUST:
        continue
    v = sorted(set(r2(d) for s, d in zd(L) if is_cjk(s["prev_cls"])))
    if len(v) < 2:
        continue
    uniq[len(v)] += 1
    lows.append(v[0])
    highs.append(v[-1])
    spread.append(r2(v[-1] - v[0]))
    for i in range(len(v) - 1):
        adj[r2(v[i + 1] - v[i])] += 1
print("    distinct CJK delta values per line: %s" % show(uniq))
report("spread from the smallest to the largest CJK delta on a line", spread)
print("    step between neighbouring values on the same line: %s" % show(adj, 12))
best, worst = None, None
for L in EM:
    if L["kind"] != JUST:
        continue
    v = zd(L)
    if not v:
        continue
    hi = max(v, key=lambda t: t[1])
    lo = min(v, key=lambda t: t[1])
    if best is None or hi[1] > best[0]:
        best = (hi[1], L, hi[0])
    if worst is None or lo[1] < worst[0]:
        worst = (lo[1], L, lo[0])
print("  largest single-seam stretch: %+.2f pt = %.2f px, %s seam %d (%s>%s U+%04X, gap %.2f pt)"
      % (best[0], best[0] * PX, id_of(best[1]), best[2]["i"], best[2]["prev_cls"], best[2]["next_cls"],
         ord(best[2]["prev"]), best[2]["gap"]))
print("  smallest seam delta on a justified line: %+.2f pt, %s seam %d (%s>%s U+%04X, gap %.2f pt)"
      % (worst[0], id_of(worst[1]), worst[2]["i"], worst[2]["prev_cls"], worst[2]["next_cls"],
         ord(worst[2]["prev"]), worst[2]["gap"]))
print("  top six single-seam stretches:")
for d, s, L in sorted(pool_j, key=lambda t: -t[0])[:6]:
    print("    %+6.2f pt (%+5.2f px)  %-32s seam %-2d %s>%s gap %.2f" % (d, d * PX, id_of(L), s["i"],
                                                                        s["prev_cls"], s["next_cls"], s["gap"]))
print("  does the line add up?  delivered stretch against k quanta")
err = []
for L in EM:
    if L["kind"] != JUST:
        continue
    k = len(widened(L))
    err.append(L["m"]["delivered"] - k * QUANTUM_PT)
report("delivered minus k * 0.60 pt", err)
# --------------------------------------------------------------------- section 5b
sec("5b. ONE GRID EXPLAINS EVERY CJK SEAM WIDTH, STRETCHED OR NOT")
vals = sorted(set(s["gap"] for L in LINES for s in L["seams"] if is_cjk(s["prev_cls"])))
print("  %d distinct CJK seam widths in the corpus: %s"
      % (len(vals), " ".join("%.2f" % v for v in vals)))
print("  model: width = %.2f pt cell + k * Q, one Q, integer k, rounded to the %.2f pt grid" % (CELL_PT, GRID_PT))
fits = []
for i in range(300, 1001):
    q = i / 1000.0
    fits.append((max(abs(v - (CELL_PT + q * round((v - CELL_PT) / q))) for v in vals), q))
band = sorted(q for w, q in fits if w <= GRID_PT + 1e-9)
Q_PT = round((band[0] + band[-1]) / 2.0, 3)
print("  Q that fits every width within the report grid: %.3f .. %.3f pt; adopted Q = %.3f pt"
      % (band[0], band[-1], Q_PT))
print("  Q = %.1f twips = %.3f document px = %.1f%% of the 12 pt em" % (Q_PT / GRID_PT, Q_PT * PX, 100.0 * Q_PT / EM_PT))
others = sorted(set(round(w, 3) for w, q in fits))[:4]
print("  for scale, the smallest achievable residuals overall: %s" % " ".join("%.3f" % w for w in others))
print("  k     model pt    observed widths (count)")
for k in sorted(set(int(round((v - CELL_PT) / Q_PT)) for v in vals)):
    got = [v for v in vals if int(round((v - CELL_PT) / Q_PT)) == k]
    print("  %+2d    %6.3f     %s" % (k, CELL_PT + k * Q_PT,
          " ".join("%.2f x%d" % (v, sum(1 for L in LINES for s in L["seams"]
                                        if is_cjk(s["prev_cls"]) and s["gap"] == v)) for v in got)))
res = max(abs(v - (CELL_PT + Q_PT * round((v - CELL_PT) / Q_PT))) for v in vals)
print("  worst residual against the grid: %.3f pt (the report grid is %.2f pt)" % (res, GRID_PT))
print("  in em terms at this %.0f pt size: cell = %.5f * size, Q = %.4f * size"
      % (EM_PT, CELL_PT / EM_PT, Q_PT / EM_PT))
mix = [int(round((s["gap"] - CELL_PT) / Q_PT)) for L in LINES if L["kind"] in NON_JUST and pure_cjk(L)
       for s in L["seams"] if s["prev_cls"] == "cjk" and s["next_cls"] == "cjk"]
print("  k mix on lines Word did NOT stretch, pure CJK: %s" % show(Counter(mix)))
mean_k = sum(mix) / float(len(mix))
print("  mean k there = %.3f, so the average cell is %.2f pt = the em, i.e. one k=-1 cell per %.1f cells"
      % (mean_k, CELL_PT + Q_PT * mean_k, 1.0 / abs(mean_k)))
print("  justification lifts k on a few seams instead of moving every one.")
print("  does the total add up on pure-CJK justified lines?  k_total = slack / Q, where a line")
print("  that hangs a trailing mark is measured as if the mark sat inside the column")
bad = 0
pure = [L for L in Jc if pure_cjk(L)]
for L in pure:
    n = len(L["chars"])
    hang = L["m"]["fill_err"] > 1.0 and L["chars"][-1]["class"] == "cjkpunct"
    avail = L["m"]["available"] + (CELL_PT if hang else 0.0)
    need = (avail - n * CELL_PT) / Q_PT
    got = sum(int(round((s["gap"] - CELL_PT) / Q_PT)) for s in L["seams"])
    if round(need) != got:
        bad += 1
    print("    %-32s chars=%-3d slack=%+6.2f pt%s -> k_total=%5.2f   realised sum of k = %+3d   %s"
          % (id_of(L), n, avail - n * CELL_PT, "  (mark hangs)" if hang else "", need, got,
             "ok" if round(need) == got else "off by %+d" % (got - round(need))))
print("  lines whose realised quanta differ from round(slack / Q): %d of %d" % (bad, len(pure)))

# --------------------------------------------------------------------- section 6
sec("6. WHICH SEAM TYPES MAY OPEN  (question 4)")
print("  delta is always measured against the measured natural width of the left character")
print("  (plus Word's auto 1/4 em where it applies), on the 0.05 pt grid.")
print("  %-16s %5s %6s %6s %6s | %4s %6s %6s | %6s %6s %6s  %s"
      % ("pair", "nJ", "minJ", "medJ", "maxJ", "nN", "minN", "maxN", "d_med", "d_max", "k%", "opens?"))
pairs = sorted(set(s["prev_cls"] + ">" + s["next_cls"] for L in LINES for s in L["seams"]))
for pair in pairs:
    pj = pair.split(">")[0]
    js = [(s["gap"], d, L) for L in EM if L["kind"] == JUST for s, d in zd(L)
          if s["prev_cls"] + ">" + s["next_cls"] == pair]
    ns = [(s["gap"], d, L) for L in EM if L["kind"] in NON_JUST for s, d in zd(L)
          if s["prev_cls"] + ">" + s["next_cls"] == pair]
    if not js:
        continue
    jg = [g for g, _, _ in js]
    ng = [g for g, _, _ in ns]
    dd = [d for _, d, _ in js]
    k = sum(1 for d in dd if d >= WIDENED_AT)
    verdict = "yes" if k else ("a hair" if max(dd) > 0.15 else "never")
    print("  %-16s %5d %6.2f %6.2f %6.2f | %4d %6.2f %6.2f | %+6.2f %+6.2f %3d%%  %s"
          % (pair, len(js), min(jg), quantiles(jg)["med"], max(jg), len(ns),
             min(ng) if ng else float("nan"), max(ng) if ng else float("nan"),
             quantiles(dd)["med"], max(dd), int(round(100.0 * k / len(dd))), verdict))
print("  seam types that never occur in this corpus: %s"
      % " ".join(p for p in ("cjk>space", "space>cjk", "cjkpunct>space", "space>cjkpunct") if p not in pairs))
print("")
print("  -- inside Latin words: the same character, justified lines vs lines Word left alone")
per = defaultdict(lambda: ({}, {}))
for L in EM:
    for s, d in zd(L):
        if s["prev_cls"] in ("latin", "digit") and s["next_cls"] in ("latin", "digit"):
            per[s["prev"]][0 if L["kind"] == JUST else 1].setdefault("v", []).append(s["gap"])
same = diff = 0
for ch, slots in sorted(per.items()):
    a, bb = slots[0].get("v", []), slots[1].get("v", [])
    if len(a) < 3 or len(bb) < 2:
        continue
    ma, mb = quantiles(a)["med"], quantiles(bb)["med"]
    if abs(ma - mb) <= 0.05:
        same += 1
    else:
        diff += 1
        print("     moves: U+%04X median %.2f justified vs %.2f not" % (ord(ch), ma, mb))
print("     %d of %d letters/digits keep exactly the same median advance when the line is justified"
      % (same, same + diff))
print("")
print("  -- explicit spaces (the space character's own advance is the seam)")
sp = [(d, s, L) for d, s, L in pool_j if s["prev_cls"] == "space"]
spn = [s["gap"] for d, s, L in pool_n if s["prev_cls"] == "space"]
print("     %d space seams on justified lines, %d on lines Word left alone" % (len(sp), len(spn)))
report("space advance, not stretched", spn)
report("space stretch on justified lines", [d for d, _, _ in sp])
for d, s, L in sorted(sp, key=lambda t: -t[0]):
    if d >= WIDENED_AT and any(is_cjk(c["class"]) for c in L["chars"]):
        print("       %s seam %-2d next U+%04X gap %.2f pt  delta %+.2f  (Chinese paragraph, %d chars)"
              % (id_of(L), s["i"], ord(s["next"]), s["gap"], d, len(L["chars"])))
print("")
print("  -- Latin seam deltas are a two-sided placement wobble, not a widening")
lat = [d for d, s, _ in pool_j if s["prev_cls"] in ("latin", "digit", "other")
       and s["next_cls"] in ("latin", "digit", "other")]
cjkj = [d for d, s, _ in pool_j if is_cjk(s["prev_cls"])]
spc = [d for d, s, _ in pool_j if s["prev_cls"] == "space"]
for name, v in (("intra-Latin / punctuation seams", lat), ("CJK-side seams", cjkj),
                ("explicit space seams", spc)):
    up = sum(1 for d in v if d >= WIDENED_AT)
    dn = sum(1 for d in v if d <= -WIDENED_AT)
    print("     %-32s n=%-5d median %+5.2f   at least +%.2f: %4d (%2.0f%%)   at most -%.2f: %4d (%2.0f%%)"
          % (name, len(v), quantiles(v)["med"], WIDENED_AT, up, 100.0 * up / len(v),
             WIDENED_AT, dn, 100.0 * dn / len(v)))
print("     widening is one sided. The CJK and space columns are; the Latin column is balanced,")
print("     so its few +0.60 outliers are Word's own +-1 quantum placement wobble on proportional text.")
lm = []
for L in EM:
    if L["kind"] != JUST:
        continue
    v = [d for s, d in zd(L) if s["prev_cls"] in ("latin", "digit", "other")
         and s["next_cls"] in ("latin", "digit", "other")]
    if len(v) >= 8:
        lm.append((quantiles(v)["med"], L))
print("     per-line median intra-Latin delta (lines with 8+ such seams): %s"
      % " ".join("%s:%+.2f" % ("para %s/line %s" % (L["para"], L["line"]), m) for m, L in sorted(lm, key=lambda t: t[0])))
one = [s["gap"] for L in EM for s, _ in zd(L) if s["prev"] == "1" and s["prev_cls"] == "digit"]
print("     one caveat: the digit U+0031 alone measures anywhere from %.2f to %.2f pt in this corpus, so a"
      % (min(one), max(one)))
print("     single Latin seam carries a point or more of context (it depends on what follows), so no")
print("     claim in this report rests on one Latin seam; only the per-character medians above are used.")

# --------------------------------------------------------------------- section 7
sec("7. TRAILING PUNCTUATION, OVERFLOW, AND GIVING UP  (question 5)")
pj = [L for L in EM if L["kind"] == JUST]
punct_end = [L for L in pj if L["chars"][-1]["class"] == "cjkpunct"]
print("  justified lines that end on CJK punctuation: %d of %d" % (len(punct_end), len(pj)))
report("fill error of those lines", [L["m"]["fill_err"] for L in punct_end])
report("delta on the seam before that mark",
       [zd(L)[-1][1] for L in punct_end if zd(L)])
report("delta on CJK seams of those same lines",
       [d for L in punct_end for s, d in zd(L) if is_cjk(s["prev_cls"])])
hangs = [L for L in punct_end if L["m"]["fill_err"] > 1.0]
for L in hangs:
    print("    %s: %d characters, x of the trailing mark %.2f pt, one cell %.2f pt, so the mark spans"
          % (id_of(L), len(L["chars"]), L["xs"][-1], CELL_PT))
    print("      %.2f..%.2f pt while the column ends at %.1f pt; the cells in front of it fill to"
          % (L["xs"][-1], L["xs"][-1] + CELL_PT, TEXT_WIDTH_PT))
    print("      %.2f pt and the seam before the mark carries %+.2f pt"
          % (L["xs"][-1], zd(L)[-1][1] if zd(L) else float("nan")))
print("")
print("  is there a 'too much slack, leave it alone' threshold?")
report("fill error, all justified lines", [L["m"]["fill_err"] for L in pj if L["chars"][-1]["class"] != "space"])
for lo, hi in ((0, 5), (5, 10), (10, 20), (20, 1e9)):
    sub = [L for L in pj if lo <= L["m"]["slack"] < hi and L["chars"][-1]["class"] != "space"]
    if not sub:
        continue
    print("    slack %5.0f..%-6.0f n=%-3d fill error min=%+6.2f med=%+6.2f max=%+6.2f  per-seam delta med=%+.2f max=%+.2f"
          % (lo, hi, len(sub), min(L["m"]["fill_err"] for L in sub),
             quantiles([L["m"]["fill_err"] for L in sub])["med"], max(L["m"]["fill_err"] for L in sub),
             quantiles([d for L in sub for s, d in zd(L) if is_cjk(s["prev_cls"])])["med"],
             max([d for L in sub for s, d in zd(L) if is_cjk(s["prev_cls"])])))
print("  the most stretched lines that do not end on a space; all of them still reach the edge:")
for L in [x for x in sorted(pj, key=lambda L: -L["m"]["slack"]) if x["chars"][-1]["class"] != "space"][:3]:
    print("    %s slack=%+6.2f pt (%+.1f%% of the line) delivered=%+6.2f fill=%+5.2f pt, %d of %d seams moved"
          % (id_of(L), L["m"]["slack"], 100.0 * L["m"]["slack"] / L["m"]["available"],
             L["m"]["delivered"], L["m"]["fill_err"], len(widened(L)), len(L["seams"])))
print("")
top = [x for x in sorted(pj, key=lambda L: -L["m"]["slack"]) if x["chars"][-1]["class"] != "space"][0]
print("  the widest of them on the cell basis: %.1f pt available for %d cells of %.2f pt = %.1f pt slack"
      % (top["m"]["available"], len(top["chars"]), CELL_PT,
         top["m"]["available"] - len(top["chars"]) * CELL_PT))
print("  the lines that appear to end past the right edge, with the reason:")
for L in sorted(EM, key=lambda L: -L["m"]["fill_err"])[:4]:
    tail = " ".join("U+%04X(%s)" % (ord(c["char"]), c["class"]) for c in L["chars"][-2:])
    why = ("trailing space, which is not part of the line width"
           if L["chars"][-1]["class"] == "space" else
           "hanging punctuation (w:overflowPunct): the mark sits outside the column"
           if L["m"]["fill_err"] > 1.0 and L["chars"][-1]["class"] == "cjkpunct" else "unexplained")
    print("    %-32s %-17s chars=%-3d fill=%+6.2f pt  ends: %s  -- %s"
          % (id_of(L), L["kind"], len(L["chars"]), L["m"]["fill_err"], tail, why))

# --------------------------------------------------------------------- section 8
sec("8. THE SAME LINES THROUGH OUR LAYOUT  (question 6)")
PAGE_W_PT = 595.3            # inventory-summary.txt: page width, both margins included
FIT = 1080.0 / (PAGE_W_PT * PX)
CELL_PX = 16                 # PageGeometry.points(12) == 16 px; a CJK ideograph advances exactly that
AUTO_PX = 4                  # AutoGap: paint.getTextSize() / 4
WIDTH_PX = int(round(TEXT_WIDTH_PT * PX))
print("  document px = pt * 4/3, exactly PageGeometry.points(): column %d px, 12 pt em = %d px,"
      " AutoGap = %d px" % (WIDTH_PX, CELL_PX, AUTO_PX))
print("  a %.1f pt page is %.1f px wide, so on a 1080 px screen fitScale puts 1 document px at %.3f"
      " device px" % (PAGE_W_PT, PAGE_W_PT * PX, FIT))
print("  Word places on a %.2f pt cell = %.2f document px, so one 12 pt ideograph is %.1f"
      " device px" % (CELL_PT, CELL_PT * PX, CELL_PX * FIT))
print("  Word moves a seam in steps of Q = %.3f pt = %.3f document px." % (Q_PT, Q_PT * PX))
print("  WidenGap.getSize returns an int, so the smallest step we can express is 1 whole document px")
print("  (= %.2f pt), which is %.0f%% coarser than Word's step." % (1.0 / PX, 100.0 * (1.0 / (Q_PT * PX) - 1.0)))


def latin_px(ch, cls):
    return int(round(char_adv(ch, cls, TABLE, BY_CLASS, False)[0] * PX))


STEP_PX = 1                  # DocxTextLayout.MAX_GAP_STRETCH_STEP_PX at the time of writing
CEIL_PX = 6                  # DocxTextLayout.MAX_GAP_STRETCH_PX


def replay(L, step=STEP_PX, centred=True):
    """spreadLine + gapOffsets replayed in document px, as the Java reads today: whole pixel
    advances, the line's whole pixel slack floored, ceil(total / step) seams chosen centred and
    evenly across the eligible gaps, each seam's share taken from a floor-Bresenham over the seams."""
    chars = list(L["chars"])
    dropped = 0
    while len(chars) > 1 and chars[-1]["class"] == "space":
        chars.pop()
        dropped += 1
    n = len(chars)
    left_px = int(round(L["left"] * PX))
    adv = []
    for i in range(n - 1):
        a = CELL_PX if is_cjk(chars[i]["class"]) else latin_px(chars[i]["char"], chars[i]["class"])
        if is_autospace_boundary(chars[i]["class"], chars[i + 1]["class"]):
            a += AUTO_PX
        adv.append(a)
    last_px = CELL_PX if is_cjk(chars[-1]["class"]) else latin_px(chars[-1]["char"], chars[-1]["class"])
    slack = WIDTH_PX - left_px - (sum(adv) + last_px)
    elig = [i for i in range(n - 1) if is_cjk(chars[i]["class"]) or is_cjk(chars[i + 1]["class"])]
    total = 0 if (slack < 1 or not elig) else min(int(slack + 0.001), len(elig) * CEIL_PX)
    seams = min(len(elig), max(1, -(-total // step))) if total else 0
    add = [0] * n
    for j in range(seams):
        slot = (((2 * j + 1) * len(elig)) // (2 * seams) if centred else
                (j * len(elig)) // seams)                        # centred, or from the left
        add[elig[slot]] += int((j + 1) * total / seams) - int(j * total / seams)
    xs = [left_px]
    for i in range(n - 1):
        xs.append(xs[-1] + adv[i] + add[i])
    return dict(xs=xs, right=xs[-1] + last_px, total=total, slack=slack, elig=len(elig), add=add,
                seams=seams, per_gap=max([add[i] for i in elig] or [0]),
                widened=sum(1 for i in elig if add[i]), last_widened=bool(n > 1 and add[n - 2]),
                dropped=dropped, n=n)


def word_rule(L):
    """The rule this report derives, replayed in pt: one modal cell per character, k = slack / Q
    quanta dithered evenly (round, so the staircase stays off the last seam), widths back on the grid."""
    n = len(L["chars"])
    ns = max(1, n - 1)
    avail = L["m"]["available"]
    hang = L["m"]["fill_err"] > 1.0 and L["chars"][-1]["class"] == "cjkpunct"
    if hang:
        avail += CELL_PT                      # the hung mark is outside the column, the rest fills it
    k = int(round((avail - n * CELL_PT) / Q_PT))
    xs = [L["left"] + i * CELL_PT + int(round(i * k / float(ns))) * Q_PT for i in range(n)]
    ks = [int(round((i + 1) * k / float(ns))) - int(round(i * k / float(ns))) for i in range(ns)]
    return dict(xs=xs, ks=ks, right=xs[-1] + CELL_PT, k=k, hang=hang)


print("")
print("  A. does the derived rule reproduce Word?  pure-CJK justified lines, no Latin to confound it")
rows = []
for L in Jc:
    if pure_cjk(L):
        rows.append((L, word_rule(L)))
same = diff = 0
for L, w in rows:
    for i, s in enumerate(L["seams"]):
        if abs(round((s["gap"] - CELL_PT) / Q_PT) - w["ks"][i]) < 1e-9:
            same += 1
        else:
            diff += 1
report("A: |x_model - x_word|, every character", [abs(a - b) for L, w in rows for a, b in zip(w["xs"], L["xs"])])
report("A: max |x_model - x_word| on a line", [max(abs(a - b) for a, b in zip(w["xs"], L["xs"])) for L, w in rows])
report("A: right edge, model minus Word", [w["right"] - L["m"]["right_edge"] for L, w in rows])
report("A: k_total, model minus Word's realised", [w["k"] - sum(int(round((s["gap"] - CELL_PT) / Q_PT))
                                                                for s in L["seams"]) for L, w in rows], "quanta")
print("     the model puts the right k on %d of %d seams (%.0f%%); the rest are the same seam count"
      % (same, same + diff, 100.0 * same / (same + diff)))
mfirst, wfirst, mper, wper = [], [], [], []
for L, w in rows:
    mk = [i for i, k in enumerate(w["ks"]) if k > 0]
    wk = [i for i, s in enumerate(L["seams"]) if round((s["gap"] - CELL_PT) / Q_PT) > 0]
    if mk and wk:
        mfirst.append(mk[0])
        wfirst.append(wk[0])
    if len(mk) > 1:
        mper.append((mk[-1] - mk[0]) / float(len(mk) - 1))
    if len(wk) > 1:
        wper.append((wk[-1] - wk[0]) / float(len(wk) - 1))
print("     the phase differs, not the arithmetic: first widened seam %.1f (model) vs %.1f (Word),"
      % (sum(mfirst) / len(mfirst), sum(wfirst) / len(wfirst)))
print("     seam period %.1f (model) vs %.1f (Word), both even spreads of the same k_total."
      % (sum(mper) / len(mper), sum(wper) / len(wper)))

print("")
print("")
print("  B. our shipped whole-pixel spread, replayed on the same lines")
ours = [(L, replay(L)) for L in J]
hang_line = lambda L: L["m"]["fill_err"] > 1.0 and L["chars"][-1]["class"] == "cjkpunct"
cjk_ok = [(L, o) for L, o in ours if pure_cjk(L) and not hang_line(L)]
cjk_hang = [(L, o) for L, o in ours if pure_cjk(L) and hang_line(L)]
mixed = [(L, o) for L, o in ours if not pure_cjk(L)]
mixed_go = [(L, o) for L, o in mixed if o["slack"] >= 1]
print("     the replay justifies all %d pure-CJK lines and %d of %d mixed ones; a mixed line whose"
      % (len(cjk_ok), len(mixed_go), len(mixed)))
print("     simulated natural width misses the column is skipped, exactly as spreadLine skips slack < 1 px.")
print("     Latin advances there are Word's, not Android's, so mixed lines are counted, not measured.")
print("     The %d pure-CJK lines that hang a trailing mark are left out of the comparison too: the" % len(cjk_hang))
print("     replay has no PunctInkWidth, so it measures those lines %d px too wide and skips them."
      % (int(round(len(cjk_hang[0][0]["chars"]) * CELL_PX - WIDTH_PX)) if cjk_hang else 0))
report("B: our right edge minus the column (%d px)" % WIDTH_PX, [o["right"] - WIDTH_PX for _, o in cjk_ok], "px")
report("B: Word's right edge minus the column, same lines",
       [(L["m"]["right_edge"] - TEXT_WIDTH_PT) * PX for L, _ in cjk_ok], "px")
devs = [o["xs"][i] - L["xs"][i] * PX for L, o in cjk_ok for i in range(o["n"])]
report("B: our x minus Word's x, every character", devs, "doc px")
report("B: the same in device px on a 1080 px screen", [d * FIT for d in devs], "device px")
report("B: max |deviation| on a line",
       [max(abs(o["xs"][i] - L["xs"][i] * PX) for i in range(o["n"])) for L, o in cjk_ok], "doc px")
report("B: gaps we widen", [o["widened"] for _, o in cjk_ok], "gaps")
report("B: gaps Word widens", [len(widened(L)) for L, _ in cjk_ok], "gaps")
print("     per line, document px:")
for L, o in cjk_ok:
    dv = [o["xs"][i] - L["xs"][i] * PX for i in range(o["n"])]
    print("       %-32s slack %3d px  total %2d px  we open %2d of %2d gaps, Word %2d;"
          " right edge %+5.2f vs Word %+5.2f; max |dev| %.2f px"
          % (id_of(L), o["slack"], o["total"], o["widened"], o["elig"], len(widened(L)),
             o["right"] - WIDTH_PX, (L["m"]["right_edge"] - TEXT_WIDTH_PT) * PX, max(abs(d) for d in dv)))
print("     B: our spread opens the seam before the last character on %d of %d lines; Word opens"
      % (sum(1 for _, o in ours if o["last_widened"]), len(ours)))
print("        it on %d of %d, so choosing the seams centred and evenly, rather than by integer"
      % (sum(1 for L in J if L["seams"] and L["seams"][-1]["i"] in widened(L)), len(J)))
print("        division from the left, already agrees with Word on leaving that seam alone.")
blind = [(L, s, d) for L in J for s, d in zd(L) if d >= WIDENED_AT
         and not is_cjk(s["prev_cls"]) and not is_cjk(s["next_cls"])]
print("     B: seams Word widens that gapOffsets cannot see (no CJK on either side): %d of the %d"
      % (len(blind), sum(len(widened(L)) for L in J)))
print("        seams Word widens, carrying %+.2f pt of extra width between them." % sum(d for _, _, d in blind))
print("        They are explicit spaces and Latin seams, which the platform opens by itself under")
print("        JUSTIFICATION_MODE_INTER_WORD, so a different mechanism already covers them.")
auto = [(L, s, d) for L in J for s, d in zd(L) if d >= WIDENED_AT and is_autospace_boundary(s["prev_cls"], s["next_cls"])]
print("     B: the CJK/Latin auto-space seam is inside our eligible set (ownsReplacedRange lets AutoGap")
print("        through), and Word opens it hard: %d widened auto-space seams, up to %+.2f pt."
      % (len(auto), max([d for _, _, d in auto] or [0.0])))
print("")
print("  C. how coarse may one seam be?  the same slack spread over a different number of seams")
print("     Word opens ceil(slack / Q) seams at 0.767 document px each. One whole document px is the")
print("     smallest seam a ReplacementSpan can open, so the only free choice is how many to open.")
for step in (1, 2, 3):
    v = [(L, replay(L, step)) for L, _ in cjk_ok]
    dev = [max(abs(o["xs"][i] - L["xs"][i] * PX) for i in range(o["n"])) for L, o in v]
    print("     one seam up to %d px: opens %s seams per line, widest seam %d px, right edge %+.2f px,"
          % (step, show(Counter(str(o["seams"]) for _, o in v)), max(o["per_gap"] for _, o in v),
             v[0][1]["right"] - WIDTH_PX))
    print("         max |deviation| from Word %.2f document px (%.2f device px), final seam opened on"
          " %d of %d lines"
          % (max(dev), max(dev) * FIT, sum(1 for _, o in v if o["last_widened"]), len(v)))
left = [(L, replay(L, centred=False)) for L, _ in cjk_ok]
ldev = [max(abs(o["xs"][i] - L["xs"][i] * PX) for i in range(o["n"])) for L, o in left]
print("     seams chosen by integer division from the left, the shape this code had earlier:")
print("         max |deviation| %.2f document px, final seam opened on %d of %d lines."
      % (max(ldev), sum(1 for _, o in left if o["last_widened"]), len(left)))
print("     why we open more seams than Word on the same line: our ideograph advances 16 px (12.00 pt),")
print("     Word places on a 12.10 pt cell, so the slack we have to spend is several times its own.")
report("C: slack the replay has to spend", [o["slack"] for _, o in cjk_ok], "px")
report("C: Word's slack on those lines",
       [(L["m"]["available"] - len(L["chars"]) * CELL_PT) * PX for L, _ in cjk_ok], "px")
report("C: the same in Word's quanta",
       [(L["m"]["available"] - len(L["chars"]) * CELL_PT) / Q_PT for L, _ in cjk_ok], "quanta")
plain = [d - 0.10 for L in EM if L["kind"] == JUST for s, d in zd(L)
         if is_cjk(s["prev_cls"]) and not is_autospace_boundary(s["prev_cls"], s["next_cls"])]
both = [d - 0.10 for L in EM if L["kind"] == JUST for s, d in zd(L)
        if is_autospace_boundary(s["prev_cls"], s["next_cls"])]
print("     For scale, Word's widest seam measured against the 12.10 pt cell: %+.2f pt ideograph to"
      % max(plain))
print("     ideograph and %+.2f pt at an inter-script seam; the ceiling in the code is %d document px"
      % (max(both), CEIL_PX))
print("     = %.1f pt, so it never binds on anything Word did to this document." % (CEIL_PX / PX))
