package com.example.railwayvehicleaddon.vehicle;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * 鉄道車両固有の設定。前提MODの車両JSONに "rail" オブジェクトとして書く
 * (前提MOD側のJSON読み込みは未知のキーを無視するため、同じファイルに同居できる)。
 *
 * <pre>
 * "rail": {
 *   "bogies": [
 *     { "part": "$bogie_front", "pivot_x": 0.0, "pivot_y": 0.45, "pivot_z": 4.5 },
 *     { "part": "$bogie_rear",  "pivot_x": 0.0, "pivot_y": 0.45, "pivot_z": -4.5 }
 *   ],
 *   "traction": 0.004,
 *   "brake": 0.015,
 *   "resistance": 0.0003,
 *   "grade_gravity": 0.04
 * }
 * </pre>
 *
 * @param bogies       台車。pivot_zが最大のものを前台車、最小のものを後台車として線路に載せる
 * @param traction     スロットル100%時の加速度(ブロック/tick²)
 * @param brake        ブレーキ時の減速度(ブロック/tick²)
 * @param resistance   惰行時の走行抵抗による減速度(ブロック/tick²)
 * @param gradeGravity 勾配で車両にかかる重力加速度(ブロック/tick²)。勾配を掛けた分が加減速になる
 */
public record RailVehicleParams(List<Bogie> bogies, float traction, float brake, float resistance, float gradeGravity) {

	public static final RailVehicleParams DEFAULT = new RailVehicleParams(List.of(), 0.004f, 0.015f, 0.0003f, 0.04f);

	/**
	 * @param part   台車のOBJグループ名(省略可。省略すると線路への載せ位置の指定だけに使われ、表示上は回転しない)
	 * @param pivotX 回転中心(モデル座標)
	 * @param pivotY 回転中心(モデル座標)
	 * @param pivotZ 回転中心(モデル座標)。scaleを掛けた値が線路上の載せ位置になる
	 */
	public record Bogie(String part, float pivotX, float pivotY, float pivotZ) {
		public static final Codec<Bogie> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.STRING.optionalFieldOf("part", "").forGetter(Bogie::part),
				Codec.FLOAT.optionalFieldOf("pivot_x", 0.0f).forGetter(Bogie::pivotX),
				Codec.FLOAT.optionalFieldOf("pivot_y", 0.0f).forGetter(Bogie::pivotY),
				Codec.FLOAT.fieldOf("pivot_z").forGetter(Bogie::pivotZ)
		).apply(instance, Bogie::new));
	}

	public static final Codec<RailVehicleParams> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Bogie.CODEC.listOf().optionalFieldOf("bogies", List.of()).forGetter(RailVehicleParams::bogies),
			Codec.FLOAT.optionalFieldOf("traction", DEFAULT.traction).forGetter(RailVehicleParams::traction),
			Codec.FLOAT.optionalFieldOf("brake", DEFAULT.brake).forGetter(RailVehicleParams::brake),
			Codec.FLOAT.optionalFieldOf("resistance", DEFAULT.resistance).forGetter(RailVehicleParams::resistance),
			Codec.FLOAT.optionalFieldOf("grade_gravity", DEFAULT.gradeGravity).forGetter(RailVehicleParams::gradeGravity)
	).apply(instance, RailVehicleParams::new));
}
