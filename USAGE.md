# Piterfy Usage Guide

## Piterfy discovery and podcasts

Home â†’ Music shows the latest server **Odkrywaj** playlists. Each card opens the playlist and offers play/download actions. **MĂłj gust** opens the configured serverâ€™s `/gust/` page in your browser.

Home â†’ Podcasts is a separate catalogue of albums tagged `Podcast`. Pull to refresh or retry a failed request; a cached catalogue remains visible while refreshing. **Downloaded** shows episodes available locally. Played episodes resume from their saved position, and **Continue episode** provides direct access to unfinished listening.

The expanded player displays the current audio route and device name when Android exposes it. The source-analysis label comes from the optional Piterfy server extension; the existing codec/bitrate details describe the playback stream.

Search suggestions wait briefly while typing, stale requests are cancelled, and **Play all results** fetches the complete queue on demand. GitHub update prompts now link to Piterfy releases.


## Experimental local-network playback

Local-network playback adds **Settings â†’ Remote player** as a
standalone entry and **Play onâ€¦** in the player's overflow menu. Two Tempus phones
can pair by comparing a code, then choose music and control playback
over the LAN. The receiver resolves track IDs using its own server account;
both phones must use the same server address. The receiving service is opt-in,
has a visible notification and can be stopped or have controller access revoked.

The mini player has a two-phone device icon, and the expanded player has a **Play onâ€¦** button with the same icon. The overflow entry also opens the device selector. Select an already paired receiver to
control its existing playback without transferring or replacing either queue.
The main player and mini player show the receiver's title, artwork, position and
playback state. Updates arrive on playback events and about once per second.
The original Tempus player remains visible: its controls, swipeable artwork,
queue page and lyrics page are reused. The device button shows **Playing on
[device name]** while remote control is selected. Playback controls, shuffle,
repeat, speed, lyrics seeking and the sleep timer act on the receiver. Device
audio effects are configured on the receiver itself. There is no separate
remote-player screen. Tap the device button again to return to the local player:
the receiver continues playing and local playback does not automatically resume.

Physical volume keys control the receiver's Android music output,
including its Bluetooth output, rather than the controller's volume. Relative
steps are applied on the receiver, and its actual volume/range are reported back
to Android's remote-volume session. The system session also routes playback
commands to the receiver. In-app keys are consumed while reconnecting to avoid
changing local volume. Background and screen-off routing depends on Android
selecting this active media session and the controller process remaining alive;
verify it on physical phones. Releasing remote control releases that session.
Both phones need a version supporting LAN remote playback.
Settings uses the same preference rows, switch style and toolbar as the original
settings. **This device** contains the editable device name. **Receiver** enables
local network control on the phone that plays music. Only while that receiver is
running, **Make available for pairing** appears as a switch with a two-minute
countdown, followed by an inline list of **Paired controllers**. Tap a controller
to review and confirm revoking its access. Switching pairing off cancels
pending requests; expiry prevents late confirmations. An incoming pairing request
opens a confirmation dialog on the receiver with the code to compare against the
controller. It also remains in **Pairing requests** if the dialog is dismissed.
Already paired controllers
do not need the window open. **Controller** searches for receiving phones and
shows the selected device with **Release remote control**. Selecting an approved
receiver opens the normal player directly. Both roles can be used on one phone;
there is no master/slave mode selector. Playback/queue/volume controls belong in
the player, not this settings page. Diagnostic export is absent.

The device list updates as receivers are found. Discovery results expire after
45 seconds and the search restarts, because Android may not report when a
receiver disappears. Tap **Find devices** to scan again at any time.

While a receiver is selected, the library's **Play**, **Add to queue** and
**Play next** actions apply to that receiver, including playlists and albums.
An empty receiver queue does not release the selection: choose music normally
to start it on the receiver. Adding to an empty queue prepares it without
automatically starting playback. The controller's local queue remains intact.
**Queue** opens the regular queue list, showing the receiver's tracks. Tap to
play/pause, swipe to remove, drag to reorder, and use the queue menu to shuffle
upcoming tracks, clear upcoming tracks or save the list to a playlist. Queue
revisions prevent edits from applying to different tracks after a concurrent
change on the receiver. Changes on either phone refresh the displayed list.
Remote playback supports up to 500 music tracks, including repeated tracks; radio
and podcasts are rejected while remote control is selected. Songs downloaded
on the controller still require server access on the receiver.
The selection survives screen rotation but
not process termination. On a brief interruption commands are disabled and the
controller retries the same endpoint; if the receiver restarts or changes network
address, release control and select it again. Failed remote actions never fall
back to local playback. Use compatible LAN playback versions on both phones.
In Settings â†’ Remote player, discovering/selecting a phone checks pairing and
opens the normal player once pairing is confirmed. Previously approved certificates do not
require another pairing code; new identities still require approval.
English and Italian labels are included.

If a discovered phone cannot connect, the controller reports whether the failure
occurred while preparing its certificate, reaching the phone, negotiating TLS,
verifying identity, or receiving the pairing response. Discovery alone does not
confirm that a connection can be established. Report the message shown on screen.
Internal Android diagnostics still exclude device names, addresses, pairing
codes, credentials and queue contents. No diagnostic export button is shown.

Connections use mutual TLS and manual approval of certificate identities; there is no plaintext fallback.
[<- back home](README.md)

## Table of Contents
- [Prerequisites](#prerequisites)
- [Getting Started](#getting-started)
- [Server Configuration](#server-configuration)
- [Main Features](#main-features)
- [Navigation](#navigation)
- [Playback Controls](#playback-controls)
- [Playing on another device](#playing-on-another-device)
- [Favorites](#favorites)
- [Playlist Management](#playlist-management)
- [Android Auto](#android-auto)
- [Settings](#settings)
- [Known Issues](#known-issues)

## Prerequisites

**Important Notice**: This app is a Subsonic-compatible client and does not provide any music content itself. To use this application, you must have:

- An active Subsonic API server (or compatible service) already set up
- Valid login credentials for your Subsonic server
- Music content uploaded and organized on your server

Tempus offers advanced features such as Instant Mix, Continuous Play, Artists Page, and many others, which require a properly configured server capable to respond to "similar songs" requests and correctly tagged music files.

### Verified backends
This app works with any service that implements the Subsonic API, including:
- [LMS - Lightweight Music Server](https://github.com/epoupon/lms) -  *personal fave and my backend*
- [Navidrome](https://www.navidrome.org/)
- [Gonic](https://github.com/sentriz/gonic)
- [Ampache](https://github.com/ampache/ampache)
- [NextCloud Music](https://apps.nextcloud.com/apps/music)
- [Airsonic Advanced](https://github.com/kagemomiji/airsonic-advanced)



## Getting Started

### Installation
1. Download the APK from the [Releases](https://github.com/eddyizm/tempus/releases) section
2. Enable "Install from unknown sources" in your Android settings
3. Install the application

### First Launch
1. Open the application
2. You will be prompted to configure your server connection
3. Grant necessary permissions for media playback and background operation

## Server Configuration

### Initial Setup
**IN PROGRESS**
1. Enter your server URL (e.g., `https://your-subsonic-server.com`)
2. Provide your username and password. Or in some cases, API key (eg LMS: https://github.com/epoupon/lms/discussions/562).
3. Test the connection to ensure proper configuration

If you intend to play to a TV or a network speaker, the address you enter here also has to be reachable from that device, because it fetches the music from your server itself. See [Playing on another device](#playing-on-another-device).

### Advanced Settings
**TODO**

## Main Features

### Library View

**Multi-library**

Tempus handles multi-library setups gracefully. They are displayed as Library folders. 

If your server reports more than one music folder, Tempus can limit browsing and search to a single one of them.

Tap the app name in the toolbar on the Music tab of Home, or on Library. A second line under it names the library in use, and tapping opens a checked list of the server's libraries. The same setting lives in Settings under General as "Music library". Either one takes effect on the screen you are looking at, with no restart and no navigating away.

<p align="left">
    <img src="mockup/usage/library_switcher.jpg" width=317>
</p>

"All libraries" is the default and keeps the old merged view. The filter reaches browsing and search only, so it never touches your starred items, your playlists or your downloads. On the Podcast and Radio tabs that second line names your server instead of a library, and it is not tappable there. On Downloads there is no second line at all.

<p align="left">
    <img src="mockup/usage/toolbar_server_name.jpg" width=317>
</p>

The setting is hidden when there is only one library to choose from.

### Folder or index playback

If your Subsonic-compatible server exposes the folder tree **or** provides an artist index (for example Gonic, Navidrome, or any backend with folder browsing enabled), Tempus lets you play an entire folder from anywhere in the library hierarchy:

<p align="left">
    <img src="mockup/usage/music_folders_root.png" width=317 style="margin-right:16px;">
    <img src="mockup/usage/music_folders_playback.png" width=317>
</p>

- The **Library â–¸ Music folders** screen shows each top-level folder with a play icon only after you drill into it. The root entry remains a simple navigator.
- When viewing **inner folders** **or artist index entries**, tap the new play button to immediately enqueue every audio track inside that folder/index and all nested subfolders.
- Video files are excluded automatically, so only playable audio ends up in the queue.

No extra config is neededâ€”Tempus adjusts based on the connected backend.

### Now Playing Screen

On the main player control screen, tapping on the artwork will reveal a small collection of 4 buttons/icons. 
<p align="left">
    <img src="mockup/usage/player_icons.png" width=159>
</p>

*marked the icons with numbers for clarity* 

1. Downloads the track (there is a notification if the android screen but not a pop toast currently )
2. Adds track to playlist - pops up playlist dialog.
3. Adds tracks to the queue via instant mix function
    * TBD: what is the _instant mix function_?
    * Uses [getSimilarSongs](https://opensubsonic.netlify.app/docs/endpoints/getsimilarsongs/) of OpenSubsonic API.
      Which tracks to be mixed depends on the server implementation. For example, Navidrome gets 15 similar artists from LastFM, then 20 top songs from each.
4. Saves play queue (if the feature is enabled in the settings) 
    * if the setting is not enabled, it toggles a view of the lyrics if available (slides to the right) 

**Format and bitrate**

The player's format and bitrate label gives what was actually decoded, not the transcode Tempus asked for. If your server ignored the request, or had no rule matching the track, the label shows what it really sent. A track the server did transcode reads as the new format followed by "(Transcoding)".

Downloaded tracks show the format on disk. When Tempus transcodes a download, the row and the track info dialog carry the transcoded format and bitrate instead of the source values.

### Podcasts  
If your server supports it - add a podcast rss feed
<p align="left">
    <img src="mockup/usage/add_podcast_feed.png" width=317>
</p>

### Radio Stations
If your server supports it - add a internet radio station feed
<p align="left">
    <img src="mockup/usage/add_radio_station.png" width=326>
</p>

## Navigation

### Bottom Navigation Bar
**IN PROGRESS**
- **Home**: Recently played and server recommendations
- **Library**: Your server's complete music collection
- **Download**: Locally downloaded files from server 

## Playback Controls

### Streaming Controls
**TODO**

### Advanced Controls
**TODO**

## Playing on another device

Tempus can hand playback to a UPnP or DLNA renderer on your network, such as a TV, an AV receiver or a network speaker. Tap the cast button in the toolbar, pick the device, and the queue, the position and the play state move to it while the app keeps the controls. On the GitHub build the same button lists Chromecast devices and network renderers together.

The renderer fetches the music from your server itself. Nothing is streamed through the phone, which is how the protocol works. Downloaded tracks are played from your server too, because the renderer cannot reach what is stored on the phone.

**Your server address has to be one the renderer can reach.** If Tempus is configured with an address only the phone can use, the device still appears in the list and still accepts being selected, and then plays nothing. Addresses that fail this way:

- a VPN or mesh network address, a Tailscale or WireGuard address for example, when the renderer is not on that network
- `localhost` or `127.0.0.1`
- an address on a different subnet from the renderer, which is easy to end up with when there is a second router

Use the address your server has on the same network as the renderer.

Volume is left to the renderer's own remote, because some renderers report a volume that does not match what they are doing. The sleep timer's fade out has no effect on a renderer, so playback stops at full volume when the timer runs out. Discovery uses SSDP, which is UDP and lossy, so if your device does not appear the first time, close the picker and open it again.

## Favorites

### Favorites (aka heart aka star) to albums and artists
- Long pressing on an album gives you access to heart/unheart an album   

<p align="center">
    <img src="mockup/usage/fave_album.png" width=376>
</p>

- Long pressing on an artist cover gets you the same access to to heart/unheart an album   

<p align="center">
    <img src="mockup/usage/fave_artist.png" width=376>
</p>


## Playlist Management

### Editing a playlist

Open a playlist and pick Edit playlist from its menu to rename it, drag tracks into a new order, or remove tracks. Saving writes the list exactly as it stands on screen, so a removal or a reorder takes effect on the server instead of being added on top of the tracks already there.

Two things to know:
- A playlist has to keep at least one track. A save that would leave it empty is refused and the editor says so, use Delete if you want the playlist gone.
- Saving needs the server. The editor will not save from the offline cache, because writing that older copy back would drop anything added to the playlist since it was cached.

### Server Playlists
**TODO**

### Creating Playlists
**TODO**

## Settings

### Language

Select **Settings â†’ UI â†’ Language** to choose an app language. Swedish is available as **Swedish** (or **Svenska**, depending on the current language). Select **System language** to follow your device language.

## Android Auto

**Enabling on your head unit**

To allow the Tempus app on your car's head unit, "Unknown sources" needs to be enabled in the Android Auto "Developer settings". This is because Tempus isn't installed through Play Store. Note that the Android Auto developer settings are different from the global Android "Developer options".
1. Switch to developer mode in the Android Auto settings by tapping ten times on the "Version" item at the bottom, followed by giving your permission.
<p align="left">
   <img width="270" height="600" alt="1a" src="https://github.com/user-attachments/assets/f09f6999-9761-4b05-8ec7-bf221a15dda3" />
   <img width="270" height="600" alt="1b" src="https://github.com/user-attachments/assets/0795e508-ba01-41c5-96a7-7c03b0156591" />
   <img width="270" height="600" alt="1c" src="https://github.com/user-attachments/assets/51c15f67-fddb-452e-b5d3-5092edeab390" />
</p>
   
2. Go to the "Developer settings" by the menu at the top right.
<p align="left">
   <img width="270" height="600" alt="2" src="https://github.com/user-attachments/assets/1ecd1f3e-026d-4d25-87f2-be7f12efbac6" />
</p>

3. Scroll down to the bottom and check "Unknown sources".
<p align="left">
   <img width="270" height="600" alt="3" src="https://github.com/user-attachments/assets/37db88e9-1b76-417f-9c47-da9f3a750fff" />
</p>

**Interface Configuration**

The Android Auto interface can be configured by user to best suit their preferences.

<p align="left">
    <img src="mockup/usage/aa_preferences.png" width=317 style="margin-right:16px;">
    <img src="mockup/usage/aa_functions.png" width=317>
</p>

4 tabs can be configured with the following functions:
- Do not display : This tab is not used
- Home : Displays all functions not used in other tabs
- Recent : The 15 recently listened-to albums
- Albums : Albums sorted by name
- Artists : Albums sorted by artist or Artists, selected by preference
- Playlists
- Podcast : The 100 podcasts recently added
- Radio : Your server's stations together with any you added locally in the app. The local ones show even when the server reports no stations of its own
- Folder : Navigation through music directories
- Albums most played : The 15 most played albums
- Tracks played : The 100 last tracks that were completely played
- Albums added : The 15 recently added albums
- For You bundle
- Starred bundle
- Tracks bundle
- Genres : 500 songs of the chosen genre OR 100 random songs if "shuffle genre songs" is selected
- Downloads : the 500 first downloaded tracks OR 100 random downloaded tracks if "shuffle downloaded tracks" is selected. Shown at the top of the Home tab and playable without connectivity

<p align="left">
    <img src="mockup/usage/aa_thumbnails.jpg" width=317 style="margin-right:16px;">
    <img src="mockup/usage/aa_list.jpg" width=317>
</p>

For You bundle includes:
- Quick mix : features 12 tracks chosen randomly from the 15 last played albums
- My mix : features 15 tracks chosen randomly from the 15 last played albums, and starred artists or starred albums, following preference
- Discovery mix : features 18 tracks, as My mix, with similar songs
- Starred artists
- Starred albums
- Starred tracks : the 500 first starred tracks OR 100 random starred tracks if "shuffle starred tracks" is selected

Starred bundle includes:
- Starred artists
- Starred albums
- Starred tracks : the 500 first starred tracks OR 100 random starred tracks if "shuffle starred tracks" is selected

Tracks bundle includes:
- Random : 100 random songs
- Genres : 500 songs of the chosen genre OR 100 random songs if "shuffle genre songs" is selected
- Tracks played : The 100 recently listened-to tracks
- Starred tracks : the 500 first starred tracks OR 100 random starred tracks if "shuffle starred tracks" is selected


<p align="left">
    <img src="mockup/usage/aa_tracks.jpg" width=317 style="margin-right:16px;">
    <img src="mockup/usage/aa_for_you.jpg" width=317>
</p>

If all tabs are set to "Do not display", then "Home" tab will be created with all functions inside.

If "Home" is selected after another tab, it becomes "More".

In addition, you can choose to display the following functions as thumbnails or lists:
- Home, For You bundle, Starred bundle and Tracks bundle
- Albums (Last played, Most played, Recently added, Artists, Starred albums, Starred artists)
- Playlists
- Radio
- Podcast

As they displayed tracks, Tracks played, Starred tracks, Random and Genres are always be displayed as a list.

Artists view and View by albums:
<p align="left">
    <img src="mockup/usage/aa_artists_view1.jpg" width=317>
    <img src="mockup/usage/aa_artists_view2.jpg" width=317 style="margin-right:16px;">
</p>

Starred Artists view:
<p align="left">
    <img src="mockup/usage/aa_starred_artists_view.jpg" width=317>
</p>

On an artist's page, if they have at least 2 albums with a minimum of 20 tracks, an "Instant Mix by Tempus" album is added at the beginning.
This album features 12, 15 ou 18 tracks chosen randomly from their discography and is an one click play.
The number of tracks on the album depends on the size of the artist's discography (>20, >30 or >40)

<p align="left">
    <img src="mockup/usage/aa_instantMix.jpg" width=317>
</p>

The A-Z button allows you to jump to items starting with the chosen letter.

Search button returns albums or artists, even if they are not displayed by the selected function.

Results of the A-Z jump will always be displayed as a list.

<p align="left">
    <img src="mockup/usage/aa_AZ.jpg" width=317 style="margin-right:16px;">
    <img src="mockup/usage/aa_search.jpg" width=317>
</p>

Display of albums and artists is limited to 500. For large libraries, it's preferable to use star albums or star artists.

Shortcuts are displayed only if the function is selected from root level:
- On albums page: jump to starred albums
- On starred albums page: jump to albums
- On artists page: jump to starred artists
- On starred artists page: jump to artists

### Server Settings
**IN PROGRESS**
- Manage multiple server connections
- Configure sync intervals
- Set data usage limits for streaming

### Audio Settings
**IN PROGRESS**
- Streaming quality settings
- Offline caching preferences

### Appearance
**TODO**

## Known Issues

### Piterfy web mini player

In desktop Chrome/Edge, click the small window icon beside the queue to open the floating mini player. Drag its edges to resize, or use its size button to switch between compact and cover views. It includes play/pause, previous/next, seeking and volume. Keep the original Piterfy tab open; closing only the mini player does not stop the music.

To keep it on all Windows virtual desktops, use **Win+Tab â†’ right-click the window â†’ Show this window on all desktops**, if your browser exposes that option. The help button inside the mini player also explains this limitation.

### Piterfy discovery, podcasts and playback (Piterfy 6)

**Odkrywaj** is available in the web header and on Android's music home screen. **WiÄ™cej takich / Mniej takich** changes future suggestions and intelligent playback. **Poziom eksperymentowania** ranges from familiar music to new sounds; settings and reactions follow the signed-in account between the browser and phone. Deliberate early skips (after three seconds, within the first thirty seconds and first 30% of the song) lower its recommendation score. Pausing, buffering and seeking within a track do not count as skips.

Music and podcasts have separate saved queues. In the browser's discovery panel, choose **Kolejka muzyki / Kolejka podcastĂłw**; the podcast page also offers queue shortcuts and **Kontynuuj sĹ‚uchanie**. On Android, use the player's overflow menu to return to either queue. Each saved queue retains its selected item and position. Radio remains independent of these library queues.

On Android's **Podcasty** tab choose **Subskrypcje**, or use **Podcast Â· automatyczne pobieranie** from a playing episode's menu. Subscriptions sync with the web album's **Subskrybuj** button. The phone checks hourly over an unmetered connection and can run later when Android schedules background work. Enabling a show queues at most its three newest episodes initially; subsequent refreshes queue new episodes. Existing completed/requested downloads are retained and deduplicated. The **Pobrane** podcast filter and the bottom **Pobrane** destination provide offline access. A playlist's toolbar download button queues its missing songs; **Keep synced** remains available from its menu.

Podcast positions sync while online, periodically and when pausing. Older device revisions cannot overwrite a newer server position. Android also retains its existing local resume points for offline playback. The web's automatic podcast downloads are performed by the Android app.

Android **Settings â†’ Piterfy Â· odtwarzanie i odkrywanie** now contains **PĹ‚ynne przejĹ›cia** and a 1â€“10 second duration (default: five seconds). Crossfade applies to music playing locally on the phone. Podcasts, radio, remote receivers, repeat-one and an active sleep timer use normal transitions. Both outgoing and incoming buffers must be ready; otherwise normal playback takes priority. The web has its separate per-browser crossfade setting on the player.

The desktop web player uses a full-width timeline with controls above it. On a narrow web screen, a bottom mini player provides the current track, cover and play/pause; tap its title to open the full player. Android's timeline has a larger touch target in portrait, landscape and tablet layouts. Both use dark surfaces and restrained cover-color accents.

### Piterfy web crossfade

Click **PrzejĹ›cie Â· 5 s** on the browser player to configure **PĹ‚ynne przejĹ›cia**. Crossfade is enabled by default at five seconds; choose 1â€“10 seconds or turn it off. The setting is saved per account in this browser. It starts the next track under the fading end of the current one, so its quiet introduction overlaps existing music. It does not detect silence or cut a fixed number of seconds from recordings.

Applies to local library music, including shuffled queues and repeat-all. Podcasts, radio, repeat-one and the end of a non-repeating queue play normally. The next track is prepared near the end of the current track; if it is not buffered, normal playback takes priority. Pause, seeking, queue changes and Connect transfer cancel the overlap. This feature is web-only; it does not change Android playback.

### Piterfy DJ and on-demand discoveries

The main DJ button starts a ready session, pauses the current one or resumes it. **ZmieĹ„ klimat** stays alongside it. Voice, exploration and summary options are under **Ustawienia audycji**. The current and prepared blocks appear first; **Zobacz utwory** expands individual songs and **Historia blokĂłw** reveals earlier blocks with their ratings. Expanded content survives ordinary state refreshes. Android uses the same hierarchy and mint accent as the web, with larger player controls and a more visible seek thumb. Mobile web adds a next-track button to the compact player; native web player controls also support Enter/Space and descriptive accessibility labels.

DJ and interactive discovery prepare a silent, account-scoped start reserve as the app opens. Two ready blocks and one alternative are kept ahead; **ZmieĹ„ klimat** selects the ready alternative. **Uruchom** consumes an already prepared block when available, while a separate worker continues acquiring discoveries. If acquisition is slow, indexed library tracks provide the prepared fallback. A background job never grants playback permission. The ready-block count excludes the playing block. New feedback replaces unheard reserves without changing the current music.

Reserves expire after 24 hours and preparation expires after two inactive hours. Android preloads a limited audio prefix into the existing streaming cache and caches voice clips (12 MB/24 hours) on unmetered networks. Disabling streaming cache disables prefix caching. Web caches queue metadata/voice clips in memory and attempts a bounded next-track range preload. Browser cache policies and network conditions affect this optimization. These buffers do not mark tracks as downloaded or provide a complete offline session.

**Uruchom** pauses the current music and selects a complete ready block, preparing a library fallback if no reserve exists yet. The indicator shows the ready reserve count or background matching/search/download/index/voice progress. Missing new files do not hold up a complete library block. Opening DJ, refreshing recommendations or receiving background blocks never starts music on its own. Android keeps an explicit start request while the dialog is closed. A cold library fallback can begin with a short cached greeting while richer per-song introductions are prepared in the background. With narration enabled, crossfade is suspended so speech finishes before the next song. **DJ / Odkrywaj** labels identify the active source on the playback bar and now-playing view.

In the browser, open **DJ** or **Odkrywaj â†’ Piterfy DJ**. On Android, choose **Piterfy DJ Â· Twoja audycja** on the home screen. Start a session, adjust exploration, and use **ZmieĹ„ klimat** to request another block. Each block lists its songs and can be played directly. **WiÄ™cej takich / Mniej takich** records feedback about the current song. Voice can be disabled independently of music.

The animated DJ symbol moves during music/announcements and rests on pause. **PomiĹ„ zapowiedĹş** moves straight to the music. More upbeat multilingual introductions still finish before music starts. **Podsumowania e-mail / Telegram** independently toggle a short report after an auditioned block ends or is left. **Blok OK / Nie mĂłj klimat** evaluates the whole block; these soft preferences guide future DJ and interactive discoveries. The report's **OceĹ„ ten blok** link opens a confirmation page with an undo option and expires after 30 days. Reports use the server's existing recipient account; other accounts cannot send music summaries to that recipient. An internet connection is required for delivery and new voice synthesis.

Introductions use a multilingual voice for mixed Polish/English names and titles. Their text is synthesized through Microsoft's speech service and cached on your server. They name the opening artist/song and, when supported by the selected tracks, the explored genre. The introduction plays before the music starts. Speech is not a library song or a scrobble. Connect receivers claim an introduction once; a transfer will not replay an already claimed introduction. If voice generation or delivery fails, music takes priority.

**Odkrywaj â†’ Nowe utwory Â· pobieranie** opens an independent acquisition session. Refreshing interactive discovery also requests its own background batch. DJ and interactive discovery have separate sessions and never run the daily discovery mailer. They reuse the existing acquisition/import adapters, avoid duplicate interactive searches, and only queue new music after it is indexed in Navidrome. Until then, library music is available as fallback.

Each account may request eight acquisition batches per hour, with up to five new acquisitions per batch and a shared check for at most two active downloader transfers. Source availability and import delays affect results. During a pause, bounded preparation may finish filling the reserve; no further work is scheduled when it is full. Stopping a session cancels queued work. Transfers already submitted to the downloader may finish. Imported music stays in the library; there is no automatic deletion of these discoveries.

### Piterfy lyrics

Open the full Android player and select the lyrics icon. In the browser, use **â™« Tekst**. Timed lyrics highlight the current line and follow playback. Tap a line to seek, scroll to read ahead, then use **WrĂłÄ‡ do tekstu** to resume following. The timing button adjusts synchronization in half-second steps and remembers the correction for that song.

**Offline** saves lyrics on Android. Library lyrics are preferred; if missing, Piterfy looks up the exact recording on LRCLIB using song metadata. Availability and timing depend on the source. Plain lyrics are displayed without invented timestamps.

### Piterfy Connect preview

Enable **Piterfy Connect** in the device picker/settings on Android and in the browser's Connect panel. Use the same server account on both. Select a destination or **PrzenieĹ› tutaj** to transfer the queue and position. The receiving browser must remain open. The preview supports library music queues up to 500 tracks; radio transfer and shared shuffle order are not yet supported. See `connect/README.md` for deployment and testing details.

### Airsonic Distorted Playback

First reported in issue [#226](https://github.com/eddyizm/tempus/issues/226)  
The work around is to disable the cache in the settings, (set to 0), and if needed, cleaning the (Android) cache fixes the problem.

### Support
For additional help:
- Question? Start a [Discussion](https://github.com/eddyizm/tempus/discussions)
- Open an [issue](https://github.com/eddyizm/tempus/issues) if you don't find a discussion solving your issue. 
- Consult your Subsonic server's documentation

---

*Note: This app requires a pre-existing Subsonic-compatible server with music content.*


### Kolejka, reakcje i pobieranie Piterfy

W webie otwĂłrz **Odkrywaj â†’ Edytuj kolejkÄ™**; w mobilnym pasku sĹ‚uĹĽy do tego przycisk kolejki. Na Androidzie uĹĽyj menu przy utworze. PrzypiÄ™cie zachowuje nadchodzÄ…cy utwĂłr po zmianie klimatu DJ-a. UsuniÄ™cie moĹĽna cofnÄ…Ä‡, dopĂłki kolejka nie ulegĹ‚a dalszej zmianie (lokalnie oraz w Connect; starszy protokĂłĹ‚ odbiornika LAN zachowuje dotychczasowe operacje).

Przy **Mniej takich** moĹĽesz wskazaÄ‡ powĂłd. **Ile mĂłwi DJ** w ustawieniach audycji wybiera zapowiedĹş na poczÄ…tku bloku, co drugi utwĂłr albo kaĹĽdy utwĂłr. PamiÄ™Ä‡ zapowiedzi jest oddzielna dla kont i trwa miÄ™dzy sesjami.

Na Androidzie **Pobrane â†’ Centrum pobierania** pokazuje postÄ™p i bĹ‚Ä™dy oraz pozwala ponowiÄ‡, wstrzymaÄ‡ lub wznowiÄ‡ pobieranie. Limit pamiÄ™ci wewnÄ™trznej zatrzymuje pobieranie po osiÄ…gniÄ™ciu progu; nie usuwa muzyki. Opcjonalne sprzÄ…tanie po 24 godzinach obejmuje ukoĹ„czone podcasty z pobraĹ„, ktĂłrych konto jest rozpoznane, z ochronÄ… przypiÄ™tych i aktualnie odtwarzanych odcinkĂłw. Nie usuwa plikĂłw w folderach zewnÄ™trznych. W webie panel **Pobieranie i gotowe bloki** pokazuje postÄ™p przygotowania na serwerze; pamiÄ™Ä‡ offline telefonu jest zarzÄ…dzana na telefonie.


### Ulubione offline i oddzielne podcasty

Android domyślnie synchronizuje **ulubione utwory** do pobierania przez połączenie bez naliczania opłat (zwykle Wi-Fi). Obejmuje obecne i kolejne polubienia, także z webu; albumy oznaczone sercem nie są pobierane w całości. Nowe polubienie na telefonie zleca sprawdzenie, a okresowa synchronizacja wychwytuje pozostałe. Android może opóźnić pracę w tle. W **Pobrane** stały przycisk pod paskiem tytułu otwiera **Centrum pobierania** z przełącznikiem automatycznych ulubionych, stanem i przyciskiem „Sprawdź ulubione teraz”. Istniejące pliki są pomijane, cofnięcie polubienia ich nie usuwa, a limit offline wstrzymuje kolejne pobrania. Pobieranie respektuje ogólne ustawienia sieci pobierania aplikacji.

**Utwory** zawierają muzykę; podcasty mają własną kategorię i kolejkę. Odcinki są rozpoznawane po typie, gatunku oraz folderach `Podcast`, `Podcasts` lub `Podcasty`, również gdy brakuje tagów. Tytuł ani wspólny wykonawca nie decydują o klasyfikacji. Webowy katalog Utworów, jego wyszukiwanie, odtwarzanie i tasowanie używają listy muzycznej. Podcasty z pustym gatunkiem są dostępne w katalogu Podcasty. Connect odrzuca próbę połączenia odcinków i muzyki w jedną kolejkę.


### Ulubione offline w przeglądarce

Otwórz [Piterfy](https://piterfy.prosinski.eu/app/) z dostępem do sieci w docelowej przeglądarce, zaloguj się i wybierz **Offline**. Poczekaj na zakończenie zapisu i komunikat o gotowym ekranie startowym. Możesz wtedy ponownie otworzyć ten sam adres `/app/` bez dostępu do sieci i odtworzyć zapisane utwory, także losowo. Synchronizacja nowych polubień działa podczas otwartej karty. Pliki są przechowywane osobno dla każdego konta i profilu przeglądarki; po cofnięciu polubienia pozostają dostępne. Wyczyszczenie danych witryny usuwa zapis.
