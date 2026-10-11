# -*- coding: utf-8 -*-
"""
导出黄金样本：同一批段落，把 19 条判据的取值与段分逐条写死成 tests/corpus/aigc-instrument-golden.tsv。
Java 侧 AigcRhythmParityRegression 读同一张表逐条比对，量台与手机算的不是同一个数就当场红。

段落一律取自仓库内已入库的标注语料 + 一批边界样本（空串、纯标点、纯西文、中英混排、
假名与全角标点、含补充平面字符、极短段），不引入仓库外文本。

    py tools/aigc-instrument-golden.py
"""
import importlib.util, json, os, sys
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
HERE = os.path.dirname(os.path.abspath(__file__)); REPO = os.path.dirname(HERE)
LABELS = os.path.join(REPO, "tests", "corpus")
OUT = os.path.join(REPO, "tests", "corpus", "aigc-instrument-golden.tsv")

def load(mod, name):
    sp = importlib.util.spec_from_file_location(name, os.path.join(HERE, mod))
    m = importlib.util.module_from_spec(sp); sp.loader.exec_module(m); return m

IMOD = load("aigc-instrument.py", "ins")
INS = IMOD.read()
RH = IMOD.RH

def paras(f, want):
    path = os.path.join(LABELS, f)
    if not os.path.exists(path):
        return []
    body = "\n".join(l for l in open(path, encoding="utf-8").read().split("\n") if not l.startswith("#"))
    ps = [p for p in RH.lab_paras(body) if RH.valid_len(p) >= 60]
    ps.sort(key=lambda p: -RH.valid_len(p))
    return ps[:want]

def main():
    picked = []
    for f, want, tag in (("real-prose.txt", 6, "真人正文"),
                         ("aigc-label-human-verbatim.txt", 4, "真人逐字"),
                         ("aigc-label-human-polish-agent.txt", 3, "真人润色"),
                         ("aigc-label-machine-raw.txt", 5, "机器直出"),
                         ("aigc-label-machine-evasive.txt", 3, "机器避套话"),
                         ("aigc-label-machine-domain.txt", 3, "机器领域对齐"),
                         ("aigc-hard-human.txt", 3, "硬真人"),
                         ("aigc-frames.txt", 2, "框架腔")):
        got = paras(f, want)
        for i, p in enumerate(got):
            picked.append(("%s-%02d" % (tag, i + 1), p))
    edge = [
        ("边界-空串", ""),
        ("边界-纯标点", "。，、；：！？——（）《》“”……"),
        ("边界-单字", "好"),
        ("边界-纯西文", "The quick brown fox jumps over the lazy dog while the detector keeps counting characters."),
        ("边界-中英混排", "本研究采用 PCR 法检测 BRCA1 基因突变，敏感度 93.5%（95%CI 0.88-0.97），与 Western blot 结果一致。"),
        ("边界-数字与文献", "对照组 128 例（45.3%），观察组 154 例（54.7%），差异有统计学意义（P<0.05）[12-14]。见表 3-2。"),
        ("边界-假名与全角", "これは実験です。結果は良好でした。サンプルは 120 件、成功率は 92％ です。"),
        ("边界-补充平面", "这个指标的分子式是 H₂O，箭头 → 指向下一段，附注 😀 属于补充平面字符，必须按码点计数。"),
        ("边界-上下标数字", "第三段引用了 ¹H NMR (400 MHz, CDCl₃) 数据：δ 7.26 (d, J = 8.4 Hz, 2H)，见第 ² 章表 Ⅲ-1。"),
        ("边界-长段无句号", "全段只用逗号分隔的一个长句" * 8),
        ("边界-等长短句", "数据采集完成。模型训练开始。结果误差下降。结论基本可靠。" * 5),
    ]
    picked += edge
    rows = []
    for i, (tag, p) in enumerate(picked, 1):
        vals = INS.values(p)
        rows.append([("G%03d" % i), tag, str(RH.valid_len(p))] +
                    ["%.10g" % v for v in vals] + ["%.10g" % INS.score(p), p.replace("\t", " ").replace("\n", " ")])
    ids = [r["id"] for r in INS.rows]
    header = ["# version=%s" % INS.describe(), "# 判据取值与段分由 tools/aigc-instrument-golden.py 用随包表算出，Java 逐条比对"]
    header.append("\t".join(["id", "source", "valid_chars"] + ids + ["score", "text"]))
    open(OUT, "w", encoding="utf-8", newline="\n").write("\n".join(header + ["\t".join(r) for r in rows]) + "\n")
    print("写出 %s：%d 段 × %d 条判据（%.1f KB）" % (OUT, len(rows), len(ids), os.path.getsize(OUT) / 1024.0))
    print("判定线 human_p99=%s；其中可疑段 %d 段" % (INS.p99, sum(1 for r in picked if INS.flagged(r[1]))))

if __name__ == "__main__":
    main()
