"""
Turns SADIE II dummy-head HRIRs (SOFA, Apache 2.0, University of York) into Continuum FX's
compact head file, app/src/main/assets/heads/<name>.hrir.

What it does to the measurements:
  1. Diffuse-field equalisation: the average response over every direction is divided out, so a
     sound from everywhere at once comes out flat. Placing a speaker then changes where it is,
     not how the music sounds, and the headphone EQ stays in charge of tone.
  2. Onset alignment: each response's arrival time (from a -20 dB threshold at 8x resolution) is
     taken out and stored on its own, so responses can be blended between measured directions
     without smearing, and the time difference between the ears is put back exactly.
  3. A regular grid: the nearest measured direction to each grid point, elevations -45 to 90.

Run by .github/workflows/hrir.yml:  python convert.py <out dir> <name>=<sofa file> ...
File layout (little-endian):
  "CHRIR" u8 version=1 | i32 sampleRate | i32 taps | i32 onset | f32 scale | i32 rows
  per row:   f32 elevation | i32 points
  per point: f32 azimuth (degrees, anticlockwise from the front: 90 is left) | f32 delayLeft
             | f32 delayRight (samples, after the onset) | i16[taps] left | i16[taps] right
"""
import sys, os, struct
import numpy as np
import h5py
from scipy.signal import resample_poly

FS = 48000
TAPS = 192
ONSET = 6
NFFT = 4096

def text(v):
    if isinstance(v, bytes):
        return v.decode(errors="replace")
    if isinstance(v, np.ndarray):
        return " ".join(text(x) for x in v.ravel())
    return str(v)

def load(path):
    f = h5py.File(path, "r")
    print(f"--- {path}")
    for k in ("Title", "DatabaseName", "ListenerShortName", "ListenerDescription", "SourceDescription", "Comment", "License"):
        if k in f.attrs:
            print(f"  {k}: {text(f.attrs[k])[:200]}")
    ir = np.array(f["Data.IR"], dtype=np.float64)          # M x R x N
    fs = float(np.array(f["Data.SamplingRate"]).ravel()[0])
    pos = np.array(f["SourcePosition"], dtype=np.float64)   # M x 3
    ptype = text(f["SourcePosition"].attrs.get("Type", b"spherical")).lower()
    print(f"  IR {ir.shape} at {fs:g} Hz, positions {ptype}")
    if "cartesian" in ptype:
        x, y, z = pos[:, 0], pos[:, 1], pos[:, 2]
        r = np.sqrt(x * x + y * y + z * z)
        pos = np.stack([np.degrees(np.arctan2(y, x)), np.degrees(np.arcsin(z / r)), r], axis=1)
    if fs != FS:
        from math import gcd
        g = gcd(int(fs), FS)
        ir = resample_poly(ir, FS // g, int(fs) // g, axis=2)
        print(f"  resampled to {FS}")
    pos[:, 0] = np.mod(pos[:, 0], 360.0)
    return ir, pos

def smooth_third_octave(freqs, mag2):
    out = np.empty_like(mag2)
    for i, f in enumerate(freqs):
        if f <= 0:
            out[i] = mag2[i]
            continue
        lo, hi = f * 2 ** (-1 / 6), f * 2 ** (1 / 6)
        sel = (freqs >= lo) & (freqs <= hi)
        out[i] = mag2[sel].mean()
    return out

def min_phase(mag, n):
    """A minimum-phase FIR of length n with the given magnitude on an NFFT grid (rfft bins)."""
    full = np.concatenate([mag, mag[-2:0:-1]])
    cep = np.fft.ifft(np.log(np.maximum(full, 1e-9))).real
    w = np.zeros(len(full))
    w[0] = 1
    w[1:len(full) // 2] = 2
    w[len(full) // 2] = 1
    h = np.fft.ifft(np.exp(np.fft.fft(cep * w))).real[:n]
    fade = 64
    h[-fade:] *= 0.5 * (1 + np.cos(np.linspace(0, np.pi, fade)))
    return h

def diffuse_field_inverse(ir):
    spec = np.fft.rfft(ir, NFFT, axis=2)
    p = (np.abs(spec) ** 2).mean(axis=(0, 1))
    freqs = np.fft.rfftfreq(NFFT, 1 / FS)
    p = smooth_third_octave(freqs, p)
    df = np.sqrt(p)
    inv = 1.0 / np.maximum(df, 1e-9)
    # The measurements say little below 150 Hz or above 18 kHz: hold the ends.
    lo = np.searchsorted(freqs, 150.0)
    hi = np.searchsorted(freqs, 18000.0)
    inv[:lo] = inv[lo]
    inv[hi:] = inv[hi]
    db = 20 * np.log10(inv)
    ref = db[np.searchsorted(freqs, 1000.0)]
    db = np.clip(db, ref - 15, ref + 15)
    print("  diffuse-field inverse (dB re 1 kHz): " + ", ".join(
        f"{int(f)}:{db[np.searchsorted(freqs, f)] - ref:+.1f}" for f in (100, 250, 1000, 2500, 4000, 8000, 12000, 16000)))
    return min_phase(10 ** (db / 20), 512)

def onset(x):
    up = resample_poly(x, 8, 1)
    a = np.abs(up)
    i = int(np.argmax(a >= 0.1 * a.max()))
    return i / 8.0

def shift(x, s, n):
    """x advanced by s samples (fractional), cut to n with a fade at the end."""
    m = 1 << int(np.ceil(np.log2(len(x) + 64)))
    X = np.fft.rfft(x, m)
    k = np.arange(len(X))
    y = np.fft.irfft(X * np.exp(2j * np.pi * k * s / m), m)[:n]
    fade = 32
    y[-fade:] *= 0.5 * (1 + np.cos(np.linspace(0, np.pi, fade)))
    return y

def grid():
    rows = []
    for el in (-45, -30, -15, 0, 15, 30, 45, 60, 75, 90):
        step = 5 if abs(el) <= 45 else (10 if el == 60 else (20 if el == 75 else 360))
        rows.append((el, [float(a) for a in np.arange(0, 360, step)]))
    return rows

def unit(az, el):
    a, e = np.radians(az), np.radians(el)
    return np.stack([np.cos(e) * np.cos(a), np.cos(e) * np.sin(a), np.sin(e)], axis=-1)

def convert(name, path, out):
    ir, pos = load(path)
    inv = diffuse_field_inverse(ir)
    eq = np.apply_along_axis(lambda x: np.convolve(x, inv), 2, ir)
    meas = unit(pos[:, 0], pos[:, 1])
    rows = grid()
    picked = []
    worst = 0.0
    for el, azs in rows:
        pts = []
        for az in azs:
            d = meas @ unit(az, el)
            i = int(np.argmax(d))
            worst = max(worst, np.degrees(np.arccos(min(1.0, d[i]))))
            pts.append((az, i))
        picked.append((el, pts))
    print(f"  grid points: {sum(len(p) for _, p in picked)}, furthest from a measurement: {worst:.2f} deg")

    cut = []
    lost = []
    for el, pts in picked:
        row = []
        for az, i in pts:
            l, r = eq[i, 0], eq[i, 1]
            ol, orr = onset(l), onset(r)
            first = min(ol, orr)
            # Both ears keep their own timing relative to the first; the first lands at ONSET.
            hl = shift(l, ol - ONSET, TAPS)
            hr = shift(r, orr - ONSET, TAPS)
            for full, h in ((l, hl), (r, hr)):
                lost.append(1 - (h ** 2).sum() / max((full ** 2).sum(), 1e-20))
            row.append((az, ol - ONSET, orr - ONSET, hl, hr))
        cut.append((el, row))
    print(f"  energy beyond {TAPS} taps: median {np.median(lost) * 100:.2f}%, worst {max(lost) * 100:.2f}%")
    side = [p for el, row in cut if el == 0 for p in row if p[0] == 90.0][0]
    print(f"  ears' time difference at 90 deg left: {(side[2] - side[1]) / FS * 1e6:.0f} us")

    scale = max(max(np.abs(p[3]).max(), np.abs(p[4]).max()) for _, row in cut for p in row) / 32767.0
    dst = os.path.join(out, f"{name}.hrir")
    with open(dst, "wb") as f:
        f.write(b"CHRIR" + struct.pack("<B", 1))
        f.write(struct.pack("<iiifi", FS, TAPS, ONSET, scale, len(cut)))
        for el, row in cut:
            f.write(struct.pack("<fi", float(el), len(row)))
            for az, dl, dr, hl, hr in row:
                f.write(struct.pack("<fff", az, dl, dr))
                f.write(np.round(hl / scale).astype("<i2").tobytes())
                f.write(np.round(hr / scale).astype("<i2").tobytes())
    print(f"  wrote {dst}: {os.path.getsize(dst)} bytes")

if __name__ == "__main__":
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    for arg in sys.argv[2:]:
        name, path = arg.split("=", 1)
        convert(name, path, out)
