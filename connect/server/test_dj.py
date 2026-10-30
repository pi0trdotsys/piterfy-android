import tempfile
import unittest
from dj_core import Sessions
from connect_core import Rejected


class DJTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.s = Sessions(self.tmp.name)

    def start(self, user='a', mode='dj'):
        return self.s.handle(user, dict(action='start', mode=mode, seeds=[dict(artist='Example', title='Song')]))

    def test_accounts_and_modes_are_isolated(self):
        self.start()
        self.assertFalse(self.s.handle('b', {})['active'])
        self.assertFalse(self.s.handle('a', dict(mode='discover'))['active'])

    def test_idempotent_start_and_refill(self):
        first = self.start()
        self.assertEqual(first['id'], self.start()['id'])
        self.s.handle('a', dict(action='refill'))
        self.assertIsNotNone(self.s.take())
        self.assertIsNone(self.s.take())

    def test_stop_does_not_accept_old_worker_result(self):
        self.start(); job = self.s.take()
        self.s.handle('a', dict(action='stop'))
        self.start()
        self.s.finish(job, dict(id='old', text='old'))
        self.assertEqual([], self.s.handle('a', {})['blocks'])

    def test_announcement_claim_survives_restart(self):
        self.start(); job = self.s.take()
        self.s.finish(job, dict(id='block', text='Hello'))
        body = dict(action='claim', block='block')
        self.assertTrue(self.s.handle('a', body)['speak'])
        self.assertFalse(Sessions(self.tmp.name).handle('a', body)['speak'])

    def test_hourly_budget_cannot_be_reset_with_stop(self):
        for _ in range(8):
            self.start(); self.s.finish(self.s.take())
            self.s.handle('a', dict(action='stop'))
        self.assertEqual('hourly_limit', self.start()['status'])
        self.assertIsNone(self.s.take())

    def test_invalid_seed_and_voice(self):
        for body in [dict(action='start', seeds=[]), dict(action='settings', voice='yes'), dict(mode='daily')]:
            with self.assertRaises(Rejected): self.s.handle('a', body)

    def test_voice_opt_out_survives_start_and_restart(self):
        self.s.handle('a', dict(action='settings', voice=False))
        self.assertFalse(self.start()['voice'])
        self.s.handle('a', dict(action='stop'))
        self.assertFalse(self.start()['voice'])

    def test_stopped_session_cannot_deliver_late_introduction(self):
        self.start();job=self.s.take();self.s.finish(job,dict(id='block',text='Introduction'))
        self.s.handle('a',dict(action='stop'))
        self.assertFalse(self.s.handle('a',dict(action='claim',block='block'))['speak'])
