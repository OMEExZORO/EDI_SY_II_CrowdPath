/**
 * config.h — Pin assignments and constants for CrowdPath Smart Cane
 * Supports both ESP8266 (WiFi/UDP) and ESP32 (BLE) hardware.
 */
#pragma once

// ── Device Identity ───────────────────────────────────────────────────────────
#define DEVICE_NAME          "SmartCane-ESP8266"

// ── WiFi AP config (ESP8266 mode) ─────────────────────────────────────────────
#define WIFI_SSID            "SmartCane"         // Phone connects to this hotspot
#define WIFI_PASS            "crowdpath"          // Password (min 8 chars for WPA2)
#define CANE_UDP_PORT        4210                 // ESP8266 listens here
#define PHONE_UDP_PORT       4211                 // App listens here for status

// ── BLE UUIDs (ESP32 — kept for future migration) ────────────────────────────
// #define SERVICE_UUID      "0000ffe0-0000-1000-8000-00805f9b34fb"
// #define COMMAND_CHAR_UUID "0000ffe1-0000-1000-8000-00805f9b34fb"
// #define STATUS_CHAR_UUID  "0000ffe2-0000-1000-8000-00805f9b34fb"

// ── GPIO Pins ────────────────────────────────────────────────────────────────
// ESP8266 NodeMCU pin mapping (D-pin labels → GPIO numbers)
#define PIN_TRIG             14   // D5 → HC-SR04 trigger
#define PIN_ECHO             12   // D6 → HC-SR04 echo (3.3V safe on ESP8266)
#define PIN_MOTOR            13   // D7 → Vibration motor / buzzer SIG
#define PIN_BUTTON           0    // D3 → Push button (has onboard 10kΩ pull-up)
// A0 → Battery ADC (0–1V range on ESP8266, use voltage divider)

// ── Thresholds ───────────────────────────────────────────────────────────────
#define OBSTACLE_THRESHOLD_CM  60   // Below this → trigger Pattern 6
                                    // 60cm = ~0.5s reaction at walking pace

// ── Pattern IDs (mirror VibrationPatterns.kt) ────────────────────────────────
#define PATTERN_TURN_LEFT      1
#define PATTERN_TURN_RIGHT     2
#define PATTERN_NEAR_TURN      3
#define PATTERN_STAIRS_AHEAD   4
#define PATTERN_ARRIVED        5
#define PATTERN_STOP_OBSTACLE  6

// ── Battery (A0 on ESP8266: 0–1V ADC, use external divider: VBAT→100kΩ→A0→100kΩ→GND) ──
#define BATTERY_FULL_V         4.20f
#define BATTERY_EMPTY_V        3.00f
#define VDIVIDER_RATIO         4.2f   // for 100k+100k divider + 1V ADC max on ESP8266
