# CrowdPath Smart Cane — ESP32 Firmware

## Hardware You Have (Final BOM)
| Component | Qty | Notes |
|-----------|-----|-------|
| ESP32 Dev Board | 1 | DOIT DevKit V1 or similar |
| HC-SR04 Ultrasonic Sensor | 1 | |
| Pre-built Vibration Motor Module | 1 | Has driver built-in — direct GPIO control |
| Push Button | 1 | From Arduino kit |
| Jumper Wires | — | From Arduino kit |
| 10kΩ Resistor | 1 | Button pull-down (in kit) |
| 1kΩ + 2kΩ Resistors | 1 each | Echo pin voltage divider (in kit) |
| USB Cable / Power Bank | 1 | Power during testing |

> You have **everything**. Nothing extra needed for testing.

---

## Wiring Diagram

```
ESP32 Pin   →   Component
─────────────────────────────────────────
GPIO 5      →   HC-SR04 TRIG
GPIO 18     →   HC-SR04 ECHO (via voltage divider: 1kΩ in series, 2kΩ to GND)
GPIO 25     →   Vibration Motor Module SIG pin
GPIO 32     →   Push Button (other leg → GND, 10kΩ from GPIO32 → GND)
3.3V        →   Vibration Motor Module VCC
5V / VIN    →   HC-SR04 VCC
GND         →   All component GNDs (common ground)
```

### Echo Voltage Divider (IMPORTANT — HC-SR04 outputs 5V, ESP32 is 3.3V):
```
HC-SR04 ECHO ──[1kΩ]──┬── GPIO18
                       │
                     [2kΩ]
                       │
                      GND
```

---

## Arduino IDE Setup

1. Install **Arduino IDE 2.x**
2. Add ESP32 board URL in Preferences:
   `https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json`
3. Install **esp32** board package (v2.x) via Board Manager
4. Install Libraries via Library Manager:
   - **ArduinoJson** by Benoit Blanchon (v6.x)
5. Select Board: `DOIT ESP32 DEVKIT V1`
6. Select Port: your COM port
7. Open `cane_firmware.ino` and Upload

---

## BLE Protocol (matches Android app exactly)

| Item | Value |
|------|-------|
| Service UUID | `0000ffe0-0000-1000-8000-00805f9b34fb` |
| Command Char (Write) | `0000ffe1-0000-1000-8000-00805f9b34fb` |
| Status Char (Notify) | `0000ffe2-0000-1000-8000-00805f9b34fb` |
| Device Name | `SmartCane-ESP32` |
| Status interval | Every 2 seconds |

### Commands received from phone:
```json
{"cmd":"SET_VIBE","pattern":2}
{"cmd":"NAV","type":"TURN","dir":"LEFT","distance_m":5.0}
```

### Status sent to phone:
```json
{"cmd":"STATUS","battery_v":3.85,"ultra_cm":45}
```

---

## Vibration Patterns

| Pattern | Meaning | Motor Sequence |
|---------|---------|----------------|
| 1 | Turn Left | 2 long pulses (500ms on, 200ms off) |
| 2 | Turn Right | 3 short pulses (200ms on, 100ms off) |
| 3 | Near Turn | 1 medium pulse (300ms) |
| 4 | Stairs Ahead | Slow ramp: 4 pulses (150ms on, 300ms off) |
| 5 | Arrived | 1 long buzz (1000ms) |
| 6 | STOP — Obstacle | Rapid continuous buzz (50ms on, 50ms off × 5) |

---

## Testing Without Android App

Open Serial Monitor at **115200 baud** and type:
- `v1` → fires Pattern 1
- `v2` → fires Pattern 2
- `v3` → fires Pattern 3
- `v4` → fires Pattern 4
- `v5` → fires Pattern 5
- `v6` → fires Pattern 6
- `ping` → prints ultrasonic distance

BLE device will appear as `SmartCane-ESP32` — connect with the Android app or nRF Connect app.
