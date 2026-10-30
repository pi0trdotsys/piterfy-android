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

## Web mini player

The window icon beside the queue opens a resizable Document Picture-in-Picture player in desktop Chrome/Edge. It controls the existing audio element, or the remote Connect receiver, without creating a second playback session. It includes cover art, seeking, transport, volume and output name, with compact and wide layouts. Closing the window leaves playback running; closing the original tab closes the window.

Windows virtual desktop visibility is controlled by the browser/OS. When available, use Win+Tab, right-click the window and select **Show this window on all desktops**. A website cannot pin it automatically. Unsupported browsers receive an explanation and an in-tab preview option.

For this stack, stage `web/miniplayer.js`, `web/miniplayer.css`, `web/connect.js` and `deploy_miniplayer.py` directly in `/home/user/piterfy-stack/discover/connectserver/`, then run the deployment script there. It backs up assets and Caddy configuration, versions the loaders and reloads Caddy without restarting the music server. The user confirmed the separate window opens in their desktop browser; native cross-desktop visibility and all transport operations remain manual verification areas.

## Web crossfade

`crossfade-core.js` implements two-deck equal-power fades, queue selection, volume isolation and a bounded handoff back to the native audio element at the already-played incoming position. `crossfade.js` adapts Navidrome 0.64 / navidrome-music-player 4.25.4. One auxiliary deck preloads the next song in the last 25 seconds, using the queue's existing URL resolver and same-origin stream endpoints. It is released on every cancellation. The auxiliary Web Audio graph applies the next song's ReplayGain independently of the native audio graph.

The browser player exposes a 1–10 second slider, on/off switch and per-account browser storage (default: enabled, 5 seconds). This is overlap, not automatic silence detection. Radio, podcasts, repeat-one and the final non-repeating track are excluded. Unbuffered or autoplay-blocked auxiliary audio falls back to normal playback. Connect cancels overlap before a transfer/pause/remote command; reports are suppressed during the short native handoff. The original native queue remains authoritative.

Stage `web/crossfade-core.js`, `web/crossfade.js`, `web/crossfade.css`, `web/connect.js` and `deploy_crossfade.py` in `/home/user/piterfy-stack/discover/connectserver/`, then run the script. It backs up all replaced files, versions the loaders, validates and reloads Caddy, and restores the backup on failure. It does not restart Navidrome or change Android.

Browser verification on 2026-10-30 covered the 1/10 second limits, off switch, narrow and desktop layouts, and a real 10-second transition from Hollywood to workin!. Both audio elements advanced during overlap; the auxiliary deck was removed after handoff and the native player continued the incoming song beyond its already-played introduction. Fifteen crossfade tests, eight Connect tests and twelve lyrics assertions passed. Live Android/browser transfer during overlap remains unverified.

## Checks

```
python -m unittest discover -s connect/server -p test_connect.py
node connect/web/lyrics-core.test.cjs
node --check connect/web/lyrics.js
node --check connect/web/connect.js
node --check connect/web/miniplayer.js
node --test connect/web/crossfade-core.test.cjs
node --check connect/web/crossfade.js
./gradlew testTempusDebugUnitTest testDegoogledDebugUnitTest lintTempusRelease lintDegoogledRelease assembleTempusRelease assembleDegoogledRelease
```

Physical Android verification confirmed lyric loading, active-line highlighting and click-to-seek. The user confirmed the lyrics view works on their phone. Browser verification covered library lyrics, click-to-seek, timing correction, manual scroll, and a 390 px viewport. Earlier tests confirmed a paused browser-to-browser Connect transfer; full Android/browser handoff remains a preview test area.
