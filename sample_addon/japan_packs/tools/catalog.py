"""車種カタログ。寸法はおおよその実寸(m)。塗色は国鉄・JRの代表的な色の近似値。番号・ロゴ等は描かない。"""
from japangen import bogie, aux_bogie, mk

# ---------------- 色
URUGUISU = (125, 190, 90); STAINLESS = (190, 193, 198); YELLOW1 = (245, 200, 0)
CREAM4 = (230, 220, 188); RED2 = (170, 30, 45); RED11 = (190, 70, 80); SHU5 = (205, 75, 55); SHU4 = (220, 90, 45)
IVORY = (240, 236, 222); BLUE20 = (25, 50, 115); BLUE15 = (40, 70, 140); CREAM1 = (225, 215, 170)
BLUE0 = (20, 70, 150)   # 0系の青(日本車輌の編成図 tec0-yk8 の色から)
ROSE = (190, 80, 100); BUDOU1 = (70, 42, 32); BUDOU2 = (95, 45, 35); WOOD = (120, 78, 42); BLACK = (36, 36, 38)
TOKIWA = (0, 105, 80); HIUN = (236, 236, 236); TSUTSUJI = (235, 110, 150); ROOF = (112, 114, 118); GRAY = (120, 122, 128)
DARKRED = (150, 32, 42); JRF_BLUE = (50, 80, 150); SILVER = (200, 202, 206)
GLASS = (40, 50, 58)

def seats_along(L, n=4, x=0.8):
    zs = [(-L / 2 + 2.5) + i * (L - 5.0) / max(1, n - 1) for i in range(n)]
    return [((x if i % 2 == 0 else -x), z) for i, z in enumerate(zs)]

def tp(bz, part=True, y=0.43):
    """台車(線路への載せ位置)。part=False なら回転させるOBJグループなし"""
    if part:
        return [{"part": "$bogie_front", "pivot_x": 0.0, "pivot_y": y, "pivot_z": bz},
                {"part": "$bogie_rear", "pivot_x": 0.0, "pivot_y": y, "pivot_z": -bz}]
    return [{"pivot_x": 0.0, "pivot_y": y, "pivot_z": bz}, {"pivot_x": 0.0, "pivot_y": y, "pivot_z": -bz}]

def car(id, name, L, W, H, livery, windows, bz, **kw):
    s = dict(builder="car", kind="car", id=id, name=name, L=L, W=W, H=H, livery=livery, windows=windows,
             bogie_z=bz, track_points=tp(bz, not kw.get("two_axle", False), y=kw.get("wheel_r", 0.43)), kmh=kw.pop("kmh", 100))
    s.update(kw)
    s.setdefault("seats", seats_along(L))
    return s

def win_commuter(L, floor, doors4=True, ww=1.0, pitch=1.22):
    d = [(-7.3, 1.3), (-2.45, 1.3), (2.45, 1.3), (7.3, 1.3)] if doors4 else []
    return {"rows": [(-L / 2 + 0.8, L / 2 - 0.8, ww, pitch, floor + 0.95, floor + 1.85)], "doors": d, "glass": GLASS}

def cabs(L, front=True, rear=False, inset=0.0, depth=0.6):
    c = []
    if front: c.append((L / 2 - inset, 1, depth))
    if rear: c.append((-L / 2 + inset, -1, depth))
    return c

def with_cabs(win, c, y0=None, y1=None, glass=None):
    w = dict(win); w["cabs"] = c
    if y0 is not None: w["cab_y0"] = y0
    if y1 is not None: w["cab_y1"] = y1
    if glass: w["glass"] = glass
    return w

def extra_bogies(lst, top, attach=False):
    """前後以外の台車。独立した部品にして線路に追随させる。attach=True なら前後の主台車と一緒に動く
    (EF58の先台車: 主台車に連結されているので、曲線で車体の外へ振れ出さない)"""
    def f(m, s):
        for z, axles, wb in lst:
            if attach:
                bogie(m, "$bogie_front" if z > 0 else "$bogie_rear", z, wb=wb, axles=axles, top=top)
            else:
                aux_bogie(m, s, z, wb=wb, axles=axles, top=top)
    return f

def containers(zs, cols, deck_top):
    def f(m, s):
        for i, z in enumerate(zs):
            m.add("body", mk.box(0, deck_top + 1.25 - 0.03, z, 2.45, 2.5, 3.6, tag=f"ctr{i % len(cols)}"))
    return f

def decks(L, floor):
    def f(m, s):
        for sgn in (1, -1):
            m.add("body", mk.box(0, floor - 0.05, sgn * (L / 2 + 0.45), 2.4, 0.12, 1.0, tag="under"))
    return f

def hopper(L, floor):
    def f(m, s):
        for z in (-L / 4, 0.0, L / 4):
            m.add("body", mk.box(0, floor - 0.45, z, 1.6, 0.9, 1.6, tag="under", ))
    return f

# ======================================================================== 旅客
def passenger():
    V = []
    fl = 1.15
    # ---- 蒸気機関車(連続スロットル式。排煙あり)
    def steam(id, name, kmh, **kw):
        s = dict(builder="steam", kind="steam", id=id, name=name, kmh=kmh, powered=True, source="steam", notches=False,
                 hud="railcar_throttle", acc=kw.pop("acc", 0.012), mass=kw.pop("mass", 4.0), hp=500.0,
                 livery={"base": BLACK, "flat": {}}, tier=2)
        s.update(kw)
        by, br = s["boiler_y"], s["boiler_r"]
        chz = s["zf"] - 0.6 - 0.65
        s["smoke"] = [{"x": 0.0, "y": round(by + br * 0.85 + s["chimney_h"] + 0.1, 2), "z": round(chz, 2),
                       "type": "campfire", "rate": 2.5, "idle_rate": 0.25}]
        cz = s["zf"] - s["boiler_len"] - 0.6 + 0.4 - s["cab_len"] / 2
        s["driver_x"], s["driver_z"], s["seat_y"] = 0.7, cz, s["frame_y"] + 0.35
        s["seats"] = [(-0.7, cz)]
        s["W"], s["H"] = 2.9, s["cab_top"] + 0.2
        return s
    V.append(steam("jnr_150", "150形蒸気機関車(1872年)", 45, acc=0.02, mass=2.0, zf=3.7, loco_len=7.0, boiler_r=0.5, boiler_y=1.95,
                   frame_y=1.0, boiler_len=3.6, chimney_h=1.25, chimney_r=0.17, chimney_flare=1.7, domes=[-2.0], cab_len=1.6,
                   cab_top=3.35, driver_r=0.66, drivers=[0.6, -0.9], small_wheels=[(2.4, 0.47)],
                   track_points=[{"pivot_x": 0.0, "pivot_y": 0.5, "pivot_z": 1.5}, {"pivot_x": 0.0, "pivot_y": 0.5, "pivot_z": -1.6}]))
    def tender_loco(id, name, kmh, total, loco_len, tl, th, drivers, smalls, **kw):
        zf = total / 2
        tz = zf - loco_len - tl / 2 - 0.2
        return steam(id, name, kmh, zf=zf, loco_len=loco_len, tender=True, tender_len=tl, tender_z=tz, tender_h=th,
                     drivers=[zf - d for d in drivers], small_wheels=[(zf - d, r) for d, r in smalls],
                     track_points=[{"pivot_x": 0.0, "pivot_y": 0.5, "pivot_z": round(zf - drivers[len(drivers) // 2], 2)},
                                   {"part": "$bogie_rear", "pivot_x": 0.0, "pivot_y": 0.43, "pivot_z": round(tz - tl / 2 + 1.6, 2)}], **kw)
    V.append(tender_loco("jnr_8620", "8620形蒸気機関車(1914年)", 95, 16.8, 10.0, 6.4, 3.0, [3.5, 5.3, 7.1], [(1.5, 0.43)],
                         boiler_r=0.72, boiler_y=2.35, frame_y=1.1, boiler_len=6.2, chimney_h=0.75, chimney_r=0.28, domes=[-2.6, -4.3],
                         cab_len=2.4, cab_top=3.75, driver_r=0.8))
    V.append(tender_loco("jnr_c57", "C57形蒸気機関車(1937年)", 100, 20.3, 12.5, 7.5, 3.3, [4.6, 6.6, 8.6], [(1.6, 0.43), (2.9, 0.43), (10.6, 0.5)],
                         boiler_r=0.82, boiler_y=2.55, frame_y=1.15, boiler_len=8.0, chimney_h=0.6, chimney_r=0.3, domes=[-3.0, -4.6],
                         cab_len=2.6, cab_top=3.9, driver_r=0.875, deflectors=True))
    V.append(tender_loco("jnr_d51", "D51形蒸気機関車(1936年)", 85, 19.7, 12.2, 7.2, 3.3, [4.2, 5.75, 7.3, 8.85], [(1.8, 0.43), (10.4, 0.5)],
                         boiler_r=0.85, boiler_y=2.6, frame_y=1.15, boiler_len=8.0, chimney_h=0.55, chimney_r=0.3, domes=[-2.6, -3.5, -4.4],
                         cab_len=2.6, cab_top=3.9, driver_r=0.7, deflectors=True))
    V.append(tender_loco("jnr_c62", "C62形蒸気機関車(1948年)", 100, 21.5, 13.3, 7.8, 3.4, [4.8, 6.8, 8.8], [(1.6, 0.43), (2.9, 0.43), (10.6, 0.5), (11.6, 0.5)],
                         boiler_r=0.95, boiler_y=2.75, frame_y=1.2, boiler_len=8.6, chimney_h=0.45, chimney_r=0.32, domes=[-3.2, -4.8],
                         cab_len=2.7, cab_top=3.95, driver_r=0.875, deflectors=True))
    # ---- 客車
    V.append(car("jnr_coach_meiji", "二軸客車(明治期)", 6.6, 2.2, 3.3, {"base": WOOD, "roof": (60, 60, 62)},
                 {"rows": [(-2.9, 2.9, 0.42, 0.75, fl + 0.85, fl + 1.5)], "doors": [(-2.25, 0.55), (-0.75, 0.55), (0.75, 0.55), (2.25, 0.55)], "glass": GLASS},
                 1.6, two_axle=True, kmh=45, seats=[(0.5, 1.5), (-0.5, -1.5)], mass=0.6, tier=1))
    V.append(car("jnr_oha31", "オハ31形客車(1927年)", 17.0, 2.8, 3.55, {"base": BUDOU1, "roof": (50, 50, 52)},
                 {"rows": [(-7.6, 7.6, 0.6, 0.85, fl + 0.85, fl + 1.65)], "doors": [(-8.1, 0.8), (8.1, 0.8)], "glass": GLASS},
                 6.0, wb=1.8, clerestory=True, kmh=95, mass=1.2))
    V.append(car("jnr_suha43", "スハ43形客車(1951年)", 20.0, 2.9, 3.85, {"base": BUDOU2, "roof": (60, 60, 62)},
                 {"rows": [(-9.0, 9.0, 0.7, 1.0, fl + 0.85, fl + 1.7)], "doors": [(-9.3, 0.8), (9.3, 0.8)], "glass": GLASS},
                 7.0, kmh=95, mass=1.3))
    blue_train = {"base": BLUE20, "bands": [(fl - 0.12, fl - 0.06, SILVER), (fl - 0.02, fl + 0.04, SILVER), (fl + 0.08, fl + 0.14, SILVER)], "roof": (150, 152, 158)}
    bt_win = {"rows": [(-9.0, 9.0, 1.0, 1.55, fl + 0.95, fl + 1.75)], "doors": [(-9.6, 0.8), (9.6, 0.8)], "glass": GLASS}
    V.append(car("jnr_ohane25", "オハネ25形客車(24系25形・B寝台)", 20.8, 2.95, 4.0, blue_train, bt_win, 7.15, kmh=110, mass=1.3,
                 roof_units=[(-6.5, 1.3), (-2.2, 1.3), (2.2, 1.3), (6.5, 1.3)]))
    V.append(car("jnr_ohanefu25", "オハネフ25形客車(24系25形・B寝台・緩急車)", 20.8, 2.95, 4.0, blue_train,
                 with_cabs(bt_win, cabs(20.8, front=False, rear=True)), 7.15, kmh=110, mass=1.3,
                 roof_units=[(-4.5, 1.3), (0.0, 1.3), (4.5, 1.3)]))
    # ---- 電気機関車
    def el(id, name, L, livery, bz, pantos, front, extra=None, mid=True, kmh=100, single=False, lamps="top2", rows=None, cab_y=None, win_extra=None, **kw):
        flL = 1.55
        rows = [(-L / 2 + 3.0, L / 2 - 3.0, 0.6, 1.5, flL + 1.15, flL + 1.65)] if rows is None else rows
        w = with_cabs({"rows": rows, "glass": GLASS}, cabs(L, True, True), *(cab_y or (flL + 1.05, flL + 1.85)))
        w["front_style"] = "split"                   # 電気機関車の前面: 中央に柱のある2枚窓
        w.update(win_extra or {})
        return car(id, name, L, 2.8, 3.95, livery, w, bz, floor=flL, front=front, rear=front, mid_bogie=mid, wb=kw.pop("wb", 2.3),
                   pantographs=[(z, single) for z in pantos], powered=True, source="electric", acc=0.012, mass=3.5,
                   kmh=kmh, hp=450.0, seats=[(-0.7, L / 2 - 1.6)], driver_z=L / 2 - 1.6, extra=[extra] if extra else [], tier=2,
                   lamp_style=lamps, **kw)
    def cab_win(L, flL=1.55):                        # 運転室の側窓だけ(前後の端に1つずつ。乗務員扉の前)
        return [(L / 2 - 1.15, L / 2 - 0.35, 0.6, 0.8, flL + 1.15, flL + 1.75), (-L / 2 + 0.35, -L / 2 + 1.15, 0.6, 0.8, flL + 1.15, flL + 1.75)]
    def louvers(L, flL=1.55, n=6):                   # 機器室の側面のよろい戸
        span = L - 6.4
        return [(-span / 2 + k * span / n + 0.25, -span / 2 + (k + 1) * span / n - 0.25, flL + 1.2, flL + 1.85) for k in range(n)]
    # EF58: 前面は上から見てくの字、運転室の窓の少し下から上は後ろへ傾く。前照灯は前面上部の飛び出した円筒。
    # 側窓は5つを広い間隔で等間隔に。主台車(3軸)は中央寄りの1軸が他の2軸から離れている
    V.append(el("jnr_ef58", "EF58形電気機関車(1946年)", 19.9, {"base": BLUE15, "bands": [(1.4, 1.85, CREAM1)], "roof": ROOF},
                3.6, [4.7, -4.7], "ef58", extra=extra_bogies([(7.9, 2, 1.8), (-7.9, 2, 1.8)], 1.4, attach=True), mid=False, kmh=95, lamps="roof",
                rows=[(-6.0, 6.0, 0.7, 2.4, 1.55 + 1.15, 1.55 + 1.7)], axle_offsets=[1.55, -0.05, -2.45], wheel_r=0.625))   # 動輪 直径約1.25m
    V[-1]["axles"] = 3
    # EF65・EF81: 他の電気機関車より直線的な箱形。側面に客車のような窓はない(運転室の側窓と機器室のよろい戸)
    # EF65 1000番台(PF形。写真による): 前面中央に貫通扉。前面と運転室の側面(扉の前)はクリーム色、下端の帯と車体は青。
    # 前照灯は屋根の前の縁の左右、尾灯は前面下部の左右。側面の下に細いクリーム色の帯
    V.append(el("jnr_ef65", "EF65形電気機関車(1000番台・1969年)", 16.5, {"base": BLUE15, "bands": [(1.66, 1.72, CREAM1)], "roof": ROOF}, 5.25, [5.65, -5.65], "ef65pf", kmh=110,
                rows=cab_win(16.5), p=9.0, side_louvers=louvers(16.5, n=5), wheel_r=0.625, wb=2.8, lamps="none",
                cab_y=(2.9, 3.45), win_extra={"front_style": "pf", "cab_xmax": 0.89, "win_frame": 0.035, "frame_color": (40, 40, 42)}))
    # EF81(写真による): 前面の2枚の窓は銀色の枠でほぼ全幅(中央の柱は細い)、外側は角の丸みへ回り込む。屋根の前の縁の左右に前照灯、
    # 窓の下に銀色の帯(中央は番号板)、前面下部は灰色の端梁。屋根上は前のパンタグラフの後ろに大きな灰色の機器箱、その後ろに
    # 低い濃い灰色の機器カバー(碍子は緑色)
    V.append(el("jnr_ef81", "EF81形電気機関車(1968年)", 18.6, {"base": ROSE, "bands": [], "roof": ROOF, "flat": {"insulator": (70, 160, 135), "roofunit": (168, 172, 175)}},
                5.8, [6.7, -6.7], "ef81", kmh=110,
                rows=cab_win(18.6), p=9.0, side_louvers=louvers(18.6, n=6), wheel_r=0.625, wb=2.8, lamps="none",
                cab_y=(2.89, 3.47), win_extra={"cab_xmax": 0.995, "pillars": [("ax", 1.11, 1.15)], "frame_color": (190, 192, 196)},
                roof_boxes=[(5.95, 0.2, 1.7, 0.78, "roofunit", 0.35), (0.0, -5.0, 1.9, 0.32, "acfan", 0.25)]))
    # ---- ディーゼル機関車
    V.append(dict(builder="hood", kind="dl", id="jnr_dd51", name="DD51形ディーゼル機関車(1962年)", L=18.0, W=2.9, H=3.95,
                  cab_z=0.0, cab_len=3.0, hoods=[(-8.1, -1.5), (1.5, 8.1)], bogies=[(5.8, 2), (0.0, 2), (-5.8, 2)],
                  cab_boxes=[(1.5, 4.8), (-4.8, -1.5)],          # 運転室の前後の、機関部の箱の根元の低い箱(機関部より幅が広く、機関部の長さの約半分)
                  livery={"base": SHU4, "bands": [(1.2, 1.5, GRAY), (2.9, 2.97, (235, 235, 235))], "roof": GRAY, "roof_h": 0.03},
                  windows=dict(with_cabs({"rows": [(0.0, 1.2, 0.8, 1.2, 3.0, 3.55)], "glass": GLASS}, [(1.5, 1, 0.1), (-1.5, -1, 0.1)], 3.1, 3.62),
                               front_style="hood"), hood_doors=[-0.75],      # 運転室の側面: 窓1つと乗務員扉
                  track_points=tp(5.8, y=0.525), wheel_r=0.525, powered=True, source="fuel", acc=0.012, mass=3.5, kmh=95, hp=450.0,
                  driver_x=0.7, driver_z=0.6, seat_y=1.4, seats=[(-0.7, -0.6)],
                  smoke=[{"x": 0.0, "y": 3.15, "z": 4.5, "type": "smoke", "rate": 0.6, "idle_rate": 0.08},
                         {"x": 0.0, "y": 3.15, "z": -4.5, "type": "smoke", "rate": 0.6, "idle_rate": 0.08}]))
    V.append(dict(builder="hood", kind="dl", id="jnr_de10", name="DE10形ディーゼル機関車(1966年)", L=14.15, W=2.9, H=3.95,
                  cab_z=-2.2, cab_len=2.6, hoods=[(-6.3, -3.5), (-0.9, 6.3)], bogies=[(3.6, 3), (-5.0, 2)], visor=0.0,   # 屋根のひさしは無い
                  cab_boxes=[(-0.9, 2.7), (-4.9, -3.5)],         # 運転室の前後の低い箱(後部の機関部は短いので、その長さの約半分)
                  livery={"base": SHU4, "bands": [(1.2, 1.5, GRAY), (2.9, 2.97, (235, 235, 235))], "roof": GRAY, "roof_h": 0.03},
                  windows=dict(with_cabs({"rows": [(-2.2, -1.1, 0.8, 1.1, 3.0, 3.55)], "glass": GLASS}, [(-0.9, 1, 0.1), (-3.5, -1, 0.1)], 3.1, 3.62),
                               front_style="hood"), hood_doors=[-2.85],      # 運転室の側面: 窓1つと乗務員扉
                  track_points=[{"part": "$bogie_front", "pivot_x": 0.0, "pivot_y": 0.525, "pivot_z": 3.6},
                                {"part": "$bogie_rear", "pivot_x": 0.0, "pivot_y": 0.525, "pivot_z": -5.0}], wheel_r=0.525,
                  powered=True, source="fuel", acc=0.014, mass=2.8, kmh=85, hp=400.0, driver_x=0.7, driver_z=-1.8, seat_y=1.4,
                  seats=[(-0.7, -2.6)], smoke=[{"x": 0.0, "y": 3.15, "z": 3.0, "type": "smoke", "rate": 0.6, "idle_rate": 0.08}]))
    # ---- 気動車
    for id, name, liv in (("jnr_kiha58", "キハ58形気動車(1961年・急行色)", {"base": RED11, "bands": [(fl + 0.75, fl + 1.95, CREAM4)], "roof": ROOF}),
                          ("jnr_kiha40", "キハ40形気動車(1977年・首都圏色)", {"base": SHU5, "roof": ROOF})):
        w = with_cabs({"rows": [(-9.4, 9.4, 0.75, 1.0, fl + 0.9, fl + 1.75)], "doors": [(-9.6, 0.9), (9.6, 0.9)], "glass": GLASS},
                      cabs(21.3, True, True), fl + 1.0, fl + 1.9)
        w["front_style"] = "kantsu"                  # 貫通形の前面(左右の窓と中央の貫通扉)
        V.append(car(id, name, 21.3, 2.9, 3.95, liv, w, 7.15, front="round", rear="round", powered=True, source="fuel", acc=0.03,
                     lamp_style="roof" if id == "jnr_kiha58" else "top2",
                     kmh=95, roof_units=[], smoke=[{"x": 0.6, "y": 4.1, "z": 3.0, "type": "smoke", "rate": 0.35, "idle_rate": 0.05}]))
    # ---- 電車(先頭車=運転台のある車両が動力車。中間車は動力を持たない。「編成の加速」の注意を参照)
    def series(prefix, label, L, W, H, liv, win, cars, cab_front, kmh, cab_acc, roof_units=(), single=False, cab_y=(None, None), cab_inset=0.0,
               cab_depth=0.6, extra=None, cab_xmax=None, cab_side_glass=None, pillars=None):
        for cid, cname, kind in cars:
            w = win
            kw = {}
            if kind in ("cab", "cab_rear"):
                rear = kind == "cab_rear"
                w = with_cabs(win, cabs(L, not rear, rear, cab_inset, cab_depth), *cab_y)
                if cab_xmax:
                    w["cab_xmax"] = cab_xmax
                if cab_side_glass and not rear:
                    w["sk0_side"] = cab_side_glass
                if pillars and not rear:
                    w["pillars"], w["cab_split"] = pillars, True
                kw = dict(front=cab_front if not rear else "flat", rear=cab_front if rear else "flat", powered=True, source="electric",
                          motor=False, driver_z=(L / 2 - 1.6) if not rear else (-L / 2 + 1.6))
            elif kind in ("panto", "motor"):
                kw = dict(motor=True, source="electric", acc=cab_acc)
            panto = [(L / 2 - 5.0, single)] if kind == "panto" else []
            kw.update(extra or {})
            V.append(car(f"{prefix}_{cid}", f"{label} {cname}", L, W, H, liv, w, (L - 6.2) / 2, kmh=kmh, pantographs=panto,
                         roof_units=[(z, 2.0) for z in roof_units], **kw))
    win103 = win_commuter(20.0, fl)
    # 103系(低運転台)の前面: 貫通扉はなく、2本の柱で3枚に仕切った1続きの窓(前面からわずかに窪み、上ほど奥へ入る)。
    # 中央の窓の上部に行先表示器、助士席側の窓の上部に運行番号表示器(表示なし)。前照灯は屋根の前端の中央のケース
    win103["front_style"] = "103"
    # 断面: 側面と裾は角張り、屋根だけが丸い(軒はレール面上約3.25m)。屋根上: 中央の冷房装置の前後に、
    # グローブ形通風器を3個ずつ等間隔に(中心寄りの2個は冷房装置のすぐ近く。接してはいない)
    vents103 = [s * z for s in (1, -1) for z in (1.65, 4.95, 8.25)]
    series("jnr_103", "103系", 20.0, 2.8, 3.93, {"base": URUGUISU, "roof": ROOF, "roof_h": 3.93 - (fl + 2.1)}, win103,
           [("kuha103", "クハ103形(制御車)", "cab"), ("moha103", "モハ103形(電動車・パンタグラフ付き)", "panto"),
            ("moha102", "モハ102形(電動車)", "motor"), ("saha103", "サハ103形(付随車)", "plain")], "103", 100, 0.03, roof_units=(0.0,),
           cab_y=(fl + 0.92, fl + 1.85), extra=dict(p=("roof", (fl + 2.1 - (fl - 0.15)) / (3.93 - (fl - 0.15)), 2.0), roof_vents_z=vents103),
           cab_xmax=1.2 / 1.4)
    series("jr_e231", "E231系", 20.0, 2.95, 3.9, {"base": STAINLESS, "bands": [(fl + 0.55, fl + 0.8, YELLOW1)], "roof": (150, 152, 156)},
           win_commuter(20.0, fl, ww=1.6, pitch=2.45), [("kuhae231", "クハE231形(制御車)", "cab"), ("mohae231", "モハE231形(電動車・パンタグラフ付き)", "panto"),
            ("mohae230", "モハE230形(電動車)", "motor"), ("sahae231", "サハE231形(付随車)", "plain")], "slope", 120, 0.03, roof_units=(0.0,), single=True)
    win485 = {"rows": [(-9.8, 9.8, 1.2, 1.55, fl + 0.95, fl + 1.75)], "doors": [(-10.1, 0.75)], "glass": GLASS}
    # 車体の屋根は約3.55m(屋根上の冷房装置の上面が約3.9m)。クハ481の運転室はさらに約0.42m高い(冷房装置の上面と同じくらい)
    series("jnr_485", "485系", 21.0, 2.9, 3.55, {"base": CREAM4, "bands": [(fl - 0.1, fl + 0.12, RED2), (fl + 0.85, fl + 1.85, RED2, 3.7)], "roof": ROOF,
                                                       "roof_h": 0.25,
                                                       "whisker": RED2},
           win485, [("kuha481", "クハ481形(制御車・ボンネット)", "cab"), ("moha485", "モハ485形(電動車・パンタグラフ付き)", "panto"),
                    ("moha484", "モハ484形(電動車)", "motor"), ("saro481", "サロ481形(グリーン車)", "plain"), ("sashi481", "サシ481形(食堂車)", "plain")],
           "bonnet", 120, 0.03, roof_units=(-5.0, 5.0), cab_y=(3.34, 3.86), cab_inset=2.55, cab_depth=0.05,
           cab_side_glass=[(1, 2.6, 2.85, 3.9, 3.38, 3.86, 3.9, 3.86)],   # 前面窓から側面へ回り込む運転室の窓(先端から約3.9mまで)
           # 窓の柱(写真): 前面の中央、前面と側面の境の角、側面の途中。前面2枚・角2枚・側面2枚に分かれる
           pillars=[("ax", 0.0, 0.035), ("ax", 0.97, 1.05), ("d", 3.28, 3.34)])   # 前面窓(写真): 先端から約2.55〜2.85m・高さ約3.34〜3.86m
    # ---- 新幹線
    fls = 1.3
    # 0系(初期の大窓車)。寸法・窓配置は日本車輌の公開図面(21形)による: 全長25,150mm、幅3,380mm、高さ3,975mm、
    # 台車中心は先端から3,900mm・後端から3,750mm(中心間17,500mm)。大窓は幅約1.45m・間隔約1.9mで、窓の高さは床上約0.86〜1.46m
    win_big = lambda z0, z1: (z0, z1, 1.42, 1.87, fls + 0.86, fls + 1.46)
    # 中間車は、日本車輌の編成図(0系2000番代 Yk8編成)の側面による小窓(幅約0.6m・間隔約0.99m、車端から約2.6〜23.0m、高さ約2.18〜2.82m)
    win0 = {"rows": [(-12.5 + 2.55, 12.5 - 2.0, 0.6, 0.99, fls + 0.88, fls + 1.52)], "doors": [(-11.8, 0.7), (11.8, 0.7)], "glass": GLASS,
            "win_r": 0.1, "win_frame": 0.035}
    # 塗装(日本車輌の編成図 tec0-yk8 の側面・前面による。高さはレール面から):
    #   裾の青 約0.63〜1.0m(前面も1.0mより下は青、排障器も青)、窓帯 約1.97〜2.96m(先頭車は先端から約3.25mの所で丸く始まる)、
    #   屋根の肩の細い青線 約3.40〜3.50m(先頭車は先端から約4.9mより後ろ)、鼻の先端に丸い光前頭
    sk0_liv = {"base": IVORY, "bands": [(0.0, 0.98, BLUE0), (1.97, 2.96, BLUE0, 3.25, True), (3.40, 3.50, BLUE0, 4.9)],
               "roof": (205, 205, 210), "plow": BLUE0, "nose_light": (1.67, 0.55, (226, 232, 242))}
    for cid, cname, kind in (("21", "21形(先頭車)", "cab"), ("22", "22形(先頭車・パンタグラフ付き。編成の反対側の端に逆向きに連結)", "cab_rear"),
                             ("25", "25形(普通車)", "plain"), ("26", "26形(普通車・パンタグラフ付き)", "panto"),
                             ("16", "16形(グリーン車・パンタグラフ付き)", "plain"), ("36", "36形(食堂車)", "plain")):
        L = 25.15 if kind.startswith("cab") else 25.0
        kw = {}
        w = win0
        if kind.startswith("cab"):
            # 先頭車はどちらも先頭部を+Z側に作る(運転台の向き=進行方向)。編成の反対側の端では逆向きに連結する
            # 車体の形状は21形の図面による(前面窓は約45°の急な傾斜で先端から約2.1〜2.85m)
            d = lambda x: L / 2 - x
            wy = (fls + 0.88, fls + 1.52)
            # 窓・扉は日本車輌の編成図(0系2000番代 Yk8編成)の1号車(21形)・16号車(22形)の側面による。先端からの距離:
            #   21形: 乗務員扉 約4.3〜4.9m、客用扉 約5.5〜6.2m、窓14枚 約7.0〜20.5m(幅約0.68m・間隔約0.98m)、後部の客用扉 約21.1〜21.8m
            #   22形: 乗務員扉 同上、客用扉 約5.25〜5.95m、窓15枚 約6.8〜21.3m、後部の客用扉 約23.75〜24.45m
            if kind == "cab":
                rows, doors = [(d(7.4 + 13.5 * 0.982), d(7.4 - 0.491), 0.68, 0.982, *wy)], [(d(5.85), 0.7), (d(21.45), 0.7)]
            else:
                rows, doors = [(d(7.18 + 14.5 * 0.985), d(7.18 - 0.4925), 0.68, 0.985, *wy)], [(d(5.6), 0.7), (d(24.1), 0.7)]
            w = {"rows": rows, "doors": doors, "glass": GLASS, "win_r": 0.1, "win_frame": 0.035}
            w = with_cabs(w, [(d(2.35), 1, 0.3)], fls + 1.77, fls + 2.33)     # 前面窓: 先端から約2.1〜2.85m、高さ約3.07〜3.63m
            # 運転台の側窓(編成図): 前面窓から側面へ回り込み、先端から約4.2mまで。高さ約3.03〜3.63m、上端は約3.3mより後ろで
            # 屋根の丸みに沿って下がり後端で約3.45m。前端は前面窓の傾斜に沿う(下端 約2.25m・上端 約2.6m)
            w["sk0_side"] = [(1, 2.25, 2.6, 4.2, 3.03, 3.63, 3.3, 3.45)]
            w["cab_split"] = True
            kw = dict(front="sk0", rear="flat", powered=True, source="electric", acc=0.03, motor=True, driver_z=L / 2 - 4.0)
            if kind == "cab_rear":
                # 編成図では22形もパンタグラフ付き(先頭部と反対側の車端から約0.1〜2.9mにカバー、パンタグラフは約1.5m)
                kw["panto_cover"] = (-L / 2 + 1.5, 2.8, 0.48)
        if not kind.startswith("cab"):
            kw = dict(motor=True, source="electric", acc=0.03)   # 0系は全車が電動車
        # 中間車の窓・扉(編成図の側面による。車端からの距離。-Z側の車端が図の左端)
        e = lambda x: -12.5 + x
        wy = (fls + 0.88, fls + 1.52)
        row = lambda c0, n, pitch, ww: (e(c0 - pitch / 2), e(c0 + (n - 0.5) * pitch), ww, pitch, *wy)
        if cid == "25":
            # 25形: 扉 約0.6〜1.3m・約21.1〜21.8m、窓19枚 約2.1〜20.5m(間隔約0.98m)、後端の約3mは窓なし(便所・洗面所)
            w = dict(win0, rows=[row(2.465, 19, 0.984, 0.68)], doors=[(e(0.95), 0.7), (e(21.45), 0.7)])
        if kind == "panto":
            # 26形(2号車): 扉 約0.7〜1.4m・約24.0〜24.8m、窓 約2.6〜3.3mに1枚、続いて約4.9〜23.2mに19枚(間隔約0.98m)
            # 屋根上: 車端から約0.1〜2.9mにカバー(高さ約0.5m)、パンタグラフはその上(車端から約1.5m)
            w = dict(win0, rows=[row(2.96, 1, 0.984, 0.68), row(5.18, 19, 0.98, 0.68)], doors=[(e(1.05), 0.7), (e(24.4), 0.7)])
            kw["panto_cover"] = (e(1.5), 2.8, 0.48)
        if cid == "16":
            # 16形(グリーン車。編成図で扉の脇に緑のマークがある車両): 扉 約0.4〜1.1m・約24.1〜24.8m、小窓 約2.3〜2.9m、
            # 広い窓17枚 約3.7〜23.2m(幅約0.86m・間隔約1.17m)、扉の脇のグリーン車マーク 約1.6m・約23.6m
            w = dict(win0, rows=[row(2.6, 1, 1.166, 0.57), row(4.085, 17, 1.166, 0.86)], doors=[(e(0.74), 0.7), (e(24.44), 0.7)],
                     marks=[(e(1.55), fls + 1.38, 0.12, (120, 200, 60)), (e(23.6), fls + 1.38, 0.12, (120, 200, 60))])
            kw["panto_cover"] = (e(1.5), 2.8, 0.48)      # 編成図では16形もパンタグラフ付き(26形と同じカバー)
        V.append(car(f"jnr_0_{cid}", f"0系新幹線 {cname}", L, 3.38, 3.975, sk0_liv, w, 8.75, floor=fls, wb=2.5, kmh=210, mass=1.2, body_bot=1.0, side_skirt=0.63,
                     pantographs=[(kw["panto_cover"][0], False)] if "panto_cover" in kw else [], **kw))
    e5_liv = {"base": HIUN, "bands": [(fls + 0.62, fls + 0.74, TSUTSUJI), (fls + 0.74, 9.0, TOKIWA)], "roof": TOKIWA}
    wine5 = {"rows": [(-11.5, 11.5, 0.7, 1.0, fls + 0.95, fls + 1.45)], "doors": [(-11.8, 0.7), (11.8, 0.7)], "glass": (25, 30, 35)}
    for cid, cname, kind in (("e523", "E523形(先頭車)", "cab"), ("e514", "E514形(先頭車・グランクラス。編成の反対側の端に逆向きに連結)", "cab_rear"),
                             ("e526", "E526形(普通車・パンタグラフ付き)", "panto"), ("e525", "E525形(普通車)", "plain"),
                             ("e515", "E515形(グリーン車)", "plain")):
        L = 26.5 if kind.startswith("cab") else 25.0
        kw = {}
        w = wine5
        if kind.startswith("cab"):
            # E5系の前面窓: 長い鼻の付け根の運転台の窓(先端から約5.5〜9.5m)
            w = with_cabs(wine5, [(L / 2 - 9.5, 1, 3.0)], fls + 1.55, 3.5, glass=(20, 24, 28))   # 1枚の曲面ガラス
            kw = dict(front="e5", rear="flat", powered=True, source="electric", motor=False, driver_z=L / 2 - 8.0)
        if not kind.startswith("cab"):
            kw = dict(motor=True, source="electric", acc=0.035)
        V.append(car(f"jr_e5_{cid}", f"E5系新幹線 {cname}", L, 3.35, 3.65, e5_liv, w, 8.75, floor=fls, wb=2.5, kmh=320, mass=1.2,
                     pantographs=[(6.0, True)] if kind == "panto" else [], **kw))
    return V

# ======================================================================== 貨物
def freight():
    V = []
    fl = 1.1
    # 9600形(貨物用の蒸気機関車)
    zf = 8.3; tl = 5.8; tz = zf - 10.5 - tl / 2 - 0.2
    by, br = 2.7, 0.8
    V.append(dict(builder="steam", kind="steam", id="jnr_9600", name="9600形蒸気機関車(1913年)", kmh=65, powered=True, source="steam",
                  notches=False, hud="railcar_throttle", acc=0.012, mass=4.0, hp=500.0, livery={"base": BLACK, "flat": {}}, tier=2,
                  zf=zf, loco_len=10.5, boiler_r=br, boiler_y=by, frame_y=1.15, boiler_len=6.4, chimney_h=0.6, chimney_r=0.3,
                  domes=[-2.6, -4.2], cab_len=2.4, cab_top=3.85, driver_r=0.625, drivers=[zf - d for d in (2.9, 4.3, 5.7, 7.1)],
                  small_wheels=[(zf - 1.3, 0.43)], tender=True, tender_len=tl, tender_z=tz, tender_h=3.1, W=2.9, H=4.05,
                  track_points=[{"pivot_x": 0.0, "pivot_y": 0.5, "pivot_z": round(zf - 4.3, 2)},
                                {"part": "$bogie_rear", "pivot_x": 0.0, "pivot_y": 0.43, "pivot_z": round(tz - tl / 2 + 1.6, 2)}],
                  driver_x=0.7, driver_z=zf - 0.6 - 6.4 + 0.4 - 1.2, seat_y=1.5, seats=[(-0.7, zf - 0.6 - 6.4 + 0.4 - 1.2)],
                  smoke=[{"x": 0.0, "y": round(by + br * 0.85 + 0.7, 2), "z": round(zf - 1.25, 2), "type": "campfire", "rate": 2.5, "idle_rate": 0.25}]))
    def cab_win2(L, flL=1.55):                       # 運転室の側窓だけ(乗務員扉の前。前面窓の回り込みの後ろ)
        return [(L / 2 - 1.45, L / 2 - 0.85, 0.5, 0.6, flL + 1.15, flL + 1.75), (-L / 2 + 0.85, -L / 2 + 1.45, 0.5, 0.6, flL + 1.15, flL + 1.75)]
    def louvers2(L, n=6, flL=1.55):                  # 機器室の側面のよろい戸
        span = L - 6.4
        return [(-span / 2 + k * span / n + 0.25, -span / 2 + (k + 1) * span / n - 0.25, flL + 1.2, flL + 1.85) for k in range(n)]
    def el(id, name, L, livery, bz, pantos, front, rear=None, mid=True, kmh=110, single=False, src="electric", smoke=None, cab_rear=True, acc=0.012, mass=3.5, lamps="top2",
           cab_depth=0.6, rows=None, flip_front=False, cab_y=None, side_glass=None, cab_inset=0.0, **kw):
        flL = 1.55
        rows = [(-L / 2 + 3.0, L / 2 - 3.0, 0.6, 1.5, flL + 1.15, flL + 1.65)] if rows is None else rows
        cy = cab_y or (flL + 1.05, flL + 1.85)
        w = with_cabs({"rows": rows, "glass": GLASS}, cabs(L, True, cab_rear, cab_inset, depth=cab_depth), *cy)
        if side_glass:
            w["sk0_side"] = side_glass
        w["front_style"] = "split"
        if front == "kuno":
            # 前面窓は側面へ少し回り込む(先端から約0.15〜0.8m)。前後の運転台とも
            w["sk0_side"] = [(sg, 0.12, 0.45, 0.8, flL + 1.07, flL + 1.83, 0.8, flL + 1.83) for sg in ((1, -1) if cab_rear else (1,))]
        # パンタグラフ: flip_front なら前(+Z)側のシングルアームの折れる向きを逆に
        pants = [(z, single, bool(flip_front and z > 0)) for z in pantos]
        s = car(id, name, L, 2.85, 3.95, livery, w, bz, floor=flL, front=front, rear=rear or front, mid_bogie=mid, wb=kw.pop("wb", 2.3),
                pantographs=pants, powered=True, source=src, acc=acc, mass=mass, kmh=kmh, hp=450.0,
                seats=[(-0.7, L / 2 - 1.6)], driver_z=L / 2 - 1.6, tier=2, lamp_style=lamps, **kw)
        if smoke: s["smoke"] = smoke
        return s
    # EF66(写真による): 前面窓の下から前へ突き出す鼻(上面は前へ下る傾斜、正面の上部はクリーム色)、屋根のひさし、
    # 鼻の下部の左右の灯具、正面中央の金属の横縞と番号板、鼻の下の灰色のスカート
    V.append(el("jnr_ef66", "EF66形電気機関車(1968年)", 18.2, {"base": BLUE15, "bands": [], "roof": ROOF, "roof_h": 3.95 - 3.84}, 5.8, [6.5, -6.5], "ef66",
                cab_inset=0.75, cab_depth=0.6, wheel_r=0.625, wb=2.8, cab_y=(3.21, 3.81),
                rows=[], side_louvers=louvers2(18.2, 6),                  # 側面は運転室の窓・乗務員扉のほかは窓なし(よろい戸)
                side_glass=[(sg, 0.8, 1.2, 1.75, 3.22, 3.72, 1.75, 3.72) for sg in (1, -1)]))   # 前面窓(後ろへ傾く)から側面へ回り込む窓
    V.append(el("jr_ef210", "EF210形電気機関車(1996年)", 18.3, {"base": JRF_BLUE, "bands": [(1.2, 1.6, GRAY), (2.5, 2.6, (235, 235, 235))], "roof": ROOF},
                5.8, [6.3, -6.3], "kuno", single=True, lamps="low",   # 前面は側面から見てくの字(DF200と同系の形)
                flip_front=True, wheel_r=0.625, wb=2.8, rows=cab_win2(18.3), side_louvers=louvers2(18.3, 6),
                roof_boxes=[(5.45, -5.45, 2.0, 0.38, "roofunit", 0.15, 8)]))   # パンタグラフの間の屋根上の機器箱(側面によろい戸)
    V.append(el("jr_eh500", "EH500形電気機関車(1997年・半車体。2両を背中合わせに連結して使う)", 12.6,
                {"base": DARKRED, "bands": [(1.2, 1.6, GRAY)], "roof": ROOF}, 3.6, [3.7], "slope", rear="flat", mid=False, single=True, cab_rear=False, acc=0.01, mass=2.5, lamps="low", wheel_r=0.625,
                rows=cab_win2(12.6)[:1], side_louvers=louvers2(12.6, 4)))   # 側面は運転室の窓のほかは窓なし(よろい戸)
    V.append(el("jr_df200", "DF200形ディーゼル機関車(1992年)", 19.6, {"base": (185, 38, 44), "bands": [(1.2, 1.6, GRAY)], "roof": (62, 64, 68)},
                5.9, [], "kuno", src="fuel", lamps="low",   # 前面は前照灯の高さで折れるくの字、下にスカートとハの字の排障器
                wheel_r=0.625, wb=2.6, rows=cab_win2(19.6), side_louvers=louvers2(19.6, 6),
                roof_hood=True, roof_vents=False, side_panel=(1.9, 4.0, 1.6, 3.45, (150, 152, 156)),          # 屋根: 前後の運転室の高い所をつなぐ丸い覆い、中央に大型のファン2つ
                smoke=[{"x": 0.0, "y": 4.35, "z": 2.0, "type": "smoke", "rate": 0.6, "idle_rate": 0.08},
                       {"x": 0.0, "y": 4.35, "z": -2.0, "type": "smoke", "rate": 0.6, "idle_rate": 0.08}]))
    # ---- 貨車(動力なし)
    def wagon(id, name, L, W, H, liv, win, bz, **kw):
        kw.setdefault("seats", [(0.6, 0.0)])
        kw.setdefault("mass", 1.5)
        kw.setdefault("kmh", 95)
        return car(id, name, L, W, H, liv, win, bz, floor=fl, **kw)
    brown = (110, 58, 40)
    V.append(wagon("jnr_wa1", "ワ1形有蓋車(明治期)", 5.5, 2.3, 3.0, {"base": (100, 60, 35), "roof": (60, 60, 62)}, {"doors": [(0.0, 1.5)], "door_glass": False}, 1.4,
                   two_axle=True, kmh=45, mass=1.0, tier=1))
    V.append(wagon("jnr_wamu80000", "ワム80000形有蓋車(1960年)", 8.95, 2.8, 3.6, {"base": brown, "roof": (70, 70, 72)},
                   {"doors": [(-1.8, 2.4), (1.8, 2.4)], "door_glass": False}, 2.5, two_axle=True))
    V.append(wagon("jnr_tora45000", "トラ45000形無蓋車(1962年)", 8.1, 2.75, 2.15, {"base": (48, 42, 38), "roof": (90, 70, 50), "roof_h": 0.06}, {}, 2.3,
                   two_axle=True))
    V.append(wagon("jnr_taki43000", "タキ43000形タンク車(1967年)", 13.9, 2.6, 3.75, {"base": BLACK}, {}, 5.0, front="round", rear="round", p=2.0,
                   extra=[lambda m, s: m.add("body", mk.box(0, 1.05, 0, 2.2, 0.3, 13.0, tag="under"))], mass=2.0))
    V.append(wagon("jnr_hoki800", "ホキ800形ホッパ車(1958年)", 11.9, 2.75, 3.3, {"base": (60, 52, 46), "roof": (80, 76, 70), "roof_h": 0.06}, {}, 4.0,
                   extra=[hopper(11.9, fl)], mass=2.0))
    V.append(wagon("jnr_yo8000", "ヨ8000形車掌車(1974年)", 7.2, 2.7, 3.6, {"base": BLACK, "roof": (70, 70, 72)},
                   {"rows": [(-2.6, 2.6, 0.7, 1.3, fl + 1.0, fl + 1.7)], "doors": [(-3.0, 0.7), (3.0, 0.7)], "glass": GLASS}, 1.9,
                   two_axle=True, extra=[decks(7.2, fl)], mass=1.0))
    V.append(wagon("jr_koki106", "コキ106形コンテナ車(1997年・空車)", 20.4, 2.64, 1.35, {"base": GRAY}, {}, 7.0, mass=1.2))
    cols = [(70, 110, 160), (150, 60, 50), (80, 130, 90), (170, 150, 60), (120, 120, 125)]
    s = wagon("jr_koki106_loaded", "コキ106形コンテナ車(1997年・12ftコンテナ5個積み)", 20.4, 2.64, 1.35, {"base": GRAY,
              "flat": {f"ctr{i}": c for i, c in enumerate(cols)}}, {}, 7.0,
              extra=[containers([-7.9, -3.95, 0.0, 3.95, 7.9], cols, 1.35)], mass=2.2)
    V.append(s)
    return V
