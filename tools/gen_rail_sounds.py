"""鉄道車両の動作音を合成してOGG(モノラル)で書き出す。 python gen_rail_sounds.py [出力先]
必要: numpy, ffmpeg(libvorbis)。音はすべて数式から作っており、外部の録音素材は使っていない。"""
import os, subprocess, sys, tempfile, wave
import numpy as np

SR = 44100
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "railwayvehicleaddon", "sounds")
rng = np.random.default_rng(1234)
t_of = lambda sec: np.arange(int(SR * sec)) / SR

def lowpass(x, cutoff):
    a = np.exp(-2 * np.pi * cutoff / SR); y = np.empty_like(x); acc = 0.0
    for i, v in enumerate(x):
        acc = (1 - a) * v + a * acc; y[i] = acc
    return y

def bandpass(x, lo, hi):
    X = np.fft.rfft(x); f = np.fft.rfftfreq(len(x), 1 / SR)
    X[(f < lo) | (f > hi)] = 0
    return np.fft.irfft(X, len(x))

def loopify(x, fade=0.25):
    """末尾をfade秒だけ先頭へ重ねてつなぎ目を消す(ループ用)。"""
    n = int(SR * fade); body = x[:-n].copy(); w = np.linspace(0, 1, n)
    body[:n] = body[:n] * w + x[-n:] * (1 - w)
    return body

def env(n, attack, release):
    e = np.ones(n); a = int(SR * attack); r = int(SR * release)
    if a: e[:a] = np.linspace(0, 1, a)
    if r: e[-r:] *= np.linspace(1, 0, r)
    return e

def norm(x, peak=0.9):
    return x / (np.max(np.abs(x)) + 1e-9) * peak

def save(name, x):
    os.makedirs(OUT, exist_ok=True)
    pcm = (np.clip(x, -1, 1) * 32767).astype(np.int16)
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        path = tmp.name
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", path, "-c:a", "libvorbis", "-q:a", "4", os.path.join(OUT, name + ".ogg")], check=True)
    os.remove(path)

def harmonic(t, f0, amps, phase_noise=0.0):
    return sum(a * np.sin(2 * np.pi * f0 * (k + 1) * t + phase_noise * k) for k, a in enumerate(amps))

# 走行音(レール上を転がる低いゴー音)。ループ
t = t_of(3.25)
n = rng.standard_normal(len(t))
roll = lowpass(lowpass(n, 180), 260) * 3.0 + bandpass(n, 400, 1400) * 0.25
roll *= 1 + 0.15 * np.sin(2 * np.pi * 2.0 * t)
save("rva_rolling", norm(loopify(roll), 0.7))

# レールの継ぎ目(ガタン)。1回分
t = t_of(0.35)
thump = np.sin(2 * np.pi * 70 * t) * np.exp(-t * 22) + 0.5 * np.sin(2 * np.pi * 140 * t) * np.exp(-t * 30)
click = bandpass(rng.standard_normal(len(t)), 800, 4000) * np.exp(-t * 90) * 0.6
ring = sum(np.sin(2 * np.pi * f * t) * np.exp(-t * d) for f, d in ((1180, 40), (1730, 55), (2650, 70))) * 0.12
save("rva_joint", norm(thump + click + ring, 0.9))

# ブレーキのきしみ(キー音)。ループ。大きく鋭い山を圧縮して(ソフトクリップ)目立つ部分だけを抑える
t = t_of(2.25)
f = 3150 + 25 * np.sin(2 * np.pi * 5 * t)
ph = 2 * np.pi * np.cumsum(f) / SR
sq = np.sin(ph) + 0.25 * np.sin(2 * ph) + 0.08 * np.sin(3.01 * ph)
sq += bandpass(rng.standard_normal(len(t)), 2000, 6000) * 0.25
sq *= 1 + 0.12 * np.sin(2 * np.pi * 1.3 * t)
sq = norm(sq, 1.0)
sq = np.tanh(sq * 1.8) / np.tanh(1.8)          # 山を潰して、音量のむらと耳に刺さるピークを減らす
save("rva_brake", norm(loopify(sq), 0.45))

# 連結音(ガチャン)
t = t_of(0.9)
clank = sum(a * np.sin(2 * np.pi * f * t) * np.exp(-t * d) for f, a, d in ((420, 1, 9), (1013, 0.6, 12), (1789, 0.4, 16), (2604, 0.3, 20), (3377, 0.2, 26)))
clank += np.sin(2 * np.pi * 60 * t) * np.exp(-t * 18) * 1.4 + bandpass(rng.standard_normal(len(t)), 500, 6000) * np.exp(-t * 60) * 0.8
d = int(SR * 0.06); clank[d:] += clank[:-d] * 0.35   # 2つの連結器が続けて当たる
save("rva_couple", norm(clank, 0.9))

# 空気の排出(停車時のプシュー)
t = t_of(1.1)
air = bandpass(rng.standard_normal(len(t)), 1500, 9000) * env(len(t), 0.02, 0.6) * np.exp(-t * 1.2)
save("rva_air", norm(air, 0.55))

# 蒸気機関車のドラフト音。動輪1回転に1回鳴らす、低く重い「ドゥシュッ」。
# 重い部品が押し出すような低い胴鳴り(減衰の遅い低音)と、こもった排気(低域に寄せたノイズ)を主にする。
# (この後の音の乱数の並びを変えないよう、以前と同じ量だけ共通の乱数を読み捨て、ドラフト音は専用の乱数で作る)
for _ in range(3):
    rng.standard_normal(int(SR * 0.32))
crng = np.random.default_rng(4321)
t = t_of(0.7)
n = crng.standard_normal(len(t))
attack = np.minimum(1, t / 0.03)
body = np.sin(2 * np.pi * (48 + 10 * np.exp(-t * 8)) * t) * np.exp(-t * 6) * 1.0     # 低い胴鳴り(少し下がる)
body += np.sin(2 * np.pi * 96 * t) * np.exp(-t * 9) * 0.35
exhaust = lowpass(lowpass(n, 380), 520) * 5.0 * attack * np.exp(-t * 5.5)              # こもった排気
breath = bandpass(n, 500, 1600) * 0.25 * attack * np.exp(-t * 7)                        # わずかな息の成分
chuff = body * attack + exhaust + breath
save("rva_chuff", norm(chuff - np.mean(chuff), 0.5))

# 汽笛(3音の和音、息の混じった音)
t = t_of(1.8)
w = sum(np.sin(2 * np.pi * f * t + 0.3 * np.sin(2 * np.pi * 4.5 * t)) * a for f, a in ((349, 1), (440, 0.8), (523, 0.6)))
w += sum(np.sin(2 * np.pi * 2 * f * t) * 0.2 for f in (349, 440, 523))
w += bandpass(rng.standard_normal(len(t)), 300, 3000) * 0.35
save("rva_whistle_steam", norm(w * env(len(t), 0.08, 0.35), 0.85))

# 電気機関車・電車の警笛(ファーン)
t = t_of(1.3)
def saw(f): return 2 * ((t * f) % 1.0) - 1
hz = (saw(330) + saw(415)) * 0.5
hz = bandpass(hz, 150, 3500)
save("rva_horn_electric", norm(hz * env(len(t), 0.04, 0.2), 0.85))

# ディーゼル機関車の警笛(低めの2音)
hz = bandpass((saw(262) + saw(311)) * 0.5, 120, 3000)
save("rva_horn_diesel", norm(hz * env(len(t), 0.05, 0.25), 0.85))

# 主電動機(VVVFインバータ風の唸り)。ループ。周波数は1Hzの整数倍にしてつなぎ目を無くす
t = t_of(2.0)
mv = harmonic(t, 220, [1, 0.5, 0.3, 0.15]) + 0.35 * np.sin(2 * np.pi * 1100 * t) + 0.2 * np.sin(2 * np.pi * 1650 * t)
save("rva_motor_vvvf", norm(mv, 0.6))

# 主電動機(抵抗制御・直流機の低い唸りとギア音)。ループ
mdc = harmonic(t, 110, [1, 0.6, 0.45, 0.3, 0.2, 0.1]) + 0.25 * np.sin(2 * np.pi * 770 * t)
save("rva_motor_dc", norm(mdc, 0.6))

# ディーゼルエンジン(ループ)。爆発の周期30Hzの鋭いパルス列
t = t_of(2.0)
pulse = np.zeros(len(t)); per = SR // 30
for i in range(0, len(t), per):
    k = np.arange(min(per, len(t) - i)) / SR
    pulse[i:i + len(k)] += np.exp(-k * 120) * (1.0 if (i // per) % 2 == 0 else 0.8)
eng = lowpass(pulse, 600) * 3 + harmonic(t, 30, [0.5, 0.3, 0.2]) * 0.3
save("rva_engine_diesel", norm(eng - np.mean(eng), 0.7))
print("written to", OUT)
