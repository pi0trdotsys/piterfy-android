import json
import tempfile
import unittest
from dj_core import Sessions
from dj_reviews import Reviews
from connect_core import Rejected


class ReviewTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.sessions=Sessions(self.tmp.name);self.reviews=Reviews(self.sessions)
        self.state=self.sessions.handle('owner',{'action':'start','seeds':[{'artist':'Example'}]})
        self.sessions.finish(self.sessions.take(),{'id':'block','title':'Title <unsafe>','tracks':[{'id':'song','artist':'Example','title':'<script>bad</script>'}]})
        self.body=dict(session=self.state['id'],block='block')

    def token(self):
        return self.reviews.pending()[0][0]

    def test_completion_is_durable_and_account_scoped(self):
        with self.assertRaises(Rejected):self.reviews.complete('other',self.body)
        with self.assertRaises(Rejected):self.reviews.complete('owner',dict(self.body,session='old'))
        self.reviews.complete('owner',self.body);self.reviews.complete('owner',self.body)
        self.assertEqual(1,len(Reviews(Sessions(self.tmp.name)).pending()))

    def test_scanner_get_does_not_vote_and_html_is_escaped(self):
        self.reviews.complete('owner',self.body)
        page=self.reviews.page(self.token())
        self.assertIn('&lt;script&gt;',page);self.assertNotIn('<script>',page)
        self.assertIsNone(self.reviews.resolve(self.token())[1])
        self.assertEqual({},self.reviews.preferences(self.sessions.owner('owner')))

    def test_rating_can_be_changed_or_undone_without_accumulating(self):
        self.reviews.complete('owner',self.body);token=self.token()
        for _ in range(3):self.reviews.resolve(token,1)
        self.assertEqual(.5,self.reviews.preferences(self.sessions.owner('owner'))['example'])
        self.reviews.resolve(token,-1)
        self.assertEqual(-.5,self.reviews.preferences(self.sessions.owner('owner'))['example'])
        self.reviews.resolve(token,0)
        self.assertEqual(0,self.reviews.preferences(self.sessions.owner('owner'))['example'])
        self.assertEqual({},self.reviews.preferences(self.sessions.owner('other')))

    def test_feedback_before_completion_does_not_notify(self):
        self.reviews.complete('owner',self.body,notify=False)
        self.assertFalse(json.loads(self.reviews.pending()[0][1])['completed'])
        self.reviews.complete('owner',self.body)
        self.assertTrue(json.loads(self.reviews.pending()[0][1])['completed'])

    def test_channel_delivery_is_once_even_after_restart_or_error(self):
        self.reviews.complete('owner',self.body);token=self.token();calls=[]
        self.reviews.deliver(token,'email',lambda:calls.append('email'))
        Reviews(Sessions(self.tmp.name)).deliver(token,'email',lambda:calls.append('duplicate'))
        def fail():calls.append('telegram');raise OSError('timeout')
        self.reviews.deliver(token,'telegram',fail)
        self.reviews.deliver(token,'telegram',lambda:calls.append('retry'))
        self.assertEqual(['email','telegram'],calls)

    def test_notification_preferences_survive_session_restart(self):
        self.sessions.handle('owner',dict(action='settings',summaryEmail=False,summaryTelegram=True))
        self.sessions.handle('owner',dict(action='stop'))
        state=self.sessions.handle('owner',dict(action='start',seeds=[{'artist':'Example'}]))
        self.assertFalse(state['summaryEmail']);self.assertTrue(state['summaryTelegram'])

    def test_late_completion_after_restart_still_reports_old_block_once(self):
        self.sessions.handle('owner',dict(action='stop'))
        self.sessions.handle('owner',dict(action='start',seeds=[dict(artist='New')]))
        self.reviews.complete('owner',self.body)
        self.reviews.complete('owner',self.body)
        self.assertEqual(1,len(self.reviews.pending()))
        with self.assertRaises(Rejected):self.reviews.complete('other',self.body)
