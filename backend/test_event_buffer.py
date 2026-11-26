"""Run from the repository root: python -m unittest discover -s backend -p 'test_*.py'."""
import unittest
from concurrent.futures import ThreadPoolExecutor

from event_buffer import EventBuffer


class EventBufferTest(unittest.TestCase):
    def test_bounded_history(self):
        buffer = EventBuffer()
        for i in range(105):
            buffer.append(str(i))
        snapshot = buffer.snapshot()
        self.assertEqual(len(snapshot["events"]), 100)
        self.assertEqual(snapshot["events"][0]["id"], 6)
        self.assertEqual(snapshot["events"][-1]["id"], 105)
        self.assertEqual(buffer.messages(), [str(i) for i in range(5, 105)])

    def test_duplicate_text_has_distinct_ids(self):
        buffer = EventBuffer()
        buffer.append("same timestamp and message")
        before = buffer.snapshot()
        for _ in range(3):
            buffer.append("same timestamp and message")
        after = buffer.snapshot()
        last_id = before["events"][-1]["id"]
        self.assertEqual([e["id"] for e in after["events"] if e["id"] > last_id], [2, 3, 4])
        self.assertEqual([e for e in after["events"] if e["id"] > 4], [])

    def test_restart_and_snapshot_isolation(self):
        buffer = EventBuffer()
        buffer.append("original")
        snapshot = buffer.snapshot()
        snapshot["events"][0]["message"] = "changed"
        self.assertEqual(buffer.messages(), ["original"])
        restarted = EventBuffer()
        self.assertNotEqual(snapshot["stream_id"], restarted.snapshot()["stream_id"])
        restarted.append("new")
        self.assertEqual(restarted.snapshot()["events"][0]["id"], 1)

    def test_concurrent_producers(self):
        buffer = EventBuffer()
        with ThreadPoolExecutor(max_workers=4) as pool:
            list(pool.map(buffer.append, map(str, range(200))))
        self.assertEqual([e["id"] for e in buffer.snapshot()["events"]], list(range(101, 201)))


if __name__ == "__main__":
    unittest.main()
