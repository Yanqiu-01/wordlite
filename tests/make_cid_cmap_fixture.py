'''CID 字体 PDF 夹具生成器（闸门里的三条断言靠它）。
只给内嵌字体放一张 cmap 表：app 的兜底路径就只读 cmap，别的表它不碰。
真值由构造决定——码就是字形号，字形号对着 cmap 里写的那个字。'''
import struct, zlib, os, io

OUT = os.path.dirname(os.path.abspath(__file__))

BODY_LINES = [
    "等温凝固时间过长会让界面出现孔洞，孔隙率随之上升。",
    "多孔铜骨架与液相之间的反应面积决定了金属间化合物的厚度。",
    "连接完成后接头导电率保持在骨架与焊料两者之间。",
]

def cmap_format4(pairs):
    pairs = sorted(pairs)
    segs = [(u, u, g - u) for u, g in pairs] + [(0xFFFF, 0xFFFF, 1)]
    segx2 = len(segs) * 2
    body = struct.pack(">HHHHHHH", 4, 0, 0, segx2, 2, 0, 0)
    body += b"".join(struct.pack(">H", e) for _, e, _ in segs)
    body += struct.pack(">H", 0)
    body += b"".join(struct.pack(">H", s) for s, _, _ in segs)
    body += b"".join(struct.pack(">H", d & 0xFFFF) for _, _, d in segs)
    body += b"".join(struct.pack(">H", 0) for _ in segs)
    body = body[:2] + struct.pack(">H", len(body)) + body[4:]
    return struct.pack(">HH", 0, 1) + struct.pack(">HHI", 3, 1, 12) + body

def fake_ttf(pairs):
    cmap = cmap_format4(pairs)
    # 表记录是 (校验和, 偏移, 长度)：偏移 28 = 12 字节 sfnt 头 + 16 字节的这一条记录
    head = struct.pack(">IHHHH", 0x00010000, 1, 16, 0, 3) + b"cmap" + struct.pack(">III", 0, 28, len(cmap))
    return head + cmap + b"\x00" * ((-((len(head) + len(cmap)) % 4)) % 4)

def hex_codes(gids):
    return "".join("%04X" % g for g in gids)

def pdf(font_bytes, lines_of_codes, comment):
    """lines_of_codes: 每行一串字形号，各自一个 BT/ET，行与行之间靠 Td 换行。"""
    fz = zlib.compress(font_bytes)
    content = bytearray()
    y = 150
    for gids in lines_of_codes:
        content += ("BT /C1 11 Tf 20 %d Td <%s> Tj ET\n" % (y, hex_codes(gids))).encode("latin1")
        y -= 20
    objs = [b"<< /Type /Catalog /Pages 2 0 R >>",
            b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 320 220] /Resources << /Font << /C1 4 0 R >> >>"
            b" /Contents 8 0 R >>",
            b"<< /Type /Font /Subtype /Type0 /BaseFont /FixtureCJK /Encoding /Identity-H /DescendantFonts [5 0 R] >>",
            b"<< /Type /Font /Subtype /CIDFontType2 /BaseFont /FixtureCJK /CIDSystemInfo << /Registry (Adobe)"
            b" /Ordering (Identity) /Supplement 0 >> /CIDToGIDMap /Identity /FontDescriptor 6 0 R /DW 1000 >>",
            b"<< /Type /FontDescriptor /FontName /FixtureCJK /Flags 4 /FontBBox [-100 -200 1000 900]"
            b" /ItalicAngle 0 /Ascent 800 /Descent -200 /CapHeight 700 /StemV 80 /FontFile2 7 0 R >>",
            ("<< /Length %d /Filter /FlateDecode >>\nstream\n" % len(fz)).encode("latin1") + fz + b"\nendstream",
            None]
    objs[7] = (("<< /Length %d >>\nstream\n" % len(content)).encode("latin1")
               + bytes(content) + b"\nendstream")
    out = bytearray(b"%PDF-1.4\n% " + comment.encode("latin1") + b"\n")
    offs = []
    for i, body in enumerate(objs, start=1):
        offs.append(len(out))
        out += ("%d 0 obj\n" % i).encode("latin1") + body + b"\nendobj\n"
    xref = len(out)
    out += ("xref\n0 %d\n" % (len(objs) + 1)).encode("latin1") + b"0000000000 65535 f \n"
    for off in offs:
        out += ("%010d 00000 n \n" % off).encode("latin1")
    out += ("trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, xref)).encode("latin1")
    return bytes(out)

def build(name, cmap_pairs, lines_of_codes, comment):
    data = pdf(fake_ttf(cmap_pairs), lines_of_codes, comment)
    with open(os.path.join(OUT, name), "wb") as handle:
        handle.write(data)
    print("wrote", name, len(data), "B")

# 1) 字体自带 cmap：码 1..4 = 铜 焊 孔 隙
GOOD = [(0x94DC, 1), (0x710A, 2), (0x5B54, 3), (0x9699, 4)]
build("fixture-cidcmap.pdf", GOOD, [[1, 2, 3, 4]], "cid font with its own cmap")
# 2) 同一份字体，但码 9/10 在 cmap 里没有 -> 必须报"读不出"，不许猜字
build("fixture-cidcmap-partial.pdf", GOOD, [[9, 10, 1, 2]], "cid font whose cmap lacks these codes")
# 3) 三行正文：一键下载那条链路的语料，真值就是这三行
pairs, order = [], 0
for line in BODY_LINES:
    for ch in line:
        order += 1
        pairs.append((ord(ch), order))
lines = []
base = 0
for line in BODY_LINES:
    lines.append(list(range(base + 1, base + 1 + len(line))))
    base += len(line)
build("fixture-oa-body.pdf", pairs, lines, "open access body for the download path")


# ---------------------------------------------------------------------------
# 4) 方正那一族：Type0 + /Identity-H + /CIDSystemInfo (Adobe, GB1)，没有 ToUnicode，
#    也没有内嵌字体文件——码到字的表不在这份 PDF 里，只有随包的 Adobe-GB1 表读得出来。
#    字形码直接取 Adobe 公布的 UniGB-UTF16-H 里"码位→CID"那一条，真值由构造决定。
# ---------------------------------------------------------------------------
import re as _re

UNIGB = os.path.join(os.path.dirname(OUT), "third_party", "cmap-resources", "Adobe-GB1", "UniGB-UTF16-H")


def unicode_to_cid():
    """UniGB-UTF16-H 写的就是码位→CID，原方向拿来出题最省事；同一个码位取碰到的第一个 CID。"""
    if not os.path.exists(UNIGB):
        return {}
    text = io.open(UNIGB, encoding="latin1").read().split("begincmap", 1)[1]
    out = {}
    for block in _re.findall(r"begincidchar(.*?)endcidchar", text, _re.S):
        for src, cid in _re.findall(r"<([0-9A-Fa-f]+)>\s+(\d+)", block):
            units = [int(src[i:i + 4], 16) for i in range(0, len(src), 4)]
            if len(units) == 2 and 0xD800 <= units[0] <= 0xDBFF:
                point = 0x10000 + ((units[0] - 0xD800) << 10) + (units[1] - 0xDC00)
            elif len(units) == 1:
                point = units[0]
            else:
                continue
            out.setdefault(point, int(cid))
    for block in _re.findall(r"begincidrange(.*?)endcidrange", text, _re.S):
        for lo, hi, base in _re.findall(r"<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s+(\d+)", block):
            lo, hi, base = int(lo, 16), int(hi, 16), int(base)
            for step in range(hi - lo + 1):
                out.setdefault(lo + step, base + step)
    return out


UCS_TO_CID = unicode_to_cid()


def gb1_pdf(ordering, supplement, lines_of_codes, comment, tag):
    """只给 CIDSystemInfo，不给 ToUnicode、不给内嵌字体：这份 PDF 自己答不出码是哪个字。"""
    content = bytearray()
    y = 150
    for codes in lines_of_codes:
        content += ("BT /C1 11 Tf 20 %d Td <%s> Tj ET\n" % (y, hex_codes(codes))).encode("latin1")
        y -= 20
    objs = [b"<< /Type /Catalog /Pages 2 0 R >>",
            b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 320 220] /Resources << /Font << /C1 4 0 R >> >>"
            b" /Contents 6 0 R >>",
            b"<< /Type /Font /Subtype /Type0 /BaseFont /FZCIDSJW--GB1-0 /Encoding /Identity-H"
            b" /DescendantFonts [5 0 R] >>",
            ("<< /Type /Font /Subtype /CIDFontType0 /BaseFont /FZCIDSJW--GB1-0 /CIDSystemInfo << /Registry (Adobe)"
             " /Ordering (%s) /Supplement %d >> /FontDescriptor 7 0 R /DW 1000 >>"
             % (ordering, supplement)).encode("latin1"),
            None,
            b"<< /Type /FontDescriptor /FontName /FZCIDSJW--GB1-0 /Flags 4 /FontBBox [-100 -200 1000 900]"
            b" /ItalicAngle 0 /Ascent 800 /Descent -200 /CapHeight 700 /StemV 80 >>"]
    objs[5] = (("<< /Length %d >>\nstream\n" % len(content)).encode("latin1")
               + bytes(content) + b"\nendstream")
    out = bytearray(b"%PDF-1.4\n% " + comment.encode("latin1") + b"\n")
    offs = []
    for i, body in enumerate(objs, start=1):
        if body is None:
            continue
        offs.append(len(out))
        out += ("%d 0 obj\n" % i).encode("latin1") + body + b"\nendobj\n"
    xref = len(out)
    out += ("xref\n0 %d\n" % (len(objs) + 1)).encode("latin1") + b"0000000000 65535 f \n"
    for off in offs:
        out += ("%010d 00000 n \n" % off).encode("latin1")
    out += ("trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n"
            % (len(objs) + 1, xref)).encode("latin1")
    name = "fixture-gb1-%s.pdf" % tag
    with open(os.path.join(OUT, name), "wb") as handle:
        handle.write(bytes(out))
    print("wrote %s %d B" % (name, len(out)))


if UCS_TO_CID:
    GB1_LINE = "碳纳米管网络在180℃下大量自组装成一根，孔隙率随之上升。"
    GB1_CODES = [UCS_TO_CID[ord(ch)] for ch in GB1_LINE]
    gb1_pdf("GB1", 4, [GB1_CODES], "Adobe-GB1 CIDs, no ToUnicode, not embedded", "gb1")
    # 5) 同一批码，但这份 PDF 声明 Ordering=Identity：码只是字形号，任何外来的表都不许套上去。
    gb1_pdf("Identity", 0, [GB1_CODES], "identity ordering: no external table may be applied", "identity")
    # 6) 康熙部首那个 CID（Adobe 官方列写 U+2F00，同一个 CID 也挂着「一」U+4E00）加 CID 0（notdef）：
    #    前者必须读成汉字本体，后者必须按读不出记账，不许蒙一个字。
    gb1_pdf("GB1", 4, [[4162, 0, UCS_TO_CID[ord("册")]]], "kangxi radical cid plus notdef", "radical")
    print("GB1 真值第一句：", GB1_LINE)
    print("GB1 字形码：", " ".join("%04X" % c for c in GB1_CODES[:6]), "...共 %d 个" % len(GB1_CODES))
else:
    print("skip gb1 fixtures: third_party/cmap-resources/Adobe-GB1/UniGB-UTF16-H not found")


# ---------------------------------------------------------------------------
# 7) 两页都用 /C1 这个名字，指的却是两张不同的脸：第 1 页是"只有 Adobe-GB1 表才读得出"的 CID 字体，
#    第 2 页是自带 cmap 的内嵌字体。按资源名缓存字体的实现会让第 2 页整页丢字
#    （真刊 scichina.pdf 就是这么少读 2,447 字的）。真值 = 两页都要在。
# ---------------------------------------------------------------------------
COLLIDE_A = "三维碳纳米管网络状结构"      # 第 1 页：Adobe-GB1 的 CID
COLLIDE_B = "接头导电率保持在两者之间"    # 第 2 页：内嵌字体 cmap 的字形号（这一句没有重复字）

if UCS_TO_CID:
    gids = list(range(1, len(COLLIDE_B) + 1))
    pairs_b = [(ord(ch), gid) for ch, gid in zip(COLLIDE_B, gids)]
    codes_a = [UCS_TO_CID[ord(ch)] for ch in COLLIDE_A]

    def two_page_collision():
        a = zlib.compress(b"")  # 占位，避免误用
        content_a = ("BT /C1 11 Tf 20 170 Td <%s> Tj ET\n" % hex_codes(codes_a)).encode("latin1")
        content_b = ("BT /C1 11 Tf 20 170 Td <%s> Tj ET\n" % hex_codes(gids)).encode("latin1")
        ttf = zlib.compress(fake_ttf(pairs_b))
        objs = {
            1: b"<< /Type /Catalog /Pages 2 0 R >>",
            2: b"<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",
            3: (b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 320 220] /Resources << /Font << /C1 5 0 R >> >>"
                b" /Contents 7 0 R >>"),
            4: (b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 320 220] /Resources << /Font << /C1 8 0 R >> >>"
                b" /Contents 9 0 R >>"),
            # 第 1 页的 /C1：Adobe-GB1 的 CID 字体，没有 ToUnicode、没有内嵌字体
            5: (b"<< /Type /Font /Subtype /Type0 /BaseFont /FZCIDSJW--GB1-0 /Encoding /Identity-H"
                b" /DescendantFonts [6 0 R] >>"),
            6: (b"<< /Type /Font /Subtype /CIDFontType0 /BaseFont /FZCIDSJW--GB1-0 /CIDSystemInfo << /Registry (Adobe)"
                b" /Ordering (GB1) /Supplement 4 >> /FontDescriptor 10 0 R /DW 1000 >>"),
            # 第 2 页的 /C1：自带 cmap 的内嵌 TrueType（Ordering=Identity，不许套外来的表）
            8: (b"<< /Type /Font /Subtype /Type0 /BaseFont /CollisionCJK /Encoding /Identity-H"
                b" /DescendantFonts [11 0 R] >>"),
            11: (b"<< /Type /Font /Subtype /CIDFontType2 /BaseFont /CollisionCJK /CIDSystemInfo << /Registry (Adobe)"
                 b" /Ordering (Identity) /Supplement 0 >> /CIDToGIDMap /Identity /FontDescriptor 12 0 R /DW 1000 >>"),
            10: (b"<< /Type /FontDescriptor /FontName /FZCIDSJW--GB1-0 /Flags 4 /FontBBox [-100 -200 1000 900]"
                 b" /ItalicAngle 0 /Ascent 800 /Descent -200 /CapHeight 700 /StemV 80 >>"),
            12: (b"<< /Type /FontDescriptor /FontName /CollisionCJK /Flags 4 /FontBBox [-100 -200 1000 900]"
                 b" /ItalicAngle 0 /Ascent 800 /Descent -200 /CapHeight 700 /StemV 80 /FontFile2 13 0 R >>"),
            13: (("<< /Length %d /Filter /FlateDecode >>\nstream\n" % len(ttf)).encode("latin1")
                 + ttf + b"\nendstream"),
            7: (("<< /Length %d >>\nstream\n" % len(content_a)).encode("latin1") + content_a + b"\nendstream"),
            9: (("<< /Length %d >>\nstream\n" % len(content_b)).encode("latin1") + content_b + b"\nendstream"),
        }
        out = bytearray(b"%PDF-1.4\n% two pages, same font key, different faces\n")
        offs = {}
        for num in sorted(objs):
            offs[num] = len(out)
            out += ("%d 0 obj\n" % num).encode("latin1") + objs[num] + b"\nendobj\n"
        top = max(objs)
        xref = len(out)
        out += ("xref\n0 %d\n" % (top + 1)).encode("latin1") + b"0000000000 65535 f \n"
        for num in range(1, top + 1):
            out += (("%010d 00000 n \n" % offs[num]).encode("latin1") if num in offs
                    else b"0000000000 65535 f \n")
        out += ("trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n"
                % (top + 1, xref)).encode("latin1")
        with open(os.path.join(OUT, "fixture-font-key-collision.pdf"), "wb") as handle:
            handle.write(bytes(out))
        print("wrote fixture-font-key-collision.pdf %d B" % len(out))
        print("   第 1 页真值:", COLLIDE_A, " 第 2 页真值:", COLLIDE_B)

    two_page_collision()
