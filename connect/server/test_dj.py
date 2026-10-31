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

    def test_refresh_changes_future_seeds_without_duplicate_job(self):
        first=self.start(mode='discover')
        updated=self.s.handle('a',dict(action='start',mode='discover',exploration=95,seeds=[dict(artist='New artist',genre='Phonk')]))
        self.assertEqual(first['id'],updated['id'])
        self.assertEqual(95,updated['exploration'])
        self.assertEqual('New artist',updated['seeds'][0]['artist'])
        self.assertIsNotNone(self.s.take());self.assertIsNone(self.s.take())

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

    def test_per_track_announcements_claim_independently_and_only_once(self):
        self.start();job=self.s.take()
        self.s.finish(job,dict(id='b',text='Intro',voiceKey='first',tracks=[dict(id='one'),dict(id='two')],announcements=[dict(track='two',text='Next',voiceKey='next')]))
        first=self.s.handle('a',dict(action='claim',block='b',track='one'))
        second=self.s.handle('a',dict(action='claim',block='b',track='two'))
        self.assertTrue(first['speak']);self.assertTrue(second['speak'])
        self.assertEqual('next',second['voiceKey'])
        self.assertFalse(self.s.handle('a',dict(action='claim',block='b',track='two'))['speak'])
        with self.assertRaises(Rejected):self.s.handle('a',dict(action='claim',block='b',track='not-in-block'))

    def test_preparation_progress_is_not_playable_until_complete(self):
        state=self.start();self.assertFalse(state['progress']['complete'])
        job=self.s.take();self.s.progress(job,'downloading',50,2,5)
        self.assertEqual(2,self.s.handle('a',{})['progress']['completed'])
        self.s.finish(job,dict(id='b',text='Hello',tracks=[dict(id='one')]),final=False)
        self.assertFalse(self.s.handle('a',{})['blocks'][0]['ready'])
        self.assertFalse(self.s.handle('a',dict(action='claim',block='b'))['speak'])
        self.s.finish(job,dict(id='ready',text='Ready',tracks=[dict(id='two')]))
        self.s.progress(job,'ready',100,5,5,complete=True)
        self.s.progress(job,'downloading',60,3,5)
        self.assertTrue(self.s.handle('a',{})['progress']['complete'])
        self.assertEqual(100,self.s.handle('a',{})['progress']['percent'])
