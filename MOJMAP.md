# Mojang マッピング(MojMap)への移行

前提MOD(tudursvehiclemod)が移植性のために Yarn から Mojang の公式マッピングへ移ったのに合わせて、
このアドオンも Mojang マッピングへ移しました。**動作は Yarn 版と同じ**です(下の「確認」)。

## 変わったところ

- `build.gradle`: `mappings loom.officialMojangMappings()`。jar の名前に環境を付けました
  (`railwayvehicleaddon-<mod_version>-fabric-1.21.11.jar`。fabric.mod.json の版は `mod_version` のまま)。
- `gradle.properties`: `yarn_mappings` を削除し、`mod_loader=fabric` を追加。前提MODは
  `1.1.1-fabric-1.21.11`(tudursvehiclemod-mojmap の Maven の版)。
- `fabric.mod.json`: 前提MODを `>=1.1.1`(Mojang マッピング版の最初の版)にしました。
- `src/**/*.java`: Loom の `migrateClientMappings --mappings net.minecraft:mappings:1.21.11` で、
  クラス・メソッド・フィールド・引数の名前を Mojang 名に変換(main と client を一緒に変換)。
  変換できなかったのは `PoseStack.peek()` → `last()` の3か所だけで、手で直しました
  (`DeviceOverlayRenderer`、`FeatureRenderer`)。
- ドキュメント中のクラス名(PersistentState → SavedData など)。

ローカル変数名・コメント・文字列・JSON・リソースは変えていません。保存データの形式も同じなので、
Yarn 版で作ったワールドをそのまま読めます。

## 確認

前提MODの `tools/mojmap/compare_jars.py` で、Yarn 版と Mojang 版の jar を intermediary の名前の段階で
1メンバーずつ比べました。

```
199 classes; forwarding methods added: 0; problems: 0
```

(全クラス・全メソッドの呼び出し先・フィールド参照・定数が一致。違うのは
デバッグ情報のローカル変数名だけ。)

## Forge 1.20.1 版

Forge 1.20.1 版はこの Mojang 版から機械的に生成します。別プロジェクト `railwayvehicleaddon-forge` の
`PORTING.md` を見てください。このプロジェクトのコードを変えたら、そちらで
`python tools/port/port.py generate --mojmap <このプロジェクト>` を実行します。
