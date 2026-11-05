"""Podcasty w Piterfy — Navidrome nie obsługuje podcastów, więc ściągamy je sami jako zwykłe pliki audio."""

import html
import re
import shutil
import struct
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path
import xml.etree.ElementTree as ET

FOLDER = "Podcasty"
GENRE = "Podcast"
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
ITUNES = "{http://www.itunes.com/dtds/podcast-1.0.dtd}"
MAX_EPISODE_BYTES = 800 * 1024 * 1024
MAX_COVER_BYTES = 15 * 1024 * 1024


def load_shows(path, latest=20, keep=30, log=print):
    shows = []
    try:
        lines = Path(path).read_text(encoding="utf-8-sig").splitlines()
    except OSError:
        return shows
    for number, raw in enumerate(lines, 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = [p.strip() for p in line.split("|")]
        if len(parts) < 2 or not parts[0] or not parts[1].startswith(("http://", "https://")):
            log(f"Podcasty: pomijam linię {number} w {Path(path).name} (format: Nazwa | adres RSS): {line[:60]}")
            continue
        options = dict(re.findall(r"(\w+)\s*=\s*(\d+)", parts[2])) if len(parts) > 2 else {}
        shows.append({"name": parts[0], "url": parts[1],
                      "last": int(options.get("last", latest)), "keep": int(options.get("keep", keep))})
    return shows


def show_names(shows):
    return {unicodedata.normalize("NFKC", s["name"]).casefold() for s in shows}


def is_podcast(song):
    parts = (song.get("path") or "").replace("\\", "/").split("/")
    return song.get("genre") == GENRE or FOLDER in parts[:3]


def open_url(url, timeout=60):
    url = urllib.parse.quote(url, safe="%/:=&?~#+!$,;'@()*[]")
    return urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": USER_AGENT}), timeout=timeout)


def strip_html(text):
    text = re.sub(r"<br\s*/?>|</p>", "\n", text or "", flags=re.I)
    text = html.unescape(re.sub(r"<[^>]+>", "", text))
    return re.sub(r"\n{3,}", "\n\n", text).strip()[:2000]


def parse_feed(data):
    if isinstance(data, bytes):
        data = re.sub(rb'[\x00-\x08\x0B\x0C\x0E-\x1F]', b'', data)
    channel = ET.fromstring(data).find("channel")
    if channel is None:
        raise ValueError("to nie jest kanał RSS")
    image = channel.find(ITUNES + "image")
    cover = image.get("href") if image is not None else channel.findtext("image/url")
    episodes = []
    for item in channel.findall("item"):
        enclosure = item.find("enclosure")
        if enclosure is None or not enclosure.get("url"):
            continue
        try:
            date = parsedate_to_datetime(item.findtext("pubDate"))
        except (TypeError, ValueError):
            continue
        if date.tzinfo is None:
            date = date.replace(tzinfo=timezone.utc)
        number = (item.findtext(ITUNES + "episode") or "").strip()
        title = " ".join((item.findtext("title") or "").split())
        if not number.isdigit():
            lead = re.match(r"^(\d{1,4})\s*[:.]\s", title)
            number = lead.group(1) if lead else ""
        episodes.append({
            "guid": (item.findtext("guid") or enclosure.get("url")).strip(),
            "title": title,
            "date": date, "url": enclosure.get("url"),
            "number": int(number) if number.isdigit() else None,
            "length": int(enclosure.get("length") or 0) if (enclosure.get("length") or "0").isdigit() else 0,
            "description": strip_html(item.findtext("description") or item.findtext(ITUNES + "summary") or ""),
        })
    episodes.sort(key=lambda e: e["date"], reverse=True)
    for rank, episode in enumerate(reversed(episodes), 1):
        episode["track"] = episode["number"] or rank
    return {"cover": cover, "episodes": episodes}


def fetch_feed(url):
    with open_url(url, timeout=90) as response:
        return parse_feed(response.read())


def syncsafe(n):
    return bytes([(n >> 21) & 0x7F, (n >> 14) & 0x7F, (n >> 7) & 0x7F, n & 0x7F])


def id3v2_length(head):
    if head[:3] != b"ID3" or len(head) < 10:
        return 0
    size = (head[6] << 21) | (head[7] << 14) | (head[8] << 7) | head[9]
    return 10 + size + (10 if head[5] & 0x10 else 0)


def id3_frame(frame_id, payload):
    return frame_id.encode("ascii") + syncsafe(len(payload)) + b"\0\0" + payload


def id3_text(frame_id, text):
    return id3_frame(frame_id, b"\x03" + text.replace("\0", "").encode("utf-8"))


def build_id3(tags):
    frames = [id3_text("TIT2", tags["title"]), id3_text("TALB", tags["album"]), id3_text("TPE1", tags["artist"]),
              id3_text("TPE2", tags["albumartist"]), id3_text("TRCK", str(tags["track"])),
              id3_text("TDRC", tags["date"]), id3_text("TCON", tags["genre"])]
    if tags.get("comment"):
        frames.append(id3_frame("COMM", b"\x03" + b"pol" + b"\0" + tags["comment"].replace("\0", "").encode("utf-8")))
    body = b"".join(frames)
    return b"ID3\x04\x00\x00" + syncsafe(len(body)) + body


def write_mp3(src, dst, tags):
    with open(src, "rb") as f, open(dst, "wb") as out:
        f.seek(id3v2_length(f.read(10)))
        out.write(build_id3(tags))
        shutil.copyfileobj(f, out, 1 << 20)


def box(kind, payload):
    return struct.pack(">I4s", 8 + len(payload), kind) + payload


def boxes(data, start=0, end=None):
    end = len(data) if end is None else end
    out, pos = [], start
    while pos + 8 <= end:
        size, kind = struct.unpack_from(">I4s", data, pos)
        if size == 1:
            size = struct.unpack_from(">Q", data, pos + 8)[0]
        elif size == 0:
            size = end - pos
        if size < 8 or pos + size > end:
            break
        out.append((kind, pos, size))
        pos += size
    return out


def mp4_item(kind, payload, flag=1):
    return box(kind, box(b"data", struct.pack(">II", flag, 0) + payload))


def build_meta(tags):
    items = [mp4_item(b"\xa9nam", tags["title"].encode("utf-8")), mp4_item(b"\xa9alb", tags["album"].encode("utf-8")),
             mp4_item(b"\xa9ART", tags["artist"].encode("utf-8")), mp4_item(b"aART", tags["albumartist"].encode("utf-8")),
             mp4_item(b"\xa9day", tags["date"].encode("utf-8")), mp4_item(b"\xa9gen", tags["genre"].encode("utf-8")),
             mp4_item(b"trkn", struct.pack(">HHHH", 0, min(tags["track"], 65535), 0, 0), flag=0)]
    if tags.get("comment"):
        items.append(mp4_item(b"\xa9cmt", tags["comment"].encode("utf-8")))
    hdlr = box(b"hdlr", b"\0\0\0\0" + b"\0\0\0\0" + b"mdir" + b"appl" + b"\0" * 8 + b"\0")
    return box(b"meta", b"\0\0\0\0" + hdlr + box(b"ilst", b"".join(items)))


def patch_offsets(moov, start, end, threshold, delta):
    for kind, pos, size in boxes(moov, start, end):
        if kind in (b"trak", b"mdia", b"minf", b"stbl"):
            patch_offsets(moov, pos + 8, pos + size, threshold, delta)
        elif kind in (b"stco", b"co64"):
            wide = kind == b"co64"
            count = struct.unpack_from(">I", moov, pos + 12)[0]
            fmt, step = (">Q", 8) if wide else (">I", 4)
            for i in range(count):
                at = pos + 16 + i * step
                offset = struct.unpack_from(fmt, moov, at)[0]
                if offset >= threshold:
                    struct.pack_into(fmt, moov, at, offset + delta)


def write_m4a(src, dst, tags):
    with open(src, "rb") as f:
        total = f.seek(0, 2)
        f.seek(0)
        top, pos = [], 0
        while pos + 8 <= total:
            f.seek(pos)
            size, kind = struct.unpack(">I4s", f.read(8))
            if size == 1:
                size = struct.unpack(">Q", f.read(8))[0]
            elif size == 0:
                size = total - pos
            if size < 8 or pos + size > total:
                raise ValueError("uszkodzony plik m4a")
            top.append((kind, pos, size))
            pos += size
        moov_entry = next((b for b in top if b[0] == b"moov"), None)
        if not moov_entry:
            raise ValueError("w pliku m4a nie ma atomu moov")
        _, moov_pos, moov_size = moov_entry
        f.seek(moov_pos)
        old = f.read(moov_size)
        header = 16 if struct.unpack_from(">I", old)[0] == 1 else 8
        children = []
        for kind, pos, size in boxes(old, header, len(old)):
            part = old[pos:pos + size]
            if kind == b"udta":
                keep = [part[p:p + s] for k, p, s in boxes(part, 8, len(part)) if k != b"meta"]
                part = box(b"udta", b"".join(keep))
            children.append((kind, part))
        udta = box(b"udta", build_meta(tags))
        if any(k == b"udta" for k, _ in children):
            children = [(k, (box(b"udta", p[8:] + build_meta(tags)) if k == b"udta" else p)) for k, p in children]
        else:
            children.append((b"udta", udta))
        new = bytearray(box(b"moov", b"".join(p for _, p in children)))
        patch_offsets(new, 8, len(new), moov_pos + moov_size, len(new) - moov_size)
        with open(dst, "wb") as out:
            for kind, pos, size in top:
                if kind == b"moov":
                    out.write(new)
                else:
                    f.seek(pos)
                    left = size
                    while left:
                        chunk = f.read(min(left, 1 << 20))
                        if not chunk:
                            raise ValueError("plik m4a się urwał")
                        out.write(chunk)
                        left -= len(chunk)


def sniff(path):
    with open(path, "rb") as f:
        head = f.read(12)
    if head[4:8] == b"ftyp":
        return "m4a"
    if head[:3] == b"ID3" or (len(head) > 1 and head[0] == 0xFF and head[1] & 0xE0 == 0xE0):
        return "mp3"
    return None


def safe_name(name, limit=100):
    name = re.sub(r'[\\/:*?"<>|\x00-\x1f]', " ", unicodedata.normalize("NFC", name))
    return re.sub(r"\s+", " ", name).strip(" .")[:limit].rstrip(" .") or "bez tytulu"


def download_file(url, dest, limit):
    with open_url(url) as response, open(dest, "wb") as out:
        total = 0
        while True:
            chunk = response.read(1 << 20)
            if not chunk:
                return total
            total += len(chunk)
            if total > limit:
                raise ValueError("plik jest za duży")
            out.write(chunk)


def save_cover(url, folder, log):
    if not url or any(folder.glob("cover.*")):
        return
    tmp = folder / ".cover.part"
    try:
        download_file(url, tmp, MAX_COVER_BYTES)
        head = tmp.read_bytes()[:4]
        ext = "jpg" if head[:2] == b"\xff\xd8" else "png" if head[:4] == b"\x89PNG" else None
        if ext:
            tmp.replace(folder / f"cover.{ext}")
    except (OSError, ValueError) as e:
        log(f"    Okładka audycji się nie pobrała: {e}")
    finally:
        tmp.unlink(missing_ok=True)


def download_episode(show, episode, music_dir, log):
    folder = Path(music_dir) / FOLDER / safe_name(show["name"])
    folder.mkdir(parents=True, exist_ok=True)
    raw, final_tmp = folder / ".episode.part", folder / ".tagged.part"
    try:
        download_file(episode["url"], raw, MAX_EPISODE_BYTES)
        kind = sniff(raw)
        if not kind:
            raise ValueError("to nie jest plik audio mp3 ani m4a")
        tags = {"title": episode["title"] or "Odcinek", "album": show["name"], "artist": show["name"],
                "albumartist": show["name"], "track": episode["track"], "date": episode["date"].strftime("%Y-%m-%d"),
                "genre": GENRE, "comment": episode["description"]}
        (write_mp3 if kind == "mp3" else write_m4a)(raw, final_tmp, tags)
        name = f"{tags['date']} {safe_name(episode['title'])}.{kind}"
        target = folder / name
        if target.exists():
            target = folder / f"{tags['date']} {safe_name(episode['title'], 80)} {abs(hash(episode['guid'])) % 10000}.{kind}"
        final_tmp.replace(target)
        return target.relative_to(music_dir).as_posix()
    finally:
        raw.unlink(missing_ok=True)
        final_tmp.unlink(missing_ok=True)


def sync_show(show, music_dir, state, log, free_ok=lambda: True, episodes=None):
    seen = state.setdefault("podcasts", {}).setdefault(show["name"], {"episodes": {}})["episodes"]
    feed = fetch_feed(show["url"])
    if episodes is None:
        episodes = [e for e in feed["episodes"][:show["last"]] if e["guid"] not in seen]
    got = 0
    for episode in episodes:
        if not free_ok():
            log("Podcasty: za mało miejsca na dysku — przerywam.")
            break
        try:
            log(f"🎙 {show['name']} — {episode['title'][:80]}")
            relative = download_episode(show, episode, music_dir, log)
        except (OSError, ValueError, urllib.error.URLError, ET.ParseError) as e:
            log(f"    nie udało się ({e}) — spróbuję przy następnym sprawdzeniu")
            continue
        seen[episode["guid"]] = {"file": relative, "date": episode["date"].date().isoformat(),
                                 "title": episode["title"], "status": "done",
                                 "fetched": datetime.now().date().isoformat()}
        got += 1
    if got:
        save_cover(feed["cover"], Path(music_dir) / FOLDER / safe_name(show["name"]), log)
    return got


def select_older(show, state, year=None, pattern=None):
    seen = state.get("podcasts", {}).get(show["name"], {}).get("episodes", {})
    regex = re.compile(pattern, re.I) if pattern else None
    return [e for e in fetch_feed(show["url"])["episodes"]
            if (not year or e["date"].year == year) and (not regex or regex.search(e["title"]))
            and seen.get(e["guid"], {}).get("status") != "done"]


def library_key(song):
    path, library = (song.get("path") or "").replace("\\", "/"), (song.get("libraryPath") or "").rstrip("/")
    if library and path.startswith(library + "/"):
        path = path[len(library) + 1:]
    return unicodedata.normalize("NFC", path.lstrip("/"))


def parse_time(value):
    try:
        return datetime.fromisoformat((value or "").replace("Z", "+00:00"))
    except ValueError:
        return None


def cleanup(songs, shows, state, music_dir, log, now=None):
    now = now or datetime.now(timezone.utc)
    keep = {s["name"]: s["keep"] for s in shows}
    by_path = {library_key(s): s for s in songs}
    removed = 0
    for name, show_state in state.get("podcasts", {}).items():
        for episode in show_state["episodes"].values():
            if episode.get("status") != "done":
                continue
            song = by_path.get(unicodedata.normalize("NFC", episode["file"]))
            last = parse_time(song.get("playDate")) if song else None
            if not song or not song.get("playCount") or not last or song.get("starred"):
                continue
            if last.tzinfo is None:
                last = last.replace(tzinfo=timezone.utc)
            if now - last < timedelta(days=keep.get(name, 30)):
                continue
            path = Path(music_dir) / episode["file"]
            try:
                path.unlink(missing_ok=True)
            except OSError as e:
                log(f"Podcasty: nie usunąłem {path.name}: {e}")
                continue
            episode["status"] = "deleted"
            removed += 1
            log(f"🗑 Odsłuchany ponad {keep.get(name, 30)} dni temu: {name} — {episode['title'][:70]}")
    return removed
