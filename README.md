# Real-Time Home Monitoring & Event Detection System

An end-to-end monitoring prototype that connects **live video streaming**, **pose-based event detection**, a **Python / Flask backend**, and an **Android client** for event history and configurable notifications.

**Tech Stack:** `Java` · `Android` · `Python` · `Flask` · `MediaPipe Pose` · `OpenCV` · `RTSP` · `LibVLC` · `OkHttp` · `JSON`

> **Project context:** Team capstone project. My primary contributions focused on **Android development and end-to-end integration**, including RTSP playback, backend communication, alert history, notification filtering, and Android notifications.

## Contents

- [Project Overview](#project-overview)
- [System Architecture](#system-architecture)
- [End-to-End Data Flow](#end-to-end-data-flow)
- [Event Detection](#event-detection)
- [Android Alert Processing](#android-alert-processing)
- [Performance & Reliability Design](#performance--reliability-design)
- [Complexity Analysis](#complexity-analysis)
- [Component Design](#component-design)
- [API and Technology Choices](#api-and-technology-choices)
- [Build and Configuration](#build-and-configuration)
- [Engineering Trade-offs and Limitations](#engineering-trade-offs-and-limitations)
- [Repository Structure](#repository-structure)
- [Contributions](#contributions)

## Project Overview

This project integrates camera capture, pose analysis, rule-based detection, network communication, and mobile alert presentation into a monitoring workflow. Its primary engineering focus is **cross-component integration**, rather than any single standalone classifier.

| Subsystem | Implementation | Purpose |
|---|---|---|
| Live video | RTSP + LibVLC + Android `SurfaceView` | Display a camera stream on Android |
| Video processing | OpenCV + MediaPipe Pose | Extract human pose landmarks |
| Event detection | Python landmark and temporal rules | Evaluate movement and posture conditions |
| Backend | Flask + HTTP / JSON | Expose alerts and processed video |
| Android integration | OkHttp + `AlertManager` | Poll, store, and display event information |
| Notifications | BroadcastReceiver + notification channels | Deliver alerts matching user-selected filters |
| Preferences | `SharedPreferences` | Store notification settings, filters, and contacts |

**Video source distinction:** The Android app plays a configured **RTSP stream** directly through LibVLC. The public Flask backend currently uses a **local webcam (`cv2.VideoCapture(0)`)** for its pose-processing pipeline. These are separate video paths and are not automatically synchronized to the same source.

## System Architecture

```mermaid
flowchart LR
    CAM["Camera / RTSP Source"] -->|RTSP| APP["Android App<br/>LibVLC + SurfaceView"]
    WEBCAM["Local Webcam<br/>(public backend default)"] --> CV["Python Backend<br/>OpenCV Capture"]
    CAM -.->|Possible after source configuration| CV
    CV --> MP["MediaPipe Pose"]
    MP --> DET["Rule-based Event Detection"]
    DET --> BUF["Bounded Event Buffer<br/>Latest 100 events"]
    BUF --> API["Flask /alerts/sync<br/>JSON event IDs"]
    API -->|OkHttp polling| AM["Android AlertManager"]
    AM --> HIST["Recent Alert History / UI"]
    AM --> FILTER["Notification Keyword Filter"]
    FILTER --> RX["AlertReceiver"]
    RX --> NOTIF["Android Notifications"]
    CV --> MJPEG["Flask /video_feed<br/>MJPEG"]
```

The Android RTSP view and Flask MJPEG output are different paths. Flask does **not** relay the RTSP video into the Android LibVLC player.

## End-to-End Data Flow

```mermaid
flowchart TD
    A["Read video frame"] --> B["Convert BGR to RGB"]
    B --> C["MediaPipe pose estimation"]
    C --> D{"Pose landmarks present?"}
    D -->|No| E["Person-absence event"]
    D -->|Yes| F["Evaluate enabled pose / timing rules"]
    E --> G["Append event to bounded buffer<br/>Assign sequence ID"]
    F --> G
    G --> H["GET /alerts/sync returns bounded event snapshot"]
    H --> I["Android OkHttp polling"]
    I --> J["Process all unseen event IDs"]
    J --> K["Update local alert history"]
    K --> L{"Notification enabled<br/>and keyword matched?"}
    L -->|Yes| M["Broadcast to AlertReceiver"]
    M --> N["Android notification"]
    L -->|No| O["History / UI only"]
```

The Android client processes all unseen event IDs from a bounded snapshot of the latest 100 events. A server stream ID distinguishes backend restarts; a sequence gap is logged when older events have expired.

## Event Detection

The Python event rules inspect pose landmarks, joint angles, relative positions, and time thresholds. They are **heuristic rules**, not a trained activity-recognition model.

The source includes nine detector functions; **only four are called by default** in the current public `backend/app.py` loop.

| Detection rule | Function | Active in public loop? | Main signal |
|---|---|---|---|
| Possible fall | `first()` | No (available) | Shoulder-to-hip displacement |
| Sudden movement | `second()` | No (available) | Knee-coordinate difference |
| Prolonged stillness | `third()` | **Yes** | Hip-angle stability over time |
| Abnormal standing | `fourth()` | **Yes** | Hip-angle threshold and duration |
| Sitting | `fifth()` | **Yes** | Hip-angle posture condition |
| Possible crawling | `sixth()` | No (available) | Relative wrist and knee positions |
| Wandering | `seventh()` | No (available) | Recent nose-position variation |
| Person absence | `eighth()` | **Yes** | Missing pose landmarks |
| Prolonged abnormal posture | `ninth()` | No (available) | Combined pose thresholds and duration |

See [`backend/events.py`](backend/events.py) for the rule implementations and [`backend/app.py`](backend/app.py) for the currently enabled calls. Implementation does not imply accuracy or clinical validation.

### Example: Temporal Stillness Detection

```mermaid
flowchart TD
    A["Pose landmarks available"] --> B["Compute hip angles"]
    B --> C{"Stable compared with previous frame?"}
    C -->|No| D["Reset stable timer"]
    C -->|Yes| E{"Stable for at least 3 seconds?"}
    E -->|No| F["Continue monitoring"]
    E -->|Yes| G["Append stillness alert"]
```

This illustrates the temporal rule used in `third()`. It does **not** establish medical or health-condition detection.

## Android Alert Processing

| Stage | Current behavior |
|---|---|
| Polling | `GET /alerts/sync` with OkHttp, scheduled approximately every **1,000 ms**; at most one request in flight |
| Event selection | Process all IDs newer than the last processed ID, in ascending order |
| History | Insert timestamped entries; retain up to **10** local records |
| UI updates | Notify the active listener once per batch of newly processed events |
| Filtering | Check notification enable switch and configured keyword matches |
| Delivery | Broadcast qualifying alerts to `AlertReceiver` |

**Synchronization:** The backend retains at most 100 events. Each event receives a monotonically increasing ID within the current server process. The client skips previously processed IDs, updates the UI once per batch, and evaluates each new event for notifications. The cursor is in memory: restarting the app may replay retained events, and more than 100 new events between successful synchronizations can cause old events to expire. A sequence gap is logged, not recovered.

## Performance & Reliability Design

The event synchronization path was redesigned to bound resource usage while processing multiple new events between polling intervals.

| Design | Implementation | Engineering benefit |
|---|---|---|
| Bounded buffer | Thread-safe `deque(maxlen=100)` | Bounds the number of retained events |
| Bounded transmission | At most 100 events per response | Bounds payload and parsing work for bounded-size records |
| Incremental processing | Ordered event sequence IDs and a client cursor | Processes all retained unseen events instead of only the newest one |
| Deduplication | Skip IDs at or below `lastProcessedId` | Avoids reprocessing received IDs within a client session |
| Thread synchronization | Backend lock protects ID allocation and snapshots | Keeps event records and sequence IDs consistent across concurrent access |
| Request concurrency control | Atomic in-flight request flag | Prevents overlapping polls and out-of-order responses |
| Batch UI updates | One listener update per nonempty batch | Avoids refreshing the UI for every event |
| Sequence gap detection | Compare the incoming ID with the expected next ID | Logs potential event loss when older records have expired |
| Restart detection | Per-process stream identifier | Distinguishes backend sessions and resets the cursor |

### Example: Multiple events between polls

If the Android client last processed event 97 and the next response contains events through 100, it skips previously processed IDs, handles events 98, 99, and 100 in order, and advances its cursor to 100. A subsequent unchanged response produces no new history entries. Each new event is evaluated separately against notification filters, while the UI retains only its latest 10 records.

The server still sends a bounded snapshot, not a delta response. Synchronization is incremental on the client side. This design improves application-level delivery reliability but does not guarantee durable or exactly-once delivery across restarts, long outages, or notification failures.

## Complexity Analysis

Let N be the cumulative number of generated events, B the configured backend capacity (100), S = min(N, B) the number of retained events, and K the number of unseen events in the received snapshot (0 ≤ K ≤ S). Assume bounded-size event records and fixed-size notification settings.

| Operation | Original prototype | Updated implementation |
|---|---|---|
| Backend event storage | O(N) | O(B) maximum |
| Append event | Amortized O(1) | O(1) |
| JSON serialization | O(N) | O(S) |
| HTTP response payload | O(N) | O(S) |
| Client JSON parsing | O(N) | O(S) |
| Event selection after parsing | O(1), latest only | O(S), scan ordered IDs |
| New event handling | At most one per poll | O(K) |
| Local history storage | At most 10 records | At most 10 records |

With a fixed buffer capacity, the number of events serialized, transmitted, and scanned per successful poll is bounded independently of total runtime. Under a constant event generation rate and fixed polling frequency, cumulative event transmission changes from quadratic growth before the redesign to at most linear growth after the buffer fills. These are analytical bounds, not measured throughput, latency, or memory benchmarks.

## Component Design

### Android Client

| Component | Responsibility |
|---|---|
| [`MainActivity`](android/app/app/src/main/java/com/example/myapplication/MainActivity.java) | Live RTSP playback, `SurfaceView` lifecycle, monitoring UI |
| [`AlertManager`](android/app/app/src/main/java/com/example/myapplication/AlertManager.java) | Asynchronous polling, history, listeners, and notification filtering |
| [`AlertReceiver`](android/app/app/src/main/java/com/example/myapplication/AlertReceiver.java) | Broadcast handling and Android notifications |
| [`NotificationActivity`](android/app/app/src/main/java/com/example/myapplication/NotificationActivity.java) | Recent monitoring records |
| [`Setting`](android/app/app/src/main/java/com/example/myapplication/Setting.java) | Preferences, alert keywords, and saved contacts |
| [`Phone`](android/app/app/src/main/java/com/example/myapplication/Phone.java) | Contact list and system dialer integration |

### Python / Flask Backend

| Component | Responsibility |
|---|---|
| [`backend/app.py`](backend/app.py) | Frame capture, MediaPipe processing, detector calls, MJPEG encoding, API routes |
| [`backend/events.py`](backend/events.py) | Landmark helpers, event predicates, timing state, alert generation |
| [`backend/event_buffer.py`](backend/event_buffer.py) | Thread-safe bounded storage, event sequence IDs, stream IDs and snapshots |
| [`backend/test_event_buffer.py`](backend/test_event_buffer.py) | Unit tests for capacity, ID uniqueness, concurrency and restart isolation |
| [`backend/requirements.txt`](backend/requirements.txt) | Python dependencies |

The backend stores alerts in a thread-safe, bounded in-memory deque (latest 100 events); it is not a persistent event database.

## API and Technology Choices

### Flask Endpoints

| Endpoint | Purpose |
|---|---|
| `GET /` | Backend status text |
| `GET /video_feed` | Processed MJPEG video stream |
| `GET /alerts` | JSON array of up to 100 alert strings (browser-compatible) |
| `GET /alerts/sync` | JSON object with `stream_id` and up to 100 ordered `{id, message}` events |
| `GET /video` | Browser-based MJPEG and alert display |

### Event synchronization verification

Run the backend buffer tests from the repository root:

```bash
python -m unittest discover -s backend -p 'test_*.py'
```

Manual Android checks: poll once, append three distinct events between polls, confirm all three are processed, then poll again without changes and verify no duplicate. Send two identical messages in one second and verify both IDs are processed. Append more than 100 events between successful polls and confirm a gap is logged and only retained events are processed. Restart the backend and verify the new stream ID resets the cursor. Android build/device verification requires the complete local Android project.

### Engineering Rationale

| Choice | Benefit | Trade-off |
|---|---|---|
| Direct RTSP playback with LibVLC | Dedicated mobile live-view path | Sensitive to network and player lifecycle |
| MediaPipe landmarks + rules | Explainable event conditions | Thresholds depend on scene and viewpoint |
| Flask + JSON | Simple backend/client interface | Development setup is not production-hardened |
| OkHttp polling | Straightforward periodic integration with client-side ID deduplication | Repeated bounded snapshots and polling latency |
| SharedPreferences | Lightweight on-device settings | Not a shared or synchronized settings database |

## Build and Configuration

### Backend

From the repository root:

```bash
python -m pip install -r backend/requirements.txt
python backend/app.py
```

The public backend defaults to **local webcam index 0**. The development server uses port **5000**; for example, `http://127.0.0.1:5000/video` displays the browser page when the backend is running.

To capture from an RTSP source instead, update the video-capture configuration in `backend/app.py` and provide your own camera address.

### Android

The public Android source includes placeholder addresses that need to be configured for the local environment:

```text
RTSP_URL  = rtsp://username:password@camera-ip:554/stream1
FLASK_URL = http://server-ip:5000/alerts/sync
```

**Build limitation:** This public repository contains selected Android Java files and Gradle configuration but does **not** include the full Android manifest and resource files required for a confirmed standalone application build. Treat it as a source-code portfolio, not a verified one-command deployment package.

**Security note:** Never commit actual camera credentials. The current Flask development configuration uses `debug=True` and binds to `0.0.0.0`; do not expose this server directly to the public internet. The sample HTTP-based alert API does not provide production-grade authentication or encrypted transport.

## Engineering Trade-offs and Limitations

| Area | Current status / limitation |
|---|---|
| Detection coverage | Four of nine detector functions enabled in the public loop |
| Detection accuracy | No reproducible evaluation dataset or per-class accuracy report in this repository |
| Event reliability | ID-based deduplication within a running client; expired events cannot be recovered |
| Persistence | Backend buffer and Android cursor are in memory; restarts can lose or replay events |
| Streaming | Android RTSP and Flask capture are separately configured |
| Deployment | Local development configuration; Android source set is incomplete |
| Testing | Backend buffer unit tests included; full Android build, device and end-to-end validation remain unverified |

### Future Work

- Add persistent event storage and durable client cursors/acknowledgements; support server-side incremental responses instead of repeated bounded snapshots.
- Replace polling with WebSocket or Server-Sent Events where appropriate.
- Make source addresses configurable without editing code.
- Add secure authentication and transport for remote deployment.
- Evaluate event precision, false positives, detection latency, and recovery with recorded test cases.
- Package the missing Android resources and verify a clean build.

## Repository Structure

```text
Real-Time-Human-Activity-Monitoring/
├── backend/
│   ├── app.py
│   ├── events.py
│   ├── event_buffer.py
│   ├── test_event_buffer.py
│   └── requirements.txt
├── android/
│   ├── app/app/src/main/java/com/example/myapplication/
│   │   ├── MainActivity.java
│   │   ├── AlertManager.java
│   │   ├── AlertReceiver.java
│   │   ├── NotificationActivity.java
│   │   ├── NotificationHelper.java
│   │   ├── Phone.java
│   │   └── Setting.java
│   ├── gradle/
│   ├── build.gradle.kts
│   └── settings.gradle.kts
├── .gitignore
└── README.md
```

## Contributions

This was a **team-based capstone project**. My primary contributions were **Android development and end-to-end system integration**, including RTSP video playback, client/backend communication, event retrieval, alert history, notification filters, and Android notification handling.

The pose-based detection code is documented as part of the team's overall system and is **not presented as solely my individual work**.

## Project Team & Contributions

**Project Type:** Undergraduate Team Capstone Project  
**Institution:** National Taipei University of Education  
**Contributor:** Hoi Chun (Chris) Wat

**My Primary Contributions:**
- Developed and integrated Android application components for real-time home monitoring.
- Integrated RTSP video playback using LibVLC and managed the Android video surface lifecycle.
- Implemented backend-to-Android communication using OkHttp and JSON-based HTTP requests.
- Developed alert history management, configurable notification filtering, and Android system notification workflows.
- Contributed to end-to-end integration of video streaming, event detection, backend communication, and the mobile interface.

*This project was developed collaboratively. The repository documents the overall system architecture and selected implementation components; the contributions above describe my primary responsibilities.*
