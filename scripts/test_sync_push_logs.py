import gzip
import tempfile
import unittest
from datetime import date
from pathlib import Path

from sync_push_logs import sync


class ArchiveDeliveryTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        self.inbox = root / "inbox"
        self.logs = root / "logs"
        self.inbox.mkdir()
        self.logs.mkdir()
        self.today = date(2026, 11, 19)

    def archive(self, day, content=b"test", part=1):
        path = self.inbox / f"b-push-center-server.log.2026-11-{day}.{part}.gz"
        path.write_bytes(gzip.compress(content))
        return path

    def test_retention_and_historical_replay(self):
        old = self.archive(14)
        first = self.archive(15)
        last = self.archive(19)
        rotation = self.archive(19, part=2)
        self.archive(20)
        keep = self.logs / "other.gz"
        keep.touch()
        self.assertEqual(sync(old, self.logs, self.today, replay=True), (1, 0))
        self.assertEqual(sync(self.inbox, self.logs, self.today), (3, 1))
        self.assertEqual(sync(self.inbox, self.logs, self.today), (3, 0))
        self.assertCountEqual([p.name for p in self.logs.iterdir()], [first.name, last.name, rotation.name, keep.name])
        self.assertTrue(old.is_file())

    def test_corrupt_copy_or_name_conflict_preserves_published_files(self):
        archive = self.archive(19)
        sync(archive, self.logs, self.today)
        published = self.logs / archive.name
        original = published.read_bytes()
        self.archive(19, content=b"different")
        with self.assertRaises(ValueError):
            sync(archive, self.logs, self.today)
        broken = self.archive(18)
        broken.write_bytes(b"broken")
        with self.assertRaises(gzip.BadGzipFile):
            sync(broken, self.logs, self.today)
        self.assertEqual(published.read_bytes(), original)
        self.assertEqual(list(self.logs.iterdir()), [published])
        with self.assertRaises(ValueError):
            sync(self.logs, self.logs, self.today)
