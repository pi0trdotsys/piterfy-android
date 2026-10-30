"""Isolated, local Polish speech synthesis; text never leaves this machine."""
import sys
import wave
from piper import PiperVoice

model = PiperVoice.load('/models/pl_PL-darkman-medium.onnx')
with wave.open(sys.argv[1], 'wb') as output:
    model.synthesize_wav(sys.stdin.buffer.read(4096).decode('utf-8'), output)
