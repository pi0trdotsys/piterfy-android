"""Time-limited synthesis. Multilingual default; explicit local Piper remains available."""
import asyncio
import os
import subprocess
import sys
import tempfile
import wave

text=sys.stdin.buffer.read(4096).decode('utf-8')
if os.environ.get('DJ_VOICE_ENGINE','multilingual')=='local':
    from piper import PiperVoice
    model=PiperVoice.load('/models/pl_PL-darkman-medium.onnx')
    with wave.open(sys.argv[1],'wb') as output:model.synthesize_wav(text,output)
else:
    import edge_tts
    with tempfile.TemporaryDirectory() as folder:
        mp3=os.path.join(folder,'intro.mp3')
        asyncio.run(edge_tts.Communicate(text,voice='en-US-AndrewMultilingualNeural',rate='+4%',pitch='+3Hz',
            connect_timeout=8,receive_timeout=15).save(mp3))
        subprocess.run(['ffmpeg','-v','error','-y','-i',mp3,'-ar','24000','-ac','1',sys.argv[1]],check=True)
