"""パックのREADMEを生成する(収録車両の表は生成済みのJSONから作る)。 python make_readmes.py [出力先]"""
import sys, json, glob, os
sys.path.insert(0, os.path.dirname(__file__))
import catalog
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..", "out")

def recommended_radius(rail, margin=0.3):
    """車体が曲線の内外へはみ出す量が margin(m)以内に収まる最小半径(5刻みで切り上げ)。
    台車間の中央が内側へ寄る量 a^2/8R と、車端が外側へ振り出す量 ((L/2)^2-(a/2)^2)/2R の大きい方で決める。"""
    import math
    zs = [b["pivot_z"] for b in rail["bogies"]]
    a = max(zs) - min(zs)
    half = max(abs(rail["coupler_front"]), abs(rail["coupler_rear"])) - 0.35
    r = max(a * a / (8 * margin), max(0.0, half * half - (a / 2) ** 2) / (2 * margin), 12.0)
    return math.ceil(r / 5) * 5

def table(ns, folder):
    rows = ["| ID | 車両 | 動力 | 長さ(m) | 推奨最小半径 |", "|---|---|---|---|---|"]
    for f in sorted(glob.glob(f'{OUT}/{folder}/data/{ns}/vehicles/*.json')):
        j = json.load(open(f, encoding='utf-8'))
        vid = os.path.basename(f)[:-5]
        rail = j["rail"]
        has_driver = any(s.get("driver") for s in j["seats"])
        unit = rail.get("powered", has_driver)
        src = {"steam": "蒸気", "electric": "電気", "fuel": "内燃"}.get(rail.get("power_source"), "") if unit else ("なし(制御車)" if has_driver else "なし")
        L = rail["coupler_front"] - rail["coupler_rear"] - 0.7
        rows.append(f"| `{ns}:{vid}` | {j['display_name']} | {src} | {L:.1f} | {recommended_radius(rail):.0f} |")
    return "\n".join(rows)

COMMON = """## 必要なもの

- tudursvehiclemod 1.0.5 以降
- railwayvehicleaddon(排煙 `rail.smoke` に対応した版。同時に送付した更新を適用したもの)

## 導入

このフォルダ(`{folder}`)を、圧縮せずにそのまま `<ゲームディレクトリ>/tudursvehiclemod-addons/` に置きます
(前提MODの「方法A」。有効化の操作は不要です)。

## 共通の注意

- **実寸(1:1)** で作っています(1ブロック=1m)。20〜26mの車両はカーブで大きくはみ出すため、線路は半径60ブロック以上を
  推奨します(設定の `min_curve_radius` を上げてください)。縮小したい場合は機体JSONの `scale` を変えます
  (台車位置・連結器の位置も自動で縮みます)
- **軌間**は鉄道模型と同じ考え方で、線路の軌間(既定1.435)を基準にしています。在来線の車両も新幹線も同じ線路を走り、
  車体は実寸のままです(在来線の車両は、実物より軌間が広く見えます)
- **推奨最小半径**: 車体が曲線ではみ出す量が0.3m以内に収まる半径です(下の表)。測量ツールの曲線半径のしきい値
  (既定: →・←キーで5ブロック刻み、Rで既定値に戻す)を、走らせる車両の値に合わせてから敷設してください
- **動力車と総括制御**: 電車・新幹線は、実物と同じく電動車(モハ等)が動力車、運転台のある制御車(クハ等)は動力なしです。
  運転台でのノッチ操作が編成のすべての動力車に伝わります(総括制御)。動力車が1両もない編成では、運転しようとすると
  警告が出ます
- **先頭車はすべて先頭部を前(+Z)に向けて作っています。** 編成の反対側の端には、同じ形式(0系なら22形)を
  **逆向きに**連結してください(連結した向きのまま固定されます)
- **架線の高さ**: 既定の5.0m(設定 `catenary_height`)がこのパックの車両に合わせた値です。敷設時に ↑・↓キーで
  0.5ブロック刻みに変えられます(Rで既定値に戻す)
- 番号・ロゴ・社紋は描いていません。同じ形式が編成内に複数ある場合は、同じ車両を必要な数だけ並べます
"""

def write_passenger():
    n = len(catalog.passenger())
    text = f"""# 日本旅客パック(japan_passenger_pack)

明治の開業時から現在までの、主要な旅客車両を収録したアドオンパックです。名前空間は `japanpassenger`。
フル編成を組むのに必要な形式(先頭車・中間車・パンタグラフ付きの電動車・グリーン車・食堂車など)をすべて収録しており、
間引いた短い編成にもできます。

{COMMON.format(folder='japan_passenger_pack')}
## 収録車両({n}形式)

{table('japanpassenger', 'japan_passenger_pack')}

## 編成例(実車の号車順を簡略化したもの。「逆」は逆向きに連結)

| 列車 | 編成 |
|---|---|
| 明治の列車 | 150形 + 二軸客車 ×3〜5 |
| 旧型客車の急行 | C62形 または EF58形 + スハ43形 ×6〜10(古い時代はオハ31形) |
| ブルートレイン | EF65形 または EF81形 + オハネ25形 ×n + オハネフ25形(緩急車。車掌室側を後ろに) |
| 103系(10両) | クハ103 + モハ103 + モハ102 + サハ103 + モハ103 + モハ102 + サハ103 + モハ103 + モハ102 + クハ103(逆) |
| 103系(間引き4両) | クハ103 + モハ103 + モハ102 + クハ103(逆) |
| E231系(10両) | クハE231 + モハE231 + モハE230 + サハE231 + サハE231 + モハE231 + モハE230 + サハE231 + サハE231 + クハE231(逆) |
| 485系(8両) | クハ481 + モハ485 + モハ484 + サロ481 + サシ481 + モハ485 + モハ484 + クハ481(逆) |
| 0系(16両) | 21 + (26 + 25) ×3 + 16 + 36 + (26 + 25) ×2 + 26 + 25 + 22(逆) |
| 0系(間引き6両) | 21 + 26 + 25 + 16 + 26 + 22(逆) |
| E5系(10両) | E523 + E526 + E525 + E526 + E525 + E526 + E525 + E526 + E515 + E514(逆) |
| 気動車 | キハ58形 / キハ40形(1両でも、複数つないでも走れる) |

## 排煙

蒸気機関車(5形式)は煙突から煙を出します(運転中・走行中のみ。出力が大きいほど多くなります)。
DD51形・DE10形・キハ58形・キハ40形は控えめな排気煙を出します。設定は機体JSONの `rail.smoke` です。

## 運転

- 蒸気機関車は連続スロットル式で、石炭(木炭)を車両のインベントリに入れて走ります。水は給水塔で補給します
- 電気機関車・電車・新幹線は前進5・後進1ノッチ。電化区間と変電所が必要です。電車・新幹線は制御車(クハ・21形など)の
  運転台で運転し、編成に電動車(モハ・26形など)を必ず入れます
- ディーゼル機関車・気動車は前進5・後進1ノッチ。前提MODの燃料を使います
"""
    open(f'{OUT}/japan_passenger_pack/README.md', 'w', encoding='utf-8').write(text)

def write_freight():
    n = len(catalog.freight())
    text = f"""# 日本貨物パック(japan_freight_pack)

明治から現在までの、主要な貨物車両を収録したアドオンパックです。名前空間は `japanfreight`。
日本旅客パックと重複する機関車(D51形・DD51形・DE10形・EF65形・EF81形など)は収録せず、日本旅客パックのものを使う
前提です(両方のパックを導入してください)。

{COMMON.format(folder='japan_freight_pack')}
## 収録車両({n}形式)

{table('japanfreight', 'japan_freight_pack')}

## 編成例

| 列車 | 編成 |
|---|---|
| 明治の貨物列車 | 150形(旅客パック) + ワ1形 ×n |
| 蒸気機関車の貨物列車 | 9600形 または D51形(旅客パック) + ワム80000形・トラ45000形 ×n + ヨ8000形 |
| コンテナ列車 | EF66形・EF210形 + コキ106形 ×n(空車/12ftコンテナ5個積みの2種類) |
| EH500形の列車 | EH500形 + EH500形(逆) + コキ106形 ×n |
| 石油列車 | DF200形 または DD51形(旅客パック) + タキ43000形 ×n |
| 工事列車 | DE10形(旅客パック) + ホキ800形 ×n |

## 現状の制限

- **EH500形**は2車体の機関車のため、1車体分を1形式として収録しています。2両を背中合わせ(2両目は逆向き)に連結します
- **コンテナの積み下ろし**には対応していません。コキ106形は「空車」と「12ftコンテナ5個積み」を別形式で収録しています
  (コンテナの扱いは、別紙「コンテナ取り扱いの実装案」で検討中です)
- 無蓋車・ホッパ車は、荷台の中が見えない形(上面を荷台の色で塗った箱)です
"""
    open(f'{OUT}/japan_freight_pack/README.md', 'w', encoding='utf-8').write(text)

write_passenger(); write_freight(); print("readmes ok")
