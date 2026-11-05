"""Wykrywa fałszywą jakość: „bezstratne” pliki zrobione z MP3 i MP3 przekodowane z niższego bitrate.

Koder MP3 obcina wysokie tony stromym filtrem (128 kb/s ≈ 17 kHz, 192 ≈ 19 kHz, 320 ≈ 20,5 kHz) i powyżej
zostaje praktycznie cisza. Prawdziwe nagranie z płyty CD sięga do ~22 kHz albo wygasa łagodnie. Liczymy
średnie widmo trzech 12-sekundowych fragmentów utworu i szukamy takiego urwiska.

Wyniki trafiają do /config/spectrum.json ({ścieżka względem /music: wynik}). Czytają je profile_api.py
(plakietka jakości w Piterfy) i discover (podmienia fałszywe FLAC-i wśród odkryć).

Ręcznie: python /config/spectrum.py plik.flac [...]   — pokaże wynik dla wskazanych plików
         python /config/spectrum.py --scan            — przeanalizuje całą bibliotekę od razu
"""
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

import numpy as np

MUSIC = Path(os.environ.get("MUSIC_DIR", "/music"))
INDEX = Path(os.environ.get("SPECTRUM_FILE", "/config/spectrum.json"))
AUDIO = {".mp3", ".flac", ".m4a", ".ogg", ".opus", ".wav", ".aac", ".wma", ".alac", ".aiff", ".aif", ".ape", ".wv"}
LOSSLESS_CODECS = {"flac", "alac", "ape", "wavpack", "tta"}  # oraz pcm_* (wav, aiff)
VERSION = 1      # zmiana sposobu liczenia → wszystko przeliczy się od nowa
BAND = 100.0     # szerokość pasma widma w Hz
SEGMENTS = (0.2, 0.5, 0.75)
SEGMENT_SECONDS = 12

# Typowe pasmo kodera LAME dla danego bitrate (kb/s → Hz)
LAME_LOWPASS = [(96, 15000), (112, 15500), (128, 17000), (160, 17500), (192, 18600), (224, 19400), (256, 19700), (320, 20500)]


def probe(path):
    """Kodek, częstotliwość próbkowania, bitrate (kb/s) i długość — z opisu strumienia w ffmpeg."""
    out = subprocess.run(["ffmpeg", "-hide_banner", "-i", str(path)], capture_output=True, text=True,
                         errors="replace").stderr
    stream = re.search(r"Stream #\d+:\d+.*?: Audio: (\w+).*?, (\d+) Hz(.*)", out)
    if not stream:
        raise ValueError("nie widzę ścieżki dźwiękowej")
    duration = re.search(r"Duration: (\d+):(\d+):([\d.]+)", out)
    seconds = int(duration[1]) * 3600 + int(duration[2]) * 60 + float(duration[3]) if duration else 0
    kbps = re.search(r"(\d+) kb/s", stream[3]) or re.search(r"bitrate: (\d+) kb/s", out)
    return stream[1], int(stream[2]), int(kbps[1]) if kbps else 0, seconds


def decode(path, start, seconds):
    """Fragment utworu jako mono float32 w oryginalnej częstotliwości próbkowania."""
    raw = subprocess.run(["ffmpeg", "-v", "error", "-ss", f"{start:.2f}", "-t", str(seconds), "-i", str(path),
                          "-map", "0:a:0", "-ac", "1", "-f", "f32le", "-acodec", "pcm_f32le", "-"],
                         capture_output=True, preexec_fn=nice if os.name == "posix" else None).stdout
    return np.frombuffer(raw, dtype=np.float32)


def nice():
    os.nice(10)  # analiza ma nie spowalniać odtwarzania na serwerze


def band_levels(samples, sr, nfft=8192):
    """Średnie widmo (dB) w pasmach po BAND Hz, z pominięciem cichych fragmentów."""
    if len(samples) < nfft:
        return None, None
    frames = np.lib.stride_tricks.sliding_window_view(samples, nfft)[::nfft // 2]
    frames = frames[np.sqrt((frames ** 2).mean(axis=1)) > 1e-3]  # cisza (< −60 dBFS) zaniża górę widma
    if not len(frames):
        return None, None
    power = (np.abs(np.fft.rfft(frames * np.hanning(nfft), axis=1)) ** 2).mean(axis=0)
    freqs = np.fft.rfftfreq(nfft, 1 / sr)
    bands = (freqs // BAND).astype(int)
    sums = np.bincount(bands, weights=power)
    counts = np.bincount(bands)
    centers = np.arange(len(sums)) * BAND + BAND / 2
    keep = (counts > 0) & (centers < sr / 2)
    return centers[keep], 10 * np.log10(sums[keep] / counts[keep] + 1e-30)


def find_cutoff(centers, levels, sr):
    """Urwisko w widmie: (częstotliwość w Hz, spadek w dB) albo (None, 0), gdy pasmo jest pełne."""
    nyquist = sr / 2
    best = None
    for f in centers:
        if f < 11000 or f > nyquist - 600:
            continue
        pre = levels[(centers >= f - 1000) & (centers < f - 200)]
        post = levels[(centers > f + 200) & (centers <= min(f + 1500, nyquist - 150))]
        tail = levels[(centers > f + 200) & (centers <= nyquist - 150)]
        if len(pre) < 3 or len(post) < 2:
            continue
        drop = pre.mean() - post.mean()
        # stromy spadek i cisza aż do końca pasma — tak wygląda filtr kodera, a nie naturalnie gasnące tony
        if drop >= 25 and tail.max() <= pre.mean() - 20 and (best is None or drop > best[1]):
            best = (f, drop, post.mean())
    if not best:
        return None, 0.0
    f, drop, post = best
    near = centers[(centers >= f - 800) & (centers <= f + 800) & (levels >= post + drop / 2)]
    return float(near.max() + BAND / 2 if len(near) else f), float(drop)


def mp3_source_kbps(cutoff):
    """Z jakiego bitrate MP3 pochodzi dźwięk o takim paśmie (przybliżenie dla kodera LAME)."""
    for kbps, lowpass in LAME_LOWPASS:
        if cutoff <= lowpass + 300:
            return kbps
    return 320


def judge(codec, sr, kbps, cutoff):
    lossless = codec in LOSSLESS_CODECS or codec.startswith("pcm_")
    if cutoff is None:
        return "ok"
    if lossless:
        if cutoff < 19500:
            return "fake"         # z MP3 do ~256 kb/s albo z AAC
        if cutoff < 20800:
            return "suspect"      # MP3 320 / V0 — albo po prostu taki mastering
        if sr > 48000 and cutoff <= 26000:
            return "upsampled"    # „hi-res” zrobione z płyty CD
        return "ok"
    if codec == "mp3" and kbps:
        expected = next((lowpass for rate, lowpass in reversed(LAME_LOWPASS) if kbps >= rate), 15000)
        if cutoff < expected - 1500:
            return "transcoded"   # MP3 przekodowane z niższego bitrate
    return "ok"


def analyze(path):
    codec, sr, kbps, seconds = probe(path)
    chunks = []
    for share in SEGMENTS:
        start = max(0.0, seconds * share - SEGMENT_SECONDS / 2) if seconds > SEGMENT_SECONDS * 2 else 0.0
        chunks.append(decode(path, start, SEGMENT_SECONDS))
        if seconds <= SEGMENT_SECONDS * 2:
            break
    centers, levels = band_levels(np.concatenate(chunks), sr)
    if centers is None:
        return {"v": VERSION, "codec": codec, "sr": sr, "kbps": kbps, "cutoff": None, "verdict": "unknown"}
    cutoff, drop = find_cutoff(centers, levels, sr)
    result = {"v": VERSION, "codec": codec, "sr": sr, "kbps": kbps, "cutoff": round(cutoff) if cutoff else None,
              "drop": round(drop, 1), "verdict": judge(codec, sr, kbps, cutoff)}
    if result["verdict"] in ("fake", "suspect", "transcoded"):
        result["source_kbps"] = mp3_source_kbps(cutoff)
    return result


def load_index():
    try:
        return json.loads(INDEX.read_text(encoding="utf-8"))
    except (FileNotFoundError, ValueError):
        return {}


def save_index(index):
    tmp = INDEX.with_suffix(".tmp")
    tmp.write_text(json.dumps(index, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    tmp.replace(INDEX)


def scan(limit=None, seconds=None, log=print):
    """Analizuje nowe i zmienione pliki biblioteki (najwyżej `limit` albo przez `seconds` sekund).
    Zwraca liczbę przeanalizowanych plików."""
    index = load_index()
    present, todo = set(), []
    for path in MUSIC.rglob("*"):
        if path.suffix.lower() not in AUDIO or not path.is_file():
            continue
        key = path.relative_to(MUSIC).as_posix()
        if key.startswith("Podcasty/"):  # audycje mają naturalnie obcięte pasmo — to nie fałszywka
            continue
        present.add(key)
        st = path.stat()
        old = index.get(key)
        if not old or old.get("v") != VERSION or old.get("size") != st.st_size or old.get("mtime") != int(st.st_mtime):
            todo.append((path, key, st))
    changed = len(index)
    index = {k: v for k, v in index.items() if k in present}  # pliki przeniesione albo usunięte
    changed = changed != len(index)
    deadline = time.time() + seconds if seconds else None
    done = 0
    for path, key, st in todo:
        if (limit is not None and done >= limit) or (deadline and time.time() > deadline):
            break
        try:
            result = analyze(path)
        except (ValueError, OSError) as e:
            result = {"v": VERSION, "verdict": "unknown", "error": str(e)[:200]}
        result.update(size=st.st_size, mtime=int(st.st_mtime))
        index[key] = result
        done += 1
        if result["verdict"] in ("fake", "transcoded"):
            khz = f"{result['cutoff'] / 1000:.1f}".replace(".", ",")
            log(f"Fałszywa jakość ({result['verdict']}, dźwięk tylko do {khz} kHz, ok. {result['source_kbps']} kb/s): {key}")
    if done or changed:
        save_index(index)
    return done


if __name__ == "__main__":
    if sys.argv[1:] == ["--scan"]:
        total = 0
        while True:
            n = scan(limit=50)
            total += n
            print(f"przeanalizowano {total}", flush=True)
            if not n:
                break
    else:
        for name in sys.argv[1:]:
            print(name, json.dumps(analyze(Path(name)), ensure_ascii=False))
