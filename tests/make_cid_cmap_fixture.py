'''CID 字体 PDF 夹具生成器（闸门里的三条断言靠它）。
只给内嵌字体放一张 cmap 表：app 的兜底路径就只读 cmap，别的表它不碰。
真值由构造决定——码就是字形号，字形号对着 cmap 里写的那个字。'''
import struct, zlib, os

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