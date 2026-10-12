"""座席(乗員)の位置の確認用。車体の外板と屋根を外した断面図に、前提MODと同じ姿勢・縮尺(0.7倍)の乗員を座席の位置に描く。
    PYTHONPATH=<meshkit> python seat_preview.py <パックの出力先> <車両ID> [出力.png]
機体JSONの seats(offset_x/y/z)をそのまま使うので、パックを生成した後の確認に使える。"""
import json, os, sys, glob
import numpy as np
import meshkit as mk
from meshkit import view
from meshkit.render import rider
from PIL import Image

root, vid = sys.argv[1], sys.argv[2]
out = sys.argv[3] if len(sys.argv) > 3 else f"seats_{vid}.png"
VIEWS = sys.argv[4].split(",") if len(sys.argv) > 4 else ("top", "iso")
jpath = glob.glob(os.path.join(root, "*", "data", "*", "vehicles", vid + ".json"))[0]
j = json.load(open(jpath, encoding="utf-8"))
ns_root = jpath.split(os.sep + "data" + os.sep)[0]
ns = j["model"].split(":")[0]
m = mk.load(os.path.join(ns_root, "assets", ns, "models", "obj", vid + ".obj"))
# 外板(body の最初の2シェル=前後半分の車体)と屋根上の機器を外して中を見せる
body = m.parts["body"]
H = max(np.array(sh.verts)[:, 1].max() for sh in body.shells[:2])
body.shells = [sh for k, sh in enumerate(body.shells) if k >= 2 and np.array(sh.verts)[:, 1].min() < H - 0.5]
for name in list(m.parts):
    if name.startswith("$tender"):
        pass
solid = []
for st in j["seats"]:
    solid += rider((st["offset_x"], st["offset_y"], st["offset_z"]), scale=0.7,
                   suit=(200, 60, 40) if st.get("driver") else (60, 90, 200))
ims = []
for v in VIEWS:
    a = view.render_view(m, v, W=1200, H=520, mode="shade", extra_solid=solid, refs=False)
    ims.append(Image.fromarray(a) if not isinstance(a, Image.Image) else a)
sheet = Image.new("RGB", (1200, 520 * len(ims)))
for i, im in enumerate(ims):
    sheet.paste(im, (0, i * 520))
sheet.save(out)
print(out, len(j["seats"]), "seats")
