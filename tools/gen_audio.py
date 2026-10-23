#!/usr/bin/env python3
"""
Genera el audio de Faro Hundido (efectos y música por bioma) con síntesis simple en numpy.

    python3 tools/gen_audio.py      # escribe assets/sfx/*.ogg y assets/music/*.ogg

Todo es determinista (semilla fija). Requiere numpy, scipy y ffmpeg con libvorbis.
Las pistas de música son bucles exactos: su duración es un número entero de compases.
"""
import os
import subprocess
import tempfile
import wave

import numpy as np
from scipy.signal import lfilter

SR = 32000
ROOT = os.path.join(os.path.dirname(__file__), "..", "assets")
rng = np.random.default_rng(1977)


# ------------------------------------------------------------------ utilidades

def t_of(dur):
    return np.arange(int(SR * dur)) / SR


def env(n, a=0.005, d=0.1, s=0.0, r=0.05, sus=None):
    """ADSR en muestras: ataque, caída hasta s, sostenido y salida."""
    a_n, d_n, r_n = int(a * SR), int(d * SR), int(r * SR)
    sus_n = max(0, n - a_n - d_n - r_n) if sus is None else int(sus * SR)
    e = np.concatenate([
        np.linspace(0, 1, max(1, a_n), endpoint=False),
        np.linspace(1, s, max(1, d_n), endpoint=False),
        np.full(sus_n, s),
        np.linspace(s, 0, max(1, r_n)),
    ])
    if len(e) < n:
        e = np.concatenate([e, np.zeros(n - len(e))])
    return e[:n]


def lowpass(x, cutoff):
    a = np.exp(-2 * np.pi * cutoff / SR)
    return lfilter([1 - a], [1, -a], x)


def highpass(x, cutoff):
    return x - lowpass(x, cutoff)


def noise(n):
    return rng.uniform(-1, 1, n)


def sine(f, dur, phase=0.0):
    t = t_of(dur)
    if callable(f):
        ph = 2 * np.pi * np.cumsum(f(t)) / SR
    else:
        ph = 2 * np.pi * f * t
    return np.sin(ph + phase)


def square(f, dur, duty=0.5):
    t = t_of(dur)
    if callable(f):
        ph = np.cumsum(f(t)) / SR
    else:
        ph = f * t
    return np.where((ph % 1.0) < duty, 1.0, -1.0)


def tri(f, dur):
    t = t_of(dur)
    ph = (f * t) % 1.0
    return 4 * np.abs(ph - 0.5) - 1


def pluck(f, dur, damp=0.996, bright=0.5):
    """Karplus-Strong: cuerda pulsada."""
    n = int(SR * dur)
    p = max(2, int(SR / f))
    buf = rng.uniform(-1, 1, p) * bright + rng.uniform(-1, 1, p) * (1 - bright) * 0.2
    out = np.zeros(n)
    for i in range(n):
        v = buf[i % p]
        out[i] = v
        buf[i % p] = damp * 0.5 * (v + buf[(i + 1) % p])
    return out


def bell(f, dur, decay=2.0):
    t = t_of(dur)
    parts = [(1.0, 1.0), (2.76, 0.5), (5.4, 0.25), (8.9, 0.12), (0.5, 0.3)]
    out = np.zeros_like(t)
    for m, a in parts:
        out += a * np.sin(2 * np.pi * f * m * t) * np.exp(-t * decay * (1 + m * 0.3))
    return out / 2.2


def organ(f, dur):
    t = t_of(dur)
    out = np.zeros_like(t)
    for m, a in [(1, 1.0), (2, 0.5), (3, 0.3), (4, 0.2), (0.5, 0.4)]:
        out += a * np.sin(2 * np.pi * f * m * t + m)
    return out / 2.4


def midi(n):
    return 440.0 * 2 ** ((n - 69) / 12)


def norm(x, peak=0.9):
    m = np.max(np.abs(x))
    return x if m == 0 else x * (peak / m)


def place(track, sig, start_s, gain=1.0):
    """Suma sig en track a partir de start_s; lo que sobra da la vuelta (bucles exactos)."""
    i = int(start_s * SR) % len(track)
    n = len(sig)
    end = i + n
    if end <= len(track):
        track[i:end] += sig * gain
    else:
        k = len(track) - i
        track[i:] += sig[:k] * gain
        rest = sig[k:]
        while len(rest) > 0:
            m = min(len(rest), len(track))
            track[:m] += rest[:m] * gain
            rest = rest[m:]


def write_ogg(path, x, quality=3):
    x = np.clip(x, -1, 1)
    pcm = (x * 32767).astype(np.int16)
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        name = tmp.name
    with wave.open(name, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    os.makedirs(os.path.dirname(path), exist_ok=True)
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", name, "-c:a", "libvorbis", "-q:a", str(quality), path], check=True)
    os.unlink(name)


# ------------------------------------------------------------------ efectos

def sfx():
    s = {}
    # Golpe del arma: soplido de ruido que baja.
    n = int(0.12 * SR)
    s["swing"] = highpass(lowpass(noise(n), 3000), 400) * env(n, 0.005, 0.11)
    # Impacto en enemigo.
    n = int(0.10 * SR)
    s["hit"] = (lowpass(noise(n), 2500) * 0.7 + square(lambda t: 180 - 900 * t, 0.10) * 0.4) * env(n, 0.001, 0.09)
    n = int(0.22 * SR)
    s["hit_heavy"] = (lowpass(noise(n), 1500) * 0.6 + sine(lambda t: 120 - 300 * t, 0.22)) * env(n, 0.001, 0.2)
    # Daño a Isla: chispa descendente y vidrio.
    n = int(0.3 * SR)
    s["hurt"] = (square(lambda t: 520 - 1200 * t, 0.3, 0.3) * 0.5 + highpass(noise(n), 3000) * 0.3) * env(n, 0.001, 0.28)
    # Enemigo vencido: pop y chispas.
    n = int(0.28 * SR)
    s["kill"] = (sine(lambda t: 300 + 900 * t, 0.28) * 0.5 + highpass(noise(n), 5000) * 0.25) * env(n, 0.001, 0.26)
    # Jefe vencido: campana grave + rumor.
    n = int(2.2 * SR)
    s["boss_kill"] = bell(midi(45), 2.2, 1.2) + lowpass(noise(n), 200) * env(n, 0.01, 1.5) * 0.8
    # Dash: chapoteo rápido.
    n = int(0.16 * SR)
    s["dash"] = lowpass(noise(n), 2200) * env(n, 0.002, 0.15) * 0.9
    # Velar: el farol se cierra (barrido grave).
    n = int(0.35 * SR)
    s["veil"] = sine(lambda t: 420 - 900 * t, 0.35) * env(n, 0.01, 0.33) * 0.7
    # Fogonazo: estallido luminoso.
    n = int(0.5 * SR)
    s["flash"] = (highpass(noise(n), 1500) * 0.6 + sine(lambda t: 200 + 1600 * t, 0.5) * 0.4) * env(n, 0.002, 0.48)
    # Orbe de Luz recogido.
    n = int(0.12 * SR)
    s["orb"] = (sine(midi(88), 0.12) * 0.6 + sine(midi(95), 0.12) * 0.3) * env(n, 0.002, 0.11)
    # Recoger exvoto / objeto: arpegio.
    out = np.zeros(int(0.5 * SR))
    for i, m in enumerate([76, 79, 83, 88]):
        place(out, bell(midi(m), 0.4, 6) * 0.7, i * 0.07)
    s["pickup"] = out
    # Perlas.
    out = np.zeros(int(0.3 * SR))
    for i, m in enumerate([91, 96]):
        place(out, sine(midi(m), 0.2) * env(int(0.2 * SR), 0.002, 0.19) * 0.6, i * 0.06)
    s["perla"] = out
    # Puertas abiertas: piedra que se arrastra.
    n = int(0.6 * SR)
    s["door"] = lowpass(noise(n), 300) * env(n, 0.05, 0.5) + sine(55, 0.6) * env(n, 0.05, 0.5) * 0.4
    # Arpón: silbido y golpe.
    n = int(0.25 * SR)
    s["harpoon"] = highpass(noise(n), 2000) * env(n, 0.001, 0.24) * 0.6 + sine(lambda t: 900 - 2400 * t, 0.25) * env(n, 0.001, 0.2) * 0.3
    # Campana del Campanero/Obispo.
    s["bell"] = bell(midi(64), 1.6, 1.6)
    # Interfaz.
    n = int(0.05 * SR)
    s["ui_move"] = square(midi(84), 0.05, 0.25) * env(n, 0.001, 0.045) * 0.4
    n = int(0.12 * SR)
    s["ui_ok"] = (square(midi(84), 0.12, 0.25) * 0.3 + square(midi(91), 0.12, 0.25) * 0.2) * env(n, 0.001, 0.11)
    # Rugido / cambio de fase de jefe.
    n = int(1.0 * SR)
    s["roar"] = lowpass(noise(n) * (1 + 0.6 * sine(23, 1.0)), 600) * env(n, 0.05, 0.9) + square(lambda t: 70 - 20 * t, 1.0) * env(n, 0.05, 0.9) * 0.2
    # Te apagas.
    n = int(1.6 * SR)
    s["death"] = sine(lambda t: 330 * np.exp(-t * 1.2), 1.6) * env(n, 0.01, 1.55) * 0.8 + lowpass(noise(n), 400) * env(n, 0.01, 1.2) * 0.3
    # Victoria: acorde que se abre.
    out = np.zeros(int(3.0 * SR))
    for i, m in enumerate([62, 66, 69, 74, 78]):
        place(out, organ(midi(m), 2.6) * env(int(2.6 * SR), 0.3, 0.5, 0.6, 1.2) * 0.5, i * 0.12)
    s["victory"] = out
    # Vidrio que se rompe.
    n = int(0.4 * SR)
    g = np.zeros(n)
    for k in range(6):
        place(g, bell(rng.uniform(2500, 5000), 0.3, 18) * 0.5, rng.uniform(0, 0.08))
    s["glass"] = g + highpass(noise(n), 4000) * env(n, 0.001, 0.2) * 0.4
    # La fosa respira (final C).
    n = int(1.4 * SR)
    s["breath"] = lowpass(noise(n), 350) * env(n, 0.6, 0.7) * 1.2
    for k, v in s.items():
        write_ogg(os.path.join(ROOT, "sfx", k + ".ogg"), norm(v, 0.85), 4)
    return list(s)


# ------------------------------------------------------------------ música

def drum_kick():
    n = int(0.3 * SR)
    return sine(lambda t: 110 * np.exp(-t * 18) + 40, 0.3) * env(n, 0.001, 0.28)


def drum_snare():
    n = int(0.18 * SR)
    return (highpass(noise(n), 1200) * 0.7 + tri(190, 0.18) * 0.3) * env(n, 0.001, 0.17)


def drum_hat():
    n = int(0.05 * SR)
    return highpass(noise(n), 6000) * env(n, 0.001, 0.045)


def song(bpm, bars, beats=4):
    beat = 60.0 / bpm
    dur = bars * beats * beat
    return np.zeros(int(round(dur * SR))), beat


def pad(track, notes, start, dur, gain, kind="organ", cut=1800):
    n = int(dur * SR)
    for m in notes:
        sig = organ(midi(m), dur) if kind == "organ" else (tri(midi(m), dur) * 0.6 + sine(midi(m) * 1.003, dur) * 0.4)
        sig = lowpass(sig, cut) * env(n, dur * 0.3, dur * 0.2, 0.8, dur * 0.4)
        place(track, sig, start, gain / len(notes))


def m_santuario():
    tr, b = song(70, 16)
    chords = [[50, 57, 62, 65], [46, 53, 58, 62], [48, 55, 60, 64], [45, 52, 57, 61]]
    for bar in range(16):
        ch = chords[(bar // 2) % 4]
        if bar % 2 == 0:
            pad(tr, ch, bar * 4 * b, 8 * b, 0.35, "tri", 1400)
        arp = [ch[1] + 12, ch[2] + 12, ch[3] + 12, ch[2] + 12]
        for i in range(4):
            place(tr, bell(midi(arp[i]), 1.2, 3.5) * 0.18, (bar * 4 + i) * b + b * 0.5)
    place(tr, lowpass(noise(len(tr)), 500) * 0.02, 0)
    return tr


def m_muelle():
    tr, b = song(80, 16)
    # Mar: ruido con oleaje lento.
    t = np.arange(len(tr)) / SR
    surf = lowpass(noise(len(tr)), 700) * (0.5 + 0.5 * np.sin(2 * np.pi * t / (8 * b))) * 0.12
    tr += surf
    drone = sine(midi(33), len(tr) / SR) * 0.18 + sine(midi(40), len(tr) / SR) * 0.08
    tr += drone[:len(tr)]
    mel = [69, None, 72, 74, None, 76, 74, None, 72, None, 69, 67, None, 69, None, None]
    for bar in range(16):
        for i, m in enumerate(mel[(bar % 4) * 4:(bar % 4) * 4 + 4]):
            if m is not None and (bar % 8) < 6:
                place(tr, pluck(midi(m - (12 if bar >= 8 else 0)), 1.0, 0.995) * 0.3, (bar * 4 + i) * b)
        if bar % 4 == 0:
            place(tr, bell(midi(57), 3.0, 0.8) * 0.12, bar * 4 * b + 2 * b)
    return tr


def m_catedral():
    tr, b = song(60, 12, 4)
    chords = [[45, 52, 57, 60], [43, 50, 55, 59], [41, 48, 53, 57], [40, 47, 52, 56]]
    for bar in range(12):
        ch = chords[bar % 4]
        pad(tr, ch, bar * 4 * b, 4 * b, 0.42, "organ", 1600)
        if bar % 2 == 0:
            place(tr, bell(midi(57), 4.0, 0.7) * 0.22, bar * 4 * b)
        place(tr, bell(midi(ch[3] + 12), 2.0, 1.8) * 0.08, bar * 4 * b + 2.5 * b)
    # Coro lejano: ruido filtrado en la nota.
    t = np.arange(len(tr)) / SR
    tr += lowpass(noise(len(tr)), 900) * 0.015 * (1 + np.sin(2 * np.pi * t / (4 * b)))
    return tr


def m_archivo():
    tr, b = song(92, 16)
    for i in range(16 * 4):
        # Reloj: tic-tac.
        n = int(0.03 * SR)
        place(tr, highpass(noise(n), 3500) * env(n, 0.001, 0.028) * (0.12 if i % 2 == 0 else 0.07), i * b)
    bass = [45, 45, 48, 43, 41, 41, 40, 40]
    mel = [72, 71, 69, 71, 72, 76, 74, 72, 71, 69, 68, 69, 71, 72, 71, 69]
    for bar in range(16):
        place(tr, pluck(midi(bass[bar % 8] - 12), 2.4, 0.997, 0.8) * 0.35, bar * 4 * b)
        if bar >= 4:
            for k in range(4):
                m = mel[(bar * 4 + k) % 16]
                if (bar * 4 + k) % 3 != 2:
                    place(tr, pluck(midi(m), 0.6, 0.99, 0.9) * 0.18, (bar * 4 + k) * b + (b * 0.5 if k % 2 else 0))
    tr += lowpass(noise(len(tr)), 300) * 0.02
    return tr


def m_raiz():
    tr, b = song(66, 12)
    L = len(tr) / SR
    t = np.arange(len(tr)) / SR
    tr += sine(midi(28), L)[:len(tr)] * 0.2 * (0.7 + 0.3 * np.sin(2 * np.pi * t / (4 * b)))
    tr += sine(midi(35) * 1.01, L)[:len(tr)] * 0.08
    for i in range(12 * 4):
        if i % 4 in (0, 1):
            k = drum_kick() * (0.55 if i % 4 == 0 else 0.35)
            place(tr, lowpass(k, 400), i * b + (0.18 * b if i % 4 == 1 else 0))
    for bar in range(0, 12, 3):
        place(tr, bell(midi(64) * 1.03, 5.0, 0.5) * 0.08, bar * 4 * b + b)
        place(tr, bell(midi(70), 5.0, 0.5) * 0.06, bar * 4 * b + 3 * b)
    tr += lowpass(noise(len(tr)), 250) * 0.03
    return tr


def m_jefe():
    tr, b = song(132, 16)
    riff = [40, 40, 52, 40, 43, 40, 50, 47]
    for bar in range(16):
        base = 0 if bar % 8 < 6 else 3
        for i in range(8):
            m = riff[i] + base
            n = int(b / 2 * SR)
            sig = lowpass(square(midi(m - 12), b / 2, 0.4), 900) * env(n, 0.002, b * 0.4, 0.3, 0.02)
            place(tr, sig * 0.22, (bar * 4 + i * 0.5) * b)
        for i in range(4):
            place(tr, drum_kick() * 0.5, (bar * 4 + i) * b)
            if i % 2 == 1:
                place(tr, drum_snare() * 0.35, (bar * 4 + i) * b)
            place(tr, drum_hat() * 0.15, (bar * 4 + i + 0.5) * b)
        if bar >= 4:
            lead = [64, 67, 71, 69, 67, 64, 62, 64]
            m = lead[bar % 8]
            n = int(4 * b * SR)
            sig = lowpass(square(midi(m), 4 * b, 0.25), 2200) * env(n, 0.02, b, 0.5, b) * (1 + 0.05 * sine(5, 4 * b))
            place(tr, sig * 0.1, bar * 4 * b)
    return tr


def m_oscuridad():
    tr, b = song(54, 8)
    t = np.arange(len(tr)) / SR
    for i in range(8 * 4):
        if i % 2 == 0:
            place(tr, lowpass(drum_kick(), 250) * 0.5, i * b)
            place(tr, lowpass(drum_kick(), 250) * 0.3, i * b + 0.22 * b)
    tr += lowpass(noise(len(tr)), 220) * 0.08 * (0.5 + 0.5 * np.sin(2 * np.pi * t / (8 * b)))
    tr += sine(midi(26), len(tr) / SR)[:len(tr)] * 0.12
    return tr


def music():
    tracks = {
        "santuario": m_santuario, "muelle": m_muelle, "catedral": m_catedral,
        "archivo": m_archivo, "raiz": m_raiz, "jefe": m_jefe, "oscuridad": m_oscuridad,
    }
    for k, f in tracks.items():
        x = f()
        write_ogg(os.path.join(ROOT, "music", k + ".ogg"), norm(x, 0.8), 2)
    return list(tracks)


if __name__ == "__main__":
    print("sfx:", ", ".join(sfx()))
    print("música:", ", ".join(music()))
