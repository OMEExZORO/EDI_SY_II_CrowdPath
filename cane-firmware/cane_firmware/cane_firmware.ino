/**
 * CrowdPath Smart Cane — ESP32 Firmware
 * ======================================
 * BLE GATT Server that:
 *  - Advertises as "SmartCane-ESP32"
 *  - Receives vibration/nav commands from the Android app
 *  - Reads HC-SR04 ultrasonic distance every 500ms
 *  - Auto-triggers Pattern 6 (STOP) when obstacle < 40cm
 *  - Sends status JSON (battery_v, ultra_cm) every 2s via BLE notify
 *  - Accepts Serial debug commands: v1..v6, ping
 *
 * UUIDs match BLEProtocol.kt in the Android app exactly.
 *
 * GPIO Map:
 *   GPIO 5  → HC-SR04 TRIG
 *   GPIO 18 → HC-SR04 ECHO (via 1kΩ+2kΩ voltage divider for 3.3V safety)
 *   GPIO 25 → Vibration motor module SIG
 *   GPIO 32 → Push button (10kΩ pull-down to GND)
 *   GPIO 34 → Battery voltage ADC (optional: connect via 2:1 voltage divider)
 */

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
// ArduinoJson removed — JSON handled with plain sprintf/strstr in ble_server.h

// ── Module includes ──────────────────────────────────────────────────────────
#include "config.h"
#include "vibration.h"
#include "ultrasonic.h"
#include "ble_server.h"

// ── Timing ───────────────────────────────────────────────────────────────────
static unsigned long lastStatusMs   = 0;
static unsigned long lastUltraMs    = 0;
static unsigned long lastButtonMs   = 0;

static const unsigned long STATUS_INTERVAL_MS   = 2000;
static const unsigned long ULTRA_INTERVAL_MS    = 500;
static const unsigned long BUTTON_DEBOUNCE_MS   = 50;

// ── State ─────────────────────────────────────────────────────────────────────
static int  currentUltraCm       = 999;
static bool lastObstacleState    = false;
static bool buttonPressed        = false;

// ── Setup ─────────────────────────────────────────────────────────────────────
void setup() {
    Serial.begin(115200);
    Serial.println(F("\n=== CrowdPath Smart Cane Firmware ==="));

    vibration_init();
    ultrasonic_init();
    ble_init();

    pinMode(PIN_BUTTON, INPUT);  // external 10kΩ pull-down

    Serial.println(F("Ready. BLE advertising as: " DEVICE_NAME));
    Serial.println(F("Serial commands: v1..v6 (patterns), ping (ultrasonic)"));

    // Startup confirmation — fire pattern 5 briefly (arrived tone)
    vibration_fire(5);
}

// ── Loop ──────────────────────────────────────────────────────────────────────
void loop() {
    unsigned long now = millis();

    // 1. Read ultrasonic every 500ms
    if (now - lastUltraMs >= ULTRA_INTERVAL_MS) {
        lastUltraMs = now;
        currentUltraCm = ultrasonic_read_cm();

        // Auto-trigger obstacle pattern when < OBSTACLE_THRESHOLD_CM
        bool obstacleNow = (currentUltraCm > 0 && currentUltraCm < OBSTACLE_THRESHOLD_CM);
        if (obstacleNow && !lastObstacleState) {
            Serial.printf("[OBSTACLE] Distance: %d cm — firing Pattern 6\n", currentUltraCm);
            vibration_fire(PATTERN_STOP_OBSTACLE);
        }
        lastObstacleState = obstacleNow;
    }

    // 2. Send BLE status every 2s (only if device connected)
    if (now - lastStatusMs >= STATUS_INTERVAL_MS) {
        lastStatusMs = now;
        ble_send_status(currentUltraCm);
    }

    // 3. Push button — emergency stop (pattern 6) with debounce
    bool btnState = digitalRead(PIN_BUTTON) == HIGH;
    if (btnState && !buttonPressed && (now - lastButtonMs > BUTTON_DEBOUNCE_MS)) {
        lastButtonMs = now;
        buttonPressed = true;
        Serial.println(F("[BUTTON] Emergency stop triggered"));
        vibration_fire(PATTERN_STOP_OBSTACLE);
    }
    if (!btnState) buttonPressed = false;

    // 4. Serial debug commands
    if (Serial.available()) {
        String cmd = Serial.readStringUntil('\n');
        cmd.trim();
        handle_serial_command(cmd);
    }

    // 5. Process any pending BLE vibration command (set by callback)
    ble_process_pending();

    delay(10);  // yield
}

// ── Serial debug handler ──────────────────────────────────────────────────────
void handle_serial_command(const String& cmd) {
    if (cmd == "ping") {
        int d = ultrasonic_read_cm();
        Serial.printf("[PING] Distance: %d cm\n", d);
    } else if (cmd.startsWith("v") && cmd.length() == 2) {
        int p = cmd.substring(1).toInt();
        if (p >= 1 && p <= 6) {
            Serial.printf("[SERIAL] Firing pattern %d\n", p);
            vibration_fire(p);
        } else {
            Serial.println(F("[SERIAL] Invalid pattern. Use v1..v6"));
        }
    } else {
        Serial.printf("[SERIAL] Unknown command: %s\n", cmd.c_str());
        Serial.println(F("Commands: v1..v6, ping"));
    }
}
