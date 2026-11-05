"""Stage a fresh Piterfy installation without copying any production state."""
import argparse
import os
from pathlib import Path
import shutil


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    source = Path(__file__).resolve().parent
    destination = args.destination.resolve()
    if destination.is_relative_to(source.parent.parent):
        parser.error("Choose a destination outside the Git checkout.")
    destination.mkdir(parents=True, exist_ok=True)
    if any(destination.iterdir()):
        parser.error("The destination must be empty.")

    for name in ("compose.yaml", "Caddyfile", ".env.example"):
        shutil.copyfile(source / name, destination / name)
    for name in (
        "discover/README.md", "discover/discover.py", "discover/podcasts.py",
        "discover/podcasts.txt", "discover/profile_api.py", "discover/taste.py",
        "discover/beets/.dockerignore", "discover/beets/Dockerfile", "discover/beets/config.yaml",
        "discover/beets/fix_library.py", "discover/beets/import_loop.py",
        "discover/beets/spectrum.py", "discover/caddy/Dockerfile",
        "gust/index.html", "gust/index-spotify.html", "gust/favicon.svg",
        "ops/check-disk.sh", "ops/bulk_import_liked_songs.py",
    ):
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / name, target)

    web = destination / "discover/navidrome-ui"
    web.mkdir()
    for asset in (source.parent / "web").iterdir():
        if asset.suffix in {".js", ".css", ".html", ".svg", ".png", ".webmanifest"} and not asset.name.endswith(".inc.js"):
            shutil.copyfile(asset, web / asset.name)

    backend = destination / "discover/connectserver"
    backend.mkdir()
    for module in (source.parent / "server").iterdir():
        if module.name in {".dockerignore", "Dockerfile.dj"} or (
            module.suffix == ".py" and not module.name.startswith("test_")
        ):
            shutil.copyfile(module, backend / module.name)
    shutil.copyfile(source.parent / "discovery/report_formatter.py", destination / "discover/report_formatter.py")

    for name in ("discover/state/connect", "discover/dj-state", "gust/data",
                 "library", "incoming", "slskd-incomplete", "reports", "discover-trash"):
        (destination / name).mkdir(parents=True, exist_ok=True)
    private = destination / "private"
    private.mkdir(mode=0o700)
    for name in ("msmtprc", "liked_songs.csv"):
        (private / name).touch(mode=0o600)
    fd = os.open(destination / ".env", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as env:
        if hasattr(os, "getuid"):
            env.write(f"PITERFY_UID={os.getuid()}\nPITERFY_GID={os.getgid()}\n")
        env.write((source / ".env.example").read_text(encoding="utf-8"))
    print(f"Piterfy staged in {destination}")


if __name__ == "__main__":
    main()
