import tempfile
import unittest
from unittest.mock import patch
from dj_buffer import BufferedSessions


class BufferTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.s=BufferedSessions(self.tmp.name)

    def fill(self):
        for i in range(3):
            job=self.s.take('library');self.assertIsNotNone(job)
            self.s.finish(job,dict(id=str(i),tracks=[dict(id='song'+str(i))]))

    def test_warm_is_silent_and_bounded_and_start_reuses_it(self):
        state=self.s.handle('a',dict(action='warm'));self.fill()
        state=self.s.handle('a',{})
        self.assertFalse(state['active']);self.assertEqual(2,state['buffer']['ready'])
        self.assertEqual(1,len(state['buffer']['alternatives']))
        self.assertIsNone(self.s.take('library'));self.assertIsNone(self.s.take())
        started=self.s.handle('a',dict(action='start'))
        self.assertEqual(state['id'],started['id']);self.assertEqual('0',started['startBlock'])

    def test_acquisition_does_not_block_local_lane(self):
        self.s.handle('a',dict(action='start'))
        acquired=self.s.take();self.assertIsNotNone(acquired)
        self.fill();self.assertIsNone(self.s.take('library'))
        self.s.finish(acquired,dict(id='new',tracks=[dict(id='new')]))
        state=self.s.handle('a',{})
        self.assertEqual(2,state['buffer']['ready']);self.assertEqual('new',state['blocks'][-1]['id'])

    def test_select_consumes_previous_and_replenishes_without_replay(self):
        state=self.s.handle('a',dict(action='start'));self.fill()
        self.s.handle('a',dict(action='select',session=state['id'],block='0'))
        current=self.s.handle('a',dict(action='select',session=state['id'],block='1'))
        self.assertNotEqual('0',current['startBlock'])
        self.assertTrue(next(b for b in current['blocks'] if b['id']=='0')['consumed'])
        self.assertIsNotNone(self.s.take('library'))

    def test_changed_taste_rejects_old_inflight_results(self):
        self.s.handle('a',dict(action='warm'));job=self.s.take('library')
        self.s.handle('a',dict(action='settings',exploration=90))
        self.assertFalse(self.s.valid(job));self.s.finish(job,dict(id='stale',tracks=[]))
        self.assertEqual([],self.s.handle('a',{})['blocks'])

    def test_isolation_and_stop_cancel_late_work(self):
        initial=self.s.handle('a',dict(action='start'));job=self.s.take('library')
        stopped=self.s.handle('a',dict(action='stop'));self.s.finish(job,dict(id='late',tracks=[]))
        self.assertNotEqual(initial['id'],stopped['id'])
        self.assertEqual([],self.s.handle('a',{})['blocks'])
        self.assertEqual([],self.s.handle('b',{})['blocks'])
        self.assertFalse(self.s.handle('a',dict(mode='discover'))['active'])

    def test_reserve_expiry_never_removes_current_block(self):
        state=self.s.handle('a',dict(action='start'));self.fill()
        self.s.handle('a',dict(action='select',session=state['id'],block='0'))
        import time
        with patch('dj_buffer.time.time',return_value=time.time()+25*3600):
            state=self.s.handle('a',{})
        self.assertEqual(['0'],[b['id'] for b in state['blocks']]);self.assertIsNone(state['startBlock'])

    def test_repeated_warm_does_not_duplicate_jobs(self):
        for _ in range(20):self.s.handle('a',dict(action='warm'))
        self.assertIsNotNone(self.s.take('library'));self.assertIsNone(self.s.take('library'))

    def test_feedback_preserves_playing_block_but_replaces_reserves(self):
        state=self.s.handle('a',dict(action='start'));self.fill()
        self.s.handle('a',dict(action='select',session=state['id'],block='0'))
        state=self.s.handle('a',dict(action='invalidate'))
        self.assertEqual('0',state['currentBlock']);self.assertEqual(['0'],[b['id'] for b in state['blocks']])
        self.assertIsNotNone(self.s.take('library'))

    def test_discoveries_cannot_grow_reserve_without_bound(self):
        self.s.handle('a',dict(action='start'));self.fill()
        for i in range(2):
            job=self.s.take();self.assertIsNotNone(job)
            self.s.finish(job,dict(id='new'+str(i),tracks=[dict(id='new-song'+str(i))]))
        state=self.s.handle('a',{})
        self.assertEqual(2,state['buffer']['ready']);self.assertEqual(3,len(self.s.available(state)))
        self.assertIsNone(self.s.take())

    def test_concurrent_lanes_cannot_publish_the_same_track_twice(self):
        self.s.handle('a',dict(action='start'));self.fill()
        self.s.finish(self.s.take(),dict(id='collision',tracks=[dict(id='song0')]))
        state=self.s.handle('a',{})
        self.assertNotIn('collision',[b['id'] for b in state['blocks']])

    def test_library_jobs_do_not_spend_acquisition_budget(self):
        self.s.handle('a',dict(action='start'));self.fill()
        for _ in range(8):
            job=self.s.take();self.assertIsNotNone(job)
            self.s.finish(job,error='no_new_tracks')
            self.s.handle('a',dict(action='refill'))
        self.assertIsNone(self.s.take())
        self.assertIsNotNone(self.s.handle('a',dict(action='start'))['startBlock'])
