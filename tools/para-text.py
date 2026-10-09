#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/para-text.py -- write the text of chosen docx paragraphs, one per line, for a device probe.

The break probes need the document's own paragraphs, not invented filler: whether a line ends on a "/"
depends on where the line boundary lands, and only the real text lands it where the thesis does. The
index is the same one every other tool uses -- the w:p order of word/document.xml, which is what
tools/break-agreement.py and the device capture call "paragraph"/"block".

Usage:
    py tools/para-text.py -Paras 86,94,113 -Out artifacts/agent-layout-verify/probe-paras.txt
"""
import argparse
import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("-Docx", default="tests/samples/input-liu.docx")
    ap.add_argument("-Paras", default="86,94,113")
    ap.add_argument("-Out", required=True)
    a = ap.parse_args()
    spec = importlib.util.spec_from_file_location("wb", os.path.join(HERE, "width-bill.py"))
    wb = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(wb)
    paras = wb.docx_paragraphs(a.Docx)
    want = [int(x) for x in a.Paras.split(",") if x.strip()]
    with open(a.Out, "w", encoding="utf-8", newline="\n") as fh:
        for i in want:
            fh.write(paras[i].replace("\n", " ") + "\n")
    print("wrote %d paragraphs to %s" % (len(want), a.Out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
