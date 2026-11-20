"""Publish completed push-center archives for the read-only investigator."""
import argparse
import filecmp
import gzip
import os
import re
import shutil
import tempfile
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

NAME = re.compile(r"b-push-center-server\.log\.(\d{4}-\d{2}-\d{2})\.[A-Za-z0-9_.-]+\.gz")
PROJECT_LOGS = Path(__file__).resolve().parents[1] / "logs"


def business_day(path):
    match = NAME.fullmatch(path.name)
    if match and not path.is_symlink() and path.is_file():
        try:
            return date.fromisoformat(match[1])
        except ValueError:
            pass
    return None


def publish(source, destination):
    final = destination / source.name
    if final.exists() or final.is_symlink():
        check_same(source, final)
        return
    with tempfile.NamedTemporaryFile(dir=destination, suffix=".part", delete=False) as out:
        staged = Path(out.name)
    try:
        shutil.copyfile(source, staged)
        with gzip.open(staged, "rb") as archive:
            while archive.read(1024 * 1024):
                pass
        # Inherit group/other read access from the source, without group/other write.
        staged.chmod(0o600 | (source.stat().st_mode & 0o044))
        try:
            if os.name == "nt":
                os.rename(staged, final)  # Windows rejects an existing target.
            else:
                os.link(staged, final)    # Atomic publication without overwriting.
        except FileExistsError:
            check_same(staged, final)
    finally:
        staged.unlink(missing_ok=True)


def check_same(source, final):
    if final.is_symlink() or not final.is_file() or not filecmp.cmp(source, final, shallow=False):
        raise ValueError(f"Conflicting archive: {final.name}")


def sync(source, destination, today, replay=False):
    destination = destination.resolve()
    if source.is_symlink() or not (source.is_file() or source.is_dir()):
        raise ValueError("Expected an archive file or source directory")
    if (source if source.is_dir() else source.parent).resolve() == destination:
        raise ValueError("Source and published archives must use separate directories")
    if source.is_file() and business_day(source) is None:
        raise ValueError("Unrecognized archive filename")
    if replay and not source.is_file():
        raise ValueError("Replay requires one named archive file")

    destination.mkdir(parents=True, exist_ok=True)
    oldest = today - timedelta(days=4)
    files = [source] if source.is_file() else sorted(source.iterdir())
    included = 0
    for path in files:
        day = business_day(path)
        if day and (replay or oldest <= day <= today):
            publish(path, destination)
            included += 1

    expired = 0
    if not replay:
        for path in destination.iterdir():
            day = business_day(path)
            if day and day < oldest:
                path.unlink(missing_ok=True)
                expired += 1
    return included, expired


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--destination", type=Path, default=PROJECT_LOGS)
    parser.add_argument("--replay", action="store_true", help="Publish one historical sample and skip pruning")
    args = parser.parse_args()
    today = datetime.now(timezone(timedelta(hours=8))).date()
    included, expired = sync(args.source, args.destination, today, args.replay)
    print(f"Business date {today}: {included} archive(s) available from input, {expired} expired archive(s) removed")
