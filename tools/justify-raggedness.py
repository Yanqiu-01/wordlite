"""Justification raggedness from a device capture.

Reads artifacts/device/<impl>/lines-all.tsv (produced by tools/capture-device.ps1) and
measures where each justified Chinese line actually ENDS in document coordinates:
    paraXPx + lineLeftPx + lineWidthPx   vs   the section's text column (566.93 px at 96 dpi)
That is the right edge the reader sees, so "median ~ 0" means the column is flush and a
large spread inside one paragraph is exactly the raggedness being complained about.

Usage:  py -3 tools/justify-raggedness.py [old new ...]
"""
import io
import os
import sys

COLUMN = 566.9333      # 15.00 cm text column at 96 dpi, as the reference section declares
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def rows(path):
    with io.open(path, encoding="utf-8") as handle:
        head = handle.readline().rstrip("\n").split("\t")
        for line in handle:
            cells = line.rstrip("\n").split("\t")
            if len(cells) < len(head):
                continue
            row = dict(zip(head, cells))
            try:
                yield {"page": int(row["page"]), "paragraph": int(row["paragraph"]),
                       "line": int(row["line"]), "text": row["lineFull"],
                       "right": float(row["lineWidthPx"])
                               + float(row.get("lineLeftPx") or 0)
                               + float(row.get("paraXPx") or 0)}
            except (KeyError, ValueError):
                continue


def east_asian(text):
    return any(ord(ch) > 0x2E80 for ch in text)


def report(impl):
    path = os.path.join(ROOT, "artifacts", "device", impl, "lines-all.tsv")
    if not os.path.exists(path):
        print("%s: no capture at %s (run tools/capture-device.ps1 -Impls %s)" % (impl, path, impl))
        return
    groups = {}
    for row in rows(path):
        groups.setdefault((row["page"], row["paragraph"]), []).append(row)
    short = []
    worst = []
    for _, lines in sorted(groups.items()):
        if len(lines) < 3:
            continue
        body = [r for r in lines[:-1]                    # the paragraph's last line is never stretched
                if 480 < r["right"] <= COLUMN + 1 and east_asian(r["text"])]
        if len(body) < 3:
            continue
        for row in body:
            gap = COLUMN - row["right"]
            short.append(gap)
            if gap > 2:
                worst.append((gap, row))
    if not short:
        print("%s: no justified body lines found in this capture" % impl)
        return
    short.sort()
    n = len(short)
    late = sum(1 for gap in short if gap > 2)
    print("%s: justified CJK non-last lines=%d | document-space gap to the %.2f px column: "
          "median=%.2f p90=%.2f worst=%.2f | more than 2 px short: %d (%.1f%%) | at or past the edge: %d"
          % (impl, n, COLUMN, short[n // 2], short[int(n * 0.9)], short[-1], late,
             100.0 * late / n, sum(1 for gap in short if gap <= 0)))
    worst.sort(key=lambda item: -item[0])
    for gap, row in worst[:6]:
        print("    p%s para%s line%s  %.2f px short  %s"
              % (row["page"], row["paragraph"], row["line"], gap, row["text"][:34]))


if __name__ == "__main__":
    for impl in (sys.argv[1:] or ["old", "new"]):
        report(impl)
