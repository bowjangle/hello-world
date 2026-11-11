#!/usr/bin/env python3
"""Generates the app's built-in notification sounds into app/src/main/res/raw/.
Pure synthesis (no samples), so there are no licensing questions.
Levels are deliberately modest: the phone's notification volume does the rest."""
import math, struct, wave, os

SR = 22050
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")

def write(name, samples, peak):
    fade = int(SR * 0.04)
    for i in range(min(fade, len(samples))):
        samples[-1 - i] *= i / fade
    m = max(abs(x) for x in samples) or 1
    g = peak / m
    with wave.open(os.path.join(OUT, name + ".wav"), "w") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", int(x * g * 32767)) for x in samples))

def tone(dur, notes, decay, partials=((1, 1.0),), attack=0.012):
    """notes: [(freq, start_seconds)], partials: [(multiple, amplitude)]"""
    out = []
    for i in range(int(SR * dur)):
        t = i / SR; s = 0.0
        for f, st in notes:
            tt = t - st
            if tt < 0: continue
            env = min(1.0, tt / attack) * math.exp(-tt * decay)
            s += env * sum(a * math.sin(2 * math.pi * f * k * tt) for k, a in partials)
        out.append(s)
    return out

# Very quiet single soft pluck.
write("snd_whisper", tone(0.9, [(1046.5, 0)], 6.0, ((1, 1), (2, 0.1))), 0.18)
# Single gentle bell (default).
write("snd_bell", tone(1.6, [(784.0, 0)], 3.0, ((1, 1), (2.0, 0.25), (3.0, 0.08))), 0.32)
# Short rising water drop.
drop = []
for i in range(int(SR * 0.45)):
    t = i / SR
    f = 600 + 900 * (1 - math.exp(-t / 0.03))
    phase = 2 * math.pi * (600 * t + 900 * (t + 0.03 * (math.exp(-t / 0.03) - 1)))
    drop.append(min(1, t / 0.004) * math.exp(-t * 9) * math.sin(phase))
write("snd_drop", drop, 0.38)
# Three soft ascending marimba notes.
write("snd_marimba", tone(1.3, [(523.25, 0), (659.25, 0.13), (783.99, 0.26)], 5.0, ((1, 1), (4.0, 0.12))), 0.38)
# Two-note chime (original v1.0 sound).
write("snd_chime", tone(1.6, [(659.25, 0), (987.77, 0.28)], 3.2, ((1, 1), (2, 0.25))), 0.47)
# Clear two-note chime (v1.1, loudest).
write("snd_clear", tone(2.2, [(880.0, 0), (1318.5, 0.30)], 2.2, ((1, 1), (2, 0.2))), 0.93)
