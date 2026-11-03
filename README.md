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
    DET --> API["Flask /alerts<br/>JSON"]
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
    E --> G["Update backend in-memory alerts"]
    F --> G
    G --> H["GET /alerts returns JSON array"]
    H --> I["Android OkHttp polling"]
    I --> J["Read latest array element"]
    J --> K["Update local alert history"]
    K --> L{"Notification enabled<br/>and keyword matched?"}
    L -->|Yes| M["Broadcast to AlertReceiver"]
    M --> N["Android notification"]
    L -->|No| O["History / UI only"]
```

The current client processes the **last element** in the returned alert array on every poll. It does not use server-side event IDs or a robust deduplication mechanism.

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
| Polling | `GET /alerts` with OkHttp, scheduled approximately every **1,000 ms** |
| Event selection | Parse the JSON array and take its latest element |
| History | Insert timestamped entries; retain up to **10** local records |
| UI updates | Notify the active listener when the history changes |
| Filtering | Check notification enable switch and configured keyword matches |
| Delivery | Broadcast qualifying alerts to `AlertReceiver` |

**Known behavior:** If the latest backend alert remains unchanged, successive polls can append repeated records. Event IDs and deduplication are not implemented.

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
| [`backend/requirements.txt`](backend/requirements.txt) | Python dependencies |

The backend stores alerts in a Python list **in memory**; it is not a persistent event database.

## API and Technology Choices

### Flask Endpoints

| Endpoint | Purpose |
|---|---|
| `GET /` | Backend status text |
| `GET /video_feed` | Processed MJPEG video stream |
| `GET /alerts` | JSON array containing alert strings |
| `GET /video` | Browser-based MJPEG and alert display |

### Engineering Rationale

| Choice | Benefit | Trade-off |
|---|---|---|
| Direct RTSP playback with LibVLC | Dedicated mobile live-view path | Sensitive to network and player lifecycle |
| MediaPipe landmarks + rules | Explainable event conditions | Thresholds depend on scene and viewpoint |
| Flask + JSON | Simple backend/client interface | Development setup is not production-hardened |
| OkHttp polling | Straightforward periodic integration | Repeats, network overhead, and delivery latency |
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
FLASK_URL = http://server-ip:5000/alerts
```

**Build limitation:** This public repository contains selected Android Java files and Gradle configuration but does **not** include the full Android manifest and resource files required for a confirmed standalone application build. Treat it as a source-code portfolio, not a verified one-command deployment package.

**Security note:** Never commit actual camera credentials. The current Flask development configuration uses `debug=True` and binds to `0.0.0.0`; do not expose this server directly to the public internet. The sample HTTP-based alert API does not provide production-grade authentication or encrypted transport.

## Engineering Trade-offs and Limitations

| Area | Current status / limitation |
|---|---|
| Detection coverage | Four of nine detector functions enabled in the public loop |
| Detection accuracy | No reproducible evaluation dataset or per-class accuracy report in this repository |
| Event reliability | No event IDs or deduplication in Android polling |
| Persistence | Backend alert list resets on restart |
| Streaming | Android RTSP and Flask capture are separately configured |
| Deployment | Local development configuration; Android source set is incomplete |
| Testing | No verified clean build or automated end-to-end test report included |

### Future Work

- Introduce event IDs, acknowledgement, deduplication, and bounded backend history.
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
