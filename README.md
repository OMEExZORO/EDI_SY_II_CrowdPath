# 🦯 CrowdPath — Smart Cane Indoor Navigation System

> **Phase 1** — Software-only implementation with BLE cane emulator.

An indoor navigation system for visually impaired users, powered by crowdsourced building maps. Sighted volunteers map buildings using ARCore; blind users navigate using step counting, WiFi fingerprint matching, and turn-by-turn voice guidance — with vibration feedback sent to a smart cane (software emulator in Phase 1).

---

## Architecture

```
┌──────────────┐     BLE      ┌──────────────────┐     REST     ┌──────────────┐
│  Cane        │◄────────────►│  Android Main    │◄────────────►│  Backend     │
│  Emulator    │  commands /  │  App             │  maps /      │  Server      │
│  (Android)   │  status      │  (Navigation +   │  upload      │  (FastAPI +  │
│              │              │   Mapping)       │              │  SQLite)     │
└──────────────┘              └──────────────────┘              └──────────────┘
```

| Component | Technology | Purpose |
|-----------|-----------|---------|
| **Backend** | Python FastAPI + SQLite | Crowdsourced map storage & merging |
| **Android App** | Kotlin + Jetpack Compose | ARCore mapping + navigation |
| **Cane Emulator** | Kotlin + Compose | Simulates BLE smart cane |

---

## Project Structure

```
project_root/
├── android-app/           # Main navigation app (Kotlin)
│   └── app/src/main/java/com/crowdpath/app/
│       ├── data/          # Models, Room DB, Retrofit, Repository
│       ├── mapping/       # ARCoreMapper, GraphBuilder, WiFiScanner
│       ├── navigation/    # PathPlanner, PDRTracker, TTSGuide, NavigationEngine
│       ├── ble/           # CaneClient, BLEProtocol
│       └── ui/            # Compose screens
├── cane-emulator/         # BLE cane emulator (Kotlin)
│   └── app/src/main/java/com/crowdpath/emulator/
│       ├── BLEServer.kt
│       ├── CommandLogger.kt
│       ├── StatusBroadcaster.kt
│       ├── VibrationVisualizer.kt
│       └── EmulatorScreen.kt
├── backend/               # FastAPI server
│   ├── main.py
│   ├── routes.py
│   ├── models.py
│   ├── schemas.py
│   ├── merge.py
│   ├── database.py
│   ├── seed_data.py
│   └── tests/
└── docs/
    ├── ARCHITECTURE.md
    └── API.md
```

---

## Prerequisites

| Tool | Version |
|------|---------|
| Python | 3.10+ |
| Android Studio | Hedgehog+ |
| Android SDK | API 34 |
| JDK | 17 |

---

## Quick Start

### 1. Backend Server

```bash
cd backend
pip install -r requirements.txt

# Start server
uvicorn main:app --reload --host 0.0.0.0 --port 8000

# Seed sample data (in another terminal)
python seed_data.py
```

The API will be available at `http://localhost:8000`. Swagger docs at `http://localhost:8000/docs`.

### 2. Android Main App

1. Open `android-app/` in Android Studio
2. Sync Gradle dependencies
3. Set backend URL in `RetrofitClient.kt` → `BASE_URL`
   - Emulator: `http://10.0.2.2:8000/`
   - Physical device: `http://<your-ip>:8000/`
4. Build & run on device/emulator (API 26+)

### 3. Cane Emulator App

1. Open `cane-emulator/` in Android Studio
2. Build & run on a **second Android device** (needs real BLE hardware)
3. Tap **Start** to begin BLE advertising
4. The main app will auto-connect when scanning

> **Note:** BLE between two emulators is not supported. Use two physical devices or one physical device + one emulator (for the main app).

---

## Running Tests

### Backend Tests

```bash
cd backend
pytest tests/ -v
```

### Seed Data

```bash
cd backend
python seed_data.py
# Uploads a 7-node, 6-edge sample building map (CSE Department Block)
```

---

## Key Features

| Feature | Implementation |
|---------|---------------|
| Indoor mapping | ARCore pose tracking + WiFi fingerprinting |
| Pathfinding | A* algorithm via JGraphT |
| Step counting | Android `TYPE_STEP_DETECTOR` sensor |
| Heading tracking | `TYPE_ROTATION_VECTOR` sensor with smoothing |
| Position confirmation | WiFi BSSID fingerprint matching |
| TTS guidance | Android TextToSpeech (0.9× rate) |
| BLE cane control | Nordic BLE library (client) + Android GATT (server) |
| Map merging | Node proximity matching + WiFi fingerprint averaging |

### Vibration Patterns

| Pattern | Meaning |
|---------|---------|
| 1 | Turn left |
| 2 | Turn right |
| 3 | Approaching turn |
| 4 | Stairs ahead |
| 5 | Arrived at destination |
| 6 | STOP — obstacle detected |

---

## Privacy

- Photos are **optional** (consent toggle in Settings)
- No raw photos uploaded without explicit permission
- WiFi fingerprints store only BSSID + RSSI, not user data
- Cached data can be deleted from Settings

---

## Phase 2 Roadmap

- ESP32 hardware cane with actual vibration motors + ultrasonic sensor
- Multi-floor elevator tracking
- Outdoor ↔ indoor transition (GPS → WiFi)
- Community map review & moderation
- Accessibility audit compliance
