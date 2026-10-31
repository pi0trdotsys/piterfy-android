"""Idempotent patch for the deployed daily daemon. Never disables daily reports."""
import ast


def patch(source):
    if 'import report_formatter\n' not in source:
        source=source.replace('from email.message import EmailMessage','from email.message import EmailMessage\nimport report_formatter',1)
    source=source.replace('def send_mail(subject, body):','def send_mail(subject, body, html_body=None):',1)
    if 'message.add_alternative(html_body' not in source:
        source=source.replace('    message.set_content(body)\n','    message.set_content(body)\n    if html_body:\n        message.add_alternative(html_body, subtype="html")\n',1)
    old='    send_mail(subject, "\\n".join(lines))'
    new='    send_mail(subject, report_formatter.generate_report_text(lines), report_formatter.generate_report_html(lines))'
    if old in source:source=source.replace(old,new,1)
    # Migrate an earlier HTML renderer too, keeping a readable plain-text alternative.
    source=source.replace('send_mail(subject, "\\n".join(lines), report_formatter.generate_report_html(lines))',new.strip())
    assert 'def send_mail(subject, body, html_body=None):' in source
    assert new in source
    ast.parse(source)
    return source
