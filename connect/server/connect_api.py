"""Small adapter for the existing authenticated Piterfy profile HTTP service."""
import json
import os
from connect_core import Broker, Rejected

broker = Broker(os.environ.get("CONNECT_STATE_DIR", "/app/state/connect"))


def handle(handler):
    path = handler.route()
    headers = {"Cache-Control": "no-store"}
    if path not in ("/piterfy-api/connect/poll", "/piterfy-api/connect/command"):
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
        result = broker.poll(user, body) if path.endswith("/poll") else broker.command(user, body)
        handler.send(200, json.dumps(result, ensure_ascii=False), "application/json; charset=utf-8", headers)
    except Rejected as error:
        handler.send(error.status, json.dumps({"error": error.reason}), "application/json", headers)
    except (ValueError, TypeError, AttributeError, KeyError, IndexError):
        handler.send(400, '{"error":"invalid_body"}', "application/json", headers)
    except OSError:
        handler.send(503, '{"error":"storage_unavailable"}', "application/json", headers)
