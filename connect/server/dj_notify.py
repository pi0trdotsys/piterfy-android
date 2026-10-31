"""Delivery adapters. Credentials never enter the DJ session or public responses."""
import json
import os
import smtplib
import ssl
import time
import urllib.request
from email.message import EmailMessage


def email(music, subject, plain, html):
    conf=music.smtp_settings()
    if not music.MAIL_TO or not conf.get('host'):raise RuntimeError('mail_unconfigured')
    message=EmailMessage()
    message['Subject']=subject;message['To']=music.MAIL_TO
    message['From']=conf.get('from') or conf.get('user') or music.MAIL_TO
    message.set_content(plain);message.add_alternative(html,subtype='html')
    tls=conf.get('tls')=='on';starttls=(conf.get('tls_starttls') or 'on')!='off'
    port=int(conf.get('port') or (465 if tls and not starttls else 587 if tls else 25))
    with (smtplib.SMTP_SSL(conf['host'],port,timeout=20,context=ssl.create_default_context()) if tls and not starttls else smtplib.SMTP(conf['host'],port,timeout=20)) as server:
        if tls and starttls:server.starttls(context=ssl.create_default_context())
        if conf.get('user') and conf.get('auth')!='off':server.login(conf['user'],conf.get('password') or '')
        server.send_message(message)


def telegram(plain,link):
    token=os.environ.get('TELEGRAM_BOT_TOKEN');chat=os.environ.get('TELEGRAM_CHAT_ID')
    if not token or not chat:raise RuntimeError('telegram_unconfigured')
    payload=dict(chat_id=chat,text=plain[:3900],reply_markup={'inline_keyboard':[[{'text':'Oceń blok · OK / nie mój klimat','url':link}]]})
    request=urllib.request.Request('https://api.telegram.org/bot'+token+'/sendMessage',
        data=json.dumps(payload).encode(),headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(request,timeout=20) as response:
        if not json.load(response).get('ok'):raise RuntimeError('telegram_failed')


def loop(reviews,music):
    while True:
        try:
            for token,raw in reviews.pending():
                data=json.loads(raw)
                # Existing server-wide recipient credentials belong only to its configured user.
                if data['user']!=music.NAVIDROME_USER or not data.get('completed'):continue
                plain,html,link=reviews.report(token,data,os.environ.get('PITERFY_PUBLIC_URL','https://piterfy.prosinski.eu'))
                if data.get('email'):reviews.deliver(token,'email',lambda:email(music,'Piterfy DJ · '+data['block']['title'],plain,html))
                if data.get('telegram'):reviews.deliver(token,'telegram',lambda:telegram(plain,link))
        except Exception:
            print('DJ summary delivery unavailable; details omitted.',flush=True)
        time.sleep(10)
