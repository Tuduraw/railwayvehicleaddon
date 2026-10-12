"""japangen - 日本の鉄道車両パック(旅客・貨物)の生成スクリプト。meshkit(Blender不要)でモデルとテクスチャを作り、
tudursvehiclemod + railwayvehicleaddon 用の機体JSONを書き出す。
座標: +X=左, +Y=上, +Z=前(車両の1位側=前), 1単位=1m=1ブロック。原点はレール面の中心(車輪の下端がy=0)。
すべて実寸(1:1)で作る。縮小したい場合は機体JSONの scale を変える(台車位置・連結器も自動で縮む)。"""
import json, math, os, sys
import math
import numpy as np
import meshkit as mk
from meshkit.texture import texture_model
from meshkit import check
from meshkit import detail
from meshkit import texture as _mktex

# 正面の展開図(front)は前向き・後ろ向きの面を同じ平面に投影する。meshkit は他の面の陰になる割合が大きい面だけを
# 別の領域へ移すので、流線形の小さな鼻先は、反対側の車端(妻面)の大きな面とテクセルを共有してしまい、
# 鼻先や前面に反対側の妻面の塗装(貫通扉・灯具など)が出る。全車両で、前向き(+Z)の面を別の領域に分ける
_orig_assign_layers = _mktex.Atlas.assign_layers
def _assign_layers(self, *a, **k):
    r = _orig_assign_layers(self, *a, **k)
    for i, (pname, si, fi, kind, tag, Q) in enumerate(self.faces):
        if kind == "front" and _mktex.face_normal(Q)[2] > 0:
            self.faces[i] = (pname, si, fi, "front#pz", tag, Q)
    return r
_mktex.Atlas.assign_layers = _assign_layers

G = 1.435            # 車輪の左右間隔(既定の線路に合わせる。実物の在来線は1067mm)
WHEEL_HALF = G / 2 + 0.08
# 車体端から連結面までの距離。連結すると車体どうしの隙間はこの2倍になる(実車に近い0.4m)
COUPLER_REACH = 0.2

# ---------------------------------------------------------------- 共通部品
def roof_section(p, hw, t, b):
    """屋根だけが丸い断面(p = ("roof", 軒の高さの割合, 屋根の丸み q)): 側面は垂直、裾は小さな角の丸み、
    軒から上は (x/hw)^q + ((y-軒)/(t-軒))^q = 1 の屋根。(軒の高さ, 裾の角の半径, q) を返す"""
    _, e, q = p
    return b + e * (t - b), min(0.05, hw * 0.1), q

def section_x(p, hw, t, b, y):
    """断面の高さ y での半幅"""
    if isinstance(p, tuple) and p and p[0] == "roof":
        ye, rc, q = roof_section(p, hw, t, b)
        if y >= ye:
            return hw * max(0.0, 1 - ((y - ye) / max(t - ye, 1e-6)) ** q) ** (1 / q)
        if y < b + rc:
            return hw - rc + np.sqrt(max(rc * rc - (b + rc - y) ** 2, 0.0))
        return hw
    c, ht = (t + b) / 2, max((t - b) / 2, 1e-6)
    pp = (p[0] if y >= c else p[1]) if isinstance(p, tuple) else p
    ny = min(abs(y - c) / ht, 1.0)
    return hw * (1 - ny ** pp) ** (1 / pp)

def sring(cx, cy, z, hw, ht, n=16, p=5.0):
    """断面リング。p は数値(上下とも同じ丸み)か (上半分のp, 下半分のp)。下半分のpを小さくすると、
    下へ向かって内側へ回り込む(すそが丸く絞れる)断面になる。("roof", 軒の割合, q) は屋根だけが丸い角形の断面
    (点の数は n によらず22点。同じロフトの断面はすべて同じ種類にする)"""
    if isinstance(p, tuple) and p and p[0] == "roof":
        b, t = cy - ht, cy + ht
        ye, rc, q = roof_section(p, hw, t, b)
        pts = [(hw, (b + ye) / 2), (hw, ye)]
        for k in range(1, 12):                      # 屋根(右の軒から左の軒へ)
            ph = np.pi * k / 12
            c, s = np.cos(ph), np.sin(ph)
            pts.append((hw * np.sign(c) * abs(c) ** (2 / q), ye + (t - ye) * abs(s) ** (2 / q)))
        pts += [(-hw, ye), (-hw, (b + ye) / 2), (-hw, b + rc), (-hw + rc * 0.29, b + rc * 0.29), (-hw + rc, b), (0.0, b),
                (hw - rc, b), (hw - rc * 0.29, b + rc * 0.29), (hw, b + rc)]
        return np.array([(cx + x, y, z) for x, y in pts], float)
    if isinstance(p, tuple):
        up = np.asarray(mk.ring_superellipse(cx, cy, z, hw, ht, n=n, p=p[0], fit=True), float)
        lo = np.asarray(mk.ring_superellipse(cx, cy, z, hw, ht, n=n, p=p[1], fit=True), float)
        k = np.arange(n)
        upper = np.sin(2 * np.pi * k / n + np.pi / n * 0) >= 0
        return np.where(upper[:, None], up, lo)
    return mk.ring_superellipse(cx, cy, z, hw, ht, n=n, p=p, fit=True)

def rings_loft(m, part, tag, profile, n=16):
    """profile: [(z, hw, top, bottom, p)] を前後方向にロフトした車体"""
    # 半幅0の断面は尖端(1点)にする(丸い鼻先を小さな平面で塞がずに1点へ閉じる)
    rings = [np.array([[0.0, t, z]]) if hw <= 1e-6 else sring(0, (t + b) / 2, z, hw, (t - b) / 2, n=n, p=p)
             for z, hw, t, b, p in profile]
    sh = mk.loft(rings, tag=tag)
    m.add(part, sh)
    return sh

# ---- EF66 の車体(写真による): 断面は側面が垂直、窓の高さから上は約5°内側へ傾き(上ほど狭い)、屋根は丸い。
# 前面は四角錐: 頂点(車番プレートのすぐ上、高さ EF66_YA)から、縦(中心線)と横(頂点の高さ)の稜線で4つの面に分かれる。
# 上の左右の面がそれぞれ前面窓(平らなガラス)で、鼻の上面と同じ面(先端から窓の上端まで一直線)。
# 先端からの距離 d で、断面の各点 P を頂点へ向けて縮める: 面の式 d = KX|x| + KU(y-YA) (上) / KD(YA-y) (下)
EF66_YA, EF66_KX, EF66_KU, EF66_KD = 2.88, 0.31, 0.70 / 0.95, 0.5 / 1.78
EF66_WY0, EF66_WY1, EF66_SH = 3.19, 3.83, 3.75     # 前面窓の下端・上端(庇の下面)、屋根の肩の高さ

def ef66_section(hw, t, b):
    """EF66の断面の輪郭(反時計回り、下の中心から)。頂点の高さ・窓の下端に点を置く(稜線が断面の点を通るように)"""
    ya, tb, y0, ysh = EF66_YA, np.tan(np.radians(5.0)), EF66_WY0, EF66_SH
    R = [(0.0, b), (hw * 0.5, b), (hw - 0.06, b), (hw - 0.018, b + 0.018), (hw, b + 0.06)]
    for k in range(1, 5):
        R.append((hw, b + 0.06 + (ya - b - 0.06) * k / 4))
    R += [(hw, (ya + y0) / 2), (hw, y0)]
    for y in (3.37, 3.56, ysh):
        R.append((hw - tb * (y - y0), y))
    xs = hw - tb * (ysh - y0)
    for k in range(1, 6):
        ph = np.pi / 2 * k / 6
        R.append((xs * np.cos(ph) ** (2 / 3), ysh + (t - ysh) * np.sin(ph) ** (2 / 3)))
    R.append((0.0, t))
    return np.array(R + [(-x, y) for x, y in reversed(R[1:-1])], float)

def ef66_roof_y(hw, t, x):
    """EF66の屋根の上面の高さ(中心から x)"""
    P = ef66_section(hw, t, 1.4)
    up = P[(P[:, 0] >= 0) & (P[:, 1] >= EF66_SH - 1e-6)]
    up = up[np.argsort(up[:, 0])]
    return float(np.interp(abs(x), up[:, 0], up[:, 1]))

def ef66_nose(P2, d, k=10.0):
    """断面の点 P2 (N,2) を、先端からの距離 d の所の四角錐の面まで頂点へ向けて縮める。側面などへは丸くつなぐ"""
    x, y = P2[:, 0], P2[:, 1] - EF66_YA
    df = EF66_KX * np.abs(x) + np.where(y > 0, EF66_KU * y, -EF66_KD * y)
    q = np.maximum(d, 1e-6) / np.maximum(df, 1e-6)
    s = (1.0 + q ** (-k)) ** (-1.0 / k)
    out = P2.copy()
    out[:, 0] = x * s
    out[:, 1] = EF66_YA + y * s
    return out

def ef66_ring(z, hw, t, b, L, inset=0.0, floor=None):
    """EF66の断面リング(3D)。inset>0 は内張り(中心へ縮め、床より下は床で平ら、四角錐の面は inset+1cm 後ろ)"""
    P = ef66_section(hw, t, b)
    if inset > 0:
        c, ht = (t + b) / 2, (t - b) / 2
        P[:, 0] *= (hw - inset) / hw
        P[:, 1] = np.maximum(c + (P[:, 1] - c) * (ht - inset) / ht, floor)
    d = L / 2 - abs(z)
    d_eff = d - (inset + 0.01 if inset > 0 else 0.0)
    if d_eff <= 1e-4:
        return None if inset > 0 else np.array([[0.0, EF66_YA, z]])
    if inset > 0 and d_eff < 0.12:          # 内張りは先端の細い所を省く
        return None
    P = ef66_nose(P, d_eff)
    return np.column_stack([P[:, 0], P[:, 1], np.full(len(P), z)])

def ef66_inscribed(hw, t, b, d, delta=0.05):
    """内装の配置用: 四角錐の中に収まる断面(半幅・上端・下端)"""
    r = max(d - delta, 0.0)
    hwi = min(hw, max(0.05, 0.55 * r / EF66_KX))
    rest = max(r - EF66_KX * hwi, 0.0)
    return hwi, min(t, EF66_YA + rest / EF66_KU), max(b, EF66_YA - rest / EF66_KD)

def wheelset(m, part, z, r, tag="wheel"):
    m.add(part, mk.cylinder((-WHEEL_HALF, r, z), (WHEEL_HALF, r, z), r, n=12, tag=tag))

def bogie(m, part, zc, wb=2.1, r=0.43, axles=2, top=1.05, offsets=None):
    """台車。offsets を与えると、軸の位置を台車中心からの距離で指定できる(等間隔でない3軸台車など)"""
    if offsets is not None:
        zs = [zc + o for o in offsets]
    else:
        zs = np.linspace(zc - wb / 2, zc + wb / 2, axles) if axles > 1 else [zc]
    for z in zs:
        wheelset(m, part, z, r)
    span = (max(zs) - min(zs)) + 2 * r + 0.3
    zm = (max(zs) + min(zs)) / 2
    if r >= 0.5:                          # 機関車の大きな台車: 側枠を車輪の上まで伸ばし、側面から車輪の上部を隠す
        y0, y1 = r - 0.22, min(2 * r + 0.07, top - 0.02)
        for s in (1, -1):
            xs = s * (G / 2 + 0.12)
            m.add(part, mk.box(xs, (y0 + y1) / 2, zm, 0.14, y1 - y0, span, tag="bogie"))
            m.add(part, mk.box(xs + s * 0.02, y1 - 0.05, zm, 0.2, 0.1, span + 0.1, tag="bogie"))      # 上縁の補強
            for z in zs:                                                                      # 軸箱とばね
                m.add(part, mk.box(xs + s * 0.08, r, z, 0.08, 0.36, 0.34, tag="bogie"))
                m.add(part, mk.cylinder((xs + s * 0.09, r + 0.2, z), (xs + s * 0.09, y1 - 0.1, z), 0.09, n=8, tag="bogie"))
    else:
        for s in (1, -1):
            m.add(part, mk.box(s * (G / 2 + 0.12), r + 0.05, zm, 0.14, 0.42, span, tag="bogie"))
    m.add(part, mk.box(0, (r + 0.15 + top) / 2, zc, G + 0.3, top - r + 0.05, 0.5, tag="bogie"))  # まくらばり(車体に食い込ませる)

def aux_bogie(m, spec, z, wb=2.1, axles=2, top=1.05, r=0.43):
    """前後以外の台車(中間台車・先台車)。独立した部品にし、線路に追随させる(carry=false: 車体は載せない)"""
    aux = spec.setdefault("_aux_track", [])
    part = f"$bogie_aux{len(aux)}"
    bogie(m, part, z, wb=wb, r=r, axles=axles, top=top)
    aux.append({"part": part, "pivot_x": 0.0, "pivot_y": round(r, 3), "pivot_z": round(z, 3), "carry": False})

def underfloor(m, z0, z1, y_top, w=2.2):
    m.add("body", mk.box(0, y_top - 0.3, (z0 + z1) / 2, w, 0.62, abs(z1 - z0), tag="under"))

def rod(m, part, p0, p1, r=0.03, tag="panto", n=6):
    m.add(part, mk.cylinder(p0, p1, r, n=n, tag=tag))

def pantograph(m, z, roof, single_arm=False, part="body", flip=False):
    """パンタグラフ(上昇した状態)。菱形(PS16等)とシングルアーム(PS33等)。
    台枠を4本の碍子で屋根に載せ、枠組み・集電舟(2本のすり板と左右のホーン)を細い管で組む。上端は屋根から約1.15m"""
    h = 1.15
    y0 = roof + 0.22                     # 台枠の上面
    top = roof + h                       # すり板の高さ
    # 碍子と台枠
    for x in (0.45, -0.45):
        for dz in (0.55, -0.55):
            m.add(part, mk.cylinder((x, roof - 0.02, z + dz), (x, y0 - 0.06, z + dz), 0.07, 0.05, n=8, tag="insulator"))
    for x in (0.45, -0.45):
        m.add(part, mk.box(x, y0 - 0.04, z, 0.08, 0.06, 1.3, tag="panto"))
    for dz in (0.55, -0.55):
        m.add(part, mk.box(0, y0 - 0.04, z + dz, 0.98, 0.06, 0.08, tag="panto"))
    if single_arm:
        fs = -1.0 if flip else 1.0       # flip: 折れる向きを前後逆にする
        hz = z - 0.55 * fs               # 主軸(台枠の後ろの横材の上)
        knee = (z + 0.42 * fs, y0 + (top - y0) * 0.48)
        head_z = z - 0.05 * fs
        for x in (0.16, -0.16):          # 下枠(二股)
            rod(m, part, (x, y0, hz), (x * 0.4, knee[1], knee[0]), 0.035)
        rod(m, part, (0.3, y0, hz), (-0.3, y0, hz), 0.05)                                     # 主軸
        rod(m, part, (0.0, knee[1], knee[0]), (0.0, top - 0.06, head_z), 0.03)              # 上枠
        rod(m, part, (0.24, y0, hz), (0.03, knee[1], knee[0]), 0.015)                        # 釣り合い棒
        rod(m, part, (-0.24, y0, hz), (-0.24, y0 + 0.08, hz + 0.45 * fs), 0.045)              # 上昇ばねの筒
        head_zs = (head_z - 0.12, head_z + 0.12)
        rod(m, part, (0.0, top - 0.08, head_zs[0]), (0.0, top - 0.08, head_zs[1]), 0.025)
        sup_x = (0.0,)
    else:
        # 側面から見て菱形: 台枠の前後の軸から外側へ開いて肘、そこから内側へ閉じて頂部
        bz, kz, tz = 0.32, 0.72, 0.18
        ky = y0 + (top - y0) * 0.45
        for x in (0.42, -0.42):
            for sg in (1, -1):
                rod(m, part, (x, y0, z + sg * bz), (x * 0.9, ky, z + sg * kz), 0.03)
                rod(m, part, (x * 0.9, ky, z + sg * kz), (x * 0.7, top - 0.08, z + sg * tz), 0.025)
        for sg in (1, -1):               # 左右をつなぐ横棒(軸・肘・頂部)
            rod(m, part, (0.42, y0, z + sg * bz), (-0.42, y0, z + sg * bz), 0.035)
            rod(m, part, (0.38, ky, z + sg * kz), (-0.38, ky, z + sg * kz), 0.02)
            rod(m, part, (0.3, top - 0.08, z + sg * tz), (-0.3, top - 0.08, z + sg * tz), 0.025)
        head_zs = (z - tz, z + tz)
        sup_x = (0.29, -0.29)
    # 集電舟: すり板2本と、両端で下へ曲がるホーン
    for hz2 in head_zs:
        for x in sup_x:
            rod(m, part, (x, top - 0.08, hz2), (x, top - 0.01, hz2), 0.02)
        m.add(part, mk.box(0, top, hz2, 1.1, 0.05, 0.07, tag="panto"))
        for sg in (1, -1):
            rod(m, part, (sg * 0.53, top, hz2), (sg * 0.82, top - 0.07, hz2), 0.022)
            rod(m, part, (sg * 0.81, top - 0.065, hz2), (sg * 0.95, top - 0.22, hz2), 0.02)
    for sg in (1, -1):                   # 舟の両端をつなぐホーンの横材
        rod(m, part, (sg * 0.9, top - 0.15, head_zs[0]), (sg * 0.9, top - 0.15, head_zs[1]), 0.015)

def roof_ac(m, z, w, H, kind="unit"):
    """冷房装置。kind: "unit"(集中式・分散式の箱形。上面にファンの丸い開口)"""
    m.add("body", mk.box(0, H + 0.04, z, w, 0.14, 2.2, tag="roofunit"))
    m.add("body", mk.box(0, H + 0.2, z, w * 0.94, 0.22, 2.1, tag="roofunit"))
    for dz in ((-0.55, 0.55) if w > 1.5 else (0.0,)):
        m.add("body", mk.cylinder((0, H + 0.3, z + dz), (0, H + 0.36, z + dz), min(0.36, w * 0.24), n=12, tag="acfan"))

def ventilators(m, L, H, step=2.2, globe=True, span=None):
    """通風器(押込形・グローブ形)を屋根の中心付近に千鳥に並べる(span: 並べる長さ。二重屋根は上の屋根の長さ)"""
    span = (L - 3.0) if span is None else span
    n = int(span / step)
    for i in range(n + 1):
        zc = -span / 2 + i * span / max(1, n)
        x = 0.35 if i % 2 == 0 else -0.35
        if globe:
            m.add("body", mk.cylinder((x, H - 0.1, zc), (x, H + 0.12, zc), 0.12, n=8, tag="roofunit"))
            m.add("body", mk.lathe((x, H + 0.12, zc), (0, 1, 0), [(0, 0.2), (0.08, 0.2), (0.16, 0.12), (0.2, 0.0)], n=8, tag="roofunit"))
        else:
            m.add("body", mk.box(x, H + 0.02, zc, 0.5, 0.2, 0.7, tag="roofunit"))

def antenna(m, z, H, x=0.6):
    """列車無線アンテナ(台座と板状のアンテナ)"""
    m.add("body", mk.box(x, H - 0.02, z, 0.32, 0.14, 0.42, tag="antenna"))
    m.add("body", mk.box(x, H + 0.2, z, 0.04, 0.34, 0.3, tag="antenna"))

def arresters(m, z, H):
    """避雷器・断路器(パンタグラフの脇の碍子付きの小さな機器)"""
    for x in (0.95, -0.95):
        m.add("body", mk.cylinder((x, H - 0.12, z), (x, H + 0.2, z), 0.08, 0.06, n=8, tag="insulator"))
        m.add("body", mk.cylinder((x, H + 0.2, z), (x, H + 0.32, z), 0.09, n=8, tag="panto"))

def skirt(m, zend, sgn, floor, width=2.3, notch=True, bottom=0.28, part="body", top=None):
    """前面下部のスカート(排障器)。連結器の部分は切り欠く(notch)"""
    zc = zend - sgn * 0.14
    top = floor - 0.1 if top is None else top
    if notch:
        for sg in (1, -1):
            m.add(part, mk.box(sg * (0.36 + (width / 2 - 0.36) / 2), (bottom + top) / 2, zc, width / 2 - 0.36, top - bottom, 0.08, tag="skirt"))
        m.add(part, mk.box(0, (bottom + 0.62) / 2, zc, 0.74, 0.62 - bottom, 0.08, tag="skirt"))
    else:
        m.add(part, mk.box(0, (bottom + top) / 2, zc, width, top - bottom, 0.08, tag="skirt"))
    for sg in (1, -1):                       # 裏側の補強(斜めの支え)
        m.add(part, mk.box(sg * (width / 2 - 0.15), (bottom + top) / 2 + 0.1, zc - sgn * 0.25, 0.08, top - bottom - 0.2, 0.45, tag="skirt"))

def equipment(m, z0, z1, floor, W, seed=0, center_sill=True):
    """床下機器: 大きさの違う箱を隙間を空けて並べる(制御器・抵抗器・空気圧縮機・蓄電池箱など)"""
    rng = np.random.default_rng(seed)
    top = floor - 0.1
    if center_sill:
        m.add("body", mk.box(0, top - 0.05, (z0 + z1) / 2, 0.5, 0.18, abs(z1 - z0) + 0.4, tag="under"))
    z = min(z0, z1)
    zmax = max(z0, z1)
    while z < zmax - 0.6:
        ln = float(rng.uniform(0.9, 2.6))
        ln = min(ln, zmax - z)
        hgt = float(rng.uniform(0.38, 0.68))
        wd = float(rng.uniform(0.55, 0.9)) * W
        xo = float(rng.uniform(-0.12, 0.12))
        m.add("body", mk.box(xo, top - hgt / 2 + 0.03, z + ln / 2, wd, hgt, ln, tag="under"))
        if rng.random() < 0.35:              # 側面の点検ふた・取っ手のある小箱
            m.add("body", mk.box(xo + (wd / 2 - 0.05) * (1 if rng.random() < 0.5 else -1), top - hgt * 0.4, z + ln / 2, 0.12, hgt * 0.5, ln * 0.6, tag="under"))
        z += ln + float(rng.uniform(0.2, 0.5))

def end_profile(kind, zend, sign, hw, top, bot, p):
    """車端の断面列(sign=+1で前端)。kind: flat / round / bonnet / sk0 / e5 / slope"""
    z = lambda d: zend - sign * d
    if kind == "flat":
        return [(z(0.25), hw, top, bot, p), (z(0.0), hw * 0.97, top - 0.12, bot, p)]
    if kind == "slope":   # 前面がやや傾いた箱形(電機・近年の電車)
        return [(z(0.6), hw, top, bot, p), (z(0.0), hw * 0.95, top - 0.45, bot, p)]
    if kind == "round":   # 丸みのある前面(EF58・キハ58等)
        return [(z(0.9), hw, top, bot, p), (z(0.35), hw * 0.93, top - 0.12, bot, p), (z(0.0), hw * 0.78, top - 0.35, bot + 0.05, p)]
    if kind == "bonnet":  # ボンネット(151系・485系の先頭車)
        # 写真(パブリックドメインのクハ481・クハ489)から読み取った形。先端からの距離 d、レール面からの高さ:
        #  ボンネットは前へ向かってわずかにしぼむ(半幅: 前面窓の下 d2.4 で全幅 → 先端で約1.17m。上から見て直線)。
        #  正面はほぼ垂直な平らな面で、左右の角は半径約0.3mで丸い。上端は半径約0.3mで丸く後ろへ回り、
        #  ボンネットの上面は d0.3・約2.88m から前面窓の下端 d2.55・約3.29m まで直線。
        #  運転室は車体の屋根から1段高い(屋根の高さ BONNET_CAB_UP。屋根上の冷房装置の上面と同じくらい)。
        #  前面窓は d2.55〜2.85(約3.34〜3.86m)で側面へ回り込み、運転室の後ろの端 d4.5〜4.8 で車体の屋根へ下りる
        #  (左右の灯具と側面の赤い帯の部分、裾の赤帯は全幅のまま。front_bonnet_parts で付ける)
        sc = hw / 1.45
        rc, rt = 0.3, 0.3
        ctop = top + BONNET_CAB_UP
        wh = lambda d: (1.17 + (1.45 - 1.17) * min(max(d / 2.4, 0.0), 1.0)) * sc
        pts = []
        for d in (0.0, 0.03, 0.1, 0.2, 0.3, 0.8, 1.5, 2.2, 2.4, 2.55, 2.85, 3.1, 3.6, 4.5, 4.8, 5.2):
            w = wh(d) - rc + np.sqrt(max(rc * rc - (rc - d) ** 2, 0.0)) if d < rc else wh(d)
            if d < rt:
                t = 2.58 + np.sqrt(max(rt * rt - (rt - d) ** 2, 0.0))
            elif d <= 2.55:
                t = 2.88 + (3.29 - 2.88) * (d - rt) / (2.55 - rt)
            else:
                t = {2.85: 3.86, 3.1: ctop - 0.03, 3.6: ctop, 4.5: ctop, 4.8: top, 5.2: top}[d]
            pp = (3.2, 8.0) if d < 2.55 else ((4.0, p) if d < 4.8 else p)
            pts.append((z(d), min(w, hw), t, bot, pp))
        return pts
    if kind == "sk0":
        # 0系の先頭。日本車輌の公開図面(21形)の側面図・上面図・正面図から読み取った外形(先端からの距離d、レール面からの高さ):
        #  上面図の半幅: d0.16→0.58、0.47→1.0、1.0→1.26、1.53→1.42、2.06→1.55、2.6→1.63、3.6→1.69(全幅)
        #  側面図の上縁: 先端の丸い正面(高さ約1.2〜2.3m)→鼻の上面がゆるく上がり、d2.16で約3.05m(前面窓の下端)
        #               →前面窓が約45°で立ち上がり、d2.8で約3.64m(前面窓の上端)→運転台の屋根がd3.9で車体の屋根(3.975m)に
        #  正面図: 角の丸い四角の断面。先端の正面は中心高さ約1.74mの丸い面
        H0 = top
        # 外形は図面の読み取り点を滑らかな曲線(形を保つ3次補間)でつなぎ、細かい間隔の断面で作る。先端は丸い面
        # (正面から見て中心高さ約1.74m・半径約0.55mの丸)で、鼻先の半幅・上端・下端は先端からの距離の平方根に比例して
        # 立ち上がる(=先端の接線が垂直な、丸い鼻先になる)。断面の丸み p も同じ曲線で連続的に変える
        from scipy.interpolate import PchipInterpolator as _P
        yc = 1.74                                       # 先端の丸い面の中心の高さ
        u = lambda d: np.sqrt(np.asarray(d, float))
        dw = [0.0, 0.16, 0.47, 1.0, 1.53, 2.06, 2.6, 3.6, 3.9]          # 上面図の半幅
        ww = [0.0, 0.58, 1.0, 1.26, 1.42, 1.55, 1.63, 1.69, 1.69]
        dt = [0.0, 0.16, 0.68, 1.0, 1.53, 2.16, 2.8, 3.3, 3.9]           # 側面図の上縁(前面窓の下端d2.16・上端d2.8)
        tt = [yc, 2.27, 2.63, 2.76, 2.90, 3.05, 3.64, H0 - 0.11, H0]
        db = [0.0, 0.16, 0.37, 0.68, 3.9]                                 # 側面図の下縁(鼻の下は車体の下端へ)
        bb = [yc, 1.20, 1.04, bot, bot]
        dp = [0.0, 0.37, 0.68, 1.0, 1.53, 2.16, 2.8, 3.3, 3.9]           # 断面の丸み(上半分・下半分)
        pu = [2.0, 2.25, 2.45, 2.65, 2.95, 3.4, 4.1, p, p]
        pl = [2.0, 2.0, 2.1, 2.2, 2.45, 2.8, 3.3, 3.9, 4.5]
        fw, ft, fb = _P(u(dw), ww), _P(u(dt), tt), _P(u(db), bb)
        fpu, fpl = _P(u(dp), pu), _P(u(dp), pl)
        ds = [0.0, 0.012, 0.035, 0.06, 0.09, 0.13, 0.18, 0.24, 0.31, 0.39, 0.48, 0.58, 0.69, 0.81, 0.94, 1.08, 1.23,
              1.39, 1.56, 1.74, 1.93, 2.16, 2.32, 2.48, 2.64, 2.8, 2.98, 3.16, 3.35, 3.55, 3.75, 3.9]
        sc = hw / 1.69                        # 幅の違う車体にも使えるよう、図面の半幅(1.69m)に対する比で縮める
        out = []
        for d in ds:
            ud = u(d)
            w_, t_, b_ = float(fw(ud)), float(ft(ud)), float(fb(ud))
            pp = (float(fpu(ud)), float(fpl(ud)))
            if d == 0.0:
                out.append((z(d), 0.0, yc, yc, pp))      # 尖端(1点)
                continue
            out.append((z(d), (w_ * sc) if d < 3.9 else hw, t_, b_, pp if d < 3.9 else (p, 4.5)))
        out.append((z(5.0), hw, H0, bot, p))
        return out
    if kind == "box":     # 箱形の電気機関車(EF65・EF81): ほぼ垂直な平らな前面。角をわずかに落とす
        return [(z(0.3), hw, top, bot, p), (z(0.06), hw * 0.985, top - 0.04, bot, p), (z(0.0), hw * 0.96, top - 0.1, bot + 0.02, p)]
    if kind == "ef81":    # EF81(写真による): 平らな前面。屋根の前の縁は大きく丸く(前照灯はこの丸みに付く)、左右の縦の角も丸い
        pts = [(0.0, 0.955, top - 0.3, bot + 0.02), (0.02, 0.972, top - 0.22, bot), (0.05, 0.986, top - 0.15, bot), (0.1, 0.995, top - 0.08, bot),
               (0.18, 1.0, top - 0.03, bot), (0.3, 1.0, top, bot)]
        return [(z(d), hw * k, t, b_, p) for d, k, t, b_ in pts]
    if kind == "ef65pf":  # EF65 1000番台(PF形。写真による): 平らな前面(中央に貫通扉)。角の丸みは小さい
        pts = [(0.0, 0.97, top - 0.17, bot + 0.02), (0.03, 0.986, top - 0.1, bot), (0.08, 0.996, top - 0.04, bot), (0.2, 1.0, top, bot)]
        return [(z(d), hw * k, t, b_, p) for d, k, t, b_ in pts]
    if kind == "kuno":    # DF200・EF210: 側面から見てくの字の前面。前照灯の高さで折れ、上の傾斜(前面窓)の方が長い
        yb = bot + 0.75                                   # 折れる高さ(前照灯の高さ)
        te = top - 0.25                                   # 上の傾斜の上端(屋根の丸みの始まり)
        out = []
        for d in (0.0, 0.06, 0.14, 0.28, 0.42, 0.6, 0.72, 0.85, 1.2):
            t = yb + 0.05 + (te - yb - 0.05) * min(d / 0.6, 1.0) if d <= 0.6 else te + (top - te) * min((d - 0.6) / 0.25, 1.0)
            b_ = yb - 0.05 - (yb - 0.05 - bot) * min(d / 0.28, 1.0)
            w = hw * (0.93 + 0.07 * min(d / 0.3, 1.0))
            out.append((z(d), w, t, b_, p))
        return out
    if kind == "ef58":    # EF58: 上から見てくの字(中央が前に出る)、運転室の窓の少し下から上は後ろへ傾く
        ys = bot + 1.05
        out = []
        for d in (0.0, 0.08, 0.2, 0.32, 0.45, 0.6, 0.75, 0.9, 1.3):
            w = hw * (0.12 + 0.88 * min(d / 0.42, 1.0))
            t = ys + (top - 0.06 - ys) * min(d / 0.9, 1.0) if d < 1.3 else top
            out.append((z(d), w, t, bot, (4.0, p) if d < 0.9 else p))
        return out
    if kind == "ef66":    # EF66(写真による): 外形は ef66_ring で作る(四角錐の前面・窓から上の5°の傾き)。ここでは包絡の断面だけ
        out = []
        for d in (0.0, 0.05, 0.1, 0.15, 0.2, 0.25, 0.3, 0.38, 0.46, 0.55, 0.64, 0.73, 0.82, 0.91, 1.0, 1.08,
                  1.15, 1.22, 1.3, 1.4, 1.5, 1.65, 1.8):
            b_ = 1.1 if d <= 1.15 else 1.1 + (bot - 1.1) * min((d - 1.15) / 0.15, 1.0)
            out.append((z(d), hw, top, b_, 6.0))
        return out
    if kind == "103":     # 103系の先頭: 車体はここで終わり、前面(窓の窪み・頭の前照灯)は front_mask_103 で付ける
        # 断面(かまぼこ形)のまま前端で垂直に切った形。車体は前面の部品の後ろの端(MASK103)まで
        return [(z(0.6), hw, top, bot, p), (z(MASK103), hw, top, bot, p)]
    if kind == "e5":      # E5系の長い先頭(約15m)
        return [(z(15.0), hw, top, bot, p), (z(11.0), hw * 0.99, top - 0.15, bot, p), (z(8.0), hw * 0.96, top - 0.55, bot, 4.0),
                (z(6.0), hw * 0.92, top - 0.85, bot, 3.5), (z(3.5), hw * 0.80, bot + 1.55, bot, 3.0),
                (z(1.5), hw * 0.55, bot + 1.0, bot + 0.05, 2.6), (z(0.0), hw * 0.18, bot + 0.6, bot + 0.25, 2.2)]
    raise ValueError(kind)

BONNET_CAB_UP = 0.42   # 485系: 運転室の屋根が車体の屋根より高い分
MASK103 = 0.22         # 103系の前面の厚み(車体の端からこの長さを前面の部品で作る)

def front_mask_103(m, spec, sgn, floor, bot):
    """103系(低運転台)の前面。かまぼこ形の断面(側面は垂直、屋根はアーチ)を前端で垂直に切った平らな面。
    水平な断面(XZ面の閉じた輪)を下から上へ積んで作る。窓(3枚の窓が枠で仕切られた1続きの窓)は前面から
    わずかに窪み、上ほど奥へ入る傾斜。前面の左右の縦の角だけ小さく丸める。前照灯は前面の上端の中央(アーチの頂部)"""
    L, W, H = spec["L"], spec["W"], spec["H"]
    hw, t, b, p = W / 2, H, bot, spec.get("p", 5.0)
    ye = roof_section(p, hw, t, b)[0]
    zend = sgn * L / 2
    z = lambda d: zend - sgn * d
    w103 = spec["windows"]
    wy0, wy1, xw = w103["cab_y0"] - 0.005, w103["cab_y1"] + 0.005, w103["cab_xmax"] * hw + 0.005
    rco = 0.05
    ys = [b, b + 0.012, b + 0.03, b + 0.05, floor + 0.3, floor + 0.6, wy0 - 0.004, wy0, (wy0 + wy1) / 2, wy1, wy1 + 0.004, ye]
    ys += [ye + (t - ye) * s for s in (0.25, 0.5, 0.7, 0.85, 0.94, 0.985)]
    ys = sorted(set(round(y, 5) for y in ys if b <= y < t))
    rings = []
    for y in ys:
        xm = max(section_x(p, hw, t, b, y), 0.05)
        inwin = wy0 <= y <= wy1
        c2 = min(rco, xm * 0.3) if y < ye + 0.01 else 0.0     # 縦の角の丸み(屋根のアーチの部分は丸めない)
        xe = min(xw, (xm - max(c2, 0.02)) * 0.85)
        def zf(x):
            ax = abs(x)
            dd = 0.0
            if c2 > 0 and ax > xm - c2:
                u = (ax - (xm - c2)) / c2
                dd = c2 * (1 - np.sqrt(max(0.0, 1 - u * u)))
            if inwin and ax <= xe + 1e-6:
                dd += 0.03 + 0.06 * (y - wy0) / (wy1 - wy0)          # 窓: わずかに窪み、上ほど奥へ
            return z(min(dd, MASK103 - 0.004))
        xs = [xm, xm - max(c2, 0.01) * 0.3, xm - max(c2, 0.02), xe + 0.004, xe, xe * 0.5]
        right = [(x, y, zf(x)) for x in xs]
        loop = [(xm, y, z(MASK103))] + right + [(0.0, y, zf(0.0))] + [(-x, y, zf(-x)) for x, _, _ in reversed(right)] + [(-xm, y, z(MASK103))]
        rings.append(np.array(loop, float))
    m.add("body", mk.loft(rings, tag="body"))
    # 前照灯: 前面の上端の中央の丸い灯具(短い円筒)
    m.add("body", mk.cylinder((0.0, t - 0.2, z(0.03)), (0.0, t - 0.2, z(-0.05)), 0.13, n=12, tag="body"))

def car_body(m, L, W, H, floor, front="flat", rear="flat", p=5.0, roof_round=True, bot=None):
    hw, top = W / 2, H
    bot = floor - 0.15 if bot is None else bot
    fz, rz = L / 2, -L / 2
    prof = list(reversed(end_profile(rear, rz, -1, hw, top, bot, p)))
    prof = prof + end_profile(front, fz, 1, hw, top, bot, p)
    prof.sort(key=lambda r: r[0])
    # 車体は1つのシェル(前後に分けると、継ぎ目のふたが車内の中央に仕切りとして残る)。前後の妻面がテクセルを
    # 取り合わないよう、正面の展開図は前向き・後ろ向きの面で分ける(_assign_layers)
    n = 28 if "sk0" in (front, rear) else 20          # 0系の丸い鼻は断面の分割を細かくする
    if front == "ef66":                                # EF66: 専用の断面と四角錐の前面
        sh = mk.loft([ef66_ring(z, hw_, t, b, L) for z, hw_, t, b, _ in prof], tag="body")
        split_twisted(sh)
        m.add("body", sh)
        return prof
    rings_loft(m, "body", "body", prof, n=n)
    return prof

# ---------------------------------------------------------------- 塗装
def rgb(c):
    return np.asarray(c, np.float32)

def rounded_rect(Z, Y, zc, ww, y0, y1, r, grow=0.0):
    """角の丸い長方形の内側(窓の形)。grow だけ外側へ広げた形も作れる(窓枠用)"""
    hz, hy = ww / 2 + grow, (y1 - y0) / 2 + grow
    yc = (y0 + y1) / 2
    rr = min(r + grow, hz, hy) if r > 0 else 0.0
    dz = np.abs(Z - zc) - (hz - rr)
    dy = np.abs(Y - yc) - (hy - rr)
    inside = (np.abs(Z - zc) <= hz) & (np.abs(Y - yc) <= hy)
    if rr > 0:
        inside &= (np.maximum(dz, 0) ** 2 + np.maximum(dy, 0) ** 2) <= rr * rr
    return inside

def window_rows(win, Z, Y, side, grow=0.0):
    """側窓の並び。rows の各行 (z0, z1, 幅, 間隔, 下端, 上端) の窓を、角の丸み win['win_r'] で描く"""
    sel = np.zeros(len(Z), bool)
    for z0, z1, ww, pitch, y0, y1 in win.get("rows", []):
        k = np.floor((Z - z0) / pitch)
        zc = z0 + (k + 0.5) * pitch
        ok = (zc - ww / 2 > z0 - 1e-6) & (zc + ww / 2 < z1 + 1e-6)
        sel |= side & ok & rounded_rect(Z, Y, zc, ww, y0, y1, win.get("win_r", 0.0), grow)
    return sel

PILLAR103 = 0.41        # 103系の前面窓の柱の位置(中心からの距離)

def front103_signs(win, X, Y):
    """103系の前面の行先表示器(中央の窓の上部)と運行番号表示器(助士席側の窓の上部)"""
    y1 = win.get("cab_y1", 3.0)
    dest = (np.abs(X) < 0.27) & (Y > y1 - 0.24) & (Y < y1 - 0.04)
    num = (np.abs(X + 0.83) < 0.2) & (Y > y1 - 0.2) & (Y < y1 - 0.04)
    return dest | num

def cab_pane_filter(win, X, Y, floor):
    """前面窓の柱・貫通扉の形(front_style)。ガラスにする部分をTrueで返す"""
    style = win.get("front_style")
    ok = np.ones(len(X), bool)
    if win.get("cab_split") or style == "split":
        ok &= np.abs(X) > 0.06
    if style == "hood":                             # 凸形の機関車: 中央の幅広の柱の左右に窓
        ok &= np.abs(X) > 0.3
    if style == "103":                              # 103系: 1続きの窓を2本の柱で3枚に仕切る。中央の窓の上に行先表示器、
        ok &= ~(np.abs(np.abs(X) - PILLAR103) < 0.04)  # 助士席側(-X)の窓の上に運行番号表示器(どちらも表示なしの暗い板)
        ok &= ~front103_signs(win, X, Y)
    if style == "pf":                               # EF65 PF: 中央の貫通扉の窓(細い)と左右の窓
        ok &= ~((np.abs(X) >= 0.2) & (np.abs(X) <= 0.5))
    if style == "kantsu":                           # 左右の窓と中央の貫通扉(扉の窓は少し小さい)
        ok &= ~((np.abs(X) > 0.4) & (np.abs(X) < 0.56))
        ok &= ~((np.abs(X) <= 0.4) & (np.abs(X) > 0.3))
    return ok

def sk0_cab_side(win, X, Y, Z, L, grow=0.0):
    """0系の運転台の側窓(前面窓から乗務員扉の手前まで側面に回り込むガラス)。win['sk0_side'] の各項目:
    (向き, 下端の前端d, 上端の前端d, 後端d, 下端y, 上端y, 上端が下がり始めるd, 後端での上端y)。d は先端からの距離"""
    sel = np.zeros(len(X), bool)
    for sgn, df0, df1, dr, y0, y1, dk, y1r in win.get("sk0_side", []):
        d = L / 2 - sgn * Z
        t = np.clip((Y - y0) / max(y1 - y0, 1e-6), 0, 1)
        dfront = df0 + (df1 - df0) * t                     # 前端は前面窓の傾斜に沿って上ほど後ろ
        top = np.where(d < dk, y1, y1 + (y1r - y1) * np.clip((d - dk) / max(dr - dk, 1e-6), 0, 1))   # 上端は屋根の丸みに沿って後ろほど低い
        sel |= (np.abs(X) > 1.0) & (d > dfront - grow) & (d < dr + grow) & (Y > y0 - grow) & (Y < top + grow)
    return sel

def cab_pillars(win, X, Y, Z, L):
    """運転室の窓の柱(窓を何枚かに仕切る)。win['pillars']: ("ax", |X|の下限, 上限) か ("d", 先端からの距離の下限, 上限)。
    窓の高さの範囲(cab_y0〜cab_y1 の少し外まで)だけ"""
    sel = np.zeros(len(X), bool)
    if not win.get("pillars"):
        return sel
    y0, y1 = win.get("cab_y0", 0.0) - 0.08, win.get("cab_y1", 9.0) + 0.08
    d = L / 2 - Z
    for kind, a, b in win["pillars"]:
        v = np.abs(X) if kind == "ax" else d
        sel |= (v >= a) & (v <= b)
    return sel & (Y > y0) & (Y < y1) & (d < 4.5)

def window_mask(spec, L, W, H, floor):
    """ガラスの部分(側窓・扉の窓・前面窓・貫通扉の窓)。make_paint の窓の塗り分けと同じ範囲。透過(アルファ)に使う"""
    hw = W / 2
    win = spec.get("windows", {})
    def fn(X, Y, Z):
        sel = np.zeros(len(X), bool)
        side = np.abs(X) > hw * 0.72
        sel |= window_rows(win, Z, Y, side)
        if win.get("door_glass", True):
            for zc, dw in win.get("doors", []):
                sel |= side & (np.abs(Z - zc) < dw / 2 - 0.12) & (Y > floor + 0.95) & (Y < floor + 1.7)
        for zc, dw in win.get("crew_doors", []):
            sel |= side & (np.abs(Z - zc) < dw / 2 - 0.1) & (Y > floor + 1.0) & (Y < floor + 1.6)
        for z0, z1, y0, y1 in win.get("cab_side", []):
            sel |= side & (Z > z0) & (Z < z1) & (Y > y0) & (Y < y1)
        sel |= sk0_cab_side(win, X, Y, Z, L)
        for cab in win.get("cabs", []):
            zf, sgn = cab[0], cab[1]
            depth = cab[2] if len(cab) > 2 else 0.6
            d = (Z - zf) * sgn
            c = (np.abs(X) < hw * win.get("cab_xmax", 0.9)) & (d > -0.5) & (d < depth) & (Y > win.get("cab_y0", floor + 1.15)) & (Y < win.get("cab_y1", floor + 1.95))
            c &= cab_pane_filter(win, X, Y, floor)
            sel |= c
        for sgn in spec.get("_gangway_ends", ()):
            sel |= (np.abs(Z - sgn * L / 2) < 0.12) & (np.abs(X) < 0.24) & (Y > floor + 1.05) & (Y < floor + 1.7)
        for zd in spec.get("hood_doors", []):
            sel |= (np.abs(X) > hw - 0.05) & (np.abs(Z - zd) < 0.22) & (Y > 2.95) & (Y < 3.45)
        sel &= ~cab_pillars(win, X, Y, Z, L)
        return sel
    return fn

def make_paint(spec, L, W, H, floor):
    """塗り分け: 地色・帯・窓・扉・屋根・前面窓。すべて3D座標の関数"""
    hw = W / 2
    liv = spec["livery"]
    win = spec.get("windows", {})
    def fn(X, Y, Z, cur):
        out = np.tile(rgb(liv["base"]), (len(X), 1))
        if liv.get("nose_light") and spec.get("front") == "sk0":   # 鼻の先端の丸い光前頭(正面から見て円形)
            ly, lr, lc = liv["nose_light"]
            d = L / 2 - Z
            out[(d < 0.45) & (np.hypot(X, Y - ly) < lr)] = rgb(lc)
        for band in liv.get("bands", []):
            y0, y1, c = band[:3]
            sel = (Y >= y0) & (Y < y1)
            if len(band) > 3 and spec.get("front") in ("sk0", "e5", "bonnet"):
                zcut = L / 2 - band[3]                    # 先頭車では、この帯は先頭から band[3] の所で終わる
                if len(band) > 4 and band[4]:             # 帯の先端を丸める(半円)
                    rr = (y1 - y0) / 2
                    sel &= (Z < zcut - rr) | (np.hypot(Z - (zcut - rr), Y - (y0 + y1) / 2) < rr)
                else:
                    sel &= Z < zcut
                sel &= np.abs(X) > hw * 0.6               # 側面だけ(妻面・先端の正面には回さない)
            out[sel] = rgb(c)
        if spec.get("front") == "bonnet" and liv.get("whisker"):
            # 485系(ボンネット。写真による): 正面の左右の角の灯具(赤い枠)から側面へ延びる4本の赤い帯、灯具の上の赤い「ひげ」、
            # ボンネットの継ぎ目、上面前端の金色の特急シンボル(V字の翼)、正面中央の愛称表示器(形のみ。中は白)
            red = rgb(liv["whisker"])
            d = L / 2 - Z
            ax = np.abs(X)
            front = d < 0.4
            # 側面へ延びる帯(灯具の高さ。後ろの端は斜め)
            for k in range(4):
                y0 = 1.47 + k * 0.18
                sel = (Y > y0) & (Y < y0 + 0.13) & (d < 1.75 - (Y - 1.47) * 0.35) & ((ax > hw * 0.82) | (front & (ax > 1.0 * hw / 1.45)))
                out[sel] = red
            # 灯具の赤い枠
            box = front & (ax > 1.07 * hw / 1.45 - 0.005) & (Y > 1.45) & (Y < 2.19)
            out[box] = red
            # ひげ: 灯具の上から内側へ細くなる三日月形
            u = np.clip((ax - 0.42) / 0.66, 0, 1)
            yc = 2.27 + 0.13 * u ** 1.6
            th = 0.015 + 0.075 * u
            out[front & (ax > 0.42) & (ax < 1.08) & (np.abs(Y - yc) < th)] = red
            # ボンネットの継ぎ目(正面の上の丸みの下から、側面を前面窓の下へ向かって上がる線)
            ys = 2.47 + 0.55 * np.clip((d - 0.3) / 2.2, 0, 1) ** 1.6
            out[(d < 2.5) & (np.abs(Y - ys) < 0.022) & (Y > 2.3)] = rgb((95, 85, 70))
            # 特急シンボル(V字の翼): ボンネット上面の前端
            g = (Y > 2.6) & (Y < 2.86) & (d < 0.6) & (ax < 0.34) & (Y > 2.84 - 0.2 * (1 - ax / 0.34)) & ~((Y > 2.8) & (ax < 0.04))
            out[g] = rgb((205, 165, 60))
            # 愛称表示器: 角の丸い枠(金属)と白い面
            hb_out = front & rounded_rect(X, Y, 0.0, 1.12, 1.66, 2.21, 0.1)
            hb_in = front & rounded_rect(X, Y, 0.0, 1.04, 1.70, 2.17, 0.07)
            out[hb_out] = rgb((120, 120, 124))
            out[hb_in] = rgb((246, 246, 244))
        if spec.get("builder") == "hood":
            # 凸形の機関車(写真): 機関部の箱の上面は灰色、端の面の上部に灰色の帯(前照灯の高さ)と番号板、
            # 側面にはよろい戸(放熱器・機関室の通風口)。運転室の前後の低い箱の側面にも通風口
            cz, cl = spec["cab_z"], spec["cab_len"]
            in_cab = np.abs(Z - cz) < cl / 2 + 0.2
            grey, dark = rgb(liv.get("roof", (150, 150, 150))), rgb((70, 62, 58))
            out[~in_cab & (Y > 2.98) & (Y < 3.3)] = grey
            for he in spec.get("_hood_ends", []):
                sg = 1 if he > cz else -1
                face = (np.abs(Z - he) < 0.08) & (np.abs(X) < W * 0.4 + 0.01)
                out[face & (Y > 2.58)] = grey
                out[face & (Y > 2.5) & (Y <= 2.58)] = rgb((235, 235, 235))                            # その下の白線
                out[face & (np.abs(X) < 0.36) & (Y > 2.68) & (Y < 2.9)] = rgb((236, 236, 232))       # 番号板
                # 側面のよろい戸: 端寄りの大きな通風口と、等間隔の小さな通風口
                for k in range(6):
                    zc_ = he - sg * (0.45 + k * 0.9)
                    if np.sign(zc_ - cz) != sg or abs(zc_ - cz) < cl / 2 + 0.5:
                        continue
                    lou = (np.abs(X) > W * 0.4 - 0.02) & (np.abs(Z - zc_) < 0.3) & (Y > 2.25) & (Y < 2.75)
                    out[lou & ((np.floor((Y - 2.25) / 0.05) % 2) == 0)] = dark
                big = (np.abs(X) > W * 0.4 - 0.02) & (np.abs(Z - (he - sg * 0.5)) < 0.32) & (Y > 1.6) & (Y < 2.15)
                out[big] = dark
            for zd in spec.get("hood_doors", []):           # 運転室の側面の乗務員扉(デッキの高さから、窓付き)
                sel = (np.abs(X) > hw - 0.05) & (np.abs(Z - zd) < 0.33) & (Y > 1.55) & (Y < 3.58)
                edge = sel & ((np.abs(np.abs(Z - zd) - 0.33) < 0.04) | (Y > 3.54) | (Y < 1.59))
                out[sel] = out[sel] * 0.93
                out[edge] = rgb((55, 55, 58))
                out[sel & (np.abs(Z - zd) < 0.22) & (Y > 2.95) & (Y < 3.45)] = rgb(win.get("glass", (40, 50, 60)))
                out[sel & (np.abs(Z - (zd - 0.2)) < 0.03) & (np.abs(Y - 2.5) < 0.04)] = rgb((170, 170, 175))
            for z0, z1 in spec.get("cab_boxes", []):
                zc_ = (z0 + z1) / 2
                out[(np.abs(X) > hw - 0.08) & (np.abs(Z - zc_) < abs(z1 - z0) / 2 - 0.25) & (Y > 1.57) & (Y < 1.73)] = dark
        for key, sgn in (("front", 1), ("rear", -1)):
            dd_ = L / 2 - sgn * Z
            ax = np.abs(X)
            face = dd_ < 0.12
            if spec.get(key) == "ef81":                   # EF81(写真): 窓の下の継ぎ目、前面の銀の帯(中央は番号板で切れる)
                out[face & (np.abs(Y - 2.71) < 0.012) & (ax < hw - 0.1)] = rgb((60, 30, 34))
                band = face & (Y > 2.39) & (Y < 2.53)
                out[band & (ax > 0.43) & (ax < hw - 0.04)] = rgb((205, 207, 210))
                out[band & ((np.floor((X + 0.4) / 0.065) % 2) == 0) & (np.abs(Y - 2.46) < 0.045) & (ax < 0.36)] = rgb((205, 207, 210))
            if spec.get(key) == "ef65pf":                 # EF65 PF(写真): 前面はクリーム色(下端の帯は青)、運転室の側面も扉の前までクリーム色。
                cream = rgb(liv.get("nose_color", (232, 222, 180)))  # 中央の貫通扉の輪郭、扉の取っ手、番号板
                out[face & (Y > 1.8)] = cream
                out[face & (Y <= 1.8)] = rgb(liv["base"])                     # 前面の下端の帯(側面の細い帯は前面に回さない)
                out[(dd_ < 1.2) & (ax > hw * 0.8) & (Y > 1.8)] = cream          # 運転室の側面(乗務員扉の前まで)
                door = face & (Y > 1.85) & (Y < 3.52) & ((np.abs(ax - 0.36) < 0.015) | ((ax < 0.36) & (np.abs(Y - 3.52) < 0.015)))
                out[door] = rgb((70, 70, 72))
                out[face & (np.abs(Y - 2.25) < 0.02) & (np.abs(X - 0.26) < 0.02)] = rgb((150, 150, 155))
                out[face & (np.abs(X - 0.85) < 0.25) & (np.abs(Y - 2.62) < 0.07)] = rgb((150, 40, 40))
                out[face & (np.abs(X - 0.85) < 0.21) & (np.abs(Y - 2.62) < 0.035) & ((np.floor((X - 0.64) / 0.06) % 2) == 0)] = rgb((235, 235, 235))
            if spec.get(key) == "kuno" and spec.get("roof_hood"):   # DF200(写真): 前面窓のまわりは黒、その下に前照灯の高さの明るい灰色の帯、
                lg = rgb((188, 190, 194))                              # 前面窓の上(運転室の屋根の前の縁)も明るい灰色
                out[(dd_ < 0.68) & (Y > 2.48) & (Y < 3.64)] = rgb((30, 30, 32))
                out[(dd_ < 0.3) & (Y > 2.1) & (Y <= 2.48)] = lg
                out[(dd_ < 0.8) & (Y >= 3.64) & (Y < 3.86) & (ax < hw * 0.97)] = lg
            if spec.get(key) == "kuno":                   # DF200・EF210: 前照灯・尾灯を囲む黒い長方形
                dd_ = L / 2 - sgn * Z
                out[(dd_ < 0.35) & (np.abs(X) > hw - 0.8) & (np.abs(X) < hw - 0.12) & (Y > floor + 0.45) & (Y < floor + 0.76)] = rgb((25, 25, 27))
        for key, sgn in (("front", 1), ("rear", -1)):
            if spec.get(key) == "ef66":                   # EF66(写真): 鼻の上面と正面上部のクリーム色(左右へ細くなる)、中央の金属の横縞、
                dd_ = L / 2 - sgn * Z                     # 灯具の黒い枠、番号板
                ax = np.abs(X)
                cream = rgb(liv.get("nose_color", (225, 215, 170)))
                up = (Y > np.where(ax < 0.84, 2.03, 2.3)) & (Y < EF66_YA + 0.005) & (dd_ < 0.6 + 0.6 * np.clip(Y - 2.3, 0, 1))
                out[up] = cream                                                     # 四角錐の頂点から下の面(左右へ回り込む)
                notch = (dd_ < 0.35) & (Y > 2.55) & (Y < EF66_YA + 0.005) & (ax < 0.52 - (EF66_YA - Y) * 0.6)  # 車番プレートの窪み
                out[notch] = rgb((88, 92, 98))
                out[(dd_ < 0.75) & (ax < 0.84) & (Y > 1.55) & (Y < 1.72)] = cream   # 灯具の間・横縞の下
                out[(np.abs(X) > hw * 0.8) & (dd_ > 0.5) & (Y > 1.86) & (Y < 1.93)] = cream   # 側面の細い帯(正面の横縞の高さ)
                gr = (dd_ < 0.75) & (ax < 0.84) & (Y > 1.75) & (Y < 2.03)
                out[gr] = rgb((60, 60, 64))
                out[gr & ((np.floor((Y - 1.75) / 0.055) % 2) == 0)] = rgb((200, 200, 205))
                out[(dd_ < 0.36) & (np.abs(ax - 1.05) < 0.17) & (Y > 1.47) & (Y < 2.28)] = rgb((28, 28, 30))
                plate = (dd_ < 0.1) & (ax < 0.42) & (np.abs(Y - 2.72) < 0.11)
                out[plate] = rgb((70, 68, 60))
                out[plate & (ax < 0.36) & (np.abs(Y - 2.72) < 0.065)] = rgb((200, 200, 200))
        if spec.get("side_panel"):                    # DF200(写真): 側面中央の灰色の部分。前後の端は斜め(下ほど運転室側へ出る)。
            d0, d1, y0p, y1p, col = spec["side_panel"]  # 上の縁(屋根の覆いの下)は車体の色の細い帯が残る
            dmin = np.minimum(L / 2 - Z, L / 2 + Z)
            dlim = d0 + (d1 - d0) * np.clip((Y - y0p) / (y1p - y0p), 0, 1)
            out[(np.abs(X) > hw * 0.8) & (Y > y0p) & (Y < y1p) & (dmin > dlim)] = rgb(col)
        for z0, z1, y0, y1 in spec.get("side_louvers", []):   # 側面のよろい戸(機器室の通風口)
            sl = (np.abs(X) > hw * 0.8) & (Z > z0) & (Z < z1) & (Y > y0) & (Y < y1)
            out[sl] = out[sl] * 0.75
            out[sl & ((np.floor((Y - y0) / 0.06) % 2) == 0)] = rgb((50, 50, 54))
        side = np.abs(X) > hw * 0.72
        # 窓(側面): 区間ごとに等間隔
        if win.get("win_frame"):                      # 窓枠(窓の周りの細い枠)
            out[window_rows(win, Z, Y, side, grow=win["win_frame"])] = rgb(win.get("frame_color", (150, 152, 158)))
        out[window_rows(win, Z, Y, side)] = rgb(win.get("glass", (40, 50, 60)))
        for zc, yc, rr, mc in win.get("marks", []):     # 側面の小さなマーク(グリーン車マークなど)
            out[side & (np.hypot(Z - zc, Y - yc) < rr)] = rgb(mc)
        for zc, dw in win.get("doors", []):
            sel = side & (np.abs(Z - zc) < dw / 2) & (Y > floor + 0.05) & (Y < floor + 1.95)
            edge = sel & ((np.abs(np.abs(Z - zc) - dw / 2) < 0.05) | (Y > floor + 1.9))
            out[sel] = out[sel] * 0.92
            out[edge] = rgb((60, 60, 64))
            if win.get("door_glass", True):
                dwin = sel & (Y > floor + 0.95) & (Y < floor + 1.7) & (np.abs(Z - zc) < dw / 2 - 0.12)
                out[dwin] = rgb(win.get("glass", (40, 50, 60)))
        # 乗務員扉(窓付き)と運転台の側窓
        for zc, dw in win.get("crew_doors", []):
            sel = side & (np.abs(Z - zc) < dw / 2) & (Y > floor + 0.05) & (Y < floor + 1.9)
            edge = sel & ((np.abs(np.abs(Z - zc) - dw / 2) < 0.04) | (Y > floor + 1.86))
            out[sel] = out[sel] * 0.93
            out[edge] = rgb((60, 60, 64))
            out[sel & (np.abs(Z - zc) < dw / 2 - 0.1) & (Y > floor + 1.0) & (Y < floor + 1.6)] = rgb(win.get("glass", (40, 50, 60)))
            out[sel & (np.abs(Z - (zc - 0.2)) < 0.03) & (np.abs(Y - (floor + 0.95)) < 0.03)] = rgb((170, 170, 175))
        for z0, z1, y0, y1 in win.get("cab_side", []):
            out[side & (Z > z0) & (Z < z1) & (Y > y0) & (Y < y1)] = rgb(win.get("glass", (40, 50, 60)))
        if win.get("sk0_side"):
            if win.get("win_frame"):
                out[sk0_cab_side(win, X, Y, Z, L, grow=win["win_frame"])] = rgb(win.get("frame_color", (150, 152, 158)))
            out[sk0_cab_side(win, X, Y, Z, L)] = rgb(win.get("glass", (40, 50, 60)))
        # 前面窓(運転台側の端)
        for cab in win.get("cabs", []):
            zf, sgn = cab[0], cab[1]
            depth = cab[2] if len(cab) > 2 else 0.6    # 前面窓として塗る範囲(運転台の面からの前後方向の深さ)
            d = (Z - zf) * sgn
            sel = (np.abs(X) < hw * win.get("cab_xmax", 0.9)) & (d > -0.5) & (d < depth) & (Y > win.get("cab_y0", floor + 1.15)) & (Y < win.get("cab_y1", floor + 1.95))
            out[sel & cab_pane_filter(win, X, Y, floor)] = rgb(win.get("glass", (40, 50, 60)))
            if win.get("front_style") == "103":           # 窓枠・柱(黒いゴム)と、表示なしの表示器
                xin = hw * win.get("cab_xmax", 0.9)
                y0, y1 = win.get("cab_y0", floor + 1.15), win.get("cab_y1", floor + 1.95)
                zone = (d > -0.5) & (d < depth)
                frame = zone & (np.abs(X) < xin + 0.05) & (Y > y0 - 0.05) & (Y < y1 + 0.05) & \
                        ((np.abs(X) > xin - 0.03) | (Y < y0 + 0.03) | (Y > y1 - 0.03) | (np.abs(np.abs(X) - PILLAR103) < 0.04))
                out[frame] = rgb((40, 40, 42))
                sg = zone & (np.abs(X) < xin) & (Y > y0) & (Y < y1) & front103_signs(win, X, Y)
                out[sg] = rgb((18, 18, 20))
            if win.get("front_style") == "kantsu":        # 貫通扉の輪郭(床の高さから窓の上まで)
                face = (d > -0.5) & (d < depth) & (Y > floor + 0.1) & (Y < win.get("cab_y1", floor + 1.95) + 0.05)
                out[face & (np.abs(np.abs(X) - 0.42) < 0.025)] = rgb((50, 50, 54))
                out[face & (np.abs(X) < 0.42) & (np.abs(Y - (win.get("cab_y1", floor + 1.95) + 0.05)) < 0.025)] = rgb((50, 50, 54))
        if win.get("pillars"):
            # 運転室の窓の枠(縁取り)と柱: 前面窓と側面へ回り込む窓をまとめて縁取り、柱で何枚かに仕切る
            fc = rgb(win.get("frame_color", (55, 55, 58)))
            def front_glass(grow):
                g = np.zeros(len(X), bool)
                for cab in win.get("cabs", []):
                    zf, sgn = cab[0], cab[1]
                    depth = cab[2] if len(cab) > 2 else 0.6
                    dz = (Z - zf) * sgn
                    g |= (np.abs(X) < hw * win.get("cab_xmax", 0.9) + grow) & (dz > -0.5) & (dz < depth + grow) & \
                         (Y > win.get("cab_y0", floor + 1.15) - grow) & (Y < win.get("cab_y1", floor + 1.95) + grow)
                return g
            gl = front_glass(0.0) | sk0_cab_side(win, X, Y, Z, L)
            gg = front_glass(0.045) | sk0_cab_side(win, X, Y, Z, L, grow=0.045)
            out[gg & ~gl] = fc
            out[gg & cab_pillars(win, X, Y, Z, L)] = fc
        # 貫通路のある車端: 妻面の貫通扉(窓付き)を描く(幌の枠の内側に見える)
        for sgn in spec.get("_gangway_ends", ()):
            ze = sgn * L / 2
            face = (np.abs(Z - ze) < 0.12) & (np.abs(X) < hw * 0.95)
            door = face & (np.abs(X) < 0.36) & (Y > floor + 0.02) & (Y < floor + 1.92)
            edge = door & ((np.abs(X) > 0.32) | (Y > floor + 1.88))
            out[door] = out[door] * 0.9
            dwin = door & (np.abs(X) < 0.24) & (Y > floor + 1.05) & (Y < floor + 1.7)
            out[dwin] = rgb(win.get("glass", (40, 50, 60)))
            out[edge] = rgb((60, 60, 64))
            knob = door & (np.abs(X - 0.26) < 0.03) & (np.abs(Y - (floor + 0.95)) < 0.03)
            out[knob] = rgb((170, 170, 175))
            # 妻面の手すり・ジャンパ栓の受け(テクスチャのみ)
            rail = face & (np.abs(np.abs(X) - 0.62) < 0.02) & (Y > floor + 0.3) & (Y < floor + 1.6)
            out[rail] = rgb((150, 150, 155))
            jump = face & (np.abs(np.abs(X) - 1.0) < 0.08) & (np.abs(Y - (floor - 0.0)) < 0.08)
            out[jump] = rgb((50, 50, 54))
        if "roof" in liv:
            rsel = Y > H - liv.get("roof_h", 0.35)
            for key, sgn in (("front", 1), ("rear", -1)):
                if spec.get(key) == "bonnet":             # 485系: 1段高い運転室の側面は車体の色(屋根の色は運転室の屋根の上だけ)
                    dd_ = L / 2 - sgn * Z
                    rsel &= ~((dd_ < 4.85) & (Y < H + BONNET_CAB_UP - 0.12))
                if spec.get(key) == "kuno" and spec.get("roof_hood"):   # DF200: 前面窓の上の明るい灰色の帯・窓のまわりの黒は屋根の色にしない
                    dd_ = L / 2 - sgn * Z
                    rsel &= ~((dd_ < 0.8) & (Y < 3.86) & (np.abs(X) < hw * 0.97))
                if spec.get(key) == "kuno" and not spec.get("roof_hood") and (key == "front" or spec.get("windows", {}).get("cabs", [None, None])[1:]):
                    dd_ = L / 2 - sgn * Z                 # DF200・EF210: 運転室の屋根(ひさし)は全体が車体の色
                    rsel &= ~(dd_ < 1.78)

            out[rsel] = rgb(liv["roof"])
        # 前照灯・尾灯
        for lx, ly, z0, z1, r, kind in spec.get("_lamps", []):
            d = np.hypot(X - lx, Y - ly)
            zone = (Z >= z0) & (Z <= z1)
            out[zone & (d < r)] = rgb((150, 152, 156))                  # 灯具の縁(金属の枠)
            out[zone & (d < r * 0.86)] = rgb((50, 50, 54))
            if kind == "tail":
                out[zone & (d < r * 0.7)] = rgb((200, 25, 30))
            elif kind == "headtail":                                    # 上が前照灯、下が尾灯
                out[zone & (d < r * 0.72)] = rgb((250, 236, 170))
                out[zone & (d < r * 0.72) & (Y < ly - r * 0.15)] = rgb((200, 25, 30))
            else:
                out[zone & (d < r * 0.7)] = rgb((250, 236, 170))
        return out, None
    return fn

# ---------------------------------------------------------------- 車種ごとの組み立て
# ---------------------------------------------------------------- 車内(簡易)
GLASS_ALPHA = 80         # 窓ガラスの不透明度(0〜255)。前提MODは網目状に間引いて描くので、車内が透けて見える
SEAT_PAN = 0.45          # 座面の高さ(床から)
RIDER_HIP = 0.49         # 前提MODは乗員の足元を座席位置に置き、腰は足元から約0.49m上(0.7倍で描くため)

def rider_y(floor_y):
    """座面に腰が載る座席位置(Y)"""
    return floor_y + SEAT_PAN - RIDER_HIP

def lining(m, prof, floor, n=20, ringfn=None):
    """内張り(車内の壁・天井・床)。外板の断面から内側へ縮めた断面のロフトを裏返して、内側から見える面にする。
    前半分・後ろ半分は外板と同じく別シェルにする。先頭部の細い所(人が入れない所)は省く。返り値は車内の前後端のZ"""
    inner, src = [], []
    for z, hw, t, b, p in prof:
        # 内張りは外板のすぐ内側(3cm)に置く。隙間が広いと、窓の縁を斜めに覗いた視線が外板と内張りの間を抜けて外が見える
        hwi, ti, bi = hw - 0.03, t - 0.03, max(b + 0.04, floor)
        # 先頭部の細い所まで内張りで覆う(前面窓から覗いたとき、空洞の向こうに外が見えないように)
        if hwi > 0.15 and ti - bi > 0.2 and (ringfn is None or ringfn(z, hw, t, b, bi) is not None):
            inner.append((z, hwi, ti, bi, p))
            src.append((hw, t, b, p))
    if len(inner) < 2:
        return None
    zmin, zmax = inner[0][0] + 0.1, inner[-1][0] - 0.1
    inner[0] = (zmin,) + inner[0][1:]
    inner[-1] = (zmax,) + inner[-1][1:]
    # 1つのシェルにする(前後に分けると継ぎ目のふたが車内の仕切りになる)。前半分の区間は "lining"、後ろ半分は "liner" の
    # タグにして、外板とも互いとも別のテクスチャ領域に割り当てる(glass_tags)。内張りは外板の中に埋まっているため、
    # 同じタグだと外板(屋根・窓)や前後の妻面とテクセルを共有してしまう
    # 断面は外板の断面を、その中心へ向けて3cm縮め、床より下は床の高さで平らにする。外板と同じ形を縮めるので、
    # 下半分が丸い断面(0系の先頭部など)でも内張りが外板の外へはみ出さない
    rings = []
    for (z, _, _, fl_, _), (hw, t, b, p) in zip(inner, src):
        if ringfn is not None:
            rings.append(ringfn(z, hw, t, b, fl_))
            continue
        r = np.array(sring(0, (t + b) / 2, z, hw, (t - b) / 2, n=n, p=p), float)
        c, ht = (t + b) / 2, (t - b) / 2
        r[:, 0] *= (hw - 0.03) / hw
        r[:, 1] = np.maximum(c + (r[:, 1] - c) * (ht - 0.03) / ht, fl_)
        rings.append(r)
    tags = ["lining" if (inner[i][0] + inner[i + 1][0]) / 2 >= 0.0 else "liner" for i in range(len(inner) - 1)]
    sh = mk.loft(rings, tags=tags)
    split_twisted(sh)        # 床で平らにした所の近くで、ねじれた四角形の面を三角形2枚に分ける
    sh.reverse()
    m.add("body", sh)
    return zmin, zmax, inner

def split_twisted(sh, tol=0.004):
    """シェルの四角形の面のうち、平面からのずれが tol を超えるものを三角形2枚に分ける"""
    faces, tags = [], []
    for f, t in zip(sh.faces, sh.tags):
        if len(f) == 4:
            P = np.array([sh.verts[i] for i in f])
            nrm = np.cross(P[2] - P[0], P[3] - P[1])
            nn = np.linalg.norm(nrm)
            if nn > 1e-9 and abs(np.dot(P[1] - P[0], nrm / nn)) > tol:
                faces += [(f[0], f[1], f[2]), (f[0], f[2], f[3])]; tags += [t, t]
                continue
        faces.append(f); tags.append(t)
    sh.faces, sh.tags = faces, tags

def inner_at(inner, z):
    """内張りの断面(半幅・天井・床)を z で補間する"""
    zs = [r[0] for r in inner]
    if z <= zs[0]:
        r = inner[0]; return r[1], r[2], r[3]
    if z >= zs[-1]:
        r = inner[-1]; return r[1], r[2], r[3]
    for a, b in zip(inner, inner[1:]):
        if a[0] <= z <= b[0]:
            t = (z - a[0]) / max(1e-6, b[0] - a[0])
            return tuple(a[i] + (b[i] - a[i]) * t for i in (1, 2, 3))

def section_top(p, hw, t, b, x):
    """断面の上縁(屋根・天井)の、中心から x の所の高さ"""
    u = min(abs(x) / max(hw, 1e-6), 1.0)
    if isinstance(p, tuple) and p and p[0] == "roof":
        ye, rc, q = roof_section(p, hw, t, b)
        return ye + (t - ye) * (1 - u ** q) ** (1 / q)
    c, ht = (t + b) / 2, (t - b) / 2
    pp = p[0] if isinstance(p, tuple) else p
    return c + ht * (1 - u ** pp) ** (1 / pp)

def ceil_y(inner, z, x):
    """内張りの天井の高さ(中心から x の所)。内張りは外板の断面を中心へ3cm縮めた形なので、外板より約3cm低い"""
    zs = [r[0] for r in inner]
    k = int(np.clip(np.searchsorted(zs, z), 0, len(inner) - 1))
    hw, t, b = inner_at(inner, z)
    return section_top(inner[k][4], hw + 0.03, t + 0.03, b, x) - 0.03

def wall_x(inner, z, y):
    """内張りの壁の、高さ y での半幅"""
    zs = [r[0] for r in inner]
    k = int(np.clip(np.searchsorted(zs, z), 0, len(inner) - 1))
    hw, t, b = inner_at(inner, z)
    return section_x(inner[k][4], hw, t, b, y)

def seat_block(m, x0, x1, z, floor_y, facing=1, depth=0.5, back=0.75):
    """座席1組(座面と背ずり)。facing=+1で前向き(背ずりが後ろ)"""
    xc, wd = (x0 + x1) / 2, abs(x1 - x0)
    m.add("body", mk.box(xc, floor_y + SEAT_PAN / 2, z, wd, SEAT_PAN, depth, tag="seat"))
    m.add("body", mk.box(xc, floor_y + SEAT_PAN + back / 2 - 0.05, z - facing * (depth / 2 - 0.05), wd, back, 0.1, tag="seat"))

def interior_layout(spec):
    i = spec["id"]
    if spec["builder"] != "car" or spec.get("two_axle") and "coach" not in i:
        return "none"
    if any(k in i for k in ("_103_", "_e231_")):
        return "long"
    if "ohane" in i:
        return "sleeper"
    if not spec.get("windows", {}).get("rows") and not spec.get("windows", {}).get("cabs"):
        return "none"                    # 窓の無い貨車
    if i.split("_")[1].startswith(("ef", "eh", "df")):
        return "cab_only"
    if spec.get("kind") == "car" and spec.get("windows", {}).get("rows"):
        return "cross"
    return "none"

# 運転台の大きさ(車端から仕切りまで)と床の高さ(客室の床から)。流線形の先頭は運転台が高い
CAB = {"box": (2.0, 0.35), "ef81": (2.0, 0.35), "ef65pf": (2.0, 0.35), "kuno": (2.2, 0.35), "ef58": (2.2, 0.35), "ef66": (2.6, 0.75), "103": (1.9, 0.0), "flat": (1.9, 0.0), "slope": (1.9, 0.0), "round": (2.1, 0.0), "bonnet": (4.6, 1.2), "sk0": (5.1, 0.94), "e5": (11.3, 0.65)}

def lamp_layout(spec, cabs, floor):
    """前照灯・尾灯(テクスチャに描く)。(x, y, z0, z1, 半径, 種類) 種類: head / tail / headtail(1つの灯具に前照灯と尾灯)"""
    hw = spec["W"] / 2
    lamps = []
    for c in cabs:
        sgn, kind, zend = c["sgn"], c["kind"], c["zend"]
        win_ = lambda d0, d1: tuple(sorted((zend - sgn * d0, zend - sgn * d1)))
        if kind == "sk0":                             # 0系(図面): 鼻の左右、先端から約0.74m・中心から約0.95m・レール面上約1.74mの灯具
            a, b = win_(0.4, 1.1)
            for sx in (1, -1):
                lamps.append((sx * 0.95, 1.74, a, b, 0.15, "headtail"))
        elif kind == "e5":                            # E5系: 鼻の側面下寄りの細長い前照灯
            a, b = win_(2.4, 3.6)
            for sx in (1, -1):
                lamps.append((sx * 0.9, 1.68, a, b, 0.09, "head"))
        elif kind == "103":                           # 103系: 屋根の前端の中央のケースに前照灯、窓の下の左右に尾灯
            lamps.append((0.0, spec["H"] - 0.2, *win_(-0.06, -0.04), 0.13, "head"))
            for sx in (1, -1):
                lamps.append((sx * (hw - 0.3), floor + 0.6, *win_(-0.05, 0.06), 0.08, "tail"))
        elif kind == "bonnet":                        # 485系: 正面の左右の角の灯具(上が前照灯、下が尾灯)。屋根の前照灯は別の部品
            a, b = win_(-0.05, 0.3)
            for sx in (1, -1):
                lamps.append((sx * 1.26 * hw / 1.45, 1.96, a, b, 0.13, "head"))
                lamps.append((sx * 1.26 * hw / 1.45, 1.63, a, b, 0.1, "tail"))
            lamps.append((0.0, spec["H"] + BONNET_CAB_UP + 0.1, *win_(2.7, 2.82), 0.12, "head"))   # 運転室の屋根の前照灯
        elif kind == "ef58":                          # EF58: 前照灯は前面上部の円筒(部品)。尾灯は前面下部の左右
            a, b = win_(-0.05, 0.5)
            for sx in (1, -1):
                lamps.append((sx * (hw - 0.45), floor + 0.45, a, b, 0.08, "tail"))
        elif kind in ("ef81", "ef65pf"):              # EF81・EF65 PF: 前照灯は屋根の前の縁の左右(部品)。尾灯は前面下部の左右
            a, b = win_(-0.05, 0.08)
            for sx in (1, -1):
                lamps.append((sx * (1.02 if kind == "ef81" else 1.05), 1.81 if kind == "ef81" else 1.95, a, b, 0.085, "tail"))
        elif kind == "ef66":                          # EF66: 鼻の下部の左右に飛び出した灯具(上が前照灯、下が尾灯)
            a, b = win_(0.26, 0.36)
            for sx in (1, -1):
                lamps.append((sx * 1.05, 2.07, a, b, 0.11, "head"))
                lamps.append((sx * 1.05, 1.68, a, b, 0.1, "tail"))
        else:
            a, b = win_(-0.05, 0.9 if kind == "round" else 0.6)
            style = spec.get("lamp_style", "low")
            H = spec["H"]
            if style == "roof":                       # 気動車・旧形: 屋根の前に前照灯1灯、窓の下の左右に尾灯
                lamps.append((0.0, H - 0.32, *win_(-0.05, 1.2), 0.15, "head"))
                for sx in (1, -1):
                    lamps.append((sx * (hw - 0.35), floor + 0.55, a, b, 0.08, "tail"))
            elif style == "top2":                     # 電気機関車: 窓の上に前照灯2灯、窓の下に尾灯
                for sx in (1, -1):
                    lamps.append((sx * 0.45, H - 0.45, *win_(-0.05, 1.0), 0.12, "head"))
                    lamps.append((sx * (hw - 0.4), floor + 0.5, a, b, 0.08, "tail"))
            else:                                     # 平らな前面: 窓の下の左右に前照灯、その外側に尾灯
                for sx in (1, -1):
                    lamps.append((sx * (hw - 0.6), floor + 0.6, a, b, 0.12, "head"))
                    lamps.append((sx * (hw - 0.3), floor + 0.6, a, b, 0.08, "tail"))
    return lamps

def cab_layout(spec, floor):
    """運転台の配置を決め、窓の設定に反映する: 客室の側窓は運転台の手前で止め、運転台の後ろの側面に乗務員扉、
    流線形の先頭は乗務員扉と前面窓の間に運転台の側窓を描く。spec['_cabs'] に各運転台の寸法を入れる"""
    win = spec.get("windows")
    if not win or not win.get("cabs") or spec["builder"] != "car":
        spec["_cabs"] = []
        return
    win = dict(win)
    L = spec["L"]
    cabs = []
    rows = [list(r) for r in win.get("rows", [])]
    crew, side = [], []
    for c in win["cabs"]:
        zf, sgn = c[0], c[1]
        kind = spec.get("front" if sgn > 0 else "rear", "flat")
        clen, plat = CAB.get(kind, (1.9, 0.0))
        zend = sgn * L / 2
        zcb = zend - sgn * clen                       # 客室との仕切り
        zr = zf - sgn * 0.5                           # 前面窓の後ろの端
        fy = floor + plat
        drv = zr - sgn * (0.2 if kind == "sk0" else 0.3) if kind in ("bonnet", "sk0", "e5") else zend - sgn * {"ef66": 1.45, "kuno": 1.1}.get(kind, 1.0)
        cabs.append(dict(sgn=sgn, kind=kind, zend=zend, zcb=zcb, zr=zr, fy=fy, drv=drv,
                         gy0=win.get("cab_y0", floor + 1.15)))
        for r in rows:                                # 客室の側窓は仕切りの手前まで
            if sgn > 0:
                r[1] = min(r[1], zcb - 0.25)
            else:
                r[0] = max(r[0], zcb + 0.25)
        if spec.get("kind") == "car" and spec["id"].split("_")[1].startswith(("ef", "eh", "df")):
            crew.append((zcb + sgn * 0.45, 0.65))     # 機関車の乗務員扉(運転室の後ろの端の側面)
        if spec.get("kind") == "car" and not spec["id"].split("_")[1].startswith(("ef", "eh", "df")):
            crew.append((zcb + sgn * 0.5, 0.6))       # 乗務員扉(運転台の後ろの端)
            if kind == "e5":                          # 運転台の側窓(乗務員扉と前面窓の間。0系・485系は前面窓が側面へ回り込む: sk0_side)
                a, b = sorted((zcb + sgn * 0.95, zr - sgn * 0.15))
                side.append((a, b, fy + 0.65, fy + 1.2))
    win["rows"] = [tuple(r) for r in rows if r[1] - r[0] > 0.5]
    win["crew_doors"] = crew
    win["cab_side"] = side
    if any(c["kind"] == "e5" for c in cabs):
        win["cab_xmax"] = 0.62                        # 前面窓は上面・正面だけ(側面へ回り込ませない)
    if any(c["kind"] == "bonnet" for c in cabs):
        win["cab_xmax"] = 0.78                        # 485系: 前面窓は側面の窓(sk0_side)へつながる
    if any(c["kind"] == "sk0" for c in cabs):
        win["cab_xmax"] = 1.05                        # 0系: 細長い前面窓が側面へ回り込む
    spec["_lamps"] = lamp_layout(spec, cabs, floor)
    spec["windows"] = win
    spec["_cabs"] = cabs

def build_interior(m, spec, prof, floor):
    """車内: 内張り・座席・運転台。乗客の座席位置を spec['_seat_pos'] に、運転士の位置を spec['_driver_pos'] に入れる"""
    L, W = spec["L"], spec["W"]
    layout = interior_layout(spec)
    spec["_seat_pos"] = []
    if layout == "none":
        return
    ef66 = spec.get("front") == "ef66"
    ext = lining(m, prof, floor, ringfn=(lambda z, hw_, t, b, fl_: ef66_ring(z, hw_, t, b, L, inset=0.03, floor=fl_)) if ef66 else None)
    if ext is None:
        return
    zin0, zin1, inner = ext
    if ef66:      # 内装の配置には、四角錐の中に収まる断面を使う
        inner = [(z, *ef66_inscribed(hwi, ti, bi, L / 2 - abs(z)), pi) if L / 2 - abs(z) < 1.4 else (z, hwi, ti, bi, pi)
                 for z, hwi, ti, bi, pi in inner]
    spec["_inner"], spec["_prof"] = inner, prof
    hw_full = max(r[1] for r in inner)
    # 車体の幅が一定の区間(先頭部の絞り込みを除く)。座席・荷物棚はこの中だけに置く
    straight = [r[0] for r in inner if r[1] >= hw_full - 0.03]
    zs0, zs1 = min(straight), max(straight)
    # 座席などの外側の端: 床の高さでの内張りの幅まで(下の角が丸い断面では床の高さで細くなるので、はみ出さない)
    hw = min(W / 2 - 0.12, min(wall_x(inner, zq, floor + 0.03) for zq in np.linspace(zs0, zs1, 9)) - 0.04)
    win = spec.get("windows", {})
    doors = [zc for zc, _ in win.get("doors", [])]
    seats = []
    door_gap = 0.6 if spec.get("two_axle") else 0.9     # 明治期の二軸客車は区分室ごとに扉がある
    # 運転台: 前面窓の後ろの端のすぐ後ろに運転士が座り、操作卓は前面窓の下に置く。流線形の先頭は床を上げた台の上
    cab_ranges = []
    for c in spec.get("_cabs", []):
        sgn, fy, zr, zcb, drv = c["sgn"], c["fy"], c["zr"], c["zcb"], c["drv"]
        zc = (zr + sgn * 0.25) if c["kind"] in ("bonnet", "sk0", "e5") else drv + sgn * 0.6
        if c["kind"] not in ("bonnet", "sk0", "e5"):
            # 先頭が細くなる形(くの字の鼻など)では、操作卓の前の角が車体の外へはみ出さない所まで下げ、運転士も一緒に下げる
            need = min(0.6 * hw * 1.6, 2 * inner_at(inner, zcb)[0] - 0.15)
            moved = False
            for _ in range(40):
                hf = min(wall_x(inner, zc + sgn * 0.25, y) for y in (fy + 0.05, min(fy + 0.8, c["gy0"] - 0.08)))
                if 2 * hf - 0.1 >= need or abs(zc - zcb) < 0.9:
                    break
                zc -= sgn * 0.05; moved = True
            if moved:
                drv = zc - sgn * 0.6
                c["drv"] = drv
        hwi = inner_at(inner, zc)[0]
        if fy > floor + 0.01:                          # 運転台の床(仕切りから操作卓の下まで)
            ze_ = zc + sgn * 0.3
            hmax = wall_x(inner, zcb, floor + 0.02)
            while abs(ze_ - zcb) > 0.6 and wall_x(inner, ze_, floor + 0.02) < 0.85 * hmax:   # 先頭の細い所(くの字の鼻など)は床を延ばさない
                ze_ -= sgn * 0.05
            z0, z1 = sorted((zcb, ze_))
            # 幅は床の高さでの内張りの幅(下の角が丸い断面では、床の高さで細くなる。そこからはみ出さない)
            pw = 2 * min(wall_x(inner, zq, floor + 0.02) for zq in np.linspace(z0, z1, 6)) - 0.1
            m.add("body", mk.box(0, (floor + fy) / 2, (z0 + z1) / 2, pw, fy - floor, z1 - z0, tag="console"))
        top = min(fy + 0.8, c["gy0"] - 0.08)          # 操作卓の上面は運転士の目(座席位置+約1.15m)より0.3m以上低く、前面窓の下端より下
        cw = max(0.6, min(hw * 1.6, 2 * hwi - 0.15, 2 * min(wall_x(inner, zc + sgn * 0.25, fy + 0.05), wall_x(inner, zc + sgn * 0.25, top)) - 0.12))
        m.add("body", mk.box(0, (fy + top) / 2, zc, cw, top - fy, 0.5, tag="console"))                  # 操作卓
        m.add("body", mk.box(min(0.62, cw / 2 - 0.3), top + 0.02, zc - sgn * 0.12, 0.55, 0.05, 0.3, tag="panel"))  # 計器盤
        if c["kind"] in ("bonnet", "sk0", "e5"):
            # 流線形の先頭: 操作卓の上面を前面窓の前の端まで延ばし、その先(鼻の中の空洞)を塞ぐ。
            # 断面は内張りと同じ形で、上を操作卓の高さで平らに切る。上から前面窓を覗くと卓の上面と前の壁が見える
            prof_o = spec["_prof"]
            z_end = zc - sgn * 0.25
            zz = z_end
            for _ in range(200):
                nz = zz + sgn * 0.1
                hwn, tn, bn = inner_at(inner, nz)
                if inner_at(prof_o, nz)[1] - 0.05 < top or bn > top - 0.1 or hwn < 0.2 or not (inner[0][0] <= nz <= inner[-1][0]):
                    break
                zz = nz
            z_end = zz
            z_start = zc - sgn * 0.25
            if abs(z_end - z_start) > 0.3:
                n_r = max(2, int(abs(z_end - z_start) / 0.25) + 1)
                rings = []
                for zq in sorted(np.linspace(z_start, z_end, n_r)):
                    hwq, tq, bq = inner_at(inner, zq)
                    pq = min(inner, key=lambda r: abs(r[0] - zq))[4]
                    rg = np.array(sring(0, (tq + bq) / 2, zq, hwq - 0.01, (tq - bq) / 2 - 0.01, n=20, p=pq), float)
                    rg[:, 1] = np.clip(rg[:, 1], bq + 0.01, top)
                    rings.append(rg)
                m.add("body", mk.loft(rings, tag="console").triangulated())
                # 卓の上の計器と、前の端の縁取り
                zmid = (z_start + z_end) / 2
                m.add("body", mk.box(0.0, top + 0.02, zmid, 0.5, 0.04, 0.3, tag="panel"))
        seat_block(m, 0.45, 0.95, drv, fy, facing=sgn, depth=0.45, back=0.7)
        # 客室との仕切り(運転室の後ろの壁)
        # 断面は外板の断面を中心へ縮めた形(内張りと同じ作り方で、さらに2cm内側)。床より下は床で平らに
        rr = []
        for zz in (zcb - 0.03, zcb + 0.03):
            ho, to, bo = inner_at(prof, zz)
            po = min(prof, key=lambda r: abs(r[0] - zz))[4]
            rg = np.array(sring(0, (to + bo) / 2, zz, ho, (to - bo) / 2, n=20, p=po), float)
            cc, hh = (to + bo) / 2, (to - bo) / 2
            rg[:, 0] *= (ho - 0.05) / ho
            rg[:, 1] = np.maximum(cc + (rg[:, 1] - cc) * (hh - 0.05) / hh, floor + 0.02)
            rr.append(rg)
        m.add("body", mk.loft(rr, tag="console"))
        cab_ranges.append(tuple(sorted((c["zend"], zcb))))
        if sgn > 0:
            spec["_driver_pos"] = (0.7, rider_y(fy), drv)
    # 客室の前後の端(運転室を除く)。荷物棚・つり革の棒はこの範囲だけに付ける
    pz0, pz1 = zs0, zs1
    for r0, r1 in cab_ranges:
        if (r0 + r1) / 2 > 0:
            pz1 = min(pz1, r0 - 0.3)
        else:
            pz0 = max(pz0, r1 + 0.3)
    def free(z, margin=0.6):
        return all(abs(z - d) > door_gap for d in doors) and all(not (a - margin < z < b + margin) for a, b in cab_ranges) \
            and zs0 + 0.6 < z < zs1 - 0.6
    if layout == "long":
        # ロングシート: 扉と扉の間の側壁沿い
        edges = sorted([zs0 + 0.3] + [d - 0.75 for d in doors if zs0 < d < zs1] + [d + 0.75 for d in doors if zs0 < d < zs1] + [zs1 - 0.3])
        segs = [(edges[k], edges[k + 1]) for k in range(0, len(edges) - 1, 2)]
        for a, b in segs:
            a2, b2 = a, b
            for r0, r1 in cab_ranges:
                if r0 < b2 and a2 < r1:
                    if r0 <= a2: a2 = r1 + 0.3
                    else: b2 = r0 - 0.3
            if b2 - a2 < 1.0:
                continue
            for sg in (1, -1):
                m.add("body", mk.box(sg * (hw - 0.27), floor + SEAT_PAN / 2, (a2 + b2) / 2, 0.5, SEAT_PAN, b2 - a2, tag="seat"))
                m.add("body", mk.box(sg * (hw - 0.04), floor + SEAT_PAN + 0.3, (a2 + b2) / 2, 0.08, 0.6, b2 - a2, tag="seat"))
                n = int((b2 - a2) / 1.3)
                for k in range(n):
                    seats.append((sg * (hw - 0.3), (a2 + 0.65 + k * (b2 - a2 - 1.3) / max(1, n - 1)) if n > 1 else (a2 + b2) / 2))
        # つり革の棒
        for sg in (1, -1):
            xb, yb = sg * (hw - 0.55), floor + 2.0
            m.add("body", mk.box(xb, yb, (pz0 + pz1) / 2, 0.04, 0.04, pz1 - pz0 - 0.4, tag="panel"))
            # 天井からの吊り金具(棒を天井につなぐ。天井の高さは内張りの断面から)
            for zh in np.linspace(pz0 + 1.0, pz1 - 1.0, max(2, int((pz1 - pz0) / 4.0))):
                yc = ceil_y(inner, zh, abs(xb)) + 0.01
                if yc > yb + 0.05:
                    m.add("body", mk.box(xb, (yb + yc) / 2, zh, 0.03, yc - yb, 0.03, tag="panel"))
    elif layout == "cross":
        pitch = 1.0 if any(k in spec["id"] for k in ("_485_", "_0_", "_e5_")) else 1.55
        z = zs0 + 1.0
        rows = []
        while z < zs1 - 0.6:
            if free(z):
                rows.append(z)
            z += pitch
        if spec.get("two_axle") and len(doors) >= 2:
            # 区分室(扉と扉の間に向かい合わせの腰掛け)
            ds = sorted(doors)
            rows = [(a + b) / 2 for a, b in zip(ds, ds[1:])]
        for z in rows:
            for sg in (1, -1):
                seat_block(m, sg * 0.36, sg * hw, z, floor, facing=1)
        # 乗客の座席位置: 多すぎる場合は1列おき
        use = rows if len(rows) * 2 <= 28 else rows[::2]
        for z in use:
            for sg in (1, -1):
                seats.append((sg * (0.36 + hw) / 2, z + 0.02))
        # 荷物棚
        for sg in (1, -1):
            za, zb = pz0 + 0.5, pz1 - 0.5
            xo = min(wall_x(inner, zz, floor + 1.95) for zz in np.linspace(za, zb, 9)) + 0.01   # 壁にわずかに食い込ませる
            m.add("body", mk.box(sg * (xo - 0.2), floor + 1.95, (za + zb) / 2, 0.4, 0.04, zb - za, tag="panel"))
    elif layout == "sleeper":
        # B寝台: 通路は右(-X)側、寝台は車体を横切る向きに2段
        z = zs0 + 1.3
        while z < zs1 - 1.0:
            if free(z, 0.3):
                for yb in (floor + 0.4, floor + 1.5):
                    m.add("body", mk.box((hw - 0.4) / 2, yb - 0.1, z + 0.03, hw + 0.4, 0.2, 0.85, tag="seat"))
                m.add("body", mk.box((hw - 0.4) / 2, floor + 1.15, z + 0.45, hw + 0.4, 2.3, 0.06, tag="console"))   # 仕切り
                seats.append((0.3, z))
            z += 1.7
        spec["_seat_y"] = rider_y(floor - 0.05)
    spec["_seat_pos"] = seats[:28]

def build_car(spec):
    """電車・気動車・客車・新幹線(箱形の車体+台車)"""
    m = mk.Model(spec["id"])
    L, W, H = spec["L"], spec["W"], spec["H"]
    floor = spec.get("floor", 1.15)
    cab_layout(spec, floor)
    prof = car_body(m, L, W, H, floor, spec.get("front", "flat"), spec.get("rear", "flat"), p=spec.get("p", 5.0), bot=spec.get("body_bot"))
    for key, sgn in (("front", 1), ("rear", -1)):
        if spec.get(key) == "103":
            front_mask_103(m, spec, sgn, floor, spec.get("body_bot") if spec.get("body_bot") is not None else floor - 0.15)
    build_interior(m, spec, prof, floor)
    if spec.get("clerestory"):   # 二重屋根
        m.add("body", mk.box(0, H + 0.12, 0, W * 0.5, 0.36, L * 0.8, tag="body"))
    bz = spec["bogie_z"]
    if spec.get("two_axle"):
        for z in (bz, -bz):
            wheelset(m, "body", z, 0.43)
            m.add("body", mk.box(0, (0.4 + floor) / 2, z, G + 0.3, floor - 0.4, 0.9, tag="bogie"))
        underfloor(m, -bz + 0.6, bz - 0.6, floor - 0.1, W * 0.7)
    else:
        ao = spec.get("axle_offsets")       # 前の台車の軸の位置(+が車端側)。後ろの台車は前後を反転
        wr = spec.get("wheel_r", 0.43)      # 車輪の半径(機関車は電車より大きい)
        bogie(m, "$bogie_front", bz, wb=spec.get("wb", 2.1), r=wr, axles=spec.get("axles", 2), top=floor - 0.1, offsets=ao)
        bogie(m, "$bogie_rear", -bz, wb=spec.get("wb", 2.1), r=wr, axles=spec.get("axles", 2), top=floor - 0.1,
              offsets=[-o for o in ao] if ao else None)
        if spec.get("mid_bogie"):
            aux_bogie(m, spec, 0.0, wb=spec.get("wb", 2.1), axles=spec.get("axles", 2), top=floor - 0.1, r=wr)
        # 床下機器は台車(中間台車を含む)の所を避けて並べる
        half = (max(ao) - min(ao)) / 2 + wr + 0.3 if ao else spec.get("wb", 2.1) / 2 + wr + 0.3
        seed = sum(map(ord, spec["id"]))
        if spec.get("mid_bogie"):
            for k, (a, b_) in enumerate(((-bz + half, -half), (half, bz - half))):
                if b_ - a > 0.9:
                    equipment(m, a + 0.1, b_ - 0.1, floor, W, seed=seed + k)
        elif 2 * (bz - half) > 0.9:
            equipment(m, -bz + half + 0.1, bz - half - 0.1, floor, W, seed=seed)
    if spec.get("side_skirt"):
        # 車体の裾(側面下部の覆い): 車体の下端から下へ。台車の所は車輪の上で止める(編成図の青い裾の範囲)
        sb, bb = spec.get("body_bot", floor - 0.15), spec["side_skirt"]
        z_front = L / 2 - (3.9 if spec.get("front") == "sk0" else 0.3)    # 流線形の先頭部は車体が細くなる所(先端から約3.9m)まで
        z_rear = -L / 2 + (3.9 if spec.get("rear") == "sk0" else 0.3)
        cuts = sorted([(zb - 1.75, zb + 1.75) for zb in (bz, -bz)])
        segs, zc_ = [], z_rear
        for a, b in cuts:
            segs.append((zc_, a, bb)); segs.append((a, b, 0.88)); zc_ = b
        segs.append((zc_, z_front, bb))
        for sx in (1, -1):
            for a, b, low in segs:
                a, b = max(a, z_rear), min(b, z_front)
                if b - a > 0.1:
                    m.add("body", mk.box(sx * (W / 2 - 0.035), (low + sb + 0.5) / 2, (a + b) / 2, 0.05, sb + 0.5 - low, b - a, tag="body"))
    rf = H + (0.3 if spec.get("clerestory") else 0.0)
    cov = spec.get("panto_cover")      # パンタグラフカバー(屋根上の箱。パンタグラフはその上に載る): (中心z, 長さ, 高さ)
    if cov:
        cz, cl, ch = cov
        m.add("body", mk.box(0, rf - 0.1 + (ch + 0.1) / 2, cz, W * 0.62, ch + 0.1, cl, tag="roofunit"))
        m.add("body", mk.box(0, rf + ch - 0.02, cz, W * 0.5, 0.06, cl - 0.3, tag="roofunit"))
        for sx in (1, -1):                 # 側面のよろい戸(4枚)
            for k in range(4):
                m.add("body", mk.box(sx * W * 0.31, rf + ch * 0.45, cz - cl * 0.3 + k * cl * 0.2, 0.02, ch * 0.5, cl * 0.15, tag="acfan"))
    for pe in spec.get("pantographs", []):
        z, single = pe[0], pe[1]
        flip = len(pe) > 2 and pe[2]
        if cov and abs(z - cov[0]) < cov[1] / 2:
            pantograph(m, z, rf + cov[2] - 0.02, single, flip=flip)
            continue
        pantograph(m, z, rf - 0.06, single, flip=flip)
        arresters(m, z + (1.0 if z < 0 else -1.0), rf - 0.06)
    for rb in spec.get("roof_boxes", []):   # 屋根上の機器箱(簡易な直方体。前後の上の角を ch だけ斜めに落とす。nl: 側面のよろい戸の数)
        z0, z1, bw, bh, tg, ch = rb[:6]
        za, zb = min(z0, z1), max(z0, z1)
        nl = rb[6] if len(rb) > 6 else 0
        for k in range(nl):
            zc_ = za + (zb - za) * (k + 0.5) / nl
            for sx in (1, -1):
                m.add("body", mk.box(sx * (bw / 2 + 0.005), rf - 0.06 + bh * 0.5, zc_, 0.02, bh * 0.6, (zb - za) / nl * 0.7, tag="acfan"))
        ca, cb = (0.0, ch) if z0 > z1 else (ch, 0.0)     # z0 の側(前)の上の角を落とす
        rs = []
        for zz, hh in sorted(set(((za, bh - ca), (za + ca, bh), (zb - cb, bh), (zb, bh - cb)))):
            rs.append(np.array([(bw / 2, rf - 0.06, zz), (bw / 2, rf - 0.06 + hh, zz), (-bw / 2, rf - 0.06 + hh, zz), (-bw / 2, rf - 0.06, zz)], float))
        m.add("body", mk.loft(rs, tag=tg))
    for z, w in spec.get("roof_units", []):    # 冷房装置
        if spec.get("front") == "bonnet":       # 1段高い運転室の後ろに置く
            z = min(z, L / 2 - 6.3)
        roof_ac(m, z, w, rf - 0.05)
    if spec.get("roof_vents_z"):       # 通風器の位置を指定(グローブ形。屋根の中心線上)。パンタグラフの近くは置かない
        pz = [pe[0] for pe in spec.get("pantographs", [])]
        for zc in spec["roof_vents_z"]:
            if any(abs(zc - z) < 1.5 for z in pz):
                continue
            # 細い円柱の上の平たい円形のかさ。かさの径は通常のグローブ形の2倍(高さは同じ)
            m.add("body", mk.cylinder((0.0, rf - 0.1, zc), (0.0, rf + 0.12, zc), 0.12, n=8, tag="roofunit"))
            m.add("body", mk.lathe((0.0, rf + 0.12, zc), (0, 1, 0), [(0, 0.4), (0.08, 0.4), (0.16, 0.24), (0.2, 0.0)], n=12, tag="roofunit"))
    shinkansen = spec["id"].startswith(("jnr_0_", "jr_e5_"))
    src = spec.get("source")
    if spec.get("roof_vents", None) or (spec.get("roof_vents") is None and not spec.get("roof_units") and not shinkansen
                                        and not spec.get("pantographs") and spec.get("kind") == "car" and not spec.get("two_axle")
                                        and spec.get("H", 3) > 3.0 and spec.get("L", 0) > 12 and src != "electric"):
        ventilators(m, L, rf - 0.05, span=(L * 0.8 - 0.8) if spec.get("clerestory") else None)
    if src == "fuel":                         # 気動車の排気管
        for sm in spec.get("smoke", []):
            m.add("body", mk.cylinder((sm["x"], rf - 0.15, sm["z"]), (sm["x"], sm["y"] - 0.05, sm["z"]), 0.09, n=8, tag="under"))
    # 運転台: 屋根の列車無線アンテナと前面下部のスカート
    for c in spec.get("windows", {}).get("cabs", []):
        sgn = c[1]
        zend = sgn * L / 2
        kind = spec.get("front" if sgn > 0 else "rear", "flat")
        nose = {"bonnet": 0.75, "sk0": 1.3, "e5": 2.0, "ef58": 0.0}.get(kind, 0.0)
        if not shinkansen:
            antenna(m, zend - sgn * (nose + 2.6 if kind != "bonnet" else 4.95), rf - 0.05, x=0.55)   # 485系: 1段高い運転室の後ろ
        else:
            antenna(m, sgn * (L / 2 - (5.5 if kind == "sk0" else 16.0)), rf - 0.05, x=0.0)
        if src is not None or spec.get("powered") or spec.get("kind") == "car":
            if kind == "sk0":
                # 0系の排障器(図面の側面図・正面図による): 左右2枚の薄い板。上から見ると先端の中央で合わさるハの字で、
                # 側面から見ると下端ほど前へ張り出す(前方へ広がる)。正面から見ると中央が低いV字
                th = 0.05
                for sx in (1, -1):
                    # 板の4隅: 前(中央)の上・下、後ろ(外側)の上・下。(x, y, 先端からの距離)
                    q = [(0.0, 1.17, 0.28), (0.0, 0.16, 0.05), (sx * 1.42, 0.42, 1.62), (sx * 1.42, 1.05, 1.9)]
                    pts = np.array([(x, y, zend - sgn * dd) for x, y, dd in q], float)
                    nrm = np.cross(pts[1] - pts[0], pts[3] - pts[0]); nrm /= np.linalg.norm(nrm)
                    m.add("body", mk.loft([pts - nrm * th / 2, pts + nrm * th / 2], tag="plow"))
                # 板の上端と鼻の下面の間の取り付け(中央の縦の支え)
                m.add("body", mk.box(0, 1.05, zend - sgn * 0.45, 0.12, 0.25, 0.3, tag="plow"))
            elif kind == "bonnet":
                # 485系の裾(写真): 正面の赤帯の下の、わずかに奥まった角の丸いスカート(下端 約0.48m)
                zf_ = lambda dd: zend - sgn * dd
                sk = []
                # 単色(車体の地色)の部品にする: 上面は鼻の中に埋まり、展開図でボンネットの上面とテクセルを取り合うため
                for dd, ww in ((0.08, 0.95), (0.11, 1.06), (0.2, 1.14), (0.4, 1.2), (1.8, 1.34)):    # 車体と同じく前へしぼむ
                    sk.append(sring(0, (1.04 + 0.48) / 2, zf_(dd), ww * W / 2.9, (1.04 - 0.48) / 2, n=20, p=8.0))
                m.add("body", mk.loft(sorted(sk, key=lambda r: r[0][2]), tag="skirt485"))
                m.add("body", mk.box(0, 0.8, zf_(0.1), 0.7, 0.36, 0.06, tag="skirt"))     # 連結器のふた
                # 左右の灯具と側面の赤い帯の部分(全幅のまま後ろへ直線的に延びる。しぼんだボンネットとの間に段ができる)、
                # その下の裾の赤帯の部分(全幅)。正面はボンネットの正面よりわずかに前
                # 灯具の部分は角の丸い形で、前の端も丸い。下側も車体から離れた張り出し(車体は下までしぼんだまま)
                xo, xi = W / 2, 1.07 * W / 2.9
                for sx in (1, -1):
                    lb = [sring(sx * (xo + xi) / 2, 1.82, zf_(dd), (xo - xi) / 2 * k, 0.37 * k, n=16, p=4.0)
                          for dd, k in ((-0.03, 0.72), (-0.01, 0.9), (0.03, 0.98), (0.08, 1.0), (2.4, 1.0))]
                    m.add("body", mk.loft(sorted(lb, key=lambda r: r[0][2]), tag="body"))
                # 運転室の屋根の前照灯のケース(前面窓の上)
                cr = H + BONNET_CAB_UP           # 運転室の屋根
                hr = [(2.75, 0.32, cr + 0.3, cr - 0.12), (2.85, 0.42, cr + 0.3, cr - 0.12), (3.3, 0.42, cr + 0.27, cr - 0.08), (3.5, 0.3, cr + 0.18, cr - 0.08)]
                m.add("body", mk.loft(sorted([sring(0, (a + b_) / 2, zf_(dd), ww, (a - b_) / 2, n=16, p=4.0) for dd, ww, a, b_ in hr],
                                             key=lambda r: r[0][2]), tag="body"))
                for sx in (1, -1):     # ケースの側面のよろい戸
                    m.add("body", mk.box(sx * 0.42, cr + 0.12, zf_(3.1), 0.02, 0.16, 0.3, tag="acfan"))
            elif kind == "e5":
                skirt(m, zend - sgn * nose, sgn, floor, width=W * 0.55, notch=False, bottom=0.3, top=floor + 0.15)
            elif kind == "kuno":
                # DF200・EF210: 車体の下のスカートは下ほど前へ出る傾斜(車体の下側の傾斜と向きが変わる)で、連結器の少し下で
                # 終わる。その下(線路の近く)は左右に広がるハの字の排障器(下から見てハの字)
                zf_ = lambda dd: zend - sgn * dd
                th = 0.05
                bb = floor - 0.15
                q = np.array([(-1.2, bb + 0.03, zf_(0.3)), (1.2, bb + 0.03, zf_(0.3)), (1.2, 0.72, zf_(0.08)), (-1.2, 0.72, zf_(0.08))], float)
                nrm = np.cross(q[1] - q[0], q[3] - q[0]); nrm /= np.linalg.norm(nrm)
                m.add("body", mk.loft([q - nrm * th / 2, q + nrm * th / 2], tag="skirt"))
                for sx in (1, -1):
                    q = np.array([(0.0, 0.74, zf_(0.06)), (0.0, 0.2, zf_(0.02)), (sx * 1.3, 0.2, zf_(0.75)), (sx * 1.3, 0.74, zf_(0.7))], float)
                    nrm = np.cross(q[1] - q[0], q[3] - q[0]); nrm /= np.linalg.norm(nrm)
                    m.add("body", mk.loft([q - nrm * th / 2, q + nrm * th / 2], tag="skirt"))
                for sx in (1, -1):    # 排障器・スカートの裏の支え(台枠へ)
                    m.add("body", mk.box(sx * 0.9, (0.7 + bb + 0.1) / 2, zf_(0.45), 0.08, bb + 0.1 - 0.7, 0.5, tag="skirt"))
                # 運転室の屋根(ひさし): 前面窓の上でわずかに前へ飛び出し、乗務員扉の位置まで緩やかに高くなる(最も高い所は
                # 屋根上の機器と同じくらい)。側面は約45°の傾斜で、上へ行くほど丸く緩む。扉の後ろで屋根へ下がる
                y0c = H - 0.35          # 側面の45°の傾斜は、この高さの車体の側面から、車体の屋根の丸い角に外から接する線
                def cap_ring(dd, ytop, zz=None):
                    hb = (W / 2) * (0.9 if dd < 0.48 else 0.95 if dd < 0.55 else 0.985 if dd < 0.65 else 1.0) + 0.01   # 前の縁は前面の幅に合わせる
                    if spec.get("roof_hood"):                 # 屋根の丸みから出っ張らないよう、裾の幅はその位置の車体の幅に合わせる
                        te_, pb = H - 0.25, spec.get("p", 5.0)
                        td = te_ + (H - te_) * min(max(dd - 0.6, 0.0) / 0.25, 1.0)
                        wd = (W / 2) * (0.93 + 0.07 * min(dd / 0.3, 1.0))
                        # 断面: 裾から車体の屋根の丸い角に沿って(1cm外側)上がり、角が45°になる所から45°の傾斜、上は丸く
                        sxf = lambda y: section_x(pb, wd, max(td, y + 0.02), floor - 0.15, y) + 0.01
                        hgt = ytop - y0c
                        y_t = min(H - 0.18, y0c + 0.6 * hgt)
                        side = [(sxf(y0c), y0c)]
                        for k in (1, 2, 3):
                            yk = y0c + (y_t - y0c) * k / 3
                            side.append((sxf(yk), yk) if yk < td - 0.02 else (side[-1][0] - (yk - side[-1][1]), yk))
                        xe, ye_ = side[-1]
                        y45 = ye_ + 0.45 * (ytop - ye_)
                        p45 = (xe - (y45 - ye_), y45)
                        cx2 = p45[0] - (ytop - y45)
                        R = [(0.0, y0c), (side[0][0] * 0.5, y0c)] + side + [p45]
                        for t_ in (0.3, 0.6, 0.85):
                            a, b2, c2 = np.array(p45), np.array((cx2, ytop)), np.array((cx2 * 0.35, ytop))
                            R.append(tuple((1 - t_) ** 2 * a + 2 * (1 - t_) * t_ * b2 + t_ ** 2 * c2))
                        R.append((0.0, ytop))
                        P = R + [(-x, y) for x, y in reversed(R[1:-1])]
                        return np.array([(x, y, zf_(dd) if zz is None else zz) for x, y in P], float)
                    hgt = ytop - y0c
                    S = 0.55 * hgt
                    cx = hb - S - (hgt - S)                   # 45°の線と上面の水平線の交点(丸めの制御点)
                    R = [(0.0, y0c), (hb * 0.5, y0c), (hb, y0c), (hb - S / 2, y0c + S / 2), (hb - S, y0c + S)]
                    for t_ in (0.3, 0.6, 0.85):
                        a, b2, c2 = np.array((hb - S, y0c + S)), np.array((cx, ytop)), np.array((cx * 0.35, ytop))
                        R.append(tuple((1 - t_) ** 2 * a + 2 * (1 - t_) * t_ * b2 + t_ ** 2 * c2))
                    R.append((0.0, ytop))
                    P = R + [(-x, y) for x, y in reversed(R[1:-1])]
                    return np.array([(x, y, zf_(dd) if zz is None else zz) for x, y in P], float)
                if spec.get("roof_hood"):
                    # DF200(写真): 運転室の屋根は前へ出っ張らず、前面窓の上から屋根の丸みに沿って上がる。前後の高い所の間は
                    # 同じ高さの丸い屋根(機関室の覆い)でつなぐ。中央に大型のファン2つを前後に並べる。前後の端で1つの部品
                    if sgn > 0:
                        hood = [(0.62, H - 0.18), (0.68, H - 0.08), (0.78, H + 0.04), (0.9, H + 0.13), (1.1, H + 0.2), (1.4, H + 0.26), (1.75, H + 0.28)]
                        rings = [cap_ring(dd, yt, L / 2 - dd) for dd, yt in hood] + [cap_ring(dd, yt, -(L / 2 - dd)) for dd, yt in reversed(hood)]
                        m.add("body", mk.loft(sorted(rings, key=lambda r: r[0][2]), tag="body"))
                        yt = H + 0.28
                        for zc in (0.95, -0.95):                     # 大型のファン(丸い枠・羽根の覆いの格子・中心)
                            m.add("body", mk.cylinder((0, yt - 0.04, zc), (0, yt + 0.07, zc), 0.62, n=20, tag="acfan"))
                            for k in (0, 2):
                                a = np.pi * k / 4
                                m.add("body", mk.box(0, yt + 0.075, zc, 1.16 * abs(np.cos(a)) + 0.03, 0.02, 1.16 * abs(np.sin(a)) + 0.03, tag="rod"))
                            m.add("body", mk.cylinder((0, yt + 0.05, zc), (0, yt + 0.1, zc), 0.12, n=10, tag="rod"))
                else:
                    cap = [(0.45, H - 0.23), (0.5, H - 0.15), (0.6, H - 0.05), (0.75, H + 0.03), (0.9, H + 0.07), (1.75, H + 0.3)]   # 後ろの端は垂直
                    m.add("body", mk.loft(sorted([cap_ring(dd, yt) for dd, yt in cap], key=lambda r: r[0][2]), tag="body"))
            elif kind in ("ef81", "ef65pf"):
                # EF81・EF65 PF(写真): 前面下部の灰色の端梁(車体の下端から線路の近くまで、ほぼ全幅)。ジャンパ連結器・
                # ホースの受けの窪み(暗い)、下の角の白いステップ。EF65 PFは下に排障器
                zf_ = lambda dd: zend - sgn * dd
                m.add("body", mk.box(0, (0.32 + floor - 0.12) / 2, zf_(0.42), 2.6, floor - 0.12 - 0.32, 0.76, tag="endbeam"))
                for sx in (1, -1):
                    m.add("body", mk.box(sx * 0.92, 0.98, zf_(0.035), 0.24, 0.36, 0.04, tag="under"))
                    m.add("body", mk.box(sx * 1.12, 0.34, zf_(0.1), 0.34, 0.03, 0.24, tag="rail"))
                if kind == "ef65pf":
                    q = np.array([(-1.15, 0.5, zf_(0.06)), (1.15, 0.5, zf_(0.06)), (1.25, 0.18, zf_(-0.08)), (-1.25, 0.18, zf_(-0.08))], float)
                    nrm = np.cross(q[1] - q[0], q[3] - q[0]); nrm /= np.linalg.norm(nrm)
                    m.add("body", mk.loft([q - nrm * 0.025, q + nrm * 0.025], tag="plow"))
                # 前照灯: 屋根の前の縁の左右に、短い円筒のケースが前へ飛び出す
                hx, hy = (0.73, H - 0.22) if kind == "ef81" else (0.72, H - 0.2)
                for sx in (1, -1):
                    m.add("body", mk.cylinder((sx * hx, hy, zf_(0.2)), (sx * hx, hy, zf_(-0.03)), 0.12, n=14, tag="lamprim"))
                    m.add("body", mk.cylinder((sx * hx, hy, zf_(-0.02)), (sx * hx, hy, zf_(-0.04)), 0.095, n=14, tag="headlamp"))
            elif not spec.get("two_axle") and kind != "ef66":
                skirt(m, zend - sgn * nose, sgn, floor, width=min(2.4, W * 0.85), notch=True)
            if kind == "ef58":
                # 前面上部の中央の前照灯: 車体から円筒が飛び出す
                zf_ = lambda dd: zend - sgn * dd
                yl = H - 0.22
                m.add("body", mk.cylinder((0, yl, zf_(0.85)), (0, yl, zf_(0.42)), 0.2, n=14, tag="lamprim"))
                m.add("body", mk.cylinder((0, yl, zf_(0.44)), (0, yl, zf_(0.41)), 0.15, n=14, tag="headlamp"))
            if kind == "ef66":
                zf_ = lambda dd: zend - sgn * dd
                # 屋根のひさし: 前面窓の上で前へ張り出す薄い板。前の角は丸い
                # 庇: 屋根の丸い上面がそのまま前へ延びる(屋根と同じ色)。下面は前面窓の上端の高さで平ら、前の縁は上から見て丸い
                hwb = W / 2
                xl = float(np.interp(EF66_WY1, [ef66_roof_y(hwb, H, x) for x in np.linspace(hwb, 0, 40)], np.linspace(hwb, 0, 40)))
                vr = []
                for dd in (0.502, 0.51, 0.53, 0.56, 0.6, 0.65, 0.7, 0.76, 0.85, 1.0, 1.3):
                    xv = min(xl, hwb * np.sqrt(max(dd - 0.5, 0.0) / 0.25))
                    xsmp = np.linspace(xv, -xv, 9)
                    top_ = [(x, max(ef66_roof_y(hwb, H, x) + 0.015, EF66_WY1 + 0.015)) for x in xsmp]
                    bot_ = [(x, EF66_WY1) for x in xsmp[::-1]]
                    vr.append(np.array([(x, y, zf_(dd)) for x, y in top_ + bot_], float))
                m.add("body", mk.loft(sorted(vr, key=lambda r: r[0][2]), tag="body"))
                # 鼻の下のスカート(灰色): 背が高く、正面は下ほど後ろへ下がる。上から見て四角錐と同じくの字
                def sk_ring(y, d0, hwx):
                    return np.array([(hwx, y, zf_(1.15)), (hwx, y, zf_(d0 + 0.28 * hwx)), (0.0, y, zf_(d0)),
                                     (-hwx, y, zf_(d0 + 0.28 * hwx)), (-hwx, y, zf_(1.15))], float)
                m.add("body", mk.loft([sk_ring(0.25, 0.7, 1.15), sk_ring(1.13, 0.5, 1.32)], tag="endbeam"))
                # 左右の灯具(上が前照灯、下が尾灯): 縦長の角の丸い箱。四角錐の下の面から前へ飛び出す(485系の灯具と同じ考え方)
                for sx in (1, -1):
                    lb = [sring(sx * 1.05, 1.875, zf_(dd), 0.19 * k, 0.42 * k, n=16, p=3.5)
                          for dd, k in ((0.3, 0.88), (0.33, 0.97), (0.38, 1.0), (0.95, 1.0))]
                    m.add("body", mk.loft(sorted(lb, key=lambda r: r[0][2]), tag="body"))
                # 車番プレートの台(四角錐の頂点のすぐ下で前へ飛び出す)
                m.add("body", mk.box(0, 2.72, zf_(0.1), 0.85, 0.22, 0.16, tag="body"))
    if spec.get("deck"):    # 貨車の床・車掌室など(箱形の上物は builder 側で追加)
        pass
    for extra in spec.get("extra", []):
        extra(m, spec)
    return m, floor

def build_hood_loco(spec):
    """DD51・DE10: 凸形(ボンネット形)のディーゼル機関車(写真による)。
    - 台枠(ランボード)は車体の全長・全幅。両端は機関部の箱から一歩下がったデッキで、手すりが立つ。
    - 機関部の箱(ボンネット)は車体より細く、上の角が丸い。端の面の上部に灰色の帯と左右2灯の前照灯。
    - 運転室は全幅で、前後の面は中央の幅広の柱(屋根のひさしと一体)で左右に分かれ、その左右に窓。
      屋根は前後へひさしのように少し張り出す。運転室の前後の低い位置に箱(DD51)。
    - 両端の端梁(灰色)に尾灯、その下に排障器と連結器"""
    m = mk.Model(spec["id"])
    L, W, H = spec["L"], spec["W"], spec.get("H", 3.95)
    hw = W / 2
    floor = 1.35
    zend = L / 2 - 0.2                                  # 台枠の端(連結器の分を除く)
    m.add("body", mk.box(0, floor, 0, W, 0.3, 2 * zend, tag="body"))          # 台枠(側面は灰色の帯)
    m.add("body", mk.box(0, floor + 0.155, 0, W - 0.004, 0.02, 2 * zend - 0.004, tag="endbeam"))   # 足場(台枠の上面)は灰色
    cz, cl = spec["cab_z"], spec["cab_len"]
    cz0, cz1 = cz - cl / 2, cz + cl / 2
    ctop = H - 0.22                                      # 運転室の壁の上端(その上に丸い屋根)
    m.add("body", mk.box(0, (floor + 0.15 + ctop) / 2, cz, W, ctop - floor - 0.15, cl, tag="body"))
    # 屋根: 断面は丸いアーチ(軒は壁の上端、頂部は H)。DD51は前後へ約0.48m張り出すひさし、DE10はひさし無し(spec["visor"]=0)
    visor = spec.get("visor", 0.48)
    rp = ("roof", 0.25, 2.0)
    rf = [sring(0, (H + ctop - 0.03) / 2, zz, hw - 0.01, (H - ctop + 0.03) / 2, p=rp) for zz in (cz0 - visor, cz1 + visor)]
    # 屋根は全体を灰色の単色にする(塗り分けなし)。投影の展開図に入れると、ひさしの下の機関部の上面と
    # テクセルを取り合い、ひさしの下の塗り分けが前後とずれる
    m.add("body", mk.loft(rf, tag="dlroof"))
    # 前後の面の中央の幅広の柱: ひさしと一体で、外側(前)の角は丸い。側面から見て下ほど外へ出る約5°の傾斜
    hood_top = 3.05
    cw2, rr_ = 0.28, 0.14
    y_lo, y_hi = hood_top - 0.1, ctop + 0.02
    for sg in (1, -1):
        zw = cz1 if sg > 0 else cz0
        rings = []
        y_top = H - 0.015 if visor <= 0 else y_hi          # ひさしの無い運転室(DE10): 柱を屋根の高さまで伸ばし、屋根と一体にする
        for yy in sorted({y_lo, y_hi, y_top}):
            dd = 0.48 + (y_hi - yy) * np.tan(np.radians(5.0))   # 壁から前の面まで(下ほど外へ5°)。柱の出っ張りはひさしの有無によらず同じ
            zf_ = zw + sg * dd
            loop = [(-cw2, yy, zw - sg * 0.03)]
            for k in range(5):                                    # 左の前の角の丸み
                a = np.pi / 2 * k / 4
                loop.append((-cw2 + rr_ * (1 - np.cos(a)), yy, zf_ - sg * rr_ * (1 - np.sin(a))))
            for k in range(5):                                    # 右の前の角の丸み
                a = np.pi / 2 * k / 4
                loop.append((cw2 - rr_ * (1 - np.sin(a)), yy, zf_ - sg * rr_ * (1 - np.cos(a))))
            loop.append((cw2, yy, zw - sg * 0.03))
            rings.append(np.array(loop, float))
        m.add("body", mk.loft(rings, tag="cabcol"))
    # 運転室の中(内張り・前後の運転台・腰掛け)。運転室の床はデッキより高く(cfl)、運転士の目が前の窓
    # (機関部の箱の上)の高さに来るようにする。目の高さ ≒ 座席位置 + 1.15m(前提MODの0.7倍の乗員)
    cfl = 2.2
    inner = mk.box(0, (floor + 0.17 + ctop - 0.06) / 2, cz, W - 0.16, ctop - 0.06 - floor - 0.17, cl - 0.16, tag="lining")
    inner.reverse()
    m.add("body", inner)
    m.add("body", mk.box(0, (floor + 0.08 + cfl) / 2, cz, W - 0.18, cfl - floor - 0.08, cl - 0.18, tag="console"))   # 運転室の床(台枠に食い込ませる)
    for sg in (1, -1):
        zc = cz + sg * (cl / 2 - 0.4)
        m.add("body", mk.box(sg * 0.45, cfl + 0.4, zc, 1.2, 0.8, 0.45, tag="console"))            # 操作卓(上面は窓の下端より下)
        m.add("body", mk.box(sg * 0.45, cfl + 0.82, zc - sg * 0.1, 0.9, 0.05, 0.3, tag="panel"))
        seat_block(m, sg * 0.25, sg * 0.85, zc - sg * 0.75, cfl, facing=sg, depth=0.45, back=0.6)
    spec["_driver_pos"] = (0.55, rider_y(cfl), cz + cl / 2 - 1.15)
    spec["seats"] = [(-0.55, cz - cl / 2 + 1.15)]
    spec["seat_y"] = rider_y(cfl)
    # 機関部の箱: 車体より細く、上の角が丸い。端の面の上の角も小さく丸める
    hh = W * 0.4
    for z0, z1 in spec["hoods"]:
        a, b_ = sorted((z0, z1))
        rings = []
        for zz, k, dt in ((a, 0.97, 0.06), (a + 0.05, 0.995, 0.015), (a + 0.12, 1.0, 0.0), (b_ - 0.12, 1.0, 0.0), (b_ - 0.05, 0.995, 0.015), (b_, 0.97, 0.06)):
            # 運転室に接する端は丸めない
            if (abs(zz - a) < 0.13 and a >= cz0 - 0.01 and a <= cz1 + 0.01) or (abs(zz - b_) < 0.13 and cz0 - 0.01 <= b_ <= cz1 + 0.01):
                k, dt = 1.0, 0.0
            t = hood_top - dt
            rings.append(sring(0, (t + floor + 0.1) / 2, zz, hh * k, (t - floor - 0.1) / 2, n=20, p=(6.0, 12.0)))
        m.add("body", mk.loft(rings, tag="body"))
    hood_ends = []
    for z0, z1 in spec["hoods"]:
        for zz in (z0, z1):
            if not (cz0 - 0.3 <= zz <= cz1 + 0.3):
                hood_ends.append(zz)
    spec["_hood_ends"] = hood_ends
    # 運転室の前後の低い箱(DD51)
    for z0, z1 in spec.get("cab_boxes", []):
        m.add("body", mk.box(0, floor + 0.15 + 0.15, (z0 + z1) / 2, W - 0.12, 0.3, abs(z1 - z0), tag="body"))
    for zc, axles in spec["bogies"]:
        zs_ = [z for z, _ in spec["bogies"]]
        if zc in (max(zs_), min(zs_)):
            bogie(m, "$bogie_front" if zc == max(zs_) else "$bogie_rear", zc, wb=1.9 if axles == 2 else 3.2, axles=axles, top=floor - 0.15, r=spec.get("wheel_r", 0.43))
        else:                              # 中間台車(DD51など): 独立した部品にして線路に追随させる
            aux_bogie(m, spec, zc, wb=1.9 if axles == 2 else 3.2, axles=axles, top=floor - 0.15, r=spec.get("wheel_r", 0.43))
    # 床下機器(燃料タンク・空気だめ・蓄電池箱など。考証より見た目): 台車と端梁の間に箱を並べる
    _hz = lambda ax: (1.6 if ax > 2 else 0.95) + spec.get("wheel_r", 0.43) + 0.22   # 台車の半分の長さ(車輪を含む)
    zones = sorted((zc - _hz(ax), zc + _hz(ax)) for zc, ax in spec["bogies"])
    segs, zz = [], -zend + 0.6
    for a, b_ in zones:
        if a - zz > 0.9:
            segs.append((zz, a))
        zz = max(zz, b_)
    if zend - 0.6 - zz > 0.9:
        segs.append((zz, zend - 0.6))
    for k, (a, b_) in enumerate(segs):
        equipment(m, a + 0.1, b_ - 0.1, floor - 0.05, W, seed=sum(map(ord, spec["id"])) + k, center_sill=False)
    for sgn in (1, -1):
        ze = sgn * zend
        he = min(hood_ends, key=lambda z: abs(z - ze))   # この端の機関部の箱の端
        # 端梁(灰色)・尾灯・排障器
        m.add("body", mk.box(0, (0.72 + floor - 0.15) / 2, ze - sgn * 0.12, W - 0.2, floor - 0.15 - 0.72, 0.24, tag="endbeam"))
        for sx in (1, -1):
            m.add("body", mk.cylinder((sx * 1.05, 1.0, ze - sgn * 0.02), (sx * 1.05, 1.0, ze + sgn * 0.05), 0.09, n=10, tag="lamprim"))
            m.add("body", mk.cylinder((sx * 1.05, 1.0, ze + sgn * 0.04), (sx * 1.05, 1.0, ze + sgn * 0.06), 0.065, n=10, tag="taillamp"))
        skirt(m, ze, sgn, 0.85, width=2.3, notch=True, bottom=0.3)
        # 機関部の端の面: 左右の前照灯(上部の灰色の帯の中)
        for sx in (1, -1):
            m.add("body", mk.cylinder((sx * 0.72, 2.78, he - sgn * 0.02), (sx * 0.72, 2.78, he + sgn * 0.06), 0.17, n=12, tag="lamprim"))
            m.add("body", mk.cylinder((sx * 0.72, 2.78, he + sgn * 0.05), (sx * 0.72, 2.78, he + sgn * 0.07), 0.12, n=12, tag="headlamp"))
            # 機関部の端の面の縦の手すり
            m.add("body", mk.cylinder((sx * 0.95, floor + 0.5, he + sgn * 0.06), (sx * 0.95, 2.4, he + sgn * 0.06), 0.022, n=6, tag="rail"))
            for yy in (floor + 0.5, 2.4):
                m.add("body", mk.box(sx * 0.95, yy, he + sgn * 0.03, 0.04, 0.04, 0.08, tag="rail"))
        # デッキの手すり(実車の構造): デッキの上を左右に行き来できるよう、手すりは台枠の外側の縁に沿わせる。
        # - 前の縁: 中央寄りの縦の支柱から外側へ延び、外側の端は斜めに下ってデッキの角へ下りる
        #   (角の側面にある昇降用のはしごで昇り降りするとき、斜めの部分をつかむ)
        # - はしご: 前の角の側面、端梁の横に、デッキから下へ段を並べる(幅約0.45m)
        # - 側縁: はしごの幅の分だけ前を空けて、はしごの後ろから機関部の端の面の手前まで
        xe_ = hw - 0.04
        ye0, ye1, ye2 = floor + 0.15, floor + 0.6, floor + 1.05
        zf_e, zb_e = ze - sgn * 0.04, he + sgn * 0.08             # 前の縁、機関部の端の面の手前
        lw = 0.45                                                 # はしごの幅
        def bar(p0, p1, r=0.022):
            m.add("body", mk.cylinder(p0, p1, r, n=6, tag="rail"))
        for sx in (1, -1):
            x = sx * xe_
            xi, xt = sx * 0.55, sx * (hw - 0.45)
            # 前の縁: 内側の縦の支柱、上の横棒、外側の斜めに下る部分、中段の横棒
            bar((xi, ye0, zf_e), (xi, ye2, zf_e), 0.025)
            bar((xi, ye2, zf_e), (xt, ye2, zf_e))
            bar((xt, ye2, zf_e), (x, ye0 + 0.03, zf_e))
            t = (ye2 - ye1) / (ye2 - ye0 - 0.03)
            bar((xi, ye1, zf_e), (xt + (x - xt) * t, ye1, zf_e))
            # はしご(前の角の側面): 縦の2本と段
            xl = sx * (hw + 0.015)
            zl0, zl1 = ze - sgn * 0.06, ze - sgn * (0.06 + lw)
            for zz in (zl0, zl1):
                bar((xl, 0.45, zz), (xl, ye0, zz), 0.025)
            for yy in (0.55, 0.85, 1.15):
                bar((xl, yy, zl0), (xl, yy, zl1), 0.02)
            # 側縁: はしごの後ろから
            zs0 = ze - sgn * (0.06 + lw + 0.05)
            for zz in (zs0, zb_e):
                bar((x, ye0, zz), (x, ye2, zz), 0.025)
            for yy in (ye1, ye2):
                bar((x, yy, zs0), (x, yy, zb_e))
    for z0, z1 in spec["hoods"]:          # ボンネット上の放熱器のファン
        zc = (z0 + z1) / 2
        for dz in (-abs(z1 - z0) * 0.25, abs(z1 - z0) * 0.25):
            m.add("body", mk.cylinder((0, hood_top - 0.05, zc + dz), (0, hood_top + 0.07, zc + dz), 0.5, n=12, tag="acfan"))
    antenna(m, cz, H, x=0.6)
    for sm in spec.get("smoke", []):       # 排気筒
        m.add("body", mk.cylinder((sm["x"], hood_top - 0.1, sm["z"]), (sm["x"], sm["y"], sm["z"]), 0.12, n=8, tag="under"))
    return m, floor

def drivers_min(spec):
    return min(spec["drivers"])

def build_steam(spec):
    """蒸気機関車(テンダー機・タンク機)"""
    m = mk.Model(spec["id"])
    zf = spec["zf"]                         # 機関車の前端
    lz = spec["loco_len"]
    br, by = spec["boiler_r"], spec["boiler_y"]
    rf = spec["frame_y"]
    # 台枠・ランボード
    fy0 = min(spec["driver_r"], min([r for _, r in spec.get("small_wheels", [])] or [spec["driver_r"]])) - 0.05
    m.add("body", mk.box(0, (fy0 + rf + 0.25) / 2, zf - lz / 2, 1.3, rf + 0.25 - fy0, lz, tag="under"))
    m.add("body", mk.box(0, by - br * 0.75, zf - lz / 2 - 0.2, 2.7, 0.08, lz - 1.2, tag="under"))
    m.add("body", mk.box(0, rf - 0.05, zf - 0.15, 2.6, 0.55, 0.3, tag="beam"))           # 前の緩衝梁(赤)
    skirt(m, zf - 0.1, 1, rf - 0.2, width=2.1, notch=True, bottom=0.22)                       # 排障器
    # ボイラー・煙室・煙突・ドーム
    bz0, bz1 = zf - spec["boiler_len"] - 0.6, zf - 0.6
    m.add("body", mk.cylinder((0, by, bz0), (0, by, bz1 - 1.2), br, n=16, tag="boiler"))
    m.add("body", mk.cylinder((0, by, bz1 - 1.3), (0, by, bz1), br * 1.04, n=16, tag="smokebox"))
    m.add("body", mk.cylinder((0, by, bz1 - 0.05), (0, by, bz1 + 0.06), br * 0.85, n=16, tag="smokebox"))
    cy0 = by + br * 0.85
    m.add("body", mk.cylinder((0, cy0, bz1 - 0.65), (0, cy0 + spec["chimney_h"], bz1 - 0.65), spec.get("chimney_r", 0.27), spec.get("chimney_r", 0.27) * spec.get("chimney_flare", 1.0), n=12, tag="smokebox"))
    for dz in spec["domes"]:
        m.add("body", mk.lathe((0, by + br * 0.8, bz1 + dz), (0, 1, 0), [(0, 0.42), (0.35, 0.38), (0.5, 0.25), (0.58, 0.0)], n=12, tag="boiler"))
    # 運転室
    cz1 = bz0 + 0.4
    cl = spec["cab_len"]
    ct = spec["cab_top"]
    m.add("body", mk.box(0, (rf + ct) / 2 + 0.1, cz1 - cl / 2, 2.7, ct - rf, cl, tag="cab"))
    m.add("body", mk.box(0, ct + 0.05, cz1 - cl / 2, 2.9, 0.12, cl + 0.3, tag="cabroof"))
    # 運転室の中(内張り・焚口のある火室の後面・左右の機関士/機関助士の腰掛け)
    cfl = rf + 0.3
    inner = mk.box(0, (cfl + ct + 0.02) / 2, cz1 - cl / 2, 2.54, ct + 0.02 - cfl, cl - 0.16, tag="lining")
    inner.reverse()
    m.add("body", inner)
    m.add("body", mk.box(0, (cfl + by + br * 0.7) / 2, cz1 - 0.42, 1.5, by + br * 0.7 - cfl, 0.12, tag="console"))   # 火室の後面
    m.add("body", mk.box(0, cfl + 0.45, cz1 - 0.52, 0.5, 0.35, 0.1, tag="panel"))                                 # 焚口
    for sg in (1, -1):
        seat_block(m, sg * 0.6, sg * 1.15, cz1 - 1.15, cfl, facing=1, depth=0.4, back=0.5)
    spec["_cab_floor"] = cfl
    spec["_driver_pos"] = (0.88, rider_y(cfl), cz1 - 1.13)
    spec["seats"] = [(-0.88, cz1 - 1.13)]
    spec["seat_y"] = rider_y(cfl)
    # 除煙板
    if spec.get("deflectors"):
        for s in (1, -1):
            m.add("body", mk.box(s * (br + 0.35), by - br * 0.75 + 0.78, bz1 - 0.9, 0.05, 1.6, 2.0, tag="boiler"))
    # シリンダー
    for s in (1, -1):
        m.add("body", mk.cylinder((s * 0.85, rf + 0.25, zf - 2.3), (s * 0.85, rf + 0.25, zf - 0.9), 0.32, n=10, tag="under"))
    # ボイラーの支持(実機ではボイラーは台枠の上に載っている)
    #  前: 煙室の下はシリンダーと一体の「煙室サドル」で台枠に載る
    #  中: 台枠から立ち上がる支え板(ボイラー受け)で下から支える
    #  後: 火室が台枠の間へ下がり、下端の灰箱まで台枠とつながる(広火室は上部が台枠より幅広)
    ftop = rf + 0.25
    bbot = by - br
    m.add("body", mk.box(0, (ftop - 0.3 + by - br * 0.4) / 2, bz1 - 0.7, 1.5, by - br * 0.4 - ftop + 0.3, 1.1, tag="smokebox"))   # 煙室サドル
    m.add("body", mk.box(0, ftop, zf - 1.6, 1.75, 0.5, 1.2, tag="under"))                                                      # シリンダー間のつなぎ
    fl = min(2.6, spec["boiler_len"] * 0.3)
    wide = spec.get("tender") and any(z < drivers_min(spec) for z, _ in spec.get("small_wheels", []))
    fw = br * 1.9 if wide else min(1.2, br * 1.9)
    m.add("body", mk.box(0, (bbot - 0.15 + by) / 2, bz0 + fl / 2, fw, by - bbot + 0.15, fl, tag="boiler"))                    # 火室(上部)
    m.add("body", mk.box(0, (rf - 0.25 + bbot) / 2, bz0 + fl / 2, 1.0, bbot - rf + 0.3, fl - 0.1, tag="under"))              # 火室下部・灰箱
    z_lo, z_hi = bz0 + fl + 0.3, bz1 - 1.4
    n_sup = max(1, int((z_hi - z_lo) / 1.6))
    for i in range(n_sup + 1):
        z = z_lo + (z_hi - z_lo) * i / max(1, n_sup)
        m.add("body", mk.box(0, (ftop - 0.05 + bbot + 0.15) / 2, z, 1.1, bbot + 0.2 - ftop, 0.14, tag="under"))         # ボイラー受け
    # 動輪(車軸ごとに別グループにして回す)・ロッド類・先台車/従台車・炭水車
    dr = spec["driver_r"]
    drivers = spec["drivers"]
    rc = round(dr * 0.4, 3)                  # クランク半径
    xo = G / 2 + 0.12                        # 車輪の外側の面
    phases = {1: 0.0, -1: 90.0}              # 左(+X)は真上、右(-X)は90°進んだ位置(実機と同じく左右90°ずらす)
    wheels, rods = [], []
    for i, z in enumerate(drivers):
        part = f"$driver{i}"
        for s in (1, -1):
            m.add(part, mk.cylinder((s * 0.62, dr, z), (s * xo, dr, z), dr, n=16, tag="wheel"))
            ph = math.radians(phases[s])
            # 釣り合いおもり(クランクの反対側)。回転が見えるよう車輪の外側の面に付ける
            cy, cz = dr - dr * 0.55 * math.cos(ph), z - dr * 0.55 * math.sin(ph)
            m.add(part, mk.box(s * (xo + 0.015), cy, cz, 0.03, dr * 0.45 if s == 1 else dr * 0.7, dr * 0.7 if s == 1 else dr * 0.45, tag="rod"))
            # クランクのボス
            m.add(part, mk.box(s * (xo + 0.02), dr + rc * math.cos(ph) * 0.5, z + rc * math.sin(ph) * 0.5, 0.04,
                               0.14 + abs(math.cos(ph)) * rc, 0.14 + abs(math.sin(ph)) * rc, tag="rod"))
        m.add(part, mk.cylinder((-0.65, dr, z), (0.65, dr, z), 0.12, n=8, tag="under"))
        wheels.append({"part": part, "pivot_y": round(dr, 3), "pivot_z": round(z, 3), "radius": round(dr, 3)})
    main_i = len(drivers) // 2
    cyl_y = rf + 0.25
    cyl_rear = zf - 2.3
    for s in (1, -1):
        side = "l" if s == 1 else "r"
        ph = math.radians(phases[s])
        # 連結棒: クランクピンと一緒に平行移動する
        py, pzo = dr + rc * math.cos(ph), rc * math.sin(ph)
        zmid = (drivers[0] + drivers[-1]) / 2 + pzo
        m.add(f"$rod_{side}", mk.box(s * (xo + 0.07), py, zmid, 0.06, 0.13, abs(drivers[0] - drivers[-1]) + 0.3, tag="rod"))
        for z in drivers:
            m.add(f"$rod_{side}", mk.cylinder((s * (xo + 0.03), py, z + pzo), (s * (xo + 0.11), py, z + pzo), 0.07, n=6, tag="rod"))
        rods.append({"part": f"$rod_{side}", "type": "coupling", "axle_y": round(dr, 3), "axle_z": round(drivers[0], 3),
                     "crank_radius": rc, "wheel_radius": round(dr, 3), "phase_deg": phases[s]})
        # 主連棒・クロスヘッド: 左のクランク角0でクロスヘッドがシリンダー後端の少し後ろに来る長さにする
        az = drivers[main_i]
        head_ref = cyl_rear - 0.3 - rc
        length = math.hypot(head_ref - az, cyl_y - (dr + rc))
        pin_y, pin_z = dr + rc * math.cos(ph), az + rc * math.sin(ph)
        head_z = pin_z + math.sqrt(length ** 2 - (pin_y - cyl_y) ** 2)
        xm = s * (xo + 0.16)
        m.add(f"$mrod_{side}", mk.cylinder((xm, pin_y, pin_z), (xm, cyl_y, head_z), 0.065, n=6, tag="rod"))
        m.add(f"$mrod_{side}", mk.cylinder((s * (xo + 0.11), pin_y, pin_z), (s * (xo + 0.21), pin_y, pin_z), 0.1, n=8, tag="rod"))
        m.add(f"$xhead_{side}", mk.box((xm + s * 0.85) / 2, cyl_y, head_z, abs(xm - s * 0.85) + 0.1, 0.22, 0.3, tag="rod"))
        m.add(f"$xhead_{side}", mk.cylinder((s * 0.85, cyl_y, head_z), (s * 0.85, cyl_y, cyl_rear + 0.5), 0.045, n=6, tag="rod"))
        common = {"axle_y": round(dr, 3), "axle_z": round(az, 3), "crank_radius": rc, "wheel_radius": round(dr, 3),
                  "phase_deg": phases[s], "length": round(length, 4), "crosshead_y": round(cyl_y, 3), "direction": 1.0}
        rods.append(dict({"part": f"$mrod_{side}", "type": "main"}, **common))
        rods.append(dict({"part": f"$xhead_{side}", "type": "crosshead"}, **common))
    # 車体を載せるのは前後端の動輪。先台車・従台車・炭水車の台車は線路に追随するだけ(carry=false)
    track = [{"pivot_x": 0.0, "pivot_y": round(dr, 3), "pivot_z": round(drivers[0], 3)},
             {"pivot_x": 0.0, "pivot_y": round(dr, 3), "pivot_z": round(drivers[-1], 3)}]
    for i, (z, r) in enumerate(spec.get("small_wheels", [])):
        part = f"$truck{i}"
        wheelset(m, part, z, r)
        for s in (1, -1):
            m.add(part, mk.box(s * (G / 2 - 0.05), r, z, 0.1, 0.25, 2 * r + 0.2, tag="bogie"))
        m.add(part, mk.box(0, (r + rf) / 2, z, 1.2, rf - r + 0.1, 0.4, tag="under"))
        track.append({"part": part, "pivot_x": 0.0, "pivot_y": round(r, 3), "pivot_z": round(z, 3), "carry": False})
    articulated = []
    if spec.get("tender"):
        tl, tz = spec["tender_len"], spec["tender_z"]
        th = spec["tender_h"]
        m.add("$tender", mk.box(0, (1.2 + th) / 2, tz, 2.8, th - 1.2, tl, tag="tender"))
        m.add("$tender", mk.box(0, th + 0.12, tz + tl * 0.18, 2.2, 0.3, tl * 0.55, tag="coal"))
        m.add("$tender", mk.box(0, 1.05, tz, 1.8, 0.4, tl - 0.4, tag="under"))
        zr, zfb = tz - tl / 2 + 1.6, tz + tl / 2 - 1.6
        bogie(m, "$tbogie_r", zr, wb=1.5, r=0.43, top=1.15)
        bogie(m, "$tbogie_f", zfb, wb=1.5, r=0.43, top=1.15)
        track.append({"part": "$tbogie_f", "pivot_x": 0.0, "pivot_y": 0.43, "pivot_z": round(zfb, 3), "carry": False})
        track.append({"part": "$tbogie_r", "pivot_x": 0.0, "pivot_y": 0.43, "pivot_z": round(zr, 3), "carry": False})
        # 炭水車は前後の台車をそれぞれ線路に載せた独立の車体として表示する(機関車の後端と同じく曲線の外側へ張り出す)。
        # 機関車と炭水車のつなぎ(ドローバー)は中間で突き合わせる
        tf = tz + tl / 2
        mid = (cz1 - cl + tf) / 2
        m.add("body", mk.box(0, 1.3, (cz1 - cl + mid) / 2 + 0.05, 1.0, 0.35, abs(cz1 - cl - mid) + 0.3, tag="under"))
        m.add("$tender", mk.box(0, 1.3, (tf + mid) / 2 - 0.05, 0.9, 0.3, abs(mid - tf) + 0.3, tag="under"))
        articulated.append({"part": "$tender", "hinge_y": 1.3, "hinge_z": round(tf, 3), "rear_z": round(zr, 3), "front_z": round(zfb, 3)})
    else:
        for s in (1, -1):   # サイドタンク
            m.add("body", mk.box(s * (br + 0.28), by - 0.1, bz0 + spec["boiler_len"] * 0.45, 0.7, 1.2, spec["boiler_len"] * 0.55, tag="tender"))
        m.add("body", mk.box(0, (rf + ct - 0.4) / 2, cz1 - cl - 0.6, 2.4, ct - 0.4 - rf, 1.2, tag="tender"))  # 炭庫
    spec["track_points"] = track
    spec["anim"] = {"articulated": articulated, "wheels": wheels, "rods": rods}
    return m, rf + 0.25

# ---------------------------------------------------------------- 機体JSON
def seat(name, x, y, z, driver=False):
    s = {"name": name, "offset_x": round(x, 2), "offset_y": round(y, 2), "offset_z": round(z, 2)}
    if driver:
        s["driver"] = True
    return s

def vehicle_json(ns, spec, floor, zmin, zmax):
    kind = spec["kind"]
    powered = spec.get("powered", False)
    j = {"entity_type": "railwayvehicleaddon:rail_vehicle",
         "model": f"{ns}:models/obj/{spec['id']}.obj",
         "texture": f"{ns}:textures/vehicle/{spec['id']}.png",
         "display_name": spec["name"],
         "hud": spec.get("hud", "railcar_notch_5" if powered else "blank"),
         "scale": 1.0, "width": round(min(spec.get("W", 2.9), 3.4), 2), "height": round(spec.get("H", 4.0), 2),
         "max_speed": round(spec["kmh"] / 72.0, 3), "acceleration": spec.get("acc", 0.02), "throttle_up_down": 2.0,
         "max_health": spec.get("hp", 300.0)}
    src = spec.get("source", "fuel")
    if not powered or src == "electric":
        j["fuel_consumption"] = -1.0
    elif src == "steam":
        j["max_fuel"] = 1000.0; j["fuel_consumption"] = 0.3; j["inventory_size"] = 9
    else:
        j["max_fuel"] = 2000.0; j["fuel_consumption"] = 0.25
    seats = []
    if powered:
        if "_driver_pos" in spec:
            dx, dy, dz = spec["_driver_pos"]
            seats.append(seat("driver", dx, dy, dz, True))
        else:
            dz = spec.get("driver_z", zmax - 1.6)
            seats.append(seat("driver", spec.get("driver_x", 0.7), spec.get("seat_y", floor + 0.05), dz, True))
    doors = [zc for zc, _ in spec.get("windows", {}).get("doors", [])]
    if spec.get("_seat_pos"):
        # 客室の座席(車内の座席の位置に合わせて生成)。降りるときは最寄りの扉の外に立つ
        sy = spec.get("_seat_y", rider_y(floor))
        for i, (x, z) in enumerate(spec["_seat_pos"]):
            st = seat(f"seat{i + 1}", x, sy, z)
            st["dismount_x"] = round((1 if x >= 0 else -1) * (spec.get("W", 2.9) / 2 + 0.8), 2)
            if doors:
                st["dismount_z"] = round(min(doors, key=lambda d: abs(d - z)), 2)
            seats.append(st)
    else:
        for i, (x, z) in enumerate(spec.get("seats", [])):
            seats.append(seat(f"seat{i + 1}", x, spec.get("seat_y", floor + 0.05), z))
    if not seats:
        seats.append(seat("seat1", 0.6, floor + 0.05, 0.0))
    j["seats"] = seats
    j["spawn_item"] = {"display_name": spec["name"], "tier": spec.get("tier", 2)}
    rail = {"bogies": list(spec["track_points"]) + spec.get("_aux_track", []), "brake": spec.get("brake", 0.015), "resistance": spec.get("resistance", 0.0003),
            "grade_gravity": 0.04, "mass": spec.get("mass", 1.0),
            "coupler_front": round(zmax + COUPLER_REACH, 2), "coupler_rear": round(zmin - COUPLER_REACH, 2)}
    motor = spec.get("motor")            # None=既定(運転席があれば動力車)。電車の電動車・制御車で指定する
    if motor is not None:
        rail["powered"] = motor
    if powered or motor:
        rail["power_source"] = src
    if powered and spec.get("notches", True) and src != "steam":
        rail["power_notches"] = 5; rail["reverse_notches"] = 1
    if spec.get("smoke"):
        rail["smoke"] = spec["smoke"]
    rail["sounds"] = sound_set(spec, powered, motor, src)
    for k, v in spec.get("anim", {}).items():
        if v:
            rail[k] = v
    j["rail"] = rail
    return j

def add_couplers(m, spec, zmin, zmax, floor):
    """連結器(自動連結器の頭・胴と、台枠の端の受け)。連結面はJSONのcoupler_front/rearと同じ位置(車体端+COUPLER_REACH)。
    新幹線の先頭(流線形の鼻)は連結器がカバーの中に収まるので付けない。"""
    yc = 0.88                                          # 連結器の高さ(レール面から約880mm)
    for sgn, zend in ((1, zmax), (-1, zmin)):
        end_kind = spec.get("front" if sgn > 0 else "rear", "flat")
        if end_kind in ("sk0", "e5"):
            continue
        face = zend + sgn * COUPLER_REACH
        # 炭水車付きの蒸気機関車の後部連結器は炭水車と一緒に動く(炭水車は線路に沿って別に置かれるため)
        part = "$tender" if sgn < 0 and spec.get("tender") else "body"
        top = max(floor - 0.05, yc + 0.25)
        # 台枠の端の受け(胴受け)。車体の床下から連結器の高さまで下ろす
        m.add(part, mk.box(0, (yc - 0.16 + top) / 2, zend - sgn * 0.25, 0.7, top - yc + 0.16, 0.5, tag="coupler"))
        # 連結器の胴(シャンク)
        m.add(part, mk.box(0, yc, (zend - sgn * 0.1 + face - sgn * 0.2) / 2, 0.22, 0.2, abs(face - sgn * 0.2 - zend) + 0.1, tag="coupler"))
        # 連結器の頭(ナックル)。片側に寄った形
        m.add(part, mk.box(0.04, yc, face - sgn * 0.12, 0.46, 0.34, 0.24, tag="coupler"))
        m.add(part, mk.box(-0.2, yc, face - sgn * 0.05, 0.1, 0.3, 0.1, tag="coupler"))
        # 解放てこ(車体の端の下に横向き)
        m.add(part, mk.box(0.45, top - 0.05, zend - sgn * 0.3, 0.9, 0.04, 0.04, tag="coupler"))
        # ブレーキ管・空気管のホース
        for x in (0.28, -0.28):
            m.add(part, mk.cylinder((x, yc + 0.05, zend - sgn * 0.2), (x, yc - 0.25, zend + sgn * 0.2), 0.035, n=6, tag="hose"))

def gangway_ends(spec, ns):
    """貫通路(幌)を付ける車端(+1=前、-1=後)。旅客車の、運転台でも流線形でもない平らな端"""
    if ns != "japanpassenger" or spec["builder"] != "car":
        return ()
    cab_signs = {c[1] for c in spec.get("windows", {}).get("cabs", [])}
    ends = []
    for sgn in (1, -1):
        kind = spec.get("front" if sgn > 0 else "rear", "flat")
        if kind == "flat" and sgn not in cab_signs:
            ends.append(sgn)
    return tuple(ends)

def add_gangways(m, spec, floor):
    """幌(貫通路のほろ)。扉が見えるようにコの字の枠にする"""
    L = spec.get("L", 10)
    for sgn in spec.get("_gangway_ends", ()):
        z = sgn * (L / 2 + COUPLER_REACH - 0.15)   # 幌の先端が連結面にそろう(隣の車両の幌と突き合わさる)
        for x in (0.52, -0.52):
            m.add("body", mk.box(x, floor + 1.0, z, 0.16, 2.0, 0.3, tag="gangway"))
        m.add("body", mk.box(0, floor + 2.06, z, 1.2, 0.16, 0.3, tag="gangway"))
        m.add("body", mk.box(0, floor + 0.02, z, 1.2, 0.06, 0.3, tag="gangway"))   # 渡り板

def detail_pass(atlas, spec, L, W, H, floor, ns):
    """汚し・墨入れ・配線(meshkit.detail)。旅客車は軽く、機関車・貨車は強めに"""
    kind = spec.get("kind")
    freight = ns == "japanfreight" and kind != "steam" and not spec.get("powered")
    loco = kind in ("steam", "dl") or spec["id"].split("_")[1].startswith(("ef", "eh", "df"))
    level = 0.55 if freight else 0.45 if kind == "steam" else 0.38 if loco else 0.22
    body_tags = [t for t in ("body", "boiler", "smokebox", "cab", "tender") if t in atlas.tag_ids]
    detail.tone(atlas, body_tags, amount=0.03 + level * 0.04, scale=1.6)
    # 配線: 屋根上の特別高圧引き通し線・母線(パンタグラフから車端へ、またはパンタグラフ間)
    pz = [pe[0] for pe in spec.get("pantographs", [])]
    if pz:
        z0, z1 = (min(pz), max(pz)) if len(pz) > 1 else (min(pz[0], 0.0) - 0.0, L / 2 - 0.4) if pz[0] > 0 else (-L / 2 + 0.4, pz[0])
        def wires(X, Y, Z, cur):
            m = (Y > H - 0.4) & (Z > z0 - 0.6) & (Z < z1 + 0.6) & ((np.abs(X - 0.32) < 0.035) | (np.abs(X + 0.32) < 0.02))
            m |= (Y > H - 0.4) & (np.abs(X) < 0.32) & ((np.abs(Z - z0) < 0.03) | (np.abs(Z - z1) < 0.03))     # 碍子への渡り
            out = cur.copy(); out[m] = out[m] * 0.35 + np.array([40, 40, 42], np.float32) * 0.65
            return out, m
        atlas.paint3d(wires, tags=["body"], kinds=("top", "side", "front"))
    # 配線: 床下の引き通し線・空気管(側面に横線)
    if "under" in atlas.tag_ids:
        def pipes(X, Y, Z, cur):
            m = (np.abs(Y - (floor - 0.22)) < 0.018) | (np.abs(Y - (floor - 0.36)) < 0.012)
            m |= (np.abs((Z % 1.7) - 0.85) < 0.015) & (Y < floor - 0.1)   # 吊り金具
            out = cur.copy(); out[m] *= 0.55
            return out, m
        atlas.paint3d(pipes, tags=["under"], kinds=("side",))
    # 車体側面のジャンパ線(電車の車端)
    if spec.get("motor") or spec.get("source") == "electric":
        def jumpers(X, Y, Z, cur):
            m = (np.abs(np.abs(Z) - (L / 2 - 0.25)) < 0.04) & (Y > floor - 0.15) & (Y < floor + 0.25) & (np.abs(X) > W / 2 * 0.8)
            out = cur.copy(); out[m] = np.array([45, 45, 48], np.float32)
            return out, m
        atlas.paint3d(jumpers, tags=["body"], kinds=("side",))
    detail.ink(atlas, strength=0.3 + level * 0.2, halo=0.1)
    # 汚し: 下回りの錆・ブレーキ粉、車体下部の汚れ、雨だれ
    rust = (92, 70, 52)
    dark = [t for t in ("bogie", "under", "wheel", "coupler", "hose", "rod") if t in atlas.tag_ids]
    if dark:
        detail.grime(atlas, dark, y_top=floor + 0.3, color=rust, amount=0.35 + level * 0.6, scale=0.5, seed=7)
    if body_tags:
        detail.grime(atlas, body_tags, y_top=floor + 0.6 + level, color=(88, 78, 64), amount=level * 0.7, scale=0.8, y_bottom=floor - 0.4)
        detail.streaks(atlas, body_tags, amount=0.04 + level * 0.18, width=0.3)
        if level >= 0.38:
            detail.chips(atlas, body_tags, density=0.01 + level * 0.03)
        if pz:
            detail.grime(atlas, ["body"], y_top=H + 1.0, color=(70, 58, 46), amount=0.35, scale=0.4, kinds=("top",), y_bottom=H - 0.6)  # 架線の摩耗粉
    if kind == "steam":
        chimney = (0.0, spec["boiler_y"] + spec["boiler_r"] + spec["chimney_h"], spec["zf"] - 1.25)
        detail.soot(atlas, body_tags, chimney, radius=3.5, amount=0.6)
    if kind == "dl":
        for sm in spec.get("smoke", []):
            detail.soot(atlas, body_tags, (sm["x"], sm["y"], sm["z"]), radius=2.2, amount=0.5)

def sound_set(spec, powered, motor, src):
    """動作音(アドオンMOD内蔵の音を車種に合わせて選ぶ)。国鉄時代の電気車は抵抗制御の唸り、JR発足後の新形式はVVVFの音"""
    snd = {"volume": 1.0}
    has_motor = motor if motor is not None else powered
    if src == "electric" and has_motor:
        if spec["id"].startswith("jnr_"):
            snd["motor"] = "rva_motor_dc"
            snd["motor_pitch_min"], snd["motor_pitch_max"] = 0.6, 1.8
        else:
            snd["motor"] = "rva_motor_vvvf"
    elif src == "electric" and powered:
        snd["motor"] = ""                # 運転台だけの制御車(クハ等)は主電動機の音を出さない
    if spec.get("kind") == "steam":
        snd["volume"] = 1.3
    elif src == "fuel" and powered:
        snd["volume"] = 1.2
    # 在来線は標準の25mレール(既定のjoint_spacing=25)。新幹線はロングレールなので継ぎ目はまれ
    if spec["id"].startswith(("jnr_0_", "jr_e5_")):
        snd["joint_spacing"] = 200.0     # 新幹線はロングレール(継ぎ目はまれ)
    snd.update(spec.get("sounds", {}))
    return snd

def write_vehicle(root, ns, spec):
    builder = {"car": build_car, "hood": build_hood_loco, "steam": build_steam}[spec["builder"]]
    spec["_aux_track"] = []
    spec["_gangway_ends"] = gangway_ends(spec, ns)
    m, floor = builder(spec)
    L, W, H = spec.get("L", 10), spec.get("W", 2.9), spec.get("H", 4.0)
    # 連結器の位置(JSONのcoupler_front/rear)は、連結器・幌を足す前の車体の端から決める
    pts0 = m.all_points()
    zmin0, zmax0 = float(pts0[:, 2].min()), float(pts0[:, 2].max())
    add_couplers(m, spec, zmin0, zmax0, floor)
    add_gangways(m, spec, floor)
    liv = spec["livery"]
    flat = {"wheel": (38, 38, 40), "bogie": (48, 48, 50), "under": (40, 40, 42), "panto": (70, 70, 74),
            "roofunit": liv.get("roof", (150, 152, 156)), "rod": (150, 150, 155), "beam": (170, 40, 35), "endbeam": (150, 152, 155), "cabcol": (150, 152, 155), "dlroof": liv.get("roof", (150, 152, 155)), "visor": (38, 44, 60),
            "rail": (215, 215, 212), "lamprim": (60, 60, 64), "headlamp": (250, 240, 200), "taillamp": (190, 25, 30),
            "coal": (25, 25, 25), "cabroof": (30, 30, 32), "coupler": (52, 50, 48), "hose": (30, 30, 32),
            "gangway": (58, 58, 60), "insulator": (205, 205, 200), "skirt": (60, 60, 64), "antenna": (55, 55, 58),
            "acfan": (60, 62, 66), "plow": liv.get("plow", (60, 60, 64)), "skirt485": liv.get("base", (200, 200, 200)), "seat": liv.get("seat", (70, 88, 140)), "console": (78, 82, 88), "panel": (38, 40, 42)}
    flat.update(liv.get("flat", {}))
    colors = {t: liv.get("base") for t in ("body",)}
    colors["lining"] = liv.get("lining", (222, 218, 204))
    colors["liner"] = colors["lining"]
    for t in ("boiler", "smokebox", "cab", "tender"):
        colors[t] = liv.get(t, liv.get("base"))
    paint_fn = make_paint(spec, L, W, H, floor) if spec["builder"] != "steam" else None
    def paint(atlas, model):
        if paint_fn is not None:
            atlas.paint3d(paint_fn, tags=["body"])
        if spec["builder"] == "steam":
            def cabwin(X, Y, Z, cur):
                cz = spec["zf"] - spec["boiler_len"] - 0.2
                sel = (Y > spec["cab_top"] - 1.1) & (Y < spec["cab_top"] - 0.35) & (np.abs(Z - (cz - spec["cab_len"] / 2)) < spec["cab_len"] * 0.3)
                return np.where(sel[:, None], [35, 40, 46], cur).astype(np.float32), None
            atlas.paint3d(cabwin, tags=["cab"])
        for key, sgn in (("front", 1), ("rear", -1)):
            if spec.get(key) == "103":
                # 103系: 前面(前を向いた面)は最上部まで車体の色。屋根の色は屋根の面だけで、境は前端の角で明確に分かれる
                fn_nr = make_paint(dict(spec, livery={k: v for k, v in spec["livery"].items() if k != "roof"}), L, W, H, floor)
                def front_face(X, Y, Z, cur, fn_nr=fn_nr, sgn=sgn):
                    out_, _ = fn_nr(X, Y, Z, cur)
                    return out_, (L / 2 - sgn * Z) < 0.3
                atlas.paint3d(front_face, tags=["body"], kinds=("front",))
        clean = atlas.img.copy()
        detail_pass(atlas, spec, L, W, H, floor, ns)
        # 流線形の鼻(0系・E5系・ボンネット)は汚し・墨入れを掛けない: 細かい面の折れ目を墨入れが拾い、
        # 面ごとの色むらと重なって、ディテールではなく汚れの斑点に見えるため。鼻の付け根で車体の汚しへ徐々につなぐ
        NOSE = {"sk0": (3.0, 4.2), "e5": (9.0, 11.3), "bonnet": (2.6, 3.6), "103": (0.25, 0.3), "ef66": (1.2, 1.6), "kuno": (0.6, 0.9), "ef58": (0.6, 1.0)}   # 103系: 前面は汚さない(簡素な塗り分け)
        for key, sgn in (("front", 1), ("rear", -1)):
            if spec.get(key) in NOSE:
                a, b = NOSE[spec[key]]
                msk = atlas.mask(["body"])
                if msk.any():
                    d = L / 2 - sgn * atlas.pmap[msk][:, 2]
                    k = np.clip((b - d) / (b - a), 0, 1)[:, None]
                    atlas.img[msk] = atlas.img[msk] * (1 - k) + clean[msk] * k
        # 車内の床・天井の色
        fl_y = spec.get("_cab_floor", floor)
        def inside(X, Y, Z, cur):
            out = cur.copy()
            out[Y < fl_y + 0.05] = rgb(liv.get("floor_in", (92, 88, 82)))
            out[Y > (spec.get("H", 4.0) - 0.35 if spec["builder"] != "steam" else spec["cab_top"] - 0.2)] = rgb((236, 236, 232))
            return out, None
        atlas.paint3d(inside, tags=["lining", "liner"])
        # 窓の透過: 外板の窓は半透明(細かい網目で透ける)、内張りの窓の位置は穴にする
        if spec["builder"] == "steam":
            cz = spec["zf"] - spec["boiler_len"] - 0.2
            wm = lambda X, Y, Z: (Y > spec["cab_top"] - 1.1) & (Y < spec["cab_top"] - 0.35) & (np.abs(Z - (cz - spec["cab_len"] / 2)) < spec["cab_len"] * 0.3)
            atlas.paint_alpha(lambda X, Y, Z: np.where(wm(X, Y, Z), GLASS_ALPHA, 255), tags=["cab"])
        else:
            wm = window_mask(spec, L, W, H, floor)
            atlas.paint_alpha(lambda X, Y, Z: np.where(wm(X, Y, Z), GLASS_ALPHA, 255), tags=["body"])
        if spec.get("_inner"):
            # 内張りの点を、同じ断面形の外板の点へ写してから窓かどうかを判定する。窓の真後ろの内張りだけに穴を開け、
            # 先頭部の細い所で窓と同じ高さにある内張りの側壁などに穴が開かないようにする
            inner_p, prof_p = spec["_inner"], spec["_prof"]
            def lwm(X, Y, Z):
                # 内張りは外板の断面を中心へ3cm縮めた形(lining)なので、その逆の拡大で外板の点を求める
                Xo = np.empty_like(X); Yo = np.empty_like(Y)
                for k in range(len(X)):
                    hwo, to, bo = inner_at(prof_p, Z[k])
                    c, ht = (to + bo) / 2, max((to - bo) / 2, 0.05)
                    Xo[k] = X[k] * hwo / max(hwo - 0.03, 1e-3)
                    Yo[k] = c + (Y[k] - c) * ht / (ht - 0.03)
                return wm(Xo, Yo, Z)
            atlas.cut_alpha(lwm, tags=["lining", "liner"])
        else:
            atlas.cut_alpha(wm, tags=["lining", "liner"])
    size = (512, 512) if L >= 12 or spec["builder"] == "steam" else (256, 256)
    texture_model(m, size, colors=colors, flat=flat, paint=paint, glass_tags=("lining", "liner"))
    rep = check.report(m)
    # 内張り(車内の壁)は内側から見せるため意図して裏返してある。検査の「inside-out」はその分を除く
    lining_shells = {f"{pn}[{k}]" for pn in m.order for k, sh in enumerate(m.parts[pn].shells) if sh.tags and all(t in ("lining", "liner") for t in sh.tags)}
    rep["issues"] = [x for x in rep["issues"] if not (x.split(":")[0] in lining_shells and "inside-out" in x)]
    rep["ok"] = not rep["issues"] and not rep["floating"]
    zmin, zmax = zmin0, zmax0
    os.makedirs(f"{root}/assets/{ns}/models/obj", exist_ok=True)
    os.makedirs(f"{root}/assets/{ns}/textures/vehicle", exist_ok=True)
    os.makedirs(f"{root}/data/{ns}/vehicles", exist_ok=True)
    mk.save(m, f"{root}/assets/{ns}/models/obj/{spec['id']}.obj")
    from PIL import Image
    tex = np.asarray(m.texture)
    if tex.dtype != np.uint8:
        tex = np.clip(tex * (255.0 if tex.max() <= 1.0 else 1.0), 0, 255).astype(np.uint8)
    Image.fromarray(tex).save(f"{root}/assets/{ns}/textures/vehicle/{spec['id']}.png")
    # meshkitはOBJと同名のmtl/pngを出すことがあるので、テクスチャはtextures側だけを使う
    for ext in (".mtl", ".png"):
        p = f"{root}/assets/{ns}/models/obj/{spec['id']}{ext}"
        if os.path.exists(p):
            os.remove(p)
    j = vehicle_json(ns, spec, floor, zmin, zmax)
    with open(f"{root}/data/{ns}/vehicles/{spec['id']}.json", "w", encoding="utf-8") as f:
        json.dump(j, f, ensure_ascii=False, indent=2)
    return m, rep
