"""自建库导入回归用的合成 PDF。和 make_fixture.py 造 docx 同一套做法：不联网、不装第三方库。

四个夹具各管一件事：
  fixture-cn.pdf         Type0 + /UniGB-UCS2-H，正文是 UCS-2 大端码，两页
  fixture-tounicode.pdf  Type0 + /Identity-H + ToUnicode bfchar，和 PdfTrueType 写出的形状一致
  fixture-scanned.pdf    一页只有一张整页位图，一个文字算子都没有 -> 扫描版
  fixture-encrypted.pdf  trailer 里挂着 /Encrypt，本仓库不做解密，必须如实报错

正文取自 tests/corpus/real-prose.txt 的真实段落（按关键词取行，不写死整句），
这样"同一正文的 TXT 与 PDF 互为重复"这条断言才是在真中文上跑的。
注意：夹具不嵌字体程序（TextCorpus 侧的抽取只读 /Encoding 与 /ToUnicode，不读 FontFile2），
别拿 tests/inspect_pdf.py 那套"导出件必须内嵌字体"的标准来要求输入侧夹具。
"""
import os
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
CORPUS = os.path.join(HERE, 'corpus', 'real-prose.txt')
MEDIA_BOX = '[0 0 595 842]'
LINE_WIDTH = 28
LINE_HEIGHT = 16.0


def prose(keyword):
    with open(CORPUS, encoding='utf-8') as handle:
        for line in handle:
            if keyword in line:
                return line.strip()
    raise SystemExit('corpus line not found: ' + keyword)


def rows(text, width=LINE_WIDTH):
    """固定字数断行，模拟排好版的行；行与行 y 坐标不同，抽取端靠 y 变化判换行。"""
    return [text[start:start + width] for start in range(0, len(text), width)]


def text_block(lines, encoder):
    out = []
    y = 780.0
    for line in lines:
        payload = hex_string(encoder(line))
        out.append(b'BT /F1 11 Tf 1 0 0 1 56 ' + ('%.2f' % y).encode('ascii') + b' Tm ' + payload + b' Tj ET\n')
        y -= LINE_HEIGHT
    return b''.join(out)


def hex_string(payload):
    return b'<' + payload.hex().upper().encode('ascii') + b'>'


class Pdf(object):
    def __init__(self):
        self.objects = {}

    def put(self, num, body):
        self.objects[num] = body if isinstance(body, bytes) else body.encode('latin-1')

    def stream(self, num, dictionary, payload):
        raw = zlib.compress(payload)
        head = ('<< %s /Filter /FlateDecode /Length %d >>' % (dictionary, len(raw))).encode('latin-1')
        self.objects[num] = head + b'\nstream\n' + raw + b'\nendstream'

    def size(self):
        return max(self.objects) + 1

    def save(self, path, root=1, trailer_extra=''):
        out = bytearray(b'%PDF-1.7\n%\xe2\xe3\xcf\xd3\n')
        offsets = {}
        for num in sorted(self.objects):
            offsets[num] = len(out)
            out += ('%d 0 obj\n' % num).encode('ascii') + self.objects[num] + b'\nendobj\n'
        size = self.size()
        assert 1 in offsets, 'catalog must exist'
        xref = len(out)
        out += ('xref\n0 %d\n0000000000 65535 f \n' % size).encode('ascii')
        for num in range(1, size):
            # 空号写成熟的空闲目（真实 PDF 删过对象就有空号），不许写成 offset 0 的假表。
            out += (('%010d 00000 n \n' % offsets[num]) if num in offsets else '0000000000 65535 f \n').encode('ascii')
        out += ('trailer\n<< /Size %d /Root %d 0 R%s >>\nstartxref\n%d\n%%%%EOF\n'
                % (size, root, trailer_extra, xref)).encode('ascii')
        with open(path, 'wb') as handle:
            handle.write(bytes(out))
        return len(out)


class Skeleton(object):
    """1 Catalog / 2 Pages / 页面从 3 开始 / 内容流紧随其后 / 字体再往后，编号不留空。"""

    def __init__(self, pdf, contents, xobjects=0):
        count = len(contents)
        self.first_page = 3
        self.first_content = 3 + count
        self.font = 3 + 2 * count
        self.first_xobject = self.font + 3
        kids = ' '.join('%d 0 R' % (self.first_page + i) for i in range(count))
        pdf.put(1, '<< /Type /Catalog /Pages 2 0 R >>')
        pdf.put(2, '<< /Type /Pages /Count %d /Kids [%s] >>' % (count, kids))
        for i, content in enumerate(contents):
            extra = ''
            if xobjects:
                cells = ' '.join('/Im%d %d 0 R' % (j + 1, self.first_xobject + j) for j in range(xobjects))
                extra = ' /XObject << %s >>' % cells
            pdf.put(self.first_page + i,
                    '<< /Type /Page /Parent 2 0 R /MediaBox %s '
                    '/Resources << /Font << /F1 %d 0 R >>%s >> /Contents %d 0 R >>'
                    % (MEDIA_BOX, self.font, extra, self.first_content + i))
        for i, content in enumerate(contents):
            pdf.stream(self.first_content + i, '', content)


def font_ucs2(pdf, num):
    pdf.put(num, '<< /Type /Font /Subtype /Type0 /BaseFont /STSong-Light /Encoding /UniGB-UCS2-H '
                 '/DescendantFonts [%d 0 R] >>' % (num + 1))
    pdf.put(num + 1, '<< /Type /Font /Subtype /CIDFontType0 /BaseFont /STSong-Light '
                     '/CIDSystemInfo << /Registry (Adobe) /Ordering (GB1) /Supplement 5 >> '
                     '/FontDescriptor %d 0 R /DW 1000 >>' % (num + 2))
    pdf.put(num + 2, '<< /Type /FontDescriptor /FontName /STSong-Light /Flags 4 '
                     '/FontBBox [-25 -254 1000 880] /ItalicAngle 0 /Ascent 880 /Descent -254 '
                     '/CapHeight 737 /StemV 58 >>')


def font_tounicode(pdf, num, mapping):
    """照 PdfTrueType.finish() 的形状写：Type0 /Identity-H + CIDFontType2 + ToUnicode bfchar。"""
    pdf.put(num, '<< /Type /Font /Subtype /Type0 /BaseFont /WordLiteFixture /Encoding /Identity-H '
                 '/DescendantFonts [%d 0 R] /ToUnicode %d 0 R >>' % (num + 1, num + 3))
    pdf.put(num + 1, '<< /Type /Font /Subtype /CIDFontType2 /BaseFont /WordLiteFixture '
                     '/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> '
                     '/FontDescriptor %d 0 R /CIDToGIDMap /Identity /DW 1000 /W [1 [500] ] >>' % (num + 2))
    body = ['/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n'
            '/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n'
            '/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n'
            '1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n']
    items = sorted(mapping.items())
    for start in range(0, len(items), 100):
        batch = items[start:start + 100]
        body.append('%d beginbfchar\n' % len(batch))
        for code, character in batch:
            body.append('<%04X> <%s>\n' % (code, character.encode('utf-16-be').hex().upper()))
        body.append('endbfchar\n')
    body.append('endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n')
    pdf.stream(num + 3, '', ''.join(body).encode('latin-1'))
    pdf.put(num + 2, '<< /Type /FontDescriptor /FontName /WordLiteFixture /Flags 32 '
                     '/FontBBox [-10 -230 1000 880] /ItalicAngle 0 /Ascent 880 /Descent -230 '
                     '/CapHeight 880 /StemV 80 >>')


def glyph_encoder(glyphs):
    def encode(line):
        out = bytearray()
        for character in line:
            out += ('%04X' % glyphs[character]).encode('ascii')
        return bytes.fromhex(out.decode('ascii'))
    return encode


def build_text_pdf(path, mode):
    text_a = prose('宽禁带半导体')
    text_b = prose('低熔点金属')
    if mode == 'ucs2':
        encoder = lambda line: line.encode('utf-16-be')
    else:
        glyphs = {character: code for code, character in enumerate(sorted(set(text_a + text_b)), start=1)}
        encoder = glyph_encoder(glyphs)
    contents = [text_block(rows(text_a), encoder), text_block(rows(text_b), encoder)]
    pdf = Pdf()
    Skeleton(pdf, contents)
    if mode == 'ucs2':
        font_ucs2(pdf, pdf_font(pdf))
    else:
        font_tounicode(pdf, pdf_font(pdf), {code: character for character, code in glyphs.items()})
    return pdf.save(path), text_a, text_b


def pdf_font(pdf):
    # Skeleton 里字体占三个对象（Type0 / CIDFont / descriptor 或 ToUnicode），编号写在 pages 之前。
    pages = pdf.objects[2].decode('latin-1')
    count = int(pages.split('/Count ')[1].split(' ')[0])
    return 3 + 2 * count


def build_scanned(path):
    """整页只有一张位图，没有任何文字算子：这就是"扫描版"。"""
    pdf = Pdf()
    Skeleton(pdf, [b'q 595.2756 0 0 841.8898 0 0 cm /Im1 Do Q\n'], xobjects=1)
    image = 3 + 2 * 1 + 3   # Skeleton.first_xobject
    pdf.objects[image] = (b'<< /Type /XObject /Subtype /Image /Width 2 /Height 2 /ColorSpace /DeviceRGB '
                          b'/BitsPerComponent 8 /Length 12 >>\nstream\n' + bytes(range(12)) + b'\nendstream')
    pdf.put(pdf_font(pdf), '<< /Type /Font /Subtype /Type0 /BaseFont /NoText /Encoding /Identity-H '
                           '/DescendantFonts [%d 0 R] >>' % (pdf_font(pdf) + 1))
    pdf.put(pdf_font(pdf) + 1, '<< /Type /Font /Subtype /CIDFontType2 /BaseFont /NoText '
                               '/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> '
                               '/DW 1000 >>')
    return pdf.save(path)


def build_encrypted(path):
    """trailer 挂 /Encrypt 的假加密件：不做解密，抽取必须报"已加密"，不能给一份空正文充成功。"""
    pdf = Pdf()
    Skeleton(pdf, [b'BT /F1 11 Tf 1 0 0 1 56 780 Tm (hidden) Tj ET\n'])
    font_ucs2(pdf, pdf_font(pdf))
    return pdf.save(path, trailer_extra=' /Encrypt << /Filter /Standard /V 2 /R 3 /Length 128 /P -1340 '
                                        '/O <2f8b1a4c> /U <9a1cef02> >> /ID [<01> <01>]')


def make_fixtures(directory=HERE):
    written = {}
    written['fixture-cn.pdf'], text_a, text_b = build_text_pdf(os.path.join(directory, 'fixture-cn.pdf'), 'ucs2')
    written['fixture-tounicode.pdf'], _, _ = build_text_pdf(
        os.path.join(directory, 'fixture-tounicode.pdf'), 'identity')
    written['fixture-scanned.pdf'] = build_scanned(os.path.join(directory, 'fixture-scanned.pdf'))
    written['fixture-encrypted.pdf'] = build_encrypted(os.path.join(directory, 'fixture-encrypted.pdf'))
    for name in sorted(written):
        print('wrote', name, written[name], 'bytes')
    print('prose A chars', len(text_a), 'prose B chars', len(text_b))


if __name__ == '__main__':
    make_fixtures()