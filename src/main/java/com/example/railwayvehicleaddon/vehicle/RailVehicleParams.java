package com.example.railwayvehicleaddon.vehicle;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * 鉄道車両固有の設定。前提MODの車両JSONに "rail" オブジェクトとして書く
 * (前提MODのJSON読み込みは未知のキーを無視するため、同じファイルに同居できる)。
 * 加速の追従度・最高速度・スロットル変化の速さは、前提MODの共通項目
 * (acceleration・max_speed・throttle_up_down)をそのまま使う。
 *
 * <pre>
 * "rail": {
 *   "bogies": [
 *     { "part": "$bogie_front", "pivot_x": 0.0, "pivot_y": 0.45, "pivot_z": 4.5 },
 *     { "part": "$bogie_rear",  "pivot_x": 0.0, "pivot_y": 0.45, "pivot_z": -4.5 }
 *   ],
 *   "brake": 0.015,
 *   "resistance": 0.0003,
 *   "grade_gravity": 0.04,
 *   "power_notches": 7,
 *   "reverse_notches": 2,
 *   "mass": 1.0,
 *   "power_source": "fuel"
 * }
 * </pre>
 *
 * @param bogies         台車。pivot_zが最大のものを前台車、最小のものを後台車として線路に載せる
 * @param brake          ブレーキ時の減速度(ブロック/tick²)
 * @param resistance     惰行時の走行抵抗による減速度(ブロック/tick²)
 * @param gradeGravity   勾配で車両にかかる重力加速度(ブロック/tick²)。勾配を掛けた分が加減速になる
 * @param powerNotches   前進側のノッチ数。前進・後進とも0ならノッチ式を使わず、前提MODと同じスロットル式になる
 * @param reverseNotches 後進側のノッチ数
 * @param mass           重さ(編成の加速に使う予定。単車では加速に影響しない)
 * @param powerSource    動力: "fuel"(前提MODの燃料)、"steam"(石炭・木炭と水)、"electric"(架線から給電)
 */
public record RailVehicleParams(List<Bogie> bogies, float brake, float resistance, float gradeGravity,
								int powerNotches, int reverseNotches, float mass, String powerSource) {

	public static final String FUEL = "fuel";
	public static final String STEAM = "steam";
	public static final String ELECTRIC = "electric";

	public static final RailVehicleParams DEFAULT = new RailVehicleParams(List.of(), 0.015f, 0.0003f, 0.04f, 0, 0, 1.0f, FUEL);

	/** ノッチ式か(前進・後進どちらかのノッチ数が1以上)。 */
	public boolean usesNotches() {
		return this.powerNotches > 0 || this.reverseNotches > 0;
	}

	/** ノッチ位置(正=前進、負=後進)に対応するスロットルの目標値。ノッチ数で等分する。 */
	public float notchTarget(int notch) {
		if (notch > 0 && this.powerNotches > 0) {
			return (float) notch / this.powerNotches;
		}
		if (notch < 0 && this.reverseNotches > 0) {
			return (float) notch / this.reverseNotches;
		}
		return 0f;
	}

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
			Codec.FLOAT.optionalFieldOf("brake", DEFAULT.brake).forGetter(RailVehicleParams::brake),
			Codec.FLOAT.optionalFieldOf("resistance", DEFAULT.resistance).forGetter(RailVehicleParams::resistance),
			Codec.FLOAT.optionalFieldOf("grade_gravity", DEFAULT.gradeGravity).forGetter(RailVehicleParams::gradeGravity),
			Codec.INT.optionalFieldOf("power_notches", 0).forGetter(RailVehicleParams::powerNotches),
			Codec.INT.optionalFieldOf("reverse_notches", 0).forGetter(RailVehicleParams::reverseNotches),
			Codec.FLOAT.optionalFieldOf("mass", 1.0f).forGetter(RailVehicleParams::mass),
			Codec.STRING.optionalFieldOf("power_source", FUEL).forGetter(RailVehicleParams::powerSource)
	).apply(instance, RailVehicleParams::new));
}
