/**
 * wifi_server.h — WiFi AP + UDP server for CrowdPath Smart Cane (ESP8266)
 *
 * Replaces ble_server.h for ESP8266 which has no Bluetooth.
 *
 * Protocol (identical interface to BLE version):
 *   Phone → Cane  : UDP packet to 192.168.4.1:4210
 *                   Payload: JSON string e.g. {"cmd":"SET_VIBE","pattern":2}
 *                             or {"cmd":"NAV","type":"TURN","dir":"LEFT"}
 *   Cane → Phone  : UDP packet back to sender port 4211 every STATUS_INTERVAL_MS
 *                   Payload: {"cmd":"STATUS","battery_v":3.85,"ultra_cm":45}
 *
 * Setup on phone:
 *   1. Go to Settings → WiFi → connect to "SmartCane" (password: crowdpath)
 *   2. Open app → cane connects automatically
 */
#pragma once
#include <ESP8266WiFi.h>
#include <WiFiUdp.h>
#include "config.h"
#include "vibration.h"

// ── Globals ───────────────────────────────────────────────────────────────────
static WiFiUDP    udp;
static IPAddress  phoneIp;           // remembers the phone's IP once it sends a packet
static bool       phoneKnown = false;

// Pending command from UDP (processed in main loop — same pattern as BLE version)
static volatile bool pendingCmd     = false;
static volatile int  pendingPattern = 0;

// ── Battery reading (same as ble_server.h) ────────────────────────────────────
static float read_battery_v() {
    // ESP8266 ADC is 0–1V (with 3.3V divider on A0). Adjust VDIVIDER_RATIO in config.h.
    int raw = analogRead(A0);   // ESP8266 only has A0 (10-bit, 0–1023)
    float v = ((float)raw / 1023.0f) * 1.0f * VDIVIDER_RATIO;
    if (v < 2.5f || v > 4.5f) return 3.85f;  // fallback for unwired pin
    return v;
}

// ── Command parser (identical logic to ble_server.h) ─────────────────────────
static void parse_command(const char* buf, size_t len) {
    // Null-terminate
    char cmd_buf[256];
    size_t cpLen = (len < sizeof(cmd_buf) - 1) ? len : sizeof(cmd_buf) - 1;
    memcpy(cmd_buf, buf, cpLen);
    cmd_buf[cpLen] = '\0';

    Serial.printf("[UDP] Command: %s\n", cmd_buf);

    auto strVal = [&](const char* key) -> const char* {
        char search[64];
        snprintf(search, sizeof(search), "\"%s\":\"", key);
        const char* p = strstr(cmd_buf, search);
        if (!p) return nullptr;
        return p + strlen(search);
    };

    const char* cmdStart = strVal("cmd");
    if (!cmdStart) return;

    if (strncmp(cmdStart, "SET_VIBE", 8) == 0) {
        const char* pp = strstr(cmd_buf, "\"pattern\":");
        if (pp) {
            int pattern = 0;
            sscanf(pp + 10, "%d", &pattern);
            if (pattern >= 1 && pattern <= 6) {
                pendingPattern = pattern;
                pendingCmd     = true;
            }
        }

    } else if (strncmp(cmdStart, "NAV", 3) == 0) {
        const char* typeStart = strVal("type");
        const char* dirStart  = strVal("dir");
        if (!typeStart) return;

        if      (strncmp(typeStart, "TURN",    4) == 0)
            pendingPattern = (dirStart && strncmp(dirStart, "LEFT", 4) == 0)
                             ? PATTERN_TURN_LEFT : PATTERN_TURN_RIGHT;
        else if (strncmp(typeStart, "STAIRS",  6) == 0) pendingPattern = PATTERN_STAIRS_AHEAD;
        else if (strncmp(typeStart, "ARRIVED", 7) == 0) pendingPattern = PATTERN_ARRIVED;
        else if (strncmp(typeStart, "STOP",    4) == 0) pendingPattern = PATTERN_STOP_OBSTACLE;
        else return;

        pendingCmd = true;
    }
}

// ── Public API ────────────────────────────────────────────────────────────────
void wifi_init() {
    // Create Access Point
    WiFi.mode(WIFI_AP);
    WiFi.softAP(WIFI_SSID, WIFI_PASS);
    Serial.printf("[WiFi] AP started — SSID: %s  IP: %s\n",
                  WIFI_SSID, WiFi.softAPIP().toString().c_str());

    // Start UDP listener on CANE_UDP_PORT
    udp.begin(CANE_UDP_PORT);
    Serial.printf("[UDP] Listening on port %d\n", CANE_UDP_PORT);
}

void wifi_process_incoming() {
    int packetSize = udp.parsePacket();
    if (packetSize <= 0) return;

    // Remember phone IP/port for status replies
    phoneIp    = udp.remoteIP();
    phoneKnown = true;

    char buf[256];
    int len = udp.read(buf, sizeof(buf) - 1);
    if (len > 0) {
        buf[len] = '\0';
        parse_command(buf, len);
    }
}

void wifi_send_status(int ultraCm) {
    if (!phoneKnown) return;

    float battV = read_battery_v();
    char buf[128];
    int len = snprintf(buf, sizeof(buf),
        "{\"cmd\":\"STATUS\",\"battery_v\":%.2f,\"ultra_cm\":%d}",
        battV, ultraCm);

    udp.beginPacket(phoneIp, PHONE_UDP_PORT);
    udp.write((uint8_t*)buf, len);
    udp.endPacket();

    Serial.printf("[UDP] Status → battery_v=%.2f ultra_cm=%d\n", battV, ultraCm);
}

void wifi_process_pending() {
    if (!pendingCmd) return;
    pendingCmd = false;
    vibration_fire(pendingPattern);
    pendingPattern = 0;
}
