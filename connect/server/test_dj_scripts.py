import ast
from pathlib import Path
import types
import unittest
from dj_scripts import compose


class ScriptTest(unittest.TestCase):
    def test_opening_connects_to_last_heard_genre_across_sessions(self):
        heard=[dict(openingVariant='genre:0',lastTrack=dict(artist='Previous',genre='Jazz'))]
        plan=compose([dict(id='a',artist='New',title='Title',genre='Rock')],'Block',heard)
        self.assertTrue(plan['openingVariant'].startswith('genre:'))
        self.assertNotEqual('genre:0',plan['openingVariant'])
        self.assertIn('Rock',plan['text'])
    def test_each_song_gets_its_own_metadata_based_announcement(self):
        source=ast.parse((Path(__file__).parent/'dj_worker.py').read_text(encoding='utf-8'))
        function=next(n for n in source.body if isinstance(n,ast.FunctionDef) and n.name=='block')
        spoken=[]
        env=dict(compose=compose,uuid=types.SimpleNamespace(uuid4=lambda:types.SimpleNamespace(hex='block')),voice=lambda text:spoken.append(text) or str(len(spoken)))
        exec(compile(ast.Module(body=[function],type_ignores=[]),'<block>','exec'),env)
        block=env['block']([dict(id='a',artist='Artist A',title='One',genre='pop'),dict(id='b',artist='Artist B',title='Two',genre='jazz')],'Block','Opening',True)
        self.assertEqual(2,len(spoken))
        self.assertEqual('b',block['announcements'][0]['track'])
        self.assertIn('Artist B',spoken[1]);self.assertIn('Two',spoken[1])

    def test_recent_templates_do_not_repeat_and_facts_stay_in_metadata(self):
        songs=[dict(id=str(i),artist='Artist',title='Track '+str(i),album='Album',genre='jazz',year=2024) for i in range(5)]
        first=compose(songs,'Library')
        second=compose(songs,'Library',[first])
        self.assertNotEqual(first['openingVariant'],second['openingVariant'])
        self.assertFalse({a['variant'] for a in first['announcements']} & {a['variant'] for a in second['announcements']})
        for a in second['announcements']:
            self.assertIn('Artist',a['text']);self.assertIn('Track',a['text'])
        sparse=compose([dict(id='a',artist='A',title='One'),dict(id='b',artist='B',title='Two')],'Library')
        self.assertNotIn('rok wydania',sparse['announcements'][0]['text'])
