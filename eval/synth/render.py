"""문서 블록을 텍스트와 PDF로 렌더링한다."""
from __future__ import annotations

from pathlib import Path

from .templates import Block, Heading, Para, Table


def to_text(blocks: list[Block]) -> str:
    out: list[str] = []
    for block in blocks:
        match block:
            case Heading(text):
                out.append(f"\n[{text}]")
            case Para(text):
                out.append(text)
            case Table(header, rows):
                out.append(" | ".join(header))
                out.extend(" | ".join(cell or "" for cell in row) for row in rows)
    return "\n".join(out).strip() + "\n"


def to_pdf(blocks: list[Block], path: Path, font_path: Path) -> None:
    # reportlab은 PDF가 필요할 때만 불러온다
    from reportlab.lib import colors
    from reportlab.lib.pagesizes import A4
    from reportlab.lib.styles import ParagraphStyle
    from reportlab.lib.units import mm
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.ttfonts import TTFont
    from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer
    from reportlab.platypus import Table as PdfTable
    from reportlab.platypus import TableStyle

    font = "SynthKorean"
    if font not in pdfmetrics.getRegisteredFontNames():
        pdfmetrics.registerFont(TTFont(font, str(font_path)))

    body = ParagraphStyle("body", fontName=font, fontSize=9, leading=13, wordWrap="CJK")
    head = ParagraphStyle("head", parent=body, fontSize=12, leading=18, spaceBefore=6)
    cell = ParagraphStyle("cell", parent=body, fontSize=8, leading=10)

    story = []
    for block in blocks:
        match block:
            case Heading(text):
                story.append(Paragraph(text, head))
            case Para(text):
                story.append(Paragraph(text, body))
                story.append(Spacer(1, 2 * mm))
            case Table(header, rows):
                data = [[Paragraph(c or "", cell) for c in header]]
                data += [[Paragraph(c or "", cell) for c in row] for row in rows]
                table = PdfTable(data, repeatRows=1)
                table.setStyle(TableStyle([
                    ("GRID", (0, 0), (-1, -1), 0.4, colors.grey),
                    ("BACKGROUND", (0, 0), (-1, 0), colors.whitesmoke),
                    ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
                ]))
                story.append(table)
                story.append(Spacer(1, 3 * mm))

    SimpleDocTemplate(str(path), pagesize=A4, leftMargin=15 * mm, rightMargin=15 * mm,
                      topMargin=15 * mm, bottomMargin=15 * mm,
                      title="합성 검진 결과지 (테스트용)").build(story)
