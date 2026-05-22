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
#include <ArduinoJson.h>
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

// ── Command parser ────────────────────────────────────────────────────────────
static void parse_command(const uint8_t* data, size_t len) {
    StaticJsonDocument<256> doc;
    DeserializationError err = deserializeJson(doc, data, len);
    if (err) {
        Serial.printf("[BLE] JSON parse error: %s\n", err.c_str());
        return;
    }

    const char* cmd = doc["cmd"];
    if (!cmd) return;

    Serial.printf("[BLE] Command received: %s\n", cmd);

    if (strcmp(cmd, "SET_VIBE") == 0) {
        int pattern = doc["pattern"] | 0;
        if (pattern >= 1 && pattern <= 6) {
            pendingPattern = pattern;
            pendingCmd = true;
        } else {
            Serial.printf("[BLE] Invalid pattern: %d\n", pattern);
        }

    } else if (strcmp(cmd, "NAV") == 0) {
        // Map NAV commands to vibration patterns
        const char* type = doc["type"];
        const char* dir  = doc["dir"];

        if (!type) return;

        if (strcmp(type, "TURN") == 0) {
            if (dir && strcmp(dir, "LEFT") == 0) {
                pendingPattern = PATTERN_TURN_LEFT;
            } else {
                pendingPattern = PATTERN_TURN_RIGHT;
            }
            pendingCmd = true;

        } else if (strcmp(type, "STAIRS") == 0) {
            pendingPattern = PATTERN_STAIRS_AHEAD;
            pendingCmd = true;

        } else if (strcmp(type, "ARRIVED") == 0) {
            pendingPattern = PATTERN_ARRIVED;
            pendingCmd = true;

        } else if (strcmp(type, "STOP") == 0) {
            pendingPattern = PATTERN_STOP_OBSTACLE;
            pendingCmd = true;
        }

    } else {
        Serial.printf("[BLE] Unhandled cmd: %s\n", cmd);
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
        std::string val = c->getValue();
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

    StaticJsonDocument<128> doc;
    doc["cmd"]        = "STATUS";
    doc["battery_v"]  = battV;
    doc["ultra_cm"]   = ultraCm;

    char buf[128];
    size_t len = serializeJson(doc, buf, sizeof(buf));

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
