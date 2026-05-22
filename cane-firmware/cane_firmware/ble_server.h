/**
 * ble_server.h — BLE GATT Server for CrowdPath Smart Cane
 *
 * Service UUID   : 0000ffe0-0000-1000-8000-00805f9b34fb
 * Command Char   : 0000ffe1-0000-1000-8000-00805f9b34fb  (WRITE — phone → cane)
 * Status Char    : 0000ffe2-0000-1000-8000-00805f9b34fb  (NOTIFY — cane → phone)
 *
 * Command JSON examples (received from Android CaneClient.kt):
 *   {"cmd":"SET_VIBE","pattern":2}
 *   {"cmd":"NAV","type":"TURN","dir":"LEFT","distance_m":5.0}
 *
 * Status JSON sent every STATUS_INTERVAL_MS:
 *   {"cmd":"STATUS","battery_v":3.85,"ultra_cm":45}
 */
#pragma once
#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
// No ArduinoJson needed — JSON built/parsed with plain C string ops
#include "config.h"
#include "vibration.h"

// ── Globals ───────────────────────────────────────────────────────────────────
static BLEServer*             pServer         = nullptr;
static BLECharacteristic*     pStatusChar     = nullptr;
static bool                   deviceConnected = false;
static bool                   wasConnected    = false;

// Pending command from BLE callback (processed in main loop to avoid blocking)
static volatile bool          pendingCmd      = false;
static volatile int           pendingPattern  = 0;

// ── Battery reading ───────────────────────────────────────────────────────────
static float read_battery_v() {
    // If no voltage divider is wired, return a fixed 3.85V for testing
    int raw = analogRead(PIN_BATTERY_ADC);
    float v = ((float)raw / ADC_MAX_COUNTS) * ADC_REF_V * VDIVIDER_RATIO;
    // Clamp to realistic Li-Po range
    if (v < 2.5f || v > 4.5f) return 3.85f;  // fallback for unwired pin
    return v;
}

// ── Command parser (no ArduinoJson — plain string search) ────────────────────
static void parse_command(const uint8_t* data, size_t len) {
    // Copy into a null-terminated buffer for safe string operations
    char buf[256];
    size_t cpLen = (len < sizeof(buf) - 1) ? len : sizeof(buf) - 1;
    memcpy(buf, data, cpLen);
    buf[cpLen] = '\0';

    Serial.printf("[BLE] Raw command: %s\n", buf);

    // Helper: extract a string value for a given key, e.g. "cmd":"SET_VIBE"
    // Returns pointer to value start (within buf) or nullptr if not found.
    auto strVal = [&](const char* key) -> const char* {
        char search[64];
        snprintf(search, sizeof(search), "\"%s\":\"", key);
        const char* p = strstr(buf, search);
        if (!p) return nullptr;
        return p + strlen(search);  // points to first char of value
    };

    // Extract "cmd" value
    const char* cmdStart = strVal("cmd");
    if (!cmdStart) { Serial.println(F("[BLE] No 'cmd' key")); return; }

    if (strncmp(cmdStart, "SET_VIBE", 8) == 0) {
        // Extract "pattern":<number>
        const char* pp = strstr(buf, "\"pattern\":");
        if (pp) {
            int pattern = 0;
            sscanf(pp + 10, "%d", &pattern);   // skip past "pattern":
            if (pattern >= 1 && pattern <= 6) {
                pendingPattern = pattern;
                pendingCmd = true;
                Serial.printf("[BLE] SET_VIBE pattern=%d\n", pattern);
            } else {
                Serial.printf("[BLE] Invalid pattern: %d\n", pattern);
            }
        }

    } else if (strncmp(cmdStart, "NAV", 3) == 0) {
        const char* typeStart = strVal("type");
        const char* dirStart  = strVal("dir");

        if (!typeStart) return;

        if (strncmp(typeStart, "TURN", 4) == 0) {
            // dir present and starts with LEFT?
            pendingPattern = (dirStart && strncmp(dirStart, "LEFT", 4) == 0)
                             ? PATTERN_TURN_LEFT
                             : PATTERN_TURN_RIGHT;
            pendingCmd = true;

        } else if (strncmp(typeStart, "STAIRS", 6) == 0) {
            pendingPattern = PATTERN_STAIRS_AHEAD;
            pendingCmd = true;

        } else if (strncmp(typeStart, "ARRIVED", 7) == 0) {
            pendingPattern = PATTERN_ARRIVED;
            pendingCmd = true;

        } else if (strncmp(typeStart, "STOP", 4) == 0) {
            pendingPattern = PATTERN_STOP_OBSTACLE;
            pendingCmd = true;
        }

    } else {
        Serial.printf("[BLE] Unhandled cmd: %.16s\n", cmdStart);
    }
}

// ── GATT Callbacks ────────────────────────────────────────────────────────────
class CaneServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer* s) override {
        deviceConnected = true;
        Serial.println(F("[BLE] Phone connected"));
    }
    void onDisconnect(BLEServer* s) override {
        deviceConnected = false;
        Serial.println(F("[BLE] Phone disconnected — restarting advertising"));
    }
};

class CommandCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* c) override {
        String val = c->getValue();
        if (val.length() > 0) {
            parse_command((const uint8_t*)val.c_str(), val.length());
        }
    }
};

// ── Public API ────────────────────────────────────────────────────────────────
void ble_init() {
    BLEDevice::init(DEVICE_NAME);
    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new CaneServerCallbacks());

    BLEService* pService = pServer->createService(SERVICE_UUID);

    // Command characteristic — phone writes here
    BLECharacteristic* pCommandChar = pService->createCharacteristic(
        COMMAND_CHAR_UUID,
        BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
    );
    pCommandChar->setCallbacks(new CommandCallbacks());

    // Status characteristic — cane notifies here
    pStatusChar = pService->createCharacteristic(
        STATUS_CHAR_UUID,
        BLECharacteristic::PROPERTY_NOTIFY
    );
    pStatusChar->addDescriptor(new BLE2902());

    pService->start();

    BLEAdvertising* pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    pAdvertising->setMinPreferred(0x06);
    BLEDevice::startAdvertising();

    Serial.printf("[BLE] Advertising as: %s\n", DEVICE_NAME);
}

/**
 * Call from main loop — sends status notification if connected.
 */
void ble_send_status(int ultraCm) {
    if (!deviceConnected || pStatusChar == nullptr) return;

    float battV = read_battery_v();

    // Build JSON with sprintf — no library needed
    char buf[128];
    int len = snprintf(buf, sizeof(buf),
        "{\"cmd\":\"STATUS\",\"battery_v\":%.2f,\"ultra_cm\":%d}",
        battV, ultraCm);

    pStatusChar->setValue((uint8_t*)buf, len);
    pStatusChar->notify();

    Serial.printf("[BLE] Status → battery_v=%.2f ultra_cm=%d\n", battV, ultraCm);
}

/**
 * Call from main loop — executes any command queued by BLE callback.
 * Callbacks must not block, so we defer motor actuation here.
 */
void ble_process_pending() {
    if (!pendingCmd) return;
    pendingCmd = false;
    int p = pendingPattern;
    pendingPattern = 0;
    vibration_fire(p);

    // Restart advertising if phone disconnected after command
    if (!deviceConnected && wasConnected) {
        wasConnected = false;
        BLEDevice::startAdvertising();
    }
    if (deviceConnected) wasConnected = true;
}
