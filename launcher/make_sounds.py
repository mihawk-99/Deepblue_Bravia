#!/usr/bin/env python3
"""Synthesise the shell's UI sounds into res/raw/*.wav.

Original synthesis (sines + filtered noise); pitch/timing targets were measured from the reference
video's UI sounds, nothing is sampled from it. Used by the browser prototype and the APK (SoundPool).
Run: python3 make_sounds.py
"""
import math
import os
import random
import struct
import wave

SR = 44100
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "res", "raw")
TAU = 2 * math.pi


def ahr(t, attack, hold, release):
    """Attack / hold / exponential release envelope."""
    if t < attack:
        return t / attack
    if t < attack + hold:
        return 1.0
    return math.exp(-(t - attack - hold) / release)


def tone(f, t, bright=0.0):
    """Sine with an optional 3rd harmonic for a synthy edge."""
    return math.sin(TAU * f * t) + bright * math.sin(3 * TAU * f * t)


class Bandpass:
    """RBJ biquad band-pass; centre can move every sample."""
    def __init__(self, q):
        self.q, self.x1 = q, 0.0
        self.x2 = self.y1 = self.y2 = 0.0

    def __call__(self, x, f):
        w = TAU * f / SR
        a = math.sin(w) / (2 * self.q)
        b0, a0, a1, a2 = a, 1 + a, -2 * math.cos(w), 1 - a
        y = (b0 * x - b0 * self.x2 - a1 * self.y1 - a2 * self.y2) / a0
        self.x2, self.x1, self.y2, self.y1 = self.x1, x, self.y1, y
        return y


def render(seconds, fn):
    return [fn(i / SR) for i in range(int(SR * seconds))]


def write(name, samples, gain):
    peak = max(abs(s) for s in samples) or 1.0
    os.makedirs(OUT, exist_ok=True)
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", int(s / peak * gain * 32767)) for s in samples))


def move():
    """Soft two-blip tick in the same family as the page sparkle; no sustained highs (they hurt on repeat)."""
    blips = ((0.000, 3500, 1.0), (0.018, 5200, 0.6))  # start, freq, amp: tiny rising step

    def s(t):
        v = sum(a * math.sin(TAU * f * (t - st)) * ahr(t - st, 0.002, 0.004, 0.012) for st, f, a in blips if t >= st)
        return v + 0.5 * math.sin(TAU * 1750 * t) * ahr(t, 0.003, 0.0, 0.025)  # warm body an octave below
    return render(0.12, s)


def confirm():
    """Descending octave zip (7.2k -> 3.6k -> 1.8k), step up to 2.2k with 5.1k shimmer, low thump."""
    steps = ((0.000, 0.015, 7260, 0.7), (0.015, 0.080, 3640, 0.9), (0.080, 0.230, 1830, 1.0), (0.230, 0.400, 2200, 0.7))

    def s(t):
        v = 0.0
        for a, b, f, amp in steps:
            if a - 0.004 <= t <= b + 0.03:  # 4 ms fade in, 30 ms tail so steps blend
                fade = min(1.0, (t - a + 0.004) / 0.004) * (1.0 if t <= b else math.exp(-(t - b) / 0.012))
                v += amp * tone(f, t, 0.25) * fade
        v *= ahr(t, 0.001, 0.25, 0.06)
        v += 0.35 * (math.sin(TAU * 1378 * t) + 0.8 * math.sin(TAU * 1227 * t)) * ahr(t, 0.07, 0.1, 0.05)  # body
        v += 0.35 * math.sin(TAU * 5103 * t) * ahr(max(0.0, t - 0.22), 0.01, 0.05, 0.08) * (t > 0.22)      # shimmer
        v += 0.9 * math.sin(TAU * 366 * t) * ahr(t, 0.001, 0.02, 0.05)                                        # thump
        v += random.uniform(-1, 1) * ahr(t, 0.0003, 0.0, 0.002) * 0.7                                          # click
        return v
    return render(0.45, s)


def sweep(seconds, f_from, f_to, blips, pad_freq):
    """Rising sparkle glissando of short blips over a held tone and a rising noise whoosh."""
    bp = Bandpass(q=2.5)
    rise = seconds * 0.6  # the sparkle climbs over the first 60%
    starts = [rise * k / blips for k in range(blips)]

    def s(t):
        v = 0.0
        for k, st in enumerate(starts):
            if st <= t < st + 0.03:
                f = f_from * (f_to / f_from) ** (k / (blips - 1))
                v += 0.55 * math.sin(TAU * f * (t - st)) * ahr(t - st, 0.001, 0.004, 0.008)
        v += 0.45 * math.sin(TAU * pad_freq * t) * ahr(t, 0.03, seconds * 0.5, seconds * 0.15)
        centre = 1500 * (6 ** min(1.0, t / rise))
        v += 1.6 * bp(random.uniform(-1, 1), centre) * ahr(t, rise * 0.4, rise * 0.3, seconds * 0.15)
        return v
    return render(seconds, s)


if __name__ == "__main__":
    random.seed(7)  # deterministic output
    sounds = (("move", move(), 0.35), ("confirm", confirm(), 0.8),
              ("splash", sweep(0.62, 2000, 10500, 16, 1660), 0.7),
              ("page", sweep(0.3, 3000, 9000, 7, 2200), 0.55))
    for name, samples, gain in sounds:
        write(name, samples, gain)
        print("wrote", os.path.join(OUT, name + ".wav"))
