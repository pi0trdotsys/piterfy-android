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

## Discovery and podcast state

The authenticated `/piterfy-api/connect/library` endpoint adds per-account discovery preferences, explicit feedback, idempotent early-skip events, ranked candidate IDs, subscriptions and revisioned podcast progress. State lives under `CONNECT_STATE_DIR/library`, with hashed account filenames and atomic replacement. Request bodies never select their owning account. No credentials or stream URLs are persisted in this state.

`web/library.js` integrates these capabilities with Navidrome's existing Redux queue, stream resolver and authenticated API. Native Android uses `PiterfyLibrary`, separate queue snapshots, podcast sync and a constrained periodic subscription job. Queue snapshots contain IDs or private library metadata; they are scoped by account. The existing Media3 download index and Room metadata remain authoritative for offline files.

Run `deploy_library.py` after staging `server/connect_api.py` as `library-connect_api.py`, `server/library_core.py` as `library-core-staged.py`, and `web/library.js`, `web/library.css`. It backs up the deployed API, UI assets, loader and Caddyfile, validates Caddy, then restarts only Connect and reloads Caddy. The daily discovery daemon is not restarted by this deployment.

## DJ worker

`server/dj_core.py` stores independent account-scoped DJ/discovery sessions and acquisition jobs in SQLite WAL. `server/dj_worker.py` imports only the existing acquisition adapters from the stack's `/legacy/discover.py`; it never invokes the daily daemon, mail, cleanup, or playlist scheduling. New music must be imported/indexed before entering a block. A single worker and durable cooldown records bound concurrent interactive acquisition. The account/hour budget applies across stop/start cycles.

Build the separate worker with `server/Dockerfile.dj`. The default Andrew multilingual voice uses edge-tts 7.3.1 and Microsoft's speech service. Announcement text (including artist/title metadata) is sent for synthesis, converted to PCM WAV with FFmpeg, and cached on this server. Set `DJ_VOICE_ENGINE=local` when deploying to use the included Piper 1.8.0 Polish model instead. Synthesis runs in a time-limited subprocess. Cached introductions are upgraded in the background while preserving their claims. Check the bundled model license when redistributing the image; the model is not packaged in Android APKs. The worker port is private to the Docker network. `/piterfy-api/connect/dj` validates Navidrome authentication and derives the account server-side. Voice claims/audio require the current Connect receiver, with durable at-most-once claims.

For this stack, stage the DJ Python files, Dockerfile, `.dockerignore`, web `dj.js`/`connect.js`/`library.js`, `connect_api.py` as `library-connect_api.py`, and `deploy_dj.py` in `discover/connectserver`. Run `deploy_dj.py`. It derives only the existing music-provider credentials into a private mode-600 compose JSON file, builds the limited worker, checks it internally, backs up UI assets, and invokes the library deployment. It does not restart Navidrome, beets or the daily discovery daemon. Worker source updates require a cached image rebuild. Connect API changes restart only Connect.

Browser speech finishes before the native music queue starts; a pending queue snapshot keeps the intended DJ block visible to Connect during the introduction. Android prepares the queue paused and starts its separate MediaPlayer before allowing music to play. Focus loss, output disconnect, queue changes, account changes and Connect handoff cancel narration. DJ blocks move through the existing music queue, and speech is never stored as a music item. Use `deploy_dj.py --ui-only` for web-only updates during an active session.

Completed blocks now have a durable summary outbox and reversible, account-scoped block votes. A block counts after at least ten seconds of music and a transition out of it, a session stop, or the end of its last track; merely preparing/downloading a block does not notify. The server deduplicates completions across devices. Email and Telegram can be toggled independently. Existing server-wide delivery credentials are used only for the configured Navidrome user. Other accounts can vote in the app but never send their music to that recipient. Summaries have a 30-day capability link: GET renders a confirmation form, POST records a vote; mail scanners cannot vote by following a link. Block votes are soft, bounded artist signals used by future DJ and interactive-discovery acquisition/ranking. They do not rewrite explicit per-song likes/dislikes.

SMTP and Telegram do not provide an idempotency key. Each channel is durably claimed before delivery and marked sent/failed; ambiguous delivery failures are not blindly retried. Credentials stay in the mode-600 runtime compose file and read-only msmtp mount, never in APKs, session responses or source control. `dj_reviews.py` owns the outbox and feedback; `dj_notify.py` owns delivery. Animated DJ artwork respects reduced-motion settings and stops when idle or detached.

`discovery/patch_mail.py` restores multipart HTML daily reports without disabling discovery or changing its schedule. Stage it with `discovery/report_formatter.py` and `deploy_discovery_mail.py`, then run the deployment only while the daily worker is idle. The script backs up both sources and restarts only the discovery daemon so its loaded sender matches disk. Daily reports keep one HTML part and one readable text alternative, rather than literal Markdown in Gmail.

## Checks

`dj_scripts.py` rotates opening and between-song variants, excluding recently used templates where possible and avoiding immediate repetition when the pool is exhausted. Only actual artist/title/album/year/genre metadata supplies factual remarks. Variant IDs remain in blocks for future selection. Up to two recent active blocks can refresh their unclaimed cached clips during upgrade; concurrent claims are rechecked before updating. Android stores explicit source markers in music metadata and a small account-scoped queue context so visible mode badges survive a process restart before session polling completes.

`dj_buffer.py` adds a separate preparation lane for indexed library music, alongside slow acquisition/import and speech work. Silent **warm** requests prepare two main reserves and one alternative, each with at most five songs. New discoveries replace a library reserve without touching the playing block. Start uses `startBlock`, independent of incomplete background acquisition progress; selection is acknowledged by the server and advances a durable `currentBlock`. A fresh explicit Start/selection intent is still required for playback. Stop rotates the session ID, preventing another device's old arm token from activating a later session. Missing acquisition files cannot block an already ready library block.

Reserves expire after 24 hours and work expires after two inactive hours. An eight-batch hourly acquisition limit is independent of cheap library preparation. Exploration/voice changes and track/block feedback invalidate unheard reserves and discard stale in-flight results by epoch. Cached generic greetings allow cold library preparation without waiting for TTS; richer metadata-grounded per-song clips are prepared on a separate speech lane. Android keeps a bounded voice cache (12 MB/24 hours) and small prefixes in its existing streaming cache, on unmetered networks. Web prepares short-lived queue metadata and up to 8 MB of voice responses in memory, with a bounded next-track range prefetch. These are disposable buffers, not full offline downloads.

Each subsequent song has its own durable announcement claim and metadata-based script. Android intercepts Media3 automatic/next transitions before allowing the new track to play; the browser intercepts the existing music element's play request while preserving the native queue. Crossfade is disabled for voiced DJ sessions so incoming music cannot overlap narration. Voice-off sessions keep the normal crossfade behavior. Old cached blocks retain their former block-level introductions; fresh blocks include per-song clips.

```
python -m unittest discover -s connect/server -p test_connect.py
python -m unittest discover -s connect/server -p 'test_*.py'
node connect/web/lyrics-core.test.cjs
node --check connect/web/lyrics.js
node --check connect/web/connect.js
node --check connect/web/miniplayer.js
node --test connect/web/crossfade-core.test.cjs
node --check connect/web/crossfade.js
node --check connect/web/library.js
node --check connect/web/dj.js
node --test connect/web/dj-preroll.test.cjs
./gradlew testTempusDebugUnitTest testDegoogledDebugUnitTest lintTempusRelease lintDegoogledRelease assembleTempusRelease assembleDegoogledRelease
```

Physical Android verification confirmed lyric loading, active-line highlighting and click-to-seek. The user confirmed the lyrics view works on their phone. Browser verification covered library lyrics, click-to-seek, timing correction, manual scroll, and a 390 px viewport. Earlier tests confirmed a paused browser-to-browser Connect transfer; full Android/browser handoff remains a preview test area.
