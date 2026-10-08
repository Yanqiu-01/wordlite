#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Per-(character, face) advance reconciliation: Word's exported PDF vs our bundled font files.

Why this exists: the "上下标导致分页不良" lead claimed Word substitutes a face for U+207B
(superscript minus, in W·m⁻¹·K⁻¹) while our engine bills a different face's advance, so width and
line height both drift. This tool answers that for every character in the sample, not just U+207B.

Usage:
    py tools/font-advance-audit.py [docx] [pdf]
        defaults: tests/samples/input-liu.docx  artifacts/agent-typeset/pdf-truth/input-liu.pdf

What it does:
  our side  - resolve the face exactly like DocxTextLayout.apply(): eastAsia face for CJK blocks,
              ascii face below U+0080, hAnsi face otherwise, docDefaults for runs that say nothing;
              then take the advance out of the bundled file's hmtx.
  Word side - pymupdf rawdict over the exported PDF: advance = next char origin - this char origin,
              dropped when the next char is a space (Word's PDF carries gap spaces), normalised to
              em so font size does not matter, and reduced to the MODE of the distribution because
              justified lines stretch advances (the mode is the natural advance, the tail is
              justification).
  report A  - same face on both sides, |our em - Word em| > 0.004 em.
  report B  - characters where we would leave the declared face (STIX detour / no bundled glyph).
  report C  - the characters the lead is about: U+207B, U+00B9, U+00B7, U+2103.

Requires: pymupdf, fontTools. Nothing is written outside stdout.
"""
import collections
import hashlib
import io
import os
import sys
import zipfile
import xml.etree.ElementTree as ET

import pymupdf
from fontTools.ttLib import TTFont

W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
FONTS = "app/src/main/assets/fonts/"
DEFAULTS = {"ascii": "Times New Roman", "hAnsi": "Times New Roman",
            "eastAsia": "\u5b8b\u4f53", "cs": "Times New Roman"}
# face name -> bundled file (mirrors DocxFontAssets; read-only)
NAME = {"Times New Roman": "times-new-roman.ttf", "\u5b8b\u4f53": "song.ttc",
        "\u9ed1\u4f53": "simhei.ttf", "\u4eff\u5b8b": "fangsong.ttf",
        "\u534e\u6587\u65b0\u9b4f": "stxinwei.ttf", "\uff2d\uff33 \u660e\u671d": "msgothic.ttf",
        "STIX Two Math": "stix-two-math.ttf"}
PDF_FONT = {"TimesNewRomanPSMT": "Times New Roman", "TimesNewRomanPS-BoldMT": "Times New Roman",
            "TimesNewRomanPS-ItalicMT": "Times New Roman", "SimSun": "\u5b8b\u4f53",
            "SimHei": "\u9ed1\u4f53"}
# DocxTextLayout.isCjk(): these Unicode block ranges only.
CJK = [(0x4E00, 0x9FFF), (0x3400, 0x4DBF), (0xF900, 0xFAFF), (0x3000, 0x303F),
       (0xAC00, 0xD7A3), (0x3040, 0x309F), (0x30A0, 0x30FF), (0xFF00, 0xFFEF)]

_cache = {}


def face(name):
    if name not in _cache:
        f = TTFont(FONTS + NAME[name], lazy=True)
        _cache[name] = (f.getBestCmap(), f["hmtx"], f["head"].unitsPerEm)
    return _cache[name]


def is_cjk(c):
    o = ord(c)
    return any(a <= o <= b for a, b in CJK)


def our_em(face_name, c):
    cm, hmtx, units = face(face_name)
    g = cm.get(ord(c))
    return None if g is None else hmtx[g][0] / float(units)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def scan_docx(path):
    """(char, face we charge) -> count, plus (char, 'declared->charged') -> count for fallbacks."""
    root = ET.fromstring(zipfile.ZipFile(path).read("word/document.xml"))
    ours = collections.defaultdict(int)
    detour = collections.defaultdict(int)
    for r in root.iter(W + "r"):
        text = "".join(t.text or "" for t in r.iter(W + "t"))
        if not text:
            continue
        rpr = r.find(W + "rPr")
        rf = rpr.find(W + "rFonts") if rpr is not None else None
        a = dict((k.split("}")[-1], v) for k, v in rf.attrib.items()) if rf is not None else {}
        fams = dict((k, a.get(k) or DEFAULTS[k]) for k in DEFAULTS)
        for c in text:
            if c in "\r\n":
                continue
            declared = fams["eastAsia"] if is_cjk(c) else (fams["ascii"] if ord(c) < 0x80 else fams["hAnsi"])
            if declared not in NAME:
                detour[(c, "face-not-bundled:" + declared)] += 1
                continue
            if our_em(declared, c) is not None:
                ours[(c, declared)] += 1
                continue
            stix = our_em("STIX Two Math", c)
            charged = "STIX Two Math" if stix is not None else "(android fallback)"
            detour[(c, declared + " -> " + charged)] += 1
    return ours, detour


def scan_pdf(path):
    """(char, family) -> list of advance/em samples from Word's own exported PDF."""
    out = collections.defaultdict(list)
    fonts = collections.Counter()
    doc = pymupdf.open(path)
    for page in doc:
        for b in page.get_text("rawdict")["blocks"]:
            for line in b.get("lines", []):
                for s in line["spans"]:
                    fam = PDF_FONT.get(s["font"].split("+")[-1])
                    fonts[s["font"].split("+")[-1]] += 1
                    size = s["size"]
                    if not fam or size <= 0:
                        continue
                    chars = s["chars"]
                    for i, ch in enumerate(chars):
                        c = ch["c"]
                        if c in (" ", "\n", "\t") or i + 1 >= len(chars):
                            continue
                        if chars[i + 1]["c"] in (" ", "\n", "\t"):
                            continue  # Word's gap space would be billed to this char
                        out[(c, fam)].append(round((chars[i + 1]["origin"][0] - ch["origin"][0]) / size, 3))
    return out, fonts


def main():
    docx = sys.argv[1] if len(sys.argv) > 1 else "tests/samples/input-liu.docx"
    pdf = sys.argv[2] if len(sys.argv) > 2 else "artifacts/agent-typeset/pdf-truth/input-liu.pdf"
    print("docx sha256 %s  %s" % (sha256(docx)[:16], docx))
    print("pdf  sha256 %s  %s  (%d pages)" % (sha256(pdf)[:16], pdf, pymupdf.open(pdf).page_count))
    ours, detour = scan_docx(docx)
    word, fonts = scan_pdf(pdf)
    print("PDF span fonts: " + ", ".join("%s x%d" % kv for kv in fonts.most_common()))
    print("docx characters resolved to a bundled face: %d, on a fallback path: %d"
          % (sum(ours.values()), sum(detour.values())))

    print("\n== A. same face both sides, |ours - Word| > 0.004 em (Word = mode of the PDF samples) ==")
    print("%-7s %-8s %-16s %-8s %-8s %s" % ("count", "char", "face", "ours", "word", "pdf samples"))
    rows = []
    for (c, fam), n in ours.items():
        samples = word.get((c, fam))
        if not samples:
            continue
        w = collections.Counter(samples).most_common(1)[0][0]
        o = our_em(fam, c)
        if abs(o - w) > 0.004:
            rows.append((n, c, fam, o, w, len(samples)))
    rows.sort(key=lambda r: -r[0])
    for n, c, fam, o, w, s in rows[:25]:
        print("%-7d U+%04X %-16s %-8.3f %-8.3f %d" % (n, ord(c), fam, o, w, s))
    print("mismatching (char, face) pairs: %d of %d comparable"
          % (len(rows), len([1 for k in ours if word.get(k)])))

    print("\n== B. characters we would take off the declared face ==")
    for (c, path), n in sorted(detour.items(), key=lambda kv: -kv[1]):
        w = sorted(set(f for (cc, f) in word if cc == c))
        print("%-7d U+%04X %-34s Word faces: %s" % (n, ord(c), path, ",".join(w) or "-"))
    print("total characters on a fallback path: %d" % sum(detour.values()))

    print("\n== C. the characters from the lead ==")
    for cp in (0x207B, 0x207A, 0x00B9, 0x00B7, 0x2103):
        c = chr(cp)
        line = "U+%04X %-3s" % (cp, c)
        for fam in ("Times New Roman", "\u5b8b\u4f53", "STIX Two Math"):
            em = our_em(fam, c) if fam in NAME else None
            line += "  ours[%s]=%s x%d" % (fam, "%.4f" % em if em else "-", ours.get((c, fam), 0))
        samples = word.get((c, "Times New Roman")) or []
        line += "  Word(Times) mode=%s n=%d" % (collections.Counter(samples).most_common(1)[0][0]
                                                if samples else "-", len(samples))
        print(line)


if __name__ == "__main__":
    main()
