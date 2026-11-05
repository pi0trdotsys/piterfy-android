FROM python:3.12-slim

# ffmpeg liczy ReplayGain (wyrównanie głośności)
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg \
 && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir "beets==2.14.1" requests

ENV BEETSDIR=/config HOME=/config PYTHONUNBUFFERED=1

# Skrypt i config leżą w zamontowanym /config — zmiany nie wymagają przebudowy obrazu.
ENTRYPOINT ["python", "/config/import_loop.py"]
