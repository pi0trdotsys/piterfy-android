import io
import json
import tempfile
import unittest
from unittest.mock import patch
import connect_api
from connect_core import Broker


class Handler:
    def __init__(self, body, user='real-account'):
        payload = json.dumps(body).encode()
        self.headers = {'Content-Length': str(len(payload))}
        self.rfile = io.BytesIO(payload)
        self.user = user
        self.code = None
    def route(self): return '/piterfy-api/connect/dj'
    def authorized_user(self):
        if self.user is None: self.send(401)
        return self.user
    def send(self, code, *args): self.code = code


class DJApiTest(unittest.TestCase):
    def test_client_cannot_select_another_account(self):
        handler = Handler({'action': 'get', 'user': 'victim'})
        response = unittest.mock.MagicMock()
        response.__enter__.return_value.read.return_value = b'{"active":false}'
        with patch('urllib.request.urlopen', return_value=response) as send:
            connect_api.handle(handler)
            forwarded = json.loads(send.call_args.args[0].data)
        self.assertEqual('real-account', forwarded['user'])
        self.assertEqual(200, handler.code)

    def test_unauthenticated_requests_never_reach_worker(self):
        with patch('urllib.request.urlopen') as send:
            handler = Handler({'action': 'start'}, user=None)
            connect_api.handle(handler)
            send.assert_not_called()
        self.assertEqual(401, handler.code)

    def test_remote_controller_cannot_claim_receiver_speech(self):
        with tempfile.TemporaryDirectory() as directory:
            broker = Broker(directory)
            broker.poll('real-account', {'device': {'id': 'phone'}, 'report': {'playing': True, 'items': [{'id': 'song', 'duration': 60}]}})
            handler = Handler({'action': 'claim', 'device': 'browser', 'block': 'block'})
            with patch.object(connect_api, 'broker', broker), patch('urllib.request.urlopen') as send:
                connect_api.handle(handler)
                send.assert_not_called()
            self.assertEqual(409, handler.code)
