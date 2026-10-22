"""Standalone Connect service; reachable only through Piterfy's existing HTTPS proxy."""
import base64
import json
import os
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from connect_api import handle


class Handler(BaseHTTPRequestHandler):
    server_version = "PiterfyConnect"

    def log_message(self, *_):
        pass  # Never log authentication headers or request bodies.

    def route(self):
        return self.path.split("?", 1)[0]

    def send(self, code, body=b"", ctype="application/json", headers=None):
        body = body.encode("utf-8") if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for key, value in (headers or {"Cache-Control": "no-store"}).items():
            self.send_header(key, value)
        self.end_headers()
        if body:
            self.wfile.write(body)

    def authorized_user(self):
        auth = self.headers.get("X-ND-Authorization", "")
        try:
            if not auth.startswith("Bearer ") or len(auth) > 8192:
                raise ValueError()
            # Validate signature, expiration and account access with Navidrome before reading sub.
            url = os.environ.get("NAVIDROME_URL", "http://piterfy-navidrome:4533").rstrip("/")
            request = urllib.request.Request(url + "/api/song?_start=0&_end=1", headers={"X-ND-Authorization": auth})
            with urllib.request.urlopen(request, timeout=5) as response:
                if response.status != 200:
                    raise ValueError()
            payload = auth.split(" ", 1)[1].split(".")[1]
            user = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4))).get("sub")
            if not isinstance(user, str) or not user:
                raise ValueError()
            return user
        except (OSError, ValueError, IndexError):
            self.close_connection = True
            self.send(401, '{"error":"unauthorized"}')
            return None

    do_POST = handle

    def do_GET(self):
        if self.route() == "/health":
            self.send(200, '{"ok":true,"protocol":1}')
        else:
            self.send(404)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8091), Handler).serve_forever()
