import re
from html import escape


def generate_report_text(lines):
    """Readable fallback for text-only mail clients; no literal Markdown headings."""
    return '\n'.join(re.sub(r'^#{1,2} ', '', line).replace('- ', '• ', 1) if line.startswith(('# ', '## ', '- ')) else line for line in lines)


def generate_report_html(lines):
    """Render the saved report, preserving every status and explanation."""
    blocks, section_open, date_label, count = [], False, "", 0
    for line in lines:
        if line.startswith("# "):
            date_label = line.removeprefix("# Odkrywanie ")
        elif line.startswith("## "):
            if section_open:
                blocks.append("</table>")
            heading = line[3:]
            primary = heading.startswith("Pobrane (")
            if primary:
                match = re.search(r"\((\d+)\)", heading)
                count = int(match.group(1)) if match else 0
            color = "#1ed760" if primary else "#b3b3b3"
            blocks.append(f'<h2 style="margin:30px 0 12px;font-size:12px;line-height:1.4;letter-spacing:1.6px;text-transform:uppercase;color:{color};">{escape(heading)}</h2>'
                          '<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="border-collapse:collapse;">')
            section_open = True
        elif line.startswith("- "):
            track, _, reason = line[2:].partition("  ·  ")
            artist, separator, title = track.partition(" — ")
            title, artist = (title, artist) if separator else (artist, "")
            blocks.append('<tr><td style="padding:16px 0;border-bottom:1px solid #2a2a2a;overflow-wrap:anywhere;word-break:break-word;">'
                          f'<div style="font-size:17px;line-height:1.45;font-weight:700;letter-spacing:-0.2px;color:#ffffff;">{escape(title)}</div>'
                          f'<div style="margin-top:2px;font-size:14px;line-height:1.6;color:#b3b3b3;">{escape(artist)}</div>'
                          + (f'<div style="margin-top:6px;font-size:12px;line-height:1.6;color:#1ed760;">{escape(reason[:1].upper() + reason[1:])}</div>' if reason else "")
                          + "</td></tr>")
        elif line.strip():
            if section_open:
                blocks.append("</table>")
                section_open = False
            if line.startswith("Playlista"):
                blocks.append(f'<p style="margin:20px 0;padding:16px 18px;background-color:#1ed760;border-radius:10px;font-size:15px;line-height:1.6;color:#000000;"><strong>{escape(line)}</strong></p>')
            elif line.startswith("⚠"):
                blocks.append(f'<p style="padding:16px;background-color:#332b00;color:#ffe08a;font-size:14px;line-height:1.6;">{escape(line)}</p>')
            else:
                blocks.append(f'<p style="margin:16px 0;font-size:14px;line-height:1.7;color:#b3b3b3;">{escape(line)}</p>')
    if section_open:
        blocks.append("</table>")
    body = "".join(blocks)
    return f'''<!doctype html>
<html lang="pl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Piterfy · Twoje muzyczne odkrycia</title>
<style>@media only screen and (max-width:480px) {{ .content {{ padding:22px 18px !important; }} .outer {{ padding:12px 6px !important; }} h1 {{ font-size:28px !important; }} }}</style></head>
<body style="margin:0;padding:0;background-color:#000000;font-family:Arial,Helvetica,sans-serif;">
<div style="display:none;max-height:0;overflow:hidden;mso-hide:all;">Nowe utwory: {count}. Sprawdź dzisiejsze rekomendacje i ich inspiracje.</div>
<table role="presentation" width="100%" cellspacing="0" cellpadding="0" bgcolor="#000000"><tr><td class="outer" align="center" style="padding:32px 12px;">
<table role="presentation" width="100%" cellspacing="0" cellpadding="0" style="max-width:620px;background-color:#121212;border:1px solid #292929;border-radius:16px;overflow:hidden;">
<tr><td class="content" bgcolor="#191414" style="padding:38px 32px 34px;border-radius:16px 16px 0 0;background-color:#191414;background-image:radial-gradient(circle at 88% 0%,#1b5d35 0%,#191414 46%);">
<p style="margin:0 0 24px;font-size:11px;font-weight:700;letter-spacing:3px;color:#1ed760;">PITERFY / DAILY DISCOVERY</p>
<h1 style="margin:0;font-size:36px;line-height:1.1;letter-spacing:-1.4px;color:#ffffff;">Dzisiejszy<br>drop.</h1>
<p style="margin:20px 0 0;font-size:13px;line-height:1.5;color:#b3b3b3;">{escape(date_label)} &nbsp;·&nbsp; <strong style="color:#ffffff;">Nowe utwory: {count}</strong></p></td></tr>
<tr><td class="content" style="padding:16px 32px 28px;background-color:#121212;">{body}</td></tr>
<tr><td style="padding:22px 24px;background-color:#0b0b0b;border-top:1px solid #292929;text-align:center;font-size:12px;line-height:1.7;color:#8d8d8d;">Your taste, in motion.<br><strong style="color:#1ed760;">PITERFY</strong> · Daily Discovery</td></tr>
</table></td></tr></table></body></html>'''
