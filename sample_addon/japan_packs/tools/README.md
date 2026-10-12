# japangen(パック生成スクリプト)

日本旅客パック・日本貨物パックを、meshkit(Blender不要)で生成するスクリプトです。

```
PYTHONPATH=<meshkitのフォルダ> python build.py [出力先]
```

- `japangen.py`: 部品(台車・パンタグラフ・車体の断面ロフト・先頭形状)、塗装(帯・窓・扉・前面窓)、機体JSONの書き出し
- `catalog.py`: 車種ごとの寸法・塗色・窓配置・台車位置・排煙の設定。車両を足すときはここに追加します
- `build.py`: 両パックを生成し、meshkitの検査結果(`report.json`)と一覧画像(`*_sheet.png`)を出力します

meshkitは0.3.0以降(https://github.com/Tuduraw/Meshkit の最新)が必要です。汚し・墨入れ・配線の書き込みに `meshkit.detail` を使います。

寸法は実寸(1:1)、座標は +X=左・+Y=上・+Z=前、原点はレール面の中心です。

## 座席の確認(seat_preview.py)

```
PYTHONPATH=<meshkitのフォルダ> python seat_preview.py <パックの出力先> <車両ID> [出力.png]
```

外板と屋根を外した車内に、前提MODと同じ姿勢・縮尺(0.7倍)の乗員を機体JSONの座席位置に描きます(運転士は赤、乗客は青)。
座席の高さは「座面(床から0.45m)に腰が載る」位置(足元=座面-0.49m)で、`japangen.py` の `rider_y()` で計算しています。
