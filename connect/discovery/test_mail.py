import ast
import unittest
from unittest.mock import MagicMock,patch
from patch_mail import patch as patch_source
import report_formatter


class MailTest(unittest.TestCase):
    def test_html_escapes_metadata_and_plain_fallback_has_no_markdown(self):
        lines=['# Odkrywanie 2026-10-31','## Pobrane (1)','- Artist <script> — A & B  ·  bo słuchasz: Test']
        self.assertIn('&lt;script&gt;',report_formatter.generate_report_html(lines))
        text=report_formatter.generate_report_text(lines)
        self.assertNotIn('#',text);self.assertIn('• Artist',text)

    def test_patch_preserves_daily_send_and_multipart(self):
        source='''from email.message import EmailMessage
def send_mail(subject, body):
    message = EmailMessage()
    message.set_content(body)
    return message
def daily(subject,lines):
    send_mail(subject, "\\n".join(lines))
'''
        updated=patch_source(source)
        self.assertEqual(updated,patch_source(updated))
        ns={};exec(updated,ns)
        message=ns['send_mail']('test','plain','<p>formatted</p>')
        self.assertEqual(['text/plain','text/html'],[part.get_content_type() for part in message.iter_parts()])
        with patch.dict(ns,send_mail=MagicMock()) as patched:
            ns['daily']('report',['# Odkrywanie Test','## Pobrane (0)'])
            ns['send_mail'].assert_called_once()
