'''把 Adobe-GB1 的 CID→Unicode 对照表做成随包文件（app/src/main/assets/cmaps/adobe-gb1.cid）。

为什么要它：中文期刊 PDF（方正排版那一族）常用 Type0 + /Encoding /Identity-H，既不写 ToUnicode、
也不内嵌字体文件——PDF 里只有"字形码"，码到字的表不在这份文件里。这类字体的 /CIDSystemInfo 写着
/Registry (Adobe) /Ordering (GB1)，等于声明"我的码就是 Adobe-GB1 的 CID"，字由 Adobe 公开的字符集定义。
随包带这张表才读得出来（PyMuPDF 也是这么补的）。

数据来源与许可：third_party/cmap-resources/（adobe-type-tools/cmap-resources，BSD 3 条款，见 LICENSE.md）。
两个来源互相核对，不许悄悄挑一个：
  1) Adobe-GB1-6/cid2code.txt 第 14 列 UniGB-UTF32：Adobe 官方给的"CID → 码位"，作为真值取它；
  2) Adobe-GB1-6/CMap/UniGB-UTF16-H：写的是"码位 → CID"，反过来用它做覆盖检查，并取出同一 CID 的其它候选码位。

一处明确的改写（会打印条数）：Adobe 官方把 214 个 CID 的规范码位写成康熙部首（U+2F00..U+2FDF）
或 CJK 部首补充（U+2E80..U+2EFF），而同一个 CID 上也挂着汉字本体（U+4E00..U+9FFF、U+3400..U+4DBF）。
方正这类 PDF 在这些码位上画的是汉字本体，取部首会得到 ⼀(U+2F00) 这种字——和 一(U+4E00) 不是一回事，
查重时对不上。所以这两段部首一律换成同一个 CID 上的汉字本体（两者本来就是 Unicode NFKC 等价）。

命令：py tools/build-cmaps.py            （重建并打印大小与核对数字）
      py tools/build-cmaps.py --check    （只核对与打印，不写文件）
'''
import os, re, sys, zlib, hashlib, collections

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "third_party", "cmap-resources")
SRC_CID2CODE = os.path.join(SRC, "Adobe-GB1", "cid2code.txt")
SRC_UTF16 = os.path.join(SRC, "Adobe-GB1", "UniGB-UTF16-H")
SRC_LICENSE = os.path.join(SRC, "LICENSE.md")
OUT_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "cmaps")
OUT = os.path.join(OUT_DIR, "adobe-gb1.cid")
OUT_LICENSE = os.path.join(ROOT, "app", "src", "main", "assets", "licenses", "ADOBE-CMAP-RESOURCES-BSD.txt")
ORDERING = "GB1"
IDEOGRAPH = lambda u: (0x4E00 <= u <= 0x9FFF) or (0x3400 <= u <= 0x4DBF)
RADICAL = lambda u: (0x2F00 <= u <= 0x2FDF) or (0x2E80 <= u <= 0x2EFF)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()[:16]


def read_cid2code(path):
    """Adobe 官方 CID→码位（第 14 列 UniGB-UTF32）。'*' 表示这个 CID 没有码位；行尾的 v 只表示竖排 CMap 也有。"""
    out = {}
    with open(path, encoding="latin1") as handle:
        for line in handle:
            if not line or line[0] == "#":
                continue
            cols = line.rstrip("\n").split("\t")
            if len(cols) < 14 or not cols[0].isdigit():
                continue
            value = cols[13].split(",")[0].strip().rstrip("v")
            if value in ("", "*"):
                continue
            out[int(cols[0])] = int(value, 16)
    return out


def read_cmap_candidates(path):
    """UniGB-UTF16-H 写的是码位→CID，反过来收集每个 CID 收到的全部码位。"""
    text = open(path, encoding="latin1").read()
    body = text.split("begincmap", 1)[1]
    pairs = []
    for block in re.findall(r"begincidchar(.*?)endcidchar", body, re.S):
        for src, cid in re.findall(r"<([0-9A-Fa-f]+)>\s+(\d+)", block):
            pairs.append((src, int(cid)))
    for block in re.findall(r"begincidrange(.*?)endcidrange", body, re.S):
        for lo, hi, base in re.findall(r"<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s+(\d+)", block):
            lo, hi, base = int(lo, 16), int(hi, 16), int(base)
            for step in range(hi - lo + 1):
                pairs.append(("%04X" % (lo + step), base + step))
    candidates = collections.defaultdict(set)
    skipped = 0
    for src, cid in pairs:
        units = [int(src[i:i + 4], 16) for i in range(0, len(src), 4)]
        if len(units) == 1:
            point = units[0]
        elif (len(units) == 2 and 0xD800 <= units[0] <= 0xDBFF and 0xDC00 <= units[1] <= 0xDFFF):
            point = 0x10000 + ((units[0] - 0xD800) << 10) + (units[1] - 0xDC00)
        else:
            skipped += 1
            continue
        candidates[cid].add(point)
    return candidates, len(pairs), skipped


def choose(canonical, candidates):
    """定一个 CID 的字：Adobe 官方列优先，其次取最小码位；康熙部首换成同一 CID 上的汉字本体。"""
    options = set(candidates)
    if canonical is not None:
        options.add(canonical)
    picked = canonical if canonical is not None else min(options)
    swapped = None
    if RADICAL(picked):
        better = sorted(u for u in options if IDEOGRAPH(u))
        if better:
            swapped = better[0]
            picked = better[0]
    return picked, swapped


def build():
    for path in (SRC_CID2CODE, SRC_UTF16, SRC_LICENSE):
        if not os.path.exists(path):
            sys.exit("缺源文件 %s\n先取（这台电脑走 clash 的代理）：\n"
                     "  Invoke-WebRequest -Proxy http://127.0.0.1:7897 "
                     "https://raw.githubusercontent.com/adobe-type-tools/cmap-resources/master/Adobe-GB1-6/cid2code.txt "
                     "-OutFile third_party/cmap-resources/Adobe-GB1/cid2code.txt" % os.path.relpath(path, ROOT))
    canonical = read_cid2code(SRC_CID2CODE)
    candidates, entries, skipped = read_cmap_inverted_counts = read_cmap_candidates(SRC_UTF16)
    table = {}
    swapped = []
    disagree = []
    for cid in sorted(set(canonical) | set(candidates)):
        can = canonical.get(cid)
        cand = candidates.get(cid, set())
        if can is not None and cand and can not in cand:
            disagree.append(cid)
        picked, swap = choose(can, cand)
        table[cid] = picked
        if swap is not None:
            swapped.append((cid, can, swap))
    multi = sum(1 for cid, v in candidates.items() if len(v) > 1)
    print("源文件 cid2code.txt=%s UniGB-UTF16-H=%s" % (sha256(SRC_CID2CODE), sha256(SRC_UTF16)))
    print("Adobe 官方 CID→码位：%d 个（最大 CID %d）" % (len(canonical), max(canonical)))
    print("UniGB-UTF16-H 反转：映射条目 %d 条 → 覆盖 CID %d 个（同一 CID 多个码位的 %d 个，解不出的 %d 条）"
          % (entries, len(candidates), multi, skipped))
    print("两个来源对不上的 CID：%d 个（以 cid2code.txt 为准）%s" % (len(disagree), disagree[:6]))
    print("部首改取汉字本体：%d 个 CID %s" % (len(swapped), [(c, "%04X→%04X" % (a, b)) for c, a, b in swapped[:5]]))
    return table


def pack(ordering, table):
    max_cid = max(table)
    bmp = [0] * (max_cid + 1)
    extra = []
    for cid, point in table.items():
        if point <= 0xFFFF and not (0xD800 <= point <= 0xDFFF):
            bmp[cid] = point
        else:
            extra.append((cid, point))
    extra.sort()
    out = bytearray(b"WLCM")
    out += bytes([1, 1])
    name = ordering.encode("ascii")
    out += bytes([len(name)]) + name
    out += max_cid.to_bytes(2, "big")
    for unit in bmp:
        out += unit.to_bytes(2, "big")
    out += len(extra).to_bytes(2, "big")
    for cid, point in extra:
        out += cid.to_bytes(2, "big") + point.to_bytes(4, "big")
    return bytes(out), len(bmp), len(extra)


def main():
    check = "--check" in sys.argv
    table = build()
    blob, rows, extras = pack(ORDERING, table)
    packed = zlib.compress(blob, 9)
    print("表：CID 槽位 %d，其中 U+FFFF 以上 %d 个" % (rows, extras))
    print("产物 adobe-gb1.cid 原样 %d 字节；同样内容 deflate 后 %d 字节（APK 里按压缩存放）" % (len(blob), len(packed)))
    print("抽查 CID→字：" + " ".join("CID%d=U+%04X" % (c, table[c])
          for c in (1, 4162, 4559, 30571) if c in table))
    if check:
        return
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(OUT, "wb") as handle:
        handle.write(blob)
    print("写了 %s（%d 字节）" % (os.path.relpath(OUT, ROOT).replace("\\", "/"), len(blob)))
    header = ("本文件前半部照抄 third_party/cmap-resources/LICENSE.md（Adobe 的 BSD 3 条款）。\n"
              "对应随包文件 app/src/main/assets/cmaps/adobe-gb1.cid：\n"
              "  内容 = Adobe-GB1 字符集的 CID→Unicode 对照表（%d 个 CID）\n"
              "  来源 = https://github.com/adobe-type-tools/cmap-resources 的 Adobe-GB1-6/cid2code.txt\n"
              "         与 Adobe-GB1-6/CMap/UniGB-UTF16-H\n"
              "  改写 = 214 个 CID 的规范码位是康熙部首（U+2F00..），表里换成同一个 CID 上的汉字本体\n"
              "         （两者是 Unicode NFKC 等价；方正 PDF 在这些码位上画的就是汉字本体，\n"
              "         取部首会把「一」读成「⼀」，查重对不上）\n"
              "  重建 = py tools/build-cmaps.py\n\n" % len(table))
    with open(OUT_LICENSE, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(header + open(SRC_LICENSE, encoding="utf-8").read())
    print("写了 %s" % os.path.relpath(OUT_LICENSE, ROOT).replace("\\", "/"))


main()
