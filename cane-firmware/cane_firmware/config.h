/**
 * config.h — Pin assignments and constants for CrowdPath Smart Cane
 */
#pragma once

// ── Device Identity ───────────────────────────────────────────────────────────
#define DEVICE_NAME          "SmartCane-ESP32"

// Must match BLEProtocol.kt exactly
#define SERVICE_UUID         "0000ffe0-0000-1000-8000-00805f9b34fb"
#define COMMAND_CHAR_UUID    "0000ffe1-0000-1000-8000-00805f9b34fb"  // phone → cane (Write)
#define STATUS_CHAR_UUID     "0000ffe2-0000-1000-8000-00805f9b34fb"  // cane → phone (Notify)

// ── GPIO Pins ────────────────────────────────────────────────────────────────
#define PIN_TRIG             5    // HC-SR04 trigger
#define PIN_ECHO             18   // HC-SR04 echo (3.3V via voltage divider)
#define PIN_MOTOR            25   // Pre-built vibration motor module SIG
#define PIN_BUTTON           32   // Push button (10kΩ pull-down)
#define PIN_BATTERY_ADC      34   // Battery ADC (optional — reads voltage divider)

// ── Thresholds ───────────────────────────────────────────────────────────────
#define OBSTACLE_THRESHOLD_CM  40   // Below this → trigger Pattern 6

// ── Pattern IDs (mirror VibrationPatterns.kt) ────────────────────────────────
#define PATTERN_TURN_LEFT      1
#define PATTERN_TURN_RIGHT     2
#define PATTERN_NEAR_TURN      3
#define PATTERN_STAIRS_AHEAD   4
#define PATTERN_ARRIVED        5
#define PATTERN_STOP_OBSTACLE  6

// ── Battery (optional voltage divider: VBAT → 100kΩ → PIN34 → 100kΩ → GND) ──
#define BATTERY_FULL_V         4.20f
#define BATTERY_EMPTY_V        3.00f
#define ADC_REF_V              3.30f
#define ADC_MAX_COUNTS         4095.0f
#define VDIVIDER_RATIO         2.0f   // adjust if different resistors used
