/**
 * CrowdPath Smart Cane — ESP8266 Firmware (WiFi/UDP mode)
 * =========================================================
 * Creates a WiFi hotspot "SmartCane" (pass: crowdpath).
 * Phone connects to this hotspot, then app sends/receives UDP.
 *
 * Pin Map (NodeMCU ESP8266):
 *   D5 (GPIO14) → HC-SR04 TRIG
 *   D6 (GPIO12) → HC-SR04 ECHO (3.3V safe)
 *   D7 (GPIO13) → Vibration motor / buzzer SIG
 *   D3 (GPIO0)  → Push button (onboard pull-up)
 *   A0          → Battery ADC (optional voltage divider)
 *
 * Board to select in Arduino IDE:
 *   Tools → Board → ESP8266 Boards → NodeMCU 1.0 (ESP-12E Module)
 *   Upload Speed: 115200
 *   Flash Size: 4MB (FS:2MB OTA:1MB)
 */

#include <Arduino.h>
// WiFi and UDP — all built into ESP8266 board package, no external library needed
#include <ESP8266WiFi.h>
#include <WiFiUdp.h>

// ── Module includes ──────────────────────────────────────────────────────────
#include "config.h"
#include "vibration.h"
#include "ultrasonic.h"
#include "wifi_server.h"   // replaces ble_server.h

// ── Timing ───────────────────────────────────────────────────────────────────
static unsigned long lastStatusMs  = 0;
static unsigned long lastUltraMs   = 0;
static unsigned long lastButtonMs  = 0;

static const unsigned long STATUS_INTERVAL_MS  = 2000;
static const unsigned long ULTRA_INTERVAL_MS   = 500;
static const unsigned long BUTTON_DEBOUNCE_MS  = 50;

// ── State ─────────────────────────────────────────────────────────────────────
static int  currentUltraCm    = 999;
static bool lastObstacleState = false;
static bool buttonPressed     = false;

// ── Setup ─────────────────────────────────────────────────────────────────────
void setup() {
    Serial.begin(115200);
    Serial.println(F("\n=== CrowdPath Smart Cane — ESP8266 WiFi Mode ==="));

    vibration_init();
    ultrasonic_init();
    wifi_init();   // starts hotspot + UDP listener

    pinMode(PIN_BUTTON, INPUT);   // D3 has onboard pull-up on NodeMCU

    Serial.println(F("Ready. Connect phone to WiFi: SmartCane / crowdpath"));
    Serial.println(F("Serial commands: v1..v6 (patterns), ping (ultrasonic)"));

    // Startup confirmation — pattern 5 (arrived buzz)
    vibration_fire(5);
}

// ── Loop ──────────────────────────────────────────────────────────────────────
void loop() {
    unsigned long now = millis();

    // 1. Process incoming UDP commands from phone
    wifi_process_incoming();

    // 2. Read ultrasonic every 500ms
    if (now - lastUltraMs >= ULTRA_INTERVAL_MS) {
        lastUltraMs = now;
        currentUltraCm = ultrasonic_read_cm();

        bool obstacleNow = (currentUltraCm > 0 && currentUltraCm < OBSTACLE_THRESHOLD_CM);
        if (obstacleNow && !lastObstacleState) {
            Serial.printf("[OBSTACLE] %d cm — firing Pattern 6\n", currentUltraCm);
            vibration_fire(PATTERN_STOP_OBSTACLE);
        }
        lastObstacleState = obstacleNow;
    }

    // 3. Send status to phone every 2s
    if (now - lastStatusMs >= STATUS_INTERVAL_MS) {
        lastStatusMs = now;
        wifi_send_status(currentUltraCm);
    }

    // 4. Push button — emergency stop
    bool btnState = digitalRead(PIN_BUTTON) == LOW;  // LOW = pressed (pull-up)
    if (btnState && !buttonPressed && (now - lastButtonMs > BUTTON_DEBOUNCE_MS)) {
        lastButtonMs  = now;
        buttonPressed = true;
        Serial.println(F("[BUTTON] Emergency stop"));
        vibration_fire(PATTERN_STOP_OBSTACLE);
    }
    if (!btnState) buttonPressed = false;

    // 5. Execute any pending vibration command
    wifi_process_pending();

    // 6. Serial debug commands
    if (Serial.available()) {
        String cmd = Serial.readStringUntil('\n');
        cmd.trim();
        if (cmd == "ping") {
            int d = ultrasonic_read_cm();
            Serial.printf("[PING] Distance: %d cm\n", d);
        } else if (cmd.startsWith("v") && cmd.length() == 2) {
            int p = cmd.substring(1).toInt();
            if (p >= 1 && p <= 6) {
                Serial.printf("[SERIAL] Firing pattern %d\n", p);
                vibration_fire(p);
            }
        } else if (cmd == "ip") {
            Serial.printf("[INFO] AP IP: %s  Clients: %d\n",
                WiFi.softAPIP().toString().c_str(),
                WiFi.softAPStationNum());
        }
    }

    delay(10);
}
