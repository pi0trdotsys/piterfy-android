# Piterfy Connect and lyrics

The `server/` directory provides the optional shared playback session for the Android client and the Navidrome 0.64 browser adapter. The service authenticates the existing Navidrome account and keeps queues separate by user. Stream URLs and account credentials are never stored in session snapshots.

`deploy.py` installs the service and browser adapter on the existing Piterfy stack. `deploy_lyrics.py` installs the lyrics assets and their loader. Both scripts are specific to this stack, create backups before replacing assets, and must be reviewed before reuse on another server. Do not commit server state or credentials.

## Lyrics

Android and the browser prefer lyrics from the user's library, then request an exact title/artist/album/duration match from LRCLIB. Only song metadata is sent to LRCLIB; server authentication stays on the configured music server. There is no API key.

Synchronized lyrics support highlighting, click-to-seek, manual scrolling with a return button, and a per-account/per-song correction of up to ten seconds. Positive correction displays text earlier. Plain lyrics remain plain; unavailable or instrumental songs have explicit status messages. Podcast descriptions remain separate.

Android supports explicitly downloaded lyrics and the existing automatic download preference. Browser lyrics are cached locally for seven days, up to thirty entries. Switching songs cancels outstanding lookups. Both adapters follow the playback position exposed by Connect when controlling a remote receiver.

## Connect (preview)

Enable Connect explicitly on each device, use the same music account, and keep the receiving browser open. Select a device or choose **Przenieś tutaj**. The source pauses and acknowledges its position before the new receiver is assigned. A lost connection pauses playback before the server can reassign an unreachable device.

This preview supports library music queues of up to 500 tracks, transport controls, seeking, and queue selection/removal. Radio transfer, shared shuffle permutations, and all native browser queue-edit actions are not supported. A large queue on a slow network may need a transfer retry. Android can retain the previous LAN control mode independently of this server service.

## Checks

```
python -m unittest discover -s connect/server -p test_connect.py
node connect/web/lyrics-core.test.cjs
node --check connect/web/lyrics.js
node --check connect/web/connect.js
./gradlew testTempusDebugUnitTest testDegoogledDebugUnitTest lintTempusRelease lintDegoogledRelease assembleTempusRelease assembleDegoogledRelease
```

Physical Android verification confirmed lyric loading, active-line highlighting and click-to-seek. The user confirmed the lyrics view works on their phone. Browser verification covered library lyrics, click-to-seek, timing correction, manual scroll, and a 390 px viewport. Earlier tests confirmed a paused browser-to-browser Connect transfer; full Android/browser handoff remains a preview test area.
