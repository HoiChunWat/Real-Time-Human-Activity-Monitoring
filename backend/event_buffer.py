"""Thread-safe, bounded in-memory event history."""
from collections import deque
from threading import Lock
from uuid import uuid4


class EventBuffer:
    def __init__(self, capacity=100):
        if capacity < 1:
            raise ValueError("capacity must be positive")
        self._events = deque(maxlen=capacity)
        self._lock = Lock()
        self._stream_id = uuid4().hex
        self._next_id = 1

    def append(self, message):
        with self._lock:
            self._events.append({"id": self._next_id, "message": message})
            self._next_id += 1

    def snapshot(self):
        # Copy under the same lock used by append so IDs and records agree.
        with self._lock:
            return {
                "stream_id": self._stream_id,
                "events": [event.copy() for event in self._events],
            }

    def messages(self):
        return [event["message"] for event in self.snapshot()["events"]]
