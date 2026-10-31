import ast
from pathlib import Path
import types
import unittest


class ScriptTest(unittest.TestCase):
    def test_each_song_gets_its_own_metadata_based_announcement(self):
        source=ast.parse((Path(__file__).parent/'dj_worker.py').read_text(encoding='utf-8'))
        function=next(n for n in source.body if isinstance(n,ast.FunctionDef) and n.name=='block')
        spoken=[]
        env=dict(uuid=types.SimpleNamespace(uuid4=lambda:types.SimpleNamespace(hex='block')),voice=lambda text:spoken.append(text) or str(len(spoken)))
        exec(compile(ast.Module(body=[function],type_ignores=[]),'<block>','exec'),env)
        block=env['block']([dict(id='a',artist='Artist A',title='One',genre='pop'),dict(id='b',artist='Artist B',title='Two',genre='jazz')],'Block','Opening',True)
        self.assertEqual(2,len(spoken))
        self.assertEqual('b',block['announcements'][0]['track'])
        self.assertIn('jazz',spoken[1]);self.assertIn('Artist B',spoken[1]);self.assertIn('Two',spoken[1])
