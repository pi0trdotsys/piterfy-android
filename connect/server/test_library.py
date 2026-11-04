import tempfile
import unittest
from library_core import Library
from connect_core import Rejected


class LibraryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.library = Library(self.temp.name)

    def call(self, action='get', user='one', **body):
        return self.library.handle(user, dict(action=action, **body))

    def test_account_isolation_and_persistence(self):
        self.call('settings', exploration=85)
        self.assertEqual(self.call(user='two')['exploration'], 50)
        self.assertEqual(Library(self.temp.name).handle('one', {})['exploration'], 85)

    def test_pin_is_account_scoped_idempotent_and_bounded(self):
        for _ in range(2): self.call('pin',id='song',enabled=True)
        self.assertEqual(['song'],self.call()['pins'])
        self.assertEqual([],self.call(user='two')['pins'])
        self.assertEqual(['song'],Library(self.temp.name).handle('one',{})['pins'])
        self.call('pin',id='song',enabled=False)
        self.assertEqual([],self.call()['pins'])
        with self.assertRaises(Rejected):self.call('pin',id='song',enabled=1)

    def test_track_dislike_does_not_penalize_the_whole_artist(self):
        self.call('feedback',id='a',artist='Artist',genre='Pop',vote=-1,reason='track',event='1')
        ranked=self.call('rank',items=[dict(id='b',artist='Artist',genre='Jazz'),dict(id='c',artist='Other',genre='Pop')])
        self.assertEqual(ranked['items'][0]['score'],ranked['items'][1]['score'])

    def test_genre_dislike_affects_genre_instead_of_other_work_by_artist(self):
        self.call('feedback',id='a',artist='Artist',genre='Pop',vote=-1,reason='genre',event='1')
        rows=self.call('rank',items=[dict(id='b',artist='Artist',genre='Jazz'),dict(id='c',artist='Other',genre='Pop')])['items']
        self.assertGreater(rows[0]['score'],rows[1]['score'])
        with self.assertRaises(Rejected):self.call('feedback',id='a',vote=-1,reason='invented',event='2')

    def test_reason_is_grounded_in_explicit_feedback(self):
        self.call('feedback',id='a',artist='Artist',vote=1,event='1')
        row=self.call('rank',items=[dict(id='b',artist='Artist')])['items'][0]
        self.assertIn('Artist',row['reason'])

    def test_feedback_changes_order_and_dislike_hides_song(self):
        self.call('feedback', id='a', artist='Artist', vote=1, event='1')
        self.call('feedback', id='b', artist='Other', vote=-1, event='2')
        result = self.call('rank', items=[{'id':'b','artist':'Other'}, {'id':'c','artist':'Artist'}, {'id':'d','artist':'Different'}])
        self.assertEqual([x['id'] for x in result['items']], ['c', 'd'])

    def test_music_ranking_excludes_untagged_podcasts(self):
        rows=self.call('rank',items=[dict(id='song',genre='Rock'),dict(id='ep',genre='',path='Podcasty/Raport/episode.mp3'),dict(id='typed',type='podcast')])['items']
        self.assertEqual(['song'],[row['id'] for row in rows])

    def test_skip_deduplication_and_early_skip_only(self):
        event = dict(id='a', artist='Artist', position=10000, duration=180000, event='1')
        self.call('skip', **event)
        self.call('skip', **event)
        self.assertEqual(self.call()['feedback']['a']['skips'], 1)
        self.call('skip', **dict(event, position=1000, event='2'))
        self.call('skip', **dict(event, position=100000, event='3'))
        self.assertEqual(self.call()['feedback']['a']['skips'], 1)

    def test_stale_device_cannot_overwrite_progress(self):
        self.call('progress', id='episode', duration=100000, position=40000, revision=0)
        result = self.call('progress', id='episode', duration=100000, position=10000, revision=0)
        self.assertTrue(result['conflict'])
        self.assertEqual(result['episode']['position'], 40000)

    def test_exploration_changes_familiarity_ranking(self):
        items = [{'id':'a','familiar':True}, {'id':'b'}]
        self.call('settings', exploration=100)
        self.assertEqual(self.call('rank', items=items)['items'][0]['id'], 'b')
        self.call('settings', exploration=0)
        self.assertEqual(self.call('rank', items=items)['items'][0]['id'], 'a')

    def test_invalid_values_and_subscription_limit(self):
        for value in [-1, 101, float('nan'), True, '10']:
            with self.assertRaises(Rejected):
                self.call('settings', exploration=value)
        self.call('subscribe', id='show', enabled=True)
        self.call('subscribe', id='show', enabled=True)
        self.assertEqual(self.call()['subscriptions'], ['show'])

    def test_accidental_skips_do_not_change_familiarity(self):
        self.call('skip', id='a', artist='Artist', position=500, duration=100000, event='one')
        self.assertEqual(self.call()['feedback'], {})

    def test_short_episode_is_not_completed_after_three_seconds(self):
        result = self.call('progress', id='short', position=3000, duration=10000, revision=0)
        self.assertFalse(result['episodes']['short']['completed'])

    def test_rejects_fractional_votes_and_boolean_revisions(self):
        with self.assertRaises(Rejected):
            self.call('feedback', id='a', artist='Artist', vote=.5, event='one')
        with self.assertRaises(Rejected):
            self.call('progress', id='a', position=1000, duration=10000, revision=True)
