import random
import tempfile
import unittest

import radio_core
from dj_buffer import BufferedSessions


def candidate(artist, title, via, score=1.0):
    return dict(artist=artist, title=title, via=via, score=score, key=artist + title)


class OrderTest(unittest.TestCase):
    def test_interleaves_seeds_instead_of_exhausting_the_first_cluster(self):
        rows = [candidate('A%d' % i, 't', 'seed one', 5 - i * .1) for i in range(10)]
        rows += [candidate('B%d' % i, 't', 'seed two', 2 - i * .1) for i in range(10)]
        picked = radio_core.order_candidates(rows, random.Random(1), limit=6)
        vias = [row['via'] for row in picked]
        self.assertEqual({'seed one', 'seed two'}, set(vias))
        self.assertEqual(3, vias.count('seed one'))

    def test_one_song_per_artist_and_featured_names_collapse(self):
        rows = [candidate('Daft Punk', 'One More Time', 'x'), candidate('Daft Punk feat. Romanthony', 'Too Long', 'x'),
                candidate('Justice', 'D.A.N.C.E.', 'x')]
        picked = radio_core.order_candidates(rows, random.Random(2))
        self.assertEqual(2, len(picked))

    def test_blocked_artists_and_blank_rows_are_dropped(self):
        rows = [candidate('Blocked', 't', 'x'), candidate('', 't', 'x'), candidate('Ok', '', 'x'), candidate('Fine', 't', 'x')]
        self.assertEqual(['Fine'], [r['artist'] for r in radio_core.order_candidates(rows, random.Random(3), blocked=['blocked'])])

    def test_order_varies_between_runs(self):
        rows = [candidate('Artist%d' % i, 't', 'seed', 1.0) for i in range(30)]
        first = [r['artist'] for r in radio_core.order_candidates(rows, random.Random(4), limit=10)]
        second = [r['artist'] for r in radio_core.order_candidates(rows, random.Random(5), limit=10)]
        self.assertNotEqual(first, second)

    def test_recent_seeds_weigh_more(self):
        self.assertGreater(radio_core.seed_weight(0), radio_core.seed_weight(3))
        self.assertEqual(radio_core.seed_weight(99), radio_core.SEED_WEIGHTS[-1])

    def test_input_is_not_mutated(self):
        rows = [candidate('A', 't', 'x')]
        radio_core.order_candidates(rows, random.Random(6))
        self.assertNotIn('_score', rows[0])


class RadioModeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.s = BufferedSessions(self.tmp.name)

    def test_radio_never_builds_library_blocks_or_voice(self):
        state = self.s.handle('a', dict(mode='radio', action='start', seeds=[dict(id='1', artist='X', title='Y', genre='Rock')]))
        self.assertTrue(state['active'])
        self.assertFalse(state['voice'])
        self.assertFalse(state['summaryEmail'])
        self.assertIsNone(self.s.take('library'))
        self.assertIsNotNone(self.s.take('acquire'))

    def test_radio_is_separate_from_dj_and_discover(self):
        self.s.handle('a', dict(mode='radio', action='start', seeds=[dict(artist='X', title='Y')]))
        self.assertFalse(self.s.handle('a', dict(mode='dj'))['active'])
        self.assertFalse(self.s.handle('a', dict(mode='discover'))['active'])

    def test_radio_hourly_cap_keeps_the_download_budget_for_other_modes(self):
        start = dict(mode='radio', action='start', seeds=[dict(artist='X', title='Y')])
        state = self.s.handle('a', start)
        for n in range(radio_core.RADIO_JOBS_PER_HOUR):
            job = self.s.take('acquire')
            self.assertIsNotNone(job, n)
            self.s.finish(job, error='no_new_tracks')
            self.s.handle('a', dict(mode='radio', action='refill'))
        self.assertIsNone(self.s.take('acquire'))
        self.assertEqual(state['id'], self.s.handle('a', dict(mode='radio'))['id'])

    def test_ready_discovery_block_is_selectable_and_frees_capacity(self):
        state = self.s.handle('a', dict(mode='radio', action='start', seeds=[dict(artist='X', title='Y')]))
        job = self.s.take('acquire')
        self.s.finish(job, dict(id='new', tracks=[dict(id='s1', artist='N', title='T')]))
        ready = self.s.handle('a', dict(mode='radio'))
        self.assertEqual('new', ready['startBlock'])
        picked = self.s.handle('a', dict(mode='radio', action='select', session=state['id'], block='new'))
        self.assertEqual('new', picked['currentBlock'])
        self.assertIsNone(picked['startBlock'])

    def test_unknown_mode_is_still_rejected(self):
        from connect_core import Rejected
        with self.assertRaises(Rejected):
            self.s.handle('a', dict(mode='other', action='get'))


if __name__ == '__main__':
    unittest.main()
