"""両パックを生成する。 python build.py [出力先]"""
import json, os, sys, shutil, traceback
sys.path.insert(0, os.path.dirname(__file__))
from japangen import write_vehicle
import catalog
from meshkit import view
from PIL import Image, ImageDraw

OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..", "out")
PACKS = [("japan_passenger_pack", "japanpassenger", catalog.passenger()), ("japan_freight_pack", "japanfreight", catalog.freight())]
report = {}
for folder, ns, specs in PACKS:
    root = os.path.join(OUT, folder)
    shutil.rmtree(root, ignore_errors=True)
    tiles = []
    for spec in specs:
        try:
            m, rep = write_vehicle(root, ns, spec)
            s = rep["summary"]
            report[f"{ns}:{spec['id']}"] = {"ok": rep["ok"], "issues": len(rep["issues"]), "floating": len(rep["floating"]),
                                            "tris": s.get("triangles", s.get("tris")), "size": s.get("size")}
            arr = view.render_view(m, "iso")
            img = (Image.fromarray(arr) if not isinstance(arr, Image.Image) else arr).convert("RGB")
            img.thumbnail((360, 240))
            tile = Image.new("RGB", (360, 260), (30, 34, 40)); tile.paste(img, (0, 0))
            ImageDraw.Draw(tile).text((4, 244), spec["id"], fill=(255, 255, 255))
            tiles.append(tile)
        except Exception as e:
            traceback.print_exc()
            report[f"{ns}:{spec['id']}"] = {"error": str(e)}
    if not tiles:
        continue
    cols = 5
    rows = (len(tiles) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * 360, rows * 260), (20, 20, 24))
    for i, t in enumerate(tiles):
        sheet.paste(t, ((i % cols) * 360, (i // cols) * 260))
    sheet.save(os.path.join(OUT, f"{folder}_sheet.png"))
json.dump(report, open(os.path.join(OUT, "report.json"), "w"), ensure_ascii=False, indent=1)
bad = {k: v for k, v in report.items() if "error" in v or not v.get("ok") or v.get("floating")}
print("vehicles:", len(report), "| problems:", len(bad))
for k, v in bad.items():
    print(" ", k, v)
