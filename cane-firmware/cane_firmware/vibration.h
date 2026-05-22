/**
 * vibration.h / vibration.cpp — Motor pattern engine
 *
 * All 6 patterns defined to match VibrationPatterns.kt:
 *   1 = TURN_LEFT    → 2 long pulses
 *   2 = TURN_RIGHT   → 3 short pulses
 *   3 = NEAR_TURN    → 1 medium pulse
 *   4 = STAIRS_AHEAD → 4 slow pulses
 *   5 = ARRIVED      → 1 long continuous buzz
 *   6 = STOP/OBSTACLE→ rapid SOS-style buzz
 */
#pragma once
#include <Arduino.h>

void vibration_init();
void vibration_fire(int pattern);

// ── Implementation ────────────────────────────────────────────────────────────

inline void motor_on()  { digitalWrite(PIN_MOTOR, HIGH); }
inline void motor_off() { digitalWrite(PIN_MOTOR, LOW);  }

inline void pulse(int on_ms, int off_ms, int count) {
    for (int i = 0; i < count; i++) {
        motor_on();
        delay(on_ms);
        motor_off();
        if (i < count - 1) delay(off_ms);
    }
}

void vibration_init() {
    pinMode(PIN_MOTOR, OUTPUT);
    motor_off();
    Serial.println(F("[VIB] Motor initialized"));
}

void vibration_fire(int pattern) {
    Serial.printf("[VIB] Pattern %d\n", pattern);
    switch (pattern) {
        case 1:  // Turn Left — 2 long pulses (distinct feel: slow & deliberate)
            pulse(500, 200, 2);
            break;

        case 2:  // Turn Right — 3 short pulses (distinct from left: fast triple)
            pulse(150, 100, 3);
            break;

        case 3:  // Near Turn — 1 medium pulse (heads-up warning)
            pulse(300, 0, 1);
            break;

        case 4:  // Stairs Ahead — 4 rhythmic pulses
            // Was 150+350=500ms×4=2000ms (too long, blocked ultrasonic reads)
            // Now 150+50=200ms×4=800ms — still feels distinct, doesn't freeze loop
            pulse(150, 50, 4);
            break;

        case 5:  // Arrived — 1 long celebratory buzz
            motor_on();
            delay(1000);
            motor_off();
            break;

        case 6:  // STOP / Obstacle — rapid urgent bursts
            for (int i = 0; i < 8; i++) {
                motor_on();
                delay(60);
                motor_off();
                delay(60);
            }
            break;

        default:
            Serial.printf("[VIB] Unknown pattern: %d\n", pattern);
            break;
    }
}
