/**
 * ultrasonic.h — HC-SR04 driver for ESP32
 *
 * Uses pulseIn() with a 30ms timeout (max useful range ~500cm).
 * Returns 0 if no echo received (out of range or sensor error).
 * Speed of sound = 343 m/s → 0.0343 cm/µs → divide by 2 for one-way.
 */
#pragma once
#include <Arduino.h>
#include "config.h"

void ultrasonic_init();
int  ultrasonic_read_cm();

void ultrasonic_init() {
    pinMode(PIN_TRIG, OUTPUT);
    pinMode(PIN_ECHO, INPUT);
    digitalWrite(PIN_TRIG, LOW);
    Serial.println(F("[US] Ultrasonic sensor initialized"));
}

/**
 * Returns distance in centimeters.
 * Returns 999 if no echo (open space / out of range).
 * Returns -1 if measurement error.
 */
int ultrasonic_read_cm() {
    // Ensure clean LOW before trigger
    digitalWrite(PIN_TRIG, LOW);
    delayMicroseconds(2);

    // Send 10µs HIGH pulse
    digitalWrite(PIN_TRIG, HIGH);
    delayMicroseconds(10);
    digitalWrite(PIN_TRIG, LOW);

    // Wait for echo pulse (timeout = 30000µs → ~5m max range)
    long duration = pulseIn(PIN_ECHO, HIGH, 30000);

    if (duration == 0) {
        return 999;  // No echo — open space or sensor error
    }

    int distanceCm = (int)(duration * 0.0343f / 2.0f);

    // Sanity bounds: HC-SR04 spec is 2cm–400cm
    if (distanceCm < 2 || distanceCm > 400) {
        return 999;
    }

    return distanceCm;
}
