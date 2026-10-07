import base64, io, os, zipfile

PNG = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=')
W='http://schemas.openxmlformats.org/wordprocessingml/2006/main'
R='http://schemas.openxmlformats.org/officeDocument/2006/relationships'
REL='http://schemas.openxmlformats.org/package/2006/relationships'

def fixture(path):
    doc='''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="%s" xmlns:r="%s" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture" xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006">
<w:body>
<w:p><w:pPr><w:keepLines w:val="0"/><w:widowControl/><w:ind w:firstLine="0"/><w:spacing w:line="360" w:lineRule="auto"/></w:pPr>
<w:r><w:t>普通</w:t></w:r><w:r><w:rPr><w:color w:val="FF0000"/></w:rPr><w:t>红色</w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>粗体</w:t></w:r><w:r><w:rPr><w:i/><w:strike/></w:rPr><w:t>斜体</w:t></w:r><w:r><w:t>尾</w:t></w:r>
</w:p>
<w:p><w:r><w:t>literal </w:t></w:r>
<w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> PAGE </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>1</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r>
<w:r><w:t> / p=</w:t></w:r>
<w:fldSimple w:instr=" PAGE "><w:r><w:t>1</w:t></w:r></w:fldSimple>
<w:r><w:t> / title=</w:t></w:r>
<w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText>TITLE</w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>T</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r>
</w:p>
<w:p><w:commentRangeStart w:id="0"/><w:r><w:t>A</w:t><w:tab/><w:t>B</w:t><w:br/><w:t>C</w:t></w:r><w:commentRangeEnd w:id="0"/><w:r><w:commentReference w:id="0"/></w:r></w:p>
<w:p><mc:AlternateContent><mc:Choice Requires="wps"><w:r><w:drawing><wp:inline><wp:extent cx="952500" cy="952500"/><a:graphic><a:graphicData><pic:pic><pic:blipFill><a:blip r:embed="rIdImage"/></pic:blipFill></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></mc:Choice></mc:AlternateContent></w:p>
<w:tbl><w:tr><w:tc><w:p><w:r><w:t>A1</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>B1</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1800" w:bottom="1440" w:left="1800"/></w:sectPr>
</w:body></w:document>'''%(W,R)
    styles='''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="%s"><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:rPr><w:rFonts w:ascii="Arial" w:hAnsi="Arial" w:eastAsia="宋体"/><w:sz w:val="24"/></w:rPr></w:style></w:styles>'''%W
    comments='''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:comments xmlns:w="%s"><w:comment w:id="0" w:author="审阅者" w:date="2024-03-04T05:06:07Z"><w:p><w:r><w:t>请修改</w:t></w:r></w:p></w:comment></w:comments>'''%W
    rels='''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="%s"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>'''%REL
    drels='''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="%s"><Relationship Id="rIdStyles" Type="%s/styles" Target="styles.xml"/><Relationship Id="rIdComments" Type="%s/comments" Target="comments.xml"/><Relationship Id="rIdImage" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image.png"/></Relationships>'''%(REL,R,R)
    types='''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/word/comments.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml"/></Types>'''
    with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED) as z:
        z.writestr('[Content_Types].xml',types); z.writestr('_rels/.rels',rels); z.writestr('word/_rels/document.xml.rels',drels)
        z.writestr('word/document.xml',doc); z.writestr('word/styles.xml',styles); z.writestr('word/comments.xml',comments); z.writestr('word/media/image.png',PNG)

if __name__=='__main__':
    fixture('/workspace/wordlite/tests/fixture.docx')
    print('fixture written')
