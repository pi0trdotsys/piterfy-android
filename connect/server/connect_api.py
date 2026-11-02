"""Small adapter for the existing authenticated Piterfy profile HTTP service."""
import json
import os
import urllib.request
import urllib.error
import urllib.parse
from connect_core import Broker, Rejected
from library_core import Library

broker = Broker(os.environ.get("CONNECT_STATE_DIR", "/app/state/connect"))
library = Library(os.environ.get("CONNECT_STATE_DIR", "/app/state/connect"))


def review_page(handler):
    """An expiring, unguessable capability. Mail scanners can GET without voting."""
    headers={'Cache-Control':'no-store','Referrer-Policy':'no-referrer',
             'Content-Security-Policy':"default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"}
    try:
        if handler.command=='POST':
            size=int(handler.headers.get('Content-Length','0'))
            if not 0<size<=2048:raise Rejected('body_limit',413)
            fields=urllib.parse.parse_qs(handler.rfile.read(size).decode())
            vote=int(fields.get('vote',[''])[0])
        elif handler.command=='GET':
            fields=urllib.parse.parse_qs(urllib.parse.urlsplit(handler.path).query)
            vote=None
        else:raise Rejected('method_not_allowed',405)
        token=fields.get('token',[''])[0]
        body=dict(action='review-page',token=token)
        if vote is not None:body['vote']=vote
        request=urllib.request.Request(os.environ.get('DJ_URL','http://piterfy-dj:8092'),
            data=json.dumps({'user':'review-capability','request':body}).encode(),headers={'Content-Type':'application/json'})
        with urllib.request.urlopen(request,timeout=8) as response:result=json.loads(response.read(65536))
        handler.send(200,result['html'],'text/html; charset=utf-8',headers)
    except (Rejected,urllib.error.HTTPError,ValueError,KeyError) as error:
        handler.send(getattr(error,'status',getattr(error,'code',400)),'Ten link oceny wygasł lub jest nieprawidłowy.','text/plain; charset=utf-8',headers)
    except OSError:
        handler.send(503,'Ocena jest chwilowo niedostępna. Spróbuj ponownie.','text/plain; charset=utf-8',headers)


def handle(handler):
    path = handler.route()
    if path=='/piterfy-api/connect/dj-feedback':return review_page(handler)
    headers = {"Cache-Control": "no-store"}
    if path not in ("/piterfy-api/connect/poll", "/piterfy-api/connect/command", "/piterfy-api/connect/library", "/piterfy-api/connect/dj"):
        return handler.send(404)
    # Token is checked by Navidrome; the client cannot choose an account identifier.
    user = handler.authorized_user()
    if not user:
        return
    try:
        length = int(handler.headers.get("Content-Length", "0"))
        if not 0 < length <= 1024 * 1024:
            handler.close_connection = True
            raise Rejected("body_limit", 413)
        body = json.loads(handler.rfile.read(length))
        if not isinstance(body, dict):
            raise Rejected("invalid_body", 400)
        if path.endswith('/dj'):
            if body.get('action') == 'review-page':raise Rejected('unknown_action',400)
            if body.get('action') in ('claim', 'audio', 'complete', 'select'):
                with broker.lock:
                    account = broker._account(user)
                    broker._expire(account)
                    if account['pending'] or (account['owner'] and account['owner'] != body.get('device')):
                        raise Rejected('receiver_required', 409)
            request = urllib.request.Request(os.environ.get('DJ_URL', 'http://piterfy-dj:8092'),
                data=json.dumps({'user': user, 'request': body}).encode(), headers={'Content-Type': 'application/json'})
            try:
                with urllib.request.urlopen(request, timeout=8) as response:
                    result = json.loads(response.read(4*1024*1024))
            except urllib.error.HTTPError as error:
                return handler.send(error.code, error.read(4096), 'application/json', headers)
            except (OSError, ValueError):
                raise Rejected('dj_unavailable', 503)
        else:
            result = library.handle(user, body) if path.endswith('/library') else (
            broker.poll(user, body) if path.endswith("/poll") else broker.command(user, body))
        handler.send(200, json.dumps(result, ensure_ascii=False), "application/json; charset=utf-8", headers)
    except Rejected as error:
        handler.send(error.status, json.dumps({"error": error.reason}), "application/json", headers)
    except (ValueError, TypeError, AttributeError, KeyError, IndexError):
        handler.send(400, '{"error":"invalid_body"}', "application/json", headers)
    except OSError:
        handler.send(503, '{"error":"storage_unavailable"}', "application/json", headers)
